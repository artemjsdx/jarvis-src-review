#!/usr/bin/env python3
"""Does the provider cache the stable prefix (system + tools) across consecutive requests?

  python3 tools/bench/cache_check.py <tools-specs.txt> <model> [runs]

Sends `runs` requests that share a long stable prefix (the tool schemas from SchemaDump plus a ~3000-char system text) and a
per-request volatile line at the end of the user message (time), like the app does. Prints cached_tokens per request.
For anthropic/* models the system message gets {"cache_control":{"type":"ephemeral"}} exactly as OpenAiClient does.
Key from ~/.local/share/opencode/auth.json; never printed. Keep runs small: it is paid.
"""
import json, os, sys, time, urllib.request

def specs(path):
    out = []
    for line in open(path, encoding="utf-8"):
        if line.startswith("SPEC\t"):
            out.append(json.loads(line.rstrip("\n").split("\t", 2)[2]))
    return out

SYSTEM = ("Ты — Jarvis, личный голосовой ассистент на Android-телефоне пользователя. Говори по-русски.\n\n" +
          "СТИЛЬ ОТВЕТА\n- Ответ озвучивается голосом: одно-два коротких предложения, без markdown.\n" * 40)

def main():
    tools = specs(sys.argv[1]); model = sys.argv[2]; runs = int(sys.argv[3]) if len(sys.argv) > 3 else 4
    key = json.load(open(os.path.join(os.environ.get("TERMUX_HOME", "/data/data/com.termux/files/home"), ".local/share/opencode/auth.json")))["openrouter"]["key"]
    cached_model = model.startswith("anthropic/")
    sysmsg = {"role": "system", "content": [{"type": "text", "text": SYSTEM, "cache_control": {"type": "ephemeral"}}] if cached_model else SYSTEM}
    for i in range(runs):
        user = "сколько времени? [контекст: сейчас: %s]" % time.strftime("%H:%M:%S")
        body = {"model": model, "max_tokens": 1, "messages": [sysmsg, {"role": "user", "content": user}], "tools": tools}
        r = urllib.request.Request("https://openrouter.ai/api/v1/chat/completions", json.dumps(body).encode(),
                                   {"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        for attempt in range(3):
            try:
                u = json.load(urllib.request.urlopen(r, timeout=90))["usage"]; break
            except Exception as e:
                if attempt == 2: raise
                time.sleep(3)
        det = u.get("prompt_tokens_details") or {}
        print("run %d: prompt_tokens %s, cached_tokens %s, cache_write %s" % (i + 1, u.get("prompt_tokens"), det.get("cached_tokens"), det.get("cache_write_tokens")))
        time.sleep(2)

main()
