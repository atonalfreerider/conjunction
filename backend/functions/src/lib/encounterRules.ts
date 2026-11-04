/**
 * Pure decision: may these two nearby users meet now, and as what? No I/O, so it is unit-tested
 * directly. `updatePresence` gathers the facts and applies the verdict.
 */
import type { Timestamp } from "firebase-admin/firestore";
import { compatibilityScore, isBirthdayTwin, isExactTwin } from "../compat/score.js";
import type { CompatScore } from "../compat/types.js";
import { eligibleMatchKind, type MatchKind } from "./matchFilter.js";
import { matchProfileOf, nearbyBlockers, type MatchCard, type UserDoc } from "./users.js";

export type SwipeDirection = "right" | "left";
export type EncounterStatus = "pending" | "matched" | "rejected" | "closed";

export interface EncounterDoc {
  uids: [string, string];
  kind: MatchKind;
  score: CompatScore;
  birthdayTwin: boolean;
  exactTwin: boolean;
  /** Swipe of uids[0] / uids[1]. */
  swipeA: SwipeDirection | null;
  swipeB: SwipeDirection | null;
  status: EncounterStatus;
  createdAt: Timestamp;
  /** Pending cards can be answered until then. */
  expiresAt: Timestamp;
  /** Not shown again before this (after a left swipe). */
  rejectedUntil?: Timestamp | null;
  /** Firestore TTL field. */
  purgeAt: Timestamp;
  cards: Record<string, MatchCard>;
}

export interface PairFacts {
  me: UserDoc;
  other: UserDoc;
  /** Either side blocked the other. */
  blocked: boolean;
  /** Either side snoozed the other and the snooze has not ended. */
  snoozed: boolean;
  /** Already friends (matched before). */
  friends: boolean;
  existing: Pick<EncounterDoc, "status" | "createdAt" | "rejectedUntil"> | null;
  /** UTC date yyyy-mm-dd for the age check. */
  today: string;
  /** True when `me` is uids[0] of the pair; the score is always computed in stored order. */
  meIsFirst: boolean;
  nowMs: number;
  dedupeMs: number;
}

export type PairVerdict =
  | { ok: true; kind: MatchKind; score: CompatScore; birthdayTwin: boolean; exactTwin: boolean }
  | { ok: false; reason: "other_unavailable" | "blocked" | "snoozed" | "friends" | "rejected_recently" | "seen_recently" | "no_intent" };

export function isPaused(user: UserDoc, nowMs: number): boolean {
  const until = user.nearby?.pausedUntil;
  return !!until && until.toMillis() > nowMs;
}

export function evaluatePair(f: PairFacts): PairVerdict {
  if (!f.other.nearby?.enabled || isPaused(f.other, f.nowMs) || nearbyBlockers(f.other, f.today).length) {
    return { ok: false, reason: "other_unavailable" };
  }
  if (f.blocked) return { ok: false, reason: "blocked" };
  if (f.friends) return { ok: false, reason: "friends" };
  if (f.snoozed) return { ok: false, reason: "snoozed" };
  if (f.existing) {
    if (f.existing.status === "matched") return { ok: false, reason: "friends" };
    if (f.existing.rejectedUntil && f.existing.rejectedUntil.toMillis() > f.nowMs) return { ok: false, reason: "rejected_recently" };
    if (f.existing.createdAt.toMillis() > f.nowMs - f.dedupeMs) return { ok: false, reason: "seen_recently" };
  }
  const a = matchProfileOf(f.me)!, b = matchProfileOf(f.other)!;
  const kind = eligibleMatchKind(a, f.me.compat!.birthDate, b, f.other.compat!.birthDate, f.today);
  if (!kind) return { ok: false, reason: "no_intent" };
  const [x, y] = f.meIsFirst ? [f.me.compat!, f.other.compat!] : [f.other.compat!, f.me.compat!];
  return {
    ok: true,
    kind,
    score: compatibilityScore(x, y),
    birthdayTwin: isBirthdayTwin(x, y),
    exactTwin: isExactTwin(x, y),
  };
}
