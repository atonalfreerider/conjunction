"""Transport-free per-link state machine (docs/P2P_PROTOCOL.md sections 6, 7, 10).

The BLE layer feeds received frames to on_frame() and writes whatever take_frames() returns, in
order, to the peer (central: INBOX writes; peripheral: OUTBOX notifications). take_events()
returns what the app should show. The Kotlin class P2pLink is a line-for-line equivalent."""
import datetime
import hashlib
import os
import random as _random
from dataclasses import dataclass, field
from typing import Callable, List, Optional

from . import canonjson, compat, crypto, instagram
from . import consts as C
from . import messages as M
from .frames import FrameError, Reassembler, encode_frames, max_frame_len

CENTRAL = "central"
PERIPHERAL = "peripheral"

AWAIT_HELLO = "await_hello"
MATCHED = "matched"
KEYX = "keyx"
SECURE = "secure"
CLOSED = "closed"



@dataclass
class LocalProfile:
    eid: str
    match: dict           # wire `match` object
    compat: dict          # wire `compat` object
    first_name: str
    bio: Optional[str] = None
    photo: Optional[bytes] = None
    photo_w: int = 0
    photo_h: int = 0


@dataclass
class Friend:
    ik: bytes
    fs: bytes
    name: str = ""
    blocked: bool = False


@dataclass
class Policy:
    friends: Callable[[], List[Friend]] = lambda: []
    blocked_fingerprint: Callable[[str], bool] = lambda fp: False
    preset_swipe: Callable[[str], Optional[str]] = lambda fp: None
    today: Callable[[], datetime.date] = datetime.date.today


class _Bucket:
    def __init__(self, cap, rate, now):
        self.cap, self.rate, self.tokens, self.t = cap, rate, float(cap), now

    def take(self, now) -> bool:
        self.tokens = min(self.cap, self.tokens + (now - self.t) * self.rate / 1000.0)
        self.t = now
        if self.tokens < 1:
            return False
        self.tokens -= 1
        return True


