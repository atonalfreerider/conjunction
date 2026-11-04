#!/usr/bin/env python3
"""Generate testdata/golden_chinese.json: Chinese zodiac / Four Pillars reference cases.

The expected values are produced INDEPENDENTLY of the app engines:
  * solar terms (Lichun = Sun at 315 deg, month terms every 30 deg) from Swiss Ephemeris;
  * day pillars from the arithmetic "mental calculation" algorithm published in the Wikipedia
    article "Sexagenary cycle" (SB = (y + c + m + d) mod 60), NOT from a Julian-day count;
    anchors checked: 1949-10-01 = 甲子 and 1912-02-18 = 甲子 (both worked examples in that
    article);
  * lunar-new-year animals from testdata/chinese_calendar.json (Hong Kong Observatory).
The rules (five tigers / five rats, Zi hour starting the day at 23:00, civil clock time) are the
documented engine defaults in docs/ENGINES.md.

Usage: /path/to/venv/bin/python make_golden_chinese.py --ephe /path/to/swisseph/ephe
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
from zoneinfo import ZoneInfo

import swisseph as swe

STEMS = "甲乙丙丁戊己庚辛壬癸"
BRANCHES = "子丑寅卯辰巳午未申酉戌亥"
ANIMALS = ["rat", "ox", "tiger", "rabbit", "dragon", "snake", "horse", "goat", "monkey", "rooster", "dog", "pig"]
HERE = os.path.dirname(os.path.abspath(__file__))


def pillar(stem: int, branch: int) -> str:
    return STEMS[stem % 10] + BRANCHES[branch % 12]


def sun_lon(jd_ut: float) -> float:
    return swe.calc_ut(jd_ut, swe.SUN, swe.FLG_SWIEPH)[0][0]


def sun_reaches(lon: float, jd_guess: float) -> float:
    lo, hi = jd_guess - 20, jd_guess + 20
    for _ in range(80):
        mid = (lo + hi) / 2
        if ((sun_lon(mid) - lon + 180) % 360 - 180) < 0:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def jd_of(utc: dt.datetime) -> float:
    return swe.julday(utc.year, utc.month, utc.day, utc.hour + utc.minute / 60 + utc.second / 3600)


def wiki_day_index(d: dt.date) -> int:
    """Wikipedia 'Sexagenary cycle' mental-calculation algorithm. Returns 0-based index (0 = 甲子)."""
    year, month, day = d.year, d.month, d.day
    r = (year % 400) % 80
    y = (r * 5 + r // 4) % 60
    c = year // 400 - year // 100 + 10
    leap = (year % 4 == 0 and year % 100 != 0) or year % 400 == 0
    m_common = {1: 0, 2: 31, 3: -1, 4: 30, 5: 0, 6: 31, 7: 1, 8: 32, 9: 3, 10: 33, 11: 4, 12: 34}
    m = m_common[month]
    if leap and month in (1, 2):
        m -= 1
    sb = (y + c + m + day) % 60  # 1-based, 0 means 60
    return (sb - 1) % 60


def expected(date: str, time: str | None, zone: str, cny: dict) -> dict:
    d = dt.date.fromisoformat(date)
    t = dt.time.fromisoformat(time) if time else dt.time(12, 0)
    local = dt.datetime.combine(d, t, tzinfo=ZoneInfo(zone))
    utc = local.astimezone(dt.timezone.utc)
    jd = jd_of(utc)

    lichun = sun_reaches(315.0, swe.julday(d.year, 2, 4, 0))
    solar_year = d.year if jd >= lichun else d.year - 1
    ys, yb = (solar_year - 4) % 10, (solar_year - 4) % 12

    lon = sun_lon(jd)
    k = int(((lon - 315.0) % 360.0) // 30.0)
    ms = ((ys % 5) * 2 + 2 + k) % 10
    mb = (2 + k) % 12

    if time is None:
        day_date = d
        hour = None
    else:
        day_date = d + dt.timedelta(days=1) if t.hour == 23 else d  # day starts at 23:00
        di = wiki_day_index(day_date)
        hb = ((t.hour + 1) // 2) % 12
        hs = ((di % 10 % 5) * 2 + hb) % 10
        hour = pillar(hs, hb)
    di = wiki_day_index(day_date)

    ny = dt.date.fromisoformat(cny[str(d.year)])
    lunar_year = d.year if d >= ny else d.year - 1
    return {
        "yearPillar": pillar(ys, yb),
        "monthPillar": pillar(ms, mb),
        "dayPillar": pillar(di % 10, di % 12),
        "hourPillar": hour,
        "yearAnimal": ANIMALS[yb],
        "lunarYearAnimal": ANIMALS[(lunar_year - 4) % 12],
        "solarYear": solar_year,
        "lichunUtc": swe_to_iso(lichun),
    }


def swe_to_iso(jd: float) -> str:
    y, m, d, h = swe.revjul(jd)
    base = dt.datetime(y, m, d, tzinfo=dt.timezone.utc) + dt.timedelta(hours=h)
    return base.strftime("%Y-%m-%dT%H:%M:%SZ")


def lichun_local(year: int, zone: str, minutes: int) -> tuple[str, str]:
    """Local date/time 'minutes' after (or before, if negative) Lichun of year, rounded to the minute."""
    jd = sun_reaches(315.0, swe.julday(year, 2, 4, 0)) + minutes / 1440.0
    y, m, d, h = swe.revjul(jd)
    utc = dt.datetime(y, m, d, tzinfo=dt.timezone.utc) + dt.timedelta(hours=h)
    local = utc.astimezone(ZoneInfo(zone)).replace(second=0, microsecond=0)
    return local.date().isoformat(), local.strftime("%H:%M")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ephe")
    ap.add_argument("--out", default=os.path.join(HERE, "..", "golden_chinese.json"))
    args = ap.parse_args()
    if args.ephe:
        swe.set_ephe_path(args.ephe)
    cny = json.load(open(os.path.join(HERE, "..", "chinese_calendar.json"), encoding="utf-8"))["newYear"]

    # Anchor checks of the independent day algorithm (Wikipedia worked examples).
    assert wiki_day_index(dt.date(1949, 10, 1)) == 0, "1949-10-01 must be 甲子"
    assert wiki_day_index(dt.date(1912, 2, 18)) == 0, "1912-02-18 must be 甲子"

    cases = []

    def add(cid, date, time, zone, lon, note):
        cases.append({"id": cid, "date": date, "time": time, "zoneId": zone, "longitude": lon,
                      "note": note, "expected": expected(date, time, zone, cny)})

    add("anchor-1949-10-01", "1949-10-01", "15:00", "Asia/Shanghai", 116.4, "Wikipedia anchor: 甲子 day")
    add("anchor-1912-02-18", "1912-02-18", "10:00", "Asia/Shanghai", 116.4, "Wikipedia anchor: 甲子 day")
    add("day-1900-01-31", "1900-01-31", "12:00", "Asia/Shanghai", 116.4, "1900-01-31 (CNY 1900) is 甲辰 day")
    add("day-2000-01-01", "2000-01-01", "12:00", "UTC", 0.0, "2000-01-01 is 戊午 day")
    # Between Lichun and lunar new year (solar and lunar animals differ).
    add("1962-feb-04-after-lichun", "1962-02-04", "18:00", "Asia/Hong_Kong", 114.17, "after Lichun, before CNY 1962-02-05: solar Tiger, lunar Ox")
    add("1984-feb-01", "1984-02-01", "11:11", "Asia/Taipei", 121.56, "before CNY (02-02) and Lichun (02-04/05): Pig both")
    add("1984-feb-03", "1984-02-03", "09:00", "Asia/Taipei", 121.56, "after CNY, before Lichun: lunar Rat, solar Pig")
    add("1985-feb-19", "1985-02-19", "12:00", "Asia/Shanghai", 121.47, "after Lichun 02-04, before CNY 02-20: solar Ox, lunar Rat")
    add("1985-feb-20", "1985-02-20", "12:00", "Asia/Shanghai", 121.47, "CNY 1985 (Yushui-rule exception year)")
    add("2033-jan-31", "2033-01-31", "08:00", "Asia/Shanghai", 121.47, "CNY 2033 is 01-31 before Lichun: lunar Ox, solar Rat")
    add("2034-feb-18", "2034-02-18", "20:00", "Asia/Shanghai", 121.47, "after Lichun, day before CNY 2034-02-19")
    add("2053-feb-19", "2053-02-19", "07:00", "Asia/Shanghai", 121.47, "CNY 2053 (Yushui-rule exception year)")
    add("1990-jan-26", "1990-01-26", "14:00", "America/New_York", -74.0, "lunar new year day 1990-01-27 tomorrow: still Snake")
    add("1990-jan-27", "1990-01-27", "14:00", "America/New_York", -74.0, "CNY 1990: Horse lunar, still Snake solar")
    add("2023-jan-21-late-zi", "2023-01-21", "23:45", "Asia/Shanghai", 121.47, "late Zi hour: day pillar of 01-22")
    add("2023-jan-22-early-zi", "2023-01-22", "00:30", "Asia/Shanghai", 121.47, "early Zi hour, CNY 2023")
    add("2008-feb-06", "2008-02-06", "21:00", "Europe/London", -0.13, "CNY eve 2008 (CNY 02-07), after Lichun")
    add("1996-feb-19", "1996-02-19", "03:00", "Australia/Sydney", 151.2, "CNY 1996-02-19, southern hemisphere")
    add("1971-jan-26", "1971-01-26", "06:30", "America/Los_Angeles", -118.24, "before CNY 1971-01-27")
    add("2061-jan-21", "2061-01-21", "12:00", "Asia/Tokyo", 139.69, "CNY 2061-01-21")
    add("2100-feb-09", "2100-02-09", "12:00", "Asia/Shanghai", 121.47, "CNY 2100")
    add("unknown-time-1950-02-04", "1950-02-04", None, "Asia/Shanghai", 121.47, "unknown time on Lichun date: noon used, hour null")
    add("unknown-time-1975-07-07", "1975-07-07", None, "Europe/Paris", 2.35, "unknown time: hour pillar null")
    # Minutes either side of the exact Lichun instant, several zones and years.
    for year, zone, lon in [(1920, "Asia/Shanghai", 121.47), (1955, "America/Chicago", -87.63),
                            (1989, "Europe/Berlin", 13.4), (2004, "Asia/Shanghai", 121.47),
                            (2017, "Pacific/Auckland", 174.76), (2044, "America/Sao_Paulo", -46.63),
                            (2088, "America/Anchorage", -149.9)]:
        for minutes in (-3, 3):
            date, time = lichun_local(year, zone, minutes)
            add(f"lichun-{year}-{'before' if minutes < 0 else 'after'}", date, time, zone, lon,
                f"{abs(minutes)} min {'before' if minutes < 0 else 'after'} Lichun {year}")
    # Month-term boundaries (Jingzhe 345, Qingming 15, Xiaohan 285).
    for year, term, zone in [(1977, 345.0, "Asia/Shanghai"), (2012, 15.0, "Asia/Seoul"), (1999, 285.0, "Asia/Singapore")]:
        guess_month = {345.0: 3, 15.0: 4, 285.0: 1}[term]
        jd = sun_reaches(term, swe.julday(year, guess_month, 6, 0))
        for minutes in (-5, 5):
            y, m, d, h = swe.revjul(jd + minutes / 1440.0)
            utc = dt.datetime(y, m, d, tzinfo=dt.timezone.utc) + dt.timedelta(hours=h)
            local = utc.astimezone(ZoneInfo(zone))
            add(f"term{int(term)}-{year}-{'before' if minutes < 0 else 'after'}", local.date().isoformat(),
                local.strftime("%H:%M"), zone, 120.0, f"{abs(minutes)} min from Sun at {term} deg")
    # Every hour branch on one day (five-rats rule) and a 23:xx case.
    for hour in range(0, 24, 2):
        add(f"hours-2010-06-15-{hour:02d}", "2010-06-15", f"{hour:02d}:30", "Asia/Shanghai", 121.47, "hour branch sweep")
    add("hours-2010-06-15-23", "2010-06-15", "23:10", "Asia/Shanghai", 121.47, "23:10 -> next day's 甲/己 Zi")

    doc = {
        "description": "Chinese zodiac / BaZi reference cases. Defaults: civil clock time, day starts at 23:00 (Zi hour), "
                       "year boundary at the Lichun instant (Sun 315 deg), lunar animal from HKO new-year table.",
        "generator": "testdata/tools/make_golden_chinese.py",
        "independentSources": ["Swiss Ephemeris " + swe.version + " (solar terms)",
                               "Wikipedia 'Sexagenary cycle' day algorithm (anchors 1949-10-01, 1912-02-18 = 甲子)",
                               "Hong Kong Observatory new-year dates (chinese_calendar.json)"],
        "cases": cases,
    }
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    print(f"wrote {len(cases)} cases")
    return 0


if __name__ == "__main__":
    sys.exit(main())
