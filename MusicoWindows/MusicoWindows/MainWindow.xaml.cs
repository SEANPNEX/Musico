using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
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
    private readonly WindowStateStore _windowStateStore;
    private readonly bool _isSmokeTest;
    private readonly List<RadioButton> _dynamicPlaylistButtons = new();

    private MiniPlayerWindow? _miniPlayer;
    private bool _navExpanded = true;
    private SidePanelKind? _sidePanel;
    private string _activeRoute = "queues";
    private Guid? _activePlaylistId;
    private string? _cachedBgImagePath;
    private DateTime _cachedBgImageWriteUtc;

    public MainWindow(AppServices services, bool isSmokeTest = false)
    {
        InitializeComponent();
        _services = services;
        _isSmokeTest = isSmokeTest;
        _windowStateStore = new WindowStateStore(services.StateDirectory);

        _ctx = new ViewContext
        {
            Services = _services,
            OpenTextPrompt = ShowTextPrompt,
            OpenConfirm = ShowConfirmDialog,
            OpenDeleteChoice = ShowDeleteChoice,
            ShowToast = ShowToastMessage,
            OpenAlbum = ShowAlbumDetail,
            OpenArtist = ShowArtistDetail,
            OpenPlaylist = ShowPlaylistDetail,
            OpenAddCloudFolder = () => CloudDialog.Show(_services),
            OpenEditCloudSource = (root, relPath, preselect) => EditCloudDialog.Show(_services, root, relPath, preselect),
            OpenSaveCloudPlaylist = (root, tracks) => SaveCloudDialog.Show(_services, root, tracks),
            OpenSaveCloudTrack = StartSingleTrackExport,
            OpenUpdateTrackedFolder = root => SaveCloudDialog.ShowTrackedFolderUpdate(_services, root),
            OpenUpdateTrackedSubfolder = (root, relPath) => SaveCloudDialog.ShowTrackedFolderUpdate(_services, root, relPath),
            OpenSongInfo = ShowSongInfo,
            OpenSearch = OpenSearchWithField,
            OpenCollectionPreview = (extId, extName, item, canImport) =>
                ShowCollectionPreview(extId, extName, item, canImport),
            OpenExtensions = () =>
            {
                NavExtensions.IsChecked = true;
                NavigateTo("extensions");
            },
            OpenUpdateDialog = () => UpdateDialog.Show(_services, () => Application.Current.Shutdown())
        };

        BottomPlayerBar.Initialize(_ctx);
        BottomPlayerBar.OpenFullPlayerRequested += () =>
        {
            ShellBodyGrid.Visibility = Visibility.Hidden;
            FullPlayer.Show();
        };
        BottomPlayerBar.OpenEqualizerRequested += () => EqDialog.Show(_services);
        BottomPlayerBar.OpenSleepTimerRequested += () => SleepDialog.Show(_services);
        BottomPlayerBar.ToggleSidePanelRequested += ToggleSidePanel;
        BottomPlayerBar.ToggleMiniPlayerRequested += ToggleMiniPlayer;

        FullPlayer.Initialize(_ctx);
        FullPlayer.CloseRequested += () => ShellBodyGrid.Visibility = Visibility.Visible;
        FullPlayer.OpenEqualizerRequested += () => EqDialog.Show(_services);
        FullPlayer.OpenSleepTimerRequested += () => SleepDialog.Show(_services);
        FullPlayer.SearchOnlineLyricsRequested += track => CloudLyricsOverlay.Show(_services, track);
        FullPlayer.ErrorOccurred += (title, msg) => ShowConfirmDialog(title, msg, L.Get("Common_OK"), null, false);
        FullPlayer.InfoOccurred += (_, msg) => ShowToastMessage(msg);

        SideLyricsPanel.Initialize(_services);
        SideLyricsPanel.CloseRequested += () => ToggleSidePanel(_sidePanel ?? SidePanelKind.Lyrics);
        SideLyricsPanel.SearchOnlineRequested += track => CloudLyricsOverlay.Show(_services, track);
        SideLyricsPanel.ErrorOccurred += (title, msg) => ShowConfirmDialog(title, msg, L.Get("Common_OK"), null, false);
        SideLyricsPanel.InfoOccurred += (_, msg) => ShowToastMessage(msg);
        SideQueuesView.Bind(_ctx);

        _services.Library.PropertyChanged += OnLibraryChanged;
        _services.Player.PropertyChanged += OnPlayerChanged;
        _services.Update.UpdateStateChanged += OnUpdateStateChanged;
        _services.Downloads.Changed += OnDownloadsChanged;

        ThemeManager.AppearanceChanged += OnAppearanceChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        SourceInitialized += (_, _) => ThemeManager.ApplyBackdrop(this);
        Loaded += OnMainWindowLoaded;
        StateChanged += (_, _) => UpdateMaximizedState();
        Closing += OnMainWindowClosing;
        Closed += (_, _) =>
        {
            _miniPlayer?.Close();
            _miniPlayer = null;
            ThemeManager.AppearanceChanged -= OnAppearanceChanged;
            LocalizationManager.LanguageChanged -= OnLanguageChanged;
            _services.Library.PropertyChanged -= OnLibraryChanged;
            _services.Player.PropertyChanged -= OnPlayerChanged;
            _services.Update.UpdateStateChanged -= OnUpdateStateChanged;
            _services.Downloads.Changed -= OnDownloadsChanged;
        };

        RefreshBackgroundImageLayer();
        ApplySidebarTextOverrides();
        RebuildDynamicPlaylists();
        RefreshDownloadsFooter();
        RestoreWindowAndShellState();
    }

    private void OnAppearanceChanged(AppearanceSettings _)
    {
        ThemeManager.ApplyBackdrop(this);
        RefreshBackgroundImageLayer();
        ApplySidebarTextOverrides();
    }

    private void ApplySidebarTextOverrides()
    {
        ThemePalette? palette = ThemeManager.CurrentPalette;
        if (palette is not null && palette.SidebarIsDark != palette.IsDark)
        {
            Brush primary = Application.Current.TryFindResource("SidebarTextPrimaryBrush") as Brush
                ?? CreateFrozenSidebarBrush(palette.SidebarTextPrimary);
            Brush secondary = Application.Current.TryFindResource("SidebarTextSecondaryBrush") as Brush
                ?? CreateFrozenSidebarBrush(palette.SidebarTextSecondary);
            Brush tertiary = Application.Current.TryFindResource("SidebarTextTertiaryBrush") as Brush
                ?? CreateFrozenSidebarBrush(palette.SidebarTextTertiary);

            SetSidebarTextResources(NavPaneBorder.Resources, primary, secondary, tertiary);
            SetSidebarTextResources(SidePanelBorder.Resources, primary, secondary, tertiary);
        }
        else
        {
            ClearSidebarTextResources(NavPaneBorder.Resources);
            ClearSidebarTextResources(SidePanelBorder.Resources);
        }
    }

    private static void SetSidebarTextResources(
        ResourceDictionary resources,
        Brush primary,
        Brush secondary,
        Brush tertiary)
    {
        resources["TextPrimaryBrush"] = primary;
        resources["TextSecondaryBrush"] = secondary;
        resources["TextTertiaryBrush"] = tertiary;
    }

    private static void ClearSidebarTextResources(ResourceDictionary resources)
    {
        resources.Remove("TextPrimaryBrush");
        resources.Remove("TextSecondaryBrush");
        resources.Remove("TextTertiaryBrush");
    }

    private static SolidColorBrush CreateFrozenSidebarBrush(ArgbColor c)
    {
        var brush = new SolidColorBrush(Color.FromArgb(c.A, c.R, c.G, c.B));
        brush.Freeze();
        return brush;
    }

    private void OnLanguageChanged(ResolvedLanguage _)
    {
        UpdateMaximizedState();
        RebuildDynamicPlaylists();
        RefreshDownloadsFooter();
    }

    private void RefreshBackgroundImageLayer()
    {
        AppearanceSettings settings = ThemeManager.CurrentSettings;
        ContentBackdropLayer.Opacity = AppearanceSettings.ClampBackgroundOpacity(settings.BackgroundOpacity);

        string? imagePath = ThemeManager.ResolveBackgroundImagePath();
        if (string.IsNullOrWhiteSpace(imagePath) || !File.Exists(imagePath))
        {
            _cachedBgImagePath = null;
            WindowBackgroundImage.Source = null;
            WindowBackgroundImage.Visibility = Visibility.Collapsed;
            WindowBackgroundScrim.Visibility = Visibility.Collapsed;
            return;
        }

        try
        {
            DateTime writeUtc = File.GetLastWriteTimeUtc(imagePath);
            if (!string.Equals(_cachedBgImagePath, imagePath, StringComparison.OrdinalIgnoreCase) ||
                _cachedBgImageWriteUtc != writeUtc ||
                WindowBackgroundImage.Source is null)
            {
                byte[] bytes = File.ReadAllBytes(imagePath);
                using var ms = new MemoryStream(bytes);
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.StreamSource = ms;
                bmp.EndInit();
                bmp.Freeze();

                WindowBackgroundImage.Source = bmp;
                _cachedBgImagePath = imagePath;
                _cachedBgImageWriteUtc = writeUtc;
            }

            WindowBackgroundImage.Opacity = settings.BackgroundImageOpacity;
            WindowBackgroundImage.Effect = settings.BackgroundImageBlur > 0.05
                ? new BlurEffect
                {
                    Radius = settings.BackgroundImageBlur,
                    KernelType = KernelType.Gaussian
                }
                : null;
            WindowBackgroundImage.Visibility = Visibility.Visible;

            ThemePalette? palette = ThemeManager.CurrentPalette;
            RgbColor scrimBase = palette is not null
                ? new RgbColor(palette.ContentBackground.R, palette.ContentBackground.G, palette.ContentBackground.B)
                : (ThemeManager.IsDark ? new RgbColor(0x14, 0x14, 0x14) : new RgbColor(0xF2, 0xF2, 0xF2));
            byte scrimAlpha = ColorMath.ComputeBackgroundScrimAlpha(settings);

            var scrimBrush = new SolidColorBrush(Color.FromArgb(scrimAlpha, scrimBase.R, scrimBase.G, scrimBase.B));
            scrimBrush.Freeze();
            WindowBackgroundScrim.Background = scrimBrush;
            WindowBackgroundScrim.Visibility = Visibility.Visible;
        }
        catch
        {
            WindowBackgroundImage.Source = null;
            WindowBackgroundImage.Visibility = Visibility.Collapsed;
            WindowBackgroundScrim.Visibility = Visibility.Collapsed;
        }
    }

    private void UpdateMaximizedState()
    {
        bool maximized = WindowState == WindowState.Maximized;
        RootGrid.Margin = maximized ? new Thickness(7) : new Thickness(0);
        MaxRestoreButton.Content = maximized ? "\uE923" : "\uE922";
        string maxTooltip = maximized ? L.Get("Window_Restore") : L.Get("Window_Maximize");
        MaxRestoreButton.ToolTip = maxTooltip;
        System.Windows.Automation.AutomationProperties.SetName(MaxRestoreButton, maxTooltip);
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
            case "settings":
                var setv = new SettingsView();
                setv.Bind(_ctx);
                ContentHost.Content = setv;
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
        if (FullPlayer.IsOpen) FullPlayer.Close();
        var detail = new AlbumDetailView();
        detail.BackRequested += () => NavigateTo(_activeRoute == "artists" ? "artists" : "albums", slideFromX: -24);
        detail.Bind(_ctx, album);
        ContentHost.Content = detail;
        Win10Animations.AnimateSlideX(detail, 24);
    }

    private void ShowArtistDetail(string artist)
    {
        if (FullPlayer.IsOpen) FullPlayer.Close();
        var detail = new ArtistDetailView();
        detail.BackRequested += () => NavigateTo("artists", slideFromX: -24);
        detail.Bind(_ctx, artist);
        ContentHost.Content = detail;
        Win10Animations.AnimateSlideX(detail, 24);
    }

    private void ShowPlaylistDetail(PlaylistRef playlistRef) => ShowPlaylistDetail(playlistRef, showBack: true);

    private void ShowPlaylistDetail(PlaylistRef playlistRef, bool showBack)
    {
        if (FullPlayer.IsOpen) FullPlayer.Close();
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

    internal void ShowCollectionPreview(
        string extensionId,
        string extensionName,
        MusicoWindows.Extensions.ExtSearchResultItem item,
        bool supportsCollectionImport)
    {
        CollectionPreviewOverlay.Show(_ctx, extensionId, extensionName, item, supportsCollectionImport);
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

    internal void ShowDeleteChoice(DeleteChoiceRequest request)
    {
        ConfirmOverlay.ShowDeleteChoice(request);
    }

    internal void ShowToastMessage(string message, string? actionLabel = null, Action? onAction = null)
    {
        ToastHostOverlay.ShowToast(message, actionLabel, onAction);
    }

    internal void ShowErrorDialog(string title, string message)
    {
        ShowConfirmDialog(title, message, L.Get("Common_OK"), null, false);
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

        SetNavLabelsVisibility(_navExpanded ? Visibility.Visible : Visibility.Collapsed);
        RefreshDownloadsFooter();
    }

    private void SetNavLabelsVisibility(Visibility labelVis)
    {
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
        LblExtensions.Visibility = labelVis;
        LblSettings.Visibility = labelVis;
        DownloadsFooterDetails.Visibility = labelVis;

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

        try
        {
            await _services.Extensions.CheckForUpdatesIfDueAsync();
        }
        catch
        {
            // Startup extension update check is silent.
        }
    }

    private void OnUpdateStateChanged(object? sender, UpdateCheckResult result)
    {
        Dispatcher.BeginInvoke(() =>
        {
            UpdateBadgeDot.Visibility = result.IsUpdateAvailable ? Visibility.Visible : Visibility.Collapsed;
        });
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

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            if (ConfirmOverlay.IsOpen) { ConfirmOverlay.Close(); e.Handled = true; return; }
            if (PromptDialog.IsOpen) { PromptDialog.Close(); e.Handled = true; return; }
            if (CommandPaletteOverlay.IsOpen) { CommandPaletteOverlay.Close(); e.Handled = true; return; }
            if (SongInfoDialog.IsOpen) { SongInfoDialog.Close(); e.Handled = true; return; }
            if (UpdateDialog.IsOpen) { UpdateDialog.Close(); e.Handled = true; return; }
            if (DownloadsOverlay.IsOpen) { DownloadsOverlay.Close(); e.Handled = true; return; }
            if (SaveCloudDialog.IsOpen) { SaveCloudDialog.Close(); e.Handled = true; return; }
            if (EditCloudDialog.IsOpen) { EditCloudDialog.Close(); e.Handled = true; return; }
            if (CloudDialog.IsOpen) { CloudDialog.Close(); e.Handled = true; return; }
            if (CollectionPreviewOverlay.IsOpen) { CollectionPreviewOverlay.Close(); e.Handled = true; return; }
            if (CloudLyricsOverlay.IsOpen) { CloudLyricsOverlay.Close(); e.Handled = true; return; }
            if (EqDialog.IsOpen) { EqDialog.Close(); e.Handled = true; return; }
            if (SleepDialog.IsOpen) { SleepDialog.Close(); e.Handled = true; return; }
            if (FullPlayer.IsOpen) { FullPlayer.Close(); e.Handled = true; return; }
            if (ContentHost.Content is ExtensionsView ev && ev.HandleEscape()) { e.Handled = true; return; }
        }

        bool isTextInputFocused = Keyboard.FocusedElement is TextBoxBase or PasswordBox;
        bool hasBlockingModal = CommandPaletteOverlay.IsOpen ||
                                PromptDialog.IsOpen ||
                                ConfirmOverlay.IsOpen ||
                                CloudDialog.IsOpen ||
                                EditCloudDialog.IsOpen ||
                                SaveCloudDialog.IsOpen ||
                                CollectionPreviewOverlay.IsOpen ||
                                CloudLyricsOverlay.IsOpen ||
                                DownloadsOverlay.IsOpen ||
                                UpdateDialog.IsOpen ||
                                SongInfoDialog.IsOpen;

        if (Keyboard.Modifiers == ModifierKeys.Control)
        {
            if (e.Key == Key.K)
            {
                if (!ConfirmOverlay.IsOpen && !PromptDialog.IsOpen)
                {
                    if (CommandPaletteOverlay.IsOpen)
                    {
                        CommandPaletteOverlay.Close();
                    }
                    else
                    {
                        OpenCommandPalette();
                    }
                    e.Handled = true;
                }
                return;
            }
            if (e.Key == Key.F)
            {
                if (FullPlayer.IsOpen) FullPlayer.Close();
                NavSongs.IsChecked = true;
                if (ContentHost.Content is not SongsView)
                {
                    NavigateTo("songs");
                }
                if (ContentHost.Content is SongsView sv)
                {
                    Dispatcher.BeginInvoke(sv.FocusSearch);
                }
                e.Handled = true;
                return;
            }
            if (e.Key == Key.OemComma)
            {
                if (FullPlayer.IsOpen) FullPlayer.Close();
                NavSettings.IsChecked = true;
                if (ContentHost.Content is not SettingsView)
                {
                    NavigateTo("settings");
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
            if (e.Key == Key.Up)
            {
                AdjustVolumeWithToast(0.05f);
                e.Handled = true;
                return;
            }
            if (e.Key == Key.Down)
            {
                AdjustVolumeWithToast(-0.05f);
                e.Handled = true;
                return;
            }
        }

        if (Keyboard.Modifiers == (ModifierKeys.Control | ModifierKeys.Shift))
        {
            if (e.Key == Key.M)
            {
                ToggleMuteWithToast();
                e.Handled = true;
                return;
            }
            if (e.Key == Key.L)
            {
                if (FullPlayer.IsOpen) FullPlayer.Close();
                ToggleSidePanel(SidePanelKind.Lyrics);
                e.Handled = true;
                return;
            }
            if (e.Key == Key.Q)
            {
                if (FullPlayer.IsOpen) FullPlayer.Close();
                ToggleSidePanel(SidePanelKind.Queue);
                e.Handled = true;
                return;
            }
            if (e.Key == Key.N)
            {
                ToggleMiniPlayer();
                e.Handled = true;
                return;
            }
        }

        if (Keyboard.Modifiers == ModifierKeys.Shift && !isTextInputFocused && !hasBlockingModal)
        {
            if (e.Key == Key.Left)
            {
                SeekBySeconds(-10.0);
                e.Handled = true;
                return;
            }
            if (e.Key == Key.Right)
            {
                SeekBySeconds(10.0);
                e.Handled = true;
                return;
            }
        }

        if (Keyboard.Modifiers == ModifierKeys.None && !isTextInputFocused && !hasBlockingModal)
        {
            if (e.Key == Key.Space)
            {
                _services.Player.TogglePlayPause();
                e.Handled = true;
                return;
            }
            if (e.Key == Key.M)
            {
                ToggleMuteWithToast();
                e.Handled = true;
                return;
            }
            if (e.Key == Key.L)
            {
                if (FullPlayer.IsOpen) FullPlayer.Close();
                ToggleSidePanel(SidePanelKind.Lyrics);
                e.Handled = true;
                return;
            }
            if (e.Key == Key.Q)
            {
                if (FullPlayer.IsOpen) FullPlayer.Close();
                ToggleSidePanel(SidePanelKind.Queue);
                e.Handled = true;
            }
        }
    }

    private void AdjustVolumeWithToast(float delta)
    {
        float next = Math.Clamp(_services.Player.Volume + delta, 0f, 1f);
        _services.Player.Volume = next;
        if (_services.Player.IsMuted && next > 0f)
        {
            _services.Player.IsMuted = false;
        }
        int pct = (int)Math.Round(_services.Player.Volume * 100);
        ShowToastMessage(L.Format("Toast_VolumePercent", pct));
    }

    private void ToggleMuteWithToast()
    {
        _services.Player.IsMuted = !_services.Player.IsMuted;
        ShowToastMessage(_services.Player.IsMuted ? L.Get("Toast_Muted") : L.Get("Toast_Unmuted"));
    }

    private void SeekBySeconds(double deltaSeconds)
    {
        if (_services.Player.CurrentTrack is null) return;
        double max = Math.Max(0, _services.Player.DurationSeconds);
        double target = Math.Clamp(_services.Player.CurrentTimeSeconds + deltaSeconds, 0, max);
        _services.Player.Seek(target);
    }

    private void OpenCommandPalette()
    {
        CommandPaletteOverlay.Show(
            _ctx,
            SelectNavAndNavigate,
            () =>
            {
                ShellBodyGrid.Visibility = Visibility.Hidden;
                FullPlayer.Show();
            },
            ToggleMiniPlayer,
            () => EqDialog.Show(_services),
            () => SleepDialog.Show(_services));
    }

    private void ToggleMiniPlayer()
    {
        if (_miniPlayer is not null)
        {
            _miniPlayer.Close();
            return;
        }

        var mini = new MiniPlayerWindow(_services);
        mini.RestoreMainWindowRequested += () =>
        {
            if (WindowState == WindowState.Minimized)
            {
                WindowState = WindowState.Normal;
            }
            Activate();
            mini.Close();
        };
        mini.Closed += (_, _) =>
        {
            _miniPlayer = null;
            BottomPlayerBar.SetMiniPlayerOpen(false);
        };
        _miniPlayer = mini;
        BottomPlayerBar.SetMiniPlayerOpen(true);
        mini.Show();
    }

    private void SelectNavAndNavigate(string route)
    {
        if (FullPlayer.IsOpen) FullPlayer.Close();

        RadioButton? target = route switch
        {
            "folders" => NavFolders,
            "songs" => NavSongs,
            "albums" => NavAlbums,
            "artists" => NavArtists,
            "playlists" => NavPlaylists,
            "favourites" => NavFavourites,
            "queues" => NavQueues,
            "stats" => NavStats,
            "extensions" => NavExtensions,
            "settings" => NavSettings,
            _ => NavQueues
        };
        string resolvedRoute = route switch
        {
            "folders" or "songs" or "albums" or "artists" or "playlists" or "favourites" or "queues" or "stats" or "extensions" or "settings" => route,
            _ => "queues"
        };

        target.IsChecked = true;
        NavigateTo(resolvedRoute);
    }

    private void RestoreWindowAndShellState()
    {
        WindowStateData state = _windowStateStore.Load();

        if (!_isSmokeTest)
        {
            WindowBounds bounds = WindowStateStore.ClampToBounds(
                state.Left,
                state.Top,
                state.Width,
                state.Height,
                SystemParameters.VirtualScreenLeft,
                SystemParameters.VirtualScreenTop,
                SystemParameters.VirtualScreenWidth,
                SystemParameters.VirtualScreenHeight);

            WindowStartupLocation = WindowStartupLocation.Manual;
            Left = bounds.Left;
            Top = bounds.Top;
            Width = bounds.Width;
            Height = bounds.Height;

            if (state.IsMaximized)
            {
                WindowState = WindowState.Maximized;
            }
        }

        if (!state.NavExpanded)
        {
            _navExpanded = false;
            NavPaneColumn.Width = new GridLength(48);
            SetNavLabelsVisibility(Visibility.Collapsed);
            RefreshDownloadsFooter();
        }

        SelectNavAndNavigate(state.LastRoute);

        if (state.SidePanel is "lyrics")
        {
            SetSidePanelImmediate(SidePanelKind.Lyrics);
        }
        else if (state.SidePanel is "queue")
        {
            SetSidePanelImmediate(SidePanelKind.Queue);
        }
    }

    private void SetSidePanelImmediate(SidePanelKind kind)
    {
        _sidePanel = kind;
        SidePanelBorder.Visibility = Visibility.Visible;
        SideLyricsPanel.Visibility = kind == SidePanelKind.Lyrics ? Visibility.Visible : Visibility.Collapsed;
        SideQueuesView.Visibility = kind == SidePanelKind.Queue ? Visibility.Visible : Visibility.Collapsed;
        SidePanelColumn.Width = new GridLength(340);

        if (kind == SidePanelKind.Lyrics)
        {
            _ = SideLyricsPanel.LoadLyricsAsync(forceReload: false);
        }
        else
        {
            SideQueuesView.Refresh();
        }
        BottomPlayerBar.SetActiveSidePanel(_sidePanel);
    }

    private void OnMainWindowClosing(object? sender, CancelEventArgs e)
    {
        _miniPlayer?.Close();
        _miniPlayer = null;

        if (_isSmokeTest) return;

        bool maximized = WindowState == WindowState.Maximized;
        Rect normalBounds = WindowState == WindowState.Normal || RestoreBounds.IsEmpty
            ? new Rect(Left, Top, Width, Height)
            : RestoreBounds;

        string lastRoute = _activeRoute == "user-playlist" ? "playlists" : _activeRoute;
        _windowStateStore.Save(new WindowStateData
        {
            Width = normalBounds.Width,
            Height = normalBounds.Height,
            Left = normalBounds.Left,
            Top = normalBounds.Top,
            IsMaximized = maximized,
            NavExpanded = _navExpanded,
            SidePanel = _sidePanel switch
            {
                SidePanelKind.Lyrics => "lyrics",
                SidePanelKind.Queue => "queue",
                _ => null
            },
            LastRoute = lastRoute
        });
    }

    private static bool IsSupportedAudioFile(string path)
    {
        if (!File.Exists(path)) return false;
        string ext = Path.GetExtension(path);
        return ext.Length > 1 && FolderScanner.AudioExtensions.Contains(ext[1..]);
    }

    private static bool HasSupportedFileDrop(DragEventArgs e)
    {
        if (!e.Data.GetDataPresent(DataFormats.FileDrop)) return false;
        if (e.Data.GetData(DataFormats.FileDrop) is not string[] paths || paths.Length == 0) return false;
        return paths.Any(p => Directory.Exists(p) || IsSupportedAudioFile(p));
    }

    private void RootGrid_DragEnter(object sender, DragEventArgs e)
    {
        if (!e.Data.GetDataPresent(DataFormats.FileDrop)) return;
        bool supported = HasSupportedFileDrop(e);
        e.Effects = supported ? DragDropEffects.Copy : DragDropEffects.None;
        DropOverlay.Visibility = supported ? Visibility.Visible : Visibility.Collapsed;
        e.Handled = true;
    }

    private void RootGrid_DragOver(object sender, DragEventArgs e)
    {
        if (!e.Data.GetDataPresent(DataFormats.FileDrop)) return;
        bool supported = HasSupportedFileDrop(e);
        e.Effects = supported ? DragDropEffects.Copy : DragDropEffects.None;
        DropOverlay.Visibility = supported ? Visibility.Visible : Visibility.Collapsed;
        e.Handled = true;
    }

    private void RootGrid_DragLeave(object sender, DragEventArgs e)
    {
        if (!e.Data.GetDataPresent(DataFormats.FileDrop)) return;
        Point pos = e.GetPosition(RootGrid);
        if (pos.X <= 0 || pos.Y <= 0 || pos.X >= RootGrid.ActualWidth || pos.Y >= RootGrid.ActualHeight)
        {
            DropOverlay.Visibility = Visibility.Collapsed;
        }
    }

    private void RootGrid_Drop(object sender, DragEventArgs e)
    {
        if (!e.Data.GetDataPresent(DataFormats.FileDrop)) return;
        DropOverlay.Visibility = Visibility.Collapsed;
        e.Handled = true;

        if (e.Data.GetData(DataFormats.FileDrop) is not string[] paths || paths.Length == 0) return;
        _ = HandleFileDropAsync(paths);
    }

    private async Task HandleFileDropAsync(string[] paths)
    {
        var dirs = paths.Where(Directory.Exists).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        var audioFiles = paths.Where(IsSupportedAudioFile).Distinct(StringComparer.OrdinalIgnoreCase).ToList();

        int addedDirs = 0;
        foreach (string dir in dirs)
        {
            try
            {
                await _services.Library.AddRootAsync(dir);
                addedDirs++;
            }
            catch (InvalidOperationException)
            {
                // Folder already indexed; skip duplicate add.
            }
            catch (Exception ex)
            {
                ShowToastMessage(ex.Message);
            }
        }

        if (audioFiles.Count > 0)
        {
            var resolvedPaths = new List<string>(audioFiles.Count);
            bool copiedAny = false;

            var existingByPath = new Dictionary<string, MusicoWindows.Models.Track>(StringComparer.OrdinalIgnoreCase);
            foreach (var t in _services.Library.Tracks)
            {
                if (_services.Library.GetFilePath(t) is { Length: > 0 } fp)
                {
                    existingByPath.TryAdd(Path.GetFullPath(fp), t);
                }
            }

            foreach (string srcPath in audioFiles)
            {
                string fullSrc = Path.GetFullPath(srcPath);
                if (existingByPath.ContainsKey(fullSrc))
                {
                    resolvedPaths.Add(fullSrc);
                    continue;
                }

                string musicDir = Path.GetFullPath(_services.MusicDirectory);
                Directory.CreateDirectory(musicDir);
                if (fullSrc.StartsWith(musicDir + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                {
                    resolvedPaths.Add(fullSrc);
                    copiedAny = true;
                    continue;
                }

                string destPath = BuildUniqueCopyDestination(musicDir, Path.GetFileName(fullSrc));
                await Task.Run(() => File.Copy(fullSrc, destPath, overwrite: false));
                resolvedPaths.Add(Path.GetFullPath(destPath));
                copiedAny = true;
            }

            if (copiedAny)
            {
                if (_services.Library.AppFolderRemoved)
                {
                    await _services.Library.RestoreAppFolderAsync();
                }
                else
                {
                    await _services.Library.RescanAsync();
                }
            }

            var refreshedByPath = new Dictionary<string, MusicoWindows.Models.Track>(StringComparer.OrdinalIgnoreCase);
            foreach (var t in _services.Library.Tracks)
            {
                if (_services.Library.GetFilePath(t) is { Length: > 0 } fp)
                {
                    refreshedByPath.TryAdd(Path.GetFullPath(fp), t);
                }
            }

            var trackIds = new List<string>();
            foreach (string p in resolvedPaths)
            {
                if (refreshedByPath.TryGetValue(p, out var matched))
                {
                    trackIds.Add(matched.Id);
                }
            }

            if (trackIds.Count > 0)
            {
                _services.Player.PlayInNamedQueue(L.Get("Drop_QueueName"), trackIds, startAt: 0);
                ShowToastMessage(L.Format("Toast_DroppedFilesPlayed", trackIds.Count));
            }
        }
        else if (addedDirs > 0)
        {
            ShowToastMessage(L.Format("Toast_DroppedFoldersAdded", addedDirs));
        }
    }

    private static string BuildUniqueCopyDestination(string targetDirectory, string fileName)
    {
        string baseName = Path.GetFileNameWithoutExtension(fileName);
        string ext = Path.GetExtension(fileName);
        string candidate = Path.Combine(targetDirectory, fileName);
        int suffix = 1;
        while (File.Exists(candidate))
        {
            candidate = Path.Combine(targetDirectory, $"{baseName} ({suffix}){ext}");
            suffix++;
        }
        return candidate;
    }
}
