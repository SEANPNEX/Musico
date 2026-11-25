package com.musico.lyrics

import com.musico.cloud.CloudHttpClient
import com.musico.cloud.CloudHttpRequest
import com.musico.cloud.CloudHttpResponse
import com.musico.core.LrcParser
import com.musico.core.LyricsStore
import com.musico.extensions.CloudProviderRegistry
import com.musico.extensions.ExtensionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.util.Collections

private class MockCloudLyricsHttpClient(
    private val handler: (method: String, url: String, headers: Map<String, String>, body: String?) -> CloudHttpResponse,
) : CloudHttpClient {
    val requests: MutableList<Pair<String, Map<String, String>>> =
        Collections.synchronizedList(ArrayList())

    override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse {
        requests.add(url to headers)
        return handler("GET", url, headers, null)
    }

    override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse {
        requests.add(url to headers)
        return handler("POST", url, headers, jsonBody)
    }

    override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 200
}

class CloudLyricsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun lrclibSearchSendsUserAgentHandles404AndParsesSyncedLyrics() = runBlocking {
        val mockHttp = MockCloudLyricsHttpClient { _, url, _, _ ->
            val path = URI(url).path
            when {
                path == "/api/get" -> CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
                path == "/api/search" && url.contains("track_name=") ->
                    CloudHttpResponse(200, url, emptyMap(), "[]".toByteArray())
                path == "/api/search" -> {
                    val json = """
                        [
                          {
                            "id": 42,
                            "trackName": "Nightcall",
                            "artistName": "Kavinsky",
                            "albumName": "OutRun",
                            "duration": 258,
                            "instrumental": false,
                            "plainLyrics": "Plain fallback",
                            "syncedLyrics": "[00:30.00]There is something inside you"
                          }
                        ]
                    """.trimIndent()
                    CloudHttpResponse(200, url, emptyMap(), json.toByteArray())
                }
                else -> CloudHttpResponse(500, url, emptyMap(), ByteArray(0))
            }
        }

