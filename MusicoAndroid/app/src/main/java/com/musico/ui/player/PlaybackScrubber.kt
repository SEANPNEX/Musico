package com.musico.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musico.ui.components.formatTime
import com.musico.ui.theme.LocalReduceMotion
import com.musico.ui.theme.MusicoMotion
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScrubber(
    currentTimeSec: Double,
    durationSec: Double,
    enabled: Boolean,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false
) {
    val safeDuration = max(durationSec, 1.0)
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubTimeSec by remember { mutableDoubleStateOf(0.0) }
    val reduceMotion = LocalReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }

    val thumbScale by animateFloatAsState(
        targetValue = if (isScrubbing) 1.24f else 1.0f,
        animationSpec = MusicoMotion.motionSpec(reduceMotion, MusicoMotion.EffectsSpring),
        label = "scrubber_thumb_scale",
    )
    val trackScaleY by animateFloatAsState(
        targetValue = if (isScrubbing) 1.32f else 1.0f,
        animationSpec = MusicoMotion.motionSpec(reduceMotion, MusicoMotion.EffectsSpring),
        label = "scrubber_track_scale",
    )

    val shown = if (isScrubbing) scrubTimeSec else currentTimeSec.coerceIn(0.0, safeDuration)
    val elapsedText = formatTime(shown)
    val remainingText = "-" + formatTime(max(durationSec - shown, 0.0))

    val timeStyle = MaterialTheme.typography.labelSmall.copy(
        fontFeatureSettings = "tnum",
        fontWeight = if (isScrubbing) FontWeight.SemiBold else FontWeight.Normal,
    )
    val elapsedColor = if (isScrubbing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val remainingColor = MaterialTheme.colorScheme.onSurfaceVariant

    val sliderComposable: @Composable (Modifier) -> Unit = { sliderModifier ->
        Slider(
            value = shown.toFloat(),
            onValueChange = {
                isScrubbing = true
                scrubTimeSec = it.toDouble()
            },
            onValueChangeFinished = {
                if (isScrubbing) {
                    onSeek(scrubTimeSec)
                    isScrubbing = false
                }
            },
            valueRange = 0f..safeDuration.toFloat(),
            enabled = enabled,
            interactionSource = interactionSource,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = interactionSource,
                    enabled = enabled,
                    modifier = Modifier.graphicsLayer {
                        scaleX = thumbScale
                        scaleY = thumbScale
                    },
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    enabled = enabled,
                    modifier = Modifier.graphicsLayer {
                        scaleY = trackScaleY
                    },
                )
            },
            modifier = sliderModifier,
        )
    }

    if (inline) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = elapsedText,
                style = timeStyle,
                color = elapsedColor,
            )
            sliderComposable(Modifier.weight(1f))
            Text(
                text = remainingText,
                style = timeStyle,
                color = remainingColor,
            )
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            sliderComposable(Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = elapsedText,
                    style = timeStyle,
                    color = elapsedColor,
                )
                Text(
                    text = remainingText,
                    style = timeStyle,
                    color = remainingColor,
                )
            }
        }
    }
}
