import SwiftUI

/// Single-line text that scrolls horizontally back and forth with pauses when wider than its container,
/// while honoring Reduce Motion and keeping single-line height so surrounding layouts never jump.
struct MarqueeText: View {
    let text: String
    var font: Font = .body
    var alignment: Alignment = .leading
    var speed: CGFloat = 30
    var pauseDuration: TimeInterval = 1.5

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var containerWidth: CGFloat = 0
    @State private var textWidth: CGFloat = 0
    @State private var offset: CGFloat = 0

    private var overflow: CGFloat {
        max(textWidth - containerWidth, 0)
    }

    private var shouldScroll: Bool {
        !reduceMotion && containerWidth > 0 && overflow > 2
    }

    private var taskKey: MarqueeKey {
        MarqueeKey(
            text: text,
            containerWidth: Int(containerWidth.rounded()),
            textWidth: Int(textWidth.rounded()),
            reduceMotion: reduceMotion
        )
    }

    var body: some View {
        Text(text.isEmpty ? " " : text)
            .font(font)
            .lineLimit(1)
            .opacity(0)
            .frame(minWidth: 0, maxWidth: textWidth > 0 ? textWidth : nil, alignment: alignment)
            .background {
                GeometryReader { geo in
                    Color.clear
                        .onAppear { containerWidth = geo.size.width }
                        .onChange(of: geo.size.width) { _, w in containerWidth = w }
                }
            }
            .background {
                Text(text)
                    .font(font)
                    .lineLimit(1)
                    .fixedSize(horizontal: true, vertical: false)
                    .hidden()
                    .background {
                        GeometryReader { geo in
                            Color.clear
                                .onAppear { textWidth = geo.size.width }
                                .onChange(of: geo.size.width) { _, w in textWidth = w }
                        }
                    }
            }
            .overlay(alignment: .leading) {
                if shouldScroll {
                    Text(text)
                        .font(font)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                        .offset(x: offset)
                } else {
                    Text(text)
                        .font(font)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .frame(maxWidth: .infinity, alignment: alignment)
                }
            }
            .clipped()
            .task(id: taskKey) {
                offset = 0
                guard shouldScroll else { return }
                let distance = overflow
                let travelDuration = max(Double(distance / max(speed, 1)), 0.6)
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(pauseDuration))
                    guard !Task.isCancelled else { break }
                    withAnimation(.linear(duration: travelDuration)) {
                        offset = -distance
                    }
                    try? await Task.sleep(for: .seconds(travelDuration))
                    guard !Task.isCancelled else { break }
                    try? await Task.sleep(for: .seconds(pauseDuration))
                    guard !Task.isCancelled else { break }
                    withAnimation(.linear(duration: travelDuration)) {
                        offset = 0
                    }
                    try? await Task.sleep(for: .seconds(travelDuration))
                }
            }
    }

    private struct MarqueeKey: Equatable {
        let text: String
        let containerWidth: Int
        let textWidth: Int
        let reduceMotion: Bool
    }
}
