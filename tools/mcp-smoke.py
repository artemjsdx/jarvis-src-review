#!/usr/bin/env python3
"""Smoke runner for the Jarvis MCP bridge: runs tests/mcp/*.json scenarios against the app and writes build/device/mcp-report-<time>.md.

  python3 tools/mcp-smoke.py                       # app on this phone (endpoint/token like the shim: env, ~/.jarvis-keys/mcp-<pkg>.json, port scan)
  python3 tools/mcp-smoke.py --jvm                 # against McpSmokeServer (fake runtime in a JVM, no phone needed)
  python3 tools/mcp-smoke.py --only chat,settings  # selected features (file names in tests/mcp without .json)
  python3 tools/mcp-smoke.py --list

Scenario file: one scenario object or {"scenarios":[...]}. Scenario keys: name, needs[] (tools that must exist, otherwise SKIP), jvm:false (skip in --jvm),
gate ("destructive_off"|"destructive_on"|"audio_off"|"audio_on": SKIP unless the bridge is in that state), needsLlm (SKIP without a stored key), needsUi (SKIP without a live window),
requires[] ("destructive"|"audio"|"seeded-turns"|"key:<provider>": SKIP with the reason shown in the report unless the bridge meets it),
steps[]. Step keys: call, args, save {var: path}, expect [...], expectError (code), sleepMs, repeat.
Strings in args may contain $var / ${var}; ${base} = http base of the bridge, ${rand} = random suffix.
Paths: "$.a.b[0].c" on the JSON of the first text block; "@text", "@isError", "@images" for the raw result. Checks: eq, ne, contains, ci (case-insensitive contains), lt, lte, gt, gte, exists, matches, len_gte.
Exit code 0 only when nothing failed (skips are reported, not hidden). Tokens are never printed."""
import argparse, base64, glob, http.client, json, os, random, re, subprocess, sys, time, urllib.parse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TERMUX_HOME = "/data/data/com.termux/files/home"


def keys_dir():
    d = os.environ.get("JV_KEYS_DIR")
    if d: return d
    t = os.path.join(TERMUX_HOME, ".jarvis-keys")
    return t if os.path.isdir(t) else os.path.expanduser("~/.jarvis-keys")


def java_hash(s):
    h = 0
    for ch in s: h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - (1 << 32) if h & 0x80000000 else h


def candidate_ports(pkg):
    ports = [8767] if pkg == "app.jarvis" else []
    ports.append(8768 + (java_hash(pkg) & 0x7FFFFFFF) % 500)
    return ports + [p for p in range(8767, 8781) if p not in ports]


class Mcp:
    def __init__(self, host, port, token):
        self.host, self.port, self.token = host, port, token
        self.sid = None
        self.n = 0

    def _post(self, body, timeout):
        c = http.client.HTTPConnection(self.host, self.port, timeout=timeout)
        h = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream", "Authorization": "Bearer " + self.token}
        if self.sid: h["Mcp-Session-Id"] = self.sid
        c.request("POST", "/mcp", json.dumps(body), h)
        r = c.getresponse()
        data = r.read()
        sid = r.getheader("Mcp-Session-Id")
        if sid: self.sid = sid
        c.close()
        return r.status, data.decode("utf-8", "replace")

    def rpc(self, method, params=None, timeout=140):
        self.n += 1
        st, txt = self._post({"jsonrpc": "2.0", "id": self.n, "method": method, "params": params or {}}, timeout)
        if st == 401: raise RuntimeError("HTTP 401: токен не подходит (Настройки → Мост для агента → «Скопировать токен»)")
        if st >= 300: raise RuntimeError("HTTP %d: %s" % (st, txt[:200]))
        if txt.lstrip().startswith("event:") or txt.lstrip().startswith("data:"):
            for line in txt.splitlines():
                if line.startswith("data:"): txt = line[5:].strip(); break
        msg = json.loads(txt)
        if "error" in msg: raise RuntimeError("JSON-RPC %s" % msg["error"])
        return msg["result"]

    def init(self):
        self.rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "mcp-smoke", "version": "1"}})
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"}, 10)

    def call(self, name, args, timeout=140):
        return self.rpc("tools/call", {"name": name, "arguments": args}, timeout)


def health(host, port, timeout=1.5):
    try:
        c = http.client.HTTPConnection(host, port, timeout=timeout)
        c.request("GET", "/health")
        r = c.getresponse()
        d = json.loads(r.read().decode())
        c.close()
        return d if r.status == 200 else None
    except Exception:
        return None


