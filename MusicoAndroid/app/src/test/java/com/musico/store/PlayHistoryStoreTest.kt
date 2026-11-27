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

    private fun track(
        name: String,
        artist: String = "A",
        album: String = "B",
        albumArtist: String = artist,
        artworkKey: String? = null,
        remoteArtworkUrl: String? = null,
        isEphemeral: Boolean = false,
    ): Track {
        val path = "$name.mp3"
        val id = if (isEphemeral) Track.makeEphemeralId("ext.demo", name) else Track.makeId(root, path)
        return Track(
            id = id,
            rootId = root,
            relativePath = path,
            title = name,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            durationSec = 10.0,
            fileSize = 1L,
            modifiedEpochMs = 0L,
            dateAddedEpochMs = 0L,
            artworkKey = artworkKey,
            remoteArtworkUrl = remoteArtworkUrl,
            isEphemeral = isEphemeral,
        )
    }

    private fun tracks(vararg names: String): List<Track> = names.map { name -> track(name) }

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

    @Test
    fun topArtistsAggregatesPlaysPicksArtworkAndRespectsLimit() {
        val t1 = track("t1", artist = "Kano", album = "Al1", artworkKey = null)
        val t2 = track("t2", artist = "Kano", album = "Al2", artworkKey = "art_kano")
        val t3 = track("t3", artist = "Reol", album = "Al3", artworkKey = "art_reol")
        val t4 = track("t4", artist = "Aimer", album = "Al4", artworkKey = "art_aimer")
        val t5 = track("t5", artist = "Unplayed", album = "Al5", artworkKey = "art_unplayed")
        val eph = track("eph", artist = "Ephemeral", album = "Al6", isEphemeral = true)
        val library = listOf(t1, t2, t3, t4, t5, eph)

        val store = PlayHistoryStore(dir)
        store.recordPlay(t1, now - 4 * day)
        store.recordPlay(t1, now - 3 * day)
        store.recordPlay(t2, now - 2 * day) // Kano = 3 plays, artworkKey = art_kano
        store.recordPlay(t3, now - day)
        store.recordPlay(t3, now) // Reol = 2 plays
        store.recordPlay(t4, now - 5 * day) // Aimer = 1 play
        store.markStarted(t5, now) // 0 plays, ignored
        store.recordPlay(eph, now) // ephemeral, ignored

        val top2 = store.topArtists(library, limit = 2)
        assertEquals(listOf("Kano", "Reol"), top2.map { it.artist })
        assertEquals(listOf(3, 2), top2.map { it.playCount })
        assertEquals("art_kano", top2[0].artworkKey)
        assertEquals("art_reol", top2[1].artworkKey)

        val topAll = store.topArtists(library)
        assertEquals(listOf("Kano", "Reol", "Aimer"), topAll.map { it.artist })
        assertEquals(emptyList<ArtistPlayStat>(), store.topArtists(library, limit = 0))
    }

    @Test
    fun topAlbumsAggregatesPlaysAndSeparatesSameTitleByAlbumArtist() {
        val a1 = track("a1", artist = "Artist A", album = "Greatest Hits", albumArtist = "Artist A", artworkKey = "art_a")
        val a2 = track("a2", artist = "Artist A feat. X", album = "Greatest Hits", albumArtist = "Artist A", artworkKey = null)
        val b1 = track("b1", artist = "Artist B", album = "Greatest Hits", albumArtist = "Artist B", artworkKey = "art_b")
        val c1 = track("c1", artist = "Artist C", album = "Zero", albumArtist = "Artist C", artworkKey = "art_c")
        val library = listOf(a1, a2, b1, c1)

        val store = PlayHistoryStore(dir)
        store.recordPlay(a1, now - 3 * day)
        store.recordPlay(a2, now - 2 * day)
        store.recordPlay(a2, now - day) // Artist A / Greatest Hits = 3 plays
        store.recordPlay(b1, now) // Artist B / Greatest Hits = 1 play
        store.markStarted(c1, now) // 0 plays, ignored

        val topAlbums = store.topAlbums(library, limit = 5)
        assertEquals(2, topAlbums.size)
        assertEquals("Artist A", topAlbums[0].artist)
        assertEquals("Greatest Hits", topAlbums[0].title)
        assertEquals(3, topAlbums[0].playCount)
        assertEquals("art_a", topAlbums[0].artworkKey)
        assertEquals("Artist B", topAlbums[1].artist)
        assertEquals(1, topAlbums[1].playCount)
    }

    @Test
    fun playedLibraryFractionIgnoresStartedAndEphemeralTracks() {
        val store = PlayHistoryStore(dir)
        assertEquals(0f, store.playedLibraryFraction(emptyList()), 0.0001f)

        val t1 = track("t1")
        val t2 = track("t2")
        val t3 = track("t3")
        val t4 = track("t4")
        val eph = track("eph", isEphemeral = true)
        val library = listOf(t1, t2, t3, t4, eph)

        assertEquals(0f, store.playedLibraryFraction(library), 0.0001f)

        store.markStarted(t1, now) // started only -> count 0
        store.recordPlay(eph, now) // ephemeral -> ignored
        assertEquals(0f, store.playedLibraryFraction(library), 0.0001f)

        store.recordPlay(t1, now)
        store.recordPlay(t1, now + 1000L) // repeated play on same song still counts 1 song explored
        store.recordPlay(t3, now)
        assertEquals(0.5f, store.playedLibraryFraction(library), 0.0001f)

        store.recordPlay(t2, now)
        store.recordPlay(t4, now)
        assertEquals(1.0f, store.playedLibraryFraction(library), 0.0001f)
    }

    @Test
    fun remapTracksMergesCountsTakesMaxTimestampIgnoresEphemeralAndPersists() {
        val store = PlayHistoryStore(dir)
        val cloudA = Track.makeId("cloud_root", "youtube/vid1")
        val cloudB = Track.makeId("cloud_root", "youtube/vid2")
        val localA = Track.makeId("local_root", "Song 1.m4a")
        val localB = Track.makeId("local_root", "Song 2.m4a")
        val ephId = Track.makeEphemeralId("youtube-search", "vid1")

        store.recordPlay(cloudA, now - 5 * day)
        store.recordPlay(cloudA, now - 2 * day) // count=2, last=now-2d
        store.recordPlay(localA, now - day) // count=1, last=now-1d
        store.recordPlay(cloudB, now) // count=1, last=now

        store.remapTracks(
            mapOf(
                cloudA to localA,
                cloudB to localB,
                ephId to localA,
                localB to localB,
            )
        )

        assertEquals(0, store.count(cloudA))
        assertEquals(0, store.count(cloudB))
        assertEquals(3, store.count(localA))
        assertEquals(now - day, store.lastPlayed(localA))
        assertEquals(1, store.count(localB))
        assertEquals(now, store.lastPlayed(localB))

        val reloaded = PlayHistoryStore(dir)
        assertEquals(3, reloaded.count(localA))
        assertEquals(now - day, reloaded.lastPlayed(localA))
        assertEquals(1, reloaded.count(localB))
    }
}
