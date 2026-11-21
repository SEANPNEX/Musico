package com.musico.ui.player

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerController
import com.musico.audio.PlayerUiState
import com.musico.core.LyricsStore
import com.musico.model.RepeatMode
import com.musico.model.SleepMode
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.store.QueueStore
import com.musico.store.QueueUiState
import com.musico.ui.components.ArtworkImage
import com.musico.ui.components.EmptyState
import com.musico.ui.components.TrackActionState
import com.musico.ui.screens.QueueSheet
import com.musico.ui.screens.QueuesContent

enum class PlayerModalSheet {
    QUEUE,
    LYRICS,
    EQUALIZER,
    SLEEP
}

enum class PlayerSidePane(val label: String) {
    LYRICS("Lyrics"),
    QUEUE("Queue")
}

@Composable
fun FullPlayerScreen(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    queueStore: QueueStore,
    queueState: QueueUiState,
    lyricsStore: LyricsStore,
    playerController: PlayerController,
    playerState: PlayerUiState,
    trackActions: TrackActionState,
    onPickLyricsFile: ((Uri) -> Unit) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeSheet by remember { mutableStateOf<PlayerModalSheet?>(null) }
    var sidePane by rememberSaveable { mutableStateOf(PlayerSidePane.LYRICS) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            val isTwoColumn = maxWidth >= 760.dp

            if (isTwoColumn) {
                Row(modifier = Modifier.fillMaxSize()) {
                    PlayerControlsPane(
                        libraryStore = libraryStore,
                        libraryState = libraryState,
                        queueState = queueState,
                        playerController = playerController,
                        playerState = playerState,
                        onOpenSheet = { target ->
                            when (target) {
                                PlayerModalSheet.LYRICS -> sidePane = PlayerSidePane.LYRICS
                                PlayerModalSheet.QUEUE -> sidePane = PlayerSidePane.QUEUE
                                else -> activeSheet = target
                            }
                        },
                        onClose = onClose,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    VerticalDivider()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 320.dp)
                                .align(Alignment.CenterHorizontally)
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            PlayerSidePane.entries.forEachIndexed { index, pane ->
                                SegmentedButton(
                                    selected = sidePane == pane,
                                    onClick = { sidePane = pane },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = PlayerSidePane.entries.size
                                    ),
                                    label = { Text(pane.label) }
                                )
                            }
                        }

                        when (sidePane) {
                            PlayerSidePane.LYRICS -> LyricsContent(
                                libraryStore = libraryStore,
                                lyricsStore = lyricsStore,
                                playerController = playerController,
                                playerState = playerState,
                                onPickLyricsFile = onPickLyricsFile,
                                embedded = true,
                                modifier = Modifier.fillMaxSize()
                            )
                            PlayerSidePane.QUEUE -> QueuesContent(
                                libraryStore = libraryStore,
                                libraryState = libraryState,
                                queueStore = queueStore,
                                queueState = queueState,
                                playerController = playerController,
                                playerState = playerState,
                                trackActions = trackActions,
                                editing = false,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            } else {
                PlayerControlsPane(
                    libraryStore = libraryStore,
                    libraryState = libraryState,
                    queueState = queueState,
                    playerController = playerController,
                    playerState = playerState,
                    onOpenSheet = { activeSheet = it },
                    onClose = onClose,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    when (activeSheet) {
        PlayerModalSheet.QUEUE -> QueueSheet(
            libraryStore = libraryStore,
            libraryState = libraryState,
            queueStore = queueStore,
            queueState = queueState,
            playerController = playerController,
            playerState = playerState,
            trackActions = trackActions,
            onDismiss = { activeSheet = null }
        )
        PlayerModalSheet.LYRICS -> LyricsSheet(
            libraryStore = libraryStore,
            lyricsStore = lyricsStore,
            playerController = playerController,
            playerState = playerState,
            onPickLyricsFile = onPickLyricsFile,
            onDismiss = { activeSheet = null }
        )
        PlayerModalSheet.EQUALIZER -> EqualizerSheet(
            eq = playerState.eq,
            onUpdateEq = { playerController.updateEq(it) },
            onDismiss = { activeSheet = null }
        )
        PlayerModalSheet.SLEEP -> SleepTimerSheet(
            sleepMode = playerState.sleepMode,
            onSetSleep = { playerController.setSleep(it) },
            onDismiss = { activeSheet = null }
        )
        null -> Unit
    }
}

@Composable
private fun PlayerControlsPane(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    queueState: QueueUiState,
    playerController: PlayerController,
    playerState: PlayerUiState,
    onOpenSheet: (PlayerModalSheet) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = playerState.currentTrack
    val activeQueue = queueState.active

    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Close player",
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        if (track != null) {
            ArtworkImage(
                artworkKey = track.artworkKey,
                artworkCache = libraryStore.artwork,
                cornerRadius = 20.dp,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 8.dp)
                    .widthIn(max = 360.dp)
                    .aspectRatio(1f)
                    .clickable { onOpenSheet(PlayerModalSheet.LYRICS) }
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${track.artist} — ${track.album}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val isFav = track.id in libraryState.favourites
                    IconButton(onClick = { libraryStore.toggleFavourite(track.id) }) {
                        Icon(
                            imageVector = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (isFav) "Remove from Favourites" else "Add to Favourites",
                            tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                PlaybackScrubber(
                    currentTimeSec = playerState.currentTimeSec,
                    durationSec = playerState.durationSec,
                    enabled = true,
                    onSeek = { playerController.seek(it) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { playerController.toggleShuffle() }) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (activeQueue.shuffle) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(onClick = { playerController.previous() }) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    FilledIconButton(
                        onClick = { playerController.togglePlayPause() },
                        modifier = Modifier.size(68.dp)
                    ) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(onClick = { playerController.next() }) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(34.dp)
                        )
                    }
                    IconButton(onClick = { playerController.cycleRepeat() }) {
                        Icon(
                            imageVector = if (activeQueue.repeatMode == RepeatMode.ONE) {
                                Icons.Rounded.RepeatOne
                            } else {
                                Icons.Rounded.Repeat
                            },
                            contentDescription = "Repeat",
                            tint = if (activeQueue.repeatMode == RepeatMode.OFF) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }

                val positionText = activeQueue.currentIndex?.let { idx ->
                    "${idx + 1} of ${activeQueue.trackIds.size}"
                }.orEmpty()
                val queueIndicator = if (positionText.isNotEmpty()) {
                    "${activeQueue.name} · $positionText"
                } else {
                    activeQueue.name
                }
                Text(
                    text = queueIndicator,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    PlayerActionButton(
                        label = "Queue",
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        active = false,
                        onClick = { onOpenSheet(PlayerModalSheet.QUEUE) }
                    )
                    PlayerActionButton(
                        label = "Lyrics",
                        icon = Icons.Rounded.Lyrics,
                        active = false,
                        onClick = { onOpenSheet(PlayerModalSheet.LYRICS) }
                    )
                    PlayerActionButton(
                        label = "Equalizer",
                        icon = Icons.Rounded.Equalizer,
                        active = playerState.eq.enabled,
                        onClick = { onOpenSheet(PlayerModalSheet.EQUALIZER) }
                    )
                    PlayerActionButton(
                        label = "Sleep Timer",
                        icon = Icons.Rounded.Bedtime,
                        active = playerState.sleepMode !is SleepMode.Off,
                        onClick = { onOpenSheet(PlayerModalSheet.SLEEP) }
                    )
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
            EmptyState(
                icon = Icons.Rounded.MusicNote,
                title = "Nothing Playing",
                description = "Pick a song from your library.",
                actionLabel = "Queues",
                onAction = { onOpenSheet(PlayerModalSheet.QUEUE) }
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlayerActionButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}
