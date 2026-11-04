#!/usr/bin/env python3
"""Generates testdata/p2p_wire.json from the Python reference implementation in tools/p2p-sim.

    python3 -m venv /tmp/cj-venv && /tmp/cj-venv/bin/pip install -r tools/p2p-sim/requirements.txt
    /tmp/cj-venv/bin/python testdata/tools/make_p2p_wire.py

Everything is deterministic (fixed private scalars, nonces, ids, timestamps, seeded RNG), so
re-running produces an identical file. Kotlin (P2pWireVectorsTest) and Swift check against it."""
import datetime
import hashlib
import json
import os
import random
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools", "p2p-sim"))

from conjunction_p2p import canonjson, compat, crypto  # noqa: E402
from conjunction_p2p import consts as C  # noqa: E402
from conjunction_p2p import messages as M  # noqa: E402
from conjunction_p2p.frames import encode_frames, max_frame_len  # noqa: E402
from conjunction_p2p.link import CENTRAL, PERIPHERAL, Link, LocalProfile, Policy  # noqa: E402

TODAY = datetime.date(2026, 11, 3)
NOW = 1_000

# Fixed test scalars (never use outside tests).
D_IK_A = 0x1C2E3F4A5B6C7D8E9FA0B1C2D3E4F5061728394A5B6C7D8E9FA0B1C2D3E4F506
D_IK_B = 0x7A6B5C4D3E2F10011223344556677889900AABBCCDDEEFF0011223344556677
D_EK_A = 0x0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20
D_EK_B = 0x2122232425262728292A2B2C2D2E2F303132333435363738393A3B3C3D3E3F40

PHOTO = hashlib.sha256(b"conjunction test photo").digest() * 32  # 1024 deterministic bytes

AURORA = LocalProfile(
    eid="0f1e2d3c4b5a69788796a5b4c3d2e1f0",
    match={"gender": "female", "seeking": "male", "relationship": "single", "lookingFor": "both"},
    compat={"sun": 35, "moon": 21075, "venus": 33012, "mars": 9120, "asc": 1550, "animal": "rat", "stem": "geng",
            "blood": "o", "birthDate": "1996-03-20", "birthHour": 6},
    first_name="Aurora", bio="Stargazer. Tea, long walks, Saturn returns.", photo=PHOTO, photo_w=480, photo_h=640)
ORION = LocalProfile(
    eid="a1b2c3d4e5f60718293a4b5c6d7e8f90",
    match={"gender": "male", "seeking": "female", "relationship": "single", "lookingFor": "dating"},
    compat={"sun": 13978, "moon": 3789, "venus": 11859, "mars": 4979, "asc": None, "animal": "horse", "stem": "ji",
            "blood": None, "birthDate": "1990-08-12", "birthHour": None},
    first_name="Orion", bio=None)


def hx(b: bytes) -> str:
    return b.hex()


def frames_at(msg, blob=b"", msg_id=0):
    body = M.encode(msg, blob)
    return {str(mtu): [hx(f) for f in encode_frames(msg_id, body, max_frame_len(mtu))] for mtu in (23, 185, 517)}


