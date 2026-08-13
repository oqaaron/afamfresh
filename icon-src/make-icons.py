#!/usr/bin/env python3
"""
Turn the three brand PNGs into per-flavour Android launcher icons.

WHY THE SOURCE IMAGES CANNOT BE USED AS-IS

Each PNG has a white rounded-square plate baked into it. Android's adaptive
icons apply the launcher's OWN mask -- a circle on Pixel, a squircle on Samsung,
a rounded square elsewhere -- so shipping the plate gives you a rounded square
floating inside a circle, with white corners the launcher never asked for.

So the plate is removed. White is flood-filled away from the four corners
inwards, which strips the background while preserving the white *inside* the
mark -- the negative-space stroke running through the leaf, which is part of the
design and must survive.

THE SAFE ZONE

An adaptive icon is 108x108dp but only the central 66dp is guaranteed visible;
launchers crop and animate within the rest. The art is therefore scaled to
~60% of the canvas and centred. Ignoring this is why so many icons look
clipped on one phone and tiny on another.

Legacy PNGs are emitted too. minSdk is 24, and adaptive icons only arrive at
26, so API 24-25 devices need a plain square icon or they fall back to a
default Android robot.

Run once the three sources are in place:
    python3 icon-src/make-icons.py
"""

import os
import sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "icon-src")

FLAVOURS = ["customer", "rider", "vendor"]

# Legacy launcher icon sizes, in the density buckets Android expects.
LEGACY = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# Adaptive foreground: 108dp canvas at each density.
FOREGROUND = {
    "drawable-mdpi": 108,
    "drawable-hdpi": 162,
    "drawable-xhdpi": 216,
    "drawable-xxhdpi": 324,
    "drawable-xxxhdpi": 432,
}

# Fraction of the 108dp canvas the artwork occupies. The safe zone is 66/108 =
# 0.61; 0.60 sits just inside it so nothing touches the crop boundary.
SAFE_FRACTION = 0.60

# The plate colour, reused as the adaptive background so the icon looks the same
# as the source once the launcher masks it.
BACKGROUND_HEX = "#FFFFFF"


def strip_plate(img: Image.Image, tolerance: int = 52) -> Image.Image:
    """
    Remove the white background, keeping white that is enclosed by the artwork.

    A flood fill from each corner, rather than "make every white pixel
    transparent" -- the latter would punch holes through the white stroke that
    runs through the leaf, which is the most recognisable part of the mark.

    The tolerance is wide enough to take the plate's DROP SHADOW with it. At a
    tight tolerance the shadow survives as a soft grey arc hugging one edge of
    the mark, which reads as a rendering fault once the launcher masks the icon
    to a circle. Light grey is nowhere near the lightest green in the palette,
    so a wide tolerance cannot eat the artwork.
    """
    img = img.convert("RGBA")
    w, h = img.size
    px = img.load()

    def is_plate(p):
        r, g, b, a = p
        return a > 0 and r >= 255 - tolerance and g >= 255 - tolerance and b >= 255 - tolerance

    # Iterative flood fill from the corners. Explicit stack rather than
    # recursion: a 1024x1024 image would blow the interpreter's stack depth.
    stack = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
    seen = set()
    while stack:
        x, y = stack.pop()
        if (x, y) in seen or not (0 <= x < w and 0 <= y < h):
            continue
        seen.add((x, y))
        if not is_plate(px[x, y]):
            continue
        px[x, y] = (255, 255, 255, 0)
        stack.extend([(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)])

    return img


def trim(img: Image.Image) -> Image.Image:
    """Crop to the artwork, so scaling is driven by the mark and not the margin."""
    bbox = img.getbbox()
    return img.crop(bbox) if bbox else img


def build(flavour: str) -> None:
    src_path = os.path.join(SRC, f"{flavour}.png")
    if not os.path.isfile(src_path):
        print(f"  ! {flavour}.png not found — skipped")
        return

    art = trim(strip_plate(Image.open(src_path)))
    res = os.path.join(ROOT, "app", "src", flavour, "res")

    # ---- adaptive foreground -------------------------------------------
    for folder, canvas in FOREGROUND.items():
        target = int(canvas * SAFE_FRACTION)
        scaled = art.copy()
        scaled.thumbnail((target, target), Image.LANCZOS)

        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        layer.paste(
            scaled,
            ((canvas - scaled.width) // 2, (canvas - scaled.height) // 2),
            scaled,
        )

        out = os.path.join(res, folder)
        os.makedirs(out, exist_ok=True)
        layer.save(os.path.join(out, "ic_launcher_foreground.png"))

    # ---- legacy square icons (API 24-25) -------------------------------
    for folder, size in LEGACY.items():
        # Slightly larger fraction than the adaptive safe zone: nothing crops a
        # legacy icon, so the art can fill more of it without risk.
        target = int(size * 0.78)
        scaled = art.copy()
        scaled.thumbnail((target, target), Image.LANCZOS)

        flat = Image.new("RGBA", (size, size), (255, 255, 255, 255))
        flat.paste(
            scaled,
            ((size - scaled.width) // 2, (size - scaled.height) // 2),
            scaled,
        )

        out = os.path.join(res, folder)
        os.makedirs(out, exist_ok=True)
        flat.save(os.path.join(out, "ic_launcher.png"))
        flat.save(os.path.join(out, "ic_launcher_round.png"))

    # ---- splash artwork ------------------------------------------------
    # One 512px copy on the plate colour, for the Compose splash screen. Kept
    # separate from the launcher icon because the splash is not masked and can
    # show the mark at its natural proportions.
    splash = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    art_splash = art.copy()
    art_splash.thumbnail((420, 420), Image.LANCZOS)
    splash.paste(
        art_splash,
        ((512 - art_splash.width) // 2, (512 - art_splash.height) // 2),
        art_splash,
    )
    out = os.path.join(res, "drawable-xxxhdpi")
    os.makedirs(out, exist_ok=True)
    splash.save(os.path.join(out, "brand_mark.png"))

    # ---- XML ------------------------------------------------------------
    anydpi = os.path.join(res, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n'
        "</adaptive-icon>\n"
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w") as fh:
            fh.write(adaptive)

    values = os.path.join(res, "values")
    os.makedirs(values, exist_ok=True)
    with open(os.path.join(values, "ic_launcher_background.xml"), "w") as fh:
        fh.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<resources>\n"
            f'    <color name="ic_launcher_background">{BACKGROUND_HEX}</color>\n'
            "</resources>\n"
        )

    print(f"  ✓ {flavour}: adaptive + legacy icons and splash mark written")


if __name__ == "__main__":
    print("Building launcher icons from icon-src/…")
    for flavour in FLAVOURS:
        build(flavour)
    print("Done. Rebuild the apps to see them.")