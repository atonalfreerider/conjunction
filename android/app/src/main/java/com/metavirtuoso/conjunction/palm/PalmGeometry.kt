package com.metavirtuoso.conjunction.palm

import com.metavirtuoso.conjunction.core.palm.Mount
import com.metavirtuoso.conjunction.core.palm.PalmLine
import com.metavirtuoso.conjunction.core.palm.Point2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A 2-D point in image pixels. */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)
    fun dist(o: Pt): Float = hypot(x - o.x, y - o.y)
}

/** Grayscale image, row-major, values 0..1 (0 = black). */
class GrayImage(val width: Int, val height: Int, val pixels: FloatArray = FloatArray(width * height)) {
    init { require(pixels.size == width * height) }
    operator fun get(x: Int, y: Int): Float = pixels[y * width + x]
    operator fun set(x: Int, y: Int, v: Float) { pixels[y * width + x] = v }

    /** Bilinear sample; outside the image returns the nearest edge value. */
    fun sample(x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (width - 1).toFloat())
        val cy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt(); val y0 = cy.toInt()
        val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val fx = cx - x0; val fy = cy - y0
        val top = this[x0, y0] * (1 - fx) + this[x1, y0] * fx
        val bottom = this[x0, y1] * (1 - fx) + this[x1, y1] * fx
        return top * (1 - fy) + bottom * fy
    }
}

/**
 * The 21 MediaPipe hand landmarks, in image pixels.
 * Indices follow MediaPipe: 0 wrist; 1-4 thumb (CMC, MCP, IP, tip); 5-8 index; 9-12 middle;
 * 13-16 ring; 17-20 pinky (MCP, PIP, DIP, tip).
 */
class HandLandmarks(val points: List<Pt>) {
    init { require(points.size == 21) { "expected 21 landmarks" } }
    val wrist get() = points[0]
    fun toPoint2(): List<Point2> = points.map { Point2(it.x.toDouble(), it.y.toDouble()) }
}

/** One detected crease: its polyline in image pixels, contrast and number of gaps along it. */
data class DetectedLine(val polyline: List<Point2>, val depth: Double, val breaks: Int)

/**
 * What the image analysis found, ready for core's PalmAnalyzer.analyze (which turns it into the
 * shared PalmFeatures type with hand shape, line lengths/curvature and finger ratios).
 */
data class PalmDetection(
    val lines: Map<PalmLine, DetectedLine>,
    /** Mount prominence 0..1 (0.5 = average brightness). */
    val mounts: Map<Mount, Double>,
    /** 0..1: how much usable crease contrast the photo had and how many lines were found. */
    val quality: Double,
)

/**
 * Pure-Kotlin palm analysis (no Android types, unit-tested on the JVM).
 *
 * 1. **Rectify.** The palm quadrilateral (pinky MCP, index MCP, and the wrist widened to the same
 *    span) is resampled with bilinear interpolation into a [W] x [H] grid: u runs from the pinky
 *    edge (0) to the thumb/index edge (1), v from the knuckle line (0) to the wrist (1). This
 *    removes rotation, scale and perspective tilt, so each line can be looked for in a fixed band.
 * 2. **Flatten lighting.** Subtract a wide Gaussian blur (high-pass) and normalise by the local
 *    standard deviation, so shadows and skin tone don't matter, only local darkness.
 * 3. **Ridge filter.** Creases are thin dark valleys. On a lightly smoothed image the Hessian's
 *    larger eigenvalue is strongly positive across a dark valley; its eigenvector gives the
 *    valley's direction. Ridge strength = max(lambda1, 0).
 * 4. **Per line.** Within the line's band keep pixels whose ridge strength is above the palm's
 *    80th percentile and whose direction matches the line (heart/head ~horizontal, life/fate
 *    ~vertical). Along the main axis, take the longest run of columns (or rows) containing such a
 *    pixel, allowing gaps of up to 3 steps (each gap counts as a "break"); the strongest pixel
 *    per step forms the line's polyline, mapped back to image pixels; the mean strength along the
 *    run relative to the palm's strongest creases is its depth. Length and curvature are then
 *    computed from the polyline by core's PalmAnalyzer, identically on iOS.
 * 5. **Mounts.** Mean brightness of each pad region relative to the palm mean (raised pads catch
 *    more light). This is a rough, documented heuristic, not anatomy.
 *
 * The output is descriptive input for a reading written for entertainment; nothing here claims
 * palmistry has predictive power.
 */
