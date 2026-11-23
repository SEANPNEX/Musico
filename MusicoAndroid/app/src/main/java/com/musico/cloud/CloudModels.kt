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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

object CloudSource {
    const val YOUTUBE = "youtube"
    const val BILIBILI = "bilibili"
    const val GOOGLE_DRIVE = "gdrive"
}

enum class CloudErrorCode {
    INVALID_URL,
    AUTH_REQUIRED,
    EMPTY_COLLECTION,
    NO_PLAYABLE_STREAM,
    RATE_LIMITED,
    NETWORK_ERROR,
    PERMISSION_DENIED,
    EXTENSION_UNAVAILABLE,
    EXTENSION_RUNTIME_ERROR,
    ;

    companion object {
        fun fromCodeString(raw: String?): CloudErrorCode? =
            raw?.trim()?.uppercase()?.let { upper ->
                entries.firstOrNull { it.name == upper }
            }
    }
}

class CloudSourceException(
    val code: CloudErrorCode,
    override val message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class CloudTrackMetadata(
    val remoteTrackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Double,
    val artworkUrl: String? = null,
    val trackNumber: Int? = null,
    val relativePath: String? = null,
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
    val fileExtension: String? = null,
    val fallbackUrls: List<String> = emptyList(),
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

data class CloudHttpRequest(
    val method: String = "GET",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val followRedirects: Boolean = true,
    val timeoutMs: Int = 15_000,
    val returnBytes: Boolean = false,
)

data class CloudHttpGenericResponse(
    val status: Int,
    val finalUrl: String,
    val headers: Map<String, String>,
    val text: String,
    val bytesBase64: String? = null,
)

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

    /**
     * Executes a generic HTTP request with manual redirect handling, optional per-hop
     * [urlValidator] enforcement, cross-host Cookie/Authorization stripping, an 8 MB body cap,
     * lowercase response header keys, and conditional `bytesBase64`.
     */
    suspend fun request(
        request: CloudHttpRequest,
        urlValidator: ((String) -> Unit)? = null,
    ): CloudHttpGenericResponse = executeDefaultGenericRequest(request, urlValidator)
}

private suspend fun CloudHttpClient.executeDefaultGenericRequest(
    request: CloudHttpRequest,
    urlValidator: ((String) -> Unit)?,
): CloudHttpGenericResponse {
    var currentUrl = request.url
    var currentMethod = request.method.trim().uppercase().ifEmpty { "GET" }
    var currentBody = request.body
    val currentHeaders = LinkedHashMap<String, String>(request.headers)

    for (hop in 0..CLOUD_HTTP_MAX_EXTENSION_REDIRECTS) {
        urlValidator?.invoke(currentUrl)
        val resp = if (currentMethod == "POST" || (currentMethod != "GET" && currentBody != null)) {
            postJson(currentUrl, currentBody.orEmpty(), currentHeaders.toMap())
        } else {
            get(currentUrl, currentHeaders.toMap(), followRedirects = false)
        }
        val lowerHeaders = normalizeResponseHeaders(resp.headers)
        if (request.followRedirects && isRedirectStatus(resp.statusCode)) {
            val location = lowerHeaders["location"]?.takeIf { it.isNotBlank() }
            if (location != null) {
                val nextUrl = URL(URL(currentUrl), location).toString()
                urlValidator?.invoke(nextUrl)
                stripSensitiveHeadersIfCrossHost(currentUrl, nextUrl, currentHeaders)
                if (resp.statusCode == 301 || resp.statusCode == 302 || resp.statusCode == 303) {
                    currentMethod = "GET"
                    currentBody = null
                }
                currentUrl = nextUrl
                continue
            }
        }
        val effectiveFinalUrl = resp.finalUrl.takeIf { it.isNotBlank() } ?: currentUrl
        if (effectiveFinalUrl != currentUrl) {
            urlValidator?.invoke(effectiveFinalUrl)
        }
        return toGenericResponse(
            status = resp.statusCode,
            finalUrl = effectiveFinalUrl,
            headers = lowerHeaders,
            rawBytes = resp.bodyBytes,
            returnBytes = request.returnBytes,
        )
    }
    throw CloudSourceException(CloudErrorCode.NETWORK_ERROR, "Too many redirects for ${request.url}")
}

internal const val CLOUD_HTTP_MAX_EXTENSION_REDIRECTS = 10
internal const val CLOUD_HTTP_MAX_BODY_BYTES = 8 * 1024 * 1024
internal const val CLOUD_HTTP_AUTO_BASE64_THRESHOLD_BYTES = 1024 * 1024

internal fun isRedirectStatus(code: Int): Boolean =
    code == HttpURLConnection.HTTP_MOVED_PERM ||
        code == HttpURLConnection.HTTP_MOVED_TEMP ||
        code == HttpURLConnection.HTTP_SEE_OTHER ||
        code == 307 ||
        code == 308

internal fun normalizeResponseHeaders(headers: Map<String, List<String>>): Map<String, String> {
    val normalized = linkedMapOf<String, String>()
    for ((k, values) in headers) {
        if (k.isNotEmpty() && values.isNotEmpty()) {
            normalized[k.lowercase()] = values.joinToString(", ")
        }
    }
    return normalized
}

internal fun stripSensitiveHeadersIfCrossHost(
    previousUrl: String,
    nextUrl: String,
    headers: MutableMap<String, String>,
) {
    val prevHost = runCatching { URL(previousUrl).host.lowercase() }.getOrNull()
    val nextHost = runCatching { URL(nextUrl).host.lowercase() }.getOrNull()
    if (prevHost != null && nextHost != null && prevHost != nextHost) {
        val iterator = headers.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key.equals("Cookie", ignoreCase = true) || key.equals("Authorization", ignoreCase = true)) {
                iterator.remove()
            }
        }
    }
}

