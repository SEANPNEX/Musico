import SwiftUI

struct MiniPlayerView: View {
    /// Approximate height, used to keep list content clear of it.
    static let height: CGFloat = 60

    var isGlassAccessory: Bool = false
    let onOpen: () -> Void
    @Environment(PlayerController.self) private var player

    var body: some View {
        if let track = player.currentTrack {
            let buttonSize: CGFloat = isGlassAccessory ? 36 : 44
            let row = HStack(spacing: 12) {
                Button(action: onOpen) {
                    HStack(spacing: 12) {
                        ArtworkView(artworkKey: track.artworkKey, size: isGlassAccessory ? 34 : 40)
                            .id(track.id)
                            .transition(.opacity)
                            .animation(.easeInOut(duration: 0.2), value: track.id)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(track.title).font(.subheadline.weight(.medium)).lineLimit(1)
                            Text(L10n.displayArtist(track.artist)).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer(minLength: 8)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.tr("Open Now Playing"))
                .contextMenu {
                    TrackMenuItems(ids: [track.id], favouriteID: track.id)
                }

                if player.isBuffering {
                    ProgressView()
                        .frame(width: buttonSize, height: buttonSize)
                } else {
                    Button(L10n.tr("Play or Pause"), systemImage: player.isPlaying ? "pause.fill" : "play.fill") {
                        player.togglePlayPause()
                    }
                    .font(.title3)
                    .buttonStyle(.plain)
                    .contentTransition(.symbolEffect(.replace))
                    .animation(.spring(duration: 0.25), value: player.isPlaying)
                    .frame(width: buttonSize, height: buttonSize)
                    .contentShape(Rectangle())
                }
                Button(L10n.tr("Next"), systemImage: "forward.fill") { player.next() }
                    .font(.title3)
                    .buttonStyle(.plain)
                    .frame(width: buttonSize, height: buttonSize)
                    .contentShape(Rectangle())
            }
            .labelStyle(.iconOnly)
            .tint(.primary)
            .padding(.horizontal, isGlassAccessory ? 12 : 14)
            .padding(.vertical, isGlassAccessory ? 4 : 6)

            Group {
                if isGlassAccessory {
                    row
                } else {
                    VStack(spacing: 0) {
                        ProgressView(value: min(player.currentTime, max(player.duration, 0.01)), total: max(player.duration, 0.01))
                            .progressViewStyle(.linear)
                            .scaleEffect(x: 1, y: 0.5, anchor: .top)
                        row
                    }
                    .background(.bar)
                }
            }
        }
    }
}
