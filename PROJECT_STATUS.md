# pho.psd — Project Status

_Last updated: 2026-10-25 · version `0.2.0-dev` · scope: Milestones 0, 1 and 2_

## Read this first: what was and was not verified

This iteration was built in a cloud workspace **without access to Google Maven, Maven Central, the Gradle plugin portal or the Android SDK**. That had consequences:

| Check | Result | How |
|---|---|---|
| `:core:engine` + `:core:editor` compile (Kotlin, warnings as errors) | ✅ pass | Kotlin compiler bundled with local Gradle (2.0.21) — `tools/run-core-tests-offline.sh` |
| Core unit tests | ✅ **74 / 74 pass** | JUnit 4.13.2 — same script |
| `:app` type-check against the real AndroidX + Android APIs | ✅ pass, 1 known tooling artifact | `tools/compile-check/` generates Kotlin stubs from androidx `api/current.txt` signature files (androidx commit `53bc2d6`, 2026-10-23) and compiles the app against them plus Android API 37 `android.jar`. The only reported error comes from the stub generator: the stub `ComponentActivity` had to be made abstract. |
| Compose compiler plugin rules | ⚠️ not checked | Stub compile runs without the plugin; composable-context rules were reviewed by hand |
| `./gradlew assembleDebug` | ❌ **not run** | AGP/AndroidX artifacts could not be downloaded here |
| Install / run on emulator or tablet | ❌ **not done** | No SDK, emulator or device was available |
| Screenshot comparison with `design-reference.png` | ❌ not done | Needs a running build |

**Your first `./gradlew assembleDebug` is the first real Android build.** Expect, at most, small API-shape fixes; please report or paste any errors.

## Build commands

```bash
# Full build (needs Android SDK with platform 37 + JDK 17+; AGP 9.4.0 requires Gradle 9.6.0 via the wrapper)
./gradlew assembleDebug
./gradlew :core:engine:test :core:editor:test     # JVM unit tests
./gradlew installDebug                            # on a connected tablet/emulator

# Offline fallbacks used in this session (no Maven access needed)
tools/run-core-tests-offline.sh [GRADLE_HOME]
tools/compile-check/compile_check.sh ANDROIDX_SRC ANDROID_JAR [GRADLE_HOME]   # see tools/compile-check/README.md
```

Toolchain (verified against official release pages on 2026-10-25): AGP **9.4.0** (built-in Kotlin, min Gradle 9.6.0, JDK 17, max API 37) · Kotlin **2.4.20** · Gradle **9.6.0** · Compose BOM **2026.09.00** · activity 1.13.0 · lifecycle 2.11.0 · core 1.19.1 · coroutines 1.11.0. `compileSdk`/`targetSdk` 37, `minSdk` 29 (reasoning in ARCHITECTURE.md).

## Milestone status

### M0 — Bootstrap: **done (pending first Gradle build)**
- Gradle multi-module project: `:app` (Android, Compose), `:core:engine` (pure Kotlin image engine), `:core:editor` (pure Kotlin editor state machine).
- Version catalog, wrapper (9.6.0), manifest (tablet-only `supports-screens`, no storage/network permissions, `largeHeap`), platform theme, original adaptive launcher icon.
- Architecture, status and feature-matrix documents; offline test and type-check tooling.

### M1 — Faithful workspace: **implemented, not yet visually verified on device**
Implemented: home screen (no fake recents); header (back, pho.psd mark, tappable document name with rename/info, unsaved indicator with “edited N min ago”, undo/redo with labels, save, export, preferences, overflow); full **File/Edit/Image/Layer/Select/Filter/View/Window/Help** menu bar with hover-switching dropdowns; **Edit/Adjust/FX/Filters/Layer/Select** quick strip opening command popovers; compact tool rail (15 families, long-press/right-click variants, hover tooltips); canvas; floating contextual tool-options bar; right dock with **Layers | Channels | Paths** tabs and a History panel; status bar.
All of these surfaces dispatch the same `CommandId`s. Unbuilt commands are still shown. They are dimmed, carry a milestone badge (M3–M8) and explain themselves when tapped.

### M2 — Raster core and image import: **implemented, engine verified by tests**
- Open JPEG/PNG/WebP through SAF or the system photo picker → new document at native size. Uses ImageDecoder, EXIF orientation and sRGB conversion; the source colour space is recorded.
- Place image as a new layer, fitted to the canvas non-destructively.
- Raster layers: add empty layer, duplicate, delete, rename, show/hide, lock, reorder (drag handle, long-press drag, menu, shortcuts), opacity, 16 blend modes.
- Move tool (drag, auto-select layer, arrow-key nudge) and numeric X/Y/scale/rotation on the layer transform. Original pixels are never resampled.
- Viewport: pinch zoom, two-finger pan, deliberate two-finger rotate, mouse-wheel zoom, Hand/Zoom tools, Space-drag pan, fit/100%/zoom steps, view rotation.
- Undo/redo for every document edit. Slider drags collapse into one step. History panel with jump-to-state.
- Memory budget on import: oversized images are decoded smaller, with a visible warning. The app does not crash on them.

## Measurements (honest scope)
Measured once in the 2-core dev container on a desktop JVM. These are **not device numbers**:
- 24 MP (6000×4000) raster import into tiles plus the mip chain: ~370 ms.
- 2048×1365 preview of 2 layers (Normal + rotated Overlay), single thread: ~190 ms. At 1280×853: ~105 ms.

No tablet measurements exist yet. Moving a layer re-composites a 1280-px preview, so on-device drag smoothness is unknown and may be well below 60 FPS (see Known issues).

## Known issues / risks
1. **Not yet built with Gradle or run on a device** (see top). Behaviours needing device confirmation: EXIF orientation via ImageDecoder, stylus hover tooltips, pinch-rotate threshold feel, multi-window resizing, mouse right-click on tool families.
2. Moving a layer re-renders the whole preview on the CPU each frame. Expect lag on large documents until M7 (GPU backend, per-layer cached tiles).
3. Engine tiles live on the Java heap (`largeHeap`). Very large images are downsampled on import rather than tiled to disk. Disk-backed/native tiles are M7.
4. Preview bitmaps are released by GC, not pooled (M7 texture/bitmap pooling).
5. Documents exist only for the session. A process kill loses work until M3 adds saving/autosave. The close/open flows warn about this.
6. The `BasicTextField(String…)` overload used in dialogs is deprecated on current AndroidX (it still compiles). Migrate to the `TextFieldState` API.
7. Channels panel shows composite-derived thumbnails only. Single-channel viewing is planned (M4).
8. Process death loses the document (no saved state yet).

## Next steps (recommended order)
1. Run `./gradlew assembleDebug` and `:core:engine:test :core:editor:test`. Fix any build issues first.
2. Install on a tablet. Verify import (portrait JPEG with EXIF rotation, PNG with alpha, WebP), layer ops, gestures and keyboard shortcuts. Capture screenshots and compare them with `design-reference.png`.
3. **M3**: versioned `.phopsd` container (ZIP + JSON manifest + tile assets), atomic save via SAF, reopen, PNG/JPEG export through the same compositor, autosave journal, and round-trip, corruption and export pixel tests.
