"""Message schemas, canonical encoding and validation (docs/P2P_PROTOCOL.md section 5).

Encoders return dicts whose insertion order IS the wire order. decode() validates a received
message and returns (dict, blob); unknown fields are dropped, unknown types return t=None."""
import base64
import binascii
import datetime
import re

from . import canonjson
from . import consts as C
from . import instagram
from .frames import FrameError, pack_body, unpack_body

GENDERS = ("male", "female")
SEEKING = ("male", "female", "both")
RELATIONSHIP = ("single", "in_a_relationship")
LOOKING_FOR = ("dating", "friends", "both")
KINDS = ("dating", "friends")
ANIMALS = ("rat", "ox", "tiger", "rabbit", "dragon", "snake", "horse", "goat", "monkey", "rooster", "dog", "pig")
STEMS = ("jia", "yi", "bing", "ding", "wu", "ji", "geng", "xin", "ren", "gui")
BLOOD = ("a", "b", "ab", "o")
DIRS = ("right", "left")
BYE_REASONS = ("no_match", "done", "dedup", "timeout", "shutdown")
ERROR_CODES = ("version", "malformed", "frame_version", "state", "rate", "too_large", "crypto", "internal")

_HEX32 = re.compile(r"^[0-9a-f]{32}$")
_HEX64 = re.compile(r"^[0-9a-f]{64}$")
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def unb64(s, n=None, what="base64") -> bytes:
    if not isinstance(s, str):
        raise FrameError("malformed", f"{what}: not a string")
    try:
        raw = base64.b64decode(s, validate=True)
    except (binascii.Error, ValueError):
        raise FrameError("malformed", f"{what}: bad base64")
    if b64(raw) != s:
        raise FrameError("malformed", f"{what}: non-canonical base64")
    if n is not None and len(raw) != n:
        raise FrameError("malformed", f"{what}: expected {n} bytes, got {len(raw)}")
    return raw


# ---------------------------------------------------------------- encoders (wire order)

def hello(eid: str, match: dict, compat: dict, ft: list, pv=C.PROTOCOL_VERSION, pmin=C.PROTOCOL_MIN_VERSION) -> dict:
    return {
        "t": "hello", "pv": pv, "pmin": pmin, "eid": eid,
        "match": {
            "gender": match["gender"], "seeking": match["seeking"],
            "relationship": match["relationship"], "lookingFor": match["lookingFor"],
        },
        "compat": compat_obj(compat),
        "ft": list(ft),
    }


def compat_obj(c: dict) -> dict:
    return {
        "sun": c["sun"], "moon": c["moon"], "venus": c["venus"], "mars": c["mars"], "asc": c.get("asc"),
        "animal": c["animal"], "stem": c["stem"], "blood": c.get("blood"),
        "birthDate": c["birthDate"], "birthHour": c.get("birthHour"),
    }


def card(kind: str, first_name: str, bio, photo) -> dict:
    return {
        "t": "card", "kind": kind, "firstName": first_name, "bio": bio,
        "photo": None if photo is None else {
            "sha256": photo["sha256"], "size": photo["size"], "w": photo["w"], "h": photo["h"],
        },
    }


def photo(sha256_hex: str) -> dict:
    return {"t": "photo", "sha256": sha256_hex, "mime": "image/jpeg"}


def swipe(direction: str) -> dict:
    return {"t": "swipe", "dir": direction}


def keyx(ik: bytes, ek: bytes) -> dict:
    return {"t": "keyx", "ik": b64(ik), "ek": b64(ek)}


def kc(mac: bytes) -> dict:
    return {"t": "kc", "mac": b64(mac)}


def chat(ctr: int, ct: bytes) -> dict:
    return {"t": "chat", "ctr": ctr, "ct": b64(ct)}


def ack(msg_id: str) -> dict:
    return {"t": "ack", "id": msg_id}


def bye(reason: str) -> dict:
    return {"t": "bye", "reason": reason}


def error(code: str, detail=None) -> dict:
    return {"t": "error", "code": code, "detail": detail}


def chat_text(msg_id: str, ts: int, text: str) -> dict:
    return {"id": msg_id, "ts": ts, "kind": "text", "text": text}


def chat_read(msg_id: str, ts: int, refs: list) -> dict:
    return {"id": msg_id, "ts": ts, "kind": "read", "refs": list(refs)}


