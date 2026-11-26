using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using MusicoWindows.Theme;

namespace MusicoWindows.Theme
{
    public enum AppThemeMode
    {
        System,
        Light,
        Dark
    }
}

namespace MusicoWindows.Models
{
    public enum BackgroundColorPreset
    {
        Default,
        Obsidian,
        Midnight,
        WarmCharcoal,
        ForestNight,
        Paper,
        Mist,
        Blossom,
        Custom
    }

    public enum ForegroundColorPreset
    {
        Coral,
        Ocean,
        Emerald,
        Amethyst,
        Amber,
        Rose,
        Monochrome,
        Custom
    }

    public enum LyricsFontSize
    {
        Compact,
        Medium,
        Large
    }

    public enum LyricsAlignment
    {
        Leading,
        Center
    }

    public readonly record struct RgbColor(byte R, byte G, byte B);

    public readonly record struct ArgbColor(byte A, byte R, byte G, byte B);

    public sealed record AppearanceSettings
    {
        public const double MinBackgroundOpacity = 0.15;
        public const double MaxBackgroundOpacity = 1.0;
        public const double DefaultBackgroundOpacity = 1.0;

        public const double MinSidebarOpacity = 0.15;
        public const double MaxSidebarOpacity = 1.0;

        public const double MinBackgroundImageOpacity = 0.10;
        public const double MaxBackgroundImageOpacity = 1.0;
        public const double DefaultBackgroundImageOpacity = 0.35;

        public const double MinBackgroundImageBlur = 0.0;
        public const double MaxBackgroundImageBlur = 24.0;
        public const double DefaultBackgroundImageBlur = 0.0;

        public AppThemeMode ThemeMode { get; init; } = AppThemeMode.System;
        public BackgroundColorPreset BackgroundPreset { get; init; } = BackgroundColorPreset.Default;
        public string? CustomBackgroundHex { get; init; }
        public BackgroundColorPreset SidebarBackgroundPreset { get; init; } = BackgroundColorPreset.Default;
        public string? CustomSidebarBackgroundHex { get; init; }
        public ForegroundColorPreset ForegroundPreset { get; init; } = ForegroundColorPreset.Coral;
        public string? CustomAccentHex { get; init; }
        public string? CustomForegroundHex { get; init; }
        public double BackgroundOpacity { get; init; } = DefaultBackgroundOpacity;
        public double? SidebarOpacity { get; init; }
        public string? BackgroundImageFileName { get; init; }
        public double BackgroundImageOpacity { get; init; } = DefaultBackgroundImageOpacity;
        public double BackgroundImageBlur { get; init; } = DefaultBackgroundImageBlur;
        public LyricsFontSize LyricsFontSize { get; init; } = LyricsFontSize.Medium;
        public LyricsAlignment LyricsAlignment { get; init; } = LyricsAlignment.Leading;

        public static double ClampBackgroundOpacity(double value) =>
            double.IsNaN(value) || double.IsInfinity(value)
                ? DefaultBackgroundOpacity
                : Math.Clamp(value, MinBackgroundOpacity, MaxBackgroundOpacity);

        public static double ClampSidebarOpacity(double value) =>
            double.IsNaN(value) || double.IsInfinity(value)
                ? DefaultBackgroundOpacity
                : Math.Clamp(value, MinSidebarOpacity, MaxSidebarOpacity);

        public double ResolveEffectiveSidebarOpacity() =>
            SidebarOpacity.HasValue
                ? ClampSidebarOpacity(SidebarOpacity.Value)
                : ClampBackgroundOpacity(BackgroundOpacity);

        public static double ClampBackgroundImageOpacity(double value) =>
            double.IsNaN(value) || double.IsInfinity(value)
                ? DefaultBackgroundImageOpacity
                : Math.Clamp(value, MinBackgroundImageOpacity, MaxBackgroundImageOpacity);

