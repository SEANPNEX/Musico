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
using MusicoWindows.Core;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public sealed class SongRowItem
{
    public required int Index { get; init; }
    public required Track Track { get; init; }
    public required string Subtitle { get; init; }
    public required string DurationText { get; init; }
    public required ArtworkCache ArtworkCache { get; init; }
    public required bool IsAvailable { get; init; }
    public required bool IsCurrent { get; init; }
    public required bool IsPlaying { get; init; }
    public required Brush RowBackground { get; init; }
    public required Brush TitleBrush { get; init; }

    public double RowOpacity => IsAvailable ? 1.0 : 0.42;
    public Visibility ActiveVisibility => IsCurrent ? Visibility.Visible : Visibility.Collapsed;
    public FontWeight TitleWeight => IsCurrent ? FontWeights.SemiBold : FontWeights.Normal;
    public string StateGlyph => IsPlaying ? "\uE768" : "\uE769";
}

public partial class SongsView : UserControl
{
    private enum SongSort
    {
        Title,
        Artist,
        Album,
        RecentlyAdded
    }

    private ViewContext? _ctx;
    private SongSort _sort = SongSort.Title;
    private SearchField _searchField = SearchField.Keyword;
    private bool _isProgrammaticSearch;
    private bool _suppressSortSelection;
    private List<Track> _displayedTracks = new();

    public SongsView()
    {
        InitializeComponent();
        RebuildSortCombo(selectedIndex: 0);
        Unloaded += OnUnloaded;
    }

    private void RebuildSortCombo(int? selectedIndex = null)
    {
        int prev = selectedIndex ?? Math.Max(0, SortCombo.SelectedIndex);
        _suppressSortSelection = true;
        try
        {
            SortCombo.Items.Clear();
            SortCombo.Items.Add(L.Get("Songs_SortTitle"));
            SortCombo.Items.Add(L.Get("Songs_SortArtist"));
            SortCombo.Items.Add(L.Get("Songs_SortAlbum"));
            SortCombo.Items.Add(L.Get("Songs_SortRecentlyAdded"));
            if (SortCombo.Items.Count > 0)
                SortCombo.SelectedIndex = Math.Clamp(prev, 0, SortCombo.Items.Count - 1);
        }
        finally
        {
            _suppressSortSelection = false;
        }
    }

    public void Bind(ViewContext ctx)
    {
        Detach();
        _ctx = ctx;
        _ctx.Services.Library.PropertyChanged += OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged += OnPlayerChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        Refresh();
    }

    public void FocusSearch()
    {
        SearchBox.Focus();
        SearchBox.SelectAll();
    }

