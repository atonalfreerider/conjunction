package com.metavirtuoso.conjunction.tz

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import net.iakovlev.timeshape.TimeZoneEngine
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Generator for src/main/assets/tz/tzgrid.bin (format documented on [TimeZoneGrid]).
 *
 * Runs only through `./gradlew :app:writeTimeZoneGrid` (which sets conjunction.writeTzGrid);
 * in a normal test run it is skipped. Source polygons: Timeshape, which packages
 * timezone-boundary-builder (the "with oceans" release, so territorial and open-ocean zones are
 * included and coastlines are not treated as borders).
 *
 * Root cells are 1 deg x 1 deg, sampled on an 8 x 8 grid (about 14 km apart, finer than the
 * narrowest county-sized zones such as the America/Indiana zones). A cell whose samples disagree splits
 * into four, each re-sampled on 4 x 4, down to depth 6 (1/64 deg, about 1.7 km). Leaves at the deepest level
 * take the majority zone of their samples.
 */
class TimeZoneGridTool {
    @Test
    fun writeGrid() {
        val target = System.getProperty("conjunction.writeTzGrid").orEmpty()
        assumeTrue("set by :app:writeTimeZoneGrid", target.isNotEmpty())

        val started = System.currentTimeMillis()
        val engine = TimeZoneEngine.initialize()
        println("Timeshape loaded in ${(System.currentTimeMillis() - started) / 1000}s")

        val zones = java.util.concurrent.ConcurrentHashMap<String, Int>().apply { put("", 0) }
        val nextIndex = java.util.concurrent.atomic.AtomicInteger(1)
        fun zoneIndex(lat: Double, lon: Double): Int {
            val id = engine.query(lat, lon).map { it.id }.orElse("")
            return zones[id] ?: zones.computeIfAbsent(id) { nextIndex.getAndIncrement() }
        }
        val progressFile = File("build/tzgrid.progress")
        val done = java.util.concurrent.atomic.AtomicInteger()

        val rows = 180 / ROOT_DEGREES
        val cols = 360 / ROOT_DEGREES
        val roots = (0 until rows * cols).toList().parallelStream().map { index ->
            val row = index / cols
            val col = index % cols
            val out = ByteArrayOutputStream()
            encode(out, -90.0 + row * ROOT_DEGREES, -180.0 + col * ROOT_DEGREES, ROOT_DEGREES.toDouble(), 0, ::zoneIndex)
            val n = done.incrementAndGet()
            if (n % 500 == 0) progressFile.writeText("$n / ${rows * cols} roots, ${(System.currentTimeMillis() - started) / 1000}s\n")
            out.toByteArray()
        }.toList()

        val data = ByteArrayOutputStream()
        roots.forEach { data.write(it) }
        val zoneList = zones.entries.sortedBy { it.value }.map { it.key }
        check(zoneList.size == nextIndex.get()) { "zone indices must be dense" }
        progressFile.delete()

        val file = File(target)
        file.parentFile.mkdirs()
        DataOutputStream(GZIPOutputStream(file.outputStream().buffered())).use { out ->
            out.writeInt(0x43545A31)
            out.writeShort(TimeZoneGrid.FORMAT_VERSION)
            out.writeUTF("timezone-boundary-builder via Timeshape ${TimeZoneEngine::class.java.`package`?.implementationVersion ?: "2026b.29"}")
            out.writeDouble(ROOT_DEGREES.toDouble())
            out.writeByte(MAX_DEPTH)
            out.writeShort(zoneList.size)
            zoneList.forEach { out.writeUTF(it) }
            out.writeInt(data.size())
            data.writeTo(out)
        }
        println("wrote ${file.length()} bytes, ${zoneList.size} zones, ${data.size()} node bytes in ${(System.currentTimeMillis() - started) / 1000}s")
    }

    private fun encode(
        out: ByteArrayOutputStream,
        lat0: Double,
        lon0: Double,
        size: Double,
        depth: Int,
        zoneAt: (Double, Double) -> Int,
    ) {
        val n = if (depth == 0) ROOT_SAMPLES else CHILD_SAMPLES
        val counts = HashMap<Int, Int>()
        for (i in 0 until n) for (j in 0 until n) {
            val lat = lat0 + (i + 0.5) / n * size
            val lon = lon0 + (j + 0.5) / n * size
            val z = zoneAt(lat.coerceIn(-89.9999, 89.9999), lon)
            counts[z] = (counts[z] ?: 0) + 1
        }
        if (counts.size == 1 || depth == MAX_DEPTH) {
            val zone = counts.maxBy { it.value }.key
            writeVarint(out, zone + 1)
            return
        }
        writeVarint(out, 0)
        val half = size / 2
        encode(out, lat0, lon0, half, depth + 1, zoneAt) // SW
        encode(out, lat0, lon0 + half, half, depth + 1, zoneAt) // SE
        encode(out, lat0 + half, lon0, half, depth + 1, zoneAt) // NW
        encode(out, lat0 + half, lon0 + half, half, depth + 1, zoneAt) // NE
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                out.write(v)
                return
            }
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    companion object {
        const val ROOT_DEGREES = 1
        const val ROOT_SAMPLES = 8
        const val CHILD_SAMPLES = 4
        const val MAX_DEPTH = 6
    }
}
