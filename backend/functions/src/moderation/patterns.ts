/**
 * Deterministic detectors that belong in code, not in the model: links, e-mail addresses and phone
 * numbers. The model (TypeSafe `contact_info` / `offplatform_lure` Nouls) handles disguised forms.
 */

const URL_RE = /\b(?:https?:\/\/|www\.)\S+|\b[a-z0-9-]{2,}\.(?:com|net|org|io|co|me|ly|gg|app|xyz|info|biz|link|site|online|club|ru|cn|tk)\b(?:\/\S*)?/i;
const EMAIL_RE = /[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}/i;
// 7+ digits, optionally separated by spaces, dots, dashes or parentheses, optional leading +.
const PHONE_RE = /(?:\+?\d[\s().-]*){7,}/;

export interface PatternHits {
  url: boolean;
  email: boolean;
  phone: boolean;
}

export function detectPatterns(text: string): PatternHits {
  return { url: URL_RE.test(text), email: EMAIL_RE.test(text), phone: PHONE_RE.test(text) };
}

/** Collapses whitespace and strips control / zero-width / bidi-override characters. */
export function normalizeText(text: string): string {
  return text
    .normalize("NFC")
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F​-‏‪-‮⁠-⁤﻿]/g, "")
    .replace(/[ \t]+/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

/** Display names: letters (any script), marks, spaces, apostrophes, hyphens and periods, 1–40 chars. */
const DISPLAY_NAME_RE = /^[\p{L}\p{M}][\p{L}\p{M} '’.-]{0,39}$/u;

export function isValidDisplayName(name: string): boolean {
  return DISPLAY_NAME_RE.test(name) && !/\s{2,}/.test(name);
}
