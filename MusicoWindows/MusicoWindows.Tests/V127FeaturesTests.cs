using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using MusicoWindows.Audio;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class V127FeaturesTests : IDisposable
{
    private readonly List<string> _tempDirs = new();

    public void Dispose()
    {
        foreach (string d in _tempDirs)
        {
            try { if (Directory.Exists(d)) Directory.Delete(d, recursive: true); } catch { }
        }
    }

    private string NewTempDir()
    {
        string d = TestHelpers.MakeTempDir();
        _tempDirs.Add(d);
        return d;
    }

    private sealed class StubHttpHandler(
        Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> responder) : HttpMessageHandler
    {
        public List<HttpRequestMessage> Requests { get; } = new();

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Requests.Add(request);
            return responder(request, cancellationToken);
        }
    }

    private sealed class FakeAudioEngine : IAudioEngine
    {
        public float Volume { get; set; } = 1.0f;
        public double CurrentTimeSeconds { get; private set; }
        public double DurationSeconds { get; private set; }
        public bool IsPlaying { get; private set; }
        public event Action? TrackEnded;
        public void RaiseTrackEnded() => TrackEnded?.Invoke();
        public List<string> LoadedPaths { get; } = new();
        public List<double> SeekCalls { get; } = new();

        public void Load(string path)
        {
            LoadedPaths.Add(path);
            DurationSeconds = 120.0;
            CurrentTimeSeconds = 0.0;
            IsPlaying = false;
        }

        public void Play() => IsPlaying = true;
        public void Pause() => IsPlaying = false;
        public void Seek(double seconds)
        {
            CurrentTimeSeconds = Math.Clamp(seconds, 0, DurationSeconds);
            SeekCalls.Add(CurrentTimeSeconds);
        }
        public void Unload()
        {
            IsPlaying = false;
            DurationSeconds = 0;
            CurrentTimeSeconds = 0;
        }
        public void ApplyEq(EqSettings settings) { }
        public void Dispose() => Unload();
    }

    private static byte[] MakeMinimalMp3Bytes()
    {
        byte[] frame = new byte[417];
        frame[0] = 0xFF;
        frame[1] = 0xFB;
        frame[2] = 0x90;
        frame[3] = 0x00;
        byte[] result = new byte[frame.Length * 4];
        for (int i = 0; i < 4; i++) Array.Copy(frame, 0, result, i * frame.Length, frame.Length);
        return result;
    }

    private static byte[] MakeExtensionZipBytes(
        string id,
        string version,
        string[] capabilities,
        string[] allowedHosts,
        string[]? urlPatterns = null,
        object[]? qualities = null,
        string? defaultStreamQuality = null,
        string? defaultDownloadQuality = null,
        object[]? settings = null,
        string? script = null)
    {
        var manifest = new Dictionary<string, object?>
        {
            ["apiVersion"] = ExtensionManifest.CurrentApiVersion,
            ["id"] = id,
            ["name"] = $"Ext {id}",
            ["version"] = version,
            ["entry"] = "index.js",
            ["capabilities"] = capabilities,
            ["permissions"] = new { hosts = allowedHosts },
            ["urlPatterns"] = urlPatterns ?? ["^https://music\\.example\\.test/playlist/"]
        };
        if (qualities is not null) manifest["qualities"] = qualities;
        if (defaultStreamQuality is not null) manifest["defaultStreamQuality"] = defaultStreamQuality;
        if (defaultDownloadQuality is not null) manifest["defaultDownloadQuality"] = defaultDownloadQuality;
        if (settings is not null) manifest["settings"] = settings;

        string finalScript = script ?? """
            exports.canHandle = () => ({ matched: false });
            exports.fetchCollection = async () => ({ remoteId: 'x', title: 'X', tracks: [] });
            exports.search = async () => ({ items: [] });
            """;

        using var ms = new MemoryStream();
        using (var zip = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
        {
            var mEntry = zip.CreateEntry("manifest.json");
            using (var sw = new StreamWriter(mEntry.Open(), new UTF8Encoding(false)))
                sw.Write(JsonSerializer.Serialize(manifest));
            var jEntry = zip.CreateEntry("index.js");
            using (var sw = new StreamWriter(jEntry.Open(), new UTF8Encoding(false)))
                sw.Write(finalScript);
        }
        return ms.ToArray();
    }

    private static InstalledExtension InstallAndEnable(ExtensionStore store, byte[] zipBytes)
    {
        var pkg = ExtensionPackageLoader.LoadFromBytes(zipBytes, "ext.musicoext");
        var staged = store.StageImportedPackage(pkg);
        store.ApproveAndEnable(staged.Id);
        return store.GetById(staged.Id)!;
    }

    // ==================== Feature A1: Manifest Qualities & JS Runtime ====================

    [Fact]
    public void ExtensionManifestValidatesQualitiesCountUniquenessAndDefaults()
    {
        byte[] validZip = MakeExtensionZipBytes(
            "com.test.qualvalid",
            "1.0.0",
            ["playlist"],
            ["api.example.test"],
            qualities:
            [
                new { id = "flac", label = "Lossless", badge = "FLAC", lossless = true },
                new { id = "standard", label = "Standard", badge = "192k" }
            ],
            defaultStreamQuality: "standard",
            defaultDownloadQuality: "flac");

        var pkg = ExtensionPackageLoader.LoadFromBytes(validZip, "valid.musicoext");
        Assert.Equal(2, pkg.Manifest.EffectiveQualities.Count);
        Assert.Equal("standard", pkg.Manifest.EffectiveDefaultStreamQualityId);
        Assert.Equal("flac", pkg.Manifest.EffectiveDefaultDownloadQualityId);
        Assert.True(pkg.Manifest.EffectiveQualities[0].Lossless);

        // Duplicate quality ids (case-insensitive)
        byte[] dupZip = MakeExtensionZipBytes(
            "com.test.qualdup",
            "1.0.0",
            ["playlist"],
            ["api.example.test"],
            qualities:
            [
                new { id = "high", label = "High" },
                new { id = "HIGH", label = "High 2" }
            ]);
        Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadFromBytes(dupZip, "dup.musicoext"));

        // More than 16 qualities
        var tooMany = Enumerable.Range(1, 17).Select(i => (object)new { id = $"q{i}", label = $"Q{i}" }).ToArray();
        byte[] overLimitZip = MakeExtensionZipBytes(
            "com.test.qualmany",
            "1.0.0",
            ["playlist"],
            ["api.example.test"],
            qualities: tooMany);
        Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadFromBytes(overLimitZip, "many.musicoext"));

        // Invalid defaultStreamQuality
        byte[] badDefaultStreamZip = MakeExtensionZipBytes(
            "com.test.qualbads",
            "1.0.0",
            ["playlist"],
            ["api.example.test"],
            qualities: [new { id = "high", label = "High" }],
            defaultStreamQuality: "lossless");
        Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadFromBytes(badDefaultStreamZip, "bads.musicoext"));

        // Invalid defaultDownloadQuality
        byte[] badDefaultDownloadZip = MakeExtensionZipBytes(
            "com.test.qualbadd",
            "1.0.0",
            ["playlist"],
            ["api.example.test"],
            qualities: [new { id = "high", label = "High" }],
            defaultDownloadQuality: "ultra");
        Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadFromBytes(badDefaultDownloadZip, "badd.musicoext"));
    }

    [Fact]
    public async Task JsExtensionProviderPassesQualityAndPurposeParsesStreamQualityMetadataAndPreservesServerUrl()
    {
        string stateDir = NewTempDir();
        string script = """
            exports.canHandle = (input) => ({ matched: true, normalizedUrl: input.extractedUrl, remoteId: "c1" });
            exports.fetchCollection = async (ctx, req) => ({
                remoteId: "c1",
                title: "Ext Album",
                tracks: [{ remoteTrackId: "t1", title: "Track 1", artist: "Art", trackNumber: 1 }]
            });
            exports.resolveStream = async (ctx, req) => {
                const base = String(ctx.settings.get('server_url') || 'https://custom.example.test').replace(/\/+$/, '');
                await ctx.http.request({ method: 'GET', url: base + '/api/q' });
                return {
                    url: `https://cdn.example.test/${req.quality}_${req.purpose}.flac`,
                    extension: req.quality === "flac" ? ".flac" : ".m4a",
                    actualQuality: req.quality,
                    qualityLabel: req.quality === "flac" ? "FLAC" : "320k",
                    bitrateKbps: req.quality === "flac" ? 960 : 320
                };
            };
            """;

        byte[] zip = MakeExtensionZipBytes(
            "com.test.extq",
            "1.0.0",
            ["playlist", "stream"],
            ["cdn.example.test"],
            qualities:
            [
                new { id = "flac", label = "Lossless", badge = "FLAC", lossless = true },
                new { id = "high", label = "High", badge = "320k" }
            ],
            defaultStreamQuality: "high",
            defaultDownloadQuality: "flac",
            settings:
            [
                new { key = "server_url", label = "Server URL", type = "server_url", @default = "https://my.nas.local:4533" }
            ],
            script: script);

        var handler = new StubHttpHandler((req, _) =>
        {
            if (req.RequestUri!.AbsoluteUri == "https://my.nas.local:4533/api/q")
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("{\"ok\":true}", Encoding.UTF8, "application/json")
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        var store = new ExtensionStore(stateDir, handler);
        var installed = InstallAndEnable(store, zip);
        var provider = new JsExtensionCloudProvider(installed, store, store.Runtime);

        Assert.Equal(2, provider.AvailableQualities.Count);
        Assert.Equal("high", provider.DefaultStreamQualityId);
        Assert.Equal("flac", provider.DefaultDownloadQualityId);

        var streamRes = await provider.ResolveAudioStreamAsync("t1", relativePath: null, cookie: null, proxyUrl: null, forceRefresh: false, quality: "high", purpose: CloudStreamPurpose.Stream);
        Assert.Equal("https://cdn.example.test/high_stream.flac", streamRes.Url);
        Assert.Equal("high", streamRes.ActualQuality);
        Assert.Equal("320k", streamRes.QualityLabel);
        Assert.Equal(320, streamRes.BitrateKbps);

        var dlRes = await provider.ResolveAudioStreamAsync("t1", relativePath: null, cookie: null, proxyUrl: null, forceRefresh: false, quality: "flac", purpose: CloudStreamPurpose.Download);
        Assert.Equal("https://cdn.example.test/flac_download.flac", dlRes.Url);
        Assert.Equal("flac", dlRes.ActualQuality);
        Assert.Equal("FLAC", dlRes.QualityLabel);
        Assert.Equal(960, dlRes.BitrateKbps);

        store.SetStreamQualityId("com.test.extq", "flac");
        store.SetDownloadQualityId("com.test.extq", "high");
        Assert.Equal("flac", store.GetStreamQualityId("com.test.extq"));
        Assert.Equal("high", store.GetDownloadQualityId("com.test.extq"));
    }

    // ==================== Feature A2: Built-in Provider Qualities ====================

    [Fact]
    public async Task BilibiliClientResolvesRequestedQualitiesAcrossIdsAndPopulatesMetadata()
    {
        var handler = new StubHttpHandler((req, _) =>
        {
            string uri = req.RequestUri!.AbsoluteUri;
            if (uri.Contains("x/web-interface/nav"))
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}""", Encoding.UTF8, "application/json")
                });
            }
            if (uri.Contains("x/web-interface/view"))
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""{"code":0,"data":{"bvid":"BV1xx411c7mD","cid":1001,"pages":[{"cid":1001,"part":"Song"}]}}""", Encoding.UTF8, "application/json")
                });
            }
            if (uri.Contains("x/player/wbi/playurl"))
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""
                    {
                      "code":0,
                      "data":{
                        "dash":{
                          "audio":[
                            {"id":30216,"baseUrl":"https://upos.bilivideo.com/low.m4s","bandwidth":66000},
                            {"id":30232,"baseUrl":"https://upos.bilivideo.com/std.m4s","bandwidth":132000},
                            {"id":30280,"baseUrl":"https://upos.bilivideo.com/high.m4s","bandwidth":192000},
                            {"id":30251,"baseUrl":"https://upos.bilivideo.com/hires.flac","bandwidth":960000}
                          ]
                        }
                      }
                    }
                    """, Encoding.UTF8, "application/json")
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        var bili = new BilibiliClient(handler);
        var provider = new BilibiliCloudProvider(bili);
        Assert.Equal(["highest", "high", "standard", "low"], provider.AvailableQualities.Select(q => q.Id));
        Assert.Equal("standard", provider.DefaultStreamQualityId);
        Assert.Equal("highest", provider.DefaultDownloadQualityId);

        var qHighest = await provider.ResolveAudioStreamAsync("BV1xx411c7mD#1001", relativePath: null, cookie: null, proxyUrl: null, forceRefresh: false, quality: "highest");
        Assert.Equal("https://upos.bilivideo.com/hires.flac", qHighest.Url);
        Assert.Equal("highest", qHighest.ActualQuality);
        Assert.Equal("FLAC", qHighest.QualityLabel);
        Assert.Equal(960, qHighest.BitrateKbps);

        var qHigh = await provider.ResolveAudioStreamAsync("BV1xx411c7mD#1001", relativePath: null, cookie: null, proxyUrl: null, forceRefresh: false, quality: "high");
        Assert.Equal("https://upos.bilivideo.com/high.m4s", qHigh.Url);
        Assert.Equal("high", qHigh.ActualQuality);
        Assert.Equal("192k", qHigh.QualityLabel);

        var qStd = await provider.ResolveAudioStreamAsync("BV1xx411c7mD#1001", relativePath: null, cookie: null, proxyUrl: null, forceRefresh: false, quality: "standard");
        Assert.Equal("https://upos.bilivideo.com/std.m4s", qStd.Url);
        Assert.Equal("standard", qStd.ActualQuality);
        Assert.Equal("132k", qStd.QualityLabel);

        var qLow = await provider.ResolveAudioStreamAsync("BV1xx411c7mD#1001", relativePath: null, cookie: null, proxyUrl: null, forceRefresh: false, quality: "low");
        Assert.Equal("https://upos.bilivideo.com/low.m4s", qLow.Url);
        Assert.Equal("low", qLow.ActualQuality);
        Assert.Equal("64k", qLow.QualityLabel);

        var gdrive = new GoogleDriveCloudProvider(new GoogleDriveClient());
        Assert.Empty(gdrive.AvailableQualities);
        Assert.Null(gdrive.DefaultStreamQualityId);
        Assert.Null(gdrive.DefaultDownloadQualityId);
    }

    [Fact]
    public async Task YouTubeClientQualitySelectionRanksCompatibleM4aStreamsAndPopulatesLabels()
    {
        string playerJson = """
        {
          "playabilityStatus": { "status": "OK" },
          "streamingData": {
            "adaptiveFormats": [
              { "itag": 251, "url": "https://rr.googlevideo.com/opus.webm", "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000 },
              { "itag": 139, "url": "https://rr.googlevideo.com/low.m4a", "mimeType": "audio/mp4; codecs=\"mp4a.40.5\"", "bitrate": 48000 },
              { "itag": 140, "url": "https://rr.googlevideo.com/std.m4a", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000 },
              { "itag": 141, "url": "https://rr.googlevideo.com/high.m4a", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 256000 }
            ]
          }
        }
        """;

        var handler = new StubHttpHandler((req, _) =>
        {
            if (req.RequestUri!.AbsoluteUri.Contains("/youtubei/v1/player"))
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(playerJson, Encoding.UTF8, "application/json")
                });
            }
            if (req.RequestUri.Host == "rr.googlevideo.com")
            {
                var resp = new HttpResponseMessage(HttpStatusCode.PartialContent);
                resp.Content = new ByteArrayContent([0x00, 0x00]);
                resp.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("audio/mp4");
                resp.Content.Headers.ContentRange = new System.Net.Http.Headers.ContentRangeHeaderValue(0, 1, 1024);
                return Task.FromResult(resp);
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        var yt = new YouTubeClient(handler);
        var provider = new YouTubeCloudProvider(yt);
        Assert.Equal(["highest", "high", "standard", "low"], provider.AvailableQualities.Select(q => q.Id));
        Assert.Equal("standard", provider.DefaultStreamQualityId);
        Assert.Equal("highest", provider.DefaultDownloadQualityId);

        using var doc = JsonDocument.Parse(playerJson);
        Assert.Equal("https://rr.googlevideo.com/high.m4a", YouTubeClient.SelectBestAudioFormat(doc.RootElement, "highest")?.Url);
        Assert.Equal("https://rr.googlevideo.com/std.m4a", YouTubeClient.SelectBestAudioFormat(doc.RootElement, "standard")?.Url);
        Assert.Equal("https://rr.googlevideo.com/low.m4a", YouTubeClient.SelectBestAudioFormat(doc.RootElement, "low")?.Url);

        var rHighest = await provider.ResolveAudioStreamAsync("dQw4w9WgXcQ", relativePath: null, cookie: null, proxyUrl: null, forceRefresh: false, quality: "highest");
        Assert.Equal("https://rr.googlevideo.com/high.m4a", rHighest.Url);
        Assert.Equal("highest", rHighest.ActualQuality);
        Assert.Equal("256k", rHighest.QualityLabel);
        Assert.Equal(256, rHighest.BitrateKbps);

        var rLow = await provider.ResolveAudioStreamAsync("dQw4w9WgXcQ", relativePath: null, cookie: null, proxyUrl: null, forceRefresh: false, quality: "low");
        Assert.Equal("https://rr.googlevideo.com/low.m4a", rLow.Url);
        Assert.Equal("low", rLow.ActualQuality);
        Assert.Equal("48k", rLow.QualityLabel);
        Assert.Equal(48, rLow.BitrateKbps);
    }

    // ==================== Feature A3: Resolver Cache Key, AudioCache Partitioning, Persistence & Export ====================

    [Fact]
    public async Task ResolverCachesPerQualityAndAudioCachePartitionsAndEvictsStaleVariants()
    {
        string stateDir = NewTempDir();
        string cacheDir = Path.Combine(stateDir, "CloudCache");
        var extStore = new ExtensionStore(stateDir);

        int hits = 0;
        var handler = new StubHttpHandler((req, _) =>
        {
            string uri = req.RequestUri!.AbsoluteUri;
            if (uri.Contains("x/web-interface/nav"))
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}""", Encoding.UTF8, "application/json")
                });
            }
            if (uri.Contains("x/web-interface/view"))
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""{"code":0,"data":{"bvid":"BV1xx411c7mD","cid":1001}}""", Encoding.UTF8, "application/json")
                });
            }
            if (uri.Contains("x/player/wbi/playurl"))
            {
                hits++;
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""
                    {"code":0,"data":{"dash":{
                      "audio":[{"id":30232,"baseUrl":"https://cdn.bilivideo.com/std.m4a","bandwidth":132000}],
                      "flac":{"audio":{"id":30251,"baseUrl":"https://cdn.bilivideo.com/hires.flac","bandwidth":960000}}
                    }}}
                    """, Encoding.UTF8, "application/json")
                });
            }
            if (uri.StartsWith("https://cdn.bilivideo.com/", StringComparison.Ordinal))
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(MakeMinimalMp3Bytes())
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        var bili = new BilibiliClient(handler);
        var registry = new CloudProviderRegistry(new YouTubeClient(handler), bili, new GoogleDriveClient(handler));
        extStore.BindRegistry(registry);
        var root = new LibraryRoot(Guid.NewGuid(), "Bili", RootKind.CloudBilibili, "https://www.bilibili.com/video/BV1xx411c7mD");
        var track = new Track(
            Id: Track.MakeId(root.Id, "song.m4a"),
            RootId: root.Id,
            RelativePath: "song.m4a",
            Title: "Song",
            Artist: "Art",
            Album: "Alb",
            AlbumArtist: "Bili",
            TrackNumber: 1,
            DiscNumber: 1,
            DurationSeconds: 60,
            FileSize: 0,
            ModifiedUtc: DateTimeOffset.UnixEpoch,
            HasEmbeddedLyrics: false,
            DateAddedUtc: DateTimeOffset.UnixEpoch,
            CloudSource: CloudSources.Bilibili,
            RemoteTrackId: "BV1xx411c7mD#1001");

        var rStd1 = await CloudStreamResolver.ResolveAsync(registry, track, root, extStore, qualityOverride: "standard");
        var rStd2 = await CloudStreamResolver.ResolveAsync(registry, track, root, extStore, qualityOverride: "standard");
        Assert.Equal(1, hits);
        Assert.Equal("standard", rStd1.ActualQuality);
        Assert.Equal(rStd1.Url, rStd2.Url);

        var rHigh = await CloudStreamResolver.ResolveAsync(registry, track, root, extStore, qualityOverride: "highest");
        Assert.Equal(2, hits);
        Assert.Equal("highest", rHigh.ActualQuality);
        Assert.Equal("https://cdn.bilivideo.com/hires.flac", rHigh.Url);

        await CloudStreamResolver.ResolveAsync(registry, track, root, extStore, forceRefresh: true, qualityOverride: "standard");
        Assert.Equal(3, hits);

        var cache = new CloudAudioCache(cacheDir, handler: handler);
        string pStd = await cache.GetOrDownloadAsync(
            track.Id,
            ct => CloudStreamResolver.ResolveAsync(registry, track, root, extStore, qualityOverride: "standard", ct: ct),
            quality: "standard");
        Assert.Equal($"{Persistence.SafeTrackFileKey(track.Id)}__standard.m4a", Path.GetFileName(pStd));
        Assert.Equal(pStd, cache.TryGetCachedPath(track.Id, "standard"));
        Assert.Null(cache.TryGetCachedPath(track.Id, "highest"));

        string pHigh = await cache.GetOrDownloadAsync(
            track.Id,
            ct => CloudStreamResolver.ResolveAsync(registry, track, root, extStore, qualityOverride: "highest", ct: ct),
            quality: "highest");
        Assert.Equal($"{Persistence.SafeTrackFileKey(track.Id)}__highest.flac", Path.GetFileName(pHigh));
        Assert.False(File.Exists(pStd));
        Assert.True(File.Exists(pHigh));

        cache.RemoveForTracks([track.Id]);
        Assert.Null(cache.TryGetCachedPath(track.Id));
    }

    [Fact]
    public void LegacyLibraryRootAndExtensionStateJsonDeserializeWithNullQualityFieldsAndRoundTrip()
    {
        const string legacyRootJson = """{"Id":"11111111-1111-1111-1111-111111111111","Name":"Old Cloud","Kind":1,"SourceUrl":"https://youtube.com/playlist?list=PL1"}""";
        var root = JsonSerializer.Deserialize<LibraryRoot>(legacyRootJson);
        Assert.NotNull(root);
        Assert.Null(root!.StreamQualityId);
        Assert.Null(root.DownloadQualityId);

        var updatedRoot = root with { StreamQualityId = "standard", DownloadQualityId = "highest" };
        var roundTrippedRoot = JsonSerializer.Deserialize<LibraryRoot>(JsonSerializer.Serialize(updatedRoot));
        Assert.Equal("standard", roundTrippedRoot?.StreamQualityId);
        Assert.Equal("highest", roundTrippedRoot?.DownloadQualityId);

        const string legacyExtJson = """{"Id":"com.test.old","Enabled":true}""";
        var extRec = JsonSerializer.Deserialize<ExtensionStateRecord>(legacyExtJson);
        Assert.NotNull(extRec);
        Assert.Null(extRec!.StreamQualityId);
        Assert.Null(extRec.DownloadQualityId);
    }

    [Fact]
    public async Task CloudExporterUsesDownloadPurposeAndDownloadQualityPreference()
    {
        string stateDir = NewTempDir();
        string cacheDir = Path.Combine(stateDir, "CloudCache");
        string destDir = NewTempDir();

        var observedPurposes = new List<string>();
        var observedQualities = new List<string>();

        string script = """
            exports.canHandle = (input) => ({ matched: true, normalizedUrl: input.extractedUrl, remoteId: "exp1" });
            exports.fetchCollection = async () => ({
                remoteId: "exp1",
                title: "Exp Album",
                tracks: [{ remoteTrackId: "t1", title: "Export Song", artist: "Art", trackNumber: 1 }]
            });
            exports.resolveStream = async (ctx, req) => ({
                url: `https://cdn.example.test/${req.purpose}/${req.quality}.mp3`,
                extension: ".mp3",
                actualQuality: req.quality,
                qualityLabel: req.quality.toUpperCase()
            });
            """;

        byte[] zip = MakeExtensionZipBytes(
            "com.test.expq",
            "1.0.0",
            ["playlist", "stream"],
            ["cdn.example.test"],
            urlPatterns: ["^https://music\\.example\\.test/album/"],
            qualities:
            [
                new { id = "standard", label = "Standard" },
                new { id = "lossless", label = "Lossless" }
            ],
            defaultStreamQuality: "standard",
            defaultDownloadQuality: "lossless",
            script: script);

        var handler = new StubHttpHandler((req, _) =>
        {
            string u = req.RequestUri!.AbsoluteUri;
            if (u.StartsWith("https://cdn.example.test/", StringComparison.Ordinal))
            {
                string[] parts = req.RequestUri.AbsolutePath.Trim('/').Split('/');
                observedPurposes.Add(parts[0]);
                observedQualities.Add(Path.GetFileNameWithoutExtension(parts[1]));
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(MakeMinimalMp3Bytes())
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        var extStore = new ExtensionStore(stateDir, handler);
        var installed = InstallAndEnable(extStore, zip);
        var registry = new CloudProviderRegistry(new YouTubeClient(handler), new BilibiliClient(handler), new GoogleDriveClient(handler));
        extStore.BindRegistry(registry);
        var cache = new CloudAudioCache(cacheDir, handler: handler);
        var exporter = new CloudExporter(cache, registry, extensions: extStore);

        var root = new LibraryRoot(
            Guid.NewGuid(),
            "Exp Root",
            RootKind.CloudExtension,
            "https://music.example.test/album/1",
            ProviderId: installed.Id,
            StreamQualityId: "standard",
            DownloadQualityId: "lossless");
        var track = new Track(
            Id: Track.MakeId(root.Id, "t1.mp3"),
            RootId: root.Id,
            RelativePath: "t1.mp3",
            Title: "Export Song",
            Artist: "Art",
            Album: "Exp Album",
            AlbumArtist: "Art",
            TrackNumber: 1,
            DiscNumber: 1,
            DurationSeconds: 60,
            FileSize: 0,
            ModifiedUtc: DateTimeOffset.UnixEpoch,
            HasEmbeddedLyrics: false,
            DateAddedUtc: DateTimeOffset.UnixEpoch,
            CloudSource: $"ext.{installed.Id}",
            RemoteTrackId: "t1");

        string exported = await exporter.ExportTrackAsync(track, root, destDir);
        Assert.True(File.Exists(exported));
        Assert.Equal(["download"], observedPurposes);
        Assert.Equal(["lossless"], observedQualities);
    }

    // ==================== Feature A4: Current-Track-Only Quality Switching ====================

    [Fact]
    public async Task PlayerControllerSwitchCurrentTrackQualityIsCurrentTrackOnlyAndPreservesPosition()
    {
        string stateDir = NewTempDir();
        string musicDir = NewTempDir();
        var resolvedCalls = new List<(string TrackId, string Quality, string Purpose)>();

        string script = """
            exports.canHandle = (input) => ({ matched: true, normalizedUrl: input.extractedUrl, remoteId: "mix1" });
            exports.fetchCollection = async () => ({
                remoteId: "mix1",
                title: "QMix",
                tracks: [
                    { remoteTrackId: "s1", title: "Song 1", artist: "A", trackNumber: 1 },
                    { remoteTrackId: "s2", title: "Song 2", artist: "A", trackNumber: 2 }
                ]
            });
            exports.resolveStream = async (ctx, req) => ({
                url: `https://cdn.example.test/audio/${req.remoteTrackId}/${req.quality}/${req.purpose}.mp3`,
                extension: ".mp3",
                actualQuality: req.quality,
                qualityLabel: req.quality === "flac" ? "FLAC" : "192k"
            });
            """;

        byte[] zip = MakeExtensionZipBytes(
            "com.test.playerq",
            "1.0.0",
            ["playlist", "stream"],
            ["cdn.example.test"],
            urlPatterns: ["^https://music\\.example\\.test/mix/"],
            qualities:
            [
                new { id = "standard", label = "Standard", badge = "192k" },
                new { id = "flac", label = "Lossless", badge = "FLAC", lossless = true }
            ],
            defaultStreamQuality: "standard",
            defaultDownloadQuality: "flac",
            script: script);

        var handler = new StubHttpHandler((req, _) =>
        {
            if (req.RequestUri!.AbsoluteUri.StartsWith("https://cdn.example.test/audio/", StringComparison.Ordinal))
            {
                string[] parts = req.RequestUri.AbsolutePath.Trim('/').Split('/');
                resolvedCalls.Add((parts[1], parts[2], Path.GetFileNameWithoutExtension(parts[3])));
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(MakeMinimalMp3Bytes())
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        var extStore = new ExtensionStore(stateDir, handler);
        InstallAndEnable(extStore, zip);
        var registry = new CloudProviderRegistry(new YouTubeClient(handler), new BilibiliClient(handler), new GoogleDriveClient(handler));
        extStore.BindRegistry(registry);
        var cache = new CloudAudioCache(Path.Combine(stateDir, "CloudCache"), handler: handler);

        var lib = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Art")),
            artworkHandler: handler,
            cloudCache: cache,
            providerRegistry: registry);
        var root = await lib.AddCloudRootAsync("https://music.example.test/mix/1");
        var tracks = lib.Tracks.OrderBy(t => t.TrackNumber).ToList();
        Assert.Equal(2, tracks.Count);

        var engine = new FakeAudioEngine();
        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        using var player = new PlayerController(
            lib,
            queues,
            history,
            engine,
            Path.Combine(stateDir, "settings.json"),
            smtc: null,
            enableTicker: false,
            cloudCache: cache,
            providerRegistry: registry,
            extensions: extStore);

        player.Play(tracks.Select(t => t.Id).ToList(), startAt: 0);
        for (int i = 0; i < 100 && !player.IsPlaying; i++) await Task.Delay(25);

        Assert.Equal("standard", player.CurrentQualityId);
        Assert.Equal("192k", player.CurrentQualityLabel);
        Assert.Equal(2, player.CurrentTrackAvailableQualities.Count);

        // Seek to 42s, then switch quality for current track ONLY
        player.Seek(42.0);
        bool switched = await player.SwitchCurrentTrackQualityAsync("flac");
        Assert.True(switched);

        Assert.Equal("flac", player.CurrentQualityId);
        Assert.Equal("FLAC", player.CurrentQualityLabel);
        Assert.Contains(engine.SeekCalls, s => Math.Abs(s - 42.0) < 0.5);
        // Folder & extension stream preferences remain strictly unchanged!
        Assert.Null(lib.GetRoot(root.Id)?.StreamQualityId);
        Assert.Null(extStore.GetStreamQualityId("com.test.playerq"));

        // Advancing to Track 2 reverts to default ("standard") because override was current-track-only
        player.Next();
        for (int i = 0; i < 100 && player.CurrentTrack?.Id != tracks[1].Id; i++) await Task.Delay(25);
        for (int i = 0; i < 100 && player.CurrentQualityId != "standard"; i++) await Task.Delay(25);

        Assert.Equal("standard", player.CurrentQualityId);
        Assert.Equal("192k", player.CurrentQualityLabel);
        Assert.Contains(("s1", "flac", "stream"), resolvedCalls);
        Assert.Contains(("s2", "standard", "stream"), resolvedCalls);
    }

    // ==================== Feature B: Safe Re-Binding & Option A Orphaned Recovery ====================

    [Fact]
    public async Task RebindingCloudFolderAndTrackedLocalFolderPreservesIdentityPlaylistsFavouritesAndManifest()
    {
        string stateDir = NewTempDir();
        string musicDir = NewTempDir();
        var extStore = new ExtensionStore(stateDir);

        string scriptA = """
            exports.canHandle = (input) => ({ matched: true, normalizedUrl: input.extractedUrl, remoteId: "list1" });
            exports.fetchCollection = async () => ({
                remoteId: "list1",
                title: "Ext Source A",
                tracks: [{ remoteTrackId: "idA", title: "Track A", artist: "Artist", trackNumber: 1 }]
            });
            exports.resolveStream = async () => ({ url: "https://cdn-a.example.test/a.mp3" });
            """;
        string scriptB = """
            exports.canHandle = (input) => ({ matched: true, normalizedUrl: input.extractedUrl, remoteId: "list1" });
            exports.fetchCollection = async () => ({
                remoteId: "list1",
                title: "Ext Source B",
                tracks: [
                    { remoteTrackId: "idB1", title: "Track B1", artist: "Artist", trackNumber: 1 },
                    { remoteTrackId: "idB2", title: "Track B2", artist: "Artist", trackNumber: 2 }
                ]
            });
            exports.resolveStream = async () => ({ url: "https://cdn-b.example.test/b.mp3" });
            """;

        byte[] zipA = MakeExtensionZipBytes(
            "com.test.srca", "1.0.0", ["playlist", "stream"], ["cdn-a.example.test"],
            urlPatterns: ["^https://shared\\.example\\.test/list/"],
            qualities: [new { id = "std", label = "Standard" }],
            defaultStreamQuality: "std", defaultDownloadQuality: "std",
            script: scriptA);
        byte[] zipB = MakeExtensionZipBytes(
            "com.test.srcb", "1.0.0", ["playlist", "stream", "cookie"], ["cdn-b.example.test"],
            urlPatterns: ["^https://shared\\.example\\.test/list/"],
            qualities: [new { id = "hq", label = "High Quality" }],
            defaultStreamQuality: "hq", defaultDownloadQuality: "hq",
            script: scriptB);

        InstallAndEnable(extStore, zipA);
        InstallAndEnable(extStore, zipB);

        var registry = new CloudProviderRegistry(new YouTubeClient(), new BilibiliClient(), new GoogleDriveClient());
        extStore.BindRegistry(registry);
        var lib = new LibraryStore(
            stateDir, musicDir, new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Art")),
            providerRegistry: registry);

        var cloudRoot = await lib.AddCloudRootAsync(
            "https://shared.example.test/list/1",
            preferredProviderId: "ext.com.test.srca",
            streamQualityId: "std");
        Guid originalRootId = cloudRoot.Id;
        var initialTrack = Assert.Single(lib.Tracks, t => t.RootId == originalRootId);

        var pl = lib.CreatePlaylist("KeepMe");
        lib.AddToPlaylist([initialTrack.Id], pl);
        lib.ToggleFavourite(initialTrack.Id);

        // Uninstall extension A -> root becomes orphaned (unavailable)
        extStore.Uninstall("com.test.srca");
        Assert.False(lib.IsAvailable(lib.GetRoot(originalRootId)!));

        // Option A Recovery: GetCompatibleRecoveryProviders finds extension B matching URL
        var recovery = lib.GetCompatibleRecoveryProviders(lib.GetRoot(originalRootId)!);
        var recoveredProvider = Assert.Single(recovery);
        Assert.Equal("ext.com.test.srcb", recoveredProvider.ProviderId);

        // Switch source via UpdateCloudRootSourceAsync -> keeps originalRootId and updates tracks/quality!
        var rebound = await lib.UpdateCloudRootSourceAsync(
            originalRootId,
            "https://shared.example.test/list/1",
            cookie: null,
            proxyUrl: null,
            preferredProviderId: recoveredProvider.ProviderId,
            streamQualityId: "hq",
            downloadQualityId: "hq");

        Assert.Equal(originalRootId, rebound.Id);
        Assert.Equal("ext.com.test.srcb", rebound.ProviderId);
        Assert.True(lib.IsAvailable(rebound));
        Assert.Equal("hq", rebound.StreamQualityId);
        Assert.Equal("hq", rebound.DownloadQualityId);

        var newTracks = lib.Tracks.Where(t => t.RootId == originalRootId).ToList();
        Assert.Equal(2, newTracks.Count);
        Assert.All(newTracks, t => Assert.Equal("ext.com.test.srcb", t.CloudSource));
        Assert.Equal("KeepMe", lib.Playlists[0].Name);

        // Cloud-Tracked Local Folder re-binding persists manifest (.musico-cloud.json) & root tracking info
        string localExportDir = Path.Combine(musicDir, "TrackedFolder");
        Directory.CreateDirectory(localExportDir);
        CloudExporter.SaveManifest(localExportDir, new CloudFolderManifest(
            SourceUrl: "https://shared.example.test/list/old",
            RemoteId: "listold",
            CloudKind: "ext.com.test.srca",
            LastSyncedUtc: DateTimeOffset.UtcNow,
            Tracks: new Dictionary<string, string>(),
            ProviderId: "ext.com.test.srca",
            StreamQualityId: "std",
            DownloadQualityId: "std"));
        await lib.RescanAsync();
        var trackedRoot = lib.GetRoot(LibraryRoot.AppFolderId)!;
        Assert.NotNull(lib.GetCloudTracking(trackedRoot.Id, "TrackedFolder"));

        lib.UpdateCloudTrackingAddress(
            trackedRoot.Id,
            "TrackedFolder",
            "https://shared.example.test/list/1",
            cookie: "SESS=123",
            proxyUrl: null,
            preferredProviderId: "ext.com.test.srcb",
            streamQualityId: "hq",
            downloadQualityId: "hq",
            updateQualityPrefs: true);

        var updatedTracking = lib.GetCloudTracking(trackedRoot.Id, "TrackedFolder");
        Assert.NotNull(updatedTracking);
        Assert.Equal("ext.com.test.srcb", updatedTracking!.ResolvedProviderId);
        Assert.Equal("https://shared.example.test/list/1", updatedTracking.SourceUrl);
        Assert.Equal("SESS=123", updatedTracking.Cookie);
        Assert.Equal("hq", updatedTracking.StreamQualityId);
        Assert.Equal("hq", updatedTracking.DownloadQualityId);

        var diskManifest = CloudExporter.LoadManifest(localExportDir);
        Assert.NotNull(diskManifest);
        Assert.Equal("ext.com.test.srcb", diskManifest!.ProviderId);
        Assert.Equal("hq", diskManifest.StreamQualityId);
        Assert.Equal("hq", diskManifest.DownloadQualityId);
    }
}
