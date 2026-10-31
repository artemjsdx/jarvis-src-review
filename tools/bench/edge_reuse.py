#!/usr/bin/env python3
"""Edge TTS: fresh connection per phrase vs one reused WebSocket (sequential requests), plus idle-timeout probe.

Usage: python3 tools/bench/edge_reuse.py [N=10] [--idle 5,15,30,60]
Output: one JSON line per request and a summary table. No secrets involved (public client token).
"""
import asyncio, json, statistics, sys, time
import websockets
from edge_common import url, headers, config_msg, ssml_msg

PHRASES = ['Слушаю.', 'Хорошо, включаю.', 'Сейчас посмотрю.', 'Готово.', 'Сегодня вторник.', 'Не нашёл такой файл.',
           'Открываю настройки.', 'Громкость сорок процентов.', 'Поставил таймер.', 'Доброе утро!',
           'Это заняло две секунды.', 'Понял вас.']


async def speak(ws, text, send_config=True):
    """Returns (first_audio_ms, total_ms, audio_bytes, ok). Time counted from the first send."""
    t0 = time.perf_counter()
    if send_config:
        await ws.send(config_msg())
    await ws.send(ssml_msg(text))
    first = None; nbytes = 0
    while True:
        m = await asyncio.wait_for(ws.recv(), 20)
        if isinstance(m, bytes):
            hl = int.from_bytes(m[:2], 'big')
            if b'Path:audio\r\n' in m[2:2 + hl] and len(m) > 2 + hl:
                if first is None:
                    first = (time.perf_counter() - t0) * 1000
                nbytes += len(m) - 2 - hl
        elif 'Path:turn.end' in m:
            return first, (time.perf_counter() - t0) * 1000, nbytes


async def connect():
    t0 = time.perf_counter()
    ws = await websockets.connect(url(), additional_headers=headers(), max_size=None, compression=None, open_timeout=15,
                                  ping_interval=None)
    return ws, (time.perf_counter() - t0) * 1000


def stats(xs):
    xs = [x for x in xs if x is not None]
    return 'n=%d med=%.0f min=%.0f max=%.0f' % (len(xs), statistics.median(xs), min(xs), max(xs)) if xs else 'n=0'


async def fresh(n):
    rows = []
    for i in range(n):
        ws, conn = await connect()
        try:
            f, tot, nb = await speak(ws, PHRASES[i % len(PHRASES)])
        finally:
            await ws.close()
        rows.append(dict(mode='fresh', i=i, connect_ms=round(conn), first_ms=round(f), total_ms=round(tot), bytes=nb,
                         to_audio_ms=round(conn + f)))
        print(json.dumps(rows[-1], ensure_ascii=False), flush=True)
    return rows


async def reused(n, send_config):
    rows = []
    ws, conn = await connect()
    mode = 'reuse_cfg_each' if send_config else 'reuse_cfg_once'
    try:
        for i in range(n):
            f, tot, nb = await speak(ws, PHRASES[i % len(PHRASES)], send_config=(send_config or i == 0))
            rows.append(dict(mode=mode, i=i, connect_ms=round(conn) if i == 0 else 0, first_ms=round(f), total_ms=round(tot),
                             bytes=nb, to_audio_ms=round(f + (conn if i == 0 else 0))))
            print(json.dumps(rows[-1], ensure_ascii=False), flush=True)
    finally:
        await ws.close()
    return rows


async def idle_probe(gaps):
    """After one request, sit idle for gap seconds, then try again on the same connection. Reports survival."""
    out = []
    for gap in gaps:
        ws, _ = await connect()
        try:
            await speak(ws, 'Проверка.')
            await asyncio.sleep(gap)
            try:
                f, tot, nb = await speak(ws, 'Ещё раз.', send_config=False)
                r = dict(mode='idle', gap_s=gap, alive=True, first_ms=round(f))
            except Exception as e:
                r = dict(mode='idle', gap_s=gap, alive=False, err=type(e).__name__ + ' ' + str(e)[:80])
        except Exception as e:
            r = dict(mode='idle', gap_s=gap, alive=False, err='first request failed ' + str(e)[:80])
        finally:
            try: await ws.close()
            except Exception: pass
        print(json.dumps(r, ensure_ascii=False), flush=True)
        out.append(r)
    return out


async def main():
    args = sys.argv[1:]
    n = int(args[0]) if args and args[0].isdigit() else 10
    gaps = [5, 15, 30, 60]
    if '--idle' in args:
        gaps = [int(x) for x in args[args.index('--idle') + 1].split(',')]
    if '--only-idle' in args:
        await idle_probe(gaps)
        return
    a = await fresh(n)
    b = await reused(n, True)
    c = await reused(n, False)
    print('--- summary (ms) ---')
    print('fresh               to_audio', stats([r['to_audio_ms'] for r in a]), '| total', stats([r['total_ms'] for r in a]))
    print('reused cfg each     first   ', stats([r['first_ms'] for r in b[1:]]), '| total', stats([r['total_ms'] for r in b[1:]]), '| 1st', b[0]['to_audio_ms'])
    print('reused cfg once     first   ', stats([r['first_ms'] for r in c[1:]]), '| total', stats([r['total_ms'] for r in c[1:]]), '| 1st', c[0]['to_audio_ms'])
    print('fresh connect only  ', stats([r['connect_ms'] for r in a]))
    await idle_probe(gaps)

if __name__ == "__main__":
    asyncio.run(main())
