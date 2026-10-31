#!/usr/bin/env python3
"""Live LLM round trip on the emulator build, through the MCP bridge: stores the OpenRouter key in the app, selects
deepseek/deepseek-v4.1-flash, sends one plain turn and one agent turn that must call a tool, then records timings, the reply,
the tool calls, the cost (key usage delta) and whether the key value is absent from the journal.
The key is read from OPENROUTER_KEY only and never printed or written. Exit 1 when a turn fails, no tool was called or the cost
exceeds the budget of the run.
  OPENROUTER_KEY=... python3 tools/ci/llm-roundtrip.py <out-dir>
Writes <out-dir>/llm.json and appends the verdict lines to <out-dir>/summary.txt."""
import importlib.machinery
import importlib.util
import json
import os
import sys
import time
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MODEL = "deepseek/deepseek-v4.1-flash"
PROVIDER = "openrouter"
BUDGET_USD = 0.02
PLAIN = "Ответь одним словом: ок"
AGENT = "Создай заметку «тест» и скажи, что получилось"


def load_smoke():
    path = os.path.join(ROOT, "tools", "mcp-smoke.py")
    loader = importlib.machinery.SourceFileLoader("mcp_smoke", path)
    mod = importlib.util.module_from_spec(importlib.util.spec_from_loader("mcp_smoke", loader))
    loader.exec_module(mod)
    return mod


smoke = load_smoke()
KEY = os.environ.get("OPENROUTER_KEY", "").strip()


def key_usage():
    req = urllib.request.Request("https://openrouter.ai/api/v1/auth/key", headers={"Authorization": "Bearer " + KEY})
    with urllib.request.urlopen(req, timeout=20) as r:
        return float(json.load(r)["data"]["usage"])


def call(cli, name, args, timeout=140):
    res = smoke.parse_result(cli.call(name, args, timeout))
    if res["isError"]:
        raise RuntimeError("%s: %s" % (name, res["text"][:300]))
    return res["json"] or {}


def turn(cli, text):
    t0 = time.time()
    r = call(cli, "chat_send", {"text": text, "wait": True, "timeoutMs": 120000}, 130)
    r["wallMs"] = int((time.time() - t0) * 1000)
    return r


def summarize(r):
    tools = [t.get("name", "?") for t in r.get("toolCalls", [])]
    return {"reply": (r.get("reply") or "")[:300], "error": r.get("error", ""), "run": r.get("run", {}),
            "toolCalls": len(tools), "tools": tools, "timings": r.get("timings", {}), "wallMs": r.get("wallMs")}


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "build/emu"
    if not KEY:
        raise SystemExit("OPENROUTER_KEY is empty: nothing to do")
    port, token = smoke.resolve_device("app.jarvis")
    cli = smoke.Mcp("127.0.0.1", port, token)
    cli.init()

    call(cli, "secrets_set", {"name": "key.openrouter", "value": KEY})
    call(cli, "llm_set", {"provider": PROVIDER, "model": MODEL})
    before = key_usage()
    plain = summarize(turn(cli, PLAIN))
    agent = summarize(turn(cli, AGENT))
    cost = round(key_usage() - before, 5)

    trace = call(cli, "trace_recent", {"n": 2})
    journal = json.dumps(call(cli, "journal_tail", {"n": 500}), ensure_ascii=False)
    redacted = KEY not in journal and KEY not in json.dumps(trace, ensure_ascii=False)

    result = {"model": MODEL, "provider": PROVIDER, "plain": plain, "agent": agent, "costUsd": cost,
              "budgetUsd": BUDGET_USD, "keyRedactedInJournal": redacted, "traces": trace.get("traces", [])}
    with open(os.path.join(out, "llm.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=1)

    problems = []
    if not plain["reply"].strip() or plain["error"]: problems.append("plain turn without a reply")
    if agent["toolCalls"] < 1 or agent["error"]: problems.append("agent turn did not call a tool")
    if cost > BUDGET_USD: problems.append("cost %.4f USD above the %.2f USD budget" % (cost, BUDGET_USD))
    if not redacted: problems.append("the key value reached the journal")
    verdict = "OK" if not problems else "FAIL: " + "; ".join(problems)
    line = ("llm %s: plain reply %r in %s ms; agent %d tool call(s) %s in %s ms, reply %r; cost %.4f USD; key redacted: %s"
            % (verdict, plain["reply"][:60], plain["timings"].get("totalMs", "?"), agent["toolCalls"], ",".join(agent["tools"]) or "-",
               agent["timings"].get("totalMs", "?"), agent["reply"][:80], cost, "yes" if redacted else "NO"))
    print(line)
    with open(os.path.join(out, "summary.txt"), "a", encoding="utf-8") as f:
        f.write(line + "\n")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
