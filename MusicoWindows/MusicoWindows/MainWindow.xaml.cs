using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Controls;
using MusicoWindows.Core;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;
using MusicoWindows.Update;
using MusicoWindows.Views;

namespace MusicoWindows;

public partial class MainWindow : Window
{
    private readonly AppServices _services;
    private readonly ViewContext _ctx;
    private readonly WindowPreferences _windowPreferences;
    private readonly List<RadioButton> _dynamicPlaylistButtons = new();

    private bool _navExpanded = true;
    private SidePanelKind? _sidePanel;
    private string _activeRoute = "songs";
    private Guid? _activePlaylistId;
    private bool _closeRequestPending;
    private WindowState _restoreWindowState = WindowState.Normal;

    public MainWindow(AppServices services)
    {
        InitializeComponent();
        _services = services;
        _windowPreferences = new WindowPreferences(services.StateDirectory, services.Update.CurrentVersion.ToString());

        _ctx = new ViewContext
        {
            Services = _services,
            OpenTextPrompt = ShowTextPrompt,
            OpenConfirm = ShowConfirmDialog,
            OpenAlbum = ShowAlbumDetail,
            OpenArtist = ShowArtistDetail,
            OpenPlaylist = ShowPlaylistDetail,
            OpenAddCloudFolder = () => CloudDialog.Show(_services),
            OpenSaveCloudPlaylist = (root, tracks) => SaveCloudDialog.Show(_services, root, tracks),
            OpenSaveCloudTrack = StartSingleTrackExport,
            OpenUpdateTrackedFolder = root => SaveCloudDialog.ShowTrackedFolderUpdate(_services, root),
            OpenUpdateTrackedSubfolder = (root, relPath) => SaveCloudDialog.ShowTrackedFolderUpdate(_services, root, relPath),
            OpenSongInfo = ShowSongInfo,
            OpenSearch = OpenSearchWithField,
            OpenExtensions = () =>
            {
                NavExtensions.IsChecked = true;
                NavigateTo("extensions");
            }
        };

        BottomPlayerBar.Initialize(_ctx);
        BottomPlayerBar.OpenFullPlayerRequested += () => FullPlayer.Show();
        BottomPlayerBar.OpenEqualizerRequested += () => EqDialog.Show(_services);
        BottomPlayerBar.OpenSleepTimerRequested += () => SleepDialog.Show(_services);
        BottomPlayerBar.ToggleSidePanelRequested += ToggleSidePanel;

        FullPlayer.Initialize(_ctx);
        FullPlayer.OpenEqualizerRequested += () => EqDialog.Show(_services);
        FullPlayer.OpenSleepTimerRequested += () => SleepDialog.Show(_services);
        FullPlayer.ErrorOccurred += (title, msg) => ShowConfirmDialog(title, msg, L.Get("Common_OK"), null, false);

        SideLyricsPanel.Initialize(_services);
        SideLyricsPanel.CloseRequested += () => ToggleSidePanel(_sidePanel ?? SidePanelKind.Lyrics);
        SideLyricsPanel.ErrorOccurred += (title, msg) => ShowConfirmDialog(title, msg, L.Get("Common_OK"), null, false);
        SideQueuesView.Bind(_ctx);

        _services.Library.PropertyChanged += OnLibraryChanged;
        _services.Player.PropertyChanged += OnPlayerChanged;
        _services.Update.UpdateStateChanged += OnUpdateStateChanged;
        _services.Downloads.Changed += OnDownloadsChanged;

        ThemeManager.ThemeChanged += OnThemeChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        SourceInitialized += (_, _) => AcrylicBackdrop.Apply(this, ThemeManager.IsDark);
        Loaded += OnMainWindowLoaded;
        StateChanged += (_, _) =>
        {
            if (WindowState != WindowState.Minimized)
                _restoreWindowState = WindowState;
            UpdateMaximizedState();
        };
        Closed += (_, _) =>
        {
            ThemeManager.ThemeChanged -= OnThemeChanged;
            LocalizationManager.LanguageChanged -= OnLanguageChanged;
            _services.Library.PropertyChanged -= OnLibraryChanged;
            _services.Player.PropertyChanged -= OnPlayerChanged;
            _services.Update.UpdateStateChanged -= OnUpdateStateChanged;
            _services.Downloads.Changed -= OnDownloadsChanged;
        };

        UpdateThemeButton();
        RebuildDynamicPlaylists();
        RefreshDownloadsFooter();
        NavigateTo("songs");
    }

