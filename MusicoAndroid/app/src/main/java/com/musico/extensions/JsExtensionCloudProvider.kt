package com.musico.extensions

import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudPlaylistResult
import com.musico.cloud.CloudQualityOption
import com.musico.cloud.CloudSourceException
import com.musico.cloud.CloudStreamPurpose
import com.musico.cloud.CloudTrackMetadata
import com.musico.cloud.GoogleDriveClient
import com.musico.cloud.ResolvedStream
import com.musico.cloud.extractCloudSourceInput
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

class JsExtensionCloudProvider(
    val extension: InstalledExtension,
    private val runtime: QuickJsExtensionRuntime,
) : CloudSourceProvider {

    private data class FolderCrawlNode(
        val id: String,
        val name: String,
        val pathSegments: List<String>,
        val folderPath: String,
        val depth: Int,
        val extra: JsonObject? = null,
    )

    private val compiledPatterns: List<Regex> = extension.manifest.urlPatterns.map { Regex(it) }

    override val providerId: String = extension.providerId
    override val rootKind: RootKind = RootKind.CLOUD_EXTENSION
    override val cloudSourceSlug: String = extension.providerId
    override val displayName: String = extension.manifest.name
    override val badgeLabel: String = extension.manifest.effectiveBadgeLabel
    override val isBuiltin: Boolean = false
    override val supportsTree: Boolean = ExtensionCapability.TREE in extension.manifest.capabilities
    override val supportsCookie: Boolean = extension.manifest.supportsCookieAuth
    override val usesFlatCloudListing: Boolean = !supportsTree
    override val supportsCollections: Boolean =
        ExtensionCapability.PLAYLIST in extension.manifest.capabilities ||
            ExtensionCapability.TREE in extension.manifest.capabilities
    override val supportsStream: Boolean = ExtensionCapability.STREAM in extension.manifest.capabilities
    override val availableQualities: List<CloudQualityOption> =
        extension.manifest.qualities.map { it.toCloudQualityOption() }
    override val defaultStreamQualityId: String? =
        extension.manifest.effectiveDefaultStreamQuality
    override val defaultDownloadQualityId: String? =
        extension.manifest.effectiveDefaultDownloadQuality

    override fun matchesUrlPatternFast(rawInput: String): Boolean {
        val extracted = extractCloudSourceInput(rawInput)
        val raw = rawInput.trim()
        return compiledPatterns.any { regex ->
            (extracted.isNotEmpty() && regex.containsMatchIn(extracted)) ||
                (raw.isNotEmpty() && regex.containsMatchIn(raw))
        }
    }

    override fun canHandle(rawInput: String): CloudUrlMatch? {
        if (!matchesUrlPatternFast(rawInput)) return null
        val extracted = extractCloudSourceInput(rawInput).ifEmpty { rawInput.trim() }
        val result = runCatching {
            runBlocking(Dispatchers.IO) {
                runtime.canHandle(extension, rawText = rawInput.trim(), extractedUrl = extracted)
            }
        }.getOrNull() ?: return null
        if (!result.matched) return null
        return CloudUrlMatch(
            normalizedUrl = result.normalizedUrl?.trim()?.takeIf { it.isNotEmpty() } ?: extracted,
            remoteId = result.remoteId?.trim()?.takeIf { it.isNotEmpty() },
            kind = result.kind?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult {
        if (ExtensionCapability.PLAYLIST !in extension.manifest.capabilities &&
            ExtensionCapability.TREE !in extension.manifest.capabilities
        ) {
            throw CloudSourceException(
                CloudErrorCode.INVALID_URL,
                "${extension.manifest.name} does not support playlist or folder collections.",
            )
        }
        if (supportsTree) {
            val collected = mutableListOf<CloudTrackMetadata>()
            val result = crawlProgressive(
                sourceUrl = sourceUrl,
                cookie = cookie,
                onBatch = { batch -> collected.addAll(batch.batchTracks) },
            )
            return result.copy(
                tracks = collected.mapIndexed { idx, track ->
                    track.copy(
                        album = track.album.ifBlank { result.title },
                        trackNumber = track.trackNumber ?: (idx + 1),
                    )
                },
            )
        }

        val effectiveCookie = isolateCookie(cookie)
        val tracks = mutableListOf<CloudTrackMetadata>()
        val seenCursors = HashSet<String>()
        var cursor: String? = null
        var remoteId = sourceUrl
        var title = extension.manifest.name

        for (page in 0 until MAX_PAGES) {
            val res = runtime.fetchCollection(
                extension = extension,
                url = sourceUrl,
                cookie = effectiveCookie,
                cursor = cursor,
            )
            if (page == 0) {
                if (res.remoteId.isNotBlank()) remoteId = res.remoteId
                if (res.title.isNotBlank()) title = res.title
            }
            for (t in res.tracks) {
                tracks.add(mapTrack(t, defaultAlbum = title, defaultFolderPath = "", indexFallback = tracks.size + 1))
            }
            val next = res.nextCursor?.trim()?.takeIf { it.isNotEmpty() } ?: break
            if (!seenCursors.add(next)) break
            cursor = next
        }

        return CloudPlaylistResult(
            source = providerId,
            remoteId = remoteId,
            title = title,
            tracks = tracks,
        )
    }

    override suspend fun crawlProgressive(
        sourceUrl: String,
        cookie: String?,
        priorityPathProvider: () -> String?,
        onBatch: suspend (CloudTreeBatch) -> Unit,
    ): CloudPlaylistResult {
        val effectiveCookie = isolateCookie(cookie)
        val rootTracks = mutableListOf<CloudTrackMetadata>()
        val rootSubfolders = mutableListOf<ExtSubfolder>()
        val seenCursors = HashSet<String>()
        var cursor: String? = null
        var remoteId = sourceUrl
        var title = extension.manifest.name

        for (page in 0 until MAX_PAGES) {
            val res = runtime.fetchCollection(
                extension = extension,
                url = sourceUrl,
                cookie = effectiveCookie,
                cursor = cursor,
            )
            if (page == 0) {
                if (res.remoteId.isNotBlank()) remoteId = res.remoteId
                if (res.title.isNotBlank()) title = res.title
            }
            for (t in res.tracks) {
                rootTracks.add(mapTrack(t, defaultAlbum = title, defaultFolderPath = "", indexFallback = rootTracks.size + 1))
            }
            rootSubfolders.addAll(res.subfolders)
            val next = res.nextCursor?.trim()?.takeIf { it.isNotEmpty() } ?: break
            if (!seenCursors.add(next)) break
            cursor = next
        }

        val visitedFolders = mutableSetOf<String>()
        val pendingQueue = ArrayList<FolderCrawlNode>()
        val knownFolderPaths = LinkedHashSet<String>()
        val collectedTracks = ArrayList<CloudTrackMetadata>(rootTracks)

        for (sub in rootSubfolders) {
            if (visitedFolders.size >= MAX_FOLDERS) break
            val node = toCrawlNode(parentSegments = emptyList(), depth = 1, sub = sub)
            val visitKey = "${node.id}\u001F${node.folderPath}"
            if (visitedFolders.add(visitKey)) {
                pendingQueue.add(node)
                if (node.folderPath.isNotEmpty()) {
                    knownFolderPaths.add(node.folderPath)
                }
            }
        }

        val initialComplete = pendingQueue.isEmpty()
        onBatch(
            CloudTreeBatch(
                rootRemoteId = remoteId,
                rootTitle = title,
                batchTracks = rootTracks,
                knownFolderPaths = knownFolderPaths.toSet(),
                completedFolders = 1,
                totalDiscoveredFolders = 1 + pendingQueue.size,
                tracksSoFar = collectedTracks.size,
                isComplete = initialComplete,
            )
        )

        if (initialComplete || !supportsTree) {
            return CloudPlaylistResult(
                source = providerId,
                remoteId = remoteId,
                title = title,
                tracks = collectedTracks.mapIndexed { idx, track ->
                    track.copy(
                        album = track.album.ifBlank { title },
                        trackNumber = track.trackNumber ?: (idx + 1),
                    )
                },
            )
        }

        val stateMutex = Mutex()
        val emitMutex = Mutex()
        val signal = Channel<Unit>(Channel.UNLIMITED)
        var inFlight = 0
        var completedFolders = 1

        coroutineScope {
            val workerCount = minOf(MAX_CONCURRENT_WORKERS, pendingQueue.size.coerceAtLeast(1))
            repeat(workerCount) {
                launch {
                    while (true) {
                        val node: FolderCrawlNode? = stateMutex.withLock {
                            if (pendingQueue.isEmpty()) {
                                if (inFlight == 0) {
                                    signal.close()
                                    return@launch
                                }
                                null
                            } else {
                                val nextIdx = pickNextFolderIndex(pendingQueue, priorityPathProvider())
                                inFlight += 1
                                pendingQueue.removeAt(nextIdx)
                            }
                        }
                        if (node == null) {
                            val recv = signal.receiveCatching()
                            if (recv.isClosed) return@launch
                            continue
                        }

                        val folderResult = try {
                            fetchFolderAllPages(node, effectiveCookie, defaultAlbum = node.name.ifBlank { title })
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            null
                        }

                        var batchToEmit: CloudTreeBatch? = null
                        var wakeWorkers = 0
                        var closeSignal = false

                        stateMutex.withLock {
                            inFlight -= 1
                            completedFolders += 1

                            val batchTracks = folderResult?.first.orEmpty()
                            collectedTracks.addAll(batchTracks)

                            val discoveredChildren = folderResult?.second.orEmpty()
                            if (node.depth < MAX_FOLDER_DEPTH) {
                                for (child in discoveredChildren) {
                                    if (visitedFolders.size >= MAX_FOLDERS) break
                                    val childNode = toCrawlNode(
                                        parentSegments = node.pathSegments,
                                        depth = node.depth + 1,
                                        sub = child,
                                    )
                                    val visitKey = "${childNode.id}\u001F${childNode.folderPath}"
                                    if (visitedFolders.add(visitKey)) {
                                        pendingQueue.add(childNode)
                                        if (childNode.folderPath.isNotEmpty()) {
                                            knownFolderPaths.add(childNode.folderPath)
                                        }
                                        wakeWorkers += 1
                                    }
                                }
                            }

                            val doneNow = pendingQueue.isEmpty() && inFlight == 0
                            if (doneNow) {
                                closeSignal = true
                            }
                            if (batchTracks.isNotEmpty() || wakeWorkers > 0 || doneNow) {
                                batchToEmit = CloudTreeBatch(
                                    rootRemoteId = remoteId,
                                    rootTitle = title,
                                    batchTracks = batchTracks,
                                    knownFolderPaths = knownFolderPaths.toSet(),
                                    completedFolders = completedFolders,
                                    totalDiscoveredFolders = 1 + visitedFolders.size,
                                    tracksSoFar = collectedTracks.size,
                                    isComplete = doneNow,
                                )
                            }
                        }

                        if (wakeWorkers > 0) {
                            repeat(minOf(wakeWorkers, MAX_CONCURRENT_WORKERS)) {
                                signal.trySend(Unit)
                            }
                        }
                        if (closeSignal) {
                            signal.close()
                        }
                        batchToEmit?.let { batch ->
                            emitMutex.withLock {
                                onBatch(batch)
                            }
                        }
                        if (closeSignal) {
                            return@launch
                        }
                    }
                }
            }
        }

        return CloudPlaylistResult(
            source = providerId,
            remoteId = remoteId,
            title = title,
            tracks = collectedTracks.mapIndexed { idx, track ->
                track.copy(
                    album = track.album.ifBlank { title },
                    trackNumber = track.trackNumber ?: (idx + 1),
                )
            },
        )
    }

    private suspend fun fetchFolderAllPages(
        node: FolderCrawlNode,
        cookie: String?,
        defaultAlbum: String,
    ): Pair<List<CloudTrackMetadata>, List<ExtSubfolder>> {
        val tracks = mutableListOf<CloudTrackMetadata>()
        val subfolders = mutableListOf<ExtSubfolder>()
        val seenCursors = HashSet<String>()
        var cursor: String? = null

        for (page in 0 until MAX_PAGES) {
            val res = runtime.fetchFolder(
                extension = extension,
                folderId = node.id,
                pathSegments = node.pathSegments,
                depth = node.depth,
                cookie = cookie,
                cursor = cursor,
                extra = node.extra,
            )
            for (t in res.tracks) {
                tracks.add(
                    mapTrack(
                        track = t,
                        defaultAlbum = defaultAlbum,
                        defaultFolderPath = node.folderPath,
                        indexFallback = tracks.size + 1,
                    )
                )
            }
            subfolders.addAll(res.subfolders)
            val next = res.nextCursor?.trim()?.takeIf { it.isNotEmpty() } ?: break
            if (!seenCursors.add(next)) break
            cursor = next
        }
        return tracks to subfolders
    }

    override suspend fun resolveStream(
        remoteTrackId: String,
        relativePath: String?,
        cookie: String?,
        forceRefresh: Boolean,
        quality: String?,
        purpose: CloudStreamPurpose,
    ): ResolvedStream {
        if (ExtensionCapability.STREAM !in extension.manifest.capabilities) {
            throw CloudSourceException(
                CloudErrorCode.NO_PLAYABLE_STREAM,
                "${extension.manifest.name} does not support audio stream resolution.",
            )
        }
        val cleanQuality = quality?.trim()?.takeIf { it.isNotEmpty() }
        val res = runtime.resolveStream(
            extension = extension,
            remoteTrackId = remoteTrackId,
            relativePath = relativePath,
            cookie = isolateCookie(cookie),
            forceRefresh = forceRefresh,
            quality = cleanQuality,
            purpose = purpose,
        )
        val ext = res.fileExtension?.trim()?.trimStart('.')?.lowercase()?.takeIf { it.isNotEmpty() }
        val mime = res.mimeType?.trim()?.takeIf { it.isNotEmpty() }
            ?: GoogleDriveClient.mimeTypeForExtension(ext)
        val actualQuality = res.actualQuality?.trim()?.takeIf { it.isNotEmpty() } ?: cleanQuality
        val resolvedLabel = res.qualityLabel?.trim()?.takeIf { it.isNotEmpty() }
            ?: actualQuality?.let { qId -> availableQualities.firstOrNull { it.id == qId }?.compactLabel }
        return ResolvedStream(
            url = res.url,
            headers = res.headers.orEmpty(),
            mimeType = mime,
            expiresAtEpochMs = res.expiresAtMs ?: 0L,
            contentLength = res.contentLength,
            fileExtension = ext,
            fallbackUrls = res.fallbackUrls.orEmpty(),
            actualQuality = actualQuality,
            qualityLabel = resolvedLabel,
            bitrateKbps = res.bitrateKbps?.takeIf { it > 0 },
        )
    }

    private fun isolateCookie(cookie: String?): String? =
        if (supportsCookie) cookie?.trim()?.takeIf { it.isNotEmpty() } else null

    private fun toCrawlNode(
        parentSegments: List<String>,
        depth: Int,
        sub: ExtSubfolder,
    ): FolderCrawlNode {
        val rawSegments = sub.pathSegments
            ?.map { sanitizePathSegment(it, sub.id) }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: (parentSegments + sanitizePathSegment(sub.name, sub.id))
        val normPath = LibraryRoot.normalizeFolderPath(rawSegments.joinToString("/"))
        return FolderCrawlNode(
            id = sub.id,
            name = sub.name.ifBlank { rawSegments.lastOrNull().orEmpty() },
            pathSegments = rawSegments,
            folderPath = normPath,
            depth = depth,
            extra = sub.extra,
        )
    }

    private fun mapTrack(
        track: ExtTrack,
        defaultAlbum: String,
        defaultFolderPath: String,
        indexFallback: Int,
    ): CloudTrackMetadata {
        val normRel = track.relativePath
            ?.let { LibraryRoot.normalizeFolderPath(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                val safeName = sanitizePathSegment(track.title, track.remoteTrackId)
                if (defaultFolderPath.isEmpty()) safeName else "$defaultFolderPath/$safeName"
            }
        return CloudTrackMetadata(
            remoteTrackId = track.remoteTrackId,
            title = track.title.ifBlank { track.remoteTrackId },
            artist = track.artist?.takeIf { it.isNotBlank() } ?: extension.manifest.name,
            album = track.album?.takeIf { it.isNotBlank() } ?: defaultAlbum,
            durationSec = track.durationSec?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
            artworkUrl = track.artworkUrl?.trim()?.takeIf { it.isNotEmpty() },
            trackNumber = track.trackNumber ?: indexFallback,
            relativePath = normRel,
        )
    }

    private fun pickNextFolderIndex(
        pendingQueue: List<FolderCrawlNode>,
        rawPriorityPath: String?,
    ): Int {
        val priorityPath = rawPriorityPath?.let { LibraryRoot.normalizeFolderPath(it) }?.takeIf { it.isNotEmpty() }
            ?: return 0
        pendingQueue.indexOfFirst { it.folderPath == priorityPath }.takeIf { it >= 0 }?.let { return it }
        pendingQueue.indexOfFirst { priorityPath.startsWith("${it.folderPath}/") }.takeIf { it >= 0 }?.let { return it }
        pendingQueue.indexOfFirst { it.folderPath.startsWith("$priorityPath/") }.takeIf { it >= 0 }?.let { return it }
        return 0
    }

    private fun sanitizePathSegment(raw: String, fallback: String): String =
        GoogleDriveClient.sanitizePathSegment(raw, fallback)

    companion object {
        private const val MAX_PAGES = 100
        private const val MAX_FOLDER_DEPTH = 12
        private const val MAX_FOLDERS = 2000
        private const val MAX_CONCURRENT_WORKERS = 1
    }
}
