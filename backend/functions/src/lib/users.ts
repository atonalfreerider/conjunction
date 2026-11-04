/** The `users/{uid}` document shape and helpers shared by handlers. */
import type { Timestamp } from "firebase-admin/firestore";
import type { CompatProfile } from "../compat/types.js";
import { yearsOn, type Gender, type LookingFor, type MatchProfile, type RelationshipStatus, type Seeking } from "./matchFilter.js";
import { signOf, type Sign } from "./zodiac.js";

export const CONSENT_KEYS = [
  "WAIVER",
  "TERMS",
  "PRIVACY",
  "AGE_18_CONFIRMED",
  "CONSENT_BLOOD_TYPE_MATCHING",
  "CONSENT_ORIENTATION_DATA",
  "PROFILE_HONESTY_CONFIRMED",
  "CONSENT_NEARBY_LOCATION",
  "SAFETY_TIPS",
] as const;
export type ConsentKey = (typeof CONSENT_KEYS)[number];

/** Consents that must be present (and not withdrawn) before Nearby can run. */
export const NEARBY_REQUIRED_CONSENTS: ConsentKey[] = [
  "WAIVER", "TERMS", "PRIVACY", "AGE_18_CONFIRMED", "CONSENT_ORIENTATION_DATA", "CONSENT_NEARBY_LOCATION",
];

export interface ConsentSummary { version: string; at: Timestamp; withdrawn: boolean }

export interface StoredPushToken { token: string; platform: "android" | "ios"; updatedAt: Timestamp }

export interface UserDoc {
  displayName?: string | null;
  bio?: string | null;
  /** Text waiting for moderation; only the owner sees it. */
  pending?: { displayName?: string | null; bio?: string | null };
  gender?: Gender;
  seeking?: Seeking;
  relationshipStatus?: RelationshipStatus;
  lookingFor?: LookingFor;
  compat?: CompatProfile | null;
  nearby?: { enabled?: boolean; pausedUntil?: Timestamp | null };
  consents?: Partial<Record<ConsentKey, ConsentSummary>>;
  photo?: { approved: boolean; version: number; updatedAt?: Timestamp } | null;
  pendingPhoto?: { uploadId: string; status: "pending" | "rejected"; reason?: string | null; updatedAt?: Timestamp } | null;
  push?: { tokens?: StoredPushToken[] };
  /** Set by moderators; suspended accounts cannot use Nearby or chat. */
  status?: "active" | "suspended";
}

export function hasConsent(user: UserDoc, key: ConsentKey): boolean {
  const c = user.consents?.[key];
  return !!c && !c.withdrawn;
}

export function matchProfileOf(user: UserDoc): MatchProfile | null {
  if (!user.gender || !user.seeking || !user.relationshipStatus || !user.lookingFor) return null;
  return { gender: user.gender, seeking: user.seeking, relationshipStatus: user.relationshipStatus, lookingFor: user.lookingFor };
}

/** Why Nearby cannot run for this user right now (empty = ready). */
export function nearbyBlockers(maybeUser: UserDoc | undefined, today: string): string[] {
  const user: UserDoc = maybeUser ?? {};
  const out: string[] = [];
  for (const key of NEARBY_REQUIRED_CONSENTS) if (!hasConsent(user, key)) out.push(`consent:${key}`);
  if (!matchProfileOf(user)) out.push("datingProfile");
  if (!user.compat) out.push("chartSummary");
  else {
    try {
      if (yearsOn(user.compat.birthDate, today) < 18) out.push("age");
    } catch {
      out.push("age");
    }
  }
  if (user.status === "suspended") out.push("suspended");
  return out;
}

export interface Signs { sun: Sign; moon: Sign; rising: Sign | null }

export function signsOf(compat: CompatProfile): Signs {
  return {
    sun: signOf(compat.sun),
    moon: signOf(compat.moon),
    rising: compat.ascendant === null || compat.ascendant === undefined ? null : signOf(compat.ascendant),
  };
}

/** First word of an approved display name (cards show first names only). */
export function firstName(user: UserDoc): string | null {
  const name = user.displayName?.trim();
  if (!name) return null;
  return name.split(/\s+/)[0] ?? null;
}

/** What the other person sees on a match card (photo URL is added on request by getEncounterCard). */
export interface MatchCard {
  firstName: string | null;
  age: number;
  sun: Sign;
  moon: Sign;
  rising: Sign | null;
  chineseAnimal: string;
  relationshipStatus: RelationshipStatus;
  lookingFor: LookingFor;
  hasPhoto: boolean;
}

export function cardOf(user: UserDoc, today: string): MatchCard {
  const compat = user.compat!;
  const s = signsOf(compat);
  return {
    firstName: firstName(user),
    age: yearsOn(compat.birthDate, today),
    sun: s.sun,
    moon: s.moon,
    rising: s.rising,
    chineseAnimal: compat.chineseAnimal,
    relationshipStatus: user.relationshipStatus!,
    lookingFor: user.lookingFor!,
    hasPhoto: !!user.photo?.approved,
  };
}
