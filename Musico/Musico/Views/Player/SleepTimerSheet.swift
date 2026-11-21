import SwiftUI

struct SleepTimerSheet: View {
    @Environment(PlayerController.self) private var player
    @Environment(\.dismiss) private var dismiss
    @State private var customMinutes = 20

    private static let presets = [5, 10, 15, 30, 45, 60, 90]

    var body: some View {
        NavigationStack {
            List {
                if player.sleepMode != .off {
                    Section {
                        HStack {
                            Image(systemName: "moon.zzz.fill").foregroundStyle(Color.accentColor)
                            status
                            Spacer()
                            Button("Cancel Timer", role: .destructive) { player.setSleep(.off) }
                                .buttonStyle(.borderless)
                        }
                    }
                }
                Section("Stop playing after") {
                    ForEach(Self.presets, id: \.self) { minutes in
                        Button("\(minutes) minutes") { start(minutes) }
                    }
                    HStack {
                        Stepper("\(customMinutes) minutes", value: $customMinutes, in: 1...240)
                        Button("Start") { start(customMinutes) }
                            .buttonStyle(.borderedProminent)
                    }
                    Button("End of current song") {
                        player.setSleep(.endOfTrack)
                        dismiss()
                    }
                }
            }
            .tint(.primary)
            .navigationTitle("Sleep Timer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
    }

    @ViewBuilder
    private var status: some View {
        switch player.sleepMode {
        case .until(let date):
            TimelineView(.periodic(from: .now, by: 1)) { context in
                Text("Stops in \(formatTime(max(date.timeIntervalSince(context.date), 0)))").monospacedDigit()
            }
        case .endOfTrack:
            Text("Stops at the end of this song")
        case .off:
            EmptyView()
        }
    }

    private func start(_ minutes: Int) {
        player.setSleep(.until(Date().addingTimeInterval(TimeInterval(minutes * 60))))
        dismiss()
    }
}
