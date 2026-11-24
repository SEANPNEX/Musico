package com.musico.extensions

import android.net.Uri
import androidx.media3.common.MediaMetadata
import com.musico.audio.AudioPlaybackEngine
import com.musico.audio.PlaybackError
import com.musico.audio.PlayerController
import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpResponse
import com.musico.cloud.CloudSourceException
import com.musico.cloud.CloudStreamDownloader
import com.musico.cloud.CloudStreamResolver
import com.musico.core.ArtworkCache
import com.musico.core.CloudThumbnailCache
import com.musico.core.MetadataReading
import com.musico.core.TrackMetadata
import com.musico.model.EqSettings
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.SearchField
import com.musico.model.Track
import com.musico.store.LibraryStore
import com.musico.store.PlayHistoryStore
import com.musico.store.QueueStore
import com.musico.ui.screens.CloudSearchController
import com.musico.ui.screens.CloudSearchSource
import com.musico.ui.screens.toEphemeralTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSearchV124Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUpMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private data class RecordedHttpCall(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String? = null,
    )

    private class MockHttpTransport(
        private val handler: suspend (RecordedHttpCall) -> CloudHttpResponse,
    ) : CloudHttpClient {
        val calls = CopyOnWriteArrayList<RecordedHttpCall>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): CloudHttpResponse {
            val call = RecordedHttpCall("GET", url, headers)
            calls.add(call)
            return handler(call)
        }

        override suspend fun postJson(
            url: String,
            jsonBody: String,
            headers: Map<String, String>,
        ): CloudHttpResponse {
            val call = RecordedHttpCall("POST", url, headers, jsonBody)
            calls.add(call)
            return handler(call)
        }

        override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 200
    }

    private class FakeAudioEngine : AudioPlaybackEngine {
        override var onTrackEnded: (() -> Unit)? = null
        override var onPlaybackError: ((String) -> Unit)? = null
        override var onDurationChanged: ((Double) -> Unit)? = null
        override var onIsPlayingChanged: ((Boolean) -> Unit)? = null

        override var currentTimeSec: Double = 0.0
        override var durationSec: Double = 120.0

        val streamCalls = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

        override fun load(uri: Uri, metadata: MediaMetadata?) = Unit
        override fun loadFile(file: File, metadata: MediaMetadata?) = Unit

        override fun loadStream(url: String, headers: Map<String, String>, metadata: MediaMetadata?) {
            streamCalls.add(url to headers)
        }

        override fun play() {
            onIsPlayingChanged?.invoke(true)
        }

        override fun pause() {
            onIsPlayingChanged?.invoke(false)
        }

        override fun seekTo(sec: Double) {
            currentTimeSec = sec
        }

        override fun unload() = Unit
        override fun applyEq(settings: EqSettings) = Unit
    }

    private fun stubReader() = object : MetadataReading {
        override suspend fun read(uri: Uri, fileNameHint: String?): TrackMetadata = TrackMetadata(durationSec = 180.0)
        override suspend fun read(file: File): TrackMetadata = TrackMetadata(durationSec = 180.0)
    }

    private fun zipEntries(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private suspend fun installExtension(
        extStore: ExtensionStore,
        manifestJson: String,
        scriptJs: String,
    ): InstalledExtension {
        val bundle = zipEntries(
            mapOf(
                "manifest.json" to manifestJson.toByteArray(Charsets.UTF_8),
                "index.js" to scriptJs.toByteArray(Charsets.UTF_8),
            )
        )
        val staged = extStore.stageFromBytes(bundle, "ext.musicoext", null).getOrThrow()
        return extStore.installStaged(staged)
    }

    private fun newSearchHarness(
        httpClient: CloudHttpClient = MockHttpTransport { CloudHttpResponse(404, it.url, emptyMap(), ByteArray(0)) },
        stateDir: File = tempFolder.newFolder("state"),
        shortTimeoutMs: Long = 2_000L,
        longTimeoutMs: Long = 5_000L,
    ): Harness {
        val docsDir = File(stateDir, "docs").apply { mkdirs() }
        val registry = CloudProviderRegistry()
        val extStore = ExtensionStore(
            stateDir = stateDir,
            httpClient = httpClient,
            providerRegistry = registry,
            appVersion = "1.2.4-test",
            shortTimeoutMs = shortTimeoutMs,
            longTimeoutMs = longTimeoutMs,
        )
        val libraryStore = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = stubReader(),
            artwork = ArtworkCache(File(stateDir, "art")),
            cloudHttpClient = httpClient,
            providerRegistry = registry,
        )
        val queueStore = QueueStore(stateDir)
        val historyStore = PlayHistoryStore(stateDir)
        libraryStore.referencedQueueTrackIdsProvider = { queueStore.allReferencedTrackIds() }
        queueStore.onReferencedTrackIdsChanged = { ids -> libraryStore.pruneEphemeralTracks(ids) }
        extStore.onAvailabilityChanged = { libraryStore.refreshAvailability() }
        return Harness(stateDir, docsDir, registry, extStore, libraryStore, queueStore, historyStore)
    }

    private data class Harness(
        val stateDir: File,
        val docsDir: File,
        val registry: CloudProviderRegistry,
        val extStore: ExtensionStore,
        val libraryStore: LibraryStore,
        val queueStore: QueueStore,
        val historyStore: PlayHistoryStore,
    )

    @Test
    fun manifestValidationAllowsSearchWithoutUrlPatternsAndDefaultsSearchKinds() = runBlocking {
        val harness = newSearchHarness()

        // Search + Stream + Cookie without urlPatterns or canHandle passes validation & staging.
        val searchOnly = installExtension(
            harness.extStore,
            manifestJson = """
                {
                  "apiVersion": 1,
                  "id": "ext.search.no.patterns",
                  "name": "Search Only Source",
                  "version": "1.0.0",
                  "capabilities": ["search", "stream", "cookie"],
                  "permissions": { "hosts": ["api.example.com"] },
                  "entry": "index.js"
                }
            """.trimIndent(),
            scriptJs = """
                function search(ctx, req) {
                  return { items: [] };
                }
                function resolveStream(ctx, req) {
                  return { url: "https://api.example.com/stream/" + req.remoteTrackId + ".mp3" };
                }
                module.exports = { search: search, resolveStream: resolveStream };
            """.trimIndent(),
        )
        assertEquals(listOf(ExtensionSearchKind.TRACK, ExtensionSearchKind.PLAYLIST, ExtensionSearchKind.ALBUM), searchOnly.manifest.effectiveSearchKinds)
        assertTrue(searchOnly.manifest.urlPatterns.isEmpty())

        // Manifest with searchKinds subset preserves explicit kinds.
        val manifestWithSubset = Json.decodeFromString<ExtensionManifest>(
            """
                {
                  "apiVersion": 1,
                  "id": "ext.subset.kinds",
                  "name": "Subset Kinds",
                  "version": "1.0.0",
                  "capabilities": ["search"],
                  "searchKinds": ["album", "track", "album"],
                  "permissions": { "hosts": ["api.example.com"] },
                  "entry": "index.js"
                }
            """.trimIndent()
        ).let(ExtensionManifestValidator::validate)
        assertEquals(listOf(ExtensionSearchKind.ALBUM, ExtensionSearchKind.TRACK), manifestWithSubset.effectiveSearchKinds)

        // Playlist/Tree capabilities STILL require urlPatterns.
        try {
            val playlistNoPatterns = zipEntries(
                mapOf(
                    "manifest.json" to """
                        {
                          "apiVersion": 1,
                          "id": "ext.bad.playlist",
                          "name": "Bad Playlist",
                          "version": "1.0.0",
                          "capabilities": ["playlist", "search"],
                          "permissions": { "hosts": ["api.example.com"] },
                          "entry": "index.js"
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                    "index.js" to """
                        function canHandle(input) { return { matched: false }; }
                        function fetchCollection() { return { remoteId: "1", title: "T", tracks: [] }; }
                        function search() { return { items: [] }; }
                        module.exports = { canHandle: canHandle, fetchCollection: fetchCollection, search: search };
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                )
            )
            harness.extStore.stageFromBytes(playlistNoPatterns, "bad.musicoext", null).getOrThrow()
            fail("Expected urlPatterns validation error for playlist capability")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("urlPatterns"))
        }

        // SEARCH manifest missing search export is rejected.
        try {
            val missingSearchExport = zipEntries(
                mapOf(
                    "manifest.json" to """
                        {
                          "apiVersion": 1,
                          "id": "ext.missing.search",
                          "name": "Missing Export",
                          "version": "1.0.0",
                          "capabilities": ["search"],
                          "permissions": { "hosts": ["api.example.com"] },
                          "entry": "index.js"
                        }
                    """.trimIndent().toByteArray(Charsets.UTF_8),
                    "index.js" to "var foo = 1;".toByteArray(Charsets.UTF_8),
                )
            )
            harness.extStore.stageFromBytes(missingSearchExport, "missing.musicoext", null).getOrThrow()
            fail("Expected missing search() export rejection")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("search"))
        }
    }

    @Test
    fun quickJsSearchPassesRequestEnforcesHostAllowlistSupportsTimeoutAndCancellation() = runBlocking {
        val http = MockHttpTransport { call ->
            CloudHttpResponse(
                statusCode = 200,
                finalUrl = call.url,
                headers = mapOf("Content-Type" to listOf("application/json")),
                bodyBytes = """{"ok":true}""".toByteArray(Charsets.UTF_8),
            )
        }
        val harness = newSearchHarness(httpClient = http, shortTimeoutMs = 300L)

        installExtension(
            harness.extStore,
            manifestJson = """
                {
                  "apiVersion": 1,
                  "id": "ext.search.demo",
                  "name": "Demo Search",
                  "badgeLabel": "Demo",
                  "version": "1.0.0",
                  "capabilities": ["search", "playlist", "stream", "cookie"],
                  "urlPatterns": ["^https://api\\.demo\\.test/collection/.*"],
                  "searchKinds": ["track", "album", "playlist"],
                  "permissions": { "hosts": ["api.demo.test"] },
                  "entry": "index.js"
                }
            """.trimIndent(),
            scriptJs = """
                function canHandle(input) {
                  var url = (input && (input.extractedUrl || input.rawText)) || "";
                  return url.indexOf("https://api.demo.test/collection/") === 0 ? { matched: true, normalizedUrl: url } : { matched: false };
                }
                function fetchCollection(ctx, req) {
                  return { remoteId: "col1", title: "Col 1", tracks: [] };
                }
                function resolveStream(ctx, req) {
                  return { url: "https://api.demo.test/audio/" + req.remoteTrackId + ".m4a" };
                }
                async function search(ctx, req) {
                  if (req.query === "disallowed_host") {
                    await ctx.http.request({ method: "GET", url: "https://evil.invalid/steal" });
                  }
                  if (req.query === "hang") {
                    var i = 0;
                    while (true) { i++; }
                  }
                  await ctx.http.request({ method: "GET", url: "https://api.demo.test/search?q=" + encodeURIComponent(req.query) + "&kind=" + req.kind });
                  return {
                    items: [
                      {
                        kind: "track",
                        remoteTrackId: "t1",
                        title: "Track One (" + req.kind + ")",
                        artist: "Artist A",
                        album: "Album A",
                        durationSec: 215,
                        artworkUrl: "https://api.demo.test/cover.jpg"
                      },
                      {
                        kind: "album",
                        remoteId: "alb1",
                        title: "Album One",
                        artist: "Artist A",
                        sourceUrl: "https://api.demo.test/collection/alb1",
                        trackCount: 8,
                        artworkUrl: "https://api.demo.test/album.jpg"
                      }
                    ],
                    nextCursor: req.cookie ? "cursor-authed" : "cursor-anon"
                  };
                }
                module.exports = {
                  canHandle: canHandle,
                  fetchCollection: fetchCollection,
                  resolveStream: resolveStream,
                  search: search
                };
            """.trimIndent(),
        )

        harness.extStore.updateCookie("ext.search.demo", "sess=secret123")
        val page = harness.extStore.search(
            extensionId = "ext.search.demo",
            query = "bocchi",
            kind = CloudSearchFilterKind.ALL,
            limit = 20,
            cursor = null,
        )
        assertEquals("cursor-authed", page.nextCursor)
        assertEquals(2, page.items.size)
        val firstTrack = page.items[0] as ExtSearchResultItem.TrackItem
        assertEquals("t1", firstTrack.remoteTrackId)
        assertEquals("Track One (all)", firstTrack.title)
        val albumItem = page.items[1] as ExtSearchResultItem.CollectionItem
        assertEquals(ExtensionSearchKind.ALBUM, albumItem.kind)
        assertEquals("https://api.demo.test/collection/alb1", albumItem.sourceUrl)

        // Disallowed host blocked by permissions.hosts.
        try {
            harness.extStore.search("ext.search.demo", "disallowed_host")
            fail("Expected PERMISSION_DENIED")
        } catch (expected: CloudSourceException) {
            assertEquals(CloudErrorCode.PERMISSION_DENIED, expected.code)
        }

        // Timeout triggers promptly (~300ms shortTimeoutMs).
        try {
            harness.extStore.search("ext.search.demo", "hang")
            fail("Expected timeout error")
        } catch (expected: CloudSourceException) {
            assertTrue(expected.message!!.contains("timed out", ignoreCase = true))
        }

        // Coroutine cancellation interrupts busy loop promptly and throws CancellationException.
        val job = async {
            harness.extStore.search("ext.search.demo", "hang")
        }
        delay(40L)
        job.cancel()
        try {
            job.await()
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
            // Expected
        }
    }

    @Test
    fun cookieAuthPersistenceRedactionNoCapabilityIsolationAndStreamPrecedence() = runBlocking {
        val harness = newSearchHarness()

        // 1. Extension WITH COOKIE capability: persists cookie, redacts logs, sends cookie to resolveStream & search.
        installExtension(
            harness.extStore,
            manifestJson = """
                {
                  "apiVersion": 1,
                  "id": "ext.cookie.yes",
                  "name": "Authed Source",
                  "version": "1.0.0",
                  "capabilities": ["search", "stream", "cookie", "playlist"],
                  "urlPatterns": ["^https://authed\\.test/.*"],
                  "permissions": { "hosts": ["authed.test"] },
                  "entry": "index.js"
                }
            """.trimIndent(),
            scriptJs = """
                function canHandle(input) {
                  var url = (input && (input.extractedUrl || input.rawText)) || "";
                  return { matched: true, normalizedUrl: url };
                }
                function fetchCollection(ctx, req) { return { remoteId: "c1", title: "C1", tracks: [] }; }
                function search(ctx, req) {
                  ctx.log.info("using cookie=" + req.cookie);
                  return { items: [{ kind: "track", remoteTrackId: "t1", title: req.cookie || "NO_COOKIE" }] };
                }
                function resolveStream(ctx, req) {
                  ctx.log.info("stream cookie=" + req.cookie);
                  return { url: "https://authed.test/play?c=" + encodeURIComponent(req.cookie || "") };
                }
                module.exports = { canHandle: canHandle, fetchCollection: fetchCollection, search: search, resolveStream: resolveStream };
            """.trimIndent(),
        )
        harness.extStore.updateCookie("ext.cookie.yes", "SECRET_COOKIE_VALUE")

        val searchPage = harness.extStore.search("ext.cookie.yes", "test")
        assertEquals("SECRET_COOKIE_VALUE", (searchPage.items.single() as ExtSearchResultItem.TrackItem).title)

        // Cookie persists across reload from disk.
        val reloadedRegistry = CloudProviderRegistry()
        val reloadedStore = ExtensionStore(
            stateDir = harness.stateDir,
            httpClient = MockHttpTransport { CloudHttpResponse(404, it.url, emptyMap(), ByteArray(0)) },
            providerRegistry = reloadedRegistry,
        )
        assertEquals("SECRET_COOKIE_VALUE", reloadedStore.cookieForExtension("ext.cookie.yes"))

        // Cookie never leaks raw string into ctx.log or logs.json!
        val logEntries = harness.extStore.logsFor("ext.cookie.yes")
        assertTrue(logEntries.any { it.message.contains("[REDACTED]") })
        assertFalse(logEntries.any { it.message.contains("SECRET_COOKIE_VALUE") })
        val rawLogsFile = File(File(harness.stateDir, ExtensionStore.EXTENSIONS_DIR_NAME), "ext.cookie.yes/${ExtensionStore.LOGS_FILE}")
        assertTrue(rawLogsFile.exists())
        assertFalse(rawLogsFile.readText(Charsets.UTF_8).contains("SECRET_COOKIE_VALUE"))

        // CloudStreamResolver: ephemeral track (root = null) uses extension-level cookie; root-linked prefers root.cookie.
        val resolver = CloudStreamResolver(providerRegistry = reloadedRegistry)
        val ephemeralTrack = ExtSearchResultItem.TrackItem(
            remoteTrackId = "trk_99",
            title = "Song 99",
        ).toEphemeralTrack("ext.cookie.yes")
        val resolvedEphemeral = resolver.resolve(ephemeralTrack, root = null)
        assertTrue(resolvedEphemeral.url.contains("c=SECRET_COOKIE_VALUE"))

        val rootWithOwnCookie = LibraryRoot(
            id = "root_with_cookie",
            name = "Custom Root",
            kind = RootKind.CLOUD_EXTENSION,
            sourceUrl = "https://authed.test/pl/1",
            cookie = "ROOT_OVERRIDE_COOKIE",
            providerId = "ext.ext.cookie.yes",
        )
        val rootTrack = ephemeralTrack.copy(id = "local_cloud_id", rootId = rootWithOwnCookie.id, isEphemeral = false)
        val resolvedRootOverride = resolver.resolve(rootTrack, root = rootWithOwnCookie)
        assertTrue(resolvedRootOverride.url.contains("c=ROOT_OVERRIDE_COOKIE"))

        val rootWithoutCookie = rootWithOwnCookie.copy(cookie = null)
        val resolvedRootFallback = resolver.resolve(rootTrack, root = rootWithoutCookie)
        assertTrue(resolvedRootFallback.url.contains("c=SECRET_COOKIE_VALUE"))

        // 2. Extension WITHOUT COOKIE capability receives null cookie even if state.json were tampered with.
        installExtension(
            harness.extStore,
            manifestJson = """
                {
                  "apiVersion": 1,
                  "id": "ext.cookie.no",
                  "name": "No Cookie Source",
                  "version": "1.0.0",
                  "capabilities": ["search", "stream"],
                  "permissions": { "hosts": ["nocookie.test"] },
                  "entry": "index.js"
                }
            """.trimIndent(),
            scriptJs = """
                function search(ctx, req) {
                  return { items: [{ kind: "track", remoteTrackId: "x1", title: req.cookie === null ? "NULL_COOKIE" : "LEAKED" }] };
                }
                function resolveStream(ctx, req) {
                  return { url: "https://nocookie.test/play/" + (req.cookie === null ? "ok" : "bad") };
                }
                module.exports = { search: search, resolveStream: resolveStream };
            """.trimIndent(),
        )
        harness.extStore.updateCookie("ext.cookie.no", "SHOULD_NOT_SAVE")
        assertNull(harness.extStore.cookieForExtension("ext.cookie.no"))
        val stateJson = File(File(harness.stateDir, ExtensionStore.EXTENSIONS_DIR_NAME), "ext.cookie.no/${ExtensionStore.STATE_FILE}")
        stateJson.writeText(stateJson.readText().replace("\"cookie\":null", "\"cookie\":\"INJECTED_SECRET\""))
        val resNoCookie = harness.extStore.search("ext.cookie.no", "hello")
        assertEquals("NULL_COOKIE", (resNoCookie.items.single() as ExtSearchResultItem.TrackItem).title)
    }

    @Test
    fun ephemeralTrackRegistryIsolationAutoPruningAndHistoryStatsExclusion() = runBlocking {
        val harness = newSearchHarness()
        val localFile = File(harness.docsDir, "local_song.mp3").apply {
            writeText("audio-bytes", Charsets.UTF_8)
        }
        harness.libraryStore.rescan()
        val localTrack = harness.libraryStore.state.value.tracks.single()

        val eph1 = ExtSearchResultItem.TrackItem(
            remoteTrackId = "r1",
            title = "Ephemeral Echo",
            artist = "Cloud Singer",
            album = "Cloud Album",
            durationSec = 200.0,
            artworkUrl = "https://cdn.example.com/art1.jpg",
        ).toEphemeralTrack("ext.demo")
        val eph2 = ExtSearchResultItem.TrackItem(
            remoteTrackId = "r2",
            title = "Ephemeral Breeze",
            artist = "Cloud Singer",
        ).toEphemeralTrack("ext.demo")

        assertEquals("extsearch:ext.demo:r1", eph1.id)
        assertTrue(eph1.isEphemeral)
        assertTrue(eph1.isCloud)

        harness.libraryStore.registerEphemeralTracks(listOf(eph1, eph2))

        // Resolved via LibraryStore.track / tracks, NEVER exposed in local library tracks, albums, artists, or search.
        assertEquals("Ephemeral Echo", harness.libraryStore.track(eph1.id)?.title)
        assertEquals(listOf(eph1.id, eph2.id), harness.libraryStore.tracks(listOf(eph1.id, eph2.id)).map { it.id })
        assertEquals(listOf(localTrack.id), harness.libraryStore.state.value.tracks.map { it.id })
        assertTrue(harness.libraryStore.search("Ephemeral", SearchField.KEYWORD).isEmpty())
        assertFalse(harness.libraryStore.state.value.artists.any { it == "Cloud Singer" })
        assertFalse(harness.libraryStore.state.value.albums.any { it.title == "Cloud Album" })

        // Queue references only eph1; saving queue prunes unreferenced eph2 cleanly and persists eph1 across reload.
        val queueId = harness.queueStore.active.id
        harness.queueStore.update(queueId) { it.replace(listOf(eph1.id), 0) }
        assertNotNull(harness.libraryStore.track(eph1.id))
        assertNull(harness.libraryStore.track(eph2.id))

        val reloadedLibrary = LibraryStore(
            directory = harness.stateDir,
            appFolderDir = harness.docsDir,
            reader = stubReader(),
            artwork = ArtworkCache(File(harness.stateDir, "art")),
        )
        assertNotNull(reloadedLibrary.track(eph1.id))
        assertNull(reloadedLibrary.track(eph2.id))
        assertEquals(listOf(localTrack.id), reloadedLibrary.state.value.tracks.map { it.id })

        // PlayHistoryStore + PlayerController completely ignore ephemeral tracks!
        harness.historyStore.recordPlay(eph1.id, 1_700_000_000_000L)
        harness.historyStore.markStarted(eph1.id, 1_700_000_000_000L)
        harness.historyStore.addListening(45.0, eph1)
        assertEquals(0, harness.historyStore.count(eph1.id))
        assertNull(harness.historyStore.lastPlayed(eph1.id))
        assertEquals(0, harness.historyStore.state.value.totalPlays)
        assertEquals(0.0, harness.historyStore.state.value.totalSeconds, 0.001)
        assertTrue(harness.historyStore.neverPlayed(listOf(localTrack, eph1)).none { it.id == eph1.id })

        // Removing eph1 from queue prunes it from ephemeralTracks immediately.
        harness.queueStore.update(queueId) { it.replace(emptyList(), 0) }
        assertNull(harness.libraryStore.track(eph1.id))
        assertTrue(harness.libraryStore.ephemeralTrackIds().isEmpty())
    }

    @Test
    fun playerControllerSetsExtensionUnavailableErrorAndSkipsHistoryForEphemeralTracks() = runBlocking {
        val harness = newSearchHarness()
        val ext = installExtension(
            harness.extStore,
            manifestJson = """
                {
                  "apiVersion": 1,
                  "id": "ext.play.test",
                  "name": "Play Test",
                  "version": "1.0.0",
                  "capabilities": ["search", "stream"],
                  "permissions": { "hosts": ["cdn.play.test"] },
                  "entry": "index.js"
                }
            """.trimIndent(),
            scriptJs = """
                function search(ctx, req) { return { items: [] }; }
                function resolveStream(ctx, req) {
                  return { url: "https://cdn.play.test/tracks/" + req.remoteTrackId + ".mp3" };
                }
                module.exports = { search: search, resolveStream: resolveStream };
            """.trimIndent(),
        )
        val engine = FakeAudioEngine()
        val player = PlayerController(
            context = null,
            library = harness.libraryStore,
            queues = harness.queueStore,
            history = harness.historyStore,
            engine = engine,
            settingsFile = File(harness.stateDir, "settings.json"),
            cloudResolver = CloudStreamResolver(providerRegistry = harness.registry),
        )
        val ephTrack = ExtSearchResultItem.TrackItem(
            remoteTrackId = "song1",
            title = "Ephemeral Song",
            artist = "Remote Band",
            durationSec = 100.0,
        ).toEphemeralTrack(ext.id)
        harness.libraryStore.registerEphemeralTrack(ephTrack)
        try {
            player.playInNamedQueue("Search (Play Test): song1", listOf(ephTrack.id), startAt = 0)
            withTimeout(5_000L) {
                player.state.first { !it.isLoadingCloud && (engine.streamCalls.isNotEmpty() || it.playbackError != null) }
            }
            assertEquals(1, engine.streamCalls.size)
            assertEquals("https://cdn.play.test/tracks/song1.mp3", engine.streamCalls.single().first)

            // Simulate track ending -> play count / history must STILL remain zero for ephemeral tracks!
            engine.onTrackEnded?.invoke()
            assertEquals(0, harness.historyStore.count(ephTrack.id))
            assertNull(harness.historyStore.lastPlayed(ephTrack.id))
            assertEquals(0, harness.historyStore.state.value.totalPlays)

            // Disable extension -> playing ephemeral track emits PlaybackError.ExtensionUnavailable.
            harness.extStore.setEnabled(ext.id, false)
            assertFalse(harness.libraryStore.isAvailable(ephTrack))
            player.play(listOf(ephTrack.id), startAt = 0)
            withTimeout(5_000L) {
                player.state.first { !it.isLoadingCloud && it.playbackError != null }
            }
            assertEquals(PlaybackError.ExtensionUnavailable, player.state.value.playbackError)
        } finally {
            player.shutdown()
        }
    }

    @Test
    fun singleTrackSaveDownloadsWithoutCloudSidecarAndRescansIntoLibrary() = runBlocking {
        val harness = newSearchHarness()
        val ext = installExtension(
            harness.extStore,
            manifestJson = """
                {
                  "apiVersion": 1,
                  "id": "ext.save.test",
                  "name": "Save Source",
                  "version": "1.0.0",
                  "capabilities": ["search", "stream", "cookie"],
                  "permissions": { "hosts": ["media.save.test"] },
                  "entry": "index.js"
                }
            """.trimIndent(),
            scriptJs = """
                function search(ctx, req) { return { items: [] }; }
                function resolveStream(ctx, req) {
                  return {
                    url: "https://media.save.test/" + req.remoteTrackId + ".mp3",
                    headers: { "X-Cookie": req.cookie || "" },
                    fileExtension: "mp3"
                  };
                }
                module.exports = { search: search, resolveStream: resolveStream };
            """.trimIndent(),
        )
        harness.extStore.updateCookie(ext.id, "ck=save99")

        val downloadedUrls = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
        val fakeDownloader = object : CloudStreamDownloader {
            override suspend fun download(url: String, headers: Map<String, String>, output: OutputStream) {
                downloadedUrls.add(url to headers)
                output.write("FAKE_MP3_STREAM".toByteArray(Charsets.UTF_8))
            }
        }
        val exporter = CloudExporter(
            context = null,
            cloudResolver = CloudStreamResolver(providerRegistry = harness.registry),
            downloader = fakeDownloader,
            providerRegistry = harness.registry,
        )

        val ephTrack = ExtSearchResultItem.TrackItem(
            remoteTrackId = "s_01",
            title = "Downloaded Star",
            artist = "Cloud Artist",
            album = "Starlight",
            durationSec = 195.0,
        ).toEphemeralTrack(ext.id)

        val savedFileName = exporter.exportTrack(ephTrack, root = null, targetDir = harness.docsDir)
        assertTrue(savedFileName.endsWith(".mp3"))
        assertTrue(File(harness.docsDir, savedFileName).exists())
        // Must NEVER create .musico-cloud.json for single-track export.
        assertFalse(File(harness.docsDir, CloudExporter.MANIFEST_FILE_NAME).exists())
        assertEquals("https://media.save.test/s_01.mp3", downloadedUrls.single().first)
        assertEquals("ck=save99", downloadedUrls.single().second["X-Cookie"])

        harness.libraryStore.rescan()
        val savedLocal = harness.libraryStore.state.value.tracks.single()
        assertFalse(savedLocal.isEphemeral)
        assertFalse(savedLocal.isCloud)
        assertEquals(savedFileName, savedLocal.relativePath)
    }

    @Test
    fun thumbnailCacheAllowsOnlyHttpsAndRedirectsAndNeverWritesArtworkDiskCache() = runBlocking {
        val artworkDir = tempFolder.newFolder("artwork_disk")
        val artworkCache = ArtworkCache(artworkDir)
        val http = MockHttpTransport { call ->
            when (call.url) {
                "https://img.example.com/ok.jpg" -> CloudHttpResponse(
                    statusCode = 200,
                    finalUrl = call.url,
                    headers = emptyMap(),
                    bodyBytes = byteArrayOf(1, 2, 3),
                )
                "https://img.example.com/redir-http" -> CloudHttpResponse(
                    statusCode = 302,
                    finalUrl = call.url,
                    headers = mapOf("Location" to listOf("http://insecure.example.com/leak.jpg")),
                    bodyBytes = ByteArray(0),
                )
                "https://img.example.com/redir-https" -> CloudHttpResponse(
                    statusCode = 302,
                    finalUrl = call.url,
                    headers = mapOf("Location" to listOf("https://cdn2.example.com/final.png")),
                    bodyBytes = ByteArray(0),
                )
                "https://cdn2.example.com/final.png" -> CloudHttpResponse(
                    statusCode = 200,
                    finalUrl = call.url,
                    headers = emptyMap(),
                    bodyBytes = byteArrayOf(9, 8, 7),
                )
                else -> CloudHttpResponse(404, call.url, emptyMap(), ByteArray(0))
            }
        }
        val decodedPayloads = CopyOnWriteArrayList<ByteArray>()
        val thumbCache = CloudThumbnailCache(
            httpClient = http,
            bitmapDecoder = { bytes ->
                decodedPayloads.add(bytes)
                null
            },
        )

        // Rejects non-https schemes immediately without contacting HTTP transport.
        assertNull(thumbCache.load("http://img.example.com/plain.jpg"))
        assertNull(thumbCache.load("file:///etc/passwd"))
        assertNull(thumbCache.load("data:image/png;base64,AAAA"))
        assertNull(thumbCache.load("javascript:alert(1)"))
        assertTrue(http.calls.isEmpty())

        // Redirect from https -> http is blocked before requesting insecure target.
        assertNull(thumbCache.load("https://img.example.com/redir-http"))
        assertEquals(listOf("https://img.example.com/redir-http"), http.calls.map { it.url })

        // https -> https redirect succeeds and reaches bitmapDecoder cleanly.
        http.calls.clear()
        thumbCache.load("https://img.example.com/redir-https")
        assertEquals(
            listOf("https://img.example.com/redir-https", "https://cdn2.example.com/final.png"),
            http.calls.map { it.url },
        )
        assertEquals(1, decodedPayloads.size)

        // ArtworkCache directory remains completely empty (never writes browsed thumbnails to disk).
        assertEquals(0, artworkCache.let { artworkDir.list()?.size ?: 0 })
    }

    @Test
    fun cloudSearchControllerStateMachineBuiltinsExclusionPaginationDedupAndExtensionDisableReset() = runBlocking {
        val harness = newSearchHarness()
        val extScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val engine = FakeAudioEngine()
        val player = PlayerController(
            context = null,
            library = harness.libraryStore,
            queues = harness.queueStore,
            history = harness.historyStore,
            engine = engine,
            settingsFile = File(harness.stateDir, "settings.json"),
            cloudResolver = CloudStreamResolver(providerRegistry = harness.registry),
        )

        try {
            val controller = CloudSearchController(
                extensionStore = harness.extStore,
                scope = extScope,
                libraryStore = harness.libraryStore,
                playerController = player,
            )

            // 1. With 0 search extensions, availableSources is empty (UI renders zero switcher) & defaults to Local.
            assertTrue(controller.uiState.value.availableSources.isEmpty())
            assertEquals(CloudSearchSource.Local, controller.uiState.value.selectedSource)

            // Built-in providers (YouTube, Bilibili, Google Drive) are NEVER exposed as search sources.
            assertTrue(harness.registry.providers.value.any { it.isBuiltin })
            assertTrue(harness.extStore.activeSearchExtensions().isEmpty())

            // Install two search extensions.
            val ext1Deferred = CompletableDeferred<Unit>()
            installExtension(
                harness.extStore,
                manifestJson = """
                    {
                      "apiVersion": 1,
                      "id": "ext.source.one",
                      "name": "Source One Full Name",
                      "badgeLabel": "Src1",
                      "version": "1.0.0",
                      "capabilities": ["search", "playlist", "stream"],
                      "urlPatterns": ["^https://src1\\.test/.*"],
                      "searchKinds": ["track", "album"],
                      "permissions": { "hosts": ["src1.test"] },
                      "entry": "index.js"
                    }
                """.trimIndent(),
                scriptJs = """
                    function canHandle(input) {
                      var url = (input && (input.extractedUrl || input.rawText)) || "";
                      return { matched: true, normalizedUrl: url };
                    }
                    function fetchCollection(ctx, req) {
                      return {
                        remoteId: "alb_src1",
                        title: "Src1 Album",
                        tracks: [{ remoteTrackId: "col_t1", title: "Col Song 1", artist: "A", durationSec: 150 }]
                      };
                    }
                    function resolveStream(ctx, req) {
                      return { url: "https://src1.test/audio/" + req.remoteTrackId + ".mp3" };
                    }
                    function search(ctx, req) {
                      ctx.log.info("search:" + req.query + ":" + req.cursor);
                      if (req.cursor === null || req.cursor === undefined) {
                        return {
                          items: [
                            { kind: "track", remoteTrackId: "t1", title: "Alpha", artist: "Art1", durationSec: 120 },
                            { kind: "album", remoteId: "alb_src1", title: "Src1 Album", sourceUrl: "https://src1.test/alb/1" }
                          ],
                          nextCursor: "page2"
                        };
                      }
                      if (req.cursor === "page2") {
                        return {
                          items: [
                            { kind: "track", remoteTrackId: "t1", title: "Alpha Duplicate", artist: "Art1", durationSec: 120 },
                            { kind: "track", remoteTrackId: "t2", title: "Beta", artist: "Art2", durationSec: 180 }
                          ],
                          nextCursor: "page2" // nextCursor == prevCursor guard!
                        };
                      }
                      return { items: [] };
                    }
                    module.exports = {
                      canHandle: canHandle,
                      fetchCollection: fetchCollection,
                      resolveStream: resolveStream,
                      search: search
                    };
                """.trimIndent(),
            )
            installExtension(
                harness.extStore,
                manifestJson = """
                    {
                      "apiVersion": 1,
                      "id": "ext.source.two",
                      "name": "Source Two",
                      "version": "1.0.0",
                      "capabilities": ["search"],
                      "searchKinds": ["track"],
                      "permissions": { "hosts": ["src2.test"] },
                      "entry": "index.js"
                    }
                """.trimIndent(),
                scriptJs = """
                    function search(ctx, req) {
                      return {
                        items: [{ kind: "track", remoteTrackId: "s2_1", title: "Two Only (" + req.kind + ")" }]
                      };
                    }
                    module.exports = { search: search };
                """.trimIndent(),
            )
            ext1Deferred.complete(Unit)

            controller.syncAvailableSources()
            assertEquals(2, controller.uiState.value.availableSources.size)
            assertEquals(CloudSearchSource.Local, controller.uiState.value.selectedSource)

            // 2. Switching to Src1 while query is blank does NOT search; typing in Cloud mode does NOT auto-submit!
            controller.selectSource(CloudSearchSource.Extension("ext.source.one"))
            assertTrue(controller.uiState.value.isCloudMode)
            assertEquals(3, controller.uiState.value.availableKindFilters.size) // ALL, TRACK, ALBUM
            controller.onQueryChanged("yoasobi")
            assertFalse(controller.uiState.value.hasSubmitted)
            assertTrue(harness.extStore.logsFor("ext.source.one").isEmpty())

            // 3. Explicit submit runs search, returns page1; Load More deduplicates (kind, id) & stops on nextCursor == prevCursor.
            controller.submitSearch()
            withTimeout(5_000L) {
                controller.uiState.first { !it.isSearching && !it.isLoadingMore }
            }
            assertTrue(controller.uiState.value.hasSubmitted)
            assertEquals(2, controller.uiState.value.items.size)
            assertEquals("page2", controller.uiState.value.nextCursor)
            assertTrue(controller.uiState.value.canLoadMore)

            controller.loadMore()
            withTimeout(5_000L) {
                controller.uiState.first { !it.isSearching && !it.isLoadingMore }
            }
            val afterPage2 = controller.uiState.value
            assertEquals(3, afterPage2.items.size) // t1 deduplicated!
            assertNull(afterPage2.nextCursor) // cleared because nextCursor == "page2" == prevCursor!
            assertFalse(afterPage2.canLoadMore)

            // 4. Tap track result -> registers ephemeral tracks & queues in "Search (Src1): yoasobi" starting at tapped index.
            val secondTrack = afterPage2.trackResults[1]
            controller.playTrackResult(secondTrack)
            withTimeout(5_000L) {
                player.state.first { !it.isLoadingCloud }
            }
            assertEquals("Search (Src1): yoasobi", harness.queueStore.active.name)
            assertEquals(listOf("extsearch:ext.source.one:t1", "extsearch:ext.source.one:t2"), harness.queueStore.active.trackIds)
            assertEquals(1, harness.queueStore.active.currentIndex)
            assertTrue(harness.libraryStore.track("extsearch:ext.source.one:t2")?.isEphemeral == true)

            // 5. Switching to another cloud source while query is non-empty triggers search once on the new source.
            controller.selectSource(CloudSearchSource.Extension("ext.source.two"))
            withTimeout(5_000L) {
                controller.uiState.first { !it.isSearching && !it.isLoadingMore }
            }
            assertEquals("ext.source.two", controller.uiState.value.activeExtension?.id)
            assertTrue(controller.uiState.value.availableKindFilters.isEmpty()) // single searchKind hides kind chips!
            val twoItem = controller.uiState.value.items.single() as ExtSearchResultItem.TrackItem
            assertEquals("Two Only (track)", twoItem.title)

            // 6. Disabling active extension resets source back to Local and clears cloud results.
            harness.extStore.setEnabled("ext.source.two", false)
            controller.syncAvailableSources()
            assertEquals(CloudSearchSource.Local, controller.uiState.value.selectedSource)
            assertFalse(controller.uiState.value.isCloudMode)
            assertTrue(controller.uiState.value.items.isEmpty())

            // 7. Re-select Src1 and verify resetToLocal() (quick-search shortcut) resets to Local cleanly.
            controller.selectSource(CloudSearchSource.Extension("ext.source.one"))
            assertTrue(controller.uiState.value.isCloudMode)
            controller.resetToLocal()
            assertEquals(CloudSearchSource.Local, controller.uiState.value.selectedSource)
            assertTrue(controller.uiState.value.items.isEmpty())

            // 8. Collection preview + Add as Cloud Folder adds collection root cleanly.
            val preview = harness.extStore.fetchCollectionPreview("ext.source.one", "https://src1.test/alb/1")
            assertEquals(1, preview.tracks.size)
            val addRootResult = harness.libraryStore.addCloudRoot("https://src1.test/alb/1", providerIdHint = "ext.ext.source.one")
            assertTrue(addRootResult.isSuccess)
            assertTrue(harness.libraryStore.state.value.roots.any { it.sourceUrl == "https://src1.test/alb/1" })
        } finally {
            player.shutdown()
            extScope.cancel()
        }
    }

    @Test
    fun switchingBackToLocalCancelsActiveCloudSearch() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val requestCancelled = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<CloudHttpResponse>()
        val slowHttp = MockHttpTransport { call ->
            if (call.url.startsWith("https://slow.test/")) {
                requestStarted.complete(Unit)
                try {
                    gate.await()
                } catch (c: CancellationException) {
                    requestCancelled.complete(Unit)
                    throw c
                }
            } else {
                CloudHttpResponse(404, call.url, emptyMap(), ByteArray(0))
            }
        }
        val harness = newSearchHarness(httpClient = slowHttp, shortTimeoutMs = 10_000L)
        val extScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            installExtension(
                harness.extStore,
                manifestJson = """
                    {
                      "apiVersion": 1,
                      "id": "ext.slow.search",
                      "name": "Slow Search",
                      "version": "1.0.0",
                      "capabilities": ["search"],
                      "permissions": { "hosts": ["slow.test"] },
                      "entry": "index.js"
                    }
                """.trimIndent(),
                scriptJs = """
                    async function search(ctx, req) {
                      var resp = await ctx.http.request({
                        method: "GET",
                        url: "https://slow.test/search?q=" + encodeURIComponent(req.query)
                      });
                      return JSON.parse(resp.text);
                    }
                    module.exports = { search: search };
                """.trimIndent(),
            )
            val controller = CloudSearchController(
                extensionStore = harness.extStore,
                scope = extScope,
                libraryStore = harness.libraryStore,
            )
            controller.syncAvailableSources()
            controller.selectSource(CloudSearchSource.Extension("ext.slow.search"))
            controller.onQueryChanged("ambient")
            controller.submitSearch()

            requestStarted.await()
            assertTrue(controller.uiState.value.isSearching)

            controller.selectSource(CloudSearchSource.Local)
            requestCancelled.await()
            assertEquals(CloudSearchSource.Local, controller.uiState.value.selectedSource)
            assertFalse(controller.uiState.value.isSearching)
            assertFalse(controller.uiState.value.isCloudMode)
            assertNull(controller.uiState.value.error)
        } finally {
            gate.cancel()
            extScope.cancel()
        }
    }

    @Test
    fun localToCloudNoAutoSubmitLossyNumericIdsMultiStepCursorLoopAndUrlEncodedCookieRedaction() = runBlocking {
        val harness = newSearchHarness()
        val extScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            installExtension(
                harness.extStore,
                manifestJson = """
                    {
                      "apiVersion": 1,
                      "id": "ext.lossy.one",
                      "name": "Lossy One",
                      "version": "1.0.0",
                      "capabilities": ["search", "cookie"],
                      "permissions": { "hosts": ["lossy1.test"] },
                      "entry": "index.js"
                    }
                """.trimIndent(),
                scriptJs = """
                    function search(ctx, req) {
                      ctx.log.info("raw=" + req.cookie + " enc=" + encodeURIComponent(req.cookie || ""));
                      if (req.cursor === null || req.cursor === undefined) {
                        return {
                          items: [
                            { kind: "track", remoteTrackId: 12345, title: "Num Track" },
                            { kind: "album", remoteId: 9876, title: "Num Album" },
                            { kind: "track", remoteTrackId: true, title: "Bad Bool Track" },
                            { kind: "playlist", remoteId: { nested: 1 }, title: "Bad Obj Playlist" }
                          ],
                          nextCursor: 2
                        };
                      }
                      if (req.cursor === "2") {
                        return {
                          items: [{ kind: "track", id: 555, title: "Page 2 Fallback Id" }],
                          nextCursor: "p3"
                        };
                      }
                      if (req.cursor === "p3") {
                        return {
                          items: [{ kind: "track", remoteTrackId: "777", title: "Page 3 Loop Back" }],
                          nextCursor: "2" // multi-step loop back to already-seen cursor "2"!
                        };
                      }
                      return { items: [], nextCursor: { bad: true } };
                    }
                    module.exports = { search: search };
                """.trimIndent(),
            )
            installExtension(
                harness.extStore,
                manifestJson = """
                    {
                      "apiVersion": 1,
                      "id": "ext.lossy.two",
                      "name": "Lossy Two",
                      "version": "1.0.0",
                      "capabilities": ["search"],
                      "permissions": { "hosts": ["lossy2.test"] },
                      "entry": "index.js"
                    }
                """.trimIndent(),
                scriptJs = """
                    function search(ctx, req) {
                      return {
                        items: [{ kind: "track", remoteTrackId: "s2", title: "Two Result" }],
                        nextCursor: false
                      };
                    }
                    module.exports = { search: search };
                """.trimIndent(),
            )

            harness.extStore.updateCookie("ext.lossy.one", "sess=a b&c=1")
            val controller = CloudSearchController(
                extensionStore = harness.extStore,
                scope = extScope,
                libraryStore = harness.libraryStore,
            )
            controller.syncAvailableSources()

            // 1. Local -> Cloud switch with NON-EMPTY query must NOT trigger search automatically!
            controller.onQueryChanged("yoasobi")
            controller.selectSource(CloudSearchSource.Extension("ext.lossy.one"))
            assertTrue(controller.uiState.value.isCloudMode)
            assertFalse(controller.uiState.value.hasSubmitted)
            assertFalse(controller.uiState.value.isSearching)
            assertTrue(harness.extStore.logsFor("ext.lossy.one").isEmpty())

            // 2. Explicit submit decodes numeric IDs (12345, 9876) & numeric nextCursor (2), rejects bool/object items!
            controller.submitSearch()
            withTimeout(5_000L) { controller.uiState.first { !it.isSearching && !it.isLoadingMore } }
            val page1 = controller.uiState.value
            assertEquals(2, page1.items.size)
            assertEquals("12345", page1.items[0].id)
            assertEquals("9876", page1.items[1].id)
            assertEquals("2", page1.nextCursor)

            // Verify URL-encoded cookie variants are redacted from logs & logs.json!
            val logs = harness.extStore.logsFor("ext.lossy.one")
            assertFalse(logs.any { it.message.contains("sess=a b&c=1") })
            assertFalse(logs.any { it.message.contains("sess%3Da%20b%26c%3D1") })
            assertFalse(logs.any { it.message.contains("sess%3Da+b%26c%3D1") })
            assertTrue(logs.any { it.message.contains("raw=[REDACTED] enc=[REDACTED]") })

            // 3. Load More follows "2" -> "p3" -> loops back to "2" -> seenCursors terminates pagination!
            controller.loadMore()
            withTimeout(5_000L) { controller.uiState.first { !it.isSearching && !it.isLoadingMore } }
            assertEquals("555", controller.uiState.value.items.last().id)
            assertEquals("p3", controller.uiState.value.nextCursor)

            controller.loadMore()
            withTimeout(5_000L) { controller.uiState.first { !it.isSearching && !it.isLoadingMore } }
            assertEquals("777", controller.uiState.value.items.last().id)
            assertNull(controller.uiState.value.nextCursor)
            assertFalse(controller.uiState.value.canLoadMore)

            // 4. Cloud -> Cloud switch with non-empty query DOES trigger once, and rejects boolean nextCursor.
            controller.selectSource(CloudSearchSource.Extension("ext.lossy.two"))
            withTimeout(5_000L) { controller.uiState.first { !it.isSearching && !it.isLoadingMore } }
            assertTrue(controller.uiState.value.hasSubmitted)
            assertEquals("s2", controller.uiState.value.items.single().id)
            assertNull(controller.uiState.value.nextCursor)
        } finally {
            extScope.cancel()
        }
    }
}
