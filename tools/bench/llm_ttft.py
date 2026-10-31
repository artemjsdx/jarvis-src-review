#!/usr/bin/env python3
"""OpenRouter time-to-first-token for a DeepSeek Flash model: prompt-cache effect and tool-schema cost.
Scenarios (N calls each, sequential, one keep-alive session): A same 3 KB system prompt; B system prompt whose FIRST line changes;
C same prompt + 1.5 KB tool schema; D tool schema + changing first line. Max tokens 8. Key from ~/.local/share/opencode/auth.json, never printed.
Usage: python3 tools/bench/llm_ttft.py [model=deepseek/deepseek-v4.1-flash] [N=5]"""
import json, os, statistics, sys, time
import requests

MODEL = sys.argv[1] if len(sys.argv) > 1 else 'deepseek/deepseek-v4.1-flash'
N = int(sys.argv[2]) if len(sys.argv) > 2 else 5
URL = 'https://openrouter.ai/api/v1/chat/completions'
AUTH = os.environ.get('JV_OPENROUTER_AUTH') or next(p for p in (os.path.expanduser('~/.local/share/opencode/auth.json'),
        '/data/data/com.termux/files/home/.local/share/opencode/auth.json') if os.path.exists(p))
KEY = json.load(open(AUTH))['openrouter']['key']

BODY = ('Ты Джарвис, голосовой помощник на телефоне. Отвечай коротко, по-русски, без markdown. Если просьба требует действия, вызывай инструмент. '
        'Правила: 1) не выдумывай результаты; 2) числа пиши словами; 3) подтверждай действия одной фразой. ')
SYSTEM = (BODY * 12)[:3000]
TOOLS = [{'type': 'function', 'function': {'name': 'music_play', 'description': 'Включить музыку по названию или любимую песню пользователя; запускает локальный плеер.',
          'parameters': {'type': 'object', 'properties': {'query': {'type': 'string', 'description': 'Название трека, исполнитель или жанр'},
                                                        'shuffle': {'type': 'boolean', 'description': 'Перемешать очередь'}}, 'required': ['query']}}},
         {'type': 'function', 'function': {'name': 'timer_set', 'description': 'Поставить таймер или будильник на заданное время и показать уведомление по завершении.',
          'parameters': {'type': 'object', 'properties': {'seconds': {'type': 'integer', 'description': 'Через сколько секунд сработать'},
                                                        'label': {'type': 'string', 'description': 'Подпись таймера'}}, 'required': ['seconds']}}},
         {'type': 'function', 'function': {'name': 'note_create', 'description': 'Создать заметку с заголовком и текстом в папке документов.',
          'parameters': {'type': 'object', 'properties': {'title': {'type': 'string', 'description': 'Заголовок'}, 'text': {'type': 'string', 'description': 'Текст заметки'}},
                         'required': ['title', 'text']}}}]
TOOLS_JSON_LEN = len(json.dumps(TOOLS, ensure_ascii=False))


def call(sess, system, tools):
    body = {'model': MODEL, 'stream': True, 'max_tokens': 8, 'temperature': 0, 'reasoning': {'enabled': False},
            'stream_options': {'include_usage': True},
            'messages': [{'role': 'system', 'content': system}, {'role': 'user', 'content': 'Скажи одно слово: готово'}]}
    if tools:
        body['tools'] = TOOLS
    if os.environ.get('JV_OR_SORT'):
        body['provider'] = {'sort': os.environ['JV_OR_SORT']}      # e.g. latency / throughput: pins the routing preference
    t0 = time.perf_counter()
    r = sess.post(URL, json=body, headers={'Authorization': 'Bearer ' + KEY}, stream=True, timeout=60)
    t_head = time.perf_counter() - t0
    first = None; usage = None; prov = ''
    if r.status_code != 200:
        return {'err': 'HTTP %d %s' % (r.status_code, r.text[:80])}
    for line in r.iter_lines():
        if not line or not line.startswith(b'data: '):
            continue
        d = line[6:]
        if d == b'[DONE]':
            break
        try:
            j = json.loads(d)
        except Exception:
            continue
        prov = j.get('provider', prov)
        if j.get('usage'):
            usage = j['usage']
        for ch in j.get('choices', []):
            dl = ch.get('delta', {})
            if first is None and (dl.get('content') or dl.get('tool_calls')):
                first = time.perf_counter() - t0
    tot = time.perf_counter() - t0
    cached = None
    if usage:
        cached = (usage.get('prompt_tokens_details') or {}).get('cached_tokens')
    return {'head': t_head, 'first': first, 'total': tot, 'prompt_tokens': usage and usage.get('prompt_tokens'), 'cached': cached, 'provider': prov}


def main():
    sess = requests.Session()
    sess.get('https://openrouter.ai/api/v1/models', headers={'Authorization': 'Bearer ' + KEY}, timeout=30)   # warm the connection
    print('model %s, system %d chars, tools %d chars, N=%d' % (MODEL, len(SYSTEM), TOOLS_JSON_LEN, N))
    summary = {}
    scen = (('A same prefix', False, False), ('B first line changes', False, True), ('C same prefix + tools', True, False), ('D tools + first line changes', True, True))
    if os.environ.get('JV_ONLY'):
        scen = tuple(x for x in scen if x[0].startswith(os.environ['JV_ONLY']))
    for name, tools, vary in scen:
        rows = []
        tag = str(int(time.time()))
        for i in range(N):
            system = ('[%s-%d] ' % (tag, i) + SYSTEM) if vary else SYSTEM
            r = call(sess, system, tools)
            rows.append(r)
            if 'err' in r:
                print('  %-30s #%d %s' % (name, i, r['err']))
            else:
                print('  %-30s #%d headers %.2fs  first token %s  total %.2fs  prompt_tokens=%s cached=%s via %s' % (name, i, r['head'], '%.2fs' % r['first'] if r['first'] else '-', r['total'], r['prompt_tokens'], r['cached'], r['provider']), flush=True)
            time.sleep(1.0)
        ok = [r for r in rows if 'err' not in r and r['first']]
        if ok:
            f = [r['first'] for r in ok]
            summary[name] = {'median_first_token_s': round(statistics.median(f), 2), 'min': round(min(f), 2), 'max': round(max(f), 2),
                             'median_without_first_call': round(statistics.median(f[1:]), 2) if len(f) > 1 else None}
            print('  => %-28s first token median %.2fs (min %.2f, max %.2f); calls 2..N median %.2fs' % (name, statistics.median(f), min(f), max(f), statistics.median(f[1:]) if len(f) > 1 else 0))
    print(json.dumps(summary, ensure_ascii=False))

main()
