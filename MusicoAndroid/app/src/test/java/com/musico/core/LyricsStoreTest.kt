package com.musico.core

import com.musico.model.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class LyricsStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var musicDir: File
    private lateinit var store: LyricsStore

    @Before
    fun setUp() {
        musicDir = tempFolder.newFolder("music")
        store = LyricsStore(tempFolder.newFolder("lyrics"))
    }

    private fun readSibling(stem: String): ByteArray? {
        for (ext in listOf("lrc", "txt")) {
            val file = File(musicDir, "$stem.$ext")
            if (file.exists()) return file.readBytes()
        }
        return null
    }

    @Test
    fun nothingAvailableReturnsNull() {
        assertNull(store.resolve("t", null, null, null))
        assertNull(store.resolve("t", null, null, "  \n"))
    }

    @Test
    fun embeddedUsedWhenNothingElse() {
        val result = store.resolve("t", null, null, "embedded words")
        assertEquals(LyricsSource.EMBEDDED, result?.second)
        assertEquals(listOf("embedded words"), result?.first?.lines?.map { it.text })
    }

    @Test
    fun sidecarBeatsEmbeddedAndLrcBeatsTxt() {
        File(musicDir, "song.txt").writeText("txt words", Charsets.UTF_8)
        val txtResult = store.resolve("t", null, { readSibling("song") }, "e")
        assertEquals(listOf("txt words"), txtResult?.first?.lines?.map { it.text })

        File(musicDir, "song.lrc").writeText("[00:01.00]lrc words", Charsets.UTF_8)
        val lrcResult = store.resolve("t", null, { readSibling("song") }, "e")
        assertEquals(LyricsSource.SIDECAR, lrcResult?.second)
        assertEquals(listOf("lrc words"), lrcResult?.first?.lines?.map { it.text })
    }

    @Test
    fun importedBeatsSidecarAndRemoveFallsBack() {
        File(musicDir, "song.lrc").writeText("[00:01.00]sidecar", Charsets.UTF_8)
        val picked = File(musicDir, "elsewhere.lrc").apply {
            writeText("[00:02.00]imported", Charsets.UTF_8)
        }
        assertFalse(store.hasImported("t"))
        store.importLyrics(picked, "t")
        assertTrue(store.hasImported("t"))

        val imported = store.resolve("t", null, { readSibling("song") }, null)
        assertEquals(LyricsSource.IMPORTED, imported?.second)
        assertEquals(listOf("imported"), imported?.first?.lines?.map { it.text })
        assertNull(store.resolve("other", null, null, null))

        store.removeImported("t")
        assertFalse(store.hasImported("t"))
        val fallback = store.resolve("t", null, { readSibling("song") }, null)
        assertEquals(LyricsSource.SIDECAR, fallback?.second)
    }

    @Test(expected = LyricsUnreadableException::class)
    fun importRejectsUndecodableFile() {
        val badBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())
        store.importLyrics(ByteArrayInputStream(badBytes), "t")
    }

    @Test
    fun folderMatchNormalizesTrackNumbersNoiseDiacriticsAndPreservesJapaneseKana() {
        val songFile = File(musicDir, "01 - Café Del Mar (Official Audio).mp3").apply { writeBytes(byteArrayOf()) }
        File(musicDir, "Artist - Cafe del Mar [FLAC].lrc").writeText("[00:05.00]Sunrise on the beach", Charsets.UTF_8)

        val resolved = store.resolve(
            trackId = "cafe",
            audioFile = songFile,
            siblingProvider = null,
            embeddedText = "embedded",
            trackTitle = "Café Del Mar",
            trackArtist = "Artist",
        )
        assertEquals(LyricsSource.FOLDER_MATCH, resolved?.second)
        assertEquals(listOf("Sunrise on the beach"), resolved?.first?.lines?.map { it.text })

        val jpSong = File(musicDir, "かがみ.mp3").apply { writeBytes(byteArrayOf()) }
        File(musicDir, "かかみ.lrc").writeText("[00:01.00]wrong kana", Charsets.UTF_8)
        assertNull(
            store.resolve(
                trackId = "jp",
                audioFile = jpSong,
                siblingProvider = null,
                embeddedText = null,
                trackTitle = "かがみ",
                trackArtist = "",
            )
        )
        File(musicDir, "02. かがみ.lrc").writeText("[00:02.00]exact kana", Charsets.UTF_8)
        val jpResolved = store.resolve(
            trackId = "jp",
            audioFile = jpSong,
            siblingProvider = null,
            embeddedText = null,
            trackTitle = "かがみ",
            trackArtist = "",
        )
        assertEquals(LyricsSource.FOLDER_MATCH, jpResolved?.second)
        assertEquals(listOf("exact kana"), jpResolved?.first?.lines?.map { it.text })

        val song1989 = File(musicDir, "1989.mp3").apply { writeBytes(byteArrayOf()) }
        File(musicDir, "01 - 1989.lrc").writeText("[00:03.00]year line", Charsets.UTF_8)
        val yrResolved = store.resolve(
            trackId = "yr",
            audioFile = song1989,
            siblingProvider = null,
            embeddedText = null,
            trackTitle = "1989",
            trackArtist = "Taylor",
        )
        assertEquals(LyricsSource.FOLDER_MATCH, yrResolved?.second)
        assertEquals(listOf("year line"), yrResolved?.first?.lines?.map { it.text })
    }

    @Test
    fun folderMatchSkipsGenericAndSubfolderAndPrefersTimedLrcOverTxt() {
        val songFile = File(musicDir, "Nightcall.mp3").apply { writeBytes(byteArrayOf()) }
        val subDir = File(musicDir, "Subfolder").apply { mkdirs() }
        File(subDir, "Nightcall.lrc").writeText("[00:01.00]subfolder", Charsets.UTF_8)
        File(musicDir, "lyrics.lrc").writeText("[00:01.00]generic", Charsets.UTF_8)
        File(musicDir, "readme.txt").writeText("readme", Charsets.UTF_8)

        val fallbackEmbedded = store.resolve(
            trackId = "nc",
            audioFile = songFile,
            siblingProvider = null,
            embeddedText = "tag line",
            trackTitle = "Nightcall",
            trackArtist = "Kavinsky",
        )
        assertEquals(LyricsSource.EMBEDDED, fallbackEmbedded?.second)

        val genericSong = File(musicDir, "lyrics.mp3").apply { writeBytes(byteArrayOf()) }
        val exactGeneric = store.resolve(
            trackId = "gen",
            audioFile = genericSong,
            siblingProvider = null,
            embeddedText = null,
        )
        assertEquals(LyricsSource.SIDECAR, exactGeneric?.second)

        File(musicDir, "Kavinsky - Nightcall.txt").writeText("plain txt", Charsets.UTF_8)
        File(musicDir, "Kavinsky - Nightcall.lrc").writeText("[00:04.00]timed lrc", Charsets.UTF_8)
        val matched = store.resolve(
            trackId = "nc",
            audioFile = songFile,
            siblingProvider = null,
            embeddedText = "tag line",
            trackTitle = "Nightcall",
            trackArtist = "Kavinsky",
        )
        assertEquals(LyricsSource.FOLDER_MATCH, matched?.second)
        assertEquals(listOf("timed lrc"), matched?.first?.lines?.map { it.text })
    }

    @Test
    fun importTextAndReadRawImportedWorks() {
        val raw = "[00:05.00]Direct text"
        store.importLyrics(raw, "direct-id")
        assertTrue(store.hasImported("direct-id"))
        assertEquals(raw, store.rawImportedText("direct-id"))
    }
}
