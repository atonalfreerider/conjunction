#!/usr/bin/env python3
"""Generate testdata/golden_charts.json: Swiss Ephemeris reference positions for the shared engines.

Every platform (Kotlin :core, Swift ConjunctionCore) reads the JSON this script writes and checks its
own ephemeris against it (planets <= 0.05 deg, Ascendant/MC <= 0.1 deg). The reference is the Swiss
Ephemeris (pyswisseph) with its own data files (sepl_18.se1 / semo_18.se1, 1800-2400), giving
apparent geocentric positions referred to the true ecliptic and equinox of date - the same quantity
the apps display.

Usage (run from anywhere; keep the venv OUTSIDE the repo):

    python3 -m venv /path/to/venv && /path/to/venv/bin/pip install pyswisseph
    /path/to/venv/bin/python testdata/tools/make_golden.py --ephe /path/to/swisseph/ephe

The ephemeris files are on https://github.com/aloistr/swisseph/tree/master/ephe. Without --ephe,
Swiss Ephemeris falls back to its built-in Moshier ephemeris (still ~1" for planets); the output
records which one was used.

Birth-time conventions (identical to BirthData in the engines):
  * Local civil time in an IANA zone, resolved with the tz rules valid at the birth date.
  * A time inside a DST gap (non-existent) is pushed forward by the gap length (java.time
    ZonedDateTime.of behaviour; Python zoneinfo with fold=0 produces the same instant).
  * An ambiguous time in a DST overlap uses the EARLIER offset (the first occurrence; fold=0).
  * Unknown birth time: positions are computed at 12:00 local civil time and the chart carries no
    Ascendant/MC/houses.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
from zoneinfo import ZoneInfo

import swisseph as swe

BODIES = [
    ("sun", swe.SUN), ("moon", swe.MOON), ("mercury", swe.MERCURY), ("venus", swe.VENUS),
    ("mars", swe.MARS), ("jupiter", swe.JUPITER), ("saturn", swe.SATURN), ("uranus", swe.URANUS),
    ("neptune", swe.NEPTUNE), ("pluto", swe.PLUTO),
]

# id, date, time (None = unknown), latitude, longitude (east +), IANA zone, place, note
CASES = [
    ("greenwich-1900", "1900-01-01", "00:00", 51.4779, -0.0015, "Europe/London", "Greenwich, UK", "start of range, LMT-free GMT"),
    ("tokyo-1900", "1900-01-31", "09:15", 35.6895, 139.6917, "Asia/Tokyo", "Tokyo, Japan", "1900-01-31 day pillar reference"),
    ("paris-1905", "1905-07-14", "18:00", 48.8566, 2.3522, "Europe/Paris", "Paris, France", "pre-1911 Paris Mean Time offset"),
    ("newyork-1912", "1912-04-15", "02:20", 40.7128, -74.0060, "America/New_York", "New York, USA", ""),
    ("beijing-1918", "1918-02-04", "23:30", 39.9042, 116.4074, "Asia/Shanghai", "Beijing, China", "near Lichun"),
    ("london-1918-dst", "1918-03-24", "02:30", 51.5074, -0.1278, "Europe/London", "London, UK", "inside 1918 BST gap -> shifted forward"),
    ("buenosaires-1923", "1923-10-30", "07:45", -34.6037, -58.3816, "America/Argentina/Buenos_Aires", "Buenos Aires, Argentina", "southern hemisphere"),
    # Atlantic/Reykjavik is a link to Africa/Abidjan in tzdata >= 2022 unless built with backzone,
    # so platforms disagree about its pre-1970 offset (UTC-1). Use the fixed zone to stay portable.
    ("reykjavik-1929", "1929-12-21", "12:00", 64.1466, -21.9426, "Etc/GMT+1", "Reykjavik, Iceland", "high latitude winter solstice; fixed UTC-1 zone (see ENGINES.md tz note)"),
    ("helsinki-1931", "1931-06-21", "03:10", 60.1699, 24.9384, "Europe/Helsinki", "Helsinki, Finland", "~60N midsummer"),
    ("sydney-1936", "1936-01-26", "16:40", -33.8688, 151.2093, "Australia/Sydney", "Sydney, Australia", "southern hemisphere"),
    ("london-1941-double-summer", "1941-06-15", "12:00", 51.5074, -0.1278, "Europe/London", "London, UK", "British Double Summer Time UTC+2"),
    ("chicago-1945-war", "1945-08-14", "18:00", 41.8781, -87.6298, "America/Chicago", "Chicago, USA", "US war time"),
    ("delhi-1947", "1947-08-15", "00:00", 28.6139, 77.2090, "Asia/Kolkata", "New Delhi, India", "midnight, half-hour zone"),
    ("capetown-1950", "1950-03-05", None, -33.9249, 18.4241, "Africa/Johannesburg", "Cape Town, South Africa", "unknown birth time"),
    ("anchorage-1955", "1955-11-30", "05:05", 61.2181, -149.9003, "America/Anchorage", "Anchorage, USA", "~61N"),
    ("mexicocity-1960", "1960-05-01", "21:21", 19.4326, -99.1332, "America/Mexico_City", "Mexico City, Mexico", ""),
    ("hongkong-1962", "1962-02-05", "08:00", 22.3193, 114.1694, "Asia/Hong_Kong", "Hong Kong", "Lichun 1962-02-04 vs CNY 1962-02-05"),
    ("london-1969-bst-all-year", "1969-12-25", "10:00", 51.5074, -0.1278, "Europe/London", "London, UK", "British Standard Time 1968-71 (UTC+1 in winter)"),
    ("lima-1971", "1971-09-09", "13:13", -12.0464, -77.0428, "America/Lima", "Lima, Peru", "near equator, southern"),
    ("moscow-1975", "1975-04-12", "06:30", 55.7558, 37.6173, "Europe/Moscow", "Moscow, Russia", ""),
    ("nairobi-1978", "1978-08-08", "15:00", -1.2921, 36.8219, "Africa/Nairobi", "Nairobi, Kenya", "equator"),
    ("losangeles-1980", "1980-10-26", "01:30", 34.0522, -118.2437, "America/Los_Angeles", "Los Angeles, USA", "ambiguous DST overlap -> earlier (PDT)"),
    ("taipei-1984", "1984-02-01", "11:11", 25.0330, 121.5654, "Asia/Taipei", "Taipei, Taiwan", "before CNY (1984-02-02) and before Lichun: Pig year"),
    ("saopaulo-1985", "1985-02-20", "19:00", -23.5505, -46.6333, "America/Sao_Paulo", "Sao Paulo, Brazil", "southern summer DST"),
    ("oslo-1988", "1988-01-15", None, 59.9139, 10.7522, "Europe/Oslo", "Oslo, Norway", "unknown time, ~60N"),
    ("seoul-1988", "1988-09-17", "10:30", 37.5665, 126.9780, "Asia/Seoul", "Seoul, South Korea", "Korean DST 1987-88"),
    ("auckland-1990", "1990-10-07", "02:30", -36.8485, 174.7633, "Pacific/Auckland", "Auckland, New Zealand", "inside NZ DST gap"),
    ("newyork-1993", "1993-03-21", "04:44", 40.7128, -74.0060, "America/New_York", "New York, USA", ""),
    ("tromso-1995", "1995-12-01", "14:00", 69.6492, 18.9553, "Europe/Oslo", "Tromso, Norway", "above polar circle: no Placidus"),
    ("singapore-1999", "1999-12-31", "23:59", 1.3521, 103.8198, "Asia/Singapore", "Singapore", ""),
    ("j2000-greenwich", "2000-01-01", "12:00", 51.4779, -0.0015, "Europe/London", "Greenwich, UK", "J2000.0 (UT)"),
    ("berlin-2004", "2004-02-04", "20:00", 52.5200, 13.4050, "Europe/Berlin", "Berlin, Germany", "Lichun 2004-02-04 ~19:56 Beijing"),
    ("mumbai-2007", "2007-06-30", "05:55", 19.0760, 72.8777, "Asia/Kolkata", "Mumbai, India", ""),
    ("ushuaia-2009", "2009-06-21", "09:00", -54.8019, -68.3030, "America/Argentina/Ushuaia", "Ushuaia, Argentina", "far south"),
    ("perth-2012", "2012-02-29", "18:20", -31.9505, 115.8605, "Australia/Perth", "Perth, Australia", "leap day"),
    ("chicago-2015", "2015-03-08", "02:15", 41.8781, -87.6298, "America/Chicago", "Chicago, USA", "inside US DST gap"),
    ("tehran-2016", "2016-09-21", "16:16", 35.6892, 51.3890, "Asia/Tehran", "Tehran, Iran", "half-hour zone with DST"),
    ("lagos-2019", "2019-05-19", None, 6.5244, 3.3792, "Africa/Lagos", "Lagos, Nigeria", "unknown time"),
    ("london-2020-overlap", "2020-10-25", "01:30", 51.5074, -0.1278, "Europe/London", "London, UK", "ambiguous -> earlier (BST)"),
    ("shanghai-2023", "2023-01-21", "23:45", 31.2304, 121.4737, "Asia/Shanghai", "Shanghai, China", "eve of CNY 2023, late zi hour"),
    ("stpetersburg-2024", "2024-06-20", "23:51", 59.9311, 30.3609, "Europe/Moscow", "Saint Petersburg, Russia", "~60N solstice white night"),
    ("honolulu-2026", "2026-11-03", "08:00", 21.3069, -157.8583, "Pacific/Honolulu", "Honolulu, USA", ""),
    ("kathmandu-2033", "2033-12-22", "06:00", 27.7172, 85.3240, "Asia/Kathmandu", "Kathmandu, Nepal", "UTC+5:45"),
    ("santiago-2041", "2041-04-07", "00:05", -33.4489, -70.6693, "America/Santiago", "Santiago, Chile", ""),
    ("vancouver-2050", "2050-07-04", "12:34", 49.2827, -123.1207, "America/Vancouver", "Vancouver, Canada", ""),
    ("cairo-2063", "2063-01-01", "00:30", 30.0444, 31.2357, "Africa/Cairo", "Cairo, Egypt", ""),
    ("hobart-2077", "2077-09-15", "17:00", -42.8821, 147.3272, "Australia/Hobart", "Hobart, Australia", "~43S"),
    ("anchorage-2088", "2088-02-04", "03:03", 61.2181, -149.9003, "America/Anchorage", "Anchorage, USA", "~61N, near Lichun"),
    ("tokyo-2099", "2099-12-31", "21:00", 35.6895, 139.6917, "Asia/Tokyo", "Tokyo, Japan", "end of range"),
    ("utc-2100", "2100-12-31", "12:00", 0.0, 0.0, "UTC", "Null Island", "range end, equator/prime meridian"),
]

TROPICAL_FLAGS = swe.FLG_SWIEPH | swe.FLG_SPEED


def resolve_utc(date: str, time: str | None, zone: str) -> dt.datetime:
    """Local civil time -> UTC with the conventions documented above (fold=0)."""
    d = dt.date.fromisoformat(date)
    t = dt.time.fromisoformat(time) if time else dt.time(12, 0)
    local = dt.datetime.combine(d, t, tzinfo=ZoneInfo(zone))  # fold=0
    utc = local.astimezone(dt.timezone.utc)
    # Python keeps a non-existent wall time with the pre-transition offset, which is the same
    # instant java.time produces by shifting forward; normalise to be explicit.
    return utc


def write_delta_t(path: str) -> None:
    """Delta T = TT - UT in seconds at 1 January 0h UT of every year 1900..2101 (Swiss Ephemeris).

    The engines interpolate linearly between rows and fall back to the Espenak-Meeus polynomials
    outside the table. Values after the current year are Swiss Ephemeris' own extrapolation.
    """
    lines = ["# Delta T (TT - UT) in seconds at Jan 1 0h UT. GENERATED by testdata/tools/make_golden.py",
             f"# Source: Swiss Ephemeris {swe.version} swe_deltat(). Format: 'year seconds'."]
    for year in range(1900, 2102):
        jd = swe.julday(year, 1, 1, 0.0, swe.GREG_CAL)
        lines.append(f"{year} {swe.deltat(jd) * 86400.0:.3f}")
    with open(path, "w", encoding="ascii") as fh:
        fh.write("\n".join(lines) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ephe", help="directory holding sepl_18.se1 / semo_18.se1")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "golden_charts.json"))
    ap.add_argument("--delta-t-out", help="also write the engines' Delta T table (resource file) here")
    args = ap.parse_args()
    if args.ephe:
        swe.set_ephe_path(args.ephe)
    if args.delta_t_out:
        write_delta_t(args.delta_t_out)

    charts = []
    ephemeris_used = set()
    for cid, date, time, lat, lon, zone, place, note in CASES:
        utc = resolve_utc(date, time, zone)
        hour = utc.hour + utc.minute / 60 + utc.second / 3600
        jd_ut = swe.julday(utc.year, utc.month, utc.day, hour, swe.GREG_CAL)
        bodies = {}
        for name, ipl in BODIES:
            xx, ret = swe.calc_ut(jd_ut, ipl, TROPICAL_FLAGS)
            ephemeris_used.add("SWIEPH" if ret & swe.FLG_SWIEPH else ("MOSEPH" if ret & swe.FLG_MOSEPH else "JPL"))
            bodies[name] = {
                "longitude": round(xx[0], 6),
                "latitude": round(xx[1], 6),
                "speed": round(xx[3], 6),
                "retrograde": xx[3] < 0,
            }
        entry = {
            "id": cid,
            "date": date,
            "time": time,
            "latitude": lat,
            "longitude": lon,
            "zoneId": zone,
            "placeName": place,
            "note": note,
            "utc": utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "jdUt": round(jd_ut, 8),
            "deltaTSeconds": round(swe.deltat(jd_ut) * 86400.0, 3),
            "bodies": bodies,
            "ascendant": None,
            "midheaven": None,
            "placidusCusps": None,
        }
        if time is not None:
            cusps, ascmc = swe.houses_ex(jd_ut, lat, lon, b"W")
            entry["ascendant"] = round(ascmc[0], 6)
            entry["midheaven"] = round(ascmc[1], 6)
            entry["armc"] = round(ascmc[2], 6)
            entry["wholeSignCusps"] = [round(c, 6) for c in cusps[:12]]
            if abs(lat) < 66.0:
                pc, _ = swe.houses_ex(jd_ut, lat, lon, b"P")
                entry["placidusCusps"] = [round(c, 6) for c in pc[:12]]
        charts.append(entry)

    doc = {
        "description": "Swiss Ephemeris reference positions for Conjunction's shared engines. "
                       "Apparent geocentric tropical longitudes, true ecliptic/equinox of date, degrees. "
                       "Tolerances: planets 0.05 deg, ascendant/midheaven 0.1 deg, Placidus cusps 0.1 deg.",
        "generator": "testdata/tools/make_golden.py",
        "swissEphemerisVersion": swe.version,
        "ephemeris": sorted(ephemeris_used),
        "conventions": {
            "timeUnknown": "positions at 12:00 local civil time; ascendant/midheaven/houses null",
            "dstGap": "non-existent local time shifted forward by the gap (java.time / zoneinfo fold=0)",
            "dstOverlap": "ambiguous local time uses the earlier offset (first occurrence)",
            "placidusPolar": "placidusCusps null for |latitude| >= 66 (Placidus undefined; engines fall back to Porphyry)",
        },
        "charts": charts,
    }
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, ensure_ascii=False)
        fh.write("\n")
    print(f"wrote {len(charts)} charts to {os.path.normpath(args.out)} using {sorted(ephemeris_used)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
