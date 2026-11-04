"""Python port of the shared matching engines used by the p2p test peer:
Compatibility.score (docs/COMPATIBILITY.md), MatchFilter.kind (docs/MATCH_FILTER.md) and Age.

Inputs are the wire `compat` / `match` objects (longitudes in integer centidegrees). Verified
against testdata/golden_compat.json and golden_match_filter.json by tests/test_compat.py."""
import datetime

from .messages import ANIMALS, STEMS, BLOOD

# Sign index -> (element index FIRE, EARTH, AIR, WATER ; modality index CARDINAL, FIXED, MUTABLE)
_SIGN_ELEMENT = [0, 1, 2, 3] * 3
_SIGN_MODALITY = [0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2]
SIGNS = ("aries", "taurus", "gemini", "cancer", "leo", "virgo", "libra", "scorpio", "sagittarius",
         "capricorn", "aquarius", "pisces")
_ANIMAL_NAMES = [a.capitalize() for a in ANIMALS]
_STEM_HANZI = "甲乙丙丁戊己庚辛壬癸"
_BLOOD_NAMES = ["A", "B", "AB", "O"]

POINTS = ("sun", "moon", "venus", "mars", "asc")
POINT_LABEL = {"sun": "Sun", "moon": "Moon", "venus": "Venus", "mars": "Mars", "asc": "Ascendant"}
PAIRS = [
    ("sun", "moon", 5), ("moon", "sun", 5),
    ("venus", "mars", 4), ("mars", "venus", 4),
    ("moon", "moon", 4),
    ("sun", "sun", 3),
    ("venus", "venus", 2),
    ("sun", "venus", 2), ("venus", "sun", 2),
    ("moon", "venus", 2), ("venus", "moon", 2),
    ("mars", "mars", 1),
    ("asc", "sun", 2), ("sun", "asc", 2),
    ("asc", "moon", 2), ("moon", "asc", 2),
    ("asc", "asc", 1),
]
ASPECTS = [("conjunction", 0, 800, 600), ("sextile", 6000, 500, 400), ("square", 9000, 700, -400),
           ("trine", 12000, 700, 500), ("opposition", 18000, 800, 200)]
ELEMENT = [[300, -50, 250, -150], [-50, 300, -150, 250], [250, -150, 300, -50], [-150, 250, -50, 300]]
BLOODT = [[80, 50, 70, 85], [50, 80, 85, 75], [70, 85, 75, 60], [85, 75, 60, 80]]
CHINESE_SCORE = {"SAME": 70, "SAME_SELF_PUNISHMENT": 60, "TRINE": 95, "HARMONY": 90, "CLASH": 25, "HARM": 40, "NEUTRAL": 60}
STEM_SCORE = {"COMBINATION": 95, "SAME_STEM": 70, "SAME_ELEMENT": 75, "GENERATING": 80, "CONTROLLING": 45}


def _tdiv(a: int, b: int) -> int:
    """Integer division truncating toward zero (Kotlin/Swift semantics)."""
    q = abs(a) // abs(b)
    return q if (a >= 0) == (b > 0) else -q


def round_div(n: int, d: int) -> int:
    return (2 * n + d) // (2 * d)


def pair_score(lon_a: int, lon_b: int):
    sa, sb = lon_a // 3000, lon_b // 3000
    sign_part = ELEMENT[_SIGN_ELEMENT[sa]][_SIGN_ELEMENT[sb]] + (-50 if _SIGN_MODALITY[sa] == _SIGN_MODALITY[sb] else 100)
    diff = abs(lon_a - lon_b)
    sep = 36000 - diff if diff > 18000 else diff
    best, best_dev = None, None
    for asp in ASPECTS:
        dev = abs(sep - asp[1])
        if dev <= asp[2] and (best_dev is None or dev < best_dev):
            best, best_dev = asp, dev
    aspect_part = _tdiv(best[3] * (best[2] - best_dev), best[2]) if best else 0
    score = max(-1000, min(1000, sign_part + aspect_part))
    return score, best, aspect_part


def chinese_relation(a: str, b: str) -> str:
    x, y = ANIMALS.index(a), ANIMALS.index(b)
    d = abs(x - y)
    if x == y:
        return "SAME_SELF_PUNISHMENT" if x in (4, 6, 9, 11) else "SAME"
    if d % 4 == 0:
        return "TRINE"
    if (x + y) % 12 == 1:
        return "HARMONY"
    if d == 6:
        return "CLASH"
    if (x + y) % 12 == 7:
        return "HARM"
    return "NEUTRAL"


def stem_relation(a: str, b: str) -> str:
    x, y = STEMS.index(a), STEMS.index(b)
    ea, eb = x // 2, y // 2
    if abs(x - y) == 5:
        return "COMBINATION"
    if x == y:
        return "SAME_STEM"
    if ea == eb:
        return "SAME_ELEMENT"
    if (ea + 1) % 5 == eb or (eb + 1) % 5 == ea:
        return "GENERATING"
    return "CONTROLLING"


