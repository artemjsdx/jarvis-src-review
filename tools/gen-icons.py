#!/usr/bin/env python3
"""Generate Android VectorDrawables from Lucide (ISC) SVGs: one consistent stroke-icon system.
Run: python3 tools/gen-icons.py   (downloads into .cache/icons, writes app/res/drawable/ic_*.xml)"""
import os, re, sys, urllib.request, xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(ROOT, ".cache", "icons")
OUT = os.path.join(ROOT, "app", "res", "drawable")
os.makedirs(CACHE, exist_ok=True)
os.makedirs(OUT, exist_ok=True)
BASE = "https://raw.githubusercontent.com/lucide-icons/lucide/main/icons/%s.svg"

# android name -> lucide name
ICONS = {
 "mic":"mic","mic_off":"mic-off","send":"send","paperclip":"paperclip","stop":"square","play":"play","pause":"pause",
 "skip_next":"skip-forward","skip_prev":"skip-back","music":"music","folder":"folder","folder_open":"folder-open","file":"file",
 "file_text":"file-text","file_audio":"file-music","terminal":"terminal","settings":"settings","sliders":"sliders-horizontal",
 "log":"scroll-text","voice":"audio-lines","home":"house","chat":"message-square","bot":"bot","plus":"plus","close":"x",
 "chevron_down":"chevron-down","chevron_up":"chevron-up","chevron_left":"chevron-left","chevron_right":"chevron-right",
 "check":"check","copy":"copy","trash":"trash","eraser":"eraser","download":"download","upload":"upload","refresh":"refresh-cw","wifi":"wifi",
 "wifi_off":"wifi-off","battery":"battery-charging","key":"key-round","globe":"globe","shield":"shield-check","info":"info",
 "warning":"triangle-alert","alert":"circle-alert","more":"ellipsis","search":"search","edit":"pencil","save":"save",
 "eye":"eye","eye_off":"eye-off","share":"share-2","volume":"volume-2","volume_off":"volume-off","headphones":"headphones",
 "sparkles":"sparkles","zap":"zap","clock":"clock","history":"list-restart","link":"link","drive":"hard-drive","layers":"layers",
 "cpu":"cpu","activity":"activity","brain":"brain","ok":"circle-check","fail":"circle-x","loader":"loader-circle","heart":"heart",
 "star":"star","moon":"moon","sun":"sun","palette":"palette","language":"languages","ear":"ear","radio":"radio","waves":"audio-waveform",
 "phone":"smartphone","plug":"plug","usb":"usb","package":"package","archive":"archive","wrench":"wrench","list":"list",
 "arrow_up":"arrow-up","arrow_down":"arrow-down","external":"external-link","lock":"lock","bell":"bell","folder_plus":"folder-plus",
 "file_plus":"file-plus","rotate":"rotate-ccw","cloud":"cloud","server":"server","timer":"timer","gauge":"gauge","bug":"bug",
 "code":"code","image":"image","video":"video","wand":"wand-sparkles","flask":"flask-conical","bolt":"bolt",
 "pen_line":"pen-line","images":"images","messages_square":"messages-square","speaker":"speaker","puzzle":"puzzle",
 "rotate_cw":"rotate-cw","book_open":"book-open","clapperboard":"clapperboard","mic_vocal":"mic-vocal",
}

# Plain music note is absent from current Lucide; geometry below is the classic Feather (MIT) note.
FALLBACK = {"music": '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/></svg>'}

def fetch(l):
    if l in FALLBACK: return FALLBACK[l]
    p = os.path.join(CACHE, l + ".svg")
    if not os.path.exists(p):
        try:
            data = urllib.request.urlopen(BASE % l, timeout=30).read()
        except Exception as e:
            return None
        open(p, "wb").write(data)
    return open(p, encoding="utf-8").read()

def f(x):
    x = float(x)
    return ("%g" % round(x, 4))

ARG_COUNT = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7}
NUMBER = re.compile(r"[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?")

