/** Profile-photo paths and short-lived read URLs. Photos are never publicly readable in Storage. */
import { bucket } from "../firebase.js";
import { isEmulator, LIMITS } from "../config.js";

export const profilePhotoPath = (uid: string) => `profilePhotos/${uid}.jpg`;
export const uploadPath = (uid: string, uploadId: string) => `uploads/${uid}/${uploadId}.jpg`;
export const heldPhotoPath = (uid: string, uploadId: string) => `moderation/photos/${uid}/${uploadId}.jpg`;

/**
 * A V4 signed URL valid for 15 minutes. In production the functions service account needs
 * `iam.serviceAccounts.signBlob` on itself (role "Service Account Token Creator"; README).
 * The Storage emulator cannot sign, so under the emulator an emulator media URL is returned instead
 * (useful only to tests, which check *whether* a URL is handed out).
 */
export async function signedPhotoUrl(uid: string, now: Date): Promise<string | null> {
  const file = bucket().file(profilePhotoPath(uid));
  const [exists] = await file.exists();
  if (!exists) return null;
  if (isEmulator()) {
    const host = process.env.FIREBASE_STORAGE_EMULATOR_HOST ?? "127.0.0.1:9199";
    return `http://${host}/v0/b/${bucket().name}/o/${encodeURIComponent(file.name)}?alt=media`;
  }
  const [url] = await file.getSignedUrl({ version: "v4", action: "read", expires: now.getTime() + LIMITS.signedUrlTtlMs });
  return url;
}
