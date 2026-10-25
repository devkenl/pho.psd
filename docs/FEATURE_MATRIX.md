# pho.psd — Feature Matrix (initial plan, NOT implementation status)

**Legend**: M0–M8 = target milestones in `ROADMAP.md`. None are assumed implemented until tested in source code. Track actual implemented state separately in root `FEATURE_MATRIX.md` when development starts.

| Capability | Target | Notes |
|---|---|---|
| Tablet shell, launcher, navigation and themed UI | M1 | Match `design-reference.png` |
| Full traditional app menu + quick categories | M1 | Disable functions until implemented |
| Image open / JPEG PNG decode | M2 | Native full resolution, respect orientation |
| Pan, zoom and coordinate maps | M2 | Finger/stylus/mouse |
| Raster layers, alpha compositing, opacity, order | M2 | Core functional editor |
| Native .phopsd save and reopen | M3 | Atomic, round-trip tested |
| PNG/JPEG export | M3 | Correct full-resolution composite |
| Undo/redo, project autosave foundations | M3 | Commands and recovery |
| Brush and eraser | M4 | Basic parameters, stylus |
| Marquee, lasso, crop, transform | M4 | Selection coordinate correctness |
| Grayscale mask + mask painting | M4 | Layer compositing |
| Adjustment layers Exposure/Contrast/Saturation | M5 | Editable parameters |
| Levels, curves, hue/saturation | M5 | Graph UI and masks |
| Advanced blend modes + groups | M6 | Mathematical tests |
| Feathering / selection refinements | M6 | Mask-native |
| Clone Stamp / basic healing | M6 | Sample multiple sources |
| Workspace persistence, dock resize | M7 | Tablet UX |
| Tile rendering and large-document optimization | M2 foundation; M7 expansion | Avoid avoidable memory spikes from start |
| Histograms, shortcuts/stylus polish | M7 | Verification on device |
| ICC advanced profiles and 16-bit | M8 | Explicit compatibility requirements |
| PSD/PSB layered interoperability | M8 | Partial first, truthful fidelity report |
| Camera RAW workflow | M8 | Non-destructive original file |
| Text, vector paths, smarter selections | M8 | Dedicated subsystem |
| Liquify, warp, AI-assisted content awareness | M8+ | Not MVP |

Never represent planned features as completed. When a tool or menu action isn't ready, disable it with a clear reason.
