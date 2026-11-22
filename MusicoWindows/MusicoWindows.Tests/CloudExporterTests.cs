using System.Net;
using System.Net.Http;
using System.Text;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class CloudExporterTests
{
    // Valid 67-byte 1x1 RGB PNG so Windows WIC BitmapDecoder accepts it in CI as well as macOS.
    private static readonly byte[] ValidPngBytes =
    [
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
        0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0x1F, 0xEA, 0x02,
        0x00, 0x03, 0xEF, 0x01, 0x99, 0xC3, 0x49, 0xF5, 0x60, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
        0x44, 0xAE, 0x42, 0x60, 0x82
    ];

    private sealed class StubHttpHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _responder;
        public int RequestCount { get; private set; }

        public StubHttpHandler(Func<HttpRequestMessage, HttpResponseMessage> responder)
        {
            _responder = responder;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            RequestCount++;
            return Task.FromResult(_responder(request));
        }
    }

    private static byte[] MakeMinimalMp3Bytes()
    {
        // Two valid MPEG-1 Layer 3 frames (44.1 kHz, 128 kbps, 417 bytes each).
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

    private static Track MakeCloudTrack(
        Guid rootId,
        string remoteId,
        string title,
        string artist = "Cloud Artist",
        string album = "Cloud Album",
        int? trackNumber = 1,
        string? artworkKey = null,
        string source = CloudSources.YouTube)
    {
        string relPath = $"{remoteId}.m4a";
        return new Track(
            Id: Track.MakeId(rootId, relPath),
            RootId: rootId,
            RelativePath: relPath,
            Title: title,
            Artist: artist,
            Album: album,
            AlbumArtist: source == CloudSources.Bilibili ? "Bilibili" : "YouTube",
            TrackNumber: trackNumber,
            DiscNumber: 1,
            DurationSeconds: 180,
            FileSize: 0,
            ModifiedUtc: DateTimeOffset.UnixEpoch,
            HasEmbeddedLyrics: false,
            DateAddedUtc: DateTimeOffset.UnixEpoch,
            ArtworkKey: artworkKey,
            CloudSource: source,
            RemoteTrackId: remoteId);
    }

    [Fact]
    public void SanitizeFileNameStripsIllegalCharsTrimsDotsAndSpacesAndHandlesReservedNames()
    {
        Assert.Equal("Hello World", CloudExporter.SanitizeFileName("Hello/\\:*?\"<>| World", "Fallback"));
        Assert.Equal("Clean", CloudExporter.SanitizeFileName("  Cl\0e\na\tn\x1F...   ", "Fallback"));
        Assert.Equal("Fallback", CloudExporter.SanitizeFileName("...   /\\:*?", "Fallback"));
        Assert.Equal("Fallback", CloudExporter.SanitizeFileName(null, "Fallback"));

        foreach (string reserved in new[] { "CON", "prn", "Aux", "NUL", "COM1", "com9", "LPT1", "lpt9" })
        {
            string sanitized = CloudExporter.SanitizeFileName(reserved, "Fallback");
            Assert.NotEqual(reserved, sanitized, StringComparer.OrdinalIgnoreCase);
            Assert.StartsWith("_", sanitized);
        }

        Assert.Equal("_CON.txt", CloudExporter.SanitizeFileName("CON.txt", "Fallback"));
        Assert.Equal("Console", CloudExporter.SanitizeFileName("Console", "Fallback"));
        Assert.Equal("COM10", CloudExporter.SanitizeFileName("COM10", "Fallback"));

        string longName = new string('a', 118) + "...   " + new string('b', 80);
        string capped = CloudExporter.SanitizeFileName(longName, "Fallback");
        Assert.True(capped.Length <= CloudExporter.MaxFileNameLength);
        Assert.False(capped.EndsWith('.'));
        Assert.False(capped.EndsWith(' '));
    }

    [Fact]
    public void GetUniqueFilePathAppendsNumberedSuffixOnCollisions()
    {
        string dir = TestHelpers.MakeTempDir();
        string p1 = CloudExporter.GetUniqueFilePath(dir, "My Song", "m4a");
        Assert.Equal(Path.Combine(dir, "My Song.m4a"), p1);
        File.WriteAllText(p1, "1");

        string p2 = CloudExporter.GetUniqueFilePath(dir, "My Song", ".m4a");
        Assert.Equal(Path.Combine(dir, "My Song (2).m4a"), p2);
        File.WriteAllText(p2, "2");

        string p3 = CloudExporter.GetUniqueFilePath(dir, "My Song", ".m4a");
        Assert.Equal(Path.Combine(dir, "My Song (3).m4a"), p3);
    }

    [Fact]
    public async Task ExportTrackAsyncUsesCachedAudioWithoutNetworkAndWritesTagsAndCoverArt()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string cacheDir = Path.Combine(stateDir, "CloudCache");
        string destDir = TestHelpers.MakeTempDir();
        Directory.CreateDirectory(cacheDir);

        var artwork = new ArtworkCache(Path.Combine(stateDir, "Artwork"));
        string? artKey = artwork.Store(ValidPngBytes);
        Assert.NotNull(artKey);

        var rootId = Guid.NewGuid();
        var root = new LibraryRoot(rootId, "My YT Mix", RootKind.CloudYouTube, "https://youtube.com/playlist?list=PL1");
        var track = MakeCloudTrack(rootId, "vid00000001", "City: Lights?", artist: "Neon Band", album: "Night Drive", trackNumber: 4, artworkKey: artKey);

        // Pre-populate CloudAudioCache with a valid minimal MP3 so zero HTTP calls happen.
        byte[] mp3Bytes = MakeMinimalMp3Bytes();
        File.WriteAllBytes(Path.Combine(cacheDir, $"{track.Id}.mp3"), mp3Bytes);

        var handler = new StubHttpHandler(_ => throw new InvalidOperationException("Network should not be called for cached tracks."));
        var cache = new CloudAudioCache(cacheDir, handler: handler);
        var exporter = new CloudExporter(cache, new YouTubeClient(handler), new BilibiliClient(handler), artwork);

        string exported = await exporter.ExportTrackAsync(track, root, destDir);

        Assert.Equal(0, handler.RequestCount);
        Assert.Equal(Path.Combine(destDir, "City Lights.mp3"), exported);
        Assert.True(File.Exists(exported));
        Assert.Empty(Directory.GetFiles(destDir, "*.tmp"));

        using var tagged = TagLib.File.Create(exported);
        Assert.Equal("City: Lights?", tagged.Tag.Title);
        Assert.Equal(["Neon Band"], tagged.Tag.Performers);
        Assert.Equal("Night Drive", tagged.Tag.Album);
        Assert.Equal(4u, tagged.Tag.Track);
        Assert.Single(tagged.Tag.Pictures);
    }

    [Fact]
    public async Task ExportTrackAsyncDownloadsViaStubbedHttpHandlerAndHandlesUnrecognizedContainerGracefully()
    {
        string cacheDir = TestHelpers.MakeTempDir();
        string destDir = TestHelpers.MakeTempDir();

        var rootId = Guid.NewGuid();
        var root = new LibraryRoot(rootId, "YT Singles", RootKind.CloudYouTube, "https://youtube.com/watch?v=vid00000002");
        var track = MakeCloudTrack(rootId, "vid00000002", "CON");

        byte[] audioPayload = Encoding.UTF8.GetBytes("stub-m4a-stream-bytes");
        var handler = new StubHttpHandler(req =>
        {
            string uri = req.RequestUri!.AbsoluteUri;
            if (uri.Contains("youtubei/v1/player", StringComparison.Ordinal))
            {
                string playerJson = """
                {
                  "streamingData": {
                    "adaptiveFormats": [
                      { "itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "url": "https://cdn.example/audio/vid2.m4a" }
                    ]
                  }
                }
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(playerJson, Encoding.UTF8, "application/json")
                };
            }

            if (uri == "https://cdn.example/audio/vid2.m4a")
            {
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(audioPayload)
                };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var cache = new CloudAudioCache(cacheDir, handler: handler);
        var exporter = new CloudExporter(cache, new YouTubeClient(handler), new BilibiliClient(handler));

        string exported = await exporter.ExportTrackAsync(track, root, destDir);

        Assert.Equal(Path.Combine(destDir, "_CON.m4a"), exported);
        Assert.True(File.Exists(exported));
        Assert.Equal(audioPayload, File.ReadAllBytes(exported));
        Assert.Empty(Directory.GetFiles(destDir, "*.tmp"));
    }

    [Fact]
    public async Task ExportTrackAsyncRejectsLocalTrack()
    {
        string cacheDir = TestHelpers.MakeTempDir();
        string destDir = TestHelpers.MakeTempDir();
        var localTrack = TestHelpers.MakeTrack("song.mp3");
        var exporter = new CloudExporter(new CloudAudioCache(cacheDir), new YouTubeClient(), new BilibiliClient());

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            exporter.ExportTrackAsync(localTrack, null, destDir));
    }

    [Fact]
    public async Task ExportPlaylistAsyncToleratesPartialFailuresReportsProgressAndDeduplicatesNames()
    {
        string cacheDir = TestHelpers.MakeTempDir();
        string parentDir = TestHelpers.MakeTempDir();

        var rootId = Guid.NewGuid();
        var root = new LibraryRoot(rootId, "Road/Trip: Mix", RootKind.CloudYouTube, "https://youtube.com/playlist?list=PLmix");

        var t1 = MakeCloudTrack(rootId, "vid00000010", "Same Title", trackNumber: 1);
        var t2Broken = MakeCloudTrack(rootId, "vid00000011", "Broken Track", trackNumber: 2);
        var t3Duplicate = MakeCloudTrack(rootId, "vid00000012", "Same Title", trackNumber: 3);

        byte[] mp3Bytes = MakeMinimalMp3Bytes();
        var handler = new StubHttpHandler(req =>
        {
            string uri = req.RequestUri!.AbsoluteUri;
            if (uri.Contains("youtubei/v1/player", StringComparison.Ordinal))
            {
                string body = req.Content?.ReadAsStringAsync().GetAwaiter().GetResult() ?? string.Empty;
                if (body.Contains("vid00000011", StringComparison.Ordinal))
                {
                    return new HttpResponseMessage(HttpStatusCode.NotFound);
                }

                string videoId = body.Contains("vid00000010", StringComparison.Ordinal) ? "vid00000010" : "vid00000012";
                string playerJson = $$"""
                {
                  "streamingData": {
                    "adaptiveFormats": [
                      { "itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "url": "https://cdn.example/{{videoId}}" }
                    ]
                  }
                }
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(playerJson, Encoding.UTF8, "application/json")
                };
            }

            if (uri.StartsWith("https://cdn.example/", StringComparison.Ordinal))
            {
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(mp3Bytes)
                };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var cache = new CloudAudioCache(cacheDir, handler: handler);
        var exporter = new CloudExporter(cache, new YouTubeClient(handler), new BilibiliClient(handler));

        var progressEvents = new List<CloudExportProgress>();
        var syncProgress = new SynchronousProgress<CloudExportProgress>(p => progressEvents.Add(p));

        var result = await exporter.ExportPlaylistAsync(
            root,
            [t1, t2Broken, t3Duplicate],
            parentDir,
            root.Name,
            syncProgress);

        string expectedFolder = Path.Combine(parentDir, "RoadTrip Mix");
        Assert.Equal(expectedFolder, result.DestinationFolder);
        Assert.Equal(2, result.SavedCount);
        Assert.Equal(1, result.FailedCount);
        Assert.Single(result.Errors);
        Assert.Contains("Broken Track", result.Errors[0]);

        Assert.True(File.Exists(Path.Combine(expectedFolder, "Same Title.m4a")));
        Assert.True(File.Exists(Path.Combine(expectedFolder, "Same Title (2).m4a")));
        Assert.False(File.Exists(Path.Combine(expectedFolder, "Broken Track.m4a")));
        Assert.Empty(Directory.GetFiles(expectedFolder, "*.tmp"));

        Assert.Equal(4, progressEvents.Count);
        Assert.Equal((0, 3), (progressEvents[0].Completed, progressEvents[0].Total));
        Assert.Equal((3, 3), (progressEvents[^1].Completed, progressEvents[^1].Total));
    }

    [Fact]
    public async Task ExportPlaylistAsyncRespectsCancellation()
    {
        string cacheDir = TestHelpers.MakeTempDir();
        string parentDir = TestHelpers.MakeTempDir();

        var rootId = Guid.NewGuid();
        var root = new LibraryRoot(rootId, "Cancel Mix", RootKind.CloudYouTube, "https://youtube.com/playlist?list=PLcancel");
        var t1 = MakeCloudTrack(rootId, "vid00000020", "First");
        var t2 = MakeCloudTrack(rootId, "vid00000021", "Second");
        File.WriteAllBytes(Path.Combine(cacheDir, $"{t1.Id}.mp3"), MakeMinimalMp3Bytes());
        File.WriteAllBytes(Path.Combine(cacheDir, $"{t2.Id}.mp3"), MakeMinimalMp3Bytes());

        var exporter = new CloudExporter(new CloudAudioCache(cacheDir), new YouTubeClient(), new BilibiliClient());
        using var cts = new CancellationTokenSource();

        var progress = new SynchronousProgress<CloudExportProgress>(p =>
        {
            if (p.Completed == 1)
            {
                cts.Cancel();
            }
        });

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            exporter.ExportPlaylistAsync(root, [t1, t2], parentDir, "Cancel Mix", progress, cts.Token));

        string destFolder = Path.Combine(parentDir, "Cancel Mix");
        Assert.True(File.Exists(Path.Combine(destFolder, "First.mp3")));
        Assert.False(File.Exists(Path.Combine(destFolder, "Second.mp3")));
    }

    [Fact]
    public async Task LibraryStoreIsFolderIndexedDetectsRootsAndSubfolders()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        string subDir = Path.Combine(musicDir, "Saved Cloud");
        string excludedDir = Path.Combine(musicDir, "Excluded");
        string outsideDir = TestHelpers.MakeTempDir();
        Directory.CreateDirectory(subDir);
        Directory.CreateDirectory(excludedDir);

        var store = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Artwork")));

        Assert.True(store.IsFolderIndexed(musicDir));
        Assert.True(store.IsFolderIndexed(subDir));
        Assert.False(store.IsFolderIndexed(outsideDir));

        store.RemoveSubfolder(LibraryRoot.AppFolderId, "Excluded");
        Assert.False(store.IsFolderIndexed(excludedDir));

        await store.AddRootAsync(outsideDir);
        Assert.True(store.IsFolderIndexed(outsideDir));
    }

    [Fact]
    public async Task ExportPlaylistWritesManifestWithoutCookieAndUpdateLocalFolderDownloadsOnlyNewAndMissing()
    {
        string cacheDir = TestHelpers.MakeTempDir();
        string parentDir = TestHelpers.MakeTempDir();

        var cloudId = Guid.NewGuid();
        var cloudRoot = new LibraryRoot(
            Id: cloudId,
            Name: "Tracked Mix",
            Kind: RootKind.CloudYouTube,
            Path: "https://music.youtube.com/playlist?list=PLtrack",
            SourceUrl: "https://music.youtube.com/playlist?list=PLtrack",
            RemoteId: "PLtrack",
            Cookie: "SECRET_COOKIE=should_not_persist");

        var t1 = MakeCloudTrack(cloudId, "vid_1", "Song One", trackNumber: 1);
        var t2 = MakeCloudTrack(cloudId, "vid_2", "Song Two", trackNumber: 2);
        byte[] mp3Bytes = MakeMinimalMp3Bytes();

        var requestedIds = new List<string>();
        var handler = new StubHttpHandler(req =>
        {
            string uri = req.RequestUri!.AbsoluteUri;
            if (uri.Contains("youtubei/v1/player", StringComparison.Ordinal))
            {
                string body = req.Content?.ReadAsStringAsync().GetAwaiter().GetResult() ?? string.Empty;
                string vid = body.Contains("vid_1", StringComparison.Ordinal) ? "vid_1"
                    : body.Contains("vid_2", StringComparison.Ordinal) ? "vid_2"
                    : "vid_3";
                requestedIds.Add(vid);
                string playerJson = $$"""
                {
                  "streamingData": {
                    "adaptiveFormats": [
                      { "itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "url": "https://cdn.example/{{vid}}.m4a" }
                    ]
                  }
                }
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(playerJson, Encoding.UTF8, "application/json")
                };
            }

            if (uri.StartsWith("https://cdn.example/", StringComparison.Ordinal))
            {
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(mp3Bytes)
                };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var exporter = new CloudExporter(new CloudAudioCache(cacheDir, handler: handler), new YouTubeClient(handler), new BilibiliClient(handler));
        var exportResult = await exporter.ExportPlaylistAsync(cloudRoot, [t1, t2], parentDir, "Tracked Mix");
        Assert.Equal(2, exportResult.SavedCount);

        string manifestPath = Path.Combine(exportResult.DestinationFolder, CloudExporter.ManifestFileName);
        Assert.True(File.Exists(manifestPath));
        string rawManifest = File.ReadAllText(manifestPath);
        Assert.DoesNotContain("SECRET_COOKIE", rawManifest, StringComparison.Ordinal);

        var loaded = CloudExporter.LoadManifest(exportResult.DestinationFolder);
        Assert.NotNull(loaded);
        Assert.Equal("https://music.youtube.com/playlist?list=PLtrack", loaded!.SourceUrl);
        Assert.Equal(RootKind.CloudYouTube.ToString(), loaded.CloudKind);
        Assert.Equal(2, loaded.Tracks.Count);

        // Delete Song Two on disk, keep Song One, add an unrelated user file, and clear requestedIds.
        File.Delete(Path.Combine(exportResult.DestinationFolder, "Song Two.m4a"));
        string keepNotePath = Path.Combine(exportResult.DestinationFolder, "Custom Note.txt");
        File.WriteAllText(keepNotePath, "keep me");
        requestedIds.Clear();

        var localTrackedRoot = new LibraryRoot(
            Id: Guid.NewGuid(),
            Name: "Tracked Mix",
            Kind: RootKind.Picked,
            Path: exportResult.DestinationFolder,
            SourceUrl: cloudRoot.SourceUrl,
            RemoteId: cloudRoot.RemoteId,
            LinkedCloudKind: RootKind.CloudYouTube);

        var updateResult = await exporter.UpdateLocalFolderAsync(
            localTrackedRoot,
            fetchPlaylist: (_, _, _, _, _) => Task.FromResult(new CloudPlaylistResult(
                Kind: RootKind.CloudYouTube,
                RemoteId: "PLtrack",
                Title: "Tracked Mix",
                SourceUrl: "https://music.youtube.com/playlist?list=PLtrack",
                Items:
                [
                    new CloudPlaylistItem("vid_1", "Song One", "Artist", "Tracked Mix", 180, null, 1),
                    new CloudPlaylistItem("vid_2", "Song Two", "Artist", "Tracked Mix", 190, null, 2),
                    new CloudPlaylistItem("vid_3", "Song Three", "Artist", "Tracked Mix", 200, null, 3),
                ])));

        Assert.Equal(2, updateResult.AddedCount);
        Assert.Equal(1, updateResult.SkippedCount);
        Assert.Equal(0, updateResult.FailedCount);
        Assert.Equal(3, updateResult.TotalRemoteCount);
        Assert.Equal(["vid_2", "vid_3"], requestedIds);
        Assert.True(File.Exists(Path.Combine(exportResult.DestinationFolder, "Song One.m4a")));
        Assert.True(File.Exists(Path.Combine(exportResult.DestinationFolder, "Song Two.m4a")));
        Assert.True(File.Exists(Path.Combine(exportResult.DestinationFolder, "Song Three.m4a")));
        Assert.True(File.Exists(keepNotePath));

        var updatedManifest = CloudExporter.LoadManifest(exportResult.DestinationFolder);
        Assert.NotNull(updatedManifest);
        Assert.Equal(3, updatedManifest!.Tracks.Count);
        Assert.Equal("Song Three.m4a", updatedManifest.Tracks["vid_3"]);
    }

    [Fact]
    public async Task LibraryStoreAddRootAutoDetectsCloudManifest()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        string savedDir = TestHelpers.MakeTempDir();

        CloudExporter.SaveManifest(
            savedDir,
            new CloudFolderManifest(
                SourceUrl: "https://www.bilibili.com/video/BV1xx411c7mD",
                RemoteId: "BV1xx411c7mD",
                CloudKind: RootKind.CloudBilibili.ToString(),
                LastSyncedUtc: DateTimeOffset.UtcNow,
                Tracks: new Dictionary<string, string> { ["BV1xx411c7mD_1"] = "Track.m4a" }));

        var store = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Artwork")));

        await store.AddRootAsync(savedDir);

        var added = Assert.Single(store.Roots, r => string.Equals(r.Path, Path.GetFullPath(savedDir), StringComparison.OrdinalIgnoreCase));
        Assert.True(added.IsCloudTrackedLocal);
        Assert.Equal(RootKind.Picked, added.Kind);
        Assert.Equal(RootKind.CloudBilibili, added.LinkedCloudKind);
        Assert.Equal("https://www.bilibili.com/video/BV1xx411c7mD", added.SourceUrl);
        Assert.Equal("BV1xx411c7mD", added.RemoteId);
        Assert.Null(added.Cookie);
    }

    private sealed class SynchronousProgress<T> : IProgress<T>
    {
        private readonly Action<T> _handler;
        public SynchronousProgress(Action<T> handler) => _handler = handler;
        public void Report(T value) => _handler(value);
    }
}
