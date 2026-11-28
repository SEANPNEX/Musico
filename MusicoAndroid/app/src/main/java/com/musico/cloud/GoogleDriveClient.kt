package com.musico.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

sealed interface GoogleDriveTarget {
    val id: String
    val resourceKey: String?

    data class Folder(override val id: String, override val resourceKey: String? = null) : GoogleDriveTarget
    data class File(override val id: String, override val resourceKey: String? = null) : GoogleDriveTarget
    data class Open(override val id: String, override val resourceKey: String? = null) : GoogleDriveTarget
}

data class DriveFolderNode(
    val folderId: String,
    val resourceKey: String?,
    val folderPath: String,
    val title: String,
    val depth: Int,
)

data class DriveSingleFolderResult(
    val folderId: String,
    val folderPath: String,
    val title: String,
    val tracks: List<CloudTrackMetadata>,
    val subfolders: List<DriveFolderNode>,
)

data class DriveCrawlProgress(
    val rootTitle: String,
    val completedFolders: Int,
    val totalDiscoveredFolders: Int,
    val tracksSoFar: Int,
    val isComplete: Boolean,
)

data class DriveCrawlBatch(
    val rootRemoteId: String,
    val rootTitle: String,
    val batchTracks: List<CloudTrackMetadata>,
    val knownFolderPaths: Set<String>,
    val progress: DriveCrawlProgress,
)

