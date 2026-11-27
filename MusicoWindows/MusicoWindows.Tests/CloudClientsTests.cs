using System.Collections.Concurrent;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class CloudClientsTests
{
    private sealed class StubHttpHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _responder;
        private readonly object _lock = new();
        public List<HttpRequestMessage> Requests { get; } = new();

        public StubHttpHandler(Func<HttpRequestMessage, HttpResponseMessage> responder)
        {
            _responder = responder;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            lock (_lock)
            {
                Requests.Add(request);
            }
            return Task.FromResult(_responder(request));
        }
    }

    private sealed class AsyncStubHttpHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> _responder;

        public AsyncStubHttpHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> responder)
        {
            _responder = responder;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            _responder(request, cancellationToken);
    }

    private static HttpResponseMessage JsonResponse(string json, HttpStatusCode code = HttpStatusCode.OK) =>
        new(code) { Content = new StringContent(json, Encoding.UTF8, "application/json") };

    [Fact]
    public void ProxyNormalizationMatchesAriaFormats()
    {
        Assert.Null(CloudProxyUtils.NormalizeProxyUrl(null));
        Assert.Null(CloudProxyUtils.NormalizeProxyUrl("   "));
        Assert.Equal("http://127.0.0.1:7890", CloudProxyUtils.NormalizeProxyUrl("7890"));
        Assert.Equal("http://127.0.0.1:8080", CloudProxyUtils.NormalizeProxyUrl("127.0.0.1:8080"));
        Assert.Equal("https://proxy.local:3128", CloudProxyUtils.NormalizeProxyUrl("https://proxy.local:3128"));
        Assert.Throws<ArgumentException>(() => CloudProxyUtils.NormalizeProxyUrl("99999"));
        Assert.Throws<ArgumentException>(() => CloudProxyUtils.NormalizeProxyUrl("ftp://proxy:21"));
    }

    [Fact]
    public async Task CloudAudioCacheDownloadsAndEvictsLru()
    {
        string dir = TestHelpers.MakeTempDir();
        byte[] payload = new byte[1200];
        Array.Fill(payload, (byte)'A');

        int downloads = 0;
        var handler = new StubHttpHandler(_ =>
        {
            downloads++;
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent(payload)
            };
        });

        // Cap at 2500 bytes -> 2 files fit (2400 bytes), 3rd file evicts least-recently-used.
        var cache = new CloudAudioCache(dir, maxBytes: 2500, handler: handler);

        string p1 = await cache.GetOrDownloadAsync("t1", _ => Task.FromResult(new CloudAudioStream("https://dl/1", ".m4a")));
        File.SetLastAccessTimeUtc(p1, DateTime.UtcNow.AddMinutes(-30));

        string p2 = await cache.GetOrDownloadAsync("t2", _ => Task.FromResult(new CloudAudioStream("https://dl/2", ".m4a")));
        File.SetLastAccessTimeUtc(p2, DateTime.UtcNow.AddMinutes(-20));

        // Cache hit on t1 updates its LRU timestamp so t2 becomes the oldest.
        string p1Again = await cache.GetOrDownloadAsync("t1", _ => Task.FromResult(new CloudAudioStream("https://dl/1", ".m4a")));
        Assert.Equal(p1, p1Again);
        Assert.Equal(2, downloads);

        string p3 = await cache.GetOrDownloadAsync("t3", _ => Task.FromResult(new CloudAudioStream("https://dl/3", ".m4a")));
        Assert.True(File.Exists(p3));
        Assert.True(File.Exists(p1));
        Assert.False(File.Exists(p2));
    }

    [Fact]
    public void LegacyLibraryRootAndTrackJsonDeserializeBackwardsCompatibly()
    {
        string legacyRootJson = """{"id":"11111111-1111-1111-1111-111111111111","name":"Local","kind":"Picked","path":"C:\\Music"}""";
        var root = JsonSerializer.Deserialize<LibraryRoot>(legacyRootJson, Persistence.JsonOptions);
        Assert.NotNull(root);
        Assert.False(root!.IsCloud);
        Assert.True(root.IsLocal);
        Assert.Null(root.SourceUrl);
        Assert.Null(root.ProxyUrl);

        string legacyTrackJson = """
        {
          "id": "abc",
          "rootId": "11111111-1111-1111-1111-111111111111",
          "relativePath": "song.mp3",
          "title": "Song",
          "artist": "Artist",
          "album": "Album",
          "albumArtist": "Artist",
          "trackNumber": 1,
          "discNumber": 1,
          "durationSeconds": 180,
          "fileSize": 1000,
          "modifiedUtc": "2025-01-01T00:00:00Z",
          "hasEmbeddedLyrics": false,
          "dateAddedUtc": "2025-01-01T00:00:00Z"
        }
        """;
        var track = JsonSerializer.Deserialize<Track>(legacyTrackJson, Persistence.JsonOptions);
        Assert.NotNull(track);
        Assert.False(track!.IsCloud);
        Assert.Null(track.CloudSource);
        Assert.Null(track.RemoteTrackId);
    }

    [Fact]
    public void BareAppRejectsYouTubeAndBilibiliWhileExtensionsHandleShareTextAndBareIds()
    {
        string stateDir = TestHelpers.MakeTempDir();
        var bareRegistry = new CloudProviderRegistry();

        // Bare app rejects YouTube & Bilibili URLs and bare IDs cleanly
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("https://music.youtube.com/playlist?list=PL1234567890abcdef"));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("https://youtu.be/dQw4w9WgXcQ"));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("https://www.bilibili.com/video/BV1xx411c7mD"));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("https://b23.tv/abc1234"));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("PL1234567890abcdef", bareRegistry));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("RDMM", bareRegistry));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("MPREb_4pQ5x8K9y2Z", bareRegistry));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("dQw4w9WgXcQ", bareRegistry));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("BV1xx411c7mD", bareRegistry));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("av170001", bareRegistry));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("ml555666", bareRegistry));

        // Bare app still supports Google Drive URLs
        Assert.Equal(RootKind.CloudGoogleDrive, CloudUrlClassifier.Classify("https://drive.google.com/drive/folders/abc1234", bareRegistry));
        Assert.Equal(
            "drive.google.com/drive/folders/abc1234",
            CloudUrlClassifier.ExtractCloudUrl("Check folder drive.google.com/drive/folders/abc1234。"));

        // Register YouTube & Bilibili JS extensions
        var extRegistry = new CloudProviderRegistry();
        var extStore = new ExtensionStore(stateDir);
        extStore.BindRegistry(extRegistry);

        string ytScript = """
            var manifest = {
              apiVersion: 1,
              id: 'youtube-search',
              name: 'YouTube',
              version: '1.0.0',
              capabilities: ['playlist', 'stream'],
              urlPatterns: [
                '^(https?://)?(www\\.|m\\.|music\\.)?youtube(-nocookie)?\\.com/(watch|playlist|browse|shorts|live|embed|v/)',
                '^(https?://)?(www\\.)?youtu\\.be/[A-Za-z0-9_-]{11}',
                '^(PL|OLAK5uy_|MPREb_|UU|FL|RD)[A-Za-z0-9_-]{8,}$',
                '^RDMM$'
              ],
              permissions: { hosts: ['youtube.com', 'music.youtube.com', 'youtu.be'] }
            };
            function canHandle(input) {
              return { matched: true, normalizedUrl: input.extractedUrl, remoteId: 'yt-1', kind: 'playlist' };
            }
            async function fetchCollection() { return { remoteId: 'yt-1', title: 'YT', tracks: [] }; }
            async function resolveStream() { return { url: 'https://youtube.com/a.m4a' }; }
            module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection, resolveStream: resolveStream };
            """;
        string biliScript = """
            var manifest = {
              apiVersion: 1,
              id: 'bilibili-search',
              name: 'Bilibili',
              version: '1.0.0',
              capabilities: ['playlist', 'stream'],
              urlPatterns: [
                '^(https?://)?(www\\.|m\\.|space\\.)?bilibili\\.com/',
                '^(https?://)?(www\\.)?b23\\.tv/[A-Za-z0-9]+',
                '^BV[0-9A-Za-z]{10,}$',
                '^av[0-9]+$',
                '^ml[0-9]+$'
              ],
              permissions: { hosts: ['bilibili.com', 'b23.tv'] }
            };
            function canHandle(input) {
              return { matched: true, normalizedUrl: input.extractedUrl, remoteId: 'bili-1', kind: 'playlist' };
            }
            async function fetchCollection() { return { remoteId: 'bili-1', title: 'Bili', tracks: [] }; }
            async function resolveStream() { return { url: 'https://bilibili.com/a.m4a' }; }
            module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection, resolveStream: resolveStream };
            """;

        var ytInst = extStore.StageImportedPackage(ExtensionPackageLoader.LoadSingleFileScript(ytScript));
        var biliInst = extStore.StageImportedPackage(ExtensionPackageLoader.LoadSingleFileScript(biliScript));
        extStore.ApproveAndEnable(ytInst.Id);
        extStore.ApproveAndEnable(biliInst.Id);

        Assert.Equal(
            "https://b23.tv/abc1234",
            CloudUrlClassifier.ExtractCloudUrl("App: https://example.com/download Link: https://b23.tv/abc1234", extRegistry));
        Assert.Equal(
            "music.youtube.com/playlist?list=PL1234567890abcdef",
            CloudUrlClassifier.ExtractCloudUrl("Mix: music.youtube.com/playlist?list=PL1234567890abcdef。", extRegistry));
        Assert.Equal(
            "b23.tv/abc1234",
            CloudUrlClassifier.ExtractCloudUrl("【歌单】b23.tv/abc1234)", extRegistry));

        Assert.Equal(RootKind.CloudExtension, CloudUrlClassifier.Classify("【YouTube搬运合集】深夜听的歌 https://b23.tv/abc1234】", extRegistry));
        Assert.Equal(RootKind.CloudExtension, CloudUrlClassifier.Classify("Check out this BV1xx411c7mD cover https://music.youtube.com/playlist?list=PL1234567890abcdef。", extRegistry));
        Assert.Equal(RootKind.CloudExtension, CloudUrlClassifier.Classify("PL1234567890abcdef", extRegistry));
        Assert.Equal(RootKind.CloudExtension, CloudUrlClassifier.Classify("BV1xx411c7mD", extRegistry));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("https://example.com/foo", extRegistry));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("just some random text", extRegistry));
    }

    [Fact]
    public async Task LibraryStoreAddCloudRootFromShareTextAndReorderCloudFolderTracks()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();

        var registry = new CloudProviderRegistry();
        var extStore = new ExtensionStore(stateDir);
        extStore.BindRegistry(registry);

        string ytScript = """
            var manifest = {
              apiVersion: 1,
              id: 'youtube-search',
              name: 'YouTube',
              version: '1.0.0',
              capabilities: ['playlist', 'stream'],
              urlPatterns: ['^(https?://)?(www\\.|m\\.|music\\.)?youtube\\.com/'],
              permissions: { hosts: ['music.youtube.com'] }
            };
            function canHandle(input) {
              return { matched: true, normalizedUrl: input.extractedUrl, remoteId: 'PLShareTest123', kind: 'playlist' };
            }
            async function fetchCollection() {
              return {
                remoteId: 'PLShareTest123',
                title: 'YT Mix',
                tracks: [
                  { remoteTrackId: 'vid00000001', title: 'Song Alpha', artist: 'Artist A', trackNumber: 1 },
                  { remoteTrackId: 'vid00000002', title: 'Song Beta', artist: 'Artist B', trackNumber: 2 }
                ]
              };
            }
            async function resolveStream() { return { url: 'https://music.youtube.com/a.m4a' }; }
            module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection, resolveStream: resolveStream };
            """;
        string biliScript = """
            var manifest = {
              apiVersion: 1,
              id: 'bilibili-search',
              name: 'Bilibili',
              version: '1.0.0',
              capabilities: ['playlist', 'stream'],
              urlPatterns: ['^(https?://)?(www\\.)?b23\\.tv/'],
              permissions: { hosts: ['b23.tv'] }
            };
            function canHandle(input) {
              return { matched: true, normalizedUrl: 'https://b23.tv/short123', remoteId: 'BV1xx411c7mD', kind: 'playlist' };
            }
            async function fetchCollection() {
              return {
                remoteId: 'BV1xx411c7mD',
                title: 'Bili Concert',
                tracks: [
                  { remoteTrackId: 'BV1xx411c7mD#1001', title: 'Part One', artist: 'Uploader', trackNumber: 1 },
                  { remoteTrackId: 'BV1xx411c7mD#1002', title: 'Part Two', artist: 'Uploader', trackNumber: 2 }
                ]
              };
            }
            async function resolveStream() { return { url: 'https://b23.tv/a.m4a' }; }
            module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection, resolveStream: resolveStream };
            """;

        var ytInst = extStore.StageImportedPackage(ExtensionPackageLoader.LoadSingleFileScript(ytScript));
        var biliInst = extStore.StageImportedPackage(ExtensionPackageLoader.LoadSingleFileScript(biliScript));
        extStore.ApproveAndEnable(ytInst.Id);
        extStore.ApproveAndEnable(biliInst.Id);

        var lib = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Art")),
            providerRegistry: registry);

        var ytRoot = await lib.AddCloudRootAsync("Check out this mix https://music.youtube.com/playlist?list=PLShareTest123。");
        Assert.Equal(RootKind.CloudExtension, ytRoot.Kind);
        Assert.Equal("ext.youtube-search", ytRoot.ProviderId);
        Assert.Equal("https://music.youtube.com/playlist?list=PLShareTest123", ytRoot.SourceUrl);

        var biliRoot = await lib.AddCloudRootAsync("【歌单】深夜听的歌 https://b23.tv/short123)");
        Assert.Equal(RootKind.CloudExtension, biliRoot.Kind);
        Assert.Equal("ext.bilibili-search", biliRoot.ProviderId);
        Assert.Equal("https://b23.tv/short123", biliRoot.SourceUrl);

        // Verify cloud folder custom ordering persists cleanly without losing tracks
        var history = new PlayHistoryStore(stateDir);
        var initialYt = lib.GetFolderContents(ytRoot.Id, string.Empty, history).Tracks.Select(t => t.Id).ToList();
        Assert.Equal(2, initialYt.Count);
        lib.MoveFolderTrack(ytRoot.Id, string.Empty, 1, 0, history);
        Assert.Equal(TrackSortMode.Custom, lib.GetFolderSortMode(ytRoot.Id, string.Empty));
        Assert.Equal(
            [initialYt[1], initialYt[0]],
            lib.GetFolderContents(ytRoot.Id, string.Empty, history).Tracks.Select(t => t.Id));

        var initialBili = lib.GetFolderContents(biliRoot.Id, string.Empty, history).Tracks.Select(t => t.Id).ToList();
        Assert.Equal(2, initialBili.Count);
        lib.MoveFolderTrack(biliRoot.Id, string.Empty, 0, 1, history);
        Assert.Equal(
            [initialBili[1], initialBili[0]],
            lib.GetFolderContents(biliRoot.Id, string.Empty, history).Tracks.Select(t => t.Id));

        // Resync preserves custom order and all tracks
        await lib.RescanAsync();
        Assert.Equal(
            [initialYt[1], initialYt[0]],
            lib.GetTracksUnder(ytRoot.Id, string.Empty, history).Select(t => t.Id));
        Assert.Equal(
            [initialBili[1], initialBili[0]],
            lib.GetTracksUnder(biliRoot.Id, string.Empty, history).Select(t => t.Id));
    }

    [Fact]
    public void GoogleDriveUrlAndShareTextParsingHandlesAllFormatsAndResourceKey()
    {
        var f1 = GoogleDriveClient.ParseInput("https://drive.google.com/drive/folders/1AbCdEfGhIjKlMnOp?usp=sharing");
        Assert.Equal(GoogleDriveTargetKind.Folder, f1.Kind);
        Assert.Equal("1AbCdEfGhIjKlMnOp", f1.Id);
        Assert.Null(f1.ResourceKey);

        var f2 = GoogleDriveClient.ParseInput("https://drive.google.com/drive/u/2/folders/1FolderU2?resourcekey=0-rkFolder");
        Assert.Equal(GoogleDriveTargetKind.Folder, f2.Kind);
        Assert.Equal("1FolderU2", f2.Id);
        Assert.Equal("0-rkFolder", f2.ResourceKey);

        var f3 = GoogleDriveClient.ParseInput("https://drive.google.com/drive/mobile/folders/1MobileFolder");
        Assert.Equal(GoogleDriveTargetKind.Folder, f3.Kind);
        Assert.Equal("1MobileFolder", f3.Id);

        var f4 = GoogleDriveClient.ParseInput("https://drive.google.com/embeddedfolderview?id=1EmbeddedId#list");
        Assert.Equal(GoogleDriveTargetKind.Folder, f4.Kind);
        Assert.Equal("1EmbeddedId", f4.Id);

        var f5 = GoogleDriveClient.ParseInput("https://drive.google.com/folderview?id=1LegacyFolder&resourcekey=0-legacy");
        Assert.Equal(GoogleDriveTargetKind.Folder, f5.Kind);
        Assert.Equal("1LegacyFolder", f5.Id);
        Assert.Equal("0-legacy", f5.ResourceKey);

        var file1 = GoogleDriveClient.ParseInput("https://drive.google.com/file/d/1FileViewId/view?usp=drive_link&resourcekey=0-rkFile");
        Assert.Equal(GoogleDriveTargetKind.File, file1.Kind);
        Assert.Equal("1FileViewId", file1.Id);
        Assert.Equal("0-rkFile", file1.ResourceKey);

        var file2 = GoogleDriveClient.ParseInput("https://drive.google.com/file/d/1FileEditId/edit");
        Assert.Equal(GoogleDriveTargetKind.File, file2.Kind);
        Assert.Equal("1FileEditId", file2.Id);

        var file3 = GoogleDriveClient.ParseInput("https://drive.google.com/file/d/1FilePreviewId/preview");
        Assert.Equal(GoogleDriveTargetKind.File, file3.Kind);
        Assert.Equal("1FilePreviewId", file3.Id);

        var file4 = GoogleDriveClient.ParseInput("https://drive.google.com/uc?export=download&id=1UcFileId");
        Assert.Equal(GoogleDriveTargetKind.File, file4.Kind);
        Assert.Equal("1UcFileId", file4.Id);

        var file5 = GoogleDriveClient.ParseInput("https://drive.usercontent.google.com/download?id=1UserContentId&confirm=t");
        Assert.Equal(GoogleDriveTargetKind.File, file5.Kind);
        Assert.Equal("1UserContentId", file5.Id);

        var file6 = GoogleDriveClient.ParseInput("https://drive.google.com/open?id=1OpenId");
        Assert.Equal(GoogleDriveTargetKind.Open, file6.Kind);
        Assert.Equal("1OpenId", file6.Id);

        var file7 = GoogleDriveClient.ParseInput("https://drive.google.com/u/1/file/d/1MultiAccountPreview/preview?resourcekey=0-rkU1");
        Assert.Equal(GoogleDriveTargetKind.File, file7.Kind);
        Assert.Equal("1MultiAccountPreview", file7.Id);
        Assert.Equal("0-rkU1", file7.ResourceKey);

        var file8 = GoogleDriveClient.ParseInput("https://drive.google.com/u/0/file/d/1MultiAccountView/view");
        Assert.Equal(GoogleDriveTargetKind.File, file8.Kind);
        Assert.Equal("1MultiAccountView", file8.Id);

        string encoded = GoogleDriveClient.EncodeRemoteTrackId("1FileId", "0-rkVal");
        var decoded = GoogleDriveClient.DecodeRemoteTrackId(encoded);
        Assert.Equal("1FileId", decoded.FileId);
        Assert.Equal("0-rkVal", decoded.ResourceKey);

        // Share text with Chinese/English wrappers & YouTube/Bilibili decoys in text
        string share1 = "【YouTube BV1xx411c7mD 备份】https://drive.google.com/drive/folders/1ShareFolderId?usp=sharing】";
        Assert.Equal("https://drive.google.com/drive/folders/1ShareFolderId?usp=sharing", CloudUrlClassifier.ExtractCloudUrl(share1));
        Assert.Equal(RootKind.CloudGoogleDrive, CloudUrlClassifier.Classify(share1));

        string share2 = "Download here: drive.usercontent.google.com/download?id=1ShareFileId。";
        Assert.Equal("drive.usercontent.google.com/download?id=1ShareFileId", CloudUrlClassifier.ExtractCloudUrl(share2));
        Assert.Equal(RootKind.CloudGoogleDrive, CloudUrlClassifier.Classify(share2));
    }

    [Fact]
    public async Task GoogleDriveFetchesDriveIvdRecursesSubfoldersAndFiltersNonAudio()
    {
        static string EscapeJs(string json) =>
            json.Replace("\\", "\\\\")
                .Replace("\"", "\\x22")
                .Replace("[", "\\x5b")
                .Replace("]", "\\x5d");

        string rootIvd = EscapeJs("""
        [
          [
            ["file_mp3", ["root_folder"], "01 - Opening.mp3", "audio/mpeg", "https://drive.google.com/file/d/file_mp3/view?resourcekey=0-rk1"],
            ["file_noext", ["root_folder"], "Lossless Bonus", "audio/flac"],
            ["file_pdf", ["root_folder"], "Booklet.pdf", "application/pdf"],
            ["file_img", ["root_folder"], "Cover.png", "image/png"],
            ["file_doc", ["root_folder"], "ReadMe", "application/vnd.google-apps.document"],
            ["sub_1", ["root_folder"], "Disc 2", "application/vnd.google-apps.folder"]
          ]
        ]
        """);

        string subIvd = EscapeJs("""
        [
          [
            ["file_opus", ["sub_1"], "02 - Acoustic.opus", "application/octet-stream"],
            ["root_folder", ["sub_1"], "Cycle Back", "application/vnd.google-apps.folder"],
            ["sub_depth2", ["sub_1"], "Hidden Outtakes", "application/vnd.google-apps.folder"]
          ]
        ]
        """);

        string depth2Ivd = EscapeJs("""
        [
          [
            ["file_wav", ["sub_depth2"], "Demo.wav", "audio/wav"]
          ]
        ]
        """);

        var requestedUrls = new List<string>();
        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            requestedUrls.Add(url);

            if (url.Contains("/drive/folders/root_folder", StringComparison.Ordinal))
            {
                Assert.Contains("resourcekey=0-rootRk", url);
                string html = $"<html><head><title>OST Collection - Google 云端硬盘</title></head><body><script>window['_DRIVE_ivd'] = '{rootIvd}';</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }
            if (url.Contains("/drive/folders/sub_1", StringComparison.Ordinal))
            {
                string html = $"<html><head><title>Disc 2 - Google Drive</title></head><body><script>window._DRIVE_ivd = '{subIvd}';</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }
            if (url.Contains("/drive/folders/sub_depth2", StringComparison.Ordinal))
            {
                string html = $"<html><head><title>Hidden Outtakes - Google Drive</title></head><body><script>window['_DRIVE_ivd'] = '{depth2Ivd}';</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new GoogleDriveClient(handler);
        var result = await client.FetchPlaylistAsync("https://drive.google.com/drive/folders/root_folder?resourcekey=0-rootRk");

        Assert.Equal(RootKind.CloudGoogleDrive, result.Kind);
        Assert.Equal("OST Collection", result.Title);
        Assert.Equal("root_folder", result.RemoteId);
        Assert.Equal(4, result.Items.Count);

        Assert.Equal("01 - Opening", result.Items[0].Title);
        Assert.Equal("OST Collection", result.Items[0].Album);
        Assert.Equal(".mp3", result.Items[0].FileExtension);
        Assert.Contains("0-rk1", result.Items[0].RemoteTrackId);

        Assert.Equal("Lossless Bonus", result.Items[1].Title);
        Assert.Equal("OST Collection", result.Items[1].Album);
        Assert.Equal(".flac", result.Items[1].FileExtension);

        Assert.Equal("02 - Acoustic", result.Items[2].Title);
        Assert.Equal("Disc 2", result.Items[2].Album);
        Assert.Equal(".opus", result.Items[2].FileExtension);

        Assert.Equal("Demo", result.Items[3].Title);
        Assert.Equal("Hidden Outtakes", result.Items[3].Album);
        Assert.Equal(".wav", result.Items[3].FileExtension);

        // Cycle to root_folder must NOT re-fetch root_folder
        Assert.Equal(1, requestedUrls.Count(u => u.Contains("/drive/folders/root_folder", StringComparison.Ordinal)));
    }

    [Fact]
    public async Task GoogleDriveFallsBackToEmbeddedFolderViewWhenIvdMissing()
    {
        string embeddedRootHtml = """
        <html>
          <head><title>Embedded Mix - Google Drive</title></head>
          <body>
            <div class="flip-entry" id="entry-f1">
              <a href="https://drive.google.com/file/d/f_audio1/view?usp=sharing&amp;resourcekey=0-rkA">
                <div class="flip-entry-title">Rain &amp; Thunder.m4a</div>
              </a>
            </div>
            <div class="flip-entry" id="entry-f2">
              <a href="https://drive.google.com/file/d/f_doc1/view">
                <div class="flip-entry-title">Lyrics.txt</div>
              </a>
            </div>
            <div class="flip-entry" id="entry-sub">
              <a href="https://drive.google.com/drive/folders/sub_embed?usp=sharing">
                <div class="flip-entry-title">Live Encore</div>
              </a>
            </div>
          </body>
        </html>
        """;

        string embeddedSubHtml = """
        <html>
          <body>
            <div class="flip-entry" id="entry-f3">
              <a href="https://drive.google.com/file/d/f_audio2/view">
                <div class="flip-entry-title">Encore.flac</div>
              </a>
            </div>
          </body>
        </html>
        """;

        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/drive/folders/", StringComparison.Ordinal))
            {
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("<html><head><title>Embedded Mix - Google Drive</title></head><body>No ivd here</body></html>", Encoding.UTF8, "text/html")
                };
            }
            if (url.Contains("/embeddedfolderview?id=root_embed", StringComparison.Ordinal))
            {
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(embeddedRootHtml, Encoding.UTF8, "text/html")
                };
            }
            if (url.Contains("/embeddedfolderview?id=sub_embed", StringComparison.Ordinal))
            {
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(embeddedSubHtml, Encoding.UTF8, "text/html")
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new GoogleDriveClient(handler);
        var result = await client.FetchPlaylistAsync("https://drive.google.com/drive/folders/root_embed");

        Assert.Equal("Embedded Mix", result.Title);
        Assert.Equal(2, result.Items.Count);
        Assert.Equal("Rain & Thunder", result.Items[0].Title);
        Assert.Equal("Embedded Mix", result.Items[0].Album);
        Assert.Equal(".m4a", result.Items[0].FileExtension);
        Assert.Contains("0-rkA", result.Items[0].RemoteTrackId);

        Assert.Equal("Encore", result.Items[1].Title);
        Assert.Equal("Live Encore", result.Items[1].Album);
        Assert.Equal(".flac", result.Items[1].FileExtension);
    }

    [Fact]
    public async Task GoogleDriveSingleFileVirusScanFormFallbackAndAccessErrorsWork()
    {
        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/file/d/file_single/view", StringComparison.Ordinal))
            {
                string viewHtml = """
                <html>
                  <head><meta property="og:title" content="Symphony No. 5.flac - Google Drive" /></head>
                  <body></body>
                </html>
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(viewHtml, Encoding.UTF8, "text/html")
                };
            }

            if (url.StartsWith("https://drive.usercontent.google.com/download", StringComparison.Ordinal) &&
                url.Contains("id=file_virus", StringComparison.Ordinal))
            {
                if (url.Contains("uuid=uuid-999", StringComparison.Ordinal))
                {
                    var resp = new HttpResponseMessage(HttpStatusCode.PartialContent)
                    {
                        Content = new ByteArrayContent([0x66, 0x4C, 0x61, 0x43])
                    };
                    resp.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("audio/flac");
                    resp.Content.Headers.ContentDisposition = new System.Net.Http.Headers.ContentDispositionHeaderValue("attachment")
                    {
                        FileName = "\"Big Track.flac\""
                    };
                    return resp;
                }

                string warnHtml = """
                <html><body>
                  <span class="uc-name-size"><a href="#">Big Track.flac</a> (120M)</span>
                  <form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
                    <input type="hidden" name="id" value="file_virus">
                    <input type="hidden" name="export" value="download">
                    <input type="hidden" name="confirm" value="t">
                    <input type="hidden" name="uuid" value="uuid-999">
                    <input type="hidden" name="at" value="at-token">
                    <input type="hidden" name="authuser" value="0">
                    <input type="hidden" name="resourcekey" value="0-rkVirus">
                  </form>
                </body></html>
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(warnHtml, Encoding.UTF8, "text/html")
                };
            }

            if (url.StartsWith("https://drive.usercontent.google.com/download", StringComparison.Ordinal) &&
                url.Contains("id=file_uc_fallback", StringComparison.Ordinal))
            {
                return new HttpResponseMessage(HttpStatusCode.NotFound);
            }

            if (url.StartsWith("https://drive.google.com/uc", StringComparison.Ordinal) &&
                url.Contains("id=file_uc_fallback", StringComparison.Ordinal))
            {
                Assert.True(req.Headers.Range is not null);
                var resp = new HttpResponseMessage(HttpStatusCode.PartialContent)
                {
                    Content = new ByteArrayContent([0xFF, 0xFB])
                };
                resp.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("audio/mpeg");
                return resp;
            }

            if (url.Contains("id=file_signin", StringComparison.Ordinal) ||
                url.Contains("/drive/folders/folder_signin", StringComparison.Ordinal))
            {
                var redirect = new HttpResponseMessage(HttpStatusCode.Found);
                redirect.Headers.Location = new Uri("https://accounts.google.com/v3/signin/identifier");
                return redirect;
            }

            if (url.Contains("/drive/folders/folder_403", StringComparison.Ordinal))
            {
                return new HttpResponseMessage(HttpStatusCode.Forbidden);
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new GoogleDriveClient(handler);

        // 1. Single file listing
        var single = await client.FetchPlaylistAsync("https://drive.google.com/file/d/file_single/view?resourcekey=0-rkSingle");
        Assert.Equal(RootKind.CloudGoogleDrive, single.Kind);
        Assert.Equal("Symphony No. 5", single.Title);
        var singleItem = Assert.Single(single.Items);
        Assert.Equal(".flac", singleItem.FileExtension);
        Assert.Contains("0-rkSingle", singleItem.RemoteTrackId);

        // 2. Virus-scan confirm form with uuid + resourcekey + cookie header
        var virusStream = await client.ResolveAudioStreamAsync("file_virus?resourcekey=0-rkVirus", cookie: "SID=testcookie");
        Assert.Contains("uuid=uuid-999", virusStream.Url);
        Assert.Contains("at=at-token", virusStream.Url);
        Assert.Contains("resourcekey=0-rkVirus", virusStream.Url);
        Assert.Equal(".flac", virusStream.Extension);
        Assert.NotNull(virusStream.Headers);
        Assert.Equal("SID=testcookie", virusStream.Headers!["Cookie"]);

        // 3. Fallback to /uc when drive.usercontent.google.com returns 404
        var ucStream = await client.ResolveAudioStreamAsync("file_uc_fallback");
        Assert.StartsWith("https://drive.google.com/uc", ucStream.Url);
        Assert.Equal(".mp3", ucStream.Extension);

        // 4. Sign-in redirect & 403 error throw clear message
        var signInEx = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            client.FetchPlaylistAsync("https://drive.google.com/drive/folders/folder_signin"));
        Assert.Contains("Anyone with the link", signInEx.Message);

        var streamSignInEx = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            client.ResolveAudioStreamAsync("file_signin"));
        Assert.Contains("Anyone with the link", streamSignInEx.Message);

        var forbiddenEx = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            client.FetchPlaylistAsync("https://drive.google.com/drive/folders/folder_403"));
        Assert.Contains("Anyone with the link", forbiddenEx.Message);
    }

    [Fact]
    public async Task GoogleDriveOpenLinksAutoDetectFolderVsFileAndRejectNonAudioSingleFiles()
    {
        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;

            if (url.Contains("/open?id=open_folder", StringComparison.Ordinal))
            {
                var redirect = new HttpResponseMessage(HttpStatusCode.Found);
                redirect.Headers.Location = new Uri("https://drive.google.com/drive/folders/open_folder");
                return redirect;
            }

            if (url.Contains("/drive/folders/open_folder", StringComparison.Ordinal))
            {
                string ivd = """[["open_track_1",["open_folder"],"Overture.mp3","audio/mpeg"]]"""
                    .Replace("\"", "\\x22").Replace("[", "\\x5b").Replace("]", "\\x5d");
                string html = $"<html><head><title>Open Folder - Google Drive</title></head><body><script>window['_DRIVE_ivd'] = '{ivd}';</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }

            if (url.Contains("/open?id=open_file", StringComparison.Ordinal))
            {
                var redirect = new HttpResponseMessage(HttpStatusCode.Found);
                redirect.Headers.Location = new Uri("https://drive.google.com/file/d/open_file/view");
                return redirect;
            }

            if (url.Contains("/file/d/open_file/view", StringComparison.Ordinal))
            {
                string html = "<html><head><meta property=\"og:title\" content=\"Nocturne.m4a - Google Drive\" /></head></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }

            if (url.Contains("/file/d/doc_pdf/view", StringComparison.Ordinal))
            {
                string html = "<html><head><title>Manual.pdf - Google Drive</title></head></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }

            if (url.Contains("/file/d/doc_mime/view", StringComparison.Ordinal))
            {
                string html = "<html><head><title>DocumentWithoutExtension - Google Drive</title></head></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }

            if (url.StartsWith("https://drive.usercontent.google.com/download", StringComparison.Ordinal) &&
                url.Contains("id=doc_mime", StringComparison.Ordinal))
            {
                var resp = new HttpResponseMessage(HttpStatusCode.PartialContent)
                {
                    Content = new ByteArrayContent(Encoding.ASCII.GetBytes("%PDF-1.4"))
                };
                resp.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/pdf");
                return resp;
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new GoogleDriveClient(handler);

        // /open?id=... resolving to a folder
        var folderResult = await client.FetchPlaylistAsync("https://drive.google.com/open?id=open_folder&resourcekey=0-rkOpen");
        Assert.Equal(RootKind.CloudGoogleDrive, folderResult.Kind);
        Assert.Equal("Open Folder", folderResult.Title);
        var folderTrack = Assert.Single(folderResult.Items);
        Assert.Equal("Overture", folderTrack.Title);
        Assert.Equal(".mp3", folderTrack.FileExtension);

        // /open?id=... resolving to an audio file
        var fileResult = await client.FetchPlaylistAsync("https://drive.google.com/open?id=open_file");
        Assert.Equal(RootKind.CloudGoogleDrive, fileResult.Kind);
        Assert.Equal("Nocturne", fileResult.Title);
        var singleTrack = Assert.Single(fileResult.Items);
        Assert.Equal(".m4a", singleTrack.FileExtension);

        // Non-audio single files must be rejected
        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            client.FetchPlaylistAsync("https://drive.google.com/u/1/file/d/doc_pdf/preview"));
        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            client.FetchPlaylistAsync("https://drive.google.com/file/d/doc_mime/view"));
    }

    [Fact]
    public async Task CloudAudioCacheDownloadsLargeStreamsInParallel4MbRangeChunks()
    {
        const long chunkSize = 4L * 1024L * 1024L;
        const int totalBytes = (int)(4 * chunkSize + 777); // 5 chunks -> exercises 4x parallel worker cap
        byte[] expectedPayload = new byte[totalBytes];
        for (int i = 0; i < totalBytes; i++)
        {
            expectedPayload[i] = (byte)((i * 31 + (i >> 8)) & 0xFF);
        }

        var observedRanges = new ConcurrentBag<(long From, long? To)>();
        int inFlight = 0;
        int maxConcurrent = 0;
        var overlapGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        var handler = new AsyncStubHttpHandler(async (req, ct) =>
        {
            var range = req.Headers.Range?.Ranges.Single();
            Assert.NotNull(range);
            Assert.True(range!.From.HasValue);
            observedRanges.Add((range.From!.Value, range.To));

            // Ensure caller's "Range: bytes=0-" was replaced by bounded chunk range, never duplicated.
            Assert.Single(req.Headers.Range!.Ranges);
            Assert.True(range.To.HasValue);

            int current = Interlocked.Increment(ref inFlight);
            while (true)
            {
                int prev = Volatile.Read(ref maxConcurrent);
                if (current <= prev || Interlocked.CompareExchange(ref maxConcurrent, current, prev) == prev)
                {
                    break;
                }
            }

            try
            {
                if (current >= 4)
                {
                    overlapGate.TrySetResult();
                }
                else
                {
                    // Brief wait so parallel workers overlap cleanly without hanging if fewer than 4 remain.
                    await Task.WhenAny(overlapGate.Task, Task.Delay(100, ct)).ConfigureAwait(false);
                }

                // Force chunk 0 to finish after other chunks to verify offset-exact RandomAccess writes.
                if (range.From.Value == 0)
                {
                    await Task.Delay(30, ct).ConfigureAwait(false);
                }

                int start = (int)range.From.Value;
                int end = (int)range.To!.Value;
                int len = end - start + 1;
                var slice = new byte[len];
                Buffer.BlockCopy(expectedPayload, start, slice, 0, len);

                var resp = new HttpResponseMessage(HttpStatusCode.PartialContent)
                {
                    Content = new ByteArrayContent(slice)
                };
                resp.Content.Headers.ContentType = new MediaTypeHeaderValue("audio/mp4");
                resp.Content.Headers.ContentRange = new ContentRangeHeaderValue(start, end, totalBytes);
                return resp;
            }
            finally
            {
                Interlocked.Decrement(ref inFlight);
            }
        });

        string cacheDir = TestHelpers.MakeTempDir();
        var cache = new CloudAudioCache(cacheDir, maxBytes: 64L * 1024 * 1024, handler: handler);
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["User-Agent"] = "MusicoTest/1.0",
            ["Range"] = "bytes=0-"
        };

        string path = await cache.GetOrDownloadAsync(
            "yt_large_chunked",
            _ => Task.FromResult(new CloudAudioStream("https://cdn.example/large.m4a", ".m4a", headers, totalBytes)));

        Assert.True(File.Exists(path));
        byte[] actual = File.ReadAllBytes(path);
        Assert.Equal(expectedPayload.Length, actual.Length);
        Assert.True(expectedPayload.AsSpan().SequenceEqual(actual));

        Assert.InRange(maxConcurrent, 2, 4);
        var sortedRanges = observedRanges.OrderBy(r => r.From).ToList();
        Assert.Equal(5, sortedRanges.Count);
        Assert.Equal((0L, chunkSize - 1), sortedRanges[0]);
        Assert.Equal((chunkSize, 2 * chunkSize - 1), sortedRanges[1]);
        Assert.Equal((2 * chunkSize, 3 * chunkSize - 1), sortedRanges[2]);
        Assert.Equal((3 * chunkSize, 4 * chunkSize - 1), sortedRanges[3]);
        Assert.Equal((4 * chunkSize, (long)totalBytes - 1), sortedRanges[4]);
        Assert.Empty(Directory.EnumerateFiles(cacheDir, "*.tmp"));
    }

    [Fact]
    public async Task CloudAudioCacheRetriesTransientChunkErrorAndRejectsHtmlChunkWithCleanup()
    {
        const long chunkSize = 4L * 1024L * 1024L;
        const int totalBytes = (int)(chunkSize + 1024); // 2 chunks
        byte[] expectedPayload = new byte[totalBytes];
        for (int i = 0; i < totalBytes; i++)
        {
            expectedPayload[i] = (byte)(i % 251);
        }

        int chunk1Attempts = 0;
        var retryHandler = new StubHttpHandler(req =>
        {
            var range = req.Headers.Range!.Ranges.Single();
            long start = range.From!.Value;
            long end = range.To!.Value;

            if (start == chunkSize && Interlocked.Increment(ref chunk1Attempts) == 1)
            {
                return new HttpResponseMessage(HttpStatusCode.ServiceUnavailable);
            }

            int len = (int)(end - start + 1);
            var slice = new byte[len];
            Buffer.BlockCopy(expectedPayload, (int)start, slice, 0, len);
            var resp = new HttpResponseMessage(HttpStatusCode.PartialContent)
            {
                Content = new ByteArrayContent(slice)
            };
            resp.Content.Headers.ContentType = new MediaTypeHeaderValue("audio/mp4");
            return resp;
        });

        string retryDir = TestHelpers.MakeTempDir();
        var retryCache = new CloudAudioCache(retryDir, maxBytes: 32L * 1024 * 1024, handler: retryHandler);
        string retryPath = await retryCache.GetOrDownloadAsync(
            "yt_retry_chunk",
            _ => Task.FromResult(new CloudAudioStream("https://cdn.example/retry.m4a", ".m4a", ContentLength: totalBytes)));

        Assert.Equal(2, chunk1Attempts);
        Assert.True(expectedPayload.AsSpan().SequenceEqual(File.ReadAllBytes(retryPath)));

        // HTML response on a chunk must fail and clean up any .tmp files
        var htmlHandler = new StubHttpHandler(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent("<html><body>Blocked</body></html>", Encoding.UTF8, "text/html")
        });

        string htmlDir = TestHelpers.MakeTempDir();
        var htmlCache = new CloudAudioCache(htmlDir, maxBytes: 32L * 1024 * 1024, handler: htmlHandler);
        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            htmlCache.GetOrDownloadAsync(
                "yt_html_chunk",
                _ => Task.FromResult(new CloudAudioStream("https://cdn.example/html.m4a", ".m4a", ContentLength: totalBytes))));
        Assert.Empty(Directory.EnumerateFiles(htmlDir, "*.tmp"));
    }

    [Fact]
    public async Task CloudAudioCachePropagatesCancellationAndRespectsCoalescedWaiters()
    {
        const long totalBytes = 5L * 1024L * 1024L;
        var chunkStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var observedDownloadCancel = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        var cancelHandler = new AsyncStubHttpHandler(async (_, ct) =>
        {
            chunkStarted.TrySetResult();
            using var reg = ct.Register(() => observedDownloadCancel.TrySetResult());
            await Task.Delay(Timeout.Infinite, ct).ConfigureAwait(false);
            return new HttpResponseMessage(HttpStatusCode.PartialContent);
        });

        string cancelDir = TestHelpers.MakeTempDir();
        var cancelCache = new CloudAudioCache(cancelDir, maxBytes: 32L * 1024 * 1024, handler: cancelHandler);
        using var soleCts = new CancellationTokenSource();

        var soleTask = cancelCache.GetOrDownloadAsync(
            "yt_cancel_sole",
            _ => Task.FromResult(new CloudAudioStream("https://cdn.example/slow.m4a", ".m4a", ContentLength: totalBytes)),
            ct: soleCts.Token);

        await chunkStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));
        soleCts.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => soleTask);
        await observedDownloadCancel.Task.WaitAsync(TimeSpan.FromSeconds(5));
        for (int i = 0; i < 40 && Directory.EnumerateFiles(cancelDir, "*.tmp").Any(); i++)
        {
            await Task.Delay(25);
        }
        Assert.Empty(Directory.EnumerateFiles(cancelDir, "*.tmp"));

        // Coalesced: cancelling waiter 1 must NOT abort the download for waiter 2
        byte[] smallPayload = Encoding.UTF8.GetBytes("coalesced-audio-ok");
        var releaseGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var downloadStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        int httpCalls = 0;

        var coalescedHandler = new AsyncStubHttpHandler(async (_, ct) =>
        {
            Interlocked.Increment(ref httpCalls);
            downloadStarted.TrySetResult();
            await releaseGate.Task.WaitAsync(ct).ConfigureAwait(false);
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent(smallPayload)
            };
        });

        string coalescedDir = TestHelpers.MakeTempDir();
        var coalescedCache = new CloudAudioCache(coalescedDir, maxBytes: 32L * 1024 * 1024, handler: coalescedHandler);
        using var waiter1Cts = new CancellationTokenSource();
        using var waiter2Cts = new CancellationTokenSource();

        var waiter1 = coalescedCache.GetOrDownloadAsync(
            "yt_coalesced",
            _ => Task.FromResult(new CloudAudioStream("https://cdn.example/small.m4a", ".m4a")),
            ct: waiter1Cts.Token);
        var waiter2 = coalescedCache.GetOrDownloadAsync(
            "yt_coalesced",
            _ => Task.FromResult(new CloudAudioStream("https://cdn.example/small.m4a", ".m4a")),
            ct: waiter2Cts.Token);

        await downloadStarted.Task.WaitAsync(TimeSpan.FromSeconds(5));
        waiter1Cts.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => waiter1);

        releaseGate.TrySetResult();
        string completedPath = await waiter2;
        Assert.Equal(1, httpCalls);
        Assert.Equal(smallPayload, File.ReadAllBytes(completedPath));
    }

    [Fact]
    public void DownloadSpeedMeterComputesRollingWindowSpeedThrottlesAndRollsBackRetries()
    {
        double clock = 0.0;
        var meter = new DownloadSpeedMeter(totalBytes: 1000, clockSeconds: () => clock, throttleSeconds: 0.12);

        // First write at t=0.05s is throttled (< 0.12s since start).
        clock = 0.05;
        Assert.Null(meter.RecordBytes(200));

        // At t=0.20s (>= 0.12s), emission triggers: 400 bytes over 0.20s window => 2000 B/s.
        clock = 0.20;
        var p1 = meter.RecordBytes(200);
        Assert.NotNull(p1);
        Assert.Equal(400, p1!.BytesDownloaded);
        Assert.Equal(1000, p1.TotalBytes);
        Assert.Equal(0.4, p1.FractionCompleted!.Value, precision: 3);
        Assert.Equal(2000.0, p1.BytesPerSecond, precision: 1);

        // Rollback retry bytes (e.g., failed chunk) subtracts both total & window bytes cleanly.
        meter.RollbackBytes(200);
        clock = 2.0; // Old sample at t=0.05s is outside 1.5s rolling window [0.5, 2.0]; sample at t=0.20s is also pruned!
        var p2 = meter.RecordBytes(600, forceEmit: true);
        Assert.NotNull(p2);
        Assert.Equal(800, p2!.BytesDownloaded);
        Assert.Equal(600.0 / 1.5, p2.BytesPerSecond, precision: 1);

        var done = meter.Complete();
        Assert.Equal(800, done.BytesDownloaded);
        Assert.Equal(1000, done.TotalBytes);
        Assert.Equal(0.8, done.FractionCompleted!.Value, precision: 3);

        // Unknown-total meter populates TotalBytes upon Complete().
        double clock2 = 0.0;
        var unknownMeter = new DownloadSpeedMeter(totalBytes: null, clockSeconds: () => clock2, throttleSeconds: 0.12);
        clock2 = 0.5;
        unknownMeter.RecordBytes(512, forceEmit: true);
        var unknownDone = unknownMeter.Complete();
        Assert.Equal(512, unknownDone.TotalBytes);
        Assert.Equal(1.0, unknownDone.FractionCompleted);
    }

    [Fact]
    public async Task CloudAudioCacheEmitsLiveProgressAndMulticastsToCoalescedWaiters()
    {
        byte[] payload = new byte[200_000];
        Array.Fill(payload, (byte)0x42);

        var releaseGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        int singleRequests = 0;

        var singleHandler = new AsyncStubHttpHandler(async (req, ct) =>
        {
            string uri = req.RequestUri!.AbsoluteUri;
            if (uri == "https://cdn.example/progress.m4a")
            {
                Interlocked.Increment(ref singleRequests);
                started.TrySetResult();
                await releaseGate.Task.WaitAsync(ct).ConfigureAwait(false);
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(payload)
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        string dir = TestHelpers.MakeTempDir();
        var cache = new CloudAudioCache(dir, maxBytes: 32L * 1024 * 1024, handler: singleHandler);
        var waiter1Events = new List<CloudDownloadProgress>();
        var waiter2Events = new List<CloudDownloadProgress>();

        var t1 = cache.GetOrDownloadAsync(
            "yt_prog_single",
            _ => Task.FromResult(new CloudAudioStream("https://cdn.example/progress.m4a", ".m4a")),
            onProgress: p => { lock (waiter1Events) waiter1Events.Add(p); });
        var t2 = cache.GetOrDownloadAsync(
            "yt_prog_single",
            _ => Task.FromResult(new CloudAudioStream("https://cdn.example/progress.m4a", ".m4a")),
            onProgress: p => { lock (waiter2Events) waiter2Events.Add(p); });

        await started.Task.WaitAsync(TimeSpan.FromSeconds(5));
        releaseGate.TrySetResult();
        var paths = await Task.WhenAll(t1, t2);

        Assert.Equal(1, singleRequests);
        Assert.Equal(paths[0], paths[1]);
        Assert.NotEmpty(waiter1Events);
        Assert.NotEmpty(waiter2Events);
        Assert.Equal(payload.Length, waiter1Events[^1].BytesDownloaded);
        Assert.Equal(payload.Length, waiter2Events[^1].BytesDownloaded);
        Assert.Equal(1.0, waiter1Events[^1].FractionCompleted);
        Assert.Equal(1.0, waiter2Events[^1].FractionCompleted);

        // Chunked parallel download (>= 4 MB) also emits live byte progress up to full length.
        byte[] largePayload = new byte[CloudAudioCache.ChunkSizeBytes + 32_768];
        Array.Fill(largePayload, (byte)0x7C);
        var chunkHandler = new StubHttpHandler(req =>
        {
            if (req.RequestUri!.AbsoluteUri == "https://cdn.example/large_chunks.m4a")
            {
                var range = req.Headers.Range?.Ranges.FirstOrDefault();
                if (range is not null)
                {
                    long from = range.From ?? 0;
                    long to = Math.Min(largePayload.Length - 1, range.To ?? (largePayload.Length - 1));
                    int sliceLen = (int)(to - from + 1);
                    var slice = new byte[sliceLen];
                    Buffer.BlockCopy(largePayload, (int)from, slice, 0, sliceLen);
                    var msg = new HttpResponseMessage(HttpStatusCode.PartialContent)
                    {
                        Content = new ByteArrayContent(slice)
                    };
                    msg.Content.Headers.ContentRange = new ContentRangeHeaderValue(from, to, largePayload.Length);
                    return msg;
                }
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        string chunkDir = TestHelpers.MakeTempDir();
        var chunkCache = new CloudAudioCache(chunkDir, maxBytes: 64L * 1024 * 1024, handler: chunkHandler);
        var chunkProgress = new List<CloudDownloadProgress>();
        string chunkPath = await chunkCache.GetOrDownloadAsync(
            "yt_prog_chunked",
            _ => Task.FromResult(new CloudAudioStream("https://cdn.example/large_chunks.m4a", ".m4a", ContentLength: largePayload.Length)),
            onProgress: p => { lock (chunkProgress) chunkProgress.Add(p); });

        Assert.Equal(largePayload, File.ReadAllBytes(chunkPath));
        Assert.NotEmpty(chunkProgress);
        Assert.Equal(largePayload.Length, chunkProgress[^1].BytesDownloaded);
        Assert.Equal(largePayload.Length, chunkProgress[^1].TotalBytes);
        Assert.Equal(1.0, chunkProgress[^1].FractionCompleted);
    }

    [Fact]
    public async Task GoogleDriveClientPaginatesDriveIvdApiKeyV2BetaWithV3FallbackAndRetry()
    {
        const string fakeApiKey = "AIzaSyA1B2C3D4E5F6G7H8I9J0K1L2M3N4O5P6Q";
        int v2betaCalls = 0;

        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.StartsWith("https://drive.google.com/drive/folders/gdPagedRoot", StringComparison.Ordinal))
            {
                string ivdJson = """[[["gd_p1_f1",["gdPagedRoot"],"01 Root Song.mp3","audio/mpeg"]],"tok_page_2"]""";
                string html = $"<html><head><title>Large Mix - Google Drive</title></head><body><script>window['_DRIVE_ivd'] = '{ivdJson}'; var key = \"{fakeApiKey}\";</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(html, Encoding.UTF8, "text/html")
                };
            }

            if (url.Contains("clients6.google.com/drive/v2beta/files", StringComparison.Ordinal))
            {
                int call = Interlocked.Increment(ref v2betaCalls);
                Assert.True(req.Headers.TryGetValues("X-Goog-Api-Key", out var apiKeys) && apiKeys.Contains(fakeApiKey));
                Assert.True(req.Headers.TryGetValues("Origin", out var origins) && origins.Contains("https://drive.google.com"));

                if (url.Contains("pageToken=tok_page_2", StringComparison.Ordinal))
                {
                    // Transient 503 then 429 before 200 OK on page 2.
                    if (call == 1) return new HttpResponseMessage(HttpStatusCode.ServiceUnavailable);
                    if (call == 2) return new HttpResponseMessage((HttpStatusCode)429);

                    string v2Json = """
                    {
                      "nextPageToken": "tok_page_3",
                      "items": [
                        { "id": "gd_p2_f1", "title": "02 Second Page.flac", "mimeType": "audio/flac" }
                      ]
                    }
                    """;
                    return new HttpResponseMessage(HttpStatusCode.OK)
                    {
                        Content = new StringContent(v2Json, Encoding.UTF8, "application/json")
                    };
                }

                if (url.Contains("pageToken=tok_page_3", StringComparison.Ordinal))
                {
                    // Trigger fallback to drive/v3/files for page 3.
                    return new HttpResponseMessage(HttpStatusCode.Forbidden);
                }
            }

            if (url.Contains("googleapis.com/drive/v3/files", StringComparison.Ordinal) &&
                url.Contains("pageToken=tok_page_3", StringComparison.Ordinal))
            {
                string v3Json = """
                {
                  "files": [
                    { "id": "gd_p3_f1", "name": "03 Third Page.opus", "mimeType": "audio/opus" }
                  ]
                }
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(v3Json, Encoding.UTF8, "application/json")
                };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new GoogleDriveClient(handler);
        var result = await client.FetchPlaylistAsync("https://drive.google.com/drive/folders/gdPagedRoot");

        Assert.Equal("Large Mix", result.Title);
        Assert.Equal(3, result.Items.Count);
        Assert.Equal(["gd_p1_f1", "gd_p2_f1", "gd_p3_f1"], result.Items.Select(i => i.RemoteTrackId).ToArray());
        Assert.DoesNotContain(handler.Requests, r => r.RequestUri!.AbsoluteUri.Contains("embeddedfolderview", StringComparison.Ordinal));
    }

    [Fact]
    public async Task GoogleDriveClientSkipsRedundantEmbeddedFolderViewWhenDriveIvdValidlyEmpty()
    {
        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.StartsWith("https://drive.google.com/drive/folders/gdEmptyRoot", StringComparison.Ordinal))
            {
                string html = "<html><head><title>Empty Folder - Google Drive</title></head><body><script>window['_DRIVE_ivd'] = '[[], null]';</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(html, Encoding.UTF8, "text/html")
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new GoogleDriveClient(handler);
        var single = await client.FetchSingleFolderAsync("gdEmptyRoot");

        Assert.Empty(single.Entries);
        Assert.Single(handler.Requests);
        Assert.DoesNotContain(handler.Requests, r => r.RequestUri!.AbsoluteUri.Contains("embeddedfolderview", StringComparison.Ordinal));
    }

    [Fact]
    public async Task GoogleDriveClientPreservesHierarchyDisambiguatesDuplicatesAndExpandsAudioExtensions()
    {
        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/drive/folders/gdHierRoot", StringComparison.Ordinal))
            {
                string ivd = """
                [[
                  ["gd_sub_albums",["gdHierRoot"],"Albums","application/vnd.google-apps.folder"],
                  ["gd_ext_mp4",["gdHierRoot"],"Clip.mp4","video/mp4"],
                  ["gd_ext_ogg",["gdHierRoot"],"Vorbis.ogg","application/ogg"],
                  ["gd_ext_opus",["gdHierRoot"],"Opus.opus","audio/opus"],
                  ["gd_ext_webm",["gdHierRoot"],"WebAudio.webm","audio/webm"],
                  ["gd_ext_wma",["gdHierRoot"],"WinMedia.wma","audio/x-ms-wma"],
                  ["gd_ext_alac",["gdHierRoot"],"Lossless.alac","audio/alac"],
                  ["gd_ext_caf",["gdHierRoot"],"CoreAudio.caf","audio/x-caf"],
                  ["gd_skip_txt",["gdHierRoot"],"readme.txt","text/plain"]
                ]]
                """;
                string html = $"<html><head><title>Hierarchy Root - Google Drive</title></head><body><script>window['_DRIVE_ivd'] = {ivd};</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }

            if (url.Contains("/drive/folders/gd_sub_albums", StringComparison.Ordinal))
            {
                string ivd = """[[["gd_sub_cd1",["gd_sub_albums"],"CD 1","application/vnd.google-apps.folder"]]]""";
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent($"<html><body><script>window['_DRIVE_ivd'] = '{ivd}';</script></body></html>", Encoding.UTF8, "text/html")
                };
            }

            if (url.Contains("/drive/folders/gd_sub_cd1", StringComparison.Ordinal))
            {
                string ivd = """[[["gd_dup_aaa11111",["gd_sub_cd1"],"Duplicate.mp3","audio/mpeg"],["gd_dup_bbb22222",["gd_sub_cd1"],"Duplicate.mp3","audio/mpeg"]]]""";
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent($"<html><body><script>window['_DRIVE_ivd'] = '{ivd}';</script></body></html>", Encoding.UTF8, "text/html")
                };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new GoogleDriveClient(handler);
        var result = await client.FetchPlaylistAsync("https://drive.google.com/drive/folders/gdHierRoot");

        Assert.Equal(9, result.Items.Count);
        Assert.DoesNotContain(result.Items, i => i.RemoteTrackId == "gd_skip_txt");
        Assert.Equal(
            [".mp4", ".ogg", ".opus", ".webm", ".wma", ".alac", ".caf"],
            result.Items.Where(i => i.RelativePath is not null && !i.RelativePath.Contains('/'))
                .Select(i => i.FileExtension ?? string.Empty)
                .ToArray());

        var dups = result.Items.Where(i => i.RemoteTrackId.StartsWith("gd_dup_", StringComparison.Ordinal)).ToList();
        Assert.Equal(2, dups.Count);
        Assert.Contains(dups, d => d.RelativePath == "Albums/CD 1/Duplicate.mp3");
        Assert.Contains(dups, d => d.RelativePath == "Albums/CD 1/Duplicate (gd_dup_b).mp3");
        Assert.All(dups, d => Assert.Equal("CD 1", d.Album));
    }

    [Fact]
    public async Task GoogleDriveClientProgressiveTraversalPrioritizesRequestedSubfolder()
    {
        var subAGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var batches = new ConcurrentQueue<GoogleDriveFolderBatch>();

        var handler = new AsyncStubHttpHandler(async (req, ct) =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/drive/folders/gdProgRoot", StringComparison.Ordinal))
            {
                string ivd = """
                [[
                  ["gd_suba",["gdProgRoot"],"SubA","application/vnd.google-apps.folder"],
                  ["gd_subb",["gdProgRoot"],"SubB","application/vnd.google-apps.folder"],
                  ["gd_r1",["gdProgRoot"],"Intro.mp3","audio/mpeg"]
                ]]
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent($"<html><head><title>Prog Root - Google Drive</title></head><body><script>window['_DRIVE_ivd'] = {ivd};</script></body></html>", Encoding.UTF8, "text/html")
                };
            }

            if (url.Contains("/drive/folders/gd_suba", StringComparison.Ordinal))
            {
                await subAGate.Task.WaitAsync(ct).ConfigureAwait(false);
                string ivd = """[[["gd_a1",["gd_suba"],"TrackA.mp3","audio/mpeg"]]]""";
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent($"<html><body><script>window['_DRIVE_ivd'] = '{ivd}';</script></body></html>", Encoding.UTF8, "text/html")
                };
            }

            if (url.Contains("/drive/folders/gd_subb", StringComparison.Ordinal))
            {
                string ivd = """[[["gd_b1",["gd_subb"],"TrackB.mp3","audio/mpeg"]]]""";
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent($"<html><body><script>window['_DRIVE_ivd'] = '{ivd}';</script></body></html>", Encoding.UTF8, "text/html")
                };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new GoogleDriveClient(handler);
        using var session = await client.StartProgressiveFolderFetchAsync("https://drive.google.com/drive/folders/gdProgRoot");

        Assert.True(session.HasMoreSubfolders);
        Assert.Equal(["SubA", "SubB"], session.DiscoveredSubfolderPaths);
        Assert.Single(session.RootResult.Items);
        Assert.Equal("gd_r1", session.RootResult.Items[0].RemoteTrackId);

        session.TraversalHandle.PrioritizeFolder("SubB");
        var continueTask = session.ContinueRemainingAsync(b => batches.Enqueue(b));

        for (int i = 0; i < 100 && !batches.Any(b => b.FolderRelativePath == "SubB"); i++)
        {
            await Task.Delay(20);
        }
        Assert.Contains(batches, b => b.FolderRelativePath == "SubB" && b.Items.Count == 1 && b.Items[0].RelativePath == "SubB/TrackB.mp3");

        subAGate.TrySetResult();
        var full = await continueTask.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal(3, full.Items.Count);
        Assert.Contains(full.Items, i => i.RelativePath == "SubA/TrackA.mp3");
        Assert.Contains(full.Items, i => i.RelativePath == "SubB/TrackB.mp3");
    }

    [Fact]
    public async Task GoogleDriveClientAcceptsPublicServiceLoginHeaderAndXFlacMime()
    {
        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/drive/folders/gdPubHeader", StringComparison.Ordinal))
            {
                string ivd = """[[["gd_xf_1",["gdPubHeader"],"Untitled Movement","application/x-flac"]]]""";
                string html = $$"""
                <html>
                  <head><title>Shared Classical - Google Drive</title></head>
                  <body>
                    <a href="https://accounts.google.com/ServiceLogin?continue=https://drive.google.com/drive/folders/gdPubHeader">Sign in</a>
                    <script>window['_DRIVE_ivd'] = '{{ivd}}';</script>
                  </body>
                </html>
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(html, Encoding.UTF8, "text/html")
                };
            }

            if (url.Contains("/drive/folders/gdPrivLogin", StringComparison.Ordinal))
            {
                string privHtml = """
                <html>
                  <head><title>Sign in - Google Accounts</title></head>
                  <body><a href="https://accounts.google.com/ServiceLogin?continue=1">Sign in</a></body>
                </html>
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(privHtml, Encoding.UTF8, "text/html")
                };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new GoogleDriveClient(handler);
        var pubResult = await client.FetchPlaylistAsync("https://drive.google.com/drive/folders/gdPubHeader");
        Assert.Equal("Shared Classical", pubResult.Title);
        Assert.Single(pubResult.Items);
        Assert.Equal(".flac", pubResult.Items[0].FileExtension);
        Assert.True(GoogleDriveClient.IsAudioEntry("NoExt", "application/x-flac"));

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            client.FetchPlaylistAsync("https://drive.google.com/drive/folders/gdPrivLogin"));
    }

    [Fact]
    public async Task GoogleDriveClientMultiKeyFallbackCachingAndFiftyItemTruncationPagination()
    {
        const string keyBad400 = "AIzaSyBadFirstPartyKey00000000000000001";
        const string keyBad403 = "AIzaSyDeniedSecondKey000000000000000002";
        const string keyGood = "AIzaSyWorkingThirdKey000000000000000003";

        string sampleHtml = $"<script>var a=\"{keyBad400}\", b=\"{keyBad403}\", c=\"{keyGood}\", dup=\"{keyBad400}\";</script>";
        Assert.Equal([keyBad400, keyBad403, keyGood], GoogleDriveClient.ExtractApiKeys(sampleHtml));

        string fiftyIvdRows = string.Join(",", Enumerable.Range(1, 50).Select(i => $"[\"gd_r_{i}\",[\"gd50Root\"],\"Song {i}.mp3\",\"audio/mpeg\"]"));
        string subIvdRows = string.Join(",", Enumerable.Range(1, 50).Select(i => $"[\"gd_s_{i}\",[\"gd50Sub\"],\"Sub {i}\",\"application/x-flac\"]"));

        var requestedKeys = new List<string>();
        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/drive/folders/gd50Root", StringComparison.Ordinal))
            {
                string html = $"<html><head><title>Large 50 - Google Drive</title></head><body>{sampleHtml}<script>window['_DRIVE_ivd'] = '[[{fiftyIvdRows}],null]';</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }

            if (url.Contains("/drive/folders/gd50Sub", StringComparison.Ordinal))
            {
                string html = $"<html><head><title>Sub 50 - Google Drive</title></head><body><script>var a=\"{keyBad400}\", c=\"{keyGood}\"; window['_DRIVE_ivd'] = '[[{subIvdRows}],null]';</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }

            if (url.Contains("clients6.google.com/drive/v2beta/files", StringComparison.Ordinal))
            {
                string key = req.Headers.TryGetValues("X-Goog-Api-Key", out var vals) ? vals.First() : string.Empty;
                lock (requestedKeys) requestedKeys.Add(key);

                if (key == keyBad400) return new HttpResponseMessage(HttpStatusCode.BadRequest);
                if (key == keyBad403) return new HttpResponseMessage(HttpStatusCode.Forbidden);

                if (url.Contains("gd50Root", StringComparison.Ordinal))
                {
                    if (!url.Contains("pageToken=", StringComparison.Ordinal))
                    {
                        string pageItems = string.Join(",", Enumerable.Range(1, 51).Select(i => $"{{\"id\":\"gd_r_{i}\",\"title\":\"Song {i}.mp3\",\"mimeType\":\"audio/mpeg\"}}"));
                        return new HttpResponseMessage(HttpStatusCode.OK)
                        {
                            Content = new StringContent($"{{\"nextPageToken\":\"tok_2\",\"items\":[{pageItems}]}}", Encoding.UTF8, "application/json")
                        };
                    }
                    Assert.Contains("pageToken=tok_2", url);
                    return new HttpResponseMessage(HttpStatusCode.OK)
                    {
                        Content = new StringContent("""{"items":[{"id":"gd_r_52","title":"Song 52.mp3","mimeType":"audio/mpeg"}]}""", Encoding.UTF8, "application/json")
                    };
                }

                if (url.Contains("gd50Sub", StringComparison.Ordinal))
                {
                    Assert.DoesNotContain("pageToken=", url);
                    string pageItems = string.Join(",", Enumerable.Range(1, 51).Select(i => $"{{\"id\":\"gd_s_{i}\",\"title\":\"Sub {i}\",\"mimeType\":\"application/x-flac\"}}"));
                    return new HttpResponseMessage(HttpStatusCode.OK)
                    {
                        Content = new StringContent($"{{\"items\":[{pageItems}]}}", Encoding.UTF8, "application/json")
                    };
                }
            }

            if (url.Contains("googleapis.com/drive/v3/files", StringComparison.Ordinal))
            {
                return new HttpResponseMessage(HttpStatusCode.Forbidden);
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new GoogleDriveClient(handler);
        var rootRes = await client.FetchPlaylistAsync("https://drive.google.com/drive/folders/gd50Root");
        Assert.Equal(52, rootRes.Items.Count);
        Assert.Contains(rootRes.Items, i => i.RemoteTrackId == "gd_r_52");

        int countBeforeSub = requestedKeys.Count;
        var subRes = await client.FetchPlaylistAsync("https://drive.google.com/drive/folders/gd50Sub");
        Assert.Equal(51, subRes.Items.Count);
        Assert.Equal(".flac", subRes.Items[^1].FileExtension);
        Assert.Equal(keyGood, requestedKeys[countBeforeSub]);

        // Fallback to embeddedfolderview when all keys fail on a 50-item IVD folder.
        var fallbackHandler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/drive/folders/gd50Emb", StringComparison.Ordinal))
            {
                string html = $"<html><head><title>Emb 50 - Google Drive</title></head><body><script>var k=\"{keyBad403}\"; window['_DRIVE_ivd'] = '[[{fiftyIvdRows}],null]';</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(html, Encoding.UTF8, "text/html") };
            }
            if (url.Contains("v2beta/files", StringComparison.Ordinal) || url.Contains("v3/files", StringComparison.Ordinal))
            {
                return new HttpResponseMessage(HttpStatusCode.Forbidden);
            }
            if (url.Contains("/embeddedfolderview", StringComparison.Ordinal))
            {
                string emb = """
                <html><body>
                  <div class="flip-entry"><a href="https://drive.google.com/file/d/gd_r_1/view"><div class="flip-entry-title">Song 1.mp3</div></a></div>
                  <div class="flip-entry"><a href="https://drive.google.com/file/d/gd_r_53/view"><div class="flip-entry-title">Song 53.mp3</div></a></div>
                </body></html>
                """;
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(emb, Encoding.UTF8, "text/html") };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var embRes = await new GoogleDriveClient(fallbackHandler).FetchPlaylistAsync("https://drive.google.com/drive/folders/gd50Emb");
        Assert.Equal(51, embRes.Items.Count);
        Assert.Contains(embRes.Items, i => i.RemoteTrackId == "gd_r_53");
    }
}
