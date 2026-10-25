# pho.psd — Architecture & Decisions

## Module layout

```
:app            Android application: Compose UI, ViewModel, platform decoding, preview bitmaps
  └─ depends on
:core:editor    Pure Kotlin editor state machine (commands, tools, availability, undo-aware edits,
                viewport control, dialogs). No Android. Unit-tested on the JVM.
  └─ depends on (api)
:core:engine    Pure Kotlin image engine: geometry, tiled rasters, mip chains, document model,
                validated edits, history, CPU reference compositor, blend maths. No Android.
```

The logical modules in `docs/IMAGE_ENGINE.md` (`core-document`, `core-renderer`, `core-io`, …) currently live as **packages** inside `:core:engine` (`model`, `edit`, `raster`, `render`, `geometry`). They will be split into Gradle modules when a second implementation (GPU/native) needs a stable boundary. Feature UI lives under `app/…/ui/{home,editor,components,theme,icons}`.

### Why the engine and editor are pure Kotlin
- They can be compiled and tested on any JVM in seconds. This is what allowed 74 tests to run in a session without Android tooling.
- Compose never sees pixel arrays. Documents hold raster **ids**, so state stays cheap to copy, diff and keep in undo history (IMAGE_ENGINE.md: “Don't keep full-sized bitmap byte arrays in immutable Compose state”).
- A future GPU/NDK backend must reproduce the CPU compositor's golden-test outputs. The CPU path stays as the correctness reference.

## Coordinate spaces (one shared implementation)
- **Screen**: canvas view pixels. **Document**: canvas pixels, where pixel *(i,j)* covers *[i,i+1)×[j,j+1)*. **Layer**: the layer raster's own pixels.
- `Viewport` (zoom, centre, rotation) is the only screen↔document mapping. `LayerTransform` (centre, scale, rotation; pivot at the raster centre) is the only layer↔document mapping. Both compose through the `Affine` class, which uses the `android.graphics.Matrix` layout.
- Tools convert pointer positions through these classes and never keep their own zoom maths. Tests cover round trips under rotation, zoom about a focus point, pan direction and fit.

## Raster storage
- `TiledRaster`: 256×256 tiles of **premultiplied 8-bit ARGB** (`IntArray`). Unwritten tiles cost nothing and read as a fill colour, so a 6000×4000 white background uses 0 bytes. Rasters are **frozen** after creation and are then immutable.
- Sharing: duplicating a layer shares its raster, and history snapshots share everything. Pixel tools (M4) will derive new rasters copy-on-write per tile, so undo stores changed tiles instead of document clones.
- `MipChain`: 2× box-filtered levels averaged in premultiplied space (avoids dark fringes at alpha edges), built once at import. Used for previews and thumbnails.
- Memory: tiles live on the Java heap (`android:largeHeap`). `ImportBudget` decides the largest decode that leaves 40% headroom, and oversized images are decoded smaller with a user-visible warning. Moving tiles to native/disk-backed storage is an M7 item and needs measurements first.

## Compositing
- CPU reference compositor (`render/Compositor.kt`). It works per output pixel, maps to each layer via the inverse affine, samples bilinearly (or nearest at ≥200% zoom) from the appropriate mip level, and accumulates in **float premultiplied** space.
- Blend modes use the W3C Compositing Level 1 formulas: separable B(Cb,Cs) on un-premultiplied colour, combined with source-over (`co = (1−αb)cs + (1−αs)cb + αsαb·B`). Non-separable Hue/Saturation/Color/Luminosity use SetLum/SetSat/ClipColor. Soft Light uses the W3C curve, which differs slightly from some desktop editors (documented).
- Maths runs on sRGB-encoded values, the conventional desktop behaviour for layer blending. Linear-light processing will be chosen per operation for adjustments (M5) and documented there.
- **Preview = export maths.** The same `render()` produces previews and (M3) exports. Only the output resolution and sampling differ.
- The compositor is stateless and renders disjoint row bands concurrently. `PreviewRenderer` splits renders across up to 8 cores and honours cancellation.

## Preview strategy (M2 foundation for M7 tiling)
1. **Base preview**: the whole document at about 1.25× the canvas's long edge (capped at 3072 px). It is re-rendered only when the document changes. Pan, zoom and rotate just redraw the bitmap through a matrix, at no rendering cost.
2. **Detail patch**: when zoomed beyond the base's pixel density, the visible rectangle is rendered at screen resolution 140 ms after the view settles. Nearest-neighbour is used at ≥200% for pixel-accurate inspection.
3. While a document edit is being dragged, the base renders at 1280 px. It returns to full quality on release.
Flows use `collectLatest`, so stale renders are cancelled. M7 replaces this with per-layer cached tiles, dirty rectangles and a GPU backend.

## State, commands and undo
- `EditorController` (`:core:editor`) owns `StateFlow<EditorState>`. Every mutation goes through validated pure functions in `DocumentEdits`, which return `Applied(doc)` or `Rejected(reason)`. Rejections become user-visible notices.
- `History` is an immutable list of document snapshots with coalescing keys, so a slider drag is one step. Selecting a layer is not an undo step (`replaceCurrent`). A canvas drag is held as a `LiveTransform` and committed once on release.
- After each commit, rasters unreachable from any history entry are released (`RasterStore.retainOnly`).
- **One command system**: `CommandId` (105 ids: 37 implemented, 68 planned) feeds the menu bar, quick strip, header, dock buttons, tool rail and keyboard shortcuts. Unbuilt features carry an `Availability.Planned(milestone, reason)` in the enum itself. A test asserts that no planned command can ever be enabled, and another that every implemented command is handled.
- Platform effects such as file pickers are emitted as `EditorEffect`s and fulfilled by the Activity layer.

## Import pipeline
`ImageImporter` reads through `ContentResolver` only, so originals are never modified. It decodes with `ImageDecoder`: software allocator, EXIF orientation applied by the platform, target colour space sRGB, and target size from the memory budget. It copies pixels in 1 MP row bands into a `RasterBuilder`, then frees the bitmap and builds mips. All of this runs on `Dispatchers.IO`/`Default`. Errors map to readable messages (damaged file, permission, memory).

## UI decisions
- **Design tokens** come from `docs/UI_DESIGN.md` (`ui/theme/Theme.kt`). Typography is compact (11–15 sp) and uses the system sans. The Material 3 theme is mapped onto the tokens; most controls (slider, buttons, tooltips, menus' content) are custom, for density and to keep Material API churn low.
- **Icons**: Lucide (ISC; Feather-derived icons MIT), converted at build time into Kotlin `ImageVector`s by `tools/lucide2kt.py`. There is no icon font and no runtime parsing. The pho.psd mark and launcher icon are original vectors.
- **Honest UI**: unbuilt features stay visible for discoverability but are dimmed, badged (M3–M8) and explain themselves on tap or hover. The Export button is styled as unavailable rather than primary-blue until M3.
- **Tablet-only**: `supports-screens` excludes small/normal screens. The layout adapts (the dock hides below 720 dp of width, the rail and strips scroll) rather than using fixed pixel geometry. The activity is resizable and handles configuration changes itself.
- **Input**: one pointer runs the active tool; two pointers pan and zoom (rotation only after a deliberate 12° twist). The mouse wheel zooms at the pointer. Long-press or secondary click opens tool families. Stylus hover and mouse hover show tooltips. Keyboard shortcuts are listed in Help.

## Version and SDK decisions
- AGP 9.4.0 with **built-in Kotlin**, so the app doesn't apply `org.jetbrains.kotlin.android`. The Compose compiler plugin version equals the Kotlin version (2.4.20). Declaring `org.jetbrains.kotlin.jvm` 2.4.20 in the root build pins the Kotlin Gradle Plugin version for both the JVM modules and AGP's built-in Kotlin.
- `minSdk 29` (Android 10): ImageDecoder (API 28) with automatic EXIF orientation, SAF/scoped storage semantics and modern stylus `MotionEvent` data. This covers the professional tablets targeted.
- `compileSdk/targetSdk 37`: the highest level AGP 9.4 supports. The system draws edge-to-edge, and the UI pads with `safeDrawingPadding()`.
- No Navigation library yet: there are two screens, switched by editor state. Add Navigation Compose when screens multiply.
- No NDK yet. CLAUDE.md requires native code only when measurements justify it; the CPU Kotlin reference comes first.

## Third-party components
| Component | Licence | Use |
|---|---|---|
| AndroidX / Jetpack Compose | Apache-2.0 | UI, lifecycle, activity |
| kotlinx.coroutines | Apache-2.0 | Background work |
| Lucide icons (converted vectors) | ISC (+ MIT for Feather-derived icons) | UI icons; see THIRD_PARTY_NOTICES.md |

None of LittleCMS, LibRaw, libvips or Skia-NDK have been adopted. Each needs a separate licence, ABI and memory evaluation before its milestone (M7/M8).
