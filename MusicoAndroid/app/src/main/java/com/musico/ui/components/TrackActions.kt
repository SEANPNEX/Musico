package com.musico.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AddToQueue
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.musico.audio.PlayerController
import com.musico.model.PlayQueue
import com.musico.model.Playlist
import com.musico.store.LibraryStore

@Stable
class TrackActionState(
    val libraryStore: LibraryStore,
    val playerController: PlayerController
) {
    var queuePickerIds: List<String>? by mutableStateOf(null)
    var playlistPickerIds: List<String>? by mutableStateOf(null)
    var newPlaylistIds: List<String>? by mutableStateOf(null)
}

@Composable
fun rememberTrackActionState(
    libraryStore: LibraryStore,
    playerController: PlayerController
): TrackActionState {
    return remember(libraryStore, playerController) {
        TrackActionState(libraryStore, playerController)
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
}
