package com.musico.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musico.core.ArtworkCache
import com.musico.core.CloudThumbnailCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalThumbnailCache = staticCompositionLocalOf<CloudThumbnailCache?> { null }

@Composable
fun rememberArtworkBitmap(
    artworkKey: String?,
    artworkCache: ArtworkCache,
    remoteArtworkUrl: String? = null,
    thumbnailCache: CloudThumbnailCache? = LocalThumbnailCache.current,
): Bitmap? {
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = artworkKey,
        key2 = remoteArtworkUrl,
        key3 = thumbnailCache,
    ) {
        value = when {
            artworkKey != null -> withContext(Dispatchers.IO) {
                artworkCache.bitmap(artworkKey)
            }
            !remoteArtworkUrl.isNullOrBlank() && thumbnailCache != null -> {
                thumbnailCache.load(remoteArtworkUrl)
            }
            else -> null
        }
    }
    return bitmap
}

@Composable
fun ArtworkImage(
    artworkKey: String?,
    artworkCache: ArtworkCache,
    modifier: Modifier = Modifier,
    size: Dp? = null,
    cornerRadius: Dp = 8.dp,
    remoteArtworkUrl: String? = null,
    thumbnailCache: CloudThumbnailCache? = LocalThumbnailCache.current,
    fallbackIcon: ImageVector = Icons.Rounded.MusicNote,
) {
    val bitmap = rememberArtworkBitmap(
        artworkKey = artworkKey,
        artworkCache = artworkCache,
        remoteArtworkUrl = remoteArtworkUrl,
        thumbnailCache = thumbnailCache,
    )

    val sizedModifier = if (size != null) {
        modifier.size(size)
    } else {
        modifier.aspectRatio(1f)
    }

    Box(
        modifier = sizedModifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(if (size != null) size * 0.45f else 48.dp)
            )
        }
    }
}
