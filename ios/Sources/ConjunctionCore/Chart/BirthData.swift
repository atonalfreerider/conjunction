import Foundation

/// Everything the engines need about a birth (port of chart/BirthData.kt). Birth data never leaves
/// the phone; only the derived `CompatProfile` is uploaded when Nearby is on.
///
/// Time-zone rules: [zoneId] is an IANA id resolved with the tz rules valid at [date], so historical
/// DST and local-mean-time offsets are right. A [time] in a DST gap is moved forward by the gap
/// length; an ambiguous time in a DST overlap uses the earlier offset (java.time's
/// `ZonedDateTime.of`, mirrored by `ZonedDateTime.instant` here and by the golden generator).
public struct BirthData: Hashable, Sendable {
    public enum InvalidBirthData: Error, Equatable, CustomStringConvertible, Sendable {
        case latitude(Double), longitude(Double), unknownZone(String)
        public var description: String {
            switch self {
            case .latitude(let v): return "latitude out of range: \(v)"
            case .longitude(let v): return "longitude out of range: \(v)"
            case .unknownZone(let z): return "Unknown time-zone id: \(z)"
            }
        }
    }

    public let date: LocalDate
    /// Exact local clock time of birth, or nil when unknown (disables Ascendant, houses, hour pillar).
    public let time: LocalTime?
    public let latitude: Double
    /// East-positive longitude in degrees.
    public let longitude: Double
    public let zoneId: String
    public let placeName: String
    public let zone: TimeZone

    public init(date: LocalDate, time: LocalTime?, latitude: Double, longitude: Double, zoneId: String,
                placeName: String = "") throws {
        guard (-90.0...90.0).contains(latitude) else { throw InvalidBirthData.latitude(latitude) }
        guard (-180.0...180.0).contains(longitude) else { throw InvalidBirthData.longitude(longitude) }
        guard let zone = TimeZone(identifier: zoneId) else { throw InvalidBirthData.unknownZone(zoneId) }
        self.date = date
        self.time = time
        self.latitude = latitude
        self.longitude = longitude
        self.zoneId = zoneId
        self.placeName = placeName
        self.zone = zone
    }

    public static let noon = LocalTime(12, 0)

    public var timeKnown: Bool { time != nil }

    /// The birth moment; with unknown time, local noon (the least-wrong single instant for the day).
    public var instant: Date { ZonedDateTime.instant(date, time ?? BirthData.noon, zone) }

    public var zonedDateTime: ZonedDateTime { ZonedDateTime(instant, zone) }
}
