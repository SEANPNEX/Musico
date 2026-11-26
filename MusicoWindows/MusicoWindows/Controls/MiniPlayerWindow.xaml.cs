using System;
using System.ComponentModel;
using System.Windows;
using System.Windows.Input;
using MusicoWindows.Core;
using MusicoWindows.Localization;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using MusicoWindows.Theme;

namespace MusicoWindows.Controls;

public partial class MiniPlayerWindow : Window
{
    private readonly AppServices _services;

    public event Action? RestoreMainWindowRequested;

    public MiniPlayerWindow(AppServices services)
    {
        InitializeComponent();
        _services = services;

        _services.Player.PropertyChanged += OnPlayerChanged;
        _services.Queues.PropertyChanged += OnQueueChanged;
        LocalizationManager.LanguageChanged += OnLanguageChanged;
        ThemeManager.AppearanceChanged += OnAppearanceChanged;

        Closed += OnMiniClosed;
        PositionNearWorkArea();
        RefreshAll();
    }

    private void PositionNearWorkArea()
    {
        var workArea = SystemParameters.WorkArea;
        var bounds = WindowStateStore.ClampToBounds(
            workArea.Right - Width - 24,
            workArea.Bottom - Height - 24,
            Width,
            Height,
            workArea.Left,
            workArea.Top,
            workArea.Width,
            workArea.Height,
            minWidth: Width,
            minHeight: Height);
        Left = bounds.Left;
        Top = bounds.Top;
    }

    private void OnMiniClosed(object? sender, EventArgs e)
    {
        _services.Player.PropertyChanged -= OnPlayerChanged;
        _services.Queues.PropertyChanged -= OnQueueChanged;
        LocalizationManager.LanguageChanged -= OnLanguageChanged;
        ThemeManager.AppearanceChanged -= OnAppearanceChanged;
    }

    private void OnPlayerChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(PlayerController.CurrentTrack)
            or nameof(PlayerController.IsPlaying)
            or nameof(PlayerController.DurationSeconds)
            or nameof(PlayerController.CurrentTimeSeconds)
            or null)
        {
            Dispatcher.BeginInvoke(RefreshAll);
        }
    }

    private void OnQueueChanged(object? sender, PropertyChangedEventArgs e) =>
        Dispatcher.BeginInvoke(RefreshAll);

    private void OnLanguageChanged(ResolvedLanguage _) =>
        Dispatcher.BeginInvoke(RefreshAll);

    private void OnAppearanceChanged(AppearanceSettings _) =>
        Dispatcher.BeginInvoke(RefreshAll);

    public void RefreshAll()
    {
        var player = _services.Player;
        var track = player.CurrentTrack;

        CoverArt.SetSource(_services.Library.Artwork, track?.ArtworkKey, 96, track?.RemoteArtworkUrl, _services.Thumbnails);
        TrackTitleText.Text = track?.Title ?? L.Get("PlayerBar_NotPlaying");
        TrackArtistText.Text = track != null ? L.DisplayArtist(track.Artist) : L.Get("PlayerBar_PickSong");

        bool hasTrack = track != null;
        PrevButton.IsEnabled = hasTrack;
        PlayPauseButton.IsEnabled = hasTrack;
        NextButton.IsEnabled = hasTrack;
        PlayPauseButton.Content = player.IsPlaying ? "\uE769" : "\uE768";

        double duration = player.DurationSeconds;
        double fraction = hasTrack && duration > 0
            ? Math.Clamp(player.CurrentTimeSeconds / duration, 0, 1)
            : 0;
        ProgressTrack.Value = fraction;

        PinButton.SetResourceReference(ForegroundProperty, Topmost ? "AccentBrush" : "TextSecondaryBrush");
    }

    private void RootCard_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed)
        {
            DragMove();
        }
    }

    private void Prev_Click(object sender, RoutedEventArgs e) => _services.Player.Previous();
    private void PlayPause_Click(object sender, RoutedEventArgs e) => _services.Player.TogglePlayPause();
    private void Next_Click(object sender, RoutedEventArgs e) => _services.Player.Next();

    private void Pin_Click(object sender, RoutedEventArgs e)
    {
        Topmost = !Topmost;
        RefreshAll();
    }

    private void Restore_Click(object sender, RoutedEventArgs e) => RestoreMainWindowRequested?.Invoke();
    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
