using System;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using MusicoWindows.Controls;
using MusicoWindows.Converters;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Views;

public partial class AlbumDetailView : UserControl
{
    private ViewContext? _ctx;
    private AlbumInfo? _album;

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
        if (_ctx is null || _album is null) return;
        var lib = _ctx.Services.Library;

        // Re-fetch live album so rescan updates this page
        var live = lib.Albums.FirstOrDefault(a => a.Id == _album.Id) ?? _album;
        _album = live;

        var tracks = lib.GetTracks(live.TrackIds);
        double totalDuration = tracks.Sum(t => t.DurationSeconds);

        AlbumArt.SetSource(lib.Artwork, live.ArtworkKey, 240);
        AlbumTitleText.Text = live.Title;
        AlbumArtistText.Text = live.Artist;
        AlbumMetaText.Text = $"{tracks.Count} {(tracks.Count == 1 ? "song" : "songs")} · {TimeFormatter.Format(totalDuration)}";

        TracksStack.Children.Clear();
        var ids = live.TrackIds.ToList();
        for (int i = 0; i < tracks.Count; i++)
        {
            int idx = i;
            var track = tracks[i];
            string sub = track.TrackNumber is int num ? $"{num} · {track.Artist}" : track.Artist;
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
                () => _ctx.Services.Player.Play(ids, idx),
                _ctx.OpenTextPrompt,
                null,
                _ctx.OpenSaveCloudTrack);
            TracksStack.Children.Add(row);
        }
    }

    private void Back_Click(object sender, RoutedEventArgs e) => BackRequested?.Invoke();

    private void PlayAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _album is null || _album.TrackIds.Count == 0) return;
        if (_ctx.Services.Queues.Active.Shuffle) _ctx.Services.Player.ToggleShuffle();
        _ctx.Services.Player.Play(_album.TrackIds, 0);
    }

    private void ShuffleAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _album is null || _album.TrackIds.Count == 0) return;
        if (!_ctx.Services.Queues.Active.Shuffle) _ctx.Services.Player.ToggleShuffle();
        _ctx.Services.Player.Play(_album.TrackIds, Random.Shared.Next(_album.TrackIds.Count));
    }

    private void More_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _album is null) return;
        var menu = TrackMenuHelper.BuildTrackMenu(
            _ctx.Services,
            _album.TrackIds,
            null,
            _ctx.OpenTextPrompt);
        menu.PlacementTarget = MoreButton;
        menu.IsOpen = true;
    }
}
