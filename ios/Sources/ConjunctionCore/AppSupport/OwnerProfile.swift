import Foundation

extension BloodType: Codable {}
extension RhFactor: Codable {}
extension Gender: Codable {}
extension Seeking: Codable {}
extension RelationshipStatus: Codable {}
extension LookingFor: Codable {}

/// Birth details as entered on the phone (never sent anywhere). Dates and times are kept as ISO
/// text ("1996-03-12", "06:40") so the stored file is readable and stable.
public struct BirthInput: Codable, Hashable, Sendable {
    public var dateText: String
    public var timeText: String?
    public var latitude: Double
    public var longitude: Double
    public var zoneId: String
    public var placeName: String

    public init(date: LocalDate, time: LocalTime?, latitude: Double, longitude: Double, zoneId: String, placeName: String) {
        dateText = date.description
        timeText = time.map { String(format: "%02d:%02d", $0.hour, $0.minute) }
        self.latitude = latitude
        self.longitude = longitude
        self.zoneId = zoneId
        self.placeName = placeName
    }

    public var date: LocalDate { LocalDate.parse(dateText) ?? LocalDate(2000, 1, 1) }
    public var time: LocalTime? { timeText.flatMap(LocalTime.parse) }

    public func toBirthData() throws -> BirthData {
        try BirthData(date: date, time: time, latitude: latitude, longitude: longitude, zoneId: zoneId, placeName: placeName)
    }
}

/// The dating/friends fields from SPEC 5b.
public struct DatingProfile: Codable, Hashable, Sendable {
    public var gender: Gender
    public var seeking: Seeking
    public var relationshipStatus: RelationshipStatus
    public var lookingFor: LookingFor

    public init(gender: Gender, seeking: Seeking, relationshipStatus: RelationshipStatus, lookingFor: LookingFor) {
        self.gender = gender
        self.seeking = seeking
        self.relationshipStatus = relationshipStatus
        self.lookingFor = lookingFor
    }

    public var matchProfile: MatchProfile {
        MatchProfile(gender: gender, seeking: seeking, relationshipStatus: relationshipStatus, lookingFor: lookingFor)
    }
}

/// Everything the app remembers about its owner (Android `Profile`). Kept in one JSON file in the
/// app's Application Support folder, excluded from backups.
public struct OwnerProfile: Codable, Hashable, Sendable {
    public var ageGateBirthDateText: String?
    /// Set when the age gate saw an under-18 date: onboarding stays blocked on this install.
    public var ageBlocked = false
    public var legalAccepted = false
    public var birth: BirthInput?
    public var bloodType: BloodType?
    public var rh: RhFactor?
    /// CONSENT_BLOOD_TYPE_MATCHING: blood type may be sent to nearby phones for compatibility.
    public var bloodForMatching = false
    public var dating: DatingProfile?
    /// CONSENT_ORIENTATION_DATA: required before any dating field leaves the phone.
    public var orientationConsent = false
    public var honestyConfirmed = false
    public var displayName = ""
    public var bio = ""
    /// The owner's photo file name in the app's private folder (sent only to Nearby matches, 640 px).
    public var photoFile: String?
    /// CONSENT_NEARBY_BLUETOOTH recorded (consent copy 2.0).
    public var nearbyConsent = false
    public var nearbyEnabled = false
    public var nearbyPausedUntil: Int64 = 0
    public var notificationsPrompted = false
    public var onboardingComplete = false
    public var safetyTipsSeen = false
    public var dailyReminder = false

    public init() {}

