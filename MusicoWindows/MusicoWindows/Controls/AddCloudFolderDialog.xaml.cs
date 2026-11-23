using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MusicoWindows.Cloud;
using MusicoWindows.Extensions;
using MusicoWindows.Localization;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class AddCloudFolderDialog : UserControl
{
    private sealed record ProviderOption(string? ProviderId, string Label, bool SupportsCookies, bool SupportsProxy);

    private AppServices? _services;
    private bool _isBusy;
    private bool _suppressProviderSelection;

    public AddCloudFolderDialog()
    {
        InitializeComponent();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(AppServices services)
    {
        _services = services;
        _isBusy = false;

        PopulateProviderCombo();

        UrlBox.Text = string.Empty;
        CookieBox.Text = string.Empty;
        ProxyBox.Text = string.Empty;
        ErrorBanner.Visibility = Visibility.Collapsed;
        ErrorText.Text = string.Empty;

        UpdateCapabilityVisibility();
        SetBusy(false);

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
        Dispatcher.BeginInvoke(() => UrlBox.Focus());
    }

    public void Close()
    {
        if (_isBusy) return;
        Visibility = Visibility.Collapsed;
    }

    private void PopulateProviderCombo()
    {
        if (_services is null) return;
        _suppressProviderSelection = true;
        try
        {
            var options = new List<ProviderOption>
            {
                new(null, L.Get("AddCloud_AutoDetect"), true, true)
            };
            foreach (var p in _services.CloudProviders.AvailableProviders)
            {
                options.Add(new ProviderOption(p.ProviderId, p.DisplayName, p.SupportsCookies, p.SupportsProxy));
            }
            ProviderCombo.ItemsSource = options;
            ProviderCombo.SelectedIndex = 0;

            bool hasExtensions = _services.CloudProviders.ExtensionProviders.Any(e => e.IsAvailable);
            UrlHintText.Text = hasExtensions ? L.Get("AddCloud_UrlHintDynamic") : L.Get("AddCloud_UrlHint");
        }
        finally
        {
            _suppressProviderSelection = false;
        }
    }

    private void SetBusy(bool busy)
    {
        _isBusy = busy;
        ProviderCombo.IsEnabled = !busy;
        UrlBox.IsEnabled = !busy;
        CookieBox.IsEnabled = !busy;
        ProxyBox.IsEnabled = !busy;
        CancelButton.IsEnabled = !busy;
        CloseIconButton.IsEnabled = !busy;
        BusyPanel.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        SubmitButton.IsEnabled = !busy && !string.IsNullOrWhiteSpace(UrlBox.Text);
    }

    private void UpdateCapabilityVisibility()
    {
        if (_services is null) return;

        bool showCookies = true;
        bool showProxy = true;

        if (ProviderCombo.SelectedItem is ProviderOption selected && selected.ProviderId is not null)
        {
            showCookies = selected.SupportsCookies;
            showProxy = selected.SupportsProxy;
        }
        else if (!string.IsNullOrWhiteSpace(UrlBox.Text))
        {
            string extracted = CloudUrlClassifier.ExtractCloudUrl(UrlBox.Text, _services.CloudProviders);
            var detected = _services.CloudProviders.AvailableProviders
                .FirstOrDefault(p => p.MatchesUrlPattern(extracted) || p.MatchesUrlPattern(UrlBox.Text));
            if (detected is not null)
            {
                showCookies = detected.SupportsCookies;
                showProxy = detected.SupportsProxy;
            }
        }

        CookieSection.Visibility = showCookies ? Visibility.Visible : Visibility.Collapsed;
        ProxySection.Visibility = showProxy ? Visibility.Visible : Visibility.Collapsed;
    }

    private async void Submit_Click(object sender, RoutedEventArgs e) => await SubmitAsync();

    private async System.Threading.Tasks.Task SubmitAsync()
    {
        if (_services is null || _isBusy) return;

        string rawUrl = UrlBox.Text.Trim();
        string? selectedProviderId = (ProviderCombo.SelectedItem as ProviderOption)?.ProviderId;
        string? cookie = CookieSection.Visibility == Visibility.Visible && !string.IsNullOrWhiteSpace(CookieBox.Text)
            ? CookieBox.Text.Trim()
            : null;
        string? proxy = ProxySection.Visibility == Visibility.Visible && !string.IsNullOrWhiteSpace(ProxyBox.Text)
            ? ProxyBox.Text.Trim()
            : null;

        string effectiveUrl;
        ICloudSourceProvider provider;
        try
        {
            YouTubeClient.NormalizeProxyUrl(proxy);
            var classified = await CloudUrlClassifier.ClassifyAsync(rawUrl, _services.CloudProviders, selectedProviderId);
            provider = classified.Provider;
            effectiveUrl = !string.IsNullOrWhiteSpace(classified.Match.NormalizedUrl)
                ? classified.Match.NormalizedUrl!
                : CloudUrlClassifier.ExtractCloudUrl(rawUrl, _services.CloudProviders);
        }
        catch (Exception ex)
        {
            ShowError(ex.Message);
            return;
        }

        ErrorBanner.Visibility = Visibility.Collapsed;
        SetBusy(true);

        try
        {
            await _services.Library.AddCloudRootAsync(
                effectiveUrl,
                provider.SupportsCookies ? cookie : null,
                provider.SupportsProxy ? proxy : null,
                preferredProviderId: selectedProviderId);
            _isBusy = false;
            Visibility = Visibility.Collapsed;
        }
        catch (Exception ex)
        {
            SetBusy(false);
            ShowError(ex.Message);
        }
    }

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorBanner.Visibility = Visibility.Visible;
    }

    private void ProviderCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressProviderSelection) return;
        UpdateCapabilityVisibility();
    }

    private void UrlBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        UpdateCapabilityVisibility();
        if (!_isBusy)
        {
            SubmitButton.IsEnabled = !string.IsNullOrWhiteSpace(UrlBox.Text);
        }
    }

    private async void InputBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            await SubmitAsync();
        }
        else if (e.Key == Key.Escape && !_isBusy)
        {
            e.Handled = true;
            Close();
        }
    }

    private void Cancel_Click(object sender, RoutedEventArgs e) => Close();

    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();

    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
