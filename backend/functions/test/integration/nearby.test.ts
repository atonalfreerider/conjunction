/** Onboarding, presence, neighbour search, encounters, pushes, swipes, chat, moderation and safety — end to end on the emulators. */
import { afterAll, describe, expect, it } from "vitest";
import { abucket, adb, consentRecords, expectCode, makeAdmin, north, onboard, outboxFor, phone, place, tinyJpeg, type Phone } from "./harness.js";

const phones: Phone[] = [];
async function newPhone() {
  const p = await phone();
  phones.push(p);
  return p;
}
afterAll(async () => {
  await Promise.all(phones.map((p) => p.close()));
});

describe("onboarding gates", () => {
  it("Nearby needs consents, a dating profile and a chart summary", async () => {
    const p = await newPhone();
    const details = await expectCode(p.call("setNearby", { enabled: true }), "failed-precondition");
    expect(details.missing).toContain("consent:CONSENT_NEARBY_LOCATION");
    expect(details.missing).toContain("datingProfile");
    expect(details.missing).toContain("chartSummary");
  });

  it("dating fields need the orientation consent first", async () => {
    const p = await newPhone();
    await expectCode(p.call("updateProfile", { gender: "male" }), "failed-precondition");
  });

  it("rejects under-18 birth dates and unknown wire values", async () => {
    const p = await newPhone();
    await p.call("recordConsents", consentRecords());
    const compat = { sun: 1, moon: 2, venus: 3, mars: 4, ascendant: null, chineseAnimal: "rat", dayStem: "jia", bloodType: null, birthDate: "2010-01-01", birthHour: null };
    await expectCode(p.call("updateProfile", { compat }), "permission-denied");
    await expectCode(p.call("updateProfile", { gender: "Male" }), "invalid-argument");
  });

  it("drops blood type without its consent, and applies the lookingFor default", async () => {
    const p = await newPhone();
    await p.call("recordConsents", consentRecords(["WAIVER", "TERMS", "PRIVACY", "AGE_18_CONFIRMED", "CONSENT_ORIENTATION_DATA"]));
    await p.call("updateProfile", {
      relationshipStatus: "in_a_relationship",
      gender: "male",
      seeking: "female",
      compat: { sun: 1, moon: 2, venus: 3, mars: 4, ascendant: null, chineseAnimal: "rat", dayStem: "jia", bloodType: "AB", birthDate: "1990-01-01", birthHour: null },
    });
    const u = (await adb.collection("users").doc(p.uid).get()).data()!;
    expect(u.compat.bloodType).toBeNull();
    expect(u.lookingFor).toBe("friends");
  });

  it("withdrawing orientation consent erases the dating fields and stops Nearby", async () => {
    const p = await newPhone();
    await onboard(p);
    await p.call("updatePresence", place(90));
    await p.call("recordConsents", consentRecords(["CONSENT_ORIENTATION_DATA"], true));
    const u = (await adb.collection("users").doc(p.uid).get()).data()!;
    expect(u.gender).toBeUndefined();
    expect(u.nearby.enabled).toBe(false);
    expect((await adb.collection("presence").doc(p.uid).get()).exists).toBe(false);
  });
});