        public static double ClampBackgroundImageBlur(double value) =>
            double.IsNaN(value) || double.IsInfinity(value)
                ? DefaultBackgroundImageBlur
                : Math.Clamp(value, MinBackgroundImageBlur, MaxBackgroundImageBlur);

        public AppearanceSettings Normalize() => this with
        {
            BackgroundPreset = Enum.IsDefined(BackgroundPreset) ? BackgroundPreset : BackgroundColorPreset.Default,
            CustomBackgroundHex = ColorMath.NormalizeHexOrNull(CustomBackgroundHex),
            SidebarBackgroundPreset = Enum.IsDefined(SidebarBackgroundPreset) ? SidebarBackgroundPreset : BackgroundColorPreset.Default,
            CustomSidebarBackgroundHex = ColorMath.NormalizeHexOrNull(CustomSidebarBackgroundHex),
            ForegroundPreset = Enum.IsDefined(ForegroundPreset) ? ForegroundPreset : ForegroundColorPreset.Coral,
            CustomAccentHex = ColorMath.NormalizeHexOrNull(CustomAccentHex),
            CustomForegroundHex = ColorMath.NormalizeHexOrNull(CustomForegroundHex),
            BackgroundOpacity = ClampBackgroundOpacity(BackgroundOpacity),
            SidebarOpacity = SidebarOpacity.HasValue ? ClampSidebarOpacity(SidebarOpacity.Value) : null,
            BackgroundImageFileName = string.IsNullOrWhiteSpace(BackgroundImageFileName)
                ? null
                : Path.GetFileName(BackgroundImageFileName.Trim()),
            BackgroundImageOpacity = ClampBackgroundImageOpacity(BackgroundImageOpacity),
            BackgroundImageBlur = ClampBackgroundImageBlur(BackgroundImageBlur),
            LyricsFontSize = Enum.IsDefined(LyricsFontSize) ? LyricsFontSize : LyricsFontSize.Medium,
            LyricsAlignment = Enum.IsDefined(LyricsAlignment) ? LyricsAlignment : LyricsAlignment.Leading
        };
    }

    public sealed record ThemePalette(
        bool IsDark,
        bool SidebarIsDark,
        RgbColor Accent,
        RgbColor AccentHover,
        RgbColor AccentPressed,
        ArgbColor AccentSubtle,
        RgbColor TextOnAccent,
        ArgbColor WindowBackground,
        RgbColor AcrylicTintBase,
        ArgbColor TitleBar,
        ArgbColor NavPane,
        ArgbColor ContentBackground,
        ArgbColor PlayerBar,
        ArgbColor SidePanel,
        ArgbColor CardBackground,
        ArgbColor CardHover,
        ArgbColor DialogSurface,
        ArgbColor MenuSurface,
        ArgbColor ControlFill,
        ArgbColor ControlHover,
        ArgbColor ControlPressed,
        ArgbColor ControlBorder,
        ArgbColor Divider,
        ArgbColor TextPrimary,
        ArgbColor TextSecondary,
        ArgbColor TextTertiary,
        ArgbColor SidebarTextPrimary,
        ArgbColor SidebarTextSecondary,
        ArgbColor SidebarTextTertiary,
        ArgbColor SliderTrack,
        ArgbColor SliderThumbFill,
        ArgbColor SubtleHover,
        ArgbColor SubtlePressed,
        ArgbColor RevealBorder);

    public static class AppearancePresets
    {
        public static readonly RgbColor DefaultCoralAccent = new(0xE8, 0x54, 0x40);
        public static readonly RgbColor LightTextOnDark = new(0xF5, 0xF5, 0xF7);
        public static readonly RgbColor DarkTextOnLight = new(0x14, 0x14, 0x16);

