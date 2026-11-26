using System;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media;
using Microsoft.Win32;
using MusicoWindows.Core;
using MusicoWindows.Models;

namespace MusicoWindows.Theme;

/// <summary>
/// Manages Windows 10 Light/Dark theme synchronization via HKCU Personalize\AppsUseLightTheme,
/// custom appearance presets/colors/opacity/background image via <see cref="AppearanceStore"/>,
/// live overlay ResourceDictionaries, and Acrylic window backdrop.
/// </summary>
public static class ThemeManager
{
    private const string PersonalizeKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";
    private const string AppsUseLightThemeValue = "AppsUseLightTheme";
    private const string LightResourceUri = "pack://application:,,,/MusicoWindows;component/Theme/Win10Light.xaml";
    private const string DarkResourceUri = "pack://application:,,,/MusicoWindows;component/Theme/Win10Dark.xaml";

    private static ResourceDictionary? _currentThemeDict;
    private static ResourceDictionary? _overlayThemeDict;
    private static bool _listeningToSystem;
    private static AppearanceStore? _store;

    public static AppearanceSettings CurrentSettings { get; private set; } = new();
    public static ThemePalette? CurrentPalette { get; private set; }
    public static AppThemeMode CurrentMode => CurrentSettings.ThemeMode;
    public static bool IsDark { get; private set; } = true;

    public static event Action<bool>? ThemeChanged;
    public static event Action<AppearanceSettings>? AppearanceChanged;

    public static void Initialize(Application app, string? stateDirectoryOrPreferencePath = null)
    {
        _store = CreateStore(stateDirectoryOrPreferencePath);
        AppearanceSettings loaded = _store.Load();
        ApplyAppearance(app, loaded, save: false);

        if (!_listeningToSystem && OperatingSystem.IsWindows())
        {
            try
            {
                SystemEvents.UserPreferenceChanged += (_, e) =>
                {
                    if (e.Category == UserPreferenceCategory.General &&
                        CurrentSettings.ThemeMode == AppThemeMode.System &&
                        AppearancePresets.ResolveBackgroundColor(CurrentSettings) is null)
                    {
                        app.Dispatcher.BeginInvoke(() =>
                        {
                            ApplyAppearance(app, CurrentSettings, save: false);
                        });
                    }
                };
                _listeningToSystem = true;
            }
            catch
            {
                // Ignore if system preference notifications are unavailable in this Windows session.
            }
        }
    }

    public static void ToggleTheme(Application app)
    {
        bool nextDark = !IsDark;
        SetMode(app, nextDark ? AppThemeMode.Dark : AppThemeMode.Light);
    }

    public static void SetMode(Application app, AppThemeMode mode)
    {
        ApplyAppearance(app, CurrentSettings with { ThemeMode = mode }, save: true);
    }

    public static void SetBackgroundPreset(Application app, BackgroundColorPreset preset)
    {
        AppearanceSettings next = CurrentSettings with { BackgroundPreset = preset };
        if (preset == BackgroundColorPreset.Custom && string.IsNullOrWhiteSpace(next.CustomBackgroundHex))
        {
            next = next with { CustomBackgroundHex = IsDark ? "#171717" : "#F8F6F1" };
        }
        ApplyAppearance(app, next, save: true);
    }

    public static void SetCustomBackgroundHex(Application app, string? hex)
    {
        string? normalized = ColorMath.NormalizeHexOrNull(hex);
        AppearanceSettings next = normalized is not null
            ? CurrentSettings with
            {
                BackgroundPreset = BackgroundColorPreset.Custom,
                CustomBackgroundHex = normalized
            }
            : CurrentSettings with
            {
                CustomBackgroundHex = null
            };
        ApplyAppearance(app, next, save: true);
    }

    public static void SetForegroundPreset(Application app, ForegroundColorPreset preset)
    {
        AppearanceSettings next = CurrentSettings with { ForegroundPreset = preset };
        if (preset == ForegroundColorPreset.Custom && string.IsNullOrWhiteSpace(next.CustomAccentHex))
        {
            next = next with { CustomAccentHex = ColorMath.ToHex(AppearancePresets.ResolveAccentColor(CurrentSettings)) };
        }
        ApplyAppearance(app, next, save: true);
    }

