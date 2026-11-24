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
    CLOUD_GOOGLE_DRIVE,
    CLOUD_EXTENSION,
    ;

    val isCloud: Boolean
        get() = this == CLOUD_YOUTUBE ||
            this == CLOUD_BILIBILI ||
            this == CLOUD_GOOGLE_DRIVE ||
            this == CLOUD_EXTENSION

    val builtinProviderId: String?
        get() = when (this) {
            CLOUD_YOUTUBE -> "builtin.youtube"
            CLOUD_BILIBILI -> "builtin.bilibili"
            CLOUD_GOOGLE_DRIVE -> "builtin.gdrive"
            PICKED, APP_FOLDER, CLOUD_EXTENSION -> null
        }
}

@Serializable
data class CloudTrackingInfo(
    val sourceUrl: String,
    val remoteId: String? = null,
    val cloudKind: RootKind,
    val providerId: String? = null,
    val cookie: String? = null,
    val lastSyncedEpochMs: Long? = null,
) {
    val resolvedProviderId: String?
        get() = providerId?.trim()?.takeIf { it.isNotEmpty() } ?: cloudKind.builtinProviderId
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
    val providerId: String? = null,
    val linkedProviderId: String? = null,
    val trackedSubfolderName: String? = null,
    val trackedSubfolders: Map<String, CloudTrackingInfo> = emptyMap(),
) {
    val isCloud: Boolean
        get() = kind.isCloud

    val resolvedProviderId: String?
        get() = if (kind.isCloud) {
            providerId?.trim()?.takeIf { it.isNotEmpty() } ?: kind.builtinProviderId
        } else {
            null
        }

    val resolvedLinkedProviderId: String?
        get() = if (!kind.isCloud) {
            linkedProviderId?.trim()?.takeIf { it.isNotEmpty() } ?: linkedCloudKind?.builtinProviderId
        } else {
            null
        }

    val effectiveProviderId: String?
        get() = if (kind.isCloud) resolvedProviderId else resolvedLinkedProviderId

    val isCloudLinked: Boolean
        get() = !kind.isCloud &&
            (resolvedLinkedProviderId != null || (!sourceUrl.isNullOrBlank() && trackedSubfolderName.isNullOrBlank()))

    val hasCloudMetadata: Boolean
        get() = isCloud || isCloudLinked || trackedSubfolders.isNotEmpty()

    val isCloudTrackedLocal: Boolean
        get() = !kind.isCloud && !sourceUrl.isNullOrBlank() && trackedSubfolderName.isNullOrBlank()

    val effectiveCloudKind: RootKind?
        get() = if (kind.isCloud) kind else linkedCloudKind

    fun trackingFor(path: String): CloudTrackingInfo? {
        val norm = normalizeFolderPath(path)
        if (norm.isEmpty()) {
            val url = sourceUrl?.takeIf { it.isNotBlank() } ?: return null
            if (!kind.isCloud && !trackedSubfolderName.isNullOrBlank()) return null
            val cloudKind = effectiveCloudKind ?: return null
            return CloudTrackingInfo(
                sourceUrl = url,
                remoteId = remoteId,
                cloudKind = cloudKind,
                providerId = effectiveProviderId,
                cookie = cookie,
                lastSyncedEpochMs = lastSyncedEpochMs,
            )
        }
        trackedSubfolders[norm]?.let { return it }
        val legacySub = trackedSubfolderName?.let { normalizeFolderPath(it) }
        if (legacySub == norm) {
            val url = sourceUrl?.takeIf { it.isNotBlank() } ?: return null
            val cloudKind = effectiveCloudKind ?: return null
            return CloudTrackingInfo(
                sourceUrl = url,
                remoteId = remoteId,
                cloudKind = cloudKind,
                providerId = effectiveProviderId,
                cookie = cookie,
                lastSyncedEpochMs = lastSyncedEpochMs,
            )
        }
        return null
    }
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
    val isEphemeral: Boolean = false,
) {
    val isCloud: Boolean
        get() = cloudSource != null || isEphemeral
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
        const val EPHEMERAL_ID_PREFIX = "extsearch:"

        fun makeId(rootId: String, relativePath: String): String {
            val input = "$rootId/$relativePath".toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(input)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun makeEphemeralId(extensionId: String, remoteTrackId: String): String =
            "$EPHEMERAL_ID_PREFIX$extensionId:$remoteTrackId"

        fun isEphemeralId(trackId: String?): Boolean =
            trackId != null && trackId.startsWith(EPHEMERAL_ID_PREFIX)

        fun extensionIdFromEphemeralId(trackId: String?): String? {
            if (trackId == null || !trackId.startsWith(EPHEMERAL_ID_PREFIX)) return null
            val rest = trackId.removePrefix(EPHEMERAL_ID_PREFIX)
            val sep = rest.indexOf(':')
            if (sep <= 0) return null
            return rest.substring(0, sep).takeIf { it.isNotEmpty() }
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
    FOLDER_MATCH,
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
