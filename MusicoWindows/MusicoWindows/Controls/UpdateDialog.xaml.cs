using System;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MusicoWindows.Localization;
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
        LocalizationManager.LanguageChanged += _ =>
        {
            if (IsOpen && _currentResult is not null && !_isBusy)
                ApplyCheckResult(_currentResult);
        };
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

        SetBusy(true, L.Get("Update_Checking"), indeterminate: true);
        ErrorBanner.Visibility = Visibility.Collapsed;
        ReleaseNotesSection.Visibility = Visibility.Collapsed;
        UpdateActionsPanel.Visibility = Visibility.Collapsed;
        SecondaryActionsPanel.Visibility = Visibility.Visible;

        TitleText.Text = L.Get("Update_CheckingTitle");
        VersionSummaryText.Text = L.Format("Update_CurrentVersion", _services.Update.CurrentVersion);
        ModeSubtitleText.Text = _services.Update.IsPortable ? L.Get("Update_ModePortable") : L.Get("Update_ModeInstaller");
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
        string modeLabel = result.IsPortable ? L.Get("Update_PackagePortable") : L.Get("Update_PackageInstaller");

        if (result.Status == UpdateCheckStatus.UpdateAvailable && result.Release is { } rel)
        {
            TitleText.Text = L.Get("Update_AvailableTitle");
            VersionSummaryText.Text = $"{result.CurrentVersion}  \u2192  {rel.Version}";
            ModeSubtitleText.Text = string.IsNullOrWhiteSpace(rel.Title)
                ? L.Format("Update_PackageLabel", modeLabel)
                : $"{rel.Title} \u00B7 {modeLabel}";
            AssetSizeText.Text = result.SelectedAsset is { Size: > 0 } asset
                ? FormatBytes(asset.Size)
                : string.Empty;

            ReleaseNotesText.Text = string.IsNullOrWhiteSpace(rel.ReleaseNotes)
                ? L.Get("Update_NoReleaseNotes")
                : rel.ReleaseNotes.Trim();
            ReleaseNotesSection.Visibility = Visibility.Visible;

            if (result.SelectedAsset is null)
            {
                ShowError(L.Format("Update_NoCompatibleAsset", modeLabel, rel.TagName));
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
            TitleText.Text = L.Get("Update_FailedTitle");
            VersionSummaryText.Text = L.Format("Update_CurrentVersion", result.CurrentVersion);
            ModeSubtitleText.Text = L.Format("Update_PackageLabel", modeLabel);
            ShowError(string.IsNullOrWhiteSpace(result.ErrorMessage)
                ? L.Get("Update_UnableToReach")
                : result.ErrorMessage);
        }
        else
        {
            TitleText.Text = L.Get("Update_UpToDateTitle");
            VersionSummaryText.Text = L.Format("Update_UpToDateVersion", result.CurrentVersion);
            ModeSubtitleText.Text = L.Format("Update_PackageLabel", modeLabel);
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
        SetBusy(true, L.Get("Update_Preparing"), indeterminate: true);

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
            ShowError(L.Format("Update_OpenBrowserError", ex.Message));
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
