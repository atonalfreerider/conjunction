/**
 * Match cards: swipe (accept / reject), the legacy `connect` alias, card details with photo, snooze.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { LIMITS } from "../config.js";
import { db } from "../firebase.js";
import { callable } from "../lib/callable.js";
import type { EncounterDoc, SwipeDirection } from "../lib/encounterRules.js";
import { utcToday } from "../lib/matchFilter.js";
import { signedPhotoUrl } from "../lib/photos.js";
import { cardOf, type UserDoc } from "../lib/users.js";
import { sendPush } from "../notify.js";
import { encounterIdSchema, snoozeSchema, swipeSchema } from "../schemas.js";

export type SwipeOutcome = "waiting" | "matched" | "rejected";

async function doSwipe(uid: string, encounterId: string, direction: SwipeDirection, now: Date) {
  const fs = db();
  const encRef = fs.collection("encounters").doc(encounterId);
  const outcome = await fs.runTransaction(async (tx) => {
    const snap = await tx.get(encRef);
    if (!snap.exists) throw new HttpsError("not-found", "This match is no longer available.");
    const enc = snap.data() as EncounterDoc;
    const side = enc.uids.indexOf(uid);
    if (side < 0) throw new HttpsError("permission-denied", "Not your match.");
    const otherUid = enc.uids[1 - side]!;
    // A block by either side since the card was created closes it.
    const [b1, b2] = await tx.getAll(
      fs.collection("blocks").doc(uid).collection("list").doc(otherUid),
      fs.collection("blocks").doc(otherUid).collection("list").doc(uid),
    );
    if (b1!.exists || b2!.exists) throw new HttpsError("not-found", "This match is no longer available.");
    if (enc.status === "matched") return { outcome: "matched" as const, enc, otherUid, changed: false };
    if (enc.status !== "pending") throw new HttpsError("failed-precondition", "This match is closed.");
    if (enc.expiresAt.toMillis() <= now.getTime()) throw new HttpsError("deadline-exceeded", "This match card has expired.");

    const field = side === 0 ? "swipeA" : "swipeB";
    const mine = direction;
    const theirs = side === 0 ? enc.swipeB : enc.swipeA;
    const nowTs = Timestamp.fromDate(now);
    if (mine === "left") {
      // Silent: the other side is never told. The pair is not shown again for 30 days.
      const until = Timestamp.fromMillis(now.getTime() + LIMITS.rejectCooldownMs);
      tx.update(encRef, {
        [field]: "left",
        status: "rejected",
        rejectedUntil: until,
        purgeAt: Timestamp.fromMillis(until.toMillis() + 24 * 3600_000),
      });
      return { outcome: "rejected" as const, enc, otherUid, changed: true };
    }
    if (theirs === "right") {
      // Both accepted: friends both ways + chat + "It's a Conjunction!" for both.
      tx.update(encRef, { [field]: "right", status: "matched", matchedAt: nowTs });
      for (const [x, y] of [[uid, otherUid], [otherUid, uid]] as const) {
        tx.set(fs.collection("friends").doc(x).collection("list").doc(y), { pairId: encounterId, kind: enc.kind, since: nowTs });
      }
      tx.set(fs.collection("chats").doc(encounterId), {
        uids: enc.uids,
        kind: enc.kind,
        status: "open",
        createdAt: nowTs,
        lastMessageAt: null,
        lastMessage: null,
        readAt: {},
        messageCounts: { [enc.uids[0]]: 0, [enc.uids[1]]: 0 },
      });
      return { outcome: "matched" as const, enc, otherUid, changed: true };
    }
    tx.update(encRef, { [field]: "right" });
    return { outcome: "waiting" as const, enc, otherUid, changed: true };
  });

  if (outcome.outcome === "matched" && outcome.changed) {
    const users = await fs.getAll(...outcome.enc.uids.map((u) => fs.collection("users").doc(u)));
    await Promise.all(
      outcome.enc.uids.map((u, i) => {
        const other = users[1 - i]!.data() as UserDoc | undefined;
        const name = other?.displayName?.split(/\s+/)[0];
        return sendPush(u, {
          kind: "conjunction",
          title: "It's a Conjunction!",
          body: name ? `You and ${name} both said yes. Say hello ✨` : "You both said yes. Say hello ✨",
          data: { encounterId, pairId: encounterId, matchKind: outcome.enc.kind, total: String(outcome.enc.score.total) },
        });
      }),
    );
  }
  return { outcome: outcome.outcome, pairId: outcome.outcome === "matched" ? encounterId : null };
}

/** `swipe(encounterId, direction)` — SPEC 5b. */
export const swipe = callable(swipeSchema, async (data, { uid, now }) => doSwipe(uid, data.encounterId, data.direction, now));

/** `connect(encounterId)` — original SPEC 5 name, kept as an alias for a right swipe. */
export const connect = callable(encounterIdSchema, async (data, { uid, now }) => doSwipe(uid, data.encounterId, "right", now));

/**
 * The other person's match card, fresh, with a 15-minute photo URL when their photo is approved
 * (SPEC 5b: approved photos are shown on match cards before mutual acceptance).
 */
export const getEncounterCard = callable(encounterIdSchema, async (data, { uid, now }) => {
  const fs = db();
  const snap = await fs.collection("encounters").doc(data.encounterId).get();
  const enc = snap.data() as EncounterDoc | undefined;
  if (!enc || !enc.uids.includes(uid)) throw new HttpsError("not-found", "This match is no longer available.");
  const otherUid = enc.uids[0] === uid ? enc.uids[1] : enc.uids[0];
  const [b1, b2, otherSnap] = await fs.getAll(
    fs.collection("blocks").doc(uid).collection("list").doc(otherUid),
    fs.collection("blocks").doc(otherUid).collection("list").doc(uid),
    fs.collection("users").doc(otherUid),
  );
  if (b1!.exists || b2!.exists || !otherSnap!.exists) throw new HttpsError("not-found", "This match is no longer available.");
  const other = otherSnap!.data() as UserDoc;
  const card = other.compat ? cardOf(other, utcToday(now)) : enc.cards[otherUid];
  const photoUrl = other.photo?.approved && enc.status !== "rejected" && enc.status !== "closed" ? await signedPhotoUrl(otherUid, now) : null;
  return {
    encounterId: data.encounterId,
    otherUid,
    kind: enc.kind,
    status: enc.status,
    score: enc.score,
    birthdayTwin: enc.birthdayTwin,
    exactTwin: enc.exactTwin,
    card,
    photoUrl,
    expiresAt: enc.expiresAt.toDate().toISOString(),
  };
});

/** Snooze one person: no encounters with them for `hours` (max 30 days). */
export const snooze = callable(snoozeSchema, async (data, { uid, now }) => {
  if (data.uid === uid) throw new HttpsError("invalid-argument", "You can't snooze yourself.");
  const until = Timestamp.fromMillis(now.getTime() + Math.min(data.hours * 3600_000, LIMITS.maxSnoozeMs));
  await db().collection("snoozes").doc(uid).collection("list").doc(data.uid).set({ until, createdAt: FieldValue.serverTimestamp() });
  return { until: until.toDate().toISOString() };
});
