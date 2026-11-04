package com.metavirtuoso.conjunction.palm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.metavirtuoso.conjunction.core.palm.Hand
import com.metavirtuoso.conjunction.core.palm.PalmAnalyzer
import com.metavirtuoso.conjunction.core.palm.PalmFeatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Why a palm photo could not be read. */
class PalmScanException(message: String) : Exception(message)

/** Result of a scan: the features for the reading, plus the landmarks to draw over the photo. */
data class PalmScan(
    val features: PalmFeatures,
    val detection: PalmDetection,
    val landmarks: HandLandmarks,
    val photo: Bitmap,
)

/**
 * On-device palm scan: MediaPipe Tasks Vision HandLandmarker (the official hand_landmarker.task,
 * bundled by :app:downloadHandLandmarker) finds the 21 hand landmarks; [PalmGeometry] measures
 * the creases on the rectified palm; core's [PalmAnalyzer] turns that into [PalmFeatures].
 * The photo stays in memory only and is never uploaded.
 */
class PalmScanner(private val context: Context) {
    @Volatile private var landmarker: HandLandmarker? = null

    private fun landmarker(): HandLandmarker = landmarker ?: synchronized(this) {
        landmarker ?: HandLandmarker.createFromOptions(
            context,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .build(),
        ).also { landmarker = it }
    }

    suspend fun scan(photo: Bitmap, hand: Hand): PalmScan = withContext(Dispatchers.Default) {
        val bitmap = downscale(photo, MAX_EDGE)
        val result = try {
            landmarker().detect(BitmapImageBuilder(bitmap).build())
        } catch (e: RuntimeException) {
            throw PalmScanException("The hand model could not run on this phone (${e.message}).")
        }
        val points = result.landmarks().firstOrNull()
            ?: throw PalmScanException("No hand found. Hold your open palm flat, fingers apart, in good light.")
        val confidence = result.handedness().firstOrNull()?.firstOrNull()?.score() ?: 0.5f
        val landmarks = HandLandmarks(points.map { Pt(it.x() * bitmap.width, it.y() * bitmap.height) })
        val palmPx = landmarks.wrist.dist(landmarks.points[9])
        if (palmPx < MIN_PALM_PX) throw PalmScanException("Your palm is too small in the photo. Move the phone closer.")
        val detection = PalmGeometry.detect(toGray(bitmap), landmarks, confidence)
        val features = PalmAnalyzer.analyze(
            hand = hand,
            landmarks = landmarks.toPoint2(),
            lines = detection.lines.mapValues { it.value.polyline },
            depth = detection.lines.mapValues { it.value.depth },
            breaks = detection.lines.mapValues { it.value.breaks },
            mounts = detection.mounts,
            quality = detection.quality,
        )
        PalmScan(features, detection, landmarks, bitmap)
    }

    fun close() {
        landmarker?.close()
        landmarker = null
    }

    companion object {
        const val MODEL_ASSET = "models/hand_landmarker.task"
        private const val MAX_EDGE = 1280
        private const val MIN_PALM_PX = 120f

        /** Loads a picked photo, applying its EXIF rotation, downscaled for analysis. */
        fun loadUri(context: Context, uri: Uri): Bitmap {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) throw PalmScanException("That file isn't a photo this phone can open.")
            var sample = 1
            while (longest / (sample * 2) >= MAX_EDGE) sample *= 2
            val bitmap = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: throw PalmScanException("That photo could not be opened.")
            val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            return rotate(bitmap, rotation)
        }

        fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
            if (degrees % 360 == 0) return bitmap
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }

        fun downscale(bitmap: Bitmap, maxEdge: Int): Bitmap {
            val longest = max(bitmap.width, bitmap.height)
            val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (longest <= maxEdge) return argb
            val scale = maxEdge.toFloat() / longest
            return Bitmap.createScaledBitmap(argb, (argb.width * scale).roundToInt(), (argb.height * scale).roundToInt(), true)
        }

        /** Luma (Rec. 601) in 0..1. Uses the red channel less, since skin is red and creases show best in green/blue. */
        fun toGray(bitmap: Bitmap): GrayImage {
            val w = bitmap.width
            val h = bitmap.height
            val argb = IntArray(w * h)
            bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            val out = FloatArray(w * h) { i ->
                val c = argb[i]
                (0.15f * Color.red(c) + 0.6f * Color.green(c) + 0.25f * Color.blue(c)) / 255f
            }
            return GrayImage(w, h, out)
        }
    }
}
