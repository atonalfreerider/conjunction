/**
 * Image moderation with Cloud Vision SafeSearch.
 *
 * PHOTO_MODERATION=vision (default) asks SafeSearch for adult / racy / violence / medical / spoof
 * likelihoods and maps them to approve / reject / review. If the Vision API is not enabled, has no
 * quota, or errors for any reason, the photo is held for human review (fail closed) — it is never
 * shown while unmoderated. PHOTO_MODERATION=manual sends every photo to human review (useful before
 * the owner enables the Vision API; also used under the emulator).
 */
import { ImageAnnotatorClient } from "@google-cloud/vision";
import { logger } from "firebase-functions";

export type PhotoDecision = "approve" | "reject" | "review";
type Likelihood = "UNKNOWN" | "VERY_UNLIKELY" | "UNLIKELY" | "POSSIBLE" | "LIKELY" | "VERY_LIKELY";

export interface SafeSearch { adult: Likelihood; racy: Likelihood; violence: Likelihood; medical: Likelihood; spoof: Likelihood }

export interface PhotoModerationResult {
  decision: PhotoDecision;
  reason: "vision" | "vision_unavailable" | "manual_review";
  safeSearch: SafeSearch | null;
}

const LEVEL: Record<Likelihood, number> = { UNKNOWN: 2, VERY_UNLIKELY: 0, UNLIKELY: 1, POSSIBLE: 2, LIKELY: 3, VERY_LIKELY: 4 };

/** Policy: profile photos are shown to strangers, so anything suggestive goes to a person. */
export function decidePhoto(s: SafeSearch): PhotoDecision {
  const adult = LEVEL[s.adult], racy = LEVEL[s.racy], violence = LEVEL[s.violence], medical = LEVEL[s.medical];
  if (adult >= 3 || violence >= 3) return "reject";
  if (adult >= 2 || racy >= 3 || violence >= 2 || medical >= 3) return "review";
  return "approve";
}

let client: ImageAnnotatorClient | null = null;

export async function moderatePhoto(jpeg: Buffer): Promise<PhotoModerationResult> {
  if ((process.env.PHOTO_MODERATION ?? "vision").toLowerCase() === "manual") {
    return { decision: "review", reason: "manual_review", safeSearch: null };
  }
  try {
    client ??= new ImageAnnotatorClient();
    const [res] = await client.safeSearchDetection({ image: { content: jpeg } });
    const a = res.safeSearchAnnotation;
    if (!a) throw new Error("no annotation");
    const pick = (v: unknown): Likelihood => (typeof v === "string" && v in LEVEL ? (v as Likelihood) : "UNKNOWN");
    const safeSearch: SafeSearch = { adult: pick(a.adult), racy: pick(a.racy), violence: pick(a.violence), medical: pick(a.medical), spoof: pick(a.spoof) };
    return { decision: decidePhoto(safeSearch), reason: "vision", safeSearch };
  } catch (err) {
    logger.warn("Cloud Vision SafeSearch unavailable; photo held for review", { error: err instanceof Error ? err.message : String(err) });
    return { decision: "review", reason: "vision_unavailable", safeSearch: null };
  }
}
