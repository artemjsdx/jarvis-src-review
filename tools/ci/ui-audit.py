#!/usr/bin/env python3
"""Read-only UI survey of a real installed Jarvis APK on an Android emulator.

Visits every registered SettingsScreen page, scrolling until repeated visible
hierarchies; takes frames/accessible UI trees and transition recordings.
Does not mutate Jarvis settings, delete data, or invoke network actions.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

GROUPS = {
    "a": ["llm", "context", "voice", "wake"],
    "b": ["sounds", "music", "triggers", "sandbox"],
    "c": ["adb", "background", "appearance", "advanced"],
    "d": ["diagnostics", "mcp", "mcpsrv", "telegram"]
}
TITLES = {
    "llm": "Языковая модель (LLM)",
    "context": "Контекст и промпт",
    "voice": "Голос и озвучка",
    "wake": "Обращение и распознавание",
    "sounds": "Звук и микрофон",
    "music": "Музыка",
    "triggers": "Фразы-триггеры",
    "sandbox": "Песочница и инструменты",
    "adb": "ADB по Wi-Fi",
    "background": "Фон и батарея",
    "appearance": "Внешний вид",
    "advanced": "Дополнительно",
    "diagnostics": "Диагностика",
    "mcp": "Мост для агента",
    "mcpsrv": "Внешние MCP-серверы",
    "telegram": "Telegram-аккаунты"
}
DOCK = [
    ("home", .22), ("agent", .41), ("files", .51),
    ("terminal", .62), ("studio", .72), ("log", .83)
]
group = sys.argv[1] if len(sys.argv) > 1 else "a"
if group not in GROUPS:
    raise SystemExit("Invalid group " + group)
root = Path("audit-output") / group
root.mkdir(parents=True, exist_ok=True)
results = {"group": group, "pages": {}, "dock": {}, "warnings": [], "events": [], "started": time.time(), "device": {}}
seq = 0

def adb(*args, timeout=35, binary=False, check=True):
    cmd = ["adb"] + list(args)
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if check and p.returncode:
            raise RuntimeError(f"adb {args[0]} failed [{p.returncode}]: {p.stderr[-600:].decode('utf-8','replace')}")
        return p.stdout if binary else p.stdout.decode("utf-8", "replace")
    except Exception as e:
        if check: raise
        results["warnings"].append(str(e))
        return b"" if binary else ""

def shell(*args, **kwargs):
    return adb("shell", *args, **kwargs)

def wait(seconds=.55):
    time.sleep(seconds)

def size():
    s = shell("wm", "size")
    m = re.findall(r"(\d+)x(\d+)", s)
    if not m: raise RuntimeError("Could not read display size: " + s)
    return tuple(map(int, m[-1]))

W, H = size()
results["device"] = {"resolution": [W,H], "abi": shell("getprop","ro.product.cpu.abi").strip(), "sdk": shell("getprop","ro.build.version.sdk").strip()}

def dump():
    global seq
    seq += 1
    adb("shell","uiautomator","dump","/sdcard/jv-ui.xml",timeout=25)
    b=adb("exec-out","cat","/sdcard/jv-ui.xml",timeout=20,binary=True)
    try:
        tree=ET.fromstring(b)
    except Exception as ex:
        raise RuntimeError("Invalid hierarchy xml: "+str(ex))
    return tree,b

def getnodes(tree):
    return list(tree.iter("node"))

def bounds(node):
    m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",node.attrib.get("bounds",""))
    if not m:return (0,0,0,0)
    return tuple(map(int,m.groups()))

def texts(tree):
    return [n.attrib.get("text","") for n in getnodes(tree) if n.attrib.get("text")]

def center(node):
    x1,y1,x2,y2=bounds(node)
    return ((x1+x2)//2,(y1+y2)//2)

def tap(x,y):
    shell("input","tap",str(x),str(y))

def swipe(x1,y1,x2,y2,ms=480):
    shell("input","swipe",str(x1),str(y1),str(x2),str(y2),str(ms))

def back():
    shell("input","keyevent","4")
    wait(.6)

def seek(tree, text, exact=True):
    for n in getnodes(tree):
        label=n.attrib.get("text","")
        if (label==text if exact else text in label):
            b=bounds(n)
            if b[2]>b[0] and b[3]>b[1]:
                return n
    return None

def tap_text(tree, value, exact=True):
    n=seek(tree,value,exact)
    if n:
        x,y=center(n)
        tap(x,y)
        wait(.7)
        return True
    return False

def scroll_target(tree):
    s=[]
    for n in getnodes(tree):
        if n.attrib.get("scrollable")!="true":continue
        x1,y1,x2,y2=bounds(n)
        if (y2-y1)<300 or (x2-x1)<300: continue
        s.append((min(y2-y1,H)*min(x2-x1,W),n))
    if s:
        return bounds(max(s,key=lambda x:x[0])[1])
    return (20,int(.17*H),W-20,int(.86*H))

def scroll(tree,direction="down"):
    x1,y1,x2,y2=scroll_target(tree)
    x=max(x1+8,min(x2-8,(x1+x2)//2))
    hi=min(y2-40,int(.84*H))
    lo=max(y1+40,int(.25*H))
    if hi-lo<180:return False
    swipe(x,hi if direction=="down" else lo,x,lo if direction=="down" else hi,420)
    wait(.65)
    return True

def signature(tree):
    # Do not accidentally call it "bottom" just because a fixed header repeats.
    # Include text bounds for scroll offset; ignore changing system status clock.
    a=[]
    for n in getnodes(tree):
        t=n.attrib.get("text","")
        if t and len(t)>1:
            x1,y1,x2,y2=bounds(n)
            if y2>int(.13*H) and y1<int(.88*H):
                a.append((t[:120],y1//8))
    return hashlib.sha256(json.dumps(a,ensure_ascii=False).encode()).hexdigest()

def capture(folder,label,tree=None,xml=None):
    target=root/folder
    target.mkdir(parents=True,exist_ok=True)
    name=f"{label}.png"
    img=adb("exec-out","screencap","-p",binary=True,timeout=30)
    (target/name).write_bytes(img)
    if tree is None:
        tree,xml=dump()
    if xml is None:xml=ET.tostring(tree,encoding="utf-8")
    (target/f"{label}.xml").write_bytes(xml)
    t=texts(tree)
    flags=[]
    for v in t:
        if len(v)>7 and (v.endswith("…") or v.endswith("...")):
            flags.append("ellipsis:"+v[:90])
    return {"shot":str((target/name).relative_to(Path("audit-output"))),"xml":str((target/f"{label}.xml").relative_to(Path("audit-output"))),
            "textCount":len(t),"texts":t[:80],"flags":flags,"signature":signature(tree)}

def log(event,**kw):
    results["events"].append({"at":round(time.time()-results["started"],2),"event":event,**kw})
    print(json.dumps(results["events"][-1],ensure_ascii=False),flush=True)

def screenrecord(path,seconds=7):
    # Recording happens while we operate UI; record actual frames, not screenshots
    proc=subprocess.Popen(["adb","shell","screenrecord","--bit-rate","2500000","--time-limit",str(seconds),"/sdcard/jv-transition.mp4"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    return proc

def finish_record(proc,path):
    try:proc.wait(timeout=12)
    except subprocess.TimeoutExpired: proc.terminate()
    try:adb("pull","/sdcard/jv-transition.mp4",str(path),timeout=35)
    except Exception as ex:results["warnings"].append("video:"+str(ex))

# Begin in clean test app; no credentials imported or destructive operations.
adb("install","-r","smoke-input/Jarvis-uifix.apk",timeout=120)
shell("pm","grant","app.jarvis.uifix","android.permission.POST_NOTIFICATIONS",check=False)
for key in ["window_animation_scale","transition_animation_scale","animator_duration_scale"]:
    shell("settings","put","global",key,"0.5")
shell("am","start","-W","-n","app.jarvis.uifix/app.jarvis.MainActivity",timeout=45)
wait(3.0)
tree,xml=dump()
results["home"]=capture("home","initial",tree,xml)
log("home",texts=texts(tree)[:9])

# Visit every top-level dock destination at least once (audit first and last visible screen positions).
if group=="a":
    for name,x in DOCK:
        snap={"snapshots":[],"opened":False,"scrollSteps":0}
        try:
            if name != "home":
                tap(int(W*x),int(H*.93))
                wait(1)
            t,b=dump()
            snap["snapshots"].append(capture("dock/"+name,"top",t,b))
            snap["opened"]=True
            if name in ("files","log","studio","agent","terminal","home"):
                for i in range(3):
                    if not scroll(t):break
                    t,b=dump()
                    oldsig=snap["snapshots"][-1]["signature"]
                    snap["snapshots"].append(capture("dock/"+name,f"scroll-{i+1:02d}",t,b))
                    snap["scrollSteps"]+=1
                    if oldsig==snap["snapshots"][-1]["signature"]:break
            log("dock",name=name,screens=len(snap["snapshots"]),lastText=texts(t)[:5])
        except Exception as ex:
            snap["error"]=str(ex);results["warnings"].append("dock/"+name+":"+str(ex))
        results["dock"][name]=snap
    # Go home to take the documented entry path into settings
    tap(int(W*.22),int(H*.93));wait(.8)

# The model chip at the top of HomeScreen navigates to settings/llm.
tap(int(W*.48),int(H*.073));wait(1)
t,b=dump()
if seek(t,"Языковая модель (LLM)") is None:
    results["warnings"].append("Cannot identify model settings after tapping chip; keys="+repr(texts(t)[:12]))
    # Try opening the settings gear if HomeScreen changed.
    tap(int(W*.92),int(H*.075));wait(.8)
# Back from LLM subpage to the settings directory.
back()
t,b=dump()
results["settingsIndex"]=capture("settings","index-top",t,b)
log("settings-directory",sample=texts(t)[:12])
if seek(t,"Языковая модель (LLM)") is None:
    results["warnings"].append("Settings directory not detected; all pages will be marked unreachable")

def visit_settings_page(pid):
    title=TITLES[pid]
    rec={"name":title,"snapshots":[],"opened":False,"scrollSteps":0,"bottomReached":False,"warnings":[]}
    t,b=dump()
    # Search all rows in both directions, retaining settings page original scroll.
    node=seek(t,title)
    if node is None:
        for direction in ("down","up"):
            for _ in range(9):
                scroll(t,direction)
                t,b=dump()
                node=seek(t,title)
                if node is not None:break
            if node is not None:break
    if node is None:
        rec["warnings"].append("row-not-found")
        log("page-unreachable",id=pid,title=title)
        return rec
    x,y=center(node)
    # Capture a transition from source screen through navigation for first page per shard.
    process=screenrecord(root/"transition.mp4",7) if pid==GROUPS[group][0] else None
    tap(x,y);wait(.7)
    t,b=dump()
    rec["opened"]=seek(t,title) is not None
    rec["snapshots"].append(capture("settings/"+pid,"top",t,b))
    log("page-start",id=pid,ok=rec["opened"],texts=texts(t)[:8])
    if not rec["opened"]:
        rec["warnings"].append("header-not-found")
    last=signature(t);stable=0
    for step in range(1,19):
        if not scroll(t):break
        t,b=dump()
        sn=capture("settings/"+pid,f"scroll-{step:02d}",t,b)
        rec["snapshots"].append(sn)
        rec["scrollSteps"]+=1
        if sn["signature"]==last:
            stable+=1
        else:stable=0
        last=sn["signature"]
        if stable>=2:
            rec["bottomReached"]=True
            break
    if len(rec["snapshots"])==19 and not rec["bottomReached"]:
        rec["warnings"].append("scroll-limit-reached")
    # Help overlay can be safely examined on the first page: click a visible '?' button
    # only if the uiautomator tree exposes a precise label (avoid random tap on controls).
    if pid==GROUPS[group][0]:
        candidates=[n for n in getnodes(t) if n.attrib.get("content-desc","") in ("Справка","Подсказка","Помощь","?")]
        if candidates:
            try:
                x,y=center(candidates[0]);tap(x,y);wait(.55)
                ht,hb=dump()
                rec["snapshots"].append(capture("settings/"+pid,"help",ht,hb))
                back()
            except Exception as ex:rec["warnings"].append("help-error:"+str(ex))
    if process:
        finish_record(process,root/"transition.mp4")
    # No destructive interaction: go back to directory with Android navigation.
    back()
    return rec

for pid in GROUPS[group]:
    try:
        results["pages"][pid]=visit_settings_page(pid)
    except Exception as ex:
        results["pages"][pid]={"error":str(ex),"opened":False}
        results["warnings"].append(pid+":"+str(ex))
    (root/"manifest.json").write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding="utf-8")

# End with a screen recording of a dock transition, available in every group.
if group!="a":
    try:
        back() # settings list -> home
        t,b=dump()
        process=screenrecord(root/"dock-transition.mp4",7)
        tap(int(W*.51),int(H*.93))
        wait(.65)
        tap(int(W*.83),int(H*.93))
        finish_record(process,root/"dock-transition.mp4")
    except Exception as ex:results["warnings"].append("transition:"+str(ex))

results["finished"]=time.time()
results["elapsedSec"]=round(results["finished"]-results["started"],1)
try:
    results["gfxinfo"]=(root/"gfxinfo.txt").as_posix()
    (root/"gfxinfo.txt").write_text(shell("dumpsys","gfxinfo","app.jarvis.uifix","framestats",timeout=30),encoding="utf-8")
    (root/"logcat-errors.txt").write_text(shell("logcat","-d","-v","brief","-s","AndroidRuntime:E",timeout=30),encoding="utf-8")
except Exception as ex:
    results["warnings"].append("metrics:"+str(ex))
(root/"manifest.json").write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding="utf-8")
print("UI AUDIT DONE",group,"opened",sum(v.get("opened",False) for v in results["pages"].values()),"of",len(GROUPS[group]),"screenshots",sum(len(v.get("snapshots",[])) for v in results["pages"].values()),"warnings",results["warnings"],flush=True)
if sum(v.get("opened",False) for v in results["pages"].values())<len(GROUPS[group]):
    sys.exit(2)
