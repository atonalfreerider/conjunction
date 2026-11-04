/** Policy bands, deterministic patterns, the TypeSafe adapter (with a stub transport) and fail-closed behaviour. */
import { describe, expect, it, vi } from "vitest";
import { APIConnectionError } from "@typesafe-ai/sdk";
import { decide } from "../../src/moderation/policy.js";
import { detectPatterns, isValidDisplayName, normalizeText } from "../../src/moderation/patterns.js";
import { FakeModerator, TypeSafeModerator, UnavailableModerator, type SystemOneCaller } from "../../src/moderation/moderator.js";
import { REPORT_QUESTIONS, TEXT_QUESTIONS, VIOLATIONS, textState } from "../../src/moderation/questions.js";

const none = { url: false, email: false, phone: false };
const low = Object.fromEntries(VIOLATIONS.map((v) => [v, 0.02]));
const newChat = { field: "chat_message" as const, isNewConnection: true };
const oldChat = { field: "chat_message" as const, isNewConnection: false };
const bio = { field: "bio" as const, isNewConnection: false };

describe("policy bands", () => {
  it("allows clean text", () => {
    expect(decide(low, none, newChat)).toEqual({ decision: "allow", reasons: [], flags: [] });
  });
  it("blocks / holds / allows by band", () => {
    expect(decide({ ...low, harassment: 0.9 }, none, newChat).decision).toBe("block");
    expect(decide({ ...low, harassment: 0.6 }, none, newChat).decision).toBe("hold");
    expect(decide({ ...low, harassment: 0.49 }, none, newChat).decision).toBe("allow");
  });
  it("a block outranks a hold and lists blocking reasons first", () => {
    const r = decide({ ...low, hate: 0.6, scam: 0.95 }, none, newChat);
    expect(r.decision).toBe("block");
    expect(r.reasons).toEqual(["scam", "hate"]);
  });
  it("sexual talk is pre-screened in new chats and profiles, not in established chats", () => {
    expect(decide({ ...low, sexual: 0.9 }, none, newChat).decision).toBe("block");
    expect(decide({ ...low, sexual: 0.82 }, none, bio).decision).toBe("block");
    expect(decide({ ...low, sexual: 0.99 }, none, oldChat).decision).toBe("allow");
  });
  it("contact details: never in profiles, not in new chats, fine once established", () => {
    expect(decide({ ...low, contact_info: 0.75 }, none, bio).decision).toBe("block");
    expect(decide({ ...low, contact_info: 0.9 }, none, newChat).decision).toBe("block");
    expect(decide({ ...low, contact_info: 0.99 }, none, oldChat).decision).toBe("allow");
    expect(decide(low, { ...none, phone: true }, newChat).decision).toBe("block");
    expect(decide(low, { ...none, email: true }, oldChat).decision).toBe("allow");
  });
  it("links: blocked in profiles and new chats, held in established chats", () => {
    expect(decide(low, { ...none, url: true }, bio).decision).toBe("block");
    expect(decide(low, { ...none, url: true }, newChat).decision).toBe("block");
    expect(decide(low, { ...none, url: true }, oldChat).decision).toBe("hold");
  });
  it("possible minors are held for a person, never auto-blocked", () => {
    expect(decide({ ...low, underage: 0.99 }, none, newChat)).toMatchObject({ decision: "hold", reasons: ["underage"] });
  });
  it("self-harm is a non-blocking flag", () => {
    expect(decide({ ...low, self_harm: 0.9 }, none, oldChat)).toEqual({ decision: "allow", reasons: [], flags: ["self_harm"] });
  });
  it("ignores NaN / missing scores", () => {
    expect(decide({ harassment: Number.NaN }, none, newChat).decision).toBe("allow");
  });
});

describe("patterns", () => {
  it("finds links, e-mail addresses and phone numbers", () => {
    expect(detectPatterns("see https://x.io/a").url).toBe(true);
    expect(detectPatterns("www.example.org").url).toBe(true);
    expect(detectPatterns("mysite.com/me").url).toBe(true);
    expect(detectPatterns("mail me a@b.co").email).toBe(true);
    expect(detectPatterns("+1 (555) 212-8899").phone).toBe(true);
    expect(detectPatterns("I'm a Leo, born 1990, see you at 7.30").phone).toBe(false);
    expect(detectPatterns("Sun 12° Leo, Moon 3° Pisces. Coffee?")).toEqual(none);
  });
  it("normalizes text", () => {
    expect(normalizeText("  hi​ there\t\t you\n\n\n\nok ")).toBe("hi there you\n\nok");
    expect(normalizeText("a‮b")).toBe("ab");
  });
  it("validates display names", () => {
    for (const ok of ["María José", "Sam O'Neill", "Anne-Marie", "J.R.", "李小龍", "Zoë"]) expect(isValidDisplayName(ok), ok).toBe(true);
    for (const bad of ["", " Sam", "Sam  Lee", "sam@x.com", "x".repeat(41), "123", "😀"]) expect(isValidDisplayName(bad), bad).toBe(false);
  });
});

describe("question batteries", () => {
  it("one Noul per violation, with yes/no criteria", () => {
    expect(Object.keys(TEXT_QUESTIONS).sort()).toEqual([...VIOLATIONS].sort());
    for (const q of Object.values(TEXT_QUESTIONS)) {
      expect(q.type).toBe("noul");
      expect(q.criteria?.true).toBeTruthy();
      expect(q.criteria?.false).toBeTruthy();
    }
    expect(REPORT_QUESTIONS.category.type).toBe("choice");
    expect(REPORT_QUESTIONS.urgency.type).toBe("choice");
  });
  it("state carries the text and its context", () => {
    const s = textState("hello", "chat_message", { isNewConnection: true, matchKind: "dating" });
    expect(s.text).toBe("hello");
    expect(s.context).toMatchObject({ field: "chat_message", recipient_is_new_connection: true, match_kind: "dating" });
    expect(textState("Sam", "display_name").context.recipient_is_new_connection).toBeNull();
  });
});

