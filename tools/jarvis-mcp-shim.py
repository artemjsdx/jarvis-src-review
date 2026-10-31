#!/usr/bin/env python3
"""jarvis-mcp-shim: stdio MCP server that bridges an agent (opencode, Claude, ...) to the Jarvis app's MCP server.

    MCP client --stdio (JSON lines)--> this shim --HTTP http://127.0.0.1:<port>/mcp (Bearer token)--> McpServer in the app

Python 3 stdlib only. stdout carries ONLY JSON-RPC lines, logs go to stderr (the token is never printed).

The shim answers initialize / notifications / ping itself, instantly, even when the app is down. tools/list = the app's
tools (cached on disk while the app is unreachable) + host-side tools (device_status, app_launch, app_stop, apk_install,
device_logcat, device_screenshot). Everything else is forwarded to the app.

Works WITHOUT adb: the phone's loopback is shared with Termux. Endpoint/token resolution order:
  env JV_MCP_TOKEN / JV_MCP_PORT -> <keysdir>/mcp-<pkg>.json {"token","port"} -> adb (only if a device is listed)
  -> port scan of /health (no token needed; candidate ports 8767..8780 + the port derived from the package name).

Environment:
  JV_PKG (app.jarvis)  JV_MCP_TOKEN  JV_MCP_PORT  JV_MCP_HOST (127.0.0.1)  ANDROID_ADB_SERVER_PORT (5038)  JV_DEVICE
  JV_KEYS_DIR (~/.jarvis-keys)  JV_NO_ADB=1 (adb absent, Termux `am` skipped)
  JV_SHIM_WAIT_SCALE (1.0; multiplies every wait: 12 s health wait after a launch attempt, ~40 s per-call budget)
  JV_AM_BIN (override the Termux `am` command, used by tests)  JV_SHIM_DEBUG=1 (more logging)

A JVM smoke mode only needs JV_MCP_PORT + JV_MCP_TOKEN pointing at a Java test server (env values are never persisted).
"""
import base64
import concurrent.futures
import http.client
import json
import os
import re
import shlex
import shutil
import socket
import subprocess
import sys
import threading
import time

SHIM_NAME = "jarvis-mcp-shim"
SHIM_VERSION = "1.0"
DEFAULT_PROTOCOL = "2025-06-18"
SUPPORTED_PROTOCOLS = ("2025-06-18", "2025-03-26", "2024-11-05")

PKG = os.environ.get("JV_PKG") or "app.jarvis"
HOST = os.environ.get("JV_MCP_HOST") or "127.0.0.1"
NO_ADB = os.environ.get("JV_NO_ADB", "") not in ("", "0", "false")
DEBUG = os.environ.get("JV_SHIM_DEBUG", "") not in ("", "0", "false")
try:
    SCALE = float(os.environ.get("JV_SHIM_WAIT_SCALE") or 1.0)
except ValueError:
    SCALE = 1.0
SCALE = max(SCALE, 0.01)

CONNECT_TIMEOUT = 3.0
DEFAULT_CALL_TIMEOUT = 150.0
OTHER_CALL_TIMEOUT = 60.0
HEALTH_WAIT = 12.0   # after each launch attempt
CALL_BUDGET = 40.0   # total waiting (launch attempts) per call
TOKEN_RE = re.compile(r"^[\x21-\x7e]{16,256}$")
HEX64_RE = re.compile(r"[0-9a-fA-F]{40,}")
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def _default_keys_dir():
    d = os.environ.get("JV_KEYS_DIR")
    if d:
        return os.path.expanduser(d)
    termux = "/data/data/com.termux/files/home/.jarvis-keys"
    if os.path.isdir(termux):
        return termux
    return os.path.expanduser("~/.jarvis-keys")


KEYS_DIR = _default_keys_dir()
KEYS_FILE = os.path.join(KEYS_DIR, "mcp-%s.json" % PKG)
TOOLS_CACHE = os.path.join(KEYS_DIR, "mcp-%s-tools.json" % PKG)

NO_TOKEN_MSG = ("Откройте Jarvis на телефоне (Настройки → Мост для агента → «Скопировать токен») "
                "и положите токен в %s" % KEYS_FILE)
APP_DOWN_MSG = ("Приложение не запущено. Откройте Jarvis на телефоне один раз "
                "(после установки или принудительной остановки фоновый запуск невозможен)")
AUTH_FAIL_MSG = ("Приложение отклонило токен (HTTP 401), и новый токен найти не удалось. Скопируйте актуальный токен "
                 "(Настройки → Мост для агента → «Скопировать токен») в %s" % KEYS_FILE)


def java_hash(s):
    h = 0
    b = s.encode("utf-16-be")
    for i in range(0, len(b), 2):
        h = (31 * h + ((b[i] << 8) | b[i + 1])) & 0xFFFFFFFF
    return h


def candidate_ports(pkg):
    ports = []
    if pkg != "app.jarvis":
        ports.append(8768 + (java_hash(pkg) & 0x7FFFFFFF) % 500)
    ports.extend(range(8767, 8781))
    seen, out = set(), []
    for p in ports:
        if p not in seen:
            seen.add(p)
            out.append(p)
    return out


# ---------------------------------------------------------------------------------------------------------------------
# logging with token masking
# ---------------------------------------------------------------------------------------------------------------------
_secrets = set()
_secrets_lock = threading.Lock()


