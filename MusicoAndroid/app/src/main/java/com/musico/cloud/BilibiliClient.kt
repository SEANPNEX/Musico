package com.musico.cloud

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TreeMap

sealed interface BilibiliTarget {
    data class FavList(val mediaId: String) : BilibiliTarget
    data class Collection(val mid: String, val seasonId: String) : BilibiliTarget
    data class Series(val mid: String, val seriesId: String) : BilibiliTarget
    data class Video(val bvid: String? = null, val aid: Long? = null) : BilibiliTarget
}

class BilibiliClient(
    private val httpClient: CloudHttpClient = UrlConnectionCloudHttpClient(),
) {
    private val authMutex = Mutex()
    private var cachedBuvid3: String? = null
    private var cachedWbiMixinKey: String? = null
    private var cachedWbiFetchedAtMs: Long = 0L

    suspend fun fetchPlaylist(sourceUrl: String, cookie: String? = null): CloudPlaylistResult {
        val target = resolveTarget(sourceUrl)
            ?: throw IOException("Not a recognized Bilibili URL or ID.")
        val effectiveCookie = resolveCookie(cookie)

        return when (target) {
            is BilibiliTarget.FavList -> fetchFavList(target.mediaId, effectiveCookie)
            is BilibiliTarget.Collection -> fetchCollection(target.mid, target.seasonId, effectiveCookie)
            is BilibiliTarget.Series -> fetchSeries(target.mid, target.seriesId, effectiveCookie)
            is BilibiliTarget.Video -> fetchVideo(target.bvid, target.aid, effectiveCookie)
        }
    }

    private suspend fun resolveTarget(input: String): BilibiliTarget? {
        val cleanInput = extractCloudSourceInput(input)
        parseTarget(cleanInput)?.let { return it }
        val lower = cleanInput.lowercase()
        if (lower.contains("b23.tv")) {
            val url = if (cleanInput.contains("://")) cleanInput else "https://$cleanInput"
            val resp = httpClient.get(url, headers = baseHeaders(null), followRedirects = true)
            return parseTarget(resp.finalUrl)
        }
        return null
    }

    private suspend fun fetchFavList(mediaId: String, cookie: String): CloudPlaylistResult {
        val tracks = mutableListOf<CloudTrackMetadata>()
        var title = "Bilibili Favlist ($mediaId)"
        var page = 1

        while (page <= MAX_PAGES) {
            val url = "https://api.bilibili.com/x/v3/fav/resource/list?media_id=$mediaId&pn=$page&ps=20&platform=web"
            val json = getJson(url, cookie)
            val data = json.obj("data") ?: break
            if (page == 1) {
                data.obj("info")?.str("title")?.takeIf { it.isNotBlank() }?.let {
                    title = it
                }
            }
            val medias = data.arr("medias") ?: break
            for (i in 0 until medias.size) {
                val item = medias.objAt(i) ?: continue
                if ((item.int("attr") ?: 0) == 9) continue
                val bvid = item.str("bvid")?.takeIf { it.isNotBlank() } ?: continue
                val itemTitle = item.str("title")?.takeIf { it.isNotBlank() } ?: bvid
                if (itemTitle == "已失效视频") continue
                val artist = item.obj("upper")?.str("name")?.takeIf { it.isNotBlank() }
                    ?: "Bilibili"
                val durationSec = item.double("duration")?.takeIf { !it.isNaN() } ?: 0.0
                val artworkUrl = normalizeHttpsUrl(item.str("cover"))
                tracks.add(
                    CloudTrackMetadata(
                        remoteTrackId = bvid,
                        title = itemTitle,
                        artist = artist,
                        album = title,
                        durationSec = durationSec,
                        artworkUrl = artworkUrl,
                        trackNumber = tracks.size + 1,
                    )
                )
            }
            if (data.bool("has_more") != true) break
            page += 1
        }

        return CloudPlaylistResult(
            source = CloudSource.BILIBILI,
            remoteId = "fav:$mediaId",
            title = title,
            tracks = tracks.map { it.copy(album = title) },
        )
    }

    private suspend fun fetchCollection(mid: String, seasonId: String, cookie: String): CloudPlaylistResult {
        val tracks = mutableListOf<CloudTrackMetadata>()
        var title = "Bilibili Collection ($seasonId)"
        var page = 1

        while (page <= MAX_PAGES) {
            val url = "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list" +
                "?mid=$mid&season_id=$seasonId&sort_reverse=false&page_num=$page&page_size=30"
            val json = getJson(url, cookie)
            val data = json.obj("data") ?: break
            if (page == 1) {
                data.obj("meta")?.str("name")?.takeIf { it.isNotBlank() }?.let {
                    title = it
                }
            }
            val archives = data.arr("archives") ?: break
            if (archives.isEmpty()) break

            for (i in 0 until archives.size) {
                val item = archives.objAt(i) ?: continue
                val bvid = item.str("bvid")?.takeIf { it.isNotBlank() } ?: continue
                val itemTitle = item.str("title")?.takeIf { it.isNotBlank() } ?: bvid
                val artist = item.str("author")?.takeIf { it.isNotBlank() }
                    ?: item.obj("owner")?.str("name")?.takeIf { it.isNotBlank() }
                    ?: "Bilibili"
                val durationSec = item.double("duration")?.takeIf { !it.isNaN() } ?: 0.0
                val artworkUrl = normalizeHttpsUrl(item.str("pic"))
                tracks.add(
                    CloudTrackMetadata(
                        remoteTrackId = bvid,
                        title = itemTitle,
                        artist = artist,
                        album = title,
                        durationSec = durationSec,
                        artworkUrl = artworkUrl,
                        trackNumber = tracks.size + 1,
                    )
                )
            }

            val total = data.obj("page")?.int("total") ?: 0
            if (archives.size < 30 || (total > 0 && tracks.size >= total)) break
            page += 1
        }

        return CloudPlaylistResult(
            source = CloudSource.BILIBILI,
            remoteId = "season:$mid:$seasonId",
            title = title,
            tracks = tracks.map { it.copy(album = title) },
        )
    }

    private suspend fun fetchSeries(mid: String, seriesId: String, cookie: String): CloudPlaylistResult {
        var title = "Bilibili Series ($seriesId)"
        runCatching {
            val metaJson = getJson("https://api.bilibili.com/x/series/series?series_id=$seriesId", cookie)
            metaJson.obj("data")?.obj("meta")?.str("name")
                ?.takeIf { it.isNotBlank() }
                ?.let { title = it }
        }

        val tracks = mutableListOf<CloudTrackMetadata>()
        var page = 1
        while (page <= MAX_PAGES) {
            val url = "https://api.bilibili.com/x/series/archives" +
                "?mid=$mid&series_id=$seriesId&only_normal=true&sort=desc&pn=$page&ps=30"
            val json = getJson(url, cookie)
            val data = json.obj("data") ?: break
            val archives = data.arr("archives") ?: break
            if (archives.isEmpty()) break

            for (i in 0 until archives.size) {
                val item = archives.objAt(i) ?: continue
                val bvid = item.str("bvid")?.takeIf { it.isNotBlank() } ?: continue
                val itemTitle = item.str("title")?.takeIf { it.isNotBlank() } ?: bvid
                val artist = item.str("author")?.takeIf { it.isNotBlank() }
                    ?: item.obj("owner")?.str("name")?.takeIf { it.isNotBlank() }
                    ?: "Bilibili"
                val durationSec = item.double("duration")?.takeIf { !it.isNaN() } ?: 0.0
                val artworkUrl = normalizeHttpsUrl(item.str("pic"))
                tracks.add(
                    CloudTrackMetadata(
                        remoteTrackId = bvid,
                        title = itemTitle,
                        artist = artist,
                        album = title,
                        durationSec = durationSec,
                        artworkUrl = artworkUrl,
                        trackNumber = tracks.size + 1,
                    )
                )
            }

            val total = data.obj("page")?.int("total") ?: 0
            if (archives.size < 30 || (total > 0 && tracks.size >= total)) break
            page += 1
        }

        return CloudPlaylistResult(
            source = CloudSource.BILIBILI,
            remoteId = "series:$mid:$seriesId",
            title = title,
            tracks = tracks.map { it.copy(album = title) },
        )
    }

    private suspend fun fetchVideo(bvid: String?, aid: Long?, cookie: String): CloudPlaylistResult {
        val query = when {
            !bvid.isNullOrBlank() -> "bvid=$bvid"
            aid != null -> "aid=$aid"
            else -> throw IOException("Missing Bilibili video ID.")
        }
        val json = getJson("https://api.bilibili.com/x/web-interface/view?$query", cookie)
        val data = json.obj("data") ?: throw IOException("Bilibili video data missing.")

        val resolvedBvid = data.str("bvid").orEmpty().ifBlank { bvid ?: "av$aid" }
        val videoTitle = data.str("title").orEmpty().ifBlank { resolvedBvid }
        val artist = data.obj("owner")?.str("name")?.takeIf { it.isNotBlank() }
            ?: "Bilibili"
        val artworkUrl = normalizeHttpsUrl(data.str("pic"))
        val totalDuration = data.double("duration")?.takeIf { !it.isNaN() } ?: 0.0
        val pages = data.arr("pages")

        val tracks = if (pages != null && pages.size > 1) {
            (0 until pages.size).mapNotNull { i ->
                val pageObj = pages.objAt(i) ?: return@mapNotNull null
                val cid = pageObj.long("cid") ?: 0L
                if (cid <= 0L) return@mapNotNull null
                val pageNum = pageObj.int("page") ?: (i + 1)
                val partName = pageObj.str("part")?.trim().orEmpty()
                val trackTitle = if (partName.isNotEmpty()) {
                    "P$pageNum $partName"
                } else {
                    "$videoTitle - P$pageNum"
                }
                val partDur = pageObj.double("duration")?.takeIf { !it.isNaN() }
                    ?: totalDuration
                CloudTrackMetadata(
                    remoteTrackId = "$resolvedBvid#$cid",
                    title = trackTitle,
                    artist = artist,
                    album = videoTitle,
                    durationSec = partDur,
                    artworkUrl = artworkUrl,
                    trackNumber = pageNum,
                )
            }
        } else {
            val cid = pages?.objAt(0)?.long("cid")?.takeIf { it > 0L }
                ?: data.long("cid")?.takeIf { it > 0L }
            val remoteTrackId = if (cid != null) "$resolvedBvid#$cid" else resolvedBvid
            listOf(
                CloudTrackMetadata(
                    remoteTrackId = remoteTrackId,
                    title = videoTitle,
                    artist = artist,
                    album = videoTitle,
                    durationSec = totalDuration,
                    artworkUrl = artworkUrl,
                    trackNumber = 1,
                )
            )
        }

        return CloudPlaylistResult(
            source = CloudSource.BILIBILI,
            remoteId = "video:$resolvedBvid",
            title = videoTitle,
            tracks = tracks,
        )
    }

    internal data class SelectedAudioStream(
        val url: String,
        val actualQuality: String?,
        val qualityLabel: String?,
        val bitrateKbps: Int?,
        val mimeType: String = "audio/mp4",
    )

    suspend fun resolveStream(
        remoteTrackId: String,
        cookie: String? = null,
        quality: String? = null,
    ): ResolvedStream {
        val effectiveCookie = resolveCookie(cookie)
        val parts = remoteTrackId.split('#', limit = 2)
        val rawId = parts[0]
        var cid = parts.getOrNull(1)?.toLongOrNull()
        var bvid = if (rawId.startsWith("BV", ignoreCase = true)) rawId else null
        val aid = if (rawId.startsWith("av", ignoreCase = true)) rawId.substring(2).toLongOrNull() else null

        if (cid == null || bvid == null) {
            val query = when {
                bvid != null -> "bvid=$bvid"
                aid != null -> "aid=$aid"
                else -> throw IOException("Invalid Bilibili track ID: $remoteTrackId")
            }
            val viewJson = getJson("https://api.bilibili.com/x/web-interface/view?$query", effectiveCookie)
            val viewData = viewJson.obj("data")
                ?: throw IOException("Could not resolve Bilibili cid for $remoteTrackId")
            if (bvid == null) {
                bvid = viewData.str("bvid")?.takeIf { it.isNotBlank() }
            }
            if (cid == null) {
                cid = viewData.long("cid")?.takeIf { it > 0L }
                    ?: viewData.arr("pages")?.objAt(0)?.long("cid")?.takeIf { it > 0L }
            }
        }

        val resolvedCid = cid ?: throw IOException("Missing Bilibili cid for $remoteTrackId")
        val resolvedBvid = bvid ?: throw IOException("Missing Bilibili bvid for $remoteTrackId")

        val params = mapOf(
            "bvid" to resolvedBvid,
            "cid" to resolvedCid.toString(),
            "qn" to "80",
            "fnval" to "4048",
            "fnver" to "0",
            "fourk" to "1",
        )

        val playJson = runCatching {
            val mixinKey = getWbiMixinKey(effectiveCookie)
            val signedQuery = signWbiParams(params, mixinKey)
            getJson("https://api.bilibili.com/x/player/wbi/playurl?$signedQuery", effectiveCookie)
        }.getOrNull() ?: run {
            val plainQuery = params.entries.joinToString("&") { (k, v) -> "$k=$v" }
            getJson("https://api.bilibili.com/x/player/playurl?$plainQuery", effectiveCookie)
        }

        val selected = extractBestAudioStream(playJson, quality = quality)
            ?: throw IOException("No playable audio stream in Bilibili playurl response.")

        val headers = baseHeaders(
            cookie = effectiveCookie,
            referer = "https://www.bilibili.com/video/$resolvedBvid",
        )
        return ResolvedStream(
            url = selected.url,
            headers = headers,
            mimeType = selected.mimeType,
            expiresAtEpochMs = System.currentTimeMillis() + DEFAULT_STREAM_TTL_MS,
            actualQuality = selected.actualQuality,
            qualityLabel = selected.qualityLabel,
            bitrateKbps = selected.bitrateKbps,
        )
    }

    internal fun extractBestAudioUrl(playJson: JsonObject, quality: String? = null): String? =
        extractBestAudioStream(playJson, quality)?.url

    internal fun extractBestAudioStream(playJson: JsonObject, quality: String? = null): SelectedAudioStream? {
        val data = playJson.obj("data") ?: return null
        val dash = data.obj("dash")
        if (dash != null) {
            data class DashAudioCandidate(
                val id: Int,
                val bandwidth: Int,
                val url: String,
                val mimeType: String?,
            )
            val candidates = mutableListOf<DashAudioCandidate>()

            fun addAudioObj(obj: JsonObject?) {
                if (obj == null) return
                val id = obj.int("id") ?: 0
                val bandwidth = obj.int("bandwidth") ?: 0
                val mimeType = obj.str("mimeType")?.takeIf { it.isNotBlank() }
                    ?: obj.str("mime_type")?.takeIf { it.isNotBlank() }
                val rawUrl = obj.str("baseUrl").orEmpty()
                    .ifBlank { obj.str("base_url").orEmpty() }
                    .ifBlank { (obj.arr("backupUrl")?.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty() }
                    .ifBlank { (obj.arr("backup_url")?.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty() }
                val normalized = normalizeHttpsUrl(rawUrl) ?: return
                candidates.add(DashAudioCandidate(id = id, bandwidth = bandwidth, url = normalized, mimeType = mimeType))
            }

            val audioArr = dash.arr("audio")
            if (audioArr != null) {
                for (i in 0 until audioArr.size) {
                    addAudioObj(audioArr.objAt(i))
                }
            }
            dash.obj("flac")?.obj("audio")?.let { addAudioObj(it) }

            if (candidates.isNotEmpty()) {
                val preferredIds = preferredIdsForQuality(quality)
                val best = candidates.sortedWith(
                    compareBy<DashAudioCandidate> { cand ->
                        val idx = preferredIds.indexOf(cand.id)
                        if (idx >= 0) idx else Int.MAX_VALUE
                    }.thenByDescending { it.bandwidth }
                ).first()
                val (actualQuality, qualityLabel, defaultKbps) = metadataForDashId(best.id, best.bandwidth)
                val kbps = (best.bandwidth / 1000).takeIf { it > 0 } ?: defaultKbps
                val mime = best.mimeType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: "audio/mp4"
                return SelectedAudioStream(
                    url = best.url,
                    actualQuality = actualQuality,
                    qualityLabel = qualityLabel,
                    bitrateKbps = kbps,
                    mimeType = mime,
                )
            }
        }

        val durl = data.arr("durl")
        if (durl != null && durl.isNotEmpty()) {
            val first = durl.objAt(0)
            val url = first?.str("url").orEmpty()
                .ifBlank { (first?.arr("backup_url")?.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty() }
            val normalized = normalizeHttpsUrl(url) ?: return null
            return SelectedAudioStream(
                url = normalized,
                actualQuality = quality?.trim()?.takeIf { it.isNotEmpty() } ?: "standard",
                qualityLabel = "132k",
                bitrateKbps = 132,
                mimeType = "audio/mp4",
            )
        }
        return null
    }

    private suspend fun getJson(url: String, cookie: String?): JsonObject {
        val resp = httpClient.get(url, headers = baseHeaders(cookie))
        if (resp.statusCode !in 200..299) {
            throw IOException("Bilibili HTTP ${resp.statusCode} for $url")
        }
        val json = resp.bodyText.parseJsonObject()
        val code = json.int("code") ?: 0
        if (code != 0) {
            val msg = json.str("message").orEmpty().ifBlank { "code $code" }
            throw IOException("Bilibili API error ($code): $msg")
        }
        return json
    }

    private suspend fun resolveCookie(userCookie: String?): String {
        val trimmed = userCookie?.trim().orEmpty()
        if (trimmed.contains("buvid3=")) return trimmed

        val buvid3 = authMutex.withLock {
            cachedBuvid3 ?: run {
                val resp = runCatching {
                    httpClient.get("https://api.bilibili.com/x/frontend/finger/spi", headers = baseHeaders(null))
                }.getOrNull()
                val fetched = resp?.takeIf { it.statusCode in 200..299 }?.let {
                    runCatching {
                        it.bodyText.parseJsonObject().obj("data")?.str("b_3")
                    }.getOrNull()
                }?.takeIf { it.isNotBlank() }
                    ?: "B0E2A3F1-1234-4567-89AB-CDEF01234567infoc"
                cachedBuvid3 = fetched
                fetched
            }
        }

        return if (trimmed.isEmpty()) {
            "buvid3=$buvid3"
        } else {
            "$trimmed; buvid3=$buvid3"
        }
    }

    private suspend fun getWbiMixinKey(cookie: String?): String {
        val now = System.currentTimeMillis()
        authMutex.withLock {
            val cached = cachedWbiMixinKey
            if (cached != null && now - cachedWbiFetchedAtMs < WBI_CACHE_TTL_MS) {
                return cached
            }
        }
        val resp = httpClient.get("https://api.bilibili.com/x/web-interface/nav", headers = baseHeaders(cookie))
        if (resp.statusCode !in 200..299) {
            throw IOException("Bilibili WBI nav failed with HTTP ${resp.statusCode}")
        }
        val wbiImg = resp.bodyText.parseJsonObject()
            .obj("data")
            ?.obj("wbi_img")
            ?: throw IOException("Bilibili WBI img keys missing.")

        val imgUrl = wbiImg.str("img_url").orEmpty()
        val subUrl = wbiImg.str("sub_url").orEmpty()
        val mixinKey = extractMixinKey(imgUrl, subUrl)

        authMutex.withLock {
            cachedWbiMixinKey = mixinKey
            cachedWbiFetchedAtMs = System.currentTimeMillis()
        }
        return mixinKey
    }

    private fun baseHeaders(
        cookie: String?,
        referer: String = "https://www.bilibili.com/",
    ): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Origin" to "https://www.bilibili.com",
        )
        if (!cookie.isNullOrBlank()) {
            headers["Cookie"] = cookie
        }
        return headers
    }

    companion object {
        val AVAILABLE_QUALITIES: List<CloudQualityOption> = listOf(
            CloudQualityOption(id = "highest", label = "Highest (FLAC / 192k)", badge = "Hi-Res", lossless = true),
            CloudQualityOption(id = "high", label = "High (192k)", badge = "192k"),
            CloudQualityOption(id = "standard", label = "Standard (132k)", badge = "132k"),
            CloudQualityOption(id = "low", label = "Low (64k)", badge = "64k"),
        )
        const val DEFAULT_STREAM_QUALITY_ID = "standard"
        const val DEFAULT_DOWNLOAD_QUALITY_ID = "highest"

        private fun preferredIdsForQuality(quality: String?): List<Int> = when (quality?.trim()?.lowercase()) {
            "low" -> listOf(30216, 30232, 30280, 30251)
            "standard" -> listOf(30232, 30280, 30216, 30251)
            "high" -> listOf(30280, 30232, 30216, 30251)
            "highest", null, "" -> listOf(30251, 30280, 30232, 30216)
            else -> listOf(30251, 30280, 30232, 30216)
        }

        private fun metadataForDashId(id: Int, bandwidth: Int): Triple<String, String, Int> = when (id) {
            30251 -> Triple("highest", "FLAC", 1411)
            30280 -> Triple("high", "192k", 192)
            30232 -> Triple("standard", "132k", 132)
            30216 -> Triple("low", "64k", 64)
            else -> {
                val kbps = (bandwidth / 1000).takeIf { it > 0 } ?: 132
                val qId = when {
                    kbps >= 500 -> "highest"
                    kbps >= 170 -> "high"
                    kbps >= 96 -> "standard"
                    else -> "low"
                }
                Triple(qId, "${kbps}k", kbps)
            }
        }

        private const val MAX_PAGES = 20
        private const val WBI_CACHE_TTL_MS = 6L * 60L * 60L * 1000L
        private const val DEFAULT_STREAM_TTL_MS = 90L * 60L * 1000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        val WBI_MIXIN_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
        )

        private val BVID_REGEX = Regex("BV[0-9A-Za-z]{10}", RegexOption.IGNORE_CASE)
        private val AVID_REGEX = Regex("\\bav(\\d+)\\b", RegexOption.IGNORE_CASE)
        private val ML_REGEX = Regex("^ml(\\d+)$", RegexOption.IGNORE_CASE)

        fun canHandle(input: String): Boolean {
            val trimmed = extractCloudSourceInput(input).lowercase()
            if (trimmed.contains("bilibili.com") || trimmed.contains("b23.tv")) return true
            return parseTarget(trimmed) != null
        }

        fun parseTarget(input: String): BilibiliTarget? {
            val trimmed = extractCloudSourceInput(input)
            if (trimmed.isEmpty()) return null

            if (!trimmed.contains("://") && !trimmed.contains('/')) {
                BVID_REGEX.find(trimmed)?.let {
                    return BilibiliTarget.Video(bvid = normalizeBvid(it.value))
                }
                AVID_REGEX.find(trimmed)?.let {
                    val aid = it.groupValues[1].toLongOrNull() ?: return null
                    return BilibiliTarget.Video(aid = aid)
                }
                ML_REGEX.matchEntire(trimmed)?.let {
                    return BilibiliTarget.FavList(mediaId = it.groupValues[1])
                }
                return null
            }

            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (!host.endsWith("bilibili.com")) return null

            val path = uri.path.orEmpty()
            val query = parseQueryParams(uri.rawQuery)

            query["fid"]?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }?.let {
                return BilibiliTarget.FavList(mediaId = it)
            }
            query["media_id"]?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }?.let {
                return BilibiliTarget.FavList(mediaId = it)
            }
            Regex("/medialist/detail/ml(\\d+)", RegexOption.IGNORE_CASE).find(path)?.let {
                return BilibiliTarget.FavList(mediaId = it.groupValues[1])
            }

            val segments = path.trim('/').split('/').filter { it.isNotEmpty() }

            if (host == "space.bilibili.com" && segments.size >= 3 && segments[1] == "lists") {
                val mid = segments[0]
                val sid = segments[2]
                val type = query["type"]?.lowercase()
                return if (type == "series") {
                    BilibiliTarget.Series(mid = mid, seriesId = sid)
                } else {
                    BilibiliTarget.Collection(mid = mid, seasonId = sid)
                }
            }

            if (host == "space.bilibili.com" && segments.size >= 3 && segments[2] == "collectiondetail") {
                val mid = segments[0]
                val sid = query["sid"] ?: query["season_id"]
                if (!sid.isNullOrBlank()) {
                    return BilibiliTarget.Collection(mid = mid, seasonId = sid)
                }
            }

            if (host == "space.bilibili.com" && segments.size >= 3 && segments[2] == "seriesdetail") {
                val mid = segments[0]
                val sid = query["sid"] ?: query["series_id"]
                if (!sid.isNullOrBlank()) {
                    return BilibiliTarget.Series(mid = mid, seriesId = sid)
                }
            }

            val qMid = query["mid"]
            val qSeason = query["season_id"]
            if (!qMid.isNullOrBlank() && !qSeason.isNullOrBlank()) {
                return BilibiliTarget.Collection(mid = qMid, seasonId = qSeason)
            }
            val qSeries = query["series_id"]
            if (!qMid.isNullOrBlank() && !qSeries.isNullOrBlank()) {
                return BilibiliTarget.Series(mid = qMid, seriesId = qSeries)
            }

            BVID_REGEX.find(path)?.let {
                return BilibiliTarget.Video(bvid = normalizeBvid(it.value))
            }
            Regex("/video/av(\\d+)", RegexOption.IGNORE_CASE).find(path)?.let {
                val aid = it.groupValues[1].toLongOrNull() ?: return null
                return BilibiliTarget.Video(aid = aid)
            }

            return null
        }

        fun extractMixinKey(imgUrl: String, subUrl: String): String {
            val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
            val subKey = subUrl.substringAfterLast('/').substringBefore('.')
            val raw = imgKey + subKey
            val sb = StringBuilder(32)
            for (i in 0 until 32) {
                val idx = WBI_MIXIN_TAB[i]
                if (idx in raw.indices) {
                    sb.append(raw[idx])
                }
            }
            return sb.toString()
        }

        fun signWbiParams(
            params: Map<String, String>,
            mixinKey: String,
            wtsSec: Long = System.currentTimeMillis() / 1000L,
        ): String {
            val sorted = TreeMap<String, String>()
            for ((k, v) in params) {
                sorted[k] = v
            }
            sorted["wts"] = wtsSec.toString()

            val query = sorted.entries.joinToString("&") { (k, v) ->
                val filtered = v.filterNot { it in "!'()*" }
                "${encodeRfc3986(k)}=${encodeRfc3986(filtered)}"
            }
            val md5 = MessageDigest.getInstance("MD5")
                .digest((query + mixinKey).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "$query&w_rid=$md5"
        }

        private fun encodeRfc3986(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")

        private fun normalizeBvid(raw: String): String =
            if (raw.length >= 2) "BV${raw.substring(2)}" else raw

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
