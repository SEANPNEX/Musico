using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Controls;
using MusicoWindows.Converters;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public partial class StatsView : UserControl
{
    private enum StatsSection
    {
        Recent,
        Plays,
        Stale,
        Never
    }

    private ViewContext? _ctx;
    private StatsSection _section = StatsSection.Recent;
    private IReadOnlyList<Track> _tracks = Array.Empty<Track>();
    private bool _suppressOrderSelection;

    public StatsView()
    {
        InitializeComponent();
        RebuildPlaysOrderCombo(selectedIndex: 0);
        Unloaded += OnUnloaded;
    }

    private void RebuildPlaysOrderCombo(int? selectedIndex = null)
    {
        int prev = selectedIndex ?? Math.Max(0, PlaysOrderCombo.SelectedIndex);
        _suppressOrderSelection = true;
        try
        {
            PlaysOrderCombo.Items.Clear();
            PlaysOrderCombo.Items.Add(L.Get("Stats_MostPlayedFirst"));
            PlaysOrderCombo.Items.Add(L.Get("Stats_LeastPlayedFirst"));
            if (PlaysOrderCombo.Items.Count > 0)
                PlaysOrderCombo.SelectedIndex = Math.Clamp(prev, 0, PlaysOrderCombo.Items.Count - 1);
        }
        finally
        {
            _suppressOrderSelection = false;
        }
    }

    public void Bind(ViewContext ctx)
    {
        Detach();
        _ctx = ctx;
        _ctx.Services.History.PropertyChanged += OnHistoryChanged;
        _ctx.Services.Library.PropertyChanged += OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged += OnPlayerChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        Refresh();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => Detach();

    private void Detach()
    {
        LocalizationManager.LanguageChanged -= OnLanguageChanged;
        if (_ctx is null) return;
        _ctx.Services.History.PropertyChanged -= OnHistoryChanged;
        _ctx.Services.Library.PropertyChanged -= OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged -= OnPlayerChanged;
    }

    private void OnLanguageChanged(ResolvedLanguage _)
    {
        RebuildPlaysOrderCombo();
        Refresh();
    }

    private void OnHistoryChanged(object? sender, PropertyChangedEventArgs e) => Refresh();
    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e) => Refresh();

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(PlayerController.CurrentTrack) or nameof(PlayerController.IsPlaying))
            Refresh();
    }

    public void Refresh()
    {
        if (_ctx is null) return;
        var history = _ctx.Services.History;
        var lib = _ctx.Services.Library;

        TotalPlaysText.Text = history.TotalPlays.ToString();
        TotalPlaysLabel.Text = history.TotalPlays == 1 ? L.Get("Stats_PlaysUnitSingular") : L.Get("Stats_PlaysUnitPlural");
        TotalTimeText.Text = TimeFormatter.FormatListeningDuration(history.TotalSeconds);
        NeverPlayedCountText.Text = history.NeverPlayed(lib.Tracks).Count.ToString();

        RefreshInsights(history, lib);

        PlaysOrderCombo.Visibility = _section == StatsSection.Plays ? Visibility.Visible : Visibility.Collapsed;

        _tracks = _section switch
        {
            StatsSection.Recent => history.RecentlyPlayed(lib.Tracks),
            StatsSection.Plays => PlaysOrderCombo.SelectedIndex == 1
                ? history.LeastPlayed(lib.Tracks)
                : history.MostPlayed(lib.Tracks),
            StatsSection.Stale => history.NotPlayedLately(lib.Tracks),
            StatsSection.Never => history.NeverPlayed(lib.Tracks),
            _ => Array.Empty<Track>()
        };

        ActionBar.Visibility = _tracks.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        TracksStack.Children.Clear();

        if (_tracks.Count == 0)
        {
            TracksStack.Visibility = Visibility.Collapsed;
            EmptyState.Visibility = Visibility.Visible;
            (EmptyTitleText.Text, EmptyDescText.Text) = _section switch
            {
                StatsSection.Recent => (L.Get("Stats_EmptyRecentTitle"), L.Get("Stats_EmptyRecentDesc")),
                StatsSection.Plays => (L.Get("Stats_EmptyPlaysTitle"), L.Get("Stats_EmptyPlaysDesc")),
                StatsSection.Stale => (L.Get("Stats_EmptyStaleTitle"), L.Format("Stats_EmptyStaleDesc", PlayHistoryStore.StaleAfterDays)),
                StatsSection.Never => (L.Get("Stats_EmptyNeverTitle"), L.Get("Stats_EmptyNeverDesc")),
                _ => (string.Empty, string.Empty)
            };
            return;
        }

        TracksStack.Visibility = Visibility.Visible;
        EmptyState.Visibility = Visibility.Collapsed;

        var ids = _tracks.Select(t => t.Id).ToList();
        for (int i = 0; i < _tracks.Count; i++)
        {
            int idx = i;
            var track = _tracks[i];
            bool isCurrent = _ctx.Services.Player.CurrentTrack?.Id == track.Id;
            bool isPlaying = isCurrent && _ctx.Services.Player.IsPlaying;

            var row = new TrackRowControl();
            row.Bind(
                _ctx.Services,
                track,
                BuildCaption(history, track),
                isCurrent,
                isPlaying,
                lib.IsAvailable(track),
                () => _ctx.Services.Player.PlayInNamedQueue("Stats", ids, startAt: idx),
                _ctx.OpenTextPrompt,
                null,
                _ctx.OpenSaveCloudTrack,
                _ctx.OpenSongInfo);
            TracksStack.Children.Add(row);
        }
    }

    private string BuildCaption(PlayHistoryStore history, Track track)
    {
        int count = history.GetCount(track.Id);
        string plays = L.PlaysCount(count);
        DateTimeOffset? last = history.GetLastPlayed(track.Id);
        string? when = last is DateTimeOffset dt ? L.FormatRelativeTime(dt) : null;
        string artist = L.DisplayArtist(track.Artist);

        return _section switch
        {
            StatsSection.Recent or StatsSection.Stale => when is not null ? $"{when} · {artist}" : artist,
            StatsSection.Plays => $"{plays} · {artist}",
            _ => artist
        };
    }

    private void Tab_Checked(object sender, RoutedEventArgs e)
    {
        if (TabRecent is null) return;
        if (TabRecent.IsChecked == true) _section = StatsSection.Recent;
        else if (TabPlays.IsChecked == true) _section = StatsSection.Plays;
        else if (TabStale.IsChecked == true) _section = StatsSection.Stale;
        else if (TabNever.IsChecked == true) _section = StatsSection.Never;
        Refresh();
    }

    private void PlaysOrderCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressOrderSelection) return;
        Refresh();
    }

    private void PlayAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _tracks.Count == 0) return;
        var ids = _tracks.Select(t => t.Id).ToList();
        _ctx.Services.Player.PlayInNamedQueue("Stats", ids, startAt: 0, shuffle: false);
    }

    private void ShuffleAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _tracks.Count == 0) return;
        var ids = _tracks.Select(t => t.Id).ToList();
        _ctx.Services.Player.PlayInNamedQueue("Stats", ids, startAt: Random.Shared.Next(ids.Count), shuffle: true);
    }

    private void RefreshInsights(PlayHistoryStore history, LibraryStore lib)
    {
        if (_ctx is null) return;
        var allTracks = lib.Tracks;
        var topArtists = history.GetTopArtists(allTracks, limit: 5);
        var topAlbums = history.GetTopAlbums(allTracks, limit: 5);

        if (history.TotalPlays <= 0 || allTracks.Count == 0 || (topArtists.Count == 0 && topAlbums.Count == 0))
        {
            InsightsCard.Visibility = Visibility.Collapsed;
            return;
        }

        InsightsCard.Visibility = Visibility.Visible;

        double fraction = history.GetPlayedLibraryFraction(allTracks);
        int totalEligible = allTracks.Count(t => !t.IsEphemeral);
        int playedCount = allTracks.Count(t => !t.IsEphemeral && history.GetCount(t.Id) > 0);
        int pct = (int)Math.Round(fraction * 100);
        ExploredProgressBar.Value = Math.Clamp(fraction, 0, 1);
        ExploredSummaryText.Text = L.Format("Stats_LibraryExploredSummary", pct, playedCount, totalEligible);

        TopArtistsStack.Children.Clear();
        TopArtistsSection.Visibility = topArtists.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        int maxArtistPlays = topArtists.Count > 0 ? Math.Max(1, topArtists[0].PlayCount) : 1;
        foreach (var stat in topArtists)
        {
            string artistKey = stat.Artist;
            string displayArtist = L.DisplayArtist(stat.Artist);
            var avatar = new ArtistAvatar { Width = 28, Height = 28 };
            avatar.SetSource(lib.Artwork, stat.ArtworkKey, displayArtist, 96);

            TopArtistsStack.Children.Add(BuildInsightBarRow(
                avatar,
                displayArtist,
                L.PlaysCount(stat.PlayCount),
                stat.PlayCount,
                maxArtistPlays,
                () => _ctx.OpenArtist(artistKey)));
        }

        TopAlbumsStack.Children.Clear();
        TopAlbumsSection.Visibility = topAlbums.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        int maxAlbumPlays = topAlbums.Count > 0 ? Math.Max(1, topAlbums[0].PlayCount) : 1;
        foreach (var stat in topAlbums)
        {
            string displayAlbum = $"{L.DisplayAlbum(stat.Album)} · {L.DisplayArtist(stat.Artist)}";
            var art = new ArtworkImage { Width = 28, Height = 28, GlyphSize = 12 };
            art.SetSource(lib.Artwork, stat.ArtworkKey, 96);

            var targetAlbum = lib.Albums.FirstOrDefault(a =>
                    string.Equals(a.Title, stat.Album, StringComparison.Ordinal) &&
                    string.Equals(a.Artist, stat.Artist, StringComparison.Ordinal))
                ?? lib.Albums.FirstOrDefault(a => string.Equals(a.Title, stat.Album, StringComparison.Ordinal));

            TopAlbumsStack.Children.Add(BuildInsightBarRow(
                art,
                displayAlbum,
                L.PlaysCount(stat.PlayCount),
                stat.PlayCount,
                maxAlbumPlays,
                targetAlbum is not null ? () => _ctx.OpenAlbum(targetAlbum) : null));
        }
    }

    private Border BuildInsightBarRow(
        UIElement leadingIcon,
        string label,
        string trailingPlays,
        int playCount,
        int maxPlayCount,
        Action? onClick)
    {
        var row = new Border
        {
            Background = Brushes.Transparent,
            BorderBrush = Brushes.Transparent,
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(4),
            Padding = new Thickness(6, 5, 6, 5),
            Margin = new Thickness(0, 0, 0, 4),
            Cursor = onClick is not null ? Cursors.Hand : Cursors.Arrow
        };

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(36) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        Grid.SetColumn(leadingIcon, 0);
        grid.Children.Add(leadingIcon);

        var rightStack = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        var topRow = new Grid { Margin = new Thickness(0, 0, 0, 4) };
        topRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        topRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var labelTb = new TextBlock
        {
            Text = label,
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 12.5,
            FontWeight = FontWeights.SemiBold,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Margin = new Thickness(0, 0, 8, 0)
        };
        labelTb.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimaryBrush");
        Grid.SetColumn(labelTb, 0);

        var countTb = new TextBlock
        {
            Text = trailingPlays,
            FontFamily = (FontFamily)FindResource("Win10Font"),
            FontSize = 11.5,
            VerticalAlignment = VerticalAlignment.Center
        };
        countTb.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
        Grid.SetColumn(countTb, 1);

        topRow.Children.Add(labelTb);
        topRow.Children.Add(countTb);

        var bar = new ProgressBar
        {
            Style = (Style)FindResource("Win10ProgressBarStyle"),
            Height = 4,
            Minimum = 0,
            Maximum = Math.Max(1, maxPlayCount),
            Value = Math.Clamp(playCount, 0, Math.Max(1, maxPlayCount))
        };

        rightStack.Children.Add(topRow);
        rightStack.Children.Add(bar);
        Grid.SetColumn(rightStack, 1);
        grid.Children.Add(rightStack);

        row.Child = grid;

        if (onClick is not null)
        {
            row.MouseEnter += (_, _) =>
            {
                row.SetResourceReference(Border.BackgroundProperty, "SubtleHoverBrush");
                row.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
            };
            row.MouseLeave += (_, _) =>
            {
                row.Background = Brushes.Transparent;
                row.BorderBrush = Brushes.Transparent;
            };
            row.MouseLeftButtonUp += (_, _) => onClick();
        }

        return row;
    }
}
