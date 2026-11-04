/**
 * Friends and chat: profile of a match, messages (moderated before delivery), read state, unmatch.
 * A chat exists only after both people swiped right; messages before that are impossible.
 */
import { FieldValue, Timestamp, type DocumentReference } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { getModerator, LIMITS } from "../config.js";
import { db } from "../firebase.js";
import { callable } from "../lib/callable.js";
import { signedPhotoUrl } from "../lib/photos.js";
import { consumeRateLimit } from "../lib/rateLimit.js";
import { enqueue, summarize } from "../lib/queue.js";
import { signsOf, type UserDoc } from "../lib/users.js";
import { normalizeText } from "../moderation/patterns.js";
import { ESTABLISHED_MIN_MESSAGES_EACH } from "../moderation/policy.js";
import { sendPush } from "../notify.js";
import { otherUidSchema, pairIdSchema, sendMessageSchema } from "../schemas.js";

export interface ChatDoc {
  uids: [string, string];
  kind: "dating" | "friends";
  status: "open" | "closed";
  createdAt: Timestamp;
  lastMessageAt: Timestamp | null;
  lastMessage: { from: string; preview: string; at: Timestamp } | null;
  readAt: Record<string, Timestamp>;
  messageCounts: Record<string, number>;
}

/** Profile of a mutual match (friend): name, first line of bio, big three, photo URL. */
export const getFriendProfile = callable(otherUidSchema, async (data, { uid, now }) => {
  const fs = db();
  const [friendSnap, otherSnap, b1, b2] = await fs.getAll(
    fs.collection("friends").doc(uid).collection("list").doc(data.uid),
    fs.collection("users").doc(data.uid),
    fs.collection("blocks").doc(uid).collection("list").doc(data.uid),
    fs.collection("blocks").doc(data.uid).collection("list").doc(uid),
  );
  if (!friendSnap!.exists || !otherSnap!.exists || b1!.exists || b2!.exists) {
    throw new HttpsError("permission-denied", "You can see this profile once you've both said yes.");
  }
  const other = otherSnap!.data() as UserDoc;
  const signs = other.compat ? signsOf(other.compat) : null;
  return {
    uid: data.uid,
    pairId: friendSnap!.get("pairId") as string,
    kind: friendSnap!.get("kind") as string,
    displayName: other.displayName ?? null,
    bioFirstLine: other.bio ? other.bio.split("\n")[0]!.slice(0, 160) : null,
    sun: signs?.sun ?? null,
    moon: signs?.moon ?? null,
    rising: signs?.rising ?? null,
    chineseAnimal: other.compat?.chineseAnimal ?? null,
    photoUrl: other.photo?.approved ? await signedPhotoUrl(data.uid, now) : null,
  };
});

async function loadChatForSender(uid: string, pairId: string) {
  const fs = db();
  const chatRef = fs.collection("chats").doc(pairId);
  const chatSnap = await chatRef.get();
  const chat = chatSnap.data() as ChatDoc | undefined;
  if (!chat || !chat.uids.includes(uid)) throw new HttpsError("not-found", "Chat not found.");
  if (chat.status !== "open") throw new HttpsError("failed-precondition", "This chat is closed.");
  const otherUid = chat.uids[0] === uid ? chat.uids[1] : chat.uids[0];
  const [friend, b1, b2, me] = await fs.getAll(
    fs.collection("friends").doc(uid).collection("list").doc(otherUid),
    fs.collection("blocks").doc(uid).collection("list").doc(otherUid),
    fs.collection("blocks").doc(otherUid).collection("list").doc(uid),
    fs.collection("users").doc(uid),
  );
  if (!friend!.exists || b1!.exists || b2!.exists) throw new HttpsError("permission-denied", "You can't message this person.");
  if ((me!.data() as UserDoc | undefined)?.status === "suspended") throw new HttpsError("permission-denied", "Your account is suspended.");
  return { chatRef, chat, otherUid, me: me!.data() as UserDoc };
}

/** Writes a moderated message and updates the chat summary. Used by sendMessage and by moderators approving a held message. */
export async function deliverMessage(chatRef: DocumentReference, from: string, to: string, text: string, messageId: string | null, now: Date): Promise<string> {
  const msgRef = messageId ? chatRef.collection("messages").doc(messageId) : chatRef.collection("messages").doc();
  const at = Timestamp.fromDate(now);
  const delivered = await db().runTransaction(async (tx) => {
    const [chatSnap, existing] = await tx.getAll(chatRef, msgRef);
    if (existing!.exists) return false; // idempotent retry
    if (!chatSnap!.exists || chatSnap!.get("status") !== "open") throw new HttpsError("failed-precondition", "This chat is closed.");
    tx.set(msgRef, { from, text, createdAt: at });
    tx.update(chatRef, {
      lastMessageAt: at,
      lastMessage: { from, preview: text.slice(0, 80), at },
      [`messageCounts.${from}`]: FieldValue.increment(1),
      [`readAt.${from}`]: at,
    });
    return true;
  });
  if (delivered) {
    const sender = (await db().collection("users").doc(from).get()).data() as UserDoc | undefined;
    const name = sender?.displayName?.split(/\s+/)[0] ?? "Your match";
    await sendPush(to, { kind: "message", title: name, body: "Sent you a message", data: { pairId: chatRef.id, messageId: msgRef.id } });
  }
  return msgRef.id;
}

