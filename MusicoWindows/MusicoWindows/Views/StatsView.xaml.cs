using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using MusicoWindows.Controls;
using MusicoWindows.Converters;
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

    public StatsView()
    {
        InitializeComponent();
        PlaysOrderCombo.Items.Add("Most Played First");
        PlaysOrderCombo.Items.Add("Least Played First");
        PlaysOrderCombo.SelectedIndex = 0;
        Unloaded += OnUnloaded;
    }

    public void Bind(ViewContext ctx)
    {
        Detach();
        _ctx = ctx;
        _ctx.Services.History.PropertyChanged += OnHistoryChanged;
        _ctx.Services.Library.PropertyChanged += OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged += OnPlayerChanged;
        Refresh();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e) => Detach();

    private void Detach()
    {
        if (_ctx is null) return;
        _ctx.Services.History.PropertyChanged -= OnHistoryChanged;
        _ctx.Services.Library.PropertyChanged -= OnLibraryChanged;
        _ctx.Services.Player.PropertyChanged -= OnPlayerChanged;
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
        TotalPlaysLabel.Text = history.TotalPlays == 1 ? "play" : "plays";
        TotalTimeText.Text = TimeFormatter.FormatListeningDuration(history.TotalSeconds);
        NeverPlayedCountText.Text = history.NeverPlayed(lib.Tracks).Count.ToString();

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
            TracksScroll.Visibility = Visibility.Collapsed;
            EmptyState.Visibility = Visibility.Visible;
            (EmptyTitleText.Text, EmptyDescText.Text) = _section switch
            {
                StatsSection.Recent => ("Nothing Played Yet", "Songs appear here a few seconds after they start playing."),
                StatsSection.Plays => ("No Plays Yet", "A song counts as played once you have listened to half of it."),
                StatsSection.Stale => ("Nothing Neglected", $"Songs you have not listened to for {PlayHistoryStore.StaleAfterDays} days appear here."),
                StatsSection.Never => ("Everything Played", "Every song in your library has been played at least once."),
                _ => (string.Empty, string.Empty)
            };
            return;
        }

        TracksScroll.Visibility = Visibility.Visible;
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
                () => _ctx.Services.Player.Play(ids, idx),
                _ctx.OpenTextPrompt);
            TracksStack.Children.Add(row);
        }
    }

    private string BuildCaption(PlayHistoryStore history, Track track)
    {
        int count = history.GetCount(track.Id);
        string plays = count == 1 ? "1 play" : $"{count} plays";
        DateTimeOffset? last = history.GetLastPlayed(track.Id);
        string? when = last is DateTimeOffset dt ? FormatRelative(dt) : null;

        return _section switch
        {
            StatsSection.Recent or StatsSection.Stale => when is not null ? $"{when} · {track.Artist}" : track.Artist,
            StatsSection.Plays => $"{plays} · {track.Artist}",
            _ => track.Artist
        };
    }

    private static string FormatRelative(DateTimeOffset utcTime)
    {
        var elapsed = DateTimeOffset.UtcNow - utcTime;
        if (elapsed.TotalSeconds < 60) return "just now";
        if (elapsed.TotalMinutes < 60)
        {
            int m = (int)elapsed.TotalMinutes;
            return m == 1 ? "1 min ago" : $"{m} min ago";
        }
        if (elapsed.TotalHours < 24)
        {
            int h = (int)elapsed.TotalHours;
            return h == 1 ? "1 hr ago" : $"{h} hr ago";
        }
        if (elapsed.TotalDays < 30)
        {
            int d = (int)elapsed.TotalDays;
            return d == 1 ? "1 day ago" : $"{d} days ago";
        }
        return utcTime.ToLocalTime().ToString("MMM d, yyyy");
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

    private void PlaysOrderCombo_SelectionChanged(object sender, SelectionChangedEventArgs e) => Refresh();

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
}
