#!/usr/bin/env python3
"""Groq Whisper: payload size and upload+transcribe time per audio container for one 16 kHz mono phrase.
Usage: python3 tools/bench/stt_formats.py [path/to/16k_mono_s16le.pcm] [repeats=2]
Key: ~/jarvis/.cache/research/keys.py (groq()), never printed."""
import os, subprocess, sys, tempfile, time
import requests

KEYS = '/data/data/com.termux/files/home/jarvis/.cache/research'
PCM = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.environ.get('TMPDIR', '/tmp'), 'svet16k.pcm')
REPEATS = int(sys.argv[2]) if len(sys.argv) > 2 else 2
MODEL = os.environ.get('GROQ_STT_MODEL', 'whisper-large-v3-turbo')


def key():
    sys.path.insert(0, KEYS)
    import keys
    return keys.groq()[0]


def ff(args, out):
    subprocess.run(['ffmpeg', '-v', 'error', '-y'] + args + [out], check=True)


def main():
    k = key()
    d = tempfile.mkdtemp(prefix='sttfmt')
    base = ['-f', 's16le', '-ar', '16000', '-ac', '1', '-i', PCM]
    files = {}
    ff(base + ['-c:a', 'pcm_s16le'], d + '/a.wav'); files['wav16k'] = (d + '/a.wav', 'audio/wav')
    ff(base + ['-c:a', 'flac'], d + '/a.flac'); files['flac'] = (d + '/a.flac', 'audio/flac')
    ff(base + ['-c:a', 'libmp3lame', '-b:a', '32k'], d + '/a32.mp3'); files['mp3_32k'] = (d + '/a32.mp3', 'audio/mpeg')
    ff(base + ['-c:a', 'libmp3lame', '-b:a', '64k'], d + '/a64.mp3'); files['mp3_64k'] = (d + '/a64.mp3', 'audio/mpeg')
    for br in (16, 24):
        ff(base + ['-c:a', 'libopus', '-b:a', '%dk' % br, '-application', 'voip'], '%s/o%d.ogg' % (d, br))
        files['ogg_opus_%dk' % br] = ('%s/o%d.ogg' % (d, br), 'audio/ogg')
    ff(base + ['-c:a', 'aac', '-b:a', '32k', '-f', 'adts'], d + '/a.aac'); files['aac_adts_32k'] = (d + '/a.aac', 'audio/aac')
    dur = os.path.getsize(PCM) / 32000.0
    print('phrase %.2f s, model %s' % (dur, MODEL))
    print('%-14s %8s %8s  %s' % ('format', 'bytes', 'ratio', 'results (s upload+transcribe, text chars) / status'))
    wav_size = os.path.getsize(files['wav16k'][0])
    for name, (path, mime) in files.items():
        size = os.path.getsize(path)
        res = []
        for _ in range(REPEATS):
            t = time.perf_counter()
            try:
                r = requests.post('https://api.groq.com/openai/v1/audio/transcriptions', headers={'Authorization': 'Bearer ' + k},
                                  files={'file': (os.path.basename(path), open(path, 'rb'), mime)},
                                  data={'model': MODEL, 'language': 'ru', 'response_format': 'json', 'temperature': '0'}, timeout=90)
                dt = time.perf_counter() - t
                if r.status_code == 200:
                    res.append('%.2fs %dch' % (dt, len(r.json().get('text', ''))))
                else:
                    res.append('HTTP %d %s' % (r.status_code, r.text[:70].replace('\n', ' ')))
            except Exception as e:
                res.append('%s %s' % (type(e).__name__, str(e)[:50]))
        print('%-14s %8d %7.0f%%  %s' % (name, size, 100.0 * size / wav_size, ' | '.join(res)), flush=True)

main()
