import Foundation
import XCTest
@testable import ConjunctionCore

/// SPEC 5d (UX 2.0) logic the iOS app relies on: legal versions and re-acceptance, the L1/L2/L3
/// texts, the owner-profile migration (no re-onboarding), the stored peer data of matches and
/// friends (chart, reading, Instagram) and what unmatch / block delete, and the Compatibility view.
/// The Android twins are `LegalStepInputTest`, `UxLogicTest` and `ProfileStoreTest`.
final class UX2Tests: XCTestCase {
    private func rec(_ key: String, _ version: String, withdrawn: Bool = false, at: String = "2026-11-03T10:00:00Z") -> ConsentRecord {
        ConsentRecord(key: key, version: version, acceptedAt: at, appVersion: "1.0.0", platform: "ios", textSha256: "x",
                      withdrawn: withdrawn)
    }

    private let bundled: [LegalDoc: String] = [.waiver: "2.1", .terms: "2.1", .privacy: "2.1", .community: "2.0", .safety: "2.1"]

    // ------------------------------------------------------------------ legal (5d.1)

    func testExistingUserSeesOneNoticeForAMinorBumpAndReacceptForAMajorOne() {
        // L-8: a 2.0 user (no Community Guidelines record: covered by the Terms).
        var records = [rec("WAIVER", "2.0"), rec("TERMS", "2.0"), rec("PRIVACY", "2.0"), rec("AGE_18_CONFIRMED", "2.0")]
        XCTAssertEqual(LegalVersions.evaluate(records: records, bundled: bundled), .notice([.terms, .waiver, .privacy]))
        for doc in [LegalDoc.terms, .waiver, .privacy] { records.append(rec(ConsentKeys.noticeSeen(doc), "2.1")) }
        XCTAssertEqual(LegalVersions.evaluate(records: records, bundled: bundled), .none, "the notice shows once")
        // A simulated major bump asks for re-acceptance instead.
        var major = bundled
        major[.terms] = "3.0"
        XCTAssertEqual(LegalVersions.evaluate(records: records, bundled: major), .reaccept([.terms, .community]))
        // Never accepted while onboarding is complete -> re-accept.
        XCTAssertEqual(LegalVersions.evaluate(records: [], bundled: bundled), .reaccept([.terms, .waiver, .community]))
        // A fresh 2.1 acceptance needs nothing.
        let fresh = [rec("WAIVER", "2.1"), rec("TERMS", "2.1"), rec("PRIVACY", "2.1"), rec("COMMUNITY_GUIDELINES", "2.0")]
        XCTAssertEqual(LegalVersions.evaluate(records: fresh, bundled: bundled), .none)
        // A withdrawn record does not count.
        XCTAssertEqual(LegalVersions.evaluate(records: fresh + [rec("TERMS", "2.1", withdrawn: true)], bundled: bundled),
                       .reaccept([.terms]))
    }

