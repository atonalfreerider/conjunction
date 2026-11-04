/** Block and report. Reports are triaged by TypeSafe (category + urgency) and land in `reports/`. */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { getModerator, LIMITS } from "../config.js";
import { db } from "../firebase.js";
import { callable } from "../lib/callable.js";
import { pairId } from "../lib/pair.js";
import { consumeRateLimit } from "../lib/rateLimit.js";
import type { UserDoc } from "../lib/users.js";
import { normalizeText } from "../moderation/patterns.js";
import { endMatch } from "./chat.js";
import { otherUidSchema, reportSchema } from "../schemas.js";

/** Blocks `other` for `uid`: no encounters, cards, profile or chat between them from now on. */
export async function blockUser(uid: string, other: string): Promise<void> {
  if (uid === other) throw new HttpsError("invalid-argument", "You can't block yourself.");
  const fs = db();
  const id = pairId(uid, other);
  await fs.collection("blocks").doc(uid).collection("list").doc(other).set({ at: FieldValue.serverTimestamp() });
  const friend = await fs.collection("friends").doc(uid).collection("list").doc(other).get();
  if (friend.exists) {
    await endMatch(uid, other, id);
  } else {
    const enc = fs.collection("encounters").doc(id);
    await fs.runTransaction(async (tx) => {
      const snap = await tx.get(enc);
      if (snap.exists) tx.update(enc, { status: "closed" });
    });
  }
}

export const block = callable(otherUidSchema, async (d, { uid }) => {
  await blockUser(uid, d.uid);
  return { ok: true };
});

/** How many recent messages from the reported person are attached as evidence. */
const EVIDENCE_MESSAGES = 20;

export const report = callable(reportSchema, async (d, { uid, now }) => {
  if (d.uid === uid) throw new HttpsError("invalid-argument", "You can't report yourself.");
  await consumeRateLimit(uid, "report", LIMITS.reportsPerDay, 24 * 3600_000, now);
  const fs = db();
  const reportedSnap = await fs.collection("users").doc(d.uid).get();
  const reported = reportedSnap.data() as UserDoc | undefined;

  // Evidence snapshot: only from a chat the reporter is part of, only the reported person's messages.
  let messages: Array<{ text: string; at: string }> = [];
  const chatId = d.pairId ?? pairId(uid, d.uid);
  const chat = await fs.collection("chats").doc(chatId).get();
  const chatUids = chat.get("uids") as string[] | undefined;
  if (chat.exists && chatUids?.includes(uid) && chatUids.includes(d.uid)) {
    const msgs = await chat.ref.collection("messages").where("from", "==", d.uid).orderBy("createdAt", "desc").limit(EVIDENCE_MESSAGES).get();
    messages = msgs.docs.map((m) => ({ text: m.get("text") as string, at: (m.get("createdAt") as Timestamp).toDate().toISOString() })).reverse();
  }
  const description = normalizeText(d.description);
  const triage = await getModerator().triageReport({
    reasonSelected: d.reason,
    description,
    reportedDisplayName: reported?.displayName ?? null,
    reportedBio: reported?.bio ?? null,
    recentMessages: messages.map((m) => m.text),
  });

  const ref = fs.collection("reports").doc();
  await ref.set({
    reporterUid: uid,
    reportedUid: d.uid,
    pairId: chat.exists ? chatId : null,
    encounterId: d.encounterId ?? null,
    reason: d.reason,
    description,
    evidence: {
      displayName: reported?.displayName ?? null,
      bio: reported?.bio ?? null,
      photoApproved: !!reported?.photo?.approved,
      messages,
    },
    triage,
    priority: triage.priority,
    status: "open",
    createdAt: FieldValue.serverTimestamp(),
  });
  if (triage.priority === 0) {
    // Structured log line for a Cloud Monitoring log-based alert (README: "Urgent report alert").
    logger.error("URGENT_REPORT", { reportId: ref.id, category: triage.category, urgency: triage.urgency });
  }
  if (d.alsoBlock) await blockUser(uid, d.uid);
  return { reportId: ref.id, blocked: d.alsoBlock };
});
