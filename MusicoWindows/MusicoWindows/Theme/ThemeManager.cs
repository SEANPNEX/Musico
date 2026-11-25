using System;
using System.IO;
using System.Linq;
using System.Windows;
using Microsoft.Win32;

namespace MusicoWindows.Theme;

public enum AppThemeMode
{
    System,
    Light,
    Dark
}

/// <summary>
/// Manages Windows 10 Light/Dark theme synchronization via HKCU Personalize\AppsUseLightTheme
/// and in-app theme overrides, updating ResourceDictionaries and Acrylic window backdrop.
/// </summary>
public static class ThemeManager
{
    private const string PersonalizeKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";
    private const string AppsUseLightThemeValue = "AppsUseLightTheme";
    private static readonly Uri LightUri = new("pack://application:,,,/MusicoWindows;component/Theme/Win10Light.xaml", UriKind.Absolute);
    private static readonly Uri DarkUri = new("pack://application:,,,/MusicoWindows;component/Theme/Win10Dark.xaml", UriKind.Absolute);

    private static ResourceDictionary? _currentThemeDict;
    private static bool _listeningToSystem;
    private static string? _preferenceFilePathOverride;

    public static AppThemeMode CurrentMode { get; private set; } = AppThemeMode.System;
    public static bool IsDark { get; private set; } = true;

    public static event Action<bool>? ThemeChanged;

    private static string PreferenceFilePath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Musico", "theme.txt");

    public static void Initialize(Application app, string? preferenceFilePath = null)
    {
        _preferenceFilePathOverride = string.IsNullOrWhiteSpace(preferenceFilePath) ? null : preferenceFilePath;
        CurrentMode = LoadSavedPreference(preferenceFilePath);
        ApplyTheme(app, ResolveIsDark(CurrentMode));

        if (!_listeningToSystem && OperatingSystem.IsWindows())
        {
            try
            {
                SystemEvents.UserPreferenceChanged += (_, e) =>
                {
                    if (e.Category == UserPreferenceCategory.General && CurrentMode == AppThemeMode.System)
                    {
                        app.Dispatcher.BeginInvoke(() =>
                        {
                            ApplyTheme(app, ResolveIsDark(AppThemeMode.System));
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
        // Toggle directly between Dark and Light (explicit override)
        bool nextDark = !IsDark;
        SetMode(app, nextDark ? AppThemeMode.Dark : AppThemeMode.Light);
    }

    public static void SetMode(Application app, AppThemeMode mode)
    {
        CurrentMode = mode;
        SavePreference(mode);
        ApplyTheme(app, ResolveIsDark(mode));
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

    private static bool ResolveIsDark(AppThemeMode mode) => mode switch
    {
        AppThemeMode.Light => false,
        AppThemeMode.Dark => true,
        _ => !ReadWindowsAppsUseLightTheme()
    };

    private static void ApplyTheme(Application app, bool isDark)
    {
        IsDark = isDark;
        var uri = isDark ? DarkUri : LightUri;
        var newDict = new ResourceDictionary { Source = uri };

        var merged = app.Resources.MergedDictionaries;
        if (_currentThemeDict != null)
        {
            merged.Remove(_currentThemeDict);
        }
        else
        {
            // Remove any initial Light/Dark dictionary declared in App.xaml
            var existing = merged.FirstOrDefault(d =>
                d.Source != null &&
                (d.Source.OriginalString.EndsWith("Win10Light.xaml", StringComparison.OrdinalIgnoreCase) ||
                 d.Source.OriginalString.EndsWith("Win10Dark.xaml", StringComparison.OrdinalIgnoreCase)));
            if (existing != null)
                merged.Remove(existing);
        }

        merged.Insert(0, newDict);
        _currentThemeDict = newDict;

        if (app.MainWindow is { IsLoaded: true } win)
        {
            AcrylicBackdrop.Apply(win, isDark);
        }

        ThemeChanged?.Invoke(isDark);
    }

    private static AppThemeMode LoadSavedPreference(string? preferenceFilePath = null)
    {
        string path = string.IsNullOrWhiteSpace(preferenceFilePath)
            ? (_preferenceFilePathOverride ?? PreferenceFilePath)
            : preferenceFilePath;
        try
        {
            if (File.Exists(path))
            {
                var text = File.ReadAllText(path).Trim();
                if (Enum.TryParse<AppThemeMode>(text, ignoreCase: true, out var mode))
                    return mode;
            }
        }
        catch
        {
            // Ignore
        }
        return AppThemeMode.System;
    }

    private static void SavePreference(AppThemeMode mode)
    {
        string path = _preferenceFilePathOverride ?? PreferenceFilePath;
        try
        {
            var dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(dir))
                Directory.CreateDirectory(dir);
            File.WriteAllText(path, mode.ToString());
        }
        catch
        {
            // Ignore
        }
    }
}
