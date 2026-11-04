import os
import random
import unittest

from conjunction_p2p import consts as C, crypto, messages as M
from conjunction_p2p.frames import FrameError, Reassembler, encode_frames, pack_body
from conjunction_p2p.link import CLOSED, KEYX, MATCHED, SECURE, Friend, dedup_keep

from tests.helpers import AURORA, ORION, pair, policy, pump, types


def full_match(mtu=517):
    a, b, ida, idb = pair(mtu=mtu)
    a.start()
    pump(a, b)
    return a, b, ida, idb


class FrameTests(unittest.TestCase):
    def test_roundtrip_random_sizes_and_mtus(self):
        rng = random.Random(7)
        for _ in range(300):
            mtu = rng.choice([23, 27, 185, 247, 517, rng.randint(23, 600)])
            fl = max(20, min(mtu - 3, 512))
            body = os.urandom(rng.randint(1, 5000))
            frames = encode_frames(rng.randint(0, 65535), body, fl)
            self.assertTrue(all(len(f) <= fl for f in frames))
            r = Reassembler()
            out = [r.accept(f, 0) for f in frames]
            self.assertEqual(out[-1], body)
            self.assertTrue(all(o is None for o in out[:-1]))

    def test_interleaved_messages(self):
        r = Reassembler()
        a = encode_frames(1, b"A" * 100, 20)
        b = encode_frames(2, b"B" * 100, 20)
        got = []
        for fa, fb in zip(a, b):
            for f in (fa, fb):
                x = r.accept(f, 0)
                if x:
                    got.append(x)
        self.assertEqual(got, [b"A" * 100, b"B" * 100])

    def test_out_of_order_and_garbage(self):
        frames = encode_frames(5, os.urandom(200), 20)
        r = Reassembler()
        r.accept(frames[0], 0)
        with self.assertRaises(FrameError):
            r.accept(frames[2], 0)
        with self.assertRaises(FrameError) as e:
            r.accept(b"\x02" + frames[0][1:], 0)
        self.assertEqual(e.exception.code, "frame_version")
        rng = random.Random(3)
        for _ in range(2000):
            junk = bytes(rng.getrandbits(8) for _ in range(rng.randint(0, 40)))
            try:
                Reassembler().accept(junk, 0)
            except FrameError:
                pass

    def test_timeout(self):
        r = Reassembler()
        f = encode_frames(1, b"x" * 50, 20)
        r.accept(f[0], 0)
        self.assertEqual(r.expire(C.REASSEMBLY_TIMEOUT_MS + 1), 1)
        with self.assertRaises(FrameError):
            r.accept(f[1], 20000)


