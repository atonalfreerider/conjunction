/** Security rules: clients read only their own data / their pairs, and never write. */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";
import { assertFails, assertSucceeds, initializeTestEnvironment, type RulesTestEnvironment } from "@firebase/rules-unit-testing";
import { doc, getDoc, setDoc, collection, getDocs, query, where, updateDoc, deleteDoc } from "firebase/firestore";
import { ref, uploadBytes, getBytes } from "firebase/storage";

let env: RulesTestEnvironment;
const [fsHost, fsPort] = (process.env.FIRESTORE_EMULATOR_HOST ?? "127.0.0.1:8080").split(":");
const [stHost, stPort] = (process.env.FIREBASE_STORAGE_EMULATOR_HOST ?? "127.0.0.1:9199").split(":");

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: "conjunction-rules",
    firestore: { rules: readFileSync(resolve("../firestore.rules"), "utf8"), host: fsHost!, port: Number(fsPort) },
    storage: { rules: readFileSync(resolve("../storage.rules"), "utf8"), host: stHost!, port: Number(stPort) },
  });
});
afterAll(async () => {
  await env.cleanup();
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "users/alice"), { displayName: "Alice", compat: { birthDate: "1990-01-01" } });
    await setDoc(doc(db, "users/bob"), { displayName: "Bob" });
    await setDoc(doc(db, "presence/alice"), { lat: 1, lon: 2, geohash: "s000000" });
    await setDoc(doc(db, "encounters/alice_bob"), { uids: ["alice", "bob"], kind: "dating", status: "pending" });
    await setDoc(doc(db, "friends/alice/list/bob"), { pairId: "alice_bob" });
    await setDoc(doc(db, "chats/alice_bob"), { uids: ["alice", "bob"], status: "open" });
    await setDoc(doc(db, "chats/alice_bob/messages/m1"), { from: "bob", text: "hi" });
    await setDoc(doc(db, "chats/alice_carol"), { uids: ["alice", "carol"], status: "closed" });
    await setDoc(doc(db, "chats/alice_carol/messages/m1"), { from: "carol", text: "old" });
    await setDoc(doc(db, "blocks/alice/list/mallory"), { at: 1 });
    await setDoc(doc(db, "snoozes/alice/list/dave"), { until: 1 });
    await setDoc(doc(db, "consents/alice/records/r1"), { key: "TERMS" });
    await setDoc(doc(db, "reports/r1"), { reporterUid: "alice", reportedUid: "bob" });
    await setDoc(doc(db, "moderationQueue/q1"), { uid: "alice" });
    await setDoc(doc(db, "rateLimits/x"), { uid: "alice" });
  });
});

const as = (uid: string | null) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).firestore();

