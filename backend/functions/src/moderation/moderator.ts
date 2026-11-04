/**
 * Text moderation and report triage backed by TypeSafe (Jev), with a fail-closed wrapper.
 *
 * Every public method returns a decision even when TypeSafe is down, misconfigured or slow:
 * text is then held for review (`unavailable: true`) and reports are triaged as `high` urgency, so an
 * outage can delay content but never lets unchecked text through.
 */
import { TypeSafeClient } from "@typesafe-ai/sdk";
import { decide, type Decision, type PolicyContext } from "./policy.js";
import { detectPatterns } from "./patterns.js";
import {
  DEFAULT_MODEL,
  REPORT_CATEGORIES,
  REPORT_QUESTIONS,
  REPORT_URGENCIES,
  TEXT_QUESTIONS,
  VIOLATIONS,
  reportState,
  textState,
  type ReportCategory,
  type ReportUrgency,
  type TextField,
  type Violation,
} from "./questions.js";

export interface TextModerationInput {
  text: string;
  field: TextField;
  /** Chat: whether the chat is still new (see policy.ts). Ignored for profile fields. */
  isNewConnection?: boolean;
  matchKind?: "dating" | "friends" | null;
}

export interface TextModerationResult {
  decision: Decision;
  reasons: Violation[];
  flags: Violation[];
  /** Raw probabilities per violation (empty when unavailable). */
  scores: Partial<Record<Violation, number>>;
  /** Versioned model that answered, e.g. "jev-1.14.0". */
  model: string | null;
  /** True when TypeSafe could not be reached and the result is the fail-closed hold. */
  unavailable: boolean;
}

export interface ReportTriageInput {
  reasonSelected: string | null;
  description: string;
  reportedDisplayName: string | null;
  reportedBio: string | null;
  recentMessages: string[];
}

export interface ReportTriage {
  category: ReportCategory;
  urgency: ReportUrgency;
  categoryConfidence: number | null;
  urgencyConfidence: number | null;
  categoryProbabilities: Record<string, number> | null;
  urgencyProbabilities: Record<string, number> | null;
  /** Review-queue priority: 0 = immediate, 3 = low. */
  priority: 0 | 1 | 2 | 3;
  model: string | null;
  unavailable: boolean;
}

export interface Moderator {
  moderateText(input: TextModerationInput): Promise<TextModerationResult>;
  triageReport(input: ReportTriageInput): Promise<ReportTriage>;
}

/** Minimal surface of TypeSafeClient we use (lets tests inject a fake transport). */
export interface SystemOneCaller {
  systemOne: TypeSafeClient["systemOne"];
}

const URGENCY_PRIORITY: Record<ReportUrgency, 0 | 1 | 2 | 3> = { immediate_danger: 0, high: 1, normal: 2, low: 3 };

/** Choice confidence below which a report's urgency is bumped one level (err toward a faster look). */
export const URGENCY_CONFIDENCE_FLOOR = 0.5;

function policyContext(input: TextModerationInput): PolicyContext {
  return { field: input.field, isNewConnection: input.field === "chat_message" ? (input.isNewConnection ?? true) : false };
}

export function failClosedText(input: TextModerationInput): TextModerationResult {
  const patterns = detectPatterns(input.text);
  // Deterministic checks still apply during an outage: a phone number in a bio is blocked outright.
  const deterministic = decide({}, patterns, policyContext(input));
  const decision: Decision = deterministic.decision === "block" ? "block" : "hold";
  return { decision, reasons: deterministic.reasons, flags: [], scores: {}, model: null, unavailable: true };
}

export function failClosedReport(input: ReportTriageInput): ReportTriage {
  const selected = input.reasonSelected && input.reasonSelected in REPORT_CATEGORIES ? (input.reasonSelected as ReportCategory) : "other";
  return {
    category: selected,
    urgency: "high",
    categoryConfidence: null,
    urgencyConfidence: null,
    categoryProbabilities: null,
    urgencyProbabilities: null,
    priority: selected === "threats_or_violence" || selected === "underage_user" || selected === "in_person_incident" ? 0 : 1,
    model: null,
    unavailable: true,
  };
}

export interface TypeSafeModeratorOptions {
  model?: string;
  /** Per-attempt timeout (ms). Chat delivery waits on this, so keep it short. */
  timeoutMs?: number;
  maxRetries?: number;
  log?: (message: string, data?: Record<string, unknown>) => void;
}

export class TypeSafeModerator implements Moderator {
  private readonly model: string;
  private readonly timeoutMs: number;
  private readonly maxRetries: number;
  private readonly log: NonNullable<TypeSafeModeratorOptions["log"]>;

  constructor(private readonly client: SystemOneCaller, opts: TypeSafeModeratorOptions = {}) {
    this.model = opts.model ?? DEFAULT_MODEL;
    this.timeoutMs = opts.timeoutMs ?? 5000;
    this.maxRetries = opts.maxRetries ?? 1;
    this.log = opts.log ?? (() => undefined);
  }

  /** Builds a moderator from an API key. Returns null when no key is configured. */
  static fromKey(apiKey: string | null, opts: TypeSafeModeratorOptions = {}): TypeSafeModerator | null {
    if (!apiKey) return null;
    const client = new TypeSafeClient({ apiKey, logLevel: "off", timeout: opts.timeoutMs ?? 5000 });
    return new TypeSafeModerator(client, opts);
  }

