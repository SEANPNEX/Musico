using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MusicoWindows.Stores;
using MusicoWindows.Theme;
using MusicoWindows.Update;

namespace MusicoWindows.Controls;

public partial class UpdateDialog : UserControl
{
    private AppServices? _services;
    private Action? _initiateShutdown;
    private UpdateCheckResult? _currentResult;
    private CancellationTokenSource? _cts;
    private bool _isBusy;

    public UpdateDialog()
    {
        InitializeComponent();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public async void Show(AppServices services, Action initiateShutdown)
    {
        _services = services;
        _initiateShutdown = initiateShutdown;
        _isBusy = false;

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);

        if (services.Update.LatestCheckResult is { IsUpdateAvailable: true } cached)
        {
            ApplyCheckResult(cached);
        }
        else
        {
            await CheckNowAsync();
        }
    }

    public void Close()
    {
        if (_isBusy) return;
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;
        Visibility = Visibility.Collapsed;
    }

    private async Task CheckNowAsync()
    {
        if (_services is null || _isBusy) return;

        _cts?.Cancel();
        _cts?.Dispose();
        _cts = new CancellationTokenSource();

        SetBusy(true, "Checking for updates...", indeterminate: true);
        ErrorBanner.Visibility = Visibility.Collapsed;
        ReleaseNotesSection.Visibility = Visibility.Collapsed;
        UpdateActionsPanel.Visibility = Visibility.Collapsed;
        SecondaryActionsPanel.Visibility = Visibility.Visible;

        TitleText.Text = "Checking for Updates";
        VersionSummaryText.Text = $"Current version: {_services.Update.CurrentVersion}";
        ModeSubtitleText.Text = _services.Update.IsPortable ? "Portable mode (.zip)" : "Installer mode (.exe)";
        AssetSizeText.Text = string.Empty;

        try
        {
            UpdateCheckResult result = await _services.Update.CheckForUpdatesAsync(manual: true, _cts.Token);
            SetBusy(false);
            ApplyCheckResult(result);
        }
        catch (OperationCanceledException)
        {
            SetBusy(false);
        }
    }

    private void ApplyCheckResult(UpdateCheckResult result)
    {
        _currentResult = result;
        string modeLabel = result.IsPortable ? "Portable (.zip)" : "Installer (.exe)";

        if (result.Status == UpdateCheckStatus.UpdateAvailable && result.Release is { } rel)
        {
            TitleText.Text = "Update Available";
            VersionSummaryText.Text = $"{result.CurrentVersion}  \u2192  {rel.Version}";
            ModeSubtitleText.Text = string.IsNullOrWhiteSpace(rel.Title)
                ? $"Package: {modeLabel}"
                : $"{rel.Title} \u00B7 {modeLabel}";
            AssetSizeText.Text = result.SelectedAsset is { Size: > 0 } asset
                ? FormatBytes(asset.Size)
                : string.Empty;

            ReleaseNotesText.Text = string.IsNullOrWhiteSpace(rel.ReleaseNotes)
                ? "No release notes provided for this release."
                : rel.ReleaseNotes.Trim();
            ReleaseNotesSection.Visibility = Visibility.Visible;

            if (result.SelectedAsset is null)
            {
                ShowError($"No compatible {modeLabel} package was found in release {rel.TagName}. You can open the release page to download it manually.");
                UpdateNowButton.IsEnabled = false;
            }
            else
            {
                ErrorBanner.Visibility = Visibility.Collapsed;
                UpdateNowButton.IsEnabled = true;
            }

            UpdateActionsPanel.Visibility = Visibility.Visible;
            SecondaryActionsPanel.Visibility = Visibility.Collapsed;
            return;
        }

        ReleaseNotesSection.Visibility = Visibility.Collapsed;
        UpdateActionsPanel.Visibility = Visibility.Collapsed;
        SecondaryActionsPanel.Visibility = Visibility.Visible;
        AssetSizeText.Text = string.Empty;

        if (result.Status == UpdateCheckStatus.Error)
        {
            TitleText.Text = "Update Check Failed";
            VersionSummaryText.Text = $"Current version: {result.CurrentVersion}";
            ModeSubtitleText.Text = $"Package: {modeLabel}";
            ShowError(string.IsNullOrWhiteSpace(result.ErrorMessage)
                ? "Unable to reach GitHub Releases."
                : result.ErrorMessage);
        }
        else
        {
            TitleText.Text = "You're Up to Date";
            VersionSummaryText.Text = $"Musico {result.CurrentVersion} is the latest version";
            ModeSubtitleText.Text = $"Package: {modeLabel}";
            ErrorBanner.Visibility = Visibility.Collapsed;
        }
    }

