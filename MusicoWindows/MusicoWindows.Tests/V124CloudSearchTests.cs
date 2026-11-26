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

public sealed class V124CloudSearchTests : IDisposable
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

    private sealed class AsyncStubHttpHandler(
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
        public event Action? TrackEnded;
        public void RaiseTrackEnded() => TrackEnded?.Invoke();

        public void Load(string path)
        {
            DurationSeconds = 120.0;
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

    private static byte[] MakeExtensionZipBytes(object manifestObj, string indexJs)
    {
        using var ms = new MemoryStream();
        using (var zip = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
        {
            var mEntry = zip.CreateEntry("manifest.json");
            using (var sw = new StreamWriter(mEntry.Open(), new UTF8Encoding(false)))
            {
                sw.Write(JsonSerializer.Serialize(manifestObj));
            }

            var jEntry = zip.CreateEntry("index.js");
            using (var sw = new StreamWriter(jEntry.Open(), new UTF8Encoding(false)))
            {
                sw.Write(indexJs);
            }
        }
        return ms.ToArray();
    }

    private static LoadedExtensionPackage LoadExt(
        string id,
        string[] capabilities,
        string indexJs,
        string[]? urlPatterns = null,
        string[]? hosts = null,
        string[]? searchKinds = null)
    {
        var manifest = new Dictionary<string, object?>
        {
            ["apiVersion"] = ExtensionManifest.CurrentApiVersion,
            ["id"] = id,
            ["name"] = $"Ext {id}",
            ["badgeLabel"] = id.ToUpperInvariant(),
            ["version"] = "1.0.0",
            ["entry"] = "index.js",
            ["capabilities"] = capabilities,
            ["permissions"] = new { hosts = hosts ?? ["api.search.test", "cdn.search.test"] }
        };
        if (urlPatterns is not null)
        {
            manifest["urlPatterns"] = urlPatterns;
        }
        if (searchKinds is not null)
        {
            manifest["searchKinds"] = searchKinds;
        }

        byte[] zip = MakeExtensionZipBytes(manifest, indexJs);
        return ExtensionPackageLoader.LoadFromBytes(zip, $"{id}.musicoext");
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

    private static readonly byte[] ValidPngBytes =
    [
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
        0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0x1F, 0xEA, 0x02,
        0x00, 0x03, 0xEF, 0x01, 0x99, 0xC3, 0x49, 0xF5, 0x60, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
        0x44, 0xAE, 0x42, 0x60, 0x82
    ];

    [Fact]
    public void ManifestValidation_AllowsSearchOnlyAndSearchStreamCookieWithoutUrlPatterns()
    {
        var searchOnly = LoadExt(
            "search-only",
            ["search"],
            "exports.search = async function(ctx, req) { return { items: [], hasMore: false }; };");
        Assert.Equal([ExtensionSearchKind.Track, ExtensionSearchKind.Playlist, ExtensionSearchKind.Album],
            searchOnly.Manifest.EffectiveSearchKinds);

        var searchStreamCookie = LoadExt(
            "search-stream",
            ["search", "stream", "cookie"],
            """
            exports.search = async function(ctx, req) { return []; };
            exports.resolveStream = async function(ctx, req) { return { url: 'https://cdn.search.test/a.mp3' }; };
            """,
            searchKinds: ["track", "album"]);
        Assert.Equal([ExtensionSearchKind.Track, ExtensionSearchKind.Album],
            searchStreamCookie.Manifest.EffectiveSearchKinds);

        // Missing 'search' hook throws
        Assert.Throws<InvalidDataException>(() => LoadExt(
            "missing-search",
            ["search"],
            "exports.other = function() {};"));

        // Invalid searchKinds throws
        Assert.Throws<InvalidDataException>(() => LoadExt(
            "bad-kind",
            ["search"],
            "exports.search = function() { return []; };",
            searchKinds: ["podcast"]));

        // Playlist still requires urlPatterns, canHandle, and fetchCollection
        Assert.Throws<InvalidDataException>(() => LoadExt(
            "playlist-no-patterns",
            ["playlist"],
            "exports.canHandle = function() { return { matched: false }; }; exports.fetchCollection = function() { return {}; };"));
    }

    [Fact]
    public void ExtensionStore_SearchSourcesNeverExposeBuiltins_AndExcludeImportOnlyOrDisabled()
    {
        string stateDir = NewTempDir();
        var registry = new CloudProviderRegistry();
        var store = new ExtensionStore(stateDir, runtime: new JintExtensionRuntime());
        store.BindRegistry(registry);

        // Initially no search extensions
        Assert.Empty(store.ActiveSearchExtensions);

        // Built-ins have playlist/tree import but must NEVER be exposed as search sources
        Assert.True(registry.AvailableProviders.All(p => p.SupportsCollectionImport));

        // Install a playlist-only extension (cannot search)
        var playlistExt = LoadExt(
            "pl-only",
            ["playlist"],
            """
            exports.canHandle = function(input) { return { matched: false }; };
            exports.fetchCollection = function() { return { remoteId: 'x', title: 'X', tracks: [] }; };
            """,
            urlPatterns: ["^https://api\\.search\\.test/"]);
        store.StageImportedPackage(playlistExt);
        store.ApproveAndEnable("pl-only");

        // Install a search-only extension (pending review -> not active yet)
        var searchExt = LoadExt(
            "srch",
            ["search", "stream"],
            """
            exports.search = async function() { return { items: [], hasMore: false }; };
            exports.resolveStream = async function() { return { url: 'https://cdn.search.test/1.mp3' }; };
            """);
        store.StageImportedPackage(searchExt);
        Assert.Empty(store.ActiveSearchExtensions);

        store.ApproveAndEnable("srch");
        var active = store.ActiveSearchExtensions;
        Assert.Single(active);
        Assert.Equal("srch", active[0].ExtensionId);
        Assert.True(active[0].SupportsStream);
        Assert.False(active[0].SupportsCollectionImport);

        // Add Cloud Folder provider combo should exclude search-only extension, keeping only playlist-capable ones
        var importProviders = registry.AvailableProviders.Where(p => p.SupportsCollectionImport).ToList();
        Assert.DoesNotContain(importProviders, p => p.ProviderId == "ext.srch");
        Assert.Contains(importProviders, p => p.ProviderId == "ext.pl-only");
    }

    [Fact]
    public void ExtensionCookie_PersistsRedactsLogs_AndPreservesRootPrecedence()
    {
        string stateDir = NewTempDir();
        var store = new ExtensionStore(stateDir);
        var pkg = LoadExt(
            "cookied-ext",
            ["search", "stream", "cookie"],
            """
            exports.search = async function(ctx, req) {
              ctx.log.info('Using cookie=' + (req.cookie || 'none'));
              return { items: [{ kind: 'track', id: 'trk-1', title: 'With Cookie' }], hasMore: false };
            };
            exports.resolveStream = async function(ctx, req) {
              return { url: 'https://cdn.search.test/stream?c=' + encodeURIComponent(req.cookie || '') };
            };
            """);
        store.StageImportedPackage(pkg);
        store.ApproveAndEnable("cookied-ext");

        const string secretCookie = "sess=super_secret_token_9988";
        store.SetCookie("cookied-ext", $"  {secretCookie}  ");
        Assert.Equal(secretCookie, store.GetCookie("cookied-ext"));

        // Reload store from disk and verify persistence
        var reloaded = new ExtensionStore(stateDir);
        Assert.Equal(secretCookie, reloaded.GetCookie("cookied-ext"));

        // Log redaction: even if script logs req.cookie, it is masked with [REDACTED]
        reloaded.AppendLog("cookied-ext", ExtensionLogLevel.Info, $"debug cookie={secretCookie}");
        var logs = reloaded.GetLogs("cookied-ext");
        Assert.Single(logs);
        Assert.DoesNotContain(secretCookie, logs[0].Message);
        Assert.Contains("[REDACTED]", logs[0].Message);

        // Stream resolution cookie precedence:
        // 1) Ephemeral track (root == null) receives extension-level cookie
        var registry = new CloudProviderRegistry();
        reloaded.BindRegistry(registry);
        Track ephemeralTrack = Track.MakeEphemeral("cookied-ext", "trk-1", "Song 1");
        var provider = registry.GetBySourceOrProviderId(ephemeralTrack.CloudSource)!;

        string? ephCookie = CloudStreamResolver.ResolveEffectiveCookie(registry, ephemeralTrack, root: null, provider, reloaded);
        Assert.Equal(secretCookie, ephCookie);

        // 2) Mounted folder WITH per-root cookie takes precedence over extension-level cookie
        var mountedWithCookie = new LibraryRoot(Guid.NewGuid(), "Folder", RootKind.CloudExtension, "https://api.search.test/p/1",
            Cookie: "root_cookie=override_xyz", ProviderId: "ext.cookied-ext");
        string? overrideCookie = CloudStreamResolver.ResolveEffectiveCookie(registry, ephemeralTrack, mountedWithCookie, provider, reloaded);
        Assert.Equal("root_cookie=override_xyz", overrideCookie);

        // 3) Reinstall/re-stage preserves saved cookie
        reloaded.StageImportedPackage(pkg);
        Assert.Equal(secretCookie, reloaded.GetCookie("cookied-ext"));
    }

    [Fact]
    public async Task RuntimeSearch_RespectsHostAllowlist_Timeout_Cancellation_AndStructuredErrors()
    {
        // 1. Successful search + host allowlist enforcement
        var runtime = new JintExtensionRuntime(new AsyncStubHttpHandler((req, _) =>
        {
            string json = JsonSerializer.Serialize(new
            {
                items = new[]
                {
                    new
                    {
                        kind = "track",
                        id = "song-1",
                        title = "Neon Skyline",
                        artist = "Vesper",
                        album = "Night Drive",
                        durationSec = 215,
                        artworkUrl = "https://cdn.search.test/cover.png",
                        trackNumber = 3
                    }
                },
                nextCursor = "c2",
                hasMore = true,
                totalCount = 50
            });
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(json, Encoding.UTF8, "application/json")
            });
        }));

        var pkg = LoadExt(
            "rt-search",
            ["search"],
            """
            exports.search = async function(ctx, req) {
              var res = await ctx.http.request({
                method: 'GET',
                url: 'https://api.search.test/q?s=' + encodeURIComponent(req.query) + '&c=' + (req.cookie || '')
              });
              return JSON.parse(res.text);
            };
            """);

        ExtSearchPage page = await runtime.SearchAsync(
            pkg.Manifest,
            File.ReadAllText(Path.Combine(NewTempDirWithFile(pkg), "index.js")),
            new ExtSearchRequest { Query = "neon", Kind = "track", Cookie = "ck=1", Limit = 20 });
        Assert.Single(page.Items);
        Assert.Equal("song-1", page.Items[0].Id);
        Assert.Equal("Neon Skyline", page.Items[0].Title);
        Assert.Equal(3, page.Items[0].TrackNumber);
        Assert.Equal("https://cdn.search.test/cover.png", page.Items[0].ArtworkUrl);
        Assert.Equal("c2", page.NextCursor);
        Assert.True(page.HasMore);
        Assert.Equal(50, page.TotalCount);

        // 2. Host allowlist blocks disallowed host
        var evilPkg = LoadExt(
            "evil-host",
            ["search"],
            """
            exports.search = async function(ctx, req) {
              await ctx.http.request({ method: 'GET', url: 'https://evil.blocked.test/steal' });
              return [];
            };
            """);
        var permEx = await Assert.ThrowsAsync<CloudServiceException>(() =>
            runtime.SearchAsync(evilPkg.Manifest, "exports.search = async function(ctx) { await ctx.http.request({ method: 'GET', url: 'https://evil.blocked.test/steal' }); return []; };",
                new ExtSearchRequest { Query = "x" }));
        Assert.Equal(CloudErrorCode.PermissionDenied, permEx.Code);

        // 3. Cancellation mid-HTTP throws OperationCanceledException cleanly
        var gate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var slowRuntime = new JintExtensionRuntime(new AsyncStubHttpHandler(async (_, ct) =>
        {
            await Task.Delay(Timeout.InfiniteTimeSpan, ct);
            return new HttpResponseMessage(HttpStatusCode.OK);
        }));
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(120));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            slowRuntime.SearchAsync(
                pkg.Manifest,
                "exports.search = async function(ctx) { await ctx.http.request({ method: 'GET', url: 'https://api.search.test/slow' }); return []; };",
                new ExtSearchRequest { Query = "slow" },
                ct: cts.Token));

        // 4. Short hook timeout (15s default constant check + fast timeout test)
        Assert.Equal(TimeSpan.FromSeconds(15), JintExtensionRuntime.DefaultShortHookTimeout);
        var shortTimeoutRuntime = new JintExtensionRuntime(
            new AsyncStubHttpHandler(async (_, ct) =>
            {
                await Task.Delay(TimeSpan.FromSeconds(10), ct);
                return new HttpResponseMessage(HttpStatusCode.OK);
            }),
            shortHookTimeout: TimeSpan.FromMilliseconds(120));
        var timeoutEx = await Assert.ThrowsAsync<CloudServiceException>(() =>
            shortTimeoutRuntime.SearchAsync(
                pkg.Manifest,
                "exports.search = async function(ctx) { await ctx.http.request({ method: 'GET', url: 'https://api.search.test/hang' }); return []; };",
                new ExtSearchRequest { Query = "hang" }));
        Assert.Equal(CloudErrorCode.ExtensionRuntimeError, timeoutEx.Code);

        // 5. Structured JS AUTH_REQUIRED error mapping
        var authEx = await Assert.ThrowsAsync<CloudServiceException>(() =>
            runtime.SearchAsync(
                pkg.Manifest,
                "exports.search = async function() { var e = new Error('Login needed'); e.code = 'AUTH_REQUIRED'; throw e; };",
                new ExtSearchRequest { Query = "priv" }));
        Assert.Equal(CloudErrorCode.AuthRequired, authEx.Code);
    }

    private string NewTempDirWithFile(LoadedExtensionPackage pkg)
    {
        string dir = NewTempDir();
        ExtensionPackageLoader.InstallToDirectory(pkg, dir);
        return dir;
    }

    [Fact]
    public async Task SearchController_StateTransitions_PaginationDedup_CursorGuard_AndDisableReset()
    {
        string stateDir = NewTempDir();
        var calls = new List<(string Ext, string Query, string? Kind, string? Cursor)>();
        var handler = new AsyncStubHttpHandler((req, _) =>
        {
            string url = req.RequestUri!.ToString();
            var q = System.Web.HttpUtility.ParseQueryString(req.RequestUri.Query);
            string ext = req.RequestUri.Host == "ext1.search.test" ? "ext1" : "ext2";
            string query = q["q"] ?? "";
            string? kind = q["k"];
            string? cursor = q["c"];
            calls.Add((ext, query, kind, cursor));

            object payload;
            if (cursor == "page2")
            {
                // Deliberately repeat (track, t2) to verify dedup AND repeat nextCursor=="page2" to test cycle guard!
                payload = new
                {
                    items = new[]
                    {
                        new { kind = "track", id = "t2", title = "Track Two Dup", artist = "A", durationSec = 100 },
                        new { kind = "track", id = "t3", title = "Track Three", artist = "A", durationSec = 150 }
                    },
                    nextCursor = "page2",
                    hasMore = true
                };
            }
            else
            {
                payload = new
                {
                    items = new object[]
                    {
                        new { kind = "track", remoteTrackId = "t1", relativePath = "disc1/01.mp3", title = "Track One", artist = "A", durationSec = 120 },
                        new { kind = "track", id = "t2", title = "Track Two", artist = "A", durationSec = 100 },
                        new { kind = "album", remoteId = "a1", title = "Album One", artist = "A", sourceUrl = "https://ext1.search.test/album/1", trackCount = 10 }
                    },
                    nextCursor = "page2",
                    hasMore = true,
                    totalCount = 3
                };
            }

            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(JsonSerializer.Serialize(payload), Encoding.UTF8, "application/json")
            });
        });

        var runtime = new JintExtensionRuntime(handler);
        var store = new ExtensionStore(stateDir, runtime: runtime);
        store.StageImportedPackage(LoadExt(
            "ext1",
            ["search"],
            """
            exports.search = async function(ctx, req) {
              var u = 'https://ext1.search.test/s?q=' + encodeURIComponent(req.query) +
                (req.kind ? '&k=' + req.kind : '') +
                (req.cursor ? '&c=' + encodeURIComponent(req.cursor) : '');
              var r = await ctx.http.request({ method: 'GET', url: u });
              return JSON.parse(r.text);
            };
            """,
            hosts: ["ext1.search.test"]));
        store.ApproveAndEnable("ext1");

        store.StageImportedPackage(LoadExt(
            "ext2",
            ["search"],
            """
            exports.search = async function(ctx, req) {
              var u = 'https://ext2.search.test/s?q=' + encodeURIComponent(req.query);
              var r = await ctx.http.request({ method: 'GET', url: u });
              return JSON.parse(r.text);
            };
            """,
            hosts: ["ext2.search.test"],
            searchKinds: ["track"]));
        store.ApproveAndEnable("ext2");

        var controller = new CloudSearchController(store);
        Assert.Null(controller.SelectedExtensionId); // Defaults to Local
        Assert.False(controller.IsCloudMode);
        Assert.Equal(2, controller.AvailableSources.Count);

        // Local -> Cloud switch MUST NOT auto-trigger search even when query is non-empty
        await controller.SelectExtensionAsync("ext1", "hello");
        Assert.True(controller.IsCloudMode);
        Assert.Empty(calls);

        // Switching kind filter BEFORE any explicit search MUST NOT trigger search either
        await controller.SelectKindAsync(ExtensionSearchKind.Album, "hello");
        Assert.Empty(calls);
        await controller.SelectKindAsync(null, "hello");
        Assert.Empty(calls);

        // Explicit submit runs search with kind="all"
        await controller.SubmitSearchAsync("hello");
        Assert.Single(calls);
        Assert.Equal("all", calls[0].Kind);
        Assert.Equal(3, controller.Results.Count);
        Assert.Equal("disc1/01.mp3", controller.Results[0].RelativePath);
        Assert.Equal("https://ext1.search.test/album/1", controller.Results[2].SourceUrl);
        Assert.True(controller.HasMore);
        Assert.Equal("page2", controller.NextCursor);

        // LoadMore dedups (track, t2) AND stops pagination when nextCursor == prevCursor ("page2" == "page2")
        await controller.LoadMoreAsync();
        Assert.Equal(2, calls.Count);
        Assert.Equal(4, controller.Results.Count); // t1, t2, a1, t3 (not t2 duplicate)
        Assert.False(controller.HasMore);
        Assert.Null(controller.NextCursor);

        // PrepareTrackQueue registers ephemeral tracks and preserves RelativePath + "Search (<Ext>): <query>"
        string musicDir = NewTempDir();
        var lib = new LibraryStore(NewTempDir(), musicDir, new StubMetadataReader(), new ArtworkCache(NewTempDir()));
        var prepared = controller.PrepareTrackQueue(lib, "t2");
        Assert.NotNull(prepared);
        Assert.Equal("Search (EXT1): hello", prepared!.Value.QueueName);
        Assert.Equal(1, prepared.Value.StartIndex);
        Assert.Equal(["extsearch:ext1:t1", "extsearch:ext1:t2", "extsearch:ext1:t3"],
            prepared.Value.Tracks.Select(t => t.Id).ToArray());
        Assert.Equal("disc1/01.mp3", prepared.Value.Tracks[0].RelativePath);
        Assert.Equal("disc1/01.mp3", controller.ToEphemeralTrack(controller.Results[0]).RelativePath);

        // Kind filter switches AFTER explicit submit trigger re-search with selected kind
        await controller.SelectKindAsync(ExtensionSearchKind.Album, "hello");
        Assert.Equal(3, calls.Count);
        Assert.Equal("album", calls[^1].Kind);

        // Switching to another cloud source with non-empty query auto-runs once
        await controller.SelectExtensionAsync("ext2", "hello");
        Assert.Equal(4, calls.Count);
        Assert.Equal("ext2", calls[^1].Ext);

        // Disabling active search extension resets source to Local & clears results
        store.SetEnabled("ext2", false);
        bool wasReset = controller.SyncExtensions();
        Assert.True(wasReset);
        Assert.False(controller.IsCloudMode);
        Assert.Null(controller.SelectedExtensionId);
        Assert.Empty(controller.Results);

        // Uninstalling active search extension also resets source to Local & clears results
        await controller.SelectExtensionAsync("ext1", "again");
        Assert.True(controller.IsCloudMode);
        store.Uninstall("ext1");
        Assert.True(controller.SyncExtensions());
        Assert.False(controller.IsCloudMode);
        Assert.Null(controller.SelectedExtensionId);
        Assert.Empty(controller.Results);
    }

    [Fact]
    public async Task EphemeralRegistry_IsolatesLibrary_PersistsQueued_PrunesOnDrop_AndExcludesStats()
    {
        string stateDir = NewTempDir();
        string musicDir = NewTempDir();
        TestHelpers.TouchFile(musicDir, "local.mp3");

        var artwork = new ArtworkCache(Path.Combine(stateDir, "Artwork"));
        var lib = new LibraryStore(stateDir, musicDir, new StubMetadataReader(), artwork);
        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        var engine = new FakeAudioEngine();
        using var player = new PlayerController(lib, queues, history, engine, Path.Combine(stateDir, "settings.json"), enableTicker: false);

        // Scan local track so library isn't empty
        await lib.RescanAsync();
        Assert.Single(lib.Tracks);

        var eph1 = Track.MakeEphemeral("demo", "id-1", "Cloud Song 1", artist: "Artist X", album: "Album X", durationSeconds: 200);
        var eph2 = Track.MakeEphemeral("demo", "id-2", "Cloud Song 2", artist: "Artist Y", album: "Album Y", durationSeconds: 180);
        lib.RegisterEphemeralTracks([eph1, eph2]);

        // Resolved by GetTrack/GetTracks
        Assert.NotNull(lib.GetTrack(eph1.Id));
        Assert.Equal(2, lib.GetTracks([eph1.Id, eph2.Id]).Count);
        Assert.True(lib.GetTrack(eph1.Id)!.IsEphemeral);

        // Excluded from Library collections & local search
        Assert.Single(lib.Tracks);
        Assert.DoesNotContain(lib.Tracks, t => t.IsEphemeral);
        Assert.DoesNotContain(lib.Artists, a => a == "Artist X");
        Assert.DoesNotContain(lib.Albums, al => al.Title == "Album X");
        Assert.Empty(lib.Search("Cloud Song 1"));

        // UpdateTrackMetadataAsync refuses ephemeral tracks
        Assert.Null(await lib.UpdateTrackMetadataAsync(eph1.Id, "Edited", "A", "B", null));

        // Put only eph1 into a queue -> QueuesChanged fires -> eph2 unreferenced gets pruned automatically!
        string qName = QueueStore.FormatCloudSearchQueueName("DEMO", "cloud");
        Assert.Equal("Search (DEMO): cloud", qName);
        queues.CreateOrReplaceQueue(qName, [eph1.Id], 0, activate: true);

        Assert.NotNull(lib.GetTrack(eph1.Id));
        Assert.Null(lib.GetTrack(eph2.Id));

        // Reload LibraryStore from disk: eph1 persisted cleanly
        var reloadedLib = new LibraryStore(stateDir, musicDir, new StubMetadataReader(), artwork);
        Assert.Single(reloadedLib.Tracks);
        Assert.True(reloadedLib.GetTrack(eph1.Id)?.IsEphemeral == true);
        Assert.Null(reloadedLib.GetTrack(eph2.Id));

        // Drop queue referencing eph1 -> auto-pruned from memory & disk
        queues.Delete(queues.ActiveId);
        Assert.Null(lib.GetTrack(eph1.Id));
        var reloadedAfterDrop = new LibraryStore(stateDir, musicDir, new StubMetadataReader(), artwork);
        Assert.Null(reloadedAfterDrop.GetTrack(eph1.Id));

        // History exclusion: PlayHistoryStore & PlayerController never record ephemeral tracks
        history.RecordPlay(eph1);
        history.MarkStarted(eph1);
        history.AddListening(30.0, eph1);
        history.RecordPlay(Track.MakeEphemeralId("demo", "id-99"));
        Assert.Equal(0, history.TotalPlays);
        Assert.Equal(0, history.TotalSeconds);
        Assert.Empty(history.Entries);
        Assert.Empty(history.RecentlyPlayed([eph1]));
        Assert.Empty(history.NeverPlayed([eph1]));
    }

    [Fact]
    public async Task PlaybackDisabledExtension_SurfacesErrorWithoutCrash()
    {
        string stateDir = NewTempDir();
        string musicDir = NewTempDir();
        var artwork = new ArtworkCache(Path.Combine(stateDir, "Artwork"));
        var registry = new CloudProviderRegistry();
        var extStore = new ExtensionStore(stateDir);
        extStore.BindRegistry(registry);

        var pkg = LoadExt(
            "playable-ext",
            ["search", "stream"],
            """
            exports.search = async function() { return []; };
            exports.resolveStream = async function() { return { url: 'https://cdn.search.test/1.mp3' }; };
            """);
        extStore.StageImportedPackage(pkg);
        extStore.ApproveAndEnable("playable-ext");

        var lib = new LibraryStore(stateDir, musicDir, new StubMetadataReader(), artwork, providerRegistry: registry);
        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        var engine = new FakeAudioEngine();
        using var player = new PlayerController(
            lib, queues, history, engine,
            Path.Combine(stateDir, "settings.json"),
            enableTicker: false,
            providerRegistry: registry,
            extensions: extStore);

        var eph = Track.MakeEphemeral("playable-ext", "track-9", "Remote Track");
        lib.RegisterEphemeralTrack(eph);

        // Disable extension before playing
        extStore.SetEnabled("playable-ext", false);
        player.PlayInNewQueue([eph.Id], "Search (PLAYABLE-EXT): remote", 0);

        // Poll briefly for background cloud load to finish surfacing error
        for (int i = 0; i < 40 && player.IsLoadingCloud; i++)
        {
            await Task.Delay(25);
        }

        Assert.False(player.IsPlaying);
        Assert.False(player.IsLoadingCloud);
        Assert.False(string.IsNullOrWhiteSpace(player.ErrorMessage));
        Assert.Contains("disabled", player.ErrorMessage!, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task SingleTrackExport_EphemeralTrack_WritesTagsAndCoverWithoutCloudManifestOrArtworkCache()
    {
        string stateDir = NewTempDir();
        string exportDir = NewTempDir();
        string artDir = Path.Combine(stateDir, "Artwork");
        var artwork = new ArtworkCache(artDir);

        byte[] mp3 = MakeMinimalMp3Bytes();
        var handler = new AsyncStubHttpHandler((req, _) =>
        {
            if (req.RequestUri!.AbsolutePath == "/audio.mp3")
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(mp3)
                });
            }
            if (req.RequestUri.AbsolutePath == "/cover.png")
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(ValidPngBytes)
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        var runtime = new JintExtensionRuntime(handler);
        var extStore = new ExtensionStore(stateDir, runtime: runtime);
        var registry = new CloudProviderRegistry();
        extStore.BindRegistry(registry);

        var pkg = LoadExt(
            "exp-ext",
            ["search", "stream", "cookie"],
            """
            exports.search = async function() { return []; };
            exports.resolveStream = async function(ctx, req) {
              return {
                url: 'https://cdn.search.test/audio.mp3?ck=' + encodeURIComponent(req.cookie || ''),
                fileExtension: 'mp3'
              };
            };
            """);
        extStore.StageImportedPackage(pkg);
        extStore.ApproveAndEnable("exp-ext");
        extStore.SetCookie("exp-ext", "auth=token123");

        var cloudCache = new CloudAudioCache(Path.Combine(stateDir, "CloudCache"), handler: handler);
        var thumbCache = new CloudThumbnailCache(handler: handler);
        var exporter = new CloudExporter(cloudCache, registry, artwork, extStore, thumbCache);

        Track eph = Track.MakeEphemeral(
            "exp-ext",
            "trk/42",
            "Saved Cloud Hit",
            artist: "Cloud Artist",
            album: "Cloud Album",
            durationSeconds: 180,
            remoteArtworkUrl: "https://cdn.search.test/cover.png",
            trackNumber: 5);

        string savedFile = await exporter.ExportTrackAsync(eph, root: null, exportDir);
        Assert.True(File.Exists(savedFile));
        Assert.False(File.Exists(Path.Combine(exportDir, CloudExporter.ManifestFileName)));
        // Thumbnail must NOT be written into disk ArtworkCache
        Assert.True(!Directory.Exists(artDir) || Directory.GetFiles(artDir).Length == 0);

        using var tagFile = TagLib.File.Create(savedFile);
        Assert.Equal("Saved Cloud Hit", tagFile.Tag.Title);
        Assert.Equal("Cloud Artist", tagFile.Tag.FirstPerformer);
        Assert.Single(tagFile.Tag.Pictures);
    }

    [Fact]
    public async Task ThumbnailCache_EnforcesHttpsOnly_Coalesces_EvictsLru_AndWritesZeroDiskFiles()
    {
        int networkCalls = 0;
        var handler = new AsyncStubHttpHandler((req, _) =>
        {
            Interlocked.Increment(ref networkCalls);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new ByteArrayContent(ValidPngBytes)
            });
        });

        var cache = new CloudThumbnailCache(maxEntries: 2, maxBytes: 1024 * 1024, handler: handler);

        // Non-HTTPS URLs strictly rejected without HTTP calls
        Assert.False(CloudThumbnailCache.IsAllowedHttpsUrl("http://cdn.search.test/a.png"));
        Assert.False(CloudThumbnailCache.IsAllowedHttpsUrl("file:///tmp/a.png"));
        Assert.False(CloudThumbnailCache.IsAllowedHttpsUrl("data:image/png;base64,abc"));
        Assert.Null(await cache.GetBytesAsync("http://cdn.search.test/a.png"));
        Assert.Equal(0, networkCalls);

        // Concurrent fetch coalesces to 1 call
        var t1 = cache.GetBytesAsync("https://cdn.search.test/1.png");
        var t2 = cache.GetBytesAsync("https://cdn.search.test/1.png");
        var results = await Task.WhenAll(t1, t2);
        Assert.NotNull(results[0]);
        Assert.Equal(1, networkCalls);

        Assert.NotNull(await cache.GetBytesAsync("https://cdn.search.test/2.png"));
        Assert.Equal(2, cache.Count);

        // Touch 1.png so 2.png becomes LRU, then insert 3.png -> 2.png evicted cleanly
        Assert.True(cache.TryGetBytes("https://cdn.search.test/1.png", out _));
        Assert.NotNull(await cache.GetBytesAsync("https://cdn.search.test/3.png"));
        Assert.Equal(2, cache.Count);
        Assert.True(cache.TryGetBytes("https://cdn.search.test/1.png", out _));
        Assert.False(cache.TryGetBytes("https://cdn.search.test/2.png", out _));
    }

    [Fact]
    public void SafeTrackFileKey_AndCollectionPreviewInMemoryWorkForEphemeralIds()
    {
        // SafeTrackFileKey keeps normal hex IDs and sanitizes colons/slashes in extsearch IDs
        string hexId = new string('a', 64);
        Assert.Equal(hexId, Persistence.SafeTrackFileKey(hexId));

        string ephId = Track.MakeEphemeralId("ext-1", "remote:123/abc");
        string safeKey = Persistence.SafeTrackFileKey(ephId);
        Assert.DoesNotContain(":", safeKey);
        Assert.DoesNotContain("/", safeKey);

        // LyricsStore stores/resolves cleanly with colon-bearing ephemeral ID
        string lyricsDir = NewTempDir();
        var lyrics = new LyricsStore(lyricsDir);
        lyrics.SaveImportedLyrics("[00:01.00]Hello", ephId);
        var resolved = lyrics.Resolve(ephId, audioPath: null, embedded: null);
        Assert.NotNull(resolved);
    }

    [Fact]
    public async Task FetchCollectionPreviewAsync_LoadsTracksInMemoryWithoutAddingLibraryRoot()
    {
        string stateDir = NewTempDir();
        string musicDir = NewTempDir();
        var artwork = new ArtworkCache(Path.Combine(stateDir, "Artwork"));
        var handler = new AsyncStubHttpHandler((req, _) =>
        {
            string json = JsonSerializer.Serialize(new
            {
                remoteId = "col-99",
                title = "Preview Album",
                tracks = new[]
                {
                    new { remoteTrackId = "pt1", title = "Preview Track 1", artist = "Band", durationSec = 210, artworkUrl = "https://cdn.search.test/c1.png" },
                    new { remoteTrackId = "pt2", title = "Preview Track 2", artist = "Band", durationSec = 195, artworkUrl = "https://cdn.search.test/c2.png" }
                }
            });
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(json, Encoding.UTF8, "application/json")
            });
        });

        var runtime = new JintExtensionRuntime(handler);
        var store = new ExtensionStore(stateDir, runtime: runtime);
        var registry = new CloudProviderRegistry();
        store.BindRegistry(registry);

        var pkg = LoadExt(
            "prev-ext",
            ["search", "playlist", "stream"],
            """
            exports.search = async function() { return []; };
            exports.canHandle = function(input) { return { matched: /^https:\/\/api\.search\.test\/col\//.test(input.extractedUrl), kind: 'playlist', remoteId: 'col-99', normalizedUrl: input.extractedUrl }; };
            exports.fetchCollection = async function(ctx, req) {
              var r = await ctx.http.request({ method: 'GET', url: req.url });
              return JSON.parse(r.text);
            };
            exports.resolveStream = async function() { return { url: 'https://cdn.search.test/a.mp3' }; };
            """,
            urlPatterns: ["^https://api\\.search\\.test/col/"]);
        store.StageImportedPackage(pkg);
        store.ApproveAndEnable("prev-ext");

        var lib = new LibraryStore(stateDir, musicDir, new StubMetadataReader(), artwork, providerRegistry: registry);
        CloudPlaylistResult preview = await store.FetchCollectionPreviewAsync("prev-ext", "https://api.search.test/col/99");
        Assert.Equal("Preview Album", preview.Title);
        Assert.Equal(2, preview.Items.Count);
        Assert.Single(lib.Roots); // Only AppFolder; preview created zero LibraryRoot entries
    }

    [Fact]
    public async Task RuntimeSearch_SpecContract_KindAll_NumericIds_MissingTitle_AndRelativePathForwarding()
    {
        string stateDir = NewTempDir();
        string? observedKind = null;
        string? observedRelativePath = null;

        var runtime = new JintExtensionRuntime(new AsyncStubHttpHandler((req, _) =>
        {
            var q = System.Web.HttpUtility.ParseQueryString(req.RequestUri!.Query);
            if (req.RequestUri.AbsolutePath == "/search")
            {
                observedKind = q["kind"];
                string json = JsonSerializer.Serialize(new
                {
                    items = new object[]
                    {
                        new
                        {
                            kind = "track",
                            remoteTrackId = 101,
                            title = "Numeric Track",
                            relativePath = "disc2/101.flac",
                            artist = "Artist",
                            durationSec = 240
                        },
                        new
                        {
                            kind = "playlist",
                            remoteId = 202,
                            title = "Numeric Playlist",
                            sourceUrl = "https://api.search.test/pl/202",
                            trackCount = 5
                        },
                        new
                        {
                            kind = "track",
                            remoteTrackId = "drop-me",
                            title = "   "
                        }
                    },
                    nextCursor = 2,
                    hasMore = true
                });
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(json, Encoding.UTF8, "application/json")
                });
            }

            if (req.RequestUri.AbsolutePath == "/stream")
            {
                observedRelativePath = q["rel"];
                string streamJson = JsonSerializer.Serialize(new
                {
                    url = "https://cdn.search.test/audio/101.flac",
                    fileExtension = "flac"
                });
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(streamJson, Encoding.UTF8, "application/json")
                });
            }

            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        }));

        var store = new ExtensionStore(stateDir, runtime: runtime);
        var registry = new CloudProviderRegistry();
        store.BindRegistry(registry);

        var pkg = LoadExt(
            "spec-ext",
            ["search", "stream"],
            """
            exports.search = async function(ctx, req) {
              var u = 'https://api.search.test/search?kind=' + encodeURIComponent(req.kind || '');
              var r = await ctx.http.request({ method: 'GET', url: u });
              return JSON.parse(r.text);
            };
            exports.resolveStream = async function(ctx, req) {
              var u = 'https://api.search.test/stream?id=' + encodeURIComponent(req.remoteTrackId) +
                '&rel=' + encodeURIComponent(req.relativePath || '');
              var r = await ctx.http.request({ method: 'GET', url: u });
              return JSON.parse(r.text);
            };
            """);
        store.StageImportedPackage(pkg);
        store.ApproveAndEnable("spec-ext");

        var controller = new CloudSearchController(store);
        await controller.SelectExtensionAsync("spec-ext", "");
        await controller.SubmitSearchAsync("numeric");

        Assert.Equal("all", observedKind);
        Assert.Equal("2", controller.NextCursor);
        Assert.True(controller.HasMore);
        Assert.Equal(2, controller.Results.Count);

        var trackItem = controller.Results[0];
        Assert.Equal(ExtensionSearchKind.Track, trackItem.Kind);
        Assert.Equal("101", trackItem.Id);
        Assert.Equal("disc2/101.flac", trackItem.RelativePath);

        var playlistItem = controller.Results[1];
        Assert.Equal(ExtensionSearchKind.Playlist, playlistItem.Kind);
        Assert.Equal("202", playlistItem.Id);
        Assert.Equal("https://api.search.test/pl/202", playlistItem.SourceUrl);
        Assert.Equal(5, playlistItem.TrackCount);

        // Verify relativePath reaches resolveStream via ephemeral Track
        Track eph = controller.ToEphemeralTrack(trackItem);
        Assert.Equal("disc2/101.flac", eph.RelativePath);
        CloudAudioStream stream = await CloudStreamResolver.ResolveAsync(registry, eph, root: null, extensions: store);
        Assert.Equal("disc2/101.flac", observedRelativePath);
        Assert.Equal("flac", stream.Extension);
    }

    [Fact]
    public async Task FirstTapSearchPlayWhileTrackIsLoaded_PreservesEphemeralTracks()
    {
        string stateDir = NewTempDir();
        string musicDir = NewTempDir();
        string track0Path = Path.Combine(musicDir, "local-initial.mp3");
        TestHelpers.TouchFile(musicDir, "local-initial.mp3");
        File.WriteAllBytes(track0Path, MakeMinimalMp3Bytes());

        var artwork = new ArtworkCache(Path.Combine(stateDir, "Artwork"));
        var lib = new LibraryStore(stateDir, musicDir, new StubMetadataReader(), artwork);
        await lib.RescanAsync();
        string initialLocalId = Assert.Single(lib.Tracks).Id;

        var queues = new QueueStore(stateDir);
        var history = new PlayHistoryStore(stateDir);
        var engine = new FakeAudioEngine();
        using var player = new PlayerController(lib, queues, history, engine, Path.Combine(stateDir, "settings.json"), enableTicker: false);

        // Load a local track first so _isLoaded == true and PersistPosition() calls Queues.Save().
        player.Play([initialLocalId], 0);
        for (int i = 0; i < 40 && player.CurrentTrack?.Id != initialLocalId; i++)
        {
            await Task.Delay(25);
        }
        Assert.Equal(initialLocalId, player.CurrentTrack?.Id);

        var eph1 = Track.MakeEphemeral("firsttap", "s1", "Search Song 1", artist: "Artist", durationSeconds: 180);
        var eph2 = Track.MakeEphemeral("firsttap", "s2", "Search Song 2", artist: "Artist", durationSeconds: 180);
        var eph3 = Track.MakeEphemeral("firsttap", "s3", "Search Song 3", artist: "Artist", durationSeconds: 180);
        lib.RegisterEphemeralTracks([eph1, eph2, eph3]);

        // ClearPlayback / queue transition happens while PersistPosition() runs before CreateOrReplaceQueue.
        player.PlayInNamedQueue("Search (FIRSTTAP): hello", [eph1.Id, eph2.Id, eph3.Id], startAt: 0, shuffle: false);

        Assert.NotNull(lib.GetTrack(eph1.Id));
        Assert.NotNull(lib.GetTrack(eph2.Id));
        Assert.NotNull(lib.GetTrack(eph3.Id));
        Assert.Equal(eph1.Id, queues.Active.CurrentTrackId);

        var eph4 = Track.MakeEphemeral("firsttap", "s4", "New Queue Song 4", artist: "Artist", durationSeconds: 180);
        lib.RegisterEphemeralTrack(eph4);
        player.PlayInNewQueue([eph4.Id]);
        Assert.NotNull(lib.GetTrack(eph4.Id));
        Assert.Equal(eph4.Id, queues.Active.CurrentTrackId);
    }
}
