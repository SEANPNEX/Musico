package com.musico.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musico.audio.PlayerUiState
import com.musico.core.ArtworkCache
import com.musico.extensions.CloudSearchFilterKind
import com.musico.extensions.ExtSearchResultItem
import com.musico.extensions.ExtensionSearchKind
import com.musico.extensions.ExtensionStore
import com.musico.extensions.SearchExtensionDescriptor
import com.musico.i18n.AppStrings
import com.musico.i18n.LocalStrings
import com.musico.model.PlayQueue
import com.musico.model.SearchField
import com.musico.model.Track
import com.musico.store.LibraryStore
import com.musico.store.LibraryUiState
import com.musico.ui.components.ArtworkImage
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
    extensionStore: ExtensionStore? = null,
    pendingSearchRequest: SearchRequest? = null,
    onConsumeSearchRequest: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var query by rememberSaveable { mutableStateOf("") }
    var searchField by rememberSaveable { mutableStateOf(SearchField.KEYWORD) }
    var sort by rememberSaveable { mutableStateOf(SongSort.TITLE) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val searchController = remember(extensionStore, libraryStore, trackActions.playerController, scope) {
        extensionStore?.let { store ->
            CloudSearchController(
                extensionStore = store,
                scope = scope,
                libraryStore = libraryStore,
                playerController = trackActions.playerController,
            )
        }
    }

    val cloudUiState = if (searchController != null) {
        searchController.uiState.collectAsStateWithLifecycle().value
    } else {
        CloudSearchUiState()
    }

    if (extensionStore != null && searchController != null) {
        val installedExtensions by extensionStore.extensions.collectAsStateWithLifecycle()
        LaunchedEffect(installedExtensions) {
            searchController.syncAvailableSources(installedExtensions)
        }
    }

    var previewTarget by remember {
        mutableStateOf<Pair<SearchExtensionDescriptor, ExtSearchResultItem.CollectionItem>?>(null)
    }

    LaunchedEffect(cloudUiState.availableSources, cloudUiState.selectedSource) {
        val target = previewTarget
        if (target != null && cloudUiState.availableSources.none { it.id == target.first.id }) {
            previewTarget = null
        }
    }

    LaunchedEffect(pendingSearchRequest?.token) {
        if (pendingSearchRequest != null) {
            query = pendingSearchRequest.query
            searchField = pendingSearchRequest.field
            searchController?.onQueryChanged(pendingSearchRequest.query)
            searchController?.resetToLocal()
            previewTarget = null
            onConsumeSearchRequest()
        }
    }

    val hasSearchExtensions = cloudUiState.availableSources.isNotEmpty()
    val activeCloudExt = cloudUiState.activeExtension
    val isCloudMode = hasSearchExtensions && cloudUiState.isCloudMode && activeCloudExt != null

    val songs = remember(libraryState.tracks, query, searchField, sort) {
        if (query.isNotBlank()) {
            libraryStore.search(query, searchField)
        } else {
            when (sort) {
                SongSort.TITLE -> libraryState.tracks
                SongSort.ARTIST -> libraryState.tracks.sortedWith(
                    compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
                SongSort.ALBUM -> libraryState.tracks.sortedWith(
                    compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.album }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
                )
                SongSort.DATE_ADDED -> libraryState.tracks.sortedByDescending { it.dateAddedEpochMs }
            }
        }
    }
    val songIds = remember(songs) { songs.map { it.id } }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = query,
                    onValueChange = { next ->
                        query = next
                        searchField = SearchField.KEYWORD
                        searchController?.onQueryChanged(next)
                    },
                    placeholder = {
                        Text(
                            if (isCloudMode) {
                                strings.cloudSearchPlaceholder(activeCloudExt!!.badgeLabel)
                            } else {
                                strings.searchPlaceholder
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                searchField = SearchField.KEYWORD
                                searchController?.clearQuery()
                            }) {
                                Icon(Icons.Rounded.Close, contentDescription = strings.clearSearch)
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (isCloudMode) {
                                searchController?.submitSearch()
                            }
                            focusManager.clearFocus()
                        }
                    ),
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

                if (isCloudMode) {
                    Spacer(Modifier.width(8.dp))
                    FilledTonalIconButton(
                        onClick = {
                            searchController?.submitSearch()
                            focusManager.clearFocus()
                        },
                        enabled = query.isNotBlank() && !cloudUiState.isSearching,
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = strings.searchAction)
                    }
                } else {
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
            }

            if (hasSearchExtensions && searchController != null) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "source_local") {
                        val localSelected = !isCloudMode
                        FilterChip(
                            selected = localSelected,
                            onClick = { searchController.selectSource(CloudSearchSource.Local) },
                            label = { Text(strings.searchSourceLocal) },
                            leadingIcon = if (localSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else null,
                        )
                    }
                    items(cloudUiState.availableSources, key = { "source_${it.id}" }) { ext ->
                        val extSelected = isCloudMode && activeCloudExt?.id == ext.id
                        FilterChip(
                            selected = extSelected,
                            onClick = {
                                searchController.onQueryChanged(query)
                                searchController.selectSource(CloudSearchSource.Extension(ext.id))
                            },
                            label = { Text(ext.badgeLabel) },
                            leadingIcon = if (extSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else null,
                        )
                    }
                }

                if (isCloudMode && cloudUiState.availableKindFilters.size > 1) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(cloudUiState.availableKindFilters, key = { "kind_${it.wireName}" }) { kind ->
                            val selected = cloudUiState.selectedKind == kind
                            FilterChip(
                                selected = selected,
                                onClick = { searchController.selectKind(kind) },
                                label = { Text(kindFilterLabel(kind, strings)) },
                            )
                        }
                    }
                }
            }

            if (!isCloudMode && query.isNotBlank() && searchField != SearchField.KEYWORD) {
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
                isRefreshing = if (isCloudMode) cloudUiState.isSearching else libraryState.isScanning,
                onRefresh = {
                    if (isCloudMode) {
                        searchController?.refresh()
                    } else {
                        scope.launch { libraryStore.rescan() }
                    }
                },
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    if (isCloudMode) {
                        val cloudError = cloudUiState.error
                        if (cloudError != null) {
                            item(key = "cloud_error_banner") {
                                ElevatedCard(
                                    colors = CardDefaults.elevatedCardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = strings.cloudSearchErrorTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Text(
                                            text = formatCloudUiError(cloudError, strings),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                        ) {
                                            TextButton(onClick = { searchController?.retry() }) {
                                                Text(strings.retry)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (cloudUiState.isSearching && cloudUiState.items.isEmpty()) {
                            item(key = "cloud_loading") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        } else if (!cloudUiState.hasSubmitted && cloudUiState.items.isEmpty() && cloudError == null) {
                            item(key = "cloud_idle") {
                                EmptyState(
                                    icon = Icons.Rounded.Search,
                                    title = strings.cloudSearchIdleTitle(activeCloudExt.badgeLabel),
                                    description = strings.cloudSearchIdleDesc,
                                )
                            }
                        } else if (cloudUiState.hasSubmitted && !cloudUiState.isSearching && cloudUiState.items.isEmpty() && cloudError == null) {
                            item(key = "cloud_empty") {
                                EmptyState(
                                    icon = Icons.Rounded.SearchOff,
                                    title = strings.noSearchResultsTitle(cloudUiState.submittedQuery ?: query),
                                    description = strings.noCloudSearchResultsDesc,
                                )
                            }
                        } else if (cloudUiState.items.isNotEmpty()) {
                            val queueTitle = "Search (${activeCloudExt.badgeLabel}): ${cloudUiState.submittedQuery ?: query.trim()}"
                            items(
                                items = cloudUiState.items,
                                key = { item -> "${activeCloudExt.id}:${item.kind.wireName}:${item.id}" },
                            ) { item ->
                                when (item) {
                                    is ExtSearchResultItem.TrackItem -> {
                                        val ephemeralTrack = remember(activeCloudExt.id, item) {
                                            item.toEphemeralTrack(activeCloudExt.id)
                                        }
                                        val subtitle = if (ephemeralTrack.album.isNotBlank()) {
                                            "${strings.displayArtist(ephemeralTrack.artist)} — ${ephemeralTrack.album}"
                                        } else {
                                            strings.displayArtist(ephemeralTrack.artist)
                                        }
                                        TrackRow(
                                            track = ephemeralTrack,
                                            artworkCache = libraryStore.artwork,
                                            isCurrent = playerState.currentTrack?.id == ephemeralTrack.id,
                                            isPlaying = playerState.isPlaying,
                                            isAvailable = libraryStore.isAvailable(ephemeralTrack),
                                            subtitle = subtitle,
                                            onClick = { searchController?.playTrackResult(item) },
                                            menuContent = { onMenuDismiss ->
                                                remember(ephemeralTrack) {
                                                    libraryStore.registerEphemeralTrack(ephemeralTrack)
                                                    true
                                                }
                                                TrackMenuItems(
                                                    ids = listOf(ephemeralTrack.id),
                                                    queues = queues,
                                                    activeQueueId = activeQueue.id,
                                                    actionState = trackActions,
                                                    onDismiss = onMenuDismiss,
                                                    namedQueueTitle = queueTitle,
                                                )
                                            },
                                        )
                                    }
                                    is ExtSearchResultItem.CollectionItem -> {
                                        CloudCollectionResultRow(
                                            item = item,
                                            artworkCache = libraryStore.artwork,
                                            canAddAsCloudFolder = activeCloudExt.supportsCollection && !item.sourceUrl.isNullOrBlank(),
                                            strings = strings,
                                            onClick = {
                                                previewTarget = activeCloudExt to item
                                            },
                                            onOpenPreview = {
                                                previewTarget = activeCloudExt to item
                                            },
                                            onAddAsCloudFolder = {
                                                val sourceUrl = item.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
                                                if (sourceUrl != null) {
                                                    scope.launch {
                                                        val res = libraryStore.addCloudRoot(
                                                            sourceUrl = sourceUrl,
                                                            cookie = null,
                                                            providerIdHint = activeCloudExt.providerId,
                                                        )
                                                        res.onSuccess {
                                                            snackbarHostState.showSnackbar(strings.addedFolderNotice(item.title))
                                                        }.onFailure { err ->
                                                            snackbarHostState.showSnackbar(formatCloudSearchError(err, strings))
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }

                            if (cloudUiState.canLoadMore || cloudUiState.isLoadingMore) {
                                item(key = "cloud_load_more") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        OutlinedButton(
                                            enabled = !cloudUiState.isLoadingMore,
                                            onClick = { searchController?.loadMore() },
                                        ) {
                                            if (cloudUiState.isLoadingMore) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(strings.loadingMore)
                                            } else {
                                                Text(strings.loadMore)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
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
                                            trackActions.playerController.playInNamedQueue(
                                                name = "Songs",
                                                ids = songIds,
                                                startAt = 0,
                                                shuffle = false,
                                            )
                                        },
                                        onShuffle = {
                                            trackActions.playerController.playInNamedQueue(
                                                name = "Songs",
                                                ids = songIds,
                                                startAt = Random.nextInt(songIds.size.coerceAtLeast(1)),
                                                shuffle = true,
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
                                            trackActions.playerController.playInNamedQueue(
                                                name = "Songs",
                                                ids = songIds,
                                                startAt = index,
                                                shuffle = false,
                                            )
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    val currentPreview = previewTarget
    if (currentPreview != null && extensionStore != null) {
        CloudCollectionPreviewSheet(
            extension = currentPreview.first,
            collectionItem = currentPreview.second,
            extensionStore = extensionStore,
            libraryStore = libraryStore,
            playerState = playerState,
            queues = queues,
            activeQueue = activeQueue,
            trackActions = trackActions,
            onAddedAsCloudFolder = { folderTitle ->
                scope.launch {
                    snackbarHostState.showSnackbar(strings.addedFolderNotice(folderTitle))
                }
            },
            onDismiss = { previewTarget = null },
        )
    }
}

@Composable
private fun CloudCollectionResultRow(
    item: ExtSearchResultItem.CollectionItem,
    artworkCache: ArtworkCache,
    canAddAsCloudFolder: Boolean,
    strings: AppStrings,
    onClick: () -> Unit,
    onOpenPreview: () -> Unit,
    onAddAsCloudFolder: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val kindLabel = when (item.kind) {
        ExtensionSearchKind.ALBUM -> strings.cloudSearchKindAlbumBadge
        ExtensionSearchKind.PLAYLIST -> strings.cloudSearchKindPlaylistBadge
        ExtensionSearchKind.TRACK -> strings.cloudSearchKindTracks
    }
    val fallbackIcon = if (item.kind == ExtensionSearchKind.ALBUM) {
        Icons.Rounded.Album
    } else {
        Icons.Rounded.LibraryMusic
    }
    val subtitleParts = buildList {
        item.artist?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        val count = item.trackCount
        if (count != null && count > 0) {
            add(strings.songCount(count))
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            artworkKey = null,
            artworkCache = artworkCache,
            remoteArtworkUrl = item.artworkUrl,
            size = 46.dp,
            cornerRadius = 8.dp,
            fallbackIcon = fallbackIcon,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = kindLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                if (subtitleParts.isNotEmpty()) {
                    Text(
                        text = subtitleParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = strings.trackOptions,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(strings.openPreview) },
                    leadingIcon = { Icon(Icons.Rounded.Visibility, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onOpenPreview()
                    },
                )
                if (canAddAsCloudFolder) {
                    DropdownMenuItem(
                        text = { Text(strings.addAsCloudFolder) },
                        leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAddAsCloudFolder()
                        },
                    )
                }
            }
        }
    }
}

private fun kindFilterLabel(kind: CloudSearchFilterKind, strings: AppStrings): String = when (kind) {
    CloudSearchFilterKind.ALL -> strings.cloudSearchKindAll
    CloudSearchFilterKind.TRACK -> strings.cloudSearchKindTracks
    CloudSearchFilterKind.ALBUM -> strings.cloudSearchKindAlbums
    CloudSearchFilterKind.PLAYLIST -> strings.cloudSearchKindPlaylists
}
