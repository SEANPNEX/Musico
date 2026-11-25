using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MusicoWindows.Cloud;
using MusicoWindows.Extensions;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class EditCloudSourceDialog : UserControl
{
    private sealed record ProviderOption(
        string? ProviderId,
        string Label,
        bool SupportsCookies,
        bool SupportsProxy,
        bool IsMissing,
        ICloudSourceProvider? Provider);

    private sealed record QualityOptionItem(string? QualityId, string Label);

    private AppServices? _services;
    private LibraryRoot? _root;
    private string _relativePath = string.Empty;
    private string? _missingProviderId;
    private string? _missingProviderLabel;
    private string? _savedStreamQualityId;
    private string? _savedDownloadQualityId;
    private bool _isBusy;
    private bool _suppressProviderSelection;

    public EditCloudSourceDialog()
    {
        InitializeComponent();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(
        AppServices services,
        LibraryRoot root,
        string relativePath = "",
        string? preselectedProviderId = null)
    {
        _services = services;
        _root = root;
        _relativePath = LibraryRoot.NormalizeFolderPath(relativePath);
        _isBusy = false;

        var tracking = root.IsLocal
            ? services.Library.GetCloudTracking(root.Id, _relativePath)
            : null;

        string initialUrl = tracking?.SourceUrl ?? root.SourceUrl ?? string.Empty;
        string initialCookie = tracking?.Cookie ?? root.Cookie ?? string.Empty;
        string initialProxy = tracking?.ProxyUrl ?? root.ProxyUrl ?? string.Empty;
        _savedStreamQualityId = tracking?.StreamQualityId ?? root.StreamQualityId;
        _savedDownloadQualityId = tracking?.DownloadQualityId ?? root.DownloadQualityId;

        string? boundProviderId = root.IsLocal
            ? tracking?.ResolvedProviderId
            : root.EffectiveProviderId;

        _missingProviderId = null;
        _missingProviderLabel = null;
        if (!string.IsNullOrWhiteSpace(boundProviderId) &&
            services.CloudProviders.FindProvider(boundProviderId) is null)
        {
            _missingProviderId = boundProviderId;
            _missingProviderLabel = root.IsCloud
                ? CloudProviderRegistry.ResolveDisplayLabel(services.CloudProviders, boundProviderId, root.Kind)
                : boundProviderId;
        }

        string displayTarget = string.IsNullOrEmpty(_relativePath)
            ? root.Name
            : $"{root.Name} / {_relativePath.Replace('\\', '/')}";
        TargetLabelText.Text = displayTarget;

        _suppressProviderSelection = true;
        try
        {
            UrlBox.Text = initialUrl;
        }
        finally
        {
            _suppressProviderSelection = false;
        }

        CookieBox.Text = initialCookie;
        ProxyBox.Text = initialProxy;
        ErrorBanner.Visibility = Visibility.Collapsed;
        ErrorText.Text = string.Empty;

        string? initialSelection = !string.IsNullOrWhiteSpace(preselectedProviderId)
            ? preselectedProviderId
            : boundProviderId;

        PopulateProviderCombo(initialSelection);
        UpdateCapabilityAndQualityUI();
        SetBusy(false);

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
        Dispatcher.BeginInvoke(() =>
        {
            UrlBox.Focus();
            UrlBox.SelectAll();
        });
    }

    public void Close()
    {
        if (_isBusy) return;
        Visibility = Visibility.Collapsed;
    }

    private void PopulateProviderCombo(string? preferredSelectionId)
    {
        if (_services is null) return;

        _suppressProviderSelection = true;
        try
        {
            var options = new List<ProviderOption>
            {
                new(null, L.Get("AddCloud_AutoDetect"), true, true, false, null)
            };

            if (!string.IsNullOrWhiteSpace(_missingProviderId))
            {
                string label = L.Format(
                    "EditCloud_MissingProviderOption",
                    _missingProviderLabel ?? _missingProviderId);
                options.Add(new ProviderOption(_missingProviderId, label, true, false, true, null));
            }

            string rawUrl = UrlBox.Text.Trim();
            IReadOnlyList<ICloudSourceProvider> compatible = !string.IsNullOrWhiteSpace(rawUrl)
                ? _services.CloudProviders.GetCompatibleProviders(rawUrl)
                : Array.Empty<ICloudSourceProvider>();

            var listedProviders = compatible.Count > 0
                ? compatible
                : _services.CloudProviders.AvailableProviders
                    .Where(p => p.SupportsCollectionImport)
                    .ToList();

            foreach (var p in listedProviders)
            {
                if (options.Any(o => string.Equals(o.ProviderId, p.ProviderId, StringComparison.OrdinalIgnoreCase)))
                {
                    continue;
                }
                options.Add(new ProviderOption(p.ProviderId, p.DisplayName, p.SupportsCookies, p.SupportsProxy, false, p));
            }

            ProviderCombo.ItemsSource = options;

            int matchIdx = 0;
            if (!string.IsNullOrWhiteSpace(preferredSelectionId))
            {
                int found = options.FindIndex(o => string.Equals(o.ProviderId, preferredSelectionId, StringComparison.OrdinalIgnoreCase));
                if (found >= 0) matchIdx = found;
            }
            ProviderCombo.SelectedIndex = matchIdx;
        }
        finally
        {
            _suppressProviderSelection = false;
        }
    }

    private ICloudSourceProvider? ResolveActiveProvider()
    {
        if (_services is null) return null;

        if (ProviderCombo.SelectedItem is ProviderOption selected)
        {
            if (selected.Provider is not null) return selected.Provider;
            if (selected.IsMissing) return null;
        }

        string rawUrl = UrlBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(rawUrl)) return null;

        return _services.CloudProviders.GetBestCompatibleProvider(rawUrl);
    }

    private void UpdateCapabilityAndQualityUI()
    {
        if (_services is null) return;

        var selected = ProviderCombo.SelectedItem as ProviderOption;
        var resolved = ResolveActiveProvider();

        bool showCookies = resolved?.SupportsCookies ?? selected?.SupportsCookies ?? true;
        bool showProxy = resolved?.SupportsProxy ?? selected?.SupportsProxy ?? true;

        CookieSection.Visibility = showCookies ? Visibility.Visible : Visibility.Collapsed;
        ProxySection.Visibility = showProxy ? Visibility.Visible : Visibility.Collapsed;

        PopulateQualityCombos(resolved);
    }

    private void PopulateQualityCombos(ICloudSourceProvider? provider)
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

        string? currentStream = (StreamQualityCombo.SelectedItem as QualityOptionItem)?.QualityId ?? _savedStreamQualityId;
        string? currentDownload = (DownloadQualityCombo.SelectedItem as QualityOptionItem)?.QualityId ?? _savedDownloadQualityId;

        PopulateQualityCombo(StreamQualityCombo, qualities, provider.DefaultStreamQualityId, currentStream);
        PopulateQualityCombo(DownloadQualityCombo, qualities, provider.DefaultDownloadQualityId, currentDownload);
    }

    private static void PopulateQualityCombo(
        ComboBox combo,
        IReadOnlyList<ExtensionQualityOption> qualities,
        string? defaultQualityId,
        string? selectedQualityId)
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
            string display = !string.IsNullOrWhiteSpace(q.Badge)
                ? $"{q.Label} ({q.Badge})"
                : q.Label;
            items.Add(new QualityOptionItem(q.Id, display));
        }

        combo.ItemsSource = items;

        int index = 0;
        if (!string.IsNullOrWhiteSpace(selectedQualityId))
        {
            int found = items.FindIndex(i => string.Equals(i.QualityId, selectedQualityId, StringComparison.OrdinalIgnoreCase));
            if (found >= 0) index = found;
        }
        combo.SelectedIndex = index;
    }

    private void SetBusy(bool busy)
    {
        _isBusy = busy;
        UrlBox.IsEnabled = !busy;
        ProviderCombo.IsEnabled = !busy;
        CookieBox.IsEnabled = !busy;
        ProxyBox.IsEnabled = !busy;
        StreamQualityCombo.IsEnabled = !busy;
        DownloadQualityCombo.IsEnabled = !busy;
        CancelButton.IsEnabled = !busy;
        CloseIconButton.IsEnabled = !busy;
        BusyPanel.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        SaveButton.IsEnabled = !busy && !string.IsNullOrWhiteSpace(UrlBox.Text);
    }

    private async void Save_Click(object sender, RoutedEventArgs e) => await SaveAsync();

    private async System.Threading.Tasks.Task SaveAsync()
    {
        if (_services is null || _root is null || _isBusy) return;

        string rawUrl = UrlBox.Text.Trim();
        var selectedOption = ProviderCombo.SelectedItem as ProviderOption;
        if (selectedOption?.IsMissing == true)
        {
            ShowError(L.Get("EditCloud_SelectAvailableProvider"));
            return;
        }

        string? selectedProviderId = selectedOption?.ProviderId;
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

        _savedStreamQualityId = streamQualityId;
        _savedDownloadQualityId = downloadQualityId;

        ErrorBanner.Visibility = Visibility.Collapsed;
        SetBusy(true);

        try
        {
            YouTubeClient.NormalizeProxyUrl(proxy);

            if (_root.IsCloud && string.IsNullOrEmpty(_relativePath))
            {
                await _services.Library.UpdateCloudRootSourceAsync(
                    _root.Id,
                    rawUrl,
                    cookie,
                    proxy,
                    preferredProviderId: selectedProviderId,
                    streamQualityId: streamQualityId,
                    downloadQualityId: downloadQualityId,
                    updateQualityPrefs: true);
            }
            else if (_root.IsLocal)
            {
                var classified = await CloudUrlClassifier.ClassifyAsync(rawUrl, _services.CloudProviders, selectedProviderId);
                string effectiveUrl = !string.IsNullOrWhiteSpace(classified.Match.NormalizedUrl)
                    ? classified.Match.NormalizedUrl!
                    : CloudUrlClassifier.ExtractCloudUrl(rawUrl, _services.CloudProviders);

                _services.Library.UpdateCloudTrackingAddress(
                    _root.Id,
                    _relativePath,
                    effectiveUrl,
                    classified.Provider.SupportsCookies ? cookie : null,
                    classified.Provider.SupportsProxy ? proxy : null,
                    preferredProviderId: classified.Provider.ProviderId,
                    streamQualityId: streamQualityId,
                    downloadQualityId: downloadQualityId,
                    updateQualityPrefs: true);
            }

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
        UpdateCapabilityAndQualityUI();
    }

    private void UrlBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_suppressProviderSelection) return;
        string? currentSelection = (ProviderCombo.SelectedItem as ProviderOption)?.ProviderId;
        PopulateProviderCombo(currentSelection);
        UpdateCapabilityAndQualityUI();
        if (!_isBusy)
        {
            SaveButton.IsEnabled = !string.IsNullOrWhiteSpace(UrlBox.Text);
        }
    }

    private async void InputBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            await SaveAsync();
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
