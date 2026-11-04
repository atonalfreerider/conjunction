/**
 * Conjunction Cloud Functions (2nd gen, Node 22). Every callable requires Firebase Auth
 * (Anonymous Auth is enough); see README for the full API.
 */
import { setGlobalOptions } from "firebase-functions/v2";
import { REGION } from "./config.js";

setGlobalOptions({ region: REGION, maxInstances: 20 });

export { updateProfile, recordConsents, registerPushToken, setNearby } from "./handlers/profile.js";
export { updatePresence } from "./handlers/presence.js";
export { swipe, connect, getEncounterCard, snooze } from "./handlers/encounters.js";
export { getFriendProfile, sendMessage, sendIntro, markRead, unmatch } from "./handlers/chat.js";
export { block, report } from "./handlers/safety.js";
export { submitPhoto, deletePhoto } from "./handlers/photos.js";
export { deleteAccount, exportData } from "./handlers/account.js";
export { reviewModeration, retryHeldModeration } from "./handlers/admin.js";
