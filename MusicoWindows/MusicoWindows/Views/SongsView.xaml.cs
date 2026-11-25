using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Cloud;
using MusicoWindows.Controls;
using MusicoWindows.Converters;
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public sealed class SongRowItem
{
    public required int Index { get; init; }
    public Track? Track { get; init; }
    public ExtSearchResultItem? CloudResult { get; init; }
    public required string Title { get; init; }
    public required string Subtitle { get; init; }
    public required string TrailingText { get; init; }
    public ArtworkCache? ArtworkCache { get; init; }
    public string? ArtworkKey { get; init; }
    public CloudThumbnailCache? ThumbnailCache { get; init; }
    public string? RemoteArtworkUrl { get; init; }
    public double PlaceholderGlyphSize { get; init; } = 15.0;
    public string? KindBadgeText { get; init; }
    public required bool IsAvailable { get; init; }
    public required bool IsCurrent { get; init; }
    public required bool IsPlaying { get; init; }
    public required Brush RowBackground { get; init; }
    public required Brush TitleBrush { get; init; }

    public bool IsCloudSearchItem => CloudResult is not null;
    public double RowOpacity => IsAvailable ? 1.0 : 0.42;
    public Visibility ActiveVisibility => IsCurrent ? Visibility.Visible : Visibility.Collapsed;
    public Visibility KindBadgeVisibility => string.IsNullOrEmpty(KindBadgeText) ? Visibility.Collapsed : Visibility.Visible;
    public FontWeight TitleWeight => IsCurrent ? FontWeights.SemiBold : FontWeights.Normal;
    public string StateGlyph => IsPlaying ? "\uE768" : "\uE769";
}

public partial class SongsView : UserControl
{
    private sealed record SearchSourceOption(string? ExtensionId, string Label);

    private enum SongSort
    {
        Title,
        Artist,
        Album,
        RecentlyAdded
    }

