import Foundation

/// The bundled reading text (port of fallback/Content.kt). Resources/content_en.json is a
/// byte-for-byte copy of android/core's fallback/content_en.json (ResourceParityTests checks it):
/// sign, planet, house, aspect, animal, element, day-master, blood-type, palm and compatibility
/// fragments used by `FallbackReadings` and `BloodTypeReading`.
public enum Content {
    /// The parsed document. JSONSerialization gives NSNull for null, NSNumber for numbers.
    nonisolated(unsafe) public static let root: [String: Any] = {
        do {
            guard let url = Bundle.module.url(forResource: "content_en", withExtension: "json", subdirectory: "Resources")
                ?? Bundle.module.url(forResource: "content_en", withExtension: "json") else {
                fatalError("Missing bundled resource content_en.json")
            }
            guard let object = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any] else {
                fatalError("content_en.json is not a JSON object")
            }
            return object
        } catch {
            fatalError("content_en.json is unreadable: \(error)")
        }
    }()

    static func obj(_ v: Any?) -> [String: Any] {
        guard let m = v as? [String: Any] else { fatalError("content_en.json: expected an object") }
        return m
    }

    static func strings(_ v: Any?) -> [String] {
        guard let a = v as? [Any] else { fatalError("content_en.json: expected a list") }
        return a.map { $0 as! String }
    }

    /// root[section][key] as a map.
    static func entry(_ section: String, _ key: String) -> [String: Any] {
        guard let m = obj(root[section])[key] else { fatalError("content_en.json: missing \(section).\(key)") }
        return obj(m)
    }

    static func text(_ section: String, _ key: String, _ field: String) -> String {
        guard let s = entry(section, key)[field] as? String else { fatalError("content_en.json: missing \(section).\(key).\(field)") }
        return s
    }

    static func list(_ section: String, _ key: String) -> [String] { strings(obj(root[section])[key]) }
}

extension Content {
    /// A list of objects (Kotlin's Content.arrOfObj).
    static func arrOfObj(_ v: Any?) -> [[String: Any]] {
        guard let a = v as? [Any] else { fatalError("content_en.json: expected a list") }
        return a.map { obj($0) }
    }
}