class LinkTests(unittest.TestCase):
    def test_match_swipe_keyx_chat(self):
        a, b, ida, idb = full_match()
        ea, eb = a.take_events(), b.take_events()
        self.assertEqual(types(ea), ["hello", "match", "card"])  # orion has no photo
        self.assertEqual(types(eb), ["hello", "match", "card", "photo"])
        ma = [e for e in ea if e["type"] == "match"][0]
        mb = [e for e in eb if e["type"] == "match"][0]
        self.assertEqual(ma["kind"], "dating")
        self.assertEqual(ma["score"]["total"], mb["score"]["total"])
        self.assertEqual(a.state, MATCHED)
        a.swipe("right"); pump(a, b)
        self.assertEqual(types(b.take_events()), ["peer_swipe"])
        b.swipe("right"); pump(a, b)
        fa, fb = a.take_events(), b.take_events()
        self.assertEqual(types(fa), ["peer_swipe", "friend_new"])
        self.assertEqual(types(fb), ["friend_new"])
        self.assertEqual(fa[1]["friend_secret"], fb[0]["friend_secret"])
        self.assertEqual(fa[1]["safety_code"], fb[0]["safety_code"])
        self.assertEqual(fb[0]["photo"], AURORA.photo)
        self.assertIsNone(fa[1]["photo"])
        self.assertEqual(a.state, SECURE)
        mid = a.send_chat("Hi Orion ✨", ts=1)
        pump(a, b)
        chat = b.take_events()[0]
        self.assertEqual(chat["message"]["text"], "Hi Orion ✨")
        self.assertEqual(types(a.take_events()), ["delivered"])
        b.send_read([mid], ts=2); pump(a, b)
        self.assertEqual(a.take_events()[0]["message"]["refs"], [mid])

    def test_left_swipe_closes_quietly(self):
        a, b, *_ = full_match()
        a.take_events(); b.take_events()
        b.swipe("right"); a.swipe("left"); pump(a, b)
        self.assertEqual(a.state, CLOSED)
        self.assertEqual(b.state, CLOSED)
        self.assertNotIn("friend_new", types(b.take_events()))

    def test_no_match(self):
        orion_friends_only = type(ORION)(**{**ORION.__dict__, "match": {**ORION.match, "lookingFor": "dating"}})
        aurora_friends = type(AURORA)(**{**AURORA.__dict__, "match": {**AURORA.match, "lookingFor": "friends"}})
        a, b, *_ = pair(aurora_friends, orion_friends_only)
        a.start(); pump(a, b)
        self.assertIn("no_match", types(a.take_events()))
        self.assertTrue(a.closed and b.closed)

    def test_small_mtu_skips_photo(self):
        a, b, *_ = full_match(mtu=23)
        self.assertNotIn("photo", types(b.take_events()))
        self.assertEqual(a.state, MATCHED)

    def test_friend_recognition_and_reconnect(self):
        a, b, ida, idb = full_match()
        a.swipe("right"); b.swipe("right"); pump(a, b)
        fa = [e for e in a.take_events() if e["type"] == "friend_new"][0]
        fb = [e for e in b.take_events() if e["type"] == "friend_new"][0]
        fra = [Friend(fa["peer_ik"], fa["friend_secret"], "Orion")]
        frb = [Friend(fb["peer_ik"], fb["friend_secret"], "Aurora")]
        a2pro = type(AURORA)(**{**AURORA.__dict__, "eid": crypto.new_eid()})
        b2pro = type(ORION)(**{**ORION.__dict__, "eid": crypto.new_eid()})
        a2, b2, *_ = pair(a2pro, b2pro, policy(fra), policy(frb), ida=ida, idb=idb)
        a2.start(); pump(a2, b2)
        self.assertEqual(a2.state, SECURE)
        self.assertIn("friend_nearby", types(a2.take_events()))
        self.assertIn("friend_nearby", types(b2.take_events()))
        b2.send_chat("queued while apart"); pump(a2, b2)
        self.assertEqual(a2.take_events()[0]["message"]["text"], "queued while apart")

    def test_impostor_with_replayed_tags_fails(self):
        # Mallory replays Aurora's eid + tags but has a different identity key.
        a, b, ida, idb = full_match()
        a.swipe("right"); b.swipe("right"); pump(a, b)
        fb = [e for e in b.take_events() if e["type"] == "friend_new"][0]
        frb = [Friend(fb["peer_ik"], fb["friend_secret"])]
        fa = [e for e in a.take_events() if e["type"] == "friend_new"][0]
        fra = [Friend(fa["peer_ik"], fa["friend_secret"])]
        mallory_id = crypto.KeyPair.generate()
        m, b2, *_ = pair(AURORA, ORION, policy(fra), policy(frb), ida=mallory_id, idb=idb)
        m.start(); pump(m, b2)
        self.assertEqual(b2.state, CLOSED)
        self.assertIn("protocol_error", types(b2.take_events()))

    def test_tampered_chat_is_rejected(self):
        a, b, *_ = full_match()
        a.swipe("right"); b.swipe("right"); pump(a, b)
        a.take_events(); b.take_events()
        a.send_chat("hello")
        frames = a.take_frames()
        body = b"".join(f[7:] for f in frames)
        msg, _ = M.decode(body)
        ct = bytearray(M.unb64(msg["ct"])); ct[0] ^= 1
        for f in encode_frames(99, M.encode(M.chat(msg["ctr"], bytes(ct))), b.frame_len):
            b.on_frame(f, 1000)
        ev = b.take_events()
        self.assertEqual(ev[0]["code"], "crypto")
        self.assertEqual(b.state, CLOSED)

    def test_chat_before_match_rejected(self):
        a, b, *_ = full_match()
        for f in encode_frames(50, M.encode(M.chat(0, b"\x00" * 32)), 512):
            b.on_frame(f, 1000)
        self.assertEqual(b.state, CLOSED)
        self.assertEqual(b.take_events()[-2]["code"], "state")

    def test_rate_limit(self):
        a, b, *_ = full_match()
        f = encode_frames(7, M.encode({"t": "zzz"}), 512)[0]
        for _ in range(400):
            b.on_frame(f, 1000)
        self.assertEqual(b.state, CLOSED)

    def test_hello_timeout(self):
        a, b, *_ = pair()
        b.tick(C.HELLO_TIMEOUT_MS + 1)
        self.assertEqual(b.state, CLOSED)

    def test_dedup(self):
        lo, hi = "00" * 16, "ff" * 16
        self.assertTrue(dedup_keep(lo, hi, True))
        self.assertFalse(dedup_keep(lo, hi, False))
        self.assertTrue(dedup_keep(hi, lo, False))
        self.assertFalse(dedup_keep(hi, lo, True))

    def test_fuzz_random_frames_never_crash(self):
        rng = random.Random(11)
        for i in range(200):
            a, b, *_ = pair()
            a.start()
            for f in a.take_frames():
                if rng.random() < 0.3:
                    f = bytearray(f); f[rng.randrange(len(f))] ^= 1 << rng.randrange(8); f = bytes(f)
                b.on_frame(f, 1000)
            b.take_events()


if __name__ == "__main__":
    unittest.main()
