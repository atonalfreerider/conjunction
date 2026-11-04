import json
import os
import unittest

from conjunction_p2p import compat

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))


def centi(x):
    import math
    return None if x is None else int(math.floor(x * 100 + 0.5)) % 36000


def wire(p):
    return {"sun": centi(p["sun"]), "moon": centi(p["moon"]), "venus": centi(p["venus"]), "mars": centi(p["mars"]),
            "asc": centi(p.get("ascendant")), "animal": p["chineseAnimal"], "stem": p["dayStem"],
            "blood": p["bloodType"].lower() if p.get("bloodType") else None,
            "birthDate": p["birthDate"], "birthHour": p.get("birthHour")}


class CompatGolden(unittest.TestCase):
    def test_golden_compat(self):
        with open(os.path.join(ROOT, "testdata", "golden_compat.json")) as f:
            cases = json.load(f)["cases"]
        self.assertGreater(len(cases), 10)
        for c in cases:
            got = compat.score(wire(c["a"]), wire(c["b"]))
            self.assertEqual(got, {k: c["expected"][k] for k in got}, c["id"])

    def test_golden_match_filter(self):
        with open(os.path.join(ROOT, "testdata", "golden_match_filter.json")) as f:
            g = json.load(f)
        n = 0
        for c in g["named"]:
            a = dict(c["a"]); b = dict(c["b"])
            for x in (a, b):
                x["relationship"] = x.pop("relationshipStatus")
            self.assertEqual(compat.match_kind(a, b), c["expected"], c["name"])
            n += 1
        self.assertGreater(n, 5)


if __name__ == "__main__":
    unittest.main()
