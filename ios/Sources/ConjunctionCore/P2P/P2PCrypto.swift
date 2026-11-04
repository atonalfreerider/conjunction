import Crypto
import Foundation

/// A P-256 key that can do ECDH. The iOS app backs the long-term identity key with
/// `SecureEnclave.P256.KeyAgreement.PrivateKey` where available, otherwise a `SoftwareP256Key`
/// whose raw representation is kept in the Keychain (kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly).
/// Link-ephemeral keys are always `SoftwareP256Key`.
public protocol P256Agreement: Sendable {
    /// X9.63 uncompressed public key, 65 bytes.
    var publicKey: [UInt8] { get }

    /// ECDH with [peerPublic] (65 bytes): the 32-byte X coordinate of the shared point.
    func agree(_ peerPublic: [UInt8]) throws -> [UInt8]
}

/// A CryptoKit / swift-crypto software key.
public struct SoftwareP256Key: P256Agreement {
    public let privateKey: P256.KeyAgreement.PrivateKey
    public let publicKey: [UInt8]

    public init(_ privateKey: P256.KeyAgreement.PrivateKey) {
        self.privateKey = privateKey
        self.publicKey = [UInt8](privateKey.publicKey.x963Representation)
    }

    /// A fresh random key.
    public static func generate() -> SoftwareP256Key { SoftwareP256Key(P256.KeyAgreement.PrivateKey()) }

    /// From the 32-byte big-endian private scalar (Keychain storage, test vectors).
    public init(rawRepresentation: [UInt8]) throws {
        do {
            self.init(try P256.KeyAgreement.PrivateKey(rawRepresentation: rawRepresentation))
        } catch {
            throw P2PCryptoError("invalid P-256 private scalar")
        }
    }

    /// The 32-byte private scalar, for storing in the Keychain.
    public var rawRepresentation: [UInt8] { [UInt8](privateKey.rawRepresentation) }

    public func agree(_ peerPublic: [UInt8]) throws -> [UInt8] {
        let peer = try P2PCrypto.publicKey(peerPublic)
        do {
            let secret = try privateKey.sharedSecretFromKeyAgreement(with: peer)
            return secret.withUnsafeBytes { [UInt8]($0) }
        } catch {
            throw P2PCryptoError("ECDH failed")
        }
    }
}

/// Session keys of one link (section 8.3).
public struct P2PSession: Sendable {
    /// True when this side is L (smaller identity key).
    public let isL: Bool
    public let sendKey: [UInt8]
    public let recvKey: [UInt8]
    public let confKey: [UInt8]
    public let transcriptSalt: [UInt8]
    public let myKc: [UInt8]
    public let peerKc: [UInt8]
}

/// Section 8 of docs/P2P_PROTOCOL.md.
public enum P2PCrypto {
    public static let infoSession = Array("conjunction/v1/session".utf8)
    public static let infoFriend = Array("conjunction/v1/friend-tag".utf8)
    public static let labelKeyx = Array("CJ1-KEYX".utf8)
    public static let labelFriend = Array("CJ1-FRIEND".utf8)
    public static let labelTag = Array("CJ1-TAG".utf8)
    public static let labelKc = Array("CJ1-KC".utf8)
    public static let chatAAD = Array("CJ1-CHAT".utf8)
    public static let labelSafety = Array("CJ1-SAFETY".utf8)
    public static let labelFingerprint = Array("CJ1-FP".utf8)
    public static let roleL: UInt8 = 0x4C
    public static let roleH: UInt8 = 0x48

    // ------------------------------------------------------------------ primitives

    public static func sha256(_ parts: [UInt8]...) -> [UInt8] {
        var h = SHA256()
        for p in parts { h.update(data: p) }
        return Array(h.finalize())
    }

    public static func hmacSHA256(key: [UInt8], _ parts: [UInt8]...) -> [UInt8] { hmac(key, parts) }

    private static func hmac(_ key: [UInt8], _ parts: [[UInt8]]) -> [UInt8] {
        var h = HMAC<SHA256>(key: SymmetricKey(data: key))
        for p in parts { h.update(data: p) }
        return Array(h.finalize())
    }

    /// RFC 5869 HKDF-SHA256. An empty salt means 32 zero bytes.
    public static func hkdf(ikm: [UInt8], salt: [UInt8], info: [UInt8], length: Int) -> [UInt8] {
        precondition((1...255 * 32).contains(length), "HKDF length out of range")
        let prk = hmac(salt.isEmpty ? [UInt8](repeating: 0, count: 32) : salt, [ikm])
        var out: [UInt8] = []
        var t: [UInt8] = []
        var i: UInt8 = 1
        while out.count < length {
            t = hmac(prk, [t, info, [i]])
            out += t
            i &+= 1
        }
        return Array(out.prefix(length))
    }

    public static func nonce(_ ctr: Int64) -> [UInt8] {
        precondition((0...0xFFFF_FFFF).contains(ctr), "chat counter out of range")
        var n = [UInt8](repeating: 0, count: 12)
        for i in 0..<8 { n[4 + i] = UInt8(truncatingIfNeeded: UInt64(ctr) >> (56 - 8 * UInt64(i))) }
        return n
    }

