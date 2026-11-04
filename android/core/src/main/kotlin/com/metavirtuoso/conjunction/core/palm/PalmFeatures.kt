package com.metavirtuoso.conjunction.core.palm

import kotlin.math.hypot

/** Palmistry's four hand shapes (palm proportion x finger length). */
enum class HandShape(val displayName: String) {
    /** Square palm, short fingers. */
    EARTH("Earth"),
    /** Square palm, long fingers. */
    AIR("Air"),
    /** Long palm, short fingers. */
    FIRE("Fire"),
    /** Long palm, long fingers. */
    WATER("Water");

    val key: String get() = name.lowercase()
}

enum class PalmLine(val displayName: String) {
    HEART("heart"), HEAD("head"), LIFE("life"), FATE("fate");

    val key: String get() = name.lowercase()
}

enum class Mount(val displayName: String) {
    VENUS("Venus"), JUPITER("Jupiter"), SATURN("Saturn"), APOLLO("Apollo"), MERCURY("Mercury"), LUNA("Luna"), MARS("Mars")
}

enum class Hand { LEFT, RIGHT }

enum class LineLength { ABSENT, SHORT, MEDIUM, LONG }

/**
 * Measurements of one palm line, all dimensionless so they do not depend on photo resolution.
 * [length]: polyline length / palm width (heart, head) or / palm length (life, fate).
 * [curvature]: 0 = straight .. 1 = strongly curved (see [PalmAnalyzer.curvature]).
 * [depth]: 0 = faint .. 1 = deep (local edge contrast reported by the platform detector).
 */
data class LineFeature(
    val present: Boolean,
    val length: Double = 0.0,
    val curvature: Double = 0.0,
    val depth: Double = 0.5,
    val breaks: Int = 0,
    val forked: Boolean = false,
) {
    val lengthClass: LineLength get() = when {
        !present -> LineLength.ABSENT
        length < SHORT_BELOW -> LineLength.SHORT
        length < LONG_FROM -> LineLength.MEDIUM
        else -> LineLength.LONG
    }

    val isCurved: Boolean get() = curvature >= CURVED_FROM

    companion object {
        const val SHORT_BELOW = 0.55
        const val LONG_FROM = 0.80
        const val CURVED_FROM = 0.35
        const val DEEP_FROM = 0.65
        const val FAINT_BELOW = 0.35
        val ABSENT = LineFeature(present = false)
    }
}

data class FingerRatios(
    /** Index finger length / ring finger length (the "2D:4D" ratio, ~0.95-1.0 typical). */
    val indexToRing: Double,
    /** Middle finger length / palm length (~0.8-0.95 typical). */
    val fingerToPalm: Double,
)

/**
 * Typed description of a palm, produced on device from hand landmarks + line detection, consumed by
 * [PalmReadingPrompt] and the fallback reading. Contains no image data.
 */
data class PalmFeatures(
    val hand: Hand,
    val handShape: HandShape,
    /** Palm width (index to little-finger knuckle) / palm length (wrist to middle knuckle). */
    val palmRatio: Double,
    val lines: Map<PalmLine, LineFeature>,
    val fingers: FingerRatios,
    /** Mount prominence 0..1 when the platform could estimate it (shading), else empty. */
    val mounts: Map<Mount, Double> = emptyMap(),
    /** 0..1 confidence of the detection (lighting, focus, coverage). */
    val quality: Double = 1.0,
) {
    fun line(l: PalmLine): LineFeature = lines[l] ?: LineFeature.ABSENT
}

/** 2-D point in image pixels (y down). */
data class Point2(val x: Double, val y: Double)

/**
 * Turns platform detections into [PalmFeatures]. The 21 landmarks use the MediaPipe Hand Landmarker
 * order, which Apple Vision's VNHumanHandPoseObservation joints map onto one-to-one:
 * 0 wrist; 1-4 thumb CMC, MCP, IP, tip; 5-8 index MCP, PIP, DIP, tip; 9-12 middle; 13-16 ring;
 * 17-20 little finger.
 */
object PalmAnalyzer {
    const val SQUARE_PALM_FROM = 0.80
    const val LONG_FINGERS_FROM = 0.88

    fun classifyHandShape(palmRatio: Double, fingerToPalm: Double): HandShape {
        val square = palmRatio >= SQUARE_PALM_FROM
        val longFingers = fingerToPalm >= LONG_FINGERS_FROM
        return when {
            square && !longFingers -> HandShape.EARTH
            square && longFingers -> HandShape.AIR
            !square && !longFingers -> HandShape.FIRE
            else -> HandShape.WATER
        }
    }

    /**
     * @param landmarks 21 points (see class doc).
     * @param lines detected crease polylines per line (image pixels, ordered along the line); a missing
     *   key means not found.
     * @param depth optional 0..1 contrast per line from the edge detector.
     * @param quality overall 0..1 detection confidence.
     */
    fun analyze(
        hand: Hand,
        landmarks: List<Point2>,
        lines: Map<PalmLine, List<Point2>>,
        depth: Map<PalmLine, Double> = emptyMap(),
        breaks: Map<PalmLine, Int> = emptyMap(),
        mounts: Map<Mount, Double> = emptyMap(),
        quality: Double = 1.0,
    ): PalmFeatures {
        require(landmarks.size == 21) { "Expected 21 hand landmarks, got ${landmarks.size}" }
        val palmLength = dist(landmarks[0], landmarks[9])
        val palmWidth = dist(landmarks[5], landmarks[17])
        require(palmLength > 0.0 && palmWidth > 0.0) { "Degenerate hand landmarks" }
        fun finger(mcp: Int) = dist(landmarks[mcp], landmarks[mcp + 1]) + dist(landmarks[mcp + 1], landmarks[mcp + 2]) +
            dist(landmarks[mcp + 2], landmarks[mcp + 3])
        val index = finger(5)
        val middle = finger(9)
        val ring = finger(13)
        val palmRatio = palmWidth / palmLength
        val fingers = FingerRatios(indexToRing = index / ring, fingerToPalm = middle / palmLength)

        val features = PalmLine.entries.associateWith { line ->
            val pts = lines[line]
            if (pts == null || pts.size < 2) LineFeature.ABSENT else {
                val scale = if (line == PalmLine.HEART || line == PalmLine.HEAD) palmWidth else palmLength
                LineFeature(
                    present = true,
                    length = polylineLength(pts) / scale,
                    curvature = curvature(pts),
                    depth = (depth[line] ?: 0.5).coerceIn(0.0, 1.0),
                    breaks = breaks[line] ?: 0,
                )
            }
        }
        return PalmFeatures(
            hand = hand,
            handShape = classifyHandShape(palmRatio, fingers.fingerToPalm),
            palmRatio = palmRatio,
            lines = features,
            fingers = fingers,
            mounts = mounts.mapValues { it.value.coerceIn(0.0, 1.0) },
            quality = quality.coerceIn(0.0, 1.0),
        )
    }

    fun polylineLength(points: List<Point2>): Double = points.zipWithNext { a, b -> dist(a, b) }.sum()

    /** (arc length / chord length - 1) * 5, clamped to 0..1: 0 straight, ~0.5 a gentle arc, 1 a half-circle or more. */
    fun curvature(points: List<Point2>): Double {
        val chord = dist(points.first(), points.last())
        if (chord <= 0.0) return 1.0
        return ((polylineLength(points) / chord - 1.0) * 5.0).coerceIn(0.0, 1.0)
    }

    private fun dist(a: Point2, b: Point2): Double = hypot(a.x - b.x, a.y - b.y)
}
