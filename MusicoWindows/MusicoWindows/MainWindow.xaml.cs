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
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;
using MusicoWindows.Views;

namespace MusicoWindows;

public partial class MainWindow : Window
{
    private readonly AppServices _services;
    private readonly ViewContext _ctx;
    private readonly List<RadioButton> _dynamicPlaylistButtons = new();

    private bool _navExpanded = true;
    private SidePanelKind? _sidePanel;
    private string _activeRoute = "songs";
    private Guid? _activePlaylistId;

    public MainWindow(AppServices services)
    {
        InitializeComponent();
        _services = services;

        _ctx = new ViewContext
        {
            Services = _services,
            OpenTextPrompt = ShowTextPrompt,
            OpenConfirm = ShowConfirmDialog,
            OpenAlbum = ShowAlbumDetail,
            OpenArtist = ShowArtistDetail,
            OpenPlaylist = ShowPlaylistDetail
        };

        BottomPlayerBar.Initialize(_services);
        BottomPlayerBar.OpenFullPlayerRequested += () => FullPlayer.Show();
        BottomPlayerBar.OpenEqualizerRequested += () => EqDialog.Show(_services);
        BottomPlayerBar.OpenSleepTimerRequested += () => SleepDialog.Show(_services);
        BottomPlayerBar.ToggleSidePanelRequested += ToggleSidePanel;

        FullPlayer.Initialize(_ctx);
        FullPlayer.OpenEqualizerRequested += () => EqDialog.Show(_services);
        FullPlayer.OpenSleepTimerRequested += () => SleepDialog.Show(_services);
        FullPlayer.ErrorOccurred += (title, msg) => ShowConfirmDialog(title, msg, "OK", null, false);

        SideLyricsPanel.Initialize(_services);
        SideLyricsPanel.CloseRequested += () => ToggleSidePanel(_sidePanel ?? SidePanelKind.Lyrics);
        SideLyricsPanel.ErrorOccurred += (title, msg) => ShowConfirmDialog(title, msg, "OK", null, false);
        SideQueuesView.Bind(_ctx);

        _services.Library.PropertyChanged += OnLibraryChanged;
        _services.Player.PropertyChanged += OnPlayerChanged;

        ThemeManager.ThemeChanged += OnThemeChanged;
        SourceInitialized += (_, _) => AcrylicBackdrop.Apply(this, ThemeManager.IsDark);
        StateChanged += (_, _) => UpdateMaximizedState();
        Closed += (_, _) =>
        {
            ThemeManager.ThemeChanged -= OnThemeChanged;
            _services.Library.PropertyChanged -= OnLibraryChanged;
            _services.Player.PropertyChanged -= OnPlayerChanged;
        };

        UpdateThemeButton();
        RebuildDynamicPlaylists();
        NavigateTo("songs");
    }

    private void OnThemeChanged(bool isDark)
    {
        AcrylicBackdrop.Apply(this, isDark);
        UpdateThemeButton();
    }

    private void UpdateThemeButton()
    {
        if (ThemeManager.IsDark)
        {
            ThemeGlyph.Text = "\uE706";
            LblTheme.Text = "Light Theme";
        }
        else
        {
            ThemeGlyph.Text = "\uE708";
            LblTheme.Text = "Dark Theme";
        }
    }

