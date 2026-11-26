package com.musico.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

@Serializable
data class GoogleDriveOAuthRecord(
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtEpochMs: Long = 0L,
    val accountEmail: String? = null,
)

data class GoogleDrivePkcePair(
    val codeVerifier: String,
    val codeChallenge: String,
)

data class GoogleDriveOAuthCallback(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
)

data class GoogleDriveTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Double,
)

data class GoogleDriveOAuthUiState(
    val clientId: String = "",
    val clientSecret: String = "",
    val isSignedIn: Boolean = false,
    val accountEmail: String? = null,
    val isSigningIn: Boolean = false,
    val errorMessage: String? = null,
)

interface GoogleDriveOAuthStorage {
    fun loadRecord(): GoogleDriveOAuthRecord
    fun saveRecord(record: GoogleDriveOAuthRecord)
}

class SharedPrefsGoogleDriveOAuthStorage(context: Context) : GoogleDriveOAuthStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun loadRecord(): GoogleDriveOAuthRecord = synchronized(lock) {
        val raw = prefs.getString(KEY_RECORD, null)?.takeIf { it.isNotBlank() }
            ?: return@synchronized GoogleDriveOAuthRecord()
        runCatching { jsonCodec.decodeFromString<GoogleDriveOAuthRecord>(raw) }
            .getOrDefault(GoogleDriveOAuthRecord())
    }

    override fun saveRecord(record: GoogleDriveOAuthRecord) = synchronized(lock) {
        prefs.edit().putString(KEY_RECORD, jsonCodec.encodeToString(record)).apply()
    }

    companion object {
        private const val PREFS_NAME = "musico_gdrive_oauth"
        private const val KEY_RECORD = "oauth_record_v1"
        private val jsonCodec = Json { ignoreUnknownKeys = true }
    }
}

class GoogleDriveOAuthException(
    message: String,
    val isRevokedOrInvalid: Boolean = false,
) : Exception(message)

