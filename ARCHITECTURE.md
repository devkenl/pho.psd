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
- Sharing: duplicating a layer shares its raster, and history snapshots share everything.
- **Copy-on-write (M4)**: `TiledRaster.derive()` makes an editable raster that shares every tile with a frozen source; a tile is cloned the first time it is written (`writableTile`). Identical solid tiles (e.g. "fully selected") share one immutable array (`SolidTiles`). `MipChain.derive()` rebuilds only the dirty rectangle of each reduced level, also copy-on-write. A brush stroke therefore costs time and memory proportional to what it touched, and the undo history keeps the old raster for free.
- `MipChain`: 2× box-filtered levels averaged in premultiplied space (avoids dark fringes at alpha edges), built once at import. Used for previews and thumbnails.
- Memory: tiles live on the Java heap (`android:largeHeap`). `ImportBudget` decides the largest decode that leaves 40% headroom, and oversized images are decoded smaller with a user-visible warning. Moving tiles to native/disk-backed storage is an M7 item and needs measurements first.

## Compositing
- CPU reference compositor (`render/Compositor.kt`). It works per output pixel, maps to each layer via the inverse affine, samples bilinearly (or nearest at ≥200% zoom) from the appropriate mip level, and accumulates in **float premultiplied** space.
- Blend modes use the W3C Compositing Level 1 formulas: separable B(Cb,Cs) on un-premultiplied colour, combined with source-over (`co = (1−αb)cs + (1−αs)cb + αsαb·B`). Non-separable Hue/Saturation/Color/Luminosity use SetLum/SetSat/ClipColor. Soft Light uses the W3C curve, which differs slightly from some desktop editors (documented).
- Maths runs on sRGB-encoded values, the conventional desktop behaviour for layer blending. Linear-light processing will be chosen per operation for adjustments (M5) and documented there.
- **Preview = export maths.** The same compositor produces previews and exports (`ExportRenderer` streams row bands via `Compositor.renderRows`). Only the output resolution and sampling differ.
- The compositor is stateless and renders disjoint row bands concurrently. `PreviewRenderer` splits renders across up to 8 cores and honours cancellation.

## Preview strategy (M2 foundation for M7 tiling)
1. **Base preview**: the whole document at about 1.25× the canvas's long edge (capped at 3072 px). It is re-rendered only when the document changes. Pan, zoom and rotate just redraw the bitmap through a matrix, at no rendering cost.
2. **Detail patch**: when zoomed beyond the base's pixel density, the visible rectangle is rendered at screen resolution 140 ms after the view settles. Nearest-neighbour is used at ≥200% for pixel-accurate inspection.
3. While a document edit is being dragged, the base renders at 1280 px. It returns to full quality on release.
4. **Live strokes (M4)**: during a stroke the editor's `renderLookup` returns the in-progress (unfrozen) raster for the painted layer or mask; each dab reports a document-space dirty rectangle. `PreviewRenderer.patch` re-renders only that rectangle of the base and detail bitmaps, on exactly the frame's pixel grid, so patches are seamless; the bitmap is updated on the main thread. On commit the base re-renders in the background (with proper mip levels), and the detail frame is kept because it already shows the committed pixels.
Flows use `collectLatest`, so stale renders are cancelled. M7 replaces this with per-layer cached tiles, dirty rectangles and a GPU backend.

## State, commands and undo
- `EditorController` (`:core:editor`) owns `StateFlow<EditorState>`. Every mutation goes through validated pure functions in `DocumentEdits`, which return `Applied(doc)` or `Rejected(reason)`. Rejections become user-visible notices.
- `History` is an immutable list of document snapshots with coalescing keys, so a slider drag is one step. Selecting a layer is not an undo step (`replaceCurrent`). A canvas drag is held as a `LiveTransform` and committed once on release.
- After each commit, rasters unreachable from any history entry (or pending in an in-flight commit) are released (`RasterStore.retainOnly`).
- **History memory budget (M4)**: after pixel edits the controller counts the *distinct* tile arrays referenced by the whole history (identity-deduplicated, all mip levels). Past `historyBudgetBytes` (30 % of the heap on device) the oldest steps are dropped — never the current state — and the user is told.
- **One command system**: `CommandId` (126 ids: 78 implemented, 48 planned) feeds the menu bar, quick strip, header, dock buttons, tool rail and keyboard shortcuts. Unbuilt features carry an `Availability.Planned(milestone, reason)` in the enum itself. A test asserts that no planned command can ever be enabled, and another that every implemented command is handled.
- Platform effects such as file pickers are emitted as `EditorEffect`s and fulfilled by the Activity layer.

