package com.musico.features

import android.net.Uri
import androidx.media3.common.MediaMetadata
import com.musico.audio.AudioPlaybackEngine
import com.musico.audio.PlayerController
import com.musico.cloud.BilibiliClient
import com.musico.cloud.CloudAudioCache
import com.musico.cloud.CloudExporter
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpResponse
import com.musico.cloud.CloudPlaylistResult
import com.musico.cloud.CloudQualityOption
import com.musico.cloud.CloudSource
import com.musico.cloud.CloudStreamDownloader
import com.musico.cloud.CloudStreamPurpose
import com.musico.cloud.CloudStreamResolver
import com.musico.cloud.CloudTrackMetadata
import com.musico.cloud.GoogleDriveClient
import com.musico.cloud.ResolvedStream
import com.musico.cloud.YouTubeClient
import com.musico.core.ArtworkCache
import com.musico.core.MetadataReading
import com.musico.core.Persistence
import com.musico.core.TrackMetadata
import com.musico.extensions.BilibiliSourceProvider
import com.musico.extensions.CloudProviderAvailability
import com.musico.extensions.CloudProviderIds
import com.musico.extensions.CloudProviderRegistry
import com.musico.extensions.CloudSourceProvider
import com.musico.extensions.CloudUrlMatch
import com.musico.extensions.ExtensionCapability
import com.musico.extensions.ExtensionManifest
import com.musico.extensions.ExtensionManifestValidator
import com.musico.extensions.ExtensionPermissions
import com.musico.extensions.ExtensionQualityOption
import com.musico.extensions.ExtensionState
import com.musico.extensions.ExtensionStore
import com.musico.extensions.GoogleDriveSourceProvider
import com.musico.extensions.YouTubeSourceProvider
import com.musico.model.EqSettings
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.Track
import com.musico.store.LibraryStore
import com.musico.store.PlayHistoryStore
import com.musico.store.QueueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class CloudQualityAndRecoveryV127Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    private class MockHttp(
        private val onGet: (String, Map<String, String>) -> CloudHttpResponse = { url, _ ->
            CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
        },
        private val onPost: (String, String, Map<String, String>) -> CloudHttpResponse = { url, _, _ ->
            CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
        },
    ) : CloudHttpClient {
        override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse =
            onGet(url, headers)

        override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse =
            onPost(url, jsonBody, headers)

        override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
    }

    private class FakePlayerEngine : AudioPlaybackEngine {
        override var onTrackEnded: (() -> Unit)? = null
        override var onPlaybackError: ((String) -> Unit)? = null
        override var onDurationChanged: ((Double) -> Unit)? = null
        override var onIsPlayingChanged: ((Boolean) -> Unit)? = null

        override var currentTimeSec: Double = 0.0
        override var durationSec: Double = 200.0
        val loadedStreamUrls = mutableListOf<String>()

        override fun load(uri: Uri, metadata: MediaMetadata?) = Unit
        override fun loadStream(url: String, headers: Map<String, String>, metadata: MediaMetadata?) {
            loadedStreamUrls.add(url)
        }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(sec: Double) {
            currentTimeSec = sec
        }
        override fun unload() = Unit
        override fun applyEq(settings: EqSettings) = Unit
    }

    private fun stubReader() = object : MetadataReading {
        override suspend fun read(uri: Uri, fileNameHint: String?): TrackMetadata = TrackMetadata()
        override suspend fun read(file: File): TrackMetadata = TrackMetadata()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun manifestQualitiesValidationAndBackwardCompatibleStateAndRootSerialization() {
        val baseManifest = ExtensionManifest(
            apiVersion = 1,
            id = "com.musico.test.qualities",
            name = "Quality Source",
            version = "1.0.0",
            capabilities = listOf(ExtensionCapability.PLAYLIST, ExtensionCapability.STREAM),
            urlPatterns = listOf("^https://q\\.test/"),
            permissions = ExtensionPermissions(hosts = listOf("q.test")),
            qualities = listOf(
                ExtensionQualityOption(id = "highest", label = "Lossless FLAC", badge = "FLAC", lossless = true),
                ExtensionQualityOption(id = "standard", label = "Standard 128k", badge = "128k"),
            ),
            defaultStreamQuality = "standard",
            defaultDownloadQuality = "highest",
        )
        val validated = ExtensionManifestValidator.validate(baseManifest)
        assertEquals(2, validated.qualities.size)
        assertEquals("standard", validated.effectiveDefaultStreamQuality)
        assertEquals("highest", validated.effectiveDefaultDownloadQuality)

        // Reject duplicate ids
        try {
            ExtensionManifestValidator.validate(
                baseManifest.copy(
                    qualities = listOf(
                        ExtensionQualityOption("dup", "One"),
                        ExtensionQualityOption("dup", "Two"),
                    ),
                ),
            )
            fail("Expected duplicate quality id rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        // Reject blank id
        try {
            ExtensionManifestValidator.validate(
                baseManifest.copy(
                    qualities = listOf(ExtensionQualityOption("  ", "Blank")),
                    defaultStreamQuality = null,
                    defaultDownloadQuality = null,
                ),
            )
            fail("Expected blank quality id rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        // Reject > 16 qualities
        val tooMany = (1..17).map { ExtensionQualityOption("q$it", "Q$it") }
        try {
            ExtensionManifestValidator.validate(
                baseManifest.copy(qualities = tooMany, defaultStreamQuality = null, defaultDownloadQuality = null),
            )
            fail("Expected >16 quality rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        // Reject unknown defaultStreamQuality
        try {
            ExtensionManifestValidator.validate(baseManifest.copy(defaultStreamQuality = "missing"))
            fail("Expected unknown defaultStreamQuality rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        // Reject unknown defaultDownloadQuality
        try {
            ExtensionManifestValidator.validate(baseManifest.copy(defaultDownloadQuality = "missing"))
            fail("Expected unknown defaultDownloadQuality rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }

        // Backward-compatible ExtensionState and LibraryRoot deserialization
        val legacyExtState = Persistence.json.decodeFromString<ExtensionState>("""{"id":"com.test","enabled":true}""")
        assertNull(legacyExtState.streamQualityId)
        assertNull(legacyExtState.downloadQualityId)

        val legacyRoot = Persistence.json.decodeFromString<LibraryRoot>(
            """{"id":"r1","name":"Cloud","kind":"CLOUD_YOUTUBE","sourceUrl":"https://youtube.com/playlist?list=PL1"}"""
        )
        assertNull(legacyRoot.streamQualityId)
        assertNull(legacyRoot.downloadQualityId)
    }

    @Test
    fun extensionRuntimeReceivesQualityAndPurposeAndReturnsQualityMetadata() = runBlocking {
        val stateDir = tempFolder.newFolder("ext_quality_state")
        val registry = CloudProviderRegistry()
        val http = MockHttp()
        val extStore = ExtensionStore(
            stateDir = stateDir,
            httpClient = http,
            providerRegistry = registry,
            appVersion = "1.2.7-test",
        )

        val script = """
            'use strict';
            var manifest = {
              apiVersion: 1,
              id: 'com.musico.test.qext',
              name: 'QExt',
              version: '1.0.0',
              capabilities: ['playlist', 'stream'],
              urlPatterns: ['^https://q\\.test/'],
              permissions: { hosts: ['cdn.q.test'] },
              qualities: [
                { id: 'lossless', label: 'FLAC', badge: 'FLAC', lossless: true },
                { id: 'standard', label: '192k AAC', badge: '192k' }
              ],
              defaultStreamQuality: 'standard',
              defaultDownloadQuality: 'lossless'
            };
            function canHandle(input) {
              return { matched: true, normalizedUrl: input.extractedUrl, remoteId: 'p1' };
            }
            async function fetchCollection(ctx, req) {
              return { remoteId: 'p1', title: 'QMix', tracks: [{ remoteTrackId: 't1', title: 'Track 1' }] };
            }
            async function resolveStream(ctx, req) {
              var q = req.quality || 'standard';
              var p = req.purpose || 'stream';
              if (q === 'lossless') {
                return {
                  url: 'https://cdn.q.test/' + req.remoteTrackId + '/' + p + '/flac.flac',
                  fileExtension: 'flac',
                  mimeType: 'audio/flac',
                  actualQuality: 'lossless',
                  qualityLabel: 'FLAC',
                  bitrateKbps: 1411,
                  expiresAtMs: Date.now() + 60000
                };
              }
              return {
                url: 'https://cdn.q.test/' + req.remoteTrackId + '/' + p + '/192.m4a',
                fileExtension: 'm4a',
                mimeType: 'audio/mp4',
                actualQuality: 'standard',
                qualityLabel: '192k',
                bitrateKbps: 192,
                expiresAtMs: Date.now() + 60000
              };
            }
            module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection, resolveStream: resolveStream };
        """.trimIndent()

        val staged = extStore.stageFromBytes(script.toByteArray(Charsets.UTF_8), "qext.musico.js").getOrThrow()
        extStore.installStaged(staged, enableImmediately = true)

        val resolver = CloudStreamResolver(registry)
        val root = LibraryRoot(
            id = "r_ext",
            name = "QMix",
            kind = RootKind.CLOUD_EXTENSION,
            sourceUrl = "https://q.test/p1",
            providerId = "ext.com.musico.test.qext",
        )
        val track = Track(
            id = "tr_1",
            rootId = root.id,
            relativePath = "ext.com.musico.test.qext/t1",
            title = "Track 1",
            artist = "QExt",
            album = "QMix",
            albumArtist = "QExt",
            durationSec = 120.0,
            fileSize = 0L,
            modifiedEpochMs = 0L,
            dateAddedEpochMs = 1L,
            cloudSource = "ext.com.musico.test.qext",
            remoteTrackId = "t1",
        )

        val streamRes = resolver.resolve(track, root, forceRefresh = false, purpose = CloudStreamPurpose.STREAM)
        assertEquals("https://cdn.q.test/t1/stream/192.m4a", streamRes.url)
        assertEquals("standard", streamRes.actualQuality)
        assertEquals("192k", streamRes.qualityLabel)
        assertEquals(192, streamRes.bitrateKbps)

        val downloadRes = resolver.resolve(track, root, forceRefresh = false, purpose = CloudStreamPurpose.DOWNLOAD)
        assertEquals("https://cdn.q.test/t1/download/flac.flac", downloadRes.url)
        assertEquals("lossless", downloadRes.actualQuality)
        assertEquals("FLAC", downloadRes.qualityLabel)
        assertEquals(1411, downloadRes.bitrateKbps)

        // Extension Settings persistence updates stream/download defaults
        extStore.updateQualityPreferences("com.musico.test.qext", streamQualityId = "lossless", downloadQualityId = "standard")
        val streamAfterPref = resolver.resolve(track, root, forceRefresh = true, purpose = CloudStreamPurpose.STREAM)
        assertEquals("lossless", streamAfterPref.actualQuality)
        val downloadAfterPref = resolver.resolve(track, root, forceRefresh = true, purpose = CloudStreamPurpose.DOWNLOAD)
        assertEquals("standard", downloadAfterPref.actualQuality)
    }

    @Test
    fun builtinBilibiliYouTubeAndGoogleDriveQualityResolution() = runBlocking {
        val biliHttp = MockHttp(
            onGet = { url, _ ->
                when {
                    url.contains("/x/frontend/finger/spi") -> CloudHttpResponse(
                        200, url, emptyMap(), """{"code":0,"data":{"b_3":"BUVID"}}""".toByteArray(),
                    )
                    url.contains("/x/web-interface/nav") -> CloudHttpResponse(
                        200, url, emptyMap(),
                        """{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}""".toByteArray(),
                    )
                    url.contains("/x/player/") -> CloudHttpResponse(
                        200, url, emptyMap(),
                        """
                        {
                          "code": 0,
                          "data": {
                            "dash": {
                              "audio": [
                                { "id": 30216, "bandwidth": 64000, "baseUrl": "https://cdn.bilivideo.com/64k.m4s" },
                                { "id": 30232, "bandwidth": 132000, "baseUrl": "https://cdn.bilivideo.com/132k.m4s" },
                                { "id": 30280, "bandwidth": 192000, "baseUrl": "https://cdn.bilivideo.com/192k.m4s" }
                              ],
                              "flac": {
                                "audio": { "id": 30251, "bandwidth": 1411000, "baseUrl": "https://cdn.bilivideo.com/flac.m4s" }
                              }
                            }
                          }
                        }
                        """.trimIndent().toByteArray(),
                    )
                    else -> CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
                }
            },
        )
        val biliClient = BilibiliClient(biliHttp)
        val biliProvider = BilibiliSourceProvider(biliClient)
        assertEquals("standard", biliProvider.defaultStreamQualityId)
        assertEquals("highest", biliProvider.defaultDownloadQualityId)
        assertEquals(listOf("highest", "high", "standard", "low"), biliProvider.availableQualities.map { it.id })

        val biliHighest = biliClient.resolveStream("BV1111111111#999", quality = "highest")
        assertEquals("https://cdn.bilivideo.com/flac.m4s", biliHighest.url)
        assertEquals("highest", biliHighest.actualQuality)
        assertEquals("FLAC", biliHighest.qualityLabel)
        assertEquals(1411, biliHighest.bitrateKbps)

        val biliHigh = biliClient.resolveStream("BV1111111111#999", quality = "high")
        assertEquals("https://cdn.bilivideo.com/192k.m4s", biliHigh.url)
        assertEquals("192k", biliHigh.qualityLabel)

        val biliStd = biliClient.resolveStream("BV1111111111#999", quality = "standard")
        assertEquals("https://cdn.bilivideo.com/132k.m4s", biliStd.url)
        assertEquals("132k", biliStd.qualityLabel)

        val biliLow = biliClient.resolveStream("BV1111111111#999", quality = "low")
        assertEquals("https://cdn.bilivideo.com/64k.m4s", biliLow.url)
        assertEquals("64k", biliLow.qualityLabel)

        // Built-in YouTube
        val ytHttp = MockHttp(
            onPost = { url, _, _ ->
                if (!url.contains("/youtubei/v1/player")) {
                    CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
                } else {
                    CloudHttpResponse(
                        200, url, emptyMap(),
                        """
                        {
                          "streamingData": {
                            "expiresInSeconds": "3600",
                            "adaptiveFormats": [
                              { "itag": 139, "url": "https://cdn.yt.test/48k.m4a", "mimeType": "audio/mp4", "bitrate": 48000 },
                              { "itag": 140, "url": "https://cdn.yt.test/128k.m4a", "mimeType": "audio/mp4", "bitrate": 128000 },
                              { "itag": 251, "url": "https://cdn.yt.test/160k.webm", "mimeType": "audio/webm", "bitrate": 160000 },
                              { "itag": 141, "url": "https://cdn.yt.test/256k.m4a", "mimeType": "audio/mp4", "bitrate": 256000 }
                            ]
                          }
                        }
                        """.trimIndent().toByteArray(),
                    )
                }
            },
        )
        val ytClient = YouTubeClient(ytHttp)
        val ytProvider = YouTubeSourceProvider(ytClient)
        assertEquals("standard", ytProvider.defaultStreamQualityId)
        assertEquals("highest", ytProvider.defaultDownloadQualityId)
        assertEquals(listOf("highest", "high", "standard", "low"), ytProvider.availableQualities.map { it.id })

        val ytHighest = ytClient.resolveStream("dQw4w9WgXcQ", quality = "highest")
        assertEquals("https://cdn.yt.test/256k.m4a", ytHighest.url)
        assertEquals("highest", ytHighest.actualQuality)
        assertEquals("256k", ytHighest.qualityLabel)
        assertEquals(256, ytHighest.bitrateKbps)

        val ytHigh = ytClient.resolveStream("dQw4w9WgXcQ", quality = "high")
        assertEquals("https://cdn.yt.test/160k.webm", ytHigh.url)
        assertEquals("160k", ytHigh.qualityLabel)

        val ytStd = ytClient.resolveStream("dQw4w9WgXcQ", quality = "standard")
        assertEquals("https://cdn.yt.test/128k.m4a", ytStd.url)
        assertEquals("128k", ytStd.qualityLabel)

        val ytLow = ytClient.resolveStream("dQw4w9WgXcQ", quality = "low")
        assertEquals("https://cdn.yt.test/48k.m4a", ytLow.url)
        assertEquals("48k", ytLow.qualityLabel)

        // Google Drive declares empty qualities
        val driveProvider = GoogleDriveSourceProvider(GoogleDriveClient(MockHttp()))
        assertTrue(driveProvider.availableQualities.isEmpty())
        assertNull(driveProvider.defaultStreamQualityId)
        assertNull(driveProvider.defaultDownloadQualityId)
    }

    @Test
    fun resolverCacheKeyPartitioningAudioCacheEvictionAndExporterDownloadQuality() = runBlocking {
        val resolveCalls = CopyOnWriteArrayList<Pair<String?, CloudStreamPurpose>>()
        val registry = CloudProviderRegistry()
        val provider = object : CloudSourceProvider {
            override val providerId: String = "ext.com.cache.test"
            override val rootKind: RootKind = RootKind.CLOUD_EXTENSION
            override val cloudSourceSlug: String = providerId
            override val displayName: String = "CacheTest"
            override val badgeLabel: String = "Cache"
            override val isBuiltin: Boolean = false
            override val supportsTree: Boolean = false
            override val supportsCookie: Boolean = false
            override val usesFlatCloudListing: Boolean = true
            override val availableQualities: List<CloudQualityOption> = listOf(
                CloudQualityOption("highest", "FLAC", "FLAC", lossless = true),
                CloudQualityOption("standard", "128k", "128k"),
            )
            override val defaultStreamQualityId: String = "standard"
            override val defaultDownloadQualityId: String = "highest"
            override fun canHandle(rawInput: String): CloudUrlMatch? = null
            override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult =
                CloudPlaylistResult(providerId, "r1", "Mix", emptyList())

            override suspend fun resolveStream(
                remoteTrackId: String,
                relativePath: String?,
                cookie: String?,
                forceRefresh: Boolean,
                quality: String?,
                purpose: CloudStreamPurpose,
            ): ResolvedStream {
                resolveCalls.add(quality to purpose)
                val q = quality ?: "standard"
                val ext = if (q == "highest") "flac" else "m4a"
                return ResolvedStream(
                    url = "https://cdn.example.com/$remoteTrackId/$q.$ext",
                    mimeType = if (q == "highest") "audio/flac" else "audio/mp4",
                    fileExtension = ext,
                    expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
                    actualQuality = q,
                    qualityLabel = if (q == "highest") "FLAC" else "128k",
                )
            }
        }
        registry.registerExtensionProvider(provider)

        val cacheDir = tempFolder.newFolder("audio_cache")
        val audioCache = CloudAudioCache(cacheDir, maxBytes = 100L)
        val resolver = CloudStreamResolver(registry, audioCache)

        val root = LibraryRoot(
            id = "root_q",
            name = "Mix",
            kind = RootKind.CLOUD_EXTENSION,
            sourceUrl = "https://cache.test/p/1",
            providerId = provider.providerId,
            streamQualityId = "standard",
            downloadQualityId = "highest",
        )
        val track = Track(
            id = "trk_cache_1",
            rootId = root.id,
            relativePath = "${provider.providerId}/rt1",
            title = "Song",
            artist = "Artist",
            album = "Mix",
            albumArtist = "Artist",
            durationSec = 100.0,
            fileSize = 0L,
            modifiedEpochMs = 0L,
            dateAddedEpochMs = 1L,
            cloudSource = provider.providerId,
            remoteTrackId = "rt1",
        )

        // Stream resolves standard and caches under standard key
        val s1 = resolver.resolve(track, root, forceRefresh = false, purpose = CloudStreamPurpose.STREAM)
        val s2 = resolver.resolve(track, root, forceRefresh = false, purpose = CloudStreamPurpose.STREAM)
        assertEquals("standard", s1.actualQuality)
        assertEquals(s1.url, s2.url)
        assertEquals(1, resolveCalls.size)

        // Explicit different quality key misses standard cache and hits provider with "highest"
        val sHigh = resolver.resolve(
            track,
            root,
            forceRefresh = false,
            purpose = CloudStreamPurpose.STREAM,
            requestedQualityId = "highest",
        )
        assertEquals("highest", sHigh.actualQuality)
        assertEquals(2, resolveCalls.size)

        // CloudExporter always requests DOWNLOAD purpose using root.downloadQualityId ("highest")
        val exportDir = tempFolder.newFolder("export_out")
        val exporter = CloudExporter(
            context = null,
            cloudResolver = resolver,
            downloader = CloudStreamDownloader { url, _, out ->
                out.write("downloaded:$url".toByteArray())
            },
            providerRegistry = registry,
        )
        val savedName = exporter.exportTrack(track, root, exportDir)
        assertEquals("Song.flac", savedName)
        assertEquals("highest" to CloudStreamPurpose.DOWNLOAD, resolveCalls.last())

        // CloudAudioCache quality partitioning + stale variant eviction + LRU budget
        val stdFile = audioCache.storeBytes("trk_cache_1", "standard", "m4a", ByteArray(40) { 1 })
        assertTrue(stdFile.exists())
        assertEquals("trk_cache_1__standard.m4a", stdFile.name)
        assertNotNull(audioCache.cachedFile("trk_cache_1", "standard"))

        val highFile = audioCache.storeBytes("trk_cache_1", "highest", "flac", ByteArray(40) { 2 })
        assertTrue(highFile.exists())
        assertEquals("trk_cache_1__highest.flac", highFile.name)
        // Stale standard variant for the same trackId was evicted cleanly
        assertFalse(stdFile.exists())
        assertNull(audioCache.cachedFile("trk_cache_1", "standard"))
        assertNotNull(audioCache.cachedFile("trk_cache_1", "highest"))

        // Exceeding 100 byte LRU budget evicts older tracks
        audioCache.storeBytes("trk_cache_2", "standard", "m4a", ByteArray(70) { 3 })
        assertTrue(audioCache.totalSizeBytes() <= 100L)
        assertNull(audioCache.cachedFile("trk_cache_1", "highest"))
        assertNotNull(audioCache.cachedFile("trk_cache_2", "standard"))
    }

    @Test
    fun playerControllerCurrentTrackOnlyQualitySwitchPreservesPositionAndDoesNotMutateFolderDefaults() = runBlocking {
        val stateDir = tempFolder.newFolder("player_q_state")
        val docsDir = tempFolder.newFolder("player_q_docs")
        val registry = CloudProviderRegistry()
        val provider = object : CloudSourceProvider {
            override val providerId: String = "ext.com.player.q"
            override val rootKind: RootKind = RootKind.CLOUD_EXTENSION
            override val cloudSourceSlug: String = providerId
            override val displayName: String = "PlayerQ"
            override val badgeLabel: String = "PQ"
            override val isBuiltin: Boolean = false
            override val supportsTree: Boolean = false
            override val supportsCookie: Boolean = false
            override val usesFlatCloudListing: Boolean = true
            override val availableQualities: List<CloudQualityOption> = listOf(
                CloudQualityOption("highest", "FLAC", "FLAC", lossless = true),
                CloudQualityOption("standard", "128k", "128k"),
            )
            override val defaultStreamQualityId: String = "standard"
            override val defaultDownloadQualityId: String = "highest"
            override fun canHandle(rawInput: String): CloudUrlMatch? = CloudUrlMatch(rawInput, "mix1")
            override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult =
                CloudPlaylistResult(
                    source = providerId,
                    remoteId = "mix1",
                    title = "PQ Mix",
                    tracks = listOf(
                        CloudTrackMetadata("rt1", "Song 1", "Artist", "PQ Mix", 200.0),
                        CloudTrackMetadata("rt2", "Song 2", "Artist", "PQ Mix", 200.0),
                    ),
                )

            override suspend fun resolveStream(
                remoteTrackId: String,
                relativePath: String?,
                cookie: String?,
                forceRefresh: Boolean,
                quality: String?,
                purpose: CloudStreamPurpose,
            ): ResolvedStream {
                val q = quality ?: "standard"
                return ResolvedStream(
                    url = "https://cdn.pq.test/$remoteTrackId/$q.m4a",
                    mimeType = "audio/mp4",
                    expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
                    actualQuality = q,
                    qualityLabel = if (q == "highest") "FLAC" else "128k",
                )
            }
        }
        registry.registerExtensionProvider(provider)

        val libraryStore = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = stubReader(),
            artwork = ArtworkCache(File(stateDir, "art")),
            providerRegistry = registry,
        )
        assertTrue(libraryStore.addCloudRoot("https://pq.test/mix1").isSuccess)
        val rootBefore = libraryStore.state.value.roots.first { it.kind.isCloud }
        val tracks = libraryStore.tracksUnder(rootBefore.id, "")
        assertEquals(2, tracks.size)

        val queueStore = QueueStore(stateDir)
        val historyStore = PlayHistoryStore(stateDir)
        val engine = FakePlayerEngine()
        val resolver = CloudStreamResolver(registry)
        val controller = PlayerController(
            context = null,
            library = libraryStore,
            queues = queueStore,
            history = historyStore,
            engine = engine,
            settingsFile = File(stateDir, "settings.json"),
            cloudResolver = resolver,
        )

        try {
            suspend fun awaitCloudLoaded() {
                val deadline = System.currentTimeMillis() + 5_000L
                while (controller.state.value.isLoadingCloud && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(10)
                }
                assertFalse("Timed out waiting for cloud load", controller.state.value.isLoadingCloud)
            }

            controller.play(tracks.map { it.id }, startAt = 0)
            awaitCloudLoaded()
            assertEquals("standard", controller.state.value.currentStreamQualityId)
            assertEquals("128k", controller.state.value.currentStreamQualityLabel)
            assertEquals("https://cdn.pq.test/rt1/standard.m4a", engine.loadedStreamUrls.last())

            // Simulate playback at 42.5s and switch quality for current track only
            controller.seek(42.5)
            controller.switchCurrentTrackQuality("highest")
            awaitCloudLoaded()

            assertEquals("highest", controller.state.value.currentStreamQualityId)
            assertEquals("FLAC", controller.state.value.currentStreamQualityLabel)
            assertEquals(42.5, engine.currentTimeSec, 0.01)
            assertEquals(42.5, controller.state.value.currentTimeSec, 0.01)
            assertEquals("https://cdn.pq.test/rt1/highest.m4a", engine.loadedStreamUrls.last())

            // Root default streamQualityId was NOT mutated
            val rootAfter = libraryStore.state.value.roots.first { it.id == rootBefore.id }
            assertNull(rootAfter.streamQualityId)

            // Advancing to next track uses default "standard", NOT track 1's override
            controller.next()
            awaitCloudLoaded()
            assertEquals("standard", controller.state.value.currentStreamQualityId)
            assertEquals("128k", controller.state.value.currentStreamQualityLabel)
            assertEquals("https://cdn.pq.test/rt2/standard.m4a", engine.loadedStreamUrls.last())
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun libraryRootRebindingPreservesRootIdReconcilesTracksAndSupportsOptionARecovery() = runBlocking {
        val stateDir = tempFolder.newFolder("rebind_state")
        val docsDir = tempFolder.newFolder("rebind_docs")
        val registry = CloudProviderRegistry()

        var oldExtInstalled = true
        registry.extensionStatusLookup = { pid ->
            if (pid == "ext.com.old.bili" && oldExtInstalled) {
                CloudProviderAvailability.AVAILABLE
            } else {
                CloudProviderAvailability.MISSING
            }
        }

        val oldProvider = object : CloudSourceProvider {
            override val providerId: String = "ext.com.old.bili"
            override val rootKind: RootKind = RootKind.CLOUD_EXTENSION
            override val cloudSourceSlug: String = providerId
            override val displayName: String = "Old Bili Ext"
            override val badgeLabel: String = "OldBili"
            override val isBuiltin: Boolean = false
            override val supportsTree: Boolean = false
            override val supportsCookie: Boolean = true
            override val usesFlatCloudListing: Boolean = true
            override fun matchesUrlPatternFast(rawInput: String): Boolean = rawInput.contains("bilibili.com")
            override fun canHandle(rawInput: String): CloudUrlMatch? =
                if (rawInput.contains("bilibili.com")) CloudUrlMatch(rawInput, remoteId = "fav:42") else null

            override suspend fun fetchCollection(sourceUrl: String, cookie: String?): CloudPlaylistResult =
                CloudPlaylistResult(
                    source = providerId,
                    remoteId = "fav:42",
                    title = "My Bili Fav",
                    tracks = listOf(
                        CloudTrackMetadata("BV1xx411c7mD", "Track Alpha", "Creator", "My Bili Fav", 120.0),
                    ),
                )

            override suspend fun resolveStream(
                remoteTrackId: String,
                relativePath: String?,
                cookie: String?,
                forceRefresh: Boolean,
                quality: String?,
                purpose: CloudStreamPurpose,
            ): ResolvedStream = ResolvedStream("https://old.example.com/$remoteTrackId.m4a")
        }

        val biliHttp = MockHttp(
            onGet = { url, _ ->
                when {
                    url.contains("/x/v3/fav/resource/list") -> CloudHttpResponse(
                        200, url, emptyMap(),
                        """
                        {
                          "code": 0,
                          "data": {
                            "info": { "title": "My Bili Fav" },
                            "has_more": false,
                            "medias": [
                              {
                                "attr": 0,
                                "bvid": "BV1xx411c7mD",
                                "title": "Track Alpha",
                                "duration": 120,
                                "upper": { "name": "Creator" }
                              }
                            ]
                          }
                        }
                        """.trimIndent().toByteArray(),
                    )
                    else -> CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
                }
            },
        )
        val customRegistry = CloudProviderRegistry(bilibiliClient = BilibiliClient(biliHttp))
        customRegistry.extensionStatusLookup = registry.extensionStatusLookup
        customRegistry.registerExtensionProvider(oldProvider)

        val store = LibraryStore(
            directory = stateDir,
            appFolderDir = docsDir,
            reader = stubReader(),
            artwork = ArtworkCache(File(stateDir, "art")),
            cloudHttpClient = biliHttp,
            providerRegistry = customRegistry,
        )
        val queueStore = QueueStore(stateDir)
        store.onTracksRemapped = { idMap -> queueStore.remapTracks(idMap) }

        assertTrue(
            store.addCloudRoot(
                sourceUrl = "https://space.bilibili.com/1/favlist?fid=42",
                providerIdHint = "ext.com.old.bili",
            ).isSuccess,
        )
        val originalRoot = store.state.value.roots.first { it.kind == RootKind.CLOUD_EXTENSION }
        val originalTrack = store.state.value.tracks.first { it.rootId == originalRoot.id }
        assertEquals("ext.com.old.bili", originalTrack.cloudSource)

        val playlistId = queueStore.addQueue("Bili Queue")
        queueStore.update(playlistId) { it.replace(listOf(originalTrack.id), startAt = 0) }
        val userPlaylistId = store.createPlaylist("Keep Playlist")
        store.addToPlaylist(listOf(originalTrack.id), userPlaylistId)
        store.toggleFavourite(originalTrack.id)

        // Option A: uninstall extension -> root stays visibly unavailable, compatible built-in Bilibili matches URL
        oldExtInstalled = false
        customRegistry.unregisterExtensionProvider("ext.com.old.bili")
        store.refreshAvailability()

        assertFalse(store.isAvailable(originalRoot))
        assertEquals(CloudProviderAvailability.MISSING, store.cloudProviderAvailability(originalRoot))
        val compatible = store.compatibleProvidersForRoot(originalRoot)
        assertTrue(compatible.any { it.providerId == CloudProviderIds.BILIBILI })
        assertEquals(CloudProviderIds.BILIBILI, store.bestCompatibleProviderForRoot(originalRoot)?.providerId)

        // Explicit recovery: re-bind to built-in Bilibili with quality preferences
        val rebindRes = store.updateCloudTrackingAddress(
            rootId = originalRoot.id,
            newSourceUrl = "https://space.bilibili.com/1/favlist?fid=42",
            providerIdHint = CloudProviderIds.BILIBILI,
            streamQualityId = "high",
            downloadQualityId = "highest",
        )
        assertTrue(rebindRes.isSuccess)

        val reboundRoot = store.state.value.roots.first { it.id == originalRoot.id }
        assertEquals(originalRoot.id, reboundRoot.id)
        assertEquals(RootKind.CLOUD_BILIBILI, reboundRoot.kind)
        assertEquals(CloudProviderIds.BILIBILI, reboundRoot.resolvedProviderId)
        assertEquals("high", reboundRoot.streamQualityId)
        assertEquals("highest", reboundRoot.downloadQualityId)
        assertTrue(store.isAvailable(reboundRoot))

        // Reconciled tracks have valid new cloudSource and preserved playlist/favourite/queue membership
        val reboundTracks = store.state.value.tracks.filter { it.rootId == originalRoot.id }
        assertEquals(1, reboundTracks.size)
        val reboundTrack = reboundTracks.single()
        assertEquals(CloudSource.BILIBILI, reboundTrack.cloudSource)
        assertEquals("BV1xx411c7mD", reboundTrack.remoteTrackId)
        assertNotEquals(originalTrack.id, reboundTrack.id)
        assertEquals(listOf(reboundTrack.id), store.state.value.playlists.first { it.id == userPlaylistId }.trackIds)
        assertTrue(store.isFavourite(reboundTrack.id))
        assertEquals(listOf(reboundTrack.id), queueStore.state.value.queues.first { it.id == playlistId }.trackIds)

        // Also verify Cloud-Tracked Local Folder re-binding updates linked provider, qualities, and .musico-cloud.json
        val localDir = tempFolder.newFolder("tracked_local")
        val addLocal = store.addLocalFolder(
            dir = localDir,
            cloudSourceRoot = LibraryRoot(
                id = "src_old",
                name = "Old",
                kind = RootKind.CLOUD_EXTENSION,
                sourceUrl = "https://space.bilibili.com/1/favlist?fid=42",
                providerId = "ext.com.old.bili",
            ),
        )
        assertTrue(addLocal.isSuccess)
        val localRoot = store.state.value.roots.first { it.treeUri?.contains("tracked_local") == true }
        val localRebind = store.updateCloudTrackingAddress(
            rootId = localRoot.id,
            newSourceUrl = "https://space.bilibili.com/1/favlist?fid=42",
            cookie = "SESSDATA=abc",
            providerIdHint = CloudProviderIds.BILIBILI,
            streamQualityId = "standard",
            downloadQualityId = "highest",
        )
        assertTrue(localRebind.isSuccess)

        val updatedLocalRoot = store.state.value.roots.first { it.id == localRoot.id }
        assertEquals(RootKind.CLOUD_BILIBILI, updatedLocalRoot.linkedCloudKind)
        assertEquals(CloudProviderIds.BILIBILI, updatedLocalRoot.linkedProviderId)
        assertEquals("standard", updatedLocalRoot.streamQualityId)
        assertEquals("highest", updatedLocalRoot.downloadQualityId)
        assertEquals("SESSDATA=abc", updatedLocalRoot.cookie)

        val manifest = CloudExporter.loadManifest(localDir)
        assertNotNull(manifest)
        assertEquals(RootKind.CLOUD_BILIBILI.name, manifest!!.cloudKind)
        assertEquals(CloudProviderIds.BILIBILI, manifest.providerId)
        assertEquals("standard", manifest.streamQualityId)
        assertEquals("highest", manifest.downloadQualityId)
    }
}
