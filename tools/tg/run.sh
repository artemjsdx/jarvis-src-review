#!/data/data/com.termux/files/usr/bin/bash
# Keeps ONE long-lived Telegram MCP server running in ~/tg-mcp (upstream advises a single process: many
# parallel sessions get accounts flagged). Restarts after a crash with backoff (2 s doubling to 60 s).
#   run.sh start | stop | restart | status | helper-start | helper-stop | _loop | _helper
set -u
TG_HOME="${TG_HOME:-$HOME/tg-mcp}"
BIN="$TG_HOME/bin"
VENV="$TG_HOME/venv"
SRC="$TG_HOME/src"
ENVF="$TG_HOME/.env"
LOG="$TG_HOME/server.log"
PIDF="$TG_HOME/run.pid"
CHILDF="$TG_HOME/server.child"
HLOG="$TG_HOME/helper.log"
HPIDF="$TG_HOME/helper.pid"

alive() { [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null; }

kill_pidfile() {
  if alive "$1"; then kill "$(cat "$1")" 2>/dev/null; fi
  rm -f "$1"
}

has_session() {
  [ -f "$ENVF" ] && grep -Eq '^TELEGRAM_SESSION_STRING[A-Z0-9_]*=.+' "$ENVF"
}

# Foreground loop: started detached by start(). Writes its own pid; the child server pid goes to CHILDF.
loop() {
  echo $$ > "$PIDF"
  trap 'kill_pidfile "$CHILDF"; rm -f "$PIDF"; exit 0' TERM INT
  backoff=2
  while :; do
    if ! has_session; then
      echo "$(date '+%F %T') waiting: no account in .env" >> "$LOG"
      sleep 10 & wait $!
      continue
    fi
    started=$(date +%s)
    (
      set -a
      . "$ENVF"
      set +a
      exec "$VENV/bin/python" "$SRC/main.py"
    ) >> "$LOG" 2>&1 &
    child=$!
    echo "$child" > "$CHILDF"
    echo "$(date '+%F %T') server started pid $child" >> "$LOG"
    wait "$child"
    rc=$?
    rm -f "$CHILDF"
    ran=$(( $(date +%s) - started ))
    [ "$ran" -ge 60 ] && backoff=2
    echo "$(date '+%F %T') server exited rc=$rc after ${ran}s; retry in ${backoff}s" >> "$LOG"
    sleep "$backoff" & wait $!
    backoff=$(( backoff * 2 > 60 ? 60 : backoff * 2 ))
  done
}

helper_loop() {
  exec "$VENV/bin/python" "$BIN/login.py" >> "$HLOG" 2>&1
}

start() {
  if alive "$PIDF"; then echo "server: already running (pid $(cat "$PIDF"))"; return 0; fi
  rm -f "$PIDF"
  nohup setsid "$0" _loop >> "$LOG" 2>&1 < /dev/null &
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    [ -f "$PIDF" ] && break
    sleep 0.3
  done
  echo "server: started (loop pid $(cat "$PIDF" 2>/dev/null || echo ?))"
}

stop() {
  kill_pidfile "$PIDF"
  kill_pidfile "$CHILDF"
  echo "server: stopped"
}

helper_start() {
  if alive "$HPIDF"; then echo "helper: already running (pid $(cat "$HPIDF"))"; return 0; fi
  rm -f "$HPIDF"
  nohup "$0" _helper >> "$HLOG" 2>&1 < /dev/null &
  echo $! > "$HPIDF"
  sleep 1
  echo "helper: started (pid $(cat "$HPIDF"), 127.0.0.1:8766)"
}

helper_stop() {
  kill_pidfile "$HPIDF"
  echo "helper: stopped"
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop; sleep 1; start ;;
  status) "$VENV/bin/python" "$BIN/status.py" ;;
  helper-start) helper_start ;;
  helper-stop) helper_stop ;;
  _loop) loop ;;
  _helper) helper_loop ;;
  *) echo "usage: run.sh start|stop|restart|status|helper-start|helper-stop" >&2; exit 2 ;;
esac
