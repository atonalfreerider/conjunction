/**
 * The scheduled retry of text held during a TypeSafe outage. Runs `retryUnavailable` in-process
 * against the Firestore emulator with the fake moderator (the scheduler itself is not emulated).
 */
import { describe, expect, it } from "vitest";
import { adb } from "./harness.js";

describe("retryHeldModeration", () => {
  it("publishes text the model now allows, rejects what it blocks, keeps counting outages", async () => {
    process.env.FUNCTIONS_EMULATOR = "true";
    process.env.MODERATION_PROVIDER = "fake";
    process.env.GCLOUD_PROJECT ??= "conjunction-dev";
    const { retryUnavailable } = await import("../../src/handlers/admin.js");

    const uid = `retry-user-${Date.now()}`;
    await adb.doc(`users/${uid}`).set({ bio: null, pending: { bio: "Plant parent", displayName: "Bad [[hate]]" } });
    const ok = await adb.collection("moderationQueue").add({ type: "bio", uid, reason: "unavailable", status: "open", attempts: 0, payload: { text: "Plant parent" } });
    const bad = await adb.collection("moderationQueue").add({ type: "display_name", uid, reason: "unavailable", status: "open", attempts: 0, payload: { text: "Bad [[hate]]" } });
    const down = await adb.collection("moderationQueue").add({ type: "bio", uid: `${uid}-2`, reason: "unavailable", status: "open", attempts: 7, payload: { text: "still [[outage]]" } });

    const r = await retryUnavailable(new Date());
    expect(r.resolved).toBeGreaterThanOrEqual(2);
    const user = (await adb.doc(`users/${uid}`).get()).data()!;
    expect(user.bio).toBe("Plant parent");
    expect(user.pending?.bio).toBeUndefined();
    expect(user.displayName).toBeUndefined();
    expect(user.pending?.displayName).toBeUndefined();
    expect((await ok.get()).get("decision")).toBe("approve");
    expect((await bad.get()).get("decision")).toBe("reject");
    const d = (await down.get()).data()!;
    expect(d).toMatchObject({ status: "open", attempts: 8, reason: "held" }); // handed to a person after 8 tries
  });
});
