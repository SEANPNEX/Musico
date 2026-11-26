import SwiftUI

/// The player along the bottom of the window in the sidebar layout.
struct PlayerBar: View {
    @Binding var sidePanel: SidePanel?
    var isQueueMainActive: Bool = false
    let onOpen: () -> Void
    @Environment(PlayerController.self) private var player
    @Environment(QueueStore.self) private var queues
    @Environment(\.themeManager) private var themeManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    #if targetEnvironment(macCatalyst)
    @Environment(\.openWindow) private var openWindow
    #endif
    @State private var isTrackHovered = false
    @State private var isMoreHovered = false
    @State private var playFeedbackTrigger = 0

    var body: some View {
        let track = player.currentTrack
        let barContent = HStack(spacing: 16) {
            Button(action: onOpen) {
                HStack(spacing: 12) {
                    ArtworkView(artworkKey: track?.artworkKey, remoteArtworkURL: track?.remoteArtworkURL, size: 46)
                        .id(track?.id)
                        .transition(.opacity)
                        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: track?.id)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(track?.title ?? L10n.tr("Not Playing"))
                            .font(.subheadline.weight(.medium))
                            .lineLimit(1)
                            .contentTransition(.opacity)
                        if player.isBuffering, let caption = bufferingCaption {
                            Text(caption)
                                .font(.footnote.monospacedDigit())
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        } else {
                            Text(track.map { L10n.displayArtist($0.artist) } ?? L10n.tr("Pick a song from your library"))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .contentTransition(.opacity)
                        }
                    }
                    .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: track?.id)
                }
                .padding(.horizontal, 6)
                .padding(.vertical, 4)
                .background(isTrackHovered ? Color.primary.opacity(0.06) : .clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .frame(minWidth: 150, maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .onHover { isTrackHovered = $0 }
            .animation(reduceMotion ? nil : .easeOut(duration: 0.14), value: isTrackHovered)
            .accessibilityLabel(L10n.tr("Open Now Playing"))
            .contextMenu {
                if let track {
                    TrackMenuItems(ids: [track.id], favouriteID: track.isEphemeral ? nil : track.id)
                }
            }

            VStack(spacing: 2) {
                transport
                PlaybackScrubber(style: .inline)
            }
            .frame(minWidth: 220, maxWidth: 480)
            .disabled(track == nil)

            HStack(spacing: 4) {
                #if targetEnvironment(macCatalyst)
                volumeControls
                AudioRoutePickerView(
                    tintColor: themeManager.primaryForegroundColor.map(UIColor.init) ?? .label,
                    activeTintColor: UIColor(themeManager.accentColor)
                )
                .frame(width: 30, height: 30)
                #endif
                if let track {
                    Menu {
                        TrackMenuItems(ids: [track.id], favouriteID: track.isEphemeral ? nil : track.id)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                            .font(.body)
                            .foregroundStyle(.secondary)
                            .frame(width: 34, height: 32)
                            .background(
                                isMoreHovered ? Color.primary.opacity(0.08) : .clear,
                                in: RoundedRectangle(cornerRadius: 7, style: .continuous)
                            )
                            .contentShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                    }
                    .buttonStyle(.borderless)
                    .onHover { isMoreHovered = $0 }
                    .animation(reduceMotion ? nil : .easeOut(duration: 0.14), value: isMoreHovered)
                    .help(L10n.tr("Song options"))
                    .accessibilityLabel(L10n.tr("Song options"))
                }
                panelToggle(L10n.tr("Lyrics"), "quote.bubble", .lyrics)
                panelToggle(L10n.tr("Queue"), "list.bullet", .queue)
                #if targetEnvironment(macCatalyst)
                DockIconButton(
                    title: L10n.tr("Mini Player"),
                    systemImage: "pip.enter",
                    foreground: .secondary,
                    width: 34,
                    height: 32
                ) {
                    openWindow(id: "mini-player")
                }
                #endif
            }
            .frame(minWidth: 112, maxWidth: .infinity, alignment: .trailing)
        }

        if #available(iOS 26.0, macCatalyst 26.0, *) {
            VStack(spacing: 0) {
                Divider().opacity(0.35)
                barContent
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background {
                        Color.clear
                            .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                            .allowsHitTesting(false)
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 8)
                    .padding(.bottom, 10)
            }
        } else {
            barContent
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(.bar)
                .overlay(alignment: .top) { Divider() }
        }
    }

    private var bufferingCaption: String? {
        guard let progress = player.bufferingProgress else { return nil }
        var parts: [String] = []
        if let fraction = progress.fractionCompleted {
            parts.append(DownloadManager.formattedPercentage(fraction))
        } else if progress.bytesDownloaded > 0 {
            parts.append(DownloadManager.formattedBytes(progress.bytesDownloaded))
        }
        if progress.bytesPerSecond > 0 {
            parts.append(DownloadManager.formattedSpeed(progress.bytesPerSecond))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    #if targetEnvironment(macCatalyst)
    private var volumeControls: some View {
        @Bindable var player = player
        let effectiveVolume = player.isMuted ? 0 : player.volume
        let speakerIcon: String = {
            if player.isMuted || effectiveVolume <= 0.001 { return "speaker.slash.fill" }
            if effectiveVolume < 0.34 { return "speaker.wave.1.fill" }
            if effectiveVolume < 0.67 { return "speaker.wave.2.fill" }
            return "speaker.wave.3.fill"
        }()
        return HStack(spacing: 4) {
            DockIconButton(
                title: player.isMuted ? L10n.tr("Unmute") : L10n.tr("Mute"),
                systemImage: speakerIcon,
                foreground: player.isMuted ? themeManager.accentColor : .secondary,
                width: 28,
                height: 28
            ) {
                player.toggleMute()
            }
            Slider(
                value: Binding(
                    get: { Double(player.isMuted ? 0 : player.volume) },
                    set: { player.volume = Float($0) }
                ),
                in: 0...1
            )
            .controlSize(.mini)
            .frame(width: 76)
            .accessibilityLabel(L10n.tr("Volume"))
        }
        .padding(.trailing, 4)
    }
    #endif

    private var transport: some View {
        let queue = queues.active
        return HStack(spacing: 12) {
            DockIconButton(
                title: L10n.tr("Shuffle"),
                systemImage: "shuffle",
                foreground: queue.shuffle ? themeManager.accentColor : .secondary,
                width: 32,
                height: 30
            ) {
                withAnimation(reduceMotion ? nil : .spring(duration: 0.25)) { player.toggleShuffle() }
            }

            DockIconButton(
                title: L10n.tr("Previous"),
                systemImage: "backward.fill",
                foreground: .primary,
                width: 34,
                height: 30
            ) {
                player.previous()
            }

            if player.isBuffering {
                ProgressView()
                    .frame(width: 36, height: 30)
            } else {
                DockIconButton(
                    title: L10n.tr("Play or Pause"),
                    systemImage: player.isPlaying ? "pause.fill" : "play.fill",
                    font: .title2,
                    foreground: .primary,
                    width: 36,
                    height: 30
                ) {
                    player.togglePlayPause()
                    playFeedbackTrigger += 1
                }
                .contentTransition(.symbolEffect(.replace))
                .animation(reduceMotion ? nil : .spring(duration: 0.25), value: player.isPlaying)
                #if !targetEnvironment(macCatalyst)
                .sensoryFeedback(.impact(flexibility: .soft), trigger: playFeedbackTrigger)
                #endif
            }

            DockIconButton(
                title: L10n.tr("Next"),
                systemImage: "forward.fill",
                foreground: .primary,
                width: 34,
                height: 30
            ) {
                player.next()
            }

            DockIconButton(
                title: L10n.tr("Repeat"),
                systemImage: queue.repeatMode == .one ? "repeat.1" : "repeat",
                foreground: queue.repeatMode == .off ? .secondary : themeManager.accentColor,
                width: 32,
                height: 30
            ) {
                withAnimation(reduceMotion ? nil : .spring(duration: 0.25)) { player.cycleRepeat() }
            }
            .contentTransition(.symbolEffect(.replace))
        }
        .frame(height: 30)
    }

    private func panelToggle(_ title: String, _ icon: String, _ panel: SidePanel) -> some View {
        let isOn = sidePanel == panel
        return DockIconButton(
            title: title,
            systemImage: icon,
            foreground: isOn ? themeManager.accentColor : .primary,
            isSelected: isOn,
            width: 36,
            height: 32
        ) {
            withAnimation(reduceMotion ? nil : .spring(response: 0.30, dampingFraction: 0.86)) {
                if panel == .queue && isQueueMainActive {
                    sidePanel = nil
                } else {
                    sidePanel = isOn ? nil : panel
                }
            }
        }
        .contentTransition(.symbolEffect(.replace))
        .animation(reduceMotion ? nil : .spring(duration: 0.25), value: isOn)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

private struct DockIconButton: View {
    let title: String
    let systemImage: String
    var font: Font = .body
    var foreground: Color = .primary
    var isSelected: Bool = false
    let width: CGFloat
    let height: CGFloat
    let action: () -> Void

    @Environment(\.themeManager) private var themeManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(font)
                .foregroundStyle(foreground)
                .frame(width: width, height: height)
                .contentShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
        }
        .buttonStyle(DockIconButtonStyle(
            isSelected: isSelected,
            isHovered: isHovered,
            accentColor: themeManager.accentColor,
            reduceMotion: reduceMotion
        ))
        .onHover { isHovered = $0 }
        .animation(reduceMotion ? nil : .easeOut(duration: 0.14), value: isHovered)
        .accessibilityLabel(title)
        .help(title)
    }
}

private struct DockIconButtonStyle: ButtonStyle {
    let isSelected: Bool
    let isHovered: Bool
    let accentColor: Color
    let reduceMotion: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                backgroundColor(isPressed: configuration.isPressed),
                in: RoundedRectangle(cornerRadius: 7, style: .continuous)
            )
            .scaleEffect(!reduceMotion && configuration.isPressed ? 0.94 : 1.0)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.08), value: configuration.isPressed)
    }

    private func backgroundColor(isPressed: Bool) -> Color {
        if isPressed {
            return isSelected ? accentColor.opacity(0.24) : Color.primary.opacity(0.14)
        }
        if isSelected {
            return accentColor.opacity(0.15)
        }
        if isHovered {
            return Color.primary.opacity(0.08)
        }
        return .clear
    }
}
