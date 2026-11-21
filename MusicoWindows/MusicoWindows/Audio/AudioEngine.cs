using MusicoWindows.Models;
using NAudio.Wave;

namespace MusicoWindows.Audio;

/// <summary>
/// Plays one audio file at a time through AudioFileReader -> TenBandEqualizerSampleProvider -> WaveOutEvent.
/// Uses a generation token to ignore superseded PlaybackStopped callbacks.
/// </summary>
public sealed class AudioEngine : IAudioEngine
{
    private readonly object _lock = new();
    private AudioFileReader? _reader;
    private TenBandEqualizerSampleProvider? _equalizer;
    private WaveOutEvent? _waveOut;
    private EqSettings _eqSettings = new();
    private float _volume = 1.0f;
    private int _generation;
    private bool _disposed;

    public event Action? TrackEnded;

    public float Volume
    {
        get { lock (_lock) return _volume; }
        set
        {
            float clamped = Math.Clamp(value, 0f, 1f);
            lock (_lock)
            {
                _volume = clamped;
                if (_reader is not null)
                {
                    _reader.Volume = clamped;
                }
            }
        }
    }

    public double DurationSeconds
    {
        get
        {
            lock (_lock)
            {
                if (_reader is null) return 0;
                double total = _reader.TotalTime.TotalSeconds;
                return double.IsFinite(total) && total > 0 ? total : 0;
            }
        }
    }

    public double CurrentTimeSeconds
    {
        get
        {
            lock (_lock)
            {
                if (_reader is null) return 0;
                double current = _reader.CurrentTime.TotalSeconds;
                return double.IsFinite(current) && current >= 0 ? Math.Min(current, DurationSeconds) : 0;
            }
        }
    }

    public bool IsPlaying
    {
        get
        {
            lock (_lock)
            {
                return _waveOut?.PlaybackState == PlaybackState.Playing;
            }
        }
    }

    public void Load(string path)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
        {
            throw new FileNotFoundException("Audio file not found.", path);
        }

        Unload();

        AudioFileReader? reader = null;
        WaveOutEvent? waveOut = null;
        try
        {
            reader = new AudioFileReader(path);
            TenBandEqualizerSampleProvider eq;
            int token;

            lock (_lock)
            {
                reader.Volume = _volume;
                eq = new TenBandEqualizerSampleProvider(reader, _eqSettings);
                waveOut = new WaveOutEvent();
                token = _generation;
                waveOut.PlaybackStopped += (_, e) => HandlePlaybackStopped(token, e);
                waveOut.Init(eq);

                _reader = reader;
                _equalizer = eq;
                _waveOut = waveOut;
            }
        }
        catch
        {
            waveOut?.Dispose();
            reader?.Dispose();
            throw;
        }
    }

    public void Play()
    {
        lock (_lock)
        {
            if (_disposed || _waveOut is null || _reader is null) return;
            if (_waveOut.PlaybackState == PlaybackState.Playing) return;

            if (_reader.Position >= _reader.Length && _reader.Length > 0)
            {
                _reader.Position = 0;
            }

            _waveOut.Play();
        }
    }

    public void Pause()
    {
        lock (_lock)
        {
            if (_waveOut is null) return;
            if (_waveOut.PlaybackState == PlaybackState.Playing)
            {
                _waveOut.Pause();
            }
        }
    }

    public void Seek(double seconds)
    {
        bool fireEnded = false;
        lock (_lock)
        {
            if (_reader is null) return;
            double duration = DurationSeconds;
            double target = double.IsFinite(seconds) ? Math.Clamp(seconds, 0, duration) : 0;

            if (duration > 0 && target >= duration && _waveOut?.PlaybackState == PlaybackState.Playing)
            {
                _generation++;
                _waveOut.Stop();
                _reader.CurrentTime = _reader.TotalTime;
                fireEnded = true;
            }
            else
            {
                _reader.CurrentTime = TimeSpan.FromSeconds(target);
            }
        }

        if (fireEnded)
        {
            TrackEnded?.Invoke();
        }
    }

    public void Unload()
    {
        WaveOutEvent? oldOut;
        AudioFileReader? oldReader;

        lock (_lock)
        {
            _generation++;
            oldOut = _waveOut;
            oldReader = _reader;
            _waveOut = null;
            _equalizer = null;
            _reader = null;
        }

        if (oldOut is not null)
        {
            try { oldOut.Stop(); } catch { /* ignore */ }
            oldOut.Dispose();
        }
        oldReader?.Dispose();
    }

    public void ApplyEq(EqSettings settings)
    {
        ArgumentNullException.ThrowIfNull(settings);
        lock (_lock)
        {
            _eqSettings = settings.Clone();
            _equalizer?.Update(_eqSettings);
        }
    }

    private void HandlePlaybackStopped(int token, StoppedEventArgs e)
    {
        bool shouldFire = false;
        lock (_lock)
        {
            if (!_disposed && token == _generation && e.Exception is null && _reader is not null)
            {
                shouldFire = true;
            }
        }

        if (shouldFire)
        {
            TrackEnded?.Invoke();
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Unload();
    }
}
