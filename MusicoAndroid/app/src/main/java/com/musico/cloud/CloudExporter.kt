package com.musico.cloud

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.musico.core.ArtworkCache
import com.musico.core.AudioTagPayload
import com.musico.core.AudioTagWriter
import com.musico.core.CloudThumbnailCache
import com.musico.core.DetectedImageInfo
import com.musico.extensions.CloudProviderIds
import com.musico.extensions.CloudProviderRegistry
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class CloudFolderManifest(
    val sourceURL: String,
    val remoteID: String? = null,
    val cloudKind: String,
    val providerId: String? = null,
    val lastSyncedEpochMs: Long = 0L,
    val streamQualityId: String? = null,
    val downloadQualityId: String? = null,
    val tracks: Map<String, String> = emptyMap(),
)

data class CloudExportSummary(
    val savedCount: Int,
    val failedCount: Int,
    val targetTreeUri: Uri? = null,
    val targetDir: File? = null,
    val savedFolderName: String? = null,
    val errors: List<String> = emptyList(),
)

data class CloudUpdateSummary(
    val addedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val totalRemoteCount: Int,
    val errors: List<String> = emptyList(),
)

data class ByteTransferProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Double,
)

data class CloudExportProgress(
    val completedTracks: Int,
    val totalTracks: Int,
    val currentTrackTitle: String,
    val failedCount: Int = 0,
    val trackBytesDownloaded: Long = 0L,
    val trackTotalBytes: Long? = null,
    val bytesPerSecond: Double = 0.0,
) {
    val trackFraction: Float?
        get() {
            val total = trackTotalBytes ?: return null
            if (total <= 0L) return null
            return (trackBytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }

    val overallFraction: Float
        get() {
            if (totalTracks <= 0) return 0f
            val base = completedTracks.coerceIn(0, totalTracks).toFloat()
            val currentPartial = if (completedTracks < totalTracks) {
                trackFraction ?: 0f
            } else {
                0f
            }
            return ((base + currentPartial) / totalTracks.toFloat()).coerceIn(0f, 1f)
        }
}

class TransferSpeedMeter(
    private val windowNs: Long = DEFAULT_WINDOW_NS,
    private val throttleNs: Long = DEFAULT_THROTTLE_NS,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var lastEmitNs: Long = 0L
    private var lastBytes: Long = 0L

    @Synchronized
    fun reset() {
        samples.clear()
        lastEmitNs = 0L
        lastBytes = 0L
    }

    @Synchronized
    fun record(bytesDownloaded: Long, forceEmit: Boolean = false): Double? {
        val now = nanoClock()
        val safeBytes = bytesDownloaded.coerceAtLeast(0L)
        if (safeBytes < lastBytes) {
            samples.clear()
            samples.addLast(now to safeBytes)
            lastBytes = safeBytes
            lastEmitNs = now
            return 0.0
        }

        if (samples.isEmpty() || safeBytes != lastBytes || now - samples.last().first >= windowNs / 4) {
            samples.addLast(now to safeBytes)
        }
        lastBytes = safeBytes

        while (samples.size > 2 && now - samples[1].first >= windowNs) {
            samples.removeFirst()
        }

        if (!forceEmit && lastEmitNs != 0L && now - lastEmitNs < throttleNs) {
            return null
        }
        lastEmitNs = now
        return computeRateLocked()
    }

    @Synchronized
    fun currentSpeed(): Double = computeRateLocked()

    private fun computeRateLocked(): Double {
        if (samples.size < 2) return 0.0
        val first = samples.first()
        val last = samples.last()
        val dtNs = last.first - first.first
        if (dtNs <= 0L) return 0.0
        val dBytes = (last.second - first.second).coerceAtLeast(0L)
        if (dtNs < MIN_RATE_WINDOW_NS && dBytes == 0L) return 0.0
        return dBytes.toDouble() * 1_000_000_000.0 / dtNs.toDouble()
    }

    companion object {
        const val DEFAULT_WINDOW_NS = 1_500_000_000L
        const val DEFAULT_THROTTLE_NS = 120_000_000L
        private const val MIN_RATE_WINDOW_NS = 40_000_000L
    }
}

fun interface CloudStreamDownloader {
    suspend fun download(
        url: String,
        headers: Map<String, String>,
        output: OutputStream,
    )

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        contentLength: Long?,
        output: OutputStream,
    ) {
        download(url, headers, output)
    }

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        contentLength: Long?,
        output: OutputStream,
        onBytesProgress: ((ByteTransferProgress) -> Unit)?,
    ) {
        download(url, headers, contentLength, output)
    }
}

class UrlConnectionCloudStreamDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : CloudStreamDownloader {

