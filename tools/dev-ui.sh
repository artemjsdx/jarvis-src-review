#!/data/data/com.termux/files/usr/bin/bash
# Compact UI dump of the real device, ONLY while app.jarvis is in focus.
# usage: tools/dev-ui.sh          -> lines: [x,y center] class text|desc  (clickable marked *)
set -u
D="${JV_DEVICE:-10.61.19.159:35933}"
export ANDROID_ADB_SERVER_PORT="${ANDROID_ADB_SERVER_PORT:-5038}"
PKG="${JV_PKG:-app.jarvis}"
f="$(timeout 15 adb -s "$D" shell 'dumpsys window | grep mCurrentFocus' 2>/dev/null | tr -d '\r')"
case "$f" in *" $PKG/"*) ;; *) echo "SKIP: focus is not $PKG ($f)"; exit 3;; esac
timeout 40 adb -s "$D" shell 'uiautomator dump --compressed /data/local/tmp/jv_ui.xml >/dev/null 2>&1; cat /data/local/tmp/jv_ui.xml; rm -f /data/local/tmp/jv_ui.xml' 2>/dev/null | python3 -c '
import sys, re, xml.etree.ElementTree as ET
data = sys.stdin.read()
i = data.find("<?xml")
if i < 0: print("no dump"); sys.exit(1)
root = ET.fromstring(data[i:])
for n in root.iter("node"):
    t, d, c = n.get("text", ""), n.get("content-desc", ""), n.get("clickable") == "true"
    if not (t or d or c): continue
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    if not m: continue
    x1, y1, x2, y2 = map(int, m.groups())
    print("%s[%4d,%4d] %-22s %s" % ("*" if c else " ", (x1 + x2) // 2, (y1 + y2) // 2, n.get("class", "").split(".")[-1][:22], (t or d)[:70].replace("\n", " ")))
'
