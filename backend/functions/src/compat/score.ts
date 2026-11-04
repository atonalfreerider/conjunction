/**
 * Deterministic compatibility score — exact TypeScript port of the Kotlin reference
 * (android/core/.../compat/Compatibility.kt; spec docs/COMPATIBILITY.md). Integer arithmetic after
 * quantising longitudes to centidegrees, so Kotlin, Swift and TypeScript agree to the digit; verified
 * against testdata/golden_compat.json.
 *
 * JS notes for exactness: Kotlin `/` on Ints truncates toward zero (→ Math.trunc), Math.floorDiv
 * floors (→ Math.floor on exact integer quotients; all intermediate values are small integers, far
 * below 2^53), and string ordering for highlight tie-breaks compares UTF-16 code units in both.
 */
import { CHINESE_ANIMALS } from "../lib/zodiac.js";
import {
  BLOOD_TYPES,
  STEMS,
  STEM_HANZI,
  type BloodType,
  type ChineseRelation,
  type CompatProfile,
  type CompatResult,
  type CompatScore,
  type Stem,
  type StemRelation,
} from "./types.js";

type Point = "SUN" | "MOON" | "VENUS" | "MARS" | "ASC";
const POINT_ORDER: Record<Point, number> = { SUN: 0, MOON: 1, VENUS: 2, MARS: 3, ASC: 4 };
const POINT_LABEL: Record<Point, string> = { SUN: "Sun", MOON: "Moon", VENUS: "Venus", MARS: "Mars", ASC: "Ascendant" };

/** (point of A, point of B, weight); symmetric. */
export const PAIRS: ReadonlyArray<readonly [Point, Point, number]> = [
  ["SUN", "MOON", 5], ["MOON", "SUN", 5],
  ["VENUS", "MARS", 4], ["MARS", "VENUS", 4],
  ["MOON", "MOON", 4],
  ["SUN", "SUN", 3],
  ["VENUS", "VENUS", 2],
  ["SUN", "VENUS", 2], ["VENUS", "SUN", 2],
  ["MOON", "VENUS", 2], ["VENUS", "MOON", 2],
  ["MARS", "MARS", 1],
  ["ASC", "SUN", 2], ["SUN", "ASC", 2],
  ["ASC", "MOON", 2], ["MOON", "ASC", 2],
  ["ASC", "ASC", 1],
];

export interface SynastryAspect { name: string; angle: number; orb: number; value: number }

/** Exact angle and orb in centidegrees, value in permille; array order = tie-break order. */
export const ASPECTS: readonly SynastryAspect[] = [
  { name: "conjunction", angle: 0, orb: 800, value: 600 },
  { name: "sextile", angle: 6000, orb: 500, value: 400 },
  { name: "square", angle: 9000, orb: 700, value: -400 },
  { name: "trine", angle: 12000, orb: 700, value: 500 },
  { name: "opposition", angle: 18000, orb: 800, value: 200 },
];

/** Sign index 0..11 → element (FIRE, EARTH, AIR, WATER) and modality (CARDINAL, FIXED, MUTABLE). */
const signElement = (sign: number) => sign % 4;
const signModality = (sign: number) => sign % 3;

/** ELEMENT[a][b] permille, order FIRE, EARTH, AIR, WATER. */
export const ELEMENT: readonly (readonly number[])[] = [
  [300, -50, 250, -150],
  [-50, 300, -150, 250],
  [250, -150, 300, -50],
  [-150, 250, -50, 300],
];

/** BLOOD[a][b], order A, B, AB, O. */
export const BLOOD: readonly (readonly number[])[] = [
  [80, 50, 70, 85],
  [50, 80, 85, 75],
  [70, 85, 75, 60],
  [85, 75, 60, 80],
];

export const WEIGHT_WESTERN = 55;
export const WEIGHT_CHINESE = 30;
export const WEIGHT_BLOOD = 15;
/** western = clamp(roundDiv(raw + WESTERN_OFFSET, WESTERN_DIVISOR), 0, 100) (calibrated; docs §2.3). */
export const WESTERN_OFFSET = 145;
export const WESTERN_DIVISOR = 5;

const CHINESE_SCORE: Record<ChineseRelation, number> = {
  SAME: 70, SAME_SELF_PUNISHMENT: 60, TRINE: 95, HARMONY: 90, CLASH: 25, HARM: 40, NEUTRAL: 60,
};
const STEM_SCORE: Record<StemRelation, number> = {
  COMBINATION: 95, SAME_STEM: 70, SAME_ELEMENT: 75, GENERATING: 80, CONTROLLING: 45,
};

