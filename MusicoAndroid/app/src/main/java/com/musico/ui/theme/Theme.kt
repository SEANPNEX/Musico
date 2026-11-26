package com.musico.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    outline = md_theme_light_outline,
    outlineVariant = md_theme_light_outlineVariant,
    inverseSurface = md_theme_light_inverseSurface,
    inverseOnSurface = md_theme_light_inverseOnSurface,
    inversePrimary = md_theme_light_inversePrimary,
    surfaceContainerLowest = md_theme_light_surfaceContainerLowest,
    surfaceContainerLow = md_theme_light_surfaceContainerLow,
    surfaceContainer = md_theme_light_surfaceContainer,
    surfaceContainerHigh = md_theme_light_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_light_surfaceContainerHighest
)

private val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    outline = md_theme_dark_outline,
    outlineVariant = md_theme_dark_outlineVariant,
    inverseSurface = md_theme_dark_inverseSurface,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    inversePrimary = md_theme_dark_inversePrimary,
    surfaceContainerLowest = md_theme_dark_surfaceContainerLowest,
    surfaceContainerLow = md_theme_dark_surfaceContainerLow,
    surfaceContainer = md_theme_dark_surfaceContainer,
    surfaceContainerHigh = md_theme_dark_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_dark_surfaceContainerHighest
)

fun buildCustomColorScheme(
    appearance: AppearanceSettings,
    resolvedDark: Boolean,
    baseScheme: ColorScheme = if (resolvedDark) DarkColorScheme else LightColorScheme,
): ColorScheme {
    if (appearance.isDefaultPalette) return baseScheme

    val bgRgb = appearance.resolvedBackgroundHex()?.let(::parseHexRgb)
    val effectiveBgRgb = bgRgb ?: baseScheme.background.toRgbColor()
    val effectiveBgColor = bgRgb?.toComposeColor() ?: baseScheme.background

    val fgHex = appearance.resolvedForegroundHex()
    val fgRgb = fgHex?.let(::parseHexRgb)
    val effectiveFgRgb = fgRgb ?: baseScheme.onBackground.toRgbColor()
    val effectiveFgColor = fgRgb?.toComposeColor() ?: baseScheme.onBackground
    val onSurfaceVariantColor = if (fgRgb != null) {
        effectiveFgColor.copy(alpha = 0.70f)
    } else {
        baseScheme.onSurfaceVariant
    }

    val rawAccentRgb = parseHexRgb(appearance.resolvedAccentHex()) ?: baseScheme.primary.toRgbColor()
    val primaryRgb = if (resolvedDark && relativeLuminance(rawAccentRgb) < 0.16f) {
        blendRgb(rawAccentRgb, RgbColor(255, 255, 255), 0.30f)
    } else {
        rawAccentRgb
    }
    val primaryColor = primaryRgb.toComposeColor()
    val onPrimaryColor = if (isLightColor(primaryRgb)) Color(0xFF141416) else Color.White

    val containerMixRatio = if (resolvedDark) 0.24f else 0.16f
    val primaryContainerRgb = blendRgb(effectiveBgRgb, primaryRgb, containerMixRatio)
    val primaryContainerColor = primaryContainerRgb.toComposeColor()
    val onPrimaryContainerColor = if (isLightColor(primaryContainerRgb)) {
        Color(0xFF141416)
    } else {
        Color(0xFFF5F5F7)
    }

    val white = RgbColor(255, 255, 255)
    val black = RgbColor(0, 0, 0)

    return if (bgRgb != null) {
        val surfaceLowest = if (resolvedDark) {
            blendRgb(bgRgb, black, 0.22f).toComposeColor()
        } else {
            blendRgb(bgRgb, white, 0.65f).toComposeColor()
        }
        val surfaceLow = if (resolvedDark) {
            blendRgb(bgRgb, white, 0.04f).toComposeColor()
        } else {
            blendRgb(bgRgb, black, 0.03f).toComposeColor()
        }
        val surfaceContainer = if (resolvedDark) {
            blendRgb(bgRgb, white, 0.07f).toComposeColor()
        } else {
            blendRgb(bgRgb, black, 0.05f).toComposeColor()
        }
        val surfaceHigh = if (resolvedDark) {
            blendRgb(bgRgb, white, 0.10f).toComposeColor()
        } else {
            blendRgb(bgRgb, black, 0.08f).toComposeColor()
        }
        val surfaceHighest = if (resolvedDark) {
            blendRgb(bgRgb, white, 0.14f).toComposeColor()
        } else {
            blendRgb(bgRgb, black, 0.11f).toComposeColor()
        }
        val surfaceVariant = if (resolvedDark) {
            blendRgb(bgRgb, white, 0.12f).toComposeColor()
        } else {
            blendRgb(bgRgb, black, 0.09f).toComposeColor()
        }

        baseScheme.copy(
            primary = primaryColor,
            onPrimary = onPrimaryColor,
            primaryContainer = primaryContainerColor,
            onPrimaryContainer = onPrimaryContainerColor,
            secondary = primaryColor,
            onSecondary = onPrimaryColor,
            secondaryContainer = primaryContainerColor,
            onSecondaryContainer = onPrimaryContainerColor,
            inversePrimary = primaryColor,
            background = effectiveBgColor,
            onBackground = effectiveFgColor,
            surface = effectiveBgColor,
            onSurface = effectiveFgColor,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariantColor,
            outline = blendRgb(bgRgb, effectiveFgRgb, 0.35f).toComposeColor(),
            outlineVariant = blendRgb(bgRgb, effectiveFgRgb, 0.16f).toComposeColor(),
            inverseSurface = effectiveFgColor,
            inverseOnSurface = effectiveBgColor,
            surfaceContainerLowest = surfaceLowest,
            surfaceContainerLow = surfaceLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceHigh,
            surfaceContainerHighest = surfaceHighest,
        )
    } else {
        baseScheme.copy(
            primary = primaryColor,
            onPrimary = onPrimaryColor,
            primaryContainer = primaryContainerColor,
            onPrimaryContainer = onPrimaryContainerColor,
            secondary = primaryColor,
            onSecondary = onPrimaryColor,
            secondaryContainer = primaryContainerColor,
            onSecondaryContainer = onPrimaryContainerColor,
            inversePrimary = primaryColor,
            onBackground = effectiveFgColor,
            onSurface = effectiveFgColor,
            onSurfaceVariant = onSurfaceVariantColor,
        )
    }
}

@Composable
fun MusicoTheme(
    appearance: AppearanceSettings = AppearanceSettings(),
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val resolvedDark = appearance.resolveDarkTheme(darkTheme)
    val colorScheme = if (appearance.isDefaultPalette) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (resolvedDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            resolvedDark -> DarkColorScheme
            else -> LightColorScheme
        }
    } else {
        buildCustomColorScheme(
            appearance = appearance,
            resolvedDark = resolvedDark,
            baseScheme = if (resolvedDark) DarkColorScheme else LightColorScheme,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MusicoTypography,
        shapes = MusicoShapes,
        content = content
    )
}
