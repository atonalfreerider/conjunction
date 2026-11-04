/**
 * Moderation policy: turns TypeSafe probabilities (and deterministic pattern hits) into one decision.
 *
 *   block — refuse (the user sees a reason and can rephrase)
 *   hold  — keep out of view until a moderator (or a later automatic retry) decides
 *   allow — deliver / publish
 *
 * plus optional `flags` that never block delivery but open a review item (e.g. possible self-harm,
 * so a person can check in, per the Community Guidelines).
 *
 * Bands per violation: p >= block → block, p >= hold → hold, else allow. The numbers live here, not
 * in a prompt; they were checked with `scripts/eval-moderation.ts` against the live API (see README).
 * When TypeSafe is unreachable the caller fails closed to `hold` (see `moderator.ts`).
 */
import type { PatternHits } from "./patterns.js";
import type { TextField, Violation } from "./questions.js";

export type Decision = "allow" | "hold" | "block";

export interface Band {
  /** p at or above which the text is held for review. `Infinity` = never hold on this signal. */
  hold: number;
  /** p at or above which the text is blocked. `Infinity` = never block on this signal. */
  block: number;
}

const NEVER = Number.POSITIVE_INFINITY;

/** Standard band for clear violations. */
export const STANDARD: Band = { hold: 0.5, block: 0.85 };

/** Context the policy needs besides the text. */
export interface PolicyContext {
  field: TextField;
  /** Chat only: true until both people have sent a few messages (see `ESTABLISHED_MIN_MESSAGES_EACH`). */
  isNewConnection: boolean;
}

/** Messages each side must have sent before a chat counts as established (contact details, links allowed). */
export const ESTABLISHED_MIN_MESSAGES_EACH = 3;

/** The band table. Profile text is seen by strangers, so it is the strictest. */
export function bandFor(violation: Violation, ctx: PolicyContext): Band | "flag" {
  const profile = ctx.field !== "chat_message";
  switch (violation) {
    case "harassment":
    case "hate":
    case "scam":
      return STANDARD;
    case "sexual":
      // Guidelines: no sexual content in bios; no unsolicited sexual messages. Between people who have
      // been chatting a while, sexual talk is not pre-screened (the recipient can report or block).
      if (profile) return { hold: 0.4, block: 0.8 };
      return ctx.isNewConnection ? STANDARD : { hold: NEVER, block: NEVER };
    case "contact_info":
      // Strangers see profiles: never publish contact details there. In chat, wait until established.
      if (profile) return { hold: 0.4, block: 0.7 };
      return ctx.isNewConnection ? { hold: 0.5, block: 0.8 } : { hold: NEVER, block: NEVER };
    case "offplatform_lure":
      return STANDARD;
    case "underage":
      // A possible minor always goes to a person (they may also act on the account).
      return { hold: 0.4, block: NEVER };
    case "self_harm":
      return "flag";
  }
}

/** p at or above which a `flag` signal opens a review item. */
export const FLAG_THRESHOLD = 0.5;

export interface PolicyOutcome {
  decision: Decision;
  /** Violations that produced the decision (blocking ones first). */
  reasons: Violation[];
  /** Non-blocking signals for human follow-up. */
  flags: Violation[];
}

const RANK: Record<Decision, number> = { allow: 0, hold: 1, block: 2 };

export function decide(scores: Partial<Record<Violation, number>>, patterns: PatternHits, ctx: PolicyContext): PolicyOutcome {
  const blocking: Violation[] = [];
  const holding: Violation[] = [];
  const flags: Violation[] = [];

  // Deterministic hits count as certain for their violation.
  const effective: Partial<Record<Violation, number>> = { ...scores };
  if (patterns.email || patterns.phone) effective.contact_info = 1;
  if (patterns.url) effective.offplatform_lure = Math.max(effective.offplatform_lure ?? 0, linkScore(ctx));

  for (const [violation, p] of Object.entries(effective) as Array<[Violation, number]>) {
    if (typeof p !== "number" || Number.isNaN(p)) continue;
    const band = bandFor(violation, ctx);
    if (band === "flag") {
      if (p >= FLAG_THRESHOLD) flags.push(violation);
    } else if (p >= band.block) {
      blocking.push(violation);
    } else if (p >= band.hold) {
      holding.push(violation);
    }
  }
  const decision: Decision = blocking.length ? "block" : holding.length ? "hold" : "allow";
  return { decision, reasons: [...blocking, ...holding], flags };
}

/**
 * A bare link: blocked in profiles and in new chats (classic lure); in an established chat it is held
 * for review rather than delivered unseen (Guidelines §5: no phishing links).
 */
function linkScore(ctx: PolicyContext): number {
  if (ctx.field !== "chat_message" || ctx.isNewConnection) return 1;
  return STANDARD.hold;
}

/** Max of two decisions (used when combining automatic and manual signals). */
export function stricter(a: Decision, b: Decision): Decision {
  return RANK[a] >= RANK[b] ? a : b;
}
