package com.metavirtuoso.conjunction.core

import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.chart.BirthData
import com.metavirtuoso.conjunction.core.chart.NatalChart
import com.metavirtuoso.conjunction.core.chinese.ChineseAnimal
import com.metavirtuoso.conjunction.core.chinese.ChineseZodiac
import com.metavirtuoso.conjunction.core.chinese.HeavenlyStem
import com.metavirtuoso.conjunction.core.compat.CompatProfile
import com.metavirtuoso.conjunction.core.compat.CompatResult
import java.time.LocalDate
import java.time.LocalTime

/** Wire format of CompatProfile and CompatResult used by golden_compat.json (see docs/COMPATIBILITY.md). */
object CompatWire {
    fun profileToMap(p: CompatProfile): Map<String, Any?> = linkedMapOf(
        "sun" to p.sun, "moon" to p.moon, "venus" to p.venus, "mars" to p.mars, "ascendant" to p.ascendant,
        "chineseAnimal" to p.chineseAnimal.key, "dayStem" to p.dayStem.name.lowercase(),
        "bloodType" to p.bloodType?.name, "birthDate" to p.birthDate.toString(), "birthHour" to p.birthHour,
    )

    fun profileFromMap(m: Map<String, Any?>): CompatProfile = CompatProfile(
        sun = (m["sun"] as Number).toDouble(),
        moon = (m["moon"] as Number).toDouble(),
        venus = (m["venus"] as Number).toDouble(),
        mars = (m["mars"] as Number).toDouble(),
        ascendant = (m["ascendant"] as Number?)?.toDouble(),
        chineseAnimal = ChineseAnimal.fromKey(m["chineseAnimal"] as String),
        dayStem = HeavenlyStem.valueOf((m["dayStem"] as String).uppercase()),
        bloodType = (m["bloodType"] as String?)?.let(BloodType::valueOf),
        birthDate = LocalDate.parse(m["birthDate"] as String),
        birthHour = (m["birthHour"] as Number?)?.toInt(),
    )

    fun resultToMap(r: CompatResult): Map<String, Any?> = linkedMapOf(
        "total" to r.total, "western" to r.western, "chinese" to r.chinese, "blood" to r.blood,
        "highlights" to r.highlights, "isBirthdayTwin" to r.isBirthdayTwin, "isExactTwin" to r.isExactTwin,
        "sharesBirthday" to r.sharesBirthday, "chineseRelation" to r.chineseRelation.name, "stemRelation" to r.stemRelation.name,
    )
}

/** Real people-like profiles computed from birth data (fixed inputs, so results are reproducible). */
object CompatFixtures {
    data class Person(val id: String, val birth: BirthData, val blood: BloodType?)

    fun person(id: String, date: String, time: String?, lat: Double, lon: Double, zone: String, blood: BloodType?) =
        Person(id, BirthData(LocalDate.parse(date), time?.let(LocalTime::parse), lat, lon, zone), blood)

    val people: List<Person> = listOf(
        person("ava-1990", "1990-08-12", "14:20", 40.71, -74.01, "America/New_York", BloodType.O),
        person("ben-1988", "1988-02-29", "06:05", 51.51, -0.13, "Europe/London", BloodType.A),
        person("chen-1992", "1992-02-04", "23:40", 31.23, 121.47, "Asia/Shanghai", BloodType.AB),
        person("dara-1995", "1995-11-03", null, -33.87, 151.21, "Australia/Sydney", null),
        person("eli-1985", "1985-06-21", "12:00", 34.05, -118.24, "America/Los_Angeles", BloodType.B),
        person("fatima-1999", "1999-12-31", "23:59", 25.20, 55.27, "Asia/Dubai", BloodType.O),
        person("gus-1978", "1978-04-01", "03:15", 59.91, 10.75, "Europe/Oslo", BloodType.A),
        person("hana-2001", "2001-07-07", "07:07", 35.69, 139.69, "Asia/Tokyo", BloodType.A),
        person("ivan-1993", "1993-01-23", "18:30", 55.76, 37.62, "Europe/Moscow", null),
        person("jade-1990", "1990-08-12", "14:50", 37.77, -122.42, "America/Los_Angeles", BloodType.B),
        person("kai-1990", "1990-08-12", "09:10", 19.43, -99.13, "America/Mexico_City", BloodType.O),
        person("lena-1990", "1990-08-12", null, 52.52, 13.40, "Europe/Berlin", BloodType.AB),
        person("milo-1984", "1984-02-03", "09:00", 25.03, 121.57, "Asia/Taipei", BloodType.B),
        person("nia-2000", "2000-02-04", "21:00", -1.29, 36.82, "Africa/Nairobi", BloodType.O),
        person("omar-1975", "1975-08-12", "14:20", 30.04, 31.24, "Africa/Cairo", BloodType.A),
        person("pia-2003", "2003-03-14", "15:09", -23.55, -46.63, "America/Sao_Paulo", null),
        person("quinn-1982", "1982-10-31", "22:22", 53.35, -6.26, "Europe/Dublin", BloodType.AB),
        person("rosa-1996", "1996-05-05", "05:05", 41.39, 2.17, "Europe/Madrid", BloodType.B),
        person("sam-1990", "1990-08-12", "14:05", 40.71, -74.01, "America/New_York", BloodType.A),
        person("tara-1970", "1970-01-01", "00:01", 28.61, 77.21, "Asia/Kolkata", BloodType.O),
    )

