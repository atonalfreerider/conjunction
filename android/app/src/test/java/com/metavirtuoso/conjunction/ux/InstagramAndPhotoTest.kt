package com.metavirtuoso.conjunction.ux

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.metavirtuoso.conjunction.data.Instagram
import com.metavirtuoso.conjunction.data.ProfilePhotoCrop
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class InstagramAndPhotoTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** I-5: the chip opens https://www.instagram.com/<handle>/ with ACTION_VIEW (not instagram://). */
    @Test
    fun chipOpensTheProfileLink() {
        assertTrue(Instagram.open(app, "aurora.stars"))
        val started = Shadows.shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://www.instagram.com/aurora.stars/", started.dataString)
    }

    /** I-6: a shared Instagram profile link offers the handle; other shared text is refused. */
    @Test
    fun shareTargetAcceptsOnlyProfileLinks() {
        fun share(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        assertEquals(Instagram.ShareResult.Handle("aurora.stars"), Instagram.handleFromShare(share("See Aurora's profile https://www.instagram.com/aurora.stars?igsh=xyz")))
        assertEquals(Instagram.ShareResult.NotAProfile, Instagram.handleFromShare(share("https://www.instagram.com/p/abc123/")))
        assertEquals(Instagram.ShareResult.NotAProfile, Instagram.handleFromShare(share("@aurora")))
        assertEquals(Instagram.ShareResult.NotAProfile, Instagram.handleFromShare(share("hello there")))
        assertNull(Instagram.handleFromShare(Intent(Intent.ACTION_VIEW)))
    }

    /** O-5: a face is centred 40% from the top at ~35% of the crop height; no face = centre crop. */
    @Test
    fun cropCentresTheFace() {
        val face = ProfilePhotoCrop.Face(cx = 700f, cy = 500f, height = 280f)
        val crop = ProfilePhotoCrop.cropFor(1600, 1200, face)
        assertEquals(4f / 5f, crop.width() / crop.height(), 0.001f)
        assertEquals(800f, crop.height(), 0.5f)
        assertEquals(700f, crop.centerX(), 0.5f)
        assertEquals(0.40f, (500f - crop.top) / crop.height(), 0.001f)
        val centre = ProfilePhotoCrop.cropFor(1600, 1200, null)
        assertEquals(800f, centre.centerX(), 0.5f)
        assertEquals(1200f, centre.height(), 0.5f)
        // Near an edge the crop is clamped inside the photo.
        val edge = ProfilePhotoCrop.cropFor(1600, 1200, face.copy(cx = 50f, cy = 60f))
        assertTrue(edge.left >= 0f && edge.top >= 0f)
    }

    /** O-5: output is a 512x640 JPEG of at most 60 KB without EXIF. */
    @Test
    fun outputIsSmallJpegWithoutExif() {
        val src = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(src)
        val paint = Paint()
        for (i in 0 until 400) {
            paint.color = Color.rgb((i * 37) % 255, (i * 91) % 255, (i * 53) % 255)
            canvas.drawCircle(((i * 131) % 1600).toFloat(), ((i * 71) % 1200).toFloat(), 40f, paint)
        }
        val bytes = ProfilePhotoCrop.encode(ProfilePhotoCrop.render(src, ProfilePhotoCrop.cropFor(1600, 1200, null)))
        assertTrue("${bytes.size} bytes", bytes.size <= ProfilePhotoCrop.MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(512, bounds.outWidth)
        assertEquals(640, bounds.outHeight)
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
    }
}
