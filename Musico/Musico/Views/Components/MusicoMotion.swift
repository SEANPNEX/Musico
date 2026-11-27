import SwiftUI

/// Unified motion and transition language across Apple (iOS & Mac Catalyst) views.
enum MusicoMotion {
    static let peerSlideOffset: CGFloat = 14

    static func viewSwitch(reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : .spring(response: 0.28, dampingFraction: 0.90)
    }

    static func panelToggle(reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : .spring(response: 0.30, dampingFraction: 0.88)
    }

    static func playerPresentation(reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : .spring(response: 0.36, dampingFraction: 0.88)
    }

    static func contentChange(reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : .easeInOut(duration: 0.22)
    }

    static func microInteraction(reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : .easeOut(duration: 0.14)
    }

    static func symbolToggle(reduceMotion: Bool) -> Animation? {
        reduceMotion ? nil : .spring(response: 0.26, dampingFraction: 0.86)
    }

    /// Directional peer transition: inserts from the incoming side while dissolving out in place.
    static func peerSwitchTransition(forward: Bool, reduceMotion: Bool) -> AnyTransition {
        if reduceMotion {
            return .opacity
        }
        return .asymmetric(
            insertion: .opacity.combined(with: .offset(x: forward ? peerSlideOffset : -peerSlideOffset)),
            removal: .opacity
        )
    }

    static func overlayBottomTransition(reduceMotion: Bool) -> AnyTransition {
        reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity)
    }

    static func overlayTopScaleTransition(reduceMotion: Bool) -> AnyTransition {
        reduceMotion ? .opacity : .scale(scale: 0.96, anchor: .top).combined(with: .opacity)
    }
}
