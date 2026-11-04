#!/usr/bin/env python3
"""Copies the generated artwork from art/ into the iOS asset catalog (ios/App/Resources/Assets.xcassets).

Same names and sizes as the Android import (android/app/tools/import_art.py), so both apps show the
same pictures: square art 512 x 512, tall art 720 x 1080. Opaque paintings ship as JPEG (quality 85),
transparent overlays as PNG with alpha. The app icon is art/icon/ios_icon_1024.png (opaque, as the
App Store requires), and splash_mark is the transparent glyph used on the welcome page and card backs.

    python3 -I ios/App/tools/import_art.py

Requires Pillow (pip install pillow). Re-run after the art changes, then regenerate the project.
"""
from __future__ import annotations

import json
import pathlib
import shutil
import sys

from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
APP = HERE.parent
ART = APP.parent.parent / "art"
CATALOG = APP / "Resources" / "Assets.xcassets"
ART_FOLDER = CATALOG / "Art"

SQUARE = (512, 512)
TALL = (720, 1080)

GROUPS: list[tuple[str, str, tuple[int, int]]] = []
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
    ("icon/app_icon_foreground_1024.png", "splash_mark", (768, 768)),
]

INFO = {"author": "xcode", "version": 1}


def write_json(path: pathlib.Path, obj: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    if not ART.is_dir():
        print(f"art folder not found: {ART}", file=sys.stderr)
        return 1
    if ART_FOLDER.exists():
        shutil.rmtree(ART_FOLDER)
    write_json(CATALOG / "Contents.json", {"info": INFO})
    write_json(ART_FOLDER / "Contents.json", {"info": INFO, "properties": {"provides-namespace": False}})
    missing = []
    for source, name, size in GROUPS:
        path = ART / source
        if not path.exists():
            missing.append(source)
            continue
        folder = ART_FOLDER / f"{name}.imageset"
        folder.mkdir(parents=True, exist_ok=True)
        with Image.open(path) as img:
            has_alpha = img.mode in ("RGBA", "LA", "P")
            img = img.convert("RGBA" if has_alpha else "RGB").resize(size, Image.LANCZOS)
            if has_alpha:
                filename = f"{name}.png"
                img.save(folder / filename, "PNG", optimize=True)
            else:
                filename = f"{name}.jpg"
                img.save(folder / filename, "JPEG", quality=85, optimize=True, progressive=False)
        write_json(folder / "Contents.json", {"images": [{"idiom": "universal", "filename": filename}], "info": INFO})
    # App icon: one 1024 px opaque image; Xcode derives every size.
    icon_dir = CATALOG / "AppIcon.appiconset"
    icon_dir.mkdir(parents=True, exist_ok=True)
    with Image.open(ART / "icon" / "ios_icon_1024.png") as icon:
        icon.convert("RGB").save(icon_dir / "AppIcon-1024.png", "PNG", optimize=True)
    write_json(icon_dir / "Contents.json", {
        "images": [{"filename": "AppIcon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
        "info": INFO,
    })
    # Colours (Celestial Art Nouveau palette): accent = gold leaf, launch = midnight indigo.
    def color(name: str, r: int, g: int, b: int) -> None:
        write_json(CATALOG / f"{name}.colorset" / "Contents.json", {
            "colors": [{"idiom": "universal", "color": {"color-space": "srgb", "components": {
                "red": f"0x{r:02X}", "green": f"0x{g:02X}", "blue": f"0x{b:02X}", "alpha": "1.000"}}}],
            "info": INFO,
        })
    color("AccentColor", 0xD4, 0xAF, 0x37)
    color("LaunchBackground", 0x0B, 0x0A, 0x2A)
    if missing:
        print("missing art: " + ", ".join(missing), file=sys.stderr)
        return 2
    print(f"imported {len(GROUPS)} images into {ART_FOLDER}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
