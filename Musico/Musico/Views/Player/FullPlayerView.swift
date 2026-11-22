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
    @Environment(PlayerController.self) private var player: PlayerController?
    @Environment(LibraryStore.self) private var library: LibraryStore?
    @Environment(QueueStore.self) private var queues: QueueStore?
    @Environment(PlayHistoryStore.self) private var history: PlayHistoryStore?
    @Environment(\.appNavigation) private var navigation
    @Environment(\.lyricsStore) private var lyricsStore

    var body: some View {
        GeometryReader { proxy in
            Group {
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
            .background { AmbientPlayerBackdrop() }
        }
        .presentationDragIndicator(.visible)
        .modifier(NewPlaylistAlert())
        .modifier(CloudExportHostModifier())
        .modifier(SongInfoHostModifier())
        .sheet(item: $panel) { panel in
            sheetContent(for: panel)
                .environment(\.appNavigation, navigation)
                .environment(\.lyricsStore, lyricsStore)
        }
    }

    @ViewBuilder
    private func sheetContent(for panel: Panel) -> some View {
        let base = Group {
            switch panel {
            case .queue: QueueSheet()
            case .lyrics: LyricsView()
            case .equalizer: EqualizerSheet()
            case .sleep: SleepTimerSheet().presentationDetents([.medium, .large])
            }
        }
        if let player, let library, let queues, let history {
            base
                .environment(player)
                .environment(library)
                .environment(queues)
                .environment(history)
        } else {
            base
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
                closeButton
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .keyboardShortcut(.cancelAction)
            }
            if let track = player.currentTrack {
                ArtworkView(artworkKey: track.artworkKey)
                    .padding(.horizontal, 12)
                    .shadow(color: .black.opacity(player.isPlaying ? 0.28 : 0.14),
                            radius: player.isPlaying ? 22 : 12,
                            y: player.isPlaying ? 12 : 6)
                    .scaleEffect(player.isPlaying ? 1.0 : 0.92)
                    .animation(.spring(response: 0.45, dampingFraction: 0.72), value: player.isPlaying)
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
                    let isFavourite = library.isFavourite(track.id)
                    Button("Favourite", systemImage: isFavourite ? "heart.fill" : "heart") {
                        library.toggleFavourite(track.id)
                    }
                    .font(.title2)
                    .labelStyle(.iconOnly)
                    .foregroundStyle(isFavourite ? Color.red : Color.secondary)
                    .contentTransition(.symbolEffect(.replace))
                    .symbolEffect(.bounce, value: isFavourite)

                    Menu("More", systemImage: "ellipsis.circle") {
                        TrackMenuItems(ids: [track.id], favouriteID: track.id)
                    }
                    .font(.title2)
                    .labelStyle(.iconOnly)
                    .foregroundStyle(.secondary)
                    .accessibilityLabel("Song options")
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

    @ViewBuilder
    private var closeButton: some View {
        if #available(iOS 26.0, macCatalyst 26.0, *) {
            Button("Close", systemImage: "chevron.down") { dismiss() }
                .labelStyle(.iconOnly)
                .font(.body.weight(.semibold))
                .frame(width: 38, height: 38)
                .glassEffect(.regular.interactive(), in: Capsule())
        } else {
            Button("Close", systemImage: "chevron.down") { dismiss() }
                .labelStyle(.iconOnly)
                .font(.title3.weight(.semibold))
                .frame(width: 44, height: 32)
        }
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
            if player.isBuffering {
                ProgressView()
                    .controlSize(.large)
                    .frame(width: 68, height: 68)
            } else {
                Button("Play or Pause", systemImage: player.isPlaying ? "pause.circle.fill" : "play.circle.fill") {
                    player.togglePlayPause()
                }
                .font(.system(size: 68))
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.bounce, value: player.isPlaying)
            }
            Spacer()
            Button("Next", systemImage: "forward.fill") { player.next() }.font(.title)
            Spacer()
            Button("Repeat", systemImage: queue.repeatMode == .one ? "repeat.1" : "repeat") { player.cycleRepeat() }
                .contentTransition(.symbolEffect(.replace))
                .foregroundStyle(queue.repeatMode == .off ? .secondary : Color.accentColor)
        }
        .labelStyle(.iconOnly)
        .font(.title3)
        .tint(.primary)
    }

    @ViewBuilder
    private var panelButtons: some View {
        if #available(iOS 26.0, macCatalyst 26.0, *) {
            GlassEffectContainer(spacing: 10) {
                HStack(spacing: 8) {
                    panelButton("Queue", "list.bullet", .queue, active: false)
                    panelButton("Lyrics", "quote.bubble", .lyrics, active: false)
                    panelButton("Equalizer", "slider.vertical.3", .equalizer, active: player.eq.enabled)
                    panelButton("Sleep Timer", "moon.zzz", .sleep, active: player.sleepMode != .off)
                }
            }
            .padding(.top, 4)
        } else {
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
    }

    @ViewBuilder
    private func panelButton(_ title: String, _ icon: String, _ target: Panel, active: Bool) -> some View {
        if #available(iOS 26.0, macCatalyst 26.0, *) {
            Button {
                panel = target
            } label: {
                VStack(spacing: 4) {
                    Image(systemName: icon).font(.title3).frame(height: 24)
                    Text(title).font(.caption2).lineLimit(1).minimumScaleFactor(0.85)
                }
                .frame(maxWidth: .infinity, minHeight: 44)
                .padding(.horizontal, 6)
                .padding(.vertical, 6)
            }
            .buttonStyle(.plain)
            .foregroundStyle(active ? Color.accentColor : .primary)
            .glassEffect(.regular.interactive(), in: Capsule())
        } else {
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
}

/// Soft ambient glow tinted from the active track's artwork.
private struct AmbientPlayerBackdrop: View {
    @Environment(PlayerController.self) private var player
    @Environment(LibraryStore.self) private var library
    @State private var image: UIImage?

    var body: some View {
        let key = player.currentTrack?.artworkKey
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .blur(radius: 72)
                    .saturation(1.25)
                    .opacity(0.24)
                    .scaleEffect(1.25)
                    .transition(.opacity)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .animation(.easeInOut(duration: 0.6), value: key)
        .task(id: key) {
            guard let key else { image = nil; return }
            let cache = library.artwork
            image = await Task.detached { cache.image(forKey: key) }.value
        }
    }
}
