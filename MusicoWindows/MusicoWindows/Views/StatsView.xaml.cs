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
                StatsSection.Recent => (L.Get("Stats_EmptyRecentTitle"), L.Get("Stats_EmptyRecentDesc")),
                StatsSection.Plays => (L.Get("Stats_EmptyPlaysTitle"), L.Get("Stats_EmptyPlaysDesc")),
                StatsSection.Stale => (L.Get("Stats_EmptyStaleTitle"), L.Format("Stats_EmptyStaleDesc", PlayHistoryStore.StaleAfterDays)),
                StatsSection.Never => (L.Get("Stats_EmptyNeverTitle"), L.Get("Stats_EmptyNeverDesc")),
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