def remember_secret(tok):
    if tok and len(tok) >= 8:
        with _secrets_lock:
            _secrets.add(tok)


def mask(s):
    s = str(s)
    with _secrets_lock:
        secrets = sorted(_secrets, key=len, reverse=True)
    for t in secrets:
        s = s.replace(t, "…" + t[-4:])
    return HEX64_RE.sub(lambda m: "…" + m.group(0)[-4:], s)


def log(msg):
    try:
        sys.stderr.write("[%s] %s\n" % (SHIM_NAME, mask(msg)))
        sys.stderr.flush()
    except Exception:
        pass


def dlog(msg):
    if DEBUG:
        log(msg)


# ---------------------------------------------------------------------------------------------------------------------
# errors
# ---------------------------------------------------------------------------------------------------------------------
class AppDown(Exception):
    pass


class AppNotRunning(Exception):
    pass


class NoToken(Exception):
    pass


class Unauthorized(Exception):
    def __init__(self, token):
        Exception.__init__(self, "401")
        self.token = token


class AuthFailed(Exception):
    pass


class SessionGone(Exception):
    def __init__(self, sid):
        Exception.__init__(self, "session gone")
        self.sid = sid


class CallTimeout(Exception):
    pass


class AppHttpError(Exception):
    pass


class RpcError(Exception):
    def __init__(self, code, message, data=None):
        Exception.__init__(self, message)
        self.code, self.message, self.data = code, message, data


# ---------------------------------------------------------------------------------------------------------------------
# subprocess / adb helpers
# ---------------------------------------------------------------------------------------------------------------------
def run_cmd(cmd, timeout, env=None):
    """Returns (rc, stdout_bytes, stderr_bytes); rc None on timeout, -1 if the binary cannot be started."""
    try:
        p = subprocess.run(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           timeout=timeout, env=env)
        return p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired as e:
        return None, e.stdout or b"", e.stderr or b""
    except (OSError, ValueError) as e:
        return -1, b"", str(e).encode("utf-8", "replace")


def text(b):
    return (b or b"").decode("utf-8", "replace").strip()


def tcp_open(host, port, timeout):
    try:
        s = socket.create_connection((host, port), timeout=timeout)
        s.close()
        return True
    except OSError:
        return False


class Adb:
    def __init__(self):
        self.port = int(os.environ.get("ANDROID_ADB_SERVER_PORT") or 5038)
        self.env = dict(os.environ)
        self.env["ANDROID_ADB_SERVER_PORT"] = str(self.port)
        self._lock = threading.Lock()
        self._cache = None
        self._cache_t = 0.0

    def available(self):
        return (not NO_ADB) and shutil.which("adb") is not None

    def devices(self, fresh=False):
        """-> dict(devices=[serial], other=[(serial,state)], selected=serial|None, note=str)."""
        if not self.available():
            return {"devices": [], "other": [], "selected": None, "note": "adb недоступен", "server": False}
        with self._lock:
            if not fresh and self._cache and time.monotonic() - self._cache_t < 5.0:
                return self._cache
            res = self._query()
            self._cache, self._cache_t = res, time.monotonic()
            return res

    def _query(self):
        res = {"devices": [], "other": [], "selected": None, "note": "", "server": False}
        # do not let `adb devices` spawn a server: only talk to one that already listens
        if not tcp_open("127.0.0.1", self.port, 0.5):
            res["note"] = "сервер adb не запущен на порту %d" % self.port
            return res
        res["server"] = True
        rc, out, _err = run_cmd(["adb", "devices"], 3.0, self.env)
        if rc != 0:
            res["note"] = "adb devices не ответил за 3 с"
            return res
        for line in text(out).splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2:
                if parts[1] == "device":
                    res["devices"].append(parts[0])
                else:
                    res["other"].append((parts[0], parts[1]))
        want = os.environ.get("JV_DEVICE")
        if want:
            if want in res["devices"]:
                res["selected"] = want
            else:
                res["note"] = "устройство JV_DEVICE=%s не подключено" % want
        elif len(res["devices"]) == 1:
            res["selected"] = res["devices"][0]
        elif len(res["devices"]) > 1:
            res["note"] = "подключено несколько устройств, задайте JV_DEVICE"
        else:
            res["note"] = "устройств нет"
        return res

    def selected(self):
        return self.devices()["selected"]

    def run(self, serial, args, timeout):
        return run_cmd(["adb", "-s", serial] + list(args), timeout, self.env)


ADB = Adb()


