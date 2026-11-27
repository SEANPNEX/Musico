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

public sealed class V126FeaturesTests : IDisposable
{
    private readonly List<string> _tempDirs = new();

    private string NewTempDir()
    {
        string dir = TestHelpers.MakeTempDir();
        _tempDirs.Add(dir);
        return dir;
    }

    public void Dispose()
    {
        foreach (string dir in _tempDirs)
        {
            try { if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true); } catch { /* ignore */ }
        }
    }

    // Valid 67-byte 1x1 RGB PNG.
    private static readonly byte[] ValidPngBytes =
    [
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
        0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0x1F, 0xEA, 0x02,
        0x00, 0x03, 0xEF, 0x01, 0x99, 0xC3, 0x49, 0xF5, 0x60, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
        0x44, 0xAE, 0x42, 0x60, 0x82
    ];

    private static readonly byte[] MinimalJpegBytes =
        [0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xFF, 0xD9];

    private static readonly byte[] MinimalWebpBytes =
        [0x52, 0x49, 0x46, 0x46, 0x1A, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x20];

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

    private static byte[] MakeMinimalFlacBytes()
    {
        // fLaC magic + final STREAMINFO metadata block (34 bytes, 44.1kHz stereo 16-bit) + 2 zero bytes.
        var bytes = new byte[4 + 4 + 34 + 2];
        bytes[0] = (byte)'f';
        bytes[1] = (byte)'L';
        bytes[2] = (byte)'a';
        bytes[3] = (byte)'C';
        bytes[4] = 0x80; // Last metadata block + STREAMINFO (0)
        bytes[5] = 0x00;
        bytes[6] = 0x00;
        bytes[7] = 34;
        // Min/max block size = 4096
        bytes[8] = 0x10; bytes[9] = 0x00;
        bytes[10] = 0x10; bytes[11] = 0x00;
        // Sample rate 44100 (0x0AC44) in top 20 bits of bytes[18..20], 2 channels (1), 16 bps (15)
        bytes[18] = 0x0A;
        bytes[19] = 0xC4;
        bytes[20] = 0x42; // 44100<<4 | (channels-1)<<1 | ((bps-1)>>4)
        bytes[21] = 0xF0; // ((bps-1)&0xF)<<4
        return bytes;
    }

    private static byte[] MakeExtensionZipBytes(
        string id,
        string version,
        string[] capabilities,
        string[] hosts,
        string? updateUrl = null,
        string? indexJs = null,
        string[]? urlPatterns = null)
    {
        var manifest = new Dictionary<string, object?>
        {
            ["apiVersion"] = ExtensionManifest.CurrentApiVersion,
            ["id"] = id,
            ["name"] = $"Ext {id}",
            ["version"] = version,
            ["entry"] = "index.js",
            ["capabilities"] = capabilities,
            ["urlPatterns"] = urlPatterns ?? ["^https://api\\.example\\.test/"],
            ["permissions"] = new { hosts }
        };
        if (updateUrl is not null)
        {
            manifest["updateUrl"] = updateUrl;
        }

        string script = indexJs ?? """
            exports.canHandle = function() { return { matched: false }; };
            exports.fetchCollection = function() { return { remoteId: 'x', title: 'X', tracks: [] }; };
            exports.search = async function() { return { items: [], hasMore: false }; };
            """;

        using var ms = new MemoryStream();
        using (var zip = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
        {
            var mEntry = zip.CreateEntry("manifest.json");
            using (var sw = new StreamWriter(mEntry.Open(), new UTF8Encoding(false)))
            {
                sw.Write(JsonSerializer.Serialize(manifest));
            }
            var jEntry = zip.CreateEntry("index.js");
            using (var sw = new StreamWriter(jEntry.Open(), new UTF8Encoding(false)))
            {
                sw.Write(script);
            }
        }
        return ms.ToArray();
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

        public void Load(string path)
        {
            DurationSeconds = 90.0;
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

        public void ApplyEq(EqSettings settings) { }
        public void Dispose() => Unload();
    }

    // ==================== 1. Extension Auto-Updates ====================

    [Fact]
    public void ExtensionManifestUpdateUrlRequiresHttpsAndSemVerComparatorWorks()
    {
        byte[] validZip = MakeExtensionZipBytes(
            "com.test.upd",
            "1.0.0",
            ["playlist"],
            ["api.example.test"],
            updateUrl: "https://cdn.example.test/ext.musicoext");
        var pkg = ExtensionPackageLoader.LoadFromBytes(validZip, "ext.musicoext");
        Assert.Equal("https://cdn.example.test/ext.musicoext", pkg.Manifest.UpdateUrl);

        byte[] httpZip = MakeExtensionZipBytes(
            "com.test.upd",
            "1.0.0",
            ["playlist"],
            ["api.example.test"],
            updateUrl: "http://cdn.example.test/ext.musicoext");
        Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadFromBytes(httpZip, "ext.musicoext"));

        Assert.True(ExtensionVersionComparer.IsStrictlyNewer("1.0.1", "1.0.0"));
        Assert.True(ExtensionVersionComparer.IsStrictlyNewer("1.1.0", "1.0.9"));
        Assert.True(ExtensionVersionComparer.IsStrictlyNewer("1.0.0", "1.0.0-beta.1"));
        Assert.True(ExtensionVersionComparer.IsStrictlyNewer("1.0.0-beta.2", "1.0.0-beta.1"));
        Assert.False(ExtensionVersionComparer.IsStrictlyNewer("1.0.0", "1.0.0"));
        Assert.False(ExtensionVersionComparer.IsStrictlyNewer("0.9.9", "1.0.0"));
        Assert.False(ExtensionVersionComparer.IsStrictlyNewer("1.0.0+build1", "1.0.0"));
    }

    [Fact]
    public async Task ExtensionAutoUpdatePrefersManifestUpdateUrlFallsBackToSourceUrlAndRespectsThrottleAndSetting()
    {
        string stateDir = NewTempDir();
        const string sourceUrl = "https://releases.example.test/source.musicoext";
        const string manifestUpdateUrl = "https://updates.example.test/latest.musicoext";

        byte[] v1FromSource = MakeExtensionZipBytes(
            "com.test.autoupdate",
            "1.0.0",
            ["playlist", "cookie"],
            ["*.example.test"],
            updateUrl: manifestUpdateUrl);
        byte[] v2FromManifestUrl = MakeExtensionZipBytes(
            "com.test.autoupdate",
            "1.2.0",
            ["playlist"], // Reduced capability -> silent apply
            ["api.example.test"]); // Narrowed host covered by *.example.test -> silent apply

        int sourceUrlHits = 0;
        int manifestUrlHits = 0;
        var handler = new StubHttpHandler((req, _) =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url == sourceUrl)
            {
                sourceUrlHits++;
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(v1FromSource)
                });
            }
            if (url == manifestUpdateUrl)
            {
                manifestUrlHits++;
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(v2FromManifestUrl)
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        var store = new ExtensionStore(stateDir, handler);
        var installed = await store.ImportFromUrlAsync(sourceUrl);
        store.ApproveAndEnable(installed.Id);
        store.SetSettingValue(installed.Id, "k", "val");

        Assert.Equal(sourceUrl, store.GetById(installed.Id)!.State.SourceUrl);
        Assert.Equal(manifestUpdateUrl, ExtensionStore.ResolveUpdateUrl(store.GetById(installed.Id)!));
        Assert.True(store.AutoUpdateEnabled);

        // First due check runs and applies v1.2.0 silently from manifest.UpdateUrl.
        DateTimeOffset t0 = new(2026, 11, 25, 0, 0, 0, TimeSpan.Zero);
        var summary1 = await store.CheckForUpdatesIfDueAsync(nowUtc: t0);
        Assert.False(summary1.WasSkippedByThrottleOrSetting);
        Assert.Equal(1, manifestUrlHits);
        var res1 = Assert.Single(summary1.Results);
        Assert.Equal(ExtensionUpdateOutcome.AppliedSilently, res1.Outcome);
        Assert.Equal("1.2.0", store.GetById(installed.Id)!.Manifest.Version);
        Assert.True(store.GetById(installed.Id)!.IsEnabled);
        Assert.Equal("val", store.GetSettingString(installed.Id, "k"));

        // Second due check within 6h is throttled (no HTTP calls).
        var summaryThrottled = await store.CheckForUpdatesIfDueAsync(nowUtc: t0.AddHours(3));
        Assert.True(summaryThrottled.WasSkippedByThrottleOrSetting);
        Assert.Equal(1, manifestUrlHits);

        // After 6h, check runs again; v1.2.0 omitted UpdateUrl so it falls back to sourceUrl (serves 1.0.0 <= 1.2.0 -> UpToDate).
        var summaryAfter6h = await store.CheckForUpdatesIfDueAsync(nowUtc: t0.AddHours(6).AddMinutes(1));
        Assert.False(summaryAfter6h.WasSkippedByThrottleOrSetting);
        Assert.Equal(1, manifestUrlHits);
        Assert.Equal(2, sourceUrlHits);
        Assert.Equal(ExtensionUpdateOutcome.UpToDate, Assert.Single(summaryAfter6h.Results).Outcome);

        // Disable AutoUpdateEnabled, persist across reload, and verify CheckForUpdatesIfDueAsync skips.
        store.AutoUpdateEnabled = false;
        var reloaded = new ExtensionStore(stateDir, handler);
        Assert.False(reloaded.AutoUpdateEnabled);
        var reloadedExt = reloaded.GetById(installed.Id);
        Assert.NotNull(reloadedExt);
        // v1.2.0 had no manifest.UpdateUrl, so it falls back to persisted SourceUrl!
        Assert.Equal(sourceUrl, ExtensionStore.ResolveUpdateUrl(reloadedExt!));

        var skippedByPref = await reloaded.CheckForUpdatesIfDueAsync(nowUtc: t0.AddHours(20));
        Assert.True(skippedByPref.WasSkippedByThrottleOrSetting);
        Assert.Equal(1, manifestUrlHits);
        Assert.Equal(2, sourceUrlHits);
    }

    [Fact]
    public async Task ExtensionUpdateEnforcesIdMatchHttpsRedirectAndPermissionEscalationGuard()
    {
        string stateDir = NewTempDir();
        const string updateUrl = "https://updates.example.test/plugin.musicoext";

        byte[] currentBytes = MakeExtensionZipBytes(
            "com.test.guard",
            "1.0.0",
            ["playlist"],
            ["api.example.test"],
            updateUrl: updateUrl);

        byte[] remoteBytes = MakeExtensionZipBytes(
            "com.test.other", // Mismatched ID!
            "2.0.0",
            ["playlist"],
            ["api.example.test"],
            updateUrl: updateUrl);

        bool redirectToHttp = false;
        var handler = new StubHttpHandler((req, _) =>
        {
            if (redirectToHttp)
            {
                var r = new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(remoteBytes)
                };
                r.RequestMessage = new HttpRequestMessage(HttpMethod.Get, "http://insecure.example.test/plugin.musicoext");
                return Task.FromResult(r);
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent(remoteBytes)
            });
        });

        var store = new ExtensionStore(stateDir, handler);
        var pkg = ExtensionPackageLoader.LoadFromBytes(currentBytes, "plugin.musicoext");
        store.StageImportedPackage(pkg);
        store.ApproveAndEnable("com.test.guard");

        // 1. Mismatched ID fails cleanly without modifying installed extension.
        var mismatchSummary = await store.CheckForUpdatesAsync();
        Assert.Equal(ExtensionUpdateOutcome.Failed, Assert.Single(mismatchSummary.Results).Outcome);
        Assert.Equal("1.0.0", store.GetById("com.test.guard")!.Manifest.Version);

        // 2. Redirect to non-HTTPS fails cleanly.
        redirectToHttp = true;
        var httpRedirectSummary = await store.CheckForUpdatesAsync();
        Assert.Equal(ExtensionUpdateOutcome.Failed, Assert.Single(httpRedirectSummary.Results).Outcome);
        redirectToHttp = false;

        // 3. Expanded host (*.example.test) + expanded capability (search) stages update for review.
        remoteBytes = MakeExtensionZipBytes(
            "com.test.guard",
            "1.1.0",
            ["playlist", "search"],
            ["*.example.test", "extra.cdn.test"],
            updateUrl: updateUrl);

        var escalatedSummary = await store.CheckForUpdatesAsync();
        Assert.Equal(ExtensionUpdateOutcome.StagedForReview, Assert.Single(escalatedSummary.Results).Outcome);
        Assert.Equal("1.0.0", store.GetById("com.test.guard")!.Manifest.Version);

        var pending = store.GetPendingUpdate("com.test.guard");
        Assert.NotNull(pending);
        Assert.True(pending!.HasPermissionEscalation);
        Assert.Equal([ExtensionCapability.Search], pending.AddedCapabilities);
        Assert.Equal(["*.example.test", "extra.cdn.test"], pending.AddedHosts);

        // 4. User approves staged update -> applies v1.1.0 and clears pending update.
        var applied = store.ApprovePendingUpdate("com.test.guard");
        Assert.Equal("1.1.0", applied.Manifest.Version);
        Assert.True(applied.IsEnabled);
        Assert.Null(store.GetPendingUpdate("com.test.guard"));
    }

    // ==================== 2. Cover Art & .webm Sidecar ====================

    [Fact]
    public void ImageFormatHelperDetectsMimeTypesAndExtensionsViaMagicBytes()
    {
        Assert.Equal("image/png", ImageFormatHelper.DetectMimeType(ValidPngBytes));
        Assert.Equal(".png", ImageFormatHelper.DetectSidecarExtension(ValidPngBytes));

        Assert.Equal("image/jpeg", ImageFormatHelper.DetectMimeType(MinimalJpegBytes));
        Assert.Equal(".jpg", ImageFormatHelper.DetectSidecarExtension(MinimalJpegBytes));

        Assert.Equal("image/webp", ImageFormatHelper.DetectMimeType(MinimalWebpBytes));
        Assert.Equal(".webp", ImageFormatHelper.DetectSidecarExtension(MinimalWebpBytes));
    }

    private sealed class ArtworkHeaderTestProvider : MusicoWindows.Extensions.ICloudSourceProvider
    {
        public string ProviderId => "ext.artwork-test";
        public string DisplayName => "Artwork Test";
        public string BadgeLabel => "ArtTest";
        public RootKind RootKind => RootKind.CloudExtension;
        public string CloudSourceKey => "ext.artwork-test";
        public bool SupportsCookies => false;
        public bool SupportsProxy => false;
        public bool UsesFlatCloudListing => true;
        public bool SupportsProgressiveTree => false;
        public bool SupportsCollectionImport => true;
        public bool IsAvailable => true;
        public IReadOnlyList<MusicoWindows.Extensions.ExtensionQualityOption> AvailableQualities => Array.Empty<MusicoWindows.Extensions.ExtensionQualityOption>();
        public string? DefaultStreamQualityId => null;
        public string? DefaultDownloadQualityId => null;
        public bool MatchesUrlPattern(string? candidate) => false;
        public Task<MusicoWindows.Extensions.CloudCanHandleResult> TryMatchAsync(string rawInput, string extractedUrl, CancellationToken ct = default) =>
            Task.FromResult(new MusicoWindows.Extensions.CloudCanHandleResult(false));
        public string? TryExtractRemoteId(string url) => null;
        public Task<CloudPlaylistResult> FetchPlaylistAsync(string sourceUrl, string? cookie, string? proxyUrl, CancellationToken ct = default) =>
            throw new NotSupportedException();
        public Task<MusicoWindows.Extensions.CloudTreeSession?> StartProgressiveFetchAsync(string sourceUrl, string? cookie, string? proxyUrl, CancellationToken ct = default) =>
            Task.FromResult<MusicoWindows.Extensions.CloudTreeSession?>(null);
        public Task<CloudAudioStream> ResolveAudioStreamAsync(string remoteTrackId, string? relativePath, string? cookie, string? proxyUrl, bool forceRefresh = false, CancellationToken ct = default, string? quality = null, CloudStreamPurpose purpose = CloudStreamPurpose.Stream) =>
            throw new NotSupportedException();
        public void ConfigureArtworkRequest(HttpRequestMessage request)
        {
            request.Headers.TryAddWithoutValidation("Referer", "https://art-provider.example/");
        }
    }

    [Fact]
    public async Task CloudThumbnailCacheSendsDefaultUserAgentAndDelegatesCustomHeadersViaCallback()
    {
        HttpRequestMessage? capturedDefault = null;
        HttpRequestMessage? capturedCustom = null;
        var handler = new StubHttpHandler((req, _) =>
        {
            if (req.RequestUri!.AbsoluteUri.Contains("custom", StringComparison.Ordinal))
                capturedCustom = req;
            else
                capturedDefault = req;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent(ValidPngBytes)
            });
        });

        var cache = new CloudThumbnailCache(handler: handler);
        byte[]? bytes = await cache.GetBytesAsync("https://cdn.example/cover.png");
        Assert.NotNull(bytes);
        Assert.Equal(ValidPngBytes, bytes);
        Assert.NotNull(capturedDefault);
        Assert.Equal(CloudThumbnailCache.DefaultUserAgent, capturedDefault!.Headers.UserAgent.ToString());
        Assert.False(capturedDefault.Headers.Contains("Referer"));

        byte[]? customBytes = await cache.GetBytesAsync(
            "https://cdn.example/custom.png",
            req => req.Headers.TryAddWithoutValidation("Referer", "https://art-provider.example/"));
        Assert.NotNull(customBytes);
        Assert.NotNull(capturedCustom);
        Assert.True(capturedCustom!.Headers.TryGetValues("Referer", out var refs));
        Assert.Contains("https://art-provider.example/", refs);
    }

    [Fact]
    public async Task CloudExporterEmbedsDetectedPngMimeIntoMp3AndFlacAndWritesWebmSidecar()
    {
        string stateDir = NewTempDir();
        string cacheDir = Path.Combine(stateDir, "CloudCache");
        string destDir = NewTempDir();
        Directory.CreateDirectory(cacheDir);

        const string pngCoverUrl = "https://cdn.example/bfs/archive/testcover.png";
        const string jpgCoverUrl = "https://cdn.example/bfs/archive/testcover.jpg";

        var handler = new StubHttpHandler((req, _) =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url == pngCoverUrl)
            {
                Assert.True(req.Headers.TryGetValues("Referer", out var refs));
                Assert.Contains("https://art-provider.example/", refs);
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(ValidPngBytes)
                });
            }
            if (url == jpgCoverUrl)
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(MinimalJpegBytes)
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        var thumbCache = new CloudThumbnailCache(handler: handler);
        var audioCache = new CloudAudioCache(cacheDir, handler: handler);
        var artworkCache = new ArtworkCache(Path.Combine(stateDir, "Artwork"));
        var registry = new MusicoWindows.Extensions.CloudProviderRegistry();
        registry.RegisterExtensionProvider(new ArtworkHeaderTestProvider());
        var exporter = new CloudExporter(audioCache, registry, artworkCache, thumbnailCache: thumbCache);

        var rootId = Guid.NewGuid();
        var extRoot = new LibraryRoot(rootId, "Art Mix", RootKind.CloudExtension, "https://art-provider.example/mix/1", ProviderId: "ext.artwork-test");

        // 1. MP3 with PNG cover fetched from remote URL -> embeds with image/png MIME
        var mp3Track = new Track(
            Id: Track.MakeId(rootId, "t_mp3.mp3"),
            RootId: rootId,
            RelativePath: "t_mp3.mp3",
            Title: "PNG MP3",
            Artist: "Artist",
            Album: "Album",
            AlbumArtist: "Artwork Test",
            TrackNumber: 1,
            DiscNumber: 1,
            DurationSeconds: 60,
            FileSize: 0,
            ModifiedUtc: DateTimeOffset.UnixEpoch,
            HasEmbeddedLyrics: false,
            DateAddedUtc: DateTimeOffset.UnixEpoch,
            CloudSource: "ext.artwork-test",
            RemoteTrackId: "art_mp3",
            RemoteArtworkUrl: pngCoverUrl);
        File.WriteAllBytes(Path.Combine(cacheDir, $"{mp3Track.Id}.mp3"), MakeMinimalMp3Bytes());

        string exportedMp3 = await exporter.ExportTrackAsync(mp3Track, extRoot, destDir);
        using (var mp3Tag = TagLib.File.Create(exportedMp3))
        {
            Assert.Single(mp3Tag.Tag.Pictures);
            Assert.Equal("image/png", mp3Tag.Tag.Pictures[0].MimeType);
        }

        // 2. FLAC with PNG cover -> embeds with image/png MIME
        var flacTrack = mp3Track with
        {
            Id = Track.MakeId(rootId, "t_flac.flac"),
            RelativePath = "t_flac.flac",
            Title = "PNG FLAC",
            RemoteTrackId = "art_flac"
        };
        File.WriteAllBytes(Path.Combine(cacheDir, $"{flacTrack.Id}.flac"), MakeMinimalFlacBytes());

        string exportedFlac = await exporter.ExportTrackAsync(flacTrack, extRoot, destDir);
        using (var flacTag = TagLib.File.Create(exportedFlac))
        {
            Assert.Single(flacTag.Tag.Pictures);
            Assert.Equal("image/png", flacTag.Tag.Pictures[0].MimeType);
        }

        // 3. .webm with PNG cover -> writes sibling <stem>.png
        var webmPngTrack = mp3Track with
        {
            Id = Track.MakeId(rootId, "t_webm1.webm"),
            RelativePath = "t_webm1.webm",
            Title = "WebM PNG",
            RemoteTrackId = "art_webm1"
        };
        File.WriteAllBytes(Path.Combine(cacheDir, $"{webmPngTrack.Id}.webm"), [0x1A, 0x45, 0xDF, 0xA3]);

        string exportedWebmPng = await exporter.ExportTrackAsync(webmPngTrack, extRoot, destDir);
        string sidecarPng = Path.Combine(destDir, "WebM PNG.png");
        Assert.True(File.Exists(exportedWebmPng));
        Assert.True(File.Exists(sidecarPng));
        Assert.Equal(ValidPngBytes, File.ReadAllBytes(sidecarPng));

        // 4. .webm with JPEG cover -> writes sibling <stem>.jpg
        var webmJpgTrack = mp3Track with
        {
            Id = Track.MakeId(rootId, "t_webm2.webm"),
            RelativePath = "t_webm2.webm",
            Title = "WebM JPG",
            RemoteTrackId = "art_webm2",
            RemoteArtworkUrl = jpgCoverUrl
        };
        File.WriteAllBytes(Path.Combine(cacheDir, $"{webmJpgTrack.Id}.webm"), [0x1A, 0x45, 0xDF, 0xA3]);

        string exportedWebmJpg = await exporter.ExportTrackAsync(webmJpgTrack, extRoot, destDir);
        string sidecarJpg = Path.Combine(destDir, "WebM JPG.jpg");
        Assert.True(File.Exists(exportedWebmJpg));
        Assert.True(File.Exists(sidecarJpg));
        Assert.Equal(MinimalJpegBytes, File.ReadAllBytes(sidecarJpg));
    }

    [Fact]
    public async Task MetadataReaderAndLibraryScannerLoadSiblingCoverArtForWebmAndUntaggedMp3()
    {
        string stateDir = NewTempDir();
        string musicDir = NewTempDir();
        string readerOnlyDir = NewTempDir();

        // Separate reader-only check for .webp sidecar discovery (MinimalWebpBytes is a header-only stub
        // rather than a WIC-decodable image for ArtworkCache on Windows).
        string webpSidecarAudio = Path.Combine(readerOnlyDir, "webp_sidecar.webm");
        File.WriteAllBytes(webpSidecarAudio, [0x1A, 0x45, 0xDF, 0xA3]);
        File.WriteAllBytes(Path.Combine(readerOnlyDir, "webp_sidecar.webp"), MinimalWebpBytes);

        // .webm + valid .png sidecar for end-to-end FolderScanner + LibraryStore + ArtworkCache scan
        string webmPath = Path.Combine(musicDir, "video_audio.webm");
        File.WriteAllBytes(webmPath, [0x1A, 0x45, 0xDF, 0xA3]);
        File.WriteAllBytes(Path.Combine(musicDir, "video_audio.png"), ValidPngBytes);

        // .mp3 without embedded art + .png sidecar
        string mp3Path = Path.Combine(musicDir, "plain_song.mp3");
        File.WriteAllBytes(mp3Path, MakeMinimalMp3Bytes());
        File.WriteAllBytes(Path.Combine(musicDir, "plain_song.png"), ValidPngBytes);

        var reader = new TagLibMetadataReader();
        var webpSidecarMeta = await reader.ReadAsync(webpSidecarAudio);
        Assert.Equal(MinimalWebpBytes, webpSidecarMeta.ArtworkBytes);

        var webmMeta = await reader.ReadAsync(webmPath);
        Assert.Equal(ValidPngBytes, webmMeta.ArtworkBytes);

        var mp3Meta = await reader.ReadAsync(mp3Path);
        Assert.Equal(ValidPngBytes, mp3Meta.ArtworkBytes);

        // Verify FolderScanner + LibraryStore scan picks up .webm and its sibling artwork.
        var scanned = FolderScanner.Scan(musicDir);
        Assert.NotNull(scanned);
        Assert.Contains(scanned!, f => f.RelativePath == "video_audio.webm");
        Assert.Contains(scanned!, f => f.RelativePath == "plain_song.mp3");

        var artwork = new ArtworkCache(Path.Combine(stateDir, "Artwork"));
        var store = new LibraryStore(stateDir, musicDir, reader, artwork);
        await store.RescanAsync();

        var webmTrack = store.Tracks.FirstOrDefault(t => t.RelativePath == "video_audio.webm");
        var mp3Track = store.Tracks.FirstOrDefault(t => t.RelativePath == "plain_song.mp3");
        Assert.NotNull(webmTrack);
        Assert.NotNull(mp3Track);
        Assert.NotNull(webmTrack!.ArtworkKey);
        Assert.NotNull(mp3Track!.ArtworkKey);
    }

    // ==================== 3. Queue Decoupling & Named Queues ====================

    [Fact]
    public async Task ViewedQueueDecouplingMutationsAndPlayTrackWorkWithoutPlaybackInterruption()
    {
        string stateDir = NewTempDir();
        string musicDir = NewTempDir();
        TestHelpers.TouchFile(musicDir, "a.mp3");
        TestHelpers.TouchFile(musicDir, "b.mp3");
        TestHelpers.TouchFile(musicDir, "c.mp3");

        var lib = new LibraryStore(stateDir, musicDir, new StubMetadataReader(), new ArtworkCache(Path.Combine(stateDir, "Art")));
        await lib.RescanAsync();
        string idA = lib.Tracks.First(t => t.RelativePath == "a.mp3").Id;
        string idB = lib.Tracks.First(t => t.RelativePath == "b.mp3").Id;
        string idC = lib.Tracks.First(t => t.RelativePath == "c.mp3").Id;

        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        var engine = new FakeAudioEngine();
        using var player = new PlayerController(lib, queues, history, engine, Path.Combine(stateDir, "settings.json"), enableTicker: false);

        // On launch, ViewedQueueId == ActiveId.
        Assert.Equal(queues.ActiveId, queues.ViewedQueueId);

        // Start playing Queue 1 with [a, b].
        player.Play([idA, idB], startAt: 0);
        Guid queue1Id = queues.ActiveId;
        Assert.True(player.IsPlaying);
        Assert.Equal(idA, player.CurrentTrack?.Id);

        // Create Queue 2 and select it for viewing -> ActiveId and playback stay untouched!
        Guid queue2Id = queues.AddQueue("Queue 2", [idB, idC], startAt: 0, activate: false);
        queues.SelectViewedQueue(queue2Id);

        Assert.Equal(queue2Id, queues.ViewedQueueId);
        Assert.Equal(queue1Id, queues.ActiveId);
        Assert.True(player.IsPlaying);
        Assert.Equal(idA, player.CurrentTrack?.Id);

        // Mutations targeting ViewedQueueId do NOT disturb playing Queue 1.
        player.AddToQueue([idA], queues.ViewedQueueId);
        Assert.Equal([idB, idC, idA], queues.GetById(queue2Id)!.TrackIds);

        player.MoveInQueue(queues.ViewedQueueId, 2, 0);
        Assert.Equal([idA, idB, idC], queues.GetById(queue2Id)!.TrackIds);

        player.SortQueue(queues.ViewedQueueId, TrackSortMode.NameDescending);
        Assert.Equal([idC, idB, idA], queues.GetById(queue2Id)!.TrackIds);

        queues.Rename(queues.ViewedQueueId, "Renamed Q2");
        Assert.Equal("Renamed Q2", queues.GetById(queue2Id)!.Name);

        player.RemoveFromQueue(queues.ViewedQueueId, 1);
        Assert.Equal([idC, idA], queues.GetById(queue2Id)!.TrackIds);

        Assert.Equal(queue1Id, queues.ActiveId);
        Assert.True(player.IsPlaying);
        Assert.Equal(idA, player.CurrentTrack?.Id);

        // Clicking a song row in ViewedQueueId switches playback via PlayTrack(ViewedQueueId, index).
        player.PlayTrack(queues.ViewedQueueId, 0);
        Assert.Equal(queue2Id, queues.ActiveId);
        Assert.True(player.IsPlaying);
        Assert.Equal(idC, player.CurrentTrack?.Id);

        // Clearing non-playing Queue 1 does not interrupt Queue 2 playback.
        queues.SelectViewedQueue(queue1Id);
        player.ClearQueue(queues.ViewedQueueId);
        Assert.Empty(queues.GetById(queue1Id)!.TrackIds);
        Assert.Equal(queue2Id, queues.ActiveId);
        Assert.True(player.IsPlaying);
        Assert.Equal(idC, player.CurrentTrack?.Id);

        // Deleting the PLAYING queue pauses cleanly without auto-starting Queue 1!
        player.DeleteQueue(queue2Id);
        Assert.False(player.IsPlaying);
        Assert.Equal(queue1Id, queues.ActiveId);
        Assert.Equal(queue1Id, queues.ViewedQueueId);
        Assert.Null(player.CurrentTrack);
    }

    [Fact]
    public async Task DeletingPlayingQueueWithPopulatedFallbackLoadsPausedWithoutAutoStarting()
    {
        string stateDir = NewTempDir();
        string musicDir = NewTempDir();
        TestHelpers.TouchFile(musicDir, "a.mp3");
        TestHelpers.TouchFile(musicDir, "b.mp3");

        var lib = new LibraryStore(stateDir, musicDir, new StubMetadataReader(), new ArtworkCache(Path.Combine(stateDir, "Art")));
        await lib.RescanAsync();
        string idA = lib.Tracks.First(t => t.RelativePath == "a.mp3").Id;
        string idB = lib.Tracks.First(t => t.RelativePath == "b.mp3").Id;

        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        var engine = new FakeAudioEngine();
        using var player = new PlayerController(lib, queues, history, engine, Path.Combine(stateDir, "settings.json"), enableTicker: false);

        Guid q1 = queues.ActiveId;
        player.Play([idA], startAt: 0);
        player.PlayInNamedQueue("Songs", [idB], startAt: 0);
        Guid songsQueueId = queues.ActiveId;
        Assert.True(player.IsPlaying);

        // Deleting playing "Songs" queue must NOT auto-start q1!
        player.DeleteQueue(songsQueueId);
        Assert.False(player.IsPlaying);
        Assert.Equal(q1, queues.ActiveId);
        Assert.Equal(idA, player.CurrentTrack?.Id);

        // Clearing playing q1 also stops cleanly.
        player.Play([idA], startAt: 0);
        Assert.True(player.IsPlaying);
        player.ClearQueue(q1);
        Assert.False(player.IsPlaying);
        Assert.Null(player.CurrentTrack);
    }
}
