package com.metavirtuoso.conjunction.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads profile photos: the backend's 15-minute signed HTTPS URLs (cached in memory for the
 * session only — never written to disk) and the owner's local photo file.
 */
object PhotoLoader {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Memory cache key: the URL without its signature query, so a refreshed URL hits the cache. */
    private fun key(url: String) = url.substringBefore('?')

    suspend fun load(url: String, maxEdge: Int = 1024): Bitmap? {
        cache.get(key(url))?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                require(url.startsWith("https://") || url.startsWith("http://10.0.2.2") || url.startsWith("http://localhost")) { "insecure URL" }
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    val bytes = connection.inputStream.use { it.readBytes() }
                    decode(bytes, maxEdge)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()?.also { cache.put(key(url), it) }
        }
    }

    suspend fun loadFile(path: String, maxEdge: Int = 1024): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { decode(File(path).readBytes(), maxEdge) }.getOrNull()
    }

    fun decode(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** A remote (or local file) photo with an artwork placeholder while loading or when absent. */
@Composable
fun ProfilePhoto(
    url: String?,
    @DrawableRes placeholder: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    localPath: String? = null,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, url, localPath) {
        value = when {
            localPath != null -> PhotoLoader.loadFile(localPath)
            url != null -> PhotoLoader.load(url)
            else -> null
        }
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Image(painterResource(placeholder), contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = modifier)
    }
}
