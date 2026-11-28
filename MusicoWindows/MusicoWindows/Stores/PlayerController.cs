using MusicoWindows.Audio;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using MusicoWindows.Localization;
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
    private readonly CloudAudioCache _cloudCache;
    private readonly GoogleDriveClient _googleDrive;
    private readonly CloudProviderRegistry _providerRegistry;
    private readonly ExtensionStore? _extensions;
    private readonly Timer _ticker;

    private Track? _currentTrack;
    private bool _isPlaying;
    private bool _isLoadingCloud;
    private double _durationSeconds;
    private double _currentTimeSeconds;
    private float _volume = 1.0f;
    private bool _isMuted;
    private string? _errorMessage;
    private SleepState _sleepState = SleepState.Off;
    private EqSettings _eq;
    private string? _currentQualityId;
    private string? _currentQualityLabel;
    private IReadOnlyList<ExtensionQualityOption> _currentTrackAvailableQualities = Array.Empty<ExtensionQualityOption>();
    private string? _qualityOverrideTrackId;
    private string? _qualityOverrideId;

    private bool _isLoaded;
    private bool _targetAutoplay;
    private double _listenedSeconds;
    private bool _playCounted;
    private bool _markedRecent;
    private int _tickCount;
    private int _loadGeneration;
    private CancellationTokenSource? _loadCts;
    private CancellationTokenSource? _prefetchCts;
    private CancellationTokenSource? _sleepCts;
    private bool _disposed;

    public PlayerController(
        LibraryStore library,
        QueueStore queues,
        PlayHistoryStore history,
        IAudioEngine engine,
        string settingsPath,
        ISmtcBridge? smtc = null,
        bool enableTicker = true,
        CloudAudioCache? cloudCache = null,
        GoogleDriveClient? googleDriveClient = null,
        CloudProviderRegistry? providerRegistry = null,
        ExtensionStore? extensions = null)
    {
        Library = library;
        Queues = queues;
        History = history;
        _engine = engine;
        _smtc = smtc;
        _settingsPath = settingsPath;
        string stateDir = Path.GetDirectoryName(settingsPath) ?? string.Empty;
        _audioPrefsPath = Path.Combine(stateDir, "audio_prefs.json");
        _cloudCache = cloudCache ?? new CloudAudioCache(Path.Combine(stateDir, "CloudCache"));
        _googleDrive = googleDriveClient ?? new GoogleDriveClient();
        _providerRegistry = providerRegistry ?? library.CloudProviders;
        _extensions = extensions;

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
        Library.OnBeforeDeletingTracks = OnBeforeDeletingTracks;
        Library.OnTracksRemoved = OnLibraryTracksRemoved;
        Library.OnTracksMigrated = OnLibraryTracksMigrated;
        Library.OnFolderAdded = OnLibraryFolderAdded;
        Library.TrackUpdated += OnLibraryTrackUpdated;
        Queues.QueuesChanged += OnQueuesChanged;

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
    public bool IsLoadingCloud { get => _isLoadingCloud; private set => SetProperty(ref _isLoadingCloud, value); }
    public double DurationSeconds { get => _durationSeconds; private set => SetProperty(ref _durationSeconds, value); }
    public double CurrentTimeSeconds { get => _currentTimeSeconds; private set => SetProperty(ref _currentTimeSeconds, value); }
    public string? ErrorMessage { get => _errorMessage; private set => SetProperty(ref _errorMessage, value); }
    public SleepState SleepState { get => _sleepState; private set => SetProperty(ref _sleepState, value); }
    public string? CurrentQualityId { get => _currentQualityId; private set => SetProperty(ref _currentQualityId, value); }
    public string? CurrentQualityLabel { get => _currentQualityLabel; private set => SetProperty(ref _currentQualityLabel, value); }
    public IReadOnlyList<ExtensionQualityOption> CurrentTrackAvailableQualities
    {
        get => _currentTrackAvailableQualities;
        private set => SetProperty(ref _currentTrackAvailableQualities, value);
    }

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

    private void CancelPendingCloudLoad()
    {
        _loadGeneration++;
        _loadCts?.Cancel();
        _loadCts?.Dispose();
        _loadCts = null;
        IsLoadingCloud = false;
        _targetAutoplay = false;
    }

    private bool LoadCurrent(bool autoplay, double position)
    {
        _isLoaded = false;
        string? trackId = Queues.Active.CurrentTrackId;
        var track = trackId is not null ? Library.GetTrack(trackId) : null;
        if (track is null || track.IsCloud) return false;

        _qualityOverrideTrackId = null;
        _qualityOverrideId = null;
        CurrentQualityId = null;
        CurrentQualityLabel = null;
        CurrentTrackAvailableQualities = Array.Empty<ExtensionQualityOption>();
        CurrentTrack = track;
        DurationSeconds = track.DurationSeconds;
        CurrentTimeSeconds = 0;

        string? filePath = Library.GetFilePath(track);
        if (filePath is null) return false;

        return FinishTrackLoad(track, filePath, autoplay, position);
    }

    private bool FinishTrackLoad(Track track, string filePath, bool autoplay, double position)
    {
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
        PrefetchNextCloudTrack();
        return true;
    }

    private void StartCurrent(bool autoplay, double position = 0)
    {
        CancelPendingCloudLoad();
        int failures = 0;
        double pos = position;

        while (true)
        {
            string? trackId = Queues.Active.CurrentTrackId;
            if (trackId is null)
            {
                ClearPlayback();
                return;
            }

            var track = Library.GetTrack(trackId);
            if (track is not null && track.IsCloud)
            {
                BeginCloudLoad(track, autoplay, pos);
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
                    ? L.Format("Player_StoppedMultipleError", failures)
                    : L.Format("Player_CouldNotPlaySong", CurrentTrack?.Title ?? L.Get("Player_ThisSong"));
                UpdateSmtc(trackChanged: true);
                return;
            }
        }
    }

    private string? GetTrackQualityOverride(string trackId) =>
        string.Equals(_qualityOverrideTrackId, trackId, StringComparison.Ordinal)
            ? _qualityOverrideId
            : null;

    private void BeginCloudLoad(Track track, bool autoplay, double position)
    {
        _engine.Unload();
        _isLoaded = false;
        IsPlaying = false;
        if (!string.Equals(_qualityOverrideTrackId, track.Id, StringComparison.Ordinal))
        {
            _qualityOverrideTrackId = null;
            _qualityOverrideId = null;
        }
        var root = Library.GetRoot(track.RootId);
        var provider = CloudStreamResolver.GetProvider(_providerRegistry, track, root);
        string? initialQuality = CloudStreamResolver.ResolveRequestedQuality(
            provider, track, root, _extensions, CloudStreamPurpose.Stream, GetTrackQualityOverride(track.Id));
        CurrentTrackAvailableQualities = provider?.AvailableQualities ?? Array.Empty<ExtensionQualityOption>();
        CurrentQualityId = initialQuality;
        CurrentQualityLabel = CloudStreamResolver.ResolveQualityLabel(provider, initialQuality);

        CurrentTrack = track;
        DurationSeconds = track.DurationSeconds;
        CurrentTimeSeconds = 0;
        IsLoadingCloud = true;
        _targetAutoplay = autoplay;
        UpdateSmtc(trackChanged: true);

        var cts = new CancellationTokenSource();
        _loadCts = cts;
        int gen = _loadGeneration;
        _ = LoadCloudAndStartAsync(position, gen, cts.Token);
    }

    private async Task LoadCloudAndStartAsync(double position, int gen, CancellationToken ct)
    {
        int failures = 0;
        double pos = position;
        CloudServiceException? lastCloudError = null;

        while (true)
        {
            if (ct.IsCancellationRequested || gen != _loadGeneration || _disposed) return;

            string? trackId = Queues.Active.CurrentTrackId;
            var track = trackId is not null ? Library.GetTrack(trackId) : null;
            if (track is null)
            {
                await RunOnUiAsync(() =>
                {
                    if (gen == _loadGeneration && !_disposed) ClearPlayback();
                }).ConfigureAwait(false);
                return;
            }

            string? filePath = null;
            CloudAudioStream? resolvedStream = null;
            ICloudSourceProvider? cloudProvider = null;
            string? requestedQuality = null;
            try
            {
                if (!track.IsCloud)
                {
                    _qualityOverrideTrackId = null;
                    _qualityOverrideId = null;
                    filePath = Library.GetFilePath(track);
                }
                else
                {
                    if (!string.Equals(_qualityOverrideTrackId, track.Id, StringComparison.Ordinal))
                    {
                        _qualityOverrideTrackId = null;
                        _qualityOverrideId = null;
                    }
                    var root = Library.GetRoot(track.RootId);
                    cloudProvider = CloudStreamResolver.GetProvider(_providerRegistry, track, root);
                    string? qualityOverride = GetTrackQualityOverride(track.Id);
                    requestedQuality = CloudStreamResolver.ResolveRequestedQuality(
                        cloudProvider, track, root, _extensions, CloudStreamPurpose.Stream, qualityOverride);

                    filePath = await _cloudCache.GetOrDownloadAsync(
                        track.Id,
                        async token =>
                        {
                            resolvedStream = await ResolveCloudStreamAsync(
                                track,
                                root,
                                token,
                                forceRefresh: false,
                                qualityOverride: qualityOverride).ConfigureAwait(false);
                            return resolvedStream;
                        },
                        root?.ProxyUrl,
                        ct,
                        quality: requestedQuality).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch (CloudServiceException cse)
            {
                lastCloudError = cse;
                filePath = null;
            }
            catch
            {
                filePath = null;
            }

            if (ct.IsCancellationRequested || gen != _loadGeneration || _disposed) return;

            (TrackMetadata Meta, string? ArtworkKey)? pendingEnrichment = null;
            if (!string.IsNullOrEmpty(filePath) && track.IsCloud && LibraryStore.NeedsCloudMetadataEnrichment(track))
            {
                pendingEnrichment = await Library.ReadCloudEnrichmentAsync(track, filePath, ct).ConfigureAwait(false);
                if (ct.IsCancellationRequested || gen != _loadGeneration || _disposed) return;
            }

            bool loaded = false;
            if (!string.IsNullOrEmpty(filePath))
            {
                loaded = await RunOnUiAsync(() =>
                {
                    if (ct.IsCancellationRequested || gen != _loadGeneration || _disposed) return true;
                    if (pendingEnrichment is { } enrichment)
                    {
                        track = Library.ApplyCloudEnrichment(track.Id, enrichment.Meta, enrichment.ArtworkKey) ?? track;
                    }
                    else
                    {
                        track = Library.GetTrack(track.Id) ?? track;
                    }
                    CurrentTrack = track;
                    DurationSeconds = track.DurationSeconds;
                    CurrentTimeSeconds = 0;
                    if (track.IsCloud)
                    {
                        CurrentTrackAvailableQualities = cloudProvider?.AvailableQualities ?? Array.Empty<ExtensionQualityOption>();
                        string? actualQuality = resolvedStream?.ActualQuality ?? requestedQuality;
                        CurrentQualityId = actualQuality;
                        CurrentQualityLabel = resolvedStream?.QualityLabel
                                              ?? CloudStreamResolver.ResolveQualityLabel(cloudProvider, actualQuality);
                    }
                    else
                    {
                        CurrentTrackAvailableQualities = Array.Empty<ExtensionQualityOption>();
                        CurrentQualityId = null;
                        CurrentQualityLabel = null;
                    }

                    bool shouldPlay = _targetAutoplay;
                    if (!FinishTrackLoad(track, filePath, shouldPlay, pos)) return false;
                    if (track.IsCloud)
                    {
                        _cloudCache.EvictStaleQualityVariants(track.Id, filePath);
                    }
                    IsLoadingCloud = false;
                    _targetAutoplay = false;
                    return true;
                }).ConfigureAwait(false);
            }

            if (loaded) return;

            pos = 0;
            failures++;
            bool stopImmediate = lastCloudError?.Code == CloudErrorCode.ExtensionUnavailable;
            bool shouldContinue = await RunOnUiAsync(() =>
            {
                if (ct.IsCancellationRequested || gen != _loadGeneration || _disposed) return false;
                string? advanced = null;
                if (!stopImmediate && failures < MaxConsecutiveFailures)
                {
                    Queues.UpdateActive(q => advanced = q.Next(auto: false));
                }
                if (advanced is not null) return true;

                _engine.Unload();
                IsPlaying = false;
                IsLoadingCloud = false;
                ErrorMessage = lastCloudError is not null &&
                               (failures == 1 || lastCloudError.Code == CloudErrorCode.ExtensionUnavailable) &&
                               !string.IsNullOrWhiteSpace(lastCloudError.Message)
                    ? lastCloudError.Message
                    : failures > 1
                        ? L.Format("Player_StoppedMultipleError", failures)
                        : L.Format("Player_CouldNotPlaySong", track.Title);
                UpdateSmtc(trackChanged: true);
                return false;
            }).ConfigureAwait(false);

            if (!shouldContinue) return;
        }
    }

    public async Task<bool> SwitchCurrentTrackQualityAsync(string qualityId, CancellationToken ct = default)
    {
        if (_disposed || string.IsNullOrWhiteSpace(qualityId)) return false;
        if (CurrentTrack is not { IsCloud: true } track) return false;

        var root = Library.GetRoot(track.RootId);
        var provider = CloudStreamResolver.GetProvider(_providerRegistry, track, root);
        if (provider is null || !provider.IsAvailable || provider.AvailableQualities.Count == 0)
        {
            return false;
        }

        string trimmed = qualityId.Trim();
        var option = provider.AvailableQualities.FirstOrDefault(q =>
            string.Equals(q.Id.Trim(), trimmed, StringComparison.OrdinalIgnoreCase));
        if (option is null) return false;

        string? prevOverrideTrackId = _qualityOverrideTrackId;
        string? prevOverrideQualityId = _qualityOverrideId;
        string targetQualityId = option.Id.Trim();

        double initialPos = _isLoaded ? _engine.CurrentTimeSeconds : CurrentTimeSeconds;
        bool wasPlaying = IsPlaying || (IsLoadingCloud && _targetAutoplay);

        CancelPendingCloudLoad();
        _qualityOverrideTrackId = track.Id;
        _qualityOverrideId = targetQualityId;
        CurrentTrackAvailableQualities = provider.AvailableQualities;
        CurrentQualityId = targetQualityId;
        CurrentQualityLabel = option.EffectiveBadge;
        IsLoadingCloud = true;
        _targetAutoplay = wasPlaying;

        var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _loadCts = cts;
        int gen = _loadGeneration;

        try
        {
            CloudAudioStream? resolvedStream = null;
            string filePath = await _cloudCache.GetOrDownloadAsync(
                track.Id,
                async token =>
                {
                    resolvedStream = await ResolveCloudStreamAsync(
                        track,
                        root,
                        token,
                        forceRefresh: true,
                        qualityOverride: targetQualityId).ConfigureAwait(false);
                    return resolvedStream;
                },
                root?.ProxyUrl,
                cts.Token,
                quality: targetQualityId,
                forceRefresh: true).ConfigureAwait(false);

            if (cts.IsCancellationRequested || gen != _loadGeneration || _disposed) return false;

            return await RunOnUiAsync(() =>
            {
                if (cts.IsCancellationRequested || gen != _loadGeneration || _disposed || CurrentTrack?.Id != track.Id)
                {
                    return false;
                }

                double resumePos = _isLoaded ? _engine.CurrentTimeSeconds : initialPos;
                bool shouldPlay = _isLoaded ? _engine.IsPlaying : _targetAutoplay;
                if (!FinishTrackLoad(track, filePath, shouldPlay, resumePos))
                {
                    return false;
                }

                _cloudCache.EvictStaleQualityVariants(track.Id, filePath);
                string actualQuality = resolvedStream?.ActualQuality ?? targetQualityId;
                CurrentQualityId = actualQuality;
                CurrentQualityLabel = resolvedStream?.QualityLabel
                                      ?? CloudStreamResolver.ResolveQualityLabel(provider, actualQuality)
                                      ?? option.EffectiveBadge;
                IsLoadingCloud = false;
                _targetAutoplay = false;
                return true;
            }).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            return false;
        }
        catch (Exception ex)
        {
            await RunOnUiAsync(() =>
            {
                if (gen != _loadGeneration || _disposed) return;
                _qualityOverrideTrackId = prevOverrideTrackId;
                _qualityOverrideId = prevOverrideQualityId;
                string? fallbackQuality = CloudStreamResolver.ResolveRequestedQuality(
                    provider, track, root, _extensions, CloudStreamPurpose.Stream, GetTrackQualityOverride(track.Id));
                CurrentQualityId = fallbackQuality;
                CurrentQualityLabel = CloudStreamResolver.ResolveQualityLabel(provider, fallbackQuality);
                IsLoadingCloud = false;
                _targetAutoplay = false;
                ErrorMessage = string.IsNullOrWhiteSpace(ex.Message)
                    ? L.Format("Player_CouldNotPlaySong", track.Title)
                    : ex.Message;
            }).ConfigureAwait(false);
            return false;
        }
    }

    private Task<CloudAudioStream> ResolveCloudStreamAsync(
        Track track,
        LibraryRoot? root,
        CancellationToken ct,
        bool forceRefresh = false,
        string? qualityOverride = null) =>
        CloudStreamResolver.ResolveAsync(
            _providerRegistry,
            track,
            root,
            _extensions,
            forceRefresh: forceRefresh,
            ct: ct,
            purpose: CloudStreamPurpose.Stream,
            qualityOverride: qualityOverride);

    private void PrefetchNextCloudTrack()
    {
        var q = Queues.Active;
        var upcoming = q.UpcomingIndices;
        if (upcoming.Count == 0) return;
        int nextIdx = upcoming[0];
        if (nextIdx < 0 || nextIdx >= q.TrackIds.Count) return;

        var nextTrack = Library.GetTrack(q.TrackIds[nextIdx]);
        if (nextTrack is null || !nextTrack.IsCloud) return;

        _prefetchCts?.Cancel();
        _prefetchCts?.Dispose();
        var cts = new CancellationTokenSource();
        _prefetchCts = cts;
        var root = Library.GetRoot(nextTrack.RootId);
        var nextProvider = CloudStreamResolver.GetProvider(_providerRegistry, nextTrack, root);
        string? nextQuality = CloudStreamResolver.ResolveRequestedQuality(
            nextProvider, nextTrack, root, _extensions, CloudStreamPurpose.Stream, qualityOverride: null);

        _ = Task.Run(async () =>
        {
            try
            {
                await _cloudCache.GetOrDownloadAsync(
                    nextTrack.Id,
                    token => ResolveCloudStreamAsync(nextTrack, root, token),
                    root?.ProxyUrl,
                    cts.Token,
                    quality: nextQuality).ConfigureAwait(false);
            }
            catch
            {
                // Best-effort prefetch only.
            }
        }, cts.Token);
    }

    private Task RunOnUiAsync(Action action) => RunOnUiAsync(() => { action(); return true; });

    private Task<T> RunOnUiAsync<T>(Func<T> func)
    {
        var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        InvokeOnUiContext(() =>
        {
            try
            {
                tcs.TrySetResult(func());
            }
            catch (Exception ex)
            {
                tcs.TrySetException(ex);
            }
        });
        return tcs.Task;
    }

    private void ClearPlayback()
    {
        CancelPendingCloudLoad();
        _engine.Unload();
        _isLoaded = false;
        _qualityOverrideTrackId = null;
        _qualityOverrideId = null;
        CurrentQualityId = null;
        CurrentQualityLabel = null;
        CurrentTrackAvailableQualities = Array.Empty<ExtensionQualityOption>();
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

    public void PlayInNewQueue(IReadOnlyList<string> ids) =>
        PlayInNewQueue(ids, queueName: null, startAt: 0);

    public void PlayInNewQueue(IReadOnlyList<string> ids, string? queueName, int startAt = 0)
    {
        if (ids.Count == 0) return;
        PersistPosition();
        ErrorMessage = null;
        Queues.AddQueue(queueName, ids, startAt, activate: true);
        StartCurrent(autoplay: true);
    }

    public void SyncNamedQueue(string queueName, IReadOnlyList<string> ids)
    {
        if (string.IsNullOrWhiteSpace(queueName) || ids.Count == 0) return;

        string title = queueName.Trim();
        var existing = Queues.Queues.FirstOrDefault(q => string.Equals(q.Name, title, StringComparison.Ordinal));
        if (existing is not null && existing.Id == Queues.ActiveId)
        {
            if (existing.TrackIds.SequenceEqual(ids, StringComparer.Ordinal))
            {
                return;
            }

            if (IsPlaying || _isLoaded || CurrentTrack is not null)
            {
                string? currentId = CurrentTrack?.Id ?? existing.CurrentTrackId;
                int preservedIdx = -1;
                if (!string.IsNullOrEmpty(currentId))
                {
                    for (int i = 0; i < ids.Count; i++)
                    {
                        if (string.Equals(ids[i], currentId, StringComparison.Ordinal))
                        {
                            preservedIdx = i;
                            break;
                        }
                    }
                }

                if (preservedIdx >= 0)
                {
                    double savedPos = _isLoaded ? _engine.CurrentTimeSeconds : existing.PositionSeconds;
                    Queues.Update(existing.Id, q =>
                    {
                        q.Replace(ids, preservedIdx);
                        q.PositionSeconds = savedPos;
                    });
                    return;
                }
            }
        }

        CreateOrReplaceFolderQueue(title, ids, startPlayback: false);
    }

    public void PlayInNamedQueue(string queueName, IReadOnlyList<string> ids, int startAt = 0, bool? shuffle = null)
    {
        if (ids.Count == 0) return;
        CreateOrReplaceFolderQueue(queueName, ids, startPlayback: true, startAt: startAt, shuffle: shuffle);
    }

    public void SortQueue(TrackSortMode mode) =>
        SortQueue(Queues.ActiveId, mode);

    public void SortQueue(Guid queueId, TrackSortMode mode)
    {
        Queues.SortQueue(queueId, mode, Library, History);
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
        if (IsPlaying || (IsLoadingCloud && _targetAutoplay)) Pause();
        else Resume();
    }

    public void Resume()
    {
        if (IsPlaying) return;
        ErrorMessage = null;
        if (IsLoadingCloud)
        {
            _targetAutoplay = true;
            return;
        }
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
        if (IsLoadingCloud && _targetAutoplay)
        {
            _targetAutoplay = false;
            return;
        }
        if (!IsPlaying) return;
        _engine.Pause();
        IsPlaying = false;
        CurrentTimeSeconds = _engine.CurrentTimeSeconds;
        PersistPosition(flush: true);
        UpdateSmtc(trackChanged: false);
    }

    public void Next()
    {
        bool wasPlaying = IsPlaying || (IsLoadingCloud && _targetAutoplay);
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
        bool wasPlaying = IsPlaying || (IsLoadingCloud && _targetAutoplay);
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

    public void Jump(int index) => PlayTrack(Queues.ActiveId, index);

    public void PlayTrack(Guid queueId, int index)
    {
        var target = Queues.GetById(queueId);
        if (target is null || index < 0 || index >= target.TrackIds.Count) return;

        ErrorMessage = null;
        if (queueId != Queues.ActiveId)
        {
            PersistPosition(flush: true);
            Queues.SetActive(queueId);
        }

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
        if (track.IsEphemeral || Track.HasEphemeralIdPrefix(track.Id)) return;
        _listenedSeconds += seconds;
        History.AddListening(seconds, track);
        if (!_markedRecent && _listenedSeconds >= RecentAfterSeconds)
        {
            _markedRecent = true;
            History.MarkStarted(track);
        }
        if (!_playCounted && DurationSeconds > 0 && _listenedSeconds >= DurationSeconds / 2.0)
        {
            CountPlay(track);
        }
    }

    private void CountPlay(Track track)
    {
        if (track.IsEphemeral || Track.HasEphemeralIdPrefix(track.Id)) return;
        _playCounted = true;
        _markedRecent = true;
        History.RecordPlay(track);
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
        bool wasPlaying = IsPlaying || (IsLoadingCloud && _targetAutoplay);
        PersistPosition(flush: true);
        Queues.SetActive(id);
        ErrorMessage = null;
        StartCurrent(autoplay: wasPlaying, position: Queues.Active.PositionSeconds);
    }

    public void DeleteQueue(Guid id)
    {
        bool wasActive = id == Queues.ActiveId;
        Queues.Delete(id);
        if (wasActive && Queues.ActiveId != id)
        {
            ClearPlayback();
            ErrorMessage = null;
            if (Queues.Active.CurrentTrackId is not null)
            {
                Restore();
            }
        }
    }

    public void RemoveFromQueue(int index) =>
        RemoveFromQueue(Queues.ActiveId, index);

    public void RemoveFromQueue(Guid queueId, int index)
    {
        var queue = Queues.GetById(queueId);
        if (queue is null) return;

        bool removingCurrent = queueId == Queues.ActiveId && index == queue.CurrentIndex;
        bool wasPlaying = IsPlaying || (IsLoadingCloud && _targetAutoplay);
        Queues.Update(queueId, q => q.RemoveAt(index));
        if (removingCurrent) StartCurrent(autoplay: wasPlaying);
    }

    public void MoveInQueue(int fromIndex, int toIndex) =>
        MoveInQueue(Queues.ActiveId, fromIndex, toIndex);

    public void MoveInQueue(Guid queueId, int fromIndex, int toIndex)
    {
        Queues.Update(queueId, q => q.Move(fromIndex, toIndex));
    }

    public void ClearQueue() =>
        ClearQueue(Queues.ActiveId);

    public void ClearQueue(Guid queueId)
    {
        var queue = Queues.GetById(queueId);
        if (queue is null) return;
        Queues.Update(queueId, q => q.Replace(Array.Empty<string>(), 0));
        if (queueId == Queues.ActiveId)
        {
            ClearPlayback();
        }
    }

    private void OnBeforeDeletingTracks(ISet<string> ids)
    {
        if (CurrentTrack is not null && ids.Contains(CurrentTrack.Id))
        {
            CancelPendingCloudLoad();
            _engine.Unload();
            _isLoaded = false;
            IsPlaying = false;
        }
    }

    private void OnLibraryTracksRemoved(ISet<string> ids)
    {
        bool currentRemoved = CurrentTrack is not null && ids.Contains(CurrentTrack.Id);
        bool wasPlaying = IsPlaying || (IsLoadingCloud && _targetAutoplay);
        Queues.RemoveTracks(ids);
        History.RemoveTracks(ids);
        if (currentRemoved) StartCurrent(autoplay: wasPlaying);
    }

    private void OnLibraryTracksMigrated(IReadOnlyDictionary<string, string> idMigrations)
    {
        if (idMigrations.Count == 0) return;
        Queues.MigrateTracks(idMigrations);
        History.MigrateTracks(idMigrations);
        if (CurrentTrack is not null && idMigrations.TryGetValue(CurrentTrack.Id, out string? newId))
        {
            var migrated = Library.GetTrack(newId);
            if (migrated is not null)
            {
                CurrentTrack = migrated;
            }
        }
    }

    private void OnLibraryTrackUpdated(Track updated)
    {
        InvokeOnUiContext(() =>
        {
            if (_disposed) return;
            if (CurrentTrack?.Id == updated.Id)
            {
                CurrentTrack = updated;
                UpdateSmtc(trackChanged: true);
            }
        });
    }

    private void OnLibraryFolderAdded(string folderName, IReadOnlyList<string> trackIds)
    {
        InvokeOnUiContext(() =>
        {
            if (_disposed) return;
            CreateOrReplaceFolderQueue(folderName, trackIds, startPlayback: false);
        });
    }

    private void OnQueuesChanged()
    {
        Library.PruneEphemeralTracks(Queues.GetAllReferencedTrackIds());
    }

    private void CreateOrReplaceFolderQueue(
        string folderName,
        IReadOnlyList<string> trackIds,
        bool startPlayback,
        int startAt = 0,
        bool? shuffle = null)
    {
        if (startPlayback)
        {
            PersistPosition(flush: true);
        }

        var (_, wasActive) = Queues.CreateOrReplaceQueue(
            folderName,
            trackIds,
            startAt: startAt,
            activate: startPlayback,
            shuffle: shuffle);

        if (startPlayback)
        {
            ErrorMessage = null;
            if (trackIds.Count == 0)
            {
                ClearPlayback();
            }
            else
            {
                StartCurrent(autoplay: true);
            }
        }
        else if (wasActive)
        {
            if (trackIds.Count == 0)
            {
                ClearPlayback();
            }
            else if (IsPlaying || _isLoaded || CurrentTrack is not null)
            {
                bool wasPlaying = IsPlaying || (IsLoadingCloud && _targetAutoplay);
                StartCurrent(autoplay: wasPlaying);
            }
        }
    }

    // MARK: Persistence & Ticker

    public void Restore()
    {
        string? trackId = Queues.Active.CurrentTrackId;
        var track = trackId is not null ? Library.GetTrack(trackId) : null;
        if (track is not null && track.IsCloud)
        {
            var root = Library.GetRoot(track.RootId);
            var provider = CloudStreamResolver.GetProvider(_providerRegistry, track, root);
            string? defaultQuality = CloudStreamResolver.ResolveRequestedQuality(
                provider, track, root, _extensions, CloudStreamPurpose.Stream);

            if ((defaultQuality is not null && _cloudCache.TryGetCachedPath(track.Id, defaultQuality) is not null) ||
                _cloudCache.TryGetCachedPath(track.Id) is not null)
            {
                StartCurrent(autoplay: false, position: Queues.Active.PositionSeconds);
            }
            else
            {
                CurrentTrackAvailableQualities = provider?.AvailableQualities ?? Array.Empty<ExtensionQualityOption>();
                CurrentQualityId = defaultQuality;
                CurrentQualityLabel = CloudStreamResolver.ResolveQualityLabel(provider, defaultQuality);
                CurrentTrack = track;
                DurationSeconds = track.DurationSeconds;
                CurrentTimeSeconds = Math.Clamp(Queues.Active.PositionSeconds, 0, Math.Max(track.DurationSeconds, 0));
                UpdateSmtc(trackChanged: true);
            }
            return;
        }
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

        CancelPendingCloudLoad();
        _prefetchCts?.Cancel();
        _prefetchCts?.Dispose();
        _prefetchCts = null;

        _sleepCts?.Cancel();
        _sleepCts?.Dispose();
        _sleepCts = null;

        _engine.TrackEnded -= OnEngineTrackEnded;
        Library.TrackUpdated -= OnLibraryTrackUpdated;
        Queues.QueuesChanged -= OnQueuesChanged;
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