class GoogleDriveClient(
    private val httpClient: CloudHttpClient = UrlConnectionCloudHttpClient(),
    private val oauthTokenProvider: (suspend (forceRefresh: Boolean) -> String?)? = null,
) {
    internal data class DriveEntry(
        val id: String,
        val name: String,
        val mimeType: String?,
        val resourceKey: String? = null,
        val isFolder: Boolean = false,
    )

    internal data class DriveIvdPage(
        val entries: List<DriveEntry>,
        val nextPageToken: String? = null,
    )

    internal data class DecodedTrackId(
        val fileId: String,
        val extension: String?,
        val resourceKey: String?,
    )

    @Volatile
    private var cachedApiKey: String? = null

    suspend fun fetchPlaylist(sourceUrl: String, cookie: String? = null): CloudPlaylistResult {
        val collected = mutableListOf<CloudTrackMetadata>()
        val result = crawlProgressive(
            sourceUrl = sourceUrl,
            cookie = cookie,
            onBatch = { batch ->
                collected.addAll(batch.batchTracks)
            },
        )
        return result.copy(
            tracks = collected.mapIndexed { idx, track ->
                track.copy(
                    album = track.album.ifBlank { result.title },
                    trackNumber = idx + 1,
                )
            },
        )
    }

    suspend fun crawlProgressive(
        sourceUrl: String,
        cookie: String? = null,
        priorityPathProvider: () -> String? = { null },
        onBatch: suspend (DriveCrawlBatch) -> Unit,
    ): CloudPlaylistResult {
        val target = parseTarget(sourceUrl)
            ?: throw IOException("Not a recognized Google Drive URL.")

        return when (target) {
            is GoogleDriveTarget.Folder -> crawlFolderProgressively(
                rootFolderId = target.id,
                rootResourceKey = target.resourceKey,
                cookie = cookie,
                priorityPathProvider = priorityPathProvider,
                onBatch = onBatch,
            )
            is GoogleDriveTarget.File -> {
                val single = fetchSingleFile(target.id, target.resourceKey, cookie)
                onBatch(
                    DriveCrawlBatch(
                        rootRemoteId = single.remoteId,
                        rootTitle = single.title,
                        batchTracks = single.tracks,
                        knownFolderPaths = emptySet(),
                        progress = DriveCrawlProgress(
                            rootTitle = single.title,
                            completedFolders = 1,
                            totalDiscoveredFolders = 1,
                            tracksSoFar = single.tracks.size,
                            isComplete = true,
                        ),
                    )
                )
                single
            }
            is GoogleDriveTarget.Open -> crawlOpenTarget(
                itemId = target.id,
                resourceKey = target.resourceKey,
                cookie = cookie,
                priorityPathProvider = priorityPathProvider,
                onBatch = onBatch,
            )
        }
    }

    private suspend fun crawlOpenTarget(
        itemId: String,
        resourceKey: String?,
        cookie: String?,
        priorityPathProvider: () -> String?,
        onBatch: suspend (DriveCrawlBatch) -> Unit,
    ): CloudPlaylistResult {
        fetchAuthorizedFileMetadata(itemId, resourceKey)?.let { meta ->
            if (meta.isFolder) {
                return crawlFolderProgressively(
                    rootFolderId = meta.id,
                    rootResourceKey = meta.resourceKey ?: resourceKey,
                    cookie = cookie,
                    prefetchedFolderResponse = null,
                    priorityPathProvider = priorityPathProvider,
                    onBatch = onBatch,
                )
            } else {
                val single = buildSingleFileResult(
                    fileId = meta.id,
                    resourceKey = meta.resourceKey ?: resourceKey,
                    fileName = meta.name,
                    detectedMime = meta.mimeType,
                )
                onBatch(
                    DriveCrawlBatch(
                        rootRemoteId = single.remoteId,
                        rootTitle = single.title,
                        batchTracks = single.tracks,
                        knownFolderPaths = emptySet(),
                        progress = DriveCrawlProgress(
                            rootTitle = single.title,
                            completedFolders = 1,
                            totalDiscoveredFolders = 1,
                            tracksSoFar = single.tracks.size,
                            isComplete = true,
                        ),
                    )
                )
                return single
            }
        }

        val openUrl = buildDriveOpenUrl(itemId, resourceKey)
        val resp = getWithRetry(openUrl, headers = baseHeaders(cookie))
        checkAccessAndStatus(resp, "Google Drive item")

        val finalTarget = parseTarget(resp.finalUrl)
        if (finalTarget is GoogleDriveTarget.Folder) {
            val rk = finalTarget.resourceKey ?: resourceKey
            return crawlFolderProgressively(
                rootFolderId = finalTarget.id,
                rootResourceKey = rk,
                cookie = cookie,
                prefetchedFolderResponse = resp,
                priorityPathProvider = priorityPathProvider,
                onBatch = onBatch,
            )
        }
        if (finalTarget is GoogleDriveTarget.File) {
            val rk = finalTarget.resourceKey ?: resourceKey
            val single = fetchSingleFile(finalTarget.id, rk, cookie, prefetchedViewResponse = resp)
            onBatch(
                DriveCrawlBatch(
                    rootRemoteId = single.remoteId,
                    rootTitle = single.title,
                    batchTracks = single.tracks,
                    knownFolderPaths = emptySet(),
                    progress = DriveCrawlProgress(
                        rootTitle = single.title,
                        completedFolders = 1,
                        totalDiscoveredFolders = 1,
                        tracksSoFar = single.tracks.size,
                        isComplete = true,
                    ),
                )
            )
            return single
        }

        val html = resp.bodyText
        if (extractDriveIvdJson(html) != null ||
            html.contains("flip-entry", ignoreCase = true) ||
            html.contains(FOLDER_MIME_TYPE, ignoreCase = true)
        ) {
            return crawlFolderProgressively(
                rootFolderId = itemId,
                rootResourceKey = resourceKey,
                cookie = cookie,
                prefetchedFolderResponse = resp,
                priorityPathProvider = priorityPathProvider,
                onBatch = onBatch,
            )
        }
        val single = fetchSingleFile(itemId, resourceKey, cookie, prefetchedViewResponse = resp)
        onBatch(
            DriveCrawlBatch(
                rootRemoteId = single.remoteId,
                rootTitle = single.title,
                batchTracks = single.tracks,
                knownFolderPaths = emptySet(),
                progress = DriveCrawlProgress(
                    rootTitle = single.title,
                    completedFolders = 1,
                    totalDiscoveredFolders = 1,
                    tracksSoFar = single.tracks.size,
                    isComplete = true,
                ),
            )
        )
        return single
    }

    suspend fun fetchSingleFolder(
        folderId: String,
        resourceKey: String?,
        cookie: String?,
        folderPath: String = "",
        explicitTitle: String? = null,
        depth: Int = 0,
        prefetchedResponse: CloudHttpResponse? = null,
    ): DriveSingleFolderResult {
        val (folderTitle, entries) = fetchFolderPage(
            folderId = folderId,
            resourceKey = resourceKey,
            cookie = cookie,
            fallbackTitle = explicitTitle,
            prefetchedResponse = prefetchedResponse,
        )
        val albumName = if (depth == 0) {
            folderTitle
        } else {
            explicitTitle?.takeIf { it.isNotBlank() } ?: folderTitle
        }

        val normFolderPath = normalizeFolderPath(folderPath)
        val usedFileNamesLower = HashSet<String>()
        val usedSubfolderNamesLower = HashSet<String>()
        val tracks = mutableListOf<CloudTrackMetadata>()
        val subfolders = mutableListOf<DriveFolderNode>()

        for (entry in entries) {
            if (entry.isFolder || entry.mimeType.equals(FOLDER_MIME_TYPE, ignoreCase = true)) {
                val subSegment = disambiguateSegment(
                    usedLower = usedSubfolderNamesLower,
                    rawName = entry.name,
                    fallback = entry.id,
                    preserveExtension = false,
                )
                val childPath = if (normFolderPath.isEmpty()) subSegment else "$normFolderPath/$subSegment"
                subfolders.add(
                    DriveFolderNode(
                        folderId = entry.id,
                        resourceKey = entry.resourceKey ?: resourceKey,
                        folderPath = childPath,
                        title = entry.name.trim().ifEmpty { subSegment },
                        depth = depth + 1,
                    )
                )
                continue
            }
            if (!isAudioEntry(entry.name, entry.mimeType)) continue
            val ext = resolveExtension(entry.name, entry.mimeType)
            val remoteTrackId = encodeRemoteTrackId(entry.id, ext, entry.resourceKey ?: resourceKey)
            val cleanTitle = stripAudioExtension(entry.name).ifBlank { entry.id }
            val fileName = disambiguateFileName(
                usedLower = usedFileNamesLower,
                rawName = entry.name,
                resolvedExt = ext,
                fallbackStem = entry.id,
            )
            val relPath = if (normFolderPath.isEmpty()) fileName else "$normFolderPath/$fileName"
            tracks.add(
                CloudTrackMetadata(
                    remoteTrackId = remoteTrackId,
                    title = cleanTitle,
                    artist = DEFAULT_ARTIST,
                    album = albumName,
                    durationSec = 0.0,
                    trackNumber = tracks.size + 1,
                    relativePath = relPath,
                )
            )
        }

        return DriveSingleFolderResult(
            folderId = folderId,
            folderPath = normFolderPath,
            title = folderTitle,
            tracks = tracks,
            subfolders = subfolders,
        )
    }

    private suspend fun crawlFolderProgressively(
        rootFolderId: String,
        rootResourceKey: String?,
        cookie: String?,
        prefetchedFolderResponse: CloudHttpResponse? = null,
        priorityPathProvider: () -> String? = { null },
        onBatch: suspend (DriveCrawlBatch) -> Unit,
    ): CloudPlaylistResult {
        val rootResult = fetchSingleFolder(
            folderId = rootFolderId,
            resourceKey = rootResourceKey,
            cookie = cookie,
            folderPath = "",
            explicitTitle = null,
            depth = 0,
            prefetchedResponse = prefetchedFolderResponse,
        )
        val rootTitle = rootResult.title.takeIf { it.isNotBlank() } ?: "Google Drive ($rootFolderId)"

        val visitedFolders = mutableSetOf(rootFolderId)
        val pendingQueue = ArrayList<DriveFolderNode>()
        val knownFolderPaths = LinkedHashSet<String>()
        val collectedTracks = ArrayList<CloudTrackMetadata>(rootResult.tracks)

        if (MAX_FOLDER_DEPTH > 0) {
            for (sub in rootResult.subfolders) {
                if (visitedFolders.size >= MAX_FOLDERS) break
                if (visitedFolders.add(sub.folderId)) {
                    pendingQueue.add(sub)
                    knownFolderPaths.add(sub.folderPath)
                }
            }
        }

        val initialComplete = pendingQueue.isEmpty()
        onBatch(
            DriveCrawlBatch(
                rootRemoteId = rootFolderId,
                rootTitle = rootTitle,
                batchTracks = rootResult.tracks,
                knownFolderPaths = knownFolderPaths.toSet(),
                progress = DriveCrawlProgress(
                    rootTitle = rootTitle,
                    completedFolders = 1,
                    totalDiscoveredFolders = 1 + pendingQueue.size,
                    tracksSoFar = collectedTracks.size,
                    isComplete = initialComplete,
                ),
            )
        )

        if (initialComplete) {
            return CloudPlaylistResult(
                source = CloudSource.GOOGLE_DRIVE,
                remoteId = rootFolderId,
                title = rootTitle,
                tracks = collectedTracks.mapIndexed { idx, track ->
                    track.copy(
                        album = track.album.ifBlank { rootTitle },
                        trackNumber = idx + 1,
                    )
                },
            )
        }

        val stateMutex = Mutex()
        val emitChannel = Channel<DriveCrawlBatch>(Channel.UNLIMITED)
        val signal = Channel<Unit>(Channel.UNLIMITED)
        var inFlight = 0
        var completedFolders = 1

        coroutineScope {
            val emitterJob = launch {
                for (batch in emitChannel) {
                    onBatch(batch)
                }
            }
            try {
                coroutineScope {
                    val workerCount = minOf(MAX_CONCURRENT_WORKERS, pendingQueue.size.coerceAtLeast(1))
                    repeat(workerCount) {
                        launch {
                            while (true) {
                                val node: DriveFolderNode? = stateMutex.withLock {
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

                                val fetched = try {
                                    fetchSingleFolder(
                                        folderId = node.folderId,
                                        resourceKey = node.resourceKey,
                                        cookie = cookie,
                                        folderPath = node.folderPath,
                                        explicitTitle = node.title,
                                        depth = node.depth,
                                        prefetchedResponse = null,
                                    )
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    null
                                }

                                var wakeWorkers = 0
                                var closeSignal = false

                                stateMutex.withLock {
                                    inFlight -= 1
                                    completedFolders += 1

                                    val batchTracks = fetched?.tracks.orEmpty()
                                    collectedTracks.addAll(batchTracks)

                                    if (fetched != null && node.depth < MAX_FOLDER_DEPTH) {
                                        for (child in fetched.subfolders) {
                                            if (visitedFolders.size >= MAX_FOLDERS) break
                                            if (visitedFolders.add(child.folderId)) {
                                                pendingQueue.add(child)
                                                knownFolderPaths.add(child.folderPath)
                                                wakeWorkers += 1
                                            }
                                        }
                                    }

                                    val doneNow = pendingQueue.isEmpty() && inFlight == 0
                                    if (doneNow) {
                                        closeSignal = true
                                    }
                                    if (batchTracks.isNotEmpty() || wakeWorkers > 0 || doneNow) {
                                        emitChannel.trySend(
                                            DriveCrawlBatch(
                                                rootRemoteId = rootFolderId,
                                                rootTitle = rootTitle,
                                                batchTracks = batchTracks,
                                                knownFolderPaths = knownFolderPaths.toSet(),
                                                progress = DriveCrawlProgress(
                                                    rootTitle = rootTitle,
                                                    completedFolders = completedFolders,
                                                    totalDiscoveredFolders = visitedFolders.size,
                                                    tracksSoFar = collectedTracks.size,
                                                    isComplete = doneNow,
                                                ),
                                            )
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
                                    return@launch
                                }
                            }
                        }
                    }
                }
            } finally {
                emitChannel.close()
            }
            emitterJob.join()
        }

        return CloudPlaylistResult(
            source = CloudSource.GOOGLE_DRIVE,
            remoteId = rootFolderId,
            title = rootTitle,
            tracks = collectedTracks.mapIndexed { idx, track ->
                track.copy(
                    album = track.album.ifBlank { rootTitle },
                    trackNumber = idx + 1,
                )
            },
        )
    }

    private fun pickNextFolderIndex(
        pendingQueue: List<DriveFolderNode>,
        rawPriorityPath: String?,
    ): Int {
        val priorityPath = rawPriorityPath?.let(::normalizeFolderPath)?.takeIf { it.isNotEmpty() } ?: return 0
        pendingQueue.indexOfFirst { it.folderPath == priorityPath }.takeIf { it >= 0 }?.let { return it }
        pendingQueue.indexOfFirst { priorityPath.startsWith("${it.folderPath}/") }.takeIf { it >= 0 }?.let { return it }
        pendingQueue.indexOfFirst { it.folderPath.startsWith("$priorityPath/") }.takeIf { it >= 0 }?.let { return it }
        return 0
    }

    private suspend fun fetchFolderPage(
        folderId: String,
        resourceKey: String?,
        cookie: String?,
        fallbackTitle: String?,
        prefetchedResponse: CloudHttpResponse?,
    ): Pair<String, List<DriveEntry>> {
        if (prefetchedResponse == null) {
            fetchAuthorizedFolderPage(folderId, resourceKey, fallbackTitle)?.let { return it }
        }

        val headers = baseHeaders(cookie)
        var title: String? = null
        var primaryAuthFailure: IOException? = null

        val primaryUrl = buildFolderUrl(folderId, resourceKey)
        val primaryResp = prefetchedResponse ?: runCatching {
            getWithRetry(primaryUrl, headers = headers)
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()

        if (primaryResp != null) {
            if (isAuthOrNotFound(primaryResp)) {
                primaryAuthFailure = buildAccessError(primaryResp)
            } else if (primaryResp.statusCode in 200..299) {
                val html = primaryResp.bodyText
                title = extractPageTitle(html)
                val ivdRaw = extractDriveIvdJson(html)
                if (ivdRaw != null) {
                    val ivdPage = parseDriveIvdPage(ivdRaw)
                    if (ivdPage != null) {
                        val resolvedTitle = title ?: fallbackTitle ?: "Google Drive ($folderId)"
                        val nextToken = ivdPage.nextPageToken
                        val shouldPaginate = nextToken != null || ivdPage.entries.size >= IVD_TRUNCATION_THRESHOLD
                        if (!shouldPaginate) {
                            return resolvedTitle to ivdPage.entries
                        }

                        val candidateKeys = buildList {
                            cachedApiKey?.takeIf { it.isNotBlank() }?.let { add(it) }
                            for (extracted in extractApiKeys(html)) {
                                if (extracted !in this) add(extracted)
                            }
                        }

                        val merged = LinkedHashMap<String, DriveEntry>()
                        for (entry in ivdPage.entries) {
                            merged.putIfAbsent(entry.id, entry)
                        }

                        val paginatedOk = paginateDriveApi(
                            folderId = folderId,
                            resourceKey = resourceKey,
                            cookie = cookie,
                            apiKeys = candidateKeys,
                            initialNextPageToken = nextToken,
                            merged = merged,
                        )
                        if (paginatedOk) {
                            return resolvedTitle to merged.values.toList()
                        }

                        // Supplement with embeddedfolderview if pagination failed to complete.
                        val embeddedResp = runCatching {
                            getWithRetry(buildEmbeddedFolderViewUrl(folderId, resourceKey), headers = headers)
                        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                        if (embeddedResp != null && embeddedResp.statusCode in 200..299 && !isAuthOrNotFound(embeddedResp)) {
                            for (entry in parseEmbeddedFolderEntries(embeddedResp.bodyText)) {
                                merged.putIfAbsent(entry.id, entry)
                            }
                        }
                        return resolvedTitle to merged.values.toList()
                    }
                }
            }
        }

        val embeddedUrl = buildEmbeddedFolderViewUrl(folderId, resourceKey)
        val embeddedResp = runCatching {
            getWithRetry(embeddedUrl, headers = headers)
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()

        if (embeddedResp != null) {
            if (isAuthOrNotFound(embeddedResp)) {
                throw buildAccessError(embeddedResp)
            }
            if (embeddedResp.statusCode in 200..299) {
                val html = embeddedResp.bodyText
                val embeddedTitle = extractPageTitle(html) ?: extractEmbeddedFolderTitle(html)
                val resolvedTitle = title ?: embeddedTitle ?: fallbackTitle ?: "Google Drive ($folderId)"
                val entries = parseEmbeddedFolderEntries(html)
                return resolvedTitle to entries
            }
        }

        if (primaryAuthFailure != null) {
            throw primaryAuthFailure
        }
        if (primaryResp != null && primaryResp.statusCode !in 200..299) {
            throw IOException("Google Drive folder request failed (HTTP ${primaryResp.statusCode}).")
        }
        if (embeddedResp != null && embeddedResp.statusCode !in 200..299) {
            throw IOException("Google Drive folder request failed (HTTP ${embeddedResp.statusCode}).")
        }
        throw IOException("Could not load Google Drive folder ($folderId).")
    }

    private suspend fun paginateDriveApi(
        folderId: String,
        resourceKey: String?,
        cookie: String?,
        apiKeys: List<String>,
        initialNextPageToken: String?,
        merged: LinkedHashMap<String, DriveEntry>,
    ): Boolean {
        if (apiKeys.isEmpty()) return false

        var activeHeaders: Map<String, String>? = null
        var useV3Only = false
        var pageToken: String? = initialNextPageToken

        for (key in apiKeys) {
            val headersForKey = buildDriveApiHeaders(folderId, resourceKey, cookie, key)
            var firstParsed: DriveIvdPage? = null
            var usedV3 = false

            val c6Resp = runCatching {
                getWithRetry(buildClients6FilesUrl(folderId, key, pageToken), headers = headersForKey)
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            if (c6Resp != null && c6Resp.statusCode in 200..299) {
                firstParsed = parseDriveApiPage(c6Resp.bodyText)
            }

            if (firstParsed == null) {
                val v3Resp = runCatching {
                    getWithRetry(buildDriveV3FilesUrl(folderId, key, pageToken), headers = headersForKey)
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                if (v3Resp != null && v3Resp.statusCode in 200..299) {
                    firstParsed = parseDriveApiPage(v3Resp.bodyText)
                    if (firstParsed != null) usedV3 = true
                }
            }

            if (firstParsed != null) {
                cachedApiKey = key
                activeHeaders = headersForKey
                useV3Only = usedV3
                for (entry in firstParsed.entries) {
                    merged.putIfAbsent(entry.id, entry)
                }
                pageToken = firstParsed.nextPageToken
                break
            }
        }

        val resolvedHeaders = activeHeaders ?: return false
        var pageCount = 1
        val seenTokens = HashSet<String>().apply {
            if (!initialNextPageToken.isNullOrBlank()) add(initialNextPageToken)
        }

        while (pageCount < MAX_API_PAGES) {
            val tok = pageToken?.takeIf { it.isNotBlank() } ?: return true
            if (!seenTokens.add(tok)) {
                return true
            }
            pageCount += 1
            var parsed: DriveIvdPage? = null

            if (!useV3Only) {
                val c6Resp = runCatching {
                    getWithRetry(buildClients6FilesUrl(folderId, resolvedHeaders["X-Goog-Api-Key"], tok), headers = resolvedHeaders)
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                if (c6Resp != null && c6Resp.statusCode in 200..299) {
                    parsed = parseDriveApiPage(c6Resp.bodyText)
                }
                if (parsed == null) {
                    useV3Only = true
                }
            }

            if (parsed == null) {
                val v3Resp = runCatching {
                    getWithRetry(buildDriveV3FilesUrl(folderId, resolvedHeaders["X-Goog-Api-Key"], tok), headers = resolvedHeaders)
                }.onFailure { if (it is CancellationException) throw it }.getOrNull()
                if (v3Resp != null && v3Resp.statusCode in 200..299) {
                    parsed = parseDriveApiPage(v3Resp.bodyText)
                }
            }

            if (parsed == null) {
                return false
            }

            for (entry in parsed.entries) {
                merged.putIfAbsent(entry.id, entry)
            }
            pageToken = parsed.nextPageToken
        }
        return true
    }

    internal fun parseDriveApiPage(rawJson: String): DriveIvdPage? {
        val obj = runCatching { rawJson.parseJsonObject() }.getOrNull() ?: return null
        val items = obj.arr("items") ?: obj.arr("files") ?: return null
        val entries = mutableListOf<DriveEntry>()
        for (item in items) {
            val entry = parseSingleIvdEntry(item) ?: continue
            entries.add(entry)
        }
        val nextToken = obj.str("nextPageToken")?.takeIf { it.isNotBlank() }
        return DriveIvdPage(entries = entries, nextPageToken = nextToken)
    }

    private suspend fun getWithRetry(
        url: String,
        headers: Map<String, String>,
    ): CloudHttpResponse {
        var attempt = 0
        while (true) {
            val resp = httpClient.get(url, headers = headers, followRedirects = true)
            val shouldRetry = resp.statusCode == 429 || resp.statusCode in 500..599
            if (!shouldRetry || attempt >= MAX_HTTP_RETRIES) {
                return resp
            }
            val retryAfterSec = headerValue(resp.headers, "Retry-After")?.trim()?.toLongOrNull()
            val delayMs = if (retryAfterSec != null && retryAfterSec >= 0L) {
                (retryAfterSec * 1000L).coerceIn(50L, 2_000L)
            } else {
                150L * (1L shl attempt)
            }
            attempt += 1
            delay(delayMs)
        }
    }

    private fun buildDriveApiHeaders(
        folderId: String,
        resourceKey: String?,
        cookie: String?,
        apiKey: String?,
    ): Map<String, String> {
        val headers = LinkedHashMap<String, String>(baseHeaders(cookie))
        headers["Referer"] = "https://drive.google.com/"
        headers["Origin"] = "https://drive.google.com"
        if (!apiKey.isNullOrBlank()) {
            headers["X-Goog-Api-Key"] = apiKey
        }
        if (!resourceKey.isNullOrBlank()) {
            headers["X-Goog-Drive-Resource-Keys"] = "$folderId/$resourceKey"
        }
        return headers
    }

    private fun buildClients6FilesUrl(
        folderId: String,
        apiKey: String?,
        pageToken: String?,
    ): String = buildString {
        val query = "'${folderId.replace("'", "\\'")}' in parents and trashed = false"
        append("https://clients6.google.com/drive/v2beta/files?openDrive=true&reason=102")
        append("&supportsAllDrives=true&includeItemsFromAllDrives=true")
        append("&maxResults=1000")
        append("&fields=").append(encodeQuery("nextPageToken,items(id,title,mimeType,resourceKey,labels(trashed))"))
        append("&q=").append(encodeQuery(query))
        if (!pageToken.isNullOrBlank()) {
            append("&pageToken=").append(encodeQuery(pageToken))
        }
        if (!apiKey.isNullOrBlank()) {
            append("&key=").append(encodeQuery(apiKey))
        }
    }

    private fun buildDriveV3FilesUrl(
        folderId: String,
        apiKey: String?,
        pageToken: String?,
    ): String = buildString {
        val query = "'${folderId.replace("'", "\\'")}' in parents and trashed = false"
        append("https://www.googleapis.com/drive/v3/files?supportsAllDrives=true&includeItemsFromAllDrives=true")
        append("&pageSize=1000")
        append("&fields=").append(encodeQuery("nextPageToken,files(id,name,mimeType,resourceKey)"))
        append("&q=").append(encodeQuery(query))
        if (!pageToken.isNullOrBlank()) {
            append("&pageToken=").append(encodeQuery(pageToken))
        }
        if (!apiKey.isNullOrBlank()) {
            append("&key=").append(encodeQuery(apiKey))
        }
    }

    private suspend fun fetchSingleFile(
        fileId: String,
        resourceKey: String?,
        cookie: String?,
        prefetchedViewResponse: CloudHttpResponse? = null,
    ): CloudPlaylistResult {
        if (prefetchedViewResponse == null) {
            fetchAuthorizedFileMetadata(fileId, resourceKey)?.let { meta ->
                return buildSingleFileResult(
                    fileId = meta.id,
                    resourceKey = meta.resourceKey ?: resourceKey,
                    fileName = meta.name,
                    detectedMime = meta.mimeType,
                )
            }
        }

        val headers = baseHeaders(cookie)
        val viewUrl = buildFileViewUrl(fileId, resourceKey)
        val viewResp = prefetchedViewResponse ?: httpClient.get(viewUrl, headers = headers, followRedirects = true)
        checkAccessAndStatus(viewResp, "Google Drive file")

        var fileName = extractPageTitle(viewResp.bodyText)
        var ext = fileName?.let { audioExtensionFromName(it) }
        var detectedMime: String? = null

        if (fileName.isNullOrBlank() || ext == null) {
            val probeHeaders = headers + ("Range" to "bytes=0-0")
            val probeUrl = buildUserContentDownloadUrl(fileId, resourceKey)
            val probeResp = runCatching {
                httpClient.get(probeUrl, headers = probeHeaders, followRedirects = true)
            }.getOrNull()
            if (probeResp != null) {
                if (isAuthOrNotFound(probeResp)) {
                    throw buildAccessError(probeResp)
                }
                if (probeResp.statusCode in 200..299) {
                    val contentDispName = parseContentDispositionFilename(
                        headerValue(probeResp.headers, "Content-Disposition")
                    )
                    if (!contentDispName.isNullOrBlank()) {
                        fileName = contentDispName
                        ext = audioExtensionFromName(contentDispName)
                    }
                    val respContentType = headerValue(probeResp.headers, "Content-Type")
                    if (!respContentType.isNullOrBlank() && !isHtmlContentType(respContentType)) {
                        detectedMime = respContentType
                        if (ext == null) {
                            ext = extensionForMimeType(respContentType)
                        }
                    }
                }
            }
        }

        val resolvedName = fileName?.takeIf { it.isNotBlank() } ?: "Google Drive ($fileId)"
        if (ext == null && !isAudioMimeType(detectedMime)) {
            val nonAudioExt = resolvedName.substringAfterLast('.', "").lowercase()
            if (nonAudioExt.isNotEmpty() && nonAudioExt != resolvedName.lowercase() && nonAudioExt !in AUDIO_EXTENSIONS) {
                throw IOException("The selected Google Drive file ($resolvedName) is not a supported audio file.")
            }
        }

        val cleanTitle = stripAudioExtension(resolvedName).ifBlank { resolvedName }
        val remoteTrackId = encodeRemoteTrackId(fileId, ext, resourceKey)
        val relFileName = disambiguateFileName(HashSet(), resolvedName, ext, fileId)

        return CloudPlaylistResult(
            source = CloudSource.GOOGLE_DRIVE,
            remoteId = fileId,
            title = cleanTitle,
            tracks = listOf(
                CloudTrackMetadata(
                    remoteTrackId = remoteTrackId,
                    title = cleanTitle,
                    artist = DEFAULT_ARTIST,
                    album = cleanTitle,
                    durationSec = 0.0,
                    trackNumber = 1,
                    relativePath = relFileName,
                )
            ),
        )
    }

    private fun buildSingleFileResult(
        fileId: String,
        resourceKey: String?,
        fileName: String?,
        detectedMime: String?,
    ): CloudPlaylistResult {
        val resolvedName = fileName?.takeIf { it.isNotBlank() } ?: "Google Drive ($fileId)"
        val ext = audioExtensionFromName(resolvedName) ?: extensionForMimeType(detectedMime)
        if (ext == null && !isAudioMimeType(detectedMime)) {
            val nonAudioExt = resolvedName.substringAfterLast('.', "").lowercase()
            if (nonAudioExt.isNotEmpty() && nonAudioExt != resolvedName.lowercase() && nonAudioExt !in AUDIO_EXTENSIONS) {
                throw IOException("The selected Google Drive file ($resolvedName) is not a supported audio file.")
            }
        }
        val cleanTitle = stripAudioExtension(resolvedName).ifBlank { resolvedName }
        val remoteTrackId = encodeRemoteTrackId(fileId, ext, resourceKey)
        val relFileName = disambiguateFileName(HashSet(), resolvedName, ext, fileId)
        return CloudPlaylistResult(
            source = CloudSource.GOOGLE_DRIVE,
            remoteId = fileId,
            title = cleanTitle,
            tracks = listOf(
                CloudTrackMetadata(
                    remoteTrackId = remoteTrackId,
                    title = cleanTitle,
                    artist = DEFAULT_ARTIST,
                    album = cleanTitle,
                    durationSec = 0.0,
                    trackNumber = 1,
                    relativePath = relFileName,
                )
            ),
        )
    }

    suspend fun resolveStream(remoteTrackId: String, cookie: String? = null): ResolvedStream {
        val decoded = decodeRemoteTrackId(remoteTrackId)
        resolveAuthorizedStream(decoded)?.let { return it }
        val headers = baseHeaders(cookie)
        val probeHeaders = headers + ("Range" to "bytes=0-0")

        val candidates = listOf(
            buildUserContentDownloadUrl(decoded.fileId, decoded.resourceKey),
            buildLegacyUcDownloadUrl(decoded.fileId, decoded.resourceKey),
        )

        var lastError: Throwable? = null
        for (candidateUrl in candidates) {
            val resp = try {
                httpClient.get(candidateUrl, headers = probeHeaders, followRedirects = true)
            } catch (err: IOException) {
                lastError = err
                continue
            }

            if (resp.finalUrl.contains("accounts.google.com", ignoreCase = true)) {
                throw buildAccessError(resp)
            }
            if (resp.statusCode == 401 || resp.statusCode == 403) {
                throw buildAccessError(resp)
            }
            if (resp.statusCode == 404) {
                lastError = buildAccessError(resp)
                continue
            }
            if (resp.statusCode !in 200..299) {
                lastError = IOException("Google Drive download failed (HTTP ${resp.statusCode}).")
                continue
            }

            val contentType = headerValue(resp.headers, "Content-Type")
            val isHtml = isHtmlContentType(contentType) || looksLikeHtml(resp.bodyBytes)

            if (!isHtml) {
                val contentDisp = headerValue(resp.headers, "Content-Disposition")
                val dispFilename = parseContentDispositionFilename(contentDisp)
                val resolvedExt = dispFilename?.let { audioExtensionFromName(it) }
                    ?: extensionForMimeType(contentType)
                    ?: decoded.extension
                val resolvedMime = when {
                    dispFilename != null -> mimeTypeForExtension(audioExtensionFromName(dispFilename))
                        ?: normalizeAudioContentType(contentType)
                    isAudioMimeType(contentType) -> contentType?.substringBefore(';')?.trim()
                    resolvedExt != null -> mimeTypeForExtension(resolvedExt)
                    else -> contentType?.substringBefore(';')?.trim()
                }
                return ResolvedStream(
                    url = resp.finalUrl.ifBlank { candidateUrl },
                    headers = headers,
                    mimeType = resolvedMime,
                    expiresAtEpochMs = System.currentTimeMillis() + DEFAULT_STREAM_TTL_MS,
                )
            }

            val html = resp.bodyText
            if (isSignInHtml(html)) {
                throw buildAccessError(resp)
            }

            val confirmedUrl = parseVirusScanConfirmUrl(
                html = html,
                baseUrl = resp.finalUrl.ifBlank { candidateUrl },
                fallbackFileId = decoded.fileId,
                fallbackResourceKey = decoded.resourceKey,
            )
            if (confirmedUrl != null) {
                return ResolvedStream(
                    url = confirmedUrl,
                    headers = headers,
                    mimeType = mimeTypeForExtension(decoded.extension),
                    expiresAtEpochMs = System.currentTimeMillis() + DEFAULT_STREAM_TTL_MS,
                )
            }

            lastError = IOException("Google Drive did not return an audio stream. Make sure the link is shared with 'Anyone with the link'.")
        }

        throw (lastError ?: IOException("Could not resolve Google Drive stream for ${decoded.fileId}."))
    }

    internal fun parseDriveIvdEntries(rawJson: String): List<DriveEntry>? =
        parseDriveIvdPage(rawJson)?.entries

    internal fun parseDriveIvdPage(rawJson: String): DriveIvdPage? {
        val element = runCatching { cloudJson.parseToJsonElement(rawJson) }.getOrNull() ?: return null
        val rootArray = element as? JsonArray ?: return null

        val isEnvelope = rootArray.isNotEmpty() && rootArray[0] is JsonArray && (rootArray[0] as JsonArray).let { inner ->
            inner.isEmpty() || inner[0] is JsonArray || inner[0] is JsonObject
        }
        val candidateArray: JsonArray = when {
            rootArray.isEmpty() -> JsonArray(emptyList())
            isEnvelope -> rootArray[0] as JsonArray
            else -> rootArray
        }
        val nextPageToken = if (isEnvelope) {
            (rootArray.getOrNull(1) as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        val entries = mutableListOf<DriveEntry>()
        for (item in candidateArray) {
            val entry = parseSingleIvdEntry(item) ?: continue
            entries.add(entry)
        }
        return DriveIvdPage(entries = entries, nextPageToken = nextPageToken)
    }

    private fun parseSingleIvdEntry(item: JsonElement): DriveEntry? {
        if (item is JsonObject) {
            val id = item.str("id")?.takeIf { it.isNotBlank() } ?: return null
            val name = item.str("name")?.takeIf { it.isNotBlank() }
                ?: item.str("title")?.takeIf { it.isNotBlank() }
                ?: id
            val mime = item.str("mimeType")
            val rk = (item.str("resourceKey") ?: item.str("resourcekey"))?.takeIf { it.isNotBlank() }
            return DriveEntry(
                id = id,
                name = name,
                mimeType = mime,
                resourceKey = rk,
                isFolder = mime.equals(FOLDER_MIME_TYPE, ignoreCase = true),
            )
        }
        val arr = item as? JsonArray ?: return null
        val id = (arr.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val second = arr.getOrNull(1)
        val (name, mime, metaStartIdx) = if (second is JsonPrimitive && second.isString) {
            val n = second.contentOrNull
            val m = (arr.getOrNull(2) as? JsonPrimitive)?.contentOrNull
            Triple(n, m, 3)
        } else {
            val n = (arr.getOrNull(2) as? JsonPrimitive)?.contentOrNull
            val m = (arr.getOrNull(3) as? JsonPrimitive)?.contentOrNull
            Triple(n, m, 4)
        }
        val resolvedName = name?.takeIf { it.isNotBlank() } ?: return null
        val rk = extractResourceKeyFromIvdTail(arr, metaStartIdx)
        return DriveEntry(
            id = id,
            name = resolvedName,
            mimeType = mime,
            resourceKey = rk,
            isFolder = mime.equals(FOLDER_MIME_TYPE, ignoreCase = true),
        )
    }

    private fun extractResourceKeyFromIvdTail(arr: JsonArray, startIndex: Int): String? {
        for (i in startIndex until arr.size) {
            val el = arr[i]
            if (el is JsonObject) {
                val rk = (el.str("resourceKey") ?: el.str("resourcekey"))?.takeIf { it.isNotBlank() }
                if (rk != null) return rk
            } else if (el is JsonPrimitive && el.isString) {
                val value = el.contentOrNull ?: continue
                if (RESOURCE_KEY_VALUE_REGEX.matches(value)) {
                    return value
                }
            }
        }
        return null
    }

    internal fun parseEmbeddedFolderEntries(html: String): List<DriveEntry> {
        val entries = mutableListOf<DriveEntry>()
        val seenIds = mutableSetOf<String>()

        val blockSplits = FLIP_ENTRY_SPLIT_REGEX.split(html)
        if (blockSplits.size > 1) {
            for (block in blockSplits.drop(1)) {
                val hrefMatch = HREF_ATTR_REGEX.find(block) ?: continue
                val href = decodeHtmlEntities(hrefMatch.groupValues[1])
                val titleMatch = FLIP_ENTRY_TITLE_REGEX.find(block)
                val rawTitle = titleMatch?.groupValues?.get(1)
                    ?.let { HTML_TAG_REGEX.replace(it, "") }
                    ?.let(::decodeHtmlEntities)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: continue

                val parsed = parseEntryHref(href, block, rawTitle) ?: continue
                if (seenIds.add(parsed.first)) {
                    entries.add(
                        DriveEntry(
                            id = parsed.first,
                            name = rawTitle,
                            mimeType = if (parsed.third) FOLDER_MIME_TYPE else null,
                            resourceKey = parsed.second,
                            isFolder = parsed.third,
                        )
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            for (match in ANCHOR_REGEX.findAll(html)) {
                val href = decodeHtmlEntities(match.groupValues[1])
                val text = HTML_TAG_REGEX.replace(match.groupValues[2], "")
                    .let(::decodeHtmlEntities)
                    .trim()
                if (text.isEmpty()) continue
                val parsed = parseEntryHref(href, match.value, text) ?: continue
                if (seenIds.add(parsed.first)) {
                    entries.add(
                        DriveEntry(
                            id = parsed.first,
                            name = text,
                            mimeType = if (parsed.third) FOLDER_MIME_TYPE else null,
                            resourceKey = parsed.second,
                            isFolder = parsed.third,
                        )
                    )
                }
            }
        }

        return entries
    }

    /** Returns Triple(id, resourceKey, isFolder) or null. */
    private fun parseEntryHref(href: String, contextHtml: String, title: String): Triple<String, String?, Boolean>? {
        val normalized = when {
            href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true) -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "https://drive.google.com$href"
            else -> return null
        }
        val target = parseTarget(normalized)
        return when (target) {
            is GoogleDriveTarget.Folder -> Triple(target.id, target.resourceKey, true)
            is GoogleDriveTarget.File -> Triple(target.id, target.resourceKey, false)
            is GoogleDriveTarget.Open -> {
                val hasAudioExt = audioExtensionFromName(title) != null
                val isFolder = !hasAudioExt && (
                    contextHtml.contains("alt=\"Folder\"", ignoreCase = true) ||
                        contextHtml.contains("alt='Folder'", ignoreCase = true) ||
                        contextHtml.contains("aria-label=\"Folder\"", ignoreCase = true) ||
                        contextHtml.contains("icon_10_folder", ignoreCase = true) ||
                        contextHtml.contains("folder_shared", ignoreCase = true)
                    )
                Triple(target.id, target.resourceKey, isFolder)
            }
            null -> null
        }
    }

    internal fun parseVirusScanConfirmUrl(
        html: String,
        baseUrl: String,
        fallbackFileId: String,
        fallbackResourceKey: String?,
    ): String? {
        for (formMatch in FORM_REGEX.findAll(html)) {
            val attrs = formMatch.groupValues[1]
            val body = formMatch.groupValues[2]
            val actionRaw = extractAttr(attrs, "action")?.let(::decodeHtmlEntities) ?: continue
            val isDownloadForm = actionRaw.contains("download", ignoreCase = true) ||
                actionRaw.contains("/uc", ignoreCase = true) ||
                attrs.contains("download-form", ignoreCase = true)
            if (!isDownloadForm) continue

            val resolvedAction = runCatching { URL(URL(baseUrl), actionRaw).toString() }
                .getOrDefault("https://drive.usercontent.google.com/download")
            val actionUri = runCatching { URI(resolvedAction) }.getOrNull()
            val params = LinkedHashMap<String, String>()
            if (actionUri != null) {
                params.putAll(parseQueryParams(actionUri.rawQuery))
            }

            for (inputMatch in INPUT_TAG_REGEX.findAll(body)) {
                val inputAttrs = inputMatch.groupValues[1]
                val type = extractAttr(inputAttrs, "type")?.lowercase()
                if (type == "submit" || type == "button" || type == "image" || type == "reset") continue
                val name = extractAttr(inputAttrs, "name")?.let(::decodeHtmlEntities)?.takeIf { it.isNotBlank() } ?: continue
                val value = extractAttr(inputAttrs, "value")?.let(::decodeHtmlEntities).orEmpty()
                params[name] = value
            }

            if (params.isEmpty()) continue
            params.putIfAbsent("id", fallbackFileId)
            params.putIfAbsent("export", "download")
            params.putIfAbsent("confirm", "t")
            if (!fallbackResourceKey.isNullOrBlank()) {
                params.putIfAbsent("resourcekey", fallbackResourceKey)
            }

            val baseWithoutQuery = resolvedAction.substringBefore('?').substringBefore('#')
            val queryString = params.entries.joinToString("&") { (k, v) ->
                "${encodeQuery(k)}=${encodeQuery(v)}"
            }
            return "$baseWithoutQuery?$queryString"
        }

        val ucLink = UC_DOWNLOAD_LINK_REGEX.find(html)?.groupValues?.get(1)?.let(::decodeHtmlEntities)
        if (!ucLink.isNullOrBlank()) {
            return runCatching { URL(URL(baseUrl), ucLink).toString() }.getOrNull()
        }
        return null
    }

    private fun checkAccessAndStatus(resp: CloudHttpResponse, label: String) {
        if (isAuthOrNotFound(resp)) {
            throw buildAccessError(resp)
        }
        if (resp.statusCode !in 200..299) {
            throw IOException("$label request failed (HTTP ${resp.statusCode}).")
        }
    }

    private fun isAuthOrNotFound(resp: CloudHttpResponse): Boolean {
        if (resp.statusCode == 401 || resp.statusCode == 403 || resp.statusCode == 404) return true
        if (resp.finalUrl.contains("accounts.google.com", ignoreCase = true)) return true
        val contentType = headerValue(resp.headers, "Content-Type")
        if ((contentType == null || isHtmlContentType(contentType)) && isSignInHtml(resp.bodyText)) {
            return true
        }
        return false
    }

    private fun buildAccessError(resp: CloudHttpResponse): IOException {
        return when {
            resp.statusCode == 404 -> IOException(
                "Google Drive link not found (HTTP 404). Make sure the link is shared with 'Anyone with the link'."
            )
            resp.statusCode == 401 || resp.statusCode == 403 -> IOException(
                "Google Drive access denied (HTTP ${resp.statusCode}). Make sure the link is shared with 'Anyone with the link'."
            )
            else -> IOException(
                "Google Drive requires sign-in. Make sure the link is shared with 'Anyone with the link'."
            )
        }
    }

    private fun isSignInHtml(html: String): Boolean {
        if (html.isBlank()) return false
        if (extractDriveIvdJson(html) != null || html.contains("flip-entry", ignoreCase = true)) return false
        if (FORM_REGEX.findAll(html).any { it.groupValues[1].contains("download", ignoreCase = true) }) return false
        val lower = html.lowercase()
        return lower.contains("accounts.google.com/servicelogin") ||
            lower.contains("google drive: sign-in") ||
            lower.contains("<title>sign in - google accounts</title>") ||
            lower.contains("you need access")
    }

    private fun baseHeaders(cookie: String?): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to USER_AGENT,
        )
        val trimmedCookie = cookie?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedCookie != null) {
            headers["Cookie"] = trimmedCookie
        }
        return headers
    }

    private suspend fun sendAuthorizedDriveGet(
        url: String,
        resourceKey: String?,
        targetIdForRk: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<CloudHttpResponse, String>? {
        val provider = oauthTokenProvider ?: return null
        val firstToken = provider(false)?.takeIf { it.isNotBlank() } ?: return null

        fun buildAuthHeaders(tok: String): Map<String, String> {
            val h = linkedMapOf(
                "User-Agent" to USER_AGENT,
                "Authorization" to "Bearer $tok",
            )
            h.putAll(extraHeaders)
            if (!resourceKey.isNullOrBlank() && !targetIdForRk.isNullOrBlank()) {
                h["X-Goog-Drive-Resource-Keys"] = "$targetIdForRk/$resourceKey"
            }
            return h
        }

        var activeToken = firstToken
        var resp = getWithRetry(url, headers = buildAuthHeaders(activeToken))
        if (resp.statusCode == 401) {
            val refreshed = provider(true)?.takeIf { it.isNotBlank() }
            if (refreshed != null) {
                activeToken = refreshed
                resp = getWithRetry(url, headers = buildAuthHeaders(activeToken))
            }
        }
        return resp to activeToken
    }

    private suspend fun fetchAuthorizedFileMetadata(
        fileId: String,
        resourceKey: String?,
    ): DriveEntry? {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId" +
            "?fields=${encodeQuery("id,name,mimeType,resourceKey,size")}&supportsAllDrives=true"
        val (resp, _) = sendAuthorizedDriveGet(url, resourceKey, fileId) ?: return null
        if (resp.statusCode == 403 || resp.statusCode == 404) return null
        if (resp.statusCode !in 200..299) {
            throw IOException("Google Drive metadata request failed (HTTP ${resp.statusCode}).")
        }
        val obj = runCatching { Json.parseToJsonElement(resp.bodyText).jsonObject }.getOrNull() ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val mimeType = obj["mimeType"]?.jsonPrimitive?.contentOrNull ?: return null
        val rk = obj["resourceKey"]?.jsonPrimitive?.contentOrNull ?: resourceKey
        return DriveEntry(
            id = id,
            name = name,
            mimeType = mimeType,
            resourceKey = rk,
            isFolder = mimeType.equals(FOLDER_MIME_TYPE, ignoreCase = true),
        )
    }

    private suspend fun fetchAuthorizedFolderPage(
        folderId: String,
        resourceKey: String?,
        fallbackTitle: String?,
    ): Pair<String, List<DriveEntry>>? {
        if (oauthTokenProvider == null) return null
        val folderMeta = fetchAuthorizedFileMetadata(folderId, resourceKey) ?: return null

        val merged = LinkedHashMap<String, DriveEntry>()
        val seenTokens = HashSet<String>()
        var pageToken: String? = null

        do {
            val query = "'${folderId.replace("'", "\\'")}' in parents and trashed = false"
            val url = buildString {
                append("https://www.googleapis.com/drive/v3/files?supportsAllDrives=true&includeItemsFromAllDrives=true")
                append("&pageSize=1000")
                append("&fields=").append(encodeQuery("nextPageToken,files(id,name,mimeType,resourceKey,size)"))
                append("&q=").append(encodeQuery(query))
                if (!pageToken.isNullOrBlank()) {
                    append("&pageToken=").append(encodeQuery(pageToken))
                }
            }
            val (resp, _) = sendAuthorizedDriveGet(url, resourceKey, folderId) ?: return null
            if (resp.statusCode == 401 || resp.statusCode == 403 || resp.statusCode == 404) {
                throw buildAccessError(resp)
            }
            if (resp.statusCode !in 200..299) {
                throw IOException("Google Drive API request failed (HTTP ${resp.statusCode}).")
            }
            val parsed = parseDriveApiPage(resp.bodyText)
                ?: throw IOException("Invalid Google Drive API response.")
            for (entry in parsed.entries) {
                merged.putIfAbsent(entry.id, entry)
            }
            val next = parsed.nextPageToken?.takeIf { it.isNotBlank() && seenTokens.add(it) }
            pageToken = next
        } while (pageToken != null)

        val resolvedTitle = folderMeta.name.ifBlank { fallbackTitle ?: "Google Drive ($folderId)" }
        return resolvedTitle to merged.values.toList()
    }

    private suspend fun resolveAuthorizedStream(decoded: DecodedTrackId): ResolvedStream? {
        val mediaUrl = "https://www.googleapis.com/drive/v3/files/${decoded.fileId}?alt=media&supportsAllDrives=true"
        val (resp, token) = sendAuthorizedDriveGet(
            url = mediaUrl,
            resourceKey = decoded.resourceKey,
            targetIdForRk = decoded.fileId,
            extraHeaders = mapOf("Range" to "bytes=0-0"),
        ) ?: return null

        if (resp.statusCode == 403 || resp.statusCode == 404) return null
        if (resp.statusCode !in 200..299) {
            throw IOException("Google Drive OAuth stream failed (HTTP ${resp.statusCode}).")
        }

        val contentType = headerValue(resp.headers, "Content-Type")
        val dispFilename = parseContentDispositionFilename(headerValue(resp.headers, "Content-Disposition"))
        val resolvedExt = dispFilename?.let { audioExtensionFromName(it) }
            ?: extensionForMimeType(contentType)
            ?: decoded.extension
        val resolvedMime = when {
            dispFilename != null -> mimeTypeForExtension(audioExtensionFromName(dispFilename))
                ?: normalizeAudioContentType(contentType)
            isAudioMimeType(contentType) -> contentType?.substringBefore(';')?.trim()
            resolvedExt != null -> mimeTypeForExtension(resolvedExt)
            else -> contentType?.substringBefore(';')?.trim()
        }
        return ResolvedStream(
            url = resp.finalUrl.ifBlank { mediaUrl },
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Authorization" to "Bearer $token",
            ),
            mimeType = resolvedMime,
            expiresAtEpochMs = System.currentTimeMillis() + DEFAULT_STREAM_TTL_MS,
        )
    }

    companion object {
        private const val MAX_FOLDER_DEPTH = 12
        private const val MAX_FOLDERS = 2000
        private const val MAX_API_PAGES = 100
        private const val IVD_TRUNCATION_THRESHOLD = 50
        private const val MAX_CONCURRENT_WORKERS = 4
        private const val MAX_HTTP_RETRIES = 3
        internal const val DEFAULT_ARTIST = "Google Drive"
        private const val DEFAULT_STREAM_TTL_MS = 60L * 60L * 1000L
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        val SUPPORTED_AUDIO_EXTENSIONS: Set<String> = setOf(
            "mp3", "m4a", "mp4", "aac", "flac", "wav", "aif", "aiff", "alac", "caf",
            "ogg", "oga", "opus", "webm", "wma", "m4b", "m4r", "amr", "3gp", "ape",
            "wv", "dsf", "dff", "ac3", "dts", "spx", "mpc",
        )

        val AUDIO_EXTENSIONS: Set<String> = SUPPORTED_AUDIO_EXTENSIONS

        private val GOOGLE_API_KEY_REGEX = Regex("""AIzaSy[0-9A-Za-z_-]{33}""")
        private val PATH_SEPARATOR_AND_CONTROL_REGEX = Regex("""[/\\\u0000-\u001F\u007F]""")
        private val DRIVE_ID_REGEX = Regex("^[A-Za-z0-9_-]{3,}$")
        private val RESOURCE_KEY_VALUE_REGEX = Regex("^0-[A-Za-z0-9_-]{6,}$")
        private val IVD_ASSIGNMENT_REGEX = Regex("""window(?:\[['"]_DRIVE_ivd['"]\]|\._DRIVE_ivd)\s*=\s*""")
        private val OG_TITLE_META_REGEX_1 = Regex(
            """<meta\b[^>]*\bproperty\s*=\s*["']og:title["'][^>]*\bcontent\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val OG_TITLE_META_REGEX_2 = Regex(
            """<meta\b[^>]*\bcontent\s*=\s*["']([^"']*)["'][^>]*\bproperty\s*=\s*["']og:title["']""",
            RegexOption.IGNORE_CASE,
        )
        private val TITLE_TAG_REGEX = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val EMBEDDED_FOLDER_TITLE_REGEX = Regex(
            """<div\b[^>]*class\s*=\s*["'][^"']*flip-folder-title[^"']*["'][^>]*>(.*?)</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val TITLE_DRIVE_SUFFIX_REGEX = Regex(
            """\s*[-–]\s*(?:Google Drive|Google 云端硬盘|Google 雲端硬碟)\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val FLIP_ENTRY_SPLIT_REGEX = Regex("""<div\b[^>]*class\s*=\s*["'][^"']*flip-entry\b[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
        private val FLIP_ENTRY_TITLE_REGEX = Regex(
            """<div\b[^>]*class\s*=\s*["'][^"']*flip-entry-title[^"']*["'][^>]*>(.*?)</div>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val HREF_ATTR_REGEX = Regex("""\bhref\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val ANCHOR_REGEX = Regex(
            """<a\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
        private val FORM_REGEX = Regex(
            """<form\b([^>]*)>(.*?)</form>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val INPUT_TAG_REGEX = Regex("""<input\b([^>]*)>""", RegexOption.IGNORE_CASE)
        private val UC_DOWNLOAD_LINK_REGEX = Regex(
            """<a\b[^>]*id\s*=\s*["']uc-download-link["'][^>]*\bhref\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        private val HTML_NUM_ENTITY_REGEX = Regex("""&#(\d+);""")
        private val HTML_HEX_ENTITY_REGEX = Regex("""&#x([0-9a-fA-F]+);""")

        fun canHandle(input: String): Boolean = parseTarget(input) != null

        fun parseTarget(input: String): GoogleDriveTarget? {
            val trimmed = extractCloudSourceInput(input)
            if (trimmed.isEmpty()) return null
            if (!trimmed.contains("drive.google.com", ignoreCase = true) &&
                !trimmed.contains("drive.usercontent.google.com", ignoreCase = true)
            ) {
                return null
            }

            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (host != "drive.google.com" && host != "drive.usercontent.google.com") {
                return null
            }

            val query = parseQueryParams(uri.rawQuery)
            val resourceKey = (query["resourcekey"] ?: query["resourceKey"])
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val segments = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }

            if (host == "drive.usercontent.google.com") {
                val id = query["id"]?.trim()?.takeIf { DRIVE_ID_REGEX.matches(it) } ?: return null
                return GoogleDriveTarget.File(id = id, resourceKey = resourceKey)
            }

            val foldersIndex = segments.indexOf("folders")
            if (foldersIndex >= 0 && foldersIndex + 1 < segments.size) {
                val folderId = segments[foldersIndex + 1]
                if (DRIVE_ID_REGEX.matches(folderId)) {
                    return GoogleDriveTarget.Folder(id = folderId, resourceKey = resourceKey)
                }
            }

            for (i in 0 until segments.size - 2) {
                if (segments[i] == "file" && segments[i + 1] == "d") {
                    val fileId = segments[i + 2]
                    if (DRIVE_ID_REGEX.matches(fileId)) {
                        return GoogleDriveTarget.File(id = fileId, resourceKey = resourceKey)
                    }
                }
            }

            val last = segments.lastOrNull()?.lowercase()
            if (last == "embeddedfolderview" || last == "folderview") {
                val id = query["id"]?.trim()?.takeIf { DRIVE_ID_REGEX.matches(it) } ?: return null
                return GoogleDriveTarget.Folder(id = id, resourceKey = resourceKey)
            }
            if (last == "uc") {
                val id = query["id"]?.trim()?.takeIf { DRIVE_ID_REGEX.matches(it) } ?: return null
                return GoogleDriveTarget.File(id = id, resourceKey = resourceKey)
            }
            if (last == "open") {
                val id = query["id"]?.trim()?.takeIf { DRIVE_ID_REGEX.matches(it) } ?: return null
                return GoogleDriveTarget.Open(id = id, resourceKey = resourceKey)
            }

            return null
        }

        internal fun encodeRemoteTrackId(
            fileId: String,
            extension: String?,
            resourceKey: String?,
        ): String {
            val cleanExt = extension?.trim()?.trimStart('.')?.lowercase()?.takeIf { it.isNotEmpty() }
            val rkPart = resourceKey?.trim()?.takeIf { it.isNotEmpty() }?.let { rk ->
                val encoded = URLEncoder.encode(rk, "UTF-8").replace(".", "%2E")
                "~rk~$encoded"
            }.orEmpty()
            val extPart = if (cleanExt != null) ".$cleanExt" else ""
            return "$fileId$rkPart$extPart"
        }

        internal fun decodeRemoteTrackId(remoteTrackId: String): DecodedTrackId {
            val dotIdx = remoteTrackId.lastIndexOf('.')
            val rkMarkerAfterDot = dotIdx >= 0 && remoteTrackId.indexOf("~rk~", dotIdx) >= 0
            val (base, ext) = if (dotIdx > 0 && !rkMarkerAfterDot) {
                val candidateExt = remoteTrackId.substring(dotIdx + 1).lowercase()
                if (candidateExt in AUDIO_EXTENSIONS) {
                    remoteTrackId.substring(0, dotIdx) to candidateExt
                } else {
                    remoteTrackId to null
                }
            } else {
                remoteTrackId to null
            }

            val rkSplit = base.split("~rk~", limit = 2)
            val fileId = rkSplit[0]
            val resourceKey = rkSplit.getOrNull(1)?.let { encoded ->
                runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
            }?.takeIf { it.isNotBlank() }

            return DecodedTrackId(
                fileId = fileId,
                extension = ext,
                resourceKey = resourceKey,
            )
        }

        internal fun extractDriveIvdJson(html: String): String? {
            val match = IVD_ASSIGNMENT_REGEX.find(html) ?: return null
            var i = match.range.last + 1
            while (i < html.length && html[i].isWhitespace()) i++
            if (i >= html.length) return null

            val first = html[i]
            if (first == '[') {
                return extractBalancedJsonArray(html, i)
            }
            if (first != '\'' && first != '"') return null

            val quote = first
            i += 1
            val raw = StringBuilder()
            var escaped = false
            while (i < html.length) {
                val ch = html[i]
                if (escaped) {
                    raw.append('\\').append(ch)
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == quote) {
                    return unescapeJsString(raw.toString())
                } else {
                    raw.append(ch)
                }
                i += 1
            }
            return null
        }

        private fun extractBalancedJsonArray(text: String, start: Int): String? {
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until text.length) {
                val ch = text[i]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        inString = false
                    }
                } else {
                    when (ch) {
                        '"' -> inString = true
                        '[' -> depth += 1
                        ']' -> {
                            depth -= 1
                            if (depth == 0) {
                                return text.substring(start, i + 1)
                            }
                        }
                    }
                }
            }
            return null
        }

        internal fun unescapeJsString(raw: String): String {
            val sb = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val ch = raw[i]
                if (ch != '\\' || i + 1 >= raw.length) {
                    sb.append(ch)
                    i += 1
                    continue
                }
                val next = raw[i + 1]
                when (next) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '\'' -> { sb.append('\''); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'x' -> {
                        if (i + 3 < raw.length) {
                            val code = raw.substring(i + 2, i + 4).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 4
                                continue
                            }
                        }
                        sb.append(next)
                        i += 2
                    }
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(next)
                        i += 2
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            }
            return sb.toString()
        }

        internal fun extractPageTitle(html: String): String? {
            val og = (OG_TITLE_META_REGEX_1.find(html) ?: OG_TITLE_META_REGEX_2.find(html))
                ?.groupValues?.get(1)
                ?.let(::cleanDriveTitle)
            if (!og.isNullOrBlank()) return og

            val title = TITLE_TAG_REGEX.find(html)
                ?.groupValues?.get(1)
                ?.let(::cleanDriveTitle)
            return title?.takeIf { it.isNotBlank() }
        }

        private fun extractEmbeddedFolderTitle(html: String): String? {
            return EMBEDDED_FOLDER_TITLE_REGEX.find(html)
                ?.groupValues?.get(1)
                ?.let { HTML_TAG_REGEX.replace(it, "") }
                ?.let(::cleanDriveTitle)
                ?.takeIf { it.isNotBlank() }
        }

        private fun cleanDriveTitle(raw: String): String? {
            val unescaped = decodeHtmlEntities(raw).trim()
            val stripped = TITLE_DRIVE_SUFFIX_REGEX.replace(unescaped, "").trim()
            if (stripped.isEmpty() ||
                stripped.equals("Google Drive", ignoreCase = true) ||
                stripped.equals("Google Drive: Sign-in", ignoreCase = true)
            ) {
                return null
            }
            return stripped
        }

        internal fun decodeHtmlEntities(raw: String): String {
            var s = raw
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
            s = HTML_NUM_ENTITY_REGEX.replace(s) { m ->
                val cp = m.groupValues[1].toIntOrNull()
                if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
            }
            s = HTML_HEX_ENTITY_REGEX.replace(s) { m ->
                val cp = m.groupValues[1].toIntOrNull(16)
                if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
            }
            return s
        }

        internal fun isAudioEntry(name: String, mimeType: String?): Boolean {
            if (mimeType.equals(FOLDER_MIME_TYPE, ignoreCase = true)) return false
            if (audioExtensionFromName(name) != null) return true
            return isAudioMimeType(mimeType)
        }

        internal fun audioExtensionFromName(name: String): String? {
            val dot = name.lastIndexOf('.')
            if (dot < 0 || dot == name.lastIndex) return null
            val ext = name.substring(dot + 1).trim().lowercase()
            return ext.takeIf { it in AUDIO_EXTENSIONS }
        }

        internal fun stripAudioExtension(name: String): String {
            val ext = audioExtensionFromName(name) ?: return name.trim()
            return name.dropLast(ext.length + 1).trim()
        }

        private fun resolveExtension(name: String, mimeType: String?): String? =
            audioExtensionFromName(name) ?: extensionForMimeType(mimeType)

        private fun isAudioMimeType(mimeType: String?): Boolean {
            val clean = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
            return clean.startsWith("audio/") ||
                clean == "application/ogg" ||
                clean == "application/x-flac" ||
                clean == "application/flac"
        }

        private fun normalizeAudioContentType(mimeType: String?): String? {
            val clean = mimeType?.substringBefore(';')?.trim() ?: return null
            return clean.takeIf { it.startsWith("audio/", ignoreCase = true) }
        }

        internal fun extractApiKeys(html: String): List<String> =
            GOOGLE_API_KEY_REGEX.findAll(html)
                .map { it.value }
                .distinct()
                .toList()

        internal fun extractApiKey(html: String): String? =
            extractApiKeys(html).firstOrNull()

        internal fun normalizeFolderPath(path: String): String =
            path.replace('\\', '/').trim { it == '/' || it.isWhitespace() }

        internal fun sanitizePathSegment(raw: String, fallback: String): String {
            val safeFallback = PATH_SEPARATOR_AND_CONTROL_REGEX.replace(fallback, "_")
                .trim()
                .takeIf { it.isNotEmpty() && it != "." && it != ".." }
                ?: "item"
            val cleaned = PATH_SEPARATOR_AND_CONTROL_REGEX.replace(raw, "_").trim()
            if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
                return safeFallback
            }
            return cleaned
        }

        internal fun disambiguateSegment(
            usedLower: MutableSet<String>,
            rawName: String,
            fallback: String,
            preserveExtension: Boolean = false,
        ): String {
            val clean = sanitizePathSegment(rawName, fallback)
            if (usedLower.add(clean.lowercase())) return clean
            var index = 2
            while (true) {
                val candidate = if (preserveExtension) {
                    val dot = clean.lastIndexOf('.')
                    if (dot > 0 && dot < clean.lastIndex) {
                        "${clean.substring(0, dot)} ($index)${clean.substring(dot)}"
                    } else {
                        "$clean ($index)"
                    }
                } else {
                    "$clean ($index)"
                }
                if (usedLower.add(candidate.lowercase())) return candidate
                index += 1
            }
        }

        internal fun disambiguateFileName(
            usedLower: MutableSet<String>,
            rawName: String,
            resolvedExt: String?,
            fallbackStem: String,
        ): String {
            val clean = sanitizePathSegment(rawName, fallbackStem)
            val hasExt = audioExtensionFromName(clean) != null
            val withExt = if (!hasExt && !resolvedExt.isNullOrBlank()) {
                "$clean.${resolvedExt.lowercase()}"
            } else {
                clean
            }
            return disambiguateSegment(
                usedLower = usedLower,
                rawName = withExt,
                fallback = fallbackStem,
                preserveExtension = true,
            )
        }

        internal fun extensionForMimeType(mimeType: String?): String? {
            val clean = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return null
            return when {
                clean.contains("flac") -> "flac"
                clean.contains("mpeg") || clean == "audio/mp3" -> "mp3"
                clean.contains("mp4") || clean.contains("m4a") -> "m4a"
                clean.contains("aac") -> "aac"
                clean.contains("wav") || clean.contains("wave") -> "wav"
                clean.contains("aiff") -> "aiff"
                clean.contains("aif") -> "aif"
                clean.contains("alac") -> "alac"
                clean.contains("caf") -> "caf"
                clean.contains("opus") -> "opus"
                clean.contains("ogg") -> "ogg"
                clean.contains("webm") -> "webm"
                clean.contains("wma") || clean.contains("x-ms-wma") -> "wma"
                else -> null
            }
        }

        internal fun mimeTypeForExtension(extension: String?): String? {
            return when (extension?.lowercase()) {
                "mp3" -> "audio/mpeg"
                "m4a", "m4b", "m4r" -> "audio/mp4"
                "mp4" -> "audio/mp4"
                "aac" -> "audio/aac"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "aif", "aiff" -> "audio/aiff"
                "alac" -> "audio/alac"
                "caf" -> "audio/x-caf"
                "ogg", "oga" -> "audio/ogg"
                "opus" -> "audio/opus"
                "webm" -> "audio/webm"
                "wma" -> "audio/x-ms-wma"
                else -> null
            }
        }

        internal fun parseContentDispositionFilename(header: String?): String? {
            if (header.isNullOrBlank()) return null
            val starMatch = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""").find(header)
            if (starMatch != null) {
                val decoded = runCatching {
                    URLDecoder.decode(starMatch.groupValues[1].trim().trim('"', '\''), "UTF-8")
                }.getOrNull()
                if (!decoded.isNullOrBlank()) return decoded
            }
            val quotedMatch = Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(header)
            if (quotedMatch != null) {
                val name = quotedMatch.groupValues[1].trim()
                if (name.isNotEmpty()) return name
            }
            val bareMatch = Regex("""filename\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE).find(header)
            return bareMatch?.groupValues?.get(1)?.trim()?.trim('\'', '"')?.takeIf { it.isNotEmpty() }
        }

        private fun headerValue(headers: Map<String, List<String>>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        private fun isHtmlContentType(contentType: String?): Boolean {
            val clean = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
            return clean == "text/html" || clean == "application/xhtml+xml"
        }

        private fun looksLikeHtml(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return false
            val prefix = bytes.copyOfRange(0, bytes.size.coerceAtMost(256))
                .toString(Charsets.UTF_8)
                .trimStart()
                .lowercase()
            return prefix.startsWith("<!doctype html") || prefix.startsWith("<html")
        }

        private fun extractAttr(attrs: String, attrName: String): String? {
            val doubleQuoted = Regex("""\b$attrName\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)
            if (doubleQuoted != null) return doubleQuoted
            val singleQuoted = Regex("""\b$attrName\s*=\s*'([^']*)'""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)
            if (singleQuoted != null) return singleQuoted
            return Regex("""\b$attrName\s*=\s*([^\s"'=<>`]+)""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)
        }

        private fun buildFolderUrl(folderId: String, resourceKey: String?): String = buildString {
            append("https://drive.google.com/drive/folders/")
            append(encodeQuery(folderId))
            if (!resourceKey.isNullOrBlank()) {
                append("?resourcekey=").append(encodeQuery(resourceKey))
            }
        }

        private fun buildEmbeddedFolderViewUrl(folderId: String, resourceKey: String?): String = buildString {
            append("https://drive.google.com/embeddedfolderview?id=")
            append(encodeQuery(folderId))
            if (!resourceKey.isNullOrBlank()) {
                append("&resourcekey=").append(encodeQuery(resourceKey))
            }
            append("#list")
        }

        private fun buildFileViewUrl(fileId: String, resourceKey: String?): String = buildString {
            append("https://drive.google.com/file/d/")
            append(encodeQuery(fileId))
            append("/view")
            if (!resourceKey.isNullOrBlank()) {
                append("?resourcekey=").append(encodeQuery(resourceKey))
            }
        }

        private fun buildDriveOpenUrl(itemId: String, resourceKey: String?): String = buildString {
            append("https://drive.google.com/open?id=")
            append(encodeQuery(itemId))
            if (!resourceKey.isNullOrBlank()) {
                append("&resourcekey=").append(encodeQuery(resourceKey))
            }
        }

        private fun buildUserContentDownloadUrl(fileId: String, resourceKey: String?): String = buildString {
            append("https://drive.usercontent.google.com/download?id=")
            append(encodeQuery(fileId))
            append("&export=download&confirm=t")
            if (!resourceKey.isNullOrBlank()) {
                append("&resourcekey=").append(encodeQuery(resourceKey))
            }
        }

        private fun buildLegacyUcDownloadUrl(fileId: String, resourceKey: String?): String = buildString {
            append("https://drive.google.com/uc?export=download&id=")
            append(encodeQuery(fileId))
            append("&confirm=t")
            if (!resourceKey.isNullOrBlank()) {
                append("&resourcekey=").append(encodeQuery(resourceKey))
            }
        }

        private fun encodeQuery(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun parseQueryParams(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrBlank()) return emptyMap()
            val out = linkedMapOf<String, String>()
            for (pair in rawQuery.split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val key = runCatching { URLDecoder.decode(pair.substring(0, eq), "UTF-8") }.getOrNull() ?: continue
                val value = runCatching { URLDecoder.decode(pair.substring(eq + 1), "UTF-8") }.getOrNull() ?: continue
                out[key] = value
            }
            return out
        }
    }
}
