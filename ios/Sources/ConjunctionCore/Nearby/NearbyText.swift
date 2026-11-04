import Foundation

/// Text the Nearby feature builds on the phone: notification titles and bodies
/// (legal/IN_APP_CONSENT_COPY.md section 10) and the report email (section 8). Same wording as
/// Android's `Notifications.text` and `ReportEmail.body`.
public enum NearbyText {
    /// Title and body of a local notification. [sunSign] = display name ("Leo").
    public static func notification(_ event: NearbyEvent, peerName: String?, sunSign: String?,
                                    appName: String = AppText.appName) -> (title: String, body: String) {
        let name = (peerName?.trimmingCharacters(in: .whitespaces).isEmpty ?? true) ? "your match" : peerName!
        let total = String(event.total ?? 0)
        switch event.kind {
        case .match:
            if event.matchKind == "friends" {
                return (appName, L10n.format(L10n.notifMatchFriends, sunSign ?? "kindred", total))
            }
            return (appName, L10n.format(L10n.notifMatchDating, sunSign ?? "kindred spirit", total))
        case .birthdayTwin: return (appName, L10n.notifBirthdayTwin)
        case .exactTwin: return (appName, L10n.notifExactTwin)
        case .conjunction: return (appName, L10n.format(L10n.notifMatched, name))
        case .message: return (appName, L10n.format(L10n.notifMessage, name))
        }
    }

    /// Who a report is about (a stranger's match card, or a friend).
    public struct ReportSubject: Sendable {
        public var firstName: String?
        public var age: Int?
        public var sun: String?
        public var moon: String?
        public var rising: String?
        public var matchKind: String?
        /// Profile fingerprint (strangers) or friend id: the "temporary ID" in the email.
        public var localId: String?
        /// Friends only: their safety code (the same digits on both phones).
        public var identityFingerprint: String?
        public var hasPhoto: Bool

        public init(firstName: String?, age: Int?, sun: String?, moon: String?, rising: String?, matchKind: String?,
                    localId: String?, identityFingerprint: String?, hasPhoto: Bool) {
            self.firstName = firstName
            self.age = age
            self.sun = sun
            self.moon = moon
            self.rising = rising
            self.matchKind = matchKind
            self.localId = localId
            self.identityFingerprint = identityFingerprint
            self.hasPhoto = hasPhoto
        }

        public static func stranger(_ e: StoredEncounter) -> ReportSubject {
            ReportSubject(firstName: e.firstName, age: e.age, sun: e.sun, moon: e.moon, rising: e.rising, matchKind: e.kind,
                          localId: e.fingerprint, identityFingerprint: nil, hasPhoto: e.photoFile != nil)
        }

        public static func friend(_ f: StoredFriend) -> ReportSubject {
            ReportSubject(firstName: f.firstName, age: nil, sun: f.sun, moon: f.moon, rising: f.rising, matchKind: f.kind,
                          localId: f.id, identityFingerprint: f.safetyCode, hasPhoto: f.photoFile != nil)
        }
    }

    public static func reportSubjectLine(_ reason: ReportReason, appName: String = AppText.appName) -> String {
        "\(appName) report: \(reason.code)"
    }

    /// The report email body. [messages] only when the person ticked "Include our messages".
    public static func reportBody(_ s: ReportSubject, reason: ReportReason, description: String, messages: [ChatLine]?,
                                  appLine: String, zone: TimeZone = .current) -> String {
        var out = ""
        func line(_ t: String = "") { out += t + "\n" }
        line("Reason: \(reason.label)")
        line()
        line("What happened:")
        let d = description.trimmingCharacters(in: .whitespacesAndNewlines)
        line(d.isEmpty ? "(not given)" : d)
        line()
        line("Reported card")
        line("  First name: \(s.firstName ?? "(not shown)")")
        if let age = s.age, age > 0 { line("  Age: \(age)") }
        line("  Signs: sun \(s.sun ?? "?"), moon \(s.moon ?? "?"), rising \(s.rising ?? "unknown")")
        if let k = s.matchKind { line("  Match kind: \(k)") }
        line("  Photo on card: \(s.hasPhoto ? "yes (attach a screenshot if relevant)" : "no")")
        if let id = s.localId { line("  Temporary ID: \(id)") }
        if let fp = s.identityFingerprint { line("  Identity-key fingerprint (safety code): \(fp)") }
        if let messages {
            line()
            line("Our messages (\(messages.count)):")
            for m in messages {
                line("  [\(isoLocal(m.ts, zone: zone))] \(m.mine ? "Me" : s.firstName ?? "Them"): \(m.text)")
            }
        }
        line()
        line(appLine)
        return out
    }

    /// "2026-11-03T14:05:09" in [zone] (Java's ISO_LOCAL_DATE_TIME without fractions).
    public static func isoLocal(_ ms: Int64, zone: TimeZone) -> String {
        let z = ZonedDateTime(Date(timeIntervalSince1970: Double(ms) / 1000.0), zone)
        return "\(z.date)T" + String(format: "%02d:%02d:%02d", z.hour, z.minute, z.second)
    }

    // ------------------------------------------------------------------ card fields

    /// First name for the card (1..64 UTF-8 bytes); people without one appear as "A Leo".
    public static func cardName(_ firstName: String, sun: ZodiacSign) -> String {
        let t = firstName.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimUTF8(t.isEmpty ? "A \(sun.displayName)" : t, P2PConstants.maxFirstNameBytes)
    }

    /// The first line of the bio, trimmed to 280 UTF-8 bytes; nil when empty.
    public static func cardBio(_ bio: String) -> String? {
        let first = firstLine(bio)?.trimmingCharacters(in: .whitespaces) ?? ""
        return first.isEmpty ? nil : trimUTF8(first, P2PConstants.maxBioBytes)
    }

    /// First line (split on \r\n, \n or \r, like Kotlin's lineSequence).
    public static func firstLine(_ s: String?) -> String? {
        guard let s else { return nil }
        return s.split(omittingEmptySubsequences: false) { $0 == "\n" || $0 == "\r" || $0 == "\r\n" }.first.map(String.init)
    }

    /// At most [maxBytes] UTF-8 bytes, cut at a character boundary.
    public static func trimUTF8(_ s: String, _ maxBytes: Int) -> String {
        if s.utf8.count <= maxBytes { return s }
        var out = ""
        var n = 0
        for ch in s {
            let c = String(ch).utf8.count
            if n + c > maxBytes { break }
            out.append(ch)
            n += c
        }
        return out
    }

    /// "Pause until tomorrow" = 06:00 local tomorrow, in ms.
    public static func tomorrowMorning(now: Date = Date(), zone: TimeZone = .current) -> Int64 {
        let today = LocalDate.of(now, zone)
        let t = ZonedDateTime.instant(today.plusDays(1), LocalTime(6, 0), zone)
        return Int64((t.timeIntervalSince1970 * 1000).rounded())
    }
}
