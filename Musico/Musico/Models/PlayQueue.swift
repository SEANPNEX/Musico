import Foundation

/// One play queue. Pure value logic; playback lives in `PlayerController`.
/// Invariant: when `shuffle` is on, `shuffleOrder` is a permutation of `trackIDs.indices`; otherwise it is empty.
struct PlayQueue: Codable, Identifiable, Hashable, Sendable {
    var id = UUID()
    var name: String
    private(set) var trackIDs: [String] = []
    private(set) var currentIndex: Int?
    var position: TimeInterval = 0
    private(set) var shuffle = false
    var repeatMode: RepeatMode = .off
    private(set) var shuffleOrder: [Int] = []
    var sortOrder: TrackSortOrder? = nil

    init(name: String) { self.name = name }

    var currentTrackID: String? {
        guard let currentIndex, trackIDs.indices.contains(currentIndex) else { return nil }
        return trackIDs[currentIndex]
    }

    /// Track indices in the order they play.
    private var order: [Int] { shuffle ? shuffleOrder : Array(trackIDs.indices) }

    private var orderPosition: Int? {
        guard let currentIndex else { return nil }
        return shuffle ? shuffleOrder.firstIndex(of: currentIndex) : currentIndex
    }

    var upcomingIndices: [Int] {
        guard let pos = orderPosition else { return [] }
        return Array(order.dropFirst(pos + 1))
    }

    mutating func replace(with ids: [String], startAt index: Int) {
        trackIDs = ids
        currentIndex = ids.isEmpty ? nil : min(max(index, 0), ids.count - 1)
        position = 0
        sortOrder = nil
        var rng = SystemRandomNumberGenerator()
        reshuffle(using: &rng)
    }

    /// Replaces the track list while keeping the currently playing track active if it remains in `ids`.
    mutating func replacePreservingPlayback(with ids: [String]) {
        guard let currentID = currentTrackID, let preservedIndex = ids.firstIndex(of: currentID) else {
            replace(with: ids, startAt: 0)
            return
        }
        trackIDs = ids
        currentIndex = preservedIndex
        sortOrder = nil
        var rng = SystemRandomNumberGenerator()
        reshuffle(using: &rng)
    }

    /// Advances and returns the new current track. `auto` (track finished by itself) honours repeat-one.
    /// Returns nil, leaving the index unchanged, at the end of the queue unless repeat is `.all`.
    mutating func next(auto: Bool) -> String? {
        guard let pos = orderPosition else { return nil }
        if auto, repeatMode == .one {
            position = 0
            return currentTrackID
        }
        let order = self.order
        if pos + 1 < order.count {
            currentIndex = order[pos + 1]
        } else if repeatMode == .all {
            currentIndex = order[0]
        } else {
            return nil
        }
        position = 0
        return currentTrackID
    }

    /// Steps back; wraps to the end only with repeat `.all`, otherwise stays on the first track.
    mutating func previous() -> String? {
        guard let pos = orderPosition else { return nil }
        let order = self.order
        if pos > 0 {
            currentIndex = order[pos - 1]
        } else if repeatMode == .all {
            currentIndex = order[order.count - 1]
        }
        position = 0
        return currentTrackID
    }

    mutating func jump(to index: Int) {
        guard trackIDs.indices.contains(index) else { return }
        currentIndex = index
        position = 0
    }

    mutating func insertNext(_ ids: [String]) {
        var seen = Set<String>()
        let dedup = ids.filter { seen.insert($0).inserted }
        guard !dedup.isEmpty else { return }

        if trackIDs.isEmpty {
            trackIDs = dedup
            currentIndex = 0
            position = 0
            shuffleOrder = shuffle ? Array(0..<dedup.count) : []
            return
        }

        let activeID = currentTrackID
        let filtered = dedup.filter { $0 != activeID }
        guard !filtered.isEmpty else { return }

        let filteredSet = Set(filtered)
        for index in trackIDs.indices.reversed() where filteredSet.contains(trackIDs[index]) && index != currentIndex {
            remove(at: index)
        }

        let insertAt = currentIndex.map { $0 + 1 } ?? 0
        let orderPos = orderPosition
        trackIDs.insert(contentsOf: filtered, at: insertAt)
        let newIndices = Array(insertAt..<(insertAt + filtered.count))
        if shuffle {
            shuffleOrder = shuffleOrder.map { $0 >= insertAt ? $0 + filtered.count : $0 }
            shuffleOrder.insert(contentsOf: newIndices, at: orderPos.map { $0 + 1 } ?? 0)
        }
        if currentIndex == nil { currentIndex = 0; position = 0 }
    }

