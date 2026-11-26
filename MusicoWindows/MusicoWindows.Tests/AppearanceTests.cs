using MusicoWindows.Core;
using MusicoWindows.Models;
using MusicoWindows.Theme;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class AppearanceTests : IDisposable
{
    private readonly List<string> _tempDirs = new();

    public void Dispose()
    {
        foreach (string dir in _tempDirs)
        {
            try
            {
                if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
            }
            catch
            {
                // Best-effort cleanup.
            }
        }
    }

    private string NewTempDir()
    {
        string dir = TestHelpers.MakeTempDir();
        _tempDirs.Add(dir);
        return dir;
    }

    [Theory]
    [InlineData(BackgroundColorPreset.Default, null)]
    [InlineData(BackgroundColorPreset.Obsidian, "#09090B")]
    [InlineData(BackgroundColorPreset.Midnight, "#0F172A")]
    [InlineData(BackgroundColorPreset.WarmCharcoal, "#1C1917")]
    [InlineData(BackgroundColorPreset.ForestNight, "#0F1D17")]
    [InlineData(BackgroundColorPreset.Paper, "#F8F6F1")]
    [InlineData(BackgroundColorPreset.Mist, "#EEF3F8")]
    [InlineData(BackgroundColorPreset.Blossom, "#FCF2F4")]
    [InlineData(BackgroundColorPreset.Custom, null)]
    public void BackgroundPresetsMatchSharedSpec(BackgroundColorPreset preset, string? expectedHex)
    {
        Assert.Equal(expectedHex, AppearancePresets.GetBackgroundHex(preset));
    }

    [Theory]
    [InlineData(ForegroundColorPreset.Coral, "#E85440")]
    [InlineData(ForegroundColorPreset.Ocean, "#2563EB")]
    [InlineData(ForegroundColorPreset.Emerald, "#10B981")]
    [InlineData(ForegroundColorPreset.Amethyst, "#8B5CF6")]
    [InlineData(ForegroundColorPreset.Amber, "#F59E0B")]
    [InlineData(ForegroundColorPreset.Rose, "#F43F5E")]
    [InlineData(ForegroundColorPreset.Monochrome, "#71717A")]
    public void ForegroundAccentPresetsMatchSharedSpec(ForegroundColorPreset preset, string expectedHex)
    {
        Assert.Equal(expectedHex, AppearancePresets.GetForegroundAccentHex(preset));
    }

    [Theory]
    [InlineData("#09090b", true, "#09090B", 9, 9, 11)]
    [InlineData("  #2563eb ", true, "#2563EB", 37, 99, 235)]
    [InlineData("F8F6F1", true, "#F8F6F1", 248, 246, 241)]
    [InlineData("#12345", false, null, 0, 0, 0)]
    [InlineData("#GGGGGG", false, null, 0, 0, 0)]
    [InlineData("", false, null, 0, 0, 0)]
    [InlineData(null, false, null, 0, 0, 0)]
    public void HexParsingAndFormattingWorks(
        string? input,
        bool expectedValid,
        string? expectedNormalized,
        byte r,
        byte g,
        byte b)
    {
        bool ok = ColorMath.TryParseHex(input, out RgbColor rgb);
        Assert.Equal(expectedValid, ok);
        Assert.Equal(expectedNormalized, ColorMath.NormalizeHexOrNull(input));
        if (expectedValid)
        {
            Assert.Equal(new RgbColor(r, g, b), rgb);
            Assert.Equal(expectedNormalized, ColorMath.ToHex(rgb));
        }
    }

    [Fact]
    public void LuminanceAndAdaptiveContrastPickReadableForegroundAndBaseMode()
    {
        // Dark background preset overrides Light ThemeMode to stay readable
        var darkPreset = new AppearanceSettings
        {
            ThemeMode = AppThemeMode.Light,
            BackgroundPreset = BackgroundColorPreset.Midnight
        };
        Assert.True(AppearancePresets.ResolveIsDark(darkPreset, systemAppsUseLightTheme: true));
        Assert.Equal(new RgbColor(0xF5, 0xF5, 0xF7), AppearancePresets.ResolveForegroundColor(darkPreset, effectiveIsDark: true));

        // Light background preset overrides Dark ThemeMode to stay readable
        var lightPreset = new AppearanceSettings
        {
            ThemeMode = AppThemeMode.Dark,
            BackgroundPreset = BackgroundColorPreset.Paper
        };
        Assert.False(AppearancePresets.ResolveIsDark(lightPreset, systemAppsUseLightTheme: false));
        Assert.Equal(new RgbColor(0x14, 0x14, 0x16), AppearancePresets.ResolveForegroundColor(lightPreset, effectiveIsDark: false));

        // Custom dark vs light background hex
        var customLight = new AppearanceSettings
        {
            BackgroundPreset = BackgroundColorPreset.Custom,
            CustomBackgroundHex = "#FFFDF5"
        };
        Assert.False(AppearancePresets.ResolveIsDark(customLight, systemAppsUseLightTheme: false));
        Assert.Equal(new RgbColor(0x14, 0x14, 0x16), AppearancePresets.ResolveForegroundColor(customLight, effectiveIsDark: false));

        // CustomForegroundHex applies only when ForegroundPreset is Custom
        var customText = customLight with
        {
            ForegroundPreset = ForegroundColorPreset.Custom,
            CustomAccentHex = "#10B981",
            CustomForegroundHex = "#223344"
        };
        Assert.Equal(new RgbColor(0x22, 0x33, 0x44), AppearancePresets.ResolveForegroundColor(customText, effectiveIsDark: false));
        Assert.Equal(new RgbColor(0x10, 0xB9, 0x81), AppearancePresets.ResolveAccentColor(customText));
    }

    [Fact]
    public void OpacityClampingAndAcrylicAbgrStayWithinBounds()
    {
        Assert.Equal(0.15, AppearanceSettings.ClampBackgroundOpacity(0.0));
        Assert.Equal(0.15, AppearanceSettings.ClampBackgroundOpacity(-1.0));
        Assert.Equal(1.0, AppearanceSettings.ClampBackgroundOpacity(2.5));
        Assert.Equal(1.0, AppearanceSettings.ClampBackgroundOpacity(double.NaN));
        Assert.Equal(0.62, AppearanceSettings.ClampBackgroundOpacity(0.62));

        Assert.Equal(0.15, AppearanceSettings.ClampSidebarOpacity(0.0));
        Assert.Equal(0.15, AppearanceSettings.ClampSidebarOpacity(-1.0));
        Assert.Equal(1.0, AppearanceSettings.ClampSidebarOpacity(2.5));
        Assert.Equal(1.0, AppearanceSettings.ClampSidebarOpacity(double.NaN));
        Assert.Equal(0.42, AppearanceSettings.ClampSidebarOpacity(0.42));

        Assert.Equal(0.10, AppearanceSettings.ClampBackgroundImageOpacity(0.01));
        Assert.Equal(1.0, AppearanceSettings.ClampBackgroundImageOpacity(3.0));
        Assert.Equal(0.35, AppearanceSettings.ClampBackgroundImageOpacity(double.NaN));
        Assert.Equal(0.45, AppearanceSettings.ClampBackgroundImageOpacity(0.45));

        // Verify ABGR byte order: (alpha << 24) | (B << 16) | (G << 8) | R
        uint fullDark = ColorMath.ToAcrylicAbgr(new RgbColor(0x18, 0x18, 0x18), 1.0);
        Assert.Equal(0xD2181818u, fullDark);

        // Translucent Acrylic uses minimal tint alpha (0x01) so WPF brushes own opacity/color without double-tinting
        uint minTint = ColorMath.ToAcrylicAbgr(new RgbColor(0x12, 0x34, 0x56), 0.15);
        Assert.Equal((byte)0x01, (byte)(minTint >> 24));
        Assert.Equal((byte)0x56, (byte)((minTint >> 16) & 0xFF));
        Assert.Equal((byte)0x34, (byte)((minTint >> 8) & 0xFF));
        Assert.Equal((byte)0x12, (byte)(minTint & 0xFF));

        uint midTint = ColorMath.ToAcrylicAbgr(new RgbColor(0x12, 0x34, 0x56), 0.50);
        Assert.Equal((byte)0x01, (byte)(midTint >> 24));
    }

    [Fact]
    public void BuildPaletteMakesSurfacesTranslucentWhenOpacityReducedOrImageActive()
    {
        var defaults = new AppearanceSettings();
        ThemePalette opaquePalette = ColorMath.BuildPalette(defaults, systemAppsUseLightTheme: false, hasBackgroundImage: false);
        Assert.Equal(0xFF, opaquePalette.WindowBackground.A);
        Assert.Equal(0xFF, opaquePalette.ContentBackground.A);
        Assert.Equal(0xFF, opaquePalette.NavPane.A);
        Assert.Equal(0xFF, opaquePalette.SidePanel.A);
        Assert.Equal(0xFF, opaquePalette.PlayerBar.A);
        Assert.Equal(0xFF, opaquePalette.TitleBar.A);
        Assert.Equal(0xFF, opaquePalette.CardBackground.A);
        Assert.Equal(0xFF, opaquePalette.ControlFill.A);

        var translucent = defaults with { BackgroundOpacity = 0.40 };
        ThemePalette translucentPalette = ColorMath.BuildPalette(translucent, systemAppsUseLightTheme: false, hasBackgroundImage: false);
        Assert.Equal(0x00, translucentPalette.WindowBackground.A);
        Assert.Equal(0xFF, translucentPalette.ContentBackground.A);
        Assert.Equal((byte)Math.Round(255.0 * 0.40), translucentPalette.NavPane.A);
        Assert.Equal(translucentPalette.NavPane.A, translucentPalette.SidePanel.A);
        Assert.Equal(translucentPalette.NavPane.A, translucentPalette.PlayerBar.A);
        Assert.Equal(translucentPalette.NavPane.A, translucentPalette.TitleBar.A);
        Assert.True(translucentPalette.CardBackground.A < 0xFF);
        Assert.True(translucentPalette.ControlFill.A < 0xFF);

        // CardBackground & ControlFill scale proportionally below old 0.55 floor
        var veryTranslucent = defaults with { BackgroundOpacity = 0.15 };
        ThemePalette veryTranslucentPalette = ColorMath.BuildPalette(veryTranslucent, systemAppsUseLightTheme: false, hasBackgroundImage: false);
        Assert.Equal(0x00, veryTranslucentPalette.WindowBackground.A);
        Assert.Equal(0x24, veryTranslucentPalette.CardBackground.A);
        Assert.Equal(0x38, veryTranslucentPalette.ControlFill.A);
        Assert.True(veryTranslucentPalette.CardBackground.A < translucentPalette.CardBackground.A);

        ThemePalette imagePalette = ColorMath.BuildPalette(defaults, systemAppsUseLightTheme: false, hasBackgroundImage: true);
        Assert.Equal(0x00, imagePalette.WindowBackground.A);
        Assert.Equal(0xFF, imagePalette.ContentBackground.A);
        Assert.True(imagePalette.CardBackground.A < 0xFF);
    }

    [Fact]
    public void SidebarPresetsHexAndOpacityResolveAndContrastAdapt()
    {
        var defaults = new AppearanceSettings();
        Assert.Equal(1.0, defaults.ResolveEffectiveSidebarOpacity());
        Assert.Null(AppearancePresets.ResolveSidebarBackgroundColor(defaults));
        Assert.True(AppearancePresets.ResolveSidebarIsDark(defaults, mainIsDark: true));
        Assert.Equal(
            AppearancePresets.LightTextOnDark,
            AppearancePresets.ResolveSidebarForegroundColor(defaults, mainIsDark: true, AppearancePresets.LightTextOnDark));

        // Explicit null SidebarOpacity tracks BackgroundOpacity
        var followingMain = defaults with { BackgroundOpacity = 0.45, SidebarOpacity = null };
        Assert.Equal(0.45, followingMain.ResolveEffectiveSidebarOpacity());

        // Light sidebar preset on dark main theme adapts sidebar text independently
        var lightSidebarOnDark = new AppearanceSettings
        {
            BackgroundPreset = BackgroundColorPreset.Obsidian,
            SidebarBackgroundPreset = BackgroundColorPreset.Paper,
            BackgroundOpacity = 1.0,
            SidebarOpacity = 0.30
        };
        ThemePalette mixedPalette = ColorMath.BuildPalette(lightSidebarOnDark, systemAppsUseLightTheme: false, hasBackgroundImage: false);
        Assert.True(mixedPalette.IsDark);
        Assert.False(mixedPalette.SidebarIsDark);
        Assert.Equal(0x00, mixedPalette.WindowBackground.A);
        Assert.Equal(new ArgbColor((byte)Math.Round(255.0 * 0.30), 0xF8, 0xF6, 0xF1), mixedPalette.NavPane);
        Assert.Equal(0xFF, mixedPalette.PlayerBar.A);
        Assert.Equal(AppearancePresets.LightTextOnDark, new RgbColor(mixedPalette.TextPrimary.R, mixedPalette.TextPrimary.G, mixedPalette.TextPrimary.B));
        Assert.Equal(AppearancePresets.DarkTextOnLight, new RgbColor(mixedPalette.SidebarTextPrimary.R, mixedPalette.SidebarTextPrimary.G, mixedPalette.SidebarTextPrimary.B));

        // Custom sidebar hex + dark sidebar on light main theme
        var customDarkSidebarOnLight = new AppearanceSettings
        {
            BackgroundPreset = BackgroundColorPreset.Paper,
            SidebarBackgroundPreset = BackgroundColorPreset.Custom,
            CustomSidebarBackgroundHex = "102030",
            BackgroundOpacity = 0.80,
            SidebarOpacity = 0.20
        }.Normalize();
        Assert.Equal("#102030", customDarkSidebarOnLight.CustomSidebarBackgroundHex);
        ThemePalette reversePalette = ColorMath.BuildPalette(customDarkSidebarOnLight, systemAppsUseLightTheme: true, hasBackgroundImage: false);
        Assert.False(reversePalette.IsDark);
        Assert.True(reversePalette.SidebarIsDark);
        Assert.Equal(new ArgbColor((byte)Math.Round(255.0 * 0.20), 0x10, 0x20, 0x30), reversePalette.NavPane);
        Assert.Equal((byte)Math.Round(255.0 * 0.80), reversePalette.PlayerBar.A);
        Assert.Equal(AppearancePresets.LightTextOnDark, new RgbColor(reversePalette.SidebarTextPrimary.R, reversePalette.SidebarTextPrimary.G, reversePalette.SidebarTextPrimary.B));
    }

    [Fact]
    public void ComputeEffectiveBackgroundImageOpacityAndScrimWork()
    {
        var s1 = new AppearanceSettings
        {
            BackgroundOpacity = 0.50,
            BackgroundImageOpacity = 0.40
        };
        Assert.Equal(0.20, ColorMath.ComputeEffectiveBackgroundImageOpacity(s1), precision: 5);

        // Scrim alpha is independent of BackgroundOpacity because ContentBackdropLayer.Opacity owns BackgroundOpacity
        var s2 = s1 with { BackgroundOpacity = 0.15 };
        Assert.Equal(ColorMath.ComputeBackgroundScrimAlpha(s1), ColorMath.ComputeBackgroundScrimAlpha(s2));

        // Higher image opacity lowers scrim alpha, bounded within [0.08, 0.45] * 255
        byte lowImgScrim = ColorMath.ComputeBackgroundScrimAlpha(s1 with { BackgroundImageOpacity = 0.10 });
        byte highImgScrim = ColorMath.ComputeBackgroundScrimAlpha(s1 with { BackgroundImageOpacity = 1.0 });
        Assert.True(lowImgScrim > highImgScrim);
        Assert.InRange(highImgScrim, (byte)20, (byte)115);
        Assert.InRange(lowImgScrim, (byte)20, (byte)115);
    }

    [Fact]
    public void AppearanceStoreRoundTripsAndNormalizes()
    {
        string dir = NewTempDir();
        var store = new AppearanceStore(dir);

        var raw = new AppearanceSettings
        {
            ThemeMode = AppThemeMode.Dark,
            BackgroundPreset = BackgroundColorPreset.Custom,
            CustomBackgroundHex = "1e293b",
            SidebarBackgroundPreset = BackgroundColorPreset.Custom,
            CustomSidebarBackgroundHex = "0f172a",
            ForegroundPreset = ForegroundColorPreset.Custom,
            CustomAccentHex = "#10b981",
            CustomForegroundHex = "bad-hex",
            BackgroundOpacity = 0.05,
            SidebarOpacity = 0.02,
            BackgroundImageFileName = "nested/path/background.png",
            BackgroundImageOpacity = 5.0
        };

        AppearanceSettings saved = store.Save(raw);
        Assert.True(File.Exists(store.SettingsPath));
        Assert.Equal("#1E293B", saved.CustomBackgroundHex);
        Assert.Equal("#0F172A", saved.CustomSidebarBackgroundHex);
        Assert.Equal("#10B981", saved.CustomAccentHex);
        Assert.Null(saved.CustomForegroundHex);
        Assert.Equal(0.15, saved.BackgroundOpacity);
        Assert.Equal(0.15, saved.SidebarOpacity);
        Assert.Equal(1.0, saved.BackgroundImageOpacity);
        Assert.Equal("background.png", saved.BackgroundImageFileName);

        AppearanceSettings reloaded = store.Load();
        Assert.Equal(saved, reloaded);
    }

    [Fact]
    public void AppearanceStoreMigratesLegacyThemeTxtOnceWhenAppearanceJsonMissing()
    {
        string dir = NewTempDir();
        string legacyPath = Path.Combine(dir, "theme.txt");
        File.WriteAllText(legacyPath, "Light");

        var store = new AppearanceStore(dir, legacyPath);
        AppearanceSettings firstLoad = store.Load();

        Assert.Equal(AppThemeMode.Light, firstLoad.ThemeMode);
        Assert.True(File.Exists(store.SettingsPath));

        // Subsequent updates to appearance.json take precedence over legacy theme.txt
        store.Save(firstLoad with { ThemeMode = AppThemeMode.Dark, BackgroundPreset = BackgroundColorPreset.Obsidian });
        AppearanceSettings secondLoad = store.Load();
        Assert.Equal(AppThemeMode.Dark, secondLoad.ThemeMode);
        Assert.Equal(BackgroundColorPreset.Obsidian, secondLoad.BackgroundPreset);
    }

    [Fact]
    public void BackgroundImageImportReplaceAndClearManageAppearanceFolder()
    {
        string dir = NewTempDir();
        var store = new AppearanceStore(dir);

        string sourcePng = Path.Combine(dir, "wall.png");
        File.WriteAllBytes(sourcePng, [0x89, 0x50, 0x4E, 0x47, 1, 2, 3]);

        string firstFileName = store.ImportBackgroundImage(sourcePng);
        Assert.Equal("background.png", firstFileName);
        string? resolvedPng = store.ResolveBackgroundImagePath(firstFileName);
        Assert.NotNull(resolvedPng);
        Assert.True(File.Exists(resolvedPng));

        // Replace with a JPEG; old background.png must be deleted
        string secondFileName = store.ImportBackgroundImage([0xFF, 0xD8, 0xFF, 0xE0], ".jpg");
        Assert.Equal("background.jpg", secondFileName);
        Assert.False(File.Exists(Path.Combine(store.AppearanceDirectory, "background.png")));
        Assert.True(File.Exists(Path.Combine(store.AppearanceDirectory, "background.jpg")));

        // Traversal names must not escape AppearanceDirectory
        Assert.Null(store.ResolveBackgroundImagePath("../appearance.json"));

        store.ClearBackgroundImage();
        Assert.False(File.Exists(Path.Combine(store.AppearanceDirectory, "background.jpg")));
        Assert.Null(store.ResolveBackgroundImagePath(secondFileName));
    }

    [Fact]
    public void WcagContrastRatioAndAutoFixMeetAaThresholds()
    {
        var black = new RgbColor(0x00, 0x00, 0x00);
        var white = new RgbColor(0xFF, 0xFF, 0xFF);
        Assert.Equal(21.0, ColorMath.ContrastRatio(black, white), precision: 2);
        Assert.Equal(1.0, ColorMath.ContrastRatio(white, white), precision: 2);

        // Low-contrast foreground on dark background must be fixed to >= 4.5
        var darkBg = new RgbColor(0x18, 0x18, 0x1B);
        var lowFgOnDark = new RgbColor(0x38, 0x38, 0x42);
        Assert.True(ColorMath.ContrastRatio(darkBg, lowFgOnDark) < 4.5);
        RgbColor fixedDarkFg = ColorMath.AutoFixForeground(darkBg, lowFgOnDark);
        Assert.True(ColorMath.ContrastRatio(darkBg, fixedDarkFg) >= 4.5);

        // Low-contrast foreground on light background must be fixed to >= 4.5
        var lightBg = new RgbColor(0xF8, 0xF6, 0xF1);
        var lowFgOnLight = new RgbColor(0xD6, 0xD3, 0xCE);
        Assert.True(ColorMath.ContrastRatio(lightBg, lowFgOnLight) < 4.5);
        RgbColor fixedLightFg = ColorMath.AutoFixForeground(lightBg, lowFgOnLight);
        Assert.True(ColorMath.ContrastRatio(lightBg, fixedLightFg) >= 4.5);

        // Already accessible foreground is returned unchanged
        Assert.Equal(white, ColorMath.AutoFixForeground(darkBg, white));

        // Low-contrast accent must be fixed to >= 3.0
        var lowAccent = new RgbColor(0x22, 0x24, 0x2C);
        Assert.True(ColorMath.ContrastRatio(darkBg, lowAccent) < 3.0);
        RgbColor fixedAccent = ColorMath.AutoFixAccent(darkBg, lowAccent);
        Assert.True(ColorMath.ContrastRatio(darkBg, fixedAccent) >= 3.0);
    }

    [Fact]
    public void BlurClampingAndLyricsAppearanceSettingsPersist()
    {
        Assert.Equal(0.0, AppearanceSettings.ClampBackgroundImageBlur(-5.0));
        Assert.Equal(24.0, AppearanceSettings.ClampBackgroundImageBlur(60.0));
        Assert.Equal(0.0, AppearanceSettings.ClampBackgroundImageBlur(double.NaN));
        Assert.Equal(0.0, AppearanceSettings.ClampBackgroundImageBlur(double.PositiveInfinity));
        Assert.Equal(12.5, AppearanceSettings.ClampBackgroundImageBlur(12.5));

        string dir = NewTempDir();
        var store = new AppearanceStore(dir);
        var saved = store.Save(new AppearanceSettings
        {
            BackgroundImageBlur = 99.0,
            LyricsFontSize = LyricsFontSize.Large,
            LyricsAlignment = LyricsAlignment.Center
        });

        Assert.Equal(24.0, saved.BackgroundImageBlur);
        Assert.Equal(LyricsFontSize.Large, saved.LyricsFontSize);
        Assert.Equal(LyricsAlignment.Center, saved.LyricsAlignment);

        AppearanceSettings reloaded = store.Load();
        Assert.Equal(24.0, reloaded.BackgroundImageBlur);
        Assert.Equal(LyricsFontSize.Large, reloaded.LyricsFontSize);
        Assert.Equal(LyricsAlignment.Center, reloaded.LyricsAlignment);

        // Invalid enum values normalize safely back to defaults
        var invalidEnum = (new AppearanceSettings
        {
            LyricsFontSize = (LyricsFontSize)999,
            LyricsAlignment = (LyricsAlignment)999
        }).Normalize();
        Assert.Equal(LyricsFontSize.Medium, invalidEnum.LyricsFontSize);
        Assert.Equal(LyricsAlignment.Leading, invalidEnum.LyricsAlignment);
    }

    [Fact]
    public void WindowsVersionHelperAndBackdropSelectionHandleWin10AndWin11Builds()
    {
        var win10Pre1803 = new Version(10, 0, 16299, 0);
        var win10Acrylic = new Version(10, 0, 19045, 0);
        var win11Initial = new Version(10, 0, 22000, 0);
        var win1122H2 = new Version(10, 0, 22621, 0);

        Assert.False(WindowsVersionHelper.IsWindows11(win10Acrylic));
        Assert.True(WindowsVersionHelper.IsWindows11(win11Initial));
        Assert.True(WindowsVersionHelper.IsWindows11(win1122H2));

        Assert.False(WindowsVersionHelper.SupportsDwmSystemBackdrop(win11Initial));
        Assert.True(WindowsVersionHelper.SupportsDwmSystemBackdrop(win1122H2));

        // Win11 22H2+ with default opaque background uses native DWM transient backdrop
        Assert.Equal(
            WindowsBackdropKind.DwmTransientWindow,
            WindowsVersionHelper.ResolveBackdropKind(win1122H2, 1.0, hasCustomBackgroundColor: false, hasBackgroundImage: false));

        // Any custom opacity < 0.995, custom background color, background image, sidebar opacity < 0.995, or custom sidebar color falls back to Acrylic composition
        Assert.Equal(
            WindowsBackdropKind.Acrylic,
            WindowsVersionHelper.ResolveBackdropKind(win1122H2, 0.85, hasCustomBackgroundColor: false, hasBackgroundImage: false));
        Assert.Equal(
            WindowsBackdropKind.Acrylic,
            WindowsVersionHelper.ResolveBackdropKind(win1122H2, 1.0, hasCustomBackgroundColor: true, hasBackgroundImage: false));
        Assert.Equal(
            WindowsBackdropKind.Acrylic,
            WindowsVersionHelper.ResolveBackdropKind(win1122H2, 1.0, hasCustomBackgroundColor: false, hasBackgroundImage: true));
        Assert.Equal(
            WindowsBackdropKind.Acrylic,
            WindowsVersionHelper.ResolveBackdropKind(win1122H2, 1.0, hasCustomBackgroundColor: false, hasBackgroundImage: false, sidebarOpacity: 0.70));
        Assert.Equal(
            WindowsBackdropKind.Acrylic,
            WindowsVersionHelper.ResolveBackdropKind(win1122H2, 1.0, hasCustomBackgroundColor: false, hasBackgroundImage: false, sidebarOpacity: 1.0, hasCustomSidebarColor: true));

        // Win11 21H2 and Win10 1803+ use Acrylic composition; older Win10 falls back to BlurBehind
        Assert.Equal(
            WindowsBackdropKind.Acrylic,
            WindowsVersionHelper.ResolveBackdropKind(win11Initial, 1.0, hasCustomBackgroundColor: false, hasBackgroundImage: false));
        Assert.Equal(
            WindowsBackdropKind.Acrylic,
            WindowsVersionHelper.ResolveBackdropKind(win10Acrylic, 1.0, hasCustomBackgroundColor: false, hasBackgroundImage: false));
        Assert.Equal(
            WindowsBackdropKind.BlurBehind,
            WindowsVersionHelper.ResolveBackdropKind(win10Pre1803, 1.0, hasCustomBackgroundColor: false, hasBackgroundImage: false));
    }
}
