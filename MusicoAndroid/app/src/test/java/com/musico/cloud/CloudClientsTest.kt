package com.musico.cloud

import com.musico.core.Persistence
import com.musico.extensions.CloudProviderRegistry
import com.musico.extensions.CloudSourceProvider
import com.musico.extensions.CloudUrlMatch
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

internal class FakeCloudHttpClient : CloudHttpClient {
    val getHandlers = mutableListOf<(String, Map<String, String>) -> CloudHttpResponse?>()
    val postHandlers = mutableListOf<(String, String, Map<String, String>) -> CloudHttpResponse?>()
    val probeHandlers = mutableListOf<(String, Map<String, String>) -> Int?>()
    val recordedGets = mutableListOf<Pair<String, Map<String, String>>>()
    val recordedPosts = mutableListOf<Triple<String, String, Map<String, String>>>()
    val recordedProbes = mutableListOf<Pair<String, Map<String, String>>>()

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): CloudHttpResponse {
        recordedGets.add(url to headers)
        for (handler in getHandlers) {
            handler(url, headers)?.let { return it }
        }
        return CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
    }

    override suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
    ): CloudHttpResponse {
        recordedPosts.add(Triple(url, jsonBody, headers))
        for (handler in postHandlers) {
            handler(url, jsonBody, headers)?.let { return it }
        }
        return CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
    }

    override suspend fun probeRange(url: String, headers: Map<String, String>): Int {
        recordedProbes.add(url to headers)
        for (handler in probeHandlers) {
            handler(url, headers)?.let { return it }
        }
        return 206
    }

    fun jsonResponse(body: String, statusCode: Int = 200, finalUrl: String = ""): CloudHttpResponse =
        CloudHttpResponse(
            statusCode = statusCode,
            finalUrl = finalUrl,
            headers = emptyMap(),
            bodyBytes = body.toByteArray(Charsets.UTF_8),
        )
}

class CloudClientsTest {

    @Test
    fun cloudStreamResolverCachesInvalidatesAndRequiresExtensionForLegacySources() = runBlocking {
        var resolveCalls = 0
        val registry = CloudProviderRegistry()
        val extProvider = object : CloudSourceProvider {
            override val providerId: String = "ext.youtube-search"
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
                CloudPlaylistResult(providerId, "PL1", "Mix", emptyList())

            override suspend fun resolveStream(
                remoteTrackId: String,
                relativePath: String?,
                cookie: String?,
                forceRefresh: Boolean,
                quality: String?,
                purpose: CloudStreamPurpose,
            ): ResolvedStream {
                resolveCalls += 1
                return ResolvedStream(
                    url = "https://cdn.example.com/stream-$resolveCalls",
                    mimeType = "audio/mp4",
                    expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
                )
            }
        }
        val track = Track(
            id = "t1",
            rootId = "r1",
            relativePath = "cloud_youtube/abc12345678",
            title = "Song",
            artist = "Artist",
            album = "Album",
            albumArtist = "Artist",
            durationSec = 180.0,
            fileSize = 0L,
            modifiedEpochMs = 0L,
            dateAddedEpochMs = 1L,
            cloudSource = CloudSource.YOUTUBE,
            remoteTrackId = "abc12345678",
        )

        val resolver = CloudStreamResolver(registry)
        try {
            resolver.resolve(track, root = null)
            fail("Expected EXTENSION_UNAVAILABLE without plugin")
        } catch (err: CloudSourceException) {
            assertEquals(CloudErrorCode.EXTENSION_UNAVAILABLE, err.code)
        }

        registry.registerExtensionProvider(extProvider)
        val first = resolver.resolve(track, root = null)
        val second = resolver.resolve(track, root = null)
        assertEquals(first.url, second.url)
        assertEquals(1, resolveCalls)

        resolver.invalidate(track)
        val third = resolver.resolve(track, root = null)
        assertEquals("https://cdn.example.com/stream-2", third.url)
        assertEquals(2, resolveCalls)
    }

