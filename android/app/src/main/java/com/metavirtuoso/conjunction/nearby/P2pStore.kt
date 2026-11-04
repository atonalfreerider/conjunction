package com.metavirtuoso.conjunction.nearby

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** A friend record (SPEC 5c "friend record (key, name, photo, card) is stored locally"). */
data class StoredFriend(
    /** hex(SHA-256(identity key))[0..32): stable local id. */
    val id: String,
    /** Base64 of the friend's 65-byte identity public key. */
    val identityKey: String,
    /** Base64 of the 32-byte friend secret (docs/P2P_PROTOCOL.md 8.5). */
    val friendSecret: String,
    val safetyCode: String,
    val kind: String,
    val firstName: String,
    val bio: String?,
    val sun: String?,
    val moon: String?,
    val rising: String?,
    val chineseAnimal: String?,
    val photoPath: String?,
    val photoScreening: Screening,
    val since: Long,
    val lastSeen: Long,
    /** Blocked friends are kept (so a recognised blocked friend gets `bye done`), with no chat. */
    val blocked: Boolean = false,
)

/** A match card in the 24-h Matches inbox, keyed by profile fingerprint. */
data class StoredEncounter(
    val fingerprint: String,
    val kind: String,
    val total: Int,
    val western: Int,
    val chinese: Int,
    val blood: Int?,
    val highlights: List<String>,
    val birthdayTwin: Boolean,
    val exactTwin: Boolean,
    val age: Int,
    val sun: String,
    val moon: String,
    val rising: String?,
    val chineseAnimal: String,
    val relationship: String,
    val lookingFor: String,
    val firstName: String? = null,
    val bio: String? = null,
    val photoPath: String? = null,
    val photoScreening: Screening = Screening.PENDING,
    val mySwipe: String? = null,
    val peerSwipe: String? = null,
    val friendId: String? = null,
    val createdAt: Long,
    val expiresAt: Long,
    val notifiedAt: Long = 0L,
) {
    fun toCard(): EncounterCard = EncounterCard(
        encounterId = fingerprint, kind = kind, status = if (friendId != null) "matched" else "pending",
        total = total, western = western, chinese = chinese, blood = blood, highlights = highlights,
        birthdayTwin = birthdayTwin, exactTwin = exactTwin,
        card = MatchCard(firstName, age, sun, moon, rising, chineseAnimal, relationship, lookingFor, photoPath != null, bio),
        photoPath = photoPath, photoScreening = photoScreening, mySwipe = mySwipe, friendId = friendId,
        createdAt = createdAt, expiresAt = expiresAt,
    )
}

/** A stranger hidden by fingerprint: left swipe (30 days), snooze (7 days) or block (forever). */
data class HiddenPeer(val fingerprint: String, val until: Long, val reason: String)

data class P2pState(
    val friends: List<StoredFriend> = emptyList(),
    val messages: List<ChatLine> = emptyList(),
    val encounters: List<StoredEncounter> = emptyList(),
    val hidden: List<HiddenPeer> = emptyList(),
) {
    fun friend(id: String): StoredFriend? = friends.firstOrNull { it.id == id }
    fun encounter(fp: String): StoredEncounter? = encounters.firstOrNull { it.fingerprint == fp }
    fun isHidden(fp: String, now: Long): Boolean = hidden.any { it.fingerprint == fp && it.until > now }
    fun messagesWith(friendId: String): List<ChatLine> = messages.filter { it.friendId == friendId }
}

/**
 * Local Nearby storage: friends, chats, the Matches inbox and the hide/block list, in one
 * DataStore file (atomic writes). Received photos are JPEG files in [photosDir].
 *
 * Reads are synchronous ([state].value) so the Bluetooth engine thread can answer the link's
 * policy questions instantly; [update] applies a change in memory at once and persists the newest
 * snapshot in the background (conflated). Nothing here is backed up or leaves the phone.
 */