    private void OnThemeChanged(bool isDark)
    {
        AcrylicBackdrop.Apply(this, isDark);
        UpdateThemeButton();
    }

    private void OnLanguageChanged(ResolvedLanguage _)
    {
        UpdateThemeButton();
        UpdateMaximizedState();
        RebuildDynamicPlaylists();
        RefreshDownloadsFooter();
    }

    private void UpdateThemeButton()
    {
        if (ThemeManager.IsDark)
        {
            ThemeGlyph.Text = "\uE706";
            LblTheme.Text = L.Get("Nav_LightTheme");
        }
        else
        {
            ThemeGlyph.Text = "\uE708";
            LblTheme.Text = L.Get("Nav_DarkTheme");
        }
    }

    private void UpdateMaximizedState()
    {
        bool maximized = WindowState == WindowState.Maximized;
        RootGrid.Margin = maximized ? new Thickness(7) : new Thickness(0);
        MaxRestoreButton.Content = maximized ? "\uE923" : "\uE922";
        MaxRestoreButton.ToolTip = maximized ? L.Get("Window_Restore") : L.Get("Window_Maximize");
    }

    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(LibraryStore.Playlists) or nameof(LibraryStore.Favourites) or nameof(LibraryStore.Tracks))
        {
            RebuildDynamicPlaylists();
            if (e.PropertyName == nameof(LibraryStore.Playlists) &&
                _activeRoute == "user-playlist" &&
                _activePlaylistId is Guid pid &&
                _services.Library.Playlists.All(p => p.Id != pid))
            {
                NavPlaylists.IsChecked = true;
                NavigateTo("playlists");
            }
        }
        else if (e.PropertyName == nameof(LibraryStore.IsScanning))
        {
            FooterRescanButton.IsEnabled = !_services.Library.IsScanning;
        }
    }

    private void OnDownloadsChanged()
    {
        Dispatcher.BeginInvoke(RefreshDownloadsFooter);
    }

    private void RefreshDownloadsFooter()
    {
        var jobs = _services.Downloads.Jobs;
        if (jobs.Count == 0)
        {
            DownloadsFooterButton.Visibility = Visibility.Collapsed;
            return;
        }

        DownloadsFooterButton.Visibility = Visibility.Visible;
        var activeJobs = jobs.Where(j => j.IsActive).ToList();
        var active = activeJobs.FirstOrDefault();

        if (active is not null)
        {
            DownloadsFooterTitleText.Text = activeJobs.Count == 1
                ? active.Title
                : L.Format("Downloads_ActiveCount", activeJobs.Count);

            if (active.OverallFraction is double frac)
            {
                DownloadsFooterProgressBar.IsIndeterminate = false;
                DownloadsFooterProgressBar.Maximum = 1;
                DownloadsFooterProgressBar.Value = frac;
            }
            else
            {
                DownloadsFooterProgressBar.IsIndeterminate = true;
                DownloadsFooterProgressBar.Maximum = 1;
                DownloadsFooterProgressBar.Value = 0;
            }

            string pct = DownloadManager.FormatPercentage(active.OverallFraction);
            string speed = active.BytesPerSecond > 0 ? DownloadManager.FormatSpeed(active.BytesPerSecond) : string.Empty;
            string bytes = active.TotalBytes is long tb && tb > 0
                ? L.Format("SaveCloud_BytesOfTotal", DownloadManager.FormatBytes(active.BytesDownloaded), DownloadManager.FormatBytes(tb))
                : active.BytesDownloaded > 0
                    ? DownloadManager.FormatBytes(active.BytesDownloaded)
                    : string.Empty;

            var parts = new List<string>();
            if (!string.IsNullOrEmpty(pct)) parts.Add(pct);
            if (!string.IsNullOrEmpty(speed)) parts.Add(speed);
            if (parts.Count == 0 && !string.IsNullOrEmpty(bytes)) parts.Add(bytes);
            if (parts.Count == 0)
            {
                parts.Add(active.TotalItems > 1 ? $"{active.CompletedItems} / {active.TotalItems}" : L.Get("SaveCloud_Preparing"));
            }

            DownloadsFooterMetaText.Text = string.Join(" · ", parts);
            DownloadsFooterCancelButton.Visibility = _navExpanded ? Visibility.Visible : Visibility.Collapsed;
            DownloadsFooterCancelButton.Tag = active.Id;
            DownloadsFooterBadgeDot.Visibility = _navExpanded ? Visibility.Collapsed : Visibility.Visible;
        }
        else
        {
            var latest = jobs[0];
            DownloadsFooterTitleText.Text = latest.Title;
            DownloadsFooterProgressBar.IsIndeterminate = false;
            DownloadsFooterProgressBar.Maximum = 1;
            DownloadsFooterProgressBar.Value = latest.Status == DownloadJobStatus.Completed ? 1 : 0;
            DownloadsFooterMetaText.Text = latest.Status switch
            {
                DownloadJobStatus.Completed => L.Get("Downloads_AllComplete"),
                DownloadJobStatus.Cancelled => L.Get("Downloads_StatusCancelled"),
                _ => L.Get("Downloads_StatusFailed")
            };
            DownloadsFooterCancelButton.Visibility = Visibility.Collapsed;
            DownloadsFooterCancelButton.Tag = null;
            DownloadsFooterBadgeDot.Visibility = Visibility.Collapsed;
        }
    }

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PlayerController.ErrorMessage) &&
            !string.IsNullOrWhiteSpace(_services.Player.ErrorMessage))
        {
            string msg = _services.Player.ErrorMessage;
            ShowConfirmDialog(L.Get("Player_PlaybackProblemTitle"), msg, L.Get("Common_OK"), () => _services.Player.ClearError(), false);
        }
    }

    private ContextMenu BuildSidebarPlaylistMenu(
        string queueName,
        IReadOnlyList<string> trackIds,
        Action<ContextMenu>? appendExtra = null)
    {
        return TrackMenuHelper.BuildTrackMenu(
            _services,
            trackIds,
            null,
            ShowTextPrompt,
            menu =>
            {
                if (trackIds.Count > 0)
                {
                    menu.Items.Insert(0, TrackMenuHelper.CreateItem(
                        L.Get("Common_Play"),
                        "\uE768",
                        () => _services.Player.PlayInNamedQueue(queueName, trackIds)));
                    menu.Items.Insert(1, new Separator());
                }
                appendExtra?.Invoke(menu);
            },
            _ctx.OpenSaveCloudTrack);
    }

    private void RebuildDynamicPlaylists()
    {
        var favIds = _services.Library.GetFavouriteTracks(_services.History).Select(t => t.Id).ToList();
        NavFavourites.ContextMenu = BuildSidebarPlaylistMenu(L.Get("Nav_Favourites"), favIds);

        DynamicPlaylistsStack.Children.Clear();
        _dynamicPlaylistButtons.Clear();

        foreach (var pl in _services.Library.Playlists)
        {
            Guid pid = pl.Id;
            var rb = new RadioButton
            {
                Style = (Style)FindResource("Win10NavItemStyle"),
                GroupName = "MainNav",
                Tag = pid,
                IsChecked = _activeRoute == "user-playlist" && _activePlaylistId == pid
            };

            var content = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(14, 0, 14, 0) };
            content.Children.Add(new TextBlock
            {
                Text = "\uE8D6",
                FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                FontSize = 15,
                Width = 22,
                VerticalAlignment = VerticalAlignment.Center
            });
            var label = new TextBlock
            {
                Text = pl.Name,
                Margin = new Thickness(12, 0, 0, 0),
                VerticalAlignment = VerticalAlignment.Center,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Visibility = _navExpanded ? Visibility.Visible : Visibility.Collapsed
            };
            content.Children.Add(label);
            rb.Content = content;

            rb.Checked += (_, _) =>
            {
                _activeRoute = "user-playlist";
                _activePlaylistId = pid;
                ShowPlaylistDetail(PlaylistRef.User(pid), showBack: false);
            };

            var plTrackIds = _services.Library.GetPlaylistTracks(pid, _services.History).Select(t => t.Id).ToList();
            rb.ContextMenu = BuildSidebarPlaylistMenu(
                pl.Name,
                plTrackIds,
                menu =>
                {
                    menu.Items.Add(new Separator());
                    menu.Items.Add(TrackMenuHelper.CreateItem(L.Get("Common_RenameEllipsis"), "\uE70F", () =>
                    {
                        ShowTextPrompt(L.Get("Playlists_RenameTitle"), L.Get("Playlists_RenameMessage"), pl.Name, L.Get("Common_Rename"),
                            name => _services.Library.RenamePlaylist(pid, name));
                    }));
                    menu.Items.Add(TrackMenuHelper.CreateItem(L.Get("Common_Delete"), "\uE74D", () =>
                    {
                        ShowConfirmDialog(L.Format("Playlists_DeleteConfirmTitle", pl.Name),
                            L.Get("Playlists_DeleteConfirmMessage"),
                            L.Get("Common_Delete"),
                            () => _services.Library.DeletePlaylist(pid),
                            true);
                    }));
                });

            DynamicPlaylistsStack.Children.Add(rb);
            _dynamicPlaylistButtons.Add(rb);
        }
    }

    private void NavigateTo(string route, double? slideFromX = null)
    {
        _activeRoute = route;
        _activePlaylistId = null;

        switch (route)
        {
            case "folders":
                var fv = new FoldersView();
                fv.Bind(_ctx);
                ContentHost.Content = fv;
                break;
            case "songs":
                var sv = new SongsView();
                sv.Bind(_ctx);
                ContentHost.Content = sv;
                break;
            case "albums":
                var alv = new AlbumsView();
                alv.Bind(_ctx);
                ContentHost.Content = alv;
                break;
            case "artists":
                var arv = new ArtistsView();
                arv.Bind(_ctx);
                ContentHost.Content = arv;
                break;
            case "playlists":
                var plv = new PlaylistsView();
                plv.Bind(_ctx);
                ContentHost.Content = plv;
                break;
            case "favourites":
                ShowPlaylistDetail(PlaylistRef.Favourites, showBack: false);
                return;
            case "queues":
                var qv = new QueuesView();
                qv.Bind(_ctx);
                ContentHost.Content = qv;
                break;
            case "stats":
                var stv = new StatsView();
                stv.Bind(_ctx);
                ContentHost.Content = stv;
                break;
            case "extensions":
                var ev = new ExtensionsView();
                ev.Bind(_ctx);
                ContentHost.Content = ev;
                break;
        }

        if (slideFromX is double offset)
        {
            Win10Animations.AnimateSlideX(ContentHost.Content as UIElement, offset);
        }
        else
        {
            Win10Animations.AnimatePageEntrance(ContentHost.Content as UIElement);
        }
    }

    private void ShowAlbumDetail(AlbumInfo album)
    {
        var detail = new AlbumDetailView();
        detail.BackRequested += () => NavigateTo(_activeRoute == "artists" ? "artists" : "albums", slideFromX: -24);
        detail.Bind(_ctx, album);
        ContentHost.Content = detail;
        Win10Animations.AnimateSlideX(detail, 24);
    }

    private void ShowArtistDetail(string artist)
    {
        var detail = new ArtistDetailView();
        detail.BackRequested += () => NavigateTo("artists", slideFromX: -24);
        detail.Bind(_ctx, artist);
        ContentHost.Content = detail;
        Win10Animations.AnimateSlideX(detail, 24);
    }

    private void ShowPlaylistDetail(PlaylistRef playlistRef) => ShowPlaylistDetail(playlistRef, showBack: true);

    private void ShowPlaylistDetail(PlaylistRef playlistRef, bool showBack)
    {
        var detail = new PlaylistDetailView();
        detail.BackRequested += () =>
        {
            NavPlaylists.IsChecked = true;
            NavigateTo("playlists", slideFromX: -24);
        };
        detail.Bind(_ctx, playlistRef, showBack);
        ContentHost.Content = detail;
        if (showBack)
        {
            Win10Animations.AnimateSlideX(detail, 24);
        }
        else
        {
            Win10Animations.AnimatePageEntrance(detail);
        }
    }

    private void ToggleSidePanel(SidePanelKind kind)
    {
        if (_sidePanel == kind)
        {
            _sidePanel = null;
            double currentWidth = SidePanelColumn.Width.Value;
            Win10Animations.AnimatePanelWidth(SidePanelColumn, currentWidth, 0, 180, () =>
            {
                if (_sidePanel is null)
                {
                    SidePanelBorder.Visibility = Visibility.Collapsed;
                    SideLyricsPanel.Visibility = Visibility.Collapsed;
                    SideQueuesView.Visibility = Visibility.Collapsed;
                }
            });
        }
        else
        {
            bool wasClosed = _sidePanel is null;
            _sidePanel = kind;
            SidePanelBorder.Visibility = Visibility.Visible;
            SideLyricsPanel.Visibility = kind == SidePanelKind.Lyrics ? Visibility.Visible : Visibility.Collapsed;
            SideQueuesView.Visibility = kind == SidePanelKind.Queue ? Visibility.Visible : Visibility.Collapsed;

            if (wasClosed)
            {
                Win10Animations.AnimatePanelWidth(SidePanelColumn, SidePanelColumn.Width.Value, 340, 200);
                Win10Animations.AnimatePanelIn(SidePanelBorder, 28);
            }
            else
            {
                Win10Animations.AnimatePanelIn(kind == SidePanelKind.Lyrics ? SideLyricsPanel : SideQueuesView, 16);
            }

            if (kind == SidePanelKind.Lyrics)
            {
                _ = SideLyricsPanel.LoadLyricsAsync(forceReload: false);
            }
            else
            {
                SideQueuesView.Refresh();
            }
        }
        BottomPlayerBar.SetActiveSidePanel(_sidePanel);
    }

    internal void StartSingleTrackExport(MusicoWindows.Models.Track track, string destinationDirectory)
    {
        SaveCloudDialog.ShowSingleTrackExport(_services, track, destinationDirectory);
    }

    internal void ShowSongInfo(MusicoWindows.Models.Track track)
    {
        SongInfoDialog.Show(_ctx, track);
    }

    private void OpenSearchWithField(string query, SearchField field)
    {
        if (FullPlayer.IsOpen) FullPlayer.Close();
        NavSongs.IsChecked = true;
        if (ContentHost.Content is not SongsView)
        {
            NavigateTo("songs");
        }
        if (ContentHost.Content is SongsView sv)
        {
            sv.ApplySearch(query, field);
        }
    }

    private void ShowTextPrompt(string title, string message, string initialText, string confirmLabel, Action<string> onConfirm)
    {
        PromptDialog.Show(title, message, initialText, confirmLabel, onConfirm);
    }

    private void ShowConfirmDialog(string title, string message, string confirmLabel, Action? onConfirm, bool showCancel)
    {
        ConfirmOverlay.Show(title, message, confirmLabel, onConfirm, showCancel);
    }

    private void NavItem_Checked(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        if (sender is RadioButton { Tag: string route })
        {
            NavigateTo(route);
        }
    }

    private void Hamburger_Click(object sender, RoutedEventArgs e)
    {
        _navExpanded = !_navExpanded;
        double fromW = NavPaneColumn.Width.Value;
        double toW = _navExpanded ? 240 : 48;
        Win10Animations.AnimatePanelWidth(NavPaneColumn, fromW, toW, 200);

        var labelVis = _navExpanded ? Visibility.Visible : Visibility.Collapsed;
        LblFolders.Visibility = labelVis;
        HdrLibrary.Visibility = labelVis;
        LblSongs.Visibility = labelVis;
        LblAlbums.Visibility = labelVis;
        LblArtists.Visibility = labelVis;
        HdrPlaylists.Visibility = labelVis;
        LblPlaylists.Visibility = labelVis;
        LblFavourites.Visibility = labelVis;
        LblNewPlaylist.Visibility = labelVis;
        LblQueues.Visibility = labelVis;
        LblStats.Visibility = labelVis;
        LblExit.Visibility = labelVis;
        LblExtensions.Visibility = labelVis;
        LblTheme.Visibility = labelVis;
        LblLanguage.Visibility = labelVis;
        LblRescan.Visibility = labelVis;
        LblCheckUpdates.Visibility = labelVis;
        DownloadsFooterDetails.Visibility = labelVis;
        RefreshDownloadsFooter();

        foreach (var rb in _dynamicPlaylistButtons)
        {
            if (rb.Content is StackPanel sp && sp.Children.Count > 1)
            {
                sp.Children[1].Visibility = labelVis;
            }
        }
    }

    private void NewPlaylist_Click(object sender, RoutedEventArgs e)
    {
        ShowTextPrompt(
            L.Get("Nav_NewPlaylist"),
            L.Get("Playlists_NewMessage"),
            string.Empty,
            L.Get("Common_Create"),
            name => _services.Library.CreatePlaylist(name));
    }

    private void ToggleTheme_Click(object sender, RoutedEventArgs e)
    {
        ThemeManager.ToggleTheme(Application.Current);
    }

    private void LanguageFooter_Click(object sender, RoutedEventArgs e)
    {
        var menu = new ContextMenu();
        menu.Items.Add(CreateLanguageMenuItem(L.Get("Lang_FollowSystem"), AppLanguage.System));
        menu.Items.Add(CreateLanguageMenuItem(L.Get("Lang_English"), AppLanguage.English));
        menu.Items.Add(CreateLanguageMenuItem(L.Get("Lang_SimplifiedChinese"), AppLanguage.SimplifiedChinese));
        menu.PlacementTarget = LanguageButton;
        menu.Placement = PlacementMode.Right;
        menu.IsOpen = true;
    }

    private MenuItem CreateLanguageMenuItem(string header, AppLanguage mode)
    {
        bool isSelected = LocalizationManager.CurrentMode == mode;
        var item = new MenuItem
        {
            Header = header,
            IsCheckable = true,
            IsChecked = isSelected
        };
        if (isSelected)
        {
            item.Icon = new TextBlock
            {
                Text = "\uE73E",
                FontFamily = (FontFamily)FindResource("Win10GlyphFont"),
                FontSize = 12,
                Foreground = (Brush)FindResource("AccentBrush"),
                VerticalAlignment = VerticalAlignment.Center,
                HorizontalAlignment = HorizontalAlignment.Center
            };
        }
        item.Click += (_, _) => LocalizationManager.SetMode(Application.Current, mode);
        return item;
    }

    private async void OnMainWindowLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnMainWindowLoaded;
        try
        {
            await _services.Update.CheckForUpdatesAsync(manual: false);
        }
        catch
        {
            // Startup check is silent.
        }
    }

    private void OnUpdateStateChanged(object? sender, UpdateCheckResult result)
    {
        Dispatcher.BeginInvoke(() =>
        {
            UpdateBadgeDot.Visibility = result.IsUpdateAvailable ? Visibility.Visible : Visibility.Collapsed;
        });
    }

    private async void RescanFooter_Click(object sender, RoutedEventArgs e)
    {
        await _services.Library.RescanAsync();
    }

    private void CheckUpdatesFooter_Click(object sender, RoutedEventArgs e)
    {
        UpdateDialog.Show(_services, () => ((App)Application.Current).RequestExit());
    }

    private void DownloadsFooter_Click(object sender, RoutedEventArgs e)
    {
        DownloadsOverlay.Show(_services, jobId => SaveCloudDialog.ShowDownloadJob(_services, jobId));
    }

    private void DownloadsFooterCancel_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (DownloadsFooterCancelButton.Tag is Guid jobId)
        {
            _services.Downloads.Cancel(jobId);
        }
    }

    private void Minimize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private void MaxRestore_Click(object sender, RoutedEventArgs e)
    {
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    protected override void OnClosing(CancelEventArgs e)
    {
        base.OnClosing(e);
        if (e.Cancel || ((App)Application.Current).IsExiting) return;

        e.Cancel = true;
        if (_closeRequestPending || WindowBehaviorOverlay.IsOpen) return;
        _closeRequestPending = true;
        // Finish the Closing event before showing a prompt or initiating a real shutdown.
        Dispatcher.BeginInvoke(() =>
        {
            _closeRequestPending = false;
            if (((App)Application.Current).IsExiting) return;
            if (_windowPreferences.RequiresConfirmation)
            {
                var previousState = WindowState;
                RestoreFromTray();
                ShowWindowBehaviorDialog(closing: true, onCancel: () => WindowState = previousState);
            }
            else
            {
                ApplyCloseBehavior();
            }
        });
    }

    private void ExitSettings_Click(object sender, RoutedEventArgs e) => ShowWindowBehaviorDialog(closing: false);

    private void ShowWindowBehaviorDialog(bool closing, Action? onCancel = null)
    {
        WindowBehaviorOverlay.Show(_windowPreferences.Behavior, closing, behavior =>
        {
            try
            {
                _windowPreferences.Save(behavior, confirmVersion: closing);
            }
            catch (Exception ex) when (ex is System.IO.IOException or UnauthorizedAccessException)
            {
                MessageBox.Show(this, L.Get("WindowBehavior_SaveFailed"), L.Get("WindowBehavior_SettingsTitle"),
                    MessageBoxButton.OK, MessageBoxImage.Error);
                return false;
            }

            if (closing)
                Dispatcher.BeginInvoke(ApplyCloseBehavior);
            return true;
        }, onCancel);
    }

    private void ApplyCloseBehavior()
    {
        var app = (App)Application.Current;
        if (app.IsExiting) return;
        if (_windowPreferences.Behavior == WindowCloseBehavior.Exit)
        {
            app.RequestExit();
        }
        else if (app.EnsureTrayIcon())
        {
            Hide();
        }
        else
        {
            RestoreFromTray();
            MessageBox.Show(this, L.Get("Tray_Unavailable"), "Musico", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    internal void RestoreFromTray()
    {
        if (((App)Application.Current).IsExiting) return;
        Show();
        if (WindowState == WindowState.Minimized)
            WindowState = _restoreWindowState;
        Activate();
    }

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (WindowBehaviorOverlay.IsOpen)
        {
            if (e.Key == Key.Escape)
            {
                WindowBehaviorOverlay.Close();
                e.Handled = true;
            }
            return;
        }

        if (e.Key == Key.Escape)
        {
            if (ConfirmOverlay.IsOpen) { ConfirmOverlay.Close(); e.Handled = true; return; }
            if (PromptDialog.IsOpen) { PromptDialog.Close(); e.Handled = true; return; }
            if (SongInfoDialog.IsOpen) { SongInfoDialog.Close(); e.Handled = true; return; }
            if (UpdateDialog.IsOpen) { UpdateDialog.Close(); e.Handled = true; return; }
            if (DownloadsOverlay.IsOpen) { DownloadsOverlay.Close(); e.Handled = true; return; }
            if (SaveCloudDialog.IsOpen) { SaveCloudDialog.Close(); e.Handled = true; return; }
            if (CloudDialog.IsOpen) { CloudDialog.Close(); e.Handled = true; return; }
            if (EqDialog.IsOpen) { EqDialog.Close(); e.Handled = true; return; }
            if (SleepDialog.IsOpen) { SleepDialog.Close(); e.Handled = true; return; }
            if (FullPlayer.IsOpen) { FullPlayer.Close(); e.Handled = true; return; }
            if (ContentHost.Content is ExtensionsView ev && ev.HandleEscape()) { e.Handled = true; return; }
        }

        if (Keyboard.Modifiers == ModifierKeys.Control)
        {
            if (e.Key == Key.F)
            {
                if (FullPlayer.IsOpen) FullPlayer.Close();
                NavSongs.IsChecked = true;
                NavigateTo("songs");
                if (ContentHost.Content is SongsView sv)
                {
                    Dispatcher.BeginInvoke(sv.FocusSearch);
                }
                e.Handled = true;
                return;
            }
            if (e.Key == Key.Left)
            {
                _services.Player.Previous();
                e.Handled = true;
                return;
            }
            if (e.Key == Key.Right)
            {
                _services.Player.Next();
                e.Handled = true;
                return;
            }
        }

        if (e.Key == Key.Space && Keyboard.Modifiers == ModifierKeys.None)
        {
            if (Keyboard.FocusedElement is TextBoxBase) return;
            if (PromptDialog.IsOpen || ConfirmOverlay.IsOpen || CloudDialog.IsOpen || SaveCloudDialog.IsOpen || DownloadsOverlay.IsOpen || UpdateDialog.IsOpen || SongInfoDialog.IsOpen) return;
            _services.Player.TogglePlayPause();
            e.Handled = true;
        }
    }
}
