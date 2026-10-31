#!/usr/bin/env python3
"""P-09 probe: how long does Edge take / how much audio does it return for punctuation-only and dotted text
(what the app sent before SpeechText.clean)? One connection, sequential requests. Also --idle SECS probes idle timeout."""
import asyncio, json, sys, time
from edge_reuse import speak, connect

CASES = ['...', '…', '. . .', 'Подожди...', 'Подожди.', 'Ну... не знаю', 'Ну, не знаю', '!!!', '?!', '— — —', 'Хорошо.']

async def main():
    ws, c = await connect()
    print('connect_ms', round(c))
    for t in CASES:
        try:
            f, tot, nb = await speak(ws, t, send_config=True)
            print(json.dumps(dict(text=t, first_ms=None if f is None else round(f), total_ms=round(tot), audio_bytes=nb), ensure_ascii=False), flush=True)
        except Exception as e:
            print(json.dumps(dict(text=t, err=type(e).__name__ + ' ' + str(e)[:80]), ensure_ascii=False), flush=True)
            ws, _ = await connect()
    await ws.close()
asyncio.run(main())
