package com.musico.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AddToQueue
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerController
import com.musico.cloud.CloudExporter
import com.musico.i18n.LocalStrings
import com.musico.model.PlayQueue
import com.musico.model.Playlist
import com.musico.model.SearchField
import com.musico.model.Track
import com.musico.store.LibraryStore
import com.musico.store.PlayHistoryUiState
import kotlinx.coroutines.CancellationException

@Stable
class TrackActionState(
    val libraryStore: LibraryStore,
    val playerController: PlayerController,
    val cloudExporter: CloudExporter,
    val onPickFolder: ((Uri) -> Unit) -> Unit,
    val onPickImage: ((Uri) -> Unit) -> Unit,
    val onQuickSearch: (String, SearchField) -> Unit,
) {
    var queuePickerIds: List<String>? by mutableStateOf(null)
    var playlistPickerIds: List<String>? by mutableStateOf(null)
    var newPlaylistIds: List<String>? by mutableStateOf(null)
    var savingCloudTrack: Pair<Track, Uri>? by mutableStateOf(null)
    var songInfoTrackId: String? by mutableStateOf(null)
}

@Composable
fun rememberTrackActionState(
    libraryStore: LibraryStore,
    playerController: PlayerController,
    cloudExporter: CloudExporter,
    onPickFolder: ((Uri) -> Unit) -> Unit,
    onPickImage: ((Uri) -> Unit) -> Unit,
    onQuickSearch: (String, SearchField) -> Unit,
): TrackActionState {
    return remember(libraryStore, playerController, cloudExporter, onPickFolder, onPickImage, onQuickSearch) {
        TrackActionState(libraryStore, playerController, cloudExporter, onPickFolder, onPickImage, onQuickSearch)
    }
}

