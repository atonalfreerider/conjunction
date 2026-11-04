package com.metavirtuoso.conjunction.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.media.FaceDetector
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Face-centred profile photo (SPEC 5d.5 screen 4): find the largest face with the platform's
 * `android.media.FaceDetector` (no new dependency) on an RGB_565 copy, crop 4:5 with the face centre
 * 40% from the top and the face about 35% of the crop's height (no face: centre crop), then encode
 * a 512×640 JPEG of at most 60 KB (quality 85 → 40). Re-encoding a bitmap writes no EXIF.
 */
object ProfilePhotoCrop {
    const val OUT_W = 512
    const val OUT_H = 640
    const val MAX_BYTES = 60 * 1024
    const val FILE_NAME = "profile_photo.jpg"

    /** A detected face: centre and an estimated height (pixels of the source). */
    data class Face(val cx: Float, val cy: Float, val height: Float)

    /** Largest face, or null (no face, or the detector is unavailable on this device). */
    fun detectFace(source: Bitmap): Face? = runCatching {
        // FaceDetector needs RGB_565 and an even width; work on a <= 640 px copy for speed.
        val scale = min(1f, 640f / max(source.width, source.height))
        var w = (source.width * scale).roundToInt().coerceAtLeast(2)
        if (w % 2 == 1) w -= 1
        val h = (source.height * scale).roundToInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(source, w, h, true).copy(Bitmap.Config.RGB_565, false)
        val faces = arrayOfNulls<FaceDetector.Face>(4)
        val found = FaceDetector(w, h, faces.size).findFaces(small, faces)
        val best = faces.take(found).filterNotNull().filter { it.confidence() >= 0.3f }.maxByOrNull { it.eyesDistance() } ?: return@runCatching null
        val mid = PointF().also(best::getMidPoint)
        // Eye distance is about 0.38 of the face height (chin to hairline).
        Face(mid.x / scale, (mid.y / scale) + best.eyesDistance() / scale * 0.25f, best.eyesDistance() / scale / 0.38f)
    }.getOrNull()

    /** The 4:5 crop rectangle in [w]×[h] source pixels for [face] (or the centre crop). */
    fun cropFor(w: Int, h: Int, face: Face?): RectF {
        val maxH = min(h.toFloat(), w * 5f / 4f)
        if (face == null) {
            val cw = maxH * 4f / 5f
            return RectF((w - cw) / 2f, (h - maxH) / 2f, (w + cw) / 2f, (h + maxH) / 2f)
        }
        val ch = (face.height / 0.35f).coerceIn(min(maxH, 64f), maxH)
        val cw = ch * 4f / 5f
        val left = (face.cx - cw / 2f).coerceIn(0f, w - cw)
        val top = (face.cy - ch * 0.40f).coerceIn(0f, h - ch)
        return RectF(left, top, left + cw, top + ch)
    }

    /** Renders [crop] of [source] at 512×640. */
    fun render(source: Bitmap, crop: RectF): Bitmap {
        val out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
        val src = Rect(crop.left.roundToInt(), crop.top.roundToInt(), crop.right.roundToInt(), crop.bottom.roundToInt())
        Canvas(out).drawBitmap(source, src, Rect(0, 0, OUT_W, OUT_H), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** JPEG at the highest quality from 85 down to 40 that fits in [MAX_BYTES]. */
    fun encode(bitmap: Bitmap): ByteArray {
        var last = ByteArray(0)
        for (q in 85 downTo 40 step 5) {
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()
            last = bytes
            if (bytes.size <= MAX_BYTES) return bytes
        }
        // Rare: a very detailed photo; scale down until it fits.
        var b = bitmap
        while (last.size > MAX_BYTES && b.width > 64) {
            b = Bitmap.createScaledBitmap(b, (b.width * 0.85f).roundToInt(), (b.height * 0.85f).roundToInt(), true)
            last = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 40, it) }.toByteArray()
        }
        return last
    }

    /** Saves the cropped photo as the owner's profile photo and returns its path. */
    fun save(context: Context, source: Bitmap, crop: RectF): String {
        val file = File(context.filesDir, FILE_NAME)
        file.writeBytes(encode(render(source, crop)))
        return file.absolutePath
    }
}
