package com.musico.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.text.Normalizer

@Serializable
enum class RootKind {
    PICKED,
    APP_FOLDER,
    CLOUD_YOUTUBE,
    CLOUD_BILIBILI,
    ;

    val isCloud: Boolean
        get() = this == CLOUD_YOUTUBE || this == CLOUD_BILIBILI
}

@Serializable
data class LibraryRoot(
    val id: String,
    val name: String,
    val kind: RootKind,
    val treeUri: String? = null,
    val sourceUrl: String? = null,
    val remoteId: String? = null,
    val cookie: String? = null,
    val proxyUrl: String? = null,
    val lastSyncedEpochMs: Long? = null,
    val excludedPaths: List<String> = emptyList(),
    val linkedCloudKind: RootKind? = null,
    val trackedSubfolderName: String? = null,
) {
    val isCloudTrackedLocal: Boolean
        get() = !kind.isCloud && !sourceUrl.isNullOrBlank()

    val effectiveCloudKind: RootKind?
        get() = if (kind.isCloud) kind else linkedCloudKind
    fun isExcluded(relativePath: String): Boolean {
        if (excludedPaths.isEmpty()) return false
        val normRel = normalizeFolderPath(relativePath)
        return excludedPaths.any { ex ->
            val normEx = normalizeFolderPath(ex)
            normEx.isNotEmpty() && (normRel == normEx || normRel.startsWith("$normEx/"))
        }
    }

    companion object {
        const val APP_FOLDER_ID = "00000000-0000-0000-0000-00000000A990"

        fun normalizeFolderPath(path: String): String =
            Normalizer.normalize(path.replace('\\', '/'), Normalizer.Form.NFC)
                .trim { it == '/' || it.isWhitespace() }

        fun folderContains(folderPath: String, relativePath: String): Boolean {
            val normFolder = normalizeFolderPath(folderPath)
            if (normFolder.isEmpty()) return false
            val normRel = normalizeFolderPath(relativePath)
            return normRel == normFolder || normRel.startsWith("$normFolder/")
        }
    }
}

@Serializable
enum class TrackSortOrder(val label: String) {
    CUSTOM("Custom"),
    NAME_ASC("Name (A–Z)"),
    NAME_DESC("Name (Z–A)"),
    DATE_MODIFIED("Date Modified"),
    MOST_PLAYED("Most Played"),
    LEAST_PLAYED("Least Played"),
}

enum class SearchField(val label: String) {
    KEYWORD("Keyword"),
    ARTIST("Artist"),
    ALBUM("Album"),
    GENRE("Genre"),
}

@Serializable
data class TrackMetadataOverride(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val artworkKey: String? = null,
)

