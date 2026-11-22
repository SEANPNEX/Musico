using System;
using System.Globalization;
using System.IO;

namespace MusicoWindows.Localization;

public enum AppLanguage
{
    System,
    English,
    SimplifiedChinese
}

public enum ResolvedLanguage
{
    English,
    SimplifiedChinese
}

/// <summary>
/// Manages in-app language preference persistence, system-language resolution,
/// and live switching for both XAML ResourceDictionaries and code-behind strings.
/// </summary>
public static partial class LocalizationManager
{
    public static AppLanguage CurrentMode { get; private set; } = AppLanguage.System;
    public static ResolvedLanguage CurrentLanguage { get; private set; } = ResolvedLanguage.English;

    public static event Action<ResolvedLanguage>? LanguageChanged;

    public static string DefaultPreferenceFilePath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Musico", "language.txt");

    public static ResolvedLanguage ResolveLanguage(AppLanguage mode, CultureInfo? systemCulture = null) => mode switch
    {
        AppLanguage.SimplifiedChinese => ResolvedLanguage.SimplifiedChinese,
        AppLanguage.English => ResolvedLanguage.English,
        _ => (systemCulture ?? CultureInfo.CurrentUICulture).TwoLetterISOLanguageName.Equals("zh", StringComparison.OrdinalIgnoreCase)
            ? ResolvedLanguage.SimplifiedChinese
            : ResolvedLanguage.English
    };

    public static void Initialize(string? preferenceFilePath = null, CultureInfo? systemCulture = null)
    {
        CurrentMode = LoadSavedPreference(preferenceFilePath);
        ApplyLanguage(ResolveLanguage(CurrentMode, systemCulture));
    }

    public static void SetMode(AppLanguage mode, string? preferenceFilePath = null, CultureInfo? systemCulture = null)
    {
        CurrentMode = mode;
        SavePreference(mode, preferenceFilePath);
        ApplyLanguage(ResolveLanguage(mode, systemCulture));
    }

    public static void ApplyLanguage(ResolvedLanguage language)
    {
        CurrentLanguage = language;
        L.SetLanguage(language);
        ApplyPlatformDictionary(language);
        LanguageChanged?.Invoke(language);
    }

    public static AppLanguage LoadSavedPreference(string? preferenceFilePath = null)
    {
        string path = string.IsNullOrWhiteSpace(preferenceFilePath) ? DefaultPreferenceFilePath : preferenceFilePath;
        try
        {
            if (File.Exists(path))
            {
                var text = File.ReadAllText(path).Trim();
                if (Enum.TryParse<AppLanguage>(text, ignoreCase: true, out var mode))
                    return mode;
            }
        }
        catch
        {
            // Ignore
        }
        return AppLanguage.System;
    }

    public static void SavePreference(AppLanguage mode, string? preferenceFilePath = null)
    {
        string path = string.IsNullOrWhiteSpace(preferenceFilePath) ? DefaultPreferenceFilePath : preferenceFilePath;
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

    static partial void ApplyPlatformDictionary(ResolvedLanguage language);
}