    public void ApplySearch(string query, SearchField field)
    {
        _isProgrammaticSearch = true;
        try
        {
            _searchField = field;
            SearchBox.Text = query;
            SearchBox.CaretIndex = SearchBox.Text.Length;
        }
        finally
        {
            _isProgrammaticSearch = false;
        }
        Refresh();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => Detach();

    private void Detach()
    {
        LocalizationManager.LanguageChanged -= OnLanguageChanged;
        if (_ctx is null) return;
        _ctx.Services.Library.PropertyChanged -= OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged -= OnPlayerChanged;
    }

    private void OnLanguageChanged(ResolvedLanguage _)
    {
        RebuildSortCombo();
        Refresh();
    }

    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(LibraryStore.Tracks) or nameof(LibraryStore.Roots))
            Refresh();
    }

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(PlayerController.CurrentTrack) or nameof(PlayerController.IsPlaying))
            Refresh();
    }

    public void Refresh()
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;
        string query = SearchBox.Text.Trim();

        SearchPlaceholder.Visibility = string.IsNullOrEmpty(SearchBox.Text) ? Visibility.Visible : Visibility.Collapsed;
        ClearSearchButton.Visibility = string.IsNullOrEmpty(SearchBox.Text) ? Visibility.Collapsed : Visibility.Visible;

        bool showFieldChip = _searchField != SearchField.Keyword && !string.IsNullOrEmpty(query);
        SearchFieldChip.Visibility = showFieldChip ? Visibility.Visible : Visibility.Collapsed;
        if (showFieldChip)
        {
            SearchFieldLabel.Text = L.SearchFieldLabel(_searchField);
        }

        IReadOnlyList<Track> list;
        if (!string.IsNullOrEmpty(query))
        {
            list = lib.Search(query, _searchField);
        }
        else
        {
            list = _sort switch
            {
                SongSort.Artist => lib.Tracks.OrderBy(t => t.Artist, StringComparer.CurrentCultureIgnoreCase)
                    .ThenBy(t => t.Title, StringComparer.CurrentCultureIgnoreCase).ToList(),
                SongSort.Album => lib.Tracks.OrderBy(t => t.Album, StringComparer.CurrentCultureIgnoreCase)
                    .ThenBy(t => t.Title, StringComparer.CurrentCultureIgnoreCase).ToList(),
                SongSort.RecentlyAdded => lib.Tracks.OrderByDescending(t => t.DateAddedUtc).ToList(),
                _ => lib.Tracks
            };
        }

        _displayedTracks = list.ToList();
        CountText.Text = L.SongsCount(_displayedTracks.Count);
        ActionBar.Visibility = string.IsNullOrEmpty(query) && _displayedTracks.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

        if (_displayedTracks.Count == 0)
        {
            SongsList.ItemsSource = null;
            SongsList.Visibility = Visibility.Collapsed;
            EmptyState.Visibility = Visibility.Visible;

            if (lib.Tracks.Count == 0)
            {
                EmptyTitleText.Text = L.Get("Songs_EmptyTitle");
                EmptyDescText.Text = L.Get("Songs_EmptyDesc");
            }
            else
            {
                EmptyTitleText.Text = L.Format("Songs_NoSearchResultsTitle", query);
                EmptyDescText.Text = L.Get("Songs_NoSearchResultsDesc");
            }
            return;
        }

        SongsList.Visibility = Visibility.Visible;
        EmptyState.Visibility = Visibility.Collapsed;

        var currentId = _ctx.Services.Player.CurrentTrack?.Id;
        bool isPlaying = _ctx.Services.Player.IsPlaying;
        var accentSubtle = (Brush)FindResource("AccentSubtleBrush");
        var accentBrush = (Brush)FindResource("AccentBrush");
        var primaryBrush = (Brush)FindResource("TextPrimaryBrush");

        var items = new List<SongRowItem>(_displayedTracks.Count);
        for (int i = 0; i < _displayedTracks.Count; i++)
        {
            var t = _displayedTracks[i];
            bool isCurr = t.Id == currentId;
            items.Add(new SongRowItem
            {
                Index = i,
                Track = t,
                Subtitle = _sort == SongSort.Album
                    ? $"{L.DisplayArtist(t.Artist)} — {L.DisplayAlbum(t.Album)}"
                    : L.DisplayArtist(t.Artist),
                DurationText = TimeFormatter.Format(t.DurationSeconds),
                ArtworkCache = lib.Artwork,
                IsAvailable = lib.IsAvailable(t),
                IsCurrent = isCurr,
                IsPlaying = isCurr && isPlaying,
                RowBackground = isCurr ? accentSubtle : Brushes.Transparent,
                TitleBrush = isCurr ? accentBrush : primaryBrush
            });
        }

        SongsList.ItemsSource = items;
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (!_isProgrammaticSearch)
        {
            _searchField = SearchField.Keyword;
        }
        Refresh();
    }

    private void ClearSearchField_Click(object sender, RoutedEventArgs e)
    {
        _searchField = SearchField.Keyword;
        Refresh();
    }

    private void ClearSearch_Click(object sender, RoutedEventArgs e)
    {
        _searchField = SearchField.Keyword;
        SearchBox.Text = string.Empty;
        SearchBox.Focus();
    }

    private void SortCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressSortSelection) return;
        _sort = SortCombo.SelectedIndex switch
        {
            1 => SongSort.Artist,
            2 => SongSort.Album,
            3 => SongSort.RecentlyAdded,
            _ => SongSort.Title
        };
        Refresh();
    }

    private void PlayAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _displayedTracks.Count == 0) return;
        var ids = _displayedTracks.Select(t => t.Id).ToList();
        if (_ctx.Services.Queues.Active.Shuffle) _ctx.Services.Player.ToggleShuffle();
        _ctx.Services.Player.Play(ids, 0);
    }

    private void ShuffleAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _displayedTracks.Count == 0) return;
        var ids = _displayedTracks.Select(t => t.Id).ToList();
        if (!_ctx.Services.Queues.Active.Shuffle) _ctx.Services.Player.ToggleShuffle();
        _ctx.Services.Player.Play(ids, Random.Shared.Next(ids.Count));
    }

    private void SongsList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        // Prevent selection highlight sticking
        SongsList.SelectedItem = null;
    }

    private void SongRow_MouseEnter(object sender, MouseEventArgs e)
    {
        if (sender is Border border && border.DataContext is SongRowItem item)
        {
            if (!item.IsCurrent)
                border.SetResourceReference(Border.BackgroundProperty, "SubtleHoverBrush");
            border.SetResourceReference(Border.BorderBrushProperty, "RevealBorderBrush");
        }
    }

    private void SongRow_MouseLeave(object sender, MouseEventArgs e)
    {
        if (sender is Border border && border.DataContext is SongRowItem item)
        {
            border.Background = item.RowBackground;
            border.BorderBrush = Brushes.Transparent;
        }
    }

    private void SongRow_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (_ctx is null || sender is not Border { DataContext: SongRowItem item } || !item.IsAvailable)
            return;

        var ids = _displayedTracks.Select(t => t.Id).ToList();
        string query = SearchBox.Text.Trim();
        if (!string.IsNullOrEmpty(query))
        {
            string queueName = QueueStore.FormatSearchQueueName(_searchField, query);
            _ctx.Services.Player.PlayInNewQueue(ids, queueName, item.Index);
        }
        else
        {
            _ctx.Services.Player.Play(ids, item.Index);
        }
    }

    private void SongRow_MouseRightButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (_ctx is null || sender is not Border { DataContext: SongRowItem item } border)
            return;

        var menu = TrackMenuHelper.BuildTrackMenu(
            _ctx.Services,
            [item.Track.Id],
            item.Track.Id,
            _ctx.OpenTextPrompt,
            null,
            _ctx.OpenSaveCloudTrack,
            _ctx.OpenSongInfo);
        menu.PlacementTarget = border;
        menu.IsOpen = true;
        e.Handled = true;
    }
}
