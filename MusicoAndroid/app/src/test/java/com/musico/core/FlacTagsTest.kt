package com.musico.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

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

    private fun buildPictureBlock(kind: Int, mime: String, data: ByteArray): ByteArray {
        return ByteArrayOutputStream().apply {
            write(intBE(kind))
            val mimeBytes = mime.toByteArray(Charsets.ISO_8859_1)
            write(intBE(mimeBytes.size))
            write(mimeBytes)
            write(intBE(0))
            write(ByteArray(16))
            write(intBE(data.size))
            write(data)
        }.toByteArray()
    }

    @Test
    fun skipsExternalMimePictureAndPicksSubsequentValidPicture() {
        val validPng = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D,
        )
        val picUrlBlock = buildPictureBlock(3, "-->", "https://example.com/cover.jpg".toByteArray())
        val picValidBlock = buildPictureBlock(3, "image/png", validPng)

        val out = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.ISO_8859_1))
            write(0x06) // type 6, not last
            write((picUrlBlock.size ushr 16) and 0xFF)
            write((picBodySizeByte2(picUrlBlock.size)))
            write(picUrlBlock.size and 0xFF)
            write(picUrlBlock)
            write(0x86) // type 6, last
            write((picValidBlock.size ushr 16) and 0xFF)
            write((picValidBlock.size ushr 8) and 0xFF)
            write(picValidBlock.size and 0xFF)
            write(picValidBlock)
        }.toByteArray()

        val parsed = FlacTags.read(ByteArrayInputStream(out))
        assertNotNull(parsed)
        assertArrayEquals(validPng, parsed!!.picture)
    }

    private fun picBodySizeByte2(size: Int): Int = (size ushr 8) and 0xFF

    @Test
    fun fallsBackToVorbisCommentMetadataBlockPictureAndCoverArt() {
        val backJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1)
        val frontJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 2)
        val b64Back = Base64.getEncoder().encodeToString(buildPictureBlock(4, "image/jpeg", backJpeg))
        val b64Front = Base64.getEncoder().encodeToString(buildPictureBlock(3, "image/jpeg", frontJpeg))

        val flac = buildSyntheticFlac(
            comments = listOf(
                "TITLE=Comment Cover",
                "METADATA_BLOCK_PICTURE=$b64Back",
                "METADATA_BLOCK_PICTURE=$b64Front",
            ),
            pictureBytes = null,
        )
        val parsed = FlacTags.read(ByteArrayInputStream(flac))
        assertNotNull(parsed)
        assertArrayEquals(frontJpeg, parsed!!.picture)
        assertFalse(parsed.comments.containsKey("METADATA_BLOCK_PICTURE"))

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val coverArtFlac = buildSyntheticFlac(
            comments = listOf("COVERART=${Base64.getEncoder().encodeToString(png)}"),
            pictureBytes = null,
        )
        val parsedCoverArt = FlacTags.read(ByteArrayInputStream(coverArtFlac))
        assertNotNull(parsedCoverArt)
        assertArrayEquals(png, parsedCoverArt!!.picture)
        assertFalse(parsedCoverArt.comments.containsKey("COVERART"))
    }

    @Test
    fun skipsPrependedId3v24TagWithFooter() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val flac = buildSyntheticFlac(listOf("TITLE=After ID3"), jpeg)

        val id3Body = ByteArray(16)
        val id3 = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(Charsets.ISO_8859_1))
            write(4)
            write(0)
            write(0x10) // footer present
            write(byteArrayOf(0, 0, 0, id3Body.size.toByte()))
            write(id3Body)
            write("3DI".toByteArray(Charsets.ISO_8859_1))
            write(4)
            write(0)
            write(0x10)
            write(byteArrayOf(0, 0, 0, id3Body.size.toByte()))
        }.toByteArray()

        val parsed = FlacTags.read(ByteArrayInputStream(id3 + flac))
        assertNotNull(parsed)
        assertEquals("After ID3", parsed!!.comments["TITLE"])
        assertArrayEquals(jpeg, parsed.picture)
    }
}