def main():
    ik_a, ik_b = crypto.KeyPair.from_scalar(D_IK_A), crypto.KeyPair.from_scalar(D_IK_B)
    ek_a, ek_b = crypto.KeyPair.from_scalar(D_EK_A), crypto.KeyPair.from_scalar(D_EK_B)
    keys = {n: {"d": f"{k.scalar:064x}", "pub": hx(k.public)} for n, k in
            (("identityA", ik_a), ("identityB", ik_b), ("ephemeralA", ek_a), ("ephemeralB", ek_b))}

    # --- canonical messages
    tags = [M.b64(bytes([i] * 8)) for i in range(8)]
    photo_sha = hashlib.sha256(PHOTO).hexdigest()
    samples = [
        ("hello", M.hello(AURORA.eid, AURORA.match, AURORA.compat, tags), b""),
        ("hello_unknown_time", M.hello(ORION.eid, ORION.match, ORION.compat, tags[:0] + [M.b64(b"\xff" * 8)] * 8), b""),
        ("card_with_photo", M.card("dating", "Aurora", AURORA.bio, {"sha256": photo_sha, "size": len(PHOTO), "w": 480, "h": 640}), b""),
        ("card_escapes", M.card("friends", "Zoë \"Z\" \\ 星\n\t\u0001😀", None, None), b""),
        ("photo", M.photo(photo_sha), PHOTO),
        ("swipe_right", M.swipe("right"), b""),
        ("swipe_left", M.swipe("left"), b""),
        ("keyx", M.keyx(ik_a.public, ek_a.public), b""),
        ("ack", M.ack("00112233445566778899aabbccddeeff"), b""),
        ("bye", M.bye("no_match"), b""),
        ("error", M.error("rate", "too many frames"), b""),
    ]
    messages = []
    for name, msg, blob in samples:
        j = canonjson.dumps(msg)
        messages.append({"name": name, "json": j, "blobHex": hx(blob), "bodyHex": hx(M.encode(msg, blob)),
                         "framesByMtu": frames_at(msg, blob, msg_id=0x0102)})
        # Self-check: decode(encode(x)) == x
        assert M.decode(M.encode(msg, blob)) == (msg, blob), name

    invalid = [
        ("float longitude", '{"t":"swipe","dir":"right","x":1.5}', "malformed"),
        ("missing dir", '{"t":"swipe"}', "malformed"),
        ("bad enum", '{"t":"swipe","dir":"up"}', "malformed"),
        ("duplicate key", '{"t":"swipe","dir":"right","dir":"left"}', "malformed"),
        ("not an object", '["swipe"]', "malformed"),
        ("non-canonical base64", '{"t":"kc","mac":"AAAAAAAAAAAAAAAAAAAAAB=="}', "malformed"),
        ("short mac", '{"t":"kc","mac":"AAAA"}', "malformed"),
        ("bad eid", '{"t":"hello","pv":1,"pmin":1,"eid":"XYZ","match":{},"compat":{},"ft":[]}', "malformed"),
        ("pmin above pv", '{"t":"hello","pv":1,"pmin":2,"eid":"00000000000000000000000000000000","match":{},"compat":{},"ft":[]}', "malformed"),
        ("bad date", json.dumps(json.loads(canonjson.dumps(M.hello(AURORA.eid, AURORA.match, {**AURORA.compat, "birthDate": "1996-02-30"}, []))), separators=(",", ":")), "malformed"),
        ("longitude out of range", canonjson.dumps(M.hello(AURORA.eid, AURORA.match, {**AURORA.compat, "sun": 36000}, [])), "malformed"),
    ]
    invalid_bodies = []
    for name, j, code in invalid:
        jb = j.encode()
        invalid_bodies.append({"name": name, "bodyHex": hx(len(jb).to_bytes(2, "big") + jb), "code": code})
        try:
            M.decode(len(jb).to_bytes(2, "big") + jb)
            raise AssertionError(f"accepted invalid: {name}")
        except M.FrameError as e:
            assert e.code == code, (name, e.code)
    jb = canonjson.dumpb(M.swipe("right"))
    invalid_bodies.append({"name": "blob on swipe", "bodyHex": hx(len(jb).to_bytes(2, "big") + jb + b"\x00"), "code": "malformed"})
    invalid_bodies.append({"name": "json length past end", "bodyHex": "00ff7b7d", "code": "malformed"})

    invalid_frames = [
        {"name": "frame version 2", "hex": "020000000000017b", "code": "frame_version"},
        {"name": "count 0", "hex": "01000000000000ff", "code": "malformed"},
        {"name": "index >= count", "hex": "01000000020002ff", "code": "malformed"},
        {"name": "header only", "hex": "01000000000001", "code": "malformed"},
        {"name": "continuation of unknown message", "hex": "01000700010002ff", "code": "malformed"},
    ]

    # --- crypto vectors
    ss = ik_a.agree(ik_b.public)
    ee = ek_a.agree(ek_b.public)
    assert ss == ik_b.agree(ik_a.public) and ee == ek_b.agree(ek_a.public)
    sa = crypto.derive_session(ik_a, ek_a, ik_b.public, ek_b.public)
    sb = crypto.derive_session(ik_b, ek_b, ik_a.public, ek_a.public)
    assert sa.send_key == sb.recv_key and sa.recv_key == sb.send_key and sa.my_kc == sb.peer_kc
    a_is_l = sa.role == crypto.ROLE_L
    ik_l, ik_h = (ik_a.public, ik_b.public) if a_is_l else (ik_b.public, ik_a.public)
    ek_l, ek_h = (ek_a.public, ek_b.public) if a_is_l else (ek_b.public, ek_a.public)
    okm = crypto.hkdf(ee + ss, sa.transcript, crypto.INFO_SESSION, 96)
    k_lh, k_hl = okm[:32], okm[32:64]

    gcm = []
    texts = [("A", 0, "Hi Orion! ☌ Your Leo sun and my Pisces moon…", 1762171200000),
             ("A", 1, "Tea at the observatory?", 1762171260000),
             ("B", 0, "Yes 🌙", 1762171300000)]
    for side, ctr, text, ts in texts:
        s = sa if side == "A" else sb
        mid = hashlib.sha256(f"{side}{ctr}".encode()).hexdigest()[:32]
        inner = M.chat_text(mid, ts, text)
        pt = canonjson.dumpb(inner)
        ct = crypto.seal(s.send_key, ctr, pt)
        env = M.chat(ctr, ct)
        gcm.append({"sender": side, "key": hx(s.send_key), "ctr": ctr, "nonce": hx(crypto.nonce(ctr)),
                    "aadHex": hx(crypto.LBL_CHAT_AAD), "plaintext": pt.decode(), "ciphertextHex": hx(ct),
                    "envelope": canonjson.dumps(env), "framesByMtu": frames_at(env, msg_id=3)})
    # A tampered envelope must fail.
    bad = bytearray(bytes.fromhex(gcm[0]["ciphertextHex"])); bad[-1] ^= 0x80
    try:
        crypto.open_(sa.recv_key if False else sb.recv_key, 0, bytes(bad))
        raise AssertionError("tamper accepted")
    except crypto.CryptoError:
        pass

    fs_a = crypto.friend_secret(ik_a, ik_b.public)
    assert fs_a == crypto.friend_secret(ik_b, ik_a.public)
    eids = ["00000000000000000000000000000000", AURORA.eid, ORION.eid, "ffffffffffffffffffffffffffffffff"]
    friend = {"friendSecret": hx(fs_a),
              "salt": hx(crypto.sha256(crypto.LBL_FRIEND + ik_l + ik_h)),
              "tags": [{"eid": e, "tagHex": hx(crypto.friend_tag(fs_a, e)), "tagB64": M.b64(crypto.friend_tag(fs_a, e))} for e in eids]}

    hkdf_plain = [
        # RFC 5869 test case 1 (sanity of the HKDF primitive itself).
        {"ikm": "0b" * 22, "salt": "000102030405060708090a0b0c", "info": "f0f1f2f3f4f5f6f7f8f9", "length": 42,
         "okm": hx(crypto.hkdf(bytes.fromhex("0b" * 22), bytes.fromhex("000102030405060708090a0b0c"), bytes.fromhex("f0f1f2f3f4f5f6f7f8f9"), 42))},
    ]
    assert hkdf_plain[0]["okm"] == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"

    crypto_v = {
        "keys": keys,
        "ecdh": {"identity": hx(ss), "ephemeral": hx(ee)},
        "order": {"identityAIsL": a_is_l, "ikL": hx(ik_l), "ikH": hx(ik_h), "ekL": hx(ek_l), "ekH": hx(ek_h)},
        "session": {"transcriptSalt": hx(sa.transcript), "ikm": hx(ee + ss), "info": crypto.INFO_SESSION.decode(),
                    "okm": hx(okm), "kLH": hx(k_lh), "kHL": hx(k_hl), "kConf": hx(sa.conf_key),
                    "kcL": hx(crypto.kc_mac(sa.conf_key, crypto.ROLE_L)), "kcH": hx(crypto.kc_mac(sa.conf_key, crypto.ROLE_H)),
                    "kcMessageA": canonjson.dumps(M.kc(sa.my_kc))},
        "hkdf": hkdf_plain,
        "aesGcm": gcm,
        "tamperedCiphertextHex": hx(bytes(bad)),
        "friend": friend,
        "safetyCode": crypto.safety_code(ik_a.public, ik_b.public),
        "profileFingerprint": [{"compat": canonjson.dumps(M.compat_obj(p.compat)),
                                "fingerprint": crypto.profile_fingerprint(canonjson.dumpb(M.compat_obj(p.compat)))}
                               for p in (AURORA, ORION)],
    }

    # --- matching glue
    ha = M.hello(AURORA.eid, AURORA.match, AURORA.compat, [])
    hb = M.hello(ORION.eid, ORION.match, ORION.compat, [])
    ev = compat.evaluate(ha, hb, TODAY)
    match_v = {"today": TODAY.isoformat(), "a": "hello", "b": "hello_unknown_time",
               "kind": ev["kind"], "total": ev["score"]["total"], "western": ev["score"]["western"],
               "chinese": ev["score"]["chinese"], "blood": ev["score"]["blood"],
               "isBirthdayTwin": ev["score"]["isBirthdayTwin"], "isExactTwin": ev["score"]["isExactTwin"],
               "peerAgeSeenByA": ev["peerAge"], "highlights": ev["score"]["highlights"]}

    # --- full link transcript (A = Aurora central, B = Orion peripheral), ATT_MTU 185
    transcript = []
    a = Link(CENTRAL, AURORA, ik_a, Policy(today=lambda: TODAY), 0, ephemeral=ek_a, rng=random.Random(1))
    b = Link(PERIPHERAL, ORION, ik_b, Policy(today=lambda: TODAY), 0, ephemeral=ek_b, rng=random.Random(2))
    a.set_att_mtu(185); b.set_att_mtu(185)

    def record(side, frames):
        # group consecutive frames by message id
        cur, cur_id = [], None
        for f in frames:
            mid = int.from_bytes(f[1:3], "big")
            if cur and mid != cur_id:
                flush(side, cur)
                cur = []
            cur.append(f); cur_id = mid
        if cur:
            flush(side, cur)

    def flush(side, frames):
        body = b"".join(f[7:] for f in frames)
        msg, blob = M.decode(body)
        transcript.append({"from": side, "type": msg["t"], "json": canonjson.dumps(msg), "blobHex": hx(blob),
                           "frames": [hx(f) for f in frames]})

    def pump():
        for _ in range(100):
            fa, fb = a.take_frames(), b.take_frames()
            if not fa and not fb:
                return
            record("central", fa)
            for f in fa:
                b.on_frame(f, NOW)
            record("peripheral", fb)
            for f in fb:
                a.on_frame(f, NOW)

    a.start(); pump()
    transcript.append({"action": "swipe", "side": "peripheral", "dir": "right"}); b.swipe("right"); pump()
    transcript.append({"action": "swipe", "side": "central", "dir": "right"}); a.swipe("right"); pump()
    assert a.state == "secure" and b.state == "secure"
    for side, link, mid, ts, text in (("central", a, "c" * 32, 1762171200000, "Hi Orion ☌"),
                                      ("peripheral", b, "d" * 32, 1762171230000, "Hello Aurora!")):
        transcript.append({"action": "chat", "side": side, "id": mid, "ts": ts, "text": text})
        link.send_chat(text, msg_id=mid, ts=ts); pump()
    transcript.append({"action": "close", "side": "central", "reason": "done"}); a.close("done"); pump()

    out = {
        "description": "Conjunction P2P wire golden vectors (docs/P2P_PROTOCOL.md). Generated by the Python "
                       "reference (tools/p2p-sim) with testdata/tools/make_p2p_wire.py; do not edit by hand.",
        "protocolVersion": C.PROTOCOL_VERSION,
        "uuids": {"service": C.SERVICE_UUID, "inbox": C.INBOX_UUID, "outbox": C.OUTBOX_UUID},
        "frameLenByMtu": {str(m): max_frame_len(m) for m in (23, 27, 185, 247, 517, 600)},
        "messages": messages,
        "invalidBodies": invalid_bodies,
        "invalidFrames": invalid_frames,
        "crypto": crypto_v,
        "match": match_v,
        "transcript": {"attMtu": 185, "today": TODAY.isoformat(), "nowMs": NOW,
                       "central": {"profile": "hello", "firstName": AURORA.first_name, "bio": AURORA.bio,
                                   "photoHex": hx(PHOTO), "photoW": 480, "photoH": 640,
                                   "identity": "identityA", "ephemeral": "ephemeralA"},
                       "peripheral": {"profile": "hello_unknown_time", "firstName": ORION.first_name, "bio": ORION.bio,
                                      "photoHex": "", "identity": "identityB", "ephemeral": "ephemeralB"},
                       "steps": transcript},
    }
    path = os.path.join(ROOT, "testdata", "p2p_wire.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print(f"wrote {path}: {len(messages)} messages, {len(transcript)} transcript steps")


if __name__ == "__main__":
    main()
