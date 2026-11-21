package com.musico.model

import kotlinx.serialization.Serializable
import java.security.MessageDigest

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
) {
    companion object {
        const val APP_FOLDER_ID = "00000000-0000-0000-0000-00000000A990"
    }
}

@Serializable
data class Track(
    val id: String,
    val rootId: String,
    val relativePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
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
)

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
