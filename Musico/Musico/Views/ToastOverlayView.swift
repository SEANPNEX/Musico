import SwiftUI

struct ToastOverlayView: View {
    var bottomPadding: CGFloat = 18

    @Environment(\.toastManager) private var toastManager
    @Environment(\.themeManager) private var themeManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack(alignment: .bottom) {
            if let toast = toastManager.currentToast {
                HStack(spacing: 10) {
                    if let systemImage = toast.systemImage {
                        Image(systemName: systemImage)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(themeManager.accentColor)
                    }
                    Text(toast.message)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(2)

                    if toast.undoAction != nil {
                        Button(toast.undoTitle ?? L10n.tr("Undo")) {
                            toastManager.performUndo()
                        }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(themeManager.accentColor)
                        .buttonStyle(.borderless)
                        .padding(.leading, 4)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.regularMaterial, in: Capsule())
                .overlay {
                    Capsule()
                        .strokeBorder(Color.primary.opacity(0.12), lineWidth: 0.75)
                        .allowsHitTesting(false)
                }
                .shadow(color: .black.opacity(0.16), radius: 12, y: 4)
                .padding(.horizontal, 16)
                .padding(.bottom, bottomPadding)
                .contentShape(Capsule())
                .onTapGesture {
                    toastManager.dismiss()
                }
                .transition(MusicoMotion.overlayBottomTransition(reduceMotion: reduceMotion))
                .id(toast.id)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .allowsHitTesting(toastManager.currentToast != nil)
        .animation(MusicoMotion.panelToggle(reduceMotion: reduceMotion), value: toastManager.currentToast?.id)
        .accessibilityElement(children: .contain)
    }
}
