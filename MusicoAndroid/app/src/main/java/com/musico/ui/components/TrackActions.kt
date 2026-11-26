package com.musico.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AddToQueue
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musico.audio.PlayerController
import com.musico.cloud.CloudExporter
import com.musico.i18n.LocalStrings
import com.musico.model.PlayQueue
import com.musico.model.Playlist
import com.musico.model.SearchField
import com.musico.model.Track
import com.musico.store.CloudDownloadJob
import com.musico.store.CloudDownloadKind
import com.musico.store.CloudDownloadManager
import com.musico.store.CloudDownloadStatus
import com.musico.store.LibraryStore
import com.musico.store.PlayHistoryUiState

sealed interface SingleTrackSaveTarget {
    data class FolderUri(val uri: Uri) : SingleTrackSaveTarget
    data object AppFolder : SingleTrackSaveTarget
}

data class PendingSingleTrackSave(
    val track: Track,
    val target: SingleTrackSaveTarget,
    val qualityId: String? = null,
)

@Stable
class TrackActionState(
    val libraryStore: LibraryStore,
    val playerController: PlayerController,
    val cloudExporter: CloudExporter,
    val cloudDownloadManager: CloudDownloadManager,
    val onPickFolder: ((Uri) -> Unit) -> Unit,
    val onPickImage: ((Uri) -> Unit) -> Unit,
    val onQuickSearch: (String, SearchField) -> Unit,
) {
    var queuePickerIds: List<String>? by mutableStateOf(null)
    var playlistPickerIds: List<String>? by mutableStateOf(null)
    var newPlaylistIds: List<String>? by mutableStateOf(null)
    var pendingSaveTrackDestination: Track? by mutableStateOf(null)
    var savingCloudTrack: PendingSingleTrackSave? by mutableStateOf(null)
    var songInfoTrackId: String? by mutableStateOf(null)

    fun requestSaveCloudTrack(track: Track) {
        pendingSaveTrackDestination = track
    }
}

