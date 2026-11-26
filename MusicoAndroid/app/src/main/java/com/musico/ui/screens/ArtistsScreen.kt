package com.musico.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerUiState
import com.musico.i18n.LocalStrings
import com.musico.model.Album
import com.musico.model.PlayQueue
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.ui.components.EmptyState
import com.musico.ui.components.PlayShuffleButtons
import com.musico.ui.components.TrackActionState
import com.musico.ui.components.TrackMenuItems
import com.musico.ui.components.TrackRow
import com.musico.ui.theme.musicoListItemColors
import com.musico.ui.theme.musicoScaffoldContainerColor
import com.musico.ui.theme.musicoTopAppBarColors
import kotlin.random.Random

@Composable
fun ArtistsScreen(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    if (libraryState.artists.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Rounded.Mic,
                title = strings.noArtistsYetTitle,
                description = strings.noArtistsYetDesc
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(libraryState.artists, key = { it }) { artist ->
            val artistTrackIds = remember(libraryState.tracks, artist) {
                libraryStore.tracksByArtist(artist).map { it.id }
            }
            var menuExpanded by remember { mutableStateOf(false) }

            Column(modifier = Modifier.animateItem()) {
                ListItem(
                    modifier = Modifier.clickable { onArtistClick(artist) },
                    colors = musicoListItemColors(),
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    headlineContent = {
                        Text(
                            text = strings.displayArtist(artist),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        Text(strings.songCount(artistTrackIds.size))
                    },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = strings.artistOptions)
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                TrackMenuItems(
                                    ids = artistTrackIds,
                                    queues = queues,
                                    activeQueueId = activeQueue.id,
                                    actionState = trackActions,
                                    onDismiss = { menuExpanded = false }
                                )
                            }
                        }
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artist: String,
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onAlbumClick: (Album) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val tracks = remember(libraryState.tracks, artist) {
        libraryStore.tracksByArtist(artist)
    }
    val trackIds = remember(tracks) { tracks.map { it.id } }
    val albums = remember(libraryState.albums, artist) {
        libraryStore.albumsByArtist(artist)
    }
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        containerColor = musicoScaffoldContainerColor(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = musicoTopAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = strings.back)
                    }
                },
                title = {
                    Text(
                        text = strings.displayArtist(artist),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = strings.artistOptions)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            TrackMenuItems(
                                ids = trackIds,
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
            val artistQueueName = "Artist: $artist"
            item(key = "play_shuffle") {
                PlayShuffleButtons(
                    enabled = trackIds.isNotEmpty(),
                    onPlay = {
                        trackActions.playerController.playInNamedQueue(
                            name = artistQueueName,
                            ids = trackIds,
                            startAt = 0,
                            shuffle = false,
                        )
                    },
                    onShuffle = {
                        trackActions.playerController.playInNamedQueue(
                            name = artistQueueName,
                            ids = trackIds,
                            startAt = Random.nextInt(trackIds.size.coerceAtLeast(1)),
                            shuffle = true,
                        )
                    }
                )
            }

            if (albums.isNotEmpty()) {
                item(key = "albums_header") {
                    Text(
                        text = strings.sectionAlbums,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                item(key = "albums_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(albums, key = { it.id }) { album ->
                            AlbumGridCard(
                                album = album,
                                artworkCache = libraryStore.artwork,
                                queues = queues,
                                activeQueue = activeQueue,
                                trackActions = trackActions,
                                onClick = { onAlbumClick(album) },
                                modifier = Modifier.width(140.dp)
                            )
                        }
                    }
                }
            }

            item(key = "songs_header") {
                Text(
                    text = strings.sectionSongs,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                TrackRow(
                    track = track,
                    artworkCache = libraryStore.artwork,
                    isCurrent = playerState.currentTrack?.id == track.id,
                    isPlaying = playerState.isPlaying,
                    isAvailable = libraryStore.isAvailable(track),
                    subtitle = strings.displayAlbum(track.album),
                    onClick = {
                        trackActions.playerController.playInNamedQueue(
                            name = artistQueueName,
                            ids = trackIds,
                            startAt = index,
                            shuffle = false,
                        )
                    },
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