# ---------------------------------------------------------------------------------------------------------------------
# the bridge to the app
# ---------------------------------------------------------------------------------------------------------------------
def read_json_file(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            raw = f.read()
    except OSError:
        return None
    raw = raw.strip()
    if not raw:
        return None
    try:
        d = json.loads(raw)
        if isinstance(d, dict):
            return d
        if isinstance(d, str):
            return {"token": d}
        return None
    except ValueError:
        # tolerate a bare token pasted into the file
        if TOKEN_RE.match(raw):
            return {"token": raw}
        return None


def parse_port(v):
    try:
        p = int(v)
        return p if 0 < p < 65536 else None
    except (TypeError, ValueError):
        return None


class Bridge:
    def __init__(self, on_list_changed=None):
        self.lock = threading.RLock()
        self.session_lock = threading.Lock()
        self.ensure_lock = threading.Lock()
        self.env_token = (os.environ.get("JV_MCP_TOKEN") or "").strip() or None
        self.env_port = parse_port(os.environ.get("JV_MCP_PORT"))
        remember_secret(self.env_token)
        self.token = None
        self.token_src = None
        self.port = None
        self.health = None
        self.sid = None
        self.session_ready = False
        self.persisted = None
        self.pending_list_changed = False
        self.on_list_changed = on_list_changed
        self.last_launch = None

    # ---- persistence ------------------------------------------------------------------------------------------------
    def persist(self):
        with self.lock:
            tok, port, src = self.token, self.port, self.token_src
        if not tok or src == "env":
            return
        key = (tok, port)
        if self.persisted == key:
            return
        try:
            old = read_json_file(KEYS_FILE) or {}
            new = dict(old)
            new["token"] = tok
            if port:
                new["port"] = port
            if new != old or not os.path.exists(KEYS_FILE):
                os.makedirs(KEYS_DIR, mode=0o700, exist_ok=True)
                tmp = "%s.tmp%d" % (KEYS_FILE, os.getpid())
                fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    json.dump(new, f)
                os.chmod(tmp, 0o600)
                os.replace(tmp, KEYS_FILE)
                dlog("wrote %s (token …%s, port %s)" % (KEYS_FILE, tok[-4:], port))
            self.persisted = key
        except OSError as e:
            log("cannot write %s: %s" % (KEYS_FILE, e))

    # ---- low level HTTP ---------------------------------------------------------------------------------------------
    def http(self, method, path, port, body=None, headers=None, read_timeout=10.0, connect_timeout=CONNECT_TIMEOUT):
        conn = http.client.HTTPConnection(HOST, port, timeout=connect_timeout)
        try:
            try:
                conn.connect()
            except OSError as e:
                raise AppDown("connect: %s" % e)
            conn.sock.settimeout(read_timeout)
            try:
                conn.request(method, path, body=body, headers=headers or {})
                resp = conn.getresponse()
                data = resp.read()
            except socket.timeout:
                raise CallTimeout()
            except (OSError, http.client.HTTPException) as e:
                raise AppDown("io: %s" % e)
            return resp.status, {k.lower(): v for k, v in resp.getheaders()}, data
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def probe_health(self, port, strict, timeout=1.5):
        try:
            status, _h, data = self.http("GET", "/health", port, read_timeout=timeout, connect_timeout=min(timeout, CONNECT_TIMEOUT))
        except (AppDown, CallTimeout):
            return None
        if status != 200:
            return None
        try:
            d = json.loads(data.decode("utf-8", "replace"))
        except ValueError:
            return None
        if not isinstance(d, dict) or d.get("ok") is False:
            return None
        if strict and d.get("app") != PKG:
            return None
        return d

    # ---- endpoint / token resolution --------------------------------------------------------------------------------
    def find_port(self):
        """Probes the candidate ports; sets self.port / self.health and returns the port or None."""
        if self.env_port:
            h = self.probe_health(self.env_port, strict=False, timeout=CONNECT_TIMEOUT)
            with self.lock:
                self.port, self.health = (self.env_port, h) if h else (None, None)
            return self.port
        cands = []
        if self.port:
            cands.append(self.port)
        f = read_json_file(KEYS_FILE) or {}
        fp = parse_port(f.get("port"))
        if fp:
            cands.append(fp)
        # explicit ports (cached/file) get the full timeout, the scan is quick
        explicit = list(dict.fromkeys(cands))
        for p in explicit:
            h = self.probe_health(p, strict=True, timeout=CONNECT_TIMEOUT)
            if h:
                return self._set_port(p, h)
        adb_p = None
        if ADB.selected():
            ep = self.adb_endpoint()
            adb_p = parse_port((ep or {}).get("port"))
            if adb_p and adb_p not in explicit:
                h = self.probe_health(adb_p, strict=True, timeout=CONNECT_TIMEOUT)
                if h:
                    return self._set_port(adb_p, h)
        for p in candidate_ports(PKG):
            if p in explicit or p == adb_p:
                continue
            h = self.probe_health(p, strict=True, timeout=0.7)
            if h:
                return self._set_port(p, h)
        with self.lock:
            self.port, self.health = None, None
        return None

    def _set_port(self, port, health):
        with self.lock:
            changed = self.port != port
            self.port, self.health = port, health
        if changed:
            dlog("app endpoint %s:%d" % (HOST, port))
            self.persist()
        return port

    def forget_port(self):
        with self.lock:
            self.port, self.health = None, None

    def adb_cat(self, relpath):
        dev = ADB.selected()
        if not dev:
            return None
        rc, out, _e = ADB.run(dev, ["shell", "cat", "/sdcard/Android/data/%s/files/mcp/%s" % (PKG, relpath)], 8.0)
        return text(out) if rc == 0 else None

    def adb_endpoint(self):
        raw = self.adb_cat("endpoint.json")
        if not raw:
            return None
        try:
            d = json.loads(raw)
            return d if isinstance(d, dict) else None
        except ValueError:
            return None

    def resolve_token(self, exclude=None):
        """env -> keys file -> adb. A token equal to `exclude` (the one just rejected) is skipped."""
        def ok(t):
            return bool(t) and TOKEN_RE.match(t) is not None and t != exclude

        if ok(self.env_token):
            return self._learn(self.env_token, "env")
        f = read_json_file(KEYS_FILE) or {}
        t = str(f.get("token") or "").strip()
        if ok(t):
            return self._learn(t, "file")
        t = self.adb_cat("token")
        t = (t or "").strip()
        if ok(t):
            self._learn(t, "adb")
            self.persist()
            return t
        return None

    def _learn(self, tok, src):
        remember_secret(tok)
        with self.lock:
            self.token, self.token_src = tok, src
        return tok

    def refresh_token(self, failed):
        new = self.resolve_token(exclude=failed)
        if new:
            dlog("token refreshed from %s" % self.token_src)
            self.drop_session(None)
            return True
        return False

    # ---- sessions ---------------------------------------------------------------------------------------------------
    def drop_session(self, sid):
        with self.lock:
            if sid is None or self.sid == sid:
                self.sid, self.session_ready = None, False

    def _headers(self, token, sid=None, extra=None):
        h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
             "Authorization": "Bearer " + token}
        if sid:
            h["Mcp-Session-Id"] = sid
        if extra:
            h.update(extra)
        return h

    def ensure_session(self, port, token):
        with self.session_lock:
            with self.lock:
                if self.session_ready:
                    return self.sid
            body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
                "protocolVersion": DEFAULT_PROTOCOL, "capabilities": {},
                "clientInfo": {"name": SHIM_NAME, "version": SHIM_VERSION}}}).encode("utf-8")
            status, hdrs, data = self.http("POST", "/mcp", port, body, self._headers(token), read_timeout=15.0)
            if status == 401:
                raise Unauthorized(token)
            if status != 200:
                raise AppHttpError("initialize: HTTP %d %s" % (status, mask(text(data)[:200])))
            sid = hdrs.get("mcp-session-id")
            try:
                self.http("POST", "/mcp", port, json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}).encode("utf-8"),
                          self._headers(token, sid), read_timeout=10.0)
            except (AppDown, CallTimeout):
                pass
            with self.lock:
                self.sid, self.session_ready = sid, True
            dlog("app session initialized")
            return sid

    # ---- one request to the app -------------------------------------------------------------------------------------
    @staticmethod
    def parse_body(hdrs, data):
        t = data.decode("utf-8", "replace")
        if "text/event-stream" in hdrs.get("content-type", ""):
            last = None
            for line in t.splitlines():
                if line.startswith("data:"):
                    last = line[5:].strip()
            t = last or ""
        return json.loads(t)

    def call_once(self, method, params, read_timeout, notify=True):
        port = self.port or self.find_port()
        if not port:
            raise AppDown("no endpoint")
        token = self.token or self.resolve_token()
        if not token:
            raise NoToken()
        sid = self.ensure_session(port, token)
        req = {"jsonrpc": "2.0", "id": 2, "method": method}
        if params is not None:
            req["params"] = params
        status, hdrs, data = self.http("POST", "/mcp", port, json.dumps(req).encode("utf-8"), self._headers(token, sid),
                                       read_timeout=read_timeout)
        if status == 401:
            raise Unauthorized(token)
        try:
            msg = self.parse_body(hdrs, data) if data else None
        except ValueError:
            msg = None
        if status == 404 or (status == 400 and b"session" in data.lower() and not isinstance(msg, dict)):
            raise SessionGone(sid)
        if not isinstance(msg, dict) or "jsonrpc" not in msg:
            raise AppHttpError("HTTP %d %s" % (status, mask(text(data)[:200])))
        self.persist()
        if notify and self.pending_list_changed:
            self.pending_list_changed = False
            if self.on_list_changed:
                self.on_list_changed()
        return msg

    def forward(self, method, params, read_timeout, allow_ensure=True):
        """Sends one JSON-RPC request to the app with token refresh / re-initialize / app launch. Returns the app's
        JSON-RPC response dict. Raises NoToken, AppNotRunning, AuthFailed, CallTimeout, AppHttpError."""
        t0 = time.monotonic()
        refreshed = reinit = rediscovered = ensured = False
        notify = method != "tools/list"
        for _ in range(10):
            try:
                return self.call_once(method, params, read_timeout, notify)
            except Unauthorized as e:
                if refreshed:
                    raise AuthFailed()
                refreshed = True
                if not self.refresh_token(e.token):
                    raise AuthFailed()
            except SessionGone as e:
                if reinit:
                    raise AppHttpError("приложение не принимает сессию MCP")
                reinit = True
                self.drop_session(e.sid)
            except AppDown as e:
                dlog("app down: %s" % e)
                self.drop_session(None)
                if not rediscovered:
                    rediscovered = True
                    self.forget_port()
                    if self.find_port():
                        continue
                if allow_ensure and not ensured:
                    ensured = True
                    left = CALL_BUDGET * SCALE - (time.monotonic() - t0)
                    if self.ensure_app(max(left, 1.0)):
                        continue
                raise AppNotRunning()
        raise AppNotRunning()

    # ---- starting the app -------------------------------------------------------------------------------------------
    def am_cmd(self):
        override = os.environ.get("JV_AM_BIN")
        if override:
            return shlex.split(override)
        if NO_ADB:
            return None
        p = shutil.which("am")
        return [p] if p else None

    def wait_health(self, seconds):
        end = time.monotonic() + max(seconds, 0.0)
        while True:
            if self.find_port():
                return True
            if time.monotonic() >= end:
                return False
            time.sleep(min(0.25, max(end - time.monotonic(), 0.0)))

    def ensure_app(self, budget):
        """Returns a short description of what worked, or None. Serialized: concurrent callers share one attempt."""
        with self.ensure_lock:
            if self.find_port():
                return "уже запущено"
            end = time.monotonic() + budget
            am = self.am_cmd()
            if am:
                log("ensure_app: am broadcast McpStartReceiver")
                rc, _o, err = run_cmd(am + ["broadcast", "-n", "%s/app.jarvis.mcp.McpStartReceiver" % PKG,
                                            "-a", "app.jarvis.mcp.START"], min(8.0, max(end - time.monotonic(), 1.0)))
                dlog("am broadcast rc=%s %s" % (rc, text(err)[:120]))
                if self.wait_health(min(HEALTH_WAIT * SCALE, max(end - time.monotonic(), 0.0))):
                    self.last_launch = "Termux am broadcast"
                    return self.last_launch
            dev = ADB.selected()
            if dev and time.monotonic() < end:
                log("ensure_app: adb am start McpLaunchActivity")
                rc, _o, err = ADB.run(dev, ["shell", "am", "start", "-n", "%s/app.jarvis.mcp.McpLaunchActivity" % PKG],
                                      min(10.0, max(end - time.monotonic(), 1.0)))
                dlog("adb am start rc=%s %s" % (rc, text(err)[:120]))
                if self.wait_health(min(HEALTH_WAIT * SCALE, max(end - time.monotonic(), 0.0))):
                    self.last_launch = "adb am start McpLaunchActivity"
                    return self.last_launch
            return None


