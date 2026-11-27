import Foundation
import Observation
import SwiftUI
import UIKit

enum AppThemeMode: String, CaseIterable, Identifiable, Codable, Sendable {
    case system
    case light
    case dark

    var id: String { rawValue }

    @MainActor
    var displayName: String {
        switch self {
        case .system: L10n.tr("Follow System")
        case .light: L10n.tr("Light")
        case .dark: L10n.tr("Dark")
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

enum BackgroundColorPreset: String, CaseIterable, Identifiable, Codable, Sendable {
    case `default`
    case obsidian
    case midnight
    case warmCharcoal
    case forestNight
    case paper
    case mist
    case blossom
    case custom

    var id: String { rawValue }

    var hexString: String? {
        switch self {
        case .default: nil
        case .obsidian: "#09090B"
        case .midnight: "#0F172A"
        case .warmCharcoal: "#1C1917"
        case .forestNight: "#0F1D17"
        case .paper: "#F8F6F1"
        case .mist: "#EEF3F8"
        case .blossom: "#FCF2F4"
        case .custom: nil
        }
    }

    @MainActor
    var displayName: String {
        switch self {
        case .default: L10n.tr("Default")
        case .obsidian: L10n.tr("Obsidian")
        case .midnight: L10n.tr("Midnight")
        case .warmCharcoal: L10n.tr("Warm Charcoal")
        case .forestNight: L10n.tr("Forest Night")
        case .paper: L10n.tr("Paper")
        case .mist: L10n.tr("Mist")
        case .blossom: L10n.tr("Blossom")
        case .custom: L10n.tr("Custom")
        }
    }
}

enum ForegroundColorPreset: String, CaseIterable, Identifiable, Codable, Sendable {
    case coral
    case ocean
    case emerald
    case amethyst
    case amber
    case rose
    case monochrome
    case custom

    var id: String { rawValue }

    var accentHex: String? {
        switch self {
        case .coral: "#E85440"
        case .ocean: "#2563EB"
        case .emerald: "#10B981"
        case .amethyst: "#8B5CF6"
        case .amber: "#F59E0B"
        case .rose: "#F43F5E"
        case .monochrome: "#71717A"
        case .custom: nil
        }
    }

    @MainActor
    var displayName: String {
        switch self {
        case .coral: L10n.tr("Coral")
        case .ocean: L10n.tr("Ocean")
        case .emerald: L10n.tr("Emerald")
        case .amethyst: L10n.tr("Amethyst")
        case .amber: L10n.tr("Amber")
        case .rose: L10n.tr("Rose")
        case .monochrome: L10n.tr("Monochrome")
        case .custom: L10n.tr("Custom")
        }
    }
}

enum LyricsFontSize: String, CaseIterable, Identifiable, Codable, Sendable {
    case compact
    case medium
    case large

    var id: String { rawValue }

    @MainActor
    var displayName: String {
        switch self {
        case .compact: L10n.tr("Compact")
        case .medium: L10n.tr("Medium")
        case .large: L10n.tr("Large")
        }
    }
}

enum LyricsAlignment: String, CaseIterable, Identifiable, Codable, Sendable {
    case leading
    case center

    var id: String { rawValue }

    @MainActor
    var displayName: String {
        switch self {
        case .leading: L10n.tr("Leading")
        case .center: L10n.tr("Center")
        }
    }
}

struct AppearanceSettings: Codable, Equatable, Sendable {
    static let defaultAccentHex = "#E85440"
    static let lightForegroundHex = "#F5F5F7"
    static let darkForegroundHex = "#141416"
    static let defaultBackgroundImageOpacity: Double = 0.35
    static let defaultBackgroundOpacity: Double = 1.0
    static let defaultBackgroundImageBlur: Double = 0.0
    static let backgroundImageOpacityRange: ClosedRange<Double> = 0.10...1.0
    static let backgroundOpacityRange: ClosedRange<Double> = 0.15...1.0
    static let sidebarOpacityRange: ClosedRange<Double> = 0.15...1.0
    static let backgroundImageBlurRange: ClosedRange<Double> = 0.0...24.0

    var themeMode: AppThemeMode
    var backgroundPreset: BackgroundColorPreset
    var customBackgroundHex: String?
    var sidebarBackgroundPreset: BackgroundColorPreset
    var customSidebarBackgroundHex: String?
    var foregroundPreset: ForegroundColorPreset
    var customAccentHex: String?
    var customForegroundHex: String?
    var backgroundImageFileName: String?
    var backgroundImageOpacity: Double
    var backgroundOpacity: Double
    var sidebarOpacity: Double?
    var backgroundImageBlur: Double
    var lyricsFontSize: LyricsFontSize
    var lyricsAlignment: LyricsAlignment

    init(
        themeMode: AppThemeMode = .system,
        backgroundPreset: BackgroundColorPreset = .default,
        customBackgroundHex: String? = nil,
        sidebarBackgroundPreset: BackgroundColorPreset = .default,
        customSidebarBackgroundHex: String? = nil,
        foregroundPreset: ForegroundColorPreset = .coral,
        customAccentHex: String? = nil,
        customForegroundHex: String? = nil,
        backgroundImageFileName: String? = nil,
        backgroundImageOpacity: Double = AppearanceSettings.defaultBackgroundImageOpacity,
        backgroundOpacity: Double = AppearanceSettings.defaultBackgroundOpacity,
        sidebarOpacity: Double? = nil,
        backgroundImageBlur: Double = AppearanceSettings.defaultBackgroundImageBlur,
        lyricsFontSize: LyricsFontSize = .medium,
        lyricsAlignment: LyricsAlignment = .leading
    ) {
        self.themeMode = themeMode
        self.backgroundPreset = backgroundPreset
        self.customBackgroundHex = customBackgroundHex.flatMap(ThemeColorHex.normalize)
        self.sidebarBackgroundPreset = sidebarBackgroundPreset
        self.customSidebarBackgroundHex = customSidebarBackgroundHex.flatMap(ThemeColorHex.normalize)
        self.foregroundPreset = foregroundPreset
        self.customAccentHex = customAccentHex.flatMap(ThemeColorHex.normalize)
        self.customForegroundHex = customForegroundHex.flatMap(ThemeColorHex.normalize)
        self.backgroundImageFileName = backgroundImageFileName
        self.backgroundImageOpacity = Self.clampBackgroundImageOpacity(backgroundImageOpacity)
        self.backgroundOpacity = Self.clampBackgroundOpacity(backgroundOpacity)
        self.sidebarOpacity = sidebarOpacity.map(Self.clampSidebarOpacity)
        self.backgroundImageBlur = Self.clampBackgroundImageBlur(backgroundImageBlur)
        self.lyricsFontSize = lyricsFontSize
        self.lyricsAlignment = lyricsAlignment
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let themeMode = try container.decodeIfPresent(AppThemeMode.self, forKey: .themeMode) ?? .system
        let backgroundPreset = try container.decodeIfPresent(BackgroundColorPreset.self, forKey: .backgroundPreset) ?? .default
        let customBackgroundHex = try container.decodeIfPresent(String.self, forKey: .customBackgroundHex)
        let sidebarBackgroundPreset = try container.decodeIfPresent(BackgroundColorPreset.self, forKey: .sidebarBackgroundPreset) ?? .default
        let customSidebarBackgroundHex = try container.decodeIfPresent(String.self, forKey: .customSidebarBackgroundHex)
        let foregroundPreset = try container.decodeIfPresent(ForegroundColorPreset.self, forKey: .foregroundPreset) ?? .coral
        let customAccentHex = try container.decodeIfPresent(String.self, forKey: .customAccentHex)
        let customForegroundHex = try container.decodeIfPresent(String.self, forKey: .customForegroundHex)
        let backgroundImageFileName = try container.decodeIfPresent(String.self, forKey: .backgroundImageFileName)
        let backgroundImageOpacity = try container.decodeIfPresent(Double.self, forKey: .backgroundImageOpacity) ?? Self.defaultBackgroundImageOpacity
        let backgroundOpacity = try container.decodeIfPresent(Double.self, forKey: .backgroundOpacity) ?? Self.defaultBackgroundOpacity
        let sidebarOpacity = try container.decodeIfPresent(Double.self, forKey: .sidebarOpacity)
        let backgroundImageBlur = try container.decodeIfPresent(Double.self, forKey: .backgroundImageBlur) ?? Self.defaultBackgroundImageBlur
        let lyricsFontSize = try container.decodeIfPresent(LyricsFontSize.self, forKey: .lyricsFontSize) ?? .medium
        let lyricsAlignment = try container.decodeIfPresent(LyricsAlignment.self, forKey: .lyricsAlignment) ?? .leading

        self.init(
            themeMode: themeMode,
            backgroundPreset: backgroundPreset,
            customBackgroundHex: customBackgroundHex,
            sidebarBackgroundPreset: sidebarBackgroundPreset,
            customSidebarBackgroundHex: customSidebarBackgroundHex,
            foregroundPreset: foregroundPreset,
            customAccentHex: customAccentHex,
            customForegroundHex: customForegroundHex,
            backgroundImageFileName: backgroundImageFileName,
            backgroundImageOpacity: backgroundImageOpacity,
            backgroundOpacity: backgroundOpacity,
            sidebarOpacity: sidebarOpacity,
            backgroundImageBlur: backgroundImageBlur,
            lyricsFontSize: lyricsFontSize,
            lyricsAlignment: lyricsAlignment
        )
    }

    static func clampBackgroundImageOpacity(_ value: Double) -> Double {
        guard value.isFinite else { return defaultBackgroundImageOpacity }
        return min(max(value, backgroundImageOpacityRange.lowerBound), backgroundImageOpacityRange.upperBound)
    }

    static func clampBackgroundOpacity(_ value: Double) -> Double {
        guard value.isFinite else { return defaultBackgroundOpacity }
        return min(max(value, backgroundOpacityRange.lowerBound), backgroundOpacityRange.upperBound)
    }

    static func clampSidebarOpacity(_ value: Double) -> Double {
        guard value.isFinite else { return defaultBackgroundOpacity }
        return min(max(value, sidebarOpacityRange.lowerBound), sidebarOpacityRange.upperBound)
    }

    static func clampBackgroundImageBlur(_ value: Double) -> Double {
        guard value.isFinite else { return defaultBackgroundImageBlur }
        return min(max(value, backgroundImageBlurRange.lowerBound), backgroundImageBlurRange.upperBound)
    }
}

enum ThemeColorHex {
    static func normalize(_ raw: String) -> String? {
        var cleaned = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.hasPrefix("#") {
            cleaned.removeFirst()
        }
        guard cleaned.count == 6 || cleaned.count == 3 else { return nil }
        let expanded: String
        if cleaned.count == 3 {
            expanded = cleaned.map { "\($0)\($0)" }.joined()
        } else {
            expanded = cleaned
        }
        guard expanded.allSatisfy({ $0.isHexDigit }) else { return nil }
        return "#\(expanded.uppercased())"
    }

    static func parseRGB(_ raw: String) -> (r: Double, g: Double, b: Double)? {
        guard let normalized = normalize(raw) else { return nil }
        let hex = String(normalized.dropFirst())
        guard let value = UInt32(hex, radix: 16) else { return nil }
        let r = Double((value >> 16) & 0xFF) / 255.0
        let g = Double((value >> 8) & 0xFF) / 255.0
        let b = Double(value & 0xFF) / 255.0
        return (r, g, b)
    }

    static func formatRGB(r: Double, g: Double, b: Double) -> String {
        let cr = Int((min(max(r, 0), 1) * 255).rounded())
        let cg = Int((min(max(g, 0), 1) * 255).rounded())
        let cb = Int((min(max(b, 0), 1) * 255).rounded())
        return String(format: "#%02X%02X%02X", cr, cg, cb)
    }

    static func linearizeSRGB(_ component: Double) -> Double {
        let c = min(max(component, 0), 1)
        return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
    }

    static func relativeLuminance(r: Double, g: Double, b: Double) -> Double {
        0.2126 * linearizeSRGB(r) + 0.7152 * linearizeSRGB(g) + 0.0722 * linearizeSRGB(b)
    }

    static func relativeLuminance(hex: String) -> Double? {
        guard let rgb = parseRGB(hex) else { return nil }
        return relativeLuminance(r: rgb.r, g: rgb.g, b: rgb.b)
    }

    static func contrastRatio(hexA: String, hexB: String) -> Double? {
        guard let lumA = relativeLuminance(hex: hexA),
              let lumB = relativeLuminance(hex: hexB) else {
            return nil
        }
        let lighter = max(lumA, lumB)
        let darker = min(lumA, lumB)
        return (lighter + 0.05) / (darker + 0.05)
    }

    static func adjustForContrast(hex: String, againstBackgroundHex bgHex: String, minimumRatio: Double) -> String? {
        guard let rgb = parseRGB(hex),
              let bgLum = relativeLuminance(hex: bgHex) else {
            return nil
        }
        if let current = contrastRatio(hexA: hex, hexB: bgHex), current >= minimumRatio {
            return normalize(hex)
        }
        let target: (r: Double, g: Double, b: Double) = bgLum < 0.179 ? (1, 1, 1) : (0, 0, 0)
        var bestHex = formatRGB(r: target.r, g: target.g, b: target.b)
        for step in 1...100 {
            let t = Double(step) / 100.0
            let candidate = formatRGB(
                r: rgb.r + (target.r - rgb.r) * t,
                g: rgb.g + (target.g - rgb.g) * t,
                b: rgb.b + (target.b - rgb.b) * t
            )
            if let ratio = contrastRatio(hexA: candidate, hexB: bgHex), ratio >= minimumRatio {
                bestHex = candidate
                break
            }
        }
        return bestHex
    }
}

extension UIColor {
    convenience init?(hex: String) {
        guard let rgb = ThemeColorHex.parseRGB(hex) else { return nil }
        self.init(red: rgb.r, green: rgb.g, blue: rgb.b, alpha: 1.0)
    }

    var hexString: String? {
        guard let srgb = CGColorSpace(name: CGColorSpace.sRGB),
              let converted = cgColor.converted(to: srgb, intent: .defaultIntent, options: nil),
              let components = converted.components,
              components.count >= 3 else {
            var r: CGFloat = 0
            var g: CGFloat = 0
            var b: CGFloat = 0
            var a: CGFloat = 0
            guard getRed(&r, green: &g, blue: &b, alpha: &a) else { return nil }
            return ThemeColorHex.formatRGB(r: Double(r), g: Double(g), b: Double(b))
        }
        return ThemeColorHex.formatRGB(r: Double(components[0]), g: Double(components[1]), b: Double(components[2]))
    }
}

extension Color {
    init?(hex: String) {
        guard let rgb = ThemeColorHex.parseRGB(hex) else { return nil }
        self.init(.sRGB, red: rgb.r, green: rgb.g, blue: rgb.b, opacity: 1.0)
    }

    func toHexString() -> String? {
        UIColor(self).hexString
    }
}

enum ThemeAppearanceError: LocalizedError {
    case emptyImageData
    case unreadableImage

    var errorDescription: String? {
        switch self {
        case .emptyImageData:
            return L10n.trNonisolated("The selected image file is empty.")
        case .unreadableImage:
            return L10n.trNonisolated("The selected image could not be loaded.")
        }
    }
}

@MainActor @Observable
final class ThemeManager {
    nonisolated static let shared = ThemeManager(directory: Persistence.supportDirectory)

    private(set) var settings: AppearanceSettings {
        get {
            access(keyPath: \.settings)
            return storedSettings
        }
        set {
            guard storedSettings != newValue else { return }
            withMutation(keyPath: \.settings) {
                storedSettings = newValue
            }
        }
    }

    @ObservationIgnored private nonisolated(unsafe) var storedSettings: AppearanceSettings
    @ObservationIgnored private let directory: URL
    @ObservationIgnored private let settingsURL: URL
    @ObservationIgnored private let appearanceDirectory: URL

    nonisolated init(directory: URL) {
        self.directory = directory
        self.settingsURL = directory.appendingPathComponent("appearance.json")
        self.appearanceDirectory = directory.appendingPathComponent("Appearance", isDirectory: true)
        self.storedSettings = Persistence.load(AppearanceSettings.self, from: settingsURL) ?? AppearanceSettings()
    }

    var backgroundImageURL: URL? {
        guard let fileName = settings.backgroundImageFileName, !fileName.isEmpty else { return nil }
        let url = appearanceDirectory.appendingPathComponent(fileName)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    var hasBackgroundImage: Bool {
        backgroundImageURL != nil
    }

    var activeBackgroundHex: String? {
        switch settings.backgroundPreset {
        case .default:
            return nil
        case .custom:
            return settings.customBackgroundHex
        default:
            return settings.backgroundPreset.hexString
        }
    }

    var activeBackgroundColor: Color? {
        activeBackgroundHex.flatMap { Color(hex: $0) }
    }

    var activeBackgroundLuminance: Double? {
        activeBackgroundHex.flatMap { ThemeColorHex.relativeLuminance(hex: $0) }
    }

    var effectiveBackgroundOpacity: Double {
        #if targetEnvironment(macCatalyst)
        AppearanceSettings.clampBackgroundOpacity(settings.backgroundOpacity)
        #else
        1.0
        #endif
    }

    var effectiveSidebarOpacity: Double {
        1.0
    }

    var activeSidebarBackgroundHex: String? {
        switch settings.sidebarBackgroundPreset {
        case .default:
            return nil
        case .custom:
            return settings.customSidebarBackgroundHex
        default:
            return settings.sidebarBackgroundPreset.hexString
        }
    }

    var activeSidebarBackgroundColor: Color? {
        activeSidebarBackgroundHex.flatMap { Color(hex: $0) }
    }

    var activeSidebarBackgroundLuminance: Double? {
        activeSidebarBackgroundHex.flatMap { ThemeColorHex.relativeLuminance(hex: $0) }
    }

    var resolvedSidebarForegroundHex: String? {
        if let lum = activeSidebarBackgroundLuminance {
            return lum < 0.179 ? AppearanceSettings.lightForegroundHex : AppearanceSettings.darkForegroundHex
        }
        return resolvedForegroundHex
    }

    var sidebarPrimaryForegroundColor: Color? {
        resolvedSidebarForegroundHex.flatMap { Color(hex: $0) }
    }

    var sidebarColorSchemeOverride: ColorScheme? {
        guard let lum = activeSidebarBackgroundLuminance else { return nil }
        return lum < 0.179 ? .dark : .light
    }

    var isWindowTranslucent: Bool {
        #if targetEnvironment(macCatalyst)
        effectiveBackgroundOpacity < 0.999
        #else
        false
        #endif
    }

    var hasCustomBackdrop: Bool {
        activeBackgroundColor != nil || hasBackgroundImage || isWindowTranslucent
    }

    var hasCustomSidebarBackdrop: Bool {
        activeSidebarBackgroundColor != nil || activeBackgroundColor != nil
    }

    var translucentSurfaceOpacity: Double {
        if isWindowTranslucent {
            return max(0.14, effectiveBackgroundOpacity * 0.55)
        }
        if hasBackgroundImage {
            return 0.78
        }
        return 1.0
    }

    var activeAccentHex: String {
        switch settings.foregroundPreset {
        case .custom:
            return settings.customAccentHex ?? AppearanceSettings.defaultAccentHex
        default:
            return settings.foregroundPreset.accentHex ?? AppearanceSettings.defaultAccentHex
        }
    }

    var accentColor: Color {
        Color(hex: activeAccentHex) ?? Color(hex: AppearanceSettings.defaultAccentHex)!
    }

    var resolvedForegroundHex: String? {
        if let customForeground = settings.customForegroundHex,
           let normalized = ThemeColorHex.normalize(customForeground) {
            return normalized
        }
        guard let luminance = activeBackgroundLuminance else {
            return nil
        }
        return luminance < 0.179 ? AppearanceSettings.lightForegroundHex : AppearanceSettings.darkForegroundHex
    }

    var primaryForegroundColor: Color? {
        resolvedForegroundHex.flatMap { Color(hex: $0) }
    }

    var secondaryForegroundColor: Color? {
        primaryForegroundColor?.opacity(0.68)
    }

    var preferredColorScheme: ColorScheme? {
        if let luminance = activeBackgroundLuminance {
            return luminance < 0.179 ? .dark : .light
        }
        return settings.themeMode.colorScheme
    }

    func effectiveBackgroundHex(fallbackScheme: ColorScheme = .light) -> String {
        if let activeBackgroundHex {
            return activeBackgroundHex
        }
        let effectiveScheme = preferredColorScheme ?? fallbackScheme
        return effectiveScheme == .dark ? "#141416" : "#FFFFFF"
    }

    func effectiveForegroundHex(fallbackScheme: ColorScheme = .light) -> String {
        if let resolvedForegroundHex {
            return resolvedForegroundHex
        }
        let effectiveScheme = preferredColorScheme ?? fallbackScheme
        return effectiveScheme == .dark ? AppearanceSettings.lightForegroundHex : AppearanceSettings.darkForegroundHex
    }

    func foregroundContrastRatio(fallbackScheme: ColorScheme = .light) -> Double {
        ThemeColorHex.contrastRatio(
            hexA: effectiveForegroundHex(fallbackScheme: fallbackScheme),
            hexB: effectiveBackgroundHex(fallbackScheme: fallbackScheme)
        ) ?? 21.0
    }

    func accentContrastRatio(fallbackScheme: ColorScheme = .light) -> Double {
        ThemeColorHex.contrastRatio(
            hexA: activeAccentHex,
            hexB: effectiveBackgroundHex(fallbackScheme: fallbackScheme)
        ) ?? 21.0
    }

    func hasLowContrast(fallbackScheme: ColorScheme = .light) -> Bool {
        foregroundContrastRatio(fallbackScheme: fallbackScheme) < 4.5
            || accentContrastRatio(fallbackScheme: fallbackScheme) < 3.0
    }

    func autoFixContrast(fallbackScheme: ColorScheme = .light) {
        let bgHex = effectiveBackgroundHex(fallbackScheme: fallbackScheme)
        let bgLum = ThemeColorHex.relativeLuminance(hex: bgHex) ?? (fallbackScheme == .dark ? 0.01 : 1.0)
        var changed = false

        if foregroundContrastRatio(fallbackScheme: fallbackScheme) < 4.5 {
            if activeBackgroundHex != nil {
                let adaptive = bgLum < 0.179 ? AppearanceSettings.lightForegroundHex : AppearanceSettings.darkForegroundHex
                if (ThemeColorHex.contrastRatio(hexA: adaptive, hexB: bgHex) ?? 0) >= 4.5 {
                    settings.customForegroundHex = nil
                } else {
                    let whiteContrast = ThemeColorHex.contrastRatio(hexA: "#FFFFFF", hexB: bgHex) ?? 0
                    let blackContrast = ThemeColorHex.contrastRatio(hexA: "#000000", hexB: bgHex) ?? 0
                    settings.customForegroundHex = whiteContrast >= blackContrast ? "#FFFFFF" : "#000000"
                }
            } else {
                settings.customForegroundHex = nil
            }
            changed = true
        }

        if accentContrastRatio(fallbackScheme: fallbackScheme) < 3.0 {
            if let adjusted = ThemeColorHex.adjustForContrast(
                hex: activeAccentHex,
                againstBackgroundHex: bgHex,
                minimumRatio: 3.1
            ) {
                settings.customAccentHex = adjusted
                settings.foregroundPreset = .custom
                changed = true
            }
        }

        if changed {
            persist()
        }
    }

    // MARK: - Mutations

    func setThemeMode(_ mode: AppThemeMode) {
        guard settings.themeMode != mode else { return }
        settings.themeMode = mode
        persist()
    }

    func setBackgroundPreset(_ preset: BackgroundColorPreset) {
        guard settings.backgroundPreset != preset else { return }
        settings.backgroundPreset = preset
        persist()
    }

    func setCustomBackgroundHex(_ hex: String?) {
        let normalized = hex.flatMap(ThemeColorHex.normalize)
        settings.customBackgroundHex = normalized
        if normalized != nil {
            settings.backgroundPreset = .custom
        }
        persist()
    }

    func setForegroundPreset(_ preset: ForegroundColorPreset) {
        guard settings.foregroundPreset != preset else { return }
        settings.foregroundPreset = preset
        persist()
    }

    func setCustomForegroundHex(_ hex: String?) {
        let normalized = hex.flatMap(ThemeColorHex.normalize)
        guard settings.customForegroundHex != normalized else { return }
        settings.customForegroundHex = normalized
        persist()
    }

    func setCustomAccentHex(_ hex: String?) {
        let normalized = hex.flatMap(ThemeColorHex.normalize)
        settings.customAccentHex = normalized
        if normalized != nil {
            settings.foregroundPreset = .custom
        }
        persist()
    }

    func setBackgroundOpacity(_ opacity: Double) {
        let clamped = AppearanceSettings.clampBackgroundOpacity(opacity)
        guard settings.backgroundOpacity != clamped else { return }
        settings.backgroundOpacity = clamped
        persist()
    }

    func setSidebarBackgroundPreset(_ preset: BackgroundColorPreset) {
        guard settings.sidebarBackgroundPreset != preset else { return }
        settings.sidebarBackgroundPreset = preset
        persist()
    }

    func setCustomSidebarBackgroundHex(_ hex: String?) {
        let normalized = hex.flatMap(ThemeColorHex.normalize)
        settings.customSidebarBackgroundHex = normalized
        if normalized != nil {
            settings.sidebarBackgroundPreset = .custom
        }
        persist()
    }

    func setSidebarOpacity(_ opacity: Double?) {
        let clamped = opacity.map(AppearanceSettings.clampSidebarOpacity)
        guard settings.sidebarOpacity != clamped else { return }
        settings.sidebarOpacity = clamped
        persist()
    }

    func setBackgroundImageOpacity(_ opacity: Double) {
        let clamped = AppearanceSettings.clampBackgroundImageOpacity(opacity)
        guard settings.backgroundImageOpacity != clamped else { return }
        settings.backgroundImageOpacity = clamped
        persist()
    }

    func setBackgroundImageBlur(_ blur: Double) {
        let clamped = AppearanceSettings.clampBackgroundImageBlur(blur)
        guard settings.backgroundImageBlur != clamped else { return }
        settings.backgroundImageBlur = clamped
        persist()
    }

    func setLyricsFontSize(_ size: LyricsFontSize) {
        guard settings.lyricsFontSize != size else { return }
        settings.lyricsFontSize = size
        persist()
    }

    func setLyricsAlignment(_ alignment: LyricsAlignment) {
        guard settings.lyricsAlignment != alignment else { return }
        settings.lyricsAlignment = alignment
        persist()
    }

    func importBackgroundImage(from url: URL) throws {
        let accessed = url.startAccessingSecurityScopedResource()
        defer {
            if accessed { url.stopAccessingSecurityScopedResource() }
        }
        let data = try Data(contentsOf: url)
        let ext = url.pathExtension.isEmpty ? "jpg" : url.pathExtension
        try importBackgroundImage(data: data, fileExtension: ext)
    }

    func importBackgroundImage(data: Data, fileExtension: String) throws {
        guard !data.isEmpty else {
            throw ThemeAppearanceError.emptyImageData
        }
        let cleanedExt = sanitizedExtension(fileExtension)
        let fileName = "background.\(cleanedExt)"
        try FileManager.default.createDirectory(at: appearanceDirectory, withIntermediateDirectories: true)
        removeBackgroundImageFiles(except: fileName)
        let destination = appearanceDirectory.appendingPathComponent(fileName)
        try data.write(to: destination, options: .atomic)
        settings.backgroundImageFileName = fileName
        persist()
    }

    func clearBackgroundImage() {
        removeBackgroundImageFiles(except: nil)
        guard settings.backgroundImageFileName != nil else { return }
        settings.backgroundImageFileName = nil
        persist()
    }

    func resetToDefaults() {
        removeBackgroundImageFiles(except: nil)
        settings = AppearanceSettings()
        persist()
    }

    private func sanitizedExtension(_ raw: String) -> String {
        let trimmed = raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "."))
            .lowercased()
        let allowed = trimmed.filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
        return allowed.isEmpty ? "jpg" : allowed
    }

    private func removeBackgroundImageFiles(except retainedFileName: String?) {
        guard let entries = try? FileManager.default.contentsOfDirectory(at: appearanceDirectory, includingPropertiesForKeys: nil) else {
            return
        }
        for entry in entries {
            let name = entry.lastPathComponent
            guard name.hasPrefix("background."), name != retainedFileName else { continue }
            try? FileManager.default.removeItem(at: entry)
        }
    }

    private func persist() {
        try? Persistence.save(settings, to: settingsURL)
    }
}

extension EnvironmentValues {
    @Entry var themeManager = ThemeManager.shared
}
