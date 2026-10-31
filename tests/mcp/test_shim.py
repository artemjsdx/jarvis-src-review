#!/usr/bin/env python3
"""Scripted stdio-client tests for tools/jarvis-mcp-shim.py against tests/mcp/fake_server.py. No phone, no adb.

Run: python3 tests/mcp/test_shim.py   (exit code != 0 on any failure)
"""
import importlib.machinery
import importlib.util
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SHIM = os.path.join(ROOT, "tools", "jarvis-mcp-shim.py")
FAKE = os.path.join(HERE, "fake_server.py")
PKG = "app.jarvis.shimtest"
SCALE = "0.2"
HOST_TOOLS = {"device_status", "app_launch", "app_stop", "apk_install", "device_logcat", "device_screenshot"}
APP_DOWN = "Приложение не запущено. Откройте Jarvis на телефоне один раз (после установки или принудительной остановки фоновый запуск невозможен)"
NO_TOKEN_PREFIX = "Откройте Jarvis на телефоне (Настройки → Мост для агента → «Скопировать токен») и положите токен в "

TMP = tempfile.mkdtemp(prefix="jvshim-", dir=os.environ.get("TMPDIR") or None)
ALL_TOKENS = []
RESULTS = []


def load_shim_module():
    os.environ["JV_PKG"] = PKG
    loader = importlib.machinery.SourceFileLoader("jv_shim_mod", SHIM)
    spec = importlib.util.spec_from_loader("jv_shim_mod", loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


def check(name, ok, detail=""):
    RESULTS.append(bool(ok))
    print("%s  %s%s" % ("PASS" if ok else "FAIL", name, ("  -- " + str(detail)) if (detail and not ok) else ""), flush=True)


def new_token():
    t = secrets.token_hex(32)
    ALL_TOKENS.append(t)
    return t


def free_port_for(port):
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)  # like the fake server: TIME_WAIT leftovers do not count
    try:
        s.bind(("127.0.0.1", port))
        return True
    except OSError:
        return False
    finally:
        s.close()


# ---------------------------------------------------------------------------------------------------------------------
class FakeServer:
    def __init__(self, token, port=0, name="srv"):
        self.dir = os.path.join(TMP, name)
        os.makedirs(self.dir, exist_ok=True)
        self.token_file = os.path.join(self.dir, "token")
        self.port_file = os.path.join(self.dir, "port")
        self.token = token
        self.req_port = port
        self.proc = None
        self.port = None

    def start(self):
        if os.path.exists(self.port_file):
            os.remove(self.port_file)
        cmd = [sys.executable, FAKE, "--token-file", self.token_file, "--port-file", self.port_file,
               "--port", str(self.req_port), "--app", PKG]
        if self.token:
            cmd += ["--token", self.token]
        self.proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        t0 = time.time()
        while time.time() - t0 < 10:
            if os.path.exists(self.port_file):
                with open(self.port_file) as f:
                    txt = f.read().strip()
                if txt:
                    self.port = int(txt)
                    return self
            if self.proc.poll() is not None:
                raise RuntimeError("fake server died")
            time.sleep(0.05)
        raise RuntimeError("fake server did not start")

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self.proc = None

    def get(self, path):
        with urllib.request.urlopen("http://127.0.0.1:%d%s" % (self.port, path), timeout=5) as r:
            return json.loads(r.read().decode())

    def rotate(self, token=None):
        t = token or new_token()
        self.get("/__rotate?token=" + t)
        return t


