import Foundation

/// Constants of the Conjunction peer-to-peer protocol (docs/P2P_PROTOCOL.md sections 2, 4, 10).
/// Keep identical to android/core .../p2p/P2pConstants.kt and tools/p2p-sim/conjunction_p2p/consts.py.
public enum P2PConstants {
    /// UUIDs, uppercase as CoreBluetooth prints them (they compare case-insensitively).
    public static let serviceUUID = "7C0A1E5F-6A3B-4E2D-9F11-C0471C710001"
    /// Write (with response) only; the central sends frames.
    public static let inboxUUID = "7C0A1E5F-6A3B-4E2D-9F11-C0471C710002"
    /// Notify only; the peripheral sends frames.
    public static let outboxUUID = "7C0A1E5F-6A3B-4E2D-9F11-C0471C710003"
    public static let cccdUUID = "00002902-0000-1000-8000-00805F9B34FB"

    public static let protocolVersion = 1
    public static let protocolMinVersion = 1

    public static let frameVersion: UInt8 = 0x01
    public static let frameHeaderLen = 7
    public static let defaultAttMtu = 23
    public static let requestedAttMtu = 517
    public static let maxFrameLen = 512
    public static let minFrameLen = 20

    public static let maxJsonLen = 16_384
    public static let maxBlobLen = 61_440
    public static let maxBodyLen = 2 + maxJsonLen + maxBlobLen
    public static let maxConcurrentReassemblies = 4
    public static let reassemblyTimeoutMs: Int64 = 15_000
    public static let helloTimeoutMs: Int64 = 10_000
    public static let keyxTimeoutMs: Int64 = 30_000
    public static let matchLinkTimeoutMs: Int64 = 600_000
    /// Photos are sent only when frameLen >= this (ATT_MTU >= 185).
    public static let photoMinFrameLen = 182

    public static let maxFirstNameBytes = 64
    public static let maxBioBytes = 280
    public static let maxChatTextBytes = 4000
    public static let maxReadRefs = 64
    public static let maxPhotoSide = 640

    public static let friendTagLen = 8
    public static let friendTagsMax = 64
    public static let friendTagsRealMax = 56
    public static let friendTagsBlock = 8

    /// Inbound token buckets: capacity and refill per second (section 10.3).
    public static let rateFramesCap = 300, rateFramesPerSecond = 150
    public static let rateMessagesCap = 30, rateMessagesPerSecond = 5
    public static let rateChatsCap = 10, rateChatsPerSecond = 1

    public static let ephemeralIdBytes = 16
    public static let ephemeralIdRotationMs: Int64 = 15 * 60 * 1000

    /// Proximity gate (SPEC 5c): RSSI >= this in >= `proximityMinSightings` scans within `proximityWindowMs`.
    public static let proximityRssiDbm = -80
    public static let proximityMinSightings = 2
    public static let proximityWindowMs: Int64 = 30_000
    public static let maxOpenLinks = 4
    public static let reconnectBackoffMs: Int64 = 120_000

    /// Largest frame for a negotiated ATT_MTU: min(ATT_MTU - 3, 512), at least 20.
    public static func maxFrameLen(attMtu: Int) -> Int {
        Swift.max(minFrameLen, Swift.min(maxFrameLen, attMtu - 3))
    }
}

/// Error codes of the `error` message (section 5.3).
public enum P2PErrorCode: String, CaseIterable, Sendable {
    case version, malformed
    case frameVersion = "frame_version"
    case state, rate
    case tooLarge = "too_large"
    case crypto, `internal`

    public var wire: String { rawValue }
}

/// A protocol violation detected while handling received bytes.
public struct P2PError: Error, Equatable, CustomStringConvertible, Sendable {
    public let code: P2PErrorCode
    public let detail: String

    public init(_ code: P2PErrorCode, _ detail: String) {
        self.code = code
        self.detail = detail
    }

    public var description: String { "\(code.wire): \(detail)" }

    static func malformed(_ detail: String) -> P2PError { P2PError(.malformed, detail) }
}

/// A cryptographic failure (bad point, failed authentication, key mismatch); a link reports it as `error crypto`.
public struct P2PCryptoError: Error, Equatable, CustomStringConvertible, Sendable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

/// Misuse of the link or profile API by the app (Kotlin's require/check failures).
public struct P2PUsageError: Error, Equatable, CustomStringConvertible, Sendable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}
