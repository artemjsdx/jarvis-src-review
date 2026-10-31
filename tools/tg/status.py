"""Health report for the Telegram MCP stack in ~/tg-mcp. Prints labels and counts only, never secret values.

python3 status.py            human-readable report
python3 status.py --json     the same as one JSON object (used by the app page and by tests)
"""
import json
import os
import socket
import sys
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tg_common as tc  # noqa: E402

MCP_URL = f"http://{tc.MCP_HOST}:{tc.MCP_PORT}/mcp"


def pid_alive(pidfile):
    p = Path(pidfile)
    if not p.exists():
        return None
    try:
        pid = int(p.read_text().strip())
        os.kill(pid, 0)
        return pid
    except (ValueError, OSError):
        return None


def port_open(host, port, timeout=1.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def mcp_initialize(timeout=5.0):
    """Returns (ok, serverInfo dict or error string). Accepts plain JSON or a one-event SSE body."""
    body = json.dumps({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                   "clientInfo": {"name": "tg-status", "version": "1"}},
    }).encode()
    req = urllib.request.Request(MCP_URL, data=body, method="POST", headers={
        "Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
    except (urllib.error.URLError, OSError) as e:
        return False, f"нет ответа: {type(e).__name__}"
    for line in raw.splitlines():
        if line.startswith("data:"):
            raw = line[5:].strip()
            break
    try:
        result = json.loads(raw).get("result") or {}
    except json.JSONDecodeError:
        return False, "ответ не JSON"
    info = result.get("serverInfo")
    return (bool(info), info or "нет serverInfo")


def collect():
    env = tc.read_env()
    version = ""
    pinned = tc.TG_HOME / "bin" / "PINNED"
    if pinned.exists():
        for line in pinned.read_text().splitlines():
            if line.startswith("VERSION="):
                version = line.split("=", 1)[1].strip()
    server_pid = pid_alive(tc.TG_HOME / "run.pid")
    port = port_open(tc.MCP_HOST, tc.MCP_PORT)
    ok, info = mcp_initialize() if port else (False, "порт закрыт")
    helper_pid = pid_alive(tc.TG_HOME / "helper.pid")
    return {
        "pinned_version": version,
        "server_loop_pid": server_pid,
        "server_port_open": port,
        "mcp_ok": ok,
        "mcp_server": info if ok else None,
        "mcp_error": None if ok else info,
        "accounts": tc.accounts(env),
        "exposed_tools": env.get("TELEGRAM_EXPOSED_TOOLS", "all"),
        "helper_pid": helper_pid,
        "helper_port_open": port_open(tc.HELPER_HOST, tc.HELPER_PORT),
        "env_mode": oct(tc.ENV_FILE.stat().st_mode & 0o777) if tc.ENV_FILE.exists() else None,
    }


def render(s):
    lines = [
        f"сервер: {'работает, pid ' + str(s['server_loop_pid']) if s['server_loop_pid'] else 'остановлен'}"
        f" · порт {tc.MCP_PORT} {'открыт' if s['server_port_open'] else 'закрыт'}",
        f"MCP initialize: {'ок — ' + str(s['mcp_server'].get('name', '?')) + ' ' + str(s['mcp_server'].get('version', '')) if s['mcp_ok'] else s['mcp_error']}",
        f"версия upstream: {s['pinned_version'] or 'неизвестна'}",
        f"аккаунты ({len(s['accounts'])}): {', '.join(s['accounts']) or 'нет'}",
        f"права: TELEGRAM_EXPOSED_TOOLS={s['exposed_tools']}",
        f"помощник входа: {'работает' if s['helper_port_open'] else 'остановлен'}",
        f"права файла .env: {s['env_mode'] or 'нет файла'}",
    ]
    return "\n".join(lines)


def main(argv):
    s = collect()
    print(json.dumps(s, ensure_ascii=False, indent=2) if "--json" in argv else render(s))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
