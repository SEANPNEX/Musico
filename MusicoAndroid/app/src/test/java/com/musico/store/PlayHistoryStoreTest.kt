package com.musico.store

import com.musico.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class PlayHistoryStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dir: File
    private val root = UUID.randomUUID().toString()
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    @Before
    fun setUp() {
        dir = tempFolder.newFolder("history")
    }

    private fun tracks(vararg names: String): List<Track> = names.map { name ->
        val path = "$name.mp3"
        Track(
            id = Track.makeId(root, path),
            rootId = root,
            relativePath = path,
            title = name,
            artist = "A",
            album = "B",
            albumArtist = "A",
            durationSec = 10.0,
            fileSize = 1L,
            modifiedEpochMs = 0L,
            dateAddedEpochMs = 0L,
        )
    }

    @Test
    fun recordPlayCountsAndRemembersLastTime() {
        val store = PlayHistoryStore(dir)
        store.recordPlay("x", now - day)
        store.recordPlay("x", now)
        assertEquals(2, store.count("x"))
        assertEquals(now, store.lastPlayed("x"))
        assertEquals(0, store.count("y"))
        assertNull(store.lastPlayed("y"))
        assertEquals(2, store.state.value.totalPlays)
    }

    @Test
    fun startedSongIsRecentButHasNoPlays() {
        val all = tracks("a", "b")
        val store = PlayHistoryStore(dir)
        store.recordPlay(all[0].id, now - day)
        store.markStarted(all[1].id, now)

        assertEquals(0, store.count(all[1].id))
        assertEquals(now, store.lastPlayed(all[1].id))
        assertEquals(listOf("b", "a"), store.recentlyPlayed(all).map { it.title })
        assertEquals(listOf("a"), store.mostPlayed(all).map { it.title })
        assertEquals(listOf("a"), store.leastPlayed(all).map { it.title })
        assertEquals(listOf("b"), store.neverPlayed(all).map { it.title })
        assertEquals(1, store.state.value.totalPlays)

        store.markStarted(all[0].id, now + 60_000L)
        assertEquals(1, store.count(all[0].id))
        assertEquals(now + 60_000L, PlayHistoryStore(dir).lastPlayed(all[0].id))
    }

    @Test
    fun historyAndListeningTimeSurviveReload() {
        val store = PlayHistoryStore(dir)
        store.recordPlay("x", now)
        store.addListening(90.0)
        store.save()

        val reloaded = PlayHistoryStore(dir)
        assertEquals(1, reloaded.count("x"))
        assertEquals(1, reloaded.state.value.totalPlays)
        assertEquals(90.0, reloaded.state.value.totalSeconds, 0.001)
    }

    @Test
    fun lists() {
        val all = tracks("a", "b", "c", "d")
        val (a, b, c, d) = all
        val store = PlayHistoryStore(dir)
        store.recordPlay(a.id, now - 40 * day)
        store.recordPlay(b.id, now - 2 * day)
        store.recordPlay(b.id, now - day)
        store.recordPlay(b.id, now - day + 60_000L)
        store.recordPlay(c.id, now - 60 * day)
        store.recordPlay(c.id, now - 50 * day)

        assertEquals(listOf("b", "a", "c"), store.recentlyPlayed(all).map { it.title })
        assertEquals(listOf("b", "c", "a"), store.mostPlayed(all).map { it.title })
        assertEquals(listOf("a", "c", "b"), store.leastPlayed(all).map { it.title })
        assertEquals(listOf(d), store.neverPlayed(all))
        assertEquals(listOf("c", "a"), store.notPlayedLately(all, now).map { it.title })
    }

    @Test
    fun equalCountsOrderByMostRecentPlay() {
        val all = tracks("a", "b")
        val store = PlayHistoryStore(dir)
        store.recordPlay(all[0].id, now - day)
        store.recordPlay(all[1].id, now)
        assertEquals(listOf("b", "a"), store.mostPlayed(all).map { it.title })
        assertEquals(listOf("a", "b"), store.leastPlayed(all).map { it.title })
    }

    @Test
    fun removedSongsLoseHistoryButTotalsStay() {
        val store = PlayHistoryStore(dir)
        store.recordPlay("x", now)
        store.recordPlay("y", now)
        store.removeTracks(setOf("x"))
        assertEquals(0, store.count("x"))
        assertEquals(1, store.count("y"))
        assertEquals(2, store.state.value.totalPlays)
        assertEquals(0, PlayHistoryStore(dir).count("x"))
        assertNotNull(PlayHistoryStore(dir).lastPlayed("y"))
    }
}
