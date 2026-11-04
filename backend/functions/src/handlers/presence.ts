/**
 * updatePresence — the Nearby engine.
 *
 * The phone reports its position about every 60 s or 25 m while Nearby is on. We keep only the latest
 * fix in `presence/{uid}` (geohash-7 + lat/lon, `expiresAt` = now + 10 min, deleted by Firestore TTL),
 * look up other fresh presences in the cells covering a 50 m circle, keep those within 50 m by
 * haversine distance, and for each eligible pair create an encounter and push both phones.
 *
 * Nobody ever receives a position or a distance: the encounter says only "nearby".
 */
import { Timestamp, type DocumentSnapshot } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { LIMITS } from "../config.js";
import { db } from "../firebase.js";
import { callable } from "../lib/callable.js";
import { evaluatePair, isPaused, type EncounterDoc } from "../lib/encounterRules.js";
import { haversineMeters } from "../lib/geo.js";
import { cellsCovering, encode } from "../lib/geohash.js";
import { utcToday } from "../lib/matchFilter.js";
import { pairId, sortedPair } from "../lib/pair.js";
import { cardOf, nearbyBlockers, signsOf, type UserDoc } from "../lib/users.js";
import { displaySign } from "../lib/zodiac.js";
import { sendPush, type PushKind } from "../notify.js";
import { updatePresenceSchema } from "../schemas.js";

/** Fixes worse than this are not used for matching (a 50 m radius needs a reasonable fix). */
const MAX_ACCURACY_M = 150;

interface PresenceDoc {
  uid: string;
  geohash: string;
  lat: number;
  lon: number;
  updatedAt: Timestamp;
  expiresAt: Timestamp;
}

export const updatePresence = callable(updatePresenceSchema, async (data, { uid, now }) => {
  const nowMs = now.getTime();
  const today = utcToday(now);
  const userRef = db().collection("users").doc(uid);
  const presenceRef = db().collection("presence").doc(uid);

  const [userSnap, presenceSnap] = await db().getAll(userRef, presenceRef);
  const me = userSnap!.data() as UserDoc | undefined;
  if (!me?.nearby?.enabled) {
    await presenceRef.delete();
    throw new HttpsError("failed-precondition", "Nearby is off.", { missing: ["nearby"] });
  }
  if (isPaused(me, nowMs)) {
    await presenceRef.delete();
    return { status: "paused" as const, pausedUntil: me.nearby.pausedUntil!.toDate().toISOString() };
  }
  const blockers = nearbyBlockers(me, today);
  if (blockers.length) {
    await presenceRef.delete();
    throw new HttpsError("failed-precondition", "Finish setting up before using Nearby.", { missing: blockers });
  }

  // Rate limit: one update per presenceMinIntervalMs (the app's cadence is ~60 s).
  const last = presenceSnap!.exists ? (presenceSnap!.get("updatedAt") as Timestamp | undefined) : undefined;
  if (last && nowMs - last.toMillis() < LIMITS.presenceMinIntervalMs) {
    return { status: "throttled" as const, retryAfterMs: LIMITS.presenceMinIntervalMs - (nowMs - last.toMillis()) };
  }
  if (data.accuracyM !== undefined && data.accuracyM > MAX_ACCURACY_M) {
    await presenceRef.delete();
    return { status: "low_accuracy" as const };
  }

  const presence: PresenceDoc = {
    uid,
    geohash: encode(data.lat, data.lon, 7),
    lat: data.lat,
    lon: data.lon,
    updatedAt: Timestamp.fromMillis(nowMs),
    expiresAt: Timestamp.fromMillis(nowMs + LIMITS.presenceTtlMs),
  };
  await presenceRef.set(presence);

  // --- neighbours -------------------------------------------------------------------------------
  const cells = cellsCovering(data.lat, data.lon, LIMITS.nearbyRadiusM, 7);
  const candidates: Array<{ uid: string; distance: number }> = [];
  for (let i = 0; i < cells.length; i += 30) {
    const snap = await db()
      .collection("presence")
      .where("geohash", "in", cells.slice(i, i + 30))
      .where("expiresAt", ">", Timestamp.fromMillis(nowMs))
      .get();
    for (const doc of snap.docs) {
      if (doc.id === uid) continue;
      const p = doc.data() as PresenceDoc;
      const distance = haversineMeters(data.lat, data.lon, p.lat, p.lon);
      if (distance <= LIMITS.nearbyRadiusM) candidates.push({ uid: doc.id, distance });
    }
  }
  candidates.sort((a, b) => a.distance - b.distance);

  let created = 0;
  for (const c of candidates) {
    if (created >= LIMITS.maxNewEncountersPerUpdate) break;
    try {
      if (await tryCreateEncounter(uid, me, c.uid, now)) created++;
    } catch (err) {
      logger.error("encounter creation failed", { error: err instanceof Error ? err.message : String(err) });
    }
  }
  return { status: "ok" as const, nearbyCount: candidates.length, newEncounters: created };
});

