import Foundation
import XCTest
@testable import ConjunctionCore

/// App-side logic of the iOS app that runs on Linux: legal documents and consent records, the
/// generated consent strings (in sync with the markdown and with ios/App/project.yml), the owner
/// profile file, chart-wheel geometry and the Markdown subset.
final class AppSupportTests: XCTestCase {
    private var repo: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent()
    }

    /// The rows of legal/IN_APP_CONSENT_COPY.md, parsed the way both generators parse them.
    private func consentRows() throws -> [String: String] {
        let md = try String(contentsOf: repo.appendingPathComponent("legal/IN_APP_CONSENT_COPY.md"), encoding: .utf8)
        var rows: [String: String] = [:]
        let re = try NSRegularExpression(pattern: #"^\|\s*`([A-Za-z0-9_]+)`(?:\s*\*\(store review\)\*)?\s*\|\s*(.+?)\s*\|\s*$"#)
        for line in md.split(separator: "\n", omittingEmptySubsequences: false).map(String.init) {
            let ns = line as NSString
            if let m = re.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)) {
                let key = ns.substring(with: m.range(at: 1))
                if rows[key] == nil { rows[key] = ns.substring(with: m.range(at: 2)) }
            }
        }
        return rows
    }

    func testConsentStringsMatchTheLegalCopy() throws {
        let rows = try consentRows()
        XCTAssertFalse(rows.isEmpty)
        XCTAssertEqual(Set(rows.keys), Set(L10n.all.keys),
                       "run python3 -I ios/App/tools/import_consent_strings.py after editing legal/IN_APP_CONSENT_COPY.md")
        for (key, text) in rows { XCTAssertEqual(L10n.all[key], text, key) }
    }

    func testInfoPlistUsageStringsMatchTheLegalCopy() throws {
        let yml = try String(contentsOf: repo.appendingPathComponent("ios/App/project.yml"), encoding: .utf8)
        // project.yml writes each usage string on one line, double-quoted.
        for (key, text) in [("NSBluetoothAlwaysUsageDescription", L10n.nsBluetoothAlwaysUsageDescription),
                            ("NSCameraUsageDescription", L10n.nsCameraUsageDescription)] {
            XCTAssertTrue(yml.contains("\(key): \"\(text)\""), "\(key) in project.yml differs from the legal copy")
        }
        for banned in ["NSLocationWhenInUseUsageDescription", "NSLocationAlwaysAndWhenInUseUsageDescription",
                       "remote-notification", "aps-environment", "FirebaseApp"] {
            XCTAssertFalse(yml.contains(banned), "\(banned) must not be in the serverless iOS app")
        }
        XCTAssertTrue(yml.contains("bluetooth-central") && yml.contains("bluetooth-peripheral"))
    }

    func testLegalVersionsAndRecords() throws {
        for doc in LegalDoc.allCases {
            let md = try String(contentsOf: repo.appendingPathComponent("legal/\(doc.resourceName).md"), encoding: .utf8)
            let text = LegalText(doc: doc, markdown: md)
            XCTAssertNotEqual(text.version, "unversioned", doc.rawValue)
            XCTAssertEqual(text.sha256.count, 64)
        }
        XCTAssertEqual(LegalText.parseVersion("| | |\n|---|---|\n| Version | 2.0 (draft of 2026-11-03) |"), "2.0")
        XCTAssertEqual(LegalText.parseVersion("no table"), "unversioned")
        let rec = ConsentRecord.now("TERMS", version: "2.0", displayedText: "abc", appVersion: "1.0.0",
                                    date: Date(timeIntervalSince1970: 0))
        XCTAssertEqual(rec.acceptedAt, "1970-01-01T00:00:00Z")
        XCTAssertEqual(rec.textSha256, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(rec.platform, "ios")
        let changed = LegalVersions.changed(records: [rec], bundled: [.terms: "2.1", .waiver: "2.0"])
        XCTAssertEqual(changed, [.terms])
    }

    func testOwnerProfileFileAndNearbyEligibility() throws {
        var p = OwnerProfile()
        XCTAssertEqual(p.resumeStep, .welcome)
        p.ageGateBirthDate = LocalDate(1996, 3, 12)
        XCTAssertEqual(p.resumeStep, .legal)
        p.legalAccepted = true
        XCTAssertEqual(p.resumeStep, .birthTime)
        p.birth = BirthInput(date: LocalDate(1996, 3, 12), time: LocalTime(6, 40), latitude: 48.8566, longitude: 2.3522,
                             zoneId: "Europe/Paris", placeName: "Paris")
        XCTAssertEqual(p.resumeStep, .blood)
        XCTAssertFalse(p.nearbyEligible)
        p.dating = DatingProfile(gender: .female, seeking: .both, relationshipStatus: .single, lookingFor: .both)
        p.orientationConsent = true
        p.honestyConfirmed = true
        p.displayName = "Aurora Borealis"
        p.bio = "Stargazer.\nTea."
        p.bloodType = .o
        XCTAssertTrue(p.nearbyEligible)
        XCTAssertEqual(p.firstName, "Aurora")

        let data = try JSONEncoder().encode(p)
        XCTAssertEqual(try JSONDecoder().decode(OwnerProfile.self, from: data), p)
        XCTAssertEqual(try JSONDecoder().decode(OwnerProfile.self, from: Data("{}".utf8)), OwnerProfile())

        let charts = try OwnerCharts(birth: p.birth!, bloodType: p.bloodType)
        let local = try XCTUnwrap(OwnerCharts.localPeerProfile(p, charts: charts, photo: nil))
        XCTAssertEqual(local.firstName, "Aurora")
        XCTAssertEqual(local.bio, "Stargazer.")
        XCTAssertNil(local.compat.blood, "blood type is sent only with its consent")
        XCTAssertEqual(local.compat.birthHour, 6)
        p.bloodForMatching = true
        XCTAssertEqual(OwnerCharts.localPeerProfile(p, charts: charts, photo: nil)?.compat.blood, .o)
        p.orientationConsent = false
        XCTAssertNil(OwnerCharts.localPeerProfile(p, charts: charts, photo: nil))
        XCTAssertEqual(charts.fingerprint.count, 16)
    }

    func testChartWheelSpreadKeepsOrderAndGap() {
        let spread = ChartWheelGeometry.spreadLongitudes([10, 11, 12, 200, 359])
        XCTAssertEqual(spread.count, 5)
        XCTAssertGreaterThanOrEqual(spread[1] - spread[0], 6.9)
        XCTAssertGreaterThanOrEqual(spread[2] - spread[1], 6.9)
        XCTAssertEqual(spread[3], 200, accuracy: 0.001)
        XCTAssertEqual(ChartWheelGeometry.wheelAngle(90, ascendant: 90), 180)
        XCTAssertEqual(ChartWheelGeometry.textGlyph("☉"), "☉\u{FE0E}")
    }

    func testMarkdownSubset() {
        let blocks = MarkdownParser.parse("# Title\n\nA **bold** line\ncontinues.\n\n- item\n  - sub\n1. first\n| a | b |\n|---|---|\n> quote\n---\n")
        XCTAssertEqual(blocks, [
            .heading(level: 1, text: "Title"),
            .paragraph("A **bold** line continues."),
            .bullet(text: "item", ordered: nil, indent: 0),
            .bullet(text: "sub", ordered: nil, indent: 1),
            .bullet(text: "first", ordered: "1", indent: 0),
            .tableRow(["a", "b"]),
            .quote("quote"),
            .rule,
        ])
        XCTAssertEqual(MarkdownParser.inline("A **b** *i* `c` [l](http://x) snake_case"),
                       [.plain("A "), .bold("b"), .plain(" "), .italic("i"), .plain(" "), .code("c"), .plain(" l snake_case")])
    }
}
