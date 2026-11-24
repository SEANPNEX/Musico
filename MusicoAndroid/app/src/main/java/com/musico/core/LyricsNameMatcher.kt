package com.musico.core

import java.text.Normalizer

/**
 * Fuzzy filename matcher for locating matching `.lrc`/`.txt` lyrics in the same folder
 * as a playing track.
 */
object LyricsNameMatcher {
    const val MINIMUM_MATCH_SCORE: Double = 0.72

    private val genericStems = setOf(
        "lyrics", "lyric", "words", "readme", "info", "notes", "note",
        "track", "song", "audio", "music", "index", "playlist", "album",
        "untitled", "unknown", "歌词",
    )

    private val noiseWords = setOf(
        "official", "audio", "video", "music", "mv", "pv", "lyric", "lyrics",
        "visualizer", "hd", "hq", "hi", "res", "hires", "flac", "mp3",
        "aac", "wav", "alac", "lossless", "explicit", "clean", "remaster",
        "remastered", "24bit", "16bit",
    )

    private val bracketPairs = listOf(
        '(' to ')',
        '[' to ']',
        '【' to '】',
        '（' to '）',
    )

    private val trackNumberPatterns = listOf(
        Regex("^(?i:(?:disc|cd)\\s*\\d{1,2}\\s*[-._\\s]+)?\\d{1,3}\\s*[-._)\\]]+\\s*(?=.+)"),
        Regex("^(?i:track\\s*#?\\s*\\d{1,3})\\s*[-._)\\s:]+\\s*(?=.+)"),
        Regex("^\\d{1,2}\\s+(?=[\\p{L}\\p{N}])"),
    )

    fun isGenericStem(stem: String): Boolean {
        val norm = normalizeKey(stem)
        return norm.isEmpty() || norm in genericStems
    }

    fun normalizeKey(raw: String): String = tokenize(raw).joinToString(" ")

