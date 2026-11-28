import Foundation

enum CloudLyricsProviderID {
    static let lrclib = "builtin.lrclib"
}

enum CloudLyricsSourceSelection: Equatable, Hashable, Sendable {
    case auto
    case lrclib
    case ext(id: String)

    var providerID: String? {
        switch self {
        case .auto: nil
        case .lrclib: CloudLyricsProviderID.lrclib
        case .ext(let id): "ext.\(id)"
        }
    }
}

struct CloudLyricsSourceOption: Identifiable, Equatable, Hashable, Sendable {
    let selection: CloudLyricsSourceSelection
    let label: String

    var id: String {
        switch selection {
        case .auto: "auto"
        case .lrclib: "lrclib"
        case .ext(let id): "ext.\(id)"
        }
    }
}

struct CloudLyricsQuery: Equatable, Sendable {
    var title: String
    var artist: String
    var album: String?
    var durationSec: Double?

    var trimmedTitle: String {
        title.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var trimmedArtist: String {
        artist.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var trimmedAlbum: String? {
        guard let album = album?.trimmingCharacters(in: .whitespacesAndNewlines), !album.isEmpty else {
            return nil
        }
        return album
    }

    var isEmpty: Bool {
        trimmedTitle.isEmpty && trimmedArtist.isEmpty
    }
}

struct CloudLyricsCandidate: Identifiable, Equatable, Sendable {
    var id: String
    var providerID: String
    var sourceName: String
    var title: String
    var artist: String
    var album: String?
    var durationSec: Double?
    var isInstrumental: Bool
    var syncedLyrics: String?
    var plainLyrics: String?
    var translatedLyrics: String?

    var isTimed: Bool {
        guard let synced = syncedLyrics?.trimmingCharacters(in: .whitespacesAndNewlines), !synced.isEmpty else {
            return false
        }
        return LRCParser.parse(synced).isTimed
    }

    var hasTranslation: Bool {
        guard let translated = translatedLyrics?.trimmingCharacters(in: .whitespacesAndNewlines) else {
            return false
        }
        return !translated.isEmpty && !LRCParser.parse(translated).lines.isEmpty
    }

    var hasUsableContent: Bool {
        if isInstrumental { return true }
        if let s = syncedLyrics, !LRCParser.parse(s).lines.isEmpty { return true }
        if let p = plainLyrics, !LRCParser.parse(p).lines.isEmpty { return true }
        return false
    }

    func formattedText(includeTranslation: Bool) -> String {
        if let synced = syncedLyrics?.trimmingCharacters(in: .whitespacesAndNewlines), !synced.isEmpty {
            if includeTranslation,
               let translation = translatedLyrics?.trimmingCharacters(in: .whitespacesAndNewlines),
               !translation.isEmpty {
                return CloudLyricsFormatter.mergeSyncedWithTranslation(primary: synced, translation: translation)
            }
            return synced
        }
        if let plain = plainLyrics?.trimmingCharacters(in: .whitespacesAndNewlines), !plain.isEmpty {
            if includeTranslation,
               let translation = translatedLyrics?.trimmingCharacters(in: .whitespacesAndNewlines),
               !translation.isEmpty {
                return "\(plain)\n\n\(translation)"
            }
            return plain
        }
        if isInstrumental {
            return CloudLyricsFormatter.instrumentalLRC
        }
        return ""
    }
}

enum CloudLyricsError: LocalizedError, Equatable, Sendable {
    case emptyQuery
    case rateLimited(retryAfterSeconds: Int?)
    case network(String)
    case providerUnavailable(String)

    var errorDescription: String? {
        switch self {
        case .emptyQuery:
            return L10n.trNonisolated("Enter a song title or artist to search.")
        case .rateLimited(let retry):
            if let retry, retry > 0 {
                return L10n.trNonisolated("Lyrics service is busy. Try again in %@s.", String(retry))
            }
            return L10n.trNonisolated("Lyrics service rate limited. Please try again shortly.")
        case .network(let message):
            return message
        case .providerUnavailable(let name):
            return L10n.trNonisolated("Lyrics provider “%@” is unavailable.", name)
        }
    }
}

struct CloudLyricsSourceFailure: Equatable, Sendable {
    var providerID: String
    var sourceName: String
    var message: String
}

struct CloudLyricsSearchResponse: Equatable, Sendable {
    var candidates: [CloudLyricsCandidate]
    var failures: [CloudLyricsSourceFailure]
}

enum CloudLyricsFormatter {
    static let instrumentalLRC = "[00:00.00]♪ Instrumental ♪"

    static func formatDurationAndDelta(candidateSec: Double?, targetSec: Double?) -> String? {
        guard let candidateSec, candidateSec.isFinite, candidateSec > 0 else { return nil }
        let base = formatClock(candidateSec)
        guard let targetSec, targetSec.isFinite, targetSec > 0 else { return base }
        let delta = Int((candidateSec - targetSec).rounded())
        if delta == 0 {
            return base
        }
        let sign = delta > 0 ? "+" : ""
        return "\(base) (\(sign)\(delta)s)"
    }

    static func formatClock(_ seconds: Double) -> String {
        let total = max(0, Int(seconds.rounded()))
        let mins = total / 60
        let secs = total % 60
        return String(format: "%d:%02d", mins, secs)
    }

    static func formatLRCTimestamp(_ seconds: Double) -> String {
        let totalHundredths = max(0, Int((seconds * 100.0).rounded()))
        let mins = totalHundredths / 6000
        let rem = totalHundredths % 6000
        let secs = rem / 100
        let cs = rem % 100
        return String(format: "[%02d:%02d.%02d]", mins, secs, cs)
    }

    /// Merges bilingual LRC lines by matching translation timestamps within `<= 50ms`.
    static func mergeSyncedWithTranslation(primary: String, translation: String) -> String {
        let primaryParsed = LRCParser.parse(primary)
        let transParsed = LRCParser.parse(translation)
        guard primaryParsed.isTimed, !primaryParsed.lines.isEmpty else {
            return primary
        }
        guard transParsed.isTimed, !transParsed.lines.isEmpty else {
            return primary
        }

        let transTimed: [(time: TimeInterval, text: String)] = transParsed.lines.compactMap { line in
            guard let t = line.time else { return nil }
            let trimmed = line.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, trimmed != "//" else { return nil }
            return (t, trimmed)
        }
        guard !transTimed.isEmpty else { return primary }

        var outputLines: [String] = []
        outputLines.reserveCapacity(primaryParsed.lines.count * 2)

        for line in primaryParsed.lines {
            guard let time = line.time else {
                let txt = line.text.trimmingCharacters(in: .whitespacesAndNewlines)
                if !txt.isEmpty { outputLines.append(txt) }
                continue
            }
            let tag = formatLRCTimestamp(time)
            outputLines.append("\(tag)\(line.text)")

            if let match = closestTranslation(to: time, in: transTimed, tolerance: 0.05) {
                if match.text != line.text.trimmingCharacters(in: .whitespacesAndNewlines) {
                    outputLines.append("\(tag)\(match.text)")
                }
            }
        }

        return outputLines.joined(separator: "\n")
    }

    private static func closestTranslation(
        to targetTime: TimeInterval,
        in lines: [(time: TimeInterval, text: String)],
        tolerance: TimeInterval
    ) -> (time: TimeInterval, text: String)? {
        var best: (time: TimeInterval, text: String)?
        var bestDiff = tolerance + 0.0001
        for item in lines {
            let diff = abs(item.time - targetTime)
            if diff <= tolerance && diff < bestDiff {
                bestDiff = diff
                best = item
            }
        }
        return best
    }
}

enum CloudLyricsScorer {
    static func score(candidate: CloudLyricsCandidate, against query: CloudLyricsQuery) -> Double {
        var total: Double = 0
        let queryTitle = query.trimmedTitle
        let queryArtist = query.trimmedArtist
        let queryAlbum = query.trimmedAlbum

        if !queryTitle.isEmpty {
            total += LyricsNameMatcher.similarity(candidate.title, queryTitle) * 42.0
        }
        if !queryArtist.isEmpty {
            total += LyricsNameMatcher.similarity(candidate.artist, queryArtist) * 28.0
        }
        if let queryAlbum, let candAlbum = candidate.album, !candAlbum.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            total += LyricsNameMatcher.similarity(candAlbum, queryAlbum) * 5.0
        }

        if let qDur = query.durationSec, qDur.isFinite, qDur > 0,
           let cDur = candidate.durationSec, cDur.isFinite, cDur > 0 {
            let diff = abs(cDur - qDur)
            if diff <= 2 {
                total += 20
            } else if diff <= 5 {
                total += 14
            } else if diff <= 10 {
                total += 7
            } else if diff <= 20 {
                total += 1
            } else {
                total -= min(18, (diff - 20) * 0.4)
            }
        }

        if candidate.isTimed {
            total += 15
        } else if candidate.isInstrumental {
            total -= 6
        }

        return total
    }

    static func rankAndDeduplicate(
        _ candidates: [CloudLyricsCandidate],
        for query: CloudLyricsQuery
    ) -> [CloudLyricsCandidate] {
        var seen: Set<String> = []
        let filtered = candidates.filter { candidate in
            guard candidate.hasUsableContent else { return false }
            let dedupKey = [
                candidate.providerID,
                LyricsNameMatcher.normalizeKey(candidate.title),
                LyricsNameMatcher.normalizeKey(candidate.artist),
                candidate.isTimed ? "1" : "0",
                String(candidate.syncedLyrics?.prefix(80) ?? candidate.plainLyrics?.prefix(80) ?? "")
            ].joined(separator: "|")
            return seen.insert(dedupKey).inserted
        }

        return filtered
            .enumerated()
            .sorted { lhs, rhs in
                let sl = score(candidate: lhs.element, against: query)
                let sr = score(candidate: rhs.element, against: query)
                if abs(sl - sr) > 0.001 {
                    return sl > sr
                }
                if lhs.element.isTimed != rhs.element.isTimed {
                    return lhs.element.isTimed && !rhs.element.isTimed
                }
                return lhs.offset < rhs.offset
            }
            .map(\.element)
    }
}
