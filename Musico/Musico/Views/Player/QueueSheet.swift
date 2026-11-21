import SwiftUI

/// The queue page as a sheet over the player.
struct QueueSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            QueuesView()
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
                }
        }
        .modifier(NewPlaylistAlert())
    }
}

/// All queues and the songs of the active one. Shown as its own tab and from the player.
struct QueuesView: View {
    @Environment(PlayerController.self) private var player
    @Environment(LibraryStore.self) private var library
    @Environment(QueueStore.self) private var queues
    @State private var renaming: PlayQueue?
    @State private var name = ""
    @State private var confirmClear = false

    var body: some View {
        List {
            Section("Queues") {
                ForEach(queues.queues) { queue in
                    queueRow(queue)
                }
                Button("New Queue", systemImage: "plus") { queues.addQueue(name: nil) }
            }
            Section(queues.active.name) {
                let active = queues.active
                if active.trackIDs.isEmpty {
                    Text("This queue is empty. Tap a song in your library to fill it.")
                        .foregroundStyle(.secondary)
                }
                // A song can be queued more than once, so rows are identified by position.
                ForEach(Array(active.trackIDs.enumerated()), id: \.offset) { index, id in
                    Button {
                        player.jump(to: index)
                    } label: {
                        if let track = library.track(id) {
                            TrackRow(track: track)
                        } else {
                            Text("Missing song").foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        TrackMenuItems(ids: [id], favouriteID: id)
                        Divider()
                        Button("Remove from Queue", systemImage: "minus.circle", role: .destructive) {
                            player.removeFromQueue(at: index)
                        }
                    }
                    .listRowBackground(index == active.currentIndex ? Color.accentColor.opacity(0.12) : nil)
                }
                .onDelete { offsets in
                    for index in offsets.sorted(by: >) { player.removeFromQueue(at: index) }
                }
                .onMove { player.moveInQueue(from: $0, to: $1) }
            }
        }
        .navigationTitle("Queues")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) { EditButton() }
            ToolbarItem(placement: .topBarTrailing) {
                Button("Clear This Queue", systemImage: "trash") { confirmClear = true }
                    .disabled(queues.active.trackIDs.isEmpty)
            }
        }
        .confirmationDialog("Remove every song from “\(queues.active.name)”?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Clear Queue", role: .destructive) { player.clearQueue() }
        }
        .alert("Rename Queue", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $name)
            Button("Cancel", role: .cancel) {}
            Button("Rename") {
                if let renaming { queues.rename(renaming.id, to: name) }
            }
        }
    }

    private func queueRow(_ queue: PlayQueue) -> some View {
        let isActive = queue.id == queues.activeID
        return Button {
            player.switchQueue(to: queue.id)
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(queue.name).foregroundStyle(.primary)
                    QueueSummary(queue: queue, isActive: isActive)
                }
                Spacer()
                if isActive { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing) {
            if queues.queues.count > 1 {
                Button("Delete", systemImage: "trash", role: .destructive) { player.deleteQueue(queue.id) }
            }
            Button("Rename", systemImage: "pencil") {
                name = queue.name
                renaming = queue
            }
        }
    }

}

/// Its own view so the playback clock only redraws this line, not the whole queue list.
private struct QueueSummary: View {
    let queue: PlayQueue
    let isActive: Bool
    @Environment(PlayerController.self) private var player
    @Environment(LibraryStore.self) private var library

    var body: some View {
        Text(text).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
    }

    private var text: String {
        guard let id = queue.currentTrackID, let track = library.track(id) else { return "Empty" }
        return "\(queue.trackIDs.count) songs · \(track.title) at \(formatTime(isActive ? player.currentTime : queue.position))"
    }
}