    private void UpdateMaximizedState()
    {
        bool maximized = WindowState == WindowState.Maximized;
        RootGrid.Margin = maximized ? new Thickness(7) : new Thickness(0);
        MaxRestoreButton.Content = maximized ? "\uE923" : "\uE922";
        MaxRestoreButton.ToolTip = maximized ? "Restore" : "Maximize";
    }

    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(LibraryStore.Playlists))
        {
            RebuildDynamicPlaylists();
            if (_activeRoute == "user-playlist" &&
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

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(PlayerController.ErrorMessage) &&
            !string.IsNullOrWhiteSpace(_services.Player.ErrorMessage))
        {
            string msg = _services.Player.ErrorMessage;
            ShowConfirmDialog("Playback Problem", msg, "OK", () => _services.Player.ClearError(), false);
        }
    }

    private void RebuildDynamicPlaylists()
    {
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

            rb.ContextMenu = TrackMenuHelper.BuildTrackMenu(
                _services,
                pl.TrackIds,
                null,
                ShowTextPrompt,
                menu =>
                {
                    menu.Items.Add(new Separator());
                    menu.Items.Add(TrackMenuHelper.CreateItem("Rename...", "\uE70F", () =>
                    {
                        ShowTextPrompt("Rename Playlist", "Enter a new name for this playlist.", pl.Name, "Rename",
                            name => _services.Library.RenamePlaylist(pid, name));
                    }));
                    menu.Items.Add(TrackMenuHelper.CreateItem("Delete", "\uE74D", () =>
                    {
                        ShowConfirmDialog($"Delete \"{pl.Name}\"?",
                            "This removes the playlist. Your music files are not affected.",
                            "Delete",
                            () => _services.Library.DeletePlaylist(pid),
                            true);
                    }));
                });

            DynamicPlaylistsStack.Children.Add(rb);
            _dynamicPlaylistButtons.Add(rb);
        }
    }

    private void NavigateTo(string route)
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
                break;
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
        }
    }

    private void ShowAlbumDetail(AlbumInfo album)
    {
        var detail = new AlbumDetailView();
        detail.BackRequested += () => NavigateTo(_activeRoute == "artists" ? "artists" : "albums");
        detail.Bind(_ctx, album);
        ContentHost.Content = detail;
    }

    private void ShowArtistDetail(string artist)
    {
        var detail = new ArtistDetailView();
        detail.BackRequested += () => NavigateTo("artists");
        detail.Bind(_ctx, artist);
        ContentHost.Content = detail;
    }

    private void ShowPlaylistDetail(PlaylistRef playlistRef) => ShowPlaylistDetail(playlistRef, showBack: true);

    private void ShowPlaylistDetail(PlaylistRef playlistRef, bool showBack)
    {
        var detail = new PlaylistDetailView();
        detail.BackRequested += () =>
        {
            NavPlaylists.IsChecked = true;
            NavigateTo("playlists");
        };
        detail.Bind(_ctx, playlistRef, showBack);
        ContentHost.Content = detail;
    }

    private void ToggleSidePanel(SidePanelKind kind)
    {
        if (_sidePanel == kind)
        {
            _sidePanel = null;
            SidePanelColumn.Width = new GridLength(0);
            SidePanelBorder.Visibility = Visibility.Collapsed;
            SideLyricsPanel.Visibility = Visibility.Collapsed;
            SideQueuesView.Visibility = Visibility.Collapsed;
        }
        else
        {
            _sidePanel = kind;
            SidePanelColumn.Width = new GridLength(340);
            SidePanelBorder.Visibility = Visibility.Visible;
            SideLyricsPanel.Visibility = kind == SidePanelKind.Lyrics ? Visibility.Visible : Visibility.Collapsed;
            SideQueuesView.Visibility = kind == SidePanelKind.Queue ? Visibility.Visible : Visibility.Collapsed;
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
        NavPaneColumn.Width = new GridLength(_navExpanded ? 240 : 48);

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
        LblTheme.Visibility = labelVis;
        LblRescan.Visibility = labelVis;

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
            "New Playlist",
            "Enter a name for the playlist.",
            string.Empty,
            "Create",
            name => _services.Library.CreatePlaylist(name));
    }

    private void ToggleTheme_Click(object sender, RoutedEventArgs e)
    {
        ThemeManager.ToggleTheme(Application.Current);
    }

    private async void RescanFooter_Click(object sender, RoutedEventArgs e)
    {
        await _services.Library.RescanAsync();
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
            if (EqDialog.IsOpen) { EqDialog.Close(); e.Handled = true; return; }
            if (SleepDialog.IsOpen) { SleepDialog.Close(); e.Handled = true; return; }
            if (FullPlayer.IsOpen) { FullPlayer.Close(); e.Handled = true; return; }
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
            if (PromptDialog.IsOpen || ConfirmOverlay.IsOpen) return;
            _services.Player.TogglePlayPause();
            e.Handled = true;
        }
    }
}
