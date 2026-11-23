using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using Microsoft.Win32;
using MusicoWindows.Extensions;
using MusicoWindows.Localization;
using MusicoWindows.Theme;

namespace MusicoWindows.Views;

public partial class ExtensionsView : UserControl
{
    private ViewContext? _ctx;
    private string? _reviewExtensionId;
    private string? _activeLogExtensionId;
    private bool _isBusy;

    public ExtensionsView()
    {
        InitializeComponent();
        Unloaded += (_, _) => Detach();
    }

    public bool IsReviewOpen => PermissionReviewOverlay.Visibility == Visibility.Visible;

    public void Bind(ViewContext ctx)
    {
        Detach();
        _ctx = ctx;
        _ctx.Services.Extensions.Changed += OnExtensionsChanged;
        _ctx.Services.Extensions.LogsChanged += OnLogsChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        Refresh();
    }

    public bool HandleEscape()
    {
        if (IsReviewOpen)
        {
            ClosePermissionReview();
            return true;
        }
        return false;
    }

    private void Detach()
    {
        LocalizationManager.LanguageChanged -= OnLanguageChanged;
        if (_ctx is null) return;
        _ctx.Services.Extensions.Changed -= OnExtensionsChanged;
        _ctx.Services.Extensions.LogsChanged -= OnLogsChanged;
    }

    private void OnLanguageChanged(ResolvedLanguage _) => Refresh();

    private void OnExtensionsChanged() => Dispatcher.BeginInvoke(Refresh);

