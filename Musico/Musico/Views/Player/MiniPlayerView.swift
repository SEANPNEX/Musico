import SwiftUI

struct MiniPlayerView: View {
    /// Approximate height, used to keep list content clear of it.
    static let height: CGFloat = 60

    let onOpen: () -> Void
    @Environment(PlayerController.self) private var player

    var body: some View {
        if let track = player.currentTrack {
            VStack(spacing: 0) {
                ProgressView(value: min(player.currentTime, max(player.duration, 0.01)), total: max(player.duration, 0.01))
                    .progressViewStyle(.linear)
                    .scaleEffect(x: 1, y: 0.5, anchor: .top)
                HStack(spacing: 12) {
                    ArtworkView(artworkKey: track.artworkKey, size: 40)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(track.title).font(.subheadline.weight(.medium)).lineLimit(1)
                        Text(track.artist).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    if player.isBuffering {
                        ProgressView()
                            .frame(width: 44, height: 44)
                    } else {
                        Button("Play or Pause", systemImage: player.isPlaying ? "pause.fill" : "play.fill") { player.togglePlayPause() }
                            .font(.title3)
                            .frame(width: 44, height: 44)
                    }
                    Button("Next", systemImage: "forward.fill") { player.next() }
                        .font(.title3)
                        .frame(width: 44, height: 44)
                }
                .labelStyle(.iconOnly)
                .tint(.primary)
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
            }
            .background(.bar)
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)
            .accessibilityAddTraits(.isButton)
        }
    }
}