def normalize_path(d):
    """Re-emits path data with every arc flag as its own token. SVG allows compact flags ('A3 3 0 1012 5' = flags 1,0 then x 12);
    the Android path parser reads '1012' as one number, drops the arc and the icon renders empty (ic_brain, ic_key ...)."""
    out, i, cmd = [], 0, None
    while True:
        while i < len(d) and d[i] in " ,\t\r\n": i += 1
        if i >= len(d): break
        if d[i].isalpha():
            cmd = d[i]; i += 1
            out.append(cmd)
            if cmd in "Zz": continue
        if cmd is None or cmd in "Zz": raise ValueError("bad path data: " + d[:40])
        group = []
        for k in range(ARG_COUNT[cmd.upper()]):
            while i < len(d) and d[i] in " ,\t\r\n": i += 1
            if cmd.upper() == "A" and k in (3, 4):
                group.append(d[i]); i += 1
            else:
                m = NUMBER.match(d, i)
                if not m: raise ValueError("bad number in path data: " + d[:40])
                group.append(m.group(0)); i = m.end()
        out.append(" ".join(group))
    return " ".join(out)

def to_path(el):
    tag = el.tag.split("}")[-1]
    a = el.attrib
    if tag == "path": return normalize_path(a["d"])
    if tag == "circle":
        cx, cy, r = float(a["cx"]), float(a["cy"]), float(a["r"])
        return "M%s,%sa%s,%s 0 1,0 %s,0a%s,%s 0 1,0 %s,0" % (f(cx - r), f(cy), f(r), f(r), f(2 * r), f(r), f(r), f(-2 * r))
    if tag == "ellipse":
        cx, cy, rx, ry = float(a["cx"]), float(a["cy"]), float(a["rx"]), float(a["ry"])
        return "M%s,%sa%s,%s 0 1,0 %s,0a%s,%s 0 1,0 %s,0" % (f(cx - rx), f(cy), f(rx), f(ry), f(2 * rx), f(rx), f(ry), f(-2 * rx))
    if tag == "line":
        return "M%s,%sL%s,%s" % (f(a["x1"]), f(a["y1"]), f(a["x2"]), f(a["y2"]))
    if tag in ("polyline", "polygon"):
        pts = re.findall(r"-?[\d.]+", a["points"])
        pairs = list(zip(pts[0::2], pts[1::2]))
        d = "M" + "L".join("%s,%s" % (f(x), f(y)) for x, y in pairs)
        return d + ("Z" if tag == "polygon" else "")
    if tag == "rect":
        x, y, w, h = (float(a.get(k, 0)) for k in ("x", "y", "width", "height"))
        rx = float(a.get("rx", a.get("ry", 0))); ry = float(a.get("ry", rx))
        if rx == 0 and ry == 0:
            return "M%s,%sH%sV%sH%sZ" % (f(x), f(y), f(x + w), f(y + h), f(x))
        return ("M%s,%sH%sa%s,%s 0 0 1 %s,%sV%sa%s,%s 0 0 1 %s,%sH%sa%s,%s 0 0 1 %s,%sV%sa%s,%s 0 0 1 %s,%sZ" %
                (f(x + rx), f(y), f(x + w - rx), f(rx), f(ry), f(rx), f(ry), f(y + h - ry), f(rx), f(ry), f(-rx), f(ry),
                 f(x + rx), f(rx), f(ry), f(-rx), f(-ry), f(y + ry), f(rx), f(ry), f(rx), f(-ry)))
    return None

def convert(svg):
    """One entry per SVG element: concatenating them into a single pathData would turn a leading relative 'm' into a
    move relative to the previous subpath's end (shifts the shape out of the viewport)."""
    root = ET.fromstring(svg)
    parts = []
    for el in root.iter():
        if el is root: continue
        p = to_path(el)
        if p: parts.append(p.strip())
    return parts

HEAD = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/gen-icons.py from Lucide (ISC License, see THIRD_PARTY.md). Do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
"""
PATH = """    <path android:pathData="%s"
        android:fillColor="#00000000" android:strokeColor="#FFFFFFFF" android:strokeWidth="1.8"
        android:strokeLineCap="round" android:strokeLineJoin="round" />
"""

missing = []
for name, lucide in sorted(ICONS.items()):
    svg = fetch(lucide)
    if svg is None:
        missing.append((name, lucide)); continue
    d = convert(svg)
    if not d:
        missing.append((name, lucide + " (empty)")); continue
    open(os.path.join(OUT, "ic_%s.xml" % name), "w", encoding="utf-8").write(HEAD + "".join(PATH % x.replace('"', "'") for x in d) + "</vector>\n")
print("generated %d icons" % (len(ICONS) - len(missing)))
if missing:
    print("MISSING:", missing); sys.exit(1)
