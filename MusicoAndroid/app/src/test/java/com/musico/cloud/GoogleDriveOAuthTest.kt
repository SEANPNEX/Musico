package com.musico.cloud

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

private class InMemoryDriveOAuthStorage(
    private var record: GoogleDriveOAuthRecord = GoogleDriveOAuthRecord(),
) : GoogleDriveOAuthStorage {
    override fun loadRecord(): GoogleDriveOAuthRecord = record
    override fun saveRecord(record: GoogleDriveOAuthRecord) {
        this.record = record
    }
}

private class MockOAuthHttpClient(
    private val getHandler: suspend (url: String, headers: Map<String, String>) -> CloudHttpResponse,
    private val postHandler: suspend (url: String, body: String, headers: Map<String, String>) -> CloudHttpResponse = { url, _, _ ->
        CloudHttpResponse(
            statusCode = 404,
            finalUrl = url,
            headers = emptyMap(),
            bodyBytes = ByteArray(0),
        )
    },
) : CloudHttpClient {
    override suspend fun get(url: String, headers: Map<String, String>, followRedirects: Boolean): CloudHttpResponse =
        getHandler(url, headers)

    override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): CloudHttpResponse =
        postHandler(url, jsonBody, headers)

    override suspend fun probeRange(url: String, headers: Map<String, String>): Int = 206
}

class GoogleDriveOAuthTest {
    @Test
    fun `PKCE auth URL and loopback callback parsing work`() {
        val pkce = GoogleDriveOAuthManager.generatePKCE()
        assertFalse(pkce.codeVerifier.contains("="))
        assertFalse(pkce.codeVerifier.contains("+"))
        assertFalse(pkce.codeVerifier.contains("/"))
        assertFalse(pkce.codeChallenge.contains("="))
        assertTrue(pkce.codeVerifier != pkce.codeChallenge)

        val authUrl = GoogleDriveOAuthManager.buildAuthorizationUrl(
            clientId = "cid-android.apps.googleusercontent.com",
            redirectUri = "http://127.0.0.1:44444/oauth2callback",
            state = "state_abc",
            codeChallenge = pkce.codeChallenge,
        )
        val uri = URI(authUrl)
        assertEquals("accounts.google.com", uri.host)
        assertTrue(uri.rawQuery.contains("client_id=cid-android.apps.googleusercontent.com"))
        assertTrue(uri.rawQuery.contains("code_challenge_method=S256"))
        assertTrue(uri.rawQuery.contains("state=state_abc"))

        val cb = GoogleDriveOAuthManager.parseCallbackRequest(
            "GET /oauth2callback?code=code_99&state=state_abc HTTP/1.1"
        )
        assertNotNull(cb)
        assertEquals("code_99", cb!!.code)
        assertEquals("state_abc", cb.state)
    }

    @Test
    fun `validAccessToken refreshes expired token and preserves refresh token`() = runTest {
        val storage = InMemoryDriveOAuthStorage(
            GoogleDriveOAuthRecord(
                clientId = "cid-1",
                clientSecret = "csec-1",
                accessToken = "old_tok",
                refreshToken = "ref_1",
                expiresAtEpochMs = 1000L,
            )
        )
        val http = MockOAuthHttpClient(
            getHandler = { url, _ ->
                CloudHttpResponse(
                    statusCode = 404,
                    finalUrl = url,
                    headers = emptyMap(),
                    bodyBytes = ByteArray(0),
                )
            },
            postHandler = { url, body, _ ->
                assertEquals(GoogleDriveOAuthManager.TOKEN_ENDPOINT, url)
                assertTrue(body.contains("grant_type=refresh_token"))
                assertTrue(body.contains("refresh_token=ref_1"))
                CloudHttpResponse(
                    statusCode = 200,
                    finalUrl = url,
                    headers = mapOf("Content-Type" to listOf("application/json")),
                    bodyBytes = """{"access_token":"fresh_tok","expires_in":3600}""".toByteArray(),
                )
            }
        )
        val manager = GoogleDriveOAuthManager(storage = storage, httpClient = http)
        val tok = manager.validAccessToken()
        assertEquals("fresh_tok", tok)
        assertEquals("ref_1", storage.loadRecord().refreshToken)
    }

    @Test
    fun `GoogleDriveClient uses OAuth Bearer token and retries once on 401`() = runTest {
        var streamProbeCalls = 0
        val http = MockOAuthHttpClient(
            getHandler = { url, headers ->
                val auth = headers["Authorization"]
                when {
                    url.contains("/drive/v3/files/folder123?") -> {
                        assertEquals("Bearer tok_1", auth)
                        CloudHttpResponse(
                            statusCode = 200,
                            headers = mapOf("Content-Type" to listOf("application/json")),
                            bodyBytes = """{"id":"folder123","name":"Private Drive","mimeType":"application/vnd.google-apps.folder"}""".toByteArray(),
                            finalUrl = url,
                        )
                    }
                    url.contains("/drive/v3/files?") -> {
                        assertEquals("Bearer tok_1", auth)
                        CloudHttpResponse(
                            statusCode = 200,
                            headers = mapOf("Content-Type" to listOf("application/json")),
                            bodyBytes = """{"files":[{"id":"trackXYZ12345","name":"01 Demo.flac","mimeType":"audio/flac"}]}""".toByteArray(),
                            finalUrl = url,
                        )
                    }
                    url.contains("/drive/v3/files/trackXYZ12345?alt=media") -> {
                        streamProbeCalls += 1
                        if (streamProbeCalls == 1) {
                            assertEquals("Bearer tok_1", auth)
                            CloudHttpResponse(
                                statusCode = 401,
                                finalUrl = url,
                                headers = emptyMap(),
                                bodyBytes = ByteArray(0),
                            )
                        } else {
                            assertEquals("Bearer tok_2", auth)
                            CloudHttpResponse(
                                statusCode = 206,
                                headers = mapOf("Content-Type" to listOf("audio/flac")),
                                bodyBytes = byteArrayOf(0x66, 0x4C, 0x61, 0x43),
                                finalUrl = url,
                            )
                        }
                    }
                    else -> error("Unexpected URL: $url")
                }
            }
        )

        val client = GoogleDriveClient(
            httpClient = http,
            oauthTokenProvider = { forceRefresh -> if (forceRefresh) "tok_2" else "tok_1" },
        )

        val folder = client.fetchPlaylist("https://drive.google.com/drive/folders/folder123")
        assertEquals("Private Drive", folder.title)
        assertEquals("01 Demo", folder.tracks.first().title)

        val stream = client.resolveStream(folder.tracks.first().remoteTrackId)
        assertEquals("Bearer tok_2", stream.headers["Authorization"])
        assertEquals("audio/flac", stream.mimeType)
        assertTrue(stream.url.contains("alt=media"))
    }
}
