package com.musico.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerUiState
import com.musico.i18n.AppStrings
import com.musico.i18n.LocalStrings
import com.musico.model.PlayQueue
import com.musico.model.SearchField
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.ui.components.EmptyState
import com.musico.ui.components.PlayShuffleButtons
import com.musico.ui.components.TrackActionState
import com.musico.ui.components.TrackMenuItems
import com.musico.ui.components.TrackRow
import kotlinx.coroutines.launch
import kotlin.random.Random

data class SearchRequest(
    val query: String,
    val field: SearchField,
    val token: Long,
)

enum class SongSort {
    TITLE,
    ARTIST,
    ALBUM,
    DATE_ADDED;

    fun label(strings: AppStrings): String = when (this) {
        TITLE -> strings.sortOptionTitle
        ARTIST -> strings.sortOptionArtist
        ALBUM -> strings.sortOptionAlbum
        DATE_ADDED -> strings.sortOptionRecentlyAdded
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsScreen(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    pendingSearchRequest: SearchRequest? = null,
    onConsumeSearchRequest: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var searchField by rememberSaveable { mutableStateOf(SearchField.KEYWORD) }
    var sort by rememberSaveable { mutableStateOf(SongSort.TITLE) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(pendingSearchRequest?.token) {
        if (pendingSearchRequest != null) {
            query = pendingSearchRequest.query
            searchField = pendingSearchRequest.field
            onConsumeSearchRequest()
        }
    }

    val songs = remember(libraryState.tracks, query, searchField, sort) {
        if (query.isNotBlank()) {
            libraryStore.search(query, searchField)
        } else {
            when (sort) {
                SongSort.TITLE -> libraryState.tracks
                SongSort.ARTIST -> libraryState.tracks.sortedWith(
                    compareBy<com.musico.model.Track, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
                SongSort.ALBUM -> libraryState.tracks.sortedWith(
                    compareBy<com.musico.model.Track, String>(String.CASE_INSENSITIVE_ORDER) { it.album }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
                SongSort.DATE_ADDED -> libraryState.tracks.sortedByDescending { it.dateAddedEpochMs }
            }
        }
    }
    val songIds = remember(songs) { songs.map { it.id } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    searchField = SearchField.KEYWORD
                },
                placeholder = { Text(strings.searchPlaceholder) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            searchField = SearchField.KEYWORD
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = strings.clearSearch)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { sortMenuExpanded = true }) {
                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = strings.sortSongs)
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { sortMenuExpanded = false }
                ) {
                    SongSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label(strings)) },
                            trailingIcon = {
                                if (sort == option) {
                                    Icon(Icons.Rounded.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                sort = option
                                sortMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        if (query.isNotBlank() && searchField != SearchField.KEYWORD) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InputChip(
                    selected = true,
                    onClick = { searchField = SearchField.KEYWORD },
                    label = { Text("${strings.searchFieldLabel(searchField)}: ${query.trim()}") },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = strings.resetFieldFilter,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = libraryState.isScanning,
            onRefresh = { scope.launch { libraryStore.rescan() } },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (libraryState.tracks.isEmpty()) {
                    item(key = "empty_library") {
                        EmptyState(
                            icon = Icons.Rounded.MusicNote,
                            title = strings.noSongsYetTitle,
                            description = strings.noSongsYetDesc
                        )
                    }
                } else if (songs.isEmpty()) {
                    item(key = "empty_search") {
                        EmptyState(
                            icon = Icons.Rounded.SearchOff,
                            title = strings.noSearchResultsTitle(query),
                            description = strings.noSearchResultsDesc
                        )
                    }
                } else {
                    if (query.isBlank()) {
                        item(key = "play_shuffle") {
                            PlayShuffleButtons(
                                enabled = songIds.isNotEmpty(),
                                onPlay = {
                                    if (activeQueue.shuffle) trackActions.playerController.toggleShuffle()
                                    trackActions.playerController.play(songIds, startAt = 0)
                                },
                                onShuffle = {
                                    if (!activeQueue.shuffle) trackActions.playerController.toggleShuffle()
                                    trackActions.playerController.play(
                                        songIds,
                                        startAt = Random.nextInt(songIds.size.coerceAtLeast(1))
                                    )
                                }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    itemsIndexed(songs, key = { _, t -> t.id }) { index, track ->
                        TrackRow(
                            track = track,
                            artworkCache = libraryStore.artwork,
                            isCurrent = playerState.currentTrack?.id == track.id,
                            isPlaying = playerState.isPlaying,
                            isAvailable = libraryStore.isAvailable(track),
                            subtitle = if (sort == SongSort.ALBUM) {
                                "${strings.displayArtist(track.artist)} — ${strings.displayAlbum(track.album)}"
                            } else {
                                strings.displayArtist(track.artist)
                            },
                            onClick = {
                                if (query.isNotBlank()) {
                                    trackActions.playerController.playInNewQueue(
                                        ids = songIds,
                                        startAt = index,
                                        name = "${strings.searchFieldLabel(searchField)}: ${query.trim()}",
                                    )
                                } else {
                                    trackActions.playerController.play(songIds, startAt = index)
                                }
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
    }
}
