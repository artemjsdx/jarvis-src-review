#!/usr/bin/env python3
"""Assemble an unsigned APK: copy aapt2's base.apk (manifest + resources), add dex files, native
libraries and extra assets with correct compression (native libs are STORED so zipalign -p can
page-align them and extractNativeLibs=false installs stay valid)."""
import argparse, os, sys, zipfile

p = argparse.ArgumentParser()
p.add_argument("base"); p.add_argument("out")
p.add_argument("--dex", action="append", default=[], help="directory with classes*.dex")
p.add_argument("--lib", action="append", default=[], help="dir laid out as <abi>/lib*.so")
p.add_argument("--extra", action="append", default=[], help="dir copied verbatim into the apk root")
a = p.parse_args()

seen = set()
with zipfile.ZipFile(a.out, "w") as z:
    with zipfile.ZipFile(a.base) as b:
        for info in b.infolist():
            data = b.read(info.filename)
            ct = zipfile.ZIP_STORED if info.filename == "resources.arsc" else info.compress_type
            z.writestr(zipfile.ZipInfo(info.filename, (1981, 1, 1, 0, 0, 0)), data, compress_type=ct)
            seen.add(info.filename)
    for d in a.dex:
        for f in sorted(os.listdir(d)):
            if f.endswith(".dex"):
                zi = zipfile.ZipInfo(f, (1981, 1, 1, 0, 0, 0))
                z.writestr(zi, open(os.path.join(d, f), "rb").read(), compress_type=zipfile.ZIP_DEFLATED)
                seen.add(f)
    for d in a.lib:
        for abi in sorted(os.listdir(d)):
            for f in sorted(os.listdir(os.path.join(d, abi))):
                if f.endswith(".so"):
                    name = "lib/%s/%s" % (abi, f)
                    zi = zipfile.ZipInfo(name, (1981, 1, 1, 0, 0, 0))
                    z.writestr(zi, open(os.path.join(d, abi, f), "rb").read(), compress_type=zipfile.ZIP_STORED)
                    seen.add(name)
    for d in a.extra:
        for root, _, files in os.walk(d):
            for f in sorted(files):
                full = os.path.join(root, f)
                name = os.path.relpath(full, d)
                if name in seen:
                    sys.exit("duplicate entry: " + name)
                zi = zipfile.ZipInfo(name, (1981, 1, 1, 0, 0, 0))
                z.writestr(zi, open(full, "rb").read(), compress_type=zipfile.ZIP_DEFLATED)
print("apk entries:", len(seen))
