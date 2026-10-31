#!/usr/bin/env python3
"""Farm supervisor: a queue of headless Claude agents with retries, stall watchdog and a budget guard.

Why: agents died whenever the network blinked ("Connection closed mid-response", ECONNRESET) and left half-done work and
a dead session. The supervisor keeps every task alive until it really finishes:
  * runs at most `concurrency` agents at once (the phone is also the owner's daily phone, agents run under `nice`);
  * on a transient API/network failure waits for the network, then RESUMES the same Claude session (not a fresh start);
  * kills and resumes an agent whose log has not grown for `stall_min` minutes;
  * stops everything when the OpenRouter balance drops below `reserve_usd` (so the owner's own sessions never starve);
  * state lives in supervisor.json next to farm.py, so a restart of the supervisor loses nothing.
Usage: supervisor.py add|status|start|stop|pause|unpause|retry|kill|inject|set|run   (see --help of each)
"""
import argparse, json, os, re, signal, subprocess, sys, time, urllib.request
from pathlib import Path

FARM = Path(os.environ.get("FARM_DIR", "/data/data/com.termux/files/usr/var/lib/proot-distro/containers/ubuntu/rootfs/home/claude/agentfarm"))
sys.path.insert(0, str(FARM))
STATE, LOG, PIDF = FARM / "supervisor.json", FARM / "supervisor.log", FARM / "supervisor.pid"
INBOX = FARM / "supervisor.inbox"      # CLI commands (one JSON per line); the daemon is the ONLY writer of supervisor.json while it runs
DEFAULTS = {"concurrency": 3, "reserve_usd": 2.5, "stall_min": 15, "max_attempts": 10, "poll_s": 20, "notify": True, "net_stable_s": 45, "launch_margin_usd": 1.0,
            "model": "anthropic/claude-sonnet-5.5", "provider": "OpenRouter", "paused": False, "paused_reason": ""}
TRANSIENT = re.compile(r"(?i)ECONNRESET|ECONNREFUSED|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|EPIPE|socket hang up|connection (was )?(closed|reset|lost|refused)|"
                       r"connection to the api|mid-response|stream (ended|closed)|unable to connect|timed? ?out|overloaded|temporarily|"
                       r"api error: ?(5\d\d|429|connection)|\b(500|502|503|504|520|522|524|529)\b|rate.?limit|network|fetch failed|server_error|"
                       r"internal server error|bad gateway|service unavailable")
FATAL = re.compile(r"(?i)invalid api key|authentication|unauthorized|\b401\b|forbidden|\b403\b|model.*not found|no such model")
CREDIT = re.compile(r"(?i)insufficient|credit|payment required|\b402\b|out of (extra )?usage|billing")
NET_ONLY = re.compile(r"(?i)ECONNRESET|ECONNREFUSED|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|EPIPE|socket hang up|connection (was )?(closed|reset|lost|refused)|"
                      r"unable to connect|mid-response|fetch failed|stream (ended|closed)")


def now(): return time.time()


# USD per 1M tokens (input, output) from OpenRouter /models; cache read = 0.1x input, cache write = 1.25x input.
PRICES = {"claude-sonnet-5.5": (2.0, 10.0), "claude-sonnet-5": (2.0, 10.0), "claude-haiku-5.5": (0.10, 0.50), "claude-opus-5.5": (4.0, 20.0),
          "claude-sonnet-4.6": (3.0, 15.0), "claude-opus-4.8": (5.0, 25.0), "claude-haiku-4.5": (1.0, 5.0)}


def real_cost(ev):
    """Claude Code's own total_cost_usd uses a guessed price for unknown model names (it overstates Haiku ~25x): price the tokens ourselves."""
    total = 0.0
    for model, u in ((ev or {}).get("modelUsage") or {}).items():
        pin, pout = next((v for k, v in PRICES.items() if k in model), (3.0, 15.0))
        total += (u.get("inputTokens", 0) * pin + u.get("outputTokens", 0) * pout + u.get("cacheReadInputTokens", 0) * pin * 0.1
                  + u.get("cacheCreationInputTokens", 0) * pin * 1.25) / 1e6
    return total


