# Compatibility algorithm (contract)

`Compatibility.score(a, b)` turns two uploaded profile summaries into whole-number scores. Code
computes the numbers; the on-device model only writes the blurb that explains them. Three
implementations must agree **exactly**, field for field:

| Platform | File |
|---|---|
| Kotlin (reference) | `android/core/src/main/kotlin/com/metavirtuoso/conjunction/core/compat/Compatibility.kt` |
| Swift | `ios/Sources/ConjunctionCore/` (port) |
| TypeScript (backend) | `backend/functions/` (port; the server is the authority for the number in notifications) |

Golden vectors: `testdata/golden_compat.json` (35 pairs, with birthday twins, exact twins,
unknown birth time, unknown blood type, a self-pair, and hand-made edge cases for rounding, orb limits
and negative truncation). The file is generated from the Kotlin engine with
`./gradlew :core:writeGoldens`. Every port must reproduce every `expected` field.

Whether a pair is offered at all (dating / friends / none) is a separate rule, documented in
[`MATCH_FILTER.md`](MATCH_FILTER.md) and pinned by `testdata/golden_match_filter.json`. The backend
runs the match filter first and scores only pairs that qualify.

## 0. Determinism rules

* The **only** floating-point operation is quantising longitudes (step 1). Everything after that is
  32-bit-safe integer arithmetic (no intermediate value exceeds ±100 000).
* `a / b` on integers means **truncation toward zero** (Kotlin/Swift `/`; JS `Math.trunc(a / b)`).
* `floorDiv(a, b)` means floor division (Kotlin `Math.floorDiv`; JS `Math.floor(a / b)`, exact for
  these magnitudes; Swift: write a helper, since `/` truncates).
* `roundDiv(n, d) = floorDiv(2n + d, 2d)` for `d > 0`: round half **up** (toward +infinity).
* The result is the same when `a` and `b` are swapped (all tables are symmetric, and every
  cross-pair appears in both directions). The tests check `score(a, b) == score(b, a)`.

## 1. Input: `CompatProfile` (the uploaded summary)

Wire format (JSON, as in `golden_compat.json`):

```json
{"sun": 139.53, "moon": 51.2, "venus": 102.88, "mars": 61.47, "ascendant": 222.05,
 "chineseAnimal": "horse", "dayStem": "ji", "bloodType": "O",
 "birthDate": "1990-08-12", "birthHour": 14}
```

| field | meaning |
|---|---|
| `sun`, `moon`, `venus`, `mars` | apparent tropical longitudes, degrees, rounded to 0.01 when the profile is built |
| `ascendant` | as above, or `null` when the birth time is unknown |
| `chineseAnimal` | solar-year (Lichun) animal key: `rat ox tiger rabbit dragon snake horse goat monkey rooster dog pig` (index 0..11 in this order) |
| `dayStem` | day-pillar stem key: `jia yi bing ding wu ji geng xin ren gui` (index 0..9) |
| `bloodType` | `"A" "B" "AB" "O"` or `null` (Rh does not affect the score) |
| `birthDate` | local civil birth date `YYYY-MM-DD` |
| `birthHour` | local clock hour 0..23, or `null` when the time is unknown |

Quantise each longitude: `c = floorMod(floor(lon * 100 + 0.5), 36000)` (centidegrees, 0..35999).
So 359.995 becomes 0 and 29.995 becomes 3000 (Taurus).

`sign(c) = c div 3000` (0 = Aries ... 11 = Pisces). Element and modality of a sign:

| sign index | 0 Ari | 1 Tau | 2 Gem | 3 Can | 4 Leo | 5 Vir | 6 Lib | 7 Sco | 8 Sag | 9 Cap | 10 Aqu | 11 Pis |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| element | Fire | Earth | Air | Water | Fire | Earth | Air | Water | Fire | Earth | Air | Water |
| modality | Card. | Fixed | Mut. | Card. | Fixed | Mut. | Card. | Fixed | Mut. | Card. | Fixed | Mut. |

(element = `sign mod 4` in the order Fire, Earth, Air, Water; modality = `sign mod 3` in the order Cardinal, Fixed, Mutable.)

## 2. Western synastry (0..100)

### 2.1 Pairs and weights

Points: Sun, Moon, Venus, Mars, Ascendant. The 17 pairs `(point of A, point of B, weight)`, in this
order:

