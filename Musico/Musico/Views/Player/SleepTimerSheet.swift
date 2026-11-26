import SwiftUI

struct SleepTimerSheet: View {
    @Environment(PlayerController.self) private var player: PlayerController?
    @Environment(\.themeManager) private var themeManager
    @Environment(\.dismiss) private var dismiss
    @State private var customMinutes = 20

    private static let presets = [5, 10, 15, 30, 45, 60, 90]

    var body: some View {
        NavigationStack {
            Group {
                if let player {
                    listContent(player: player)
                } else {
                    ProgressView()
                }
            }
            .background { ThemeBackdropView() }
            .tint(themeManager.accentColor)
            .navigationTitle(L10n.tr("Sleep Timer"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button(L10n.tr("Done")) { dismiss() } }
            }
        }
    }

    private func listContent(player: PlayerController) -> some View {
        List {
            if player.sleepMode != .off {
                Section {
                    HStack {
                        Image(systemName: "moon.zzz.fill").foregroundStyle(themeManager.accentColor)
                        status(for: player)
                        Spacer()
                        Button(L10n.tr("Cancel Timer"), role: .destructive) { player.setSleep(.off) }
                            .buttonStyle(.borderless)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .layoutPriority(1)
                    }
                }
            }
            Section(L10n.tr("Stop playing after")) {
                ForEach(Self.presets, id: \.self) { minutes in
                    Button(L10n.tr("%@ minutes", String(minutes))) { start(minutes, player: player) }
                }
                HStack {
                    Stepper(L10n.tr("%@ minutes", String(customMinutes)), value: $customMinutes, in: 1...240)
                    Button(L10n.tr("Start")) { start(customMinutes, player: player) }
                        .buttonStyle(.borderedProminent)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .layoutPriority(1)
                }
                Button(L10n.tr("End of current song")) {
                    player.setSleep(.endOfTrack)
                    dismiss()
                }
            }
        }
        .hiddenScrollBackgroundIfThemed(themeManager)
    }

    @ViewBuilder
    private func status(for player: PlayerController) -> some View {
        switch player.sleepMode {
        case .until(let date):
            TimelineView(.periodic(from: .now, by: 1)) { context in
                Text(L10n.tr("Stops in %@", formatTime(max(date.timeIntervalSince(context.date), 0)))).monospacedDigit()
            }
        case .endOfTrack:
            Text(L10n.tr("Stops at the end of this song"))
        case .off:
            EmptyView()
        }
    }

    private func start(_ minutes: Int, player: PlayerController) {
        player.setSleep(.until(Date().addingTimeInterval(TimeInterval(minutes * 60))))
        dismiss()
    }
}
