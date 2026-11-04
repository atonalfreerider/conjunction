/**
 * Profile photo flow:
 *   1. The app uploads a JPEG to Storage `uploads/{uid}/{uploadId}.jpg` (rules: owner-only write,
 *      image/jpeg, ≤ 8 MB, no reads).
 *   2. The app calls `submitPhoto({uploadId})`.
 *   3. We validate the bytes, strip EXIF/GPS metadata, and moderate (Cloud Vision SafeSearch):
 *        approve → `profilePhotos/{uid}.jpg`, `photo.approved = true`
 *        review  → `moderation/photos/{uid}/{uploadId}.jpg` + review item; `pendingPhoto.status = "pending"`
 *        reject  → bytes deleted; `pendingPhoto.status = "rejected"`
 *      The previous approved photo (if any) stays visible until a new one is approved.
 *   4. Photos are only ever served as 15-minute signed URLs by getEncounterCard / getFriendProfile.
 */
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { LIMITS } from "../config.js";
import { bucket, db } from "../firebase.js";
import { callable } from "../lib/callable.js";
import { isJpeg, stripJpegMetadata } from "../lib/jpeg.js";
import { heldPhotoPath, profilePhotoPath, uploadPath } from "../lib/photos.js";
import { consumeRateLimit } from "../lib/rateLimit.js";
import { enqueue } from "../lib/queue.js";
import { moderatePhoto } from "../moderation/photo.js";
import { emptySchema, submitPhotoSchema } from "../schemas.js";

export async function publishPhoto(uid: string, jpeg: Buffer): Promise<void> {
  await bucket().file(profilePhotoPath(uid)).save(jpeg, {
    contentType: "image/jpeg",
    resumable: false,
    metadata: { cacheControl: "private, max-age=900" },
  });
  await db().collection("users").doc(uid).set(
    { photo: { approved: true, version: FieldValue.increment(1), updatedAt: FieldValue.serverTimestamp() }, pendingPhoto: null },
    { merge: true },
  );
}

export const submitPhoto = callable(submitPhotoSchema, async (d, { uid, now }) => {
  await consumeRateLimit(uid, "photo", LIMITS.photoSubmissionsPerDay, 24 * 3600_000, now);
  const upload = bucket().file(uploadPath(uid, d.uploadId));
  const [exists] = await upload.exists();
  if (!exists) throw new HttpsError("not-found", "Upload not found. Please upload the photo again.");
  const [meta] = await upload.getMetadata();
  if (Number(meta.size ?? 0) > LIMITS.photoMaxBytes) {
    await upload.delete({ ignoreNotFound: true });
    throw new HttpsError("invalid-argument", "Photo is too large (8 MB max).");
  }
  const [raw] = await upload.download();
  await upload.delete({ ignoreNotFound: true });
  if (!isJpeg(raw)) throw new HttpsError("invalid-argument", "Photos must be JPEG images.");
  let jpeg: Buffer;
  try {
    jpeg = stripJpegMetadata(raw);
  } catch {
    throw new HttpsError("invalid-argument", "That image file looks damaged.");
  }

  const result = await moderatePhoto(jpeg);
  const userRef = db().collection("users").doc(uid);
  if (result.decision === "approve") {
    await publishPhoto(uid, jpeg);
    return { status: "approved" as const };
  }
  if (result.decision === "reject") {
    await userRef.set({ pendingPhoto: { uploadId: d.uploadId, status: "rejected", reason: "not_allowed", updatedAt: Timestamp.fromDate(now) } }, { merge: true });
    return { status: "rejected" as const };
  }
  await bucket().file(heldPhotoPath(uid, d.uploadId)).save(jpeg, { contentType: "image/jpeg", resumable: false });
  await db().runTransaction(async (tx) => {
    tx.set(userRef, { pendingPhoto: { uploadId: d.uploadId, status: "pending", reason: null, updatedAt: Timestamp.fromDate(now) } }, { merge: true });
    enqueue({
      type: "photo",
      uid,
      reason: result.reason === "vision" ? "vision_uncertain" : result.reason,
      payload: { uploadId: d.uploadId, path: heldPhotoPath(uid, d.uploadId), safeSearch: result.safeSearch },
      moderation: null,
      priority: 2,
    }, tx);
  });
  return { status: "pending" as const };
});

/** Removes the profile photo (and any pending one). */
export const deletePhoto = callable(emptySchema, async (_d, { uid }) => {
  await bucket().file(profilePhotoPath(uid)).delete({ ignoreNotFound: true });
  await bucket().deleteFiles({ prefix: `moderation/photos/${uid}/` });
  await db().collection("users").doc(uid).set({ photo: null, pendingPhoto: null }, { merge: true });
  return { ok: true };
});
