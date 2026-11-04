import Foundation
import Combine
import UIKit
import CoreBluetooth
import ConjunctionCore

/// Nearby for the UI (Android `NearbyRepository`): on / off / pause, the Matches inbox, friends,
/// chats and safety actions, all on this phone. Bluetooth work happens in `NearbyNode`.
@MainActor
final class NearbyRepository: ObservableObject {
    @Published private(set) var state = P2PState()
    @Published private(set) var engineStatus = EngineStatus()
    @Published private(set) var radio = RadioStatus()
    /// Matches / twins / mutual matches to show full-screen while the app is open.
    let events = PassthroughSubject<NearbyEvent, Never>()

    /// The chat currently on screen, so its messages don't also raise a notification.
    var openChatFriendId: String?
    private(set) var foreground = true
    private(set) var radioRunning = false
    /// Settings › Notifications: Matches nearby / Messages (from the profile).
    private var notifyMatches = true
    private var notifyMessages = true
    /// Compatibility views opened this session (the score counts up on the first open only).
    var compatOpened: Set<String> = []
    private var resumeTimer: Timer?
    let node: NearbyNode

    init(node: NearbyNode = NearbyNode()) {
        self.node = node
        node.onState = { [weak self] s in DispatchQueue.main.async { self?.state = s } }
        node.onEngineStatus = { [weak self] s in DispatchQueue.main.async { self?.engineStatus = s } }
        node.onRadioStatus = { [weak self] s in DispatchQueue.main.async { self?.radio = s } }
        node.onAnnounce = { [weak self] e, name, sun in DispatchQueue.main.async { self?.announce(e, peerName: name, peerSun: sun) } }
        node.queue.sync { state = node.store.state }
    }

    /// Bluetooth is usable by this app at all (not denied in Settings).
    var bluetoothAllowed: Bool {
        let a = CBManager.authorization
        return a != .denied && a != .restricted
    }

    // ------------------------------------------------------------------ announcements