def chat_profile(msg_id: str, ts: int, rev: int, ig) -> dict:
    """Chat plaintext kind `profile` (SPEC 5d.6): the sender's Instagram handle for this friend, or None."""
    return {"id": msg_id, "ts": ts, "kind": "profile", "rev": rev, "ig": ig}


def encode(msg: dict, blob: bytes = b"") -> bytes:
    """Message -> body bytes (u16 json length | canonical JSON | blob)."""
    return pack_body(canonjson.dumpb(msg), blob)


# ---------------------------------------------------------------- validation helpers

def _req(o, k):
    if not isinstance(o, dict) or k not in o:
        raise FrameError("malformed", f"missing field {k!r}")
    return o[k]


def _opt(o, k):
    return o.get(k) if isinstance(o, dict) else None


def _int(v, lo, hi, what):
    if not isinstance(v, int) or isinstance(v, bool) or not (lo <= v <= hi):
        raise FrameError("malformed", f"{what}: expected integer {lo}..{hi}")
    return v


def _enum(v, allowed, what):
    if v not in allowed:
        raise FrameError("malformed", f"{what}: {v!r} not one of {allowed}")
    return v


def _str(v, max_bytes, what, min_bytes=0):
    if not isinstance(v, str):
        raise FrameError("malformed", f"{what}: not a string")
    n = len(v.encode("utf-8", "surrogatepass"))
    if not (min_bytes <= n <= max_bytes):
        raise FrameError("malformed", f"{what}: length {n} bytes not in {min_bytes}..{max_bytes}")
    try:
        v.encode("utf-8")
    except UnicodeEncodeError:
        raise FrameError("malformed", f"{what}: lone surrogate")
    return v


def _hex(v, rx, what):
    if not isinstance(v, str) or not rx.match(v):
        raise FrameError("malformed", f"{what}: bad hex")
    return v


def _date(v, what):
    if not isinstance(v, str) or not _DATE.match(v):
        raise FrameError("malformed", f"{what}: expected YYYY-MM-DD")
    try:
        datetime.date.fromisoformat(v)
    except ValueError:
        raise FrameError("malformed", f"{what}: invalid date")
    return v


def _point(v, what):
    raw = unb64(v, 65, what)
    if raw[0] != 0x04:
        raise FrameError("malformed", f"{what}: not an uncompressed point")
    return raw


def _validate_compat(c):
    out = {}
    for k in ("sun", "moon", "venus", "mars"):
        out[k] = _int(_req(c, k), 0, 35999, f"compat.{k}")
    asc = _opt(c, "asc")
    out["asc"] = None if asc is None else _int(asc, 0, 35999, "compat.asc")
    out["animal"] = _enum(_req(c, "animal"), ANIMALS, "compat.animal")
    out["stem"] = _enum(_req(c, "stem"), STEMS, "compat.stem")
    bl = _opt(c, "blood")
    out["blood"] = None if bl is None else _enum(bl, BLOOD, "compat.blood")
    out["birthDate"] = _date(_req(c, "birthDate"), "compat.birthDate")
    bh = _opt(c, "birthHour")
    out["birthHour"] = None if bh is None else _int(bh, 0, 23, "compat.birthHour")
    return out


