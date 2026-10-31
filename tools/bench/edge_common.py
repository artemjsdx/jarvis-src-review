"""Shared helpers for Edge Read Aloud benches (token via the reference edge-tts drm module in .cache/research)."""
import importlib.util, os, sys, time, types, uuid, secrets, json
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PKG = os.path.join(ROOT, '.cache', 'research', 'edge_tts_pkg')
TOKEN = '6A5AA1D4EAFF4E9FB37E23D68491D6F4'
VERSION = '2-143.0.3650.75'
WSS = 'wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1'
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0')
VOICE = 'ru-RU-SvetlanaNeural'
FMT = 'audio-24khz-48kbitrate-mono-mp3'


def _load_drm():
    sys.path.insert(0, os.path.join(PKG, 'stubs'))
    pkg = types.ModuleType('edge_tts'); pkg.__path__ = [os.path.join(PKG, 'edge_tts')]; sys.modules['edge_tts'] = pkg
    def load(name):
        spec = importlib.util.spec_from_file_location('edge_tts.' + name, os.path.join(PKG, 'edge_tts', name + '.py'))
        m = importlib.util.module_from_spec(spec); sys.modules['edge_tts.' + name] = m; spec.loader.exec_module(m); return m
    load('constants'); load('exceptions')
    return load('drm')


_drm = None


def gec():
    global _drm
    if _drm is None:
        _drm = _load_drm()
    return _drm.generate_sec_ms_gec_v2(time.time())


def url():
    return (WSS + '?TrustedClientToken=' + TOKEN + '&ConnectionId=' + uuid.uuid4().hex
            + '&Sec-MS-GEC=' + gec() + '&Sec-MS-GEC-Version=' + VERSION)


def headers():
    return {'Pragma': 'no-cache', 'Cache-Control': 'no-cache',
            'Origin': 'chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold', 'User-Agent': UA,
            'Accept-Language': 'en-US,en;q=0.9', 'Cookie': 'muid=' + secrets.token_hex(16).upper() + ';'}


def jsdate():
    return datetime.now(timezone.utc).strftime('%a %b %d %Y %H:%M:%S') + ' GMT+0000 (Coordinated Universal Time)'


def config_msg():
    return ('X-Timestamp:' + jsdate() + '\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n'
            '{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},'
            '"outputFormat":"' + FMT + '"}}}}\r\n')


def ssml_msg(text, rid=None):
    ssml = ("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='" + VOICE + "'>"
            "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>" + text + "</prosody></voice></speak>")
    return ('X-RequestId:' + (rid or uuid.uuid4().hex) + '\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:'
            + jsdate() + 'Z\r\nPath:ssml\r\n\r\n' + ssml)
