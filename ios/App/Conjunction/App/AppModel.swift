import Foundation
import SwiftUI
import Combine
import CoreBluetooth
import ConjunctionCore

/// The app's state on the main actor (Android `AppContainer` + `ProfileStore` + `ConsentLedger`):
/// the owner's profile and the charts computed from it, the on-phone consent records, readings and
/// Nearby. Everything is kept in local files; nothing is sent to a server (there is none).
@MainActor
final class AppModel: ObservableObject {
    static let shared = AppModel()

    @Published private(set) var profile: OwnerProfile
    @Published private(set) var charts: OwnerCharts? = nil
    @Published private(set) var consents: [ConsentRecord]
    /// A tapped notification waiting for the main screen to open it.
    @Published var pendingEvent: NearbyEvent? = nil
    /// SPEC 5d.1.4: the re-accept sheet (major change) or the one-time notice (minor change).
    @Published private(set) var legalUpdate: LegalUpdate = .none
    /// The L2 Nearby consent sheet (SPEC 5d.3.1), shown over the app by the main scaffold.
    @Published var nearbySheet = false
    /// The Bluetooth permission was refused (status card "Open Settings").
    @Published var bluetoothDenied = false

    let readings = ReadingService()
    let nearby = NearbyRepository()
    /// Versions of the bundled documents (read once).
    let bundledVersions: [LegalDoc: String]
    private var chartsTask: Task<Void, Never>?

    private init() {
        let stored = AppFiles.read(OwnerProfile.self, from: AppFiles.profileURL) ?? OwnerProfile()
        profile = stored
        consents = AppFiles.read([ConsentRecord].self, from: AppFiles.consentsURL) ?? []
        var versions: [LegalDoc: String] = [:]
        for doc in LegalDoc.allCases { versions[doc] = LegalTexts.load(doc)?.version }
        bundledVersions = versions
        // Synchronously at launch (a few milliseconds): when iOS relaunches the app for a Bluetooth
        // event, the CoreBluetooth managers must be re-created during didFinishLaunching, which
        // needs the Nearby card (and so the charts) right away.
        charts = stored.birth.flatMap { try? OwnerCharts(birth: $0, bloodType: stored.bloodType) }
        refreshLegal()
    }

    // ------------------------------------------------------------------ profile

    /// Read-modify-write of the whole profile; the file is written at once.
    func update(_ transform: (inout OwnerProfile) -> Void) {
        let old = profile
        var p = profile
        transform(&p)
        guard p != old else { return }
        profile = p
        AppFiles.write(p, to: AppFiles.profileURL)
        if p.birth != old.birth || p.bloodType != old.bloodType {
            recomputeCharts()
        } else {
            applyNearby()
        }
        if p.dailyReminder != old.dailyReminder || p.dailyReadingTime != old.dailyReadingTime {
            Notifications.setDailyReminder(p.dailyReminder, at: p.dailyReadingTime)
        }
        if p.instagram != old.instagram || p.instagramShareDefault != old.instagramShareDefault {
            nearby.refreshProfiles()
        }
        if p.onboardingComplete != old.onboardingComplete { refreshLegal() }
    }

    private func recomputeCharts() {
        chartsTask?.cancel()
        guard let birth = profile.birth else {
            charts = nil
            applyNearby()
            return
        }
        let blood = profile.bloodType
        chartsTask = Task { [weak self] in
            let computed = await Task.detached(priority: .userInitiated) { try? OwnerCharts(birth: birth, bloodType: blood) }.value
            guard !Task.isCancelled, let self else { return }
            self.charts = computed
            self.applyNearby()
        }
    }

    /// What this phone sends over Bluetooth and whether the radio runs follow the profile.
    func applyNearby() { nearby.apply(profile: profile, charts: charts) }

    /// The owner's compatibility profile as nearby phones see it (blood only with its consent).
    var myCompat: CompatProfile? { charts?.compatProfile(includeBlood: profile.bloodForMatching) }

    // ------------------------------------------------------------------ consents

    func record(_ key: String, version: String, text: String, withdrawn: Bool = false, docSha256: String? = nil) {
        consents.append(ConsentRecord.now(key, version: version, displayedText: text, appVersion: AppInfo.version, withdrawn: withdrawn,
                                          docSha256: docSha256))
        AppFiles.write(consents, to: AppFiles.consentsURL)
        refreshLegal()
    }

    func recordDocument(_ text: LegalText) { record(text.doc.key, version: text.version, text: text.markdown) }

