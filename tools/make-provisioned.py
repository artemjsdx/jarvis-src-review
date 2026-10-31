#!/usr/bin/env python3
"""Builds ~/.jarvis-keys/provisioned.json (secrets + settings for the OWNER's personal build) from keys the owner keeps
on this phone. The file never enters the repo; `bash build.sh personal` embeds it as assets/provisioned.json.
Nothing is printed except key names and masked lengths."""
import json, os, re, secrets as _secrets, sys
home = os.path.expanduser("~")
sys.path.insert(0, os.path.join(home, "jarvis", ".cache", "research"))
import keys
out = {"secrets": {}, "settings": {}}
s = out["secrets"]
try: s["key.inception"] = keys.inception()
except Exception: pass
e = keys.eleven();  s.update({"key.elevenlabs": e[0]} if e else {})
g = keys.groq();    s.update({"key.groq": g[0]} if g else {})
auth = json.load(open(os.path.join(home, ".local/share/opencode/auth.json")))
for opencode_id, our_id in (("openrouter", "openrouter"), ("ikhdev", "ikhdev"), ("reformboss", "reformboss"), ("selora", "selora")):
    k = auth.get(opencode_id, {}).get("key")
    if k: s["key." + our_id] = k
zt = open("/storage/emulated/0/Цхранилище/API/02 API-ключи/ZAI_key_live.txt", encoding="utf-8", errors="replace").read()
m = re.search(r"key:\s*([A-Za-z0-9._\-]{30,})", zt)
if m: s["key.zai-coding"] = m.group(1)
# MCP bridge: a stable pre-shared token, so the agent in Termux can reach the app without anybody copying it from the phone.
# The same token goes into the shim's key file (~/.jarvis-keys/mcp-app.jarvis.json); an existing token is never replaced.
mcp_file = os.path.join(home, ".jarvis-keys", "mcp-app.jarvis.json")
mcp_token = ""
try: mcp_token = json.load(open(mcp_file)).get("token", "")
except Exception: pass
if not re.fullmatch(r"[0-9a-f]{64}", mcp_token or ""):
    mcp_token = _secrets.token_hex(32)
    os.makedirs(os.path.dirname(mcp_file), exist_ok=True)
    json.dump({"token": mcp_token, "port": 8767}, open(mcp_file, "w"))
    os.chmod(mcp_file, 0o600)
s["mcp.token"] = mcp_token
st = out["settings"]
st.update({
    "mcp.enabled": "true",
    # api.inceptionlabs.ai answers 403 (geo-block) from Belarus, so the owner's primary is DeepSeek V4.1 Flash through OpenRouter
    "llm.provider": "openrouter", "llm.model.openrouter": "deepseek/deepseek-v4.1-flash", "llm.fallbacks": "zai-coding,inception",
    "llm.reasoning": "low", "llm.model.zai-coding": "glm-5.3-flash", "llm.model.inception": "mercury-3",
    "tts.auto": "voice", "tts.chain": "elevenlabs,edge,gtts", "stt.chain": "groq-stt,elevenlabs-stt,google-stt,openrouter-stt",
    "wake.stt": "google-stt", "tts.elevenlabs.model": "eleven_flash_v2_5",
    "tts.voice.elevenlabs": "JBFqnCBsd6RMkjVDRZzb", "tts.voice.edge": "ru-RU-SvetlanaNeural",
})
path = os.path.join(home, ".jarvis-keys", "provisioned.json")
json.dump(out, open(path, "w"), ensure_ascii=False, indent=1)
os.chmod(path, 0o600)
print("wrote", path)
for k, v in s.items(): print("  %-22s %s…(%d)" % (k, "****" if k == "mcp.token" else v[:4], len(v)))