@Serializable
data class Track(
    val id: String,
    val rootId: String,
    val relativePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String = "",
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationSec: Double,
    val fileSize: Long,
    val modifiedEpochMs: Long,
    val hasEmbeddedLyrics: Boolean = false,
    val dateAddedEpochMs: Long,
    val artworkKey: String? = null,
    val cloudSource: String? = null,
    val remoteTrackId: String? = null,
    val remoteArtworkUrl: String? = null,
) {
    val isCloud: Boolean
        get() = cloudSource != null
    /** Folder part of [relativePath] (empty string for files directly in the root). */
    val folderPath: String
        get() {
            val slash = relativePath.lastIndexOf('/')
            return if (slash < 0) "" else relativePath.substring(0, slash)
        }

    val fileName: String
        get() {
            val slash = relativePath.lastIndexOf('/')
            return if (slash < 0) relativePath else relativePath.substring(slash + 1)
        }

    companion object {
        fun makeId(rootId: String, relativePath: String): String {
            val input = "$rootId/$relativePath".toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(input)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val trackIds: List<String>,
    val artworkKey: String? = null,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val sortOrder: TrackSortOrder = TrackSortOrder.CUSTOM,
)

fun compareTracks(
    a: Track,
    b: Track,
    sortOrder: TrackSortOrder,
    playCounts: (String) -> Int = { 0 },
): Int {
    return when (sortOrder) {
        TrackSortOrder.CUSTOM -> 0
        TrackSortOrder.NAME_ASC -> {
            val byTitle = String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title)
            if (byTitle != 0) byTitle else String.CASE_INSENSITIVE_ORDER.compare(a.fileName, b.fileName)
        }
        TrackSortOrder.NAME_DESC -> {
            val byTitle = String.CASE_INSENSITIVE_ORDER.compare(b.title, a.title)
            if (byTitle != 0) byTitle else String.CASE_INSENSITIVE_ORDER.compare(b.fileName, a.fileName)
        }
        TrackSortOrder.DATE_MODIFIED -> {
            val byMod = b.modifiedEpochMs.compareTo(a.modifiedEpochMs)
            if (byMod != 0) byMod else String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title)
        }
        TrackSortOrder.MOST_PLAYED -> {
            val byPlays = playCounts(b.id).compareTo(playCounts(a.id))
            if (byPlays != 0) byPlays else String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title)
        }
        TrackSortOrder.LEAST_PLAYED -> {
            val byPlays = playCounts(a.id).compareTo(playCounts(b.id))
            if (byPlays != 0) byPlays else String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title)
        }
    }
}

fun sortTracks(
    tracks: List<Track>,
    sortOrder: TrackSortOrder,
    playCounts: (String) -> Int = { 0 },
): List<Track> {
    if (sortOrder == TrackSortOrder.CUSTOM) return tracks
    return tracks.sortedWith { a, b -> compareTracks(a, b, sortOrder, playCounts) }
}

@Serializable
enum class RepeatMode {
    OFF,
    ALL,
    ONE,
}

@Serializable
data class EqSettings(
    val enabled: Boolean = false,
    val preampDb: Float = 0f,
    val gains: List<Float> = List(10) { 0f },
    val presetName: String? = "Flat",
) {
    companion object {
        val FREQUENCIES = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val GAIN_RANGE: ClosedFloatingPointRange<Float> = -12f..12f
    }
}

data class EqPreset(
    val name: String,
    val gains: List<Float>,
) {
    companion object {
        val ALL: List<EqPreset> = listOf(
            EqPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
            EqPreset("Bass Boost", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
            EqPreset("Treble Boost", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
            EqPreset("Rock", listOf(5f, 4f, 3f, 1f, -1f, -1f, 1f, 3f, 4f, 5f)),
            EqPreset("Pop", listOf(-1f, 0f, 2f, 4f, 5f, 4f, 2f, 0f, -1f, -1f)),
            EqPreset("Jazz", listOf(4f, 3f, 1f, 2f, -2f, -2f, 0f, 1f, 3f, 4f)),
            EqPreset("Classical", listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
            EqPreset("Vocal", listOf(-2f, -2f, -1f, 1f, 4f, 4f, 3f, 1f, 0f, -1f)),
        )
    }
}

data class LyricLine(
    val id: Int,
    val timeSec: Double?,
    val text: String,
)

data class Lyrics(
    val lines: List<LyricLine>,
    val isTimed: Boolean,
) {
    /** Index of the line being sung at [timeSec], or null before the first line / for untimed lyrics. */
    fun lineIndexAt(timeSec: Double): Int? {
        if (!isTimed) return null
        var result: Int? = null
        for (index in lines.indices) {
            val start = lines[index].timeSec ?: break
            if (start > timeSec) break
            result = index
        }
        return result
    }
}

enum class LyricsSource {
    IMPORTED,
    SIDECAR,
    EMBEDDED,
}

@Serializable
data class PlayHistoryEntry(
    val count: Int,
    val lastPlayedEpochMs: Long,
)

sealed interface SleepMode {
    data object Off : SleepMode
    data class Until(val targetEpochMs: Long) : SleepMode
    data object EndOfTrack : SleepMode
}
