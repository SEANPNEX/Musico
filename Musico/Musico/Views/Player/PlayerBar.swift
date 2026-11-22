import SwiftUI

/// The player along the bottom of the window in the sidebar layout.
struct PlayerBar: View {
    @Binding var sidePanel: SidePanel?
    let onOpen: () -> Void
    @Environment(PlayerController.self) private var player
    @Environment(QueueStore.self) private var queues

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
                        Text(track?.title ?? "Not Playing").font(.subheadline.weight(.medium)).lineLimit(1)
                        Text(track?.artist ?? "Pick a song from your library").font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                .frame(minWidth: 170, maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Open Now Playing")
            .contextMenu {
                if let track {
                    TrackMenuItems(ids: [track.id], favouriteID: track.id)
                }
            }

            VStack(spacing: 2) {
                transport
                PlaybackScrubber(style: .inline)
            }
            .frame(minWidth: 240, maxWidth: 520)
            .disabled(track == nil)

            HStack(spacing: 4) {
                if let track {
                    Menu("More", systemImage: "ellipsis.circle") {
                        TrackMenuItems(ids: [track.id], favouriteID: track.id)
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.plain)
                    .font(.body)
                    .frame(width: 36, height: 32)
                    .contentShape(Rectangle())
                    .foregroundStyle(.secondary)
                    .accessibilityLabel("Song options")
                }
                panelToggle("Lyrics", "quote.bubble", .lyrics)
                panelToggle("Queue", "list.bullet", .queue)
            }
            .frame(minWidth: 80, maxWidth: .infinity, alignment: .trailing)
        }

        if #available(iOS 26.0, macCatalyst 26.0, *) {
            barContent
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background {
                    Color.clear
                        .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .allowsHitTesting(false)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 10)
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
        return HStack(spacing: 14) {
            Button("Shuffle", systemImage: "shuffle") {
                withAnimation(.spring(duration: 0.25)) { player.toggleShuffle() }
            }
            .foregroundStyle(queue.shuffle ? Color.accentColor : .secondary)
            .frame(width: 32, height: 30)
            .contentShape(Rectangle())

            Button("Previous", systemImage: "backward.fill") { player.previous() }
                .frame(width: 34, height: 30)
                .contentShape(Rectangle())

            if player.isBuffering {
                ProgressView()
                    .frame(width: 36, height: 30)
            } else {
                Button("Play or Pause", systemImage: player.isPlaying ? "pause.fill" : "play.fill") {
                    player.togglePlayPause()
                }
                .font(.title2)
                .contentTransition(.symbolEffect(.replace))
                .animation(.spring(duration: 0.25), value: player.isPlaying)
                .frame(width: 36, height: 30)
                .contentShape(Rectangle())
            }

            Button("Next", systemImage: "forward.fill") { player.next() }
                .frame(width: 34, height: 30)
                .contentShape(Rectangle())

            Button("Repeat", systemImage: queue.repeatMode == .one ? "repeat.1" : "repeat") {
                withAnimation(.spring(duration: 0.25)) { player.cycleRepeat() }
            }
            .contentTransition(.symbolEffect(.replace))
            .foregroundStyle(queue.repeatMode == .off ? .secondary : Color.accentColor)
            .frame(width: 32, height: 30)
            .contentShape(Rectangle())
        }
        .labelStyle(.iconOnly)
        .buttonStyle(.plain)
        .font(.body)
        .frame(height: 30)
    }

    private func panelToggle(_ title: String, _ icon: String, _ panel: SidePanel) -> some View {
        let isOn = sidePanel == panel
        return Button(title, systemImage: icon) {
            withAnimation(.spring(duration: 0.25)) {
                sidePanel = isOn ? nil : panel
            }
        }
        .labelStyle(.iconOnly)
        .buttonStyle(.plain)
        .font(.body)
        .contentTransition(.symbolEffect(.replace))
        .frame(width: 36, height: 32)
        .contentShape(Rectangle())
        .foregroundStyle(isOn ? Color.accentColor : .primary)
        .background(isOn ? Color.accentColor.opacity(0.15) : .clear, in: RoundedRectangle(cornerRadius: 7))
        .animation(.spring(duration: 0.25), value: isOn)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}