object PalmGeometry {
    const val W = 120
    const val H = 150

    data class Band(val line: PalmLine, val u0: Float, val u1: Float, val v0: Float, val v1: Float, val horizontal: Boolean, val maxAngleDeg: Float)

    /** Search bands on the rectified palm (u: pinky 0 → thumb 1, v: knuckles 0 → wrist 1). */
    val BANDS = listOf(
        Band(PalmLine.HEART, 0.0f, 0.85f, 0.06f, 0.32f, horizontal = true, maxAngleDeg = 35f),
        Band(PalmLine.HEAD, 0.15f, 1.0f, 0.28f, 0.55f, horizontal = true, maxAngleDeg = 35f),
        Band(PalmLine.LIFE, 0.55f, 1.0f, 0.25f, 1.0f, horizontal = false, maxAngleDeg = 60f),
        Band(PalmLine.FATE, 0.32f, 0.68f, 0.35f, 1.0f, horizontal = false, maxAngleDeg = 30f),
    )

    /** Corners of the palm quad in image space: knuckle line (pinky, index) and wrist (pinky side, thumb side). */
    fun palmQuad(hand: HandLandmarks): List<Pt> {
        val p17 = hand.points[17]
        val p5 = hand.points[5]
        val mid = (p5 + p17) * 0.5f
        val w = hand.wrist
        val halfAcross = (p5 - mid) * 0.95f
        // Widen the knuckle edge slightly past the pinky (palm edge) and keep the thumb side at the index MCP.
        val topLeft = p17 + (p17 - p5) * 0.08f
        val topRight = p5
        val bottomLeft = w - halfAcross
        val bottomRight = w + halfAcross
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    fun rectify(image: GrayImage, hand: HandLandmarks): GrayImage {
        val (tl, tr, br, bl) = palmQuad(hand)
        val out = GrayImage(W, H)
        for (j in 0 until H) {
            val v = (j + 0.5f) / H
            val left = tl + (bl - tl) * v
            val right = tr + (br - tr) * v
            for (i in 0 until W) {
                val u = (i + 0.5f) / W
                val p = left + (right - left) * u
                out[i, j] = image.sample(p.x, p.y)
            }
        }
        return out
    }

    fun gaussianBlur(img: GrayImage, sigma: Float): GrayImage {
        val radius = max(1, (sigma * 3).roundToInt())
        val kernel = FloatArray(radius * 2 + 1) { k -> val d = (k - radius).toFloat(); exp(-(d * d) / (2 * sigma * sigma)) }
        val sum = kernel.sum(); for (k in kernel.indices) kernel[k] /= sum
        val tmp = GrayImage(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            var acc = 0f
            for (k in -radius..radius) acc += kernel[k + radius] * img[(x + k).coerceIn(0, img.width - 1), y]
            tmp[x, y] = acc
        }
        val out = GrayImage(img.width, img.height)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            var acc = 0f
            for (k in -radius..radius) acc += kernel[k + radius] * tmp[x, (y + k).coerceIn(0, img.height - 1)]
            out[x, y] = acc
        }
        return out
    }

    /** High-pass and contrast-normalise (step 2). */
    fun flatten(img: GrayImage): GrayImage {
        val low = gaussianBlur(img, 10f)
        val diff = GrayImage(img.width, img.height, FloatArray(img.pixels.size) { img.pixels[it] - low.pixels[it] })
        val variance = gaussianBlur(GrayImage(img.width, img.height, FloatArray(diff.pixels.size) { diff.pixels[it] * diff.pixels[it] }), 10f)
        return GrayImage(img.width, img.height, FloatArray(diff.pixels.size) { diff.pixels[it] / sqrt(variance.pixels[it] + 1e-4f) })
    }