describe("presence and encounters", () => {
  const base = place(1);
  let ava: Phone, ben: Phone, far: Phone;

  it("stores only the latest position, with a 10-minute TTL", async () => {
    ava = await newPhone();
    await onboard(ava, { displayName: "Ava Stone", gender: "female", seeking: "male" });
    const r = await ava.call("updatePresence", base);
    expect(r.status).toBe("ok");
    const pres = (await adb.collection("presence").doc(ava.uid).get()).data()!;
    expect(pres.geohash).toHaveLength(7);
    expect(pres.expiresAt.toMillis() - pres.updatedAt.toMillis()).toBe(10 * 60_000);
  });

  it("throttles updates closer than 15 s", async () => {
    const r = await ava.call("updatePresence", base);
    expect(r.status).toBe("throttled");
  });

  it("matches two compatible people within 50 m and pushes both, never revealing location", async () => {
    ben = await newPhone();
    await onboard(ben, { displayName: "Ben Okafor", gender: "male", seeking: "female", sun: 10.5, birthDate: "1988-02-29" });
    far = await newPhone();
    await onboard(far, { displayName: "Far Away", gender: "male", seeking: "female", birthDate: "1985-01-01" });
    await far.call("updatePresence", north(base, 300));
    const r = await ben.call("updatePresence", north(base, 30));
    expect(r).toMatchObject({ status: "ok", newEncounters: 1 });

    const encs = await adb.collection("encounters").where("uids", "array-contains", ava.uid).get();
    expect(encs.size).toBe(1);
    const enc = encs.docs[0]!.data();
    expect(enc.uids).toEqual([ava.uid, ben.uid].sort());
    expect(enc.kind).toBe("dating");
    expect(enc.status).toBe("pending");
    expect(enc.score.total).toBeGreaterThanOrEqual(0);
    expect(enc.cards[ava.uid].firstName).toBe("Ava");
    expect(enc.cards[ben.uid].age).toBe(38);
    expect(JSON.stringify(enc)).not.toMatch(/"(lat|lon|latitude|longitude|geohash|distance)"/);

    for (const u of [ava, ben]) {
      const pushes = await outboxFor(u.uid);
      expect(pushes).toHaveLength(1);
      expect(pushes[0]!.data).toMatchObject({ kind: "match", matchKind: "dating", encounterId: encs.docs[0]!.id });
      expect(JSON.stringify(pushes[0])).not.toMatch(/"(lat|lon|latitude|longitude|geohash|distance)"|\d+\.\d{3}/);
    }
    expect(await outboxFor(far.uid)).toHaveLength(0);
  });

  it("does not re-create an encounter within 24 h", async () => {
    // far walks up: new dating encounter with Ava, new friends encounter with Ben (male/male, both "both").
    await adb.doc(`presence/${far.uid}`).update({ updatedAt: new Date(Date.now() - 60_000) });
    const r = await far.call("updatePresence", north(base, 15));
    expect(r).toMatchObject({ nearbyCount: 2, newEncounters: 2 });
    const id = [ava.uid, ben.uid].sort().join("_");
    const before = (await adb.doc(`encounters/${id}`).get()).get("createdAt").toMillis();
    // Let Ben report again (skip the 15 s throttle by back-dating his last fix).
    await adb.doc(`presence/${ben.uid}`).update({ updatedAt: new Date(Date.now() - 60_000) });
    const again = await ben.call("updatePresence", north(base, 31));
    expect(again).toMatchObject({ status: "ok", nearbyCount: 2, newEncounters: 0 });
    expect((await adb.doc(`encounters/${id}`).get()).get("createdAt").toMillis()).toBe(before);
    expect(await outboxFor(ben.uid)).toHaveLength(2); // Ava (dating) + far (friends), no repeats
  });

  it("getEncounterCard returns the other card; outsiders get not-found", async () => {
    const id = [ava.uid, ben.uid].sort().join("_");
    const card = await ava.call("getEncounterCard", { encounterId: id });
    expect(card.otherUid).toBe(ben.uid);
    expect(card.card.firstName).toBe("Ben");
    expect(card.photoUrl).toBeNull();
    await expectCode(far.call("getEncounterCard", { encounterId: id }), "not-found");
  });

  it("swipes: one right waits, both right = Conjunction (friends, chat, pushes)", async () => {
    const id = [ava.uid, ben.uid].sort().join("_");
    expect((await ava.call("swipe", { encounterId: id, direction: "right" })).outcome).toBe("waiting");
    await expectCode(far.call("swipe", { encounterId: id, direction: "right" }), "permission-denied");
    const r = await ben.call("connect", { encounterId: id });
    expect(r).toEqual({ outcome: "matched", pairId: id });
    expect((await adb.doc(`friends/${ava.uid}/list/${ben.uid}`).get()).exists).toBe(true);
    expect((await adb.doc(`friends/${ben.uid}/list/${ava.uid}`).get()).exists).toBe(true);
    expect((await adb.doc(`chats/${id}`).get()).get("status")).toBe("open");
    for (const u of [ava, ben]) {
      const kinds = (await outboxFor(u.uid)).map((p) => p.data.kind);
      expect(kinds).toContain("conjunction");
    }
  });

  it("friend profile: only for matches; photo URL only when approved", async () => {
    const prof = await ava.call("getFriendProfile", { uid: ben.uid });
    expect(prof).toMatchObject({ displayName: "Ben Okafor", photoUrl: null });
    await expectCode(far.call("getFriendProfile", { uid: ava.uid }), "permission-denied");
  });

  it("chat: delivered, blocked, held (model) and held (outage) — then a moderator approves", async () => {
    const id = [ava.uid, ben.uid].sort().join("_");
    const ok = await ava.call("sendIntro", { pairId: id, text: "Hi Ben! A Leo and an Aquarius walk into a café ☕" });
    expect(ok.status).toBe("delivered");
    await expectCode(ava.call("sendIntro", { pairId: id, text: "again" }), "failed-precondition");
    const blocked = await ava.call("sendMessage", { pairId: id, text: "you are [[harassment]]" });
    expect(blocked).toMatchObject({ status: "blocked", reasons: ["harassment"] });
    const held = await ben.call("sendMessage", { pairId: id, text: "hmm [[hold:hate]]" });
    expect(held.status).toBe("held");
    const outage = await ben.call("sendMessage", { pairId: id, text: "hello [[outage]]", clientMessageId: "retry-abc-123" });
    expect(outage.status).toBe("held");
    const phoneNum = await ben.call("sendMessage", { pairId: id, text: "text me 555 212 8899" });
    expect(phoneNum.status).toBe("blocked");

    const msgs = await adb.collection(`chats/${id}/messages`).get();
    expect(msgs.size).toBe(1);
    const q = await adb.collection("moderationQueue").where("uid", "==", ben.uid).get();
    const reasons = q.docs.map((d) => d.get("reason")).sort();
    expect(reasons).toEqual(["held", "unavailable"]);

    await expectCode(ben.call("reviewModeration", { itemId: q.docs[0]!.id, decision: "approve" }), "permission-denied");
    const mod = await makeAdmin();
    phones.push(mod);
    const outageItem = q.docs.find((d) => d.get("reason") === "unavailable")!;
    await mod.call("reviewModeration", { itemId: outageItem.id, decision: "approve" });
    const after = await adb.collection(`chats/${id}/messages`).get();
    expect(after.size).toBe(2);
    expect(after.docs.map((d) => d.id)).toContain("retry-abc-123");
    expect((await outboxFor(ava.uid)).some((p) => p.data.kind === "message" && !JSON.stringify(p).includes("hello"))).toBe(true);
    await expectCode(mod.call("reviewModeration", { itemId: outageItem.id, decision: "approve" }), "failed-precondition");
  });

  it("markRead stores read state; outsiders cannot message", async () => {
    const id = [ava.uid, ben.uid].sort().join("_");
    await ben.call("markRead", { pairId: id });
    expect((await adb.doc(`chats/${id}`).get()).get(`readAt.${ben.uid}`)).toBeTruthy();
    await expectCode(far.call("sendMessage", { pairId: id, text: "hi" }), "not-found");
  });

  it("report: triaged, evidence captured, reporter blocks by default", async () => {
    const r = await ava.call("report", { uid: ben.uid, pairId: [ava.uid, ben.uid].sort().join("_"), reason: "harassment", description: "He said he'd wait outside my work [[danger]]" });
    const rep = (await adb.doc(`reports/${r.reportId}`).get()).data()!;
    expect(rep.priority).toBe(0);
    expect(rep.triage.urgency).toBe("immediate_danger");
    expect(rep.evidence.messages.length).toBeGreaterThan(0);
    expect(rep.evidence.messages.every((m: { text: string }) => !m.text.startsWith("Hi Ben"))).toBe(true); // only Ben's messages
    // Blocked: the match is over for both.
    expect((await adb.doc(`blocks/${ava.uid}/list/${ben.uid}`).get()).exists).toBe(true);
    expect((await adb.doc(`friends/${ben.uid}/list/${ava.uid}`).get()).exists).toBe(false);
    expect((await adb.collection(`chats/${[ava.uid, ben.uid].sort().join("_")}/messages`).get()).size).toBe(0);
    await expectCode(ben.call("getFriendProfile", { uid: ava.uid }), "permission-denied");
  });
});

