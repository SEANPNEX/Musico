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
import java.util.Base64

class EmbeddedArtworkExtractorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sampleJpeg(size: Int = 32): ByteArray {
        val b = ByteArray(size.coerceAtLeast(16)) { 0x42 }
        b[0] = 0xFF.toByte()
        b[1] = 0xD8.toByte()
        b[2] = 0xFF.toByte()
        b[3] = 0xE0.toByte()
        return b
    }

    private fun samplePng(size: Int = 32): ByteArray {
        val header = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        val out = ByteArray(size.coerceAtLeast(header.size)) { 0x11 }
        System.arraycopy(header, 0, out, 0, header.size)
        return out
    }

    private fun intBE(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun intLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    private fun synchsafe(v: Int): ByteArray = byteArrayOf(
        ((v ushr 21) and 0x7F).toByte(),
        ((v ushr 14) and 0x7F).toByte(),
        ((v ushr 7) and 0x7F).toByte(),
        (v and 0x7F).toByte(),
    )

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(intBE(8 + payload.size))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun id3v2Tag(
        majorVersion: Int,
        flags: Int = 0,
        framesBytes: ByteArray,
        addFooter: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(majorVersion)
        out.write(0)
        out.write(flags)
        out.write(synchsafe(framesBytes.size))
        out.write(framesBytes)
        if (addFooter) {
            out.write("3DI".toByteArray(Charsets.ISO_8859_1))
            out.write(majorVersion)
            out.write(0)
            out.write(flags)
            out.write(synchsafe(framesBytes.size))
        }
        return out.toByteArray()
    }

    private fun id3v23Frame(id: String, payload: ByteArray, flags: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(intBE(payload.size))
        out.write((flags ushr 8) and 0xFF)
        out.write(flags and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    private fun id3v24Frame(
        id: String,
        payload: ByteArray,
        flags: Int = 0,
        synchsafeSize: Boolean = true,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(if (synchsafeSize) synchsafe(payload.size) else intBE(payload.size))
        out.write((flags ushr 8) and 0xFF)
        out.write(flags and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    private fun apicPayload(
        encoding: Int = 3,
        mime: String = "image/jpeg",
        picType: Int = 3,
        description: ByteArray = byteArrayOf(0),
        image: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encoding)
        out.write(mime.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(picType)
        out.write(description)
        out.write(image)
        return out.toByteArray()
    }

    private fun usltPayload(lyrics: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(3) // UTF-8
        out.write("eng".toByteArray(Charsets.ISO_8859_1))
        out.write(0) // empty descriptor
        out.write(lyrics.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private fun applyUnsync(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            out.write(b)
            if (b == 0xFF && i + 1 < data.size) {
                val next = data[i + 1].toInt() and 0xFF
                if (next == 0x00 || (next and 0xE0) == 0xE0) {
                    out.write(0x00)
                }
            }
            i++
        }
        return out.toByteArray()
    }

    @Test
    fun normalizeImageBytesSupportsCommonFormatsAndRejectsInvalid() {
        val jpeg = sampleJpeg()
        val png = samplePng()
        val webp = "RIFF1234WEBPvp8x".toByteArray(Charsets.ISO_8859_1)
        val gif87 = "GIF87aabcdef".toByteArray(Charsets.ISO_8859_1)
        val gif89 = "GIF89aabcdef".toByteArray(Charsets.ISO_8859_1)
        val bmp = ByteArray(26).also { it[0] = 'B'.code.toByte(); it[1] = 'M'.code.toByte() }
        val tiffLe = byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0x00, 1, 2)
        val tiffBe = byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A, 1, 2)
        val heic = byteArrayOf(0, 0, 0, 24) + "ftypheic0000".toByteArray(Charsets.ISO_8859_1)
        val avif = byteArrayOf(0, 0, 0, 24) + "ftypavif0000".toByteArray(Charsets.ISO_8859_1)

        assertArrayEquals(jpeg, EmbeddedArtworkExtractor.normalizeImageBytes(jpeg))
        assertArrayEquals(png, EmbeddedArtworkExtractor.normalizeImageBytes(png))
        assertArrayEquals(webp, EmbeddedArtworkExtractor.normalizeImageBytes(webp))
        assertArrayEquals(gif87, EmbeddedArtworkExtractor.normalizeImageBytes(gif87))
        assertArrayEquals(gif89, EmbeddedArtworkExtractor.normalizeImageBytes(gif89))
        assertArrayEquals(bmp, EmbeddedArtworkExtractor.normalizeImageBytes(bmp))
        assertArrayEquals(tiffLe, EmbeddedArtworkExtractor.normalizeImageBytes(tiffLe))
        assertArrayEquals(tiffBe, EmbeddedArtworkExtractor.normalizeImageBytes(tiffBe))
        assertArrayEquals(heic, EmbeddedArtworkExtractor.normalizeImageBytes(heic))
        assertArrayEquals(avif, EmbeddedArtworkExtractor.normalizeImageBytes(avif))

        val padded = byteArrayOf(0, 0, 0, 0) + png
        assertArrayEquals(png, EmbeddedArtworkExtractor.normalizeImageBytes(padded))

        assertNull(EmbeddedArtworkExtractor.normalizeImageBytes(ByteArray(0)))
        assertNull(EmbeddedArtworkExtractor.normalizeImageBytes("-->".toByteArray(Charsets.ISO_8859_1)))
        assertNull(EmbeddedArtworkExtractor.normalizeImageBytes(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(EmbeddedArtworkExtractor.normalizeImageBytes(ByteArray(10)))
    }

    @Test
    fun id3v23PrefersFrontCoverSkipsExternalUrlAndExtractsLyrics() {
        val backCover = samplePng(24)
        val frontCover = sampleJpeg(40)
        val utf16Desc = byteArrayOf(
            0xFF.toByte(), 0xFE.toByte(),
            'C'.code.toByte(), 0, 'o'.code.toByte(), 0, 'v'.code.toByte(), 0,
            0, 0,
        )

        val frames = ByteArrayOutputStream().apply {
            write(id3v23Frame("APIC", apicPayload(encoding = 3, mime = "-->", picType = 3, image = "https://x".toByteArray())))
            write(id3v23Frame("APIC", apicPayload(encoding = 1, mime = "image/png", picType = 0, description = utf16Desc, image = backCover)))
            write(id3v23Frame("USLT", usltPayload("[00:01.00]Hello ID3")))
            write(id3v23Frame("APIC", apicPayload(encoding = 3, mime = "image/jpeg", picType = 3, image = frontCover)))
        }.toByteArray()

        val tags = EmbeddedArtworkExtractor.extract(id3v2Tag(3, 0, frames))
        assertArrayEquals(frontCover, tags.artwork)
        assertEquals("[00:01.00]Hello ID3", tags.lyrics)
    }

    @Test
    fun id3v23TagLevelUnsynchronisationDecodesJpegCleanly() {
        val jpeg = sampleJpeg(32)
        val frame = id3v23Frame("APIC", apicPayload(image = jpeg))
        val unsyncBody = applyUnsync(frame)
        val tag = id3v2Tag(majorVersion = 3, flags = 0x80, framesBytes = unsyncBody)

        val extracted = EmbeddedArtworkExtractor.extractId3v2(ByteArrayInputStream(tag))
        assertArrayEquals(jpeg, extracted.artwork)
    }

    @Test
    fun id3v24NonSynchsafeFrameSizesAndFrameUnsyncWork() {
        val largeFrontCover = sampleJpeg(300) // 300 > 128, encodes cleanly without high bits too (0x0000012C)
        val apic = apicPayload(image = largeFrontCover)
        val unsyncedApic = applyUnsync(intBE(apic.size) + apic)
        val uslt = usltPayload("Line one")

        // Case 1: buggy non-synchsafe frame size (300 written as BE 0x0000012C) followed by USLT frame
        val badSizeFrames = ByteArrayOutputStream().apply {
            write(id3v24Frame("APIC", apic, flags = 0, synchsafeSize = false))
            write(id3v24Frame("USLT", uslt, flags = 0, synchsafeSize = true))
        }.toByteArray()
        val parsedBadSize = EmbeddedArtworkExtractor.extract(id3v2Tag(4, 0, badSizeFrames))
        assertArrayEquals(largeFrontCover, parsedBadSize.artwork)
        assertEquals("Line one", parsedBadSize.lyrics)

        // Case 2: per-frame unsynchronisation (0x0002) + Data Length Indicator (0x0001)
        val unsyncFrame = id3v24Frame("APIC", unsyncedApic, flags = 0x0003, synchsafeSize = true)
        val parsedUnsync = EmbeddedArtworkExtractor.extract(id3v2Tag(4, 0, unsyncFrame))
        assertArrayEquals(largeFrontCover, parsedUnsync.artwork)
    }

    @Test
    fun id3v22PicFrameExtractsFrontCover() {
        val png = samplePng(28)
        val picPayload = ByteArrayOutputStream().apply {
            write(0) // ISO-8859-1
            write("PNG".toByteArray(Charsets.ISO_8859_1))
            write(3) // Front cover
            write(0) // empty description
            write(png)
        }.toByteArray()
        val frame = ByteArrayOutputStream().apply {
            write("PIC".toByteArray(Charsets.ISO_8859_1))
            write((picPayload.size ushr 16) and 0xFF)
            write((picPayload.size ushr 8) and 0xFF)
            write(picPayload.size and 0xFF)
            write(picPayload)
        }.toByteArray()

        val extracted = EmbeddedArtworkExtractor.extract(id3v2Tag(2, 0, frame))
        assertArrayEquals(png, extracted.artwork)
    }

    @Test
    fun mp4ExtractsCovrAndLyricsAfterLargeMdatForBothFullBoxAndQuickTimeMeta() {
        val jpeg = sampleJpeg(48)
        val png = samplePng(48)

        fun buildMp4File(quickTimeMeta: Boolean, coverType: Int, coverBytes: ByteArray): ByteArray {
            val dataCovr = box("data", intBE(coverType) + ByteArray(4) + coverBytes)
            val covr = box("covr", dataCovr)
            val lyrAtom = ByteArrayOutputStream().apply {
                val dataLyr = box("data", intBE(1) + ByteArray(4) + "M4A Lyrics".toByteArray(Charsets.UTF_8))
                write(intBE(8 + dataLyr.size))
                write(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()))
                write(dataLyr)
            }.toByteArray()
            val ilst = box("ilst", covr + lyrAtom)
            val hdlr = box("hdlr", ByteArray(24))
            val metaPayload = if (quickTimeMeta) {
                hdlr + ilst
            } else {
                ByteArray(4) + hdlr + ilst
            }
            val meta = box("meta", metaPayload)
            val moov = if (quickTimeMeta) {
                box("moov", meta)
            } else {
                box("moov", box("udta", meta))
            }
            val ftyp = box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1) + ByteArray(8))
            val mdat = box("mdat", ByteArray(4096) { 0x55 })
            return ftyp + mdat + moov
        }

        // Standard ISO BMFF FullBox meta inside moov -> udta -> meta (via seekable File)
        val stdBytes = buildMp4File(quickTimeMeta = false, coverType = 13, coverBytes = jpeg)
        val tempMp4 = File(tempFolder.root, "sample.m4a").apply { writeBytes(stdBytes) }
        val stdTags = EmbeddedArtworkExtractor.extract(tempMp4)
        assertArrayEquals(jpeg, stdTags.artwork)
        assertEquals("M4A Lyrics", stdTags.lyrics)

        // QuickTime meta variant directly under moov with PNG type 14
        val qtBytes = buildMp4File(quickTimeMeta = true, coverType = 14, coverBytes = png)
        val qtTags = EmbeddedArtworkExtractor.extract(qtBytes)
        assertArrayEquals(png, qtTags.artwork)
        assertEquals("M4A Lyrics", qtTags.lyrics)
    }

    @Test
    fun oggVorbisAndOpusExtractMultiPageMetadataBlockPictureAndCoverArt() {
        val jpeg = sampleJpeg(420)
        val flacPicBlock = ByteArrayOutputStream().apply {
            write(intBE(3)) // front cover
            val mime = "image/jpeg".toByteArray(Charsets.ISO_8859_1)
            write(intBE(mime.size))
            write(mime)
            write(intBE(0)) // description
            write(ByteArray(16))
            write(intBE(jpeg.size))
            write(jpeg)
        }.toByteArray()
        val b64Pic = Base64.getEncoder().encodeToString(flacPicBlock)

        fun buildOggCommentPacket(prefix: ByteArray, comments: List<String>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(prefix)
            val vendor = "MusicoTest".toByteArray(Charsets.UTF_8)
            out.write(intLE(vendor.size))
            out.write(vendor)
            out.write(intLE(comments.size))
            for (c in comments) {
                val cb = c.toByteArray(Charsets.UTF_8)
                out.write(intLE(cb.size))
                out.write(cb)
            }
            return out.toByteArray()
        }

        fun oggPage(serial: Int, pageSeq: Int, headerType: Int, lacing: List<Int>, payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write("OggS".toByteArray(Charsets.ISO_8859_1))
            out.write(0)
            out.write(headerType)
            out.write(ByteArray(8)) // granule
            out.write(intLE(serial))
            out.write(intLE(pageSeq))
            out.write(intLE(0)) // CRC
            out.write(lacing.size)
            for (len in lacing) out.write(len)
            out.write(payload)
            return out.toByteArray()
        }

        // Vorbis BOS page + multi-page comment packet (> 255 bytes -> spans 2 pages)
        val identVorbis = oggPage(serial = 42, pageSeq = 0, headerType = 2, lacing = listOf(30), payload = ByteArray(30))
        val commentVorbis = buildOggCommentPacket(
            byteArrayOf(0x03) + "vorbis".toByteArray(Charsets.ISO_8859_1),
            listOf("METADATA_BLOCK_PICTURE=$b64Pic", "LYRICS=Ogg Lyrics"),
        )
        val split = 255
        val page1 = oggPage(serial = 42, pageSeq = 1, headerType = 0, lacing = listOf(255), payload = commentVorbis.copyOfRange(0, split))
        val rem = commentVorbis.copyOfRange(split, commentVorbis.size)
        val remLacing = buildList {
            var left = rem.size
            while (left >= 255) {
                add(255)
                left -= 255
            }
            add(left)
        }
        val page2 = oggPage(serial = 42, pageSeq = 2, headerType = 1, lacing = remLacing, payload = rem)

        val vorbisTags = EmbeddedArtworkExtractor.extract(identVorbis + page1 + page2)
        assertArrayEquals(jpeg, vorbisTags.artwork)
        assertEquals("Ogg Lyrics", vorbisTags.lyrics)

        // Opus COVERART fallback
        val png = samplePng(48)
        val b64Png = Base64.getEncoder().encodeToString(png)
        val identOpus = oggPage(serial = 7, pageSeq = 0, headerType = 2, lacing = listOf(19), payload = "OpusHead".toByteArray() + ByteArray(11))
        val commentOpus = buildOggCommentPacket(
            "OpusTags".toByteArray(Charsets.ISO_8859_1),
            listOf("COVERART=$b64Png", "UNSYNCEDLYRICS=Opus Lyrics"),
        )
        val opusLacing = buildList {
            var left = commentOpus.size
            while (left >= 255) {
                add(255)
                left -= 255
            }
            add(left)
        }
        val opusPage1 = oggPage(serial = 7, pageSeq = 1, headerType = 0, lacing = opusLacing, payload = commentOpus)
        val opusTags = EmbeddedArtworkExtractor.extract(identOpus + opusPage1)
        assertArrayEquals(png, opusTags.artwork)
        assertEquals("Opus Lyrics", opusTags.lyrics)
    }

    @Test
    fun riffWavAndAiffExtractId3ChunkAfterAudioDataWithOddPadding() {
        val jpeg = sampleJpeg(36)
        val png = samplePng(36)
        val id3Jpeg = id3v2Tag(3, 0, id3v23Frame("APIC", apicPayload(image = jpeg)) + id3v23Frame("USLT", usltPayload("WAV Lyrics")))
        val id3Png = id3v2Tag(3, 0, id3v23Frame("APIC", apicPayload(image = png)))

        // WAV with odd-sized data chunk (5 bytes + 1 pad byte) before "id3 "
        val wavBody = ByteArrayOutputStream().apply {
            write("fmt ".toByteArray(Charsets.ISO_8859_1))
            write(intLE(16))
            write(ByteArray(16))
            write("data".toByteArray(Charsets.ISO_8859_1))
            write(intLE(5))
            write(ByteArray(5))
            write(0) // odd pad byte
            write("id3 ".toByteArray(Charsets.ISO_8859_1))
            write(intLE(id3Jpeg.size))
            write(id3Jpeg)
        }.toByteArray()
        val wav = "RIFF".toByteArray(Charsets.ISO_8859_1) + intLE(4 + wavBody.size) + "WAVE".toByteArray(Charsets.ISO_8859_1) + wavBody
        val wavTags = EmbeddedArtworkExtractor.extract(wav)
        assertArrayEquals(jpeg, wavTags.artwork)
        assertEquals("WAV Lyrics", wavTags.lyrics)

        // AIFF with odd-sized SSND chunk before "ID3 "
        val aiffBody = ByteArrayOutputStream().apply {
            write("SSND".toByteArray(Charsets.ISO_8859_1))
            write(intBE(7))
            write(ByteArray(7))
            write(0) // odd pad byte
            write("ID3 ".toByteArray(Charsets.ISO_8859_1))
            write(intBE(id3Png.size))
            write(id3Png)
        }.toByteArray()
        val aiff = "FORM".toByteArray(Charsets.ISO_8859_1) + intBE(4 + aiffBody.size) + "AIFF".toByteArray(Charsets.ISO_8859_1) + aiffBody
        val aiffTags = EmbeddedArtworkExtractor.extract(aiff)
        assertArrayEquals(png, aiffTags.artwork)
    }

    @Test
    fun id3v2PrependedToFlacFallsThroughToFlacStreamIncludingFooter() {
        val flacArt = samplePng(36)
        val flacOut = ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.ISO_8859_1))
            val picBody = ByteArrayOutputStream().apply {
                write(intBE(3))
                val mime = "image/png".toByteArray(Charsets.ISO_8859_1)
                write(intBE(mime.size))
                write(mime)
                write(intBE(0))
                write(ByteArray(16))
                write(intBE(flacArt.size))
                write(flacArt)
            }.toByteArray()
            write(0x86)
            write((picBody.size ushr 16) and 0xFF)
            write((picBody.size ushr 8) and 0xFF)
            write(picBody.size and 0xFF)
            write(picBody)
        }.toByteArray()

        // ID3v2.4 with footer (flags=0x10) containing only USLT lyrics, followed by FLAC carrying PNG cover
        val id3WithFooter = id3v2Tag(
            majorVersion = 4,
            flags = 0x10,
            framesBytes = id3v24Frame("USLT", usltPayload("ID3+FLAC Lyrics")),
            addFooter = true,
        )
        val combined = id3WithFooter + flacOut
        val tags = EmbeddedArtworkExtractor.extract(combined)
        assertNotNull(tags.artwork)
        assertArrayEquals(flacArt, tags.artwork)
        assertEquals("ID3+FLAC Lyrics", tags.lyrics)
    }
}