    /** Ridge strength and line direction (degrees from horizontal, -90..90) per pixel (step 3). */
    fun ridges(img: GrayImage, sigma: Float = 1.6f): Pair<FloatArray, FloatArray> {
        val s = gaussianBlur(img, sigma)
        val strength = FloatArray(s.pixels.size)
        val angle = FloatArray(s.pixels.size)
        for (y in 1 until s.height - 1) for (x in 1 until s.width - 1) {
            val ixx = s[x + 1, y] - 2 * s[x, y] + s[x - 1, y]
            val iyy = s[x, y + 1] - 2 * s[x, y] + s[x, y - 1]
            val ixy = (s[x + 1, y + 1] - s[x + 1, y - 1] - s[x - 1, y + 1] + s[x - 1, y - 1]) / 4f
            val tr = ixx + iyy
            val det = ixx * iyy - ixy * ixy
            val disc = sqrt(max(0f, tr * tr / 4 - det))
            val l1 = tr / 2 + disc // larger eigenvalue: curvature across a dark valley
            strength[y * s.width + x] = max(0f, l1)
            // Eigenvector of l1 is across the valley; the line runs perpendicular to it.
            val across = if (abs(ixy) > 1e-6f) atan2(l1 - ixx, ixy) else if (ixx >= iyy) 0f else (Math.PI / 2).toFloat()
            var along = Math.toDegrees(across.toDouble()).toFloat() + 90f
            while (along > 90f) along -= 180f
            while (along < -90f) along += 180f
            angle[y * s.width + x] = along
        }
        return strength to angle
    }