  async moderateText(input: TextModerationInput): Promise<TextModerationResult> {
    const ctx = policyContext(input);
    const patterns = detectPatterns(input.text);
    try {
      const res = await this.client.systemOne(
        {
          model: this.model,
          state: textState(input.text, input.field, { isNewConnection: ctx.isNewConnection, matchKind: input.matchKind ?? null }) as never,
          questions: TEXT_QUESTIONS,
        },
        { timeout: this.timeoutMs, retry: { maxRetries: this.maxRetries } },
      );
      const scores: Partial<Record<Violation, number>> = {};
      for (const v of VIOLATIONS) {
        const p = res.answers[v]?.noul;
        if (typeof p !== "number" || !Number.isFinite(p)) throw new Error(`missing answer for ${v}`);
        scores[v] = p;
      }
      const outcome = decide(scores, patterns, ctx);
      return { ...outcome, scores, model: res.model, unavailable: false };
    } catch (err) {
      // Never log the text itself or the key; the error class/status is enough to diagnose.
      this.log("typesafe moderateText failed; holding for review", { error: describeError(err) });
      return failClosedText(input);
    }
  }

  async triageReport(input: ReportTriageInput): Promise<ReportTriage> {
    try {
      const res = await this.client.systemOne(
        { model: this.model, state: reportState(input) as never, questions: REPORT_QUESTIONS },
        { timeout: this.timeoutMs * 2, retry: { maxRetries: this.maxRetries + 1 } },
      );
      const cat = res.answers.category;
      const urg = res.answers.urgency;
      if (!(cat?.choice in REPORT_CATEGORIES) || !(urg?.choice in REPORT_URGENCIES)) throw new Error("unexpected choice");
      let priority = URGENCY_PRIORITY[urg.choice as ReportUrgency];
      // Low confidence on urgency: look sooner rather than later.
      if (urg.confidence < URGENCY_CONFIDENCE_FLOOR && priority > 0) priority = (priority - 1) as 0 | 1 | 2;
      // Categories that are always urgent regardless of the urgency answer.
      if (cat.choice === "underage_user" || cat.choice === "threats_or_violence") priority = Math.min(priority, 1) as 0 | 1;
      return {
        category: cat.choice as ReportCategory,
        urgency: urg.choice as ReportUrgency,
        categoryConfidence: cat.confidence,
        urgencyConfidence: urg.confidence,
        categoryProbabilities: { ...cat.probabilities },
        urgencyProbabilities: { ...urg.probabilities },
        priority,
        model: res.model,
        unavailable: false,
      };
    } catch (err) {
      this.log("typesafe triageReport failed; defaulting to high urgency", { error: describeError(err) });
      return failClosedReport(input);
    }
  }
}

/** A moderator used when no TypeSafe key is configured: everything is held / triaged as high. */
export class UnavailableModerator implements Moderator {
  async moderateText(input: TextModerationInput): Promise<TextModerationResult> {
    return failClosedText(input);
  }
  async triageReport(input: ReportTriageInput): Promise<ReportTriage> {
    return failClosedReport(input);
  }
}

/**
 * Deterministic stand-in for emulator integration tests (MODERATION_PROVIDER=fake). It runs the real
 * policy on scores derived from markers in the text, so tests exercise every branch without network:
 *   "[[harassment]]", "[[sexual]]", … → that violation at p=0.95 (block band)
 *   "[[hold:<violation>]]"            → that violation at p=0.6 (hold band)
 *   "[[outage]]"                       → behaves as if TypeSafe were unreachable
 */
export class FakeModerator implements Moderator {
  async moderateText(input: TextModerationInput): Promise<TextModerationResult> {
    if (input.text.includes("[[outage]]")) return failClosedText(input);
    const scores: Partial<Record<Violation, number>> = Object.fromEntries(VIOLATIONS.map((v) => [v, 0.01]));
    for (const v of VIOLATIONS) {
      if (input.text.includes(`[[${v}]]`)) scores[v] = 0.95;
      if (input.text.includes(`[[hold:${v}]]`)) scores[v] = 0.6;
    }
    const outcome = decide(scores, detectPatterns(input.text), policyContext(input));
    return { ...outcome, scores, model: "fake", unavailable: false };
  }
  async triageReport(input: ReportTriageInput): Promise<ReportTriage> {
    if (input.description.includes("[[outage]]")) return failClosedReport(input);
    const danger = /\[\[danger\]\]/.test(input.description);
    const category = (input.reasonSelected && input.reasonSelected in REPORT_CATEGORIES ? input.reasonSelected : "other") as ReportCategory;
    return {
      category,
      urgency: danger ? "immediate_danger" : "normal",
      categoryConfidence: 1,
      urgencyConfidence: 1,
      categoryProbabilities: null,
      urgencyProbabilities: null,
      priority: danger ? 0 : 2,
      model: "fake",
      unavailable: false,
    };
  }
}

export function describeError(err: unknown): string {
  if (err && typeof err === "object") {
    const e = err as { name?: string; status?: number; message?: string };
    return [e.name, e.status].filter((x) => x !== undefined).join(" ") || "error";
  }
  return "error";
}
