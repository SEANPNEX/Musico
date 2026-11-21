package com.musico.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
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

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sampleSize = 1
        while (max(rawW / sampleSize, rawH / sampleSize) > MAX_DIMENSION * 2) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null

        val longest = max(decoded.width, decoded.height)
        val finalBitmap = if (longest > MAX_DIMENSION) {
            val scale = MAX_DIMENSION.toFloat() / longest.toFloat()
            val targetW = max(1, (decoded.width * scale).roundToInt())
            val targetH = max(1, (decoded.height * scale).roundToInt())
            Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
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
