# pho.psd — Feature Matrix (actual implementation status)

Status vocabulary: **Implemented** = code complete with automated tests where the logic is testable on the JVM · **Implemented (UI)** = UI wired to real behaviour; type-checked against the AndroidX APIs but **not yet run on a device** · **Partial** · **Planned** (milestone). The plan lives in `docs/FEATURE_MATRIX.md`; this file reports reality. The in-app *Help ▸ Feature Status* dialog mirrors it.

> M0–M2 rows were confirmed on the developer's tablet. M3 rows (and touch-rotation snapping) build in CI and pass JVM tests but have **not** been run on a device yet.

| Capability | Status | Tests / evidence | Notes |
|---|---|---|---|
| Gradle project, version catalog, wrapper, manifest, theme, launcher icon | Implemented | CI build passes (GitHub Actions) | Not yet run on a device |
| Design tokens, typography, Lucide icon set, pho.psd mark | Implemented (UI) | — | Original mark; no Adobe branding |
| Home screen (New / Open Image / Open from Photos / Open Project / Recents / Recovery) | Implemented (UI) | Controller tests | Recents are real (saved/opened projects) |
| New Document dialog (presets, size, ppi, orientation, background) | Implemented | `EditingTest.specValidation`, `newDocument…` | 8-bit sRGB only |
| Header: doc name rename/info, save state (saving/saved/unsaved/autosaved/failed), undo/redo, Save, Export | Implemented (UI) | `PersistenceFlowTest` | Settings still planned (M7) |
| Traditional menu bar File…Help with real command routing | Implemented (UI) | `plannedCommandsAreNeverEnabled`, `everyImplementedCommandIsHandled` | 37 working commands, 68 badged as planned |
| Quick strip Edit/Adjust/FX/Filters/Layer/Select | Implemented (UI) | — | Opens command popovers |
| Tool rail with tool families, long-press/right-click variants, hover tooltips | Implemented (UI) | `unavailableToolExplainsItself` | Working tools: Move, Hand, Zoom |
| Contextual tool options bar | Implemented (UI) | — | Move: auto-select, X/Y, scale, rotation, opacity, reset; Hand/Zoom: zoom, fit, 100%, view rotation |
| Status bar | Implemented (UI) | — | Zoom (tap for exact), size/ppi, profile, layers, save state |
| Open JPEG / PNG / WebP (SAF + photo picker) | Implemented (UI) | Raster builder/budget tests | ImageDecoder; EXIF orientation and sRGB conversion need device confirmation |
| Memory-budgeted import (downsample with warning) | Implemented | `importBudget…`, `downsampledImportWarns` | Heuristic: 40% headroom |
| Place image as layer (non-destructive fit) | Implemented | `placeImageAddsFittedLayerAboveActive` | |
| Tiled premultiplied rasters, zero-cost solid fills, freezing | Implemented | `RasterTest` (10 tests) | Java heap; native/disk tiles M7 |
| Mip chains (premultiplied box filter) | Implemented | `mipLevels…`, `mipChain…` | |
| Coordinate system (screen/document/layer), viewport zoom/pan/rotate/fit | Implemented | `GeometryTest` (15 tests) | |
| Canvas: pinch zoom, 2-finger pan, deliberate rotate, wheel zoom, Space-pan | Implemented (UI) | Viewport maths tested | Gesture feel needs device tuning |
| Raster layers: new, duplicate, delete, rename, visibility, lock, reorder | Implemented | `EditingTest`, `EditorControllerTest` | Drag reorder in the Layers panel is UI-only |
| Layer opacity | Implemented | `normalSourceOverWithAlphaAndOpacity`, coalescing test | |
| 16 blend modes (W3C formulas) | Implemented | `separableBlendModesMatchFormulas`, `nonSeparable…`, `multiplyWithPartialAlpha…` | Soft Light = W3C curve |
| Alpha compositing correctness (premultiplied float) | Implemented | `CompositorTest` (17 tests) incl. tile seams and banding | |
| Move tool (drag, auto-select, nudge) and numeric transform | Implemented | `moveDragIsOneUndoStep…`, `autoSelect…`, `numericEntry…` | On-canvas transform handles M4 |
| Undo / redo / History panel (jump to state) | Implemented | `History…` tests | Covers all M2 edits |
| Layers panel thumbnails (background, cached per raster) | Implemented (UI) | `thumbnailsAndChannels` | |
| Channels panel | Partial | `thumbnailsAndChannels` | Composite RGB/R/G/B thumbnails only; isolation M4 |
| Paths panel | Planned (M8) | — | Honest empty state |
| Preview renderer (base + detail, cancellable, parallel bands) | Partial | Compositor band test | CPU; GPU and dirty tiles M7 |
| Keyboard shortcuts | Implemented (UI) | — | List in Help ▸ Keyboard Shortcuts |
| Native `.phopsd` format v1 (manifest + lossless tiled rasters + hashes) | Implemented | `PersistenceTest` (19 tests): exact round trip, pixel-identical render after reopen, re-save stability, shared rasters, corruption/truncation/missing data/foreign file/newer version, unknown blend mode/layer type, JSON strictness | Not yet run on device |
| Save / Save As via SAF (staged, verified, backup + restore) | Implemented (UI) | `PersistenceFlowTest` (save flow, edits during save, snapshot survives raster release) | SAF itself cannot replace atomically; see PROJECT_STATUS risks |
| Open Project, Recent Projects | Implemented (UI) | `loadProjectOpensClean…`, `openProjectAsksBeforeDiscarding` | |
| Unsaved-changes tracking, Save / Don't save / Cancel | Implemented | `editsDuringSaveKeepDocumentDirty`, `selectingLayersDoesNotDirty`, `saveThenCloseClosesOnlyAfterSuccess` | |
| Autosave + crash recovery | Implemented (UI) | Format/verify tests; recovery opens dirty (`…RecoveryOpensDirty`) | 30 s idle + on background |
| PNG / JPEG export (100/75/50/25%) | Implemented (UI) | `exportBandsMatchSinglePassAndAreStraightAlpha`, `exportOverWhite…` | sRGB 8-bit; JPEG flattens onto white |
| Two-finger rotation snapping (view; layer via Move option) | Implemented | `PinchSnapTest` (9), `GesturesTest` (6) | Haptic tick on snap; step 15°/45°/90°/off |
| Two-finger layer move/scale/rotate (Move tool option) | Implemented | `moveToolCanTransformTheLayer…`, `layerPinchFollowsFingers…` | One undo step per gesture |
| Brush, eraser, masks, selections, crop, free transform | Planned (M4) | — | |
| Adjustment layers (exposure, curves, levels, HSL…) | Planned (M5) | — | |
| Groups, clone/heal, gradients, filters, layer effects | Planned (M6) | — | |
| Workspace presets/resizing, rulers/guides/grid, histogram, navigator | Planned (M7) | — | Dock visibility/history toggles work today |
| PSD/PSB, RAW, ICC/16-bit, text, paths, liquify, subject select | Planned (M8+) | — | |
