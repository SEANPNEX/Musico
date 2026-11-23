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

namespace MusicoWindows.Tests;

public sealed class CloudProviderRegistryTests
{
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

    private sealed class DummyAudioEngine : IAudioEngine
    {
        public event Action? TrackEnded { add { } remove { } }
        public float Volume { get; set; } = 1.0f;
        public bool IsPlaying => false;
        public double CurrentTimeSeconds => 0;
        public double DurationSeconds => 0;
        public string? LoadedPath { get; private set; }
        public void ApplyEq(EqSettings eq) { }
        public void Load(string path) => LoadedPath = path;
        public void Play() { }
        public void Pause() { }
        public void Seek(double seconds) { }
        public void Unload() => LoadedPath = null;
        public void Dispose() { }
    }

    private static string BuildSampleExtensionScript(string id, string host, string urlPattern, string titlePrefix) => $$"""
        var manifest = {
          apiVersion: 1,
          id: '{{id}}',
          name: '{{titlePrefix}} Source',
          version: '1.0.0',
          badgeLabel: '{{titlePrefix}}',
          capabilities: ['playlist', 'stream', 'cookie'],
          urlPatterns: ['{{urlPattern}}'],
          permissions: { hosts: ['{{host}}'] }
        };
        function canHandle(input) {
          var raw = (input && (input.extractedUrl || input.rawText)) || '';
          var m = String(raw).match(/\/item\/([a-z0-9_-]+)/i);
          if (!m) return { matched: false };
          return { matched: true, normalizedUrl: 'https://{{host}}/item/' + m[1], remoteId: m[1], kind: 'playlist' };
        }
        async function fetchCollection(ctx, req) {
          var m = String(req.url).match(/\/item\/([a-z0-9_-]+)/i);
          var rid = m ? m[1] : 'unknown';
          return {
            remoteId: rid,
            title: '{{titlePrefix}} ' + rid + (req.cookie ? (' [' + req.cookie + ']') : ''),
            tracks: [
              {
                remoteTrackId: 'trk-' + rid,
                relativePath: '01.mp3',
                title: '{{titlePrefix}} Song',
                artist: 'Ext Artist',
                album: 'Ext Album',
                trackNumber: 1,
                durationSec: 42
              }
            ],
            nextCursor: null
          };
        }
        async function resolveStream(ctx, req) {
          return {
            url: 'https://{{host}}/audio/' + req.remoteTrackId + '.mp3',
            headers: { 'X-Cookie-Seen': req.cookie || 'none' },
            fileExtension: 'mp3'
          };
        }
        module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection, resolveStream: resolveStream };
        """;

    [Fact]
    public void BuiltinProvidersRegisterWithExpectedCapabilitiesAndFlags()
    {
        var registry = new CloudProviderRegistry();

        Assert.Equal(3, registry.AllProviders.Count);

        var yt = registry.GetById(CloudProviderRegistry.BuiltinYouTubeId);
        var bili = registry.GetById(CloudProviderRegistry.BuiltinBilibiliId);
        var gd = registry.GetById(CloudProviderRegistry.BuiltinGoogleDriveId);

        Assert.NotNull(yt);
        Assert.NotNull(bili);
        Assert.NotNull(gd);

        Assert.True(yt!.UsesFlatCloudListing);
        Assert.False(yt.SupportsProgressiveTree);
        Assert.True(yt.SupportsProxy);

        Assert.True(bili!.UsesFlatCloudListing);
        Assert.False(bili.SupportsProgressiveTree);
        Assert.False(bili.SupportsProxy);

        Assert.False(gd!.UsesFlatCloudListing);
        Assert.True(gd.SupportsProgressiveTree);
        Assert.True(gd.SupportsProxy);

        Assert.Same(yt, registry.GetBySourceOrProviderId(CloudSources.YouTube));
        Assert.Same(bili, registry.GetBySourceOrProviderId(CloudSources.Bilibili));
        Assert.Same(gd, registry.GetBySourceOrProviderId(CloudSources.GoogleDrive));
    }