def decode(body: bytes):
    """Body bytes -> (validated message dict in canonical field order, blob)."""
    jb, blob = unpack_body(body)
    try:
        o = canonjson.loads(jb)
    except (ValueError, UnicodeDecodeError) as e:
        raise FrameError("malformed", f"bad JSON: {e}")
    if not isinstance(o, dict):
        raise FrameError("malformed", "message is not an object")
    t = _req(o, "t")
    if not isinstance(t, str):
        raise FrameError("malformed", "t is not a string")
    if blob and t != "photo":
        raise FrameError("malformed", f"{t} must not carry a blob")
    if t == "hello":
        pv = _int(_req(o, "pv"), 1, 255, "pv")
        pmin = _int(_req(o, "pmin"), 1, pv, "pmin")
        eid = _hex(_req(o, "eid"), _HEX32, "eid")
        m = _req(o, "match")
        match = {
            "gender": _enum(_req(m, "gender"), GENDERS, "match.gender"),
            "seeking": _enum(_req(m, "seeking"), SEEKING, "match.seeking"),
            "relationship": _enum(_req(m, "relationship"), RELATIONSHIP, "match.relationship"),
            "lookingFor": _enum(_req(m, "lookingFor"), LOOKING_FOR, "match.lookingFor"),
        }
        compat = _validate_compat(_req(o, "compat"))
        ft = _req(o, "ft")
        if not isinstance(ft, list) or len(ft) > C.FRIEND_TAGS_MAX:
            raise FrameError("malformed", "ft: expected list of at most 64 tags")
        for tag in ft:
            unb64(tag, C.FRIEND_TAG_LEN, "ft[]")
        return hello(eid, match, compat, ft, pv, pmin), blob
    if t == "card":
        kind = _enum(_req(o, "kind"), KINDS, "kind")
        name = _str(_req(o, "firstName"), C.MAX_FIRST_NAME_BYTES, "firstName", 1)
        bio = _opt(o, "bio")
        if bio is not None:
            _str(bio, C.MAX_BIO_BYTES, "bio")
        ph = _opt(o, "photo")
        if ph is not None:
            ph = {
                "sha256": _hex(_req(ph, "sha256"), _HEX64, "photo.sha256"),
                "size": _int(_req(ph, "size"), 1, C.MAX_BLOB_LEN, "photo.size"),
                "w": _int(_req(ph, "w"), 1, 640, "photo.w"),
                "h": _int(_req(ph, "h"), 1, 640, "photo.h"),
            }
        return card(kind, name, bio, ph), blob
    if t == "photo":
        sha = _hex(_req(o, "sha256"), _HEX64, "sha256")
        _enum(_req(o, "mime"), ("image/jpeg",), "mime")
        if not blob:
            raise FrameError("malformed", "photo without blob")
        return photo(sha), blob
    if t == "swipe":
        return swipe(_enum(_req(o, "dir"), DIRS, "dir")), blob
    if t == "keyx":
        ik = _point(_req(o, "ik"), "ik")
        ek = _point(_req(o, "ek"), "ek")
        return keyx(ik, ek), blob
    if t == "kc":
        return kc(unb64(_req(o, "mac"), 16, "mac")), blob
    if t == "chat":
        ctr = _int(_req(o, "ctr"), 0, 0xFFFFFFFF, "ctr")
        ct = unb64(_req(o, "ct"), None, "ct")
        if not (17 <= len(ct) <= 2 * C.MAX_CHAT_TEXT_BYTES + 256):
            raise FrameError("malformed", "ct length")
        return chat(ctr, ct), blob
    if t == "ack":
        return ack(_hex(_req(o, "id"), _HEX32, "id")), blob
    if t == "bye":
        return bye(_str(_req(o, "reason"), 32, "reason", 1)), blob
    if t == "error":
        code = _str(_req(o, "code"), 32, "code", 1)
        detail = _opt(o, "detail")
        if detail is not None:
            detail = _str(detail, 200, "detail")
        return error(code, detail), blob
    return {"t": None, "type": t}, blob


def decode_chat_plaintext(pt: bytes) -> dict:
    try:
        o = canonjson.loads(pt)
    except (ValueError, UnicodeDecodeError) as e:
        raise FrameError("malformed", f"chat plaintext: {e}")
    msg_id = _hex(_req(o, "id"), _HEX32, "chat.id")
    ts = _int(_req(o, "ts"), 0, 2**53, "chat.ts")
    kind = _req(o, "kind")
    if kind == "text":
        return chat_text(msg_id, ts, _str(_req(o, "text"), C.MAX_CHAT_TEXT_BYTES, "chat.text", 1))
    if kind == "read":
        refs = _req(o, "refs")
        if not isinstance(refs, list) or not (1 <= len(refs) <= C.MAX_READ_REFS):
            raise FrameError("malformed", "chat.refs")
        return chat_read(msg_id, ts, [_hex(r, _HEX32, "chat.refs[]") for r in refs])
    if kind == "profile":
        rev = _int(_req(o, "rev"), 0, C.MAX_PROFILE_REV, "chat.rev")
        ig = _opt(o, "ig")
        if ig is not None and not isinstance(ig, str):
            raise FrameError("malformed", "chat.ig: expected a string or null")
        # Lenient: a string failing the handle rule is treated as null (SPEC 5d.6).
        return chat_profile(msg_id, ts, rev, instagram.received(ig))
    if not isinstance(kind, str):
        raise FrameError("malformed", "chat.kind: expected a string")
    return {"id": msg_id, "ts": ts, "kind": None}  # unknown kinds are ignored (forward compatibility)
