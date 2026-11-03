#!/usr/bin/env python3
"""
Mara IPTV yeni logo setini sifirdan uretir (Pillow).

Kullanim:  python tools/brand_gen.py
Gereken:   pip install pillow

Uretilenler:
  app/src/main/res/drawable-{mdpi..xxxhdpi}/ic_launcher.png
  app/src/main/res/drawable-xhdpi/banner.png        (640x360 Android TV banner)
  app/src/main/res/drawable-nodpi/logo_mark.png     (seffaf marka isareti, splash animasyonu)
  app/src/main/res/drawable-nodpi/logo_text.png     (yatay logo kilidi)
  app/src/main/res/drawable-nodpi/splash_art.png    (960x540 acilis / profil gorseli)
  app/src/main/assets/logo.png                      (web panel ikonu)
  app/src/main/assets/logo_text.png                 (web panel yazisi)

Yer tutucu vector XML'leri (ic_launcher.xml, banner.xml, logo_text.xml, splash_art.xml) siler.
"""
import math
import os

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
FONTS = "C:/Windows/Fonts"
BLACK = os.path.join(FONTS, "seguibl.ttf")
BOLD = os.path.join(FONTS, "segoeuib.ttf")
SEMI = os.path.join(FONTS, "segoeuisl.ttf")

# Palet
ORANGE = (255, 122, 26)
ORANGE_HI = (255, 176, 92)
ORANGE_DK = (214, 84, 0)
DARK = (11, 13, 18)
DARK2 = (20, 24, 33)
WHITE = (245, 247, 252)


def font(path, size):
    return ImageFont.truetype(path, size)


def gradient(size, top, bottom):
    w, h = size
    g = Image.new("RGB", (1, h))
    px = g.load()
    for y in range(h):
        t = y / max(1, h - 1)
        px[0, y] = (
            int(top[0] + (bottom[0] - top[0]) * t),
            int(top[1] + (bottom[1] - top[1]) * t),
            int(top[2] + (bottom[2] - top[2]) * t),
        )
    return g.resize((w, h), Image.BILINEAR)


def rounded_mask(size, radius, ss=4):
    w, h = size
    mask = Image.new("L", (w * ss, h * ss), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, w * ss - 1, h * ss - 1), radius=int(radius * ss), fill=255)
    return mask.resize((w, h), Image.LANCZOS)


