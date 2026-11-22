package com.musico.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musico.i18n.LocalStrings
import com.musico.model.EqPreset
import com.musico.model.EqSettings
import kotlin.math.roundToInt

private val BAND_LABELS = listOf("31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    eq: EqSettings,
    onUpdateEq: (EqSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        EqualizerSheetContent(
            eq = eq,
            onUpdateEq = onUpdateEq,
            onDismiss = onDismiss
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheetContent(
    eq: EqSettings,
    onUpdateEq: (EqSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    var presetExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(
                onClick = {
                    onUpdateEq(
                        eq.copy(
                            gains = EqPreset.ALL.first().gains,
                            preampDb = 0f,
                            presetName = "Flat"
                        )
                    )
                }
            ) {
                Text(strings.reset)
            }
            Text(
                text = strings.equalizer,
                style = MaterialTheme.typography.titleLarge
            )
            TextButton(onClick = onDismiss) {
                Text(strings.done)
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = strings.equalizer,
                style = MaterialTheme.typography.titleMedium
            )
            Switch(
                checked = eq.enabled,
                onCheckedChange = { enabled -> onUpdateEq(eq.copy(enabled = enabled)) }
            )
        }

        ExposedDropdownMenuBox(
            expanded = presetExpanded,
            onExpandedChange = { if (eq.enabled) presetExpanded = it }
        ) {
            OutlinedTextField(
                value = strings.eqPresetLabel(eq.presetName),
                onValueChange = {},
                readOnly = true,
                enabled = eq.enabled,
                label = { Text(strings.presetLabel) },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded)
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = presetExpanded,
                onDismissRequest = { presetExpanded = false }
            ) {
                EqPreset.ALL.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(strings.eqPresetLabel(preset.name)) },
                        onClick = {
                            onUpdateEq(
                                eq.copy(
                                    gains = preset.gains,
                                    presetName = preset.name
                                )
                            )
                            presetExpanded = false
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (eq.enabled) 1f else 0.4f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            BAND_LABELS.forEachIndexed { index, label ->
                val gain = eq.gains.getOrElse(index) { 0f }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {
                            contentDescription = strings.bandAccessibility(label, formatGain(gain))
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = formatGain(gain),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontFeatureSettings = "tnum"
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                    VerticalBandSlider(
                        value = gain,
                        enabled = eq.enabled,
                        onValueChange = { newValue ->
                            val updated = eq.gains.toMutableList()
                            while (updated.size < 10) updated.add(0f)
                            if (updated[index] != newValue) {
                                updated[index] = newValue
                                onUpdateEq(eq.copy(gains = updated, presetName = null))
                            }
                        },
                        modifier = Modifier
                            .height(176.dp)
                            .width(28.dp)
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (eq.enabled) 1f else 0.4f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = strings.preampLabel(formatGain(eq.preampDb)),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = eq.preampDb,
                onValueChange = { raw ->
                    val snapped = ((raw * 2f).roundToInt() / 2f).coerceIn(
                        EqSettings.GAIN_RANGE.start,
                        EqSettings.GAIN_RANGE.endInclusive
                    )
                    onUpdateEq(eq.copy(preampDb = snapped))
                },
                valueRange = EqSettings.GAIN_RANGE,
                enabled = eq.enabled
            )
        }
    }
}

@Composable
private fun VerticalBandSlider(
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val min = EqSettings.GAIN_RANGE.start
    val max = EqSettings.GAIN_RANGE.endInclusive
    val activeColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val density = LocalDensity.current

    fun yToValue(y: Float, heightPx: Float): Float {
        if (heightPx <= 0f) return value
        val clamped = y.coerceIn(0f, heightPx)
        val raw = min + (1f - clamped / heightPx) * (max - min)
        return ((raw * 2f).roundToInt() / 2f).coerceIn(min, max)
    }

    BoxWithConstraints(
        modifier = modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onValueChange(yToValue(offset.y, size.height.toFloat()))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    onValueChange(yToValue(change.position.y, size.height.toFloat()))
                }
            }
    ) {
        val thumbRadiusPx = with(density) { 9.dp.toPx() }
        val trackStrokePx = with(density) { 4.dp.toPx() }
        val tickHalfWidthPx = with(density) { 7.dp.toPx() }
        val tickStrokePx = with(density) { 1.dp.toPx() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val topY = thumbRadiusPx
            val bottomY = (size.height - thumbRadiusPx).coerceAtLeast(topY + 1f)
            val usableHeight = bottomY - topY
            val midY = topY + usableHeight / 2f
            val fraction = ((value - min) / (max - min)).coerceIn(0f, 1f)
            val thumbY = topY + usableHeight * (1f - fraction)

            // Center 0 dB tick
            drawLine(
                color = tickColor,
                start = Offset(centerX - tickHalfWidthPx, midY),
                end = Offset(centerX + tickHalfWidthPx, midY),
                strokeWidth = tickStrokePx
            )

            // Full track
            drawLine(
                color = trackColor,
                start = Offset(centerX, topY),
                end = Offset(centerX, bottomY),
                strokeWidth = trackStrokePx,
                cap = StrokeCap.Round
            )

            // Active segment from 0 dB to thumb
            drawLine(
                color = activeColor,
                start = Offset(centerX, midY),
                end = Offset(centerX, thumbY),
                strokeWidth = trackStrokePx,
                cap = StrokeCap.Round
            )

            // Thumb
            drawCircle(
                color = activeColor,
                radius = thumbRadiusPx,
                center = Offset(centerX, thumbY)
            )
        }
    }
}

private fun formatGain(gain: Float): String {
    if (gain == 0f) return "0"
    val rounded = gain.roundToInt()
    return if (gain == rounded.toFloat()) {
        if (rounded > 0) "+$rounded" else "$rounded"
    } else {
        val sign = if (gain > 0f) "+" else ""
        "$sign$gain"
    }
}
