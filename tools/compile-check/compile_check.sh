#!/usr/bin/env bash
# Type-checks the Android app module WITHOUT Google Maven access:
#   1. generates Kotlin stubs from AndroidX api/current.txt signature files,
#   2. compiles the stubs (pruning stub declarations kotlinc rejects),
#   3. compiles :core:engine, :core:editor and :app sources against
#      stubs + a real android.jar + kotlinx-coroutines.
# No Compose compiler plugin is applied, so Compose-specific rules are not checked.
#
# Usage: compile_check.sh ANDROIDX_SRC_DIR ANDROID_JAR [GRADLE_HOME]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
AX="$1"; ANDROID_JAR="$2"
GRADLE_HOME="${3:-${GRADLE_HOME:-/opt/gradle}}"
LIB="$GRADLE_HOME/lib"
jar() { ls "$LIB"/$1 2>/dev/null | head -1; }
STD="$(jar 'kotlin-stdlib-[0-9]*.jar')"
COR="$(jar 'kotlinx-coroutines-core-jvm-*.jar')"
CPC="$(jar 'kotlin-compiler-embeddable-*.jar'):$STD:$(jar 'kotlin-script-runtime-*.jar'):$(jar 'kotlin-reflect-*.jar'):$(jar 'trove4j-*.jar'):$(jar 'annotations-*.jar'):$COR:$(jar 'kotlin-daemon-embeddable-*.jar')"
W="${TMPDIR:-/tmp}/phopsd-compile-check"
rm -rf "$W" && mkdir -p "$W/stubs-src" "$W/stubs" "$W/core" "$W/app"
kc() { java -Xmx3g -cp "$CPC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -nowarn "$@" 2>&1; }

python3 -I "$ROOT/tools/compile-check/api2kt.py" "$W/stubs-src" $(find "$AX" -path '*/api/current.txt' | sort)
cp "$ROOT/tools/compile-check/manual-stubs.kt" "$W/stubs-src/" 2>/dev/null || true

echo "== compiling stubs (error-driven pruning)"
for i in $(seq 1 40); do
  kc -classpath "$STD:$COR:$ANDROID_JAR" -d "$W/stubs" "$W/stubs-src" > "$W/stub-errors.txt"
  cp "$W/stub-errors.txt" "$W/stub-errors-$i.txt"
  n=$(grep -c ": error:" "$W/stub-errors.txt")
  echo "  pass $i: $n errors"
  [ "$n" = "0" ] && break
  python3 -I "$ROOT/tools/compile-check/prune.py" "$W/stub-errors.txt" || { echo "no progress"; cat "$W/stub-errors.txt" | head -40; exit 2; }
done
grep -rc "// PRUNED" "$W/stubs-src" | awk -F: '{s+=$2} END {print "  pruned stub lines:", s}'

echo "== compiling core modules"
kc -classpath "$STD:$COR" -d "$W/core" $(find "$ROOT/core/engine/src/main/kotlin" "$ROOT/core/editor/src/main/kotlin" -name '*.kt') | grep -E 'error' ; true

echo "== compiling app module against stubs"
kc -classpath "$STD:$COR:$ANDROID_JAR:$W/stubs:$W/core" -d "$W/app" \
  -opt-in=kotlin.RequiresOptIn \
  $(find "$ROOT/app/src/main/kotlin" -name '*.kt') > "$W/app-errors.txt"
cat "$W/app-errors.txt" | grep -E 'error:' | sed "s|$ROOT/||" 
echo "app errors: $(grep -c ': error:' "$W/app-errors.txt")"
