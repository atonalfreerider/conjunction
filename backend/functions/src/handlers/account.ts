/**
 * Data rights: full account erasure and machine-readable export (Privacy Policy §8).
 */
import { createHash } from "node:crypto";
import { FieldValue, Timestamp, type DocumentData } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { LIMITS } from "../config.js";
import { auth, bucket, db } from "../firebase.js";
import { callable } from "../lib/callable.js";
import { profilePhotoPath, signedPhotoUrl } from "../lib/photos.js";
import { consumeRateLimit } from "../lib/rateLimit.js";
import type { UserDoc } from "../lib/users.js";
import { endMatch } from "./chat.js";
import { emptySchema } from "../schemas.js";

/**
 * Erases everything about `uid` from active systems:
 *   presence, profile, photos (approved, pending, uploads), encounters, matches and whole chats
 *   (both sides — "your messages disappear from your matches' chats"), the user's blocks and snoozes,
 *   rate-limit counters, open review items about their content, and the Auth account.
 * Kept, as the Privacy Policy §7–8 says: reports (filed by or about the user, as safety records),
 * consent / acceptance records (`consents/{uid}`), blocks *other* people made, and — only for a
 * suspended account — a SHA-256 of each non-anonymous sign-in identifier so a banned person cannot
 * simply re-register.
 */
export async function eraseUser(uid: string): Promise<void> {
  const fs = db();
  await fs.collection("presence").doc(uid).delete();

  // Matches: end each one (removes the other side's friend entry, closes chat, deletes messages).
  const friends = await fs.collection("friends").doc(uid).collection("list").get();
  for (const f of friends.docs) await endMatch(uid, f.id, f.get("pairId") as string);
  await fs.recursiveDelete(fs.collection("friends").doc(uid));

  // Chats (including closed ones) and encounters that involve the user.
  for (const coll of ["chats", "encounters"] as const) {
    const snap = await fs.collection(coll).where("uids", "array-contains", uid).get();
    for (const d of snap.docs) await fs.recursiveDelete(d.ref);
  }
  await fs.recursiveDelete(fs.collection("blocks").doc(uid));
  await fs.recursiveDelete(fs.collection("snoozes").doc(uid));
  for (const coll of ["rateLimits", "moderationQueue"] as const) {
    const snap = await fs.collection(coll).where("uid", "==", uid).get();
    const writer = fs.bulkWriter();
    for (const d of snap.docs) void writer.delete(d.ref);
    await writer.close();
  }

  const userRef = fs.collection("users").doc(uid);
  const user = (await userRef.get()).data() as UserDoc | undefined;
  if (user?.status === "suspended") {
    try {
      const record = await auth().getUser(uid);
      for (const p of record.providerData) {
        const hash = createHash("sha256").update(`${p.providerId}:${p.uid}`).digest("hex");
        await fs.collection("bannedIdentities").doc(hash).set({ at: FieldValue.serverTimestamp() });
      }
    } catch {
      // No auth record (already deleted): nothing to hash.
    }
  }
  await fs.recursiveDelete(userRef);

  const b = bucket();
  await b.file(profilePhotoPath(uid)).delete({ ignoreNotFound: true });
  await b.deleteFiles({ prefix: `uploads/${uid}/` });
  await b.deleteFiles({ prefix: `moderation/photos/${uid}/` });

  try {
    await auth().deleteUser(uid);
  } catch (err) {
    if ((err as { code?: string }).code !== "auth/user-not-found") throw err;
  }
}

export const deleteAccount = callable(emptySchema, async (_d, { uid }) => {
  await eraseUser(uid);
  logger.info("account erased", { uidHash: createHash("sha256").update(uid).digest("hex").slice(0, 12) });
  return { deleted: true };
}, { timeoutSeconds: 300, memory: "512MiB" });

/** Converts Firestore values (Timestamps, nested maps) into JSON-friendly values. */
function plain(value: unknown): unknown {
  if (value instanceof Timestamp) return value.toDate().toISOString();
  if (Array.isArray(value)) return value.map(plain);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.entries(value as DocumentData).map(([k, v]) => [k, plain(v)]));
  }
  return value;
}

/**
 * Everything we hold about the caller, as JSON: profile, chart summary, settings, consents, matches,
 * encounters (with their own card), messages they sent, blocks, snoozes, reports they filed, and the
 * status of their content in review. The photo is included as a 15-minute download link.
 */
export const exportData = callable(emptySchema, async (_d, { uid, now }) => {
  await consumeRateLimit(uid, "export", LIMITS.exportsPerDay, 24 * 3600_000, now);
  const fs = db();
  const user = (await fs.collection("users").doc(uid).get()).data() ?? null;
  if (user?.push?.tokens) user.push.tokens = (user.push.tokens as Array<{ platform: string; updatedAt: Timestamp }>).map((t) => ({ platform: t.platform, updatedAt: t.updatedAt }));
  const [consents, friends, blocks, snoozes, encounters, chats, reports, review] = await Promise.all([
    fs.collection("consents").doc(uid).collection("records").get(),
    fs.collection("friends").doc(uid).collection("list").get(),
    fs.collection("blocks").doc(uid).collection("list").get(),
    fs.collection("snoozes").doc(uid).collection("list").get(),
    fs.collection("encounters").where("uids", "array-contains", uid).get(),
    fs.collection("chats").where("uids", "array-contains", uid).get(),
    fs.collection("reports").where("reporterUid", "==", uid).get(),
    fs.collection("moderationQueue").where("uid", "==", uid).get(),
  ]);
  const chatExports = await Promise.all(
    chats.docs.map(async (c) => {
      const sent = await c.ref.collection("messages").where("from", "==", uid).get();
      return {
        pairId: c.id,
        kind: c.get("kind"),
        status: c.get("status"),
        createdAt: plain(c.get("createdAt")),
        messagesYouSent: sent.docs.map((m) => plain({ id: m.id, ...m.data() })),
      };
    }),
  );
  return {
    exportedAt: now.toISOString(),
    uid,
    profile: plain(user),
    photoDownloadUrl: user?.photo?.approved ? await signedPhotoUrl(uid, now) : null,
    consents: consents.docs.map((d) => plain(d.data())),
    matches: friends.docs.map((d) => plain({ uid: d.id, ...d.data() })),
    encounters: encounters.docs.map((d) => {
      const e = d.data();
      return plain({ id: d.id, kind: e.kind, status: e.status, score: e.score, birthdayTwin: e.birthdayTwin, exactTwin: e.exactTwin, createdAt: e.createdAt, yourCard: e.cards?.[uid] ?? null });
    }),
    chats: chatExports,
    blocks: blocks.docs.map((d) => plain({ uid: d.id, ...d.data() })),
    snoozes: snoozes.docs.map((d) => plain({ uid: d.id, ...d.data() })),
    reportsYouFiled: reports.docs.map((d) => plain({ id: d.id, reportedUid: d.get("reportedUid"), reason: d.get("reason"), description: d.get("description"), status: d.get("status"), createdAt: d.get("createdAt") })),
    contentInReview: review.docs.map((d) => plain({ id: d.id, type: d.get("type"), status: d.get("status"), createdAt: d.get("createdAt") })),
  };
}, { timeoutSeconds: 120 });

