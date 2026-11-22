import SwiftUI

/// The player along the bottom of the window in the sidebar layout.
struct PlayerBar: View {
    @Binding var sidePanel: SidePanel?
    var isQueueMainActive: Bool = false
    let onOpen: () -> Void
    @Environment(PlayerController.self) private var player
    @Environment(QueueStore.self) private var queues
    @State private var isTrackHovered = false
    @State private var isMoreHovered = false

    var body: some View {
        let track = player.currentTrack
        let barContent = HStack(spacing: 16) {
            Button(action: onOpen) {
                HStack(spacing: 12) {
                    ArtworkView(artworkKey: track?.artworkKey, size: 46)
                        .id(track?.id)
                        .transition(.opacity)
                        .animation(.easeInOut(duration: 0.2), value: track?.id)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(track?.title ?? L10n.tr("Not Playing"))
                            .font(.subheadline.weight(.medium))
                            .lineLimit(1)
                            .contentTransition(.opacity)
                        Text(track.map { L10n.displayArtist($0.artist) } ?? L10n.tr("Pick a song from your library"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .contentTransition(.opacity)
                    }
                    .animation(.easeInOut(duration: 0.2), value: track?.id)
                }
                .padding(.horizontal, 6)
                .padding(.vertical, 4)
                .background(isTrackHovered ? Color.primary.opacity(0.06) : .clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .frame(minWidth: 150, maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .onHover { isTrackHovered = $0 }
            .animation(.easeOut(duration: 0.14), value: isTrackHovered)
            .accessibilityLabel(L10n.tr("Open Now Playing"))
            .contextMenu {
                if let track {
                    TrackMenuItems(ids: [track.id], favouriteID: track.id)
                }
            }

            VStack(spacing: 2) {
                transport
                PlaybackScrubber(style: .inline)
            }
            .frame(minWidth: 220, maxWidth: 480)
            .disabled(track == nil)

            HStack(spacing: 4) {
                if let track {
                    Menu(L10n.tr("More"), systemImage: "ellipsis.circle") {
                        TrackMenuItems(ids: [track.id], favouriteID: track.id)
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.plain)
                    .font(.body)
                    .frame(width: 34, height: 32)
                    .background(isMoreHovered ? Color.primary.opacity(0.08) : .clear, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                    .contentShape(Rectangle())
                    .foregroundStyle(.secondary)
                    .onHover { isMoreHovered = $0 }
                    .animation(.easeOut(duration: 0.14), value: isMoreHovered)
                    .accessibilityLabel(L10n.tr("Song options"))
                }
                panelToggle(L10n.tr("Lyrics"), "quote.bubble", .lyrics)
                panelToggle(L10n.tr("Queue"), "list.bullet", .queue)
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

    private var transport: some View {
        let queue = queues.active
        return HStack(spacing: 12) {
            DockIconButton(
                title: L10n.tr("Shuffle"),
                systemImage: "shuffle",
                foreground: queue.shuffle ? Color.accentColor : .secondary,
                width: 32,
                height: 30
            ) {
                withAnimation(.spring(duration: 0.25)) { player.toggleShuffle() }
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
                }
                .contentTransition(.symbolEffect(.replace))
                .animation(.spring(duration: 0.25), value: player.isPlaying)
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
                foreground: queue.repeatMode == .off ? .secondary : Color.accentColor,
                width: 32,
                height: 30
            ) {
                withAnimation(.spring(duration: 0.25)) { player.cycleRepeat() }
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
            foreground: isOn ? Color.accentColor : .primary,
            isSelected: isOn,
            width: 36,
            height: 32
        ) {
            withAnimation(.spring(response: 0.30, dampingFraction: 0.86)) {
                if panel == .queue && isQueueMainActive {
                    sidePanel = nil
                } else {
                    sidePanel = isOn ? nil : panel
                }
            }
        }
        .contentTransition(.symbolEffect(.replace))
        .animation(.spring(duration: 0.25), value: isOn)
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

    @State private var isHovered = false

    var body: some View {
        Button(title, systemImage: systemImage, action: action)
            .labelStyle(.iconOnly)
            .buttonStyle(.plain)
            .font(font)
            .frame(width: width, height: height)
            .foregroundStyle(foreground)
            .background(
                isSelected
                    ? Color.accentColor.opacity(0.15)
                    : (isHovered ? Color.primary.opacity(0.08) : .clear),
                in: RoundedRectangle(cornerRadius: 7, style: .continuous)
            )
            .contentShape(Rectangle())
            .onHover { isHovered = $0 }
            .animation(.easeOut(duration: 0.14), value: isHovered)
    }
}
