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
}
