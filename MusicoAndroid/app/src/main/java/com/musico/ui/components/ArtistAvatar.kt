package com.musico.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musico.core.ArtworkCache
import com.musico.core.CloudThumbnailCache

internal fun artistMonogramInitial(artistName: String): String {
    val trimmed = artistName.trim()
    if (trimmed.isEmpty()) return "?"
    val first = trimmed.firstOrNull { it.isLetterOrDigit() } ?: trimmed.first()
    return first.uppercaseChar().toString()
}

@Composable
fun ArtistAvatar(
    artist: String,
    artworkKey: String?,
    artworkCache: ArtworkCache,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    remoteArtworkUrl: String? = null,
    thumbnailCache: CloudThumbnailCache? = LocalThumbnailCache.current,
) {
    val bitmap = rememberArtworkBitmap(
        artworkKey = artworkKey,
        artworkCache = artworkCache,
        remoteArtworkUrl = remoteArtworkUrl,
        thumbnailCache = thumbnailCache,
    )
    val initial = remember(artist) { artistMonogramInitial(artist) }
    val fontSize = remember(size) { (size.value * 0.42f).coerceIn(13f, 44f).sp }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = initial,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
