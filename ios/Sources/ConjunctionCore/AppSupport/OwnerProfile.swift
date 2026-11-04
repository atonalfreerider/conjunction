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
///
/// UX 2.0 (SPEC 5d): the four dating fields are kept one by one (onboarding screen 3 may fill only
/// some of them; the Nearby sheet collects the rest) and `dating` is the complete set. Files written
/// before 2.1 kept them as one `dating` object, which is still read. `honestyConfirmed` and
/// `legalAccepted` stay readable but no longer gate anything.
public struct OwnerProfile: Codable, Hashable, Sendable {
    public var ageGateBirthDateText: String?
    /// Set when an under-18 birth date was entered: the app stays blocked on this install.
    public var ageBlocked = false
    public var legalAccepted = false
    public var birth: BirthInput?
    public var bloodType: BloodType?
    public var rh: RhFactor?
    /// CONSENT_BLOOD_TYPE_MATCHING (the L3 switch): blood type may be sent to nearby phones.
    public var bloodForMatching = false
    public var gender: Gender?
    public var seeking: Seeking?
    public var relationshipStatus: RelationshipStatus?
    public var lookingFor: LookingFor?
    /// CONSENT_ORIENTATION_DATA recorded and not withdrawn (half of the L2 Nearby consent).
    public var orientationConsent = false
    public var honestyConfirmed = false
    public var displayName = ""
    public var bio = ""
    /// Free-text interests from onboarding screen 3 (feeds "Write one for me"); stays on the phone.
    public var interests = ""
    /// The owner's photo file name in the app's private folder (sent only to Nearby matches, 640 px).
    public var photoFile: String?
    /// Normalised Instagram username (`InstagramHandle.normalize`). Sent only to friends, encrypted.
    public var instagram: String?
    /// Settings › Nearby "Share my Instagram with new matches" (the per-friend switch's default).
    public var instagramShareDefault = true
    /// CONSENT_NEARBY_BLUETOOTH recorded and not withdrawn (the other half of the L2 consent).
    public var nearbyConsent = false
    public var nearbyEnabled = false
    public var nearbyPausedUntil: Int64 = 0
    /// The notification permission was asked once (Today's card stops showing).
    public var notificationsPrompted = false
    public var notifyMatches = true
    public var notifyMessages = true
    public var onboardingComplete = false
    /// UX 2.0 onboarding screen to resume at (`OnboardingScreen` raw value); nil = legacy mapping.
    public var onboardingStep: String?
    /// Set by onboarding's Done: the first main screen is Chart (the wheel draws itself) once.
    public var landOnChart = false
    public var safetyTipsSeen = false
    public var dailyReminder = false
    /// Daily reading time on the local clock, "HH:mm" (default 08:00).
    public var dailyReadingTimeText = "08:00"

    public init() {}

