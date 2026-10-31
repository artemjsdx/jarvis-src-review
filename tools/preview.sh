#!/data/data/com.termux/files/usr/bin/bash
# Build the debug APK and render UI scenes headlessly into build/preview/*.png on the real Android runtime.
#   bash tools/preview.sh [SceneClass...]    (default: every *Scenes.java under app/src/debug/java/app/jarvis/dev/scenes)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
W="$ROOT/build/preview-run-$$"; mkdir -p "$W"; trap 'rm -rf "$W"' EXIT
OUTDIR="${JV_PREVIEW_OUT:-$ROOT/build/preview}"; mkdir -p "$OUTDIR"
JV_APK_OUT="$W/app.apk" bash "$ROOT/build.sh" debug > "$W/build.log" 2>&1 || { grep -E "error|Error" -A3 "$W/build.log" | head -40; exit 1; }
. "$ROOT/version.properties"
APK="$W/app.apk"
SCENES="$*"
[ -n "$SCENES" ] || SCENES=$(cd "$ROOT/app/src/debug/java/app/jarvis/dev/scenes" 2>/dev/null && ls *Scenes.java | sed 's/\.java$//' | tr '\n' ' ')
rm -f "$OUTDIR"/*.png
# Output goes to a file first: `| head -80` closed the pipe once a run printed more than 80 lines, SIGPIPE killed the renderer
# (exit 141) and every scene after that point was silently skipped.
rc=0
bash "$ROOT/tools/art-run.sh" "$APK" app.jarvis.dev.Preview "$APK" "$OUTDIR" $SCENES > "$W/run.log" 2>&1 || rc=$?
grep -v '^$' "$W/run.log" | grep -v '^\[probe\]' | head -${JV_PREVIEW_LINES:-150}
exit $rc
