package com.musico.extensions

import android.net.Uri
import com.musico.cloud.BilibiliClient
import com.musico.cloud.CloudErrorCode
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudFolderManifest
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpRequest
import com.musico.cloud.CloudHttpResponse
import com.musico.cloud.CloudPlaylistResult
import com.musico.cloud.CloudSource
import com.musico.cloud.CloudSourceException
import com.musico.cloud.CloudStreamDownloader
import com.musico.cloud.CloudStreamResolver
import com.musico.cloud.CloudTrackMetadata
import com.musico.cloud.GoogleDriveClient
import com.musico.cloud.ResolvedStream
import com.musico.cloud.UrlConnectionCloudHttpClient
import com.musico.cloud.YouTubeClient
import com.musico.core.ArtworkCache
import com.musico.core.MetadataReading
import com.musico.core.Persistence
import com.musico.core.TrackMetadata
import com.musico.model.CloudTrackingInfo
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.Track
import com.musico.store.LibraryException
import com.musico.store.LibraryStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

class CloudProviderRegistryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeProvider(
        override val providerId: String,
        override val displayName: String,
        override val badgeLabel: String,
        private val urlPrefix: String,
        override val supportsTree: Boolean = false,
        override val supportsCookie: Boolean = true,
        private val tracksToReturn: List<CloudTrackMetadata> = listOf(
            CloudTrackMetadata(
                remoteTrackId = "tr_1",
                title = "Fake Track",
                artist = "Fake Artist",
                album = "Fake Mix",
                durationSec = 123.0,
            )
        ),
        private val remoteIdToReturn: String = "shared-mix",
    ) : CloudSourceProvider {
        override val rootKind: RootKind = RootKind.CLOUD_EXTENSION
        override val cloudSourceSlug: String = providerId
        override val isBuiltin: Boolean = false
        override val usesFlatCloudListing: Boolean = !supportsTree

        override fun canHandle(rawInput: String): CloudUrlMatch? {
            val clean = rawInput.trim()
            if (!clean.startsWith(urlPrefix)) return null
            return CloudUrlMatch(normalizedUrl = clean, remoteId = remoteIdToReturn, kind = "playlist")
        }

        override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult =
            CloudPlaylistResult(
                source = providerId,
                remoteId = remoteIdToReturn,
                title = "$displayName Mix",
                tracks = tracksToReturn,
            )

        override suspend fun resolveStream(
            remoteTrackId: String,
            relativePath: String?,
            cookie: String?,
            forceRefresh: Boolean,
        ): ResolvedStream = ResolvedStream(
            url = "https://cdn.example.com/$remoteTrackId.flac",
            headers = mapOf("X-Provider" to providerId),
            fileExtension = "flac",
            expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
        )
    }

    private fun stubReader() = object : MetadataReading {
        override suspend fun read(uri: Uri, fileNameHint: String?): TrackMetadata = TrackMetadata()
        override suspend fun read(file: File): TrackMetadata = TrackMetadata()
    }

    private fun noopHttp() = object : CloudHttpClient {
        override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse =
            CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

        override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse =
            CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

        override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
    }

    @Test
    fun v1113LibraryJsonCompatibilityMapsBuiltinProvidersImplicitly() {
        val v1113Json = """
            {
              "roots": [
                {
                  "id": "yt-root",
                  "name": "My YouTube Mix",
                  "kind": "CLOUD_YOUTUBE",
                  "sourceUrl": "https://music.youtube.com/playlist?list=PL123",
                  "remoteId": "PL123"
                },
                {
                  "id": "bili-root",
                  "name": "Bili Fav",
                  "kind": "CLOUD_BILIBILI",
                  "sourceUrl": "https://space.bilibili.com/1/favlist?fid=42",
                  "remoteId": "fav:42"
                },
                {
                  "id": "drive-root",
                  "name": "Drive Music",
                  "kind": "CLOUD_GOOGLE_DRIVE",
                  "sourceUrl": "https://drive.google.com/drive/folders/1Drive",
                  "remoteId": "1Drive"
                },
                {
                  "id": "local-linked",
                  "name": "Saved Local",
                  "kind": "PICKED",
                  "treeUri": "file:///tmp/music",
                  "sourceUrl": "https://music.youtube.com/playlist?list=PL999",
                  "linkedCloudKind": "CLOUD_YOUTUBE",
                  "trackedSubfolders": {
                    "Sub": {
                      "sourceUrl": "https://www.bilibili.com/video/BV1xx411c7mD",
                      "remoteId": "BV1xx411c7mD",
                      "cloudKind": "CLOUD_BILIBILI"
                    }
                  }
                }
              ],
              "tracks": [],
              "readerVersion": 3
            }
        """.trimIndent()

        val stateDir = tempFolder.newFolder("compat_state")
        val docsDir = tempFolder.newFolder("compat_docs")
        File(stateDir, "library.json").writeText(v1113Json, Charsets.UTF_8)

        val store = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = stubReader(),
            artwork = ArtworkCache(File(stateDir, "art")),
        )

        val roots = store.state.value.roots.associateBy { it.id }
        val yt = roots.getValue("yt-root")
        val bili = roots.getValue("bili-root")
        val drive = roots.getValue("drive-root")
        val localLinked = roots.getValue("local-linked")

        assertTrue(yt.isCloud)
        assertEquals(CloudProviderIds.YOUTUBE, yt.resolvedProviderId)
        assertNull(yt.resolvedLinkedProviderId)
        assertTrue(yt.hasCloudMetadata)

        assertEquals(CloudProviderIds.BILIBILI, bili.resolvedProviderId)
        assertEquals(CloudProviderIds.GOOGLE_DRIVE, drive.resolvedProviderId)

        assertFalse(localLinked.isCloud)
        assertTrue(localLinked.isCloudLinked)
        assertTrue(localLinked.hasCloudMetadata)
        assertEquals(CloudProviderIds.YOUTUBE, localLinked.resolvedLinkedProviderId)
        assertEquals(CloudProviderIds.BILIBILI, localLinked.trackingFor("Sub")?.resolvedProviderId)

        val legacyManifestJson = """
            {
              "sourceURL": "https://music.youtube.com/playlist?list=PL123",
              "remoteID": "PL123",
              "cloudKind": "CLOUD_YOUTUBE",
              "lastSyncedEpochMs": 1000,
              "tracks": { "v1": "01.m4a" }
            }
        """.trimIndent()
        val decodedManifest = Persistence.json.decodeFromString<CloudFolderManifest>(legacyManifestJson)
        assertNull(decodedManifest.providerId)
        assertEquals("CLOUD_YOUTUBE", decodedManifest.cloudKind)
    }

    @Test
    fun registryClassifiesBuiltinsExtensionsAndManualHints() {
        val registry = CloudProviderRegistry()
        val extA = FakeProvider("ext.com.example.a", "Alpha Source", "Alpha", "https://alpha.test/")
        val extB = FakeProvider("ext.com.example.b", "Beta Source", "Beta", "https://beta.test/")
        registry.setExtensionProviders(listOf(extA, extB))

        assertEquals(CloudProviderIds.YOUTUBE, registry.classifyUrl("https://music.youtube.com/playlist?list=PL1234567890")?.provider?.providerId)
        assertEquals(CloudProviderIds.BILIBILI, registry.classifyUrl("https://www.bilibili.com/video/BV1xx411c7mD")?.provider?.providerId)
        assertEquals(CloudProviderIds.GOOGLE_DRIVE, registry.classifyUrl("https://drive.google.com/drive/folders/1DriveRoot")?.provider?.providerId)
        assertEquals("ext.com.example.a", registry.classifyUrl("https://alpha.test/list/1")?.provider?.providerId)
        assertEquals("ext.com.example.b", registry.classifyUrl("https://beta.test/list/2", providerIdHint = "ext.com.example.b")?.provider?.providerId)
        assertNull(registry.classifyUrl("https://beta.test/list/2", providerIdHint = "ext.com.example.a"))

        try {
            registry.classifyUrl("https://missing.test/list/1", providerIdHint = "ext.missing")
            fail("Expected EXTENSION_UNAVAILABLE")
        } catch (err: CloudSourceException) {
            assertEquals(CloudErrorCode.EXTENSION_UNAVAILABLE, err.code)
        }
    }

    @Test
    fun libraryStoreDedupesByKindProviderIdAndRemoteIdAndPreservesDataWhenDisabledOrMissing() = runBlocking {
        val stateDir = tempFolder.newFolder("dedupe_state")
        val docsDir = tempFolder.newFolder("dedupe_docs")
        val registry = CloudProviderRegistry()
        var extAEnabled = true
        var extAInstalled = true
        registry.extensionStatusLookup = { pid ->
            if (pid == "ext.com.alpha") {
                when {
                    !extAInstalled -> CloudProviderAvailability.MISSING
                    !extAEnabled -> CloudProviderAvailability.DISABLED
                    else -> CloudProviderAvailability.AVAILABLE
                }
            } else {
                CloudProviderAvailability.MISSING
            }
        }

        val extA = FakeProvider("ext.com.alpha", "Alpha", "Alpha", "https://alpha.test/", remoteIdToReturn = "shared-id")
        val extB = FakeProvider("ext.com.beta", "Beta", "Beta", "https://beta.test/", remoteIdToReturn = "shared-id")
        registry.setExtensionProviders(listOf(extA, extB))

        val store = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = stubReader(),
            artwork = ArtworkCache(File(stateDir, "art")),
            cloudHttpClient = noopHttp(),
            providerRegistry = registry,
        )

        assertTrue(store.addCloudRoot("https://alpha.test/pl/1").isSuccess)
        // Same (kind=CLOUD_EXTENSION, remoteId="shared-id") under a DIFFERENT providerId succeeds!
        assertTrue(store.addCloudRoot("https://beta.test/pl/1").isSuccess)
        // Same (kind, providerId, remoteId) is rejected as DuplicateRoot.
        val dup = store.addCloudRoot("https://alpha.test/pl/other")
        assertEquals(LibraryException.DuplicateRoot, dup.exceptionOrNull())

        val alphaRoot = store.state.value.roots.first { it.resolvedProviderId == "ext.com.alpha" }
        val alphaTrack = store.state.value.tracks.first { it.rootId == alphaRoot.id }
        val pid = store.createPlaylist("Keep Me")
        store.addToPlaylist(listOf(alphaTrack.id), pid)
        store.toggleFavourite(alphaTrack.id)
        assertTrue(store.isAvailable(alphaRoot))

        // Disable extension -> root becomes unavailable, but tracks, playlists, and favourites remain intact.
        extAEnabled = false
        registry.unregisterExtensionProvider("ext.com.alpha")
        store.refreshAvailability()
        store.rescan()

        assertFalse(store.isAvailable(alphaRoot))
        assertEquals(CloudProviderAvailability.DISABLED, store.cloudProviderAvailability(alphaRoot))
        assertNotNull(store.track(alphaTrack.id))
        assertEquals(listOf(alphaTrack.id), store.state.value.playlists.first { it.id == pid }.trackIds)
        assertTrue(store.isFavourite(alphaTrack.id))

        // Resolving stream while disabled throws EXTENSION_UNAVAILABLE.
        val resolver = CloudStreamResolver(registry)
        try {
            resolver.resolve(alphaTrack, alphaRoot)
            fail("Expected EXTENSION_UNAVAILABLE")
        } catch (err: CloudSourceException) {
            assertEquals(CloudErrorCode.EXTENSION_UNAVAILABLE, err.code)
        }

        // Uninstall extension -> availability reports MISSING, still non-destructive.
        extAInstalled = false
        assertEquals(CloudProviderAvailability.MISSING, store.cloudProviderAvailability(alphaRoot))
        assertNotNull(store.track(alphaTrack.id))

        // Re-enable extension -> root and stream resolution recover.
        extAInstalled = true
        extAEnabled = true
        registry.registerExtensionProvider(extA)
        store.refreshAvailability()
        assertTrue(store.isAvailable(alphaRoot))
        val stream = resolver.resolve(alphaTrack, alphaRoot)
        assertEquals("https://cdn.example.com/tr_1.flac", stream.url)
        assertEquals("flac", stream.fileExtension)
    }

    @Test
    fun cloudExporterWritesExtensionProviderIdAndHonorsStreamFileExtension() = runBlocking {
        val registry = CloudProviderRegistry()
        val ext = FakeProvider("ext.com.export", "ExportExt", "ExportEx", "https://export.test/")
        registry.registerExtensionProvider(ext)
        val resolver = CloudStreamResolver(registry)
        val downloadedHeaders = mutableListOf<Map<String, String>>()
        val exporter = CloudExporter(
            context = null,
            cloudResolver = resolver,
            downloader = CloudStreamDownloader { _, headers, out ->
                downloadedHeaders.add(headers)
                out.write("flac-bytes".toByteArray())
            },
            providerRegistry = registry,
        )

        val root = LibraryRoot(
            id = "ext_root",
            name = "ExportExt Mix",
            kind = RootKind.CLOUD_EXTENSION,
            sourceUrl = "https://export.test/pl/1",
            remoteId = "pl_1",
            providerId = "ext.com.export",
        )
        val track = Track(
            id = "t1",
            rootId = root.id,
            relativePath = "ext.com.export/tr_1",
            title = "Lossless Tune",
            artist = "Artist",
            album = "Album",
            albumArtist = "Artist",
            durationSec = 100.0,
            fileSize = 0L,
            modifiedEpochMs = 0L,
            dateAddedEpochMs = 1L,
            cloudSource = "ext.com.export",
            remoteTrackId = "tr_1",
        )

        val parentDir = tempFolder.newFolder("export_parent")
        val summary = exporter.exportPlaylist(listOf(track), root, parentDir, "ExtExport")
        assertEquals(1, summary.savedCount)
        val exportedFile = File(summary.targetDir, "Lossless Tune.flac")
        assertTrue(exportedFile.exists())
        val manifest = CloudExporter.loadManifest(summary.targetDir!!)
        assertEquals("ext.com.export", manifest?.providerId)
        assertEquals(RootKind.CLOUD_EXTENSION.name, manifest?.cloudKind)
        assertEquals("ext.com.export", downloadedHeaders.single()["X-Provider"])
    }

    @Test
    fun urlConnectionGenericRequestEnforcesRedirectsHeaderStrippingSizeCapAndBase64() = runBlocking {
        val capturedAuthByPath = mutableMapOf<String, Map<String, String>>()
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val port = server.localPort

        executor.submit {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val requestLine = reader.readLine() ?: ""
                    val path = requestLine.split(" ").getOrElse(1) { "/" }
                    val hdrs = linkedMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            hdrs[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                        }
                    }
                    capturedAuthByPath[path] = hdrs

                    val out = socket.getOutputStream()
                    when {
                        path == "/hop-cross-host" -> {
                            val resp = "HTTP/1.1 302 Found\r\nLocation: http://localhost:$port/final-cross\r\nContent-Length: 0\r\n\r\n"
                            out.write(resp.toByteArray())
                        }
                        path == "/final-cross" -> {
                            val body = "hello-redirect".toByteArray()
                            val resp = "HTTP/1.1 200 OK\r\nX-Custom-Hdr: Val\r\nContent-Length: ${body.size}\r\n\r\n"
                            out.write(resp.toByteArray())
                            out.write(body)
                        }
                        path.startsWith("/loop/") -> {
                            val n = path.removePrefix("/loop/").toInt()
                            val resp = "HTTP/1.1 302 Found\r\nLocation: /loop/${n + 1}\r\nContent-Length: 0\r\n\r\n"
                            out.write(resp.toByteArray())
                        }
                        path == "/large-2mb" -> {
                            val body = ByteArray(2 * 1024 * 1024) { 'A'.code.toByte() }
                            val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\n\r\n"
                            out.write(resp.toByteArray())
                            out.write(body)
                        }
                        else -> {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        }
                    }
                    out.flush()
                    socket.close()
                }
            }
        }

        try {
            val client = UrlConnectionCloudHttpClient()

            // 1. Cross-host redirect strips Cookie & Authorization, normalizes response headers to lowercase, includes bytesBase64 <= 1MB.
            val crossRes = client.request(
                CloudHttpRequest(
                    method = "GET",
                    url = "http://127.0.0.1:$port/hop-cross-host",
                    headers = mapOf("Cookie" to "secret=1", "Authorization" to "Bearer tok", "X-Keep" to "yes"),
                    followRedirects = true,
                )
            )
            assertEquals(200, crossRes.status)
            assertEquals("hello-redirect", crossRes.text)
            assertEquals("Val", crossRes.headers["x-custom-hdr"])
            assertNotNull(crossRes.bytesBase64)
            assertTrue(capturedAuthByPath["/hop-cross-host"]?.containsKey("cookie") == true)
            assertFalse(capturedAuthByPath["/final-cross"]?.containsKey("cookie") == true)
            assertFalse(capturedAuthByPath["/final-cross"]?.containsKey("authorization") == true)
            assertEquals("yes", capturedAuthByPath["/final-cross"]?.get("x-keep"))

            // 2. > 1 MB omits bytesBase64 unless returnBytes == true.
            val bigDefault = client.request(CloudHttpRequest(url = "http://127.0.0.1:$port/large-2mb", returnBytes = false))
            assertEquals(200, bigDefault.status)
            assertEquals(2 * 1024 * 1024, bigDefault.text.length)
            assertNull(bigDefault.bytesBase64)

            val bigWithBytes = client.request(CloudHttpRequest(url = "http://127.0.0.1:$port/large-2mb", returnBytes = true))
            assertNotNull(bigWithBytes.bytesBase64)

            // 3. > 10 redirect hops fails with NETWORK_ERROR.
            try {
                client.request(CloudHttpRequest(url = "http://127.0.0.1:$port/loop/0", followRedirects = true))
                fail("Expected too many redirects error")
            } catch (err: CloudSourceException) {
                assertEquals(CloudErrorCode.NETWORK_ERROR, err.code)
            }
        } finally {
            runCatching { server.close() }
            executor.shutdownNow()
        }
    }
}