def resolve_device(pkg):
    port, token = os.environ.get("JV_MCP_PORT"), os.environ.get("JV_MCP_TOKEN")
    kf = os.path.join(keys_dir(), "mcp-%s.json" % pkg)
    try:
        f = json.load(open(kf))
    except Exception:
        f = {}
    if not token: token = str(f.get("token") or "").strip()
    cands = [int(port)] if port else ([int(f["port"])] if str(f.get("port", "")).isdigit() else []) + candidate_ports(pkg)
    for p in cands:
        h = health("127.0.0.1", p)
        if h and (h.get("app") == pkg):
            if not token:
                raise SystemExit("Мост найден на порту %d, но токена нет. Откройте Jarvis на телефоне (Настройки → Мост для агента → «Скопировать токен») и положите токен в %s" % (p, kf))
            return p, token
    raise SystemExit("Приложение не отвечает на портах %s. Откройте Jarvis на телефоне или запустите: am broadcast -n %s/app.jarvis.mcp.McpStartReceiver -a app.jarvis.mcp.START" % (cands[:4], pkg))


def start_jvm(extra):
    p = subprocess.Popen(["bash", os.path.join(ROOT, "tools", "mcp-jvm-server.sh")] + extra, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    end = time.time() + 180
    while time.time() < end:
        line = p.stdout.readline()
        if not line:
            raise SystemExit("JVM-сервер не стартовал: " + p.stderr.read()[-800:])
        if line.startswith("READY "):
            d = json.loads(line[6:])
            return p, d["port"], d["token"]
    raise SystemExit("JVM-сервер не ответил за 180 с")


# ---- scenario engine ------------------------------------------------------------------------------------------------
def subst(v, env):
    if isinstance(v, str):
        m = re.fullmatch(r"\$([A-Za-z_]\w*)", v)
        if m and m.group(1) in env: return env[m.group(1)]
        return re.sub(r"\$\{(\w+)\}", lambda mm: str(env.get(mm.group(1), mm.group(0))), v)
    if isinstance(v, list): return [subst(x, env) for x in v]
    if isinstance(v, dict): return {k: subst(x, env) for k, x in v.items()}
    return v


def parse_result(res):
    text, images, imgs = "", 0, []
    for c in res.get("content", []):
        if c.get("type") == "text" and not text: text = c.get("text", "")
        if c.get("type") == "image": images += 1; imgs.append((c.get("mimeType", ""), c.get("data", "")))
    try: parsed = json.loads(text)
    except Exception: parsed = None
    return {"text": text, "images": images, "imgs": imgs, "isError": bool(res.get("isError")), "json": parsed}


def path_get(r, path):
    if path == "@text": return r["text"]
    if path == "@isError": return r["isError"]
    if path == "@images": return r["images"]
    if not path.startswith("$"): raise KeyError(path)
    cur = r["json"]
    for tok in re.findall(r"\.([^.\[\]]+)|\[(-?\d+)\]", path[1:]):
        if cur is None: raise KeyError(path)
        if tok[0] != "":
            if tok[0] == "length" and isinstance(cur, (list, str)): cur = len(cur)
            elif isinstance(cur, dict) and tok[0] in cur: cur = cur[tok[0]]
            else: raise KeyError(path)
        else:
            i = int(tok[1])
            if not isinstance(cur, list) or not (-len(cur) <= i < len(cur)): raise KeyError(path)
            cur = cur[i]
    return cur


def check(r, e, env):
    path = e["path"]
    try: val, found = path_get(r, path), True
    except KeyError: val, found = None, False
    if "exists" in e: return (found == bool(e["exists"])), "exists(%s)=%s want %s" % (path, found, e["exists"])
    if not found: return False, "%s: путь не найден в ответе" % path
    for op in ("eq", "ne", "contains", "lt", "lte", "gt", "gte", "matches", "len_gte"):
        if op not in e: continue
        want = subst(e[op], env)
        if op == "eq": ok = val == want
        elif op == "ne": ok = val != want
        elif op == "contains":
            ok = (str(want).lower() in str(val).lower()) if e.get("ci") else (want in val if isinstance(val, (list, str)) else False)
        elif op == "matches": ok = re.search(want, str(val)) is not None
        elif op == "len_gte": ok = hasattr(val, "__len__") and len(val) >= want
        else:
            try: ok = {"lt": val < want, "lte": val <= want, "gt": val > want, "gte": val >= want}[op]
            except TypeError: ok = False
        shown = str(val)
        return ok, "%s %s %r (got %s)" % (path, op, want, shown[:120] + ("…" if len(shown) > 120 else ""))
    return False, "%s: нет условия проверки" % path


class Runner:
    def __init__(self, cli, base, host_default, tools, shots_dir, jvm, lines):
        self.cli, self.base, self.host_default, self.tools, self.shots_dir, self.jvm, self.lines = cli, base, host_default, tools, shots_dir, jvm, lines
        self.counts = {"pass": 0, "fail": 0, "skip": 0}
        self.cache = {}

    def state(self, name, args=None):
        k = name + json.dumps(args or {}, sort_keys=True)
        if k not in self.cache:
            try: self.cache[k] = parse_result(self.cli.call(name, args or {}, 30))["json"]
            except Exception: self.cache[k] = None
        return self.cache[k]

    def requirement_reason(self, req):
        """None when the requirement holds, otherwise the reason the scenario is skipped (shown in the report)."""
        if req.startswith("key:"):
            name = "key." + req[4:]
            names = [s.get("name") for s in (self.state("secrets_list") or {}).get("secrets", [])]
            return None if name in names else "нет ключа %s (секрет %s не задан)" % (req[4:], name)
        if req == "seeded-turns":
            return None if self.jvm else "сценарий ждёт засеянные ходы McpSmokeServer (есть только в --jvm)"
        m = (self.state("app_info") or {}).get("mcp", {})
        if req == "destructive":
            return None if m.get("allowDestructive") is True else "нужен mcp.allowDestructive (включён только в сборке для эмулятора)"
        if req == "audio":
            return None if m.get("allowAudio") is True else "нужен mcp.allowAudio (звук выключен по умолчанию)"
        raise ValueError("неизвестное требование в сценарии: " + req)

    def skip_reason(self, sc, fid):
        if self.jvm and sc.get("jvm") is False: return "не для --jvm (нужен настоящий Android)"
        missing = [t for t in sc.get("needs", []) if t not in self.tools]
        if missing: return "нет инструментов: " + ", ".join(missing)
        for req in sc.get("requires", []):
            why = self.requirement_reason(req)
            if why: return why
        g = sc.get("gate")
        if g:
            info = self.state("app_info") or {}
            m = info.get("mcp", {})
            want = {"destructive_off": ("allowDestructive", False), "destructive_on": ("allowDestructive", True), "audio_off": ("allowAudio", False), "audio_on": ("allowAudio", True)}.get(g)
            if want and m.get(want[0]) is not want[1]: return "нужно состояние %s (сейчас %s=%s)" % (g, want[0], m.get(want[0]))
        if sc.get("needsLlm"):
            cfg = self.state("llm_config") or {}
            ok = any(p.get("id") == cfg.get("provider") and p.get("hasKey") for p in cfg.get("providers", []))
            if not ok and self.jvm: ok = True
            if not ok: return "у выбранного провайдера нет ключа"
        if sc.get("needsUi"):
            hosts = (self.state("ui_hosts") or {}).get("hosts", [])
            if not any(h.get("id") == "live" and h.get("available") for h in hosts): return "нет живого окна приложения (откройте Jarvis)"
        return None

    def run_scenario(self, fid, sc):
        label = "%s / %s" % (fid, sc.get("name", "?"))
        why = self.skip_reason(sc, fid)
        if why:
            self.counts["skip"] += 1
            self.lines.append("- SKIP **%s** — %s" % (label, why))
            print("SKIP  %s: %s" % (label, why))
            return
        env = {"base": self.base, "rand": "%06x" % random.randrange(1 << 24)}
        ok_all, rows = True, []
        for i, step in enumerate(sc["steps"]):
            if "sleepMs" in step and "call" not in step: time.sleep(step["sleepMs"] / 1000.0); continue
            args = subst(step.get("args", {}), env)
            if self.host_default and step["call"].startswith("ui_") and "host" not in args and step["call"] not in ("ui_hosts", "ui_screens", "ui_theme"): args["host"] = self.host_default
            t0 = time.time()
            try:
                res = parse_result(self.cli.call(step["call"], args, step.get("timeoutS", 140)))
            except Exception as ex:
                ok_all = False
                rows.append("  - FAIL `%s` — транспорт: %s" % (step["call"], ex))
                break
            ms = int((time.time() - t0) * 1000)
            problems = []
            if "expectError" in step:
                code = ((res["json"] or {}).get("error") or {}).get("code")
                if not res["isError"] or (step["expectError"] and code != step["expectError"]): problems.append("ожидалась ошибка %s, пришло isError=%s code=%s" % (step["expectError"], res["isError"], code))
            elif res["isError"]:
                problems.append("isError: " + res["text"][:200])
            if not problems:
                for k, p in (step.get("save") or {}).items():
                    try: env[k] = path_get(res, p)
                    except KeyError: problems.append("save %s: путь %s не найден" % (k, p))
                for e in step.get("expect", []):
                    ok, msg = check(res, e, env)
                    if not ok: problems.append(msg)
            shot = ""
            if res["imgs"] and self.shots_dir:
                os.makedirs(self.shots_dir, exist_ok=True)
                mime, data = res["imgs"][0]
                fn = "%s-%s-%d.%s" % (fid, re.sub(r"\W+", "_", sc.get("name", "x"))[:30], i, {"image/png": "png", "image/webp": "webp"}.get(mime, "jpg"))
                open(os.path.join(self.shots_dir, fn), "wb").write(base64.b64decode(data))
                shot = " [снимок: shots/%s]" % fn
            rows.append("  - %s `%s` %d мс%s%s" % ("ok" if not problems else "FAIL", step["call"], ms, shot, "" if not problems else " — " + "; ".join(problems)))
            if problems: ok_all = False; break
        self.counts["pass" if ok_all else "fail"] += 1
        self.lines.append("- %s **%s**" % ("PASS" if ok_all else "FAIL", label))
        self.lines.extend(rows)
        print("%s  %s" % ("PASS" if ok_all else "FAIL", label))
        if not ok_all:
            for r in rows[-1:]: print(r)


def load_scenarios(only):
    out = []
    for f in sorted(glob.glob(os.path.join(ROOT, "tests", "mcp", "*.json"))):
        fid = os.path.basename(f)[:-5]
        if only and fid not in only: continue
        d = json.load(open(f, encoding="utf-8"))
        for sc in (d["scenarios"] if "scenarios" in d else [d]):
            for req in sc.get("requires", []):
                if req not in ("destructive", "audio", "seeded-turns") and not (req.startswith("key:") and len(req) > 4):
                    raise SystemExit("%s / %s: неизвестное требование %r" % (fid, sc.get("name"), req))
            out.append((fid, sc))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jvm", action="store_true", help="run against McpSmokeServer (fake runtime)")
    ap.add_argument("--jvm-flags", default="", help="extra McpSmokeServer flags, e.g. '--destructive'")
    ap.add_argument("--only", default="", help="comma separated feature ids")
    ap.add_argument("--host", default="", choices=["", "live", "headless"], help="default host for ui_* tools")
    ap.add_argument("--pkg", default=os.environ.get("JV_PKG", "app.jarvis"))
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--report-dir", default=os.path.join(ROOT, "build", "device"))
    a = ap.parse_args()
    only = [x for x in a.only.split(",") if x]
    scenarios = load_scenarios(only)
    if a.list:
        for fid, sc in scenarios: print("%-18s %s" % (fid, sc.get("name")))
        return 0
    proc = None
    if a.jvm:
        proc, port, token = start_jvm(a.jvm_flags.split())
        mode = "jvm (McpSmokeServer, fake runtime)"
    else:
        port, token = resolve_device(a.pkg)
        mode = "device %s" % a.pkg
    try:
        cli = Mcp("127.0.0.1", port, token)
        cli.init()
        tools = {t["name"] for t in cli.rpc("tools/list")["tools"]}
        info = (parse_result(cli.call("app_info", {}))["json"] or {})
        lines = []
        r = Runner(cli, "http://127.0.0.1:%d" % port, a.host, tools, os.path.join(a.report_dir, "shots"), a.jvm, lines)
        t0 = time.time()
        for fid, sc in scenarios: r.run_scenario(fid, sc)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        os.makedirs(a.report_dir, exist_ok=True)
        path = os.path.join(a.report_dir, "mcp-report-%s.md" % stamp)
        head = ["# MCP smoke report %s" % stamp, "", "- режим: %s" % mode, "- приложение: %s, flavor %s" % (info.get("package", "?"), info.get("flavor", "?")),
                "- инструментов в мосте: %d" % len(tools), "- итог: **%d pass, %d fail, %d skip**, %.1f с" % (r.counts["pass"], r.counts["fail"], r.counts["skip"], time.time() - t0), ""]
        open(path, "w", encoding="utf-8").write("\n".join(head + lines) + "\n")
        print("\n%d pass, %d fail, %d skip" % (r.counts["pass"], r.counts["fail"], r.counts["skip"]))
        print("report:", path)
        return 1 if r.counts["fail"] else 0
    finally:
        if proc:
            try: proc.stdin.close(); proc.wait(timeout=10)
            except Exception: proc.kill()


if __name__ == "__main__":
    sys.exit(main())
