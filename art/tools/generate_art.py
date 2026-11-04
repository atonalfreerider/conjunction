#!/usr/bin/env python3
"""Generate every image in the SPEC.md "Art asset manifest" with the OpenAI Images API.

Art direction: "Celestial Art Nouveau" (see SPEC.md, "Look and feel").

Usage (from any directory):

    python art/tools/generate_art.py                 # generate everything that is missing
    python art/tools/generate_art.py --only zodiac/  # only files whose path contains "zodiac/"
    python art/tools/generate_art.py --only icon/app_icon_1024.png --force   # regenerate one
    python art/tools/generate_art.py --post-only     # just redo derived files + contact sheet
    python art/tools/generate_art.py --dry-run       # print what would be generated

Requirements: ``pip install openai pillow`` (use a virtualenv).

Secrets: the API key is read at runtime from ``~/Desktop/oai-key.txt`` (override with
``--key-file`` or the ``OPENAI_API_KEY_FILE`` env var). The key is never printed, logged,
or written anywhere; it only lives in this process's memory.

Idempotent: a file that already exists is skipped unless ``--force`` is given. ``art/manifest.json``
records file, prompt, model, size, quality and background for every generated image and is merged
(not overwritten) on each run, so partial runs keep earlier entries.

Derived files (always rebuilt by the post-processing step, cheap, no API calls):
  * ``store/feature_graphic_1024x500.png`` - exact 1024x500 centre crop of the wide render
  * ``icon/play_icon_512.png``             - 512x512 RGB, no transparency (Play Console)
  * ``icon/ios_icon_1024.png``             - 1024x1024 RGB, no alpha, flattened onto indigo
  * ``contact_sheet.png``                  - labelled thumbnail grid for review
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import os
import random
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path

ART_DIR = Path(__file__).resolve().parent.parent
MANIFEST = ART_DIR / "manifest.json"
DEFAULT_KEY_FILE = Path.home() / "Desktop" / "oai-key.txt"

# Newest first. The first one the account can see is used unless --model is given.
MODEL_PREFERENCE = [
    "gpt-image-2.5-sunburst",
    "gpt-image-2.5-flare",
    "gpt-image-2",
    "gpt-image-1.5",
    "gpt-image-1",
]

INDIGO = (0x0B, 0x0A, 0x2A)  # SPEC ground colour, used to flatten the iOS icon

# --------------------------------------------------------------------------------------------
# Prompts
# --------------------------------------------------------------------------------------------

STYLE = (
    "Celestial Art Nouveau illustration, one image from a single cohesive premium series for a "
    "luxury astrology app. Palette strictly limited to: deep midnight indigo (#0B0A2A) and "
    "ink-violet grounds; burnished gold leaf with warm highlights (gradients from #D4AF37 to pale "
    "champagne #F5E6A8); rose-copper accents (#C97B63); moonstone pearl lavender-white "
    "(#EDE6F7) for the brightest lights. Alphonse Mucha inspired ornament: sinuous whiplash "
    "curves, ornate filigree, arched halo roundels, stylised lilies and stars. Fine "
    "gold constellation line-work (thin lines joining small bright star points) woven through the "
    "design. Painterly rendering with gilded metallic sheen, delicate engraving-like linework, "
    "soft inner glow, subtle film grain, rich but restrained detail. Elegant, mystical, calm, "
    "adult and refined - not cartoonish, not cute, not neon, no photorealism. "
    "Absolutely no text, no letters, no numbers, no words, no runes, no captions, no signature, "
    "no logo, no watermark, no border text anywhere in the image."
)

# Shared framing so each family reads as a matching set.
ROUNDEL = (
    "Composition: perfectly centred, front-facing emblem inside a circular gold filigree "
    "Art Nouveau roundel medallion with a thin double gold ring and small star studs, "
    "the medallion filling about 85% of a square canvas, flat midnight indigo background "
    "around it with a faint starfield. The subject is a bold, clear, instantly recognisable "
    "silhouette that still reads at small size. Same frame, same lighting, same scale as the "
    "other medallions in the set."
)

ZODIAC_FRAME = ROUNDEL + " Set: the twelve Western zodiac signs. "
CHINESE_FRAME = (
    ROUNDEL.replace("circular gold filigree", "circular gold filigree with a subtle cloud-scroll motif")
    + " Set: the twelve Chinese zodiac animals, each shown in full or three-quarter body, "
    "elegant East-Asian-meets-Art-Nouveau stylisation. "
)
BLOOD_FRAME = (
    ROUNDEL + " Set: the four blood types, each represented symbolically as a single luminous "
    "faceted gemstone droplet (a teardrop of blood rendered as a precious stone) at the centre, "
    "with a distinct ornamental motif behind it. No letters or symbols spelling the blood type. "
)
ELEMENT_FRAME = (
    ROUNDEL + " Set: the five Chinese Wu Xing elements (wood, fire, earth, metal, water), each "
    "shown as a single stylised emblem. "
)


@dataclass(frozen=True)
class Asset:
    file: str             # relative to art/
    subject: str          # per-asset prompt (appended to STYLE)
    size: str = "1024x1024"
    quality: str = "medium"
    background: str = "opaque"   # "opaque" or "transparent"

    def prompt(self) -> str:
        return f"{STYLE}\n\nSubject: {self.subject}"


ZODIAC = {
    "aries": "a powerful ram's head in profile-three-quarter view with large spiralling gold horns, "
             "rose-copper fire-like mane accents, Aries constellation line-work",
    "taurus": "a majestic bull's head facing forward with wide curved horns, a garland of "
              "lilies and roses at its brow, Taurus constellation line-work. Do not draw the "
              "Taurus astrological glyph or any other symbol at the bottom of the medallion",
    "gemini": "two serene androgynous twin figures side by side, faces turned to each other, "
              "hands joined, flowing hair merging into star trails, Gemini constellation line-work",
    "cancer": "an ornate crab with a jewelled shell and raised claws beneath a crescent moon, "
              "pearlescent waves, Cancer constellation line-work",
    "leo": "a regal lion's head facing forward with a radiant sun-ray mane of gold leaf, "
           "Leo constellation line-work",
    "virgo": "a graceful maiden in profile holding a sheaf of wheat, long flowing hair, "
             "Mucha-style halo behind her head, Virgo constellation line-work",
    "libra": "an elegant balanced pair of golden scales hanging level from an ornate beam, "
             "Libra constellation line-work",
    "scorpio": "a sleek scorpion with a raised curved tail and jewelled segments, seen from above, "
               "Scorpio constellation line-work",
    "sagittarius": "a centaur archer drawing a bow aimed up and to the right, the arrow tipped with a star, "
                   "Sagittarius constellation line-work",
    "capricorn": "a sea-goat: front half a mountain goat with curved horns, back half a coiling "
                 "fish tail with fins, Capricorn constellation line-work",
    "aquarius": "a water bearer figure pouring a stream of starlit water from an ornate urn held "
                "on the shoulder, Aquarius constellation line-work",
    "pisces": "two fish swimming in opposite directions forming a circle, joined by a golden "
              "cord, Pisces constellation line-work",
}

CHINESE = {
    "rat": "a clever rat (small rodent, long thin tail, round ears) sitting up holding a gold coin",
    "ox": "a strong ox (bovine, short curved horns, heavy shoulders) standing calmly, side view",
    "tiger": "a striped tiger mid-stride, head turned toward the viewer, bold stripes in gold and indigo",
    "rabbit": "a rabbit with long upright ears sitting beside a full moon",
    "dragon": "a sinuous Chinese long dragon with whiskers and antler-like horns, coiled around a pearl",
    "snake": "a graceful coiled snake with jewelled scales, head raised",
    "horse": "a galloping horse with flowing mane and tail, side view",
    "goat": "a gentle goat (domestic goat with small backward-curving horns and a beard) standing on a rock",
    "monkey": "a playful monkey sitting on a branch holding a peach",
    "rooster": "a proud rooster with a tall comb and a long cascading tail, side view",
    "dog": "a loyal dog sitting upright, alert ears, noble profile",
    "pig": "a round contented pig standing among lotus flowers, side view",
}

BLOOD = {
    "a": "a deep garnet-red faceted gem droplet, behind it a calm ordered pattern of "
         "symmetrical lotus petals - suggesting patience and harmony",
    "b": "a ruby-red faceted gem droplet, behind it free-flowing swirling vines and a comet - "
         "suggesting curiosity and independence",
    "ab": "a faceted gem droplet split vertically into two harmonised halves, one rose-copper "
          "and one deep red, behind it a crescent moon interlocked with a small sun - suggesting "
          "duality and balance",
    "o": "a bright crimson faceted gem droplet, behind it a radiant sunburst with strong "
         "outward rays - suggesting confidence and leadership",
}

ELEMENTS = {
    "wood": "a young tree with spreading branches and fresh leaves growing from a gold seed, bamboo stalks",
    "fire": "a stylised flame in rose-copper and gold rising in elegant Art Nouveau curls",
    "earth": "layered golden mountains above a rich terracotta earth band with a square-cut gem",
    "metal": "a polished gold disc and an elegant bronze bell with a crisp polished metallic sheen",
    "water": "a cresting stylised wave in the manner of Art Nouveau with droplets and a crescent moon reflection",
}

# The app's mark: the "conjunction" idea (two bodies meeting) as a bold, simple silhouette.
GLYPH = (
    "A bold emblem of two glowing spheres meeting: a large burnished gold-leaf orb on the left "
    "and a slightly smaller luminous moonstone-pearl orb on the right, overlapping by about a "
    "quarter like an eclipse, with a brilliant four-point star flare at the point where they "
    "overlap. Smooth solid shapes with gentle metallic shading, clean crisp edges, no thin "
    "details inside the orbs."
)

ASSETS: list[Asset] = [
    # --- icon ---
    Asset(
        "icon/app_icon_1024.png",
        "App icon. " + GLYPH + " The emblem is large, simple, high-contrast and centred, "
        "occupying about 62% of the canvas, on a solid deep midnight indigo field with a soft "
        "radial violet glow behind the orbs. One thin plain gold ring near the edge with four tiny "
        "star studs; no other ornament. It must read clearly at 48 pixels. Square full-bleed "
        "canvas, no rounded corners.",
        quality="high",
    ),
    Asset(
        "icon/app_icon_foreground_1024.png",
        "Android adaptive icon foreground layer. Only this emblem: " + GLYPH + " The emblem "
        "is small and centred: the whole emblem including its glow fits inside a central circle "
        "half the canvas width (Android adaptive-icon safe zone), with wide empty margins. "
        "Fully transparent background: no ring, no frame, no sky, no backdrop, no glow halo "
        "beyond the orbs' own edges.",
        quality="high",
        background="transparent",
    ),
    # --- backgrounds ---
    Asset(
        "backgrounds/bg_starfield.png",
        "Main app background, portrait. A deep midnight indigo night sky with layered starfield "
        "depth, a soft ink-violet nebula haze, sparse gold constellation line-work, faint "
        "filigree arcs in the corners only. Very dark and calm with low contrast in the central "
        "area so white and gold interface text can sit on top. No figures, no planets in the "
        "centre.",
        size="1024x1536",
    ),
    Asset(
        "backgrounds/bg_onboarding_1.png",
        "Onboarding page 1, portrait: 'welcome to the stars'. A tall ornate Art Nouveau arched "
        "window frame of gold filigree opening onto a vast indigo cosmos, a large crescent moon, "
        "a graceful Mucha-style woman reaching toward a star, and a faint plain ring of "
        "constellation star-points behind her (no zodiac glyphs, no symbols, no numerals). Keep the lower 40% darker and simpler for overlaid "
        "interface text.",
        size="1024x1536",
    ),
    Asset(
        "backgrounds/bg_onboarding_2.png",
        "Onboarding page 2, portrait: 'your birth chart'. An ornate golden astrolabe / natal chart "
        "wheel with twelve segments and planet orbs floating in the upper half of the composition, "
        "Mucha-style lilies curling around it, starry indigo sky. Keep the lower 40% darker and "
        "simpler for overlaid interface text.",
        size="1024x1536",
    ),
    Asset(
        "backgrounds/bg_onboarding_3.png",
        "Onboarding page 3, portrait: 'meet your conjunctions'. Two softly glowing gold stars "
        "drawing together across the indigo sky, their trails of constellation lines converging, "
        "a distant city skyline silhouette at the very bottom edge in ink-violet. Keep the lower "
        "40% darker and simpler for overlaid interface text.",
        size="1024x1536",
    ),
    # --- palm ---
    Asset(
        "palm/palm_guide_overlay.png",
        "Camera guide overlay, portrait. A single clean outline of an open RIGHT human hand seen "
        "palm-side toward the viewer, so the THUMB is on the LEFT side of the image and the little "
        "finger on the right; fingers slightly spread, wrist at the bottom, drawn as a smooth "
        "continuous glowing pale gold line about 8 pixels thick with a few tiny star sparkles "
        "along it and small ornamental filigree flourishes only at the four outer corners of the "
        "canvas. The hand fills about 75% of the canvas height, centred. Line art only, the inside "
        "of the hand and everything else fully transparent, no fill, no shading, no background.",
        size="1024x1536",
        background="transparent",
    ),
    Asset(
        "palm/palm_lines_diagram.png",
        "A palmistry chart illustration: an open left palm drawn in elegant pearl and gold line "
        "art on indigo, with the four principal palm lines - heart line, head line, life line, "
        "fate line - each traced in a different glowing colour (rose-copper, gold, pearl, "
        "violet-lilac), and the seven palm mounts suggested with tiny planetary orbs. Framed by a "
        "Mucha-style ornamental border. No labels, no text, no lettering.",
    ),
    # --- popups ---
    Asset(
        "popups/match_nearby.png",
        "Celebration popup art, portrait. Two elegant figures (a man and a woman, or two "
        "androgynous silhouettes) standing apart on a moonlit hill at night, looking up as two "
        "streams of stars converge above them into a single bright conjunction point; gold "
        "constellation lines link them. Ornate Art Nouveau arched frame. Romantic but tasteful.",
        size="1024x1536",
        quality="high",
    ),
    Asset(
        "popups/birthday_twins.png",
        "Extra lavish celebratory popup art, portrait. Two mirrored celestial twin figures in the "
        "style of Gemini, facing each other in perfect symmetry, holding a shared glowing birthday "
        "star between their raised hands, a cascade of golden star-confetti and rose-copper "
        "sparkles raining down, an elaborate double Mucha-style halo arch behind them, abundant "
        "lilies and filigree. More opulent and festive than anything else in the series.",
        size="1024x1536",
        quality="high",
    ),
    Asset(
        "popups/friend_connected.png",
        "Popup art: two interlocking burnished gold rings, each ring traced with tiny constellation "
        "stars, linked at the centre where a bright star flares, rose-copper ribbon flourishes "
        "around them, centred on starry indigo inside a delicate circular filigree roundel.",
        quality="high",
    ),
    # --- frames ---
    Asset(
        "frames/card_frame.png",
        "An ornate portrait tarot-card style frame border: a thick gold-leaf Art Nouveau border "
        "with an arched top, Mucha-style lilies and whiplash curls at the corners, a small "
        "crescent moon at the top centre and a small star at the bottom centre, thin constellation "
        "line accents. The border occupies only the outer 12% of the canvas on each side. The "
        "entire inner area inside the frame is completely empty and fully transparent, and the "
        "outside of the frame is transparent too. No backdrop, no card fill.",
        size="1024x1536",
        background="transparent",
    ),
    # --- store ---
    Asset(
        "store/feature_graphic_wide.png",
        "Wide banner artwork for an app store feature graphic. Panoramic midnight indigo sky; in "
        "the centre a large radiant emblem of two glowing orbs (one gold, one moonstone pearl) "
        "overlapping like an eclipse with a bright four-point star where they meet; left and right, two elegant Art Nouveau figures in profile "
        "face it from a distance, connected by flowing gold constellation lines; a faint zodiac "
        "wheel arcs behind. All important content stays within the horizontal middle band "
        "(top and bottom 15% are only sky) because the image will be cropped to a 2:1 strip.",
        size="1792x880",  # custom size allowed by gpt-image-2.x; ~2.04:1 -> cropped to 1024x500
        quality="high",
    ),
]
# Match animation (two zodiac medallions fly in from opposite corners and merge) + mutual match.
TRANSPARENT_ONLY = (
    " Fully transparent background everywhere outside the drawn elements: no sky, no backdrop, "
    "no vignette, no solid fill."
)
ASSETS += [
    Asset(
        "match/medallion_frame.png",
        "An ornate circular gold-leaf Art Nouveau medallion frame ring, perfectly centred, outer "
        "diameter about 92% of the canvas, ring band about 14% of the radius thick, decorated with "
        "filigree, tiny star studs, small lilies at the four cardinal points and fine constellation "
        "line accents. The large circle inside the ring is completely empty and fully transparent "
        "(it will hold a profile photo)." + TRANSPARENT_ONLY,
        quality="high", background="transparent",
    ),
    Asset(
        "match/merge_emblem.png",
        "A combined conjunction emblem: two interlocking celestial rings of burnished gold leaf, "
        "one traced with tiny constellation stars and one with rose-copper filigree, fused at "
        "their overlap by a brilliant gold eight-point starburst, centred, filling about 75% of "
        "the canvas. Bold clear silhouette." + TRANSPARENT_ONLY,
        quality="high", background="transparent",
    ),
    Asset(
        "match/merge_burst.png",
        "A radial light-burst flare overlay: a bright champagne-gold core with long thin tapering "
        "gold rays of varying length radiating in all directions, scattered small four-point "
        "sparkles and gold dust, centred, rays fading out before the canvas edges. Light only, no "
        "objects." + TRANSPARENT_ONLY,
        quality="high", background="transparent",
    ),
    Asset(
        "match/twin_burst.png",
        "An extra-lavish twin starburst for a celebration: two mirrored radiant gold starbursts "
        "side by side whose rays interweave, surrounded by a ring of rose-copper and pearl star "
        "confetti, tiny crescent moons and Mucha-style filigree curls, symmetrical, centred, "
        "more opulent and festive than a single burst, fading out before the canvas edges."
        + TRANSPARENT_ONLY,
        quality="high", background="transparent",
    ),
    Asset(
        "match/swipe_card_frame.png",
        "An ornate portrait card frame border for a swipeable profile card: a gold-leaf Art "
        "Nouveau border with gently rounded corners, small filigree corner flourishes, a tiny "
        "star at the top centre and a small conjunction of two orbs at the bottom centre, thin "
        "constellation line accents. The border occupies only the outer 8% of the canvas on each "
        "side. The entire inner area inside the frame is completely empty and fully transparent."
        + TRANSPARENT_ONLY,
        size="1024x1536", background="transparent",
    ),
    Asset(
        "popups/its_a_conjunction.png",
        "Celebratory mutual-match popup art, portrait. Two elegant Art Nouveau figures on either "
        "side reaching toward each other, between their hands two glowing orbs (one gold, one "
        "moonstone pearl) merging into a brilliant starburst, gold constellation lines spiralling "
        "outward, a shower of star confetti and rose-copper sparkles, ornate Mucha-style arched "
        "frame with lilies. Joyful, romantic, tasteful. Keep the lower 30% darker and simpler for "
        "overlaid interface text.",
        size="1024x1536", quality="high",
    ),
]
ASSETS += [Asset(f"zodiac/{k}.png", ZODIAC_FRAME + "Subject: " + v) for k, v in ZODIAC.items()]
ASSETS += [Asset(f"chinese/{k}.png", CHINESE_FRAME + "Subject: " + v) for k, v in CHINESE.items()]
ASSETS += [Asset(f"blood/{k}.png", BLOOD_FRAME + "Subject: " + v) for k, v in BLOOD.items()]
ASSETS += [Asset(f"elements/{k}.png", ELEMENT_FRAME + "Subject: " + v) for k, v in ELEMENTS.items()]

# Sizes accepted by the older models (used when falling back).
LEGACY_SIZES = {"1024x1024", "1024x1536", "1536x1024"}

# --------------------------------------------------------------------------------------------
# API
# --------------------------------------------------------------------------------------------


def read_key(path: Path) -> str:
    try:
        key = path.read_text(encoding="utf-8").strip()
    except OSError as e:
        sys.exit(f"Cannot read OpenAI key file {path}: {e.strerror}")
    if not key:
        sys.exit(f"OpenAI key file {path} is empty")
    return key


def pick_model(client, requested: str | None) -> str:
    available = {m.id for m in client.models.list()}
    if requested:
        if requested not in available:
            sys.exit(f"Model {requested!r} is not available to this key")
        return requested
    for m in MODEL_PREFERENCE:
        if m in available:
            return m
    imgs = sorted(a for a in available if a.startswith("gpt-image"))
    if imgs:
        return imgs[-1]
    sys.exit("No gpt-image-* model is available to this key")


def generate_one(client, model: str, asset: Asset, retries: int) -> bytes:
    size = asset.size
    if not model.startswith("gpt-image-2") and size not in LEGACY_SIZES:
        size = "1536x1024"
    kwargs = dict(
        model=model,
        prompt=asset.prompt(),
        size=size,
        quality=asset.quality,
        n=1,
        output_format="png",
        background=asset.background,
    )
    delay = 4.0
    for attempt in range(1, retries + 1):
        try:
            RATE.wait()
            resp = client.images.generate(**kwargs)
            b64 = resp.data[0].b64_json
            if not b64:
                raise RuntimeError("response contained no image data")
            return base64.b64decode(b64)
        except Exception as e:  # noqa: BLE001 - report and retry any API/network error
            msg = str(e).splitlines()[0][:300]
            # Content-policy and bad-request errors will not fix themselves.
            status = getattr(e, "status_code", None)
            if status in (400, 401, 403) or attempt == retries:
                raise RuntimeError(f"{asset.file}: {type(e).__name__}: {msg}") from None
            print(f"  ! {asset.file} attempt {attempt} failed ({type(e).__name__}: {msg}); "
                  f"retrying in {delay:.0f}s", flush=True)
            if status == 429:  # per-minute quota: wait for the window to roll over
                time.sleep(20 + random.random() * 10)
            else:
                time.sleep(delay + random.random() * 2)
                delay = min(delay * 2, 60)
    raise AssertionError("unreachable")


# --------------------------------------------------------------------------------------------
# Manifest + post-processing
# --------------------------------------------------------------------------------------------

_manifest_lock = threading.Lock()


class RateLimiter:
    """Spaces request starts so a run stays under the account's images-per-minute limit."""

    def __init__(self, per_minute: int):
        self.interval = 60.0 / max(1, per_minute)
        self.next_at = 0.0
        self.lock = threading.Lock()

    def wait(self) -> None:
        with self.lock:
            now = time.monotonic()
            start = max(now, self.next_at)
            self.next_at = start + self.interval
        time.sleep(max(0.0, start - now))


