package com.musico.lyrics

import com.musico.BuildConfig
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlin.math.roundToInt

class LrclibClient(
    private val httpClient: CloudHttpClient,
    appVersion: String = BuildConfig.VERSION_NAME,
) {
    private val userAgent = "Musico/$appVersion (https://github.com/SEANPNEX/Musico)"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: CloudLyricsQuery): List<CloudLyricsCandidate> = coroutineScope {
        val title = query.trimmedTitle
        val artist = query.trimmedArtist
        if (title.isEmpty() && artist.isEmpty()) {
            throw CloudLyricsException.EmptyQuery()
        }

        val collected = ArrayList<LrclibRecord>()
        val seenIds = HashSet<Int>()

        if (title.isNotEmpty() && artist.isNotEmpty()) {
            val exactDeferred = async { fetchExact(query) }
            val structuredDeferred = async {
                fetchSearch(
                    listOf(
                        "track_name" to title,
                        "artist_name" to artist,
                    )
                )
            }
            exactDeferred.await()?.let { if (seenIds.add(it.id)) collected.add(it) }
            for (item in structuredDeferred.await()) {
                if (seenIds.add(item.id)) collected.add(item)
            }
            if (collected.isEmpty()) {
                val fallback = fetchSearch(listOf("q" to "$title $artist"))
                for (item in fallback) {
                    if (seenIds.add(item.id)) collected.add(item)
                }
            }
        } else {
            val keyword = if (title.isNotEmpty()) title else artist
            for (item in fetchSearch(listOf("q" to keyword))) {
                if (seenIds.add(item.id)) collected.add(item)
            }
        }

        val candidates = collected.mapNotNull { it.toCandidate() }
        CloudLyricsScorer.rankAndDeduplicate(candidates, query)
    }

    private suspend fun fetchExact(query: CloudLyricsQuery): LrclibRecord? {
        val params = ArrayList<Pair<String, String>>(4).apply {
            add("track_name" to query.trimmedTitle)
            add("artist_name" to query.trimmedArtist)
            query.trimmedAlbum?.let { add("album_name" to it) }
            query.durationSec?.takeIf { it.isFinite() && it > 0.0 }?.let {
                add("duration" to it.roundToInt().toString())
            }
        }
        val url = buildUrl("https://lrclib.net/api/get", params)
        val resp = sendGet(url)
        if (resp.statusCode == 404) return null
        checkRateLimitOrError(resp)
        return runCatching { json.decodeFromString<LrclibRecord>(resp.bodyText) }.getOrNull()
    }

    private suspend fun fetchSearch(params: List<Pair<String, String>>): List<LrclibRecord> {
        val url = buildUrl("https://lrclib.net/api/search", params)
        val resp = sendGet(url)
        if (resp.statusCode == 404) return emptyList()
        checkRateLimitOrError(resp)
        return runCatching { json.decodeFromString<List<LrclibRecord>>(resp.bodyText) }.getOrDefault(emptyList())
    }

    private suspend fun sendGet(url: String): CloudHttpResponse {
        return try {
            httpClient.get(
                url = url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json",
                ),
                followRedirects = true,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (ex: CloudLyricsException) {
            throw ex
        } catch (ex: Exception) {
            throw CloudLyricsException.Network(ex.message ?: "LRCLIB request failed", ex)
        }
    }

    private fun checkRateLimitOrError(resp: CloudHttpResponse) {
        if (resp.statusCode == 429 || resp.statusCode == 503) {
            val retryAfter = resp.headers.entries
                .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                ?.value?.firstOrNull()?.trim()?.toIntOrNull()
            throw CloudLyricsException.RateLimited(retryAfter)
        }
        if (resp.statusCode !in 200..299) {
            throw CloudLyricsException.Network("LRCLIB HTTP ${resp.statusCode}")
        }
    }

    private fun buildUrl(base: String, params: List<Pair<String, String>>): String {
        val queryStr = params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$base?$queryStr"
    }

    @Serializable
    private data class LrclibRecord(
        val id: Int,
        val name: String? = null,
        val trackName: String? = null,
        val artistName: String? = null,
        val albumName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean? = null,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    ) {
        fun toCandidate(): CloudLyricsCandidate? {
            val title = (trackName ?: name).orEmpty().trim()
            val artist = artistName.orEmpty().trim()
            val synced = syncedLyrics?.trim()?.takeIf { it.isNotEmpty() }
            val plain = plainLyrics?.trim()?.takeIf { it.isNotEmpty() }
            val isInst = instrumental == true
            if (title.isEmpty() && !isInst) return null
            val candidate = CloudLyricsCandidate(
                id = "${CloudLyricsProviderId.LRCLIB}:$id",
                providerId = CloudLyricsProviderId.LRCLIB,
                sourceName = "LRCLIB",
                title = title,
                artist = artist,
                album = albumName?.trim()?.takeIf { it.isNotEmpty() },
                durationSec = duration,
                isInstrumental = isInst,
                syncedLyrics = synced,
                plainLyrics = plain,
            )
            return candidate.takeIf { it.hasUsableContent }
        }
    }
}
