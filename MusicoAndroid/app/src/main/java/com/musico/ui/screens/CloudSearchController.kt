package com.musico.ui.screens

import com.musico.audio.PlayerController
import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudPlaylistResult
import com.musico.cloud.CloudSourceException
import com.musico.cloud.CloudTrackMetadata
import com.musico.extensions.CloudProviderIds
import com.musico.extensions.CloudSearchFilterKind
import com.musico.extensions.ExtSearchPage
import com.musico.extensions.ExtSearchResultItem
import com.musico.extensions.ExtensionStore
import com.musico.extensions.InstalledExtension
import com.musico.extensions.QuickJsExtensionRuntime
import com.musico.extensions.SearchExtensionDescriptor
import com.musico.model.Track
import com.musico.store.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CloudSearchSource {
    data object Local : CloudSearchSource
    data class Extension(val extensionId: String) : CloudSearchSource
}

data class CloudSearchUiError(
    val code: CloudErrorCode,
    val detail: String? = null,
)

data class CloudSearchUiState(
    val availableSources: List<SearchExtensionDescriptor> = emptyList(),
    val selectedSource: CloudSearchSource = CloudSearchSource.Local,
    val query: String = "",
    val selectedKind: CloudSearchFilterKind = CloudSearchFilterKind.ALL,
    val submittedQuery: String? = null,
    val submittedKind: CloudSearchFilterKind = CloudSearchFilterKind.ALL,
    val items: List<ExtSearchResultItem> = emptyList(),
    val nextCursor: String? = null,
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: CloudSearchUiError? = null,
    val hasSubmitted: Boolean = false,
) {
    val isCloudMode: Boolean
        get() = selectedSource is CloudSearchSource.Extension && activeExtension != null

    val activeExtension: SearchExtensionDescriptor?
        get() = (selectedSource as? CloudSearchSource.Extension)?.let { src ->
            availableSources.firstOrNull { it.id == src.extensionId }
        }

    val availableKindFilters: List<CloudSearchFilterKind>
        get() {
            val ext = activeExtension ?: return emptyList()
            if (ext.searchKinds.size <= 1) return emptyList()
            return listOf(CloudSearchFilterKind.ALL) + ext.searchKinds.map { it.toFilterKind() }
        }

    val effectiveQueryKind: CloudSearchFilterKind
        get() {
            val ext = activeExtension ?: return CloudSearchFilterKind.ALL
            if (ext.searchKinds.size == 1) return ext.searchKinds.first().toFilterKind()
            return if (selectedKind == CloudSearchFilterKind.ALL ||
                ext.searchKinds.any { it.toFilterKind() == selectedKind }
            ) {
                selectedKind
            } else {
                CloudSearchFilterKind.ALL
            }
        }

    val trackResults: List<ExtSearchResultItem.TrackItem>
        get() = items.filterIsInstance<ExtSearchResultItem.TrackItem>()

    val canLoadMore: Boolean
        get() = isCloudMode && !isSearching && !isLoadingMore && hasSubmitted && !nextCursor.isNullOrBlank()
}

fun ExtSearchResultItem.TrackItem.toEphemeralTrack(extensionId: String): Track {
    val cleanRemoteId = remoteTrackId.trim()
    return Track(
        id = Track.makeEphemeralId(extensionId, cleanRemoteId),
        rootId = "",
        relativePath = relativePath?.trim().orEmpty(),
        title = title.trim().ifEmpty { cleanRemoteId },
        artist = artist?.trim().orEmpty(),
        album = album?.trim().orEmpty(),
        albumArtist = "",
        genre = "",
        trackNumber = null,
        discNumber = null,
        durationSec = durationSec?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
        fileSize = 0L,
        modifiedEpochMs = 0L,
        hasEmbeddedLyrics = false,
        dateAddedEpochMs = 0L,
        artworkKey = null,
        cloudSource = CloudProviderIds.extensionProviderId(extensionId),
        remoteTrackId = cleanRemoteId,
        remoteArtworkUrl = artworkUrl?.trim()?.takeIf { it.isNotEmpty() },
        isEphemeral = true,
    )
}