describe("twins, intent and blocks", () => {
  it("exact birthday twins get the special push kind", async () => {
    const base = place(2);
    const a = await newPhone();
    const b = await newPhone();
    await onboard(a, { gender: "female", seeking: "female", birthDate: "1995-05-05", birthHour: 5 });
    await onboard(b, { gender: "female", seeking: "female", birthDate: "1995-05-05", birthHour: 5 });
    await a.call("updatePresence", base);
    await b.call("updatePresence", north(base, 10));
    const enc = (await adb.doc(`encounters/${[a.uid, b.uid].sort().join("_")}`).get()).data()!;
    expect(enc).toMatchObject({ birthdayTwin: true, exactTwin: true, kind: "dating" });
    expect((await outboxFor(a.uid))[0]!.data.kind).toBe("exact_twin");
  });

  it("birthday twins (different hour) and friends-only intent", async () => {
    const base = place(3);
    const a = await newPhone();
    const b = await newPhone();
    await onboard(a, { gender: "male", seeking: "female", lookingFor: "friends", birthDate: "1992-03-03", birthHour: 1 });
    await onboard(b, { gender: "male", seeking: "female", lookingFor: "both", birthDate: "1992-03-03", birthHour: 9 });
    await a.call("updatePresence", base);
    await b.call("updatePresence", north(base, 40));
    const enc = (await adb.doc(`encounters/${[a.uid, b.uid].sort().join("_")}`).get()).data()!;
    expect(enc).toMatchObject({ birthdayTwin: true, exactTwin: false, kind: "friends" });
    expect((await outboxFor(b.uid))[0]!.data.kind).toBe("birthday_twin");
  });

  it("no qualifying intent → no encounter and no push (twins included)", async () => {
    const base = place(4);
    const a = await newPhone();
    const b = await newPhone();
    await onboard(a, { gender: "male", seeking: "female", lookingFor: "dating", birthDate: "1991-07-07", birthHour: 7 });
    await onboard(b, { gender: "male", seeking: "female", lookingFor: "dating", birthDate: "1991-07-07", birthHour: 7 });
    await a.call("updatePresence", base);
    const r = await b.call("updatePresence", north(base, 5));
    expect(r).toMatchObject({ nearbyCount: 1, newEncounters: 0 });
    expect(await outboxFor(b.uid)).toHaveLength(0);
  });

  it("blocked, snoozed and paused people are skipped; > 50 m is not nearby", async () => {
    const base = place(5);
    const [a, b, c, d, e] = await Promise.all([newPhone(), newPhone(), newPhone(), newPhone(), newPhone()]);
    for (const p of [b, c, d, e]) await onboard(p, { gender: "male", seeking: "female" });
    await onboard(a, { gender: "female", seeking: "male" });
    await a.call("block", { uid: b.uid });
    await a.call("snooze", { uid: c.uid, hours: 2 });
    await b.call("updatePresence", north(base, 10));
    await c.call("updatePresence", north(base, 20));
    await d.call("setNearby", { enabled: true, pausedUntil: new Date(Date.now() + 3600_000).toISOString() });
    expect((await d.call("updatePresence", north(base, 25))).status).toBe("paused");
    await e.call("updatePresence", north(base, 51));
    const r = await a.call("updatePresence", base);
    expect(r).toMatchObject({ nearbyCount: 2, newEncounters: 0 });
  });

  it("left swipe is silent and closes the card", async () => {
    const base = place(6);
    const a = await newPhone();
    const b = await newPhone();
    await onboard(a, { gender: "female", seeking: "male" });
    await onboard(b, { gender: "male", seeking: "female", birthDate: "1987-12-24" });
    await a.call("updatePresence", base);
    await b.call("updatePresence", north(base, 10));
    const id = [a.uid, b.uid].sort().join("_");
    expect((await a.call("swipe", { encounterId: id, direction: "left" })).outcome).toBe("rejected");
    const enc = (await adb.doc(`encounters/${id}`).get()).data()!;
    expect(enc.status).toBe("rejected");
    expect(enc.rejectedUntil.toMillis()).toBeGreaterThan(Date.now() + 29 * 24 * 3600_000);
    await expectCode(b.call("swipe", { encounterId: id, direction: "right" }), "failed-precondition");
    expect((await outboxFor(b.uid)).map((p) => p.data.kind)).toEqual(["match"]);
  });
});

