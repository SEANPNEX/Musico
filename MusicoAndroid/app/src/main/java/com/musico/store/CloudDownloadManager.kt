package com.musico.store

import android.net.Uri
import com.musico.cloud.CloudExportProgress
import com.musico.cloud.CloudExportSummary
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudUpdateSummary
import com.musico.model.LibraryRoot
import com.musico.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class CloudDownloadKind {
    PLAYLIST_EXPORT,
    TRACK_EXPORT,
    FOLDER_UPDATE,
}

enum class CloudDownloadStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class CloudDownloadJob(
    val id: String,
    val kind: CloudDownloadKind,
    val title: String,
    val subtitle: String? = null,
    val status: CloudDownloadStatus = CloudDownloadStatus.RUNNING,
    val completedTracks: Int = 0,
    val totalTracks: Int = 0,
    val currentTrackTitle: String = "",
    val failedCount: Int = 0,
    val trackBytesDownloaded: Long = 0L,
    val trackTotalBytes: Long? = null,
    val bytesPerSecond: Double = 0.0,
    val trackFraction: Float? = null,
    val overallFraction: Float = 0f,
    val exportSummary: CloudExportSummary? = null,
    val updateSummary: CloudUpdateSummary? = null,
    val savedFileName: String? = null,
    val libraryAddAttempted: Boolean = false,
    val libraryUpdated: Boolean = false,
    val libraryAddError: Throwable? = null,
    val errorMessage: String? = null,
    val locationUnavailable: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
    val isActive: Boolean
        get() = status == CloudDownloadStatus.RUNNING
}

/**
 * App-scoped download manager that executes cloud playlist exports, single-track saves,
 * and tracked local folder updates in background coroutines with live byte/speed progress.
 */
