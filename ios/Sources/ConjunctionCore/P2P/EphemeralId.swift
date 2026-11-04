import Foundation

/// Cryptographically secure random bytes. `SystemRandomNumberGenerator` is the platform CSPRNG
/// (arc4random_buf on Apple platforms, getrandom(2) on Linux).
public enum P2PRandom {
    public static func bytes(_ n: Int) -> [UInt8] {
        var g = SystemRandomNumberGenerator()
        return (0..<n).map { _ in UInt8.random(in: .min ... .max, using: &g) }
    }
}

/// Ephemeral id (section 7.1): 16 CSPRNG bytes as 32 lowercase hex chars, redrawn every 15 min and
/// on every Nearby toggle. `current(nowMs:)` returns the id for a link opened at [nowMs]. Not
/// thread-safe; keep it on the Bluetooth queue.
public final class EphemeralIdSource {
    private var id: String
    private var drawnAtMs: Int64?

    public init() { id = EphemeralIdSource.draw() }

    public func current(nowMs: Int64) -> String {
        guard let drawn = drawnAtMs else {
            drawnAtMs = nowMs
            return id
        }
        if nowMs - drawn >= P2PConstants.ephemeralIdRotationMs || nowMs < drawn { rotate(nowMs: nowMs) }
        return id
    }

    /// Call when Nearby is switched on.
    public func rotate(nowMs: Int64) {
        id = EphemeralIdSource.draw()
        drawnAtMs = nowMs
    }

    private static func draw() -> String { P2PBytes.hex(P2PRandom.bytes(P2PConstants.ephemeralIdBytes)) }

    public static func isValid(_ eid: String) -> Bool { P2PMessages.isHex(eid, 32) }
}

/// Section 7.2: of two links between the same phones keep the one whose central has the smaller eid.
public enum LinkDedup {
    /// Both arguments are valid eids (lowercase hex, so string order is byte order).
    public static func keep(myEid: String, peerEid: String, iAmCentral: Bool) -> Bool {
        precondition(myEid != peerEid, "equal ephemeral ids")
        return (myEid < peerEid) == iAmCentral
    }
}