    private void OnLogsChanged(string extensionId)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (string.Equals(_activeLogExtensionId, extensionId, StringComparison.OrdinalIgnoreCase))
            {
                RefreshLogsDrawer();
            }
            Refresh();
        });
    }

    private void Refresh()
    {
        if (_ctx is null) return;
        var store = _ctx.Services.Extensions;
        var extensions = store.Extensions;

        ExtensionsStack.Children.Clear();
        EmptyState.Visibility = extensions.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

        foreach (var ext in extensions)
        {
            ExtensionsStack.Children.Add(BuildExtensionCard(ext, store));
        }

        if (_activeLogExtensionId is not null && store.GetById(_activeLogExtensionId) is null)
        {
            _activeLogExtensionId = null;
        }
        RefreshLogsDrawer();
    }

    private Border BuildExtensionCard(InstalledExtension ext, ExtensionStore store)
    {
        var card = new Border
        {
            Margin = new Thickness(0, 0, 0, 12),
            Padding = new Thickness(18, 16, 18, 16),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(0)
        };
        card.SetResourceReference(Border.BackgroundProperty, "CardBackgroundBrush");
        card.SetResourceReference(Border.BorderBrushProperty, "ControlBorderBrush");

        var rootPanel = new StackPanel();

        // Top row: icon, title + badge + status, toggle / review / logs / uninstall
        var topGrid = new Grid();
        topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(36) });
        topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        topGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var glyph = new TextBlock
        {
            Text = "\uEA86",
            FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
            FontSize = 20,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 2, 0, 0)
        };
        glyph.SetResourceReference(TextBlock.ForegroundProperty, ext.IsEnabled ? "AccentBrush" : "TextSecondaryBrush");
        Grid.SetColumn(glyph, 0);

        var headerInfo = new StackPanel { Margin = new Thickness(8, 0, 16, 0) };
        var titleRow = new WrapPanel { VerticalAlignment = VerticalAlignment.Center };

        var nameTb = new TextBlock
        {
            Text = ext.Manifest.Name,
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 15,
            FontWeight = FontWeights.SemiBold,
            VerticalAlignment = VerticalAlignment.Center
        };
        nameTb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
        titleRow.Children.Add(nameTb);

        titleRow.Children.Add(CreatePill(ext.Manifest.EffectiveBadgeLabel, isAccent: true));

        string statusLabel = ext.PendingPermissionReview
            ? L.Get("Extensions_StatusPendingReview")
            : ext.IsEnabled
                ? L.Get("Extensions_StatusEnabled")
                : L.Get("Extensions_StatusDisabled");
        titleRow.Children.Add(CreatePill(statusLabel, isAccent: ext.IsEnabled, isWarning: ext.PendingPermissionReview));

        string author = string.IsNullOrWhiteSpace(ext.Manifest.Author)
            ? L.Get("Extensions_UnknownAuthor")
            : ext.Manifest.Author!;
        var metaTb = new TextBlock
        {
            Text = $"{L.Format("Extensions_AuthorVersion", ext.Manifest.Version, author)} · {ext.ProviderId}",
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 12,
            Margin = new Thickness(0, 3, 0, 0)
        };
        metaTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");

        headerInfo.Children.Add(titleRow);
        headerInfo.Children.Add(metaTb);

        if (!string.IsNullOrWhiteSpace(ext.Manifest.Description))
        {
            var descTb = new TextBlock
            {
                Text = ext.Manifest.Description,
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 12,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 5, 0, 0)
            };
            descTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
            headerInfo.Children.Add(descTb);
        }

        Grid.SetColumn(headerInfo, 1);

        var actions = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            VerticalAlignment = VerticalAlignment.Top
        };

        if (ext.PendingPermissionReview)
        {
            var reviewBtn = new Button
            {
                Style = (Style)FindResource("Win10PrimaryButtonStyle"),
                Content = L.Get("Extensions_ReviewPermissions"),
                Margin = new Thickness(0, 0, 8, 0)
            };
            reviewBtn.Click += (_, _) => OpenPermissionReview(ext.Id);
            actions.Children.Add(reviewBtn);
        }
        else
        {
            var toggle = new CheckBox
            {
                Style = (Style)FindResource("Win10ToggleSwitchStyle"),
                IsChecked = ext.IsEnabled,
                VerticalAlignment = VerticalAlignment.Center,
                Margin = new Thickness(0, 0, 12, 0)
            };
            toggle.Checked += (_, _) => store.SetEnabled(ext.Id, true);
            toggle.Unchecked += (_, _) => store.SetEnabled(ext.Id, false);
            actions.Children.Add(toggle);
        }

        int logCount = store.GetLogs(ext.Id).Count;
        bool isShowingLogs = string.Equals(_activeLogExtensionId, ext.Id, StringComparison.OrdinalIgnoreCase);
        var logsBtn = new Button
        {
            Style = (Style)FindResource("Win10ButtonStyle"),
            Content = isShowingLogs ? L.Get("Extensions_HideLogs") : L.Format("Extensions_ViewLogs", logCount),
            Margin = new Thickness(0, 0, 8, 0)
        };
        logsBtn.Click += (_, _) =>
        {
            _activeLogExtensionId = isShowingLogs ? null : ext.Id;
            Refresh();
        };
        actions.Children.Add(logsBtn);

        var uninstallBtn = new Button
        {
            Style = (Style)FindResource("Win10ButtonStyle"),
            Content = L.Get("Extensions_Uninstall")
        };
        uninstallBtn.Click += (_, _) => ConfirmUninstall(ext);
        actions.Children.Add(uninstallBtn);

        Grid.SetColumn(actions, 2);

        topGrid.Children.Add(glyph);
        topGrid.Children.Add(headerInfo);
        topGrid.Children.Add(actions);
        rootPanel.Children.Add(topGrid);

        // Permissions summary row
        string capsText = string.Join(", ", ext.Manifest.Capabilities.Select(FormatCapability));
        string hostsText = ext.Manifest.Permissions.Hosts.Count > 0
            ? string.Join(", ", ext.Manifest.Permissions.Hosts)
            : L.Get("Extensions_NoHosts");
        var permTb = new TextBlock
        {
            Text = $"{L.Get("Extensions_PermissionsHeader")}: {capsText} · {hostsText}",
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 11,
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(44, 10, 0, 0)
        };
        permTb.SetResourceReference(TextBlock.ForegroundProperty, "TextTertiaryBrush");
        rootPanel.Children.Add(permTb);

        // Dynamic Settings Editor
        if (ext.Manifest.Settings is { Count: > 0 } settings)
        {
            var settingsPanel = new StackPanel
            {
                Margin = new Thickness(44, 12, 0, 0)
            };

            var settingsBorder = new Border
            {
                BorderThickness = new Thickness(0, 1, 0, 0),
                Padding = new Thickness(0, 10, 0, 0)
            };
            settingsBorder.SetResourceReference(Border.BorderBrushProperty, "DividerBrush");

            var settingsHeader = new TextBlock
            {
                Text = L.Get("Extensions_SettingsHeader"),
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Margin = new Thickness(0, 0, 0, 8)
            };
            settingsHeader.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
            settingsPanel.Children.Add(settingsHeader);

            foreach (var spec in settings)
            {
                settingsPanel.Children.Add(BuildSettingRow(ext.Id, spec, store));
            }

            settingsBorder.Child = settingsPanel;
            rootPanel.Children.Add(settingsBorder);
        }

        card.Child = rootPanel;
        return card;
    }

    private Grid BuildSettingRow(string extensionId, ExtensionSettingSpec spec, ExtensionStore store)
    {
        var row = new Grid { Margin = new Thickness(0, 0, 0, 8) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(220) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var labelTb = new TextBlock
        {
            Text = spec.Label,
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 12,
            VerticalAlignment = VerticalAlignment.Center,
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 0, 12, 0)
        };
        labelTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
        Grid.SetColumn(labelTb, 0);
        row.Children.Add(labelTb);

        FrameworkElement editor;
        switch (spec.Type)
        {
            case ExtensionSettingType.Boolean:
                var cb = new CheckBox
                {
                    Style = (Style)FindResource("Win10ToggleSwitchStyle"),
                    IsChecked = store.GetSettingBool(extensionId, spec.Key),
                    VerticalAlignment = VerticalAlignment.Center
                };
                cb.Checked += (_, _) => store.SetSettingValue(extensionId, spec.Key, true);
                cb.Unchecked += (_, _) => store.SetSettingValue(extensionId, spec.Key, false);
                editor = cb;
                break;

            case ExtensionSettingType.Select:
                var combo = new ComboBox
                {
                    Style = (Style)FindResource("Win10ComboBoxStyle"),
                    MaxWidth = 260,
                    HorizontalAlignment = HorizontalAlignment.Left,
                    DisplayMemberPath = nameof(ExtensionSettingOption.Label),
                    SelectedValuePath = nameof(ExtensionSettingOption.Value),
                    ItemsSource = spec.Options ?? new List<ExtensionSettingOption>()
                };
                string currentVal = store.GetSettingString(extensionId, spec.Key);
                combo.SelectedValue = currentVal;
                if (combo.SelectedIndex < 0 && combo.Items.Count > 0)
                {
                    combo.SelectedIndex = 0;
                }
                combo.SelectionChanged += (_, _) =>
                {
                    if (combo.SelectedValue is string selected)
                    {
                        store.SetSettingValue(extensionId, spec.Key, selected);
                    }
                };
                editor = combo;
                break;

            default:
                var tb = new TextBox
                {
                    Style = (Style)FindResource("Win10TextBoxStyle"),
                    MaxWidth = 300,
                    Height = 32,
                    HorizontalAlignment = HorizontalAlignment.Stretch,
                    Text = store.GetSettingString(extensionId, spec.Key)
                };
                tb.LostFocus += (_, _) => store.SetSettingValue(extensionId, spec.Key, tb.Text);
                tb.KeyDown += (_, e) =>
                {
                    if (e.Key == Key.Enter)
                    {
                        store.SetSettingValue(extensionId, spec.Key, tb.Text);
                        Keyboard.ClearFocus();
                    }
                };
                editor = tb;
                break;
        }

        Grid.SetColumn(editor, 1);
        row.Children.Add(editor);
        return row;
    }

    private Border CreatePill(string text, bool isAccent = false, bool isWarning = false)
    {
        var pill = new Border
        {
            Margin = new Thickness(8, 0, 0, 0),
            Padding = new Thickness(6, 2, 6, 2),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(0),
            VerticalAlignment = VerticalAlignment.Center
        };
        pill.SetResourceReference(Border.BackgroundProperty, isWarning ? "WarningBannerBrush" : isAccent ? "AccentSubtleBrush" : "ControlFillBrush");
        pill.SetResourceReference(Border.BorderBrushProperty, isWarning ? "WarningTextBrush" : isAccent ? "AccentBrush" : "ControlBorderBrush");

        var tb = new TextBlock
        {
            Text = text,
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 10,
            FontWeight = FontWeights.SemiBold
        };
        tb.SetResourceReference(TextBlock.ForegroundProperty, isWarning ? "WarningTextBrush" : isAccent ? "AccentBrush" : "TextSecondaryBrush");
        pill.Child = tb;
        return pill;
    }

    private void OpenPermissionReview(string extensionId)
    {
        if (_ctx is null) return;
        var ext = _ctx.Services.Extensions.GetById(extensionId);
        if (ext is null) return;

        _reviewExtensionId = ext.Id;
        ReviewExtNameText.Text = ext.Manifest.Name;
        string author = string.IsNullOrWhiteSpace(ext.Manifest.Author)
            ? L.Get("Extensions_UnknownAuthor")
            : ext.Manifest.Author!;
        ReviewExtMetaText.Text = $"{L.Format("Extensions_AuthorVersion", ext.Manifest.Version, author)} · {ext.Id}";
        ReviewExtDescText.Text = ext.Manifest.Description ?? string.Empty;
        ReviewExtDescText.Visibility = string.IsNullOrWhiteSpace(ext.Manifest.Description)
            ? Visibility.Collapsed
            : Visibility.Visible;

        ReviewCapabilitiesPanel.Children.Clear();
        foreach (var cap in ext.Manifest.Capabilities)
        {
            var pill = CreatePill(FormatCapability(cap), isAccent: true);
            pill.Margin = new Thickness(0, 0, 6, 6);
            ReviewCapabilitiesPanel.Children.Add(pill);
        }

        ReviewHostsPanel.Children.Clear();
        if (ext.Manifest.Permissions.Hosts.Count == 0)
        {
            var noneTb = new TextBlock
            {
                Text = L.Get("Extensions_NoHosts"),
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 12
            };
            noneTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
            ReviewHostsPanel.Children.Add(noneTb);
        }
        else
        {
            foreach (string host in ext.Manifest.Permissions.Hosts)
            {
                var hostTb = new TextBlock
                {
                    Text = $"• {host}",
                    FontFamily = (FontFamily)FindResource("Win10Font"),
                    FontSize = 12,
                    Margin = new Thickness(0, 1, 0, 1)
                };
                hostTb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
                ReviewHostsPanel.Children.Add(hostTb);
            }
        }

        PermissionReviewOverlay.Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(PermissionReviewOverlay, PermissionReviewCard);
    }

    private void ClosePermissionReview()
    {
        _reviewExtensionId = null;
        PermissionReviewOverlay.Visibility = Visibility.Collapsed;
    }

    private void RefreshLogsDrawer()
    {
        if (_ctx is null || string.IsNullOrWhiteSpace(_activeLogExtensionId))
        {
            LogsDrawer.Visibility = Visibility.Collapsed;
            return;
        }

        var store = _ctx.Services.Extensions;
        var ext = store.GetById(_activeLogExtensionId);
        if (ext is null)
        {
            LogsDrawer.Visibility = Visibility.Collapsed;
            return;
        }

        LogsDrawer.Visibility = Visibility.Visible;
        LogsHeaderText.Text = L.Format("Extensions_LogsFor", ext.Manifest.Name);
        LogsListStack.Children.Clear();

        var entries = store.GetLogs(ext.Id);
        if (entries.Count == 0)
        {
            var emptyTb = new TextBlock
            {
                Text = L.Get("Extensions_LogsEmpty"),
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 12,
                Margin = new Thickness(0, 4, 0, 4)
            };
            emptyTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
            LogsListStack.Children.Add(emptyTb);
            return;
        }

        foreach (var entry in entries)
        {
            string ts = DateTimeOffset.FromUnixTimeMilliseconds(entry.TimestampMs).ToLocalTime().ToString("HH:mm:ss");
            string levelTag = entry.Level switch
            {
                ExtensionLogLevel.Warn => "WARN",
                ExtensionLogLevel.Error => "ERROR",
                _ => "INFO"
            };

            var line = new TextBlock
            {
                Text = $"[{ts}] [{levelTag}] {entry.Message}",
                FontFamily = new FontFamily("Consolas"),
                FontSize = 11.5,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(0, 1, 0, 2)
            };
            line.SetResourceReference(TextBlock.ForegroundProperty, entry.Level switch
            {
                ExtensionLogLevel.Error => "DangerBrush",
                ExtensionLogLevel.Warn => "WarningTextBrush",
                _ => "TextSecondaryBrush"
            });
            LogsListStack.Children.Add(line);
        }

        LogsScrollViewer.ScrollToEnd();
    }

    private static string FormatCapability(ExtensionCapability cap) => cap switch
    {
        ExtensionCapability.Playlist => L.Get("Extensions_Capability_Playlist"),
        ExtensionCapability.Tree => L.Get("Extensions_Capability_Tree"),
        ExtensionCapability.Stream => L.Get("Extensions_Capability_Stream"),
        ExtensionCapability.Cookie => L.Get("Extensions_Capability_Cookie"),
        ExtensionCapability.Proxy => L.Get("Extensions_Capability_Proxy"),
        _ => cap.ToString()
    };

    private void ConfirmUninstall(InstalledExtension ext)
    {
        if (_ctx is null) return;
        _ctx.OpenConfirm(
            L.Format("Extensions_UninstallConfirmTitle", ext.Manifest.Name),
            L.Get("Extensions_UninstallConfirmMessage"),
            L.Get("Extensions_Uninstall"),
            () => _ctx.Services.Extensions.Uninstall(ext.Id),
            true);
    }

    private async void ImportFile_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _isBusy) return;
        var dlg = new OpenFileDialog
        {
            Title = L.Get("Extensions_ImportDialogTitle"),
            Filter = L.Get("Extensions_ImportDialogFilter")
        };
        if (dlg.ShowDialog() != true) return;

        await RunImportAsync(() => _ctx.Services.Extensions.ImportFromFileAsync(dlg.FileName));
    }

    private async void ImportUrl_Click(object sender, RoutedEventArgs e) => await ImportUrlAsync();

    private async Task ImportUrlAsync()
    {
        if (_ctx is null || _isBusy) return;
        string url = UrlImportBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(url)) return;

        var imported = await RunImportAsync(() => _ctx.Services.Extensions.ImportFromUrlAsync(url));
        if (imported is not null)
        {
            UrlImportBox.Text = string.Empty;
        }
    }

    private async Task<InstalledExtension?> RunImportAsync(Func<Task<InstalledExtension>> importer)
    {
        SetBusy(true);
        ErrorBanner.Visibility = Visibility.Collapsed;
        try
        {
            var ext = await importer();
            Refresh();
            OpenPermissionReview(ext.Id);
            return ext;
        }
        catch (Exception ex)
        {
            ErrorBannerText.Text = $"{L.Get("Extensions_ImportFailedTitle")}: {ex.Message}";
            ErrorBanner.Visibility = Visibility.Visible;
            return null;
        }
        finally
        {
            SetBusy(false);
        }
    }

    private void SetBusy(bool busy)
    {
        _isBusy = busy;
        BusyBanner.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        ImportFileButton.IsEnabled = !busy;
        UrlImportBox.IsEnabled = !busy;
        ImportUrlButton.IsEnabled = !busy && !string.IsNullOrWhiteSpace(UrlImportBox.Text);
    }

    private void UrlImportBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (!_isBusy)
        {
            ImportUrlButton.IsEnabled = !string.IsNullOrWhiteSpace(UrlImportBox.Text);
        }
    }

    private async void UrlImportBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && ImportUrlButton.IsEnabled)
        {
            e.Handled = true;
            await ImportUrlAsync();
        }
    }

    private void DismissError_Click(object sender, RoutedEventArgs e) =>
        ErrorBanner.Visibility = Visibility.Collapsed;

    private void ApproveReview_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || string.IsNullOrWhiteSpace(_reviewExtensionId)) return;
        string id = _reviewExtensionId;
        ClosePermissionReview();
        _ctx.Services.Extensions.ApproveAndEnable(id);
    }

    private void KeepDisabledReview_Click(object sender, RoutedEventArgs e) => ClosePermissionReview();

    private void ReviewOverlay_MouseDown(object sender, MouseButtonEventArgs e) => ClosePermissionReview();

    private void ReviewCard_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;

    private void ClearLogs_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || string.IsNullOrWhiteSpace(_activeLogExtensionId)) return;
        _ctx.Services.Extensions.ClearLogs(_activeLogExtensionId);
    }

    private void CloseLogs_Click(object sender, RoutedEventArgs e)
    {
        _activeLogExtensionId = null;
        Refresh();
    }
}
