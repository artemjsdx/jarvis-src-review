#!/usr/bin/env python3
"""Procedural generator for the recording start/stop/error cues (BACKLOG Z-01).

  python3 tools/gen-sounds.py            # regenerate app/assets/sounds/*.ogg + sounds.json
  python3 tools/gen-sounds.py --check    # decode the OGG files and print measurements (markdown)
  python3 tools/gen-sounds.py --check --update-doc   # also rewrite the table in docs/SOUNDS.md

Deterministic: all noise comes from seeded RNGs, ffmpeg runs with bitexact flags.
Needs numpy and ffmpeg (libvorbis). Every sound is 44.1 kHz mono, normalised to about -20 LUFS
(BS.1770 K-weighted, ungated over the whole clip) with a peak ceiling of -3 dBFS.
"""
import hashlib
import json
import math
import os
import subprocess
import sys
import tempfile

import numpy as np

SR = 44100
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "assets", "sounds")
DOC = os.path.join(ROOT, "docs", "SOUNDS.md")
TARGET_LUFS = -20.0
PEAK_CEIL_DB = -3.0
FADE = 0.005
MAX_FILE_BYTES = 25 * 1024
MAX_TOTAL_BYTES = 600 * 1024

# ---------------------------------------------------------------- primitives

def time_axis(dur):
    return np.arange(int(round(dur * SR))) / SR


def smooth_attack(n_total, attack):
    na = max(1, int(attack * SR))
    a = np.ones(n_total)
    a[:na] = 0.5 - 0.5 * np.cos(np.pi * np.arange(na) / na)
    return a


