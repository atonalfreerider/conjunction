import Foundation
import XCTest
@testable import ConjunctionCore

/// The iOS app's Nearby rules that need no radio: proximity gate, reconnect cool-downs, chat-text
/// rules, the model's verdict, notification and report text, card fields, string formatting.
final class NearbyLogicTests: XCTestCase {
    func testProximityGateNeedsTwoStrongSightingsInThirtySeconds() {
        var g = ProximityGate()
        XCTAssertFalse(g.observe("p", rssi: -70, nowMs: 0))
        XCTAssertTrue(g.observe("p", rssi: -75, nowMs: 10_000))
        XCTAssertEqual(g.closeBy(nowMs: 10_000), 1)
        // Weak or unavailable readings never count.
        XCTAssertFalse(g.observe("q", rssi: -81, nowMs: 0))
        XCTAssertFalse(g.observe("q", rssi: -90, nowMs: 1_000))
        XCTAssertFalse(g.observe("r", rssi: 127, nowMs: 0))
        XCTAssertFalse(g.observe("r", rssi: 127, nowMs: 1_000))
        // Sightings older than 30 s fall out of the window.
        XCTAssertFalse(g.observe("s", rssi: -60, nowMs: 0))
        XCTAssertFalse(g.observe("s", rssi: -60, nowMs: 31_000))
        g.prune(nowMs: 100_000)
        XCTAssertEqual(g.closeBy(nowMs: 100_000), 0)
    }

    func testReconnectCooldownsAndBackoff() {
        var r = ReconnectPolicy()
        XCTAssertTrue(r.mayConnect("a", nowMs: 0))
        r.finished("a", outcome: .noMatch, nowMs: 0)
        XCTAssertFalse(r.mayConnect("a", nowMs: 9 * 60_000))
        XCTAssertTrue(r.mayConnect("a", nowMs: 10 * 60_000))
        r.finished("b", outcome: .error, nowMs: 0)
        XCTAssertTrue(r.mayConnect("b", nowMs: 120_000))
        r.failed("c", nowMs: 0)
        XCTAssertTrue(r.mayConnect("c", nowMs: 5_000))
        r.failed("c", nowMs: 5_000)
        XCTAssertFalse(r.mayConnect("c", nowMs: 14_000))
        XCTAssertTrue(r.mayConnect("c", nowMs: 15_000))
        for _ in 0..<10 { r.failed("c", nowMs: 0) }
        XCTAssertTrue(r.mayConnect("c", nowMs: 120_000), "backoff is capped at 120 s")
        r.finished("d", outcome: .done, nowMs: 0)
        r.expedite(nowMs: 1)
        XCTAssertTrue(r.mayConnect("d", nowMs: 1), "expedite lifts finished cool-downs")
    }

    func testChatTextRules() {
        for bad in ["call me 415 555 0100", "mail me at a.b@example.com", "see www.example.com", "add me on snapchat",
                    "send me money via venmo", "send nudes", "i know where you live", "go to t.me/x"] {
            XCTAssertTrue(TextHeuristics.flags(bad), bad)
        }
        for ok in ["Hi! Lovely to meet you ☌", "Coffee on Saturday at 3?", "My moon is in Leo", "I was born in 1996"] {
            XCTAssertFalse(TextHeuristics.flags(ok), ok)
        }
        XCTAssertTrue(TextHeuristics.textPrompt("hello").contains("<<<\nhello\n>>>"))
    }

    func testModelVerdictParsing() {
        XCTAssertEqual(TextHeuristics.parseVerdict("SAFE"), .safe)
        XCTAssertEqual(TextHeuristics.parseVerdict("flag."), .flagged)
        XCTAssertEqual(TextHeuristics.parseVerdict("FLAG, not SAFE"), .flagged)
        XCTAssertEqual(TextHeuristics.parseVerdict("Safe"), .safe)
        XCTAssertNil(TextHeuristics.parseVerdict("unsafe-ish"))
        XCTAssertNil(TextHeuristics.parseVerdict(nil))
    }

    func testFormatFillsAndroidPlaceholders() {
        XCTAssertEqual(L10n.format(L10n.notifMatchDating, "Leo", "87"), "A Leo ☌ is nearby — 87% compatible. Tap to see.")
        XCTAssertEqual(L10n.format(L10n.chatUnmatchConfirm, "Vega"),
                       "Unmatch Vega? The chat is deleted from this phone. Vega keeps their copy.")
        XCTAssertEqual(L10n.format("%s and %d%%", "a", "5"), "a and 5%")
        XCTAssertEqual(L10n.format("100% sure"), "100% sure")
    }

    func testNotificationTexts() {
        let match = NearbyEvent(kind: .match, encounterId: "fp", friendId: nil, matchKind: "dating", total: 87)
        XCTAssertEqual(NearbyText.notification(match, peerName: nil, sunSign: "Leo").body,
                       "A Leo ☌ is nearby — 87% compatible. Tap to see.")
        let friends = NearbyEvent(kind: .match, encounterId: "fp", friendId: nil, matchKind: "friends", total: 70)
        XCTAssertEqual(NearbyText.notification(friends, peerName: nil, sunSign: nil).body,
                       "A potential kindred friend is nearby — 70% compatible.")
        XCTAssertEqual(NearbyText.notification(NearbyEvent(kind: .message, encounterId: nil, friendId: "f"),
                                               peerName: " ", sunSign: nil).body, "New message from your match")
        XCTAssertEqual(NearbyText.notification(NearbyEvent(kind: .conjunction, encounterId: nil, friendId: "f"),
                                               peerName: "Vega", sunSign: nil).body,
                       "It's a Conjunction! You and Vega both said yes. Say hi.")
    }

