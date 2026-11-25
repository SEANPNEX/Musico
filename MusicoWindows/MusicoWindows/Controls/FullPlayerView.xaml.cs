using System;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using MusicoWindows.Converters;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;
using MusicoWindows.Views;

namespace MusicoWindows.Controls;

public partial class FullPlayerView : UserControl
{
    private ViewContext? _ctx;
    private bool _isScrubbing;
    private bool? _lastIsPlaying;
    private string? _lastTrackId;

    public event Action? CloseRequested;
    public event Action? OpenEqualizerRequested;
    public event Action? OpenSleepTimerRequested;
    public event Action<Track>? SearchOnlineLyricsRequested;
    public event Action<string, string>? ErrorOccurred;
    public event Action<string, string>? InfoOccurred;

    public FullPlayerView()
    {
        InitializeComponent();
        EmbeddedLyrics.SearchOnlineRequested += track => SearchOnlineLyricsRequested?.Invoke(track);
        EmbeddedLyrics.ErrorOccurred += (title, msg) => ErrorOccurred?.Invoke(title, msg);
        EmbeddedLyrics.InfoOccurred += (title, msg) => InfoOccurred?.Invoke(title, msg);
        LocalizationManager.LanguageChanged += _ => RefreshAll();
    }

    public bool IsOpen => Visibility == Visibility.Visible;

    public void Initialize(ViewContext ctx)
    {
        if (_ctx != null)
        {
            _ctx.Services.Player.PropertyChanged -= OnPlayerChanged;
            _ctx.Services.Queues.PropertyChanged -= OnQueueChanged;
            _ctx.Services.Library.PropertyChanged -= OnLibraryChanged;
        }

        _ctx = ctx;
        _ctx.Services.Player.PropertyChanged += OnPlayerChanged;
        _ctx.Services.Queues.PropertyChanged += OnQueueChanged;
        _ctx.Services.Library.PropertyChanged += OnLibraryChanged;

        EmbeddedLyrics.Initialize(ctx.Services);
        EmbeddedQueues.Bind(ctx);
        RefreshAll();
    }

    public void Show()
    {
        Visibility = Visibility.Visible;
        RefreshAll();
        Win10Animations.AnimateModalOpen(BackdropGrid, PlayerCard);
    }

