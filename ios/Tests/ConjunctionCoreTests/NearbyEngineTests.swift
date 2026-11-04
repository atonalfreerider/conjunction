import Foundation
import XCTest
@testable import ConjunctionCore

/// Two complete engines (core P2PLink + app logic + stores) wired back to back through in-memory
/// ports, as the CoreBluetooth transport wires them over GATT: match → card and photo → swipes →
/// keys → encrypted chat with receipts → disconnect → queued message → friend recognised on a new
/// link with fresh ephemeral ids → delivery. Also de-duplication, hide-after-left and the store
/// format. A port of the Android app's NearbyEngineTest.
final class NearbyEngineTests: XCTestCase {
    final class Host: NearbyHost {
        let profile: LocalPeerProfile
        var announced: [NearbyEvent] = []
        var logs: [String] = []
        weak var engine: NearbyEngine?
        init(_ profile: LocalPeerProfile) { self.profile = profile }
        func localProfile() -> LocalPeerProfile? { profile }
        func announce(_ event: NearbyEvent, peerName: String?, peerSun: String?) { announced.append(event) }
        // The model is "unavailable": the rules' verdict stands (safe), photos stay unchecked.
        func screenMessage(id: String, text: String) { engine?.applyMessageScreening(id: id, verdict: .safe) }
        func screenPhoto(fileName: String) { engine?.applyPhotoScreening(fileName: fileName, verdict: .unchecked) }
        func log(_ message: String) { logs.append(message) }
    }

    /// Frames travel through one FIFO, so engines are never re-entered (like the single BLE queue).
    final class Net {
        var queue: [() -> Void] = []
        func pump() {
            var n = 0
            while !queue.isEmpty {
                queue.removeFirst()()
                n += 1
                precondition(n < 100_000)
            }
        }
    }

    final class Port: LinkPort {
        let net: Net
        let label: String
        var deliver: ([UInt8]) -> Void = { _ in }
        var closedWith: LinkOutcome?
        var onClosed: () -> Void = {}
        init(_ net: Net, _ name: String) { self.net = net; label = name }
        var sent: [[UInt8]] = []
        func send(_ frames: [[UInt8]]) { sent += frames; for f in frames { net.queue.append { [unowned self] in self.deliver(f) } } }
        func close(_ outcome: LinkOutcome) { closedWith = outcome; net.queue.append { [unowned self] in self.onClosed() } }
    }

    /// A port like a real GATT queue: frames wait until `writeOne` hands the next one to the radio,
    /// so a close can find frames not yet written.
    final class SlowPort: LinkPort {
        let label: String
        var pending: [[UInt8]] = []
        var written: [[UInt8]] = []
        var discarded = 0
        var closedWith: LinkOutcome?
        init(_ name: String) { label = name }
        func send(_ frames: [[UInt8]]) { pending += frames }
        func close(_ outcome: LinkOutcome) { closedWith = outcome }
        func discardPending() { discarded += pending.count; pending.removeAll() }
        func writeOne() -> [UInt8]? {
            guard !pending.isEmpty else { return nil }
            let f = pending.removeFirst()
            written.append(f)
            return f
        }
    }

    private func messages(in frames: [[UInt8]]) throws -> [P2PMessage] {
        var r = Reassembler()
        return try frames.compactMap { try r.accept($0, nowMs: 0) }.map { try P2PMessages.decode($0).message }
    }

    private func helloEid(_ frames: [[UInt8]]) throws -> String {
        for m in try messages(in: frames) { if case .hello(let h) = m { return h.eid } }
        XCTFail("no hello")
        return ""
    }

    var now: Int64 = 1_762_171_200_000
    let today = LocalDate(2026, 11, 3)
    var dir: URL!
    var a: NearbyEngine!
    var b: NearbyEngine!
    var storeA: P2PStore!
    var storeB: P2PStore!
    var hostA: Host!
    var hostB: Host!
    var ports: [Port] = []

    static let auroraPhoto: [UInt8] = (0..<3000).map { UInt8($0 % 251) }

    let aurora = LocalPeerProfile(
        match: MatchProfile(gender: .female, seeking: .both, relationshipStatus: .single, lookingFor: .both),
        compat: try! WireCompat(sun: 35193, moon: 25565, venus: 3680, mars: 35030, asc: 32195, animal: .rat, stem: .wu,
                                blood: .o, birthDate: LocalDate(1996, 3, 12), birthHour: 6),
        firstName: "Aurora", bio: "Stargazer.", photo: NearbyEngineTests.auroraPhoto, photoW: 480, photoH: 640)
    let vega = LocalPeerProfile(
        match: MatchProfile(gender: .male, seeking: .female, relationshipStatus: .single, lookingFor: .both),
        compat: try! WireCompat(sun: 6020, moon: 14000, venus: 7200, mars: 9000, asc: 12000, animal: .monkey, stem: .ren,
                                blood: nil, birthDate: LocalDate(1992, 5, 21), birthHour: 9),
        firstName: "Vega", bio: nil, photo: nil, photoW: 0, photoH: 0)

