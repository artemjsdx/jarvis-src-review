#!/usr/bin/env python3
"""check-apk.py APK [--expect-cert-sha256 HEX]   release gate for every APK that leaves the build machine.

 * assets/provisioned.json may carry ONLY the bridge token (secrets == {"mcp.token"}): no API keys;
 * no string shaped like a provider key anywhere in dex / assets / resources;
 * prints the signer certificate SHA-256 (public) and, when --expect-cert-sha256 is given, fails if it differs.
Exit 0 = fine, 1 = problem. Never prints secret values."""
import json, re, subprocess, sys, zipfile

KEY_SHAPES = [rb"sk-or-v1-[A-Za-z0-9]{20,}", rb"sk-[A-Za-z0-9]{32,}", rb"gsk_[A-Za-z0-9]{24,}", rb"AKIA[0-9A-Z]{16}",
              rb"(?:ghp_|gho_|ghs_|ghu_|github_pat_)[A-Za-z0-9_]{30,}", rb"sk_[0-9a-f]{40,}"]
RX = re.compile(b"|".join(b"(?:%s)" % p for p in KEY_SHAPES))
PRIV = re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__); return 2
    apk = args[0]; expect = ""
    if "--expect-cert-sha256" in args:
        expect = re.sub(r"[^0-9a-f]", "", args[args.index("--expect-cert-sha256") + 1].lower())
    bad = []
    z = zipfile.ZipFile(apk)
    if "assets/provisioned.json" in z.namelist():
        prov = json.loads(z.read("assets/provisioned.json"))
        sec = sorted(prov.get("secrets", {}))
        if sec != ["mcp.token"]:
            bad.append("provisioned.json carries secrets other than mcp.token: %s" % sec)
    for n in z.namelist():
        if n.endswith((".so", ".ogg", ".png", ".jpg", ".webp", ".zip", ".arsc")) and not n.endswith(".json"):
            continue
        data = z.read(n)
        if RX.search(data):
            bad.append("key-shaped string in " + n)
        if not n.endswith(".dex") and PRIV.search(data):   # the dex legitimately contains the PEM header constant of the ADB key code
            bad.append("private key block in " + n)
    out = subprocess.run(["apksigner", "verify", "--print-certs", apk], capture_output=True, text=True)
    m = re.search(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", out.stdout)
    cert = m.group(1).lower() if m else ""
    print("signature verify rc=%d, signer SHA-256: %s" % (out.returncode, cert or "?"))
    if out.returncode != 0:
        bad.append("apksigner verify failed: " + out.stderr.strip()[:120])
    if expect and cert != expect:
        bad.append("signer certificate differs from the expected one (expected %s...)" % expect[:12])
    print("entries: %d, size: %.1f MB" % (len(z.namelist()), sum(i.file_size for i in z.infolist()) / 1e6))
    for b in bad:
        print("PROBLEM:", b)
    print("check-apk: " + ("FAILED" if bad else "OK (only the bridge token is provisioned; no key shapes found)"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
