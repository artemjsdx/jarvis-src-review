#!/data/data/com.termux/files/usr/bin/bash
# Run the live LLM check (LiveCheck) on the phone's real Android runtime (ART + Android's own HttpURLConnection/TLS).
#   bash tools/art-live.sh <opencode-provider-id> <model> [stream|agent|cancel|all] [preset]
# or  JV_BASE=... JV_KEY=... JV_MODEL=... bash tools/art-live.sh - '' all [preset]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROV="${1:?provider id or -}"; MODEL="${2:-}"; WHAT="${3:-all}"; QUIRKS="${4:-}"
W="$ROOT/build/art-live-$$"; mkdir -p "$W/cls" "$W/dex"; trap 'rm -rf "$W"' EXIT
AJ="$ROOT/.cache/android-36/android.jar"; ST="$ROOT/.cache/stubs/core-lambda-stubs.jar"
C="$ROOT/app/src/main/java/app/jarvis/core"
javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 -bootclasspath "$AJ:$ST" -d "$W/cls" \
  $(find "$C/llm" "$C/agent" "$C/tools" "$C/net" "$C/log" "$C/util" -name '*.java') "$ROOT/app/src/test/java/app/jarvis/core/live/LiveCheck.java" 2>&1 | grep -v '^Note:' || true
d8 --min-api 26 --lib "$AJ" --output "$W/dex" $(find "$W/cls" -name '*.class') 2>&1 | grep -v '^Warning' || true
if [ "$PROV" != "-" ]; then
eval "$(python3 - "$PROV" <<'PY'
import json, os, re, sys, shlex
p = sys.argv[1]
auth = json.load(open(os.path.expanduser('~/.local/share/opencode/auth.json')))
t = open(os.path.expanduser('~/.config/opencode/opencode.jsonc'), encoding='utf-8').read()
t = re.sub(r'^\s*//.*$', '', t, flags=re.M); t = re.sub(r',(\s*[}\]])', r'\1', t)
base = json.loads(t)['provider'][p]['options']['baseURL']
print('export JV_BASE=%s JV_KEY=%s' % (shlex.quote(base), shlex.quote(auth[p]['key'])))
PY
)"
fi
export JV_MODEL="$MODEL"; [ -n "$QUIRKS" ] && export JV_QUIRKS="$QUIRKS"
chmod 0400 "$W/dex/classes.dex"
# an uncaught exception would be SIGKILLed silently on ART: main is wrapped by LiveMain
env -u LD_LIBRARY_PATH -u LD_PRELOAD CLASSPATH="$W/dex/classes.dex" timeout 280 /system/bin/app_process -Xnoimage-dex2oat / app.jarvis.core.live.LiveCheck "$WHAT" 2>&1