    @Test
    fun backwardsCompatibleSerializationForRootsAndTracks() {
        val legacyRootJson = """{"id":"r1","name":"Local","kind":"PICKED","treeUri":"file:///music"}"""
        val decodedLegacyRoot = Persistence.json.decodeFromString<LibraryRoot>(legacyRootJson)
        assertEquals(RootKind.PICKED, decodedLegacyRoot.kind)
        assertNull(decodedLegacyRoot.sourceUrl)
        assertNull(decodedLegacyRoot.remoteId)
        assertFalse(decodedLegacyRoot.kind.isCloud)

        val legacyTrackJson = """
            {"id":"t1","rootId":"r1","relativePath":"a.mp3","title":"A","artist":"B","album":"C","albumArtist":"B","durationSec":10.0,"fileSize":100,"modifiedEpochMs":5,"dateAddedEpochMs":9}
        """.trimIndent()
        val decodedLegacyTrack = Persistence.json.decodeFromString<Track>(legacyTrackJson)
        assertFalse(decodedLegacyTrack.isCloud)
        assertNull(decodedLegacyTrack.cloudSource)
        assertNull(decodedLegacyTrack.remoteTrackId)

        val cloudRoot = LibraryRoot(
            id = "c1",
            name = "YT Mix",
            kind = RootKind.CLOUD_YOUTUBE,
            sourceUrl = "https://music.youtube.com/playlist?list=PL123",
            remoteId = "PL123",
            lastSyncedEpochMs = 123456L,
        )
        val encodedRoot = Persistence.json.encodeToString(LibraryRoot.serializer(), cloudRoot)
        assertEquals(cloudRoot, Persistence.json.decodeFromString<LibraryRoot>(encodedRoot))
        assertTrue(cloudRoot.kind.isCloud)
    }

    @Test
    fun extractCloudSourceInputHandlesShareTextPunctuationAndMultiUrlCandidates() {
        assertEquals(
            "https://b23.tv/abc1234",
            extractCloudSourceInput("【歌单】深夜听的歌 https://b23.tv/abc1234"),
        )
        assertEquals(
            "https://b23.tv/abc1234",
            extractCloudSourceInput("【歌单】深夜听的歌https://b23.tv/abc1234快去听"),
        )
        assertEquals(
            "https://music.youtube.com/playlist?list=PL123",
            extractCloudSourceInput("My Mix https://music.youtube.com/playlist?list=PL123。"),
        )
        assertEquals(
            "https://www.youtube.com/playlist?list=PLabc123",
            extractCloudSourceInput("Check (https://www.youtube.com/playlist?list=PLabc123), enjoy!"),
        )
        assertEquals(
            "https://www.bilibili.com/video/BV1xx411c7mD",
            extractCloudSourceInput("「推荐」[https://www.bilibili.com/video/BV1xx411c7mD]】」》.,'\""),
        )
        assertEquals(
            "https://youtu.be/dQw4w9WgXcQ",
            extractCloudSourceInput("\"https://youtu.be/dQw4w9WgXcQ\"."),
        )

        // Multi-URL text returns first URL by default (no hardcoded YT/Bili bias), or preferred matcher / Google Drive
        assertEquals(
            "https://promo.example.com/intro",
            extractCloudSourceInput("Info https://promo.example.com/intro listen https://music.youtube.com/playlist?list=PL123"),
        )
        assertEquals(
            listOf("https://promo.example.com/intro", "https://music.youtube.com/playlist?list=PL123"),
            extractCloudSourceInputs("Info https://promo.example.com/intro listen https://music.youtube.com/playlist?list=PL123。"),
        )
        assertEquals(
            "https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOp?usp=sharing&resourcekey=0-rk123",
            extractCloudSourceInput("Promo https://promo.example.com and album https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOp?usp=sharing&resourcekey=0-rk123。"),
        )
        assertEquals(
            "https://drive.usercontent.google.com/download?id=1FileIdXYZ&export=download",
            extractCloudSourceInput("【音乐】https://drive.usercontent.google.com/download?id=1FileIdXYZ&export=download快下载"),
        )

        // Bare IDs are preserved cleanly
        assertEquals("PL1234567890", extractCloudSourceInput("  PL1234567890  "))
        assertEquals("BV1xx411c7mD", extractCloudSourceInput("BV1xx411c7mD"))
        assertEquals("av170001", extractCloudSourceInput("  av170001 "))
        assertEquals("ml555666", extractCloudSourceInput("ml555666"))
        assertEquals("", extractCloudSourceInput("   "))
    }

