using System.Net;
using System.Net.Http;
using System.Text;
using MusicoWindows.Audio;
using MusicoWindows.Cloud;
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
        public List<string> LoadedPaths { get; } = new();

        public void Load(string path)
        {
            LoadedPaths.Add(path);
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

    private sealed class AsyncStubHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> _fn;
        public AsyncStubHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> fn) => _fn = fn;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            _fn(request, cancellationToken);
    }

    [Fact]
    public async Task RapidCloudTrackSkipsCancelStaleLoadAndPrefetchNext()
    {
        string playlistJson = """
        {
          "header": { "musicDetailHeaderRenderer": { "title": { "runs": [{ "text": "Cloud" }] } } },
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicPlaylistShelfRenderer": {
                          "contents": [
                            { "musicResponsiveListItemRenderer": { "playlistItemData": { "videoId": "vidSlow0001" }, "flexColumns": [ { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Slow 1" }] } } }, { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Artist" }] } } } ] } },
                            { "musicResponsiveListItemRenderer": { "playlistItemData": { "videoId": "vidFast0002" }, "flexColumns": [ { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Fast 2" }] } } }, { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Artist" }] } } } ] } },
                            { "musicResponsiveListItemRenderer": { "playlistItemData": { "videoId": "vidNext0003" }, "flexColumns": [ { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Next 3" }] } } }, { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Artist" }] } } } ] } }
                          ]
                        }
                      }]
                    }
                  }
                }
              }]
            }
          }
        }
        """;

        var slowGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var handler = new AsyncStubHandler(async (req, ct) =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/youtubei/v1/browse"))
            {
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(playlistJson, Encoding.UTF8, "application/json")
                };
            }
            if (url.Contains("/youtubei/v1/player"))
            {
                string body = await req.Content!.ReadAsStringAsync(ct);
                string vid = body.Contains("vidSlow0001") ? "vidSlow0001" : body.Contains("vidFast0002") ? "vidFast0002" : "vidNext0003";
                if (vid == "vidSlow0001")
                {
                    await slowGate.Task.WaitAsync(ct);
                }
                string playerJson = $$"""
                {
                  "streamingData": {
                    "adaptiveFormats": [
                      { "itag": 140, "url": "https://cdn.example/{{vid}}.m4a", "mimeType": "audio/mp4", "bitrate": 128000 }
                    ]
                  }
                }
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(playerJson, Encoding.UTF8, "application/json")
                };
            }
            if (url.StartsWith("https://cdn.example/", StringComparison.Ordinal))
            {
                if (req.Headers.Range is not null)
                {
                    return new HttpResponseMessage(HttpStatusCode.PartialContent)
                    {
                        Content = new ByteArrayContent([1, 2, 3, 4, 5, 6, 7, 8])
                    };
                }
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent([1, 2, 3, 4, 5, 6, 7, 8])
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var yt = new YouTubeClient(handler);
        var cache = new CloudAudioCache(Path.Combine(_stateDir, "CloudCache"), handler: handler);
        var lib = new LibraryStore(
            _stateDir,
            _musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(_stateDir, "Art2")),
            youtubeClient: yt,
            artworkHandler: handler);

        await lib.AddCloudRootAsync("https://music.youtube.com/playlist?list=PLRaceTest12345");
        var cloudTracks = lib.Tracks.Where(t => t.IsCloud).OrderBy(t => t.TrackNumber).ToList();
        Assert.Equal(3, cloudTracks.Count);

        var q = new QueueStore(_stateDir);
        var h = new PlayHistoryStore(_stateDir);
        using var player = new PlayerController(
            lib,
            q,
            h,
            _engine,
            Path.Combine(_stateDir, "settings_cloud.json"),
            smtc: null,
            enableTicker: false,
            cloudCache: cache,
            youtubeClient: yt);

        _engine.LoadedPaths.Clear();

        // Start slow track 1, then immediately skip to track 2 before track 1 resolves.
        player.Play(cloudTracks.Select(t => t.Id).ToList(), startAt: 0);
        player.Next();

        // Release slow track 1 after skip; stale load must NOT overwrite track 2.
        slowGate.TrySetResult();

        for (int i = 0; i < 200 && !player.IsPlaying; i++)
        {
            await Task.Delay(25);
        }

        Assert.Equal(cloudTracks[1].Id, player.CurrentTrack?.Id);
        Assert.True(player.IsPlaying);
        Assert.Single(_engine.LoadedPaths);
        Assert.Contains(cloudTracks[1].Id, _engine.LoadedPaths[0]);

        // Next track (#3) should be prefetched into CloudCache in the background.
        for (int i = 0; i < 200 && cache.TryGetCachedPath(cloudTracks[2].Id) is null; i++)
        {
            await Task.Delay(25);
        }
        Assert.NotNull(cache.TryGetCachedPath(cloudTracks[2].Id));
    }

    [Fact]
    public void SyncNamedQueueAndPlayInNamedQueueManageQueuesNonDestructively()
    {
        string idA = Id("a.mp3");
        string idB = Id("b.mp3");

        // Start playback in default main queue.
        _player.Play([idA], startAt: 0);
        Guid initialActiveId = _queues.ActiveId;
        Assert.True(_player.IsPlaying);
        Assert.Equal(idA, _player.CurrentTrack?.Id);

        // Non-empty guard: empty list does NOT create an empty named queue.
        _player.SyncNamedQueue("Empty Playlist", Array.Empty<string>());
        Assert.DoesNotContain(_queues.Queues, q => q.Name == "Empty Playlist");

        // SyncNamedQueue creates/updates named queue without interrupting active playback or switching ActiveId.
        _player.SyncNamedQueue("Road Mix", [idA, idB]);
        var roadQueue = _queues.Queues.FirstOrDefault(q => q.Name == "Road Mix");
        Assert.NotNull(roadQueue);
        Assert.Equal([idA, idB], roadQueue!.TrackIds);
        Assert.Equal(initialActiveId, _queues.ActiveId);
        Assert.Equal(idA, _player.CurrentTrack?.Id);
        Assert.True(_player.IsPlaying);

        // Re-syncing existing named queue updates tracks in place without adding duplicate queues.
        _player.SyncNamedQueue("Road Mix", [idB, idA]);
        Assert.Equal(1, _queues.Queues.Count(q => q.Name == "Road Mix"));
        roadQueue = _queues.Queues.First(q => q.Name == "Road Mix");
        Assert.Equal([idB, idA], roadQueue.TrackIds);
        Assert.Equal(initialActiveId, _queues.ActiveId);

        // PlayInNamedQueue activates/updates the named queue and starts playback at requested index & shuffle mode.
        _player.PlayInNamedQueue("Road Mix", [idA, idB], startAt: 1, shuffle: true);
        Assert.Equal(roadQueue.Id, _queues.ActiveId);
        Assert.Equal(idB, _player.CurrentTrack?.Id);
        Assert.True(_queues.Active.Shuffle);
        Assert.True(_player.IsPlaying);
    }
}
