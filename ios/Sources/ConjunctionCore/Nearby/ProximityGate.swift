import Foundation

/// How a link ended, for the reconnect cool-down.
public enum LinkOutcome: Sendable, Equatable { case noMatch, done, dedup, error, timeout, shutdown }

/// "Close by" (SPEC 5c, protocol section 3): an advertiser seen with RSSI >= [rssiThreshold] dBm
/// in at least [minSightings] scan results within [windowMs]. Only RSSI is used; nothing is turned
/// into a distance or a location. Keys are CoreBluetooth peripheral identifiers (Android: addresses).
public struct ProximityGate: Sendable {
    public var rssiThreshold: Int
    private let minSightings: Int
    private let windowMs: Int64
    private var sightings: [String: [Int64]] = [:]

    public init(rssiThreshold: Int = P2PConstants.proximityRssiDbm, minSightings: Int = P2PConstants.proximityMinSightings,
                windowMs: Int64 = P2PConstants.proximityWindowMs) {
        self.rssiThreshold = rssiThreshold
        self.minSightings = minSightings
        self.windowMs = windowMs
    }

    /// Records one scan result; true when [key] now counts as close by. CoreBluetooth reports
    /// RSSI 127 when it is unavailable: such readings never count.
    public mutating func observe(_ key: String, rssi: Int, nowMs: Int64) -> Bool {
        var q = sightings[key] ?? []
        q.removeAll { nowMs - $0 > windowMs }
        if rssi >= rssiThreshold && rssi < 20 { q.append(nowMs) }
        sightings[key] = q.isEmpty ? nil : q
        return q.count >= minSightings
    }

    /// Advertisers currently close by (for "N phones nearby" on the Nearby screen).
    public func closeBy(nowMs: Int64) -> Int {
        sightings.values.filter { q in q.filter { nowMs - $0 <= windowMs }.count >= minSightings }.count
    }

    public mutating func prune(nowMs: Int64) {
        for (k, q) in sightings {
            let kept = q.filter { nowMs - $0 <= windowMs }
            sightings[k] = kept.isEmpty ? nil : kept
        }
    }

    public mutating func clear() { sightings.removeAll() }
}

/// When the central side may (re)connect to an advertiser (protocol section 3): not within a
/// cool-down after a finished link; failed attempts back off exponentially (5 s, 10 s, ... 120 s).
public struct ReconnectPolicy: Sendable {
    public static let firstBackoffMs: Int64 = 5_000
    /// No match / declined / hidden: do not ask the same advertiser again for a while.
    public static let settledCooldownMs: Int64 = 10 * 60_000

    private var until: [String: Int64] = [:]
    private var failures: [String: Int] = [:]

    public init() {}

    public func mayConnect(_ key: String, nowMs: Int64) -> Bool { (until[key] ?? 0) <= nowMs }

    public mutating func finished(_ key: String, outcome: LinkOutcome, nowMs: Int64) {
        failures[key] = nil
        until[key] = nowMs + ReconnectPolicy.cooldown(for: outcome)
    }

    public mutating func failed(_ key: String, nowMs: Int64) {
        let n = (failures[key] ?? 0) + 1
        failures[key] = n
        until[key] = nowMs + min(P2PConstants.reconnectBackoffMs, ReconnectPolicy.firstBackoffMs << Int64(min(n - 1, 5)))
    }

    /// Lift "finished" cool-downs (the person queued a message for a friend who may be in range).
    public mutating func expedite(nowMs: Int64) {
        until = until.filter { failures[$0.key] != nil && $0.value > nowMs }
    }

    public mutating func clear() {
        until.removeAll()
        failures.removeAll()
    }

    public static func cooldown(for outcome: LinkOutcome) -> Int64 {
        switch outcome {
        case .noMatch, .done, .dedup: return settledCooldownMs
        case .error, .timeout, .shutdown: return P2PConstants.reconnectBackoffMs
        }
    }
}
