#!/usr/bin/env python3
"""Fake Jarvis MCP app server for testing tools/jarvis-mcp-shim.py without a phone (stdlib only).

Imitates McpServer: GET /health (no token), POST /mcp (Bearer token, Host check, Mcp-Session-Id issued on initialize),
tools echo + journal_tail (+ unlisted app_quit), resources/list, resources/read, batches.

Usage: fake_server.py --token-file F [--token T] [--port N] [--port-file F] [--app app.jarvis]
The token is re-read from --token-file on every request, so a test can rotate it.

Test-only admin endpoints (GET, loopback):
  /__rotate[?token=X]  change the token (random 64 hex when omitted), answers {"token": new}
  /__restart           forget all sessions (like an app restart); later requests with an old session get 404
  /__stats             counters: initialize, calls, unauthorized, quit, sessions
"""
import argparse
import json
import os
import secrets
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

TOOLS = [
    {"name": "echo", "description": "Echo the text back (optional delayMs to simulate a slow tool).",
     "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}, "delayMs": {"type": "integer"},
                                                       "timeoutMs": {"type": "integer"}}, "required": ["text"]}},
    {"name": "journal_tail", "description": "Last journal lines.",
     "inputSchema": {"type": "object", "properties": {"n": {"type": "integer"}}}},
]


class State:
    def __init__(self, args):
        self.args = args
        self.lock = threading.Lock()
        self.sessions = set()
        self.stats = {"initialize": 0, "calls": 0, "unauthorized": 0, "quit": 0}

    def token(self):
        try:
            with open(self.args.token_file, "r", encoding="utf-8") as f:
                return f.read().strip()
        except OSError:
            return ""

    def set_token(self, t):
        tmp = self.args.token_file + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            f.write(t)
        os.replace(tmp, self.args.token_file)