fun CloudTrackMetadata.toEphemeralTrack(extensionId: String): Track {
    val cleanRemoteId = remoteTrackId.trim()
    return Track(
        id = Track.makeEphemeralId(extensionId, cleanRemoteId),
        rootId = "",
        relativePath = relativePath?.trim().orEmpty(),
        title = title.trim().ifEmpty { cleanRemoteId },
        artist = artist.trim(),
        album = album.trim(),
        albumArtist = "",
        genre = "",
        trackNumber = trackNumber,
        discNumber = null,
        durationSec = durationSec.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
        fileSize = 0L,
        modifiedEpochMs = 0L,
        hasEmbeddedLyrics = false,
        dateAddedEpochMs = 0L,
        artworkKey = null,
        cloudSource = CloudProviderIds.extensionProviderId(extensionId),
        remoteTrackId = cleanRemoteId,
        remoteArtworkUrl = artworkUrl?.trim()?.takeIf { it.isNotEmpty() },
        isEphemeral = true,
    )
}

fun CloudPlaylistResult.toEphemeralTracks(extensionId: String): List<Track> =
    tracks.map { it.toEphemeralTrack(extensionId) }

class CloudSearchController(
    private val extensionStore: ExtensionStore,
    private val scope: CoroutineScope,
    private val libraryStore: LibraryStore? = null,
    private val playerController: PlayerController? = null,
) {
    private val _uiState = MutableStateFlow(
        CloudSearchUiState(
            availableSources = extensionStore.activeSearchExtensions(),
        )
    )
    val uiState: StateFlow<CloudSearchUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var requestGeneration: Long = 0L
    private val seenCursors = HashSet<String>()

    init {
        scope.launch {
            extensionStore.extensions.collect {
                syncAvailableSources(it)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun syncAvailableSources(unused: List<InstalledExtension> = extensionStore.extensions.value) {
        val available = extensionStore.activeSearchExtensions()
        val cur = _uiState.value
        val activeExtId = (cur.selectedSource as? CloudSearchSource.Extension)?.extensionId
        if (activeExtId != null && available.none { it.id == activeExtId }) {
            cancelActiveRequest()
            _uiState.value = cur.copy(
                availableSources = available,
                selectedSource = CloudSearchSource.Local,
                selectedKind = CloudSearchFilterKind.ALL,
                submittedQuery = null,
                submittedKind = CloudSearchFilterKind.ALL,
                items = emptyList(),
                nextCursor = null,
                isSearching = false,
                isLoadingMore = false,
                error = null,
                hasSubmitted = false,
            )
        } else {
            _uiState.value = cur.copy(availableSources = available)
        }
    }

    fun selectSource(source: CloudSearchSource) {
        val cur = _uiState.value
        if (source == cur.selectedSource) return
        val wasCloud = cur.selectedSource is CloudSearchSource.Extension

        cancelActiveRequest()
        if (source is CloudSearchSource.Local) {
            _uiState.value = cur.copy(
                selectedSource = CloudSearchSource.Local,
                selectedKind = CloudSearchFilterKind.ALL,
                submittedQuery = null,
                submittedKind = CloudSearchFilterKind.ALL,
                items = emptyList(),
                nextCursor = null,
                isSearching = false,
                isLoadingMore = false,
                error = null,
                hasSubmitted = false,
            )
            return
        }

        val extSource = source as CloudSearchSource.Extension
        if (cur.availableSources.none { it.id == extSource.extensionId }) {
            _uiState.value = cur.copy(
                selectedSource = CloudSearchSource.Local,
                selectedKind = CloudSearchFilterKind.ALL,
                submittedQuery = null,
                submittedKind = CloudSearchFilterKind.ALL,
                items = emptyList(),
                nextCursor = null,
                isSearching = false,
                isLoadingMore = false,
                error = null,
                hasSubmitted = false,
            )
            return
        }

        _uiState.value = cur.copy(
            selectedSource = extSource,
            selectedKind = CloudSearchFilterKind.ALL,
            submittedQuery = null,
            submittedKind = CloudSearchFilterKind.ALL,
            items = emptyList(),
            nextCursor = null,
            isSearching = false,
            isLoadingMore = false,
            error = null,
            hasSubmitted = false,
        )

        if (wasCloud && cur.query.isNotBlank()) {
            submitSearch()
        }
    }

    fun resetToLocal() {
        val cur = _uiState.value
        if (cur.selectedSource is CloudSearchSource.Local &&
            !cur.isSearching &&
            !cur.isLoadingMore &&
            cur.items.isEmpty() &&
            cur.error == null &&
            !cur.hasSubmitted
        ) {
            return
        }
        cancelActiveRequest()
        _uiState.value = cur.copy(
            selectedSource = CloudSearchSource.Local,
            selectedKind = CloudSearchFilterKind.ALL,
            submittedQuery = null,
            submittedKind = CloudSearchFilterKind.ALL,
            items = emptyList(),
            nextCursor = null,
            isSearching = false,
            isLoadingMore = false,
            error = null,
            hasSubmitted = false,
        )
    }

    fun onQueryChanged(newQuery: String) {
        val cur = _uiState.value
        if (cur.query == newQuery) return
        _uiState.value = cur.copy(query = newQuery)
    }

    fun clearQuery() {
        val cur = _uiState.value
        if (cur.isCloudMode) {
            cancelActiveRequest()
            _uiState.value = cur.copy(
                query = "",
                submittedQuery = null,
                items = emptyList(),
                nextCursor = null,
                isSearching = false,
                isLoadingMore = false,
                error = null,
                hasSubmitted = false,
            )
        } else {
            _uiState.value = cur.copy(query = "")
        }
    }

    fun selectKind(kind: CloudSearchFilterKind) {
        val cur = _uiState.value
        if (cur.selectedKind == kind) return
        _uiState.value = cur.copy(selectedKind = kind)
        val cleanQuery = cur.query.trim()
        if (cur.isCloudMode && cur.hasSubmitted && cleanQuery.isNotEmpty() && cur.submittedQuery == cleanQuery) {
            submitSearch()
        }
    }

    fun submitSearch() {
        val cur = _uiState.value
        val ext = cur.activeExtension ?: return
        val cleanQuery = cur.query.trim()
        if (cleanQuery.isEmpty()) return

        cancelActiveRequest()
        val gen = ++requestGeneration
        val kind = cur.effectiveQueryKind

        _uiState.value = _uiState.value.copy(
            submittedQuery = cleanQuery,
            submittedKind = kind,
            items = emptyList(),
            nextCursor = null,
            isSearching = true,
            isLoadingMore = false,
            error = null,
            hasSubmitted = true,
        )

        activeJob = scope.launch {
            try {
                val page = extensionStore.search(
                    extensionId = ext.id,
                    query = cleanQuery,
                    kind = kind,
                    limit = QuickJsExtensionRuntime.DEFAULT_SEARCH_LIMIT,
                    cursor = null,
                )
                if (requestGeneration != gen) return@launch
                val deduped = deduplicateItems(page.items)
                val nextCursor = page.nextCursor?.trim()?.takeIf { it.isNotEmpty() }
                _uiState.value = _uiState.value.copy(
                    items = deduped,
                    nextCursor = nextCursor,
                    isSearching = false,
                    error = null,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (requestGeneration != gen) return@launch
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    error = toUiError(t),
                )
            }
        }
    }

    fun refresh(): Boolean {
        val cur = _uiState.value
        if (!cur.isCloudMode) return false
        if (cur.query.isNotBlank()) {
            submitSearch()
        }
        return true
    }

    fun retry() {
        val cur = _uiState.value
        if (!cur.isCloudMode) return
        if (cur.items.isNotEmpty() && !cur.nextCursor.isNullOrBlank()) {
            loadMore()
        } else {
            submitSearch()
        }
    }

    fun loadMore() {
        val cur = _uiState.value
        val ext = cur.activeExtension ?: return
        val prevCursor = cur.nextCursor?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val query = cur.submittedQuery?.takeIf { it.isNotBlank() } ?: return
        if (cur.isSearching || cur.isLoadingMore) return

        val gen = requestGeneration
        val kind = cur.submittedKind

        seenCursors.add(prevCursor)

        _uiState.value = cur.copy(
            isLoadingMore = true,
            error = null,
        )

        activeJob = scope.launch {
            try {
                val page: ExtSearchPage = extensionStore.search(
                    extensionId = ext.id,
                    query = query,
                    kind = kind,
                    limit = QuickJsExtensionRuntime.DEFAULT_SEARCH_LIMIT,
                    cursor = prevCursor,
                )
                if (requestGeneration != gen) return@launch
                val latest = _uiState.value
                val merged = deduplicateItems(latest.items + page.items)
                val returnedCursor = page.nextCursor?.trim()?.takeIf { it.isNotEmpty() }
                val safeNextCursor = if (
                    returnedCursor != null &&
                    returnedCursor != prevCursor &&
                    returnedCursor !in seenCursors
                ) {
                    returnedCursor
                } else {
                    null
                }
                _uiState.value = latest.copy(
                    items = merged,
                    nextCursor = safeNextCursor,
                    isLoadingMore = false,
                    error = null,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (requestGeneration != gen) return@launch
                _uiState.value = _uiState.value.copy(
                    isLoadingMore = false,
                    error = toUiError(t),
                )
            }
        }
    }

    fun toEphemeralTrack(item: ExtSearchResultItem.TrackItem): Track? {
        val ext = _uiState.value.activeExtension ?: return null
        return item.toEphemeralTrack(ext.id)
    }

    fun playTrackResult(tappedItem: ExtSearchResultItem.TrackItem): List<Track> {
        val cur = _uiState.value
        val ext = cur.activeExtension ?: return emptyList()
        val trackItems = cur.trackResults
        if (trackItems.isEmpty()) return emptyList()

        val tracks = trackItems.map { it.toEphemeralTrack(ext.id) }
        libraryStore?.registerEphemeralTracks(tracks)

        val tappedIndex = trackItems.indexOfFirst { it.remoteTrackId == tappedItem.remoteTrackId }
            .takeIf { it >= 0 } ?: 0
        val cleanQuery = cur.submittedQuery ?: cur.query.trim()
        val queueName = "Search (${ext.badgeLabel}): $cleanQuery"

        playerController?.playInNamedQueue(
            name = queueName,
            ids = tracks.map { it.id },
            startAt = tappedIndex,
            shuffle = false,
        )
        return tracks
    }

    private fun cancelActiveRequest() {
        requestGeneration++
        seenCursors.clear()
        activeJob?.cancel()
        activeJob = null
    }

    private fun deduplicateItems(list: List<ExtSearchResultItem>): List<ExtSearchResultItem> {
        if (list.size <= 1) return list
        val seen = HashSet<Pair<Any, String>>(list.size)
        val out = ArrayList<ExtSearchResultItem>(list.size)
        for (item in list) {
            if (seen.add(item.kind to item.id)) {
                out.add(item)
            }
        }
        return out
    }

    private fun toUiError(throwable: Throwable): CloudSearchUiError {
        val code = (throwable as? CloudSourceException)?.code ?: CloudErrorCode.EXTENSION_RUNTIME_ERROR
        return CloudSearchUiError(code = code, detail = throwable.message)
    }
}
