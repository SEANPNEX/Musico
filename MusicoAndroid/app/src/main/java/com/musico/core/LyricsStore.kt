package com.musico.core

import android.net.Uri
import com.musico.model.Lyrics
import com.musico.model.LyricsSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.abs

class LyricsUnreadableException : Exception("That file could not be read as lyrics text.")

/**
 * Resolves lyrics for a track using local sources only, in priority order:
 * 1. User-imported `.lrc` in [directory]
 * 2. Exact sibling `.lrc` / `.txt` next to the audio file
 * 3. Non-recursive fuzzy-matched `.lrc` / `.txt` in the same folder
 * 4. Embedded lyrics tag
 */
class LyricsStore(
    private val directory: File,
) {
    private val _lyricsChanges = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val lyricsChanges: SharedFlow<String> = _lyricsChanges.asSharedFlow()

    private fun importedFile(trackId: String): File = File(directory, "$trackId.lrc")

    fun resolve(
        trackId: String,
        audioUri: Uri? = null,
        siblingProvider: (() -> ByteArray?)? = null,
        embeddedText: String? = null,
        trackTitle: String? = null,
        trackArtist: String? = null,
        folderMatchProvider: (() -> ByteArray?)? = null,
        audioFile: File? = null,
    ): Pair<Lyrics, LyricsSource>? {
        readFile(importedFile(trackId))?.let { return it to LyricsSource.IMPORTED }

        val resolvedLocalAudio = audioFile ?: audioUri
            ?.takeIf { runCatching { it.scheme == "file" && it.path != null }.getOrDefault(false) }
            ?.let { File(it.path!!) }

        if (siblingProvider != null) {
            val sidecarBytes = siblingProvider()
            if (sidecarBytes != null) {
                decodeAndParse(sidecarBytes)?.let { return it to LyricsSource.SIDECAR }
            }
        } else if (resolvedLocalAudio != null) {
            val parent = resolvedLocalAudio.parentFile
            val stem = resolvedLocalAudio.nameWithoutExtension
            if (parent != null) {
                for (ext in listOf("lrc", "txt")) {
                    readFile(File(parent, "$stem.$ext"))?.let { return it to LyricsSource.SIDECAR }
                }
            }
        }

        if (folderMatchProvider != null) {
            val folderBytes = folderMatchProvider()
            if (folderBytes != null) {
                decodeAndParse(folderBytes)?.let { return it to LyricsSource.FOLDER_MATCH }
            }
        } else if (resolvedLocalAudio != null) {
            val parent = resolvedLocalAudio.parentFile
            if (parent != null) {
                findBestFolderMatch(
                    dir = parent,
                    audioStem = resolvedLocalAudio.nameWithoutExtension,
                    trackTitle = trackTitle,
                    trackArtist = trackArtist,
                )?.let { return it to LyricsSource.FOLDER_MATCH }
            }
        }

        if (embeddedText != null) {
            val parsed = LrcParser.parse(embeddedText)
            if (parsed.lines.isNotEmpty()) return parsed to LyricsSource.EMBEDDED
        }
        return null
    }

    private fun findBestFolderMatch(
        dir: File,
        audioStem: String,
        trackTitle: String?,
        trackArtist: String?,
    ): Lyrics? {
        val children = dir.listFiles() ?: return null
        val exactNames = setOf(
            "$audioStem.lrc".lowercase(),
            "$audioStem.txt".lowercase(),
        )
        data class Candidate(
            val fileName: String,
            val isLrc: Boolean,
            val score: Double,
            val lyrics: Lyrics,
        )

        val candidates = ArrayList<Candidate>()
        for (child in children) {
            if (!child.isFile || child.isHidden) continue
            val name = child.name
            if (name.lowercase() in exactNames) continue
            val ext = child.extension.lowercase()
            if (ext != "lrc" && ext != "txt") continue
            val stem = child.nameWithoutExtension
            val score = LyricsNameMatcher.matchScore(stem, audioStem, trackTitle, trackArtist)
            if (score < LyricsNameMatcher.MINIMUM_MATCH_SCORE) continue
            val lyrics = readFile(child) ?: continue
            candidates.add(Candidate(name, ext == "lrc", score, lyrics))
        }

        val winner = candidates.sortedWith { a, b ->
            if (abs(a.score - b.score) > 0.001) {
                b.score.compareTo(a.score)
            } else if (a.lyrics.isTimed != b.lyrics.isTimed) {
                if (b.lyrics.isTimed) 1 else -1
            } else if (a.isLrc != b.isLrc) {
                if (b.isLrc) 1 else -1
            } else {
                a.fileName.lowercase().compareTo(b.fileName.lowercase())
            }
        }.firstOrNull()

        return winner?.lyrics
    }

    private fun decodeAndParse(bytes: ByteArray): Lyrics? {
        val decoded = LrcParser.decode(bytes) ?: return null
        val parsed = LrcParser.parse(decoded)
        return parsed.takeIf { it.lines.isNotEmpty() }
    }

    private fun readFile(file: File): Lyrics? {
        if (!file.exists() || !file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return decodeAndParse(bytes)
    }

    fun hasImported(trackId: String): Boolean = importedFile(trackId).exists()

    fun rawImportedText(trackId: String): String? {
        val file = importedFile(trackId)
        if (!file.exists() || !file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return LrcParser.decode(bytes)
    }

    fun importLyrics(inputStream: InputStream, trackId: String) {
        val bytes = inputStream.readBytes()
        val text = LrcParser.decode(bytes) ?: throw LyricsUnreadableException()
        importLyrics(text = text, trackId = trackId)
    }

    fun importLyrics(text: String, trackId: String) {
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
        _lyricsChanges.tryEmit(trackId)
    }

    fun importLyrics(file: File, trackId: String) {
        FileInputStream(file).use { importLyrics(it, trackId) }
    }

    fun removeImported(trackId: String) {
        runCatching { importedFile(trackId).delete() }
        _lyricsChanges.tryEmit(trackId)
    }
}
