using System.Net;
using System.Net.Http;
using System.Text;
using MusicoWindows.Audio;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class CloudMergeAndExtensionsTests
{
    private sealed class StubHttpHandler(
        Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> responder) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return responder(request, cancellationToken);
        }
    }

    private sealed class FakeAudioEngine : IAudioEngine
    {
        public float Volume { get; set; } = 1.0f;
        public double CurrentTimeSeconds { get; private set; }
        public double DurationSeconds { get; private set; }
        public bool IsPlaying { get; private set; }
        public event Action? TrackEnded { add { } remove { } }
        public void Load(string path) { DurationSeconds = 180; CurrentTimeSeconds = 0; IsPlaying = false; }
        public void Play() => IsPlaying = true;
        public void Pause() => IsPlaying = false;
        public void Seek(double seconds) => CurrentTimeSeconds = Math.Clamp(seconds, 0, DurationSeconds);
        public void Unload() { IsPlaying = false; DurationSeconds = 0; CurrentTimeSeconds = 0; }
        public void ApplyEq(EqSettings settings) { }
        public void Dispose() => Unload();
    }

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

    private static (CloudProviderRegistry Registry, ExtensionStore ExtStore) CreateYouTubeExtensionSetup(
        string stateDir,
        HttpMessageHandler handler,
        bool failStream = false)
    {
        var registry = new CloudProviderRegistry(new GoogleDriveClient(handler));
        var extStore = new ExtensionStore(stateDir, handler);
        extStore.BindRegistry(registry);

        string streamReturn = failStream
            ? "throw new Error('stream failed');"
            : "return { url: 'https://cdn.example/' + req.remoteTrackId + '.mp3', fileExtension: 'mp3' };";

        string script = $$"""
            var manifest = {
              apiVersion: 1,
              id: 'youtube-search',
              name: 'YouTube',
              version: '1.0.0',
              capabilities: ['playlist', 'stream', 'cookie', 'proxy'],
              urlPatterns: ['^(https?://)?(www\\.|m\\.|music\\.)?youtube\\.com/'],
              permissions: { hosts: ['music.youtube.com', 'cdn.example'] }
            };
            function canHandle(input) {
              return { matched: true, normalizedUrl: input.extractedUrl, remoteId: 'PL_MERGE_1', kind: 'playlist' };
            }
            async function fetchCollection() {
              return {
                remoteId: 'PL_MERGE_1',
                title: 'Cloud Merge Mix',
                tracks: [
                  { remoteTrackId: 'yt_1', title: 'Alpha Track', artist: 'Artist One', album: 'Mix Album', trackNumber: 1, durationSec: 120 },
                  { remoteTrackId: 'yt_2', title: 'Beta Track', artist: 'Artist Two', album: 'Mix Album', trackNumber: 2, durationSec: 150 }
                ]
              };
            }
            async function resolveStream(ctx, req) {
              {{streamReturn}}
            }
            module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection, resolveStream: resolveStream };
            """;

        var inst = extStore.StageImportedPackage(ExtensionPackageLoader.LoadSingleFileScript(script));
        extStore.ApproveAndEnable(inst.Id);
        return (registry, extStore);
    }

    [Fact]
    public async Task SaveCloudPlaylistToStandaloneFolderMergesCloudRootAndMigratesAllUserState()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        string otherLocalDir = TestHelpers.MakeTempDir();
        string exportParentDir = TestHelpers.MakeTempDir();
        TestHelpers.TouchFile(otherLocalDir, "other.mp3");

        byte[] mp3Bytes = MakeMinimalMp3Bytes();
        var handler = new StubHttpHandler((req, _) =>
        {
            if (req.RequestUri!.AbsoluteUri.StartsWith("https://cdn.example/", StringComparison.Ordinal))
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(mp3Bytes)
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        var (registry, extStore) = CreateYouTubeExtensionSetup(stateDir, handler);
        var cache = new CloudAudioCache(Path.Combine(stateDir, "CloudCache"), handler: handler);
        var library = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Artwork")),
            cloudCache: cache,
            providerRegistry: registry);

        var cloudRoot = await library.AddCloudRootAsync(
            "https://music.youtube.com/playlist?list=PL_MERGE_1",
            cookie: "SID=abc",
            proxyUrl: "7890");
        await library.AddRootAsync(otherLocalDir);

        // Roots order: [0]=AppFolder, [1]=cloudRoot, [2]=otherLocalDir
        Assert.Equal(3, library.Roots.Count);
        Assert.Equal(cloudRoot.Id, library.Roots[1].Id);

        var cloudTracks = library.Tracks
            .Where(t => t.RootId == cloudRoot.Id)
            .OrderBy(t => t.TrackNumber)
            .ToList();
        Assert.Equal(2, cloudTracks.Count);
        var c1 = cloudTracks[0];
        var c2 = cloudTracks[1];

        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        using var player = new PlayerController(
            library, queues, history, new FakeAudioEngine(),
            Path.Combine(stateDir, "settings.json"),
            enableTicker: false, cloudCache: cache, providerRegistry: registry, extensions: extStore);

        // User state before export: Playlist, Favourite, Custom folder order, MetadataOverride, Queue, PlayHistory
        var playlistId = library.CreatePlaylist("Roadtrip");
        library.AddToPlaylist([c1.Id, c2.Id], playlistId);
        library.ToggleFavourite(c1.Id);
        library.MoveFolderTrack(cloudRoot.Id, string.Empty, 1, 0, history); // custom order: [c2, c1]
        await library.UpdateTrackMetadataAsync(c1.Id, "Alpha Custom", "Artist One", "Mix Album", "Synthpop");

        var customQueueId = queues.AddQueue("Saved Cloud Queue", [c1.Id, c2.Id], startAt: 0, activate: true);
        player.Restore();
        history.RecordPlay(c1, new DateTimeOffset(2026, 1, 10, 0, 0, 0, TimeSpan.Zero));
        history.RecordPlay(c1, new DateTimeOffset(2026, 1, 11, 0, 0, 0, TimeSpan.Zero));
        history.RecordPlay(c2, new DateTimeOffset(2026, 1, 12, 0, 0, 0, TimeSpan.Zero));

        (Guid OldId, Guid TargetRootId, string TargetRelPath)? mergedEvent = null;
        library.CloudRootMerged += (oldId, newRootId, relPath) => mergedEvent = (oldId, newRootId, relPath);

        var exporter = new CloudExporter(cache, registry, library.Artwork, extStore);
        var downloads = new DownloadManager(exporter, library);

        var doneTcs = new TaskCompletionSource<DownloadJob>(TaskCreationOptions.RunContinuationsAsynchronously);
        downloads.Changed += () =>
        {
            var j = downloads.Jobs.FirstOrDefault(x => x.Kind == DownloadJobKind.PlaylistExport && x.IsFinished);
            if (j is not null) doneTcs.TrySetResult(j);
        };

        downloads.StartPlaylistExport(cloudRoot, [c1, c2], exportParentDir, "Cloud Merge Mix", addToLibrary: true, isWholeRootExport: true);
        var job = await doneTcs.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(DownloadJobStatus.Completed, job.Status);

        // Strictly 1 folder for the collection, placed at cloudRoot's original index [1]
        Assert.Equal(3, library.Roots.Count);
        Assert.DoesNotContain(library.Roots, r => r.Id == cloudRoot.Id);
        Assert.DoesNotContain(library.Tracks, t => t.RootId == cloudRoot.Id);

        var mergedRoot = library.Roots[1];
        Assert.Equal(RootKind.Picked, mergedRoot.Kind);
        Assert.True(mergedRoot.IsCloudTrackedLocal);
        Assert.Equal(Path.GetFullPath(Path.Combine(exportParentDir, "Cloud Merge Mix")), mergedRoot.Path);
        Assert.Equal("https://music.youtube.com/playlist?list=PL_MERGE_1", mergedRoot.SourceUrl);
        Assert.Equal("ext.youtube-search", mergedRoot.LinkedProviderId);
        Assert.Equal("SID=abc", mergedRoot.Cookie);
        Assert.Equal("http://127.0.0.1:7890", mergedRoot.ProxyUrl);

        Assert.NotNull(mergedEvent);
        Assert.Equal(cloudRoot.Id, mergedEvent!.Value.OldId);
        Assert.Equal(mergedRoot.Id, mergedEvent.Value.TargetRootId);
        Assert.Equal(string.Empty, mergedEvent.Value.TargetRelPath);
        Assert.Equal(mergedRoot.Id, job.TargetRootId);
        Assert.Equal(string.Empty, job.TargetRelativePath);

        var localTracks = library.GetTracksUnder(mergedRoot.Id, string.Empty).ToList();
        Assert.Equal(2, localTracks.Count);
        Assert.All(localTracks, t => Assert.False(t.IsCloud));
        var l1 = localTracks.Single(t => t.FileName.StartsWith("Alpha", StringComparison.Ordinal));
        var l2 = localTracks.Single(t => t.FileName.StartsWith("Beta", StringComparison.Ordinal));

        // All user state migrated cleanly
        Assert.Equal([l1.Id, l2.Id], library.GetPlaylistTracks(playlistId).Select(t => t.Id));
        Assert.True(library.IsFavourite(l1.Id));
        Assert.False(library.IsFavourite(c1.Id));
        Assert.Equal(TrackSortMode.Custom, library.GetFolderSortMode(mergedRoot.Id, string.Empty));
        Assert.Equal([l2.Id, l1.Id], library.GetFolderContents(mergedRoot.Id, string.Empty, history).Tracks.Select(t => t.Id));
        Assert.Equal("Alpha Custom", l1.Title);
        Assert.Equal("Synthpop", l1.Genre);

        var migratedQueue = queues.GetById(customQueueId)!;
        Assert.Equal([l1.Id, l2.Id], migratedQueue.TrackIds);
        Assert.Equal(l1.Id, player.CurrentTrack?.Id);

        Assert.Equal(2, history.GetCount(l1.Id));
        Assert.Equal(1, history.GetCount(l2.Id));
        Assert.Equal(0, history.GetCount(c1.Id));
        Assert.Equal(0, history.GetCount(c2.Id));
    }

    [Fact]
    public async Task SaveCloudPlaylistInsideExistingLocalRootMergesIntoTrackedSubfolderWithOneFolderEntry()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();

        byte[] mp3Bytes = MakeMinimalMp3Bytes();
        var handler = new StubHttpHandler((req, _) =>
        {
            if (req.RequestUri!.AbsoluteUri.StartsWith("https://cdn.example/", StringComparison.Ordinal))
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(mp3Bytes)
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        var (registry, extStore) = CreateYouTubeExtensionSetup(stateDir, handler);
        var cache = new CloudAudioCache(Path.Combine(stateDir, "CloudCache"), handler: handler);
        var library = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Artwork")),
            cloudCache: cache,
            providerRegistry: registry);

        var cloudRoot = await library.AddCloudRootAsync("https://music.youtube.com/playlist?list=PL_MERGE_1");
        var cloudTracks = library.Tracks.Where(t => t.RootId == cloudRoot.Id).OrderBy(t => t.TrackNumber).ToList();
        var c1 = cloudTracks[0];
        var c2 = cloudTracks[1];

        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        using var player = new PlayerController(
            library, queues, history, new FakeAudioEngine(),
            Path.Combine(stateDir, "settings.json"),
            enableTicker: false, cloudCache: cache, providerRegistry: registry, extensions: extStore);

        var plId = library.CreatePlaylist("Subfolder Target");
        library.AddToPlaylist([c1.Id, c2.Id], plId);
        library.ToggleFavourite(c2.Id);
        library.MoveFolderTrack(cloudRoot.Id, string.Empty, 1, 0, history); // [c2, c1]
        history.RecordPlay(c2);

        (Guid OldId, Guid TargetRootId, string TargetRelPath)? mergedEvent = null;
        library.CloudRootMerged += (oldId, newRootId, relPath) => mergedEvent = (oldId, newRootId, relPath);

        var exporter = new CloudExporter(cache, registry, library.Artwork, extStore);
        var downloads = new DownloadManager(exporter, library);

        var doneTcs = new TaskCompletionSource<DownloadJob>(TaskCreationOptions.RunContinuationsAsynchronously);
        downloads.Changed += () =>
        {
            var j = downloads.Jobs.FirstOrDefault(x => x.Kind == DownloadJobKind.PlaylistExport && x.IsFinished);
            if (j is not null) doneTcs.TrySetResult(j);
        };

        // Export into existing AppFolder (`musicDir`), even with addToLibrary: false because destination is already indexed
        downloads.StartPlaylistExport(cloudRoot, [c1, c2], musicDir, "Saved SubMix", addToLibrary: false, isWholeRootExport: true);
        var job = await doneTcs.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(DownloadJobStatus.Completed, job.Status);

        // Strictly 1 root (AppFolder); cloudRoot removed and no duplicate Picked root added
        var soleRoot = Assert.Single(library.Roots);
        Assert.Equal(LibraryRoot.AppFolderId, soleRoot.Id);
        Assert.DoesNotContain(library.Tracks, t => t.RootId == cloudRoot.Id);

        var subTracking = library.GetCloudTracking(LibraryRoot.AppFolderId, "Saved SubMix");
        Assert.NotNull(subTracking);
        Assert.Equal("https://music.youtube.com/playlist?list=PL_MERGE_1", subTracking!.SourceUrl);

        Assert.NotNull(mergedEvent);
        Assert.Equal((cloudRoot.Id, LibraryRoot.AppFolderId, "Saved SubMix"), mergedEvent!.Value);
        Assert.Equal(LibraryRoot.AppFolderId, job.TargetRootId);
        Assert.Equal("Saved SubMix", job.TargetRelativePath);

        var localTracks = library.GetTracksUnder(LibraryRoot.AppFolderId, "Saved SubMix").ToList();
        Assert.Equal(2, localTracks.Count);
        var l1 = localTracks.Single(t => t.FileName.StartsWith("Alpha", StringComparison.Ordinal));
        var l2 = localTracks.Single(t => t.FileName.StartsWith("Beta", StringComparison.Ordinal));

        Assert.Equal([l1.Id, l2.Id], library.GetPlaylistTracks(plId).Select(t => t.Id));
        Assert.True(library.IsFavourite(l2.Id));
        Assert.Equal(1, history.GetCount(l2.Id));
        Assert.Equal([l2.Id, l1.Id], library.GetFolderContents(LibraryRoot.AppFolderId, "Saved SubMix", history).Tracks.Select(t => t.Id));
    }

    [Fact]
    public async Task CloudMergeGuardsKeepCloudRootOnZeroSavedUnindexedNoAddOrPartialSubfolderExport()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        string unindexedParent = TestHelpers.MakeTempDir();
        string partialParent = TestHelpers.MakeTempDir();

        byte[] mp3Bytes = MakeMinimalMp3Bytes();
        var handler = new StubHttpHandler((req, _) =>
        {
            if (req.RequestUri!.AbsoluteUri.StartsWith("https://cdn.example/", StringComparison.Ordinal))
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(mp3Bytes)
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        // 1. SavedCount == 0 -> keeps cloudRoot
        {
            string failStateDir = TestHelpers.MakeTempDir();
            var (failReg, failExt) = CreateYouTubeExtensionSetup(failStateDir, handler, failStream: true);
            var failCache = new CloudAudioCache(Path.Combine(failStateDir, "CloudCache"), handler: handler);
            var failLib = new LibraryStore(failStateDir, TestHelpers.MakeTempDir(), new StubMetadataReader(), new ArtworkCache(Path.Combine(failStateDir, "Art")), cloudCache: failCache, providerRegistry: failReg);
            var root = await failLib.AddCloudRootAsync("https://music.youtube.com/playlist?list=PL_MERGE_1");
            var tracks = failLib.Tracks.Where(t => t.RootId == root.Id).ToList();

            var dl = new DownloadManager(new CloudExporter(failCache, failReg, failLib.Artwork, failExt), failLib);
            var tcs = new TaskCompletionSource<DownloadJob>(TaskCreationOptions.RunContinuationsAsynchronously);
            dl.Changed += () =>
            {
                var j = dl.Jobs.FirstOrDefault(x => x.IsFinished);
                if (j is not null) tcs.TrySetResult(j);
            };
            dl.StartPlaylistExport(root, tracks, TestHelpers.MakeTempDir(), "Fail Export", addToLibrary: true, isWholeRootExport: true);
            var job = await tcs.Task.WaitAsync(TimeSpan.FromSeconds(5));
            Assert.Equal(0, job.CompletedItems);
            Assert.NotNull(failLib.GetRoot(root.Id));
        }

        // 2. addToLibrary == false on unindexed target -> keeps cloudRoot
        var (registry, extStore) = CreateYouTubeExtensionSetup(stateDir, handler);
        var cache = new CloudAudioCache(Path.Combine(stateDir, "CloudCache"), handler: handler);
        var library = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Artwork")),
            cloudCache: cache,
            providerRegistry: registry);

        var cloudRoot = await library.AddCloudRootAsync("https://music.youtube.com/playlist?list=PL_MERGE_1");
        var cloudTracks = library.Tracks.Where(t => t.RootId == cloudRoot.Id).ToList();
        var downloads = new DownloadManager(new CloudExporter(cache, registry, library.Artwork, extStore), library);

        var unindexedDone = new TaskCompletionSource<DownloadJob>(TaskCreationOptions.RunContinuationsAsynchronously);
        downloads.Changed += () =>
        {
            var j = downloads.Jobs.FirstOrDefault(x => x.Title == "Unindexed Mix" && x.IsFinished);
            if (j is not null) unindexedDone.TrySetResult(j);
        };
        downloads.StartPlaylistExport(cloudRoot, cloudTracks, unindexedParent, "Unindexed Mix", addToLibrary: false, isWholeRootExport: true);
        var j1 = await unindexedDone.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(2, j1.CompletedItems);
        Assert.NotNull(library.GetRoot(cloudRoot.Id));
        Assert.False(library.IsFolderIndexed(Path.Combine(unindexedParent, "Unindexed Mix")));

        // 3. isWholeRootExport == false (e.g. subfolder save under Google Drive) -> keeps cloudRoot AND indexes local subfolder
        var partialDone = new TaskCompletionSource<DownloadJob>(TaskCreationOptions.RunContinuationsAsynchronously);
        downloads.Changed += () =>
        {
            var j = downloads.Jobs.FirstOrDefault(x => x.Title == "Subfolder Save" && x.IsFinished);
            if (j is not null) partialDone.TrySetResult(j);
        };
        downloads.StartPlaylistExport(cloudRoot, [cloudTracks[0]], partialParent, "Subfolder Save", addToLibrary: true, isWholeRootExport: false);
        var j2 = await partialDone.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(1, j2.CompletedItems);
        Assert.NotNull(library.GetRoot(cloudRoot.Id));
        Assert.True(library.IsFolderIndexed(Path.Combine(partialParent, "Subfolder Save")));
    }
}
