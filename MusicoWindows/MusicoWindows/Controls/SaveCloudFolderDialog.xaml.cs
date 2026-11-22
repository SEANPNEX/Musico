using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using Microsoft.Win32;
using MusicoWindows.Cloud;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class SaveCloudFolderDialog : UserControl
{
    private AppServices? _services;
    private LibraryRoot? _root;
    private IReadOnlyList<Track> _tracks = Array.Empty<Track>();
    private CancellationTokenSource? _cts;
    private bool _isBusy;
    private string? _completedFolder;
    private string? _completedFile;

    public SaveCloudFolderDialog()
    {
        InitializeComponent();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(AppServices services, LibraryRoot root, IReadOnlyList<Track> tracks)
    {
        _services = services;
        _root = root;
        _tracks = tracks;
        _completedFolder = null;
        _completedFile = null;
        ResetCts();

        TitleText.Text = L.Get("SaveCloud_Title");
        SourceSummaryText.Text = $"{root.Name} · {L.SongsCount(tracks.Count)}";

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
        Grid.SetColumn(CancelButton, 2);
        Grid.SetColumnSpan(CancelButton, 1);

        FolderNameBox.Text = initialFolderName;
        LocationBox.Text = defaultLocation;
        AddToLibraryCheckBox.IsChecked = true;

        SetBusy(false);
        UpdateFormState();

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);
        Dispatcher.BeginInvoke(() => FolderNameBox.Focus());
    }

    public async void ShowSingleTrackExport(AppServices services, Track track, string destinationDirectory)
    {
        _services = services;
        _root = services.Library.GetRoot(track.RootId);
        _tracks = [track];
        _completedFolder = destinationDirectory;
        _completedFile = null;
        ResetCts();

        TitleText.Text = L.Get("SaveCloud_SingleTrackTitle");
        SourceSummaryText.Text = $"{track.Title} · {L.DisplayArtist(track.Artist)}";

        FormPanel.Visibility = Visibility.Collapsed;
        SummaryPanel.Visibility = Visibility.Collapsed;
        PartialErrorsBanner.Visibility = Visibility.Collapsed;
        ErrorBanner.Visibility = Visibility.Collapsed;
        ActiveActionsPanel.Visibility = Visibility.Visible;
        DoneActionsPanel.Visibility = Visibility.Collapsed;
        ResetFooterLayout();
        SaveButton.Visibility = Visibility.Collapsed;
        Grid.SetColumn(CancelButton, 0);
        Grid.SetColumnSpan(CancelButton, 3);

        ProgressPanel.Visibility = Visibility.Visible;
        ProgressStatusText.Text = L.Format("SaveCloud_SavingItem", track.Title);
        ProgressCountText.Text = string.Empty;
        ExportProgressBar.IsIndeterminate = true;
        ExportProgressBar.Value = 0;

        SetBusy(true);
        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);

        var cts = new CancellationTokenSource();
        _cts = cts;

        try
        {
            string savedPath = await services.CloudExporter.ExportTrackAsync(
                track,
                _root,
                destinationDirectory,
                cts.Token);

            _completedFile = savedPath;
            _completedFolder = Path.GetDirectoryName(savedPath) ?? destinationDirectory;

            bool rescanned = false;
            if (services.Library.IsFolderIndexed(_completedFolder))
            {
                await services.Library.RescanAsync();
                rescanned = true;
            }

            SetBusy(false);
            ProgressPanel.Visibility = Visibility.Collapsed;
            SummaryPanel.Visibility = Visibility.Visible;
            SummaryHeadlineText.Text = L.Get("SaveCloud_SavedSingleTrack");
            SummaryPathText.Text = savedPath;
            LibraryStatusText.Text = L.Get("SaveCloud_LibraryRescanned");
            LibraryStatusText.Visibility = rescanned ? Visibility.Visible : Visibility.Collapsed;

            ActiveActionsPanel.Visibility = Visibility.Collapsed;
            DoneActionsPanel.Visibility = Visibility.Visible;
            OpenFolderButton.IsEnabled = true;
        }
        catch (OperationCanceledException)
        {
            SetBusy(false);
            ProgressPanel.Visibility = Visibility.Collapsed;
            ShowError(L.Get("SaveCloud_ExportCancelled"));
            ShowCloseOnlyFooter();
        }
        catch (Exception ex)
        {
            SetBusy(false);
            ProgressPanel.Visibility = Visibility.Collapsed;
            ShowError(ex.Message);
            ShowCloseOnlyFooter();
        }
    }

    public async void ShowTrackedFolderUpdate(AppServices services, LibraryRoot root)
    {
        _services = services;
        _root = root;
        _tracks = Array.Empty<Track>();
        _completedFolder = root.Path;
        _completedFile = null;
        ResetCts();

        string sourceLabel = root.EffectiveCloudKind switch
        {
            RootKind.CloudBilibili => "Bilibili",
            RootKind.CloudGoogleDrive => "Google Drive",
            _ => "YouTube"
        };
        TitleText.Text = L.Get("Folders_UpdateFromCloud");
        SourceSummaryText.Text = string.IsNullOrWhiteSpace(root.SourceUrl)
            ? $"{root.Name} · {sourceLabel}"
            : $"{root.Name} · {sourceLabel} · {root.SourceUrl}";

        FormPanel.Visibility = Visibility.Collapsed;
        SummaryPanel.Visibility = Visibility.Collapsed;
        PartialErrorsBanner.Visibility = Visibility.Collapsed;
        ErrorBanner.Visibility = Visibility.Collapsed;
        ActiveActionsPanel.Visibility = Visibility.Visible;
        DoneActionsPanel.Visibility = Visibility.Collapsed;
        ResetFooterLayout();
        SaveButton.Visibility = Visibility.Collapsed;
        Grid.SetColumn(CancelButton, 0);
        Grid.SetColumnSpan(CancelButton, 3);

        ProgressPanel.Visibility = Visibility.Visible;
        ProgressStatusText.Text = L.Get("SaveCloud_CheckingPlaylist");
        ProgressCountText.Text = string.Empty;
        ExportProgressBar.IsIndeterminate = true;
        ExportProgressBar.Value = 0;

        SetBusy(true);
        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);

        var cts = new CancellationTokenSource();
        _cts = cts;

        var progress = new Progress<CloudExportProgress>(p =>
        {
            ExportProgressBar.IsIndeterminate = false;
            ExportProgressBar.Maximum = Math.Max(p.Total, 1);
            ExportProgressBar.Value = p.Completed;
            ProgressCountText.Text = $"{p.Completed} / {p.Total}";
            ProgressStatusText.Text = string.IsNullOrWhiteSpace(p.CurrentTrackTitle)
                ? L.Get("SaveCloud_Finishing")
                : L.Format("SaveCloud_SyncingItem", p.CurrentTrackTitle);
        });

        try
        {
            CloudUpdateResult result = await services.CloudExporter.UpdateLocalFolderAsync(
                root,
                progress: progress,
                ct: cts.Token);

            services.Library.MarkRootCloudSynced(root.Id);
            if (result.AddedCount > 0)
            {
                await services.Library.RescanAsync();
            }

            SetBusy(false);
            ProgressPanel.Visibility = Visibility.Collapsed;
            SummaryPanel.Visibility = Visibility.Visible;

            if (result.AddedCount == 0 && result.FailedCount == 0)
            {
                SummaryHeadlineText.Text = L.Format("SaveCloud_AlreadyUpToDate", L.SongsCount(result.SkippedCount));
            }
            else if (result.FailedCount == 0)
            {
                SummaryHeadlineText.Text = L.Format("SaveCloud_UpdateDownloaded", L.SongsCount(result.AddedCount), result.SkippedCount);
            }
            else
            {
                SummaryHeadlineText.Text = L.Format("SaveCloud_UpdateWithFailures", result.AddedCount, result.SkippedCount, result.FailedCount);
            }

            SummaryPathText.Text = root.Path ?? string.Empty;
            LibraryStatusText.Text = L.Get("SaveCloud_LibraryRescanned");
            LibraryStatusText.Visibility = result.AddedCount > 0 ? Visibility.Visible : Visibility.Collapsed;

            if (result.Errors.Count > 0)
            {
                PartialErrorsText.Text = string.Join(Environment.NewLine, result.Errors);
                PartialErrorsBanner.Visibility = Visibility.Visible;
            }

            ActiveActionsPanel.Visibility = Visibility.Collapsed;
            DoneActionsPanel.Visibility = Visibility.Visible;
            OpenFolderButton.IsEnabled = !string.IsNullOrWhiteSpace(root.Path) && Directory.Exists(root.Path);
        }
        catch (OperationCanceledException)
        {
            SetBusy(false);
            ProgressPanel.Visibility = Visibility.Collapsed;
            ShowError(L.Get("SaveCloud_UpdateCancelled"));
            ShowCloseOnlyFooter();
        }
        catch (Exception ex)
        {
            SetBusy(false);
            ProgressPanel.Visibility = Visibility.Collapsed;
            ShowError(ex.Message);
            ShowCloseOnlyFooter();
        }
    }

    public void Close()
    {
        if (_isBusy) return;
        ResetCts();
        Visibility = Visibility.Collapsed;
    }

    private void ResetCts()
    {
        _cts?.Cancel();
        _cts?.Dispose();
        _cts = null;
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

    private void SetBusy(bool busy)
    {
        _isBusy = busy;
        FolderNameBox.IsEnabled = !busy;
        LocationBox.IsEnabled = !busy;
        BrowseButton.IsEnabled = !busy;
        AddToLibraryCheckBox.IsEnabled = !busy;
        CloseIconButton.IsEnabled = !busy;
        CancelButton.IsEnabled = true;
        CancelButton.Content = L.Get("Common_Cancel");
        if (busy)
        {
            SaveButton.IsEnabled = false;
        }
        else
        {
            UpdateFormState();
        }
    }

    private void UpdateFormState()
    {
        if (_isBusy) return;

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

    private async void Save_Click(object sender, RoutedEventArgs e) => await StartPlaylistExportAsync();

    private async Task StartPlaylistExportAsync()
    {
        if (_services is null || _root is null || _isBusy || _tracks.Count == 0) return;

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
        SummaryPanel.Visibility = Visibility.Collapsed;
        PartialErrorsBanner.Visibility = Visibility.Collapsed;

        SaveButton.Visibility = Visibility.Collapsed;
        Grid.SetColumn(CancelButton, 0);
        Grid.SetColumnSpan(CancelButton, 3);

        ProgressPanel.Visibility = Visibility.Visible;
        ExportProgressBar.IsIndeterminate = false;
        ExportProgressBar.Minimum = 0;
        ExportProgressBar.Maximum = Math.Max(_tracks.Count, 1);
        ExportProgressBar.Value = 0;
        ProgressStatusText.Text = L.Get("SaveCloud_Preparing");
        ProgressCountText.Text = $"0 / {_tracks.Count}";

        SetBusy(true);

        var cts = new CancellationTokenSource();
        _cts = cts;

        var progress = new Progress<CloudExportProgress>(p =>
        {
            ExportProgressBar.Maximum = Math.Max(p.Total, 1);
            ExportProgressBar.Value = p.Completed;
            ProgressCountText.Text = $"{p.Completed} / {p.Total}";
            ProgressStatusText.Text = string.IsNullOrWhiteSpace(p.CurrentTrackTitle)
                ? L.Get("SaveCloud_Finishing")
                : L.Format("SaveCloud_SavingItem", p.CurrentTrackTitle);
        });

        try
        {
            CloudExportResult result = await _services.CloudExporter.ExportPlaylistAsync(
                _root,
                _tracks,
                parentDir,
                folderName,
                progress,
                cts.Token);

            _completedFolder = result.DestinationFolder;

            string? libraryNote = null;
            if (addToLibrary && result.SavedCount > 0)
            {
                try
                {
                    if (_services.Library.IsFolderIndexed(result.DestinationFolder))
                    {
                        _services.Library.AttachCloudTracking(result.DestinationFolder, _root);
                        await _services.Library.RescanAsync();
                        _services.Library.NotifyFolderAddedByPath(result.DestinationFolder);
                        libraryNote = L.Get("SaveCloud_LibraryRescanned");
                    }
                    else
                    {
                        await _services.Library.AddRootAsync(result.DestinationFolder, _root);
                        libraryNote = L.Get("SaveCloud_AddedToLibrary");
                    }
                }
                catch (Exception libEx)
                {
                    libraryNote = L.Format("SaveCloud_SavedLibError", libEx.Message);
                }
            }
            else if (_services.Library.IsFolderIndexed(result.DestinationFolder))
            {
                _services.Library.AttachCloudTracking(result.DestinationFolder, _root);
                await _services.Library.RescanAsync();
                _services.Library.NotifyFolderAddedByPath(result.DestinationFolder);
            }
            else if (result.SavedCount > 0)
            {
                var trackIds = _tracks
                    .Where(t => _services.Library.GetTrack(t.Id) is not null)
                    .Select(t => t.Id)
                    .ToList();
                _services.Library.NotifyFolderAddedByPath(result.DestinationFolder, trackIds);
            }

            SetBusy(false);
            FormPanel.Visibility = Visibility.Collapsed;
            ProgressPanel.Visibility = Visibility.Collapsed;
            SummaryPanel.Visibility = Visibility.Visible;

            SummaryHeadlineText.Text = result.FailedCount == 0
                ? L.Format("SaveCloud_ExportSuccess", L.SongsCount(result.SavedCount))
                : L.Format("SaveCloud_ExportWithFailures", L.SongsCount(result.SavedCount), result.FailedCount);
            SummaryPathText.Text = result.DestinationFolder;

            if (!string.IsNullOrWhiteSpace(libraryNote))
            {
                LibraryStatusText.Text = libraryNote;
                LibraryStatusText.Visibility = Visibility.Visible;
            }
            else
            {
                LibraryStatusText.Visibility = Visibility.Collapsed;
            }

            if (result.Errors.Count > 0)
            {
                PartialErrorsText.Text = string.Join(Environment.NewLine, result.Errors);
                PartialErrorsBanner.Visibility = Visibility.Visible;
            }

            ActiveActionsPanel.Visibility = Visibility.Collapsed;
            DoneActionsPanel.Visibility = Visibility.Visible;
            OpenFolderButton.IsEnabled = Directory.Exists(result.DestinationFolder);
        }
        catch (OperationCanceledException)
        {
            SetBusy(false);
            ProgressPanel.Visibility = Visibility.Collapsed;
            ShowError(L.Get("SaveCloud_ExportCancelled"));
            SaveButton.Visibility = Visibility.Visible;
            Grid.SetColumn(CancelButton, 2);
            Grid.SetColumnSpan(CancelButton, 1);
            CancelButton.Content = L.Get("Common_Close");
        }
        catch (Exception ex)
        {
            SetBusy(false);
            ProgressPanel.Visibility = Visibility.Collapsed;
            ShowError(ex.Message);
            SaveButton.Visibility = Visibility.Visible;
            Grid.SetColumn(CancelButton, 2);
            Grid.SetColumnSpan(CancelButton, 1);
        }
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
        if (_isBusy) return;
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

    private async void InputBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && SaveButton.Visibility == Visibility.Visible && SaveButton.IsEnabled)
        {
            e.Handled = true;
            await StartPlaylistExportAsync();
        }
        else if (e.Key == Key.Escape && !_isBusy)
        {
            e.Handled = true;
            Close();
        }
    }

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        if (_isBusy)
        {
            CancelButton.IsEnabled = false;
            CancelButton.Content = L.Get("Common_Cancelling");
            _cts?.Cancel();
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
