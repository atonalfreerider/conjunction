"""A simulated nearby user: persona + persistent identity/friends + automatic reactions.

Shared by the BLE peripheral, the BLE central and the in-memory loopback. All protocol logic lives
in link.Link; this module only decides what the persona does with the events."""
import asyncio
import datetime
import hashlib
import json
import logging
import os
import random
import struct
import time
from dataclasses import dataclass, field
from typing import Optional

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from . import canonjson, compat, crypto
from . import messages as M
from .link import CENTRAL, Friend, Link, LocalProfile, Policy, dedup_keep

log = logging.getLogger("p2p")


def now_ms() -> int:
    return int(time.monotonic() * 1000)


@dataclass
class Persona:
    path: str
    first_name: str
    bio: Optional[str]
    match: dict
    compat: dict
    photo: Optional[bytes]
    photo_w: int
    photo_h: int
    auto_swipe: Optional[str]
    replies: list

    @staticmethod
    def load(path: str) -> "Persona":
        with open(path, encoding="utf-8") as f:
            p = json.load(f)
        if p.get("compat") is None:
            raise SystemExit(f"{path}: 'compat' missing; run ./gradlew :core:test (PersonaProfilesTest prints it)")
        photo = w = h = None
        if p.get("photo"):
            ppath = os.path.join(os.path.dirname(path), p["photo"])
            with open(ppath, "rb") as f:
                photo = f.read()
            w, h = _jpeg_size(photo)
            if len(photo) > 61440 or max(w, h) > 640:
                raise SystemExit(f"{ppath}: photo must be a JPEG <= 61440 bytes and <= 640 px")
        match = {"gender": p["gender"], "seeking": p["seeking"], "relationship": p["relationship"],
                 "lookingFor": p["lookingFor"]}
        # Validate through the real decoder so a bad persona fails early.
        M.decode(M.encode(M.hello("0" * 32, match, p["compat"], [])))
        return Persona(path, p["firstName"], p.get("bio"), match, p["compat"], photo, w or 0, h or 0,
                       p.get("autoSwipe"), p.get("replies") or ["Received."])

    @property
    def key(self) -> str:
        return os.path.splitext(os.path.basename(self.path))[0]


def _jpeg_size(data: bytes):
    if data[:2] != b"\xff\xd8":
        raise SystemExit("photo is not a JPEG")
    i = 2
    while i < len(data):
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack(">HH", data[i + 5:i + 9])
            return w, h
        seg = struct.unpack(">H", data[i + 2:i + 4])[0]
        i += 2 + seg
    raise SystemExit("JPEG size not found")