    private enum LegacyKeys: String, CodingKey { case dating }

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
        gender = try? c.decodeIfPresent(Gender.self, forKey: .gender)
        seeking = try? c.decodeIfPresent(Seeking.self, forKey: .seeking)
        relationshipStatus = try? c.decodeIfPresent(RelationshipStatus.self, forKey: .relationshipStatus)
        lookingFor = try? c.decodeIfPresent(LookingFor.self, forKey: .lookingFor)
        // Files from before UX 2.0 kept the four fields as one object.
        if gender == nil && seeking == nil && relationshipStatus == nil && lookingFor == nil,
           let legacy = try? decoder.container(keyedBy: LegacyKeys.self),
           let d = try? legacy.decodeIfPresent(DatingProfile.self, forKey: .dating) {
            gender = d.gender
            seeking = d.seeking
            relationshipStatus = d.relationshipStatus
            lookingFor = d.lookingFor
        }
        orientationConsent = try c.decodeIfPresent(Bool.self, forKey: .orientationConsent) ?? false
        honestyConfirmed = try c.decodeIfPresent(Bool.self, forKey: .honestyConfirmed) ?? false
        displayName = try c.decodeIfPresent(String.self, forKey: .displayName) ?? ""
        bio = try c.decodeIfPresent(String.self, forKey: .bio) ?? ""
        interests = try c.decodeIfPresent(String.self, forKey: .interests) ?? ""
        photoFile = try c.decodeIfPresent(String.self, forKey: .photoFile)
        instagram = InstagramHandle.normalize(try c.decodeIfPresent(String.self, forKey: .instagram))
        instagramShareDefault = try c.decodeIfPresent(Bool.self, forKey: .instagramShareDefault) ?? true
        nearbyConsent = try c.decodeIfPresent(Bool.self, forKey: .nearbyConsent) ?? false
        nearbyEnabled = try c.decodeIfPresent(Bool.self, forKey: .nearbyEnabled) ?? false
        nearbyPausedUntil = try c.decodeIfPresent(Int64.self, forKey: .nearbyPausedUntil) ?? 0
        notificationsPrompted = try c.decodeIfPresent(Bool.self, forKey: .notificationsPrompted) ?? false
        notifyMatches = try c.decodeIfPresent(Bool.self, forKey: .notifyMatches) ?? true
        notifyMessages = try c.decodeIfPresent(Bool.self, forKey: .notifyMessages) ?? true
        onboardingComplete = try c.decodeIfPresent(Bool.self, forKey: .onboardingComplete) ?? false
        onboardingStep = try c.decodeIfPresent(String.self, forKey: .onboardingStep)
        landOnChart = try c.decodeIfPresent(Bool.self, forKey: .landOnChart) ?? false
        safetyTipsSeen = try c.decodeIfPresent(Bool.self, forKey: .safetyTipsSeen) ?? false
        dailyReminder = try c.decodeIfPresent(Bool.self, forKey: .dailyReminder) ?? false
        dailyReadingTimeText = try c.decodeIfPresent(String.self, forKey: .dailyReadingTimeText) ?? "08:00"
    }

    public var ageGateBirthDate: LocalDate? {
        get { ageGateBirthDateText.flatMap(LocalDate.parse) }
        set { ageGateBirthDateText = newValue?.description }
    }

    public var dailyReadingTime: LocalTime {
        get { LocalTime.parse(dailyReadingTimeText) ?? LocalTime(8, 0) }
        set { dailyReadingTimeText = String(format: "%02d:%02d", newValue.hour, newValue.minute) }
    }

    public var firstName: String {
        String(displayName.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: " ").first ?? "")
    }

    /// The four dating fields when all are set; setting it writes (or, with nil, clears) all four.
    public var dating: DatingProfile? {
        get {
            guard let gender, let seeking, let relationshipStatus, let lookingFor else { return nil }
            return DatingProfile(gender: gender, seeking: seeking, relationshipStatus: relationshipStatus, lookingFor: lookingFor)
        }
        set {
            gender = newValue?.gender
            seeking = newValue?.seeking
            relationshipStatus = newValue?.relationshipStatus
            lookingFor = newValue?.lookingFor
        }
    }

    public var datingFieldsComplete: Bool { dating != nil }

    /// L2 given: both Nearby consent records present and not withdrawn (SPEC 5d.1.4).
    public var nearbyConsentGiven: Bool { orientationConsent && nearbyConsent }

    /// SPEC 5d.1.4: birth data, the four dating fields and the L2 consent.
    public var nearbyEligible: Bool { birth != nil && datingFieldsComplete && nearbyConsentGiven }

    /// The turn-on flow must show the L2 sheet first (consent, dating fields or first name missing).
    public var nearbyNeedsSheet: Bool { !nearbyConsentGiven || !datingFieldsComplete || firstName.isEmpty }

    public func isPaused(nowMs: Int64) -> Bool { nearbyPausedUntil > nowMs }

    /// Nearby is on, consented and not paused: the radio should run.
    public func nearbyShouldRun(nowMs: Int64) -> Bool { nearbyEnabled && nearbyEligible && !isPaused(nowMs: nowMs) }

    /// Where a partly onboarded profile resumes (SPEC 5d.1.4), or nil = onboarding is over. Old
    /// steps map by what was saved: nothing -> Welcome; an age-gate date but no birth data -> Birth
    /// (the date pre-filled); birth saved -> About you; the dating profile also saved (the old flow
    /// was at Nearby / Notifications) -> finished.
    public var resumeScreen: OnboardingScreen? {
        if onboardingComplete { return nil }
        if let saved = onboardingStep.flatMap(OnboardingScreen.init(rawValue:)) { return saved }
        if birth == nil && ageGateBirthDate == nil { return .welcome }
        if birth == nil { return .birth }
        if dating != nil { return nil }
        return .about
    }
}

/// The four onboarding screens (SPEC 5d.5).
public enum OnboardingScreen: String, CaseIterable, Sendable {
    case welcome = "WELCOME", birth = "BIRTH", about = "ABOUT", card = "CARD"

    /// 0-based position for the 4-dot progress indicator.
    public var index: Int { OnboardingScreen.allCases.firstIndex(of: self)! }
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
        // The dating fields leave the phone only after the L2 consent (SPEC 5d.5 screen 3).
        guard let charts, let dating = p.dating, p.nearbyConsentGiven, p.birth != nil else { return nil }
        let compat = WireCompat.from(charts.compatProfile(includeBlood: p.bloodForMatching))
        return LocalPeerProfile(match: dating.matchProfile, compat: compat,
                                firstName: NearbyText.cardName(p.firstName, sun: charts.natal.sunSign),
                                bio: NearbyText.cardBio(p.bio), photo: photo?.jpeg, photoW: photo?.w ?? 0, photoH: photo?.h ?? 0,
                                instagram: InstagramHandle.normalize(p.instagram), shareInstagramByDefault: p.instagramShareDefault)
    }
}
