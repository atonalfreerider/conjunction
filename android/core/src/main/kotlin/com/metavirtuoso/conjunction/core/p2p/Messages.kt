package com.metavirtuoso.conjunction.core.p2p

import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.chinese.ChineseAnimal
import com.metavirtuoso.conjunction.core.chinese.HeavenlyStem
import com.metavirtuoso.conjunction.core.compat.Compatibility
import com.metavirtuoso.conjunction.core.compat.CompatProfile
import com.metavirtuoso.conjunction.core.match.Gender
import com.metavirtuoso.conjunction.core.match.LookingFor
import com.metavirtuoso.conjunction.core.match.MatchKind
import com.metavirtuoso.conjunction.core.match.MatchProfile
import com.metavirtuoso.conjunction.core.match.RelationshipStatus
import com.metavirtuoso.conjunction.core.match.Seeking
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Base64

/*
 * Message schemas (docs/P2P_PROTOCOL.md section 5.3). toWire() returns the members in wire order;
 * P2pMessages.decode validates a received body. Byte arrays travel as base64 strings and are kept
 * as strings here so data-class equality works; use the *Bytes accessors.
 */

/** `hello.compat`: the CompatProfile with longitudes as integer centidegrees. */
data class WireCompat(
    val sun: Int,
    val moon: Int,
    val venus: Int,
    val mars: Int,
    val asc: Int?,
    val animal: ChineseAnimal,
    val stem: HeavenlyStem,
    val blood: BloodType?,
    val birthDate: LocalDate,
    val birthHour: Int?,
) {
    init {
        for (v in listOfNotNull(sun, moon, venus, mars, asc)) require(v in 0..35_999) { "centidegrees out of range: $v" }
        birthHour?.let { require(it in 0..23) { "birthHour out of range" } }
    }

    fun toWire(): Map<String, Any?> = linkedMapOf(
        "sun" to sun, "moon" to moon, "venus" to venus, "mars" to mars, "asc" to asc,
        "animal" to animal.key, "stem" to stem.name.lowercase(), "blood" to blood?.key,
        "birthDate" to birthDate.toString(), "birthHour" to birthHour,
    )

    /** Exact: Compatibility.centidegrees(c / 100.0) == c for every c in 0..35999. */
    fun toCompatProfile(): CompatProfile = CompatProfile(
        sun = sun / 100.0, moon = moon / 100.0, venus = venus / 100.0, mars = mars / 100.0,
        ascendant = asc?.let { it / 100.0 }, chineseAnimal = animal, dayStem = stem, bloodType = blood,
        birthDate = birthDate, birthHour = birthHour,
    )

    companion object {
        fun from(p: CompatProfile): WireCompat = WireCompat(
            sun = Compatibility.centidegrees(p.sun), moon = Compatibility.centidegrees(p.moon),
            venus = Compatibility.centidegrees(p.venus), mars = Compatibility.centidegrees(p.mars),
            asc = p.ascendant?.let(Compatibility::centidegrees), animal = p.chineseAnimal, stem = p.dayStem,
            blood = p.bloodType, birthDate = p.birthDate, birthHour = p.birthHour,
        )
    }
}

internal fun MatchProfile.toWire(): Map<String, Any?> = linkedMapOf(
    "gender" to gender.wire, "seeking" to seeking.wire,
    "relationship" to relationshipStatus.wire, "lookingFor" to lookingFor.wire,
)

data class PhotoMeta(val sha256: String, val size: Int, val w: Int, val h: Int) {
    fun toWire(): Map<String, Any?> = linkedMapOf("sha256" to sha256, "size" to size, "w" to w, "h" to h)
}

enum class SwipeDirection(val wire: String) { RIGHT("right"), LEFT("left") }

sealed class P2pMessage {
    abstract val type: String
    abstract fun toWire(): Map<String, Any?>

    data class Hello(
        val pv: Int,
        val pmin: Int,
        val eid: String,
        val match: MatchProfile,
        val compat: WireCompat,
        /** Base64 friend tags / decoys. */
        val ft: List<String>,
    ) : P2pMessage() {
        override val type get() = "hello"
        override fun toWire() = linkedMapOf<String, Any?>(
            "t" to type, "pv" to pv, "pmin" to pmin, "eid" to eid, "match" to match.toWire(),
            "compat" to compat.toWire(), "ft" to ft,
        )
    }