    public init(from decoder: Decoder) throws {
        // Every field optional on read, so the file format can grow without losing the owner's data.
        let c = try decoder.container(keyedBy: CodingKeys.self)
        ageGateBirthDateText = try c.decodeIfPresent(String.self, forKey: .ageGateBirthDateText)
        ageBlocked = try c.decodeIfPresent(Bool.self, forKey: .ageBlocked) ?? false
        legalAccepted = try c.decodeIfPresent(Bool.self, forKey: .legalAccepted) ?? false
        birth = try? c.decodeIfPresent(BirthInput.self, forKey: .birth)
        bloodType = try? c.decodeIfPresent(BloodType.self, forKey: .bloodType)
        rh = try? c.decodeIfPresent(RhFactor.self, forKey: .rh)
        bloodForMatching = try c.decodeIfPresent(Bool.self, forKey: .bloodForMatching) ?? false
        dating = try? c.decodeIfPresent(DatingProfile.self, forKey: .dating)
        orientationConsent = try c.decodeIfPresent(Bool.self, forKey: .orientationConsent) ?? false
        honestyConfirmed = try c.decodeIfPresent(Bool.self, forKey: .honestyConfirmed) ?? false
        displayName = try c.decodeIfPresent(String.self, forKey: .displayName) ?? ""
        bio = try c.decodeIfPresent(String.self, forKey: .bio) ?? ""
        photoFile = try c.decodeIfPresent(String.self, forKey: .photoFile)
        nearbyConsent = try c.decodeIfPresent(Bool.self, forKey: .nearbyConsent) ?? false
        nearbyEnabled = try c.decodeIfPresent(Bool.self, forKey: .nearbyEnabled) ?? false
        nearbyPausedUntil = try c.decodeIfPresent(Int64.self, forKey: .nearbyPausedUntil) ?? 0
        notificationsPrompted = try c.decodeIfPresent(Bool.self, forKey: .notificationsPrompted) ?? false
        onboardingComplete = try c.decodeIfPresent(Bool.self, forKey: .onboardingComplete) ?? false
        safetyTipsSeen = try c.decodeIfPresent(Bool.self, forKey: .safetyTipsSeen) ?? false
        dailyReminder = try c.decodeIfPresent(Bool.self, forKey: .dailyReminder) ?? false
    }

    public var ageGateBirthDate: LocalDate? {
        get { ageGateBirthDateText.flatMap(LocalDate.parse) }
        set { ageGateBirthDateText = newValue?.description }
    }

    public var firstName: String {
        String(displayName.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: " ").first ?? "")
    }

    /// Whether Nearby can be used at all (the Nearby consent is separate).
    public var nearbyEligible: Bool { dating != nil && orientationConsent && honestyConfirmed && birth != nil }

    public func isPaused(nowMs: Int64) -> Bool { nearbyPausedUntil > nowMs }

    /// Nearby is on, consented and not paused: the radio should run.
    public func nearbyShouldRun(nowMs: Int64) -> Bool { nearbyEnabled && nearbyConsent && nearbyEligible && !isPaused(nowMs: nowMs) }

    /// Onboarding step a partly-onboarded profile should resume at.
    public var resumeStep: OnboardingStep {
        if ageGateBirthDate == nil { return .welcome }
        if !legalAccepted { return .legal }
        if birth == nil { return .birthTime }
        return .blood
    }
}

public enum OnboardingStep: Int, CaseIterable, Sendable {
    case welcome, age, legal, birthTime, birthPlace, blood, profile, nearby, notifications
}

/// Everything computed on the phone from the birth data.
public struct OwnerCharts: Sendable {
    public let birth: BirthInput
    public let natal: NatalChart
    public let chinese: ChineseChart
    public let bloodType: BloodType?

    public init(birth: BirthInput, bloodType: BloodType?) throws {
        let data = try birth.toBirthData()
        self.birth = birth
        natal = NatalChart.compute(data)
        chinese = ChineseZodiac.compute(data)
        self.bloodType = bloodType
    }

    /// Changes whenever anything a reading depends on changes; used as a cache key.
    public var fingerprint: String {
        let parts = [birth.dateText, birth.timeText ?? "notime", String(format: "%.3f", birth.latitude),
                     String(format: "%.3f", birth.longitude), birth.zoneId, bloodType?.rawValue ?? "-"]
        return String(P2PBytes.hex(P2PCrypto.sha256(Array(parts.joined(separator: "|").utf8))).prefix(16))
    }

    /// The compatibility summary sent while Nearby is on. Blood type only with its consent.
    public func compatProfile(includeBlood: Bool) -> CompatProfile {
        CompatProfile.from(natal, chinese, includeBlood ? bloodType : nil)
    }

    /// What this phone sends in hello and card ([photo] = card JPEG from the app's photo codec).
    /// Nil when the profile may not take part in Nearby.
    public static func localPeerProfile(_ p: OwnerProfile, charts: OwnerCharts?, photo: (jpeg: [UInt8], w: Int, h: Int)?) -> LocalPeerProfile? {
        guard let charts, let dating = p.dating, p.orientationConsent, p.birth != nil else { return nil }
        let compat = WireCompat.from(charts.compatProfile(includeBlood: p.bloodForMatching))
        return LocalPeerProfile(match: dating.matchProfile, compat: compat,
                                firstName: NearbyText.cardName(p.firstName, sun: charts.natal.sunSign),
                                bio: NearbyText.cardBio(p.bio), photo: photo?.jpeg, photoW: photo?.w ?? 0, photoH: photo?.h ?? 0)
    }
}
