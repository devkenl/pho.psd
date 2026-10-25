# pho.psd — Milestones, Deliverables & Work Order

## Execution rules
Inspect repository/Gradle environment before changing files. Implement only a small number of actual features per iteration and keep builds green. For each milestone record: files changed, tests run/results, actual functional controls, disabled controls, known bugs and next work. No invented benchmarks or claims. Update root `PROJECT_STATUS.md`, `ARCHITECTURE.md`, and root `FEATURE_MATRIX.md` during development.

### M0 — Bootstrap (build must pass)
Create/update Android Gradle/Kotlin/Compose application, min SDK chosen and justified, app ID/name, theme, launcher. Establish lint/testing/build instructions, image engine boundary, test resources and docs. Launch emulator/device successfully. Output initial architecture and status reports.

### M1 — Faithful workspace
Landing page + create/open project actions; editor top header, traditional File/Edit/Image/Layer/Select/Filter/View/Window/Help menus, Edit/Adjust/FX/Filters/Layer/Select quick strip, compact tool rail, large canvas, right Layers/Channels/Paths dock, bottom contextual brush toolbar, status. Use `design-reference.png` as visual specification. Implement menus as real command routing; disabled for unavailable backend operations. Test screen resizing, landscape accessibility, interactions, basic app navigation. Maintain screen comparisons.

### M2 — Raster core and image import
Create document; SAF/open JPEG PNG (prefer robust platform decoder), preview at correct orientation, pan/zoom, new raster layer from a second image, name/show/hide/reorder/opacity, move, correct alpha compositing, responsive previews. Separate main thread from decode/render. Establish performance/caching primitives. **Acceptance:** edit two imported images as separate layers with correct z-order and transparency; no hardcoded mountain photo; smooth navigation.

### M3 — Persist and export
Versioned `.phopsd`, safe save/reopen, PNG/JPEG composite export, command undo/redo, autosave journal or safe recovery. **Acceptance:** import → layer edit → save → restart → reopen → pixel-correct export; pass corruption and round-trip tests.

### M4 — Manual compositing tools
Soft/hard brush, eraser, mask painting, rectangle/ellipse/lasso, crop, free transform, basic stylus input. **Acceptance:** mask combines two photographs without destructively removing source pixels; undo all tested strokes and transforms.

### M5 — Non-destructive adjustment pipeline
Exposure, brightness/contrast, saturation, temperature/tint, levels, Curves graph (RGB + channels), Hue/Saturation; editable adjustment layers/masks. **Acceptance:** changes survive save/reopen and are reflected in full-res export without modifying original rasters.

### M6 — Stronger compositing and retouch
Groups, more blend modes, feather/refinement, clone stamp, basic heal, gradient masks, additional transform abilities. **Acceptance:** multiple mask blends, no visible tile seams, compare against known golden outputs.

### M7 — Pro UX and speed
Tiled viewport optimization, caches/dirty region scheduling, 12/24/50 MP benchmarks, workspace customization, shortcuts, stylus hover/pressure refinement, histogram and navigator. **Acceptance:** publish actual measured latency/memory; improve regressions against previous build.

### M8+ — Extended Photoshop-class coverage
Investigate layered PSD/PSB, RAW, ICC and 16-bit, vector paths, text, filters/liquify, advanced heal/content aware, on-device subject selection. Make separate architectural designs and licensing analyses for each before coding. No promises of full fidelity until test vectors pass.

## First suggested Claude Code task
> Inspect this repository and `CLAUDE.md`, `docs/MASTER_PLAN.md`, `docs/UI_DESIGN.md`, `docs/IMAGE_ENGINE.md` and `docs/ROADMAP.md`, plus `design-reference.png`. If empty, bootstrap pho.psd and implement M0 and the initial M1 editor shell. Begin M2 real image import if M0/M1 compile and are tested. Keep work incremental, update status documents, and report only verified features. Don't implement all future features in one pass.
