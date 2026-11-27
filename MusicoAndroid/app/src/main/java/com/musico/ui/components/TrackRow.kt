package com.musico.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musico.core.ArtworkCache
import com.musico.i18n.LocalStrings
import com.musico.model.Track

data class TrackSwipeActions(
    val onPlayNext: (Track) -> Unit,
    val onDelete: (Track) -> Unit,
)

sealed interface TrackEndSwipeBehavior {
    data object DefaultDelete : TrackEndSwipeBehavior
    data object Disabled : TrackEndSwipeBehavior
    data class Custom(
        val label: String,
        val icon: ImageVector,
        val thresholdFraction: Float = 0.40f,
        val minThresholdDp: Dp = 130.dp,
        val onTrigger: () -> Unit,
    ) : TrackEndSwipeBehavior
}

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
    swipeEnabled: Boolean = true,
    onSwipePlayNext: (() -> Unit)? = null,
    endSwipeBehavior: TrackEndSwipeBehavior = TrackEndSwipeBehavior.DefaultDelete,
    menuContent: (@Composable ColumnScope.(onDismiss: () -> Unit) -> Unit)? = null
) {
    val strings = LocalStrings.current
    val swipeActions = LocalTrackSwipeActions.current
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

    val resolvedPlayNext = if (isAvailable) {
        onSwipePlayNext ?: swipeActions?.let { actions -> { actions.onPlayNext(track) } }
    } else {
        null
    }
    val startAction = resolvedPlayNext?.let { trigger ->
        SwipeActionConfig(
            label = strings.playNext,
            icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
            isDestructive = false,
            thresholdFraction = 0.28f,
            minThresholdDp = 88.dp,
            maxThresholdDp = 130.dp,
            onTrigger = trigger,
        )
    }
    val endAction = when (endSwipeBehavior) {
        TrackEndSwipeBehavior.Disabled -> null
        TrackEndSwipeBehavior.DefaultDelete -> {
            if (track.isEphemeral || swipeActions == null) {
                null
            } else {
                SwipeActionConfig(
                    label = strings.delete,
                    icon = Icons.Rounded.DeleteOutline,
                    isDestructive = true,
                    thresholdFraction = 0.44f,
                    minThresholdDp = 140.dp,
                    maxThresholdDp = 200.dp,
                    onTrigger = { swipeActions.onDelete(track) },
                )
            }
        }
        is TrackEndSwipeBehavior.Custom -> SwipeActionConfig(
            label = endSwipeBehavior.label,
            icon = endSwipeBehavior.icon,
            isDestructive = true,
            thresholdFraction = endSwipeBehavior.thresholdFraction,
            minThresholdDp = endSwipeBehavior.minThresholdDp,
            maxThresholdDp = 200.dp,
            onTrigger = endSwipeBehavior.onTrigger,
        )
    }

    if (!swipeEnabled || (startAction == null && endAction == null)) {
        rowContent(modifier)
        return
    }

    MusicoSwipeActionRow(
        modifier = modifier,
        startAction = startAction,
        endAction = endAction,
        enabled = swipeEnabled,
    ) {
        rowContent(Modifier)
    }
}
