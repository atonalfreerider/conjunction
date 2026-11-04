#!/usr/bin/env python3
"""Writes the hand-specified onboarding goldens (SPEC 5d.5): golden_birth_text.json (BirthTextParser),
golden_about_you.json (AboutYouParser) and golden_onboarding_ai.json (OnboardingExtraction: model JSON
validation). The expectations below ARE the specification; Kotlin (:core OnboardingTest) and Swift
(OnboardingTests) must reproduce every field. Run: python3 testdata/tools/make_golden_onboarding.py"""
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TODAY = "2026-11-04"


def b(text, region, date, time, unknown, place, amb=False, note=""):
    return {"text": text, "region": region, "date": date, "time": time, "timeUnknown": unknown,
            "placeQuery": place, "ambiguousDate": amb, "note": note}


BIRTH = [
    b("Aug 7 1994, 4:53 pm, Providence RI", "US", "1994-08-07", "16:53", False, "Providence RI", note="the placeholder example"),
    b("August 7, 1994 4:53pm Providence, RI", "US", "1994-08-07", "16:53", False, "Providence, RI"),
    b("7 Aug 1994 16:53 London", "GB", "1994-08-07", "16:53", False, "London"),
    b("7th of August 1994 at noon in Lisbon, Portugal", "PT", "1994-08-07", "12:00", False, "Lisbon, Portugal"),
    b("1994-08-07 4.53pm Providence", "US", "1994-08-07", "16:53", False, "Providence", note="ISO date, dotted 12-hour time"),
    b("8/7/1994 4pm Boston", "US", "1994-08-07", "16:00", False, "Boston", True, "US: month first"),
    b("8/7/1994 4pm Boston", "GB", "1994-07-08", "16:00", False, "Boston", True, "elsewhere: day first"),
    b("8/7/1994 4pm Boston", None, "1994-07-08", "16:00", False, "Boston", True, "no region: day first"),
    b("8-7-94 Boston", "US", "1994-08-07", None, False, "Boston", True, "two-digit year > 26 -> 19xx"),
    b("8/7/94 Manila", "PH", "1994-08-07", None, False, "Manila", True, "PH writes month first"),
    b("25/12/1990 midnight Paris", "FR", "1990-12-25", "00:00", False, "Paris", note="day > 12: not ambiguous"),
    b("07.08.1994 Berlin", "US", "1994-08-07", None, False, "Berlin", note="D.M.Y always day first"),
    b("Aug 7 1994, time unknown, Providence RI", "US", "1994-08-07", None, True, "Providence RI"),
    b("born in Providence, RI on 8/7/94 at 4:53 pm", "US", "1994-08-07", "16:53", False, "Providence, RI", True, "edge words removed"),
    b("Mar. 3rd, 2001 at 11 a.m. in São Paulo", "BR", "2001-03-03", "11:00", False, "São Paulo"),
    b("12/31/1999 11:59 PM New York", "US", "1999-12-31", "23:59", False, "New York"),
    b("Feb 30 1990 Rome", "IT", None, None, False, "Rome", note="no such date: date null, span still removed"),
    b("I don't know my birth time, Sept 9 1985, Austin TX", "US", "1985-09-09", None, True, "Austin TX"),
    b("I don’t know, Sept 9 1985, Austin TX", "US", "1985-09-09", None, True, "Austin TX", note="curly apostrophe"),
    b("", "US", None, None, False, None),
    b("Tokyo", "JP", None, None, False, "Tokyo"),
    b("12am Jan 1 2000 Sydney", "AU", "2000-01-01", "00:00", False, "Sydney", note="12 am = 00:00"),
    b("Jan 1 2000 12pm", "US", "2000-01-01", "12:00", False, None, note="12 pm = 12:00, no place"),
    b("5/5/05 Dublin", "IE", "2005-05-05", None, False, "Dublin", note="equal parts are not ambiguous; 05 <= 26 -> 2005"),
    b("3/4/30", "US", "1930-03-04", None, False, None, True),
    b("June 15 1988, 25:61, Oslo", "NO", "1988-06-15", None, False, "Oslo", note="invalid 24-hour time: dropped"),
    b("Born Nov 3 1995 in Sydney, Australia, unknown time", "AU", "1995-11-03", None, True, "Sydney, Australia"),
    b("may 5 1990 6:30 am  Madrid", "ES", "1990-05-05", "06:30", False, "Madrid"),
    b("Aug 7 1994 4:53 pm　Providence", "US", "1994-08-07", "16:53", False, "Providence", note="Unicode spaces"),
    b("Ontario, Canada Jan 2 1980", "CA", "1980-01-02", None, False, "Ontario, Canada", note="'On' of Ontario is not an edge word"),
    b("Aug 7 1994 in Turin", "IT", "1994-08-07", None, False, "Turin"),
    b("midday 1 May 1990 Lagos", "NG", "1990-05-01", "12:00", False, "Lagos"),
    b("12/13/2001 Chicago", "US", "2001-12-13", None, False, "Chicago"),
    b("13/12/2001", "US", None, None, False, None, note="US month 13: no date"),
    b("I was born on July 4, 1976 at 9:15 pm in Philadelphia", "US", "1976-07-04", "21:15", False, "Philadelphia"),
    b("4:53 pm", "US", None, "16:53", False, None),
    b("Providence RI 1994-08-07 16:53", "US", "1994-08-07", "16:53", False, "Providence RI"),
    b("2030-01-01 Paris", "FR", "2030-01-01", None, False, "Paris", note="parser keeps a future date; isAcceptableDate rejects it"),
]

