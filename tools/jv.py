#!/usr/bin/env python3
"""jv — tiny command line client for the Jarvis MCP bridge (no opencode restart needed, usable by farm agents too).

  python3 tools/jv.py health                      # which Jarvis variants answer on loopback
  python3 tools/jv.py tools [prefix]              # list tools (name + first line of description)
  python3 tools/jv.py call <tool> ['{"a":1}']     # call a tool, print the JSON result (images are saved to build/device/)
  python3 tools/jv.py <tool> ['{"a":1}']          # shorthand for call
Options (before the command): --pkg app.jarvis.next   (default app.jarvis; env JV_PKG)   --port N   --token T   --timeout S
Token: --token / env JV_MCP_TOKEN / ~/.jarvis-keys/mcp-<pkg>.json (written by tools/make-provisioned.py or the shim).
"""
import base64, json, os, sys, time, urllib.request, urllib.error

args = sys.argv[1:]
opts = {"pkg": os.environ.get("JV_PKG", "app.jarvis"), "port": os.environ.get("JV_MCP_PORT", ""), "token": os.environ.get("JV_MCP_TOKEN", ""), "timeout": "120"}
while args and args[0].startswith("--"):
    k = args.pop(0)[2:]
    opts[k] = args.pop(0)
if not args:
    print(__doc__); sys.exit(2)
KEYS = os.path.expanduser(os.environ.get("JV_KEYS_DIR", "~/.jarvis-keys"))
kf = {}
try: kf = json.load(open(os.path.join(KEYS, "mcp-%s.json" % opts["pkg"])))
except (OSError, ValueError): pass
token = opts["token"] or kf.get("token", "")


def pref_port(pkg):
    if pkg == "app.jarvis": return 8767
    h = 0
    for ch in pkg: h = (h * 31 + ord(ch)) & 0xFFFFFFFF
    return 8768 + (h & 0x7FFFFFFF) % 500


def get(port, path="/health", timeout=1.5):
    with urllib.request.urlopen("http://127.0.0.1:%d%s" % (port, path), timeout=timeout) as r: return json.loads(r.read())


def find_port(pkg):
    cands = [int(x) for x in (opts["port"], kf.get("port")) if x]
    p0 = pref_port(pkg); cands += [p0 + i for i in range(0, 20)] + list(range(8767, 8790))
    for p in dict.fromkeys(cands):
        try:
            if get(p).get("app") == pkg: return p
        except Exception: pass
    return None


cmd = args.pop(0)
if cmd == "health":
    found = []
    for p in list(range(8767, 8790)) + [pref_port(opts["pkg"]) + i for i in range(20)]:
        try: h = get(p); found.append((p, h))
        except Exception: pass
    for p, h in dict(found).items(): print(p, json.dumps(h, ensure_ascii=False))
    if not found: print("no Jarvis MCP server answers on 127.0.0.1 (open the app once; MCP must be enabled)")
    sys.exit(0 if found else 1)
port = find_port(opts["pkg"])
if not port: print("Jarvis (%s) does not answer on loopback: open the app on the phone." % opts["pkg"], file=sys.stderr); sys.exit(3)
if not token: print("no token: put {\"token\":\"...\"} into %s/mcp-%s.json" % (KEYS, opts["pkg"]), file=sys.stderr); sys.exit(4)
sid = [None]


def rpc(method, params=None, rid=1):
    body = json.dumps({"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}}).encode()
    h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", "Authorization": "Bearer " + token}
    if sid[0]: h["Mcp-Session-Id"] = sid[0]
    req = urllib.request.Request("http://127.0.0.1:%d/mcp" % port, data=body, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=float(opts["timeout"])) as r:
            if r.headers.get("Mcp-Session-Id"): sid[0] = r.headers["Mcp-Session-Id"]
            raw = r.read().decode("utf8", "replace")
    except urllib.error.HTTPError as e:
        print("HTTP %d: %s" % (e.code, e.read()[:300].decode("utf8", "replace")), file=sys.stderr); sys.exit(5)
    if raw.lstrip().startswith("event:") or raw.lstrip().startswith("data:"):
        for ln in raw.splitlines():
            if ln.startswith("data:"): raw = ln[5:].strip(); break
    return json.loads(raw)


rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "jv-cli", "version": "1"}})
if cmd == "tools":
    pre = args[0] if args else ""
    for t in rpc("tools/list", rid=2)["result"]["tools"]:
        if t["name"].startswith(pre): print("%-28s %s" % (t["name"], (t.get("description") or "").split("\n")[0][:110]))
    sys.exit(0)
if cmd == "call": cmd = args.pop(0)
a = json.loads(args[0]) if args else {}
t0 = time.time()
res = rpc("tools/call", {"name": cmd, "arguments": a}, rid=3)
if "error" in res: print(json.dumps(res["error"], ensure_ascii=False)); sys.exit(6)
r = res["result"]; out = []
for c in r.get("content", []):
    if c.get("type") == "image":
        os.makedirs("build/device", exist_ok=True)
        ext = {"image/png": "png", "image/jpeg": "jpg", "image/webp": "webp"}.get(c.get("mimeType"), "bin")
        path = opts.get("save") or "build/device/jv-%d.%s" % (int(time.time() * 1000), ext)
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        open(path, "wb").write(base64.b64decode(c["data"])); out.append("[image saved: %s]" % path)
    else: out.append(c.get("text", ""))
print("\n".join(out))
print("(%.2f s%s)" % (time.time() - t0, ", isError" if r.get("isError") else ""), file=sys.stderr)
sys.exit(1 if r.get("isError") else 0)
