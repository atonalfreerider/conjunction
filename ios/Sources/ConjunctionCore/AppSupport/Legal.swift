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

    /// The documents whose acceptance is checked after an update (SPEC 5d.1.4), in this order.
    public static let accepted: [LegalDoc] = [.terms, .waiver, .privacy, .community]

    /// Accepted by the L1 clickwrap line on onboarding screen 2 (every record hashes the line). The
    /// Privacy Policy is only read there (PRIVACY_NOTICE_SEEN, hashing the privacy line).
    public static let clickwrap: [LegalDoc] = [.waiver, .terms, .community]

    /// Profile › Settings › Privacy & legal › Documents, in this order (SPEC 5d.1.3).
    public static let settingsOrder: [LegalDoc] = [.terms, .waiver, .privacy, .community, .safety]
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
/// [docSha256] is the SHA-256 of the full bundled document the record is about (Terms, Waiver,
/// Community Guidelines, Privacy Policy), so it shows which text was accepted (Waiver §19.2); nil for
/// consents that are not about a document and for records made before 2.1.
public struct ConsentRecord: Codable, Hashable, Sendable, Identifiable {
    public var key: String
    public var version: String
    public var acceptedAt: String
    public var appVersion: String
    public var platform: String
    public var textSha256: String
    /// True for a withdrawal (Settings → Privacy).
    public var withdrawn: Bool
    public var docSha256: String?

    public var id: String { "\(key)|\(acceptedAt)|\(withdrawn)" }

    public init(key: String, version: String, acceptedAt: String, appVersion: String, platform: String, textSha256: String,
                withdrawn: Bool = false, docSha256: String? = nil) {
        self.key = key
        self.version = version
        self.acceptedAt = acceptedAt
        self.appVersion = appVersion
        self.platform = platform
        self.textSha256 = textSha256
        self.withdrawn = withdrawn
        self.docSha256 = docSha256
    }

    public static func now(_ key: String, version: String, displayedText: String, appVersion: String,
                           withdrawn: Bool = false, docSha256: String? = nil, date: Date = Date()) -> ConsentRecord {
        ConsentRecord(key: key, version: version, acceptedAt: isoUTC(date), appVersion: appVersion, platform: "ios",
                      textSha256: LegalText.sha256Hex(displayedText), withdrawn: withdrawn, docSha256: docSha256)
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
    public static let copyVersion = "2.1"

    /// `<DOC>_NOTICE_SEEN`: the minor-update notice for that document was shown (SPEC 5d.1.4).
    public static func noticeSeen(_ doc: LegalDoc) -> String { "\(doc.key)_NOTICE_SEEN" }
}

/// What the app must show about document versions after an update (SPEC 5d.1.4).
public enum LegalUpdate: Equatable, Sendable {
    case none
    /// A major version changed (or a document was never accepted): the compact re-accept sheet (L4).
    case reaccept([LegalDoc])
    /// Only minor versions changed: the one-time notice sheet.
    case notice([LegalDoc])
}

/// Version rules for accepted documents and the Nearby consent (Android `LegalVersions`).
public enum LegalVersions {
    /// "2.1" -> "2"; nil for anything that does not start with digits before the first dot.
    public static func major(_ version: String?) -> String? {
        guard let v = version?.trimmingCharacters(in: .whitespaces) else { return nil }
        let head = v.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? ""
        return !head.isEmpty && head.allSatisfy({ $0.isASCII && $0.isNumber }) ? head : nil
    }

    /// The version of the latest record for [key] when it is not a withdrawal, else nil.
    public static func accepted(_ records: [ConsentRecord], _ key: String) -> String? {
        guard let last = records.last(where: { $0.key == key }), !last.withdrawn else { return nil }
        return last.version
    }

    /// Compares the major version of each accepted document with the bundled one. A missing
    /// COMMUNITY_GUIDELINES record is covered by TERMS accepted at the bundled Terms' major version.
    /// The Privacy Policy is read, never agreed to (GDPR Art. 7(2)): any change to it, major or
    /// minor, is a notice and never enters re-acceptance.
    public static let readOnlyDocs: Set<LegalDoc> = [.privacy]

