/**
 * Match filter and age gate — TypeScript port of the Kotlin reference
 * (`android/core/.../match/MatchFilter.kt`), contract in `docs/MATCH_FILTER.md`, verified against
 * `testdata/golden_match_filter.json` (all 1296 ordered profile pairs plus age edge cases).
 *
 * The backend is the authoritative gate: no encounter document and no push without `kind(...)`
 * returning "dating" or "friends" for two adults.
 */

export const GENDERS = ["male", "female"] as const;
export const SEEKINGS = ["male", "female", "both"] as const;
export const RELATIONSHIP_STATUSES = ["single", "in_a_relationship"] as const;
export const LOOKING_FORS = ["dating", "friends", "both"] as const;
export const MATCH_KINDS = ["dating", "friends"] as const;

export type Gender = (typeof GENDERS)[number];
export type Seeking = (typeof SEEKINGS)[number];
export type RelationshipStatus = (typeof RELATIONSHIP_STATUSES)[number];
export type LookingFor = (typeof LOOKING_FORS)[number];
export type MatchKind = (typeof MATCH_KINDS)[number];

export interface MatchProfile {
  gender: Gender;
  seeking: Seeking;
  relationshipStatus: RelationshipStatus;
  lookingFor: LookingFor;
}

const includesDating = (l: LookingFor) => l === "dating" || l === "both";
const includesFriends = (l: LookingFor) => l === "friends" || l === "both";
const seeks = (s: Seeking, g: Gender) => s === "both" || s === g;

/** Parses wire values strictly (case-sensitive); unknown values throw instead of being mapped. */
export function parseMatchProfile(raw: Record<string, unknown>): MatchProfile {
  const pick = <T extends string>(field: string, allowed: readonly T[]): T => {
    const v = raw[field];
    if (typeof v !== "string" || !(allowed as readonly string[]).includes(v)) {
      throw new TypeError(`Unknown ${field} '${String(v)}'`);
    }
    return v as T;
  };
  return {
    gender: pick("gender", GENDERS),
    seeking: pick("seeking", SEEKINGS),
    relationshipStatus: pick("relationshipStatus", RELATIONSHIP_STATUSES),
    lookingFor: pick("lookingFor", LOOKING_FORS),
  };
}

export function qualifiesForDating(a: MatchProfile, b: MatchProfile): boolean {
  return (
    includesDating(a.lookingFor) && includesDating(b.lookingFor) &&
    seeks(a.seeking, b.gender) && seeks(b.seeking, a.gender) &&
    a.relationshipStatus === "single" && b.relationshipStatus === "single"
  );
}

/** dating, else friends, else null (no encounter). Symmetric in a and b. */
export function matchKind(a: MatchProfile, b: MatchProfile): MatchKind | null {
  if (qualifiesForDating(a, b)) return "dating";
  if (includesFriends(a.lookingFor) && includesFriends(b.lookingFor)) return "friends";
  return null;
}

/** As `matchKind`, but null unless both are 18+ on `today` (ISO dates; the backend uses UTC). */
export function eligibleMatchKind(a: MatchProfile, aBirthDate: string, b: MatchProfile, bBirthDate: string, today: string): MatchKind | null {
  if (!isAdult(aBirthDate, today) || !isAdult(bBirthDate, today)) return null;
  return matchKind(a, b);
}

export function defaultLookingFor(status: RelationshipStatus): LookingFor {
  return status === "single" ? "both" : "friends";
}

// ---------------------------------------------------------------------------------------------
// Age (java.time Period semantics, on ISO yyyy-mm-dd dates)

export const MINIMUM_AGE = 18;

export interface IsoDate { year: number; month: number; day: number }

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** Parses and validates a calendar date `yyyy-mm-dd` (proleptic Gregorian, years 1..9999). */
export function parseIsoDate(s: string): IsoDate {
  const m = ISO_DATE.exec(s);
  if (!m) throw new RangeError(`invalid date '${s}'`);
  const year = Number(m[1]), month = Number(m[2]), day = Number(m[3]);
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) {
    throw new RangeError(`invalid date '${s}'`);
  }
  return { year, month, day };
}

export function isLeapYear(y: number): boolean {
  return (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
}

function daysInMonth(y: number, m: number): number {
  return [31, isLeapYear(y) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1]!;
}

function compare(a: IsoDate, b: IsoDate): number {
  return a.year - b.year || a.month - b.month || a.day - b.day;
}

const fmt = (d: IsoDate) =>
  `${String(d.year).padStart(4, "0")}-${String(d.month).padStart(2, "0")}-${String(d.day).padStart(2, "0")}`;

/** Completed years. Throws when the birth date is after `today`. */
export function yearsOn(birthDate: string, today: string): number {
  const b = parseIsoDate(birthDate), t = parseIsoDate(today);
  if (compare(b, t) > 0) throw new RangeError(`Birth date ${birthDate} is after ${today}`);
  let years = t.year - b.year;
  if (t.month < b.month || (t.month === b.month && t.day < b.day)) years -= 1;
  return years;
}

export function isAdult(birthDate: string, today: string): boolean {
  const b = parseIsoDate(birthDate), t = parseIsoDate(today);
  return compare(b, t) <= 0 && yearsOn(birthDate, today) >= MINIMUM_AGE;
}

/** today − 18 years; 29 Feb clamps to 28 Feb (java.time minusYears). */
export function latestAdultBirthDate(today: string): string {
  const t = parseIsoDate(today);
  const year = t.year - MINIMUM_AGE;
  return fmt({ year, month: t.month, day: Math.min(t.day, daysInMonth(year, t.month)) });
}

/** Today's date in UTC as yyyy-mm-dd. */
export function utcToday(now: Date = new Date()): string {
  return now.toISOString().slice(0, 10);
}
