package com.metavirtuoso.conjunction.palm

import com.metavirtuoso.conjunction.core.palm.Hand
import com.metavirtuoso.conjunction.core.palm.PalmAnalyzer
import com.metavirtuoso.conjunction.core.palm.PalmLine
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic palms: an upright right hand (fingers up) on a light background with drawn creases. */
class PalmGeometryTest {
    private val w = 600
    private val h = 800

    /** Wrist at the bottom, knuckles at y=300, fingers above. Pinky on the image's left (palm-side right hand). */
    private fun hand(): HandLandmarks {
        val pts = MutableList(21) { Pt(0f, 0f) }
        pts[0] = Pt(300f, 650f)
        // Thumb on the right side.
        pts[1] = Pt(400f, 600f); pts[2] = Pt(450f, 540f); pts[3] = Pt(490f, 480f); pts[4] = Pt(520f, 430f)
        fun finger(mcp: Int, x: Float, len: Float) {
            pts[mcp] = Pt(x, 300f)
            for (k in 1..3) pts[mcp + k] = Pt(x, 300f - len * k / 3f)
        }
        finger(5, 400f, 210f)  // index
        finger(9, 330f, 240f)  // middle
        finger(13, 260f, 220f) // ring
        finger(17, 190f, 170f) // pinky
        return HandLandmarks(pts)
    }

    private fun skin(): GrayImage = GrayImage(w, h, FloatArray(w * h) { i ->
        // Gentle lighting gradient plus deterministic texture noise.
        val x = i % w; val y = i / w
        0.72f + 0.08f * y / h + 0.02f * (((x * 7919 + y * 104729) % 97) / 97f - 0.5f)
    })

    private fun drawLine(img: GrayImage, x0: Float, y0: Float, x1: Float, y1: Float, sag: Float = 0f) {
        val steps = (hypot(x1 - x0, y1 - y0) * 2).toInt()
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t + sag * 4 * t * (1 - t)
            for (dy in -2..2) for (dx in -2..2) {
                val px = (x + dx).toInt(); val py = (y + dy).toInt()
                if (px in 0 until w && py in 0 until h) img[px, py] = img[px, py] * (0.55f + 0.08f * (abs(dx) + abs(dy)))
            }
        }
    }

    @Test
    fun findsHeartAndHeadLinesWhereTheyAreDrawn() {
        val img = skin()
        drawLine(img, 185f, 345f, 370f, 335f, sag = -12f) // heart: just below the knuckles, pinky side
        drawLine(img, 230f, 410f, 400f, 405f)            // head: across the middle
        val detection = PalmGeometry.detect(img, hand())
        assertTrue("heart found: ${detection.lines.keys}", PalmLine.HEART in detection.lines)
        assertTrue("head found: ${detection.lines.keys}", PalmLine.HEAD in detection.lines)
        // The heart polyline stays near y≈340 in the image.
        val heartY = detection.lines.getValue(PalmLine.HEART).polyline.map { it.y }.average()
        assertEquals(340.0, heartY, 25.0)
        val headY = detection.lines.getValue(PalmLine.HEAD).polyline.map { it.y }.average()
        assertEquals(408.0, headY, 25.0)
    }

    /** Like [drawLine] but only slightly darker than the skin: a faint fate line. */
    private fun drawFaintLine(img: GrayImage, x0: Float, y0: Float, x1: Float, y1: Float, darken: Float) {
        val steps = (hypot(x1 - x0, y1 - y0) * 2).toInt()
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val x = x0 + (x1 - x0) * t
            val y = y0 + (y1 - y0) * t
            for (dx in -1..1) {
                val px = (x + dx).toInt(); val py = y.toInt()
                if (px in 0 until w && py in 0 until h) img[px, py] = img[px, py] * darken
            }
        }
    }

    @Test
    fun findsAFaintFateLineAfterContrastEnhancement() {
        val img = skin()
        drawLine(img, 185f, 345f, 370f, 335f, sag = -12f) // strong heart line
        drawLine(img, 230f, 410f, 400f, 405f)            // strong head line
        // Fate line: 3 px wide, only 8 % darker than the skin, from the wrist up the middle.
        drawFaintLine(img, 300f, 630f, 305f, 360f, darken = 0.92f)
        val detection = PalmGeometry.detect(img, hand())
        assertTrue("fate found: ${detection.lines.keys}", PalmLine.FATE in detection.lines)
        val fateX = detection.lines.getValue(PalmLine.FATE).polyline.map { it.x }.average()
        assertEquals(302.0, fateX, 20.0)
        assertTrue("enhanced image returned", detection.enhanced != null)
    }

    @Test
    fun blankPalmHasNoLongLines() {
        val detection = PalmGeometry.detect(skin(), hand())
        val features = PalmAnalyzer.analyze(Hand.RIGHT, hand().toPoint2(), detection.lines.mapValues { it.value.polyline })
        // Texture alone must not produce a long heart line.
        assertFalse(features.line(PalmLine.HEART).present && features.line(PalmLine.HEART).length > 0.8)
    }

    @Test
    fun rectifyMapsKnucklesToTopAndWristToBottom() {
        val quad = PalmGeometry.palmQuad(hand())
        val top = PalmGeometry.toImage(quad, PalmGeometry.W / 2, 0)
        val bottom = PalmGeometry.toImage(quad, PalmGeometry.W / 2, PalmGeometry.H - 1)
        assertTrue(top.y < 320)
        assertTrue(bottom.y > 620)
    }

    @Test
    fun featuresFeedCoreAnalyzer() {
        val img = skin()
        drawLine(img, 185f, 345f, 370f, 335f)
        drawLine(img, 380f, 360f, 360f, 640f, sag = 40f) // life line arcing round the thumb
        val detection = PalmGeometry.detect(img, hand(), handConfidence = 0.9f)
        val features = PalmAnalyzer.analyze(
            hand = Hand.RIGHT,
            landmarks = hand().toPoint2(),
            lines = detection.lines.mapValues { it.value.polyline },
            depth = detection.lines.mapValues { it.value.depth },
            mounts = detection.mounts,
            quality = detection.quality,
        )
        assertTrue(features.quality in 0.0..1.0)
        assertTrue(features.mounts.values.all { it in 0.0..1.0 })
        assertTrue(features.fingers.fingerToPalm > 0.5)
    }
}
