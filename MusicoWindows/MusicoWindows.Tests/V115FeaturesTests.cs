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

public sealed class V115FeaturesTests
{
    // Valid 67-byte 1x1 RGB PNG so Windows WIC BitmapDecoder accepts it as well as macOS.
    private static readonly byte[] ValidPngBytes =
    [
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
        0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0x1F, 0xEA, 0x02,
        0x00, 0x03, 0xEF, 0x01, 0x99, 0xC3, 0x49, 0xF5, 0x60, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
        0x44, 0xAE, 0x42, 0x60, 0x82
    ];

    private static byte[] MakeMinimalMp3Bytes()
    {
        const int frameSize = 417;
        byte[] data = new byte[frameSize * 2];
        for (int f = 0; f < 2; f++)
        {
            int offset = f * frameSize;
            data[offset] = 0xFF;
            data[offset + 1] = 0xFB;
            data[offset + 2] = 0x90;
            data[offset + 3] = 0x00;
        }
        return data;
    }

    private sealed class FakeAudioEngine : IAudioEngine
    {
        public float Volume { get; set; } = 1.0f;
        public double CurrentTimeSeconds { get; private set; }
        public double DurationSeconds { get; private set; }
        public bool IsPlaying { get; private set; }
        public event Action? TrackEnded;
        public void RaiseTrackEnded() => TrackEnded?.Invoke();

        public void Load(string path)
        {
            DurationSeconds = 120;
            CurrentTimeSeconds = 0;
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

        public void ApplyEq(EqSettings settings) { }
        public void Dispose() => Unload();
    }