    private async void UpdateNow_Click(object sender, RoutedEventArgs e)
    {
        if (_services is null || _currentResult is null || _isBusy) return;

        _cts?.Cancel();
        _cts?.Dispose();
        _cts = new CancellationTokenSource();

        ErrorBanner.Visibility = Visibility.Collapsed;
        SetBusy(true, "Preparing update...", indeterminate: true);

        var progress = new Progress<UpdateProgress>(p =>
        {
            ProgressStatusText.Text = p.Stage;
            if (p.Percent is double pct)
            {
                DownloadProgressBar.IsIndeterminate = false;
                DownloadProgressBar.Value = pct;
                ProgressPercentText.Text = $"{pct:0}%";
            }
            else
            {
                DownloadProgressBar.IsIndeterminate = true;
                ProgressPercentText.Text = string.Empty;
            }
        });

        try
        {
            await _services.Update.DownloadAndApplyUpdateAsync(
                _currentResult,
                () => Dispatcher.Invoke(() => _initiateShutdown?.Invoke()),
                progress,
                _cts.Token);
        }
        catch (OperationCanceledException)
        {
            SetBusy(false);
        }
        catch (Exception ex)
        {
            SetBusy(false);
            ShowError(ex.Message);
        }
    }

    private void SkipVersion_Click(object sender, RoutedEventArgs e)
    {
        if (_services is null || _currentResult?.Release is null || _isBusy) return;
        _services.Update.SkipVersion(_currentResult.Release.Version);
        Close();
    }

    private void OpenReleasePage_Click(object sender, RoutedEventArgs e)
    {
        string? url = _currentResult?.Release?.HtmlUrl;
        if (string.IsNullOrWhiteSpace(url))
        {
            url = UpdateService.ReleasesPageUrl;
        }

        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            ShowError($"Unable to open browser: {ex.Message}");
        }
    }

    private async void CheckAgain_Click(object sender, RoutedEventArgs e) => await CheckNowAsync();

    private void SetBusy(bool busy, string? status = null, bool indeterminate = true)
    {
        _isBusy = busy;
        CloseIconButton.IsEnabled = !busy;
        CloseButton.IsEnabled = !busy;
        CheckAgainButton.IsEnabled = !busy;
        UpdateNowButton.IsEnabled = !busy && _currentResult?.SelectedAsset is not null;
        SkipVersionButton.IsEnabled = !busy;
        OpenReleaseFromUpdateButton.IsEnabled = !busy;
        OpenReleasePageButton.IsEnabled = !busy;

        if (busy)
        {
            ProgressPanel.Visibility = Visibility.Visible;
            ProgressStatusText.Text = status ?? string.Empty;
            ProgressPercentText.Text = string.Empty;
            DownloadProgressBar.IsIndeterminate = indeterminate;
            DownloadProgressBar.Value = 0;
        }
        else
        {
            ProgressPanel.Visibility = Visibility.Collapsed;
            DownloadProgressBar.IsIndeterminate = false;
        }
    }

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorBanner.Visibility = Visibility.Visible;
    }

    private static string FormatBytes(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return $"{kb:0.0} KB";
        double mb = kb / 1024.0;
        return $"{mb:0.0} MB";
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();

    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
