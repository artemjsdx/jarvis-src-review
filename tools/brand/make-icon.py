#!/usr/bin/env python3
"""Brand assets from the generated source pictures: adaptive launcher icon layers and the assistant orb skin.

  python3 tools/brand/make-icon.py [--icon PNG] [--orb PNG] [--review]

Outputs (paths are fixed; see tools/brand/README.md):
  app/res/drawable-nodpi/ic_launcher_foreground.png   432x432, transparent, sphere inside the 66 dp safe circle
  app/res/drawable-nodpi/ic_launcher_monochrome.png   432x432, white alpha ring for themed icons (Android 13+)
  app/res/drawable/ic_launcher_background.xml         linear gradient sampled from the pearl tile
  app/assets/brand/orb.webp                           512x512 circular-alpha skin for OrbView (<= 60 KB)
  build/brand/review.png                              icon masks at 192 and 48 px, orb on dark and light (--review)

Pillow only, deterministic: the same pictures give the same bytes. The sphere is located by a circle fit to the edge
gradient, so a replaced picture needs no hand-measured coordinates.
"""
import argparse
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "tools", "brand", "src")
DEFAULT_ICON = os.path.join(SRC, "pearl-icon-1_gemini.png")
DEFAULT_ORB = os.path.join(SRC, "pearl-orb-1_gpt.png")
RES = os.path.join(ROOT, "app", "res")
ASSETS = os.path.join(ROOT, "app", "assets", "brand")
REVIEW = os.path.join(ROOT, "build", "brand")

LAYER = 432            # adaptive icon layer: 108 dp at xxxhdpi
SAFE_DIAMETER = 264    # 66 dp safe circle inside the 108 dp layer
ORB_SIZE = 512
ORB_MAX_BYTES = 60 * 1024
ORB_EDGE_INSET = 2.5   # the source rim darkens to black over its last 3 px; keep it out of the skin
SS = 4                 # supersampling factor for anti-aliased masks


def fit_circle(gray, r_lo, r_hi):
    """Centre and radius of the strongest circular edge: coarse grid, then a finer pass around the best cell."""
    g = np.asarray(gray.filter(ImageFilter.GaussianBlur(2)), dtype=float)
    gy, gx = np.gradient(g)
    h, w = g.shape
    th = np.linspace(0, 2 * np.pi, 720, endpoint=False)
    ct, st = np.cos(th), np.sin(th)

    def score(cx, cy, r):
        x = np.clip(np.round(cx + r * ct).astype(int), 0, w - 1)
        y = np.clip(np.round(cy + r * st).astype(int), 0, h - 1)
        return float(np.abs(gx[y, x] * ct + gy[y, x] * st).mean())

    best = (w / 2, h / 2, (r_lo + r_hi) / 2, -1.0)
    for cx in range(int(w * 0.42), int(w * 0.58), 6):
        for cy in range(int(h * 0.42), int(h * 0.58), 6):
            for r in range(int(w * r_lo), int(w * r_hi), 4):
                s = score(cx, cy, r)
                if s > best[3]:
                    best = (cx, cy, r, s)
    cx0, cy0, r0, _ = best
    for cx in range(cx0 - 6, cx0 + 7, 2):
        for cy in range(cy0 - 6, cy0 + 7, 2):
            for r in range(r0 - 4, r0 + 5):
                s = score(cx, cy, r)
                if s > best[3]:
                    best = (cx, cy, r, s)
    return best[0], best[1], best[2]


