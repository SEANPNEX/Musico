import Foundation

/// Fuzzy stem matcher for locating matching `.lrc`/`.txt` lyrics in the same folder as an audio file.
enum LyricsNameMatcher {
    static let minimumMatchScore: Double = 0.72

    private static let genericStems: Set<String> = [
        "lyrics", "lyric", "words", "readme", "info", "notes", "note",
        "track", "song", "audio", "music", "index", "playlist", "album",
        "untitled", "unknown", "歌词"
    ]

    private static let noiseWords: Set<String> = [
        "official", "audio", "video", "music", "mv", "pv", "lyric", "lyrics",
        "visualizer", "hd", "hq", "hi", "res", "hires", "flac", "mp3",
        "aac", "wav", "alac", "lossless", "explicit", "clean", "remaster",
        "remastered", "24bit", "16bit"
    ]

    struct ParsedStem: Equatable {
        let normalized: String
        let tokens: [String]
    }

    static func isGenericStem(_ stem: String) -> Bool {
        let normalized = normalizeKey(stem)
        return normalized.isEmpty || genericStems.contains(normalized)
    }

    /// Normalizes a file stem or metadata field into a comparison-ready lowercase string:
    /// NFKC compatibility mapping, stripping only Latin combining diacritics (`U+0300...U+036F`)
    /// so Japanese dakuten/handakuten stay intact, removing noise brackets and leading track numbers
    /// (while preserving standalone years such as `"1989"`).
    static func normalizeKey(_ raw: String) -> String {
        tokenize(raw).joined(separator: " ")
    }

