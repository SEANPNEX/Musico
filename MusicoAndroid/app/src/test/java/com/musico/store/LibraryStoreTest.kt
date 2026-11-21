package com.musico.store

import android.net.Uri
import com.musico.cloud.BilibiliClient
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpResponse
import com.musico.cloud.CloudSource
import com.musico.cloud.YouTubeClient
import com.musico.core.ArtworkCache
import com.musico.core.MetadataReading
import com.musico.core.TrackMetadata
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private class StubMetadataReader : MetadataReading {
    var calls = 0
    val byName = mutableMapOf<String, TrackMetadata>()
    var delayMs = 0L

    override suspend fun read(uri: Uri, fileNameHint: String?): TrackMetadata {
        calls += 1
        if (delayMs > 0) delay(delayMs)
        val name = fileNameHint ?: uri.lastPathSegment ?: ""
        return byName[name] ?: TrackMetadata(durationSec = 5.0)
    }

    override suspend fun read(file: File): TrackMetadata {
        calls += 1
        if (delayMs > 0) delay(delayMs)
        return byName[file.name] ?: TrackMetadata(durationSec = 5.0)
    }
}

class LibraryStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var stateDir: File
    private lateinit var docsDir: File
    private lateinit var reader: StubMetadataReader

    @Before
    fun setUp() {
        stateDir = tempFolder.newFolder("state")
        docsDir = tempFolder.newFolder("docs")
        reader = StubMetadataReader()
    }

    private fun makeStore(batchSize: Int = 100): LibraryStore =
        LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = reader,
            artwork = ArtworkCache(File(stateDir, "artwork")),
            context = null,
            scanBatchSize = batchSize,
        )

    private fun touch(relativePath: String, content: String = "x", root: File = docsDir): File {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    @Test
    fun emptyAppFolderGetsNoteSoItIsVisible() {
        makeStore()
        val names = docsDir.list()?.toList()
        assertEquals(listOf("Put your music in this folder.txt"), names)
    }

    @Test
    fun appFolderWithContentGetsNoNote() {
        touch("a.mp3")
        makeStore()
        assertEquals(listOf("a.mp3"), docsDir.list()?.toList())
    }

    @Test
    fun appFolderRootAlwaysPresent() {
        val store = makeStore()
        assertEquals(listOf(LibraryRoot.APP_FOLDER_ID), store.state.value.roots.map { it.id })
        assertTrue(store.isAvailable(store.state.value.roots[0]))
    }

    @Test
    fun initialScanIndexesFiles() = runBlocking {
        touch("a.mp3")
        touch("Rock/b.flac")
        val store = makeStore()
        store.rescan()
        val state = store.state.value
        assertEquals(listOf("Rock/b.flac", "a.mp3"), state.tracks.map { it.relativePath }.sorted())
        val track = state.tracks.first { it.relativePath == "Rock/b.flac" }
        assertEquals(track, store.track(track.id))
        assertFalse(state.isScanning)
    }

    @Test
    fun rescanKeepsUnchangedWithoutRereading() = runBlocking {
        touch("a.mp3")
        val store = makeStore()
        store.rescan()
        val before = store.state.value.tracks
        assertEquals(1, reader.calls)
        store.rescan()
        assertEquals(1, reader.calls)
        assertEquals(before, store.state.value.tracks)
    }

    @Test
    fun rescanPicksUpNewAndRemovesDeleted() = runBlocking {
        val fileA = touch("a.mp3")
        touch("b.mp3")
        val store = makeStore()
        store.rescan()

        val trackA = store.state.value.tracks.first { it.relativePath == "a.mp3" }
        val playlistId = store.createPlaylist("P")
        store.addToPlaylist(listOf(trackA.id), playlistId)
        store.toggleFavourite(trackA.id)

        val removed = mutableSetOf<String>()
        store.onTracksRemoved = { removed.addAll(it) }

        assertTrue(fileA.delete())
        touch("c.mp3")
        store.rescan()

        assertEquals(listOf("b.mp3", "c.mp3"), store.state.value.tracks.map { it.relativePath }.sorted())
        assertEquals(setOf(trackA.id), removed)
        assertEquals(emptyList<String>(), store.state.value.playlists.first().trackIds)
        assertFalse(store.isFavourite(trackA.id))
    }

    @Test
    fun changedFileIsRereadAndKeepsDateAdded() = runBlocking {
        val fileA = touch("a.mp3")
        val store = makeStore()
        store.rescan()
        val added = store.state.value.tracks[0].dateAddedEpochMs

        fileA.writeText("longer content", Charsets.UTF_8)
        reader.byName["a.mp3"] = TrackMetadata(title = "New Title", durationSec = 9.0)
        store.rescan()

        assertEquals(listOf("New Title"), store.state.value.tracks.map { it.title })
        assertEquals(added, store.state.value.tracks[0].dateAddedEpochMs)
    }

    @Test
    fun fallbacksWhenTagsMissing() = runBlocking {
        touch("Live Set/01 Opening.mp3")
        touch("loose.mp3")
        val store = makeStore()
        store.rescan()

        val nested = store.state.value.tracks.first { it.relativePath.startsWith("Live") }
        assertEquals("01 Opening", nested.title)
        assertEquals("Unknown Artist", nested.artist)
        assertEquals("Live Set", nested.album)

        val loose = store.state.value.tracks.first { it.relativePath == "loose.mp3" }
        assertEquals("Unknown Album", loose.album)
    }

    @Test
    fun folderContentsListsSubfoldersAndTracksWithPrefixBoundary() = runBlocking {
        touch("top.mp3")
        touch("A/one.mp3")
        touch("A/Deep/two.mp3")
        touch("AB/sibling.mp3")
        touch("B/three.mp3")
        val store = makeStore()
        store.rescan()

        val (rootFolders, rootTracks) = store.folderContents(LibraryRoot.APP_FOLDER_ID, "")
        assertEquals(listOf("A", "AB", "B"), rootFolders)
        assertEquals(listOf("top.mp3"), rootTracks.map { it.relativePath })

        val (aFolders, aTracks) = store.folderContents(LibraryRoot.APP_FOLDER_ID, "A")
        assertEquals(listOf("Deep"), aFolders)
        assertEquals(listOf("A/one.mp3"), aTracks.map { it.relativePath })

        assertEquals(
            listOf("A/Deep/two.mp3", "A/one.mp3"),
            store.tracksUnder(LibraryRoot.APP_FOLDER_ID, "A").map { it.relativePath },
        )
    }

    @Test
    fun albumsSortedByDiscAndTrack() = runBlocking {
        listOf("x.mp3", "y.mp3", "z.mp3", "other.mp3").forEach { touch(it) }
        reader.byName["x.mp3"] = TrackMetadata(title = "Third", artist = "Ar", album = "Al", trackNumber = 1, discNumber = 2, durationSec = 1.0)
        reader.byName["y.mp3"] = TrackMetadata(title = "Second", artist = "Ar", album = "Al", trackNumber = 2, discNumber = 1, durationSec = 1.0)
        reader.byName["z.mp3"] = TrackMetadata(title = "First", artist = "Ar", album = "Al", trackNumber = 1, discNumber = 1, durationSec = 1.0)
        reader.byName["other.mp3"] = TrackMetadata(title = "Solo", artist = "Zed", album = "Else", durationSec = 1.0)

        val store = makeStore()
        store.rescan()
        val state = store.state.value
        assertEquals(listOf("Al", "Else"), state.albums.map { it.title })
        assertEquals(
            listOf("First", "Second", "Third"),
            state.albums[0].trackIds.mapNotNull { store.track(it)?.title },
        )
        assertEquals(listOf("Ar", "Zed"), state.artists)
    }

    @Test
    fun searchIsCaseAndDiacriticInsensitive() = runBlocking {
        touch("a.mp3")
        touch("b.mp3")
        reader.byName["a.mp3"] = TrackMetadata(title = "Café del Mar", artist = "X", album = "Y", durationSec = 1.0)
        reader.byName["b.mp3"] = TrackMetadata(title = "Other", artist = "Björk", album = "Y", durationSec = 1.0)
        val store = makeStore()
        store.rescan()

        assertEquals(listOf("Café del Mar"), store.search("cafe").map { it.title })
        assertEquals(listOf("Other"), store.search("BJORK").map { it.title })
        assertEquals(emptyList<String>(), store.search("  ").map { it.title })
    }

    @Test
    fun statePersistsAndPlaylistReorderWorks() = runBlocking {
        touch("a.mp3")
        touch("b.mp3")
        val first = makeStore()
        first.rescan()
        val idA = first.state.value.tracks[0].id
        val idB = first.state.value.tracks[1].id
        first.toggleFavourite(idA)
        val pid = first.createPlaylist("Mix")
        first.addToPlaylist(listOf(idA, idB), pid)
        first.movePlaylistTrack(pid, fromIndex = 1, toIndex = 0)
        first.renamePlaylist(pid, "Renamed Mix")

        val second = makeStore()
        assertEquals(first.state.value.tracks, second.state.value.tracks)
        assertEquals(setOf(idA), second.state.value.favourites)
        assertEquals(listOf("Renamed Mix"), second.state.value.playlists.map { it.name })
        assertEquals(listOf(idB, idA), second.state.value.playlists[0].trackIds)
    }

    @Test
    fun scanPublishesTracksInBatchesBeforeFinishing() = runBlocking {
        for (i in 0 until 6) touch("s$i.mp3")
        val store = makeStore(batchSize = 2)
        reader.delayMs = 40L
        val job = launch { store.rescan() }
        delay(130L)
        assertTrue(store.state.value.isScanning)
        assertTrue(store.state.value.tracks.size >= 2)
        assertTrue(store.state.value.tracks.size < 6)
        job.join()
        assertEquals(6, store.state.value.tracks.size)
        assertNotNull(store.state.value.tracks.firstOrNull())
    }

    @Test
    fun cloudRootLifecycleAddSyncRetainOnErrorPersistAndRemove() = runBlocking {
        var failNetwork = false
        var playlistTitle = "Cloud Mix"
        var includeSecondTrack = false

        val fakeHttp = object : CloudHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse {
                if (url.contains("/x/frontend/finger/spi")) {
                    return CloudHttpResponse(200, url, emptyMap(), """{"code":0,"data":{"b_3":"B3"}}""".toByteArray())
                }
                return CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
            }

            override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse {
                if (failNetwork) {
                    return CloudHttpResponse(503, url, emptyMap(), ByteArray(0))
                }
                val second = if (includeSecondTrack) {
                    """,{
                      "playlistVideoRenderer": {
                        "videoId": "vid_two_0002",
                        "title": { "runs": [ { "text": "Cloud Song 2" } ] },
                        "shortBylineText": { "runs": [ { "text": "Artist 2" } ] },
                        "lengthSeconds": "200"
                      }
                    }"""
                } else ""
                val body = """
                    {
                      "header": { "musicDetailHeaderRenderer": { "title": { "runs": [ { "text": "$playlistTitle" } ] } } },
                      "contents": {
                        "items": [
                          {
                            "playlistVideoRenderer": {
                              "videoId": "vid_one_0001",
                              "title": { "runs": [ { "text": "Cloud Song 1" } ] },
                              "shortBylineText": { "runs": [ { "text": "Artist 1" } ] },
                              "lengthSeconds": "180"
                            }
                          }
                          $second
                        ]
                      }
                    }
                """.trimIndent()
                return CloudHttpResponse(200, url, emptyMap(), body.toByteArray())
            }

            override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
        }

        fun makeCloudStore(): LibraryStore = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = reader,
            artwork = ArtworkCache(File(stateDir, "artwork")),
            context = null,
            cloudHttpClient = fakeHttp,
            youTubeClient = YouTubeClient(fakeHttp),
            bilibiliClient = BilibiliClient(fakeHttp),
        )

        val store = makeCloudStore()
        val added = store.addCloudRoot("https://music.youtube.com/playlist?list=PLcloud123456")
        assertTrue(added.isSuccess)

        val dup = store.addCloudRoot("https://www.youtube.com/playlist?list=PLcloud123456")
        assertTrue(dup.isFailure)

        val cloudRoot = store.state.value.roots.first { it.kind == RootKind.CLOUD_YOUTUBE }
        assertEquals("Cloud Mix", cloudRoot.name)
        assertTrue(store.isAvailable(cloudRoot))
        assertEquals(1, store.state.value.tracks.size)
        val firstTrack = store.state.value.tracks.first()
        assertTrue(firstTrack.isCloud)
        assertEquals(CloudSource.YOUTUBE, firstTrack.cloudSource)
        assertTrue(store.isAvailable(firstTrack))
        val originalAddedMs = firstTrack.dateAddedEpochMs

        // Transient network error retains existing cloud tracks.
        failNetwork = true
        store.rescan()
        assertEquals(1, store.state.value.tracks.size)
        assertEquals(firstTrack.id, store.state.value.tracks.first().id)

        // Successful rescan adds new track while preserving dateAddedEpochMs of existing track.
        failNetwork = false
        includeSecondTrack = true
        store.rescan()
        assertEquals(2, store.state.value.tracks.size)
        assertEquals(originalAddedMs, store.track(firstTrack.id)?.dateAddedEpochMs)

        // Persists across store reload.
        val reloaded = makeCloudStore()
        assertEquals(2, reloaded.state.value.roots.size)
        assertEquals(2, reloaded.state.value.tracks.size)
        assertTrue(reloaded.isAvailable(reloaded.state.value.roots.first { it.kind.isCloud }))

        // Removing cloud root deletes its tracks cleanly.
        reloaded.removeRoot(cloudRoot.id)
        assertEquals(1, reloaded.state.value.roots.size)
        assertTrue(reloaded.state.value.tracks.isEmpty())
    }
}
