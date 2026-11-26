package com.musico.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThemeStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun defaultsAndAppearanceJsonRoundTrip() {
        val stateDir = tempFolder.newFolder("musico")
        val store = ThemeStore(context = null, stateDir = stateDir)

        val initial = store.settings.value
        assertEquals(AppThemeMode.SYSTEM, initial.themeMode)
        assertEquals(BackgroundColorPreset.DEFAULT, initial.backgroundPreset)
        assertNull(initial.customBackgroundHex)
        assertEquals(ForegroundColorPreset.CORAL, initial.foregroundPreset)
        assertNull(initial.customAccentHex)
        assertNull(initial.customForegroundHex)
        assertNull(initial.backgroundImageFileName)
        assertEquals(0.35f, initial.backgroundImageOpacity, 0.0001f)

        store.setThemeMode(AppThemeMode.DARK)
        store.setBackgroundPreset(BackgroundColorPreset.MIDNIGHT)
        store.setForegroundPreset(ForegroundColorPreset.OCEAN)
        store.setBackgroundImageOpacity(0.72f)

        val appearanceFile = File(stateDir, "appearance.json")
        assertTrue(appearanceFile.exists())

        val reloaded = ThemeStore(context = null, stateDir = stateDir).settings.value
        assertEquals(AppThemeMode.DARK, reloaded.themeMode)
        assertEquals(BackgroundColorPreset.MIDNIGHT, reloaded.backgroundPreset)
        assertEquals(ForegroundColorPreset.OCEAN, reloaded.foregroundPreset)
        assertEquals(0.72f, reloaded.backgroundImageOpacity, 0.0001f)

        store.setCustomBackgroundHex("1a2b3c")
        store.setCustomAccentHex(" #ff00aa ")
        store.setCustomForegroundHex("#102030")

        val customReloaded = ThemeStore(context = null, stateDir = stateDir).settings.value
        assertEquals(BackgroundColorPreset.CUSTOM, customReloaded.backgroundPreset)
        assertEquals("#1A2B3C", customReloaded.customBackgroundHex)
        assertEquals(ForegroundColorPreset.CUSTOM, customReloaded.foregroundPreset)
        assertEquals("#FF00AA", customReloaded.customAccentHex)
        assertEquals("#102030", customReloaded.customForegroundHex)
    }

    @Test
    fun hexParsingAndFormattingNormalizeAndRejectInvalidInputs() {
        assertEquals(RgbColor(232, 84, 64), parseHexRgb("#E85440"))
        assertEquals(RgbColor(232, 84, 64), parseHexRgb(" e85440 "))
        assertEquals("#09090B", normalizeHexOrNull("09090b"))
        assertEquals("#FFFFFF", formatHexRgb(300, 255, 999))
        assertEquals("#000000", formatHexRgb(-10, 0, -1))

        assertNull(parseHexRgb(null))
        assertNull(parseHexRgb(""))
        assertNull(parseHexRgb("#123"))
        assertNull(parseHexRgb("#GGGGGG"))
        assertNull(parseHexRgb("+12345"))
        assertNull(parseHexRgb("#12345678"))
    }

    @Test
    fun luminanceAndAdaptiveContrastChooseHighContrastForegrounds() {
        val obsidian = parseHexRgb(BackgroundColorPreset.OBSIDIAN.hex)!!
        val paper = parseHexRgb(BackgroundColorPreset.PAPER.hex)!!
        val mist = parseHexRgb(BackgroundColorPreset.MIST.hex)!!
        val blossom = parseHexRgb(BackgroundColorPreset.BLOSSOM.hex)!!
        val forest = parseHexRgb(BackgroundColorPreset.FOREST_NIGHT.hex)!!

        assertFalse(isLightColor(obsidian))
        assertFalse(isLightColor(forest))
        assertTrue(isLightColor(paper))
        assertTrue(isLightColor(mist))
        assertTrue(isLightColor(blossom))

        assertEquals(LIGHT_FOREGROUND_HEX, adaptiveForegroundHex(obsidian))
        assertEquals(DARK_FOREGROUND_HEX, adaptiveForegroundHex(paper))

        val autoSettings = AppearanceSettings(
            backgroundPreset = BackgroundColorPreset.PAPER,
            customForegroundHex = null,
        )
        assertEquals(DARK_FOREGROUND_HEX, autoSettings.resolvedForegroundHex())
        assertFalse(autoSettings.resolveDarkTheme(systemDark = true))

        val overrideSettings = autoSettings.copy(customForegroundHex = "#abcdef")
        assertEquals("#ABCDEF", overrideSettings.resolvedForegroundHex())

        val blackLum = relativeLuminance(RgbColor(0, 0, 0))
        val whiteLum = relativeLuminance(RgbColor(255, 255, 255))
        assertEquals(0f, blackLum, 0.0001f)
        assertEquals(1f, whiteLum, 0.0001f)
    }

    @Test
    fun backgroundImageOpacityIsClamped() {
        val stateDir = tempFolder.newFolder("opacity")
        val store = ThemeStore(context = null, stateDir = stateDir)

        store.setBackgroundImageOpacity(0.01f)
        assertEquals(0.10f, store.settings.value.backgroundImageOpacity, 0.0001f)

        store.setBackgroundImageOpacity(2.5f)
        assertEquals(1.0f, store.settings.value.backgroundImageOpacity, 0.0001f)

        store.setBackgroundImageOpacity(Float.NaN)
        assertEquals(0.35f, store.settings.value.backgroundImageOpacity, 0.0001f)

        store.setBackgroundImageOpacity(0.6f)
        assertEquals(0.6f, store.settings.value.backgroundImageOpacity, 0.0001f)
    }

    @Test
    fun backgroundImageImportSupersedesClearAndReset() {
        val stateDir = tempFolder.newFolder("images")
        val store = ThemeStore(context = null, stateDir = stateDir)

        val pngResult = store.importBackgroundImage(byteArrayOf(1, 2, 3, 4), "PNG")
        assertTrue(pngResult.isSuccess)
        val pngFile = pngResult.getOrThrow()
        assertEquals("background.png", pngFile.name)
        assertEquals(File(stateDir, "Appearance/background.png"), pngFile)
        assertTrue(pngFile.exists())
        assertEquals("background.png", store.settings.value.backgroundImageFileName)
        assertEquals(pngFile, store.resolveBackgroundImageFile())

        val jpgResult = store.importBackgroundImage(byteArrayOf(5, 6, 7, 8), ".jpeg")
        assertTrue(jpgResult.isSuccess)
        val jpgFile = jpgResult.getOrThrow()
        assertEquals("background.jpeg", jpgFile.name)
        assertTrue(jpgFile.exists())
        assertFalse(pngFile.exists())
        assertEquals("background.jpeg", store.settings.value.backgroundImageFileName)
        assertEquals(jpgFile, store.resolveBackgroundImageFile())

        store.clearBackgroundImage()
        assertNull(store.settings.value.backgroundImageFileName)
        assertNull(store.resolveBackgroundImageFile())
        assertFalse(jpgFile.exists())

        store.importBackgroundImage(byteArrayOf(9, 10), "webp")
        store.setThemeMode(AppThemeMode.LIGHT)
        store.setBackgroundPreset(BackgroundColorPreset.BLOSSOM)
        store.setForegroundPreset(ForegroundColorPreset.AMETHYST)
        store.setBackgroundImageOpacity(0.85f)

        store.resetToDefaults()
        val reset = store.settings.value
        assertEquals(AppearanceSettings(), reset)
        assertNull(store.resolveBackgroundImageFile())
        val appearanceDir = File(stateDir, "Appearance")
        assertTrue(appearanceDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun buildCustomColorSchemePreservesDefaultsAndAppliesCustomizations() {
        val baseDark = darkColorScheme()
        val baseLight = lightColorScheme()

        assertEquals(baseDark, buildCustomColorScheme(AppearanceSettings(), resolvedDark = true, baseScheme = baseDark))
        assertEquals(baseLight, buildCustomColorScheme(AppearanceSettings(), resolvedDark = false, baseScheme = baseLight))

        val darkCustom = buildCustomColorScheme(
            appearance = AppearanceSettings(
                backgroundPreset = BackgroundColorPreset.OBSIDIAN,
                foregroundPreset = ForegroundColorPreset.OCEAN,
            ),
            resolvedDark = true,
            baseScheme = baseDark,
        )
        assertEquals(Color(0xFF09090B), darkCustom.background)
        assertEquals(Color(0xFF09090B), darkCustom.surface)
        assertEquals(Color(0xFF2563EB), darkCustom.primary)
        assertEquals(Color(0xFFF5F5F7), darkCustom.onBackground)
        assertEquals(Color(0xFFF5F5F7), darkCustom.onSurface)

        val lightCustom = buildCustomColorScheme(
            appearance = AppearanceSettings(
                backgroundPreset = BackgroundColorPreset.PAPER,
                foregroundPreset = ForegroundColorPreset.AMBER,
            ),
            resolvedDark = false,
            baseScheme = baseLight,
        )
        assertEquals(Color(0xFFF8F6F1), lightCustom.background)
        assertEquals(Color(0xFFF59E0B), lightCustom.primary)
        assertEquals(Color(0xFF141416), lightCustom.onPrimary)
        assertEquals(Color(0xFF141416), lightCustom.onBackground)
    }

    @Test
    fun invalidAppearanceJsonFallsBackToDefaultsSafely() {
        val stateDir = tempFolder.newFolder("corrupt")
        val file = File(stateDir, "appearance.json")
        file.writeText("{not valid json")

        val store = ThemeStore(context = null, stateDir = stateDir)
        assertEquals(AppearanceSettings(), store.settings.value)
        assertNotNull(store.settings.value)
    }

    @Test
    fun wcagContrastRatioAndAutoFixContrastWork() {
        val black = RgbColor(0, 0, 0)
        val white = RgbColor(255, 255, 255)
        assertEquals(21.0f, contrastRatio(black, white), 0.01f)
        assertEquals(1.0f, contrastRatio(white, white), 0.001f)

        val stateDir = tempFolder.newFolder("contrast")
        val store = ThemeStore(context = null, stateDir = stateDir)
        store.setBackgroundPreset(BackgroundColorPreset.PAPER)
        store.setCustomForegroundHex("#E5E5E5")
        store.setCustomAccentHex("#F2EFE9")

        val broken = store.settings.value
        val bg = broken.effectiveBackgroundRgb(systemDark = false)
        assertTrue(contrastRatio(bg, broken.effectiveForegroundRgb(false)) < AppearanceSettings.MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(bg, broken.effectiveAccentRgb(false)) < AppearanceSettings.MIN_ACCENT_CONTRAST)

        store.autoFixContrast(systemDark = false)
        val fixed = store.settings.value
        val fixedBg = fixed.effectiveBackgroundRgb(systemDark = false)
        assertTrue(contrastRatio(fixedBg, fixed.effectiveForegroundRgb(false)) >= AppearanceSettings.MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(fixedBg, fixed.effectiveAccentRgb(false)) >= AppearanceSettings.MIN_ACCENT_CONTRAST)

        val reloaded = ThemeStore(context = null, stateDir = stateDir).settings.value
        assertEquals(fixed, reloaded)
    }

    @Test
    fun backgroundBlurAndLyricsPreferencesClampPersistAndReset() {
        val stateDir = tempFolder.newFolder("blur_lyrics")
        val store = ThemeStore(context = null, stateDir = stateDir)

        assertEquals(0f, store.settings.value.backgroundImageBlurDp, 0.0001f)
        assertEquals(LyricsFontSize.MEDIUM, store.settings.value.lyricsFontSize)
        assertEquals(LyricsAlignment.START, store.settings.value.lyricsAlignment)

        store.setBackgroundImageBlurDp(-4f)
        assertEquals(0f, store.settings.value.backgroundImageBlurDp, 0.0001f)

        store.setBackgroundImageBlurDp(42f)
        assertEquals(24f, store.settings.value.backgroundImageBlurDp, 0.0001f)

        store.setBackgroundImageBlurDp(Float.NaN)
        assertEquals(0f, store.settings.value.backgroundImageBlurDp, 0.0001f)

        store.setBackgroundImageBlurDp(12.5f)
        store.setLyricsFontSize(LyricsFontSize.LARGE)
        store.setLyricsAlignment(LyricsAlignment.CENTER)

        val reloaded = ThemeStore(context = null, stateDir = stateDir).settings.value
        assertEquals(12.5f, reloaded.backgroundImageBlurDp, 0.0001f)
        assertEquals(LyricsFontSize.LARGE, reloaded.lyricsFontSize)
        assertEquals(LyricsAlignment.CENTER, reloaded.lyricsAlignment)

        store.resetToDefaults()
        assertEquals(0f, store.settings.value.backgroundImageBlurDp, 0.0001f)
        assertEquals(LyricsFontSize.MEDIUM, store.settings.value.lyricsFontSize)
        assertEquals(LyricsAlignment.START, store.settings.value.lyricsAlignment)
    }
}
