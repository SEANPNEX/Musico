package com.musico.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class FlacTagsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun intLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private fun intBE(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun buildSyntheticFlac(
        comments: List<String>,
        pictureBytes: ByteArray?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))

        val vorbisBody = ByteArrayOutputStream().apply {
            val vendor = "MusicoTest".toByteArray(Charsets.UTF_8)
            write(intLE(vendor.size))
            write(vendor)
            write(intLE(comments.size))
            for (c in comments) {
                val bytes = c.toByteArray(Charsets.UTF_8)
                write(intLE(bytes.size))
                write(bytes)
            }
        }.toByteArray()

        val hasPicture = pictureBytes != null
        val vorbisHeaderFlag = if (hasPicture) 0x04 else 0x84
        out.write(vorbisHeaderFlag)
        out.write((vorbisBody.size ushr 16) and 0xFF)
        out.write((vorbisBody.size ushr 8) and 0xFF)
        out.write(vorbisBody.size and 0xFF)
        out.write(vorbisBody)

        if (pictureBytes != null) {
            val picBody = ByteArrayOutputStream().apply {
                write(intBE(3)) // front cover
                val mime = "image/jpeg".toByteArray(Charsets.ISO_8859_1)
                write(intBE(mime.size))
                write(mime)
                write(intBE(0)) // description length
                write(ByteArray(16)) // width, height, depth, color count
                write(intBE(pictureBytes.size))
                write(pictureBytes)
            }.toByteArray()
            out.write(0x86) // isLast=1, type=6 (PICTURE)
            out.write((picBody.size ushr 16) and 0xFF)
            out.write((picBody.size ushr 8) and 0xFF)
            out.write(picBody.size and 0xFF)
            out.write(picBody)
        }
        return out.toByteArray()
    }

    @Test
    fun readsVorbisCommentsAndFrontCoverPicture() {
        val pic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val flac = buildSyntheticFlac(
            comments = listOf("title=Blue Train", "ARTIST=John Coltrane", "TITLE=Ignored Duplicate"),
            pictureBytes = pic,
        )
        val parsed = FlacTags.read(ByteArrayInputStream(flac))
        assertNotNull(parsed)
        assertEquals("Blue Train", parsed!!.comments["TITLE"])
        assertEquals("John Coltrane", parsed.comments["ARTIST"])
        assertArrayEquals(pic, parsed.picture)
    }

    @Test
    fun nonFlacFileGivesNull() {
        val file = File(tempFolder.root, "notflac.mp3")
        file.writeText("ID3notflac", Charsets.ISO_8859_1)
        assertNull(FlacTags.read(file))
    }

    @Test
    fun truncatedFileDoesNotCrash() {
        val full = buildSyntheticFlac(listOf("TITLE=Song"), byteArrayOf(1, 2, 3, 4))
        for (len in listOf(0, 3, 4, 7, 15, 30)) {
            val prefix = full.copyOf(minOf(len, full.size))
            FlacTags.read(ByteArrayInputStream(prefix))
        }
    }
}
