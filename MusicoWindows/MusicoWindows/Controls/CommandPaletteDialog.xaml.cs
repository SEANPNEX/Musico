using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;
using MusicoWindows.Localization;
using MusicoWindows.Stores;
using MusicoWindows.Theme;
using MusicoWindows.Views;

namespace MusicoWindows.Controls;

public sealed class CommandPaletteEntry
{
    public required string Title { get; init; }
    public string? Subtitle { get; init; }
    public required string CategoryLabel { get; init; }
    public required string Glyph { get; init; }
    public required string SearchKeywords { get; init; }
    public required int GroupOrder { get; init; }
    public required Action Execute { get; init; }

    public Visibility SubtitleVisibility => string.IsNullOrWhiteSpace(Subtitle)
        ? Visibility.Collapsed
        : Visibility.Visible;
}

public partial class CommandPaletteDialog : UserControl
{
    private const int MaxDisplayedEntries = 60;

    private ViewContext? _ctx;
    private Action<string>? _navigateToRoute;
    private Action? _openNowPlaying;
    private Action? _toggleMiniPlayer;
    private Action? _openEqualizer;
    private Action? _openSleepTimer;
    private List<CommandPaletteEntry> _allEntries = new();

    public CommandPaletteDialog()
    {
        InitializeComponent();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Show(
        ViewContext ctx,
        Action<string> navigateToRoute,
        Action openNowPlaying,
        Action toggleMiniPlayer,
        Action openEqualizer,
        Action openSleepTimer)
    {
        _ctx = ctx;
        _navigateToRoute = navigateToRoute;
        _openNowPlaying = openNowPlaying;
        _toggleMiniPlayer = toggleMiniPlayer;
        _openEqualizer = openEqualizer;
        _openSleepTimer = openSleepTimer;

        BuildCatalog();
        SearchBox.Text = string.Empty;
        ApplyFilter();

        Visibility = Visibility.Visible;
        Win10Animations.AnimateModalOpen(BackdropGrid, DialogCard);

        Dispatcher.BeginInvoke(DispatcherPriority.Input, new Action(() =>
        {
            SearchBox.Focus();
            SearchBox.SelectAll();
        }));
    }

    public void Close()
    {
        Visibility = Visibility.Collapsed;
    }

    private void BuildCatalog()
    {
        if (_ctx is null) return;
        var services = _ctx.Services;
        var lib = services.Library;
        var list = new List<CommandPaletteEntry>();

        string actionCat = L.Get("CommandPalette_CategoryAction");
        string viewCat = L.Get("CommandPalette_CategoryView");
        string queueCat = L.Get("CommandPalette_CategoryQueue");
        string playlistCat = L.Get("CommandPalette_CategoryPlaylist");
        string albumCat = L.Get("CommandPalette_CategoryAlbum");
        string artistCat = L.Get("CommandPalette_CategoryArtist");
        string songCat = L.Get("CommandPalette_CategorySong");

        void AddView(string titleKey, string glyph, string route, string keywords)
        {
            string title = L.Get(titleKey);
            list.Add(new CommandPaletteEntry
            {
                Title = title,
                CategoryLabel = viewCat,
                Glyph = glyph,
                SearchKeywords = $"{title} {keywords} {viewCat}",
                GroupOrder = 0,
                Execute = () => _navigateToRoute?.Invoke(route)
            });
        }

        void AddAction(string title, string? subtitle, string glyph, string keywords, Action action)
        {
            list.Add(new CommandPaletteEntry
            {
                Title = title,
                Subtitle = subtitle,
                CategoryLabel = actionCat,
                Glyph = glyph,
                SearchKeywords = $"{title} {subtitle} {keywords} {actionCat}",
                GroupOrder = 0,
                Execute = action
            });
        }

        // Actions
        AddAction(L.Get("FullPlayer_NowPlaying"), null, "\uE8D6", "now playing full player", () => _openNowPlaying?.Invoke());
        AddAction(L.Get("MiniPlayer_Title"), "Ctrl+Shift+N", "\uEE49", "mini player compact floating window", () => _toggleMiniPlayer?.Invoke());
        AddAction(L.Get("Equalizer_Title"), null, "\uE9E9", "equalizer eq audio", () => _openEqualizer?.Invoke());
        AddAction(L.Get("SleepTimer_Title"), null, "\uE708", "sleep timer", () => _openSleepTimer?.Invoke());
        AddAction(L.Get("Nav_Rescan"), L.Get("Nav_RescanTooltip"), "\uE72C", "rescan library scan refresh", () =>
        {
            _ = services.Library.RescanAsync();
            _ctx.ShowToast?.Invoke(L.Get("Settings_Scanning"), null, null);
        });

        // Views
        AddView("Nav_Folders", "\uE8B7", "folders", "folders directories");
        AddView("Nav_Songs", "\uE8D6", "songs", "songs tracks library");
        AddView("Nav_Albums", "\uE93C", "albums", "albums");
        AddView("Nav_Artists", "\uE720", "artists", "artists");
        AddView("Nav_AllPlaylists", "\uE8FD", "playlists", "playlists");
        AddView("Nav_Favourites", "\uEB51", "favourites", "favourites favorites liked");
        AddView("Nav_Queues", "\uE90B", "queues", "queues");
        AddView("Nav_Stats", "\uE9D9", "stats", "stats statistics history");
        AddView("Nav_Extensions", "\uEA86", "extensions", "extensions plugins");
        AddView("Nav_Settings", "\uE713", "settings", "settings preferences theme appearance");

        // Queues
        foreach (var q in services.Queues.Queues)
        {
            Guid qid = q.Id;
            string qName = q.Name;
            bool isActive = q.Id == services.Queues.ActiveId;
            string sub = isActive
                ? $"{L.SongsCount(q.TrackIds.Count)} · {L.Get("Queues_CurrentBadge")}"
                : L.SongsCount(q.TrackIds.Count);
            list.Add(new CommandPaletteEntry
            {
                Title = qName,
                Subtitle = sub,
                CategoryLabel = queueCat,
                Glyph = "\uE90B",
                SearchKeywords = $"{qName} {queueCat}",
                GroupOrder = 1,
                Execute = () =>
                {
                    services.Player.SwitchQueue(qid);
                    _ctx.ShowToast?.Invoke(L.Format("Toast_SwitchedQueue", qName), null, null);
                }
            });
        }

        // Playlists (Favourites + User playlists)
        var favCount = lib.GetFavouriteTracks(services.History).Count;
        list.Add(new CommandPaletteEntry
        {
            Title = L.Get("Nav_Favourites"),
            Subtitle = L.SongsCount(favCount),
            CategoryLabel = playlistCat,
            Glyph = "\uEB51",
            SearchKeywords = $"{L.Get("Nav_Favourites")} favourites favorites {playlistCat}",
            GroupOrder = 2,
            Execute = () => _ctx.OpenPlaylist(PlaylistRef.Favourites)
        });

        foreach (var pl in lib.Playlists)
        {
            Guid pid = pl.Id;
            list.Add(new CommandPaletteEntry
            {
                Title = pl.Name,
                Subtitle = L.SongsCount(pl.TrackIds.Count),
                CategoryLabel = playlistCat,
                Glyph = "\uE8FD",
                SearchKeywords = $"{pl.Name} {playlistCat}",
                GroupOrder = 2,
                Execute = () => _ctx.OpenPlaylist(PlaylistRef.User(pid))
            });
        }

        // Albums
        foreach (var album in lib.Albums)
        {
            var captured = album;
            string displayAlbum = L.DisplayAlbum(album.Title);
            string displayArtist = L.DisplayArtist(album.Artist);
            list.Add(new CommandPaletteEntry
            {
                Title = displayAlbum,
                Subtitle = displayArtist,
                CategoryLabel = albumCat,
                Glyph = "\uE93C",
                SearchKeywords = $"{displayAlbum} {displayArtist} {album.Title} {album.Artist} {albumCat}",
                GroupOrder = 3,
                Execute = () => _ctx.OpenAlbum(captured)
            });
        }

        // Artists
        foreach (string artist in lib.Artists)
        {
            string captured = artist;
            string displayArtist = L.DisplayArtist(artist);
            list.Add(new CommandPaletteEntry
            {
                Title = displayArtist,
                CategoryLabel = artistCat,
                Glyph = "\uE720",
                SearchKeywords = $"{displayArtist} {artist} {artistCat}",
                GroupOrder = 4,
                Execute = () => _ctx.OpenArtist(captured)
            });
        }

        // Songs
        var allTrackIds = lib.Tracks.Select(t => t.Id).ToList();
        for (int i = 0; i < lib.Tracks.Count; i++)
        {
            var track = lib.Tracks[i];
            int idx = i;
            string sub = $"{L.DisplayArtist(track.Artist)} · {L.DisplayAlbum(track.Album)}";
            list.Add(new CommandPaletteEntry
            {
                Title = track.Title,
                Subtitle = sub,
                CategoryLabel = songCat,
                Glyph = "\uE8D6",
                SearchKeywords = $"{track.Title} {sub} {track.Artist} {track.Album} {songCat}",
                GroupOrder = 5,
                Execute = () =>
                {
                    services.Player.PlayInNamedQueue(L.Get("Nav_Songs"), allTrackIds, startAt: idx);
                }
            });
        }

        _allEntries = list;
    }

    private void ApplyFilter()
    {
        string query = SearchBox.Text.Trim();
        SearchPlaceholder.Visibility = string.IsNullOrEmpty(SearchBox.Text) ? Visibility.Visible : Visibility.Collapsed;

        List<CommandPaletteEntry> matches;
        if (string.IsNullOrEmpty(query))
        {
            matches = _allEntries
                .Where(e => e.GroupOrder <= 2)
                .Take(MaxDisplayedEntries)
                .ToList();
        }
        else
        {
            matches = _allEntries
                .Where(e => e.Title.Contains(query, StringComparison.OrdinalIgnoreCase) ||
                            (!string.IsNullOrEmpty(e.Subtitle) && e.Subtitle.Contains(query, StringComparison.OrdinalIgnoreCase)) ||
                            e.SearchKeywords.Contains(query, StringComparison.OrdinalIgnoreCase))
                .OrderBy(e => e.Title.StartsWith(query, StringComparison.OrdinalIgnoreCase) ? 0 : 1)
                .ThenBy(e => e.GroupOrder)
                .ThenBy(e => e.Title, StringComparer.CurrentCultureIgnoreCase)
                .Take(MaxDisplayedEntries)
                .ToList();
        }

        ResultsList.ItemsSource = matches;
        if (matches.Count > 0)
        {
            ResultsList.SelectedIndex = 0;
            ResultsList.ScrollIntoView(matches[0]);
            ResultsList.Visibility = Visibility.Visible;
            EmptyResultsText.Visibility = Visibility.Collapsed;
        }
        else
        {
            ResultsList.Visibility = Visibility.Collapsed;
            EmptyResultsText.Visibility = Visibility.Visible;
        }
    }

    private void ExecuteSelected()
    {
        CommandPaletteEntry? entry = ResultsList.SelectedItem as CommandPaletteEntry;
        if (entry is null && ResultsList.Items.Count > 0)
        {
            entry = ResultsList.Items[0] as CommandPaletteEntry;
        }
        if (entry is null) return;

        Close();
        entry.Execute();
    }

    private void MoveSelection(int delta)
    {
        int count = ResultsList.Items.Count;
        if (count == 0) return;

        int current = ResultsList.SelectedIndex;
        int next = current < 0
            ? (delta > 0 ? 0 : count - 1)
            : (current + delta + count) % count;

        ResultsList.SelectedIndex = next;
        ResultsList.ScrollIntoView(ResultsList.Items[next]);
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e) => ApplyFilter();

    private void SearchBox_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.Down:
                MoveSelection(1);
                e.Handled = true;
                break;
            case Key.Up:
                MoveSelection(-1);
                e.Handled = true;
                break;
            case Key.Enter:
                ExecuteSelected();
                e.Handled = true;
                break;
            case Key.Escape:
                Close();
                e.Handled = true;
                break;
        }
    }

    private void ResultsList_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.Enter:
                ExecuteSelected();
                e.Handled = true;
                break;
            case Key.Escape:
                Close();
                e.Handled = true;
                break;
        }
    }

    private void ResultItem_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (sender is FrameworkElement { DataContext: CommandPaletteEntry entry })
        {
            Close();
            entry.Execute();
            e.Handled = true;
        }
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
    private void Overlay_MouseDown(object sender, MouseButtonEventArgs e) => Close();
    private void Card_MouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;
}
