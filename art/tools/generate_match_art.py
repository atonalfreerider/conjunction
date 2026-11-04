#!/usr/bin/env python3
"""Generate the match-animation art (two zodiac medallions fly in from opposite corners and merge)
plus the mutual-match popup, with the OpenAI Images API.

Kept separate from generate_art.py so the two can run without touching each other's manifest.
It reuses generate_art.STYLE (the "Celestial Art Nouveau" style prefix), so the series looks the same.

Outputs (relative to art/):
  match/medallion_frame.png      1024x1024 transparent: gold medallion ring, empty centre for a photo
  match/merge_emblem.png         1024x1024 transparent: two interlocking rings fused by a starburst
  match/merge_burst.png          1024x1024 transparent: radial gold light-burst overlay
  match/twin_burst.png           1024x1024 transparent: extra-lavish twin starburst (birthday twins)
  match/swipe_card_frame.png     1024x1536 transparent: swipe-card frame, empty centre
  popups/its_a_conjunction.png   1024x1536 opaque: mutual-match celebration scene, no text

Records file, prompt, model, size, quality and background in art/match/manifest_match.json.

Usage:
    python art/tools/generate_match_art.py                     # generate missing files
    python art/tools/generate_match_art.py --only merge_emblem --force
    python art/tools/generate_match_art.py --record-only       # add manifest entries for existing files

The OpenAI key is read at runtime from ~/Desktop/oai-key.txt (or --key-file / OPENAI_API_KEY_FILE)
and is never printed or written anywhere.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import random
import sys
import time
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from generate_art import STYLE  # noqa: E402  - shared series style prefix

ART_DIR = Path(__file__).resolve().parent.parent
MANIFEST = ART_DIR / "match" / "manifest_match.json"
DEFAULT_KEY_FILE = Path.home() / "Desktop" / "oai-key.txt"
MODEL_PREFERENCE = ["gpt-image-2.5-sunburst", "gpt-image-2.5-flare", "gpt-image-2",
                    "gpt-image-1.5", "gpt-image-1"]

TRANSPARENT_ONLY = (
    " Fully transparent background everywhere outside the drawn elements: no sky, no backdrop, "
    "no vignette, no solid fill."
)


@dataclass(frozen=True)
class Asset:
    file: str
    subject: str
    size: str = "1024x1024"
    quality: str = "high"
    background: str = "transparent"

    def prompt(self) -> str:
        return f"{STYLE}\n\nSubject: {self.subject}"


ASSETS = [
    Asset(
        "match/medallion_frame.png",
        "An ornate circular gold-leaf Art Nouveau medallion frame ring, perfectly centred, outer "
        "diameter about 92% of the canvas, ring band about 14% of the radius thick, decorated with "
        "filigree, tiny star studs, small lilies at the four cardinal points and fine constellation "
        "line accents. The large circle inside the ring is completely empty and fully transparent "
        "(it will hold a profile photo)." + TRANSPARENT_ONLY,
    ),
    Asset(
        "match/merge_emblem.png",
        "A combined conjunction emblem: two celestial rings of equal size genuinely interlocked "
        "like two links of a chain (each passes over the other at one crossing and under it at "
        "the other); the left ring an indigo enamel band traced with tiny gold constellation "
        "stars, the right ring rose-copper with gold filigree. At their overlap a brilliant gold "
        "eight-point starburst with a pearl at its heart fuses them. Bold clear silhouette. The "
        "whole emblem fits inside the central 78% of the canvas, with an empty margin of at least "
        "11% on every side; nothing touches the edges." + TRANSPARENT_ONLY,
    ),
    Asset(
        "match/merge_burst.png",
        "A radial light-burst flare overlay: a bright champagne-gold core with long thin tapering "
        "gold rays of varying length radiating in all directions, scattered small four-point "
        "sparkles and gold dust, centred, rays fading out before the canvas edges. Light only, no "
        "objects." + TRANSPARENT_ONLY,
    ),
    Asset(
        "match/twin_burst.png",
        "An extra-lavish twin starburst for a celebration: two mirrored radiant gold starbursts "
        "side by side whose rays interweave, surrounded by a ring of rose-copper and pearl star "
        "confetti, tiny crescent moons and Mucha-style filigree curls, symmetrical, centred, "
        "more opulent and festive than a single burst, fading out before the canvas edges."
        + TRANSPARENT_ONLY,
    ),
    Asset(
        "match/swipe_card_frame.png",
        "An ornate portrait card frame border for a swipeable profile card: a gold-leaf Art "
        "Nouveau border with gently rounded corners, small filigree corner flourishes, a tiny "
        "star at the top centre and a small conjunction of two orbs at the bottom centre, thin "
        "constellation line accents. The border occupies only the outer 8% of the canvas on each "
        "side. The entire inner area inside the frame is completely empty and fully transparent."
        + TRANSPARENT_ONLY,
        size="1024x1536", quality="medium",
    ),
    Asset(
        "popups/its_a_conjunction.png",
        "Celebratory mutual-match popup art, portrait. Two elegant Art Nouveau figures on either "
        "side reaching toward each other, between their hands two glowing orbs (one gold, one "
        "moonstone pearl) merging into a brilliant starburst, gold constellation lines spiralling "
        "outward, a shower of star confetti and rose-copper sparkles, ornate Mucha-style arched "
        "frame with lilies. Joyful, romantic, tasteful. Keep the lower 30% darker and simpler for "
        "overlaid interface text.",
        size="1024x1536", background="opaque",
    ),
]


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
    sys.exit("No gpt-image-* model is available to this key")


def generate(client, model: str, a: Asset, retries: int) -> bytes:
    delay = 5.0
    for attempt in range(1, retries + 1):
        try:
            resp = client.images.generate(model=model, prompt=a.prompt(), size=a.size,
                                          quality=a.quality, n=1, output_format="png",
                                          background=a.background)
            b64 = resp.data[0].b64_json
            if not b64:
                raise RuntimeError("response contained no image data")
            return base64.b64decode(b64)
        except Exception as e:  # noqa: BLE001 - report and retry API/network errors
            status = getattr(e, "status_code", None)
            msg = str(e).splitlines()[0][:200]
            if status in (400, 401, 403) or attempt == retries:
                raise RuntimeError(f"{a.file}: {type(e).__name__}: {msg}") from None
            wait = 25 + random.random() * 10 if status == 429 else delay
            print(f"  ! {a.file} attempt {attempt} failed ({msg}); retrying in {wait:.0f}s", flush=True)
            time.sleep(wait)
            delay = min(delay * 2, 60)
    raise AssertionError("unreachable")


def load_manifest() -> dict:
    if MANIFEST.exists():
        return {e["file"]: e for e in json.loads(MANIFEST.read_text(encoding="utf-8"))["images"]}
    return {}


def save_manifest(entries: dict) -> None:
    out = {"art_direction": "Celestial Art Nouveau", "style_prefix": STYLE,
           "generator": "art/tools/generate_match_art.py",
           "images": [entries[k] for k in sorted(entries)]}
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    tmp = MANIFEST.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    tmp.replace(MANIFEST)


def entry(a: Asset, model: str, note: str | None = None) -> dict:
    from PIL import Image
    with Image.open(ART_DIR / a.file) as im:
        size, mode = f"{im.size[0]}x{im.size[1]}", im.mode
    e = {"file": a.file, "prompt": a.prompt(), "model": model, "size": size, "mode": mode,
         "quality": a.quality, "background": a.background,
         "recorded_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    if note:
        e["note"] = note
    return e


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--only", action="append", default=[])
    ap.add_argument("--model")
    ap.add_argument("--key-file", type=Path,
                    default=Path(os.environ.get("OPENAI_API_KEY_FILE", DEFAULT_KEY_FILE)))
    ap.add_argument("--retries", type=int, default=6)
    ap.add_argument("--record-only", action="store_true",
                    help="no API calls; record manifest entries for files that already exist")
    ap.add_argument("--recorded-model", default="gpt-image-2.5-sunburst",
                    help="model to record with --record-only")
    args = ap.parse_args()

    selected = [a for a in ASSETS if not args.only or any(s in a.file for s in args.only)]
    entries = load_manifest()

    if args.record_only:
        for a in selected:
            if (ART_DIR / a.file).exists() and a.file not in entries:
                entries[a.file] = entry(a, args.recorded_model)
        save_manifest(entries)
        print(f"Recorded {len(entries)} entr(ies) in {MANIFEST.relative_to(ART_DIR.parent)}")
        return 0

    todo = [a for a in selected if args.force or not (ART_DIR / a.file).exists()]
    if not todo:
        print("Nothing to generate (use --force to regenerate)")
        return 0
    from openai import OpenAI
    client = OpenAI(api_key=read_key(args.key_file), max_retries=0, timeout=300)
    model = pick_model(client, args.model)
    print(f"Using model {model}; {len(todo)} image(s)", flush=True)
    failed = 0
    for a in todo:  # sequential: only a handful of images, stays well under per-minute limits
        try:
            data = generate(client, model, a, args.retries)
        except RuntimeError as e:
            failed += 1
            print(f"  FAILED {e}", flush=True)
            continue
        out = ART_DIR / a.file
        out.parent.mkdir(parents=True, exist_ok=True)
        tmp = out.with_suffix(".png.tmp")
        tmp.write_bytes(data)
        tmp.replace(out)
        entries[a.file] = entry(a, model)
        save_manifest(entries)
        print(f"  ok {a.file}", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
