package com.metavirtuoso.conjunction.nearby

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.metavirtuoso.conjunction.BuildConfig
import com.metavirtuoso.conjunction.core.compat.CompatProfile
import com.metavirtuoso.conjunction.legal.ConsentRecord
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * The whole client side of the Conjunction backend in one file, so it is easy to keep aligned with
 * backend/functions/src (handlers + schemas.ts). Every call is a Firebase callable function in
 * [REGION] and requires Firebase Auth (anonymous sign-in is done on first use).
 *
 * Callables used (backend/functions/src/index.ts):
 *   updateProfile, recordConsents, registerPushToken, setNearby, updatePresence,
 *   swipe, getEncounterCard, snooze, getFriendProfile, sendMessage, markRead, unmatch,
 *   block, report, submitPhoto, deletePhoto, deleteAccount, exportData.
 * Firestore reads (security rules allow a member to read their own): encounters where uids
 * contains me, chats/{pairId}/messages, friends/{me}/list.
 * Storage write: uploads/{uid}/{uploadId}.jpg (JPEG, owner only), then submitPhoto.
 * Push payload (notify.ts): data.kind = match | birthday_twin | exact_twin | conjunction | message,
 * plus encounterId / pairId / matchKind / total; Android channels "conjunctions" and "messages".
 *
 * The app builds without google-services.json; then [isConfigured] is false and the Nearby
 * screen shows "Nearby service not configured in this build".
 */
class NearbyApi(private val context: Context) {

    val isConfigured: Boolean
        get() = runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    private val functions by lazy {
        FirebaseFunctions.getInstance(REGION).also { fn ->
            BuildConfig.EMULATOR_HOST.takeIf { it.isNotBlank() }?.let { fn.useEmulator(it, 5001) }
        }
    }
    private val firestore by lazy {
        FirebaseFirestore.getInstance().also { fs ->
            BuildConfig.EMULATOR_HOST.takeIf { it.isNotBlank() }?.let { fs.useEmulator(it, 8080) }
        }
    }
    private val auth by lazy {
        FirebaseAuth.getInstance().also { a ->
            BuildConfig.EMULATOR_HOST.takeIf { it.isNotBlank() }?.let { a.useEmulator(it, 9099) }
        }
    }
    private val storage by lazy {
        FirebaseStorage.getInstance().also { s ->
            BuildConfig.EMULATOR_HOST.takeIf { it.isNotBlank() }?.let { s.useEmulator(it, 9199) }
        }
    }

    /** Current account id, or null before the first sign-in. */
    val uid: String? get() = if (isConfigured) auth.currentUser?.uid else null

    /** Signs in anonymously if needed and returns the uid. */
    suspend fun ensureSignedIn(): String {
        requireConfigured()
        auth.currentUser?.let { return it.uid }
        return auth.signInAnonymously().await().user?.uid ?: throw NearbyException("unauthenticated", "Could not sign in.")
    }

