#if !targetEnvironment(macCatalyst)
import SwiftUI
import UIKit

/// Transparent UIKit handle container backing the `line.3.horizontal` reorder grabber inside an iOS `List`.
///
/// Using a UIKit view + gesture recognizers avoids SwiftUI `.draggable` / `.contextMenu` / scroll-pan
/// conflicts by driving the enclosing `UICollectionView`'s native interactive movement directly.
internal final class HandleContainerView: UIView {
    weak var coordinator: IOSListReorderHandleOverlay.Coordinator?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isUserInteractionEnabled = true
        isMultipleTouchEnabled = false
        isAccessibilityElement = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }
}

internal struct IOSListReorderHandleOverlay: UIViewRepresentable {
    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> HandleContainerView {
        let view = HandleContainerView()
        context.coordinator.attach(to: view)
        return view
    }

    func updateUIView(_ uiView: HandleContainerView, context: Context) {
        uiView.coordinator = context.coordinator
        context.coordinator.handleView = uiView
    }

    static func dismantleUIView(_ uiView: HandleContainerView, coordinator: Coordinator) {
        coordinator.cancelIfActive()
        uiView.coordinator = nil
        coordinator.handleView = nil
    }

    @MainActor
    internal final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        weak var handleView: HandleContainerView?
        private(set) lazy var panGesture: UIPanGestureRecognizer = {
            let pan = UIPanGestureRecognizer(target: self, action: #selector(handlePan(_:)))
            pan.maximumNumberOfTouches = 1
            pan.cancelsTouchesInView = true
            pan.delaysTouchesBegan = false
            pan.delegate = self
            return pan
        }()

        private(set) lazy var longPressGesture: UILongPressGestureRecognizer = {
            let press = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
            press.minimumPressDuration = 0.15
            press.allowableMovement = 10
            press.cancelsTouchesInView = true
            press.delegate = self
            return press
        }()

        private let feedbackGenerator = UIImpactFeedbackGenerator(style: .light)
        private weak var activeCollectionView: UICollectionView?
        private weak var activeGesture: UIGestureRecognizer?

        internal var isReordering: Bool {
            activeCollectionView != nil
        }

        func attach(to view: HandleContainerView) {
            handleView = view
            view.coordinator = self
            if panGesture.view !== view {
                view.addGestureRecognizer(panGesture)
            }
            if longPressGesture.view !== view {
                view.addGestureRecognizer(longPressGesture)
            }
        }

        internal static func enclosingCollectionView(from view: UIView) -> (collectionView: UICollectionView, cell: UICollectionViewCell?)? {
            var current: UIView? = view.superview
            var foundCell: UICollectionViewCell?
            while let candidate = current {
                if foundCell == nil, let cell = candidate as? UICollectionViewCell {
                    foundCell = cell
                }
                if let collectionView = candidate as? UICollectionView {
                    return (collectionView, foundCell)
                }
                current = candidate.superview
            }
            return nil
        }

        internal static func shouldBeginPan(velocity: CGPoint, translation: CGPoint) -> Bool {
            if abs(velocity.x) > abs(velocity.y) * 1.5 && abs(velocity.x) > 30 {
                return false
            }
            if abs(translation.x) > abs(translation.y) * 1.5 && abs(translation.x) > 8 {
                return false
            }
            return true
        }

        @discardableResult
        internal func beginInteractiveReorder(at locationInCollectionView: CGPoint? = nil, gesture: UIGestureRecognizer? = nil) -> Bool {
            guard !isReordering,
                  let handleView,
                  let (collectionView, cell) = Self.enclosingCollectionView(from: handleView) else {
                return false
            }

            let resolvedLocation: CGPoint
            if let locationInCollectionView {
                resolvedLocation = locationInCollectionView
            } else if let gesture {
                resolvedLocation = gesture.location(in: collectionView)
            } else {
                let handleCenter = CGPoint(x: handleView.bounds.midX, y: handleView.bounds.midY)
                resolvedLocation = handleView.convert(handleCenter, to: collectionView)
            }

            guard let indexPath = cell.flatMap({ collectionView.indexPath(for: $0) })
                ?? collectionView.indexPathForItem(at: resolvedLocation) else {
                return false
            }

            guard collectionView.beginInteractiveMovementForItem(at: indexPath) else {
                return false
            }

            activeCollectionView = collectionView
            activeGesture = gesture
            feedbackGenerator.impactOccurred()
            updateInteractiveReorder(to: resolvedLocation)
            return true
        }

        internal func updateInteractiveReorder(to locationInCollectionView: CGPoint) {
            guard let collectionView = activeCollectionView else { return }
            let clampedX = collectionView.bounds.midX
            collectionView.updateInteractiveMovementTargetPosition(CGPoint(x: clampedX, y: locationInCollectionView.y))
        }

        internal func endInteractiveReorder() {
            guard let collectionView = activeCollectionView else {
                activeGesture = nil
                return
            }
            activeCollectionView = nil
            activeGesture = nil
            collectionView.endInteractiveMovement()
        }

        internal func cancelIfActive() {
            guard let collectionView = activeCollectionView else {
                activeGesture = nil
                return
            }
            activeCollectionView = nil
            activeGesture = nil
            collectionView.cancelInteractiveMovement()
        }

        private func resetGesture(_ gesture: UIGestureRecognizer) {
            guard gesture.isEnabled else { return }
            gesture.isEnabled = false
            gesture.isEnabled = true
        }

        @objc private func handlePan(_ gesture: UIPanGestureRecognizer) {
            handleGestureStateChange(gesture, sibling: longPressGesture)
        }

        @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
            handleGestureStateChange(gesture, sibling: panGesture)
        }

