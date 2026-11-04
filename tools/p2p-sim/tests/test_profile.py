"""SPEC 5d.6: Instagram handle normalisation and the chat `profile` plaintext."""
import json
import os
import unittest

from conjunction_p2p import canonjson, crypto, instagram, messages as M
from conjunction_p2p.frames import FrameError
from conjunction_p2p.link import SECURE, Friend

from tests.helpers import AURORA, ORION, pair, policy, pump, types

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))


def load(name):
    with open(os.path.join(ROOT, "testdata", name), encoding="utf-8") as f:
        return json.load(f)


def secure_pair():
    a, b, ida, idb = pair()
    a.start()
    pump(a, b)
    b.swipe("right"); a.swipe("right")
    pump(a, b)
    assert a.state == SECURE and b.state == SECURE
    a.take_events(); b.take_events()
    return a, b, ida, idb


class InstagramHandleTests(unittest.TestCase):
    def test_golden_normalize(self):
        g = load("golden_instagram.json")
        for c in g["normalize"]:
            self.assertEqual(c["handle"], instagram.normalize(c["input"]), c)
        for c in g["isValid"]:
            self.assertEqual(c["valid"], instagram.is_valid(c["handle"]), c)

    def test_wire_vectors(self):
        w = load("p2p_wire.json")
        for c in w["normalize"]:
            self.assertEqual(c["handle"], instagram.normalize(c["input"]))
        for c in w["profileValidation"]:
            self.assertEqual(c["stored"], M.decode_chat_plaintext(c["plaintext"].encode())["ig"], c)
        for c in w["profileMalformed"]:
            with self.assertRaises(FrameError) as e:
                M.decode_chat_plaintext(c["plaintext"].encode())
            self.assertEqual(c["code"], e.exception.code)
        for p in w["plaintexts"]:
            msg = M.chat_profile(p["id"], p["ts"], p["rev"], p["ig"])
            self.assertEqual(p["json"], canonjson.dumps(msg))
            self.assertEqual(msg, M.decode_chat_plaintext(p["json"].encode()))

    def test_profile_url(self):
        self.assertEqual("https://www.instagram.com/aurora.stars/", instagram.profile_url("aurora.stars"))


class ShareStateTests(unittest.TestCase):
    def test_lifecycle(self):
        st = instagram.ShareState(instagram.ShareState.effective("aurora", True))
        self.assertEqual((0, -1, "aurora"), (st.rev, st.acked_rev, st.value))
        self.assertTrue(st.needs_send)
        st = st.sent("a" * 32)
        self.assertIs(st, st.on_ack("b" * 32))          # other ids are chat acks
        st = st.on_ack("a" * 32)
        self.assertFalse(st.needs_send)
        self.assertIs(st, st.with_effective("aurora"))  # unchanged value: same rev
        st = st.with_effective(instagram.ShareState.effective("aurora", False))
        self.assertEqual((1, None), (st.rev, st.value))
        self.assertTrue(st.needs_send)
        # A change while a send is in flight: the old ack marks only the old rev.
        st = st.sent("c" * 32).with_effective("new.handle")
        st = st.on_ack("c" * 32)
        self.assertEqual((2, 1), (st.rev, st.acked_rev))
        self.assertTrue(st.needs_send)
        self.assertEqual(st.to_json(), instagram.ShareState.from_json(st.to_json()).to_json())

    def test_receiver_rule(self):
        self.assertTrue(instagram.accept_received(-1, 0))
        self.assertTrue(instagram.accept_received(2, 3))
        self.assertFalse(instagram.accept_received(3, 3))
        self.assertFalse(instagram.accept_received(3, 2))


class ProfileLinkTests(unittest.TestCase):
    def test_profile_exchange_after_mutual_match(self):
        a, b, _, _ = secure_pair()
        mid = a.send_profile(0, "aurora.stars")
        pump(a, b)
        ev = b.take_events()
        self.assertEqual(["profile"], types(ev))
        self.assertEqual({"id": mid, "kind": "profile", "rev": 0, "ig": "aurora.stars"},
                         {k: ev[0]["message"][k] for k in ("id", "kind", "rev", "ig")})
        self.assertEqual([{"type": "delivered", "id": mid}], a.take_events())
        b.send_profile(0, None)
        pump(a, b)
        self.assertIsNone(a.take_events()[0]["message"]["ig"])

    def test_profile_never_before_secure(self):
        a, b, _, _ = pair()
        a.start()
        pump(a, b)  # MATCHED
        with self.assertRaises(RuntimeError):
            a.send_profile(0, "aurora")

    def test_send_rejects_unnormalised_handles(self):
        a, b, _, _ = secure_pair()
        for bad in ("Aurora", "@aurora", "", "a" * 31):
            with self.assertRaises(ValueError):
                a.send_profile(0, bad)
        with self.assertRaises(ValueError):
            a.send_profile(-1, None)

    def test_handle_not_in_hello_or_card(self):
        a, b, _, _ = pair()
        a.start()
        frames = []
        for _ in range(20):
            fa, fb = a.take_frames(), b.take_frames()
            if not fa and not fb:
                break
            frames += fa + fb
            for f in fa:
                b.on_frame(f, 1000)
            for f in fb:
                a.on_frame(f, 1000)
        blob = b"".join(frames)
        self.assertNotIn(b"aurora.stars", blob)
        self.assertNotIn(b"instagram", blob)

    def test_friend_reconnect_vector(self):
        w = load("p2p_wire.json")
        steps = w["profileTranscript"]["steps"]
        self.assertEqual(["hello", "hello", "keyx", "keyx", "kc", "kc", "chat", "ack", "bye"],
                         [s["type"] for s in steps if "type" in s])

    def test_friend_reconnect_live(self):
        ida, idb = crypto.KeyPair.generate(), crypto.KeyPair.generate()
        fs = crypto.friend_secret(ida, idb.public)
        a, b, _, _ = pair(pol_a=policy([Friend(idb.public, fs, "Orion")]),
                          pol_b=policy([Friend(ida.public, fs, "Aurora")]), ida=ida, idb=idb)
        a.start()
        pump(a, b)
        self.assertEqual(SECURE, a.state)
        self.assertIn("friend_nearby", types(a.take_events()))
        # The app can backfill peerCompat from the verified link (SPEC 5d.4).
        self.assertEqual(ORION.compat["sun"], a.peer_hello["compat"]["sun"])
        mid = a.send_profile(4, "aurora.stars")
        pump(a, b)
        self.assertEqual("aurora.stars", [e for e in b.take_events() if e["type"] == "profile"][0]["message"]["ig"])
        self.assertIn({"type": "delivered", "id": mid}, a.take_events())


if __name__ == "__main__":
    unittest.main()
