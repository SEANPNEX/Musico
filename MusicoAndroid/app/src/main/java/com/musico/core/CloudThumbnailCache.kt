package com.musico.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import com.musico.cloud.CloudHttpClient
import com.musico.cloud.UrlConnectionCloudHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * In-memory LRU thumbnail loader (`https://` URLs only) for cloud search and preview rows.
 * Never writes browsed thumbnails to disk [ArtworkCache].
 */
class CloudThumbnailCache(
    private val httpClient: CloudHttpClient = UrlConnectionCloudHttpClient(),
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val bitmapDecoder: (ByteArray) -> Bitmap? = ::decodeThumbnailBitmap,
) {
    private val memory = object : LruCache<String, Bitmap>(maxEntries.coerceAtLeast(1)) {}
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    fun peek(url: String?): Bitmap? {
        val clean = normalizeHttpsUrlOrNull(url) ?: return null
        return memory.get(clean)
    }

    fun clear() {
        memory.evictAll()
    }

    suspend fun load(
        url: String?,
        headers: Map<String, String> = emptyMap(),
    ): Bitmap? {
        val cleanUrl = normalizeHttpsUrlOrNull(url) ?: return null
        memory.get(cleanUrl)?.let { return it }

        val existing = inFlight[cleanUrl]
        if (existing != null) {
            return existing.await()
        }

        val deferred = CompletableDeferred<Bitmap?>()
        val raced = inFlight.putIfAbsent(cleanUrl, deferred)
        if (raced != null) {
            return raced.await()
        }

        return try {
            val loaded = withContext(Dispatchers.IO) {
                fetchAndDecode(cleanUrl, headers)
            }
            if (loaded != null) {
                memory.put(cleanUrl, loaded)
            }
            deferred.complete(loaded)
            loaded
        } catch (cancel: CancellationException) {
            deferred.cancel(cancel)
            throw cancel
        } catch (_: Exception) {
            deferred.complete(null)
            null
        } finally {
            inFlight.remove(cleanUrl, deferred)
        }
    }

    private suspend fun fetchAndDecode(
        initialUrl: String,
        headers: Map<String, String>,
    ): Bitmap? {
        var currentUrl = initialUrl
        for (hop in 0..MAX_REDIRECTS) {
            if (!isAllowedUrl(currentUrl)) return null
            val resp = httpClient.get(
                url = currentUrl,
                headers = headers,
                followRedirects = false,
            )
            if (resp.statusCode in REDIRECT_CODES) {
                val location = resp.headers.entries
                    .firstOrNull { it.key.equals("Location", ignoreCase = true) }
                    ?.value
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return null
                val nextUrl = runCatching { URL(URL(currentUrl), location).toString() }.getOrNull()
                    ?: return null
                if (!isAllowedUrl(nextUrl)) return null
                currentUrl = nextUrl
                continue
            }
            if (resp.statusCode !in 200..299 || resp.bodyBytes.isEmpty()) {
                return null
            }
            val bytes = if (resp.bodyBytes.size > MAX_IMAGE_BYTES) {
                return null
            } else {
                resp.bodyBytes
            }
            return bitmapDecoder(bytes)
        }
        return null
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 120
        const val MAX_DIMENSION = 320
        private const val MAX_REDIRECTS = 5
        private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        fun isAllowedUrl(rawUrl: String?): Boolean = normalizeHttpsUrlOrNull(rawUrl) != null

        fun normalizeHttpsUrlOrNull(rawUrl: String?): String? {
            val trimmed = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val parsed = runCatching { URL(trimmed) }.getOrNull() ?: return null
            if (!parsed.protocol.equals("https", ignoreCase = true)) return null
            if (parsed.host.isNullOrBlank()) return null
            return trimmed
        }

        internal fun decodeThumbnailBitmap(bytes: ByteArray): Bitmap? {
            if (bytes.isEmpty()) return null
            val imageBytes = EmbeddedArtworkExtractor.normalizeImageBytes(bytes) ?: bytes
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight

            var decoded: Bitmap? = null
            if (rawW > 0 && rawH > 0) {
                var sampleSize = 1
                while (max(rawW / sampleSize, rawH / sampleSize) > MAX_DIMENSION * 2) {
                    sampleSize *= 2
                }
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOpts)
            }
            if (decoded == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                decoded = decodeWithImageDecoder(imageBytes)
            }
            val bitmap = decoded ?: return null
            val longest = max(bitmap.width, bitmap.height)
            return if (longest > MAX_DIMENSION) {
                val scale = MAX_DIMENSION.toFloat() / longest.toFloat()
                val targetW = max(1, (bitmap.width * scale).roundToInt())
                val targetH = max(1, (bitmap.height * scale).roundToInt())
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }
        }

        @RequiresApi(Build.VERSION_CODES.P)
        private fun decodeWithImageDecoder(bytes: ByteArray): Bitmap? {
            return runCatching {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val longest = max(w, h)
                    if (longest > MAX_DIMENSION) {
                        val scale = MAX_DIMENSION.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            max(1, (w * scale).roundToInt()),
                            max(1, (h * scale).roundToInt()),
                        )
                    }
                }
            }.getOrNull()
        }
    }
}
