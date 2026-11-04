package com.metavirtuoso.conjunction.tz

import java.io.DataInputStream
import java.io.InputStream
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Offline latitude/longitude -> IANA time-zone lookup.
 *
 * android.location.Geocoder gives a place's coordinates but not its time zone, and the birth chart
 * needs the zone that was in force *at the birth date* (historical DST). An IANA id such as
 * "America/Indiana/Knox" carries that history inside java.time's tz database, so all we need is
 * the right id for the coordinates, offline.
 *
 * The asset `tz/tzgrid.bin` is a set of quadtrees built from the timezone-boundary-builder
 * polygons (through the Timeshape library; see TimeZoneGridTool in the unit tests). The world is
 * cut into [rootDegrees]-sized root cells; each root is a pre-order quadtree whose leaves hold one
 * zone. Uniform areas stop early; cells that straddle a border split down to [maxDepth]
 * (1 deg / 2^6 = 0.016 deg, about 1.7 km). Accuracy against the source polygons is checked by
 * TimeZoneGridTest.
 *
 * File layout (big-endian, gzip-compressed):
 * ```
 * magic "CTZ1" | u16 version | utf source-description | f64 rootDegrees | u8 maxDepth
 * u16 zoneCount | zoneCount x utf zone id      (index 0 is "" = open ocean / no data)
 * i32 dataLength | data bytes                  (roots in row-major order: lat from -90, lon from -180)
 * ```
 * Node encoding: one unsigned LEB128 varint; 0 = split into 4 children in order SW, SE, NW, NE;
 * n > 0 = leaf with zone index n - 1.
 */
class TimeZoneGrid private constructor(
    val source: String,
    private val rootDegrees: Double,
    val maxDepth: Int,
    private val zones: Array<String>,
    private val data: ByteArray,
    private val rootOffsets: IntArray,
    private val rows: Int,
    private val cols: Int,
) {
    /** Number of distinct zone ids in the grid (including the "no zone" entry). */
    val zoneCount: Int get() = zones.size

    /**
     * The IANA id at [latitude], [longitude], or null for open ocean / outside any polygon.
     * Callers usually want [zoneFor], which also falls back to a nautical zone.
     */
    fun lookup(latitude: Double, longitude: Double): String? {
        require(latitude in -90.0..90.0) { "latitude out of range: $latitude" }
        require(longitude.isFinite()) { "longitude must be finite" }
        val lon = normalizeLongitude(longitude)
        val lat = latitude.coerceAtMost(89.999999)
        val row = floor((lat + 90.0) / rootDegrees).toInt().coerceIn(0, rows - 1)
        val col = floor((lon + 180.0) / rootDegrees).toInt().coerceIn(0, cols - 1)
        var cellLat = -90.0 + row * rootDegrees
        var cellLon = -180.0 + col * rootDegrees
        var size = rootDegrees
        val cursor = Cursor(rootOffsets[row * cols + col])
        while (true) {
            val node = cursor.readVarint()
            if (node != 0) return zones[node - 1].ifEmpty { null }
            size /= 2
            val north = lat >= cellLat + size
            val east = lon >= cellLon + size
            val childIndex = (if (north) 2 else 0) + (if (east) 1 else 0)
            repeat(childIndex) { skipSubtree(cursor) }
            if (north) cellLat += size
            if (east) cellLon += size
        }
    }

    /**
     * A java.time [ZoneId] for the coordinates: the grid's IANA id when the running device's tz
     * database knows it (with a few renamed-zone aliases for older devices), otherwise the
     * nautical zone Etc/GMT±n from the longitude (used for open ocean).
     */
    fun zoneFor(latitude: Double, longitude: Double): ZoneId {
        val id = lookup(latitude, longitude)
        if (id != null) {
            resolveZoneId(id)?.let { return it }
        }
        return nauticalZone(longitude)
    }

    private inner class Cursor(var position: Int) {
        fun readVarint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = data[position++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
    }

    private fun skipSubtree(cursor: Cursor) {
        var pending = 1
        while (pending > 0) {
            pending--
            if (cursor.readVarint() == 0) pending += 4
        }
    }

    companion object {
        const val ASSET_PATH = "tz/tzgrid.bin"
        private const val MAGIC = 0x43545A31 // "CTZ1"
        const val FORMAT_VERSION = 1

        /** Renamed zones: the newer id the grid may contain -> an older id that older devices know. */
        private val ALIASES = mapOf(
            "Europe/Kyiv" to "Europe/Kiev",
            "America/Nuuk" to "America/Godthab",
            "Pacific/Kanton" to "Pacific/Enderbury",
            "Asia/Qostanay" to "Asia/Almaty",
            "America/Ciudad_Juarez" to "America/Ojinaga",
            "Europe/Uzhgorod" to "Europe/Kiev",
            "Europe/Zaporozhye" to "Europe/Kiev",
        )

        /** Reads the grid from an (already opened) gzip stream such as `assets.open(ASSET_PATH)`. */
        fun read(input: InputStream): TimeZoneGrid {
            DataInputStream(GZIPInputStream(input.buffered())).use { stream ->
                require(stream.readInt() == MAGIC) { "not a Conjunction tz grid" }
                val version = stream.readUnsignedShort()
                require(version == FORMAT_VERSION) { "unsupported tz grid version $version" }
                val source = stream.readUTF()
                val rootDegrees = stream.readDouble()
                val maxDepth = stream.readUnsignedByte()
                val zones = Array(stream.readUnsignedShort()) { stream.readUTF() }
                val data = ByteArray(stream.readInt())
                stream.readFully(data)
                val rows = (180.0 / rootDegrees).roundToInt()
                val cols = (360.0 / rootDegrees).roundToInt()
                val offsets = IntArray(rows * cols)
                val grid = TimeZoneGrid(source, rootDegrees, maxDepth, zones, data, offsets, rows, cols)
                // Index the start of every root once (a linear scan of the pre-order stream).
                val cursor = grid.Cursor(0)
                for (i in offsets.indices) {
                    offsets[i] = cursor.position
                    grid.skipSubtree(cursor)
                }
                check(cursor.position == data.size) { "tz grid data has trailing bytes" }
                return grid
            }
        }

        fun resolveZoneId(id: String): ZoneId? =
            runCatching { ZoneId.of(id) }.getOrNull()
                ?: ALIASES[id]?.let { alias -> runCatching { ZoneId.of(alias) }.getOrNull() }

        /** Etc/GMT zones use POSIX signs: Etc/GMT+5 is UTC-5 (15 degrees per hour of longitude). */
        fun nauticalZone(longitude: Double): ZoneId {
            val hours = (normalizeLongitude(longitude) / 15.0).roundToInt().coerceIn(-12, 12)
            return when {
                hours == 0 -> ZoneId.of("Etc/GMT")
                hours > 0 -> ZoneId.of("Etc/GMT-$hours")
                else -> ZoneId.of("Etc/GMT+${-hours}")
            }
        }

        fun normalizeLongitude(longitude: Double): Double {
            var lon = ((longitude + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            if (lon >= 180.0) lon -= 360.0
            return lon
        }
    }
}