    private sealed class StubHttpHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _responder;
        public StubHttpHandler(Func<HttpRequestMessage, HttpResponseMessage> responder) => _responder = responder;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult(_responder(request));
        }
    }

    [Fact]
    public async Task MetadataReaderReadsAndWritesGenreCoverAndAudioDetails()
    {
        string dir = TestHelpers.MakeTempDir();
        string mp3Path = Path.Combine(dir, "sample.mp3");
        File.WriteAllBytes(mp3Path, MakeMinimalMp3Bytes());

        bool wrote = TagLibMetadataReader.TryWriteTags(
            mp3Path,
            title: "Neon Sky",
            artist: "Kano",
            album: "Starlight",
            genre: "Electronic",
            artworkBytes: ValidPngBytes);
        Assert.True(wrote);

        var reader = new TagLibMetadataReader();
        var meta = await reader.ReadAsync(mp3Path);
        Assert.Equal("Neon Sky", meta.Title);
        Assert.Equal("Kano", meta.Artist);
        Assert.Equal("Starlight", meta.Album);
        Assert.Equal("Electronic", meta.Genre);
        Assert.NotNull(meta.ArtworkBytes);
        Assert.NotEmpty(meta.ArtworkBytes!);

        var details = TagLibMetadataReader.ReadAudioDetails(mp3Path);
        Assert.Equal(44100, details.SampleRateHz);
        Assert.Equal(128, details.BitRateKbps);
        Assert.Equal(2, details.Channels);
        Assert.True(details.FileSize > 0);
    }

    [Fact]
    public async Task MetadataAndGenreAndCoverOverridesSurviveRescanAndRestart()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        string songPath = Path.Combine(musicDir, "track.mp3");
        File.WriteAllBytes(songPath, MakeMinimalMp3Bytes());

        var stubReader = new StubMetadataReader();
        stubReader.ByFileName["track.mp3"] = new TrackMetadata(
            Title: "Old Title",
            Artist: "Old Artist",
            Album: "Old Album",
            DurationSeconds: 180,
            Genre: "Pop");

        var artwork = new ArtworkCache(Path.Combine(stateDir, "Artwork"));
        var store = new LibraryStore(stateDir, musicDir, stubReader, artwork);
        await store.RescanAsync();

        var initial = Assert.Single(store.Tracks);
        Assert.Equal("Pop", initial.Genre);

        Track? updatedEventTrack = null;
        store.TrackUpdated += t => updatedEventTrack = t;

        var updated = await store.UpdateTrackMetadataAsync(
            initial.Id,
            title: "Edited Title",
            artist: "Kano",
            album: "Night Album",
            genre: "Synthwave",
            newArtworkBytes: ValidPngBytes);

        Assert.NotNull(updated);
        Assert.Equal(updated, updatedEventTrack);
        Assert.Equal("Edited Title", updated!.Title);
        Assert.Equal("Kano", updated.Artist);
        Assert.Equal("Night Album", updated.Album);
        Assert.Equal("Synthwave", updated.Genre);
        Assert.NotNull(updated.ArtworkKey);
        Assert.NotNull(artwork.GetImagePath(updated.ArtworkKey));

        // Verify TagLibSharp wrote tags back to local audio file.
        var fileMeta = await new TagLibMetadataReader().ReadAsync(songPath);
        Assert.Equal("Edited Title", fileMeta.Title);
        Assert.Equal("Kano", fileMeta.Artist);
        Assert.Equal("Night Album", fileMeta.Album);
        Assert.Equal("Synthwave", fileMeta.Genre);
        Assert.NotNull(fileMeta.ArtworkBytes);

        // Even if the stub reader returns stale metadata on rescan (e.g. non-writable file case),
        // library.json overrides preserve edits across rescans and store reloads.
        await store.RescanAsync();
        var afterRescan = Assert.Single(store.Tracks);
        Assert.Equal("Edited Title", afterRescan.Title);
        Assert.Equal("Kano", afterRescan.Artist);
        Assert.Equal("Night Album", afterRescan.Album);
        Assert.Equal("Synthwave", afterRescan.Genre);
        Assert.Equal(updated.ArtworkKey, afterRescan.ArtworkKey);

        var reloaded = new LibraryStore(stateDir, musicDir, stubReader, artwork);
        await reloaded.RescanAsync();
        var afterRestart = Assert.Single(reloaded.Tracks);
        Assert.Equal("Edited Title", afterRestart.Title);
        Assert.Equal("Kano", afterRestart.Artist);
        Assert.Equal("Night Album", afterRestart.Album);
        Assert.Equal("Synthwave", afterRestart.Genre);
        Assert.Equal(updated.ArtworkKey, afterRestart.ArtworkKey);
    }

    [Fact]
    public async Task SearchByFieldAndSearchQueueNamingWorkCorrectly()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        TestHelpers.TouchFile(musicDir, "t1.mp3");
        TestHelpers.TouchFile(musicDir, "t2.mp3");
        TestHelpers.TouchFile(musicDir, "t3.mp3");

        var reader = new StubMetadataReader();
        reader.ByFileName["t1.mp3"] = new TrackMetadata(Title: "Kano Song", Artist: "Other", Album: "Misc", Genre: "Rock", DurationSeconds: 60);
        reader.ByFileName["t2.mp3"] = new TrackMetadata(Title: "Midnight", Artist: "Kano", Album: "Blue", Genre: "Electronic", DurationSeconds: 60);
        reader.ByFileName["t3.mp3"] = new TrackMetadata(Title: "Sunrise", Artist: "Beta", Album: "Kano Best", Genre: "Electronic", DurationSeconds: 60);

        var lib = new LibraryStore(stateDir, musicDir, reader, new ArtworkCache(Path.Combine(stateDir, "Art")));
        await lib.RescanAsync();

        Assert.Equal(["Midnight"], lib.Search("Kano", SearchField.Artist).Select(t => t.Title));
        Assert.Equal(["Sunrise"], lib.Search("Kano", SearchField.Album).Select(t => t.Title));
        Assert.Equal(["Midnight", "Sunrise"], lib.Search("Electronic", SearchField.Genre).Select(t => t.Title).OrderBy(x => x));
        Assert.Equal(3, lib.Search("Kano", SearchField.Keyword).Count);

        Assert.Equal("Artist: Kano", QueueStore.FormatSearchQueueName(SearchField.Artist, "  Kano  "));
        Assert.Equal("Album: Kano Best", QueueStore.FormatSearchQueueName(SearchField.Album, "Kano Best"));
        Assert.Equal("Genre: Electronic", QueueStore.FormatSearchQueueName(SearchField.Genre, "Electronic"));
        Assert.Equal("Keyword: Kano", QueueStore.FormatSearchQueueName(SearchField.Keyword, "Kano"));

        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        using var player = new PlayerController(lib, queues, history, new FakeAudioEngine(), Path.Combine(stateDir, "settings.json"), enableTicker: false);

        Guid originalQueueId = queues.ActiveId;
        var allIds = lib.Tracks.Select(t => t.Id).ToList();
        player.Play(allIds, 0);

        var artistSearchIds = lib.Search("Electronic", SearchField.Genre).Select(t => t.Id).ToList();
        player.PlayInNewQueue(artistSearchIds, QueueStore.FormatSearchQueueName(SearchField.Genre, "Electronic"), startAt: 1);

        Assert.NotEqual(originalQueueId, queues.ActiveId);
        Assert.Equal("Genre: Electronic", queues.Active.Name);
        Assert.Equal(1, queues.Active.CurrentIndex);
        Assert.Equal(artistSearchIds[1], player.CurrentTrack?.Id);
        Assert.Equal(allIds, queues.Queues.First(q => q.Id == originalQueueId).TrackIds);

        player.PlayInNewQueue(allIds, QueueStore.FormatSearchQueueName(SearchField.Keyword, "Kano"), startAt: 2);
        Assert.Equal("Keyword: Kano", queues.Active.Name);
        Assert.Equal(2, queues.Active.CurrentIndex);
        Assert.Equal(3, queues.Queues.Count);
    }

    [Fact]
    public async Task QueueSortingModesPreserveCurrentIndexAndPersist()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        TestHelpers.TouchFile(musicDir, "a.mp3");
        TestHelpers.TouchFile(musicDir, "b.mp3");
        TestHelpers.TouchFile(musicDir, "c.mp3");

        var tBase = new DateTimeOffset(2025, 1, 1, 0, 0, 0, TimeSpan.Zero);
        File.SetLastWriteTimeUtc(Path.Combine(musicDir, "a.mp3"), tBase.UtcDateTime);
        File.SetLastWriteTimeUtc(Path.Combine(musicDir, "b.mp3"), tBase.AddDays(2).UtcDateTime);
        File.SetLastWriteTimeUtc(Path.Combine(musicDir, "c.mp3"), tBase.AddDays(1).UtcDateTime);

        var reader = new StubMetadataReader();
        reader.ByFileName["a.mp3"] = new TrackMetadata(Title: "Alpha", DurationSeconds: 60);
        reader.ByFileName["b.mp3"] = new TrackMetadata(Title: "Beta", DurationSeconds: 60);
        reader.ByFileName["c.mp3"] = new TrackMetadata(Title: "Gamma", DurationSeconds: 60);

        var lib = new LibraryStore(stateDir, musicDir, reader, new ArtworkCache(Path.Combine(stateDir, "Art")));
        await lib.RescanAsync();

        string idA = lib.Tracks.First(t => t.Title == "Alpha").Id;
        string idB = lib.Tracks.First(t => t.Title == "Beta").Id;
        string idC = lib.Tracks.First(t => t.Title == "Gamma").Id;

        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        history.RecordPlay(idB);
        history.RecordPlay(idB);
        history.RecordPlay(idB);
        history.RecordPlay(idC);

        using var player = new PlayerController(lib, queues, history, new FakeAudioEngine(), Path.Combine(stateDir, "settings.json"), enableTicker: false);
        player.Play([idA, idB, idC], startAt: 2); // Playing Gamma (idC)
        player.ToggleShuffle();

        player.SortQueue(TrackSortMode.MostPlayed);
        Assert.Equal([idB, idC, idA], queues.Active.TrackIds);
        Assert.Equal(idC, queues.Active.CurrentTrackId);
        Assert.Equal(1, queues.Active.CurrentIndex);
        Assert.Equal(TrackSortMode.MostPlayed, queues.Active.SortMode);
        Assert.Equal([0, 1, 2], queues.Active.ShuffleOrder.OrderBy(i => i).ToArray());

        player.SortQueue(TrackSortMode.LeastPlayed);
        Assert.Equal([idA, idC, idB], queues.Active.TrackIds);
        Assert.Equal(idC, queues.Active.CurrentTrackId);
        Assert.Equal(1, queues.Active.CurrentIndex);

        player.SortQueue(TrackSortMode.NameDescending);
        Assert.Equal([idC, idB, idA], queues.Active.TrackIds);
        Assert.Equal(idC, queues.Active.CurrentTrackId);
        Assert.Equal(0, queues.Active.CurrentIndex);

        player.SortQueue(TrackSortMode.DateModified);
        Assert.Equal([idB, idC, idA], queues.Active.TrackIds);
        Assert.Equal(idC, queues.Active.CurrentTrackId);
        Assert.Equal(1, queues.Active.CurrentIndex);

        player.SortQueue(TrackSortMode.NameAscending);
        Assert.Equal([idA, idB, idC], queues.Active.TrackIds);
        Assert.Equal(idC, queues.Active.CurrentTrackId);
        Assert.Equal(2, queues.Active.CurrentIndex);

        // Drag reorder switches back to Custom and preserves CurrentIndex.
        player.MoveInQueue(2, 0); // Gamma -> index 0
        Assert.Equal([idC, idA, idB], queues.Active.TrackIds);
        Assert.Equal(0, queues.Active.CurrentIndex);
        Assert.Equal(TrackSortMode.Custom, queues.Active.SortMode);

        var reloadedQueues = new QueueStore(stateDir);
        Assert.Equal([idC, idA, idB], reloadedQueues.Active.TrackIds);
        Assert.Equal(0, reloadedQueues.Active.CurrentIndex);
        Assert.Equal(TrackSortMode.Custom, reloadedQueues.Active.SortMode);
    }

    [Fact]
    public async Task FolderAndPlaylistSortAndReorderPersistAcrossReload()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        TestHelpers.TouchFile(musicDir, "Rock/a.mp3");
        TestHelpers.TouchFile(musicDir, "Rock/b.mp3");
        TestHelpers.TouchFile(musicDir, "Rock/c.mp3");

        var reader = new StubMetadataReader();
        reader.ByFileName["a.mp3"] = new TrackMetadata(Title: "Alpha", DurationSeconds: 30);
        reader.ByFileName["b.mp3"] = new TrackMetadata(Title: "Beta", DurationSeconds: 30);
        reader.ByFileName["c.mp3"] = new TrackMetadata(Title: "Gamma", DurationSeconds: 30);

        var artwork = new ArtworkCache(Path.Combine(stateDir, "Art"));
        var lib = new LibraryStore(stateDir, musicDir, reader, artwork);
        await lib.RescanAsync();

        var history = new PlayHistoryStore(stateDir);
        string idA = lib.Tracks.First(t => t.Title == "Alpha").Id;
        string idB = lib.Tracks.First(t => t.Title == "Beta").Id;
        string idC = lib.Tracks.First(t => t.Title == "Gamma").Id;
        history.RecordPlay(idB);
        history.RecordPlay(idB);
        history.RecordPlay(idC);

        // Playlist sort + drag reorder
        Guid pid = lib.CreatePlaylist("Favorites Mix");
        lib.AddToPlaylist([idA, idB, idC], pid);
        lib.SetPlaylistSortMode(pid, TrackSortMode.MostPlayed, history);
        Assert.Equal([idB, idC, idA], lib.GetPlaylistTracks(pid, history).Select(t => t.Id));

        lib.MovePlaylistTrack(pid, 2, 0, history); // Move Alpha to front -> [idA, idB, idC]
        Assert.Equal(TrackSortMode.Custom, lib.Playlists[0].SortMode);
        Assert.Equal([idA, idB, idC], lib.GetPlaylistTracks(pid, history).Select(t => t.Id));

        // Folder sort + drag reorder
        lib.SetFolderSortMode(LibraryRoot.AppFolderId, "Rock", TrackSortMode.NameDescending);
        Assert.Equal([idC, idB, idA], lib.GetFolderContents(LibraryRoot.AppFolderId, "Rock", history).Tracks.Select(t => t.Id));
        Assert.Equal([idC, idB, idA], lib.GetTracksUnder(LibraryRoot.AppFolderId, "Rock", history).Select(t => t.Id));

        lib.MoveFolderTrack(LibraryRoot.AppFolderId, "Rock", 2, 1, history); // [Gamma, Alpha, Beta]
        Assert.Equal(TrackSortMode.Custom, lib.GetFolderSortMode(LibraryRoot.AppFolderId, "Rock"));
        Assert.Equal([idC, idA, idB], lib.GetFolderContents(LibraryRoot.AppFolderId, "Rock", history).Tracks.Select(t => t.Id));
        Assert.Equal([idC, idA, idB], lib.GetTracksUnder(LibraryRoot.AppFolderId, "Rock", history).Select(t => t.Id));

        // Reload LibraryStore and verify both playlist and folder custom orders persist
        var reloaded = new LibraryStore(stateDir, musicDir, reader, artwork);
        Assert.Equal(TrackSortMode.Custom, reloaded.Playlists[0].SortMode);
        Assert.Equal([idA, idB, idC], reloaded.GetPlaylistTracks(pid, history).Select(t => t.Id));
        Assert.Equal(TrackSortMode.Custom, reloaded.GetFolderSortMode(LibraryRoot.AppFolderId, "Rock"));
        Assert.Equal([idC, idA, idB], reloaded.GetFolderContents(LibraryRoot.AppFolderId, "Rock", history).Tracks.Select(t => t.Id));
        Assert.Equal([idC, idA, idB], reloaded.GetTracksUnder(LibraryRoot.AppFolderId, "Rock", history).Select(t => t.Id));
    }

    [Fact]
    public async Task NewFolderCreatesOrReplacesExactNameQueueWithoutDisruptingUnrelatedPlayback()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        string parentDir = TestHelpers.MakeTempDir();
        string rockFolder = Path.Combine(parentDir, "rock_songs");
        Directory.CreateDirectory(rockFolder);
        TestHelpers.TouchFile(musicDir, "existing.mp3");
        TestHelpers.TouchFile(rockFolder, "r1.mp3");
        TestHelpers.TouchFile(rockFolder, "metal/m1.mp3");

        var httpHandler = new StubHttpHandler(req =>
        {
            if (req.RequestUri!.AbsoluteUri.Contains("/drive/folders/gdSynth1", StringComparison.Ordinal))
            {
                string html = """
                <html><head><title>Cloud Synth - Google Drive</title></head><body>
                <script>window['_DRIVE_ivd'] = '[["gd_s1",["gdSynth1"],"Synth Song.mp3","audio/mpeg"]]';</script>
                </body></html>
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(html, Encoding.UTF8, "text/html")
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var gd = new GoogleDriveClient(httpHandler);
        var registry = new MusicoWindows.Extensions.CloudProviderRegistry(gd);
        var lib = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Art")),
            googleDriveClient: gd,
            providerRegistry: registry,
            artworkHandler: httpHandler);
        await lib.RescanAsync();

        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        using var player = new PlayerController(lib, queues, history, new FakeAudioEngine(), Path.Combine(stateDir, "settings.json"), enableTicker: false);

        string existingId = lib.Tracks.First(t => t.RelativePath == "existing.mp3").Id;
        player.Play([existingId], 0);
        Guid initialActiveId = queues.ActiveId;
        Assert.True(player.IsPlaying);

        // Pre-create a same-name queue "rock_songs" to verify replacement rather than duplication.
        Guid preExistingRockQueueId = queues.AddQueue("rock_songs");
        queues.Update(preExistingRockQueueId, q => q.Replace([existingId], 0));

        // 1. Adding local root uses exact folder name "rock_songs", replaces same-name queue, keeps active playback intact.
        await lib.AddRootAsync(rockFolder);

        var rockQueues = queues.Queues.Where(q => q.Name == "rock_songs").ToList();
        Assert.Single(rockQueues);
        Assert.Equal(2, rockQueues[0].TrackIds.Count);
        Assert.Equal(initialActiveId, queues.ActiveId);
        Assert.Equal(existingId, player.CurrentTrack?.Id);
        Assert.True(player.IsPlaying);

        // 2. Adding cloud folder creates queue named "Cloud Synth" without disrupting playback.
        await lib.AddCloudRootAsync("https://drive.google.com/drive/folders/gdSynth1");
        var cloudQueue = Assert.Single(queues.Queues, q => q.Name == "Cloud Synth");
        Assert.Single(cloudQueue.TrackIds);
        Assert.Equal(initialActiveId, queues.ActiveId);
        Assert.True(player.IsPlaying);

        // 3. Creating subfolder uses exact leaf folder name "jazz_club" (not parent).
        await lib.CreateSubfolderAsync(LibraryRoot.AppFolderId, "SubParent", "jazz_club");
        Assert.Single(queues.Queues, q => q.Name == "jazz_club");
        Assert.DoesNotContain(queues.Queues, q => q.Name == "SubParent");
        Assert.Equal(initialActiveId, queues.ActiveId);
        Assert.True(player.IsPlaying);

        // 4. Playing subfolder uses exact leaf name "metal" and replaces any same-name queue.
        var rockRoot = lib.Roots.First(r => r.Name == "rock_songs");
        var metalIds = lib.GetTracksUnder(rockRoot.Id, "metal", history).Select(t => t.Id).ToList();
        player.PlayInNamedQueue("metal", metalIds);
        Assert.Equal("metal", queues.Active.Name);
        Assert.Equal(metalIds, queues.Active.TrackIds);

        // Replacing the currently active "metal" queue with empty tracks keeps playback state valid.
        await lib.CreateSubfolderAsync(LibraryRoot.AppFolderId, string.Empty, "metal");
        var replacedMetal = Assert.Single(queues.Queues, q => q.Name == "metal");
        Assert.Empty(replacedMetal.TrackIds);
        Assert.Null(player.CurrentTrack);
        Assert.False(player.IsPlaying);
    }
}
