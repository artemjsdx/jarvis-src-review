#!/data/data/com.termux/files/usr/bin/bash
# JVM tests for app.jarvis.wake (offline wake word / STT). Mirrors tools/run-tests.sh but also compiles the
# wake main sources and puts the vosk/jna jars from tools/setup-native.sh on the classpath.
#   bash tools/run-wake-tests.sh [fully.qualified.TestClass ...]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
L="$ROOT/.cache/testlibs"; A="$ROOT/.cache/appjars"
OUT="${JV_TEST_OUT:-$ROOT/build/wake-test-$$}"
trap '[ -n "${JV_KEEP:-}" ] || rm -rf "$OUT"' EXIT
for j in junit-4.13.2.jar hamcrest-core-1.3.jar json-20240303.jar; do [ -f "$L/$j" ] || { echo "missing $L/$j (run: bash tools/setup.sh)"; exit 1; }; done
for j in vosk-android.jar jna.jar; do [ -f "$A/$j" ] || { echo "missing $A/$j (run: bash tools/setup-native.sh)"; exit 1; }; done
LIBS="$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar:$L/json-20240303.jar"
JARS="$A/vosk-android.jar:$A/jna.jar"
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
MAIN_SRC=$(find "$ROOT/app/src/main/java/app/jarvis/core" "$ROOT/app/src/main/java/app/jarvis/wake" -name '*.java')
TEST_SRC=$(find "$ROOT/app/src/test/java/app/jarvis/wake" -name '*.java')
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$LIBS:$JARS" -d "$OUT/main" $MAIN_SRC
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$OUT/main:$LIBS:$JARS" -d "$OUT/test" $TEST_SRC
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
[ $# -gt 0 ] && CLASSES="$*"
java -Dfile.encoding=UTF-8 -cp "$OUT/main:$OUT/test:$LIBS:$JARS" org.junit.runner.JUnitCore $CLASSES
