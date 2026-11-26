import SwiftUI

struct SkeletonShimmerModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isAnimating = false

    func body(content: Content) -> some View {
        content
            .opacity(reduceMotion ? 0.65 : (isAnimating ? 0.42 : 0.88))
            .animation(
                reduceMotion
                    ? nil
                    : .easeInOut(duration: 0.95).repeatForever(autoreverses: true),
                value: isAnimating
            )
            .onAppear {
                guard !reduceMotion else { return }
                isAnimating = true
            }
    }
}

extension View {
    func skeletonShimmer() -> some View {
        modifier(SkeletonShimmerModifier())
    }
}

struct SkeletonTrackRowView: View {
    var titleWidth: CGFloat = 160
    var subtitleWidth: CGFloat = 104

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(Color(.tertiarySystemFill))
                .frame(width: 44, height: 44)

            VStack(alignment: .leading, spacing: 6) {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(Color(.tertiarySystemFill))
                    .frame(width: titleWidth, height: 13)

                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(Color(.quaternarySystemFill))
                    .frame(width: subtitleWidth, height: 10)
            }

            Spacer(minLength: 8)

            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(Color(.quaternarySystemFill))
                .frame(width: 34, height: 11)
        }
        .padding(.vertical, 3)
        .skeletonShimmer()
        .accessibilityHidden(true)
    }
}

struct SkeletonTrackListPlaceholder: View {
    var count: Int = 6

    private static let titleWidths: [CGFloat] = [180, 142, 204, 156, 172, 134]
    private static let subtitleWidths: [CGFloat] = [110, 92, 128, 86, 118, 96]

    var body: some View {
        ForEach(0..<max(count, 0), id: \.self) { index in
            SkeletonTrackRowView(
                titleWidth: Self.titleWidths[index % Self.titleWidths.count],
                subtitleWidth: Self.subtitleWidths[index % Self.subtitleWidths.count]
            )
            .listRowSeparator(.hidden)
        }
    }
}

struct SkeletonCardView: View {
    var cornerRadius: CGFloat = 12

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(Color(.tertiarySystemFill))
                .aspectRatio(1, contentMode: .fit)

            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(Color(.tertiarySystemFill))
                .frame(height: 13)
                .frame(maxWidth: 120)

            RoundedRectangle(cornerRadius: 4, style: .continuous)
                .fill(Color(.quaternarySystemFill))
                .frame(height: 10)
                .frame(maxWidth: 84)
        }
        .skeletonShimmer()
        .accessibilityHidden(true)
    }
}
