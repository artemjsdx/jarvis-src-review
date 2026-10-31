#!/usr/bin/env python3
"""Does announcing fewer tools shorten the wait for the first token? A/B on the real prompt, interleaved, through OpenRouter.

  bash tools/art-run.sh <apk> app.jarvis.dev.checks.ToolGroupsCheck <apk> <outDir> > groups.txt
  python3 tools/bench/group_ttft.py groups.txt SYSTEM_TEXT_FILE [model] [rounds] [arms]

Arms are the tools blocks printed by ToolGroupsCheck: "all" (every tool, the old behaviour) or a row index ("0" = plain talk, core only;
"5" = a music request, core + music). One system prompt file is used for every arm (the block of tools is what differs).
Each round sends one request per arm, the order rotating, so provider drift hits every arm alike. Two modes:
  warm: identical prompt each round (the provider may reuse a cached prefix, as it does between turns of one conversation);
  cold: the first line of the system prompt carries a nonce, so no prefix can be cached (the raw cost of the prompt size).
Reports time to first token (ms), prompt_tokens, cached tokens and provider per arm. Key from ~/.local/share/opencode/auth.json, never printed.
Each request is a few thousand tokens on a cheap model: a full run costs a few cents."""
import json, os, statistics, sys, time, uuid
import requests

URL = 'https://openrouter.ai/api/v1/chat/completions'
AUTH = os.environ.get('JV_OPENROUTER_AUTH') or next(p for p in (os.path.expanduser('~/.local/share/opencode/auth.json'),
        '/data/data/com.termux/files/home/.local/share/opencode/auth.json') if os.path.exists(p))
KEY = json.load(open(AUTH))['openrouter']['key']


def load_blocks(path):
    blocks = {}
    for line in open(path, encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if p[0] == 'BLOCK':
            blocks[p[1]] = json.loads(p[2])
    return blocks


def call(sess, model, system, tools, nonce):
    sys_text = ('[%s]\n' % nonce + system) if nonce else system
    user = 'Привет, как дела?\n\n[контекст: сейчас: четверг, 30 октября 2026, 19:05; плеер: не играет; устройство: vivo I2407, Android 16]'
    body = {'model': model, 'stream': True, 'max_tokens': 16, 'temperature': 0, 'reasoning': {'enabled': False},
            'stream_options': {'include_usage': True}, 'tools': tools,
            'messages': [{'role': 'system', 'content': sys_text}, {'role': 'user', 'content': user}]}
    t0 = time.perf_counter()
    try:
        r = sess.post(URL, json=body, headers={'Authorization': 'Bearer ' + KEY}, stream=True, timeout=60)
    except Exception as e:  # noqa: BLE001 - class name only, never the key
        return {'err': type(e).__name__}
    if r.status_code != 200:
        return {'err': 'HTTP %d' % r.status_code}
    first, usage, prov = None, None, ''
    try:
        for line in r.iter_lines():
            if not line or not line.startswith(b'data: '):
                continue
            d = line[6:]
            if d == b'[DONE]':
                break
            try:
                j = json.loads(d)
            except Exception:  # noqa: BLE001
                continue
            prov = j.get('provider', prov)
            if j.get('usage'):
                usage = j['usage']
            for ch in j.get('choices', []):
                dl = ch.get('delta', {})
                if first is None and (dl.get('content') or dl.get('tool_calls')):
                    first = time.perf_counter() - t0
    except Exception as e:  # noqa: BLE001
        return {'err': type(e).__name__}
    if first is None:
        return {'err': 'no first token'}
    cached = ((usage or {}).get('prompt_tokens_details') or {}).get('cached_tokens')
    return {'first': first * 1000, 'prompt': (usage or {}).get('prompt_tokens'), 'cached': cached, 'provider': prov}


def med(v):
    return statistics.median(v) if v else float('nan')


def main():
    blocks = load_blocks(sys.argv[1])
    system = open(sys.argv[2], encoding='utf-8').read()
    model = sys.argv[3] if len(sys.argv) > 3 else 'deepseek/deepseek-v4.1-flash'
    rounds = int(sys.argv[4]) if len(sys.argv) > 4 else 6
    arms = (sys.argv[5] if len(sys.argv) > 5 else 'all,0,5').split(',')
    sess = requests.Session()
    for mode in ('warm', 'cold'):
        res = {a: [] for a in arms}
        errs = {a: 0 for a in arms}
        for r in range(rounds + (1 if mode == 'warm' else 0)):      # warm: the first round only fills the cache, it is not counted
            order = arms[r % len(arms):] + arms[:r % len(arms)]
            for a in order:
                out = call(sess, model, system, blocks[a], uuid.uuid4().hex[:8] if mode == 'cold' else None)
                if 'err' in out:
                    errs[a] += 1
                    print('  [%s round %d arm %s] %s' % (mode, r, a, out['err']), flush=True)
                elif mode == 'cold' or r > 0:
                    res[a].append(out)
        print('\n== %s, %s, %d rounds, system prompt %d chars ==' % (model, mode, rounds, len(system)))
        print('%-5s %5s %6s %8s %8s %8s %9s %8s  %s' % ('arm', 'tools', 'n/err', 'ttft med', 'min', 'max', 'prompt tk', 'cached', 'providers'))
        for a in arms:
            v = [x['first'] for x in res[a]]
            if not v:
                print('%-5s no successful request' % a)
                continue
            print('%-5s %5d %3d/%-2d %8.0f %8.0f %8.0f %9.0f %8.0f  %s' % (a, len(blocks[a]), len(v), errs[a], med(v), min(v), max(v),
                  med([x['prompt'] for x in res[a] if x['prompt']]), med([x['cached'] or 0 for x in res[a]]),
                  ','.join(sorted(set(x['provider'] for x in res[a])))))
        base = res[arms[0]]
        for a in arms[1:]:
            n = min(len(base), len(res[a]))
            if n:
                d = [res[a][i]['first'] - base[i]['first'] for i in range(n)]
                print('paired (arm %s minus arm %s) over %d rounds: median %+.0f ms, mean %+.0f ms' % (a, arms[0], n, med(d), statistics.mean(d)))


main()