ACCEPTABLE = [("1900-01-01", True), ("1899-12-31", False), (TODAY, True), ("2026-11-05", False), ("1994-08-07", True)]


def a(text, first=None, gender=None, seeking=None, rel=None, looking=None, interests=None, note=""):
    return {"text": text, "firstName": first, "gender": gender, "seeking": seeking, "relationship": rel,
            "lookingFor": looking, "interests": interests, "note": note}


ABOUT = [
    a("I'm Sam, a single guy into hiking and jazz, looking to date women", "Sam", "male", "female", "single", "dating",
      "hiking and jazz", "the placeholder example"),
    a("My name is Aurora. I'm a woman looking for a man, mostly for friendship.", "Aurora", "female", "male", None, "friends"),
    a("call me Zoë! married, here to make friends, I love tea and tarot", "Zoë", None, None, "in_a_relationship", "friends",
      "tea and tarot"),
    a("Just a girl who likes girls", None, "female", "female", None, None, None),
    a("I am bisexual and single, into climbing", None, None, "both", "single", None, "climbing"),
    a("Looking for men or women, I'm Alex", "Alex", None, "both", None, None, None),
    a("Hi! I'm Priya, 29, a woman seeking a man to date", "Priya", "female", "male", None, "dating", None),
    a("dad of two, divorced, love hiking", None, None, None, None, None, "hiking"),
    a("My partner and I are new in town and want to meet friends", None, None, None, "in_a_relationship", "friends", None,
      "'my partner' is a status, not dating intent"),
    a("I'm not single but looking for friends", None, None, None, "in_a_relationship", "friends", None),
    a("I'm open to dating and friends", None, None, None, None, "both", None),
    a("", None, None, None, None, None, None),
    a("I'm a guy into guys", None, "male", "male", None, None, None, "seeking clause removed before own gender"),
    a("my name is jordan, looking for everyone", "Jordan", None, "both", None, None, None, "first letter capitalised"),
    a("i'm jordan", None, None, None, None, None, None, "after I'm only a capitalised word is a name"),
    a("I'm a man looking for a woman", None, "male", "female", None, None, None),
    a("woman, single, enjoy pottery; looking for a relationship", None, "female", None, "single", "dating", "pottery"),
    a("I'm Mary-Jane and I like board games, books", "Mary-Jane", None, None, None, None, "board games",
      "interests stop at a comma"),
    a("My friends say I'm funny", None, None, None, None, None, None, "'my friends' is not looking for friends"),
]