@Composable
fun rememberTrackActionState(
    libraryStore: LibraryStore,
    playerController: PlayerController,
    cloudExporter: CloudExporter,
    cloudDownloadManager: CloudDownloadManager = remember(libraryStore, cloudExporter) {
        CloudDownloadManager(libraryStore, cloudExporter)
    },
    onPickFolder: ((Uri) -> Unit) -> Unit,
    onPickImage: ((Uri) -> Unit) -> Unit,
    onQuickSearch: (String, SearchField) -> Unit,
): TrackActionState {
    return remember(libraryStore, playerController, cloudExporter, cloudDownloadManager, onPickFolder, onPickImage, onQuickSearch) {
        TrackActionState(libraryStore, playerController, cloudExporter, cloudDownloadManager, onPickFolder, onPickImage, onQuickSearch)
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
    isFavourite: Boolean = false,
    namedQueueTitle: String? = null,
    addTargetQueueId: String? = null,
) {
    val strings = LocalStrings.current
    val singleResolved = if (ids.size == 1) actionState.libraryStore.track(ids[0]) else null
    val hasEphemeral = ids.any { id ->
        Track.isEphemeralId(id) || actionState.libraryStore.track(id)?.isEphemeral == true
    }
    if (ids.size == 1 && !hasEphemeral) {
        DropdownMenuItem(
            text = { Text(strings.songInfo) },
            leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
            onClick = {
                actionState.songInfoTrackId = ids[0]
                onDismiss()
            }
        )
        HorizontalDivider()
    }
    DropdownMenuItem(
        text = { Text(strings.playNext) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null) },
        enabled = ids.isNotEmpty(),
        onClick = {
            actionState.playerController.playNext(ids)
            onDismiss()
        }
    )
    DropdownMenuItem(
        text = { Text(strings.addToQueue) },
        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
        enabled = ids.isNotEmpty(),
        onClick = {
            actionState.playerController.addToQueue(ids, queueId = addTargetQueueId)
            onDismiss()
        }
    )
    if (queues.size > 1) {
        DropdownMenuItem(
            text = { Text(strings.addToAnotherQueueEllipsis) },
            leadingIcon = { Icon(Icons.Rounded.AddToQueue, contentDescription = null) },
            enabled = ids.isNotEmpty(),
            onClick = {
                actionState.queuePickerIds = ids
                onDismiss()
            }
        )
    }
    DropdownMenuItem(
        text = { Text(strings.playInNewQueue) },
        leadingIcon = { Icon(Icons.Rounded.Queue, contentDescription = null) },
        enabled = ids.isNotEmpty(),
        onClick = {
            if (namedQueueTitle != null) {
                actionState.playerController.playInNamedQueue(namedQueueTitle, ids, startAt = 0)
            } else {
                actionState.playerController.playInNewQueue(ids)
            }
            onDismiss()
        }
    )
    if (!hasEphemeral) {
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(strings.addToPlaylistEllipsis) },
            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
            enabled = ids.isNotEmpty(),
            onClick = {
                actionState.playlistPickerIds = ids
                onDismiss()
            }
        )
        if (favouriteId != null) {
            DropdownMenuItem(
                text = { Text(if (isFavourite) strings.removeFromFavourites else strings.addToFavourites) },
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
    val singleCloudTrack = singleResolved?.takeIf { it.isCloud }
    val supportsStream = singleCloudTrack?.let { cloudTrack ->
        val provider = actionState.libraryStore.providerRegistry.providerForTrack(
            cloudTrack,
            actionState.libraryStore.rootFor(cloudTrack),
        )
        provider?.supportsStream ?: !cloudTrack.isEphemeral
    } ?: false
    if (singleCloudTrack != null && supportsStream) {
        if (hasEphemeral) {
            HorizontalDivider()
        }
        DropdownMenuItem(
            text = { Text(strings.saveToLocalFolderEllipsis) },
            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
            onClick = {
                onDismiss()
                actionState.requestSaveCloudTrack(singleCloudTrack)
            }
        )
    }
}

@Composable
fun TrackActionDialogs(
    state: TrackActionState,
    queues: List<PlayQueue>,
    activeQueueId: String,
    playlists: List<Playlist>,
    historyState: PlayHistoryUiState,
) {
    val strings = LocalStrings.current
    val queueIds = state.queuePickerIds
    if (queueIds != null) {
        val otherQueues = queues.filter { it.id != activeQueueId }
        OptionPickerDialog(
            title = strings.addToQueue,
            options = otherQueues.map { q ->
                PickerOption(
                    id = q.id,
                    title = strings.displayQueueName(q.name),
                    subtitle = strings.songCount(q.trackIds.size)
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
            title = strings.addToPlaylistEllipsis.removeSuffix("…"),
            options = playlists.map { pl ->
                PickerOption(
                    id = pl.id,
                    title = pl.name,
                    subtitle = strings.songCount(pl.trackIds.size)
                )
            },
            extraActionLabel = strings.newPlaylistEllipsis,
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
            title = strings.newPlaylist,
            label = strings.nameLabel,
            supportingText = strings.enterPlaylistNameHint,
            confirmLabel = strings.create,
            onDismiss = { state.newPlaylistIds = null },
            onConfirm = { name ->
                val newId = state.libraryStore.createPlaylist(name)
                state.libraryStore.addToPlaylist(createIds, newId)
            }
        )
    }

    val pendingDestinationTrack = state.pendingSaveTrackDestination
    if (pendingDestinationTrack != null) {
        val root = remember(pendingDestinationTrack.id, pendingDestinationTrack.rootId) {
            state.libraryStore.rootFor(pendingDestinationTrack)
        }
        val provider = remember(pendingDestinationTrack.id, pendingDestinationTrack.cloudSource, root?.id) {
            state.libraryStore.providerRegistry.providerForTrack(pendingDestinationTrack, root)
        }
        val qualities = remember(provider) { provider?.availableQualities.orEmpty() }
        val initialQualityId = remember(pendingDestinationTrack.id, provider, root) {
            if (qualities.isEmpty()) {
                null
            } else {
                val validIds = qualities.mapTo(HashSet()) { it.id }
                val extDownloadQuality = provider?.providerId?.let {
                    state.libraryStore.providerRegistry.extensionQualityFor(it)?.second
                }
                listOfNotNull(
                    root?.downloadQualityId,
                    extDownloadQuality,
                    provider?.defaultDownloadQualityId,
                ).firstOrNull { it in validIds } ?: qualities.firstOrNull()?.id
            }
        }
        var selectedQualityId by remember(pendingDestinationTrack.id, initialQualityId) {
            mutableStateOf(initialQualityId)
        }
        var qualityMenuExpanded by remember(pendingDestinationTrack.id) {
            mutableStateOf(false)
        }

        AlertDialog(
            onDismissRequest = { state.pendingSaveTrackDestination = null },
            icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
            title = { Text(strings.saveToLocalFolderTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = pendingDestinationTrack.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = strings.saveTrackDestinationMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (qualities.size > 1) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = strings.downloadQualityLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Box {
                                FilledTonalButton(
                                    onClick = { qualityMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    val selectedOption = qualities.firstOrNull { it.id == selectedQualityId }
                                        ?: qualities.first()
                                    Text(
                                        text = selectedOption.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                DropdownMenu(
                                    expanded = qualityMenuExpanded,
                                    onDismissRequest = { qualityMenuExpanded = false },
                                ) {
                                    qualities.forEach { option ->
                                        val displayLabel = if (
                                            option.lossless &&
                                            !option.label.contains(strings.qualityLosslessBadge, ignoreCase = true) &&
                                            !option.label.contains("Lossless", ignoreCase = true)
                                        ) {
                                            "${option.label} (${strings.qualityLosslessBadge})"
                                        } else {
                                            option.label
                                        }
                                        DropdownMenuItem(
                                            text = { Text(displayLabel) },
                                            leadingIcon = if (option.id == selectedQualityId) {
                                                { Icon(Icons.Rounded.Check, contentDescription = null) }
                                            } else null,
                                            onClick = {
                                                selectedQualityId = option.id
                                                qualityMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val chosenQuality = if (qualities.size > 1) selectedQualityId else null
                        state.pendingSaveTrackDestination = null
                        state.savingCloudTrack = PendingSingleTrackSave(
                            track = pendingDestinationTrack,
                            target = SingleTrackSaveTarget.AppFolder,
                            qualityId = chosenQuality,
                        )
                    }
                ) {
                    Text(strings.saveToMusicoFolder)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val chosenQuality = if (qualities.size > 1) selectedQualityId else null
                        state.pendingSaveTrackDestination = null
                        state.onPickFolder { uri ->
                            state.savingCloudTrack = PendingSingleTrackSave(
                                track = pendingDestinationTrack,
                                target = SingleTrackSaveTarget.FolderUri(uri),
                                qualityId = chosenQuality,
                            )
                        }
                    }
                ) {
                    Text(strings.chooseFolderEllipsis)
                }
            },
        )
    }

    val saveRequest = state.savingCloudTrack
    if (saveRequest != null) {
        SaveSingleCloudTrackDialog(
            track = saveRequest.track,
            target = saveRequest.target,
            qualityId = saveRequest.qualityId,
            libraryStore = state.libraryStore,
            cloudDownloadManager = state.cloudDownloadManager,
            onDismiss = { state.savingCloudTrack = null },
        )
    }

    val infoTrackId = state.songInfoTrackId
    val infoTrack = infoTrackId?.let { state.libraryStore.track(it) }
    if (infoTrack != null) {
        SongInfoSheet(
            track = infoTrack,
            libraryStore = state.libraryStore,
            historyState = historyState,
            onPickImage = state.onPickImage,
            onQuickSearch = state.onQuickSearch,
            onDismiss = { state.songInfoTrackId = null },
        )
    }
}

@Composable
private fun SaveSingleCloudTrackDialog(
    track: Track,
    target: SingleTrackSaveTarget,
    qualityId: String?,
    libraryStore: LibraryStore,
    cloudDownloadManager: CloudDownloadManager,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val jobs by cloudDownloadManager.jobs.collectAsStateWithLifecycle()
    var jobId by remember(track.id, target, qualityId) { mutableStateOf<String?>(null) }

    LaunchedEffect(track.id, target, qualityId) {
        jobId = when (target) {
            is SingleTrackSaveTarget.FolderUri -> cloudDownloadManager.startTrackExport(
                track = track,
                root = libraryStore.rootFor(track),
                targetUri = target.uri,
                qualityOverride = qualityId,
            )
            SingleTrackSaveTarget.AppFolder -> cloudDownloadManager.startTrackExportToAppFolder(
                track = track,
                root = libraryStore.rootFor(track),
                qualityOverride = qualityId,
            )
        }
    }

    val activeJob = remember(jobs, jobId, track.title) {
        jobs.firstOrNull { it.id == jobId } ?: CloudDownloadJob(
            id = jobId.orEmpty(),
            kind = CloudDownloadKind.TRACK_EXPORT,
            title = track.title,
            totalTracks = 1,
            currentTrackTitle = track.title,
        )
    }
    val isExporting = activeJob.isActive
    val isFailed = activeJob.status == CloudDownloadStatus.FAILED
    val isCancelled = activeJob.status == CloudDownloadStatus.CANCELLED

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
        title = {
            Text(
                when {
                    isExporting -> strings.savingSongTitle
                    isFailed -> strings.saveFailedTitle
                    isCancelled -> strings.downloadStatusCancelled
                    else -> strings.songSavedTitle
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    isExporting -> {
                        Text(
                            text = strings.downloadingTrack(track.title),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        CloudTransferProgressSection(job = activeJob)
                    }
                    isFailed -> {
                        Text(
                            text = activeJob.errorMessage ?: strings.couldNotSaveSongFallback,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    isCancelled -> {
                        Text(
                            text = strings.downloadStatusCancelled,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        Text(
                            text = strings.savedAsFile(activeJob.savedFileName.orEmpty()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (activeJob.libraryUpdated) {
                            Text(
                                text = strings.libraryUpdated,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isExporting) {
                TextButton(onClick = onDismiss) {
                    Text(strings.runInBackground)
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(if (isFailed || isCancelled) strings.close else strings.ok)
                }
            }
        },
        dismissButton = {
            if (isExporting) {
                TextButton(
                    onClick = {
                        jobId?.let(cloudDownloadManager::cancel)
                        onDismiss()
                    }
                ) {
                    Text(strings.cancel)
                }
            }
        },
    )
}