    static func tokenize(_ raw: String) -> [String] {
        let folded = foldLatinDiacriticsPreservingKana(raw.precomposedStringWithCompatibilityMapping)
            .replacingOccurrences(of: "\u{3000}", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !folded.isEmpty else { return [] }

        var cleaned = stripNoiseBrackets(folded)
        cleaned = stripLeadingTrackNumber(cleaned)

        var tokens: [String] = []
        var current = ""
        for scalar in cleaned.lowercased().unicodeScalars {
            if CharacterSet.alphanumerics.contains(scalar) {
                current.unicodeScalars.append(scalar)
            } else if !current.isEmpty {
                tokens.append(current)
                current = ""
            }
        }
        if !current.isEmpty {
            tokens.append(current)
        }
        return tokens
    }

    /// Scores how well a candidate lyrics file stem matches a track's filename and optional tags.
    /// Returns a value in `0...1`, or `0` if generic or below meaningful similarity.
    static func matchScore(
        candidateStem: String,
        audioStem: String,
        trackTitle: String?,
        trackArtist: String?
    ) -> Double {
        let candTokens = tokenize(candidateStem)
        let candNorm = candTokens.joined(separator: " ")
        guard !candNorm.isEmpty, !genericStems.contains(candNorm) else { return 0 }

        var references: [(norm: String, tokens: [String], weight: Double)] = []
        let audioTokens = tokenize(audioStem)
        let audioNorm = audioTokens.joined(separator: " ")
        if !audioNorm.isEmpty {
            references.append((audioNorm, audioTokens, 1.0))
        }

        let titleTokens = tokenize(trackTitle ?? "")
        let titleNorm = titleTokens.joined(separator: " ")
        let artistTokens = tokenize(trackArtist ?? "")
        let artistNorm = artistTokens.joined(separator: " ")

        if !titleNorm.isEmpty {
            references.append((titleNorm, titleTokens, 0.98))
            if !artistNorm.isEmpty {
                let atTokens = artistTokens + titleTokens
                let taTokens = titleTokens + artistTokens
                references.append((atTokens.joined(separator: " "), atTokens, 0.99))
                references.append((taTokens.joined(separator: " "), taTokens, 0.97))
            }
        }

        guard !references.isEmpty else { return 0 }

        var best: Double = 0
        for ref in references {
            if candNorm == ref.norm {
                best = max(best, ref.weight)
                continue
            }
            let candSet = Set(candTokens)
            let refSet = Set(ref.tokens)
            if candSet == refSet && !candSet.isEmpty {
                best = max(best, 0.96 * ref.weight)
                continue
            }
            let jaccard = tokenJaccard(candSet, refSet)
            let containment = max(
                tokenContainment(candSet, inside: refSet),
                tokenContainment(refSet, inside: candSet)
            )
            let tokenSim = jaccard * 0.55 + containment * 0.45
            let charSim = bigramDiceSimilarity(candNorm, ref.norm)
            let combined = max(tokenSim, charSim * 0.92) * ref.weight
            best = max(best, combined)
        }

        // Guard against wrong cover/artist prefix when the track has a known artist tag.
        if !artistTokens.isEmpty && !titleNorm.isEmpty && candNorm != audioNorm {
            let candDashParts = splitHyphenParts(candidateStem)
            if let prefix = candDashParts.first {
                let prefixSet = Set(tokenize(prefix))
                let artistSet = Set(artistTokens)
                if !prefixSet.isEmpty && prefixSet.isDisjoint(with: artistSet) && prefixSet != Set(titleTokens) {
                    best *= 0.65
                }
            }
        }

        return best
    }

    static func similarity(_ a: String, _ b: String) -> Double {
        let ta = tokenize(a)
        let tb = tokenize(b)
        let na = ta.joined(separator: " ")
        let nb = tb.joined(separator: " ")
        if na.isEmpty || nb.isEmpty { return 0 }
        if na == nb { return 1 }
        let sa = Set(ta)
        let sb = Set(tb)
        if sa == sb { return 0.96 }
        let j = tokenJaccard(sa, sb)
        let c = max(tokenContainment(sa, inside: sb), tokenContainment(sb, inside: sa))
        let charDice = bigramDiceSimilarity(na, nb)
        return max(j * 0.6 + c * 0.4, charDice * 0.9)
    }

    // MARK: - Helpers

    private static func foldLatinDiacriticsPreservingKana(_ text: String) -> String {
        let decomposed = text.decomposedStringWithCanonicalMapping
        var filtered = String.UnicodeScalarView()
        filtered.reserveCapacity(decomposed.unicodeScalars.count)
        for scalar in decomposed.unicodeScalars {
            if (0x0300...0x036F).contains(scalar.value) {
                continue
            }
            filtered.append(scalar)
        }
        return String(filtered).precomposedStringWithCanonicalMapping
    }

    private static func stripNoiseBrackets(_ text: String) -> String {
        let bracketPairs: [(Character, Character)] = [("(", ")"), ("[", "]"), ("【", "】"), ("（", "）")]
        var output = text
        for (open, close) in bracketPairs {
            output = removeMatchingNoiseSegments(in: output, open: open, close: close)
        }
        return output.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func removeMatchingNoiseSegments(in text: String, open: Character, close: Character) -> String {
        var result = ""
        var i = text.startIndex
        while i < text.endIndex {
            if text[i] == open,
               let end = text[text.index(after: i)...].firstIndex(of: close) {
                let inner = String(text[text.index(after: i)..<end])
                if isPureNoiseSegment(inner) {
                    let after = text.index(after: end)
                    let without = result + String(text[after...])
                    if !without.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        i = after
                        continue
                    }
                }
            }
            result.append(text[i])
            i = text.index(after: i)
        }
        return result
    }

    private static func isPureNoiseSegment(_ inner: String) -> Bool {
        var words: [String] = []
        var cur = ""
        for scalar in inner.lowercased().unicodeScalars {
            if CharacterSet.alphanumerics.contains(scalar) {
                cur.unicodeScalars.append(scalar)
            } else if !cur.isEmpty {
                words.append(cur)
                cur = ""
            }
        }
        if !cur.isEmpty { words.append(cur) }
        guard !words.isEmpty else { return true }
        return words.allSatisfy { word in
            if noiseWords.contains(word) { return true }
            if word.hasSuffix("kbps") || word.hasSuffix("khz") || word.hasSuffix("bit") {
                let prefix = word.dropLast(word.hasSuffix("bit") ? 3 : 4)
                return !prefix.isEmpty && prefix.allSatisfy(\.isNumber)
            }
            if word.count == 4, let year = Int(word), (1900...2099).contains(year) {
                return words.count > 1
            }
            return false
        }
    }

    private static func stripLeadingTrackNumber(_ text: String) -> String {
        var working = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let patterns = [
            #"^(?i:(?:disc|cd)\s*\d{1,2}\s*[-._\s]+)?\d{1,3}\s*[-._)\]]+\s*(?=.+)"#,
            #"^(?i:track\s*#?\s*\d{1,3})\s*[-._)\s:]+\s*(?=.+)"#,
            #"^\d{1,2}\s+(?=\p{L}|\p{N})"#
        ]
        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
            let range = NSRange(working.startIndex..<working.endIndex, in: working)
            if let match = regex.firstMatch(in: working, range: range),
               let swiftRange = Range(match.range, in: working) {
                let candidate = String(working[swiftRange.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
                if candidate.unicodeScalars.contains(where: { CharacterSet.alphanumerics.contains($0) }) {
                    working = candidate
                    break
                }
            }
        }
        return working
    }

    private static func splitHyphenParts(_ raw: String) -> [String] {
        for sep in [" - ", " — ", " – "] {
            if raw.contains(sep) {
                return raw.components(separatedBy: sep)
            }
        }
        return [raw]
    }

    private static func tokenJaccard(_ a: Set<String>, _ b: Set<String>) -> Double {
        guard !a.isEmpty, !b.isEmpty else { return 0 }
        let inter = a.intersection(b).count
        let union = a.union(b).count
        return union > 0 ? Double(inter) / Double(union) : 0
    }

    private static func tokenContainment(_ inner: Set<String>, inside outer: Set<String>) -> Double {
        guard !inner.isEmpty, !outer.isEmpty else { return 0 }
        let hits = inner.intersection(outer).count
        return Double(hits) / Double(inner.count)
    }

    private static func bigramDiceSimilarity(_ a: String, _ b: String) -> Double {
        let ca = a.replacingOccurrences(of: " ", with: "")
        let cb = b.replacingOccurrences(of: " ", with: "")
        guard ca.count >= 2, cb.count >= 2 else { return ca == cb && !ca.isEmpty ? 1 : 0 }
        let bgA = bigrams(ca)
        let bgB = bigrams(cb)
        var countsA: [String: Int] = [:]
        for g in bgA { countsA[g, default: 0] += 1 }
        var countsB: [String: Int] = [:]
        for g in bgB { countsB[g, default: 0] += 1 }
        var overlap = 0
        for (g, c) in countsA {
            if let c2 = countsB[g] {
                overlap += min(c, c2)
            }
        }
        return Double(2 * overlap) / Double(bgA.count + bgB.count)
    }

    private static func bigrams(_ s: String) -> [String] {
        let chars = Array(s)
        guard chars.count >= 2 else { return [] }
        var out: [String] = []
        out.reserveCapacity(chars.count - 1)
        for i in 0..<(chars.count - 1) {
            out.append(String([chars[i], chars[i + 1]]))
        }
        return out
    }
}
