package com.metavirtuoso.conjunction.tz

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import net.iakovlev.timeshape.TimeZoneEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test

/**
 * The bundled grid answers from the asset alone (no network, no Timeshape at runtime): known
 * cities, historical offsets, and agreement with the source polygons on random land points.
 */
class TimeZoneGridTest {
    companion object {
        lateinit var grid: TimeZoneGrid

        /**
         * The grid asset is optional (the app falls back to zone1970.tab, see ZoneTableTest). When
         * it has not been generated, these tests are skipped rather than failed.
         */
        @BeforeClass
        @JvmStatic
        fun load() {
            val asset = listOf(File("src/main/assets/tz/tzgrid.bin"), File("app/src/main/assets/tz/tzgrid.bin")).firstOrNull { it.exists() }
            Assume.assumeTrue("tzgrid.bin not generated (optional asset)", asset != null)
            grid = asset!!.inputStream().use(TimeZoneGrid::read)
        }
    }

    private fun zone(lat: Double, lon: Double) = grid.lookup(lat, lon)

    @Test
    fun knownCities() {
        assertEquals("America/New_York", zone(40.7128, -74.0060))
        assertEquals("Europe/London", zone(51.5072, -0.1276))
        assertEquals("America/Indiana/Indianapolis", zone(39.7684, -86.1581))
        assertEquals("America/Phoenix", zone(33.4484, -112.0740))
        assertEquals("Asia/Kolkata", zone(22.5726, 88.3639))
        assertEquals("Asia/Kathmandu", zone(27.7172, 85.3240))
        assertEquals("Asia/Tokyo", zone(35.6762, 139.6503))
        assertEquals("Australia/Sydney", zone(-33.8688, 151.2093))
        assertEquals("Australia/Adelaide", zone(-34.9285, 138.6007))
        assertEquals("Pacific/Honolulu", zone(21.3069, -157.8583))
        assertEquals("America/Sao_Paulo", zone(-23.5558, -46.6396))
        assertEquals("Africa/Lagos", zone(6.5244, 3.3792))
        assertEquals("America/Chicago", zone(41.8781, -87.6298))
        assertEquals("America/Denver", zone(39.7392, -104.9903))
        assertEquals("America/Los_Angeles", zone(34.0522, -118.2437))
        assertEquals("Europe/Paris", zone(48.8566, 2.3522))
    }

    @Test
    fun historicalRulesComeFromTheIanaId() {
        // Indianapolis did not observe DST in 1990: UTC-5 in July. In 2010 it did: UTC-4.
        val id = ZoneId.of(zone(39.7684, -86.1581)!!)
        assertEquals(ZoneOffset.ofHours(-5), id.rules.getOffset(LocalDateTime.of(1990, 7, 1, 12, 0)))
        assertEquals(ZoneOffset.ofHours(-4), id.rules.getOffset(LocalDateTime.of(2010, 7, 1, 12, 0)))
        // London during British Summer Time 1968-71 (UTC+1 all year).
        val london = ZoneId.of(zone(51.5072, -0.1276)!!)
        assertEquals(ZoneOffset.ofHours(1), london.rules.getOffset(LocalDateTime.of(1969, 1, 15, 12, 0)))
    }

    @Test
    fun openOceanFallsBackToNauticalZone() {
        val id = grid.lookup(-40.0, -120.0)
        if (id == null || id.startsWith("Etc/")) {
            assertEquals("Etc/GMT+8", grid.zoneFor(-40.0, -120.0).id)
        }
        assertEquals("Etc/GMT-9", TimeZoneGrid.nauticalZone(135.0).id)
        assertEquals("Etc/GMT", TimeZoneGrid.nauticalZone(3.0).id)
    }

    @Test
    fun longitudeWrapsAndPolesWork() {
        assertEquals(zone(35.6762, 139.6503), zone(35.6762, 139.6503 - 360.0))
        grid.lookup(90.0, 0.0)
        grid.lookup(-90.0, 179.999)
        assertEquals(-180.0, TimeZoneGrid.normalizeLongitude(180.0), 1e-9)
    }

    @Test
    fun everyZoneIdIsKnownToJavaTime() {
        // Ids unknown to this JVM's tz database must still resolve through the alias table.
        val unknown = (0 until 3600).mapNotNull { i ->
            val lat = -60.0 + (i / 60) * 2.3
            val lon = -179.0 + (i % 60) * 6.0
            grid.lookup(lat, lon)?.takeIf { TimeZoneGrid.resolveZoneId(it) == null }
        }.distinct()
        assertTrue("unresolvable ids: $unknown", unknown.isEmpty())
    }

    @Test
    fun agreesWithSourcePolygonsOnRandomPoints() {
        val engine = TimeZoneEngine.initialize()
        val random = Random(2026)
        var compared = 0
        var agreed = 0
        while (compared < 3000) {
            val lat = random.nextDouble(-56.0, 72.0)
            val lon = random.nextDouble(-180.0, 180.0)
            val expected = engine.query(lat, lon).map { it.id }.orElse(null) ?: continue
            if (expected.startsWith("Etc/")) continue // open ocean: not a birth place
            compared++
            if (grid.lookup(lat, lon) == expected) agreed++
        }
        val rate = agreed.toDouble() / compared
        assertTrue("agreement $rate", rate >= 0.99)
    }
}