describe("firestore.rules", () => {
  it("users: owner can get own doc only; nobody writes or lists", async () => {
    await assertSucceeds(getDoc(doc(as("alice"), "users/alice")));
    await assertFails(getDoc(doc(as("bob"), "users/alice")));
    await assertFails(getDoc(doc(as(null), "users/alice")));
    await assertFails(getDocs(collection(as("alice"), "users")));
    await assertFails(setDoc(doc(as("alice"), "users/alice"), { displayName: "x" }));
    await assertFails(updateDoc(doc(as("alice"), "users/alice"), { "photo.approved": true }));
  });

  it("presence (location) is unreadable by everyone, including the owner", async () => {
    await assertFails(getDoc(doc(as("alice"), "presence/alice")));
    await assertFails(getDoc(doc(as("bob"), "presence/alice")));
    await assertFails(setDoc(doc(as("alice"), "presence/alice"), { lat: 0, lon: 0 }));
  });

  it("encounters: readable by the pair, queryable with array-contains, never writable", async () => {
    await assertSucceeds(getDoc(doc(as("alice"), "encounters/alice_bob")));
    await assertSucceeds(getDoc(doc(as("bob"), "encounters/alice_bob")));
    await assertFails(getDoc(doc(as("carol"), "encounters/alice_bob")));
    await assertSucceeds(getDocs(query(collection(as("bob"), "encounters"), where("uids", "array-contains", "bob"))));
    await assertFails(getDocs(collection(as("bob"), "encounters")));
    await assertFails(updateDoc(doc(as("alice"), "encounters/alice_bob"), { swipeA: "right" }));
  });

  it("friends / blocks / snoozes / consents: owner read-only", async () => {
    for (const path of ["friends/alice/list/bob", "blocks/alice/list/mallory", "snoozes/alice/list/dave", "consents/alice/records/r1"]) {
      await assertSucceeds(getDoc(doc(as("alice"), path)));
      await assertFails(getDoc(doc(as("bob"), path)));
      await assertFails(setDoc(doc(as("alice"), path), { x: 1 }));
    }
    await assertFails(deleteDoc(doc(as("alice"), "blocks/alice/list/mallory")));
  });

  it("chats: participants read open chats and their messages; nobody writes", async () => {
    await assertSucceeds(getDoc(doc(as("alice"), "chats/alice_bob")));
    await assertSucceeds(getDocs(collection(as("alice"), "chats/alice_bob/messages")));
    await assertFails(getDocs(collection(as("carol"), "chats/alice_bob/messages")));
    await assertFails(getDocs(collection(as("alice"), "chats/alice_carol/messages"))); // closed
    await assertFails(setDoc(doc(as("alice"), "chats/alice_bob/messages/m2"), { from: "alice", text: "unmoderated" }));
    await assertSucceeds(getDocs(query(collection(as("alice"), "chats"), where("uids", "array-contains", "alice"))));
  });

  it("server-only collections are closed", async () => {
    for (const path of ["reports/r1", "moderationQueue/q1", "rateLimits/x", "devOutbox/a", "bannedIdentities/h"]) {
      await assertFails(getDoc(doc(as("alice"), path)));
      await assertFails(setDoc(doc(as("alice"), path), { x: 1 }));
    }
    await assertFails(setDoc(doc(as("alice"), "reports/new"), { reportedUid: "bob" }));
  });
});

describe("storage.rules", () => {
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
  const st = (uid: string | null) => (uid ? env.authenticatedContext(uid) : env.unauthenticatedContext()).storage();

  it("owner may upload a JPEG candidate photo to their own uploads folder", async () => {
    await assertSucceeds(uploadBytes(ref(st("alice"), "uploads/alice/abcdefgh.jpg"), jpeg, { contentType: "image/jpeg" }));
  });
  it("wrong owner, type, name or size is refused", async () => {
    await assertFails(uploadBytes(ref(st("bob"), "uploads/alice/abcdefgh.jpg"), jpeg, { contentType: "image/jpeg" }));
    await assertFails(uploadBytes(ref(st(null), "uploads/alice/abcdefgh.jpg"), jpeg, { contentType: "image/jpeg" }));
    await assertFails(uploadBytes(ref(st("alice"), "uploads/alice/abcdefgh.jpg"), jpeg, { contentType: "image/png" }));
    await assertFails(uploadBytes(ref(st("alice"), "uploads/alice/../x.jpg"), jpeg, { contentType: "image/jpeg" }));
    await assertFails(uploadBytes(ref(st("alice"), "uploads/alice/short.jpg"), jpeg, { contentType: "image/jpeg" }));
    await assertFails(uploadBytes(ref(st("alice"), "uploads/alice/abcdefgh.jpg"), new Uint8Array(8 * 1024 * 1024 + 1), { contentType: "image/jpeg" }));
  });
  it("nothing is directly readable: not uploads, not profile photos", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await uploadBytes(ref(ctx.storage(), "profilePhotos/alice.jpg"), jpeg, { contentType: "image/jpeg" });
    });
    await assertFails(getBytes(ref(st("alice"), "profilePhotos/alice.jpg")));
    await assertFails(getBytes(ref(st("bob"), "profilePhotos/alice.jpg")));
    await assertFails(getBytes(ref(st("alice"), "uploads/alice/abcdefgh.jpg")));
    await assertFails(uploadBytes(ref(st("alice"), "profilePhotos/alice.jpg"), jpeg, { contentType: "image/jpeg" }));
  });
});