def log(msg):
    line = "%s %s" % (time.strftime("%m-%d %H:%M:%S"), msg)
    with open(LOG, "a", encoding="utf8") as f: f.write(line + "\n")
    print(line, flush=True)


def load():
    try: st = json.loads(STATE.read_text())
    except (OSError, ValueError): st = {}
    st.setdefault("config", {}); st.setdefault("tasks", {})
    for k, v in DEFAULTS.items(): st["config"].setdefault(k, v)
    return st


def save(st):
    tmp = STATE.with_suffix(".tmp"); tmp.write_text(json.dumps(st, ensure_ascii=False, indent=1)); os.replace(tmp, STATE)


def notify(st, title, text):
    if not st["config"].get("notify"): return
    try:
        subprocess.Popen(["termux-notification", "--id", "farm-sup", "--title", title, "--content", text[:300]],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError: pass


def or_key():
    try:
        keys = json.loads((FARM / "farm_keys.json").read_text()).get("OpenRouter") or []
        return keys[0] if isinstance(keys, list) else keys
    except (OSError, ValueError, IndexError): return ""


def net_ok():
    """Light probe (a few hundred bytes: this runs every poll on mobile data). Any HTTP answer, even 401, proves the path works."""
    h = {"User-Agent": "curl/8.4.0"}
    k = or_key()
    if k: h["Authorization"] = "Bearer " + k
    for attempt in range(2):
        try:
            with urllib.request.urlopen(urllib.request.Request("https://openrouter.ai/api/v1/auth/key", headers=h), timeout=8): return True
        except urllib.error.HTTPError: return True
        except Exception: pass
        if attempt == 0: time.sleep(2)
    return False


def credits_left():
    k = or_key()
    if not k: return None
    try:
        r = urllib.request.Request("https://openrouter.ai/api/v1/credits", headers={"Authorization": "Bearer " + k, "User-Agent": "curl/8.4.0"})
        with urllib.request.urlopen(r, timeout=15) as x:
            d = json.loads(x.read())["data"]; return d["total_credits"] - d["total_usage"]
    except Exception: return None


def alive(pid):
    try:
        os.kill(pid, 0)
        with open("/proc/%d/stat" % pid) as f: return f.read().rsplit(")", 1)[1].split()[0] != "Z"
    except (OSError, IndexError): return False


def kill_group(pid):
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try: os.killpg(pid, sig)
        except OSError:
            try: os.kill(pid, sig)
            except OSError: pass
        for _ in range(20):
            if not alive(pid): return
            time.sleep(0.25)


def read_events(path, head=False, tail_bytes=200_000):
    """Yield parsed stream-json events: from the start (head, first 60 lines) or from the tail."""
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            if head: lines = [f.readline() for _ in range(60)]
            else:
                f.seek(max(0, size - tail_bytes)); data = f.read().split(b"\n"); lines = data[1:] if size > tail_bytes else data
    except OSError: return []
    out = []
    for ln in lines:
        try: out.append(json.loads(ln))
        except ValueError: pass
    return out


def session_id_of(path):
    for ev in read_events(path, head=True):
        if ev.get("session_id"): return ev["session_id"]
    for ev in read_events(path):
        if ev.get("session_id"): return ev["session_id"]
    return ""


def final_of(path):
    for ev in reversed(read_events(path)):
        if ev.get("type") == "result": return ev
    return None


def activity(path):
    for ev in reversed(read_events(path, tail_bytes=60_000)):
        if ev.get("type") != "assistant": continue
        for c in reversed(ev.get("message", {}).get("content", [])):
            if c.get("type") == "tool_use":
                i = c.get("input", {}); v = i.get("command") or i.get("file_path") or i.get("pattern") or i.get("description") or ""
                return ("%s: %s" % (c.get("name"), str(v).replace("\n", " ")))[:90]
            if c.get("type") == "text" and c.get("text", "").strip(): return ("say: " + c["text"].strip().replace("\n", " "))[:90]
    return ""


def resume_prompt(t, reason):
    extra = (t.pop("inject", "") or "").strip()
    base = ("The previous run was interrupted (%s). Continue the SAME task from where you stopped; do not start over. First run `git status` and "
            "`git log --oneline -8` in your worktree to see what is already done and committed, then continue with the next unfinished step. "
            "Commit after every finished step so an interruption never loses work. When the whole task file is done, end with the final report." % reason)
    return base + (("\n\nNEW INSTRUCTIONS FROM THE LEAD (apply them in addition to the task file):\n" + extra) if extra else "")


def launch(st, t, reason=""):
    import farm
    first = not t.get("session_id")
    if first:
        prompt = t["prompt"]
        extra = (t.pop("inject", "") or "").strip()
        if extra: prompt += "\n\nADDITIONAL INSTRUCTIONS FROM THE LEAD (apply them in addition to the task file):\n" + extra
    else:
        prompt = resume_prompt(t, reason or "resumed")
    t["attempts"] = t.get("attempts", 0) + 1
    name = "%s-a%d" % (t["name"], t["attempts"])
    r = farm.spawn(prompt, provider=t.get("provider") or st["config"]["provider"], model=t.get("model") or st["config"]["model"],
                   room=t.get("room", "wave"), name=name, cwd=t["cwd"], resume=("" if first else t["session_id"]),
                   max_turns=t.get("max_turns"), timeout=14400)
    if not r.get("ok"):
        t["last_error"] = str(r.get("error"))[:200]; t["state"] = "retry"
        t["spawn_failures"] = t.get("spawn_failures", 0) + 1
        t["attempts"] = max(0, t["attempts"] - 1)                      # a spawn that never started is not an attempt
        t["next_try"] = now() + min(30 * t["spawn_failures"], 300)
        if t["spawn_failures"] > 60: t["state"] = "failed"
        log("%s: spawn failed (#%d): %s" % (t["name"], t["spawn_failures"], t["last_error"][:120])); return
    t["spawn_failures"] = 0
    t.update(state="running", farm_id=r["id"], started=now(), log=str(FARM / "logs" / t.get("room", "wave") / (name + ".log")))
    log("%s: attempt %d started as %s (%s)%s" % (t["name"], t["attempts"], r["id"], r.get("model"), "" if first else " [resume]"))


def finish_attempt(st, t):
    ev = final_of(t["log"]); sid = (ev or {}).get("session_id") or session_id_of(t["log"])
    if sid: t["session_id"] = sid
    cost = real_cost(ev) if ev else 0.0; t["spent"] = round(t.get("spent", 0) + cost, 2)
    text = str((ev or {}).get("result") or ""); sub = (ev or {}).get("subtype", "")
    if ev and not ev.get("is_error") and sub == "success":
        t.update(state="done", finished=now(), final=text[:6000]); log("%s: DONE ($%.2f this attempt, $%.2f total)" % (t["name"], cost, t["spent"]))
        notify(st, "Farm: %s done" % t["name"], "$%.2f spent" % t["spent"]); return
    if ev and sub == "error_max_turns":
        t.update(state="maxturns", finished=now(), final=text[:2000]); log("%s: stopped at max turns" % t["name"]); notify(st, "Farm: %s max turns" % t["name"], "needs a decision"); return
    reason = (text or "process exited without a result")[:160]
    cfg = st["config"]
    if CREDIT.search(text) and not NET_ONLY.search(text):
        # running out of money is not the task's fault: park the whole farm; it resumes by itself when the balance recovers
        cfg.update(paused=True, paused_reason="budget: provider says: " + reason[:70], paused_at=now())
        t.update(state="retry", last_error=reason, next_try=0); t["attempts"] = max(0, t.get("attempts", 1) - 1)
        log("%s: PAUSED farm (credits): %s" % (t["name"], reason[:100])); notify(st, "Farm paused", "credits: " + reason[:80]); return
    if NET_ONLY.search(text) and t.get("net_retries", 0) < 80:
        # a network blip does not use up an attempt (the resume itself is gated on a stable connection)
        t["attempts"] = max(0, t.get("attempts", 1) - 1); t["net_retries"] = t.get("net_retries", 0) + 1
        t.update(state="retry", last_error=reason, next_try=now() + 10)
        log("%s: network failure #%d (%s); will resume when the connection is stable" % (t["name"], t["net_retries"], reason[:70])); return
    if FATAL.search(text) and not TRANSIENT.search(text):
        t.update(state="failed", finished=now(), last_error=reason); log("%s: FATAL %s" % (t["name"], reason)); notify(st, "Farm: %s failed" % t["name"], reason); return
    if t["attempts"] >= st["config"]["max_attempts"]:
        t.update(state="failed", finished=now(), last_error="max attempts: " + reason); log("%s: FAILED after %d attempts" % (t["name"], t["attempts"])); notify(st, "Farm: %s failed" % t["name"], reason); return
    t.update(state="retry", last_error=reason, next_try=now() + min(20 * 2 ** (t["attempts"] - 1), 300))
    log("%s: transient failure (%s); retry in %ds" % (t["name"], reason[:90], t["next_try"] - now()))


def tick(st, netstate):
    cfg, tasks = st["config"], st["tasks"]
    ok = net_ok()
    if ok != netstate.get("ok"):
        log("network %s" % ("UP" if ok else "DOWN")); netstate["ok"] = ok
        netstate["up_since"] = now() if ok else None
    stable = bool(ok and netstate.get("up_since") and now() - netstate["up_since"] >= cfg["net_stable_s"])
    # balance: every 300 s (every 90 s while paused for budget, so a top-up resumes the farm without anyone touching it)
    paused_budget = cfg["paused"] and str(cfg.get("paused_reason", "")).startswith("budget")
    if ok and now() - netstate.get("credit_t", 0) > (90 if paused_budget else 300):
        netstate["credit_t"] = now(); left = credits_left()
        if left is not None:
            netstate["left"] = left
            if left < cfg["reserve_usd"] and not cfg["paused"]:
                cfg.update(paused=True, paused_reason="budget: $%.2f left < reserve $%.2f" % (left, cfg["reserve_usd"]), paused_at=now())
                log("PAUSED: " + cfg["paused_reason"]); notify(st, "Farm paused", cfg["paused_reason"])
                for t in tasks.values():
                    if t["state"] == "running" and t.get("pid"):
                        kill_group(t["pid"]); t["session_id"] = session_id_of(t["log"]) or t.get("session_id", "")
                        t["state"] = "retry"; t["next_try"] = 0; t["attempts"] = max(0, t.get("attempts", 1) - 1)
            elif paused_budget and left >= cfg["reserve_usd"] + 3 and now() - cfg.get("paused_at", 0) > 120:
                cfg.update(paused=False, paused_reason="")
                log("RESUMED: balance recovered ($%.2f)" % left); notify(st, "Farm resumed", "balance $%.2f" % left)
    for t in tasks.values():
        if t["state"] != "running": continue
        import farm
        a = farm._state()["agents"].get(t.get("farm_id", ""), {}); pid = a.get("pid"); t["pid"] = pid
        if pid and alive(pid):
            try: idle = now() - os.path.getmtime(t["log"])
            except OSError: idle = 0
            if idle > cfg["stall_min"] * 60:
                log("%s: stalled %dm, killing" % (t["name"], idle // 60)); kill_group(pid)
                t["state"] = "retry"; t["last_error"] = "stalled"; t["next_try"] = now() + 5; t["attempts"] = max(0, t.get("attempts", 1) - 1)
                sid = session_id_of(t["log"]); t["session_id"] = sid or t.get("session_id", "")
            continue
        finish_attempt(st, t)
    running = sum(1 for t in tasks.values() if t["state"] == "running")
    if cfg["paused"] or not stable: return
    left = netstate.get("left")
    if left is not None and left < cfg["reserve_usd"] + cfg["launch_margin_usd"]: return
    ready = sorted((t for t in tasks.values() if t["state"] in ("queued", "retry") and t.get("next_try", 0) <= now()
                    and all(tasks.get(d, {}).get("state") == "done" for d in t.get("after", []))), key=lambda t: (t.get("priority", 5), t.get("added", 0)))
    for t in ready:
        if running >= cfg["concurrency"]: break
        launch(st, t, t.get("last_error", "")); running += 1 if t["state"] == "running" else 0


def run():
    PIDF.write_text(str(os.getpid())); log("supervisor started (pid %d)" % os.getpid()); netstate = {}
    stop = {"v": False}
    signal.signal(signal.SIGTERM, lambda *a: stop.update(v=True))
    while not stop["v"]:
        st = load()
        try: drain_inbox(st)
        except Exception as e: log("inbox error: %s: %s" % (type(e).__name__, e))
        try: tick(st, netstate)
        except Exception as e: log("tick error: %s: %s" % (type(e).__name__, e))
        save(st)
        for _ in range(st["config"]["poll_s"]):
            if stop["v"]: break
            time.sleep(1)
    log("supervisor stopped")


def apply_cmd(st, c):
    """Apply one queued command to the state (called by the daemon, or by the CLI when no daemon runs)."""
    k = c["cmd"]
    if k == "add":
        st["tasks"][c["name"]] = {"name": c["name"], "cwd": c["cwd"], "prompt": c["prompt"], "state": "queued", "attempts": 0, "spent": 0.0, "added": now(),
                                  "room": c.get("room", "wave"), "provider": c.get("provider", ""), "model": c.get("model", ""), "max_turns": c.get("max_turns"),
                                  "after": c.get("after") or [], "priority": c.get("priority", 5)}
        if c.get("session"): st["tasks"][c["name"]]["session_id"] = c["session"]
        log("task added: %s" % c["name"]); return
    if k == "pause": st["config"].update(paused=True, paused_reason="manual"); return
    if k == "unpause": st["config"].update(paused=False, paused_reason=""); return
    if k == "set":
        v = c["value"]
        try: v = json.loads(v)
        except (ValueError, TypeError): pass
        st["config"][c["key"]] = v; return
    t = st["tasks"].get(c.get("name", ""))
    if t is None: log("command %s: no such task %r" % (k, c.get("name"))); return
    if k == "retry": t.update(state="queued", attempts=0, last_error="", next_try=0, net_retries=0, spawn_failures=0)
    elif k == "setmodel": t["model"] = c["model"]
    elif k == "prio": t["priority"] = int(c["value"])
    elif k == "kill":
        if t.get("pid") and alive(t["pid"]): kill_group(t["pid"])
        t["state"] = "failed"; t["last_error"] = "killed by lead"
    elif k == "inject":
        t["inject"] = c["text"]
        if t["state"] == "running" and t.get("pid") and alive(t["pid"]):
            kill_group(t["pid"]); t["session_id"] = session_id_of(t["log"]) or t.get("session_id", ""); t["state"] = "retry"; t["next_try"] = 0; t["attempts"] = max(0, t["attempts"] - 1)
        elif t["state"] in ("done", "maxturns", "failed"): t["state"] = "retry"; t["next_try"] = 0; t["attempts"] = 0


def daemon_alive():
    try: return PIDF.exists() and PIDF.read_text().strip().isdigit() and alive(int(PIDF.read_text()))
    except OSError: return False


def submit(c):
    """Daemon running -> append to the inbox (it picks the command up within one poll); otherwise apply directly under an exclusive lock."""
    if daemon_alive():
        with open(INBOX, "a", encoding="utf8") as f: f.write(json.dumps(c, ensure_ascii=False) + "\n")
        print("queued for the daemon:", c["cmd"], c.get("name", c.get("key", ""))); return
    import fcntl
    with open(FARM / "supervisor.lock", "w") as lk:
        fcntl.flock(lk, fcntl.LOCK_EX); st = load(); apply_cmd(st, c); save(st)
    print("applied:", c["cmd"], c.get("name", c.get("key", "")))


def drain_inbox(st):
    if not INBOX.exists(): return
    tmp = INBOX.with_suffix(".processing")
    try: os.replace(INBOX, tmp)
    except OSError: return
    for line in tmp.read_text(encoding="utf8").splitlines():
        try: apply_cmd(st, json.loads(line))
        except Exception as e: log("bad inbox command %r: %s" % (line[:80], e))
    tmp.unlink()


def cmd_add(a):
    prompt = Path(a.prompt_file).read_text() if a.prompt_file else a.prompt
    submit({"cmd": "add", "name": a.name, "cwd": a.cwd, "prompt": prompt, "room": a.room, "provider": a.provider, "model": a.model, "max_turns": a.max_turns,
            "after": a.after or [], "priority": a.priority, "session": a.session})


def cmd_status(a):
    st = load(); c = st["config"]
    if a.json: print(json.dumps(st, ensure_ascii=False, indent=1)); return
    pid = int(PIDF.read_text()) if PIDF.exists() and PIDF.read_text().strip().isdigit() else 0
    left = credits_left()
    print("supervisor: %s | paused=%s %s | concurrency=%d | reserve=$%.0f | account left=%s" % ("RUNNING pid %d" % pid if pid and alive(pid) else "NOT running", c["paused"], c["paused_reason"], c["concurrency"], c["reserve_usd"], "?" if left is None else "$%.2f" % left))
    for t in sorted(st["tasks"].values(), key=lambda t: t.get("added", 0)):
        age = "%dm" % ((now() - t["started"]) // 60) if t.get("started") and t["state"] == "running" else ""
        print("%-9s %-8s try=%d $%-6.2f %-5s %s | %s" % (t["name"], t["state"], t.get("attempts", 0), t.get("spent", 0), age, t.get("model", "default").split("/")[-1][:18],
              (activity(t["log"]) if t["state"] == "running" and t.get("log") else t.get("last_error", ""))[:70]))


def cmd_start(a):
    if daemon_alive(): print("already running"); return
    subprocess.Popen([sys.executable, os.path.abspath(__file__), "run"], stdout=open(FARM / "supervisor.out", "a"), stderr=subprocess.STDOUT,
                     stdin=subprocess.DEVNULL, start_new_session=True, close_fds=True); time.sleep(1.5); print("started")


def cmd_stop(a):
    if PIDF.exists() and PIDF.read_text().strip().isdigit():
        try: os.kill(int(PIDF.read_text()), signal.SIGTERM); print("stopping (agents keep running)")
        except OSError: print("not running")


def cmd_mod(a):
    c = {"cmd": a.cmd}
    for attr in ("name", "text", "key", "value", "model"):
        if hasattr(a, attr): c[attr] = getattr(a, attr)
    submit(c)


def main():
    ap = argparse.ArgumentParser(); sp = ap.add_subparsers(dest="cmd", required=True)
    p = sp.add_parser("add"); p.add_argument("name"); p.add_argument("--cwd", required=True); p.add_argument("--prompt"); p.add_argument("--prompt-file")
    p.add_argument("--room", default="wave"); p.add_argument("--provider", default=""); p.add_argument("--model", default=""); p.add_argument("--max-turns", type=int, default=None)
    p.add_argument("--after", nargs="*"); p.add_argument("--priority", type=int, default=5); p.add_argument("--session", default="")
    p = sp.add_parser("status"); p.add_argument("--json", action="store_true")
    for c in ("start", "stop", "run", "pause", "unpause"): sp.add_parser(c)
    for c in ("retry", "kill"): sp.add_parser(c).add_argument("name")
    p = sp.add_parser("inject"); p.add_argument("name"); p.add_argument("text")
    p = sp.add_parser("set"); p.add_argument("key"); p.add_argument("value")
    p = sp.add_parser("setmodel"); p.add_argument("name"); p.add_argument("model")
    p = sp.add_parser("prio"); p.add_argument("name"); p.add_argument("value")
    a = ap.parse_args()
    {"add": cmd_add, "status": cmd_status, "start": cmd_start, "stop": cmd_stop, "run": lambda a: run()}.get(a.cmd, cmd_mod)(a)


if __name__ == "__main__": main()
