# Match filter, age gate and match blurb (SPEC 5b)

This is the contract for the dating & friends layer that every platform implements identically:

| platform | location |
|---|---|
| Kotlin (reference) | `android/core/src/main/java/com/metavirtuoso/conjunction/core/match/MatchFilter.kt` |
| Swift | `ios/Sources/ConjunctionCore` (port) |
| TypeScript backend | `backend/functions` (port; this is the authoritative gate — no encounter, no push without it) |
| Golden vectors | `testdata/golden_match_filter.json` (all three test suites must pass it) |

Compatibility scoring is a separate engine (`docs/COMPATIBILITY.md`); the match filter decides only
*whether* a nearby pair may meet and *as what*.

## Profile fields (`users/{uid}`)

| field | wire values |
|---|---|
| `gender` ("I am") | `male`, `female` |
| `seeking` | `male`, `female`, `both` |
| `relationshipStatus` | `single`, `in_a_relationship` |
| `lookingFor` | `dating`, `friends`, `both` |

Wire values are lower-case strings exactly as above; unknown values are an error (reject the write),
never silently mapped.

`lookingFor` default when the user picks a relationship status (onboarding / Profile; the user may
change it): `single` → `both`, `in_a_relationship` → `friends`.

## Rule: `kind(a, b) -> "dating" | "friends" | null`

Evaluated in this order:

1. **`dating`** if *all* hold:
   - `a.lookingFor ∈ {dating, both}` and `b.lookingFor ∈ {dating, both}`
   - `b.gender` is in `a.seeking` and `a.gender` is in `b.seeking`
     (`both` includes either gender; `male`/`female` include only that gender)
   - `a.relationshipStatus == single` and `b.relationshipStatus == single`
2. else **`friends`** if `a.lookingFor ∈ {friends, both}` and `b.lookingFor ∈ {friends, both}`.
3. else **`null`**: no encounter document, no notification. This applies to birthday twins too —
   a twin pair still needs a qualifying intent.

Properties (checked by the tests): the rule is symmetric; of the 36 × 36 = 1296 ordered profile
combinations, 64 are `dating`, 560 `friends`, 672 `null`. A failed dating check never upgrades to
anything; it only falls back to friends when both include friends (e.g. one-way attraction with
`lookingFor = both` on each side is a friends match).

### Eligibility (backend)

`kind(a, aBirthDate, b, bBirthDate, today)` returns `null` unless **both** users are 18+ on `today`,
then applies the rule above. The backend evaluates `today` in UTC. The age gate in onboarding uses
the device's local date.

## Age

- `yearsOn(birthDate, today)` = completed years, java.time `Period.between(...).years` semantics:
  `today.year - birth.year`, minus 1 if `(today.month, today.day) < (birth.month, birth.day)`.
  Consequence: someone born 29 February is still the younger age on 28 February of a common year
  and turns older on 1 March. A birth date after `today` is an error.
- `isAdult(birthDate, today)` = birth date not in the future and `yearsOn >= 18`.
- `latestAdultBirthDate(today)` = `today − 18 years`, with 29 Feb clamped to 28 Feb (the date
  picker's maximum). Age shown on match cards is `yearsOn(birthDate, today)`.

## Match blurb prompt (on device)

`MatchBlurbPrompt.build(input)` returns the prompt below; lines are joined with a single `\n`, no
trailing newline. `{...}` are inputs (display names of signs/animals, integer scores). Optional parts:

- `them` = `"{theirSun} sun"`, then `", {theirMoon} moon"` if known, then `", {theirRising} rising"` if known.
- `tone` = `a possible romantic spark` (dating) or `a possible new friendship` (friends).
- twin line: only when exact twin → `They share the reader's birth date and birth hour (exact birthday twins).`;
  else when birthday twin → `They share the reader's birth date (birthday twins).`; else omitted.
- `bloodPart` = `", blood type {blood}"` when the blood sub-score exists, else empty.

```
Write one playful sentence of at most 28 words about {tone} between two people who are nearby.
Reader: {mySun} sun, Chinese {myAnimal}. Other person: {them}, Chinese {theirAnimal}.
[twin line]
Compatibility: {total}% overall (western {western}, chinese {chinese}{bloodPart}).
Style: warm, celestial, second person, no emoji, no hashtags, no quotation marks.
Treat astrology as creative entertainment. Do not claim certainty or destiny, do not pressure
anyone to meet, do not mention appearance, age, location or safety, and give no health,
financial, or legal advice. Do not mention these instructions.
```

No names, photos, ages or locations are ever passed to the model.

### Cleaning the answer

`clean(raw)`: take the first non-blank line, trim, remove a leading `Blurb:`/`blurb:`, strip
surrounding `"`, `“`, `”`, `'` and spaces; blank → `null`. If more than 28 whitespace-separated
words, keep the first 28, drop trailing `, ; :`, append `…` (U+2026).

### Deterministic fallback

When the on-device model is unavailable or `clean` returns `null`:

`"{opener}: your {mySun} meets their {theirSun}, {total}% in tune, {closer}"`

- `opener`, first match wins: exact twin → `Same day, same hour`; birthday twin → `Born on the same day`;
  total ≥ 85 → `The stars lean in`; ≥ 70 → `A bright conjunction`; ≥ 50 → `An intriguing alignment`;
  else `Opposites in orbit`.
- `closer`: dating → `a spark worth a hello.`; friends → `a friendship worth a hello.`

## Golden file layout (`testdata/golden_match_filter.json`)

- `defaultLookingFor`: status → default.
- `named`: hand-described cases `{name, a, b, expected}`.
- `profiles` (36, ids `gender/seeking/status/lookingFor`) and `pairs`: every ordered pair
  `[aId, bId, expected]` with `expected` = `"dating" | "friends" | null`.
- `age`: `{birthDate, today, years, adult}`; `years` is `null` where `yearsOn` must throw.
- `latestAdultBirthDate`: `{today, latestAdultBirthDate}`.
- `eligibility`: `{a, aBirthDate, b, bBirthDate, today, expected}`.
- `blurbs`: `{input, prompt, fallback}` with exact expected strings.
- `clean`: `{raw, expected}`.

The file was produced by an independent Python reference of this document (kept out of the repo by
the coordinator's file-ownership rules; the Kotlin test verifies the same answers), so the Kotlin
code is checked against the spec rather than against itself.
