using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using MusicoWindows.Controls;
using MusicoWindows.Converters;
using MusicoWindows.Localization;
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
        if (_ctx is null) return;
        var lib = _ctx.Services.Library;

        _tracks = lib.GetTracksByArtist(_artist);
        var albums = lib.GetAlbumsByArtist(_artist);

        string displayArtist = L.DisplayArtist(_artist);
        string? heroArtKey = albums.Select(a => a.ArtworkKey).FirstOrDefault(k => !string.IsNullOrEmpty(k))
            ?? _tracks.Select(t => t.ArtworkKey).FirstOrDefault(k => !string.IsNullOrEmpty(k));
        double totalDuration = _tracks.Sum(t => t.DurationSeconds);

        ArtistHeroAvatar.SetSource(lib.Artwork, heroArtKey, displayArtist, 240);
        HeroBackdropHelper.ApplyArtworkWash(HeroBackdropImage, lib.Artwork, heroArtKey);
        ArtistNameText.Text = displayArtist;
        ArtistMetaText.Text = $"{L.AlbumsCount(albums.Count)} · {L.SongsCount(_tracks.Count)} · {TimeFormatter.Format(totalDuration)}";

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
        string queueName = FormatArtistQueueName(_artist);
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
                L.DisplayAlbum(track.Album),
                isCurrent,
                isPlaying,
                lib.IsAvailable(track),
                () => _ctx.Services.Player.PlayInNamedQueue(queueName, ids, startAt: idx),
                _ctx.OpenTextPrompt,
                null,
                _ctx.OpenSaveCloudTrack,
                _ctx.OpenSongInfo);
            TracksStack.Children.Add(row);
        }
    }

    private static string FormatArtistQueueName(string artist) =>
        $"Artist: {L.DisplayArtist(artist)}";

    private void Back_Click(object sender, RoutedEventArgs e) => BackRequested?.Invoke();

    private void PlayAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _tracks.Count == 0) return;
        var ids = _tracks.Select(t => t.Id).ToList();
        _ctx.Services.Player.PlayInNamedQueue(FormatArtistQueueName(_artist), ids, startAt: 0, shuffle: false);
    }

    private void ShuffleAll_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null || _tracks.Count == 0) return;
        var ids = _tracks.Select(t => t.Id).ToList();
        _ctx.Services.Player.PlayInNamedQueue(
            FormatArtistQueueName(_artist),
            ids,
            startAt: Random.Shared.Next(ids.Count),
            shuffle: true);
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
