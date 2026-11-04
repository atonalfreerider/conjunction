package com.metavirtuoso.conjunction.core.p2p

import com.metavirtuoso.conjunction.core.TestData
import com.metavirtuoso.conjunction.core.json.Json
import com.metavirtuoso.conjunction.core.match.MatchProfile
import java.time.LocalDate

internal object Wire {
    val root: Map<String, Any?> by lazy { TestData.json("p2p_wire.json") }

    fun obj(v: Any?) = Json.obj(v)
    fun arr(v: Any?) = Json.arr(v)
    fun str(m: Map<String, Any?>, k: String) = m[k] as String

    /** messages[] entry by name. */
    fun message(name: String): Map<String, Any?> = arr(root["messages"]).map(::obj).first { it["name"] == name }

    fun hello(name: String): P2pMessage.Hello =
        P2pMessages.decode(unhex(str(message(name), "bodyHex"))).message as P2pMessage.Hello

    fun key(name: String): SoftwareP256Key {
        val k = obj(obj(obj(root["crypto"])["keys"])[name])
        return SoftwareP256Key.fromScalar(java.math.BigInteger(str(k, "d"), 16))
    }
}

internal class TestPolicy(
    var friendList: List<FriendRecord> = emptyList(),
    var hidden: Set<String> = emptySet(),
    var preset: Map<String, SwipeDirection> = emptyMap(),
    val day: LocalDate = LocalDate.of(2026, 11, 3),
) : LinkPolicy {
    override fun friends() = friendList
    override fun isHidden(fingerprint: String) = fingerprint in hidden
    override fun presetSwipe(fingerprint: String) = preset[fingerprint]
    override fun today() = day
}

internal fun profileOf(hello: P2pMessage.Hello, firstName: String, bio: String? = null, photo: ByteArray? = null, eid: String = hello.eid) =
    LocalP2pProfile(eid, hello.match, hello.compat, firstName, bio, photo, if (photo != null) 480 else 0, if (photo != null) 640 else 0)

internal fun withMatch(p: LocalP2pProfile, m: MatchProfile) =
    LocalP2pProfile(p.eid, m, p.compat, p.firstName, p.bio, p.photo, p.photoW, p.photoH)

/** Moves frames both ways until quiet, asserting frame sizes. */
internal fun pump(a: P2pLink, b: P2pLink, now: Long = 1_000, tamper: ((ByteArray, Boolean) -> ByteArray)? = null) {
    repeat(10_000) {
        val fa = a.takeFrames()
        val fb = b.takeFrames()
        if (fa.isEmpty() && fb.isEmpty()) return
        for (f in fa) {
            check(f.size <= a.frameLen) { "frame ${f.size} > ${a.frameLen}" }
            b.onFrame(tamper?.invoke(f, true) ?: f, now)
        }
        for (f in fb) {
            check(f.size <= b.frameLen)
            a.onFrame(tamper?.invoke(f, false) ?: f, now)
        }
    }
    error("pump did not settle")
}