# ---------------------------------------------------------------------------------------------------------------------
# host-side tools
# ---------------------------------------------------------------------------------------------------------------------
def tool_text(s, is_error=False):
    return {"content": [{"type": "text", "text": s}], "isError": bool(is_error)}


def tool_json(obj, is_error=False):
    return tool_text(json.dumps(obj, ensure_ascii=False, indent=2), is_error)


EMPTY_SCHEMA = {"type": "object", "properties": {}, "additionalProperties": False}

HOST_TOOLS = [
    {"name": "device_status",
     "description": "Состояние моста (хост-инструмент прокладки): устройства adb, отвечает ли приложение на /health, адрес "
                    "эндпоинта, пакет, известен ли токен (сам токен не показывается). Работает, даже если приложение закрыто.",
     "inputSchema": EMPTY_SCHEMA},
    {"name": "app_launch",
     "description": "Запустить приложение Jarvis без окна и дождаться ответа /health: сначала Termux `am broadcast` "
                    "(McpStartReceiver), затем adb `am start` (McpLaunchActivity, если подключено устройство). "
                    "После установки или принудительной остановки приложение нужно один раз открыть вручную.",
     "inputSchema": EMPTY_SCHEMA},
    {"name": "app_stop",
     "description": "Остановить приложение: adb `am force-stop`, если устройство подключено, иначе вызов app_quit через мост.",
     "inputSchema": EMPTY_SCHEMA},
    {"name": "apk_install",
     "description": "Установить APK на подключённое устройство (adb install -r). Без устройства adb откажет с понятным сообщением.",
     "inputSchema": {"type": "object", "properties": {
         "path": {"type": "string", "description": "Путь к .apk на этой машине (Termux)"}},
         "required": ["path"], "additionalProperties": False}},
    {"name": "device_logcat",
     "description": "Последние строки logcat: через adb, если устройство подключено, иначе локальный logcat Termux. "
                    "По умолчанию только строки приложения.",
     "inputSchema": {"type": "object", "properties": {
         "n": {"type": "integer", "minimum": 1, "maximum": 5000, "default": 200, "description": "Сколько строк"},
         "level": {"type": "string", "enum": ["V", "D", "I", "W", "E", "F"], "default": "I", "description": "Минимальный уровень"},
         "all": {"type": "boolean", "default": False, "description": "Не фильтровать по приложению"}},
         "additionalProperties": False}},
    {"name": "device_screenshot",
     "description": "Снимок экрана устройства через adb screencap (PNG). Делается только если устройство подключено и "
                    "в фокусе окно приложения Jarvis, чужие окна не снимаются.",
     "inputSchema": EMPTY_SCHEMA},
]
HOST_TOOL_NAMES = [t["name"] for t in HOST_TOOLS]


