#!/data/data/com.termux/files/usr/bin/bash
# Live protocol check against a provider configured for OpenCode on this phone. Reads the key in-process only
# (never printed, never stored). Usage: bash tools/live-check.sh <opencode-provider-id> <model> [stream|agent|cancel|all] [inception]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROV="${1:?provider id}"; MODEL="${2:-}"; WHAT="${3:-all}"; QUIRKS="${4:-}"
OUT="$ROOT/build/test"; L="$ROOT/.cache/testlibs"; LIBS="$L/json-20240303.jar"
[ -d "$OUT/main" ] || bash "$ROOT/tools/run-tests.sh" >/dev/null
mkdir -p "$OUT/live"; javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$OUT/main:$LIBS" -d "$OUT/live" "$ROOT/app/src/test/java/app/jarvis/core/live/LiveCheck.java"
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
export JV_MODEL="$MODEL"; [ -n "$QUIRKS" ] && export JV_QUIRKS="$QUIRKS"
exec java -Dfile.encoding=UTF-8 -cp "$OUT/main:$OUT/live:$LIBS" app.jarvis.core.live.LiveCheck "$WHAT"
