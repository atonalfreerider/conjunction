import Foundation
import CoreLocation
import ConjunctionCore

/// A birth place found on the phone: coordinates and the IANA time zone (whose rules give the
/// offset and daylight saving in force on the birth date, so historical DST is right).
struct PlaceResult: Identifiable, Equatable {
    let name: String
    let latitude: Double
    let longitude: Double
    let zoneId: String
    let offsetLabel: String

    var id: String { "\(name)|\(latitude)|\(longitude)" }
}

/// Birth-place lookup with `CLGeocoder` (forward geocoding of typed text, Apple's service; no
/// location permission). Each placemark carries `timeZone`; when it is missing (open sea,
/// coordinates typed by hand) the zone falls back to the nautical zone of the longitude (Etc/GMT±N).
enum PlaceSearch {
    enum Failure: LocalizedError {
        case none, failed(String)
        var errorDescription: String? {
            switch self {
            case .none: return "No places found. Try adding the country."
            case .failed(let why): return why
            }
        }
    }

    static func search(_ query: String, date: LocalDate, time: LocalTime?) async throws -> [PlaceResult] {
        let geocoder = CLGeocoder()
        let marks: [CLPlacemark]
        do {
            marks = try await geocoder.geocodeAddressString(query)
        } catch let error as CLError where error.code == .geocodeFoundNoResult {
            throw Failure.none
        } catch let error as CLError where error.code == .network {
            throw Failure.failed("Place search needs a connection to Apple's map service. Or enter coordinates.")
        } catch {
            throw Failure.failed("Place search failed. Check your connection or enter coordinates.")
        }
        let results = marks.compactMap { mark -> PlaceResult? in
            guard let loc = mark.location else { return nil }
            let zone = mark.timeZone ?? nauticalZone(longitude: loc.coordinate.longitude)
            return PlaceResult(name: label(mark, fallback: query), latitude: loc.coordinate.latitude,
                               longitude: loc.coordinate.longitude, zoneId: zone.identifier,
                               offsetLabel: offsetLabel(zone, date: date, time: time))
        }
        if results.isEmpty { throw Failure.none }
        return results
    }

    /// For typed coordinates: reverse-geocodes for the zone when possible, else the nautical zone.
    static func resolve(name: String, latitude: Double, longitude: Double, date: LocalDate, time: LocalTime?) async -> PlaceResult {
        let marks = try? await CLGeocoder().reverseGeocodeLocation(CLLocation(latitude: latitude, longitude: longitude))
        let zone = marks?.first?.timeZone ?? nauticalZone(longitude: longitude)
        return PlaceResult(name: name, latitude: latitude, longitude: longitude, zoneId: zone.identifier,
                           offsetLabel: offsetLabel(zone, date: date, time: time))
    }

    static func label(_ m: CLPlacemark, fallback: String) -> String {
        let parts = [m.locality ?? m.name, m.administrativeArea, m.country].compactMap { $0 }.filter { !$0.isEmpty }
        var unique: [String] = []
        for p in parts where !unique.contains(p) { unique.append(p) }
        return unique.isEmpty ? fallback : unique.joined(separator: ", ")
    }

    /// Etc/GMT-N is N hours EAST of Greenwich (POSIX sign convention).
    static func nauticalZone(longitude: Double) -> TimeZone {
        let hours = Int((longitude / 15).rounded())
        let id = hours == 0 ? "Etc/GMT" : (hours > 0 ? "Etc/GMT-\(hours)" : "Etc/GMT+\(-hours)")
        return TimeZone(identifier: id) ?? TimeZone(identifier: "UTC")!
    }

    /// "UTC+01:00 (summer time)" for the birth moment in [zone].
    static func offsetLabel(_ zone: TimeZone, date: LocalDate, time: LocalTime?) -> String {
        let instant = ZonedDateTime.instant(date, time ?? LocalTime(12, 0), zone)
        let secs = zone.secondsFromGMT(for: instant)
        let sign = secs < 0 ? "−" : "+"
        let a = abs(secs)
        let base = String(format: "UTC%@%02d:%02d", sign, a / 3600, (a % 3600) / 60)
        return zone.isDaylightSavingTime(for: instant) ? "\(base) (summer time)" : base
    }
}
