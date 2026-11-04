import datetime

from conjunction_p2p import crypto
from conjunction_p2p.link import CENTRAL, PERIPHERAL, Friend, Link, LocalProfile, Policy

TODAY = datetime.date(2026, 11, 3)

AURORA = LocalProfile(
    eid="0f" * 16,
    match={"gender": "female", "seeking": "male", "relationship": "single", "lookingFor": "both"},
    compat={"sun": 35, "moon": 21075, "venus": 33012, "mars": 9120, "asc": 1550, "animal": "rat", "stem": "geng",
            "blood": "o", "birthDate": "1996-03-20", "birthHour": 6},
    first_name="Aurora", bio="Stargazer, tea, long walks.", photo=bytes(range(256)) * 40, photo_w=480, photo_h=640)

ORION = LocalProfile(
    eid="a1" * 16,
    match={"gender": "male", "seeking": "female", "relationship": "single", "lookingFor": "dating"},
    compat={"sun": 13978, "moon": 3789, "venus": 11859, "mars": 4979, "asc": None, "animal": "horse", "stem": "ji",
            "blood": None, "birthDate": "1990-08-12", "birthHour": None},
    first_name="Orion", bio=None)


def policy(friends=None, **kw):
    fr = friends if friends is not None else []
    return Policy(friends=lambda: fr, today=lambda: TODAY, **kw)


def pump(a: Link, b: Link, now=1000, tamper=None, mtu=517):
    """Moves frames both ways until quiet. tamper(frame, direction) may return a modified frame."""
    for _ in range(10000):
        fa, fb = a.take_frames(), b.take_frames()
        if not fa and not fb:
            return
        for f in fa:
            if tamper:
                f = tamper(f, "ab")
            assert len(f) <= min(mtu - 3, 512)
            b.on_frame(f, now)
        for f in fb:
            if tamper:
                f = tamper(f, "ba")
            assert len(f) <= min(mtu - 3, 512)
            a.on_frame(f, now)
    raise AssertionError("pump did not settle")


def pair(pa=AURORA, pb=ORION, pol_a=None, pol_b=None, mtu=517, ida=None, idb=None):
    ida = ida or crypto.KeyPair.generate()
    idb = idb or crypto.KeyPair.generate()
    a = Link(CENTRAL, pa, ida, pol_a or policy(), 0)
    b = Link(PERIPHERAL, pb, idb, pol_b or policy(), 0)
    a.set_att_mtu(mtu)
    b.set_att_mtu(mtu)
    return a, b, ida, idb


def types(events):
    return [e["type"] for e in events]