        private func handleGestureStateChange(_ gesture: UIGestureRecognizer, sibling: UIGestureRecognizer) {
            switch gesture.state {
            case .began:
                resetGesture(sibling)
                if !beginInteractiveReorder(gesture: gesture) {
                    resetGesture(gesture)
                }
            case .changed:
                guard activeGesture === gesture, let collectionView = activeCollectionView else { return }
                updateInteractiveReorder(to: gesture.location(in: collectionView))
            case .ended:
                guard activeGesture === gesture else { return }
                endInteractiveReorder()
            case .cancelled, .failed:
                guard activeGesture === gesture else { return }
                cancelIfActive()
            default:
                break
            }
        }

        // MARK: - UIGestureRecognizerDelegate

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            feedbackGenerator.prepare()
            return true
        }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard !isReordering else { return false }
            if gestureRecognizer === panGesture {
                return Self.shouldBeginPan(
                    velocity: panGesture.velocity(in: panGesture.view),
                    translation: panGesture.translation(in: panGesture.view)
                )
            }
            if gestureRecognizer === longPressGesture {
                return true
            }
            return true
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            false
        }

        func gestureRecognizer(
            _ gestureRecognizer: UIGestureRecognizer,
            shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
        ) -> Bool {
            guard let handleView,
                  let otherView = otherGestureRecognizer.view,
                  otherGestureRecognizer !== panGesture,
                  otherGestureRecognizer !== longPressGesture,
                  handleView.isDescendant(of: otherView) else {
                return false
            }

            if gestureRecognizer === panGesture {
                return otherGestureRecognizer is UIPanGestureRecognizer
                    || otherGestureRecognizer is UILongPressGestureRecognizer
            }

            if gestureRecognizer === longPressGesture {
                if otherGestureRecognizer is UIPanGestureRecognizer {
                    return false
                }
                if otherGestureRecognizer is UILongPressGestureRecognizer {
                    return true
                }
                if let cell = Self.enclosingCollectionView(from: handleView)?.cell,
                   otherView === cell || otherView.isDescendant(of: cell) {
                    return true
                }
                let className = NSStringFromClass(type(of: otherGestureRecognizer)).lowercased()
                return className.contains("contextmenu")
                    || className.contains("click")
                    || className.contains("preview")
            }

            return false
        }
    }
}
#endif