    @Test
    fun googleDriveUrlAndResourceKeyParsing() {
        assertEquals(
            GoogleDriveTarget.Folder("1FolderA", "0-rkFolder"),
            GoogleDriveClient.parseTarget("https://drive.google.com/drive/folders/1FolderA?usp=sharing&resourcekey=0-rkFolder"),
        )
        assertEquals(
            GoogleDriveTarget.Folder("1FolderB", null),
            GoogleDriveClient.parseTarget("https://drive.google.com/drive/u/0/folders/1FolderB"),
        )
        assertEquals(
            GoogleDriveTarget.Folder("1FolderC", null),
            GoogleDriveClient.parseTarget("https://drive.google.com/drive/mobile/folders/1FolderC"),
        )
        assertEquals(
            GoogleDriveTarget.Folder("1FolderD", "0-embRk"),
            GoogleDriveClient.parseTarget("https://drive.google.com/embeddedfolderview?id=1FolderD&resourcekey=0-embRk#list"),
        )
        assertEquals(
            GoogleDriveTarget.Folder("1FolderE", null),
            GoogleDriveClient.parseTarget("https://drive.google.com/folderview?id=1FolderE"),
        )
        assertEquals(
            GoogleDriveTarget.File("1FileA", "0-fileRk"),
            GoogleDriveClient.parseTarget("https://drive.google.com/file/d/1FileA/view?usp=drive_link&resourcekey=0-fileRk"),
        )
        assertEquals(
            GoogleDriveTarget.File("1FileB", null),
            GoogleDriveClient.parseTarget("https://drive.google.com/u/1/file/d/1FileB/preview"),
        )
        assertEquals(
            GoogleDriveTarget.File("1FileC", null),
            GoogleDriveClient.parseTarget("https://drive.google.com/uc?id=1FileC&export=download"),
        )
        assertEquals(
            GoogleDriveTarget.File("1FileD", "0-ucRk"),
            GoogleDriveClient.parseTarget("https://drive.usercontent.google.com/download?id=1FileD&resourcekey=0-ucRk"),
        )
        assertEquals(
            GoogleDriveTarget.Open("1OpenItem", "0-openRk"),
            GoogleDriveClient.parseTarget("https://drive.google.com/open?id=1OpenItem&resourcekey=0-openRk"),
        )
        assertTrue(GoogleDriveClient.canHandle("Check https://drive.google.com/drive/folders/1FolderA」"))
        assertFalse(GoogleDriveClient.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun googleDriveFolderParsesDriveIvdWithSubfoldersEmbeddedFallbackAndAudioFilter() = runBlocking {
        val http = FakeCloudHttpClient()
        val rootIvdJson = """[[["file_root_1",["root_folder"],"01 Intro.flac","audio/flac","0-trkRk1"],["file_non_audio",["root_folder"],"cover.jpg","image/jpeg"],["sub_folder_1",["root_folder"],"Disc 2","application/vnd.google-apps.folder"],["sub_folder_cycle",["root_folder"],"Cycle","application/vnd.google-apps.folder"]]]"""
        val escapedIvd = rootIvdJson
            .replace("\"", "\\x22")
            .replace("[", "\\x5b")
            .replace("]", "\\x5d")

        http.getHandlers.add { url, _ ->
            when {
                url.startsWith("https://drive.google.com/drive/folders/root_folder") -> http.jsonResponse(
                    """
                    <html><head>
                    <meta property="og:title" content="OST Collection - Google Drive">
                    </head><body>
                    <script>window['_DRIVE_ivd'] = '$escapedIvd';</script>
                    </body></html>
                    """.trimIndent()
                )
                url.startsWith("https://drive.google.com/drive/folders/sub_folder_1") -> http.jsonResponse(
                    """<html><head><title>Disc 2 - Google 云端硬盘</title></head><body>no ivd here</body></html>"""
                )
                url.startsWith("https://drive.google.com/embeddedfolderview?id=sub_folder_1") -> http.jsonResponse(
                    """
                    <html><head><title>Disc 2 - Google Drive</title></head><body>
                    <div class="flip-entry" id="entry-file_sub_1">
                      <a href="https://drive.google.com/file/d/file_sub_1/view?resourcekey=0-subRk">
                        <div class="flip-entry-title">02 Boss &amp; Battle.mp3</div>
                      </a>
                    </div>
                    <div class="flip-entry" id="entry-readme">
                      <a href="https://drive.google.com/file/d/readme_txt/view">
                        <div class="flip-entry-title">readme.txt</div>
                      </a>
                    </div>
                    <div class="flip-entry" id="entry-back-cycle">
                      <a href="https://drive.google.com/drive/folders/root_folder">
                        <div class="flip-entry-title">Parent</div>
                      </a>
                    </div>
                    </body></html>
                    """.trimIndent()
                )
                url.startsWith("https://drive.google.com/drive/folders/sub_folder_cycle") -> http.jsonResponse(
                    """<html><body><script>window._DRIVE_ivd = '[["root_folder",["sub_folder_cycle"],"Back","application/vnd.google-apps.folder"]]';</script></body></html>"""
                )
                else -> null
            }
        }

        val result = GoogleDriveClient(http).fetchPlaylist(
            "https://drive.google.com/drive/folders/root_folder?resourcekey=0-rootRk"
        )
        assertEquals(CloudSource.GOOGLE_DRIVE, result.source)
        assertEquals("root_folder", result.remoteId)
        assertEquals("OST Collection", result.title)
        assertEquals(2, result.tracks.size)

        val t1 = result.tracks[0]
        assertEquals("01 Intro", t1.title)
        assertEquals("OST Collection", t1.album)
        assertEquals("file_root_1~rk~0-trkRk1.flac", t1.remoteTrackId)
        assertEquals(1, t1.trackNumber)

        val t2 = result.tracks[1]
        assertEquals("02 Boss & Battle", t2.title)
        assertEquals("Disc 2", t2.album)
        assertEquals("file_sub_1~rk~0-subRk.mp3", t2.remoteTrackId)
        assertEquals(2, t2.trackNumber)
    }

    @Test
    fun googleDriveSingleFileOpenAndStreamResolutionWithVirusScanAndSignInErrors() = runBlocking {
        val http = FakeCloudHttpClient()
        http.getHandlers.add { url, _ ->
            when {
                url.startsWith("https://drive.google.com/open?id=single_open") -> CloudHttpResponse(
                    statusCode = 200,
                    finalUrl = "https://drive.google.com/file/d/single_open/view?resourcekey=0-openRk",
                    headers = emptyMap(),
                    bodyBytes = """<html><head><meta property="og:title" content="Live Track.m4a - Google Drive"></head></html>""".toByteArray(),
                )
                url.startsWith("https://drive.google.com/file/d/direct_audio/view") -> http.jsonResponse(
                    """<html><head><title>Direct Song.wav - Google Drive</title></head></html>"""
                )
                url.startsWith("https://drive.usercontent.google.com/download?id=direct_audio") -> CloudHttpResponse(
                    statusCode = 206,
                    finalUrl = url,
                    headers = mapOf(
                        "Content-Type" to listOf("application/octet-stream"),
                        "Content-Disposition" to listOf("""attachment; filename="Direct Song.wav""""),
                    ),
                    bodyBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46),
                )
                url.startsWith("https://drive.usercontent.google.com/download?id=virus_file") -> CloudHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    headers = mapOf("Content-Type" to listOf("text/html; charset=utf-8")),
                    bodyBytes = """
                        <html><body>
                        <form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
                          <input type="submit" value="Download anyway">
                          <input type="hidden" name="id" value="virus_file">
                          <input type="hidden" name="export" value="download">
                          <input type="hidden" name="confirm" value="t">
                          <input type="hidden" name="uuid" value="uuid-9999-abcd">
                          <input type="hidden" name="at" value="token-at-123">
                        </form>
                        </body></html>
                    """.trimIndent().toByteArray(),
                )
                url.startsWith("https://drive.usercontent.google.com/download?id=private_file") -> CloudHttpResponse(
                    statusCode = 200,
                    finalUrl = "https://accounts.google.com/ServiceLogin?continue=...",
                    headers = mapOf("Content-Type" to listOf("text/html")),
                    bodyBytes = "<html><title>Google Drive: Sign-in</title></html>".toByteArray(),
                )
                url.startsWith("https://drive.google.com/drive/folders/denied_folder") -> CloudHttpResponse(
                    statusCode = 403,
                    finalUrl = url,
                    headers = emptyMap(),
                    bodyBytes = ByteArray(0),
                )
                else -> null
            }
        }

        val client = GoogleDriveClient(http)
        val openSingle = client.fetchPlaylist("https://drive.google.com/open?id=single_open")
        assertEquals(CloudSource.GOOGLE_DRIVE, openSingle.source)
        assertEquals("Live Track", openSingle.title)
        assertEquals(1, openSingle.tracks.size)
        assertEquals("single_open~rk~0-openRk.m4a", openSingle.tracks[0].remoteTrackId)

        val directStream = client.resolveStream("direct_audio.wav")
        assertTrue(directStream.url.contains("id=direct_audio"))
        assertEquals("audio/wav", directStream.mimeType)
        assertFalse(directStream.headers["User-Agent"].isNullOrBlank())

        val virusStream = client.resolveStream("virus_file~rk~0-rkV.flac", cookie = "NID=abc")
        assertTrue(virusStream.url.contains("uuid=uuid-9999-abcd"))
        assertTrue(virusStream.url.contains("at=token-at-123"))
        assertTrue(virusStream.url.contains("confirm=t"))
        assertTrue(virusStream.url.contains("resourcekey=0-rkV"))
        assertEquals("audio/flac", virusStream.mimeType)
        assertEquals("NID=abc", virusStream.headers["Cookie"])

        // Resolver routes CloudSource.GOOGLE_DRIVE cleanly
        val resolver = CloudStreamResolver(googleDriveClient = client)
        val gdriveTrack = Track(
            id = "g1",
            rootId = "rg1",
            relativePath = "gdrive/direct_audio.wav",
            title = "Direct Song",
            artist = "Google Drive",
            album = "Direct Song",
            albumArtist = "Google Drive",
            durationSec = 0.0,
            fileSize = 0L,
            modifiedEpochMs = 0L,
            dateAddedEpochMs = 1L,
            cloudSource = CloudSource.GOOGLE_DRIVE,
            remoteTrackId = "direct_audio.wav",
        )
        assertEquals("audio/wav", resolver.resolve(gdriveTrack, root = null).mimeType)

        // Sign-in / 403 throws clear access error
        val signInFailure = runCatching { client.resolveStream("private_file.mp3") }.exceptionOrNull()
        assertNotNull(signInFailure)
        assertTrue(signInFailure!!.message!!.contains("Anyone with the link"))

        val folder403 = runCatching {
            client.fetchPlaylist("https://drive.google.com/drive/folders/denied_folder")
        }.exceptionOrNull()
        assertNotNull(folder403)
        assertTrue(folder403!!.message!!.contains("Anyone with the link"))
    }

    @Test
    fun googleDrivePaginationExtractsApiKeyClients6FallbackToV3AndRetries429() = runBlocking {
        val fakeKey = "AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ1234567"
        val http = FakeCloudHttpClient()
        var clients6Page2Calls = 0

        val page1Entries = (1..5).joinToString(",") { i ->
            """["f_p1_$i",["1BigFolder"],"0$i Part1.mp3","audio/mpeg"]"""
        }
        val ivd = """[[$page1Entries],"tok_p2"]"""
        val escaped = ivd.replace("\"", "\\x22").replace("[", "\\x5b").replace("]", "\\x5d")

        http.getHandlers.add { url, headers ->
            when {
                url.startsWith("https://drive.google.com/drive/folders/1BigFolder") -> http.jsonResponse(
                    """
                    <html><head><title>Large Mix - Google Drive</title></head><body>
                    <script>var key="$fakeKey"; window['_DRIVE_ivd'] = '$escaped';</script>
                    </body></html>
                    """.trimIndent()
                )
                url.startsWith("https://clients6.google.com/drive/v2beta/files") && url.contains("pageToken=tok_p2") -> {
                    assertEquals(fakeKey, headers["X-Goog-Api-Key"])
                    assertEquals("1BigFolder/0-bigRk", headers["X-Goog-Drive-Resource-Keys"])
                    clients6Page2Calls += 1
                    if (clients6Page2Calls == 1) {
                        CloudHttpResponse(
                            statusCode = 429,
                            finalUrl = url,
                            headers = mapOf("Retry-After" to listOf("0")),
                            bodyBytes = ByteArray(0),
                        )
                    } else {
                        http.jsonResponse(
                            """
                            {
                              "nextPageToken": "tok_p3",
                              "items": [
                                {"id": "f_p2_1", "title": "06 Part2.flac", "mimeType": "audio/flac"},
                                {"id": "f_p2_2", "title": "07 Part2.opus", "mimeType": "audio/opus"}
                              ]
                            }
                            """.trimIndent()
                        )
                    }
                }
                url.startsWith("https://clients6.google.com/drive/v2beta/files") && url.contains("pageToken=tok_p3") ->
                    http.jsonResponse("""{"error":{"code":403}}""", statusCode = 403)
                url.startsWith("https://www.googleapis.com/drive/v3/files") && url.contains("pageToken=tok_p3") -> {
                    assertEquals(fakeKey, headers["X-Goog-Api-Key"])
                    http.jsonResponse(
                        """
                        {
                          "files": [
                            {"id": "f_p3_1", "name": "08 Part3.m4a", "mimeType": "audio/mp4"}
                          ]
                        }
                        """.trimIndent()
                    )
                }
                else -> null
            }
        }

        val client = GoogleDriveClient(http)
        assertEquals(fakeKey, GoogleDriveClient.extractApiKey("""foo="$fakeKey""""))
        val res = client.fetchPlaylist("https://drive.google.com/drive/folders/1BigFolder?resourcekey=0-bigRk")
        assertEquals(8, res.tracks.size)
        assertEquals(2, clients6Page2Calls)
        assertTrue(res.tracks.any { it.title == "06 Part2" && it.relativePath == "06 Part2.flac" })
        assertTrue(res.tracks.any { it.title == "07 Part2" && it.relativePath == "07 Part2.opus" })
        assertTrue(res.tracks.any { it.title == "08 Part3" && it.relativePath == "08 Part3.m4a" })
    }

    @Test
    fun googleDriveDeepHierarchyResourceKeyInheritanceExtensionsEmptyFolderAndDisambiguation() = runBlocking {
        val http = FakeCloudHttpClient()
        fun ivdHtml(title: String, rawIvd: String): CloudHttpResponse {
            val esc = rawIvd
                .replace("\\", "\\\\")
                .replace("\"", "\\x22")
                .replace("[", "\\x5b")
                .replace("]", "\\x5d")
            return http.jsonResponse(
                """<html><head><title>$title - Google Drive</title></head><body><script>window['_DRIVE_ivd']='$esc';</script></body></html>"""
            )
        }
        http.getHandlers.add { url, _ ->
            when {
                url.startsWith("https://drive.google.com/drive/folders/fld0") -> {
                    assertTrue(url.contains("resourcekey=0-rootInherited"))
                    ivdHtml(
                        "Deep Root",
                        """[[
                            ["f_opus",["fld0"],"01 Track.opus","audio/opus"],
                            ["f_wma",["fld0"],"02 Old.wma","audio/x-ms-wma"],
                            ["f_webm",["fld0"],"03 Stream.webm","audio/webm"],
                            ["f_dup1",["fld0"],"Dup.mp3","audio/mpeg"],
                            ["f_dup2",["fld0"],"Dup.mp3","audio/mpeg"],
                            ["f_slash",["fld0"],"AC/DC \\ Live.mp4","video/mp4"],
                            ["d_empty",["fld0"],"EmptyDir","application/vnd.google-apps.folder"],
                            ["fld1",["fld0"],"L1","application/vnd.google-apps.folder"]
                        ]]""",
                    )
                }
                url.startsWith("https://drive.google.com/drive/folders/d_empty") ->
                    ivdHtml("EmptyDir", "[[],null]")
                url.startsWith("https://drive.google.com/drive/folders/fld1") -> {
                    // Inherited resourcekey from fld0 when child entry omitted resourceKey
                    assertTrue(url.contains("resourcekey=0-rootInherited"))
                    ivdHtml("L1", """[[["fld2",["fld1"],"L2","application/vnd.google-apps.folder"]]]""")
                }
                url.startsWith("https://drive.google.com/drive/folders/fld2") ->
                    ivdHtml("L2", """[[["fld3",["fld2"],"L3","application/vnd.google-apps.folder"]]]""")
                url.startsWith("https://drive.google.com/drive/folders/fld3") ->
                    ivdHtml("L3", """[[["fld4",["fld3"],"L4","application/vnd.google-apps.folder"]]]""")
                url.startsWith("https://drive.google.com/drive/folders/fld4") ->
                    ivdHtml("L4", """[[["fld5",["fld4"],"L5","application/vnd.google-apps.folder"]]]""")
                url.startsWith("https://drive.google.com/drive/folders/fld5") ->
                    ivdHtml("L5", """[[["fld6",["fld5"],"L6","application/vnd.google-apps.folder"]]]""")
                url.startsWith("https://drive.google.com/drive/folders/fld6") ->
                    ivdHtml("L6", """[[["f_deep",["fld6"],"Deep Song.ogg","audio/ogg"]]]""")
                else -> null
            }
        }

        val res = GoogleDriveClient(http).fetchPlaylist("https://drive.google.com/drive/folders/fld0?resourcekey=0-rootInherited")
        assertFalse(http.recordedGets.any { it.first.contains("embeddedfolderview") })

        val paths = res.tracks.mapNotNull { it.relativePath }.sorted()
        assertEquals(
            listOf(
                "01 Track.opus",
                "02 Old.wma",
                "03 Stream.webm",
                "AC_DC _ Live.mp4",
                "Dup (2).mp3",
                "Dup.mp3",
                "L1/L2/L3/L4/L5/L6/Deep Song.ogg",
            ),
            paths,
        )
        assertEquals("f_deep~rk~0-rootInherited.ogg", res.tracks.first { it.relativePath?.startsWith("L1/") == true }.remoteTrackId)
    }

    @Test
    fun googleDriveCrawlProgressivePriorityBumpEmitsBatchesAndCompletes() = runBlocking(Dispatchers.IO) {
        val http = FakeCloudHttpClient()
        val albumAGate = CountDownLatch(1)
        val albumAReached = CountDownLatch(1)
        val priorityRef = AtomicReference<String?>(null)
        val visited = Collections.synchronizedList(mutableListOf<String>())

        fun ivdHtml(title: String, rawIvd: String): CloudHttpResponse {
            val esc = rawIvd
                .replace("\\", "\\\\")
                .replace("\"", "\\x22")
                .replace("[", "\\x5b")
                .replace("]", "\\x5d")
            return http.jsonResponse(
                """<html><head><title>$title - Google Drive</title></head><body><script>window['_DRIVE_ivd']='$esc';</script></body></html>"""
            )
        }

        http.getHandlers.add { url, _ ->
            when {
                url.contains("/folders/p_root") -> ivdHtml(
                    "Prog Album",
                    """[[
                        ["t_r",["p_root"],"00 Intro.mp3","audio/mpeg"],
                        ["p_a",["p_root"],"Album A","application/vnd.google-apps.folder"],
                        ["p_b",["p_root"],"Album B","application/vnd.google-apps.folder"],
                        ["p_c",["p_root"],"Album C","application/vnd.google-apps.folder"],
                        ["p_d",["p_root"],"Album D","application/vnd.google-apps.folder"],
                        ["p_e",["p_root"],"Album E","application/vnd.google-apps.folder"]
                    ]]""",
                )
                url.contains("/folders/p_a") -> {
                    visited.add("p_a")
                    albumAReached.countDown()
                    albumAGate.await(5, TimeUnit.SECONDS)
                    ivdHtml("Album A", """[[["t_a",["p_a"],"01 A.mp3","audio/mpeg"]]]""")
                }
                url.contains("/folders/p_b") -> {
                    visited.add("p_b")
                    ivdHtml("Album B", """[[["t_b",["p_b"],"02 B.mp3","audio/mpeg"]]]""")
                }
                url.contains("/folders/p_c") -> {
                    visited.add("p_c")
                    ivdHtml("Album C", """[[["t_c",["p_c"],"03 C.mp3","audio/mpeg"]]]""")
                }
                url.contains("/folders/p_d") -> {
                    visited.add("p_d")
                    ivdHtml("Album D", """[[["t_d",["p_d"],"04 D.mp3","audio/mpeg"]]]""")
                }
                url.contains("/folders/p_e") -> {
                    visited.add("p_e")
                    ivdHtml("Album E", """[[["t_e",["p_e"],"05 E.mp3","audio/mpeg"]]]""")
                }
                else -> null
            }
        }

        val batches = Collections.synchronizedList(mutableListOf<DriveCrawlBatch>())
        val result = GoogleDriveClient(http).crawlProgressive(
            sourceUrl = "https://drive.google.com/drive/folders/p_root",
            priorityPathProvider = { priorityRef.get() },
        ) { batch ->
            batches.add(batch)
            if (batch.progress.completedFolders == 1) {
                priorityRef.set("Album E")
            }
            if ("p_e" in visited) {
                albumAGate.countDown()
            }
        }

        // Fallback safety release if p_e wasn't reached yet when batch arrived
        albumAGate.countDown()

        assertTrue(batches.isNotEmpty())
        assertTrue(batches.first().knownFolderPaths.containsAll(listOf("Album A", "Album B", "Album C", "Album D", "Album E")))
        assertTrue(batches.last().progress.isComplete)
        assertEquals(6, batches.last().progress.tracksSoFar)
        assertEquals(6, result.tracks.size)
    }

    @Test
    fun googleDriveMultiKeyFallbackCachingFiftyItemTruncationPaginationAndXFlac() = runBlocking {
        val keyBad400 = "AIzaSyBadFirstPartyKey00000000000000001"
        val keyBad403 = "AIzaSyDeniedSecondKey000000000000000002"
        val keyGood = "AIzaSyWorkingThirdKey000000000000000003"
        val htmlKeys = """<script>var a="$keyBad400", b="$keyBad403", c="$keyGood", dup="$keyBad400";</script>"""
        assertEquals(listOf(keyBad400, keyBad403, keyGood), GoogleDriveClient.extractApiKeys(htmlKeys))

        val fiftyEntries = (1..50).joinToString(",") { i ->
            """["f_r_$i",["fld_50"],"Song $i.mp3","audio/mpeg"]"""
        }
        val rootEscaped = """[[$fiftyEntries],null]""".replace("\"", "\\x22").replace("[", "\\x5b").replace("]", "\\x5d")
        val subEntries = (1..50).joinToString(",") { i ->
            """["f_s_$i",["fld_sub50"],"Sub $i","application/x-flac"]"""
        }
        val subEscaped = """[[$subEntries],null]""".replace("\"", "\\x22").replace("[", "\\x5b").replace("]", "\\x5d")

        val requestedKeys = mutableListOf<String>()
        val http = FakeCloudHttpClient()
        http.getHandlers.add { url, headers ->
            when {
                url.startsWith("https://drive.google.com/drive/folders/fld_50") -> http.jsonResponse(
                    """<html><head><title>Root 50 - Google Drive</title></head><body>$htmlKeys<script>window['_DRIVE_ivd']='$rootEscaped';</script></body></html>"""
                )
                url.startsWith("https://drive.google.com/drive/folders/fld_sub50") -> http.jsonResponse(
                    """<html><head><title>Sub 50 - Google Drive</title></head><body><script>var a="$keyBad400", c="$keyGood"; window['_DRIVE_ivd']='$subEscaped';</script></body></html>"""
                )
                url.startsWith("https://clients6.google.com/drive/v2beta/files") -> {
                    val key = headers["X-Goog-Api-Key"].orEmpty()
                    requestedKeys.add(key)
                    when (key) {
                        keyBad400 -> http.jsonResponse("""{"error":{"code":400}}""", statusCode = 400)
                        keyBad403 -> http.jsonResponse("""{"error":{"code":403}}""", statusCode = 403)
                        keyGood -> when {
                            url.contains("fld_50") && !url.contains("pageToken=") -> {
                                val items = (1..51).joinToString(",") { i ->
                                    """{"id":"f_r_$i","title":"Song $i.mp3","mimeType":"audio/mpeg"}"""
                                }
                                http.jsonResponse("""{"nextPageToken":"tok_2","items":[$items]}""")
                            }
                            url.contains("fld_50") && url.contains("pageToken=tok_2") -> http.jsonResponse(
                                """{"items":[{"id":"f_r_52","title":"Song 52.mp3","mimeType":"audio/mpeg"}]}"""
                            )
                            url.contains("fld_sub50") -> {
                                assertFalse(url.contains("pageToken="))
                                val items = (1..51).joinToString(",") { i ->
                                    """{"id":"f_s_$i","title":"Sub $i","mimeType":"application/x-flac"}"""
                                }
                                http.jsonResponse("""{"items":[$items]}""")
                            }
                            else -> null
                        }
                        else -> null
                    }
                }
                url.startsWith("https://www.googleapis.com/drive/v3/files") ->
                    http.jsonResponse("""{"error":{"code":403}}""", statusCode = 403)
                else -> null
            }
        }

        val client = GoogleDriveClient(http)
        val rootRes = client.fetchPlaylist("https://drive.google.com/drive/folders/fld_50")
        assertEquals(52, rootRes.tracks.size)
        assertTrue(rootRes.tracks.any { it.remoteTrackId == "f_r_52.mp3" })

        val keysBeforeSub = requestedKeys.size
        val subRes = client.fetchPlaylist("https://drive.google.com/drive/folders/fld_sub50")
        assertEquals(51, subRes.tracks.size)
        assertEquals("f_s_51.flac", subRes.tracks.last().remoteTrackId)
        assertEquals("Sub 51.flac", subRes.tracks.last().relativePath)
        assertEquals(keyGood, requestedKeys.drop(keysBeforeSub).first())

        // Fallback to embeddedfolderview when 50-item IVD folder has no working API key.
        val embHttp = FakeCloudHttpClient()
        embHttp.getHandlers.add { url, _ ->
            when {
                url.startsWith("https://drive.google.com/drive/folders/fld_emb50") -> embHttp.jsonResponse(
                    """<html><head><title>Emb 50 - Google Drive</title></head><body><script>var k="$keyBad403"; window['_DRIVE_ivd']='$rootEscaped';</script></body></html>"""
                )
                url.contains("v2beta/files") || url.contains("v3/files") ->
                    embHttp.jsonResponse("""{"error":{"code":403}}""", statusCode = 403)
                url.contains("/embeddedfolderview") -> embHttp.jsonResponse(
                    """
                    <html><body>
                      <div class="flip-entry"><a href="https://drive.google.com/file/d/f_r_1/view"><div class="flip-entry-title">Song 1.mp3</div></a></div>
                      <div class="flip-entry"><a href="https://drive.google.com/file/d/f_r_53/view"><div class="flip-entry-title">Song 53.mp3</div></a></div>
                    </body></html>
                    """.trimIndent()
                )
                else -> null
            }
        }
        val embRes = GoogleDriveClient(embHttp).fetchPlaylist("https://drive.google.com/drive/folders/fld_emb50")
        assertEquals(51, embRes.tracks.size)
        assertTrue(embRes.tracks.any { it.remoteTrackId == "f_r_53.mp3" })
    }
}
