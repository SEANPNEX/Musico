using System;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MusicoWindows.Converters;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Views;

namespace MusicoWindows.Controls;

public enum SidePanelKind
{
    Lyrics,
    Queue
}

public partial class PlayerBar : UserControl
{
    private AppServices? _services;
    private ViewContext? _ctx;
    private bool _isScrubbing;
    private bool _updatingVolume;
    private SidePanelKind? _activeSidePanel;

    public event Action? OpenFullPlayerRequested;
    public event Action? OpenEqualizerRequested;
    public event Action? OpenSleepTimerRequested;
    public event Action<SidePanelKind>? ToggleSidePanelRequested;

    public PlayerBar()
    {
        InitializeComponent();
        LocalizationManager.LanguageChanged += _ => RefreshAll();
    }

    public void Initialize(ViewContext ctx)
    {
        _ctx = ctx;
        Initialize(ctx.Services);
    }

    public void Initialize(AppServices services)
    {
        if (_services != null)
        {
            _services.Player.PropertyChanged -= OnPlayerChanged;
            _services.Queues.PropertyChanged -= OnQueueChanged;
        }

        _services = services;
        _services.Player.PropertyChanged += OnPlayerChanged;
        _services.Queues.PropertyChanged += OnQueueChanged;

        RefreshAll();
    }

