# pho.psd — Project Status

_Last updated: 2026-10-25 · version `0.3.0-dev` · scope: Milestones 0–3 + touch rotation snapping_

**M0–M2 were confirmed working on the developer's tablet (2026-10-25).** M3 (this update) builds in CI and passes its JVM tests, but has **not yet been run on a device**.

## Read this first: what was and was not verified

**Update (2026-10-25): the project now builds in GitHub Actions.** Run [#40177654382](https://github.com/devkenl/pho.psd/actions/runs/40177654382) passed `:core:engine:test :core:editor:test` and `:app:assembleDebug` with AGP 9.4.0 / Gradle 9.6.0 / JDK 21 / Android platform 37, and produced the debug APK artifact. That was the first real Gradle build, and it needed no source changes. The only fixes needed were CI SDK setup. The app has **still not been run on a device or emulator.**

CI (`.github/workflows/android.yml`) runs on every push to `main` and on pull requests. It uploads `pho.psd-debug-apk` and `test-reports` artifacts, and publishes `REPORT.txt` plus build logs to the `ci-logs/latest` branch (and to `ci-logs/<run id>` on failure).

The original development environment had **no access to Google Maven, Maven Central, the Gradle plugin portal or the Android SDK**, so the following pre-CI checks were used:

| Check | Result | How |
|---|---|---|
| `:core:engine` + `:core:editor` compile (Kotlin, warnings as errors) | ✅ pass | Kotlin compiler bundled with local Gradle (2.0.21) — `tools/run-core-tests-offline.sh` |
| Core unit tests | ✅ **117 / 117 pass** (M3 added 43) | JUnit 4.13.2 — same script; CI run 40190963223 also: 117 run, 0 failures |
| `:app` type-check against the real AndroidX + Android APIs | ✅ pass, 1 known tooling artifact | `tools/compile-check/` generates Kotlin stubs from androidx `api/current.txt` signature files (androidx commit `53bc2d6`, 2026-10-23) and compiles the app against them plus Android API 37 `android.jar`. The only reported error comes from the stub generator: the stub `ComponentActivity` had to be made abstract. |
| Compose compiler plugin rules | ⚠️ not checked | Stub compile runs without the plugin; composable-context rules were reviewed by hand |
| `./gradlew assembleDebug` | ✅ **pass in GitHub Actions** | Not possible locally; CI run 40177654382 |
| Install / run on tablet | ✅ M0–M2 confirmed by the developer · ❌ M3 not yet | |
| Screenshot comparison with `design-reference.png` | ❌ not done | |

Next verification gap: exercise M3 on the tablet (checklist under "Next steps").

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

### M0 — Bootstrap: **done**
- Gradle multi-module project: `:app` (Android, Compose), `:core:engine` (pure Kotlin image engine), `:core:editor` (pure Kotlin editor state machine).
- Version catalog, wrapper (9.6.0), manifest (tablet-only `supports-screens`, no storage/network permissions, `largeHeap`), platform theme, original adaptive launcher icon.
- Architecture, status and feature-matrix documents; offline test and type-check tooling.

### M1 — Faithful workspace: **done (confirmed on device)**
Implemented: home screen (no fake recents); header (back, pho.psd mark, tappable document name with rename/info, unsaved indicator with “edited N min ago”, undo/redo with labels, save, export, preferences, overflow); full **File/Edit/Image/Layer/Select/Filter/View/Window/Help** menu bar with hover-switching dropdowns; **Edit/Adjust/FX/Filters/Layer/Select** quick strip opening command popovers; compact tool rail (15 families, long-press/right-click variants, hover tooltips); canvas; floating contextual tool-options bar; right dock with **Layers | Channels | Paths** tabs and a History panel; status bar.
All of these surfaces dispatch the same `CommandId`s. Unbuilt commands are still shown. They are dimmed, carry a milestone badge (M3–M8) and explain themselves when tapped.

### M2 — Raster core and image import: **done (confirmed on device)**
- Open JPEG/PNG/WebP through SAF or the system photo picker → new document at native size. Uses ImageDecoder, EXIF orientation and sRGB conversion; the source colour space is recorded.
- Place image as a new layer, fitted to the canvas non-destructively.
- Raster layers: add empty layer, duplicate, delete, rename, show/hide, lock, reorder (drag handle, long-press drag, menu, shortcuts), opacity, 16 blend modes.
- Move tool (drag, auto-select layer, arrow-key nudge) and numeric X/Y/scale/rotation on the layer transform. Original pixels are never resampled.
- Viewport: pinch zoom, two-finger pan, deliberate two-finger rotate, mouse-wheel zoom, Hand/Zoom tools, Space-drag pan, fit/100%/zoom steps, view rotation.
- Undo/redo for every document edit. Slider drags collapse into one step. History panel with jump-to-state.
- Memory budget on import: oversized images are decoded smaller, with a visible warning. The app does not crash on them.

### M3 — Persist and export: **implemented, tested on the JVM, not yet on device**
- **Native `.phopsd` format v1** (`core/engine/io`): ZIP with a `mimetype` entry, `manifest.json` (document, layers, transforms, blend modes, opacity, visibility, locks, import provenance, colour profile, active layer) and one lossless tiled raster file per distinct raster (shared by duplicated layers), plus a thumbnail. Each raster is protected by ZIP CRC-32 and a SHA-256 recorded in the manifest. A `formatVersion`/`minReaderVersion` pair and a migration hook handle future versions.
- **Save / Save As** (Ctrl+S / Shift+Ctrl+S) through the Storage Access Framework. The save is written to a private temp file, fsync'd and verified (structure + every hash). Then the existing destination is backed up privately, the verified bytes are copied over it, and the destination is verified again. On failure the backup is restored, so the previous file is never left half-written. You can keep editing while a save runs, because it saves an immutable snapshot.
- **Open Project** (Shift+Ctrl+O) with full validation. Damaged, truncated, foreign or newer-version files are refused with a clear message; nothing is silently dropped. An unknown blend mode falls back to Normal with a warning.
- **Unsaved-changes tracking** compares content against the last save (layer selection doesn't count). Close, open and new-document actions offer **Save / Don't save / Cancel**, and "Save" finishes the action only after a successful save.
- **Autosave & recovery**: 30 s after editing pauses, and immediately when the app goes to the background, the document is written to app-private storage (temp file, verify, atomic rename). After a crash or kill, the home screen offers **Recover / Discard**. The recovery copy is kept until the recovered work is autosaved or saved again.
- **Recent projects**: a real list (with thumbnails) of projects saved or opened, on the home screen and under File ▸ Recent Projects. SAF permissions are persisted so they reopen after restarts.
- **Export PNG / JPEG** at 100/75/50/25%. It renders through the same compositor as the screen, band by band. PNG keeps transparency exactly (non-premultiplied bitmap); JPEG flattens onto white. The written file is re-read to confirm its dimensions.
- Undo/redo (already in M2) covers every M3-era edit.

### Extra (requested): touch rotation snapping
- Two-finger **view rotation** snaps magnetically to multiples of 45° by default. The step can be 15°, 45° or 90°, or snapping can be turned off, from the Hand/Zoom/Move tool options. Snapping is computed from the raw finger angle, so twisting further always breaks free. A deliberate 12° twist is still needed before the view starts rotating.
- New Move-tool option **Two fingers: View | Layer**. In Layer mode, two fingers move, scale and rotate the active layer around the point between the fingers. Rotation snaps the same way. The whole gesture is one undo step, and locked layers fall back to view navigation.
- An angle readout appears while rotating; a haptic tick fires each time a snap engages.

## Measurements (honest scope)
Measured once in the 2-core dev container on a desktop JVM. These are **not device numbers**:
- 24 MP (6000×4000) raster import into tiles plus the mip chain: ~370 ms.
- 2048×1365 preview of 2 layers (Normal + rotated Overlay), single thread: ~190 ms. At 1280×853: ~105 ms.
- `.phopsd` with one 24 MP raster (synthetic gradient + noise): write ~1.1 s, structural verify ~50 ms, full read ~0.55 s, 25 MB file (96 MB raw). Real photographs compress less; expect larger files.

No tablet measurements exist yet. Moving a layer re-composites a 1280-px preview, so on-device drag smoothness is unknown and may be well below 60 FPS (see Known issues).

## Known issues / risks
1. **Builds in CI but not yet run on a device** (see top). Behaviours needing device confirmation: EXIF orientation via ImageDecoder, stylus hover tooltips, pinch-rotate threshold feel, multi-window resizing, mouse right-click on tool families.
2. Moving a layer re-renders the whole preview on the CPU each frame. Expect lag on large documents until M7 (GPU backend, per-layer cached tiles).
3. Engine tiles live on the Java heap (`largeHeap`). Very large images are downsampled on import rather than tiled to disk. Disk-backed/native tiles are M7.
4. Preview bitmaps are released by GC, not pooled (M7 texture/bitmap pooling).
5. SAF cannot replace a document atomically. pho.psd verifies before and after copying and restores a private backup on failure, but a power loss *during* the final copy could still leave the destination incomplete. The previous version then survives in the app's private backup folder, and the autosave also holds the current work.
6. Channels panel shows composite-derived thumbnails only. Single-channel viewing is planned (M4).
7. Autosave runs 30 s after editing pauses and when the app is backgrounded. If the process is killed within that window, the last ~30 s of edits can be lost.
8. `.phopsd` has no registered MIME type, so the Open Project picker shows all files; the content is validated on open.
9. Tiles are written uncompressed-then-deflated per tile on one thread. Large saves take seconds (they run in the background); parallel encoding is a later optimisation.

## Next steps (recommended order)
1. **On-device M3 check** (roadmap acceptance): import → layer edits → Save → force-stop the app → reopen from Recents → compare with the original → Export PNG and JPEG and inspect them in another app. Also: edit, background the app for 5 s, force-stop, relaunch → Recover. Then try two-finger view/layer rotation with snapping.
2. Capture screenshots and compare them with `design-reference.png`.
3. **M4**: brush and eraser, layer masks with mask painting, rectangle/ellipse/lasso selections, crop, free-transform handles and basic stylus pressure.
