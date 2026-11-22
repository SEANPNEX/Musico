package com.musico.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musico.i18n.LocalStrings
import com.musico.model.SleepMode
import com.musico.ui.components.formatTime
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

private val SLEEP_PRESETS = listOf(5, 10, 15, 30, 45, 60, 90)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    sleepMode: SleepMode,
    onSetSleep: (SleepMode) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        SleepTimerSheetContent(
            sleepMode = sleepMode,
            onSetSleep = onSetSleep,
            onDismiss = onDismiss
        )
    }
}

@Composable
fun SleepTimerSheetContent(
    sleepMode: SleepMode,
    onSetSleep: (SleepMode) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    var customMinutes by remember { mutableIntStateOf(20) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(sleepMode) {
        while (sleepMode is SleepMode.Until) {
            nowMs = System.currentTimeMillis()
            delay(1000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = strings.sleepTimer,
                style = MaterialTheme.typography.titleLarge
            )
            TextButton(onClick = onDismiss) {
                Text(strings.done)
            }
        }

        if (sleepMode !is SleepMode.Off) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Bedtime,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(Modifier.width(12.dp))
                    val statusText = when (sleepMode) {
                        is SleepMode.Until -> {
                            val remainingSec = max((sleepMode.targetEpochMs - nowMs) / 1000.0, 0.0)
                            strings.stopsIn(formatTime(remainingSec))
                        }
                        SleepMode.EndOfTrack -> strings.stopsAtEndOfSong
                        SleepMode.Off -> ""
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { onSetSleep(SleepMode.Off) }) {
                        Text(strings.cancelTimer, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        Text(
            text = strings.stopPlayingAfter,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        SLEEP_PRESETS.forEach { minutes ->
            ListItem(
                headlineContent = { Text(strings.minutesCount(minutes)) },
                modifier = Modifier.clickable {
                    val target = System.currentTimeMillis() + minutes * 60_000L
                    onSetSleep(SleepMode.Until(target))
                    onDismiss()
                }
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = strings.customMinutes(customMinutes),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { customMinutes = (customMinutes - 1).coerceAtLeast(1) },
                        enabled = customMinutes > 1
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = strings.decreaseMinutes)
                    }
                    IconButton(
                        onClick = { customMinutes = (customMinutes + 1).coerceAtMost(240) },
                        enabled = customMinutes < 240
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = strings.increaseMinutes)
                    }
                    Spacer(Modifier.width(4.dp))
                    FilledTonalButton(
                        onClick = {
                            val target = System.currentTimeMillis() + customMinutes * 60_000L
                            onSetSleep(SleepMode.Until(target))
                            onDismiss()
                        }
                    ) {
                        Text(strings.start)
                    }
                }
            }
            Slider(
                value = customMinutes.toFloat(),
                onValueChange = { customMinutes = it.roundToInt().coerceIn(1, 240) },
                valueRange = 1f..240f
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        ListItem(
            headlineContent = { Text(strings.endOfCurrentSong) },
            modifier = Modifier.clickable {
                onSetSleep(SleepMode.EndOfTrack)
                onDismiss()
            }
        )
    }
}