    /// AES-256-GCM with the counter nonce and AAD "CJ1-CHAT": ciphertext || 16-byte tag.
    public static func seal(key: [UInt8], ctr: Int64, plaintext: [UInt8]) throws -> [UInt8] {
        do {
            let box = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key),
                                       nonce: try AES.GCM.Nonce(data: nonce(ctr)), authenticating: chatAAD)
            return [UInt8](box.ciphertext) + [UInt8](box.tag)
        } catch {
            throw P2PCryptoError("AES-GCM encryption failed")
        }
    }

    public static func open(key: [UInt8], ctr: Int64, ciphertext: [UInt8]) throws -> [UInt8] {
        guard ciphertext.count >= 16 else { throw P2PCryptoError("AES-GCM authentication failed") }
        do {
            let box = try AES.GCM.SealedBox(nonce: try AES.GCM.Nonce(data: nonce(ctr)),
                                            ciphertext: ciphertext.dropLast(16), tag: ciphertext.suffix(16))
            return [UInt8](try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: chatAAD))
        } catch {
            throw P2PCryptoError("AES-GCM authentication failed")
        }
    }

    public static func constantTimeEquals(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        guard a.count == b.count else { return false }
        var d: UInt8 = 0
        for i in 0..<a.count { d |= a[i] ^ b[i] }
        return d == 0
    }

    // ------------------------------------------------------------------ points

    /// Parses and validates a 65-byte uncompressed P-256 point (on the curve, coordinates < p).
    public static func publicKey(_ pub: [UInt8]) throws -> P256.KeyAgreement.PublicKey {
        guard pub.count == 65, pub[0] == 0x04 else { throw P2PCryptoError("point must be 65-byte uncompressed") }
        do {
            return try P256.KeyAgreement.PublicKey(x963Representation: pub)
        } catch {
            throw P2PCryptoError("point not on P-256")
        }
    }

    public static func validatePoint(_ pub: [UInt8]) throws { _ = try publicKey(pub) }

    // ------------------------------------------------------------------ protocol constructions

    /// Unsigned lexicographic compare of byte strings: negative, zero or positive.
    public static func compareUnsigned(_ a: [UInt8], _ b: [UInt8]) -> Int {
        for i in 0..<min(a.count, b.count) where a[i] != b[i] { return Int(a[i]) - Int(b[i]) }
        return a.count - b.count
    }

    /// (ik_L, ik_H, self is L).
    public static func orderIdentities(mine: [UInt8], peer: [UInt8]) throws -> (l: [UInt8], h: [UInt8], selfIsL: Bool) {
        let c = compareUnsigned(mine, peer)
        if c == 0 { throw P2PCryptoError("peer identity key equals ours") }
        return c < 0 ? (mine, peer, true) : (peer, mine, false)
    }

    public static func kcMac(confKey: [UInt8], role: UInt8) -> [UInt8] {
        Array(hmacSHA256(key: confKey, labelKc, [role]).prefix(16))
    }

    public static func deriveSession(identity: any P256Agreement, ephemeral: any P256Agreement,
                                     peerIk: [UInt8], peerEk: [UInt8]) throws -> P2PSession {
        try validatePoint(peerIk)
        try validatePoint(peerEk)
        if peerEk == ephemeral.publicKey { throw P2PCryptoError("peer ephemeral key equals ours") }
        let (ikL, ikH, isL) = try orderIdentities(mine: identity.publicKey, peer: peerIk)
        let ekL = isL ? ephemeral.publicKey : peerEk
        let ekH = isL ? peerEk : ephemeral.publicKey
        let ss = try identity.agree(peerIk)
        let ee = try ephemeral.agree(peerEk)
        let salt = sha256(labelKeyx, ikL, ikH, ekL, ekH)
        let okm = hkdf(ikm: ee + ss, salt: salt, info: infoSession, length: 96)
        let kLH = Array(okm[0..<32])
        let kHL = Array(okm[32..<64])
        let kConf = Array(okm[64..<96])
        return P2PSession(isL: isL, sendKey: isL ? kLH : kHL, recvKey: isL ? kHL : kLH, confKey: kConf,
                          transcriptSalt: salt,
                          myKc: kcMac(confKey: kConf, role: isL ? roleL : roleH),
                          peerKc: kcMac(confKey: kConf, role: isL ? roleH : roleL))
    }

    public static func friendSecret(identity: any P256Agreement, peerIk: [UInt8]) throws -> [UInt8] {
        try validatePoint(peerIk)
        let (ikL, ikH, _) = try orderIdentities(mine: identity.publicKey, peer: peerIk)
        return hkdf(ikm: try identity.agree(peerIk), salt: sha256(labelFriend, ikL, ikH), info: infoFriend, length: 32)
    }

    /// HMAC-SHA256(fs, "CJ1-TAG" || eid bytes)[0..8). [eidHex] must be a valid eid (32 hex chars).
    public static func friendTag(friendSecret: [UInt8], eidHex: String) -> [UInt8] {
        let eid = P2PBytes.unhex(eidHex) ?? []
        return Array(hmacSHA256(key: friendSecret, labelTag, eid).prefix(P2PConstants.friendTagLen))
    }

    /// Six groups of five digits, the same on both phones.
    public static func safetyCode(_ ikA: [UInt8], _ ikB: [UInt8]) -> String {
        let (l, h) = compareUnsigned(ikA, ikB) < 0 ? (ikA, ikB) : (ikB, ikA)
        let d = sha256(labelSafety, l, h)
        return (0..<6).map { i -> String in
            var v: UInt64 = 0
            for b in d[(5 * i)..<(5 * i + 5)] { v = v << 8 | UInt64(b) }
            let s = String(v % 100_000)
            return String(repeating: "0", count: 5 - s.count) + s
        }.joined(separator: " ")
    }

    /// Local-only stranger fingerprint (section 7.4) of a `hello.compat`.
    public static func profileFingerprint(_ compat: WireCompat) -> String {
        P2PBytes.hex(sha256(labelFingerprint, WireJSON.write(.object(compat.wire))).prefix(16))
    }
}