    public static func evaluate(records: [ConsentRecord], bundled: [LegalDoc: String]) -> LegalUpdate {
        var reaccept: [LegalDoc] = []
        var notice: [LegalDoc] = []
        for doc in LegalDoc.accepted {
            guard let have = bundled[doc] else { continue }
            if readOnlyDocs.contains(doc) {
                // Seen at onboarding (PRIVACY_NOTICE_SEEN) or, for records made before 2.1, "PRIVACY".
                let seen = accepted(records, ConsentKeys.noticeSeen(doc)) ?? accepted(records, doc.key)
                if seen != have { notice.append(doc) }
                continue
            }
            var mine = accepted(records, doc.key)
            if mine == nil && doc == .community, let terms = accepted(records, LegalDoc.terms.key),
               major(terms) == major(bundled[.terms]) {
                mine = have
            }
            if mine == nil || major(mine) != major(have) {
                reaccept.append(doc)
            } else if mine != have && accepted(records, ConsentKeys.noticeSeen(doc)) != have {
                notice.append(doc)
            }
        }
        if !reaccept.isEmpty { return .reaccept(reaccept) }
        if !notice.isEmpty { return .notice(notice) }
        return .none
    }

    /// L1 already accepted (a 2.0 user migrated mid-onboarding): WAIVER and TERMS at the bundled
    /// major version and AGE_18_CONFIRMED.
    public static func clickwrapAlreadyAccepted(records: [ConsentRecord], bundled: [LegalDoc: String]) -> Bool {
        [LegalDoc.waiver, .terms].allSatisfy { doc in
            guard let mine = accepted(records, doc.key) else { return false }
            return major(mine) == major(bundled[doc])
        } && accepted(records, ConsentKeys.age18Confirmed) != nil
    }

    /// Both L2 records present and the latest of each not withdrawn.
    public static func nearbyConsentGiven(_ records: [ConsentRecord]) -> Bool {
        accepted(records, ConsentKeys.nearbyBluetooth) != nil && accepted(records, ConsentKeys.orientationData) != nil
    }

    /// When the current L2 consent was given (the later of the two records), or nil.
    public static func nearbyConsentAt(_ records: [ConsentRecord]) -> String? {
        guard nearbyConsentGiven(records) else { return nil }
        return [ConsentKeys.nearbyBluetooth, ConsentKeys.orientationData]
            .compactMap { k in records.last(where: { $0.key == k })?.acceptedAt }.max()
    }

    /// "2026-11-04T10:00:00Z" -> "4 Nov 2026" in [zone] (the Nearby consent row).
    public static func recordDate(_ utc: String, zone: TimeZone = .current) -> String {
        let parts = utc.split(whereSeparator: { "-T:Z".contains($0) }).compactMap { Int($0) }
        guard parts.count >= 6, (0...23).contains(parts[3]), (0...59).contains(parts[4]),
              let date = LocalDate.parse(String(utc.prefix(10))) else { return String(utc.prefix(10)) }
        let instant = ZonedDateTime.instant(date, LocalTime(parts[3], parts[4]), TimeZone(identifier: "UTC")!)
        let local = ZonedDateTime(instant, zone).date
        let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        return "\(local.day) \(months[local.month - 1]) \(local.year)"
    }
}

/// The L1 clickwrap texts (SPEC 5d.1.1): the formatted sentence is what every L1 record hashes.
public enum Clickwrap {
    public static var checkboxText: String {
        L10n.format(L10n.legalAcceptCheckbox, L10n.legalLinkTerms, L10n.legalLinkGuidelines, L10n.legalLinkWaiver)
    }

    public static var privacyText: String { L10n.format(L10n.legalPrivacyLine, L10n.legalLinkPrivacy) }

    /// The L2 sheet text the two Nearby consent records hash: title, the four points, the checkbox.
    public static var nearbySheetText: String {
        ([L10n.nearbyTitle] + NearbySheet.points + [L10n.nearbyConsentCheckbox]).joined(separator: "\n")
    }

    /// The L3 text the blood-type record hashes.
    public static var bloodShareText: String { "\(L10n.bloodTypeShareSwitch)\n\(L10n.bloodTypeShareSub)" }
}

/// The L2 sheet's visible points, in IN_APP_CONSENT_COPY §5 table order.
public enum NearbySheet {
    public static var points: [String] {
        [L10n.nearbySheetPointBluetooth, L10n.nearbySheetPointSent, L10n.nearbySheetPointMatch, L10n.nearbySheetPointControl]
    }
}
