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
import com.musico.model.PlayQueue
import com.musico.model.Playlist
import com.musico.model.Track
import com.musico.store.LibraryStore
import kotlinx.coroutines.CancellationException

@Stable
class TrackActionState(
    val libraryStore: LibraryStore,
    val playerController: PlayerController,
    val cloudExporter: CloudExporter,
    val onPickFolder: ((Uri) -> Unit) -> Unit,
) {
    var queuePickerIds: List<String>? by mutableStateOf(null)
    var playlistPickerIds: List<String>? by mutableStateOf(null)
    var newPlaylistIds: List<String>? by mutableStateOf(null)
    var savingCloudTrack: Pair<Track, Uri>? by mutableStateOf(null)
}

@Composable
fun rememberTrackActionState(
    libraryStore: LibraryStore,
    playerController: PlayerController,
    cloudExporter: CloudExporter,
    onPickFolder: ((Uri) -> Unit) -> Unit,
): TrackActionState {
    return remember(libraryStore, playerController, cloudExporter, onPickFolder) {
        TrackActionState(libraryStore, playerController, cloudExporter, onPickFolder)
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
    isFavourite: Boolean = false
) {
    DropdownMenuItem(
        text = { Text("Play Next") },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null) },
        enabled = ids.isNotEmpty(),
        onClick = {
            actionState.playerController.playNext(ids)
            onDismiss()
        }
    )
    DropdownMenuItem(
        text = { Text("Add to Queue") },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
        enabled = ids.isNotEmpty(),
        onClick = {
            actionState.playerController.addToQueue(ids, queueId = null)
            onDismiss()
        }
    )
    if (queues.size > 1) {
        DropdownMenuItem(
            text = { Text("Add to Another Queue…") },
            leadingIcon = { Icon(Icons.Rounded.AddToQueue, contentDescription = null) },
            enabled = ids.isNotEmpty(),
            onClick = {
                actionState.queuePickerIds = ids
                onDismiss()
            }
        )
    }
    DropdownMenuItem(
        text = { Text("Play in New Queue") },
        leadingIcon = { Icon(Icons.Rounded.Queue, contentDescription = null) },
        enabled = ids.isNotEmpty(),
        onClick = {
            actionState.playerController.playInNewQueue(ids)
            onDismiss()
        }
    )
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text("Add to Playlist…") },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
        enabled = ids.isNotEmpty(),
        onClick = {
            actionState.playlistPickerIds = ids
            onDismiss()
        }
    )
    if (favouriteId != null) {
        DropdownMenuItem(
            text = { Text(if (isFavourite) "Remove from Favourites" else "Add to Favourites") },
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
            text = { Text("Save to Local Folder…") },
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
    playlists: List<Playlist>
) {
    val queueIds = state.queuePickerIds
    if (queueIds != null) {
        val otherQueues = queues.filter { it.id != activeQueueId }
        OptionPickerDialog(
            title = "Add to Queue",
            options = otherQueues.map { q ->
                PickerOption(
                    id = q.id,
                    title = q.name,
                    subtitle = "${q.trackIds.size} songs"
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
            title = "Add to Playlist",
            options = playlists.map { pl ->
                PickerOption(
                    id = pl.id,
                    title = pl.name,
                    subtitle = "${pl.trackIds.size} songs"
                )
            },
            extraActionLabel = "New Playlist…",
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
            title = "New Playlist",
            label = "Name",
            supportingText = "Enter a name for the playlist.",
            confirmLabel = "Create",
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
}

@Composable
private fun SaveSingleCloudTrackDialog(
    track: Track,
    targetUri: Uri,
    libraryStore: LibraryStore,
    cloudExporter: CloudExporter,
    onDismiss: () -> Unit,
) {
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
            errorMessage = err.message ?: "Could not save song."
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
                    isExporting -> "Saving Song"
                    errorMessage != null -> "Save Failed"
                    else -> "Song Saved"
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
                            text = "Downloading ${track.title}…",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    errorMessage != null -> {
                        Text(
                            text = errorMessage ?: "Unknown error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> {
                        Text(
                            text = "Saved as “${savedFileName.orEmpty()}”.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (libraryUpdated) {
                            Text(
                                text = "Library updated.",
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
                    Text(if (errorMessage != null) "Close" else "OK")
                }
            }
        },
        dismissButton = {
            if (isExporting) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
