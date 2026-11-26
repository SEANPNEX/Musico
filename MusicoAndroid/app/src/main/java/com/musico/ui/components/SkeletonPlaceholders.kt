package com.musico.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.musico.ui.theme.LocalReduceMotion
import com.musico.ui.theme.MusicoMotion

@Composable
fun rememberShimmerProgress(): Float? {
    val reduceMotion = LocalReduceMotion.current || MusicoMotion.rememberReduceMotion()
    if (reduceMotion) return null
    val transition = rememberInfiniteTransition(label = "skeleton_shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_progress",
    )
    return progress
}

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp,
    shimmerProgress: Float? = rememberShimmerProgress(),
) {
    val baseColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f)
    val highlightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

    Box(
        modifier = modifier.drawBehind {
            val radiusPx = cornerRadius.toPx()
            val radius = CornerRadius(radiusPx, radiusPx)
            if (shimmerProgress == null) {
                drawRoundRect(color = baseColor, cornerRadius = radius)
            } else {
                val w = size.width.coerceAtLeast(1f)
                val sweepWidth = (w * 0.65f).coerceAtLeast(48f)
                val startX = -sweepWidth + shimmerProgress * (w + sweepWidth * 2f)
                val endX = startX + sweepWidth
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(baseColor, highlightColor, baseColor),
                        start = Offset(startX, 0f),
                        end = Offset(endX, size.height),
                    ),
                    cornerRadius = radius,
                )
            }
        },
    )
}

@Composable
fun SkeletonTrackRow(
    index: Int = 0,
    shimmerProgress: Float? = rememberShimmerProgress(),
    modifier: Modifier = Modifier,
) {
    val titleFraction = when (index % 4) {
        0 -> 0.66f
        1 -> 0.54f
        2 -> 0.74f
        else -> 0.60f
    }
    val subtitleFraction = when (index % 3) {
        0 -> 0.42f
        1 -> 0.35f
        else -> 0.48f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBox(
            modifier = Modifier.size(46.dp),
            cornerRadius = 8.dp,
            shimmerProgress = shimmerProgress,
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(titleFraction)
                    .height(15.dp),
                cornerRadius = 6.dp,
                shimmerProgress = shimmerProgress,
            )
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth(subtitleFraction)
                    .height(12.dp),
                cornerRadius = 6.dp,
                shimmerProgress = shimmerProgress,
            )
        }
        Spacer(Modifier.width(12.dp))
        SkeletonBox(
            modifier = Modifier
                .width(34.dp)
                .height(12.dp),
            cornerRadius = 6.dp,
            shimmerProgress = shimmerProgress,
        )
    }
}

fun LazyListScope.skeletonTrackRows(
    count: Int = 7,
    keyPrefix: String = "skeleton_track",
) {
    items(count = count, key = { idx -> "${keyPrefix}_$idx" }) { idx ->
        SkeletonTrackRow(index = idx)
    }
}
