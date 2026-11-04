import Foundation
import CryptoKit
import Security
import ConjunctionCore

/// The long-term Nearby identity key (docs/P2P_PROTOCOL.md 8.1), generated on first use.
///
/// On a device with a Secure Enclave the private key is a `SecureEnclave.P256.KeyAgreement` key
/// that never leaves the enclave; the Keychain holds only its wrapped blob. Elsewhere (simulator)
/// it is a software key whose 32-byte scalar is the Keychain item. Either way the item is
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: usable by Bluetooth work in the background
/// after the first unlock, never backed up, never synced. "Delete all my data" deletes it.
enum IdentityKeychain {
    private static let service = "com.metavirtuoso.conjunction.identity"
    private static let account = "p2p-identity-v1"
    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: (any P256Agreement)?

    /// The identity key, created and stored on first use.
    static func identity() -> any P256Agreement {
        lock.lock()
        defer { lock.unlock() }
        if let cached { return cached }
        let key = load() ?? create()
        cached = key
        return key
    }

    /// Deletes the identity (and with it every friendship's ability to recognise this phone).
    static func delete() {
        lock.lock()
        defer { lock.unlock() }
        cached = nil
        SecItemDelete(baseQuery() as CFDictionary)
    }

    private static func baseQuery() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    private static func load() -> (any P256Agreement)? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data, let kind = data.first else {
            return nil
        }
        let body = data.dropFirst()
        switch kind {
        case 1:
            return try? EnclaveIdentity(SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: body))
        case 2:
            return try? SoftwareP256Key(rawRepresentation: [UInt8](body))
        default:
            return nil
        }
    }

    private static func create() -> any P256Agreement {
        var stored: Data
        let key: any P256Agreement
        if SecureEnclave.isAvailable,
           let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, .privateKeyUsage, nil),
           let enclave = try? SecureEnclave.P256.KeyAgreement.PrivateKey(accessControl: access) {
            key = EnclaveIdentity(enclave)
            stored = Data([1]) + enclave.dataRepresentation
        } else {
            let soft = SoftwareP256Key.generate()
            key = soft
            stored = Data([2]) + Data(soft.rawRepresentation)
        }
        var add = baseQuery()
        add[kSecValueData as String] = stored
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemDelete(baseQuery() as CFDictionary)
        let status = SecItemAdd(add as CFDictionary, nil)
        if status != errSecSuccess {
            // Not storable (should not happen): the key still works for this run; a new one is made next time.
            NSLog("Conjunction: identity key not stored (\(status))")
        }
        stored.resetBytes(in: 0..<stored.count)
        return key
    }
}

/// A Secure Enclave key adapted to ConjunctionCore's `P256Agreement`.
struct EnclaveIdentity: P256Agreement, @unchecked Sendable {
    let key: SecureEnclave.P256.KeyAgreement.PrivateKey
    let publicKey: [UInt8]

    init(_ key: SecureEnclave.P256.KeyAgreement.PrivateKey) {
        self.key = key
        publicKey = [UInt8](key.publicKey.x963Representation)
    }

    func agree(_ peerPublic: [UInt8]) throws -> [UInt8] {
        do {
            let peer = try P256.KeyAgreement.PublicKey(x963Representation: peerPublic)
            let secret = try key.sharedSecretFromKeyAgreement(with: peer)
            return secret.withUnsafeBytes { [UInt8]($0) }
        } catch {
            throw P2PCryptoError("ECDH failed")
        }
    }
}
