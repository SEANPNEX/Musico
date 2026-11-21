package com.musico.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerUiState
import com.musico.core.ArtworkCache
import com.musico.model.Album
import com.musico.model.PlayQueue
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.ui.components.ArtworkImage
import com.musico.ui.components.EmptyState
import com.musico.ui.components.PlayShuffleButtons
import com.musico.ui.components.TrackActionState
import com.musico.ui.components.TrackMenuItems
import com.musico.ui.components.TrackRow
import com.musico.ui.components.formatTime
import kotlin.random.Random

@Composable
fun AlbumsScreen(
    libraryState: LibraryUiState,
    artworkCache: ArtworkCache,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    if (libraryState.albums.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Rounded.Album,
                title = "No Albums Yet",
                description = "Albums appear once your library has songs."
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 152.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        items(libraryState.albums, key = { it.id }) { album ->
            AlbumGridCard(
                album = album,
                artworkCache = artworkCache,
                queues = queues,
                activeQueue = activeQueue,
                trackActions = trackActions,
                onClick = { onAlbumClick(album) },
                modifier = Modifier.animateItem()
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumGridCard(
    album: Album,
    artworkCache: ArtworkCache,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true }
                )
        ) {
            ArtworkImage(
                artworkKey = album.artworkKey,
                artworkCache = artworkCache,
                cornerRadius = 12.dp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = album.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            TrackMenuItems(
                ids = album.trackIds,
                queues = queues,
                activeQueueId = activeQueue.id,
                actionState = trackActions,
                onDismiss = { menuExpanded = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    fallbackAlbum: Album,
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val album = remember(libraryState.albums, albumId) {
        libraryState.albums.firstOrNull { it.id == albumId } ?: fallbackAlbum
    }
    val tracks = remember(libraryState.tracks, album.trackIds) {
        libraryStore.tracks(album.trackIds)
    }
    val totalDuration = remember(tracks) { tracks.sumOf { it.durationSec } }
    var menuExpanded by remember { mutableStateOf(false) }

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
                title = {},
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Album options")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            TrackMenuItems(
                                ids = album.trackIds,
                                queues = queues,
                                activeQueueId = activeQueue.id,
                                actionState = trackActions,
                                onDismiss = { menuExpanded = false }
                            )
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
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ArtworkImage(
                        artworkKey = album.artworkKey,
                        artworkCache = libraryStore.artwork,
                        size = 210.dp,
                        cornerRadius = 16.dp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = album.title,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = album.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "${tracks.size} songs · ${formatTime(totalDuration)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "play_shuffle") {
                PlayShuffleButtons(
                    enabled = album.trackIds.isNotEmpty(),
                    onPlay = {
                        if (activeQueue.shuffle) trackActions.playerController.toggleShuffle()
                        trackActions.playerController.play(album.trackIds, startAt = 0)
                    },
                    onShuffle = {
                        if (!activeQueue.shuffle) trackActions.playerController.toggleShuffle()
                        trackActions.playerController.play(
                            album.trackIds,
                            startAt = Random.nextInt(album.trackIds.size.coerceAtLeast(1))
                        )
                    }
                )
            }

            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                val subtitle = if (track.trackNumber != null) {
                    val discPrefix = if ((track.discNumber ?: 1) > 1) "Disc ${track.discNumber} · " else ""
                    "$discPrefix${track.trackNumber} · ${track.artist}"
                } else {
                    track.artist
                }
                TrackRow(
                    track = track,
                    artworkCache = libraryStore.artwork,
                    isCurrent = playerState.currentTrack?.id == track.id,
                    isPlaying = playerState.isPlaying,
                    isAvailable = libraryStore.isAvailable(track),
                    subtitle = subtitle,
                    onClick = { trackActions.playerController.play(album.trackIds, startAt = index) },
                    modifier = Modifier.animateItem(),
                    menuContent = { onDismiss ->
                        TrackMenuItems(
                            ids = listOf(track.id),
                            queues = queues,
                            activeQueueId = activeQueue.id,
                            actionState = trackActions,
                            onDismiss = onDismiss,
                            favouriteId = track.id,
                            isFavourite = track.id in libraryState.favourites
                        )
                    }
                )
            }
        }
    }
}
