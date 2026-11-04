import { describe, expect, it } from "vitest";
import { Timestamp } from "firebase-admin/firestore";
import { evaluatePair, isPaused, type PairFacts } from "../../src/lib/encounterRules.js";
import type { UserDoc } from "../../src/lib/users.js";
import type { CompatProfile } from "../../src/compat/types.js";

const NOW = Date.UTC(2026, 10, 3, 12);
const consent = { version: "1.0", at: Timestamp.fromMillis(NOW - 1000), withdrawn: false };
const consents = Object.fromEntries(
  ["WAIVER", "TERMS", "PRIVACY", "AGE_18_CONFIRMED", "CONSENT_ORIENTATION_DATA", "CONSENT_NEARBY_LOCATION"].map((k) => [k, consent]),
);
const compat = (birthDate: string, birthHour: number | null = 12): CompatProfile => ({
  sun: 140.5, moon: 22.1, venus: 101.3, mars: 200, ascendant: 75, chineseAnimal: "horse", dayStem: "jia", bloodType: null, birthDate, birthHour,
});
const user = (over: Partial<UserDoc> = {}): UserDoc => ({
  gender: "female", seeking: "male", relationshipStatus: "single", lookingFor: "both",
  compat: compat("1990-08-12"), nearby: { enabled: true, pausedUntil: null }, consents, status: "active", ...over,
});
const facts = (over: Partial<PairFacts> = {}): PairFacts => ({
  me: user(), other: user({ gender: "male", seeking: "female" }), blocked: false, snoozed: false, friends: false, existing: null,
  today: "2026-11-03", meIsFirst: true, nowMs: NOW, dedupeMs: 24 * 3600_000, ...over,
});

describe("evaluatePair", () => {
  it("dating match with score and twin flags", () => {
    const v = evaluatePair(facts());
    expect(v.ok).toBe(true);
    if (v.ok) {
      expect(v.kind).toBe("dating");
      expect(v.birthdayTwin).toBe(true);
      expect(v.exactTwin).toBe(true);
      expect(v.score.total).toBeGreaterThanOrEqual(0);
      expect(v.score.total).toBeLessThanOrEqual(100);
    }
  });
  it("falls back to friends when attraction is one-way", () => {
    const v = evaluatePair(facts({ other: user({ gender: "female", seeking: "male" }) }));
    expect(v).toMatchObject({ ok: true, kind: "friends" });
  });
  it("no intent → no encounter, twins included", () => {
    const v = evaluatePair(facts({ me: user({ lookingFor: "dating", seeking: "male" }), other: user({ gender: "female", lookingFor: "dating" }) }));
    expect(v).toEqual({ ok: false, reason: "no_intent" });
  });
  it("blocks, friends, snoozes", () => {
    expect(evaluatePair(facts({ blocked: true }))).toEqual({ ok: false, reason: "blocked" });
    expect(evaluatePair(facts({ friends: true }))).toEqual({ ok: false, reason: "friends" });
    expect(evaluatePair(facts({ snoozed: true }))).toEqual({ ok: false, reason: "snoozed" });
  });
  it("24 h dedupe and 30-day left-swipe cooldown", () => {
    const recent = { status: "pending" as const, createdAt: Timestamp.fromMillis(NOW - 3600_000), rejectedUntil: null };
    expect(evaluatePair(facts({ existing: recent }))).toEqual({ ok: false, reason: "seen_recently" });
    const old = { status: "pending" as const, createdAt: Timestamp.fromMillis(NOW - 25 * 3600_000), rejectedUntil: null };
    expect(evaluatePair(facts({ existing: old })).ok).toBe(true);
    const rejected = { status: "rejected" as const, createdAt: Timestamp.fromMillis(NOW - 5 * 24 * 3600_000), rejectedUntil: Timestamp.fromMillis(NOW + 3600_000) };
    expect(evaluatePair(facts({ existing: rejected }))).toEqual({ ok: false, reason: "rejected_recently" });
    const matched = { status: "matched" as const, createdAt: Timestamp.fromMillis(NOW - 40 * 24 * 3600_000), rejectedUntil: null };
    expect(evaluatePair(facts({ existing: matched }))).toEqual({ ok: false, reason: "friends" });
  });
  it("other side paused, off, missing consent or under 18", () => {
    expect(evaluatePair(facts({ other: user({ nearby: { enabled: false } }) })).ok).toBe(false);
    expect(evaluatePair(facts({ other: user({ nearby: { enabled: true, pausedUntil: Timestamp.fromMillis(NOW + 60_000) } }) })).ok).toBe(false);
    const { CONSENT_NEARBY_LOCATION: _drop, ...partial } = consents;
    expect(evaluatePair(facts({ other: user({ consents: partial }) })).ok).toBe(false);
    expect(evaluatePair(facts({ other: user({ compat: compat("2009-01-01") }) })).ok).toBe(false);
  });
  it("score is the same whichever side triggers it", () => {
    const me = user({ compat: { ...compat("1990-08-12"), sun: 10, moon: 200 } });
    const other = user({ gender: "male", seeking: "female", compat: { ...compat("1985-02-01"), sun: 250, moon: 90 } });
    const a = evaluatePair(facts({ me, other, meIsFirst: true }));
    const b = evaluatePair(facts({ me: other, other: me, meIsFirst: false }));
    expect(a).toEqual(b);
  });
  it("isPaused", () => {
    expect(isPaused(user({ nearby: { enabled: true, pausedUntil: Timestamp.fromMillis(NOW + 1) } }), NOW)).toBe(true);
    expect(isPaused(user({ nearby: { enabled: true, pausedUntil: Timestamp.fromMillis(NOW - 1) } }), NOW)).toBe(false);
  });
});
