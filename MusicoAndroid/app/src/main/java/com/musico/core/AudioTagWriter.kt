package com.musico.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioTagPayload(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val genre: String = "",
    val trackNumber: Int? = null,
    val artworkBytes: ByteArray? = null,
    val artworkMimeType: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioTagPayload) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            albumArtist == other.albumArtist &&
            genre == other.genre &&
            trackNumber == other.trackNumber &&
            artworkMimeType == other.artworkMimeType &&
            ((artworkBytes == null && other.artworkBytes == null) ||
                (artworkBytes != null && other.artworkBytes != null && artworkBytes.contentEquals(other.artworkBytes)))
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + albumArtist.hashCode()
        result = 31 * result + genre.hashCode()
        result = 31 * result + (trackNumber ?: 0)
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        result = 31 * result + (artworkMimeType?.hashCode() ?: 0)
        return result
    }
}

data class EmbeddedTagSummary(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val trackNumber: Int? = null,
    val hasArtwork: Boolean = false,
) {
    val hasAnyTagOrArtwork: Boolean
        get() = !title.isNullOrBlank() ||
            !artist.isNullOrBlank() ||
            !album.isNullOrBlank() ||
            !albumArtist.isNullOrBlank() ||
            !genre.isNullOrBlank() ||
            (trackNumber != null && trackNumber > 0) ||
            hasArtwork
}

data class DetectedImageInfo(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
    val mp4DataType: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetectedImageInfo) return false
        return mimeType == other.mimeType &&
            extension == other.extension &&
            mp4DataType == other.mp4DataType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + extension.hashCode()
        result = 31 * result + mp4DataType
        return result
    }
}

/**
 * Pure-Kotlin best-effort metadata & cover art writer for FLAC, M4A/MP4, and MP3 files,
 * plus magic-byte image detection for embedded tags and sibling cover sidecars.
 */
object AudioTagWriter {
    private val EMBEDDABLE_EXTENSIONS = setOf("flac", "m4a", "mp4", "mp3")
    private val SIDE_CAR_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

    fun isEmbeddableExtension(extension: String): Boolean =
        extension.trim().trimStart('.').lowercase() in EMBEDDABLE_EXTENSIONS

    fun sidecarImageExtensions(): List<String> = SIDE_CAR_EXTENSIONS

    fun detectImage(rawBytes: ByteArray?): DetectedImageInfo? {
        if (rawBytes == null || rawBytes.isEmpty()) return null
        val bytes = EmbeddedArtworkExtractor.normalizeImageBytes(rawBytes) ?: return null
        fun u(i: Int): Int = bytes[i].toInt() and 0xFF
        return when {
            bytes.size >= 8 &&
                u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47 &&
                u(4) == 0x0D && u(5) == 0x0A && u(6) == 0x1A && u(7) == 0x0A -> {
                DetectedImageInfo(bytes = bytes, mimeType = "image/png", extension = "png", mp4DataType = 14)
            }
            bytes.size >= 3 && u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> {
                DetectedImageInfo(bytes = bytes, mimeType = "image/jpeg", extension = "jpg", mp4DataType = 13)
            }
            bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> {
                DetectedImageInfo(bytes = bytes, mimeType = "image/webp", extension = "webp", mp4DataType = 0)
            }
            bytes.size >= 6 &&
                (String(bytes, 0, 6, Charsets.US_ASCII) == "GIF87a" ||
                    String(bytes, 0, 6, Charsets.US_ASCII) == "GIF89a") -> {
                DetectedImageInfo(bytes = bytes, mimeType = "image/gif", extension = "gif", mp4DataType = 12)
            }
            else -> null
        }
    }

