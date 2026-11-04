package com.metavirtuoso.conjunction.core.p2p

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A P-256 key that can do ECDH. The Android app implements this over Android Keystore
 * (PURPOSE_AGREE_KEY, API 31+) or a Keystore-wrapped software key (API 26-30); tests and the
 * link-ephemeral keys use [SoftwareP256Key].
 */
interface P256Agreement {
    /** X9.63 uncompressed public key, 65 bytes. */
    val publicKey: ByteArray

    /** ECDH with [peerPublic] (65 bytes, validated by the caller): 32-byte X coordinate. */
    fun agree(peerPublic: ByteArray): ByteArray
}

/** JCA software key (works on the JVM and on Android API 26+, no provider assumptions). */
class SoftwareP256Key private constructor(private val privateKey: PrivateKey, override val publicKey: ByteArray) : P256Agreement {

    override fun agree(peerPublic: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(P2pCrypto.publicKeyFromX963(peerPublic), true)
        val s = ka.generateSecret()
        // Providers return the 32-byte X coordinate; normalise defensively.
        return if (s.size == 32) s else P2pCrypto.fixed32(BigInteger(1, s))
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): SoftwareP256Key {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"), random)
            val kp = g.generateKeyPair()
            return SoftwareP256Key(kp.private, P2pCrypto.x963((kp.public as ECPublicKey).w))
        }

        /** Deterministic key from a private scalar (test vectors only). */
        fun fromScalar(d: BigInteger): SoftwareP256Key {
            require(d.signum() > 0 && d < P2pCrypto.N) { "scalar out of range" }
            val priv = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, P2pCrypto.params))
            return SoftwareP256Key(priv, P2pCrypto.x963(P2pCrypto.multiplyG(d)))
        }
    }
}

class P2pCryptoException(message: String) : Exception(message)

/** Session keys of one link (section 8.3). */
class P2pSession(
    /** True when this side is L (smaller identity key). */
    val isL: Boolean,
    val sendKey: ByteArray,
    val recvKey: ByteArray,
    val confKey: ByteArray,
    val transcriptSalt: ByteArray,
    val myKc: ByteArray,
    val peerKc: ByteArray,
)

/** Section 8 of docs/P2P_PROTOCOL.md. */
object P2pCrypto {
    val INFO_SESSION = "conjunction/v1/session".toByteArray()
    val INFO_FRIEND = "conjunction/v1/friend-tag".toByteArray()
    val LBL_KEYX = "CJ1-KEYX".toByteArray()
    val LBL_FRIEND = "CJ1-FRIEND".toByteArray()
    val LBL_TAG = "CJ1-TAG".toByteArray()
    val LBL_KC = "CJ1-KC".toByteArray()
    val CHAT_AAD = "CJ1-CHAT".toByteArray()
    val LBL_SAFETY = "CJ1-SAFETY".toByteArray()
    val LBL_FP = "CJ1-FP".toByteArray()
    const val ROLE_L: Byte = 0x4C
    const val ROLE_H: Byte = 0x48

