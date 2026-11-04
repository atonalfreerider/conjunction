/**
 * The uploaded chart summary (`CompatProfile`, SPEC "Shared engine contract") and the score shape.
 * Wire format = Kotlin `CompatWire` (android/core/src/test/.../CompatFixtures.kt) and
 * testdata/golden_compat.json.
 */
export const BLOOD_TYPES = ["A", "B", "AB", "O"] as const;
export type BloodType = (typeof BLOOD_TYPES)[number];

/** Heavenly stems by pinyin key, index 0 = 甲 (jia). */
export const STEMS = ["jia", "yi", "bing", "ding", "wu", "ji", "geng", "xin", "ren", "gui"] as const;
export type Stem = (typeof STEMS)[number];
export const STEM_HANZI: Record<Stem, string> = {
  jia: "甲", yi: "乙", bing: "丙", ding: "丁", wu: "戊", ji: "己", geng: "庚", xin: "辛", ren: "壬", gui: "癸",
};

export interface CompatProfile {
  /** Tropical ecliptic longitudes in degrees [0, 360], rounded to 0.01 on the phone. */
  sun: number;
  moon: number;
  venus: number;
  mars: number;
  /** Null when the birth time is unknown. */
  ascendant: number | null;
  /** Lower-case year animal key, e.g. "rat" (Lichun-boundary sexagenary year). */
  chineseAnimal: string;
  /** Day-pillar stem (day master) as lower-case pinyin key, e.g. "jia". */
  dayStem: Stem;
  /** Only stored when the user consented to blood-type matching. */
  bloodType: BloodType | null;
  /** yyyy-mm-dd (local civil birth date). */
  birthDate: string;
  /** 0..23 local clock hour, null when unknown. */
  birthHour: number | null;
}

export type ChineseRelation = "SAME" | "SAME_SELF_PUNISHMENT" | "TRINE" | "HARMONY" | "CLASH" | "HARM" | "NEUTRAL";
export type StemRelation = "COMBINATION" | "SAME_STEM" | "SAME_ELEMENT" | "GENERATING" | "CONTROLLING";

export interface CompatResult {
  total: number;
  western: number;
  chinese: number;
  /** Null when either blood type is unknown. */
  blood: number | null;
  highlights: string[];
  isBirthdayTwin: boolean;
  isExactTwin: boolean;
  sharesBirthday: boolean;
  chineseRelation: ChineseRelation;
  stemRelation: StemRelation;
}

/** What an encounter stores. */
export interface CompatScore {
  total: number;
  western: number;
  chinese: number;
  blood: number | null;
  highlights: string[];
}
