using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using MusicoWindows.Controls;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public partial class PlaylistDetailView : UserControl
{
    private ViewContext? _ctx;
    private PlaylistRef _ref = PlaylistRef.Favourites;
    private IReadOnlyList<Track> _tracks = Array.Empty<Track>();

    public event Action? BackRequested;

    public PlaylistDetailView()
    {
        InitializeComponent();
        Unloaded += OnUnloaded;
    }

    public void Bind(ViewContext ctx, PlaylistRef playlistRef, bool showBackButton = true)
    {
        Detach();
        _ctx = ctx;
        _ref = playlistRef;
        BackBar.Visibility = showBackButton ? Visibility.Visible : Visibility.Collapsed;
        _ctx.Services.Library.PropertyChanged += OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged += OnPlayerChanged;
        Refresh();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => Detach();

    private void Detach()
    {
        if (_ctx is null) return;
        _ctx.Services.Library.PropertyChanged -= OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged -= OnPlayerChanged;
    }

    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e) => Refresh();

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(PlayerController.CurrentTrack) or nameof(PlayerController.IsPlaying))
            Refresh();
    }

    public void Refresh()
    {
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;

        if (_ref.Kind == PlaylistKind.Favourites)
        {
            TitleText.Text = "Favourites";
            _tracks = lib.Tracks.Where(t => lib.IsFavourite(t.Id)).ToList();
        }
        else if (_ref.Id is Guid pid)
        {
            var pl = lib.Playlists.FirstOrDefault(p => p.Id == pid);
            TitleText.Text = pl?.Name ?? "Playlist";
            _tracks = pl is null ? Array.Empty<Track>() : lib.GetTracks(pl.TrackIds);
        }

        CountText.Text = $"{_tracks.Count} {(_tracks.Count == 1 ? "song" : "songs")}";
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
                track.Artist,
                isCurrent,
                isPlaying,
                lib.IsAvailable(track),
                () => _ctx.Services.Player.Play(ids, idx),
                _ctx.OpenTextPrompt,
                isUser ? menu =>
                {
                    menu.Items.Add(new Separator());
                    menu.Items.Add(TrackMenuHelper.CreateItem("Remove from Playlist", "\uE74D",
                        () => lib.RemoveFromPlaylist(userPlaylistId, idx)));
                } : null);

            if (isUser)
            {
                row.AddRowActionButton("\uE74A", "Move Up", () => lib.MovePlaylistTrack(userPlaylistId, idx, idx - 1), idx > 0);
                row.AddRowActionButton("\uE74B", "Move Down", () => lib.MovePlaylistTrack(userPlaylistId, idx, idx + 1), idx < _tracks.Count - 1);
                row.AddRowActionButton("\uE711", "Remove from Playlist", () => lib.RemoveFromPlaylist(userPlaylistId, idx));
            }

            TracksStack.Children.Add(row);
        }
    }

    private void Back_Click(object sender, RoutedEventArgs e) => BackRequested?.Invoke();

    private void PlayAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _tracks.Count == 0) return;
        var ids = _tracks.Select(t => t.Id).ToList();
        if (_ctx.Services.Queues.Active.Shuffle) _ctx.Services.Player.ToggleShuffle();
        _ctx.Services.Player.Play(ids, 0);
    }

    private void ShuffleAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _tracks.Count == 0) return;
        var ids = _tracks.Select(t => t.Id).ToList();
        if (!_ctx.Services.Queues.Active.Shuffle) _ctx.Services.Player.ToggleShuffle();
        _ctx.Services.Player.Play(ids, Random.Shared.Next(ids.Count));
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