class GoogleDriveOAuthManager(
    private val storage: GoogleDriveOAuthStorage,
    private val httpClient: CloudHttpClient = UrlConnectionCloudHttpClient(),
) {
    constructor(context: Context, httpClient: CloudHttpClient = UrlConnectionCloudHttpClient()) : this(
        storage = SharedPrefsGoogleDriveOAuthStorage(context),
        httpClient = httpClient,
    )

    private val mutex = Mutex()
    private var currentRecord: GoogleDriveOAuthRecord = storage.loadRecord()

    private val _state = MutableStateFlow(toUiState(currentRecord, isSigningIn = false, errorMessage = null))
    val state: StateFlow<GoogleDriveOAuthUiState> = _state.asStateFlow()

    fun updateClientCredentials(clientId: String, clientSecret: String) {
        val cleanId = clientId.trim()
        val cleanSecret = clientSecret.trim()
        currentRecord = currentRecord.copy(clientId = cleanId, clientSecret = cleanSecret)
        storage.saveRecord(currentRecord)
        _state.value = toUiState(currentRecord, isSigningIn = _state.value.isSigningIn, errorMessage = _state.value.errorMessage)
    }

    fun signOut() {
        currentRecord = currentRecord.copy(
            accessToken = "",
            refreshToken = "",
            expiresAtEpochMs = 0L,
            accountEmail = null,
        )
        storage.saveRecord(currentRecord)
        _state.value = toUiState(currentRecord, isSigningIn = false, errorMessage = null)
    }

    suspend fun validAccessToken(forceRefresh: Boolean = false): String? = mutex.withLock {
        val nowMs = System.currentTimeMillis()
        if (!forceRefresh &&
            currentRecord.accessToken.isNotBlank() &&
            currentRecord.expiresAtEpochMs > nowMs + 60_000L
        ) {
            return@withLock currentRecord.accessToken
        }

        if (currentRecord.refreshToken.isBlank() || currentRecord.clientId.isBlank()) {
            if (currentRecord.accessToken.isNotBlank() && currentRecord.expiresAtEpochMs > nowMs + 5_000L) {
                return@withLock currentRecord.accessToken
            }
            return@withLock null
        }

        try {
            val refreshed = refreshAccessToken(
                clientId = currentRecord.clientId,
                clientSecret = currentRecord.clientSecret.takeIf { it.isNotBlank() },
                refreshToken = currentRecord.refreshToken,
                httpClient = httpClient,
            )
            currentRecord = currentRecord.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken?.takeIf { it.isNotBlank() } ?: currentRecord.refreshToken,
                expiresAtEpochMs = System.currentTimeMillis() + (refreshed.expiresInSeconds.coerceAtLeast(60.0) * 1000.0).toLong(),
            )
            storage.saveRecord(currentRecord)
            _state.value = toUiState(currentRecord, isSigningIn = _state.value.isSigningIn, errorMessage = null)
            currentRecord.accessToken
        } catch (err: GoogleDriveOAuthException) {
            if (err.isRevokedOrInvalid) {
                signOut()
            }
            throw err
        }
    }

    suspend fun startSignIn(context: Context) {
        val cleanClientId = currentRecord.clientId.trim()
        val cleanSecret = currentRecord.clientSecret.trim()
        if (cleanClientId.isEmpty()) {
            _state.value = toUiState(
                currentRecord,
                isSigningIn = false,
                errorMessage = "Enter a Google Cloud OAuth Client ID first.",
            )
            return
        }

        _state.value = toUiState(currentRecord, isSigningIn = true, errorMessage = null)
        try {
            val pkce = generatePKCE()
            val expectedState = randomBase64Url(24)

            val callback = withContext(Dispatchers.IO) {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 180_000
                    val redirectUri = "http://127.0.0.1:${server.localPort}/oauth2callback"
                    val authUrl = buildAuthorizationUrl(
                        clientId = cleanClientId,
                        redirectUri = redirectUri,
                        state = expectedState,
                        codeChallenge = pkce.codeChallenge,
                    )

                    withContext(Dispatchers.Main) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK,
                        )
                        context.startActivity(intent)
                    }

                    var matchedCallback: GoogleDriveOAuthCallback? = null
                    while (matchedCallback == null) {
                        server.accept().use { socket ->
                            socket.soTimeout = 5_000
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                            val requestLine = reader.readLine().orEmpty()
                            val parsed = parseCallbackRequest(requestLine)
                            val html = buildSuccessHtmlResponse()
                            socket.getOutputStream().write(html.toByteArray(StandardCharsets.UTF_8))
                            socket.getOutputStream().flush()
                            if (parsed != null && (parsed.code != null || parsed.error != null)) {
                                matchedCallback = parsed
                            }
                        }
                    }
                    redirectUri to matchedCallback!!
                }
            }

            val (redirectUri, parsedCallback) = callback
            if (!parsedCallback.error.isNullOrBlank()) {
                throw GoogleDriveOAuthException(parsedCallback.errorDescription ?: parsedCallback.error)
            }
            if (parsedCallback.state != expectedState) {
                throw GoogleDriveOAuthException("OAuth state validation failed.")
            }
            val code = parsedCallback.code?.takeIf { it.isNotBlank() }
                ?: throw GoogleDriveOAuthException("Google sign-in was cancelled.")

            val tokens = exchangeAuthorizationCode(
                clientId = cleanClientId,
                clientSecret = cleanSecret.takeIf { it.isNotBlank() },
                code = code,
                codeVerifier = pkce.codeVerifier,
                redirectUri = redirectUri,
                httpClient = httpClient,
            )
            val email = fetchUserEmail(tokens.accessToken, httpClient)
            mutex.withLock {
                currentRecord = currentRecord.copy(
                    clientId = cleanClientId,
                    clientSecret = cleanSecret,
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken?.takeIf { it.isNotBlank() } ?: currentRecord.refreshToken,
                    expiresAtEpochMs = System.currentTimeMillis() + (tokens.expiresInSeconds.coerceAtLeast(60.0) * 1000.0).toLong(),
                    accountEmail = email,
                )
                storage.saveRecord(currentRecord)
            }
            _state.value = toUiState(currentRecord, isSigningIn = false, errorMessage = null)
        } catch (err: CancellationException) {
            _state.value = toUiState(currentRecord, isSigningIn = false, errorMessage = null)
            throw err
        } catch (err: Throwable) {
            _state.value = toUiState(
                currentRecord,
                isSigningIn = false,
                errorMessage = err.message ?: "Google Drive sign-in failed.",
            )
        }
    }

    private fun toUiState(
        record: GoogleDriveOAuthRecord,
        isSigningIn: Boolean,
        errorMessage: String?,
    ): GoogleDriveOAuthUiState = GoogleDriveOAuthUiState(
        clientId = record.clientId,
        clientSecret = record.clientSecret,
        isSignedIn = record.accessToken.isNotBlank() || record.refreshToken.isNotBlank(),
        accountEmail = record.accountEmail,
        isSigningIn = isSigningIn,
        errorMessage = errorMessage,
    )

    companion object {
        const val DRIVE_READONLY_SCOPE =
            "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/userinfo.email"
        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val USERINFO_ENDPOINT = "https://www.googleapis.com/oauth2/v3/userinfo"

        private val secureRandom = SecureRandom()
        private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
        private val jsonParser = Json { ignoreUnknownKeys = true }

        fun randomBase64Url(byteCount: Int): String {
            val bytes = ByteArray(byteCount.coerceAtLeast(16))
            secureRandom.nextBytes(bytes)
            return urlEncoder.encodeToString(bytes)
        }

        fun generatePKCE(): GoogleDrivePkcePair {
            val verifier = randomBase64Url(32)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.UTF_8))
            val challenge = urlEncoder.encodeToString(digest)
            return GoogleDrivePkcePair(codeVerifier = verifier, codeChallenge = challenge)
        }

        fun buildAuthorizationUrl(
            clientId: String,
            redirectUri: String,
            state: String,
            codeChallenge: String,
        ): String {
            val params = listOf(
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "response_type" to "code",
                "scope" to DRIVE_READONLY_SCOPE,
                "access_type" to "offline",
                "prompt" to "consent",
                "code_challenge_method" to "S256",
                "code_challenge" to codeChallenge,
                "state" to state,
            )
            val qs = params.joinToString("&") { (k, v) ->
                "${encodeQuery(k)}=${encodeQuery(v)}"
            }
            return "$AUTH_ENDPOINT?$qs"
        }

        fun parseCallbackRequest(rawHttpRequest: String): GoogleDriveOAuthCallback? {
            val firstLine = rawHttpRequest.lineSequence().firstOrNull()?.trim().orEmpty()
            val parts = firstLine.split(' ')
            if (parts.size < 2) return null
            val uri = runCatching { URI("http://127.0.0.1${parts[1]}") }.getOrNull() ?: return null
            val query = LinkedHashMap<String, String>()
            val rawQuery = uri.rawQuery.orEmpty()
            if (rawQuery.isNotEmpty()) {
                for (pair in rawQuery.split('&')) {
                    if (pair.isEmpty()) continue
                    val eq = pair.indexOf('=')
                    val key = if (eq >= 0) decodeQuery(pair.substring(0, eq)) else decodeQuery(pair)
                    val valStr = if (eq >= 0) decodeQuery(pair.substring(eq + 1)) else ""
                    if (key.isNotEmpty()) query[key] = valStr
                }
            }
            return GoogleDriveOAuthCallback(
                code = query["code"],
                state = query["state"],
                error = query["error"],
                errorDescription = query["error_description"],
            )
        }

        suspend fun exchangeAuthorizationCode(
            clientId: String,
            clientSecret: String?,
            code: String,
            codeVerifier: String,
            redirectUri: String,
            httpClient: CloudHttpClient,
        ): GoogleDriveTokenResponse {
            val params = mutableListOf(
                "grant_type" to "authorization_code",
                "client_id" to clientId,
                "code" to code,
                "code_verifier" to codeVerifier,
                "redirect_uri" to redirectUri,
            )
            if (!clientSecret.isNullOrBlank()) {
                params.add("client_secret" to clientSecret)
            }
            return postTokenRequest(params, httpClient)
        }

        suspend fun refreshAccessToken(
            clientId: String,
            clientSecret: String?,
            refreshToken: String,
            httpClient: CloudHttpClient,
        ): GoogleDriveTokenResponse {
            val params = mutableListOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refreshToken,
            )
            if (!clientSecret.isNullOrBlank()) {
                params.add("client_secret" to clientSecret)
            }
            return postTokenRequest(params, httpClient)
        }

        private suspend fun postTokenRequest(
            params: List<Pair<String, String>>,
            httpClient: CloudHttpClient,
        ): GoogleDriveTokenResponse {
            val body = params.joinToString("&") { (k, v) -> "${encodeQuery(k)}=${encodeQuery(v)}" }
            val resp = httpClient.request(
                CloudHttpRequest(
                    method = "POST",
                    url = TOKEN_ENDPOINT,
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    body = body,
                    followRedirects = true,
                )
            )
            val json = runCatching { jsonParser.parseToJsonElement(resp.text).jsonObject }.getOrNull()
            val errCode = json?.get("error")?.jsonPrimitive?.contentOrNull
            if (!errCode.isNullOrBlank()) {
                val desc = json["error_description"]?.jsonPrimitive?.contentOrNull ?: errCode
                val revoked = errCode == "invalid_grant" || errCode == "unauthorized_client" || resp.status == 401
                throw GoogleDriveOAuthException(desc, isRevokedOrInvalid = revoked)
            }
            val accessToken = json?.get("access_token")?.jsonPrimitive?.contentOrNull
            if (resp.status !in 200..299 || accessToken.isNullOrBlank()) {
                throw GoogleDriveOAuthException("Google OAuth token request failed (HTTP ${resp.status}).")
            }
            val refreshToken = json["refresh_token"]?.jsonPrimitive?.contentOrNull
            val expiresIn = json["expires_in"]?.jsonPrimitive?.doubleOrNull ?: 3600.0
            return GoogleDriveTokenResponse(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresInSeconds = expiresIn,
            )
        }

        suspend fun fetchUserEmail(
            accessToken: String,
            httpClient: CloudHttpClient,
        ): String? = runCatching {
            val resp = httpClient.get(
                url = USERINFO_ENDPOINT,
                headers = mapOf("Authorization" to "Bearer $accessToken"),
                followRedirects = true,
            )
            if (resp.statusCode !in 200..299) return@runCatching null
            val json = jsonParser.parseToJsonElement(resp.bodyText).jsonObject
            json["email"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }.getOrNull()

        private fun encodeQuery(raw: String): String =
            URLEncoder.encode(raw, StandardCharsets.UTF_8.name()).replace("+", "%20")

        private fun decodeQuery(raw: String): String =
            runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrDefault(raw)

        private fun buildSuccessHtmlResponse(): String {
            val html = """
                <!doctype html><html><body style="font-family:sans-serif;text-align:center;padding:48px;">
                <h2>Musico</h2><p>Google Drive sign-in complete. You can close this tab and return to Musico.</p>
                </body></html>
            """.trimIndent()
            val bytes = html.toByteArray(StandardCharsets.UTF_8)
            return "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$html"
        }
    }
}
