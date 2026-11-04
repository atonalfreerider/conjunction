package com.metavirtuoso.conjunction.nearby

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** Profile-photo sizes for the Bluetooth card (SPEC 5c: max 640 px, JPEG <= 60 KB). */
object PhotoCodec {
    const val MAX_SIDE = 640
    const val MAX_BYTES = 61_440

    /** A photo ready for the card: JPEG bytes and pixel size. */
    class CardPhoto(val jpeg: ByteArray, val width: Int, val height: Int)

    /** Decodes [bytes], sub-sampled so the longest side is near [maxEdge]; null if it is not an image. */
    fun decode(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** The owner's photo file as a card photo: longest side <= 640 and <= 61440 bytes, or null. */
    fun cardPhoto(file: File): CardPhoto? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val decoded = decode(bytes, MAX_SIDE * 2) ?: return null
        var bitmap = scaleToFit(decoded, MAX_SIDE)
        repeat(6) {
            for (quality in intArrayOf(85, 75, 65, 55, 45)) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= MAX_BYTES) return CardPhoto(out.toByteArray(), bitmap.width, bitmap.height)
            }
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * 0.8f).roundToInt().coerceAtLeast(1), (bitmap.height * 0.8f).roundToInt().coerceAtLeast(1), true)
        }
        return null
    }

    private fun scaleToFit(b: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= maxSide) return b
        val f = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).roundToInt().coerceIn(1, maxSide), (b.height * f).roundToInt().coerceIn(1, maxSide), true)
    }
}