@Composable
fun TrackMenuItems(
    ids: List<String>,
    queues: List<PlayQueue>,
    activeQueueId: String,
    actionState: TrackActionState,
    onDismiss: () -> Unit,
    favouriteId: String? = null,
    isFavourite: Boolean = false,
    namedQueueTitle: String? = null,
) {
    val strings = LocalStrings.current
    if (ids.size == 1) {
        DropdownMenuItem(
            text = { Text(strings.songInfo) },
            leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
            onClick = {
                actionState.songInfoTrackId = ids[0]
                onDismiss()
            }
        )
        HorizontalDivider()
    }
    DropdownMenuItem(
        text = { Text(strings.playNext) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null) },
        enabled = ids.isNotEmpty(),
        onClick = {
            actionState.playerController.playNext(ids)
            onDismiss()
        }
    )
    DropdownMenuItem(
        text = { Text(strings.addToQueue) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
        enabled = ids.isNotEmpty(),
        onClick = {
            actionState.playerController.addToQueue(ids, queueId = null)
            onDismiss()
        }
    )
    if (queues.size > 1) {
        DropdownMenuItem(
            text = { Text(strings.addToAnotherQueueEllipsis) },
            leadingIcon = { Icon(Icons.Rounded.AddToQueue, contentDescription = null) },
            enabled = ids.isNotEmpty(),
            onClick = {
                actionState.queuePickerIds = ids
                onDismiss()
            }
        )
    }
    DropdownMenuItem(
        text = { Text(strings.playInNewQueue) },
        leadingIcon = { Icon(Icons.Rounded.Queue, contentDescription = null) },
        enabled = ids.isNotEmpty(),
        onClick = {
            if (namedQueueTitle != null) {
                actionState.playerController.playInNamedQueue(namedQueueTitle, ids, startAt = 0)
            } else {
                actionState.playerController.playInNewQueue(ids)
            }
            onDismiss()
        }
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text(strings.addToPlaylistEllipsis) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
        enabled = ids.isNotEmpty(),
        onClick = {
            actionState.playlistPickerIds = ids
            onDismiss()
        }
    )
    if (favouriteId != null) {
        DropdownMenuItem(
            text = { Text(if (isFavourite) strings.removeFromFavourites else strings.addToFavourites) },
            leadingIcon = {
                Icon(
                    imageVector = if (isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = null
                )
            },
            onClick = {
                actionState.libraryStore.toggleFavourite(favouriteId)
                onDismiss()
            }
        )
    }
    val singleCloudTrack = if (ids.size == 1) {
        actionState.libraryStore.track(ids[0])?.takeIf { it.isCloud }
    } else {
        null
    }
    if (singleCloudTrack != null) {
        DropdownMenuItem(
            text = { Text(strings.saveToLocalFolderEllipsis) },
            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
            onClick = {
                onDismiss()
                actionState.onPickFolder { uri ->
                    actionState.savingCloudTrack = singleCloudTrack to uri
                }
            }
        )
    }
}

@Composable
fun TrackActionDialogs(
    state: TrackActionState,
    queues: List<PlayQueue>,
    activeQueueId: String,
    playlists: List<Playlist>,
    historyState: PlayHistoryUiState,
) {
    val strings = LocalStrings.current
    val queueIds = state.queuePickerIds
    if (queueIds != null) {
        val otherQueues = queues.filter { it.id != activeQueueId }
        OptionPickerDialog(
            title = strings.addToQueue,
            options = otherQueues.map { q ->
                PickerOption(
                    id = q.id,
                    title = strings.displayQueueName(q.name),
                    subtitle = strings.songCount(q.trackIds.size)
                )
            },
            onDismiss = { state.queuePickerIds = null },
            onSelect = { selectedQueueId ->
                state.playerController.addToQueue(queueIds, queueId = selectedQueueId)
            }
        )
    }

    val playlistIds = state.playlistPickerIds
    if (playlistIds != null) {
        OptionPickerDialog(
            title = strings.addToPlaylistEllipsis.removeSuffix("…"),
            options = playlists.map { pl ->
                PickerOption(
                    id = pl.id,
                    title = pl.name,
                    subtitle = strings.songCount(pl.trackIds.size)
                )
            },
            extraActionLabel = strings.newPlaylistEllipsis,
            onExtraAction = {
                state.newPlaylistIds = playlistIds
            },
            onDismiss = { state.playlistPickerIds = null },
            onSelect = { playlistId ->
                state.libraryStore.addToPlaylist(playlistIds, playlistId)
            }
        )
    }

    val createIds = state.newPlaylistIds
    if (createIds != null) {
        TextInputDialog(
            title = strings.newPlaylist,
            label = strings.nameLabel,
            supportingText = strings.enterPlaylistNameHint,
            confirmLabel = strings.create,
            onDismiss = { state.newPlaylistIds = null },
            onConfirm = { name ->
                val newId = state.libraryStore.createPlaylist(name)
                state.libraryStore.addToPlaylist(createIds, newId)
            }
        )
    }

    val saveTarget = state.savingCloudTrack
    if (saveTarget != null) {
        SaveSingleCloudTrackDialog(
            track = saveTarget.first,
            targetUri = saveTarget.second,
            libraryStore = state.libraryStore,
            cloudExporter = state.cloudExporter,
            onDismiss = { state.savingCloudTrack = null },
        )
    }

    val infoTrackId = state.songInfoTrackId
    val infoTrack = infoTrackId?.let { state.libraryStore.track(it) }
    if (infoTrack != null) {
        SongInfoSheet(
            track = infoTrack,
            libraryStore = state.libraryStore,
            historyState = historyState,
            onPickImage = state.onPickImage,
            onQuickSearch = state.onQuickSearch,
            onDismiss = { state.songInfoTrackId = null },
        )
    }
}

@Composable
private fun SaveSingleCloudTrackDialog(
    track: Track,
    targetUri: Uri,
    libraryStore: LibraryStore,
    cloudExporter: CloudExporter,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var isExporting by remember(track.id, targetUri) { mutableStateOf(true) }
    var savedFileName by remember(track.id, targetUri) { mutableStateOf<String?>(null) }
    var libraryUpdated by remember(track.id, targetUri) { mutableStateOf(false) }
    var errorMessage by remember(track.id, targetUri) { mutableStateOf<String?>(null) }

    LaunchedEffect(track.id, targetUri) {
        isExporting = true
        savedFileName = null
        libraryUpdated = false
        errorMessage = null
        try {
            val fileName = cloudExporter.exportTrack(
                track = track,
                root = libraryStore.rootFor(track),
                targetTreeUri = targetUri,
            )
            savedFileName = fileName
            if (libraryStore.isUriInsideIndexedRoot(targetUri)) {
                libraryStore.rescan()
                libraryUpdated = true
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (err: Exception) {
            errorMessage = err.message ?: strings.couldNotSaveSongFallback
        } finally {
            isExporting = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
        title = {
            Text(
                when {
                    isExporting -> strings.savingSongTitle
                    errorMessage != null -> strings.saveFailedTitle
                    else -> strings.songSavedTitle
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    isExporting -> {
                        Text(
                            text = strings.downloadingTrack(track.title),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    errorMessage != null -> {
                        Text(
                            text = errorMessage ?: strings.unknownError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {
                        Text(
                            text = strings.savedAsFile(savedFileName.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (libraryUpdated) {
                            Text(
                                text = strings.libraryUpdated,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isExporting) {
                TextButton(onClick = onDismiss) {
                    Text(if (errorMessage != null) strings.close else strings.ok)
                }
            }
        },
        dismissButton = {
            if (isExporting) {
                TextButton(onClick = onDismiss) {
                    Text(strings.cancel)
                }
            }
        },
    )
}
