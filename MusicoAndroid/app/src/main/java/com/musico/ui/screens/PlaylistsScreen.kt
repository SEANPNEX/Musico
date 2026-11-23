package com.musico.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.musico.audio.PlayerUiState
import com.musico.i18n.LocalStrings
import com.musico.model.PlayQueue
import com.musico.model.Playlist
import com.musico.model.TrackSortOrder
import com.musico.model.sortTracks
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.store.PlayHistoryUiState
import com.musico.ui.components.ConfirmDialog
import com.musico.ui.components.EmptyState
import com.musico.ui.components.PlayShuffleButtons
import com.musico.ui.components.SortOrderMenuButton
import com.musico.ui.components.TextInputDialog
import com.musico.ui.components.TrackActionState
import com.musico.ui.components.TrackMenuItems
import com.musico.ui.components.TrackRow
import com.musico.ui.components.dragHandle
import com.musico.ui.components.rememberDragReorderState
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
    val strings = LocalStrings.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var renamingPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<Playlist?>(null) }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Rounded.Add, contentDescription = strings.newPlaylist)
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
                        headlineContent = { Text(strings.favourites) },
                        supportingContent = { Text(strings.songCount(libraryState.favourites.size)) }
                    )
                }
            }

            items(libraryState.playlists, key = { it.id }) { playlist ->
                var menuExpanded by remember { mutableStateOf(false) }

                Column(modifier = Modifier.animateItem()) {
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
                        supportingContent = { Text(strings.songCount(playlist.trackIds.size)) },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Rounded.MoreVert, contentDescription = strings.playlistOptions)
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(strings.renameEllipsis) },
                                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                        onClick = {
                                            menuExpanded = false
                                            renamingPlaylist = playlist
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.delete) },
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
                                        DropdownMenuItem(
                                            text = { Text(strings.play) },
                                            leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                                            onClick = {
                                                menuExpanded = false
                                                trackActions.playerController.playInNamedQueue(
                                                    name = playlist.name,
                                                    ids = playlist.trackIds,
                                                    startAt = 0,
                                                    shuffle = false,
                                                )
                                            }
                                        )
                                        TrackMenuItems(
                                            ids = playlist.trackIds,
                                            queues = queues,
                                            activeQueueId = activeQueue.id,
                                            actionState = trackActions,
                                            onDismiss = { menuExpanded = false },
                                            namedQueueTitle = playlist.name,
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
    }

    if (showCreateDialog) {
        TextInputDialog(
            title = strings.newPlaylist,
            label = strings.nameLabel,
            confirmLabel = strings.create,
            allowBlankWithDefault = strings.newPlaylist,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name -> libraryStore.createPlaylist(name) }
        )
    }

    val renaming = renamingPlaylist
    if (renaming != null) {
        TextInputDialog(
            title = strings.renamePlaylistTitle,
            label = strings.nameLabel,
            initialValue = renaming.name,
            confirmLabel = strings.rename,
            onDismiss = { renamingPlaylist = null },
            onConfirm = { newName -> libraryStore.renamePlaylist(renaming.id, newName) }
        )
    }

    val deleting = deletingPlaylist
    if (deleting != null) {
        ConfirmDialog(
            title = strings.deleteItemConfirmTitle(deleting.name),
            message = strings.deletePlaylistConfirmMessage,
            confirmLabel = strings.delete,
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
    historyState: PlayHistoryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    var editing by remember { mutableStateOf(false) }

    val playlist = remember(libraryState.playlists, ref) {
        when (ref) {
            PlaylistRef.Favourites -> null
            is PlaylistRef.User -> libraryState.playlists.firstOrNull { it.id == ref.playlistId }
        }
    }

    val title = when (ref) {
        PlaylistRef.Favourites -> strings.favourites
        is PlaylistRef.User -> playlist?.name ?: strings.playlistFallbackTitle
    }

    val sortOrder = when (ref) {
        PlaylistRef.Favourites -> libraryState.favouritesSortOrder
        is PlaylistRef.User -> playlist?.sortOrder ?: TrackSortOrder.CUSTOM
    }

    val tracks = remember(libraryState.tracks, libraryState.favourites, playlist, ref, sortOrder, historyState.entries) {
        val playCounts: (String) -> Int = { id -> historyState.entries[id]?.count ?: 0 }
        when (ref) {
            PlaylistRef.Favourites -> {
                val raw = libraryState.tracks.filter { it.id in libraryState.favourites }
                sortTracks(raw, sortOrder, playCounts)
            }
            is PlaylistRef.User -> {
                val raw = libraryStore.tracks(playlist?.trackIds.orEmpty())
                sortTracks(raw, sortOrder, playCounts)
            }
        }
    }
    val trackIds = remember(tracks) { tracks.map { it.id } }

    LaunchedEffect(ref, trackIds) {
        if (trackIds.isNotEmpty()) {
            trackActions.playerController.syncNamedQueue(title, trackIds)
        }
    }

    val listState = rememberLazyListState()
    val dragState = rememberDragReorderState(
        items = tracks,
        listState = listState,
        headerCount = if (tracks.isEmpty()) 0 else 1,
        scopeKey = ref,
        keyOf = { it.id },
        onCommit = { reordered ->
            if (ref is PlaylistRef.User) {
                libraryStore.reorderPlaylistTracks(ref.playlistId, reordered.map { it.value.id })
            }
        },
    )

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.back)
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
                    if (tracks.isNotEmpty()) {
                        SortOrderMenuButton(
                            current = sortOrder,
                            options = if (ref is PlaylistRef.User) {
                                TrackSortOrder.entries
                            } else {
                                TrackSortOrder.entries.filter { it != TrackSortOrder.CUSTOM }
                            },
                            onSelect = { selected ->
                                when (ref) {
                                    PlaylistRef.Favourites -> libraryStore.setFavouritesSortOrder(selected)
                                    is PlaylistRef.User -> libraryStore.setPlaylistSortOrder(ref.playlistId, selected) { id ->
                                        historyState.entries[id]?.count ?: 0
                                    }
                                }
                            },
                        )
                    }
                    if (ref is PlaylistRef.User && tracks.isNotEmpty()) {
                        TextButton(onClick = { editing = !editing }) {
                            Text(if (editing) strings.done else strings.edit)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (tracks.isEmpty()) {
                item(key = "empty_playlist") {
                    EmptyState(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        title = strings.nothingHereYetTitle,
                        description = strings.nothingHereYetDesc
                    )
                }
            } else {
                item(key = "play_shuffle") {
                    PlayShuffleButtons(
                        enabled = trackIds.isNotEmpty(),
                        onPlay = {
                            trackActions.playerController.playInNamedQueue(
                                name = title,
                                ids = trackIds,
                                startAt = 0,
                                shuffle = false,
                            )
                        },
                        onShuffle = {
                            trackActions.playerController.playInNamedQueue(
                                name = title,
                                ids = trackIds,
                                startAt = Random.nextInt(trackIds.size.coerceAtLeast(1)),
                                shuffle = true,
                            )
                        }
                    )
                }

                itemsIndexed(
                    items = dragState.items,
                    key = { _, item -> item.uid }
                ) { index, dragItem ->
                    val track = dragItem.value
                    val isDragged = dragState.activeUid == dragItem.uid
                    val rowBg = if (isDragged) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        Color.Transparent
                    }
                    val rowModifier = Modifier
                        .animateItem(
                            placementSpec = if (dragState.isDragging || dragState.isSettling) {
                                null
                            } else {
                                spring(
                                    stiffness = Spring.StiffnessMediumLow,
                                    visibilityThreshold = IntOffset.VisibilityThreshold,
                                )
                            }
                        )
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = dragState.offsetFor(dragItem.uid) }

                    val removeIndex = if (ref is PlaylistRef.User) {
                        if (sortOrder == TrackSortOrder.CUSTOM) {
                            dragItem.originalIndex
                        } else {
                            playlist?.trackIds?.indexOf(track.id)?.takeIf { it >= 0 } ?: index
                        }
                    } else {
                        index
                    }

                    TrackRow(
                        track = track,
                        artworkCache = libraryStore.artwork,
                        isCurrent = playerState.currentTrack?.id == track.id,
                        isPlaying = playerState.isPlaying,
                        isAvailable = libraryStore.isAvailable(track),
                        backgroundColor = rowBg,
                        onClick = {
                            val currentIds = dragState.items.map { it.value.id }
                            trackActions.playerController.playInNamedQueue(
                                name = title,
                                ids = currentIds.ifEmpty { trackIds },
                                startAt = index,
                                shuffle = false,
                            )
                        },
                        modifier = rowModifier,
                        dragHandleModifier = if (ref is PlaylistRef.User) {
                            Modifier.dragHandle(dragState, dragItem.uid)
                        } else null,
                        trailingActions = if (ref is PlaylistRef.User && editing) {
                            {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            if (sortOrder != TrackSortOrder.CUSTOM) {
                                                libraryStore.reorderPlaylistTracks(ref.playlistId, trackIds)
                                            }
                                            libraryStore.movePlaylistTrack(ref.playlistId, index, index - 1)
                                        },
                                        enabled = index > 0
                                    ) {
                                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = strings.moveUp)
                                    }
                                    IconButton(
                                        onClick = {
                                            if (sortOrder != TrackSortOrder.CUSTOM) {
                                                libraryStore.reorderPlaylistTracks(ref.playlistId, trackIds)
                                            }
                                            libraryStore.movePlaylistTrack(ref.playlistId, index, index + 1)
                                        },
                                        enabled = index < tracks.lastIndex
                                    ) {
                                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = strings.moveDown)
                                    }
                                    IconButton(
                                        onClick = {
                                            libraryStore.removeFromPlaylist(ref.playlistId, removeIndex)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = strings.removeFromPlaylistCd,
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
                                        text = { Text(strings.removeFromPlaylist) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            onDismiss()
                                            libraryStore.removeFromPlaylist(ref.playlistId, removeIndex)
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
