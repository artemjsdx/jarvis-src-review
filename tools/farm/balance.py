#!/usr/bin/env python3
"""balance.py [--watch SECONDS]  OpenRouter balance (retries on network blips) + what the farm supervisor counted for running agents."""
import json, os, sys, time, urllib.request

FARM = "/data/data/com.termux/files/usr/var/lib/proot-distro/containers/ubuntu/rootfs/home/claude/agentfarm"


def key():
    return json.load(open(os.path.expanduser("~/.local/share/opencode/auth.json")))["openrouter"]["key"]


def left(tries=6):
    for i in range(tries):
        try:
            r = urllib.request.Request("https://openrouter.ai/api/v1/credits", headers={"Authorization": "Bearer " + key(), "User-Agent": "curl/8.4.0"})
            with urllib.request.urlopen(r, timeout=15) as x:
                c = json.load(x)["data"]
            return c["total_credits"] - c["total_usage"]
        except Exception:
            time.sleep(2 + i * 2)
    return None


def agents():
    try:
        t = json.load(open(FARM + "/supervisor.json"))["tasks"]
    except Exception:
        return 0.0, {}
    return sum(x.get("spent", 0) for x in t.values()), {n: round(x.get("spent", 0), 2) for n, x in t.items() if x["state"] == "running"}


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "--watch":
        l0 = left(); s0, _ = agents(); t0 = time.time(); time.sleep(float(sys.argv[2]))
        l1 = left(); s1, run = agents(); dt = (time.time() - t0) / 60
        if l0 is None or l1 is None:
            print("balance unavailable (network)"); sys.exit(1)
        print("balance %.2f -> %.2f in %.1f min: total burn %.3f USD/min | counted agents %.3f USD/min | rest (lead session etc.) %.3f USD/min | running: %s"
              % (l0, l1, dt, (l0 - l1) / dt, (s1 - s0) / dt, ((l0 - l1) - (s1 - s0)) / dt, run))
    else:
        l = left(); s, run = agents()
        print("balance left: %s | running agents: %s" % ("?" if l is None else "$%.2f" % l, run))