class Store:
    """Identity key, friends and chat log in a per-persona state directory (outside git)."""

    def __init__(self, state_dir: str, persona: str):
        self.dir = os.path.join(state_dir, persona)
        os.makedirs(self.dir, exist_ok=True)
        self.identity = self._load_identity()
        self.friends_path = os.path.join(self.dir, "friends.json")
        self.friends = self._load_friends()
        self.outbox_path = os.path.join(self.dir, "outbox.json")
        self.outbox = self._load_json(self.outbox_path, {})  # friend ik hex -> [ {id, ts, text} ]
        self.hidden_path = os.path.join(self.dir, "hidden.json")
        self.hidden = self._load_json(self.hidden_path, {})  # fingerprint -> until epoch ms

    def _load_identity(self) -> crypto.KeyPair:
        p = os.path.join(self.dir, "identity.pem")
        if os.path.exists(p):
            with open(p, "rb") as f:
                return crypto.KeyPair(serialization.load_pem_private_key(f.read(), None))
        kp = ec.generate_private_key(ec.SECP256R1())
        fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "wb") as f:
            f.write(kp.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                     serialization.NoEncryption()))
        return crypto.KeyPair(kp)

    @staticmethod
    def _load_json(path, default):
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        return default

    def _save(self, path, obj):
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, indent=1)
        os.replace(tmp, path)

    def _load_friends(self):
        raw = self._load_json(os.path.join(self.dir, "friends.json"), [])
        return [Friend(bytes.fromhex(r["ik"]), bytes.fromhex(r["fs"]), r.get("name", ""), r.get("blocked", False)) for r in raw]

    def add_friend(self, ik: bytes, fs: bytes, name: str):
        self.friends = [f for f in self.friends if f.ik != ik]
        self.friends.insert(0, Friend(ik, fs, name))
        self._save(self.friends_path, [{"ik": f.ik.hex(), "fs": f.fs.hex(), "name": f.name, "blocked": f.blocked}
                                       for f in self.friends])

    def queue(self, friend_ik: bytes, text: str):
        self.outbox.setdefault(friend_ik.hex(), []).append(
            {"id": crypto.new_msg_id(), "ts": int(time.time() * 1000), "text": text})
        self._save(self.outbox_path, self.outbox)

    def delivered(self, msg_id: str):
        for k in list(self.outbox):
            self.outbox[k] = [m for m in self.outbox[k] if m["id"] != msg_id]
        self._save(self.outbox_path, self.outbox)

    def hide(self, fp: str, days=30):
        self.hidden[fp] = int(time.time() * 1000) + days * 86400_000
        self._save(self.hidden_path, self.hidden)

    def is_hidden(self, fp: str) -> bool:
        return self.hidden.get(fp, 0) > time.time() * 1000

    def append_chat(self, line: dict):
        with open(os.path.join(self.dir, "chat.log"), "a", encoding="utf-8") as f:
            f.write(json.dumps(line, ensure_ascii=False) + "\n")


def describe_frame(frame: bytes) -> str:
    if len(frame) < 7:
        return f"short frame {frame.hex()}"
    ver, mid, idx, cnt = struct.unpack(">BHHH", frame[:7])
    return f"v{ver} msg={mid} {idx + 1}/{cnt} len={len(frame)}"


