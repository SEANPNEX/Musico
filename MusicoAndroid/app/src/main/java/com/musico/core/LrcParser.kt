package com.musico.core

import com.musico.model.LyricLine
import com.musico.model.Lyrics
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object LrcParser {
    private val timestampRegex = Regex("""^\s*\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
    private val metadataTagRegex = Regex("""^\s*\[[A-Za-z#][^\]]*\]\s*$""")

    fun parse(text: String): Lyrics {
        val timed = mutableListOf<Pair<Double, String>>()
        val plain = mutableListOf<String>()

        for (rawLine in text.split('\n')) {
            var rest = rawLine.trimEnd('\r')
            val times = mutableListOf<Double>()

            while (true) {
                val match = timestampRegex.find(rest) ?: break
                val minutes = match.groupValues[1].toDoubleOrNull() ?: 0.0
                val seconds = match.groupValues[2].toDoubleOrNull() ?: 0.0
                val fracRaw = match.groupValues[3]
                val fraction = if (fracRaw.isNotEmpty()) ("0.$fracRaw").toDoubleOrNull() ?: 0.0 else 0.0
                times.add(minutes * 60.0 + seconds + fraction)
                rest = rest.substring(match.range.last + 1)
            }

            val content = rest.trim()
            if (times.isNotEmpty()) {
                for (t in times) {
                    timed.add(t to content)
                }
            } else if (!metadataTagRegex.matches(rest) && content.isNotEmpty()) {
                plain.add(content)
            }
        }

        if (timed.isEmpty()) {
            val lines = plain.mapIndexed { idx, lineText ->
                LyricLine(id = idx, timeSec = null, text = lineText)
            }
            return Lyrics(lines = lines, isTimed = false)
        }

        val sorted = timed.withIndex().sortedWith(
            compareBy<IndexedValue<Pair<Double, String>>> { it.value.first }.thenBy { it.index }
        )
        val lines = sorted.mapIndexed { idx, item ->
            LyricLine(id = idx, timeSec = item.value.first, text = item.value.second)
        }
        return Lyrics(lines = lines, isTimed = true)
    }

    /**
     * Decodes lyric file bytes trying UTF-8 -> UTF-16 (with BOM) -> GB18030.
     * Strips any leading BOM character.
     */
    fun decode(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""
        val hasUtf16Bom = (bytes.size >= 2) && (
            (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            )

        val decoded = decodeStrict(bytes, Charsets.UTF_8)
            ?: if (hasUtf16Bom && bytes.size % 2 == 0) {
                decodeStrict(bytes, Charsets.UTF_16)
            } else {
                null
            }
            ?: runCatching { Charset.forName("GB18030") }.getOrNull()?.let { gb ->
                decodeStrict(bytes, gb)
            }
            ?: return null

        return if (decoded.startsWith('\uFEFF')) decoded.substring(1) else decoded
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? {
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
    }
}