        public static readonly IReadOnlyList<BackgroundColorPreset> AllBackgroundPresets =
        [
            BackgroundColorPreset.Default,
            BackgroundColorPreset.Obsidian,
            BackgroundColorPreset.Midnight,
            BackgroundColorPreset.WarmCharcoal,
            BackgroundColorPreset.ForestNight,
            BackgroundColorPreset.Paper,
            BackgroundColorPreset.Mist,
            BackgroundColorPreset.Blossom,
            BackgroundColorPreset.Custom
        ];

        public static readonly IReadOnlyList<ForegroundColorPreset> AllForegroundPresets =
        [
            ForegroundColorPreset.Coral,
            ForegroundColorPreset.Ocean,
            ForegroundColorPreset.Emerald,
            ForegroundColorPreset.Amethyst,
            ForegroundColorPreset.Amber,
            ForegroundColorPreset.Rose,
            ForegroundColorPreset.Monochrome,
            ForegroundColorPreset.Custom
        ];

        public static string? GetBackgroundHex(BackgroundColorPreset preset) => preset switch
        {
            BackgroundColorPreset.Obsidian => "#09090B",
            BackgroundColorPreset.Midnight => "#0F172A",
            BackgroundColorPreset.WarmCharcoal => "#1C1917",
            BackgroundColorPreset.ForestNight => "#0F1D17",
            BackgroundColorPreset.Paper => "#F8F6F1",
            BackgroundColorPreset.Mist => "#EEF3F8",
            BackgroundColorPreset.Blossom => "#FCF2F4",
            _ => null
        };

        public static string GetForegroundAccentHex(ForegroundColorPreset preset) => preset switch
        {
            ForegroundColorPreset.Ocean => "#2563EB",
            ForegroundColorPreset.Emerald => "#10B981",
            ForegroundColorPreset.Amethyst => "#8B5CF6",
            ForegroundColorPreset.Amber => "#F59E0B",
            ForegroundColorPreset.Rose => "#F43F5E",
            ForegroundColorPreset.Monochrome => "#71717A",
            _ => "#E85440"
        };

        public static RgbColor? ResolveBackgroundColor(AppearanceSettings settings)
        {
            if (settings.BackgroundPreset == BackgroundColorPreset.Custom)
            {
                return ColorMath.TryParseHex(settings.CustomBackgroundHex, out var custom)
                    ? custom
                    : null;
            }

            string? presetHex = GetBackgroundHex(settings.BackgroundPreset);
            return presetHex is not null && ColorMath.TryParseHex(presetHex, out var presetRgb)
                ? presetRgb
                : null;
        }

        public static RgbColor? ResolveSidebarBackgroundColor(AppearanceSettings settings)
        {
            if (settings.SidebarBackgroundPreset == BackgroundColorPreset.Default)
                return null;

            if (settings.SidebarBackgroundPreset == BackgroundColorPreset.Custom)
            {
                return ColorMath.TryParseHex(settings.CustomSidebarBackgroundHex, out var custom)
                    ? custom
                    : null;
            }

            string? presetHex = GetBackgroundHex(settings.SidebarBackgroundPreset);
            return presetHex is not null && ColorMath.TryParseHex(presetHex, out var presetRgb)
                ? presetRgb
                : null;
        }

        public static bool ResolveSidebarIsDark(AppearanceSettings settings, bool mainIsDark)
        {
            if (ResolveSidebarBackgroundColor(settings) is RgbColor sidebarBg)
            {
                return !ColorMath.IsLight(sidebarBg);
            }

            return mainIsDark;
        }

        public static RgbColor ResolveSidebarForegroundColor(
            AppearanceSettings settings,
            bool mainIsDark,
            RgbColor mainForeground)
        {
            if (ResolveSidebarBackgroundColor(settings) is RgbColor sidebarBg)
            {
                return ColorMath.ContrastingForeground(sidebarBg);
            }

            return mainForeground;
        }

