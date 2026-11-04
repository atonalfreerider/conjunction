/** Lazily initialised Admin SDK handles. */
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { getStorage } from "firebase-admin/storage";

function app() {
  return getApps()[0] ?? initializeApp();
}

let firestore: Firestore | null = null;
export function db(): Firestore {
  if (!firestore) {
    firestore = getFirestore(app());
    firestore.settings({ ignoreUndefinedProperties: true });
  }
  return firestore;
}
export const auth = () => getAuth(app());
export const messaging = () => getMessaging(app());
export const bucket = () => getStorage(app()).bucket();