describe("profile text moderation", () => {
  it("bio: allowed, held (pending, visible only to owner), blocked", async () => {
    const p = await newPhone();
    await p.call("recordConsents", consentRecords());
    expect((await p.call("updateProfile", { bio: "Taurus sun, plant parent." })).status.bio).toBe("approved");
    expect((await p.call("updateProfile", { bio: "hmm [[hold:harassment]]" })).status.bio).toBe("pending");
    const u = (await adb.collection("users").doc(p.uid).get()).data()!;
    expect(u.bio).toBe("Taurus sun, plant parent.");
    expect(u.pending.bio).toContain("hold");
    const d = await expectCode(p.call("updateProfile", { bio: "[[sexual]]" }), "invalid-argument");
    expect(d).toMatchObject({ field: "bio", reasons: ["sexual"] });
    await expectCode(p.call("updateProfile", { bio: "find me at insta.com/me" }), "invalid-argument");
  });

  it("display names are validated before moderation", async () => {
    const p = await newPhone();
    await expectCode(p.call("updateProfile", { displayName: "sam@x.com" }), "invalid-argument");
    expect((await p.call("updateProfile", { displayName: "  Zoë   Ng " })).status.displayName).toBe("approved");
    expect((await adb.collection("users").doc(p.uid).get()).get("displayName")).toBe("Zoë Ng");
  });
});

