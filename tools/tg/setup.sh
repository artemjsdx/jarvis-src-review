#!/data/data/com.termux/files/usr/bin/bash
# Provisions the Telegram MCP server in ~/tg-mcp (Termux). Idempotent: safe to re-run after an update.
# Installs nothing compiled: Termux python already ships telethon, pydantic and Pillow, so the venv reuses them.
# Usage: bash tools/tg/setup.sh          (TG_HOME overrides ~/tg-mcp)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TG_HOME="${TG_HOME:-$HOME/tg-mcp}"
. "$HERE/PINNED"
say() { printf '[setup] %s\n' "$*"; }

umask 077
mkdir -p "$TG_HOME/bin"
chmod 700 "$TG_HOME"

for cmd in python3 git curl; do
  if ! command -v "$cmd" >/dev/null 2>&1; then say "installing $cmd"; pkg install -y "$cmd"; fi
done
# jsonschema (an mcp dependency) needs rpds-py, a Rust extension; Termux ships it prebuilt, so pip never compiles it.
if ! python3 -c 'import rpds' 2>/dev/null; then say "installing python-rpds-py (prebuilt)"; pkg install -y python-rpds-py; fi
say "python: $(python3 -V 2>&1), git: $(git --version | cut -d' ' -f3)"

VENV="$TG_HOME/venv"
if [ ! -x "$VENV/bin/python" ]; then
  say "creating venv (system site-packages on)"
  python3 -m venv --system-site-packages "$VENV"
fi

SRC="$TG_HOME/src"
if [ ! -d "$SRC/.git" ]; then
  git init -q "$SRC"
  git -C "$SRC" remote add origin "$REPO"
fi
if [ "$(git -C "$SRC" rev-parse --verify -q HEAD || true)" != "$COMMIT" ]; then
  say "fetching $REPO @ ${COMMIT:0:12} (v$VERSION)"
  git -C "$SRC" fetch -q --depth 1 origin "$COMMIT"
  git -C "$SRC" checkout -q -f FETCH_HEAD
else
  say "source already at ${COMMIT:0:12}"
fi

say "installing the pinned project into the venv"
"$VENV/bin/pip" install -q --prefer-binary -e "$SRC"
"$VENV/bin/python" -c 'import importlib.metadata as m; print("[setup] installed: telegram-mcp", m.version("telegram-mcp"), "telethon", m.version("telethon"), "mcp", m.version("mcp"))'

install -m 700 "$HERE/run.sh" "$TG_HOME/bin/run.sh"
install -m 700 "$HERE/status.py" "$TG_HOME/bin/status.py"
install -m 600 "$HERE/login.py" "$TG_HOME/bin/login.py"
install -m 600 "$HERE/tg_common.py" "$TG_HOME/bin/tg_common.py"
install -m 644 "$HERE/PINNED" "$TG_HOME/bin/PINNED"

if [ ! -f "$TG_HOME/.env" ]; then
  cat > "$TG_HOME/.env" <<'EOF'
MCP_TRANSPORT=http
MCP_HOST=127.0.0.1
MCP_PORT=8765
TELEGRAM_EXPOSED_TOOLS=all
TELEGRAM_TOOL_TIMEOUT_SECONDS=55
EOF
  say "created .env (no accounts yet)"
fi
chmod 600 "$TG_HOME/.env"

if [ ! -s "$TG_HOME/helper.token" ]; then
  "$VENV/bin/python" -c 'import secrets; print(secrets.token_urlsafe(32))' > "$TG_HOME/helper.token"
  chmod 600 "$TG_HOME/helper.token"
  say "created helper token (file, mode 600)"
fi

say "done. Next: bash ~/tg-mcp/bin/run.sh helper-start   (then add an account in the app)"
