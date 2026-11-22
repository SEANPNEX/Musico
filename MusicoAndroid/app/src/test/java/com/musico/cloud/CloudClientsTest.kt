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
            YouTubeTarget.Playlist("PL9999999999", "dQw4w9WgXcQ"),
            YouTubeClient.parseTarget("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL9999999999"),
        )
        assertEquals(
            YouTubeTarget.Playlist("PL8888888888", "dQw4w9WgXcQ"),
            YouTubeClient.parseTarget("https://youtu.be/dQw4w9WgXcQ?list=PL8888888888"),
        )
        assertEquals(
            YouTubeTarget.Playlist("RDdQw4w9WgXcQ", "dQw4w9WgXcQ"),
            YouTubeClient.parseTarget("https://www.youtube.com/shorts/dQw4w9WgXcQ?list=RDdQw4w9WgXcQ"),
        )
        assertEquals(
            YouTubeTarget.Playlist("MPREb_abc123456"),
            YouTubeClient.parseTarget("https://music.youtube.com/browse/MPREb_abc123456"),
        )
        assertEquals(
            YouTubeTarget.Playlist("MPREb_abc123456"),
            YouTubeClient.parseTarget("MPREb_abc123456"),
        )
        assertEquals(
            YouTubeTarget.Playlist("RDMM"),
            YouTubeClient.parseTarget("RDMM"),
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
        assertFalse(stream.headers.containsKey("Range"))
        assertTrue(http.recordedProbes.any { it.first == "https://cdn.example.com/android-403" && it.second["Range"] == "bytes=0-" })
        assertTrue(http.recordedProbes.any { it.first == "https://cdn.example.com/ios-403" })
    }

    @Test
    fun youTubeLockupViewModelAndPageHeaderAndMprebBrowse() = runBlocking {
        val http = FakeCloudHttpClient()
        http.postHandlers.add { url, body, headers ->
            if (!url.contains("music.youtube.com/youtubei/v1/browse")) return@add null
            assertEquals("67", headers["X-YouTube-Client-Name"])
            assertEquals("1.20260707.12.00", headers["X-YouTube-Client-Version"])
            assertEquals("SOCS=CAI", headers["Cookie"])
            val json = body.parseJsonObject()
            assertEquals("MPREb_album12345", json.str("browseId"))
            http.jsonResponse(
                """
                {
                  "header": {
                    "pageHeaderRenderer": {
                      "content": {
                        "pageHeaderViewModel": {
                          "title": {
                            "dynamicTextViewModel": {
                              "text": { "content": "Modern Album" }
                            }
                          }
                        }
                      }
                    }
                  },
                  "contents": {
                    "singleColumnBrowseResultsRenderer": {
                      "tabs": [
                        {
                          "tabRenderer": {
                            "content": {
                              "sectionListRenderer": {
                                "contents": [
                                  {
                                    "lockupViewModel": {
                                      "contentId": "abcDEF12345",
                                      "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                                      "metadata": {
                                        "lockupMetadataViewModel": {
                                          "title": { "content": "Lockup Track" },
                                          "metadata": {
                                            "contentMetadataViewModel": {
                                              "metadataRows": [
                                                { "metadataParts": [ { "text": { "content": "Lockup Artist" } } ] }
                                              ]
                                            }
                                          }
                                        }
                                      },
                                      "contentImage": {
                                        "thumbnailViewModel": {
                                          "image": {
                                            "sources": [
                                              { "url": "https://img.example.com/small.jpg", "width": 120 },
                                              { "url": "https://img.example.com/large.jpg", "width": 720 }
                                            ]
                                          },
                                          "overlays": [
                                            {
                                              "thumbnailOverlayBadgeViewModel": {
                                                "thumbnailBadges": [
                                                  { "thumbnailBadgeViewModel": { "text": "4:12" } }
                                                ]
                                              }
                                            }
                                          ]
                                        }
                                      }
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
                """.trimIndent()
            )
        }

        val result = YouTubeClient(http).fetchPlaylist("https://music.youtube.com/browse/MPREb_album12345")
        assertEquals("MPREb_album12345", result.remoteId)
        assertEquals("Modern Album", result.title)
        assertEquals(1, result.tracks.size)
        val track = result.tracks[0]
        assertEquals("abcDEF12345", track.remoteTrackId)
        assertEquals("Lockup Track", track.title)
        assertEquals("Lockup Artist", track.artist)
        assertEquals("Modern Album", track.album)
        assertEquals(252.0, track.durationSec, 0.01)
        assertEquals("https://img.example.com/large.jpg", track.artworkUrl)
        assertTrue(http.recordedGets.none { it.first.contains("sw.js_data") })
    }

    @Test
    fun youTubeRdMixUsesNextEndpointAndFallsBackToSingleVideoWhenEmpty() = runBlocking {
        val http = FakeCloudHttpClient()
        http.postHandlers.add { url, body, headers ->
            if (!url.contains("www.youtube.com/youtubei/v1/next")) return@add null
            assertEquals("1", headers["X-YouTube-Client-Name"])
            assertEquals("2.20260708.00.00", headers["X-YouTube-Client-Version"])
            assertEquals("SOCS=CAI", headers["Cookie"])
            val json = body.parseJsonObject()
            assertEquals("RDdQw4w9WgXcQ", json.str("playlistId"))
            assertEquals("dQw4w9WgXcQ", json.str("videoId"))
            http.jsonResponse(
                """
                {
                  "contents": {
                    "twoColumnWatchNextResults": {
                      "playlist": {
                        "playlist": {
                          "title": "Mix - Classics",
                          "contents": [
                            {
                              "playlistPanelVideoRenderer": {
                                "videoId": "dQw4w9WgXcQ",
                                "title": { "simpleText": "Mix Track One" },
                                "shortBylineText": { "runs": [ { "text": "Artist A" } ] },
                                "lengthText": { "simpleText": "3:33" },
                                "thumbnail": { "thumbnails": [ { "url": "https://img.example.com/mix1.jpg", "width": 320 } ] }
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

        val mixResult = YouTubeClient(http).fetchPlaylist("https://www.youtube.com/playlist?list=RDdQw4w9WgXcQ")
        assertEquals("RDdQw4w9WgXcQ", mixResult.remoteId)
        assertEquals("Mix - Classics", mixResult.title)
        assertEquals(1, mixResult.tracks.size)
        assertEquals("dQw4w9WgXcQ", mixResult.tracks[0].remoteTrackId)
        assertEquals("Mix Track One", mixResult.tracks[0].title)
        assertEquals("Artist A", mixResult.tracks[0].artist)
        assertEquals(213.0, mixResult.tracks[0].durationSec, 0.01)

        // When all playlist methods yield 0 tracks and videoId is present, fall back to single video
        val fallbackHttp = FakeCloudHttpClient()
        fallbackHttp.postHandlers.add { url, _, _ ->
            if (url.contains("/youtubei/v1/player")) {
                fallbackHttp.jsonResponse(
                    """
                    {
                      "videoDetails": {
                        "videoId": "dQw4w9WgXcQ",
                        "title": "Fallback Single Song",
                        "author": "Solo Artist",
                        "lengthSeconds": "195"
                      }
                    }
                    """.trimIndent()
                )
            } else null
        }
        val singleFallback = YouTubeClient(fallbackHttp).fetchPlaylist(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLempty999999"
        )
        assertEquals("dQw4w9WgXcQ", singleFallback.remoteId)
        assertEquals("Fallback Single Song", singleFallback.title)
        assertEquals(1, singleFallback.tracks.size)
        assertEquals("Solo Artist", singleFallback.tracks[0].artist)
    }

    @Test
    fun youTubeStreamResolutionUsesVisionOsVisitorDataAndRejectsPost1Mb403() = runBlocking {
        val http = FakeCloudHttpClient()
        val visitorToken = "Cgx0ZXN0VmlzaXRvckRhdGExMjM%3D"
        http.getHandlers.add { url, _ ->
            if (url.contains("/sw.js_data")) {
                http.jsonResponse("""{"responseContext":{},"visitorData":"$visitorToken"}""")
            } else null
        }
        http.postHandlers.add { url, body, headers ->
            if (!url.contains("/youtubei/v1/player")) return@add null
            val json = body.parseJsonObject()
            val clientObj = json.obj("context")?.obj("client")
            val clientName = clientObj?.str("clientName")
            assertEquals(visitorToken, clientObj?.str("visitorData"))
            assertEquals(visitorToken, headers["X-Goog-Visitor-Id"])
            assertEquals("SOCS=CAI", headers["Cookie"])
            when (clientName) {
                "VISIONOS" -> {
                    assertEquals("101", headers["X-YouTube-Client-Name"])
                    assertEquals("1.02", headers["X-YouTube-Client-Version"])
                    assertNull(json["params"])
                    http.jsonResponse(
                        """
                        {
                          "streamingData": {
                            "expiresInSeconds": "3600",
                            "adaptiveFormats": [
                              {
                                "itag": 140,
                                "url": "https://cdn.example.com/visionos-wall",
                                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                                "bitrate": 128000,
                                "contentLength": "3500000"
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                }
                "ANDROID" -> {
                    assertEquals("3", headers["X-YouTube-Client-Name"])
                    http.jsonResponse(
                        """
                        {
                          "streamingData": {
                            "expiresInSeconds": "3600",
                            "adaptiveFormats": [
                              {
                                "itag": 140,
                                "url": "https://cdn.example.com/android-clean",
                                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                                "bitrate": 128000,
                                "contentLength": "3500000"
                              }
                            ]
                          }
                        }
                        """.trimIndent()
                    )
                }
                else -> null
            }
        }
        http.probeHandlers.add { url, headers ->
            val range = headers["Range"]
            when {
                url.contains("visionos-wall") && range == "bytes=0-" -> 206
                url.contains("visionos-wall") && range == "bytes=1048576-1049599" -> 403
                url.contains("android-clean") -> 206
                else -> 404
            }
        }

        val stream = YouTubeClient(http).resolveStream("dQw4w9WgXcQ")
        assertEquals("https://cdn.example.com/android-clean", stream.url)
        assertEquals(3_500_000L, stream.contentLength)
        assertFalse(stream.headers.containsKey("Range"))
        assertTrue(
            http.recordedProbes.any {
                it.first == "https://cdn.example.com/visionos-wall" &&
                    it.second["Range"] == "bytes=1048576-1049599"
            }
        )
        // sw.js_data is cached across subsequent resolveStream calls
        YouTubeClient(http).resolveStream("dQw4w9WgXcQ")
        val client = YouTubeClient(http)
        client.resolveStream("dQw4w9WgXcQ")
        client.resolveStream("dQw4w9WgXcQ")
        assertEquals(3, http.recordedGets.count { it.first.contains("/sw.js_data") })
    }

    @Test
    fun youTubeSingleVideoFallsBackToOEmbedWhenPlayerDetailsMissing() = runBlocking {
        val http = FakeCloudHttpClient()
        http.getHandlers.add { url, _ ->
            if (url.contains("/oembed")) {
                http.jsonResponse(
                    """
                    {
                      "title": "OEmbed Song Title",
                      "author_name": "OEmbed Channel",
                      "thumbnail_url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
                    }
                    """.trimIndent()
                )
            } else null
        }

        val result = YouTubeClient(http).fetchPlaylist("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals("dQw4w9WgXcQ", result.remoteId)
        assertEquals("OEmbed Song Title", result.title)
        assertEquals(1, result.tracks.size)
        assertEquals("OEmbed Channel", result.tracks[0].artist)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", result.tracks[0].artworkUrl)
    }

    @Test
    fun youTubePlaylistFallsBackToHtmlYtInitialData() = runBlocking {
        val http = FakeCloudHttpClient()
        http.getHandlers.add { url, headers ->
            if (!url.contains("www.youtube.com/playlist?list=PLhtmlfallback1")) return@add null
            assertEquals("SOCS=CAI", headers["Cookie"])
            http.jsonResponse(
                """
                <html><head><title>YouTube</title></head><body>
                <script>var ytInitialData = {"metadata":{"playlistMetadataRenderer":{"title":"HTML Fallback Mix"}},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"playlistVideoListRenderer":{"contents":[{"playlistVideoRenderer":{"videoId":"htmlvid0001","title":{"runs":[{"text":"HTML Track {One}"}]},"shortBylineText":{"runs":[{"text":"HTML Artist"}]},"lengthSeconds":"180"}}]}}]}}]}}}}]}}};</script>
                </body></html>
                """.trimIndent()
            )
        }

        val result = YouTubeClient(http).fetchPlaylist("https://www.youtube.com/playlist?list=PLhtmlfallback1")
        assertEquals("PLhtmlfallback1", result.remoteId)
        assertEquals("HTML Fallback Mix", result.title)
        assertEquals(1, result.tracks.size)
        assertEquals("htmlvid0001", result.tracks[0].remoteTrackId)
        assertEquals("HTML Track {One}", result.tracks[0].title)
        assertEquals("HTML Artist", result.tracks[0].artist)
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

    @Test
    fun extractCloudSourceInputHandlesShareTextPunctuationAndBareIds() {
        // Chinese & English share text with surrounding titles and trailing punctuation
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

        // Bare IDs are preserved cleanly
        assertEquals("PL1234567890", extractCloudSourceInput("  PL1234567890  "))
        assertEquals("BV1xx411c7mD", extractCloudSourceInput("BV1xx411c7mD"))
        assertEquals("av170001", extractCloudSourceInput("  av170001 "))
        assertEquals("ml555666", extractCloudSourceInput("ml555666"))
        assertEquals("", extractCloudSourceInput("   "))

        // Clients classify and parse share text directly
        assertTrue(BilibiliClient.canHandle("【歌单】深夜听的歌 https://b23.tv/abc1234。"))
        assertEquals(
            BilibiliTarget.Video(bvid = "BV1xx411c7mD"),
            BilibiliClient.parseTarget("【B站】好歌分享 https://www.bilibili.com/video/BV1xx411c7mD」"),
        )
        assertTrue(YouTubeClient.canHandle("My Mix https://music.youtube.com/playlist?list=PL123。"))
        assertEquals(
            YouTubeTarget.Playlist("PL123"),
            YouTubeClient.parseTarget("My Mix https://music.youtube.com/playlist?list=PL123。"),
        )

        // Google Drive share text extraction
        assertEquals(
            "https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOp?usp=sharing&resourcekey=0-rk123",
            extractCloudSourceInput("Shared album: https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOp?usp=sharing&resourcekey=0-rk123。"),
        )
        assertEquals(
            "https://drive.usercontent.google.com/download?id=1FileIdXYZ&export=download",
            extractCloudSourceInput("【音乐】https://drive.usercontent.google.com/download?id=1FileIdXYZ&export=download快下载"),
        )
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
    fun youTubeResolveStreamPopulatesContentLengthFromStringLongOrNull() = runBlocking {
        val http = FakeCloudHttpClient()
        http.postHandlers.add { url, body, _ ->
            if (!url.contains("/youtubei/v1/player")) return@add null
            val vid = body.parseJsonObject().str("videoId")
            when (vid) {
                "strLen00001" -> http.jsonResponse(
                    """
                    {
                      "streamingData": {
                        "expiresInSeconds": "3600",
                        "adaptiveFormats": [
                          { "itag": 140, "url": "https://cdn.example.com/str", "mimeType": "audio/mp4", "bitrate": 128000, "contentLength": "9437184" }
                        ]
                      }
                    }
                    """.trimIndent()
                )
                "numLen00002" -> http.jsonResponse(
                    """
                    {
                      "streamingData": {
                        "expiresInSeconds": "3600",
                        "adaptiveFormats": [
                          { "itag": 251, "url": "https://cdn.example.com/num", "mimeType": "audio/webm", "bitrate": 160000, "contentLength": 5242880 }
                        ]
                      }
                    }
                    """.trimIndent()
                )
                else -> http.jsonResponse(
                    """
                    {
                      "streamingData": {
                        "expiresInSeconds": "3600",
                        "adaptiveFormats": [
                          { "itag": 140, "url": "https://cdn.example.com/missing", "mimeType": "audio/mp4", "bitrate": 128000 }
                        ]
                      }
                    }
                    """.trimIndent()
                )
            }
        }
        http.probeHandlers.add { _, _ -> 206 }

        val client = YouTubeClient(http)
        assertEquals(9_437_184L, client.resolveStream("strLen00001").contentLength)
        assertEquals(5_242_880L, client.resolveStream("numLen00002").contentLength)
        assertNull(client.resolveStream("noLen000003").contentLength)
    }
}