class HostTools:
    def __init__(self, bridge):
        self.b = bridge

    def call(self, name, args):
        fn = getattr(self, "t_" + name)
        try:
            return fn(args)
        except Exception as e:  # never let a host tool kill the shim
            return tool_text("%s: %s: %s" % (name, type(e).__name__, mask(e)), True)

    def t_device_status(self, args):
        b = self.b
        devs = ADB.devices(fresh=True)
        port = b.find_port()
        if not b.token:
            b.resolve_token()
        info = {
            "package": PKG, "keysFile": KEYS_FILE,
            "adb": {"available": ADB.available(), "serverPort": ADB.port, "serverUp": devs.get("server", False),
                    "devices": devs["devices"], "otherStates": [list(x) for x in devs["other"]],
                    "selected": devs["selected"], "note": devs["note"]},
            "app": {"healthy": bool(port), "port": port,
                    "endpoint": ("http://%s:%d/mcp" % (HOST, port)) if port else None,
                    "health": b.health if port else None},
            "token": {"known": bool(b.token), "source": b.token_src,
                      "tail": ("…" + b.token[-4:]) if b.token else None},
            "toolsCache": os.path.exists(TOOLS_CACHE),
            "termuxAm": bool(b.am_cmd()),
            "lastLaunch": b.last_launch,
        }
        return tool_json(info)

    def t_app_launch(self, args):
        b = self.b
        how = b.ensure_app(CALL_BUDGET * SCALE)
        if not how:
            return tool_text(APP_DOWN_MSG, True)
        return tool_json({"ok": True, "method": how, "port": b.port, "endpoint": "http://%s:%d/mcp" % (HOST, b.port)})

    def t_app_stop(self, args):
        b = self.b
        dev = ADB.selected()
        if dev:
            rc, out, err = ADB.run(dev, ["shell", "am", "force-stop", PKG], 10.0)
            b.drop_session(None)
            b.forget_port()
            if rc == 0:
                return tool_json({"ok": True, "method": "adb am force-stop", "package": PKG})
            return tool_text("adb am force-stop не удался: %s" % mask(text(err) or text(out) or "rc=%s" % rc), True)
        if not b.find_port():
            return tool_json({"ok": True, "method": "none", "note": "приложение и так не запущено"})
        try:
            resp = b.forward("tools/call", {"name": "app_quit", "arguments": {}}, 15.0, allow_ensure=False)
        except AppNotRunning:
            resp = None  # the app went away while answering: that is what we wanted
        except (NoToken, AuthFailed) as e:
            return tool_text(NO_TOKEN_MSG if isinstance(e, NoToken) else AUTH_FAIL_MSG, True)
        except (CallTimeout, AppHttpError) as e:
            return tool_text("app_quit не выполнен: %s" % mask(e), True)
        b.drop_session(None)
        b.forget_port()
        if resp and "error" in resp:
            return tool_text("app_quit: %s" % mask(json.dumps(resp["error"], ensure_ascii=False)), True)
        return tool_json({"ok": True, "method": "app_quit через мост"})

    def t_apk_install(self, args):
        path = args.get("path")
        if not isinstance(path, str) or not path:
            return tool_text("Нужен аргумент path (путь к .apk).", True)
        path = os.path.expanduser(path)
        if not os.path.isfile(path):
            return tool_text("Файл не найден: %s" % path, True)
        dev = ADB.selected()
        if not dev:
            d = ADB.devices(fresh=True)
            return tool_text("Нет подключённого устройства adb (%s). Установка APK без adb невозможна: откройте файл на "
                             "телефоне вручную." % (d["note"] or "устройств нет"), True)
        rc, out, err = ADB.run(dev, ["install", "-r", path], 300.0)
        msg = text(out) + ("\n" + text(err) if text(err) else "")
        if rc == 0 and "Success" in msg:
            return tool_text("Установлено: %s\n%s" % (path, msg.strip()))
        return tool_text("adb install не удался (rc=%s): %s" % (rc, mask(msg.strip())), True)

    def t_device_logcat(self, args):
        try:
            n = int(args.get("n", 200))
        except (TypeError, ValueError):
            n = 200
        n = max(1, min(n, 5000))
        level = str(args.get("level") or "I").upper()[:1]
        if level not in "VDIWEF":
            return tool_text("level должен быть одним из V D I W E F", True)
        everything = bool(args.get("all"))
        problems = []
        dev = ADB.selected()
        if dev:
            cmd = ["logcat", "-d", "-t", str(n), "-v", "threadtime"]
            if not everything:
                rc, out, _e = ADB.run(dev, ["shell", "pidof", PKG], 6.0)
                pid = text(out).split()[0] if rc == 0 and text(out) else ""
                if pid.isdigit():
                    cmd.append("--pid=" + pid)
            cmd.append("*:" + level)
            rc, out, err = ADB.run(dev, cmd, 20.0)
            if rc == 0 and out:
                return tool_text(text(out))
            problems.append("adb logcat: rc=%s %s" % (rc, text(err)[:150]))
        lc = shutil.which("logcat")
        if lc:
            rc, out, err = run_cmd([lc, "-d", "-t", str(n), "-v", "threadtime", "*:" + level], 10.0)
            if rc == 0 and out:
                lines = text(out).splitlines()
                if not everything:
                    lines = [x for x in lines if PKG in x or "jarvis" in x.lower()]
                if lines:
                    return tool_text("\n".join(lines))
                problems.append("локальный logcat не содержит строк приложения (Termux видит только свои логи)")
            else:
                problems.append("локальный logcat: rc=%s %s" % (rc, text(err)[:150]))
        else:
            problems.append("команды logcat нет в PATH")
        return tool_text("Не удалось получить logcat: " + "; ".join(problems), True)

    def t_device_screenshot(self, args):
        dev = ADB.selected()
        if not dev:
            d = ADB.devices(fresh=True)
            return tool_text("Скриншот невозможен: нет подключённого устройства adb (%s). Используйте инструмент ui_screenshot "
                             "приложения." % (d["note"] or "устройств нет"), True)
        rc, out, err = ADB.run(dev, ["shell", "dumpsys", "window"], 15.0)
        focus = ""
        for line in text(out).splitlines():
            if "mCurrentFocus" in line:
                focus = line.strip()
                break
        if not re.search(r"[ {]%s/" % re.escape(PKG), focus):
            return tool_text("Скриншот не сделан: в фокусе не окно %s (%s). Откройте приложение на телефоне." %
                             (PKG, focus or "фокус не определён"), True)
        rc, png, err = ADB.run(dev, ["exec-out", "screencap", "-p"], 25.0)
        if rc != 0 or not png.startswith(PNG_MAGIC):
            return tool_text("adb screencap не вернул PNG (rc=%s): %s" % (rc, text(err)[:200]), True)
        return {"content": [{"type": "image", "data": base64.b64encode(png).decode("ascii"), "mimeType": "image/png"}],
                "isError": False}