    // P-256 domain parameters (SEC 2 / FIPS 186-4).
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val A = P.subtract(BigInteger.valueOf(3))
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    internal val N = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val G = ECPoint(
        BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16),
        BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16),
    )

    val params: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    // ------------------------------------------------------------------ primitives

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { update(it) }
        doFinal()
    }

    /** RFC 5869 HKDF-SHA256. An empty salt means 32 zero bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmacSha256(prk, t, info, byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    fun nonce(ctr: Long): ByteArray {
        require(ctr in 0..0xFFFF_FFFFL)
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = (ctr ushr (56 - 8 * i)).toByte()
        return n
    }

    fun seal(key: ByteArray, ctr: Long, plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(ctr)))
        c.updateAAD(CHAT_AAD)
        return c.doFinal(plaintext)
    }

    fun open(key: ByteArray, ctr: Long, ciphertext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(ctr)))
        c.updateAAD(CHAT_AAD)
        return try {
            c.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw P2pCryptoException("AES-GCM authentication failed")
        } catch (e: javax.crypto.BadPaddingException) {
            throw P2pCryptoException("AES-GCM authentication failed")
        }
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ------------------------------------------------------------------ points

    fun x963(w: ECPoint): ByteArray = byteArrayOf(0x04) + fixed32(w.affineX) + fixed32(w.affineY)

    internal fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size == 33 && b[0] == 0.toByte() -> b.copyOfRange(1, 33)
            b.size < 32 -> ByteArray(32 - b.size) + b
            else -> throw IllegalArgumentException("value does not fit 32 bytes")
        }
    }

    /** Validates a 65-byte uncompressed point on P-256 (not the identity); throws [P2pCryptoException]. */
    fun validatePoint(pub: ByteArray): ECPoint {
        if (pub.size != 65 || pub[0] != 0x04.toByte()) throw P2pCryptoException("point must be 65-byte uncompressed")
        val x = BigInteger(1, pub.copyOfRange(1, 33))
        val y = BigInteger(1, pub.copyOfRange(33, 65))
        if (x >= P || y >= P) throw P2pCryptoException("point coordinate out of range")
        val lhs = y.multiply(y).mod(P)
        val rhs = x.multiply(x).multiply(x).add(A.multiply(x)).add(B).mod(P)
        if (lhs != rhs) throw P2pCryptoException("point not on P-256")
        return ECPoint(x, y)
    }

    fun publicKeyFromX963(pub: ByteArray): ECPublicKey =
        KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(validatePoint(pub), params)) as ECPublicKey

    /** d·G with affine double-and-add (only for deterministic test keys; not constant time). */
    internal fun multiplyG(d: BigInteger): ECPoint {
        var r: ECPoint = ECPoint.POINT_INFINITY
        var q = G
        for (i in 0 until d.bitLength()) {
            if (d.testBit(i)) r = add(r, q)
            q = add(q, q)
        }
        return r
    }

    private fun add(p1: ECPoint, p2: ECPoint): ECPoint {
        if (p1 == ECPoint.POINT_INFINITY) return p2
        if (p2 == ECPoint.POINT_INFINITY) return p1
        val (x1, y1) = p1.affineX to p1.affineY
        val (x2, y2) = p2.affineX to p2.affineY
        val l = if (x1 == x2) {
            if (y1.add(y2).mod(P).signum() == 0) return ECPoint.POINT_INFINITY
            x1.multiply(x1).multiply(BigInteger.valueOf(3)).add(A).multiply(y1.shiftLeft(1).modInverse(P)).mod(P)
        } else {
            y2.subtract(y1).multiply(x2.subtract(x1).modInverse(P)).mod(P)
        }
        val x3 = l.multiply(l).subtract(x1).subtract(x2).mod(P)
        val y3 = l.multiply(x1.subtract(x3)).subtract(y1).mod(P)
        return ECPoint(x3, y3)
    }

    // ------------------------------------------------------------------ protocol constructions

    /** Unsigned lexicographic compare of byte strings. */
    fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /** (ik_L, ik_H, self is L). */
    fun orderIdentities(mine: ByteArray, peer: ByteArray): Triple<ByteArray, ByteArray, Boolean> {
        val c = compareUnsigned(mine, peer)
        if (c == 0) throw P2pCryptoException("peer identity key equals ours")
        return if (c < 0) Triple(mine, peer, true) else Triple(peer, mine, false)
    }

    fun kcMac(confKey: ByteArray, role: Byte): ByteArray = hmacSha256(confKey, LBL_KC, byteArrayOf(role)).copyOf(16)

    fun deriveSession(identity: P256Agreement, ephemeral: P256Agreement, peerIk: ByteArray, peerEk: ByteArray): P2pSession {
        validatePoint(peerIk)
        validatePoint(peerEk)
        if (peerEk.contentEquals(ephemeral.publicKey)) throw P2pCryptoException("peer ephemeral key equals ours")
        val (ikL, ikH, isL) = orderIdentities(identity.publicKey, peerIk)
        val ekL = if (isL) ephemeral.publicKey else peerEk
        val ekH = if (isL) peerEk else ephemeral.publicKey
        val ss = identity.agree(peerIk)
        val ee = ephemeral.agree(peerEk)
        val salt = sha256(LBL_KEYX, ikL, ikH, ekL, ekH)
        val okm = hkdf(ee + ss, salt, INFO_SESSION, 96)
        val kLH = okm.copyOfRange(0, 32)
        val kHL = okm.copyOfRange(32, 64)
        val kConf = okm.copyOfRange(64, 96)
        return P2pSession(
            isL = isL,
            sendKey = if (isL) kLH else kHL,
            recvKey = if (isL) kHL else kLH,
            confKey = kConf,
            transcriptSalt = salt,
            myKc = kcMac(kConf, if (isL) ROLE_L else ROLE_H),
            peerKc = kcMac(kConf, if (isL) ROLE_H else ROLE_L),
        )
    }

    fun friendSecret(identity: P256Agreement, peerIk: ByteArray): ByteArray {
        validatePoint(peerIk)
        val (ikL, ikH, _) = orderIdentities(identity.publicKey, peerIk)
        return hkdf(identity.agree(peerIk), sha256(LBL_FRIEND, ikL, ikH), INFO_FRIEND, 32)
    }

    fun friendTag(friendSecret: ByteArray, eidHex: String): ByteArray =
        hmacSha256(friendSecret, LBL_TAG, unhex(eidHex)).copyOf(P2pConstants.FRIEND_TAG_LEN)

    fun safetyCode(ikA: ByteArray, ikB: ByteArray): String {
        val (l, h) = if (compareUnsigned(ikA, ikB) < 0) ikA to ikB else ikB to ikA
        val d = sha256(LBL_SAFETY, l, h)
        return (0 until 6).joinToString(" ") { i ->
            val v = BigInteger(1, d.copyOfRange(5 * i, 5 * i + 5)).mod(BigInteger.valueOf(100_000)).toInt()
            v.toString().padStart(5, '0')
        }
    }

    /** Local-only stranger fingerprint (section 7.4) of a `hello.compat`. */
    fun profileFingerprint(compat: WireCompat): String =
        hexOf(sha256(LBL_FP, WireJson.writeBytes(compat.toWire())).copyOf(16))
}
