/**
 * Profile, consents, push tokens and the Nearby switch.
 *
 * Clients never write `users/{uid}` directly (rules deny it): every change comes through these
 * callables so it can be validated and, for text strangers see, moderated first.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { getModerator, LIMITS } from "../config.js";
import { db } from "../firebase.js";
import { callable, type CallContext } from "../lib/callable.js";
import { consumeRateLimit } from "../lib/rateLimit.js";
import { enqueue, summarize } from "../lib/queue.js";
import { defaultLookingFor, utcToday, yearsOn } from "../lib/matchFilter.js";
import { hasConsent, nearbyBlockers, type StoredPushToken, type UserDoc } from "../lib/users.js";
import { isValidDisplayName, normalizeText } from "../moderation/patterns.js";
import type { TextField } from "../moderation/questions.js";
import {
  recordConsentsSchema,
  registerPushTokenSchema,
  setNearbySchema,
  updateProfileSchema,
} from "../schemas.js";

type FieldStatus = "approved" | "pending" | "unchanged" | "cleared";

async function moderateProfileText(uid: string, field: Extract<TextField, "display_name" | "bio">, text: string) {
  const result = await getModerator().moderateText({ text, field });
  if (result.decision === "block") {
    throw new HttpsError("invalid-argument", field === "bio" ? "That bio isn't allowed." : "That name isn't allowed.", {
      field,
      reasons: result.reasons,
    });
  }
  return result;
}

export const updateProfile = callable(updateProfileSchema, async (data, { uid, now }: CallContext) => {
  await consumeRateLimit(uid, "profile", LIMITS.profileUpdatesPerHour, 3600_000, now);
  const userRef = db().collection("users").doc(uid);
  const current = ((await userRef.get()).data() ?? {}) as UserDoc;
  const update: Record<string, unknown> = { updatedAt: FieldValue.serverTimestamp() };
  const status: Record<string, FieldStatus> = {};
  const queued: Array<Parameters<typeof enqueue>[0]> = [];

  // --- dating & friends profile (special-category data: needs explicit consent) ---------------
  const datingFields = ["gender", "seeking", "relationshipStatus", "lookingFor"] as const;
  if (datingFields.some((f) => data[f] !== undefined)) {
    if (!hasConsent(current, "CONSENT_ORIENTATION_DATA")) {
      throw new HttpsError("failed-precondition", "Consent to use your dating profile is required first.", { missing: ["consent:CONSENT_ORIENTATION_DATA"] });
    }
    for (const f of datingFields) if (data[f] !== undefined) update[f] = data[f];
    // Picking a relationship status without an explicit lookingFor applies the documented default.
    if (data.relationshipStatus !== undefined && data.lookingFor === undefined && !current.lookingFor) {
      update.lookingFor = defaultLookingFor(data.relationshipStatus);
    }
  }

  // --- chart summary ------------------------------------------------------------------------------
  if (data.compat !== undefined) {
    if (data.compat === null) {
      update.compat = null;
    } else {
      const today = utcToday(now);
      let age: number;
      try {
        age = yearsOn(data.compat.birthDate, today);
      } catch {
        throw new HttpsError("invalid-argument", "Birth date is in the future.");
      }
      if (age < 18) throw new HttpsError("permission-denied", "Conjunction is for adults 18 and over.", { reason: "under-18" });
      // Blood type leaves the phone only with its own consent (Privacy Policy, consent copy §3).
      const bloodType = hasConsent(current, "CONSENT_BLOOD_TYPE_MATCHING") ? data.compat.bloodType : null;
      update.compat = { ...data.compat, bloodType };
    }
  }

  // --- display name -------------------------------------------------------------------------------
  if (data.displayName !== undefined) {
    const name = normalizeText(data.displayName).replace(/\s+/g, " ");
    if (!name || name.length > LIMITS.displayNameMax || !isValidDisplayName(name)) {
      throw new HttpsError("invalid-argument", "Names can use letters, spaces, apostrophes, hyphens and periods (up to 40).", { field: "display_name" });
    }
    if (name === current.displayName) status.displayName = "unchanged";
    else {
      const result = await moderateProfileText(uid, "display_name", name);
      if (result.decision === "allow") {
        update.displayName = name;
        update["pending.displayName"] = FieldValue.delete();
        status.displayName = "approved";
      } else {
        update["pending.displayName"] = name;
        status.displayName = "pending";
        queued.push({ type: "display_name", uid, reason: result.unavailable ? "unavailable" : "held", payload: { text: name }, moderation: summarize(result), priority: 2 });
      }
      if (result.flags.length) queued.push({ type: "safety_flag", uid, reason: "flag", payload: { field: "display_name", text: name, flags: result.flags }, moderation: summarize(result), priority: 0 });
    }
  }

  // --- bio ------------------------------------------------------------------------------------------
  if (data.bio !== undefined) {
    const bio = data.bio === null ? "" : normalizeText(data.bio);
    if (bio.length > LIMITS.bioMax) throw new HttpsError("invalid-argument", `Bios can be up to ${LIMITS.bioMax} characters.`, { field: "bio" });
    if (!bio) {
      update.bio = null;
      update["pending.bio"] = FieldValue.delete();
      status.bio = "cleared";
    } else if (bio === current.bio) status.bio = "unchanged";
    else {
      const result = await moderateProfileText(uid, "bio", bio);
      if (result.decision === "allow") {
        update.bio = bio;
        update["pending.bio"] = FieldValue.delete();
        status.bio = "approved";
      } else {
        update["pending.bio"] = bio;
        status.bio = "pending";
        queued.push({ type: "bio", uid, reason: result.unavailable ? "unavailable" : "held", payload: { text: bio }, moderation: summarize(result), priority: 2 });
      }
      if (result.flags.length) queued.push({ type: "safety_flag", uid, reason: "flag", payload: { field: "bio", text: bio, flags: result.flags }, moderation: summarize(result), priority: 0 });
    }
  }

  await db().runTransaction(async (tx) => {
    const snap = await tx.get(userRef);
    if (!snap.exists) tx.set(userRef, { createdAt: FieldValue.serverTimestamp(), status: "active" }, { merge: true });
    // `update` uses dotted paths (pending.*), so apply it with update() after ensuring the doc exists.
    if (snap.exists) tx.update(userRef, update);
    else tx.set(userRef, expandDotted(update), { merge: true });
    for (const item of queued) enqueue(item, tx);
  });
  return { status };
});

/** Turns {"a.b": v} into {a: {b: v}} for set(..., {merge: true}) (FieldValue.delete() entries dropped). */
function expandDotted(update: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(update)) {
    const parts = k.split(".");
    if (parts.length === 1) {
      out[k] = v;
      continue;
    }
    if (v instanceof FieldValue && v.isEqual(FieldValue.delete())) continue;
    let node = out;
    for (const p of parts.slice(0, -1)) node = (node[p] ??= {}) as Record<string, unknown>;
    node[parts.at(-1)!] = v;
  }
  return out;
}