    /// SPEC 5d.1.4: compare major versions; only once onboarding is complete (L1 covers new users).
    private func refreshLegal() {
        let next: LegalUpdate = profile.onboardingComplete ? LegalVersions.evaluate(records: consents, bundled: bundledVersions) : .none
        if next != legalUpdate { legalUpdate = next }
    }

    /// L1 (SPEC 5d.1.1 / L-3): WAIVER, TERMS, COMMUNITY_GUIDELINES at the bundled versions, each
    /// hashing the formatted checkbox sentence and carrying the bundled document's own SHA-256;
    /// PRIVACY_NOTICE_SEEN (read, not agreed) hashing the privacy line; AGE_18_CONFIRMED.
    func recordClickwrap() {
        let text = Clickwrap.checkboxText
        for doc in LegalDoc.clickwrap {
            if let t = LegalTexts.load(doc) { record(doc.key, version: t.version, text: text, docSha256: t.sha256) }
        }
        if let t = LegalTexts.load(.privacy) {
            record(ConsentKeys.noticeSeen(.privacy), version: t.version, text: Clickwrap.privacyText, docSha256: t.sha256)
        }
        record(ConsentKeys.age18Confirmed, version: ConsentKeys.copyVersion, text: text)
    }

    /// A user who accepted under 2.0 and was migrated mid-onboarding never sees L1 again (O-7).
    var clickwrapAlreadyAccepted: Bool {
        LegalVersions.clickwrapAlreadyAccepted(records: consents, bundled: bundledVersions)
    }

    /// The re-accept sheet's button: records the documents at the bundled versions.
    func acceptLegalUpdate(_ docs: [LegalDoc], body: String) {
        for doc in docs { if let t = LegalTexts.load(doc) { record(doc.key, version: t.version, text: body, docSha256: t.sha256) } }
    }

    /// The notice sheet was seen: `<DOC>_NOTICE_SEEN` at the bundled versions.
    func noticeSeen(_ docs: [LegalDoc], body: String) {
        for doc in docs { if let t = LegalTexts.load(doc) { record(ConsentKeys.noticeSeen(doc), version: t.version, text: body, docSha256: t.sha256) } }
    }

    /// "Given 4 Nov 2026" for the Nearby consent row, or nil when not given.
    var nearbyConsentDate: String? {
        guard profile.nearbyConsentGiven, let at = LegalVersions.nearbyConsentAt(consents) else { return nil }
        return LegalVersions.recordDate(at)
    }

    // ------------------------------------------------------------------ Nearby (SPEC 5d.3.1)

    /// The one turn-on flow (empty state, status card, Settings switch): the L2 sheet when the
    /// consent, the dating fields or the first name are missing, else straight to Bluetooth.
    func requestNearbyOn() {
        if profile.nearbyNeedsSheet {
            nearbySheet = true
        } else {
            turnOnNearby()
        }
    }

    /// The L2 sheet's Turn on: records both consents with the sheet's hash and saves the fields.
    func giveNearbyConsent(firstName: String?, dating: DatingProfile) {
        let text = Clickwrap.nearbySheetText
        record(ConsentKeys.nearbyBluetooth, version: ConsentKeys.copyVersion, text: text)
        record(ConsentKeys.orientationData, version: ConsentKeys.copyVersion, text: text)
        update {
            $0.dating = dating
            if let firstName, $0.firstName.isEmpty { $0.displayName = firstName }
            $0.orientationConsent = true
            $0.nearbyConsent = true
        }
        nearbySheet = false
        turnOnNearby()
    }

    /// Consent given: Nearby on. Starting the radio makes iOS show its Bluetooth prompt; the
    /// notification prompt follows once, only if it was never asked (match alerts).
    func turnOnNearby() {
        guard profile.nearbyEligible else {
            nearbySheet = true
            return
        }
        guard nearby.bluetoothAllowed else {
            bluetoothDenied = true
            return
        }
        bluetoothDenied = false
        update { $0.nearbyEnabled = true; $0.nearbyPausedUntil = 0 }
        guard !profile.notificationsPrompted else { return }
        Task { [weak self] in
            // Wait for the Bluetooth answer so the two system prompts never overlap.
            for _ in 0..<120 where CBManager.authorization == .notDetermined {
                try? await Task.sleep(nanoseconds: 500_000_000)
            }
            guard let self, !self.profile.notificationsPrompted else { return }
            let granted = await Notifications.requestAuthorization()
            self.update { $0.notificationsPrompted = true; if granted && !$0.dailyReminder { $0.dailyReminder = true } }
            self.bluetoothDenied = !self.nearby.bluetoothAllowed
        }
    }

