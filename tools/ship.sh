#!/data/data/com.termux/files/usr/bin/bash
# Build the "bridge" APK for the owner and put it where he can install it with a file manager.
#   bash tools/ship.sh [--dir <worktree>] [--out <dir>]
# The APK is the "personal" flavour (release code, MCP bridge enabled, token baked in) but provisioned with the BRIDGE-ONLY file
# (~/.jarvis-keys/provisioned-bridge.json): the bridge token and neutral defaults, NO API keys. Keys the owner already typed into
# the app stay in the app (updates keep data); new ones can be pushed over the bridge with `jv.py secrets_set`.
set -euo pipefail
SRC="$HOME/jarvis"; OUT="/storage/emulated/0/Download"
while [ $# -gt 0 ]; do case "$1" in --dir) SRC="$2"; shift 2;; --out) OUT="$2"; shift 2;; *) echo "usage: ship.sh [--dir D] [--out D]"; exit 2;; esac; done
KEYS="$HOME/.jarvis-keys"; BR="$KEYS/provisioned-bridge.json"
[ -f "$KEYS/mcp-app.jarvis.json" ] || python3 "$SRC/tools/make-provisioned.py" >/dev/null     # creates the shared token file if missing
python3 - "$KEYS" <<'PY'
import json, os, sys
k = sys.argv[1]
full = json.load(open(os.path.join(k, "provisioned.json")))
tok = json.load(open(os.path.join(k, "mcp-app.jarvis.json")))["token"]
slim = {"secrets": {"mcp.token": tok}, "settings": {s: v for s, v in full["settings"].items() if s != "mcp.token"}}
slim["settings"]["mcp.enabled"] = "true"
p = os.path.join(k, "provisioned-bridge.json"); json.dump(slim, open(p, "w"), ensure_ascii=False, indent=1); os.chmod(p, 0o600)
print("bridge provisioning:", sorted(slim["secrets"]), "+", len(slim["settings"]), "settings")
PY
cd "$SRC"
JARVIS_PROVISIONED="$BR" bash build.sh personal 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | tail -n 3
. ./version.properties
APK="$SRC/build/outputs/jarvis-$VERSION_NAME-personal.apk"
[ -f "$APK" ] || { echo "no APK at $APK"; exit 1; }
apksigner verify --min-sdk-version "$MIN_SDK" "$APK" >/dev/null && echo "signature OK"
python3 "$SRC/tools/ci/check-apk.py" "$APK"
mkdir -p "$OUT"
DEST="$OUT/Jarvis.apk"
cp -f "$APK" "$DEST"
( cd "$OUT" && sha256sum "$(basename "$DEST")" > "$(basename "$DEST").sha256" )
echo "READY: $DEST ($(du -h "$DEST" | cut -f1)), sha256 $(cut -c1-16 "$DEST.sha256")..."
echo "Owner: file manager -> Download -> $(basename "$DEST") -> Install (over the old app: data stays) -> open Jarvis once."
