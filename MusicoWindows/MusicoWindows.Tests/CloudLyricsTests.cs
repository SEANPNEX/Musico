using System.Collections.Concurrent;
using System.Net;
using System.Net.Http;
using System.Text;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using Xunit;

namespace MusicoWindows.Tests;

internal sealed class MockCloudLyricsHandler : HttpMessageHandler
{
    private readonly Func<HttpRequestMessage, HttpResponseMessage> _handler;
    public ConcurrentQueue<(Uri? Uri, Dictionary<string, string> Headers)> Requests { get; } = new();

    public MockCloudLyricsHandler(Func<HttpRequestMessage, HttpResponseMessage> handler)
    {
        _handler = handler;
    }

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var hdrs = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var kvp in request.Headers)
        {
            hdrs[kvp.Key] = string.Join(", ", kvp.Value);
        }
        Requests.Enqueue((request.RequestUri, hdrs));
        return Task.FromResult(_handler(request));
    }
}

public sealed class CloudLyricsTests : IDisposable
{
    private readonly string _tempStateDir;

    public CloudLyricsTests()
    {
        _tempStateDir = TestHelpers.MakeTempDir();
    }

    public void Dispose()
    {
        try { Directory.Delete(_tempStateDir, recursive: true); } catch { /* ignore */ }
    }

    [Fact]
    public async Task LrclibSearchSendsUserAgentHandles404AndParsesSyncedLyrics()
    {
        using var handler = new MockCloudLyricsHandler(req =>
        {
            var uri = req.RequestUri!;
            if (uri.AbsolutePath == "/api/get")
            {
                return new HttpResponseMessage(HttpStatusCode.NotFound);
            }
            if (uri.AbsolutePath == "/api/search" && uri.Query.Contains("track_name=", StringComparison.Ordinal))
            {
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("[]", Encoding.UTF8, "application/json")
                };
            }
            if (uri.AbsolutePath == "/api/search")
            {
                const string json = """
                [
                  {
                    "id": 42,
                    "trackName": "Nightcall",
                    "artistName": "Kavinsky",
                    "albumName": "OutRun",
                    "duration": 258,
                    "instrumental": false,
                    "plainLyrics": "Plain fallback",
                    "syncedLyrics": "[00:30.00]There is something inside you"
                  }
                ]
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(json, Encoding.UTF8, "application/json")
                };
            }
            return new HttpResponseMessage(HttpStatusCode.InternalServerError);
        });

        var client = new LrclibClient(handler, appVersion: "1.2.3");
        var results = await client.SearchAsync(new CloudLyricsQuery("Nightcall", "Kavinsky", "OutRun", 258));

        Assert.Single(results);
        Assert.Equal(CloudLyricsProviderIds.Lrclib, results[0].ProviderId);
        Assert.True(results[0].IsTimed);
        Assert.All(handler.Requests, r =>
        {
            Assert.Contains("Musico/1.2.3", r.Headers["User-Agent"], StringComparison.Ordinal);
            Assert.False(r.Headers.ContainsKey("Lrclib-App-Id"));
        });
    }

    [Fact]
    public async Task LrclibPropagatesRetryAfterOn429()
    {
        using var handler = new MockCloudLyricsHandler(_ =>
        {
            var resp = new HttpResponseMessage((HttpStatusCode)429);
            resp.Headers.TryAddWithoutValidation("Retry-After", "18");
            return resp;
        });

        var client = new LrclibClient(handler, appVersion: "1.2.3");
        var ex = await Assert.ThrowsAsync<CloudLyricsException>(() =>
            client.SearchAsync(new CloudLyricsQuery("Hello", "Adele")));
        Assert.Equal(18, ex.RetryAfterSeconds);
    }

    [Fact]
    public async Task NeteaseFallbackSearchAndBilingualMergeWithin50ms()
    {
        using var handler = new MockCloudLyricsHandler(req =>
        {
            var uri = req.RequestUri!;
            Assert.Equal("https://music.163.com/", req.Headers.GetValues("Referer").First());
            Assert.Equal("https://music.163.com", req.Headers.GetValues("Origin").First());

            if (uri.AbsolutePath == "/api/search/get")
            {
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""{"code":200,"result":{"songs":[]}}""", Encoding.UTF8, "application/json")
                };
            }
            if (uri.AbsolutePath == "/api/cloudsearch/pc")
            {
                const string json = """
                {
                  "code": 200,
                  "result": {
                    "songs": [
                      {
                        "id": 991,
                        "name": "晴天",
                        "ar": [{ "name": "周杰伦" }],
                        "al": { "name": "叶惠美" },
                        "dt": 269000
                      }
                    ]
                  }
                }
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(json, Encoding.UTF8, "application/json")
                };
            }
            if (uri.AbsolutePath == "/api/song/lyric")
            {
                const string json = """
                {
                  "code": 200,
                  "lrc": { "lyric": "[00:28.90]故事的小黄花\n[00:32.10]从出生那年就飘着" },
                  "tlyric": { "lyric": "[00:28.93]Little yellow flower\n[00:32.12]Floating since birth" }
                }
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(json, Encoding.UTF8, "application/json")
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new NeteaseLyricsClient(handler);
        var results = await client.SearchAsync(new CloudLyricsQuery("晴天", "周杰伦", null, 269));
        Assert.Single(results);
        var hit = results[0];
        Assert.True(hit.IsTimed);
        Assert.True(hit.HasTranslation);

        var merged = LrcParser.Parse(hit.FormattedText(includeTranslation: true)).Lines.Select(l => l.Text).ToList();
        Assert.Equal(["故事的小黄花", "Little yellow flower", "从出生那年就飘着", "Floating since birth"], merged);

        var unmerged = LrcParser.Parse(hit.FormattedText(includeTranslation: false)).Lines.Select(l => l.Text).ToList();
        Assert.Equal(["故事的小黄花", "从出生那年就飘着"], unmerged);
    }

