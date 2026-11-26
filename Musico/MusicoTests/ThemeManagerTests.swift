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
        XCTAssertEqual(manager.settings.backgroundOpacity, 0.25, accuracy: 0.0001)
        manager.setBackgroundOpacity(3.0)
        XCTAssertEqual(manager.settings.backgroundOpacity, 1.0, accuracy: 0.0001)
        manager.setBackgroundOpacity(.infinity)
        XCTAssertEqual(manager.settings.backgroundOpacity, AppearanceSettings.defaultBackgroundOpacity, accuracy: 0.0001)
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
}