    [Fact]
    public void V1113LibraryAndManifestJsonDeserializeWithResolvedProviderIds()
    {
        // v1.1.13 library.json without providerId or linkedProviderId
        const string legacyLibraryJson = """
        {
          "roots": [
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "name": "Legacy YT",
              "kind": "CloudYouTube",
              "path": "https://youtube.com/playlist?list=PLlegacy",
              "sourceUrl": "https://youtube.com/playlist?list=PLlegacy",
              "remoteId": "PLlegacy"
            },
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "name": "Legacy Local Tracked",
              "kind": "Picked",
              "path": "C:\\Music\\Tracked",
              "sourceUrl": "https://space.bilibili.com/1/favlist?fid=99",
              "remoteId": "99",
              "linkedCloudKind": "CloudBilibili",
              "trackedSubfolders": {
                "DriveSub": {
                  "sourceUrl": "https://drive.google.com/drive/folders/abc",
                  "remoteId": "abc",
                  "cloudKind": "CloudGoogleDrive"
                }
              }
            }
          ],
          "tracks": []
        }
        """;

        using var doc = JsonDocument.Parse(legacyLibraryJson);
        var roots = JsonSerializer.Deserialize<List<LibraryRoot>>(
            doc.RootElement.GetProperty("roots").GetRawText(),
            Persistence.JsonOptions)!;

        Assert.Equal(2, roots.Count);
        Assert.True(roots[0].IsCloud);
        Assert.Equal(CloudProviderRegistry.BuiltinYouTubeId, roots[0].ResolvedProviderId);
        Assert.Equal(CloudProviderRegistry.BuiltinYouTubeId, roots[0].EffectiveProviderId);

        Assert.True(roots[1].IsCloudTrackedLocal);
        Assert.Equal(CloudProviderRegistry.BuiltinBilibiliId, roots[1].ResolvedLinkedProviderId);
        Assert.Equal(CloudProviderRegistry.BuiltinBilibiliId, roots[1].EffectiveProviderId);
        Assert.Equal(CloudProviderRegistry.BuiltinGoogleDriveId, roots[1].TrackedSubfolders!["DriveSub"].ResolvedProviderId);

        // v1.1.13 .musico-cloud.json without providerId
        const string legacyManifestJson = """
        {
          "sourceUrl": "https://youtube.com/playlist?list=PLold",
          "remoteId": "PLold",
          "cloudKind": "CloudYouTube",
          "lastSyncedUtc": "2026-01-01T00:00:00Z",
          "tracks": { "v1": "01.mp3" }
        }
        """;
        var manifest = JsonSerializer.Deserialize<CloudFolderManifest>(legacyManifestJson, new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        Assert.NotNull(manifest);
        Assert.Null(manifest!.ProviderId);
        Assert.Equal("CloudYouTube", manifest.CloudKind);

        // CloudExtension root round-trips cleanly
        var extRoot = new LibraryRoot(
            Guid.NewGuid(),
            "Custom Ext",
            RootKind.CloudExtension,
            "https://ext.test/1",
            SourceUrl: "https://ext.test/1",
            RemoteId: "1",
            ProviderId: "ext.com.example");
        string roundTrip = JsonSerializer.Serialize(extRoot, Persistence.JsonOptions);
        var loadedExt = JsonSerializer.Deserialize<LibraryRoot>(roundTrip, Persistence.JsonOptions)!;
        Assert.Equal(RootKind.CloudExtension, loadedExt.Kind);
        Assert.True(loadedExt.IsCloud);
        Assert.Equal("ext.com.example", loadedExt.ResolvedProviderId);
    }

    [Fact]
    public async Task LibraryStoreDedupesByKindProviderIdAndRemoteIdAndPreservesDataWhenExtensionDisabledOrUninstalled()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();

        var registry = new CloudProviderRegistry();
        var extStore = new ExtensionStore(stateDir);
        extStore.BindRegistry(registry);

        string scriptA = BuildSampleExtensionScript("com.test.sourcea", "a.test", @"^https?://a\.test/item/[a-z0-9_-]+$", "SourceA");
        string scriptB = BuildSampleExtensionScript("com.test.sourceb", "b.test", @"^https?://b\.test/item/[a-z0-9_-]+$", "SourceB");

        var pkgA = ExtensionPackageLoader.LoadSingleFileScript(scriptA);
        var pkgB = ExtensionPackageLoader.LoadSingleFileScript(scriptB);

        var instA = extStore.StageImportedPackage(pkgA);
        var instB = extStore.StageImportedPackage(pkgB);

        // Pending Permission Review -> NOT yet in CloudProviderRegistry
        Assert.Null(registry.GetById("ext.com.test.sourcea"));

        extStore.ApproveAndEnable(instA.Id);
        extStore.ApproveAndEnable(instB.Id);

        Assert.NotNull(registry.GetById("ext.com.test.sourcea"));
        Assert.NotNull(registry.GetById("ext.com.test.sourceb"));

        var library = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Artwork")),
            providerRegistry: registry);

        // Add same remoteId ("shared-1") from two distinct extensions -> BOTH succeed!
        var rootA = await library.AddCloudRootAsync("https://a.test/item/shared-1", cookie: "cookie-a");
        var rootB = await library.AddCloudRootAsync("https://b.test/item/shared-1", cookie: "cookie-b");

        Assert.Equal(RootKind.CloudExtension, rootA.Kind);
        Assert.Equal("ext.com.test.sourcea", rootA.ResolvedProviderId);
        Assert.Equal("SourceA shared-1 [cookie-a]", rootA.Name);
        Assert.Equal("SourceB shared-1 [cookie-b]", rootB.Name);