    func disableNearby() { update { $0.nearbyEnabled = false; $0.nearbyPausedUntil = 0 } }

    func pauseNearby(until ms: Int64) { update { $0.nearbyPausedUntil = ms } }

    func resumeNearby() { update { $0.nearbyPausedUntil = 0 } }

    /// Settings › Privacy & legal › Nearby consent › Withdraw (SPEC 5d.1.3 item 2): both records
    /// withdrawn, Nearby off; the next turn-on shows the L2 sheet again.
    func withdrawNearbyConsent() {
        let text = L10n.settingsWithdrawNearbyConsentConfirm
        record(ConsentKeys.orientationData, version: ConsentKeys.copyVersion, text: text, withdrawn: true)
        record(ConsentKeys.nearbyBluetooth, version: ConsentKeys.copyVersion, text: text, withdrawn: true)
        update { $0.nearbyEnabled = false; $0.nearbyPausedUntil = 0; $0.orientationConsent = false; $0.nearbyConsent = false }
    }

    /// L3 (and its mirrors): consent on, withdrawal off.
    func setBloodShare(_ on: Bool) {
        record(ConsentKeys.bloodTypeMatching, version: ConsentKeys.copyVersion, text: Clickwrap.bloodShareText, withdrawn: !on)
        update { $0.bloodForMatching = on }
    }

    func removeBloodType() {
        if profile.bloodForMatching {
            record(ConsentKeys.bloodTypeMatching, version: ConsentKeys.copyVersion, text: Clickwrap.bloodShareText, withdrawn: true)
        }
        update { $0.bloodType = nil; $0.rh = nil; $0.bloodForMatching = false }
    }

    // ------------------------------------------------------------------ birth (age gate, SPEC 5d.1.2)

    /// Saves the birth data; under 18 blocks this install instead (the date is not kept). Returns
    /// false when blocked.
    @discardableResult
    func saveBirth(date: LocalDate, time: LocalTime?, place: PlaceResult) -> Bool {
        guard Age.isAdult(date, today: LocalDate.todayInCurrentTimeZone()) else {
            update { $0.nearbyEnabled = false; $0.ageBlocked = true; $0.ageGateBirthDate = nil; $0.birth = nil }
            return false
        }
        update {
            $0.ageGateBirthDate = date
            $0.legalAccepted = true
            $0.birth = BirthInput(date: date, time: time, latitude: place.latitude, longitude: place.longitude, zoneId: place.zoneId,
                                  placeName: place.name)
        }
        return true
    }

    func finishOnboarding() {
        update { $0.onboardingComplete = true; $0.onboardingStep = nil; $0.landOnChart = true }
    }

    // ------------------------------------------------------------------ Instagram (SPEC 5d.6)

    /// Saves a typed / pasted handle or profile link; false (nothing saved) when it is not valid.
    @discardableResult
    func setInstagram(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            update { $0.instagram = nil }
            return true
        }
        guard let handle = InstagramHandle.normalize(trimmed) else { return false }
        update { $0.instagram = handle }
        return true
    }

    // ------------------------------------------------------------------ owner photo

    /// Saves an already cropped profile JPEG (512 x 640, <= 60 KB, no EXIF).
    func setPhoto(jpeg: Data) {
        AppFiles.write(jpeg, to: AppFiles.ownerPhotoURL)
        PhotoCache.shared.invalidate(AppFiles.ownerPhotoURL)
        update { $0.photoFile = "profile_photo.jpg" }
        // The file name does not change: re-apply so the card picks up the new picture.
        applyNearby()
    }

    func removePhoto() {
        try? FileManager.default.removeItem(at: AppFiles.ownerPhotoURL)
        PhotoCache.shared.invalidate(AppFiles.ownerPhotoURL)
        update { $0.photoFile = nil }
    }

    var ownerPhotoURL: URL? { profile.photoFile == nil ? nil : AppFiles.ownerPhotoURL }

    // ------------------------------------------------------------------ Delete all my data

    /// Full erasure on this phone: Nearby (friends, chats, photos, identity key), readings,
    /// consents, the daily reminder and the profile. Returns to onboarding.
    func deleteEverything() {
        nearby.deleteAll()
        Notifications.setDailyReminder(false, at: profile.dailyReadingTime)
        readings.clear()
        PhotoCache.shared.removeAll()
        // The Nearby store clears itself on its queue first; then the folder goes.
        nearby.node.post {
            DispatchQueue.main.async { AppFiles.eraseAll() }
        }
        consents = []
        legalUpdate = .none
        nearbySheet = false
        profile = OwnerProfile()
        charts = nil
    }
}
