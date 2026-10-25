#!/usr/bin/env bash
# Offline fallback for environments WITHOUT Maven/Google repository access:
# compiles :core:engine + :core:editor and their JUnit tests with the Kotlin compiler + JUnit jars that ship
# inside a local Gradle distribution, then runs the tests. The normal path is
#   ./gradlew :core:engine:test :core:editor:test
# Usage: tools/run-engine-tests-offline.sh [GRADLE_HOME]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_HOME="${1:-${GRADLE_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v gradle)")")")}}"
LIB="$GRADLE_HOME/lib"
jar() { ls "$LIB"/$1 2>/dev/null | head -1; }
KC="$(jar 'kotlin-compiler-embeddable-*.jar')"
STD="$(jar 'kotlin-stdlib-[0-9]*.jar')"
CP_COMPILER="$KC:$STD:$(jar 'kotlin-script-runtime-*.jar'):$(jar 'kotlin-reflect-*.jar'):$(jar 'trove4j-*.jar'):$(jar 'annotations-*.jar'):$(jar 'kotlinx-coroutines-core-jvm-*.jar'):$(jar 'kotlin-daemon-embeddable-*.jar')"
JUNIT="$(jar 'junit-4*.jar')"
HAM="$(ls "$LIB"/hamcrest*.jar "$LIB"/plugins/hamcrest*.jar 2>/dev/null | head -1 || true)"
COROUTINES="$(jar 'kotlinx-coroutines-core-jvm-*.jar')"
OUT="${TMPDIR:-/tmp}/phopsd-core-build"
rm -rf "$OUT" && mkdir -p "$OUT/engine" "$OUT/editor" "$OUT/test"
kc() { java -cp "$CP_COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "$@"; }
echo "Compiling :core:engine with $(basename "$KC")"
kc -Werror -classpath "$STD" -d "$OUT/engine" $(find "$ROOT/core/engine/src/main/kotlin" -name '*.kt')
echo "Compiling :core:editor"
kc -Werror -classpath "$STD:$OUT/engine:$COROUTINES" -d "$OUT/editor" $(find "$ROOT/core/editor/src/main/kotlin" -name '*.kt')
echo "Compiling tests"
kc -classpath "$STD:$OUT/engine:$OUT/editor:$COROUTINES:$JUNIT" -d "$OUT/test" \
  $(find "$ROOT/core/engine/src/test/kotlin" "$ROOT/core/editor/src/test/kotlin" -name '*.kt')
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
java -cp "$STD:$OUT/engine:$OUT/editor:$OUT/test:$COROUTINES:$JUNIT${HAM:+:$HAM}" org.junit.runner.JUnitCore $CLASSES
