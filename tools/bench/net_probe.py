#!/usr/bin/env python3
"""Network probe for the voice chain: per host DNS, TCP connect (= RTT), TLS handshake, time to first byte of a tiny HTTPS
request (fresh connection), then the same request on the already open connection (warm RTT). Plus upload throughput.
Usage: python3 tools/bench/net_probe.py [N=5]   (no keys used)"""
import json, socket, ssl, statistics, sys, time

HOSTS = [('openrouter.ai', '/api/v1/models'), ('api.groq.com', '/openai/v1/models'), ('api.elevenlabs.io', '/v1/models'),
         ('speech.platform.bing.com', '/'), ('www.google.com', '/'), ('api.deepseek.com', '/'),
         ('api.inceptionlabs.ai', '/')]


def probe(host, path):
    r = {}
    t = time.perf_counter()
    ip = socket.getaddrinfo(host, 443, socket.AF_INET, socket.SOCK_STREAM)[0][4][0]
    r['dns'] = (time.perf_counter() - t) * 1000
    t = time.perf_counter()
    s = socket.create_connection((ip, 443), timeout=15)
    r['tcp'] = (time.perf_counter() - t) * 1000
    ctx = ssl.create_default_context()
    t = time.perf_counter()
    ss = ctx.wrap_socket(s, server_hostname=host)
    r['tls'] = (time.perf_counter() - t) * 1000
    req = ('GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: jarvis-bench\r\nAccept: */*\r\nConnection: keep-alive\r\n\r\n' % (path, host)).encode()
    for key in ('ttfb_fresh', 'ttfb_warm'):
        t = time.perf_counter()
        ss.sendall(req)
        ss.settimeout(15)
        first = ss.recv(1)
        r[key] = (time.perf_counter() - t) * 1000
        r['status'] = None
        buf = first
        ss.settimeout(0.6)
        try:
            while True:
                d = ss.recv(65536)
                if not d: break
                buf += d
        except Exception:
            pass
        line = buf.split(b'\r\n', 1)[0].decode('latin1')
        r['status'] = line[9:12] if line.startswith('HTTP/') else line[:20]
    ss.close()
    return r


def med(xs):
    return statistics.median(xs)


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 5
    print('%-26s %6s %6s %6s %9s %9s  status' % ('host', 'dns', 'tcp', 'tls', 'ttfb_new', 'ttfb_warm'))
    out = {}
    for host, path in HOSTS:
        rows = []
        for _ in range(n):
            try:
                rows.append(probe(host, path))
            except Exception as e:
                rows.append({'err': type(e).__name__})
        ok = [r for r in rows if 'err' not in r]
        if not ok:
            print('%-26s FAILED %s' % (host, rows[0].get('err')))
            out[host] = {'error': rows[0].get('err')}
            continue
        m = {k: med([r[k] for r in ok]) for k in ('dns', 'tcp', 'tls', 'ttfb_fresh', 'ttfb_warm')}
        m['status'] = ok[0]['status']; m['ok'] = '%d/%d' % (len(ok), n)
        out[host] = m
        print('%-26s %6.0f %6.0f %6.0f %9.0f %9.0f  %s (%s)' % (host, m['dns'], m['tcp'], m['tls'], m['ttfb_fresh'], m['ttfb_warm'], m['status'], m['ok']))
    print(json.dumps(out))


if __name__ == '__main__':
    main()