def lowpass(x, fc, order=2):
    """Zero-phase Butterworth-shaped low-pass via FFT (with zero padding)."""
    n = len(x)
    m = 1 << (n + SR // 10 - 1).bit_length()
    f = np.fft.rfftfreq(m, 1.0 / SR)
    g = 1.0 / np.sqrt(1.0 + (f / fc) ** (2 * order))
    return np.fft.irfft(np.fft.rfft(x, m) * g, m)[:n]


def highpass(x, fc, order=2):
    n = len(x)
    m = 1 << (n + SR // 10 - 1).bit_length()
    f = np.fft.rfftfreq(m, 1.0 / SR)
    r = (f / fc) ** (2 * order)
    g = np.sqrt(r / (1.0 + r))
    return np.fft.irfft(np.fft.rfft(x, m) * g, m)[:n]


def partials_tone(freq, dur, partials, tau, attack=0.003, inharm=0.0, freq_curve=None):
    """Additive tone. partials = [(ratio, amp, tau_scale)]. freq_curve(t)->multiplier for glides."""
    t = time_axis(dur)
    mult = freq_curve(t) if freq_curve is not None else np.ones_like(t)
    y = np.zeros_like(t)
    for ratio, amp, ts in partials:
        r = ratio * math.sqrt(1.0 + inharm * ratio * ratio) if inharm else ratio
        f = freq * r * mult
        if f.max() > SR * 0.45:
            continue
        phase = 2 * np.pi * np.cumsum(f) / SR
        y += amp * np.sin(phase) * np.exp(-t / (tau * ts))
    return y * smooth_attack(len(t), attack)


def mix_notes(total, notes):
    """notes = [(start_s, wave, gain)] -> buffer of `total` seconds."""
    buf = np.zeros(int(round(total * SR)))
    for t0, w, g in notes:
        i = int(round(t0 * SR))
        j = min(len(buf), i + len(w))
        buf[i:j] += g * w[: j - i]
    return buf


def karplus_strong(freq, dur, rng, decay=0.996, bright=0.5):
    """Plucked string; block-wise Karplus-Strong with a lowpassed noise burst excitation."""
    n = int(round(dur * SR))
    period = SR / freq
    p = int(round(period))
    exc = rng.uniform(-1, 1, p)
    exc = np.convolve(exc, [bright, 1 - bright], mode="same") if bright < 1 else exc
    exc -= exc.mean()
    out = np.zeros(n + p)
    out[:p] = exc
    prev_last = 0.0
    for i in range(p, n + p, p):
        blk = out[i - p:i]
        shifted = np.concatenate(([prev_last], blk[:-1]))
        out[i:i + p] = decay * 0.5 * (blk + shifted)[: len(out[i:i + p])]
        prev_last = blk[-1]
    y = out[:n]
    y = highpass(y, 80.0, 1)
    return y * smooth_attack(n, 0.001)


def finish(x, dur, fade=FADE, release=0.030):
    """Trim/pad to dur, remove DC, 5 ms fade-in, cosine release over the last `release` s."""
    n = int(round(dur * SR))
    x = np.pad(x, (0, max(0, n - len(x))))[:n]
    x = x - x.mean()
    nf = int(fade * SR)
    ramp = 0.5 - 0.5 * np.cos(np.pi * np.arange(nf) / nf)
    x[:nf] *= ramp
    nr = int(release * SR)
    x[-nr:] *= (0.5 + 0.5 * np.cos(np.pi * np.arange(nr) / nr))
    return x

# ----------------------------------------------------------------- loudness

def _biquad(x, b, a):
    y = np.zeros_like(x)
    x1 = x2 = y1 = y2 = 0.0
    b0, b1, b2 = b
    a1, a2 = a
    for i in range(len(x)):
        v = x[i]
        o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1 = x1, v
        y2, y1 = y1, o
        y[i] = o
    return y


def k_weight(x):
    """BS.1770 K-weighting (shelf + RLB high-pass) designed for SR."""
    # stage 1: high shelf
    f0, g, q = 1681.974450955533, 3.999843853973347, 0.7071752369554196
    k = math.tan(math.pi * f0 / SR)
    vh = 10 ** (g / 20.0)
    vb = vh ** 0.4996667741545416
    a0 = 1 + k / q + k * k
    b = ((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0)
    a = (2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    y = _biquad(x, b, a)
    # stage 2: RLB high-pass
    f0, q = 38.13547087602444, 0.5003270373238773
    k = math.tan(math.pi * f0 / SR)
    a0 = 1 + k / q + k * k
    b = (1.0, -2.0, 1.0)
    a = (2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    return _biquad(y, b, a)


def clip_lufs(x):
    y = k_weight(x)
    return -0.691 + 10 * math.log10(max(float(np.mean(y * y)), 1e-20))


def normalise(x):
    """Gain to TARGET_LUFS, limited so the sample peak stays <= PEAK_CEIL_DB. Returns (y, info)."""
    lufs = clip_lufs(x)
    gain_db = TARGET_LUFS - lufs
    peak_db = 20 * math.log10(max(float(np.max(np.abs(x))), 1e-12))
    limited = False
    if peak_db + gain_db > PEAK_CEIL_DB:
        gain_db = PEAK_CEIL_DB - peak_db
        limited = True
    return x * 10 ** (gain_db / 20.0), {"gain_db": gain_db, "peak_limited": limited}

# ----------------------------------------------------------------- voices

N = {  # note frequencies (Hz)
    "C4": 261.63, "D4": 293.66, "E4": 329.63, "F4": 349.23, "G4": 392.00, "A4": 440.00,
    "C5": 523.25, "D5": 587.33, "E5": 659.26, "F5": 698.46, "G5": 783.99, "A5": 880.00,
    "C6": 1046.5, "D6": 1174.7, "E6": 1318.5, "G6": 1568.0, "A6": 1760.0, "B6": 1975.5,
    "D7": 2349.3,
}


def v_crystal(f, dur, rng):
    p = [(1.0, 1.0, 1.0), (2.756, 0.35, 0.5), (5.404, 0.14, 0.3)]
    return lowpass(partials_tone(f, dur, p, tau=0.055, attack=0.002), 9000)


def v_marimba(f, dur, rng):
    p = [(1.0, 1.0, 1.0), (3.99, 0.32, 0.35), (9.8, 0.07, 0.15)]
    y = partials_tone(f, dur, p, tau=0.07, attack=0.0015)
    return lowpass(y, 5000)


def v_bell(f, dur, rng):
    p = [(0.5, 0.35, 1.2), (1.0, 1.0, 1.0), (1.19, 0.35, 0.7), (1.5, 0.3, 0.55),
         (2.0, 0.45, 0.5), (2.55, 0.14, 0.3), (3.0, 0.12, 0.25)]
    return lowpass(partials_tone(f, dur, p, tau=0.085, attack=0.002), 7000)


def v_piano(f, dur, rng):
    p = [(1.0, 1.0, 1.0), (2.0, 0.5, 0.8), (3.0, 0.28, 0.6), (4.0, 0.14, 0.45), (5.0, 0.07, 0.35)]
    y = partials_tone(f, dur, p, tau=0.085, attack=0.008, inharm=0.0004)
    thump = lowpass(rng.normal(0, 1, len(y)), 500) * np.exp(-time_axis(dur) / 0.012) * 0.12
    return lowpass(y + thump, 3200)


def v_musicbox(f, dur, rng):
    p = [(1.0, 1.0, 1.0), (2.0, 0.18, 0.5), (3.01, 0.12, 0.35), (6.2, 0.07, 0.2)]
    return lowpass(partials_tone(f, dur, p, tau=0.075, attack=0.0008), 9500)


def v_harp(f, dur, rng):
    return lowpass(karplus_strong(f, dur, rng, decay=0.990, bright=0.35), 6500)


def sequence(voice, notes, total, rng):
    return mix_notes(total, [(t0, voice(N[name], total - t0, rng), g) for t0, name, g in notes])

# note-based families: start / stop / error sequences, (total length s)
NOTE_FAMILIES = {
    "crystal": dict(voice=v_crystal,
        start=([(0.0, "E6", 0.8), (0.07, "B6", 1.0)], 0.32),
        stop=([(0.0, "B6", 0.9), (0.09, "E6", 0.9)], 0.38),
        error=([(0.0, "E5", 0.9), (0.13, "C5", 0.9)], 0.42)),
    "marimba": dict(voice=v_marimba,
        start=([(0.0, "A4", 0.85), (0.08, "E5", 1.0)], 0.26),
        stop=([(0.0, "E5", 0.9), (0.09, "A4", 0.9)], 0.30),
        error=([(0.0, "F4", 0.9), (0.13, "C4", 0.9)], 0.40)),
    "bell": dict(voice=v_bell,
        start=([(0.0, "G5", 0.8), (0.09, "D6", 1.0)], 0.35),
        stop=([(0.0, "D6", 0.9), (0.10, "G5", 0.9)], 0.40),
        error=([(0.0, "A4", 0.9), (0.15, "E4", 0.9)], 0.45)),
    "piano": dict(voice=v_piano,
        start=([(0.0, "D5", 0.85), (0.08, "A5", 1.0)], 0.30),
        stop=([(0.0, "A5", 0.9), (0.09, "D5", 0.9)], 0.38),
        error=([(0.0, "F4", 0.9), (0.14, "D4", 0.9)], 0.45)),
    "musicbox": dict(voice=v_musicbox,
        start=([(0.0, "G6", 0.8), (0.055, "B6", 0.9), (0.11, "D7", 1.0)], 0.30),
        stop=([(0.0, "D7", 0.9), (0.07, "B6", 0.9), (0.14, "G6", 0.9)], 0.38),
        error=([(0.0, "C6", 0.9), (0.12, "G5", 0.9)], 0.42)),
    "harp": dict(voice=v_harp,
        start=([(0.0, "E5", 0.85), (0.07, "A5", 1.0)], 0.30),
        stop=([(0.0, "A5", 0.9), (0.08, "E5", 0.9)], 0.38),
        error=([(0.0, "E4", 0.9), (0.13, "C4", 0.9)], 0.42)),
}

# ---- special families -------------------------------------------------

def water_drop(kind, rng):
    """Bubble-like drop: pitch glide up (start) / down (stop) / low plop (error)."""
    if kind == "start":
        dur, f0, f1, tau, glide = 0.20, 520.0, 1500.0, 0.050, 0.035
    elif kind == "stop":
        dur, f0, f1, tau, glide = 0.26, 1350.0, 430.0, 0.065, 0.060
    else:
        dur, f0, f1, tau, glide = 0.34, 380.0, 190.0, 0.100, 0.090
    t = time_axis(dur)
    f = f1 + (f0 - f1) * np.exp(-t / glide)
    if kind == "stop":
        f = f1 + (f0 - f1) * np.exp(-t / glide)
    phase = 2 * np.pi * np.cumsum(f) / SR
    y = (np.sin(phase) + 0.12 * np.sin(2 * phase)) * np.exp(-t / tau) * smooth_attack(len(t), 0.002)
    return lowpass(y, 5000), dur


def breath(kind, rng):
    """Airy band-passed noise whose centre moves up (start), down (stop), or stays low (error)."""
    dur = {"start": 0.30, "stop": 0.36, "error": 0.40}[kind]
    n = int(round(dur * SR))
    t = np.arange(n) / SR
    x = rng.normal(0, 1, n)
    m = 1 << (n - 1).bit_length()
    spec = np.fft.rfft(x, m)
    fr = np.fft.rfftfreq(m, 1.0 / SR)
    centres = np.geomspace(500.0, 5000.0, 12)
    y = np.zeros(n)
    u = t / dur
    if kind == "start":
        pos = 700 * (4500 / 700) ** (u ** 1.3)
        env = np.sin(np.pi * np.clip(u ** 0.8, 0, 1)) ** 2
    elif kind == "stop":
        pos = 4200 * (600 / 4200) ** (u ** 0.8)
        env = np.sin(np.pi * np.clip(u ** 0.5, 0, 1)) ** 2 * np.exp(-1.2 * u)
    else:
        pos = 700 * (350 / 700) ** u
        env = np.sin(np.pi * u) ** 2
    for c in centres:
        band = np.fft.irfft(spec * np.exp(-0.5 * (np.log2(np.maximum(fr, 1.0) / c) / 0.45) ** 2), m)[:n]
        w = np.exp(-0.5 * (np.log2(pos / c) / 0.55) ** 2)
        y += band * w
    return y * env, dur


def bowed(kind, rng):
    """Bowed glass: swell with slight pitch lift (start) / bow onset then pitch fall (stop)."""
    p = [(1.0, 1.0, 1.0), (2.0, 0.28, 1.0), (3.0, 0.14, 1.0), (4.02, 0.06, 1.0)]
    if kind == "start":
        dur, f, att, tau = 0.34, 880.0, 0.16, 0.10
        curve = lambda t: 1.0 + 0.09 * np.clip(t / 0.22, 0, 1)
    elif kind == "stop":
        dur, f, att, tau = 0.40, 988.0, 0.012, 0.10
        curve = lambda t: 1.0 - 0.16 * (1 - np.exp(-t / 0.12))
    else:
        dur, f, att, tau = 0.44, 440.0, 0.05, 0.12
        curve = lambda t: 1.0 - 0.06 * (1 - np.exp(-t / 0.15))
    t = time_axis(dur)
    vib = 1.0 + 0.004 * np.sin(2 * np.pi * 5.5 * t)
    y = partials_tone(f, dur, p, tau=1e9, attack=att, freq_curve=lambda tt: curve(tt) * vib)
    if kind == "start":
        env = np.sin(np.pi * np.clip(t / dur, 0, 1) ** 0.8) ** 1.5
        env = np.where(t < 0.2, env, env * 1.0)
        y = y * env
    else:
        y = y * np.exp(-t / tau)
    return lowpass(y, 6000), dur


def click_family(kind, rng):
    """Very minimal: two tiny damped resonances (like soft taps)."""
    def tap(freq, tau):
        t = time_axis(0.05)
        body = np.sin(2 * np.pi * freq * t) * np.exp(-t / tau)
        noise = lowpass(highpass(rng.normal(0, 1, len(t)), 1500), 6000) * np.exp(-t / 0.0015) * 0.5
        return (body + noise) * smooth_attack(len(t), 0.0005)
    if kind == "start":
        total, ev = 0.13, [(0.0, 2300.0, 0.7), (0.07, 3300.0, 1.0)]
    elif kind == "stop":
        total, ev = 0.16, [(0.0, 3300.0, 0.9), (0.08, 2000.0, 0.9)]
    else:
        total, ev = 0.22, [(0.0, 900.0, 1.0), (0.10, 700.0, 1.0)]
    notes = [(t0, tap(f, 0.006 if kind != "error" else 0.012), g) for t0, f, g in ev]
    return mix_notes(total, notes), total


SPECIAL_FAMILIES = {"water": water_drop, "breath": breath, "bowed": bowed, "click": click_family}

SETS = [
    # id, Russian title, family
    ("crystal", "Хрусталь", "crystal/glass chimes"),
    ("marimba", "Тёплая маримба", "wooden marimba"),
    ("water", "Капля", "water drop"),
    ("bell", "Мягкий колокольчик", "soft bell"),
    ("breath", "Лёгкое дыхание", "airy breath"),
    ("piano", "Фетровое пианино", "felt piano"),
    ("musicbox", "Музыкальная шкатулка", "music box"),
    ("harp", "Арфа", "harp pluck"),
    ("bowed", "Смычок по стеклу", "bowed glass"),
    ("click", "Тихий щелчок", "silent click (minimal)"),
]


def render(set_id, kind):
    seed = int(hashlib.sha256(f"{set_id}/{kind}".encode()).hexdigest()[:8], 16)
    rng = np.random.default_rng(seed)
    if set_id in NOTE_FAMILIES:
        fam = NOTE_FAMILIES[set_id]
        notes, total = fam[kind]
        x = sequence(fam["voice"], notes, total, rng)
        dur = total
    else:
        x, dur = SPECIAL_FAMILIES[set_id](kind, rng)
    return finish(x, dur)

# ----------------------------------------------------------------- encode / decode

def write_wav(path, x):
    pcm = np.round(np.clip(x, -1, 1) * 32767).astype("<i2")
    import wave
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def encode_ogg(x, path):
    with tempfile.TemporaryDirectory() as d:
        wav = os.path.join(d, "a.wav")
        write_wav(wav, x)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-fflags", "+bitexact", "-flags:a", "+bitexact",
                        "-i", wav, "-map_metadata", "-1", "-c:a", "libvorbis", "-b:a", "64k", "-ar", str(SR), "-ac", "1",
                        "-fflags", "+bitexact", path], check=True)


def decode_ogg(path):
    r = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
                       check=True, capture_output=True)
    return np.frombuffer(r.stdout, dtype="<f4").astype(np.float64)


def generate():
    os.makedirs(OUT, exist_ok=True)
    manifest = []
    total = 0
    for set_id, title, family in SETS:
        entry = {"id": set_id, "title_ru": title, "family": family, "durationMs": {}}
        for kind in ("start", "stop", "error"):
            x = render(set_id, kind)
            x, info = normalise(x)
            name = f"{set_id}_{kind}.ogg"
            encode_ogg(x, os.path.join(OUT, name))
            entry[kind] = name
            entry["durationMs"][kind] = int(round(len(x) * 1000.0 / SR))
            size = os.path.getsize(os.path.join(OUT, name))
            total += size
            assert size <= MAX_FILE_BYTES, (name, size)
        entry["license"] = "generated (CC0)"
        manifest.append(entry)
    assert total <= MAX_TOTAL_BYTES, total
    ordered = []
    for e in manifest:
        ordered.append({"id": e["id"], "title_ru": e["title_ru"], "family": e["family"], "start": e["start"],
                        "stop": e["stop"], "error": e["error"],
                        "durationMs": {"start": e["durationMs"]["start"], "stop": e["durationMs"]["stop"],
                                       "error": e["durationMs"]["error"]},
                        "license": e["license"]})
    with open(os.path.join(OUT, "sounds.json"), "w", encoding="utf-8") as f:
        json.dump(ordered, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"generated {len(manifest) * 3} files, {total} bytes total")

# ----------------------------------------------------------------- analysis

def frames_track(x, kind="peak", win=0.020, hop=0.005):
    """Per-frame peak frequency / centroid for frames within 30 dB of the loudest frame."""
    n, h = int(win * SR), int(hop * SR)
    window = np.hanning(n)
    nfft = 4096
    fr = np.fft.rfftfreq(nfft, 1.0 / SR)
    rows = []
    for i in range(0, max(1, len(x) - n), h):
        seg = x[i:i + n]
        if len(seg) < n:
            break
        s = np.abs(np.fft.rfft(seg * window, nfft))
        e = float(np.sum(s * s))
        sel = fr > 150
        if kind == "peak":
            # lowest strong spectral peak ~ fundamental
            ss, ff = s[sel], fr[sel]
            loc = np.where((ss[1:-1] > ss[:-2]) & (ss[1:-1] >= ss[2:]) & (ss[1:-1] > ss.max() * 0.3))[0] + 1
            v = ff[loc[0]] if len(loc) else ff[int(np.argmax(ss))]
        else:
            v = float(np.sum(fr[sel] * s[sel]) / max(float(np.sum(s[sel])), 1e-12))
        rows.append((i / SR, e, v))
    a = np.array(rows)
    if len(a) == 0:
        return a
    return a[a[:, 1] > a[:, 1].max() * 1e-3]


def lowest_peak_hz(seg, before=None):
    """Fundamental estimate: lowest strong spectral peak (>15% of max) above 150 Hz, parabolic interpolation.
    With `before` (the preceding segment) a still-ringing earlier note is subtracted from the spectrum."""
    nfft = 8192
    sp = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), nfft))
    if before is not None and len(before) == len(seg):
        sp = np.maximum(sp - np.abs(np.fft.rfft(before * np.hanning(len(before)), nfft)), 0.0)
    fr = np.fft.rfftfreq(nfft, 1.0 / SR)
    lo = int(np.searchsorted(fr, 150.0))
    ss = sp[lo:]
    loc = np.where((ss[1:-1] > ss[:-2]) & (ss[1:-1] >= ss[2:]) & (ss[1:-1] > ss.max() * 0.15))[0] + 1
    k = (loc[0] if len(loc) else int(np.argmax(ss))) + lo
    if 0 < k < len(sp) - 1:
        a_, b_, c_ = np.log(sp[k - 1] + 1e-12), np.log(sp[k] + 1e-12), np.log(sp[k + 1] + 1e-12)
        k = k + 0.5 * (a_ - c_) / (a_ - 2 * b_ + c_ + 1e-12)
    return float(k * SR / nfft)