    fun percentile(values: FloatArray, p: Float): Float {
        val sorted = values.copyOf().also { it.sort() }
        return sorted[((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)]
    }

    /** A run found in one band, in rectified grid coordinates (x = column, y = row). */
    data class Run(val points: List<Pair<Int, Int>>, val depth: Double, val breaks: Int)

    /** Step 4 for one band; null when no run of at least a quarter of the band was found. */
    fun measureLine(strength: FloatArray, angle: FloatArray, band: Band, threshold: Float, strongest: Float): Run? {
        val x0 = (band.u0 * W).toInt(); val x1 = (band.u1 * W).toInt().coerceAtMost(W - 1)
        val y0 = (band.v0 * H).toInt(); val y1 = (band.v1 * H).toInt().coerceAtMost(H - 1)
        val mainRange = if (band.horizontal) x0..x1 else y0..y1
        val crossRange = if (band.horizontal) y0..y1 else x0..x1
        // For each step along the main axis: best qualifying pixel (cross position, strength).
        val best = mainRange.map { m ->
            var bestC = -1; var bestS = 0f
            for (c in crossRange) {
                val x = if (band.horizontal) m else c
                val y = if (band.horizontal) c else m
                val i = y * W + x
                val s = strength[i]
                if (s < threshold) continue
                val a = angle[i]
                val dev = if (band.horizontal) abs(a) else 90f - abs(a)
                if (dev > band.maxAngleDeg) continue
                if (s > bestS) { bestS = s; bestC = c }
            }
            bestC to bestS
        }
        // Longest run allowing gaps of up to 3 steps, with continuity in the cross direction.
        var runStart = -1; var bestStart = 0; var bestEnd = -1; var gap = 0; var lastC = -1
        for ((k, pair) in best.withIndex()) {
            val c = pair.first
            if (c >= 0 && (lastC < 0 || abs(c - lastC) <= 6)) {
                if (runStart < 0) runStart = k
                lastC = c; gap = 0
                if (k - runStart > bestEnd - bestStart) { bestStart = runStart; bestEnd = k }
            } else {
                gap++
                if (gap > 3) { runStart = -1; lastC = -1; gap = 0 }
            }
        }
        val runLength = if (bestEnd >= bestStart) bestEnd - bestStart + 1 else 0
        if (runLength < mainRange.count() / 4 || runLength < 4) return null
        val steps = (bestStart..bestEnd)
        val hits = steps.filter { best[it].first >= 0 }
        var breaks = 0
        hits.zipWithNext { a, b -> if (b - a > 1) breaks++ }
        val pts = hits.map { k ->
            val m = mainRange.first + k
            val c = best[k].first
            if (band.horizontal) m to c else c to m
        }
        val depth = (hits.map { best[it].second }.average() / max(strongest, 1e-6f)).coerceIn(0.0, 1.0)
        return Run(pts, depth, breaks)
    }

    /** Maps a rectified grid cell centre back to image pixels (inverse of [rectify]). */
    fun toImage(quad: List<Pt>, gx: Int, gy: Int): Point2 {
        val (tl, tr, br, bl) = quad
        val u = (gx + 0.5f) / W
        val v = (gy + 0.5f) / H
        val left = tl + (bl - tl) * v
        val right = tr + (br - tr) * v
        val p = left + (right - left) * u
        return Point2(p.x.toDouble(), p.y.toDouble())
    }

    private fun regionMean(img: GrayImage, u0: Float, u1: Float, v0: Float, v1: Float): Float {
        var sum = 0f; var n = 0
        for (y in (v0 * H).toInt() until (v1 * H).toInt().coerceAtMost(H)) for (x in (u0 * W).toInt() until (u1 * W).toInt().coerceAtMost(W)) {
            sum += img[x, y]; n++
        }
        return if (n == 0) 0f else sum / n
    }

    /** Step 5: mount prominence 0..1 (0.5 = as bright as the palm on average). */
    fun measureMounts(rectified: GrayImage): Map<Mount, Double> {
        val mean = rectified.pixels.average().toFloat()
        val spread = max(1e-3f, sqrt(rectified.pixels.map { (it - mean) * (it - mean) }.average().toFloat()))
        fun rel(u0: Float, u1: Float, v0: Float, v1: Float): Double =
            (0.5 + (regionMean(rectified, u0, u1, v0, v1) - mean) / (4 * spread)).coerceIn(0.0, 1.0)
        return mapOf(
            Mount.VENUS to rel(0.7f, 1f, 0.55f, 0.95f),
            Mount.LUNA to rel(0f, 0.3f, 0.55f, 0.95f),
            Mount.MARS to rel(0f, 0.25f, 0.3f, 0.55f),
            Mount.JUPITER to rel(0.75f, 1f, 0f, 0.18f),
            Mount.SATURN to rel(0.5f, 0.75f, 0f, 0.18f),
            Mount.APOLLO to rel(0.25f, 0.5f, 0f, 0.18f),
            Mount.MERCURY to rel(0f, 0.25f, 0f, 0.18f),
        )
    }

    /**
     * Runs steps 1-5. [image] is the full photo in grayscale; [hand] its landmarks in pixels;
     * [handConfidence] the landmarker's score.
     */
    fun detect(image: GrayImage, hand: HandLandmarks, handConfidence: Float = 1f): PalmDetection {
        val quad = palmQuad(hand)
        val rectified = rectify(image, hand)
        val flat = flatten(rectified)
        val (strength, angle) = ridges(flat)
        val threshold = max(percentile(strength, 0.80f), 1e-4f)
        val strongest = max(percentile(strength, 0.99f), threshold)
        val lines = BANDS.mapNotNull { band ->
            measureLine(strength, angle, band, threshold, strongest)?.let { run ->
                band.line to DetectedLine(run.points.map { (x, y) -> toImage(quad, x, y) }, run.depth, run.breaks)
            }
        }.toMap()
        // Contrast: a blurry or flat photo has little difference between the 99th and 50th percentile ridge.
        val contrast = ((strongest - percentile(strength, 0.5f)) / max(strongest, 1e-6f)).toDouble().coerceIn(0.0, 1.0)
        val quality = (0.4 * handConfidence + 0.3 * contrast + 0.3 * lines.size / 4.0).coerceIn(0.0, 1.0)
        return PalmDetection(lines, measureMounts(rectified), quality)
    }
}