class P2pStore(
    private val dataStore: DataStore<P2pState>,
    val photosDir: File,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(P2pState())
    val state: StateFlow<P2pState> = _state.asStateFlow()
    private val lock = Any()
    private val loadLock = Mutex()
    @Volatile private var loaded = false
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private val writeLock = Mutex()

    init {
        scope.launch {
            load()
            for (signal in dirty) persist()
        }
    }

    /** Loads the file once (idempotent). Engines call this before they start. */
    suspend fun load() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            val stored = dataStore.data.first()
            synchronized(lock) { _state.value = stored }
            loaded = true
        }
    }

    val isLoaded: Boolean get() = loaded

    /** Applies [transform] in memory at once and schedules a write. Call [load] first. */
    fun update(transform: (P2pState) -> P2pState): P2pState {
        check(loaded) { "P2pStore.update() before load()" }
        val next = synchronized(lock) { transform(_state.value).also { _state.value = it } }
        dirty.trySend(Unit)
        return next
    }

    /** Writes the newest snapshot now (tests, and before the process may die). */
    suspend fun flush() = persist()

    private suspend fun persist() = writeLock.withLock {
        if (!loaded) return@withLock
        val snapshot = _state.value
        dataStore.updateData { snapshot }
    }

    /** Erases friends, chats, inbox, hide list and received photos (Delete all my data). */
    suspend fun clear() {
        update { P2pState() }
        persist()
        photosDir.deleteRecursively()
    }

    fun newPhotoFile(sha256: String): File = File(photosDir.apply { mkdirs() }, "$sha256.jpg")

    companion object {
        const val MAX_MESSAGES_PER_FRIEND = 1000

        fun create(filesDir: File, name: String = "p2p", scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)): P2pStore {
            val ds = DataStoreFactory.create(
                serializer = StateSerializer,
                corruptionHandler = ReplaceFileCorruptionHandler { P2pState() },
                scope = scope,
                produceFile = { File(filesDir, "datastore/$name.json") },
            )
            return P2pStore(ds, File(filesDir, "$name/photos"), scope)
        }
    }

    /** JSON file format (org.json). Unknown fields are ignored so the format can grow. */
    object StateSerializer : Serializer<P2pState> {
        override val defaultValue = P2pState()

        override suspend fun readFrom(input: InputStream): P2pState = try {
            decode(input.readBytes().toString(Charsets.UTF_8))
        } catch (e: org.json.JSONException) {
            throw CorruptionException("Nearby store is not valid JSON", e)
        }

        override suspend fun writeTo(t: P2pState, output: OutputStream) {
            output.write(encode(t).toByteArray(Charsets.UTF_8))
        }

        fun encode(s: P2pState): String = JSONObject().apply {
            put("v", 1)
            put("friends", JSONArray(s.friends.map { f ->
                JSONObject().put("id", f.id).put("ik", f.identityKey).put("fs", f.friendSecret).put("safety", f.safetyCode)
                    .put("kind", f.kind).put("name", f.firstName).putOpt("bio", f.bio).putOpt("sun", f.sun).putOpt("moon", f.moon)
                    .putOpt("rising", f.rising).putOpt("animal", f.chineseAnimal).putOpt("photo", f.photoPath)
                    .put("photoScreen", f.photoScreening.wire).put("since", f.since).put("lastSeen", f.lastSeen).put("blocked", f.blocked)
            }))
            put("messages", JSONArray(s.messages.map { m ->
                JSONObject().put("id", m.id).put("friend", m.friendId).put("mine", m.mine).put("text", m.text).put("ts", m.ts)
                    .put("status", m.status.wire).put("screen", m.screening.wire).put("seen", m.seen).put("rr", m.readReceiptSent)
            }))
            put("encounters", JSONArray(s.encounters.map { e ->
                JSONObject().put("fp", e.fingerprint).put("kind", e.kind).put("total", e.total).put("western", e.western)
                    .put("chinese", e.chinese).putOpt("blood", e.blood).put("highlights", JSONArray(e.highlights))
                    .put("twin", e.birthdayTwin).put("exactTwin", e.exactTwin).put("age", e.age).put("sun", e.sun).put("moon", e.moon)
                    .putOpt("rising", e.rising).put("animal", e.chineseAnimal).put("relationship", e.relationship)
                    .put("lookingFor", e.lookingFor).putOpt("name", e.firstName).putOpt("bio", e.bio).putOpt("photo", e.photoPath)
                    .put("photoScreen", e.photoScreening.wire).putOpt("mySwipe", e.mySwipe).putOpt("peerSwipe", e.peerSwipe)
                    .putOpt("friend", e.friendId).put("created", e.createdAt).put("expires", e.expiresAt).put("notified", e.notifiedAt)
            }))
            put("hidden", JSONArray(s.hidden.map { h -> JSONObject().put("fp", h.fingerprint).put("until", h.until).put("reason", h.reason) }))
        }.toString()

        fun decode(text: String): P2pState {
            if (text.isBlank()) return P2pState()
            val o = JSONObject(text)
            fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)
            fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
            return P2pState(
                friends = o.optJSONArray("friends").objects().map { f ->
                    StoredFriend(
                        id = f.getString("id"), identityKey = f.getString("ik"), friendSecret = f.getString("fs"),
                        safetyCode = f.optString("safety"), kind = f.optString("kind", "friends"), firstName = f.optString("name"),
                        bio = f.str("bio"), sun = f.str("sun"), moon = f.str("moon"), rising = f.str("rising"), chineseAnimal = f.str("animal"),
                        photoPath = f.str("photo"), photoScreening = Screening.fromWire(f.str("photoScreen")),
                        since = f.optLong("since"), lastSeen = f.optLong("lastSeen"), blocked = f.optBoolean("blocked"),
                    )
                },
                messages = o.optJSONArray("messages").objects().map { m ->
                    ChatLine(
                        id = m.getString("id"), friendId = m.getString("friend"), mine = m.getBoolean("mine"), text = m.getString("text"),
                        ts = m.optLong("ts"), status = ChatStatus.fromWire(m.str("status")), screening = Screening.fromWire(m.str("screen")),
                        seen = m.optBoolean("seen"), readReceiptSent = m.optBoolean("rr"),
                    )
                },
                encounters = o.optJSONArray("encounters").objects().map { e ->
                    StoredEncounter(
                        fingerprint = e.getString("fp"), kind = e.optString("kind", "friends"), total = e.optInt("total"),
                        western = e.optInt("western"), chinese = e.optInt("chinese"), blood = if (e.isNull("blood") || !e.has("blood")) null else e.getInt("blood"),
                        highlights = e.optJSONArray("highlights")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                        birthdayTwin = e.optBoolean("twin"), exactTwin = e.optBoolean("exactTwin"), age = e.optInt("age"),
                        sun = e.optString("sun"), moon = e.optString("moon"), rising = e.str("rising"), chineseAnimal = e.optString("animal"),
                        relationship = e.optString("relationship", "single"), lookingFor = e.optString("lookingFor", "friends"),
                        firstName = e.str("name"), bio = e.str("bio"), photoPath = e.str("photo"), photoScreening = Screening.fromWire(e.str("photoScreen")),
                        mySwipe = e.str("mySwipe"), peerSwipe = e.str("peerSwipe"), friendId = e.str("friend"),
                        createdAt = e.optLong("created"), expiresAt = e.optLong("expires"), notifiedAt = e.optLong("notified"),
                    )
                },
                hidden = o.optJSONArray("hidden").objects().map { h -> HiddenPeer(h.getString("fp"), h.getLong("until"), h.optString("reason")) },
            )
        }
    }
}
