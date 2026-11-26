import SwiftUI
import UIKit

/// Renders the configured theme background color and optional full-bleed background image
/// with opacity/scrim controls behind views and sheets.
struct ThemeBackdropView: View {
    @Environment(\.themeManager) private var themeManager
    @State private var loadedImage: UIImage?

    var body: some View {
        let imageURL = themeManager.backgroundImageURL
        let baseColor = themeManager.activeBackgroundColor ?? Color(.systemBackground)
        let windowOpacity = themeManager.effectiveBackgroundOpacity
        let imageOpacity = themeManager.settings.backgroundImageOpacity
        let imageBlur = themeManager.settings.backgroundImageBlur

        Group {
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
                Color(.systemBackground).opacity(windowOpacity)
            } else {
                Color.clear
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .task(id: imageURL) {
            guard let imageURL else {
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
