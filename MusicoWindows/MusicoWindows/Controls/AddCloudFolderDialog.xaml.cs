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
    private sealed record ProviderOption(
        string? ProviderId,
        string Label,
        bool SupportsCookies,
        bool SupportsProxy,
        ICloudSourceProvider? Provider);

    private sealed record QualityOptionItem(string? QualityId, string Label);

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
                new(null, L.Get("AddCloud_AutoDetect"), true, true, null)
            };
            foreach (var p in _services.CloudProviders.AvailableProviders.Where(p => p.SupportsCollectionImport))
            {
                options.Add(new ProviderOption(p.ProviderId, p.DisplayName, p.SupportsCookies, p.SupportsProxy, p));
            }
            ProviderCombo.ItemsSource = options;
            ProviderCombo.SelectedIndex = 0;

            var extNames = _services.CloudProviders.ExtensionProviders
                .Where(e => e.IsAvailable && e.SupportsCollectionImport)
                .Select(e => e.DisplayName)
                .Where(n => !string.IsNullOrWhiteSpace(n))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();
            UrlHintText.Text = extNames.Count > 0
                ? L.Format("AddCloud_UrlHintDynamic", string.Join(", ", extNames))
                : L.Get("AddCloud_UrlHint");
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
        StreamQualityCombo.IsEnabled = !busy;
        DownloadQualityCombo.IsEnabled = !busy;
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
        ICloudSourceProvider? activeProvider = null;

        if (ProviderCombo.SelectedItem is ProviderOption selected && selected.ProviderId is not null)
        {
            showCookies = selected.SupportsCookies;
            showProxy = selected.SupportsProxy;
            activeProvider = selected.Provider;
        }
        else if (!string.IsNullOrWhiteSpace(UrlBox.Text))
        {
            var detected = _services.CloudProviders.GetBestCompatibleProvider(UrlBox.Text);
            if (detected is not null)
            {
                showCookies = detected.SupportsCookies;
                showProxy = detected.SupportsProxy;
                activeProvider = detected;
            }
        }

        CookieSection.Visibility = showCookies ? Visibility.Visible : Visibility.Collapsed;
        ProxySection.Visibility = showProxy ? Visibility.Visible : Visibility.Collapsed;

        UpdateQualitySection(activeProvider);
    }

    private void UpdateQualitySection(ICloudSourceProvider? provider)
    {
        var qualities = provider?.AvailableQualities ?? Array.Empty<ExtensionQualityOption>();
        if (provider is null || qualities.Count == 0)
        {
            QualitySection.Visibility = Visibility.Collapsed;
            StreamQualityCombo.ItemsSource = null;
            DownloadQualityCombo.ItemsSource = null;
            return;
        }

        QualitySection.Visibility = Visibility.Visible;
        string? prevStream = (StreamQualityCombo.SelectedItem as QualityOptionItem)?.QualityId;
        string? prevDownload = (DownloadQualityCombo.SelectedItem as QualityOptionItem)?.QualityId;

        PopulateQualityCombo(StreamQualityCombo, qualities, provider.DefaultStreamQualityId, prevStream);
        PopulateQualityCombo(DownloadQualityCombo, qualities, provider.DefaultDownloadQualityId, prevDownload);
    }

    private static void PopulateQualityCombo(
        ComboBox combo,
        IReadOnlyList<ExtensionQualityOption> qualities,
        string? defaultQualityId,
        string? previousSelection)
    {
        string? defaultLabel = qualities
            .FirstOrDefault(q => string.Equals(q.Id, defaultQualityId, StringComparison.OrdinalIgnoreCase))
            ?.Label;
        string autoLabel = !string.IsNullOrWhiteSpace(defaultLabel)
            ? L.Format("Quality_DefaultOption", defaultLabel)
            : L.Get("Quality_AutoDefault");

        var items = new List<QualityOptionItem> { new(null, autoLabel) };
        foreach (var q in qualities)
        {
            string display = !string.IsNullOrWhiteSpace(q.Badge) ? $"{q.Label} ({q.Badge})" : q.Label;
            items.Add(new QualityOptionItem(q.Id, display));
        }

        combo.ItemsSource = items;
        int index = 0;
        if (!string.IsNullOrWhiteSpace(previousSelection))
        {
            int found = items.FindIndex(i => string.Equals(i.QualityId, previousSelection, StringComparison.OrdinalIgnoreCase));
            if (found >= 0) index = found;
        }
        combo.SelectedIndex = index;
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
        string? streamQualityId = QualitySection.Visibility == Visibility.Visible
            ? (StreamQualityCombo.SelectedItem as QualityOptionItem)?.QualityId
            : null;
        string? downloadQualityId = QualitySection.Visibility == Visibility.Visible
            ? (DownloadQualityCombo.SelectedItem as QualityOptionItem)?.QualityId
            : null;

        string effectiveUrl;
        ICloudSourceProvider provider;
        try
        {
            CloudProxyUtils.NormalizeProxyUrl(proxy);
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
                preferredProviderId: selectedProviderId,
                streamQualityId: streamQualityId,
                downloadQualityId: downloadQualityId);
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
