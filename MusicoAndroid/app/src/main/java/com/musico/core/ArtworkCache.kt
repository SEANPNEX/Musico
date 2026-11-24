package com.musico.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Cover art on disk (downscaled up to 600px JPEG per distinct image),
 * backed by an in-memory [LruCache].
 */
class ArtworkCache(
    private val directory: File,
) {
    private val memory = object : LruCache<String, Bitmap>(120) {}

    private fun targetFile(key: String): File = File(directory, "$key.jpg")

    /**
     * Stores [bytes] as a downscaled JPEG and returns its SHA-256 key,
     * or null if [bytes] cannot be decoded as an image.
     */
    fun store(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val key = sha256Hex(bytes)
        val outFile = targetFile(key)
        if (outFile.exists()) return key

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
        val finalBitmap = if (longest > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toFloat() / longest.toFloat()
            val targetW = max(1, (bitmap.width * scale).roundToInt())
            val targetH = max(1, (bitmap.height * scale).roundToInt())
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }

        if (!directory.exists()) directory.mkdirs()
        val tempFile = File(directory, "$key.jpg.tmp.${System.nanoTime()}")
        return try {
            FileOutputStream(tempFile).use { out ->
                if (!finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) {
                    tempFile.delete()
                    return null
                }
            }
            if (!tempFile.renameTo(outFile)) {
                tempFile.copyTo(outFile, overwrite = true)
                tempFile.delete()
            }
            memory.put(key, finalBitmap)
            key
        } catch (_: Exception) {
            runCatching { tempFile.delete() }
            null
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

    fun bitmap(key: String?): Bitmap? {
        if (key.isNullOrBlank()) return null
        memory.get(key)?.let { return it }
        val file = targetFile(key)
        if (!file.exists()) return null
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        memory.put(key, bmp)
        return bmp
    }

    fun file(key: String?): File? {
        if (key.isNullOrBlank()) return null
        return targetFile(key).takeIf { it.exists() }
    }

    /** Deletes stored images that no track refers to any more. */
    fun prune(keeping: Set<String>) {
        val files = directory.listFiles() ?: return
        for (file in files) {
            val stem = file.nameWithoutExtension
            if (stem !in keeping) {
                memory.remove(stem)
                runCatching { file.delete() }
            }
        }
    }

    companion object {
        const val MAX_DIMENSION = 600

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