## Persistence (M3, masks in M4)
- **Format v2 (M4)** adds layer masks: `"mask": {"raster", "enabled"}` on a layer and the mask raster in the raster table. A file declares `minReaderVersion` 2 only when it contains masks (a v1 reader would silently drop them); files without masks stay readable by 0.3. v1 files load unchanged (`migrate(1)` is the identity).
- **Format**: `.phopsd` v1 is a ZIP archive (`java.util.zip`, so there's no extra dependency). The first entry is a stored `mimetype` (`application/x-phopsd`), followed by `rasters/<id>.pras`, an optional `thumbnail.png`, and `manifest.json` written last so it can record the hashes. The manifest uses a small strict JSON implementation (`io/Json.kt`). A dependency-free parser was chosen over kotlinx.serialization to keep `:core:engine` free of compiler plugins and fully testable offline. The parser rejects duplicate keys, trailing data and excessive nesting rather than guessing.
- **Rasters** (`io/RasterCodec.kt`, "PRAS" v1) store exactly the in-memory representation: premultiplied 8-bit ARGB tiles. Only allocated tiles are written (solid fills cost ~40 bytes). Each tile is passed through a PNG-style per-channel *Sub* predictor and DEFLATE (best speed). Round trips are bit-identical, and a test asserts pixel-identical rendering after reopen.
- **Integrity**: ZIP CRC-32 per entry plus a SHA-256 and byte length per raster in the manifest. `ProjectReader.verify()` checks structure and hashes without decoding pixels (~50 ms for 24 MP on the dev JVM). It runs on every staged save, on the destination after copying, and on every autosave.
- **Compatibility**: `formatVersion` (writer) and `minReaderVersion` (oldest reader that can load it without data loss). Newer files are refused with an "update pho.psd" message. `ProjectFormat.migrate()` is the hook for upgrading older manifests. Unknown archive entries are ignored. An unknown *layer type* is refused (never dropped silently). An unknown *blend mode* falls back to Normal with a warning.
- **Saving via SAF** (`platform/ProjectStorage.kt`): stage to a private temp file, fsync, verify, back up the existing destination, copy, verify the destination, and restore the backup on failure. Saves use an immutable `SaveSnapshot` (document plus the exact mip-chain references) taken at the start, so editing can continue and history pruning can't remove pixels mid-save. Persistable URI permissions are taken so Save and Recents work after restarts.
- **Autosave** (`platform/AutosaveStore.kt`): an app-private single slot. Each write goes to a temp file, is verified and fsync'd, then atomically `rename`d over the previous autosave (same filesystem). At startup a leftover autosave is moved to a separate *recovery* slot, so a new session can never overwrite unrecovered work. The autosave is cleared after a successful save, an explicit discard, or a close with "Don't save".
- **Dirty tracking** compares the current document with the snapshot from the last save/open via `Document.contentKey()`, ignoring the active layer and the selection. Recovered documents have no snapshot and are always considered unsaved.
- **Export** (`platform/ImageExporter.kt`): bands from `ExportRenderer` go into a *non-premultiplied* ARGB_8888 bitmap, so straight-alpha values reach the PNG encoder unrounded. JPEG renders over opaque white. The output header is re-decoded to confirm the dimensions. Exports are sRGB 8-bit (ICC handling is M8).
- **Recents** (`platform/RecentProjects.kt`): JSON plus cached PNG thumbnails in app storage, capped at 20. DataStore/Room were not needed for a list this small.

## Painting, selections and masks (M4)
- **Brush engine** (`paint/BrushStroke.kt`): round dabs along the (optionally smoothed) input path at a spacing that is a fraction of the diameter. Per pixel the stroke keeps a strength `s`; each dab proposes `t = falloff × opacity` and `s` moves towards `t` by `flow`, only upwards. With flow 1 overlaps never exceed the stroke opacity; low flow builds up like an airbrush. Output pixels are recomputed from the *original* pixel and `s`, so results are deterministic and the original raster is never touched. Size is in document pixels and points are mapped through the layer transform, so strokes are correct on scaled/rotated layers. Modes: paint colour, erase, set mask value. Pressure can drive size, opacity and flow.
- **Threading**: strokes run on one dedicated paint thread (input order preserved). Heavy one-off operations (merge, flatten, apply mask) run on the same thread behind a busy indicator, so they never race a stroke.
- **Selections** are a document-sized coverage raster (alpha channel) plus an outline. Shapes are rasterized with non-zero winding, 4 sub-scanlines and exact horizontal span coverage (anti-aliased edges). Add = max, subtract = a·(1−b), intersect = min. Uniform tiles collapse to nothing or a shared solid tile, so "select all" on 50 MP costs almost no memory. The outline is a list of steps (shape+op, or invert) that the UI replays with `android.graphics.Path.op` to draw exact marching ants. Selections are undoable but not saved and do not mark the document unsaved (`Document.contentKey()`).
- **Layer masks** live in the layer's own pixel space (same size as the layer raster), so they move and transform with the layer. The value is the alpha channel of a `TiledRaster` (255 reveals). The compositor multiplies the sampled source by the mask at the same mip level (or, for a live single-level mask, samples its base with scaling — a mask is never silently ignored). Mask-alone (grayscale) and overlay (red) display modes are render-time only. Painting a mask uses the brush engine in mask-value mode with the luminance of the foreground/background colour.
- **Canvas operations are transforms**: crop, canvas size, rotate and flip change the canvas and every layer's `LayerTransform`; image size scales transforms proportionally. Layer pixels are never resampled or deleted, so growing the canvas again restores cropped pixels. Merge down / flatten are the explicit destructive exceptions (undoable, flatten asks first); they render through the same compositor as export.
- **Free transform / crop maths** (`geometry/TransformHandles.kt`) are computed from the drag's start state: scaling keeps the opposite handle (or centre) fixed in document space, proportional scaling projects onto the diagonal, rotation reuses the touch `AngleSnap`.

## Touch rotation & snapping
- Two-finger gestures are handled by `EditorController.beginPinch/updatePinch/endPinch`, not in the UI, so thresholds and snapping are unit-tested. The view starts rotating only after a 12° deliberate twist (6° for a layer).
- `AngleSnap` is magnetic. The *raw* finger angle is accumulated, and the applied angle is the nearest multiple of the step when within a threshold (min(5°, step/3)). Because each update is computed from the raw angle and the gesture's start state, twisting further always breaks free, and nothing drifts or accumulates rounding.
- `LayerPinch` computes the layer transform from the gesture start so the layer point under the initial finger centroid stays under the current centroid while scaling/rotating (scale clamped to 1%–10,000%). The whole gesture commits as one undo step.
- The UI shows an angle readout and fires `HapticFeedbackType.SegmentTick` when a snap engages.

## Import pipeline
`ImageImporter` reads through `ContentResolver` only, so originals are never modified. It decodes with `ImageDecoder`: software allocator, EXIF orientation applied by the platform, target colour space sRGB, and target size from the memory budget. It copies pixels in 1 MP row bands into a `RasterBuilder`, then frees the bitmap and builds mips. All of this runs on `Dispatchers.IO`/`Default`. Errors map to readable messages (damaged file, permission, memory).

## UI decisions
- **Design tokens** come from `docs/UI_DESIGN.md` (`ui/theme/Theme.kt`). Typography is compact (11–15 sp) and uses the system sans. The Material 3 theme is mapped onto the tokens; most controls (slider, buttons, tooltips, menus' content) are custom, for density and to keep Material API churn low.
- **Icons**: Lucide (ISC; Feather-derived icons MIT), converted at build time into Kotlin `ImageVector`s by `tools/lucide2kt.py`. There is no icon font and no runtime parsing. The pho.psd mark and launcher icon are original vectors.
- **Honest UI**: unbuilt features stay visible for discoverability but are dimmed, badged (M3–M8) and explain themselves on tap or hover. The Export button is the primary blue action (as in the reference) since M3.
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
