import Foundation
import Observation
import SwiftUI

@MainActor @Observable
final class ToastManager {
    struct ToastItem: Identifiable, Equatable {
        let id: UUID
        let message: String
        let systemImage: String?
        let undoTitle: String?
        let undoAction: (() -> Void)?

        static func == (lhs: ToastItem, rhs: ToastItem) -> Bool {
            lhs.id == rhs.id
                && lhs.message == rhs.message
                && lhs.systemImage == rhs.systemImage
                && lhs.undoTitle == rhs.undoTitle
        }
    }

    nonisolated static let shared = ToastManager()

    private(set) var currentToast: ToastItem?
    @ObservationIgnored private var dismissTask: Task<Void, Never>?

    nonisolated init() {}

    func show(
        _ message: String,
        systemImage: String? = "checkmark.circle.fill",
        duration: TimeInterval = 3.5,
        undoTitle: String? = nil,
        onUndo: (() -> Void)? = nil
    ) {
        dismissTask?.cancel()
        let item = ToastItem(
            id: UUID(),
            message: message,
            systemImage: systemImage,
            undoTitle: undoTitle,
            undoAction: onUndo
        )
        currentToast = item
        let clampedDuration = max(duration, 0.5)
        dismissTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(clampedDuration))
            guard !Task.isCancelled, let self, self.currentToast?.id == item.id else { return }
            self.dismiss()
        }
    }

    func performUndo() {
        let action = currentToast?.undoAction
        dismiss()
        action?()
    }

    func dismiss() {
        dismissTask?.cancel()
        dismissTask = nil
        currentToast = nil
    }
}

extension EnvironmentValues {
    @Entry var toastManager = ToastManager.shared
}