class Link:
    def __init__(self, role: str, local: LocalProfile, identity: crypto.KeyPair, policy: Policy,
                 now_ms: int, ephemeral: Optional[crypto.KeyPair] = None, rng=None):
        self.role = role
        self.local = local
        self.identity = identity
        self.policy = policy
        self.ephemeral = ephemeral or crypto.KeyPair.generate()
        self.rng = rng or _random.SystemRandom()
        self.state = AWAIT_HELLO
        self.frame_len = C.MIN_FRAME_LEN
        self._rx = Reassembler()
        self._next_msg_id = 0
        self._frames: list = []
        self._events: list = []
        self._started_ms = now_ms
        self._last_rx_ms = now_ms
        self._state_since_ms = now_ms
        self._keyx_seen_ms = None
        self._b_frames = _Bucket(*C.RATE_FRAMES, now_ms)
        self._b_msgs = _Bucket(*C.RATE_MESSAGES, now_ms)
        self._b_chats = _Bucket(*C.RATE_CHATS, now_ms)
        self.sent_hello = False
        self.my_hello = None
        self.peer_hello = None
        self.fingerprint = None
        self.kind = None
        self.result = None
        self.mode = None              # "stranger" | "friend"
        self.friend: Optional[Friend] = None
        self.my_swipe = None
        self.peer_swipe = None
        self.peer_card = None
        self.peer_photo = None
        self.sent_keyx = False
        self.peer_ik = None
        self.session: Optional[crypto.Session] = None
        self.send_ctr = 0
        self.recv_ctr = 0

    # ------------------------------------------------------------------ transport API
    def set_att_mtu(self, att_mtu: int):
        self.frame_len = max_frame_len(att_mtu)

    def start(self):
        """Called when the link is ready (central: MTU done + OUTBOX notifications enabled)."""
        if self.role == CENTRAL:
            self._send_hello()

    def take_frames(self) -> list:
        f, self._frames = self._frames, []
        return f

    def take_events(self) -> list:
        e, self._events = self._events, []
        return e

    @property
    def closed(self) -> bool:
        return self.state == CLOSED

    def on_frame(self, frame: bytes, now_ms: int):
        if self.state == CLOSED:
            return
        self._last_rx_ms = now_ms
        if not self._b_frames.take(now_ms):
            return self._fail("rate", "too many frames")
        try:
            body = self._rx.accept(frame, now_ms)
            if body is None:
                return
            if not self._b_msgs.take(now_ms):
                return self._fail("rate", "too many messages")
            msg, blob = M.decode(body)
            self._handle(msg, blob, now_ms)
        except FrameError as e:
            self._fail(e.code, e.detail)
        except crypto.CryptoError as e:
            self._fail("crypto", str(e))

    def tick(self, now_ms: int):
        if self.state == CLOSED:
            return
        self._rx.expire(now_ms)
        if self.state == AWAIT_HELLO and now_ms - self._started_ms > C.HELLO_TIMEOUT_MS:
            return self.close("timeout")
        if self.state == KEYX:
            if self._keyx_seen_ms is None:
                self._keyx_seen_ms = now_ms
            if now_ms - max(self._keyx_seen_ms, self._last_rx_ms) > C.KEYX_TIMEOUT_MS:
                return self.close("timeout")
        if self.state == MATCHED and now_ms - self._state_since_ms > C.MATCH_LINK_TIMEOUT_MS:
            return self.close("timeout")

    # ------------------------------------------------------------------ app API
    def swipe(self, direction: str):
        if direction not in M.DIRS:
            raise ValueError(direction)
        if self.state != MATCHED or self.my_swipe is not None:
            raise RuntimeError(f"cannot swipe in state {self.state}")
        self.my_swipe = direction
        self._send(M.swipe(direction))
        self._resolve_swipes()

    def send_chat(self, text: str, msg_id: Optional[str] = None, ts: Optional[int] = None) -> str:
        if self.state != SECURE:
            raise RuntimeError("chat requires a secure link")
        if not (1 <= len(text.encode("utf-8")) <= C.MAX_CHAT_TEXT_BYTES):
            raise ValueError("chat text length")
        msg_id = msg_id or crypto.new_msg_id()
        ts = ts if ts is not None else int(datetime.datetime.now().timestamp() * 1000)
        self._send_secure(M.chat_text(msg_id, ts, text))
        return msg_id

    def send_read(self, refs: list, msg_id: Optional[str] = None, ts: Optional[int] = None) -> str:
        if self.state != SECURE:
            raise RuntimeError("read receipts require a secure link")
        msg_id = msg_id or crypto.new_msg_id()
        ts = ts if ts is not None else int(datetime.datetime.now().timestamp() * 1000)
        self._send_secure(M.chat_read(msg_id, ts, refs))
        return msg_id

    def send_profile(self, rev: int, ig, msg_id: Optional[str] = None, ts: Optional[int] = None) -> str:
        """Sends a chat `profile` (SPEC 5d.6): `ig` is the normalised handle shared with this friend, or
        None. Only ever on a SECURE link, i.e. after a mutual match. Returns the message id; the app
        keeps it as profileInFlightId and marks `rev` acked when `delivered` reports that id."""
        if self.state != SECURE:
            raise RuntimeError("profile requires a secure link")
        if not (0 <= rev <= C.MAX_PROFILE_REV):
            raise ValueError("profile rev")
        if ig is not None and not instagram.is_valid(ig):
            raise ValueError("profile ig")
        msg_id = msg_id or crypto.new_msg_id()
        ts = ts if ts is not None else int(datetime.datetime.now().timestamp() * 1000)
        self._send_secure(M.chat_profile(msg_id, ts, rev, ig))
        return msg_id

    def close(self, reason: str = "done"):
        if self.state == CLOSED:
            return
        self._send(M.bye(reason))
        self._closed(reason, "local")

    # ------------------------------------------------------------------ internals
    def _send(self, msg: dict, blob: bytes = b""):
        body = M.encode(msg, blob)
        self._frames += encode_frames(self._next_msg_id, body, self.frame_len)
        self._next_msg_id = (self._next_msg_id + 1) & 0xFFFF

    def _send_secure(self, inner: dict):
        pt = canonjson.dumpb(inner)
        ct = crypto.seal(self.session.send_key, self.send_ctr, pt)
        self._send(M.chat(self.send_ctr, ct))
        self.send_ctr += 1

    def _emit(self, type_: str, **kw):
        kw["type"] = type_
        self._events.append(kw)

    def _set_state(self, s):
        self.state = s

    def _closed(self, reason, by, code=None):
        self.state = CLOSED
        self._emit("closed", reason=reason, by=by, code=code)

    def _fail(self, code, detail):
        if self.state == CLOSED:
            return
        self._send(M.error(code, (detail or "")[:200] or None))
        self._emit("protocol_error", code=code, detail=detail)
        self._closed("error", "local", code)

    def _friend_tags(self) -> list:
        friends = [f for f in self.policy.friends() if not f.blocked][:C.FRIEND_TAGS_REAL_MAX]
        tags = [crypto.friend_tag(f.fs, self.local.eid) for f in friends]
        n = max(C.FRIEND_TAGS_BLOCK, -(-len(tags) // C.FRIEND_TAGS_BLOCK) * C.FRIEND_TAGS_BLOCK)
        while len(tags) < n:
            tags.append(self.rng.randbytes(C.FRIEND_TAG_LEN))
        self.rng.shuffle(tags)
        return [M.b64(t) for t in tags]

    def _send_hello(self):
        self.my_hello = M.hello(self.local.eid, self.local.match, self.local.compat, self._friend_tags())
        self._send(self.my_hello)
        self.sent_hello = True

    def _handle(self, msg: dict, blob: bytes, now_ms: int):
        t = msg["t"]
        if t is None:
            return  # unknown type: ignored (forward compatibility)
        if t == "bye":
            self._closed(msg["reason"], "peer")
            return
        if t == "error":
            self._emit("protocol_error", code=msg["code"], detail=msg["detail"], by="peer")
            self._closed("error", "peer", msg["code"])
            return
        if t == "hello":
            if self.state != AWAIT_HELLO or self.peer_hello is not None:
                raise FrameError("state", "duplicate hello")
            return self._on_hello(msg, now_ms)
        if self.peer_hello is None:
            raise FrameError("state", f"{t} before hello")
        if t == "card":
            if self.state != MATCHED or self.peer_card is not None:
                raise FrameError("state", "unexpected card")
            if msg["kind"] != self.kind:
                return self.close("no_match")
            self.peer_card = msg
            self._emit("card", card=msg)
        elif t == "photo":
            pc = self.peer_card
            if self.state not in (MATCHED, KEYX, SECURE) or pc is None or pc["photo"] is None or self.peer_photo is not None:
                raise FrameError("state", "unexpected photo")
            if len(blob) != pc["photo"]["size"] or hashlib.sha256(blob).hexdigest() != pc["photo"]["sha256"] \
                    or msg["sha256"] != pc["photo"]["sha256"]:
                raise FrameError("malformed", "photo does not match card hash/size")
            self.peer_photo = blob
            self._emit("photo", data=blob, sha256=msg["sha256"])
        elif t == "swipe":
            if self.state != MATCHED or self.peer_swipe is not None:
                raise FrameError("state", "unexpected swipe")
            self.peer_swipe = msg["dir"]
            self._emit("peer_swipe", dir=msg["dir"])
            self._resolve_swipes()
        elif t == "keyx":
            if self.state != KEYX or not self.sent_keyx or self.session is not None:
                raise FrameError("state", "unexpected keyx")
            ik, ek = M.unb64(msg["ik"]), M.unb64(msg["ek"])
            if self.mode == "friend" and ik != self.friend.ik:
                raise crypto.CryptoError("identity key does not match recognised friend")
            self.peer_ik = ik
            self.session = crypto.derive_session(self.identity, self.ephemeral, ik, ek)
            self._send(M.kc(self.session.my_kc))
        elif t == "kc":
            if self.state != KEYX or self.session is None:
                raise FrameError("state", "unexpected kc")
            if not crypto.hmac.compare_digest(M.unb64(msg["mac"]), self.session.peer_kc):
                raise crypto.CryptoError("key confirmation failed")
            self._set_state(SECURE)
            if self.mode == "friend":
                self._emit("friend_nearby", friend=self.friend, peer_ik=self.peer_ik)
            else:
                self._emit("friend_new", peer_ik=self.peer_ik,
                           friend_secret=crypto.friend_secret(self.identity, self.peer_ik),
                           safety_code=crypto.safety_code(self.identity.public, self.peer_ik),
                           card=self.peer_card, photo=self.peer_photo, hello=self.peer_hello)
        elif t == "chat":
            if self.state != SECURE:
                raise FrameError("state", "chat before secure link")
            if not self._b_chats.take(now_ms):
                raise FrameError("rate", "too many chat messages")
            if msg["ctr"] != self.recv_ctr:
                raise crypto.CryptoError(f"chat counter {msg['ctr']} != expected {self.recv_ctr}")
            pt = crypto.open_(self.session.recv_key, msg["ctr"], M.unb64(msg["ct"]))
            self.recv_ctr += 1
            inner = M.decode_chat_plaintext(pt)
            if inner["kind"] is None:
                return
            if inner["kind"] == "profile":
                # Not a chat message: never shown or stored in the history; the app keeps peerIg/peerIgRev.
                self._emit("profile", message=inner)
                self._send(M.ack(inner["id"]))
                return
            self._emit("chat", message=inner)
            if inner["kind"] == "text":
                self._send(M.ack(inner["id"]))
        elif t == "ack":
            if self.state != SECURE:
                raise FrameError("state", "ack before secure link")
            self._emit("delivered", id=msg["id"])

    def _on_hello(self, peer: dict, now_ms: int):
        self.peer_hello = peer
        if self.role == PERIPHERAL and not self.sent_hello:
            self._send_hello()
        if peer["eid"] == self.local.eid:
            raise FrameError("state", "peer uses our ephemeral id")
        if min(peer["pv"], C.PROTOCOL_VERSION) < max(peer["pmin"], C.PROTOCOL_MIN_VERSION):
            raise FrameError("version", f"no common version (peer {peer['pmin']}..{peer['pv']})")
        self.fingerprint = crypto.profile_fingerprint(canonjson.dumpb(peer["compat"]))
        self._emit("hello", hello=peer, fingerprint=self.fingerprint)
        # 1. Friend recognition.
        tags = set(M.unb64(t) for t in peer["ft"])
        for f in self.policy.friends():
            if crypto.friend_tag(f.fs, peer["eid"]) in tags:
                if f.blocked:
                    return self.close("done")
                self.mode, self.friend = "friend", f
                self._set_state(KEYX)
                self._send_keyx()
                return
        # 2. Blocked / hidden stranger.
        if self.policy.blocked_fingerprint(self.fingerprint):
            return self.close("done")
        # 3. Matching.
        res = compat.evaluate(self.my_hello, peer, self.policy.today())
        if res is None:
            self._emit("no_match")
            return self.close("no_match")
        self.mode = "stranger"
        self.kind = res["kind"]
        self.result = res
        self._set_state(MATCHED)
        self._state_since_ms = now_ms
        self._emit("match", kind=res["kind"], score=res["score"], peer_age=res["peerAge"],
                   fingerprint=self.fingerprint, hello=peer)
        self._send_card()
        preset = self.policy.preset_swipe(self.fingerprint)
        if preset is not None:
            self.swipe(preset)

    def _send_card(self):
        lp = self.local
        send_photo = lp.photo is not None and self.frame_len >= C.PHOTO_MIN_FRAME_LEN
        meta = None
        if send_photo:
            meta = {"sha256": hashlib.sha256(lp.photo).hexdigest(), "size": len(lp.photo), "w": lp.photo_w, "h": lp.photo_h}
        self._send(M.card(self.kind, lp.first_name, lp.bio, meta))
        if send_photo:
            self._send(M.photo(meta["sha256"]), lp.photo)

    def _send_keyx(self):
        self._send(M.keyx(self.identity.public, self.ephemeral.public))
        self.sent_keyx = True

    def _resolve_swipes(self):
        if self.my_swipe is None:
            return
        if self.my_swipe == "left":
            return self.close("done")
        if self.peer_swipe is None:
            return
        if self.peer_swipe == "right":
            self._set_state(KEYX)
            self._send_keyx()
        # peer swiped left: the peer closes the link; nothing to show (left is silent).


def dedup_keep(my_eid: str, peer_eid: str, i_am_central: bool) -> bool:
    """When two links connect the same pair, keep the one whose central has the smaller eid."""
    return (my_eid < peer_eid) == i_am_central