AI_BIRTH = [
    ('{"date":"1994-08-07","time":"16:53","timeUnknown":false,"place":"Providence, RI"}', "1994-08-07", "16:53", False, "Providence, RI"),
    ('```json\n{"date":"1994-08-07","time":null,"timeUnknown":true,"place":null}\n```', "1994-08-07", None, True, None),
    ('Sure! Here it is: {"date": "1985-09-09", "time": "07:05", "timeUnknown": true, "place": "  Austin, TX "} Hope that helps.',
     "1985-09-09", "07:05", False, "Austin, TX"),
    ('{"date":"2030-01-01","time":"24:00","timeUnknown":false,"place":""}', None, None, False, None),
    ('{"date":"1899-12-31","time":"23:60","timeUnknown":"yes","place":null}', None, None, False, None),
    ('{"date":"1994-8-7","time":"4:53","place":"Paris"}', None, None, False, "Paris"),
    ('{"date":"1994-02-29","time":"00:00"}', None, "00:00", False, None),
    ('{"date":5,"time":1653,"timeUnknown":false,"place":["Paris"]}', None, None, False, None),
]
AI_BIRTH_REJECT = [
    "not json", "", '{"date":"1994-08-07","date":"1995-01-01"}', '{"place":1.5}', "[1,2]", '{"date":"1994-08-07"',
]

AI_ABOUT = [
    ('{"firstName":"Sam","gender":"male","seeking":"female","relationship":"single","lookingFor":"dating","interests":"hiking, jazz"}',
     "Sam", "male", "female", "single", "dating", "hiking, jazz"),
    ('{"firstName":"Sam123","gender":"Male","seeking":"women","relationship":"married","lookingFor":"Dating","interests":"x"}',
     None, None, None, None, None, "x"),
    ('{"firstName":"Mary-Jane O\'Neil","gender":null,"seeking":"both","relationship":"in_a_relationship","lookingFor":"both","interests":null}',
     "Mary-Jane O'Neil", None, "both", "in_a_relationship", "both", None),
    ('{"firstName":"  Zoë ","interests":"' + "a" * 201 + '"}', "Zoë", None, None, None, None, None),
    ('{"firstName":"' + "a" * 65 + '","interests":"' + "b" * 200 + '"}', None, None, None, None, None, "b" * 200),
    ('{"firstName":"   ","gender":"other","lookingFor":"friends"}', None, None, None, None, "friends", None),
]
AI_ABOUT_REJECT = ["garbage {", "{}}", '{"firstName":"A","firstName":"B"}']


def main():
    def dump(name, obj):
        with open(os.path.join(ROOT, "testdata", name), "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, indent=1)
            f.write("\n")

    dump("golden_birth_text.json", {
        "description": "BirthTextParser.parse(text, region, today) golden cases (SPEC 5d.5). Hand-specified by "
                       "testdata/tools/make_golden_onboarding.py; Kotlin and Swift must match every field.",
        "today": TODAY, "cases": BIRTH,
        "acceptableDate": [{"date": d, "acceptable": ok} for d, ok in ACCEPTABLE]})
    dump("golden_about_you.json", {
        "description": "AboutYouParser.parse(text) golden cases (SPEC 5d.5). Hand-specified by "
                       "testdata/tools/make_golden_onboarding.py; Kotlin and Swift must match every field.",
        "cases": ABOUT})
    dump("golden_onboarding_ai.json", {
        "description": "OnboardingExtraction: validation of on-device model JSON (SPEC 5d.5). `reject` inputs give "
                       "no result (keep the deterministic chips); otherwise each field is validated on its own.",
        "today": TODAY,
        "birth": [{"raw": r, "date": d, "time": t, "timeUnknown": u, "place": p} for r, d, t, u, p in AI_BIRTH],
        "birthReject": AI_BIRTH_REJECT,
        "aboutYou": [{"raw": r, "firstName": f, "gender": g, "seeking": s, "relationship": rel, "lookingFor": lf,
                      "interests": i} for r, f, g, s, rel, lf, i in AI_ABOUT],
        "aboutYouReject": AI_ABOUT_REJECT})
    print(f"{len(BIRTH)} birth, {len(ABOUT)} about-you, {len(AI_BIRTH) + len(AI_ABOUT)} AI cases")


if __name__ == "__main__":
    main()
