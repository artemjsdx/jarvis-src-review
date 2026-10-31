"""Shared paths and the ~/tg-mcp/.env file for the Telegram helper scripts.

Secrets (api hash, session strings, helper token) only ever go to the 0600 .env file or to memory;
nothing in this module prints values.
"""
import os
import re
import tempfile
from pathlib import Path

TG_HOME = Path(os.environ.get("TG_HOME") or Path.home() / "tg-mcp")
ENV_FILE = TG_HOME / ".env"
TOKEN_FILE = TG_HOME / "helper.token"
MCP_HOST = "127.0.0.1"
MCP_PORT = 8765
HELPER_HOST = "127.0.0.1"
HELPER_PORT = 8766
SESSION_PREFIX = "TELEGRAM_SESSION_STRING_"
LABEL_RE = re.compile(r"^[a-z][a-z0-9_]{0,15}$")
ENV_LINE_RE = re.compile(r"^([A-Z][A-Z0-9_]*)=(.*)$")


def canonical_label(label):
    """Same folding as upstream: lowercase, '-' becomes '_'. Raises ValueError for unusable labels."""
    value = (label or "").strip().lower().replace("-", "_")
    if not LABEL_RE.match(value):
        raise ValueError("Метка аккаунта: латиница, цифры и _, до 16 символов, с буквы.")
    return value


def session_key(label):
    return SESSION_PREFIX + canonical_label(label).upper()


def read_env(path=ENV_FILE):
    """Returns {KEY: value} for KEY=VALUE lines; comments and blank lines are skipped."""
    values = {}
    if not Path(path).exists():
        return values
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        m = ENV_LINE_RE.match(line.strip())
        if m:
            values[m.group(1)] = m.group(2)
    return values


def accounts(env):
    """Labels of accounts that have a session string, in file order."""
    labels = []
    for key, value in env.items():
        if key.startswith(SESSION_PREFIX) and value:
            labels.append(key[len(SESSION_PREFIX):].lower())
    return labels


def write_env(updates, remove=(), path=ENV_FILE):
    """Atomically updates KEY=VALUE pairs in the env file, keeping other lines, mode 0600."""
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    kept = []
    seen = set()
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            m = ENV_LINE_RE.match(line.strip())
            if not m:
                kept.append(line)
                continue
            key = m.group(1)
            if key in remove:
                continue
            if key in updates:
                kept.append(f"{key}={updates[key]}")
                seen.add(key)
            else:
                kept.append(line)
    for key, value in updates.items():
        if key not in seen and key not in remove:
            kept.append(f"{key}={value}")
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=".env.", suffix=".tmp")
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write("\n".join(kept) + "\n")
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def read_token(path=TOKEN_FILE):
    p = Path(path)
    return p.read_text(encoding="utf-8").strip() if p.exists() else ""