def glow(size, cx, cy, radius, color, strength=170):
    """Yumusak radyal isik (seffaf katman)."""
    w, h = size
    small = (max(2, w // 8), max(2, h // 8))
    layer = Image.new("RGBA", small, (0, 0, 0, 0))
    px = layer.load()
    sx = small[0] / w
    sy = small[1] / h
    scx, scy, sr = cx * sx, cy * sy, radius * sx
    for y in range(small[1]):
        for x in range(small[0]):
            d = math.hypot(x - scx, y - scy) / max(1.0, sr)
            if d < 1.0:
                a = int((1.0 - d) ** 2.2 * strength)
                if a > 0:
                    px[x, y] = (color[0], color[1], color[2], a)
    return layer.resize((w, h), Image.BICUBIC)


def add_glow(base, cx, cy, radius, color, strength=170):
    g = glow(base.size, cx, cy, radius, color, strength).convert("RGBA")
    base.alpha_composite(g)
    return base


def mark(size):
    """Kare marka isareti: turuncu gradient zemin + koyu ekran + play + yorunge halkasi."""
    S = size
    icon_mask = rounded_mask((S, S), S * 0.235)
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bg = gradient((S, S), ORANGE_HI, ORANGE).convert("RGBA")
    bg.putalpha(icon_mask)
    im.alpha_composite(bg)
    # ust-sol isik
    add_glow(im, S * 0.22, S * 0.14, S * 0.55, (255, 226, 186), 120)

    d = ImageDraw.Draw(im)
    # ekran govdesi
    sx0, sy0, sx1, sy1 = S * 0.135, S * 0.215, S * 0.865, S * 0.775
    screen = gradient((int(sx1 - sx0), int(sy1 - sy0)), (32, 38, 52), (12, 15, 21)).convert("RGBA")
    screen.putalpha(rounded_mask(screen.size, S * 0.105))
    im.alpha_composite(screen, (int(sx0), int(sy0)))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((sx0, sy0, sx1, sy1), radius=S * 0.105,
                        outline=(255, 255, 255, 26), width=max(2, int(S * 0.006)))

    # play ucgeni (yumusak golgeli)
    tw, th = S * 0.30, S * 0.34
    cx, cy = S * 0.515, S * 0.495
    tri = [(cx - tw / 2 + S * 0.02, cy - th / 2), (cx - tw / 2 + S * 0.02, cy + th / 2),
           (cx + tw / 2 + S * 0.02, cy)]
    shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).polygon([(x, y + S * 0.012) for x, y in tri], fill=(0, 0, 0, 130))
    shadow = shadow.filter(ImageFilter.GaussianBlur(S * 0.018))
    im.alpha_composite(shadow)
    d = ImageDraw.Draw(im)
    d.polygon(tri, fill=(255, 255, 255, 255))

    # yorunge halkasi (ekranin uzerinden gecer, ikon siniri icinde kalir)
    W2 = S * 2
    ring = Image.new("RGBA", (W2, W2), (0, 0, 0, 0))
    rd = ImageDraw.Draw(ring)
    cxr, cyr = W2 / 2, W2 * 0.545
    rx, ry = S * 1.16, S * 0.40
    box = (cxr - rx, cyr - ry, cxr + rx, cyr + ry)
    rw = int(S * 0.105)
    rd.arc(box, 182, 358, fill=(255, 176, 116, 190), width=rw)   # arka (ust)
    rd.arc(box, 2, 178, fill=(255, 232, 206, 250), width=rw)     # on (alt)
    ring = ring.rotate(-12, resample=Image.BICUBIC, center=(W2 / 2, W2 / 2))
    ring = ring.resize((S, S), Image.LANCZOS)
    ring.putalpha(ImageChops.multiply(ring.split()[3], icon_mask))
    im.alpha_composite(ring)

    # ust kenar parlamasi
    gloss = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(gloss)
    gd.ellipse((-S * 0.30, -S * 0.62, S * 1.30, S * 0.34), fill=(255, 255, 255, 22))
    gloss.putalpha(ImageChops.multiply(gloss.split()[3], icon_mask))
    im.alpha_composite(gloss)
    return im


def text_w(d, s, f, spacing=0):
    if not s:
        return 0
    w = 0
    for ch in s:
        w += d.textlength(ch, font=f) + spacing
    return w - spacing


def draw_spaced(d, xy, s, f, fill, spacing=0):
    x, y = xy
    for ch in s:
        d.text((x, y), ch, font=f, fill=fill)
        x += d.textlength(ch, font=f) + spacing


def wordmark(target_h):
    """'MARA' + 'IPTV PLAYER' dikey yazi logosu (seffaf)."""
    W, H = 1600, 1600
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    f1 = font(BLACK, 560)
    f2 = font(BOLD, 150)
    w1 = text_w(d, "MARA", f1, -10)
    w2 = text_w(d, "IPTV PLAYER", f2, 26)
    x1 = (W - w1) / 2
    x2 = (W - w2) / 2
    d.text((x1, 300), "MARA", font=f1, fill=WHITE)
    draw_spaced(d, (x2, 930), "IPTV PLAYER", f2, ORANGE_HI, 26)
    d.rounded_rectangle(((W - 190) / 2, 1180, (W + 190) / 2, 1192), radius=6, fill=ORANGE)
    bbox = im.getbbox()
    im = im.crop(bbox)
    w, h = im.size
    return im.resize((int(w * target_h / h), target_h), Image.LANCZOS)


def lockup(target_h, gap_ratio=0.36):
    """Marka isareti + yazi logosu yatay kilit."""
    m = mark(target_h)
    wm = wordmark(int(target_h * 0.78))
    gap = int(target_h * gap_ratio)
    W = m.width + gap + wm.width
    H = target_h
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    out.alpha_composite(m, (0, (H - m.height) // 2))
    out.alpha_composite(wm, (m.width + gap, (H - wm.height) // 2))
    return out


def save(im, *parts):
    path = os.path.join(*parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, optimize=True)
    print("  +", os.path.relpath(path, ROOT))


def remove_xml(name):
    p = os.path.join(RES, "drawable", name)
    if os.path.exists(p):
        os.remove(p)
        print("  -", os.path.relpath(p, ROOT))


def banner():
    """640x360 (2x uretilir) Android TV banner."""
    W, H = 1280, 720
    im = Image.new("RGBA", (W, H), DARK + (255,))
    # turuncu isik lekeleri
    add_glow(im, W * 0.06, H * 0.90, W * 0.45, (255, 120, 20), 150)
    add_glow(im, W * 0.97, H * 0.05, W * 0.35, (255, 150, 60), 90)
    # ince dalga cizgileri
    wave = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    wd = ImageDraw.Draw(wave)
    for i, a in ((0, 34), (1, 22), (2, 14)):
        pts = []
        for x in range(0, W + 8, 8):
            y = H * (0.86 - i * 0.05) + math.sin(x / 260.0 + i) * 26
            pts.append((x, y))
        wd.line(pts, fill=(255, 138, 43, a), width=3)
    im.alpha_composite(wave)

    lk = lockup(360, 0.30)
    lk = lk.resize((int(lk.width * 0.78), int(lk.height * 0.78)), Image.LANCZOS)
    im.alpha_composite(lk, (int(W * 0.075), int((H - lk.height) / 2) - 26))

    d = ImageDraw.Draw(im)
    f = font(BOLD, 46)
    draw_spaced(d, (W * 0.078, H * 0.74), "CANLI TV   •   FILM   •   DIZI", f,
                (255, 190, 140, 230), 6)
    im = im.resize((640, 360), Image.LANCZOS).convert("RGB")
    save(im, RES, "drawable-xhdpi", "banner.png")


def _art(with_logo):
    """Logo iceren (acilis) veya yalnizca arka plan olan 960x540 gorsel."""
    W, H = 1920, 1080
    im = Image.new("RGBA", (W, H), DARK + (255,))
    add_glow(im, W * 0.18, H * 0.88, W * 0.55, (255, 110, 10), 175)
    add_glow(im, W * 0.86, H * 0.12, W * 0.42, (255, 160, 70), 80)
    wave = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    wd = ImageDraw.Draw(wave)
    for i in range(5):
        pts = []
        for x in range(0, W + 10, 10):
            y = H * (0.78 + i * 0.045) + math.sin(x / 420.0 + i * 0.8) * 34
            pts.append((x, y))
        wd.line(pts, fill=(255, 145, 50, 26 - i * 4), width=3)
    im.alpha_composite(wave)

    if with_logo:
        lk = lockup(330, 0.34)
        im.alpha_composite(lk, ((W - lk.width) // 2, (H - lk.height) // 2 - 60))
        d = ImageDraw.Draw(im)
        f = font(SEMI, 42)
        s = "CANLI TV   •   FILM   •   DIZI"
        draw_spaced(d, ((W - text_w(d, s, f, 8)) / 2, H * 0.70), s, f, (255, 205, 170, 220), 8)

    return im.resize((960, 540), Image.LANCZOS).convert("RGB")


def splash_art():
    """960x540 acilis / profil ekrani gorseli (logo dahil)."""
    save(_art(True), RES, "drawable-nodpi", "splash_art.png")


def art_bg():
    """960x540 logosuz arka plan: ekranlarin/image'larin uzerinde kullanilir."""
    save(_art(False), RES, "drawable-nodpi", "bg_art.png")


def hero_art():
    """Dikey (portre) ana ekran gorseli: 900x1000, sol altta yogun turuncu isik."""
    W, H = 900, 1000
    im = Image.new("RGBA", (W, H), DARK + (255,))
    add_glow(im, W * 0.10, H * 0.88, W * 0.95, (255, 108, 8), 215)
    add_glow(im, W * 0.92, H * 0.10, W * 0.62, (255, 158, 66), 120)
    add_glow(im, W * 0.62, H * 0.46, W * 0.55, (255, 96, 0), 60)

    wave = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    wd = ImageDraw.Draw(wave)
    for i in range(6):
        pts = []
        for x in range(-20, W + 20, 6):
            y = H * (0.52 + i * 0.075) + math.sin(x / 190.0 + i * 0.9) * 30
            pts.append((x, y))
        wd.line(pts, fill=(255, 150, 60, 30 - i * 3), width=3)
    im.alpha_composite(wave)
    save(im, RES, "drawable-nodpi", "hero_art.png")


def main():
    print("Yeni logo seti uretiliyor...")
    icon = mark(1024)
    for folder, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                         ("xxhdpi", 144), ("xxxhdpi", 192)):
        save(icon.resize((size, size), Image.LANCZOS), RES, "drawable-" + folder, "ic_launcher.png")
    save(icon.resize((192, 192), Image.LANCZOS), ASSETS, "logo.png")
    save(mark(512), RES, "drawable-nodpi", "logo_mark.png")

    lk = lockup(300)
    save(lk, RES, "drawable-nodpi", "logo_text.png")
    save(lk.resize((int(lk.width * 0.66), int(lk.height * 0.66)), Image.LANCZOS),
         ASSETS, "logo_text.png")

    # Sadece yazi logosu (marka isareti olmadan) -> splash / profil ekraninda
    # isaretin altina yerlestirilir; boylece logo iki kez gorunmez.
    save(wordmark(360), RES, "drawable-nodpi", "logo_word.png")

    banner()
    splash_art()
    art_bg()
    hero_art()

    for n in ("ic_launcher.xml", "banner.xml", "logo_text.xml", "splash_art.xml"):
        remove_xml(n)
    print("Tamam.")


if __name__ == "__main__":
    main()
