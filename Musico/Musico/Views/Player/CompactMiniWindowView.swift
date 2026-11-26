import SwiftUI

struct CompactMiniWindowView: View {
    @Environment(PlayerController.self) private var player
    @Environment(LibraryStore.self) private var library
    @Environment(QueueStore.self) private var queues
    @Environment(\.themeManager) private var themeManager
    @Environment(\.languageManager) private var languageManager
    @Environment(\.toastManager) private var toastManager
    @Environment(\.openWindow) private var openWindow
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var progressFraction: Double {
        let total = max(player.duration, 0.01)
        return min(max(player.currentTime / total, 0), 1)
    }

    var body: some View {
        let track = player.currentTrack
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                ArtworkView(
                    artworkKey: track?.artworkKey,
                    remoteArtworkURL: track?.remoteArtworkURL,
                    size: 60,
                    cornerRadius: 10
                )
                .shadow(color: .black.opacity(0.16), radius: 6, y: 2)

                VStack(alignment: .leading, spacing: 2) {
                    Text(track?.title ?? L10n.tr("Not Playing"))
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(track.map { L10n.displayArtist($0.artist) } ?? L10n.tr("Pick a song from your library"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: 4) {
                    Button {
                        player.previous()
                    } label: {
                        Image(systemName: "backward.fill")
                            .font(.subheadline)
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.borderless)
                    .disabled(track == nil)
                    .accessibilityLabel(L10n.tr("Previous"))
                    .help(L10n.tr("Previous"))

                    if player.isBuffering {
                        ProgressView()
                            .controlSize(.small)
                            .frame(width: 30, height: 28)
                    } else {
                        Button {
                            player.togglePlayPause()
                        } label: {
                            Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                .font(.title3)
                                .frame(width: 30, height: 28)
                        }
                        .buttonStyle(.borderless)
                        .disabled(track == nil)
                        .contentTransition(.symbolEffect(.replace))
                        .animation(reduceMotion ? nil : .spring(duration: 0.25), value: player.isPlaying)
                        .accessibilityLabel(L10n.tr("Play or Pause"))
                        .help(L10n.tr("Play or Pause"))
                    }

                    Button {
                        player.next()
                    } label: {
                        Image(systemName: "forward.fill")
                            .font(.subheadline)
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.borderless)
                    .disabled(track == nil)
                    .accessibilityLabel(L10n.tr("Next"))
                    .help(L10n.tr("Next"))

                    Button {
                        openWindow(id: "main")
                    } label: {
                        Image(systemName: "macwindow")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L10n.tr("Open Main Window"))
                    .help(L10n.tr("Open Main Window"))
                }
            }

            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color.primary.opacity(0.12))
                    Capsule()
                        .fill(themeManager.accentColor)
                        .frame(width: proxy.size.width * progressFraction)
                        .animation(reduceMotion ? nil : .linear(duration: 0.2), value: progressFraction)
                }
            }
            .frame(height: 4)
            .accessibilityHidden(true)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(minWidth: 340, idealWidth: 360, maxWidth: 420, minHeight: 108, idealHeight: 116, maxHeight: 132)
        .background { ThemeBackdropView() }
        .environment(\.locale, languageManager.effectiveLocale)
        .applyTheme(themeManager)
    }
}
