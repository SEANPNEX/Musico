import SwiftUI
import UIKit

/// Renders per-region theme backdrops:
/// - `mainContent`: full-bleed color and optional blurred background image scaled together by window opacity.
/// - `sidebar`: solid sidebar background color (never renders background images or window transparency).
/// - `playerBar`: clean player bar base color + window opacity (never renders background images).
struct ThemeBackdropView: View {
    enum Role {
        case mainContent
        case sidebar
        case playerBar
    }

    var role: Role = .mainContent

    @Environment(\.themeManager) private var themeManager
    @Environment(\.colorScheme) private var colorScheme
    @State private var loadedImage: UIImage?

    var body: some View {
        switch role {
        case .mainContent:
            mainContentBackdrop
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
                .task(id: themeManager.backgroundImageURL) {
                    guard let imageURL = themeManager.backgroundImageURL else {
                        loadedImage = nil
                        return
                    }
                    let image = await Task.detached(priority: .userInitiated) {
                        UIImage(contentsOfFile: imageURL.path)
                    }.value
                    if !Task.isCancelled {
                        loadedImage = image
                    }
                }
        case .sidebar:
            sidebarBackdrop
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        case .playerBar:
            playerBarBackdrop
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder
    private var mainContentBackdrop: some View {
        let baseColor = themeManager.activeBackgroundColor ?? Color(.systemBackground)
        let windowOpacity = themeManager.effectiveBackgroundOpacity
        let imageOpacity = themeManager.settings.backgroundImageOpacity
        let imageBlur = themeManager.settings.backgroundImageBlur

        if let loadedImage {
            ZStack {
                baseColor
                GeometryReader { proxy in
                    Image(uiImage: loadedImage)
                        .resizable()
                        .scaledToFill()
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .blur(radius: imageBlur)
                        .clipped()
                        .opacity(imageOpacity)
                }
            }
            .compositingGroup()
            .opacity(windowOpacity)
        } else if let customColor = themeManager.activeBackgroundColor {
            customColor.opacity(windowOpacity)
        } else if themeManager.isWindowTranslucent {
            defaultMainBaseColor.opacity(windowOpacity)
        } else {
            Color.clear
        }
    }

    @ViewBuilder
    private var sidebarBackdrop: some View {
        if let sidebarColor = themeManager.activeSidebarBackgroundColor {
            sidebarColor
        } else if let mainColor = themeManager.activeBackgroundColor {
            mainColor
        } else {
            Color.clear
        }
    }

    @ViewBuilder
    private var playerBarBackdrop: some View {
        if themeManager.isWindowTranslucent || themeManager.activeBackgroundColor != nil {
            let base = themeManager.activeBackgroundColor ?? defaultPlayerBarBaseColor
            base.opacity(themeManager.effectiveBackgroundOpacity)
        } else {
            Color.clear
        }
    }

    private var defaultMainBaseColor: Color {
        let scheme = themeManager.preferredColorScheme ?? colorScheme
        return scheme == .dark ? Color(red: 0.09, green: 0.09, blue: 0.10) : Color.white
    }

    private var defaultPlayerBarBaseColor: Color {
        let scheme = themeManager.preferredColorScheme ?? colorScheme
        return scheme == .dark ? Color(red: 0.12, green: 0.12, blue: 0.13) : Color(red: 0.98, green: 0.98, blue: 0.98)
    }
}

/// Applies the theme accent tint, preferred color scheme, and adaptive/custom foreground styles.
struct ThemeStylingModifier: ViewModifier {
    let themeManager: ThemeManager

    func body(content: Content) -> some View {
        let tinted = content
            .environment(\.themeManager, themeManager)
            .tint(themeManager.accentColor)
            .preferredColorScheme(themeManager.preferredColorScheme)

        if let fg = themeManager.primaryForegroundColor {
            tinted.foregroundStyle(fg, fg.opacity(0.68))
        } else {
            tinted
        }
    }
}

extension View {
    func applyTheme(_ themeManager: ThemeManager) -> some View {
        modifier(ThemeStylingModifier(themeManager: themeManager))
    }

    @ViewBuilder
    func hiddenScrollBackgroundIfThemed(_ themeManager: ThemeManager) -> some View {
        if themeManager.hasCustomBackdrop {
            scrollContentBackground(.hidden)
        } else {
            self
        }
    }
}
