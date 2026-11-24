package com.musico.lyrics

import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpRequest
import com.musico.core.LrcParser
import com.musico.core.LyricsNameMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlin.math.abs

class NeteaseLyricsClient(
    private val httpClient: CloudHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val neteaseHeaders = mapOf(
        "Referer" to "https://music.163.com/",
        "Origin" to "https://music.163.com",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Cookie" to "appver=2.0.2; os=pc",
        "Accept" to "application/json",
    )

    suspend fun search(query: CloudLyricsQuery): List<CloudLyricsCandidate> = coroutineScope {
        val keyword = listOf(query.trimmedTitle, query.trimmedArtist)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (keyword.isEmpty()) throw CloudLyricsException.EmptyQuery()

        val songs = searchSongs(keyword)
        if (songs.isEmpty()) return@coroutineScope emptyList()

        val topSongs = selectTopSongs(songs, query, limit = 5)
        val candidates = topSongs.map { song ->
            async { fetchLyricCandidate(song) }
        }.awaitAll().filterNotNull()

        CloudLyricsScorer.rankAndDeduplicate(candidates, query)
    }

    private suspend fun searchSongs(keyword: String): List<NeteaseSong> {
        val primary = postLegacySearch(keyword)
        if (primary.isNotEmpty()) return primary
        return getCloudSearch(keyword)
    }

    private suspend fun postLegacySearch(keyword: String): List<NeteaseSong> {
        val body = "s=${URLEncoder.encode(keyword, "UTF-8")}&type=1&offset=0&limit=10"
        val resp = try {
            httpClient.request(
                CloudHttpRequest(
                    method = "POST",
                    url = "https://music.163.com/api/search/get",
                    headers = neteaseHeaders + ("Content-Type" to "application/x-www-form-urlencoded"),
                    body = body,
                    followRedirects = true,
                )
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (ex: CloudLyricsException) {
            throw ex
        } catch (ex: Exception) {
            throw CloudLyricsException.Network(ex.message ?: "NetEase search failed", ex)
        }
        if (resp.status == 429 || resp.status == 503) {
            throw CloudLyricsException.RateLimited(parseRetryAfter(resp.headers))
        }
        if (resp.status !in 200..299) return emptyList()
        return decodeSearchSongs(resp.text)
    }

    private suspend fun getCloudSearch(keyword: String): List<NeteaseSong> {
        val url = "https://music.163.com/api/cloudsearch/pc?s=${URLEncoder.encode(keyword, "UTF-8")}&type=1&offset=0&limit=10"
        val resp = try {
            httpClient.get(url = url, headers = neteaseHeaders, followRedirects = true)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (ex: CloudLyricsException) {
            throw ex
        } catch (ex: Exception) {
            throw CloudLyricsException.Network(ex.message ?: "NetEase cloud search failed", ex)
        }
        if (resp.statusCode == 429 || resp.statusCode == 503) {
            val lower = resp.headers.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            throw CloudLyricsException.RateLimited(parseRetryAfter(lower))
        }
        if (resp.statusCode !in 200..299) {
            throw CloudLyricsException.Network("NetEase HTTP ${resp.statusCode}")
        }
        return decodeSearchSongs(resp.bodyText)
    }

    private suspend fun fetchLyricCandidate(song: NeteaseSong): CloudLyricsCandidate? {
        val url = "https://music.163.com/api/song/lyric?os=pc&id=${song.id}&lv=-1&tv=-1&kv=-1"
        val resp = try {
            httpClient.get(url = url, headers = neteaseHeaders, followRedirects = true)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (ex: CloudLyricsException) {
            throw ex
        } catch (_: Exception) {
            return null
        }
        if (resp.statusCode == 429 || resp.statusCode == 503) {
            val lower = resp.headers.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            throw CloudLyricsException.RateLimited(parseRetryAfter(lower))
        }
        if (resp.statusCode !in 200..299) return null
        val payload = runCatching { json.decodeFromString<NeteaseLyricResponse>(resp.bodyText) }.getOrNull()
            ?: return null

        if (payload.nolyric == true) {
            return CloudLyricsCandidate(
                id = "${CloudLyricsProviderId.NETEASE}:${song.id}",
                providerId = CloudLyricsProviderId.NETEASE,
                sourceName = "NetEase",
                title = song.title,
                artist = song.artistName,
                album = song.albumName,
                durationSec = song.durationSec,
                isInstrumental = true,
            )
        }

        val primaryRaw = normalizeNeteaseLyricBody(payload.lrc?.lyric) ?: return null
        val transRaw = normalizeNeteaseLyricBody(payload.tlyric?.lyric)
        val parsed = LrcParser.parse(primaryRaw)
        if (parsed.lines.isEmpty()) return null

        val candidate = CloudLyricsCandidate(
            id = "${CloudLyricsProviderId.NETEASE}:${song.id}",
            providerId = CloudLyricsProviderId.NETEASE,
            sourceName = "NetEase",
            title = song.title,
            artist = song.artistName,
            album = song.albumName,
            durationSec = song.durationSec,
            isInstrumental = false,
            syncedLyrics = if (parsed.isTimed) primaryRaw else null,
            plainLyrics = if (parsed.isTimed) null else primaryRaw,
            translatedLyrics = transRaw,
        )
        return candidate.takeIf { it.hasUsableContent }
    }

    private fun parseRetryAfter(lowerHeaders: Map<String, String>): Int? =
        lowerHeaders["retry-after"]?.trim()?.toIntOrNull()

    private fun selectTopSongs(songs: List<NeteaseSong>, query: CloudLyricsQuery, limit: Int): List<NeteaseSong> {
        return songs.withIndex()
            .sortedWith { a, b ->
                val sa = songPreScore(a.value, query)
                val sb = songPreScore(b.value, query)
                if (abs(sa - sb) > 0.001) sb.compareTo(sa) else a.index.compareTo(b.index)
            }
            .take(limit)
            .map { it.value }
    }

    private fun songPreScore(song: NeteaseSong, query: CloudLyricsQuery): Double {
        var score = 0.0
        if (query.trimmedTitle.isNotEmpty()) {
            score += LyricsNameMatcher.similarity(song.title, query.trimmedTitle) * 50.0
        }
        if (query.trimmedArtist.isNotEmpty()) {
            score += LyricsNameMatcher.similarity(song.artistName, query.trimmedArtist) * 30.0
        }
        val qDur = query.durationSec
        val sDur = song.durationSec
        if (qDur != null && qDur > 0.0 && sDur != null && sDur > 0.0) {
            val diff = abs(sDur - qDur)
            score += when {
                diff <= 2.0 -> 20.0
                diff <= 6.0 -> 12.0
                diff <= 12.0 -> 4.0
                else -> 0.0
            }
        }
        return score
    }

    private fun decodeSearchSongs(raw: String): List<NeteaseSong> {
        val resp = runCatching { json.decodeFromString<NeteaseSearchResponse>(raw) }.getOrNull()
        return resp?.result?.songs.orEmpty().mapNotNull { it.toNormalized() }
    }

    internal fun normalizeNeteaseLyricBody(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val out = ArrayList<String>()
        for (rawLine in trimmed.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("{") && line.endsWith("}")) {
                val parsed = runCatching { json.decodeFromString<NeteaseJsonLyricLine>(line) }.getOrNull()
                if (parsed != null) {
                    val txt = parsed.c.orEmpty().mapNotNull { it.tx }.joinToString("").trim()
                    if (txt.isNotEmpty()) {
                        val sec = (parsed.t ?: 0.0) / 1000.0
                        out.add("${CloudLyricsFormatter.formatLrcTimestamp(sec)}$txt")
                    }
                    continue
                }
            }
            out.add(line)
        }
        return out.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }

    @Serializable
    private data class NeteaseSearchResponse(
        val code: Int? = null,
        val result: NeteaseSearchResult? = null,
    )

    @Serializable
    private data class NeteaseSearchResult(
        val songs: List<NeteaseSongDto>? = null,
    )

    @Serializable
    private data class NeteaseArtistDto(val name: String? = null)

    @Serializable
    private data class NeteaseAlbumDto(val name: String? = null)

    @Serializable
    private data class NeteaseSongDto(
        val id: Long? = null,
        val name: String? = null,
        val artists: List<NeteaseArtistDto>? = null,
        val ar: List<NeteaseArtistDto>? = null,
        val album: NeteaseAlbumDto? = null,
        val al: NeteaseAlbumDto? = null,
        val duration: Double? = null,
        val dt: Double? = null,
    ) {
        fun toNormalized(): NeteaseSong? {
            val songId = id ?: return null
            val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val artistList = (ar ?: artists).orEmpty()
                .mapNotNull { it.name?.trim()?.takeIf { s -> s.isNotEmpty() } }
            val albumName = (al ?: album)?.name?.trim()?.takeIf { it.isNotEmpty() }
            val ms = dt ?: duration
            val sec = if (ms != null && ms > 0.0) ms / 1000.0 else null
            return NeteaseSong(
                id = songId,
                title = title,
                artistName = artistList.joinToString(" / "),
                albumName = albumName,
                durationSec = sec,
            )
        }
    }

    private data class NeteaseSong(
        val id: Long,
        val title: String,
        val artistName: String,
        val albumName: String?,
        val durationSec: Double?,
    )

    @Serializable
    private data class NeteaseLyricResponse(
        val nolyric: Boolean? = null,
        val uncollected: Boolean? = null,
        val lrc: NeteaseLyricSection? = null,
        val tlyric: NeteaseLyricSection? = null,
    )

    @Serializable
    private data class NeteaseLyricSection(val lyric: String? = null)

    @Serializable
    private data class NeteaseJsonLyricLine(
        val t: Double? = null,
        val c: List<NeteaseJsonToken>? = null,
    )

    @Serializable
    private data class NeteaseJsonToken(val tx: String? = null)
}
