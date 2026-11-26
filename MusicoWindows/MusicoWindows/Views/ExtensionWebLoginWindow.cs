using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;
using MusicoWindows.Extensions;
using MusicoWindows.Localization;

namespace MusicoWindows.Views;

/// <summary>
/// Ephemeral WebView2 modal that lets a user sign in to a web service and captures
/// domain-filtered session cookies directly into <see cref="ExtensionStore"/>.
/// </summary>
public sealed class ExtensionWebLoginWindow : Window
{
    private readonly InstalledExtension _extension;
    private readonly ExtensionWebLoginConfig _config;
    private readonly ExtensionStore _store;
    private readonly string _tempUserDataDir;
    private readonly WebView2 _webView;
    private readonly TextBlock _statusText;
    private readonly Button _saveButton;

    private string? _capturedCookieHeader;
    private bool _didAutoComplete;

    public ExtensionWebLoginWindow(
        InstalledExtension extension,
        ExtensionWebLoginConfig config,
        ExtensionStore store)
    {
        _extension = extension;
        _config = config;
        _store = store;
        _tempUserDataDir = Path.Combine(Path.GetTempPath(), "MusicoWebLogin_" + Guid.NewGuid().ToString("N"));

        Title = L.Format("Extensions_WebLoginTitle", extension.Manifest.EffectiveBadgeLabel);
        Width = 620;
        Height = 720;
        MinWidth = 460;
        MinHeight = 520;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;

        var root = new Grid();
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        var headerBorder = new Border
        {
            Padding = new Thickness(12, 8, 12, 8),
            BorderThickness = new Thickness(0, 0, 0, 1)
        };
        headerBorder.SetResourceReference(Border.BackgroundProperty, "CardBackgroundBrush");
        headerBorder.SetResourceReference(Border.BorderBrushProperty, "DividerBrush");

        var headerGrid = new Grid();
        headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        headerGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        _statusText = new TextBlock
        {
            Text = L.Get("Extensions_WebLoginPrompt"),
            FontSize = 12,
            VerticalAlignment = VerticalAlignment.Center,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Margin = new Thickness(0, 0, 12, 0)
        };
        _statusText.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
        Grid.SetColumn(_statusText, 0);
        headerGrid.Children.Add(_statusText);

        var buttonPanel = new StackPanel { Orientation = Orientation.Horizontal };
        _saveButton = new Button
        {
            Content = L.Get("Extensions_SaveCookies"),
            IsEnabled = false,
            Padding = new Thickness(12, 4, 12, 4),
            Margin = new Thickness(0, 0, 8, 0)
        };
        _saveButton.Click += async (_, _) =>
        {
            await InspectCookiesAsync();
            SaveAndClose();
        };

        var cancelButton = new Button
        {
            Content = (string)Application.Current.FindResource("Str_Common_Cancel"),
            Padding = new Thickness(12, 4, 12, 4)
        };
        cancelButton.Click += (_, _) => Close();

        buttonPanel.Children.Add(_saveButton);
        buttonPanel.Children.Add(cancelButton);
        Grid.SetColumn(buttonPanel, 1);
        headerGrid.Children.Add(buttonPanel);
        headerBorder.Child = headerGrid;

        Grid.SetRow(headerBorder, 0);
        root.Children.Add(headerBorder);

        _webView = new WebView2();
        Grid.SetRow(_webView, 1);
        root.Children.Add(_webView);

        Content = root;

        Loaded += async (_, _) => await InitializeWebViewAsync();
        Closed += (_, _) => CleanupTempUserData();
    }

    private async Task InitializeWebViewAsync()
    {
        try
        {
            Directory.CreateDirectory(_tempUserDataDir);
            var env = await CoreWebView2Environment.CreateAsync(userDataFolder: _tempUserDataDir);
            await _webView.EnsureCoreWebView2Async(env);

            if (!string.IsNullOrWhiteSpace(_config.UserAgent))
            {
                _webView.CoreWebView2.Settings.UserAgent = _config.UserAgent.Trim();
            }

            _webView.CoreWebView2.NewWindowRequested += (_, e) =>
            {
                if (!string.IsNullOrWhiteSpace(e.Uri))
                {
                    e.Handled = true;
                    _webView.CoreWebView2.Navigate(e.Uri);
                }
            };
            _webView.CoreWebView2.NavigationStarting += (_, _) =>
            {
                _statusText.Text = L.Get("Extensions_WebLoginPrompt");
            };
            _webView.CoreWebView2.NavigationCompleted += async (_, _) =>
            {
                await InspectCookiesAsync();
            };

            _webView.CoreWebView2.Navigate(_config.LoginUrl);
        }
        catch (Exception ex)
        {
            _statusText.Text = L.Format("Extensions_WebLoginUnavailable", ex.Message);
            _statusText.SetResourceReference(TextBlock.ForegroundProperty, "DangerBrush");
        }
    }

    private async Task InspectCookiesAsync()
    {
        if (_webView.CoreWebView2 is null) return;
        try
        {
            var allCookies = await _webView.CoreWebView2.CookieManager.GetCookiesAsync(null);
            DateTime nowUtc = DateTime.UtcNow;
            var pairs = new List<(string Name, string Value)>();
            var presentNames = new HashSet<string>(StringComparer.Ordinal);

            foreach (var c in allCookies)
            {
                if (c.Expires != DateTime.MinValue && c.Expires < nowUtc) continue;
                if (string.IsNullOrEmpty(c.Value)) continue;
                if (!NetscapeCookieFileParser.DomainMatches(c.Domain, _config.CookieDomains)) continue;
                pairs.Add((c.Name, c.Value));
                presentNames.Add(c.Name);
            }

            string? header = NetscapeCookieFileParser.FormatCookiePairs(pairs);
            _capturedCookieHeader = header;
            _saveButton.IsEnabled = !string.IsNullOrWhiteSpace(header);

            var required = (_config.RequiredCookies ?? [])
                .Select(r => r.Trim())
                .Where(r => r.Length > 0)
                .ToHashSet(StringComparer.Ordinal);
            bool hasReq = required.Count > 0 && presentNames.Overlaps(required);

            string host = string.Empty;
            if (Uri.TryCreate(_webView.Source?.ToString(), UriKind.Absolute, out var currentUri))
            {
                host = currentUri.Host;
            }

            if (hasReq)
            {
                _statusText.Text = L.Format("Extensions_WebLoginDetected", presentNames.Count);
                _statusText.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
                bool isChallengeHost = host.Equals("accounts.google.com", StringComparison.OrdinalIgnoreCase) ||
                                       host.StartsWith("accounts.", StringComparison.OrdinalIgnoreCase);
                if (!_didAutoComplete && !isChallengeHost && !string.IsNullOrWhiteSpace(header))
                {
                    _didAutoComplete = true;
                    SaveAndClose();
                }
            }
            else if (!string.IsNullOrWhiteSpace(host))
            {
                _statusText.Text = L.Format("Extensions_WebLoginHostStatus", host, presentNames.Count);
            }
        }
        catch
        {
            // Ignore transient navigation read errors.
        }
    }

    private void SaveAndClose()
    {
        if (string.IsNullOrWhiteSpace(_capturedCookieHeader)) return;
        _store.SetCookie(_extension.Id, _capturedCookieHeader);
        Close();
    }

    private void CleanupTempUserData()
    {
        try
        {
            _webView.Dispose();
        }
        catch
        {
        }
        try
        {
            if (Directory.Exists(_tempUserDataDir))
            {
                Directory.Delete(_tempUserDataDir, recursive: true);
            }
        }
        catch
        {
        }
    }
}