class Shim:
    def __init__(self, keys_dir, env=None, name="shim"):
        e = dict(os.environ)
        for k in ("JV_MCP_TOKEN", "JV_MCP_PORT", "JV_MCP_HOST", "JV_DEVICE", "JV_AM_BIN"):
            e.pop(k, None)
        e.update({"JV_PKG": PKG, "JV_KEYS_DIR": keys_dir, "JV_NO_ADB": "1", "JV_SHIM_WAIT_SCALE": SCALE})
        e.update(env or {})
        self.keys_dir = keys_dir
        self.proc = subprocess.Popen([sys.executable, SHIM], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.PIPE, env=e)
        self.msgs, self.raw_out, self.raw_err, self.bad_lines = [], [], [], []
        self.cv = threading.Condition()
        self.next_id = 100
        threading.Thread(target=self._rd_out, daemon=True).start()
        threading.Thread(target=self._rd_err, daemon=True).start()

    def _rd_out(self):
        for line in self.proc.stdout:
            self.raw_out.append(line)
            try:
                m = json.loads(line)
            except ValueError:
                self.bad_lines.append(line)
                continue
            with self.cv:
                self.msgs.append(m)
                self.cv.notify_all()

    def _rd_err(self):
        for line in self.proc.stderr:
            self.raw_err.append(line)

    def send_raw(self, s):
        self.proc.stdin.write(s.encode("utf-8") + b"\n")
        self.proc.stdin.flush()

    def send(self, obj):
        self.send_raw(json.dumps(obj))

    def wait_msg(self, pred, timeout):
        end = time.time() + timeout
        with self.cv:
            while True:
                for m in self.msgs:
                    if pred(m):
                        return m
                left = end - time.time()
                if left <= 0:
                    return None
                self.cv.wait(left)

    def request(self, method, params=None, timeout=20, id_=None):
        self.next_id += 1
        rid = id_ if id_ is not None else self.next_id
        msg = {"jsonrpc": "2.0", "id": rid, "method": method}
        if params is not None:
            msg["params"] = params
        self.send(msg)
        return self.wait_msg(lambda m: isinstance(m, dict) and m.get("id") == rid and "method" not in m, timeout)

    def call(self, name, args=None, timeout=20):
        return self.request("tools/call", {"name": name, "arguments": args or {}}, timeout)

    def close(self):
        try:
            self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        time.sleep(0.1)

    def all_output(self):
        return b"".join(self.raw_out) + b"\n" + b"".join(self.raw_err)


def result_text(resp):
    try:
        return resp["result"]["content"][0]["text"]
    except (TypeError, KeyError, IndexError):
        return ""


def is_err(resp):
    return bool(resp) and resp.get("result", {}).get("isError") is True


def write_keys(keys_dir, **kw):
    os.makedirs(keys_dir, exist_ok=True)
    with open(os.path.join(keys_dir, "mcp-%s.json" % PKG), "w") as f:
        json.dump(kw, f)


def read_keys(keys_dir):
    try:
        with open(os.path.join(keys_dir, "mcp-%s.json" % PKG)) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