    private fun requireConfigured() {
        if (!isConfigured) throw NearbyException("not-configured", "Nearby service not configured in this build")
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun call(name: String, data: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        ensureSignedIn()
        return try {
            val result = functions.getHttpsCallable(name).call(data).await()
            (result.getData() as? Map<String, Any?>) ?: emptyMap()
        } catch (e: FirebaseFunctionsException) {
            throw NearbyException(e.code.name.lowercase().replace('_', '-'), e.message ?: "Request failed.", e.details as? Map<String, Any?>)
        }
    }

    // ---- profile & consents --------------------------------------------------------------------

    /** Uploads profile fields; only non-null arguments are sent. Returns per-field moderation status. */
    suspend fun updateProfile(
        displayName: String? = null,
        bio: String? = null,
        gender: String? = null,
        seeking: String? = null,
        relationshipStatus: String? = null,
        lookingFor: String? = null,
        compat: CompatProfile? = null,
        clearCompat: Boolean = false,
    ): Map<String, String> {
        val data = buildMap<String, Any?> {
            displayName?.let { put("displayName", it) }
            bio?.let { put("bio", it) }
            gender?.let { put("gender", it) }
            seeking?.let { put("seeking", it) }
            relationshipStatus?.let { put("relationshipStatus", it) }
            lookingFor?.let { put("lookingFor", it) }
            if (compat != null) put("compat", compatToWire(compat)) else if (clearCompat) put("compat", null)
        }
        @Suppress("UNCHECKED_CAST")
        return (call("updateProfile", data)["status"] as? Map<String, String>).orEmpty()
    }

    suspend fun recordConsents(records: List<ConsentRecord>) {
        if (records.isEmpty()) return
        // The backend accepts at most 20 per call.
        records.chunked(20).forEach { chunk ->
            call("recordConsents", mapOf("records" to chunk.map {
                mapOf(
                    "key" to it.key, "version" to it.version, "acceptedAt" to it.acceptedAtUtc,
                    "appVersion" to it.appVersion, "platform" to it.platform, "textSha256" to it.textSha256,
                    "withdrawn" to it.withdrawn,
                )
            }))
        }
    }

    /**
     * Turns on FCM and registers this install (firebase-messaging 26 API: register() delivers the
     * token to [ConjunctionMessagingService.onRegistered], which uploads it with [registerPushToken]).
     */
    suspend fun enablePush() {
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        FirebaseMessaging.getInstance().register().await()
    }

    suspend fun registerPushToken(token: String) {
        call("registerPushToken", mapOf("token" to token, "platform" to "android"))
    }

    /** Turns Nearby on/off server-side; [pausedUntil] pauses it (null resumes). */
    suspend fun setNearby(enabled: Boolean, pausedUntil: Instant? = null) {
        call("setNearby", buildMap {
            put("enabled", enabled)
            if (pausedUntil != null) put("pausedUntil", pausedUntil.toString()) else put("pausedUntil", null)
        })
    }

    // ---- presence ------------------------------------------------------------------------------

    /** Reports the latest position (kept 10 min server-side, never shown to anyone). */
    suspend fun updatePresence(lat: Double, lon: Double, accuracyM: Float?): PresenceResult {
        val r = call("updatePresence", buildMap {
            put("lat", lat); put("lon", lon)
            accuracyM?.let { put("accuracyM", it.toDouble().coerceIn(0.0, 100_000.0)) }
        })
        return PresenceResult(
            status = r["status"] as? String ?: "ok",
            nearbyCount = (r["nearbyCount"] as? Number)?.toInt() ?: 0,
            newEncounters = (r["newEncounters"] as? Number)?.toInt() ?: 0,
            retryAfterMs = (r["retryAfterMs"] as? Number)?.toLong(),
        )
    }

    // ---- encounters (match cards) --------------------------------------------------------------

    suspend fun getEncounterCard(encounterId: String): EncounterCard = parseCard(call("getEncounterCard", mapOf("encounterId" to encounterId)))

    /** Right = accept, left = pass (silent). Returns "waiting" | "matched" | "rejected" and the chat id when matched. */
    suspend fun swipe(encounterId: String, accept: Boolean): SwipeResult {
        val r = call("swipe", mapOf("encounterId" to encounterId, "direction" to if (accept) "right" else "left"))
        return SwipeResult(r["outcome"] as? String ?: "waiting", r["pairId"] as? String)
    }

    suspend fun snooze(otherUid: String, hours: Int) {
        call("snooze", mapOf("uid" to otherUid, "hours" to hours))
    }

    /** Live list of my pending and matched encounters (Matches inbox), newest first. */
    fun encounters(uid: String): Flow<List<EncounterSummary>> = callbackFlow {
        val registration = firestore.collection("encounters")
            .whereArrayContains("uids", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snap, error ->
                if (error != null) { close(NearbyException("unavailable", error.message ?: "Could not load matches.")); return@addSnapshotListener }
                val list = snap?.documents.orEmpty().mapNotNull { doc ->
                    runCatching {
                        val uids = (doc.get("uids") as List<*>).map { it as String }
                        val side = uids.indexOf(uid)
                        val otherUid = uids[1 - side]
                        @Suppress("UNCHECKED_CAST")
                        val cards = doc.get("cards") as? Map<String, Map<String, Any?>>
                        @Suppress("UNCHECKED_CAST")
                        val score = doc.get("score") as? Map<String, Any?>
                        EncounterSummary(
                            id = doc.id,
                            otherUid = otherUid,
                            kind = doc.getString("kind") ?: "friends",
                            status = doc.getString("status") ?: "pending",
                            mySwipe = doc.getString(if (side == 0) "swipeA" else "swipeB"),
                            total = (score?.get("total") as? Number)?.toInt() ?: 0,
                            birthdayTwin = doc.getBoolean("birthdayTwin") ?: false,
                            exactTwin = doc.getBoolean("exactTwin") ?: false,
                            card = cards?.get(otherUid)?.let(::parseMatchCard),
                            createdAt = doc.getTimestamp("createdAt")?.toDate()?.toInstant(),
                            expiresAt = doc.getTimestamp("expiresAt")?.toDate()?.toInstant(),
                        )
                    }.getOrNull()
                }
                trySend(list)
            }
        awaitClose { registration.remove() }
    }

    // ---- friends & chat ------------------------------------------------------------------------

    suspend fun getFriendProfile(otherUid: String): FriendProfile {
        val r = call("getFriendProfile", mapOf("uid" to otherUid))
        return FriendProfile(
            uid = r["uid"] as String,
            pairId = r["pairId"] as String,
            kind = r["kind"] as? String ?: "friends",
            displayName = r["displayName"] as? String,
            bioFirstLine = r["bioFirstLine"] as? String,
            sun = r["sun"] as? String,
            moon = r["moon"] as? String,
            rising = r["rising"] as? String,
            chineseAnimal = r["chineseAnimal"] as? String,
            photoUrl = r["photoUrl"] as? String,
        )
    }

    /** My mutual matches: friends/{me}/list/{other} = {pairId, kind, since}. */
    fun friends(uid: String): Flow<List<FriendLink>> = callbackFlow {
        val registration = firestore.collection("friends").document(uid).collection("list")
            .orderBy("since", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, error ->
                if (error != null) { close(NearbyException("unavailable", error.message ?: "Could not load matches.")); return@addSnapshotListener }
                trySend(snap?.documents.orEmpty().map { d ->
                    FriendLink(d.id, d.getString("pairId") ?: "", d.getString("kind") ?: "friends", d.getTimestamp("since")?.toDate()?.toInstant())
                })
            }
        awaitClose { registration.remove() }
    }

    /** Live messages in a chat, oldest first (chats/{pairId}/messages). */
    fun messages(pairId: String): Flow<List<ChatMessage>> = callbackFlow {
        val registration = firestore.collection("chats").document(pairId).collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limitToLast(200)
            .addSnapshotListener { snap, error ->
                if (error != null) { close(NearbyException("unavailable", error.message ?: "Could not load messages.")); return@addSnapshotListener }
                trySend(snap?.documents.orEmpty().map { d ->
                    ChatMessage(
                        id = d.id,
                        from = d.getString("from") ?: "",
                        text = d.getString("text") ?: "",
                        createdAt = d.getTimestamp("createdAt")?.toDate()?.toInstant(),
                    )
                })
            }
        awaitClose { registration.remove() }
    }

    /** Read receipts: chats/{pairId}.readAt.{uid}. */
    fun readState(pairId: String): Flow<Map<String, Instant>> = callbackFlow {
        val registration = firestore.collection("chats").document(pairId).addSnapshotListener { snap, _ ->
            @Suppress("UNCHECKED_CAST")
            val raw = snap?.get("readAt") as? Map<String, com.google.firebase.Timestamp>
            trySend(raw.orEmpty().mapValues { it.value.toDate().toInstant() })
        }
        awaitClose { registration.remove() }
    }

    /** Sends a chat message; it is moderated before delivery ("delivered" | "held" | "blocked"). */
    suspend fun sendMessage(pairId: String, text: String, clientMessageId: String = newClientId()): String =
        call("sendMessage", mapOf("pairId" to pairId, "text" to text, "clientMessageId" to clientMessageId))["status"] as? String ?: "delivered"

    suspend fun markRead(pairId: String) {
        call("markRead", mapOf("pairId" to pairId))
    }

    suspend fun unmatch(pairId: String) {
        call("unmatch", mapOf("pairId" to pairId))
    }

    // ---- safety --------------------------------------------------------------------------------

    suspend fun block(otherUid: String) {
        call("block", mapOf("uid" to otherUid))
    }

    /** [reason] is one of [ReportReason] wire values. */
    suspend fun report(otherUid: String, reason: ReportReason, description: String, pairId: String? = null, encounterId: String? = null, alsoBlock: Boolean = true) {
        call("report", buildMap {
            put("uid", otherUid)
            put("reason", reason.wire)
            put("description", description.take(2000))
            put("alsoBlock", alsoBlock)
            pairId?.let { put("pairId", it) }
            encounterId?.let { put("encounterId", it) }
        })
    }

    // ---- photo ---------------------------------------------------------------------------------

    /** Uploads a JPEG and submits it for moderation. Returns "approved" | "pending" | "rejected". */
    suspend fun submitPhoto(jpeg: ByteArray): String {
        val uid = ensureSignedIn()
        val uploadId = newClientId()
        storage.reference.child("uploads/$uid/$uploadId.jpg")
            .putBytes(jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build())
            .await()
        return call("submitPhoto", mapOf("uploadId" to uploadId))["status"] as? String ?: "pending"
    }

    suspend fun deletePhoto() {
        call("deletePhoto")
    }

    // ---- account -------------------------------------------------------------------------------

    /** Full erasure server-side, then signs out locally. */
    suspend fun deleteAccount() {
        call("deleteAccount")
        runCatching { FirebaseMessaging.getInstance().unregister().await() }
        auth.signOut()
    }

    /** The server-side data export as a JSON-ready map. */
    suspend fun exportData(): Map<String, Any?> = call("exportData")

    companion object {
        const val REGION = "us-central1"

        fun newClientId(): String = UUID.randomUUID().toString().replace("-", "")

        /** Wire format of CompatProfile (schemas.ts compatProfileSchema). */
        fun compatToWire(c: CompatProfile): Map<String, Any?> = mapOf(
            "sun" to c.sun,
            "moon" to c.moon,
            "venus" to c.venus,
            "mars" to c.mars,
            "ascendant" to c.ascendant,
            "chineseAnimal" to c.chineseAnimal.key,
            // schemas.ts STEMS: lower-case ASCII pinyin ("jia" .. "gui").
            "dayStem" to c.dayStem.name.lowercase(java.util.Locale.ROOT),
            "bloodType" to c.bloodType?.name,
            "birthDate" to c.birthDate.toString(),
            "birthHour" to c.birthHour,
        )

        fun parseMatchCard(m: Map<String, Any?>): MatchCard = MatchCard(
            firstName = m["firstName"] as? String,
            age = (m["age"] as? Number)?.toInt() ?: 0,
            sun = m["sun"] as? String ?: "",
            moon = m["moon"] as? String ?: "",
            rising = m["rising"] as? String,
            chineseAnimal = m["chineseAnimal"] as? String ?: "",
            relationshipStatus = m["relationshipStatus"] as? String ?: "single",
            lookingFor = m["lookingFor"] as? String ?: "friends",
            hasPhoto = m["hasPhoto"] as? Boolean ?: false,
        )

        @Suppress("UNCHECKED_CAST")
        fun parseCard(r: Map<String, Any?>): EncounterCard {
            val score = r["score"] as? Map<String, Any?> ?: emptyMap()
            return EncounterCard(
                encounterId = r["encounterId"] as String,
                otherUid = r["otherUid"] as String,
                kind = r["kind"] as? String ?: "friends",
                status = r["status"] as? String ?: "pending",
                total = (score["total"] as? Number)?.toInt() ?: 0,
                western = (score["western"] as? Number)?.toInt() ?: 0,
                chinese = (score["chinese"] as? Number)?.toInt() ?: 0,
                blood = (score["blood"] as? Number)?.toInt(),
                highlights = (score["highlights"] as? List<String>).orEmpty(),
                birthdayTwin = r["birthdayTwin"] as? Boolean ?: false,
                exactTwin = r["exactTwin"] as? Boolean ?: false,
                card = (r["card"] as? Map<String, Any?>)?.let(::parseMatchCard),
                photoUrl = r["photoUrl"] as? String,
                expiresAt = (r["expiresAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() },
            )
        }
    }
}

class NearbyException(val code: String, message: String, val details: Map<String, Any?>? = null) : Exception(message)

data class PresenceResult(val status: String, val nearbyCount: Int, val newEncounters: Int, val retryAfterMs: Long?)

/** What a match card shows (backend lib/users.ts MatchCard): sign keys are lower-case ("leo"). */
data class MatchCard(
    val firstName: String?,
    val age: Int,
    val sun: String,
    val moon: String,
    val rising: String?,
    val chineseAnimal: String,
    val relationshipStatus: String,
    val lookingFor: String,
    val hasPhoto: Boolean,
)

data class EncounterCard(
    val encounterId: String,
    val otherUid: String,
    val kind: String,
    val status: String,
    val total: Int,
    val western: Int,
    val chinese: Int,
    val blood: Int?,
    val highlights: List<String>,
    val birthdayTwin: Boolean,
    val exactTwin: Boolean,
    val card: MatchCard?,
    /** 15-minute signed URL; null when no approved photo (show zodiac art instead). */
    val photoUrl: String?,
    val expiresAt: Instant?,
)

data class EncounterSummary(
    val id: String,
    val otherUid: String,
    val kind: String,
    /** pending | matched | rejected | closed */
    val status: String,
    val mySwipe: String?,
    val total: Int,
    val birthdayTwin: Boolean,
    val exactTwin: Boolean,
    val card: MatchCard?,
    val createdAt: Instant?,
    val expiresAt: Instant?,
)

data class SwipeResult(val outcome: String, val pairId: String?)

data class FriendLink(val otherUid: String, val pairId: String, val kind: String, val since: Instant?)

data class FriendProfile(
    val uid: String,
    val pairId: String,
    val kind: String,
    val displayName: String?,
    val bioFirstLine: String?,
    val sun: String?,
    val moon: String?,
    val rising: String?,
    val chineseAnimal: String?,
    val photoUrl: String?,
)

data class ChatMessage(val id: String, val from: String, val text: String, val createdAt: Instant?)

/** schemas.ts reportSchema reasons. */
enum class ReportReason(val wire: String, val label: String) {
    HARASSMENT("harassment", "Harassment or bullying"),
    THREATS("threats_or_violence", "Threats or violence"),
    SEXUAL("sexual_content", "Unwanted sexual content"),
    SCAM("scam_or_solicitation", "Scam, spam or solicitation"),
    HATE("hate", "Hate speech"),
    UNDERAGE("underage_user", "May be under 18"),
    FAKE("fake_profile", "Fake profile"),
    SPAM("spam", "Spam"),
    IN_PERSON("in_person_incident", "Something happened in person"),
    OTHER("other", "Something else"),
}
