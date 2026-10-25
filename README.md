# pho.psd

[![Android CI](https://github.com/devkenl/pho.psd/actions/workflows/android.yml/badge.svg)](https://github.com/devkenl/pho.psd/actions/workflows/android.yml)

A professional, offline image editor for **Android tablets**: layers, true alpha
compositing, and a desktop-class workspace designed for touch, stylus, keyboard and mouse.

> Status: `0.4.0-dev`, Milestones 0–4 (workspace, image import, raster layers, `.phopsd` projects, export,
> brushes, layer masks, selections, crop and free transform).
> M0–M3 confirmed on a tablet; M4 builds and passes its tests in CI, device check pending.
> See **PROJECT_STATUS.md** for exactly what has and hasn't been verified.

## Build
Every push to `main` builds in GitHub Actions; download the debug APK from the latest
successful run's **Artifacts** (`pho.psd-debug-apk`).

To build locally you need Android Studio (or the command line) with Android SDK platform 37 and JDK 17+.

```bash
./gradlew assembleDebug                          # APK at app/build/outputs/apk/debug/
./gradlew :core:engine:test :core:editor:test    # 170 JVM unit tests
./gradlew installDebug                           # install on a connected tablet/emulator
```

## Project map
| Path | What |
|---|---|
| `app/` | Android app: Compose UI, ViewModel, decoding, preview rendering |
| `core/editor/` | Pure Kotlin editor state machine (commands, tools, undo-aware edits) |
| `core/engine/` | Pure Kotlin image engine (geometry, copy-on-write tiles, mips, compositor, brushes, selections, masks, history) |
| `docs/` | Product, UI, engine and roadmap specifications (authoritative requirements) |
| `PROJECT_STATUS.md` / `ARCHITECTURE.md` / `FEATURE_MATRIX.md` | Progress, decisions, real feature status |
| `tools/` | Icon generator, offline test runner, offline API type-check |
| `design-reference.png` | Visual reference only (never shipped as app content) |

`CLAUDE.md` holds the standing instructions for Claude Code sessions in this repository.
