#!/usr/bin/env python3
"""Upload throughput: POST random bytes of growing size to a Cloudflare speed endpoint (accepts any body, discards it).
Falls back to a Groq endpoint with an invalid body (401/400 comes back after the whole body is read) if unreachable."""
import json, os, ssl, socket, sys, time

def post(host, path, size, extra=''):
    ctx = ssl.create_default_context()
    s = socket.create_connection((host, 443), timeout=20)
    ss = ctx.wrap_socket(s, server_hostname=host)
    body = os.urandom(size)
    head = ('POST %s HTTP/1.1\r\nHost: %s\r\nContent-Type: application/octet-stream\r\nContent-Length: %d\r\n%sConnection: close\r\n\r\n' % (path, host, size, extra)).encode()
    t = time.perf_counter()
    ss.sendall(head + body)
    t_sent = time.perf_counter() - t
    ss.settimeout(60)
    d = ss.recv(200)
    t_resp = time.perf_counter() - t
    ss.close()
    return t_sent, t_resp, d.split(b'\r\n', 1)[0].decode('latin1')

def main():
    targets = [('speed.cloudflare.com', '/__up'), ('api.groq.com', '/openai/v1/audio/transcriptions')]
    for host, path in targets:
        try:
            res = []
            for kb in (16, 64, 128, 256):
                ts, tr, st = post(host, path, kb * 1024)
                res.append((kb, ts, tr, st))
                print('%-24s %4d KB  sent %.2fs  response after %.2fs  => %.0f KB/s (until response)  [%s]' % (host, kb, ts, tr, kb / tr, st))
                if tr > 25: break
            print(json.dumps(res))
            return
        except Exception as e:
            print(host, 'failed:', type(e).__name__, e)

main()
