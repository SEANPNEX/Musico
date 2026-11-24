package com.musico.core

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.util.Base64

data class ExtractedEmbeddedTags(
    val artwork: ByteArray? = null,
    val lyrics: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractedEmbeddedTags) return false
        val artEqual = when {
            artwork === other.artwork -> true
            artwork == null || other.artwork == null -> false
            else -> artwork.contentEquals(other.artwork)
        }
        return artEqual && lyrics == other.lyrics
    }

    override fun hashCode(): Int {
        var result = artwork?.contentHashCode() ?: 0
        result = 31 * result + (lyrics?.hashCode() ?: 0)
        return result
    }
}

/**
 * Container-level artwork and lyrics extractor supporting ID3v2 (v2.2/v2.3/v2.4),
 * FLAC (and ID3v2-prepended FLAC), MP4/M4A/ALAC, Ogg Vorbis/Opus, RIFF WAV, and AIFF.
 */
object EmbeddedArtworkExtractor {
    internal const val MAX_TAG_BYTES = 32 * 1024 * 1024

    private const val PICTURE_TYPE_FRONT_COVER = 3
    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
    private val MP4_TOP_CONTAINERS = setOf("ftyp", "moov", "mdat", "free", "wide", "skip")
    private val META_CHILD_ATOMS = setOf("hdlr", "ilst", "keys", "free", "udta")

    internal interface ByteSource : Closeable {
        fun readExact(count: Int): ByteArray?
        fun readAvailable(maxCount: Int): ByteArray
        fun skipExact(count: Long): Boolean
        fun position(): Long
        fun seek(pos: Long): Boolean
        fun size(): Long?
    }

    internal class FileChannelByteSource(
        private val channel: FileChannel,
        private val onClose: (() -> Unit)? = null,
    ) : ByteSource {
        override fun readExact(count: Int): ByteArray? {
            if (count == 0) return ByteArray(0)
            if (count < 0) return null
            val buf = ByteArray(count)
            val bb = ByteBuffer.wrap(buf)
            while (bb.hasRemaining()) {
                val read = runCatching { channel.read(bb) }.getOrElse { return null }
                if (read < 0) return null
            }
            return buf
        }

        override fun readAvailable(maxCount: Int): ByteArray {
            if (maxCount <= 0) return ByteArray(0)
            val buf = ByteArray(maxCount)
            val bb = ByteBuffer.wrap(buf)
            while (bb.hasRemaining()) {
                val read = try {
                    channel.read(bb)
                } catch (_: Exception) {
                    -1
                }
                if (read < 0) break
            }
            val total = bb.position()
            return if (total == buf.size) buf else buf.copyOf(total)
        }

        override fun skipExact(count: Long): Boolean {
            if (count == 0L) return true
            if (count < 0L) return false
            val cur = position()
            val target = cur + count
            if (target < cur) return false
            val totalSize = size()
            if (totalSize != null && target > totalSize) return false
            return seek(target)
        }

        override fun position(): Long = runCatching { channel.position() }.getOrDefault(0L)

        override fun seek(pos: Long): Boolean {
            if (pos < 0L) return false
            return runCatching {
                channel.position(pos)
                true
            }.getOrDefault(false)
        }

        override fun size(): Long? = runCatching { channel.size() }.getOrNull()

        override fun close() {
            runCatching { channel.close() }
            onClose?.invoke()
        }
    }

    internal class StreamByteSource(
        private val stream: InputStream,
        private val totalSize: Long? = null,
        private val onClose: (() -> Unit)? = null,
    ) : ByteSource {
        private var pos = 0L

        override fun readExact(count: Int): ByteArray? {
            if (count == 0) return ByteArray(0)
            if (count < 0) return null
            val buf = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = runCatching { stream.read(buf, offset, count - offset) }.getOrElse { return null }
                if (read < 0) return null
                offset += read
                pos += read
            }
            return buf
        }

        override fun readAvailable(maxCount: Int): ByteArray {
            if (maxCount <= 0) return ByteArray(0)
            val buf = ByteArray(maxCount)
            var offset = 0
            while (offset < maxCount) {
                val read = try {
                    stream.read(buf, offset, maxCount - offset)
                } catch (_: Exception) {
                    -1
                }
                if (read < 0) break
                offset += read
                pos += read
            }
            return if (offset == buf.size) buf else buf.copyOf(offset)
        }

        override fun skipExact(count: Long): Boolean {
            if (count == 0L) return true
            if (count < 0L) return false
            var remaining = count
            while (remaining > 0L) {
                val skipped = runCatching { stream.skip(remaining) }.getOrElse { return false }
                if (skipped > 0L) {
                    remaining -= skipped
                    pos += skipped
                } else {
                    val b = runCatching { stream.read() }.getOrElse { return false }
                    if (b < 0) return false
                    remaining--
                    pos++
                }
            }
            return true
        }

        override fun position(): Long = pos

        override fun seek(pos: Long): Boolean {
            if (pos < this.pos) return false
            return skipExact(pos - this.pos)
        }

        override fun size(): Long? = totalSize