    public void SetActiveSidePanel(SidePanelKind? panel)
    {
        _activeSidePanel = panel;
        UpdateSidePanelButtons();
    }

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(PlayerController.CurrentTrack)
            or nameof(PlayerController.IsPlaying)
            or nameof(PlayerController.DurationSeconds)
            or nameof(PlayerController.CurrentTimeSeconds)
            or nameof(PlayerController.Volume)
            or nameof(PlayerController.IsMuted)
            or nameof(PlayerController.Eq)
            or nameof(PlayerController.SleepState)
            or null)
        {
            RefreshAll();
        }
    }

    private void OnQueueChanged(object? sender, PropertyChangedEventArgs e)
    {
        RefreshTransportState();
    }

    public void RefreshAll()
    {
        if (_services is null) return;
        var player = _services.Player;
        var track = player.CurrentTrack;

        CoverArt.SetSource(_services.Library.Artwork, track?.ArtworkKey, 96, track?.RemoteArtworkUrl, _services.Thumbnails);
        TrackTitleText.Text = track?.Title ?? L.Get("PlayerBar_NotPlaying");
        TrackArtistText.Text = track != null ? L.DisplayArtist(track.Artist) : L.Get("PlayerBar_PickSong");

        bool hasTrack = track != null;
        ShuffleButton.IsEnabled = hasTrack;
        PrevButton.IsEnabled = hasTrack;
        PlayPauseButton.IsEnabled = hasTrack;
        NextButton.IsEnabled = hasTrack;
        RepeatButton.IsEnabled = hasTrack;
        ScrubberSlider.IsEnabled = hasTrack;

        PlayPauseButton.Content = player.IsPlaying ? "\uE769" : "\uE768";

        if (!_isScrubbing)
        {
            double duration = Math.Max(player.DurationSeconds, 1);
            double current = Math.Clamp(player.CurrentTimeSeconds, 0, duration);
            ScrubberSlider.Maximum = duration;
            ScrubberSlider.Value = hasTrack ? current : 0;
            UpdateTimeLabels(hasTrack ? current : 0, hasTrack ? player.DurationSeconds : 0);
        }

        _updatingVolume = true;
        VolumeSlider.Value = player.Volume;
        _updatingVolume = false;
        MuteButton.Content = player.IsMuted || player.Volume <= 0.001f ? "\uE74F" : "\uE767";

        EqButton.SetResourceReference(ForegroundProperty, player.Eq.Enabled ? "AccentBrush" : "TextPrimaryBrush");
        SleepButton.SetResourceReference(ForegroundProperty, player.SleepState.Kind != SleepModeKind.Off ? "AccentBrush" : "TextPrimaryBrush");

        RefreshTransportState();
        UpdateSidePanelButtons();
    }

    private void RefreshTransportState()
    {
        if (_services is null) return;
        var q = _services.Queues.Active;

        ShuffleButton.SetResourceReference(ForegroundProperty, q.Shuffle ? "AccentBrush" : "TextSecondaryBrush");
        RepeatButton.Content = q.RepeatMode == RepeatMode.One ? "\uE8ED" : "\uE8EE";
        RepeatButton.SetResourceReference(ForegroundProperty, q.RepeatMode == RepeatMode.Off ? "TextSecondaryBrush" : "AccentBrush");
    }

    private void UpdateSidePanelButtons()
    {
        bool lyricsOn = _activeSidePanel == SidePanelKind.Lyrics;
        bool queueOn = _activeSidePanel == SidePanelKind.Queue;

        LyricsButton.SetResourceReference(ForegroundProperty, lyricsOn ? "AccentBrush" : "TextPrimaryBrush");
        LyricsButton.SetResourceReference(BackgroundProperty, lyricsOn ? "AccentSubtleBrush" : "SubtleFillBrush");

        QueueButton.SetResourceReference(ForegroundProperty, queueOn ? "AccentBrush" : "TextPrimaryBrush");
        QueueButton.SetResourceReference(BackgroundProperty, queueOn ? "AccentSubtleBrush" : "SubtleFillBrush");
    }

    private void UpdateTimeLabels(double current, double total)
    {
        ElapsedText.Text = TimeFormatter.Format(current);
        RemainingText.Text = "-" + TimeFormatter.Format(Math.Max(total - current, 0));
    }

    private void NowPlaying_Click(object sender, RoutedEventArgs e) => OpenFullPlayerRequested?.Invoke();

    private void NowPlaying_MouseRightButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (_services?.Player.CurrentTrack is not { } track) return;

        var menu = TrackMenuHelper.BuildTrackMenu(
            _services,
            [track.Id],
            track.IsEphemeral ? null : track.Id,
            _ctx?.OpenTextPrompt ?? ((_, _, _, _, _) => { }),
            null,
            _ctx?.OpenSaveCloudTrack,
            _ctx?.OpenSongInfo,
            explicitTrack: track);
        menu.PlacementTarget = NowPlayingButton;
        menu.IsOpen = true;
        e.Handled = true;
    }
    private void Shuffle_Click(object sender, RoutedEventArgs e) => _services?.Player.ToggleShuffle();
    private void Prev_Click(object sender, RoutedEventArgs e) => _services?.Player.Previous();
    private void PlayPause_Click(object sender, RoutedEventArgs e) => _services?.Player.TogglePlayPause();
    private void Next_Click(object sender, RoutedEventArgs e) => _services?.Player.Next();
    private void Repeat_Click(object sender, RoutedEventArgs e) => _services?.Player.CycleRepeat();
    private void Mute_Click(object sender, RoutedEventArgs e)
    {
        if (_services is not null) _services.Player.IsMuted = !_services.Player.IsMuted;
    }

    private void VolumeSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_updatingVolume || _services is null) return;
        _services.Player.Volume = (float)e.NewValue;
        if (_services.Player.IsMuted && e.NewValue > 0)
        {
            _services.Player.IsMuted = false;
        }
    }

    private void Eq_Click(object sender, RoutedEventArgs e) => OpenEqualizerRequested?.Invoke();
    private void Sleep_Click(object sender, RoutedEventArgs e) => OpenSleepTimerRequested?.Invoke();
    private void Lyrics_Click(object sender, RoutedEventArgs e) => ToggleSidePanelRequested?.Invoke(SidePanelKind.Lyrics);
    private void Queue_Click(object sender, RoutedEventArgs e) => ToggleSidePanelRequested?.Invoke(SidePanelKind.Queue);

    private void Scrubber_PreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        _isScrubbing = true;
        ScrubberSlider.Tag = "Scrubbing";
        ElapsedText.SetResourceReference(TextBlock.ForegroundProperty, "AccentBrush");
        ElapsedText.FontWeight = FontWeights.SemiBold;
    }

    private void Scrubber_PreviewMouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        FinishScrub();
    }

    private void Scrubber_LostMouseCapture(object sender, MouseEventArgs e)
    {
        FinishScrub();
    }

    private void FinishScrub()
    {
        if (!_isScrubbing) return;
        _isScrubbing = false;
        ScrubberSlider.Tag = null;
        ElapsedText.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondaryBrush");
        ElapsedText.FontWeight = FontWeights.Normal;
        _services?.Player.Seek(ScrubberSlider.Value);
    }

    private void Scrubber_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isScrubbing && _services is not null)
        {
            UpdateTimeLabels(e.NewValue, _services.Player.DurationSeconds);
        }
    }
}
