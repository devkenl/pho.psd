# pho.psd — Feature Matrix (actual implementation status)

Status vocabulary: **Implemented** = code complete with automated tests where the logic is testable on the JVM · **Implemented (UI)** = UI wired to real behaviour; type-checked against the AndroidX APIs but **not yet run on a device** · **Partial** · **Planned** (milestone). The plan lives in `docs/FEATURE_MATRIX.md`; this file reports reality. The in-app *Help ▸ Feature Status* dialog mirrors it.

> This build has **not** been compiled with Gradle or run on a device yet (see PROJECT_STATUS.md). Every "Implemented (UI)" row needs on-device confirmation.

| Capability | Status | Tests / evidence | Notes |
|---|---|---|---|
| Gradle project, version catalog, wrapper, manifest, theme, launcher icon | Implemented | Versions verified against official release pages | First Gradle build pending |
| Design tokens, typography, Lucide icon set, pho.psd mark | Implemented (UI) | — | Original mark; no Adobe branding |
| Home screen (New / Open Image / Open from Photos / Open Project) | Implemented (UI) | Controller tests | Open Project disabled (M3); no fake recents |
| New Document dialog (presets, size, ppi, orientation, background) | Implemented | `EditingTest.specValidation`, `newDocument…` | 8-bit sRGB only |
| Header: doc name rename/info, unsaved state, undo/redo, save/export/settings (disabled with reasons) | Implemented (UI) | — | |
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
| Native `.phopsd` save / reopen, autosave | Planned (M3) | — | |
| PNG / JPEG export | Planned (M3) | — | Will reuse the tested compositor |
| Brush, eraser, masks, selections, crop, free transform | Planned (M4) | — | |
| Adjustment layers (exposure, curves, levels, HSL…) | Planned (M5) | — | |
| Groups, clone/heal, gradients, filters, layer effects | Planned (M6) | — | |
| Workspace presets/resizing, rulers/guides/grid, histogram, navigator | Planned (M7) | — | Dock visibility/history toggles work today |
| PSD/PSB, RAW, ICC/16-bit, text, paths, liquify, subject select | Planned (M8+) | — | |