    public static void SetCustomAccentHex(Application app, string? hex)
    {
        string? normalized = ColorMath.NormalizeHexOrNull(hex);
        AppearanceSettings next = normalized is not null
            ? CurrentSettings with
            {
                ForegroundPreset = ForegroundColorPreset.Custom,
                CustomAccentHex = normalized
            }
            : CurrentSettings with
            {
                CustomAccentHex = null
            };
        ApplyAppearance(app, next, save: true);
    }

    public static void SetCustomForegroundHex(Application app, string? hex)
    {
        string? normalized = ColorMath.NormalizeHexOrNull(hex);
        AppearanceSettings next = CurrentSettings with
        {
            ForegroundPreset = normalized is not null ? ForegroundColorPreset.Custom : CurrentSettings.ForegroundPreset,
            CustomForegroundHex = normalized
        };
        if (next.ForegroundPreset == ForegroundColorPreset.Custom && string.IsNullOrWhiteSpace(next.CustomAccentHex))
        {
            next = next with { CustomAccentHex = ColorMath.ToHex(AppearancePresets.ResolveAccentColor(CurrentSettings)) };
        }
        ApplyAppearance(app, next, save: true);
    }

    public static void SetBackgroundOpacity(Application app, double opacity)
    {
        ApplyAppearance(
            app,
            CurrentSettings with { BackgroundOpacity = AppearanceSettings.ClampBackgroundOpacity(opacity) },
            save: true);
    }

    public static void SetBackgroundImageOpacity(Application app, double opacity)
    {
        ApplyAppearance(
            app,
            CurrentSettings with { BackgroundImageOpacity = AppearanceSettings.ClampBackgroundImageOpacity(opacity) },
            save: true);
    }

    public static void SetBackgroundImageBlur(Application app, double blur)
    {
        ApplyAppearance(
            app,
            CurrentSettings with { BackgroundImageBlur = AppearanceSettings.ClampBackgroundImageBlur(blur) },
            save: true);
    }

    public static void SetLyricsFontSize(Application app, LyricsFontSize fontSize)
    {
        ApplyAppearance(
            app,
            CurrentSettings with { LyricsFontSize = fontSize },
            save: true);
    }

    public static void SetLyricsAlignment(Application app, LyricsAlignment alignment)
    {
        ApplyAppearance(
            app,
            CurrentSettings with { LyricsAlignment = alignment },
            save: true);
    }

    public static void ApplyContrastFix(Application app, string? fixedForegroundHex, string? fixedAccentHex)
    {
        var next = CurrentSettings;
        if (!string.IsNullOrWhiteSpace(fixedForegroundHex) || !string.IsNullOrWhiteSpace(fixedAccentHex))
        {
            next = next with
            {
                ForegroundPreset = ForegroundColorPreset.Custom,
                CustomAccentHex = fixedAccentHex ?? ColorMath.ToHex(AppearancePresets.ResolveAccentColor(CurrentSettings)),
                CustomForegroundHex = fixedForegroundHex ?? next.CustomForegroundHex
            };
        }
        ApplyAppearance(app, next, save: true);
    }

    public static void ImportBackgroundImage(Application app, string sourceFilePath)
    {
        string fileName = EnsureStore().ImportBackgroundImage(sourceFilePath);
        ApplyAppearance(app, CurrentSettings with { BackgroundImageFileName = fileName }, save: true);
    }

    public static void ClearBackgroundImage(Application app)
    {
        EnsureStore().ClearBackgroundImage();
        ApplyAppearance(app, CurrentSettings with { BackgroundImageFileName = null }, save: true);
    }

    public static void ResetToDefaults(Application app)
    {
        EnsureStore().ClearBackgroundImage();
        ApplyAppearance(app, new AppearanceSettings(), save: true);
    }

