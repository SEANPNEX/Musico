package com.musico.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerUiState
import com.musico.model.PlayQueue
import com.musico.model.Playlist
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.ui.components.ConfirmDialog
import com.musico.ui.components.EmptyState
import com.musico.ui.components.PlayShuffleButtons
import com.musico.ui.components.TextInputDialog
import com.musico.ui.components.TrackActionState
import com.musico.ui.components.TrackMenuItems
import com.musico.ui.components.TrackRow
import kotlin.random.Random

sealed interface PlaylistRef {
    data object Favourites : PlaylistRef
    data class User(val playlistId: String) : PlaylistRef
}

@Composable
fun PlaylistsScreen(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onPlaylistClick: (PlaylistRef) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var renamingPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<Playlist?>(null) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "New Playlist")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp)
        ) {
            item(key = "favourites") {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    ListItem(
                        modifier = Modifier.clickable { onPlaylistClick(PlaylistRef.Favourites) },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        headlineContent = { Text("Favourites") },
                        supportingContent = { Text("${libraryState.favourites.size} songs") }
                    )
                }
            }

            items(libraryState.playlists, key = { it.id }) { playlist ->
                var menuExpanded by remember { mutableStateOf(false) }

                ListItem(
                    modifier = Modifier.clickable { onPlaylistClick(PlaylistRef.User(playlist.id)) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    headlineContent = {
                        Text(
                            text = playlist.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = { Text("${playlist.trackIds.size} songs") },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Playlist options")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename…") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                    onClick = {
                                        menuExpanded = false
                                        renamingPlaylist = playlist
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Rounded.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        deletingPlaylist = playlist
                                    }
                                )
                                if (playlist.trackIds.isNotEmpty()) {
                                    HorizontalDivider()
                                    TrackMenuItems(
                                        ids = playlist.trackIds,
                                        queues = queues,
                                        activeQueueId = activeQueue.id,
                                        actionState = trackActions,
                                        onDismiss = { menuExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }

    if (showCreateDialog) {
        TextInputDialog(
            title = "New Playlist",
            label = "Name",
            confirmLabel = "Create",
            allowBlankWithDefault = "New Playlist",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name -> libraryStore.createPlaylist(name) }
        )
    }

    val renaming = renamingPlaylist
    if (renaming != null) {
        TextInputDialog(
            title = "Rename Playlist",
            label = "Name",
            initialValue = renaming.name,
            confirmLabel = "Rename",
            onDismiss = { renamingPlaylist = null },
            onConfirm = { newName -> libraryStore.renamePlaylist(renaming.id, newName) }
        )
    }

    val deleting = deletingPlaylist
    if (deleting != null) {
        ConfirmDialog(
            title = "Delete “${deleting.name}”?",
            message = "This playlist will be removed. Your songs will stay in your library.",
            confirmLabel = "Delete",
            onDismiss = { deletingPlaylist = null },
            onConfirm = { libraryStore.deletePlaylist(deleting.id) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    ref: PlaylistRef,
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf(false) }

    val playlist = remember(libraryState.playlists, ref) {
        when (ref) {
            PlaylistRef.Favourites -> null
            is PlaylistRef.User -> libraryState.playlists.firstOrNull { it.id == ref.playlistId }
        }
    }

    val title = when (ref) {
        PlaylistRef.Favourites -> "Favourites"
        is PlaylistRef.User -> playlist?.name ?: "Playlist"
    }

    val tracks = remember(libraryState.tracks, libraryState.favourites, playlist, ref) {
        when (ref) {
            PlaylistRef.Favourites -> libraryState.tracks.filter { it.id in libraryState.favourites }
            is PlaylistRef.User -> libraryStore.tracks(playlist?.trackIds.orEmpty())
        }
    }
    val trackIds = remember(tracks) { tracks.map { it.id } }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    if (ref is PlaylistRef.User && tracks.isNotEmpty()) {
                        TextButton(onClick = { editing = !editing }) {
                            Text(if (editing) "Done" else "Edit")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (tracks.isEmpty()) {
                item(key = "empty_playlist") {
                    EmptyState(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        title = "Nothing Here Yet",
                        description = "Open the menu on a song, album or folder to add it."
                    )
                }
            } else {
                item(key = "play_shuffle") {
                    PlayShuffleButtons(
                        enabled = trackIds.isNotEmpty(),
                        onPlay = {
                            if (activeQueue.shuffle) trackActions.playerController.toggleShuffle()
                            trackActions.playerController.play(trackIds, startAt = 0)
                        },
                        onShuffle = {
                            if (!activeQueue.shuffle) trackActions.playerController.toggleShuffle()
                            trackActions.playerController.play(
                                trackIds,
                                startAt = Random.nextInt(trackIds.size.coerceAtLeast(1))
                            )
                        }
                    )
                }

                itemsIndexed(
                    items = tracks,
                    key = { index, track -> "${index}_${track.id}" }
                ) { index, track ->
                    TrackRow(
                        track = track,
                        artworkCache = libraryStore.artwork,
                        isCurrent = playerState.currentTrack?.id == track.id,
                        isPlaying = playerState.isPlaying,
                        isAvailable = libraryStore.isAvailable(track),
                        onClick = { trackActions.playerController.play(trackIds, startAt = index) },
                        trailingActions = if (ref is PlaylistRef.User && editing) {
                            {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            libraryStore.movePlaylistTrack(ref.playlistId, index, index - 1)
                                        },
                                        enabled = index > 0
                                    ) {
                                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up")
                                    }
                                    IconButton(
                                        onClick = {
                                            libraryStore.movePlaylistTrack(ref.playlistId, index, index + 1)
                                        },
                                        enabled = index < tracks.lastIndex
                                    ) {
                                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down")
                                    }
                                    IconButton(
                                        onClick = {
                                            libraryStore.removeFromPlaylist(ref.playlistId, index)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = "Remove from playlist",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        } else null,
                        menuContent = if (!editing) {
                            { onDismiss ->
                                TrackMenuItems(
                                    ids = listOf(track.id),
                                    queues = queues,
                                    activeQueueId = activeQueue.id,
                                    actionState = trackActions,
                                    onDismiss = onDismiss,
                                    favouriteId = track.id,
                                    isFavourite = track.id in libraryState.favourites
                                )
                                if (ref is PlaylistRef.User) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("Remove from Playlist") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            onDismiss()
                                            libraryStore.removeFromPlaylist(ref.playlistId, index)
                                        }
                                    )
                                }
                            }
                        } else null
                    )
                }
            }
        }
    }
}
