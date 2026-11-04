/** zod schemas for callable inputs (wire values are the lower-case strings in docs/MATCH_FILTER.md). */
import { z } from "zod";
import { GENDERS, LOOKING_FORS, RELATIONSHIP_STATUSES, SEEKINGS, parseIsoDate } from "./lib/matchFilter.js";
import { CHINESE_ANIMALS } from "./lib/zodiac.js";
import { BLOOD_TYPES, STEMS } from "./compat/types.js";
import { CONSENT_KEYS } from "./lib/users.js";

const longitude = z.number().finite().min(0).max(360);
const isoDate = z.string().refine((s) => {
  try {
    parseIsoDate(s);
    return true;
  } catch {
    return false;
  }
}, "expected yyyy-mm-dd");

export const uid = z.string().min(1).max(128);
export const docId = z.string().min(1).max(256).regex(/^[^/]+$/);

export const compatProfileSchema = z.object({
  sun: longitude,
  moon: longitude,
  venus: longitude,
  mars: longitude,
  ascendant: longitude.nullable(),
  chineseAnimal: z.enum(CHINESE_ANIMALS),
  dayStem: z.enum(STEMS),
  bloodType: z.enum(BLOOD_TYPES).nullable(),
  birthDate: isoDate,
  birthHour: z.number().int().min(0).max(23).nullable(),
}).strict();

export const updateProfileSchema = z.object({
  displayName: z.string().max(200).optional(),
  bio: z.string().max(2000).nullable().optional(),
  gender: z.enum(GENDERS).optional(),
  seeking: z.enum(SEEKINGS).optional(),
  relationshipStatus: z.enum(RELATIONSHIP_STATUSES).optional(),
  lookingFor: z.enum(LOOKING_FORS).optional(),
  compat: compatProfileSchema.nullable().optional(),
}).strict();

export const recordConsentsSchema = z.object({
  records: z.array(z.object({
    key: z.enum(CONSENT_KEYS),
    version: z.string().min(1).max(32),
    /** Client-side acceptance time (ISO 8601); the server also stores its own timestamp. */
    acceptedAt: z.string().datetime({ offset: true }),
    appVersion: z.string().max(64),
    platform: z.enum(["android", "ios"]),
    textSha256: z.string().regex(/^[0-9a-f]{64}$/),
    withdrawn: z.boolean().default(false),
  }).strict()).min(1).max(20),
}).strict();

export const registerPushTokenSchema = z.object({
  token: z.string().min(10).max(4096),
  platform: z.enum(["android", "ios"]),
}).strict();

export const setNearbySchema = z.object({
  enabled: z.boolean(),
  /** ISO time to pause until (the app computes "1 h" / "tomorrow" in local time); null resumes. */
  pausedUntil: z.string().datetime({ offset: true }).nullable().optional(),
}).strict();

export const updatePresenceSchema = z.object({
  lat: z.number().finite().min(-90).max(90),
  lon: z.number().finite().min(-180).max(180),
  /** Reported accuracy in metres (informational; very poor fixes are ignored). */
  accuracyM: z.number().finite().min(0).max(100_000).optional(),
}).strict();

export const encounterIdSchema = z.object({ encounterId: docId }).strict();
export const swipeSchema = z.object({ encounterId: docId, direction: z.enum(["right", "left"]) }).strict();
export const otherUidSchema = z.object({ uid }).strict();
export const snoozeSchema = z.object({ uid, hours: z.number().int().min(1).max(720) }).strict();
export const pairIdSchema = z.object({ pairId: docId }).strict();
export const sendMessageSchema = z.object({
  pairId: docId,
  text: z.string().min(1).max(4000),
  /** Client-generated id for idempotent retries. */
  clientMessageId: z.string().regex(/^[A-Za-z0-9_-]{8,64}$/).optional(),
}).strict();
export const submitPhotoSchema = z.object({ uploadId: z.string().regex(/^[A-Za-z0-9_-]{8,64}$/) }).strict();
export const reportSchema = z.object({
  uid,
  pairId: docId.optional(),
  encounterId: docId.optional(),
  reason: z.enum([
    "harassment", "threats_or_violence", "sexual_content", "scam_or_solicitation", "hate",
    "underage_user", "fake_profile", "spam", "in_person_incident", "other",
  ]),
  description: z.string().max(2000).default(""),
  alsoBlock: z.boolean().default(true),
}).strict();
export const reviewSchema = z.object({
  itemId: docId,
  decision: z.enum(["approve", "reject"]),
  note: z.string().max(1000).optional(),
}).strict();
export const emptySchema = z.object({}).strict();
