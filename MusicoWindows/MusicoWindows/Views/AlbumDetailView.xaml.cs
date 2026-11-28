using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MusicoWindows.Controls;
using MusicoWindows.Converters;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public partial class AlbumDetailView : UserControl
{
    private ViewContext? _ctx;
    private AlbumInfo? _album;
    private bool _isSelectionMode;
    private bool _suppressSelectionChanged;

    public event Action? BackRequested;

    public AlbumDetailView()
    {
        InitializeComponent();
        Unloaded += OnUnloaded;
    }

    public void Bind(ViewContext ctx, AlbumInfo album)
    {
        Detach();
        _ctx = ctx;
        _album = album;
        _isSelectionMode = false;
        ClearTracksList();
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
        _ctx.Services.Library.PropertyChanged -= OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged -= OnPlayerChanged;
    }

    private void OnLanguageChanged(ResolvedLanguage _) => Refresh();

    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e) => Refresh();

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(PlayerController.CurrentTrack) or nameof(PlayerController.IsPlaying))
            Refresh();
    }

    public void Refresh()
    {
        if (_ctx is null || _album is null) return;
        var lib = _ctx.Services.Library;

        // Re-fetch live album so rescan updates this page
        var live = lib.Albums.FirstOrDefault(a => a.Id == _album.Id) ?? _album;
        _album = live;

        var tracks = lib.GetTracks(live.TrackIds);
        double totalDuration = tracks.Sum(t => t.DurationSeconds);

        AlbumArt.SetSource(lib.Artwork, live.ArtworkKey, 240);
        HeroBackdropHelper.ApplyArtworkWash(HeroBackdropImage, lib.Artwork, live.ArtworkKey);
        AlbumTitleText.Text = L.DisplayAlbum(live.Title);
        AlbumArtistText.Text = L.DisplayArtist(live.Artist);
        AlbumMetaText.Text = $"{L.SongsCount(tracks.Count)} · {TimeFormatter.Format(totalDuration)}";
        SelectTracksButton.Visibility = tracks.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

        var prevSelectedSet = new HashSet<string>(GetSelectedTrackIds(), StringComparer.Ordinal);
        _suppressSelectionChanged = true;
        try
        {
            TracksList.UnselectAll();
            TracksList.Items.Clear();
            var ids = live.TrackIds.ToList();
            string queueName = FormatAlbumQueueName(live);
            for (int i = 0; i < tracks.Count; i++)
            {
                int idx = i;
                var track = tracks[i];
                string displayArtist = L.DisplayArtist(track.Artist);
                string sub = track.TrackNumber is int num ? $"{num} · {displayArtist}" : displayArtist;
                bool isCurrent = _ctx.Services.Player.CurrentTrack?.Id == track.Id;
                bool isPlaying = isCurrent && _ctx.Services.Player.IsPlaying;

                var row = new TrackRowControl();
                row.Bind(
                    _ctx.Services,
                    track,
                    sub,
                    isCurrent,
                    isPlaying,
                    lib.IsAvailable(track),
                    () => _ctx.Services.Player.PlayInNamedQueue(queueName, ids, startAt: idx),
                    _ctx.OpenTextPrompt,
                    null,
                    _ctx.OpenSaveCloudTrack,
                    _ctx.OpenSongInfo,
                    showToast: _ctx.ShowToast,
                    openBatchTagEdit: _ctx.OpenBatchTagEdit);

                row.MultiSelectContextMenuFactory = () =>
                {
                    var selectedTracks = GetSelectedTracks();
                    if (selectedTracks.Count > 1 && selectedTracks.Any(t => t.Id == track.Id))
                    {
                        return TrackMenuHelper.BuildTrackMenu(
                            _ctx.Services,
                            selectedTracks.Select(t => t.Id).ToList(),
                            null,
                            _ctx.OpenTextPrompt,
                            null,
                            _ctx.OpenSaveCloudTrack,
                            _ctx.OpenSongInfo,
                            showToast: _ctx.ShowToast,
                            openBatchTagEdit: _ctx.OpenBatchTagEdit);
                    }
                    return null;
                };

                TracksList.Items.Add(row);
                if (prevSelectedSet.Contains(track.Id))
                {
                    TracksList.SelectedItems.Add(row);
                }
            }
        }
        finally
        {
            _suppressSelectionChanged = false;
        }

        if (tracks.Count == 0)
        {
            _isSelectionMode = false;
        }
        MultiSelectUiHelper.SyncTrackRowControls(TracksList, _isSelectionMode);
        UpdateSelectionBar();
    }

    private void ClearTracksList()
    {
        _suppressSelectionChanged = true;
        try
        {
            TracksList.UnselectAll();
            TracksList.Items.Clear();
        }
        finally
        {
            _suppressSelectionChanged = false;
        }
    }

    private List<Track> GetSelectedTracks() =>
        TracksList.SelectedItems
            .OfType<TrackRowControl>()
            .Where(r => r.BoundTrack is not null)
            .Select(r => r.BoundTrack!)
            .ToList();

    private List<string> GetSelectedTrackIds() =>
        GetSelectedTracks().Select(t => t.Id).ToList();

    private void UpdateSelectionBar()
    {
        int count = GetSelectedTracks().Count;
        bool showBar = TracksList.Items.Count > 0 && (_isSelectionMode || count > 0);
        SelectionBar.Visibility = showBar ? Visibility.Visible : Visibility.Collapsed;
        SelectionCountText.Text = L.Format("Select_SelectedCount", count);
        BatchEditTagsButton.IsEnabled = count > 0;
        SelectAllButton.IsEnabled = TracksList.Items.Count > 0;
        SelectTracksButton.Style = (Style)FindResource(_isSelectionMode ? "Win10PrimaryButtonStyle" : "Win10ButtonStyle");
    }

    private void TracksList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressSelectionChanged) return;
        MultiSelectUiHelper.SyncTrackRowControls(TracksList, _isSelectionMode);
        UpdateSelectionBar();
    }

    private void TracksList_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        MultiSelectUiHelper.HandleListKeyboardShortcuts(TracksList, e, () => CancelSelection_Click(sender, e));
    }

    private void SelectTracks_Click(object sender, RoutedEventArgs e)
    {
        _isSelectionMode = !_isSelectionMode;
        if (!_isSelectionMode)
        {
            CancelSelection_Click(sender, e);
        }
        else
        {
            MultiSelectUiHelper.SyncTrackRowControls(TracksList, true);
            UpdateSelectionBar();
        }
    }

    private void SelectAll_Click(object sender, RoutedEventArgs e)
    {
        if (TracksList.Items.Count == 0) return;
        _isSelectionMode = true;
        TracksList.SelectAll();
        MultiSelectUiHelper.SyncTrackRowControls(TracksList, _isSelectionMode);
        UpdateSelectionBar();
    }

    private void BatchEditTags_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        var selected = GetSelectedTracks();
        if (selected.Count == 0) return;
        MultiSelectUiHelper.OpenBatchTagEdit(_ctx, selected);
    }

    private void CancelSelection_Click(object sender, RoutedEventArgs e)
    {
        _isSelectionMode = false;
        _suppressSelectionChanged = true;
        try
        {
            TracksList.UnselectAll();
        }
        finally
        {
            _suppressSelectionChanged = false;
        }
        MultiSelectUiHelper.SyncTrackRowControls(TracksList, false);
        UpdateSelectionBar();
    }

    private static string FormatAlbumQueueName(AlbumInfo album) =>
        $"Album: {L.DisplayAlbum(album.Title)}";

    private void Back_Click(object sender, RoutedEventArgs e) => BackRequested?.Invoke();

    private void PlayAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _album is null || _album.TrackIds.Count == 0) return;
        _ctx.Services.Player.PlayInNamedQueue(FormatAlbumQueueName(_album), _album.TrackIds, startAt: 0, shuffle: false);
    }

    private void ShuffleAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _album is null || _album.TrackIds.Count == 0) return;
        _ctx.Services.Player.PlayInNamedQueue(
            FormatAlbumQueueName(_album),
            _album.TrackIds,
            startAt: Random.Shared.Next(_album.TrackIds.Count),
            shuffle: true);
    }

    private void More_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _album is null) return;
        var menu = TrackMenuHelper.BuildTrackMenu(
            _ctx.Services,
            _album.TrackIds,
            null,
            _ctx.OpenTextPrompt,
            showToast: _ctx.ShowToast,
            openBatchTagEdit: _ctx.OpenBatchTagEdit);
        menu.PlacementTarget = MoreButton;
        menu.IsOpen = true;
    }
}