const floorDiv = (n: number, d: number) => Math.floor(n / d);
const floorMod = (n: number, d: number) => ((n % d) + d) % d;
const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** floor(lon * 100 + 0.5) mod 36000 — the only floating-point step. */
export function centidegrees(lon: number): number {
  return floorMod(Math.floor(lon * 100 + 0.5), 36_000);
}

/** Round half up for integer n / d (d > 0). */
export function roundDiv(n: number, d: number): number {
  return floorDiv(2 * n + d, 2 * d);
}

export interface PairScore { score: number; aspect: SynastryAspect | null; aspectPart: number }

export function pairScore(lonA: number, lonB: number): PairScore {
  const signA = Math.trunc(lonA / 3000), signB = Math.trunc(lonB / 3000);
  const signPart = ELEMENT[signElement(signA)]![signElement(signB)]! + (signModality(signA) === signModality(signB) ? -50 : 100);
  const diff = Math.abs(lonA - lonB);
  const sep = diff > 18_000 ? 36_000 - diff : diff;
  let best: SynastryAspect | null = null;
  let bestDev = Number.MAX_SAFE_INTEGER;
  for (const asp of ASPECTS) {
    const dev = Math.abs(sep - asp.angle);
    if (dev <= asp.orb && dev < bestDev) {
      best = asp;
      bestDev = dev;
    }
  }
  const aspectPart = best ? Math.trunc((best.value * (best.orb - bestDev)) / best.orb) : 0;
  return { score: clamp(signPart + aspectPart, -1000, 1000), aspect: best, aspectPart };
}

function animalIndex(key: string): number {
  const i = (CHINESE_ANIMALS as readonly string[]).indexOf(key);
  if (i < 0) throw new RangeError(`unknown Chinese animal '${key}'`);
  return i;
}

export function chineseRelation(a: string, b: string): ChineseRelation {
  const x = animalIndex(a), y = animalIndex(b);
  const d = Math.abs(x - y);
  if (x === y) return [4, 6, 9, 11].includes(x) ? "SAME_SELF_PUNISHMENT" : "SAME";
  if (d % 4 === 0) return "TRINE";
  if ((x + y) % 12 === 1) return "HARMONY";
  if (d === 6) return "CLASH";
  if ((x + y) % 12 === 7) return "HARM";
  return "NEUTRAL";
}

function stemIndex(s: Stem): number {
  const i = STEMS.indexOf(s);
  if (i < 0) throw new RangeError(`unknown stem '${s}'`);
  return i;
}

export function stemRelation(a: Stem, b: Stem): StemRelation {
  const ia = stemIndex(a), ib = stemIndex(b);
  // Elements WOOD, FIRE, EARTH, METAL, WATER; stem index / 2; generating = next in that order.
  const ea = Math.trunc(ia / 2), eb = Math.trunc(ib / 2);
  if (Math.abs(ia - ib) === 5) return "COMBINATION";
  if (ia === ib) return "SAME_STEM";
  if (ea === eb) return "SAME_ELEMENT";
  if ((ea + 1) % 5 === eb || (eb + 1) % 5 === ea) return "GENERATING";
  return "CONTROLLING";
}

export function bloodScore(a: BloodType | null, b: BloodType | null): number | null {
  if (!a || !b) return null;
  return BLOOD[BLOOD_TYPES.indexOf(a)]![BLOOD_TYPES.indexOf(b)]!;
}

export const isBirthdayTwin = (a: CompatProfile, b: CompatProfile) => a.birthDate === b.birthDate;

export const isExactTwin = (a: CompatProfile, b: CompatProfile) =>
  isBirthdayTwin(a, b) && a.birthHour !== null && a.birthHour !== undefined && a.birthHour === b.birthHour;

export const sharesBirthday = (a: CompatProfile, b: CompatProfile) => !isBirthdayTwin(a, b) && a.birthDate.slice(5) === b.birthDate.slice(5);

function lon(p: CompatProfile, point: Point): number | null {
  switch (point) {
    case "SUN": return p.sun;
    case "MOON": return p.moon;
    case "VENUS": return p.venus;
    case "MARS": return p.mars;
    case "ASC": return p.ascendant ?? null;
  }
}

const animalName = (i: number) => {
  const k = CHINESE_ANIMALS[i]!;
  return k.charAt(0).toUpperCase() + k.slice(1);
};

