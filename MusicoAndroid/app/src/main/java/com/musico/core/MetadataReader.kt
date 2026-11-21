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
import java.nio.charset.Charset

data class TrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
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
                retriever.setDataSource(context, uri)
            }
            title = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            artist = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            album = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            albumArtist = clean(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
            trackNumber = parseLeadingInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
            discNumber = parseLeadingInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (durationMs != null && durationMs > 0) {
                durationSec = durationMs / 1000.0
            }
            artwork = retriever.embeddedPicture
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
                trackNumber = trackNumber ?: parseLeadingInt(flac.comments["TRACKNUMBER"])
                discNumber = discNumber ?: parseLeadingInt(flac.comments["DISCNUMBER"])
                lyrics = lyrics ?: comment("LYRICS", "UNSYNCEDLYRICS")
                artwork = artwork ?: flac.picture
            }
        }

        if (lyrics == null) {
            lyrics = openStream(uri)?.use { extractId3Uslt(BufferedInputStream(it)) }
        }

        TrackMetadata(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
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
        private const val MAX_ID3_TAG_BYTES = 4 * 1024 * 1024

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
            val header = readBytes(input, 10) ?: return null
            if (header[0] != 0x49.toByte() || header[1] != 0x44.toByte() || header[2] != 0x33.toByte()) {
                return null
            }
            val majorVersion = header[3].toInt() and 0xFF
            if (majorVersion < 3 || majorVersion > 4) return null
            val flags = header[5].toInt() and 0xFF
            val tagSize = synchsafeInt(header, 6)
            if (tagSize <= 0 || tagSize > MAX_ID3_TAG_BYTES) return null

            val tagBytes = readBytes(input, tagSize) ?: return null
            var pos = 0

            // Skip extended header if present (bit 6)
            if ((flags and 0x40) != 0 && pos + 4 <= tagBytes.size) {
                val extSize = if (majorVersion == 4) synchsafeInt(tagBytes, pos) else bigEndianInt(tagBytes, pos)
                if (extSize > 0) {
                    pos += if (majorVersion == 4) extSize else extSize + 4
                }
            }

            while (pos + 10 <= tagBytes.size) {
                if (tagBytes[pos] == 0.toByte()) break // padding
                val frameId = String(tagBytes, pos, 4, Charsets.ISO_8859_1)
                val frameSize = if (majorVersion == 4) {
                    synchsafeInt(tagBytes, pos + 4)
                } else {
                    bigEndianInt(tagBytes, pos + 4)
                }
                pos += 10
                if (frameSize <= 0 || pos + frameSize > tagBytes.size) break

                if (frameId == "USLT") {
                    val decoded = decodeUsltFrame(tagBytes, pos, frameSize)
                    if (decoded != null) return decoded
                }
                pos += frameSize
            }
            return null
        }

        private fun decodeUsltFrame(data: ByteArray, offset: Int, length: Int): String? {
            if (length < 5) return null
            val encodingByte = data[offset].toInt() and 0xFF
            // bytes offset+1 .. offset+3 are ISO-639-2 language
            var cursor = offset + 4
            val end = offset + length

            val charset: Charset
            val twoByteNull: Boolean
            when (encodingByte) {
                0 -> {
                    charset = Charsets.ISO_8859_1
                    twoByteNull = false
                }
                1 -> {
                    charset = Charsets.UTF_16
                    twoByteNull = true
                }
                2 -> {
                    charset = Charsets.UTF_16BE
                    twoByteNull = true
                }
                3 -> {
                    charset = Charsets.UTF_8
                    twoByteNull = false
                }
                else -> return null
            }

            // Skip content descriptor (null-terminated)
            if (twoByteNull) {
                while (cursor + 1 < end) {
                    if (data[cursor] == 0.toByte() && data[cursor + 1] == 0.toByte()) {
                        cursor += 2
                        break
                    }
                    cursor += 2
                }
            } else {
                while (cursor < end) {
                    if (data[cursor] == 0.toByte()) {
                        cursor += 1
                        break
                    }
                    cursor += 1
                }
            }

            if (cursor >= end) return null
            val text = String(data, cursor, end - cursor, charset)
                .trimEnd('\u0000')
            return clean(text)
        }

        private fun synchsafeInt(b: ByteArray, offset: Int): Int {
            return ((b[offset].toInt() and 0x7F) shl 21) or
                ((b[offset + 1].toInt() and 0x7F) shl 14) or
                ((b[offset + 2].toInt() and 0x7F) shl 7) or
                (b[offset + 3].toInt() and 0x7F)
        }

        private fun bigEndianInt(b: ByteArray, offset: Int): Int {
            return ((b[offset].toInt() and 0xFF) shl 24) or
                ((b[offset + 1].toInt() and 0xFF) shl 16) or
                ((b[offset + 2].toInt() and 0xFF) shl 8) or
                (b[offset + 3].toInt() and 0xFF)
        }

        private fun readBytes(input: InputStream, count: Int): ByteArray? {
            val buf = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(buf, offset, count - offset)
                if (read < 0) return null
                offset += read
            }
            return buf
        }
    }
}