    fun readEmbeddedSummary(
        file: File,
        formatExtension: String = file.extension,
    ): EmbeddedTagSummary {
        if (!file.exists() || !file.isFile) return EmbeddedTagSummary()
        val ext = formatExtension.trim().trimStart('.').lowercase()
        val extractedArtwork = runCatching {
            EmbeddedArtworkExtractor.extract(file, needArtwork = true, needLyrics = false).artwork != null
        }.getOrDefault(false)
        return when (ext) {
            "flac" -> {
                val flac = runCatching { FlacTags.read(file) }.getOrNull()
                fun commentValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
                    flac?.comments?.entries?.firstOrNull { it.key.equals(k, ignoreCase = true) }?.value
                        ?.trim()?.takeIf { it.isNotEmpty() }
                }
                EmbeddedTagSummary(
                    title = commentValue("TITLE"),
                    artist = commentValue("ARTIST"),
                    album = commentValue("ALBUM"),
                    albumArtist = commentValue("ALBUMARTIST", "ALBUM ARTIST"),
                    genre = commentValue("GENRE"),
                    trackNumber = commentValue("TRACKNUMBER")?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 },
                    hasArtwork = extractedArtwork || flac?.picture != null,
                )
            }
            "mp3" -> readMp3Summary(file, extractedArtwork)
            "m4a", "mp4" -> readMp4Summary(file, extractedArtwork)
            else -> EmbeddedTagSummary(hasArtwork = extractedArtwork)
        }
    }

    fun write(
        file: File,
        payload: AudioTagPayload,
        formatExtension: String = file.extension,
    ): Boolean {
        if (!file.exists() || !file.isFile || !file.canWrite()) return false
        val ext = formatExtension.trim().trimStart('.').lowercase()
        return when (ext) {
            "flac" -> writeFlac(file, payload)
            "m4a", "mp4" -> writeMp4(file, payload)
            "mp3" -> writeMp3(file, payload)
            else -> false
        }
    }

    // region FLAC

    private data class FlacBlock(
        val type: Int,
        val body: ByteArray,
    )

    private fun writeFlac(file: File, payload: AudioTagPayload): Boolean {
        val data = runCatching { file.readBytes() }.getOrNull() ?: return false
        if (data.size < 8) return false

        var offset = 0
        if (data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
            if (data.size < 10) return false
            val flags = data[5].toInt() and 0xFF
            val hasFooter = (flags and 0x10) != 0
            val size = synchsafeInt(data, 6)
            offset = 10 + size + if (hasFooter) 10 else 0
        }
        if (offset + 4 > data.size) return false
        if (data[offset] != 'f'.code.toByte() ||
            data[offset + 1] != 'L'.code.toByte() ||
            data[offset + 2] != 'a'.code.toByte() ||
            data[offset + 3] != 'C'.code.toByte()
        ) {
            return false
        }
        val flacStart = offset
        offset += 4

        val blocks = mutableListOf<FlacBlock>()
        var vendor = "Musico"
        var existingComments = emptyList<Pair<String, String>>()
        var hasLast = false
        val artwork = detectImage(payload.artworkBytes)

        while (offset + 4 <= data.size) {
            val h0 = data[offset].toInt() and 0xFF
            val isLast = (h0 and 0x80) != 0
            val type = h0 and 0x7F
            val length = ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            offset += 4
            if (offset + length > data.size) return false
            val body = data.copyOfRange(offset, offset + length)
            offset += length

            when {
                type == 4 -> {
                    val parsed = parseVorbisComments(body)
                    if (!parsed.first.isNullOrBlank()) vendor = parsed.first!!
                    existingComments = parsed.second
                }
                type == 6 && artwork != null -> {
                    // Replaced below with updated front cover.
                }
                type != 1 -> {
                    blocks.add(FlacBlock(type, body))
                }
            }

            if (isLast) {
                hasLast = true
                break
            }
        }
        if (!hasLast) return false

        val managedKeys = mutableSetOf<String>()
        if (payload.title.isNotBlank()) managedKeys.add("TITLE")
        if (payload.artist.isNotBlank()) managedKeys.add("ARTIST")
        if (payload.album.isNotBlank()) managedKeys.add("ALBUM")
        if (payload.albumArtist.isNotBlank()) {
            managedKeys.add("ALBUMARTIST")
            managedKeys.add("ALBUM ARTIST")
        }
        if (payload.genre.isNotBlank()) managedKeys.add("GENRE")
        if (payload.trackNumber != null && payload.trackNumber > 0) managedKeys.add("TRACKNUMBER")

        val mergedComments = existingComments.filterTo(mutableListOf()) { (k, _) ->
            k.uppercase() !in managedKeys
        }
        if (payload.title.isNotBlank()) mergedComments.add("TITLE" to payload.title)
        if (payload.artist.isNotBlank()) mergedComments.add("ARTIST" to payload.artist)
        if (payload.album.isNotBlank()) mergedComments.add("ALBUM" to payload.album)
        if (payload.albumArtist.isNotBlank()) mergedComments.add("ALBUMARTIST" to payload.albumArtist)
        if (payload.genre.isNotBlank()) mergedComments.add("GENRE" to payload.genre)
        if (payload.trackNumber != null && payload.trackNumber > 0) {
            mergedComments.add("TRACKNUMBER" to payload.trackNumber.toString())
        }

        blocks.add(FlacBlock(4, encodeVorbisComments(vendor, mergedComments)))
        if (artwork != null) {
            val mime = payload.artworkMimeType?.takeIf { it.isNotBlank() } ?: artwork.mimeType
            blocks.add(FlacBlock(6, encodeFlacPicture(artwork.bytes, mime)))
        }

        val out = ByteArrayOutputStream(data.size + (artwork?.bytes?.size ?: 0) + 1024)
        if (flacStart > 0) {
            out.write(data, 0, flacStart)
        }
        out.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))
        for ((idx, block) in blocks.withIndex()) {
            val len = block.body.size
            if (len > 0xFFFFFF) return false
            val headerByte = (if (idx == blocks.lastIndex) 0x80 else 0x00) or (block.type and 0x7F)
            out.write(headerByte)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(block.body)
        }
        out.write(data, offset, data.size - offset)

        return atomicReplace(file, out.toByteArray()) { tempFile ->
            FlacTags.read(tempFile) != null
        }
    }

    private fun parseVorbisComments(body: ByteArray): Pair<String?, List<Pair<String, String>>> {
        var cursor = 0
        fun readIntLE(): Int? {
            if (cursor + 4 > body.size) return null
            val v = (body[cursor].toInt() and 0xFF) or
                ((body[cursor + 1].toInt() and 0xFF) shl 8) or
                ((body[cursor + 2].toInt() and 0xFF) shl 16) or
                ((body[cursor + 3].toInt() and 0xFF) shl 24)
            cursor += 4
            return v
        }

        val vendorLen = readIntLE() ?: return null to emptyList()
        if (vendorLen < 0 || cursor + vendorLen > body.size) return null to emptyList()
        val vendor = String(body, cursor, vendorLen, Charsets.UTF_8)
        cursor += vendorLen

        val count = readIntLE() ?: return vendor to emptyList()
        if (count < 0) return vendor to emptyList()
        val result = ArrayList<Pair<String, String>>(count.coerceAtMost(256))
        for (i in 0 until count) {
            val len = readIntLE() ?: break
            if (len < 0 || cursor + len > body.size) break
            val entry = String(body, cursor, len, Charsets.UTF_8)
            cursor += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                result.add(entry.substring(0, eq) to entry.substring(eq + 1))
            }
        }
        return vendor to result
    }

    private fun encodeVorbisComments(vendor: String, comments: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        writeIntLE(vendorBytes.size)
        out.write(vendorBytes)
        writeIntLE(comments.size)
        for ((k, v) in comments) {
            val entry = "$k=$v".toByteArray(Charsets.UTF_8)
            writeIntLE(entry.size)
            out.write(entry)
        }
        return out.toByteArray()
    }

    private fun encodeFlacPicture(imageBytes: ByteArray, mimeType: String): ByteArray {
        val mimeBytes = mimeType.toByteArray(Charsets.ISO_8859_1)
        val out = ByteArrayOutputStream(32 + mimeBytes.size + imageBytes.size)
        fun writeIntBE(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        writeIntBE(3) // Picture type: Front Cover
        writeIntBE(mimeBytes.size)
        out.write(mimeBytes)
        writeIntBE(0) // Description length
        writeIntBE(0) // Width
        writeIntBE(0) // Height
        writeIntBE(0) // Color depth
        writeIntBE(0) // Indexed colors
        writeIntBE(imageBytes.size)
        out.write(imageBytes)
        return out.toByteArray()
    }

    // endregion

    // region MP3 (ID3v2.3)

    private fun writeMp3(file: File, payload: AudioTagPayload): Boolean {
        val data = runCatching { file.readBytes() }.getOrNull() ?: return false
        if (data.size < 4) return false

        val artwork = detectImage(payload.artworkBytes)
        var audioOffset = 0
        val preserved = ByteArrayOutputStream()
        var preservedV22Artwork = false

        if (data.size >= 10 &&
            data[0] == 'I'.code.toByte() &&
            data[1] == 'D'.code.toByte() &&
            data[2] == '3'.code.toByte()
        ) {
            val major = data[3].toInt() and 0xFF
            val flags = data[5].toInt() and 0xFF
            val tagSize = synchsafeInt(data, 6)
            val footerSize = if (major == 4 && (flags and 0x10) != 0) 10 else 0
            audioOffset = (10 + tagSize + footerSize).coerceAtMost(data.size)
            val tagBodyEnd = (10 + tagSize).coerceAtMost(data.size)
            if (tagBodyEnd > 10 && (major == 3 || major == 4) && (flags and 0x80) == 0) {
                var bodyStart = 10
                if ((flags and 0x40) != 0 && tagBodyEnd - bodyStart >= 4) {
                    val extHeaderSize = if (major == 4) {
                        synchsafeInt(data, bodyStart).coerceAtLeast(4)
                    } else {
                        4 + bigEndianInt(data, bodyStart).coerceAtLeast(0)
                    }
                    bodyStart = (bodyStart + extHeaderSize).coerceAtMost(tagBodyEnd)
                }
                if (bodyStart < tagBodyEnd) {
                    val tagBody = data.copyOfRange(bodyStart, tagBodyEnd)
                    extractPreservedId3v2Frames(
                        major = major,
                        tagBody = tagBody,
                        droppingTitle = payload.title.isNotBlank(),
                        droppingArtist = payload.artist.isNotBlank(),
                        droppingAlbum = payload.album.isNotBlank(),
                        droppingAlbumArtist = payload.albumArtist.isNotBlank(),
                        droppingTrackNumber = payload.trackNumber != null && payload.trackNumber > 0,
                        droppingGenre = payload.genre.isNotBlank(),
                        droppingArtwork = artwork != null,
                        out = preserved,
                    )
                }
            } else if (major == 2 && tagBodyEnd > 10) {
                val existingSummary = readMp3Summary(file, hasArtworkHint = false)
                if (payload.title.isBlank() && !existingSummary.title.isNullOrBlank()) {
                    preserved.write(makeId3TextFrame("TIT2", existingSummary.title))
                }
                if (payload.artist.isBlank() && !existingSummary.artist.isNullOrBlank()) {
                    preserved.write(makeId3TextFrame("TPE1", existingSummary.artist))
                }
                if (payload.album.isBlank() && !existingSummary.album.isNullOrBlank()) {
                    preserved.write(makeId3TextFrame("TALB", existingSummary.album))
                }
                if (payload.albumArtist.isBlank() && !existingSummary.albumArtist.isNullOrBlank()) {
                    preserved.write(makeId3TextFrame("TPE2", existingSummary.albumArtist))
                }
                if (payload.genre.isBlank() && !existingSummary.genre.isNullOrBlank()) {
                    preserved.write(makeId3TextFrame("TCON", existingSummary.genre))
                }
                if ((payload.trackNumber == null || payload.trackNumber <= 0) && existingSummary.trackNumber != null) {
                    preserved.write(makeId3TextFrame("TRCK", existingSummary.trackNumber.toString()))
                }
                if (artwork == null) {
                    val existingArt = EmbeddedArtworkExtractor.extract(file, needArtwork = true, needLyrics = false).artwork
                    val detectedExisting = detectImage(existingArt)
                    if (detectedExisting != null) {
                        preserved.write(makeId3ApicFrame(detectedExisting.bytes, detectedExisting.mimeType))
                        preservedV22Artwork = true
                    }
                }
            }
        }

        val frames = ByteArrayOutputStream()
        frames.write(preserved.toByteArray())
        if (payload.title.isNotBlank()) frames.write(makeId3TextFrame("TIT2", payload.title))
        if (payload.artist.isNotBlank()) frames.write(makeId3TextFrame("TPE1", payload.artist))
        if (payload.album.isNotBlank()) frames.write(makeId3TextFrame("TALB", payload.album))
        if (payload.albumArtist.isNotBlank()) frames.write(makeId3TextFrame("TPE2", payload.albumArtist))
        if (payload.genre.isNotBlank()) frames.write(makeId3TextFrame("TCON", payload.genre))
        if (payload.trackNumber != null && payload.trackNumber > 0) {
            frames.write(makeId3TextFrame("TRCK", payload.trackNumber.toString()))
        }
        if (artwork != null) {
            val mime = payload.artworkMimeType?.takeIf { it.isNotBlank() } ?: artwork.mimeType
            frames.write(makeId3ApicFrame(artwork.bytes, mime))
        }

        val frameBytes = frames.toByteArray()
        val out = ByteArrayOutputStream(10 + frameBytes.size + (data.size - audioOffset))
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0x03, 0x00, 0x00))
        out.write(encodeSynchsafe(frameBytes.size))
        out.write(frameBytes)
        out.write(data, audioOffset, data.size - audioOffset)

        val shouldValidateArtwork = artwork != null || preservedV22Artwork
        return atomicReplace(file, out.toByteArray()) { tempFile ->
            if (!shouldValidateArtwork) true else EmbeddedArtworkExtractor.extract(tempFile, needArtwork = true, needLyrics = false).artwork != null
        }
    }

    private fun extractPreservedId3v2Frames(
        major: Int,
        tagBody: ByteArray,
        droppingTitle: Boolean,
        droppingArtist: Boolean,
        droppingAlbum: Boolean,
        droppingAlbumArtist: Boolean,
        droppingTrackNumber: Boolean,
        droppingGenre: Boolean,
        droppingArtwork: Boolean,
        out: ByteArrayOutputStream,
    ) {
        val excluded = hashSetOf<String>()
        if (droppingTitle) excluded.add("TIT2")
        if (droppingArtist) excluded.add("TPE1")
        if (droppingAlbum) excluded.add("TALB")
        if (droppingAlbumArtist) excluded.add("TPE2")
        if (droppingGenre) excluded.add("TCON")
        if (droppingTrackNumber) excluded.add("TRCK")
        if (droppingArtwork) excluded.add("APIC")

        var cursor = 0
        while (cursor + 10 <= tagBody.size) {
            if (tagBody[cursor] == 0.toByte()) break
            val id = String(tagBody, cursor, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val rawSize = resolveId3v2FrameSize(major, tagBody, cursor, excluded) ?: break
            val frameFlags = tagBody[cursor + 9].toInt() and 0xFF
            if (id !in excluded) {
                val framePayloadStart = cursor + 10
                val framePayloadEnd = cursor + 10 + rawSize
                var normalizedPayload = tagBody.copyOfRange(framePayloadStart, framePayloadEnd)
                var skipFrame = false
                if (major == 4) {
                    if ((frameFlags and 0x0C) != 0) {
                        // Compressed/encrypted ID3v2.4 frames cannot be safely rewritten without v2.4 header flags
                        skipFrame = true
                    } else {
                        var offset = 0
                        if ((frameFlags and 0x01) != 0 && normalizedPayload.size >= 4) {
                            offset = 4 // data-length indicator
                        }
                        if (offset > 0) {
                            normalizedPayload = normalizedPayload.copyOfRange(offset, normalizedPayload.size)
                        }
                        if ((frameFlags and 0x02) != 0) {
                            normalizedPayload = deUnsync(normalizedPayload)
                        }
                    }
                }
                if (!skipFrame) {
                    if (id.startsWith("T") && id != "TXXX" && normalizedPayload.isNotEmpty() && normalizedPayload[0] == 0x03.toByte()) {
                        val decoded = decodeId3TextPayload(normalizedPayload)
                        if (decoded != null) {
                            out.write(makeId3TextFrame(id, decoded))
                        }
                    } else {
                        out.write(id.toByteArray(Charsets.ISO_8859_1), 0, 4)
                        out.write(bigEndianIntBytes(normalizedPayload.size))
                        out.write(byteArrayOf(0x00, 0x00))
                        out.write(normalizedPayload)
                    }
                }
            }
            cursor += 10 + rawSize
        }
    }

    private fun resolveId3v2FrameSize(
        major: Int,
        tagBody: ByteArray,
        cursor: Int,
        knownIds: Set<String>,
    ): Int? {
        val rawBe = bigEndianInt(tagBody, cursor + 4)
        if (major != 4) {
            return if (rawBe > 0 && cursor + 10 + rawBe <= tagBody.size) rawBe else null
        }
        val synch = synchsafeInt(tagBody, cursor + 4)
        fun looksValidAt(size: Int): Boolean {
            if (size <= 0 || cursor + 10 + size > tagBody.size) return false
            val next = cursor + 10 + size
            if (next == tagBody.size) return true
            if (tagBody[next] == 0.toByte()) return true
            if (next + 4 <= tagBody.size) {
                val nextId = String(tagBody, next, 4, Charsets.ISO_8859_1)
                return nextId in knownIds || nextId.all { it in 'A'..'Z' || it in '0'..'9' }
            }
            return false
        }
        return when {
            looksValidAt(synch) -> synch
            looksValidAt(rawBe) -> rawBe
            synch > 0 && cursor + 10 + synch <= tagBody.size -> synch
            rawBe > 0 && cursor + 10 + rawBe <= tagBody.size -> rawBe
            else -> null
        }
    }

    private fun deUnsync(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            out.write(b.toInt() and 0xFF)
            if (b == 0xFF.toByte() && i + 1 < bytes.size && bytes[i + 1] == 0x00.toByte()) {
                i += 2
            } else {
                i++
            }
        }
        return out.toByteArray()
    }

    private fun decodeId3TextPayload(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val encoding = payload[0].toInt() and 0xFF
        val textBytes = payload.copyOfRange(1, payload.size)
        val raw = when (encoding) {
            0 -> String(textBytes, Charsets.ISO_8859_1)
            1 -> String(textBytes, Charsets.UTF_16)
            2 -> String(textBytes, Charsets.UTF_16BE)
            3 -> String(textBytes, Charsets.UTF_8)
            else -> return null
        }
        return raw.substringBefore('\u0000').trim().takeIf { it.isNotEmpty() }
    }

    private fun readMp3Summary(file: File, hasArtworkHint: Boolean): EmbeddedTagSummary {
        val data = runCatching { file.readBytes() }.getOrNull() ?: return EmbeddedTagSummary(hasArtwork = hasArtworkHint)
        if (data.size < 10 ||
            data[0] != 'I'.code.toByte() ||
            data[1] != 'D'.code.toByte() ||
            data[2] != '3'.code.toByte()
        ) {
            return EmbeddedTagSummary(hasArtwork = hasArtworkHint)
        }
        val major = data[3].toInt() and 0xFF
        val flags = data[5].toInt() and 0xFF
        val tagSize = synchsafeInt(data, 6)
        val tagBodyEnd = (10 + tagSize).coerceAtMost(data.size)
        if (tagBodyEnd <= 10 || (flags and 0x80) != 0) {
            return EmbeddedTagSummary(hasArtwork = hasArtworkHint)
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var trackNumber: Int? = null
        var hasArtwork = hasArtworkHint

        if (major == 2) {
            var cursor = 10
            while (cursor + 6 <= tagBodyEnd) {
                if (data[cursor] == 0.toByte()) break
                val id = String(data, cursor, 3, Charsets.ISO_8859_1)
                val size = ((data[cursor + 3].toInt() and 0xFF) shl 16) or
                    ((data[cursor + 4].toInt() and 0xFF) shl 8) or
                    (data[cursor + 5].toInt() and 0xFF)
                if (size <= 0 || cursor + 6 + size > tagBodyEnd) break
                val payload = data.copyOfRange(cursor + 6, cursor + 6 + size)
                when (id) {
                    "TT2" -> if (title == null) title = decodeId3TextPayload(payload)
                    "TP1" -> if (artist == null) artist = decodeId3TextPayload(payload)
                    "TAL" -> if (album == null) album = decodeId3TextPayload(payload)
                    "TP2" -> if (albumArtist == null) albumArtist = decodeId3TextPayload(payload)
                    "TCO" -> if (genre == null) genre = decodeId3TextPayload(payload)
                    "TRK" -> if (trackNumber == null) {
                        trackNumber = decodeId3TextPayload(payload)?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                    }
                    "PIC" -> hasArtwork = true
                }
                cursor += 6 + size
            }
        } else if (major == 3 || major == 4) {
            var bodyStart = 10
            if ((flags and 0x40) != 0 && tagBodyEnd - bodyStart >= 4) {
                val extHeaderSize = if (major == 4) {
                    synchsafeInt(data, bodyStart).coerceAtLeast(4)
                } else {
                    4 + bigEndianInt(data, bodyStart).coerceAtLeast(0)
                }
                bodyStart = (bodyStart + extHeaderSize).coerceAtMost(tagBodyEnd)
            }
            val tagBody = data.copyOfRange(bodyStart, tagBodyEnd)
            var cursor = 0
            while (cursor + 10 <= tagBody.size) {
                if (tagBody[cursor] == 0.toByte()) break
                val id = String(tagBody, cursor, 4, Charsets.ISO_8859_1)
                if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
                val size = resolveId3v2FrameSize(major, tagBody, cursor, emptySet()) ?: break
                var payload = tagBody.copyOfRange(cursor + 10, cursor + 10 + size)
                val frameFlags = tagBody[cursor + 9].toInt() and 0xFF
                if (major == 4 && (frameFlags and 0x0C) == 0) {
                    if ((frameFlags and 0x01) != 0 && payload.size >= 4) {
                        payload = payload.copyOfRange(4, payload.size)
                    }
                    if ((frameFlags and 0x02) != 0) {
                        payload = deUnsync(payload)
                    }
                }
                when (id) {
                    "TIT2" -> if (title == null) title = decodeId3TextPayload(payload)
                    "TPE1" -> if (artist == null) artist = decodeId3TextPayload(payload)
                    "TALB" -> if (album == null) album = decodeId3TextPayload(payload)
                    "TPE2" -> if (albumArtist == null) albumArtist = decodeId3TextPayload(payload)
                    "TCON" -> if (genre == null) genre = decodeId3TextPayload(payload)
                    "TRCK" -> if (trackNumber == null) {
                        trackNumber = decodeId3TextPayload(payload)?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                    }
                    "APIC" -> hasArtwork = true
                }
                cursor += 10 + size
            }
        }

        return EmbeddedTagSummary(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            trackNumber = trackNumber,
            hasArtwork = hasArtwork,
        )
    }

    private fun makeId3TextFrame(frameId: String, text: String): ByteArray {
        val utf16Le = text.toByteArray(Charsets.UTF_16LE)
        val payloadSize = 1 + 2 + utf16Le.size // encoding (0x01) + BOM (FF FE) + text
        val out = ByteArrayOutputStream(10 + payloadSize)
        out.write(frameId.toByteArray(Charsets.ISO_8859_1), 0, 4)
        out.write(bigEndianIntBytes(payloadSize))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(0x01)
        out.write(0xFF)
        out.write(0xFE)
        out.write(utf16Le)
        return out.toByteArray()
    }

    private fun makeId3ApicFrame(imageBytes: ByteArray, mimeType: String): ByteArray {
        val mimeBytes = mimeType.toByteArray(Charsets.ISO_8859_1)
        val payloadSize = 1 + mimeBytes.size + 1 + 1 + 1 + imageBytes.size
        val out = ByteArrayOutputStream(10 + payloadSize)
        out.write("APIC".toByteArray(Charsets.ISO_8859_1), 0, 4)
        out.write(bigEndianIntBytes(payloadSize))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(0x00) // ISO-8859-1
        out.write(mimeBytes)
        out.write(0x00) // NUL-terminated MIME
        out.write(0x03) // Front cover
        out.write(0x00) // Empty description
        out.write(imageBytes)
        return out.toByteArray()
    }

    // endregion

    // region MP4 / M4A

    private data class Mp4Box(
        val type: String,
        val start: Int,
        val end: Int,
        val headerSize: Int,
    ) {
        val totalSize: Int get() = end - start
        val payloadStart: Int get() = start + headerSize
    }

    private fun writeMp4(file: File, payload: AudioTagPayload): Boolean {
        val data = runCatching { file.readBytes() }.getOrNull() ?: return false
        if (data.size < 16) return false

        val topBoxes = parseMp4Boxes(data, 0, data.size) ?: return false
        val moovBox = topBoxes.firstOrNull { it.type == "moov" } ?: return false
        if (topBoxes.none { it.type == "ftyp" }) return false

        val moovChildren = parseMp4Boxes(data, moovBox.payloadStart, moovBox.end) ?: return false
        val existingUdta = moovChildren.firstOrNull { it.type == "udta" }
        val existingDirectMeta = moovChildren.firstOrNull { it.type == "meta" }

        val udtaChildren = if (existingUdta != null) {
            parseMp4Boxes(data, existingUdta.payloadStart, existingUdta.end) ?: return false
        } else {
            emptyList()
        }
        val existingUdtaMeta = udtaChildren.firstOrNull { it.type == "meta" }
        val sourceMeta = existingUdtaMeta ?: existingDirectMeta
        val existingIlstItems = extractExistingMp4IlstItems(data, sourceMeta)

        val artwork = detectImage(payload.artworkBytes)
        val replacedKeys = hashSetOf<String>()
        if (payload.title.isNotBlank()) replacedKeys.add(mp4Key("\u00A9nam"))
        if (payload.artist.isNotBlank()) replacedKeys.add(mp4Key("\u00A9ART"))
        if (payload.album.isNotBlank()) replacedKeys.add(mp4Key("\u00A9alb"))
        if (payload.albumArtist.isNotBlank()) replacedKeys.add("aART")
        if (payload.genre.isNotBlank()) replacedKeys.add(mp4Key("\u00A9gen"))
        if (payload.trackNumber != null && payload.trackNumber > 0) replacedKeys.add("trkn")
        if (artwork != null) replacedKeys.add("covr")

        val ilstBody = ByteArrayOutputStream()
        for ((itemKey, rawBox) in existingIlstItems) {
            if (itemKey !in replacedKeys) {
                ilstBody.write(rawBox)
            }
        }
        if (payload.title.isNotBlank()) {
            ilstBody.write(makeMp4TextItem("\u00A9nam", payload.title))
        }
        if (payload.artist.isNotBlank()) {
            ilstBody.write(makeMp4TextItem("\u00A9ART", payload.artist))
        }
        if (payload.album.isNotBlank()) {
            ilstBody.write(makeMp4TextItem("\u00A9alb", payload.album))
        }
        if (payload.albumArtist.isNotBlank()) {
            ilstBody.write(makeMp4TextItem("aART", payload.albumArtist))
        }
        if (payload.genre.isNotBlank()) {
            ilstBody.write(makeMp4TextItem("\u00A9gen", payload.genre))
        }
        if (payload.trackNumber != null && payload.trackNumber > 0) {
            val trackPayload = byteArrayOf(
                0, 0,
                ((payload.trackNumber ushr 8) and 0xFF).toByte(),
                (payload.trackNumber and 0xFF).toByte(),
                0, 0, 0, 0,
            )
            ilstBody.write(makeMp4Box("trkn", makeMp4DataBox(0, trackPayload)))
        }
        if (artwork != null) {
            ilstBody.write(makeMp4Box("covr", makeMp4DataBox(artwork.mp4DataType, artwork.bytes)))
        }

        val hdlrPayload = byteArrayOf(
            0, 0, 0, 0, // version + flags
            0, 0, 0, 0, // pre_defined
            'm'.code.toByte(), 'd'.code.toByte(), 'i'.code.toByte(), 'r'.code.toByte(),
            'a'.code.toByte(), 'p'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(),
            0, 0, 0, 0,
            0, 0, 0, 0,
            0, // empty name
        )
        val metaInner = ByteArrayOutputStream()
        metaInner.write(byteArrayOf(0, 0, 0, 0)) // ISO BMFF FullBox version/flags
        metaInner.write(makeMp4Box("hdlr", hdlrPayload))
        metaInner.write(makeMp4Box("ilst", ilstBody.toByteArray()))
        val newMetaBox = makeMp4Box("meta", metaInner.toByteArray())

        val udtaInner = ByteArrayOutputStream()
        for (child in udtaChildren) {
            if (child.type != "meta") {
                udtaInner.write(data, child.start, child.totalSize)
            }
        }
        udtaInner.write(newMetaBox)
        val newUdtaBox = makeMp4Box("udta", udtaInner.toByteArray())

        val moovInner = ByteArrayOutputStream()
        for (child in moovChildren) {
            if (child.type != "udta" && child.type != "meta") {
                moovInner.write(data, child.start, child.totalSize)
            }
        }
        moovInner.write(newUdtaBox)
        val newMoovBox = makeMp4Box("moov", moovInner.toByteArray())

        val delta = newMoovBox.size - moovBox.totalSize
        if (delta != 0) {
            adjustMp4ChunkOffsets(
                moovBytes = newMoovBox,
                start = 8,
                end = newMoovBox.size,
                minOffsetToShift = moovBox.end.toLong(),
                delta = delta.toLong(),
            )
        }

        val out = ByteArrayOutputStream(data.size + delta.coerceAtLeast(0))
        out.write(data, 0, moovBox.start)
        out.write(newMoovBox)
        out.write(data, moovBox.end, data.size - moovBox.end)

        return atomicReplace(file, out.toByteArray()) { tempFile ->
            if (artwork == null) true else EmbeddedArtworkExtractor.extract(tempFile, needArtwork = true, needLyrics = false).artwork != null
        }
    }

    private fun parseMp4Boxes(data: ByteArray, start: Int, end: Int): List<Mp4Box>? {
        val boxes = mutableListOf<Mp4Box>()
        var cursor = start
        while (cursor + 8 <= end) {
            val size32 = bigEndianUInt32(data, cursor)
            val type = latin1(data, cursor + 4, 4)
            val headerSize: Int
            val totalSize: Long
            when (size32) {
                1L -> {
                    if (cursor + 16 > end) return null
                    totalSize = bigEndianLong(data, cursor + 8)
                    headerSize = 16
                }
                0L -> {
                    totalSize = (end - cursor).toLong()
                    headerSize = 8
                }
                else -> {
                    totalSize = size32
                    headerSize = 8
                }
            }
            if (totalSize < headerSize || cursor + totalSize > end) return null
            val boxEnd = cursor + totalSize.toInt()
            boxes.add(Mp4Box(type = type, start = cursor, end = boxEnd, headerSize = headerSize))
            cursor = boxEnd
        }
        return boxes
    }

    private fun extractExistingMp4IlstItems(data: ByteArray, metaBox: Mp4Box?): List<Pair<String, ByteArray>> {
        if (metaBox == null) return emptyList()
        val bodyStart = metaBox.payloadStart
        val bodyEnd = metaBox.end
        if (bodyEnd - bodyStart < 8) return emptyList()

        // Try FullBox (4-byte version/flags) then QuickTime direct meta.
        val childOffset = if (bodyEnd - bodyStart >= 12) {
            val fullSize = bigEndianUInt32(data, bodyStart + 4)
            val fullType = latin1(data, bodyStart + 8, 4)
            if (fullType in setOf("hdlr", "ilst", "keys", "free", "udta") && fullSize in 8L..(bodyEnd - bodyStart - 4L)) {
                bodyStart + 4
            } else {
                bodyStart
            }
        } else {
            bodyStart
        }

        val metaChildren = parseMp4Boxes(data, childOffset, bodyEnd) ?: return emptyList()
        val ilst = metaChildren.firstOrNull { it.type == "ilst" } ?: return emptyList()
        val ilstItems = parseMp4Boxes(data, ilst.payloadStart, ilst.end) ?: return emptyList()
        return ilstItems.map { box -> box.type to data.copyOfRange(box.start, box.end) }
    }

    private fun readMp4Summary(file: File, hasArtworkHint: Boolean): EmbeddedTagSummary {
        val data = runCatching { file.readBytes() }.getOrNull() ?: return EmbeddedTagSummary(hasArtwork = hasArtworkHint)
        if (data.size < 16) return EmbeddedTagSummary(hasArtwork = hasArtworkHint)
        val topBoxes = parseMp4Boxes(data, 0, data.size) ?: return EmbeddedTagSummary(hasArtwork = hasArtworkHint)
        val moovBox = topBoxes.firstOrNull { it.type == "moov" } ?: return EmbeddedTagSummary(hasArtwork = hasArtworkHint)
        val moovChildren = parseMp4Boxes(data, moovBox.payloadStart, moovBox.end) ?: return EmbeddedTagSummary(hasArtwork = hasArtworkHint)
        val existingUdta = moovChildren.firstOrNull { it.type == "udta" }
        val existingDirectMeta = moovChildren.firstOrNull { it.type == "meta" }
        val udtaMeta = existingUdta?.let { udta ->
            parseMp4Boxes(data, udta.payloadStart, udta.end)?.firstOrNull { it.type == "meta" }
        }
        val sourceMeta = udtaMeta ?: existingDirectMeta ?: return EmbeddedTagSummary(hasArtwork = hasArtworkHint)
        val items = extractExistingMp4IlstItems(data, sourceMeta)

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var genre: String? = null
        var trackNumber: Int? = null
        var hasArtwork = hasArtworkHint

        val keyTitle = mp4Key("\u00A9nam")
        val keyArtist = mp4Key("\u00A9ART")
        val keyAlbum = mp4Key("\u00A9alb")
        val keyGenre = mp4Key("\u00A9gen")

        for ((itemKey, rawBox) in items) {
            val childBoxes = parseMp4Boxes(rawBox, 8, rawBox.size) ?: continue
            val dataBox = childBoxes.firstOrNull { it.type == "data" } ?: continue
            val dataPayloadStart = dataBox.payloadStart + 8
            if (dataPayloadStart > dataBox.end) continue
            when (itemKey) {
                keyTitle -> if (title == null) title = String(rawBox, dataPayloadStart, dataBox.end - dataPayloadStart, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                keyArtist -> if (artist == null) artist = String(rawBox, dataPayloadStart, dataBox.end - dataPayloadStart, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                keyAlbum -> if (album == null) album = String(rawBox, dataPayloadStart, dataBox.end - dataPayloadStart, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                "aART" -> if (albumArtist == null) albumArtist = String(rawBox, dataPayloadStart, dataBox.end - dataPayloadStart, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                keyGenre -> if (genre == null) genre = String(rawBox, dataPayloadStart, dataBox.end - dataPayloadStart, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
                "trkn" -> if (trackNumber == null && dataBox.end - dataPayloadStart >= 4) {
                    val num = ((rawBox[dataPayloadStart + 2].toInt() and 0xFF) shl 8) or
                        (rawBox[dataPayloadStart + 3].toInt() and 0xFF)
                    if (num > 0) trackNumber = num
                }
                "covr" -> hasArtwork = true
            }
        }

        return EmbeddedTagSummary(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            trackNumber = trackNumber,
            hasArtwork = hasArtwork,
        )
    }

    private fun adjustMp4ChunkOffsets(
        moovBytes: ByteArray,
        start: Int,
        end: Int,
        minOffsetToShift: Long,
        delta: Long,
    ) {
        var cursor = start
        while (cursor + 8 <= end) {
            val size32 = bigEndianUInt32(moovBytes, cursor)
            if (size32 < 8L || cursor + size32 > end) break
            val boxEnd = cursor + size32.toInt()
            val type = latin1(moovBytes, cursor + 4, 4)
            when (type) {
                "trak", "mdia", "minf", "stbl" -> {
                    adjustMp4ChunkOffsets(moovBytes, cursor + 8, boxEnd, minOffsetToShift, delta)
                }
                "stco" -> {
                    if (cursor + 16 <= boxEnd) {
                        val count = bigEndianInt(moovBytes, cursor + 12)
                        var p = cursor + 16
                        for (i in 0 until count) {
                            if (p + 4 > boxEnd) break
                            val oldOff = bigEndianUInt32(moovBytes, p)
                            if (oldOff >= minOffsetToShift) {
                                val nextOff = (oldOff + delta).coerceAtLeast(0L)
                                writeUInt32BE(moovBytes, p, nextOff)
                            }
                            p += 4
                        }
                    }
                }
                "co64" -> {
                    if (cursor + 16 <= boxEnd) {
                        val count = bigEndianInt(moovBytes, cursor + 12)
                        var p = cursor + 16
                        for (i in 0 until count) {
                            if (p + 8 > boxEnd) break
                            val oldOff = bigEndianLong(moovBytes, p)
                            if (oldOff >= minOffsetToShift) {
                                val nextOff = (oldOff + delta).coerceAtLeast(0L)
                                writeInt64BE(moovBytes, p, nextOff)
                            }
                            p += 8
                        }
                    }
                }
            }
            cursor = boxEnd
        }
    }

    private fun mp4Key(s: String): String =
        String(s.map { (it.code and 0xFF).toByte() }.toByteArray(), Charsets.ISO_8859_1)

    private fun makeMp4TextItem(typeKey: String, value: String): ByteArray =
        makeMp4BoxBytes(
            byteArrayOf(
                (typeKey[0].code and 0xFF).toByte(),
                (typeKey[1].code and 0xFF).toByte(),
                (typeKey[2].code and 0xFF).toByte(),
                (typeKey[3].code and 0xFF).toByte(),
            ),
            makeMp4DataBox(1, value.toByteArray(Charsets.UTF_8)),
        )

    private fun makeMp4DataBox(typeIndicator: Int, payload: ByteArray): ByteArray {
        val inner = ByteArrayOutputStream(8 + payload.size)
        inner.write(bigEndianIntBytes(typeIndicator))
        inner.write(byteArrayOf(0, 0, 0, 0)) // locale
        inner.write(payload)
        return makeMp4Box("data", inner.toByteArray())
    }

    private fun makeMp4Box(type: String, payload: ByteArray): ByteArray =
        makeMp4BoxBytes(type.toByteArray(Charsets.ISO_8859_1), payload)

    private fun makeMp4BoxBytes(typeBytes: ByteArray, payload: ByteArray): ByteArray {
        val total = 8 + payload.size
        val out = ByteArray(total)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(total)
        bb.put(typeBytes, 0, 4)
        bb.put(payload)
        return out
    }

    // endregion

    // region Helpers

    private fun atomicReplace(
        target: File,
        bytes: ByteArray,
        validator: (File) -> Boolean,
    ): Boolean {
        val parent = target.parentFile ?: return false
        val temp = File(parent, ".${target.name}.tagtmp.${System.nanoTime()}")
        return try {
            temp.writeBytes(bytes)
            if (!validator(temp)) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            true
        } catch (_: Throwable) {
            runCatching { temp.delete() }
            false
        }
    }

    private fun synchsafeInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun encodeSynchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun bigEndianInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun bigEndianUInt32(bytes: ByteArray, offset: Int): Long =
        bigEndianInt(bytes, offset).toLong() and 0xFFFF_FFFFL

    private fun bigEndianLong(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFFL) shl 56) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 48) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 40) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 32) or
            ((bytes[offset + 4].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 5].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 6].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 7].toLong() and 0xFFL)

    private fun bigEndianIntBytes(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun writeUInt32BE(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = ((value ushr 24) and 0xFFL).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFFL).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFFL).toByte()
        bytes[offset + 3] = (value and 0xFFL).toByte()
    }

    private fun writeInt64BE(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value ushr (56 - i * 8)) and 0xFFL).toByte()
        }
    }

    private fun latin1(bytes: ByteArray, offset: Int, len: Int): String =
        String(bytes, offset, len, Charsets.ISO_8859_1)

    // endregion
}