    func testEventUserInfoRoundTrip() {
        let e = NearbyEvent(kind: .exactTwin, encounterId: "fp", friendId: nil, matchKind: "dating", total: 91)
        XCTAssertEqual(NearbyEvent.fromUserInfo(e.userInfo), e)
        XCTAssertNil(NearbyEvent.fromUserInfo(["x": "y"]))
        XCTAssertEqual(e.notificationId, "match:fp")
        XCTAssertEqual(NearbyEvent(kind: .message, encounterId: nil, friendId: "f").notificationId, "msg:f")
    }

    func testCardFields() {
        XCTAssertEqual(NearbyText.cardName("  ", sun: .leo), "A Leo")
        XCTAssertEqual(NearbyText.cardName(String(repeating: "é", count: 40), sun: .leo).utf8.count, 64)
        XCTAssertEqual(NearbyText.trimUTF8("a☌b", 3), "a")
        XCTAssertEqual(NearbyText.cardBio("First line\nsecond"), "First line")
        XCTAssertNil(NearbyText.cardBio("\nsecond"))
        XCTAssertEqual(NearbyText.firstLine("a\r\nb"), "a")
        XCTAssertEqual(CardText.signsLine(sun: "leo", moon: "aries", rising: nil), "☉ Leo  ☽ Aries")
    }

    func testReportBody() {
        let subject = NearbyText.ReportSubject(firstName: "Vega", age: 34, sun: "gemini", moon: "cancer", rising: nil,
                                               matchKind: "dating", localId: "abcd", identityFingerprint: nil, hasPhoto: true)
        let messages = [ChatLine(id: "1", friendId: "f", mine: false, text: "hey", ts: 0, status: .received)]
        let body = NearbyText.reportBody(subject, reason: .scam, description: "", messages: messages,
                                         appLine: "App: Conjunction 1.0.0 (1), iOS 26.0", zone: TimeZone(identifier: "UTC")!)
        XCTAssertTrue(body.hasPrefix("Reason: Scam, money or spam\n\nWhat happened:\n(not given)\n"))
        XCTAssertTrue(body.contains("  Age: 34\n"))
        XCTAssertTrue(body.contains("  [1970-01-01T00:00:00] Vega: hey\n"))
        XCTAssertTrue(body.hasSuffix("App: Conjunction 1.0.0 (1), iOS 26.0\n"))
        XCTAssertEqual(NearbyText.reportSubjectLine(.minor), "Conjunction report: child_safety")
    }

    func testInboxListsAndSummaries() {
        var s = P2PState()
        func enc(_ fp: String, created: Int64, swipe: String? = nil, friend: String? = nil) -> StoredEncounter {
            StoredEncounter(fingerprint: fp, kind: "dating", total: 80, western: 80, chinese: 80, blood: nil, highlights: [],
                            birthdayTwin: false, exactTwin: false, age: 30, sun: "leo", moon: "leo", rising: nil,
                            chineseAnimal: "rat", relationship: "single", lookingFor: "both", mySwipe: swipe,
                            friendId: friend, createdAt: created, expiresAt: created + NearbyEngine.inboxTTLMs)
        }
        s.encounters = [enc("old", created: 0), enc("new", created: 10), enc("yes", created: 5, swipe: "right"),
                        enc("hid", created: 6), enc("fr", created: 7, friend: "f1")]
        s.hidden = [HiddenPeer(fingerprint: "hid", until: 1_000_000, reason: "snooze")]
        XCTAssertEqual(s.inbox(now: 100).map(\.fingerprint), ["new", "old"])
        XCTAssertEqual(s.waiting(now: 100).map(\.fingerprint), ["yes"])
        XCTAssertTrue(s.inbox(now: NearbyEngine.inboxTTLMs + 20).isEmpty, "cards expire after 24 h")
        s.friends = [StoredFriend(id: "f1", identityKey: "", friendSecret: "", safetyCode: "1", kind: "dating", firstName: "Vega",
                                  bio: nil, sun: "leo", moon: nil, rising: nil, chineseAnimal: nil, photoFile: nil,
                                  photoScreening: .safe, since: 0, lastSeen: 0)]
        s.messages = [ChatLine(id: "m1", friendId: "f1", mine: false, text: "hi", ts: 1, status: .received),
                      ChatLine(id: "m2", friendId: "f1", mine: true, text: "yo", ts: 2, status: .queued),
                      ChatLine(id: "m3", friendId: "f1", mine: false, text: "bad", ts: 3, status: .received, screening: .flagged)]
        let sum = s.friendSummary("f1", inRange: ["f1"])!
        XCTAssertEqual(sum.unread, 2)
        XCTAssertEqual(sum.queued, 1)
        XCTAssertEqual(sum.lastMessage, "yo", "flagged text is never previewed")
        XCTAssertTrue(sum.inRange)
        s.friends[0].blocked = true
        XCTAssertNil(s.friendSummary("f1", inRange: []))
    }

    func testMessageTrimKeepsQueued() {
        var list = (0..<1001).map { ChatLine(id: "\($0)", friendId: "f", mine: true, text: "x", ts: Int64($0), status: .delivered) }
        list[0].status = .queued
        let trimmed = NearbyEngine.trim(list, friendId: "f")
        XCTAssertEqual(trimmed.count, 1000)
        XCTAssertEqual(trimmed.first?.id, "0")
        XCTAssertFalse(trimmed.contains { $0.id == "1" })
    }
}
