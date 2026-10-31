#!/data/data/com.termux/files/usr/bin/bash
# Build the debug APK and run <MainClass> from it on the real Android runtime (ART) of this phone.
#   bash tools/art-apk.sh app.jarvis.dev.checks.MyCheck [args...]
# Put such mains under app/src/debug/java/app/jarvis/dev/checks/. Output = the program's stdout (uncaught exceptions
# only reach logcat: wrap main() in try/catch + printStackTrace(System.out)).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
W="$ROOT/build/art-run-$$"; mkdir -p "$W"; trap 'rm -rf "$W"' EXIT
JV_APK_OUT="$W/app.apk" bash "$ROOT/build.sh" debug > "$W/build.log" 2>&1 || { grep -E 'error|Error' -A3 "$W/build.log" | head -40; exit 1; }
. "$ROOT/version.properties"
APK="$W/app.apk"
bash "$ROOT/tools/art-run.sh" "$APK" "$@"
