package com.musico.store

import android.net.Uri
import com.musico.cloud.BilibiliClient
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudFolderManifest
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpResponse
import com.musico.cloud.CloudSource
import com.musico.cloud.GoogleDriveClient
import com.musico.cloud.YouTubeClient
import com.musico.core.ArtworkCache
import com.musico.core.MetadataReading
import com.musico.core.TrackMetadata
import com.musico.model.CloudTrackingInfo
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.SearchField
import com.musico.model.Track
import com.musico.model.TrackSortOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun removeSubfolderExcludesOnlyTargetedTreeAndPersistsAcrossRescanAndReload() = runBlocking {
        touch("A/one.mp3")
        touch("A/Deep/two.mp3")
        touch("AB/sibling.mp3")
        touch("root.mp3")

        val store = makeStore()
        store.rescan()

        val aOne = store.state.value.tracks.first { it.relativePath == "A/one.mp3" }
        val aDeep = store.state.value.tracks.first { it.relativePath == "A/Deep/two.mp3" }
        val ab = store.state.value.tracks.first { it.relativePath == "AB/sibling.mp3" }
        val playlistId = store.createPlaylist("Sub Mix")
        store.addToPlaylist(listOf(aOne.id, aDeep.id, ab.id), playlistId)
        store.toggleFavourite(aOne.id)
        store.toggleFavourite(ab.id)

        val removedCallbacks = mutableSetOf<String>()
        store.onTracksRemoved = { removedCallbacks.addAll(it) }

        store.removeSubfolder(LibraryRoot.APP_FOLDER_ID, "A")

        // Disk files remain intact.
        assertTrue(File(docsDir, "A/one.mp3").exists())
        assertTrue(File(docsDir, "A/Deep/two.mp3").exists())

        assertEquals(setOf("AB/sibling.mp3", "root.mp3"), store.state.value.tracks.map { it.relativePath }.toSet())
        assertEquals(setOf(aOne.id, aDeep.id), removedCallbacks)
        assertEquals(listOf(ab.id), store.state.value.playlists.first().trackIds)
        assertFalse(store.isFavourite(aOne.id))
        assertTrue(store.isFavourite(ab.id))

        // Rescan & fresh store reload keep "A" excluded.
        store.rescan()
        assertEquals(setOf("AB/sibling.mp3", "root.mp3"), store.state.value.tracks.map { it.relativePath }.toSet())

        val reloaded = makeStore()
        reloaded.rescan()
        assertEquals(setOf("AB/sibling.mp3", "root.mp3"), reloaded.state.value.tracks.map { it.relativePath }.toSet())
        assertEquals(listOf("A"), reloaded.state.value.roots.first().excludedPaths)

        // Restoring excluded subfolders brings A's tracks back on rescan.
        reloaded.restoreExcludedFolders(LibraryRoot.APP_FOLDER_ID)
        assertEquals(
            setOf("A/one.mp3", "A/Deep/two.mp3", "AB/sibling.mp3", "root.mp3"),
            reloaded.state.value.tracks.map { it.relativePath }.toSet(),
        )
        assertEquals(emptyList<String>(), reloaded.state.value.roots.first().excludedPaths)
    }

    @Test
    fun parentExclusionPrunesChildrenAndLegacyRootJsonDecodesCleanly() = runBlocking {
        touch("Group/Sub/one.mp3")
        touch("Group/two.mp3")

        val store = makeStore()
        store.rescan()

        store.removeSubfolder(LibraryRoot.APP_FOLDER_ID, "Group/Sub")
        assertEquals(listOf("Group/two.mp3"), store.state.value.tracks.map { it.relativePath })
        assertEquals(listOf("Group/Sub"), store.state.value.roots.first().excludedPaths)

        // Excluding parent prunes deeper child exclusion and removes remaining track cleanly.
        store.removeSubfolder(LibraryRoot.APP_FOLDER_ID, "Group")
        assertEquals(listOf("Group"), store.state.value.roots.first().excludedPaths)
        assertTrue(store.state.value.tracks.isEmpty())

        store.rescan()
        assertTrue(store.state.value.tracks.isEmpty())

        val legacyJson = """{"id":"r1","name":"Old","kind":"PICKED"}"""
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<LibraryRoot>(legacyJson)
        assertEquals(emptyList<String>(), decoded.excludedPaths)
        assertFalse(decoded.isExcluded("Any/song.mp3"))
    }

    @Test
    fun metadataOverridesPersistAndSurviveRescanAndReload() = runBlocking {
        val file = touch("song.flac", "initial")
        reader.byName["song.flac"] = TrackMetadata(
            title = "Original Title",
            artist = "Original Artist",
            album = "Original Album",
            genre = "Pop",
            durationSec = 10.0,
        )
        val store = makeStore()
        store.rescan()

        val original = store.state.value.tracks.single()
        assertEquals("Pop", original.genre)

        val customArtKey = store.artwork.store(createValidPngBytes())
        // Test with arbitrary bytes via a real 1x1 PNG or fallback key string if BitmapFactory is stubbed in JVM unit tests
        val effectiveArtKey = customArtKey ?: "manual_art_key"

        var updatedCallbackTrackId: String? = null
        store.onTrackUpdated = { updatedCallbackTrackId = it.id }

        val edited = store.updateTrackMetadata(
            trackId = original.id,
            title = "Edited Title",
            artist = "Edited Artist",
            album = "Edited Album",
            genre = "Synthwave",
            artworkKey = effectiveArtKey,
        )
        assertNotNull(edited)
        assertEquals(original.id, updatedCallbackTrackId)
        assertEquals("Edited Title", store.track(original.id)?.title)
        assertEquals("Edited Artist", store.track(original.id)?.artist)
        assertEquals("Edited Album", store.track(original.id)?.album)
        assertEquals("Synthwave", store.track(original.id)?.genre)
        assertEquals(effectiveArtKey, store.track(original.id)?.artworkKey)
        assertEquals(listOf("Edited Artist"), store.state.value.artists)
        assertEquals(listOf("Edited Album"), store.state.value.albums.map { it.title })

        // Modify file size/content so rescan re-reads tags from disk, then verify overrides still win.
        file.writeText("modified longer audio bytes", Charsets.UTF_8)
        reader.byName["song.flac"] = TrackMetadata(
            title = "Re-read Disk Title",
            artist = "Re-read Disk Artist",
            album = "Re-read Disk Album",
            genre = "Jazz",
            durationSec = 12.0,
        )
        store.rescan()
        val afterRescan = store.track(original.id)!!
        assertEquals("Edited Title", afterRescan.title)
        assertEquals("Edited Artist", afterRescan.artist)
        assertEquals("Edited Album", afterRescan.album)
        assertEquals("Synthwave", afterRescan.genre)
        assertEquals(effectiveArtKey, afterRescan.artworkKey)

        // Reload fresh LibraryStore from disk and rescan again.
        val reloaded = makeStore()
        val afterReload = reloaded.track(original.id)!!
        assertEquals("Edited Title", afterReload.title)
        assertEquals("Synthwave", afterReload.genre)
        reloaded.rescan()
        assertEquals("Edited Title", reloaded.track(original.id)?.title)
        assertEquals("Edited Artist", reloaded.track(original.id)?.artist)
        assertEquals("Edited Album", reloaded.track(original.id)?.album)
        assertEquals("Synthwave", reloaded.track(original.id)?.genre)
        assertEquals(effectiveArtKey, reloaded.track(original.id)?.artworkKey)
    }

    @Test
    fun searchSupportsFieldFilteringIncludingGenre() = runBlocking {
        touch("a.mp3")
        touch("b.mp3")
        reader.byName["a.mp3"] = TrackMetadata(
            title = "Night Drive",
            artist = "Kano",
            album = "Neon City",
            genre = "Rock",
            durationSec = 3.0,
        )
        reader.byName["b.mp3"] = TrackMetadata(
            title = "Kanojo",
            artist = "Other",
            album = "Rock Anthology",
            genre = "Electronic",
            durationSec = 3.0,
        )
        val store = makeStore()
        store.rescan()

        assertEquals(listOf("Kanojo", "Night Drive"), store.search("Kano", SearchField.KEYWORD).map { it.title }.sorted())
        assertEquals(listOf("Night Drive"), store.search("Kano", SearchField.ARTIST).map { it.title })
        assertEquals(listOf("Night Drive"), store.search("Neon City", SearchField.ALBUM).map { it.title })
        assertEquals(listOf("Night Drive"), store.search("Rock", SearchField.GENRE).map { it.title })
        assertEquals(listOf("Kanojo", "Night Drive"), store.search("Rock", SearchField.KEYWORD).map { it.title }.sorted())
    }

    @Test
    fun folderSortOrderAndCustomTrackOrderPersistForDisplayAndPlayback() = runBlocking {
        val f1 = touch("Mix/b_song.mp3", "1")
        val f2 = touch("Mix/a_song.mp3", "2")
        val f3 = touch("Mix/c_song.mp3", "3")
        f1.setLastModified(1_000L)
        f2.setLastModified(3_000L)
        f3.setLastModified(2_000L)
        reader.byName["b_song.mp3"] = TrackMetadata(title = "Beta", durationSec = 5.0)
        reader.byName["a_song.mp3"] = TrackMetadata(title = "Alpha", durationSec = 5.0)
        reader.byName["c_song.mp3"] = TrackMetadata(title = "Gamma", durationSec = 5.0)

        val store = makeStore()
        store.rescan()

        val playCounts = mapOf(
            store.state.value.tracks.first { it.title == "Alpha" }.id to 1,
            store.state.value.tracks.first { it.title == "Beta" }.id to 10,
            store.state.value.tracks.first { it.title == "Gamma" }.id to 5,
        )
        val countFn: (String) -> Int = { playCounts[it] ?: 0 }

        store.setFolderSortOrder(LibraryRoot.APP_FOLDER_ID, "Mix", TrackSortOrder.NAME_DESC)
        assertEquals(
            listOf("Gamma", "Beta", "Alpha"),
            store.folderContents(LibraryRoot.APP_FOLDER_ID, "Mix", countFn).second.map { it.title },
        )
        assertEquals(
            listOf("Gamma", "Beta", "Alpha"),
            store.tracksUnder(LibraryRoot.APP_FOLDER_ID, "Mix", countFn).map { it.title },
        )

        store.setFolderSortOrder(LibraryRoot.APP_FOLDER_ID, "Mix", TrackSortOrder.MOST_PLAYED)
        assertEquals(
            listOf("Beta", "Gamma", "Alpha"),
            store.folderContents(LibraryRoot.APP_FOLDER_ID, "Mix", countFn).second.map { it.title },
        )

        // Drag reorder switches folder to CUSTOM and persists across reload for both display and playback.
        val currentIds = store.folderContents(LibraryRoot.APP_FOLDER_ID, "Mix", countFn).second.map { it.id }
        store.moveFolderTrack(LibraryRoot.APP_FOLDER_ID, "Mix", fromIndex = 2, toIndex = 0, currentOrderedIds = currentIds)
        assertEquals(TrackSortOrder.CUSTOM, store.folderSortOrder(LibraryRoot.APP_FOLDER_ID, "Mix"))
        assertEquals(
            listOf("Alpha", "Beta", "Gamma"),
            store.folderContents(LibraryRoot.APP_FOLDER_ID, "Mix", countFn).second.map { it.title },
        )

        val reloaded = makeStore()
        assertEquals(TrackSortOrder.CUSTOM, reloaded.folderSortOrder(LibraryRoot.APP_FOLDER_ID, "Mix"))
        assertEquals(
            listOf("Alpha", "Beta", "Gamma"),
            reloaded.folderContents(LibraryRoot.APP_FOLDER_ID, "Mix", countFn).second.map { it.title },
        )
        assertEquals(
            listOf("Alpha", "Beta", "Gamma"),
            reloaded.tracksUnder(LibraryRoot.APP_FOLDER_ID, "Mix", countFn).map { it.title },
        )
    }

    @Test
    fun playlistSortOrderAndReorderPersistAcrossReload() = runBlocking {
        touch("a.mp3")
        touch("b.mp3")
        touch("c.mp3")
        reader.byName["a.mp3"] = TrackMetadata(title = "Alpha", durationSec = 4.0)
        reader.byName["b.mp3"] = TrackMetadata(title = "Beta", durationSec = 4.0)
        reader.byName["c.mp3"] = TrackMetadata(title = "Gamma", durationSec = 4.0)

        val store = makeStore()
        store.rescan()
        val idA = store.state.value.tracks.first { it.title == "Alpha" }.id
        val idB = store.state.value.tracks.first { it.title == "Beta" }.id
        val idC = store.state.value.tracks.first { it.title == "Gamma" }.id

        val pid = store.createPlaylist("Sorted Mix")
        store.addToPlaylist(listOf(idA, idB, idC), pid)
        store.setPlaylistSortOrder(pid, TrackSortOrder.NAME_DESC)
        assertEquals(listOf(idC, idB, idA), store.state.value.playlists.first { it.id == pid }.trackIds)
        assertEquals(TrackSortOrder.NAME_DESC, store.state.value.playlists.first { it.id == pid }.sortOrder)

        store.reorderPlaylistTracks(pid, listOf(idB, idC, idA))
        val reloaded = makeStore()
        val pl = reloaded.state.value.playlists.first { it.id == pid }
        assertEquals(TrackSortOrder.CUSTOM, pl.sortOrder)
        assertEquals(listOf(idB, idC, idA), pl.trackIds)
    }

    @Test
    fun cloudFolderReorderingWorksForYouTubeAndBilibiliAndSurvivesSyncAndReload() = runBlocking {
        var includeNewYtTrack = false
        var includeNewBiliTrack = false

        val fakeHttp = object : CloudHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse {
                if (url.contains("/x/frontend/finger/spi")) {
                    return CloudHttpResponse(200, url, emptyMap(), """{"code":0,"data":{"b_3":"B3"}}""".toByteArray())
                }
                if (url.contains("/x/v3/fav/resource/list")) {
                    val extra = if (includeNewBiliTrack) {
                        """,{ "bvid": "BV4444444444", "title": "Bili Four", "upper": { "name": "UP4" }, "duration": 210, "attr": 0 }"""
                    } else ""
                    val body = """
                        {
                          "code": 0,
                          "data": {
                            "info": { "title": "Bili Fav" },
                            "has_more": false,
                            "medias": [
                              { "bvid": "BV1111111111", "title": "Bili One", "upper": { "name": "UP1" }, "duration": 100, "attr": 0 },
                              { "bvid": "BV2222222222", "title": "Bili Two", "upper": { "name": "UP2" }, "duration": 120, "attr": 0 },
                              { "bvid": "BV3333333333", "title": "Bili Three", "upper": { "name": "UP3" }, "duration": 140, "attr": 0 }
                              $extra
                            ]
                          }
                        }
                    """.trimIndent()
                    return CloudHttpResponse(200, url, emptyMap(), body.toByteArray())
                }
                return CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
            }

            override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse {
                val extra = if (includeNewYtTrack) {
                    """,{
                      "playlistVideoRenderer": {
                        "videoId": "yt_vid_0004",
                        "title": { "runs": [ { "text": "YT Four" } ] },
                        "shortBylineText": { "runs": [ { "text": "Art 4" } ] },
                        "lengthSeconds": "240"
                      }
                    }"""
                } else ""
                val body = """
                    {
                      "header": { "musicDetailHeaderRenderer": { "title": { "runs": [ { "text": "YT Mix" } ] } } },
                      "contents": {
                        "items": [
                          {
                            "playlistVideoRenderer": {
                              "videoId": "yt_vid_0001",
                              "title": { "runs": [ { "text": "YT One" } ] },
                              "shortBylineText": { "runs": [ { "text": "Art 1" } ] },
                              "lengthSeconds": "180"
                            }
                          },
                          {
                            "playlistVideoRenderer": {
                              "videoId": "yt_vid_0002",
                              "title": { "runs": [ { "text": "YT Two" } ] },
                              "shortBylineText": { "runs": [ { "text": "Art 2" } ] },
                              "lengthSeconds": "190"
                            }
                          },
                          {
                            "playlistVideoRenderer": {
                              "videoId": "yt_vid_0003",
                              "title": { "runs": [ { "text": "YT Three" } ] },
                              "shortBylineText": { "runs": [ { "text": "Art 3" } ] },
                              "lengthSeconds": "200"
                            }
                          }
                          $extra
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
        assertTrue(store.addCloudRoot("My Mix https://music.youtube.com/playlist?list=PLcloudyt1234。").isSuccess)
        assertTrue(store.addCloudRoot("【歌单】深夜听的歌 https://space.bilibili.com/12/favlist?fid=987654").isSuccess)

        val ytRoot = store.state.value.roots.first { it.kind == RootKind.CLOUD_YOUTUBE }
        val biliRoot = store.state.value.roots.first { it.kind == RootKind.CLOUD_BILIBILI }
        assertEquals("https://music.youtube.com/playlist?list=PLcloudyt1234", ytRoot.sourceUrl)
        assertEquals("https://space.bilibili.com/12/favlist?fid=987654", biliRoot.sourceUrl)

        // Cloud folders expose tracks directly at root without synthetic subfolders.
        val (ytFolders, ytInitial) = store.folderContents(ytRoot.id, "")
        assertTrue(ytFolders.isEmpty())
        assertEquals(listOf("YT One", "YT Two", "YT Three"), ytInitial.map { it.title })

        val (biliFolders, biliInitial) = store.folderContents(biliRoot.id, "")
        assertTrue(biliFolders.isEmpty())
        assertEquals(listOf("Bili One", "Bili Two", "Bili Three"), biliInitial.map { it.title })

        // Reorder via setFolderTrackOrder at root and verify both folderContents & tracksUnder match.
        val ytReorderedIds = listOf(ytInitial[2].id, ytInitial[0].id, ytInitial[1].id)
        store.setFolderTrackOrder(ytRoot.id, "", ytReorderedIds)
        assertEquals(TrackSortOrder.CUSTOM, store.folderSortOrder(ytRoot.id, ""))
        assertEquals(listOf("YT Three", "YT One", "YT Two"), store.folderContents(ytRoot.id, "").second.map { it.title })
        assertEquals(listOf("YT Three", "YT One", "YT Two"), store.tracksUnder(ytRoot.id, "").map { it.title })

        // Also works if called with legacy subfolder path (e.g. "cloud_bilibili" / "bilibili").
        val biliReorderedIds = listOf(biliInitial[1].id, biliInitial[2].id, biliInitial[0].id)
        store.setFolderTrackOrder(biliRoot.id, "cloud_bilibili", biliReorderedIds)
        assertEquals(TrackSortOrder.CUSTOM, store.folderSortOrder(biliRoot.id, ""))
        assertEquals(listOf("Bili Two", "Bili Three", "Bili One"), store.folderContents(biliRoot.id, "").second.map { it.title })
        assertEquals(listOf("Bili Two", "Bili Three", "Bili One"), store.tracksUnder(biliRoot.id, "").map { it.title })

        // Sync adds new tracks without losing existing tracks or their custom order.
        includeNewYtTrack = true
        includeNewBiliTrack = true
        store.rescan()
        assertEquals(
            listOf("YT Three", "YT One", "YT Two", "YT Four"),
            store.folderContents(ytRoot.id, "").second.map { it.title },
        )
        assertEquals(
            listOf("YT Three", "YT One", "YT Two", "YT Four"),
            store.tracksUnder(ytRoot.id, "").map { it.title },
        )
        assertEquals(
            listOf("Bili Two", "Bili Three", "Bili One", "Bili Four"),
            store.folderContents(biliRoot.id, "").second.map { it.title },
        )
        assertEquals(
            listOf("Bili Two", "Bili Three", "Bili One", "Bili Four"),
            store.tracksUnder(biliRoot.id, "").map { it.title },
        )

        // Reloading from disk preserves all tracks and custom orders.
        val reloaded = makeCloudStore()
        assertEquals(
            listOf("YT Three", "YT One", "YT Two", "YT Four"),
            reloaded.folderContents(ytRoot.id, "").second.map { it.title },
        )
        assertEquals(
            listOf("Bili Two", "Bili Three", "Bili One", "Bili Four"),
            reloaded.tracksUnder(biliRoot.id, "").map { it.title },
        )
    }

    @Test
    fun googleDriveCloudFolderPreservesHierarchyDragReorderAndSync() = runBlocking {
        var includeFourth = false
        val fakeHttp = object : CloudHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse {
                if (url.contains("drive.google.com/drive/folders/1DriveMix")) {
                    val fourth = if (includeFourth) {
                        """,["g_id_4",["1DriveMix"],"04 Delta.mp3","audio/mpeg"]"""
                    } else ""
                    val ivd = """[[["g_id_1",["1DriveMix"],"01 Alpha.flac","audio/flac"],["g_id_2",["1DriveMix"],"02 Beta.mp3","audio/mpeg"],["g_id_3",["1DriveMix"],"03 Gamma.m4a","audio/mp4"]$fourth,["1Disc2",["1DriveMix"],"Disc 2","application/vnd.google-apps.folder"]]]"""
                    val html = """
                        <html><head><title>Drive Mix - Google Drive</title></head><body>
                        <script>window['_DRIVE_ivd'] = '${ivd.replace("\"", "\\x22").replace("[", "\\x5b").replace("]", "\\x5d")}';</script>
                        </body></html>
                    """.trimIndent()
                    return CloudHttpResponse(200, url, emptyMap(), html.toByteArray())
                }
                if (url.contains("drive.google.com/drive/folders/1Disc2")) {
                    val ivd = """[[["g_sub_1",["1Disc2"],"05 Sub.flac","audio/flac"]]]"""
                    val html = """
                        <html><head><title>Disc 2 - Google Drive</title></head><body>
                        <script>window['_DRIVE_ivd'] = '${ivd.replace("\"", "\\x22").replace("[", "\\x5b").replace("]", "\\x5d")}';</script>
                        </body></html>
                    """.trimIndent()
                    return CloudHttpResponse(200, url, emptyMap(), html.toByteArray())
                }
                return CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
            }

            override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse =
                CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

            override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
        }

        fun makeDriveStore(): LibraryStore = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = reader,
            artwork = ArtworkCache(File(stateDir, "artwork")),
            context = null,
            cloudHttpClient = fakeHttp,
            youTubeClient = YouTubeClient(fakeHttp),
            bilibiliClient = BilibiliClient(fakeHttp),
            googleDriveClient = GoogleDriveClient(fakeHttp),
        )

        val store = makeDriveStore()
        val addRes = store.addCloudRoot("Shared Drive: https://drive.google.com/drive/folders/1DriveMix?usp=sharing。")
        assertTrue(addRes.isSuccess)
        store.awaitCloudSync()

        val driveRoot = store.state.value.roots.first { it.kind == RootKind.CLOUD_GOOGLE_DRIVE }
        assertTrue(driveRoot.kind.isCloud)
        assertEquals("Drive Mix", driveRoot.name)
        assertEquals(
            listOf("01 Alpha.flac", "02 Beta.mp3", "03 Gamma.m4a", "Disc 2/05 Sub.flac"),
            store.state.value.tracks.map { it.relativePath }.sorted(),
        )

        val (folders, initial) = store.folderContents(driveRoot.id, "")
        assertEquals(listOf("Disc 2"), folders)
        assertEquals(listOf("01 Alpha", "02 Beta", "03 Gamma"), initial.map { it.title })
        val (subFolders, subTracks) = store.folderContents(driveRoot.id, "Disc 2")
        assertTrue(subFolders.isEmpty())
        assertEquals(listOf("05 Sub"), subTracks.map { it.title })

        store.moveFolderTrack(driveRoot.id, "", fromIndex = 2, toIndex = 0, currentOrderedIds = initial.map { it.id })
        assertEquals(TrackSortOrder.CUSTOM, store.folderSortOrder(driveRoot.id, ""))
        assertEquals(listOf("03 Gamma", "01 Alpha", "02 Beta"), store.folderContents(driveRoot.id, "").second.map { it.title })
        assertEquals(
            listOf("03 Gamma", "01 Alpha", "02 Beta", "05 Sub"),
            store.tracksUnder(driveRoot.id, "").map { it.title },
        )

        // Sync appends new root track while preserving custom order and subfolder tracks.
        includeFourth = true
        store.rescan()
        store.awaitCloudSync()
        assertEquals(
            listOf("03 Gamma", "01 Alpha", "02 Beta", "04 Delta"),
            store.folderContents(driveRoot.id, "").second.map { it.title },
        )

        // Reloading from disk preserves hierarchy and custom order.
        val reloaded = makeDriveStore()
        assertEquals(
            listOf("03 Gamma", "01 Alpha", "02 Beta", "04 Delta"),
            reloaded.folderContents(driveRoot.id, "").second.map { it.title },
        )
        assertEquals(
            listOf("03 Gamma", "01 Alpha", "02 Beta", "04 Delta", "05 Sub"),
            reloaded.tracksUnder(driveRoot.id, "").map { it.title },
        )
    }

    @Test
    fun legacyFlatGoogleDriveRootKeepsFlattenedBrowsingCompatibility() = runBlocking {
        val rootId = "legacy-gdrive-root"
        val legacyJson = """
            {
              "roots": [
                {
                  "id": "$rootId",
                  "name": "Legacy Drive",
                  "kind": "CLOUD_GOOGLE_DRIVE",
                  "sourceUrl": "https://drive.google.com/drive/folders/1Legacy",
                  "remoteId": "1Legacy"
                }
              ],
              "tracks": [
                {
                  "id": "legacy_1",
                  "rootId": "$rootId",
                  "relativePath": "gdrive/fid1.mp3",
                  "title": "01 Legacy Alpha",
                  "artist": "Artist",
                  "album": "Legacy Drive",
                  "albumArtist": "Artist",
                  "durationSec": 120.0,
                  "fileSize": 0,
                  "modifiedEpochMs": 0,
                  "dateAddedEpochMs": 1000,
                  "cloudSource": "google_drive",
                  "remoteTrackId": "fid1.mp3"
                },
                {
                  "id": "legacy_2",
                  "rootId": "$rootId",
                  "relativePath": "gdrive/fid2.flac",
                  "title": "02 Legacy Beta",
                  "artist": "Artist",
                  "album": "Legacy Drive",
                  "albumArtist": "Artist",
                  "durationSec": 140.0,
                  "fileSize": 0,
                  "modifiedEpochMs": 0,
                  "dateAddedEpochMs": 1000,
                  "cloudSource": "google_drive",
                  "remoteTrackId": "fid2.flac"
                }
              ],
              "readerVersion": 3
            }
        """.trimIndent()
        File(stateDir, "library.json").writeText(legacyJson, Charsets.UTF_8)

        val store = makeStore()
        val root = store.state.value.roots.first { it.id == rootId }
        assertTrue(root.trackedSubfolders.isEmpty())
        val (folders, tracks) = store.folderContents(rootId, "")
        assertTrue(folders.isEmpty())
        assertEquals(listOf("01 Legacy Alpha", "02 Legacy Beta"), tracks.map { it.title })

        store.setFolderSortOrder(rootId, "gdrive", TrackSortOrder.NAME_DESC)
        assertEquals(TrackSortOrder.NAME_DESC, store.folderSortOrder(rootId, ""))
        assertEquals(listOf("02 Legacy Beta", "01 Legacy Alpha"), store.folderContents(rootId, "").second.map { it.title })
    }

    @Test
    fun googleDriveProgressiveSyncStreamsBatchesPrioritizesAndCancelsOnRemove() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val folderReached = CompletableDeferred<Unit>()
        var failPartial = false

        val fakeHttp = object : CloudHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse {
                fun page(ivd: String, title: String) = CloudHttpResponse(
                    200,
                    url,
                    emptyMap(),
                    """<html><head><title>$title - Google Drive</title></head><body><script>window['_DRIVE_ivd'] = '${ivd.replace("\"", "\\x22").replace("[", "\\x5b").replace("]", "\\x5d")}';</script></body></html>""".toByteArray(),
                )
                return when {
                    url.contains("drive/folders/1ProgRoot") -> page(
                        """[[["g_root",["1ProgRoot"],"01 Root.mp3","audio/mpeg"],["1SubA",["1ProgRoot"],"Sub A","application/vnd.google-apps.folder"],["1SubTarget",["1ProgRoot"],"Sub Target","application/vnd.google-apps.folder"]]]""",
                        "Prog Drive",
                    )
                    url.contains("drive/folders/1SubA") -> {
                        folderReached.complete(Unit)
                        gate.await()
                        if (failPartial) {
                            CloudHttpResponse(500, url, emptyMap(), ByteArray(0))
                        } else {
                            page("""[[["g_suba",["1SubA"],"02 SubA.mp3","audio/mpeg"]]]""", "Sub A")
                        }
                    }
                    url.contains("drive/folders/1SubTarget") -> page(
                        """[[["g_target",["1SubTarget"],"03 Target.mp3","audio/mpeg"]]]""",
                        "Sub Target",
                    )
                    else -> CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
                }
            }

            override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse =
                CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

            override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
        }

        val store = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = reader,
            artwork = ArtworkCache(File(stateDir, "artwork")),
            context = null,
            cloudHttpClient = fakeHttp,
            youTubeClient = YouTubeClient(fakeHttp),
            bilibiliClient = BilibiliClient(fakeHttp),
            googleDriveClient = GoogleDriveClient(fakeHttp),
        )
        val completedFolderSyncs = mutableListOf<Pair<String, List<String>>>()
        store.onFolderAdded = { name, ids -> completedFolderSyncs.add(name to ids) }

        val res = store.addCloudRoot("https://drive.google.com/drive/folders/1ProgRoot")
        assertTrue(res.isSuccess)
        val root = store.state.value.roots.first { it.kind == RootKind.CLOUD_GOOGLE_DRIVE }
        assertTrue(completedFolderSyncs.isEmpty())
        assertTrue(store.state.value.tracks.any { it.relativePath == "01 Root.mp3" })
        // Subfolders known immediately even while lazy/background sync is still pending.
        val (initialSubfolders, _) = store.folderContents(root.id, "")
        assertEquals(listOf("Sub A", "Sub Target"), initialSubfolders)

        folderReached.await()
        store.prioritizeCloudFolder(root.id, "Sub Target")
        gate.complete(Unit)
        store.awaitCloudSync(root.id)

        assertFalse(root.id in store.state.value.cloudSyncProgress)
        assertEquals(
            listOf("01 Root.mp3", "Sub A/02 SubA.mp3", "Sub Target/03 Target.mp3"),
            store.state.value.tracks.map { it.relativePath }.sorted(),
        )
        assertEquals(1, completedFolderSyncs.size)
        assertEquals("Prog Drive", completedFolderSyncs.first().first)

        // Removing root while a sync is running cancels it cleanly without re-adding tracks.
        val gate2 = CompletableDeferred<Unit>()
        val reached2 = CompletableDeferred<Unit>()
        val slowHttp = object : CloudHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse {
                if (url.contains("drive/folders/1CancelRoot")) {
                    val ivd = """[[["g_c0",["1CancelRoot"],"01 CRoot.mp3","audio/mpeg"],["1SlowChild",["1CancelRoot"],"SlowChild","application/vnd.google-apps.folder"]]]"""
                    return CloudHttpResponse(
                        200,
                        url,
                        emptyMap(),
                        """<html><head><title>Cancel Drive - Google Drive</title></head><body><script>window['_DRIVE_ivd'] = '${ivd.replace("\"", "\\x22").replace("[", "\\x5b").replace("]", "\\x5d")}';</script></body></html>""".toByteArray(),
                    )
                }
                if (url.contains("drive/folders/1SlowChild")) {
                    reached2.complete(Unit)
                    gate2.await()
                }
                return CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
            }

            override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse =
                CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

            override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
        }
        val cancelStore = LibraryStore(
            directory = tempFolder.newFolder("cancelState"),
            appFolderDir = tempFolder.newFolder("cancelDocs"),
            reader = reader,
            artwork = ArtworkCache(File(stateDir, "artwork2")),
            context = null,
            cloudHttpClient = slowHttp,
            youTubeClient = YouTubeClient(slowHttp),
            bilibiliClient = BilibiliClient(slowHttp),
            googleDriveClient = GoogleDriveClient(slowHttp),
        )
        assertTrue(cancelStore.addCloudRoot("https://drive.google.com/drive/folders/1CancelRoot").isSuccess)
        val cancelRoot = cancelStore.state.value.roots.first { it.kind == RootKind.CLOUD_GOOGLE_DRIVE }
        reached2.await()
        cancelStore.removeRoot(cancelRoot.id)
        gate2.complete(Unit)
        cancelStore.awaitCloudSync()
        assertFalse(cancelStore.state.value.roots.any { it.id == cancelRoot.id })
        assertFalse(cancelRoot.id in cancelStore.state.value.cloudSyncProgress)
        assertTrue(cancelStore.state.value.tracks.isEmpty())
    }

    @Test
    fun addLocalFolderNonOverlappingTracksSubfoldersWithoutDuplicatesAndMigratesChildRoots() = runBlocking {
        val store = makeStore()
        // 1. Add cloud subfolder inside APP_FOLDER: must attach trackedSubfolders instead of creating duplicate root or overwriting APP_FOLDER root sourceUrl.
        val appSub = File(docsDir, "Cloud Mix").apply { mkdirs() }
        touch("Cloud Mix/01.mp3")
        val sourceRoot = LibraryRoot(
            id = "cloud-src",
            name = "Cloud Mix",
            kind = RootKind.CLOUD_YOUTUBE,
            sourceUrl = "https://www.youtube.com/playlist?list=PL_APP",
            remoteId = "PL_APP",
        )
        store.removeSubfolder(LibraryRoot.APP_FOLDER_ID, "Cloud Mix")
        val addInsideApp = store.addLocalFolder(appSub, cloudSourceRoot = sourceRoot)
        assertTrue(addInsideApp.isSuccess)
        assertEquals(1, store.state.value.roots.size)
        val appRoot = store.state.value.roots.first { it.id == LibraryRoot.APP_FOLDER_ID }
        assertNull(appRoot.sourceUrl)
        assertFalse("Cloud Mix" in appRoot.excludedPaths)
        assertEquals("https://www.youtube.com/playlist?list=PL_APP", appRoot.trackedSubfolders["Cloud Mix"]?.sourceUrl)

        // 2. Adding an external parent containing child subfolder must not duplicate roots; adding nested subfolder inside PICKED root attaches trackedSubfolders.
        val parentDir = tempFolder.newFolder("MusicLibrary")
        val childA = File(parentDir, "SubA").apply { mkdirs() }
        val childB = File(parentDir, "SubB").apply { mkdirs() }
        touch("a.mp3", root = childA)
        touch("b.mp3", root = childB)
        assertTrue(
            store.addLocalFolder(
                childA,
                cloudSourceRoot = sourceRoot.copy(
                    sourceUrl = "https://www.youtube.com/playlist?list=PL_SUBA",
                    remoteId = "PL_SUBA",
                ),
            ).isSuccess
        )
        assertEquals(2, store.state.value.roots.size)

        // Now add parentDir: existing SubA picked root should migrate cleanly into parentDir.trackedSubfolders["SubA"]!
        assertTrue(store.addLocalFolder(parentDir).isSuccess)
        assertEquals(2, store.state.value.roots.size)
        val pickedParent = store.state.value.roots.first { it.kind == RootKind.PICKED }
        assertEquals("MusicLibrary", pickedParent.name)
        assertEquals("https://www.youtube.com/playlist?list=PL_SUBA", pickedParent.trackedSubfolders["SubA"]?.sourceUrl)

        // Adding SubB inside pickedParent attaches SubB tracking cleanly without adding a duplicate root.
        assertTrue(
            store.addLocalFolder(
                childB,
                cloudSourceRoot = LibraryRoot(
                    id = "cloud-bili",
                    name = "SubB",
                    kind = RootKind.CLOUD_BILIBILI,
                    sourceUrl = "https://www.bilibili.com/video/BV1xx411c7mD",
                    remoteId = "BV1xx411c7mD",
                ),
            ).isSuccess
        )
        assertEquals(2, store.state.value.roots.size)
        val refreshedParent = store.state.value.roots.first { it.id == pickedParent.id }
        assertEquals(
            CloudTrackingInfo(
                sourceUrl = "https://www.bilibili.com/video/BV1xx411c7mD",
                remoteId = "BV1xx411c7mD",
                cloudKind = RootKind.CLOUD_BILIBILI,
                lastSyncedEpochMs = refreshedParent.trackedSubfolders["SubB"]?.lastSyncedEpochMs,
            ),
            refreshedParent.trackedSubfolders["SubB"],
        )
        assertEquals("https://www.youtube.com/playlist?list=PL_SUBA", refreshedParent.trackedSubfolders["SubA"]?.sourceUrl)
    }

    @Test
    fun manifestDiscoveryPrunesMissingFoldersAndUpdatesTrackingAddress() = runBlocking {
        val store = makeStore()
        val subDir = File(docsDir, "Nested/Album").apply { mkdirs() }
        touch("Nested/Album/song.mp3")
        val manifest = CloudFolderManifest(
            sourceURL = "https://music.youtube.com/playlist?list=PL_OLD",
            remoteID = "PL_OLD",
            cloudKind = RootKind.CLOUD_YOUTUBE.name,
            lastSyncedEpochMs = 1234L,
            tracks = mapOf("vid1" to "song.mp3"),
        )
        val manifestFile = File(subDir, CloudExporter.MANIFEST_FILE_NAME)
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        manifestFile.writeText(json.encodeToString(manifest), Charsets.UTF_8)

        store.rescan()
        val discovered = store.cloudTracking(LibraryRoot.APP_FOLDER_ID, "Nested/Album")
        assertNotNull(discovered)
        assertEquals("https://music.youtube.com/playlist?list=PL_OLD", discovered?.sourceUrl)
        assertEquals(RootKind.CLOUD_YOUTUBE, discovered?.cloudKind)

        // Updating cloud tracking address validates URL, updates store, persists library.json, and rewrites manifest on disk.
        val badResult = store.updateCloudTrackingAddress(
            rootId = LibraryRoot.APP_FOLDER_ID,
            path = "Nested/Album",
            newSourceUrl = "https://example.com/not-supported",
        )
        assertTrue(badResult.isFailure)

        val okResult = store.updateCloudTrackingAddress(
            rootId = LibraryRoot.APP_FOLDER_ID,
            path = "Nested/Album",
            newSourceUrl = "https://www.bilibili.com/video/BV1newTest01",
            cookie = "SESSDATA=xyz",
        )
        assertTrue(okResult.isSuccess)
        val updated = store.cloudTracking(LibraryRoot.APP_FOLDER_ID, "Nested/Album")
        assertEquals("https://www.bilibili.com/video/BV1newTest01", updated?.sourceUrl)
        assertEquals(RootKind.CLOUD_BILIBILI, updated?.cloudKind)
        assertEquals("SESSDATA=xyz", updated?.cookie)

        val rewritten = json.decodeFromString<CloudFolderManifest>(manifestFile.readText(Charsets.UTF_8))
        assertEquals("https://www.bilibili.com/video/BV1newTest01", rewritten.sourceURL)
        assertEquals(RootKind.CLOUD_BILIBILI.name, rewritten.cloudKind)

        // Deleting the folder on disk prunes stale trackedSubfolders entry during next scan.
        subDir.deleteRecursively()
        store.rescan()
        assertNull(store.cloudTracking(LibraryRoot.APP_FOLDER_ID, "Nested/Album"))
    }

    @Test
    fun deleteTrackFileAndSubfolderAndAppFolderGuardAndRemoveFromLibrary() = runBlocking {
        val fileOne = touch("Mix/one.mp3")
        val fileTwo = touch("Mix/Sub/two.mp3")
        val fileKeep = touch("Keep/keep.mp3")

        val store = makeStore()
        store.rescan()

        val trackOne = store.state.value.tracks.first { it.relativePath == "Mix/one.mp3" }
        val trackTwo = store.state.value.tracks.first { it.relativePath == "Mix/Sub/two.mp3" }
        val trackKeep = store.state.value.tracks.first { it.relativePath == "Keep/keep.mp3" }

        assertEquals(fileOne.absolutePath, store.displayLocation(trackOne))
        assertEquals(File(docsDir, "Mix/Sub").absolutePath, store.displayFolderLocation(LibraryRoot.APP_FOLDER_ID, "Mix/Sub"))

        val pid = store.createPlaylist("My List")
        store.addToPlaylist(listOf(trackOne.id, trackTwo.id, trackKeep.id), pid)
        store.toggleFavourite(trackOne.id)
        store.toggleFavourite(trackKeep.id)

        val beforeDeleting = mutableListOf<Set<String>>()
        val removedCallbacks = mutableSetOf<String>()
        store.onBeforeDeletingTracks = { beforeDeleting.add(it) }
        store.onTracksRemoved = { removedCallbacks.addAll(it) }

        // 1. Delete individual track from APP_FOLDER
        val delOne = store.deleteTrackFile(trackOne.id)
        assertTrue(delOne.isSuccess)
        assertFalse(fileOne.exists())
        assertNull(store.track(trackOne.id))
        assertEquals(listOf(setOf(trackOne.id)), beforeDeleting)
        assertEquals(setOf(trackOne.id), removedCallbacks)
        assertFalse(store.isFavourite(trackOne.id))
        assertEquals(listOf(trackTwo.id, trackKeep.id), store.state.value.playlists.first().trackIds)

        // 2. Delete subfolder from APP_FOLDER
        val delSub = store.deleteFolderFiles(LibraryRoot.APP_FOLDER_ID, "Mix")
        assertTrue(delSub.isSuccess)
        assertFalse(File(docsDir, "Mix").exists())
        assertFalse(fileTwo.exists())
        assertNull(store.track(trackTwo.id))
        assertTrue(setOf(trackTwo.id) in beforeDeleting)
        assertTrue(trackTwo.id in removedCallbacks)
        assertEquals(listOf(trackKeep.id), store.state.value.playlists.first().trackIds)

        // 3. Deleting missing file fails and leaves library intact
        assertTrue(fileKeep.delete())
        val delMissing = store.deleteTrackFile(trackKeep.id)
        assertTrue(delMissing.isFailure)
        assertNotNull(store.track(trackKeep.id))

        // Restore fileKeep on disk for next checks
        touch("Keep/keep.mp3")

        // 4. Guard against deleting APP_FOLDER root
        val delAppRoot = store.deleteFolderFiles(LibraryRoot.APP_FOLDER_ID, "")
        assertTrue(delAppRoot.isFailure)
        assertTrue(docsDir.exists())
        assertNotNull(store.track(trackKeep.id))

        // 5. removeTrackFromLibrary excludes without deleting file, and restoreExcludedFolders restores it
        store.removeTrackFromLibrary(trackKeep.id)
        assertTrue(fileKeep.exists())
        assertNull(store.track(trackKeep.id))
        val appRoot = store.state.value.roots.first { it.id == LibraryRoot.APP_FOLDER_ID }
        assertTrue("Keep/keep.mp3" in appRoot.excludedPaths)

        store.rescan()
        assertNull(store.track(trackKeep.id))

        store.restoreExcludedFolders(LibraryRoot.APP_FOLDER_ID)
        assertNotNull(store.track(trackKeep.id))
        assertTrue(store.state.value.roots.first { it.id == LibraryRoot.APP_FOLDER_ID }.excludedPaths.isEmpty())
    }

    private fun createValidPngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}
