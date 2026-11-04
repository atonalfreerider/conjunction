/**
 * Runtime configuration: secrets, feature switches and product limits in one place.
 *
 * Environment switches (functions/.env for deploys, functions/.env.local for the emulator):
 *   MODERATION_PROVIDER = typesafe (default) | fake (emulator tests only) | none (hold everything)
 *   TYPESAFE_MODEL      = pinned Jev version (default jev-1.14.0; thresholds were checked against it)
 *   TYPESAFE_API_KEY_FILE = path to a key file, used only when the TYPESAFE_API_KEY secret is empty
 *                           (local emulator / scripts). Never set in production.
 *   PHOTO_MODERATION    = vision (default: Cloud Vision SafeSearch) | manual (every photo waits for a person)
 *   ENFORCE_APP_CHECK   = true to reject callables without a valid App Check token (recommended in prod)
 */
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { defineSecret } from "firebase-functions/params";
import { logger } from "firebase-functions";
import { FakeModerator, TypeSafeModerator, UnavailableModerator, type Moderator } from "./moderation/moderator.js";
import { DEFAULT_MODEL } from "./moderation/questions.js";

/** `firebase functions:secrets:set TYPESAFE_API_KEY` — never in the repo or an app binary. */
export const TYPESAFE_API_KEY = defineSecret("TYPESAFE_API_KEY");

export const REGION = "us-central1";

export const isEmulator = () => process.env.FUNCTIONS_EMULATOR === "true";

export const LIMITS = {
  /** Proximity radius for a match (metres). */
  nearbyRadiusM: 50,
  /** Presence document lifetime; Firestore TTL deletes it afterwards. */
  presenceTtlMs: 10 * 60_000,
  /** Minimum spacing between presence updates from one user (the app sends every ~60 s / 25 m). */
  presenceMinIntervalMs: 15_000,
  /** Max encounters created by one presence update (busy places). */
  maxNewEncountersPerUpdate: 5,
  /** Max encounter notifications per user per hour. */
  maxEncounterPushesPerHour: 12,
  /** A pair is not re-notified within this window. */
  encounterDedupeMs: 24 * 3600_000,
  /** Pending match cards stay answerable this long. */
  pendingMatchMs: 24 * 3600_000,
  /** After a left swipe the pair is not shown again for this long. */
  rejectCooldownMs: 30 * 24 * 3600_000,
  /** Encounter documents that did not lead to a match are purged (TTL) this long after their last activity. */
  encounterRetentionMs: 30 * 24 * 3600_000,
  maxPauseMs: 48 * 3600_000,
  maxSnoozeMs: 30 * 24 * 3600_000,
  displayNameMax: 40,
  bioMax: 300,
  messageMax: 1000,
  introMax: 280,
  messagesPerMinute: 20,
  messagesPerDay: 500,
  reportsPerDay: 20,
  profileUpdatesPerHour: 30,
  photoSubmissionsPerDay: 10,
  photoMaxBytes: 8 * 1024 * 1024,
  signedUrlTtlMs: 15 * 60_000,
  exportsPerDay: 3,
} as const;

/** Reads the TypeSafe key: the Firebase secret first, else TYPESAFE_API_KEY_FILE (local only). */
export function resolveTypesafeKey(): string | null {
  let value = "";
  try {
    value = TYPESAFE_API_KEY.value()?.trim() ?? "";
  } catch {
    value = "";
  }
  if (value && value !== "unset") return value;
  const file = process.env.TYPESAFE_API_KEY_FILE;
  if (file) {
    try {
      const fromFile = readFileSync(file.replace(/^~(?=\/|$)/, homedir()), "utf8").trim();
      if (fromFile) return fromFile;
    } catch {
      logger.warn("TYPESAFE_API_KEY_FILE is set but unreadable");
    }
  }
  return null;
}

let cachedModerator: Moderator | null = null;

/** The moderator for this instance (memoised). Fails closed when no key is configured. */
export function getModerator(): Moderator {
  if (cachedModerator) return cachedModerator;
  const provider = (process.env.MODERATION_PROVIDER ?? "typesafe").toLowerCase();
  if (provider === "fake") {
    if (!isEmulator()) throw new Error("MODERATION_PROVIDER=fake is only allowed in the emulator");
    cachedModerator = new FakeModerator();
  } else if (provider === "none") {
    cachedModerator = new UnavailableModerator();
  } else {
    const mod = TypeSafeModerator.fromKey(resolveTypesafeKey(), {
      model: process.env.TYPESAFE_MODEL || DEFAULT_MODEL,
      log: (message, data) => logger.warn(message, data),
    });
    if (!mod) logger.error("TYPESAFE_API_KEY is not configured: all user text will be held for review");
    cachedModerator = mod ?? new UnavailableModerator();
  }
  return cachedModerator;
}

/** Test hook. */
export function setModeratorForTests(m: Moderator | null): void {
  cachedModerator = m;
}
