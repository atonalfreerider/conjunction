import Foundation
import XCTest
@testable import ConjunctionCore

/// The laptop test peer (tools/p2p-sim) cannot run the astronomy engines, so each persona stores
/// its wire `compat` object precomputed (by the Kotlin engines). This recomputes it from the
/// persona's birth data with the Swift engines and the Swift wire encoding, so a drift on either
/// platform shows up here too (android/core PersonaProfilesTest is the Kotlin twin).
final class P2PPersonaProfilesTests: XCTestCase {
    func testPersonaCompatMatchesSwiftEngines() throws {
        let dir = Golden.directory.deletingLastPathComponent().appendingPathComponent("tools/p2p-sim/personas")
        let files = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix(".json") }.sorted()
        XCTAssertFalse(files.isEmpty, "no personas in \(dir.path)")
        for name in files {
            let p = try JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent(name))) as! [String: Any]
            let b = p.obj("birth")
            let birth = try BirthData(date: date(b.str("date")), time: b.optStr("time").map { LocalTime.parse($0)! },
                                      latitude: b.double("latitude"), longitude: b.double("longitude"),
                                      zoneId: b.str("zone"), placeName: b.str("place"))
            let blood = p.optStr("bloodType").flatMap(BloodType.parse)
            let computed = WireCompat.from(CompatProfile.from(NatalChart.compute(birth), ChineseZodiac.compute(birth), blood)).wire
            // The stored object, re-encoded canonically in wire order.
            let stored = p.obj("compat")
            XCTAssertEqual(Set(stored.keys), Set(computed.keys), name)
            var storedWire = WireObject()
            for k in computed.keys {
                switch stored[k] {
                case let s as String: storedWire.insert(k, .string(s))
                case let n as NSNumber: storedWire.insert(k, .int(n.int64Value))
                default: storedWire.insert(k, .null)
                }
            }
            XCTAssertEqual(WireJSON.writeString(.object(storedWire)), WireJSON.writeString(.object(computed)), name)
        }
    }
}
