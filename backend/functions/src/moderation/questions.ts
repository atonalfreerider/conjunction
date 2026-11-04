/**
 * TypeSafe (Jev) question batteries for Conjunction.
 *
 * Design (per the TypeSafe docs: one narrow judgment per question, complete meaning in the
 * instructions, boundary cases in `criteria`, all questions over the same state in ONE request):
 *
 * - Text moderation sends one state — the text plus its context (which field it is, who will see it,
 *   whether the recipient is a brand-new connection, whether the pair matched for dating or for
 *   friends) — and asks one Noul per violation. Code (policy.ts) owns the thresholds.
 * - Report triage sends the report plus the reported content as evidence and asks two Choices
 *   (category, urgency). Code maps them to a review-queue priority.
 *
 * Deterministic things (URLs, e-mail addresses, phone numbers, length limits) are detected in code
 * (`patterns.ts`), not asked of the model; the model handles meaning — insults, obfuscated contact
 * details ("my insta is j dot doe"), off-platform pressure, and so on.
 */
import { choice, noul, type NoulQuestion } from "@typesafe-ai/sdk";

/** Fields whose text strangers or matches see. */
export type TextField = "display_name" | "bio" | "chat_message";

export const VIOLATIONS = [
  "harassment",
  "sexual",
  "scam",
  "contact_info",
  "offplatform_lure",
  "hate",
  "underage",
  "self_harm",
] as const;
export type Violation = (typeof VIOLATIONS)[number];

/** Pinned model: thresholds in policy.ts were checked against this version (see eval-moderation.ts). */
export const DEFAULT_MODEL = "jev-1.14.0";

const FIELD_DESCRIPTIONS: Record<TextField, string> = {
  display_name: "The user's public display name, shown on their match card to nearby strangers.",
  bio: "The user's short profile bio, shown on their match card to nearby strangers and to matches.",
  chat_message: "A private chat message sent to someone the sender matched with in the app.",
};

const APP_CONTEXT =
  "Conjunction is an 18+ astrology app that introduces people who are physically nearby, for dating or for " +
  "friendship. Talking about zodiac signs, charts and compatibility, friendly flirting, compliments on " +
  "someone's profile, and suggesting meeting for coffee or a walk in a public place are all normal and allowed.";

export interface TextModerationState {
  app: string;
  text: string;
  context: {
    field: TextField;
    field_description: string;
    recipient_is_new_connection: boolean | null;
    match_kind: "dating" | "friends" | null;
  };
}

export function textState(
  text: string,
  field: TextField,
  opts: { isNewConnection?: boolean; matchKind?: "dating" | "friends" | null } = {},
): TextModerationState {
  return {
    app: APP_CONTEXT,
    text,
    context: {
      field,
      field_description: FIELD_DESCRIPTIONS[field],
      recipient_is_new_connection: field === "chat_message" ? (opts.isNewConnection ?? true) : null,
      match_kind: opts.matchKind ?? null,
    },
  };
}

const q = (instructions: string, yes: string, no: string): NoulQuestion => noul(instructions, { true: yes, false: no });