| # | A | B | w | | # | A | B | w |
|---|---|---|---|---|---|---|---|---|
| 1 | Sun | Moon | 5 | | 10 | Moon | Venus | 2 |
| 2 | Moon | Sun | 5 | | 11 | Venus | Moon | 2 |
| 3 | Venus | Mars | 4 | | 12 | Mars | Mars | 1 |
| 4 | Mars | Venus | 4 | | 13 | Asc | Sun | 2 |
| 5 | Moon | Moon | 4 | | 14 | Sun | Asc | 2 |
| 6 | Sun | Sun | 3 | | 15 | Asc | Moon | 2 |
| 7 | Venus | Venus | 2 | | 16 | Moon | Asc | 2 |
| 8 | Sun | Venus | 2 | | 17 | Asc | Asc | 1 |
| 9 | Venus | Sun | 2 | | | | | |

A pair whose point is missing (Ascendant `null`) is skipped entirely: it adds nothing to the
numerator or the denominator.

### 2.2 Pair score (permille, -1000..1000)

`pair = clamp(signPart + aspectPart, -1000, 1000)`

**signPart** = `ELEMENT[eA][eB] + MODALITY(mA, mB)`:

| ELEMENT | Fire | Earth | Air | Water |
|---|---|---|---|---|
| **Fire** | 300 | -50 | 250 | -150 |
| **Earth** | -50 | 300 | -150 | 250 |
| **Air** | 250 | -150 | 300 | -50 |
| **Water** | -150 | 250 | -50 | 300 |

`MODALITY = -50` if the modalities are equal, else `+100`.

The resulting whole-sign relations, for reference: same sign 250, trine 400, sextile 350,
opposition 200, square -200 or -100, semi-sextile and quincunx +50 or -50.

**aspectPart**: `sep = |cA - cB|; if sep > 18000 then sep = 36000 - sep` (0..18000). Then, over the
aspects below **in this order**, take the one with the smallest `dev = |sep - angle|` among those
with `dev <= orb`, keeping the earlier aspect on an exact tie:

| aspect | angle | orb | value |
|---|---|---|---|
| conjunction | 0 | 800 | +600 |
| sextile | 6000 | 500 | +400 |
| square | 9000 | 700 | -400 |
| trine | 12000 | 700 | +500 |
| opposition | 18000 | 800 | +200 |

(angle and orb in centidegrees; value in permille.) Then
`aspectPart = value * (orb - dev) / orb` with **truncation toward zero** (so -329.7 becomes -329).
No aspect in orb: `aspectPart = 0`. An aspect exactly at the orb limit contributes 0.

### 2.3 Western total

```
weighted = Σ w * pair      weights = Σ w        (over the pairs that were not skipped)
raw      = floorDiv(weighted, weights)          (-1000..1000)
western  = clamp(roundDiv(raw + 145, 5), 0, 100)
```

The offset 145 and divisor 5 were calibrated on 12 720 pairs of real charts (1960-2005 births, three
time zones, 20% unknown times) to give a mean of about 63 and a 5th to 95th percentile range of
about 38 to 88 for `western` (and 48 to 81 for `total`). `CompatibilityTest.distributionIsSensible`
guards the calibration.

## 3. Chinese (0..100)

### 3.1 Year animals (branch relation)

With branch indices `x, y` (Rat 0 ... Pig 11), `d = |x - y|`, the **first** rule that matches wins:

| relation | rule | score |
|---|---|---|
| SAME_SELF_PUNISHMENT | `x == y` and x in {Dragon 4, Horse 6, Rooster 9, Pig 11} (自刑) | 60 |
| SAME | `x == y` (other animals) | 70 |
| TRINE (三合) | `d mod 4 == 0` | 95 |
| HARMONY (六合, "secret friends") | `(x + y) mod 12 == 1` | 90 |
| CLASH (六冲) | `d == 6` | 25 |
| HARM (六害) | `(x + y) mod 12 == 7` | 40 |
| NEUTRAL | otherwise | 60 |

The trines are Rat-Dragon-Monkey, Ox-Snake-Rooster, Tiger-Horse-Dog and Rabbit-Goat-Pig. The six
harmonies are Rat-Ox, Tiger-Pig, Rabbit-Dog, Dragon-Rooster, Snake-Monkey and Horse-Goat. The
clashes are Rat-Horse, Ox-Goat, Tiger-Monkey, Rabbit-Rooster, Dragon-Dog and Snake-Pig. The harms
are Rat-Goat, Ox-Horse, Tiger-Snake, Rabbit-Dragon, Monkey-Pig and Rooster-Dog. These four sets
never overlap.

### 3.2 Day Masters (day-pillar stems)

Stems `s, t` (Jia 0 ... Gui 9), each with element `index div 2` (Wood, Fire, Earth, Metal, Water).
The generating cycle is Wood → Fire → Earth → Metal → Water → Wood. The **first** rule that matches
wins:

| relation | rule | score |
|---|---|---|
| COMBINATION (天干五合) | `|s - t| == 5` (Jia-Ji, Yi-Geng, Bing-Xin, Ding-Ren, Wu-Gui) | 95 |
| SAME_STEM | `s == t` | 70 |
| SAME_ELEMENT | same element, different polarity | 75 |
| GENERATING | one element generates the other (`(eA + 1) mod 5 == eB` or vice versa) | 80 |
| CONTROLLING | otherwise (the elements are two apart on the cycle) | 45 |

### 3.3 Chinese total

`chinese = roundDiv(3 * yearScore + 2 * stemScore, 5)`

## 4. Blood type (0..100 or null)

Symmetric matrix, rows and columns A, B, AB, O. It follows popular ketsueki-gata lore and is
entertainment only.

| | A | B | AB | O |
|---|---|---|---|---|
| **A** | 80 | 50 | 70 | 85 |
| **B** | 50 | 80 | 85 | 75 |
| **AB** | 70 | 85 | 75 | 60 |
| **O** | 85 | 75 | 60 | 80 |

If either blood type is `null`, `blood = null`.

## 5. Total

```
if blood != null:  total = roundDiv(55 * western + 30 * chinese + 15 * blood, 100)
else:              total = roundDiv(55 * western + 30 * chinese, 85)
total = clamp(total, 0, 100)
```

Twins do not change any number; they change the presentation (a special popup) and add a highlight.

## 6. Twins

* `isBirthdayTwin`: the `birthDate` values are equal (same year, month and day).
* `isExactTwin`: birthday twins **and** both `birthHour` values are non-null and equal (the same
  clock hour, so 14:20 and 14:50 are exact twins). An unknown hour is never an exact twin.
* `sharesBirthday`: same month and day but a different year (a lighter "same birthday" note).

## 7. Highlights (strings, identical everywhere)

The list is built in this order and then cut to **at most 4** entries:

1. The twin line, if any: `"Exact twins: same day, same hour"`, else `"Birthday twins"`, else
   (sharesBirthday) `"Same birthday"`.
2. Up to **two** harmonious aspects. For each pair from section 2.1 whose in-orb aspect has
   `aspectPart > 0`, form the label `"<P1>–<P2> <aspect>"`. P1 and P2 are the two point names
   ordered Sun < Moon < Venus < Mars < Ascendant, joined by an EN DASH (U+2013), for example
   `"Sun–Moon trine"` or `"Ascendant–Ascendant trine"`. Its contribution is `w * aspectPart`. Sort
   by contribution descending, then by label ascending (compare UTF-16 code units), drop duplicate
   labels (keeping the first), and take the first two.
3. The Chinese year relation, with the animal names ordered by branch index (`X` is the smaller):
   TRINE `"X & Y: San He trine"`; HARMONY `"X & Y: Liu He secret friends"`; CLASH
   `"X & Y: opposites attract"`; SAME or SAME_SELF_PUNISHMENT `"Both Xs"` (for example
   `"Both Horses"`); HARM and NEUTRAL add nothing. Display names are Rat, Ox, Tiger, Rabbit,
   Dragon, Snake, Horse, Goat, Monkey, Rooster, Dog, Pig.
4. If the Day Masters combine: `"Day Masters combine (<hanzi><hanzi>)"`, smaller stem index
   first, for example `"Day Masters combine (甲己)"`.
5. If `blood >= 80`: `"Blood types X + Y"`, ordered A, B, AB, O, for example
   `"Blood types A + O"`.

`chineseRelation` and `stemRelation` are also returned (as the enum names above) so clients can
pick localized copy.

## 8. Output

```json
{"total": 77, "western": 81, "chinese": 64, "blood": 85,
 "highlights": ["Exact twins: same day, same hour", "Moon–Moon conjunction", "Sun–Sun conjunction", "Both Horses"],
 "isBirthdayTwin": true, "isExactTwin": true, "sharesBirthday": false,
 "chineseRelation": "SAME_SELF_PUNISHMENT", "stemRelation": "SAME_STEM"}
```

## 9. Explanatory blurb (on device)

The number never comes from a model. Each phone writes its own one-line blurb on device with
`HoroscopePrompt.matchBlurb(me, them, result, kind)`. That delegates to `MatchBlurbPrompt.build`
(wording in `MATCH_FILTER.md`). For the longer reading the phone uses
`HoroscopePrompt.compatibility(me, them, result)`. Without a model,
`FallbackReadings.compatibility(...)` builds the text from bundled phrases. The prompt wording is
pinned in `testdata/golden_prompts.json`.

## 10. Changing the algorithm

Any change to a table, weight or rounding rule is a **contract change**. Edit the Kotlin
reference, run `./gradlew :core:writeGoldens`, review the diff of `testdata/golden_compat.json`,
update this document, and land the Swift and TypeScript ports in the same commit.
