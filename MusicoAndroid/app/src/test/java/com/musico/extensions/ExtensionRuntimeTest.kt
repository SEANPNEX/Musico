package com.musico.extensions

import android.net.Uri
import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpResponse
import com.musico.cloud.CloudSourceException
import com.musico.cloud.CloudStreamResolver
import com.musico.core.ArtworkCache
import com.musico.core.MetadataReading
import com.musico.core.TrackMetadata
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.Track
import com.musico.store.LibraryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionRuntimeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private data class RecordedHttpCall(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String? = null,
    )

    private data class MockHttpReply(
        val statusCode: Int,
        val headers: Map<String, List<String>> = emptyMap(),
        val bodyBytes: ByteArray = ByteArray(0),
        val finalUrl: String? = null,
    )

    private class MockHttpTransport(
        private val handler: suspend (RecordedHttpCall) -> MockHttpReply,
    ) : CloudHttpClient {
        val calls = CopyOnWriteArrayList<RecordedHttpCall>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            followRedirects: Boolean,
        ): CloudHttpResponse {
            val call = RecordedHttpCall(method = "GET", url = url, headers = headers)
            calls.add(call)
            val reply = handler(call)
            return CloudHttpResponse(
                statusCode = reply.statusCode,
                finalUrl = reply.finalUrl ?: url,
                headers = reply.headers,
                bodyBytes = reply.bodyBytes,
            )
        }

        override suspend fun postJson(
            url: String,
            jsonBody: String,
            headers: Map<String, String>,
        ): CloudHttpResponse {
            val call = RecordedHttpCall(method = "POST", url = url, headers = headers, body = jsonBody)
            calls.add(call)
            val reply = handler(call)
            return CloudHttpResponse(
                statusCode = reply.statusCode,
                finalUrl = reply.finalUrl ?: url,
                headers = reply.headers,
                bodyBytes = reply.bodyBytes,
            )
        }

        override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
    }

    private fun stubReader() = object : MetadataReading {
        override suspend fun read(uri: Uri, fileNameHint: String?): TrackMetadata = TrackMetadata()
        override suspend fun read(file: File): TrackMetadata = TrackMetadata()
    }

    private fun locateFixturesDir(): File {
        val candidates = listOf(
            File("../docs/extensions/fixtures"),
            File("../../docs/extensions/fixtures"),
            File("docs/extensions/fixtures"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate docs/extensions/fixtures from ${File(".").absolutePath}")
    }

    private fun jsonReply(jsonText: String, status: Int = 200): MockHttpReply =
        MockHttpReply(
            statusCode = status,
            headers = mapOf("Content-Type" to listOf("application/json")),
            bodyBytes = jsonText.toByteArray(Charsets.UTF_8),
        )

    private fun zipEntriesToBytes(entries: Map<String, ByteArray>): ByteArray {
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

    private fun zipDirectoryToBytes(dir: File): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.invariantSeparatorsPath }
            for (file in files) {
                val relPath = file.relativeTo(dir).invariantSeparatorsPath
                zos.putNextEntry(ZipEntry(relPath))
                zos.write(file.readBytes())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun createHarness(
        httpClient: CloudHttpClient = MockHttpTransport { jsonReply("{}", 404) },
        stateDir: File = tempFolder.newFolder("ext_state"),
        shortTimeoutMs: Long = 2_000L,
        longTimeoutMs: Long = 5_000L,
        clockMs: () -> Long = System::currentTimeMillis,
    ): Triple<ExtensionStore, CloudProviderRegistry, LibraryStore> {
        val docsDir = File(stateDir, "docs").apply { mkdirs() }
        val registry = CloudProviderRegistry()
        val extStore = ExtensionStore(
            stateDir = stateDir,
            httpClient = httpClient,
            providerRegistry = registry,
            appVersion = "1.2.0-test",
            shortTimeoutMs = shortTimeoutMs,
            longTimeoutMs = longTimeoutMs,
            clockMs = clockMs,
        )
        val libraryStore = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = stubReader(),
            artwork = ArtworkCache(File(stateDir, "art")),
            cloudHttpClient = httpClient,
            providerRegistry = registry,
        )
        extStore.onAvailabilityChanged = { libraryStore.refreshAvailability() }
        return Triple(extStore, registry, libraryStore)
    }

    @Test
    fun manifestSelectOptionParsingAndHostAllowlistValidation() {
        val rawSpecJson = """
            {
              "key": "region",
              "label": "Region",
              "type": "select",
              "default": "us",
              "options": [
                "us",
                { "value": "jp", "label": "Japan" }
              ]
            }
        """.trimIndent()
        val spec = Json { ignoreUnknownKeys = true }.decodeFromString<ExtensionSettingSpec>(rawSpecJson)
        assertEquals(2, spec.options.size)
        assertEquals(ExtensionSelectOption("us", "us"), spec.options[0])
        assertEquals(ExtensionSelectOption("jp", "Japan"), spec.options[1])

        val allowed = listOf("fixture.test", "*.cdn.fixture.test")
        assertTrue(HostAllowlistMatcher.isAllowed("https://fixture.test/a", allowed))
        assertTrue(HostAllowlistMatcher.isAllowed("http://fixture.test/a", allowed))
        assertTrue(HostAllowlistMatcher.isAllowed("https://edge1.cdn.fixture.test/audio.mp3", allowed))
        assertFalse(HostAllowlistMatcher.isAllowed("https://cdn.fixture.test/audio.mp3", allowed))
        assertFalse(HostAllowlistMatcher.isAllowed("file:///etc/passwd", allowed))
        assertFalse(HostAllowlistMatcher.isAllowed("ftp://fixture.test/a", allowed))
        assertFalse(HostAllowlistMatcher.isAllowed("https://evil.com/a", allowed))

        for (badPattern in listOf("*", "*.com", "https://fixture.test", "fixture.test/path", "foo bar")) {
            try {
                HostAllowlistMatcher.validatePattern(badPattern)
                fail("Expected invalid pattern rejection for $badPattern")
            } catch (err: CloudSourceException) {
                assertEquals(CloudErrorCode.PERMISSION_DENIED, err.code)
            }
        }
    }

    @Test
    fun zipSecurityRejectsTraversalTooManyEntriesAndOversizedArchive() = runBlocking {
        val (extStore, _, _) = createHarness()

        for (unsafeName in listOf("../escape.js", "/etc/passwd", "sub\\index.js")) {
            val badZip = zipEntriesToBytes(
                mapOf(
                    unsafeName to "console.log('bad')".toByteArray(Charsets.UTF_8),
                    "manifest.json" to "{}".toByteArray(Charsets.UTF_8),
                )
            )
            val result = extStore.stageFromBytes(badZip, fileNameHint = "bad.musicoext")
            assertTrue("Expected ZIP path failure for $unsafeName", result.isFailure)
        }

        val manyEntries = LinkedHashMap<String, ByteArray>()
        for (i in 0..ExtensionPackageLoader.MAX_ZIP_ENTRIES) {
            manyEntries["file_$i.txt"] = "x".toByteArray(Charsets.UTF_8)
        }
        val tooManyZip = zipEntriesToBytes(manyEntries)
        assertTrue(extStore.stageFromBytes(tooManyZip, fileNameHint = "many.musicoext").isFailure)

        val oversizedPayload = ByteArray(ExtensionPackageLoader.MAX_UNCOMPRESSED_BYTES + 64) { 'a'.code.toByte() }
        val hugeZip = zipEntriesToBytes(
            mapOf(
                "manifest.json" to "{}".toByteArray(Charsets.UTF_8),
                "index.js" to oversizedPayload,
            )
        )
        assertTrue(extStore.stageFromBytes(hugeZip, fileNameHint = "huge.musicoext").isFailure)
    }

    @Test
    fun singleFileStagingPermissionReviewEnableDisableSettingsAndDiskPersistence() = runBlocking {
        val stateDir = tempFolder.newFolder("persist_state")
        val fixtures = locateFixturesDir()
        val basicFile = File(fixtures, "basic-playlist.musico.js")

        val (extStore, registry, _) = createHarness(stateDir = stateDir)
        val staged = extStore.stageFromFile(basicFile).getOrThrow()

        assertEquals(ExtensionPackageFormat.SINGLE_FILE, staged.format)
        assertEquals("com.musico.fixture.basic", staged.manifest.id)
        assertEquals("Fixture", staged.manifest.effectiveBadgeLabel)
        assertEquals(listOf("fixture.test", "api.fixture.test", "*.cdn.fixture.test"), staged.manifest.permissions.hosts)
        assertEquals(
            setOf(ExtensionCapability.PLAYLIST, ExtensionCapability.STREAM, ExtensionCapability.COOKIE),
            staged.manifest.capabilities.toSet(),
        )
        // Not registered before Permission Review confirmation
        assertNull(registry.providerForId("ext.com.musico.fixture.basic"))
        assertEquals(CloudProviderAvailability.MISSING, registry.availabilityFor("ext.com.musico.fixture.basic"))

        val installedDisabled = extStore.installStaged(staged, enableImmediately = false)
        assertFalse(installedDisabled.enabled)
        assertNull(registry.providerForId("ext.com.musico.fixture.basic"))
        assertEquals(CloudProviderAvailability.DISABLED, registry.availabilityFor("ext.com.musico.fixture.basic"))

        extStore.setEnabled("com.musico.fixture.basic", true)
        assertEquals(CloudProviderAvailability.AVAILABLE, registry.availabilityFor("ext.com.musico.fixture.basic"))
        assertNotNull(registry.providerForId("ext.com.musico.fixture.basic"))

        extStore.updateSetting("com.musico.fixture.basic", "apiTag", JsonPrimitive("persisted-tag"))
        extStore.setKv("com.musico.fixture.basic", "persisted_key", "persisted_val", ttlMs = 60_000L)
        extStore.appendLog("com.musico.fixture.basic", "info", "persisted log line")

        // Reload fresh ExtensionStore from disk
        val reloadedRegistry = CloudProviderRegistry()
        val reloadedStore = ExtensionStore(
            stateDir = stateDir,
            httpClient = MockHttpTransport { jsonReply("{}", 404) },
            providerRegistry = reloadedRegistry,
            appVersion = "1.2.0-test",
        )
        val reloadedExt = reloadedStore.findById("com.musico.fixture.basic")
        assertNotNull(reloadedExt)
        assertTrue(reloadedExt!!.enabled)
        assertEquals("persisted-tag", (reloadedExt.state.settings["apiTag"] as? JsonPrimitive)?.content)
        assertEquals("persisted_val", reloadedStore.getKv("com.musico.fixture.basic", "persisted_key"))
        assertTrue(reloadedStore.logsFor("com.musico.fixture.basic").any { it.message == "persisted log line" })
        assertNotNull(reloadedRegistry.providerForId("ext.com.musico.fixture.basic"))

        reloadedStore.resetSettings("com.musico.fixture.basic")
        assertTrue(reloadedStore.findById("com.musico.fixture.basic")!!.state.settings.isEmpty())

        reloadedStore.uninstall("com.musico.fixture.basic")
        assertNull(reloadedStore.findById("com.musico.fixture.basic"))
        assertNull(reloadedRegistry.providerForId("ext.com.musico.fixture.basic"))
        assertFalse(File(reloadedStore.extensionsDir, "com.musico.fixture.basic").exists())
    }

    @Test
    fun httpsOnlyUrlStagingBlocksHttpAndRedirectsToHttp() = runBlocking {
        val basicScript = File(locateFixturesDir(), "basic-playlist.musico.js").readText(Charsets.UTF_8)
        val http = MockHttpTransport { call ->
            when (call.url) {
                "https://good.example.com/redir.musico.js" -> MockHttpReply(
                    statusCode = 302,
                    headers = mapOf("Location" to listOf("http://downgrade.example.com/ext.musico.js")),
                )
                "https://good.example.com/basic-playlist.musico.js" -> MockHttpReply(
                    statusCode = 200,
                    bodyBytes = basicScript.toByteArray(Charsets.UTF_8),
                )
                else -> jsonReply("{}", 404)
            }
        }
        val (extStore, _, _) = createHarness(httpClient = http)

        val plainHttp = extStore.stageFromUrl("http://good.example.com/basic-playlist.musico.js")
        assertTrue(plainHttp.isFailure)

        val redirectDowngrade = extStore.stageFromUrl("https://good.example.com/redir.musico.js")
        assertTrue(redirectDowngrade.isFailure)
        assertFalse(http.calls.any { it.url.startsWith("http://downgrade.example.com") })

        val validHttps = extStore.stageFromUrl("https://good.example.com/basic-playlist.musico.js").getOrThrow()
        assertEquals("com.musico.fixture.basic", validHttps.manifest.id)
        assertEquals("https://good.example.com/basic-playlist.musico.js", validHttps.sourceUrl)
    }

    @Test
    fun basicPlaylistFixtureCursorPaginationCryptoKvSettingsEnvLogsAndResolveStream() = runBlocking {
        var nowMs = 1_700_000_000_000L
        val http = MockHttpTransport { call ->
            when {
                call.url == "https://api.fixture.test/playlists/mix1" -> jsonReply(
                    """
                    {
                      "remoteId": "mix1",
                      "title": "Fixture Mix 1",
                      "injectDiagnostics": true,
                      "nextCursor": "p2",
                      "tracks": [
                        {
                          "remoteTrackId": "trk-1",
                          "relativePath": "01-First.mp3",
                          "title": "First Song",
                          "artist": "Artist A",
                          "album": "Placeholder",
                          "trackNumber": 1,
                          "durationSec": 180
                        }
                      ]
                    }
                    """.trimIndent()
                )
                call.url == "https://api.fixture.test/playlists/mix1?cursor=p2" -> jsonReply(
                    """
                    {
                      "remoteId": "mix1",
                      "title": "Fixture Mix 1",
                      "nextCursor": null,
                      "tracks": [
                        {
                          "remoteTrackId": "trk-2",
                          "relativePath": "02-Second.mp3",
                          "title": "Second Song",
                          "artist": "Artist B",
                          "album": "Page 2 Album",
                          "trackNumber": 2,
                          "durationSec": 210
                        }
                      ]
                    }
                    """.trimIndent()
                )
                call.url == "https://api.fixture.test/stream" -> jsonReply(
                    """
                    {
                      "url": "https://edge1.cdn.fixture.test/trk-1.flac",
                      "headers": { "Referer": "https://fixture.test/" },
                      "fileExtension": "flac",
                      "mimeType": "audio/flac",
                      "expiresAtMs": 1700003600000,
                      "fallbackUrls": ["https://edge2.cdn.fixture.test/trk-1.flac"],
                      "contentLength": 12345678
                    }
                    """.trimIndent()
                )
                else -> jsonReply("{}", 404)
            }
        }

        val (extStore, registry, _) = createHarness(httpClient = http, clockMs = { nowMs })
        val basicFile = File(locateFixturesDir(), "basic-playlist.musico.js")
        val staged = extStore.stageFromFile(basicFile).getOrThrow()
        extStore.installStaged(staged, enableImmediately = true)

        extStore.updateSetting("com.musico.fixture.basic", "preferHighBitrate", JsonPrimitive(false))
        extStore.updateSetting("com.musico.fixture.basic", "apiTag", JsonPrimitive("custom-tag"))
        extStore.updateSetting("com.musico.fixture.basic", "region", JsonPrimitive("jp"))

        val classified = registry.classifyUrl("Check this out: https://www.fixture.test/playlist/mix1?ref=1")
        assertNotNull(classified)
        assertEquals("ext.com.musico.fixture.basic", classified!!.provider.providerId)
        assertEquals("https://fixture.test/playlist/mix1", classified.normalizedUrl)
        assertEquals("mix1", classified.remoteId)

        val provider = classified.provider
        val pageResult = provider.fetchCollection(classified.normalizedUrl, cookie = "session=abc123")
        assertEquals("Fixture Mix 1", pageResult.title)
        assertEquals(2, pageResult.tracks.size)
        assertEquals("trk-1", pageResult.tracks[0].remoteTrackId)
        assertEquals("trk-2", pageResult.tracks[1].remoteTrackId)

        val diag = pageResult.tracks[0].album
        assertTrue("Expected md5 in $diag", diag.contains("md5=860a75194508c8447e93dbcba86b5ffe"))
        assertTrue("Expected sha1 in $diag", diag.contains("sha1=e16682e68bc1074d3b8f9e4c9869ebcdb3b20090"))
        assertTrue("Expected sha256 in $diag", diag.contains("sha256=cbcd5268d62f393b8ce6cc33ed8f5309be03b110012a0bc48d302855d63edd11"))
        assertTrue("Expected hmac in $diag", diag.contains("hmac=9edcd2ade9a09eac5f91629abeb0fb0ff5fe6a4cad391511b4dbeb4a333a1ba8"))
        assertTrue("Expected b64 in $diag", diag.contains("b64=musico-hello"))
        assertTrue("Expected kv=1/temp/null in $diag", diag.contains("kv=1/temp/null"))
        assertTrue("Expected settings=false|custom-tag|jp in $diag", diag.contains("settings=false|custom-tag|jp"))
        assertTrue("Expected env=android|v1 in $diag", diag.contains("env=android|v1"))

        val firstHttpCall = http.calls.first { it.url == "https://api.fixture.test/playlists/mix1" }
        assertEquals("custom-tag", firstHttpCall.headers["X-Api-Tag"])
        assertEquals("jp", firstHttpCall.headers["X-Region"])
        assertEquals("false", firstHttpCall.headers["X-High-Bitrate"])
        assertEquals("android", firstHttpCall.headers["X-Platform"])
        assertEquals("session=abc123", firstHttpCall.headers["Cookie"])

        // Second invocation within TTL increments kv visit_count (2 pages ran on first fetch -> starts at 3)
        val secondRun = provider.fetchCollection(classified.normalizedUrl, cookie = null)
        assertTrue(secondRun.tracks[0].album.contains("kv=3/temp/null"))

        // Advance clock past 300s TTL -> kv resets to 1
        nowMs += 301_000L
        val expiredRun = provider.fetchCollection(classified.normalizedUrl, cookie = null)
        assertTrue(expiredRun.tracks[0].album.contains("kv=1/temp/null"))

        // Verify resolveStream via CloudStreamResolver integration
        val resolver = CloudStreamResolver(providerRegistry = registry)
        val root = LibraryRoot(
            id = "r1",
            name = "Fixture Mix 1",
            kind = RootKind.CLOUD_EXTENSION,
            sourceUrl = classified.normalizedUrl,
            remoteId = "mix1",
            cookie = "session=abc123",
            providerId = "ext.com.musico.fixture.basic",
        )
        val sampleTrack = Track(
            id = "track-r1",
            rootId = "r1",
            relativePath = "01-First.mp3",
            title = "First Song",
            artist = "Artist A",
            album = "Placeholder",
            albumArtist = "Artist A",
            durationSec = 180.0,
            fileSize = 0L,
            modifiedEpochMs = 0L,
            dateAddedEpochMs = 0L,
            cloudSource = "ext.com.musico.fixture.basic",
            remoteTrackId = "trk-1",
        )
        val resolved = resolver.resolve(sampleTrack, root, forceRefresh = true)
        assertEquals("https://edge1.cdn.fixture.test/trk-1.flac", resolved.url)
        assertEquals("flac", resolved.fileExtension)
        assertEquals("audio/flac", resolved.mimeType)
        assertEquals(1700003600000L, resolved.expiresAtEpochMs)
        assertEquals(listOf("https://edge2.cdn.fixture.test/trk-1.flac"), resolved.fallbackUrls)
        assertEquals(12345678L, resolved.contentLength)
        assertEquals("https://fixture.test/", resolved.headers["Referer"])

        val streamCall = http.calls.last { it.url == "https://api.fixture.test/stream" }
        assertEquals("session=abc123", streamCall.headers["Cookie"])
        assertTrue(streamCall.body.orEmpty().contains("\"preferHighBitrate\":false"))
        assertTrue(streamCall.body.orEmpty().contains("\"region\":\"jp\""))

        // Logs: fixture emitted info/warn/error entries; test 200-entry ring buffer cap + clear
        val initialLogs = extStore.logsFor("com.musico.fixture.basic")
        assertTrue(initialLogs.any { it.level == "info" && it.message.contains("fetchCollection start") })
        assertTrue(initialLogs.any { it.level == "warn" })
        assertTrue(initialLogs.any { it.level == "error" })

        repeat(210) { idx ->
            extStore.appendLog("com.musico.fixture.basic", "info", "ring-$idx")
        }
        val cappedLogs = extStore.logsFor("com.musico.fixture.basic")
        assertEquals(ExtensionStore.MAX_LOG_ENTRIES, cappedLogs.size)
        assertEquals("ring-10", cappedLogs.first().message)
        assertEquals("ring-209", cappedLogs.last().message)

        extStore.clearLogs("com.musico.fixture.basic")
        assertTrue(extStore.logsFor("com.musico.fixture.basic").isEmpty())
    }

    @Test
    fun cookieIsolationBetweenRootsAndNoCookieCapability() = runBlocking {
        val http = MockHttpTransport { call ->
            if (call.url == "https://api.fixture.test/playlists/auth-required") {
                jsonReply(
                    """
                    {
                      "remoteId": "auth-required",
                      "title": "Private List",
                      "tracks": [
                        { "remoteTrackId": "priv-1", "title": "Secret", "artist": "Me", "durationSec": 100 }
                      ]
                    }
                    """.trimIndent()
                )
            } else {
                jsonReply("{}", 404)
            }
        }
        val (extStore, registry, _) = createHarness(httpClient = http)
        val basicFile = File(locateFixturesDir(), "basic-playlist.musico.js")
        extStore.installStaged(extStore.stageFromFile(basicFile).getOrThrow(), enableImmediately = true)
        val provider = registry.providerForId("ext.com.musico.fixture.basic")!!

        // Root A without cookie -> AUTH_REQUIRED
        try {
            provider.fetchCollection("https://fixture.test/playlist/auth-required", cookie = null)
            fail("Expected AUTH_REQUIRED without cookie")
        } catch (err: CloudSourceException) {
            assertEquals(CloudErrorCode.AUTH_REQUIRED, err.code)
        }

        // Root B with cookie -> succeeds
        val authed = provider.fetchCollection("https://fixture.test/playlist/auth-required", cookie = "sid=secret")
        assertEquals(1, authed.tracks.size)

        // Root A again without cookie -> must STILL fail (no cookie leakage across calls)
        try {
            provider.fetchCollection("https://fixture.test/playlist/auth-required", cookie = null)
            fail("Expected AUTH_REQUIRED without cookie even after authed root")
        } catch (err: CloudSourceException) {
            assertEquals(CloudErrorCode.AUTH_REQUIRED, err.code)
        }

        // Single-file extension WITHOUT cookie capability strips incoming cookie
        val noCookieScript = """
            var manifest = {
              apiVersion: 1,
              id: 'com.musico.test.nocookie',
              name: 'No Cookie',
              version: '1.0.0',
              capabilities: ['playlist'],
              urlPatterns: ['^https://nocookie\\.fixture\\.test/p$'],
              permissions: { hosts: ['nocookie.fixture.test'] }
            };
            function canHandle(i) { return { matched: true, normalizedUrl: 'https://nocookie.fixture.test/p', remoteId: 'p' }; }
            async function fetchCollection(ctx, req) {
              return {
                remoteId: 'p',
                title: req.cookie ? 'LEAKED' : 'STRIPPED',
                tracks: [{ remoteTrackId: 't1', title: req.cookie ? 'LEAKED' : 'STRIPPED', durationSec: 1 }]
              };
            }
            module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection };
        """.trimIndent()
        val stagedNoCookie = extStore.stageFromBytes(noCookieScript.toByteArray(Charsets.UTF_8), "nocookie.musico.js").getOrThrow()
        extStore.installStaged(stagedNoCookie, enableImmediately = true)
        val noCookieProvider = registry.providerForId("ext.com.musico.test.nocookie")!!
        val res = noCookieProvider.fetchCollection("https://nocookie.fixture.test/p", cookie = "should-be-dropped")
        assertEquals("STRIPPED", res.title)
        assertEquals("STRIPPED", res.tracks[0].title)
    }

    @Test
    fun zippedTreeSourceBundleProgressiveCrawlFolderPaginationAndPrioritization() = runBlocking {
        val priorityGate = CompletableDeferred<Unit>()
        val requestedFolders = CopyOnWriteArrayList<String>()

        val http = MockHttpTransport { call ->
            when {
                call.url == "https://tree.fixture.test/api/root/demo" -> jsonReply(
                    """
                    {
                      "remoteId": "demo",
                      "title": "Demo Tree Root",
                      "tracks": [
                        { "remoteTrackId": "t-root", "relativePath": "00-Intro.mp3", "title": "Intro", "trackNumber": 1, "durationSec": 45 }
                      ],
                      "subfolders": [
                        { "id": "f_alpha", "name": "Alpha", "pathSegments": ["Alpha"] },
                        { "id": "f_beta", "name": "Beta", "pathSegments": ["Beta"] },
                        { "id": "f_gamma", "name": "Gamma", "pathSegments": ["Gamma"] },
                        { "id": "f_delta", "name": "Delta", "pathSegments": ["Delta"] },
                        { "id": "f_priority", "name": "Priority", "pathSegments": ["Priority"] }
                      ]
                    }
                    """.trimIndent()
                )
                call.url.startsWith("https://tree.fixture.test/api/folder/f_alpha") -> {
                    requestedFolders.add("f_alpha")
                    if (!call.url.contains("cursor=")) {
                        jsonReply(
                            """
                            {
                              "folderId": "f_alpha",
                              "nextCursor": "p2",
                              "tracks": [
                                { "remoteTrackId": "t-a1", "relativePath": "Alpha/01-A1.mp3", "title": "Alpha 1", "durationSec": 100 }
                              ],
                              "subfolders": [
                                { "id": "f_nested", "name": "Nested", "pathSegments": ["Alpha", "Nested"] }
                              ]
                            }
                            """.trimIndent()
                        )
                    } else {
                        jsonReply(
                            """
                            {
                              "folderId": "f_alpha",
                              "nextCursor": null,
                              "tracks": [
                                { "remoteTrackId": "t-a2", "relativePath": "Alpha/02-A2.mp3", "title": "Alpha 2", "durationSec": 120 }
                              ],
                              "subfolders": []
                            }
                            """.trimIndent()
                        )
                    }
                }
                call.url.startsWith("https://tree.fixture.test/api/folder/f_nested") -> {
                    requestedFolders.add("f_nested")
                    jsonReply(
                        """
                        {
                          "folderId": "f_nested",
                          "tracks": [
                            { "remoteTrackId": "t-nest", "relativePath": "Alpha/Nested/01-Deep.mp3", "title": "Deep", "durationSec": 90 }
                          ]
                        }
                        """.trimIndent()
                    )
                }
                call.url.startsWith("https://tree.fixture.test/api/folder/f_priority") -> {
                    requestedFolders.add("f_priority")
                    priorityGate.complete(Unit)
                    jsonReply(
                        """
                        {
                          "folderId": "f_priority",
                          "tracks": [
                            { "remoteTrackId": "t-prio", "relativePath": "Priority/01-Fast.mp3", "title": "Fast Track", "durationSec": 60 }
                          ]
                        }
                        """.trimIndent()
                    )
                }
                call.url.startsWith("https://tree.fixture.test/api/folder/f_") -> {
                    val folderId = call.url.substringAfter("/api/folder/").substringBefore('?')
                    requestedFolders.add(folderId)
                    kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                        priorityGate.await()
                    }
                    jsonReply(
                        """
                        {
                          "folderId": "$folderId",
                          "tracks": [
                            { "remoteTrackId": "t-$folderId", "title": "Song $folderId", "durationSec": 50 }
                          ]
                        }
                        """.trimIndent()
                    )
                }
                else -> jsonReply("{}", 404)
            }
        }

        val (extStore, registry, libraryStore) = createHarness(httpClient = http)
        val treeDir = File(locateFixturesDir(), "tree-source")
        val bundleBytes = zipDirectoryToBytes(treeDir)

        val staged = extStore.stageFromBytes(bundleBytes, fileNameHint = "tree-source.musicoext").getOrThrow()
        assertEquals(ExtensionPackageFormat.BUNDLE, staged.format)
        assertEquals("com.musico.fixture.tree", staged.manifest.id)
        extStore.installStaged(staged, enableImmediately = true)

        val provider = registry.providerForId("ext.com.musico.fixture.tree")!!
        assertTrue(provider.supportsTree)
        assertFalse(provider.usesFlatCloudListing)

        // Verify direct crawlProgressive prioritizes Priority over the 4th FIFO sibling (f_delta)
        val batches = mutableListOf<CloudTreeBatch>()
        val fullTree = provider.crawlProgressive(
            sourceUrl = "https://tree.fixture.test/root/demo",
            priorityPathProvider = { "Priority" },
            onBatch = { batch -> batches.add(batch) },
        )
        assertTrue(priorityGate.isCompleted)
        val priorityPos = requestedFolders.indexOf("f_priority")
        val deltaPos = requestedFolders.indexOf("f_delta")
        assertTrue("Expected Priority before Delta: $requestedFolders", priorityPos in 0 until deltaPos)
        assertTrue(batches.first().knownFolderPaths.containsAll(setOf("Alpha", "Priority")))
        assertEquals("Demo Tree Root", fullTree.title)
        assertTrue(fullTree.tracks.any { it.remoteTrackId == "t-root" && it.relativePath == "00-Intro.mp3" })
        assertTrue(fullTree.tracks.any { it.remoteTrackId == "t-a1" })
        assertTrue(fullTree.tracks.any { it.remoteTrackId == "t-a2" })
        assertTrue(fullTree.tracks.any { it.remoteTrackId == "t-nest" && it.relativePath == "Alpha/Nested/01-Deep.mp3" })
        assertTrue(fullTree.tracks.any { it.remoteTrackId == "t-prio" })

        // Now verify LibraryStore integration: progressive root insertion, prioritizeCloudFolder, folderContents
        val addRes = libraryStore.addCloudRoot("https://tree.fixture.test/root/demo")
        assertTrue(addRes.isSuccess)
        val addedRoot = libraryStore.state.value.roots.single { it.kind == RootKind.CLOUD_EXTENSION }
        libraryStore.prioritizeCloudFolder(addedRoot.id, "Priority")
        libraryStore.awaitCloudSync(addedRoot.id)

        val (rootFolders, rootTracks) = libraryStore.folderContents(addedRoot.id, "")
        assertEquals(listOf("Alpha", "Beta", "Delta", "Gamma", "Priority"), rootFolders)
        assertEquals(listOf("t-root"), rootTracks.map { it.remoteTrackId })

        val (alphaSubfolders, alphaTracks) = libraryStore.folderContents(addedRoot.id, "Alpha")
        assertEquals(listOf("Nested"), alphaSubfolders)
        assertEquals(setOf("t-a1", "t-a2"), alphaTracks.map { it.remoteTrackId }.toSet())

        val (_, nestedTracks) = libraryStore.folderContents(addedRoot.id, "Alpha/Nested")
        assertEquals(listOf("t-nest"), nestedTracks.map { it.remoteTrackId })
    }

    @Test
    fun securityAndErrorsFixtureAllowlistRedirectsErrorCodesAndTimeouts() = runBlocking {
        val http = MockHttpTransport { call ->
            when (call.url) {
                "https://fixture.test/redirect-evil" -> MockHttpReply(
                    statusCode = 302,
                    headers = mapOf("Location" to listOf("https://evil.example.com/leak")),
                )
                "https://edge1.cdn.fixture.test/ok" -> MockHttpReply(
                    statusCode = 200,
                    bodyBytes = "subdomain-body".toByteArray(Charsets.UTF_8),
                )
                else -> jsonReply("{}", 200)
            }
        }
        val (extStore, registry, _) = createHarness(
            httpClient = http,
            shortTimeoutMs = 250L,
            longTimeoutMs = 350L,
        )
        val secFile = File(locateFixturesDir(), "security-and-errors.musico.js")
        extStore.installStaged(extStore.stageFromFile(secFile).getOrThrow(), enableImmediately = true)
        val provider = registry.providerForId("ext.com.musico.fixture.security")!!

        // Host allowlist & redirect escape checks
        suspend fun expectCollectionError(slug: String, expected: CloudErrorCode) {
            try {
                provider.fetchCollection("https://fixture.test/security/$slug")
                fail("Expected $expected for $slug")
            } catch (err: CloudSourceException) {
                assertEquals("Unexpected error code for $slug: ${err.message}", expected, err.code)
            }
        }

        expectCollectionError("disallowed-host", CloudErrorCode.PERMISSION_DENIED)
        expectCollectionError("disallowed-scheme", CloudErrorCode.PERMISSION_DENIED)
        expectCollectionError("redirect-escape", CloudErrorCode.PERMISSION_DENIED)
        assertFalse(http.calls.any { it.url.contains("evil.example.com") })

        expectCollectionError("subdomain-exact-miss", CloudErrorCode.PERMISSION_DENIED)

        val noFollow = provider.fetchCollection("https://fixture.test/security/redirect-no-follow")
        assertEquals("status=302", noFollow.title)
        assertEquals("https://evil.example.com/leak", noFollow.tracks.single().album)

        val subdomainOk = provider.fetchCollection("https://fixture.test/security/subdomain-hit")
        assertEquals("Subdomain Hit 200", subdomainOk.title)
        assertEquals("subdomain-body", subdomainOk.tracks.single().album)

        // All error code mappings
        expectCollectionError("throw-invalid-url", CloudErrorCode.INVALID_URL)
        expectCollectionError("throw-auth-required", CloudErrorCode.AUTH_REQUIRED)
        expectCollectionError("throw-empty-collection", CloudErrorCode.EMPTY_COLLECTION)
        expectCollectionError("throw-no-stream", CloudErrorCode.NO_PLAYABLE_STREAM)
        expectCollectionError("throw-rate-limited", CloudErrorCode.RATE_LIMITED)
        expectCollectionError("throw-network-error", CloudErrorCode.NETWORK_ERROR)

        // Sync infinite loop & hanging async Promise timeouts
        expectCollectionError("sync-loop", CloudErrorCode.EXTENSION_RUNTIME_ERROR)
        expectCollectionError("async-hang", CloudErrorCode.EXTENSION_RUNTIME_ERROR)

        // Stream URL & fallbackUrls host allowlist checks
        try {
            provider.resolveStream("bad-stream-host")
            fail("Expected PERMISSION_DENIED for bad-stream-host")
        } catch (err: CloudSourceException) {
            assertEquals(CloudErrorCode.PERMISSION_DENIED, err.code)
        }
        try {
            provider.resolveStream("bad-fallback-host")
            fail("Expected PERMISSION_DENIED for bad-fallback-host")
        } catch (err: CloudSourceException) {
            assertEquals(CloudErrorCode.PERMISSION_DENIED, err.code)
        }

        val validStream = provider.resolveStream("good-track")
        assertEquals("https://media1.cdn.fixture.test/audio.mp3", validStream.url)
        assertEquals(listOf("https://media2.cdn.fixture.test/audio.mp3"), validStream.fallbackUrls)
    }

    @Test
    fun qrLoginCapabilityRequiresStartAndPollHooks() = runBlocking {
        val (extStore, _, _) = createHarness()

        val missingPollScript = """
            var manifest = {
              apiVersion: 1,
              id: 'com.musico.test.qr.nopoll',
              name: 'Missing Poll',
              version: '1.0.0',
              capabilities: ['search', 'qr_login'],
              permissions: { hosts: ['qr.fixture.test'] }
            };
            async function search() { return { items: [], hasMore: false }; }
            async function startQrLogin() { return { sessionId: 's1', qrContent: 'https://qr.fixture.test/s1' }; }
            module.exports = { manifest: manifest, search: search, startQrLogin: startQrLogin };
        """.trimIndent()
        val missingPollResult = extStore.stageFromBytes(
            missingPollScript.toByteArray(Charsets.UTF_8),
            fileNameHint = "missing-poll.musico.js",
        )
        assertTrue(missingPollResult.isFailure)
        assertTrue(missingPollResult.exceptionOrNull()?.message?.contains("pollQrLogin") == true)

        val missingStartScript = """
            var manifest = {
              apiVersion: 1,
              id: 'com.musico.test.qr.nostart',
              name: 'Missing Start',
              version: '1.0.0',
              capabilities: ['search', 'qr_login'],
              permissions: { hosts: ['qr.fixture.test'] }
            };
            async function search() { return { items: [], hasMore: false }; }
            async function pollQrLogin() { return { status: 'pending' }; }
            module.exports = { manifest: manifest, search: search, pollQrLogin: pollQrLogin };
        """.trimIndent()
        val missingStartResult = extStore.stageFromBytes(
            missingStartScript.toByteArray(Charsets.UTF_8),
            fileNameHint = "missing-start.musico.js",
        )
        assertTrue(missingStartResult.isFailure)
        assertTrue(missingStartResult.exceptionOrNull()?.message?.contains("startQrLogin") == true)
    }

    @Test
    fun qrLoginLifecycleSetCookiesAutoPersistSearchForwardingAndRedaction() = runBlocking {
        var checkAttempt = 0
        val http = MockHttpTransport { call ->
            when (call.url) {
                "https://qr.fixture.test/qr/unikey" -> jsonReply("""{"unikey":"u-android-1"}""")
                "https://qr.fixture.test/qr/check" -> {
                    checkAttempt += 1
                    when (checkAttempt) {
                        1 -> jsonReply("""{"code":801}""")
                        2 -> jsonReply("""{"code":802,"nickname":"TestListener"}""")
                        3 -> MockHttpReply(
                            statusCode = 200,
                            headers = mapOf(
                                "Set-Cookie" to listOf(
                                    "MUSIC_U=secret_android_cookie_999; Max-Age=2592000; Expires=Thu, 31 Dec 2099 23:59:59 GMT; Path=/; HttpOnly",
                                    "__csrf=csrf_abc; Expires=Thu, 31 Dec 2099 23:59:59 GMT; Path=/",
                                ),
                            ),
                            bodyBytes = """{"code":803}""".toByteArray(Charsets.UTF_8),
                        )
                        else -> jsonReply("""{"code":803}""")
                    }
                }
                else -> jsonReply("{}", 404)
            }
        }

        val (extStore, _, _) = createHarness(httpClient = http)
        val extId = "com.musico.test.qr.flow"
        val script = """
            var manifest = {
              apiVersion: 1,
              id: '$extId',
              name: 'QR Fixture',
              version: '1.0.0',
              capabilities: ['search', 'qr_login'],
              permissions: { hosts: ['qr.fixture.test'] }
            };
            async function startQrLogin(ctx) {
              var res = await ctx.http.request({ method: 'POST', url: 'https://qr.fixture.test/qr/unikey' });
              var body = JSON.parse(res.text);
              return {
                sessionId: body.unikey,
                qrContent: 'https://music.163.com/login?codekey=' + body.unikey,
                pollIntervalMs: 1500
              };
            }
            async function pollQrLogin(ctx, req) {
              if (req.sessionId === 'empty-cookie') {
                return { status: 'confirmed', cookie: '   ' };
              }
              var res = await ctx.http.request({ method: 'POST', url: 'https://qr.fixture.test/qr/check' });
              var body = JSON.parse(res.text);
              if (body.code === 801) return { status: 'pending' };
              if (body.code === 802) return { status: 'scanned', accountName: body.nickname };
              if (body.code === 803) {
                var list = Array.isArray(res.setCookies) && res.setCookies.length > 0 ? res.setCookies : [];
                var musicU = '';
                var csrf = '';
                for (var i = 0; i < list.length; i++) {
                  var firstPair = String(list[i]).split(';')[0].trim();
                  if (firstPair.indexOf('MUSIC_U=') === 0) musicU = firstPair;
                  if (firstPair.indexOf('__csrf=') === 0) csrf = firstPair;
                }
                var cookie = [musicU, csrf].filter(Boolean).join('; ');
                return { status: 'confirmed', cookie: cookie, accountName: 'TestListener' };
              }
              return { status: 'expired' };
            }
            async function search(ctx, req) {
              ctx.log.info('searching with cookie: ' + (req.cookie || 'none'));
              return {
                items: [
                  {
                    kind: 'track',
                    title: req.cookie === 'MUSIC_U=secret_android_cookie_999; __csrf=csrf_abc' ? 'AUTHED_OK' : 'NO_AUTH',
                    remoteTrackId: 'tr-1'
                  }
                ],
                hasMore: false
              };
            }
            module.exports = {
              manifest: manifest,
              startQrLogin: startQrLogin,
              pollQrLogin: pollQrLogin,
              search: search
            };
        """.trimIndent()

        val staged = extStore.stageFromBytes(script.toByteArray(Charsets.UTF_8), "qr-flow.musico.js").getOrThrow()
        val installed = extStore.installStaged(staged, enableImmediately = true)
        assertTrue(installed.manifest.supportsCookieAuth)
        assertTrue(installed.manifest.supportsQrLogin)

        val session = extStore.startQrLogin(extId)
        assertEquals("u-android-1", session.sessionId)
        assertEquals("https://music.163.com/login?codekey=u-android-1", session.qrContent)
        assertEquals(1500, session.pollIntervalMs)

        val poll1 = extStore.pollQrLogin(extId, session.sessionId)
        assertEquals(ExtQrLoginStatus.PENDING, poll1.status)
        assertNull(extStore.findById(extId)?.state?.cookie)

        val poll2 = extStore.pollQrLogin(extId, session.sessionId)
        assertEquals(ExtQrLoginStatus.SCANNED, poll2.status)
        assertEquals("TestListener", poll2.accountName)
        assertNull(extStore.findById(extId)?.state?.cookie)

        val poll3 = extStore.pollQrLogin(extId, session.sessionId)
        assertEquals(ExtQrLoginStatus.CONFIRMED, poll3.status)
        assertEquals("MUSIC_U=secret_android_cookie_999; __csrf=csrf_abc", poll3.cookie)
        assertEquals(
            "MUSIC_U=secret_android_cookie_999; __csrf=csrf_abc",
            extStore.findById(extId)?.state?.cookie,
        )

        val page = extStore.search(extensionId = extId, query = "hello")
        assertEquals(1, page.items.size)
        assertEquals("AUTHED_OK", page.items[0].title)
        val logs = extStore.logsFor(extId)
        assertTrue(logs.none { it.message.contains("secret_android_cookie_999") || it.message.contains("csrf_abc") })

        // Empty cookie on confirmed must throw
        try {
            extStore.pollQrLogin(extId, "empty-cookie")
            fail("Expected confirmed without cookie to fail")
        } catch (_: CloudSourceException) {
            // expected
        }
    }

    @Test
    fun serverUrlSetting_dynamicallyAllowsConfiguredHost_andBlocksOthers() = runBlocking {
        val extId = "com.test.android.serverurl"
        val mockHttp = MockHttpTransport { call ->
            jsonReply("""{"id":"tr-10","title":"Android Server OK"}""")
        }
        val (extStore, _, _) = createHarness(httpClient = mockHttp)
        val runtime = QuickJsExtensionRuntime(httpClient = mockHttp, host = extStore)
        val script = """
            'use strict';
            var manifest = {
              apiVersion: 1,
              id: '$extId',
              name: 'Server URL Ext',
              version: '1.0.0',
              capabilities: ['search', 'stream'],
              permissions: { hosts: [] },
              settings: [
                { key: 'serverUrl', label: 'Server URL', type: 'server_url', default: '' }
              ]
            };
            async function search(ctx, req) {
              var base = String(ctx.settings.get('serverUrl') || '').replace(/\/+$/, '');
              var res = await ctx.http.request({ method: 'GET', url: base + '/v1/search?q=' + encodeURIComponent(req.query) });
              var data = JSON.parse(res.text);
              return { items: [{ kind: 'track', remoteTrackId: data.id, title: data.title }], nextCursor: null };
            }
            async function resolveStream(ctx, req) {
              var base = String(ctx.settings.get('serverUrl') || '').replace(/\/+$/, '');
              if (req.remoteTrackId === 'evil') {
                return { url: 'https://evil.example.test/stream.m4a', fileExtension: 'm4a' };
              }
              return { url: base + '/v1/tracks/' + req.remoteTrackId + '/stream', fileExtension: 'm4a' };
            }
            module.exports = { manifest: manifest, search: search, resolveStream: resolveStream };
        """.trimIndent()

        val staged = extStore.stageFromBytes(script.toByteArray(Charsets.UTF_8), "serverurl.musico.js").getOrThrow()
        assertTrue(staged.manifest.hasServerUrlSetting)
        assertNull(HostAllowlistMatcher.extractApprovedHost("file:///etc/passwd"))
        assertNull(HostAllowlistMatcher.extractApprovedHost("https://*.wildcard.test"))
        assertEquals("am.local.test", HostAllowlistMatcher.extractApprovedHost("https://am.local.test:8787/api"))

        val installed = extStore.installStaged(staged, enableImmediately = true)
        extStore.updateSetting(installed.id, "serverUrl", JsonPrimitive("https://am.local.test:8787/"))

        val page = extStore.search(extensionId = extId, query = "hello")
        assertEquals(1, page.items.size)
        assertEquals("Android Server OK", page.items[0].title)

        val stream = runtime.resolveStream(
            extension = extStore.findById(installed.id)!!,
            remoteTrackId = "tr-10",
        )
        assertEquals("https://am.local.test:8787/v1/tracks/tr-10/stream", stream.url)

        try {
            runtime.resolveStream(
                extension = extStore.findById(installed.id)!!,
                remoteTrackId = "evil",
            )
            fail("Expected unrelated stream host to be rejected")
        } catch (err: CloudSourceException) {
            assertEquals(CloudErrorCode.PERMISSION_DENIED, err.code)
        }
    }

    @Test
    fun `web_login manifest validation and Netscape cookie parser work`() {
        val valid = ExtensionManifest(
            apiVersion = 1,
            id = "com.test.weblogin",
            name = "Web Login Probe",
            version = "1.0.0",
            capabilities = listOf(ExtensionCapability.SEARCH, ExtensionCapability.WEB_LOGIN),
            permissions = ExtensionPermissions(hosts = listOf("music.youtube.com")),
            webLogin = ExtensionWebLoginConfig(
                loginUrl = "https://accounts.google.com/ServiceLogin",
                cookieDomains = listOf(".youtube.com", ".google.com"),
                requiredCookies = listOf("SAPISID"),
            ),
        )
        val validated = ExtensionManifestValidator.validate(valid)
        assertTrue(validated.supportsWebLogin)
        assertTrue(validated.supportsCookieAuth)

        try {
            ExtensionManifestValidator.validate(valid.copy(webLogin = null))
            fail("Expected missing webLogin to fail")
        } catch (_: IllegalArgumentException) {
        }

        try {
            ExtensionManifestValidator.validate(
                valid.copy(
                    webLogin = ExtensionWebLoginConfig(
                        loginUrl = "http://accounts.google.com/ServiceLogin",
                        cookieDomains = listOf(".youtube.com"),
                    ),
                ),
            )
            fail("Expected http loginUrl to fail")
        } catch (_: IllegalArgumentException) {
        }

        val netscape = """
            # Netscape HTTP Cookie File
            #HttpOnly_.youtube.com	TRUE	/	TRUE	1900000000	SAPISID	sapisid_val
            .google.com	TRUE	/	TRUE	0	SID	sid_val
            .youtube.com	TRUE	/	TRUE	1000	EXPIRED	old_val
            .github.com	TRUE	/	TRUE	0	GH_SESS	do_not_leak
        """.trimIndent()
        val parsed = NetscapeCookieFileParser.parseCookieHeader(
            text = netscape,
            allowedDomains = listOf(".youtube.com", ".google.com"),
            nowEpochSec = 1700000000L,
        )
        assertEquals("SAPISID=sapisid_val; SID=sid_val", parsed)

        val plainParsed = NetscapeCookieFileParser.parseCookieHeader(
            text = "Cookie: SESSDATA=abc123; bili_jct=def456; SESSDATA=abc_new",
            allowedDomains = listOf("www.bilibili.com"),
        )
        assertEquals("SESSDATA=abc_new; bili_jct=def456", plainParsed)
    }
}
