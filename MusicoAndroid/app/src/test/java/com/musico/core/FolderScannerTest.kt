package com.musico.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.Normalizer

class FolderScannerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun touch(root: File, relativePath: String) {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeText("x", Charsets.UTF_8)
    }

    @Test
    fun findsNestedAudioOnly() {
        val root = tempFolder.newFolder("root")
        listOf(
            "a.mp3",
            "sub/b.FLAC",
            "sub/deep/c.m4a",
            "sub/d.ogg",
            "sub/e.opus",
            "notes.txt",
            ".hidden.mp3",
            ".hiddenDir/secret.mp3",
            "sub/cover.jpg",
        ).forEach { touch(root, it) }

        val found = FolderScanner.scan(root)
        assertNotNull(found)
        assertEquals(
            listOf("a.mp3", "sub/b.FLAC", "sub/d.ogg", "sub/deep/c.m4a", "sub/e.opus"),
            found!!.map { it.relativePath },
        )
        assertEquals(1L, found.first().fileSize)
    }

    @Test
    fun unicodeAndSpecialNamesRoundTrip() {
        val root = tempFolder.newFolder("unicode")
        val path = "日本語/曲 [live] #1 100%.mp3"
        touch(root, path)
        val found = FolderScanner.scan(root)
        assertNotNull(found)
        assertEquals(listOf(path), found!!.map { it.relativePath })
        val resolved = FolderScanner.fileFor(root, found[0].relativePath)
        assertTrue(resolved.exists())
    }

    @Test
    fun decomposedUnicodeNameIsNormalizedToNfc() {
        val root = tempFolder.newFolder("nfd")
        val decomposed = "Cafe\u0301.mp3"
        touch(root, decomposed)
        val found = FolderScanner.scan(root)!!
        val expected = Normalizer.normalize("Café.mp3", Normalizer.Form.NFC)
        assertEquals(listOf(expected), found.map { it.relativePath })
    }

    @Test
    fun emptyAndMissingRoot() {
        val empty = tempFolder.newFolder("empty")
        assertEquals(emptyList<ScannedFile>(), FolderScanner.scan(empty))

        val missing = File(tempFolder.root, "missing")
        assertNull(FolderScanner.scan(missing))
    }
}
