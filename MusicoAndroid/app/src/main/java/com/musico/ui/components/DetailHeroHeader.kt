package com.musico.ui.components

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.core.ArtworkCache
import com.musico.core.CloudThumbnailCache
import com.musico.ui.theme.LocalReduceMotion
import com.musico.ui.theme.MusicoMotion
import com.musico.ui.theme.fastBlurBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DetailHeroHeader(
    artworkKey: String?,
    artworkCache: ArtworkCache,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    metadata: String? = null,
    remoteArtworkUrl: String? = null,
    thumbnailCache: CloudThumbnailCache? = LocalThumbnailCache.current,
    artworkContent: @Composable () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val rawBitmap = rememberArtworkBitmap(
        artworkKey = artworkKey,
        artworkCache = artworkCache,
        remoteArtworkUrl = remoteArtworkUrl,
        thumbnailCache = thumbnailCache,
    )
    val needsSoftwareBlur = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    val backdropBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = rawBitmap,
        key2 = needsSoftwareBlur,
    ) {
        value = if (rawBitmap == null) {
            null
        } else if (needsSoftwareBlur) {
            withContext(Dispatchers.Default) { fastBlurBitmap(rawBitmap, 14) }
        } else {
            rawBitmap
        }
    }

    val backdropAlpha by animateFloatAsState(
        targetValue = if (backdropBitmap != null) 1f else 0f,
        animationSpec = MusicoMotion.finiteMotionSpec(
            reduceMotion = reduceMotion,
            normal = tween(durationMillis = MusicoMotion.ENTER_DURATION_MS),
        ),
        label = "hero_backdrop_alpha",
    )

    val washGradient = Brush.verticalGradient(
        0.0f to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.26f),
        0.6f to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
        1.0f to Color.Transparent,
    )

    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(washGradient),
        )

        val loadedBackdrop = backdropBitmap
        if (loadedBackdrop != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        alpha = backdropAlpha
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.0f to Color.Black.copy(alpha = 0.44f),
                                0.6f to Color.Black.copy(alpha = 0.18f),
                                1.0f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                val imageModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Modifier.fillMaxSize().blur(28.dp)
                } else {
                    Modifier.fillMaxSize()
                }
                Image(
                    bitmap = loadedBackdrop.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = imageModifier,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            artworkContent()
            Spacer(Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!metadata.isNullOrBlank()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
