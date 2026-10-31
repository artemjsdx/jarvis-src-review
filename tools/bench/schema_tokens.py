#!/usr/bin/env python3
"""Token cost of the tool schemas sent on every LLM request.

  bash tools/art-run.sh <apk> app.jarvis.dev.checks.SchemaDump <apk> <outDir> > specs.txt
  python3 tools/bench/schema_tokens.py specs.txt [--live MODEL]

Static: serialized size per tool and chars/3.2 token estimate (Russian text, 3.2 chars per token is the working figure).
--live: real prompt_tokens through OpenRouter, requests with and without the tools block (max_tokens 1; ~0.001 USD each);
the key is read from ~/.local/share/opencode/auth.json and is never printed.
"""
import json, os, sys, urllib.request

def load(path):
    specs = []
    for line in open(path, encoding="utf-8"):
        if line.startswith("SPEC\t"):
            _, name, js = line.rstrip("\n").split("\t", 2)
            specs.append((name, json.loads(js)))
    return specs

def live(specs, model):
    home = os.environ.get("TERMUX_HOME", "/data/data/com.termux/files/home")
    key = json.load(open(os.path.join(home, ".local/share/opencode/auth.json")))["openrouter"]["key"]
    def call(tools):
        body = {"model": model, "max_tokens": 1, "messages": [{"role": "user", "content": "ok"}]}
        if tools: body["tools"] = tools
        r = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", json.dumps(body).encode(),
                                   {"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        return json.load(urllib.request.urlopen(r, timeout=60))["usage"]["prompt_tokens"]
    base = call(None)
    full = call([s for _, s in specs])
    return base, full

def main():
    specs = load(sys.argv[1])
    rows = sorted(((len(json.dumps(s, ensure_ascii=False, separators=(",", ":"))), n) for n, s in specs), reverse=True)
    total = sum(c for c, _ in rows)
    for c, n in rows: print("%6d chars  ~%5d tok  %s" % (c, round(c / 3.2), n))
    print("TOTAL %d tools, %d chars, ~%d tokens (chars/3.2)" % (len(rows), total, round(total / 3.2)))
    if "--live" in sys.argv:
        model = sys.argv[sys.argv.index("--live") + 1]
        base, full = live(specs, model)
        print("LIVE %s: prompt_tokens without tools %d, with tools %d, delta %d" % (model, base, full, full - base))

main()
