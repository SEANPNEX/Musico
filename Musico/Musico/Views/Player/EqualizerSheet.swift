import SwiftUI

struct EqualizerSheet: View {
    @Environment(PlayerController.self) private var player
    @Environment(\.dismiss) private var dismiss

    private static let labels = ["31", "62", "125", "250", "500", "1K", "2K", "4K", "8K", "16K"]

    var body: some View {
        @Bindable var player = player
        NavigationStack {
            ScrollView {
            VStack(spacing: 24) {
                Toggle("Equalizer", isOn: $player.eq.enabled)

                HStack {
                    Text("Preset")
                    Spacer()
                    Menu(player.eq.presetName ?? "Custom") {
                        ForEach(EQPreset.all, id: \.name) { preset in
                            Button(preset.name) {
                                player.eq.gains = preset.gains
                                player.eq.presetName = preset.name
                            }
                        }
                    }
                }

                HStack(alignment: .bottom, spacing: 0) {
                    ForEach(Array(Self.labels.enumerated()), id: \.offset) { index, label in
                        VStack(spacing: 6) {
                            Text(gainText(player.eq.gains[index]))
                                .font(.system(size: 10).monospacedDigit())
                                .foregroundStyle(.secondary)
                            VerticalSlider(value: band(index), range: EQSettings.gainRange)
                                .frame(height: 170)
                            Text(label).font(.caption2).foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel("\(label) hertz")
                    }
                }
                .disabled(!player.eq.enabled)
                .opacity(player.eq.enabled ? 1 : 0.4)

                VStack(alignment: .leading, spacing: 4) {
                    Text("Preamp \(gainText(player.eq.preamp)) dB").font(.footnote).foregroundStyle(.secondary)
                    Slider(value: $player.eq.preamp, in: EQSettings.gainRange, step: 0.5)
                }
                .disabled(!player.eq.enabled)

            }
            .padding(20)
            }
            .navigationTitle("Equalizer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Reset") {
                        player.eq.gains = EQPreset.all[0].gains
                        player.eq.preamp = 0
                        player.eq.presetName = "Flat"
                    }
                }
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
    }

    private func gainText(_ gain: Float) -> String {
        gain == 0 ? "0" : String(format: "%+.0f", gain)
    }

    private func band(_ index: Int) -> Binding<Float> {
        Binding(
            get: { player.eq.gains[index] },
            set: { newValue in
                guard player.eq.gains[index] != newValue else { return }
                player.eq.gains[index] = newValue
                player.eq.presetName = nil
            })
    }
}

/// A slider drawn bottom-to-top. Drag anywhere on the track to set the value.
struct VerticalSlider: View {
    @Binding var value: Float
    let range: ClosedRange<Float>

    var body: some View {
        GeometryReader { proxy in
            let height = proxy.size.height
            let fraction = CGFloat((value - range.lowerBound) / (range.upperBound - range.lowerBound))
            let y = height * (1 - fraction)
            ZStack(alignment: .top) {
                Capsule().fill(Color(.systemFill)).frame(width: 4)
                Rectangle().fill(Color(.separator)).frame(width: 12, height: 1).offset(y: height / 2)
                Circle()
                    .fill(Color.accentColor)
                    .frame(width: 20, height: 20)
                    .offset(y: y - 10)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { drag in
                let clamped = min(max(drag.location.y, 0), height)
                let raw = range.lowerBound + Float(1 - clamped / height) * (range.upperBound - range.lowerBound)
                value = (raw * 2).rounded() / 2
            })
        }
        .frame(minWidth: 28)
    }
}
