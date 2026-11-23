using System.Net;
using System.Net.Http;
using System.Text;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class V1112FeaturesTests
{
    private sealed class AsyncStubHttpHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> _responder;

        public AsyncStubHttpHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> responder)
        {
            _responder = responder;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return _responder(request, cancellationToken);
        }
    }

    private sealed class StubHttpHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _responder;

        public StubHttpHandler(Func<HttpRequestMessage, HttpResponseMessage> responder)
        {
            _responder = responder;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult(_responder(request));
        }
    }

    private static LibraryStore MakeLibrary(
        string stateDir,
        string musicDir,
        YouTubeClient? youtube = null,
        GoogleDriveClient? googleDrive = null,
        HttpMessageHandler? handler = null) =>
        new(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Artwork")),
            youtubeClient: youtube,
            googleDriveClient: googleDrive,
            artworkHandler: handler);

    [Fact]
    public async Task LibraryStoreProgressiveGoogleDriveLoadsRootFirstUnionsSubfoldersPrioritizesRemapsLegacyIdsAndCancelsOnRemove()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        var subAGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var subBEntered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        bool moveSongIntoNestedFolder = false;

        var handler = new AsyncStubHttpHandler(async (req, ct) =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/drive/folders/gdProgLib", StringComparison.Ordinal))
            {
                string rootFiles = moveSongIntoNestedFolder
                    ? """["gd_suba",["gdProgLib"],"SubA","application/vnd.google-apps.folder"],["gd_subb",["gdProgLib"],"SubB","application/vnd.google-apps.folder"]"""
                    : """["gd_suba",["gdProgLib"],"SubA","application/vnd.google-apps.folder"],["gd_subb",["gdProgLib"],"SubB","application/vnd.google-apps.folder"],["gd_root_song",["gdProgLib"],"Theme.mp3","audio/mpeg"]""";
                string html = $"<html><head><title>Soundtrack Drive - Google Drive</title></head><body><script>window['_DRIVE_ivd'] = '[[{rootFiles}]]';</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }

            if (url.Contains("/drive/folders/gd_suba", StringComparison.Ordinal))
            {
                await subAGate.Task.WaitAsync(ct).ConfigureAwait(false);
                string songRow = moveSongIntoNestedFolder
                    ? """["gd_root_song",["gd_suba"],"Theme.mp3","audio/mpeg"],["gd_a_song",["gd_suba"],"Aria.mp3","audio/mpeg"]"""
                    : """["gd_a_song",["gd_suba"],"Aria.mp3","audio/mpeg"]""";
                string html = $"<html><body><script>window['_DRIVE_ivd'] = '[[{songRow}]]';</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }

            if (url.Contains("/drive/folders/gd_subb", StringComparison.Ordinal))
            {
                subBEntered.TrySetResult();
                string html = """<html><body><script>window['_DRIVE_ivd'] = '[[]]';</script></body></html>""";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var store = MakeLibrary(stateDir, musicDir, googleDrive: new GoogleDriveClient(handler), handler: handler);
        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        store.OnTracksMigrated = migrations =>
        {
            queues.MigrateTracks(migrations);
            history.MigrateTracks(migrations);
        };

        // AddCloudRootAsync returns immediately after root folder discovery without blocking on SubA.
        var root = await store.AddCloudRootAsync("https://drive.google.com/drive/folders/gdProgLib");
        Assert.Equal("Soundtrack Drive", root.Name);

        var syncDuringLoad = store.GetCloudSyncProgress(root.Id);
        Assert.NotNull(syncDuringLoad);
        Assert.False(syncDuringLoad!.IsCompleted);

        // Known unloaded subfolders (SubA, SubB) already appear in GetFolderContents while background sync is running.
        var (subfolders, directTracks) = store.GetFolderContents(root.Id, string.Empty);
        Assert.Contains("SubA", subfolders);
        Assert.Contains("SubB", subfolders);
        var initialThemeTrack = Assert.Single(directTracks);
        Assert.Equal("gd_root_song", initialThemeTrack.RemoteTrackId);

        // Record playlist, favourite, queue, and play history on the initial track ID before moving it on rescan.
        Guid plId = store.CreatePlaylist("OST Picks");
        store.AddToPlaylist([initialThemeTrack.Id], plId);
        store.ToggleFavourite(initialThemeTrack.Id);
        queues.CreateOrReplaceQueue("Active OST", [initialThemeTrack.Id], activate: true);
        history.RecordPlay(initialThemeTrack.Id);

        // Prioritize SubB while SubA is blocked, then release SubA and await completion.
        store.PrioritizeCloudFolder(root.Id, "SubB");
        await subBEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        subAGate.TrySetResult();
        await store.WaitForCloudSyncAsync(root.Id).WaitAsync(TimeSpan.FromSeconds(5));

        var doneSync = store.GetCloudSyncProgress(root.Id);
        Assert.NotNull(doneSync);
        Assert.True(doneSync!.IsCompleted);
        Assert.Contains(store.Tracks, t => t.RelativePath == "SubA/Aria.mp3");

        // Rescan where gd_root_song moved into SubA/: verify RemoteTrackId migration preserves playlist, favourite, queue, history.
        moveSongIntoNestedFolder = true;
        await store.RescanAsync();
        await store.WaitForCloudSyncAsync(root.Id).WaitAsync(TimeSpan.FromSeconds(5));

        var migratedTrack = store.Tracks.First(t => t.RemoteTrackId == "gd_root_song");
        Assert.Equal("SubA/Theme.mp3", migratedTrack.RelativePath);
        Assert.NotEqual(initialThemeTrack.Id, migratedTrack.Id);
        Assert.Contains(migratedTrack.Id, store.Playlists.First(p => p.Id == plId).TrackIds);
        Assert.True(store.IsFavourite(migratedTrack.Id));
        Assert.Contains(migratedTrack.Id, queues.Active.TrackIds);
        Assert.Equal(1, history.GetCount(migratedTrack.Id));

        // Removing a cloud root cancels an in-flight background Drive sync cleanly.
        var stalledGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var stalledEntered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var cancelHandler = new AsyncStubHttpHandler(async (req, ct) =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/drive/folders/gdCancelRoot", StringComparison.Ordinal))
            {
                string ivd = """[[["gd_slow_sub",["gdCancelRoot"],"SlowSub","application/vnd.google-apps.folder"],["gd_c1",["gdCancelRoot"],"One.mp3","audio/mpeg"]]]""";
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent($"<html><head><title>Cancel Drive - Google Drive</title></head><body><script>window['_DRIVE_ivd'] = '{ivd}';</script></body></html>", Encoding.UTF8, "text/html")
                };
            }
            if (url.Contains("/drive/folders/gd_slow_sub", StringComparison.Ordinal))
            {
                stalledEntered.TrySetResult();
                await stalledGate.Task.WaitAsync(ct).ConfigureAwait(false);
                string ivd = """[[["gd_c2",["gd_slow_sub"],"Two.mp3","audio/mpeg"]]]""";
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent($"<html><body><script>window['_DRIVE_ivd'] = '{ivd}';</script></body></html>", Encoding.UTF8, "text/html")
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var store2 = MakeLibrary(TestHelpers.MakeTempDir(), TestHelpers.MakeTempDir(), googleDrive: new GoogleDriveClient(cancelHandler), handler: cancelHandler);
        var cancelRoot = await store2.AddCloudRootAsync("https://drive.google.com/drive/folders/gdCancelRoot");
        await stalledEntered.Task.WaitAsync(TimeSpan.FromSeconds(5));
        store2.RemoveRoot(cancelRoot.Id);
        await store2.WaitForCloudSyncAsync(cancelRoot.Id).WaitAsync(TimeSpan.FromSeconds(5));
        Assert.DoesNotContain(store2.Roots, r => r.Id == cancelRoot.Id);
        Assert.DoesNotContain(store2.Tracks, t => t.RootId == cancelRoot.Id);
    }

    [Fact]
    public async Task LibraryStorePreventsOverlappingRootsAndWrapsExistingChildRootsWithTrackingMigration()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        string parentDir = TestHelpers.MakeTempDir();
        string childDir = Path.Combine(parentDir, "Game OST");
        string grandChildDir = Path.Combine(childDir, "Disc 2");
        Directory.CreateDirectory(grandChildDir);
        TestHelpers.TouchFile(childDir, "opening.mp3");
        TestHelpers.TouchFile(grandChildDir, "finale.mp3");

        CloudExporter.SaveManifest(childDir, new CloudFolderManifest(
            SourceUrl: "https://www.youtube.com/playlist?list=PLCHILD001",
            RemoteId: "PLCHILD001",
            CloudKind: RootKind.CloudYouTube.ToString(),
            LastSyncedUtc: DateTimeOffset.UtcNow,
            Tracks: new Dictionary<string, string>(StringComparer.Ordinal)));

        var store = MakeLibrary(stateDir, musicDir);
        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        store.OnTracksMigrated = m =>
        {
            queues.MigrateTracks(m);
            history.MigrateTracks(m);
        };

        await store.AddRootAsync(childDir);
        var childRoot = store.Roots.Single(r => r.Kind == RootKind.Picked);
        Assert.True(childRoot.IsCloudTrackedLocal);
        var oldOpeningId = store.Tracks.Single(t => t.Title == "opening" || t.RelativePath == "opening.mp3").Id;
        Guid plId = store.CreatePlaylist("Child Playlist");
        store.AddToPlaylist([oldOpeningId], plId);
        queues.CreateOrReplaceQueue("Child Q", [oldOpeningId], activate: true);
        history.RecordPlay(oldOpeningId);

        // Adding a child folder inside an already indexed root is rejected.
        var childEx = await Assert.ThrowsAsync<InvalidOperationException>(() => store.AddRootAsync(grandChildDir));
        Assert.Contains("already inside an indexed", childEx.Message, StringComparison.OrdinalIgnoreCase);

        // Adding the parent folder wraps the existing child root cleanly, migrating cloud tracking and track IDs.
        await store.AddRootAsync(parentDir);

        var pickedRoots = store.Roots.Where(r => r.Kind == RootKind.Picked).ToList();
        var parentRoot = Assert.Single(pickedRoots);
        Assert.Equal(parentDir, parentRoot.Path);
        Assert.DoesNotContain(store.Roots, r => r.Id == childRoot.Id);

        var migratedTracking = store.GetCloudTracking(parentRoot.Id, "Game OST");
        Assert.NotNull(migratedTracking);
        Assert.Equal("https://www.youtube.com/playlist?list=PLCHILD001", migratedTracking!.SourceUrl);
        Assert.Equal(RootKind.CloudYouTube, migratedTracking.CloudKind);

        var newOpeningTrack = store.Tracks.Single(t => t.RootId == parentRoot.Id && t.RelativePath == "Game OST/opening.mp3");
        Assert.Contains(newOpeningTrack.Id, store.Playlists.First(p => p.Id == plId).TrackIds);
        Assert.Contains(newOpeningTrack.Id, queues.Active.TrackIds);
        Assert.Equal(1, history.GetCount(newOpeningTrack.Id));
    }

    [Fact]
    public async Task LibraryStoreRegisterExportedCloudFolderUnExcludesDiscoversSubfolderManifestsAndEditsTrackingUrl()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        string parentDir = TestHelpers.MakeTempDir();
        string exportedSubDir = Path.Combine(parentDir, "BiliMix");
        string scannedSubDir = Path.Combine(parentDir, "DriveAuto");
        Directory.CreateDirectory(exportedSubDir);
        Directory.CreateDirectory(scannedSubDir);
        TestHelpers.TouchFile(exportedSubDir, "01.mp3");
        TestHelpers.TouchFile(scannedSubDir, "02.mp3");

        var store = MakeLibrary(stateDir, musicDir);
        await store.AddRootAsync(parentDir);
        var parentRoot = store.Roots.Single(r => r.Kind == RootKind.Picked);

        // Simulate user having excluded BiliMix earlier; RegisterExportedCloudFolderAsync un-excludes it and adds zero duplicate roots.
        store.RemoveSubfolder(parentRoot.Id, "BiliMix");
        Assert.Contains("BiliMix", store.GetRoot(parentRoot.Id)!.ExcludedPaths!);

        CloudExporter.SaveManifest(exportedSubDir, new CloudFolderManifest(
            SourceUrl: "https://www.bilibili.com/video/BV1xx411c7mD",
            RemoteId: "BV1xx411c7mD",
            CloudKind: RootKind.CloudBilibili.ToString(),
            LastSyncedUtc: DateTimeOffset.UtcNow,
            Tracks: new Dictionary<string, string>(StringComparer.Ordinal)));

        var cloudSourceRoot = new LibraryRoot(
            Guid.NewGuid(),
            "Bili Source",
            RootKind.CloudBilibili,
            "https://www.bilibili.com/video/BV1xx411c7mD",
            SourceUrl: "https://www.bilibili.com/video/BV1xx411c7mD",
            RemoteId: "BV1xx411c7mD",
            Cookie: "SESSDATA=abc");

        await store.RegisterExportedCloudFolderAsync(exportedSubDir, cloudSourceRoot);

        Assert.Single(store.Roots, r => r.Kind == RootKind.Picked);
        var updatedParent = store.GetRoot(parentRoot.Id)!;
        Assert.False(updatedParent.IsExcluded("BiliMix"));
        Assert.Contains(store.Tracks, t => t.RootId == parentRoot.Id && t.RelativePath == "BiliMix/01.mp3");

        var subTracking = store.GetCloudTracking(parentRoot.Id, "BiliMix");
        Assert.NotNull(subTracking);
        Assert.Equal("https://www.bilibili.com/video/BV1xx411c7mD", subTracking!.SourceUrl);
        Assert.Equal(RootKind.CloudBilibili, subTracking.CloudKind);

        // FolderScanner + ScanOnceAsync discovers .musico-cloud.json inside DriveAuto subfolder and prunes stale ones when removed.
        CloudExporter.SaveManifest(scannedSubDir, new CloudFolderManifest(
            SourceUrl: "https://drive.google.com/drive/folders/gdSubManifest1",
            RemoteId: "gdSubManifest1",
            CloudKind: RootKind.CloudGoogleDrive.ToString(),
            LastSyncedUtc: DateTimeOffset.UtcNow,
            Tracks: new Dictionary<string, string>(StringComparer.Ordinal)));

        await store.RescanAsync();
        var driveTracking = store.GetCloudTracking(parentRoot.Id, "DriveAuto");
        Assert.NotNull(driveTracking);
        Assert.Equal(RootKind.CloudGoogleDrive, driveTracking!.CloudKind);

        // Editing tracking URL updates both LibraryStore and .musico-cloud.json on disk.
        store.UpdateCloudTrackingAddress(
            parentRoot.Id,
            "BiliMix",
            "https://www.youtube.com/playlist?list=PL_EDITED_DEST",
            cookie: "NID=updated",
            proxyUrl: "7890");

        var editedTracking = store.GetCloudTracking(parentRoot.Id, "BiliMix");
        Assert.NotNull(editedTracking);
        Assert.Equal("https://www.youtube.com/playlist?list=PL_EDITED_DEST", editedTracking!.SourceUrl);
        Assert.Equal("PL_EDITED_DEST", editedTracking.RemoteId);
        Assert.Equal(RootKind.CloudYouTube, editedTracking.CloudKind);
        Assert.Equal("http://127.0.0.1:7890", editedTracking.ProxyUrl);

        var diskManifest = CloudExporter.LoadManifest(exportedSubDir);
        Assert.NotNull(diskManifest);
        Assert.Equal("https://www.youtube.com/playlist?list=PL_EDITED_DEST", diskManifest!.SourceUrl);
        Assert.Equal("PL_EDITED_DEST", diskManifest.RemoteId);
        Assert.Equal(RootKind.CloudYouTube.ToString(), diskManifest.CloudKind);

        // Deleting DriveAuto's manifest causes next RescanAsync to prune DriveAuto from TrackedSubfolders.
        File.Delete(Path.Combine(scannedSubDir, CloudExporter.ManifestFileName));
        await store.RescanAsync();
        Assert.Null(store.GetCloudTracking(parentRoot.Id, "DriveAuto"));
    }

    [Fact]
    public async Task DownloadManagerManagesBackgroundJobsFormattingAndCancellation()
    {
        Assert.Equal("0 B", DownloadManager.FormatBytes(0));
        Assert.Equal("1.5 MB", DownloadManager.FormatBytes((long)(1.5 * 1024 * 1024)));
        Assert.Equal("8.6 MB/s", DownloadManager.FormatSpeed(8.6 * 1024 * 1024));
        Assert.Equal("25%", DownloadManager.FormatPercentage(0.25));
        Assert.Equal(string.Empty, DownloadManager.FormatPercentage(null));

        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        string cacheDir = TestHelpers.MakeTempDir();
        string exportParentDir = TestHelpers.MakeTempDir();

        byte[] audioBytes = new byte[128 * 1024];
        Array.Fill(audioBytes, (byte)0x33);

        var slowTrackGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var slowTrackStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        var handler = new AsyncStubHttpHandler(async (req, ct) =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("youtubei/v1/player", StringComparison.Ordinal))
            {
                string body = req.Content is not null
                    ? await req.Content.ReadAsStringAsync(ct).ConfigureAwait(false)
                    : string.Empty;
                string cdnUrl = body.Contains("vidSlow1", StringComparison.Ordinal)
                    ? "https://cdn.example/slow.m4a"
                    : "https://cdn.example/fast.m4a";
                string json = $$"""
                {
                  "streamingData": {
                    "adaptiveFormats": [
                      { "itag": 140, "url": "{{cdnUrl}}", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000 }
                    ]
                  }
                }
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(json, Encoding.UTF8, "application/json")
                };
            }

            if (url == "https://cdn.example/fast.m4a")
            {
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(audioBytes)
                };
            }

            if (url == "https://cdn.example/slow.m4a")
            {
                slowTrackStarted.TrySetResult();
                await slowTrackGate.Task.WaitAsync(ct).ConfigureAwait(false);
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(audioBytes)
                };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var store = MakeLibrary(stateDir, musicDir, youtube: new YouTubeClient(handler), handler: handler);
        var cache = new CloudAudioCache(cacheDir, handler: handler);
        var exporter = new CloudExporter(cache, new YouTubeClient(handler), new BilibiliClient(handler));
        var downloads = new DownloadManager(exporter, store);

        var cloudRoot = new LibraryRoot(
            Guid.NewGuid(),
            "Fast Mix",
            RootKind.CloudYouTube,
            "https://www.youtube.com/playlist?list=PL_FAST",
            SourceUrl: "https://www.youtube.com/playlist?list=PL_FAST",
            RemoteId: "PL_FAST");
        var fastTrack = new Track(
            Id: Track.MakeId(cloudRoot.Id, "fast.mp3"),
            RootId: cloudRoot.Id,
            RelativePath: "fast.mp3",
            Title: "Fast Track",
            Artist: "Artist",
            Album: "Fast Mix",
            AlbumArtist: "YouTube",
            TrackNumber: 1,
            DiscNumber: 1,
            DurationSeconds: 60,
            FileSize: 0,
            ModifiedUtc: DateTimeOffset.UtcNow,
            HasEmbeddedLyrics: false,
            DateAddedUtc: DateTimeOffset.UtcNow,
            ArtworkKey: null,
            CloudSource: CloudSources.YouTube,
            RemoteTrackId: "vidFast1");

        var completedTcs = new TaskCompletionSource<DownloadJob>(TaskCreationOptions.RunContinuationsAsynchronously);
        var cancelledTcs = new TaskCompletionSource<DownloadJob>(TaskCreationOptions.RunContinuationsAsynchronously);
        downloads.Changed += () =>
        {
            foreach (var job in downloads.Jobs)
            {
                if (job.Kind == DownloadJobKind.PlaylistExport && job.Status == DownloadJobStatus.Completed)
                {
                    completedTcs.TrySetResult(job);
                }
                if (job.Kind == DownloadJobKind.SingleTrackExport && job.Status == DownloadJobStatus.Cancelled)
                {
                    cancelledTcs.TrySetResult(job);
                }
            }
        };

        var playlistJob = downloads.StartPlaylistExport(cloudRoot, [fastTrack], exportParentDir, "Fast Mix", addToLibrary: true);
        var doneJob = await completedTcs.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(playlistJob.Id, doneJob.Id);
        Assert.Equal(1, doneJob.CompletedItems);
        Assert.Equal(1, doneJob.TotalItems);
        Assert.Equal(0, doneJob.FailedItems);
        Assert.Equal(1.0, doneJob.OverallFraction);
        Assert.NotNull(doneJob.LibraryNote);
        Assert.Contains(store.Roots, r => r.IsCloudTrackedLocal && r.Path == Path.Combine(exportParentDir, "Fast Mix"));

        // Cancelling a running SingleTrackExport stops the transfer and marks job Cancelled.
        var slowTrack = fastTrack with { Id = Track.MakeId(cloudRoot.Id, "slow.mp3"), RelativePath = "slow.mp3", Title = "Slow Track", RemoteTrackId = "vidSlow1" };
        var singleJob = downloads.StartSingleTrackExport(slowTrack, cloudRoot, TestHelpers.MakeTempDir());
        await slowTrackStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.True(downloads.HasActiveDownloads);

        downloads.Cancel(singleJob.Id);
        var cancelledJob = await cancelledTcs.Task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(DownloadJobStatus.Cancelled, cancelledJob.Status);
        Assert.False(downloads.HasActiveDownloads);

        downloads.Dismiss(cancelledJob.Id);
        Assert.DoesNotContain(downloads.Jobs, j => j.Id == cancelledJob.Id);

        downloads.ClearFinished();
        Assert.Empty(downloads.Jobs);
    }
}
