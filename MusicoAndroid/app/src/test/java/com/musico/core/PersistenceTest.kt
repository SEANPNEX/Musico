package com.musico.core

import com.musico.model.EqPreset
import com.musico.model.EqSettings
import com.musico.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class PersistenceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun makeTrack(path: String, rootId: String = UUID.randomUUID().toString()): Track =
        Track(
            id = Track.makeId(rootId, path),
            rootId = rootId,
            relativePath = path,
            title = path,
            artist = "A",
            album = "B",
            albumArtist = "A",
            durationSec = 10.0,
            fileSize = 1L,
            modifiedEpochMs = 0L,
            hasEmbeddedLyrics = false,
            dateAddedEpochMs = 0L,
        )

    @Test
    fun roundTrip() {
        val file = File(tempFolder.root, "nested/x.json")
        val tracks = listOf(makeTrack("a.mp3"), makeTrack("日本語/b.flac"))
        Persistence.save(tracks, file)
        assertEquals(tracks, Persistence.load<List<Track>>(file))
    }

    @Test
    fun missingFileReturnsNull() {
        val file = File(tempFolder.root, "nope.json")
        assertNull(Persistence.load<List<Track>>(file))
    }

    @Test
    fun corruptFileIsRenamed() {
        val dir = tempFolder.newFolder("corrupt")
        val file = File(dir, "x.json")
        file.writeText("{", Charsets.UTF_8)
        assertNull(Persistence.load<List<Track>>(file))
        assertFalse(file.exists())
        assertTrue(File(dir, "x.json.corrupt").exists())
    }

    @Test
    fun trackIdIsStable() {
        val root = UUID.randomUUID().toString()
        val a = Track.makeId(root, "a/b.mp3")
        assertEquals(a, Track.makeId(root, "a/b.mp3"))
        assertEquals(64, a.length)
        assertNotEquals(a, Track.makeId(root, "a/c.mp3"))
    }

    @Test
    fun eqPresetsMatchSpec() {
        assertEquals(
            listOf("Flat", "Bass Boost", "Treble Boost", "Rock", "Pop", "Jazz", "Classical", "Vocal"),
            EqPreset.ALL.map { it.name },
        )
        assertTrue(EqPreset.ALL.all { it.gains.size == EqSettings.FREQUENCIES.size })
        assertEquals(10, EqSettings.FREQUENCIES.size)
    }
}
