using MusicoWindows.Audio;
using MusicoWindows.Core;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class PlayerControllerTests : IDisposable
{
    private sealed class FakeAudioEngine : IAudioEngine
    {
        public float Volume { get; set; } = 1.0f;
        public double CurrentTimeSeconds { get; private set; }
        public double DurationSeconds { get; private set; }
        public bool IsPlaying { get; private set; }
        public EqSettings LastEq { get; private set; } = new();
        public HashSet<string> FailFileNames { get; } = new(StringComparer.OrdinalIgnoreCase);

        public event Action? TrackEnded;
        public void RaiseTrackEnded() => TrackEnded?.Invoke();

        public void Load(string path)
        {
            string name = Path.GetFileName(path);
            if (FailFileNames.Contains(name))
            {
                throw new InvalidOperationException("Unplayable");
            }
            DurationSeconds = 60.0;
            CurrentTimeSeconds = 0.0;
            IsPlaying = false;
        }

        public void Play() => IsPlaying = true;
        public void Pause() => IsPlaying = false;
        public void Seek(double seconds) => CurrentTimeSeconds = Math.Clamp(seconds, 0, DurationSeconds);
        public void Unload()
        {
            IsPlaying = false;
            DurationSeconds = 0;
            CurrentTimeSeconds = 0;
        }
        public void ApplyEq(EqSettings settings) => LastEq = settings.Clone();
        public void Dispose() => Unload();
    }

    private readonly string _stateDir = TestHelpers.MakeTempDir();
    private readonly string _musicDir = TestHelpers.MakeTempDir();
    private readonly FakeAudioEngine _engine = new();
    private readonly LibraryStore _library;
    private QueueStore _queues;
    private PlayHistoryStore _history;
    private PlayerController _player;

    public PlayerControllerTests()
    {
        TestHelpers.TouchFile(_musicDir, "a.mp3");
        TestHelpers.TouchFile(_musicDir, "b.mp3");
        TestHelpers.TouchFile(_musicDir, "bad1.mp3");
        TestHelpers.TouchFile(_musicDir, "bad2.mp3");
        TestHelpers.TouchFile(_musicDir, "bad3.mp3");

        _engine.FailFileNames.Add("bad1.mp3");
        _engine.FailFileNames.Add("bad2.mp3");
        _engine.FailFileNames.Add("bad3.mp3");

        _library = new LibraryStore(
            _stateDir,
            _musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(_stateDir, "Art")));
        _library.RescanAsync().GetAwaiter().GetResult();

        _queues = new QueueStore(_stateDir);
        _history = new PlayHistoryStore(_stateDir);
        _player = new PlayerController(
            _library,
            _queues,
            _history,
            _engine,
            Path.Combine(_stateDir, "settings.json"),
            smtc: null,
            enableTicker: false);
    }

    public void Dispose() => _player.Dispose();

    private string Id(string relPath) => _library.Tracks.First(t => t.RelativePath == relPath).Id;

    [Fact]
    public void ListeningToHalfSongCountsOnePlay()
    {
        _player.Play([Id("a.mp3")], startAt: 0);
        _player.NoteListening(20);
        Assert.Equal(0, _history.GetCount(Id("a.mp3")));

        _player.NoteListening(10);
        Assert.Equal(1, _history.GetCount(Id("a.mp3")));

        _player.NoteListening(25);
        _player.TrackDidEnd();
        Assert.Equal(1, _history.GetCount(Id("a.mp3")));
        Assert.Equal(55, _history.TotalSeconds, precision: 3);
    }

    [Fact]
    public void UnplayableFileIsSkippedAndThreeFailuresStopWithError()
    {
        _player.Play([Id("bad1.mp3"), Id("a.mp3")], startAt: 0);
        Assert.Equal("a.mp3", _player.CurrentTrack?.RelativePath);
        Assert.Equal(1, _queues.Active.CurrentIndex);

        _player.Play([Id("bad1.mp3"), Id("bad2.mp3"), Id("bad3.mp3"), Id("a.mp3")], startAt: 0);
        Assert.NotNull(_player.ErrorMessage);
        Assert.False(_player.IsPlaying);
        Assert.Equal(2, _queues.Active.CurrentIndex);
    }

    [Fact]
    public void PreviousRestartsAfterThreeSecondsOtherwiseGoesBack()
    {
        _player.Play([Id("a.mp3"), Id("b.mp3")], startAt: 1);
        _player.Seek(10);
        _player.Previous();
        Assert.Equal("b.mp3", _player.CurrentTrack?.RelativePath);
        Assert.Equal(0, _player.CurrentTimeSeconds, precision: 2);

        _player.Previous();
        Assert.Equal("a.mp3", _player.CurrentTrack?.RelativePath);
    }

    [Fact]
    public void SleepAtEndOfTrackLoadsNextPaused()
    {
        _player.Play([Id("a.mp3"), Id("b.mp3")], startAt: 0);
        _player.SetSleep(SleepState.EndOfTrack);
        _player.TrackDidEnd();

        Assert.Equal("b.mp3", _player.CurrentTrack?.RelativePath);
        Assert.False(_player.IsPlaying);
        Assert.Equal(SleepModeKind.Off, _player.SleepState.Kind);
    }

    [Fact]
    public void EqSettingsPersistAcrossControllerInstances()
    {
        _player.ApplyEq(new EqSettings
        {
            Enabled = true,
            PreampDb = 2f,
            Gains = EqPreset.All[3].Gains,
            PresetName = "Rock"
        });
        _player.Shutdown();

        _queues = new QueueStore(_stateDir);
        _history = new PlayHistoryStore(_stateDir);
        _player = new PlayerController(
            _library,
            _queues,
            _history,
            _engine,
            Path.Combine(_stateDir, "settings.json"),
            smtc: null,
            enableTicker: false);

        Assert.Equal("Rock", _player.Eq.PresetName);
        Assert.True(_player.Eq.Enabled);
        Assert.True(_engine.LastEq.Enabled);
    }
}
