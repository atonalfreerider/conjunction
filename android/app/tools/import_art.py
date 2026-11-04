#!/usr/bin/env python3
"""Copies the generated artwork from ../../../art into the app's resource folders.

The source PNGs are ~2.3 MB each at 1024 px. The app ships them as lossy WebP, downscaled to
what a phone screen needs, with alpha kept for the transparent overlays:

  square art (zodiac, chinese, blood, elements, medallions)   -> 512 x 512
  tall art   (backgrounds, popups, frames, palm guide)        -> 720 x 1080
  adaptive-icon foreground                                     -> mipmap-<density>/ic_launcher_foreground.webp

Names follow SPEC "Art asset manifest": snake_case with a group prefix (zodiac_leo, chinese_rat,
blood_ab, popup_birthday_twins, ...). Re-run after the art changes:

    python3 -I android/app/tools/import_art.py

Requires Pillow with WebP support (pip install pillow).
"""
from __future__ import annotations

import pathlib
import sys

from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
APP = HERE.parent
ART = APP.parent.parent / "art"
RES = APP / "src" / "main" / "res"
NODPI = RES / "drawable-nodpi"

SQUARE = 512
TALL = (720, 1080)
QUALITY = 82

# (source relative to art/, resource name, target size)
GROUPS: list[tuple[str, str, object]] = []
for sign in "aries taurus gemini cancer leo virgo libra scorpio sagittarius capricorn aquarius pisces".split():
    GROUPS.append((f"zodiac/{sign}.png", f"zodiac_{sign}", SQUARE))
for animal in "rat ox tiger rabbit dragon snake horse goat monkey rooster dog pig".split():
    GROUPS.append((f"chinese/{animal}.png", f"chinese_{animal}", SQUARE))
for blood in "a b ab o".split():
    GROUPS.append((f"blood/{blood}.png", f"blood_{blood}", SQUARE))
for element in "wood fire earth metal water".split():
    GROUPS.append((f"elements/{element}.png", f"element_{element}", SQUARE))
GROUPS += [
    ("backgrounds/bg_starfield.png", "bg_starfield", TALL),
    ("backgrounds/bg_onboarding_1.png", "bg_onboarding_1", TALL),
    ("backgrounds/bg_onboarding_2.png", "bg_onboarding_2", TALL),
    ("backgrounds/bg_onboarding_3.png", "bg_onboarding_3", TALL),
    ("popups/match_nearby.png", "popup_match_nearby", TALL),
    ("popups/birthday_twins.png", "popup_birthday_twins", TALL),
    ("popups/its_a_conjunction.png", "popup_its_a_conjunction", TALL),
    ("popups/friend_connected.png", "popup_friend_connected", SQUARE),
    ("frames/card_frame.png", "frame_card", TALL),
    ("match/swipe_card_frame.png", "frame_swipe_card", TALL),
    ("match/medallion_frame.png", "match_medallion_frame", SQUARE),
    ("match/merge_emblem.png", "match_merge_emblem", SQUARE),
    ("match/merge_burst.png", "match_merge_burst", SQUARE),
    ("match/twin_burst.png", "match_twin_burst", SQUARE),
    ("palm/palm_guide_overlay.png", "palm_guide_overlay", TALL),
    ("palm/palm_lines_diagram.png", "palm_lines_diagram", SQUARE),
    ("icon/app_icon_1024.png", "brand_mark", SQUARE),
]

# Adaptive icon foreground: 108 dp canvas per density.
ICON_DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def save_webp(image: Image.Image, target: pathlib.Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target, "WEBP", quality=QUALITY, method=6)


def fit(image: Image.Image, size: object) -> Image.Image:
    if isinstance(size, int):
        box = (size, size)
    else:
        box = size  # type: ignore[assignment]
    return image.resize(box, Image.LANCZOS)


def main() -> int:
    if not ART.is_dir():
        print(f"art folder not found: {ART}", file=sys.stderr)
        return 1
    missing = []
    for source, name, size in GROUPS:
        path = ART / source
        if not path.exists():
            missing.append(source)
            continue
        with Image.open(path) as img:
            img = img.convert("RGBA" if img.mode in ("RGBA", "LA", "P") else "RGB")
            save_webp(fit(img, size), NODPI / f"{name}.webp")
    with Image.open(ART / "icon" / "app_icon_foreground_1024.png") as fg:
        fg = fg.convert("RGBA")
        for density, px in ICON_DENSITIES.items():
            save_webp(fg.resize((px, px), Image.LANCZOS), RES / f"mipmap-{density}" / "ic_launcher_foreground.webp")
    # Splash-screen icon: the API wants the glyph inside a 2/3 circle of a 288 dp canvas; the
    # foreground art already leaves that margin, so it is reused at xxxhdpi size.
    with Image.open(ART / "icon" / "app_icon_foreground_1024.png") as fg:
        save_webp(fg.convert("RGBA").resize((768, 768), Image.LANCZOS), NODPI / "splash_mark.webp")
    if missing:
        print("missing art: " + ", ".join(missing), file=sys.stderr)
        return 2
    print(f"imported {len(GROUPS)} images into {NODPI}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
