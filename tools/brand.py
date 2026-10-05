#!/usr/bin/env python3
"""
Mara IPTV logo setini (tek parca marka sayfasi) uygulama kaynaklarina boler.

Kullanim:  python tools/brand.py [branding/marka-seti.png]
Gereken:   pip install pillow

Uretilenler (app/src/main/res altinda):
  drawable-{mdpi..xxxhdpi}/ic_launcher.png   uygulama ikonu (koseleri seffaf yuvarlatilmis)
  drawable-xhdpi/banner.png                  Android TV banner (640x360)
  drawable-nodpi/splash_art.png              acilis / profil ekrani gorseli
  drawable-nodpi/logo_text.png               "Mara IPTV Player" yazi logosu
  ../assets/logo.png, ../assets/logo_text.png  web panel icin
Yer tutucu XML'ler (ic_launcher.xml, banner.xml, logo_text.xml, splash_art.xml) silinir.
"""
import glob
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
REF = 1254.0  # bolgeler bu genislikteki sayfaya gore olculdu

# (sol, ust, sag, alt) - REF olceginde, biraz genis tutuldu; sonra arka plana gore kirpilir
REGIONS = {
    "icon": (28, 28, 568, 562),
    "banner": (34, 664, 462, 895),
    "splash": (890, 656, 1228, 895),
    "logo_text": (905, 1000, 1215, 1155),
}


def region(img, key):
    s = img.width / REF
    l, t, r, b = REGIONS[key]
    return img.crop((int(l * s), int(t * s), int(r * s), int(b * s)))


def trim(im, tol=38):
    """Koseden alinan arka plan rengine gore iceriye kirp."""
    rgb = im.convert("RGB")
    bg = rgb.getpixel((2, 2))
    w, h = rgb.size
    px = rgb.load()
    minx, miny, maxx, maxy = w, h, -1, -1
    for y in range(0, h, 1):
        for x in range(0, w, 1):
            p = px[x, y]
            if abs(p[0] - bg[0]) + abs(p[1] - bg[1]) + abs(p[2] - bg[2]) > tol:
                if x < minx: minx = x
                if y < miny: miny = y
                if x > maxx: maxx = x
                if y > maxy: maxy = y
    if maxx < 0:
        return im
    return im.crop((minx, miny, maxx + 1, maxy + 1))


def rounded(im, radius_ratio=0.21):
    im = im.convert("RGBA")
    w, h = im.size
    scale = 4
    mask = Image.new("L", (w * scale, h * scale), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w * scale - 1, h * scale - 1),
                                           radius=int(min(w, h) * radius_ratio * scale), fill=255)
    mask = mask.resize((w, h), Image.LANCZOS)
    im.putalpha(mask)
    return im


def square(im):
    w, h = im.size
    s = min(w, h)
    return im.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s))


def save(im, *parts):
    path = os.path.join(*parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, optimize=True)
    print("  +", os.path.relpath(path, ROOT))


def remove(name):
    p = os.path.join(RES, "drawable", name)
    if os.path.exists(p):
        os.remove(p)
        print("  -", os.path.relpath(p, ROOT))


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else None
    if not src:
        found = sorted(glob.glob(os.path.join(ROOT, "branding", "*.png")))
        if not found:
            print("branding/ klasorunde logo seti bulunamadi, varsayilan ikonlar kullanilacak.")
            return
        src = found[0]
    print("Logo seti:", src)
    img = Image.open(src).convert("RGB")

    # 1) Uygulama ikonu
    icon = rounded(square(trim(region(img, "icon"))))
    for folder, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        save(icon.resize((size, size), Image.LANCZOS), RES, "drawable-" + folder, "ic_launcher.png")
    save(icon.resize((192, 192), Image.LANCZOS), ASSETS, "logo.png")

    # 2) Android TV banner (16:9)
    banner = trim(region(img, "banner")).convert("RGB")
    save(banner.resize((640, 360), Image.LANCZOS), RES, "drawable-xhdpi", "banner.png")

    # 3) Acilis gorseli
    splash = trim(region(img, "splash")).convert("RGB")
    w = 960
    splash = splash.resize((w, int(w * splash.height / splash.width)), Image.LANCZOS).filter(ImageFilter.SMOOTH)
    save(splash, RES, "drawable-nodpi", "splash_art.png")

    # 4) Yazi logosu: koyu arka plani seffaflastir
    logo = trim(region(img, "logo_text")).convert("RGBA")
    px = logo.load()
    bg = logo.getpixel((1, 1))
    for y in range(logo.height):
        for x in range(logo.width):
            r, g, b, a = px[x, y]
            d = abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2])
            if d < 60:
                px[x, y] = (r, g, b, int(255 * d / 60))
    save(logo, RES, "drawable-nodpi", "logo_text.png")
    save(logo, ASSETS, "logo_text.png")

    for n in ("ic_launcher.xml", "banner.xml", "logo_text.xml", "splash_art.xml"):
        remove(n)
    print("Tamam.")


if __name__ == "__main__":
    main()
