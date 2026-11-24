package com.musico.lyrics

import com.musico.core.LrcParser
import com.musico.core.LyricsNameMatcher
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object CloudLyricsProviderId {
    const val LRCLIB = "builtin.lrclib"
    const val NETEASE = "builtin.netease"
}

sealed interface CloudLyricsSourceSelection {
    data object Auto : CloudLyricsSourceSelection
    data object Lrclib : CloudLyricsSourceSelection
    data object Netease : CloudLyricsSourceSelection
    data class Extension(val extensionId: String) : CloudLyricsSourceSelection
}

data class CloudLyricsSourceOption(
    val id: String,
    val selection: CloudLyricsSourceSelection,
    val label: String,
)

data class CloudLyricsExtensionDescriptor(
    val id: String,
    val name: String,
)

data class CloudLyricsQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Double? = null,
) {
    val trimmedTitle: String get() = title.trim()
    val trimmedArtist: String get() = artist.trim()
    val trimmedAlbum: String? get() = album?.trim()?.takeIf { it.isNotEmpty() }
    val isEmpty: Boolean get() = trimmedTitle.isEmpty() && trimmedArtist.isEmpty()
}

data class CloudLyricsCandidate(
    val id: String,
    val providerId: String,
    val sourceName: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Double? = null,
    val isInstrumental: Boolean = false,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val translatedLyrics: String? = null,
) {
    val isTimed: Boolean
        get() {
            val s = syncedLyrics?.trim().orEmpty()
            return s.isNotEmpty() && LrcParser.parse(s).isTimed
        }

    val hasTranslation: Boolean
        get() {
            val t = translatedLyrics?.trim().orEmpty()
            return t.isNotEmpty() && LrcParser.parse(t).lines.isNotEmpty()
        }

    val hasUsableContent: Boolean
        get() = isInstrumental ||
            (!syncedLyrics.isNullOrBlank() && LrcParser.parse(syncedLyrics).lines.isNotEmpty()) ||
            (!plainLyrics.isNullOrBlank() && LrcParser.parse(plainLyrics).lines.isNotEmpty())

    fun formattedText(includeTranslation: Boolean): String {
        val synced = syncedLyrics?.trim().orEmpty()
        val trans = translatedLyrics?.trim().orEmpty()
        if (synced.isNotEmpty()) {
            return if (includeTranslation && trans.isNotEmpty()) {
                CloudLyricsFormatter.mergeSyncedWithTranslation(synced, trans)
            } else {
                synced
            }
        }
        val plain = plainLyrics?.trim().orEmpty()
        if (plain.isNotEmpty()) {
            return if (includeTranslation && trans.isNotEmpty()) {
                "$plain\n\n$trans"
            } else {
                plain
            }
        }
        return if (isInstrumental) CloudLyricsFormatter.INSTRUMENTAL_LRC else ""
    }
}

sealed class CloudLyricsException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class EmptyQuery : CloudLyricsException("Enter a song title or artist to search.")
    class RateLimited(val retryAfterSeconds: Int?) : CloudLyricsException(
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            "Lyrics service is busy. Try again in ${retryAfterSeconds}s."
        } else {
            "Lyrics service rate limited. Please try again shortly."
        },
    )
    class Network(message: String, cause: Throwable? = null) : CloudLyricsException(message, cause)
    class ProviderUnavailable(val providerName: String) : CloudLyricsException("Lyrics provider '$providerName' is unavailable.")
}

data class CloudLyricsSourceFailure(
    val providerId: String,
    val sourceName: String,
    val message: String,
)

data class CloudLyricsSearchResponse(
    val candidates: List<CloudLyricsCandidate>,
    val failures: List<CloudLyricsSourceFailure> = emptyList(),
)

object CloudLyricsFormatter {
    const val INSTRUMENTAL_LRC = "[00:00.00]♪ Instrumental ♪"

    fun formatDurationAndDelta(candidateSec: Double?, targetSec: Double?): String? {
        if (candidateSec == null || !candidateSec.isFinite() || candidateSec <= 0.0) return null
        val base = formatClock(candidateSec)
        if (targetSec == null || !targetSec.isFinite() || targetSec <= 0.0) return base
        val delta = (candidateSec - targetSec).roundToInt()
        if (delta == 0) return base
        val sign = if (delta > 0) "+" else ""
        return "$base ($sign${delta}s)"
    }