    private ViewContext? _ctx;
    private CloudSearchController? _searchController;
    private SongSort _sort = SongSort.Title;
    private SearchField _searchField = SearchField.Keyword;
    private bool _isProgrammaticSearch;
    private bool _suppressSortSelection;
    private bool _suppressSourceSelection;
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
        _searchController = new CloudSearchController(ctx.Services.Extensions);
        _searchController.StateChanged += OnCloudSearchStateChanged;
        _ctx.Services.Library.PropertyChanged += OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged += OnPlayerChanged;
        _ctx.Services.Extensions.Changed += OnExtensionsChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        RebuildSearchSourceCombo();
        Refresh();
    }

    public void FocusSearch()
    {
        SearchBox.Focus();
        SearchBox.SelectAll();
    }

    public void ApplySearch(string query, SearchField field)
    {
        if (_searchController is { IsCloudMode: true })
        {
            _searchController.SelectLocal();
            RebuildSearchSourceCombo();
        }

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
        if (_searchController is not null)
        {
            _searchController.StateChanged -= OnCloudSearchStateChanged;
            _searchController.SelectLocal();
            _searchController = null;
        }
        if (_ctx is null) return;
        _ctx.Services.Library.PropertyChanged -= OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged -= OnPlayerChanged;
        _ctx.Services.Extensions.Changed -= OnExtensionsChanged;
    }

    private void OnLanguageChanged(ResolvedLanguage _)
    {
        RebuildSortCombo();
        RebuildSearchSourceCombo();
        Refresh();
    }

    private void OnExtensionsChanged()
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (_ctx is null || _searchController is null) return;
            _searchController.SyncExtensions();
            RebuildSearchSourceCombo();
            Refresh();
        });
    }

    private void OnCloudSearchStateChanged()
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(Refresh);
            return;
        }
        Refresh();
    }

    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(LibraryStore.Tracks) or nameof(LibraryStore.Roots))
        {
            if (_searchController?.IsCloudMode != true)
            {
                Refresh();
            }
        }
    }

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(PlayerController.CurrentTrack) or nameof(PlayerController.IsPlaying))
            Refresh();
    }

    private void RebuildSearchSourceCombo()
    {
        if (_searchController is null)
        {
            SearchSourceCombo.Visibility = Visibility.Collapsed;
            return;
        }

        var sources = _searchController.AvailableSources;
        if (sources.Count == 0)
        {
            _suppressSourceSelection = true;
            try
            {
                SearchSourceCombo.ItemsSource = null;
            }
            finally
            {
                _suppressSourceSelection = false;
            }
            SearchSourceCombo.Visibility = Visibility.Collapsed;
            return;
        }

        var options = new List<SearchSourceOption>(sources.Count + 1)
        {
            new(null, L.Get("Songs_SourceLocal"))
        };
        foreach (var ext in sources)
        {
            options.Add(new(ext.ExtensionId, ext.DisplayName));
        }

        string? selectedExtId = _searchController.SelectedExtensionId;
        int targetIndex = 0;
        if (!string.IsNullOrWhiteSpace(selectedExtId))
        {
            int idx = options.FindIndex(o =>
                o.ExtensionId is not null &&
                string.Equals(o.ExtensionId, selectedExtId, StringComparison.OrdinalIgnoreCase));
            if (idx >= 0) targetIndex = idx;
        }

        _suppressSourceSelection = true;
        try
        {
            SearchSourceCombo.ItemsSource = options;
            SearchSourceCombo.SelectedIndex = targetIndex;
            SearchSourceCombo.Visibility = Visibility.Visible;
        }
        finally
        {
            _suppressSourceSelection = false;
        }
    }

    public void Refresh()
    {
        if (_ctx is null) return;

        SearchPlaceholder.Visibility = string.IsNullOrEmpty(SearchBox.Text) ? Visibility.Visible : Visibility.Collapsed;
        ClearSearchButton.Visibility = string.IsNullOrEmpty(SearchBox.Text) ? Visibility.Collapsed : Visibility.Visible;

        if (_searchController is { IsCloudMode: true })
        {
            RefreshCloudMode();
        }
        else
        {
            RefreshLocalMode();
        }
    }

    private void RefreshLocalMode()
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;
        string query = SearchBox.Text.Trim();

        SearchPlaceholder.Text = L.Get("Songs_SearchPlaceholder");
        SortCombo.Visibility = Visibility.Visible;
        CloudSearchButton.Visibility = Visibility.Collapsed;
        LocalActionPanel.Visibility = Visibility.Visible;
        CloudActionBarGrid.Visibility = Visibility.Collapsed;
        CloudLoadingBanner.Visibility = Visibility.Collapsed;
        CloudErrorBanner.Visibility = Visibility.Collapsed;
        LoadMoreBar.Visibility = Visibility.Collapsed;

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
            EmptyGlyph.Text = "\uE8D6";

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
                Title = t.Title,
                Subtitle = _sort == SongSort.Album
                    ? $"{L.DisplayArtist(t.Artist)} — {L.DisplayAlbum(t.Album)}"
                    : L.DisplayArtist(t.Artist),
                TrailingText = TimeFormatter.Format(t.DurationSeconds),
                ArtworkCache = lib.Artwork,
                ArtworkKey = t.ArtworkKey,
                ThumbnailCache = _ctx.Services.Thumbnails,
                RemoteArtworkUrl = t.RemoteArtworkUrl,
                PlaceholderGlyphSize = 15.0,
                IsAvailable = lib.IsAvailable(t),
                IsCurrent = isCurr,
                IsPlaying = isCurr && isPlaying,
                RowBackground = isCurr ? accentSubtle : Brushes.Transparent,
                TitleBrush = isCurr ? accentBrush : primaryBrush
            });
        }

        SongsList.ItemsSource = items;
    }

    private void RefreshCloudMode()
    {
        if (_ctx is null || _searchController is null) return;
        var ext = _searchController.ActiveExtension;
        if (ext is null)
        {
            _searchController.SelectLocal();
            RebuildSearchSourceCombo();
            RefreshLocalMode();
            return;
        }

        _displayedTracks = new();
        SearchFieldChip.Visibility = Visibility.Collapsed;
        SortCombo.Visibility = Visibility.Collapsed;
        CloudSearchButton.Visibility = Visibility.Visible;
        SearchPlaceholder.Text = L.Format("Songs_CloudSearchPlaceholder", ext.DisplayName);

        ActionBar.Visibility = Visibility.Visible;
        LocalActionPanel.Visibility = Visibility.Collapsed;
        CloudActionBarGrid.Visibility = Visibility.Visible;
        UpdateKindFilterPills(ext);

        CloudLoadingBanner.Visibility = _searchController.IsLoading ? Visibility.Visible : Visibility.Collapsed;
        UpdateCloudErrorBanner(_searchController.Error);

        var results = _searchController.Results;
        if (_searchController.IsLoading)
        {
            CloudStatusText.Text = L.Get("Songs_CloudSearching");
            CountText.Text = ext.DisplayName;
        }
        else if (_searchController.HasSearched)
        {
            string countText = _searchController.HasMore
                ? L.Format("Songs_CloudMoreResultsCount", results.Count)
                : L.Format("Songs_CloudResultsCount", results.Count);
            CloudStatusText.Text = countText;
            CountText.Text = $"{ext.DisplayName} · {countText}";
        }
        else
        {
            CloudStatusText.Text = string.Empty;
            CountText.Text = ext.DisplayName;
        }

        if (_searchController.HasMore || _searchController.IsLoadingMore)
        {
            LoadMoreBar.Visibility = Visibility.Visible;
            LoadMoreButton.IsEnabled = !_searchController.IsLoadingMore;
            LoadMoreButton.Content = _searchController.IsLoadingMore
                ? L.Get("Songs_CloudLoadingMore")
                : L.Get("Songs_LoadMore");
        }
        else
        {
            LoadMoreBar.Visibility = Visibility.Collapsed;
        }

        if (results.Count == 0)
        {
            SongsList.ItemsSource = null;
            SongsList.Visibility = Visibility.Collapsed;

            if (_searchController.IsLoading || _searchController.Error is not null)
            {
                EmptyState.Visibility = Visibility.Collapsed;
                return;
            }

            EmptyState.Visibility = Visibility.Visible;
            EmptyGlyph.Text = "\uE721";
            if (_searchController.HasSearched)
            {
                EmptyTitleText.Text = L.Format("Songs_NoSearchResultsTitle", _searchController.LastSubmittedQuery);
                EmptyDescText.Text = L.Get("Songs_NoSearchResultsDesc");
            }
            else
            {
                EmptyTitleText.Text = L.Format("Songs_CloudSearchPromptTitle", ext.DisplayName);
                EmptyDescText.Text = L.Get("Songs_CloudSearchPromptDesc");
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
        bool showKindBadge = ext.SearchKinds.Count > 1;

        var items = new List<SongRowItem>(results.Count);
        for (int i = 0; i < results.Count; i++)
        {
            var r = results[i];
            string? ephemeralId = r.Kind == ExtensionSearchKind.Track
                ? Track.MakeEphemeralId(ext.ExtensionId, r.Id)
                : null;
            bool isCurr = ephemeralId is not null && string.Equals(ephemeralId, currentId, StringComparison.Ordinal);

            string subtitle;
            string trailing;
            double glyphSize;
            string? kindBadge = null;

            if (r.Kind == ExtensionSearchKind.Track)
            {
                subtitle = !string.IsNullOrWhiteSpace(r.Album)
                    ? $"{L.DisplayArtist(r.Artist ?? string.Empty)} — {r.Album}"
                    : L.DisplayArtist(r.Artist ?? string.Empty);
                trailing = r.DurationSec > 0 ? TimeFormatter.Format(r.DurationSec) : string.Empty;
                glyphSize = 15.0;
                if (showKindBadge) kindBadge = L.Get("Songs_KindBadge_Track");
            }
            else
            {
                var subParts = new List<string>();
                if (!string.IsNullOrWhiteSpace(r.Artist)) subParts.Add(r.Artist!);
                if (r.TrackCount is int tc && tc > 0) subParts.Add(L.Format("Songs_TrackCountShort", tc));
                if (r.Year is int yr && yr > 0) subParts.Add(yr.ToString());
                subtitle = subParts.Count > 0 ? string.Join(" · ", subParts) : ext.DisplayName;
                trailing = "\uE76C";
                glyphSize = 16.0;
                kindBadge = r.Kind == ExtensionSearchKind.Album
                    ? L.Get("Songs_KindBadge_Album")
                    : L.Get("Songs_KindBadge_Playlist");
            }

            items.Add(new SongRowItem
            {
                Index = i,
                CloudResult = r,
                Title = r.Title,
                Subtitle = subtitle,
                TrailingText = trailing,
                ThumbnailCache = _ctx.Services.Thumbnails,
                RemoteArtworkUrl = r.ArtworkUrl,
                PlaceholderGlyphSize = glyphSize,
                KindBadgeText = kindBadge,
                IsAvailable = true,
                IsCurrent = isCurr,
                IsPlaying = isCurr && isPlaying,
                RowBackground = isCurr ? accentSubtle : Brushes.Transparent,
                TitleBrush = isCurr ? accentBrush : primaryBrush
            });
        }

        SongsList.ItemsSource = items;
    }

    private void UpdateKindFilterPills(CloudSearchExtensionDescriptor ext)
    {
        if (ext.SearchKinds.Count <= 1)
        {
            KindFilterPanel.Visibility = Visibility.Collapsed;
            return;
        }

        KindFilterPanel.Visibility = Visibility.Visible;
        KindAllButton.Visibility = Visibility.Visible;
        KindTracksButton.Visibility = ext.SearchKinds.Contains(ExtensionSearchKind.Track) ? Visibility.Visible : Visibility.Collapsed;
        KindAlbumsButton.Visibility = ext.SearchKinds.Contains(ExtensionSearchKind.Album) ? Visibility.Visible : Visibility.Collapsed;
        KindPlaylistsButton.Visibility = ext.SearchKinds.Contains(ExtensionSearchKind.Playlist) ? Visibility.Visible : Visibility.Collapsed;

        ExtensionSearchKind? activeKind = _searchController?.SelectedKind;
        ApplyKindPillStyle(KindAllButton, activeKind is null);
        ApplyKindPillStyle(KindTracksButton, activeKind == ExtensionSearchKind.Track);
        ApplyKindPillStyle(KindAlbumsButton, activeKind == ExtensionSearchKind.Album);
        ApplyKindPillStyle(KindPlaylistsButton, activeKind == ExtensionSearchKind.Playlist);
    }

    private void ApplyKindPillStyle(Button button, bool selected)
    {
        button.Style = (Style)FindResource(selected ? "Win10PrimaryButtonStyle" : "Win10ButtonStyle");
    }

    private void UpdateCloudErrorBanner(CloudSearchError? error)
    {
        if (error is null)
        {
            CloudErrorBanner.Visibility = Visibility.Collapsed;
            return;
        }

        CloudErrorBanner.Visibility = Visibility.Visible;
        CloudErrorMessageText.Text = string.IsNullOrWhiteSpace(error.Message)
            ? L.Get("Songs_CloudSearchFailed")
            : error.Message;

        if (error.Code == CloudErrorCode.AuthRequired)
        {
            CloudErrorHintText.Text = L.Get("Songs_CloudAuthRequiredHint");
            CloudErrorHintText.Visibility = Visibility.Visible;
            CloudOpenExtensionsButton.Content = L.Get("Songs_CloudOpenExtensions");
            CloudOpenExtensionsButton.Visibility = error.ExtensionSupportsCookie ? Visibility.Visible : Visibility.Collapsed;
        }
        else if (error.Code == CloudErrorCode.RateLimited)
        {
            CloudErrorHintText.Text = L.Get("Songs_CloudRateLimitedHint");
            CloudErrorHintText.Visibility = Visibility.Visible;
            CloudOpenExtensionsButton.Visibility = Visibility.Collapsed;
        }
        else
        {
            CloudErrorHintText.Visibility = Visibility.Collapsed;
            CloudOpenExtensionsButton.Visibility = Visibility.Collapsed;
        }
    }

    private void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_searchController is { IsCloudMode: true })
        {
            SearchPlaceholder.Visibility = string.IsNullOrEmpty(SearchBox.Text) ? Visibility.Visible : Visibility.Collapsed;
            ClearSearchButton.Visibility = string.IsNullOrEmpty(SearchBox.Text) ? Visibility.Collapsed : Visibility.Visible;
            return;
        }

        if (!_isProgrammaticSearch)
        {
            _searchField = SearchField.Keyword;
        }
        Refresh();
    }

    private async void SearchBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && _searchController is { IsCloudMode: true })
        {
            e.Handled = true;
            await _searchController.SubmitSearchAsync(SearchBox.Text);
        }
    }

    private async void CloudSearch_Click(object sender, RoutedEventArgs e)
    {
        if (_searchController is { IsCloudMode: true })
        {
            await _searchController.SubmitSearchAsync(SearchBox.Text);
        }
    }

    private async void SearchSourceCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressSourceSelection || _searchController is null) return;
        if (SearchSourceCombo.SelectedItem is not SearchSourceOption option) return;

        if (option.ExtensionId is null)
        {
            _searchController.SelectLocal();
            Refresh();
        }
        else
        {
            await _searchController.SelectExtensionAsync(option.ExtensionId, SearchBox.Text);
        }
    }

    private async void KindFilter_Click(object sender, RoutedEventArgs e)
    {
        if (_searchController is null || sender is not Button btn) return;
        ExtensionSearchKind? kind = (btn.Tag as string) switch
        {
            "track" => ExtensionSearchKind.Track,
            "album" => ExtensionSearchKind.Album,
            "playlist" => ExtensionSearchKind.Playlist,
            _ => null
        };
        await _searchController.SelectKindAsync(kind, SearchBox.Text);
    }

    private async void CloudRetry_Click(object sender, RoutedEventArgs e)
    {
        if (_searchController is null) return;
        await _searchController.RetryAsync();
    }

    private void CloudOpenExtensions_Click(object sender, RoutedEventArgs e) =>
        _ctx?.OpenExtensions?.Invoke();

    private async void LoadMore_Click(object sender, RoutedEventArgs e)
    {
        if (_searchController is null) return;
        await _searchController.LoadMoreAsync();
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
        _ctx.Services.Player.PlayInNamedQueue("Songs", ids, startAt: 0, shuffle: false);
    }

    private void ShuffleAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _displayedTracks.Count == 0) return;
        var ids = _displayedTracks.Select(t => t.Id).ToList();
        _ctx.Services.Player.PlayInNamedQueue("Songs", ids, startAt: Random.Shared.Next(ids.Count), shuffle: true);
    }

    private void SongsList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        SongsList.SelectedItem = null;
    }

    private void SongsList_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        if (e.OriginalSource is DependencyObject dep)
        {
            var container = ItemsControl.ContainerFromElement(SongsList, dep) as ListBoxItem;
            if (container?.DataContext is SongRowItem item)
            {
                e.Handled = true;
                ActivateRow(item);
            }
        }
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
        if (e.ClickCount != 1) return;
        if (sender is Border { DataContext: SongRowItem item })
        {
            ActivateRow(item);
        }
    }

    private void ActivateRow(SongRowItem item)
    {
        if (_ctx is null || !item.IsAvailable) return;

        if (item.CloudResult is { } searchItem && _searchController?.ActiveExtension is { } ext)
        {
            if (searchItem.Kind == ExtensionSearchKind.Track)
            {
                var prepared = _searchController.PrepareTrackQueue(_ctx.Services.Library, searchItem.Id);
                if (prepared is { } q)
                {
                    var ids = q.Tracks.Select(t => t.Id).ToList();
                    _ctx.Services.Player.PlayInNewQueue(ids, q.QueueName, q.StartIndex);
                }
            }
            else
            {
                _ctx.OpenCollectionPreview?.Invoke(
                    ext.ExtensionId,
                    ext.DisplayName,
                    searchItem,
                    ext.SupportsCollectionImport);
            }
            return;
        }

        if (item.Track is null) return;
        var localIds = _displayedTracks.Select(t => t.Id).ToList();
        string query = SearchBox.Text.Trim();
        if (!string.IsNullOrEmpty(query))
        {
            string queueName = QueueStore.FormatSearchQueueName(_searchField, query);
            _ctx.Services.Player.PlayInNewQueue(localIds, queueName, item.Index);
        }
        else
        {
            _ctx.Services.Player.PlayInNamedQueue("Songs", localIds, startAt: item.Index);
        }
    }

    private void SongRow_MouseRightButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (_ctx is null || sender is not Border { DataContext: SongRowItem item } border)
            return;

        ContextMenu menu;
        if (item.CloudResult is { } searchItem && _searchController?.ActiveExtension is { } ext)
        {
            if (searchItem.Kind == ExtensionSearchKind.Track)
            {
                var ephTrack = _searchController.ToEphemeralTrack(searchItem);
                menu = TrackMenuHelper.BuildTrackMenu(
                    _ctx.Services,
                    [ephTrack.Id],
                    null,
                    _ctx.OpenTextPrompt,
                    appendExtra: null,
                    openSaveCloudTrack: _ctx.OpenSaveCloudTrack,
                    openSongInfo: _ctx.OpenSongInfo,
                    explicitTrack: ephTrack);
            }
            else
            {
                menu = new ContextMenu();
                menu.Items.Add(TrackMenuHelper.CreateItem(
                    L.Get("Menu_OpenPreview"),
                    "\uE8A9",
                    () => _ctx.OpenCollectionPreview?.Invoke(
                        ext.ExtensionId,
                        ext.DisplayName,
                        searchItem,
                        ext.SupportsCollectionImport)));
                if (ext.SupportsCollectionImport)
                {
                    menu.Items.Add(TrackMenuHelper.CreateItem(
                        L.Get("Menu_AddAsCloudFolder"),
                        "\uE8F4",
                        () => _ = AddCloudFolderDirectAsync(ext, searchItem)));
                }
            }
        }
        else if (item.Track is not null)
        {
            menu = TrackMenuHelper.BuildTrackMenu(
                _ctx.Services,
                [item.Track.Id],
                item.Track.Id,
                _ctx.OpenTextPrompt,
                null,
                _ctx.OpenSaveCloudTrack,
                _ctx.OpenSongInfo);
        }
        else
        {
            return;
        }

        menu.PlacementTarget = border;
        menu.IsOpen = true;
        e.Handled = true;
    }

    private async System.Threading.Tasks.Task AddCloudFolderDirectAsync(
        CloudSearchExtensionDescriptor ext,
        ExtSearchResultItem item)
    {
        if (_ctx is null) return;
        string url = !string.IsNullOrWhiteSpace(item.SourceUrl) ? item.SourceUrl!.Trim() : item.Id;
        try
        {
            await _ctx.Services.Library.AddCloudRootAsync(
                url,
                cookie: null,
                proxyUrl: null,
                ct: CancellationToken.None,
                preferredProviderId: CloudSources.Extension(ext.ExtensionId));
            _ctx.OpenConfirm(
                item.Title,
                L.Get("Preview_AddedCloudFolder"),
                L.Get("Common_OK"),
                null,
                false);
        }
        catch (Exception ex)
        {
            _ctx.OpenConfirm(
                item.Title,
                ex.Message,
                L.Get("Common_OK"),
                null,
                false);
        }
    }
}
