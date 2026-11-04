/**
 * Human review and automatic retry of held content.
 *
 * - `reviewModeration` (moderators only: custom claim `admin: true`, set with the Admin SDK — see
 *   README) approves or rejects a `moderationQueue` item and applies the result.
 * - `retryHeldModeration` runs every 15 minutes and re-checks text that was held only because
 *   TypeSafe was unreachable; text the model now allows is published / delivered automatically,
 *   text it blocks is rejected, anything uncertain stays for a person.
 */
import { FieldValue, Timestamp, type DocumentSnapshot } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import { getModerator, REGION, TYPESAFE_API_KEY } from "../config.js";
import { bucket, db } from "../firebase.js";
import { callable } from "../lib/callable.js";
import { summarize } from "../lib/queue.js";
import type { TextField } from "../moderation/questions.js";
import { deliverMessage } from "./chat.js";
import { publishPhoto } from "./photos.js";
import { reviewSchema } from "../schemas.js";

type Outcome = "approve" | "reject";

async function applyDecision(item: DocumentSnapshot, decision: Outcome, now: Date): Promise<void> {
  const type = item.get("type") as string;
  const uid = item.get("uid") as string;
  const payload = (item.get("payload") ?? {}) as Record<string, unknown>;
  const userRef = db().collection("users").doc(uid);
  if (type === "display_name" || type === "bio") {
    const field = type === "display_name" ? "displayName" : "bio";
    await db().runTransaction(async (tx) => {
      const user = await tx.get(userRef);
      if (!user.exists) return;
      // Only apply if the user has not replaced the pending text since.
      if (user.get(`pending.${field}`) !== payload.text) return;
      tx.update(userRef, decision === "approve"
        ? { [field]: payload.text, [`pending.${field}`]: FieldValue.delete() }
        : { [`pending.${field}`]: FieldValue.delete() });
    });
  } else if (type === "message") {
    if (decision === "approve") {
      const chatRef = db().collection("chats").doc(payload.pairId as string);
      const [chat, friend] = await db().getAll(chatRef, db().collection("friends").doc(uid).collection("list").doc(payload.to as string));
      if (chat!.exists && chat!.get("status") === "open" && friend!.exists) {
        await deliverMessage(chatRef, uid, payload.to as string, payload.text as string, (payload.clientMessageId as string | null) ?? null, now);
      }
    }
  } else if (type === "photo") {
    const held = bucket().file(payload.path as string);
    const [exists] = await held.exists();
    if (decision === "approve" && exists) {
      const [bytes] = await held.download();
      await publishPhoto(uid, bytes);
    } else {
      await userRef.set({ pendingPhoto: { uploadId: payload.uploadId, status: "rejected", reason: "not_allowed", updatedAt: Timestamp.fromDate(now) } }, { merge: true });
    }
    await held.delete({ ignoreNotFound: true });
  }
}

export const reviewModeration = callable(reviewSchema, async (d, { uid, request, now }) => {
  if (request.auth?.token.admin !== true) throw new HttpsError("permission-denied", "Moderators only.");
  const ref = db().collection("moderationQueue").doc(d.itemId);
  const item = await ref.get();
  if (!item.exists) throw new HttpsError("not-found", "No such review item.");
  if (item.get("status") !== "open") throw new HttpsError("failed-precondition", "Already resolved.");
  await applyDecision(item, d.decision, now);
  await ref.update({ status: "resolved", decision: d.decision, decidedBy: uid, decidedAt: FieldValue.serverTimestamp(), note: d.note ?? null });
  return { ok: true };
});

/** Re-moderates items held because TypeSafe was unreachable. Exported for tests. */
export async function retryUnavailable(now: Date, limit = 100): Promise<{ retried: number; resolved: number }> {
  const snap = await db().collection("moderationQueue").where("reason", "==", "unavailable").where("status", "==", "open").limit(limit).get();
  let resolved = 0;
  for (const item of snap.docs) {
    const type = item.get("type") as string;
    const payload = item.get("payload") as Record<string, unknown>;
    const field: TextField | null = type === "display_name" ? "display_name" : type === "bio" ? "bio" : type === "message" ? "chat_message" : null;
    if (!field) continue;
    const result = await getModerator().moderateText({ text: payload.text as string, field, isNewConnection: true });
    if (result.unavailable) {
      const attempts = ((item.get("attempts") as number | undefined) ?? 0) + 1;
      // After 8 failed attempts (~2 h) leave it for a person.
      await item.ref.update({ attempts, ...(attempts >= 8 ? { reason: "held" } : {}) });
      continue;
    }
    if (result.decision === "hold") {
      await item.ref.update({ reason: "held", moderation: summarize(result) });
      continue;
    }
    const decision: Outcome = result.decision === "allow" ? "approve" : "reject";
    await applyDecision(item, decision, now);
    await item.ref.update({ status: "resolved", decision, decidedBy: "auto-retry", decidedAt: FieldValue.serverTimestamp(), moderation: summarize(result) });
    resolved++;
  }
  return { retried: snap.size, resolved };
}

export const retryHeldModeration = onSchedule(
  { schedule: "every 15 minutes", region: REGION, secrets: [TYPESAFE_API_KEY], timeoutSeconds: 300 },
  async () => {
    const r = await retryUnavailable(new Date());
    if (r.retried) logger.info("retried held moderation", r);
  },
);
