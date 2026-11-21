package com.musico.core

import android.net.Uri
import com.musico.model.Lyrics
import com.musico.model.LyricsSource
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class LyricsUnreadableException : Exception("That file could not be read as lyrics text.")

/**
 * Resolves lyrics for a track in priority order:
 * 1. User-imported `.lrc` in [directory]
 * 2. Sibling `.lrc` / `.txt` next to the audio file
 * 3. Embedded lyrics tag
 */
class LyricsStore(
    private val directory: File,
) {
    private fun importedFile(trackId: String): File = File(directory, "$trackId.lrc")

    fun resolve(
        trackId: String,
        audioUri: Uri? = null,
        siblingProvider: (() -> ByteArray?)? = null,
        embeddedText: String? = null,
    ): Pair<Lyrics, LyricsSource>? {
        readFile(importedFile(trackId))?.let { return it to LyricsSource.IMPORTED }

        if (siblingProvider != null) {
            val sidecarBytes = siblingProvider()
            if (sidecarBytes != null) {
                val decoded = LrcParser.decode(sidecarBytes)
                if (decoded != null) {
                    val parsed = LrcParser.parse(decoded)
                    if (parsed.lines.isNotEmpty()) return parsed to LyricsSource.SIDECAR
                }
            }
        } else if (audioUri != null && audioUri.scheme == "file" && audioUri.path != null) {
            val audioFile = File(audioUri.path!!)
            val parent = audioFile.parentFile
            val stem = audioFile.nameWithoutExtension
            if (parent != null) {
                for (ext in listOf("lrc", "txt")) {
                    readFile(File(parent, "$stem.$ext"))?.let { return it to LyricsSource.SIDECAR }
                }
            }
        }

        if (embeddedText != null) {
            val parsed = LrcParser.parse(embeddedText)
            if (parsed.lines.isNotEmpty()) return parsed to LyricsSource.EMBEDDED
        }
        return null
    }

    private fun readFile(file: File): Lyrics? {
        if (!file.exists() || !file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val text = LrcParser.decode(bytes) ?: return null
        val parsed = LrcParser.parse(text)
        return parsed.takeIf { it.lines.isNotEmpty() }
    }

    fun hasImported(trackId: String): Boolean = importedFile(trackId).exists()

    fun importLyrics(inputStream: InputStream, trackId: String) {
        val bytes = inputStream.readBytes()
        val text = LrcParser.decode(bytes) ?: throw LyricsUnreadableException()
        if (LrcParser.parse(text).lines.isEmpty()) throw LyricsUnreadableException()
        if (!directory.exists()) directory.mkdirs()
        val target = importedFile(trackId)
        val temp = File(directory, "${target.name}.tmp.${System.nanoTime()}")
        try {
            temp.writeText(text, Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            throw t
        }
    }

    fun importLyrics(file: File, trackId: String) {
        FileInputStream(file).use { importLyrics(it, trackId) }
    }

    fun removeImported(trackId: String) {
        runCatching { importedFile(trackId).delete() }
    }
}
