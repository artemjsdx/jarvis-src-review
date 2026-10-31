#!/data/data/com.termux/files/usr/bin/bash
# JVM tests for the pure-Java core (app.jarvis.core.*). Runs real sockets, real processes, real files.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
L="$ROOT/.cache/testlibs"; OUT="${JV_TEST_OUT:-$ROOT/build/test-$$}"
trap '[ -n "${JV_KEEP:-}" ] || rm -rf "$OUT"' EXIT
for j in junit-4.13.2.jar hamcrest-core-1.3.jar json-20240303.jar; do [ -f "$L/$j" ] || { echo "missing $L/$j (see tools/setup.sh)"; exit 1; }; done
LIBS="$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar:$L/json-20240303.jar"
for x in "$ROOT"/.cache/appjars/*.jar "$L"/extra/*.jar; do [ -f "$x" ] && LIBS="$LIBS:$x"; done
MAIN_DIRS="$ROOT/app/src/main/java/app/jarvis/core"; [ -d "$ROOT/app/src/main/java/app/jarvis/wake" ] && MAIN_DIRS="$MAIN_DIRS $ROOT/app/src/main/java/app/jarvis/wake"
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
MAIN_SRC=$(find $MAIN_DIRS -name '*.java')
# pure chat logic (no android.*), listed by file: the chat view packages are not compiled here
MAIN_SRC="$MAIN_SRC $ROOT/app/src/main/java/app/jarvis/data/ChatItem.java $ROOT/app/src/main/java/app/jarvis/ui/chat/ChatFormat.java $ROOT/app/src/main/java/app/jarvis/ui/chat/actions/ActionGrouping.java $ROOT/app/src/main/java/app/jarvis/ui/chat/list/ScrollMath.java $ROOT/app/src/main/java/app/jarvis/ui/chat/actions/ToolIcons.java $ROOT/app/src/main/java/app/jarvis/rt/AState.java $ROOT/app/src/main/java/app/jarvis/ui/home/HomeStatus.java $ROOT/app/src/main/java/app/jarvis/ui/glass/Spring.java $ROOT/app/src/main/java/app/jarvis/ui/glass/Sdf.java $ROOT/app/src/main/java/app/jarvis/ui/glass/FrameGovernor.java"
TEST_SRC=$(find "$ROOT/app/src/test/java" -name '*.java')
if [ -n "${JV_EXCLUDE:-}" ]; then
  # another worker's half-written files may block the build; egrep of source paths to leave out
  MAIN_SRC=$(echo "$MAIN_SRC" | egrep -v "$JV_EXCLUDE" || true)
  TEST_SRC=$(echo "$TEST_SRC" | egrep -v "$JV_EXCLUDE" || true)
fi
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$LIBS" -d "$OUT/main" $MAIN_SRC
javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$OUT/main:$LIBS" -d "$OUT/test" $TEST_SRC
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
[ $# -gt 0 ] && CLASSES="$*"
java -Dfile.encoding=UTF-8 -cp "$OUT/main:$OUT/test:$LIBS" org.junit.runner.JUnitCore $CLASSES
