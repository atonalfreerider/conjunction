package com.metavirtuoso.conjunction.tz

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneTableTest {
    private val table = File("src/main/assets/${ZoneTable.ASSET_PATH}").inputStream().use(ZoneTable::read)

    @Test
    fun nearestReferenceCityInCountry() {
        assertEquals("America/New_York", table.zoneFor(41.824, -71.4128, "US").id) // Providence
        assertEquals("America/Chicago", table.zoneFor(41.8781, -87.6298, "US").id)
        assertEquals("America/Phoenix", table.zoneFor(33.4484, -112.074, "US").id)
        assertEquals("America/Los_Angeles", table.zoneFor(37.7749, -122.4194, "US").id)
        assertEquals("Europe/London", table.zoneFor(51.5074, -0.1278, "GB").id)
        assertEquals("Australia/Perth", table.zoneFor(-31.95, 115.86, "AU").id)
        assertEquals("Asia/Shanghai", table.zoneFor(23.13, 113.26, "CN").id) // Guangzhou: one zone for China
    }

    @Test
    fun parsesBothCoordinateForms() {
        val (lat, lon) = ZoneTable.parseIso6709("+404251-0740023")!!
        assertEquals(40.7142, lat, 1e-3)
        assertEquals(-74.0064, lon, 1e-3)
    }
}