    public static string? ResolveBackgroundImagePath() =>
        _store?.ResolveBackgroundImagePath(CurrentSettings);

    public static void ApplyBackdrop(Window window)
    {
        ThemePalette palette = CurrentPalette ?? ColorMath.BuildPalette(CurrentSettings, ReadWindowsAppsUseLightTheme(), false);
        Color tintColor = Color.FromRgb(palette.AcrylicTintBase.R, palette.AcrylicTintBase.G, palette.AcrylicTintBase.B);
        bool hasCustomBg = AppearancePresets.ResolveBackgroundColor(CurrentSettings) is not null;
        bool hasBgImage = !string.IsNullOrEmpty(ResolveBackgroundImagePath());
        AcrylicBackdrop.Apply(
            window,
            palette.IsDark,
            tintColor,
            CurrentSettings.BackgroundOpacity,
            hasCustomBg,
            hasBgImage);
    }

    public static bool ReadWindowsAppsUseLightTheme()
    {
        if (!OperatingSystem.IsWindows())
            return false;

        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(PersonalizeKeyPath);
            if (key?.GetValue(AppsUseLightThemeValue) is int value)
            {
                return value != 0;
            }
        }
        catch
        {
            // Ignore registry read failure
        }

        return false; // Default to Groove Dark if key is missing
    }

    private static AppearanceStore EnsureStore() => _store ??= new AppearanceStore();

    private static AppearanceStore CreateStore(string? stateDirectoryOrPreferencePath)
    {
        if (string.IsNullOrWhiteSpace(stateDirectoryOrPreferencePath))
            return new AppearanceStore();

        string trimmed = stateDirectoryOrPreferencePath.Trim();
        if (Directory.Exists(trimmed))
            return new AppearanceStore(trimmed);

        string ext = Path.GetExtension(trimmed);
        if (string.Equals(ext, ".txt", StringComparison.OrdinalIgnoreCase))
        {
            string? dir = Path.GetDirectoryName(trimmed);
            return new AppearanceStore(string.IsNullOrWhiteSpace(dir) ? Persistence.AppDataDirectory : dir, trimmed);
        }

        if (string.Equals(ext, ".json", StringComparison.OrdinalIgnoreCase))
        {
            string? dir = Path.GetDirectoryName(trimmed);
            return new AppearanceStore(string.IsNullOrWhiteSpace(dir) ? Persistence.AppDataDirectory : dir);
        }

        return new AppearanceStore(trimmed);
    }

    private static void ApplyAppearance(Application app, AppearanceSettings settings, bool save)
    {
        AppearanceStore store = EnsureStore();
        AppearanceSettings resolved = save ? store.Save(settings) : settings.Normalize();
        CurrentSettings = resolved;

        bool hasBgImage = !string.IsNullOrEmpty(store.ResolveBackgroundImagePath(resolved));
        ThemePalette palette = ColorMath.BuildPalette(resolved, ReadWindowsAppsUseLightTheme(), hasBgImage);
        CurrentPalette = palette;
        IsDark = palette.IsDark;

        var uri = new Uri(palette.IsDark ? DarkResourceUri : LightResourceUri, UriKind.Absolute);
        var baseDict = new ResourceDictionary { Source = uri };
        var overlayDict = BuildOverlayDictionary(palette);

        var merged = app.Resources.MergedDictionaries;
        if (_overlayThemeDict != null)
        {
            merged.Remove(_overlayThemeDict);
            _overlayThemeDict = null;
        }

        if (_currentThemeDict != null)
        {
            merged.Remove(_currentThemeDict);
        }
        else
        {
            var existing = merged.FirstOrDefault(d =>
                d.Source != null &&
                (d.Source.OriginalString.EndsWith("Win10Light.xaml", StringComparison.OrdinalIgnoreCase) ||
                 d.Source.OriginalString.EndsWith("Win10Dark.xaml", StringComparison.OrdinalIgnoreCase)));
            if (existing != null)
                merged.Remove(existing);
        }

        merged.Insert(0, baseDict);
        merged.Insert(1, overlayDict);
        _currentThemeDict = baseDict;
        _overlayThemeDict = overlayDict;

        if (app.MainWindow is { IsLoaded: true } win)
        {
            ApplyBackdrop(win);
        }

        ThemeChanged?.Invoke(palette.IsDark);
        AppearanceChanged?.Invoke(resolved);
    }

    private static ResourceDictionary BuildOverlayDictionary(ThemePalette p)
    {
        var dict = new ResourceDictionary();

        dict["AccentColor"] = ToMediaColor(p.Accent);
        dict["AccentHoverColor"] = ToMediaColor(p.AccentHover);
        dict["AccentPressedColor"] = ToMediaColor(p.AccentPressed);
        dict["AccentSubtleColor"] = ToMediaColor(p.AccentSubtle);

        dict["AccentBrush"] = CreateFrozenBrush(p.Accent);
        dict["AccentHoverBrush"] = CreateFrozenBrush(p.AccentHover);
        dict["AccentPressedBrush"] = CreateFrozenBrush(p.AccentPressed);
        dict["AccentSubtleBrush"] = CreateFrozenBrush(p.AccentSubtle);
        dict["TextOnAccentBrush"] = CreateFrozenBrush(p.TextOnAccent);

        dict["SliderThumbBorderBrush"] = CreateFrozenBrush(p.Accent);
        dict["SliderTrackBrush"] = CreateFrozenBrush(p.SliderTrack);
        dict["SliderThumbFillBrush"] = CreateFrozenBrush(p.SliderThumbFill);

        dict["WindowBackgroundBrush"] = CreateFrozenBrush(p.WindowBackground);
        dict["NavPaneBrush"] = CreateFrozenBrush(p.NavPane);
        dict["ContentBackgroundBrush"] = CreateFrozenBrush(p.ContentBackground);
        dict["PlayerBarBrush"] = CreateFrozenBrush(p.PlayerBar);
        dict["SidePanelBrush"] = CreateFrozenBrush(p.SidePanel);

        dict["CardBackgroundBrush"] = CreateFrozenBrush(p.CardBackground);
        dict["CardHoverBrush"] = CreateFrozenBrush(p.CardHover);
        dict["DialogSurfaceBrush"] = CreateFrozenBrush(p.DialogSurface);
        dict["MenuSurfaceBrush"] = CreateFrozenBrush(p.MenuSurface);

        dict["ControlFillBrush"] = CreateFrozenBrush(p.ControlFill);
        dict["ControlHoverBrush"] = CreateFrozenBrush(p.ControlHover);
        dict["ControlPressedBrush"] = CreateFrozenBrush(p.ControlPressed);
        dict["ControlBorderBrush"] = CreateFrozenBrush(p.ControlBorder);
        dict["DividerBrush"] = CreateFrozenBrush(p.Divider);

        dict["TextPrimaryBrush"] = CreateFrozenBrush(p.TextPrimary);
        dict["TextSecondaryBrush"] = CreateFrozenBrush(p.TextSecondary);
        dict["TextTertiaryBrush"] = CreateFrozenBrush(p.TextTertiary);

        dict["SubtleHoverBrush"] = CreateFrozenBrush(p.SubtleHover);
        dict["SubtlePressedBrush"] = CreateFrozenBrush(p.SubtlePressed);
        dict["RevealBorderBrush"] = CreateFrozenBrush(p.RevealBorder);

        return dict;
    }

    private static Color ToMediaColor(RgbColor c) => Color.FromRgb(c.R, c.G, c.B);
    private static Color ToMediaColor(ArgbColor c) => Color.FromArgb(c.A, c.R, c.G, c.B);

    private static SolidColorBrush CreateFrozenBrush(RgbColor c)
    {
        var brush = new SolidColorBrush(ToMediaColor(c));
        brush.Freeze();
        return brush;
    }

    private static SolidColorBrush CreateFrozenBrush(ArgbColor c)
    {
        var brush = new SolidColorBrush(ToMediaColor(c));
        brush.Freeze();
        return brush;
    }
}
