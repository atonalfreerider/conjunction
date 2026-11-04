import Foundation
import XCTest
@testable import ConjunctionCore

/// Reads the shared golden vectors in ../testdata (the same files the Kotlin :core tests read).
/// CONJUNCTION_TESTDATA overrides the location; otherwise the directory is found from this
/// source file's path (ios/Tests/ConjunctionCoreTests/ -> ../../../testdata).
enum Golden {
    static var directory: URL {
        if let env = ProcessInfo.processInfo.environment["CONJUNCTION_TESTDATA"], !env.isEmpty {
            return URL(fileURLWithPath: env, isDirectory: true)
        }
        return URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("testdata", isDirectory: true)
    }

    static func url(_ name: String) -> URL { directory.appendingPathComponent(name) }

    static func exists(_ name: String) -> Bool { FileManager.default.fileExists(atPath: url(name).path) }

    /// The parsed JSON document [name], as Foundation objects (NSNull for null).
    static func load(_ name: String) throws -> [String: Any] {
        let data = try Data(contentsOf: url(name))
        guard let object = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) as? [String: Any] else {
            throw NSError(domain: "Golden", code: 1, userInfo: [NSLocalizedDescriptionKey: "\(name) is not a JSON object"])
        }
        return object
    }
}

/// Small typed accessors over JSONSerialization output.
extension Dictionary where Key == String, Value == Any {
    func str(_ key: String) -> String { self[key] as! String }
    func optStr(_ key: String) -> String? { self[key] as? String }
    func int(_ key: String) -> Int { (self[key] as! NSNumber).intValue }
    func optInt(_ key: String) -> Int? { (self[key] as? NSNumber)?.intValue }
    func double(_ key: String) -> Double { (self[key] as! NSNumber).doubleValue }
    func optDouble(_ key: String) -> Double? { (self[key] as? NSNumber)?.doubleValue }
    func bool(_ key: String) -> Bool { (self[key] as! NSNumber).boolValue }
    func obj(_ key: String) -> [String: Any] { self[key] as! [String: Any] }
    func optObj(_ key: String) -> [String: Any]? { self[key] as? [String: Any] }
    func list(_ key: String) -> [[String: Any]] { self[key] as! [[String: Any]] }
    func array(_ key: String) -> [Any] { self[key] as! [Any] }
    func isNull(_ key: String) -> Bool { self[key] == nil || self[key] is NSNull }
}

func date(_ iso: String) -> LocalDate {
    guard let d = LocalDate.parse(iso) else { fatalError("bad date \(iso)") }
    return d
}