def make_handler(state):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "FakeJarvisMcp/1"

        def log_message(self, fmt, *a):
            pass

        def _send(self, status, obj=None, headers=None, raw=None):
            body = raw if raw is not None else (b"" if obj is None else json.dumps(obj).encode("utf-8"))
            self.send_response(status)
            if body:
                self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if body:
                self.wfile.write(body)

        def _host_ok(self):
            h = (self.headers.get("Host") or "").lower()
            port = self.server.server_address[1]
            return h in ("127.0.0.1:%d" % port, "localhost:%d" % port, "[::1]:%d" % port)

        def do_GET(self):
            if not self._host_ok():
                return self._send(403, {"error": "forbidden host"})
            u = urlparse(self.path)
            if u.path == "/health":
                return self._send(200, {"ok": True, "app": state.args.app, "version": "fake-1", "flavor": "debug",
                                        "pid": os.getpid(), "uptimeMs": int((time.time() - START) * 1000)})
            if u.path == "/__rotate":
                q = parse_qs(u.query)
                new = (q.get("token") or [secrets.token_hex(32)])[0]
                state.set_token(new)
                return self._send(200, {"token": new})
            if u.path == "/__restart":
                with state.lock:
                    state.sessions.clear()
                return self._send(200, {"ok": True})
            if u.path == "/__stats":
                with state.lock:
                    return self._send(200, dict(state.stats, sessions=len(state.sessions)))
            if u.path == "/mcp":
                return self._send(405, {"error": "only POST is supported"}, {"Allow": "POST, DELETE"})
            return self._send(404, {"error": "not found"})

        def do_DELETE(self):
            self._send(200)

        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(n) if n > 0 else b""
            if not self._host_ok():
                return self._send(403, {"error": "forbidden host"})
            if urlparse(self.path).path != "/mcp":
                return self._send(404, {"error": "not found"})
            expected = state.token()
            a = self.headers.get("Authorization") or ""
            if not expected or a != "Bearer " + expected:
                with state.lock:
                    state.stats["unauthorized"] += 1
                return self._send(401, {"error": "unauthorized"}, {"WWW-Authenticate": 'Bearer realm="jarvis-mcp"'})
            try:
                msg = json.loads(body.decode("utf-8"))
            except ValueError:
                return self._send(400, {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}})
            sid = self.headers.get("Mcp-Session-Id")
            batch = isinstance(msg, list)
            items = msg if batch else [msg]
            out, new_sid = [], None
            for m in items:
                is_init = isinstance(m, dict) and m.get("method") == "initialize"
                if sid and not is_init:
                    with state.lock:
                        known = sid in state.sessions
                    if not known:
                        return self._send(404, {"error": "session not found"})
                r, s = self.dispatch(m)
                if s:
                    new_sid = s
                if r is not None:
                    out.append(r)
            hdrs = {"Mcp-Session-Id": new_sid} if new_sid else None
            if not out:
                return self._send(202, None, hdrs)
            return self._send(200, out if batch else out[0], hdrs)

        # ---- JSON-RPC -------------------------------------------------------------------------------------------
        def dispatch(self, m):
            if not isinstance(m, dict) or not isinstance(m.get("method"), str):
                return {"jsonrpc": "2.0", "id": None, "error": {"code": -32600, "message": "Invalid Request"}}, None
            method, id_, notif = m["method"], m.get("id"), "id" not in m
            params = m.get("params") if isinstance(m.get("params"), dict) else {}
            if method.startswith("notifications/"):
                return None, None
            sid = None
            if method == "initialize":
                sid = secrets.token_hex(16)
                with state.lock:
                    state.sessions.add(sid)
                    state.stats["initialize"] += 1
                res = {"protocolVersion": params.get("protocolVersion") or "2025-06-18",
                       "capabilities": {"tools": {"listChanged": False}, "resources": {"listChanged": False, "subscribe": False}},
                       "serverInfo": {"name": "fake-jarvis", "version": "fake-1"}}
            elif method == "ping":
                res = {}
            elif method == "tools/list":
                res = {"tools": TOOLS}
            elif method == "tools/call":
                res = self.call(params)
                if res is None:
                    return ({"jsonrpc": "2.0", "id": id_, "error": {"code": -32602, "message": "Unknown tool: %s" % params.get("name")}}
                            if not notif else None), None
            elif method == "resources/list":
                res = {"resources": [{"uri": "jarvis://state", "name": "state", "description": "fake state", "mimeType": "application/json"}]}
            elif method == "resources/read":
                if params.get("uri") != "jarvis://state":
                    return {"jsonrpc": "2.0", "id": id_, "error": {"code": -32002, "message": "Resource not found"}}, None
                res = {"contents": [{"uri": "jarvis://state", "mimeType": "application/json", "text": "{\"fake\":true}"}]}
            else:
                return ({"jsonrpc": "2.0", "id": id_, "error": {"code": -32601, "message": "Method not found: " + method}}
                        if not notif else None), None
            if notif:
                return None, sid
            return {"jsonrpc": "2.0", "id": id_, "result": res}, sid

        def call(self, params):
            name = params.get("name")
            args = params.get("arguments") if isinstance(params.get("arguments"), dict) else {}
            with state.lock:
                state.stats["calls"] += 1
            if name == "echo":
                d = args.get("delayMs")
                if isinstance(d, (int, float)) and d > 0:
                    time.sleep(d / 1000.0)
                return {"content": [{"type": "text", "text": "echo: %s" % args.get("text", "")}], "isError": False}
            if name == "journal_tail":
                n = args.get("n") if isinstance(args.get("n"), int) else 3
                lines = ["I/fake line %d" % i for i in range(max(0, min(n, 50)))]
                return {"content": [{"type": "text", "text": "\n".join(lines)}], "isError": False}
            if name == "app_quit":  # callable but not listed: lets the shim's app_stop be tested
                with state.lock:
                    state.stats["quit"] += 1
                return {"content": [{"type": "text", "text": "{\"ok\":true}"}], "isError": False}
            return None

    return H


START = time.time()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=0)
    ap.add_argument("--port-file")
    ap.add_argument("--token-file", required=True)
    ap.add_argument("--token")
    ap.add_argument("--app", default="app.jarvis")
    args = ap.parse_args()
    state = State(args)
    if args.token:
        state.set_token(args.token)
    elif not state.token():
        state.set_token(secrets.token_hex(32))
    srv = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(state))
    srv.daemon_threads = True
    port = srv.server_address[1]
    if args.port_file:
        tmp = args.port_file + ".tmp"
        with open(tmp, "w") as f:
            f.write(str(port))
        os.replace(tmp, args.port_file)
    sys.stderr.write("fake server on 127.0.0.1:%d\n" % port)
    sys.stderr.flush()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
