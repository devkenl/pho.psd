# pho.psd — Claude Code project instructions

You are building **pho.psd**, a professional, offline-first, native Android **tablet-only** photo editor optimized for photographers, retouchers and photo compositors. The supplied `design-reference.png` is the primary UI visual target. The name must always be spelled `pho.psd`.

## Read before coding
- `docs/MASTER_PLAN.md` — product goals, scope and acceptance standards.
- `docs/UI_DESIGN.md` — visual language, screen geometry, menus and gestures.
- `docs/IMAGE_ENGINE.md` — document model, raster engine, color, performance and storage.
- `docs/FEATURE_MATRIX.md` — exact MVP vs later features.
- `docs/ROADMAP.md` — implementation order and verification gates.
- `docs/SETUP_AND_PROMPTS.md` — operational instructions for the developer.

Read the relevant documents for the current work, not necessarily all lengthy docs on every small edit. Treat `CLAUDE.md` as permanent instructions and the `docs/` files as authoritative task requirements.

## Rules
1. **Build a real editor, not a clickable UI mockup.** No fake functionality or hardcoded demonstration images in production flows. Mark unfinished controls as disabled with an explanation.
2. **Native Android:** Kotlin + Jetpack Compose; isolated C++/NDK image-processing core when justified by measured performance; avoid WebView as the editor.
3. **Tablet-first:** landscape, responsive density, keyboard/mouse/stylus/touch; no phone UI targets; maintain sensible min supported tablet size rather than hardcoded pixels.
4. **Non-destructive where possible:** layer graph, editable masks and adjustments, stable IDs, true alpha compositing, reliable save/reopen/undo/export. Never flatten without consent.
5. **Protect user data:** atomic project saves, tests for round-trip serialization and undo/redo; don't overwrite original imports.
6. **Keep builds passing:** inspect existing repo first; preserve working code; make small verified commits/iterations.
7. **Be precise about capabilities:** PSD, RAW, AI selection and 16-bit depth are milestones, NOT magically done in MVP. Audit open-source licensing before incorporating libraries.
8. **Progress:** maintain `PROJECT_STATUS.md` (done/in progress/planned, bugs, build commands), `ARCHITECTURE.md` (decisions), and `FEATURE_MATRIX.md` (real implementation statuses). Report what works, what was tested, and what remains unsupported.
9. Prefer current stable compatible versions of Android Gradle Plugin, Kotlin, Compose and build tools. Verify rather than invent versions.
10. Do not blindly create a new app if source code exists; inspect, assess and build incrementally.

## First action
Inspect the repository and build environment. If empty, bootstrap a compiling Android project and implement milestones 0–2 from `docs/ROADMAP.md`, starting with the design system, landing page and actual image import/viewport/layers. Use `design-reference.png` for styling, not as app content. If full milestones cannot be finished within one session, stop at a clean, compiling checkpoint with accurate status notes.
