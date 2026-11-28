package com.musico.store

import android.net.Uri
import com.musico.cloud.CloudAudioCache
import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudFolderManifest
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpResponse
import com.musico.cloud.CloudPlaylistResult
import com.musico.cloud.CloudSource
import com.musico.cloud.CloudSourceException
import com.musico.cloud.CloudStreamDownloader
import com.musico.cloud.CloudStreamPurpose
import com.musico.cloud.CloudStreamResolver
import com.musico.cloud.CloudTrackMetadata
import com.musico.cloud.GoogleDriveClient
import com.musico.cloud.ResolvedStream
import com.musico.core.ArtworkCache
import com.musico.core.AudioTagPayload
import com.musico.core.AudioTagWriter
import com.musico.core.EmbeddedArtworkExtractor
import com.musico.core.MetadataReading
import com.musico.core.TrackMetadata
import com.musico.extensions.CloudProviderIds
import com.musico.extensions.CloudProviderRegistry
import com.musico.extensions.CloudSourceProvider
import com.musico.extensions.CloudUrlMatch
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        val playlistTitle = "Cloud Mix"
        var includeSecondTrack = false

        val fakeHttp = object : CloudHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse =
                CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

            override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse =
                CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

            override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
        }

        val ytExtProvider = object : CloudSourceProvider {
            override val providerId: String = CloudProviderIds.YOUTUBE_EXTENSION
            override val rootKind: RootKind = RootKind.CLOUD_EXTENSION
            override val cloudSourceSlug: String = CloudSource.YOUTUBE
            override val displayName: String = "YouTube Search"
            override val badgeLabel: String = "YouTube"
            override val isBuiltin: Boolean = false
            override val supportsTree: Boolean = false
            override val supportsCookie: Boolean = true
            override val usesFlatCloudListing: Boolean = true

            override fun canHandle(rawInput: String): CloudUrlMatch? {
                val match = Regex("list=([A-Za-z0-9_-]+)").find(rawInput) ?: return null
                val id = match.groupValues[1]
                return CloudUrlMatch(
                    normalizedUrl = "https://www.youtube.com/playlist?list=$id",
                    remoteId = id,
                )
            }

            override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult {
                if (failNetwork) {
                    throw CloudSourceException(CloudErrorCode.NETWORK_ERROR, "Simulated 503")
                }
                val tracks = buildList {
                    add(
                        CloudTrackMetadata(
                            remoteTrackId = "vid_one_0001",
                            title = "Cloud Song 1",
                            artist = "Artist 1",
                            album = playlistTitle,
                            durationSec = 180.0,
                        ),
                    )
                    if (includeSecondTrack) {
                        add(
                            CloudTrackMetadata(
                                remoteTrackId = "vid_two_0002",
                                title = "Cloud Song 2",
                                artist = "Artist 2",
                                album = playlistTitle,
                                durationSec = 200.0,
                            ),
                        )
                    }
                }
                return CloudPlaylistResult(
                    source = CloudSource.YOUTUBE,
                    remoteId = "PLcloud123456",
                    title = playlistTitle,
                    tracks = tracks,
                )
            }

            override suspend fun resolveStream(
                remoteTrackId: String,
                relativePath: String?,
                cookie: String?,
                forceRefresh: Boolean,
                quality: String?,
                purpose: CloudStreamPurpose,
            ): ResolvedStream = throw UnsupportedOperationException()
        }

        fun makeCloudStore(withExtension: Boolean = true): LibraryStore {
            val registry = CloudProviderRegistry(GoogleDriveClient(fakeHttp))
            if (withExtension) {
                registry.registerExtensionProvider(ytExtProvider)
            }
            return LibraryStore(
                directory = stateDir,
                appFolderDir = docsDir,
                reader = reader,
                artwork = ArtworkCache(File(stateDir, "artwork")),
                context = null,
                cloudHttpClient = fakeHttp,
                providerRegistry = registry,
            )
        }

        // Bare app without YouTube extension must reject YouTube URL.
        val bareStore = makeCloudStore(withExtension = false)
        assertTrue(bareStore.addCloudRoot("https://music.youtube.com/playlist?list=PLcloud123456").isFailure)

        val store = makeCloudStore(withExtension = true)
        val added = store.addCloudRoot("https://music.youtube.com/playlist?list=PLcloud123456")
        assertTrue(added.isSuccess)

        val dup = store.addCloudRoot("https://www.youtube.com/playlist?list=PLcloud123456")
        assertTrue(dup.isFailure)

        val cloudRoot = store.state.value.roots.first { it.kind == RootKind.CLOUD_EXTENSION }
        assertEquals("Cloud Mix", cloudRoot.name)
        assertEquals(CloudProviderIds.YOUTUBE_EXTENSION, cloudRoot.providerId)
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
        val reloaded = makeCloudStore(withExtension = true)
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
    fun cloudFolderReorderingWorksForYouTubeAndBilibiliExtensionsAndSurvivesSyncAndReload() = runBlocking {
        var includeNewYtTrack = false
        var includeNewBiliTrack = false
        val registry = CloudProviderRegistry()
        registry.registerExtensionProvider(
            object : CloudSourceProvider {
                override val providerId: String = CloudProviderIds.YOUTUBE_EXTENSION
                override val rootKind: RootKind = RootKind.CLOUD_EXTENSION
                override val cloudSourceSlug: String = providerId
                override val displayName: String = "YouTube Search"
                override val badgeLabel: String = "YouTube"
                override val isBuiltin: Boolean = false
                override val supportsTree: Boolean = false
                override val supportsCookie: Boolean = true
                override val usesFlatCloudListing: Boolean = true
                override fun canHandle(rawInput: String): CloudUrlMatch? {
                    val idx = rawInput.indexOf("https://music.youtube.com/")
                    if (idx < 0) return null
                    val url = rawInput.substring(idx).trimEnd('。')
                    return CloudUrlMatch(url, "PLcloudyt1234")
                }

                override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult {
                    val tracks = buildList {
                        add(CloudTrackMetadata("yt_vid_0001", "YT One", "Art 1", "YT Mix", 180.0))
                        add(CloudTrackMetadata("yt_vid_0002", "YT Two", "Art 2", "YT Mix", 190.0))
                        add(CloudTrackMetadata("yt_vid_0003", "YT Three", "Art 3", "YT Mix", 200.0))
                        if (includeNewYtTrack) {
                            add(CloudTrackMetadata("yt_vid_0004", "YT Four", "Art 4", "YT Mix", 240.0))
                        }
                    }
                    return CloudPlaylistResult(providerId, "PLcloudyt1234", "YT Mix", tracks)
                }

                override suspend fun resolveStream(
                    remoteTrackId: String,
                    relativePath: String?,
                    cookie: String?,
                    forceRefresh: Boolean,
                    quality: String?,
                    purpose: CloudStreamPurpose,
                ): ResolvedStream = ResolvedStream("https://cdn.example.com/$remoteTrackId.m4a")
            }
        )
        registry.registerExtensionProvider(
            object : CloudSourceProvider {
                override val providerId: String = CloudProviderIds.BILIBILI_EXTENSION
                override val rootKind: RootKind = RootKind.CLOUD_EXTENSION
                override val cloudSourceSlug: String = providerId
                override val displayName: String = "Bilibili Search"
                override val badgeLabel: String = "Bilibili"
                override val isBuiltin: Boolean = false
                override val supportsTree: Boolean = false
                override val supportsCookie: Boolean = true
                override val usesFlatCloudListing: Boolean = true
                override fun canHandle(rawInput: String): CloudUrlMatch? {
                    val idx = rawInput.indexOf("https://space.bilibili.com/")
                    if (idx < 0) return null
                    val url = rawInput.substring(idx).trim()
                    return CloudUrlMatch(url, "fav:987654")
                }

                override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult {
                    val tracks = buildList {
                        add(CloudTrackMetadata("BV1111111111", "Bili One", "UP1", "Bili Fav", 100.0))
                        add(CloudTrackMetadata("BV2222222222", "Bili Two", "UP2", "Bili Fav", 120.0))
                        add(CloudTrackMetadata("BV3333333333", "Bili Three", "UP3", "Bili Fav", 140.0))
                        if (includeNewBiliTrack) {
                            add(CloudTrackMetadata("BV4444444444", "Bili Four", "UP4", "Bili Fav", 210.0))
                        }
                    }
                    return CloudPlaylistResult(providerId, "fav:987654", "Bili Fav", tracks)
                }

                override suspend fun resolveStream(
                    remoteTrackId: String,
                    relativePath: String?,
                    cookie: String?,
                    forceRefresh: Boolean,
                    quality: String?,
                    purpose: CloudStreamPurpose,
                ): ResolvedStream = ResolvedStream("https://cdn.example.com/$remoteTrackId.m4a")
            }
        )

        fun makeCloudStore(): LibraryStore = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = reader,
            artwork = ArtworkCache(File(stateDir, "artwork")),
            context = null,
            providerRegistry = registry,
        )

        val store = makeCloudStore()
        assertTrue(store.addCloudRoot("My Mix https://music.youtube.com/playlist?list=PLcloudyt1234。").isSuccess)
        assertTrue(store.addCloudRoot("【歌单】深夜听的歌 https://space.bilibili.com/12/favlist?fid=987654").isSuccess)

        val ytRoot = store.state.value.roots.first { it.resolvedProviderId == CloudProviderIds.YOUTUBE_EXTENSION }
        val biliRoot = store.state.value.roots.first { it.resolvedProviderId == CloudProviderIds.BILIBILI_EXTENSION }
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
        val registry = CloudProviderRegistry()
        registry.registerExtensionProvider(
            object : CloudSourceProvider {
                override val providerId: String = CloudProviderIds.BILIBILI_EXTENSION
                override val rootKind: RootKind = RootKind.CLOUD_EXTENSION
                override val cloudSourceSlug: String = providerId
                override val displayName: String = "Bilibili Search"
                override val badgeLabel: String = "Bilibili"
                override val isBuiltin: Boolean = false
                override val supportsTree: Boolean = false
                override val supportsCookie: Boolean = true
                override val usesFlatCloudListing: Boolean = true
                override fun canHandle(rawInput: String): CloudUrlMatch? =
                    if (rawInput.contains("bilibili.com")) CloudUrlMatch(rawInput.trim(), "BV1newTest01") else null

                override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult =
                    CloudPlaylistResult(providerId, "BV1newTest01", "Bili", emptyList())

                override suspend fun resolveStream(
                    remoteTrackId: String,
                    relativePath: String?,
                    cookie: String?,
                    forceRefresh: Boolean,
                    quality: String?,
                    purpose: CloudStreamPurpose,
                ): ResolvedStream = ResolvedStream("https://cdn.example.com/$remoteTrackId.m4a")
            }
        )
        val store = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = reader,
            artwork = ArtworkCache(File(stateDir, "artwork")),
            context = null,
            providerRegistry = registry,
        )
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
        assertEquals(RootKind.CLOUD_EXTENSION, updated?.cloudKind)
        assertEquals(CloudProviderIds.BILIBILI_EXTENSION, updated?.providerId)
        assertEquals("SESSDATA=xyz", updated?.cookie)

        val rewritten = json.decodeFromString<CloudFolderManifest>(manifestFile.readText(Charsets.UTF_8))
        assertEquals("https://www.bilibili.com/video/BV1newTest01", rewritten.sourceURL)
        assertEquals(RootKind.CLOUD_EXTENSION.name, rewritten.cloudKind)
        assertEquals(CloudProviderIds.BILIBILI_EXTENSION, rewritten.providerId)

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

    @Test
    fun cloudToLocalStandaloneMergeReplacesRootAtOriginalIndexAndRemapsAllState() = runBlocking {
        val registry = CloudProviderRegistry()
        registry.registerExtensionProvider(
            object : CloudSourceProvider {
                override val providerId: String = "ext.merge-test"
                override val rootKind: RootKind = RootKind.CLOUD_EXTENSION
                override val cloudSourceSlug: String = providerId
                override val displayName: String = "MergeTest"
                override val badgeLabel: String = "Merge"
                override val isBuiltin: Boolean = false
                override val supportsTree: Boolean = false
                override val supportsCookie: Boolean = false
                override val usesFlatCloudListing: Boolean = true
                override fun canHandle(rawInput: String): CloudUrlMatch? =
                    if (rawInput.startsWith("https://merge.test/")) CloudUrlMatch(rawInput.trim(), "mix_standalone") else null

                override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult =
                    CloudPlaylistResult(
                        source = providerId,
                        remoteId = "mix_standalone",
                        title = "Standalone Mix",
                        tracks = listOf(
                            CloudTrackMetadata("rm_1", "Alpha", "Artist A", "Standalone Mix", 100.0),
                            CloudTrackMetadata("rm_2", "Beta", "Artist B", "Standalone Mix", 120.0),
                        ),
                    )

                override suspend fun resolveStream(
                    remoteTrackId: String,
                    relativePath: String?,
                    cookie: String?,
                    forceRefresh: Boolean,
                    quality: String?,
                    purpose: CloudStreamPurpose,
                ): ResolvedStream = ResolvedStream(
                    url = "https://cdn.merge.test/$remoteTrackId.m4a",
                    mimeType = "audio/mp4",
                    fileExtension = "m4a",
                )
            }
        )

        val store = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = reader,
            artwork = ArtworkCache(File(stateDir, "artwork")),
            context = null,
            providerRegistry = registry,
        )
        val queueStore = QueueStore(stateDir)
        val historyStore = PlayHistoryStore(stateDir)
        store.onTracksRemapped = { idMap ->
            queueStore.remapTracks(idMap)
            historyStore.remapTracks(idMap)
        }

        // Order roots as: [APP_FOLDER, CloudRoot, OtherLocal]
        assertTrue(store.addCloudRoot("https://merge.test/mix1").isSuccess)
        val otherDir = tempFolder.newFolder("OtherLocal")
        touch("other.mp3", root = otherDir)
        assertTrue(store.addLocalFolder(otherDir).isSuccess)
        assertEquals(3, store.state.value.roots.size)

        val cloudRoot = store.state.value.roots[1]
        assertTrue(cloudRoot.kind.isCloud)
        val cloudTracks = store.tracksUnder(cloudRoot.id, "")
        assertEquals(2, cloudTracks.size)
        val cAlpha = cloudTracks.first { it.title == "Alpha" }
        val cBeta = cloudTracks.first { it.title == "Beta" }

        // Populate custom folder order, playlist, favourite, metadata override, queue, and play history on cloud tracks
        store.setFolderTrackOrder(cloudRoot.id, "", listOf(cBeta.id, cAlpha.id))
        val playlistId = store.createPlaylist("Cloud Favs")
        store.addToPlaylist(listOf(cBeta.id, cAlpha.id), playlistId)
        store.toggleFavourite(cAlpha.id)
        store.updateTrackMetadata(cBeta.id, title = "Beta Custom")
        val qId = queueStore.addQueue("Standalone Mix")
        queueStore.update(qId) { it.replace(listOf(cBeta.id, cAlpha.id), startAt = 0) }
        historyStore.recordPlay(cAlpha.id, 1_000_000L)
        historyStore.recordPlay(cAlpha.id, 2_000_000L)

        val exporter = CloudExporter(
            context = null,
            cloudResolver = CloudStreamResolver(registry),
            downloader = CloudStreamDownloader { url, _, out -> out.write("audio:$url".toByteArray()) },
            providerRegistry = registry,
        )
        val downloadManager = CloudDownloadManager(libraryStore = store, cloudExporter = exporter)
        val exportParent = tempFolder.newFolder("unindexed_exports")

        val jobId = downloadManager.startPlaylistExport(
            tracks = cloudTracks,
            root = cloudRoot,
            parentDir = exportParent,
            folderName = "Standalone Mix",
            addToLibrary = true,
            isWholeRootExport = true,
        )
        withTimeout(5_000L) {
            while (downloadManager.job(jobId)?.isActive != false) {
                delay(15L)
            }
        }
        assertEquals(CloudDownloadStatus.COMPLETED, downloadManager.job(jobId)?.status)

        // Strictly 1 folder replaces cloudRoot at index 1!
        assertEquals(3, store.state.value.roots.size)
        assertFalse(store.state.value.roots.any { it.id == cloudRoot.id })
        val mergedRoot = store.state.value.roots[1]
        assertEquals(RootKind.PICKED, mergedRoot.kind)
        assertEquals("Standalone Mix", mergedRoot.name)
        assertEquals(cloudRoot.sourceUrl, mergedRoot.sourceUrl)

        val localTracks = store.tracksUnder(mergedRoot.id, "")
        assertEquals(2, localTracks.size)
        assertTrue(localTracks.none { it.isCloud })
        // Custom folder order migrated (Beta first, Alpha second) + metadata override migrated onto Beta
        assertEquals(listOf("Beta Custom", "Alpha"), localTracks.map { it.title })
        assertEquals(TrackSortOrder.CUSTOM, store.folderSortOrder(mergedRoot.id, ""))

        val lBeta = localTracks[0]
        val lAlpha = localTracks[1]
        assertEquals(listOf(lBeta.id, lAlpha.id), store.state.value.playlists.first { it.id == playlistId }.trackIds)
        assertTrue(store.isFavourite(lAlpha.id))
        assertFalse(store.isFavourite(cAlpha.id))
        assertEquals(listOf(lBeta.id, lAlpha.id), queueStore.state.value.queues.first { it.id == qId }.trackIds)
        assertEquals(2, historyStore.count(lAlpha.id))
        assertEquals(0, historyStore.count(cAlpha.id))
        assertEquals(2_000_000L, historyStore.lastPlayed(lAlpha.id))
        assertEquals(CloudRootMergeEvent(cloudRoot.id, mergedRoot.id, "", store.lastCloudMerge.value!!.timestampMs), store.lastCloudMerge.value)
    }

    @Test
    fun cloudToLocalSubfolderMergeAndGuardConditionsBehaveCorrectly() = runBlocking {
        val registry = CloudProviderRegistry()
        registry.registerExtensionProvider(
            object : CloudSourceProvider {
                override val providerId: String = "ext.sub-merge"
                override val rootKind: RootKind = RootKind.CLOUD_EXTENSION
                override val cloudSourceSlug: String = providerId
                override val displayName: String = "SubMerge"
                override val badgeLabel: String = "Sub"
                override val isBuiltin: Boolean = false
                override val supportsTree: Boolean = false
                override val supportsCookie: Boolean = false
                override val usesFlatCloudListing: Boolean = true
                override fun canHandle(rawInput: String): CloudUrlMatch? =
                    if (rawInput.startsWith("https://sub.test/")) CloudUrlMatch(rawInput.trim(), rawInput.substringAfterLast('/')) else null

                override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult =
                    CloudPlaylistResult(
                        source = providerId,
                        remoteId = sourceUrl.substringAfterLast('/'),
                        title = "Sub Mix",
                        tracks = listOf(
                            CloudTrackMetadata("s_1", "First", "Art", "Sub Mix", 80.0),
                            CloudTrackMetadata("s_2", "Second", "Art", "Sub Mix", 90.0),
                        ),
                    )

                override suspend fun resolveStream(
                    remoteTrackId: String,
                    relativePath: String?,
                    cookie: String?,
                    forceRefresh: Boolean,
                    quality: String?,
                    purpose: CloudStreamPurpose,
                ): ResolvedStream = ResolvedStream("https://cdn.sub.test/$remoteTrackId.m4a", mimeType = "audio/mp4")
            }
        )

        val store = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = reader,
            artwork = ArtworkCache(File(stateDir, "artwork")),
            context = null,
            providerRegistry = registry,
        )
        val queueStore = QueueStore(stateDir)
        val historyStore = PlayHistoryStore(stateDir)
        store.onTracksRemapped = { idMap ->
            queueStore.remapTracks(idMap)
            historyStore.remapTracks(idMap)
        }

        // 1. Subfolder inside existing local root (APP_FOLDER), even with addToLibrary == false, merges cloudRoot cleanly!
        assertTrue(store.addCloudRoot("https://sub.test/m1").isSuccess)
        val cloudRoot1 = store.state.value.roots.first { it.kind.isCloud }
        val cTracks1 = store.tracksUnder(cloudRoot1.id, "")
        val cFirst = cTracks1.first { it.title == "First" }
        val cSecond = cTracks1.first { it.title == "Second" }
        store.setFolderTrackOrder(cloudRoot1.id, "", listOf(cSecond.id, cFirst.id))
        val plId = store.createPlaylist("Sub Playlist")
        store.addToPlaylist(listOf(cFirst.id), plId)
        store.toggleFavourite(cSecond.id)
        queueStore.update(queueStore.active.id) { it.replace(listOf(cFirst.id, cSecond.id), 0) }
        historyStore.recordPlay(cFirst.id, 5_000L)

        var failAllDownloads = false
        val exporter = CloudExporter(
            context = null,
            cloudResolver = CloudStreamResolver(registry),
            downloader = CloudStreamDownloader { url, _, out ->
                if (failAllDownloads) error("download failed")
                out.write("audio:$url".toByteArray())
            },
            providerRegistry = registry,
        )
        val dm = CloudDownloadManager(libraryStore = store, cloudExporter = exporter)
        suspend fun awaitJob(id: String) {
            withTimeout(5_000L) {
                while (dm.job(id)?.isActive != false) delay(15L)
            }
        }

        val job1 = dm.startPlaylistExport(
            tracks = cTracks1,
            root = cloudRoot1,
            parentDir = docsDir,
            folderName = "Saved In App",
            addToLibrary = false, // target IS already indexed -> still merges to keep 1 folder!
            isWholeRootExport = true,
        )
        awaitJob(job1)
        assertEquals(1, store.state.value.roots.size)
        assertFalse(store.state.value.roots.any { it.id == cloudRoot1.id })
        assertNotNull(store.cloudTracking(LibraryRoot.APP_FOLDER_ID, "Saved In App"))
        val mergedSubTracks = store.tracksUnder(LibraryRoot.APP_FOLDER_ID, "Saved In App")
        assertEquals(listOf("Second", "First"), mergedSubTracks.map { it.title })
        val lSecond = mergedSubTracks[0]
        val lFirst = mergedSubTracks[1]
        assertEquals(listOf(lFirst.id), store.state.value.playlists.first { it.id == plId }.trackIds)
        assertTrue(store.isFavourite(lSecond.id))
        assertEquals(listOf(lFirst.id, lSecond.id), queueStore.active.trackIds)
        assertEquals(1, historyStore.count(lFirst.id))
        assertEquals(LibraryRoot.APP_FOLDER_ID, store.lastCloudMerge.value?.targetRootId)
        assertEquals("Saved In App", store.lastCloudMerge.value?.targetRelPath)

        // 2. Guard cases leaving cloudRoot untouched:
        assertTrue(store.addCloudRoot("https://sub.test/m2").isSuccess)
        val cloudRoot2 = store.state.value.roots.first { it.kind.isCloud }
        val cTracks2 = store.tracksUnder(cloudRoot2.id, "")

        // 2a. savedCount == 0 leaves cloudRoot untouched
        failAllDownloads = true
        val zeroJob = dm.startPlaylistExport(
            tracks = cTracks2,
            root = cloudRoot2,
            parentDir = tempFolder.newFolder("zero_dir"),
            folderName = "Zero",
            addToLibrary = true,
            isWholeRootExport = true,
        )
        awaitJob(zeroJob)
        assertEquals(0, dm.job(zeroJob)?.exportSummary?.savedCount)
        assertTrue(store.state.value.roots.any { it.id == cloudRoot2.id })

        // 2b. addToLibrary == false on unindexed target leaves cloudRoot untouched
        failAllDownloads = false
        val unindexedOut = tempFolder.newFolder("unindexed_out")
        val noAddJob = dm.startPlaylistExport(
            tracks = cTracks2,
            root = cloudRoot2,
            parentDir = unindexedOut,
            folderName = "NoAdd",
            addToLibrary = false,
            isWholeRootExport = true,
        )
        awaitJob(noAddJob)
        assertEquals(2, dm.job(noAddJob)?.exportSummary?.savedCount)
        assertTrue(store.state.value.roots.any { it.id == cloudRoot2.id })
        assertFalse(store.state.value.roots.any { it.name == "NoAdd" })

        // 2c. isWholeRootExport == false leaves cloudRoot untouched (even when addToLibrary == true)
        val partialParent = tempFolder.newFolder("partial_parent")
        val partialJob = dm.startPlaylistExport(
            tracks = cTracks2,
            root = cloudRoot2,
            parentDir = partialParent,
            folderName = "PartialSub",
            addToLibrary = true,
            isWholeRootExport = false,
        )
        awaitJob(partialJob)
        assertTrue(store.state.value.roots.any { it.id == cloudRoot2.id })
        assertTrue(store.state.value.roots.any { it.name == "PartialSub" })
    }

    @Test
    fun googleDriveCacheEnrichmentAndMergeNeverCopiesPlaceholderMetadata() = runBlocking {
        val stateDir = tempFolder.newFolder("gd_enrich_state")
        val appDir = tempFolder.newFolder("gd_enrich_app")
        val artDir = tempFolder.newFolder("gd_enrich_art")
        val audioCache = CloudAudioCache(tempFolder.newFolder("gd_audio_cache"))
        val artCache = ArtworkCache(artDir)

        val flacOut = java.io.ByteArrayOutputStream().apply {
            write("fLaC".toByteArray(Charsets.US_ASCII))
            write(0x80)
            write(0x00)
            write(0x00)
            write(34)
            write(ByteArray(34))
            write(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x01, 0x02))
        }.toByteArray()
        val artBytes = createValidPngBytes()
        val expectedArtKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest(artBytes)
            .joinToString("") { "%02x".format(it) }
        // On JVM unit tests Android's BitmapFactory is stubbed; pre-populating the content-addressed
        // JPEG file verifies enrichCloudTrackFromFile extracted the exact embedded bytes and called artwork.store(bytes).
        File(artDir, "$expectedArtKey.jpg").writeBytes(byteArrayOf(1, 2, 3))

        val taggedFlac = tempFolder.newFile("drive_cached_seed.flac").apply {
            writeBytes(flacOut)
            assertTrue(
                AudioTagWriter.write(
                    this,
                    AudioTagPayload(
                        title = "Real Embedded Song",
                        artist = "Real Drive Artist",
                        album = "Real Drive Album",
                        genre = "Jazz",
                        trackNumber = 2,
                        artworkBytes = artBytes,
                    ),
                    "flac",
                ),
            )
        }

        val googleHttp = object : CloudHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse {
                if (url.contains("www.googleapis.com/drive/v3/files/drv_root") && !url.contains("q=")) {
                    return CloudHttpResponse(200, url, emptyMap(), """{"id":"drv_root","name":"Drive Folder","mimeType":"application/vnd.google-apps.folder"}""".toByteArray())
                }
                if (url.contains("www.googleapis.com/drive/v3/files")) {
                    val body = """{"files":[{"id":"gd_file_1","name":"song.flac","mimeType":"audio/flac"}]}"""
                    return CloudHttpResponse(200, url, emptyMap(), body.toByteArray())
                }
                return CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
            }

            override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse =
                CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

            override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 200
        }
        val gdClient = GoogleDriveClient(googleHttp, oauthTokenProvider = { "token" })
        val gdRegistry = CloudProviderRegistry(googleDriveClient = gdClient)
        val reader = object : MetadataReading {
            override suspend fun read(uri: Uri, fileNameHint: String?): TrackMetadata = TrackMetadata()
            override suspend fun read(file: File): TrackMetadata {
                val s = AudioTagWriter.readEmbeddedSummary(file)
                return TrackMetadata(
                    title = s.title,
                    artist = s.artist,
                    album = s.album,
                    albumArtist = s.albumArtist,
                    genre = s.genre,
                    trackNumber = s.trackNumber,
                    durationSec = if (file.extension == "flac") 198.0 else 0.0,
                    artwork = EmbeddedArtworkExtractor.extract(file).artwork,
                )
            }
        }
        val store = LibraryStore(
            directory = stateDir,
            appFolderDir = appDir,
            reader = reader,
            artwork = artCache,
            context = null,
            cloudHttpClient = googleHttp,
            googleDriveClient = gdClient,
            providerRegistry = gdRegistry,
            cloudAudioCache = audioCache,
        )
        assertTrue(store.addCloudRoot("https://drive.google.com/drive/folders/drv_root").isSuccess)
        val initialTrack = store.state.value.tracks.single()
        assertEquals(GoogleDriveClient.DEFAULT_ARTIST, initialTrack.artist)

        // Caching audio enriches the cloud track & survives subsequent cloud resyncs.
        audioCache.storeBytes(initialTrack.id, qualityId = null, fileExtension = "flac", bytes = taggedFlac.readBytes())
        val enriched = store.enrichCloudTrackFromFile(initialTrack.id, audioCache.latestCachedFile(initialTrack.id)!!)!!
        assertEquals("Real Embedded Song", enriched.title)
        assertEquals("Real Drive Artist", enriched.artist)
        assertEquals("Real Drive Album", enriched.album)
        assertEquals("Jazz", enriched.genre)
        assertEquals(198.0, enriched.durationSec, 0.01)
        assertEquals(expectedArtKey, enriched.artworkKey)

        // Cloud resync still retains enriched metadata & reads from audioCache if needed.
        store.rescan()
        store.awaitCloudSync(initialTrack.rootId)
        val afterResync = store.state.value.tracks.single()
        assertEquals("Real Drive Artist", afterResync.artist)
        assertEquals("Real Drive Album", afterResync.album)
        assertEquals(expectedArtKey, afterResync.artworkKey)

        // Placeholder "Google Drive" artist/root name must NEVER copy onto a local track during fallback/merge.
        val unknownFile = File(appDir, "plain.mp3").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00))
        }
        store.rescan()
        val localUnknown = store.state.value.tracks.first { !it.isCloud && it.fileName == "plain.mp3" }
        val placeholderCloudTrack = Track(
            id = "gd_placeholder",
            rootId = initialTrack.rootId,
            relativePath = "google_drive/plain.mp3",
            title = "Plain Title",
            artist = GoogleDriveClient.DEFAULT_ARTIST,
            album = "Drive Folder",
            albumArtist = GoogleDriveClient.DEFAULT_ARTIST,
            durationSec = 100.0,
            fileSize = 0L,
            modifiedEpochMs = 0L,
            dateAddedEpochMs = 1L,
            cloudSource = CloudSource.GOOGLE_DRIVE,
            remoteTrackId = "plain_id",
        )
        store.applyFallbackMetadataForSavedFile(
            savedFileName = unknownFile.name,
            sourceTrack = placeholderCloudTrack,
            targetDir = appDir,
        )
        val localAfter = store.state.value.tracks.first { it.id == localUnknown.id }
        assertEquals("Unknown Artist", localAfter.artist)
        assertNotEquals(GoogleDriveClient.DEFAULT_ARTIST, localAfter.artist)
    }

    @Test
    fun batchUpdateTrackMetadataUpdatesLibrarySkipsEphemeralAndWritesLocalAudioTags() = runBlocking {
        val stateDir = tempFolder.newFolder("batch_state")
        val appDir = tempFolder.newFolder("batch_app")
        val artCache = ArtworkCache(tempFolder.newFolder("batch_art"))
        val png = createValidPngBytes() + byteArrayOf(0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0)

        val flacFile = File(appDir, "one.flac").apply {
            val out = java.io.ByteArrayOutputStream()
            out.write("fLaC".toByteArray(Charsets.US_ASCII))
            out.write(0x80); out.write(0x00); out.write(0x00); out.write(34)
            out.write(ByteArray(34))
            out.write(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x01, 0x02))
            writeBytes(out.toByteArray())
            assertTrue(
                AudioTagWriter.write(
                    this,
                    AudioTagPayload(
                        title = "Track One",
                        artist = "Old Artist One",
                        album = "Old Album One",
                        genre = "Rock",
                        artworkBytes = png,
                    ),
                    "flac",
                ),
            )
        }
        val mp3File = File(appDir, "two.mp3").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00, 0x11, 0x22, 0x33, 0x44))
            assertTrue(
                AudioTagWriter.write(
                    this,
                    AudioTagPayload(
                        title = "Track Two",
                        artist = "Old Artist Two",
                        album = "Old Album Two",
                        genre = "Pop",
                        artworkBytes = png,
                    ),
                    "mp3",
                ),
            )
        }

        val biliHttp = object : CloudHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse =
                CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

            override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse =
                CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

            override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 200
        }
        val registry = CloudProviderRegistry()
        registry.registerExtensionProvider(
            object : CloudSourceProvider {
                override val providerId: String = "ext.bili"
                override val rootKind: RootKind = RootKind.CLOUD_EXTENSION
                override val cloudSourceSlug: String = CloudSource.BILIBILI
                override val displayName: String = "Bili"
                override val badgeLabel: String = "Bili"
                override val isBuiltin: Boolean = false
                override val supportsTree: Boolean = false
                override val supportsCookie: Boolean = false
                override val usesFlatCloudListing: Boolean = true
                override fun canHandle(rawInput: String): CloudUrlMatch? =
                    if (rawInput.startsWith("https://bili.test/")) CloudUrlMatch(rawInput.trim(), "bv1") else null

                override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult =
                    CloudPlaylistResult(
                        source = CloudSource.BILIBILI,
                        remoteId = "bv1",
                        title = "Cloud Mix",
                        tracks = listOf(CloudTrackMetadata("p1", "Cloud Song", "Old Cloud Artist", "Cloud Mix", 90.0)),
                    )

                override suspend fun resolveStream(
                    remoteTrackId: String,
                    relativePath: String?,
                    cookie: String?,
                    forceRefresh: Boolean,
                    quality: String?,
                    purpose: CloudStreamPurpose,
                ): ResolvedStream = ResolvedStream("https://bili.test/stream")
            },
        )

        val reader = object : MetadataReading {
            override suspend fun read(uri: Uri, fileNameHint: String?): TrackMetadata = TrackMetadata()
            override suspend fun read(file: File): TrackMetadata {
                val s = AudioTagWriter.readEmbeddedSummary(file)
                return TrackMetadata(
                    title = s.title,
                    artist = s.artist,
                    album = s.album,
                    albumArtist = s.albumArtist,
                    genre = s.genre,
                    durationSec = 120.0,
                    artwork = EmbeddedArtworkExtractor.extract(file).artwork,
                )
            }
        }
        val store = LibraryStore(
            directory = stateDir,
            appFolderDir = appDir,
            reader = reader,
            artwork = artCache,
            context = null,
            cloudHttpClient = biliHttp,
            providerRegistry = registry,
        )
        store.rescan()
        assertTrue(store.addCloudRoot("https://bili.test/1").isSuccess)

        val localTracks = store.state.value.tracks.filter { !it.isCloud }.associateBy { it.fileName }
        val flacTrack = localTracks.getValue("one.flac")
        val mp3Track = localTracks.getValue("two.mp3")
        val cloudTrack = store.state.value.tracks.single { it.isCloud }
        val ephemeral = Track(
            id = "ephem_1",
            rootId = "ephemeral",
            relativePath = "ephem_1",
            title = "Ephemeral",
            artist = "Ephem Artist",
            album = "Ephem Album",
            albumArtist = "Ephem Artist",
            durationSec = 60.0,
            fileSize = 0L,
            modifiedEpochMs = 0L,
            dateAddedEpochMs = 1L,
            isEphemeral = true,
        )
        store.registerEphemeralTrack(ephemeral)

        val updatedCount = store.batchUpdateTrackMetadata(
            trackIds = listOf(flacTrack.id, mp3Track.id, cloudTrack.id, ephemeral.id),
            artist = "Unified Artist",
            album = "Unified Album",
        )
        assertEquals(3, updatedCount)

        // Ephemeral track was skipped.
        assertEquals("Ephem Artist", store.track(ephemeral.id)!!.artist)

        val updatedFlacTrack = store.track(flacTrack.id)!!
        val updatedMp3Track = store.track(mp3Track.id)!!
        val updatedCloudTrack = store.track(cloudTrack.id)!!
        assertEquals("Track One", updatedFlacTrack.title)
        assertEquals("Rock", updatedFlacTrack.genre)
        assertEquals("Unified Artist", updatedFlacTrack.artist)
        assertEquals("Unified Artist", updatedFlacTrack.albumArtist)
        assertEquals("Unified Album", updatedFlacTrack.album)
        assertEquals(flacTrack.artworkKey, updatedFlacTrack.artworkKey)
        assertEquals("Unified Artist", updatedCloudTrack.artist)
        assertEquals("Unified Album", updatedCloudTrack.album)
        assertEquals("Cloud Song", updatedCloudTrack.title)

        // Artist-only update preserves album.
        assertEquals(1, store.batchUpdateTrackMetadata(listOf(mp3Track.id), artist = "Artist Only", album = null))
        assertEquals("Unified Album", store.track(mp3Track.id)!!.album)
        assertEquals("Artist Only", store.track(mp3Track.id)!!.artist)
        assertEquals("Artist Only", store.track(mp3Track.id)!!.albumArtist)

        // Physical local files preserve title, genre, and embedded artwork while reflecting artist/album updates.
        val flacSummary = AudioTagWriter.readEmbeddedSummary(flacFile)
        assertEquals("Track One", flacSummary.title)
        assertEquals("Rock", flacSummary.genre)
        assertEquals("Unified Artist", flacSummary.artist)
        assertEquals("Unified Artist", flacSummary.albumArtist)
        assertEquals("Unified Album", flacSummary.album)
        assertTrue(flacSummary.hasArtwork)

        val mp3Summary = AudioTagWriter.readEmbeddedSummary(mp3File)
        assertEquals("Track Two", mp3Summary.title)
        assertEquals("Pop", mp3Summary.genre)
        assertEquals("Artist Only", mp3Summary.artist)
        assertEquals("Artist Only", mp3Summary.albumArtist)
        assertEquals("Unified Album", mp3Summary.album)
        assertTrue(mp3Summary.hasArtwork)

        // Reload from disk verifies cloud override persistence.
        val reloaded = LibraryStore(
            directory = stateDir,
            appFolderDir = appDir,
            reader = reader,
            artwork = artCache,
            context = null,
            cloudHttpClient = biliHttp,
            providerRegistry = registry,
        )
        val reloadedCloud = reloaded.track(cloudTrack.id)!!
        assertEquals("Unified Artist", reloadedCloud.artist)
        assertEquals("Unified Artist", reloadedCloud.albumArtist)
        assertEquals("Unified Album", reloadedCloud.album)
    }

    private fun createValidPngBytes(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}