export const recordConsents = callable(recordConsentsSchema, async (data, { uid, now }) => {
  const userRef = db().collection("users").doc(uid);
  const presenceRef = db().collection("presence").doc(uid);
  await db().runTransaction(async (tx) => {
    const snap = await tx.get(userRef);
    const update: Record<string, unknown> = { updatedAt: FieldValue.serverTimestamp() };
    let stopNearby = false;
    for (const r of data.records) {
      // Append-only audit record (kept per Privacy Policy §7 even after account deletion).
      tx.set(db().collection("consents").doc(uid).collection("records").doc(), {
        ...r,
        acceptedAt: Timestamp.fromDate(new Date(r.acceptedAt)),
        serverAt: FieldValue.serverTimestamp(),
      });
      update[`consents.${r.key}`] = { version: r.version, at: Timestamp.fromDate(now), withdrawn: r.withdrawn };
      if (r.withdrawn) {
        // Withdrawal side effects (consent copy §3–§5): stop using / delete the data it covered.
        if (r.key === "CONSENT_ORIENTATION_DATA") {
          for (const f of ["gender", "seeking", "relationshipStatus", "lookingFor"]) update[f] = FieldValue.delete();
          stopNearby = true;
        }
        if (r.key === "CONSENT_BLOOD_TYPE_MATCHING" && snap.get("compat")) update["compat.bloodType"] = null;
        if (r.key === "CONSENT_NEARBY_LOCATION" || ["WAIVER", "TERMS", "PRIVACY", "AGE_18_CONFIRMED"].includes(r.key)) stopNearby = true;
      }
    }
    if (stopNearby) {
      update["nearby.enabled"] = false;
      tx.delete(presenceRef);
    }
    tx.set(db().collection("consents").doc(uid), { uid, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    if (snap.exists) tx.update(userRef, update);
    else tx.set(userRef, { ...expandDotted(update), createdAt: FieldValue.serverTimestamp(), status: "active" }, { merge: true });
  });
  return { ok: true };
});

export const registerPushToken = callable(registerPushTokenSchema, async (data, { uid, now }) => {
  const userRef = db().collection("users").doc(uid);
  await db().runTransaction(async (tx) => {
    const snap = await tx.get(userRef);
    const tokens = (snap.get("push.tokens") as StoredPushToken[] | undefined) ?? [];
    const kept = tokens.filter((t) => t.token !== data.token);
    kept.unshift({ token: data.token, platform: data.platform, updatedAt: Timestamp.fromDate(now) });
    // A user rarely has more than a couple of devices; keep the 5 most recent.
    const next = kept.slice(0, 5);
    if (snap.exists) tx.update(userRef, { "push.tokens": next });
    else tx.set(userRef, { push: { tokens: next }, createdAt: FieldValue.serverTimestamp(), status: "active" }, { merge: true });
  });
  return { ok: true };
});

export const setNearby = callable(setNearbySchema, async (data, { uid, now }) => {
  const userRef = db().collection("users").doc(uid);
  const presenceRef = db().collection("presence").doc(uid);
  let pausedUntil: Timestamp | null = null;
  if (data.pausedUntil) {
    const until = new Date(data.pausedUntil);
    if (until.getTime() <= now.getTime()) throw new HttpsError("invalid-argument", "Pause end must be in the future.");
    pausedUntil = Timestamp.fromMillis(Math.min(until.getTime(), now.getTime() + LIMITS.maxPauseMs));
  }
  const snap = await userRef.get();
  const user = snap.data() as UserDoc | undefined;
  if (data.enabled) {
    const blockers = nearbyBlockers(user, utcToday(now));
    if (blockers.length) throw new HttpsError("failed-precondition", "Finish setting up before turning on Nearby.", { missing: blockers });
  }
  await db().runTransaction(async (tx) => {
    tx.set(userRef, { nearby: { enabled: data.enabled, pausedUntil }, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    // Off or paused: the latest position is deleted immediately (Privacy Policy §7).
    if (!data.enabled || pausedUntil) tx.delete(presenceRef);
  });
  return { enabled: data.enabled, pausedUntil: pausedUntil?.toDate().toISOString() ?? null };
});