RATE = RateLimiter(18)  # account limit observed: 20 images/min for gpt-image models


def load_manifest() -> dict:
    if MANIFEST.exists():
        data = json.loads(MANIFEST.read_text(encoding="utf-8"))
        return {e["file"]: e for e in data.get("images", [])}
    return {}


def save_manifest(entries: dict) -> None:
    # Merge with what is on disk so concurrent/partial runs never drop each other's entries.
    for k, v in load_manifest().items():
        entries.setdefault(k, v)
    ordered = [entries[k] for k in sorted(entries)]
    out = {
        "art_direction": "Celestial Art Nouveau",
        "style_prefix": STYLE,
        "generator": "art/tools/generate_art.py",
        "images": ordered,
    }
    tmp = MANIFEST.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    tmp.replace(MANIFEST)


def post_process(entries: dict) -> None:
    from PIL import Image, ImageDraw, ImageFont

    def derived(file: str, source: str, note: str) -> None:
        src = entries.get(source, {})
        with Image.open(ART_DIR / file) as im:
            w, h = im.size
            mode = im.mode
        entries[file] = {
            "file": file,
            "derived_from": source,
            "processing": note,
            "prompt": src.get("prompt"),
            "model": src.get("model"),
            "size": f"{w}x{h}",
            "mode": mode,
            "quality": src.get("quality"),
        }

    # Feature graphic: exact 1024x500 centre crop of the wide render.
    wide = ART_DIR / "store/feature_graphic_wide.png"
    if wide.exists():
        with Image.open(wide) as im:
            im = im.convert("RGB")
            target = 1024 / 500
            w, h = im.size
            if w / h > target:      # too wide: trim sides
                nw = round(h * target)
                box = ((w - nw) // 2, 0, (w - nw) // 2 + nw, h)
            else:                   # too tall: trim top/bottom
                nh = round(w / target)
                box = (0, (h - nh) // 2, w, (h - nh) // 2 + nh)
            im.crop(box).resize((1024, 500), Image.LANCZOS).save(
                ART_DIR / "store/feature_graphic_1024x500.png", optimize=True)
        derived("store/feature_graphic_1024x500.png", "store/feature_graphic_wide.png",
                "centre crop to 2.048:1, Lanczos resize to 1024x500, RGB")

    icon = ART_DIR / "icon/app_icon_1024.png"
    if icon.exists():
        with Image.open(icon) as im:
            rgba = im.convert("RGBA")
            flat = Image.new("RGB", rgba.size, INDIGO)
            flat.paste(rgba, mask=rgba.getchannel("A"))
            flat = flat.resize((1024, 1024), Image.LANCZOS)
            flat.save(ART_DIR / "icon/ios_icon_1024.png", optimize=True)
            flat.resize((512, 512), Image.LANCZOS).save(ART_DIR / "icon/play_icon_512.png", optimize=True)
        derived("icon/ios_icon_1024.png", "icon/app_icon_1024.png",
                "flattened onto #0B0A2A, RGB (no alpha channel)")
        derived("icon/play_icon_512.png", "icon/app_icon_1024.png",
                "flattened onto #0B0A2A, Lanczos resize to 512x512, RGB")

    # Contact sheet.
    files = sorted(p for p in ART_DIR.rglob("*.png")
                   if p.name != "contact_sheet.png" and "tools" not in p.parts)
    if not files:
        return
    cell, label_h, cols = 220, 30, 8
    rows = (len(files) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cell, rows * (cell + label_h)), (40, 38, 60))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 12)
    except OSError:
        font = ImageFont.load_default()
    checker = Image.new("RGB", (16, 16), (90, 90, 90))
    ImageDraw.Draw(checker).rectangle((0, 0, 7, 7), fill=(140, 140, 140))
    ImageDraw.Draw(checker).rectangle((8, 8, 15, 15), fill=(140, 140, 140))
    for i, p in enumerate(files):
        x, y = (i % cols) * cell, (i // cols) * (cell + label_h)
        with Image.open(p) as im:
            im = im.convert("RGBA")
            im.thumbnail((cell - 10, cell - 10), Image.LANCZOS)
            bg = Image.new("RGB", im.size)
            for cx in range(0, im.size[0], 16):          # checkerboard shows transparency
                for cy in range(0, im.size[1], 16):
                    bg.paste(checker, (cx, cy))
            bg.paste(im, mask=im.getchannel("A"))
            sheet.paste(bg, (x + (cell - im.size[0]) // 2, y + (cell - im.size[1]) // 2))
        label = str(p.relative_to(ART_DIR))
        draw.text((x + 4, y + cell + 6), label[:34], fill=(237, 230, 247), font=font)
    sheet.save(ART_DIR / "contact_sheet.png", optimize=True)


# --------------------------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------------------------


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--force", action="store_true", help="regenerate files that already exist")
    ap.add_argument("--only", action="append", default=[],
                    help="substring filter on the asset path (repeatable)")
    ap.add_argument("--model", help="force a specific image model id")
    ap.add_argument("--key-file", type=Path,
                    default=Path(os.environ.get("OPENAI_API_KEY_FILE", DEFAULT_KEY_FILE)))
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--retries", type=int, default=6)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--post-only", action="store_true", help="skip generation, rebuild derived files")
    ap.add_argument("--per-minute", type=int, default=18, help="max image requests started per minute")
    args = ap.parse_args()
    RATE.interval = 60.0 / max(1, args.per_minute)

    todo = [a for a in ASSETS
            if (not args.only or any(s in a.file for s in args.only))
            and (args.force or not (ART_DIR / a.file).exists())]

    entries = load_manifest()

    if not args.post_only and todo:
        if args.dry_run:
            for a in todo:
                print(f"would generate {a.file} ({a.size}, {a.quality}, {a.background})")
            return 0
        from openai import OpenAI
        client = OpenAI(api_key=read_key(args.key_file), max_retries=0, timeout=300)
        model = pick_model(client, args.model)
        print(f"Using model {model}; {len(todo)} image(s) to generate", flush=True)

        failures = []

        def work(a: Asset):
            data = generate_one(client, model, a, args.retries)
            out = ART_DIR / a.file
            out.parent.mkdir(parents=True, exist_ok=True)
            tmp = out.with_suffix(".png.tmp")
            tmp.write_bytes(data)
            tmp.replace(out)
            from PIL import Image
            with Image.open(out) as im:
                real = f"{im.size[0]}x{im.size[1]}"
            with _manifest_lock:
                entries[a.file] = {
                    "file": a.file,
                    "prompt": a.prompt(),
                    "model": model,
                    "size": real,
                    "quality": a.quality,
                    "background": a.background,
                    "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                }
                save_manifest(entries)
            return a.file

        with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
            futs = {pool.submit(work, a): a for a in todo}
            for f in as_completed(futs):
                try:
                    print(f"  ok {f.result()}", flush=True)
                except Exception as e:  # noqa: BLE001
                    failures.append(str(e))
                    print(f"  FAILED {e}", flush=True)
        if failures:
            print(f"{len(failures)} failure(s); rerun to retry the missing files", file=sys.stderr)
    elif not todo and not args.post_only:
        print("Nothing to generate (all files exist; use --force to regenerate)")

    by_file = {a.file: a for a in ASSETS}
    for f, a in by_file.items():
        if f not in entries and (ART_DIR / f).exists():
            from PIL import Image
            with Image.open(ART_DIR / f) as im:
                real = f"{im.size[0]}x{im.size[1]}"
            entries[f] = {
                "file": f, "prompt": a.prompt(), "model": None, "size": real,
                "quality": a.quality, "background": a.background,
                "note": "generation metadata not recorded (manifest entry lost); prompt is the "
                        "script's current prompt for this file; regenerate with --force to record",
            }

    post_process(entries)
    save_manifest(entries)
    print("Post-processing done; manifest and contact sheet updated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
