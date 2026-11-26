package com.musico.ui.theme

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.roundToInt

@Serializable
enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
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
) {
    fun sanitized(): AppearanceSettings = copy(
        customBackgroundHex = normalizeHexOrNull(customBackgroundHex),
        customAccentHex = normalizeHexOrNull(customAccentHex),
        customForegroundHex = normalizeHexOrNull(customForegroundHex),
        backgroundImageFileName = backgroundImageFileName?.trim()?.takeIf { it.isNotEmpty() },
        backgroundImageOpacity = clampBackgroundImageOpacity(backgroundImageOpacity),
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

    companion object {
        const val MIN_BACKGROUND_IMAGE_OPACITY = 0.10f
        const val MAX_BACKGROUND_IMAGE_OPACITY = 1.0f
        const val DEFAULT_BACKGROUND_IMAGE_OPACITY = 0.35f
    }
}
