import SwiftUI

/// The seek slider with elapsed and remaining time.
struct PlaybackScrubber: View {
    enum Style {
        /// Times on a line under the slider (full player).
        case stacked
        /// Times on either side of the slider (player bar).
        case inline
    }

    var style: Style = .stacked
    @Environment(PlayerController.self) private var player
    /// Slider position while the user is dragging. Only used while `isScrubbing`:
    /// the system slider also writes through its binding when playback moves the value.
    @State private var scrubTime: Double = 0
    @State private var isScrubbing = false

    var body: some View {
        let duration = max(player.duration, 1)
        let shown = isScrubbing ? scrubTime : min(player.currentTime, duration)
        // The slider also calls `set` while SwiftUI is updating the view (when playback moves the
        // value); writing state then is what SwiftUI warns about, so only a drag may write.
        let slider = Slider(value: Binding(get: { shown }, set: { if isScrubbing { scrubTime = $0 } }), in: 0...duration) { editing in
            if editing {
                scrubTime = min(player.currentTime, duration)
                isScrubbing = true
            } else if isScrubbing {
                player.seek(to: scrubTime)
                isScrubbing = false
            }
        }
        let elapsed = Text(formatTime(shown))
            .contentTransition(.numericText())
        let remaining = Text("-" + formatTime(max(player.duration - shown, 0)))
            .contentTransition(.numericText())
        Group {
            switch style {
            case .stacked:
                VStack(spacing: 2) {
                    slider
                    HStack {
                        elapsed
                        Spacer()
                        remaining
                    }
                }
            case .inline:
                HStack(spacing: 8) {
                    elapsed.frame(minWidth: 40, alignment: .trailing)
                    slider.controlSize(.mini)
                    remaining.frame(minWidth: 44, alignment: .leading)
                }
            }
        }
        .font(.caption.monospacedDigit())
        .foregroundStyle(.secondary)
    }
}
