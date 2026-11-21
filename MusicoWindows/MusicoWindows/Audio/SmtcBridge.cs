using MusicoWindows.Models;
using Windows.Media;
using Windows.Media.Playback;
using Windows.Storage;
using Windows.Storage.Streams;

namespace MusicoWindows.Audio;

/// <summary>
/// Windows 10 SystemMediaTransportControls (SMTC) integration via MediaPlayer.
/// Publishes Now Playing metadata/artwork and forwards hardware/overlay media buttons.
/// </summary>
public sealed class SmtcBridge : ISmtcBridge
{
    private readonly MediaPlayer? _mediaPlayer;
    private readonly SystemMediaTransportControls? _smtc;
    private string? _lastArtworkPath;
    private bool _disposed;

    public event Action? PlayRequested;
    public event Action? PauseRequested;
    public event Action? NextRequested;
    public event Action? PreviousRequested;

    public SmtcBridge()
    {
        try
        {
            _mediaPlayer = new MediaPlayer();
            _mediaPlayer.CommandManager.IsEnabled = false;
            _smtc = _mediaPlayer.SystemMediaTransportControls;
            _smtc.IsEnabled = true;
            _smtc.IsPlayEnabled = true;
            _smtc.IsPauseEnabled = true;
            _smtc.IsNextEnabled = true;
            _smtc.IsPreviousEnabled = true;
            _smtc.PlaybackStatus = MediaPlaybackStatus.Closed;
            _smtc.ButtonPressed += OnButtonPressed;
        }
        catch
        {
            // Headless or server SKUs without Media Foundation / SMTC services degrade gracefully.
            _mediaPlayer?.Dispose();
            _mediaPlayer = null;
            _smtc = null;
        }
    }

    public void UpdateTrack(Track? track, string? artworkPath)
    {
        if (_disposed || _smtc is null) return;

        try
        {
            var updater = _smtc.DisplayUpdater;
            if (track is null)
            {
                _lastArtworkPath = null;
                _smtc.PlaybackStatus = MediaPlaybackStatus.Closed;
                updater.ClearAll();
                updater.Update();
                return;
            }

            updater.Type = MediaPlaybackType.Music;
            updater.MusicProperties.Title = track.Title;
            updater.MusicProperties.Artist = track.Artist;
            updater.MusicProperties.AlbumTitle = track.Album;
            updater.MusicProperties.AlbumArtist = track.AlbumArtist;

            if (!string.Equals(_lastArtworkPath, artworkPath, StringComparison.Ordinal))
            {
                _lastArtworkPath = artworkPath;
                if (!string.IsNullOrWhiteSpace(artworkPath) && File.Exists(artworkPath))
                {
                    _ = ApplyThumbnailAsync(updater, artworkPath);
                }
                else
                {
                    updater.Thumbnail = null;
                }
            }

            updater.Update();
        }
        catch
        {
            // Ignore transient WinRT COM exceptions during shutdown or session lock.
        }
    }

    public void UpdatePlaybackState(bool isPlaying, double positionSeconds, double durationSeconds)
    {
        if (_disposed || _smtc is null) return;

        try
        {
            _smtc.PlaybackStatus = isPlaying ? MediaPlaybackStatus.Playing : MediaPlaybackStatus.Paused;

            if (durationSeconds > 0 && double.IsFinite(durationSeconds))
            {
                var timeline = new SystemMediaTransportControlsTimelineProperties
                {
                    StartTime = TimeSpan.Zero,
                    MinSeekTime = TimeSpan.Zero,
                    EndTime = TimeSpan.FromSeconds(durationSeconds),
                    MaxSeekTime = TimeSpan.FromSeconds(durationSeconds),
                    Position = TimeSpan.FromSeconds(Math.Clamp(positionSeconds, 0, durationSeconds))
                };
                _smtc.UpdateTimelineProperties(timeline);
            }
        }
        catch
        {
            // Ignore transient WinRT COM exceptions.
        }
    }

    private async Task ApplyThumbnailAsync(SystemMediaTransportControlsDisplayUpdater updater, string artworkPath)
    {
        try
        {
            var file = await StorageFile.GetFileFromPathAsync(artworkPath);
            if (_disposed || !string.Equals(_lastArtworkPath, artworkPath, StringComparison.Ordinal)) return;
            updater.Thumbnail = RandomAccessStreamReference.CreateFromFile(file);
            updater.Update();
        }
        catch
        {
            // Ignore thumbnail load failures.
        }
    }

    private void OnButtonPressed(SystemMediaTransportControls sender, SystemMediaTransportControlsButtonPressedEventArgs args)
    {
        if (_disposed) return;

        switch (args.Button)
        {
            case SystemMediaTransportControlsButton.Play:
                PlayRequested?.Invoke();
                break;
            case SystemMediaTransportControlsButton.Pause:
                PauseRequested?.Invoke();
                break;
            case SystemMediaTransportControlsButton.Next:
                NextRequested?.Invoke();
                break;
            case SystemMediaTransportControlsButton.Previous:
                PreviousRequested?.Invoke();
                break;
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        if (_smtc is not null)
        {
            try
            {
                _smtc.ButtonPressed -= OnButtonPressed;
                _smtc.IsEnabled = false;
            }
            catch { /* ignore */ }
        }

        try { _mediaPlayer?.Dispose(); } catch { /* ignore */ }
    }
}