    private data class DownloadResult(
        val statusCode: Int,
        val bytesWritten: Long,
        val contentRangeTotal: Long?,
    )

    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        output: OutputStream,
    ) = download(url, headers, contentLength = null, output = output, onBytesProgress = null)

    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        contentLength: Long?,
        output: OutputStream,
    ) = download(url, headers, contentLength, output, onBytesProgress = null)

    override suspend fun download(
        url: String,
        headers: Map<String, String>,
        contentLength: Long?,
        output: OutputStream,
        onBytesProgress: ((ByteTransferProgress) -> Unit)?,
    ) = withContext(Dispatchers.IO) {
        val meter = if (onBytesProgress != null) TransferSpeedMeter() else null
        val hasAuth = headers.keys.any { it.equals("Authorization", ignoreCase = true) }
        val isYouTube = headers.keys.any { it.equals("X-YouTube-Client-Name", ignoreCase = true) }
        if (contentLength != null && contentLength > CHUNK_SIZE_BYTES && (!hasAuth || isYouTube)) {
            downloadChunked(url, headers, contentLength, output, meter, onBytesProgress)
        } else {
            executeDownloadRequest(
                url = url,
                headers = headers,
                output = output,
                meter = meter,
                baseBytes = 0L,
                knownTotalBytes = contentLength,
                onBytesProgress = onBytesProgress,
            )
            output.flush()
        }
    }

    private suspend fun downloadChunked(
        url: String,
        headers: Map<String, String>,
        contentLength: Long,
        output: OutputStream,
        meter: TransferSpeedMeter?,
        onBytesProgress: ((ByteTransferProgress) -> Unit)?,
    ) {
        val baseHeaders = headers.filterKeys { !it.equals("Range", ignoreCase = true) }
        var offset = 0L
        onBytesProgress?.invoke(ByteTransferProgress(0L, contentLength, 0.0))
        while (offset < contentLength) {
            currentCoroutineContext().ensureActive()
            val end = minOf(offset + CHUNK_SIZE_BYTES - 1L, contentLength - 1L)
            val chunkHeaders = baseHeaders + ("Range" to "bytes=$offset-$end")
            val result = try {
                executeDownloadRequest(
                    url = url,
                    headers = chunkHeaders,
                    output = output,
                    meter = meter,
                    baseBytes = offset,
                    knownTotalBytes = contentLength,
                    onBytesProgress = onBytesProgress,
                )
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    meter?.record(offset, forceEmit = true)
                    onBytesProgress?.invoke(ByteTransferProgress(offset, contentLength, 0.0))
                }
                throw t
            }
            if (offset == 0L && result.statusCode == HttpURLConnection.HTTP_OK) {
                output.flush()
                return
            }
            if (result.statusCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Expected HTTP 206 for range bytes=$offset-$end, got HTTP ${result.statusCode}")
            }
            if (result.bytesWritten <= 0L) {
                throw IOException("Empty response body for range bytes=$offset-$end")
            }
            offset += result.bytesWritten
            val reportedTotal = result.contentRangeTotal
            if (reportedTotal != null && offset >= reportedTotal) {
                break
            }
        }
        output.flush()
    }

    private suspend fun executeDownloadRequest(
        url: String,
        headers: Map<String, String>,
        output: OutputStream,
        meter: TransferSpeedMeter? = null,
        baseBytes: Long = 0L,
        knownTotalBytes: Long? = null,
        onBytesProgress: ((ByteTransferProgress) -> Unit)? = null,
    ): DownloadResult {
        var currentUrl = url
        for (hop in 0 until MAX_REDIRECTS) {
            currentCoroutineContext().ensureActive()
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                instanceFollowRedirects = false
                for ((k, v) in headers) {
                    setRequestProperty(k, v)
                }
            }
            try {
                val code = conn.responseCode
                if (isRedirect(code)) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = URL(URL(currentUrl), location).toString()
                        continue
                    }
                }
                if (code !in 200..299) {
                    throw IOException("HTTP $code downloading stream")
                }
                val expectedLength = conn.contentLengthLong
                val contentRangeTotal = parseContentRangeTotal(conn.getHeaderField("Content-Range"))
                val resolvedTotal = contentRangeTotal
                    ?: knownTotalBytes?.takeIf { it > 0L }
                    ?: if (baseBytes == 0L && expectedLength > 0L) expectedLength else null
                var totalRead = 0L
                val buffer = ByteArray(16 * 1024)
                if (baseBytes == 0L) {
                    meter?.record(0L, forceEmit = true)
                    onBytesProgress?.invoke(ByteTransferProgress(0L, resolvedTotal, 0.0))
                }
                conn.inputStream.use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            totalRead += read
                            val cumulative = baseBytes + totalRead
                            val speed = meter?.record(cumulative)
                            if (speed != null) {
                                onBytesProgress?.invoke(
                                    ByteTransferProgress(
                                        bytesDownloaded = cumulative,
                                        totalBytes = resolvedTotal,
                                        bytesPerSecond = speed,
                                    )
                                )
                            }
                        }
                    }
                }
                if (expectedLength > 0L && totalRead < expectedLength) {
                    throw IOException("Truncated stream: expected $expectedLength bytes, got $totalRead")
                }
                val finalCumulative = baseBytes + totalRead
                if (onBytesProgress != null) {
                    val finalSpeed = meter?.record(finalCumulative, forceEmit = true)
                        ?: meter?.currentSpeed()
                        ?: 0.0
                    onBytesProgress.invoke(
                        ByteTransferProgress(
                            bytesDownloaded = finalCumulative,
                            totalBytes = resolvedTotal,
                            bytesPerSecond = finalSpeed,
                        )
                    )
                }
                return DownloadResult(
                    statusCode = code,
                    bytesWritten = totalRead,
                    contentRangeTotal = contentRangeTotal,
                )
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects downloading $url")
    }

    private fun parseContentRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val slashIdx = header.lastIndexOf('/')
        if (slashIdx < 0) return null
        return header.substring(slashIdx + 1).trim().toLongOrNull()?.takeIf { it > 0L }
    }

    private fun isRedirect(code: Int): Boolean =
        code == HttpURLConnection.HTTP_MOVED_PERM ||
            code == HttpURLConnection.HTTP_MOVED_TEMP ||
            code == HttpURLConnection.HTTP_SEE_OTHER ||
            code == 307 ||
            code == 308

    companion object {
        private const val MAX_REDIRECTS = 6
        internal const val CHUNK_SIZE_BYTES = 4L * 1024L * 1024L
    }
}