        override fun close() {
            runCatching { stream.close() }
            onClose?.invoke()
        }
    }

    private class ReplayPrefixByteSource(
        private val prefix: ByteArray,
        private val backing: ByteSource,
    ) : ByteSource {
        private var pos = 0L

        override fun readExact(count: Int): ByteArray? {
            if (count == 0) return ByteArray(0)
            if (count < 0) return null
            val out = ByteArray(count)
            var outOffset = 0
            if (pos < prefix.size) {
                val avail = (prefix.size - pos.toInt()).coerceAtMost(count)
                System.arraycopy(prefix, pos.toInt(), out, 0, avail)
                pos += avail
                outOffset += avail
            }
            if (outOffset < count) {
                val rest = backing.readExact(count - outOffset) ?: return null
                System.arraycopy(rest, 0, out, outOffset, rest.size)
                pos += rest.size
            }
            return out
        }

        override fun readAvailable(maxCount: Int): ByteArray {
            if (maxCount <= 0) return ByteArray(0)
            val out = ByteArray(maxCount)
            var outOffset = 0
            if (pos < prefix.size) {
                val avail = (prefix.size - pos.toInt()).coerceAtMost(maxCount)
                System.arraycopy(prefix, pos.toInt(), out, 0, avail)
                pos += avail
                outOffset += avail
            }
            if (outOffset < maxCount) {
                val rest = backing.readAvailable(maxCount - outOffset)
                System.arraycopy(rest, 0, out, outOffset, rest.size)
                pos += rest.size
                outOffset += rest.size
            }
            return if (outOffset == out.size) out else out.copyOf(outOffset)
        }

        override fun skipExact(count: Long): Boolean {
            if (count == 0L) return true
            if (count < 0L) return false
            var remaining = count
            if (pos < prefix.size) {
                val avail = (prefix.size.toLong() - pos).coerceAtMost(remaining)
                pos += avail
                remaining -= avail
            }
            if (remaining > 0L) {
                if (!backing.skipExact(remaining)) return false
                pos += remaining
            }
            return true
        }

        override fun position(): Long = pos

        override fun seek(pos: Long): Boolean {
            if (pos < this.pos) {
                if (pos < 0L) return false
                // Can only rewind if still within prefix
                if (this.pos <= prefix.size && pos <= prefix.size) {
                    this.pos = pos
                    return true
                }
                return false
            }
            return skipExact(pos - this.pos)
        }

        override fun size(): Long? = backing.size()

        override fun close() {
            backing.close()
        }
    }

    private class ByteSourceInputStream(private val source: ByteSource) : InputStream() {
        override fun read(): Int {
            val single = source.readAvailable(1)
            return if (single.isEmpty()) -1 else (single[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val chunk = source.readAvailable(len)
            if (chunk.isEmpty()) return -1
            System.arraycopy(chunk, 0, b, off, chunk.size)
            return chunk.size
        }

        override fun skip(n: Long): Long {
            if (n <= 0L) return 0L
            val before = source.position()
            return if (source.skipExact(n)) {
                source.position() - before
            } else {
                0L
            }
        }
    }

    fun extract(
        context: Context,
        uri: Uri,
        needArtwork: Boolean = true,
        needLyrics: Boolean = true,
    ): ExtractedEmbeddedTags {
        if (!needArtwork && !needLyrics) return ExtractedEmbeddedTags()
        val source = openByteSource(context, uri) ?: return ExtractedEmbeddedTags()
        return source.use { extract(it, needArtwork, needLyrics) }
    }

    fun extract(
        file: File,
        needArtwork: Boolean = true,
        needLyrics: Boolean = true,
    ): ExtractedEmbeddedTags {
        if (!needArtwork && !needLyrics) return ExtractedEmbeddedTags()
        if (!file.exists() || !file.isFile) return ExtractedEmbeddedTags()
        val fis = runCatching { FileInputStream(file) }.getOrNull() ?: return ExtractedEmbeddedTags()
        val source = FileChannelByteSource(fis.channel) { runCatching { fis.close() } }
        return source.use { extract(it, needArtwork, needLyrics) }
    }

    internal fun extract(
        bytes: ByteArray,
        needArtwork: Boolean = true,
        needLyrics: Boolean = true,
    ): ExtractedEmbeddedTags {
        return StreamByteSource(ByteArrayInputStream(bytes), totalSize = bytes.size.toLong()).use {
            extract(it, needArtwork, needLyrics)
        }
    }

    internal fun extract(
        rawSource: ByteSource,
        needArtwork: Boolean = true,
        needLyrics: Boolean = true,
    ): ExtractedEmbeddedTags {
        if (!needArtwork && !needLyrics) return ExtractedEmbeddedTags()

        val header = rawSource.readAvailable(12)
        if (header.size < 4) return ExtractedEmbeddedTags()

        val source: ByteSource = if (rawSource.seek(0L)) {
            rawSource
        } else {
            ReplayPrefixByteSource(header, rawSource)
        }

        return when {
            header[0] == 0x49.toByte() && header[1] == 0x44.toByte() && header[2] == 0x33.toByte() -> {
                val (id3Tags, consumedTag) = extractId3v2Internal(
                    ByteSourceInputStream(source),
                    needArtwork = needArtwork,
                    needLyrics = needLyrics,
                )
                var artwork = id3Tags.artwork
                var lyrics = id3Tags.lyrics
                if (consumedTag && ((needArtwork && artwork == null) || (needLyrics && lyrics == null))) {
                    val flac = FlacTags.read(ByteSourceInputStream(source))
                    if (flac != null) {
                        if (needArtwork && artwork == null) {
                            artwork = flac.picture?.let(::normalizeImageBytes)
                        }
                        if (needLyrics && lyrics == null) {
                            lyrics = firstClean(flac.comments["LYRICS"], flac.comments["UNSYNCEDLYRICS"])
                        }
                    }
                }
                ExtractedEmbeddedTags(artwork = artwork, lyrics = lyrics)
            }

            header[0] == 0x66.toByte() &&
                header[1] == 0x4C.toByte() &&
                header[2] == 0x61.toByte() &&
                header[3] == 0x43.toByte() -> {
                val flac = FlacTags.read(ByteSourceInputStream(source)) ?: return ExtractedEmbeddedTags()
                ExtractedEmbeddedTags(
                    artwork = if (needArtwork) flac.picture?.let(::normalizeImageBytes) else null,
                    lyrics = if (needLyrics) firstClean(flac.comments["LYRICS"], flac.comments["UNSYNCEDLYRICS"]) else null,
                )
            }

            header.size >= 8 && ascii(header, 4, 4) in MP4_TOP_CONTAINERS -> {
                extractMp4(source, needArtwork, needLyrics)
            }

            header[0] == 0x4F.toByte() &&
                header[1] == 0x67.toByte() &&
                header[2] == 0x67.toByte() &&
                header[3] == 0x53.toByte() -> {
                extractOgg(ByteSourceInputStream(source), needArtwork, needLyrics)
            }

            header.size >= 12 &&
                ascii(header, 0, 4) == "RIFF" &&
                ascii(header, 8, 4) == "WAVE" -> {
                extractRiffWav(source, needArtwork, needLyrics)
            }

            header.size >= 12 &&
                ascii(header, 0, 4) == "FORM" &&
                (ascii(header, 8, 4) == "AIFF" || ascii(header, 8, 4) == "AIFC") -> {
                extractAiff(source, needArtwork, needLyrics)
            }

            else -> ExtractedEmbeddedTags()
        }
    }

    private fun openByteSource(context: Context, uri: Uri): ByteSource? {
        if (uri.scheme == "file" && uri.path != null) {
            val fis = runCatching { FileInputStream(uri.path!!) }.getOrNull() ?: return null
            return FileChannelByteSource(fis.channel) { runCatching { fis.close() } }
        }

        val pfd: ParcelFileDescriptor? = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
        }.getOrNull()
        if (pfd != null) {
            val fis = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            val channel = fis.channel
            val seekable = runCatching { channel.size() >= 0L && channel.position() == 0L }.getOrDefault(false)
            if (seekable) {
                return FileChannelByteSource(channel) {
                    runCatching { fis.close() }
                    runCatching { pfd.close() }
                }
            }
            runCatching { fis.close() }
            runCatching { pfd.close() }
        }

        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() ?: return null
        return StreamByteSource(BufferedInputStream(stream, 65536)) { runCatching { stream.close() } }
    }

    internal fun normalizeImageBytes(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        if (hasImageHeaderAt(bytes, 0)) return bytes

        var start = 0
        while (start < bytes.size && start < 16 && bytes[start] == 0.toByte()) {
            start++
        }
        if (start == 0 || start >= bytes.size) return null
        if (!hasImageHeaderAt(bytes, start)) return null
        return bytes.copyOfRange(start, bytes.size)
    }

    private fun hasImageHeaderAt(bytes: ByteArray, start: Int): Boolean {
        val rem = bytes.size - start
        if (rem < 3) return false
        fun u(offset: Int): Int = bytes[start + offset].toInt() and 0xFF

        return when {
            // JPEG: FF D8 FF
            rem >= 3 && u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> true
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            rem >= 8 && u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47 &&
                u(4) == 0x0D && u(5) == 0x0A && u(6) == 0x1A && u(7) == 0x0A -> true
            // WebP: RIFF....WEBP
            rem >= 12 && ascii(bytes, start, 4) == "RIFF" && ascii(bytes, start + 8, 4) == "WEBP" -> true
            // GIF: GIF87a / GIF89a
            rem >= 6 && (ascii(bytes, start, 6) == "GIF87a" || ascii(bytes, start, 6) == "GIF89a") -> true
            // BMP: BM (>= 26 bytes)
            rem >= 26 && u(0) == 0x42 && u(1) == 0x4D -> true
            // TIFF: II*\0 or MM\0*
            rem >= 4 && (
                (u(0) == 0x49 && u(1) == 0x49 && u(2) == 0x2A && u(3) == 0x00) ||
                    (u(0) == 0x4D && u(1) == 0x4D && u(2) == 0x00 && u(3) == 0x2A)
                ) -> true
            // HEIF / HEIC / AVIF
            rem >= 12 && ascii(bytes, start + 4, 4) == "ftyp" && ascii(bytes, start + 8, 4) in HEIF_BRANDS -> true
            else -> false
        }
    }

    internal fun extractId3v2(
        input: InputStream,
        needArtwork: Boolean = true,
        needLyrics: Boolean = true,
    ): ExtractedEmbeddedTags = extractId3v2Internal(input, needArtwork, needLyrics).first

    private fun extractId3v2Internal(
        input: InputStream,
        needArtwork: Boolean,
        needLyrics: Boolean,
    ): Pair<ExtractedEmbeddedTags, Boolean> {
        val header = readExactStream(input, 10) ?: return ExtractedEmbeddedTags() to false
        if (header[0] != 0x49.toByte() || header[1] != 0x44.toByte() || header[2] != 0x33.toByte()) {
            return ExtractedEmbeddedTags() to false
        }
        val majorVersion = header[3].toInt() and 0xFF
        if (majorVersion !in 2..4) return ExtractedEmbeddedTags() to false
        val flags = header[5].toInt() and 0xFF
        val tagSize = synchsafeInt(header, 6)
        if (tagSize <= 0 || tagSize > MAX_TAG_BYTES) return ExtractedEmbeddedTags() to false

        var tagBytes = readExactStream(input, tagSize) ?: return ExtractedEmbeddedTags() to false
        if (majorVersion == 4 && (flags and 0x10) != 0) {
            // Skip 10-byte ID3v2.4 footer so chained streams (e.g. FLAC after ID3v2) align cleanly.
            readExactStream(input, 10)
        }

        val tagUnsync = (flags and 0x80) != 0
        if (majorVersion < 4 && tagUnsync) {
            tagBytes = removeUnsync(tagBytes)
        }

        var pos = 0
        val tagEnd = tagBytes.size

        // Skip extended header if present (bit 6)
        if ((flags and 0x40) != 0 && majorVersion >= 3 && pos + 4 <= tagEnd) {
            val extSize = if (majorVersion == 4) synchsafeInt(tagBytes, pos) else bigEndianInt(tagBytes, pos)
            if (extSize > 0) {
                val advance = if (majorVersion == 4) extSize else extSize + 4
                if (pos + advance <= tagEnd) {
                    pos += advance
                }
            }
        }

        var bestArtwork: ByteArray? = null
        var bestPicType = -1
        var lyrics: String? = null

        if (majorVersion == 2) {
            while (pos + 6 <= tagEnd) {
                if (tagBytes[pos] == 0.toByte()) break
                val frameId = ascii(tagBytes, pos, 3)
                val frameSize = bigEndian24(tagBytes, pos + 3)
                pos += 6
                if (frameSize <= 0 || pos + frameSize > tagEnd) break

                if (needArtwork && frameId == "PIC") {
                    val pic = parsePicFrame(tagBytes, pos, frameSize)
                    if (pic != null) {
                        val (picType, data) = pic
                        if (bestArtwork == null || (bestPicType != PICTURE_TYPE_FRONT_COVER && picType == PICTURE_TYPE_FRONT_COVER)) {
                            bestArtwork = data
                            bestPicType = picType
                        }
                    }
                }
                pos += frameSize
                if ((!needArtwork || bestPicType == PICTURE_TYPE_FRONT_COVER) && (!needLyrics || lyrics != null)) {
                    break
                }
            }
            return ExtractedEmbeddedTags(artwork = bestArtwork, lyrics = lyrics) to true
        }

        while (pos + 10 <= tagEnd) {
            if (tagBytes[pos] == 0.toByte()) break
            val frameId = ascii(tagBytes, pos, 4)
            val rawSizeOffset = pos + 4
            val frameSize = resolveFrameSize(
                majorVersion = majorVersion,
                tagBytes = tagBytes,
                sizeOffset = rawSizeOffset,
                payloadStart = pos + 10,
                tagEnd = tagEnd,
            )
            val frameFlags = ((tagBytes[pos + 8].toInt() and 0xFF) shl 8) or
                (tagBytes[pos + 9].toInt() and 0xFF)
            pos += 10
            if (frameSize <= 0 || pos + frameSize > tagEnd) break

            val wantsThisFrame = (needArtwork && frameId == "APIC") || (needLyrics && lyrics == null && frameId == "USLT")
            if (wantsThisFrame && isSupportedFrameFlags(majorVersion, frameFlags)) {
                var payloadOffset = pos
                var payloadLen = frameSize
                var workingBytes = tagBytes

                if (majorVersion == 4) {
                    if ((frameFlags and 0x0040) != 0 && payloadLen > 1) {
                        payloadOffset += 1
                        payloadLen -= 1
                    }
                    val frameUnsync = tagUnsync || (frameFlags and 0x0002) != 0
                    if (frameUnsync) {
                        workingBytes = removeUnsync(workingBytes, payloadOffset, payloadLen)
                        payloadOffset = 0
                        payloadLen = workingBytes.size
                    }
                    if ((frameFlags and 0x0001) != 0 && payloadLen >= 4) {
                        payloadOffset += 4
                        payloadLen -= 4
                    }
                }

                when (frameId) {
                    "APIC" -> {
                        val apic = parseApicFrame(workingBytes, payloadOffset, payloadLen)
                        if (apic != null) {
                            val (picType, data) = apic
                            if (bestArtwork == null || (bestPicType != PICTURE_TYPE_FRONT_COVER && picType == PICTURE_TYPE_FRONT_COVER)) {
                                bestArtwork = data
                                bestPicType = picType
                            }
                        }
                    }

                    "USLT" -> {
                        if (lyrics == null) {
                            lyrics = decodeUsltFrame(workingBytes, payloadOffset, payloadLen)
                        }
                    }
                }
            }

            pos += frameSize
            if ((!needArtwork || bestPicType == PICTURE_TYPE_FRONT_COVER) && (!needLyrics || lyrics != null)) {
                break
            }
        }

        return ExtractedEmbeddedTags(artwork = bestArtwork, lyrics = lyrics) to true
    }

    private fun isSupportedFrameFlags(majorVersion: Int, frameFlags: Int): Boolean {
        return if (majorVersion == 4) {
            // Reject compressed (0x0008) or encrypted (0x0004) frames.
            (frameFlags and 0x000C) == 0
        } else {
            // ID3v2.3 format flags are in bits 7..5 of the second byte: 0x0080 comp, 0x0040 enc.
            (frameFlags and 0x00C0) == 0
        }
    }

    private fun resolveFrameSize(
        majorVersion: Int,
        tagBytes: ByteArray,
        sizeOffset: Int,
        payloadStart: Int,
        tagEnd: Int,
    ): Int {
        val be = bigEndianInt(tagBytes, sizeOffset)
        if (majorVersion != 4) return be

        for (i in 0 until 4) {
            if ((tagBytes[sizeOffset + i].toInt() and 0x80) != 0) {
                return be
            }
        }

        val sync = synchsafeInt(tagBytes, sizeOffset)
        if (sync == be) return sync

        val syncValid = sync > 0 && payloadStart + sync <= tagEnd &&
            isPlausibleFrameBoundary(tagBytes, payloadStart + sync, tagEnd)
        val beValid = be > 0 && payloadStart + be <= tagEnd &&
            isPlausibleFrameBoundary(tagBytes, payloadStart + be, tagEnd)

        return when {
            beValid && !syncValid -> be
            syncValid && !beValid -> sync
            else -> sync
        }
    }

    private fun isPlausibleFrameBoundary(tagBytes: ByteArray, offset: Int, tagEnd: Int): Boolean {
        if (offset == tagEnd) return true
        if (offset < 0 || offset >= tagEnd) return false
        if (tagBytes[offset] == 0.toByte()) return true
        if (offset + 10 > tagEnd) return false
        val first = tagBytes[offset].toInt() and 0xFF
        if (first !in 'A'.code..'Z'.code) return false
        for (i in 1 until 4) {
            val c = tagBytes[offset + i].toInt() and 0xFF
            val isUpper = c in 'A'.code..'Z'.code
            val isDigit = c in '0'.code..'9'.code
            if (!isUpper && !isDigit) return false
        }
        val flagHi = tagBytes[offset + 8].toInt() and 0xFF
        val flagLo = tagBytes[offset + 9].toInt() and 0xFF
        if ((flagHi and 0x8F) != 0 || (flagLo and 0xB0) != 0) return false
        val remaining = tagEnd - (offset + 10)
        val nextSync = synchsafeInt(tagBytes, offset + 4)
        val nextBe = bigEndianInt(tagBytes, offset + 4)
        return nextSync in 1..remaining || nextBe in 1..remaining
    }

    private fun parseApicFrame(data: ByteArray, offset: Int, length: Int): Pair<Int, ByteArray>? {
        if (length < 4) return null
        val end = offset + length
        val encoding = data[offset].toInt() and 0xFF
        var cursor = offset + 1

        val mimeStart = cursor
        while (cursor < end && data[cursor] != 0.toByte()) {
            cursor++
        }
        if (cursor >= end) return null
        val mime = String(data, mimeStart, cursor - mimeStart, Charsets.ISO_8859_1).trim()
        cursor++ // skip NUL
        if (mime == "-->" || cursor >= end) return null

        val pictureType = data[cursor].toInt() and 0xFF
        cursor++

        val descStart = cursor
        val img = extractImageAfterDescription(data, descStart, end, encoding) ?: return null
        return pictureType to img
    }

    private fun parsePicFrame(data: ByteArray, offset: Int, length: Int): Pair<Int, ByteArray>? {
        if (length < 6) return null
        val end = offset + length
        val encoding = data[offset].toInt() and 0xFF
        val format = String(data, offset + 1, 3, Charsets.ISO_8859_1).trim()
        if (format == "-->") return null

        val pictureType = data[offset + 4].toInt() and 0xFF
        val descStart = offset + 5
        val img = extractImageAfterDescription(data, descStart, end, encoding) ?: return null
        return pictureType to img
    }

    private fun extractImageAfterDescription(
        data: ByteArray,
        descStart: Int,
        end: Int,
        encoding: Int,
    ): ByteArray? {
        if (descStart >= end) return null
        var cursor = descStart
        val twoByteNull = encoding == 1 || encoding == 2
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

        if (cursor < end) {
            normalizeImageBytes(data.copyOfRange(cursor, end))?.let { return it }
        }

        // Defensive recovery when a tagger mis-encodes the description null terminator.
        val scanLimit = minOf(end, descStart + 64)
        for (probe in descStart until scanLimit) {
            if (isJpegOrPngAt(data, probe, end)) {
                return data.copyOfRange(probe, end)
            }
        }
        return null
    }

    private fun isJpegOrPngAt(data: ByteArray, pos: Int, end: Int): Boolean {
        val rem = end - pos
        fun u(o: Int): Int = data[pos + o].toInt() and 0xFF
        if (rem >= 3 && u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF) return true
        if (rem >= 8 && u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47 &&
            u(4) == 0x0D && u(5) == 0x0A && u(6) == 0x1A && u(7) == 0x0A
        ) {
            return true
        }
        return false
    }

    internal fun extractMp4(
        source: ByteSource,
        needArtwork: Boolean = true,
        needLyrics: Boolean = true,
    ): ExtractedEmbeddedTags {
        var artwork: ByteArray? = null
        var lyrics: String? = null
        val totalSize = source.size()

        while (true) {
            if (totalSize != null && source.position() >= totalSize) break
            val atom = readNextAtomHeader(source, containerEnd = totalSize) ?: break
            val payloadSize = atom.payloadSize
            if (atom.type == "moov") {
                val moovEnd = source.position() + payloadSize
                val (a, l) = parseMp4Moov(source, moovEnd, needArtwork, needLyrics)
                if (artwork == null) artwork = a
                if (lyrics == null) lyrics = l
                break
            } else {
                if (payloadSize < 0 || !source.skipExact(payloadSize)) break
            }
        }
        return ExtractedEmbeddedTags(artwork = artwork, lyrics = lyrics)
    }

    private data class AtomHeader(
        val type: String,
        val payloadSize: Long,
    )

    private fun readNextAtomHeader(source: ByteSource, containerEnd: Long?): AtomHeader? {
        val atomStart = source.position()
        if (containerEnd != null && atomStart + 8L > containerEnd) return null
        val header = source.readExact(8) ?: return null
        val size32 = bigEndianUInt32(header, 0)
        val type = ascii(header, 4, 4)

        val headerSize: Long
        val totalSize: Long
        when (size32) {
            1L -> {
                if (containerEnd != null && atomStart + 16L > containerEnd) return null
                val ext = source.readExact(8) ?: return null
                totalSize = bigEndianLong(ext, 0)
                headerSize = 16L
            }

            0L -> {
                headerSize = 8L
                val end = containerEnd ?: source.size()
                totalSize = if (end != null) end - atomStart else -1L
            }

            else -> {
                totalSize = size32
                headerSize = 8L
            }
        }

        if (size32 != 0L && totalSize < headerSize) return null
        val payloadSize = if (totalSize < 0L) Long.MAX_VALUE else totalSize - headerSize
        return AtomHeader(type = type, payloadSize = payloadSize)
    }

    private fun parseMp4Moov(
        source: ByteSource,
        moovEnd: Long,
        needArtwork: Boolean,
        needLyrics: Boolean,
    ): Pair<ByteArray?, String?> {
        var artwork: ByteArray? = null
        var lyrics: String? = null

        while (source.position() + 8L <= moovEnd) {
            val atom = readNextAtomHeader(source, containerEnd = moovEnd) ?: break
            val nextPos = (source.position() + atom.payloadSize).coerceAtMost(moovEnd)
            when (atom.type) {
                "udta" -> {
                    val (a, l) = parseMp4Udta(source, nextPos, needArtwork && artwork == null, needLyrics && lyrics == null)
                    if (artwork == null) artwork = a
                    if (lyrics == null) lyrics = l
                }

                "meta" -> {
                    if (atom.payloadSize in 8..MAX_TAG_BYTES) {
                        val body = source.readExact(atom.payloadSize.toInt())
                        if (body != null) {
                            val (a, l) = parseMp4Meta(body, needArtwork && artwork == null, needLyrics && lyrics == null)
                            if (artwork == null) artwork = a
                            if (lyrics == null) lyrics = l
                        }
                    }
                }
            }
            if ((!needArtwork || artwork != null) && (!needLyrics || lyrics != null)) break
            if (!source.seek(nextPos)) break
        }
        return artwork to lyrics
    }

    private fun parseMp4Udta(
        source: ByteSource,
        udtaEnd: Long,
        needArtwork: Boolean,
        needLyrics: Boolean,
    ): Pair<ByteArray?, String?> {
        var artwork: ByteArray? = null
        var lyrics: String? = null

        while (source.position() + 8L <= udtaEnd) {
            val atom = readNextAtomHeader(source, containerEnd = udtaEnd) ?: break
            val nextPos = (source.position() + atom.payloadSize).coerceAtMost(udtaEnd)
            if (atom.type == "meta" && atom.payloadSize in 8..MAX_TAG_BYTES) {
                val body = source.readExact(atom.payloadSize.toInt())
                if (body != null) {
                    val (a, l) = parseMp4Meta(body, needArtwork && artwork == null, needLyrics && lyrics == null)
                    if (artwork == null) artwork = a
                    if (lyrics == null) lyrics = l
                }
            }
            if ((!needArtwork || artwork != null) && (!needLyrics || lyrics != null)) break
            if (!source.seek(nextPos)) break
        }
        return artwork to lyrics
    }

    private fun parseMp4Meta(
        body: ByteArray,
        needArtwork: Boolean,
        needLyrics: Boolean,
    ): Pair<ByteArray?, String?> {
        var offset = detectMetaStartOffset(body)
        var artwork: ByteArray? = null
        var lyrics: String? = null

        while (offset + 8 <= body.size) {
            val childSize = bigEndianUInt32(body, offset)
            if (childSize < 8L || offset + childSize > body.size) break
            val childType = ascii(body, offset + 4, 4)
            if (childType == "ilst") {
                val (a, l) = parseMp4Ilst(body, offset + 8, offset + childSize.toInt(), needArtwork, needLyrics)
                if (artwork == null) artwork = a
                if (lyrics == null) lyrics = l
                if ((!needArtwork || artwork != null) && (!needLyrics || lyrics != null)) break
            }
            offset += childSize.toInt()
        }
        return artwork to lyrics
    }

    private fun detectMetaStartOffset(body: ByteArray): Int {
        // ISO BMFF FullBox meta: 4-byte version/flags followed by child atom [size(4)][type(4)].
        if (body.size >= 12) {
            val fullBoxChildSize = bigEndianUInt32(body, 4)
            val fullBoxChildType = ascii(body, 8, 4)
            if (fullBoxChildType in META_CHILD_ATOMS && fullBoxChildSize in 8L..(body.size - 4L)) {
                return 4
            }
        }
        // QuickTime meta variant: child atom starts immediately at offset 0.
        if (body.size >= 8) {
            val directChildSize = bigEndianUInt32(body, 0)
            val directChildType = ascii(body, 4, 4)
            if (directChildType in META_CHILD_ATOMS && directChildSize in 8L..body.size.toLong()) {
                return 0
            }
        }
        if (body.size >= 4 && body[0] == 0.toByte() && body[1] == 0.toByte() && body[2] == 0.toByte() && body[3] == 0.toByte()) {
            return 4
        }
        return 0
    }

    private fun parseMp4Ilst(
        body: ByteArray,
        start: Int,
        end: Int,
        needArtwork: Boolean,
        needLyrics: Boolean,
    ): Pair<ByteArray?, String?> {
        var offset = start
        var artwork: ByteArray? = null
        var lyrics: String? = null

        while (offset + 8 <= end) {
            val itemSize = bigEndianUInt32(body, offset)
            if (itemSize < 8L || offset + itemSize > end) break
            val itemType = ascii(body, offset + 4, 4)
            val itemEnd = offset + itemSize.toInt()

            if (needArtwork && artwork == null && itemType == "covr") {
                var child = offset + 8
                while (child + 8 <= itemEnd) {
                    val dataSize = bigEndianUInt32(body, child)
                    if (dataSize < 8L || child + dataSize > itemEnd) break
                    val dataTypeName = ascii(body, child + 4, 4)
                    if (dataTypeName == "data" && dataSize > 16L) {
                        val payloadStart = child + 16
                        val payloadEnd = child + dataSize.toInt()
                        val raw = body.copyOfRange(payloadStart, payloadEnd)
                        val valid = normalizeImageBytes(raw)
                        if (valid != null) {
                            artwork = valid
                            break
                        }
                    }
                    child += dataSize.toInt()
                }
            } else if (needLyrics && lyrics == null && isMp4LyricsAtom(body, offset + 4)) {
                var child = offset + 8
                while (child + 8 <= itemEnd) {
                    val dataSize = bigEndianUInt32(body, child)
                    if (dataSize < 8L || child + dataSize > itemEnd) break
                    val dataTypeName = ascii(body, child + 4, 4)
                    if (dataTypeName == "data" && dataSize > 16L) {
                        val indicator = bigEndianInt(body, child + 8)
                        if (indicator == 1 || indicator == 0) {
                            val text = String(
                                body,
                                child + 16,
                                dataSize.toInt() - 16,
                                Charsets.UTF_8,
                            ).trim()
                            if (text.isNotEmpty()) {
                                lyrics = text
                                break
                            }
                        }
                    }
                    child += dataSize.toInt()
                }
            }

            if ((!needArtwork || artwork != null) && (!needLyrics || lyrics != null)) break
            offset = itemEnd
        }
        return artwork to lyrics
    }

    private fun isMp4LyricsAtom(body: ByteArray, offset: Int): Boolean {
        return body[offset] == 0xA9.toByte() &&
            body[offset + 1] == 'l'.code.toByte() &&
            body[offset + 2] == 'y'.code.toByte() &&
            body[offset + 3] == 'r'.code.toByte()
    }

    internal fun extractOgg(
        input: InputStream,
        needArtwork: Boolean = true,
        needLyrics: Boolean = true,
    ): ExtractedEmbeddedTags {
        var targetSerial: Int? = null
        val packetBuf = ByteArrayOutputStream()
        var completedPackets = 0

        while (true) {
            val header = readExactStream(input, 27) ?: break
            if (ascii(header, 0, 4) != "OggS") break
            val serial = littleEndianInt(header, 14)
            val numSegments = header[26].toInt() and 0xFF
            val lacing = readExactStream(input, numSegments) ?: break

            var payloadLen = 0
            for (b in lacing) {
                payloadLen += b.toInt() and 0xFF
            }
            if (payloadLen < 0 || payloadLen > MAX_TAG_BYTES) break

            if (targetSerial == null) {
                targetSerial = serial
            }
            if (serial != targetSerial) {
                if (!skipExactStream(input, payloadLen.toLong())) break
                continue
            }

            val pagePayload = readExactStream(input, payloadLen) ?: break
            var cursor = 0
            for (segByte in lacing) {
                val segLen = segByte.toInt() and 0xFF
                if (packetBuf.size() + segLen <= MAX_TAG_BYTES) {
                    packetBuf.write(pagePayload, cursor, segLen)
                }
                cursor += segLen
                if (segLen < 255) {
                    val packet = packetBuf.toByteArray()
                    packetBuf.reset()
                    completedPackets++

                    val parsed = parseOggCommentPacketIfPresent(packet, needArtwork, needLyrics)
                    if (parsed != null) return parsed
                    if (completedPackets >= 3) return ExtractedEmbeddedTags()
                }
            }
        }
        return ExtractedEmbeddedTags()
    }

    private fun parseOggCommentPacketIfPresent(
        packet: ByteArray,
        needArtwork: Boolean,
        needLyrics: Boolean,
    ): ExtractedEmbeddedTags? {
        val commentOffset = when {
            packet.size >= 7 && packet[0] == 0x03.toByte() && ascii(packet, 1, 6) == "vorbis" -> 7
            packet.size >= 8 && ascii(packet, 0, 8) == "OpusTags" -> 8
            else -> return null
        }

        var cursor = commentOffset
        fun readIntLE(): Int? {
            if (cursor + 4 > packet.size) return null
            val v = littleEndianInt(packet, cursor)
            cursor += 4
            return v
        }

        val vendorLen = readIntLE() ?: return ExtractedEmbeddedTags()
        if (vendorLen < 0 || cursor + vendorLen > packet.size) return ExtractedEmbeddedTags()
        cursor += vendorLen

        val count = readIntLE() ?: return ExtractedEmbeddedTags()
        if (count <= 0) return ExtractedEmbeddedTags()

        var bestBlockPicture: ByteArray? = null
        var bestBlockPicType = -1
        var fallbackCoverArt: ByteArray? = null
        var lyrics: String? = null

        for (i in 0 until count) {
            val len = readIntLE() ?: break
            if (len < 0 || cursor + len > packet.size) break
            val eqIndex = indexOfByte(packet, cursor, cursor + len, '='.code.toByte())
            if (eqIndex > cursor) {
                val key = String(packet, cursor, eqIndex - cursor, Charsets.UTF_8).uppercase()
                val valueOffset = eqIndex + 1
                val valueLen = (cursor + len) - valueOffset
                when (key) {
                    "METADATA_BLOCK_PICTURE" -> if (needArtwork && valueLen > 0) {
                        val b64 = String(packet, valueOffset, valueLen, Charsets.ISO_8859_1).trim()
                        val rawBlock = runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull()
                        val pic = rawBlock?.let { FlacTags.parsePicture(it) }
                        if (pic != null) {
                            val (kind, rawImg) = pic
                            val valid = normalizeImageBytes(rawImg)
                            if (valid != null &&
                                (bestBlockPicture == null ||
                                    (bestBlockPicType != PICTURE_TYPE_FRONT_COVER && kind == PICTURE_TYPE_FRONT_COVER))
                            ) {
                                bestBlockPicture = valid
                                bestBlockPicType = kind
                            }
                        }
                    }

                    "COVERART" -> if (needArtwork && fallbackCoverArt == null && valueLen > 0) {
                        val b64 = String(packet, valueOffset, valueLen, Charsets.ISO_8859_1).trim()
                        val rawImg = runCatching { Base64.getMimeDecoder().decode(b64) }.getOrNull()
                        if (rawImg != null) {
                            fallbackCoverArt = normalizeImageBytes(rawImg)
                        }
                    }

                    "LYRICS", "UNSYNCEDLYRICS" -> if (needLyrics && lyrics == null && valueLen > 0) {
                        lyrics = String(packet, valueOffset, valueLen, Charsets.UTF_8).trim().ifEmpty { null }
                    }
                }
            }
            cursor += len
        }

        return ExtractedEmbeddedTags(
            artwork = bestBlockPicture ?: fallbackCoverArt,
            lyrics = lyrics,
        )
    }

    internal fun extractRiffWav(
        source: ByteSource,
        needArtwork: Boolean = true,
        needLyrics: Boolean = true,
    ): ExtractedEmbeddedTags {
        val header = source.readExact(12) ?: return ExtractedEmbeddedTags()
        if (ascii(header, 0, 4) != "RIFF" || ascii(header, 8, 4) != "WAVE") {
            return ExtractedEmbeddedTags()
        }
        return extractChunkedId3(source, bigEndianSizes = false, needArtwork = needArtwork, needLyrics = needLyrics)
    }

    internal fun extractAiff(
        source: ByteSource,
        needArtwork: Boolean = true,
        needLyrics: Boolean = true,
    ): ExtractedEmbeddedTags {
        val header = source.readExact(12) ?: return ExtractedEmbeddedTags()
        val form = ascii(header, 8, 4)
        if (ascii(header, 0, 4) != "FORM" || (form != "AIFF" && form != "AIFC")) {
            return ExtractedEmbeddedTags()
        }
        return extractChunkedId3(source, bigEndianSizes = true, needArtwork = needArtwork, needLyrics = needLyrics)
    }

    private fun extractChunkedId3(
        source: ByteSource,
        bigEndianSizes: Boolean,
        needArtwork: Boolean,
        needLyrics: Boolean,
    ): ExtractedEmbeddedTags {
        var artwork: ByteArray? = null
        var lyrics: String? = null
        val totalSize = source.size()

        while (true) {
            if (totalSize != null && source.position() + 8L > totalSize) break
            val chunkHeader = source.readExact(8) ?: break
            val id = ascii(chunkHeader, 0, 4)
            val chunkSize = if (bigEndianSizes) {
                bigEndianUInt32(chunkHeader, 4)
            } else {
                littleEndianUInt32(chunkHeader, 4)
            }
            val pad = chunkSize and 1L

            if (id.equals("id3 ", ignoreCase = true) && chunkSize in 10L..MAX_TAG_BYTES.toLong()) {
                val id3Bytes = source.readExact(chunkSize.toInt()) ?: break
                val tags = extractId3v2(
                    ByteArrayInputStream(id3Bytes),
                    needArtwork = needArtwork && artwork == null,
                    needLyrics = needLyrics && lyrics == null,
                )
                if (artwork == null) artwork = tags.artwork
                if (lyrics == null) lyrics = tags.lyrics
                if ((!needArtwork || artwork != null) && (!needLyrics || lyrics != null)) break
                if (pad == 1L && !source.skipExact(1L)) break
            } else {
                if (chunkSize < 0L || !source.skipExact(chunkSize + pad)) break
            }
        }
        return ExtractedEmbeddedTags(artwork = artwork, lyrics = lyrics)
    }

    private fun decodeUsltFrame(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 5) return null
        val encodingByte = data[offset].toInt() and 0xFF
        var cursor = offset + 4 // skip encoding + 3-byte ISO-639-2 language
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
            .trim()
        return text.ifEmpty { null }
    }

    private fun removeUnsync(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        val out = ByteArrayOutputStream(length)
        var i = offset
        val end = offset + length
        while (i < end) {
            val b = data[i]
            out.write(b.toInt() and 0xFF)
            if (b == 0xFF.toByte() && i + 1 < end && data[i + 1] == 0x00.toByte()) {
                i += 2
            } else {
                i += 1
            }
        }
        return out.toByteArray()
    }

    private fun ascii(bytes: ByteArray, offset: Int, count: Int): String {
        if (offset < 0 || offset + count > bytes.size) return ""
        return String(bytes, offset, count, Charsets.ISO_8859_1)
    }

    private fun firstClean(vararg values: String?): String? {
        return values.firstNotNullOfOrNull { it?.trim()?.ifEmpty { null } }
    }

    private fun indexOfByte(bytes: ByteArray, start: Int, end: Int, target: Byte): Int {
        for (i in start until end) {
            if (bytes[i] == target) return i
        }
        return -1
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

    private fun bigEndianUInt32(b: ByteArray, offset: Int): Long {
        return ((b[offset].toLong() and 0xFFL) shl 24) or
            ((b[offset + 1].toLong() and 0xFFL) shl 16) or
            ((b[offset + 2].toLong() and 0xFFL) shl 8) or
            (b[offset + 3].toLong() and 0xFFL)
    }

    private fun bigEndianLong(b: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (b[offset + i].toLong() and 0xFFL)
        }
        return v
    }

    private fun bigEndian24(b: ByteArray, offset: Int): Int {
        return ((b[offset].toInt() and 0xFF) shl 16) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            (b[offset + 2].toInt() and 0xFF)
    }

    private fun littleEndianInt(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun littleEndianUInt32(b: ByteArray, offset: Int): Long {
        return (b[offset].toLong() and 0xFFL) or
            ((b[offset + 1].toLong() and 0xFFL) shl 8) or
            ((b[offset + 2].toLong() and 0xFFL) shl 16) or
            ((b[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun readExactStream(input: InputStream, count: Int): ByteArray? {
        if (count == 0) return ByteArray(0)
        if (count < 0) return null
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = runCatching { input.read(buf, offset, count - offset) }.getOrElse { return null }
            if (read < 0) return null
            offset += read
        }
        return buf
    }

    private fun skipExactStream(input: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0L) {
            val skipped = runCatching { input.skip(remaining) }.getOrElse { return false }
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val b = runCatching { input.read() }.getOrElse { return false }
                if (b < 0) return false
                remaining--
            }
        }
        return true
    }
}
