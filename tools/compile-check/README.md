# Offline compile check (no Google Maven needed)

Use this only where AndroidX artifacts cannot be downloaded. The real build is always
`./gradlew assembleDebug`.

`compile_check.sh` does the following:
1. `api2kt.py` converts AndroidX Metalava signature files (`*/api/current.txt`) into Kotlin
   stub sources (public API, `TODO()` bodies).
2. It compiles the stubs, letting `prune.py` comment out or adjust any stub declaration
   kotlinc rejects (error-driven; unresolved supertypes are dropped and classes with
   unimplemented members become abstract).
3. It compiles `:core:engine`, `:core:editor` and every `:app` Kotlin source against stubs
   + a real `android.jar` + kotlinx-coroutines.

Inputs used on 2026-10-25:
- androidx signature files: `git clone --depth 1 --filter=blob:none --sparse
  https://github.com/androidx/androidx` (commit 53bc2d6), sparse paths for compose
  ui/ui-graphics/ui-unit/ui-text/ui-geometry/foundation/foundation-layout/material3/runtime/
  runtime-saveable/animation/animation-core/ui-tooling-preview, activity, activity-compose,
  lifecycle-*, core/core, savedstate.
- `android.jar` API 37 from https://github.com/Sable/android-platforms (compile-time only).

Limitations: no Compose compiler plugin (composable-context rules unchecked), sealed/value
modifiers dropped, deprecation annotations stripped (check `@Deprecated` in the api files
by hand), and pruning can create false errors. The known one: "MainActivity does not
implement abstract member addMenuProvider", because the stub `ComponentActivity` was made
abstract.