        val client = LrclibClient(mockHttp, appVersion = "1.2.3")
        val results = client.search(CloudLyricsQuery("Nightcall", "Kavinsky", "OutRun", 258.0))
        assertEquals(1, results.size)
        assertEquals(CloudLyricsProviderId.LRCLIB, results[0].providerId)
        assertTrue(results[0].isTimed)
        assertTrue(mockHttp.requests.all { (_, hdrs) ->
            (hdrs["User-Agent"] ?: "").contains("Musico/1.2.3") &&
                !hdrs.keys.any { it.equals("Lrclib-App-Id", ignoreCase = true) }
        })
    }

    @Test
    fun lrclibRateLimitPropagatesRetryAfter() = runBlocking {
        val mockHttp = MockCloudLyricsHttpClient { _, url, _, _ ->
            CloudHttpResponse(429, url, mapOf("Retry-After" to listOf("15")), ByteArray(0))
        }
        val client = LrclibClient(mockHttp, appVersion = "1.2.3")
        var caught: CloudLyricsException.RateLimited? = null
        try {
            client.search(CloudLyricsQuery("Hello", "Adele"))
        } catch (ex: CloudLyricsException.RateLimited) {
            caught = ex
        }
        assertEquals(15, caught?.retryAfterSeconds)
    }

    @Test
    fun neteaseFallbackSearchAndBilingualMergeWithin50ms() = runBlocking {
        val mockHttp = MockCloudLyricsHttpClient { _, url, headers, _ ->
            assertEquals("https://music.163.com/", headers["Referer"])
            assertEquals("https://music.163.com", headers["Origin"])
            val path = URI(url).path
            when (path) {
                "/api/search/get" -> CloudHttpResponse(200, url, emptyMap(), """{"code":200,"result":{"songs":[]}}""".toByteArray())
                "/api/cloudsearch/pc" -> {
                    val json = """
                        {
                          "code": 200,
                          "result": {
                            "songs": [
                              {
                                "id": 991,
                                "name": "晴天",
                                "ar": [{ "name": "周杰伦" }],
                                "al": { "name": "叶惠美" },
                                "dt": 269000
                              }
                            ]
                          }
                        }
                    """.trimIndent()
                    CloudHttpResponse(200, url, emptyMap(), json.toByteArray())
                }
                "/api/song/lyric" -> {
                    val json = """
                        {
                          "code": 200,
                          "lrc": { "lyric": "[00:28.90]故事的小黄花\n[00:32.10]从出生那年就飘着" },
                          "tlyric": { "lyric": "[00:28.93]Little yellow flower\n[00:32.12]Floating since birth" }
                        }
                    """.trimIndent()
                    CloudHttpResponse(200, url, emptyMap(), json.toByteArray())
                }
                else -> CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
            }
        }

        val client = NeteaseLyricsClient(mockHttp)
        val results = client.search(CloudLyricsQuery("晴天", "周杰伦", null, 269.0))
        assertEquals(1, results.size)
        val hit = results[0]
        assertTrue(hit.isTimed)
        assertTrue(hit.hasTranslation)

        val merged = LrcParser.parse(hit.formattedText(includeTranslation = true)).lines.map { it.text }
        assertEquals(listOf("故事的小黄花", "Little yellow flower", "从出生那年就飘着", "Floating since birth"), merged)

        val plainOnly = LrcParser.parse(hit.formattedText(includeTranslation = false)).lines.map { it.text }
        assertEquals(listOf("故事的小黄花", "从出生那年就飘着"), plainOnly)
    }

    @Test
    fun lyricsExtensionManifestHooksRegistryExclusionAndSourceSwitching() = runBlocking {
        val script = """
            exports.manifest = {
              apiVersion: 1,
              id: "sample.lyrics",
              name: "Sample Lyrics",
              version: "1.0.0",
              badgeLabel: "SampleLrc",
              capabilities: ["lyrics"],
              permissions: { hosts: ["lyrics.example.com"] }
            };

            exports.searchLyrics = async function(ctx, req) {
              var res = await ctx.http.request({
                url: "https://lyrics.example.com/search?title=" + encodeURIComponent(req.title)
              });
              return JSON.parse(res.text).items;
            };
        """.trimIndent()

        val mockHttp = MockCloudLyricsHttpClient { _, url, _, _ ->
            val host = URI(url).host
            when {
                host == "lyrics.example.com" -> {
                    val json = """
                        {
                          "items": [
                            {
                              "externalId": "ext-1",
                              "title": "Sample Song",
                              "artist": "Sample Artist",
                              "durationSec": 200,
                              "syncedLyrics": "[00:05.00]From Extension"
                            }
                          ]
                        }
                    """.trimIndent()
                    CloudHttpResponse(200, url, emptyMap(), json.toByteArray())
                }
                host == "music.163.com" -> CloudHttpResponse(500, url, emptyMap(), ByteArray(0))
                host == "lrclib.net" && URI(url).path == "/api/search" -> {
                    val json = """
                        [
                          {
                            "id": 7,
                            "trackName": "Sample Song",
                            "artistName": "Sample Artist",
                            "duration": 180,
                            "plainLyrics": "Plain fallback"
                          }
                        ]
                    """.trimIndent()
                    CloudHttpResponse(200, url, emptyMap(), json.toByteArray())
                }
                else -> CloudHttpResponse(404, url, emptyMap(), ByteArray(0))
            }
        }

        val stateDir = tempFolder.newFolder("state")
        val registry = CloudProviderRegistry()
        val extStore = ExtensionStore(stateDir, mockHttp, registry, appVersion = "1.2.3")
        val staged = extStore.stageFromBytes(script.toByteArray(), fileNameHint = "sample.musico.js").getOrThrow()
        val installed = extStore.installStaged(staged, enableImmediately = true)
        extStore.setEnabled(installed.id, enabled = true)

        assertTrue(registry.activeExtensionProviders().isEmpty())
        assertEquals(listOf("sample.lyrics"), extStore.activeLyricsExtensions().map { it.id })

        val lyricsStore = LyricsStore(tempFolder.newFolder("lyrics"))
        val service = CloudLyricsService(
            httpClient = mockHttp,
            appVersion = "1.2.3",
            extensionSearch = { id, q -> extStore.searchLyrics(id, q) },
        )
        val query = CloudLyricsQuery("Sample Song", "Sample Artist", null, 200.0)

        val autoResp = service.search(query, CloudLyricsSourceSelection.Auto, extStore.activeLyricsExtensions())
        assertEquals("SampleLrc", autoResp.candidates.first().sourceName)
        assertEquals(2, autoResp.candidates.size)
        assertTrue(autoResp.failures.isNotEmpty())
        assertFalse(lyricsStore.hasImported("track-1"))

        val lrclibOnly = service.search(query, CloudLyricsSourceSelection.Lrclib, extStore.activeLyricsExtensions())
        assertEquals(listOf(CloudLyricsProviderId.LRCLIB), lrclibOnly.candidates.map { it.providerId })

        val extOnly = service.search(query, CloudLyricsSourceSelection.Extension("sample.lyrics"), extStore.activeLyricsExtensions())
        assertEquals(listOf("SampleLrc"), extOnly.candidates.map { it.sourceName })
    }

    @Test
    fun lyricsExtensionReceivesSavedCookieInSearchLyrics() = runBlocking {
        val script = """
            exports.manifest = {
              apiVersion: 1,
              id: "sample.lyrics.auth",
              name: "Sample Auth Lyrics",
              version: "1.0.0",
              badgeLabel: "AuthLrc",
              capabilities: ["lyrics", "cookie"],
              permissions: { hosts: ["lyrics.example.com"] }
            };

            exports.searchLyrics = async function(ctx, req) {
              var headers = {};
              if (req.cookie) {
                headers.Authorization = "Bearer " + req.cookie;
              }
              var res = await ctx.http.request({
                url: "https://lyrics.example.com/search?title=" + encodeURIComponent(req.title),
                headers: headers
              });
              return JSON.parse(res.text).items;
            };
        """.trimIndent()

        val mockHttp = MockCloudLyricsHttpClient { _, url, headers, _ ->
            if (headers["Authorization"] == "Bearer secret-lyrics-token") {
                val json = """
                    {
                      "items": [
                        {
                          "externalId": "auth-1",
                          "title": "21→22",
                          "artist": "あよ",
                          "syncedLyrics": "[00:01.00]Line"
                        }
                      ]
                    }
                """.trimIndent()
                CloudHttpResponse(200, url, emptyMap(), json.toByteArray())
            } else {
                CloudHttpResponse(401, url, emptyMap(), ByteArray(0))
            }
        }

        val stateDir = tempFolder.newFolder("state-auth")
        val extStore = ExtensionStore(stateDir, mockHttp, CloudProviderRegistry(), appVersion = "1.2.3")
        val staged = extStore.stageFromBytes(script.toByteArray(), fileNameHint = "auth.musico.js").getOrThrow()
        val installed = extStore.installStaged(staged, enableImmediately = true)
        extStore.setEnabled(installed.id, enabled = true)
        extStore.updateCookie(installed.id, "secret-lyrics-token")

        val service = CloudLyricsService(
            httpClient = mockHttp,
            appVersion = "1.2.3",
            extensionSearch = { id, q -> extStore.searchLyrics(id, q) },
        )
        val resp = service.search(
            CloudLyricsQuery("21→22", "あよ", null, null),
            CloudLyricsSourceSelection.Extension("sample.lyrics.auth"),
            extStore.activeLyricsExtensions(),
        )
        assertEquals(1, resp.candidates.size)
        assertEquals("Bearer secret-lyrics-token", mockHttp.requests.single().second["Authorization"])
    }
}