describe("photo upload flow", () => {
  it("upload → submit → held for review (manual mode) → moderator approves → signed URL for matches only", async () => {
    const base = place(7);
    const a = await newPhone();
    const b = await newPhone();
    await onboard(a, { gender: "female", seeking: "male" });
    await onboard(b, { gender: "male", seeking: "female" });

    await a.upload(`uploads/${a.uid}/photo-0001.jpg`, tinyJpeg(), "image/jpeg");
    expect((await a.call("submitPhoto", { uploadId: "photo-0001" })).status).toBe("pending");
    const [uploadLeft] = await abucket().file(`uploads/${a.uid}/photo-0001.jpg`).exists();
    expect(uploadLeft).toBe(false);
    const item = (await adb.collection("moderationQueue").where("uid", "==", a.uid).where("type", "==", "photo").get()).docs[0]!;
    const [held] = await abucket().file(item.get("payload.path")).download();
    expect(held.toString("binary")).not.toContain("GPSLatitude"); // EXIF stripped before anyone sees it

    await a.call("updatePresence", base);
    await b.call("updatePresence", north(base, 10));
    const id = [a.uid, b.uid].sort().join("_");
    expect((await b.call("getEncounterCard", { encounterId: id })).photoUrl).toBeNull(); // unmoderated → no photo

    const mod = await makeAdmin();
    phones.push(mod);
    await mod.call("reviewModeration", { itemId: item.id, decision: "approve" });
    expect((await adb.collection("users").doc(a.uid).get()).get("photo.approved")).toBe(true);
    const card = await b.call("getEncounterCard", { encounterId: id });
    expect(card.photoUrl).toContain(encodeURIComponent(`profilePhotos/${a.uid}.jpg`));
    expect(card.card.hasPhoto).toBe(true);
  });

  it("rejects non-JPEG bytes and blocks uploads to other users' folders", async () => {
    const a = await newPhone();
    const b = await newPhone();
    await a.upload(`uploads/${a.uid}/notajpeg-01.jpg`, new TextEncoder().encode("hello"), "image/jpeg");
    await expectCode(a.call("submitPhoto", { uploadId: "notajpeg-01" }), "invalid-argument");
    await expect(b.upload(`uploads/${a.uid}/sneaky-0001.jpg`, tinyJpeg(), "image/jpeg")).rejects.toThrow();
    await expect(a.upload(`uploads/${a.uid}/wrongtype-1.jpg`, tinyJpeg(), "image/png")).rejects.toThrow();
  });
});

