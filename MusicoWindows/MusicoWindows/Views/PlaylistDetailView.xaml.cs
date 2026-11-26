using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using MusicoWindows.Controls;
using MusicoWindows.Core;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public partial class PlaylistDetailView : UserControl
{
    private ViewContext? _ctx;
    private PlaylistRef _ref = PlaylistRef.Favourites;
    private IReadOnlyList<Track> _tracks = Array.Empty<Track>();
    private bool _suppressSortSelection;

    public event Action? BackRequested;

    public PlaylistDetailView()
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
            foreach (var mode in TrackSorter.AllModes)
            {
                SortCombo.Items.Add(TrackSorter.Label(mode));
            }
            if (SortCombo.Items.Count > 0)
                SortCombo.SelectedIndex = Math.Clamp(prev, 0, SortCombo.Items.Count - 1);
        }
        finally
        {
            _suppressSortSelection = false;
        }
    }

    public void Bind(ViewContext ctx, PlaylistRef playlistRef, bool showBackButton = true)
    {
        Detach();
        _ctx = ctx;
        _ref = playlistRef;
        BackBar.Visibility = showBackButton ? Visibility.Visible : Visibility.Collapsed;
        _ctx.Services.Library.PropertyChanged += OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged += OnPlayerChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        Refresh(syncNamedQueue: true);
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
        Refresh(syncNamedQueue: false);
    }

    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e) =>
        Refresh(syncNamedQueue: e.PropertyName is nameof(LibraryStore.Playlists) or nameof(LibraryStore.Favourites));

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(PlayerController.CurrentTrack) or nameof(PlayerController.IsPlaying))
            Refresh(syncNamedQueue: false);
    }

    public void Refresh() => Refresh(syncNamedQueue: false);

    private void Refresh(bool syncNamedQueue)
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;
        var history = _ctx.Services.History;
        TrackSortMode sortMode = TrackSortMode.Custom;

        if (_ref.Kind == PlaylistKind.Favourites)
        {
            TitleText.Text = L.Get("Nav_Favourites");
            _tracks = lib.GetFavouriteTracks(history);
            sortMode = lib.GetFavouritesSortMode();
        }
        else if (_ref.Id is Guid pid)
        {
            var pl = lib.Playlists.FirstOrDefault(p => p.Id == pid);
            TitleText.Text = pl?.Name ?? L.Get("PlaylistDetail_FallbackTitle");
            _tracks = pl is null ? Array.Empty<Track>() : lib.GetPlaylistTracks(pid, history);
            sortMode = pl?.SortMode ?? TrackSortMode.Custom;
        }

        int modeIndex = 0;
        for (int i = 0; i < TrackSorter.AllModes.Count; i++)
        {
            if (TrackSorter.AllModes[i] == sortMode)
            {
                modeIndex = i;
                break;
            }
        }
        _suppressSortSelection = true;
        SortCombo.SelectedIndex = modeIndex;
        _suppressSortSelection = false;
        SortCombo.IsEnabled = _tracks.Count > 1;

        CountText.Text = L.SongsCount(_tracks.Count);
        PlayAllButton.IsEnabled = _tracks.Count > 0;
        ShuffleAllButton.IsEnabled = _tracks.Count > 0;

        TracksStack.Children.Clear();
        if (_tracks.Count == 0)
        {
            TracksScroll.Visibility = Visibility.Collapsed;
            EmptyState.Visibility = Visibility.Visible;
            return;
        }

        TracksScroll.Visibility = Visibility.Visible;
        EmptyState.Visibility = Visibility.Collapsed;

        var ids = _tracks.Select(t => t.Id).ToList();
        if (syncNamedQueue && ids.Count > 0)
        {
            _ctx.Services.Player.SyncNamedQueue(TitleText.Text, ids);
        }

        bool isUser = _ref.Kind == PlaylistKind.User && _ref.Id.HasValue;
        Guid userPlaylistId = _ref.Id ?? Guid.Empty;

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
                L.DisplayArtist(track.Artist),
                isCurrent,
                isPlaying,
                lib.IsAvailable(track),
                () => _ctx.Services.Player.PlayInNamedQueue(TitleText.Text, ids, startAt: idx),
                _ctx.OpenTextPrompt,
                isUser ? menu =>
                {
                    menu.Items.Add(new Separator());
                    menu.Items.Add(TrackMenuHelper.CreateItem(L.Get("PlaylistDetail_RemoveFromPlaylist"), "\uE74D",
                        () => RemoveFromPlaylistWithUndo(userPlaylistId, idx, track.Title, history)));
                } : null,
                _ctx.OpenSaveCloudTrack,
                _ctx.OpenSongInfo);

            if (isUser)
            {
                row.EnableDragReorder($"playlist:{userPlaylistId:D}", idx, _tracks.Count,
                    (from, to) => lib.MovePlaylistTrack(userPlaylistId, from, to, history));
                row.AddRowActionButton("\uE74A", L.Get("Common_MoveUp"), () => lib.MovePlaylistTrack(userPlaylistId, idx, idx - 1, history), idx > 0);
                row.AddRowActionButton("\uE74B", L.Get("Common_MoveDown"), () => lib.MovePlaylistTrack(userPlaylistId, idx, idx + 1, history), idx < _tracks.Count - 1);
                row.AddRowActionButton("\uE711", L.Get("PlaylistDetail_RemoveFromPlaylist"), () => RemoveFromPlaylistWithUndo(userPlaylistId, idx, track.Title, history));
            }
            else
            {
                row.EnableDragReorder("playlist:favourites", idx, _tracks.Count,
                    (from, to) => lib.MoveFavouriteTrack(from, to, history));
            }

            TracksStack.Children.Add(row);
        }
    }

    private void SortCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressSortSelection || _ctx is null) return;
        int idx = SortCombo.SelectedIndex;
        if (idx < 0 || idx >= TrackSorter.AllModes.Count) return;

        var mode = TrackSorter.AllModes[idx];
        var lib = _ctx.Services.Library;
        var history = _ctx.Services.History;
        if (_ref.Kind == PlaylistKind.Favourites)
        {
            lib.SetFavouritesSortMode(mode, history);
        }
        else if (_ref.Id is Guid pid)
        {
            lib.SetPlaylistSortMode(pid, mode, history);
        }
    }

    private void RemoveFromPlaylistWithUndo(Guid playlistId, int index, string trackTitle, PlayHistoryStore history)
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;
        var pl = lib.Playlists.FirstOrDefault(p => p.Id == playlistId);
        if (pl is null) return;

        var savedIds = pl.TrackIds.ToList();
        var savedSort = pl.SortMode;

        lib.RemoveFromPlaylist(playlistId, index, history);
        _ctx.ShowToast?.Invoke(
            L.Format("Toast_RemovedFromPlaylist", trackTitle),
            L.Get("Common_Undo"),
            () => lib.RestorePlaylistTracks(playlistId, savedIds, savedSort));
    }

    private void Back_Click(object sender, RoutedEventArgs e) => BackRequested?.Invoke();

    private void PlayAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _tracks.Count == 0) return;
        var ids = _tracks.Select(t => t.Id).ToList();
        _ctx.Services.Player.PlayInNamedQueue(TitleText.Text, ids, startAt: 0, shuffle: false);
    }

    private void ShuffleAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _tracks.Count == 0) return;
        var ids = _tracks.Select(t => t.Id).ToList();
        _ctx.Services.Player.PlayInNamedQueue(TitleText.Text, ids, startAt: Random.Shared.Next(ids.Count), shuffle: true);
    }

    private void More_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        var ids = _tracks.Select(t => t.Id).ToList();
        var menu = TrackMenuHelper.BuildTrackMenu(
            _ctx.Services,
            ids,
            null,
            _ctx.OpenTextPrompt);
        menu.PlacementTarget = MoreButton;
        menu.IsOpen = true;
    }
}
