using System.Globalization;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using MusicoWindows.Core;
using MusicoWindows.Localization;
using MusicoWindows.Models;

namespace MusicoWindows.Tests;

public sealed class LocalizationTests
{
    private static string FindMusicoWindowsProjectRoot()
    {
        string? dir = AppContext.BaseDirectory;
        while (!string.IsNullOrEmpty(dir))
        {
            string candidate = Path.Combine(dir, "MusicoWindows", "Localization", "Strings.en.xaml");
            if (File.Exists(candidate))
                return Path.Combine(dir, "MusicoWindows");
            dir = Path.GetDirectoryName(dir);
        }

        throw new DirectoryNotFoundException($"Could not locate MusicoWindows project root from {AppContext.BaseDirectory}");
    }

    private static string FindLocalizationFile(string fileName) =>
        Path.Combine(FindMusicoWindowsProjectRoot(), "Localization", fileName);

    private static Dictionary<string, string> LoadXamlStringDictionary(string fileName)
    {
        string path = FindLocalizationFile(fileName);
        var doc = XDocument.Load(path);
        XNamespace xNs = "http://schemas.microsoft.com/winfx/2006/xaml";
        XNamespace sysNs = "clr-namespace:System;assembly=mscorlib";

        var dict = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var el in doc.Root!.Elements(sysNs + "String"))
        {
            string? key = el.Attribute(xNs + "Key")?.Value;
            Assert.False(string.IsNullOrWhiteSpace(key), $"Found empty x:Key in {fileName}");
            Assert.True(dict.TryAdd(key!, el.Value), $"Duplicate key '{key}' in {fileName}");
        }
        return dict;
    }

    [Fact]
    public void DefaultPreferenceIsSystemWhenFileMissingOrCorrupt()
    {
        string dir = TestHelpers.MakeTempDir();
        string missingPath = Path.Combine(dir, "language.txt");
        Assert.Equal(AppLanguage.System, LocalizationManager.LoadSavedPreference(missingPath));

        File.WriteAllText(missingPath, "invalid-value");
        Assert.Equal(AppLanguage.System, LocalizationManager.LoadSavedPreference(missingPath));
    }

    [Theory]
    [InlineData(AppLanguage.System, "zh-CN", ResolvedLanguage.SimplifiedChinese)]
    [InlineData(AppLanguage.System, "zh-Hans", ResolvedLanguage.SimplifiedChinese)]
    [InlineData(AppLanguage.System, "zh-Hans-CN", ResolvedLanguage.SimplifiedChinese)]
    [InlineData(AppLanguage.System, "en-US", ResolvedLanguage.English)]
    [InlineData(AppLanguage.System, "ja-JP", ResolvedLanguage.English)]
    [InlineData(AppLanguage.English, "zh-CN", ResolvedLanguage.English)]
    [InlineData(AppLanguage.SimplifiedChinese, "en-US", ResolvedLanguage.SimplifiedChinese)]
    [InlineData(AppLanguage.SimplifiedChinese, "ja-JP", ResolvedLanguage.SimplifiedChinese)]
    public void ResolveLanguageHandlesSystemAndExplicitModes(
        AppLanguage mode,
        string cultureName,
        ResolvedLanguage expected)
    {
        var culture = CultureInfo.GetCultureInfo(cultureName);
        Assert.Equal(expected, LocalizationManager.ResolveLanguage(mode, culture));
    }

    [Fact]
    public void PreferencePersistenceRoundTripAndLanguageChangedEventWork()
    {
        string dir = TestHelpers.MakeTempDir();
        string prefPath = Path.Combine(dir, "nested", "language.txt");
        var previousMode = LocalizationManager.CurrentMode;
        var previousLang = LocalizationManager.CurrentLanguage;

        var events = new List<ResolvedLanguage>();
        void Handler(ResolvedLanguage lang) => events.Add(lang);
        LocalizationManager.LanguageChanged += Handler;

        try
        {
            LocalizationManager.SetMode(AppLanguage.SimplifiedChinese, prefPath, CultureInfo.GetCultureInfo("en-US"));
            Assert.Equal(AppLanguage.SimplifiedChinese, LocalizationManager.CurrentMode);
            Assert.Equal(ResolvedLanguage.SimplifiedChinese, LocalizationManager.CurrentLanguage);
            Assert.Equal(AppLanguage.SimplifiedChinese, LocalizationManager.LoadSavedPreference(prefPath));
            Assert.Equal("歌曲", L.Get("Nav_Songs"));
            Assert.Equal("名称 (A-Z)", TrackSorter.Label(TrackSortMode.NameAscending));

            LocalizationManager.SetMode(AppLanguage.English, prefPath, CultureInfo.GetCultureInfo("zh-CN"));
            Assert.Equal(AppLanguage.English, LocalizationManager.CurrentMode);
            Assert.Equal(ResolvedLanguage.English, LocalizationManager.CurrentLanguage);
            Assert.Equal(AppLanguage.English, LocalizationManager.LoadSavedPreference(prefPath));
            Assert.Equal("Songs", L.Get("Nav_Songs"));

            LocalizationManager.SetMode(AppLanguage.System, prefPath, CultureInfo.GetCultureInfo("zh-Hans"));
            Assert.Equal(AppLanguage.System, LocalizationManager.CurrentMode);
            Assert.Equal(ResolvedLanguage.SimplifiedChinese, LocalizationManager.CurrentLanguage);
            Assert.Equal(AppLanguage.System, LocalizationManager.LoadSavedPreference(prefPath));

            // Simulate fresh startup reading persisted preference under en-US culture
            LocalizationManager.Initialize(prefPath, CultureInfo.GetCultureInfo("en-US"));
            Assert.Equal(AppLanguage.System, LocalizationManager.CurrentMode);
            Assert.Equal(ResolvedLanguage.English, LocalizationManager.CurrentLanguage);

            Assert.Equal(
                [
                    ResolvedLanguage.SimplifiedChinese,
                    ResolvedLanguage.English,
                    ResolvedLanguage.SimplifiedChinese,
                    ResolvedLanguage.English
                ],
                events);
        }
        finally
        {
            LocalizationManager.LanguageChanged -= Handler;
            LocalizationManager.ApplyLanguage(previousLang);
            if (previousMode != LocalizationManager.CurrentMode)
            {
                L.SetLanguage(previousLang);
            }
        }
    }

    [Fact]
    public void CodeBehindDictionariesHaveFullKeyParityAndNonEmptyValues()
    {
        var en = L.English;
        var zh = L.SimplifiedChinese;

        Assert.NotEmpty(en);
        Assert.Equal(en.Count, zh.Count);

        var missingInZh = en.Keys.Except(zh.Keys).ToList();
        var extraInZh = zh.Keys.Except(en.Keys).ToList();
        Assert.Empty(missingInZh);
        Assert.Empty(extraInZh);

        foreach (var kvp in en)
        {
            Assert.False(string.IsNullOrWhiteSpace(kvp.Value), $"English value for '{kvp.Key}' is empty");
            Assert.True(zh.TryGetValue(kvp.Key, out var zhVal));
            Assert.False(string.IsNullOrWhiteSpace(zhVal), $"Simplified Chinese value for '{kvp.Key}' is empty");
        }
    }

    [Fact]
    public void XamlStringDictionariesHaveFullKeyParityAndNonEmptyValues()
    {
        var en = LoadXamlStringDictionary("Strings.en.xaml");
        var zh = LoadXamlStringDictionary("Strings.zh-Hans.xaml");

        Assert.NotEmpty(en);
        Assert.Equal(en.Count, zh.Count);

        var missingInZh = en.Keys.Except(zh.Keys).ToList();
        var extraInZh = zh.Keys.Except(en.Keys).ToList();
        Assert.Empty(missingInZh);
        Assert.Empty(extraInZh);

        foreach (var kvp in en)
        {
            Assert.False(string.IsNullOrWhiteSpace(kvp.Value), $"Strings.en.xaml value for '{kvp.Key}' is empty");
            Assert.True(zh.TryGetValue(kvp.Key, out var zhVal));
            Assert.False(string.IsNullOrWhiteSpace(zhVal), $"Strings.zh-Hans.xaml value for '{kvp.Key}' is empty");
        }
    }

    [Fact]
    public void AllReferencedXamlAndCodeBehindKeysExistInDictionaries()
    {
        string root = FindMusicoWindowsProjectRoot();
        var xamlKeys = LoadXamlStringDictionary("Strings.en.xaml");

        var xamlRegex = new Regex(@"\{DynamicResource\s+(Str_[A-Za-z0-9_]+)\}", RegexOptions.Compiled);
        foreach (string xamlFile in Directory.EnumerateFiles(root, "*.xaml", SearchOption.AllDirectories))
        {
            if (xamlFile.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}", StringComparison.Ordinal) ||
                xamlFile.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}", StringComparison.Ordinal))
            {
                continue;
            }
            string text = File.ReadAllText(xamlFile);
            foreach (Match m in xamlRegex.Matches(text))
            {
                string key = m.Groups[1].Value;
                Assert.True(xamlKeys.ContainsKey(key), $"Missing XAML key '{key}' referenced in {Path.GetFileName(xamlFile)}");
            }
        }

        var csRegex = new Regex(@"\bL\.(?:Get|Format)\(\s*""([^""]+)""", RegexOptions.Compiled);
        foreach (string csFile in Directory.EnumerateFiles(root, "*.cs", SearchOption.AllDirectories))
        {
            if (csFile.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}", StringComparison.Ordinal) ||
                csFile.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}", StringComparison.Ordinal))
            {
                continue;
            }
            string text = File.ReadAllText(csFile);
            foreach (Match m in csRegex.Matches(text))
            {
                string key = m.Groups[1].Value;
                Assert.True(L.English.ContainsKey(key), $"Missing English key '{key}' referenced in {Path.GetFileName(csFile)}");
                Assert.True(L.SimplifiedChinese.ContainsKey(key), $"Missing Simplified Chinese key '{key}' referenced in {Path.GetFileName(csFile)}");
            }
        }
    }
}
