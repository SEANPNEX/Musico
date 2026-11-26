import SwiftUI

/// The queue page as a sheet over the player.
struct QueueSheet: View {
    @Environment(\.themeManager) private var themeManager
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            QueuesView()
                .background { ThemeBackdropView() }
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) { Button(L10n.tr("Done")) { dismiss() } }
                }
        }
        .applyTheme(themeManager)
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
    @Environment(\.languageManager) private var languageManager
    @Environment(\.themeManager) private var themeManager
    @State private var renaming: PlayQueue?
    @State private var clearingQueue: PlayQueue?

    var body: some View {
        let canDeleteQueues = queues.queues.count > 1
        let viewed = queues.viewed
        queueContent(canDeleteQueues: canDeleteQueues, viewed: viewed)
            .navigationTitle(L10n.tr("Queues"))
            #if targetEnvironment(macCatalyst)
            .navigationBarTitleDisplayMode(.inline)
            #else
            .navigationBarTitleDisplayMode(embeddedInInspector ? .inline : .automatic)
            #endif
            .toolbar {
                #if !targetEnvironment(macCatalyst)
                ToolbarItem(placement: .topBarLeading) { EditButton() }
                #endif
                ToolbarItemGroup(placement: .topBarTrailing) {
                    TrackSortMenu(selection: viewed.sortOrder ?? .custom) { order in
                        player.sortQueue(viewed.id, by: order)
                    }
                    .disabled(viewed.trackIDs.count < 2)

                    Button(L10n.tr("Clear This Queue"), systemImage: "eraser") { clearingQueue = viewed }
                        .disabled(viewed.trackIDs.isEmpty)
                        .help(L10n.tr("Clear This Queue"))

                    #if targetEnvironment(macCatalyst)
                    Button(L10n.tr("Delete Queue"), systemImage: "trash", role: .destructive) {
                        player.deleteQueue(viewed.id)
                    }
                    .disabled(!canDeleteQueues)
                    .help(canDeleteQueues ? L10n.tr("Delete Queue") : L10n.tr("At least one queue is required"))
                    #endif

                    if let onDone {
                        Button(L10n.tr("Done")) { onDone() }
                    }
                }
            }
            .confirmationDialog(
                L10n.tr("Remove every song from “%@”?", clearingQueue.map { L10n.displayQueueName($0.name) } ?? ""),
                isPresented: Binding(get: { clearingQueue != nil }, set: { if !$0 { clearingQueue = nil } }),
                titleVisibility: .visible,
                presenting: clearingQueue
            ) { target in
                Button(L10n.tr("Clear Queue"), role: .destructive) { player.clearQueue(target.id) }
                Button(L10n.tr("Cancel"), role: .cancel) {}
            }
            .sheet(item: $renaming) { queue in
                NamePromptSheet(
                    title: L10n.tr("Rename Queue"),
                    subtitle: L10n.tr("Choose a new name for this queue."),
                    placeholder: L10n.tr("Queue name"),
                    confirmLabel: L10n.tr("Rename"),
                    systemImage: "list.bullet",
                    initialName: queue.name
                ) { trimmed in
                    queues.rename(queue.id, to: trimmed)
                }
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
                .applyTheme(themeManager)
            }
    }

    @ViewBuilder
    private func queueContent(canDeleteQueues: Bool, viewed: PlayQueue) -> some View {
        #if targetEnvironment(macCatalyst)
        macQueuesBody(canDeleteQueues: canDeleteQueues, viewed: viewed)
        #else
        iosQueuesList(canDeleteQueues: canDeleteQueues, viewed: viewed)
        #endif
    }

    #if targetEnvironment(macCatalyst)
    private func macQueuesBody(canDeleteQueues: Bool, viewed: PlayQueue) -> some View {
        let isViewingPlayingQueue = viewed.id == queues.playingQueueID
        let scopeID = "queue-\(viewed.id.uuidString)"
        return ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text(L10n.tr("Queues"))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .textCase(.uppercase)
                        Spacer()
                        Button {
                            let newID = queues.addQueue(name: nil)
                            queues.selectViewedQueue(newID)
                        } label: {
                            Label(L10n.tr("New Queue"), systemImage: "plus")
                                .font(.caption.weight(.medium))
                        }
                        .buttonStyle(.borderless)
                    }
                    .padding(.horizontal, 4)

                    VStack(spacing: 0) {
                        ForEach(Array(queues.queues.enumerated()), id: \.element.id) { idx, queue in
                            let isPlayingQueue = queue.id == queues.playingQueueID
                            let isViewed = queue.id == viewed.id
                            MacQueueRow(
                                queue: queue,
                                isPlayingQueue: isPlayingQueue,
                                isViewed: isViewed,
                                isAudioPlaying: player.isPlaying || player.isBuffering,
                                canDelete: canDeleteQueues,
                                onSelect: { queues.selectViewedQueue(queue.id) },
                                onRename: { renaming = queue },
                                onClear: { clearingQueue = queue },
                                onDelete: { player.deleteQueue(queue.id) }
                            )
                            if idx < queues.queues.count - 1 {
                                Divider().padding(.leading, 12)
                            }
                        }
                    }
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5)
                            .allowsHitTesting(false)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }

                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 6) {
                        Text(L10n.displayQueueName(viewed.name))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .textCase(.uppercase)
                            .lineLimit(1)
                        if isViewingPlayingQueue {
                            Label(L10n.tr("Playing"), systemImage: (player.isPlaying || player.isBuffering) ? "speaker.wave.2.fill" : "speaker.fill")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(themeManager.accentColor)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(themeManager.accentColor.opacity(0.14), in: Capsule())
                        }
                        Spacer()
                        if !viewed.trackIDs.isEmpty {
                            Text(L10n.songCount(viewed.trackIDs.count))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 4)

                    Group {
                        if viewed.trackIDs.isEmpty {
                            Text(L10n.tr("This queue is empty. Tap a song in your library to fill it."))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(14)
                        } else {
                            LazyVStack(spacing: 0) {
                                ForEach(Array(viewed.trackIDs.enumerated()), id: \.offset) { index, id in
                                    MacQueueTrackRow(
                                        track: library.track(id),
                                        trackID: id,
                                        index: index,
                                        scopeID: scopeID,
                                        isActive: isViewingPlayingQueue && index == viewed.currentIndex,
                                        onSelect: { player.playTrack(inQueue: viewed.id, at: index) },
                                        onMove: { from, to in
                                            player.moveInQueue(from: IndexSet(integer: from), to: to, inQueue: viewed.id)
                                        },
                                        onRemove: { player.removeFromQueue(at: index, inQueue: viewed.id) }
                                    )
                                    if index < viewed.trackIDs.count - 1 {
                                        Divider().padding(.leading, 68)
                                    }
                                }
                            }
                        }
                    }
                    .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5)
                            .allowsHitTesting(false)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
            }
            .padding(16)
        }
        .background(themeManager.hasCustomBackdrop ? Color.clear : Color(.systemGroupedBackground))
    }
    #endif

    private func iosQueuesList(canDeleteQueues: Bool, viewed: PlayQueue) -> some View {
        let isViewingPlayingQueue = viewed.id == queues.playingQueueID
        return List {
            Section(L10n.tr("Queues")) {
                ForEach(queues.queues) { queue in
                    let isPlayingQueue = queue.id == queues.playingQueueID
                    let isViewed = queue.id == viewed.id
                    QueueRowView(
                        queue: queue,
                        isPlayingQueue: isPlayingQueue,
                        isViewed: isViewed,
                        isAudioPlaying: player.isPlaying || player.isBuffering,
                        canDelete: canDeleteQueues,
                        onSelect: { queues.selectViewedQueue(queue.id) },
                        onRename: { renaming = queue },
                        onClear: { clearingQueue = queue },
                        onDelete: { player.deleteQueue(queue.id) }
                    )
                    .deleteDisabled(!canDeleteQueues)
                }
                .onDelete { offsets in
                    let ids = offsets.compactMap { queues.queues.indices.contains($0) ? queues.queues[$0].id : nil }
                    for id in ids where queues.queues.count > 1 {
                        player.deleteQueue(id)
                    }
                }
                Button {
                    let newID = queues.addQueue(name: nil)
                    queues.selectViewedQueue(newID)
                } label: {
                    Label(L10n.tr("New Queue"), systemImage: "plus")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
            }
            Section {
                if viewed.trackIDs.isEmpty {
                    Text(L10n.tr("This queue is empty. Tap a song in your library to fill it."))
                        .foregroundStyle(.secondary)
                }
                // A song can be queued more than once, so rows are identified by position.
                ForEach(Array(viewed.trackIDs.enumerated()), id: \.offset) { index, id in
                    let isRowActive = isViewingPlayingQueue && index == viewed.currentIndex
                    Group {
                        if let track = library.track(id) {
                            ReorderableTrackRow(
                                track: track,
                                index: index,
                                scopeID: "queue-\(viewed.id.uuidString)",
                                isActive: isRowActive,
                                onTap: { player.playTrack(inQueue: viewed.id, at: index) },
                                onMove: { from, to in
                                    player.moveInQueue(from: IndexSet(integer: from), to: to, inQueue: viewed.id)
                                },
                                onRemove: { player.removeFromQueue(at: index, inQueue: viewed.id) },
                                removeHelp: L10n.tr("Remove from Queue")
                            )
                        } else {
                            Button {
                                player.playTrack(inQueue: viewed.id, at: index)
                            } label: {
                                Text(L10n.tr("Missing song"))
                                    .foregroundStyle(.secondary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.borderless)
                            .listRowBackground(isRowActive ? themeManager.accentColor.opacity(0.12) : nil)
                        }
                    }
                    .contextMenu {
                        let isEphemeral = library.track(id)?.isEphemeral == true || id.hasPrefix(Track.ephemeralIDPrefix)
                        TrackMenuItems(ids: [id], favouriteID: isEphemeral ? nil : id)
                        Divider()
                        Button(L10n.tr("Remove from Queue"), systemImage: "minus.circle", role: .destructive) {
                            player.removeFromQueue(at: index, inQueue: viewed.id)
                        }
                    }
                }
                .onDelete { offsets in
                    for index in offsets.sorted(by: >) { player.removeFromQueue(at: index, inQueue: viewed.id) }
                }
                .onMove { player.moveInQueue(from: $0, to: $1, inQueue: viewed.id) }
            } header: {
                HStack(spacing: 6) {
                    MarqueeText(
                        text: L10n.displayQueueName(viewed.name),
                        font: .footnote.weight(.semibold),
                        alignment: .leading
                    )
                    if isViewingPlayingQueue {
                        Label(L10n.tr("Playing"), systemImage: (player.isPlaying || player.isBuffering) ? "speaker.wave.2.fill" : "speaker.fill")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(themeManager.accentColor)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(themeManager.accentColor.opacity(0.14), in: Capsule())
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .textCase(nil)
        }
        .hiddenScrollBackgroundIfThemed(themeManager)
    }
}

#if targetEnvironment(macCatalyst)
private struct MacQueueRow: View {
    let queue: PlayQueue
    let isPlayingQueue: Bool
    let isViewed: Bool
    let isAudioPlaying: Bool
    let canDelete: Bool
    let onSelect: () -> Void
    let onRename: () -> Void
    let onClear: () -> Void
    let onDelete: () -> Void

    @Environment(\.themeManager) private var themeManager
    @State private var isHovered = false
    @State private var isRenameHovered = false
    @State private var isClearHovered = false
    @State private var isDeleteHovered = false

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onSelect) {
                HStack(spacing: 8) {
                    Group {
                        if isPlayingQueue {
                            Image(systemName: isAudioPlaying ? "speaker.wave.2.fill" : "speaker.fill")
                                .font(.caption.weight(.bold))
                                .foregroundStyle(themeManager.accentColor)
                        } else {
                            Image(systemName: "eye")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(themeManager.accentColor)
                                .opacity(isViewed ? 1 : 0)
                        }
                    }
                    .frame(width: 14)

                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(L10n.displayQueueName(queue.name))
                                .font(.subheadline.weight(isViewed || isPlayingQueue ? .semibold : .medium))
                                .foregroundStyle(isViewed ? themeManager.accentColor : .primary)
                                .lineLimit(1)
                            if isPlayingQueue {
                                Text(L10n.tr("Playing"))
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(themeManager.accentColor)
                                    .padding(.horizontal, 5)
                                    .padding(.vertical, 1)
                                    .background(themeManager.accentColor.opacity(0.14), in: Capsule())
                            }
                        }
                        QueueSummary(queue: queue, isPlayingQueue: isPlayingQueue)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
            }
            .buttonStyle(.plain)

            HStack(spacing: 2) {
                Button(action: onRename) {
                    Image(systemName: "pencil")
                        .font(.subheadline)
                        .foregroundStyle(isRenameHovered ? Color.primary : Color.secondary)
                        .frame(width: 28, height: 28)
                        .background(
                            isRenameHovered ? Color.primary.opacity(0.08) : .clear,
                            in: RoundedRectangle(cornerRadius: 6, style: .continuous)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .onHover { isRenameHovered = $0 }
                .help(L10n.tr("Rename…"))
                .accessibilityLabel(L10n.tr("Rename…"))

                if !queue.trackIDs.isEmpty {
                    Button(action: onClear) {
                        Image(systemName: "eraser")
                            .font(.subheadline)
                            .foregroundStyle(isClearHovered ? Color.primary : Color.secondary)
                            .frame(width: 28, height: 28)
                            .background(
                                isClearHovered ? Color.primary.opacity(0.08) : .clear,
                                in: RoundedRectangle(cornerRadius: 6, style: .continuous)
                            )
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .onHover { isClearHovered = $0 }
                    .help(L10n.tr("Clear Queue"))
                    .accessibilityLabel(L10n.tr("Clear Queue"))
                }

                Button(action: onDelete) {
                    Image(systemName: "trash")
                        .font(.subheadline)
                        .foregroundStyle(canDelete ? (isDeleteHovered ? Color.red : Color.secondary) : Color.secondary.opacity(0.35))
                        .frame(width: 28, height: 28)
                        .background(
                            canDelete && isDeleteHovered ? Color.red.opacity(0.12) : .clear,
                            in: RoundedRectangle(cornerRadius: 6, style: .continuous)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .disabled(!canDelete)
                .onHover { isDeleteHovered = $0 }
                .help(canDelete ? L10n.tr("Delete Queue") : L10n.tr("At least one queue is required"))
                .accessibilityLabel(L10n.tr("Delete Queue"))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            isViewed
                ? themeManager.accentColor.opacity(0.10)
                : (isHovered ? Color.primary.opacity(0.04) : .clear)
        )
        .onHover { isHovered = $0 }
        .contextMenu {
            if !isViewed {
                Button(L10n.tr("View Queue"), systemImage: "eye", action: onSelect)
            }
            Button(L10n.tr("Rename…"), systemImage: "pencil", action: onRename)
            if !queue.trackIDs.isEmpty {
                Button(L10n.tr("Clear Queue"), systemImage: "eraser", role: .destructive, action: onClear)
            }
            if canDelete {
                Divider()
                Button(L10n.tr("Delete Queue"), systemImage: "trash", role: .destructive, action: onDelete)
            }
        }
    }
}

private struct MacQueueTrackRow: View {
    let track: Track?
    let trackID: String
    let index: Int
    let scopeID: String
    let isActive: Bool
    let onSelect: () -> Void
    let onMove: (Int, Int) -> Void
    let onRemove: () -> Void

    @Environment(\.themeManager) private var themeManager
    @State private var isHovered = false
    @State private var isRemoveHovered = false
    @State private var isDropTargeted = false

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onSelect) {
                Group {
                    if let track {
                        TrackRow(track: track)
                    } else {
                        Text(L10n.tr("Missing song"))
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button(action: onRemove) {
                Image(systemName: "minus.circle")
                    .font(.subheadline)
                    .foregroundStyle(isRemoveHovered ? Color.red : Color.secondary)
                    .frame(width: 28, height: 28)
                    .background(
                        isRemoveHovered ? Color.red.opacity(0.12) : .clear,
                        in: RoundedRectangle(cornerRadius: 6, style: .continuous)
                    )
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .onHover { isRemoveHovered = $0 }
            .help(L10n.tr("Remove from Queue"))
            .accessibilityLabel(L10n.tr("Remove from Queue"))
            .opacity(isHovered ? 1 : 0)
            .allowsHitTesting(isHovered)

            Image(systemName: "line.3.horizontal")
                .font(.body)
                .foregroundStyle(.tertiary)
                .frame(width: 28, height: 36)
                .contentShape(Rectangle())
                .accessibilityLabel(L10n.tr("Reorder %@", track?.title ?? L10n.tr("Missing song")))
                .draggable("\(scopeID):\(index)") {
                    Text(track?.title ?? L10n.tr("Missing song"))
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
                }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(
            isDropTargeted
                ? themeManager.accentColor.opacity(0.20)
                : (isActive
                    ? themeManager.accentColor.opacity(0.12)
                    : (isHovered ? Color.primary.opacity(0.04) : .clear))
        )
        .onHover { isHovered = $0 }
        .dropDestination(for: String.self) { items, _ in
            guard let payload = items.first,
                  let sourceIndex = ReorderableTrackRow.parseSourceIndex(payload, scopeID: scopeID),
                  sourceIndex != index else {
                return false
            }
            let destination = sourceIndex < index ? index + 1 : index
            onMove(sourceIndex, destination)
            return true
        } isTargeted: { targeted in
            isDropTargeted = targeted
        }
        .contextMenu {
            let isEphemeral = track?.isEphemeral == true || trackID.hasPrefix(Track.ephemeralIDPrefix)
            TrackMenuItems(ids: [trackID], favouriteID: isEphemeral ? nil : trackID)
            Divider()
            Button(L10n.tr("Remove from Queue"), systemImage: "minus.circle", role: .destructive, action: onRemove)
        }
    }
}
#endif

private struct QueueRowView: View {
    let queue: PlayQueue
    let isPlayingQueue: Bool
    let isViewed: Bool
    let isAudioPlaying: Bool
    let canDelete: Bool
    let onSelect: () -> Void
    let onRename: () -> Void
    let onClear: () -> Void
    let onDelete: () -> Void

    @Environment(\.themeManager) private var themeManager
    @State private var isHovered = false
    @State private var isDeleteHovered = false

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onSelect) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(L10n.displayQueueName(queue.name))
                            .fontWeight(isViewed || isPlayingQueue ? .semibold : .regular)
                            .foregroundStyle(isViewed ? themeManager.accentColor : .primary)
                            .lineLimit(2)
                        if isPlayingQueue {
                            Label(
                                L10n.tr("Playing"),
                                systemImage: isAudioPlaying ? "speaker.wave.2.fill" : "speaker.fill"
                            )
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(themeManager.accentColor)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(themeManager.accentColor.opacity(0.14), in: Capsule())
                        }
                    }
                    QueueSummary(queue: queue, isPlayingQueue: isPlayingQueue)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)

            HStack(spacing: 4) {
                if isViewed {
                    Image(systemName: "checkmark")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(themeManager.accentColor)
                }

                Menu {
                    queueMenuItems
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .frame(width: 26, height: 26)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L10n.tr("More"))

                if canDelete {
                    Button(action: onDelete) {
                        Image(systemName: "trash")
                            .font(.subheadline)
                            .foregroundStyle(isDeleteHovered ? Color.red : Color.secondary)
                            .frame(width: 26, height: 26)
                            .background(
                                isDeleteHovered ? Color.red.opacity(0.12) : .clear,
                                in: RoundedRectangle(cornerRadius: 6, style: .continuous)
                            )
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .onHover { isDeleteHovered = $0 }
                    .help(L10n.tr("Delete Queue"))
                    .accessibilityLabel(L10n.tr("Delete Queue"))
                }
            }
        }
        .onHover { isHovered = $0 }
        .listRowBackground(
            isViewed
                ? themeManager.accentColor.opacity(0.10)
                : (isHovered ? Color.primary.opacity(0.04) : nil)
        )
        .contextMenu { queueMenuItems }
        .swipeActions(edge: .trailing) {
            if canDelete {
                Button(L10n.tr("Delete"), systemImage: "trash", role: .destructive, action: onDelete)
            }
            Button(L10n.tr("Rename"), systemImage: "pencil", action: onRename)
        }
    }

    @ViewBuilder
    private var queueMenuItems: some View {
        if !isViewed {
            Button(L10n.tr("View Queue"), systemImage: "eye", action: onSelect)
        }
        Button(L10n.tr("Rename…"), systemImage: "pencil", action: onRename)
        if !queue.trackIDs.isEmpty {
            Button(L10n.tr("Clear Queue"), systemImage: "eraser", role: .destructive, action: onClear)
        }
        if canDelete {
            Divider()
            Button(L10n.tr("Delete Queue"), systemImage: "trash", role: .destructive, action: onDelete)
        }
    }
}

/// Its own view so the playback clock only redraws this line, not the whole queue list.
private struct QueueSummary: View {
    let queue: PlayQueue
    let isPlayingQueue: Bool
    @Environment(PlayerController.self) private var player
    @Environment(LibraryStore.self) private var library

    var body: some View {
        Text(text).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
    }

    private var text: String {
        guard let id = queue.currentTrackID, let track = library.track(id) else { return L10n.tr("Empty") }
        return L10n.tr("%@ · %@ at %@", L10n.songCount(queue.trackIDs.count), track.title, formatTime(isPlayingQueue ? player.currentTime : queue.position))
    }
}