    override func setUp() {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("p2p-\(UUID().uuidString)")
        storeA = P2PStore(fileURL: dir.appendingPathComponent("a/p2p.json"), photosDirectory: dir.appendingPathComponent("a/photos"))
        storeB = P2PStore(fileURL: dir.appendingPathComponent("b/p2p.json"), photosDirectory: dir.appendingPathComponent("b/photos"))
        hostA = Host(aurora)
        hostB = Host(vega)
        a = engine(storeA, hostA)
        b = engine(storeB, hostB)
        a.start()
        b.start()
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    private func engine(_ store: P2PStore, _ host: Host) -> NearbyEngine {
        let key = SoftwareP256Key.generate()
        let e = NearbyEngine(store: store, identity: { key }, host: host, clock: { [unowned self] in self.now },
                             today: { [unowned self] in self.today })
        host.engine = e
        return e
    }

    /// [central] connects to [peripheral]; the peripheral's link opens on the first INBOX write.
    @discardableResult
    private func connect(_ net: Net, _ central: NearbyEngine, _ peripheral: NearbyEngine) -> (Int, () -> Int?) {
        let cPort = Port(net, "c"), pPort = Port(net, "p")
        ports += [cPort, pPort]
        var pId: Int?
        var cId = 0
        cPort.deliver = { f in
            if pId == nil { pId = peripheral.openLink(role: .peripheral, port: pPort, attMtu: 185) }
            if let id = pId { peripheral.onFrame(id, f) }
        }
        pPort.deliver = { f in central.onFrame(cId, f) }
        cPort.onClosed = {
            central.onDisconnected(cId)
            if let id = pId { peripheral.onDisconnected(id) }
        }
        cId = central.openLink(role: .central, port: cPort, attMtu: 185)!
        return (cId, { pId })
    }

    private func drop(_ central: NearbyEngine, _ cId: Int, _ peripheral: NearbyEngine, _ pId: Int?) {
        central.onDisconnected(cId)
        if let pId { peripheral.onDisconnected(pId) }
    }

    private func queue(_ store: P2PStore, _ friendId: String, _ text: String) {
        store.update { $0.messages.append(ChatLine(id: NearbyEngine.newMessageId(), friendId: friendId, mine: true, text: text,
                                                   ts: self.now, status: .queued)) }
    }

    func testMatchSwipeChatQueueAndFriendRecognition() throws {
        let net = Net()
        let (cId, pId) = connect(net, a, b)
        net.pump()

        // Both phones computed the same match, kept the card and announced it.
        XCTAssertEqual(storeA.state.encounters.count, 1)
        let encA = storeA.state.encounters[0], encB = storeB.state.encounters[0]
        XCTAssertEqual(encA.kind, "dating")
        XCTAssertEqual(encA.total, encB.total)
        XCTAssertEqual(encA.firstName, "Vega")
        XCTAssertEqual(encB.firstName, "Aurora")
        XCTAssertEqual(encB.age, 30)
        let photo = try XCTUnwrap(encB.photoFile)
        XCTAssertEqual([UInt8](try Data(contentsOf: storeB.photoURL(photo))), aurora.photo!)
        XCTAssertEqual(encB.photoScreening, .unchecked)
        XCTAssertEqual(hostA.announced.map(\.kind), [.match])
        XCTAssertEqual(hostB.announced.map(\.kind), [.match])

        // Mutual right swipe -> keys -> friends on both sides with the same safety code.
        a.swipe(encA.fingerprint, .right)
        net.pump()
        XCTAssertTrue(storeA.state.friends.isEmpty)
        b.swipe(encB.fingerprint, .right)
        net.pump()
        let fA = try XCTUnwrap(storeA.state.friends.first), fB = try XCTUnwrap(storeB.state.friends.first)
        XCTAssertEqual(fA.safetyCode, fB.safetyCode)
        XCTAssertEqual(fA.firstName, "Vega")
        XCTAssertEqual(fB.firstName, "Aurora")
        XCTAssertEqual(storeA.state.encounters[0].friendId, fA.id)
        XCTAssertTrue(hostA.announced.contains { $0.kind == .conjunction && $0.friendId == fA.id })
        XCTAssertEqual(a.status.friendsInRange, [fA.id])

        // Encrypted chat while in range: delivered and acknowledged.
        queue(storeA, fA.id, "Hi Vega ☌")
        a.deliver(fA.id)
        net.pump()
        XCTAssertEqual(storeB.state.messages.map(\.text), ["Hi Vega ☌"])
        XCTAssertEqual(storeA.state.messages[0].status, .delivered)
        XCTAssertEqual(storeB.state.messages[0].screening, .safe)
        XCTAssertTrue(hostB.announced.contains { $0.kind == .message && $0.friendId == fB.id })

        // A message with contact details is hidden by the rules on arrival.
        queue(storeA, fA.id, "text me on whatsapp +1 415 555 0100")
        a.deliver(fA.id)
        net.pump()
        XCTAssertEqual(storeB.state.messages.last?.screening, .flagged)

        // Out of range: the message waits.
        drop(a, cId, b, pId())
        net.queue.removeAll()
        queue(storeB, fB.id, "Saw you at the observatory!")
        b.deliver(fB.id)
        XCTAssertEqual(storeB.state.messages.last?.status, .queued)

        // 16 minutes later (new ephemeral ids), B finds A: tags recognised, keys confirmed, queue delivered.
        now += 16 * 60_000
        let net2 = Net()
        connect(net2, b, a)
        net2.pump()
        XCTAssertEqual(storeB.state.messages.last?.status, .delivered)
        XCTAssertEqual(storeA.state.messages.last?.text, "Saw you at the observatory!")
        XCTAssertEqual(storeA.state.friends.count, 1)
        XCTAssertEqual(storeA.state.encounters.count, 1, "no new match card for a friend")

        // Read receipt: A looks at the chat; B's message becomes READ.
        storeA.update { s in for i in s.messages.indices where !s.messages[i].mine { s.messages[i].seen = true } }
        a.deliver(fA.id)
        net2.pump()
        XCTAssertEqual(storeB.state.messages.last?.status, .read)

        // The store survives a restart from its file.
        a.tick()
        let reopened = P2PStore(fileURL: storeA.fileURL, photosDirectory: storeA.photosDirectory)
        XCTAssertEqual(reopened.state, storeA.state)
    }

    func testOutOfRangeSwipeIsAppliedAtNextEncounter() {
        let net = Net()
        let (cId, pId) = connect(net, a, b)
        net.pump()
        let fpOfB = storeA.state.encounters[0].fingerprint
        let fpOfA = storeB.state.encounters[0].fingerprint
        b.swipe(fpOfA, .right)
        net.pump()
        drop(a, cId, b, pId())
        a.swipe(fpOfB, .right)
        XCTAssertEqual(storeA.state.encounter(fpOfB)?.mySwipe, "right")
        now += 3 * 60_000
        let net2 = Net()
        connect(net2, a, b)
        net2.pump()
        XCTAssertEqual(storeA.state.friends.count, 1)
        XCTAssertEqual(storeB.state.friends.count, 1)
        XCTAssertEqual(hostA.announced.filter { $0.kind == .match }.count, 1, "announced once per 24 h")
    }

    func testLeftSwipeHidesThePerson() {
        let net = Net()
        let (cId, pId) = connect(net, a, b)
        net.pump()
        let fpOfB = storeA.state.encounters[0].fingerprint
        a.swipe(fpOfB, .left)
        net.pump()
        XCTAssertTrue(storeA.state.isHidden(fpOfB, now: now))
        XCTAssertTrue(storeA.state.inbox(now: now).isEmpty)
        drop(a, cId, b, pId())
        now += 5 * 60_000
        hostA.announced.removeAll()
        let net2 = Net()
        connect(net2, b, a)
        net2.pump()
        XCTAssertTrue(hostA.announced.isEmpty, "hidden person is not announced again")
    }

    func testBlockedFriendIsRefused() {
        let net = Net()
        let (cId, pId) = connect(net, a, b)
        net.pump()
        a.swipe(storeA.state.encounters[0].fingerprint, .right)
        b.swipe(storeB.state.encounters[0].fingerprint, .right)
        net.pump()
        XCTAssertEqual(storeA.state.friends.count, 1)
        drop(a, cId, b, pId())
        storeA.update { s in s.friends[0].blocked = true }
        hostA.announced.removeAll()
        now += 20 * 60_000
        let net2 = Net()
        connect(net2, b, a)
        net2.pump()
        XCTAssertTrue(hostA.announced.isEmpty)
        XCTAssertTrue(hostA.logs.contains { $0.contains("closed 'done' by us") })
        XCTAssertEqual(a.openLinkCount, 0)
    }

    func testDuplicateLinksBetweenTheSamePhonesAreDeduplicated() {
        let net = Net()
        connect(net, a, b)
        connect(net, b, a)
        net.pump()
        XCTAssertTrue((hostA.logs + hostB.logs).contains { $0.hasPrefix("dedup:") }, "a dedup bye was logged")
        XCTAssertEqual(storeA.state.encounters.count, 1)
        XCTAssertEqual(storeB.state.encounters.count, 1)
        XCTAssertEqual(hostA.announced.filter { $0.kind == .match }.count, 1)
        XCTAssertEqual(a.openLinkCount, 1)
        XCTAssertEqual(b.openLinkCount, 1)
    }

    /// Regression (OTA round 1, scenarios 3/9; Android NearbyEngineTest twin): the duplicate link's
    /// queued card / photo frames kept being written after `bye dedup`, onto the LE connection the
    /// pair shares with the kept link. Now only the bye goes out.
    func testDedupLoserWritesNothingButItsByeOverASlowConnection() throws {
        let net = Net()
        connect(net, a, b)
        net.pump()
        let eidA = try helloEid(ports[0].sent), eidB = try helloEid(ports[1].sent)
        // The duplicate whose central has the larger eid is the one closed (protocol 7.2).
        let (central, peripheral): (NearbyEngine, NearbyEngine) = eidB > eidA ? (b, a) : (a, b)
        let cPort = SlowPort("c2"), pPort = SlowPort("p2")
        let cId = central.openLink(role: .central, port: cPort, attMtu: 185)!
        var pId: Int?
        var guardCount = 0
        while !cPort.pending.isEmpty || !pPort.pending.isEmpty {
            guardCount += 1
            precondition(guardCount < 10_000)
            if let f = cPort.writeOne() {
                if pId == nil { pId = peripheral.openLink(role: .peripheral, port: pPort, attMtu: 185) }
                if let id = pId { peripheral.onFrame(id, f) }
            }
            if let f = pPort.writeOne() { central.onFrame(cId, f) }
        }
        let pHost = peripheral === a ? hostA! : hostB!
        XCTAssertTrue(pHost.logs.contains { $0.hasPrefix("dedup:") }, "the peripheral detected the duplicate")
        XCTAssertGreaterThanOrEqual(pPort.discarded, 2, "its queued hello/card/photo were dropped")
        XCTAssertEqual(try messages(in: pPort.written), [.bye(reason: "dedup")])
        XCTAssertEqual(pPort.closedWith, .dedup)
        XCTAssertEqual(cPort.closedWith, .dedup)
        XCTAssertFalse((hostA.logs + hostB.logs).contains { $0.contains("protocol error") }, "no protocol errors")
        central.onDisconnected(cId)
        if let id = pId { peripheral.onDisconnected(id) }

        // The kept link is untouched: the mutual swipe still makes friends on it.
        XCTAssertEqual(a.openLinkCount, 1)
        XCTAssertEqual(b.openLinkCount, 1)
        a.swipe(storeA.state.encounters[0].fingerprint, .right)
        b.swipe(storeB.state.encounters[0].fingerprint, .right)
        net.pump()
        XCTAssertEqual(storeA.state.friends.count, 1)
        XCTAssertEqual(storeA.state.friends.first?.safetyCode, storeB.state.friends.first?.safetyCode)
    }

    func testStopSaysByeAndRefusesNewLinks() {
        let net = Net()
        connect(net, a, b)
        net.pump()
        a.stop()
        net.pump()
        XCTAssertEqual(a.openLinkCount, 0)
        XCTAssertEqual(b.openLinkCount, 0)
        XCTAssertNil(a.openLink(role: .central, port: Port(net, "x"), attMtu: 185))
    }

    func testStoreJSONRoundTrip() {
        var s = P2PState()
        s.messages = [ChatLine(id: String(repeating: "0", count: 32), friendId: "f", mine: true, text: "hé ☌ \"q\"", ts: 5,
                               status: .sent, screening: .flagged, seen: true)]
        s.hidden = [HiddenPeer(fingerprint: "ab", until: Int64.max, reason: "block")]
        s.encounters = [StoredEncounter(fingerprint: "fp", kind: "dating", total: 87, western: 80, chinese: 90, blood: nil,
                                        highlights: ["x"], birthdayTwin: true, exactTwin: false, age: 30, sun: "leo",
                                        moon: "aries", rising: nil, chineseAnimal: "rat", relationship: "single",
                                        lookingFor: "both", createdAt: 1, expiresAt: 2)]
        XCTAssertEqual(P2PStore.decode(P2PStore.encode(s)), s)
        XCTAssertEqual(P2PStore.decode(Data()), P2PState())
        XCTAssertNil(P2PStore.decode(Data("not json".utf8)))
        // Unknown fields and missing optional ones are tolerated (the format can grow).
        let loose = #"{"v":2,"friends":[{"id":"i","ik":"AA==","fs":"AA==","extra":1}],"future":[]}"#
        XCTAssertEqual(P2PStore.decode(Data(loose.utf8))?.friends.first?.kind, "friends")
    }
}
