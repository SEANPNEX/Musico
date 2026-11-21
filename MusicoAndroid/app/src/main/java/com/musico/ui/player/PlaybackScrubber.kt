package com.musico.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musico.ui.components.formatTime
import kotlin.math.max

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

    val shown = if (isScrubbing) scrubTimeSec else currentTimeSec.coerceIn(0.0, safeDuration)
    val elapsedText = formatTime(shown)
    val remainingText = "-" + formatTime(max(durationSec - shown, 0.0))

    val timeStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val timeColor = MaterialTheme.colorScheme.onSurfaceVariant

    if (inline) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = elapsedText,
                style = timeStyle,
                color = timeColor
            )
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
                modifier = Modifier.weight(1f)
            )
            Text(
                text = remainingText,
                style = timeStyle,
                color = timeColor
            )
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
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
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = elapsedText,
                    style = timeStyle,
                    color = timeColor
                )
                Text(
                    text = remainingText,
                    style = timeStyle,
                    color = timeColor
                )
            }
        }
    }
}
