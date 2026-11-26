using System;
using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using Microsoft.Win32;
using MusicoWindows.Cloud;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;
using MusicoWindows.Update;

namespace MusicoWindows.Views;

public partial class SettingsView : UserControl
{
    private ViewContext? _ctx;
    private bool _isUpdatingUi;
    private string? _cachedPreviewPath;
    private DateTime _cachedPreviewWriteUtc;

    public SettingsView()
    {
        InitializeComponent();
        Unloaded += OnUnloaded;
    }

    public void Bind(ViewContext ctx)
    {
        Detach();
        _ctx = ctx;

        ThemeManager.AppearanceChanged += OnAppearanceChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        _ctx.Services.Library.PropertyChanged += OnLibraryChanged;
        _ctx.Services.Update.UpdateStateChanged += OnUpdateStateChanged;
        _ctx.Services.GoogleDriveOAuth.StateChanged += OnGoogleDriveOAuthChanged;

        RefreshAll();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => Detach();

    private void Detach()
    {
        ThemeManager.AppearanceChanged -= OnAppearanceChanged;
        LocalizationManager.LanguageChanged -= OnLanguageChanged;
        if (_ctx is not null)
        {
            _ctx.Services.Library.PropertyChanged -= OnLibraryChanged;
            _ctx.Services.Update.UpdateStateChanged -= OnUpdateStateChanged;
            _ctx.Services.GoogleDriveOAuth.StateChanged -= OnGoogleDriveOAuthChanged;
        }
    }

    private void OnAppearanceChanged(AppearanceSettings _)
    {
        Dispatcher.BeginInvoke(RefreshAppearanceSection);
    }

    private void OnLanguageChanged(ResolvedLanguage _)
    {
        Dispatcher.BeginInvoke(RefreshAll);
    }

    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(LibraryStore.IsScanning))
        {
            Dispatcher.BeginInvoke(RefreshLibrarySection);
        }
    }

    private void OnUpdateStateChanged(object? sender, UpdateCheckResult _)
    {
        Dispatcher.BeginInvoke(RefreshAboutSection);
    }

    private void OnGoogleDriveOAuthChanged(object? sender, GoogleDriveOAuthState _)
    {
        Dispatcher.BeginInvoke(RefreshGoogleDriveSection);
    }

    private void RefreshAll()
    {
        RefreshAppearanceSection();
        RefreshLanguageSection();
        RefreshGoogleDriveSection();
        RefreshLibrarySection();
        RefreshAboutSection();
    }

    private void RefreshAppearanceSection()
    {
        AppearanceSettings settings = ThemeManager.CurrentSettings;
        _isUpdatingUi = true;
        try
        {
            // Theme mode buttons
            UpdateThemeModeButton(ThemeSystemButton, settings.ThemeMode == AppThemeMode.System);
            UpdateThemeModeButton(ThemeLightButton, settings.ThemeMode == AppThemeMode.Light);
            UpdateThemeModeButton(ThemeDarkButton, settings.ThemeMode == AppThemeMode.Dark);

            bool bgPresetOverridesMode = AppearancePresets.ResolveBackgroundColor(settings) is not null;
            ThemeModeOverrideHint.Text = L.Get("Settings_BgModeOverrideHint");
            ThemeModeOverrideHint.Visibility = bgPresetOverridesMode ? Visibility.Visible : Visibility.Collapsed;

            // Preset swatches
            RebuildBackgroundPresets(settings);
            RebuildSidebarPresets(settings);
            RebuildForegroundPresets(settings);

            // Custom background picker
            CustomBackgroundPanel.Visibility = settings.BackgroundPreset == BackgroundColorPreset.Custom
                ? Visibility.Visible
                : Visibility.Collapsed;
            RgbColor customBg = ColorMath.TryParseHex(settings.CustomBackgroundHex, out var parsedBg)
                ? parsedBg
                : (ThemeManager.IsDark ? new RgbColor(0x17, 0x17, 0x17) : new RgbColor(0xF8, 0xF6, 0xF1));
            SyncColorPicker(customBg, CustomBgPreviewSwatch, CustomBgHexBox, CustomBgRSlider, CustomBgGSlider, CustomBgBSlider, CustomBgRText, CustomBgGText, CustomBgBText);

            // Custom sidebar background picker
            CustomSidebarPanel.Visibility = settings.SidebarBackgroundPreset == BackgroundColorPreset.Custom
                ? Visibility.Visible
                : Visibility.Collapsed;
            RgbColor customSidebarBg = ColorMath.TryParseHex(settings.CustomSidebarBackgroundHex, out var parsedSidebarBg)
                ? parsedSidebarBg
                : (ThemeManager.IsDark ? new RgbColor(0x18, 0x18, 0x18) : new RgbColor(0xEF, 0xEF, 0xEF));
            SyncColorPicker(customSidebarBg, CustomSidebarPreviewSwatch, CustomSidebarHexBox, CustomSidebarRSlider, CustomSidebarGSlider, CustomSidebarBSlider, CustomSidebarRText, CustomSidebarGText, CustomSidebarBText);

            // Custom foreground & accent picker
            CustomForegroundPanel.Visibility = settings.ForegroundPreset == ForegroundColorPreset.Custom
                ? Visibility.Visible
                : Visibility.Collapsed;
            RgbColor customAccent = ColorMath.TryParseHex(settings.CustomAccentHex, out var parsedAccent)
                ? parsedAccent
                : AppearancePresets.ResolveAccentColor(settings);
            SyncColorPicker(customAccent, CustomAccentPreviewSwatch, CustomAccentHexBox, CustomAccentRSlider, CustomAccentGSlider, CustomAccentBSlider, CustomAccentRText, CustomAccentGText, CustomAccentBText);

            bool hasCustomText = ColorMath.TryParseHex(settings.CustomForegroundHex, out var parsedFg);
            CustomTextToggle.IsChecked = hasCustomText;
            CustomTextPickerSection.Visibility = hasCustomText ? Visibility.Visible : Visibility.Collapsed;
            RgbColor customFg = hasCustomText
                ? parsedFg
                : (ThemeManager.IsDark ? AppearancePresets.LightTextOnDark : AppearancePresets.DarkTextOnLight);
            SyncColorPicker(customFg, CustomFgPreviewSwatch, CustomFgHexBox, CustomFgRSlider, CustomFgGSlider, CustomFgBSlider, CustomFgRText, CustomFgGText, CustomFgBText);

            // Opacity sliders + live preview content backdrop opacity
            double clampedBgOpacity = AppearanceSettings.ClampBackgroundOpacity(settings.BackgroundOpacity);
            LivePreviewContentBackdrop.Opacity = clampedBgOpacity;
            int bgPct = (int)Math.Round(clampedBgOpacity * 100.0);
            WindowOpacitySlider.Value = Math.Clamp(bgPct, 15, 100);
            WindowOpacityValueText.Text = $"{bgPct}%";

            int sidebarPct = (int)Math.Round(settings.ResolveEffectiveSidebarOpacity() * 100.0);
            SidebarOpacitySlider.Value = Math.Clamp(sidebarPct, 15, 100);
            SidebarOpacityValueText.Text = $"{sidebarPct}%";

            // Background image preview + visibility & blur sliders
            string? imagePath = ThemeManager.ResolveBackgroundImagePath();
            bool hasImage = !string.IsNullOrWhiteSpace(imagePath) && File.Exists(imagePath);
            RemoveBgImageButton.Visibility = hasImage ? Visibility.Visible : Visibility.Collapsed;
            ImageVisibilityRow.Visibility = hasImage ? Visibility.Visible : Visibility.Collapsed;
            ImageBlurRow.Visibility = hasImage ? Visibility.Visible : Visibility.Collapsed;

            if (!hasImage)
            {
                BgImageStatusText.SetResourceReference(TextBlock.TextProperty, "Str_Settings_NoBackgroundImage");
                BgImagePreview.Source = null;
                BgImagePreview.Visibility = Visibility.Collapsed;
                BgImagePlaceholderGlyph.Visibility = Visibility.Visible;
                LivePreviewBgImage.Source = null;
                LivePreviewBgImage.Effect = null;
                LivePreviewBgImage.Visibility = Visibility.Collapsed;
                LivePreviewBgScrim.Visibility = Visibility.Collapsed;
                _cachedPreviewPath = null;
            }
            else
            {
                BgImageStatusText.ClearValue(TextBlock.TextProperty);
                BgImageStatusText.Text = L.Get("Settings_ImageActive");
                LoadPreviewImage(imagePath!);

                LivePreviewBgImage.Source = BgImagePreview.Source;
                LivePreviewBgImage.Opacity = settings.BackgroundImageOpacity;
                LivePreviewBgImage.Effect = settings.BackgroundImageBlur > 0.05
                    ? new BlurEffect { Radius = settings.BackgroundImageBlur, KernelType = KernelType.Gaussian }
                    : null;
                LivePreviewBgImage.Visibility = BgImagePreview.Source is not null ? Visibility.Visible : Visibility.Collapsed;

                ThemePalette? previewPalette = ThemeManager.CurrentPalette;
                RgbColor scrimRgb = previewPalette is not null
                    ? new RgbColor(previewPalette.ContentBackground.R, previewPalette.ContentBackground.G, previewPalette.ContentBackground.B)
                    : (ThemeManager.IsDark ? new RgbColor(0x14, 0x14, 0x14) : new RgbColor(0xF2, 0xF2, 0xF2));
                byte scrimAlpha = ColorMath.ComputeBackgroundScrimAlpha(settings);
                var scrimBrush = new SolidColorBrush(Color.FromArgb(scrimAlpha, scrimRgb.R, scrimRgb.G, scrimRgb.B));
                scrimBrush.Freeze();
                LivePreviewBgScrim.Background = scrimBrush;
                LivePreviewBgScrim.Visibility = LivePreviewBgImage.Visibility;
            }

            int imgPct = (int)Math.Round(settings.BackgroundImageOpacity * 100.0);
            ImageOpacitySlider.Value = Math.Clamp(imgPct, 10, 100);
            ImageOpacityValueText.Text = $"{imgPct}%";

            double blurVal = AppearanceSettings.ClampBackgroundImageBlur(settings.BackgroundImageBlur);
            ImageBlurSlider.Value = Math.Round(blurVal);
            ImageBlurValueText.Text = L.Format("Settings_BlurPxFormat", (int)Math.Round(blurVal));

            // WCAG 2.1 contrast check
            RgbColor effectiveBg = ResolveEffectiveBackgroundRgb(settings);
            RgbColor effectiveFg = AppearancePresets.ResolveForegroundColor(settings, ThemeManager.IsDark);
            RgbColor effectiveAccent = AppearancePresets.ResolveAccentColor(settings);
            double fgRatio = ColorMath.ContrastRatio(effectiveBg, effectiveFg);
            double accentRatio = ColorMath.ContrastRatio(effectiveBg, effectiveAccent);
            bool lowContrast = fgRatio < 4.5 || accentRatio < 3.0;
            ContrastWarningBanner.Visibility = lowContrast ? Visibility.Visible : Visibility.Collapsed;
            if (lowContrast)
            {
                ContrastWarningText.Text = L.Format("Settings_LowContrastDesc", fgRatio, accentRatio);
            }
        }
        finally
        {
            _isUpdatingUi = false;
        }
    }

    private void LoadPreviewImage(string imagePath)
    {
        try
        {
            DateTime writeUtc = File.GetLastWriteTimeUtc(imagePath);
            if (string.Equals(_cachedPreviewPath, imagePath, StringComparison.OrdinalIgnoreCase) &&
                _cachedPreviewWriteUtc == writeUtc &&
                BgImagePreview.Source is not null)
            {
                BgImagePreview.Visibility = Visibility.Visible;
                BgImagePlaceholderGlyph.Visibility = Visibility.Collapsed;
                return;
            }

            byte[] bytes = File.ReadAllBytes(imagePath);
            using var ms = new MemoryStream(bytes);
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.DecodePixelWidth = 200;
            bmp.StreamSource = ms;
            bmp.EndInit();
            bmp.Freeze();

            _cachedPreviewPath = imagePath;
            _cachedPreviewWriteUtc = writeUtc;
            BgImagePreview.Source = bmp;
            BgImagePreview.Visibility = Visibility.Visible;
            BgImagePlaceholderGlyph.Visibility = Visibility.Collapsed;
        }
        catch
        {
            BgImagePreview.Source = null;
            BgImagePreview.Visibility = Visibility.Collapsed;
            BgImagePlaceholderGlyph.Visibility = Visibility.Visible;
        }
    }

    private void UpdateThemeModeButton(Button btn, bool active)
    {
        btn.Style = (Style)FindResource(active ? "Win10PrimaryButtonStyle" : "Win10ButtonStyle");
    }

    private void RebuildBackgroundPresets(AppearanceSettings settings)
    {
        BackgroundPresetsPanel.Children.Clear();
        foreach (BackgroundColorPreset preset in AppearancePresets.AllBackgroundPresets)
        {
            bool selected = settings.BackgroundPreset == preset;
            string label = L.Get($"Settings_BgPreset_{preset}");
            string? hex = preset == BackgroundColorPreset.Custom
                ? settings.CustomBackgroundHex
                : AppearancePresets.GetBackgroundHex(preset);

            var btn = CreatePresetSwatchButton(label, hex, isCustom: preset == BackgroundColorPreset.Custom, isDefault: preset == BackgroundColorPreset.Default, selected: selected);
            BackgroundColorPreset captured = preset;
            btn.Click += (_, _) => ThemeManager.SetBackgroundPreset(Application.Current, captured);
            BackgroundPresetsPanel.Children.Add(btn);
        }
    }

    private void RebuildSidebarPresets(AppearanceSettings settings)
    {
        SidebarPresetsPanel.Children.Clear();
        foreach (BackgroundColorPreset preset in AppearancePresets.AllBackgroundPresets)
        {
            bool selected = settings.SidebarBackgroundPreset == preset;
            string label = preset == BackgroundColorPreset.Default
                ? L.Get("Settings_SidebarPreset_Default")
                : L.Get($"Settings_BgPreset_{preset}");
            string? hex = preset == BackgroundColorPreset.Custom
                ? settings.CustomSidebarBackgroundHex
                : AppearancePresets.GetBackgroundHex(preset);

            var btn = CreatePresetSwatchButton(label, hex, isCustom: preset == BackgroundColorPreset.Custom, isDefault: preset == BackgroundColorPreset.Default, selected: selected);
            BackgroundColorPreset captured = preset;
            btn.Click += (_, _) => ThemeManager.SetSidebarBackgroundPreset(Application.Current, captured);
            SidebarPresetsPanel.Children.Add(btn);
        }
    }

    private void RebuildForegroundPresets(AppearanceSettings settings)
    {
        ForegroundPresetsPanel.Children.Clear();
        foreach (ForegroundColorPreset preset in AppearancePresets.AllForegroundPresets)
        {
            bool selected = settings.ForegroundPreset == preset;
            string label = L.Get($"Settings_FgPreset_{preset}");
            string? hex = preset == ForegroundColorPreset.Custom
                ? (settings.CustomAccentHex ?? AppearancePresets.GetForegroundAccentHex(ForegroundColorPreset.Coral))
                : AppearancePresets.GetForegroundAccentHex(preset);

            var btn = CreatePresetSwatchButton(label, hex, isCustom: preset == ForegroundColorPreset.Custom, isDefault: false, selected: selected);
            ForegroundColorPreset captured = preset;
            btn.Click += (_, _) => ThemeManager.SetForegroundPreset(Application.Current, captured);
            ForegroundPresetsPanel.Children.Add(btn);
        }
    }

    private Button CreatePresetSwatchButton(string label, string? hex, bool isCustom, bool isDefault, bool selected)
    {
        var btn = new Button
        {
            Style = (Style)FindResource("Win10ButtonStyle"),
            Padding = new Thickness(10, 6, 12, 6),
            Margin = new Thickness(0, 0, 8, 8),
            BorderThickness = new Thickness(selected ? 2 : 1)
        };
        btn.SetResourceReference(BorderBrushProperty, selected ? "AccentBrush" : "ControlBorderBrush");
        btn.SetResourceReference(BackgroundProperty, selected ? "AccentSubtleBrush" : "ControlFillBrush");

        var content = new StackPanel { Orientation = Orientation.Horizontal };

        var swatch = new Border
        {
            Width = 16,
            Height = 16,
            BorderThickness = new Thickness(1),
            Margin = new Thickness(0, 0, 8, 0),
            VerticalAlignment = VerticalAlignment.Center
        };
        swatch.SetResourceReference(Border.BorderBrushProperty, "ControlBorderBrush");

        if (hex is not null && ColorMath.TryParseHex(hex, out var rgb))
        {
            swatch.Background = new SolidColorBrush(Color.FromRgb(rgb.R, rgb.G, rgb.B));
        }
        else if (isDefault)
        {
            swatch.SetResourceReference(Border.BackgroundProperty, "ContentBackgroundBrush");
            swatch.Child = new TextBlock
            {
                Text = "\uE790",
                FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                FontSize = 9,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center
            };
        }
        else if (isCustom)
        {
            swatch.SetResourceReference(Border.BackgroundProperty, "AccentBrush");
        }

        var text = new TextBlock
        {
            Text = label,
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 12,
            FontWeight = selected ? FontWeights.SemiBold : FontWeights.Normal,
            VerticalAlignment = VerticalAlignment.Center
        };
        text.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");

        content.Children.Add(swatch);
        content.Children.Add(text);
        btn.Content = content;
        return btn;
    }

    private static void SyncColorPicker(
        RgbColor rgb,
        Border previewSwatch,
        TextBox hexBox,
        Slider rSlider,
        Slider gSlider,
        Slider bSlider,
        TextBlock rText,
        TextBlock gText,
        TextBlock bText)
    {
        previewSwatch.Background = new SolidColorBrush(Color.FromRgb(rgb.R, rgb.G, rgb.B));
        string hex = ColorMath.ToHex(rgb);
        if (!hexBox.IsKeyboardFocusWithin || !string.Equals(hexBox.Text, hex, StringComparison.OrdinalIgnoreCase))
        {
            hexBox.Text = hex;
        }
        rSlider.Value = rgb.R;
        gSlider.Value = rgb.G;
        bSlider.Value = rgb.B;
        rText.Text = rgb.R.ToString();
        gText.Text = rgb.G.ToString();
        bText.Text = rgb.B.ToString();
    }

    private void RefreshLanguageSection()
    {
        _isUpdatingUi = true;
        try
        {
            LanguageCombo.Items.Clear();
            LanguageCombo.Items.Add(L.Get("Lang_FollowSystem"));
            LanguageCombo.Items.Add(L.Get("Lang_English"));
            LanguageCombo.Items.Add(L.Get("Lang_SimplifiedChinese"));

            LanguageCombo.SelectedIndex = LocalizationManager.CurrentMode switch
            {
                AppLanguage.English => 1,
                AppLanguage.SimplifiedChinese => 2,
                _ => 0
            };
        }
        finally
        {
            _isUpdatingUi = false;
        }
    }

    private void RefreshLibrarySection()
    {
        bool scanning = _ctx?.Services.Library.IsScanning ?? false;
        RescanLibraryButton.IsEnabled = !scanning;
        RescanButtonLabel.Text = scanning ? L.Get("Settings_Scanning") : L.Get("Nav_Rescan");
    }

    private void RefreshAboutSection()
    {
        AppVersion ver = _ctx?.Services.Update.CurrentVersion ?? UpdateService.GetCurrentAppVersion();
        UpdateCheckResult? latest = _ctx?.Services.Update.LatestCheckResult;

        string statusPart = latest is { IsUpdateAvailable: true, Release: { } rel }
            ? L.Format("Settings_UpdateReady", rel.Version)
            : L.Get("Settings_UpToDate");

        VersionSummaryText.Text = $"{L.Format("Settings_VersionFormat", ver)} · {statusPart}";
        UpdateAvailablePill.Visibility = latest is { IsUpdateAvailable: true }
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private void ThemeSystem_Click(object sender, RoutedEventArgs e) =>
        ThemeManager.SetMode(Application.Current, AppThemeMode.System);

    private void ThemeLight_Click(object sender, RoutedEventArgs e) =>
        ThemeManager.SetMode(Application.Current, AppThemeMode.Light);

    private void ThemeDark_Click(object sender, RoutedEventArgs e) =>
        ThemeManager.SetMode(Application.Current, AppThemeMode.Dark);

    private void CustomBgHexBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        if (ColorMath.TryParseHex(CustomBgHexBox.Text, out var rgb))
        {
            ThemeManager.SetCustomBackgroundHex(Application.Current, ColorMath.ToHex(rgb));
        }
    }

    private void CustomBgHexBox_LostFocus(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        RefreshAppearanceSection();
    }

    private void CustomBgRgbSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        var rgb = new RgbColor((byte)CustomBgRSlider.Value, (byte)CustomBgGSlider.Value, (byte)CustomBgBSlider.Value);
        ThemeManager.SetCustomBackgroundHex(Application.Current, ColorMath.ToHex(rgb));
    }

    private void CustomSidebarHexBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        if (ColorMath.TryParseHex(CustomSidebarHexBox.Text, out var rgb))
        {
            ThemeManager.SetCustomSidebarBackgroundHex(Application.Current, ColorMath.ToHex(rgb));
        }
    }

    private void CustomSidebarHexBox_LostFocus(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        RefreshAppearanceSection();
    }

    private void CustomSidebarRgbSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        var rgb = new RgbColor((byte)CustomSidebarRSlider.Value, (byte)CustomSidebarGSlider.Value, (byte)CustomSidebarBSlider.Value);
        ThemeManager.SetCustomSidebarBackgroundHex(Application.Current, ColorMath.ToHex(rgb));
    }

    private void CustomAccentHexBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        if (ColorMath.TryParseHex(CustomAccentHexBox.Text, out var rgb))
        {
            ThemeManager.SetCustomAccentHex(Application.Current, ColorMath.ToHex(rgb));
        }
    }

    private void CustomAccentHexBox_LostFocus(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        RefreshAppearanceSection();
    }

    private void CustomAccentRgbSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        var rgb = new RgbColor((byte)CustomAccentRSlider.Value, (byte)CustomAccentGSlider.Value, (byte)CustomAccentBSlider.Value);
        ThemeManager.SetCustomAccentHex(Application.Current, ColorMath.ToHex(rgb));
    }

    private void CustomTextToggle_Changed(object sender, RoutedEventArgs e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        if (CustomTextToggle.IsChecked == true)
        {
            RgbColor initial = ThemeManager.IsDark
                ? AppearancePresets.LightTextOnDark
                : AppearancePresets.DarkTextOnLight;
            ThemeManager.SetCustomForegroundHex(Application.Current, ColorMath.ToHex(initial));
        }
        else
        {
            ThemeManager.SetCustomForegroundHex(Application.Current, null);
        }
    }

    private void CustomFgHexBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        if (ColorMath.TryParseHex(CustomFgHexBox.Text, out var rgb))
        {
            ThemeManager.SetCustomForegroundHex(Application.Current, ColorMath.ToHex(rgb));
        }
    }

    private void CustomFgHexBox_LostFocus(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        RefreshAppearanceSection();
    }

    private void CustomFgRgbSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        var rgb = new RgbColor((byte)CustomFgRSlider.Value, (byte)CustomFgGSlider.Value, (byte)CustomFgBSlider.Value);
        ThemeManager.SetCustomForegroundHex(Application.Current, ColorMath.ToHex(rgb));
    }

    private void WindowOpacitySlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        double opacity = Math.Round(e.NewValue) / 100.0;
        WindowOpacityValueText.Text = $"{(int)Math.Round(e.NewValue)}%";
        ThemeManager.SetBackgroundOpacity(Application.Current, opacity);
    }

    private void SidebarOpacitySlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        double opacity = Math.Round(e.NewValue) / 100.0;
        SidebarOpacityValueText.Text = $"{(int)Math.Round(e.NewValue)}%";
        ThemeManager.SetSidebarOpacity(Application.Current, opacity);
    }

    private void ChooseBgImage_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = L.Get("Settings_SelectImageTitle"),
            Filter = L.Get("Settings_SelectImageFilter"),
            CheckFileExists = true
        };

        if (dlg.ShowDialog() != true)
            return;

        try
        {
            ThemeManager.ImportBackgroundImage(Application.Current, dlg.FileName);
        }
        catch (Exception ex)
        {
            _ctx?.OpenConfirm(
                L.Get("Settings_ImageImportErrorTitle"),
                ex.Message,
                L.Get("Common_OK"),
                null,
                false);
        }
    }

    private void RemoveBgImage_Click(object sender, RoutedEventArgs e)
    {
        ThemeManager.ClearBackgroundImage(Application.Current);
    }

    private void ImageOpacitySlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        double opacity = Math.Round(e.NewValue) / 100.0;
        ImageOpacityValueText.Text = $"{(int)Math.Round(e.NewValue)}%";
        ThemeManager.SetBackgroundImageOpacity(Application.Current, opacity);
    }

    private void ImageBlurSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        double blur = Math.Round(e.NewValue);
        ImageBlurValueText.Text = L.Format("Settings_BlurPxFormat", (int)blur);
        ThemeManager.SetBackgroundImageBlur(Application.Current, blur);
    }

    private void AutoFixContrast_Click(object sender, RoutedEventArgs e)
    {
        AppearanceSettings settings = ThemeManager.CurrentSettings;
        RgbColor effectiveBg = ResolveEffectiveBackgroundRgb(settings);
        RgbColor effectiveFg = AppearancePresets.ResolveForegroundColor(settings, ThemeManager.IsDark);
        RgbColor effectiveAccent = AppearancePresets.ResolveAccentColor(settings);

        string? fixedFgHex = ColorMath.ContrastRatio(effectiveBg, effectiveFg) < 4.5
            ? ColorMath.ToHex(ColorMath.AutoFixForeground(effectiveBg, effectiveFg))
            : null;
        string? fixedAccentHex = ColorMath.ContrastRatio(effectiveBg, effectiveAccent) < 3.0
            ? ColorMath.ToHex(ColorMath.AutoFixAccent(effectiveBg, effectiveAccent))
            : null;

        ThemeManager.ApplyContrastFix(Application.Current, fixedFgHex, fixedAccentHex);
    }

    private static RgbColor ResolveEffectiveBackgroundRgb(AppearanceSettings settings)
    {
        if (AppearancePresets.ResolveBackgroundColor(settings) is RgbColor explicitBg)
            return explicitBg;

        if (ThemeManager.CurrentPalette is { } palette)
            return new RgbColor(palette.ContentBackground.R, palette.ContentBackground.G, palette.ContentBackground.B);

        return ThemeManager.IsDark ? new RgbColor(0x17, 0x17, 0x17) : new RgbColor(0xFF, 0xFF, 0xFF);
    }

    private void ResetAppearance_Click(object sender, RoutedEventArgs e)
    {
        ThemeManager.ResetToDefaults(Application.Current);
    }

    private void LanguageCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_isUpdatingUi || !IsLoaded) return;
        AppLanguage mode = LanguageCombo.SelectedIndex switch
        {
            1 => AppLanguage.English,
            2 => AppLanguage.SimplifiedChinese,
            _ => AppLanguage.System
        };
        LocalizationManager.SetMode(Application.Current, mode);
    }

    private async void RescanLibrary_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _ctx.Services.Library.IsScanning)
            return;
        await _ctx.Services.Library.RescanAsync();
    }

    private void CheckUpdates_Click(object sender, RoutedEventArgs e)
    {
        _ctx?.OpenUpdateDialog?.Invoke();
    }

    private void RefreshGoogleDriveSection()
    {
        if (_ctx is null) return;
        var st = _ctx.Services.GoogleDriveOAuth.CurrentState;
        _isUpdatingUi = true;
        try
        {
            GDriveClientIdBox.Text = st.ClientId;
            if (GDriveClientSecretBox.Password != st.ClientSecret)
            {
                GDriveClientSecretBox.Password = st.ClientSecret;
            }
            GDriveStatusText.Text = st.IsSignedIn
                ? (st.AccountEmail ?? L.Get("Settings_GDriveConnected"))
                : L.Get("Settings_GDriveNotSignedIn");
            GDriveSignInLabel.Text = st.IsSignedIn
                ? L.Get("Settings_GDriveReconnect")
                : L.Get("Settings_GDriveSignIn");
            GDriveSignInButton.IsEnabled = !st.IsSigningIn && !string.IsNullOrWhiteSpace(st.ClientId);
            GDriveSignOutButton.Visibility = st.IsSignedIn ? Visibility.Visible : Visibility.Collapsed;
            GDriveErrorText.Text = st.ErrorMessage ?? string.Empty;
            GDriveErrorText.Visibility = string.IsNullOrWhiteSpace(st.ErrorMessage) ? Visibility.Collapsed : Visibility.Visible;
        }
        finally
        {
            _isUpdatingUi = false;
        }
    }

    private void GDriveCredentials_LostFocus(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _isUpdatingUi) return;
        _ctx.Services.GoogleDriveOAuth.UpdateClientCredentials(GDriveClientIdBox.Text, GDriveClientSecretBox.Password);
    }

    private async void GDriveSignIn_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        _ctx.Services.GoogleDriveOAuth.UpdateClientCredentials(GDriveClientIdBox.Text, GDriveClientSecretBox.Password);
        await _ctx.Services.GoogleDriveOAuth.StartSignInAsync();
    }

    private void GDriveSignOut_Click(object sender, RoutedEventArgs e)
    {
        _ctx?.Services.GoogleDriveOAuth.SignOut();
    }
}
