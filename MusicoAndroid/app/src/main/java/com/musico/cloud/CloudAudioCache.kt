package com.musico.cloud

import java.io.File
import java.io.FileOutputStream

/**
 * Quality-partitioned on-disk cache for resolved cloud audio files.
 * Files are named `<sanitizedTrackId>__<sanitizedQuality>.<ext>` and stale quality
 * variants for the same `trackId` are evicted whenever a new quality is stored.
 */
class CloudAudioCache(
    val directory: File,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val lock = Any()

    init {
        directory.mkdirs()
    }

    fun cachedFile(trackId: String, qualityId: String?): File? = synchronized(lock) {
        if (!directory.isDirectory) return null
        val safeTrack = sanitizeTrackId(trackId)
        val safeQuality = sanitizeQualityId(qualityId)
        val prefix = "${safeTrack}__${safeQuality}."
        val exactStem = "${safeTrack}__${safeQuality}"
        val match = directory.listFiles()
            ?.firstOrNull { file ->
                file.isFile &&
                    !file.name.endsWith(".part") &&
                    file.length() > 0L &&
                    (file.name.startsWith(prefix) || file.name == exactStem)
            }
            ?: return null
        runCatching { match.setLastModified(System.currentTimeMillis()) }
        match
    }

    fun store(
        trackId: String,
        qualityId: String?,
        fileExtension: String?,
        writeContent: (File) -> Unit,
    ): File = synchronized(lock) {
        directory.mkdirs()
        val safeTrack = sanitizeTrackId(trackId)
        val safeQuality = sanitizeQualityId(qualityId)
        val cleanExt = fileExtension?.trim()?.trimStart('.')?.lowercase()?.takeIf { it.isNotEmpty() } ?: "m4a"
        val targetFile = File(directory, "${safeTrack}__${safeQuality}.$cleanExt")
        val partFile = File(directory, ".${safeTrack}__${safeQuality}.${System.nanoTime()}.part")
        try {
            writeContent(partFile)
            if (!partFile.exists() || partFile.length() == 0L) {
                throw IllegalStateException("Cached audio write produced an empty file")
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }
            runCatching { targetFile.setLastModified(System.currentTimeMillis()) }
            evictOtherQualityVariantsLocked(safeTrack, keepFileName = targetFile.name)
            trimToBudgetLocked(protectFileName = targetFile.name)
            targetFile
        } catch (t: Throwable) {
            runCatching { partFile.delete() }
            throw t
        }
    }

    fun storeBytes(
        trackId: String,
        qualityId: String?,
        fileExtension: String?,
        bytes: ByteArray,
    ): File = store(trackId, qualityId, fileExtension) { file ->
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
        }
    }

    fun evictTrack(trackId: String, keepQualityId: String? = null) {
        synchronized(lock) {
            if (!directory.isDirectory) return
            val safeTrack = sanitizeTrackId(trackId)
            val keepPrefix = keepQualityId?.let { "${safeTrack}__${sanitizeQualityId(it)}." }
            val keepExact = keepQualityId?.let { "${safeTrack}__${sanitizeQualityId(it)}" }
            val trackPrefix = "${safeTrack}__"
            val legacyPrefix = "${safeTrack}."
            directory.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val name = file.name
                val belongsToTrack = name.startsWith(trackPrefix) || name.startsWith(legacyPrefix)
                val shouldKeep = (keepPrefix != null && name.startsWith(keepPrefix)) ||
                    (keepExact != null && name == keepExact)
                if (belongsToTrack && !shouldKeep) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    fun totalSizeBytes(): Long = synchronized(lock) {
        directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sumOf { it.length() }
            ?: 0L
    }

    fun clear() = synchronized(lock) {
        directory.listFiles()?.forEach { file ->
            if (file.isFile) runCatching { file.delete() }
        }
    }

    private fun evictOtherQualityVariantsLocked(safeTrack: String, keepFileName: String) {
        val trackPrefix = "${safeTrack}__"
        val legacyPrefix = "${safeTrack}."
        directory.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name
            if (name == keepFileName || name.endsWith(".part")) return@forEach
            if (name.startsWith(trackPrefix) || name.startsWith(legacyPrefix)) {
                runCatching { file.delete() }
            }
        }
    }

    private fun trimToBudgetLocked(protectFileName: String) {
        if (maxBytes <= 0L) return
        val files = directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            if (file.name == protectFileName) continue
            val len = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                total -= len
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 500L * 1024L * 1024L
        private val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")

        fun sanitizeTrackId(trackId: String): String {
            val cleaned = UNSAFE_CHARS.replace(trackId.trim(), "_")
                .replace("__", "_")
                .trim('.')
            return cleaned.ifEmpty { "track" }.take(96)
        }

        fun sanitizeQualityId(qualityId: String?): String {
            val raw = qualityId?.trim().orEmpty()
            if (raw.isEmpty()) return "default"
            val cleaned = UNSAFE_CHARS.replace(raw, "_").trim('.')
            return cleaned.ifEmpty { "default" }.take(32)
        }
    }
}
