package com.musico.ui.theme

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

@Serializable
enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
enum class LyricsFontSize(val scaleFactor: Float) {
    SMALL(0.85f),
    MEDIUM(1.0f),
    LARGE(1.22f),
}

@Serializable
enum class LyricsAlignment {
    START,
    CENTER,
}

@Serializable
enum class BackgroundColorPreset(val hex: String?) {
    DEFAULT(null),
    OBSIDIAN("#09090B"),
    MIDNIGHT("#0F172A"),
    WARM_CHARCOAL("#1C1917"),
    FOREST_NIGHT("#0F1D17"),
    PAPER("#F8F6F1"),
    MIST("#EEF3F8"),
    BLOSSOM("#FCF2F4"),
    CUSTOM(null),
    ;

    fun resolvedHex(customBackgroundHex: String?): String? = when (this) {
        DEFAULT -> null
        CUSTOM -> normalizeHexOrNull(customBackgroundHex)
        else -> hex
    }
}

@Serializable
enum class ForegroundColorPreset(val hex: String?) {
    CORAL("#E85440"),
    OCEAN("#2563EB"),
    EMERALD("#10B981"),
    AMETHYST("#8B5CF6"),
    AMBER("#F59E0B"),
    ROSE("#F43F5E"),
    MONOCHROME("#71717A"),
    CUSTOM(null),
    ;

    fun resolvedAccentHex(customAccentHex: String?): String = when (this) {
        CUSTOM -> normalizeHexOrNull(customAccentHex) ?: CORAL.hex!!
        else -> hex!!
    }
}

data class RgbColor(
    val r: Int,
    val g: Int,
    val b: Int,
) {
    fun toHex(): String = formatHexRgb(r, g, b)

    fun toComposeColor(): Color = Color(red = r.coerceIn(0, 255), green = g.coerceIn(0, 255), blue = b.coerceIn(0, 255))
}

const val LIGHT_FOREGROUND_HEX = "#F5F5F7"
const val DARK_FOREGROUND_HEX = "#141416"

fun formatHexRgb(r: Int, g: Int, b: Int): String = String.format(
    Locale.US,
    "#%02X%02X%02X",
    r.coerceIn(0, 255),
    g.coerceIn(0, 255),
    b.coerceIn(0, 255),
)

fun parseHexRgb(raw: String?): RgbColor? {
    val trimmed = raw?.trim() ?: return null
    val hex = if (trimmed.startsWith("#")) trimmed.substring(1) else trimmed
    if (hex.length != 6) return null
    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    val value = hex.toIntOrNull(16) ?: return null
    return RgbColor(
        r = (value shr 16) and 0xFF,
        g = (value shr 8) and 0xFF,
        b = value and 0xFF,
    )
}

fun normalizeHexOrNull(raw: String?): String? = parseHexRgb(raw)?.toHex()

fun String.toComposeColorOrNull(): Color? = parseHexRgb(this)?.toComposeColor()

fun Color.toRgbColor(): RgbColor = RgbColor(
    r = (red * 255f).roundToInt().coerceIn(0, 255),
    g = (green * 255f).roundToInt().coerceIn(0, 255),
    b = (blue * 255f).roundToInt().coerceIn(0, 255),
)

fun relativeLuminance(rgb: RgbColor): Float {
    val r = rgb.r.coerceIn(0, 255) / 255f
    val g = rgb.g.coerceIn(0, 255) / 255f
    val b = rgb.b.coerceIn(0, 255) / 255f
    return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
}

