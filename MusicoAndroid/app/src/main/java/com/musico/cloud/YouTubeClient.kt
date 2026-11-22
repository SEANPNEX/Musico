package com.musico.cloud

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

sealed interface YouTubeTarget {
    data class Playlist(val listId: String, val videoId: String? = null) : YouTubeTarget
    data class Video(val videoId: String) : YouTubeTarget
}

class YouTubeClient(
    private val httpClient: CloudHttpClient = UrlConnectionCloudHttpClient(),
) {
    private data class InnertubeClientConfig(
        val name: String,
        val version: String,
        val clientId: Int,
        val userAgent: String,
        val androidSdkVersion: Int? = null,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val thirdPartyEmbedUrl: String? = null,
        val userAgentFieldInClient: Boolean = false,
    )

    @Volatile
    private var cachedVisitorData: Pair<String, Long>? = null

    suspend fun fetchPlaylist(sourceUrl: String, cookie: String? = null): CloudPlaylistResult {
        val target = parseTarget(sourceUrl)
            ?: throw IOException("Not a recognized YouTube URL or playlist ID.")

        return when (target) {
            is YouTubeTarget.Video -> fetchSingleVideo(target.videoId, cookie)
            is YouTubeTarget.Playlist -> fetchPlaylistInternal(target, cookie)
        }
    }

    private suspend fun fetchVisitorData(forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            val cached = cachedVisitorData
            if (cached != null && System.currentTimeMillis() - cached.second < VISITOR_DATA_TTL_MS) {
                return cached.first
            }
        }
        val visitor = runCatching {
            val resp = httpClient.get(
                url = "https://www.youtube.com/sw.js_data",
                headers = mapOf(
                    "User-Agent" to WEB_CLIENT.userAgent,
                    "Cookie" to ANONYMOUS_COOKIE,
                ),
            )
            if (resp.statusCode !in 200..299) return@runCatching null
            VISITOR_DATA_REGEX.find(resp.bodyText)?.groupValues?.getOrNull(1)
        }.getOrNull()
        if (!visitor.isNullOrBlank()) {
            cachedVisitorData = visitor to System.currentTimeMillis()
        }
        return visitor
    }

    private suspend fun fetchSingleVideo(videoId: String, cookie: String?): CloudPlaylistResult {
        val visitorData = fetchVisitorData()
        val clients = listOf(VISIONOS_CLIENT, ANDROID_CLIENT, IOS_CLIENT, WEB_CLIENT)
        var details: JsonObject? = null
        for (client in clients) {
            val playerJson = runCatching { requestPlayer(client, videoId, cookie, visitorData) }.getOrNull()
            val candidate = playerJson?.obj("videoDetails")
            if (candidate != null && !candidate.str("title").isNullOrBlank()) {
                details = candidate
                break
            }
            if (candidate != null && details == null) {
                details = candidate
            }
        }

        if (details != null) {
            val title = details.str("title").orEmpty().ifBlank { "YouTube ($videoId)" }
            val author = details.str("author").orEmpty().ifBlank { "YouTube" }
            val durationSec = details.str("lengthSeconds")?.toDoubleOrNull()
                ?: details.double("lengthSeconds")?.takeIf { !it.isNaN() }
                ?: 0.0
            val artworkUrl = bestThumbnailUrl(details.obj("thumbnail"))

            return CloudPlaylistResult(
                source = CloudSource.YOUTUBE,
                remoteId = videoId,
                title = title,
                tracks = listOf(
                    CloudTrackMetadata(
                        remoteTrackId = videoId,
                        title = title,
                        artist = author,
                        album = title,
                        durationSec = durationSec,
                        artworkUrl = artworkUrl,
                        trackNumber = 1,
                    )
                ),
            )
        }

        val oembed = fetchSingleVideoOEmbed(videoId, cookie)
        if (oembed != null) return oembed
        throw IOException("Could not load YouTube video details.")
    }

    private suspend fun fetchSingleVideoOEmbed(videoId: String, cookie: String?): CloudPlaylistResult? {
        return runCatching {
            val trimmedCookie = cookie?.trim()?.takeIf { it.isNotEmpty() } ?: ANONYMOUS_COOKIE
            val resp = httpClient.get(
                url = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json",
                headers = mapOf(
                    "User-Agent" to WEB_CLIENT.userAgent,
                    "Cookie" to trimmedCookie,
                ),
            )
            if (resp.statusCode !in 200..299) return@runCatching null
            val json = resp.bodyText.parseJsonObject()
            val title = json.str("title")?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val author = json.str("author_name")?.takeIf { it.isNotBlank() } ?: "YouTube"
            val artworkUrl = normalizeHttpsUrl(json.str("thumbnail_url"))
            CloudPlaylistResult(
                source = CloudSource.YOUTUBE,
                remoteId = videoId,
                title = title,
                tracks = listOf(
                    CloudTrackMetadata(
                        remoteTrackId = videoId,
                        title = title,
                        artist = author,
                        album = title,
                        durationSec = 0.0,
                        artworkUrl = artworkUrl,
                        trackNumber = 1,
                    )
                ),
            )
        }.getOrNull()
    }

    private suspend fun fetchPlaylistInternal(
        target: YouTubeTarget.Playlist,
        cookie: String?,
    ): CloudPlaylistResult {
        val listId = target.listId
        val isMix = listId.startsWith("RD")
        val browseId = if (
            listId.startsWith("VL") ||
            listId.startsWith("MPREb_") ||
            listId.startsWith("FEmusic_")
        ) {
            listId
        } else {
            "VL$listId"
        }

        var nextMixResult: CloudPlaylistResult? = null
        if (isMix) {
            nextMixResult = runCatching {
                fetchNextMix(listId, target.videoId, cookie)
            }.getOrNull()
            if (nextMixResult != null && nextMixResult.tracks.isNotEmpty()) {
                return nextMixResult
            }
        }

        val remixResult = runCatching {
            browsePlaylistWithClient(
                baseUrl = "https://music.youtube.com",
                origin = "https://music.youtube.com",
                client = WEB_REMIX_CLIENT,
                browseId = browseId,
                listId = listId,
                cookie = cookie,
            )
        }.getOrNull()

        if (remixResult != null && remixResult.tracks.isNotEmpty()) {
            return remixResult
        }

        val webResult = runCatching {
            browsePlaylistWithClient(
                baseUrl = "https://www.youtube.com",
                origin = "https://www.youtube.com",
                client = WEB_CLIENT,
                browseId = browseId,
                listId = listId,
                cookie = cookie,
            )
        }.getOrNull()

        if (webResult != null && webResult.tracks.isNotEmpty()) {
            return webResult
        }

        if (!isMix && target.videoId != null) {
            nextMixResult = runCatching {
                fetchNextMix(listId, target.videoId, cookie)
            }.getOrNull()
            if (nextMixResult != null && nextMixResult.tracks.isNotEmpty()) {
                return nextMixResult
            }
        }

        val htmlResult = runCatching {
            fetchPlaylistFromHtml(listId, cookie)
        }.getOrNull()
        if (htmlResult != null && htmlResult.tracks.isNotEmpty()) {
            return htmlResult
        }

        if (target.videoId != null) {
            val single = runCatching { fetchSingleVideo(target.videoId, cookie) }.getOrNull()
            if (single != null) return single
        }

        if (remixResult != null) return remixResult
        if (webResult != null) return webResult
        if (nextMixResult != null) return nextMixResult
        if (htmlResult != null) return htmlResult
        throw IOException("Could not fetch YouTube playlist $listId.")
    }

    private suspend fun fetchNextMix(
        listId: String,
        explicitVideoId: String?,
        cookie: String?,
    ): CloudPlaylistResult {
        val inferredVideoId = explicitVideoId ?: inferVideoIdFromMixListId(listId)
        val endpoint = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false"
        val headers = buildHeaders(
            origin = "https://www.youtube.com",
            client = WEB_CLIENT,
            cookie = cookie,
        )
        val body = buildJsonObject {
            put("context", buildContextJson(WEB_CLIENT))
            put("playlistId", listId)
            inferredVideoId?.let { put("videoId", it) }
        }.toString()

        val resp = httpClient.postJson(endpoint, body, headers)
        if (resp.statusCode !in 200..299) {
            throw IOException("YouTube next failed with HTTP ${resp.statusCode}")
        }

        val json = resp.bodyText.parseJsonObject()
        val parsedTitle = extractPlaylistTitle(json)
        val collected = LinkedHashMap<String, CloudTrackMetadata>()
        for (track in parsePlaylistTracksFromJson(json, fallbackAlbum = parsedTitle)) {
            collected.putIfAbsent(track.remoteTrackId, track)
        }
        val finalTitle = parsedTitle?.takeIf { it.isNotBlank() } ?: "YouTube Playlist ($listId)"
        val numbered = collected.values.mapIndexed { idx, track ->
            track.copy(
                album = track.album.ifBlank { finalTitle },
                trackNumber = idx + 1,
            )
        }
        return CloudPlaylistResult(
            source = CloudSource.YOUTUBE,
            remoteId = listId,
            title = finalTitle,
            tracks = numbered,
        )
    }

    private fun inferVideoIdFromMixListId(listId: String): String? {
        if (listId.startsWith("RDAMVM") && listId.length >= 17) {
            val candidate = listId.substring(6, 17)
            if (VIDEO_ID_REGEX.matches(candidate)) return candidate
        }
        if (listId.startsWith("RD") && listId.length == 13) {
            val candidate = listId.substring(2)
            if (VIDEO_ID_REGEX.matches(candidate)) return candidate
        }
        return null
    }

    private suspend fun fetchPlaylistFromHtml(listId: String, cookie: String?): CloudPlaylistResult? {
        val trimmedCookie = cookie?.trim()?.takeIf { it.isNotEmpty() } ?: ANONYMOUS_COOKIE
        val resp = httpClient.get(
            url = "https://www.youtube.com/playlist?list=$listId",
            headers = mapOf(
                "User-Agent" to WEB_CLIENT.userAgent,
                "Cookie" to trimmedCookie,
                "Accept-Language" to "en-US,en;q=0.9",
            ),
        )
        if (resp.statusCode !in 200..299) return null
        val json = extractYtInitialData(resp.bodyText) ?: return null
        val parsedTitle = extractPlaylistTitle(json)
        val collected = LinkedHashMap<String, CloudTrackMetadata>()
        for (track in parsePlaylistTracksFromJson(json, fallbackAlbum = parsedTitle)) {
            collected.putIfAbsent(track.remoteTrackId, track)
        }
        val finalTitle = parsedTitle?.takeIf { it.isNotBlank() } ?: "YouTube Playlist ($listId)"
        val numbered = collected.values.mapIndexed { idx, track ->
            track.copy(
                album = track.album.ifBlank { finalTitle },
                trackNumber = idx + 1,
            )
        }
        return CloudPlaylistResult(
            source = CloudSource.YOUTUBE,
            remoteId = listId,
            title = finalTitle,
            tracks = numbered,
        )
    }

    private fun extractYtInitialData(html: String): JsonObject? {
        var searchFrom = 0
        while (searchFrom < html.length) {
            val markerIdx = html.indexOf("ytInitialData", searchFrom)
            if (markerIdx < 0) return null
            searchFrom = markerIdx + "ytInitialData".length
            val eqIdx = html.indexOf('=', searchFrom)
            if (eqIdx < 0 || eqIdx - searchFrom > 16) continue
            var startIdx = eqIdx + 1
            while (startIdx < html.length && html[startIdx].isWhitespace()) startIdx++
            if (startIdx >= html.length || html[startIdx] != '{') continue

            var depth = 0
            var inString = false
            var escaped = false
            var endIdx = -1
            for (i in startIdx until html.length) {
                val ch = html[i]
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
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                endIdx = i
                                break
                            }
                        }
                    }
                }
            }
            if (endIdx > startIdx) {
                val parsed = runCatching { html.substring(startIdx, endIdx + 1).parseJsonObject() }.getOrNull()
                if (parsed != null) return parsed
            }
        }
        return null
    }

    private suspend fun browsePlaylistWithClient(
        baseUrl: String,
        origin: String,
        client: InnertubeClientConfig,
        browseId: String,
        listId: String,
        cookie: String?,
    ): CloudPlaylistResult {
        val endpoint = "$baseUrl/youtubei/v1/browse?prettyPrint=false"
        val headers = buildHeaders(origin = origin, client = client, cookie = cookie)

        val initialBody = buildJsonObject {
            put("context", buildContextJson(client))
            put("browseId", browseId)
        }.toString()

        val initialResp = httpClient.postJson(endpoint, initialBody, headers)
        if (initialResp.statusCode !in 200..299) {
            throw IOException("YouTube browse failed with HTTP ${initialResp.statusCode}")
        }

        val firstJson = initialResp.bodyText.parseJsonObject()
        val parsedTitle = extractPlaylistTitle(firstJson)
        val collected = LinkedHashMap<String, CloudTrackMetadata>()
        val seenTokens = mutableSetOf<String>()

        var currentJson: JsonObject? = firstJson
        var page = 0
        while (currentJson != null && page < MAX_BROWSE_PAGES) {
            val extracted = parsePlaylistTracksFromJson(currentJson, fallbackAlbum = parsedTitle)
            for (track in extracted) {
                collected.putIfAbsent(track.remoteTrackId, track)
            }
            val token = findContinuationToken(currentJson)
            if (token.isNullOrBlank() || !seenTokens.add(token)) {
                break
            }
            val contBody = buildJsonObject {
                put("context", buildContextJson(client))
                put("continuation", token)
            }.toString()
            val contResp = httpClient.postJson(endpoint, contBody, headers)
            if (contResp.statusCode !in 200..299) break
            currentJson = runCatching { contResp.bodyText.parseJsonObject() }.getOrNull()
            page += 1
        }

        val finalTitle = parsedTitle?.takeIf { it.isNotBlank() } ?: "YouTube Playlist ($listId)"
        val numbered = collected.values.mapIndexed { idx, track ->
            track.copy(
                album = track.album.ifBlank { finalTitle },
                trackNumber = idx + 1,
            )
        }

        return CloudPlaylistResult(
            source = CloudSource.YOUTUBE,
            remoteId = listId,
            title = finalTitle,
            tracks = numbered,
        )
    }

    suspend fun resolveStream(videoId: String, cookie: String? = null): ResolvedStream {
        val visitorData = fetchVisitorData()
        val clients = listOf(
            VISIONOS_CLIENT,
            ANDROID_CLIENT,
            IOS_CLIENT,
            ANDROID_VR_CLIENT,
            WEB_EMBEDDED_CLIENT,
        )
        var lastError: Throwable? = null

        for (client in clients) {
            val playerJson = runCatching { requestPlayer(client, videoId, cookie, visitorData) }
                .onFailure { lastError = it }
                .getOrNull() ?: continue

            val candidates = parseAudioCandidates(playerJson)
            if (candidates.isEmpty()) continue

            val headers = buildHeaders(
                origin = "https://www.youtube.com",
                client = client,
                cookie = cookie,
                visitorData = visitorData,
            )
            val expiresInSec = playerJson.obj("streamingData")
                ?.str("expiresInSeconds")
                ?.toLongOrNull()
                ?: DEFAULT_EXPIRE_SEC

            val probeHeaders = headers + ("Range" to "bytes=0-")
            for (candidate in candidates) {
                val probeCode = runCatching {
                    httpClient.probeRange(candidate.url, probeHeaders)
                }.getOrDefault(-1)

                if (probeCode == 200 || probeCode == 206) {
                    if (candidate.contentLength > POST_1MB_PROBE_THRESHOLD_BYTES) {
                        val post1MbHeaders = headers + ("Range" to "bytes=1048576-1049599")
                        val post1MbCode = runCatching {
                            httpClient.probeRange(candidate.url, post1MbHeaders)
                        }.getOrDefault(-1)
                        if (post1MbCode != 200 && post1MbCode != 206) {
                            continue
                        }
                    }

                    val ttlMs = ((expiresInSec - 120L).coerceAtLeast(300L)) * 1000L
                    return ResolvedStream(
                        url = candidate.url,
                        headers = headers,
                        mimeType = candidate.mimeType,
                        expiresAtEpochMs = System.currentTimeMillis() + ttlMs,
                    )
                }
            }
        }

        throw IOException("No playable stream found for YouTube video $videoId", lastError)
    }

    private suspend fun requestPlayer(
        client: InnertubeClientConfig,
        videoId: String,
        cookie: String?,
        visitorData: String? = null,
    ): JsonObject? {
        val endpoint = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        val headers = buildHeaders(
            origin = "https://www.youtube.com",
            client = client,
            cookie = cookie,
            visitorData = visitorData,
        )
        val body = buildJsonObject {
            put("context", buildContextJson(client, visitorData))
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString()

        val resp = httpClient.postJson(endpoint, body, headers)
        if (resp.statusCode !in 200..299) return null
        return runCatching { resp.bodyText.parseJsonObject() }.getOrNull()
    }

    private fun buildContextJson(
        client: InnertubeClientConfig,
        visitorData: String? = null,
    ): JsonObject = buildJsonObject {
        put(
            "client",
            buildJsonObject {
                put("clientName", client.name)
                put("clientVersion", client.version)
                put("hl", "en")
                put("gl", "US")
                if (client.userAgentFieldInClient) {
                    put("userAgent", client.userAgent)
                }
                visitorData?.takeIf { it.isNotBlank() }?.let { put("visitorData", it) }
                client.androidSdkVersion?.let { put("androidSdkVersion", it) }
                client.deviceMake?.let { put("deviceMake", it) }
                client.deviceModel?.let { put("deviceModel", it) }
                client.osName?.let { put("osName", it) }
                client.osVersion?.let { put("osVersion", it) }
            }
        )
        client.thirdPartyEmbedUrl?.let { embed ->
            put("thirdParty", buildJsonObject { put("embedUrl", embed) })
        }
    }

    private fun buildHeaders(
        origin: String,
        client: InnertubeClientConfig,
        cookie: String?,
        visitorData: String? = null,
    ): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to client.userAgent,
            "Origin" to origin,
            "X-Origin" to origin,
            "Referer" to "$origin/",
            "X-YouTube-Client-Name" to client.clientId.toString(),
            "X-YouTube-Client-Version" to client.version,
        )
        visitorData?.takeIf { it.isNotBlank() }?.let {
            headers["X-Goog-Visitor-Id"] = it
        }
        val trimmedCookie = cookie?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedCookie != null) {
            headers["Cookie"] = trimmedCookie
            computeSapisidHash(trimmedCookie, origin)?.let { hash ->
                headers["Authorization"] = hash
            }
        } else {
            headers["Cookie"] = ANONYMOUS_COOKIE
        }
        return headers
    }

    internal data class AudioCandidate(
        val itag: Int,
        val url: String,
        val mimeType: String?,
        val bitrate: Int,
        val contentLength: Long = 0L,
    )

    internal fun parseAudioCandidates(playerJson: JsonObject): List<AudioCandidate> {
        val streamingData = playerJson.obj("streamingData") ?: return emptyList()
        val raw = mutableListOf<AudioCandidate>()

        fun collectFrom(array: JsonArray?) {
            if (array == null) return
            for (i in 0 until array.size) {
                val obj = array.objAt(i) ?: continue
                val url = normalizeHttpsUrl(obj.str("url")) ?: continue
                val itag = obj.int("itag") ?: -1
                val mimeType = obj.str("mimeType")?.takeIf { it.isNotBlank() }
                val bitrate = obj.int("bitrate") ?: 0
                val contentLength = obj.str("contentLength")?.toLongOrNull()
                    ?: obj.long("contentLength")
                    ?: 0L
                val isAudio = mimeType?.startsWith("audio/", ignoreCase = true) == true || itag == 18
                if (isAudio) {
                    raw.add(
                        AudioCandidate(
                            itag = itag,
                            url = url,
                            mimeType = mimeType,
                            bitrate = bitrate,
                            contentLength = contentLength,
                        )
                    )
                }
            }
        }

        collectFrom(streamingData.arr("adaptiveFormats"))
        collectFrom(streamingData.arr("formats"))

        val preferredOrder = listOf(251, 250, 140, 141, 18)
        return raw.sortedWith(
            compareBy<AudioCandidate> {
                val idx = preferredOrder.indexOf(it.itag)
                if (idx >= 0) idx else Int.MAX_VALUE
            }.thenByDescending { it.bitrate }
        )
    }

    internal fun parsePlaylistTracksFromJson(
        root: JsonObject,
        fallbackAlbum: String? = null,
    ): List<CloudTrackMetadata> {
        val out = mutableListOf<CloudTrackMetadata>()
        val albumName = fallbackAlbum.orEmpty()

        fun visit(node: JsonElement?) {
            when (node) {
                is JsonObject -> {
                    node.obj("musicResponsiveListItemRenderer")?.let { renderer ->
                        parseMusicResponsiveItem(renderer, albumName)?.let(out::add)
                    }
                    node.obj("playlistVideoRenderer")?.let { renderer ->
                        parsePlaylistVideoRenderer(renderer, albumName)?.let(out::add)
                    }
                    node.obj("playlistPanelVideoRenderer")?.let { renderer ->
                        parsePlaylistVideoRenderer(renderer, albumName)?.let(out::add)
                    }
                    node.obj("lockupViewModel")?.let { vm ->
                        parseLockupViewModel(vm, albumName)?.let(out::add)
                    }
                    for ((key, value) in node) {
                        if (key !in RENDERER_KEYS) {
                            visit(value)
                        }
                    }
                }
                is JsonArray -> {
                    for (item in node) {
                        visit(item)
                    }
                }
                else -> Unit
            }
        }

        visit(root)
        return out
    }

    private fun parseMusicResponsiveItem(
        renderer: JsonObject,
        fallbackAlbum: String,
    ): CloudTrackMetadata? {
        val videoId = renderer.obj("playlistItemData")?.str("videoId")
            ?.takeIf { it.isNotBlank() }
            ?: renderer.obj("overlay")
                ?.obj("musicItemThumbnailOverlayRenderer")
                ?.obj("content")
                ?.obj("musicPlayButtonRenderer")
                ?.obj("playNavigationEndpoint")
                ?.obj("watchEndpoint")
                ?.str("videoId")
                ?.takeIf { it.isNotBlank() }
            ?: findFirstVideoIdInFlexColumns(renderer.arr("flexColumns"))
            ?: return null

        val flexColumns = renderer.arr("flexColumns")
        val title = flexColumns?.objAt(0)
            ?.obj("musicResponsiveListItemFlexColumnRenderer")
            ?.obj("text")
            ?.let(::runsText)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val subtitleParts = flexColumns.objAt(1)
            ?.obj("musicResponsiveListItemFlexColumnRenderer")
            ?.obj("text")
            ?.let(::splitRunsByBullet)
            .orEmpty()

        var artist = subtitleParts.firstOrNull { it.isNotBlank() && !isClockDuration(it) }.orEmpty()
        var album = ""
        var durationSec = 0.0

        for (part in subtitleParts.drop(1)) {
            if (isClockDuration(part)) {
                durationSec = parseClockDurationSec(part)
            } else if (album.isBlank() && !part.endsWith("plays", ignoreCase = true)) {
                album = part
            }
        }

        if (flexColumns.size > 2) {
            val col2 = flexColumns.objAt(2)
                ?.obj("musicResponsiveListItemFlexColumnRenderer")
                ?.obj("text")
                ?.let(::runsText)
            if (!col2.isNullOrBlank() && !isClockDuration(col2)) {
                album = col2
            }
        }
        if (album.isBlank()) {
            album = fallbackAlbum
        }

        if (durationSec <= 0.0) {
            val fixedText = renderer.arr("fixedColumns")
                ?.objAt(0)
                ?.obj("musicResponsiveListItemFixedColumnRenderer")
                ?.obj("text")
                ?.let(::runsText)
            if (fixedText != null) {
                durationSec = parseClockDurationSec(fixedText)
            }
        }

        if (artist.isBlank()) artist = "YouTube Music"
        val thumbObj = renderer.obj("thumbnail")
            ?.obj("musicThumbnailRenderer")
            ?.obj("thumbnail")
        val artworkUrl = bestThumbnailUrl(thumbObj)

        return CloudTrackMetadata(
            remoteTrackId = videoId,
            title = title,
            artist = artist,
            album = album,
            durationSec = durationSec,
            artworkUrl = artworkUrl,
        )
    }

    private fun parsePlaylistVideoRenderer(
        renderer: JsonObject,
        fallbackAlbum: String,
    ): CloudTrackMetadata? {
        val videoId = renderer.str("videoId")?.takeIf { it.isNotBlank() } ?: return null
        val title = renderer.obj("title")?.let(::runsText)?.takeIf { it.isNotBlank() }
            ?: return null
        val artist = (renderer.obj("shortBylineText") ?: renderer.obj("longBylineText"))
            ?.let(::runsText)
            ?.takeIf { it.isNotBlank() }
            ?: "YouTube"
        val durationSec = renderer.str("lengthSeconds")?.toDoubleOrNull()
            ?: renderer.double("lengthSeconds")?.takeIf { !it.isNaN() }
            ?: renderer.obj("lengthText")?.let(::runsText)?.let(::parseClockDurationSec)
            ?: 0.0
        val artworkUrl = bestThumbnailUrl(renderer.obj("thumbnail"))

        return CloudTrackMetadata(
            remoteTrackId = videoId,
            title = title,
            artist = artist,
            album = fallbackAlbum,
            durationSec = durationSec,
            artworkUrl = artworkUrl,
        )
    }

    private fun parseLockupViewModel(
        vm: JsonObject,
        fallbackAlbum: String,
    ): CloudTrackMetadata? {
        val contentId = vm.str("contentId")?.takeIf { it.isNotBlank() } ?: return null
        val contentType = vm.str("contentType")
        if (contentType != "LOCKUP_CONTENT_TYPE_VIDEO" && !VIDEO_ID_REGEX.matches(contentId)) {
            return null
        }

        val lockupMeta = vm.obj("metadata")?.obj("lockupMetadataViewModel")
        val title = lockupMeta?.obj("title")?.str("content")?.takeIf { it.isNotBlank() }
            ?: return null

        val metadataRows = lockupMeta.obj("metadata")
            ?.obj("contentMetadataViewModel")
            ?.arr("metadataRows")
            ?: lockupMeta.arr("metadataRows")
        var artist = ""
        if (metadataRows != null) {
            for (i in 0 until metadataRows.size) {
                val candidate = metadataRows.objAt(i)
                    ?.arr("metadataParts")
                    ?.objAt(0)
                    ?.obj("text")
                    ?.str("content")
                    ?.trim()
                if (!candidate.isNullOrEmpty() && !isClockDuration(candidate)) {
                    artist = candidate
                    break
                }
            }
        }
        if (artist.isBlank()) artist = "YouTube"

        val thumbVm = vm.obj("contentImage")?.obj("thumbnailViewModel")
        var durationSec = 0.0
        val overlays = thumbVm?.arr("overlays")
        if (overlays != null) {
            for (i in 0 until overlays.size) {
                val overlay = overlays.objAt(i) ?: continue
                val directText = overlay.obj("thumbnailBadgeViewModel")?.str("text")
                if (directText != null && isClockDuration(directText)) {
                    durationSec = parseClockDurationSec(directText)
                    break
                }
                val badges = overlay.obj("thumbnailOverlayBadgeViewModel")?.arr("thumbnailBadges")
                if (badges != null) {
                    for (j in 0 until badges.size) {
                        val badgeText = badges.objAt(j)?.obj("thumbnailBadgeViewModel")?.str("text")
                        if (badgeText != null && isClockDuration(badgeText)) {
                            durationSec = parseClockDurationSec(badgeText)
                            break
                        }
                    }
                    if (durationSec > 0.0) break
                }
            }
        }

        val artworkUrl = bestThumbnailUrlFromArray(thumbVm?.obj("image")?.arr("sources"))

        return CloudTrackMetadata(
            remoteTrackId = contentId,
            title = title,
            artist = artist,
            album = fallbackAlbum,
            durationSec = durationSec,
            artworkUrl = artworkUrl,
        )
    }

    private fun findFirstVideoIdInFlexColumns(flexColumns: JsonArray?): String? {
        if (flexColumns == null) return null
        for (i in 0 until flexColumns.size) {
            val runs = flexColumns.objAt(i)
                ?.obj("musicResponsiveListItemFlexColumnRenderer")
                ?.obj("text")
                ?.arr("runs") ?: continue
            for (j in 0 until runs.size) {
                val vid = runs.objAt(j)
                    ?.obj("navigationEndpoint")
                    ?.obj("watchEndpoint")
                    ?.str("videoId")
                    ?.takeIf { it.isNotBlank() }
                if (vid != null) return vid
            }
        }
        return null
    }

    private fun extractPlaylistTitle(root: JsonObject): String? {
        val header = root.obj("header")
        val pageHeader = header?.obj("pageHeaderRenderer")
        val fromHeader = pageHeader?.str("pageTitle")?.takeIf { it.isNotBlank() }
            ?: pageHeader?.obj("content")
                ?.obj("pageHeaderViewModel")
                ?.obj("title")
                ?.obj("dynamicTextViewModel")
                ?.obj("text")
                ?.str("content")
                ?.takeIf { it.isNotBlank() }
            ?: root.obj("metadata")
                ?.obj("playlistMetadataRenderer")
                ?.str("title")
                ?.takeIf { it.isNotBlank() }
            ?: header?.obj("musicDetailHeaderRenderer")?.obj("title")
                ?.let(::runsText)
            ?: header?.obj("musicResponsiveHeaderRenderer")?.obj("title")
                ?.let(::runsText)
            ?: header?.obj("playlistHeaderRenderer")?.obj("title")
                ?.let(::runsText)
            ?: header?.obj("musicEditablePlaylistDetailHeaderRenderer")
                ?.obj("header")
                ?.obj("musicDetailHeaderRenderer")
                ?.obj("title")
                ?.let(::runsText)
            ?: header?.obj("musicEditablePlaylistDetailHeaderRenderer")
                ?.obj("header")
                ?.obj("musicResponsiveHeaderRenderer")
                ?.obj("title")
                ?.let(::runsText)
        if (!fromHeader.isNullOrBlank()) return fromHeader

        val fromMicroformat = root.obj("microformat")
            ?.obj("microformatDataRenderer")
            ?.str("title")
            ?.takeIf { it.isNotBlank() }
        if (fromMicroformat != null) return fromMicroformat

        val watchPlaylist = root.obj("contents")
            ?.obj("twoColumnWatchNextResults")
            ?.obj("playlist")
            ?.obj("playlist")
        return watchPlaylist?.str("title")?.takeIf { it.isNotBlank() }
            ?: watchPlaylist?.obj("title")?.let(::runsText)
    }

    private fun findContinuationToken(root: JsonObject): String? {
        fun visit(node: JsonElement?): String? {
            when (node) {
                is JsonObject -> {
                    node.obj("continuationCommand")
                        ?.str("token")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                    node.obj("nextContinuationData")
                        ?.str("continuation")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                    for ((_, value) in node) {
                        visit(value)?.let { return it }
                    }
                }
                is JsonArray -> {
                    for (item in node) {
                        visit(item)?.let { return it }
                    }
                }
                else -> Unit
            }
            return null
        }
        return visit(root)
    }

    private fun runsText(textObj: JsonObject): String? {
        val simple = textObj.str("simpleText")?.takeIf { it.isNotBlank() }
        if (simple != null) return simple
        val runs = textObj.arr("runs") ?: return null
        val sb = StringBuilder()
        for (i in 0 until runs.size) {
            sb.append(runs.objAt(i)?.str("text").orEmpty())
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun splitRunsByBullet(textObj: JsonObject): List<String> {
        val runs = textObj.arr("runs") ?: return listOfNotNull(runsText(textObj))
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (i in 0 until runs.size) {
            val part = runs.objAt(i)?.str("text").orEmpty()
            if (part.trim() == "•" || part.trim() == "·") {
                val built = current.toString().trim()
                if (built.isNotEmpty()) parts.add(built)
                current.clear()
            } else {
                current.append(part)
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) parts.add(tail)
        return parts
    }

    private fun isClockDuration(text: String): Boolean =
        CLOCK_DURATION_REGEX.matches(text.trim())

    private fun bestThumbnailUrl(thumbnailObj: JsonObject?): String? =
        bestThumbnailUrlFromArray(thumbnailObj?.arr("thumbnails"))

    private fun bestThumbnailUrlFromArray(array: JsonArray?): String? {
        if (array == null) return null
        var bestUrl: String? = null
        var bestWidth = -1
        for (i in 0 until array.size) {
            val item = array.objAt(i) ?: continue
            val url = normalizeHttpsUrl(item.str("url")) ?: continue
            val width = item.int("width") ?: 0
            if (width >= bestWidth) {
                bestWidth = width
                bestUrl = url
            }
        }
        return bestUrl
    }

    companion object {
        private const val MAX_BROWSE_PAGES = 20
        private const val DEFAULT_EXPIRE_SEC = 18_000L
        private const val VISITOR_DATA_TTL_MS = 30L * 60L * 1000L
        private const val POST_1MB_PROBE_THRESHOLD_BYTES = 1_200_000L
        private const val ANONYMOUS_COOKIE = "SOCS=CAI"
        private val VISITOR_DATA_REGEX = Regex("\"(Cg[A-Za-z0-9_%-]{15,})\"")
        private val CLOCK_DURATION_REGEX = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
        private val PLAYLIST_ID_PREFIXES = listOf("PL", "OLAK5uy_", "RD", "UU", "LL", "FL", "WL", "MPREb_")
        private val RENDERER_KEYS = setOf(
            "musicResponsiveListItemRenderer",
            "playlistVideoRenderer",
            "playlistPanelVideoRenderer",
            "lockupViewModel",
        )

        private val WEB_REMIX_CLIENT = InnertubeClientConfig(
            name = "WEB_REMIX",
            version = "1.20260707.12.00",
            clientId = 67,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )

        private val WEB_CLIENT = InnertubeClientConfig(
            name = "WEB",
            version = "2.20260708.00.00",
            clientId = 1,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )

        private val VISIONOS_CLIENT = InnertubeClientConfig(
            name = "VISIONOS",
            version = "1.02",
            clientId = 101,
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            osName = "visionOS",
            osVersion = "26.5.23O471",
        )

        private val ANDROID_CLIENT = InnertubeClientConfig(
            name = "ANDROID",
            version = "20.10.38",
            clientId = 3,
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 15; en_US) gzip",
            androidSdkVersion = 35,
            osName = "Android",
            osVersion = "15",
        )

        private val IOS_CLIENT = InnertubeClientConfig(
            name = "IOS",
            version = "20.10.4",
            clientId = 5,
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X;)",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.3.1",
        )

        private val ANDROID_VR_CLIENT = InnertubeClientConfig(
            name = "ANDROID_VR",
            version = "1.65.10",
            clientId = 28,
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
            osName = "Android",
            osVersion = "12L",
        )

        private val WEB_EMBEDDED_CLIENT = InnertubeClientConfig(
            name = "WEB_EMBEDDED_PLAYER",
            version = "2.20260708.00.00",
            clientId = 56,
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            thirdPartyEmbedUrl = "https://www.reddit.com/",
        )

        fun canHandle(input: String): Boolean = parseTarget(input) != null

        fun parseTarget(input: String): YouTubeTarget? {
            val trimmed = extractCloudSourceInput(input)
            if (trimmed.isEmpty()) return null

            if (!trimmed.contains("://") && !trimmed.contains('/')) {
                val stripped = stripVlPrefix(trimmed)
                if ((PLAYLIST_ID_PREFIXES.any { stripped.startsWith(it) } && stripped.length >= 10) || stripped == "RDMM") {
                    return YouTubeTarget.Playlist(stripped)
                }
                if (VIDEO_ID_REGEX.matches(trimmed)) {
                    return YouTubeTarget.Video(trimmed)
                }
                return null
            }

            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
            val query = parseQueryParams(uri.rawQuery)
            val listId = query["list"]?.takeIf { it.isNotBlank() }?.let(::stripVlPrefix)

            if (host == "youtu.be") {
                val id = uri.path?.trim('/')?.substringBefore('/')?.takeIf { VIDEO_ID_REGEX.matches(it) }
                if (listId != null) {
                    return YouTubeTarget.Playlist(listId, id)
                }
                return id?.let { YouTubeTarget.Video(it) }
            }

            if (host != "youtube.com" && host != "music.youtube.com" && host != "youtube-nocookie.com") {
                return null
            }

            val videoFromQuery = query["v"]?.takeIf { VIDEO_ID_REGEX.matches(it) }
            val segments = uri.path?.trim('/')?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
            val videoFromPath = if (segments.size >= 2 && segments[0] in setOf("shorts", "live", "embed", "v")) {
                segments[1].takeIf { VIDEO_ID_REGEX.matches(it) }
            } else {
                null
            }
            val videoId = videoFromQuery ?: videoFromPath

            if (listId != null) {
                return YouTubeTarget.Playlist(listId, videoId)
            }

            if (segments.size >= 2 && segments[0] == "browse") {
                val browseId = stripVlPrefix(segments[1])
                if (browseId.isNotBlank()) {
                    return YouTubeTarget.Playlist(browseId)
                }
            }

            if (videoId != null) {
                return YouTubeTarget.Video(videoId)
            }

            return null
        }

        private fun stripVlPrefix(raw: String): String =
            if (raw.startsWith("VL") && raw.length > 4) raw.substring(2) else raw

        internal fun computeSapisidHash(
            cookie: String,
            origin: String,
            timestampSec: Long = System.currentTimeMillis() / 1000L,
        ): String? {
            val sapisid = cookie.split(';')
                .map { it.trim() }
                .firstNotNullOfOrNull { pair ->
                    val eq = pair.indexOf('=')
                    if (eq <= 0) null
                    else {
                        val key = pair.substring(0, eq).trim()
                        val value = pair.substring(eq + 1).trim()
                        if (key in setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID") && value.isNotEmpty()) {
                            value
                        } else {
                            null
                        }
                    }
                } ?: return null

            val digest = MessageDigest.getInstance("SHA-1")
                .digest("$timestampSec $sapisid $origin".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "SAPISIDHASH ${timestampSec}_$digest"
        }

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