async function send(uid: string, pairId: string, rawText: string, clientMessageId: string | undefined, now: Date, maxLen: number, introOnly: boolean) {
  await consumeRateLimit(uid, "msgMinute", LIMITS.messagesPerMinute, 60_000, now);
  await consumeRateLimit(uid, "msgDay", LIMITS.messagesPerDay, 24 * 3600_000, now);
  const { chatRef, chat, otherUid } = await loadChatForSender(uid, pairId);
  const text = normalizeText(rawText);
  if (!text) throw new HttpsError("invalid-argument", "Message is empty.");
  if (text.length > maxLen) throw new HttpsError("invalid-argument", `Messages can be up to ${maxLen} characters.`, { maxLength: maxLen });
  const counts = chat.messageCounts ?? {};
  if (introOnly && (counts[uid] ?? 0) > 0) throw new HttpsError("failed-precondition", "You've already said hello — use the chat.");
  const isNewConnection = chat.uids.some((u) => (counts[u] ?? 0) < ESTABLISHED_MIN_MESSAGES_EACH);

  const result = await getModerator().moderateText({ text, field: "chat_message", isNewConnection, matchKind: chat.kind });
  if (result.flags.length) {
    await db().runTransaction(async (tx) => {
      enqueue({ type: "safety_flag", uid, reason: "flag", payload: { pairId, from: uid, to: otherUid, text, flags: result.flags }, moderation: summarize(result), priority: 0 }, tx);
    });
  }
  if (result.decision === "block") {
    return { status: "blocked" as const, reasons: result.reasons, messageId: null };
  }
  if (result.decision === "hold") {
    const itemId = await db().runTransaction(async (tx) =>
      enqueue({
        type: "message",
        uid,
        reason: result.unavailable ? "unavailable" : "held",
        payload: { pairId, from: uid, to: otherUid, text, clientMessageId: clientMessageId ?? null, sentAt: Timestamp.fromDate(now) },
        moderation: summarize(result),
        priority: result.reasons.includes("underage") ? 0 : 2,
      }, tx),
    );
    return { status: "held" as const, reasons: result.reasons, messageId: null, reviewId: itemId };
  }
  const messageId = await deliverMessage(chatRef, uid, otherUid, text, clientMessageId ?? null, now);
  return { status: "delivered" as const, reasons: [], messageId };
}

/** Full chat message (SPEC 5b). Returns delivered | held (waiting for review) | blocked (with reasons). */
export const sendMessage = callable(sendMessageSchema, async (d, { uid, now }) => send(uid, d.pairId, d.text, d.clientMessageId, now, LIMITS.messageMax, false), { moderation: true });

/** The first hello (SPEC 5 `sendIntro`): only between friends, shorter limit, only once. */
export const sendIntro = callable(sendMessageSchema, async (d, { uid, now }) => send(uid, d.pairId, d.text, d.clientMessageId, now, LIMITS.introMax, true), { moderation: true });

export const markRead = callable(pairIdSchema, async (d, { uid, now }) => {
  const chatRef = db().collection("chats").doc(d.pairId);
  const snap = await chatRef.get();
  if (!snap.exists || !(snap.get("uids") as string[]).includes(uid)) throw new HttpsError("not-found", "Chat not found.");
  await chatRef.update({ [`readAt.${uid}`]: Timestamp.fromDate(now) });
  return { ok: true };
});

/**
 * Ends a match for both people: friendship removed both ways, chat closed and its messages deleted
 * (Privacy Policy §7: deleted within 30 days; we do it now). A report filed before unmatching keeps
 * its own evidence snapshot.
 */
export async function endMatch(a: string, b: string, pairIdValue: string): Promise<void> {
  const fs = db();
  const chatRef = fs.collection("chats").doc(pairIdValue);
  const encRef = fs.collection("encounters").doc(pairIdValue);
  await fs.runTransaction(async (tx) => {
    const [chat, enc] = await tx.getAll(chatRef, encRef);
    tx.delete(fs.collection("friends").doc(a).collection("list").doc(b));
    tx.delete(fs.collection("friends").doc(b).collection("list").doc(a));
    if (chat!.exists) tx.update(chatRef, { status: "closed", closedAt: FieldValue.serverTimestamp(), lastMessage: null });
    if (enc!.exists) tx.update(encRef, { status: "closed" });
  });
  await fs.recursiveDelete(chatRef.collection("messages"));
}

export const unmatch = callable(pairIdSchema, async (d, { uid }) => {
  const snap = await db().collection("chats").doc(d.pairId).get();
  const uids = snap.get("uids") as string[] | undefined;
  if (!uids || !uids.includes(uid)) throw new HttpsError("not-found", "Chat not found.");
  const other = uids[0] === uid ? uids[1]! : uids[0]!;
  await endMatch(uid, other, d.pairId);
  return { ok: true };
});