    private func announce(_ event: NearbyEvent, peerName: String?, peerSun: String?) {
        if event.kind == .message {
            if notifyMessages && !(foreground && openChatFriendId == event.friendId) {
                Notifications.show(event, peerName: peerName, peerSun: peerSun)
            }
        } else if foreground {
            events.send(event)
        } else if notifyMatches {
            Notifications.show(event, peerName: peerName, peerSun: peerSun)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    func setForeground(_ visible: Bool) {
        foreground = visible
        node.foreground = visible
        if visible {
            // Screening that had to wait for the foreground (the on-device model).
            node.post { [node] in node.engine.rescreenPending() }
        } else {
            node.flush()
        }
    }

    /// Applies the profile: what this phone sends, and whether the radio should run.
    func apply(profile: OwnerProfile, charts: OwnerCharts?) {
        notifyMatches = profile.notifyMatches
        notifyMessages = profile.notifyMessages
        let photo = profile.photoFile.flatMap { _ in PhotoCodec.cardPhoto(AppFiles.ownerPhotoURL) }
        let local = profile.nearbyEnabled ? OwnerCharts.localPeerProfile(profile, charts: charts, photo: photo) : nil
        node.setLocalProfile(local)
        let now = P2PLink.nowMs()
        let shouldRun = profile.nearbyShouldRun(nowMs: now) && local != nil
        if shouldRun && !radioRunning {
            radioRunning = true
            node.startRadio()
        } else if !shouldRun && radioRunning {
            radioRunning = false
            node.stopRadio()
        }
        // Wake up when a pause ends.
        resumeTimer?.invalidate()
        resumeTimer = nil
        if profile.nearbyEnabled && profile.isPaused(nowMs: now) {
            let delay = Double(profile.nearbyPausedUntil - now) / 1000 + 1
            resumeTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
                Task { @MainActor in self?.apply(profile: profile, charts: charts) }
            }
        }
    }

    // ------------------------------------------------------------------ reads

    var now: Int64 { P2PLink.nowMs() }
    var inbox: [StoredEncounter] { state.inbox(now: now) }
    var waiting: [StoredEncounter] { state.waiting(now: now) }
    var pendingCount: Int { state.pendingCount(now: now) }
    func compatSubject(_ key: String) -> CompatSubject? { state.compatSubject(key, now: now) }
    var snoozed: [HiddenPeer] { state.snoozed(now: now) }
    var friends: [FriendSummary] { state.friendSummaries(inRange: engineStatus.friendsInRange) }
    func friend(_ id: String) -> FriendSummary? { state.friendSummary(id, inRange: engineStatus.friendsInRange) }
    func encounter(_ fp: String) -> StoredEncounter? { state.encounter(fp) }
    func messages(_ friendId: String) -> [ChatLine] { state.messagesWith(friendId) }
    func photoURL(_ file: String?) -> URL? { file.map { node.store.photoURL($0) } }

    // ------------------------------------------------------------------ actions (posted to the Bluetooth queue)

    private func onQueue(_ block: @escaping (NearbyNode) -> Void) {
        let n = node
        n.post { block(n) }
    }

    func swipe(_ fingerprint: String, accept: Bool) {
        onQueue { $0.engine.swipe(fingerprint, accept ? .right : .left) }
    }

    /// Queues a message; it goes out at once if the friend is in range, else the next time they are.
    func sendChat(_ friendId: String, _ text: String) {
        let trimmed = NearbyText.trimUTF8(text.trimmingCharacters(in: .whitespacesAndNewlines), P2PConstants.maxChatTextBytes)
        guard !trimmed.isEmpty else { return }
        let line = ChatLine(id: NearbyEngine.newMessageId(), friendId: friendId, mine: true, text: trimmed, ts: P2PLink.nowMs(),
                            status: .queued)
        onQueue { n in
            n.store.update { $0.messages.append(line) }
            n.engine.deliver(friendId)
            if !n.engine.status.friendsInRange.contains(friendId) { n.transport?.expediteReconnect() }
        }
    }

    /// The person looked at the chat: incoming messages are seen (read receipts go when in range).
    func markSeen(_ friendId: String) {
        guard state.messages.contains(where: { $0.friendId == friendId && !$0.mine && !$0.seen }) else { return }
        onQueue { n in
            n.store.update { s in for i in s.messages.indices where s.messages[i].friendId == friendId && !s.messages[i].mine { s.messages[i].seen = true } }
            n.engine.deliver(friendId)
        }
    }

    /// "Show anyway" on a hidden message.
    func reveal(_ messageId: String) {
        onQueue { n in n.store.update { s in for i in s.messages.indices where s.messages[i].id == messageId { s.messages[i].screening = .safe } } }
    }

    /// Block a friend: kept as a blocked record (so they are recognised and refused), chat and
    /// everything kept of them (chart, reading, Instagram) deleted here.
    func blockFriend(_ friendId: String) {
        onQueue { n in
            n.store.update { $0.blockFriend(friendId) }
            n.engine.disconnectPeer(friendId: friendId, fingerprint: nil)
        }
    }

    /// Unmatch: friendship, chat and kept data deleted here; their card stays hidden for 30 days.
    func unmatch(_ friendId: String) {
        let until = P2PLink.nowMs() + NearbyEngine.leftSwipeHideMs
        onQueue { n in
            n.engine.disconnectPeer(friendId: friendId, fingerprint: nil)
            n.store.update { $0.unmatch(friendId, hiddenUntil: until) }
        }
    }

    func blockStranger(_ fingerprint: String) { hideStranger(fingerprint, until: Int64.max, reason: "block") }

    func snooze(_ fingerprint: String) { hideStranger(fingerprint, until: P2PLink.nowMs() + NearbyEngine.snoozeMs, reason: "snooze") }

    private func hideStranger(_ fingerprint: String, until: Int64, reason: String) {
        onQueue { n in
            n.store.update { $0.hideStranger(fingerprint, until: until, reason: reason) }
            n.engine.disconnectPeer(friendId: nil, fingerprint: fingerprint)
        }
    }

    /// Settings › Nearby › Snoozed people.
    func unsnooze(_ fingerprint: String) {
        onQueue { n in n.store.update { $0.hidden.removeAll { $0.fingerprint == fingerprint && $0.reason == "snooze" } } }
    }

    /// "Share my Instagram with <name>" (friend ⋯ and chat ⋯): the next `profile` goes at once if in range.
    func setInstagramShare(_ friendId: String, on: Bool) {
        onQueue { n in
            n.store.update { $0.setInstagramShare(friendId, on: on) }
            n.engine.refreshProfiles()
        }
    }

    /// The owner's handle or the Settings default changed (SPEC 5d.6).
    func refreshProfiles() { onQueue { $0.engine.refreshProfiles() } }

    /// The Compatibility view's reading, cached on the record (SPEC 5d.4 item 6).
    func saveBlurb(_ key: String, _ blurb: StoredBlurb) { onQueue { n in n.store.update { $0.saveBlurb(key, blurb) } } }

    /// Settings → Blocked people: forget a blocked former friend (they could match again later).
    func unblockFriend(_ friendId: String) {
        onQueue { n in n.store.update { $0.friends.removeAll { $0.id == friendId && $0.blocked } } }
    }

    /// Settings → Blocked people: a stranger blocked from a match card may match again.
    func unblockStranger(_ fingerprint: String) {
        onQueue { n in n.store.update { $0.hidden.removeAll { $0.fingerprint == fingerprint && $0.reason == "block" } } }
    }

    /// Delete all my data: Nearby off, friends, chats, photos and the identity key.
    func deleteAll() {
        radioRunning = false
        node.setLocalProfile(nil)
        node.stopRadio()
        onQueue { n in
            n.store.clear()
            IdentityKeychain.delete()
        }
    }
}
