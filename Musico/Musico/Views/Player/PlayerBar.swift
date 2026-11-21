import SwiftUI

/// The player along the bottom of the window in the sidebar layout.
struct PlayerBar: View {
    @Binding var sidePanel: SidePanel?
    let onOpen: () -> Void
    @Environment(PlayerController.self) private var player
    @Environment(QueueStore.self) private var queues

    var body: some View {
        let track = player.currentTrack
        HStack(spacing: 16) {
            Button(action: onOpen) {
                HStack(spacing: 12) {
                    ArtworkView(artworkKey: track?.artworkKey, size: 46)
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

            VStack(spacing: 2) {
                transport
                PlaybackScrubber(style: .inline)
            }
            .frame(minWidth: 240, maxWidth: 520)
            .disabled(track == nil)

            HStack(spacing: 4) {
                panelToggle("Lyrics", "quote.bubble", .lyrics)
                panelToggle("Queue", "list.bullet", .queue)
            }
            .frame(minWidth: 80, maxWidth: .infinity, alignment: .trailing)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
    }

    private var transport: some View {
        let queue = queues.active
        return HStack(spacing: 22) {
            Button("Shuffle", systemImage: "shuffle") { player.toggleShuffle() }
                .foregroundStyle(queue.shuffle ? Color.accentColor : .secondary)
            Button("Previous", systemImage: "backward.fill") { player.previous() }
            Button("Play or Pause", systemImage: player.isPlaying ? "pause.fill" : "play.fill") { player.togglePlayPause() }
                .font(.title2)
                .frame(width: 32)
            Button("Next", systemImage: "forward.fill") { player.next() }
            Button("Repeat", systemImage: queue.repeatMode == .one ? "repeat.1" : "repeat") { player.cycleRepeat() }
                .foregroundStyle(queue.repeatMode == .off ? .secondary : Color.accentColor)
        }
        .labelStyle(.iconOnly)
        .buttonStyle(.plain)
        .font(.body)
        .frame(height: 30)
    }

    private func panelToggle(_ title: String, _ icon: String, _ panel: SidePanel) -> some View {
        let isOn = sidePanel == panel
        return Button(title, systemImage: icon) { sidePanel = isOn ? nil : panel }
            .labelStyle(.iconOnly)
            .buttonStyle(.plain)
            .font(.body)
            .frame(width: 36, height: 32)
            .foregroundStyle(isOn ? Color.accentColor : .primary)
            .background(isOn ? Color.accentColor.opacity(0.15) : .clear, in: RoundedRectangle(cornerRadius: 7))
            .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}
