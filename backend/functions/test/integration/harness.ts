/**
 * Test harness for the emulator suite: one Firebase client app per simulated phone (each signs in
 * anonymously, like the real app), plus the Admin SDK for seeding and assertions.
 */
import { deleteApp, initializeApp as initClient, type FirebaseApp } from "firebase/app";
import { connectAuthEmulator, getAuth, signInAnonymously } from "firebase/auth";
import { connectFunctionsEmulator, getFunctions, httpsCallable } from "firebase/functions";
import { connectStorageEmulator, getStorage, ref, uploadBytes } from "firebase/storage";
import { getApps, initializeApp } from "firebase-admin/app";
import { getAuth as adminAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage as adminStorage } from "firebase-admin/storage";

export const PROJECT_ID = "conjunction-dev";
export const BUCKET = `${PROJECT_ID}.appspot.com`;

if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error("Run through scripts/emulator-test.sh (emulators not running)");

export const admin = getApps()[0] ?? initializeApp({ projectId: PROJECT_ID, storageBucket: BUCKET });
export const adb = getFirestore(admin);
export const abucket = () => adminStorage(admin).bucket(BUCKET);
export const aauth = () => adminAuth(admin);

const [authHost, authPort] = (process.env.FIREBASE_AUTH_EMULATOR_HOST ?? "127.0.0.1:9099").split(":");
const [storageHost, storagePort] = (process.env.FIREBASE_STORAGE_EMULATOR_HOST ?? "127.0.0.1:9199").split(":");

export interface Phone {
  uid: string;
  app: FirebaseApp;
  call<T = any>(name: string, data?: unknown): Promise<T>;
  upload(path: string, bytes: Uint8Array, contentType: string): Promise<void>;
  close(): Promise<void>;
}

let counter = 0;

export async function phone(): Promise<Phone> {
  const app = initClient({ projectId: PROJECT_ID, apiKey: "fake-api-key", storageBucket: BUCKET, appId: "test" }, `phone-${Date.now()}-${counter++}`);
  const auth = getAuth(app);
  connectAuthEmulator(auth, `http://${authHost}:${authPort}`, { disableWarnings: true });
  const functions = getFunctions(app, "us-central1");
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
  const storage = getStorage(app);
  connectStorageEmulator(storage, storageHost!, Number(storagePort));
  const cred = await signInAnonymously(auth);
  return {
    uid: cred.user.uid,
    app,
    async call(name, data = {}) {
      const res = await httpsCallable(functions, name)(data);
      return res.data as any;
    },
    async upload(path, bytes, contentType) {
      await uploadBytes(ref(storage, path), bytes, { contentType });
    },
    close: () => deleteApp(app),
  };
}

/** Expects a callable to fail with `functions/<code>`. */
export async function expectCode(p: Promise<unknown>, code: string): Promise<any> {
  try {
    await p;
  } catch (err) {
    const e = err as { code?: string; details?: unknown };
    if (e.code !== `functions/${code}`) throw new Error(`expected functions/${code}, got ${e.code}: ${String(err)}`);
    return e.details;
  }
  throw new Error(`expected functions/${code}, but the call succeeded`);
}

export const CONSENTS = ["WAIVER", "TERMS", "PRIVACY", "AGE_18_CONFIRMED", "CONSENT_ORIENTATION_DATA", "PROFILE_HONESTY_CONFIRMED", "CONSENT_NEARBY_LOCATION", "CONSENT_BLOOD_TYPE_MATCHING"];

export function consentRecords(keys: string[] = CONSENTS, withdrawn = false) {
  return {
    records: keys.map((key) => ({
      key,
      version: "1.0",
      acceptedAt: new Date().toISOString(),
      appVersion: "1.0.0 (1)",
      platform: "android",
      textSha256: "a".repeat(64),
      withdrawn,
    })),
  };
}

export interface ProfileOpts {
  gender?: "male" | "female";
  seeking?: "male" | "female" | "both";
  relationshipStatus?: "single" | "in_a_relationship";
  lookingFor?: "dating" | "friends" | "both";
  birthDate?: string;
  birthHour?: number | null;
  sun?: number;
  displayName?: string;
}

/** Full onboarding through the public callables: consents, dating profile, chart summary, Nearby on. */
export async function onboard(p: Phone, o: ProfileOpts = {}): Promise<void> {
  await p.call("recordConsents", consentRecords());
  await p.call("updateProfile", {
    displayName: o.displayName ?? "Ava Stone",
    gender: o.gender ?? "female",
    seeking: o.seeking ?? "male",
    relationshipStatus: o.relationshipStatus ?? "single",
    lookingFor: o.lookingFor ?? "both",
    compat: {
      sun: o.sun ?? 139.75,
      moon: 210.12,
      venus: 110.4,
      mars: 45.9,
      ascendant: 250.33,
      chineseAnimal: "horse",
      dayStem: "jia",
      bloodType: "O",
      birthDate: o.birthDate ?? "1990-08-12",
      birthHour: o.birthHour === undefined ? 14 : o.birthHour,
    },
  });
  await p.call("setNearby", { enabled: true });
}

/** A distinct base location per test file so files never see each other's phones. */
export function place(seed: number): { lat: number; lon: number } {
  return { lat: 10 + seed * 0.37, lon: 20 + seed * 0.41 };
}

/** Moves (lat, lon) north by `metres`. */
export function north(p: { lat: number; lon: number }, metres: number) {
  return { lat: p.lat + metres / 111_195, lon: p.lon };
}

export async function outboxFor(uid: string) {
  const snap = await adb.collection("devOutbox").where("uid", "==", uid).get();
  return snap.docs.map((d) => d.data());
}

export async function makeAdmin(): Promise<Phone> {
  const p = await phone();
  await aauth().setCustomUserClaims(p.uid, { admin: true });
  // Refresh the ID token so the claim is present.
  const { getAuth: ga } = await import("firebase/auth");
  await ga(p.app).currentUser!.getIdToken(true);
  return p;
}

/** Smallest valid-looking JPEG (SOI, APP1 Exif with GPS text, SOS, data, EOI). */
export function tinyJpeg(): Uint8Array {
  const seg = (marker: number, payload: Buffer) => {
    const len = Buffer.alloc(2);
    len.writeUInt16BE(payload.length + 2);
    return Buffer.concat([Buffer.from([0xff, marker]), len, payload]);
  };
  return new Uint8Array(Buffer.concat([
    Buffer.from([0xff, 0xd8]),
    seg(0xe0, Buffer.from("JFIF\0\x01\x01\0\0\x01\0\x01\0\0", "binary")),
    seg(0xe1, Buffer.from("Exif\0\0GPSLatitude=51.5", "binary")),
    seg(0xda, Buffer.from([0x01, 0x01, 0x00, 0x00, 0x3f, 0x00])),
    Buffer.from([0x12, 0x34, 0x56]),
    Buffer.from([0xff, 0xd9]),
  ]));
}
