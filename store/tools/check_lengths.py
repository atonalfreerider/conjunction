#!/usr/bin/env python3
"""Check the store listing text against Google Play and App Store Connect limits.

Usage:  python3 store/tools/check_lengths.py          (from the repository root, or anywhere)

Counts characters the way both consoles do: Unicode code points of the file's text, with a
trailing newline ignored (fastlane strips it on upload). Exits 1 if any field is over its limit
or a required file is missing; prints warnings (not failures) for placeholders like
"[DEMO VIDEO URL]" that the owner must fill in before submitting.

Limits (checked 2026-11-03):
  Google Play  - app name 30, short description 80, full description 4000, release notes 500.
  App Store    - name 30, subtitle 30, promotional text 170, description 4000, keywords 100
                 (comma-separated; spaces after commas count), What's New 4000,
                 App Review notes 4000.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

STORE = Path(__file__).resolve().parent.parent
PLAY = STORE / "play/fastlane/metadata/android/en-US"
APPLE = STORE / "appstore/fastlane/metadata"

# (path, limit, required)
FIELDS: list[tuple[Path, int, bool]] = [
    (PLAY / "title.txt", 30, True),
    (PLAY / "short_description.txt", 80, True),
    (PLAY / "full_description.txt", 4000, True),
    (PLAY / "changelogs/1.txt", 500, True),
    (APPLE / "en-US/name.txt", 30, True),
    (APPLE / "en-US/subtitle.txt", 30, True),
    (APPLE / "en-US/promotional_text.txt", 170, True),
    (APPLE / "en-US/description.txt", 4000, True),
    (APPLE / "en-US/keywords.txt", 100, True),
    (APPLE / "en-US/release_notes.txt", 4000, True),
    (APPLE / "review_information/notes.txt", 4000, True),
]

PLACEHOLDER = re.compile(r"\[[A-Z][A-Z0-9 _/-]{2,}\]")


def text_of(path: Path) -> str:
    return path.read_text(encoding="utf-8").rstrip("\n")


def check_keywords(text: str) -> list[str]:
    """App Store keyword hygiene: no empty items, no duplicates, no words repeated from the name."""
    problems = []
    items = [k.strip().lower() for k in text.split(",")]
    if any(not k for k in items):
        problems.append("keywords: empty item (double or trailing comma)")
    dupes = {k for k in items if items.count(k) > 1}
    if dupes:
        problems.append(f"keywords: duplicates {sorted(dupes)}")
    name = text_of(APPLE / "en-US/name.txt").lower()
    name_words = set(re.findall(r"[a-z]+", name))
    repeated = sorted({w for k in items for w in k.split() if w in name_words})
    if repeated:
        problems.append(f"keywords: repeat words already in the app name (wasted) {repeated}")
    return problems


def main() -> int:
    failures: list[str] = []
    warnings: list[str] = []
    print(f"{'file':<62} {'chars':>5} / limit")
    for path, limit, required in FIELDS:
        rel = path.relative_to(STORE)
        if not path.exists():
            if required:
                failures.append(f"missing: {rel}")
            continue
        text = text_of(path)
        n = len(text)
        flag = "" if n <= limit else "  OVER"
        print(f"{str(rel):<62} {n:>5} / {limit}{flag}")
        if n > limit:
            failures.append(f"{rel}: {n} > {limit}")
        if required and n == 0:
            failures.append(f"{rel}: empty")
        for ph in PLACEHOLDER.findall(text):
            warnings.append(f"{rel}: placeholder {ph} must be filled in before submitting")
    keywords = APPLE / "en-US/keywords.txt"
    if keywords.exists():
        warnings.extend(check_keywords(text_of(keywords)))
    for w in warnings:
        print(f"warning: {w}")
    for f in failures:
        print(f"FAIL: {f}")
    print("OK" if not failures else f"{len(failures)} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
