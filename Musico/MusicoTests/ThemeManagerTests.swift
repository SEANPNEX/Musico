import SwiftUI
import UIKit
import XCTest
@testable import Musico

@MainActor
final class ThemeManagerTests: XCTestCase {
    private func makeTempDir() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ThemeManagerTests-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        addTeardownBlock {
            try? FileManager.default.removeItem(at: url)
        }
        return url
    }

    func testDefaultsAndPersistenceRoundTrip() {
        let dir = makeTempDir()
        let manager = ThemeManager(directory: dir)
        XCTAssertEqual(manager.settings, AppearanceSettings())
        XCTAssertNil(manager.activeBackgroundHex)
        XCTAssertNil(manager.resolvedForegroundHex)
        XCTAssertNil(manager.preferredColorScheme)
        XCTAssertEqual(manager.activeAccentHex, "#E85440")
        XCTAssertEqual(manager.effectiveBackgroundOpacity, 1.0)
        XCTAssertFalse(manager.hasCustomBackdrop)

        manager.setThemeMode(.dark)
        manager.setBackgroundPreset(.midnight)
        manager.setForegroundPreset(.ocean)
        manager.setCustomForegroundHex("#FAFAFC")
        manager.setBackgroundImageOpacity(0.55)
        manager.setBackgroundOpacity(0.75)

        let reloaded = ThemeManager(directory: dir)
        XCTAssertEqual(reloaded.settings.themeMode, .dark)
        XCTAssertEqual(reloaded.settings.backgroundPreset, .midnight)
        XCTAssertEqual(reloaded.settings.foregroundPreset, .ocean)
        XCTAssertEqual(reloaded.settings.customForegroundHex, "#FAFAFC")
        XCTAssertEqual(reloaded.settings.backgroundImageOpacity, 0.55, accuracy: 0.0001)
        XCTAssertEqual(reloaded.settings.backgroundOpacity, 0.75, accuracy: 0.0001)
        XCTAssertEqual(reloaded.activeAccentHex, "#2563EB")
        XCTAssertEqual(reloaded.resolvedForegroundHex, "#FAFAFC")
        XCTAssertTrue(reloaded.hasCustomBackdrop)

        #if targetEnvironment(macCatalyst)
        XCTAssertEqual(reloaded.effectiveBackgroundOpacity, 0.75, accuracy: 0.0001)
        XCTAssertTrue(reloaded.isWindowTranslucent)
        #else
        XCTAssertEqual(reloaded.effectiveBackgroundOpacity, 1.0, accuracy: 0.0001)
        XCTAssertFalse(reloaded.isWindowTranslucent)
        #endif
    }

    func testOpacityClampingAndNonFiniteHandling() {
        let dir = makeTempDir()
        let manager = ThemeManager(directory: dir)

        manager.setBackgroundImageOpacity(0.01)
        XCTAssertEqual(manager.settings.backgroundImageOpacity, 0.10, accuracy: 0.0001)
        manager.setBackgroundImageOpacity(2.5)
        XCTAssertEqual(manager.settings.backgroundImageOpacity, 1.0, accuracy: 0.0001)
        manager.setBackgroundImageOpacity(.nan)
        XCTAssertEqual(manager.settings.backgroundImageOpacity, AppearanceSettings.defaultBackgroundImageOpacity, accuracy: 0.0001)

        manager.setBackgroundOpacity(0.05)
        XCTAssertEqual(manager.settings.backgroundOpacity, 0.15, accuracy: 0.0001)
        manager.setBackgroundOpacity(3.0)
        XCTAssertEqual(manager.settings.backgroundOpacity, 1.0, accuracy: 0.0001)
        manager.setBackgroundOpacity(.infinity)
        XCTAssertEqual(manager.settings.backgroundOpacity, AppearanceSettings.defaultBackgroundOpacity, accuracy: 0.0001)

        manager.setSidebarOpacity(0.05)
        XCTAssertEqual(manager.settings.sidebarOpacity ?? -1, 0.15, accuracy: 0.0001)
        manager.setSidebarOpacity(2.0)
        XCTAssertEqual(manager.settings.sidebarOpacity ?? -1, 1.0, accuracy: 0.0001)
        manager.setSidebarOpacity(.nan)
        XCTAssertEqual(manager.settings.sidebarOpacity ?? -1, AppearanceSettings.defaultBackgroundOpacity, accuracy: 0.0001)
        manager.setSidebarOpacity(nil)
        XCTAssertNil(manager.settings.sidebarOpacity)
    }

    func testHexParsingAndFormatting() {
        XCTAssertEqual(ThemeColorHex.normalize("e85440"), "#E85440")
        XCTAssertEqual(ThemeColorHex.normalize(" #0f172a "), "#0F172A")
        XCTAssertEqual(ThemeColorHex.normalize("#abc"), "#AABBCC")
        XCTAssertNil(ThemeColorHex.normalize("not-a-hex"))
        XCTAssertNil(ThemeColorHex.normalize("#12345"))
        XCTAssertNil(ThemeColorHex.normalize("#GGGGGG"))

        let uiColor = UIColor(hex: "#2563EB")
        XCTAssertNotNil(uiColor)
        XCTAssertEqual(uiColor?.hexString, "#2563EB")

        let swiftUIColor = Color(hex: "#10B981")
        XCTAssertNotNil(swiftUIColor)
        XCTAssertEqual(swiftUIColor?.toHexString(), "#10B981")

        XCTAssertNil(Color(hex: "invalid"))
        XCTAssertNil(UIColor(hex: "invalid"))
    }

    func testAdaptiveContrastLuminance() {
        let dir = makeTempDir()
        let manager = ThemeManager(directory: dir)

        let darkPresets: [BackgroundColorPreset] = [.obsidian, .midnight, .warmCharcoal, .forestNight]
        for preset in darkPresets {
            manager.setBackgroundPreset(preset)
            XCTAssertEqual(manager.resolvedForegroundHex, "#F5F5F7", "Expected light text on \(preset)")
            XCTAssertEqual(manager.preferredColorScheme, .dark, "Expected dark scheme on \(preset)")
            XCTAssertNotNil(manager.primaryForegroundColor)
            XCTAssertNotNil(manager.secondaryForegroundColor)
        }

        let lightPresets: [BackgroundColorPreset] = [.paper, .mist, .blossom]
        for preset in lightPresets {
            manager.setBackgroundPreset(preset)
            XCTAssertEqual(manager.resolvedForegroundHex, "#141416", "Expected dark text on \(preset)")
            XCTAssertEqual(manager.preferredColorScheme, .light, "Expected light scheme on \(preset)")
        }

        // Custom dark background -> light text
        manager.setCustomBackgroundHex("#111827")
        XCTAssertEqual(manager.settings.backgroundPreset, .custom)
        XCTAssertEqual(manager.activeBackgroundHex, "#111827")
        XCTAssertEqual(manager.resolvedForegroundHex, "#F5F5F7")
        XCTAssertEqual(manager.preferredColorScheme, .dark)

        // Custom light background -> dark text
        manager.setCustomBackgroundHex("#F3F4F6")
        XCTAssertEqual(manager.resolvedForegroundHex, "#141416")
        XCTAssertEqual(manager.preferredColorScheme, .light)

        // Custom foreground overrides adaptive foreground
        manager.setCustomForegroundHex("#224466")
        XCTAssertEqual(manager.resolvedForegroundHex, "#224466")

        // Clearing custom foreground returns to adaptive contrast
        manager.setCustomForegroundHex(nil)
        XCTAssertEqual(manager.resolvedForegroundHex, "#141416")

        // Custom accent hex
        manager.setCustomAccentHex("#336699")
        XCTAssertEqual(manager.settings.foregroundPreset, .custom)
        XCTAssertEqual(manager.activeAccentHex, "#336699")

        // Luminance formula check: 0.2126*R + 0.7152*G + 0.0722*B
        let greenLum = ThemeColorHex.relativeLuminance(hex: "#00FF00") ?? -1
        XCTAssertEqual(greenLum, 0.7152, accuracy: 0.0001)
    }

    func testBackgroundImageImportSupersedesPreviousClearAndReset() throws {
        let dir = makeTempDir()
        let manager = ThemeManager(directory: dir)

        try manager.importBackgroundImage(data: Data([0x89, 0x50, 0x4E, 0x47]), fileExtension: "png")
        XCTAssertEqual(manager.settings.backgroundImageFileName, "background.png")
        let firstURL = try XCTUnwrap(manager.backgroundImageURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: firstURL.path))

        // Importing a new extension should delete the old background.png file
        let sourceURL = dir.appendingPathComponent("wallpaper.jpg")
        try Data([0xFF, 0xD8, 0xFF, 0xE0]).write(to: sourceURL)
        try manager.importBackgroundImage(from: sourceURL)
        XCTAssertEqual(manager.settings.backgroundImageFileName, "background.jpg")
        let secondURL = try XCTUnwrap(manager.backgroundImageURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: secondURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: firstURL.path))

        manager.clearBackgroundImage()
        XCTAssertNil(manager.settings.backgroundImageFileName)
        XCTAssertNil(manager.backgroundImageURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: secondURL.path))

        // Re-import and verify resetToDefaults removes image & restores all defaults
        try manager.importBackgroundImage(data: Data([0x01, 0x02, 0x03]), fileExtension: "webp")
        manager.setThemeMode(.light)
        manager.setBackgroundPreset(.obsidian)
        manager.setForegroundPreset(.emerald)
        manager.setBackgroundImageOpacity(0.8)
        manager.setBackgroundOpacity(0.5)
        let thirdURL = try XCTUnwrap(manager.backgroundImageURL)
        XCTAssertTrue(FileManager.default.fileExists(atPath: thirdURL.path))

        manager.resetToDefaults()
        XCTAssertEqual(manager.settings, AppearanceSettings())
        XCTAssertNil(manager.backgroundImageURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: thirdURL.path))

        let reloaded = ThemeManager(directory: dir)
        XCTAssertEqual(reloaded.settings, AppearanceSettings())
    }

    func testBlurLyricsTypographyAndBackwardCompatibleDecoding() throws {
        let dir = makeTempDir()
        // Legacy JSON missing backgroundImageBlur, lyricsFontSize, lyricsAlignment
        let legacyJSON = """
        {
          "themeMode": "dark",
          "backgroundPreset": "obsidian",
          "foregroundPreset": "emerald",
          "backgroundImageOpacity": 0.6,
          "backgroundOpacity": 0.8
        }
        """
        try Data(legacyJSON.utf8).write(to: dir.appendingPathComponent("appearance.json"))

        let manager = ThemeManager(directory: dir)
        XCTAssertEqual(manager.settings.themeMode, .dark)
        XCTAssertEqual(manager.settings.backgroundPreset, .obsidian)
        XCTAssertEqual(manager.settings.foregroundPreset, .emerald)
        XCTAssertEqual(manager.settings.sidebarBackgroundPreset, .default)
        XCTAssertNil(manager.settings.customSidebarBackgroundHex)
        XCTAssertNil(manager.settings.sidebarOpacity)
        XCTAssertEqual(manager.settings.backgroundImageBlur, 0.0, accuracy: 0.0001)
        XCTAssertEqual(manager.settings.lyricsFontSize, .medium)
        XCTAssertEqual(manager.settings.lyricsAlignment, .leading)

        // Clamping and non-finite blur
        manager.setBackgroundImageBlur(-5)
        XCTAssertEqual(manager.settings.backgroundImageBlur, 0.0, accuracy: 0.0001)
        manager.setBackgroundImageBlur(99)
        XCTAssertEqual(manager.settings.backgroundImageBlur, 24.0, accuracy: 0.0001)
        manager.setBackgroundImageBlur(.nan)
        XCTAssertEqual(manager.settings.backgroundImageBlur, 0.0, accuracy: 0.0001)
        manager.setBackgroundImageBlur(12)

        manager.setLyricsFontSize(.large)
        manager.setLyricsAlignment(.center)

        let reloaded = ThemeManager(directory: dir)
        XCTAssertEqual(reloaded.settings.backgroundImageBlur, 12.0, accuracy: 0.0001)
        XCTAssertEqual(reloaded.settings.lyricsFontSize, .large)
        XCTAssertEqual(reloaded.settings.lyricsAlignment, .center)
    }

    func testWCAGContrastRatioAndAutoFixContrast() {
        let maxRatio = ThemeColorHex.contrastRatio(hexA: "#FFFFFF", hexB: "#000000") ?? 0
        XCTAssertEqual(maxRatio, 21.0, accuracy: 0.01)
        let sameRatio = ThemeColorHex.contrastRatio(hexA: "#336699", hexB: "#336699") ?? 0
        XCTAssertEqual(sameRatio, 1.0, accuracy: 0.001)

        // Linearized sRGB check for mid-gray #808080 (~0.21586)
        let grayLum = ThemeColorHex.relativeLuminance(hex: "#808080") ?? -1
        XCTAssertEqual(grayLum, 0.21586, accuracy: 0.001)

        let dir = makeTempDir()
        let manager = ThemeManager(directory: dir)
        manager.setCustomBackgroundHex("#111827")
        manager.setCustomForegroundHex("#1F2937")
        manager.setCustomAccentHex("#1E293B")
        XCTAssertTrue(manager.hasLowContrast(fallbackScheme: .dark))
        XCTAssertLessThan(manager.foregroundContrastRatio(fallbackScheme: .dark), 4.5)
        XCTAssertLessThan(manager.accentContrastRatio(fallbackScheme: .dark), 3.0)

        manager.autoFixContrast(fallbackScheme: .dark)
        XCTAssertFalse(manager.hasLowContrast(fallbackScheme: .dark))
        XCTAssertGreaterThanOrEqual(manager.foregroundContrastRatio(fallbackScheme: .dark), 4.5)
        XCTAssertGreaterThanOrEqual(manager.accentContrastRatio(fallbackScheme: .dark), 3.0)

        // Also test light background low contrast auto-fix
        manager.setCustomBackgroundHex("#F8FAFC")
        manager.setCustomForegroundHex("#E2E8F0")
        manager.setCustomAccentHex("#F1F5F9")
        XCTAssertTrue(manager.hasLowContrast(fallbackScheme: .light))
        manager.autoFixContrast(fallbackScheme: .light)
        XCTAssertFalse(manager.hasLowContrast(fallbackScheme: .light))
        XCTAssertGreaterThanOrEqual(manager.foregroundContrastRatio(fallbackScheme: .light), 4.5)
        XCTAssertGreaterThanOrEqual(manager.accentContrastRatio(fallbackScheme: .light), 3.0)
    }

    func testSidebarThemingOpacityAndPersistence() {
        let dir = makeTempDir()
        let manager = ThemeManager(directory: dir)

        // Default follows main; sidebar itself stays opaque (1.0) on Apple platforms
        XCTAssertEqual(manager.settings.sidebarBackgroundPreset, .default)
        XCTAssertNil(manager.activeSidebarBackgroundHex)
        XCTAssertNil(manager.sidebarColorSchemeOverride)
        XCTAssertFalse(manager.hasCustomSidebarBackdrop)
        XCTAssertEqual(manager.effectiveSidebarOpacity, 1.0, accuracy: 0.0001)

        // Adjusting main backgroundOpacity does not make the sidebar translucent
        manager.setBackgroundOpacity(0.5)
        XCTAssertEqual(manager.effectiveSidebarOpacity, 1.0, accuracy: 0.0001)
        XCTAssertFalse(manager.hasCustomSidebarBackdrop)

        // Legacy sidebarOpacity field still clamps/persists without making window or sidebar translucent
        manager.setBackgroundOpacity(1.0)
        manager.setSidebarOpacity(0.35)
        XCTAssertEqual(manager.settings.sidebarOpacity ?? -1, 0.35, accuracy: 0.0001)
        XCTAssertEqual(manager.effectiveSidebarOpacity, 1.0, accuracy: 0.0001)
        XCTAssertFalse(manager.isWindowTranslucent)

        // Preset sidebar colors drive sidebarColorSchemeOverride and sidebarPrimaryForegroundColor
        manager.setSidebarBackgroundPreset(.midnight)
        XCTAssertEqual(manager.activeSidebarBackgroundHex, "#0F172A")
        XCTAssertEqual(manager.sidebarColorSchemeOverride, .dark)
        XCTAssertEqual(manager.resolvedSidebarForegroundHex, AppearanceSettings.lightForegroundHex)
        XCTAssertNotNil(manager.sidebarPrimaryForegroundColor)
        XCTAssertTrue(manager.hasCustomSidebarBackdrop)

        manager.setSidebarBackgroundPreset(.paper)
        XCTAssertEqual(manager.activeSidebarBackgroundHex, "#F8F6F1")
        XCTAssertEqual(manager.sidebarColorSchemeOverride, .light)
        XCTAssertEqual(manager.resolvedSidebarForegroundHex, AppearanceSettings.darkForegroundHex)

        // Custom sidebar hex
        manager.setCustomSidebarBackgroundHex("#102A43")
        XCTAssertEqual(manager.settings.sidebarBackgroundPreset, .custom)
        XCTAssertEqual(manager.activeSidebarBackgroundHex, "#102A43")
        XCTAssertEqual(manager.sidebarColorSchemeOverride, .dark)

        // Persistence round-trip
        let reloaded = ThemeManager(directory: dir)
        XCTAssertEqual(reloaded.settings.sidebarBackgroundPreset, .custom)
        XCTAssertEqual(reloaded.settings.customSidebarBackgroundHex, "#102A43")
        XCTAssertEqual(reloaded.settings.sidebarOpacity ?? -1, 0.35, accuracy: 0.0001)
        XCTAssertEqual(reloaded.effectiveSidebarOpacity, 1.0, accuracy: 0.0001)
        XCTAssertEqual(reloaded.sidebarColorSchemeOverride, .dark)
    }
}
