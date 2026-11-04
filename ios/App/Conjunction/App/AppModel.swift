import Foundation
import SwiftUI
import Combine
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
    /// Accepted legal documents with a newer bundled version (re-acceptance screen).
    @Published private(set) var changedLegalDocs: [LegalDoc] = []

    let readings = ReadingService()
    let nearby = NearbyRepository()
    private var chartsTask: Task<Void, Never>?

    private init() {
        let stored = AppFiles.read(OwnerProfile.self, from: AppFiles.profileURL) ?? OwnerProfile()
        profile = stored
        consents = AppFiles.read([ConsentRecord].self, from: AppFiles.consentsURL) ?? []
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
        if p.dailyReminder != old.dailyReminder { Notifications.setDailyReminder(p.dailyReminder) }
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

    // ------------------------------------------------------------------ consents

    func record(_ key: String, version: String, text: String, withdrawn: Bool = false) {
        consents.append(ConsentRecord.now(key, version: version, displayedText: text, appVersion: AppInfo.version, withdrawn: withdrawn))
        AppFiles.write(consents, to: AppFiles.consentsURL)
        refreshLegal()
    }

    func recordDocument(_ text: LegalText) { record(text.doc.key, version: text.version, text: text.markdown) }

    /// Accepted documents whose bundled version is newer than the accepted one.
    private func refreshLegal() {
        var bundled: [LegalDoc: String] = [:]
        for doc in LegalDoc.accepted { bundled[doc] = LegalTexts.load(doc)?.version }
        let changed = LegalVersions.changed(records: consents, bundled: bundled)
        if changed != changedLegalDocs { changedLegalDocs = changed }
    }

    // ------------------------------------------------------------------ Nearby switch

    /// Turns Nearby on (consent and the Bluetooth rationale already handled by the UI).
    func enableNearby() throws {
        guard profile.nearbyEligible else { throw ModelFailure("Finish your dating & friends profile first.") }
        guard profile.nearbyConsent else { throw ModelFailure("Turn on Nearby from the Nearby screen first.") }
        update { $0.nearbyEnabled = true; $0.nearbyPausedUntil = 0 }
    }

    func disableNearby() { update { $0.nearbyEnabled = false; $0.nearbyPausedUntil = 0 } }

    func pauseNearby(until ms: Int64) { update { $0.nearbyPausedUntil = ms } }

    func resumeNearby() { update { $0.nearbyPausedUntil = 0 } }

    // ------------------------------------------------------------------ owner photo

    func setPhoto(_ data: Data) -> String {
        guard let jpeg = PhotoCodec.profileJPEG(from: data) else { return "The photo could not be used." }
        AppFiles.write(jpeg, to: AppFiles.ownerPhotoURL)
        update { $0.photoFile = "profile_photo.jpg" }
        // The file name does not change: re-apply so the card picks up the new picture.
        applyNearby()
        PhotoCache.shared.invalidate(AppFiles.ownerPhotoURL)
        return "Photo saved on this phone."
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
        Notifications.setDailyReminder(false)
        readings.clear()
        PhotoCache.shared.removeAll()
        // The Nearby store clears itself on its queue first; then the folder goes.
        nearby.node.post {
            DispatchQueue.main.async { AppFiles.eraseAll() }
        }
        consents = []
        changedLegalDocs = []
        profile = OwnerProfile()
        charts = nil
    }
}