# ---------------------------------------------------------------------------------------------------------------------
# the stdio server
# ---------------------------------------------------------------------------------------------------------------------
class Shim:
    def __init__(self):
        self.out = sys.stdout.buffer
        self.out_lock = threading.Lock()
        self.bridge = Bridge(on_list_changed=self.notify_list_changed)
        self.host = HostTools(self.bridge)
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=32, thread_name_prefix="shim")
        self.pending = set()
        self.pending_lock = threading.Lock()

    # ---- output -----------------------------------------------------------------------------------------------------
    def write(self, obj):
        line = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n"
        with self.out_lock:
            try:
                self.out.write(line)
                self.out.flush()
            except (BrokenPipeError, OSError, ValueError):
                pass

    def notify_list_changed(self):
        self.write({"jsonrpc": "2.0", "method": "notifications/tools/list_changed"})

    @staticmethod
    def ok(id_, result):
        return {"jsonrpc": "2.0", "id": id_, "result": result}

    @staticmethod
    def err(id_, code, message, data=None):
        e = {"code": code, "message": message}
        if data is not None:
            e["data"] = data
        return {"jsonrpc": "2.0", "id": id_, "error": e}

    # ---- tools/list -------------------------------------------------------------------------------------------------
    def read_cache(self):
        try:
            with open(TOOLS_CACHE, "r", encoding="utf-8") as f:
                d = json.load(f)
            return d if isinstance(d, list) else []
        except (OSError, ValueError):
            return []

    def write_cache(self, tools):
        try:
            if self.read_cache() == tools and os.path.exists(TOOLS_CACHE):
                return
            os.makedirs(KEYS_DIR, mode=0o700, exist_ok=True)
            tmp = "%s.tmp%d" % (TOOLS_CACHE, os.getpid())
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(tools, f, ensure_ascii=False)
            os.replace(tmp, TOOLS_CACHE)
        except OSError as e:
            log("cannot write tools cache: %s" % e)

    def tools_list(self):
        live = False
        app_tools = []
        try:
            resp = self.bridge.forward("tools/list", {}, OTHER_CALL_TIMEOUT, allow_ensure=False)
            lst = (resp.get("result") or {}).get("tools")
            if isinstance(lst, list):
                app_tools, live = lst, True
                self.write_cache(lst)
        except (AppNotRunning, NoToken, AuthFailed, CallTimeout, AppHttpError) as e:
            dlog("tools/list from app failed: %s" % type(e).__name__)
        if not live:
            app_tools = self.read_cache()
            self.bridge.pending_list_changed = True
        else:
            self.bridge.pending_list_changed = False
        names = set(HOST_TOOL_NAMES)
        merged = list(HOST_TOOLS) + [t for t in app_tools if isinstance(t, dict) and t.get("name") not in names]
        return {"tools": merged}

    # ---- request handling -------------------------------------------------------------------------------------------
    def error_result(self, e):
        if isinstance(e, NoToken):
            return tool_text(NO_TOKEN_MSG, True)
        if isinstance(e, AppNotRunning):
            return tool_text(APP_DOWN_MSG, True)
        if isinstance(e, AuthFailed):
            return tool_text(AUTH_FAIL_MSG, True)
        if isinstance(e, CallTimeout):
            return tool_text("Приложение не ответило вовремя (таймаут чтения). Вызов мог выполняться дальше; "
                             "проверьте журнал: journal_tail.", True)
        return tool_text("Ошибка обращения к приложению: %s" % mask(e), True)

    def tools_call(self, params):
        name = params.get("name")
        args = params.get("arguments")
        if args is not None and not isinstance(args, dict):
            raise RpcError(-32602, "arguments must be an object")
        if name in HOST_TOOL_NAMES:
            return self.host.call(name, args or {})
        timeout = DEFAULT_CALL_TIMEOUT
        tm = (args or {}).get("timeoutMs")
        if isinstance(tm, (int, float)) and not isinstance(tm, bool) and tm > 0:
            timeout = float(tm) / 1000.0 + 10.0
        try:
            resp = self.bridge.forward("tools/call", params, timeout)
        except (NoToken, AppNotRunning, AuthFailed, CallTimeout, AppHttpError) as e:
            return self.error_result(e)
        if "error" in resp:
            e = resp["error"] if isinstance(resp["error"], dict) else {}
            raise RpcError(e.get("code", -32603), mask(e.get("message", "app error")), e.get("data"))
        return resp.get("result") if isinstance(resp.get("result"), dict) else {}

    def forward_plain(self, method, params, empty_on_down):
        try:
            resp = self.bridge.forward(method, params, OTHER_CALL_TIMEOUT, allow_ensure=False)
        except (NoToken, AppNotRunning, AuthFailed, CallTimeout, AppHttpError) as e:
            if empty_on_down is not None:
                return empty_on_down
            msg = (NO_TOKEN_MSG if isinstance(e, NoToken) else APP_DOWN_MSG if isinstance(e, AppNotRunning)
                   else AUTH_FAIL_MSG if isinstance(e, AuthFailed) else "Ошибка обращения к приложению: %s" % mask(e))
            raise RpcError(-32000, msg)
        if "error" in resp:
            e = resp["error"] if isinstance(resp["error"], dict) else {}
            raise RpcError(e.get("code", -32603), mask(e.get("message", "app error")), e.get("data"))
        return resp.get("result") if isinstance(resp.get("result"), dict) else {}

    def initialize_result(self, params):
        asked = params.get("protocolVersion") if isinstance(params, dict) else None
        ver = asked if asked in SUPPORTED_PROTOCOLS else DEFAULT_PROTOCOL
        return {"protocolVersion": ver,
                "capabilities": {"tools": {"listChanged": True}, "resources": {"listChanged": False, "subscribe": False}},
                "serverInfo": {"name": SHIM_NAME, "version": SHIM_VERSION},
                "instructions": "Мост к приложению Jarvis. Хост-инструменты: %s. Остальные инструменты выполняет приложение."
                                % ", ".join(HOST_TOOL_NAMES)}

    def is_fast(self, msg):
        return isinstance(msg, dict) and msg.get("method") in ("initialize", "ping") or (
            isinstance(msg, dict) and isinstance(msg.get("method"), str) and msg["method"].startswith("notifications/"))

    def handle_message(self, msg):
        """-> response dict or None (notification / client response)."""
        if not isinstance(msg, dict):
            return self.err(None, -32600, "Invalid Request")
        has_id = "id" in msg
        id_ = msg.get("id")
        method = msg.get("method")
        if not isinstance(method, str):
            if "result" in msg or "error" in msg:
                return None  # a response to a request we never send
            return self.err(id_ if has_id else None, -32600, "Invalid Request")
        params = msg.get("params")
        if method.startswith("notifications/"):
            return None
        try:
            if params is None:
                params = {}
            if not isinstance(params, dict):
                raise RpcError(-32602, "params must be an object")
            if method == "initialize":
                result = self.initialize_result(params)
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = self.tools_list()
            elif method == "tools/call":
                result = self.tools_call(params)
            elif method == "resources/list":
                result = self.forward_plain(method, params, {"resources": []})
            elif method == "resources/templates/list":
                result = self.forward_plain(method, params, {"resourceTemplates": []})
            elif method == "resources/read":
                result = self.forward_plain(method, params, None)
            elif method == "prompts/list":
                result = {"prompts": []}
            else:
                raise RpcError(-32601, "Method not found: %s" % method)
        except RpcError as e:
            return self.err(id_, e.code, e.message, e.data) if has_id else None
        except Exception as e:
            log("internal error in %s: %s: %s" % (method, type(e).__name__, e))
            return self.err(id_, -32603, "Internal error: %s" % type(e).__name__) if has_id else None
        return self.ok(id_, result) if has_id else None

    def handle_line(self, line):
        try:
            data = json.loads(line)
        except ValueError:
            self.write(self.err(None, -32700, "Parse error"))
            return
        if isinstance(data, list):
            if not data:
                self.write(self.err(None, -32600, "Invalid Request: empty batch"))
                return
            outs = [r for r in (self.handle_message(m) for m in data) if r is not None]
            if outs:
                self.write(outs)
            return
        r = self.handle_message(data)
        if r is not None:
            self.write(r)

    def _done(self, fut):
        with self.pending_lock:
            self.pending.discard(fut)

    def submit(self, line):
        fut = self.pool.submit(self._safe, line)
        with self.pending_lock:
            self.pending.add(fut)
        fut.add_done_callback(self._done)

    def _safe(self, line):
        try:
            self.handle_line(line)
        except Exception as e:
            log("unhandled error: %s: %s" % (type(e).__name__, e))

    def run(self):
        sys.stdout = sys.stderr  # nothing but JSON-RPC may reach the real stdout
        inp = sys.stdin.buffer
        log("started: pkg=%s keys=%s adb=%s" % (PKG, KEYS_FILE, "off" if not ADB.available() else "port %d" % ADB.port))
        while True:
            try:
                raw = inp.readline()
            except (OSError, ValueError):
                break
            if not raw:
                break  # EOF: clean exit
            line = raw.decode("utf-8", "replace").strip()
            if not line:
                continue
            fast = False
            try:
                first = line.lstrip()[:1]
                if first == "{":
                    msg = json.loads(line)
                    fast = self.is_fast(msg)
            except ValueError:
                fast = True  # parse error answers instantly
            if fast:
                self._safe(line)  # initialize / ping / notifications never queue behind slow calls
            else:
                self.submit(line)
        # let in-flight requests finish (bounded), then leave
        with self.pending_lock:
            futs = list(self.pending)
        if futs:
            concurrent.futures.wait(futs, timeout=30.0 * max(SCALE, 0.1))
        self.pool.shutdown(wait=False, cancel_futures=True)
        sys.stderr.flush()
        os._exit(0)


def main():
    if len(sys.argv) > 1 and sys.argv[1] in ("-h", "--help"):
        sys.stderr.write(__doc__ or "")
        return 0
    Shim().run()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
