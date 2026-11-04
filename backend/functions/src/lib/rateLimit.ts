/**
 * Fixed-window rate limiter backed by Firestore (`rateLimits/{uid}_{action}_{window}`), with a TTL
 * field so old windows disappear. Counted inside a transaction, so concurrent calls cannot overshoot.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { db } from "../firebase.js";

export async function consumeRateLimit(uid: string, action: string, limit: number, windowMs: number, now: Date): Promise<void> {
  const window = Math.floor(now.getTime() / windowMs);
  const ref = db().collection("rateLimits").doc(`${uid}_${action}_${windowMs}_${window}`);
  const allowed = await db().runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const count = (snap.get("count") as number | undefined) ?? 0;
    if (count >= limit) return false;
    tx.set(
      ref,
      {
        uid,
        action,
        count: FieldValue.increment(1),
        expiresAt: Timestamp.fromMillis((window + 1) * windowMs + 60_000),
      },
      { merge: true },
    );
    return true;
  });
  if (!allowed) throw new HttpsError("resource-exhausted", "Too many requests. Please slow down.", { action });
}
