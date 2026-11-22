package com.musico.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object CloudSource {
    const val YOUTUBE = "youtube"
    const val BILIBILI = "bilibili"
    const val GOOGLE_DRIVE = "gdrive"
}

data class CloudTrackMetadata(
    val remoteTrackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Double,
    val artworkUrl: String? = null,
    val trackNumber: Int? = null,
)

data class CloudPlaylistResult(
    val source: String,
    val remoteId: String,
    val title: String,
    val tracks: List<CloudTrackMetadata>,
)

data class ResolvedStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val expiresAtEpochMs: Long = 0L,
    val contentLength: Long? = null,
)

data class CloudHttpResponse(
    val statusCode: Int,
    val finalUrl: String,
    val headers: Map<String, List<String>>,
    val bodyBytes: ByteArray,
) {
    val bodyText: String
        get() = bodyBytes.toString(Charsets.UTF_8)
}

interface CloudHttpClient {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): CloudHttpResponse

    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
    ): CloudHttpResponse

    suspend fun probeRange(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Int
}

class UrlConnectionCloudHttpClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) : CloudHttpClient {

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        followRedirects: Boolean,
    ): CloudHttpResponse = withContext(Dispatchers.IO) {
        executeWithRedirects(
            initialUrl = url,
            method = "GET",
            headers = headers,
            jsonBody = null,
            followRedirects = followRedirects,
        )
    }

    override suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String>,
    ): CloudHttpResponse = withContext(Dispatchers.IO) {
        executeWithRedirects(
            initialUrl = url,
            method = "POST",
            headers = headers,
            jsonBody = jsonBody,
            followRedirects = true,
        )
    }

    override suspend fun probeRange(
        url: String,
        headers: Map<String, String>,
    ): Int = withContext(Dispatchers.IO) {
        var currentUrl = url
        val probeHeaders = headers.toMutableMap()
        if (!probeHeaders.containsKey("Range")) {
            probeHeaders["Range"] = "bytes=0-"
        }
        for (hop in 0 until MAX_REDIRECTS) {
            val conn = openConnection(currentUrl, "GET", probeHeaders)
            try {
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                if (isRedirect(code)) {
                    val location = conn.getHeaderField("Location") ?: return@withContext code
                    currentUrl = URL(URL(currentUrl), location).toString()
                    continue
                }
                return@withContext code
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects probing $url")
    }

    private fun executeWithRedirects(
        initialUrl: String,
        method: String,
        headers: Map<String, String>,
        jsonBody: String?,
        followRedirects: Boolean,
    ): CloudHttpResponse {
        var currentUrl = initialUrl
        var currentMethod = method
        var currentBody = jsonBody

        for (hop in 0 until MAX_REDIRECTS) {
            val conn = openConnection(currentUrl, currentMethod, headers)
            try {
                conn.instanceFollowRedirects = false
                if (currentBody != null) {
                    conn.doOutput = true
                    if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                    val bytes = currentBody.toByteArray(Charsets.UTF_8)
                    conn.outputStream.use { it.write(bytes) }
                }

                val code = conn.responseCode
                if (followRedirects && isRedirect(code)) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = URL(URL(currentUrl), location).toString()
                        if (code == 301 || code == 302 || code == 303) {
                            currentMethod = "GET"
                            currentBody = null
                        }
                        continue
                    }
                }

                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                val body = stream?.use { readAllBytes(it) } ?: ByteArray(0)
                val responseHeaders = linkedMapOf<String, List<String>>()
                for ((k, v) in conn.headerFields) {
                    if (k != null && v != null) {
                        responseHeaders[k] = v
                    }
                }
                return CloudHttpResponse(
                    statusCode = code,
                    finalUrl = conn.url.toString(),
                    headers = responseHeaders,
                    bodyBytes = body,
                )
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects for $initialUrl")
    }

    private fun openConnection(
        url: String,
        method: String,
        headers: Map<String, String>,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        for ((k, v) in headers) {
            conn.setRequestProperty(k, v)
        }
        return conn
    }

    private fun isRedirect(code: Int): Boolean =
        code == HttpURLConnection.HTTP_MOVED_PERM ||
            code == HttpURLConnection.HTTP_MOVED_TEMP ||
            code == HttpURLConnection.HTTP_SEE_OTHER ||
            code == 307 ||
            code == 308

    private fun readAllBytes(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_REDIRECTS = 6
    }
}

internal fun normalizeHttpsUrl(url: String?): String? {
    val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("http://", ignoreCase = true) -> "https://${trimmed.substring(7)}"
        else -> trimmed
    }
}

private val CLOUD_HTTP_URL_REGEX = Regex(
    "https?://[A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
    RegexOption.IGNORE_CASE,
)

private val CLOUD_URL_TRAILING_PUNCTUATION = setOf(
    ')', ']', '】', '」', '》', '。', ',', '.', '\'', '"',
    '）', '］', '，', ';', '!',
)

/**
 * Extracts an `http(s)://...` link from pasted share text (e.g. title + URL + trailing
 * punctuation), stripping surrounding text and trailing punctuation while preserving
 * bare playlist/video IDs (`PL...`, `BV...`, `av...`, `ml...`).
 */
fun extractCloudSourceInput(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val matches = CLOUD_HTTP_URL_REGEX.findAll(trimmed)
        .map { match -> match.value.trimEnd { it in CLOUD_URL_TRAILING_PUNCTUATION } }
        .filter { it.isNotEmpty() }
        .toList()
    if (matches.isEmpty()) return trimmed
    return matches.firstOrNull { url ->
        val lower = url.lowercase()
        lower.contains("youtube") || lower.contains("youtu.be") ||
            lower.contains("bilibili") || lower.contains("b23.tv") ||
            lower.contains("drive.google.com") || lower.contains("drive.usercontent.google.com")
    } ?: matches.first()
}

internal fun parseClockDurationSec(text: String?): Double {
    val clean = text?.trim()?.takeIf { it.isNotEmpty() } ?: return 0.0
    val parts = clean.split(':').mapNotNull { it.trim().toDoubleOrNull() }
    return when (parts.size) {
        2 -> parts[0] * 60.0 + parts[1]
        3 -> parts[0] * 3600.0 + parts[1] * 60.0 + parts[2]
        else -> 0.0
    }
}

internal val cloudJson = Json { ignoreUnknownKeys = true }

internal fun String.parseJsonObject(): JsonObject =
    cloudJson.parseToJsonElement(this).jsonObject

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.double(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonArray.objAt(index: Int): JsonObject? =
    getOrNull(index) as? JsonObject

