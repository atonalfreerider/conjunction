"""Crypto of docs/P2P_PROTOCOL.md section 8: P-256 ECDH, HKDF-SHA256, HMAC-SHA256, AES-256-GCM.

All points are X9.63 uncompressed (65 bytes: 0x04 || X || Y). ECDH output = 32-byte X coordinate."""
import hashlib
import hmac
import os
import struct
from dataclasses import dataclass

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

from . import consts as C

INFO_SESSION = b"conjunction/v1/session"
INFO_FRIEND = b"conjunction/v1/friend-tag"
LBL_KEYX = b"CJ1-KEYX"
LBL_FRIEND = b"CJ1-FRIEND"
LBL_TAG = b"CJ1-TAG"
LBL_KC = b"CJ1-KC"
LBL_CHAT_AAD = b"CJ1-CHAT"
LBL_SAFETY = b"CJ1-SAFETY"
LBL_FP = b"CJ1-FP"
ROLE_L = 0x4C  # 'L'
ROLE_H = 0x48  # 'H'


class CryptoError(Exception):
    pass


def sha256(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def hmac_sha256(key: bytes, data: bytes) -> bytes:
    return hmac.new(key, data, hashlib.sha256).digest()


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=length, salt=salt, info=info).derive(ikm)


class KeyPair:
    """A P-256 key pair (identity or link-ephemeral)."""

    def __init__(self, private_key: ec.EllipticCurvePrivateKey):
        self._priv = private_key
        self.public = private_key.public_key().public_bytes(
            serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)

    @staticmethod
    def generate() -> "KeyPair":
        return KeyPair(ec.generate_private_key(ec.SECP256R1()))

    @staticmethod
    def from_scalar(d: int) -> "KeyPair":
        return KeyPair(ec.derive_private_key(d, ec.SECP256R1()))

    @property
    def scalar(self) -> int:
        return self._priv.private_numbers().private_value

    def agree(self, peer_public: bytes) -> bytes:
        try:
            peer = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), peer_public)
        except ValueError as e:
            raise CryptoError(f"invalid peer point: {e}")
        return self._priv.exchange(ec.ECDH(), peer)


def validate_point(pub: bytes) -> None:
    if len(pub) != 65 or pub[0] != 0x04:
        raise CryptoError("point must be 65-byte uncompressed")
    try:
        ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), pub)
    except ValueError as e:
        raise CryptoError(f"point not on P-256: {e}")


def order_ik(my_ik: bytes, peer_ik: bytes):
    """Returns (ik_L, ik_H, my_role) where L is the lexicographically smaller identity key."""
    if my_ik == peer_ik:
        raise CryptoError("peer identity key equals ours")
    if my_ik < peer_ik:
        return my_ik, peer_ik, ROLE_L
    return peer_ik, my_ik, ROLE_H


@dataclass
class Session:
    role: int           # ROLE_L or ROLE_H (this side)
    send_key: bytes
    recv_key: bytes
    conf_key: bytes
    transcript: bytes   # salt = SHA-256 transcript hash
    my_kc: bytes
    peer_kc: bytes


def derive_session(identity: KeyPair, ephemeral: KeyPair, peer_ik: bytes, peer_ek: bytes) -> Session:
    validate_point(peer_ik)
    validate_point(peer_ek)
    ik_l, ik_h, role = order_ik(identity.public, peer_ik)
    ek_l, ek_h = (ephemeral.public, peer_ek) if role == ROLE_L else (peer_ek, ephemeral.public)
    if ephemeral.public == peer_ek:
        raise CryptoError("peer ephemeral key equals ours")
    ss = identity.agree(peer_ik)
    ee = ephemeral.agree(peer_ek)
    th = sha256(LBL_KEYX + ik_l + ik_h + ek_l + ek_h)
    okm = hkdf(ee + ss, th, INFO_SESSION, 96)
    k_lh, k_hl, k_conf = okm[:32], okm[32:64], okm[64:96]
    send, recv = (k_lh, k_hl) if role == ROLE_L else (k_hl, k_lh)
    peer_role = ROLE_H if role == ROLE_L else ROLE_L
    return Session(role, send, recv, k_conf, th, kc_mac(k_conf, role), kc_mac(k_conf, peer_role))


def kc_mac(conf_key: bytes, role: int) -> bytes:
    return hmac_sha256(conf_key, LBL_KC + bytes([role]))[:16]


def nonce(ctr: int) -> bytes:
    return b"\x00\x00\x00\x00" + struct.pack(">Q", ctr)


def seal(key: bytes, ctr: int, plaintext: bytes) -> bytes:
    return AESGCM(key).encrypt(nonce(ctr), plaintext, LBL_CHAT_AAD)


def open_(key: bytes, ctr: int, ct: bytes) -> bytes:
    try:
        return AESGCM(key).decrypt(nonce(ctr), ct, LBL_CHAT_AAD)
    except Exception:
        raise CryptoError("AES-GCM authentication failed")


def friend_secret(identity: KeyPair, peer_ik: bytes) -> bytes:
    validate_point(peer_ik)
    ik_l, ik_h, _ = order_ik(identity.public, peer_ik)
    ss = identity.agree(peer_ik)
    return hkdf(ss, sha256(LBL_FRIEND + ik_l + ik_h), INFO_FRIEND, 32)


def friend_tag(fs: bytes, eid_hex: str) -> bytes:
    return hmac_sha256(fs, LBL_TAG + bytes.fromhex(eid_hex))[:C.FRIEND_TAG_LEN]


def safety_code(ik_a: bytes, ik_b: bytes) -> str:
    ik_l, ik_h = (ik_a, ik_b) if ik_a < ik_b else (ik_b, ik_a)
    h = sha256(LBL_SAFETY + ik_l + ik_h)
    groups = []
    for i in range(6):
        v = int.from_bytes(h[5 * i:5 * i + 5], "big") % 100000
        groups.append(f"{v:05d}")
    return " ".join(groups)


def profile_fingerprint(compat_json: bytes) -> str:
    return sha256(LBL_FP + compat_json)[:16].hex()


def new_eid() -> str:
    return os.urandom(C.EPHEMERAL_ID_BYTES).hex()


def new_msg_id() -> str:
    return os.urandom(16).hex()
