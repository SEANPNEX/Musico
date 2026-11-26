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

struct AppearanceSettings: Codable, Equatable, Sendable {
    static let defaultAccentHex = "#E85440"
    static let lightForegroundHex = "#F5F5F7"
    static let darkForegroundHex = "#141416"
    static let defaultBackgroundImageOpacity: Double = 0.35
    static let defaultBackgroundOpacity: Double = 1.0
    static let backgroundImageOpacityRange: ClosedRange<Double> = 0.10...1.0
    static let backgroundOpacityRange: ClosedRange<Double> = 0.25...1.0

    var themeMode: AppThemeMode
    var backgroundPreset: BackgroundColorPreset
    var customBackgroundHex: String?
    var foregroundPreset: ForegroundColorPreset
    var customAccentHex: String?
    var customForegroundHex: String?
    var backgroundImageFileName: String?
    var backgroundImageOpacity: Double
    var backgroundOpacity: Double

    init(
        themeMode: AppThemeMode = .system,
        backgroundPreset: BackgroundColorPreset = .default,
        customBackgroundHex: String? = nil,
        foregroundPreset: ForegroundColorPreset = .coral,
        customAccentHex: String? = nil,
        customForegroundHex: String? = nil,
        backgroundImageFileName: String? = nil,
        backgroundImageOpacity: Double = AppearanceSettings.defaultBackgroundImageOpacity,
        backgroundOpacity: Double = AppearanceSettings.defaultBackgroundOpacity
    ) {
        self.themeMode = themeMode
        self.backgroundPreset = backgroundPreset
        self.customBackgroundHex = customBackgroundHex.flatMap(ThemeColorHex.normalize)
        self.foregroundPreset = foregroundPreset
        self.customAccentHex = customAccentHex.flatMap(ThemeColorHex.normalize)
        self.customForegroundHex = customForegroundHex.flatMap(ThemeColorHex.normalize)
        self.backgroundImageFileName = backgroundImageFileName
        self.backgroundImageOpacity = Self.clampBackgroundImageOpacity(backgroundImageOpacity)
        self.backgroundOpacity = Self.clampBackgroundOpacity(backgroundOpacity)
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let themeMode = try container.decodeIfPresent(AppThemeMode.self, forKey: .themeMode) ?? .system
        let backgroundPreset = try container.decodeIfPresent(BackgroundColorPreset.self, forKey: .backgroundPreset) ?? .default
        let customBackgroundHex = try container.decodeIfPresent(String.self, forKey: .customBackgroundHex)
        let foregroundPreset = try container.decodeIfPresent(ForegroundColorPreset.self, forKey: .foregroundPreset) ?? .coral
        let customAccentHex = try container.decodeIfPresent(String.self, forKey: .customAccentHex)
        let customForegroundHex = try container.decodeIfPresent(String.self, forKey: .customForegroundHex)
        let backgroundImageFileName = try container.decodeIfPresent(String.self, forKey: .backgroundImageFileName)
        let backgroundImageOpacity = try container.decodeIfPresent(Double.self, forKey: .backgroundImageOpacity) ?? Self.defaultBackgroundImageOpacity
        let backgroundOpacity = try container.decodeIfPresent(Double.self, forKey: .backgroundOpacity) ?? Self.defaultBackgroundOpacity

        self.init(
            themeMode: themeMode,
            backgroundPreset: backgroundPreset,
            customBackgroundHex: customBackgroundHex,
            foregroundPreset: foregroundPreset,
            customAccentHex: customAccentHex,
            customForegroundHex: customForegroundHex,
            backgroundImageFileName: backgroundImageFileName,
            backgroundImageOpacity: backgroundImageOpacity,
            backgroundOpacity: backgroundOpacity
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

    static func relativeLuminance(r: Double, g: Double, b: Double) -> Double {
        0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    static func relativeLuminance(hex: String) -> Double? {
        guard let rgb = parseRGB(hex) else { return nil }
        return relativeLuminance(r: rgb.r, g: rgb.g, b: rgb.b)
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
        return luminance < 0.5 ? AppearanceSettings.lightForegroundHex : AppearanceSettings.darkForegroundHex
    }

    var primaryForegroundColor: Color? {
        resolvedForegroundHex.flatMap { Color(hex: $0) }
    }

    var secondaryForegroundColor: Color? {
        primaryForegroundColor?.opacity(0.68)
    }

    var preferredColorScheme: ColorScheme? {
        if let luminance = activeBackgroundLuminance {
            return luminance < 0.5 ? .dark : .light
        }
        return settings.themeMode.colorScheme
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

    func setBackgroundImageOpacity(_ opacity: Double) {
        let clamped = AppearanceSettings.clampBackgroundImageOpacity(opacity)
        guard settings.backgroundImageOpacity != clamped else { return }
        settings.backgroundImageOpacity = clamped
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