        public static RgbColor ResolveAccentColor(AppearanceSettings settings)
        {
            if (settings.ForegroundPreset == ForegroundColorPreset.Custom &&
                ColorMath.TryParseHex(settings.CustomAccentHex, out var custom))
            {
                return custom;
            }

            string hex = GetForegroundAccentHex(settings.ForegroundPreset);
            return ColorMath.TryParseHex(hex, out var presetRgb)
                ? presetRgb
                : DefaultCoralAccent;
        }

        public static bool ResolveIsDark(AppearanceSettings settings, bool systemAppsUseLightTheme)
        {
            if (ResolveBackgroundColor(settings) is RgbColor bg)
            {
                return !ColorMath.IsLight(bg);
            }

            return settings.ThemeMode switch
            {
                AppThemeMode.Light => false,
                AppThemeMode.Dark => true,
                _ => !systemAppsUseLightTheme
            };
        }

        public static RgbColor ResolveForegroundColor(AppearanceSettings settings, bool effectiveIsDark)
        {
            if (settings.ForegroundPreset == ForegroundColorPreset.Custom &&
                ColorMath.TryParseHex(settings.CustomForegroundHex, out var customFg))
            {
                return customFg;
            }

            if (ResolveBackgroundColor(settings) is RgbColor bg)
            {
                return ColorMath.ContrastingForeground(bg);
            }

            return effectiveIsDark ? LightTextOnDark : DarkTextOnLight;
        }

        public static RgbColor ResolveTextOnAccentColor(RgbColor accent) =>
            ColorMath.RelativeLuminance(accent) >= 0.62
                ? DarkTextOnLight
                : new RgbColor(0xFF, 0xFF, 0xFF);
    }

    public static class ColorMath
    {
        private static readonly RgbColor Black = new(0x00, 0x00, 0x00);
        private static readonly RgbColor White = new(0xFF, 0xFF, 0xFF);

        public static bool TryParseHex(string? hex, out RgbColor color)
        {
            color = default;
            if (string.IsNullOrWhiteSpace(hex))
                return false;

            ReadOnlySpan<char> span = hex.AsSpan().Trim();
            if (span.StartsWith("#"))
                span = span[1..];

            if (span.Length != 6)
                return false;

            if (!byte.TryParse(span[..2], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out byte r) ||
                !byte.TryParse(span.Slice(2, 2), NumberStyles.HexNumber, CultureInfo.InvariantCulture, out byte g) ||
                !byte.TryParse(span.Slice(4, 2), NumberStyles.HexNumber, CultureInfo.InvariantCulture, out byte b))
            {
                return false;
            }

            color = new RgbColor(r, g, b);
            return true;
        }

        public static string ToHex(RgbColor color) =>
            $"#{color.R:X2}{color.G:X2}{color.B:X2}";

        public static string? NormalizeHexOrNull(string? hex) =>
            TryParseHex(hex, out var parsed) ? ToHex(parsed) : null;

        public static double RelativeLuminance(RgbColor color) =>
            (0.2126 * color.R + 0.7152 * color.G + 0.0722 * color.B) / 255.0;

        public static double LinearRelativeLuminance(RgbColor color)
        {
            static double Linearize(byte channel)
            {
                double s = channel / 255.0;
                return s <= 0.04045
                    ? s / 12.92
                    : Math.Pow((s + 0.055) / 1.055, 2.4);
            }

            return 0.2126 * Linearize(color.R) +
                   0.7152 * Linearize(color.G) +
                   0.0722 * Linearize(color.B);
        }

        public static double ContrastRatio(RgbColor a, RgbColor b)
        {
            double la = LinearRelativeLuminance(a);
            double lb = LinearRelativeLuminance(b);
            double lighter = Math.Max(la, lb);
            double darker = Math.Min(la, lb);
            return (lighter + 0.05) / (darker + 0.05);
        }

        public static RgbColor AutoFixForeground(RgbColor bg, RgbColor fg) =>
            AutoFixContrast(bg, fg, minimumRatio: 4.5);

