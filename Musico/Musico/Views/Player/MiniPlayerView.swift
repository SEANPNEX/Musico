import SwiftUI

struct MiniPlayerView: View {
    /// Approximate height, used to keep list content clear of it.
    static let height: CGFloat = 60

    var isGlassAccessory: Bool = false
    let onOpen: () -> Void
    @Environment(PlayerController.self) private var player

    var body: some View {
        if let track = player.currentTrack {
            let row = HStack(spacing: 12) {
                ArtworkView(artworkKey: track.artworkKey, size: isGlassAccessory ? 34 : 40)
                    .id(track.id)
                    .transition(.opacity)
                    .animation(.easeInOut(duration: 0.2), value: track.id)
                VStack(alignment: .leading, spacing: 1) {
                    Text(track.title).font(.subheadline.weight(.medium)).lineLimit(1)
                    Text(track.artist).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 8)
                if player.isBuffering {
                    ProgressView()
                        .frame(width: isGlassAccessory ? 36 : 44, height: isGlassAccessory ? 36 : 44)
                } else {
                    Button("Play or Pause", systemImage: player.isPlaying ? "pause.fill" : "play.fill") {
                        player.togglePlayPause()
                    }
                    .font(.title3)
                    .contentTransition(.symbolEffect(.replace))
                    .animation(.spring(duration: 0.25), value: player.isPlaying)
                    .frame(width: isGlassAccessory ? 36 : 44, height: isGlassAccessory ? 36 : 44)
                }
                Button("Next", systemImage: "forward.fill") { player.next() }
                    .font(.title3)
                    .frame(width: isGlassAccessory ? 36 : 44, height: isGlassAccessory ? 36 : 44)
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
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)
            .contextMenu {
                TrackMenuItems(ids: [track.id], favouriteID: track.id)
            }
            .accessibilityAddTraits(.isButton)
        }
    }
}
