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
    @Environment(\.themeManager) private var themeManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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
            .background {
                ZStack {
                    ThemeBackdropView()
                    AmbientPlayerBackdrop()
                }
            }
        }
        .presentationDragIndicator(.visible)
        .modifier(NewPlaylistAlert())
        .modifier(CloudExportHostModifier())
        .modifier(SongInfoHostModifier())
        .modifier(DeleteConfirmHostModifier())
        .overlay(alignment: .bottom) {
            ToastOverlayView()
        }
        .sheet(item: $panel) { panel in
            sheetContent(for: panel)
                .environment(\.appNavigation, navigation)
                .environment(\.lyricsStore, lyricsStore)
                .environment(\.extensionStore, extensionStore)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
                .applyTheme(themeManager)
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
            withAnimation(MusicoMotion.viewSwitch(reduceMotion: reduceMotion)) {
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
            Picker(
                L10n.tr("Show"),
                selection: Binding(
                    get: { sidePane },
                    set: { newValue in
                        guard newValue != sidePane else { return }
                        withAnimation(MusicoMotion.viewSwitch(reduceMotion: reduceMotion)) {
                            sidePane = newValue
                        }
                    }
                )
            ) {
                ForEach(SidePane.allCases) { Text($0.localizedTitle).tag($0) }
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 320)
            .padding(.top, 20)
            .padding(.bottom, 8)

            ZStack {
                LyricsView(embedded: true)
                    .opacity(sidePane == .lyrics ? 1 : 0)
                    .offset(x: reduceMotion ? 0 : (sidePane == .lyrics ? 0 : -MusicoMotion.peerSlideOffset))
                    .allowsHitTesting(sidePane == .lyrics)
                    .accessibilityHidden(sidePane != .lyrics)

                NavigationStack {
                    QueuesView(embeddedInInspector: true).navigationBarTitleDisplayMode(.inline)
                }
                .opacity(sidePane == .queue ? 1 : 0)
                .offset(x: reduceMotion ? 0 : (sidePane == .queue ? 0 : MusicoMotion.peerSlideOffset))
                .allowsHitTesting(sidePane == .queue)
                .accessibilityHidden(sidePane != .queue)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()
            .animation(MusicoMotion.viewSwitch(reduceMotion: reduceMotion), value: sidePane)
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
    @Environment(\.themeManager) private var themeManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dismiss) private var dismiss
    @State private var isCloseHovered = false
    @State private var artworkDragOffset: CGFloat = 0
    @State private var playFeedbackTrigger = 0
    @State private var favouriteFeedbackTrigger = 0

    var body: some View {
        VStack(spacing: 16) {
            if showsClose {
                HStack {
                    closeButton
                    Spacer()
                }
                .zIndex(10)
            }
            if let track = player.currentTrack {
                ArtworkView(artworkKey: track.artworkKey, remoteArtworkURL: track.remoteArtworkURL)
                    .id(track.id)
                    .transition(.opacity)
                    .animation(MusicoMotion.contentChange(reduceMotion: reduceMotion), value: track.id)
                    .padding(.horizontal, 12)
                    .shadow(color: .black.opacity(player.isPlaying ? 0.28 : 0.14),
                            radius: player.isPlaying ? 22 : 12,
                            y: player.isPlaying ? 12 : 6)
                    .scaleEffect(reduceMotion ? 1.0 : (player.isPlaying ? 1.0 : 0.92))
                    .offset(x: reduceMotion ? 0 : artworkDragOffset)
                    .animation(reduceMotion ? nil : .spring(response: 0.45, dampingFraction: 0.72), value: player.isPlaying)
                    .frame(maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 24)
                            .onChanged { value in
                                guard abs(value.translation.width) > abs(value.translation.height) * 1.15 else { return }
                                artworkDragOffset = reduceMotion ? 0 : value.translation.width * 0.35
                            }
                            .onEnded { value in
                                if abs(value.translation.width) > abs(value.translation.height) {
                                    if value.translation.width < -60 || value.predictedEndTranslation.width < -100 {
                                        player.next()
                                    } else if value.translation.width > 60 || value.predictedEndTranslation.width > 100 {
                                        player.previous()
                                    }
                                }
                                withAnimation(MusicoMotion.panelToggle(reduceMotion: reduceMotion)) {
                                    artworkDragOffset = 0
                                }
                            }
                    )
                    .onTapGesture { panel = .lyrics }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityLabel(L10n.tr("Show lyrics"))
                    .clipped()
                    .zIndex(0)

                HStack(alignment: .center, spacing: 8) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(track.title)
                            .font(.title2.weight(.semibold))
                            .lineLimit(2)
                            .contentTransition(.opacity)
                        HStack(spacing: 6) {
                            Text("\(L10n.displayArtist(track.artist)) — \(L10n.displayAlbum(track.album))")
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .contentTransition(.opacity)
                            if !player.availableQualitiesForCurrentTrack.isEmpty,
                               let pillLabel = currentQualityPillLabel {
                                qualityPillMenu(
                                    label: pillLabel,
                                    qualities: player.availableQualitiesForCurrentTrack,
                                    activeID: player.activeQualityIDForCurrentTrack
                                )
                            }
                        }
                    }
                    .animation(MusicoMotion.contentChange(reduceMotion: reduceMotion), value: track.id)
                    Spacer(minLength: 4)
                    if !track.isEphemeral {
                        let isFavourite = library.isFavourite(track.id)
                        Button {
                            library.toggleFavourite(track.id)
                            favouriteFeedbackTrigger += 1
                        } label: {
                            Image(systemName: isFavourite ? "heart.fill" : "heart")
                                .font(.title2)
                                .foregroundStyle(isFavourite ? Color.red : Color.secondary)
                                .frame(minWidth: 44, minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.borderless)
                        .contentTransition(.symbolEffect(.replace))
                        .symbolEffect(.bounce, value: reduceMotion ? false : isFavourite)
                        #if !targetEnvironment(macCatalyst)
                        .sensoryFeedback(.impact(weight: .light), trigger: favouriteFeedbackTrigger)
                        #endif
                        .accessibilityLabel(L10n.tr("Favourite"))
                    }

                    Menu {
                        TrackMenuItems(ids: [track.id], favouriteID: track.isEphemeral ? nil : track.id)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .font(.title2)
                            .foregroundStyle(.secondary)
                            .frame(minWidth: 44, minHeight: 44)
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
                    .frame(width: 22, height: 22)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .keyboardShortcut(.cancelAction)
            .accessibilityLabel(L10n.tr("Close"))
            .help(L10n.tr("Close"))
        } else {
            Button(action: performClose) {
                Image(systemName: "chevron.down")
                    .font(.title3.weight(.semibold))
                    .frame(width: 38, height: 34)
                    .background(.regularMaterial, in: Capsule())
                    .overlay {
                        Capsule()
                            .fill(isCloseHovered ? Color.primary.opacity(0.08) : .clear)
                    }
                    .contentShape(Capsule())
            }
            .buttonStyle(.borderless)
            .keyboardShortcut(.cancelAction)
            .accessibilityLabel(L10n.tr("Close"))
            .help(L10n.tr("Close"))
            .onHover { isCloseHovered = $0 }
            .animation(MusicoMotion.microInteraction(reduceMotion: reduceMotion), value: isCloseHovered)
        }
    }

    private var positionText: String {
        guard let index = queues.active.currentIndex else { return "" }
        return L10n.tr("%@ of %@", String(index + 1), String(queues.active.trackIDs.count))
    }

    private var currentQualityPillLabel: String? {
        if let label = player.currentStreamQualityLabel?.trimmingCharacters(in: .whitespacesAndNewlines),
           !label.isEmpty {
            return label
        }
        let qualities = player.availableQualitiesForCurrentTrack
        guard !qualities.isEmpty else { return nil }
        if let activeID = player.activeQualityIDForCurrentTrack,
           let matched = qualities.first(where: { $0.id == activeID }) {
            return matched.badge ?? matched.label
        }
        return qualities.first.map { $0.badge ?? $0.label }
    }

    private func qualityPillMenu(label: String, qualities: [CloudQualityOption], activeID: String?) -> some View {
        Menu {
            Section(L10n.tr("Audio Quality (This Track)")) {
                ForEach(qualities) { option in
                    Button {
                        player.switchCurrentTrackQuality(to: option.id)
                    } label: {
                        if option.id == activeID {
                            Label(option.label, systemImage: "checkmark")
                        } else {
                            Text(option.label)
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 3) {
                Text(label)
                Image(systemName: "chevron.down")
                    .font(.system(size: 8, weight: .bold))
            }
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(themeManager.accentColor.opacity(0.14), in: Capsule())
            .foregroundStyle(themeManager.accentColor)
            .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(.plain)
        .layoutPriority(1)
        .accessibilityLabel(L10n.tr("Audio Quality"))
    }

    private var transport: some View {
        let queue = queues.active
        return HStack {
            Button {
                player.toggleShuffle()
            } label: {
                Image(systemName: "shuffle")
                    .font(.title3)
                    .foregroundStyle(queue.shuffle ? themeManager.accentColor : .secondary)
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
                    playFeedbackTrigger += 1
                } label: {
                    Image(systemName: player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 68))
                        .foregroundStyle(.primary)
                        .frame(width: 76, height: 76)
                        .contentShape(Circle())
                }
                .buttonStyle(.borderless)
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.bounce, value: reduceMotion ? false : player.isPlaying)
                #if !targetEnvironment(macCatalyst)
                .sensoryFeedback(.impact(flexibility: .soft), trigger: playFeedbackTrigger)
                #endif
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
                    .foregroundStyle(queue.repeatMode == .off ? .secondary : themeManager.accentColor)
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

    @Environment(\.themeManager) private var themeManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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
        .foregroundStyle(active ? themeManager.accentColor : Color.primary)
        .background {
            ZStack {
                Capsule().fill(.regularMaterial)
                Capsule().fill(
                    active
                        ? themeManager.accentColor.opacity(0.15)
                        : (isHovered ? Color.primary.opacity(0.09) : Color.primary.opacity(0.04))
                )
            }
            .allowsHitTesting(false)
        }
        .overlay {
            Capsule()
                .strokeBorder(
                    active ? themeManager.accentColor.opacity(0.38) : Color.primary.opacity(0.14),
                    lineWidth: 0.75
                )
                .allowsHitTesting(false)
        }
        .shadow(color: .black.opacity(0.06), radius: 3, y: 1)
        .onHover { isHovered = $0 }
        .animation(MusicoMotion.microInteraction(reduceMotion: reduceMotion), value: isHovered)
    }
}

/// Soft ambient glow tinted from the active track's artwork.
private struct AmbientPlayerBackdrop: View {
    @Environment(PlayerController.self) private var player
    @Environment(LibraryStore.self) private var library
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var image: UIImage?

    var body: some View {
        let key = player.currentTrack?.artworkKey
        let remoteURL = player.currentTrack?.remoteArtworkURL
        let taskID = "\(key ?? "")|\(remoteURL ?? "")"
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
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.6), value: taskID)
        .task(id: taskID) {
            if let key {
                let cache = library.artwork
                image = await Task.detached { cache.image(forKey: key) }.value
                return
            }
            if let remoteURL {
                if let cached = CloudThumbnailCache.shared.cachedImage(for: remoteURL) {
                    image = cached
                    return
                }
                image = nil
                let loaded = await CloudThumbnailCache.shared.loadImage(from: remoteURL)
                if !Task.isCancelled {
                    image = loaded
                }
                return
            }
            image = nil
        }
    }
}
