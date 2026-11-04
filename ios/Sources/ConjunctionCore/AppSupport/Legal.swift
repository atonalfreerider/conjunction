import Foundation

/// The full legal documents bundled from legal/ (the iOS app copies them as resources).
public enum LegalDoc: String, CaseIterable, Sendable, Identifiable {
    case waiver = "WAIVER"
    case terms = "TERMS"
    case privacy = "PRIVACY"
    case community = "COMMUNITY_GUIDELINES"
    case safety = "SAFETY_TIPS"

    public var id: String { rawValue }
    public var key: String { rawValue }
    /// Resource file name (without extension) in the app bundle.
    public var resourceName: String { rawValue }

    public var title: String {
        switch self {
        case .waiver: return "Release & Waiver of Liability"
        case .terms: return "Terms of Service"
        case .privacy: return "Privacy Policy"
        case .community: return "Community Guidelines"
        case .safety: return "Safety Tips"
        }
    }

    /// Accepted together during onboarding (versioned; a newer bundled version asks again).
    public static let accepted: [LegalDoc] = [.waiver, .terms, .privacy]
}

/// A loaded document: its markdown, the version from its header table and the text's SHA-256.
public struct LegalText: Sendable, Equatable {
    public let doc: LegalDoc
    public let markdown: String
    public let version: String
    public let sha256: String

    public init(doc: LegalDoc, markdown: String) {
        self.doc = doc
        self.markdown = markdown
        version = LegalText.parseVersion(markdown)
        sha256 = LegalText.sha256Hex(markdown)
    }

    /// "| Version | 2.0 (draft of 2026-11-03) |" -> "2.0".
    public static func parseVersion(_ markdown: String) -> String {
        for raw in markdown.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard line.hasPrefix("|") else { continue }
            let cells = line.split(separator: "|", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
            guard cells.count >= 3, cells[1] == "Version" else { continue }
            let value = cells[2]
            let cut = value.firstIndex(of: "(").map { String(value[..<$0]) } ?? value
            let v = cut.trimmingCharacters(in: .whitespaces)
            if !v.isEmpty { return v }
        }
        return "unversioned"
    }

    public static func sha256Hex(_ text: String) -> String { P2PBytes.hex(P2PCrypto.sha256(Array(text.utf8))) }
}

/// One accepted consent, in the shape IN_APP_CONSENT_COPY.md "Rules for the apps" requires:
/// key, version, UTC timestamp, app version, platform and the SHA-256 of the text displayed.
public struct ConsentRecord: Codable, Hashable, Sendable, Identifiable {
    public var key: String
    public var version: String
    public var acceptedAt: String
    public var appVersion: String
    public var platform: String
    public var textSha256: String
    /// True for a withdrawal (Settings → Privacy).
    public var withdrawn: Bool

    public var id: String { "\(key)|\(acceptedAt)|\(withdrawn)" }

    public init(key: String, version: String, acceptedAt: String, appVersion: String, platform: String, textSha256: String,
                withdrawn: Bool = false) {
        self.key = key
        self.version = version
        self.acceptedAt = acceptedAt
        self.appVersion = appVersion
        self.platform = platform
        self.textSha256 = textSha256
        self.withdrawn = withdrawn
    }

    public static func now(_ key: String, version: String, displayedText: String, appVersion: String,
                           withdrawn: Bool = false, date: Date = Date()) -> ConsentRecord {
        ConsentRecord(key: key, version: version, acceptedAt: isoUTC(date), appVersion: appVersion, platform: "ios",
                      textSha256: LegalText.sha256Hex(displayedText), withdrawn: withdrawn)
    }

    /// "2026-11-03T14:05:09Z" (Java's Instant.toString without fractions).
    public static func isoUTC(_ date: Date) -> String {
        let utc = TimeZone(identifier: "UTC")!
        let z = ZonedDateTime(date, utc)
        return "\(z.date)T" + String(format: "%02d:%02d:%02dZ", z.hour, z.minute, z.second)
    }
}

/// Consent keys from IN_APP_CONSENT_COPY.md.
public enum ConsentKeys {
    public static let age18Confirmed = "AGE_18_CONFIRMED"
    public static let bloodTypeMatching = "CONSENT_BLOOD_TYPE_MATCHING"
    public static let orientationData = "CONSENT_ORIENTATION_DATA"
    public static let profileHonesty = "PROFILE_HONESTY_CONFIRMED"
    public static let nearbyBluetooth = "CONSENT_NEARBY_BLUETOOTH"
    /// Version of the short in-app consent copy (legal/IN_APP_CONSENT_COPY.md).
    public static let copyVersion = "2.0"
}

/// Which accepted documents have a newer bundled version (re-acceptance screen).
public enum LegalVersions {
    public static func changed(records: [ConsentRecord], bundled: [LegalDoc: String]) -> [LegalDoc] {
        LegalDoc.accepted.filter { doc in
            guard let current = bundled[doc],
                  let accepted = records.last(where: { $0.key == doc.key && !$0.withdrawn })?.version else { return false }
            return accepted != current
        }
    }
}