fun srgbRelativeLuminance(rgb: RgbColor): Float {
    fun linearize(channel: Int): Double {
        val c = channel.coerceIn(0, 255) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    val lum = 0.2126 * linearize(rgb.r) + 0.7152 * linearize(rgb.g) + 0.0722 * linearize(rgb.b)
    return lum.toFloat().coerceIn(0f, 1f)
}

fun contrastRatio(a: RgbColor, b: RgbColor): Float {
    val lumA = srgbRelativeLuminance(a)
    val lumB = srgbRelativeLuminance(b)
    val lighter = maxOf(lumA, lumB)
    val darker = minOf(lumA, lumB)
    return ((lighter + 0.05f) / (darker + 0.05f)).coerceAtLeast(1f)
}

fun isLightColor(rgb: RgbColor): Boolean = relativeLuminance(rgb) > 0.5f

fun adaptiveForegroundHex(background: RgbColor): String =
    if (isLightColor(background)) DARK_FOREGROUND_HEX else LIGHT_FOREGROUND_HEX

fun blendRgb(base: RgbColor, target: RgbColor, ratio: Float): RgbColor {
    val t = if (ratio.isFinite()) ratio.coerceIn(0f, 1f) else 0f
    val inv = 1f - t
    return RgbColor(
        r = (base.r * inv + target.r * t).roundToInt().coerceIn(0, 255),
        g = (base.g * inv + target.g * t).roundToInt().coerceIn(0, 255),
        b = (base.b * inv + target.b * t).roundToInt().coerceIn(0, 255),
    )
}

fun clampBackgroundImageOpacity(opacity: Float): Float {
    if (!opacity.isFinite()) return AppearanceSettings.DEFAULT_BACKGROUND_IMAGE_OPACITY
    return opacity.coerceIn(
        AppearanceSettings.MIN_BACKGROUND_IMAGE_OPACITY,
        AppearanceSettings.MAX_BACKGROUND_IMAGE_OPACITY,
    )
}

fun clampBackgroundImageBlurDp(blurDp: Float): Float {
    if (!blurDp.isFinite()) return AppearanceSettings.DEFAULT_BACKGROUND_IMAGE_BLUR_DP
    return blurDp.coerceIn(
        AppearanceSettings.MIN_BACKGROUND_IMAGE_BLUR_DP,
        AppearanceSettings.MAX_BACKGROUND_IMAGE_BLUR_DP,
    )
}

@Serializable
data class AppearanceSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val backgroundPreset: BackgroundColorPreset = BackgroundColorPreset.DEFAULT,
    val customBackgroundHex: String? = null,
    val foregroundPreset: ForegroundColorPreset = ForegroundColorPreset.CORAL,
    val customAccentHex: String? = null,
    val customForegroundHex: String? = null,
    val backgroundImageFileName: String? = null,
    val backgroundImageOpacity: Float = DEFAULT_BACKGROUND_IMAGE_OPACITY,
    val backgroundImageBlurDp: Float = DEFAULT_BACKGROUND_IMAGE_BLUR_DP,
    val lyricsFontSize: LyricsFontSize = LyricsFontSize.MEDIUM,
    val lyricsAlignment: LyricsAlignment = LyricsAlignment.START,
) {
    fun sanitized(): AppearanceSettings = copy(
        customBackgroundHex = normalizeHexOrNull(customBackgroundHex),
        customAccentHex = normalizeHexOrNull(customAccentHex),
        customForegroundHex = normalizeHexOrNull(customForegroundHex),
        backgroundImageFileName = backgroundImageFileName?.trim()?.takeIf { it.isNotEmpty() },
        backgroundImageOpacity = clampBackgroundImageOpacity(backgroundImageOpacity),
        backgroundImageBlurDp = clampBackgroundImageBlurDp(backgroundImageBlurDp),
    )

    val hasBackgroundImage: Boolean
        get() = !backgroundImageFileName.isNullOrBlank()

    val isDefaultPalette: Boolean
        get() = backgroundPreset == BackgroundColorPreset.DEFAULT &&
            foregroundPreset == ForegroundColorPreset.CORAL &&
            normalizeHexOrNull(customBackgroundHex) == null &&
            normalizeHexOrNull(customAccentHex) == null &&
            normalizeHexOrNull(customForegroundHex) == null

    fun resolvedBackgroundHex(): String? = backgroundPreset.resolvedHex(customBackgroundHex)

    fun resolvedAccentHex(): String = foregroundPreset.resolvedAccentHex(customAccentHex)

    fun resolvedForegroundHex(): String? {
        normalizeHexOrNull(customForegroundHex)?.let { return it }
        val bgRgb = resolvedBackgroundHex()?.let(::parseHexRgb) ?: return null
        return adaptiveForegroundHex(bgRgb)
    }

    fun resolveDarkTheme(systemDark: Boolean): Boolean {
        val bgRgb = resolvedBackgroundHex()?.let(::parseHexRgb)
        if (bgRgb != null) {
            return !isLightColor(bgRgb)
        }
        return when (themeMode) {
            AppThemeMode.SYSTEM -> systemDark
            AppThemeMode.LIGHT -> false
            AppThemeMode.DARK -> true
        }
    }

    fun effectiveBackgroundRgb(systemDark: Boolean = false): RgbColor {
        resolvedBackgroundHex()?.let(::parseHexRgb)?.let { return it }
        return if (resolveDarkTheme(systemDark)) {
            md_theme_dark_background.toRgbColor()
        } else {
            md_theme_light_background.toRgbColor()
        }
    }

    fun effectiveForegroundRgb(systemDark: Boolean = false): RgbColor {
        resolvedForegroundHex()?.let(::parseHexRgb)?.let { return it }
        return if (resolveDarkTheme(systemDark)) {
            md_theme_dark_onBackground.toRgbColor()
        } else {
            md_theme_light_onBackground.toRgbColor()
        }
    }

    fun effectiveAccentRgb(systemDark: Boolean = false): RgbColor {
        if (isDefaultPalette) {
            return if (resolveDarkTheme(systemDark)) {
                md_theme_dark_primary.toRgbColor()
            } else {
                md_theme_light_primary.toRgbColor()
            }
        }
        val raw = parseHexRgb(resolvedAccentHex()) ?: RgbColor(232, 84, 64)
        val dark = resolveDarkTheme(systemDark)
        return if (dark && relativeLuminance(raw) < 0.16f) {
            blendRgb(raw, RgbColor(255, 255, 255), 0.30f)
        } else {
            raw
        }
    }

    fun autoFixContrast(systemDark: Boolean = false): AppearanceSettings {
        val bgRgb = effectiveBackgroundRgb(systemDark)
        val fgRgb = effectiveForegroundRgb(systemDark)
        val accentRgb = effectiveAccentRgb(systemDark)

        val fixedForegroundHex = if (contrastRatio(bgRgb, fgRgb) < MIN_TEXT_CONTRAST) {
            val adaptiveHex = adaptiveForegroundHex(bgRgb)
            val adaptiveRgb = parseHexRgb(adaptiveHex)!!
            if (contrastRatio(bgRgb, adaptiveRgb) >= MIN_TEXT_CONTRAST) {
                if (resolvedBackgroundHex() != null) null else adaptiveHex
            } else {
                val white = RgbColor(255, 255, 255)
                val black = RgbColor(0, 0, 0)
                if (contrastRatio(bgRgb, white) >= contrastRatio(bgRgb, black)) {
                    white.toHex()
                } else {
                    black.toHex()
                }
            }
        } else {
            customForegroundHex
        }

        var fixedForegroundPreset = foregroundPreset
        var fixedCustomAccentHex = customAccentHex
        if (contrastRatio(bgRgb, accentRgb) < MIN_ACCENT_CONTRAST) {
            val white = RgbColor(255, 255, 255)
            val black = RgbColor(0, 0, 0)
            val target = if (contrastRatio(bgRgb, white) >= contrastRatio(bgRgb, black)) white else black
            var best = target
            for (step in 1..20) {
                val candidate = blendRgb(accentRgb, target, step / 20f)
                if (contrastRatio(bgRgb, candidate) >= MIN_ACCENT_CONTRAST) {
                    best = candidate
                    break
                }
            }
            fixedForegroundPreset = ForegroundColorPreset.CUSTOM
            fixedCustomAccentHex = best.toHex()
        }

        return copy(
            customForegroundHex = fixedForegroundHex,
            foregroundPreset = fixedForegroundPreset,
            customAccentHex = fixedCustomAccentHex,
        ).sanitized()
    }

    companion object {
        const val MIN_BACKGROUND_IMAGE_OPACITY = 0.10f
        const val MAX_BACKGROUND_IMAGE_OPACITY = 1.0f
        const val DEFAULT_BACKGROUND_IMAGE_OPACITY = 0.35f
        const val MIN_BACKGROUND_IMAGE_BLUR_DP = 0f
        const val MAX_BACKGROUND_IMAGE_BLUR_DP = 24f
        const val DEFAULT_BACKGROUND_IMAGE_BLUR_DP = 0f
        const val MIN_TEXT_CONTRAST = 4.5f
        const val MIN_ACCENT_CONTRAST = 3.0f
    }
}