    [Fact]
    public async Task LyricsOnlyExtensionRuntimeRegistryExclusionAndSourceSwitching()
    {
        const string script = """
        exports.manifest = {
          apiVersion: 1,
          id: "sample.lyrics",
          name: "Sample Lyrics",
          version: "1.0.0",
          badgeLabel: "SampleLrc",
          capabilities: ["lyrics"],
          permissions: { hosts: ["lyrics.example.com"] }
        };

        exports.searchLyrics = async function(ctx, req) {
          var res = await ctx.http.request({
            url: "https://lyrics.example.com/search?title=" + encodeURIComponent(req.title)
          });
          return JSON.parse(res.text).items;
        };
        """;

        using var handler = new MockCloudLyricsHandler(req =>
        {
            string host = req.RequestUri!.Host;
            if (host == "lyrics.example.com")
            {
                const string json = """
                {
                  "items": [
                    {
                      "externalId": "ext-1",
                      "title": "Sample Song",
                      "artist": "Sample Artist",
                      "durationSec": 200,
                      "syncedLyrics": "[00:05.00]From Extension"
                    }
                  ]
                }
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(json, Encoding.UTF8, "application/json")
                };
            }
            if (host == "music.163.com")
            {
                return new HttpResponseMessage(HttpStatusCode.InternalServerError);
            }
            if (host == "lrclib.net" && req.RequestUri.AbsolutePath == "/api/search")
            {
                const string json = """
                [
                  {
                    "id": 7,
                    "trackName": "Sample Song",
                    "artistName": "Sample Artist",
                    "duration": 180,
                    "plainLyrics": "Plain fallback"
                  }
                ]
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(json, Encoding.UTF8, "application/json")
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var registry = new CloudProviderRegistry();
        var extStore = new ExtensionStore(stateDirectory: _tempStateDir, httpHandler: handler, appVersion: "1.2.3");
        extStore.BindRegistry(registry);

        string jsPath = Path.Combine(_tempStateDir, "sample.musico.js");
        File.WriteAllText(jsPath, script);
        var installed = await extStore.ImportFromFileAsync(jsPath);
        extStore.ApproveAndEnable(installed.Id);

        Assert.Empty(registry.ExtensionProviders);
        Assert.Equal(["sample.lyrics"], extStore.GetActiveLyricsExtensions().Select(e => e.Id));

        var lyricsStore = new LyricsStore(Path.Combine(_tempStateDir, "Lyrics"));
        var service = new CloudLyricsService(
            customHandler: handler,
            appVersion: "1.2.3",
            extensionSearch: (id, q, ct) => extStore.SearchLyricsAsync(id, q, proxyUrl: null, ct: ct));

        var query = new CloudLyricsQuery("Sample Song", "Sample Artist", null, 200);
        var autoResp = await service.SearchAsync(query, CloudLyricsSourceSelection.Auto, extStore.GetActiveLyricsExtensions());

        Assert.Equal("SampleLrc", autoResp.Candidates.First().SourceName);
        Assert.Equal(2, autoResp.Candidates.Count);
        Assert.NotEmpty(autoResp.Failures);
        Assert.False(lyricsStore.HasImported("track-1"));

        var lrclibOnly = await service.SearchAsync(query, CloudLyricsSourceSelection.Lrclib, extStore.GetActiveLyricsExtensions());
        Assert.Equal([CloudLyricsProviderIds.Lrclib], lrclibOnly.Candidates.Select(c => c.ProviderId));

        var extOnly = await service.SearchAsync(query, CloudLyricsSourceSelection.Extension("sample.lyrics"), extStore.GetActiveLyricsExtensions());
        Assert.Equal(["SampleLrc"], extOnly.Candidates.Select(c => c.SourceName));
    }
}
