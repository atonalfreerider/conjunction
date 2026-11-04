/**
 * Wrapper for callable functions: authentication, App Check (optional), input validation with zod,
 * and uniform error mapping. Every callable is authenticated (Anonymous Auth counts).
 */
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { z } from "zod";
import { REGION, TYPESAFE_API_KEY } from "../config.js";

export interface CallContext {
  uid: string;
  request: CallableRequest<unknown>;
  /** Server "now" for this call (one clock per call keeps comparisons consistent). */
  now: Date;
}

export interface CallableOptions {
  /** Needs the TypeSafe secret mounted. */
  moderation?: boolean;
  timeoutSeconds?: number;
  memory?: "256MiB" | "512MiB" | "1GiB";
}

export function callable<S extends z.ZodType, R>(
  schema: S,
  handler: (data: z.infer<S>, ctx: CallContext) => Promise<R>,
  opts: CallableOptions = {},
) {
  return onCall(
    {
      region: REGION,
      enforceAppCheck: process.env.ENFORCE_APP_CHECK === "true",
      secrets: opts.moderation ? [TYPESAFE_API_KEY] : [],
      timeoutSeconds: opts.timeoutSeconds ?? 30,
      memory: opts.memory ?? "256MiB",
      maxInstances: 20,
    },
    async (request) => {
      const uid = request.auth?.uid;
      if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
      const parsed = schema.safeParse(request.data ?? {});
      if (!parsed.success) {
        throw new HttpsError("invalid-argument", "Invalid request.", {
          issues: parsed.error.issues.map((i) => ({ path: i.path.join("."), message: i.message })),
        });
      }
      try {
        return await handler(parsed.data, { uid, request, now: new Date() });
      } catch (err) {
        if (err instanceof HttpsError) throw err;
        logger.error("callable failed", { error: err instanceof Error ? err.stack : String(err) });
        throw new HttpsError("internal", "Something went wrong. Please try again.");
      }
    },
  );
}

export const fail = (code: ConstructorParameters<typeof HttpsError>[0], message: string, details?: unknown): never => {
  throw new HttpsError(code, message, details);
};
