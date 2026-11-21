using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Models;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class CloudClientsTests
{
    private sealed class StubHttpHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _responder;
        public List<HttpRequestMessage> Requests { get; } = new();

        public StubHttpHandler(Func<HttpRequestMessage, HttpResponseMessage> responder)
        {
            _responder = responder;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Requests.Add(request);
            return Task.FromResult(_responder(request));
        }
    }

    private static HttpResponseMessage JsonResponse(string json, HttpStatusCode code = HttpStatusCode.OK) =>
        new(code) { Content = new StringContent(json, Encoding.UTF8, "application/json") };

    [Fact]
    public void YouTubeUrlAndIdParsingHandlesAllFormats()
    {
        var t1 = YouTubeClient.ParseInput("https://music.youtube.com/playlist?list=PL1234567890abcdef");
        Assert.True(t1.IsPlaylist);
        Assert.Equal("PL1234567890abcdef", t1.PlaylistId);

        var t2 = YouTubeClient.ParseInput("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=OLAK5uy_k12345678");
        Assert.True(t2.IsPlaylist);
        Assert.Equal("OLAK5uy_k12345678", t2.PlaylistId);

        var t3 = YouTubeClient.ParseInput("https://youtu.be/dQw4w9WgXcQ?t=10");
        Assert.False(t3.IsPlaylist);
        Assert.Equal("dQw4w9WgXcQ", t3.VideoId);

        var t4 = YouTubeClient.ParseInput("PLabcdefgh123456");
        Assert.True(t4.IsPlaylist);
        Assert.Equal("PLabcdefgh123456", t4.PlaylistId);

        var t5 = YouTubeClient.ParseInput("dQw4w9WgXcQ");
        Assert.False(t5.IsPlaylist);
        Assert.Equal("dQw4w9WgXcQ", t5.VideoId);

        Assert.Throws<ArgumentException>(() => YouTubeClient.ParseInput("https://example.com/foo"));
    }

    [Fact]
    public void ProxyNormalizationMatchesAriaFormats()
    {
        Assert.Null(YouTubeClient.NormalizeProxyUrl(null));
        Assert.Null(YouTubeClient.NormalizeProxyUrl("   "));
        Assert.Equal("http://127.0.0.1:7890", YouTubeClient.NormalizeProxyUrl("7890"));
        Assert.Equal("http://127.0.0.1:8080", YouTubeClient.NormalizeProxyUrl("127.0.0.1:8080"));
        Assert.Equal("https://proxy.local:3128", YouTubeClient.NormalizeProxyUrl("https://proxy.local:3128"));
        Assert.Throws<ArgumentException>(() => YouTubeClient.NormalizeProxyUrl("99999"));
        Assert.Throws<ArgumentException>(() => YouTubeClient.NormalizeProxyUrl("ftp://proxy:21"));
    }

    [Fact]
    public void SapisidHashHeaderMatchesExpectedSha1Vector()
    {
        string? header = YouTubeClient.BuildSapisidHashHeader(
            "PREF=f1; SAPISID=my_secret_sid; SID=abc",
            "https://music.youtube.com",
            timestampOverride: 1700000000);

        byte[] expected = SHA1.HashData(Encoding.UTF8.GetBytes("1700000000 my_secret_sid https://music.youtube.com"));
        string expectedHex = Convert.ToHexString(expected).ToLowerInvariant();
        Assert.Equal($"SAPISIDHASH 1700000000_{expectedHex}", header);
        Assert.Null(YouTubeClient.BuildSapisidHashHeader("PREF=f1", "https://music.youtube.com"));
    }

    [Fact]
    public async Task YouTubeInnertubeBrowsePaginatesAndParsesBothRenderers()
    {
        string page1Json = """
        {
          "header": {
            "musicDetailHeaderRenderer": {
              "title": { "runs": [ { "text": "Late Night Mix" } ] }
            }
          },
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicPlaylistShelfRenderer": {
                          "contents": [
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "vid00000001" },
                                "thumbnail": {
                                  "musicThumbnailRenderer": {
                                    "thumbnail": { "thumbnails": [{ "url": "https://img.example/1.jpg", "width": 544 }] }
                                  }
                                },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Track One" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Artist A" }, { "text": " • " }, { "text": "3:15" }] } } }
                                ]
                              }
                            },
                            {
                              "continuationItemRenderer": {
                                "continuationEndpoint": {
                                  "continuationCommand": { "token": "CONT_PAGE_2" }
                                }
                              }
                            }
                          ]
                        }
                      }]
                    }
                  }
                }
              }]
            }
          }
        }
        """;

        string page2Json = """
        {
          "onResponseReceivedActions": [{
            "appendContinuationItemsAction": {
              "continuationItems": [{
                "playlistVideoRenderer": {
                  "videoId": "vid00000002",
                  "title": { "simpleText": "Track Two" },
                  "shortBylineText": { "runs": [{ "text": "Artist B" }] },
                  "lengthSeconds": "245",
                  "thumbnail": { "thumbnails": [{ "url": "//img.example/2.jpg", "width": 480 }] }
                }
              }]
            }
          }]
        }
        """;

        int browseCalls = 0;
        var handler = new StubHttpHandler(req =>
        {
            browseCalls++;
            return browseCalls == 1 ? JsonResponse(page1Json) : JsonResponse(page2Json);
        });

        var client = new YouTubeClient(handler);
        var result = await client.FetchPlaylistAsync("https://music.youtube.com/playlist?list=PLTest12345678");

        Assert.Equal(RootKind.CloudYouTube, result.Kind);
        Assert.Equal("Late Night Mix", result.Title);
        Assert.Equal(2, result.Items.Count);
        Assert.Equal("vid00000001", result.Items[0].RemoteTrackId);
        Assert.Equal("Track One", result.Items[0].Title);
        Assert.Equal("Artist A", result.Items[0].Artist);
        Assert.Equal(195, result.Items[0].DurationSeconds);
        Assert.Equal("vid00000002", result.Items[1].RemoteTrackId);
        Assert.Equal("https://img.example/2.jpg", result.Items[1].ArtworkUrl);
        Assert.Equal(245, result.Items[1].DurationSeconds);
        Assert.Equal(2, browseCalls);
    }

    [Fact]
    public async Task YouTubePlayerMultiClientFallbackAndRangeProbeWorks()
    {
        string androidForbidden = """{ "playabilityStatus": { "status": "LOGIN_REQUIRED" } }""";
        string iosSuccess = """
        {
          "streamingData": {
            "adaptiveFormats": [
              { "itag": 251, "url": "https://cdn.example/opus.webm", "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000 },
              { "itag": 140, "url": "https://cdn.example/aac128.m4a", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 129000 }
            ]
          }
        }
        """;

        int playerAttempt = 0;
        bool sawRangeProbe = false;
        var handler = new StubHttpHandler(req =>
        {
            if (req.RequestUri!.AbsoluteUri.Contains("/youtubei/v1/player"))
            {
                playerAttempt++;
                return playerAttempt == 1 ? JsonResponse(androidForbidden) : JsonResponse(iosSuccess);
            }
            if (req.RequestUri.AbsoluteUri == "https://cdn.example/aac128.m4a")
            {
                sawRangeProbe = req.Headers.Range is not null;
                return new HttpResponseMessage(HttpStatusCode.PartialContent);
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new YouTubeClient(handler);
        var stream = await client.ResolveAudioStreamAsync("dQw4w9WgXcQ");

        Assert.Equal("https://cdn.example/aac128.m4a", stream.Url);
        Assert.Equal(".m4a", stream.Extension);
        Assert.True(sawRangeProbe);
        Assert.Equal(2, playerAttempt);
    }

    [Fact]
    public void BilibiliUrlParsingHandlesAllTargets()
    {
        var fav = BilibiliClient.ParseTarget("https://space.bilibili.com/12345/favlist?fid=987654");
        Assert.Equal(BilibiliTargetKind.FavList, fav.Kind);
        Assert.Equal("987654", fav.Id);

        var ml = BilibiliClient.ParseTarget("https://www.bilibili.com/medialist/detail/ml555666");
        Assert.Equal(BilibiliTargetKind.FavList, ml.Kind);
        Assert.Equal("555666", ml.Id);

        var season = BilibiliClient.ParseTarget("https://space.bilibili.com/42/channel/collectiondetail?sid=777");
        Assert.Equal(BilibiliTargetKind.Season, season.Kind);
        Assert.Equal("777", season.Id);
        Assert.Equal("42", season.Mid);

        var series = BilibiliClient.ParseTarget("https://space.bilibili.com/42/channel/seriesdetail?sid=888");
        Assert.Equal(BilibiliTargetKind.Series, series.Kind);
        Assert.Equal("888", series.Id);
        Assert.Equal("42", series.Mid);

        var bv = BilibiliClient.ParseTarget("https://www.bilibili.com/video/BV1xx411c7mD");
        Assert.Equal(BilibiliTargetKind.Video, bv.Kind);
        Assert.Equal("BV1xx411c7mD", bv.Id);

        var av = BilibiliClient.ParseTarget("av170001");
        Assert.Equal(BilibiliTargetKind.Video, av.Kind);
        Assert.Equal("av170001", av.Id);
    }

    [Fact]
    public void BilibiliWbiMixinAndSigningMatchesStandardVector()
    {
        string imgKey = "7cd084941338484aae1ad9425b84077c";
        string subKey = "4932caff0ff746eab6f01bf08b70ac45";

        string mixin = BilibiliClient.GetMixinKey(imgKey + subKey);
        Assert.Equal(32, mixin.Length);
        Assert.Equal("ea1db124af3c7062474693fa704f4ff8", mixin);

        var parameters = new Dictionary<string, string>
        {
            ["foo"] = "114",
            ["bar"] = "514",
            ["baz"] = "1919810!*'()"
        };
        string signed = BilibiliClient.SignWbiQuery(parameters, imgKey, subKey, wtsOverride: 1702204169);

        string expectedRaw = "bar=514&baz=1919810&foo=114&wts=1702204169";
        string expectedRid = Convert.ToHexString(
            MD5.HashData(Encoding.UTF8.GetBytes(expectedRaw + mixin))).ToLowerInvariant();
        Assert.Equal($"{expectedRaw}&w_rid={expectedRid}", signed);
    }

    [Fact]
    public async Task BilibiliExpandsMultiPartVideoAndResolvesWbiPlayUrl()
    {
        string viewJson = """
        {
          "code": 0,
          "data": {
            "bvid": "BV1xx411c7mD",
            "title": "Live Concert",
            "pic": "http://i0.hdslb.com/bfs/archive/cover.jpg",
            "duration": 500,
            "cid": 1001,
            "owner": { "name": "Musician" },
            "pages": [
              { "cid": 1001, "page": 1, "part": "Intro", "duration": 180 },
              { "cid": 1002, "page": 2, "part": "Finale", "duration": 320 }
            ]
          }
        }
        """;

        string navJson = """
        {
          "code": 0,
          "data": {
            "wbi_img": {
              "img_url": "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
              "sub_url": "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"
            }
          }
        }
        """;

        string playUrlJson = """
        {
          "code": 0,
          "data": {
            "dash": {
              "audio": [
                { "id": 30216, "bandwidth": 64000, "baseUrl": "https://upos.example/64k.m4s" },
                { "id": 30280, "bandwidth": 192000, "baseUrl": "https://upos.example/192k.m4s" }
              ]
            }
          }
        }
        """;

        bool sawB23Redirect = false;
        bool sawWbiQuery = false;
        bool sawReferer = false;

        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("b23.tv"))
            {
                sawB23Redirect = true;
                var resp = new HttpResponseMessage(HttpStatusCode.Redirect);
                resp.Headers.Location = new Uri("https://www.bilibili.com/video/BV1xx411c7mD");
                return resp;
            }
            if (url.Contains("/x/frontend/finger/spi"))
            {
                return JsonResponse("""{"code":0,"data":{"b_3":"BUVID3TEST","b_4":"BUVID4TEST"}}""");
            }
            if (url.Contains("/x/web-interface/view"))
            {
                return JsonResponse(viewJson);
            }
            if (url.Contains("/x/web-interface/nav"))
            {
                return JsonResponse(navJson);
            }
            if (url.Contains("/x/player/wbi/playurl"))
            {
                sawWbiQuery = url.Contains("w_rid=") && url.Contains("wts=");
                sawReferer = req.Headers.TryGetValues("Referer", out var refs) &&
                             refs.Any(r => r.Contains("bilibili.com"));
                return JsonResponse(playUrlJson);
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new BilibiliClient(handler);
        var playlist = await client.FetchPlaylistAsync("https://b23.tv/short123");

        Assert.True(sawB23Redirect);
        Assert.Equal(RootKind.CloudBilibili, playlist.Kind);
        Assert.Equal(2, playlist.Items.Count);
        Assert.Equal("BV1xx411c7mD#1001", playlist.Items[0].RemoteTrackId);
        Assert.Equal("BV1xx411c7mD#1002", playlist.Items[1].RemoteTrackId);
        Assert.Equal("P1 Intro", playlist.Items[0].Title);
        Assert.Equal("P2 Finale", playlist.Items[1].Title);
        Assert.Equal("https://i0.hdslb.com/bfs/archive/cover.jpg", playlist.Items[0].ArtworkUrl);

        var stream = await client.ResolveAudioStreamAsync("BV1xx411c7mD#1002");
        Assert.True(sawWbiQuery);
        Assert.True(sawReferer);
        Assert.Equal("https://upos.example/192k.m4s", stream.Url);
        Assert.Equal(".m4a", stream.Extension);
        Assert.True(stream.Headers!.ContainsKey("Referer"));
        Assert.True(stream.Headers!.ContainsKey("Cookie"));
    }

    [Fact]
    public void BilibiliFavAndSeasonJsonParsingSkipsInvalidAndExtractsCids()
    {
        using var favDoc = JsonDocument.Parse("""
        {
          "code": 0,
          "data": {
            "info": { "title": "My Favs" },
            "has_more": false,
            "medias": [
              { "attr": 0, "bvid": "BV1111111111", "title": "Good Song", "duration": 200, "upper": { "name": "Up1" }, "cover": "http://img/a.jpg", "ugc": { "first_cid": 9001 } },
              { "attr": 9, "bvid": "BV2222222222", "title": "已失效视频", "duration": 0 }
            ]
          }
        }
        """);

        var (favTitle, favItems, hasMore) = BilibiliClient.ParseFavListResponse(favDoc.RootElement, "Fallback");
        Assert.Equal("My Favs", favTitle);
        Assert.False(hasMore);
        Assert.Single(favItems);
        Assert.Equal("BV1111111111#9001", favItems[0].RemoteTrackId);
        Assert.Equal("https://img/a.jpg", favItems[0].ArtworkUrl);

        using var seasonDoc = JsonDocument.Parse("""
        {
          "code": 0,
          "data": {
            "meta": { "name": "OST Collection" },
            "page": { "total": 1 },
            "archives": [
              { "bvid": "BV3333333333", "title": "Theme", "duration": 150, "pic": "//img/b.jpg" }
            ]
          }
        }
        """);

        var (sTitle, sItems, sTotal) = BilibiliClient.ParseArchivesListResponse(seasonDoc.RootElement, "Fallback");
        Assert.Equal("OST Collection", sTitle);
        Assert.Equal(1, sTotal);
        Assert.Single(sItems);
        Assert.Equal("https://img/b.jpg", sItems[0].ArtworkUrl);
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
}
