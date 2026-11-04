package com.metavirtuoso.conjunction.ui.components

import android.graphics.Bitmap
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metavirtuoso.conjunction.R
import com.metavirtuoso.conjunction.nearby.PhotoCodec
import com.metavirtuoso.conjunction.nearby.Screening
import com.metavirtuoso.conjunction.ui.theme.Palette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads photos from app storage only: the owner's own photo and photos received from nearby phones
 * over Bluetooth. Nothing is fetched from the network.
 */
object PhotoLoader {
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun loadFile(path: String, maxEdge: Int = 1024): Bitmap? {
        val key = "$path@$maxEdge@${File(path).lastModified()}"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { PhotoCodec.decode(File(path).readBytes(), maxEdge) }.getOrNull()
        }?.also { cache.put(key, it) }
    }
}

/** A local photo with an artwork placeholder while loading or when absent. */
@Composable
fun ProfilePhoto(
    localPath: String?,
    @DrawableRes placeholder: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, localPath) {
        value = localPath?.let { PhotoLoader.loadFile(it) }
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Image(painterResource(placeholder), contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

/**
 * A photo someone sent, after on-device screening (SPEC 5c): shown when it was checked and looks
 * fine; blurred until the person taps "Show photo" when this phone could not check it; replaced by
 * the zodiac art with a warning when it was flagged (still viewable on purpose). [compact] = small
 * avatars: no controls, art instead of anything not shown in full.
 */
@Composable
fun ScreenedPhoto(
    path: String?,
    screening: Screening,
    @DrawableRes placeholder: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var revealed by rememberSaveable(path) { mutableStateOf(false) }
    if (path == null || screening == Screening.SAFE || revealed) {
        ProfilePhoto(path, placeholder, contentDescription, modifier)
        return
    }
    if (compact) {
        Image(painterResource(placeholder), contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = modifier)
        return
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (screening == Screening.FLAGGED) {
            Image(painterResource(placeholder), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            ProfilePhoto(path, placeholder, null, Modifier.fillMaxSize().blur(28.dp))
        }
        Column(
            Modifier.background(Palette.Midnight.copy(alpha = 0.72f)).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(if (screening == Screening.FLAGGED) R.string.photo_flagged else R.string.photo_hidden),
                style = MaterialTheme.typography.bodySmall, color = Palette.Moonstone, textAlign = TextAlign.Center,
            )
            GhostButton(stringResource(R.string.photo_show), onClick = { revealed = true })
        }
    }
}
