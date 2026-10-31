#!/usr/bin/env python3
"""Waits until the emulator CPU is quiet. The software GPU makes the first frames after a navigation slow, and a screenshot taken
during that burst shows half-drawn screens. Samples /proc/stat over adb: returns when --quiet consecutive samples are below --busy %.
  wait-idle.py [--max 30] [--busy 25] [--quiet 2]
Prints one line with the last measured busy percentage. Never fails the caller: the result is informational."""
import argparse
import subprocess
import time


def cpu_sample():
    out = subprocess.run(["adb", "shell", "cat", "/proc/stat"], capture_output=True, text=True, timeout=20).stdout
    vals = [int(x) for x in out.splitlines()[0].split()[1:9]]
    return sum(vals), vals[3] + vals[4]  # total jiffies, idle + iowait


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max", type=float, default=30.0, help="seconds to wait at most")
    ap.add_argument("--busy", type=float, default=25.0, help="busy percent that counts as quiet")
    ap.add_argument("--quiet", type=int, default=2, help="consecutive quiet samples needed")
    a = ap.parse_args()
    end = time.time() + a.max
    busy, run = 100.0, 0
    prev = cpu_sample()
    while time.time() < end:
        time.sleep(1.5)
        cur = cpu_sample()
        total, idle = cur[0] - prev[0], cur[1] - prev[1]
        prev = cur
        busy = 100.0 * (1 - idle / total) if total > 0 else 100.0
        run = run + 1 if busy < a.busy else 0
        if run >= a.quiet:
            break
    print("cpu busy %.0f%% (%s)" % (busy, "quiet" if run >= a.quiet else "still busy after %.0f s" % a.max))


if __name__ == "__main__":
    main()
