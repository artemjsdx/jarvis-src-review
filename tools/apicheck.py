#!/usr/bin/env python3
"""API-level lint: lists framework/JDK members used by app classes that were added after a given API level.
  python3 tools/apicheck.py <classes-dir> [maxApi=26]
Needs .cache/api/api-versions.xml (from the Android platform package). Resolves inherited members through extends/implements.
Reports usage whose `since` > maxApi, grouped, so we can decide whether the real target devices (Android 13+ expected) are safe."""
import os, re, subprocess, sys, xml.etree.ElementTree as ET
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
classes_dir = sys.argv[1]; max_api = int(sys.argv[2]) if len(sys.argv) > 2 else 26
xml = os.path.join(root, ".cache", "api", "api-versions.xml")
if not os.path.exists(xml): sys.exit("missing " + xml)
C = {}
for ev, el in ET.iterparse(xml, events=("end",)):
    if el.tag == "class":
        name = el.get("name"); since = int(el.get("since", "1"))
        members = {}
        for m in el:
            if m.tag in ("method", "field"):
                members[m.get("name")] = int(m.get("since", "0")) or since
        supers = [m.get("name") for m in el if m.tag in ("extends", "implements")]
        C[name] = (since, members, supers)
        el.clear()
def lookup(cls, member, seen=None):
    seen = seen or set()
    if cls in seen or cls not in C: return None
    seen.add(cls)
    since, members, supers = C[cls]
    if member in members: return max(since, members[member])
    for s in supers:
        r = lookup(s, member, seen)
        if r is not None: return r
    return None
files = [os.path.join(d, f) for d, _, fs in os.walk(classes_dir) for f in fs if f.endswith(".class")]
out = subprocess.run(["javap", "-c", "-p", "-constants"] + files, capture_output=True, text=True).stdout
refs = {}
cur = ""
for line in out.splitlines():
    m = re.match(r'^(?:\S.*\s)?class (\S+)', line) or re.match(r'^(?:public |final |abstract )*(?:class|interface) (\S+)', line)
    if m and "{" in line: cur = m.group(1)
    m = re.search(r'(?:invoke\w+|getstatic|putstatic|getfield|putfield)\s+#\d+\s+//\s+(?:Method|InterfaceMethod|Field)\s+([^.\s]+(?:/[^.\s]+)*)\.([^:\s]+):(\S+)', line)
    if m:
        cls, name, desc = m.groups()
        cls = cls.strip('"')
        if cls.startswith("[") or cls not in C: continue
        key = name + desc if name != "<init>" else "<init>" + desc
        s = lookup(cls, name + desc) if name != "<init>" else lookup(cls, "<init>" + desc)
        if s is None: s = lookup(cls, name)  # fields have no descriptor
        if s is None: s = C[cls][0] if False else None
        if s and s > max_api:
            refs.setdefault((s, cls, name + (desc if "(" in desc else "")), set()).add(cur)
for (s, cls, mem), users in sorted(refs.items(), key=lambda x: (-x[0][0], x[0][1])):
    print("API %-2d %s.%s  <- %s" % (s, cls, mem, ", ".join(sorted(users))[:110]))
print("--- %d distinct members above API %d" % (len(refs), max_api))
