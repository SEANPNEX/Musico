using MusicoWindows.Models;

namespace MusicoWindows.Audio;

public interface IAudioEngine : IDisposable
{
    float Volume { get; set; }
    double CurrentTimeSeconds { get; }
    double DurationSeconds { get; }
    bool IsPlaying { get; }

    event Action? TrackEnded;

    void Load(string path);
    void Play();
    void Pause();
    void Seek(double seconds);
    void Unload();
    void ApplyEq(EqSettings settings);
}
