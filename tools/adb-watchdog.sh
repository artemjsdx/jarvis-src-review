#!/data/data/com.termux/files/usr/bin/bash
# Держит ADB-соединение с телефоном: каждые 15 с проверяет и переподключает (порт connect не меняется,
# пока включена беспроводная отладка; повторное сопряжение не нужно, ключ уже авторизован).
# usage: tools/adb-watchdog.sh HOST:PORT        лог: $TMPDIR/adb-watchdog.log
D="${1:-10.61.19.159:38569}"
export ANDROID_ADB_SERVER_PORT="${ANDROID_ADB_SERVER_PORT:-5038}"
LOG="$TMPDIR/adb-watchdog.log"
while true; do
  if ! timeout 10 adb devices 2>/dev/null | grep -q "^$D[[:space:]]*device"; then
    echo "$(date +%H:%M:%S) нет связи, переподключаю" >> "$LOG"
    timeout 25 adb disconnect "$D" >/dev/null 2>&1
    timeout 30 adb connect "$D" >> "$LOG" 2>&1
  fi
  sleep 15
done