class CloudExporter(
    private val context: Context?,
    private val cloudResolver: CloudStreamResolver,
    private val downloader: CloudStreamDownloader = UrlConnectionCloudStreamDownloader(),
    private val providerRegistry: CloudProviderRegistry = CloudProviderRegistry(),
    private val artworkCache: ArtworkCache? = null,
    private val thumbnailCache: CloudThumbnailCache? = null,
    private val httpClient: CloudHttpClient = UrlConnectionCloudHttpClient(),
) {
    private interface ExportSink {
        val existingNames: MutableSet<String>
        fun exists(fileName: String): Boolean
        fun loadManifest(): CloudFolderManifest?
        fun saveManifest(manifest: CloudFolderManifest)
        suspend fun downloadTrack(
            track: Track,
            root: LibraryRoot?,
            suppliedArtworkBytes: ByteArray? = null,
            coverCacheByUrl: MutableMap<String, DetectedImageInfo?>? = null,
            onBytesProgress: ((ByteTransferProgress) -> Unit)?,
        ): String
    }

    private inner class FileExportSink(
        private val dir: File,
    ) : ExportSink {
        override val existingNames: MutableSet<String> = dir.list()?.toMutableSet() ?: mutableSetOf()
        override fun exists(fileName: String): Boolean = File(dir, fileName).exists()
        override fun loadManifest(): CloudFolderManifest? = Companion.loadManifest(dir)
        override fun saveManifest(manifest: CloudFolderManifest) = Companion.saveManifest(dir, manifest)
        override suspend fun downloadTrack(
            track: Track,
            root: LibraryRoot?,
            suppliedArtworkBytes: ByteArray?,
            coverCacheByUrl: MutableMap<String, DetectedImageInfo?>?,
            onBytesProgress: ((ByteTransferProgress) -> Unit)?,
        ): String = downloadToFileDir(
            track = track,
            root = root,
            targetDir = dir,
            existingNames = existingNames,
            suppliedArtworkBytes = suppliedArtworkBytes,
            coverCacheByUrl = coverCacheByUrl,
            onBytesProgress = onBytesProgress,
        )
    }

    private inner class DocumentExportSink(
        private val ctx: Context,
        private val dirDoc: DocumentFile,
    ) : ExportSink {
        override val existingNames: MutableSet<String> =
            dirDoc.listFiles().mapNotNullTo(mutableSetOf()) { it.name }

        override fun exists(fileName: String): Boolean = fileName in existingNames
        override fun loadManifest(): CloudFolderManifest? = Companion.loadManifest(ctx, dirDoc)
        override fun saveManifest(manifest: CloudFolderManifest) = Companion.saveManifest(ctx, dirDoc, manifest)
        override suspend fun downloadTrack(
            track: Track,
            root: LibraryRoot?,
            suppliedArtworkBytes: ByteArray?,
            coverCacheByUrl: MutableMap<String, DetectedImageInfo?>?,
            onBytesProgress: ((ByteTransferProgress) -> Unit)?,
        ): String = downloadToDocumentDir(
            ctx = ctx,
            track = track,
            root = root,
            dirDoc = dirDoc,
            existingNames = existingNames,
            suppliedArtworkBytes = suppliedArtworkBytes,
            coverCacheByUrl = coverCacheByUrl,
            onBytesProgress = onBytesProgress,
        )
    }

    suspend fun exportPlaylist(
        tracks: List<Track>,
        root: LibraryRoot?,
        parentDir: File,
        folderName: String,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
        onDetailedProgress: ((CloudExportProgress) -> Unit)? = null,
    ): CloudExportSummary = withContext(Dispatchers.IO) {
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw IOException("Could not create parent folder: ${parentDir.absolutePath}")
        }
        if (!parentDir.isDirectory) {
            throw IOException("Destination is not a directory: ${parentDir.absolutePath}")
        }

        val safeFolder = sanitizeFileName(folderName, "Cloud Playlist")
        val initialTarget = File(parentDir, safeFolder)
        val targetDir = if (initialTarget.exists() && !initialTarget.isDirectory) {
            val existingParentNames = parentDir.list()?.toSet().orEmpty()
            File(parentDir, uniqueFileName(existingParentNames, safeFolder, ""))
        } else {
            initialTarget
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Could not create folder: ${targetDir.absolutePath}")
        }

        val result = exportPlaylistToSink(
            tracks = tracks,
            root = root,
            sink = FileExportSink(targetDir),
            onProgress = onProgress,
            onDetailedProgress = onDetailedProgress,
        )
        result.copy(
            targetTreeUri = null,
            targetDir = targetDir,
            savedFolderName = targetDir.name,
        )
    }

    suspend fun exportPlaylist(
        tracks: List<Track>,
        root: LibraryRoot?,
        parentTreeUri: Uri,
        folderName: String,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
        onDetailedProgress: ((CloudExportProgress) -> Unit)? = null,
    ): CloudExportSummary {
        if (parentTreeUri.scheme == "file") {
            val path = parentTreeUri.path ?: throw IOException("Invalid file URI: $parentTreeUri")
            return exportPlaylist(tracks, root, File(path), folderName, onProgress, onDetailedProgress)
        }
        return withContext(Dispatchers.IO) {
            val ctx = context ?: throw IOException("Android context unavailable for SAF export.")
            val parentDoc = DocumentFile.fromTreeUri(ctx, parentTreeUri)
                ?: throw IOException("Could not open selected folder.")
            if (!parentDoc.exists() || !parentDoc.isDirectory || !parentDoc.canWrite()) {
                throw IOException("Selected folder is not writable.")
            }

            val safeFolder = sanitizeFileName(folderName, "Cloud Playlist")
            val existingSub = parentDoc.findFile(safeFolder)
            val subDirDoc = when {
                existingSub != null && existingSub.isDirectory -> existingSub
                existingSub != null -> {
                    val parentNames = parentDoc.listFiles().mapNotNull { it.name }.toSet()
                    val uniqueFolder = uniqueFileName(parentNames, safeFolder, "")
                    parentDoc.createDirectory(uniqueFolder)
                        ?: throw IOException("Could not create folder $uniqueFolder.")
                }
                else -> parentDoc.createDirectory(safeFolder)
                    ?: throw IOException("Could not create folder $safeFolder.")
            }

            val result = exportPlaylistToSink(
                tracks = tracks,
                root = root,
                sink = DocumentExportSink(ctx, subDirDoc),
                onProgress = onProgress,
                onDetailedProgress = onDetailedProgress,
            )
            result.copy(
                targetTreeUri = subDirDoc.uri,
                targetDir = null,
                savedFolderName = subDirDoc.name ?: safeFolder,
            )
        }
    }

    private suspend fun exportPlaylistToSink(
        tracks: List<Track>,
        root: LibraryRoot?,
        sink: ExportSink,
        onProgress: (done: Int, total: Int, title: String) -> Unit,
        onDetailedProgress: ((CloudExportProgress) -> Unit)?,
    ): CloudExportSummary {
        val trackedMap = (sink.loadManifest()?.tracks ?: emptyMap()).toMutableMap()
        val coverCacheByUrl = HashMap<String, DetectedImageInfo?>()
        val errors = mutableListOf<String>()
        var savedCount = 0
        var failedCount = 0

        for ((index, track) in tracks.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(index, tracks.size, track.title)
            onDetailedProgress?.invoke(
                CloudExportProgress(
                    completedTracks = index,
                    totalTracks = tracks.size,
                    currentTrackTitle = track.title,
                    failedCount = failedCount,
                )
            )
            try {
                val savedName = sink.downloadTrack(
                    track = track,
                    root = root,
                    coverCacheByUrl = coverCacheByUrl,
                ) { bp ->
                    onDetailedProgress?.invoke(
                        CloudExportProgress(
                            completedTracks = index,
                            totalTracks = tracks.size,
                            currentTrackTitle = track.title,
                            failedCount = failedCount,
                            trackBytesDownloaded = bp.bytesDownloaded,
                            trackTotalBytes = bp.totalBytes,
                            bytesPerSecond = bp.bytesPerSecond,
                        )
                    )
                }
                sink.existingNames.add(savedName)
                val trackKey = track.remoteTrackId ?: track.relativePath
                if (trackKey.isNotEmpty()) {
                    trackedMap[trackKey] = savedName
                }
                savedCount += 1
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Exception) {
                failedCount += 1
                errors.add("${track.title}: ${err.message ?: err.javaClass.simpleName}")
            } finally {
                onProgress(index + 1, tracks.size, track.title)
                onDetailedProgress?.invoke(
                    CloudExportProgress(
                        completedTracks = index + 1,
                        totalTracks = tracks.size,
                        currentTrackTitle = track.title,
                        failedCount = failedCount,
                    )
                )
            }
        }

        val sourceUrl = root?.sourceUrl?.trim()
        val cloudKind = root?.effectiveCloudKind
        if (!sourceUrl.isNullOrEmpty() && cloudKind != null) {
            runCatching {
                sink.saveManifest(
                    CloudFolderManifest(
                        sourceURL = sourceUrl,
                        remoteID = root.remoteId,
                        cloudKind = cloudKind.name,
                        providerId = root.effectiveProviderId,
                        lastSyncedEpochMs = System.currentTimeMillis(),
                        streamQualityId = root.streamQualityId,
                        downloadQualityId = root.downloadQualityId,
                        tracks = trackedMap,
                    )
                )
            }
        }

        return CloudExportSummary(
            savedCount = savedCount,
            failedCount = failedCount,
            errors = errors,
        )
    }

    suspend fun updateLocalFolder(
        root: LibraryRoot,
        targetDir: File,
        fetchPlaylist: suspend (sourceUrl: String, cookie: String?, kindHint: RootKind?) -> CloudPlaylistResult,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
        onDetailedProgress: ((CloudExportProgress) -> Unit)? = null,
        overrideSourceUrl: String? = null,
        overrideCookie: String? = null,
        overrideCloudKind: RootKind? = null,
        subfolderPath: String? = null,
        overrideProviderId: String? = null,
        fetchPlaylistWithProvider: (suspend (sourceUrl: String, cookie: String?, kindHint: RootKind?, providerIdHint: String?) -> CloudPlaylistResult)? = null,
    ): CloudUpdateSummary = withContext(Dispatchers.IO) {
        val normExplicitSub = subfolderPath?.let { LibraryRoot.normalizeFolderPath(it) }?.takeIf { it.isNotEmpty() }
        val resolvedDir = resolveSubfolderFile(targetDir, normExplicitSub)
        if (!resolvedDir.exists() && !resolvedDir.mkdirs()) {
            throw IOException("Could not access target folder: ${resolvedDir.absolutePath}")
        }
        updateLocalFolderInSink(
            root = root,
            sink = FileExportSink(resolvedDir),
            effectiveSubPath = normExplicitSub,
            fetchPlaylist = fetchPlaylist,
            fetchPlaylistWithProvider = fetchPlaylistWithProvider,
            onProgress = onProgress,
            onDetailedProgress = onDetailedProgress,
            overrideSourceUrl = overrideSourceUrl,
            overrideCookie = overrideCookie,
            overrideCloudKind = overrideCloudKind,
            overrideProviderId = overrideProviderId,
        )
    }

    suspend fun updateLocalFolder(
        root: LibraryRoot,
        treeUri: Uri,
        fetchPlaylist: suspend (sourceUrl: String, cookie: String?, kindHint: RootKind?) -> CloudPlaylistResult,
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
        onDetailedProgress: ((CloudExportProgress) -> Unit)? = null,
        overrideSourceUrl: String? = null,
        overrideCookie: String? = null,
        overrideCloudKind: RootKind? = null,
        subfolderPath: String? = null,
        overrideProviderId: String? = null,
        fetchPlaylistWithProvider: (suspend (sourceUrl: String, cookie: String?, kindHint: RootKind?, providerIdHint: String?) -> CloudPlaylistResult)? = null,
    ): CloudUpdateSummary {
        val effectiveSubPath = subfolderPath?.let { LibraryRoot.normalizeFolderPath(it) }?.takeIf { it.isNotEmpty() }
            ?: root.trackedSubfolderName?.let { LibraryRoot.normalizeFolderPath(it) }?.takeIf { it.isNotEmpty() }

        if (treeUri.scheme == "file") {
            val path = treeUri.path ?: throw IOException("Invalid file URI: $treeUri")
            val resolvedDir = resolveSubfolderFile(File(path), effectiveSubPath)
            if (!resolvedDir.exists() && !resolvedDir.mkdirs()) {
                throw IOException("Could not access target folder: ${resolvedDir.absolutePath}")
            }
            return withContext(Dispatchers.IO) {
                updateLocalFolderInSink(
                    root = root,
                    sink = FileExportSink(resolvedDir),
                    effectiveSubPath = effectiveSubPath,
                    fetchPlaylist = fetchPlaylist,
                    fetchPlaylistWithProvider = fetchPlaylistWithProvider,
                    onProgress = onProgress,
                    onDetailedProgress = onDetailedProgress,
                    overrideSourceUrl = overrideSourceUrl,
                    overrideCookie = overrideCookie,
                    overrideCloudKind = overrideCloudKind,
                    overrideProviderId = overrideProviderId,
                )
            }
        }
        return withContext(Dispatchers.IO) {
            val ctx = context ?: throw IOException("Android context unavailable for SAF export.")
            val rootDoc = DocumentFile.fromTreeUri(ctx, treeUri)
                ?: throw IOException("Could not open saved folder.")
            val targetDoc = resolveOrCreateSubfolderDoc(rootDoc, effectiveSubPath)
            if (!targetDoc.exists() || !targetDoc.isDirectory || !targetDoc.canWrite()) {
                throw IOException("Saved folder is not writable. Re-select it to grant write permission.")
            }

            updateLocalFolderInSink(
                root = root,
                sink = DocumentExportSink(ctx, targetDoc),
                effectiveSubPath = effectiveSubPath,
                fetchPlaylist = fetchPlaylist,
                fetchPlaylistWithProvider = fetchPlaylistWithProvider,
                onProgress = onProgress,
                onDetailedProgress = onDetailedProgress,
                overrideSourceUrl = overrideSourceUrl,
                overrideCookie = overrideCookie,
                overrideCloudKind = overrideCloudKind,
                overrideProviderId = overrideProviderId,
            )
        }
    }

    private suspend fun updateLocalFolderInSink(
        root: LibraryRoot,
        sink: ExportSink,
        effectiveSubPath: String?,
        fetchPlaylist: suspend (sourceUrl: String, cookie: String?, kindHint: RootKind?) -> CloudPlaylistResult,
        fetchPlaylistWithProvider: (suspend (sourceUrl: String, cookie: String?, kindHint: RootKind?, providerIdHint: String?) -> CloudPlaylistResult)?,
        onProgress: (done: Int, total: Int, title: String) -> Unit,
        onDetailedProgress: ((CloudExportProgress) -> Unit)?,
        overrideSourceUrl: String?,
        overrideCookie: String?,
        overrideCloudKind: RootKind?,
        overrideProviderId: String?,
    ): CloudUpdateSummary {
        val existingManifest = sink.loadManifest()
        val trackedSubInfo = effectiveSubPath?.let { root.trackingFor(it) }
        val rootMatchesTarget = effectiveSubPath == null ||
            (root.trackedSubfolderName?.let { LibraryRoot.normalizeFolderPath(it) } == effectiveSubPath)

        val rawSource = (
            overrideSourceUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?: trackedSubInfo?.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?: (if (rootMatchesTarget) root.sourceUrl?.trim()?.takeIf { it.isNotEmpty() } else null)
                ?: existingManifest?.sourceURL?.trim()?.takeIf { it.isNotEmpty() }
                ?: (if (rootMatchesTarget) root.remoteId?.trim()?.takeIf { it.isNotEmpty() } else null)
                ?: ""
            )
        if (rawSource.isEmpty()) throw IOException("No cloud tracking source URL is associated with this folder.")

        val effectiveCookie = overrideCookie?.trim()?.takeIf { it.isNotEmpty() }
            ?: trackedSubInfo?.cookie?.trim()?.takeIf { it.isNotEmpty() }
            ?: (if (rootMatchesTarget) root.cookie?.trim()?.takeIf { it.isNotEmpty() } else null)

        val urlClassified = providerRegistry.classifyUrl(rawSource)
        val kindHint = urlClassified?.provider?.rootKind
            ?: inferCloudKindFromUrl(rawSource)
            ?: overrideCloudKind
            ?: trackedSubInfo?.cloudKind
            ?: (if (rootMatchesTarget) root.effectiveCloudKind else null)
            ?: existingManifest?.cloudKind?.let { raw -> runCatching { RootKind.valueOf(raw) }.getOrNull() }
        val providerIdHint = urlClassified?.provider?.providerId
            ?: overrideProviderId
            ?: trackedSubInfo?.resolvedProviderId
            ?: (if (rootMatchesTarget) root.effectiveProviderId else null)
            ?: existingManifest?.providerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: kindHint?.builtinProviderId

        val playlist = if (fetchPlaylistWithProvider != null) {
            fetchPlaylistWithProvider(rawSource, effectiveCookie, kindHint, providerIdHint)
        } else {
            fetchPlaylist(rawSource, effectiveCookie, kindHint)
        }
        val cloudKind = cloudKindForSource(playlist.source)
        val resolvedProviderId = CloudProviderIds.fromLegacySlug(playlist.source)
            ?: providerIdHint
            ?: cloudKind.builtinProviderId
        val resolvedStreamQualityId = trackedSubInfo?.streamQualityId
            ?: (if (rootMatchesTarget) root.streamQualityId else null)
            ?: existingManifest?.streamQualityId
        val resolvedDownloadQualityId = trackedSubInfo?.downloadQualityId
            ?: (if (rootMatchesTarget) root.downloadQualityId else null)
            ?: existingManifest?.downloadQualityId
        val lookupRoot = root.copy(
            kind = cloudKind,
            sourceUrl = rawSource,
            remoteId = playlist.remoteId,
            cookie = effectiveCookie,
            providerId = resolvedProviderId,
            streamQualityId = resolvedStreamQualityId,
            downloadQualityId = resolvedDownloadQualityId,
        )
        val trackedMap = (existingManifest?.tracks ?: emptyMap()).toMutableMap()
        val coverCacheByUrl = HashMap<String, DetectedImageInfo?>()
        val sourceSlug = if (playlist.source.startsWith("ext.")) {
            playlist.source
        } else {
            sourceSlugForKind(cloudKind)
        }
        val nowMs = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        var addedCount = 0
        var skippedCount = 0
        var failedCount = 0

        for ((index, meta) in playlist.tracks.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(index, playlist.tracks.size, meta.title)
            onDetailedProgress?.invoke(
                CloudExportProgress(
                    completedTracks = index,
                    totalTracks = playlist.tracks.size,
                    currentTrackTitle = meta.title,
                    failedCount = failedCount,
                )
            )

            val recordedName = trackedMap[meta.remoteTrackId]
            if (recordedName != null && sink.exists(recordedName)) {
                skippedCount += 1
                onProgress(index + 1, playlist.tracks.size, meta.title)
                onDetailedProgress?.invoke(
                    CloudExportProgress(
                        completedTracks = index + 1,
                        totalTracks = playlist.tracks.size,
                        currentTrackTitle = meta.title,
                        failedCount = failedCount,
                    )
                )
                continue
            }

            val relPath = "$sourceSlug/${meta.remoteTrackId}"
            val tempTrack = Track(
                id = Track.makeId(root.id, relPath),
                rootId = root.id,
                relativePath = relPath,
                title = meta.title.ifBlank { meta.remoteTrackId },
                artist = meta.artist.ifBlank { root.name },
                album = meta.album.ifBlank { playlist.title.ifBlank { root.name } },
                albumArtist = meta.artist.ifBlank { root.name },
                trackNumber = meta.trackNumber ?: (index + 1),
                durationSec = meta.durationSec,
                fileSize = 0L,
                modifiedEpochMs = 0L,
                dateAddedEpochMs = nowMs,
                cloudSource = playlist.source,
                remoteTrackId = meta.remoteTrackId,
                remoteArtworkUrl = meta.artworkUrl,
            )

            try {
                val savedName = sink.downloadTrack(
                    track = tempTrack,
                    root = lookupRoot,
                    coverCacheByUrl = coverCacheByUrl,
                ) { bp ->
                    onDetailedProgress?.invoke(
                        CloudExportProgress(
                            completedTracks = index,
                            totalTracks = playlist.tracks.size,
                            currentTrackTitle = meta.title,
                            failedCount = failedCount,
                            trackBytesDownloaded = bp.bytesDownloaded,
                            trackTotalBytes = bp.totalBytes,
                            bytesPerSecond = bp.bytesPerSecond,
                        )
                    )
                }
                sink.existingNames.add(savedName)
                trackedMap[meta.remoteTrackId] = savedName
                addedCount += 1
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Exception) {
                failedCount += 1
                errors.add("${meta.title}: ${err.message ?: err.javaClass.simpleName}")
            } finally {
                onProgress(index + 1, playlist.tracks.size, meta.title)
                onDetailedProgress?.invoke(
                    CloudExportProgress(
                        completedTracks = index + 1,
                        totalTracks = playlist.tracks.size,
                        currentTrackTitle = meta.title,
                        failedCount = failedCount,
                    )
                )
            }
        }

        sink.saveManifest(
            CloudFolderManifest(
                sourceURL = rawSource,
                remoteID = playlist.remoteId,
                cloudKind = cloudKind.name,
                providerId = resolvedProviderId,
                lastSyncedEpochMs = System.currentTimeMillis(),
                streamQualityId = resolvedStreamQualityId,
                downloadQualityId = resolvedDownloadQualityId,
                tracks = trackedMap,
            )
        )

        return CloudUpdateSummary(
            addedCount = addedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            totalRemoteCount = playlist.tracks.size,
            errors = errors,
        )
    }

    suspend fun exportTrack(
        track: Track,
        root: LibraryRoot?,
        targetDir: File,
        onDetailedProgress: ((CloudExportProgress) -> Unit)? = null,
        artworkBytes: ByteArray? = null,
        qualityOverride: String? = null,
    ): String = withContext(Dispatchers.IO) {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Could not create destination folder: ${targetDir.absolutePath}")
        }
        if (!targetDir.isDirectory) {
            throw IOException("Destination is not a directory: ${targetDir.absolutePath}")
        }
        val existingNames = targetDir.list()?.toSet().orEmpty()
        onDetailedProgress?.invoke(
            CloudExportProgress(
                completedTracks = 0,
                totalTracks = 1,
                currentTrackTitle = track.title,
            )
        )
        val savedName = downloadToFileDir(
            track = track,
            root = root,
            targetDir = targetDir,
            existingNames = existingNames,
            suppliedArtworkBytes = artworkBytes,
            qualityOverride = qualityOverride,
        ) { bp ->
            onDetailedProgress?.invoke(
                CloudExportProgress(
                    completedTracks = 0,
                    totalTracks = 1,
                    currentTrackTitle = track.title,
                    trackBytesDownloaded = bp.bytesDownloaded,
                    trackTotalBytes = bp.totalBytes,
                    bytesPerSecond = bp.bytesPerSecond,
                )
            )
        }
        onDetailedProgress?.invoke(
            CloudExportProgress(
                completedTracks = 1,
                totalTracks = 1,
                currentTrackTitle = track.title,
            )
        )
        savedName
    }

    suspend fun exportTrack(
        track: Track,
        root: LibraryRoot?,
        targetTreeUri: Uri,
        onDetailedProgress: ((CloudExportProgress) -> Unit)? = null,
        artworkBytes: ByteArray? = null,
        qualityOverride: String? = null,
    ): String {
        if (targetTreeUri.scheme == "file") {
            val path = targetTreeUri.path ?: throw IOException("Invalid file URI: $targetTreeUri")
            return exportTrack(track, root, File(path), onDetailedProgress, artworkBytes, qualityOverride)
        }
        return withContext(Dispatchers.IO) {
            val ctx = context ?: throw IOException("Android context unavailable for SAF export.")
            val dirDoc = DocumentFile.fromTreeUri(ctx, targetTreeUri)
                ?: throw IOException("Could not open selected folder.")
            if (!dirDoc.exists() || !dirDoc.isDirectory || !dirDoc.canWrite()) {
                throw IOException("Selected folder is not writable.")
            }
            val existingNames = dirDoc.listFiles().mapNotNullTo(mutableSetOf()) { it.name }
            onDetailedProgress?.invoke(
                CloudExportProgress(
                    completedTracks = 0,
                    totalTracks = 1,
                    currentTrackTitle = track.title,
                )
            )
            val savedName = downloadToDocumentDir(
                ctx = ctx,
                track = track,
                root = root,
                dirDoc = dirDoc,
                existingNames = existingNames,
                suppliedArtworkBytes = artworkBytes,
                qualityOverride = qualityOverride,
            ) { bp ->
                onDetailedProgress?.invoke(
                    CloudExportProgress(
                        completedTracks = 0,
                        totalTracks = 1,
                        currentTrackTitle = track.title,
                        trackBytesDownloaded = bp.bytesDownloaded,
                        trackTotalBytes = bp.totalBytes,
                        bytesPerSecond = bp.bytesPerSecond,
                    )
                )
            }
            onDetailedProgress?.invoke(
                CloudExportProgress(
                    completedTracks = 1,
                    totalTracks = 1,
                    currentTrackTitle = track.title,
                )
            )
            savedName
        }
    }

    private suspend fun downloadToFileDir(
        track: Track,
        root: LibraryRoot?,
        targetDir: File,
        existingNames: Set<String>,
        suppliedArtworkBytes: ByteArray? = null,
        coverCacheByUrl: MutableMap<String, DetectedImageInfo?>? = null,
        qualityOverride: String? = null,
        onBytesProgress: ((ByteTransferProgress) -> Unit)? = null,
    ): String {
        return withStreamRetry(track, root, qualityOverride, onBytesProgress) { stream ->
            val (ext, _) = extensionAndMimeFor(stream)
            val baseName = sanitizeFileName(track.title, track.remoteTrackId ?: "track")
            val fileName = uniqueFileName(existingNames, baseName, ext)
            val targetFile = File(targetDir, fileName)
            val partFile = File(targetDir, ".$fileName.part")
            try {
                FileOutputStream(partFile).use { rawOut ->
                    downloadWithMeteredProgress(track, stream, rawOut, onBytesProgress)
                }
                val cover = resolveCoverImage(track, root, suppliedArtworkBytes, coverCacheByUrl)
                if (AudioTagWriter.isEmbeddableExtension(ext)) {
                    runCatching {
                        AudioTagWriter.write(
                            file = partFile,
                            payload = AudioTagPayload(
                                title = track.title,
                                artist = track.artist,
                                album = track.album,
                                genre = track.genre,
                                trackNumber = track.trackNumber,
                                artworkBytes = cover?.bytes,
                                artworkMimeType = cover?.mimeType,
                            ),
                            formatExtension = ext,
                        )
                    }
                }
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                if (!AudioTagWriter.isEmbeddableExtension(ext) && cover != null) {
                    writeFileSidecarCover(targetDir, fileName, cover)
                }
                fileName
            } catch (t: Throwable) {
                runCatching { partFile.delete() }
                runCatching { targetFile.delete() }
                throw t
            }
        }
    }

    private suspend fun downloadToDocumentDir(
        ctx: Context,
        track: Track,
        root: LibraryRoot?,
        dirDoc: DocumentFile,
        existingNames: Set<String>,
        suppliedArtworkBytes: ByteArray? = null,
        coverCacheByUrl: MutableMap<String, DetectedImageInfo?>? = null,
        qualityOverride: String? = null,
        onBytesProgress: ((ByteTransferProgress) -> Unit)? = null,
    ): String {
        return withStreamRetry(track, root, qualityOverride, onBytesProgress) { stream ->
            val (ext, mimeType) = extensionAndMimeFor(stream)
            val baseName = sanitizeFileName(track.title, track.remoteTrackId ?: "track")
            val fileName = uniqueFileName(existingNames, baseName, ext)
            val cacheParent = (ctx.cacheDir ?: ctx.filesDir).apply { mkdirs() }
            val tempFile = File.createTempFile("musico_export_", if (ext.isNotEmpty()) ".$ext" else ".tmp", cacheParent)
            try {
                FileOutputStream(tempFile).use { rawOut ->
                    downloadWithMeteredProgress(track, stream, rawOut, onBytesProgress)
                }
                val cover = resolveCoverImage(track, root, suppliedArtworkBytes, coverCacheByUrl)
                if (AudioTagWriter.isEmbeddableExtension(ext)) {
                    runCatching {
                        AudioTagWriter.write(
                            tempFile,
                            AudioTagPayload(
                                title = track.title,
                                artist = track.artist,
                                album = track.album,
                                genre = track.genre,
                                trackNumber = track.trackNumber,
                                artworkBytes = cover?.bytes,
                                artworkMimeType = cover?.mimeType,
                            ),
                        )
                    }
                }
                val doc = dirDoc.createFile(mimeType, fileName)
                    ?: throw IOException("Could not create file $fileName in destination folder.")
                try {
                    val out = ctx.contentResolver.openOutputStream(doc.uri)
                        ?: throw IOException("Could not open output stream for $fileName.")
                    out.use { targetOut ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(targetOut)
                        }
                        targetOut.flush()
                    }
                    val actualName = doc.name ?: fileName
                    if (!AudioTagWriter.isEmbeddableExtension(ext) && cover != null) {
                        writeSafSidecarCover(ctx, dirDoc, actualName, cover)
                    }
                    actualName
                } catch (t: Throwable) {
                    runCatching { doc.delete() }
                    throw t
                }
            } finally {
                runCatching { tempFile.delete() }
            }
        }
    }

    internal suspend fun resolveCoverImage(
        track: Track,
        root: LibraryRoot?,
        suppliedArtworkBytes: ByteArray? = null,
        coverCacheByUrl: MutableMap<String, DetectedImageInfo?>? = null,
    ): DetectedImageInfo? {
        AudioTagWriter.detectImage(suppliedArtworkBytes)?.let { return it }

        val cachedFile = track.artworkKey?.let { key -> artworkCache?.file(key) }
        if (cachedFile != null && cachedFile.isFile && cachedFile.canRead()) {
            val cachedBytes = runCatching { cachedFile.readBytes() }.getOrNull()
            AudioTagWriter.detectImage(cachedBytes)?.let { return it }
        }

        val remoteUrl = CloudThumbnailCache.normalizeHttpsUrlOrNull(track.remoteArtworkUrl)
            ?: return null
        if (coverCacheByUrl != null && coverCacheByUrl.containsKey(remoteUrl)) {
            return coverCacheByUrl[remoteUrl]
        }

        val providerHeaders = providerRegistry.providerForTrack(track, root)?.artworkHeaders().orEmpty()
        val fetched = runCatching {
            val resolved = if (thumbnailCache != null) {
                thumbnailCache.fetchImageBytes(remoteUrl, providerHeaders)
            } else {
                CloudThumbnailCache.fetchImageBytesWithClient(httpClient, remoteUrl, providerHeaders)
            }
            resolved?.let { AudioTagWriter.detectImage(it.bytes) }
        }.getOrNull()

        coverCacheByUrl?.put(remoteUrl, fetched)
        return fetched
    }

    private fun writeFileSidecarCover(
        targetDir: File,
        savedFileName: String,
        cover: DetectedImageInfo,
    ) {
        val stem = savedFileName.substringBeforeLast('.', savedFileName)
        if (stem.isEmpty()) return
        val sidecarName = "$stem.${cover.extension}"
        val sidecarFile = File(targetDir, sidecarName)
        val partSidecar = File(targetDir, ".$sidecarName.part")
        runCatching {
            partSidecar.writeBytes(cover.bytes)
            if (!partSidecar.renameTo(sidecarFile)) {
                partSidecar.copyTo(sidecarFile, overwrite = true)
                partSidecar.delete()
            }
        }.onFailure {
            runCatching { partSidecar.delete() }
        }
    }

    private fun writeSafSidecarCover(
        ctx: Context,
        dirDoc: DocumentFile,
        savedFileName: String,
        cover: DetectedImageInfo,
    ) {
        val stem = savedFileName.substringBeforeLast('.', savedFileName)
        if (stem.isEmpty()) return
        val sidecarName = "$stem.${cover.extension}"
        runCatching {
            dirDoc.findFile(sidecarName)?.let { existing -> runCatching { existing.delete() } }
            val sidecarDoc = dirDoc.createFile(cover.mimeType, sidecarName) ?: return
            ctx.contentResolver.openOutputStream(sidecarDoc.uri)?.use { out ->
                out.write(cover.bytes)
                out.flush()
            }
        }
    }

    private suspend fun downloadWithMeteredProgress(
        track: Track,
        stream: ResolvedStream,
        rawOut: OutputStream,
        onBytesProgress: ((ByteTransferProgress) -> Unit)?,
    ) {
        if (onBytesProgress == null) {
            downloader.download(
                stream.url,
                downloadHeadersFor(track, stream),
                stream.contentLength,
                rawOut,
            )
            return
        }

        val meter = TransferSpeedMeter()
        var downloaderReported = false
        val countingOut = CountingOutputStream(rawOut) { written ->
            if (!downloaderReported) {
                meter.record(written)?.let { speed ->
                    onBytesProgress(ByteTransferProgress(written, stream.contentLength, speed))
                }
            }
        }
        downloader.download(
            stream.url,
            downloadHeadersFor(track, stream),
            stream.contentLength,
            countingOut,
        ) { progress ->
            downloaderReported = true
            onBytesProgress(progress)
        }
        if (!downloaderReported && countingOut.bytesWritten > 0L) {
            val finalSpeed = meter.record(countingOut.bytesWritten, forceEmit = true) ?: meter.currentSpeed()
            onBytesProgress(ByteTransferProgress(countingOut.bytesWritten, stream.contentLength, finalSpeed))
        }
    }

    private class CountingOutputStream(
        delegate: OutputStream,
        private val onWritten: (Long) -> Unit,
    ) : FilterOutputStream(delegate) {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            bytesWritten += 1L
            onWritten(bytesWritten)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            if (len > 0) {
                bytesWritten += len.toLong()
                onWritten(bytesWritten)
            }
        }
    }

    private fun resolveSubfolderFile(baseDir: File, subPath: String?): File {
        if (subPath.isNullOrBlank()) return baseDir
        var current = baseDir
        for (segment in subPath.split('/')) {
            if (segment.isNotEmpty()) {
                current = File(current, segment)
            }
        }
        return current
    }

    private fun resolveOrCreateSubfolderDoc(rootDoc: DocumentFile, subPath: String?): DocumentFile {
        if (subPath.isNullOrBlank()) return rootDoc
        var current = rootDoc
        for (segment in subPath.split('/')) {
            if (segment.isEmpty()) continue
            val existing = current.findFile(segment)?.takeIf { it.isDirectory }
                ?: current.listFiles().firstOrNull { it.isDirectory && it.name.equals(segment, ignoreCase = true) }
            current = existing
                ?: current.createDirectory(segment)
                ?: throw IOException("Could not open subfolder $segment.")
        }
        return current
    }

    private fun inferCloudKindFromUrl(rawUrl: String): RootKind? =
        providerRegistry.classifyUrl(rawUrl)?.provider?.rootKind

    private fun downloadHeadersFor(track: Track, stream: ResolvedStream): Map<String, String> {
        val provider = providerRegistry.providerForTrack(track, null)
        if (provider != null) {
            return provider.downloadHeaders(stream)
        }
        if (track.cloudSource != CloudSource.YOUTUBE) return stream.headers
        val hasRange = stream.headers.keys.any { it.equals("Range", ignoreCase = true) }
        return if (hasRange) stream.headers else stream.headers + ("Range" to "bytes=0-")
    }

    private suspend fun <T> withStreamRetry(
        track: Track,
        root: LibraryRoot?,
        qualityOverride: String? = null,
        onBytesProgress: ((ByteTransferProgress) -> Unit)? = null,
        block: suspend (ResolvedStream) -> T,
    ): T {
        val firstStream = cloudResolver.resolve(
            track = track,
            root = root,
            forceRefresh = false,
            purpose = CloudStreamPurpose.DOWNLOAD,
            requestedQualityId = qualityOverride,
        )
        return try {
            block(firstStream)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            onBytesProgress?.invoke(ByteTransferProgress(0L, null, 0.0))
            cloudResolver.invalidate(track, root)
            val refreshed = cloudResolver.resolve(
                track = track,
                root = root,
                forceRefresh = true,
                purpose = CloudStreamPurpose.DOWNLOAD,
                requestedQualityId = qualityOverride,
            )
            block(refreshed)
        }
    }

    companion object {
        const val MANIFEST_FILE_NAME = ".musico-cloud.json"
        private const val SAF_MANIFEST_FILE_NAME = "musico-cloud.json"
        private val manifestJson = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
        private val INVALID_FILENAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
        private val MULTI_WHITESPACE = Regex("\\s+")
        private const val MAX_BASENAME_LENGTH = 120

        fun loadManifest(dir: File): CloudFolderManifest? {
            val file = listOf(MANIFEST_FILE_NAME, SAF_MANIFEST_FILE_NAME)
                .map { File(dir, it) }
                .firstOrNull { it.exists() && it.isFile } ?: return null
            return runCatching {
                manifestJson.decodeFromString<CloudFolderManifest>(file.readText(Charsets.UTF_8))
            }.getOrNull()
        }

        fun saveManifest(dir: File, manifest: CloudFolderManifest) {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, MANIFEST_FILE_NAME)
            val tmp = File(dir, ".$MANIFEST_FILE_NAME.tmp")
            tmp.writeText(manifestJson.encodeToString(manifest), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }

        fun loadManifest(context: Context, dirDoc: DocumentFile): CloudFolderManifest? {
            val doc = dirDoc.listFiles().firstOrNull {
                it.name == MANIFEST_FILE_NAME || it.name == SAF_MANIFEST_FILE_NAME
            } ?: return null
            return runCatching {
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    val text = input.readBytes().toString(Charsets.UTF_8)
                    manifestJson.decodeFromString<CloudFolderManifest>(text)
                }
            }.getOrNull()
        }

        fun saveManifest(context: Context, dirDoc: DocumentFile, manifest: CloudFolderManifest) {
            val payload = manifestJson.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            dirDoc.listFiles()
                .filter { it.name == MANIFEST_FILE_NAME || it.name == SAF_MANIFEST_FILE_NAME }
                .forEach { runCatching { it.delete() } }
            val created = dirDoc.createFile("application/octet-stream", MANIFEST_FILE_NAME)
                ?: dirDoc.createFile("application/octet-stream", SAF_MANIFEST_FILE_NAME)
                ?: throw IOException("Could not create cloud manifest file.")
            context.contentResolver.openOutputStream(created.uri)?.use { out ->
                out.write(payload)
                out.flush()
            } ?: throw IOException("Could not write cloud manifest file.")
        }

        fun sanitizeFileName(raw: String, fallback: String = "track"): String {
            val safeFallback = cleanSegment(fallback).ifBlank { "track" }
            val cleaned = cleanSegment(raw)
            if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
                return safeFallback
            }
            return cleaned
        }

        private fun cleanSegment(raw: String): String {
            var s = INVALID_FILENAME_CHARS.replace(raw, "_")
            s = MULTI_WHITESPACE.replace(s, " ")
            s = s.trim { it.isWhitespace() || it == '.' }
            if (s.length > MAX_BASENAME_LENGTH) {
                s = s.substring(0, MAX_BASENAME_LENGTH).trim { it.isWhitespace() || it == '.' }
            }
            return s
        }

        fun uniqueFileName(existingNames: Set<String>, baseName: String, ext: String): String {
            val cleanBase = sanitizeFileName(baseName, "track")
            val cleanExt = ext.trim().trimStart('.').lowercase()
            val suffix = if (cleanExt.isNotEmpty()) ".$cleanExt" else ""
            val lowerExisting = existingNames.mapTo(HashSet(existingNames.size)) { it.lowercase() }

            val first = "$cleanBase$suffix"
            if (first.lowercase() !in lowerExisting) {
                return first
            }

            var index = 2
            while (true) {
                val candidate = "$cleanBase ($index)$suffix"
                if (candidate.lowercase() !in lowerExisting) {
                    return candidate
                }
                index += 1
            }
        }

        internal fun cloudKindForSource(source: String): RootKind = when {
            source == CloudSource.YOUTUBE -> RootKind.CLOUD_YOUTUBE
            source == CloudSource.BILIBILI -> RootKind.CLOUD_BILIBILI
            source == CloudSource.GOOGLE_DRIVE -> RootKind.CLOUD_GOOGLE_DRIVE
            source.startsWith("ext.") -> RootKind.CLOUD_EXTENSION
            else -> RootKind.CLOUD_BILIBILI
        }

        internal fun sourceSlugForKind(kind: RootKind): String = when (kind) {
            RootKind.CLOUD_GOOGLE_DRIVE -> CloudSource.GOOGLE_DRIVE
            else -> kind.name.lowercase()
        }

        internal fun extensionAndMimeFor(stream: ResolvedStream): Pair<String, String> {
            val rawMime = stream.mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            return when {
                rawMime.contains("webm") -> "webm" to "audio/webm"
                rawMime.contains("opus") -> "opus" to "audio/opus"
                rawMime.contains("ogg") -> "ogg" to "audio/ogg"
                rawMime.contains("flac") -> "flac" to "audio/flac"
                rawMime.contains("mpeg") || rawMime.contains("mp3") -> "mp3" to "audio/mpeg"
                rawMime.contains("aac") -> "aac" to "audio/aac"
                rawMime.contains("wav") -> "wav" to "audio/wav"
                rawMime.contains("aiff") || rawMime.contains("aif") -> "aiff" to "audio/aiff"
                rawMime.contains("alac") -> "alac" to "audio/alac"
                rawMime.contains("caf") -> "caf" to "audio/x-caf"
                rawMime.contains("mp4") || rawMime.contains("m4a") -> "m4a" to "audio/mp4"
                !stream.fileExtension.isNullOrBlank() -> {
                    val ext = stream.fileExtension.trim().trimStart('.').lowercase()
                    val mime = GoogleDriveClient.mimeTypeForExtension(ext) ?: "audio/$ext"
                    ext to mime
                }
                else -> extensionFromUrl(stream.url)
            }
        }

        private fun extensionFromUrl(url: String): Pair<String, String> {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return when {
                path.endsWith(".webm") -> "webm" to "audio/webm"
                path.endsWith(".opus") -> "opus" to "audio/opus"
                path.endsWith(".ogg") -> "ogg" to "audio/ogg"
                path.endsWith(".flac") -> "flac" to "audio/flac"
                path.endsWith(".mp3") -> "mp3" to "audio/mpeg"
                path.endsWith(".aac") -> "aac" to "audio/aac"
                path.endsWith(".wav") -> "wav" to "audio/wav"
                path.endsWith(".aiff") -> "aiff" to "audio/aiff"
                path.endsWith(".aif") -> "aif" to "audio/aiff"
                path.endsWith(".alac") -> "alac" to "audio/alac"
                path.endsWith(".caf") -> "caf" to "audio/x-caf"
                else -> "m4a" to "audio/mp4"
            }
        }
    }
}
