package com.musico.core

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Tags and cover art read straight from a FLAC file's metadata blocks.
 */
data class FlacMetadata(
    val comments: Map<String, String> = emptyMap(),
    val picture: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FlacMetadata) return false
        if (comments != other.comments) return false
        if (picture != null) {
            if (other.picture == null) return false
            if (!picture.contentEquals(other.picture)) return false
        } else if (other.picture != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = comments.hashCode()
        result = 31 * result + (picture?.contentHashCode() ?: 0)
        return result
    }
}

object FlacTags {
    private const val BLOCK_VORBIS_COMMENT = 4
    private const val BLOCK_PICTURE = 6
    private const val PICTURE_TYPE_FRONT_COVER = 3
    private const val MAX_BLOCK_SIZE = 32 * 1024 * 1024

    fun read(file: File): FlacMetadata? {
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            FileInputStream(file).use { fis ->
                read(BufferedInputStream(fis))
            }
        }.getOrNull()
    }

    fun read(input: InputStream): FlacMetadata? {
        var magic = readExact(input, 4) ?: return null

        // Skip optional ID3v2 tag placed before the FLAC stream.
        if (magic[0] == 0x49.toByte() && magic[1] == 0x44.toByte() && magic[2] == 0x33.toByte()) {
            val rest = readExact(input, 6) ?: return null
            val size = ((rest[2].toInt() and 0x7F) shl 21) or
                ((rest[3].toInt() and 0x7F) shl 14) or
                ((rest[4].toInt() and 0x7F) shl 7) or
                (rest[5].toInt() and 0x7F)
            if (!skipExact(input, size.toLong())) return null
            magic = readExact(input, 4) ?: return null
        }

        // Expect "fLaC"
        if (magic[0] != 0x66.toByte() ||
            magic[1] != 0x4C.toByte() ||
            magic[2] != 0x61.toByte() ||
            magic[3] != 0x43.toByte()
        ) {
            return null
        }

        val comments = LinkedHashMap<String, String>()
        var picture: ByteArray? = null
        var hasFrontCover = false

        while (true) {
            val header = readExact(input, 4) ?: break
            val b0 = header[0].toInt() and 0xFF
            val isLast = (b0 and 0x80) != 0
            val type = b0 and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            if (length < 0 || length > MAX_BLOCK_SIZE) break

            if (type == BLOCK_VORBIS_COMMENT || type == BLOCK_PICTURE) {
                val body = readExact(input, length) ?: break
                if (type == BLOCK_VORBIS_COMMENT) {
                    parseComments(body, comments)
                } else if (!hasFrontCover) {
                    val parsed = parsePicture(body)
                    if (parsed != null) {
                        val (kind, data) = parsed
                        if (picture == null || kind == PICTURE_TYPE_FRONT_COVER) {
                            picture = data
                        }
                        if (kind == PICTURE_TYPE_FRONT_COVER) {
                            hasFrontCover = true
                        }
                    }
                }
            } else {
                if (!skipExact(input, length.toLong())) break
            }

            if (isLast) break
        }

        return FlacMetadata(comments = comments, picture = picture)
    }

    private fun parseComments(body: ByteArray, out: MutableMap<String, String>) {
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

        val vendorLen = readIntLE() ?: return
        if (vendorLen < 0 || cursor + vendorLen > body.size) return
        cursor += vendorLen

        val count = readIntLE() ?: return
        if (count <= 0) return

        for (i in 0 until count) {
            val len = readIntLE() ?: return
            if (len < 0 || cursor + len > body.size) return
            val entry = String(body, cursor, len, Charsets.UTF_8)
            cursor += len
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val key = entry.substring(0, eq).uppercase()
            val value = entry.substring(eq + 1)
            if (!out.containsKey(key) && value.isNotEmpty()) {
                out[key] = value
            }
        }
    }

    private fun parsePicture(body: ByteArray): Pair<Int, ByteArray>? {
        var cursor = 0
        fun readIntBE(): Int? {
            if (cursor + 4 > body.size) return null
            val v = ((body[cursor].toInt() and 0xFF) shl 24) or
                ((body[cursor + 1].toInt() and 0xFF) shl 16) or
                ((body[cursor + 2].toInt() and 0xFF) shl 8) or
                (body[cursor + 3].toInt() and 0xFF)
            cursor += 4
            return v
        }

        val kind = readIntBE() ?: return null
        val mimeLen = readIntBE() ?: return null
        if (mimeLen < 0 || cursor + mimeLen > body.size) return null
        cursor += mimeLen

        val descLen = readIntBE() ?: return null
        if (descLen < 0 || cursor + descLen + 16 > body.size) return null
        cursor += descLen + 16 // width, height, colour depth, colour count

        val dataLen = readIntBE() ?: return null
        if (dataLen <= 0 || cursor + dataLen > body.size) return null
        val data = body.copyOfRange(cursor, cursor + dataLen)
        return kind to data
    }

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        if (count == 0) return ByteArray(0)
        if (count < 0) return null
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