def score(a: dict, b: dict) -> dict:
    weighted = weights = 0
    contributions = []
    for pa, pb, w in PAIRS:
        la, lb = a.get(pa), b.get(pb)
        if la is None or lb is None:
            continue
        s, asp, part = pair_score(la, lb)
        weighted += w * s
        weights += w
        if asp is not None and part > 0:
            first, second = (pa, pb) if POINTS.index(pa) <= POINTS.index(pb) else (pb, pa)
            contributions.append((f"{POINT_LABEL[first]}–{POINT_LABEL[second]} {asp[0]}", w * part))
    raw = weighted // weights
    western = max(0, min(100, round_div(raw + 145, 5)))
    crel = chinese_relation(a["animal"], b["animal"])
    srel = stem_relation(a["stem"], b["stem"])
    chinese = round_div(3 * CHINESE_SCORE[crel] + 2 * STEM_SCORE[srel], 5)
    blood = None
    if a.get("blood") and b.get("blood"):
        blood = BLOODT[BLOOD.index(a["blood"])][BLOOD.index(b["blood"])]
    if blood is not None:
        total = round_div(55 * western + 30 * chinese + 15 * blood, 100)
    else:
        total = round_div(55 * western + 30 * chinese, 85)
    twin = a["birthDate"] == b["birthDate"]
    exact = twin and a.get("birthHour") is not None and a.get("birthHour") == b.get("birthHour")
    shares = (not twin) and a["birthDate"][5:] == b["birthDate"][5:]
    hl = []
    if exact:
        hl.append("Exact twins: same day, same hour")
    elif twin:
        hl.append("Birthday twins")
    elif shares:
        hl.append("Same birthday")
    seen = []
    for label, _ in sorted(contributions, key=lambda c: (-c[1], c[0])):
        if label not in seen:
            seen.append(label)
    hl += seen[:2]
    xi, yi = sorted((ANIMALS.index(a["animal"]), ANIMALS.index(b["animal"])))
    xn, yn = _ANIMAL_NAMES[xi], _ANIMAL_NAMES[yi]
    ch = {"TRINE": f"{xn} & {yn}: San He trine", "HARMONY": f"{xn} & {yn}: Liu He secret friends",
          "CLASH": f"{xn} & {yn}: opposites attract", "SAME": f"Both {xn}s",
          "SAME_SELF_PUNISHMENT": f"Both {xn}s"}.get(crel)
    if ch:
        hl.append(ch)
    if srel == "COMBINATION":
        s1, s2 = sorted((STEMS.index(a["stem"]), STEMS.index(b["stem"])))
        hl.append(f"Day Masters combine ({_STEM_HANZI[s1]}{_STEM_HANZI[s2]})")
    if blood is not None and blood >= 80:
        b1, b2 = sorted((BLOOD.index(a["blood"]), BLOOD.index(b["blood"])))
        hl.append(f"Blood types {_BLOOD_NAMES[b1]} + {_BLOOD_NAMES[b2]}")
    return {
        "total": max(0, min(100, total)), "western": western, "chinese": chinese, "blood": blood,
        "highlights": hl[:4], "isBirthdayTwin": twin, "isExactTwin": exact, "sharesBirthday": shares,
        "chineseRelation": crel, "stemRelation": srel,
    }


def _includes_dating(lf): return lf in ("dating", "both")
def _includes_friends(lf): return lf in ("friends", "both")
def _seeks(seeking, gender): return seeking == "both" or seeking == gender


def match_kind(a: dict, b: dict):
    """MatchFilter.kind on wire `match` objects -> 'dating' | 'friends' | None."""
    if (_includes_dating(a["lookingFor"]) and _includes_dating(b["lookingFor"])
            and _seeks(a["seeking"], b["gender"]) and _seeks(b["seeking"], a["gender"])
            and a["relationship"] == "single" and b["relationship"] == "single"):
        return "dating"
    if _includes_friends(a["lookingFor"]) and _includes_friends(b["lookingFor"]):
        return "friends"
    return None


def age_on(birth: str, today: datetime.date) -> int:
    bd = datetime.date.fromisoformat(birth)
    if bd > today:
        raise ValueError("birth date in the future")
    years = today.year - bd.year
    if (today.month, today.day) < (bd.month, bd.day):
        years -= 1
    return years


def is_adult(birth: str, today: datetime.date) -> bool:
    bd = datetime.date.fromisoformat(birth)
    return bd <= today and age_on(birth, today) >= 18


def sun_sign(c: dict) -> str:
    return SIGNS[c["sun"] // 3000]


def evaluate(local_hello: dict, peer_hello: dict, today: datetime.date):
    """Matching glue (spec section 9): returns None (no match) or a dict with kind + score."""
    lc, pc = local_hello["compat"], peer_hello["compat"]
    if not is_adult(lc["birthDate"], today) or not is_adult(pc["birthDate"], today):
        return None
    kind = match_kind(local_hello["match"], peer_hello["match"])
    if kind is None:
        return None
    return {"kind": kind, "score": score(lc, pc), "peerAge": age_on(pc["birthDate"], today)}
