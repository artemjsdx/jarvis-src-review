#!/usr/bin/env python3
"""One line over the perf_frames results of a pass (JSON lines written by emulator-smoke.sh): frames, median jank %, worst p90.
  frame-summary.py <frames.jsonl>"""
import json
import statistics
import sys

rows = []
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    line = line.strip()
    if not line.startswith("{"):
        continue
    try:
        d = json.loads(line)
    except ValueError:
        continue
    if d.get("frames", 0) > 0:
        rows.append(d)

if not rows:
    print("no frames measured")
else:
    frames = sum(r["frames"] for r in rows)
    jank = statistics.median(r["jankPct"] for r in rows)
    p90 = max(r["p90Ms"] for r in rows)
    print("%d transitions, %d frames, median jank %.1f%%, worst p90 %.1f ms" % (len(rows), frames, jank, p90))
