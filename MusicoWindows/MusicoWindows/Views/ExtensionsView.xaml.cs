using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
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
    private bool _isUpdateReview;
    private List<(string ExtensionId, CheckBox Check)> _batchReviewItems = new();
    private string? _qrExtensionId;
    private CancellationTokenSource? _qrLoginCts;
    private string? _activeLogExtensionId;
    private bool _isBusy;
    private bool _suppressAutoUpdateToggle;

    public ExtensionsView()
    {
        InitializeComponent();
        Unloaded += (_, _) => Detach();
    }

    public bool IsReviewOpen =>
        PermissionReviewOverlay.Visibility == Visibility.Visible ||
        QrLoginOverlay.Visibility == Visibility.Visible;

    public void Bind(ViewContext ctx)
    {
        Detach();
        _ctx = ctx;
        _ctx.Services.Extensions.Changed += OnExtensionsChanged;
        _ctx.Services.Extensions.UpdatesChanged += OnUpdatesChanged;
        _ctx.Services.Extensions.LogsChanged += OnLogsChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        Refresh();
    }

    public bool HandleEscape()
    {
        if (QrLoginOverlay.Visibility == Visibility.Visible)
        {
            CloseQrLoginModal();
            return true;
        }
        if (PermissionReviewOverlay.Visibility == Visibility.Visible)
        {
            ClosePermissionReview();
            return true;
        }
        return false;
    }

    private void Detach()
    {
        CancelQrLogin();
        QrLoginOverlay.Visibility = Visibility.Collapsed;
        _qrExtensionId = null;
        LocalizationManager.LanguageChanged -= OnLanguageChanged;
        if (_ctx is null) return;
        _ctx.Services.Extensions.Changed -= OnExtensionsChanged;
        _ctx.Services.Extensions.UpdatesChanged -= OnUpdatesChanged;
        _ctx.Services.Extensions.LogsChanged -= OnLogsChanged;
    }

    private void OnLanguageChanged(ResolvedLanguage _) => Refresh();

    private void OnExtensionsChanged() => Dispatcher.BeginInvoke(Refresh);

    private void OnUpdatesChanged() => Dispatcher.BeginInvoke(Refresh);

    private void OnLogsChanged(string extensionId)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (string.Equals(_activeLogExtensionId, extensionId, StringComparison.OrdinalIgnoreCase))
            {
                RefreshLogsDrawer();
            }
            if (IsKeyboardFocusInsideExtensionsList())
            {
                return;
            }
            Refresh();
        });
    }

    private bool IsKeyboardFocusInsideExtensionsList()
    {
        if (Keyboard.FocusedElement is DependencyObject focused)
        {
            DependencyObject? cur = focused;
            while (cur is not null)
            {
                if (ReferenceEquals(cur, ExtensionsStack))
                {
                    return true;
                }
                cur = VisualTreeHelper.GetParent(cur);
            }
        }
        return false;
    }

    private void Refresh()
    {
        if (_ctx is null) return;
        var store = _ctx.Services.Extensions;
        var extensions = store.Extensions;

        _suppressAutoUpdateToggle = true;
        try
        {
            AutoUpdateToggle.IsChecked = store.AutoUpdateEnabled;
        }
        finally
        {
            _suppressAutoUpdateToggle = false;
        }
        CheckUpdatesButton.IsEnabled = !_isBusy && extensions.Count > 0;

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

        var pendingUpdate = store.GetPendingUpdate(ext.Id);
        if (pendingUpdate is not null)
        {
            titleRow.Children.Add(CreatePill(
                L.Format("Extensions_UpdateAvailableBadge", pendingUpdate.NewVersion),
                isWarning: true));
        }

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

        if (pendingUpdate is not null)
        {
            var reviewUpdateBtn = new Button
            {
                Style = (Style)FindResource("Win10PrimaryButtonStyle"),
                Content = L.Get("Extensions_ReviewUpdateButton"),
                Margin = new Thickness(0, 0, 8, 0)
            };
            reviewUpdateBtn.Click += (_, _) => OpenUpdateReview(ext.Id);
            actions.Children.Add(reviewUpdateBtn);
        }
        else if (ExtensionStore.ResolveUpdateUrl(ext) is not null)
        {
            var updateBtn = new Button
            {
                Style = (Style)FindResource("Win10ButtonStyle"),
                Content = L.Get("Extensions_UpdateButton"),
                Margin = new Thickness(0, 0, 8, 0),
                IsEnabled = !_isBusy
            };
            updateBtn.Click += async (_, _) => await CheckSingleExtensionUpdateAsync(ext);
            actions.Children.Add(updateBtn);
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
        var effectiveHosts = ext.Manifest.GetEffectiveAllowedHosts(key => store.GetSettingJson(ext.Id, key));
        string hostsText = effectiveHosts.Count > 0
            ? string.Join(", ", effectiveHosts)
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

        if (ExtensionStore.ResolveUpdateUrl(ext) is string updateUrl)
        {
            var updateUrlTb = new TextBlock
            {
                Text = L.Format("Extensions_UpdateFromUrlLabel", updateUrl),
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 11,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Margin = new Thickness(44, 3, 0, 0),
                ToolTip = updateUrl
            };
            updateUrlTb.SetResourceReference(TextBlock.ForegroundProperty, "TextTertiaryBrush");
            rootPanel.Children.Add(updateUrlTb);
        }

        // Extension Auth Section (Cookie editor and/or QR code login)
        if (ext.Manifest.SupportsCookieAuth)
        {
            rootPanel.Children.Add(BuildCookieSection(ext, store));
        }

        // Dynamic Settings & Audio Quality Editor
        var qualities = ext.Manifest.EffectiveQualities;
        var settings = ext.Manifest.Settings;
        if (qualities.Count > 0 || settings is { Count: > 0 })
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

            if (qualities.Count > 0)
            {
                string? currentStream = store.GetStreamQualityId(ext.Id) ?? ext.Manifest.EffectiveDefaultStreamQualityId;
                settingsPanel.Children.Add(BuildQualitySettingRow(
                    L.Get("Quality_StreamLabel"),
                    qualities,
                    currentStream,
                    selected => store.SetStreamQualityId(ext.Id, selected)));

                string? currentDownload = store.GetDownloadQualityId(ext.Id) ?? ext.Manifest.EffectiveDefaultDownloadQualityId;
                settingsPanel.Children.Add(BuildQualitySettingRow(
                    L.Get("Quality_DownloadLabel"),
                    qualities,
                    currentDownload,
                    selected => store.SetDownloadQualityId(ext.Id, selected)));
            }

            if (settings is { Count: > 0 })
            {
                foreach (var spec in settings)
                {
                    settingsPanel.Children.Add(BuildSettingRow(ext.Id, spec, store));
                }
            }

            settingsBorder.Child = settingsPanel;
            rootPanel.Children.Add(settingsBorder);
        }

        card.Child = rootPanel;
        return card;
    }

    private Border BuildCookieSection(InstalledExtension ext, ExtensionStore store)
    {
        var border = new Border
        {
            Margin = new Thickness(44, 12, 0, 0),
            BorderThickness = new Thickness(0, 1, 0, 0),
            Padding = new Thickness(0, 10, 0, 0)
        };
        border.SetResourceReference(Border.BorderBrushProperty, "DividerBrush");

        var panel = new StackPanel();
        var row = new Grid();
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(220) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var labelTb = new TextBlock
        {
            Text = L.Get("Extensions_CookieLabel"),
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 12,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 12, 0)
        };
        labelTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
        Grid.SetColumn(labelTb, 0);
        row.Children.Add(labelTb);

        string currentCookie = store.GetCookie(ext.Id) ?? string.Empty;
        var passwordBox = new PasswordBox
        {
            Style = (Style)FindResource("Win10PasswordBoxStyle"),
            Height = 32,
            MaxWidth = 360,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            Password = currentCookie
        };
        passwordBox.ToolTip = L.Get("Extensions_CookiePlaceholder");

        passwordBox.LostFocus += (_, _) => store.SetCookie(ext.Id, passwordBox.Password);
        passwordBox.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter)
            {
                e.Handled = true;
                store.SetCookie(ext.Id, passwordBox.Password);
                Keyboard.ClearFocus();
            }
        };

        Grid.SetColumn(passwordBox, 1);
        row.Children.Add(passwordBox);

        var clearBtn = new Button
        {
            Style = (Style)FindResource("Win10ButtonStyle"),
            Content = L.Get("Extensions_CookieClear"),
            Margin = new Thickness(8, 0, 0, 0),
            IsEnabled = !string.IsNullOrEmpty(currentCookie)
        };
        clearBtn.Click += (_, _) =>
        {
            passwordBox.Password = string.Empty;
            store.SetCookie(ext.Id, null);
        };
        passwordBox.PasswordChanged += (_, _) =>
        {
            clearBtn.IsEnabled = !string.IsNullOrEmpty(passwordBox.Password);
        };
        Grid.SetColumn(clearBtn, 2);
        row.Children.Add(clearBtn);

        panel.Children.Add(row);

        var hintTb = new TextBlock
        {
            Text = L.Get("Extensions_CookieSavedHint"),
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 11,
            Margin = new Thickness(220, 4, 0, 0)
        };
        hintTb.SetResourceReference(TextBlock.ForegroundProperty, "TextTertiaryBrush");
        panel.Children.Add(hintTb);

        if (ext.Manifest.SupportsQrLogin || ext.Manifest.SupportsWebLogin || ext.Manifest.SupportsCookieAuth)
        {
            var actionsPanel = new WrapPanel { Margin = new Thickness(220, 8, 0, 0) };

            if (ext.Manifest.SupportsQrLogin)
            {
                var qrBtn = new Button
                {
                    Style = (Style)FindResource("Win10ButtonStyle"),
                    Content = L.Get("Extensions_SignInQr"),
                    Margin = new Thickness(0, 0, 8, 4),
                    IsEnabled = ext.IsEnabled,
                    ToolTip = ext.IsEnabled ? null : L.Get("Extensions_QrLogin_EnableFirst")
                };
                qrBtn.Click += (_, _) => OpenQrLoginModal(ext);
                actionsPanel.Children.Add(qrBtn);
            }

            if (ext.Manifest.SupportsWebLogin && ext.Manifest.WebLogin is { } webLoginConfig)
            {
                var webBtn = new Button
                {
                    Style = (Style)FindResource("Win10ButtonStyle"),
                    Content = L.Get("Extensions_SignInWeb"),
                    Margin = new Thickness(0, 0, 8, 4)
                };
                webBtn.Click += (_, _) => OpenWebLoginWindow(ext, webLoginConfig, store);
                actionsPanel.Children.Add(webBtn);
            }

            var importCookieBtn = new Button
            {
                Style = (Style)FindResource("Win10ButtonStyle"),
                Content = L.Get("Extensions_ImportCookiesFile"),
                Margin = new Thickness(0, 0, 8, 4)
            };
            importCookieBtn.Click += (_, _) => ImportCookiesFileForExtension(ext, store, passwordBox);
            actionsPanel.Children.Add(importCookieBtn);

            panel.Children.Add(actionsPanel);
        }

        border.Child = panel;
        return border;
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

            case ExtensionSettingType.ServerUrl:
                var serverStack = new StackPanel { MaxWidth = 300, HorizontalAlignment = HorizontalAlignment.Stretch };
                string initialServerUrl = store.GetSettingString(extensionId, spec.Key);
                var serverTb = new TextBox
                {
                    Style = (Style)FindResource("Win10TextBoxStyle"),
                    Height = 32,
                    HorizontalAlignment = HorizontalAlignment.Stretch,
                    Text = initialServerUrl
                };
                var hintTb = new TextBlock
                {
                    FontFamily = (FontFamily)FindResource("Win10Font"),
                    FontSize = 11,
                    TextWrapping = TextWrapping.Wrap,
                    Margin = new Thickness(0, 4, 0, 0),
                    Text = FormatServerUrlHint(initialServerUrl)
                };
                hintTb.SetResourceReference(TextBlock.ForegroundProperty, "TextTertiaryBrush");
                serverTb.TextChanged += (_, _) => hintTb.Text = FormatServerUrlHint(serverTb.Text);
                serverTb.LostFocus += (_, _) => store.SetSettingValue(extensionId, spec.Key, serverTb.Text);
                serverTb.KeyDown += (_, e) =>
                {
                    if (e.Key == Key.Enter)
                    {
                        store.SetSettingValue(extensionId, spec.Key, serverTb.Text);
                        Keyboard.ClearFocus();
                    }
                };
                serverStack.Children.Add(serverTb);
                serverStack.Children.Add(hintTb);
                editor = serverStack;
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

    private sealed record QualityChoiceItem(string Id, string DisplayLabel);

    private Grid BuildQualitySettingRow(
        string label,
        IReadOnlyList<ExtensionQualityOption> qualities,
        string? currentValue,
        Action<string> onChanged)
    {
        var row = new Grid { Margin = new Thickness(0, 0, 0, 8) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(220) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var labelTb = new TextBlock
        {
            Text = label,
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 12,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 12, 0)
        };
        labelTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
        Grid.SetColumn(labelTb, 0);
        row.Children.Add(labelTb);

        var items = qualities
            .Select(q => new QualityChoiceItem(
                q.Id,
                !string.IsNullOrWhiteSpace(q.Badge) ? $"{q.Label} ({q.Badge})" : q.Label))
            .ToList();

        var combo = new ComboBox
        {
            Style = (Style)FindResource("Win10ComboBoxStyle"),
            MaxWidth = 260,
            HorizontalAlignment = HorizontalAlignment.Left,
            DisplayMemberPath = nameof(QualityChoiceItem.DisplayLabel),
            SelectedValuePath = nameof(QualityChoiceItem.Id),
            ItemsSource = items
        };
        if (!string.IsNullOrWhiteSpace(currentValue))
        {
            combo.SelectedValue = currentValue;
        }
        if (combo.SelectedIndex < 0 && items.Count > 0)
        {
            combo.SelectedIndex = 0;
        }
        combo.SelectionChanged += (_, _) =>
        {
            if (combo.SelectedValue is string selected)
            {
                onChanged(selected);
            }
        };

        Grid.SetColumn(combo, 1);
        row.Children.Add(combo);
        return row;
    }

    private static string FormatServerUrlHint(string? rawServerUrl)
    {
        string? host = HostAllowlistMatcher.ExtractApprovedHost(rawServerUrl);
        return host is not null
            ? L.Format("Extensions_ServerUrlAllowedHost", host)
            : L.Get("Extensions_ServerUrlHint");
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

        _isUpdateReview = false;
        _reviewExtensionId = ext.Id;
        _batchReviewItems.Clear();
        SingleReviewContent.Visibility = Visibility.Visible;
        BatchReviewPanel.Visibility = Visibility.Collapsed;
        BatchReviewPanel.Children.Clear();
        ReviewPrimaryButton.IsEnabled = true;
        ReviewTitleText.Text = (string)FindResource("Str_Extensions_ReviewTitle");
        ReviewSubtitleText.Text = (string)FindResource("Str_Extensions_ReviewSubtitle");
        ReviewPrimaryButton.Content = (string)FindResource("Str_Extensions_ApproveEnable");
        ReviewSecondaryButton.Content = (string)FindResource("Str_Extensions_KeepDisabled");
        ReviewEscalationSection.Visibility = Visibility.Collapsed;
        ReviewNewPermissionsPanel.Children.Clear();

        ReviewExtNameText.Text = ext.Manifest.Name;
        string author = string.IsNullOrWhiteSpace(ext.Manifest.Author)
            ? L.Get("Extensions_UnknownAuthor")
            : ext.Manifest.Author!;
        ReviewExtMetaText.Text = $"{L.Format("Extensions_AuthorVersion", ext.Manifest.Version, author)} · {ext.Id}";
        ReviewExtDescText.Text = ext.Manifest.Description ?? string.Empty;
        ReviewExtDescText.Visibility = string.IsNullOrWhiteSpace(ext.Manifest.Description)
            ? Visibility.Collapsed
            : Visibility.Visible;

        PopulateReviewPermissions(ext.Manifest);

        PermissionReviewOverlay.Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(PermissionReviewOverlay, PermissionReviewCard);
    }

    private void OpenUpdateReview(string extensionId)
    {
        if (_ctx is null) return;
        var store = _ctx.Services.Extensions;
        var pending = store.GetPendingUpdate(extensionId);
        if (pending is null) return;

        _isUpdateReview = true;
        _reviewExtensionId = pending.ExtensionId;
        _batchReviewItems.Clear();
        SingleReviewContent.Visibility = Visibility.Visible;
        BatchReviewPanel.Visibility = Visibility.Collapsed;
        BatchReviewPanel.Children.Clear();
        ReviewPrimaryButton.IsEnabled = true;
        var nextManifest = pending.Package.Manifest;

        ReviewTitleText.Text = L.Get("Extensions_ReviewUpdateTitle");
        ReviewSubtitleText.Text = L.Format("Extensions_ReviewUpdateSubtitle", pending.InstalledVersion, pending.NewVersion);
        ReviewPrimaryButton.Content = L.Get("Extensions_ApproveUpdate");
        ReviewSecondaryButton.Content = (string)FindResource("Str_Common_Cancel");

        ReviewExtNameText.Text = nextManifest.Name;
        string author = string.IsNullOrWhiteSpace(nextManifest.Author)
            ? L.Get("Extensions_UnknownAuthor")
            : nextManifest.Author!;
        ReviewExtMetaText.Text = $"{L.Format("Extensions_AuthorVersion", pending.NewVersion, author)} · {pending.ExtensionId}";
        ReviewExtDescText.Text = nextManifest.Description ?? string.Empty;
        ReviewExtDescText.Visibility = string.IsNullOrWhiteSpace(nextManifest.Description)
            ? Visibility.Collapsed
            : Visibility.Visible;

        ReviewNewPermissionsPanel.Children.Clear();
        if (pending.HasPermissionEscalation)
        {
            ReviewEscalationSection.Visibility = Visibility.Visible;
            ReviewNewPermissionsHeader.Text = L.Get("Extensions_NewPermissionsLabel");
            foreach (var cap in pending.AddedCapabilities)
            {
                var tb = new TextBlock
                {
                    Text = $"+ {FormatCapability(cap)}",
                    FontFamily = (FontFamily)FindResource("Win10Font"),
                    FontSize = 12,
                    FontWeight = FontWeights.SemiBold,
                    Margin = new Thickness(0, 1, 0, 1)
                };
                tb.SetResourceReference(TextBlock.ForegroundProperty, "WarningTextBrush");
                ReviewNewPermissionsPanel.Children.Add(tb);
            }
            foreach (string host in pending.AddedHosts)
            {
                var tb = new TextBlock
                {
                    Text = $"+ {host}",
                    FontFamily = (FontFamily)FindResource("Win10Font"),
                    FontSize = 12,
                    FontWeight = FontWeights.SemiBold,
                    Margin = new Thickness(0, 1, 0, 1)
                };
                tb.SetResourceReference(TextBlock.ForegroundProperty, "WarningTextBrush");
                ReviewNewPermissionsPanel.Children.Add(tb);
            }
        }
        else
        {
            ReviewEscalationSection.Visibility = Visibility.Collapsed;
        }

        PopulateReviewPermissions(nextManifest);

        PermissionReviewOverlay.Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(PermissionReviewOverlay, PermissionReviewCard);
    }

    private void PopulateReviewPermissions(ExtensionManifest manifest)
    {
        ReviewCapabilitiesPanel.Children.Clear();
        foreach (var cap in manifest.Capabilities)
        {
            var pill = CreatePill(FormatCapability(cap), isAccent: true);
            pill.Margin = new Thickness(0, 0, 6, 6);
            ReviewCapabilitiesPanel.Children.Add(pill);
        }

        ReviewHostsPanel.Children.Clear();
        if (manifest.Permissions.Hosts.Count == 0)
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
            foreach (string host in manifest.Permissions.Hosts)
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
    }

    private void OpenBatchPermissionReview(IReadOnlyList<InstalledExtension> imported)
    {
        if (_ctx is null || imported.Count == 0) return;

        _isUpdateReview = false;
        _reviewExtensionId = null;
        _batchReviewItems.Clear();
        SingleReviewContent.Visibility = Visibility.Collapsed;
        BatchReviewPanel.Visibility = Visibility.Visible;
        BatchReviewPanel.Children.Clear();

        ReviewTitleText.Text = L.Format("Extensions_ReviewBatchTitle", imported.Count);
        ReviewSubtitleText.Text = L.Get("Extensions_ReviewBatchSubtitle");
        ReviewSecondaryButton.Content = (string)FindResource("Str_Extensions_KeepDisabled");

        foreach (InstalledExtension ext in imported)
        {
            var card = new Border
            {
                BorderThickness = new Thickness(1),
                CornerRadius = new CornerRadius(0),
                Padding = new Thickness(12, 10, 12, 10),
                Margin = new Thickness(0, 0, 0, 10)
            };
            card.SetResourceReference(Border.BackgroundProperty, "CardBackgroundBrush");
            card.SetResourceReference(Border.BorderBrushProperty, "ControlBorderBrush");

            var stack = new StackPanel();
            var check = new CheckBox
            {
                Style = (Style)FindResource("Win10CheckBoxStyle"),
                Content = $"{ext.Manifest.Name} (v{ext.Manifest.Version})",
                IsChecked = true,
                FontWeight = FontWeights.SemiBold
            };
            check.Checked += (_, _) => UpdateBatchReviewButton();
            check.Unchecked += (_, _) => UpdateBatchReviewButton();
            stack.Children.Add(check);

            string caps = string.Join(", ", ext.Manifest.Capabilities.Select(FormatCapability));
            var capTb = new TextBlock
            {
                Text = caps,
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 11.5,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(28, 4, 0, 0)
            };
            capTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
            stack.Children.Add(capTb);

            string hosts = ext.Manifest.Permissions.Hosts.Count == 0
                ? L.Get("Extensions_NoHosts")
                : string.Join(", ", ext.Manifest.Permissions.Hosts);
            string hostsLabel = TryFindResource("Str_Extensions_HostsLabel") as string ?? "Allowed Network Hosts";
            var hostsTb = new TextBlock
            {
                Text = $"{hostsLabel}: {hosts}",
                FontFamily = (FontFamily)FindResource("Win10Font"),
                FontSize = 11,
                TextWrapping = TextWrapping.Wrap,
                Margin = new Thickness(28, 2, 0, 0)
            };
            hostsTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
            stack.Children.Add(hostsTb);

            card.Child = stack;
            BatchReviewPanel.Children.Add(card);
            _batchReviewItems.Add((ext.Id, check));
        }

        UpdateBatchReviewButton();
        PermissionReviewOverlay.Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(PermissionReviewOverlay, PermissionReviewCard);
    }

    private void UpdateBatchReviewButton()
    {
        if (_batchReviewItems.Count == 0) return;
        int selectedCount = _batchReviewItems.Count(item => item.Check.IsChecked == true);
        ReviewPrimaryButton.Content = L.Format("Extensions_ApproveEnableSelected", selectedCount);
        ReviewPrimaryButton.IsEnabled = selectedCount > 0;
    }

    private void ClosePermissionReview()
    {
        _reviewExtensionId = null;
        _isUpdateReview = false;
        _batchReviewItems.Clear();
        BatchReviewPanel.Children.Clear();
        BatchReviewPanel.Visibility = Visibility.Collapsed;
        SingleReviewContent.Visibility = Visibility.Visible;
        ReviewPrimaryButton.IsEnabled = true;
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
        ExtensionCapability.Lyrics => L.Get("Extensions_Capability_Lyrics"),
        ExtensionCapability.Search => L.Get("Extensions_Capability_Search"),
        ExtensionCapability.QrLogin => L.Get("Extensions_Capability_QrLogin"),
        ExtensionCapability.WebLogin => L.Get("Extensions_Capability_WebLogin"),
        _ => cap.ToString()
    };

    private void OpenWebLoginWindow(InstalledExtension ext, ExtensionWebLoginConfig config, ExtensionStore store)
    {
        var win = new ExtensionWebLoginWindow(ext, config, store)
        {
            Owner = Window.GetWindow(this)
        };
        win.ShowDialog();
        RefreshExtensions();
    }

    private void ImportCookiesFileForExtension(InstalledExtension ext, ExtensionStore store, PasswordBox passwordBox)
    {
        var dlg = new OpenFileDialog
        {
            Title = L.Get("Extensions_ImportCookiesFile"),
            Filter = "Cookie Files (*.txt)|*.txt|All Files (*.*)|*.*",
            Multiselect = false
        };
        if (dlg.ShowDialog() != true) return;
        try
        {
            string text = File.ReadAllText(dlg.FileName);
            IReadOnlyList<string> domains = ext.Manifest.WebLogin?.CookieDomains is { Count: > 0 } wlDomains
                ? wlDomains
                : (ext.Manifest.Permissions?.Hosts ?? []);
            string? parsed = NetscapeCookieFileParser.ParseCookieHeader(text, domains);
            if (string.IsNullOrWhiteSpace(parsed))
            {
                MessageBox.Show(
                    L.Get("Extensions_CookieFileEmptyError"),
                    L.Get("Extensions_CookieImportFailedTitle"),
                    MessageBoxButton.OK,
                    MessageBoxImage.Warning);
                return;
            }
            store.SetCookie(ext.Id, parsed);
            passwordBox.Password = parsed;
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                ex.Message,
                L.Get("Extensions_CookieImportFailedTitle"),
                MessageBoxButton.OK,
                MessageBoxImage.Warning);
        }
    }

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
            Filter = L.Get("Extensions_ImportDialogFilter"),
            Multiselect = true
        };
        if (dlg.ShowDialog() != true || dlg.FileNames.Length == 0) return;

        await RunImportAsync(() => _ctx.Services.Extensions.ImportPackagesFromFilesAsync(dlg.FileNames));
    }

    private async void ImportUrl_Click(object sender, RoutedEventArgs e) => await ImportUrlAsync();

    private async Task ImportUrlAsync()
    {
        if (_ctx is null || _isBusy) return;
        string url = UrlImportBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(url)) return;

        var imported = await RunImportAsync(() => _ctx.Services.Extensions.ImportPackagesFromUrlAsync(url));
        if (imported is { Count: > 0 })
        {
            UrlImportBox.Text = string.Empty;
        }
    }

    private async Task<IReadOnlyList<InstalledExtension>?> RunImportAsync(Func<Task<IReadOnlyList<InstalledExtension>>> importer)
    {
        SetBusy(true);
        ErrorBanner.Visibility = Visibility.Collapsed;
        try
        {
            IReadOnlyList<InstalledExtension> imported = await importer();
            Refresh();
            if (imported.Count == 1)
            {
                OpenPermissionReview(imported[0].Id);
            }
            else if (imported.Count > 1)
            {
                OpenBatchPermissionReview(imported);
            }
            return imported;
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
        AutoUpdateToggle.IsEnabled = !busy;
        CheckUpdatesButton.IsEnabled = !busy && (_ctx?.Services.Extensions.Extensions.Count ?? 0) > 0;
    }

    private void AutoUpdateToggle_Changed(object sender, RoutedEventArgs e)
    {
        if (_suppressAutoUpdateToggle || _ctx is null) return;
        _ctx.Services.Extensions.AutoUpdateEnabled = AutoUpdateToggle.IsChecked == true;
    }

    private async void CheckUpdates_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _isBusy) return;
        SetBusy(true);
        ErrorBanner.Visibility = Visibility.Collapsed;
        UpdateStatusBannerText.Text = L.Get("Extensions_CheckingUpdates");
        UpdateStatusBanner.Visibility = Visibility.Visible;
        try
        {
            var summary = await _ctx.Services.Extensions.CheckForUpdatesAsync();
            Refresh();

            int applied = summary.Results.Count(r => r.Outcome == ExtensionUpdateOutcome.AppliedSilently);
            int staged = summary.Results.Count(r => r.Outcome == ExtensionUpdateOutcome.StagedForReview);
            var failed = summary.Results.FirstOrDefault(r => r.Outcome == ExtensionUpdateOutcome.Failed);

            if (staged > 0)
            {
                UpdateStatusBannerText.Text = L.Format("Extensions_UpdatesReviewNeededSummary", staged);
                var firstStaged = summary.Results.First(r => r.Outcome == ExtensionUpdateOutcome.StagedForReview);
                OpenUpdateReview(firstStaged.ExtensionId);
            }
            else if (applied > 0)
            {
                UpdateStatusBannerText.Text = L.Format("Extensions_UpdatesAppliedSummary", applied);
            }
            else if (failed is not null)
            {
                UpdateStatusBanner.Visibility = Visibility.Collapsed;
                ErrorBannerText.Text = L.Format("Extensions_UpdateFailedBanner", failed.ErrorMessage ?? "Error");
                ErrorBanner.Visibility = Visibility.Visible;
            }
            else
            {
                UpdateStatusBannerText.Text = L.Get("Extensions_AllUpToDate");
            }
        }
        catch (Exception ex)
        {
            UpdateStatusBanner.Visibility = Visibility.Collapsed;
            ErrorBannerText.Text = L.Format("Extensions_UpdateFailedBanner", ex.Message);
            ErrorBanner.Visibility = Visibility.Visible;
        }
        finally
        {
            SetBusy(false);
            Refresh();
        }
    }

    private async Task CheckSingleExtensionUpdateAsync(InstalledExtension ext)
    {
        if (_ctx is null || _isBusy) return;
        SetBusy(true);
        ErrorBanner.Visibility = Visibility.Collapsed;
        UpdateStatusBannerText.Text = L.Get("Extensions_CheckingUpdates");
        UpdateStatusBanner.Visibility = Visibility.Visible;
        try
        {
            var summary = await _ctx.Services.Extensions.CheckForUpdatesAsync(extensionId: ext.Id);
            Refresh();
            var result = summary.Results.FirstOrDefault();
            if (result is null)
            {
                UpdateStatusBanner.Visibility = Visibility.Collapsed;
            }
            else if (result.Outcome == ExtensionUpdateOutcome.StagedForReview)
            {
                UpdateStatusBannerText.Text = L.Format("Extensions_UpdatesReviewNeededSummary", 1);
                OpenUpdateReview(ext.Id);
            }
            else if (result.Outcome == ExtensionUpdateOutcome.AppliedSilently)
            {
                UpdateStatusBannerText.Text = L.Format(
                    "Extensions_UpdatedSingle",
                    ext.Manifest.Name,
                    result.RemoteVersion ?? string.Empty);
            }
            else if (result.Outcome == ExtensionUpdateOutcome.Failed)
            {
                UpdateStatusBanner.Visibility = Visibility.Collapsed;
                ErrorBannerText.Text = L.Format("Extensions_UpdateFailedBanner", result.ErrorMessage ?? "Error");
                ErrorBanner.Visibility = Visibility.Visible;
            }
            else
            {
                UpdateStatusBannerText.Text = L.Format("Extensions_UpToDateSingle", ext.Manifest.Name);
            }
        }
        catch (Exception ex)
        {
            UpdateStatusBanner.Visibility = Visibility.Collapsed;
            ErrorBannerText.Text = L.Format("Extensions_UpdateFailedBanner", ex.Message);
            ErrorBanner.Visibility = Visibility.Visible;
        }
        finally
        {
            SetBusy(false);
            Refresh();
        }
    }

    private void DismissUpdateStatus_Click(object sender, RoutedEventArgs e) =>
        UpdateStatusBanner.Visibility = Visibility.Collapsed;

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
        if (_ctx is null) return;
        if (_batchReviewItems.Count > 0)
        {
            var selectedIds = _batchReviewItems
                .Where(item => item.Check.IsChecked == true)
                .Select(item => item.ExtensionId)
                .ToList();
            ClosePermissionReview();
            if (selectedIds.Count > 0)
            {
                _ctx.Services.Extensions.ApproveAndEnableBatch(selectedIds);
            }
            return;
        }

        if (string.IsNullOrWhiteSpace(_reviewExtensionId)) return;
        string id = _reviewExtensionId;
        bool isUpdate = _isUpdateReview;
        ClosePermissionReview();
        if (isUpdate)
        {
            _ctx.Services.Extensions.ApprovePendingUpdate(id);
            UpdateStatusBanner.Visibility = Visibility.Collapsed;
        }
        else
        {
            _ctx.Services.Extensions.ApproveAndEnable(id);
        }
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

    private void OpenQrLoginModal(InstalledExtension ext)
    {
        _qrExtensionId = ext.Id;
        QrSubtitleText.Text = L.Format("Extensions_QrLogin_Subtitle", ext.Manifest.Name);
        QrLoginOverlay.Visibility = Visibility.Visible;
        _ = StartQrSessionAsync(ext.Id);
    }

    private void CloseQrLoginModal()
    {
        CancelQrLogin();
        _qrExtensionId = null;
        QrCodeImage.Source = null;
        QrLoginOverlay.Visibility = Visibility.Collapsed;
    }

    private void CancelQrLogin()
    {
        CancellationTokenSource? cts = _qrLoginCts;
        _qrLoginCts = null;
        if (cts is not null)
        {
            try
            {
                cts.Cancel();
            }
            catch
            {
                // Ignore cancellation exceptions.
            }
            cts.Dispose();
        }
    }

    private async Task StartQrSessionAsync(string extensionId)
    {
        if (_ctx is null) return;

        CancelQrLogin();
        var cts = new CancellationTokenSource();
        _qrLoginCts = cts;
        CancellationToken ct = cts.Token;

        QrCodeImage.Source = null;
        QrLoadingPlaceholderText.Text = L.Get("Extensions_QrLogin_Loading");
        QrLoadingPlaceholderText.Visibility = Visibility.Visible;
        QrStatusText.Text = L.Get("Extensions_QrLogin_Loading");
        UpdateQrMessage(null);
        QrRefreshButton.IsEnabled = false;

        try
        {
            ExtQrLoginSession session = await _ctx.Services.Extensions.StartQrLoginAsync(extensionId, ct: ct);
            ct.ThrowIfCancellationRequested();

            QrCodeMatrix matrix = QrCodeEncoder.Encode(session.QrContent);
            QrCodeImage.Source = RenderQrCodeImage(matrix);
            QrLoadingPlaceholderText.Visibility = Visibility.Collapsed;
            QrStatusText.Text = L.Get("Extensions_QrLogin_WaitingScan");
            UpdateQrMessage(session.Message);
            QrRefreshButton.IsEnabled = true;

            while (!ct.IsCancellationRequested)
            {
                if (session.ExpiresAtMs is long exp && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() >= exp)
                {
                    QrStatusText.Text = L.Get("Extensions_QrLogin_Expired");
                    UpdateQrMessage(null);
                    break;
                }

                await Task.Delay(session.PollIntervalMs, ct);

                ExtQrLoginPollResult poll = await _ctx.Services.Extensions.PollQrLoginAsync(
                    extensionId,
                    session.SessionId,
                    ct: ct);
                ct.ThrowIfCancellationRequested();

                switch (poll.Status)
                {
                    case ExtQrLoginStatus.Pending:
                        QrStatusText.Text = L.Get("Extensions_QrLogin_WaitingScan");
                        UpdateQrMessage(poll.Message);
                        break;
                    case ExtQrLoginStatus.Scanned:
                        QrStatusText.Text = string.IsNullOrWhiteSpace(poll.AccountName)
                            ? L.Get("Extensions_QrLogin_Scanned")
                            : L.Format("Extensions_QrLogin_ScannedAs", poll.AccountName);
                        UpdateQrMessage(poll.Message);
                        break;
                    case ExtQrLoginStatus.Confirmed:
                        QrStatusText.Text = string.IsNullOrWhiteSpace(poll.AccountName)
                            ? L.Get("Extensions_QrLogin_Confirmed")
                            : L.Format("Extensions_QrLogin_ConfirmedAs", poll.AccountName);
                        UpdateQrMessage(poll.Message);
                        Refresh();
                        await Task.Delay(900, ct);
                        if (!ct.IsCancellationRequested)
                        {
                            CloseQrLoginModal();
                        }
                        return;
                    case ExtQrLoginStatus.Expired:
                        QrStatusText.Text = L.Get("Extensions_QrLogin_Expired");
                        UpdateQrMessage(poll.Message);
                        return;
                    case ExtQrLoginStatus.Error:
                        QrStatusText.Text = poll.Message ?? L.Get("Extensions_QrLogin_Expired");
                        UpdateQrMessage(null);
                        return;
                }
            }
        }
        catch (OperationCanceledException)
        {
            // Cancelled cleanly on close, refresh, or unload.
        }
        catch (Exception ex)
        {
            if (!ct.IsCancellationRequested)
            {
                QrLoadingPlaceholderText.Visibility = Visibility.Collapsed;
                QrStatusText.Text = ex.Message;
                UpdateQrMessage(null);
                QrRefreshButton.IsEnabled = true;
            }
        }
    }

    private void UpdateQrMessage(string? message)
    {
        if (string.IsNullOrWhiteSpace(message))
        {
            QrMessageText.Text = string.Empty;
            QrMessageText.Visibility = Visibility.Collapsed;
        }
        else
        {
            QrMessageText.Text = message;
            QrMessageText.Visibility = Visibility.Visible;
        }
    }

    private static DrawingImage RenderQrCodeImage(QrCodeMatrix matrix)
    {
        const int quietModules = 4;
        int totalModules = matrix.Size + quietModules * 2;
        var group = new DrawingGroup();
        group.Children.Add(new GeometryDrawing(
            Brushes.White,
            null,
            new RectangleGeometry(new Rect(0, 0, totalModules, totalModules))));

        var darkGeometry = new StreamGeometry();
        using (StreamGeometryContext ctx = darkGeometry.Open())
        {
            for (int r = 0; r < matrix.Size; r++)
            {
                for (int c = 0; c < matrix.Size; c++)
                {
                    if (!matrix.Get(r, c)) continue;
                    double x = c + quietModules;
                    double y = r + quietModules;
                    ctx.BeginFigure(new Point(x, y), isFilled: true, isClosed: true);
                    ctx.LineTo(new Point(x + 1, y), isStroked: false, isSmoothJoin: false);
                    ctx.LineTo(new Point(x + 1, y + 1), isStroked: false, isSmoothJoin: false);
                    ctx.LineTo(new Point(x, y + 1), isStroked: false, isSmoothJoin: false);
                }
            }
        }
        darkGeometry.Freeze();
        group.Children.Add(new GeometryDrawing(Brushes.Black, null, darkGeometry));
        group.Freeze();

        var image = new DrawingImage(group);
        image.Freeze();
        return image;
    }

    private void RefreshQrLogin_Click(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrWhiteSpace(_qrExtensionId))
        {
            _ = StartQrSessionAsync(_qrExtensionId);
        }
    }

    private void CloseQrLogin_Click(object sender, RoutedEventArgs e) => CloseQrLoginModal();

    private void QrOverlay_MouseDown(object sender, MouseButtonEventArgs e) => CloseQrLoginModal();

    private void QrCard_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
