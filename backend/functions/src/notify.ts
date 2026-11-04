/**
 * Push notifications through FCM (Android directly, iOS via APNs through FCM).
 *
 * Contract with the apps (also in README "Push payload"):
 *   data.kind     match | birthday_twin | exact_twin | conjunction | message
 *   data.encounterId  for match / birthday_twin / exact_twin / conjunction
 *   data.pairId       for conjunction / message (chat id)
 *   data.matchKind    dating | friends
 *   data.total        compatibility 0..100 (string; FCM data values are strings)
 *   Android channel ids: "conjunctions" (matches, twins, mutual matches), "messages" (chat).
 *
 * Message pushes never include the message text (lock screens are public); exact location is never
 * part of any payload. Under the emulator, messages are written to `devOutbox` instead of being sent,
 * so integration tests can assert on them.
 */
import { FieldValue } from "firebase-admin/firestore";
import { logger } from "firebase-functions";
import type { MulticastMessage } from "firebase-admin/messaging";
import { db, messaging } from "./firebase.js";
import { isEmulator } from "./config.js";

export type PushKind = "match" | "birthday_twin" | "exact_twin" | "conjunction" | "message";

export interface Push {
  kind: PushKind;
  title: string;
  body: string;
  data: Record<string, string>;
}

const CHANNEL: Record<PushKind, string> = {
  match: "conjunctions",
  birthday_twin: "conjunctions",
  exact_twin: "conjunctions",
  conjunction: "conjunctions",
  message: "messages",
};

interface StoredToken { token: string; platform?: string }

export async function sendPush(uid: string, push: Push): Promise<void> {
  const data = { ...push.data, kind: push.kind };
  if (isEmulator()) {
    await db().collection("devOutbox").add({ uid, ...push, data, createdAt: FieldValue.serverTimestamp() });
    return;
  }
  const userRef = db().collection("users").doc(uid);
  const snap = await userRef.get();
  const tokens = ((snap.get("push.tokens") as StoredToken[] | undefined) ?? []).map((t) => t.token).filter(Boolean);
  if (!tokens.length) return;
  const collapse = push.data.encounterId ?? push.data.pairId ?? push.kind;
  const message: MulticastMessage = {
    tokens,
    notification: { title: push.title, body: push.body },
    data,
    android: {
      priority: "high",
      collapseKey: collapse,
      notification: { channelId: CHANNEL[push.kind], tag: collapse, sound: "default" },
    },
    apns: {
      headers: { "apns-priority": "10", "apns-collapse-id": collapse.slice(0, 64) },
      payload: { aps: { sound: "default", threadId: push.kind === "message" ? `chat-${push.data.pairId ?? ""}` : "conjunctions" } },
    },
  };
  try {
    const res = await messaging().sendEachForMulticast(message);
    const dead = res.responses
      .map((r, i) => ({ r, token: tokens[i]! }))
      .filter(({ r }) => !r.success && (r.error?.code === "messaging/registration-token-not-registered" || r.error?.code === "messaging/invalid-registration-token"))
      .map(({ token }) => token);
    if (dead.length) {
      await db().runTransaction(async (tx) => {
        const cur = await tx.get(userRef);
        const kept = ((cur.get("push.tokens") as StoredToken[] | undefined) ?? []).filter((t) => !dead.includes(t.token));
        tx.update(userRef, { "push.tokens": kept });
      });
    }
  } catch (err) {
    // A failed push must not fail the action that triggered it (the encounter / message exists).
    logger.warn("push failed", { uid, kind: push.kind, error: err instanceof Error ? err.message : String(err) });
  }
}