    fun profile(p: Person): CompatProfile {
        val chart = NatalChart.compute(p.birth)
        val chinese = ChineseZodiac.compute(p.birth)
        return CompatProfile.from(chart, chinese, p.blood)
    }

    /** Hand-made edge profiles exercising rounding, orb edges and negative truncation. */
    val edgeProfiles: Map<String, CompatProfile> = mapOf(
        "edge-zero" to CompatProfile(0.0, 0.0, 0.0, 0.0, 0.0, ChineseAnimal.RAT, HeavenlyStem.JIA, BloodType.A, LocalDate.of(1990, 1, 1), 0),
        "edge-orb-limit" to CompatProfile(127.0, 7.0, 248.0, 60.0, 90.0, ChineseAnimal.DRAGON, HeavenlyStem.JI, BloodType.O, LocalDate.of(1991, 1, 1), 12),
        "edge-wrap" to CompatProfile(359.995, 29.995, 359.994, 180.0, null, ChineseAnimal.HORSE, HeavenlyStem.GENG, null, LocalDate.of(1992, 6, 6), null),
        "edge-square" to CompatProfile(91.23, 268.77, 181.23, 1.23, 271.23, ChineseAnimal.ROOSTER, HeavenlyStem.YI, BloodType.AB, LocalDate.of(1993, 3, 3), 3),
    )

    /** Pairs for golden_compat.json: (id a, id b, note). */
    val pairs: List<Triple<String, String, String>> = listOf(
        Triple("ava-1990", "jade-1990", "same date, 14:20 vs 14:50: same clock hour -> exact twins"),
        Triple("ava-1990", "sam-1990", "exact twins (same date, same hour 14)"),
        Triple("ava-1990", "kai-1990", "birthday twins, different hours"),
        Triple("ava-1990", "lena-1990", "birthday twins, one unknown time"),
        Triple("ava-1990", "omar-1975", "same birthday (month/day), different year"),
        Triple("ava-1990", "ben-1988", ""),
        Triple("ben-1988", "chen-1992", "leap-day birth"),
        Triple("chen-1992", "milo-1984", "late zi hour vs pre-Lichun birth"),
        Triple("dara-1995", "eli-1985", "unknown time, unknown blood"),
        Triple("eli-1985", "fatima-1999", ""),
        Triple("fatima-1999", "gus-1978", ""),
        Triple("gus-1978", "hana-2001", ""),
        Triple("hana-2001", "ivan-1993", "blood unknown on one side"),
        Triple("ivan-1993", "jade-1990", ""),
        Triple("milo-1984", "nia-2000", ""),
        Triple("nia-2000", "omar-1975", ""),
        Triple("omar-1975", "pia-2003", ""),
        Triple("pia-2003", "quinn-1982", ""),
        Triple("quinn-1982", "rosa-1996", ""),
        Triple("rosa-1996", "sam-1990", ""),
        Triple("sam-1990", "tara-1970", ""),
        Triple("tara-1970", "ava-1990", ""),
        Triple("dara-1995", "lena-1990", "both unknown time"),
        Triple("chen-1992", "hana-2001", ""),
        Triple("eli-1985", "rosa-1996", ""),
        Triple("kai-1990", "jade-1990", "birthday twins"),
        Triple("ben-1988", "ben-1988", "self-pair (identical profiles)"),
        Triple("gus-1978", "quinn-1982", ""),
        Triple("fatima-1999", "nia-2000", ""),
        Triple("ivan-1993", "pia-2003", "both blood unknown"),
        Triple("edge-zero", "edge-orb-limit", "orb exactly at limit -> aspect part 0"),
        Triple("edge-zero", "edge-wrap", "359.995 rounds to 0.00; 29.995 rounds into Taurus"),
        Triple("edge-zero", "edge-square", "negative aspect parts truncate toward zero"),
        Triple("edge-orb-limit", "edge-square", ""),
        Triple("edge-wrap", "edge-square", "blood unknown, asc unknown"),
    )

    fun allProfiles(): Map<String, CompatProfile> = people.associate { it.id to profile(it) } + edgeProfiles
}
