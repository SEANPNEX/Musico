using System.IO.Compression;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Web;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using MusicoWindows.Models;
using MusicoWindows.Stores;

namespace MusicoWindows.Tests;

public sealed class ExtensionRuntimeTests
{
    private sealed class AsyncStubHttpHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> _responder;
        public AsyncStubHttpHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> responder) =>
            _responder = responder;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return _responder(request, cancellationToken);
        }
    }

    private static string FindFixturesDir()
    {
        string? dir = AppContext.BaseDirectory;
        while (!string.IsNullOrEmpty(dir))
        {
            string candidate = Path.Combine(dir, "docs", "extensions", "fixtures");
            if (Directory.Exists(candidate))
            {
                return candidate;
            }
            dir = Path.GetDirectoryName(dir);
        }
        throw new DirectoryNotFoundException($"Could not find docs/extensions/fixtures from {AppContext.BaseDirectory}");
    }

    private static string FindStarterDir()
    {
        string fixtures = FindFixturesDir();
        return Path.GetFullPath(Path.Combine(fixtures, "..", "starter"));
    }

    private static string CreateZipArchive(string destPath, params (string EntryName, byte[] Content)[] entries)
    {
        using var fs = new FileStream(destPath, FileMode.Create, FileAccess.Write);
        using var zip = new ZipArchive(fs, ZipArchiveMode.Create);
        foreach (var (name, content) in entries)
        {
            var entry = zip.CreateEntry(name);
            using var s = entry.Open();
            s.Write(content, 0, content.Length);
        }
        return destPath;
    }

    private static string ZipDirectoryToMusicoExt(string sourceDir, string destZipPath)
    {
        var files = Directory.GetFiles(sourceDir, "*", SearchOption.AllDirectories)
            .Select(f => (
                Path.GetRelativePath(sourceDir, f).Replace('\\', '/'),
                File.ReadAllBytes(f)))
            .ToArray();
        return CreateZipArchive(destPath: destZipPath, entries: files);
    }

    private static HttpMessageHandler CreateConformanceHttpHandler(List<HttpRequestMessage>? captured = null)
    {
        return new AsyncStubHttpHandler(async (req, ct) =>
        {
            captured?.Add(req);
            Uri uri = req.RequestUri!;

            if (uri.Host == "api.fixture.test" && uri.AbsolutePath.StartsWith("/playlists/", StringComparison.Ordinal))
            {
                var q = HttpUtility.ParseQueryString(uri.Query);
                string? cursor = q["cursor"];
                if (string.IsNullOrEmpty(cursor))
                {
                    string json = JsonSerializer.Serialize(new
                    {
                        remoteId = "conformance",
                        title = "Conformance Playlist",
                        injectDiagnostics = true,
                        tracks = new[]
                        {
                            new
                            {
                                remoteTrackId = "t1",
                                relativePath = "01.mp3",
                                title = "Track One",
                                artist = "Tester",
                                album = "",
                                trackNumber = 1,
                                durationSec = 120,
                                artworkUrl = "https://img.cdn.fixture.test/1.jpg"
                            }
                        },
                        nextCursor = "page-2"
                    });
                    return new HttpResponseMessage(HttpStatusCode.OK)
                    {
                        Content = new StringContent(json, Encoding.UTF8, "application/json")
                    };
                }
                else
                {
                    string json = JsonSerializer.Serialize(new
                    {
                        remoteId = "conformance",
                        title = "Conformance Playlist",
                        tracks = new[]
                        {
                            new
                            {
                                remoteTrackId = "t2",
                                relativePath = "02.flac",
                                title = "Track Two",
                                artist = "Tester",
                                album = "Page 2",
                                trackNumber = 2,
                                durationSec = 200
                            }
                        },
                        nextCursor = (string?)null
                    });
                    return new HttpResponseMessage(HttpStatusCode.OK)
                    {
                        Content = new StringContent(json, Encoding.UTF8, "application/json")
                    };
                }
            }

            if (uri.Host == "api.fixture.test" && uri.AbsolutePath == "/stream")
            {
                string bodyText = req.Content is not null ? await req.Content.ReadAsStringAsync(ct).ConfigureAwait(false) : "{}";
                using var doc = JsonDocument.Parse(bodyText);
                Assert.True(doc.RootElement.TryGetProperty("remoteTrackId", out _));

                string json = JsonSerializer.Serialize(new
                {
                    url = "https://edge1.cdn.fixture.test/audio/t1.mp3",
                    headers = new Dictionary<string, string> { ["Referer"] = "https://fixture.test/" },
                    fileExtension = "mp3",
                    mimeType = "audio/mpeg",
                    expiresAtMs = 1800000000000L,
                    fallbackUrls = new[] { "https://edge2.cdn.fixture.test/audio/t1.mp3" },
                    contentLength = 1024000L
                });
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(json, Encoding.UTF8, "application/json")
                };
            }

            if (uri.Host == "tree.fixture.test" && uri.AbsolutePath == "/api/root/root-1")
            {
                string json = JsonSerializer.Serialize(new
                {
                    remoteId = "root-1",
                    title = "Tree Root",
                    tracks = new[]
                    {
                        new { remoteTrackId = "rt-1", relativePath = "intro.mp3", title = "Intro", artist = "Root", album = "Tree", trackNumber = 1, durationSec = 30 }
                    },
                    subfolders = new[]
                    {
                        new { id = "sub-a", name = "Disc 1", pathSegments = new[] { "Disc 1" }, extra = new { pageSize = 1 } }
                    },
                    nextCursor = (string?)null
                });
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(json, Encoding.UTF8, "application/json")
                };
            }

            if (uri.Host == "tree.fixture.test" && uri.AbsolutePath == "/api/folder/sub-a")
            {
                var q = HttpUtility.ParseQueryString(uri.Query);
                string? cursor = q["cursor"];
                string json = string.IsNullOrEmpty(cursor)
                    ? JsonSerializer.Serialize(new
                    {
                        folderId = "sub-a",
                        tracks = new[]
                        {
                            new { remoteTrackId = "sa-1", relativePath = "Disc 1/01.mp3", title = "Sub 1", artist = "Tree", album = "Disc 1", trackNumber = 1, durationSec = 90 }
                        },
                        subfolders = Array.Empty<object>(),
                        nextCursor = "sub-p2"
                    })
                    : JsonSerializer.Serialize(new
                    {
                        folderId = "sub-a",
                        tracks = new[]
                        {
                            new { remoteTrackId = "sa-2", relativePath = "Disc 1/02.mp3", title = "Sub 2", artist = "Tree", album = "Disc 1", trackNumber = 2, durationSec = 95 }
                        },
                        subfolders = Array.Empty<object>(),
                        nextCursor = (string?)null
                    });
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(json, Encoding.UTF8, "application/json")
                };
            }

            if (uri.Host == "tree.fixture.test" && uri.AbsolutePath.StartsWith("/api/stream/", StringComparison.Ordinal))
            {
                string tid = uri.AbsolutePath["/api/stream/".Length..];
                string json = JsonSerializer.Serialize(new
                {
                    url = $"https://edge1.cdn.fixture.test/tree/{tid}.mp3",
                    fileExtension = "mp3",
                    mimeType = "audio/mpeg"
                });
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(json, Encoding.UTF8, "application/json")
                };
            }

            if (uri.Host == "fixture.test" && uri.AbsolutePath == "/redirect-evil")
            {
                var resp = new HttpResponseMessage(HttpStatusCode.Redirect);
                resp.Headers.Location = new Uri("https://evil.example.com/steal");
                return resp;
            }

            if (uri.Host == "edge1.cdn.fixture.test" && uri.AbsolutePath == "/ok")
            {
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("edge-ok", Encoding.UTF8, "text/plain")
                };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound) { Content = new StringContent("not found") };
        });
    }

    [Fact]
    public async Task BasicPlaylistFixtureVerifiesCryptoKvSettingsEnvLogsPaginationAuthAndStream()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string fixturesDir = FindFixturesDir();
        string basicPath = Path.Combine(fixturesDir, "basic-playlist.musico.js");

        var handler = CreateConformanceHttpHandler();
        var runtime = new JintExtensionRuntime(handler, appVersion: "1.2.0-test");
        var store = new ExtensionStore(stateDir, handler, runtime, appVersion: "1.2.0-test");
        var registry = new CloudProviderRegistry();
        store.BindRegistry(registry);

        var imported = await store.ImportFromFileAsync(basicPath);
        Assert.Equal("com.musico.fixture.basic", imported.Id);
        Assert.True(imported.PendingPermissionReview);
        Assert.False(imported.IsEnabled);
        Assert.True(File.Exists(Path.Combine(store.ExtensionsDirectory, imported.Id, "manifest.json")));
        Assert.True(File.Exists(Path.Combine(store.ExtensionsDirectory, imported.Id, "index.js")));

        // Settings defaults & updates
        Assert.True(store.GetSettingBool(imported.Id, "preferHighBitrate"));
        Assert.Equal("default-tag", store.GetSettingString(imported.Id, "apiTag"));
        Assert.Equal("us", store.GetSettingString(imported.Id, "region"));

        store.SetSettingValue(imported.Id, "preferHighBitrate", false);
        store.SetSettingValue(imported.Id, "apiTag", "custom-tag");
        store.SetSettingValue(imported.Id, "region", "jp");

        // Must approve before enabling
        Assert.Throws<InvalidOperationException>(() => store.SetEnabled(imported.Id, true));
        store.ApproveAndEnable(imported.Id);
        Assert.True(store.GetById(imported.Id)!.IsEnabled);

        var provider = registry.GetById("ext.com.musico.fixture.basic");
        Assert.NotNull(provider);
        Assert.True(provider!.UsesFlatCloudListing);
        Assert.False(provider.SupportsProgressiveTree);
        Assert.True(provider.SupportsCookies);
        Assert.Equal("Fixture", provider.BadgeLabel);

        // Classify & canHandle
        var (matchedProvider, match) = await CloudUrlClassifier.ClassifyAsync(
            "Check this out: https://www.fixture.test/playlist/conformance?foo=bar!",
            registry);
        Assert.Same(provider, matchedProvider);
        Assert.Equal("https://fixture.test/playlist/conformance", match.NormalizedUrl);
        Assert.Equal("conformance", match.RemoteId);

        // Fetch paginated collection & verify diagnostics (crypto, kv, settings, env, logs)
        var result = await provider.FetchPlaylistAsync(match.NormalizedUrl!, "sid=abc", null);
        Assert.Equal("conformance", result.RemoteId);
        Assert.Equal("Conformance Playlist", result.Title);
        Assert.Equal(2, result.Items.Count);
        Assert.Equal("Track One", result.Items[0].Title);
        Assert.Equal("Track Two", result.Items[1].Title);

        string diag = result.Items[0].Album;
        Assert.Contains("md5=860a75194508c8447e93dbcba86b5ffe", diag);
        Assert.Contains("sha1=e16682e68bc1074d3b8f9e4c9869ebcdb3b20090", diag);
        Assert.Contains("sha256=cbcd5268d62f393b8ce6cc33ed8f5309be03b110012a0bc48d302855d63edd11", diag);
        Assert.Contains("hmac=9edcd2ade9a09eac5f91629abeb0fb0ff5fe6a4cad391511b4dbeb4a333a1ba8", diag);
        Assert.Contains("b64=musico-hello", diag);
        Assert.Contains("kv=1/temp/null", diag);
        Assert.Contains("settings=false|custom-tag|jp", diag);
        Assert.Contains("env=windows|v1", diag);

        // Logs ring buffer captured 3 entries per page (6 total across 2 pages)
        var logs = store.GetLogs(imported.Id);
        Assert.Equal(6, logs.Count);
        Assert.Equal(ExtensionLogLevel.Info, logs[0].Level);
        Assert.Equal(ExtensionLogLevel.Warn, logs[1].Level);
        Assert.Equal(ExtensionLogLevel.Error, logs[2].Level);

        // Second fetch increments persisted kv visit_count (page 1 -> 3, page 2 -> 4)
        var result2 = await provider.FetchPlaylistAsync(match.NormalizedUrl!, "sid=abc", null);
        Assert.Contains("kv=3/temp/null", result2.Items[0].Album);

        // AUTH_REQUIRED thrown cleanly
        var authEx = await Assert.ThrowsAsync<CloudServiceException>(() =>
            provider.FetchPlaylistAsync("https://fixture.test/playlist/auth-required", null, null));
        Assert.Equal(CloudErrorCode.AuthRequired, authEx.Code);

        // resolveStream with headers & fallbackUrls
        var stream = await provider.ResolveAudioStreamAsync("t1", "01.mp3", "sid=abc", null, forceRefresh: true);
        Assert.Equal("https://edge1.cdn.fixture.test/audio/t1.mp3", stream.Url);
        Assert.Equal("mp3", stream.Extension);
        Assert.Equal(1024000L, stream.ContentLength);
        Assert.Equal("https://fixture.test/", stream.Headers!["Referer"]);
        Assert.Equal(["https://edge2.cdn.fixture.test/audio/t1.mp3"], stream.FallbackUrls);

        // Reload ExtensionStore from disk: state, settings, and kv survive restart
        var reloadedStore = new ExtensionStore(stateDir, handler, runtime);
        var reloaded = reloadedStore.GetById(imported.Id);
        Assert.NotNull(reloaded);
        Assert.True(reloaded!.IsEnabled);
        Assert.False(reloadedStore.GetSettingBool(imported.Id, "preferHighBitrate"));
        Assert.Equal("custom-tag", reloadedStore.GetSettingString(imported.Id, "apiTag"));
        Assert.Equal("jp", reloadedStore.GetSettingString(imported.Id, "region"));
        Assert.Equal("4", reloadedStore.KvGet(imported.Id, "visit_count"));
    }

    [Fact]
    public async Task TreeSourceBundleSupportsProgressiveTreeCrawlingPaginationAndPriority()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        string fixturesDir = FindFixturesDir();
        string treeSourceDir = Path.Combine(fixturesDir, "tree-source");
        string bundlePath = Path.Combine(TestHelpers.MakeTempDir(), "tree-source.musicoext");
        ZipDirectoryToMusicoExt(treeSourceDir, bundlePath);

        // Also validate starter bundle and starter single-file load cleanly
        string starterBundlePath = Path.Combine(TestHelpers.MakeTempDir(), "starter.musicoext");
        ZipDirectoryToMusicoExt(FindStarterDir(), starterBundlePath);
        Assert.Equal("com.example.musico.starter", ExtensionPackageLoader.LoadFromFile(starterBundlePath).Manifest.Id);
        Assert.Equal("com.example.musico.singlefile",
            ExtensionPackageLoader.LoadFromFile(Path.Combine(fixturesDir, "..", "starter-single-file.musico.js")).Manifest.Id);

        var handler = CreateConformanceHttpHandler();
        var runtime = new JintExtensionRuntime(handler);
        var store = new ExtensionStore(stateDir, handler, runtime);
        var registry = new CloudProviderRegistry();
        store.BindRegistry(registry);

        var imported = await store.ImportFromFileAsync(bundlePath);
        store.ApproveAndEnable(imported.Id);

        var provider = registry.GetById("ext.com.musico.fixture.tree")!;
        Assert.False(provider.UsesFlatCloudListing);
        Assert.True(provider.SupportsProgressiveTree);

        var lib = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Artwork")),
            providerRegistry: registry);

        var root = await lib.AddCloudRootAsync("https://tree.fixture.test/root/root-1");
        Assert.Equal(RootKind.CloudExtension, root.Kind);
        Assert.Equal("ext.com.musico.fixture.tree", root.ResolvedProviderId);
        Assert.Equal("Tree Root", root.Name);

        await lib.WaitForCloudSyncAsync(root.Id);

        var tracks = lib.GetTracksUnder(root.Id, string.Empty);
        Assert.Equal(3, tracks.Count);
        Assert.Contains(tracks, t => t.Title == "Intro" && t.RelativePath == "intro.mp3");
        Assert.Contains(tracks, t => t.Title == "Sub 1" && t.RelativePath == "Disc 1/01.mp3");
        Assert.Contains(tracks, t => t.Title == "Sub 2" && t.RelativePath == "Disc 1/02.mp3");

        var stream = await provider.ResolveAudioStreamAsync("sa-2", "Disc 1/02.mp3", null, null);
        Assert.Equal("https://edge1.cdn.fixture.test/tree/sa-2.mp3", stream.Url);
    }

    [Fact]
    public async Task ProgressiveTreeCrawlerHonorsPrioritizeCloudFolder()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        int workerCount = JsExtensionCloudProvider.FolderWorkerConcurrency;
        int blockersEntered = 0;
        var allBlockersEntered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var releaseFirstBlocker = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var releaseRemainingBlockers = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var subBVisited = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var visitOrder = new List<string>();
        var orderLock = new object();

        var handler = new AsyncStubHttpHandler(async (req, ct) =>
        {
            string path = req.RequestUri!.AbsolutePath;
            if (path == "/api/root/p1")
            {
                var subfolders = Enumerable.Range(1, workerCount)
                    .Select(i => new { id = $"blocker-{i}", name = $"Blocker{i}", pathSegments = new[] { $"Blocker{i}" } })
                    .Concat([
                        new { id = "sub-a", name = "SubA", pathSegments = new[] { "SubA" } },
                        new { id = "sub-b", name = "SubB", pathSegments = new[] { "SubB" } }
                    ])
                    .ToArray();

                string json = JsonSerializer.Serialize(new
                {
                    remoteId = "p1",
                    title = "Priority Tree",
                    tracks = new[] { new { remoteTrackId = "r0", relativePath = "root.mp3", title = "Root Song" } },
                    subfolders
                });
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(json, Encoding.UTF8, "application/json") };
            }

            if (path.StartsWith("/api/folder/blocker-", StringComparison.Ordinal))
            {
                string id = path["/api/folder/".Length..];
                if (Interlocked.Increment(ref blockersEntered) == workerCount)
                {
                    allBlockersEntered.TrySetResult();
                }
                var gate = id == "blocker-1" ? releaseFirstBlocker : releaseRemainingBlockers;
                await gate.Task.WaitAsync(ct).ConfigureAwait(false);
                string json = JsonSerializer.Serialize(new { folderId = id, tracks = Array.Empty<object>(), subfolders = Array.Empty<object>() });
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(json, Encoding.UTF8, "application/json") };
            }

            if (path == "/api/folder/sub-a" || path == "/api/folder/sub-b")
            {
                string id = path["/api/folder/".Length..];
                lock (orderLock) { visitOrder.Add(id); }
                if (id == "sub-b")
                {
                    subBVisited.TrySetResult();
                }
                string folderName = id == "sub-a" ? "SubA" : "SubB";
                string json = JsonSerializer.Serialize(new
                {
                    folderId = id,
                    tracks = new[] { new { remoteTrackId = $"t-{id}", relativePath = $"{folderName}/song.mp3", title = folderName } },
                    subfolders = Array.Empty<object>()
                });
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(json, Encoding.UTF8, "application/json") };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        string script = """
            var manifest = {
              apiVersion: 1,
              id: 'com.test.prioritytree',
              name: 'Priority Tree',
              version: '1.0.0',
              capabilities: ['tree', 'stream'],
              urlPatterns: ['^https?://ptree\\.test/root/[a-z0-9_-]+$'],
              permissions: { hosts: ['ptree.test'] }
            };
            function canHandle(input) {
              var m = String(input.extractedUrl || input.rawText || '').match(/\/root\/([a-z0-9_-]+)$/i);
              return m ? { matched: true, normalizedUrl: 'https://ptree.test/root/' + m[1], remoteId: m[1], kind: 'folder' } : { matched: false };
            }
            async function fetchCollection(ctx, req) {
              var m = String(req.url).match(/\/root\/([a-z0-9_-]+)$/i);
              var res = await ctx.http.request({ method: 'GET', url: 'https://ptree.test/api/root/' + m[1] });
              return JSON.parse(res.text);
            }
            async function fetchFolder(ctx, req) {
              var res = await ctx.http.request({ method: 'GET', url: 'https://ptree.test/api/folder/' + req.folderId });
              return JSON.parse(res.text);
            }
            async function resolveStream(ctx, req) {
              return { url: 'https://ptree.test/audio/' + req.remoteTrackId + '.mp3', fileExtension: 'mp3' };
            }
            module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection, fetchFolder: fetchFolder, resolveStream: resolveStream };
            """;

        var runtime = new JintExtensionRuntime(handler);
        var store = new ExtensionStore(stateDir, handler, runtime);
        var registry = new CloudProviderRegistry();
        store.BindRegistry(registry);

        var ext = store.StageImportedPackage(ExtensionPackageLoader.LoadSingleFileScript(script));
        store.ApproveAndEnable(ext.Id);

        var lib = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Artwork")),
            providerRegistry: registry);

        var root = await lib.AddCloudRootAsync("https://ptree.test/root/p1");
        try
        {
            await allBlockersEntered.Task.WaitAsync(TimeSpan.FromSeconds(10));
            // Prioritize SubB while every worker slot is occupied on blockers and SubA/SubB are still queued
            lib.PrioritizeCloudFolder(root.Id, "SubB");
            releaseFirstBlocker.TrySetResult();
            await subBVisited.Task.WaitAsync(TimeSpan.FromSeconds(10));
            releaseRemainingBlockers.TrySetResult();
            await lib.WaitForCloudSyncAsync(root.Id).WaitAsync(TimeSpan.FromSeconds(10));
        }
        finally
        {
            releaseFirstBlocker.TrySetResult();
            releaseRemainingBlockers.TrySetResult();
        }

        Assert.Contains("sub-b", visitOrder);
        Assert.Contains("sub-a", visitOrder);
        Assert.True(visitOrder.IndexOf("sub-b") < visitOrder.IndexOf("sub-a"),
            $"Expected sub-b before sub-a, got [{string.Join(", ", visitOrder)}]");
    }

    [Fact]
    public async Task SecurityAndErrorsFixtureEnforcesAllowlistRedirectsStreamValidationErrorCodesAndTimeouts()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string fixturesDir = FindFixturesDir();
        string secPath = Path.Combine(fixturesDir, "security-and-errors.musico.js");

        var handler = CreateConformanceHttpHandler();
        // Use short timeouts so sync-loop and async-hang complete rapidly in unit tests!
        var runtime = new JintExtensionRuntime(
            handler,
            appVersion: "1.2.0-test",
            shortHookTimeout: TimeSpan.FromMilliseconds(250),
            longHookTimeout: TimeSpan.FromMilliseconds(250));
        var store = new ExtensionStore(stateDir, handler, runtime);
        var registry = new CloudProviderRegistry();
        store.BindRegistry(registry);

        var imported = await store.ImportFromFileAsync(secPath);
        store.ApproveAndEnable(imported.Id);
        var provider = registry.GetById("ext.com.musico.fixture.security")!;

        // Allowlist & redirect blocks
        foreach (string mode in new[] { "disallowed-host", "disallowed-scheme", "redirect-escape", "subdomain-exact-miss" })
        {
            var ex = await Assert.ThrowsAsync<CloudServiceException>(() =>
                provider.FetchPlaylistAsync($"https://fixture.test/security/{mode}", null, null));
            Assert.Equal(CloudErrorCode.PermissionDenied, ex.Code);
        }

        // followRedirects: false exposes 302 and lowercase location header
        var noFollow = await provider.FetchPlaylistAsync("https://fixture.test/security/redirect-no-follow", null, null);
        Assert.Equal("status=302", noFollow.Title);
        Assert.Equal("https://evil.example.com/steal", noFollow.Items[0].Album);

        // Wildcard subdomain match
        var subHit = await provider.FetchPlaylistAsync("https://fixture.test/security/subdomain-hit", null, null);
        Assert.Equal("edge-ok", subHit.Items[0].Album);

        // Standard error code mapping
        var errorCases = new (string Mode, CloudErrorCode Expected)[]
        {
            ("throw-invalid-url", CloudErrorCode.InvalidUrl),
            ("throw-auth-required", CloudErrorCode.AuthRequired),
            ("throw-empty-collection", CloudErrorCode.EmptyCollection),
            ("throw-no-stream", CloudErrorCode.NoPlayableStream),
            ("throw-rate-limited", CloudErrorCode.RateLimited),
            ("throw-network-error", CloudErrorCode.NetworkError)
        };
        foreach (var (mode, expected) in errorCases)
        {
            var ex = await Assert.ThrowsAsync<CloudServiceException>(() =>
                provider.FetchPlaylistAsync($"https://fixture.test/security/{mode}", null, null));
            Assert.Equal(expected, ex.Code);
        }

        // Stream URL & fallbackUrl host validation
        var badStreamEx = await Assert.ThrowsAsync<CloudServiceException>(() =>
            provider.ResolveAudioStreamAsync("bad-stream-host", null, null, null));
        Assert.Equal(CloudErrorCode.PermissionDenied, badStreamEx.Code);

        var badFallbackEx = await Assert.ThrowsAsync<CloudServiceException>(() =>
            provider.ResolveAudioStreamAsync("bad-fallback-host", null, null, null));
        Assert.Equal(CloudErrorCode.PermissionDenied, badFallbackEx.Code);

        var goodStream = await provider.ResolveAudioStreamAsync("good", null, null, null);
        Assert.Equal("https://media1.cdn.fixture.test/audio.mp3", goodStream.Url);
        Assert.Equal(["https://media2.cdn.fixture.test/audio.mp3"], goodStream.FallbackUrls);

        // Sync infinite loop aborts cleanly within timeout
        var syncTimeoutEx = await Assert.ThrowsAsync<CloudServiceException>(() =>
            provider.FetchPlaylistAsync("https://fixture.test/security/sync-loop", null, null));
        Assert.Equal(CloudErrorCode.ExtensionRuntimeError, syncTimeoutEx.Code);

        // Hanging async Promise aborts cleanly without deadlock or hang
        var asyncTimeoutEx = await Assert.ThrowsAsync<CloudServiceException>(() =>
            provider.FetchPlaylistAsync("https://fixture.test/security/async-hang", null, null));
        Assert.Equal(CloudErrorCode.ExtensionRuntimeError, asyncTimeoutEx.Code);
    }

    [Fact]
    public async Task JintRuntimeEnforces32MbMemoryLimitKvTtlAnd200EntryLogRingBuffer()
    {
        string stateDir = TestHelpers.MakeTempDir();
        var store = new ExtensionStore(stateDir);
        var runtime = new JintExtensionRuntime(shortHookTimeout: TimeSpan.FromSeconds(5), longHookTimeout: TimeSpan.FromSeconds(5));

        string memHogScript = """
            var manifest = {
              apiVersion: 1,
              id: 'com.test.memhog',
              name: 'MemHog',
              version: '1.0.0',
              capabilities: ['playlist'],
              urlPatterns: ['^https?://mem\\.test/.+$'],
              permissions: { hosts: ['mem.test'] }
            };
            function canHandle() { return { matched: true }; }
            async function fetchCollection(ctx, req) {
              var arr = [];
              for (var i = 0; i < 5000000; i++) {
                arr.push('allocation-' + i);
              }
              return { remoteId: 'x', title: String(arr.length), tracks: [] };
            }
            module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection };
            """;

        var pkg = ExtensionPackageLoader.LoadSingleFileScript(memHogScript);
        var ext = store.StageImportedPackage(pkg);
        store.ApproveAndEnable(ext.Id);

        var ex = await Assert.ThrowsAsync<CloudServiceException>(() =>
            runtime.FetchCollectionAsync(
                ext.Manifest,
                memHogScript,
                new ExtFetchCollectionRequest { Url = "https://mem.test/1" },
                host: store));
        Assert.Equal(CloudErrorCode.ExtensionRuntimeError, ex.Code);

        // KV TTL expiration & persistence
        store.KvSet(ext.Id, "short-lived", "val", ttlSeconds: 0.5);
        Assert.Equal("val", store.KvGet(ext.Id, "short-lived"));
        await Task.Delay(700);
        Assert.Null(store.KvGet(ext.Id, "short-lived"));

        // 200-entry ring buffer cap & clear
        for (int i = 0; i < 250; i++)
        {
            store.AppendLog(ext.Id, ExtensionLogLevel.Info, $"msg-{i}");
        }
        var logs = store.GetLogs(ext.Id);
        Assert.Equal(200, logs.Count);
        Assert.Equal("msg-50", logs[0].Message);
        Assert.Equal("msg-249", logs[^1].Message);

        store.ClearLogs(ext.Id);
        Assert.Empty(store.GetLogs(ext.Id));
    }

    [Fact]
    public void ZipLoaderRejectsTraversalAbsolutePathsEntryCountAndSizeLimitsAndParsesMixedSettingOptions()
    {
        string tempDir = TestHelpers.MakeTempDir();
        byte[] dummyManifest = Encoding.UTF8.GetBytes("""
            {
              "apiVersion": 1,
              "id": "com.test.zipsec",
              "name": "ZipSec",
              "version": "1.0.0",
              "capabilities": ["playlist"],
              "urlPatterns": ["^https?://zip\\.test/.+$"],
              "permissions": { "hosts": ["zip.test"] },
              "settings": [
                {
                  "key": "quality",
                  "label": "Quality",
                  "type": "select",
                  "default": "high",
                  "options": ["low", { "value": "high", "label": "High Quality" }]
                }
              ]
            }
            """);
        byte[] dummyIndex = Encoding.UTF8.GetBytes("""
            module.exports = {
              canHandle: function() { return { matched: false }; },
              fetchCollection: async function() { return { remoteId: '1', title: '1', tracks: [] }; }
            };
            """);

        // Verify string + {value, label} mixed options deserialize properly
        var parsedManifest = JsonSerializer.Deserialize<ExtensionManifest>(dummyManifest, ExtensionJson.Options)!;
        Assert.Equal(2, parsedManifest.Settings![0].Options!.Count);
        Assert.Equal(new ExtensionSettingOption("low", "low"), parsedManifest.Settings[0].Options![0]);
        Assert.Equal(new ExtensionSettingOption("high", "High Quality"), parsedManifest.Settings[0].Options![1]);

        // 1. Path traversal ../
        string traversalZip = CreateZipArchive(
            Path.Combine(tempDir, "traversal.musicoext"),
            ("manifest.json", dummyManifest),
            ("index.js", dummyIndex),
            ("../evil.txt", Encoding.UTF8.GetBytes("bad")));
        Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadFromFile(traversalZip));

        // 2. Absolute path /etc/passwd
        string absZip = CreateZipArchive(
            Path.Combine(tempDir, "abs.musicoext"),
            ("manifest.json", dummyManifest),
            ("index.js", dummyIndex),
            ("/etc/passwd", Encoding.UTF8.GetBytes("bad")));
        Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadFromFile(absZip));

        // 3. > MaxArchiveEntries (512 entries)
        var manyEntries = new List<(string, byte[])> { ("manifest.json", dummyManifest), ("index.js", dummyIndex) };
        for (int i = 0; i < ExtensionPackageLoader.MaxArchiveEntries; i++)
        {
            manyEntries.Add(($"extra/{i}.txt", [(byte)'a']));
        }
        string tooManyZip = CreateZipArchive(Path.Combine(tempDir, "many.musicoext"), manyEntries.ToArray());
        Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadFromFile(tooManyZip));

        // 4. > MaxUncompressedBytes (32 MB uncompressed)
        byte[] bigPayload = new byte[(int)ExtensionPackageLoader.MaxUncompressedBytes + 1024];
        string hugeZip = CreateZipArchive(
            Path.Combine(tempDir, "huge.musicoext"),
            ("manifest.json", dummyManifest),
            ("index.js", dummyIndex),
            ("big.bin", bigPayload));
        Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadFromFile(hugeZip));

        // 5. Wildcard host allowlist validation rejects top-level wildcard *.com and *
        Assert.Throws<InvalidDataException>(() => HostAllowlistMatcher.ValidatePattern("*"));
        Assert.Throws<InvalidDataException>(() => HostAllowlistMatcher.ValidatePattern("*.com"));
        Assert.Equal("*.cdn.example.com", HostAllowlistMatcher.ValidatePattern("*.CDN.Example.COM."));
    }

    [Fact]
    public async Task CloudHttpHelperStripsAuthAndCookiesAcrossHostsCapsRedirectsCapsBodyAndRejectsHttpExtensionUrls()
    {
        var seenHeadersPerRequest = new List<(string Host, bool HasCookie, bool HasAuth)>();
        int hopCounter = 0;

        var handler = new AsyncStubHttpHandler((req, _) =>
        {
            Uri uri = req.RequestUri!;
            seenHeadersPerRequest.Add((
                uri.Host,
                req.Headers.Contains("Cookie"),
                req.Headers.Contains("Authorization")));

            if (uri.AbsoluteUri == "https://start.example.com/1")
            {
                var r = new HttpResponseMessage(HttpStatusCode.Redirect);
                r.Headers.Location = new Uri("https://next.example.com/2");
                return Task.FromResult(r);
            }

            if (uri.AbsoluteUri == "https://next.example.com/2")
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("done")
                });
            }

            if (uri.AbsoluteUri.StartsWith("https://loop.example.com/", StringComparison.Ordinal))
            {
                hopCounter++;
                var r = new HttpResponseMessage(HttpStatusCode.Redirect);
                r.Headers.Location = new Uri($"https://loop.example.com/{hopCounter}");
                return Task.FromResult(r);
            }

            if (uri.AbsoluteUri == "https://big.example.com/data")
            {
                // 9 MB payload -> capped to 8 MB, and > 1 MB omits bytesBase64 unless returnBytes=true
                byte[] nineMb = new byte[9 * 1024 * 1024];
                Array.Fill(nineMb, (byte)'A');
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(nineMb)
                });
            }

            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        string[] allowed = ["start.example.com", "next.example.com", "loop.example.com", "big.example.com"];

        // Cross-host redirect strips Cookie & Authorization
        var resp = await CloudHttpHelper.SendExtensionRequestAsync(
            new ExtensionHttpRequest
            {
                Method = "GET",
                Url = "https://start.example.com/1",
                Headers = new Dictionary<string, string>
                {
                    ["Cookie"] = "secret=1",
                    ["Authorization"] = "Bearer abc",
                    ["X-Custom"] = "keep-me"
                },
                FollowRedirects = true
            },
            allowed,
            customHandler: handler);

        Assert.Equal(200, resp.Status);
        Assert.Equal("https://next.example.com/2", resp.FinalUrl);
        Assert.NotNull(resp.BytesBase64); // <= 1 MB includes bytesBase64 automatically
        Assert.Equal(2, seenHeadersPerRequest.Count);
        Assert.True(seenHeadersPerRequest[0].HasCookie && seenHeadersPerRequest[0].HasAuth);
        Assert.False(seenHeadersPerRequest[1].HasCookie);
        Assert.False(seenHeadersPerRequest[1].HasAuth);

        // Max 10 redirect hops enforced
        var tooManyEx = await Assert.ThrowsAsync<CloudServiceException>(() =>
            CloudHttpHelper.SendExtensionRequestAsync(
                new ExtensionHttpRequest { Url = "https://loop.example.com/0", FollowRedirects = true },
                allowed,
                customHandler: handler));
        Assert.Equal(CloudErrorCode.NetworkError, tooManyEx.Code);

        // 8 MB body cap and bytesBase64 omission when > 1 MB and returnBytes == false
        var bigRespNoB64 = await CloudHttpHelper.SendExtensionRequestAsync(
            new ExtensionHttpRequest { Url = "https://big.example.com/data", ReturnBytes = false },
            allowed,
            customHandler: handler);
        Assert.Equal(CloudHttpHelper.MaxExtensionBodyBytes, Encoding.UTF8.GetByteCount(bigRespNoB64.Text));
        Assert.Null(bigRespNoB64.BytesBase64);

        // returnBytes == true includes bytesBase64 even when > 1 MB
        var bigRespWithB64 = await CloudHttpHelper.SendExtensionRequestAsync(
            new ExtensionHttpRequest { Url = "https://big.example.com/data", ReturnBytes = true },
            allowed,
            customHandler: handler);
        Assert.NotNull(bigRespWithB64.BytesBase64);
        Assert.Equal(CloudHttpHelper.MaxExtensionBodyBytes, Convert.FromBase64String(bigRespWithB64.BytesBase64!).Length);

        // ExtensionStore.ImportFromUrlAsync rejects non-HTTPS URLs and accepts HTTPS .musicoext bundles staged for review
        string starterZipPath = Path.Combine(TestHelpers.MakeTempDir(), "starter.musicoext");
        ZipDirectoryToMusicoExt(FindStarterDir(), starterZipPath);
        byte[] starterZipBytes = File.ReadAllBytes(starterZipPath);

        var urlImportHandler = new AsyncStubHttpHandler((req, _) =>
        {
            if (req.RequestUri!.AbsoluteUri == "https://extensions.example.com/starter.musicoext")
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(starterZipBytes)
                });
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        var store = new ExtensionStore(TestHelpers.MakeTempDir(), urlImportHandler);
        await Assert.ThrowsAsync<ArgumentException>(() => store.ImportFromUrlAsync("http://example.com/ext.musico.js"));

        var importedFromUrl = await store.ImportFromUrlAsync("https://extensions.example.com/starter.musicoext");
        Assert.Equal("com.example.musico.starter", importedFromUrl.Id);
        Assert.True(importedFromUrl.PendingPermissionReview);
        Assert.False(importedFromUrl.IsEnabled);
    }

    [Fact]
    public void QrLoginCapability_RequiresStartAndPollHooks()
    {
        string missingPoll = """
            var manifest = {
              apiVersion: 1,
              id: 'com.musico.test.win.qr.nopoll',
              name: 'Missing Poll',
              version: '1.0.0',
              capabilities: ['search', 'qr_login'],
              permissions: { hosts: ['qr.fixture.test'] }
            };
            async function search() { return { items: [], hasMore: false }; }
            async function startQrLogin() { return { sessionId: 's1', qrContent: 'https://qr.fixture.test/s1' }; }
            module.exports = { manifest: manifest, search: search, startQrLogin: startQrLogin };
            """;
        var pollEx = Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadSingleFileScript(missingPoll));
        Assert.Contains("pollQrLogin", pollEx.Message);

        string missingStart = """
            var manifest = {
              apiVersion: 1,
              id: 'com.musico.test.win.qr.nostart',
              name: 'Missing Start',
              version: '1.0.0',
              capabilities: ['search', 'qr_login'],
              permissions: { hosts: ['qr.fixture.test'] }
            };
            async function search() { return { items: [], hasMore: false }; }
            async function pollQrLogin() { return { status: 'pending' }; }
            module.exports = { manifest: manifest, search: search, pollQrLogin: pollQrLogin };
            """;
        var startEx = Assert.Throws<InvalidDataException>(() => ExtensionPackageLoader.LoadSingleFileScript(missingStart));
        Assert.Contains("startQrLogin", startEx.Message);
    }

    [Fact]
    public async Task QrLogin_LifecycleSetCookiesAutoSaveSearchForwardingAndRedaction()
    {
        int checkAttempt = 0;
        var handler = new AsyncStubHttpHandler((req, _) =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url == "https://qr.fixture.test/qr/unikey")
            {
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""{"unikey":"u-win-1"}""", Encoding.UTF8, "application/json")
                });
            }
            if (url == "https://qr.fixture.test/qr/check")
            {
                checkAttempt++;
                if (checkAttempt == 1)
                {
                    return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                    {
                        Content = new StringContent("""{"code":801}""", Encoding.UTF8, "application/json")
                    });
                }
                if (checkAttempt == 2)
                {
                    return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
                    {
                        Content = new StringContent("""{"code":802,"nickname":"WinListener"}""", Encoding.UTF8, "application/json")
                    });
                }
                var confirmed = new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""{"code":803}""", Encoding.UTF8, "application/json")
                };
                confirmed.Headers.TryAddWithoutValidation(
                    "Set-Cookie",
                    "MUSIC_U=secret_win_cookie_777; Max-Age=2592000; Expires=Thu, 31 Dec 2099 23:59:59 GMT; Path=/; HttpOnly");
                confirmed.Headers.TryAddWithoutValidation(
                    "Set-Cookie",
                    "__csrf=csrf_win; Expires=Thu, 31 Dec 2099 23:59:59 GMT; Path=/");
                return Task.FromResult(confirmed);
            }
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        });

        string extId = "com.musico.test.win.qr.flow";
        string script = $$"""
            var manifest = {
              apiVersion: 1,
              id: '{{extId}}',
              name: 'QR Windows Fixture',
              version: '1.0.0',
              capabilities: ['search', 'qr_login'],
              permissions: { hosts: ['qr.fixture.test'] }
            };
            async function startQrLogin(ctx) {
              var res = await ctx.http.request({ method: 'POST', url: 'https://qr.fixture.test/qr/unikey' });
              var body = JSON.parse(res.text);
              return {
                sessionId: body.unikey,
                qrContent: 'https://music.163.com/login?codekey=' + body.unikey,
                pollIntervalMs: 1500
              };
            }
            async function pollQrLogin(ctx, req) {
              if (req.sessionId === 'empty-cookie') {
                return { status: 'confirmed', cookie: '   ' };
              }
              var res = await ctx.http.request({ method: 'POST', url: 'https://qr.fixture.test/qr/check' });
              var body = JSON.parse(res.text);
              if (body.code === 801) return { status: 'pending' };
              if (body.code === 802) return { status: 'scanned', accountName: body.nickname };
              if (body.code === 803) {
                var list = Array.isArray(res.setCookies) ? res.setCookies : [];
                var musicU = '';
                var csrf = '';
                for (var i = 0; i < list.length; i++) {
                  var firstPair = String(list[i]).split(';')[0].trim();
                  if (firstPair.indexOf('MUSIC_U=') === 0) musicU = firstPair;
                  if (firstPair.indexOf('__csrf=') === 0) csrf = firstPair;
                }
                return {
                  status: 'confirmed',
                  cookie: [musicU, csrf].filter(Boolean).join('; '),
                  accountName: 'WinListener'
                };
              }
              return { status: 'expired' };
            }
            async function search(ctx, req) {
              ctx.log.info('searching with cookie: ' + (req.cookie || 'none'));
              return {
                items: [
                  {
                    kind: 'track',
                    remoteTrackId: 'tr-1',
                    title: req.cookie === 'MUSIC_U=secret_win_cookie_777; __csrf=csrf_win' ? 'AUTHED_OK' : 'NO_AUTH'
                  }
                ],
                hasMore: false
              };
            }
            module.exports = {
              manifest: manifest,
              startQrLogin: startQrLogin,
              pollQrLogin: pollQrLogin,
              search: search
            };
            """;

        var store = new ExtensionStore(TestHelpers.MakeTempDir(), handler);
        var staged = store.StageImportedPackage(ExtensionPackageLoader.LoadSingleFileScript(script));
        store.ApproveAndEnable(staged.Id);

        Assert.True(staged.Manifest.SupportsCookieAuth);
        Assert.True(staged.Manifest.SupportsQrLogin);
        var searchDesc = Assert.Single(store.GetActiveSearchExtensions());
        Assert.True(searchDesc.SupportsCookie);

        ExtQrLoginSession session = await store.StartQrLoginAsync(extId);
        Assert.Equal("u-win-1", session.SessionId);
        Assert.Equal("https://music.163.com/login?codekey=u-win-1", session.QrContent);
        Assert.Equal(1500, session.PollIntervalMs);

        ExtQrLoginPollResult poll1 = await store.PollQrLoginAsync(extId, session.SessionId);
        Assert.Equal(ExtQrLoginStatus.Pending, poll1.Status);
        Assert.Null(store.GetCookie(extId));

        ExtQrLoginPollResult poll2 = await store.PollQrLoginAsync(extId, session.SessionId);
        Assert.Equal(ExtQrLoginStatus.Scanned, poll2.Status);
        Assert.Equal("WinListener", poll2.AccountName);
        Assert.Null(store.GetCookie(extId));

        ExtQrLoginPollResult poll3 = await store.PollQrLoginAsync(extId, session.SessionId);
        Assert.Equal(ExtQrLoginStatus.Confirmed, poll3.Status);
        Assert.Equal("MUSIC_U=secret_win_cookie_777; __csrf=csrf_win", poll3.Cookie);
        Assert.Equal("MUSIC_U=secret_win_cookie_777; __csrf=csrf_win", store.GetCookie(extId));

        ExtSearchPage page = await store.SearchAsync(extId, "hello");
        var item = Assert.Single(page.Items);
        Assert.Equal("AUTHED_OK", item.Title);

        IReadOnlyList<ExtensionLogEntry> logs = store.GetLogs(extId);
        Assert.DoesNotContain(logs, l => l.Message.Contains("secret_win_cookie_777", StringComparison.Ordinal));

        var ex = await Assert.ThrowsAsync<CloudServiceException>(() => store.PollQrLoginAsync(extId, "empty-cookie"));
        Assert.Equal(CloudErrorCode.ExtensionRuntimeError, ex.Code);
    }

    [Fact]
    public void QrCodeEncoder_Versions1Through10AndNetEaseUrl_ProducesValidIsoStructure()
    {
        int[] lengths = [10, 20, 35, 55, 75, 95, 115, 140, 170, 205];
        for (int i = 0; i < lengths.Length; i++)
        {
            int expectedVersion = i + 1;
            QrCodeMatrix matrix = QrCodeEncoder.Encode(new string('A', lengths[i]));
            Assert.Equal(expectedVersion, matrix.Version);
            int expectedSize = 17 + 4 * expectedVersion;
            Assert.Equal(expectedSize, matrix.Size);
            Assert.Equal(expectedSize * expectedSize, matrix.Modules.Length);

            AssertFinderPattern(matrix, 0, 0);
            AssertFinderPattern(matrix, 0, matrix.Size - 7);
            AssertFinderPattern(matrix, matrix.Size - 7, 0);
            Assert.True(matrix.Get(matrix.Size - 8, 8));

            for (int t = 8; t < matrix.Size - 8; t++)
            {
                Assert.Equal(t % 2 == 0, matrix.Get(6, t));
                Assert.Equal(t % 2 == 0, matrix.Get(t, 6));
            }
        }

        QrCodeMatrix urlMatrix = QrCodeEncoder.Encode("https://music.163.com/login?codekey=test-uuid-1234567890");
        Assert.Equal(4, urlMatrix.Version);
        Assert.Equal(33, urlMatrix.Size);
        for (int i = 0; i <= 5; i++)
        {
            Assert.Equal(urlMatrix.Get(8, i), urlMatrix.Get(urlMatrix.Size - 1 - i, 8));
        }
        Assert.Equal(urlMatrix.Get(8, 7), urlMatrix.Get(urlMatrix.Size - 7, 8));
        Assert.Equal(urlMatrix.Get(8, 8), urlMatrix.Get(8, urlMatrix.Size - 8));
        Assert.Equal(urlMatrix.Get(7, 8), urlMatrix.Get(8, urlMatrix.Size - 7));
        for (int i = 0; i <= 5; i++)
        {
            Assert.Equal(urlMatrix.Get(i, 8), urlMatrix.Get(8, urlMatrix.Size - 1 - i));
        }

        Assert.Throws<ArgumentException>(() => QrCodeEncoder.Encode(""));
        Assert.Throws<ArgumentException>(() => QrCodeEncoder.Encode(new string('x', 214)));
    }

    private static void AssertFinderPattern(QrCodeMatrix matrix, int top, int left)
    {
        for (int r = 0; r < 7; r++)
        {
            for (int c = 0; c < 7; c++)
            {
                bool expected = r == 0 || r == 6 || c == 0 || c == 6 || (r is >= 2 and <= 4 && c is >= 2 and <= 4);
                Assert.Equal(expected, matrix.Get(top + r, left + c));
            }
        }
        int sepRow = top == 0 ? 7 : top - 1;
        int sepCol = left == 0 ? 7 : left - 1;
        Assert.False(matrix.Get(sepRow, sepCol));
    }

    [Fact]
    public async Task ServerUrlSetting_DynamicallyAllowsConfiguredHostAndBlocksOthers()
    {
        var handler = new AsyncStubHttpHandler((req, _) =>
        {
            string json = """{"id":"tr-win-1","title":"Windows Server OK"}""";
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(json, Encoding.UTF8, "application/json")
            });
        });

        var store = new ExtensionStore(TestHelpers.MakeTempDir(), handler);
        const string script = """
            'use strict';
            var manifest = {
              apiVersion: 1,
              id: 'com.musico.test.win.serverurl',
              name: 'Windows Server URL',
              version: '1.0.0',
              capabilities: ['search', 'stream'],
              permissions: { hosts: [] },
              settings: [
                { key: 'serverUrl', label: 'Server URL', type: 'server_url', default: '' }
              ]
            };
            async function search(ctx, req) {
              var base = String(ctx.settings.get('serverUrl') || '').replace(/\/+$/, '');
              var res = await ctx.http.request({ method: 'GET', url: base + '/v1/search?q=' + encodeURIComponent(req.query) });
              var data = JSON.parse(res.text);
              return { items: [{ kind: 'track', remoteTrackId: data.id, title: data.title }], nextCursor: null };
            }
            async function resolveStream(ctx, req) {
              var base = String(ctx.settings.get('serverUrl') || '').replace(/\/+$/, '');
              if (req.remoteTrackId === 'evil') {
                return { url: 'https://evil.example.test/stream.m4a', fileExtension: 'm4a' };
              }
              return { url: base + '/v1/tracks/' + req.remoteTrackId + '/stream', fileExtension: 'm4a' };
            }
            module.exports = { manifest: manifest, search: search, resolveStream: resolveStream };
            """;

        Assert.Null(HostAllowlistMatcher.ExtractApprovedHost("file:///etc/passwd"));
        Assert.Null(HostAllowlistMatcher.ExtractApprovedHost("https://*.wildcard.test"));
        Assert.Equal("am.local.test", HostAllowlistMatcher.ExtractApprovedHost("https://am.local.test:8787/api"));

        var staged = store.StageImportedPackage(ExtensionPackageLoader.LoadSingleFileScript(script));
        Assert.True(staged.Manifest.HasServerUrlSetting);
        store.ApproveAndEnable(staged.Id);
        store.SetSettingValue(staged.Id, "serverUrl", "https://am.local.test:8787/");

        var page = await store.SearchAsync(staged.Id, "hello");
        var item = Assert.Single(page.Items);
        Assert.Equal("Windows Server OK", item.Title);

        var runtime = new JintExtensionRuntime(handler);
        var stream = await runtime.ResolveStreamAsync(
            staged.Manifest,
            script,
            new ExtResolveStreamRequest { RemoteTrackId = "tr-win-1" },
            host: store);
        Assert.Equal("https://am.local.test:8787/v1/tracks/tr-win-1/stream", stream.Url);

        var ex = await Assert.ThrowsAsync<CloudServiceException>(() =>
            runtime.ResolveStreamAsync(
                staged.Manifest,
                script,
                new ExtResolveStreamRequest { RemoteTrackId = "evil" },
                host: store));
        Assert.Equal(CloudErrorCode.PermissionDenied, ex.Code);
    }
}
