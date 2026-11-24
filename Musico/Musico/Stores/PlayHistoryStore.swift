import Foundation
import Observation

/// How often and how recently each song was played, for the Stats tab.
@MainActor @Observable
final class PlayHistoryStore {
    struct Entry: Codable, Equatable {
        var count: Int
        var lastPlayed: Date
    }

    private struct HistoryFile: Codable {
        var entries: [String: Entry]
        var totalPlays: Int
        var totalSeconds: TimeInterval
    }

    /// Songs last played longer ago than this are listed as "not played lately".
    static let staleAfterDays = 30

    private(set) var entries: [String: Entry]
    /// All plays ever counted, including songs that have since left the library.
    private(set) var totalPlays: Int
    private(set) var totalSeconds: TimeInterval
    @ObservationIgnored private let fileURL: URL

    nonisolated static func isEphemeralTrackID(_ id: String) -> Bool {
        id.hasPrefix(Track.ephemeralIDPrefix)
    }

    init(directory: URL) {
        fileURL = directory.appendingPathComponent("history.json")
        let file = Persistence.load(HistoryFile.self, from: fileURL)
        entries = (file?.entries ?? [:]).filter { !Self.isEphemeralTrackID($0.key) }
        totalPlays = file?.totalPlays ?? 0
        totalSeconds = file?.totalSeconds ?? 0
    }

    func save() {
        try? Persistence.save(HistoryFile(entries: entries, totalPlays: totalPlays, totalSeconds: totalSeconds), to: fileURL)
    }

    func recordPlay(_ id: String, at date: Date = Date()) {
        guard !Self.isEphemeralTrackID(id) else { return }
        entries[id] = Entry(count: (entries[id]?.count ?? 0) + 1, lastPlayed: date)
        totalPlays += 1
        save()
    }

    /// Notes that a song has started playing: it becomes "recently played" without gaining a play.
    func markStarted(_ id: String, at date: Date = Date()) {
        guard !Self.isEphemeralTrackID(id) else { return }
        entries[id] = Entry(count: entries[id]?.count ?? 0, lastPlayed: date)
        save()
    }

    /// Adds listening time in memory; callers flush with `save()`.
    func addListening(_ seconds: TimeInterval) {
        guard seconds > 0 else { return }
        totalSeconds += seconds
    }

    func removeTracks(_ ids: Set<String>) {
        guard ids.contains(where: { entries[$0] != nil }) else { return }
        for id in ids { entries[id] = nil }
        save()
    }

    func count(for id: String) -> Int { entries[id]?.count ?? 0 }
    func lastPlayed(for id: String) -> Date? { entries[id]?.lastPlayed }

    // MARK: Lists

    /// Songs that have been started at least once, with their history.
    private func started(_ tracks: [Track]) -> [(track: Track, entry: Entry)] {
        tracks.compactMap { track in
            guard !track.isEphemeral, !Self.isEphemeralTrackID(track.id) else { return nil }
            return entries[track.id].map { (track, $0) }
        }
    }

    /// Songs with at least one counted play.
    private func played(_ tracks: [Track]) -> [(track: Track, entry: Entry)] {
        started(tracks).filter { $0.entry.count > 0 }
    }

    /// Most recent first. Includes songs that were started but not heard long enough to count.
    func recentlyPlayed(_ tracks: [Track]) -> [Track] {
        started(tracks).sorted { $0.entry.lastPlayed > $1.entry.lastPlayed }.map(\.track)
    }

    /// Highest count first; ties go to the more recently played.
    func mostPlayed(_ tracks: [Track]) -> [Track] {
        played(tracks).sorted {
            $0.entry.count != $1.entry.count ? $0.entry.count > $1.entry.count : $0.entry.lastPlayed > $1.entry.lastPlayed
        }.map(\.track)
    }

    /// Lowest count first among songs played at least once; ties go to the one played longer ago.
    func leastPlayed(_ tracks: [Track]) -> [Track] {
        played(tracks).sorted {
            $0.entry.count != $1.entry.count ? $0.entry.count < $1.entry.count : $0.entry.lastPlayed < $1.entry.lastPlayed
        }.map(\.track)
    }

    func neverPlayed(_ tracks: [Track]) -> [Track] {
        tracks.filter { !$0.isEphemeral && !Self.isEphemeralTrackID($0.id) && count(for: $0.id) == 0 }
    }

    /// Started before, but not within `staleAfterDays`; the longest-neglected first.
    func notPlayedLately(_ tracks: [Track], now: Date = Date()) -> [Track] {
        let cutoff = now.addingTimeInterval(-Double(Self.staleAfterDays) * 86_400)
        return started(tracks).filter { $0.entry.lastPlayed < cutoff }
            .sorted { $0.entry.lastPlayed < $1.entry.lastPlayed }.map(\.track)
    }
}