# ---------------------------------------------------------------------------------------------------------------------
def main():
    mod = load_shim_module()
    check("java hashCode port derivation", mod.java_hash("hello") == 99162322 and mod.candidate_ports("app.jarvis")[0] == 8767
          and mod.candidate_ports(PKG)[0] == 8768 + (mod.java_hash(PKG) & 0x7FFFFFFF) % 500)
    port = mod.candidate_ports(PKG)[0]
    if not free_port_for(port):
        check("computed port %d is free" % port, False, "choose another JV_PKG in the test")
        return finish()

    keys = os.path.join(TMP, "keys")
    t1 = new_token()
    write_keys(keys, token=t1)  # the owner pasted a bare token, no port: the port must come from the scan
    cache_file = os.path.join(keys, "mcp-%s-tools.json" % PKG)
    srv = FakeServer(t1, port=port)
    sh = Shim(keys)

    # (1) server DOWN: initialize / tools/list / ping answered locally
    t0 = time.time()
    r = sh.request("initialize", {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "t", "version": "1"}})
    sh.send({"jsonrpc": "2.0", "method": "notifications/initialized"})
    r2 = sh.request("tools/list")
    r3 = sh.request("ping")
    names = {t["name"] for t in (r2 or {}).get("result", {}).get("tools", [])}
    check("(1) initialize echoes client version, answered while app down",
          r and r["result"]["protocolVersion"] == "2025-03-26" and r["result"]["serverInfo"]["name"] and time.time() - t0 < 5, r)
    check("(1) tools/list while app down = host tools only (no cache)", names == HOST_TOOLS and not os.path.exists(cache_file), names)
    check("(1) ping while app down", r3 and r3.get("result") == {}, r3)
    rr = sh.request("initialize", {"protocolVersion": "1999-01-01"})
    check("(1) unknown client protocol version -> 2025-06-18", rr and rr["result"]["protocolVersion"] == "2025-06-18", rr)
    rd = sh.request("resources/list")
    check("(1) resources/list while down -> empty list, no error", rd and rd.get("result") == {"resources": []}, rd)

    # (2) server starts: tools/list now includes app tools + host tools and writes the cache
    srv.start()
    sh.request("resources/list")  # first successful contact: the stale tools/list served earlier must be announced
    lc = sh.wait_msg(lambda m: m.get("method") == "notifications/tools/list_changed", 3)
    check("(2) notifications/tools/list_changed after the app became reachable", lc is not None)
    r = sh.request("tools/list")
    names = [t["name"] for t in (r or {}).get("result", {}).get("tools", [])]
    check("(2) tools/list = echo + journal_tail + host tools", {"echo", "journal_tail"} | HOST_TOOLS == set(names), names)
    cache_ok = False
    try:
        with open(cache_file) as f:
            cache_ok = {t["name"] for t in json.load(f)} == {"echo", "journal_tail"}
    except (OSError, ValueError):
        pass
    check("(2) tools cache written", cache_ok)

    # (3) tools/call echo; token from the keys file, port by scan; keys file updated with the port
    r = sh.call("echo", {"text": "привет"})
    check("(3) tools/call echo forwarded", r and result_text(r) == "echo: привет" and not is_err(r), r)
    kf = read_keys(keys)
    check("(3) keys file updated with port, mode 600", kf and kf.get("port") == port and kf.get("token") == t1
          and (os.stat(os.path.join(keys, "mcp-%s.json" % PKG)).st_mode & 0o777) == 0o600, kf)
    r = sh.call("journal_tail", {"n": 2})
    check("(3) journal_tail", r and result_text(r).count("\n") == 1, r)
    r = sh.request("resources/list")
    check("(3) resources/list forwarded", r and r["result"]["resources"][0]["uri"] == "jarvis://state", r)
    r = sh.request("resources/read", {"uri": "jarvis://state"})
    check("(3) resources/read forwarded", r and "fake" in r["result"]["contents"][0]["text"], r)
    r = sh.call("no_such_tool")
    check("(3) unknown app tool -> JSON-RPC error passed through", r and r.get("error", {}).get("code") == -32602, r)
    r = sh.call("device_status")
    st = {}
    try:
        st = json.loads(result_text(r))
    except ValueError:
        pass
    check("(3) device_status: app healthy, token known, no adb",
          st.get("app", {}).get("healthy") is True and st.get("app", {}).get("port") == port and st.get("token", {}).get("known") is True
          and st.get("adb", {}).get("devices") == [], st)
    r = sh.call("apk_install", {"path": "/nonexistent.apk"})
    check("(3) apk_install: missing file -> clear error", is_err(r) and "не найден" in result_text(r), r)
    apk = os.path.join(TMP, "x.apk")
    open(apk, "w").close()
    r = sh.call("apk_install", {"path": apk})
    check("(3) apk_install: no device -> clear error", is_err(r) and "устройств" in result_text(r), r)
    r = sh.call("device_screenshot")
    check("(3) device_screenshot: no device -> clear error", is_err(r) and "adb" in result_text(r), r)
    t_lc = time.time()
    r = sh.call("device_logcat", {"n": 5, "level": "W"}, timeout=30)
    check("(3) device_logcat answers (text or clear error), %.1fs" % (time.time() - t_lc), r is not None and bool(result_text(r)), r)
    r = sh.call("device_logcat", {"level": "X"})
    check("(3) device_logcat bad level rejected", is_err(r), r)

    # (4) token rotation -> 401 -> automatic refresh (the owner pastes the new token into the keys file)
    t2 = srv.rotate()
    write_keys(keys, token=t2, port=port)
    u0 = srv.get("/__stats")["unauthorized"]
    r = sh.call("echo", {"text": "after-rotate"})
    check("(4) rotated token: 401 then refresh from keys file, call succeeds",
          r and result_text(r) == "echo: after-rotate" and srv.get("/__stats")["unauthorized"] > u0, r)
    t3 = srv.rotate()  # nobody tells the shim
    r = sh.call("echo", {"text": "x"})
    check("(4) rotated token nobody supplied -> clear auth error (isError)", is_err(r) and "401" in result_text(r) and "токен" in result_text(r).lower(), r)
    write_keys(keys, token=t3)
    r = sh.call("echo", {"text": "recovered"})
    check("(4) token pasted later -> works again", r and result_text(r) == "echo: recovered", r)
    kf = read_keys(keys)
    check("(4) keys file keeps the verified token and port", kf and kf.get("token") == t3 and kf.get("port") == port, kf)

    # app restart: sessions forgotten -> 404 -> re-initialize
    i0 = srv.get("/__stats")["initialize"]
    srv.get("/__restart")
    r = sh.call("echo", {"text": "after-restart"})
    check("(4b) app restarted (unknown session) -> shim re-initializes", r and result_text(r) == "echo: after-restart"
          and srv.get("/__stats")["initialize"] == i0 + 1, r)

    # (6) ping while a slow call runs; timeoutMs raises the read timeout
    sh.send({"jsonrpc": "2.0", "id": 9001, "method": "tools/call", "params": {"name": "echo", "arguments": {"text": "slow", "delayMs": 2500}}})
    time.sleep(0.3)
    tp = time.time()
    p = sh.request("ping", id_=9002)
    ping_dt = time.time() - tp
    slow_done_early = any(m.get("id") == 9001 for m in sh.msgs)
    slow = sh.wait_msg(lambda m: m.get("id") == 9001, 15)
    check("(6) ping answered while a slow tools/call is running (%.2fs)" % ping_dt, p and ping_dt < 1.0 and not slow_done_early, p)
    check("(6) slow call completes", slow and result_text(slow) == "echo: slow", slow)

    # (7) invalid input
    sh.send_raw("{this is not json")
    bad = sh.wait_msg(lambda m: m.get("error", {}).get("code") == -32700, 5)
    check("(7) invalid JSON line -> -32700 with id null", bad and bad.get("id", "missing") is None, bad)
    r = sh.request("no/such/method")
    check("(7) unknown method -> -32601", r and r.get("error", {}).get("code") == -32601, r)
    sh.send([{"jsonrpc": "2.0", "id": 7001, "method": "ping"}, {"jsonrpc": "2.0", "method": "notifications/initialized"},
             {"jsonrpc": "2.0", "id": 7002, "method": "nope"}])
    b = sh.wait_msg(lambda m: isinstance(m, list), 5)
    check("(7) batch array answered as array", b and len(b) == 2 and {x["id"] for x in b} == {7001, 7002}, b)
    sh.send_raw("[]")
    e = sh.wait_msg(lambda m: isinstance(m, dict) and m.get("error", {}).get("code") == -32600 and m.get("id") is None, 5)
    check("(7) empty batch -> -32600", e is not None, e)
    sh.send_raw("42")
    e = sh.wait_msg(lambda m: isinstance(m, dict) and m.get("error", {}).get("code") == -32600 and m.get("id") is None and m is not e, 5)
    check("(7) non-object JSON -> -32600", e is not None)
    r = sh.request("ping")
    check("(7) still alive after garbage", r and r.get("result") == {}, r)

    # app_stop without adb goes through app_quit in the bridge
    q0 = srv.get("/__stats")["quit"]
    r = sh.call("app_stop")
    check("(9) app_stop without adb -> app_quit via bridge", r and not is_err(r) and srv.get("/__stats")["quit"] == q0 + 1, r)

    # (5) server stops -> tool call returns the Russian 'not running' error quickly
    srv.stop()
    t0 = time.time()
    r = sh.call("echo", {"text": "dead"}, timeout=50)
    dt = time.time() - t0
    check("(5) app down: tools/call -> isError with the 'app not running' text (%.1fs)" % dt,
          r and is_err(r) and result_text(r) == APP_DOWN and dt < 45, r)
    r = sh.call("app_launch")
    check("(5) app_launch with no way to start -> same error", r and is_err(r) and result_text(r) == APP_DOWN, r)
    t0 = time.time()
    r = sh.request("tools/list")
    names = {t["name"] for t in r["result"]["tools"]}
    check("(5) tools/list after stop: cached app tools + host tools, fast (%.1fs)" % (time.time() - t0),
          {"echo", "journal_tail"} | HOST_TOOLS == names and time.time() - t0 < 10, names)
    r = sh.call("device_status")
    st = json.loads(result_text(r))
    check("(5) device_status reports app not healthy", st["app"]["healthy"] is False, st)
    sh.close()
    check("(7) EOF on stdin -> clean exit code 0", sh.proc.returncode == 0, sh.proc.returncode)
    check("(7) stdout carried only JSON lines", not sh.bad_lines, sh.bad_lines[:2])
    outputs = [sh]

    # (9) ensure_app via a fake Termux `am`: the broadcast "starts the app"
    keys2 = os.path.join(TMP, "keys2")
    t4 = new_token()
    write_keys(keys2, token=t4)
    srv2 = FakeServer(t4, port=port, name="srv2")
    am_log = os.path.join(TMP, "am.log")
    fake_am = os.path.join(TMP, "fake_am.py")
    with open(fake_am, "w") as f:
        f.write("import subprocess, sys\n"
                "open(%r, 'a').write(' '.join(sys.argv[1:]) + '\\n')\n"
                "subprocess.Popen([sys.executable, %r, '--token-file', %r, '--port', %r, '--app', %r, '--token', %r],\n"
                "                 stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)\n"
                % (am_log, FAKE, srv2.token_file, str(port), PKG, t4))
    sh2 = Shim(keys2, {"JV_AM_BIN": "%s %s" % (sys.executable, fake_am), "JV_SHIM_WAIT_SCALE": "0.5"}, name="shim2")
    sh2.request("initialize", {"protocolVersion": "2025-06-18"})
    t0 = time.time()
    r = sh2.call("echo", {"text": "autostart"}, timeout=45)
    am_args = open(am_log).read() if os.path.exists(am_log) else ""
    check("(9) app down + Termux am broadcast -> shim starts the app and the call succeeds (%.1fs)" % (time.time() - t0),
          r and result_text(r) == "echo: autostart" and "McpStartReceiver" in am_args and "app.jarvis.mcp.START" in am_args and PKG in am_args,
          (r, am_args))
    # stop the fake server started by am
    try:
        requests_pid = None
        with urllib.request.urlopen("http://127.0.0.1:%d/health" % port, timeout=3) as h:
            requests_pid = json.loads(h.read().decode()).get("pid")
        if requests_pid:
            os.kill(requests_pid, 15)
    except Exception:
        pass
    sh2.close()
    outputs.append(sh2)

    # (5b) `am` exists but the app never comes up: bounded wait (12 s * scale), then the Russian error
    keys5 = os.path.join(TMP, "keys5")
    write_keys(keys5, token=new_token())
    sh5 = Shim(keys5, {"JV_AM_BIN": "%s -c pass" % sys.executable})
    t0 = time.time()
    r = sh5.call("echo", {"text": "nope"}, timeout=50)
    dt = time.time() - t0
    check("(5b) am ran but app never started: waits ~12s*scale then APP_DOWN error (%.1fs)" % dt,
          r and is_err(r) and result_text(r) == APP_DOWN and 1.5 < dt < 10, (dt, r))
    sh5.close()
    outputs.append(sh5)

    # (10) JVM/smoke mode: JV_MCP_PORT + JV_MCP_TOKEN only; nothing may be persisted
    keys3 = os.path.join(TMP, "keys3")
    t5 = new_token()
    srv3 = FakeServer(t5, port=0, name="srv3").start()
    sh3 = Shim(keys3, {"JV_MCP_PORT": str(srv3.port), "JV_MCP_TOKEN": t5})
    r = sh3.call("echo", {"text": "env"})
    check("(10) env JV_MCP_PORT + JV_MCP_TOKEN (arbitrary port) works", r and result_text(r) == "echo: env", r)
    check("(10) env-supplied token is not persisted", not os.path.exists(os.path.join(keys3, "mcp-%s.json" % PKG)))
    sh3.close()
    outputs.append(sh3)
    srv3.stop()

    # (11) no token anywhere: the exact instruction for the owner
    keys4 = os.path.join(TMP, "keys4")
    srv4 = FakeServer(new_token(), port=port, name="srv4").start()
    sh4 = Shim(keys4)
    r = sh4.call("echo", {"text": "x"})
    expect = NO_TOKEN_PREFIX + os.path.join(keys4, "mcp-%s.json" % PKG)
    check("(11) no token -> exact owner instruction with the real keys path", r and is_err(r) and result_text(r) == expect, r)
    # owner drops the token into the file afterwards
    write_keys(keys4, token=srv4.token)
    r = sh4.call("echo", {"text": "y"})
    check("(11) token file appears later -> works without restarting the shim", r and result_text(r) == "echo: y", r)
    sh4.close()
    outputs.append(sh4)
    srv4.stop()

    # (8) tokens never leak into stdout/stderr
    leaked = [i for i, o in enumerate(outputs) for t in ALL_TOKENS if t.encode() in o.all_output()]
    tails = b"".join(o.all_output() for o in outputs)
    check("(8) no token in any shim stdout/stderr", not leaked, leaked)
    check("(8) shim stderr is non-empty log only (sanity)", any(o.raw_err for o in outputs))
    del tails
    return finish()


def finish():
    shutil.rmtree(TMP, ignore_errors=True)
    bad = RESULTS.count(False)
    print("\n%d checks, %d failed" % (len(RESULTS), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    try:
        rc = main()
    except Exception:
        import traceback
        traceback.print_exc()
        rc = 2
    sys.exit(rc)
