"""Framing and reassembly (docs/P2P_PROTOCOL.md section 4)."""
import struct
from dataclasses import dataclass, field

from . import consts as C


class FrameError(Exception):
    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


def max_frame_len(att_mtu: int) -> int:
    """Largest frame for a negotiated ATT_MTU: min(ATT_MTU - 3, 512), at least 20."""
    return max(C.MIN_FRAME_LEN, min(att_mtu - 3, C.MAX_FRAME_LEN))


def pack_body(json_bytes: bytes, blob: bytes = b"") -> bytes:
    if not (2 <= len(json_bytes) <= C.MAX_JSON_LEN):
        raise ValueError("json length out of range")
    if len(blob) > C.MAX_BLOB_LEN:
        raise ValueError("blob too large")
    return struct.pack(">H", len(json_bytes)) + json_bytes + blob


def unpack_body(body: bytes):
    if len(body) < 4:
        raise FrameError("malformed", "body shorter than 4 bytes")
    (jlen,) = struct.unpack(">H", body[:2])
    if jlen < 2 or jlen > C.MAX_JSON_LEN or 2 + jlen > len(body):
        raise FrameError("malformed", f"bad json length {jlen}")
    return body[2:2 + jlen], body[2 + jlen:]


def encode_frames(msg_id: int, body: bytes, frame_len: int) -> list:
    """Split a message body into frames of at most frame_len bytes (header included)."""
    if not (0 <= msg_id <= 0xFFFF):
        raise ValueError("msg_id out of range")
    if not (C.MIN_FRAME_LEN <= frame_len <= C.MAX_FRAME_LEN):
        raise ValueError("frame_len out of range")
    if not (1 <= len(body) <= C.MAX_BODY_LEN):
        raise ValueError("body length out of range")
    chunk = frame_len - C.FRAME_HEADER_LEN
    count = (len(body) + chunk - 1) // chunk
    frames = []
    for i in range(count):
        part = body[i * chunk:(i + 1) * chunk]
        frames.append(struct.pack(">BHHH", C.FRAME_VERSION, msg_id, i, count) + part)
    return frames


def is_link_start(frame: bytes) -> bool:
    """True when frame can open a link: msgId 0, index 0 (a central's hello is always the first frame
    it writes on a link). A peripheral opens a link only on such a frame; anything else arriving on a
    connection with no open link is left over from a link that already ended while the LE connection
    stayed up (docs/P2P_PROTOCOL.md section 3, step 7) and is discarded without an error."""
    return len(frame) >= C.FRAME_HEADER_LEN + 1 and frame[1:3] == b"\x00\x00" and frame[3:5] == b"\x00\x00"


@dataclass
class _Partial:
    count: int
    next_index: int = 0
    data: bytearray = field(default_factory=bytearray)
    last_ms: int = 0


class Reassembler:
    """Per-link, per-direction reassembly. accept() returns a complete body or None.

    Raises FrameError for protocol violations; the caller sends `error` and (for "frame_version")
    closes the link. A violation discards only the affected message."""

    def __init__(self):
        self._partials = {}

    def accept(self, frame: bytes, now_ms: int):
        if len(frame) < C.FRAME_HEADER_LEN + 1:
            raise FrameError("malformed", "frame shorter than header + 1")
        if len(frame) > C.MAX_FRAME_LEN:
            raise FrameError("malformed", "frame longer than 512 bytes")
        ver, msg_id, index, count = struct.unpack(">BHHH", frame[:C.FRAME_HEADER_LEN])
        if ver != C.FRAME_VERSION:
            raise FrameError("frame_version", f"unknown frame version {ver}")
        if count == 0 or index >= count:
            self._partials.pop(msg_id, None)
            raise FrameError("malformed", f"index {index} / count {count}")
        payload = frame[C.FRAME_HEADER_LEN:]
        p = self._partials.get(msg_id)
        if index == 0:
            # A new message with the id of an unfinished one replaces it (spec 4.3 rule 4).
            self._partials.pop(msg_id, None)
            if count == 1:
                return bytes(payload)
            if len(self._partials) >= C.MAX_CONCURRENT_REASSEMBLIES:
                raise FrameError("rate", "too many concurrent messages")
            if count > C.MAX_BODY_LEN:  # every frame carries >= 1 payload byte
                raise FrameError("too_large", "frame count too large")
            p = _Partial(count=count)
            self._partials[msg_id] = p
        else:
            if p is None:
                raise FrameError("malformed", f"frame {index} of unknown message {msg_id}")
            if index != p.next_index or count != p.count:
                del self._partials[msg_id]
                raise FrameError("malformed", f"out-of-order frame {index}/{count} for message {msg_id}")
        p.data += payload
        p.next_index += 1
        p.last_ms = now_ms
        if len(p.data) > C.MAX_BODY_LEN:
            del self._partials[msg_id]
            raise FrameError("too_large", "message too large")
        if p.next_index == p.count:
            del self._partials[msg_id]
            return bytes(p.data)
        return None

    def expire(self, now_ms: int) -> int:
        """Drops partial messages idle for more than REASSEMBLY_TIMEOUT_MS; returns how many."""
        dead = [k for k, p in self._partials.items() if now_ms - p.last_ms > C.REASSEMBLY_TIMEOUT_MS]
        for k in dead:
            del self._partials[k]
        return len(dead)

    @property
    def pending(self) -> int:
        return len(self._partials)