/** One Noul per violation, all asked about the same state in one request. */
export const TEXT_QUESTIONS = {
  harassment: q(
    "Does `text` insult, demean, mock, threaten, intimidate, or harass the reader or any other person?",
    "It contains an insult, a put-down, a threat, intimidation, or harassment aimed at someone.",
    "It is friendly, neutral, playful teasing about zodiac signs, or a polite refusal.",
  ),
  sexual: q(
    "Does `text` contain sexually explicit language, describe sexual acts or body parts in a sexual way, or ask for sex, nude photos, or sexual favours?",
    "It is sexually explicit or asks for sex, nudes, or sexual acts.",
    "It has no sexual content, or only mild romantic interest such as compliments, saying someone is cute, or asking for a date.",
  ),
  scam: q(
    "Does `text` ask for money, gifts, gift cards, cryptocurrency, an investment, bank or card details, or advertise or sell a product, service, or paid content?",
    "It solicits money or financial details, promotes an investment or scheme, or advertises something for sale (including paid sexual services).",
    "It does not ask for money or sell anything; offering to buy someone a coffee on a date is not solicitation.",
  ),
  contact_info: q(
    "Does `text` share or ask for a way to contact someone outside this app, such as a phone number, e-mail address, social-media handle or username, messaging app account, or home or street address, even if written in a disguised way?",
    "It contains or requests off-app contact details or an address, including disguised spellings like 'five five five' or 'insta: jdoe'.",
    "It contains no contact details and does not ask for any; mentioning a public place to meet, like a cafe or park, is not contact information.",
  ),
  offplatform_lure: q(
    "Does `text` push the reader to leave this app — to move the conversation to another app or website, open a link, download something, or verify themselves elsewhere?",
    "It urges or pressures the reader to continue elsewhere, open a link, or sign up or verify on another site.",
    "It does not urge anyone to leave the app. Suggesting meeting in person in a public place is not leaving the app, and calmly sharing a username or number to keep in touch, without urging or a link, does not count here (contact details are judged by a separate question).",
  ),
  hate: q(
    "Does `text` attack, demean, or use slurs against people because of race, ethnicity, nationality, religion, caste, sexual orientation, sex, gender identity, disability, or serious illness?",
    "It contains a slur or demeans people for a protected characteristic.",
    "It does not target anyone for a protected characteristic; stating one's own preferences politely or joking about zodiac signs is not hate.",
  ),
  underage: q(
    "Does `text` say or suggest that the writer, or the person they are talking to or about, is under 18 years old?",
    "It states or implies an age under 18, being in high school or middle school, or needing a parent's permission.",
    "It gives no sign that anyone involved is a minor.",
  ),
  self_harm: q(
    "Does `text` suggest the writer may be thinking about hurting or killing themselves?",
    "It hints at suicidal thoughts or an intent to self-harm.",
    "It shows no sign the writer intends to harm themselves.",
  ),
} satisfies Record<Violation, NoulQuestion>;

// ---------------------------------------------------------------------------------------------
// Report triage

export const REPORT_CATEGORIES = {
  harassment: "Insults, bullying, unwanted repeated contact, or intimidation.",
  threats_or_violence: "Threats to hurt someone, stalking, or violence, online or in person.",
  sexual_content: "Unwanted sexual messages, explicit photos, or sexual pressure.",
  scam_or_solicitation: "Asking for money, investments, or financial details, or selling something.",
  hate: "Slurs or attacks based on race, religion, sexuality, gender, disability, or similar.",
  underage_user: "The reported person appears to be under 18.",
  fake_profile: "Impersonation, a fake or stolen photo, or a bot account.",
  spam: "Repeated promotional or irrelevant messages, or links.",
  in_person_incident: "Something unsafe that happened when the two people met in person.",
  other: "None of the above.",
} as const;
export type ReportCategory = keyof typeof REPORT_CATEGORIES;

export const REPORT_URGENCIES = {
  immediate_danger: "Someone may be in danger now or soon: a threat of violence, stalking, an assault, self-harm, or a minor at risk.",
  high: "A serious violation a moderator should see within hours: sexual harassment, a scam targeting money, hate, or a fake profile used to deceive.",
  normal: "A rule violation with no sign of danger, such as rudeness or spam.",
  low: "A minor annoyance or a disagreement that may not break any rule.",
} as const;
export type ReportUrgency = keyof typeof REPORT_URGENCIES;

export const REPORT_QUESTIONS = {
  category: choice(
    "Which category best describes what `report.description` and `evidence` say the reported user did?",
    REPORT_CATEGORIES,
  ),
  urgency: choice(
    "How urgently should a moderator look at this report, given `report.description` and `evidence`?",
    REPORT_URGENCIES,
  ),
};

export interface ReportState {
  app: string;
  report: { reason_selected_by_reporter: string | null; description: string };
  evidence: {
    reported_display_name: string | null;
    reported_bio: string | null;
    recent_messages_from_reported_user: string[];
  };
}

export function reportState(input: {
  reasonSelected: string | null;
  description: string;
  reportedDisplayName: string | null;
  reportedBio: string | null;
  recentMessages: string[];
}): ReportState {
  return {
    app: APP_CONTEXT,
    report: { reason_selected_by_reporter: input.reasonSelected, description: input.description },
    evidence: {
      reported_display_name: input.reportedDisplayName,
      reported_bio: input.reportedBio,
      recent_messages_from_reported_user: input.recentMessages,
    },
  };
}
