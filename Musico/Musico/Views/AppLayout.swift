import SwiftUI

/// How the main window is arranged: tabs on phones and narrow windows, a sidebar on wide ones.
enum AppLayout: Equatable {
    case tabs, sidebar

    /// Narrowest window that still fits a sidebar, the content and the player bar comfortably.
    static let minimumSidebarWidth: CGFloat = 700

    static func resolve(width: CGFloat, compactWidth: Bool, phone: Bool) -> AppLayout {
        !phone && !compactWidth && width >= minimumSidebarWidth ? .sidebar : .tabs
    }
}

/// What the right-hand panel of the sidebar layout shows.
enum SidePanel: String {
    case queue, lyrics
}
