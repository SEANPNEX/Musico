package com.musico.core

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

data class AudioFileInfo(
    val bitDepth: Int? = null,
    val sampleRateHz: Int? = null,
    val bitRateBps: Long? = null,
    val channelCount: Int? = null,
)

object AudioFileInfoReader {

    fun read(
        context: Context?,
        uri: Uri?,
        fileSize: Long,
        durationSec: Double,
    ): AudioFileInfo {
        if (uri == null) {
            return fallbackBitrateOnly(fileSize, durationSec)
        }

        var bitDepth: Int? = null
        var sampleRateHz: Int? = null
        var bitRateBps: Long? = null
        var channelCount: Int? = null

        val headerInfo = openStream(context, uri)?.use { stream ->
            inspectHeader(BufferedInputStream(stream))
        }
        if (headerInfo != null) {
            bitDepth = headerInfo.bitDepth
            sampleRateHz = headerInfo.sampleRateHz
            bitRateBps = headerInfo.bitRateBps
            channelCount = headerInfo.channelCount
        }

        val extractor = MediaExtractor()
        try {
            if (uri.scheme == "file" && uri.path != null) {
                extractor.setDataSource(uri.path!!)
            } else if (context != null) {
                extractor.setDataSource(context, uri, null)
            }
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                if (sampleRateHz == null && format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRateHz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 }
                }
                if (channelCount == null && format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 }
                }
                if (bitRateBps == null && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    bitRateBps = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong().takeIf { it > 0L }
                }
                if (bitDepth == null && format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    bitDepth = pcmEncodingToBits(format.getInteger(MediaFormat.KEY_PCM_ENCODING))
                }
                break
            }
        } catch (_: Exception) {
            // Best-effort extraction.
        } finally {
            runCatching { extractor.release() }
        }

        if (bitRateBps == null || sampleRateHz == null || bitDepth == null) {
            val retriever = MediaMetadataRetriever()
            try {
                if (uri.scheme == "file" && uri.path != null) {
                    retriever.setDataSource(uri.path)
                } else if (context != null) {
                    retriever.setDataSource(context, uri)
                }
                if (bitRateBps == null) {
                    bitRateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toLongOrNull()
                        ?.takeIf { it > 0L }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (sampleRateHz == null) {
                        sampleRateHz = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                            ?.toIntOrNull()
                            ?.takeIf { it > 0 }
                    }
                    if (bitDepth == null) {
                        bitDepth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                            ?.toIntOrNull()
                            ?.takeIf { it > 0 }
                    }
                }
            } catch (_: Exception) {
                // Best-effort extraction.
            } finally {
                runCatching { retriever.release() }
            }
        }

        if (bitRateBps == null && fileSize > 0L && durationSec > 0.0) {
            bitRateBps = ((fileSize * 8.0) / durationSec).toLong().takeIf { it > 0L }
        }

        return AudioFileInfo(
            bitDepth = bitDepth,
            sampleRateHz = sampleRateHz,
            bitRateBps = bitRateBps,
            channelCount = channelCount,
        )
    }

    private fun fallbackBitrateOnly(fileSize: Long, durationSec: Double): AudioFileInfo {
        val bps = if (fileSize > 0L && durationSec > 0.0) {
            ((fileSize * 8.0) / durationSec).toLong().takeIf { it > 0L }
        } else {
            null
        }
        return AudioFileInfo(bitRateBps = bps)
    }

    private fun openStream(context: Context?, uri: Uri): InputStream? {
        return runCatching {
            if (uri.scheme == "file" && uri.path != null) {
                FileInputStream(File(uri.path!!))
            } else {
                context?.contentResolver?.openInputStream(uri)
            }
        }.getOrNull()
    }

    private fun pcmEncodingToBits(encoding: Int): Int? = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 8
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
        else -> null
    }

    /**
     * Inspects FLAC or WAV headers from [input] to extract lossless bit depth, sample rate, and channels.
     */
    fun inspectHeader(input: InputStream): AudioFileInfo? {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(64)
        val probe = readUpTo(buffered, 12) ?: return null
        buffered.reset()

        if (probe.size >= 12 &&
            probe[0] == 'R'.code.toByte() &&
            probe[1] == 'I'.code.toByte() &&
            probe[2] == 'F'.code.toByte() &&
            probe[3] == 'F'.code.toByte() &&
            probe[8] == 'W'.code.toByte() &&
            probe[9] == 'A'.code.toByte() &&
            probe[10] == 'V'.code.toByte() &&
            probe[11] == 'E'.code.toByte()
        ) {
            return inspectWav(buffered)
        }

        return inspectFlac(buffered)
    }

    private fun inspectFlac(input: InputStream): AudioFileInfo? {
        var magic = readExact(input, 4) ?: return null
        if (magic[0] == 0x49.toByte() && magic[1] == 0x44.toByte() && magic[2] == 0x33.toByte()) {
            val rest = readExact(input, 6) ?: return null
            val size = ((rest[2].toInt() and 0x7F) shl 21) or
                ((rest[3].toInt() and 0x7F) shl 14) or
                ((rest[4].toInt() and 0x7F) shl 7) or
                (rest[5].toInt() and 0x7F)
            if (!skipExact(input, size.toLong())) return null
            magic = readExact(input, 4) ?: return null
        }

        if (magic[0] != 0x66.toByte() ||
            magic[1] != 0x4C.toByte() ||
            magic[2] != 0x61.toByte() ||
            magic[3] != 0x43.toByte()
        ) {
            return null
        }

        val blockHeader = readExact(input, 4) ?: return null
        val type = blockHeader[0].toInt() and 0x7F
        val length = ((blockHeader[1].toInt() and 0xFF) shl 16) or
            ((blockHeader[2].toInt() and 0xFF) shl 8) or
            (blockHeader[3].toInt() and 0xFF)
        if (type != 0 || length < 18) return null

        val streamInfo = readExact(input, 18) ?: return null
        val b10 = streamInfo[10].toInt() and 0xFF
        val b11 = streamInfo[11].toInt() and 0xFF
        val b12 = streamInfo[12].toInt() and 0xFF
        val b13 = streamInfo[13].toInt() and 0xFF

        val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 ushr 4)
        val channels = ((b12 and 0x0E) ushr 1) + 1
        val bitDepth = (((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1

        return AudioFileInfo(
            bitDepth = bitDepth.takeIf { it > 0 },
            sampleRateHz = sampleRate.takeIf { it > 0 },
            bitRateBps = null,
            channelCount = channels.takeIf { it > 0 },
        )
    }

    private fun inspectWav(input: InputStream): AudioFileInfo? {
        // Skip RIFF(4) + chunkSize(4) + WAVE(4)
        if (!skipExact(input, 12)) return null
        while (true) {
            val chunkHeader = readExact(input, 8) ?: return null
            val id = String(chunkHeader, 0, 4, Charsets.ISO_8859_1)
            val size = (chunkHeader[4].toInt() and 0xFF) or
                ((chunkHeader[5].toInt() and 0xFF) shl 8) or
                ((chunkHeader[6].toInt() and 0xFF) shl 16) or
                ((chunkHeader[7].toInt() and 0xFF) shl 24)
            if (size < 0) return null
            if (id == "fmt ") {
                if (size < 16) return null
                val fmt = readExact(input, 16) ?: return null
                val channels = (fmt[2].toInt() and 0xFF) or ((fmt[3].toInt() and 0xFF) shl 8)
                val sampleRate = (fmt[4].toInt() and 0xFF) or
                    ((fmt[5].toInt() and 0xFF) shl 8) or
                    ((fmt[6].toInt() and 0xFF) shl 16) or
                    ((fmt[7].toInt() and 0xFF) shl 24)
                val byteRate = (fmt[8].toLong() and 0xFFL) or
                    ((fmt[9].toLong() and 0xFFL) shl 8) or
                    ((fmt[10].toLong() and 0xFFL) shl 16) or
                    ((fmt[11].toLong() and 0xFFL) shl 24)
                val bitsPerSample = (fmt[14].toInt() and 0xFF) or ((fmt[15].toInt() and 0xFF) shl 8)
                return AudioFileInfo(
                    bitDepth = bitsPerSample.takeIf { it > 0 },
                    sampleRateHz = sampleRate.takeIf { it > 0 },
                    bitRateBps = (byteRate * 8L).takeIf { it > 0L },
                    channelCount = channels.takeIf { it > 0 },
                )
            }
            val paddedSize = size.toLong() + (size and 1)
            if (!skipExact(input, paddedSize)) return null
        }
    }

    private fun readUpTo(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == 0) null else buf.copyOf(offset)
    }

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read < 0) return null
            offset += read
        }
        return buf
    }

    private fun skipExact(input: InputStream, count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = try {
                input.skip(remaining)
            } catch (_: EOFException) {
                return false
            }
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) return false
                remaining--
            }
        }
        return true
    }
}
