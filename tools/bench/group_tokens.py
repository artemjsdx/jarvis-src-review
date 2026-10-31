#!/usr/bin/env python3
"""Tool groups: how many tokens of tool schemas each kind of request carries, against announcing every tool.

  bash tools/art-run.sh <apk> app.jarvis.dev.checks.ToolGroupsCheck <apk> <outDir> > groups.txt
  python3 tools/bench/group_tokens.py groups.txt [--live MODEL] [--rows 0,5,9]

Static: serialized size of the tools block per sample request (the real registry of a real Jarvis on ART) and a chars/3.2 estimate.
--live: real prompt_tokens through OpenRouter: one request without tools and one per selected scenario, max_tokens 1
(~0.001 USD each); the tools block is the difference. The key is read from ~/.local/share/opencode/auth.json and never printed.
Rows: indexes of the sample requests to measure live (default: all of them, "all" = every tool announced).
"""
import json, os, sys, time, urllib.request


def load(path):
    allrow, rows, blocks = None, [], {}
    for line in open(path, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if p[0] == "ALL":
            allrow = (int(p[1]), int(p[2]))
        elif p[0] == "ROW":
            rows.append({"tools": int(p[1]), "chars": int(p[2]), "groups": p[4], "text": p[5]})
        elif p[0] == "BLOCK":
            blocks[p[1]] = json.loads(p[2])
        elif p[0] == "UNDECIDED":
            print("UNDECIDED TOOL (in no group, not core on purpose): " + p[1])
        elif p[0] == "CRASH":
            sys.exit(line)
    return allrow, rows, blocks


def post(key, tools, model):
    body = {"model": model, "max_tokens": 1, "messages": [{"role": "user", "content": "ok"}]}
    if tools:
        body["tools"] = tools
    last = None
    for attempt in range(3):  # this network drops connections now and then
        try:
            r = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", json.dumps(body).encode(),
                                       {"Authorization": "Bearer " + key, "Content-Type": "application/json"})
            return json.load(urllib.request.urlopen(r, timeout=60))["usage"]["prompt_tokens"]
        except Exception as e:  # noqa: BLE001 - report the class only, never the key
            last = type(e).__name__
            time.sleep(2 + attempt * 2)
    raise SystemExit("live request failed: " + str(last))


def main():
    allrow, rows, blocks = load(sys.argv[1])
    n_all, c_all = allrow
    print("%-4s %5s %7s %7s %6s  %-22s %s" % ("row", "tools", "chars", "~tok", "of all", "groups", "request"))
    print("%-4s %5d %7d %7d %5d%%  %-22s %s" % ("all", n_all, c_all, round(c_all / 3.2), 100, "-", "(every tool announced)"))
    for i, r in enumerate(rows):
        print("%-4d %5d %7d %7d %5d%%  %-22s %s" % (i, r["tools"], r["chars"], round(r["chars"] / 3.2), round(100.0 * r["chars"] / c_all), r["groups"], r["text"]))
    if "--live" not in sys.argv:
        return
    model = sys.argv[sys.argv.index("--live") + 1]
    home = os.environ.get("TERMUX_HOME", "/data/data/com.termux/files/home")
    key = json.load(open(os.path.join(home, ".local/share/opencode/auth.json")))["openrouter"]["key"]
    pick = sys.argv[sys.argv.index("--rows") + 1].split(",") if "--rows" in sys.argv else ["all"] + [str(i) for i in range(len(rows))]
    base = post(key, None, model)
    print("LIVE %s: prompt_tokens without tools = %d" % (model, base))
    for k in pick:
        used = post(key, blocks[k], model)
        label = "all tools" if k == "all" else rows[int(k)]["text"]
        print("LIVE row %-4s tools block = %5d tokens  (%s)" % (k, used - base, label))


main()
