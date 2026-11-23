import SwiftUI

/// The full-screen player: this outer view owns the sheets and the two-column arrangement,
/// `PlayerPage` draws the controls.
struct FullPlayerView: View {
    var onClose: (() -> Void)? = nil

    enum Panel: String, Identifiable {
        case queue, lyrics, equalizer, sleep
        var id: String { rawValue }
    }

    /// What the right-hand column shows when there is room for two.
    enum SidePane: String, CaseIterable, Identifiable {
        case lyrics = "Lyrics", queue = "Queue"
        var id: String { rawValue }

        @MainActor
        var localizedTitle: String { L10n.tr(rawValue) }
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
    @Environment(\.extensionStore) private var extensionStore
    @Environment(\.languageManager) private var languageManager

    var body: some View {
        GeometryReader { proxy in
            let twoColumn = proxy.size.width >= Self.twoColumnWidth
            Group {
                if twoColumn {
                    HStack(spacing: 0) {
                        PlayerPage(
                            panel: columnPanel,
                            showsClose: true,
                            activeSidePane: sidePane,
                            onClose: onClose
                        )
                        .frame(maxWidth: .infinity)
                        Divider()
                        sideColumn
                            .frame(maxWidth: .infinity)
                    }
                } else {
                    PlayerPage(
                        panel: $panel,
                        showsClose: onClose != nil,
                        activeSidePane: nil,
                        onClose: onClose
                    )
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
                .environment(\.extensionStore, extensionStore)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
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
            withAnimation(.easeInOut(duration: 0.2)) {
                switch target {
                case .queue: sidePane = .queue
                case .lyrics: sidePane = .lyrics
                default: panel = target
                }
            }
        })
    }

    private var sideColumn: some View {
        VStack(spacing: 0) {
            Picker(L10n.tr("Show"), selection: $sidePane.animation(.easeInOut(duration: 0.2))) {
                ForEach(SidePane.allCases) { Text($0.localizedTitle).tag($0) }
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 320)
            .padding(.top, 20)
            .padding(.bottom, 8)
            Group {
                switch sidePane {
                case .lyrics: LyricsView(embedded: true)
                case .queue:
                    NavigationStack {
                        QueuesView(embeddedInInspector: true).navigationBarTitleDisplayMode(.inline)
                    }
                }
            }
            .id(sidePane)
            .transition(.opacity)
            .animation(.easeInOut(duration: 0.2), value: sidePane)
        }
    }
}

private struct PlayerPage: View {
    typealias Panel = FullPlayerView.Panel

    @Binding var panel: Panel?
    /// A close button, for when the player fills the window and cannot be dragged away.
    let showsClose: Bool
    let activeSidePane: FullPlayerView.SidePane?
    let onClose: (() -> Void)?
    @Environment(PlayerController.self) private var player
    @Environment(LibraryStore.self) private var library
    @Environment(QueueStore.self) private var queues
    @Environment(\.dismiss) private var dismiss
    @State private var isCloseHovered = false

    var body: some View {
        VStack(spacing: 16) {
            if showsClose {
                closeButton
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .keyboardShortcut(.cancelAction)
            }
            if let track = player.currentTrack {
                ArtworkView(artworkKey: track.artworkKey)
                    .id(track.id)
                    .transition(.opacity)
                    .animation(.easeInOut(duration: 0.25), value: track.id)
                    .padding(.horizontal, 12)
                    .shadow(color: .black.opacity(player.isPlaying ? 0.28 : 0.14),
                            radius: player.isPlaying ? 22 : 12,
                            y: player.isPlaying ? 12 : 6)
                    .scaleEffect(player.isPlaying ? 1.0 : 0.92)
                    .animation(.spring(response: 0.45, dampingFraction: 0.72), value: player.isPlaying)
                    .frame(maxHeight: .infinity)
                    .onTapGesture { panel = .lyrics }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityLabel(L10n.tr("Show lyrics"))

                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(track.title)
                            .font(.title2.weight(.semibold))
                            .lineLimit(2)
                            .contentTransition(.opacity)
                        Text("\(L10n.displayArtist(track.artist)) — \(L10n.displayAlbum(track.album))")
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .contentTransition(.opacity)
                    }
                    .animation(.easeInOut(duration: 0.2), value: track.id)
                    Spacer()
                    let isFavourite = library.isFavourite(track.id)
                    Button {
                        library.toggleFavourite(track.id)
                    } label: {
                        Image(systemName: isFavourite ? "heart.fill" : "heart")
                            .font(.title2)
                            .foregroundStyle(isFavourite ? Color.red : Color.secondary)
                            .frame(width: 36, height: 36)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .contentTransition(.symbolEffect(.replace))
                    .symbolEffect(.bounce, value: isFavourite)
                    .accessibilityLabel(L10n.tr("Favourite"))

                    Menu {
                        TrackMenuItems(ids: [track.id], favouriteID: track.id)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .font(.title2)
                            .foregroundStyle(.secondary)
                            .frame(width: 36, height: 36)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L10n.tr("Song options"))
                }

                PlaybackScrubber()
                transport
                HStack(spacing: 0) {
                    MarqueeText(
                        text: L10n.displayQueueName(queues.active.name),
                        font: .footnote,
                        alignment: .center
                    )
                    if !positionText.isEmpty {
                        Text(" · " + positionText)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .layoutPriority(1)
                    }
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                panelButtons
            } else {
                ContentUnavailableView(L10n.tr("Nothing Playing"), systemImage: "music.note",
                                       description: Text(L10n.tr("Pick a song from your library.")))
                Button(L10n.tr("Queues"), systemImage: "list.bullet") { panel = .queue }
            }
        }
        // Keeps the player a comfortable size in wide iPad and Mac windows.
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.top, showsClose ? 12 : 28)
        .padding(.bottom, 20)
    }

