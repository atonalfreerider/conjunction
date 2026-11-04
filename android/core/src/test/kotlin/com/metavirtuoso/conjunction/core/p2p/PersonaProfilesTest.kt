package com.metavirtuoso.conjunction.core.p2p

import com.metavirtuoso.conjunction.core.TestData
import com.metavirtuoso.conjunction.core.blood.BloodType
import com.metavirtuoso.conjunction.core.chart.BirthData
import com.metavirtuoso.conjunction.core.chart.NatalChart
import com.metavirtuoso.conjunction.core.chinese.ChineseZodiac
import com.metavirtuoso.conjunction.core.compat.CompatProfile
import com.metavirtuoso.conjunction.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * The laptop test peer (tools/p2p-sim) cannot run the astronomy engines, so each persona stores
 * its wire `compat` object precomputed. This test recomputes it from the persona's birth data with
 * the real engines and fails (printing the correct value) if they drift.
 */
class PersonaProfilesTest {
    private val dir = File(TestData.dir.parentFile, "tools/p2p-sim/personas")

    @Test
    fun personaCompatMatchesEngines() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("no personas in $dir", files.isNotEmpty())
        val problems = mutableListOf<String>()
        for (f in files) {
            val p = Json.obj(Json.parse(f.readText()))
            val b = Json.obj(p["birth"])
            val birth = BirthData(
                date = LocalDate.parse(b["date"] as String),
                time = (b["time"] as String?)?.let(LocalTime::parse),
                latitude = Json.num(b["latitude"]),
                longitude = Json.num(b["longitude"]),
                zoneId = b["zone"] as String,
                placeName = b["place"] as String,
            )
            val blood = (p["bloodType"] as String?)?.let { BloodType.parse(it) }
            val profile = CompatProfile.from(NatalChart.compute(birth), ChineseZodiac.compute(birth), blood)
            val expected = WireJson.write(WireCompat.from(profile).toWire())
            val stored = p["compat"]?.let { Json.write(it, indent = 0) }
            if (expected != stored) problems += "${f.name}: set \"compat\" to $expected"
        }
        assertEquals(problems.joinToString("\n"), 0, problems.size)
    }
}
