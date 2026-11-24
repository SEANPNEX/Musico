import Foundation
import Observation

/// The set of play queues and which one is active. There is always at least one.
@MainActor @Observable
final class QueueStore {
    private struct QueueFile: Codable { var queues: [PlayQueue]; var activeID: UUID }

    private(set) var queues: [PlayQueue]
    private(set) var activeID: UUID
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored var onQueuesChanged: ((Set<String>) -> Void)?

    init(directory: URL) {
        fileURL = directory.appendingPathComponent("queues.json")
        if let file = Persistence.load(QueueFile.self, from: fileURL), !file.queues.isEmpty {
            queues = file.queues
            activeID = file.queues.contains { $0.id == file.activeID } ? file.activeID : file.queues[0].id
        } else {
            let first = PlayQueue(name: "Queue 1")
            queues = [first]
            activeID = first.id
        }
    }

    var active: PlayQueue { queues.first { $0.id == activeID } ?? queues[0] }
    var allReferencedTrackIDs: Set<String> { Set(queues.flatMap(\.trackIDs)) }

    func save() {
        try? Persistence.save(QueueFile(queues: queues, activeID: activeID), to: fileURL)
        onQueuesChanged?(allReferencedTrackIDs)
    }

    func update(_ id: UUID, _ body: (inout PlayQueue) -> Void) {
        guard let index = queues.firstIndex(where: { $0.id == id }) else { return }
        body(&queues[index])
        save()
    }

    /// Records the playback position without writing the file; callers flush with `save()`.
    /// Playback ticks use this so a long queue is not rewritten to disk every few seconds.
    func setActivePosition(_ time: TimeInterval) {
        guard let index = queues.firstIndex(where: { $0.id == activeID }) else { return }
        queues[index].position = time
    }

    func updateActive(_ body: (inout PlayQueue) -> Void) { update(activeID, body) }

    @discardableResult
    func addQueue(name: String?) -> UUID {
        var title = name?.trimmingCharacters(in: .whitespaces) ?? ""
        if title.isEmpty {
            let used = Set(queues.map(\.name))
            title = (1...).lazy.map { "Queue \($0)" }.first { !used.contains($0) }!
        }
        let queue = PlayQueue(name: title)
        queues.append(queue)
        save()
        return queue.id
    }

    /// Creates a queue with the exact given name, or replaces the existing same-name queue.
    /// When `startAt` is nil and the replaced queue is active, playback of the current song is preserved if possible.
    @discardableResult
    func createOrReplaceQueue(named name: String, trackIDs: [String], startAt: Int? = nil) -> UUID {
        let title = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty else {
            let id = addQueue(name: nil)
            update(id) { $0.replace(with: trackIDs, startAt: startAt ?? 0) }
            return id
        }
        if let index = queues.firstIndex(where: { $0.name == title }) {
            if let startAt {
                queues[index].replace(with: trackIDs, startAt: startAt)
            } else if queues[index].id == activeID {
                queues[index].replacePreservingPlayback(with: trackIDs)
            } else {
                queues[index].replace(with: trackIDs, startAt: 0)
            }
            save()
            return queues[index].id
        }
        var queue = PlayQueue(name: title)
        queue.replace(with: trackIDs, startAt: startAt ?? 0)
        queues.append(queue)
        save()
        return queue.id
    }

    func rename(_ id: UUID, to name: String) {
        let title = name.trimmingCharacters(in: .whitespaces)
        guard !title.isEmpty else { return }
        update(id) { $0.name = title }
    }

    /// Deletes a queue. The last remaining queue cannot be deleted.
    func delete(_ id: UUID) {
        guard queues.count > 1, let index = queues.firstIndex(where: { $0.id == id }) else { return }
        queues.remove(at: index)
        if activeID == id { activeID = queues[min(index, queues.count - 1)].id }
        save()
    }

    func setActive(_ id: UUID) {
        guard queues.contains(where: { $0.id == id }) else { return }
        activeID = id
        save()
    }

    /// Drops tracks that left the library from every queue.
    func removeTracks(_ ids: Set<String>) {
        for index in queues.indices {
            let valid = Set(queues[index].trackIDs).subtracting(ids)
            queues[index].removeTracks(notIn: valid)
        }
        save()
    }
}
