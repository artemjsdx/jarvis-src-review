#!/usr/bin/env bash
# Runs INSIDE a booted Android emulator (see .github/workflows/ci.yml). Installs the x86_64 bridge APK (glass off in its provisioning: the
# software GPU cannot draw the blur), opens the app and drives it through the MCP bridge the way the lead does on the owner's phone:
# health, jv-check, the smoke scenarios (seeded chat screens included), the optional live LLM round trip (OPENROUTER_KEY), every screen with
# glass off, the headless host, and last every screen with ui.glass=full with frame jank per pass. Everything lands in $OUT; summary.txt is
# the first file to read. Only a dead bridge or a crash fails the job.
#   emulator-smoke.sh <apk> [out-dir]
set -uo pipefail
cd "$(dirname "$0")/../.."
APK="${1:?usage: emulator-smoke.sh <apk> [out]}"; OUT="${2:-build/emu}"; PKG=app.jarvis
SCREENS="home agent files terminal studio log settings"
mkdir -p "$OUT/shots"; : > "$OUT/summary.txt"
note() { echo "$*" | tee -a "$OUT/summary.txt"; }
FAIL=0

adb wait-for-device
for i in $(seq 1 80); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 3; done
note "device: $(adb shell getprop ro.product.model | tr -d '\r') | android $(adb shell getprop ro.build.version.release | tr -d '\r') (api $(adb shell getprop ro.build.version.sdk | tr -d '\r')) | abi $(adb shell getprop ro.product.cpu.abi | tr -d '\r') | kvm: $([ -e /dev/kvm ] && echo yes || echo NO)"
adb shell input keyevent 82 >/dev/null 2>&1 || true
# the software GPU stalls the system UI: crash dialogs of the app stay hidden
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1 || true

calm() {   # closes system dialogs; a "not responding" window is only reported: BACK on it would choose "Close app"
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  if adb shell dumpsys window windows 2>/dev/null | tr -d '\r' | grep -q "isn't responding"; then echo "ANR window on screen"; fi
}

