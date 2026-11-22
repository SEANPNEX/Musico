package com.musico.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musico.audio.PlayerUiState
import com.musico.i18n.AppStrings
import com.musico.i18n.LanguageStore
import com.musico.i18n.LocalStrings
import com.musico.model.Album
import com.musico.model.PlayQueue
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.store.PlayHistoryUiState
import com.musico.ui.components.LanguageDialog
import com.musico.ui.components.TrackActionState
import com.musico.ui.theme.MusicoMotion

enum class LibrarySection {
    SONGS,
    ALBUMS,
    ARTISTS,
    PLAYLISTS;

    fun label(strings: AppStrings): String = when (this) {
        SONGS -> strings.sectionSongs
        ALBUMS -> strings.sectionAlbums
        ARTISTS -> strings.sectionArtists
        PLAYLISTS -> strings.sectionPlaylists
    }
}

sealed interface LibraryDetail {
    data class AlbumDetail(val album: Album) : LibraryDetail
    data class ArtistDetail(val artist: String) : LibraryDetail
    data class PlaylistDetail(val ref: PlaylistRef) : LibraryDetail
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    libraryStore: LibraryStore,
    libraryState: LibraryUiState,
    historyState: PlayHistoryUiState,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    languageStore: LanguageStore? = null,
    pendingSearchRequest: SearchRequest? = null,
    onConsumeSearchRequest: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    var showLanguageDialog by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(LibrarySection.SONGS) }
    val detailStack = remember { mutableStateListOf<LibraryDetail>() }

    LaunchedEffect(pendingSearchRequest?.token) {
        if (pendingSearchRequest != null) {
            detailStack.clear()
            section = LibrarySection.SONGS
        }
    }

    BackHandler(enabled = detailStack.isNotEmpty()) {
        detailStack.removeAt(detailStack.lastIndex)
    }

    val stackSnapshot = detailStack.toList()

    AnimatedContent(
        targetState = stackSnapshot,
        transitionSpec = {
            val forward = targetState.size >= initialState.size
            MusicoMotion.sharedAxisXTransform(forward = forward)
        },
        label = "library_drill_in",
        modifier = modifier
    ) { stack ->
        val topDetail = stack.lastOrNull()
        if (topDetail != null) {
            when (topDetail) {
                is LibraryDetail.AlbumDetail -> {
                    AlbumDetailScreen(
                        albumId = topDetail.album.id,
                        fallbackAlbum = topDetail.album,
                        libraryStore = libraryStore,
                        libraryState = libraryState,
                        playerState = playerState,
                        queues = queues,
                        activeQueue = activeQueue,
                        trackActions = trackActions,
                        onBack = { detailStack.removeAt(detailStack.lastIndex) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is LibraryDetail.ArtistDetail -> {
                    ArtistDetailScreen(
                        artist = topDetail.artist,
                        libraryStore = libraryStore,
                        libraryState = libraryState,
                        playerState = playerState,
                        queues = queues,
                        activeQueue = activeQueue,
                        trackActions = trackActions,
                        onAlbumClick = { album -> detailStack.add(LibraryDetail.AlbumDetail(album)) },
                        onBack = { detailStack.removeAt(detailStack.lastIndex) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is LibraryDetail.PlaylistDetail -> {
                    PlaylistDetailScreen(
                        ref = topDetail.ref,
                        libraryStore = libraryStore,
                        libraryState = libraryState,
                        historyState = historyState,
                        playerState = playerState,
                        queues = queues,
                        activeQueue = activeQueue,
                        trackActions = trackActions,
                        onBack = { detailStack.removeAt(detailStack.lastIndex) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(0),
                topBar = {
                    TopAppBar(
                        title = { Text(strings.libraryTitle) },
                        actions = {
                            if (languageStore != null) {
                                IconButton(onClick = { showLanguageDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Language,
                                        contentDescription = strings.languageTitle,
                                    )
                                }
                            }
                        }
                    )
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        LibrarySection.entries.forEachIndexed { index, item ->
                            SegmentedButton(
                                selected = section == item,
                                onClick = { section = item },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = LibrarySection.entries.size
                                ),
                                label = {
                                    Text(
                                        text = item.label(strings),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }

                    AnimatedContent(
                        targetState = section,
                        transitionSpec = {
                            MusicoMotion.sharedAxisXTransform(
                                forward = targetState.ordinal >= initialState.ordinal
                            )
                        },
                        label = "library_section_switch",
                        modifier = Modifier.fillMaxSize()
                    ) { currentSection ->
                        when (currentSection) {
                            LibrarySection.SONGS -> SongsScreen(
                                libraryStore = libraryStore,
                                libraryState = libraryState,
                                playerState = playerState,
                                queues = queues,
                                activeQueue = activeQueue,
                                trackActions = trackActions,
                                pendingSearchRequest = pendingSearchRequest,
                                onConsumeSearchRequest = onConsumeSearchRequest,
                                modifier = Modifier.fillMaxSize()
                            )
                            LibrarySection.ALBUMS -> AlbumsScreen(
                                libraryState = libraryState,
                                artworkCache = libraryStore.artwork,
                                queues = queues,
                                activeQueue = activeQueue,
                                trackActions = trackActions,
                                onAlbumClick = { album -> detailStack.add(LibraryDetail.AlbumDetail(album)) },
                                modifier = Modifier.fillMaxSize()
                            )
                            LibrarySection.ARTISTS -> ArtistsScreen(
                                libraryStore = libraryStore,
                                libraryState = libraryState,
                                queues = queues,
                                activeQueue = activeQueue,
                                trackActions = trackActions,
                                onArtistClick = { artist -> detailStack.add(LibraryDetail.ArtistDetail(artist)) },
                                modifier = Modifier.fillMaxSize()
                            )
                            LibrarySection.PLAYLISTS -> PlaylistsScreen(
                                libraryStore = libraryStore,
                                libraryState = libraryState,
                                queues = queues,
                                activeQueue = activeQueue,
                                trackActions = trackActions,
                                onPlaylistClick = { ref -> detailStack.add(LibraryDetail.PlaylistDetail(ref)) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }

    if (showLanguageDialog && languageStore != null) {
        val currentLanguage by languageStore.language.collectAsStateWithLifecycle()
        LanguageDialog(
            current = currentLanguage,
            onSelect = { languageStore.setLanguage(it) },
            onDismiss = { showLanguageDialog = false },
        )
    }
}
