"""Instagram handle normalisation and validation (SPEC 5d.6; docs/P2P_PROTOCOL.md section 5.3 `profile`).

Python twin of Kotlin `core.profile.InstagramHandle` and Swift `InstagramHandle`; all three pass
testdata/golden_instagram.json and the `profileValidation` / `normalize` vectors in p2p_wire.json."""
import re

MAX_LEN = 30

# A first path segment that is an Instagram page, not a profile.
RESERVED_SEGMENTS = frozenset(
    "p reel reels stories explore accounts direct tv about developer legal web oauth".split())

_VALID = re.compile(r"[a-z0-9._]{1,30}")
_HOST = "instagram.com/"

# Unicode White_Space (the same explicit list in Kotlin and Swift, so trimming agrees everywhere).
WHITESPACE = frozenset(
    [chr(c) for c in range(0x09, 0x0E)] + [" ", "\u0085", " ", " "]
    + [chr(c) for c in range(0x2000, 0x200B)] + [" ", " ", " ", " ", "　"])


def _ascii_lower(s: str) -> str:
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in s)


def is_valid(handle) -> bool:
    """`^[a-z0-9._]{1,30}$` with at least one letter or digit (the wire rule for `profile.ig`)."""
    return (isinstance(handle, str) and _VALID.fullmatch(handle) is not None
            and any(c.isascii() and c.isalnum() for c in handle))


def _trim(s: str) -> str:
    i, j = 0, len(s)
    while i < j and s[i] in WHITESPACE:
        i += 1
    while j > i and s[j - 1] in WHITESPACE:
        j -= 1
    return s[i:j]


def _segment(s: str, start: int):
    """The path segment starting at `start`, and the index just past it."""
    end = start
    while end < len(s) and s[end] not in "/?#" and s[end] not in WHITESPACE:
        end += 1
    return s[start:end], end


def normalize(text: str):
    """User input (a handle, `@handle` or a profile link / shared text) -> the normalised handle, or
    None when it is not a valid Instagram username."""
    if text is None:
        return None
    at = _ascii_lower(text).find(_HOST)
    if at >= 0:
        seg, end = _segment(text, at + len(_HOST))
        if _ascii_lower(seg) == "_u":
            seg = _segment(text, end + 1)[0] if end < len(text) and text[end] == "/" else ""
        if _ascii_lower(seg) in RESERVED_SEGMENTS:
            return None
        candidate = seg
    else:
        candidate = _trim(text)
        if candidate.startswith("@"):
            candidate = candidate[1:]
    candidate = _ascii_lower(candidate)
    return candidate if is_valid(candidate) else None


def received(ig):
    """Receiver leniency for `profile.ig`: a string failing the rule is treated as null."""
    return ig if is_valid(ig) else None


def profile_url(handle: str) -> str:
    return f"https://www.instagram.com/{handle}/"


class ShareState:
    """Sender state for the chat `profile` toward ONE friend (SPEC 5d.6), the twin of Kotlin
    `InstagramShareState` / Swift `InstagramShareState`.

    rev: profile revision for this friend (0 when the friendship is created, +1 whenever the
    effective value changes); acked_rev: highest rev the friend acknowledged (-1 = none);
    in_flight_id / in_flight_rev: the last `profile` sent and not yet acked; value: the effective
    value at `rev` (the handle if set AND sharing with this friend is on, else None)."""

    __slots__ = ("rev", "acked_rev", "in_flight_id", "in_flight_rev", "value")

    def __init__(self, value=None, rev=0, acked_rev=-1, in_flight_id=None, in_flight_rev=-1):
        self.rev, self.acked_rev, self.value = rev, acked_rev, value
        self.in_flight_id, self.in_flight_rev = in_flight_id, in_flight_rev

    @staticmethod
    def effective(handle, share_on: bool):
        return handle if share_on and handle is not None and is_valid(handle) else None

    def with_effective(self, value) -> "ShareState":
        if value == self.value:
            return self
        return ShareState(value, min(self.rev + 1, 2_147_483_647), self.acked_rev, self.in_flight_id, self.in_flight_rev)

    @property
    def needs_send(self) -> bool:
        return self.acked_rev < self.rev

    def sent(self, msg_id: str) -> "ShareState":
        return ShareState(self.value, self.rev, self.acked_rev, msg_id, self.rev)

    def on_ack(self, msg_id: str) -> "ShareState":
        if msg_id != self.in_flight_id:
            return self
        return ShareState(self.value, self.rev, max(self.acked_rev, self.in_flight_rev), None, -1)

    def to_json(self) -> dict:
        return {"rev": self.rev, "ackedRev": self.acked_rev, "inFlightId": self.in_flight_id,
                "inFlightRev": self.in_flight_rev, "value": self.value}

    @staticmethod
    def from_json(o) -> "ShareState":
        if not isinstance(o, dict):
            return ShareState()
        return ShareState(o.get("value"), o.get("rev", 0), o.get("ackedRev", -1), o.get("inFlightId"), o.get("inFlightRev", -1))


def accept_received(stored_rev: int, rev: int) -> bool:
    """Receiver rule: store (peerIg, peerIgRev) only when rev > the stored peerIgRev (initial -1)."""
    return rev > stored_rev