def onsets(x, hop=0.003, win=0.010):
    """Note onsets: frames whose energy jumps >=2x over the frame 6 ms earlier, above -35 dB of the max."""
    n, h = int(win * SR), int(hop * SR)
    e = np.array([float(np.sum(x[i:i + n] ** 2)) for i in range(0, len(x) - n, h)])
    if len(e) == 0:
        return []
    thr = e.max() * 10 ** (-3.5)
    e = np.concatenate(([0.0, 0.0], e))
    out, last = [], -10
    for i in range(2, len(e)):
        if e[i] > thr and e[i] > 2 * e[i - 2] and i - last > int(0.03 / hop):
            out.append((i - 2) * h / SR)
            last = i
    return out


def pitch_change_semitones(x):
    """(#onsets, semitones from first to last onset). One onset: slope-based glide over the active span."""
    ons = onsets(x)
    if len(ons) >= 2:
        f = []
        for t in (ons[0], ons[-1]):
            i = int((t + 0.015) * SR)
            n = int(0.035 * SR)
            f.append(lowest_peak_hz(x[i:i + n], x[max(0, i - n):i] if i - n >= 0 else None))
        return len(ons), 12 * math.log2(f[1] / f[0])
    a = frames_track(x, "peak")
    if len(a) < 3:
        return len(ons), 0.0
    k = max(1, len(a) // 4)
    f0 = np.median(a[:k, 2])
    f1 = np.median(a[-k:, 2])
    return len(ons), 12 * math.log2(f1 / f0)


def centroid_trend(x):
    """Semitones between the spectral centroid of the first and last quarter of the active frames."""
    a = frames_track(x, "centroid")
    if len(a) < 4:
        return 0.0
    k = max(1, len(a) // 4)
    return 12 * math.log2(np.median(a[-k:, 2]) / np.median(a[:k, 2]))


def xcorr_max(a, b):
    n = len(a) + len(b)
    m = 1 << (n - 1).bit_length()
    c = np.fft.irfft(np.fft.rfft(a, m) * np.conj(np.fft.rfft(b, m)), m)
    return float(np.max(np.abs(c)) / math.sqrt(np.sum(a * a) * np.sum(b * b)))


def analyse(path):
    x = decode_ogg(path)
    pk = float(np.max(np.abs(x)))
    t_end = x[-int(0.010 * SR):-int(0.003 * SR) or None]
    tail_db = 20 * math.log10(max(float(np.max(np.abs(t_end))), 1e-9) / pk)
    return {
        "x": x,
        "ms": len(x) * 1000.0 / SR,
        "peak_db": 20 * math.log10(max(pk, 1e-12)),
        "lufs": clip_lufs(x),
        "centroid": float(np.mean(frames_track(x, "centroid")[:, 2])) if len(frames_track(x, "centroid")) else 0.0,
        "onsets": pitch_change_semitones(x)[0],
        "pitch_slope": pitch_change_semitones(x)[1],
        "centroid_slope": centroid_trend(x),
        "tail_db": tail_db,
        "bytes": os.path.getsize(path),
    }


def ffmpeg_lufs(path):
    """Cross-check: ffmpeg ebur128 on the file looped to 3 s is NOT meaningful; report momentary max instead."""
    r = subprocess.run(["ffmpeg", "-nostats", "-i", path, "-af", "ebur128=peak=true", "-f", "null", "-"],
                       capture_output=True, text=True)
    last = [l for l in r.stderr.splitlines() if "Peak:" in l or "I:" in l]
    return " ".join(s.strip() for s in last[-2:])


def check(update_doc):
    with open(os.path.join(OUT, "sounds.json"), encoding="utf-8") as f:
        man = {"sets": json.load(f)}
    lines = ["| набор | файл | мс | байт | пик dBFS | LUFS клипа | центроид Гц | нот | Δвысоты, полутон (первая→последняя нота / глиссандо)| Δцентроида, полутон | хвост dB |",
             "|---|---|---|---|---|---|---|---|---|---|---|"]
    contrast = ["| набор | разные направления? | макс. корреляция start/stop |", "|---|---|---|"]
    total = 0
    bad = []
    for s in man["sets"]:
        res = {}
        for kind in ("start", "stop", "error"):
            r = analyse(os.path.join(OUT, s[kind]))
            res[kind] = r
            total += r["bytes"]
            lines.append(f"| {s['id']} | {kind} | {r['ms']:.0f} | {r['bytes']} | {r['peak_db']:.1f} | {r['lufs']:.1f} | "
                         f"{r['centroid']:.0f} | {r['onsets']} | {r['pitch_slope']:+.1f} | {r['centroid_slope']:+.1f} | {r['tail_db']:.0f} |")
            lo, hi = {"start": (120, 350), "stop": (150, 400), "error": (100, 450)}[kind]
            if not (lo <= r["ms"] <= hi + 15):
                bad.append(f"{s['id']}/{kind}: duration {r['ms']:.0f} ms out of [{lo},{hi}]")
            if r["peak_db"] > PEAK_CEIL_DB + 0.3:
                bad.append(f"{s['id']}/{kind}: peak {r['peak_db']:.1f}")
            if r["bytes"] > MAX_FILE_BYTES:
                bad.append(f"{s['id']}/{kind}: size")
        ps, pt = res["start"], res["stop"]
        sign = "да" if ps["pitch_slope"] > 0 > pt["pitch_slope"] else "НЕТ"
        if sign == "НЕТ":
            bad.append(f"{s['id']}: start does not rise / stop does not fall")
        contrast.append(f"| {s['id']} | {sign} (start {ps['pitch_slope']:+.1f}, stop {pt['pitch_slope']:+.1f} полутона) | "
                        f"{xcorr_max(ps['x'], pt['x']):.2f} |")
    summary = f"Всего ассетов: {total} байт ({total / 1024:.0f} КБ), файлов {len(man['sets']) * 3}."
    text = "\n".join(lines) + "\n\n" + "\n".join(contrast) + "\n\n" + summary + "\n"
    print(text)
    print("PROBLEMS:" if bad else "no problems", *bad, sep="\n")
    if update_doc:
        a, b = "<!-- METRICS-BEGIN -->", "<!-- METRICS-END -->"
        d = open(DOC, encoding="utf-8").read()
        i, j = d.index(a) + len(a), d.index(b)
        open(DOC, "w", encoding="utf-8").write(d[:i] + "\n" + text + d[j:])
    return 1 if bad else 0


if __name__ == "__main__":
    if "--check" in sys.argv:
        sys.exit(check("--update-doc" in sys.argv))
    generate()
