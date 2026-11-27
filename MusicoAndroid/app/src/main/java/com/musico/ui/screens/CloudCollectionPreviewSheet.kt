package com.musico.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musico.audio.PlayerUiState
import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudPlaylistResult
import com.musico.cloud.CloudSourceException
import com.musico.extensions.ExtSearchResultItem
import com.musico.extensions.ExtensionSearchKind
import com.musico.extensions.ExtensionStore
import com.musico.extensions.SearchExtensionDescriptor
import com.musico.i18n.AppStrings
import com.musico.i18n.LocalStrings
import com.musico.model.PlayQueue
import com.musico.model.Track
import com.musico.store.LibraryStore
import com.musico.ui.LocalShowMessage
import com.musico.ui.components.ArtworkImage
import com.musico.ui.components.EmptyState
import com.musico.ui.components.PlayShuffleButtons
import com.musico.ui.components.TrackActionState
import com.musico.ui.components.TrackMenuItems
import com.musico.ui.components.TrackRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudCollectionPreviewSheet(
    extension: SearchExtensionDescriptor,
    collectionItem: ExtSearchResultItem.CollectionItem,
    extensionStore: ExtensionStore,
    libraryStore: LibraryStore,
    playerState: PlayerUiState,
    queues: List<PlayQueue>,
    activeQueue: PlayQueue,
    trackActions: TrackActionState,
    onAddedAsCloudFolder: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val showMessage = LocalShowMessage.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sourceUrl = collectionItem.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }

    var reloadNonce by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var previewResult by remember { mutableStateOf<CloudPlaylistResult?>(null) }
    var ephemeralTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loadError by remember { mutableStateOf<CloudSearchUiError?>(null) }

    var isAddingFolder by remember { mutableStateOf(false) }
    var folderAdded by remember { mutableStateOf(false) }
    var addFolderError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(extension.id, sourceUrl, reloadNonce) {
        if (!extension.supportsCollection || sourceUrl == null) {
            isLoading = false
            previewResult = null
            ephemeralTracks = emptyList()
            loadError = CloudSearchUiError(CloudErrorCode.INVALID_URL, strings.collectionPreviewUnsupported)
            return@LaunchedEffect
        }
        isLoading = true
        loadError = null
        try {
            val result = extensionStore.fetchCollectionPreview(extension.id, sourceUrl)
            previewResult = result
            ephemeralTracks = result.toEphemeralTracks(extension.id)
            isLoading = false
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val code = (t as? CloudSourceException)?.code ?: CloudErrorCode.EXTENSION_RUNTIME_ERROR
            loadError = CloudSearchUiError(code = code, detail = t.message)
            isLoading = false
        }
    }

    val resolvedTitle = previewResult?.title?.trim()?.takeIf { it.isNotEmpty() } ?: collectionItem.title
    val kindBadge = when (collectionItem.kind) {
        ExtensionSearchKind.ALBUM -> strings.cloudSearchKindAlbumBadge
        ExtensionSearchKind.PLAYLIST -> strings.cloudSearchKindPlaylistBadge
        ExtensionSearchKind.TRACK -> strings.cloudSearchKindTracks
    }
    val fallbackIcon = if (collectionItem.kind == ExtensionSearchKind.ALBUM) {
        Icons.Rounded.Album
    } else {
        Icons.Rounded.LibraryMusic
    }
    val queueName = "Search (${extension.badgeLabel}): $resolvedTitle"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 640.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    artworkKey = null,
                    artworkCache = libraryStore.artwork,
                    remoteArtworkUrl = collectionItem.artworkUrl,
                    size = 64.dp,
                    cornerRadius = 10.dp,
                    fallbackIcon = fallbackIcon,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                text = kindBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                text = extension.badgeLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = resolvedTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val metaParts = buildList {
                        collectionItem.artist?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
                        val count = previewResult?.tracks?.size ?: collectionItem.trackCount
                        if (count != null && count > 0) {
                            add(strings.songCount(count))
                        }
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (extension.supportsCollection && sourceUrl != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        enabled = !isAddingFolder && !folderAdded,
                        onClick = {
                            if (isAddingFolder || folderAdded) return@FilledTonalButton
                            isAddingFolder = true
                            addFolderError = null
                            scope.launch {
                                val res = libraryStore.addCloudRoot(
                                    sourceUrl = sourceUrl,
                                    cookie = null,
                                    providerIdHint = extension.providerId,
                                )
                                isAddingFolder = false
                                res.onSuccess {
                                    folderAdded = true
                                    onAddedAsCloudFolder(resolvedTitle)
                                }.onFailure { err ->
                                    addFolderError = formatCloudSearchError(err, strings)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        when {
                            isAddingFolder -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(strings.addingEllipsis, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                            folderAdded -> {
                                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(strings.addedToFolders, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                            else -> {
                                Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(strings.addAsCloudFolder, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            addFolderError?.let { errText ->
                Text(
                    text = errText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = 6.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                loadError != null -> {
                    val err = loadError!!
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = strings.collectionPreviewLoadFailedTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = formatCloudUiError(err, strings),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            if (extension.supportsCollection && sourceUrl != null) {
                                TextButton(onClick = { reloadNonce++ }) {
                                    Text(strings.retry, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                ephemeralTracks.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            icon = Icons.Rounded.CloudOff,
                            title = strings.collectionPreviewEmptyTitle,
                            description = strings.collectionPreviewEmptyDesc,
                        )
                    }
                }
                else -> {
                    val trackIds = remember(ephemeralTracks) { ephemeralTracks.map { it.id } }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        item(key = "preview_play_shuffle") {
                            PlayShuffleButtons(
                                enabled = trackIds.isNotEmpty(),
                                onPlay = {
                                    libraryStore.registerEphemeralTracks(ephemeralTracks)
                                    trackActions.playerController.playInNamedQueue(
                                        name = queueName,
                                        ids = trackIds,
                                        startAt = 0,
                                        shuffle = false,
                                    )
                                },
                                onShuffle = {
                                    libraryStore.registerEphemeralTracks(ephemeralTracks)
                                    trackActions.playerController.playInNamedQueue(
                                        name = queueName,
                                        ids = trackIds,
                                        startAt = Random.nextInt(trackIds.size.coerceAtLeast(1)),
                                        shuffle = true,
                                    )
                                },
                            )
                        }
                        itemsIndexed(ephemeralTracks, key = { _, t -> t.id }) { index, track ->
                            TrackRow(
                                track = track,
                                artworkCache = libraryStore.artwork,
                                isCurrent = playerState.currentTrack?.id == track.id,
                                isPlaying = playerState.isPlaying,
                                isAvailable = libraryStore.isAvailable(track),
                                onClick = {
                                    libraryStore.registerEphemeralTracks(ephemeralTracks)
                                    trackActions.playerController.playInNamedQueue(
                                        name = queueName,
                                        ids = trackIds,
                                        startAt = index,
                                        shuffle = false,
                                    )
                                },
                                onSwipePlayNext = {
                                    libraryStore.registerEphemeralTrack(track)
                                    trackActions.playerController.playNext(listOf(track.id))
                                    showMessage(strings.playingNextNotice)
                                },
                                menuContent = { onMenuDismiss ->
                                    remember(track) {
                                        libraryStore.registerEphemeralTrack(track)
                                        true
                                    }
                                    TrackMenuItems(
                                        ids = listOf(track.id),
                                        queues = queues,
                                        activeQueueId = activeQueue.id,
                                        actionState = trackActions,
                                        onDismiss = onMenuDismiss,
                                        namedQueueTitle = queueName,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun formatCloudUiError(error: CloudSearchUiError, strings: AppStrings): String = when (error.code) {
    CloudErrorCode.AUTH_REQUIRED -> {
        val detail = error.detail?.trim()?.takeIf { it.isNotEmpty() }
        if (detail != null) "${strings.cloudSearchAuthRequiredHint}\n$detail" else strings.cloudSearchAuthRequiredHint
    }
    CloudErrorCode.RATE_LIMITED -> {
        val detail = error.detail?.trim()?.takeIf { it.isNotEmpty() }
        if (detail != null) "${strings.cloudSearchRateLimitedHint}\n$detail" else strings.cloudSearchRateLimitedHint
    }
    CloudErrorCode.PERMISSION_DENIED -> {
        val detail = error.detail?.trim()?.takeIf { it.isNotEmpty() }
        if (detail != null) "${strings.cloudSearchPermissionDeniedHint}\n$detail" else strings.cloudSearchPermissionDeniedHint
    }
    CloudErrorCode.NETWORK_ERROR -> {
        val detail = error.detail?.trim()?.takeIf { it.isNotEmpty() }
        if (detail != null) "${strings.cloudSearchNetworkErrorHint}\n$detail" else strings.cloudSearchNetworkErrorHint
    }
    CloudErrorCode.EXTENSION_UNAVAILABLE -> strings.errorExtensionUnavailable
    CloudErrorCode.EMPTY_COLLECTION -> strings.collectionPreviewEmptyDesc
    CloudErrorCode.INVALID_URL,
    CloudErrorCode.NO_PLAYABLE_STREAM,
    CloudErrorCode.EXTENSION_RUNTIME_ERROR -> {
        error.detail?.trim()?.takeIf { it.isNotEmpty() } ?: strings.unknownError
    }
}

internal fun formatCloudSearchError(throwable: Throwable, strings: AppStrings): String {
    val code = (throwable as? CloudSourceException)?.code ?: CloudErrorCode.EXTENSION_RUNTIME_ERROR
    return formatCloudUiError(CloudSearchUiError(code = code, detail = throwable.message), strings)
}
