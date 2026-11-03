#!/usr/bin/env python3
"""
Ana ekran "Canli TV" banner gorselini uretir (Pillow).

Kullanim:  python tools/hero_banner.py
Uretir:    app/src/main/res/drawable-nodpi/hero_banner.png   (1600x620)

Soyut "yayin sinyali" kompozisyonu: koyu zemin, sagda sicak turuncu
halkalar ve oynat simgesi, solda yazi icin bos/koyu alan.
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi", "hero_banner.png")

W, H = 1600, 620
ORANGE = (255, 122, 26)
ORANGE_HI = (255, 184, 108)
DARK_TOP = (18, 22, 31)
DARK_BOT = (7, 10, 14)


def vgrad(size, top, bottom):
    w, h = size
    g = Image.new("RGB", (1, h))
    px = g.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    return g.resize((w, h), Image.BILINEAR)


def radial(size, cx, cy, radius, color, strength):
    """Kucuk cozunurlukte hesaplanip buyutulen yumusak isik."""
    w, h = size
    sw, sh = max(2, w // 10), max(2, h // 10)
    layer = Image.new("RGBA", (sw, sh), (0, 0, 0, 0))
    px = layer.load()
    sx, sy = sw / w, sh / h
    scx, scy, sr = cx * sx, cy * sy, radius * sx
    for y in range(sh):
        for x in range(sw):
            d = math.hypot(x - scx, y - scy) / max(1.0, sr)
            if d < 1.0:
                a = int((1.0 - d) ** 2.0 * strength)
                if a > 0:
                    px[x, y] = (color[0], color[1], color[2], a)
    return layer.resize((w, h), Image.LANCZOS)


def main():
    random.seed(7)
    img = vgrad((W, H), DARK_TOP, DARK_BOT).convert("RGBA")

    # Ana sicak isik (sag orta)
    img = Image.alpha_composite(img, radial((W, H), 1290, 310, 760, ORANGE, 150))
    # Ikinci, daha kucuk ve parlak cekirdek
    img = Image.alpha_composite(img, radial((W, H), 1290, 310, 330, ORANGE_HI, 90))
    # Sol altta hafif sicaklik (metin tarafini tamamen koyu tutmamak icin)
    img = Image.alpha_composite(img, radial((W, H), 120, 620, 520, ORANGE, 55))

    # Yayin halkalari
    rings = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(rings)
    cx, cy = 1300, 315
    for i, r in enumerate((105, 190, 280, 375, 475, 580)):
        a = int(120 - i * 16)
        wdt = max(1, 4 - i // 2)
        d.ellipse((cx - r, cy - r * 0.62, cx + r, cy + r * 0.62),
                  outline=(ORANGE[0], ORANGE[1], ORANGE[2], max(18, a)), width=wdt)
    rings = rings.filter(ImageFilter.GaussianBlur(1.6))
    img = Image.alpha_composite(img, rings)

    # Oynat simgesi (cok dusuk alfa, filigran gibi)
    play = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    pd = ImageDraw.Draw(play)
    s = 92
    pd.polygon([(cx - s * 0.55, cy - s), (cx - s * 0.55, cy + s), (cx + s * 1.0, cy)],
               fill=(255, 255, 255, 26))
    # Oynat simgesini cevreleyen ince halka
    pd.ellipse((cx - s * 1.55, cy - s * 1.55, cx + s * 1.55, cy + s * 1.55),
               outline=(ORANGE_HI[0], ORANGE_HI[1], ORANGE_HI[2], 70), width=3)
    play = play.filter(ImageFilter.GaussianBlur(1.2))
    img = Image.alpha_composite(img, play)

    # Capraz isik huzmeleri
    beams = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    bd = ImageDraw.Draw(beams)
    bd.polygon([(760, H), (1030, -40), (1120, -40), (900, H)],
               fill=(255, 150, 70, 16))
    bd.polygon([(980, H), (1250, -40), (1300, -40), (1080, H)],
               fill=(255, 190, 120, 10))
    beams = beams.filter(ImageFilter.GaussianBlur(26))
    img = Image.alpha_composite(img, beams)

    # Soldan saga koyu gecis: yazi okunurlugu
    scrim = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sp = scrim.load()
    for x in range(W):
        t = min(1.0, max(0.0, (x - 380) / 620.0))
        a = int(232 * (1.0 - t))
        if a > 0:
            for y in range(H):
                sp[x, y] = (7, 10, 14, a)
    img = Image.alpha_composite(img, scrim)

    # Ince tane (banding kirici)
    noise = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    np_ = noise.load()
    for y in range(0, H):
        for x in range(0, W):
            v = random.randint(0, 255)
            np_[x, y] = (v, v, v, 5)
    img = Image.alpha_composite(img, noise)

    img.convert("RGB").save(OUT, optimize=True)
    print("yazildi:", OUT)


if __name__ == "__main__":
    main()
