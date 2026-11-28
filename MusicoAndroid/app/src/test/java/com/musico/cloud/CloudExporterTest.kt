package com.musico.cloud

import android.net.Uri
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
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.Track
import com.musico.store.CloudDownloadJob
import com.musico.store.CloudDownloadKind
import com.musico.store.CloudDownloadManager
import com.musico.store.CloudDownloadStatus
import com.musico.store.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

class CloudExporterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeCloudStreamResolver(
        private val onResolve: suspend (Track, LibraryRoot?, Boolean) -> ResolvedStream,
    ) : CloudStreamResolver() {
        val invalidateCalls = mutableListOf<String>()
        val requestedQualities = mutableListOf<String?>()

        override suspend fun resolve(
            track: Track,
            root: LibraryRoot?,
            forceRefresh: Boolean,
        ): ResolvedStream = onResolve(track, root, forceRefresh)

        override suspend fun resolve(
            track: Track,
            root: LibraryRoot?,
            forceRefresh: Boolean,
            purpose: CloudStreamPurpose,
            requestedQualityId: String?,
        ): ResolvedStream {
            requestedQualities.add(requestedQualityId)
            return onResolve(track, root, forceRefresh)
        }

        override fun invalidate(track: Track, root: LibraryRoot?) {
            invalidateCalls.add(track.id)
        }
    }

    private fun cloudTrack(
        id: String,
        title: String,
        remoteId: String = "remote_$id",
        source: String = CloudSource.YOUTUBE,
    ): Track = Track(
        id = id,
        rootId = "root_1",
        relativePath = "cloud/$remoteId",
        title = title,
        artist = "Artist",
        album = "Album",
        albumArtist = "Artist",
        durationSec = 180.0,
        fileSize = 0L,
        modifiedEpochMs = 0L,
        dateAddedEpochMs = 1000L,
        cloudSource = source,
        remoteTrackId = remoteId,
    )

    private fun cloudRoot(): LibraryRoot = LibraryRoot(
        id = "root_1",
        name = "My: Cloud / Mix?",
        kind = RootKind.CLOUD_YOUTUBE,
        sourceUrl = "https://music.youtube.com/playlist?list=PL123",
        remoteId = "PL123",
        cookie = "SID=abc",
    )

    @Test
    fun sanitizeFileNameHandlesIllegalCharsDotsBlankAndUnicode() {
        assertEquals("A_B_C_D_E_F_G_H_I", CloudExporter.sanitizeFileName("A/B\\C:D*E?F\"G<H>I"))
        assertEquals("曲名 日本語 (Live)", CloudExporter.sanitizeFileName("  .曲名   日本語 (Live).  "))
        assertEquals("fallback", CloudExporter.sanitizeFileName("   ", "fallback"))
        assertEquals("fallback", CloudExporter.sanitizeFileName("...", "fallback"))
        assertEquals("fallback", CloudExporter.sanitizeFileName("..", "fallback"))
        assertEquals("a_b", CloudExporter.sanitizeFileName("a\u0000b"))
        val longInput = "x".repeat(200)
        assertTrue(CloudExporter.sanitizeFileName(longInput).length <= 120)
    }

    @Test
    fun uniqueFileNameAvoidsCaseInsensitiveCollisions() {
        val existing = mutableSetOf("Song.m4a", "song (2).m4a", "Folder")
        assertEquals("Other.m4a", CloudExporter.uniqueFileName(existing, "Other", "m4a"))
        assertEquals("Song (3).m4a", CloudExporter.uniqueFileName(existing, "Song", "m4a"))
        assertEquals("song (3).m4a", CloudExporter.uniqueFileName(existing, "song", "M4A"))
        assertEquals("Folder (2)", CloudExporter.uniqueFileName(existing, "Folder", ""))
    }

    @Test
    fun exportPlaylistCreatesSanitizedSubfolderAndHandlesDuplicateTitles() = runBlocking {
        val parent = tempFolder.newFolder("exports")
        val root = cloudRoot()
        val tracks = listOf(
            cloudTrack("t1", "Song: One"),
            cloudTrack("t2", "Song: One"),
            cloudTrack("t3", "Track/Three"),
        )
        val resolver = FakeCloudStreamResolver { track, passedRoot, _ ->
            assertEquals(root, passedRoot)
            val mime = if (track.id == "t3") "audio/webm; codecs=\"opus\"" else "audio/mp4"
            ResolvedStream(
                url = "https://cdn.example.com/${track.id}",
                headers = mapOf("Referer" to "https://www.youtube.com/"),
                mimeType = mime,
            )
        }
        val recordedHeaders = mutableListOf<Map<String, String>>()
        val downloader = CloudStreamDownloader { url, headers, output ->
            recordedHeaders.add(headers)
            output.write("audio:$url".toByteArray(Charsets.UTF_8))
        }

        val progressEvents = mutableListOf<Triple<Int, Int, String>>()
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
                override fun canHandle(rawInput: String): CloudUrlMatch? = null
                override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult =
                    CloudPlaylistResult(providerId, "PL123", "Mix", emptyList())
                override suspend fun resolveStream(
                    remoteTrackId: String,
                    relativePath: String?,
                    cookie: String?,
                    forceRefresh: Boolean,
                    quality: String?,
                    purpose: CloudStreamPurpose,
                ): ResolvedStream = ResolvedStream("https://cdn.example.com/$remoteTrackId")
                override fun downloadHeaders(stream: ResolvedStream): Map<String, String> =
                    stream.headers + ("Range" to "bytes=0-")
            }
        )
        val exporter = CloudExporter(
            context = null,
            cloudResolver = resolver,
            downloader = downloader,
            providerRegistry = registry,
        )
        val summary = exporter.exportPlaylist(
            tracks = tracks,
            root = root,
            parentDir = parent,
            folderName = "My: Cloud / Mix?",
            onProgress = { done, total, title ->
                progressEvents.add(Triple(done, total, title))
            },
        )

        assertEquals(3, summary.savedCount)
        assertEquals(0, summary.failedCount)
        assertTrue(summary.errors.isEmpty())
        val targetDir = summary.targetDir
        assertNotNull(targetDir)
        assertEquals("My_ Cloud _ Mix_", targetDir!!.name)

        val savedFiles = targetDir.listFiles()!!
            .map { it.name }
            .filter { it != CloudExporter.MANIFEST_FILE_NAME }
            .sorted()
        assertEquals(listOf("Song_ One (2).m4a", "Song_ One.m4a", "Track_Three.webm"), savedFiles)
        val manifest = CloudExporter.loadManifest(targetDir)
        assertNotNull(manifest)
        assertEquals(root.sourceUrl, manifest!!.sourceURL)
        assertEquals(RootKind.CLOUD_YOUTUBE.name, manifest.cloudKind)
        assertEquals(3, manifest.tracks.size)
        assertEquals("audio:https://cdn.example.com/t1", File(targetDir, "Song_ One.m4a").readText())
        assertEquals("audio:https://cdn.example.com/t2", File(targetDir, "Song_ One (2).m4a").readText())
        assertEquals("audio:https://cdn.example.com/t3", File(targetDir, "Track_Three.webm").readText())
        assertTrue(recordedHeaders.all { it["Referer"] == "https://www.youtube.com/" })
        assertTrue(recordedHeaders.all { it["Range"] == "bytes=0-" })
        assertTrue(progressEvents.contains(Triple(0, 3, "Song: One")))
        assertTrue(progressEvents.contains(Triple(3, 3, "Track/Three")))
    }

    @Test
    fun exportTrackUsesCollisionSafeNameAndRetriesRefreshedStream() = runBlocking {
        val dir = tempFolder.newFolder("single")
        File(dir, "Hello.m4a").writeText("existing")
        val root = cloudRoot()
        val track = cloudTrack("t1", "Hello")

        val resolver = FakeCloudStreamResolver { _, _, forceRefresh ->
            ResolvedStream(
                url = if (forceRefresh) "https://cdn.example.com/fresh" else "https://cdn.example.com/stale",
                mimeType = "audio/mp4",
            )
        }
        val downloader = CloudStreamDownloader { url, _, output ->
            if (url.endsWith("stale")) {
                output.write("partial".toByteArray())
                throw IOException("HTTP 403")
            }
            output.write("fresh-bytes".toByteArray())
        }

        val exporter = CloudExporter(context = null, cloudResolver = resolver, downloader = downloader)
        val savedName = exporter.exportTrack(track, root, dir)

        assertEquals("Hello (2).m4a", savedName)
        assertEquals(listOf("t1"), resolver.invalidateCalls)
        assertEquals("existing", File(dir, "Hello.m4a").readText())
        assertEquals("fresh-bytes", File(dir, "Hello (2).m4a").readText())
        assertFalse(File(dir, ".Hello (2).m4a.part").exists())
    }

    @Test
    fun exportPlaylistContinuesOnPartialFailureAndCleansUpOnCancellation() = runBlocking {
        val parent = tempFolder.newFolder("partial")
        val root = cloudRoot()
        val tracks = listOf(
            cloudTrack("ok1", "First"),
            cloudTrack("bad", "Broken"),
            cloudTrack("ok2", "Third"),
        )
        val resolver = FakeCloudStreamResolver { track, _, _ ->
            if (track.id == "bad") throw IOException("Stream unavailable")
            ResolvedStream(url = "https://cdn.example.com/${track.id}", mimeType = "audio/mp4")
        }
        val downloader = CloudStreamDownloader { url, _, output ->
            output.write("bytes:$url".toByteArray())
        }

        val exporter = CloudExporter(context = null, cloudResolver = resolver, downloader = downloader)
        val summary = exporter.exportPlaylist(tracks, root, parent, "Partial Test")

        assertEquals(2, summary.savedCount)
        assertEquals(1, summary.failedCount)
        assertEquals(1, summary.errors.size)
        assertTrue(summary.errors[0].contains("Broken"))
        val names = summary.targetDir!!.listFiles()!!
            .map { it.name }
            .filter { it != CloudExporter.MANIFEST_FILE_NAME }
            .sorted()
        assertEquals(listOf("First.m4a", "Third.m4a"), names)

        // Cancellation rethrows and leaves no partial files behind.
        val cancelDir = tempFolder.newFolder("cancel")
        val cancelExporter = CloudExporter(
            context = null,
            cloudResolver = FakeCloudStreamResolver { _, _, _ ->
                ResolvedStream("https://cdn.example.com/x", mimeType = "audio/mp4")
            },
            downloader = CloudStreamDownloader { _, _, output: OutputStream ->
                output.write("partial".toByteArray())
                throw CancellationException("user cancelled")
            },
        )
        var cancelled = false
        try {
            cancelExporter.exportPlaylist(listOf(cloudTrack("c1", "Cancelled")), root, cancelDir, "Cancelled")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(0, File(cancelDir, "Cancelled").listFiles()?.size ?: 0)
    }

    @Test
    fun updateLocalFolderDownloadsOnlyNewAndMissingAndUpdatesManifest() = runBlocking {
        val parent = tempFolder.newFolder("tracked_parent")
        val root = cloudRoot()
        val initialTracks = listOf(
            cloudTrack("t1", "Song One", remoteId = "vid_1"),
            cloudTrack("t2", "Song Two", remoteId = "vid_2"),
        )
        val resolvedIds = mutableListOf<String>()
        val exporter = CloudExporter(
            context = null,
            cloudResolver = FakeCloudStreamResolver { track, _, _ ->
                resolvedIds.add(track.remoteTrackId ?: track.id)
                ResolvedStream(
                    url = "https://cdn.example.com/${track.remoteTrackId}",
                    mimeType = "audio/mp4",
                )
            },
            downloader = CloudStreamDownloader { url, _, output ->
                output.write("audio:$url".toByteArray(Charsets.UTF_8))
            },
        )
        val exportSummary = exporter.exportPlaylist(initialTracks, root, parent, "Tracked Mix")
        val targetDir = exportSummary.targetDir!!
        assertEquals(2, exportSummary.savedCount)

        // Delete Song Two on disk while leaving Song One intact; keep an unrelated file too.
        File(targetDir, "Song Two.m4a").delete()
        File(targetDir, "Custom Note.txt").writeText("keep")
        resolvedIds.clear()

        val trackedRoot = LibraryRoot(
            id = "local_tracked",
            name = "Tracked Mix",
            kind = RootKind.PICKED,
            treeUri = targetDir.toURI().toString(),
            sourceUrl = root.sourceUrl,
            remoteId = root.remoteId,
            linkedCloudKind = RootKind.CLOUD_YOUTUBE,
        )
        val updateSummary = exporter.updateLocalFolder(
            root = trackedRoot,
            targetDir = targetDir,
            fetchPlaylist = { _, _, _ ->
                CloudPlaylistResult(
                    source = CloudSource.YOUTUBE,
                    remoteId = "PL123",
                    title = "Tracked Mix",
                    tracks = listOf(
                        CloudTrackMetadata("vid_1", "Song One", "Artist", "Album", 180.0),
                        CloudTrackMetadata("vid_2", "Song Two", "Artist", "Album", 200.0),
                        CloudTrackMetadata("vid_3", "Song Three", "Artist", "Album", 210.0),
                    ),
                )
            },
        )

        assertEquals(2, updateSummary.addedCount)
        assertEquals(1, updateSummary.skippedCount)
        assertEquals(0, updateSummary.failedCount)
        assertEquals(3, updateSummary.totalRemoteCount)
        assertEquals(listOf("vid_2", "vid_3"), resolvedIds)
        assertTrue(File(targetDir, "Song One.m4a").exists())
        assertTrue(File(targetDir, "Song Two.m4a").exists())
        assertTrue(File(targetDir, "Song Three.m4a").exists())
        assertTrue(File(targetDir, "Custom Note.txt").exists())

        val updatedManifest = CloudExporter.loadManifest(targetDir)
        assertNotNull(updatedManifest)
        assertEquals(3, updatedManifest!!.tracks.size)
        assertEquals("Song Three.m4a", updatedManifest.tracks["vid_3"])
    }

    @Test
    fun exportAndUpdateLocalFolderSupportGoogleDriveSource() = runBlocking {
        val parent = tempFolder.newFolder("gdrive_exports")
        val gdriveRoot = LibraryRoot(
            id = "gdrive_1",
            name = "Drive Album",
            kind = RootKind.CLOUD_GOOGLE_DRIVE,
            sourceUrl = "https://drive.google.com/drive/folders/1DriveFolder",
            remoteId = "1DriveFolder",
        )
        val initialTracks = listOf(
            cloudTrack("g1", "Track One", remoteId = "file1.flac", source = CloudSource.GOOGLE_DRIVE),
        )
        val exporter = CloudExporter(
            context = null,
            cloudResolver = FakeCloudStreamResolver { track, _, _ ->
                ResolvedStream(
                    url = "https://drive.usercontent.google.com/download?id=${track.remoteTrackId}",
                    mimeType = "audio/flac",
                )
            },
            downloader = CloudStreamDownloader { url, _, output ->
                output.write("flac:$url".toByteArray(Charsets.UTF_8))
            },
        )

        val exportSummary = exporter.exportPlaylist(initialTracks, gdriveRoot, parent, "Drive Album")
        val targetDir = exportSummary.targetDir!!
        assertEquals(1, exportSummary.savedCount)
        assertTrue(File(targetDir, "Track One.flac").exists())

        val manifest = CloudExporter.loadManifest(targetDir)!!
        assertEquals(RootKind.CLOUD_GOOGLE_DRIVE.name, manifest.cloudKind)
        assertEquals("Track One.flac", manifest.tracks["file1.flac"])

        val trackedRoot = LibraryRoot(
            id = "local_gdrive",
            name = "Drive Album",
            kind = RootKind.PICKED,
            treeUri = targetDir.toURI().toString(),
            sourceUrl = gdriveRoot.sourceUrl,
            remoteId = gdriveRoot.remoteId,
            linkedCloudKind = RootKind.CLOUD_GOOGLE_DRIVE,
        )
        val updateSummary = exporter.updateLocalFolder(
            root = trackedRoot,
            targetDir = targetDir,
            fetchPlaylist = { _, _, kindHint ->
                assertEquals(RootKind.CLOUD_GOOGLE_DRIVE, kindHint)
                CloudPlaylistResult(
                    source = CloudSource.GOOGLE_DRIVE,
                    remoteId = "1DriveFolder",
                    title = "Drive Album",
                    tracks = listOf(
                        CloudTrackMetadata("file1.flac", "Track One", "Google Drive", "Drive Album", 0.0),
                        CloudTrackMetadata("file2.flac", "Track Two", "Google Drive", "Drive Album", 0.0),
                    ),
                )
            },
        )
        assertEquals(1, updateSummary.addedCount)
        assertEquals(1, updateSummary.skippedCount)
        assertTrue(File(targetDir, "Track Two.flac").exists())
        val updatedManifest = CloudExporter.loadManifest(targetDir)!!
        assertEquals(RootKind.CLOUD_GOOGLE_DRIVE.name, updatedManifest.cloudKind)
        assertEquals("Track Two.flac", updatedManifest.tracks["file2.flac"])
    }

    private class LocalTestHttpServer(
        private val handler: (path: String, headers: Map<String, String>) -> HttpReply,
    ) : Closeable {
        data class HttpReply(
            val statusCode: Int,
            val headers: Map<String, String> = emptyMap(),
            val body: ByteArray,
            val offset: Int = 0,
            val length: Int = body.size,
        )

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val port: Int = socket.localPort

        init {
            executor.submit {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    try {
                        client.use { conn ->
                            val reader = BufferedReader(InputStreamReader(conn.getInputStream(), Charsets.ISO_8859_1))
                            val requestLine = reader.readLine() ?: return@use
                            val path = requestLine.split(' ').getOrNull(1) ?: "/"
                            val reqHeaders = linkedMapOf<String, String>()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                                val colon = line.indexOf(':')
                                if (colon > 0) {
                                    reqHeaders[line.substring(0, colon).trim().lowercase()] =
                                        line.substring(colon + 1).trim()
                                }
                            }
                            val reply = handler(path, reqHeaders)
                            val out = conn.getOutputStream()
                            val statusText = if (reply.statusCode == 206) "Partial Content" else "OK"
                            val headerText = buildString {
                                append("HTTP/1.1 ").append(reply.statusCode).append(' ').append(statusText).append("\r\n")
                                append("Content-Length: ").append(reply.length).append("\r\n")
                                append("Connection: close\r\n")
                                for ((k, v) in reply.headers) {
                                    append(k).append(": ").append(v).append("\r\n")
                                }
                                append("\r\n")
                            }
                            out.write(headerText.toByteArray(Charsets.ISO_8859_1))
                            out.write(reply.body, reply.offset, reply.length)
                            out.flush()
                        }
                    } catch (_: Throwable) {
                        // Ignore socket teardown races on close()
                    }
                }
            }
        }

        override fun close() {
            runCatching { socket.close() }
            executor.shutdownNow()
        }
    }

    @Test
    fun exportTrackUsesBounded4MbRangeChunksForLargeYouTubeStreams() = runBlocking {
        val totalSize = 9 * 1024 * 1024 + 123
        val payload = ByteArray(totalSize) { index -> ((index * 31 + 7) and 0xFF).toByte() }
        val recordedRanges = mutableListOf<String?>()

        LocalTestHttpServer { _, headers ->
            val rangeHeader = headers["range"]
            recordedRanges.add(rangeHeader)
            val rangeSpec = rangeHeader?.removePrefix("bytes=")?.split('-')
            val start = rangeSpec?.getOrNull(0)?.toIntOrNull() ?: 0
            val requestedEnd = rangeSpec?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                ?: (totalSize - 1)
            val end = minOf(requestedEnd, totalSize - 1)
            val length = end - start + 1
            LocalTestHttpServer.HttpReply(
                statusCode = 206,
                headers = mapOf("Content-Range" to "bytes $start-$end/$totalSize"),
                body = payload,
                offset = start,
                length = length,
            )
        }.use { server ->
            val baseUrl = "http://127.0.0.1:${server.port}/audio"
            val dir = tempFolder.newFolder("chunked_export")
            val root = cloudRoot()
            val track = cloudTrack("big1", "Large YouTube Mix")
            val resolver = FakeCloudStreamResolver { _, _, _ ->
                ResolvedStream(
                    url = baseUrl,
                    headers = mapOf(
                        "User-Agent" to "TestAgent",
                        "X-YouTube-Client-Name" to "101",
                    ),
                    mimeType = "audio/mp4",
                    contentLength = totalSize.toLong(),
                )
            }

            val exporter = CloudExporter(context = null, cloudResolver = resolver)
            val savedName = exporter.exportTrack(track, root, dir)

            assertEquals("Large YouTube Mix.m4a", savedName)
            assertEquals(
                listOf(
                    "bytes=0-4194303",
                    "bytes=4194304-8388607",
                    "bytes=8388608-${totalSize - 1}",
                ),
                recordedRanges,
            )
            assertArrayEquals(payload, File(dir, savedName).readBytes())
        }
    }

    @Test
    fun urlConnectionDownloaderHandlesSmall200FallbackAndAuthorizationGuards() = runBlocking {
        val largeSize = 5 * 1024 * 1024 + 50
        val largePayload = ByteArray(largeSize) { index -> (index % 251).toByte() }
        val smallPayload = "small-audio-bytes".toByteArray(Charsets.UTF_8)
        val recordedRanges = mutableListOf<Pair<String, String?>>()

        LocalTestHttpServer { path, headers ->
            val rangeHeader = headers["range"]
            recordedRanges.add(path to rangeHeader)
            when (path) {
                "/small" -> LocalTestHttpServer.HttpReply(statusCode = 200, body = smallPayload)
                "/no-range-200" -> LocalTestHttpServer.HttpReply(statusCode = 200, body = largePayload)
                "/range-partial" -> {
                    if (rangeHeader != null && rangeHeader != "bytes=0-") {
                        val parts = rangeHeader.removePrefix("bytes=").split('-')
                        val start = parts[0].toInt()
                        val end = minOf(parts[1].toInt(), largeSize - 1)
                        val len = end - start + 1
                        LocalTestHttpServer.HttpReply(
                            statusCode = 206,
                            headers = mapOf("Content-Range" to "bytes $start-$end/$largeSize"),
                            body = largePayload,
                            offset = start,
                            length = len,
                        )
                    } else {
                        LocalTestHttpServer.HttpReply(statusCode = 200, body = largePayload)
                    }
                }
                else -> LocalTestHttpServer.HttpReply(statusCode = 404, body = ByteArray(0))
            }
        }.use { server ->
            val base = "http://127.0.0.1:${server.port}"
            val downloader = UrlConnectionCloudStreamDownloader()

            // <= 4 MB stays single-GET
            val smallOut = ByteArrayOutputStream()
            downloader.download("$base/small", mapOf("Range" to "bytes=0-"), smallPayload.size.toLong(), smallOut)
            assertArrayEquals(smallPayload, smallOut.toByteArray())
            assertEquals(listOf("/small" to "bytes=0-"), recordedRanges)

            // Server ignoring Range (returning 200 on chunk 0) completes in 1 request without duplicating bytes
            recordedRanges.clear()
            val fallbackOut = ByteArrayOutputStream()
            downloader.download("$base/no-range-200", emptyMap(), largeSize.toLong(), fallbackOut)
            assertArrayEquals(largePayload, fallbackOut.toByteArray())
            assertEquals(listOf("/no-range-200" to "bytes=0-4194303"), recordedRanges)

            // Non-YouTube Authorization header skips chunking
            recordedRanges.clear()
            val authOut = ByteArrayOutputStream()
            downloader.download(
                "$base/range-partial",
                mapOf("Authorization" to "Bearer token"),
                largeSize.toLong(),
                authOut,
            )
            assertArrayEquals(largePayload, authOut.toByteArray())
            assertEquals(listOf("/range-partial" to null), recordedRanges)

            // Signed-in YouTube stream (X-YouTube-Client-Name + Authorization) still chunks in 4 MB pieces
            recordedRanges.clear()
            val ytSignedOut = ByteArrayOutputStream()
            downloader.download(
                "$base/range-partial",
                mapOf(
                    "Authorization" to "SAPISIDHASH 123_abc",
                    "X-YouTube-Client-Name" to "101",
                    "Range" to "bytes=0-",
                ),
                largeSize.toLong(),
                ytSignedOut,
            )
            assertArrayEquals(largePayload, ytSignedOut.toByteArray())
            assertEquals(
                listOf(
                    "/range-partial" to "bytes=0-4194303",
                    "/range-partial" to "bytes=4194304-${largeSize - 1}",
                ),
                recordedRanges,
            )
        }
    }

    @Test
    fun transferSpeedMeterComputesSlidingWindowRateThrottlesAndResets() {
        var nowNs = 1_000_000_000L
        val meter = TransferSpeedMeter(
            windowNs = 1_500_000_000L,
            throttleNs = 120_000_000L,
            nanoClock = { nowNs },
        )

        // Initial sample -> dt == 0 -> 0.0 rate
        assertEquals(0.0, meter.record(0L, forceEmit = false)!!, 0.001)

        // Within throttleNs (50ms) -> suppressed unless forceEmit = true
        nowNs += 50_000_000L
        assertNull(meter.record(100_000L, forceEmit = false))

        // At 500ms -> 1,000,000 bytes in 0.5s => 2,000,000 B/s
        nowNs += 450_000_000L
        val rate1 = meter.record(1_000_000L, forceEmit = false)!!
        assertEquals(2_000_000.0, rate1, 1_000.0)

        // Advance past window (now = 3.0s, adding +1,500,000 bytes in 1.5s => ~1,000,000 B/s)
        nowNs += 1_000_000_000L
        meter.record(1_500_000L, forceEmit = true)
        nowNs += 500_000_000L
        val rate2 = meter.record(2_500_000L, forceEmit = true)!!
        assertTrue(rate2 > 900_000.0 && rate2 < 1_600_000.0)

        // Rollback on retry (bytesDownloaded < lastBytes) clears samples and returns 0.0
        assertEquals(0.0, meter.record(500L, forceEmit = false)!!, 0.001)
    }

    @Test
    fun cloudExportProgressComputesTrackAndBlendedOverallFraction() {
        val unknownLen = CloudExportProgress(
            completedTracks = 1,
            totalTracks = 4,
            currentTrackTitle = "Track 2",
            trackBytesDownloaded = 512L,
            trackTotalBytes = null,
        )
        assertNull(unknownLen.trackFraction)
        assertEquals(0.25f, unknownLen.overallFraction, 0.001f)

        val halfwaySecond = CloudExportProgress(
            completedTracks = 1,
            totalTracks = 4,
            currentTrackTitle = "Track 2",
            trackBytesDownloaded = 500L,
            trackTotalBytes = 1000L,
        )
        assertEquals(0.5f, halfwaySecond.trackFraction!!, 0.001f)
        assertEquals(0.375f, halfwaySecond.overallFraction, 0.001f)

        val done = CloudExportProgress(
            completedTracks = 4,
            totalTracks = 4,
            currentTrackTitle = "Track 4",
            trackBytesDownloaded = 1000L,
            trackTotalBytes = 1000L,
        )
        assertEquals(1f, done.overallFraction, 0.001f)
    }

    @Test
    fun urlConnectionDownloaderReportsLiveBytesProgressAcrossSingleAndChunkedTransfers() = runBlocking {
        val smallPayload = ByteArray(16_384) { (it % 199).toByte() }
        val largeSize = 4 * 1024 * 1024 + 512 * 1024
        val largePayload = ByteArray(largeSize) { (it % 251).toByte() }

        LocalTestHttpServer { path, headers ->
            when (path) {
                "/small" -> LocalTestHttpServer.HttpReply(statusCode = 200, body = smallPayload)
                "/large" -> {
                    val range = headers["range"]?.removePrefix("bytes=")?.split('-')
                    val start = range?.getOrNull(0)?.toIntOrNull() ?: 0
                    val end = minOf(range?.getOrNull(1)?.toIntOrNull() ?: (largeSize - 1), largeSize - 1)
                    LocalTestHttpServer.HttpReply(
                        statusCode = 206,
                        headers = mapOf("Content-Range" to "bytes $start-$end/$largeSize"),
                        body = largePayload,
                        offset = start,
                        length = end - start + 1,
                    )
                }
                else -> LocalTestHttpServer.HttpReply(statusCode = 404, body = ByteArray(0))
            }
        }.use { server ->
            val downloader = UrlConnectionCloudStreamDownloader()
            val smallEvents = mutableListOf<ByteTransferProgress>()
            val smallOut = ByteArrayOutputStream()
            downloader.download(
                url = "http://127.0.0.1:${server.port}/small",
                headers = emptyMap(),
                contentLength = smallPayload.size.toLong(),
                output = smallOut,
            ) { smallEvents.add(it) }
            assertArrayEquals(smallPayload, smallOut.toByteArray())
            assertTrue(smallEvents.isNotEmpty())
            assertEquals(smallPayload.size.toLong(), smallEvents.last().bytesDownloaded)
            assertEquals(smallPayload.size.toLong(), smallEvents.last().totalBytes)

            val chunkEvents = mutableListOf<ByteTransferProgress>()
            val largeOut = ByteArrayOutputStream()
            downloader.download(
                url = "http://127.0.0.1:${server.port}/large",
                headers = emptyMap(),
                contentLength = largeSize.toLong(),
                output = largeOut,
            ) { chunkEvents.add(it) }
            assertArrayEquals(largePayload, largeOut.toByteArray())
            assertTrue(chunkEvents.size >= 2)
            assertEquals(largeSize.toLong(), chunkEvents.last().bytesDownloaded)
            assertEquals(largeSize.toLong(), chunkEvents.last().totalBytes)
            assertTrue(chunkEvents.zipWithNext().all { (a, b) -> b.bytesDownloaded >= a.bytesDownloaded })
        }
    }

    @Test
    fun exporterDetailedProgressAndUpdateLocalFolderManifestPrecedenceAndSubfolder() = runBlocking {
        val parentDir = tempFolder.newFolder("export_parent")
        val resolver = FakeCloudStreamResolver { track, _, _ ->
            ResolvedStream(
                url = "https://cdn.example/${track.remoteTrackId}.m4a",
                mimeType = "audio/mp4",
                contentLength = 1000L,
            )
        }
        val downloader = object : CloudStreamDownloader {
            override suspend fun download(url: String, headers: Map<String, String>, output: OutputStream) {
                download(url, headers, 1000L, output, null)
            }

            override suspend fun download(
                url: String,
                headers: Map<String, String>,
                contentLength: Long?,
                output: OutputStream,
                onBytesProgress: ((ByteTransferProgress) -> Unit)?,
            ) {
                val payload = ByteArray(1000) { 7 }
                onBytesProgress?.invoke(ByteTransferProgress(500L, 1000L, 1024.0))
                output.write(payload, 0, 500)
                onBytesProgress?.invoke(ByteTransferProgress(1000L, 1000L, 2048.0))
                output.write(payload, 500, 500)
            }
        }
        val exporter = CloudExporter(context = null, cloudResolver = resolver, downloader = downloader)
        val root = cloudRoot()
        val t1 = cloudTrack("t1", "Song 1", remoteId = "r1")
        val t2 = cloudTrack("t2", "Song 2", remoteId = "r2")

        val detailed = mutableListOf<CloudExportProgress>()
        val summary = exporter.exportPlaylist(
            tracks = listOf(t1, t2),
            root = root,
            parentDir = parentDir,
            folderName = "My Mix",
            onDetailedProgress = { detailed.add(it) },
        )
        assertEquals(2, summary.savedCount)
        assertTrue(detailed.any { it.completedTracks == 0 && (it.trackFraction ?: 0f) > 0f && it.overallFraction > 0f })
        assertTrue(detailed.any { it.completedTracks == 2 && it.overallFraction == 1f })

        // Update tracked subfolder where stale manifest has old URL, but explicit override URL takes precedence AND rewrites manifest!
        val localRootDir = tempFolder.newFolder("local_root")
        val subDir = File(localRootDir, "Nested/SubMix").apply { mkdirs() }
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val staleManifest = CloudFolderManifest(
            sourceURL = "https://www.youtube.com/playlist?list=STALE_MANIFEST_URL",
            remoteID = "STALE_MANIFEST_URL",
            cloudKind = RootKind.CLOUD_YOUTUBE.name,
            lastSyncedEpochMs = 100L,
            tracks = mapOf("r1" to "Song 1.m4a"),
        )
        File(subDir, CloudExporter.MANIFEST_FILE_NAME).writeText(json.encodeToString(staleManifest), Charsets.UTF_8)
        File(subDir, "Song 1.m4a").writeBytes(ByteArray(100))

        var requestedUrl: String? = null
        val localRoot = LibraryRoot(
            id = "picked-1",
            name = "local_root",
            kind = RootKind.PICKED,
            treeUri = localRootDir.toURI().toString(),
        )
        val updateSummary = exporter.updateLocalFolder(
            root = localRoot,
            targetDir = localRootDir,
            fetchPlaylist = { sourceUrl, _, _ ->
                requestedUrl = sourceUrl
                CloudPlaylistResult(
                    source = CloudSource.BILIBILI,
                    remoteId = "BV_EDITED",
                    title = "Edited Bili",
                    tracks = listOf(
                        CloudTrackMetadata(
                            remoteTrackId = "r1",
                            title = "Song 1",
                            artist = "Artist",
                            album = "Edited Bili",
                            durationSec = 10.0,
                            trackNumber = 1,
                        ),
                        CloudTrackMetadata(
                            remoteTrackId = "r2",
                            title = "Song 2",
                            artist = "Artist",
                            album = "Edited Bili",
                            durationSec = 10.0,
                            trackNumber = 2,
                        ),
                    ),
                )
            },
            overrideSourceUrl = "https://www.bilibili.com/video/BV_EDITED",
            overrideCloudKind = RootKind.CLOUD_BILIBILI,
            subfolderPath = "Nested/SubMix",
        )

        assertEquals("https://www.bilibili.com/video/BV_EDITED", requestedUrl)
        assertEquals(1, updateSummary.addedCount)
        assertEquals(1, updateSummary.skippedCount)
        assertTrue(File(subDir, "Song 2.m4a").exists())

        val rewritten = CloudExporter.loadManifest(subDir)
        assertNotNull(rewritten)
        assertEquals("https://www.bilibili.com/video/BV_EDITED", rewritten?.sourceURL)
        assertEquals("BV_EDITED", rewritten?.remoteID)
        assertEquals(RootKind.CLOUD_BILIBILI.name, rewritten?.cloudKind)
    }

    @Test
    fun cloudDownloadManagerRunsBackgroundExportsFolderUpdatesAndSupportsCancelDismissClear() = runBlocking {
        val stateDir = tempFolder.newFolder("dl_state")
        val docsDir = tempFolder.newFolder("dl_docs")
        val stubReader = object : MetadataReading {
            override suspend fun read(uri: Uri, fileNameHint: String?): TrackMetadata = TrackMetadata(durationSec = 10.0)
            override suspend fun read(file: File): TrackMetadata = TrackMetadata(durationSec = 10.0)
        }
        val fakeHttp = object : CloudHttpClient {
            override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse {
                if (url.contains("drive.google.com/drive/folders/1DriveUpd")) {
                    val ivd = """[[["gd_new",["1DriveUpd"],"02 New Song.mp3","audio/mpeg"]]]"""
                    val esc = ivd.replace("\"", "\\x22").replace("[", "\\x5b").replace("]", "\\x5d")
                    val html = """<html><head><title>Drive Update - Google Drive</title></head><body><script>window['_DRIVE_ivd']='$esc';</script></body></html>"""
                    return CloudHttpResponse(200, url, emptyMap(), html.toByteArray())
                }
                return CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
            }

            override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse =
                CloudHttpResponse(404, url, emptyMap(), ByteArray(0))

            override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
        }
        val libraryStore = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = stubReader,
            artwork = ArtworkCache(File(stateDir, "art")),
            context = null,
            cloudHttpClient = fakeHttp,
            googleDriveClient = GoogleDriveClient(fakeHttp),
        )

        val slowGate = CompletableDeferred<Unit>()
        val slowStarted = CompletableDeferred<Unit>()
        var blockDownloads = false
        val resolver = FakeCloudStreamResolver { track, _, _ ->
            ResolvedStream(url = "https://cdn.example/${track.remoteTrackId}.m4a", mimeType = "audio/mp4", contentLength = 500L)
        }
        val downloader = object : CloudStreamDownloader {
            override suspend fun download(url: String, headers: Map<String, String>, output: OutputStream) {
                download(url, headers, 500L, output, null)
            }

            override suspend fun download(
                url: String,
                headers: Map<String, String>,
                contentLength: Long?,
                output: OutputStream,
                onBytesProgress: ((ByteTransferProgress) -> Unit)?,
            ) {
                if (blockDownloads) {
                    slowStarted.complete(Unit)
                    slowGate.await()
                }
                onBytesProgress?.invoke(ByteTransferProgress(500L, 500L, 4096.0))
                output.write(ByteArray(500) { 1 })
            }
        }
        val exporter = CloudExporter(context = null, cloudResolver = resolver, downloader = downloader)
        val manager = CloudDownloadManager(libraryStore = libraryStore, cloudExporter = exporter)

        suspend fun awaitTerminal(jobId: String): CloudDownloadJob = withTimeout(5_000L) {
            while (true) {
                val job = manager.job(jobId)
                if (job != null && !job.isActive) return@withTimeout job
                delay(15L)
            }
            error("unreachable")
        }

        // 1. Playlist export into APP_FOLDER adds tracked subfolder without duplicating roots
        val exportJobId = manager.startPlaylistExport(
            tracks = listOf(cloudTrack("1", "Track One", remoteId = "gd_old", source = CloudSource.GOOGLE_DRIVE)),
            root = cloudRoot().copy(
                kind = RootKind.CLOUD_GOOGLE_DRIVE,
                sourceUrl = "https://drive.google.com/drive/folders/1DriveUpd",
                remoteId = "1DriveUpd",
            ),
            parentDir = docsDir,
            folderName = "Drive Mix",
            addToLibrary = true,
        )
        val finishedExport = awaitTerminal(exportJobId)
        assertEquals(CloudDownloadStatus.COMPLETED, finishedExport.status)
        assertTrue(finishedExport.libraryUpdated)
        assertEquals(1, finishedExport.exportSummary?.savedCount)
        assertNotNull(libraryStore.cloudTracking(LibraryRoot.APP_FOLDER_ID, "Drive Mix"))

        // 2. Folder update on APP_FOLDER tracked subfolder fetches missing remote track and updates library
        val appRoot = libraryStore.state.value.roots.first { it.id == LibraryRoot.APP_FOLDER_ID }
        val updateJobId = manager.startFolderUpdate(root = appRoot, subfolderPath = "Drive Mix")
        val finishedUpdate = awaitTerminal(updateJobId)
        assertEquals(CloudDownloadStatus.COMPLETED, finishedUpdate.status)
        assertEquals(1, finishedUpdate.updateSummary?.addedCount)
        assertTrue(finishedUpdate.libraryUpdated)

        // 3. Single track export inside indexed docsDir completes and marks libraryUpdated
        val singleJobId = manager.startTrackExport(
            track = cloudTrack("s1", "Single Hit", remoteId = "s_1"),
            root = cloudRoot(),
            targetDir = docsDir,
        )
        val finishedSingle = awaitTerminal(singleJobId)
        assertEquals(CloudDownloadStatus.COMPLETED, finishedSingle.status)
        assertEquals("Single Hit.m4a", finishedSingle.savedFileName)
        assertTrue(finishedSingle.libraryUpdated)

        // 4. Folder update with missing treeUri fails with locationUnavailable = true
        val unavailJobId = manager.startFolderUpdate(
            root = LibraryRoot(
                id = "missing-picked",
                name = "Missing",
                kind = RootKind.PICKED,
                treeUri = null,
                sourceUrl = "https://drive.google.com/drive/folders/1DriveUpd",
            ),
        )
        val unavailJob = manager.job(unavailJobId)!!
        assertEquals(CloudDownloadStatus.FAILED, unavailJob.status)
        assertTrue(unavailJob.locationUnavailable)

        // 5. Cancel active download sets CANCELLED; dismiss / clearFinished clean up job list
        blockDownloads = true
        val cancelledJobId = manager.startTrackExport(
            track = cloudTrack("s2", "Slow Song", remoteId = "s_2"),
            root = cloudRoot(),
            targetDir = docsDir,
        )
        slowStarted.await()
        manager.cancel(cancelledJobId)
        val cancelledJob = awaitTerminal(cancelledJobId)
        assertEquals(CloudDownloadStatus.CANCELLED, cancelledJob.status)
        slowGate.complete(Unit)

        manager.dismiss(singleJobId)
        assertNull(manager.job(singleJobId))
        manager.clearFinished()
        assertTrue(manager.jobs.value.isEmpty())
    }

    @Test
    fun exportTrackForwardsQualityOverrideToStreamResolver() = runBlocking {
        val root = cloudRoot()
        val track = cloudTrack("q1", "Lossless Song", remoteId = "am_lossless")
        val targetDir = tempFolder.newFolder("single_quality")
        val resolver = FakeCloudStreamResolver { _, _, _ ->
            ResolvedStream(
                url = "https://cdn.example/song.m4a",
                mimeType = "audio/mp4",
                fileExtension = "m4a",
            )
        }
        val downloader = object : CloudStreamDownloader {
            override suspend fun download(url: String, headers: Map<String, String>, output: OutputStream) {
                output.write("alac-data".toByteArray())
            }
        }
        val exporter = CloudExporter(
            context = null,
            cloudResolver = resolver,
            downloader = downloader,
        )

        val savedName = exporter.exportTrack(
            track = track,
            root = root,
            targetDir = targetDir,
            qualityOverride = "alac",
        )

        assertEquals("Lossless Song.m4a", savedName)
        assertTrue(File(targetDir, savedName).isFile)
        assertEquals(listOf("alac"), resolver.requestedQualities)
    }

    @Test
    fun googleDriveExportRetainsEmbeddedArtistAlbumTitleAndCoverArt() = runBlocking {
        val samplePng = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
        )
        val sourceFlac = File(tempFolder.root, "seed.flac").apply {
            val out = ByteArrayOutputStream()
            out.write("fLaC".toByteArray(Charsets.US_ASCII))
            out.write(0x80)
            out.write(0x00)
            out.write(0x00)
            out.write(34)
            out.write(ByteArray(34))
            out.write(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x01, 0x02))
            writeBytes(out.toByteArray())
            assertTrue(
                AudioTagWriter.write(
                    this,
                    AudioTagPayload(
                        title = "Embedded Real Title",
                        artist = "Real Original Artist",
                        album = "Real Original Album",
                        genre = "Electronic",
                        trackNumber = 3,
                        artworkBytes = samplePng,
                    ),
                    "flac",
                ),
            )
        }
        val sourceMp3 = File(tempFolder.root, "seed.mp3").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00, 0x11, 0x22, 0x33, 0x44))
            assertTrue(
                AudioTagWriter.write(
                    this,
                    AudioTagPayload(
                        title = "MP3 Real Title",
                        artist = "Real MP3 Artist",
                        album = "Real MP3 Album",
                        genre = "Indie",
                        artworkBytes = samplePng,
                    ),
                    "mp3",
                ),
            )
        }

        val driveRoot = LibraryRoot(
            id = "cloud_gdrive_1",
            name = "My Drive Music",
            kind = RootKind.CLOUD_GOOGLE_DRIVE,
            sourceUrl = "https://drive.google.com/drive/folders/1DriveFolder",
            remoteId = "1DriveFolder",
        )
        val tracks = listOf(
            Track(
                id = "gd_flac",
                rootId = driveRoot.id,
                relativePath = "google_drive/file1.flac",
                title = "seed",
                artist = GoogleDriveClient.DEFAULT_ARTIST,
                album = "My Drive Music",
                albumArtist = GoogleDriveClient.DEFAULT_ARTIST,
                durationSec = 0.0,
                fileSize = 0L,
                modifiedEpochMs = 0L,
                dateAddedEpochMs = 1L,
                cloudSource = CloudSource.GOOGLE_DRIVE,
                remoteTrackId = "file1",
            ),
            Track(
                id = "gd_mp3",
                rootId = driveRoot.id,
                relativePath = "google_drive/file2.mp3",
                title = "seed2",
                artist = GoogleDriveClient.DEFAULT_ARTIST,
                album = "My Drive Music",
                albumArtist = GoogleDriveClient.DEFAULT_ARTIST,
                durationSec = 0.0,
                fileSize = 0L,
                modifiedEpochMs = 0L,
                dateAddedEpochMs = 2L,
                cloudSource = CloudSource.GOOGLE_DRIVE,
                remoteTrackId = "file2",
            ),
        )
        val resolver = FakeCloudStreamResolver { track, _, _ ->
            when (track.remoteTrackId) {
                "file1" -> ResolvedStream("https://drive.test/file1", mimeType = "audio/flac", fileExtension = "flac")
                else -> ResolvedStream("https://drive.test/file2", mimeType = "audio/mpeg", fileExtension = "mp3")
            }
        }
        val downloader = object : CloudStreamDownloader {
            override suspend fun download(url: String, headers: Map<String, String>, output: OutputStream) {
                val src = if (url.endsWith("file1")) sourceFlac else sourceMp3
                output.write(src.readBytes())
            }
        }
        val exporter = CloudExporter(
            context = null,
            cloudResolver = resolver,
            downloader = downloader,
        )
        val exportParent = tempFolder.newFolder("gdrive_export")
        val summary = exporter.exportPlaylist(tracks, driveRoot, exportParent, "My Drive Music")
        assertEquals(2, summary.savedCount)
        assertEquals(0, summary.failedCount)

        val exportedFlac = File(summary.targetDir!!, "seed.flac")
        val flacSummary = AudioTagWriter.readEmbeddedSummary(exportedFlac)
        assertEquals("Embedded Real Title", flacSummary.title)
        assertEquals("Real Original Artist", flacSummary.artist)
        assertEquals("Real Original Album", flacSummary.album)
        assertEquals("Electronic", flacSummary.genre)
        assertEquals(3, flacSummary.trackNumber)
        assertArrayEquals(samplePng, EmbeddedArtworkExtractor.extract(exportedFlac).artwork!!)

        val exportedMp3 = File(summary.targetDir!!, "seed2.mp3")
        val mp3Summary = AudioTagWriter.readEmbeddedSummary(exportedMp3)
        assertEquals("MP3 Real Title", mp3Summary.title)
        assertEquals("Real MP3 Artist", mp3Summary.artist)
        assertEquals("Real MP3 Album", mp3Summary.album)
        assertEquals("Indie", mp3Summary.genre)
        assertArrayEquals(samplePng, EmbeddedArtworkExtractor.extract(exportedMp3).artwork!!)
    }
}