function stubClient(answers: Record<string, unknown> | (() => never)): SystemOneCaller & { calls: unknown[] } {
  const calls: unknown[] = [];
  const systemOne = vi.fn(async (req: unknown) => {
    calls.push(req);
    if (typeof answers === "function") answers();
    return { model: "jev-1.14.0", answers, usage: { input_tokens: 1, output_tokens: 1 } };
  });
  return { systemOne: systemOne as unknown as SystemOneCaller["systemOne"], calls };
}

const nouls = (over: Record<string, number> = {}) =>
  Object.fromEntries(VIOLATIONS.map((v) => [v, { type: "noul", noul: over[v] ?? 0.02 }]));

describe("TypeSafeModerator", () => {
  it("sends one request with every question and the pinned model", async () => {
    const client = stubClient(nouls());
    const m = new TypeSafeModerator(client);
    const r = await m.moderateText({ text: "Hi Leo!", field: "chat_message", isNewConnection: true, matchKind: "friends" });
    expect(r).toMatchObject({ decision: "allow", model: "jev-1.14.0", unavailable: false });
    expect(client.calls).toHaveLength(1);
    const req = client.calls[0] as { model: string; questions: object; state: { text: string } };
    expect(req.model).toBe("jev-1.14.0");
    expect(Object.keys(req.questions).sort()).toEqual([...VIOLATIONS].sort());
    expect(req.state.text).toBe("Hi Leo!");
  });

  it("applies the policy to the returned probabilities", async () => {
    const m = new TypeSafeModerator(stubClient(nouls({ scam: 0.97 })));
    expect(await m.moderateText({ text: "send $", field: "chat_message" })).toMatchObject({ decision: "block", reasons: ["scam"] });
  });

  it("fails closed (hold) when TypeSafe is unreachable", async () => {
    const m = new TypeSafeModerator(stubClient(() => { throw new APIConnectionError("down"); }));
    const r = await m.moderateText({ text: "hello", field: "bio" });
    expect(r).toMatchObject({ decision: "hold", unavailable: true, model: null });
  });

  it("fails closed on a malformed answer (missing question)", async () => {
    const partial = nouls();
    delete (partial as Record<string, unknown>).hate;
    const r = await new TypeSafeModerator(stubClient(partial)).moderateText({ text: "hello", field: "bio" });
    expect(r.unavailable).toBe(true);
    expect(r.decision).toBe("hold");
  });

  it("still blocks deterministic hits during an outage", async () => {
    const m = new TypeSafeModerator(stubClient(() => { throw new Error("timeout"); }));
    expect((await m.moderateText({ text: "call 555 212 8899", field: "bio" })).decision).toBe("block");
  });

  it("triages reports and maps urgency to priority", async () => {
    const answers = {
      category: { type: "choice", choice: "threats_or_violence", confidence: 0.9, probabilities: {} },
      urgency: { type: "choice", choice: "immediate_danger", confidence: 0.95, probabilities: {} },
    };
    const t = await new TypeSafeModerator(stubClient(answers)).triageReport({ reasonSelected: "harassment", description: "he said he'd wait outside my work", reportedDisplayName: null, reportedBio: null, recentMessages: [] });
    expect(t).toMatchObject({ category: "threats_or_violence", urgency: "immediate_danger", priority: 0, unavailable: false });
  });

  it("bumps priority when urgency confidence is low", async () => {
    const answers = {
      category: { type: "choice", choice: "spam", confidence: 0.9, probabilities: {} },
      urgency: { type: "choice", choice: "low", confidence: 0.2, probabilities: {} },
    };
    const t = await new TypeSafeModerator(stubClient(answers)).triageReport({ reasonSelected: "spam", description: "", reportedDisplayName: null, reportedBio: null, recentMessages: [] });
    expect(t.priority).toBe(2);
  });

  it("report triage fails closed to high urgency with the reporter's category", async () => {
    const t = await new TypeSafeModerator(stubClient(() => { throw new Error("x"); })).triageReport({ reasonSelected: "underage_user", description: "", reportedDisplayName: null, reportedBio: null, recentMessages: [] });
    expect(t).toMatchObject({ category: "underage_user", urgency: "high", priority: 0, unavailable: true });
  });

  it("fromKey returns null without a key", () => {
    expect(TypeSafeModerator.fromKey(null)).toBeNull();
    expect(TypeSafeModerator.fromKey("")).toBeNull();
  });
});

describe("fallback moderators", () => {
  it("UnavailableModerator holds everything", async () => {
    expect((await new UnavailableModerator().moderateText({ text: "hi", field: "bio" })).decision).toBe("hold");
  });
  it("FakeModerator follows markers through the real policy", async () => {
    const f = new FakeModerator();
    expect((await f.moderateText({ text: "hi", field: "chat_message" })).decision).toBe("allow");
    expect((await f.moderateText({ text: "[[harassment]]", field: "chat_message" })).decision).toBe("block");
    expect((await f.moderateText({ text: "[[hold:hate]]", field: "chat_message" })).decision).toBe("hold");
    expect((await f.moderateText({ text: "[[outage]]", field: "chat_message" })).unavailable).toBe(true);
  });
});