    public void Close()
    {
        Visibility = Visibility.Collapsed;
        CloseRequested?.Invoke();
    }

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e) => RefreshAll();
    private void OnQueueChanged(object? sender, PropertyChangedEventArgs e) => RefreshAll();
    private void OnLibraryChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(LibraryStore.Favourites)) RefreshAll();
    }

    private void RefreshAll()
    {
        if (_ctx is null) return;
        var services = _ctx.Services;
        var player = services.Player;
        var track = player.CurrentTrack;
        var queue = services.Queues.Active;

        CoverArt.SetSource(services.Library.Artwork, track?.ArtworkKey, 600, track?.RemoteArtworkUrl, services.Thumbnails);
        if (!string.Equals(_lastTrackId, track?.Id, StringComparison.Ordinal))
        {
            _lastTrackId = track?.Id;
            if (IsOpen)
            {
                Win10Animations.AnimateFadeIn(CoverArt, 0.35, 220);
            }
        }
        if (_lastIsPlaying != player.IsPlaying)
        {
            _lastIsPlaying = player.IsPlaying;
            Win10Animations.AnimateScale(CoverArt, player.IsPlaying ? 1.0 : 0.94, 260);
        }

        TitleText.Text = track?.Title ?? L.Get("FullPlayer_NothingPlaying");
        MetaText.Text = track != null
            ? $"{L.DisplayArtist(track.Artist)} — {L.DisplayAlbum(track.Album)}"
            : L.Get("PlayerBar_PickSong");

        bool hasTrack = track != null;
        SongInfoButton.IsEnabled = hasTrack;
        FavouriteButton.IsEnabled = hasTrack && !track!.IsEphemeral;
        ShuffleButton.IsEnabled = hasTrack;
        PrevButton.IsEnabled = hasTrack;
        PlayPauseButton.IsEnabled = hasTrack;
        NextButton.IsEnabled = hasTrack;
        RepeatButton.IsEnabled = hasTrack;
        ScrubberSlider.IsEnabled = hasTrack;

        var qualities = player.CurrentTrackAvailableQualities;
        bool showQuality = hasTrack && qualities.Count > 0;
        QualityButton.Visibility = showQuality ? Visibility.Visible : Visibility.Collapsed;
        if (showQuality)
        {
            QualityButton.Content = !string.IsNullOrWhiteSpace(player.CurrentQualityLabel)
                ? player.CurrentQualityLabel
                : L.Get("Quality_Select");
        }

        bool isFav = track != null && services.Library.IsFavourite(track.Id);
        FavouriteButton.Content = isFav ? "\uEB52" : "\uEB51";
        FavouriteButton.SetResourceReference(ForegroundProperty, isFav ? "AccentBrush" : "TextSecondaryBrush");

        PlayPauseButton.Content = player.IsPlaying ? "\uE769" : "\uE768";

        if (!_isScrubbing)
        {
            double duration = Math.Max(player.DurationSeconds, 1);
            double current = Math.Clamp(player.CurrentTimeSeconds, 0, duration);
            ScrubberSlider.Maximum = duration;
            ScrubberSlider.Value = hasTrack ? current : 0;
            UpdateTimeLabels(hasTrack ? current : 0, hasTrack ? player.DurationSeconds : 0);
        }

        ShuffleButton.SetResourceReference(ForegroundProperty, queue.Shuffle ? "AccentBrush" : "TextSecondaryBrush");
        RepeatButton.Content = queue.RepeatMode == RepeatMode.One ? "\uE8ED" : "\uE8EE";
        RepeatButton.SetResourceReference(ForegroundProperty, queue.RepeatMode == RepeatMode.Off ? "TextSecondaryBrush" : "AccentBrush");

        if (queue.CurrentIndex is int idx && queue.TrackIds.Count > 0)
        {
            QueuePositionText.Text = L.Format("FullPlayer_QueuePosition", queue.Name, idx + 1, queue.TrackIds.Count);
        }
        else
        {
            QueuePositionText.Text = queue.Name;
        }

        EqButton.SetResourceReference(ForegroundProperty, player.Eq.Enabled ? "AccentBrush" : "TextPrimaryBrush");
        SleepButton.SetResourceReference(ForegroundProperty, player.SleepState.Kind != SleepModeKind.Off ? "AccentBrush" : "TextPrimaryBrush");
    }

    private void UpdateTimeLabels(double current, double total)
    {
        ElapsedText.Text = TimeFormatter.Format(current);
        RemainingText.Text = "-" + TimeFormatter.Format(Math.Max(total - current, 0));
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
    private void Artwork_MouseLeftButtonUp(object sender, MouseButtonEventArgs e) => LyricsPivotTab.IsChecked = true;
    private void SongInfo_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx?.Services.Player.CurrentTrack is { } track)
            _ctx.OpenSongInfo?.Invoke(track);
    }

    private void Quality_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx is null) return;
        var player = _ctx.Services.Player;
        var qualities = player.CurrentTrackAvailableQualities;
        if (qualities.Count == 0) return;

        string? currentId = player.CurrentQualityId;
        var menu = new ContextMenu();
        foreach (var opt in qualities)
        {
            string label = !string.IsNullOrWhiteSpace(opt.Badge)
                ? $"{opt.Label} ({opt.Badge})"
                : opt.Label;
            bool isCurrent = string.Equals(opt.Id, currentId, StringComparison.OrdinalIgnoreCase);
            string qualityId = opt.Id;
            menu.Items.Add(TrackMenuHelper.CreateItem(
                label,
                isCurrent ? "\uE73E" : null,
                () => _ = SwitchTrackQualityAsync(qualityId)));
        }

        menu.PlacementTarget = QualityButton;
        menu.IsOpen = true;
    }

    private async System.Threading.Tasks.Task SwitchTrackQualityAsync(string qualityId)
    {
        if (_ctx is null) return;
        try
        {
            bool switched = await _ctx.Services.Player.SwitchCurrentTrackQualityAsync(qualityId);
            if (!switched && _ctx.Services.Player.LastError is { Length: > 0 } err)
            {
                ErrorOccurred?.Invoke(L.Get("Quality_SwitchFailedTitle"), err);
            }
        }
        catch (Exception ex)
        {
            ErrorOccurred?.Invoke(L.Get("Quality_SwitchFailedTitle"), ex.Message);
        }
    }

    private void TrackInfo_MouseRightButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (_ctx?.Services.Player.CurrentTrack is not { } track) return;
        var menu = TrackMenuHelper.BuildTrackMenu(
            _ctx.Services,
            [track.Id],
            track.IsEphemeral ? null : track.Id,
            _ctx.OpenTextPrompt,
            null,
            _ctx.OpenSaveCloudTrack,
            _ctx.OpenSongInfo,
            explicitTrack: track);
        menu.PlacementTarget = TrackInfoBar;
        menu.IsOpen = true;
        e.Handled = true;
    }

    private void Favourite_Click(object sender, RoutedEventArgs e)
    {
        if (_ctx?.Services.Player.CurrentTrack is { IsEphemeral: false } track)
            _ctx.Services.Library.ToggleFavourite(track.Id);
    }

    private void Shuffle_Click(object sender, RoutedEventArgs e) => _ctx?.Services.Player.ToggleShuffle();
    private void Prev_Click(object sender, RoutedEventArgs e) => _ctx?.Services.Player.Previous();
    private void PlayPause_Click(object sender, RoutedEventArgs e) => _ctx?.Services.Player.TogglePlayPause();
    private void Next_Click(object sender, RoutedEventArgs e) => _ctx?.Services.Player.Next();
    private void Repeat_Click(object sender, RoutedEventArgs e) => _ctx?.Services.Player.CycleRepeat();
    private void Eq_Click(object sender, RoutedEventArgs e) => OpenEqualizerRequested?.Invoke();
    private void Sleep_Click(object sender, RoutedEventArgs e) => OpenSleepTimerRequested?.Invoke();

    private void PivotTab_Checked(object sender, RoutedEventArgs e)
    {
        if (EmbeddedLyrics is null || EmbeddedQueues is null) return;
        bool showLyrics = LyricsPivotTab.IsChecked == true;
        EmbeddedLyrics.Visibility = showLyrics ? Visibility.Visible : Visibility.Collapsed;
        EmbeddedQueues.Visibility = showLyrics ? Visibility.Collapsed : Visibility.Visible;
    }

    private void Scrubber_PreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e) => _isScrubbing = true;
    private void Scrubber_PreviewMouseLeftButtonUp(object sender, MouseButtonEventArgs e) => FinishScrub();
    private void Scrubber_LostMouseCapture(object sender, MouseEventArgs e) => FinishScrub();

    private void FinishScrub()
    {
        if (!_isScrubbing) return;
        _isScrubbing = false;
        _ctx?.Services.Player.Seek(ScrubberSlider.Value);
    }

    private void Scrubber_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_isScrubbing && _ctx is not null)
            UpdateTimeLabels(e.NewValue, _ctx.Services.Player.DurationSeconds);
    }
}
