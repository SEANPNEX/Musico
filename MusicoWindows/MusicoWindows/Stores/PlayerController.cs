using MusicoWindows.Audio;
using MusicoWindows.Core;
using MusicoWindows.Models;

namespace MusicoWindows.Stores;

/// <summary>
/// Drives playback from the active queue, manages play history counting, sleep timer, EQ, volume, and SMTC sync.
/// </summary>
public sealed class PlayerController : ObservableStore, IDisposable
{
    private sealed class AudioPrefs
    {
        public float Volume { get; set; } = 1.0f;
        public bool IsMuted { get; set; }
    }

    public const int MaxConsecutiveFailures = 3;
    public const double RestartThresholdSeconds = 3.0;
    public const double TickIntervalSeconds = 0.25;
    public const double RecentAfterSeconds = 5.0;

    private readonly IAudioEngine _engine;
    private readonly ISmtcBridge? _smtc;
    private readonly string _settingsPath;
    private readonly string _audioPrefsPath;
    private readonly Timer _ticker;

    private Track? _currentTrack;
    private bool _isPlaying;
    private double _durationSeconds;
    private double _currentTimeSeconds;
    private float _volume = 1.0f;
    private bool _isMuted;
    private string? _errorMessage;
    private SleepState _sleepState = SleepState.Off;
    private EqSettings _eq;

    private bool _isLoaded;
    private double _listenedSeconds;
    private bool _playCounted;
    private bool _markedRecent;
    private int _tickCount;
    private CancellationTokenSource? _sleepCts;
    private bool _disposed;

    public PlayerController(
        LibraryStore library,
        QueueStore queues,
        PlayHistoryStore history,
        IAudioEngine engine,
        string settingsPath,
        ISmtcBridge? smtc = null,
        bool enableTicker = true)
    {
        Library = library;
        Queues = queues;
        History = history;
        _engine = engine;
        _smtc = smtc;
        _settingsPath = settingsPath;
        _audioPrefsPath = Path.Combine(Path.GetDirectoryName(settingsPath) ?? string.Empty, "audio_prefs.json");

        _eq = Persistence.Load<EqSettings>(_settingsPath) ?? new EqSettings();
        _engine.ApplyEq(_eq);

        var prefs = Persistence.Load<AudioPrefs>(_audioPrefsPath);
        if (prefs is not null)
        {
            _volume = Math.Clamp(prefs.Volume, 0f, 1f);
            _isMuted = prefs.IsMuted;
        }
        ApplyEffectiveVolume();

        _engine.TrackEnded += OnEngineTrackEnded;
        Library.OnTracksRemoved = OnLibraryTracksRemoved;

        if (_smtc is not null)
        {
            _smtc.PlayRequested += OnSmtcPlay;
            _smtc.PauseRequested += OnSmtcPause;
            _smtc.NextRequested += OnSmtcNext;
            _smtc.PreviousRequested += OnSmtcPrevious;
        }

        int periodMs = enableTicker ? (int)(TickIntervalSeconds * 1000) : Timeout.Infinite;
        _ticker = new Timer(_ => InvokeOnUiContext(Tick), null, periodMs, periodMs);
    }

    public LibraryStore Library { get; }
    public QueueStore Queues { get; }
    public PlayHistoryStore History { get; }

    public Track? CurrentTrack { get => _currentTrack; private set => SetProperty(ref _currentTrack, value); }
    public bool IsPlaying { get => _isPlaying; private set => SetProperty(ref _isPlaying, value); }
    public double DurationSeconds { get => _durationSeconds; private set => SetProperty(ref _durationSeconds, value); }
    public double CurrentTimeSeconds { get => _currentTimeSeconds; private set => SetProperty(ref _currentTimeSeconds, value); }
    public string? ErrorMessage { get => _errorMessage; private set => SetProperty(ref _errorMessage, value); }
    public SleepState SleepState { get => _sleepState; private set => SetProperty(ref _sleepState, value); }

    public float Volume
    {
        get => _volume;
        set
        {
            float clamped = Math.Clamp(value, 0f, 1f);
            if (!SetProperty(ref _volume, clamped)) return;
            ApplyEffectiveVolume();
            SaveAudioPrefs();
        }
    }

    public bool IsMuted
    {
        get => _isMuted;
        set
        {
            if (!SetProperty(ref _isMuted, value)) return;
            ApplyEffectiveVolume();
            SaveAudioPrefs();
        }
    }

