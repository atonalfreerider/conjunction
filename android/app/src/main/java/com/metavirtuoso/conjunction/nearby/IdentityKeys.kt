package com.metavirtuoso.conjunction.nearby

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import com.metavirtuoso.conjunction.core.p2p.P256Agreement
import com.metavirtuoso.conjunction.core.p2p.P2pCrypto
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * The long-term identity key (docs/P2P_PROTOCOL.md 8.1): a P-256 key pair generated in Android
 * Keystore on first use with PURPOSE_AGREE_KEY (API 31+, the same minimum as Nearby itself), so the
 * private key never leaves the Keystore (hardware-backed where the phone has it). Deleting the app's
 * data deletes the key and with it every friendship.
 */
@RequiresApi(Build.VERSION_CODES.S)
class KeystoreIdentity private constructor(private val privateKey: PrivateKey, override val publicKey: ByteArray) : P256Agreement {

    override fun agree(peerPublic: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH", KEYSTORE)
        ka.init(privateKey)
        ka.doPhase(P2pCrypto.publicKeyFromX963(peerPublic), true)
        // JCA ECDH returns the 32-byte big-endian X coordinate (section 8).
        return ka.generateSecret().also { check(it.size == 32) { "unexpected ECDH secret length ${it.size}" } }
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "conjunction_identity_v1"

        /** Loads the key under [alias], creating it on first use. Call off the main thread. */
        fun loadOrCreate(alias: String = DEFAULT_ALIAS): KeystoreIdentity {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val entry = ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            if (entry != null) {
                return KeystoreIdentity(entry.privateKey, P2pCrypto.x963((entry.certificate.publicKey as ECPublicKey).w))
            }
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            gen.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .build(),
            )
            val pair = gen.generateKeyPair()
            return KeystoreIdentity(pair.private, P2pCrypto.x963((pair.public as ECPublicKey).w))
        }

        /** Deletes the identity (Delete all my data). */
        fun delete(alias: String = DEFAULT_ALIAS) {
            runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias) }
        }
    }
}
