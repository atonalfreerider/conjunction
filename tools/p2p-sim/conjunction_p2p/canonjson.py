"""Canonical JSON (docs/P2P_PROTOCOL.md section 5.1).

Encoding: keys in the order given by the caller (dict insertion order), no whitespace, UTF-8,
integers only (no floats), strings escape only '"', '\\' and U+0000..U+001F (\\b \\f \\n \\r \\t
short forms, others \\u00xx lowercase). Equivalent to
json.dumps(obj, ensure_ascii=False, separators=(",", ":")) for the value types we allow.
"""
import json


def _check(v, path="$"):
    if v is None or isinstance(v, (bool, str)):
        return
    if isinstance(v, int):
        if not (-(2**63) <= v < 2**63):
            raise ValueError(f"{path}: integer out of int64 range")
        return
    if isinstance(v, float):
        raise ValueError(f"{path}: floats are not allowed on the wire")
    if isinstance(v, dict):
        for k, x in v.items():
            if not isinstance(k, str):
                raise ValueError(f"{path}: non-string key")
            _check(x, f"{path}.{k}")
        return
    if isinstance(v, (list, tuple)):
        for i, x in enumerate(v):
            _check(x, f"{path}[{i}]")
        return
    raise ValueError(f"{path}: unsupported type {type(v).__name__}")


def dumps(obj) -> str:
    _check(obj)
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def dumpb(obj) -> bytes:
    return dumps(obj).encode("utf-8")


def loads(data: bytes):
    """Strict-ish decode: UTF-8, valid JSON, no floats, no duplicate keys."""
    text = data.decode("utf-8")  # raises on invalid UTF-8

    def no_dupes(pairs):
        d = {}
        for k, v in pairs:
            if k in d:
                raise ValueError(f"duplicate key {k!r}")
            d[k] = v
        return d

    def no_float(s):
        raise ValueError("floats are not allowed on the wire")

    def no_const(s):
        raise ValueError(f"{s} not allowed")

    return json.loads(text, object_pairs_hook=no_dupes, parse_float=no_float, parse_constant=no_const)
