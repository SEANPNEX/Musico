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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerController
import com.musico.audio.PlayerUiState
import com.musico.core.ArtworkCache
import com.musico.i18n.LocalStrings
import com.musico.model.PlayQueue
import com.musico.model.RepeatMode
import com.musico.ui.components.ArtworkImage
import com.musico.ui.theme.LocalHasBackgroundImage

enum class SidePanel {
    QUEUE,
    LYRICS
}

@Composable
fun PlayerBar(
    playerController: PlayerController,
    playerState: PlayerUiState,
    activeQueue: PlayQueue,
    artworkCache: ArtworkCache,
    sidePanel: SidePanel?,
    onToggleSidePanel: (SidePanel) -> Unit,
    onOpenSongInfo: (String) -> Unit,
    onOpenFullPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val haptic = LocalHapticFeedback.current
    val track = playerState.currentTrack

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (LocalHasBackgroundImage.current) {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.84f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (LocalHasBackgroundImage.current) 0.dp else 3.dp
    ) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onOpenFullPlayer)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkImage(
                        artworkKey = track?.artworkKey,
                        artworkCache = artworkCache,
                        remoteArtworkUrl = track?.remoteArtworkUrl,
                        size = 48.dp,
                        cornerRadius = 8.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = track?.title ?: strings.notPlaying,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track?.let { strings.displayArtist(it.artist) } ?: strings.pickSongHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Column(
                    modifier = Modifier.widthIn(min = 260.dp, max = 480.dp).weight(1.4f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(
                            onClick = { playerController.toggleShuffle() },
                            enabled = track != null
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shuffle,
                                contentDescription = strings.shuffle,
                                tint = if (activeQueue.shuffle) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        IconButton(
                            onClick = { playerController.previous() },
                            enabled = track != null
                        ) {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = strings.previous)
                        }
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                playerController.togglePlayPause()
                            },
                            enabled = track != null
                        ) {
                            Icon(
                                imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playerState.isPlaying) strings.pause else strings.play
                            )
                        }
                        IconButton(
                            onClick = { playerController.next() },
                            enabled = track != null
                        ) {
                            Icon(Icons.Rounded.SkipNext, contentDescription = strings.next)
                        }
                        IconButton(
                            onClick = { playerController.cycleRepeat() },
                            enabled = track != null
                        ) {
                            Icon(
                                imageVector = if (activeQueue.repeatMode == RepeatMode.ONE) {
                                    Icons.Rounded.RepeatOne
                                } else {
                                    Icons.Rounded.Repeat
                                },
                                contentDescription = strings.repeat,
                                tint = if (activeQueue.repeatMode == RepeatMode.OFF) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                    }

                    PlaybackScrubber(
                        currentTimeSec = playerState.currentTimeSec,
                        durationSec = playerState.durationSec,
                        enabled = track != null,
                        onSeek = { playerController.seek(it) },
                        inline = true
                    )
                }

                Row(
                    modifier = Modifier.weight(0.7f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (track != null) {
                        IconButton(onClick = { onOpenSongInfo(track.id) }) {
                            Icon(Icons.Rounded.Info, contentDescription = strings.songInfo)
                        }
                    }
                    IconToggleButton(
                        checked = sidePanel == SidePanel.LYRICS,
                        onCheckedChange = { onToggleSidePanel(SidePanel.LYRICS) }
                    ) {
                        Icon(Icons.Rounded.Lyrics, contentDescription = strings.lyricsPanel)
                    }
                    IconToggleButton(
                        checked = sidePanel == SidePanel.QUEUE,
                        onCheckedChange = { onToggleSidePanel(SidePanel.QUEUE) }
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = strings.queuePanel)
                    }
                }
            }
        }
    }
}