    private func performClose() {
        if let onClose {
            onClose()
        } else {
            dismiss()
        }
    }

    @ViewBuilder
    private var closeButton: some View {
        if #available(iOS 26.0, macCatalyst 26.0, *) {
            Button(action: performClose) {
                Image(systemName: "chevron.down")
                    .font(.body.weight(.semibold))
                    .frame(width: 38, height: 38)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.tr("Close"))
            .help(L10n.tr("Close"))
            .background {
                Color.clear
                    .glassEffect(.regular, in: Capsule())
                    .allowsHitTesting(false)
            }
            .overlay {
                ZStack {
                    Capsule()
                        .strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.5)
                    if isCloseHovered {
                        Capsule()
                            .fill(Color.primary.opacity(0.08))
                    }
                }
                .allowsHitTesting(false)
            }
            .scaleEffect(isCloseHovered ? 1.05 : 1.0)
            .onHover { isCloseHovered = $0 }
            .animation(.easeOut(duration: 0.14), value: isCloseHovered)
        } else {
            Button(action: performClose) {
                Image(systemName: "chevron.down")
                    .font(.title3.weight(.semibold))
                    .frame(width: 44, height: 32)
                    .background(isCloseHovered ? Color.primary.opacity(0.08) : .clear, in: Capsule())
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.tr("Close"))
            .help(L10n.tr("Close"))
            .onHover { isCloseHovered = $0 }
            .animation(.easeOut(duration: 0.14), value: isCloseHovered)
        }
    }

    private var positionText: String {
        guard let index = queues.active.currentIndex else { return "" }
        return L10n.tr("%@ of %@", String(index + 1), String(queues.active.trackIDs.count))
    }

    private var transport: some View {
        let queue = queues.active
        return HStack {
            Button {
                player.toggleShuffle()
            } label: {
                Image(systemName: "shuffle")
                    .font(.title3)
                    .foregroundStyle(queue.shuffle ? Color.accentColor : .secondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L10n.tr("Shuffle"))

            Spacer()

            Button {
                player.previous()
            } label: {
                Image(systemName: "backward.fill")
                    .font(.title)
                    .foregroundStyle(.primary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L10n.tr("Previous"))

            Spacer()

            if player.isBuffering {
                ProgressView()
                    .controlSize(.large)
                    .frame(width: 76, height: 76)
            } else {
                Button {
                    player.togglePlayPause()
                } label: {
                    Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 68))
                        .foregroundStyle(.primary)
                        .frame(width: 76, height: 76)
                        .contentShape(Circle())
                }
                .buttonStyle(.borderless)
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.bounce, value: player.isPlaying)
                .accessibilityLabel(L10n.tr("Play or Pause"))
            }

            Spacer()

            Button {
                player.next()
            } label: {
                Image(systemName: "forward.fill")
                    .font(.title)
                    .foregroundStyle(.primary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L10n.tr("Next"))

            Spacer()

            Button {
                player.cycleRepeat()
            } label: {
                Image(systemName: queue.repeatMode == .one ? "repeat.1" : "repeat")
                    .font(.title3)
                    .foregroundStyle(queue.repeatMode == .off ? .secondary : Color.accentColor)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .contentTransition(.symbolEffect(.replace))
            .accessibilityLabel(L10n.tr("Repeat"))
        }
    }

    private var panelButtons: some View {
        let queueActive = activeSidePane == .queue || panel == .queue
        let lyricsActive = activeSidePane == .lyrics || panel == .lyrics
        return HStack(spacing: 10) {
            panelButton(L10n.tr("Queue"), "list.bullet", .queue, active: queueActive)
            panelButton(L10n.tr("Lyrics"), "quote.bubble", .lyrics, active: lyricsActive)
            panelButton(L10n.tr("Equalizer"), "slider.vertical.3", .equalizer, active: player.eq.enabled)
            panelButton(L10n.tr("Sleep Timer"), "moon.zzz", .sleep, active: player.sleepMode != .off)
        }
        .padding(.top, 4)
    }

    private func panelButton(_ title: String, _ icon: String, _ target: Panel, active: Bool) -> some View {
        PlayerPanelButton(title: title, icon: icon, active: active) {
            panel = target
        }
    }
}

private struct PlayerPanelButton: View {
    let title: String
    let icon: String
    let active: Bool
    let action: () -> Void

    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.body.weight(.semibold))
                    .frame(height: 22)
                Text(title)
                    .font(.caption.weight(.medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }
            .frame(maxWidth: .infinity, minHeight: 50)
            .padding(.horizontal, 8)
            .padding(.vertical, 7)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .foregroundStyle(active ? Color.accentColor : Color.primary)
        .background {
            ZStack {
                Capsule().fill(.regularMaterial)
                Capsule().fill(
                    active
                        ? Color.accentColor.opacity(0.15)
                        : (isHovered ? Color.primary.opacity(0.09) : Color.primary.opacity(0.04))
                )
            }
            .allowsHitTesting(false)
        }
        .overlay {
            Capsule()
                .strokeBorder(
                    active ? Color.accentColor.opacity(0.38) : Color.primary.opacity(0.14),
                    lineWidth: 0.75
                )
                .allowsHitTesting(false)
        }
        .shadow(color: .black.opacity(0.06), radius: 3, y: 1)
        .onHover { isHovered = $0 }
        .animation(.easeOut(duration: 0.14), value: isHovered)
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
