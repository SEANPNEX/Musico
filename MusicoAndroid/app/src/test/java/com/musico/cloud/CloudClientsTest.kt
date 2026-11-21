package com.musico.cloud

import com.musico.core.Persistence
import com.musico.model.LibraryRoot
import com.musico.model.RootKind
import com.musico.model.Track
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun youTubeUrlAndIdParsing() {
        assertEquals(
            YouTubeTarget.Playlist("PL1234567890"),
            YouTubeClient.parseTarget("https://www.youtube.com/playlist?list=PL1234567890"),
        )
        assertEquals(
            YouTubeTarget.Playlist("OLAK5uy_abcdef123456"),
            YouTubeClient.parseTarget("https://music.youtube.com/playlist?list=OLAK5uy_abcdef123456"),
        )
        assertEquals(
            YouTubeTarget.Playlist("PL9999999999"),
            YouTubeClient.parseTarget("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL9999999999"),
        )
        assertEquals(
            YouTubeTarget.Video("dQw4w9WgXcQ"),
            YouTubeClient.parseTarget("https://youtu.be/dQw4w9WgXcQ?si=abc"),
        )
        assertEquals(
            YouTubeTarget.Video("dQw4w9WgXcQ"),
            YouTubeClient.parseTarget("https://www.youtube.com/shorts/dQw4w9WgXcQ"),
        )
        assertEquals(
            YouTubeTarget.Playlist("PLabcdef123456"),
            YouTubeClient.parseTarget("PLabcdef123456"),
        )
        assertEquals(
            YouTubeTarget.Video("dQw4w9WgXcQ"),
            YouTubeClient.parseTarget("dQw4w9WgXcQ"),
        )
        assertNull(YouTubeClient.parseTarget("https://www.bilibili.com/video/BV1xx411c7mD"))
        assertFalse(YouTubeClient.canHandle("https://space.bilibili.com/123/favlist?fid=456"))
    }

    @Test
    fun youTubeSapisidHashMatchesExpectedFormat() {
        val hash = YouTubeClient.computeSapisidHash(
            cookie = "PREF=f1; SAPISID=test_sapisid_value; SID=xyz",
            origin = "https://music.youtube.com",
            timestampSec = 1700000000L,
        )
        assertNotNull(hash)
        assertTrue(hash!!.startsWith("SAPISIDHASH 1700000000_"))
        assertEquals(12 + 11 + 40, hash.length)
    }

    @Test
    fun youTubePlaylistParsesResponsiveAndVideoRenderersWithContinuation() = runBlocking {
        val http = FakeCloudHttpClient()
        http.postHandlers.add { url, body, _ ->
            if (!url.contains("music.youtube.com/youtubei/v1/browse")) return@add null
            val json = body.parseJsonObject()
            if (json.containsKey("continuation")) {
                http.jsonResponse(
                    """
                    {
                      "continuationContents": {
                        "musicPlaylistShelfContinuation": {
                          "contents": [
                            {
                              "playlistVideoRenderer": {
                                "videoId": "vid_page2_01",
                                "title": { "runs": [ { "text": "Second Page Song" } ] },
                                "shortBylineText": { "runs": [ { "text": "Page2 Artist" } ] },
                                "lengthSeconds": "215",
                                "thumbnail": { "thumbnails": [ { "url": "http://img.youtube.com/vi/2.jpg", "width": 480 } ] }
                              }
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent()
                )
            } else {
                http.jsonResponse(
                    """
                    {
                      "header": {
                        "musicDetailHeaderRenderer": {
                          "title": { "runs": [ { "text": "Lo-Fi Mix" } ] }
                        }
                      },
                      "contents": {
                        "twoColumnBrowseResultsRenderer": {
                          "secondaryContents": {
                            "sectionListRenderer": {
                              "contents": [
                                {
                                  "musicPlaylistShelfRenderer": {
                                    "contents": [
                                      {
                                        "musicResponsiveListItemRenderer": {
                                          "playlistItemData": { "videoId": "vid_page1_01" },
                                          "flexColumns": [
                                            {
                                              "musicResponsiveListItemFlexColumnRenderer": {
                                                "text": { "runs": [ { "text": "First Song" } ] }
                                              }
                                            },
                                            {
                                              "musicResponsiveListItemFlexColumnRenderer": {
                                                "text": { "runs": [ { "text": "Chill Artist" }, { "text": " • " }, { "text": "Night Album" } ] }
                                              }
                                            }
                                          ],
                                          "fixedColumns": [
                                            {
                                              "musicResponsiveListItemFixedColumnRenderer": {
                                                "text": { "runs": [ { "text": "3:45" } ] }
                                              }
                                            }
                                          ],
                                          "thumbnail": {
                                            "musicThumbnailRenderer": {
                                              "thumbnail": {
                                                "thumbnails": [ { "url": "https://lh3.googleusercontent.com/art1", "width": 544 } ]
                                              }
                                            }
                                          }
                                        }
                                      }
                                    ],
                                    "continuations": [
                                      {
                                        "nextContinuationData": { "continuation": "CONT_TOKEN_2" }
                                      }
                                    ]
                                  }
                                }
                              ]
                            }
                          }
                        }
                      }
                    }
                    """.trimIndent()
                )
            }
        }

        val client = YouTubeClient(http)
        val result = client.fetchPlaylist("https://music.youtube.com/playlist?list=PL1234567890")
        assertEquals(CloudSource.YOUTUBE, result.source)
        assertEquals("PL1234567890", result.remoteId)
        assertEquals("Lo-Fi Mix", result.title)
        assertEquals(2, result.tracks.size)

        assertEquals("vid_page1_01", result.tracks[0].remoteTrackId)
        assertEquals("First Song", result.tracks[0].title)
        assertEquals("Chill Artist", result.tracks[0].artist)
        assertEquals("Night Album", result.tracks[0].album)
        assertEquals(225.0, result.tracks[0].durationSec, 0.01)
        assertEquals("https://lh3.googleusercontent.com/art1", result.tracks[0].artworkUrl)
        assertEquals(1, result.tracks[0].trackNumber)

        assertEquals("vid_page2_01", result.tracks[1].remoteTrackId)
        assertEquals("Second Page Song", result.tracks[1].title)
        assertEquals("Page2 Artist", result.tracks[1].artist)
        assertEquals("Lo-Fi Mix", result.tracks[1].album)
        assertEquals(215.0, result.tracks[1].durationSec, 0.01)
        assertEquals("https://img.youtube.com/vi/2.jpg", result.tracks[1].artworkUrl)
        assertEquals(2, result.tracks[1].trackNumber)
    }

    @Test
    fun youTubeStreamResolutionFallsBackAcrossClientsAndSkips403Probe() = runBlocking {
        val http = FakeCloudHttpClient()
        http.postHandlers.add { url, body, _ ->
            if (!url.contains("/youtubei/v1/player")) return@add null
            val clientName = body.parseJsonObject().obj("context")?.obj("client")?.str("clientName")
            when (clientName) {
                "ANDROID" -> http.jsonResponse(
                    """
                    {
                      "streamingData": {
                        "expiresInSeconds": "3600",
                        "adaptiveFormats": [
                          { "itag": 251, "url": "https://cdn.example.com/android-403", "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000 }
                        ]
                      }
                    }
                    """.trimIndent()
                )
                "IOS" -> http.jsonResponse(
                    """
                    {
                      "streamingData": {
                        "expiresInSeconds": "3600",
                        "adaptiveFormats": [
                          { "itag": 140, "url": "https://cdn.example.com/ios-ok", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000 },
                          { "itag": 251, "url": "https://cdn.example.com/ios-403", "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000 }
                        ]
                      }
                    }
                    """.trimIndent()
                )
                else -> null
            }
        }
        http.probeHandlers.add { url, _ ->
            when {
                url.contains("403") -> 403
                url.contains("ios-ok") -> 206
                else -> 404
            }
        }

        val stream = YouTubeClient(http).resolveStream("dQw4w9WgXcQ")
        assertEquals("https://cdn.example.com/ios-ok", stream.url)
        assertTrue(http.recordedProbes.any { it.first == "https://cdn.example.com/android-403" && it.second["Range"] == "bytes=0-" })
        assertTrue(http.recordedProbes.any { it.first == "https://cdn.example.com/ios-403" })
    }

    @Test
    fun bilibiliUrlParsingAndShortLinkRedirect() = runBlocking {
        assertEquals(
            BilibiliTarget.FavList("987654"),
            BilibiliClient.parseTarget("https://space.bilibili.com/12345/favlist?fid=987654"),
        )
        assertEquals(
            BilibiliTarget.FavList("555666"),
            BilibiliClient.parseTarget("https://www.bilibili.com/medialist/detail/ml555666"),
        )
        assertEquals(
            BilibiliTarget.Collection(mid = "12345", seasonId = "777"),
            BilibiliClient.parseTarget("https://space.bilibili.com/12345/lists/777?type=season"),
        )
        assertEquals(
            BilibiliTarget.Series(mid = "12345", seriesId = "888"),
            BilibiliClient.parseTarget("https://space.bilibili.com/12345/channel/seriesdetail?sid=888"),
        )
        assertEquals(
            BilibiliTarget.Video(bvid = "BV1xx411c7mD"),
            BilibiliClient.parseTarget("https://www.bilibili.com/video/BV1xx411c7mD?p=2"),
        )
        assertEquals(
            BilibiliTarget.Video(aid = 170001L),
            BilibiliClient.parseTarget("https://www.bilibili.com/video/av170001"),
        )

        val http = FakeCloudHttpClient()
        http.getHandlers.add { url, _ ->
            when {
                url.contains("b23.tv") -> http.jsonResponse(
                    body = "",
                    statusCode = 200,
                    finalUrl = "https://www.bilibili.com/video/BV1GJ411x7h7",
                )
                url.contains("/x/frontend/finger/spi") -> http.jsonResponse("""{"code":0,"data":{"b_3":"TEST_BUVID3"}}""")
                url.contains("/x/web-interface/view") -> http.jsonResponse(
                    """
                    {
                      "code": 0,
                      "data": {
                        "bvid": "BV1GJ411x7h7",
                        "title": "Test Multi-Part",
                        "pic": "http://i0.hdslb.com/bfs/archive/cover.jpg",
                        "duration": 300,
                        "owner": { "name": "UP主" },
                        "pages": [
                          { "cid": 1001, "page": 1, "part": "Intro", "duration": 120 },
                          { "cid": 1002, "page": 2, "part": "Main", "duration": 180 }
                        ]
                      }
                    }
                    """.trimIndent()
                )
                else -> null
            }
        }

        val result = BilibiliClient(http).fetchPlaylist("https://b23.tv/abc123")
        assertEquals(CloudSource.BILIBILI, result.source)
        assertEquals("video:BV1GJ411x7h7", result.remoteId)
        assertEquals("Test Multi-Part", result.title)
        assertEquals(2, result.tracks.size)
        assertEquals("BV1GJ411x7h7#1001", result.tracks[0].remoteTrackId)
        assertEquals("P1 Intro", result.tracks[0].title)
        assertEquals(120.0, result.tracks[0].durationSec, 0.01)
        assertEquals("https://i0.hdslb.com/bfs/archive/cover.jpg", result.tracks[0].artworkUrl)
        assertEquals("BV1GJ411x7h7#1002", result.tracks[1].remoteTrackId)
        assertEquals("P2 Main", result.tracks[1].title)
        assertEquals(180.0, result.tracks[1].durationSec, 0.01)
    }

    @Test
    fun bilibiliWbiMixinAndSigningMatchesStandardVector() {
        val imgUrl = "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png"
        val subUrl = "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"
        val mixinKey = BilibiliClient.extractMixinKey(imgUrl, subUrl)
        assertEquals("ea1db124af3c7062474693fa704f4ff8", mixinKey)

        val signed = BilibiliClient.signWbiParams(
            params = mapOf("foo" to "114", "bar" to "514", "baz" to "1919810"),
            mixinKey = mixinKey,
            wtsSec = 1702204169L,
        )
        assertEquals("bar=514&baz=1919810&foo=114&wts=1702204169&w_rid=6149fdadf571698ca7e6a567265cd0ee", signed)
    }

    @Test
    fun bilibiliFavCollectionAndPlayUrlResolution() = runBlocking {
        val http = FakeCloudHttpClient()
        http.getHandlers.add { url, _ ->
            when {
                url.contains("/x/frontend/finger/spi") -> http.jsonResponse("""{"code":0,"data":{"b_3":"BUVID3_OK"}}""")
                url.contains("/x/web-interface/nav") -> http.jsonResponse(
                    """
                    {
                      "code": 0,
                      "data": {
                        "wbi_img": {
                          "img_url": "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
                          "sub_url": "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"
                        }
                      }
                    }
                    """.trimIndent()
                )
                url.contains("/x/v3/fav/resource/list") -> http.jsonResponse(
                    """
                    {
                      "code": 0,
                      "data": {
                        "info": { "title": "My Favs" },
                        "has_more": false,
                        "medias": [
                          {
                            "bvid": "BV1111111111",
                            "title": "Fav Track",
                            "upper": { "name": "Uploader" },
                            "cover": "http://i0.hdslb.com/bfs/fav.jpg",
                            "duration": 200,
                            "attr": 0
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
                url.contains("/x/polymer/web-space/seasons_archives_list") -> http.jsonResponse(
                    """
                    {
                      "code": 0,
                      "data": {
                        "meta": { "name": "Season Album" },
                        "page": { "total": 1 },
                        "archives": [
                          {
                            "bvid": "BV2222222222",
                            "title": "Season Track",
                            "author": "Creator",
                            "pic": "https://i0.hdslb.com/bfs/season.jpg",
                            "duration": 150
                          }
                        ]
                      }
                    }
                    """.trimIndent()
                )
                url.contains("/x/player/wbi/playurl") -> http.jsonResponse(
                    """
                    {
                      "code": 0,
                      "data": {
                        "dash": {
                          "audio": [
                            { "id": 30216, "bandwidth": 64000, "baseUrl": "http://cdn.bilivideo.com/low.m4s" },
                            { "id": 30280, "bandwidth": 192000, "baseUrl": "http://cdn.bilivideo.com/high.m4s" }
                          ]
                        }
                      }
                    }
                    """.trimIndent()
                )
                else -> null
            }
        }

        val client = BilibiliClient(http)
        val fav = client.fetchPlaylist("https://space.bilibili.com/1/favlist?fid=42")
        assertEquals("My Favs", fav.title)
        assertEquals(listOf("BV1111111111"), fav.tracks.map { it.remoteTrackId })

        val season = client.fetchPlaylist("https://space.bilibili.com/1/lists/77?type=season")
        assertEquals("Season Album", season.title)
        assertEquals(listOf("BV2222222222"), season.tracks.map { it.remoteTrackId })

        val stream = client.resolveStream("BV1111111111#999")
        assertEquals("https://cdn.bilivideo.com/high.m4s", stream.url)
        assertTrue(stream.headers["Referer"]?.contains("bilibili.com") == true)
        assertTrue(stream.headers["Cookie"]?.contains("buvid3=BUVID3_OK") == true)
        assertFalse(stream.headers["User-Agent"].isNullOrBlank())
        assertTrue(http.recordedGets.any { it.first.contains("/x/player/wbi/playurl") && it.first.contains("w_rid=") })
    }

    @Test
    fun cloudStreamResolverCachesAndInvalidates() = runBlocking {
        var playerCalls = 0
        val http = FakeCloudHttpClient()
        http.postHandlers.add { url, _, _ ->
            if (!url.contains("/youtubei/v1/player")) return@add null
            playerCalls += 1
            http.jsonResponse(
                """
                {
                  "streamingData": {
                    "expiresInSeconds": "3600",
                    "adaptiveFormats": [
                      { "itag": 140, "url": "https://cdn.example.com/stream-$playerCalls", "mimeType": "audio/mp4", "bitrate": 128000 }
                    ]
                  }
                }
                """.trimIndent()
            )
        }
        val resolver = CloudStreamResolver(youTubeClient = YouTubeClient(http), bilibiliClient = BilibiliClient(http))
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
        val first = resolver.resolve(track, root = null)
        val second = resolver.resolve(track, root = null)
        assertEquals(first.url, second.url)
        assertEquals(1, playerCalls)

        resolver.invalidate(track)
        val third = resolver.resolve(track, root = null)
        assertEquals("https://cdn.example.com/stream-2", third.url)
        assertEquals(2, playerCalls)
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
}
