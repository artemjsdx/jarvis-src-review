#!/usr/bin/env python3
"""scan-secrets.py [REVRANGE]   pre-push gate: nothing that looks like a credential may leave this machine.

Scans the patches of REVRANGE (default: everything reachable from HEAD, or only the commits GitHub does not have yet when a remote ref is given,
e.g. `ci/origin..HEAD`) in two ways, WITHOUT ever printing a value:
  1. generic key shapes (provider prefixes, private key blocks, JWTs, long hex tokens next to words like key/token/secret);
  2. literal values of the owner's real keys, harvested from local key stores (~/.jarvis-keys, opencode auth.json, the API folder when present),
     filtered to key-shaped strings so that model ids and other public words are not mistaken for keys.
Also fails when a file that must never be committed (keystores, provisioned*.json, .env, session files) appears in the range.
Exit 0 = clean, 1 = findings (commit + file are listed)."""
import collections, glob, math, os, re, subprocess, sys

RANGE = sys.argv[1] if len(sys.argv) > 1 else "HEAD"
PUBLIC = {"6A5AA1D4EAFF4E9FB37E23D68491D6F4", "REDACTED"}   # Edge client token, Chromium web-speech key: documented public constants
SHAPES = {
    "openrouter": r"sk-or-v1-[A-Za-z0-9]{20,}", "sk-key": r"sk-[A-Za-z0-9]{32,}", "groq": r"gsk_[A-Za-z0-9]{24,}", "aws": r"AKIA[0-9A-Z]{16}",
    "github": r"(?:ghp_|gho_|ghs_|ghu_|github_pat_)[A-Za-z0-9_]{30,}", "slack": r"xox[abprs]-[A-Za-z0-9-]{10,}", "eleven": r"\bsk_[0-9a-f]{40,}",
    "google": r"AIza[0-9A-Za-z_\-]{35}", "jwt": r"eyJ[A-Za-z0-9_\-]{20,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}",
    "zai": r"\b[0-9a-f]{32}\.[A-Za-z0-9]{16}\b", "hex-assign": r"(?i)(?:key|token|secret|passw\w*)[\"']?\s*[:=]\s*[\"']?[0-9a-f]{32,}[\"']?",
}
RX = {k: re.compile(v) for k, v in SHAPES.items()}
PRIV = re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----\s*[A-Za-z0-9+/=]{40,}")   # a real PEM block has base64 right after the header; the bare header constant in source does not
BAD_FILES = re.compile(r"(?:^|/)(?:[^/]*\.(?:p12|jks|keystore|session)|provisioned[^/]*\.json|\.env|keystore\.pass|github\.token)$")


def entropy(s):
    c = collections.Counter(s); n = len(s)
    return -sum(v / n * math.log2(v / n) for v in c.values())


def keyish(s):
    if s in PUBLIC or len(s) < 20:
        return False
    if re.match(r"^(sk[-_]|gsk_|ghp_|gho_|github_pat_|xai-|r8_|hf_|AIza|tvly-|fc-|pplx-|nvapi-|csk-|cpk_|sk_)", s):
        return True
    if re.fullmatch(r"[0-9a-fA-F]{32,}", s):
        return True
    return len(s) >= 32 and bool(re.search(r"[a-z]", s) and re.search(r"[A-Z]", s) and re.search(r"[0-9]", s)) and entropy(s) >= 4.2


def literals():
    out = set()
    tok = re.compile(r"[A-Za-z0-9_\-\.\+=]{20,}")
    files = glob.glob(os.path.expanduser("~/.jarvis-keys/*")) + [os.path.expanduser("~/.local/share/opencode/auth.json")] + glob.glob(os.path.expanduser("~/jarvis/.cache/research/*.py"))
    d = os.environ.get("JV_SECRET_DIR", "/storage/emulated/0/Цхранилище/API")
    if os.path.isdir(d):
        files += [f for f in glob.glob(d + "/**/*", recursive=True) if os.path.isfile(f) and os.path.getsize(f) < 400_000 and "__pycache__" not in f]
    for f in files:
        if f.endswith((".p12", ".pyc")) or not os.path.isfile(f):
            continue
        try:
            t = open(f, encoding="utf8", errors="ignore").read()
        except OSError:
            continue
        for m in tok.finditer(t):
            s = m.group(0).strip(".-_=+")
            if keyish(s):
                out.add(s)
    try:
        out.add(open(os.path.expanduser("~/.jarvis-keys/keystore.pass")).read().strip())
    except OSError:
        pass
    return {s for s in out if len(s) >= 8}


def main():
    lits = literals()
    p = subprocess.Popen(["git", "log", RANGE, "-p", "--no-color", "--format=C %h", "--diff-filter=AM"], stdout=subprocess.PIPE, text=True, errors="ignore")
    c = f = ""; found = collections.OrderedDict(); commits = set()
    for line in p.stdout:
        if line.startswith("C "):
            c = line[2:].strip(); commits.add(c); continue
        if line.startswith("+++ b/"):
            f = line[6:].strip()
            if BAD_FILES.search(f) and not f.startswith(("tools/make-provisioned", "tools/ci/")):
                found[("forbidden-file", c, f)] = 1
            continue
        if not line.startswith("+"):
            continue
        for k, r in RX.items():
            for m in r.finditer(line):
                if m.group(0) in PUBLIC or any(pub in m.group(0) for pub in PUBLIC):
                    continue
                if k == "hex-assign" and f.startswith("app/src/test/"):
                    continue          # crypto test vectors (RFC 8032, recorded SPAKE2 exchanges): public by nature
                found[(k, c, f)] = found.get((k, c, f), 0) + 1
        if PRIV.search(line):
            found[("private-key-block", c, f)] = 1
        for s in lits:
            if s in line:
                found[("known-secret-literal", c, f)] = found.get((("known-secret-literal"), c, f), 0) + 1
                break
    p.wait()
    print("scanned %d commits of %s against %d known key-shaped literals" % (len(commits), RANGE, len(lits)))
    for (k, cc, ff), n in list(found.items())[:40]:
        print("  FINDING %-20s commit %s  %s" % (k, cc, ff))
    print("scan-secrets: " + ("FINDINGS" if found else "clean"))
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