health() { curl -sf -m 4 http://127.0.0.1:8767/health >/dev/null 2>&1; }
ensure_app() {   # restarts the app when the bridge is gone (an ANR "Close app" ends the process)
  health && return 0
  adb shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  for i in $(seq 1 20); do health && { note "app restarted: $1"; return 0; }; sleep 2; done
  return 1
}

adb install -r -g "$APK" > "$OUT/install.log" 2>&1
note "install: $(tail -n1 "$OUT/install.log" | tr -d '\r')"
adb shell pm list packages "$PKG" | tr -d '\r' >> "$OUT/summary.txt"

# the bridge token is the one baked into the APK from the provisioning secret
K="$HOME/.jarvis-keys"
python3 - "$K" <<'PY'
import json, os, sys
k = sys.argv[1]
tok = json.load(open(os.path.join(k, "provisioned-bridge.json")))["secrets"]["mcp.token"]
p = os.path.join(k, "mcp-app.jarvis.json")
json.dump({"token": tok, "port": 8767}, open(p, "w")); os.chmod(p, 0o600)
PY

adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" > "$OUT/start.log" 2>&1
note "start: $(grep -E 'Status|TotalTime' "$OUT/start.log" | tr -d '\r' | tr '\n' ' ')"
adb forward tcp:8767 tcp:8767 >/dev/null
OK=0
for i in $(seq 1 50); do
  if curl -sf -m 4 http://127.0.0.1:8767/health > "$OUT/health.json" 2>/dev/null; then OK=1; break; fi
  sleep 3
done
note "bridge health: ok=$OK $(cat "$OUT/health.json" 2>/dev/null)"

# every screen once on the live window (jv screenshot, adb screencap, view tree), then frame jank of short transitions
shoot_pass() {
  local P="$OUT/$1"; mkdir -p "$P"
  for s in $SCREENS; do
    ensure_app "$1/$s" || { note "$1: bridge gone at $s, pass stopped"; return 1; }
    python3 tools/jv.py ui_navigate "{\"screen\":\"$s\"}" > "$P/nav_$s.txt" 2>&1
    calm; python3 tools/ci/wait-idle.py --max 12 >> "$P/settle.txt" 2>&1
    python3 tools/jv.py --save "$P/live_$s.png" ui_screenshot '{"host":"live"}' > "$P/shot_$s.txt" 2>&1 || note "ui_screenshot($1/$s): $(head -c 200 "$P/shot_$s.txt" | tr '\n' ' ')"
    adb exec-out screencap -p > "$P/adb_$s.png" 2>/dev/null
    python3 tools/jv.py ui_tree '{"maxDepth":7}' > "$P/tree_$s.json" 2>&1 || true
  done
  : > "$P/frames.jsonl"
  for s in settings home agent home; do
    ensure_app "$1/frames" || break
    python3 tools/jv.py ui_navigate "{\"screen\":\"$s\"}" > /dev/null 2>&1
    python3 tools/jv.py perf_frames '{"durationMs":1500}' >> "$P/frames.jsonl" 2>&1
  done
  note "$1: $(python3 tools/ci/frame-summary.py "$P/frames.jsonl")"
}

if [ "$OK" = 1 ]; then
  python3 tools/jv-check.py --no-llm > "$OUT/jv-check.txt" 2>&1; note "jv-check exit $?: $(grep -cE '^\s*(FAIL)' "$OUT/jv-check.txt") FAIL lines"
  python3 tools/mcp-smoke.py --pkg "$PKG" --report-dir "$OUT/smoke" > "$OUT/smoke.txt" 2>&1; note "mcp-smoke exit $?: $(tail -n 2 "$OUT/smoke.txt" | tr '\n' ' ')"

  if [ -n "${OPENROUTER_KEY:-}" ]; then
    ensure_app "llm"; python3 tools/ci/llm-roundtrip.py "$OUT" > "$OUT/llm.log" 2>&1; note "live LLM round trip exit $?: $(tail -n 1 "$OUT/llm.log")"
  else
    note "live LLM round trip: skipped (no OPENROUTER_KEY secret)"
  fi

  shoot_pass glass-off

  python3 tools/jv.py ui_host_open '{"kind":"headless"}' > "$OUT/headless_open.txt" 2>&1; note "headless host: $(head -c 240 "$OUT/headless_open.txt" | tr '\n' ' ')"
  for s in home agent files terminal; do
    python3 tools/jv.py ui_navigate "{\"screen\":\"$s\",\"host\":\"headless\"}" > /dev/null 2>&1
    python3 tools/jv.py --save "$OUT/shots/headless_$s.png" ui_screenshot '{"host":"headless"}' > "$OUT/hshot_$s.txt" 2>&1 || note "headless screenshot($s): $(head -c 200 "$OUT/hshot_$s.txt" | tr '\n' ' ')"
  done
  python3 tools/jv.py journal_tail '{"n":300,"level":"WARN"}' > "$OUT/journal_warn.json" 2>&1
  python3 tools/jv.py app_health > "$OUT/app_health.json" 2>&1

  # last: the full glass on the software GPU; a stalled frame can end in an ANR and the app is restarted for the next run
  python3 tools/jv.py settings_set '{"key":"ui.glass","value":"full"}' > "$OUT/glass-full.set" 2>&1
  shoot_pass glass-full || note "glass-full: pass stopped (the app stalled on the software GPU)"
  python3 tools/jv.py settings_set '{"key":"ui.glass","value":"off"}' > /dev/null 2>&1
fi

adb logcat -d > "$OUT/logcat.txt" 2>&1
# only crashes and ANRs of OUR process count: the emulator image's own apps (Gmail, SystemUI...) crash now and then, that is not a Jarvis bug
CR=$(grep -A2 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -c "Process: app\.jarvis" || true)
ANR=$(grep -c "ANR in app\.jarvis" "$OUT/logcat.txt" || true)
OTHER=$(grep -c "FATAL EXCEPTION" "$OUT/logcat.txt" || true)
note "FATAL EXCEPTIONs of app.jarvis in logcat: $CR (ANR: $ANR; crashes of other apps of the emulator image: $((OTHER - CR)))"
[ "${ANR:-0}" = 0 ] || CR=$((CR + ANR))
{ [ "$OK" = 1 ] && [ "${CR:-0}" = 0 ]; } || FAIL=1
note "result: $([ $FAIL = 0 ] && echo PASS || echo FAIL)"
exit $FAIL