    fun tokenize(raw: String): List<String> {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val folded = foldLatinDiacriticsPreservingKana(nfkc)
            .replace('\u3000', ' ')
            .trim()
        if (folded.isEmpty()) return emptyList()

        val cleaned = stripLeadingTrackNumber(stripNoiseBrackets(folded))
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var index = 0
        while (index < cleaned.length) {
            val cp = cleaned.codePointAt(index)
            val charCount = Character.charCount(cp)
            if (Character.isLetterOrDigit(cp)) {
                current.appendCodePoint(Character.toLowerCase(cp))
            } else if (current.isNotEmpty()) {
                tokens.add(current.toString())
                current.setLength(0)
            }
            index += charCount
        }
        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }
        return tokens
    }

    fun matchScore(
        candidateStem: String,
        audioStem: String,
        trackTitle: String?,
        trackArtist: String?,
    ): Double {
        val candTokens = tokenize(candidateStem)
        val candNorm = candTokens.joinToString(" ")
        if (candNorm.isEmpty() || candNorm in genericStems) return 0.0

        data class Reference(val norm: String, val tokens: List<String>, val weight: Double)

        val references = ArrayList<Reference>(4)
        val audioTokens = tokenize(audioStem)
        val audioNorm = audioTokens.joinToString(" ")
        if (audioNorm.isNotEmpty()) {
            references.add(Reference(audioNorm, audioTokens, 1.0))
        }

        val titleTokens = tokenize(trackTitle.orEmpty())
        val titleNorm = titleTokens.joinToString(" ")
        val artistTokens = tokenize(trackArtist.orEmpty())

        if (titleNorm.isNotEmpty()) {
            references.add(Reference(titleNorm, titleTokens, 0.98))
            if (artistTokens.isNotEmpty()) {
                val atTokens = artistTokens + titleTokens
                val taTokens = titleTokens + artistTokens
                references.add(Reference(atTokens.joinToString(" "), atTokens, 0.99))
                references.add(Reference(taTokens.joinToString(" "), taTokens, 0.97))
            }
        }
        if (references.isEmpty()) return 0.0

        val candSet = candTokens.toSet()
        var best = 0.0
        for (ref in references) {
            if (candNorm == ref.norm) {
                best = maxOf(best, ref.weight)
                continue
            }
            val refSet = ref.tokens.toSet()
            if (candSet == refSet && candSet.isNotEmpty()) {
                best = maxOf(best, 0.96 * ref.weight)
                continue
            }
            val jaccard = tokenJaccard(candSet, refSet)
            val containment = maxOf(
                tokenContainment(candSet, refSet),
                tokenContainment(refSet, candSet),
            )
            val tokenSim = jaccard * 0.55 + containment * 0.45
            val charSim = bigramDiceSimilarity(candNorm, ref.norm)
            val combined = maxOf(tokenSim, charSim * 0.92) * ref.weight
            best = maxOf(best, combined)
        }

        if (artistTokens.isNotEmpty() && titleNorm.isNotEmpty() && candNorm != audioNorm) {
            val prefix = splitHyphenPrefix(candidateStem)
            if (prefix != null) {
                val prefixSet = tokenize(prefix).toSet()
                val artistSet = artistTokens.toSet()
                val titleSet = titleTokens.toSet()
                if (prefixSet.isNotEmpty() && prefixSet.intersect(artistSet).isEmpty() && prefixSet != titleSet) {
                    best *= 0.65
                }
            }
        }

        return best
    }

    fun similarity(a: String, b: String): Double {
        val ta = tokenize(a)
        val tb = tokenize(b)
        val na = ta.joinToString(" ")
        val nb = tb.joinToString(" ")
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val sa = ta.toSet()
        val sb = tb.toSet()
        if (sa == sb) return 0.96
        val jaccard = tokenJaccard(sa, sb)
        val containment = maxOf(tokenContainment(sa, sb), tokenContainment(sb, sa))
        val dice = bigramDiceSimilarity(na, nb)
        return maxOf(jaccard * 0.6 + containment * 0.4, dice * 0.9)
    }

    private fun foldLatinDiacriticsPreservingKana(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (ch in '\u0300'..'\u036F') continue
            sb.append(ch)
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC)
    }

    private fun stripNoiseBrackets(text: String): String {
        var current = text
        for ((open, close) in bracketPairs) {
            current = removeMatchingNoiseSegments(current, open, close)
        }
        return current.trim()
    }

    private fun removeMatchingNoiseSegments(text: String, open: Char, close: Char): String {
        val result = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] == open) {
                val end = text.indexOf(close, i + 1)
                if (end > i) {
                    val inner = text.substring(i + 1, end)
                    if (isPureNoiseSegment(inner)) {
                        val remaining = result.toString() + text.substring(end + 1)
                        if (remaining.trim().isNotEmpty()) {
                            i = end + 1
                            continue
                        }
                    }
                }
            }
            result.append(text[i])
            i += 1
        }
        return result.toString()
    }

    private fun isPureNoiseSegment(inner: String): Boolean {
        val words = ArrayList<String>()
        val cur = StringBuilder()
        var idx = 0
        while (idx < inner.length) {
            val cp = inner.codePointAt(idx)
            val cc = Character.charCount(cp)
            if (Character.isLetterOrDigit(cp)) {
                cur.appendCodePoint(Character.toLowerCase(cp))
            } else if (cur.isNotEmpty()) {
                words.add(cur.toString())
                cur.setLength(0)
            }
            idx += cc
        }
        if (cur.isNotEmpty()) words.add(cur.toString())
        if (words.isEmpty()) return true
        return words.all { word ->
            when {
                word in noiseWords -> true
                word.endsWith("kbps") || word.endsWith("khz") || word.endsWith("bit") -> {
                    val prefixLen = if (word.endsWith("bit")) 3 else 4
                    val prefix = word.dropLast(prefixLen)
                    prefix.isNotEmpty() && prefix.all { it.isDigit() }
                }
                word.length == 4 && word.all { it.isDigit() } -> {
                    val year = word.toIntOrNull()
                    year != null && year in 1900..2099 && words.size > 1
                }
                else -> false
            }
        }
    }

    private fun stripLeadingTrackNumber(text: String): String {
        var working = text.trim()
        for (pattern in trackNumberPatterns) {
            val match = pattern.find(working)
            if (match != null) {
                val candidate = working.substring(match.range.last + 1).trim()
                if (candidate.any { Character.isLetterOrDigit(it) }) {
                    working = candidate
                    break
                }
            }
        }
        return working
    }

    private fun splitHyphenPrefix(raw: String): String? {
        for (sep in listOf(" - ", " — ", " – ")) {
            val idx = raw.indexOf(sep)
            if (idx > 0) return raw.substring(0, idx)
        }
        return null
    }

    private fun tokenJaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return if (union > 0) inter.toDouble() / union.toDouble() else 0.0
    }

    private fun tokenContainment(inner: Set<String>, outer: Set<String>): Double {
        if (inner.isEmpty() || outer.isEmpty()) return 0.0
        val hits = inner.intersect(outer).size
        return hits.toDouble() / inner.size.toDouble()
    }

    private fun bigramDiceSimilarity(a: String, b: String): Double {
        val ca = a.replace(" ", "")
        val cb = b.replace(" ", "")
        if (ca.length < 2 || cb.length < 2) {
            return if (ca == cb && ca.isNotEmpty()) 1.0 else 0.0
        }
        val bgA = HashMap<String, Int>()
        for (i in 0 until ca.length - 1) {
            val g = ca.substring(i, i + 2)
            bgA[g] = (bgA[g] ?: 0) + 1
        }
        val bgB = HashMap<String, Int>()
        for (i in 0 until cb.length - 1) {
            val g = cb.substring(i, i + 2)
            bgB[g] = (bgB[g] ?: 0) + 1
        }
        var overlap = 0
        for ((g, countA) in bgA) {
            val countB = bgB[g] ?: 0
            if (countB > 0) overlap += minOf(countA, countB)
        }
        return (2.0 * overlap) / ((ca.length - 1) + (cb.length - 1)).toDouble()
    }
}