    public EqSettings Eq
    {
        get => _eq;
        set
        {
            ArgumentNullException.ThrowIfNull(value);
            _eq = value.Clone();
            _engine.ApplyEq(_eq);
            SaveEqSettings();
            OnPropertyChanged(nameof(Eq));
        }
    }

    public void ApplyEq(EqSettings settings) => Eq = settings;

    public void ClearError() => ErrorMessage = null;

    private void ApplyEffectiveVolume() => _engine.Volume = _isMuted ? 0f : _volume;

    private void SaveEqSettings()
    {
        try { Persistence.Save(_eq, _settingsPath); } catch { /* ignore */ }
    }

    private void SaveAudioPrefs()
    {
        try { Persistence.Save(new AudioPrefs { Volume = _volume, IsMuted = _isMuted }, _audioPrefsPath); } catch { /* ignore */ }
    }

    // MARK: Loading

    private bool LoadCurrent(bool autoplay, double position)
    {
        _isLoaded = false;
        string? trackId = Queues.Active.CurrentTrackId;
        var track = trackId is not null ? Library.GetTrack(trackId) : null;
        if (track is null) return false;

        CurrentTrack = track;
        DurationSeconds = track.DurationSeconds;
        CurrentTimeSeconds = 0;

        string? filePath = Library.GetFilePath(track);
        if (filePath is null) return false;

        try
        {
            _engine.Load(filePath);
        }
        catch
        {
            return false;
        }

        _isLoaded = true;
        _listenedSeconds = 0;
        _playCounted = false;
        _markedRecent = false;
        DurationSeconds = _engine.DurationSeconds > 0 ? _engine.DurationSeconds : track.DurationSeconds;

        if (position > 0 && position < DurationSeconds - 1)
        {
            _engine.Seek(position);
        }
        CurrentTimeSeconds = _engine.CurrentTimeSeconds;

        if (autoplay)
        {
            _engine.Play();
        }
        IsPlaying = _engine.IsPlaying;
        UpdateSmtc(trackChanged: true);
        return true;
    }

    private void StartCurrent(bool autoplay, double position = 0)
    {
        int failures = 0;
        double pos = position;

        while (true)
        {
            if (Queues.Active.CurrentTrackId is null)
            {
                ClearPlayback();
                return;
            }

            if (LoadCurrent(autoplay, pos)) return;

            pos = 0;
            failures++;
            string? advanced = null;
            if (failures < MaxConsecutiveFailures)
            {
                Queues.UpdateActive(q => advanced = q.Next(auto: false));
            }

            if (advanced is null)
            {
                _engine.Unload();
                IsPlaying = false;
                ErrorMessage = failures > 1
                    ? $"Playback stopped: {failures} songs in a row could not be played."
                    : $"\"{CurrentTrack?.Title ?? "This song"}\" could not be played.";
                UpdateSmtc(trackChanged: true);
                return;
            }
        }
    }

    private void ClearPlayback()
    {
        _engine.Unload();
        _isLoaded = false;
        CurrentTrack = null;
        IsPlaying = false;
        DurationSeconds = 0;
        CurrentTimeSeconds = 0;
        UpdateSmtc(trackChanged: true);
    }

    // MARK: Starting playback

    public void Play(IReadOnlyList<string> ids, int startAt = 0)
    {
        if (ids.Count == 0) return;
        ErrorMessage = null;
        Queues.UpdateActive(q => q.Replace(ids, startAt));
        StartCurrent(autoplay: true);
    }

    public void PlayInNewQueue(IReadOnlyList<string> ids)
    {
        if (ids.Count == 0) return;
        PersistPosition();
        Queues.SetActive(Queues.AddQueue());
        Play(ids, startAt: 0);
    }

    public void PlayNext(IReadOnlyList<string> ids)
    {
        if (ids.Count == 0) return;
        bool wasEmpty = Queues.Active.CurrentTrackId is null;
        Queues.UpdateActive(q => q.InsertNext(ids));
        if (wasEmpty) StartCurrent(autoplay: true);
    }

    public void AddToQueue(IReadOnlyList<string> ids, Guid? queueId = null)
    {
        if (ids.Count == 0) return;
        Guid target = queueId ?? Queues.ActiveId;
        bool startsPlayback = target == Queues.ActiveId && Queues.Active.CurrentTrackId is null;
        Queues.Update(target, q => q.Append(ids));
        if (startsPlayback) StartCurrent(autoplay: true);
    }

