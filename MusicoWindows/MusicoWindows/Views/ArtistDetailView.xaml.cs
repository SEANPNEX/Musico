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

public partial class ArtistDetailView : UserControl
{
    private ViewContext? _ctx;
    private string _artist = string.Empty;
    private IReadOnlyList<Track> _tracks = Array.Empty<Track>();

    public event Action? BackRequested;

    public ArtistDetailView()
    {
        InitializeComponent();
        Unloaded += OnUnloaded;
    }

    public void Bind(ViewContext ctx, string artist)
    {
        Detach();
        _ctx = ctx;
        _artist = artist;
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

        _tracks = lib.GetTracksByArtist(_artist);
        var albums = lib.GetAlbumsByArtist(_artist);

        ArtistNameText.Text = _artist;
        ArtistMetaText.Text = $"{albums.Count} {(albums.Count == 1 ? "album" : "albums")} · {_tracks.Count} {(_tracks.Count == 1 ? "song" : "songs")}";

        AlbumsWrap.Children.Clear();
        if (albums.Count > 0)
        {
            AlbumsSection.Visibility = Visibility.Visible;
            foreach (var album in albums)
            {
                AlbumsWrap.Children.Add(AlbumsView.CreateAlbumCard(_ctx, album));
            }
        }
        else
        {
            AlbumsSection.Visibility = Visibility.Collapsed;
        }

        TracksStack.Children.Clear();
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
                track.Album,
                isCurrent,
                isPlaying,
                lib.IsAvailable(track),
                () => _ctx.Services.Player.Play(ids, idx),
                _ctx.OpenTextPrompt,
                null,
                _ctx.OpenSaveCloudTrack,
                _ctx.OpenSongInfo);
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