    fun formatClock(seconds: Double): String {
        val total = maxOf(0, seconds.roundToInt())
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    fun formatLrcTimestamp(seconds: Double): String {
        val totalHundredths = maxOf(0, (seconds * 100.0).roundToInt())
        val mins = totalHundredths / 6000
        val rem = totalHundredths % 6000
        val secs = rem / 100
        val cs = rem % 100
        return String.format(Locale.US, "[%02d:%02d.%02d]", mins, secs, cs)
    }

    fun mergeSyncedWithTranslation(primary: String, translation: String): String {
        val primaryParsed = LrcParser.parse(primary)
        val transParsed = LrcParser.parse(translation)
        if (!primaryParsed.isTimed || primaryParsed.lines.isEmpty()) return primary
        if (!transParsed.isTimed || transParsed.lines.isEmpty()) return primary

        val transTimed = transParsed.lines.mapNotNull { line ->
            val t = line.timeSec ?: return@mapNotNull null
            val txt = line.text.trim()
            if (txt.isEmpty() || txt == "//") null else (t to txt)
        }
        if (transTimed.isEmpty()) return primary

        val out = ArrayList<String>(primaryParsed.lines.size * 2)
        for (line in primaryParsed.lines) {
            val time = line.timeSec
            if (time == null) {
                val trimmed = line.text.trim()
                if (trimmed.isNotEmpty()) out.add(trimmed)
                continue
            }
            val tag = formatLrcTimestamp(time)
            out.add("$tag${line.text}")
            val match = closestTranslation(time, transTimed, toleranceSec = 0.05)
            if (match != null && match.second != line.text.trim()) {
                out.add("$tag${match.second}")
            }
        }
        return out.joinToString("\n")
    }

    private fun closestTranslation(
        targetTime: Double,
        lines: List<Pair<Double, String>>,
        toleranceSec: Double,
    ): Pair<Double, String>? {
        var best: Pair<Double, String>? = null
        var bestDiff = toleranceSec + 0.0001
        for (item in lines) {
            val diff = abs(item.first - targetTime)
            if (diff <= toleranceSec && diff < bestDiff) {
                bestDiff = diff
                best = item
            }
        }
        return best
    }
}

object CloudLyricsScorer {
    fun score(candidate: CloudLyricsCandidate, query: CloudLyricsQuery): Double {
        var total = 0.0
        val qTitle = query.trimmedTitle
        val qArtist = query.trimmedArtist
        val qAlbum = query.trimmedAlbum

        if (qTitle.isNotEmpty()) {
            total += LyricsNameMatcher.similarity(candidate.title, qTitle) * 42.0
        }
        if (qArtist.isNotEmpty()) {
            total += LyricsNameMatcher.similarity(candidate.artist, qArtist) * 28.0
        }
        if (!qAlbum.isNullOrEmpty() && !candidate.album.isNullOrBlank()) {
            total += LyricsNameMatcher.similarity(candidate.album, qAlbum) * 5.0
        }

        val qDur = query.durationSec
        val cDur = candidate.durationSec
        if (qDur != null && qDur.isFinite() && qDur > 0.0 && cDur != null && cDur.isFinite() && cDur > 0.0) {
            val diff = abs(cDur - qDur)
            total += when {
                diff <= 2.0 -> 20.0
                diff <= 5.0 -> 14.0
                diff <= 10.0 -> 7.0
                diff <= 20.0 -> 1.0
                else -> -minOf(18.0, (diff - 20.0) * 0.4)
            }
        }

        if (candidate.isTimed) {
            total += 15.0
        } else if (candidate.isInstrumental) {
            total -= 6.0
        }
        return total
    }

    fun rankAndDeduplicate(
        candidates: List<CloudLyricsCandidate>,
        query: CloudLyricsQuery,
    ): List<CloudLyricsCandidate> {
        val seen = HashSet<String>()
        val filtered = candidates.filter { candidate ->
            if (!candidate.hasUsableContent) return@filter false
            val snippet = (candidate.syncedLyrics ?: candidate.plainLyrics ?: "").take(80)
            val dedupKey = listOf(
                candidate.providerId,
                LyricsNameMatcher.normalizeKey(candidate.title),
                LyricsNameMatcher.normalizeKey(candidate.artist),
                if (candidate.isTimed) "1" else "0",
                snippet,
            ).joinToString("|")
            seen.add(dedupKey)
        }
        return filtered.withIndex()
            .sortedWith { a, b ->
                val sa = score(a.value, query)
                val sb = score(b.value, query)
                if (abs(sa - sb) > 0.001) {
                    sb.compareTo(sa)
                } else if (a.value.isTimed != b.value.isTimed) {
                    if (b.value.isTimed) 1 else -1
                } else {
                    a.index.compareTo(b.index)
                }
            }
            .map { it.value }
    }
}