describe("data rights", () => {
  it("exportData returns the caller's data; deleteAccount erases it everywhere", async () => {
    const base = place(8);
    const a = await newPhone();
    const b = await newPhone();
    await onboard(a, { gender: "female", seeking: "male", displayName: "Del Ete" });
    await onboard(b, { gender: "male", seeking: "female" });
    await a.call("updatePresence", base);
    await b.call("updatePresence", north(base, 10));
    const id = [a.uid, b.uid].sort().join("_");
    await a.call("swipe", { encounterId: id, direction: "right" });
    await b.call("swipe", { encounterId: id, direction: "right" });
    await a.call("sendMessage", { pairId: id, text: "Hello from a Leo" });
    await a.call("registerPushToken", { token: "token-abcdefghijkl", platform: "ios" });
    await a.upload(`uploads/${a.uid}/photo-0002.jpg`, tinyJpeg(), "image/jpeg");
    await a.call("submitPhoto", { uploadId: "photo-0002" });

    const exp = await a.call("exportData", {});
    expect(exp.uid).toBe(a.uid);
    expect(exp.profile.displayName).toBe("Del Ete");
    expect(JSON.stringify(exp)).not.toContain("token-abcdefghijkl");
    expect(exp.matches).toHaveLength(1);
    expect(exp.chats[0].messagesYouSent[0].text).toBe("Hello from a Leo");
    expect(exp.consents.length).toBeGreaterThan(0);

    expect(await a.call("deleteAccount", {})).toEqual({ deleted: true });
    expect((await adb.doc(`users/${a.uid}`).get()).exists).toBe(false);
    expect((await adb.doc(`presence/${a.uid}`).get()).exists).toBe(false);
    expect((await adb.doc(`encounters/${id}`).get()).exists).toBe(false);
    expect((await adb.doc(`chats/${id}`).get()).exists).toBe(false);
    expect((await adb.collection(`chats/${id}/messages`).get()).size).toBe(0);
    expect((await adb.doc(`friends/${b.uid}/list/${a.uid}`).get()).exists).toBe(false);
    expect((await adb.collection("moderationQueue").where("uid", "==", a.uid).get()).size).toBe(0);
    const [files] = await abucket().getFiles({ prefix: `moderation/photos/${a.uid}/` });
    expect(files).toHaveLength(0);
    // Consent records are retained (Privacy Policy §7).
    expect((await adb.collection(`consents/${a.uid}/records`).get()).size).toBeGreaterThan(0);
    const { aauth } = await import("./harness.js");
    await expect(aauth().getUser(a.uid)).rejects.toThrow();
  });

  it("unauthenticated calls are refused", async () => {
    const { initializeApp } = await import("firebase/app");
    const { getFunctions, connectFunctionsEmulator, httpsCallable } = await import("firebase/functions");
    const app = initializeApp({ projectId: "conjunction-dev", apiKey: "x" }, "anon-check");
    const f = getFunctions(app, "us-central1");
    connectFunctionsEmulator(f, "127.0.0.1", 5001);
    await expectCode(httpsCallable(f, "exportData")({}), "unauthenticated");
  });
});