def disc(size, cx, cy, r):
    """Anti-aliased disc mask on a size x size grid."""
    m = Image.new("L", (size * SS, size * SS), 0)
    ImageDraw.Draw(m).ellipse([(cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def square_crop(img, cx, cy, half):
    return img.crop((cx - half, cy - half, cx + half, cy + half))


def sample(img, fx, fy, half=20):
    w, h = img.size
    x, y = int(fx * w), int(fy * h)
    px = np.asarray(img.crop((x - half, y - half, x + half, y + half)), dtype=float)
    return tuple(int(round(v)) for v in px.reshape(-1, 3).mean(0))


def hexrgb(c):
    return "#%02X%02X%02X" % c


def build_icon(src):
    cx, cy, r = fit_circle(src.convert("L"), 0.15, 0.30)
    half = int(round(r)) + 2
    crop = square_crop(src, int(round(cx)), int(round(cy)), half)
    local = half
    side = 2 * half
    rr = r
    alpha = disc(side, local + (cx - round(cx)), local + (cy - round(cy)), rr)

    scale = SAFE_DIAMETER / (2 * rr)
    out_side = int(round(side * scale))
    rgb = crop.resize((out_side, out_side), Image.LANCZOS)
    mask = alpha.resize((out_side, out_side), Image.LANCZOS)
    fg = Image.new("RGBA", (LAYER, LAYER), (0, 0, 0, 0))
    rgb.putalpha(mask)
    off = (LAYER - out_side) // 2
    fg.paste(rgb, (off, off), rgb)

    mono = Image.new("RGBA", (LAYER, LAYER), (0, 0, 0, 0))
    ring_outer = disc(LAYER, LAYER / 2, LAYER / 2, SAFE_DIAMETER / 2)
    ring_inner = disc(LAYER, LAYER / 2, LAYER / 2, SAFE_DIAMETER / 2 * 0.93)
    ring = Image.fromarray(np.clip(np.asarray(ring_outer, dtype=int) - np.asarray(ring_inner, dtype=int), 0, 255).astype(np.uint8))
    dot_r = SAFE_DIAMETER / 2 * 0.2
    dot_c = LAYER / 2 - SAFE_DIAMETER / 2 * 0.42
    dot = disc(LAYER, dot_c, dot_c, dot_r)
    combined = np.maximum(np.asarray(ring, dtype=int), np.asarray(dot, dtype=int)).astype(np.uint8)
    mono.putalpha(Image.fromarray(combined))
    white = Image.new("RGBA", (LAYER, LAYER), (255, 255, 255, 0))
    white.putalpha(mono.getchannel("A"))

    bg_start = sample(src, 0.20, 0.20)
    bg_mid = sample(src, 0.50, 0.12)
    bg_end = sample(src, 0.80, 0.80)
    return fg, white, (bg_start, bg_mid, bg_end), (cx, cy, r)


def background_xml(colors):
    a, b, c = (hexrgb(x) for x in colors)
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- Generated by tools/brand/make-icon.py: linear gradient sampled from the pearl tile. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    xmlns:aapt="http://schemas.android.com/aapt"\n'
        '    android:width="108dp" android:height="108dp"\n'
        '    android:viewportWidth="108" android:viewportHeight="108">\n'
        '    <path android:pathData="M0,0h108v108h-108z">\n'
        '        <aapt:attr name="android:fillColor">\n'
        '            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">\n'
        '                <item android:offset="0" android:color="%s" />\n'
        '                <item android:offset="0.5" android:color="%s" />\n'
        '                <item android:offset="1" android:color="%s" />\n'
        '            </gradient>\n'
        '        </aapt:attr>\n'
        '    </path>\n'
        '</vector>\n' % (a, b, c)
    )


def build_orb(src):
    cx, cy, r = fit_circle(src.convert("L"), 0.33, 0.43)
    half = int(round(r)) + 1
    crop = square_crop(src, int(round(cx)), int(round(cy)), half)
    rgb = crop.resize((ORB_SIZE, ORB_SIZE), Image.LANCZOS)
    alpha = disc(ORB_SIZE, ORB_SIZE / 2, ORB_SIZE / 2, ORB_SIZE / 2 - ORB_EDGE_INSET)
    rgb.putalpha(alpha)
    return rgb, (cx, cy, r)


def encode_orb(img, path):
    for q in (92, 88, 84, 80, 76, 72, 68, 64, 60):
        img.save(path, "WEBP", quality=q, method=6)
        size = os.path.getsize(path)
        if size <= ORB_MAX_BYTES:
            return q, size
    raise SystemExit("orb.webp is above %d bytes even at quality 60" % ORB_MAX_BYTES)


def mask_for(shape, size):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse([0, 0, size - 1, size - 1], fill=255)
    elif shape == "squircle":
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.36, fill=255)
    else:
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.18, fill=255)
    return m


def write_review(fg, mono, colors, orb):
    bg = Image.new("RGBA", (LAYER, LAYER), (0, 0, 0, 255))
    d = ImageDraw.Draw(bg)
    for y in range(LAYER):
        t = y / (LAYER - 1)
        d.line([(0, y), (LAYER, y)], fill=tuple(int(round(colors[0][i] * (1 - t) + colors[2][i] * t)) for i in range(3)))
    icon = Image.alpha_composite(bg, fg)
    sheet = Image.new("RGB", (900, 560), (30, 30, 34))
    x = 10
    for shape in ("circle", "squircle", "rounded"):
        for size, y in ((192, 10), (48, 220)):
            tile = Image.new("RGBA", (size, size), (0, 0, 0, 0))
            tile.paste(icon.resize((size, size), Image.LANCZOS), (0, 0), mask_for(shape, size))
            sheet.paste(tile, (x, y), tile)
        x += 200
    mono_tile = Image.new("RGBA", (192, 192), (40, 40, 48, 255))
    mono_tile.alpha_composite(mono.resize((192, 192), Image.LANCZOS))
    sheet.paste(mono_tile.convert("RGB"), (10, 290))
    for i, back in enumerate(((8, 8, 12, 255), (244, 240, 246, 255))):
        tile = Image.new("RGBA", (240, 240), back)
        tile.alpha_composite(orb.resize((220, 220), Image.LANCZOS), (10, 10))
        sheet.paste(tile.convert("RGB"), (230 + i * 260, 290))
    os.makedirs(REVIEW, exist_ok=True)
    sheet.save(os.path.join(REVIEW, "review.png"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--icon", default=DEFAULT_ICON)
    ap.add_argument("--orb", default=DEFAULT_ORB)
    ap.add_argument("--review", action="store_true")
    args = ap.parse_args()

    fg, mono, colors, icon_fit = build_icon(Image.open(args.icon).convert("RGB"))
    orb, orb_fit = build_orb(Image.open(args.orb).convert("RGB"))

    drawable_nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(drawable_nodpi, exist_ok=True)
    fg.save(os.path.join(drawable_nodpi, "ic_launcher_foreground.png"), optimize=True)
    mono.save(os.path.join(drawable_nodpi, "ic_launcher_monochrome.png"), optimize=True)
    with open(os.path.join(RES, "drawable", "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
        f.write(background_xml(colors))
    os.makedirs(ASSETS, exist_ok=True)
    q, size = encode_orb(orb, os.path.join(ASSETS, "orb.webp"))

    print("icon sphere centre=(%.0f,%.0f) r=%.0f px of %s" % (icon_fit[0], icon_fit[1], icon_fit[2], os.path.basename(args.icon)))
    print("orb sphere centre=(%.0f,%.0f) r=%.0f px of %s" % (orb_fit[0], orb_fit[1], orb_fit[2], os.path.basename(args.orb)))
    print("background " + " ".join(hexrgb(c) for c in colors))
    print("orb.webp quality=%d bytes=%d" % (q, size))
    if args.review:
        write_review(fg, mono, colors, orb)
        print("review -> build/brand/review.png")


if __name__ == "__main__":
    main()
