#!/data/data/com.termux/files/usr/bin/bash
# Serialize use of the phone's SCREEN between agents (one foreground app at a time).
# usage: bash tools/with-screen.sh <command...>     e.g.  bash tools/with-screen.sh bash tools/dev-shot.sh home --raise
# Holds an exclusive lock while <command> runs (waits up to 10 min). Keep the critical section short (seconds, not minutes).
exec python3 - "$@" <<'PY'
import fcntl, os, subprocess, sys, time
lock = open(os.path.join(os.environ.get("TMPDIR", "/tmp"), "jarvis-screen.lock"), "w")
t0 = time.time()
while True:
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB); break
    except OSError:
        if time.time() - t0 > 600: print("with-screen: lock timeout", file=sys.stderr); sys.exit(75)
        time.sleep(0.5)
sys.exit(subprocess.call(sys.argv[1:]))
PY