    // MARK: Transport

    public void TogglePlayPause()
    {
        if (IsPlaying) Pause();
        else Resume();
    }

    public void Resume()
    {
        if (IsPlaying) return;
        ErrorMessage = null;
        if (_isLoaded)
        {
            _engine.Play();
            IsPlaying = _engine.IsPlaying;
            UpdateSmtc(trackChanged: false);
        }
        else
        {
            StartCurrent(autoplay: true, position: Queues.Active.PositionSeconds);
        }
    }

    public void Pause()
    {
        if (!IsPlaying) return;
        _engine.Pause();
        IsPlaying = false;
        CurrentTimeSeconds = _engine.CurrentTimeSeconds;
        PersistPosition(flush: true);
        UpdateSmtc(trackChanged: false);
    }

    public void Next()
    {
        bool wasPlaying = IsPlaying;
        string? advanced = null;
        Queues.UpdateActive(q => advanced = q.Next(auto: false));
        if (advanced is not null) StartCurrent(autoplay: wasPlaying);
    }

    public void Previous()
    {
        if (_isLoaded && _engine.CurrentTimeSeconds > RestartThresholdSeconds)
        {
            Seek(0);
            return;
        }
        bool wasPlaying = IsPlaying;
        Queues.UpdateActive(q => _ = q.Previous());
        StartCurrent(autoplay: wasPlaying);
    }

    public void Seek(double seconds)
    {
        if (!_isLoaded) return;
        _engine.Seek(seconds);
        CurrentTimeSeconds = _engine.CurrentTimeSeconds;
        UpdateSmtc(trackChanged: false);
    }

    public void Jump(int index)
    {
        ErrorMessage = null;
        Queues.UpdateActive(q => q.Jump(index));
        StartCurrent(autoplay: true);
    }

    public void ToggleShuffle()
    {
        Queues.UpdateActive(q => q.SetShuffle(!q.Shuffle));
    }

    public void CycleRepeat()
    {
        Queues.UpdateActive(q =>
        {
            q.RepeatMode = q.RepeatMode switch
            {
                RepeatMode.Off => RepeatMode.All,
                RepeatMode.All => RepeatMode.One,
                _ => RepeatMode.Off
            };
        });
    }

    // MARK: Play history & Track completion

    public void NoteListening(double seconds)
    {
        if (!_isLoaded || CurrentTrack is not { } track) return;
        _listenedSeconds += seconds;
        History.AddListening(seconds);
        if (!_markedRecent && _listenedSeconds >= RecentAfterSeconds)
        {
            _markedRecent = true;
            History.MarkStarted(track.Id);
        }
        if (!_playCounted && DurationSeconds > 0 && _listenedSeconds >= DurationSeconds / 2.0)
        {
            CountPlay(track);
        }
    }

    private void CountPlay(Track track)
    {
        _playCounted = true;
        _markedRecent = true;
        History.RecordPlay(track.Id);
    }

    public void TrackDidEnd()
    {
        if (_isLoaded && !_playCounted && CurrentTrack is { } track)
        {
            CountPlay(track);
        }

        bool stopAfter = SleepState.Kind == SleepModeKind.EndOfTrack;
        if (stopAfter) SetSleep(SleepState.Off);

        string? advanced = null;
        Queues.UpdateActive(q => advanced = q.Next(auto: true));
        if (advanced is not null)
        {
            StartCurrent(autoplay: !stopAfter);
        }
        else
        {
            _engine.Pause();
            _engine.Seek(0);
            IsPlaying = false;
            CurrentTimeSeconds = 0;
            PersistPosition(flush: true);
            UpdateSmtc(trackChanged: false);
        }
    }

    // MARK: Queue management

    public void SwitchQueue(Guid id)
    {
        if (id == Queues.ActiveId) return;
        bool wasPlaying = IsPlaying;
        PersistPosition(flush: true);
        Queues.SetActive(id);
        ErrorMessage = null;
        StartCurrent(autoplay: wasPlaying, position: Queues.Active.PositionSeconds);
    }

    public void DeleteQueue(Guid id)
    {
        bool wasActive = id == Queues.ActiveId;
        bool wasPlaying = IsPlaying;
        Queues.Delete(id);
        if (wasActive && Queues.ActiveId != id)
        {
            StartCurrent(autoplay: wasPlaying, position: Queues.Active.PositionSeconds);
        }
    }

