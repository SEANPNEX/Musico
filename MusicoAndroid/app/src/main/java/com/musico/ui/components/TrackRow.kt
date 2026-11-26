package com.musico.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.core.ArtworkCache
import com.musico.i18n.LocalStrings
import com.musico.model.Track

data class TrackSwipeActions(
    val onPlayNext: (Track) -> Unit,
    val onAddToQueue: (Track) -> Unit,
)

val LocalTrackSwipeActions = staticCompositionLocalOf<TrackSwipeActions?> { null }

@Composable
fun TrackRow(
    track: Track,
    artworkCache: ArtworkCache,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isAvailable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    backgroundColor: Color = Color.Transparent,
    dragHandleModifier: Modifier? = null,
    trailingActions: (@Composable () -> Unit)? = null,
    onSwipePlayNext: (() -> Unit)? = null,
    onSwipeAddToQueue: (() -> Unit)? = null,
    menuContent: (@Composable ColumnScope.(onDismiss: () -> Unit) -> Unit)? = null
) {
    val strings = LocalStrings.current
    val swipeActions = LocalTrackSwipeActions.current
    val resolvedPlayNext = onSwipePlayNext ?: swipeActions?.let { actions -> { actions.onPlayNext(track) } }
    val resolvedAddToQueue = onSwipeAddToQueue ?: swipeActions?.let { actions -> { actions.onAddToQueue(track) } }
    val resolvedSubtitle = subtitle ?: strings.displayArtist(track.artist)
    var menuExpanded by remember { mutableStateOf(false) }

    val rowContent: @Composable (Modifier) -> Unit = { rowMod ->
        Row(
            modifier = rowMod
                .fillMaxWidth()
                .background(backgroundColor)
                .clickable(enabled = isAvailable, onClick = onClick)
                .alpha(if (isAvailable) 1f else 0.4f)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkImage(
                artworkKey = track.artworkKey,
                artworkCache = artworkCache,
                size = 46.dp,
                cornerRadius = 8.dp,
                remoteArtworkUrl = track.remoteArtworkUrl,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = resolvedSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isCurrent) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause,
                    contentDescription = if (isPlaying) strings.playing else strings.paused,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = formatTime(track.durationSec),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (trailingActions != null) {
                trailingActions()
            }
            if (dragHandleModifier != null) {
                Icon(
                    imageVector = Icons.Rounded.DragHandle,
                    contentDescription = strings.reorder,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandleModifier.padding(horizontal = 8.dp, vertical = 10.dp)
                )
            }
            if (menuContent != null) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = strings.trackOptions,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        menuContent { menuExpanded = false }
                    }
                }
            } else if (trailingActions == null && dragHandleModifier == null) {
                Spacer(Modifier.width(12.dp))
            }
        }
    }

    if (!isAvailable || (resolvedPlayNext == null && resolvedAddToQueue == null)) {
        rowContent(modifier)
        return
    }

    val haptic = LocalHapticFeedback.current
    val currentPlayNext by rememberUpdatedState(resolvedPlayNext)
    val currentAddToQueue by rememberUpdatedState(resolvedAddToQueue)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentPlayNext?.invoke()
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentAddToQueue?.invoke()
                }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = resolvedPlayNext != null,
        enableDismissFromEndToStart = resolvedAddToQueue != null,
        modifier = modifier,
        backgroundContent = {
            val dir = dismissState.dismissDirection
            val showStart = dir == SwipeToDismissBoxValue.StartToEnd && resolvedPlayNext != null
            val showEnd = dir == SwipeToDismissBoxValue.EndToStart && resolvedAddToQueue != null
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        when {
                            showStart -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            showEnd -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                            else -> Color.Transparent
                        }
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = if (showStart) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (showStart) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            contentDescription = strings.playNext,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = strings.playNext,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                } else if (showEnd) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = strings.addToQueue,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = strings.addToQueue,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        },
        content = { rowContent(Modifier) },
    )
}