async function tryCreateEncounter(myUid: string, me: UserDoc, otherUid: string, now: Date): Promise<boolean> {
  const nowMs = now.getTime();
  const today = utcToday(now);
  const [a, b] = sortedPair(myUid, otherUid);
  const id = pairId(myUid, otherUid);
  const fs = db();
  const encRef = fs.collection("encounters").doc(id);
  const otherRef = fs.collection("users").doc(otherUid);
  const refs = [
    otherRef,
    fs.collection("blocks").doc(myUid).collection("list").doc(otherUid),
    fs.collection("blocks").doc(otherUid).collection("list").doc(myUid),
    fs.collection("snoozes").doc(myUid).collection("list").doc(otherUid),
    fs.collection("snoozes").doc(otherUid).collection("list").doc(myUid),
    fs.collection("friends").doc(myUid).collection("list").doc(otherUid),
  ];

  const result = await fs.runTransaction(async (tx) => {
    const [otherSnap, b1, b2, s1, s2, fr, encSnap] = (await tx.getAll(...refs, encRef)) as DocumentSnapshot[];
    const other = otherSnap!.data() as UserDoc | undefined;
    if (!other) return null;
    const snoozedActive = (s: DocumentSnapshot | undefined) => !!s?.exists && (s.get("until") as Timestamp).toMillis() > nowMs;
    const existing = encSnap!.exists ? (encSnap!.data() as EncounterDoc) : null;
    const verdict = evaluatePair({
      me,
      other,
      blocked: !!b1?.exists || !!b2?.exists,
      snoozed: snoozedActive(s1) || snoozedActive(s2),
      friends: !!fr?.exists,
      existing,
      today,
      meIsFirst: myUid === a,
      nowMs,
      dedupeMs: LIMITS.encounterDedupeMs,
    });
    if (!verdict.ok) return null;
    const users: Record<string, UserDoc> = { [myUid]: me, [otherUid]: other };
    const doc: EncounterDoc = {
      uids: [a, b],
      kind: verdict.kind,
      score: verdict.score,
      birthdayTwin: verdict.birthdayTwin,
      exactTwin: verdict.exactTwin,
      swipeA: null,
      swipeB: null,
      status: "pending",
      createdAt: Timestamp.fromMillis(nowMs),
      expiresAt: Timestamp.fromMillis(nowMs + LIMITS.pendingMatchMs),
      rejectedUntil: null,
      purgeAt: Timestamp.fromMillis(nowMs + LIMITS.encounterRetentionMs),
      cards: { [a]: cardOf(users[a]!, today), [b]: cardOf(users[b]!, today) },
    };
    tx.set(encRef, doc);
    return { doc, users };
  });
  if (!result) return false;

  // Push both phones; each sees the *other* person's sun sign. Capped per user per hour.
  const { doc, users } = result;
  await Promise.all(
    doc.uids.map(async (uid) => {
      const otherId = uid === doc.uids[0] ? doc.uids[1] : doc.uids[0];
      if (!(await withinPushBudget(uid, now))) return;
      await sendPush(uid, encounterPush(id, doc, users[otherId]!));
    }),
  );
  return true;
}

/** Notification text for one recipient; `other` is the person they are being told about. */
export function encounterPush(encounterId: string, doc: Pick<EncounterDoc, "kind" | "score" | "birthdayTwin" | "exactTwin">, other: UserDoc) {
  const sign = displaySign(signsOf(other.compat!).sun);
  const pct = `${doc.score.total}% compatible`;
  const kind: PushKind = doc.exactTwin ? "exact_twin" : doc.birthdayTwin ? "birthday_twin" : "match";
  const kindLabel = doc.kind === "dating" ? "Dating" : "Friends";
  const text: Record<PushKind, { title: string; body: string }> = {
    exact_twin: { title: "Exact birthday twin nearby!", body: `A ${sign} born on your birthday, in the same hour, is nearby — ${pct}` },
    birthday_twin: { title: "Birthday twin nearby!", body: `A ${sign} born on your birthday is nearby — ${pct}` },
    match: { title: `A ${sign} ☌ is nearby`, body: `${pct} · ${kindLabel}` },
    conjunction: { title: "", body: "" },
    message: { title: "", body: "" },
  };
  return {
    kind,
    ...text[kind],
    data: { encounterId, matchKind: doc.kind, total: String(doc.score.total) },
  };
}

async function withinPushBudget(uid: string, now: Date): Promise<boolean> {
  const hour = Math.floor(now.getTime() / 3600_000);
  const ref = db().collection("rateLimits").doc(`${uid}_encounterPush_${hour}`);
  return db().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const count = (snap.get("count") as number | undefined) ?? 0;
    if (count >= LIMITS.maxEncounterPushesPerHour) return false;
    tx.set(ref, { uid, action: "encounterPush", count: count + 1, expiresAt: Timestamp.fromMillis((hour + 2) * 3600_000) }, { merge: true });
    return true;
  });
}