    public void RemoveFromQueue(int index)
    {
        bool removingCurrent = index == Queues.Active.CurrentIndex;
        bool wasPlaying = IsPlaying;
        Queues.UpdateActive(q => q.RemoveAt(index));
        if (removingCurrent) StartCurrent(autoplay: wasPlaying);
    }

    public void MoveInQueue(int fromIndex, int toIndex)
    {
        Queues.UpdateActive(q => q.Move(fromIndex, toIndex));
    }

    public void ClearQueue()
    {
        Queues.UpdateActive(q => q.Replace(Array.Empty<string>(), 0));
        ClearPlayback();
    }

    private void OnLibraryTracksRemoved(ISet<string> ids)
    {
        bool currentRemoved = CurrentTrack is not null && ids.Contains(CurrentTrack.Id);
        bool wasPlaying = IsPlaying;
        Queues.RemoveTracks(ids);
        History.RemoveTracks(ids);
        if (currentRemoved) StartCurrent(autoplay: wasPlaying);
    }

    // MARK: Persistence & Ticker

    public void Restore()
    {
        _ = LoadCurrent(autoplay: false, position: Queues.Active.PositionSeconds);
    }

    public void PersistPosition(bool flush = true)
    {
        if (!_isLoaded) return;
        Queues.SetActivePosition(_engine.CurrentTimeSeconds);
        if (flush)
        {
            Queues.Save();
            History.Save();
        }
    }

    private void Tick()
    {
        if (_disposed || !IsPlaying) return;
        CurrentTimeSeconds = _engine.CurrentTimeSeconds;
        NoteListening(TickIntervalSeconds);
        _tickCount++;
        if (_tickCount % 20 == 0)
        {
            PersistPosition(flush: _tickCount % 120 == 0);
        }
    }

    // MARK: Sleep timer

    public void SetSleep(SleepState state)
    {
        _sleepCts?.Cancel();
        _sleepCts?.Dispose();
        _sleepCts = null;

        SleepState = state;
        if (state.Kind != SleepModeKind.Until || state.UntilUtc is not DateTimeOffset until) return;

        var cts = new CancellationTokenSource();
        _sleepCts = cts;
        var delay = until - DateTimeOffset.UtcNow;
        if (delay < TimeSpan.Zero) delay = TimeSpan.Zero;

        _ = RunSleepCountdownAsync(delay, cts.Token);
    }

    private async Task RunSleepCountdownAsync(TimeSpan delay, CancellationToken token)
    {
        try
        {
            await Task.Delay(delay, token);
            if (token.IsCancellationRequested || _disposed) return;
            InvokeOnUiContext(() =>
            {
                if (token.IsCancellationRequested || _disposed) return;
                SleepState = SleepState.Off;
                Pause();
            });
        }
        catch (OperationCanceledException)
        {
            // Cancelled by user or Shutdown.
        }
    }

    // MARK: System & Shutdown

    private void UpdateSmtc(bool trackChanged)
    {
        if (_smtc is null) return;
        if (trackChanged)
        {
            string? artPath = CurrentTrack?.ArtworkKey is { } key ? Library.Artwork.GetImagePath(key) : null;
            _smtc.UpdateTrack(CurrentTrack, artPath);
        }
        _smtc.UpdatePlaybackState(IsPlaying, CurrentTimeSeconds, DurationSeconds);
    }

    private void OnEngineTrackEnded() => InvokeOnUiContext(TrackDidEnd);
    private void OnSmtcPlay() => InvokeOnUiContext(Resume);
    private void OnSmtcPause() => InvokeOnUiContext(Pause);
    private void OnSmtcNext() => InvokeOnUiContext(Next);
    private void OnSmtcPrevious() => InvokeOnUiContext(Previous);

    public void Shutdown()
    {
        if (_disposed) return;
        _disposed = true;

        _ticker.Change(Timeout.Infinite, Timeout.Infinite);
        _ticker.Dispose();

        _sleepCts?.Cancel();
        _sleepCts?.Dispose();
        _sleepCts = null;

        _engine.TrackEnded -= OnEngineTrackEnded;
        if (_smtc is not null)
        {
            _smtc.PlayRequested -= OnSmtcPlay;
            _smtc.PauseRequested -= OnSmtcPause;
            _smtc.NextRequested -= OnSmtcNext;
            _smtc.PreviousRequested -= OnSmtcPrevious;
        }

        _engine.Unload();
        IsPlaying = false;
    }

    public void Dispose() => Shutdown();
}
