import SwiftUI

/// The queue page as a sheet over the player.
struct QueueSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            QueuesView()
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) { Button(L10n.tr("Done")) { dismiss() } }
                }
        }
        .modifier(NewPlaylistAlert())
        .modifier(CloudExportHostModifier())
        .modifier(SongInfoHostModifier())
    }
}

/// All queues and the songs of the active one. Shown as its own tab and from the player.
struct QueuesView: View {
    var embeddedInInspector: Bool = false
    var onDone: (() -> Void)? = nil

    @Environment(PlayerController.self) private var player
    @Environment(LibraryStore.self) private var library
    @Environment(QueueStore.self) private var queues
    @State private var renaming: PlayQueue?
    @State private var name = ""
    @State private var confirmClear = false

    var body: some View {
        List {
            Section(L10n.tr("Queues")) {
                ForEach(queues.queues) { queue in
                    queueRow(queue)
                }
                Button(L10n.tr("New Queue"), systemImage: "plus") { queues.addQueue(name: nil) }
            }
            Section(L10n.displayQueueName(queues.active.name)) {
                let active = queues.active
                if active.trackIDs.isEmpty {
                    Text(L10n.tr("This queue is empty. Tap a song in your library to fill it."))
                        .foregroundStyle(.secondary)
                }
                // A song can be queued more than once, so rows are identified by position.
                ForEach(Array(active.trackIDs.enumerated()), id: \.offset) { index, id in
                    Group {
                        if let track = library.track(id) {
                            ReorderableTrackRow(
                                track: track,
                                index: index,
                                scopeID: "queue-\(active.id.uuidString)",
                                isActive: index == active.currentIndex,
                                onTap: { player.jump(to: index) },
                                onMove: { from, to in
                                    player.moveInQueue(from: IndexSet(integer: from), to: to)
                                }
                            )
                        } else {
                            Button {
                                player.jump(to: index)
                            } label: {
                                Text(L10n.tr("Missing song")).foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                            .listRowBackground(index == active.currentIndex ? Color.accentColor.opacity(0.12) : nil)
                        }
                    }
                    .contextMenu {
                        TrackMenuItems(ids: [id], favouriteID: id)
                        Divider()
                        Button(L10n.tr("Remove from Queue"), systemImage: "minus.circle", role: .destructive) {
                            player.removeFromQueue(at: index)
                        }
                    }
                }
                .onDelete { offsets in
                    for index in offsets.sorted(by: >) { player.removeFromQueue(at: index) }
                }
                .onMove { player.moveInQueue(from: $0, to: $1) }
            }
        }
        .navigationTitle(L10n.tr("Queues"))
        #if targetEnvironment(macCatalyst)
        .navigationBarTitleDisplayMode(.inline)
        #else
        .navigationBarTitleDisplayMode(embeddedInInspector ? .inline : .automatic)
        #endif
        .toolbar {
            ToolbarItem(placement: .topBarLeading) { EditButton() }
            ToolbarItemGroup(placement: .topBarTrailing) {
                TrackSortMenu(selection: queues.active.sortOrder ?? .custom) { order in
                    player.sortActiveQueue(by: order)
                }
                .disabled(queues.active.trackIDs.count < 2)
                Button(L10n.tr("Clear This Queue"), systemImage: "trash") { confirmClear = true }
                    .disabled(queues.active.trackIDs.isEmpty)
                if let onDone {
                    Button(L10n.tr("Done")) { onDone() }
                }
            }
        }
        .confirmationDialog(L10n.tr("Remove every song from “%@”?", L10n.displayQueueName(queues.active.name)), isPresented: $confirmClear, titleVisibility: .visible) {
            Button(L10n.tr("Clear Queue"), role: .destructive) { player.clearQueue() }
        }
        .alert(L10n.tr("Rename Queue"), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(L10n.tr("Name"), text: $name)
            Button(L10n.tr("Cancel"), role: .cancel) {}
            Button(L10n.tr("Rename")) {
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
                    Text(L10n.displayQueueName(queue.name)).foregroundStyle(.primary)
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
                Button(L10n.tr("Delete"), systemImage: "trash", role: .destructive) { player.deleteQueue(queue.id) }
            }
            Button(L10n.tr("Rename"), systemImage: "pencil") {
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
        guard let id = queue.currentTrackID, let track = library.track(id) else { return L10n.tr("Empty") }
        return L10n.tr("%@ · %@ at %@", L10n.songCount(queue.trackIDs.count), track.title, formatTime(isActive ? player.currentTime : queue.position))
    }
}