        public static RgbColor AutoFixAccent(RgbColor bg, RgbColor accent) =>
            AutoFixContrast(bg, accent, minimumRatio: 3.0);

        private static RgbColor AutoFixContrast(RgbColor bg, RgbColor color, double minimumRatio)
        {
            if (ContrastRatio(bg, color) >= minimumRatio)
                return color;

            double whiteContrast = ContrastRatio(bg, White);
            double blackContrast = ContrastRatio(bg, Black);
            RgbColor primaryTarget = whiteContrast >= blackContrast ? White : Black;
            RgbColor secondaryTarget = whiteContrast >= blackContrast ? Black : White;

            if (TryFindMinimumBlend(bg, color, primaryTarget, minimumRatio, out var fixedPrimary))
                return fixedPrimary;
            if (TryFindMinimumBlend(bg, color, secondaryTarget, minimumRatio, out var fixedSecondary))
                return fixedSecondary;

            return primaryTarget;
        }

        private static bool TryFindMinimumBlend(
            RgbColor bg,
            RgbColor color,
            RgbColor target,
            double minimumRatio,
            out RgbColor result)
        {
            if (ContrastRatio(bg, target) < minimumRatio)
            {
                result = default;
                return false;
            }

            double lo = 0.0;
            double hi = 1.0;
            RgbColor best = target;
            for (int i = 0; i < 24; i++)
            {
                double mid = (lo + hi) * 0.5;
                RgbColor candidate = Blend(color, target, mid);
                if (ContrastRatio(bg, candidate) >= minimumRatio)
                {
                    best = candidate;
                    hi = mid;
                }
                else
                {
                    lo = mid;
                }
            }

            result = best;
            return true;
        }

        public static bool IsLight(RgbColor color) =>
            RelativeLuminance(color) >= 0.5;

        public static RgbColor ContrastingForeground(RgbColor background) =>
            IsLight(background) ? AppearancePresets.DarkTextOnLight : AppearancePresets.LightTextOnDark;

        public static RgbColor Blend(RgbColor from, RgbColor to, double amount)
        {
            double t = Math.Clamp(amount, 0.0, 1.0);
            byte r = (byte)Math.Clamp((int)Math.Round(from.R + (to.R - from.R) * t), 0, 255);
            byte g = (byte)Math.Clamp((int)Math.Round(from.G + (to.G - from.G) * t), 0, 255);
            byte b = (byte)Math.Clamp((int)Math.Round(from.B + (to.B - from.B) * t), 0, 255);
            return new RgbColor(r, g, b);
        }

        public static RgbColor AccentHover(RgbColor accent, bool isDark) =>
            isDark ? Blend(accent, White, 0.14) : Blend(accent, Black, 0.12);

        public static RgbColor AccentPressed(RgbColor accent, bool isDark) =>
            isDark ? Blend(accent, Black, 0.16) : Blend(accent, Black, 0.24);

        public static ArgbColor AccentSubtle(RgbColor accent, bool isDark) =>
            new(isDark ? (byte)0x29 : (byte)0x22, accent.R, accent.G, accent.B);

        public static uint ToAcrylicAbgr(RgbColor tintBase, double backgroundOpacity)
        {
            double opacity = AppearanceSettings.ClampBackgroundOpacity(backgroundOpacity);
            if (opacity < 0.999)
            {
                return (0x01u << 24) |
                       ((uint)tintBase.B << 16) |
                       ((uint)tintBase.G << 8) |
                       tintBase.R;
            }

            const byte alpha = 0xD2;
            return ((uint)alpha << 24) |
                   ((uint)tintBase.B << 16) |
                   ((uint)tintBase.G << 8) |
                   tintBase.R;
        }

        public static double ComputeEffectiveBackgroundImageOpacity(AppearanceSettings settings) =>
            AppearanceSettings.ClampBackgroundImageOpacity(settings.BackgroundImageOpacity) *
            AppearanceSettings.ClampBackgroundOpacity(settings.BackgroundOpacity);

