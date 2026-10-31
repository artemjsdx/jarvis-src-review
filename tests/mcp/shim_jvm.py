#!/usr/bin/env python3
"""Scripted stdio client: tools/jarvis-mcp-shim.py against the REAL Java MCP server (McpSmokeServer, fake runtime). No phone, no adb.
Scenarios: initialize + tools/list + tools/call, stale token -> 401 -> refresh from the key file, wrong port/app down, restart of the app.
Run: python3 tests/mcp/shim_jvm.py"""
import json, os, secrets, subprocess, sys, tempfile, time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SHIM = os.path.join(ROOT, "tools", "jarvis-mcp-shim.py")
KEYS = tempfile.mkdtemp(prefix="jvshimjvm-", dir=os.environ.get("TMPDIR") or None)
RES = []


def check(name, ok, detail=""):
    RES.append(bool(ok)); print("%s  %s%s" % ("PASS" if ok else "FAIL", name, "" if ok or not detail else "  -- " + str(detail)), flush=True)


def start_server(port=0, token=None):
    args = ["bash", os.path.join(ROOT, "tools", "mcp-jvm-server.sh")] + ([str(port), token] if token else [])
    p = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    while True:
        line = p.stdout.readline()
        if not line: raise SystemExit("server did not start")
        if line.startswith("READY "):
            d = json.loads(line[6:]); return p, d["port"], d["token"]


def stop_server(p):
    try: p.stdin.close(); p.wait(timeout=10)
    except Exception: p.kill()


class Client:
    def __init__(self):
        env = dict(os.environ, JV_KEYS_DIR=KEYS, JV_NO_ADB="1", JV_PKG="app.jarvis", JV_SHIM_WAIT_SCALE="0.05")
        for k in ("JV_MCP_TOKEN", "JV_MCP_PORT"): env.pop(k, None)
        self.p = subprocess.Popen([sys.executable, SHIM], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
        self.n = 0

    def rpc(self, method, params=None):
        self.n += 1
        self.p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.n, "method": method, "params": params or {}}) + "\n"); self.p.stdin.flush()
        while True:
            line = self.p.stdout.readline()
            if not line: return {"error": "eof"}
            m = json.loads(line)
            if m.get("id") == self.n: return m

    def call(self, name, args=None):
        r = self.rpc("tools/call", {"name": name, "arguments": args or {}})
        res = r.get("result", {})
        txt = next((c["text"] for c in res.get("content", []) if c.get("type") == "text"), "")
        return res.get("isError", False), txt

    def close(self):
        try: self.p.stdin.close(); self.p.wait(timeout=10)
        except Exception: self.p.kill()
        return self.p.stderr.read()


def write_keys(token, port):
    f = os.path.join(KEYS, "mcp-app.jarvis.json")
    json.dump({"token": token, "port": port}, open(f, "w")); os.chmod(f, 0o600)


srv, port, token = start_server()
write_keys(token, port)
c = Client()
r = c.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "t", "version": "1"}})
check("initialize answered by the shim", "result" in r and r["result"].get("serverInfo"))
names = {t["name"] for t in c.rpc("tools/list").get("result", {}).get("tools", [])}
check("tools/list = app tools + host tools", {"chat_send", "journal_tail", "app_info", "device_status", "app_launch"} <= names, sorted(names)[:8])
err, txt = c.call("app_info")
check("tools/call app_info through the Java server", not err and json.loads(txt).get("flavor") == "jvm-smoke", txt[:150])
err, txt = c.call("chat_send", {"text": "Ответь одним словом: ок"})
check("tools/call chat_send (reply)", not err and "ок" in json.loads(txt)["reply"], txt[:150])
err, txt = c.call("settings_set", {"key": "mcp.allowAudio", "value": "true"})
check("protected setting refused by the server", err and "FORBIDDEN" in txt, txt[:150])
err, txt = c.call("secrets_get", {"name": "x"})
check("destructive tool gated", err and "FORBIDDEN" in txt, txt[:150])

# stale token -> 401 -> clear error; then the key file is fixed -> next call works without restarting the shim
write_keys("0" * 64, port)
time.sleep(0.3)
c2 = Client()
c2.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "t", "version": "1"}})
err, txt = c2.call("app_info")
check("wrong token reports a clear auth error", err and ("токен" in txt.lower() or "401" in txt), txt[:200])
write_keys(token, port)
err, txt = c2.call("app_info")
check("token refreshed from the key file after 401", not err and "jvm-smoke" in txt, txt[:200])
check("token never printed", token not in c2.close() and token not in txt)

# app down, then restarted on the same port with a NEW token (key file updated)
stop_server(srv)
err, txt = c.call("app_info")
check("app down: Russian 'not running' error", err and "Приложение не запущено" in txt, txt[:200])
srv, port2, token2 = start_server(port, secrets.token_hex(32))
write_keys(token2, port2)
err, txt = c.call("app_info")
check("app restarted with a new token: call works again", not err and "jvm-smoke" in txt, txt[:200])
errlog = c.close()
check("token never in the shim log", token not in errlog and token2 not in errlog)
stop_server(srv)
print("\n%d/%d checks passed" % (sum(RES), len(RES)))
sys.exit(0 if all(RES) else 1)