internal fun toGenericResponse(
    status: Int,
    finalUrl: String,
    headers: Map<String, String>,
    rawBytes: ByteArray,
    returnBytes: Boolean,
): CloudHttpGenericResponse {
    val capped = if (rawBytes.size > CLOUD_HTTP_MAX_BODY_BYTES) {
        rawBytes.copyOf(CLOUD_HTTP_MAX_BODY_BYTES)
    } else {
        rawBytes
    }
    val includeBase64 = returnBytes || capped.size <= CLOUD_HTTP_AUTO_BASE64_THRESHOLD_BYTES
    val b64 = if (includeBase64) Base64.getEncoder().encodeToString(capped) else null
    return CloudHttpGenericResponse(
        status = status,
        finalUrl = finalUrl,
        headers = headers,
        text = capped.toString(Charsets.UTF_8),
        bytesBase64 = b64,
    )
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
            body = null,
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
            body = jsonBody,
            followRedirects = true,
        )
    }

    override suspend fun request(
        request: CloudHttpRequest,
        urlValidator: ((String) -> Unit)?,
    ): CloudHttpGenericResponse = withContext(Dispatchers.IO) {
        val method = request.method.trim().uppercase().ifEmpty { "GET" }
        val timeoutOverride = request.timeoutMs.takeIf { it > 0 }?.coerceIn(100, 60_000)
        val resp = executeWithRedirects(
            initialUrl = request.url,
            method = method,
            headers = request.headers,
            body = request.body,
            followRedirects = request.followRedirects,
            maxRedirects = CLOUD_HTTP_MAX_EXTENSION_REDIRECTS,
            timeoutOverrideMs = timeoutOverride,
            maxBodyBytes = CLOUD_HTTP_MAX_BODY_BYTES,
            urlValidator = urlValidator,
            stripSensitiveHeadersOnCrossHostRedirect = true,
            defaultJsonContentType = false,
        )
        toGenericResponse(
            status = resp.statusCode,
            finalUrl = resp.finalUrl,
            headers = normalizeResponseHeaders(resp.headers),
            rawBytes = resp.bodyBytes,
            returnBytes = request.returnBytes,
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
                if (isRedirectStatus(code)) {
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
        body: String?,
        followRedirects: Boolean,
        maxRedirects: Int = MAX_REDIRECTS - 1,
        timeoutOverrideMs: Int? = null,
        maxBodyBytes: Int? = null,
        urlValidator: ((String) -> Unit)? = null,
        stripSensitiveHeadersOnCrossHostRedirect: Boolean = false,
        defaultJsonContentType: Boolean = true,
    ): CloudHttpResponse {
        var currentUrl = initialUrl
        var currentMethod = method
        var currentBody = body
        val currentHeaders = LinkedHashMap<String, String>(headers)

        for (hop in 0..maxRedirects) {
            urlValidator?.invoke(currentUrl)
            val conn = openConnection(currentUrl, currentMethod, currentHeaders, timeoutOverrideMs)
            try {
                conn.instanceFollowRedirects = false
                if (currentBody != null && currentMethod != "GET" && currentMethod != "HEAD") {
                    conn.doOutput = true
                    if (defaultJsonContentType &&
                        currentHeaders.keys.none { it.equals("Content-Type", ignoreCase = true) }
                    ) {
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                    val bytes = currentBody.toByteArray(Charsets.UTF_8)
                    conn.outputStream.use { it.write(bytes) }
                }

                val code = conn.responseCode
                if (followRedirects && isRedirectStatus(code)) {
                    val location = conn.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        val nextUrl = URL(URL(currentUrl), location).toString()
                        urlValidator?.invoke(nextUrl)
                        if (stripSensitiveHeadersOnCrossHostRedirect) {
                            stripSensitiveHeadersIfCrossHost(currentUrl, nextUrl, currentHeaders)
                        }
                        if (code == 301 || code == 302 || code == 303) {
                            currentMethod = "GET"
                            currentBody = null
                        }
                        currentUrl = nextUrl
                        continue
                    }
                }

                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                val rawBody = stream?.use { readAllBytes(it, maxBodyBytes) } ?: ByteArray(0)
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
                    bodyBytes = rawBody,
                )
            } finally {
                conn.disconnect()
            }
        }
        throw CloudSourceException(CloudErrorCode.NETWORK_ERROR, "Too many redirects for $initialUrl")
    }

    private fun openConnection(
        url: String,
        method: String,
        headers: Map<String, String>,
        timeoutOverrideMs: Int? = null,
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = timeoutOverrideMs ?: connectTimeoutMs
        conn.readTimeout = timeoutOverrideMs ?: readTimeoutMs
        conn.useCaches = false
        for ((k, v) in headers) {
            conn.setRequestProperty(k, v)
        }
        return conn
    }

    private fun readAllBytes(input: InputStream, maxBytes: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var remaining = maxBytes ?: Int.MAX_VALUE
        while (remaining > 0) {
            val toRead = minOf(buf.size, remaining)
            val n = input.read(buf, 0, toRead)
            if (n < 0) break
            out.write(buf, 0, n)
            if (maxBytes != null) {
                remaining -= n
            }
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