    mutating func restore(at index: Int, trackID: String, makeCurrent: Bool, restoredPosition: TimeInterval = 0) {
        let insertAt = min(max(index, 0), trackIDs.count)
        let orderPos = orderPosition
        trackIDs.insert(trackID, at: insertAt)
        if shuffle {
            shuffleOrder = shuffleOrder.map { $0 >= insertAt ? $0 + 1 : $0 }
            shuffleOrder.insert(insertAt, at: orderPos.map { $0 + 1 } ?? 0)
        }
        if makeCurrent {
            currentIndex = insertAt
            position = restoredPosition
        } else if let current = currentIndex, current >= insertAt {
            currentIndex = current + 1
        } else if currentIndex == nil {
            currentIndex = 0
            position = 0
        }
    }

    mutating func append(_ ids: [String]) {
        guard !ids.isEmpty else { return }
        let start = trackIDs.count
        trackIDs.append(contentsOf: ids)
        if shuffle { shuffleOrder.append(contentsOf: start..<trackIDs.count) }
        if currentIndex == nil { currentIndex = start; position = 0 }
    }

    mutating func remove(at index: Int) {
        guard trackIDs.indices.contains(index) else { return }
        var successor: Int?
        let removingCurrent = index == currentIndex
        if removingCurrent, let pos = orderPosition {
            let order = self.order
            if pos + 1 < order.count { successor = order[pos + 1] }
            else if pos > 0 { successor = order[pos - 1] }
        }
        trackIDs.remove(at: index)
        if shuffle {
            shuffleOrder = shuffleOrder.filter { $0 != index }.map { $0 > index ? $0 - 1 : $0 }
        }
        if removingCurrent {
            currentIndex = successor.map { $0 > index ? $0 - 1 : $0 }
            position = 0
        } else if let current = currentIndex, current > index {
            currentIndex = current - 1
        }
    }

    mutating func move(from source: IndexSet, to destination: Int) {
        var oldIndices = Array(trackIDs.indices)
        oldIndices.move(fromOffsets: source, toOffset: destination)
        applyOrder(oldIndices, sortOrder: .custom)
    }

    mutating func applyOrder(_ oldIndices: [Int], sortOrder: TrackSortOrder) {
        guard oldIndices.count == trackIDs.count else { return }
        trackIDs = oldIndices.map { trackIDs[$0] }
        var newPosition = Array(repeating: 0, count: oldIndices.count)
        for (new, old) in oldIndices.enumerated() { newPosition[old] = new }
        currentIndex = currentIndex.map { newPosition[$0] }
        shuffleOrder = shuffleOrder.map { newPosition[$0] }
        self.sortOrder = sortOrder
    }

    mutating func sort(
        by order: TrackSortOrder,
        tracksByID: [String: Track],
        playCount: (String) -> Int,
        lastPlayed: ((String) -> Date?)? = nil
    ) {
        if order == .custom {
            sortOrder = .custom
            return
        }
        let oldIndices = TrackSorter.sortedIndexOrder(
            for: trackIDs,
            tracksByID: tracksByID,
            by: order,
            playCount: playCount,
            lastPlayed: lastPlayed
        )
        applyOrder(oldIndices, sortOrder: order)
    }

    mutating func setShuffle(_ on: Bool, using rng: inout some RandomNumberGenerator) {
        shuffle = on
        reshuffle(using: &rng)
    }

    /// Drops every track whose id is not in `valid` (files that left the library).
    mutating func removeTracks(notIn valid: Set<String>) {
        for index in trackIDs.indices.reversed() where !valid.contains(trackIDs[index]) {
            remove(at: index)
        }
    }

    @discardableResult
    mutating func remapTracks(_ map: [String: String]) -> Bool {
        guard !map.isEmpty else { return false }
        let updated = trackIDs.map { map[$0] ?? $0 }
        guard updated != trackIDs else { return false }
        trackIDs = updated
        return true
    }

    private mutating func reshuffle(using rng: inout some RandomNumberGenerator) {
        guard shuffle else { shuffleOrder = []; return }
        var rest = trackIDs.indices.filter { $0 != currentIndex }
        rest.shuffle(using: &rng)
        shuffleOrder = (currentIndex.map { [$0] } ?? []) + rest
    }
}