    data class Card(val kind: MatchKind, val firstName: String, val bio: String?, val photo: PhotoMeta?) : P2pMessage() {
        override val type get() = "card"
        override fun toWire() = linkedMapOf<String, Any?>(
            "t" to type, "kind" to kind.wire, "firstName" to firstName, "bio" to bio, "photo" to photo?.toWire(),
        )
    }

    /** The JPEG travels as the body's blob. */
    data class Photo(val sha256: String) : P2pMessage() {
        override val type get() = "photo"
        override fun toWire() = linkedMapOf<String, Any?>("t" to type, "sha256" to sha256, "mime" to "image/jpeg")
    }

    data class Swipe(val dir: SwipeDirection) : P2pMessage() {
        override val type get() = "swipe"
        override fun toWire() = linkedMapOf<String, Any?>("t" to type, "dir" to dir.wire)
    }

    data class KeyX(val ik: String, val ek: String) : P2pMessage() {
        override val type get() = "keyx"
        val ikBytes: ByteArray get() = b64d(ik)
        val ekBytes: ByteArray get() = b64d(ek)
        override fun toWire() = linkedMapOf<String, Any?>("t" to type, "ik" to ik, "ek" to ek)

        companion object {
            fun of(ik: ByteArray, ek: ByteArray) = KeyX(b64(ik), b64(ek))
        }
    }

    data class Kc(val mac: String) : P2pMessage() {
        override val type get() = "kc"
        val macBytes: ByteArray get() = b64d(mac)
        override fun toWire() = linkedMapOf<String, Any?>("t" to type, "mac" to mac)
    }

    data class Chat(val ctr: Long, val ct: String) : P2pMessage() {
        override val type get() = "chat"
        val ctBytes: ByteArray get() = b64d(ct)
        override fun toWire() = linkedMapOf<String, Any?>("t" to type, "ctr" to ctr, "ct" to ct)
    }

    data class Ack(val id: String) : P2pMessage() {
        override val type get() = "ack"
        override fun toWire() = linkedMapOf<String, Any?>("t" to type, "id" to id)
    }

    data class Bye(val reason: String) : P2pMessage() {
        override val type get() = "bye"
        override fun toWire() = linkedMapOf<String, Any?>("t" to type, "reason" to reason)
    }

    data class Error(val code: String, val detail: String?) : P2pMessage() {
        override val type get() = "error"
        override fun toWire() = linkedMapOf<String, Any?>("t" to type, "code" to code, "detail" to detail)
    }

    /** A message type this version does not know; ignored by the link. */
    data class Unknown(override val type: String) : P2pMessage() {
        override fun toWire(): Map<String, Any?> = linkedMapOf("t" to type)
    }
}

/** Decrypted chat plaintext (section 5.3, `chat`). */
sealed class ChatPayload {
    abstract val id: String
    abstract val ts: Long
    abstract fun toWire(): Map<String, Any?>

    data class Text(override val id: String, override val ts: Long, val text: String) : ChatPayload() {
        override fun toWire() = linkedMapOf<String, Any?>("id" to id, "ts" to ts, "kind" to "text", "text" to text)
    }

    data class Read(override val id: String, override val ts: Long, val refs: List<String>) : ChatPayload() {
        override fun toWire() = linkedMapOf<String, Any?>("id" to id, "ts" to ts, "kind" to "read", "refs" to refs)
    }

    data class Unknown(override val id: String, override val ts: Long, val kind: String) : ChatPayload() {
        override fun toWire() = linkedMapOf<String, Any?>("id" to id, "ts" to ts, "kind" to kind)
    }
}

/** A decoded body: message + blob (non-empty only for `photo`). */
class DecodedMessage(val message: P2pMessage, val blob: ByteArray)

object P2pMessages {

    fun encode(message: P2pMessage, blob: ByteArray = FrameCodec.EMPTY): ByteArray =
        FrameCodec.packBody(WireJson.writeBytes(message.toWire()), blob)

    fun canonicalJson(message: P2pMessage): String = WireJson.write(message.toWire())