        public static byte ComputeBackgroundScrimAlpha(AppearanceSettings settings)
        {
            double imgOpacity = AppearanceSettings.ClampBackgroundImageOpacity(settings.BackgroundImageOpacity);
            double scrimFraction = Math.Clamp(0.45 - 0.35 * imgOpacity, 0.08, 0.45);
            return (byte)Math.Clamp((int)Math.Round(255.0 * scrimFraction), 0x10, 0xFF);
        }

        public static ThemePalette BuildPalette(
            AppearanceSettings settings,
            bool systemAppsUseLightTheme,
            bool hasBackgroundImage)
        {
            AppearanceSettings normalized = settings.Normalize();
            bool isDark = AppearancePresets.ResolveIsDark(normalized, systemAppsUseLightTheme);
            double op = normalized.BackgroundOpacity;
            double sidebarOp = normalized.ResolveEffectiveSidebarOpacity();
            bool anyTranslucent = op < 0.999 || sidebarOp < 0.999;

            RgbColor accent = AppearancePresets.ResolveAccentColor(normalized);
            RgbColor accentHover = AccentHover(accent, isDark);
            RgbColor accentPressed = AccentPressed(accent, isDark);
            ArgbColor accentSubtle = AccentSubtle(accent, isDark);
            RgbColor textOnAccent = AppearancePresets.ResolveTextOnAccentColor(accent);

            RgbColor? customBg = AppearancePresets.ResolveBackgroundColor(normalized);
            RgbColor? customSidebarBg = AppearancePresets.ResolveSidebarBackgroundColor(normalized);
            bool sidebarIsDark = AppearancePresets.ResolveSidebarIsDark(normalized, isDark);

            RgbColor windowRgb;
            RgbColor navPaneRgb;
            RgbColor contentRgb;
            RgbColor playerBarRgb;
            RgbColor sidePanelRgb;
            RgbColor cardRgb;
            RgbColor cardHoverRgb;
            RgbColor dialogRgb;
            RgbColor menuRgb;
            RgbColor controlFillRgb;
            RgbColor controlHoverRgb;
            RgbColor controlPressedRgb;
            RgbColor controlBorderRgb;
            RgbColor dividerRgb;
            RgbColor sliderTrackRgb;
            RgbColor sliderThumbFillRgb;

            if (customBg is RgbColor bg)
            {
                if (isDark)
                {
                    windowRgb = Blend(bg, Black, 0.14);
                    navPaneRgb = Blend(bg, Black, 0.05);
                    contentRgb = bg;
                    playerBarRgb = Blend(bg, White, 0.04);
                    sidePanelRgb = Blend(bg, White, 0.02);
                    cardRgb = Blend(bg, White, 0.06);
                    cardHoverRgb = Blend(bg, White, 0.11);
                    dialogRgb = Blend(bg, White, 0.07);
                    menuRgb = Blend(bg, White, 0.08);
                    controlFillRgb = Blend(bg, White, 0.09);
                    controlHoverRgb = Blend(bg, White, 0.14);
                    controlPressedRgb = Blend(bg, White, 0.19);
                    controlBorderRgb = Blend(bg, White, 0.18);
                    dividerRgb = Blend(bg, White, 0.10);
                    sliderTrackRgb = Blend(bg, White, 0.24);
                    sliderThumbFillRgb = Blend(bg, White, 0.05);
                }
                else
                {
                    windowRgb = Blend(bg, Black, 0.05);
                    navPaneRgb = Blend(bg, Black, 0.06);
                    contentRgb = bg;
                    playerBarRgb = Blend(bg, White, 0.45);
                    sidePanelRgb = Blend(bg, Black, 0.03);
                    cardRgb = Blend(bg, Black, 0.04);
                    cardHoverRgb = Blend(bg, Black, 0.09);
                    dialogRgb = Blend(bg, White, 0.65);
                    menuRgb = Blend(bg, White, 0.50);
                    controlFillRgb = Blend(bg, Black, 0.08);
                    controlHoverRgb = Blend(bg, Black, 0.13);
                    controlPressedRgb = Blend(bg, Black, 0.19);
                    controlBorderRgb = Blend(bg, Black, 0.18);
                    dividerRgb = Blend(bg, Black, 0.10);
                    sliderTrackRgb = Blend(bg, Black, 0.18);
                    sliderThumbFillRgb = White;
                }
            }
            else if (isDark)
            {
                windowRgb = new RgbColor(0x14, 0x14, 0x14);
                navPaneRgb = new RgbColor(0x18, 0x18, 0x18);
                contentRgb = new RgbColor(0x17, 0x17, 0x17);
                playerBarRgb = new RgbColor(0x1E, 0x1E, 0x1E);
                sidePanelRgb = new RgbColor(0x1B, 0x1B, 0x1B);
                cardRgb = new RgbColor(0x22, 0x22, 0x22);
                cardHoverRgb = new RgbColor(0x2C, 0x2C, 0x2C);
                dialogRgb = new RgbColor(0x24, 0x24, 0x24);
                menuRgb = new RgbColor(0x26, 0x26, 0x26);
                controlFillRgb = new RgbColor(0x2B, 0x2B, 0x2B);
                controlHoverRgb = new RgbColor(0x36, 0x36, 0x36);
                controlPressedRgb = new RgbColor(0x40, 0x40, 0x40);
                controlBorderRgb = new RgbColor(0x3A, 0x3A, 0x3A);
                dividerRgb = new RgbColor(0x2A, 0x2A, 0x2A);
                sliderTrackRgb = new RgbColor(0x4A, 0x4A, 0x4A);
                sliderThumbFillRgb = new RgbColor(0x1F, 0x1F, 0x1F);
            }
            else
            {
                windowRgb = new RgbColor(0xF2, 0xF2, 0xF2);
                navPaneRgb = new RgbColor(0xEF, 0xEF, 0xEF);
                contentRgb = new RgbColor(0xFF, 0xFF, 0xFF);
                playerBarRgb = new RgbColor(0xFA, 0xFA, 0xFA);
                sidePanelRgb = new RgbColor(0xF6, 0xF6, 0xF6);
                cardRgb = new RgbColor(0xF4, 0xF4, 0xF4);
                cardHoverRgb = new RgbColor(0xE8, 0xE8, 0xE8);
                dialogRgb = new RgbColor(0xFF, 0xFF, 0xFF);
                menuRgb = new RgbColor(0xF7, 0xF7, 0xF7);
                controlFillRgb = new RgbColor(0xE6, 0xE6, 0xE6);
                controlHoverRgb = new RgbColor(0xD8, 0xD8, 0xD8);
                controlPressedRgb = new RgbColor(0xC8, 0xC8, 0xC8);
                controlBorderRgb = new RgbColor(0xCC, 0xCC, 0xCC);
                dividerRgb = new RgbColor(0xE4, 0xE4, 0xE4);
                sliderTrackRgb = new RgbColor(0xCC, 0xCC, 0xCC);
                sliderThumbFillRgb = new RgbColor(0xFF, 0xFF, 0xFF);
            }

            if (customSidebarBg is RgbColor sb)
            {
                navPaneRgb = sb;
                sidePanelRgb = sidebarIsDark ? Blend(sb, White, 0.03) : Blend(sb, Black, 0.03);
            }

            byte windowAlpha = (anyTranslucent || hasBackgroundImage || customSidebarBg is not null)
                ? (byte)0x00
                : (byte)0xFF;
            byte titleBarAlpha = (byte)Math.Clamp((int)Math.Round(255.0 * op), 0x18, 0xFF);
            byte navAlpha = (byte)Math.Clamp((int)Math.Round(255.0 * sidebarOp), 0x18, 0xFF);
            byte sidePanelAlpha = (byte)Math.Clamp((int)Math.Round(255.0 * sidebarOp), 0x18, 0xFF);
            byte playerBarAlpha = (byte)Math.Clamp((int)Math.Round(255.0 * op), 0x18, 0xFF);

            bool translucentSurfaces = anyTranslucent || hasBackgroundImage;
            byte cardAlpha = translucentSurfaces
                ? (byte)Math.Clamp((int)Math.Round(255.0 * op * 0.55), 0x24, 0xEB)
                : (byte)0xFF;
            byte controlAlpha = translucentSurfaces
                ? (byte)Math.Clamp((int)Math.Round(255.0 * op * 0.65), 0x38, 0xEB)
                : (byte)0xFF;

            RgbColor fgPrimary = AppearancePresets.ResolveForegroundColor(normalized, isDark);
            RgbColor sidebarFgPrimary = AppearancePresets.ResolveSidebarForegroundColor(normalized, isDark, fgPrimary);

            return new ThemePalette(
                IsDark: isDark,
                SidebarIsDark: sidebarIsDark,
                Accent: accent,
                AccentHover: accentHover,
                AccentPressed: accentPressed,
                AccentSubtle: accentSubtle,
                TextOnAccent: textOnAccent,
                WindowBackground: WithAlpha(windowAlpha, windowRgb),
                AcrylicTintBase: navPaneRgb,
                TitleBar: WithAlpha(titleBarAlpha, windowRgb),
                NavPane: WithAlpha(navAlpha, navPaneRgb),
                ContentBackground: WithAlpha(0xFF, contentRgb),
                PlayerBar: WithAlpha(playerBarAlpha, playerBarRgb),
                SidePanel: WithAlpha(sidePanelAlpha, sidePanelRgb),
                CardBackground: WithAlpha(cardAlpha, cardRgb),
                CardHover: WithAlpha(cardAlpha, cardHoverRgb),
                DialogSurface: WithAlpha(0xFA, dialogRgb),
                MenuSurface: WithAlpha(0xFA, menuRgb),
                ControlFill: WithAlpha(controlAlpha, controlFillRgb),
                ControlHover: WithAlpha(controlAlpha, controlHoverRgb),
                ControlPressed: WithAlpha(controlAlpha, controlPressedRgb),
                ControlBorder: WithAlpha(0xFF, controlBorderRgb),
                Divider: WithAlpha(hasBackgroundImage ? (byte)0x88 : (byte)0xFF, dividerRgb),
                TextPrimary: WithAlpha(0xFF, fgPrimary),
                TextSecondary: WithAlpha(0xAD, fgPrimary),
                TextTertiary: WithAlpha(0x75, fgPrimary),
                SidebarTextPrimary: WithAlpha(0xFF, sidebarFgPrimary),
                SidebarTextSecondary: WithAlpha(0xAD, sidebarFgPrimary),
                SidebarTextTertiary: WithAlpha(0x75, sidebarFgPrimary),
                SliderTrack: WithAlpha(0xFF, sliderTrackRgb),
                SliderThumbFill: WithAlpha(0xFF, sliderThumbFillRgb),
                SubtleHover: isDark ? new ArgbColor(0x14, 0xFF, 0xFF, 0xFF) : new ArgbColor(0x12, 0x00, 0x00, 0x00),
                SubtlePressed: isDark ? new ArgbColor(0x24, 0xFF, 0xFF, 0xFF) : new ArgbColor(0x20, 0x00, 0x00, 0x00),
                RevealBorder: isDark ? new ArgbColor(0x4D, 0xFF, 0xFF, 0xFF) : new ArgbColor(0x44, 0x00, 0x00, 0x00));
        }

        private static ArgbColor WithAlpha(byte alpha, RgbColor rgb) =>
            new(alpha, rgb.R, rgb.G, rgb.B);
    }
}
