#!/usr/bin/env bash
# CI: the bridge APK = "personal" flavour (release code) provisioned with the bridge-only file (MCP token + neutral defaults, NO api keys).
#   build-bridge.sh device   -> build/outputs/Jarvis.apk          (arm64 native libs: the phone)
#   build-bridge.sh emu      -> build/outputs/Jarvis-emulator.apk      (same code, x86_64 native libs: the CI emulator)
# Env: BRIDGE_B64 (base64 of provisioned-bridge.json; a random one-off token is used when empty), EXPECT_CERT_SHA256 (optional signer check).
set -euo pipefail
cd "$(dirname "$0")/../.."
MODE="${1:-device}"; K="$HOME/.jarvis-keys"; mkdir -p "$K"
if [ -n "${BRIDGE_B64:-}" ]; then
  printf '%s' "$BRIDGE_B64" | base64 -d > "$K/provisioned-bridge.json"
else
  python3 - "$K" <<'PY'
import json, os, secrets, sys
json.dump({"secrets": {"mcp.token": secrets.token_hex(32)}, "settings": {"mcp.enabled": "true"}}, open(os.path.join(sys.argv[1], "provisioned-bridge.json"), "w"))
PY
  echo "bridge: no JARVIS_BRIDGE_B64 secret, random one-off token"
fi
chmod 600 "$K/provisioned-bridge.json"
. ./version.properties
mkdir -p build/outputs
if [ "$MODE" = emu ]; then
  # the emulator build may run destructive tools (sessions_delete, chat_seed, secrets_set); the phone build never does
  python3 - "$K" <<'PY'
import json, os, sys
k = sys.argv[1]
d = json.load(open(os.path.join(k, "provisioned-bridge.json")))
d.setdefault("settings", {})["mcp.allowDestructive"] = "true"
# no glass blur on the software GPU of the emulator: a full-glass frame takes seconds and the ANR kills the app; the glass passes set it explicitly
d["settings"]["ui.glass"] = "off"
json.dump(d, open(os.path.join(k, "provisioned-bridge-emu.json"), "w"))
PY
  chmod 600 "$K/provisioned-bridge-emu.json"
  bash tools/ci/native-x86_64.sh
  OUT="$PWD/build/outputs/Jarvis-emulator.apk"
  JV_NATIVE_DIR="$PWD/.cache/native-emu" JARVIS_PROVISIONED="$K/provisioned-bridge-emu.json" JV_APK_OUT="$OUT" bash build.sh personal
else
  OUT="$PWD/build/outputs/Jarvis.apk"
  JARVIS_PROVISIONED="$K/provisioned-bridge.json" JV_APK_OUT="$OUT" bash build.sh personal
fi
python3 tools/ci/check-apk.py "$OUT" ${EXPECT_CERT_SHA256:+--expect-cert-sha256 "$EXPECT_CERT_SHA256"}
ls -la "$OUT"