    fun decode(body: ByteArray): DecodedMessage {
        val (json, blob) = FrameCodec.unpackBody(body)
        val o = WireJson.parse(json) as? Map<*, *> ?: malformed("message is not an object")
        val t = o["t"] as? String ?: malformed("missing or non-string t")
        if (blob.isNotEmpty() && t != "photo") malformed("$t must not carry a blob")
        val msg: P2pMessage = when (t) {
            "hello" -> {
                val pv = int(o, "pv", 1, 255)
                val pmin = int(o, "pmin", 1, pv.toLong())
                val eid = hex(o, "eid", 32)
                val m = obj(o, "match")
                val match = MatchProfile(
                    gender = enumOf(m, "match.gender", "gender", Gender.entries, Gender::wire),
                    seeking = enumOf(m, "match.seeking", "seeking", Seeking.entries, Seeking::wire),
                    relationshipStatus = enumOf(m, "match.relationship", "relationship", RelationshipStatus.entries, RelationshipStatus::wire),
                    lookingFor = enumOf(m, "match.lookingFor", "lookingFor", LookingFor.entries, LookingFor::wire),
                )
                val c = obj(o, "compat")
                val compat = WireCompat(
                    sun = int(c, "sun", 0, 35_999), moon = int(c, "moon", 0, 35_999),
                    venus = int(c, "venus", 0, 35_999), mars = int(c, "mars", 0, 35_999),
                    asc = if (c["asc"] == null) null else int(c, "asc", 0, 35_999),
                    animal = enumOf(c, "compat.animal", "animal", ChineseAnimal.entries) { it.key },
                    stem = enumOf(c, "compat.stem", "stem", HeavenlyStem.entries) { it.name.lowercase() },
                    blood = if (c["blood"] == null) null else enumOf(c, "compat.blood", "blood", BloodType.entries) { it.key },
                    birthDate = date(c, "birthDate"),
                    birthHour = if (c["birthHour"] == null) null else int(c, "birthHour", 0, 23),
                )
                val ftRaw = o["ft"] as? List<*> ?: malformed("ft: expected a list")
                if (ftRaw.size > P2pConstants.FRIEND_TAGS_MAX) malformed("ft: more than 64 tags")
                val ft = ftRaw.map { tag ->
                    val s = tag as? String ?: malformed("ft[]: not a string")
                    b64Strict(s, P2pConstants.FRIEND_TAG_LEN, "ft[]")
                    s
                }
                P2pMessage.Hello(pv, pmin, eid, match, compat, ft)
            }
            "card" -> {
                val kind = enumOf(o, "kind", "kind", MatchKind.entries, MatchKind::wire)
                val name = str(o, "firstName", P2pConstants.MAX_FIRST_NAME_BYTES, 1)
                val bio = if (o["bio"] == null) null else str(o, "bio", P2pConstants.MAX_BIO_BYTES, 0)
                val photo = if (o["photo"] == null) null else {
                    val p = obj(o, "photo")
                    PhotoMeta(
                        sha256 = hex(p, "sha256", 64),
                        size = int(p, "size", 1, P2pConstants.MAX_BLOB_LEN.toLong()),
                        w = int(p, "w", 1, P2pConstants.MAX_PHOTO_SIDE.toLong()),
                        h = int(p, "h", 1, P2pConstants.MAX_PHOTO_SIDE.toLong()),
                    )
                }
                P2pMessage.Card(kind, name, bio, photo)
            }
            "photo" -> {
                val sha = hex(o, "sha256", 64)
                if (o["mime"] != "image/jpeg") malformed("mime must be image/jpeg")
                if (blob.isEmpty()) malformed("photo without blob")
                P2pMessage.Photo(sha)
            }
            "swipe" -> P2pMessage.Swipe(enumOf(o, "dir", "dir", SwipeDirection.entries, SwipeDirection::wire))
            "keyx" -> {
                val ik = o["ik"] as? String ?: malformed("ik missing")
                val ek = o["ek"] as? String ?: malformed("ek missing")
                for ((n, v) in listOf("ik" to ik, "ek" to ek)) {
                    if (b64Strict(v, 65, n)[0] != 0x04.toByte()) malformed("$n: not an uncompressed point")
                }
                P2pMessage.KeyX(ik, ek)
            }
            "kc" -> {
                val mac = o["mac"] as? String ?: malformed("mac missing")
                b64Strict(mac, 16, "mac")
                P2pMessage.Kc(mac)
            }
            "chat" -> {
                val ctr = long(o, "ctr", 0, 0xFFFF_FFFFL)
                val ct = o["ct"] as? String ?: malformed("ct missing")
                val n = b64Strict(ct, null, "ct").size
                if (n < 17 || n > 2 * P2pConstants.MAX_CHAT_TEXT_BYTES + 256) malformed("ct length")
                P2pMessage.Chat(ctr, ct)
            }
            "ack" -> P2pMessage.Ack(hex(o, "id", 32))
            "bye" -> P2pMessage.Bye(str(o, "reason", 32, 1))
            "error" -> P2pMessage.Error(str(o, "code", 32, 1), if (o["detail"] == null) null else str(o, "detail", 200, 0))
            else -> P2pMessage.Unknown(t)
        }
        return DecodedMessage(msg, blob)
    }