    func testPrivacyPolicyChangesAreAlwaysANotice() {
        // Read, not agreed (GDPR Art. 7(2)): even a major Privacy change is a notice, never re-acceptance.
        let seen = [rec("WAIVER", "2.1"), rec("TERMS", "2.1"), rec("COMMUNITY_GUIDELINES", "2.0"),
                    rec(ConsentKeys.noticeSeen(.privacy), "2.1"), rec("AGE_18_CONFIRMED", "2.1")]
        XCTAssertEqual(LegalVersions.evaluate(records: seen, bundled: bundled), .none)
        var major = bundled
        major[.privacy] = "3.0"
        XCTAssertEqual(LegalVersions.evaluate(records: seen, bundled: major), .notice([.privacy]))
        XCTAssertEqual(LegalVersions.evaluate(records: seen + [rec(ConsentKeys.noticeSeen(.privacy), "3.0")], bundled: major), .none)
        let r = ConsentRecord.now("TERMS", version: "2.1", displayedText: "x", appVersion: "1", docSha256: "abc")
        XCTAssertEqual(r.docSha256, "abc")
        let old = try? JSONDecoder().decode(ConsentRecord.self, from: Data(#"{"key":"TERMS","version":"2.0","acceptedAt":"2026-11-01T00:00:00Z","appVersion":"1","platform":"ios","textSha256":"x","withdrawn":false}"#.utf8))
        XCTAssertNotNil(old, "records written before docSha256 still decode")
        XCTAssertNil(old?.docSha256)
    }

    func testClickwrapAndNearbyConsentState() {
        let migrated = [rec("WAIVER", "2.0"), rec("TERMS", "2.0"), rec("PRIVACY", "2.0"), rec("AGE_18_CONFIRMED", "2.0")]
        XCTAssertTrue(LegalVersions.clickwrapAlreadyAccepted(records: migrated, bundled: bundled), "O-7: no L1 for a migrated user")
        XCTAssertFalse(LegalVersions.clickwrapAlreadyAccepted(records: Array(migrated.prefix(3)), bundled: bundled))
        XCTAssertFalse(LegalVersions.clickwrapAlreadyAccepted(records: [], bundled: bundled))

        var l2 = [rec(ConsentKeys.nearbyBluetooth, "2.0", at: "2026-11-01T09:00:00Z"),
                  rec(ConsentKeys.orientationData, "2.0", at: "2026-10-30T09:00:00Z")]
        XCTAssertTrue(LegalVersions.nearbyConsentGiven(l2), "2.0 consents stay valid")
        XCTAssertEqual(LegalVersions.nearbyConsentAt(l2), "2026-11-01T09:00:00Z")
        XCTAssertEqual(LegalVersions.recordDate("2026-11-04T10:00:00Z", zone: TimeZone(identifier: "UTC")!), "4 Nov 2026")
        l2.append(rec(ConsentKeys.orientationData, "2.1", withdrawn: true))
        XCTAssertFalse(LegalVersions.nearbyConsentGiven(l2))
        XCTAssertNil(LegalVersions.nearbyConsentAt(l2))
    }

    func testL1L2L3TextsAreTheFormattedCopy() {
        XCTAssertEqual(ConsentKeys.copyVersion, "2.1")
        XCTAssertTrue(L10n.copyDocumentVersion.hasPrefix("2.1"))
        XCTAssertEqual(Clickwrap.checkboxText,
                       "I'm 18+ and agree to the Terms, Community Guidelines and Waiver, including a release of claims and individual arbitration.")
        XCTAssertEqual(Clickwrap.privacyText, "See how your data stays on your phone: Privacy Policy")
        let r = ConsentRecord.now(LegalDoc.terms.key, version: "2.1", displayedText: Clickwrap.checkboxText, appVersion: "1.0.0")
        XCTAssertEqual(r.textSha256, LegalText.sha256Hex(Clickwrap.checkboxText), "L-3: one hash of the formatted line")
        XCTAssertEqual(Clickwrap.nearbySheetText.split(separator: "\n").count, 6)
        XCTAssertTrue(Clickwrap.nearbySheetText.hasPrefix(L10n.nearbyTitle))
        XCTAssertTrue(Clickwrap.bloodShareText.contains("health data"))
        XCTAssertEqual(LegalDoc.clickwrap.map(\.key), ["WAIVER", "TERMS", "COMMUNITY_GUIDELINES"])
        XCTAssertEqual(LegalDoc.settingsOrder, [.terms, .waiver, .privacy, .community, .safety])
    }

    func testRetiredConsentStringsAreGoneFromTheApp() throws {
        // L-1: the retired keys (IN_APP_CONSENT_COPY 2.1 "Retired in 2.1") are not referenced by the iOS app.
        let repo = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let appDir = repo.appendingPathComponent("App/Conjunction")
        var source = ""
        let files = FileManager.default.enumerator(at: appDir, includingPropertiesForKeys: nil)
        while let url = files?.nextObject() as? URL {
            if url.pathExtension == "swift" { source += (try? String(contentsOf: url, encoding: .utf8)) ?? "" }
        }
        XCTAssertFalse(source.isEmpty)
        for retired in ["legalSummary1", "legalCheckboxAge", "legalCheckboxAgree", "honestyCheckbox", "ageGateBody",
                        "orientationConsentBody", "orientationConsentCheckbox", "nearbyBody", "permBluetoothTitle",
                        "chatKeepNote", "photoBody", "photoTip", "legalTitle", "nearbyCheckbox", "permNotificationsDriving"] {
            XCTAssertFalse(source.contains("L10n.\(retired)"), "\(retired) is retired in consent copy 2.1")
        }
        for gone in ["ReacceptScreen", "LegalStep", "AgeGateStep", "PermissionRationaleSheet(title: L10n.permBluetooth"] {
            XCTAssertFalse(source.contains(gone), gone)
        }
    }

    // ------------------------------------------------------------------ owner profile migration (5d.1.4)

    func testOldProfileFileDecodesWithoutReOnboarding() throws {
        // An onboarded 2.0 profile with the dating object, both consents and Nearby on.
        let json = """
        {"ageGateBirthDateText":"1996-03-12","legalAccepted":true,"onboardingComplete":true,"nearbyEnabled":true,
         "nearbyConsent":true,"orientationConsent":true,"honestyConfirmed":true,"displayName":"Aurora",
         "birth":{"dateText":"1996-03-12","timeText":"06:40","latitude":48.85,"longitude":2.35,"zoneId":"Europe/Paris","placeName":"Paris"},
         "dating":{"gender":"female","seeking":"both","relationshipStatus":"single","lookingFor":"both"}}
        """
        let p = try JSONDecoder().decode(OwnerProfile.self, from: Data(json.utf8))
        XCTAssertNil(p.resumeScreen, "an onboarded user never sees onboarding again")
        XCTAssertEqual(p.dating, DatingProfile(gender: .female, seeking: .both, relationshipStatus: .single, lookingFor: .both))
        XCTAssertTrue(p.nearbyConsentGiven)
        XCTAssertTrue(p.nearbyEligible)
        XCTAssertTrue(p.nearbyShouldRun(nowMs: 0), "Nearby stays on")
        XCTAssertFalse(p.nearbyNeedsSheet)
        XCTAssertTrue(p.instagramShareDefault)
        XCTAssertEqual(p.dailyReadingTime, LocalTime(8, 0))
        // Written back in the new shape (one field each) and read again unchanged.
        let again = try JSONDecoder().decode(OwnerProfile.self, from: JSONEncoder().encode(p))
        XCTAssertEqual(again, p)

        // Mid-onboarding users resume at the screen with their first missing item.
        var mid = OwnerProfile()
        mid.onboardingStep = OnboardingScreen.card.rawValue
        XCTAssertEqual(mid.resumeScreen, .card)
        mid.onboardingStep = "NOTIFICATIONS"
        XCTAssertEqual(mid.resumeScreen, .welcome)

        // Only some dating fields (onboarding screen 3 skipped parts): the sheet collects the rest.
        var partial = OwnerProfile()
        partial.gender = .male
        partial.displayName = "Sam"
        XCTAssertNil(partial.dating)
        XCTAssertTrue(partial.nearbyNeedsSheet)
        let decoded = try JSONDecoder().decode(OwnerProfile.self, from: JSONEncoder().encode(partial))
        XCTAssertEqual(decoded.gender, .male)
        XCTAssertNil(decoded.seeking)

        // A stored handle is normalised on read; junk is dropped.
        let ig = try JSONDecoder().decode(OwnerProfile.self, from: Data(#"{"instagram":"@Aurora.Stars"}"#.utf8))
        XCTAssertEqual(ig.instagram, "aurora.stars")
    }

    // ------------------------------------------------------------------ stored peer data (5d.4 / 5d.6)

    private let peer = try! WireCompat(sun: 35193, moon: 25565, venus: 3680, mars: 35030, asc: 32195, animal: .rat, stem: .wu,
                                       blood: .o, birthDate: LocalDate(1996, 3, 12), birthHour: 6)
    private let me = try! WireCompat(sun: 6020, moon: 14000, venus: 7200, mars: 9000, asc: 12000, animal: .monkey, stem: .ren,
                                     blood: nil, birthDate: LocalDate(1992, 5, 21), birthHour: 9)

    private func friend(_ id: String, peerCompat: WireCompat? = nil) -> StoredFriend {
        StoredFriend(id: id, identityKey: "ik", friendSecret: "fs", safetyCode: "12345", kind: "dating", firstName: "Aurora",
                     bio: nil, sun: "pisces", moon: "sagittarius", rising: nil, chineseAnimal: "rat", photoFile: nil,
                     photoScreening: .safe, since: 1, lastSeen: 2, peerCompat: peerCompat,
                     score: StoredScore(total: 87, western: 80, chinese: 90, blood: nil, highlights: ["Sun–Venus trine"],
                                        birthdayTwin: false, exactTwin: false),
                     blurb: StoredBlurb(text: "Two stars.", source: "fallback", forHash: "h"), peerIg: "aurora.stars", peerIgRev: 3,
                     igShareOn: false, igState: InstagramShareState(value: "vega", rev: 2, ackedRev: 2))
    }

    private func encounter(_ fp: String, friendId: String? = nil, mySwipe: String? = nil, created: Int64 = 100) -> StoredEncounter {
        StoredEncounter(fingerprint: fp, kind: "dating", total: 87, western: 80, chinese: 90, blood: nil, highlights: [],
                        birthdayTwin: false, exactTwin: false, age: 30, sun: "pisces", moon: "sagittarius", rising: nil,
                        chineseAnimal: "rat", relationship: "single", lookingFor: "both", firstName: "Aurora", mySwipe: mySwipe,
                        friendId: friendId, createdAt: created, expiresAt: 1_000_000, peerCompat: peer, chineseRelation: "TRINE",
                        stemRelation: "GENERATING")
    }

    func testStoreKeepsPeerDataAndOldFilesStillDecode() throws {
        let state = P2PState(friends: [friend("f1", peerCompat: peer)], encounters: [encounter("e1")])
        let decoded = try XCTUnwrap(P2PStore.decode(P2PStore.encode(state)))
        XCTAssertEqual(decoded, state)
        XCTAssertEqual(decoded.friends[0].peerCompat, peer)
        XCTAssertEqual(decoded.encounters[0].chineseRelation, "TRINE")
        // A friend record from before UX 2.0 decodes with none of it (C-4 placeholder).
        let old = #"{"v":1,"friends":[{"id":"f0","ik":"a","fs":"b","name":"Vega"}],"encounters":[],"messages":[],"hidden":[]}"#
        let f = try XCTUnwrap(P2PStore.decode(Data(old.utf8))?.friends.first)
        XCTAssertNil(f.peerCompat)
        XCTAssertNil(f.score)
        XCTAssertEqual(f.peerIgRev, -1)
        XCTAssertNil(f.igShareOn)
        // A broken chart is dropped, not the friend.
        let broken = #"{"friends":[{"id":"f0","ik":"a","fs":"b","name":"Vega","peerCompat":{"sun":99999}}]}"#
        XCTAssertEqual(P2PStore.decode(Data(broken.utf8))?.friends.first?.firstName, "Vega")
    }

    func testUnmatchAndBlockDeleteWhatThisPhoneKept() {
        var s = P2PState(friends: [friend("f1", peerCompat: peer), friend("f2", peerCompat: peer)],
                         messages: [ChatLine(id: "m", friendId: "f1", mine: true, text: "hi", ts: 1, status: .sent)],
                         encounters: [encounter("e1", friendId: "f1"), encounter("e2", friendId: "f2")])
        s.blockFriend("f1")
        let blocked = s.friend("f1")!
        XCTAssertTrue(blocked.blocked)
        XCTAssertNil(blocked.peerCompat)
        XCTAssertNil(blocked.peerIg)
        XCTAssertNil(blocked.blurb)
        XCTAssertNil(blocked.igState)
        XCTAssertTrue(s.messages.isEmpty)
        XCTAssertNil(s.encounter("e1"))
        XCTAssertTrue(s.isHidden("e1", now: 0))
        XCTAssertNil(s.compatSubject("f1", now: 0), "no compatibility view for a blocked friend")
        s.unmatch("f2", hiddenUntil: 500)
        XCTAssertNil(s.friend("f2"))
        XCTAssertNil(s.encounter("e2"))
        XCTAssertTrue(s.isHidden("e2", now: 400))
        XCTAssertFalse(s.isHidden("e2", now: 600))
    }

    func testListsCompatSubjectsAndInstagramSwitch() {
        var s = P2PState(friends: [friend("f1", peerCompat: peer)],
                         encounters: [encounter("new1", created: 300), encounter("new2", created: 200),
                                      encounter("wait", mySwipe: "right"), encounter("old", friendId: "f1")])
        XCTAssertEqual(s.inbox(now: 0).map(\.fingerprint), ["new1", "new2"])
        XCTAssertEqual(s.pendingCount(now: 0), 2)
        XCTAssertEqual(s.waiting(now: 0).map(\.fingerprint), ["wait"])
        XCTAssertEqual(s.compatSubject("wait", now: 0)?.name, "Aurora")
        XCTAssertEqual(s.compatSubject("wait", now: 0)?.peerCompat, peer)
        XCTAssertNil(s.compatSubject("wait", now: 2_000_000), "a waiting card lasts 24 h")
        XCTAssertEqual(s.compatSubject("old", now: 0)?.friendId, "f1", "a matched card opens the friend")
        XCTAssertEqual(s.compatSubject("f1", now: 0)?.peerIg, "aurora.stars")
        XCTAssertEqual(s.friendSummaries(inRange: []).first?.total, 87)
        XCTAssertEqual(s.friendSummaries(inRange: []).first?.igShareOn(default: true), false)
        s.setInstagramShare("f1", on: true)
        XCTAssertEqual(s.friend("f1")?.igShareOn, true)
        s.saveBlurb("wait", StoredBlurb(text: "R", source: "model", forHash: "x"))
        XCTAssertEqual(s.encounter("wait")?.blurb?.source, "model")
        s.hideStranger("new1", until: 50, reason: "snooze")
        XCTAssertEqual(s.snoozed(now: 0).map(\.fingerprint), ["new1"])
        XCTAssertTrue(s.snoozed(now: 60).isEmpty)
    }

    // ------------------------------------------------------------------ compatibility view (5d.4)

    func testCompatViewRecomputesFromBothChartsElseUsesTheStoredNumbers() throws {
        let mine = me.toCompatProfile()
        let s = P2PState(friends: [friend("f1", peerCompat: peer), friend("f0")])
        let full = CompatView.of(try XCTUnwrap(s.compatSubject("f1", now: 0)), me: mine)
        let expected = Compatibility.score(mine, peer.toCompatProfile())
        XCTAssertTrue(full.hasWheel)
        XCTAssertEqual(full.score.total, expected.total, "recomputed, not the stored 87")
        XCTAssertEqual(full.lines, Compatibility.synastry(mine, peer.toCompatProfile()))
        XCTAssertEqual(full.chineseRelation, expected.chineseRelation)
        XCTAssertLessThanOrEqual(full.aspectRows.count, 8)

        let pending = CompatView.of(try XCTUnwrap(s.compatSubject("f0", now: 0)), me: mine)
        XCTAssertFalse(pending.hasWheel, "C-4: placeholder until the next verified link")
        XCTAssertEqual(pending.score.total, 87)
        XCTAssertEqual(pending.chineseRelation, Compatibility.chineseRelation(.monkey, .rat))

        let line = Compatibility.SynastryLine(pointA: .venus, pointB: .mars, aspect: Compatibility.aspects.first { $0.name == "trine" }!,
                                              orbCentideg: 210, weight: 3, score: 10)
        XCTAssertEqual(CompatView.aspectRow(line), "Your Venus △\u{FE0E} their Mars — trine, 2.1°")
        XCTAssertEqual(CompatView.blurbHash(me: mine, them: peer.toCompatProfile(), kind: "dating").count, 32)
        XCTAssertNotEqual(CompatView.blurbHash(me: mine, them: peer.toCompatProfile(), kind: "dating"),
                          CompatView.blurbHash(me: mine, them: peer.toCompatProfile(), kind: "friends"))
        XCTAssertEqual(CompatView.points(mine).count, 5)
        XCTAssertEqual(CompatView.chineseRelationLabel(.trine), "Trine allies (San He)")
    }

    func testInstagramLinkIsAPlainHttpsProfileURL() {
        // I-5: the chip opens https://www.instagram.com/<handle>/ (universal link -> app, else Safari).
        XCTAssertEqual(InstagramHandle.profileURL("aurora.stars"), "https://www.instagram.com/aurora.stars/")
        XCTAssertEqual(InstagramHandle.normalize("See my profile https://instagram.com/_u/Aurora.Stars"), "aurora.stars")
        XCTAssertNil(InstagramHandle.normalize("instagram.com/p/abc"))
    }

    func testUIStringsUseTheSharedWording() {
        XCTAssertEqual(L10n.format(AppText.tabNearbyBadgeDesc, "3"), "Nearby, 3 new matches")
        XCTAssertEqual(L10n.format(AppText.compatTitle, "Aurora"), "You & Aurora")
        XCTAssertEqual(L10n.format(AppText.compatWheelPending, "Aurora"), "Your chart together appears the next time you're near Aurora.")
        XCTAssertEqual(L10n.format(L10n.instagramShareWithMatch, "Aurora"), "Share my Instagram with Aurora")
        XCTAssertEqual(AppText.onbWelcomeStart, "Get started")
        XCTAssertEqual(L10n.legalBegin, "Begin")
    }
}

/// O-5 geometry: the face-centred 4:5 crop (Android `ProfilePhotoCrop.cropFor`).
final class PhotoCropGeometryTests: XCTestCase {
    func testFaceCentredCropAndCentreCrop() {
        let face = PhotoCropGeometry.Face(cx: 600, cy: 500, height: 280)
        let c = PhotoCropGeometry.cropFor(width: 1200, height: 1600, face: face)
        XCTAssertEqual(c.width / c.height, 0.8, accuracy: 1e-9)
        XCTAssertEqual(c.height, 800, accuracy: 1e-9, "face is 35% of the crop height")
        XCTAssertEqual(c.x + c.width / 2, 600, accuracy: 1e-9)
        XCTAssertEqual(c.y + c.height * 0.4, 500, accuracy: 1e-9, "face centre 40% from the top")
        // Near an edge the crop stays inside the image.
        let edge = PhotoCropGeometry.cropFor(width: 1200, height: 1600, face: .init(cx: 30, cy: 20, height: 280))
        XCTAssertEqual(edge.x, 0)
        XCTAssertEqual(edge.y, 0)
        // No face: the largest centred 4:5 crop.
        let centre = PhotoCropGeometry.cropFor(width: 2000, height: 1000, face: nil)
        XCTAssertEqual(centre.height, 1000)
        XCTAssertEqual(centre.width, 800)
        XCTAssertEqual(centre.x, 600)
        let moved = PhotoCropGeometry.moved(centre, width: 2000, height: 1000, frameWidth: 400, dx: 100, dy: 0, zoom: 2)
        XCTAssertEqual(moved.width, 400, accuracy: 1e-9)
        XCTAssertEqual(moved.height, 500, accuracy: 1e-9)
        XCTAssertEqual(PhotoCropGeometry.qualitySteps.first, 0.85)
        XCTAssertEqual(PhotoCropGeometry.qualitySteps.last, 0.40)
    }
}
