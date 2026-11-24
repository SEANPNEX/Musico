package com.musico.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

data class TrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationSec: Double = 0.0,
    val artwork: ByteArray? = null,
    val lyrics: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrackMetadata) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            albumArtist == other.albumArtist &&
            genre == other.genre &&
            trackNumber == other.trackNumber &&
            discNumber == other.discNumber &&
            durationSec == other.durationSec &&
            (artwork contentEqualsSafe other.artwork) &&
            lyrics == other.lyrics
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (albumArtist?.hashCode() ?: 0)
        result = 31 * result + (genre?.hashCode() ?: 0)
        result = 31 * result + (trackNumber ?: 0)
        result = 31 * result + (discNumber ?: 0)
        result = 31 * result + durationSec.hashCode()
        result = 31 * result + (artwork?.contentHashCode() ?: 0)
        result = 31 * result + (lyrics?.hashCode() ?: 0)
        return result
    }
}

private infix fun ByteArray?.contentEqualsSafe(other: ByteArray?): Boolean {
    if (this === other) return true
    if (this == null || other == null) return false
    return this.contentEquals(other)
}

interface MetadataReading {
    suspend fun read(uri: Uri, fileNameHint: String? = null): TrackMetadata
    suspend fun read(file: File): TrackMetadata = read(Uri.fromFile(file), file.name)
}

class AndroidMetadataReader(
    private val context: Context,
) : MetadataReading {

    override suspend fun read(uri: Uri, fileNameHint: String?): TrackMetadata = withContext(Dispatchers.IO) {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var trackNumber: Int? = null
        var discNumber: Int? = null
        var durationSec = 0.0
        var artwork: ByteArray? = null
        var mimeType: String? = null

        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file" && uri.path != null) {
                retriever.setDataSource(uri.path)
            } else {
                try {
                    retriever.setDataSource(context, uri)
                } catch (_: Exception) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                    }
                }
            }
            title = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            artist = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            album = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            albumArtist = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
            genre = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE))
            trackNumber = parseLeadingInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
            discNumber = parseLeadingInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (durationMs != null && durationMs > 0) {
                durationSec = durationMs / 1000.0
            }
            artwork = retriever.embeddedPicture?.let { EmbeddedArtworkExtractor.normalizeImageBytes(it) }
        } catch (_: Exception) {
            // Best-effort metadata reading; fall through to container-specific readers.
        } finally {
            runCatching { retriever.release() }
        }

        var lyrics: String? = null
        val lowerHint = (fileNameHint ?: uri.lastPathSegment ?: "").lowercase()
        val isFlac = lowerHint.endsWith(".flac") || (mimeType?.lowercase()?.contains("flac") == true)

        if (isFlac) {
            val flac = openStream(uri)?.use { FlacTags.read(BufferedInputStream(it)) }
            if (flac != null) {
                fun comment(vararg keys: String): String? =
                    keys.firstNotNullOfOrNull { clean(flac.comments[it]) }

                title = title ?: comment("TITLE")
                artist = artist ?: comment("ARTIST")
                album = album ?: comment("ALBUM")
                albumArtist = albumArtist ?: comment("ALBUMARTIST", "ALBUM ARTIST")
                genre = genre ?: comment("GENRE")
                trackNumber = trackNumber ?: parseLeadingInt(flac.comments["TRACKNUMBER"])
                discNumber = discNumber ?: parseLeadingInt(flac.comments["DISCNUMBER"])
                lyrics = lyrics ?: comment("LYRICS", "UNSYNCEDLYRICS")
                artwork = artwork ?: flac.picture?.let { EmbeddedArtworkExtractor.normalizeImageBytes(it) }
            }
        }

        if (artwork == null || lyrics == null) {
            val extracted = EmbeddedArtworkExtractor.extract(
                context = context,
                uri = uri,
                needArtwork = artwork == null,
                needLyrics = lyrics == null,
            )
            artwork = artwork ?: extracted.artwork
            lyrics = lyrics ?: extracted.lyrics
        }

        TrackMetadata(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            trackNumber = trackNumber,
            discNumber = discNumber,
            durationSec = durationSec,
            artwork = artwork,
            lyrics = lyrics,
        )
    }

    private fun openStream(uri: Uri): InputStream? {
        return runCatching {
            if (uri.scheme == "file" && uri.path != null) {
                FileInputStream(uri.path)
            } else {
                context.contentResolver.openInputStream(uri)
            }
        }.getOrNull()
    }

    companion object {
        internal fun clean(value: String?): String? {
            val trimmed = value?.trim() ?: return null
            return trimmed.ifEmpty { null }
        }

        internal fun parseLeadingInt(value: String?): Int? {
            val trimmed = value?.trim() ?: return null
            val digits = trimmed.takeWhile { it.isDigit() }
            val parsed = digits.toIntOrNull() ?: return null
            return parsed.takeIf { it > 0 }
        }

        /**
         * Extracts the first USLT (unsynchronized lyrics) frame from an ID3v2.3 or ID3v2.4 header.
         */
        internal fun extractId3Uslt(input: InputStream): String? {
            return EmbeddedArtworkExtractor.extractId3v2(
                input = input,
                needArtwork = false,
                needLyrics = true,
            ).lyrics
        }
    }
}