class SimPeer:
    """Owns the persona's links; the transport calls new_link() / feed() / drain()."""

    def __init__(self, persona: Persona, store: Store, swipe: Optional[str], swipe_delay: float,
                 greet: bool, log_hex: bool, today: Optional[datetime.date] = None):
        self.persona = persona
        self.store = store
        self.swipe_dir = swipe if swipe is not None else persona.auto_swipe
        self.swipe_delay = swipe_delay
        self.greet = greet
        self.log_hex = log_hex
        self.today = today
        self.eid = crypto.new_eid()
        self.eid_since = time.monotonic()
        self.links = {}          # handle -> Link
        self.pending_swipes = {}  # fingerprint -> direction (applied on re-encounter)
        self._reply_idx = 0
        self.max_replies = 20
        self._replies_sent = {}  # handle -> count (stops two bots from chatting forever)
        self.policy = Policy(
            friends=lambda: self.store.friends,
            blocked_fingerprint=self.store.is_hidden,
            preset_swipe=lambda fp: self.pending_swipes.pop(fp, None),
            today=lambda: self.today or datetime.date.today(),
        )
        self.on_output = None   # callback(handle) when a link has frames to send
        self.on_close = None    # callback(handle) when a link closed

    def current_eid(self) -> str:
        if time.monotonic() - self.eid_since >= 15 * 60:
            self.eid = crypto.new_eid()
            self.eid_since = time.monotonic()
            log.info("rotated ephemeral id -> %s", self.eid)
        return self.eid

    def profile(self) -> LocalProfile:
        p = self.persona
        return LocalProfile(self.current_eid(), p.match, p.compat, p.first_name, p.bio, p.photo, p.photo_w, p.photo_h)

    def new_link(self, handle: str, role: str, att_mtu: int) -> Link:
        link = Link(role, self.profile(), self.store.identity, self.policy, now_ms())
        link.set_att_mtu(att_mtu)
        self.links[handle] = link
        log.info("[%s] link up as %s, ATT_MTU %d -> frameLen %d, my eid %s", handle, role, att_mtu, link.frame_len, link.local.eid)
        return link

    def drop_link(self, handle: str):
        self.links.pop(handle, None)

    def log_frames(self, handle: str, direction: str, frames: list):
        for f in frames:
            if self.log_hex:
                log.debug("[%s] %s %s %s", handle, direction, describe_frame(f), f.hex())
            else:
                log.debug("[%s] %s %s", handle, direction, describe_frame(f))

    def feed(self, handle: str, frame: bytes):
        link = self.links.get(handle)
        if link is None:
            return
        self.log_frames(handle, "<-", [frame])
        link.on_frame(frame, now_ms())
        self._after(handle, link)

    def tick(self):
        for handle, link in list(self.links.items()):
            link.tick(now_ms())
            self._after(handle, link)

    def drain(self, handle: str) -> list:
        link = self.links.get(handle)
        if link is None:
            return []
        frames = link.take_frames()
        self._log_messages(handle, "->", frames)
        self.log_frames(handle, "->", frames)
        return frames

    def _log_messages(self, handle, direction, frames):
        cur = {}
        for f in frames:
            mid = int.from_bytes(f[1:3], "big")
            cur.setdefault(mid, bytearray()).extend(f[7:])
        for mid, body in cur.items():
            try:
                msg, blob = M.decode(bytes(body))
                text = canonjson.dumps(msg)
                if len(text) > 400:
                    text = text[:400] + "..."
                log.info("[%s] %s %s%s", handle, direction, text, f" + {len(blob)} byte blob" if blob else "")
            except Exception as e:  # partial messages spanning drains are logged by the receiver
                log.debug("[%s] %s (partial message %d: %s)", handle, direction, mid, e)

    # ------------------------------------------------------------------ persona behaviour
    def _after(self, handle: str, link: Link):
        for ev in link.take_events():
            self._on_event(handle, link, ev)
        if link._frames and self.on_output:
            self.on_output(handle)
        if link.closed and self.on_close:
            self.on_close(handle)

    def _dedup(self, handle: str, link: Link) -> bool:
        """Protocol 7.2: of two links to the same phone close the one whose central has the larger eid.
        Nothing more goes out on it but the bye: its frames not yet written are dropped."""
        peer = link.peer_hello["eid"]
        for h, other in list(self.links.items()):
            if h != handle and other.peer_hello and other.peer_hello["eid"] == peer and not other.closed:
                keep_this = dedup_keep(link.local.eid, peer, link.role == CENTRAL)
                keep_other = dedup_keep(other.local.eid, peer, other.role == CENTRAL)
                loser_h, loser = (h, other) if keep_this and not keep_other else (handle, link)
                log.info("[%s] duplicate link to eid %s; closing %s (dedup)", handle, peer,
                         "this one" if loser is link else loser_h)
                loser._frames.clear()
                loser.close("dedup")
                if loser is not link:
                    self._after(loser_h, loser)
                return loser is link
        return False

    def _on_event(self, handle: str, link: Link, ev: dict):
        t = ev["type"]
        if t == "hello":
            h = ev["hello"]
            log.info("[%s] hello from eid %s (fingerprint %s): %s, sun %s", handle, h["eid"], ev["fingerprint"],
                     h["match"], compat.sun_sign(h["compat"]))
            self._dedup(handle, link)
        elif t == "no_match":
            log.info("[%s] no qualifying match -> bye no_match", handle)
        elif t == "match":
            s = ev["score"]
            log.info("[%s] MATCH kind=%s total=%d%% (western %d, chinese %d, blood %s) twins=%s/%s peer age %d; highlights %s",
                     handle, ev["kind"], s["total"], s["western"], s["chinese"], s["blood"], s["isBirthdayTwin"],
                     s["isExactTwin"], ev["peer_age"], s["highlights"])
            if self.swipe_dir in ("right", "left"):
                asyncio.get_event_loop().call_later(self.swipe_delay, self._swipe, handle, self.swipe_dir)
            else:
                log.info("[%s] not swiping (--swipe none)", handle)
        elif t == "card":
            c = ev["card"]
            log.info("[%s] card: %s, bio %r, photo %s", handle, c["firstName"], c["bio"], c["photo"])
        elif t == "photo":
            path = os.path.join(self.store.dir, f"received-{ev['sha256'][:12]}.jpg")
            with open(path, "wb") as f:
                f.write(ev["data"])
            log.info("[%s] photo %d bytes, sha256 ok, saved to %s", handle, len(ev["data"]), path)
        elif t == "peer_swipe":
            log.info("[%s] peer swiped %s", handle, ev["dir"])
        elif t == "friend_new":
            name = (ev["card"] or {}).get("firstName", "?")
            self.store.add_friend(ev["peer_ik"], ev["friend_secret"], name)
            log.info("[%s] IT'S A CONJUNCTION with %s; safety code %s; friend stored", handle, name, ev["safety_code"])
            if self.greet:
                self._send_chat(handle, link, f"Hi {name}! {self.persona.first_name} here.")
            self._flush_outbox(handle, link, ev["peer_ik"])
        elif t == "friend_nearby":
            log.info("[%s] friend %s is nearby (recognised by tag, key confirmed)", handle, ev["friend"].name)
            self._flush_outbox(handle, link, ev["peer_ik"])
        elif t == "chat":
            m = ev["message"]
            self.store.append_chat({"dir": "in", "peer": link.peer_ik.hex()[:16], **m})
            if m["kind"] == "text":
                log.info("[%s] CHAT <- %r", handle, m["text"])
                n = self._replies_sent.get(handle, 0)
                if n >= self.max_replies:
                    return
                self._replies_sent[handle] = n + 1
                reply = self.persona.replies[self._reply_idx % len(self.persona.replies)]
                self._reply_idx += 1
                asyncio.get_event_loop().call_later(1.0, self._send_chat, handle, link, reply)
            else:
                log.info("[%s] read receipt for %s", handle, m["refs"])
        elif t == "delivered":
            self.store.delivered(ev["id"])
            log.info("[%s] delivered %s", handle, ev["id"])
        elif t == "protocol_error":
            log.warning("[%s] protocol error %s: %s (%s)", handle, ev["code"], ev["detail"], "peer" if ev.get("by") else "local")
        elif t == "closed":
            log.info("[%s] link closed: %s (by %s)", handle, ev["reason"], ev["by"])

    def _swipe(self, handle: str, direction: str):
        link = self.links.get(handle)
        if link is None or link.closed:
            if link is not None and link.fingerprint:
                self.pending_swipes[link.fingerprint] = direction
            return
        if link.state != "matched" or link.my_swipe is not None:
            return
        log.info("[%s] swiping %s", handle, direction)
        link.swipe(direction)
        if direction == "left" and link.fingerprint:
            self.store.hide(link.fingerprint)
        self._after(handle, link)

    def _send_chat(self, handle: str, link: Link, text: str):
        if link.closed or link.state != "secure":
            if link.peer_ik:
                self.store.queue(link.peer_ik, text)
                log.info("[%s] link not secure; queued %r for next encounter", handle, text)
            return
        mid = link.send_chat(text)
        self.store.append_chat({"dir": "out", "peer": link.peer_ik.hex()[:16], "id": mid, "text": text})
        log.info("[%s] CHAT -> %r", handle, text)
        self._after(handle, link)

    def _flush_outbox(self, handle: str, link: Link, ik: bytes):
        for m in list(self.store.outbox.get(ik.hex(), [])):
            link.send_chat(m["text"], msg_id=m["id"], ts=m["ts"])
            log.info("[%s] CHAT -> (queued) %r", handle, m["text"])
