using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Microsoft.Win32;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class SaveCloudFolderDialog : UserControl
{
    private AppServices? _services;
    private LibraryRoot? _root;
    private string _updateRelativePath = string.Empty;
    private IReadOnlyList<Track> _tracks = Array.Empty<Track>();
    private Guid? _activeJobId;
    private string? _completedFolder;
    private string? _completedFile;

    public SaveCloudFolderDialog()
    {
        InitializeComponent();
        Unloaded += (_, _) => DetachSubscription();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(AppServices services, LibraryRoot root, IReadOnlyList<Track> tracks)
    {
        DetachSubscription();
        _services = services;
        _root = root;
        _updateRelativePath = string.Empty;
        _tracks = tracks;
        _activeJobId = null;
        _completedFolder = null;
        _completedFile = null;

        TitleText.Text = L.Get("SaveCloud_Title");
        SourceSummaryText.Text = $"{root.Name} · {L.SongsCount(tracks.Count)}";
        EditSourceUrlButton.Visibility = Visibility.Collapsed;
        EditSourcePanel.Visibility = Visibility.Collapsed;

        string initialFolderName = CloudExporter.SanitizeFileName(root.Name, L.Get("SaveCloud_DefaultFolderName"));
        string defaultLocation = ResolveDefaultSaveLocation(services);

        FormPanel.Visibility = Visibility.Visible;
        ProgressPanel.Visibility = Visibility.Collapsed;
        SummaryPanel.Visibility = Visibility.Collapsed;
        PartialErrorsBanner.Visibility = Visibility.Collapsed;
        ErrorBanner.Visibility = Visibility.Collapsed;
        ActiveActionsPanel.Visibility = Visibility.Visible;
        DoneActionsPanel.Visibility = Visibility.Collapsed;
        ResetFooterLayout();

        SaveButton.Visibility = Visibility.Visible;
        BackgroundButton.Visibility = Visibility.Collapsed;
        CancelButton.IsEnabled = true;
        CancelButton.Content = L.Get("Common_Cancel");
        Grid.SetColumn(CancelButton, 2);
        Grid.SetColumnSpan(CancelButton, 1);

        FolderNameBox.IsEnabled = true;
        LocationBox.IsEnabled = true;
        BrowseButton.IsEnabled = true;
        AddToLibraryCheckBox.IsEnabled = true;

        FolderNameBox.Text = initialFolderName;
        LocationBox.Text = defaultLocation;
        AddToLibraryCheckBox.IsChecked = true;

        UpdateFormState();

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
        Dispatcher.BeginInvoke(() => FolderNameBox.Focus());
    }

    public void ShowSingleTrackExport(AppServices services, Track track, string destinationDirectory)
    {
        DetachSubscription();
        _services = services;
        _root = services.Library.GetRoot(track.RootId);
        _updateRelativePath = string.Empty;
        _tracks = [track];
        _completedFolder = destinationDirectory;
        _completedFile = null;

        TitleText.Text = L.Get("SaveCloud_SingleTrackTitle");
        SourceSummaryText.Text = $"{track.Title} · {L.DisplayArtist(track.Artist)}";
        EditSourceUrlButton.Visibility = Visibility.Collapsed;
        EditSourcePanel.Visibility = Visibility.Collapsed;

        var job = services.Downloads.StartSingleTrackExport(track, _root, destinationDirectory);
        AttachToJob(services, job);

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
    }

    public void ShowTrackedFolderUpdate(AppServices services, LibraryRoot root, string relativePath = "")
    {
        DetachSubscription();
        _services = services;
        _root = root;
        _updateRelativePath = LibraryRoot.NormalizeFolderPath(relativePath);
        _tracks = Array.Empty<Track>();
        _completedFile = null;

        CloudTrackingInfo? tracking = services.Library.GetCloudTracking(root.Id, _updateRelativePath);
        string targetFolderPath = string.IsNullOrEmpty(_updateRelativePath)
            ? root.Path ?? string.Empty
            : FolderScanner.CombinePath(root.Path ?? string.Empty, _updateRelativePath);
        _completedFolder = targetFolderPath;

        TitleText.Text = L.Get("Folders_UpdateFromCloud");
        UpdateTrackedSourceBanner(root, _updateRelativePath, tracking);
        EditSourceUrlButton.Visibility = Visibility.Visible;
        EditSourcePanel.Visibility = Visibility.Collapsed;

        var job = services.Downloads.StartFolderUpdate(root, targetFolderPath, tracking, _updateRelativePath);
        AttachToJob(services, job);

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
    }

    public void ShowDownloadJob(AppServices services, Guid jobId)
    {
        var job = services.Downloads.GetJob(jobId);
        if (job is null) return;

        DetachSubscription();
        _services = services;
        _root = job.TargetRootId is Guid rid ? services.Library.GetRoot(rid) : null;
        _updateRelativePath = job.TargetRelativePath ?? string.Empty;
        _tracks = Array.Empty<Track>();
        _completedFolder = job.DestinationPath;
        _completedFile = job.SavedFilePath;

        TitleText.Text = job.Kind switch
        {
            DownloadJobKind.SingleTrackExport => L.Get("SaveCloud_SingleTrackTitle"),
            DownloadJobKind.FolderUpdate => L.Get("Folders_UpdateFromCloud"),
            _ => L.Get("SaveCloud_Title")
        };
        SourceSummaryText.Text = job.Subtitle ?? job.Title;
        EditSourceUrlButton.Visibility = job.Kind == DownloadJobKind.FolderUpdate && _root is not null
            ? Visibility.Visible
            : Visibility.Collapsed;
        EditSourcePanel.Visibility = Visibility.Collapsed;

        AttachToJob(services, job);

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
    }

    public void Close()
    {
        DetachSubscription();
        _activeJobId = null;
        EditSourcePanel.Visibility = Visibility.Collapsed;
        Visibility = Visibility.Collapsed;
    }

    private void AttachToJob(AppServices services, DownloadJob job)
    {
        _activeJobId = job.Id;
        services.Downloads.Changed += OnDownloadsChanged;
        RenderJob(job);
    }

    private void DetachSubscription()
    {
        if (_services is not null)
        {
            _services.Downloads.Changed -= OnDownloadsChanged;
        }
    }

    private void OnDownloadsChanged()
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (!IsOpen || _services is null || _activeJobId is not Guid jobId) return;
            var job = _services.Downloads.GetJob(jobId);
            if (job is not null)
            {
                RenderJob(job);
            }
        });
    }

    private void RenderJob(DownloadJob job)
    {
        _completedFolder = job.DestinationPath;
        _completedFile = job.SavedFilePath;

        if (job.IsActive)
        {
            FormPanel.Visibility = Visibility.Collapsed;
            SummaryPanel.Visibility = Visibility.Collapsed;
            PartialErrorsBanner.Visibility = Visibility.Collapsed;
            ErrorBanner.Visibility = Visibility.Collapsed;
            DoneActionsPanel.Visibility = Visibility.Collapsed;
            ProgressPanel.Visibility = Visibility.Visible;
            ActiveActionsPanel.Visibility = Visibility.Visible;

            SaveButton.Visibility = Visibility.Collapsed;
            BackgroundButton.Visibility = Visibility.Visible;
            CancelButton.IsEnabled = true;
            CancelButton.Content = L.Get("Common_Cancel");
            Grid.SetColumn(CancelButton, 2);
            Grid.SetColumnSpan(CancelButton, 1);

            if (job.OverallFraction is double frac)
            {
                ExportProgressBar.IsIndeterminate = false;
                ExportProgressBar.Maximum = 1;
                ExportProgressBar.Value = frac;
            }
            else
            {
                ExportProgressBar.IsIndeterminate = true;
                ExportProgressBar.Maximum = 1;
                ExportProgressBar.Value = 0;
            }

            ProgressStatusText.Text = BuildStatusText(job);
            ProgressCountText.Text = BuildCountAndPercentText(job);
            ProgressBytesText.Text = BuildBytesText(job);
            ProgressSpeedText.Text = job.BytesPerSecond > 0
                ? DownloadManager.FormatSpeed(job.BytesPerSecond)
                : string.Empty;
            return;
        }

        // Job finished (Completed, Cancelled, or Failed)
        FormPanel.Visibility = Visibility.Collapsed;
        ProgressPanel.Visibility = Visibility.Collapsed;
        ResetFooterLayout();

        if (job.Status == DownloadJobStatus.Completed)
        {
            ErrorBanner.Visibility = Visibility.Collapsed;
            SummaryPanel.Visibility = Visibility.Visible;

            SummaryHeadlineText.Text = job.Kind switch
            {
                DownloadJobKind.SingleTrackExport => L.Get("SaveCloud_SavedSingleTrack"),
                DownloadJobKind.FolderUpdate => job.AddedItems == 0 && job.FailedItems == 0
                    ? L.Format("SaveCloud_AlreadyUpToDate", L.SongsCount(job.SkippedItems))
                    : job.FailedItems == 0
                        ? L.Format("SaveCloud_UpdateDownloaded", L.SongsCount(job.AddedItems), job.SkippedItems)
                        : L.Format("SaveCloud_UpdateWithFailures", job.AddedItems, job.SkippedItems, job.FailedItems),
                _ => job.FailedItems == 0
                    ? L.Format("SaveCloud_ExportSuccess", L.SongsCount(job.CompletedItems))
                    : L.Format("SaveCloud_ExportWithFailures", L.SongsCount(job.CompletedItems), job.FailedItems)
            };

            SummaryPathText.Text = job.SavedFilePath ?? job.DestinationPath;

            if (!string.IsNullOrWhiteSpace(job.LibraryNote))
            {
                LibraryStatusText.Text = job.LibraryNote;
                LibraryStatusText.Visibility = Visibility.Visible;
            }
            else
            {
                LibraryStatusText.Visibility = Visibility.Collapsed;
            }

            if (job.Errors is { Count: > 0 })
            {
                PartialErrorsText.Text = string.Join(Environment.NewLine, job.Errors);
                PartialErrorsBanner.Visibility = Visibility.Visible;
            }
            else
            {
                PartialErrorsBanner.Visibility = Visibility.Collapsed;
            }

            ActiveActionsPanel.Visibility = Visibility.Collapsed;
            DoneActionsPanel.Visibility = Visibility.Visible;
            OpenFolderButton.IsEnabled = (!string.IsNullOrWhiteSpace(_completedFile) && File.Exists(_completedFile)) ||
                                         (!string.IsNullOrWhiteSpace(_completedFolder) && Directory.Exists(_completedFolder));
        }
        else
        {
            SummaryPanel.Visibility = Visibility.Collapsed;
            PartialErrorsBanner.Visibility = Visibility.Collapsed;
            string fallbackErr = job.Status == DownloadJobStatus.Cancelled
                ? (job.Kind == DownloadJobKind.FolderUpdate ? L.Get("SaveCloud_UpdateCancelled") : L.Get("SaveCloud_ExportCancelled"))
                : L.Get("Downloads_StatusFailed");
            ShowError(string.IsNullOrWhiteSpace(job.ErrorMessage) ? fallbackErr : job.ErrorMessage);
            ShowCloseOnlyFooter();
        }
    }

    private static string BuildStatusText(DownloadJob job)
    {
        if (string.IsNullOrWhiteSpace(job.CurrentItemTitle))
        {
            return job.Kind switch
            {
                DownloadJobKind.FolderUpdate => L.Get("SaveCloud_CheckingPlaylist"),
                DownloadJobKind.PlaylistExport when job.TotalItems > 0 => L.Get("SaveCloud_Preparing"),
                _ => L.Get("SaveCloud_Finishing")
            };
        }

        return job.Kind == DownloadJobKind.FolderUpdate
            ? L.Format("SaveCloud_SyncingItem", job.CurrentItemTitle)
            : L.Format("SaveCloud_SavingItem", job.CurrentItemTitle);
    }

    private static string BuildCountAndPercentText(DownloadJob job)
    {
        string pct = DownloadManager.FormatPercentage(job.OverallFraction);
        if (job.TotalItems > 1)
        {
            string count = $"{job.CompletedItems} / {job.TotalItems}";
            return string.IsNullOrEmpty(pct) ? count : $"{count} · {pct}";
        }
        return pct;
    }

    private static string BuildBytesText(DownloadJob job)
    {
        if (job.TotalBytes is long total && total > 0)
        {
            return L.Format("SaveCloud_BytesOfTotal",
                DownloadManager.FormatBytes(job.BytesDownloaded),
                DownloadManager.FormatBytes(total));
        }
        if (job.BytesDownloaded > 0)
        {
            return DownloadManager.FormatBytes(job.BytesDownloaded);
        }
        return string.Empty;
    }

    private void UpdateTrackedSourceBanner(LibraryRoot root, string relativePath, CloudTrackingInfo? tracking)
    {
        string folderName = string.IsNullOrEmpty(relativePath)
            ? root.Name
            : relativePath.Split('/').Last();
        RootKind? kind = tracking?.CloudKind ?? root.EffectiveCloudKind;
        string? providerId = tracking?.ResolvedProviderId ?? root.EffectiveProviderId;
        string sourceLabel = CloudProviderRegistry.ResolveDisplayLabel(
            _services?.CloudProviders,
            providerId,
            kind);
        string? url = tracking?.SourceUrl ?? root.SourceUrl;
        SourceSummaryText.Text = string.IsNullOrWhiteSpace(url)
            ? $"{folderName} · {sourceLabel}"
            : $"{folderName} · {sourceLabel} · {url}";
    }

    private static string ResolveDefaultSaveLocation(AppServices services)
    {
        if (!string.IsNullOrWhiteSpace(services.MusicDirectory) && Directory.Exists(services.MusicDirectory))
        {
            return services.MusicDirectory;
        }
        string myMusic = Environment.GetFolderPath(Environment.SpecialFolder.MyMusic);
        return !string.IsNullOrWhiteSpace(myMusic) ? myMusic : services.StateDirectory;
    }

    private void UpdateFormState()
    {
        if (_activeJobId is not null) return;

        string folderName = FolderNameBox.Text.Trim();
        string location = LocationBox.Text.Trim();
        bool valid = !string.IsNullOrWhiteSpace(folderName) &&
                     !string.IsNullOrWhiteSpace(location) &&
                     _tracks.Count > 0;
        SaveButton.IsEnabled = valid;

        if (!string.IsNullOrWhiteSpace(location))
        {
            string fallback = _root is not null
                ? CloudExporter.SanitizeFileName(_root.Name, L.Get("SaveCloud_DefaultFolderName"))
                : L.Get("SaveCloud_DefaultFolderName");
            string safeFolder = CloudExporter.SanitizeFileName(folderName, fallback);
            DestinationPreviewText.Text = L.Format("SaveCloud_DestinationPreview", Path.Combine(location, safeFolder));
        }
        else
        {
            DestinationPreviewText.Text = L.Get("SaveCloud_ChooseDestinationHint");
        }
    }

    private void Save_Click(object sender, RoutedEventArgs e) => StartPlaylistExport();

    private void StartPlaylistExport()
    {
        if (_services is null || _root is null || _activeJobId is not null || _tracks.Count == 0) return;

        string folderName = FolderNameBox.Text.Trim();
        string parentDir = LocationBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(folderName))
        {
            ShowError(L.Get("SaveCloud_EnterFolderName"));
            return;
        }
        if (string.IsNullOrWhiteSpace(parentDir))
        {
            ShowError(L.Get("SaveCloud_ChooseSaveLocation"));
            return;
        }

        bool addToLibrary = AddToLibraryCheckBox.IsChecked == true;
        ErrorBanner.Visibility = Visibility.Collapsed;

        var job = _services.Downloads.StartPlaylistExport(_root, _tracks, parentDir, folderName, addToLibrary);
        AttachToJob(_services, job);
    }

    private void EditSourceUrl_Click(object sender, RoutedEventArgs e)
    {
        if (_services is null || _root is null) return;
        var tracking = _services.Library.GetCloudTracking(_root.Id, _updateRelativePath);
        EditSourceUrlBox.Text = tracking?.SourceUrl ?? _root.SourceUrl ?? string.Empty;
        EditSourcePanel.Visibility = Visibility.Visible;
        ErrorBanner.Visibility = Visibility.Collapsed;
        Dispatcher.BeginInvoke(() =>
        {
            EditSourceUrlBox.Focus();
            EditSourceUrlBox.SelectAll();
        });
    }

    private void SaveSourceUrl_Click(object sender, RoutedEventArgs e) => ApplyEditedSourceUrl();

    private void CancelEditSourceUrl_Click(object sender, RoutedEventArgs e)
    {
        EditSourcePanel.Visibility = Visibility.Collapsed;
    }

    private void EditSourceUrlBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            ApplyEditedSourceUrl();
        }
        else if (e.Key == Key.Escape)
        {
            e.Handled = true;
            EditSourcePanel.Visibility = Visibility.Collapsed;
        }
    }

    private void ApplyEditedSourceUrl()
    {
        if (_services is null || _root is null) return;
        string raw = EditSourceUrlBox.Text.Trim();
        var previousTracking = _services.Library.GetCloudTracking(_root.Id, _updateRelativePath);

        try
        {
            _services.Library.UpdateCloudTrackingAddress(
                _root.Id,
                _updateRelativePath,
                raw,
                previousTracking?.Cookie ?? _root.Cookie,
                previousTracking?.ProxyUrl ?? _root.ProxyUrl);
        }
        catch (Exception ex)
        {
            ShowError(ex.Message);
            return;
        }

        EditSourcePanel.Visibility = Visibility.Collapsed;
        ErrorBanner.Visibility = Visibility.Collapsed;

        var updatedRoot = _services.Library.GetRoot(_root.Id) ?? _root;
        _root = updatedRoot;
        var updatedTracking = _services.Library.GetCloudTracking(updatedRoot.Id, _updateRelativePath);
        UpdateTrackedSourceBanner(updatedRoot, _updateRelativePath, updatedTracking);

        if (_activeJobId is Guid oldJobId)
        {
            _services.Downloads.Cancel(oldJobId);
        }
        DetachSubscription();

        string targetFolderPath = string.IsNullOrEmpty(_updateRelativePath)
            ? updatedRoot.Path ?? string.Empty
            : FolderScanner.CombinePath(updatedRoot.Path ?? string.Empty, _updateRelativePath);
        var nextJob = _services.Downloads.StartFolderUpdate(updatedRoot, targetFolderPath, updatedTracking, _updateRelativePath);
        AttachToJob(_services, nextJob);
    }

    private void ShowCloseOnlyFooter()
    {
        ActiveActionsPanel.Visibility = Visibility.Collapsed;
        DoneActionsPanel.Visibility = Visibility.Visible;
        OpenFolderButton.Visibility = Visibility.Collapsed;
        Grid.SetColumn(DoneButton, 0);
        Grid.SetColumnSpan(DoneButton, 3);
    }

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        if (_activeJobId is not null) return;
        var dlg = new OpenFolderDialog
        {
            Title = L.Get("SaveCloud_SelectSaveLocationTitle")
        };
        if (!string.IsNullOrWhiteSpace(LocationBox.Text) && Directory.Exists(LocationBox.Text))
        {
            dlg.InitialDirectory = LocationBox.Text;
        }
        if (dlg.ShowDialog() == true)
        {
            LocationBox.Text = dlg.FolderName;
        }
    }

    private void OpenFolder_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            if (!string.IsNullOrWhiteSpace(_completedFile) && File.Exists(_completedFile))
            {
                Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{_completedFile}\"")
                {
                    UseShellExecute = true
                });
            }
            else if (!string.IsNullOrWhiteSpace(_completedFolder) && Directory.Exists(_completedFolder))
            {
                Process.Start(new ProcessStartInfo(_completedFolder)
                {
                    UseShellExecute = true
                });
            }
        }
        catch (Exception ex)
        {
            ShowError(L.Format("SaveCloud_OpenFolderError", ex.Message));
        }
    }

    private void ShowError(string message)
    {
        ErrorText.Text = message;
        ErrorBanner.Visibility = Visibility.Visible;
    }

    private void FormInput_TextChanged(object sender, TextChangedEventArgs e) => UpdateFormState();

    private void InputBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && SaveButton.Visibility == Visibility.Visible && SaveButton.IsEnabled)
        {
            e.Handled = true;
            StartPlaylistExport();
        }
        else if (e.Key == Key.Escape)
        {
            e.Handled = true;
            Close();
        }
    }

    private void Background_Click(object sender, RoutedEventArgs e) => Close();

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        if (_services is not null &&
            _activeJobId is Guid jobId &&
            _services.Downloads.GetJob(jobId) is { IsActive: true })
        {
            CancelButton.IsEnabled = false;
            CancelButton.Content = L.Get("Common_Cancelling");
            _services.Downloads.Cancel(jobId);
            return;
        }
        Close();
    }

    private void Done_Click(object sender, RoutedEventArgs e)
    {
        ResetFooterLayout();
        Close();
    }

    private void ResetFooterLayout()
    {
        OpenFolderButton.Visibility = Visibility.Visible;
        Grid.SetColumn(DoneButton, 2);
        Grid.SetColumnSpan(DoneButton, 1);
    }

    private void CloseIcon_Click(object sender, RoutedEventArgs e) => Close();

    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();

    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
