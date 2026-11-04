# Shared test vectors

Every platform tests against these files. Kotlin (`android/core`) and Swift (`ios`) read all of
them; the TypeScript backend reads the compat and match-filter files.

| file | contents | produced by |
|---|---|---|
| `golden_charts.json` | 50 natal charts 1900-2100 with Swiss Ephemeris longitudes, speeds, Ascendant, MC, whole-sign and Placidus cusps, UTC instant, ΔT | `tools/make_golden.py` (pyswisseph + SWIEPH files) |
| `chinese_calendar.json` | Chinese New Year 1900-2100 and the dates of all 24 solar terms 1901-2100 (HKO) | `tools/make_chinese_calendar.py` |
| `golden_chinese.json` | 56 Four Pillars / animal cases from an independent oracle | `tools/make_golden_chinese.py` |
| `golden_compat.json` | 35 compatibility pairs (incl. birthday twins, exact twins, edge cases) + `synastry` lines (SPEC 5d.4) | `./gradlew :core:writeGoldens` (Kotlin reference) |
| `golden_prompts.json` | exact prompt and fallback-reading strings (incl. onboarding / bio prompts and `FallbackReadings.bio`) | `./gradlew :core:writeGoldens` |
| `golden_match_filter.json` | dating/friends match filter (see docs/MATCH_FILTER.md) | match-filter work |
| `p2p_wire.json` | P2P protocol vectors (frames, crypto, transcripts, SPEC 5d.6 `profile`) | `tools/make_p2p_wire.py` (Python reference in tools/p2p-sim) |
| `golden_instagram.json` | `InstagramHandle.normalize` / `isValid` (SPEC 5d.6) | hand-specified (checked by Kotlin, Swift and tools/p2p-sim) |
| `golden_birth_text.json` | `BirthTextParser.parse` (SPEC 5d.5) | `tools/make_golden_onboarding.py` (hand-specified) |
| `golden_about_you.json` | `AboutYouParser.parse` (SPEC 5d.5) | `tools/make_golden_onboarding.py` (hand-specified) |
| `golden_onboarding_ai.json` | `OnboardingExtraction` model-JSON validation (SPEC 5d.5) | `tools/make_golden_onboarding.py` (hand-specified) |

Tolerances, conventions and regeneration steps: `docs/ENGINES.md`. Compatibility algorithm:
`docs/COMPATIBILITY.md`. Python virtual environments and downloaded source data stay outside the
repo.
