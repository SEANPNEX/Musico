using System;
using System.Globalization;
using System.Linq;
using System.Windows;

namespace MusicoWindows.Localization;

public static partial class LocalizationManager
{
    private const string EnResourceUri = "pack://application:,,,/MusicoWindows;component/Localization/Strings.en.xaml";
    private const string ZhHansResourceUri = "pack://application:,,,/MusicoWindows;component/Localization/Strings.zh-Hans.xaml";

    private static ResourceDictionary? _currentStringsDict;

    public static void Initialize(Application app, string? preferenceFilePath = null, CultureInfo? systemCulture = null)
    {
        CurrentMode = LoadSavedPreference(preferenceFilePath);
        ApplyLanguage(app, ResolveLanguage(CurrentMode, systemCulture));
    }

    public static void SetMode(Application app, AppLanguage mode, string? preferenceFilePath = null, CultureInfo? systemCulture = null)
    {
        CurrentMode = mode;
        SavePreference(mode, preferenceFilePath);
        ApplyLanguage(app, ResolveLanguage(mode, systemCulture));
    }

    public static void ApplyLanguage(Application app, ResolvedLanguage language)
    {
        CurrentLanguage = language;
        L.SetLanguage(language);
        SwapStringDictionary(app, language);
        LanguageChanged?.Invoke(language);
    }

    static partial void ApplyPlatformDictionary(ResolvedLanguage language)
    {
        if (Application.Current is { } app)
        {
            SwapStringDictionary(app, language);
        }
    }

    private static void SwapStringDictionary(Application app, ResolvedLanguage language)
    {
        var uri = new Uri(language == ResolvedLanguage.SimplifiedChinese ? ZhHansResourceUri : EnResourceUri, UriKind.Absolute);
        var newDict = new ResourceDictionary { Source = uri };

        var merged = app.Resources.MergedDictionaries;
        if (_currentStringsDict != null)
        {
            merged.Remove(_currentStringsDict);
        }
        else
        {
            var existing = merged.FirstOrDefault(d =>
                d.Source != null &&
                (d.Source.OriginalString.EndsWith("Strings.en.xaml", StringComparison.OrdinalIgnoreCase) ||
                 d.Source.OriginalString.EndsWith("Strings.zh-Hans.xaml", StringComparison.OrdinalIgnoreCase)));
            if (existing != null)
                merged.Remove(existing);
        }

        merged.Add(newDict);
        _currentStringsDict = newDict;
    }
}