        // Adding same (Kind, ResolvedProviderId, RemoteId) again is rejected as duplicate!
        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            library.AddCloudRootAsync("https://a.test/item/shared-1"));

        var trackA = Assert.Single(library.Tracks, t => t.RootId == rootA.Id);
        var playlistId = library.CreatePlaylist("My Mixed Playlist");
        library.AddToPlaylist([trackA.Id], playlistId);
        library.ToggleFavourite(trackA.Id);

        Assert.True(library.IsAvailable(rootA));
        Assert.True(library.IsAvailable(trackA));

        // Disabling extension keeps tracks, playlists, and favourites intact, while marking unavailable!
        extStore.SetEnabled(instA.Id, false);
        await library.RescanAsync();

        Assert.False(library.IsAvailable(rootA));
        Assert.False(library.IsAvailable(trackA));
        Assert.True(library.IsAvailable(rootB));
        Assert.NotNull(library.GetTrack(trackA.Id));
        Assert.Single(library.GetPlaylistTracks(playlistId));
        Assert.True(library.IsFavourite(trackA.Id));

        // Uninstalling still preserves tracks/playlists/favourites non-destructively
        extStore.Uninstall(instA.Id);
        Assert.False(library.IsAvailable(rootA));
        Assert.NotNull(library.GetTrack(trackA.Id));
        Assert.Single(library.GetPlaylistTracks(playlistId));

        // Re-installing & enabling restores availability seamlessly
        var reimported = extStore.StageImportedPackage(ExtensionPackageLoader.LoadSingleFileScript(scriptA));
        extStore.ApproveAndEnable(reimported.Id);
        Assert.True(library.IsAvailable(rootA));
        Assert.True(library.IsAvailable(trackA));
    }

    [Fact]
    public async Task ManualProviderOverrideCookieIsolationAndStreamExportWorkThroughRegistry()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        string cacheDir = Path.Combine(stateDir, "CloudCache");
        string exportDir = TestHelpers.MakeTempDir();

        string? observedHeader = null;
        var handler = new StubHttpHandler(req =>
        {
            if (req.RequestUri!.AbsoluteUri == "https://a.test/audio/trk-42.mp3")
            {
                observedHeader = req.Headers.TryGetValues("X-Cookie-Seen", out var vals)
                    ? string.Join(",", vals)
                    : null;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(Encoding.UTF8.GetBytes("ID3-stub-mp3-bytes"))
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var registry = new CloudProviderRegistry();
        var runtime = new JintExtensionRuntime(handler);
        var extStore = new ExtensionStore(stateDir, handler, runtime);
        extStore.BindRegistry(registry);

        string script = BuildSampleExtensionScript("com.test.iso", "a.test", @"^https?://a\.test/item/[a-z0-9_-]+$", "Iso");
        var ext = extStore.StageImportedPackage(ExtensionPackageLoader.LoadSingleFileScript(script));
        extStore.ApproveAndEnable(ext.Id);

        // Manual override to disabled/unknown provider throws ExtensionUnavailable
        await Assert.ThrowsAsync<CloudServiceException>(() =>
            CloudUrlClassifier.ClassifyAsync("https://a.test/item/42", registry, preferredProviderId: "ext.does.not.exist"));

        // Manual override to wrong provider throws InvalidUrl
        var wrongEx = await Assert.ThrowsAsync<CloudServiceException>(() =>
            CloudUrlClassifier.ClassifyAsync("https://a.test/item/42", registry, preferredProviderId: CloudProviderRegistry.BuiltinYouTubeId));
        Assert.Equal(CloudErrorCode.InvalidUrl, wrongEx.Code);

        var lib = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Artwork")),
            providerRegistry: registry);

        // Two roots from the same provider with isolated cookies
        var rootWithCookie = await lib.AddCloudRootAsync("https://a.test/item/42", cookie: "token=alpha", preferredProviderId: "ext.com.test.iso");
        var rootNoCookie = await lib.AddCloudRootAsync("https://a.test/item/43", cookie: null, preferredProviderId: "ext.com.test.iso");

        Assert.Contains("[token=alpha]", rootWithCookie.Name);
        Assert.DoesNotContain("[", rootNoCookie.Name);

        var track42 = Assert.Single(lib.Tracks, t => t.RootId == rootWithCookie.Id);
        var cache = new CloudAudioCache(cacheDir, handler: handler);
        var exporter = new CloudExporter(cache, registry);

        string exported = await exporter.ExportTrackAsync(track42, rootWithCookie, exportDir);
        Assert.True(File.Exists(exported));
        Assert.Equal("token=alpha", observedHeader);

        // PlayerController resolves and loads extension tracks through CloudProviderRegistry
        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        var dummyEngine = new DummyAudioEngine();
        using var player = new PlayerController(
            lib,
            queues,
            history,
            dummyEngine,
            Path.Combine(stateDir, "settings.json"),
            enableTicker: false,
            cloudCache: cache,
            providerRegistry: registry);

        player.Play([track42.Id]);
        for (int i = 0; i < 40 && dummyEngine.LoadedPath is null; i++)
        {
            await Task.Delay(25);
        }
        Assert.NotNull(dummyEngine.LoadedPath);

        var provider = registry.GetProvider(rootWithCookie)!;
        var stream = await provider.ResolveAudioStreamAsync(track42.RemoteTrackId!, track42.RelativePath, rootNoCookie.Cookie, null);
        Assert.Equal("none", stream.Headers!["X-Cookie-Seen"]);
    }
}
