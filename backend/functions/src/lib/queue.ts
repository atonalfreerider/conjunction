/**
 * Human review queue (`moderationQueue/{id}`), readable by no client. Items are created when text or a
 * photo is held, when a non-blocking safety flag fires (possible self-harm), and for reports.
 * Moderators resolve them with the `reviewModeration` callable (custom claim `admin: true`).
 */
import { FieldValue, type Transaction } from "firebase-admin/firestore";
import { db } from "../firebase.js";
import type { TextModerationResult } from "../moderation/moderator.js";

export type QueueItemType = "display_name" | "bio" | "message" | "photo" | "safety_flag";

export interface QueueItem {
  type: QueueItemType;
  uid: string;
  /** Why it is here: the model held it, TypeSafe was unreachable, or a flag fired. */
  reason: "held" | "unavailable" | "flag" | "vision_unavailable" | "vision_uncertain" | "manual_review";
  payload: Record<string, unknown>;
  moderation?: Pick<TextModerationResult, "decision" | "reasons" | "flags" | "scores" | "model" | "unavailable"> | null;
  /** 0 = look now … 3 = low. */
  priority: number;
}

/** Adds an item inside a transaction; returns its id. */
export function enqueue(item: QueueItem, tx: Transaction): string {
  const ref = db().collection("moderationQueue").doc();
  tx.set(ref, { ...item, status: "open", attempts: 0, createdAt: FieldValue.serverTimestamp() });
  return ref.id;
}

export async function enqueueAndWait(item: QueueItem): Promise<string> {
  const ref = db().collection("moderationQueue").doc();
  await ref.set({ ...item, status: "open", attempts: 0, createdAt: FieldValue.serverTimestamp() });
  return ref.id;
}

export function summarize(result: TextModerationResult): QueueItem["moderation"] {
  return {
    decision: result.decision,
    reasons: result.reasons,
    flags: result.flags,
    scores: result.scores,
    model: result.model,
    unavailable: result.unavailable,
  };
}
