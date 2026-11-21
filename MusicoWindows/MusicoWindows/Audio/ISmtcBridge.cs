using MusicoWindows.Models;

namespace MusicoWindows.Audio;

public interface ISmtcBridge : IDisposable
{
    event Action? PlayRequested;
    event Action? PauseRequested;
    event Action? NextRequested;
    event Action? PreviousRequested;

    void UpdateTrack(Track? track, string? artworkPath);
    void UpdatePlaybackState(bool isPlaying, double positionSeconds, double durationSeconds);
}