function chineseHighlight(rel: ChineseRelation, a: string, b: string): string | null {
  const [x, y] = [animalIndex(a), animalIndex(b)].sort((p, q) => p - q) as [number, number];
  switch (rel) {
    case "TRINE": return `${animalName(x)} & ${animalName(y)}: San He trine`;
    case "HARMONY": return `${animalName(x)} & ${animalName(y)}: Liu He secret friends`;
    case "CLASH": return `${animalName(x)} & ${animalName(y)}: opposites attract`;
    case "SAME":
    case "SAME_SELF_PUNISHMENT": return `Both ${animalName(x)}s`;
    default: return null;
  }
}

export function score(a: CompatProfile, b: CompatProfile): CompatResult {
  // 1. Western synastry.
  let weighted = 0, weights = 0;
  const contributions: Array<{ label: string; value: number }> = [];
  for (const [pa, pb, w] of PAIRS) {
    const la = lon(a, pa), lb = lon(b, pb);
    if (la === null || lb === null) continue;
    const ps = pairScore(centidegrees(la), centidegrees(lb));
    weighted += w * ps.score;
    weights += w;
    if (ps.aspect && ps.aspectPart > 0) {
      const [first, second] = POINT_ORDER[pa] <= POINT_ORDER[pb] ? [pa, pb] : [pb, pa];
      contributions.push({ label: `${POINT_LABEL[first]}–${POINT_LABEL[second]} ${ps.aspect.name}`, value: w * ps.aspectPart });
    }
  }
  const raw = floorDiv(weighted, weights);
  const western = clamp(roundDiv(raw + WESTERN_OFFSET, WESTERN_DIVISOR), 0, 100);

  // 2. Chinese: year-branch relation (3/5) and day-master relation (2/5).
  const cRel = chineseRelation(a.chineseAnimal, b.chineseAnimal);
  const sRel = stemRelation(a.dayStem, b.dayStem);
  const chinese = roundDiv(3 * CHINESE_SCORE[cRel] + 2 * STEM_SCORE[sRel], 5);

  // 3. Blood type.
  const blood = bloodScore(a.bloodType ?? null, b.bloodType ?? null);

  // 4. Blend.
  const total = blood !== null
    ? roundDiv(WEIGHT_WESTERN * western + WEIGHT_CHINESE * chinese + WEIGHT_BLOOD * blood, 100)
    : roundDiv(WEIGHT_WESTERN * western + WEIGHT_CHINESE * chinese, WEIGHT_WESTERN + WEIGHT_CHINESE);

  // 5. Highlights.
  const twin = isBirthdayTwin(a, b), exact = isExactTwin(a, b), shares = sharesBirthday(a, b);
  const highlights: string[] = [];
  if (exact) highlights.push("Exact twins: same day, same hour");
  else if (twin) highlights.push("Birthday twins");
  else if (shares) highlights.push("Same birthday");
  const labels = contributions
    .sort((p, q) => q.value - p.value || (p.label < q.label ? -1 : p.label > q.label ? 1 : 0))
    .map((c) => c.label);
  for (const l of [...new Set(labels)].slice(0, 2)) highlights.push(l);
  const ch = chineseHighlight(cRel, a.chineseAnimal, b.chineseAnimal);
  if (ch) highlights.push(ch);
  if (sRel === "COMBINATION") {
    const [x, y] = [stemIndex(a.dayStem), stemIndex(b.dayStem)].sort((p, q) => p - q) as [number, number];
    highlights.push(`Day Masters combine (${STEM_HANZI[STEMS[x]!]}${STEM_HANZI[STEMS[y]!]})`);
  }
  if (blood !== null && blood >= 80) {
    const [x, y] = [a.bloodType!, b.bloodType!].sort((p, q) => BLOOD_TYPES.indexOf(p) - BLOOD_TYPES.indexOf(q));
    highlights.push(`Blood types ${x} + ${y}`);
  }
  return {
    total: clamp(total, 0, 100),
    western,
    chinese,
    blood,
    highlights: highlights.slice(0, 4),
    isBirthdayTwin: twin,
    isExactTwin: exact,
    sharesBirthday: shares,
    chineseRelation: cRel,
    stemRelation: sRel,
  };
}

/** The subset stored on an encounter. */
export function compatibilityScore(a: CompatProfile, b: CompatProfile): CompatScore {
  const r = score(a, b);
  return { total: r.total, western: r.western, chinese: r.chinese, blood: r.blood, highlights: r.highlights };
}