    fun encodeChatPayload(p: ChatPayload): ByteArray = WireJson.writeBytes(p.toWire())

    fun decodeChatPayload(plaintext: ByteArray): ChatPayload {
        val o = WireJson.parse(plaintext) as? Map<*, *> ?: malformed("chat plaintext is not an object")
        val id = hex(o, "id", 32)
        val ts = long(o, "ts", 0, 1L shl 53)
        return when (val kind = o["kind"]) {
            "text" -> ChatPayload.Text(id, ts, str(o, "text", P2pConstants.MAX_CHAT_TEXT_BYTES, 1))
            "read" -> {
                val refs = o["refs"] as? List<*> ?: malformed("refs: expected a list")
                if (refs.size !in 1..P2pConstants.MAX_READ_REFS) malformed("refs: 1..64 entries")
                ChatPayload.Read(id, ts, refs.map { r ->
                    (r as? String)?.takeIf { HEX32.matches(it) } ?: malformed("refs[]: bad id")
                })
            }
            is String -> ChatPayload.Unknown(id, ts, kind)
            else -> malformed("chat kind missing")
        }
    }

    // ----------------------------------------------------------------- validation helpers

    private val HEX32 = Regex("^[0-9a-f]{32}$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

    private fun obj(o: Map<*, *>, k: String): Map<*, *> = o[k] as? Map<*, *> ?: malformed("$k: expected an object")

    private fun long(o: Map<*, *>, k: String, lo: Long, hi: Long): Long {
        val v = o[k] as? Long ?: malformed("$k: expected an integer")
        if (v < lo || v > hi) malformed("$k: $v not in $lo..$hi")
        return v
    }

    private fun int(o: Map<*, *>, k: String, lo: Long, hi: Long): Int = long(o, k, lo, hi).toInt()

    private fun str(o: Map<*, *>, k: String, maxBytes: Int, minBytes: Int): String {
        val v = o[k] as? String ?: malformed("$k: expected a string")
        val n = v.toByteArray(Charsets.UTF_8).size
        if (n < minBytes || n > maxBytes) malformed("$k: $n bytes not in $minBytes..$maxBytes")
        return v
    }

    private fun hex(o: Map<*, *>, k: String, len: Int): String {
        val v = o[k] as? String ?: malformed("$k: expected a hex string")
        if (!(if (len == 32) HEX32 else HEX64).matches(v)) malformed("$k: bad hex")
        return v
    }

    private fun date(o: Map<*, *>, k: String): LocalDate {
        val v = o[k] as? String ?: malformed("$k: expected a date")
        val m = DATE.matchEntire(v) ?: malformed("$k: expected YYYY-MM-DD")
        val (y, mo, d) = m.destructured
        if (y.toInt() < 1) malformed("$k: year 0000")
        return try { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) } catch (e: DateTimeException) { malformed("$k: invalid date") }
    }

    private fun <E> enumOf(o: Map<*, *>, what: String, k: String, entries: List<E>, wire: (E) -> String): E {
        val v = o[k] as? String ?: malformed("$what: expected a string")
        return entries.firstOrNull { wire(it) == v } ?: malformed("$what: '$v' is not allowed")
    }
}

private val B64E: Base64.Encoder = Base64.getEncoder()
private val B64D: Base64.Decoder = Base64.getDecoder()

internal fun b64(b: ByteArray): String = B64E.encodeToString(b)

internal fun b64d(s: String): ByteArray = B64D.decode(s)

/** Strict base64: standard alphabet, canonical padding; optional exact length. */
internal fun b64Strict(s: String, len: Int?, what: String): ByteArray {
    val raw = try { B64D.decode(s) } catch (e: IllegalArgumentException) { malformed("$what: bad base64") }
    if (b64(raw) != s) malformed("$what: non-canonical base64")
    if (len != null && raw.size != len) malformed("$what: expected $len bytes, got ${raw.size}")
    return raw
}

internal fun hexOf(b: ByteArray): String = buildString(b.size * 2) {
    for (x in b) { append("0123456789abcdef"[(x.toInt() shr 4) and 15]); append("0123456789abcdef"[x.toInt() and 15]) }
}

internal fun unhex(s: String): ByteArray {
    require(s.length % 2 == 0) { "odd hex length" }
    return ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
