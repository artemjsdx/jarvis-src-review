#!/data/data/com.termux/files/usr/bin/bash
# Starts McpSmokeServer (loopback MCP server with a fake runtime, see app/src/test/.../McpSmokeServer.java).
# Compiles core + the smoke server into build/jvm-smoke when sources changed. Args are passed through ([port [token]] [--audio] [--destructive]).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
L="$ROOT/.cache/testlibs"; OUT="$ROOT/build/jvm-smoke"
LIBS="$L/json-20240303.jar"
for x in "$ROOT"/.cache/appjars/*.jar "$L"/extra/*.jar; do [ -f "$x" ] && LIBS="$LIBS:$x"; done
SRC_MAIN=$(find "$ROOT/app/src/main/java/app/jarvis/core" -name '*.java')
SMOKE="$ROOT/app/src/test/java/app/jarvis/core/mcp/McpSmokeServer.java"
STAMP="$OUT/.stamp"
if [ ! -f "$STAMP" ] || [ -n "$(find "$ROOT/app/src/main/java/app/jarvis/core" "$SMOKE" -newer "$STAMP" -name '*.java' | head -1)" ]; then
  rm -rf "$OUT"; mkdir -p "$OUT/classes"
  javac --release 8 -Xlint:-options -nowarn -encoding UTF-8 -cp "$LIBS" -d "$OUT/classes" $SRC_MAIN "$SMOKE" >&2
  touch "$STAMP"
fi
exec java -Dfile.encoding=UTF-8 -cp "$OUT/classes:$LIBS" app.jarvis.core.mcp.McpSmokeServer "$@"