class CloudDownloadManager(
    private val libraryStore: LibraryStore,
    private val cloudExporter: CloudExporter,
    private val onFolderSavedWithoutLibrary: ((String) -> Unit)? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _jobs = MutableStateFlow<List<CloudDownloadJob>>(emptyList())
    val jobs: StateFlow<List<CloudDownloadJob>> = _jobs.asStateFlow()

    private val runningCoroutines = ConcurrentHashMap<String, Job>()
    private val stateLock = Any()

    fun job(jobId: String): CloudDownloadJob? =
        _jobs.value.firstOrNull { it.id == jobId }

    fun startPlaylistExport(
        tracks: List<Track>,
        root: LibraryRoot?,
        parentUri: Uri,
        folderName: String,
        addToLibrary: Boolean,
        isWholeRootExport: Boolean = true,
        onSavedWithoutLibrary: ((String) -> Unit)? = null,
    ): String = launchPlaylistExportJob(
        tracks = tracks,
        root = root,
        folderName = folderName,
        addToLibrary = addToLibrary,
        onSavedWithoutLibrary = onSavedWithoutLibrary,
        runExport = { onProgress ->
            cloudExporter.exportPlaylist(
                tracks = tracks,
                root = root,
                parentTreeUri = parentUri,
                folderName = folderName,
                onDetailedProgress = onProgress,
            )
        },
        isTargetAlreadyIndexed = { res ->
            if (parentUri.scheme == "file" && res.targetDir != null) {
                libraryStore.isDirectoryInsideIndexedRoot(res.targetDir)
            } else {
                libraryStore.isUriInsideIndexedRoot(parentUri)
            }
        },
        addTargetToLibrary = { res ->
            val mergeCloudRoot = isWholeRootExport && root?.kind?.isCloud == true
            val isDirectFileDir = parentUri.scheme == "file" && res.targetDir != null
            if (isDirectFileDir) {
                libraryStore.addLocalFolder(
                    dir = res.targetDir!!,
                    cloudSourceRoot = root,
                    trackedSubfolderName = null,
                    mergeCloudRoot = mergeCloudRoot,
                )
            } else {
                libraryStore.addPickedRoot(
                    treeUri = parentUri,
                    cloudSourceRoot = root,
                    trackedSubfolderName = res.savedFolderName,
                    mergeCloudRoot = mergeCloudRoot,
                )
            }
        },
    )

    fun startPlaylistExport(
        tracks: List<Track>,
        root: LibraryRoot?,
        parentDir: File,
        folderName: String,
        addToLibrary: Boolean,
        isWholeRootExport: Boolean = true,
        onSavedWithoutLibrary: ((String) -> Unit)? = null,
    ): String = launchPlaylistExportJob(
        tracks = tracks,
        root = root,
        folderName = folderName,
        addToLibrary = addToLibrary,
        onSavedWithoutLibrary = onSavedWithoutLibrary,
        runExport = { onProgress ->
            cloudExporter.exportPlaylist(
                tracks = tracks,
                root = root,
                parentDir = parentDir,
                folderName = folderName,
                onDetailedProgress = onProgress,
            )
        },
        isTargetAlreadyIndexed = { res ->
            val dir = res.targetDir ?: File(parentDir, res.savedFolderName ?: folderName)
            libraryStore.isDirectoryInsideIndexedRoot(dir)
        },
        addTargetToLibrary = { res ->
            val mergeCloudRoot = isWholeRootExport && root?.kind?.isCloud == true
            val dir = res.targetDir ?: File(parentDir, res.savedFolderName ?: folderName)
            libraryStore.addLocalFolder(
                dir = dir,
                cloudSourceRoot = root,
                trackedSubfolderName = null,
                mergeCloudRoot = mergeCloudRoot,
            )
        },
    )

    private fun launchPlaylistExportJob(
        tracks: List<Track>,
        root: LibraryRoot?,
        folderName: String,
        addToLibrary: Boolean,
        onSavedWithoutLibrary: ((String) -> Unit)?,
        runExport: suspend (onProgress: (CloudExportProgress) -> Unit) -> CloudExportSummary,
        isTargetAlreadyIndexed: (CloudExportSummary) -> Boolean,
        addTargetToLibrary: suspend (CloudExportSummary) -> Result<Unit>,
    ): String {
        val jobId = UUID.randomUUID().toString()
        val displayTitle = folderName.trim().ifEmpty { root?.name ?: "Cloud Playlist" }
        val initial = CloudDownloadJob(
            id = jobId,
            kind = CloudDownloadKind.PLAYLIST_EXPORT,
            title = displayTitle,
            subtitle = root?.sourceUrl,
            totalTracks = tracks.size,
            currentTrackTitle = tracks.firstOrNull()?.title.orEmpty(),
        )
        prependJob(initial)

        val coroutineJob = scope.launch {
            try {
                val res = runExport { progress -> applyProgress(jobId, progress) }
                var addAttempted = false
                var libUpdated = false
                var addErr: Throwable? = null
                val targetIndexed = res.savedCount > 0 && isTargetAlreadyIndexed(res)
                if (res.savedCount > 0 && (addToLibrary || targetIndexed)) {
                    addAttempted = addToLibrary
                    val addResult = addTargetToLibrary(res)
                    libUpdated = addResult.isSuccess
                    addErr = addResult.exceptionOrNull()
                } else if (!addToLibrary && res.savedCount > 0) {
                    val savedName = res.savedFolderName?.trim()?.takeIf { it.isNotEmpty() }
                    if (savedName != null) {
                        (onSavedWithoutLibrary ?: this@CloudDownloadManager.onFolderSavedWithoutLibrary)?.invoke(savedName)
                    }
                }
                updateJob(jobId) { cur ->
                    cur.copy(
                        status = CloudDownloadStatus.COMPLETED,
                        completedTracks = tracks.size,
                        totalTracks = tracks.size,
                        failedCount = res.failedCount,
                        bytesPerSecond = 0.0,
                        overallFraction = 1f,
                        exportSummary = res,
                        libraryAddAttempted = addAttempted,
                        libraryUpdated = libUpdated,
                        libraryAddError = addErr,
                    )
                }
            } catch (cancel: CancellationException) {
                markCancelled(jobId)
                throw cancel
            } catch (err: Exception) {
                updateJob(jobId) { cur ->
                    cur.copy(
                        status = CloudDownloadStatus.FAILED,
                        bytesPerSecond = 0.0,
                        errorMessage = err.message ?: err.javaClass.simpleName,
                    )
                }
            }
        }
        trackCoroutine(jobId, coroutineJob)
        return jobId
    }

    fun startTrackExport(
        track: Track,
        root: LibraryRoot?,
        targetUri: Uri,
        artworkBytes: ByteArray? = null,
        qualityOverride: String? = null,
    ): String = launchTrackExportJob(
        track = track,
        targetDir = if (targetUri.scheme == "file" && targetUri.path != null) File(targetUri.path!!) else null,
        targetUri = targetUri,
        runExport = { onProgress ->
            cloudExporter.exportTrack(
                track = track,
                root = root,
                targetTreeUri = targetUri,
                onDetailedProgress = onProgress,
                artworkBytes = artworkBytes,
                qualityOverride = qualityOverride,
            )
        },
        isInsideIndexedRoot = { libraryStore.isUriInsideIndexedRoot(targetUri) },
    )

    fun startTrackExport(
        track: Track,
        root: LibraryRoot?,
        targetDir: File,
        artworkBytes: ByteArray? = null,
        qualityOverride: String? = null,
    ): String = launchTrackExportJob(
        track = track,
        targetDir = targetDir,
        targetUri = null,
        runExport = { onProgress ->
            cloudExporter.exportTrack(
                track = track,
                root = root,
                targetDir = targetDir,
                onDetailedProgress = onProgress,
                artworkBytes = artworkBytes,
                qualityOverride = qualityOverride,
            )
        },
        isInsideIndexedRoot = { libraryStore.isDirectoryInsideIndexedRoot(targetDir) },
    )

    fun startTrackExportToAppFolder(
        track: Track,
        root: LibraryRoot? = null,
        artworkBytes: ByteArray? = null,
        qualityOverride: String? = null,
    ): String = startTrackExport(
        track = track,
        root = root,
        targetDir = libraryStore.appFolderDirectory(),
        artworkBytes = artworkBytes,
        qualityOverride = qualityOverride,
    )

    private fun launchTrackExportJob(
        track: Track,
        targetDir: File?,
        targetUri: Uri?,
        runExport: suspend (onProgress: (CloudExportProgress) -> Unit) -> String,
        isInsideIndexedRoot: () -> Boolean,
    ): String {
        val jobId = UUID.randomUUID().toString()
        val initial = CloudDownloadJob(
            id = jobId,
            kind = CloudDownloadKind.TRACK_EXPORT,
            title = track.title,
            subtitle = track.artist,
            totalTracks = 1,
            currentTrackTitle = track.title,
        )
        prependJob(initial)

        val coroutineJob = scope.launch {
            try {
                val fileName = runExport { progress -> applyProgress(jobId, progress) }
                var libUpdated = false
                if (isInsideIndexedRoot()) {
                    libraryStore.rescan()
                    libraryStore.applyFallbackMetadataForSavedFile(
                        savedFileName = fileName,
                        sourceTrack = track,
                        targetDir = targetDir,
                        targetUri = targetUri,
                    )
                    libUpdated = true
                }
                updateJob(jobId) { cur ->
                    cur.copy(
                        status = CloudDownloadStatus.COMPLETED,
                        completedTracks = 1,
                        totalTracks = 1,
                        bytesPerSecond = 0.0,
                        overallFraction = 1f,
                        savedFileName = fileName,
                        libraryUpdated = libUpdated,
                    )
                }
            } catch (cancel: CancellationException) {
                markCancelled(jobId)
                throw cancel
            } catch (err: Exception) {
                updateJob(jobId) { cur ->
                    cur.copy(
                        status = CloudDownloadStatus.FAILED,
                        bytesPerSecond = 0.0,
                        errorMessage = err.message ?: err.javaClass.simpleName,
                    )
                }
            }
        }
        trackCoroutine(jobId, coroutineJob)
        return jobId
    }

    private fun resolveDirectDirectory(root: LibraryRoot): File? {
        if (root.kind == com.musico.model.RootKind.APP_FOLDER) {
            return libraryStore.appFolderDirectory()
        }
        val raw = root.treeUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!raw.startsWith("file:", ignoreCase = true)) return null
        return runCatching { File(URI.create(raw)) }.getOrNull()
    }

    fun startFolderUpdate(
        root: LibraryRoot,
        subfolderPath: String? = null,
        sourceUrlOverride: String? = null,
        cookieOverride: String? = null,
    ): String {
        val normSub = subfolderPath?.let { LibraryRoot.normalizeFolderPath(it) }?.takeIf { it.isNotEmpty() }
        val tracking = libraryStore.cloudTracking(root.id, normSub.orEmpty())
        val displayTitle = normSub?.substringAfterLast('/') ?: root.name
        val resolvedUrl = sourceUrlOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: tracking?.sourceUrl
            ?: root.sourceUrl

        val jobId = UUID.randomUUID().toString()
        val directDir = resolveDirectDirectory(root)
        val treeUri = if (directDir == null) {
            root.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        } else null
        if (directDir == null && treeUri == null) {
            prependJob(
                CloudDownloadJob(
                    id = jobId,
                    kind = CloudDownloadKind.FOLDER_UPDATE,
                    title = displayTitle,
                    subtitle = resolvedUrl,
                    status = CloudDownloadStatus.FAILED,
                    locationUnavailable = true,
                )
            )
            return jobId
        }

        prependJob(
            CloudDownloadJob(
                id = jobId,
                kind = CloudDownloadKind.FOLDER_UPDATE,
                title = displayTitle,
                subtitle = resolvedUrl,
            )
        )

        val coroutineJob = scope.launch {
            try {
                val fetcher: suspend (String, String?, com.musico.model.RootKind?) -> com.musico.cloud.CloudPlaylistResult =
                    { url, cookie, kindHint -> libraryStore.fetchCloudPlaylist(url, cookie, kindHint, tracking?.resolvedProviderId) }
                val providerFetcher: suspend (String, String?, com.musico.model.RootKind?, String?) -> com.musico.cloud.CloudPlaylistResult =
                    { url, cookie, kindHint, providerIdHint -> libraryStore.fetchCloudPlaylist(url, cookie, kindHint, providerIdHint) }
                val res = if (directDir != null) {
                    cloudExporter.updateLocalFolder(
                        root = root,
                        targetDir = directDir,
                        fetchPlaylist = fetcher,
                        onDetailedProgress = { progress -> applyProgress(jobId, progress) },
                        overrideSourceUrl = sourceUrlOverride ?: tracking?.sourceUrl,
                        overrideCookie = cookieOverride ?: tracking?.cookie,
                        overrideCloudKind = tracking?.cloudKind,
                        subfolderPath = normSub,
                        overrideProviderId = tracking?.resolvedProviderId,
                        fetchPlaylistWithProvider = providerFetcher,
                    )
                } else {
                    cloudExporter.updateLocalFolder(
                        root = root,
                        treeUri = treeUri!!,
                        fetchPlaylist = fetcher,
                        onDetailedProgress = { progress -> applyProgress(jobId, progress) },
                        overrideSourceUrl = sourceUrlOverride ?: tracking?.sourceUrl,
                        overrideCookie = cookieOverride ?: tracking?.cookie,
                        overrideCloudKind = tracking?.cloudKind,
                        subfolderPath = normSub,
                        overrideProviderId = tracking?.resolvedProviderId,
                        fetchPlaylistWithProvider = providerFetcher,
                    )
                }
                libraryStore.markRootCloudSynced(root.id, subfolderPath = normSub)
                if (res.addedCount > 0) {
                    libraryStore.rescan()
                }
                updateJob(jobId) { cur ->
                    cur.copy(
                        status = CloudDownloadStatus.COMPLETED,
                        completedTracks = res.totalRemoteCount,
                        totalTracks = res.totalRemoteCount,
                        failedCount = res.failedCount,
                        bytesPerSecond = 0.0,
                        overallFraction = 1f,
                        updateSummary = res,
                        libraryUpdated = res.addedCount > 0,
                    )
                }
            } catch (cancel: CancellationException) {
                markCancelled(jobId)
                throw cancel
            } catch (err: Exception) {
                updateJob(jobId) { cur ->
                    cur.copy(
                        status = CloudDownloadStatus.FAILED,
                        bytesPerSecond = 0.0,
                        errorMessage = err.message ?: err.javaClass.simpleName,
                    )
                }
            }
        }
        trackCoroutine(jobId, coroutineJob)
        return jobId
    }

    fun cancel(jobId: String) {
        runningCoroutines.remove(jobId)?.cancel()
        markCancelled(jobId)
    }

    fun dismiss(jobId: String) {
        runningCoroutines.remove(jobId)?.cancel()
        synchronized(stateLock) {
            _jobs.value = _jobs.value.filterNot { it.id == jobId }
        }
    }

    fun clearFinished() {
        synchronized(stateLock) {
            _jobs.value = _jobs.value.filter { it.isActive }
        }
    }

    private fun trackCoroutine(jobId: String, coroutineJob: Job) {
        runningCoroutines[jobId] = coroutineJob
        coroutineJob.invokeOnCompletion { runningCoroutines.remove(jobId, coroutineJob) }
    }

    private fun prependJob(job: CloudDownloadJob) {
        synchronized(stateLock) {
            _jobs.value = listOf(job) + _jobs.value
        }
    }

    private fun applyProgress(jobId: String, progress: CloudExportProgress) {
        updateJob(jobId) { cur ->
            if (!cur.isActive) cur else cur.copy(
                completedTracks = progress.completedTracks,
                totalTracks = progress.totalTracks,
                currentTrackTitle = progress.currentTrackTitle,
                failedCount = progress.failedCount,
                trackBytesDownloaded = progress.trackBytesDownloaded,
                trackTotalBytes = progress.trackTotalBytes,
                bytesPerSecond = progress.bytesPerSecond,
                trackFraction = progress.trackFraction,
                overallFraction = progress.overallFraction,
            )
        }
    }

    private fun markCancelled(jobId: String) {
        updateJob(jobId) { cur ->
            if (!cur.isActive) cur else cur.copy(
                status = CloudDownloadStatus.CANCELLED,
                bytesPerSecond = 0.0,
            )
        }
    }

    private inline fun updateJob(jobId: String, transform: (CloudDownloadJob) -> CloudDownloadJob) {
        synchronized(stateLock) {
            val current = _jobs.value
            val index = current.indexOfFirst { it.id == jobId }
            if (index < 0) return
            val next = current.toMutableList()
            next[index] = transform(next[index])
            _jobs.value = next
        }
    }
}
