import SwiftUI

/// The full-screen player: this outer view owns the sheets and the two-column arrangement,
/// `PlayerPage` draws the controls.
struct FullPlayerView: View {
    enum Panel: String, Identifiable {
        case queue, lyrics, equalizer, sleep
        var id: String { rawValue }
    }

    /// What the right-hand column shows when there is room for two.
    enum SidePane: String, CaseIterable, Identifiable {
        case lyrics = "Lyrics", queue = "Queue"
        var id: String { rawValue }
    }

    /// Narrowest player that still fits the controls and a second column side by side.
    static let twoColumnWidth: CGFloat = 760

    @State private var panel: Panel?
    @AppStorage("playerSidePane") private var sidePane: SidePane = .lyrics

    var body: some View {
        GeometryReader { proxy in
            if proxy.size.width >= Self.twoColumnWidth {
                HStack(spacing: 0) {
                    PlayerPage(panel: columnPanel, showsClose: true)
                        .frame(maxWidth: .infinity)
                    Divider()
                    sideColumn
                        .frame(maxWidth: .infinity)
                }
            } else {
                PlayerPage(panel: $panel, showsClose: false)
            }
        }
        .presentationDragIndicator(.visible)
        .modifier(NewPlaylistAlert())
        .sheet(item: $panel) { panel in
            switch panel {
            case .queue: QueueSheet()
            case .lyrics: LyricsView()
            case .equalizer: EqualizerSheet()
            case .sleep: SleepTimerSheet().presentationDetents([.medium, .large])
            }
        }
    }

    /// With two columns, Queue and Lyrics switch the right-hand column instead of opening a sheet.
    private var columnPanel: Binding<Panel?> {
        Binding(get: { panel }, set: { target in
            switch target {
            case .queue: sidePane = .queue
            case .lyrics: sidePane = .lyrics
            default: panel = target
            }
        })
    }

    private var sideColumn: some View {
        VStack(spacing: 0) {
            Picker("Show", selection: $sidePane) {
                ForEach(SidePane.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 320)
            .padding(.top, 20)
            .padding(.bottom, 8)
            switch sidePane {
            case .lyrics: LyricsView(embedded: true)
            case .queue:
                NavigationStack {
                    QueuesView().navigationBarTitleDisplayMode(.inline)
                }
            }
        }
    }
}

private struct PlayerPage: View {
    typealias Panel = FullPlayerView.Panel

    @Binding var panel: Panel?
    /// A close button, for when the player fills the window and cannot be dragged away.
    let showsClose: Bool
    @Environment(PlayerController.self) private var player
    @Environment(LibraryStore.self) private var library
    @Environment(QueueStore.self) private var queues
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 20) {
            if showsClose {
                Button("Close", systemImage: "chevron.down") { dismiss() }
                    .labelStyle(.iconOnly)
                    .font(.title3.weight(.semibold))
                    .frame(width: 44, height: 32)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .keyboardShortcut(.cancelAction)
            }
            if let track = player.currentTrack {
                ArtworkView(artworkKey: track.artworkKey)
                    .padding(.horizontal, 12)
                    .shadow(color: .black.opacity(0.2), radius: 16, y: 8)
                    .frame(maxHeight: .infinity)
                    .onTapGesture { panel = .lyrics }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityLabel("Show lyrics")

                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(track.title).font(.title2.weight(.semibold)).lineLimit(2)
                        Text("\(track.artist) — \(track.album)").foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer()
                    Button("Favourite", systemImage: library.isFavourite(track.id) ? "heart.fill" : "heart") {
                        library.toggleFavourite(track.id)
                    }
                    .font(.title2)
                    .labelStyle(.iconOnly)
                }

                PlaybackScrubber()
                transport
                Text(queues.active.name + " · " + positionText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                panelButtons
            } else {
                ContentUnavailableView("Nothing Playing", systemImage: "music.note",
                                       description: Text("Pick a song from your library."))
                Button("Queues", systemImage: "list.bullet") { panel = .queue }
            }
        }
        // Keeps the player a comfortable size in wide iPad and Mac windows.
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.top, showsClose ? 12 : 28)
        .padding(.bottom, 16)
    }

    private var positionText: String {
        guard let index = queues.active.currentIndex else { return "" }
        return "\(index + 1) of \(queues.active.trackIDs.count)"
    }

    private var transport: some View {
        let queue = queues.active
        return HStack {
            Button("Shuffle", systemImage: "shuffle") { player.toggleShuffle() }
                .foregroundStyle(queue.shuffle ? Color.accentColor : .secondary)
            Spacer()
            Button("Previous", systemImage: "backward.fill") { player.previous() }.font(.title)
            Spacer()
            Button("Play or Pause", systemImage: player.isPlaying ? "pause.circle.fill" : "play.circle.fill") {
                player.togglePlayPause()
            }
            .font(.system(size: 68))
            Spacer()
            Button("Next", systemImage: "forward.fill") { player.next() }.font(.title)
            Spacer()
            Button("Repeat", systemImage: queue.repeatMode == .one ? "repeat.1" : "repeat") { player.cycleRepeat() }
                .foregroundStyle(queue.repeatMode == .off ? .secondary : Color.accentColor)
        }
        .labelStyle(.iconOnly)
        .font(.title3)
        .tint(.primary)
    }

    private var panelButtons: some View {
        HStack {
            panelButton("Queue", "list.bullet", .queue, active: false)
            Spacer()
            panelButton("Lyrics", "quote.bubble", .lyrics, active: false)
            Spacer()
            panelButton("Equalizer", "slider.vertical.3", .equalizer, active: player.eq.enabled)
            Spacer()
            panelButton("Sleep Timer", "moon.zzz", .sleep, active: player.sleepMode != .off)
        }
        .padding(.top, 4)
    }

    private func panelButton(_ title: String, _ icon: String, _ target: Panel, active: Bool) -> some View {
        Button {
            panel = target
        } label: {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.title3).frame(height: 26)
                Text(title).font(.caption2)
            }
            .frame(minWidth: 64, minHeight: 44)
        }
        .foregroundStyle(active ? Color.accentColor : .primary)
    }
}
