using System.Collections.Concurrent;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
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
    public void YouTubeUrlAndIdParsingHandlesAllFormats()
    {
        var t1 = YouTubeClient.ParseInput("https://music.youtube.com/playlist?list=PL1234567890abcdef");
        Assert.True(t1.IsPlaylist);
        Assert.Equal("PL1234567890abcdef", t1.PlaylistId);

        var t2 = YouTubeClient.ParseInput("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=OLAK5uy_k12345678");
        Assert.True(t2.IsPlaylist);
        Assert.Equal("OLAK5uy_k12345678", t2.PlaylistId);
        Assert.Equal("dQw4w9WgXcQ", t2.VideoId);

        var t2b = YouTubeClient.ParseInput("https://youtu.be/dQw4w9WgXcQ?list=RDdQw4w9WgXcQ");
        Assert.True(t2b.IsPlaylist);
        Assert.Equal("RDdQw4w9WgXcQ", t2b.PlaylistId);
        Assert.Equal("dQw4w9WgXcQ", t2b.VideoId);

        var tBrowse = YouTubeClient.ParseInput("https://music.youtube.com/browse/MPREb_4pQ5x8K9y2Z");
        Assert.True(tBrowse.IsPlaylist);
        Assert.Equal("MPREb_4pQ5x8K9y2Z", tBrowse.PlaylistId);
        Assert.Null(tBrowse.VideoId);

        var t3 = YouTubeClient.ParseInput("https://youtu.be/dQw4w9WgXcQ?t=10");
        Assert.False(t3.IsPlaylist);
        Assert.Equal("dQw4w9WgXcQ", t3.VideoId);

        var t4 = YouTubeClient.ParseInput("PLabcdefgh123456");
        Assert.True(t4.IsPlaylist);
        Assert.Equal("PLabcdefgh123456", t4.PlaylistId);

        var tRdmm = YouTubeClient.ParseInput("RDMM");
        Assert.True(tRdmm.IsPlaylist);
        Assert.Equal("RDMM", tRdmm.PlaylistId);
        Assert.True(YouTubeClient.LooksLikeRawYouTubeId("RDMM"));
        Assert.Equal(RootKind.CloudYouTube, CloudUrlClassifier.Classify("RDMM"));

        var tMpreb = YouTubeClient.ParseInput("MPREb_4pQ5x8K9y2Z");
        Assert.True(tMpreb.IsPlaylist);
        Assert.Equal("MPREb_4pQ5x8K9y2Z", tMpreb.PlaylistId);
        Assert.True(YouTubeClient.LooksLikeRawYouTubeId("MPREb_4pQ5x8K9y2Z"));
        Assert.Equal(RootKind.CloudYouTube, CloudUrlClassifier.Classify("MPREb_4pQ5x8K9y2Z"));

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
        Assert.NotNull(stream.Headers);
        Assert.Equal("bytes=0-", stream.Headers!["Range"]);
    }

    [Fact]
    public async Task YouTubeLockupViewModelPageHeaderAndBrowseIdPrefixWorkWithoutSwJsData()
    {
        string browseJson = """
        {
          "header": {
            "pageHeaderRenderer": {
              "content": {
                "pageHeaderViewModel": {
                  "title": {
                    "dynamicTextViewModel": {
                      "text": { "content": "Album via PageHeaderVM" }
                    }
                  }
                }
              }
            }
          },
          "contents": {
            "twoColumnBrowseResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "lockupViewModel": {
                            "contentId": "vidLockup01",
                            "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                            "contentImage": {
                              "thumbnailViewModel": {
                                "image": {
                                  "sources": [
                                    { "url": "//img.example/small.jpg", "width": 168 },
                                    { "url": "//img.example/large.jpg", "width": 720 }
                                  ]
                                },
                                "overlays": [{
                                  "thumbnailOverlayBadgeViewModel": {
                                    "thumbnailBadges": [{ "thumbnailBadgeViewModel": { "text": "4:05" } }]
                                  }
                                }]
                              }
                            },
                            "metadata": {
                              "lockupMetadataViewModel": {
                                "title": { "content": "Lockup Track One" },
                                "metadata": {
                                  "contentMetadataViewModel": {
                                    "metadataRows": [{
                                      "metadataParts": [{ "text": { "content": "Artist Lockup" } }]
                                    }]
                                  }
                                }
                              }
                            }
                          }
                        },
                        {
                          "lockupViewModel": {
                            "contentId": "vidEleven02",
                            "metadata": {
                              "lockupMetadataViewModel": {
                                "title": { "content": "Lockup Track Two" }
                              }
                            }
                          }
                        },
                        {
                          "lockupViewModel": {
                            "contentId": "PLShouldBeSkipped1234",
                            "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
                            "metadata": {
                              "lockupMetadataViewModel": {
                                "title": { "content": "Nested Playlist" }
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              }]
            }
          }
        }
        """;

        string? capturedBrowseBody = null;
        bool sawSwJsData = false;
        string? clientNameHeader = null;
        string? clientVersionHeader = null;
        string? cookieHeader = null;

        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("sw.js_data", StringComparison.Ordinal))
            {
                sawSwJsData = true;
                return new HttpResponseMessage(HttpStatusCode.NotFound);
            }
            if (url.Contains("/youtubei/v1/browse", StringComparison.Ordinal))
            {
                capturedBrowseBody = req.Content!.ReadAsStringAsync().GetAwaiter().GetResult();
                clientNameHeader = req.Headers.GetValues("X-YouTube-Client-Name").FirstOrDefault();
                clientVersionHeader = req.Headers.GetValues("X-YouTube-Client-Version").FirstOrDefault();
                cookieHeader = req.Headers.GetValues("Cookie").FirstOrDefault();
                return JsonResponse(browseJson);
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new YouTubeClient(handler);
        var result = await client.FetchPlaylistAsync("https://music.youtube.com/browse/MPREb_4pQ5x8K9y2Z");

        Assert.False(sawSwJsData);
        Assert.Equal("67", clientNameHeader);
        Assert.Equal("1.20260707.12.00", clientVersionHeader);
        Assert.Equal("SOCS=CAI", cookieHeader);
        Assert.NotNull(capturedBrowseBody);
        Assert.Contains("\"browseId\":\"MPREb_4pQ5x8K9y2Z\"", capturedBrowseBody);
        Assert.DoesNotContain("VLMPREb_", capturedBrowseBody);

        Assert.Equal("Album via PageHeaderVM", result.Title);
        Assert.Equal("https://music.youtube.com/browse/MPREb_4pQ5x8K9y2Z", result.SourceUrl);
        Assert.Equal(2, result.Items.Count);
        Assert.Equal("vidLockup01", result.Items[0].RemoteTrackId);
        Assert.Equal("Lockup Track One", result.Items[0].Title);
        Assert.Equal("Artist Lockup", result.Items[0].Artist);
        Assert.Equal(245, result.Items[0].DurationSeconds);
        Assert.Equal("https://img.example/large.jpg", result.Items[0].ArtworkUrl);
        Assert.Equal("vidEleven02", result.Items[1].RemoteTrackId);
        Assert.Equal("Lockup Track Two", result.Items[1].Title);
    }

    [Fact]
    public async Task YouTubeRdMixUsesNextEndpointHtmlFallbackAndSingleVideoFallback()
    {
        string nextJson = """
        {
          "contents": {
            "twoColumnWatchNextResults": {
              "playlist": {
                "playlist": {
                  "title": "My YouTube Mix",
                  "contents": [
                    {
                      "playlistPanelVideoRenderer": {
                        "videoId": "dQw4w9WgXcQ",
                        "title": { "simpleText": "Mix Track 1" },
                        "shortBylineText": { "runs": [{ "text": "Mix Artist 1" }] },
                        "lengthText": { "simpleText": "3:33" },
                        "thumbnail": { "thumbnails": [{ "url": "https://img.example/m1.jpg", "width": 320 }] }
                      }
                    },
                    {
                      "playlistPanelVideoRenderer": {
                        "videoId": "abc12345678",
                        "title": { "runs": [{ "text": "Mix Track 2" }] },
                        "longBylineText": { "runs": [{ "text": "Mix Artist 2" }] },
                        "lengthText": { "simpleText": "4:10" }
                      }
                    }
                  ]
                }
              }
            }
          }
        }
        """;

        string? nextBody = null;
        string? nextClientName = null;
        string? nextCookie = null;

        var mixHandler = new StubHttpHandler(req =>
        {
            if (req.RequestUri!.AbsoluteUri.Contains("/youtubei/v1/next", StringComparison.Ordinal))
            {
                nextBody = req.Content!.ReadAsStringAsync().GetAwaiter().GetResult();
                nextClientName = req.Headers.GetValues("X-YouTube-Client-Name").FirstOrDefault();
                nextCookie = req.Headers.GetValues("Cookie").FirstOrDefault();
                return JsonResponse(nextJson);
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var mixClient = new YouTubeClient(mixHandler);
        var mixResult = await mixClient.FetchPlaylistAsync("RDdQw4w9WgXcQ");

        Assert.Equal("1", nextClientName);
        Assert.Equal("SOCS=CAI", nextCookie);
        Assert.NotNull(nextBody);
        Assert.Contains("\"playlistId\":\"RDdQw4w9WgXcQ\"", nextBody);
        Assert.Contains("\"videoId\":\"dQw4w9WgXcQ\"", nextBody);
        Assert.Equal("My YouTube Mix", mixResult.Title);
        Assert.Equal(2, mixResult.Items.Count);
        Assert.Equal("Mix Track 1", mixResult.Items[0].Title);
        Assert.Equal(213, mixResult.Items[0].DurationSeconds);
        Assert.Equal("Mix Artist 2", mixResult.Items[1].Artist);
        Assert.Equal(250, mixResult.Items[1].DurationSeconds);

        // HTML ytInitialData fallback when /browse returns 404
        string htmlPage = """
        <html><head><title>YouTube</title></head><body>
        <script>var ytInitialData = {"metadata":{"playlistMetadataRenderer":{"title":"HTML Fallback List"}},"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"playlistVideoRenderer":{"videoId":"htmlVid0001","title":{"simpleText":"HTML Song"},"shortBylineText":{"runs":[{"text":"HTML Artist"}]},"lengthSeconds":"180"}}]}}}}]}}};</script>
        </body></html>
        """;
        string? htmlCookie = null;
        var htmlHandler = new StubHttpHandler(req =>
        {
            if (req.RequestUri!.AbsoluteUri.StartsWith("https://www.youtube.com/playlist?list=PLHtmlFallback1", StringComparison.Ordinal))
            {
                htmlCookie = req.Headers.GetValues("Cookie").FirstOrDefault();
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(htmlPage, Encoding.UTF8, "text/html")
                };
            }
            return new HttpResponseMessage(HttpStatusCode.BadRequest);
        });

        var htmlResult = await new YouTubeClient(htmlHandler).FetchPlaylistAsync("PLHtmlFallback1");
        Assert.Equal("SOCS=CAI", htmlCookie);
        Assert.Equal("HTML Fallback List", htmlResult.Title);
        var htmlTrack = Assert.Single(htmlResult.Items);
        Assert.Equal("htmlVid0001", htmlTrack.RemoteTrackId);
        Assert.Equal("HTML Song", htmlTrack.Title);

        // If all playlist fetches yield 0 items and VideoId is present, fall back to FetchSingleVideoInfoAsync
        var singleFallbackHandler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/youtubei/v1/player", StringComparison.Ordinal))
            {
                return JsonResponse("""
                {
                  "videoDetails": {
                    "videoId": "dQw4w9WgXcQ",
                    "title": "Single Video Fallback",
                    "author": "Rick",
                    "lengthSeconds": "212"
                  }
                }
                """);
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var singleResult = await new YouTubeClient(singleFallbackHandler)
            .FetchPlaylistAsync("https://youtu.be/dQw4w9WgXcQ?list=PLEmpty1234567");
        Assert.Equal("dQw4w9WgXcQ", singleResult.RemoteId);
        Assert.Equal("Single Video Fallback", singleResult.Title);
        var singleItem = Assert.Single(singleResult.Items);
        Assert.Equal("Rick", singleItem.Artist);
        Assert.Equal(212, singleItem.DurationSeconds);
    }

    [Fact]
    public async Task YouTubeVisionOsVisitorDataPost1Mb403RejectionAndRangeDownloadWork()
    {
        const string visitor = "CgJ2aXNpdG9yVGVzdDEyMzQ1";
        int swJsCalls = 0;
        string? playerBody = null;
        string? playerClientName = null;
        string? playerClientVersion = null;
        string? playerVisitorHeader = null;
        string? playerCookie = null;
        bool rejectedBlocked1Mb = false;
        bool sawFullRangeDownload = false;

        string playerResponse = """
        {
          "streamingData": {
            "adaptiveFormats": [
              {
                "itag": 140,
                "url": "https://cdn.example/blocked-at-1mb.m4a",
                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                "bitrate": 129000,
                "contentLength": "5000000"
              },
              {
                "itag": 141,
                "url": "https://cdn.example/good-256k.m4a",
                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                "bitrate": 256000,
                "contentLength": "2000000"
              }
            ]
          }
        }
        """;

        byte[] audioBytes = Encoding.UTF8.GetBytes("full-speed-m4a-stream");

        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url == "https://www.youtube.com/sw.js_data")
            {
                swJsCalls++;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent($")]}}'\n[[\"f\",\"{visitor}\"]]", Encoding.UTF8, "application/json")
                };
            }
            if (url.Contains("/youtubei/v1/player", StringComparison.Ordinal))
            {
                playerBody = req.Content!.ReadAsStringAsync().GetAwaiter().GetResult();
                playerClientName = req.Headers.GetValues("X-YouTube-Client-Name").FirstOrDefault();
                playerClientVersion = req.Headers.GetValues("X-YouTube-Client-Version").FirstOrDefault();
                playerVisitorHeader = req.Headers.GetValues("X-Goog-Visitor-Id").FirstOrDefault();
                playerCookie = req.Headers.GetValues("Cookie").FirstOrDefault();
                return JsonResponse(playerResponse);
            }
            if (url == "https://cdn.example/blocked-at-1mb.m4a")
            {
                var range = req.Headers.Range?.Ranges.FirstOrDefault();
                if (range is { From: 0, To: 1023 })
                {
                    return new HttpResponseMessage(HttpStatusCode.PartialContent);
                }
                if (range is { From: 1048576, To: 1049599 })
                {
                    rejectedBlocked1Mb = true;
                    return new HttpResponseMessage(HttpStatusCode.Forbidden);
                }
            }
            if (url == "https://cdn.example/good-256k.m4a")
            {
                var range = req.Headers.Range?.Ranges.FirstOrDefault();
                if (range is { From: 0, To: null })
                {
                    sawFullRangeDownload = true;
                    return new HttpResponseMessage(HttpStatusCode.PartialContent)
                    {
                        Content = new ByteArrayContent(audioBytes)
                    };
                }
                if (range is not null)
                {
                    return new HttpResponseMessage(HttpStatusCode.PartialContent);
                }
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new YouTubeClient(handler);
        var stream = await client.ResolveAudioStreamAsync("dQw4w9WgXcQ");

        Assert.Equal(1, swJsCalls);
        Assert.Equal("101", playerClientName);
        Assert.Equal("1.02", playerClientVersion);
        Assert.Equal(visitor, playerVisitorHeader);
        Assert.Equal("SOCS=CAI", playerCookie);
        Assert.NotNull(playerBody);
        Assert.Contains($"\"visitorData\":\"{visitor}\"", playerBody);
        Assert.True(rejectedBlocked1Mb);
        Assert.Equal("https://cdn.example/good-256k.m4a", stream.Url);
        Assert.Equal(".m4a", stream.Extension);
        Assert.Equal("bytes=0-", stream.Headers!["Range"]);
        Assert.Equal(2_000_000L, stream.ContentLength);

        // Second resolve reuses cached visitorData without re-fetching sw.js_data
        var stream2 = await client.ResolveAudioStreamAsync("dQw4w9WgXcQ");
        Assert.Equal("https://cdn.example/good-256k.m4a", stream2.Url);
        Assert.Equal(1, swJsCalls);

        // CloudAudioCache forwards Range: bytes=0- and accepts 206 PartialContent
        string cacheDir = TestHelpers.MakeTempDir();
        var cache = new CloudAudioCache(cacheDir, handler: handler);
        string cachedPath = await cache.GetOrDownloadAsync("yt_track_1", ct => client.ResolveAudioStreamAsync("dQw4w9WgXcQ", ct: ct));
        Assert.True(sawFullRangeDownload);
        Assert.Equal(audioBytes, File.ReadAllBytes(cachedPath));
    }

    [Fact]
    public async Task YouTubeSingleVideoFallsBackToOembedWhenAllPlayerClientsFail()
    {
        var triedClientIds = new List<string>();
        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/youtubei/v1/player", StringComparison.Ordinal))
            {
                if (req.Headers.TryGetValues("X-YouTube-Client-Name", out var vals))
                {
                    triedClientIds.Add(vals.First());
                }
                return JsonResponse("""{ "playabilityStatus": { "status": "LOGIN_REQUIRED" } }""");
            }
            if (url.StartsWith("https://www.youtube.com/oembed?", StringComparison.Ordinal))
            {
                return JsonResponse("""
                {
                  "title": "OEmbed Fallback Song",
                  "author_name": "OEmbed Channel",
                  "thumbnail_url": "http://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
                }
                """);
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new YouTubeClient(handler);
        var result = await client.FetchPlaylistAsync("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        Assert.Equal(["101", "3", "5", "1"], triedClientIds);
        Assert.Equal("OEmbed Fallback Song", result.Title);
        var item = Assert.Single(result.Items);
        Assert.Equal("dQw4w9WgXcQ", item.RemoteTrackId);
        Assert.Equal("OEmbed Channel", item.Artist);
        Assert.Equal("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", item.ArtworkUrl);
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

    [Fact]
    public void ExtractCloudUrlHandlesShareTextTrailingPunctuationAndBareIds()
    {
        // Chinese & English share text with URLs and trailing punctuation
        Assert.Equal(
            "https://b23.tv/abc1234",
            CloudUrlClassifier.ExtractCloudUrl("【歌单】深夜听的歌 https://b23.tv/abc1234"));
        Assert.Equal(
            "https://b23.tv/abc1234",
            CloudUrlClassifier.ExtractCloudUrl("【歌单】深夜听的歌https://b23.tv/abc1234快来听"));
        Assert.Equal(
            "https://music.youtube.com/playlist?list=PL1234567890abcdef",
            CloudUrlClassifier.ExtractCloudUrl("Check out this mix https://music.youtube.com/playlist?list=PL1234567890abcdef。"));
        Assert.Equal(
            "https://youtu.be/dQw4w9WgXcQ",
            CloudUrlClassifier.ExtractCloudUrl("Listen here (https://youtu.be/dQw4w9WgXcQ),"));
        Assert.Equal(
            "https://www.bilibili.com/video/BV1xx411c7mD",
            CloudUrlClassifier.ExtractCloudUrl("Favorite track [https://www.bilibili.com/video/BV1xx411c7mD]."));
        Assert.Equal(
            "https://www.bilibili.com/video/BV1xx411c7mD?p=2",
            CloudUrlClassifier.ExtractCloudUrl("【合集】https://www.bilibili.com/video/BV1xx411c7mD?p=2】"));
        Assert.Equal(
            "https://music.youtube.com/playlist?list=OLAK5uy_k12345678",
            CloudUrlClassifier.ExtractCloudUrl("「夜曲」https://music.youtube.com/playlist?list=OLAK5uy_k12345678」"));
        Assert.Equal(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890abcdef",
            CloudUrlClassifier.ExtractCloudUrl("《歌单》https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1234567890abcdef》"));
        Assert.Equal(
            "https://youtu.be/dQw4w9WgXcQ",
            CloudUrlClassifier.ExtractCloudUrl("Share: \"https://youtu.be/dQw4w9WgXcQ\""));
        Assert.Equal(
            "https://youtu.be/dQw4w9WgXcQ",
            CloudUrlClassifier.ExtractCloudUrl("Share: 'https://youtu.be/dQw4w9WgXcQ'."));

        // Multi-line share text & multiple URLs
        Assert.Equal(
            "https://space.bilibili.com/12345/favlist?fid=987654",
            CloudUrlClassifier.ExtractCloudUrl("【B站收藏夹】夜曲\nhttps://space.bilibili.com/12345/favlist?fid=987654\n打开哔哩哔哩"));
        Assert.Equal(
            "https://b23.tv/abc1234",
            CloudUrlClassifier.ExtractCloudUrl("App: https://example.com/download Link: https://b23.tv/abc1234"));

        // Scheme-less URLs in share text
        Assert.Equal(
            "music.youtube.com/playlist?list=PL1234567890abcdef",
            CloudUrlClassifier.ExtractCloudUrl("Mix: music.youtube.com/playlist?list=PL1234567890abcdef。"));
        Assert.Equal(
            "b23.tv/abc1234",
            CloudUrlClassifier.ExtractCloudUrl("【歌单】b23.tv/abc1234)"));

        // Bare IDs preserved
        Assert.Equal("PL1234567890abcdef", CloudUrlClassifier.ExtractCloudUrl("  PL1234567890abcdef  "));
        Assert.Equal("OLAK5uy_k12345678", CloudUrlClassifier.ExtractCloudUrl("OLAK5uy_k12345678"));
        Assert.Equal("BV1xx411c7mD", CloudUrlClassifier.ExtractCloudUrl("BV1xx411c7mD"));
        Assert.Equal("av170001", CloudUrlClassifier.ExtractCloudUrl("av170001"));
        Assert.Equal("ml555666", CloudUrlClassifier.ExtractCloudUrl("ml555666"));
        Assert.Equal("dQw4w9WgXcQ", CloudUrlClassifier.ExtractCloudUrl("dQw4w9WgXcQ"));
        Assert.Equal(string.Empty, CloudUrlClassifier.ExtractCloudUrl("   "));
    }

    [Fact]
    public void ClassifierAndClientsParseShareTextWithoutTitleInterference()
    {
        // Title mentions YouTube, URL is Bilibili -> classifies as Bilibili
        string biliShare = "【YouTube搬运合集】深夜听的歌 https://b23.tv/abc1234】";
        Assert.Equal(RootKind.CloudBilibili, CloudUrlClassifier.Classify(biliShare));

        string biliFavShare = "My favs [https://space.bilibili.com/42/favlist?fid=987654].";
        Assert.Equal(RootKind.CloudBilibili, CloudUrlClassifier.Classify(biliFavShare));
        var biliTarget = BilibiliClient.ParseTarget(biliFavShare);
        Assert.Equal(BilibiliTargetKind.FavList, biliTarget.Kind);
        Assert.Equal("987654", biliTarget.Id);

        // Title mentions BV id, URL is YouTube Music -> classifies as YouTube
        string ytShare = "Check out this BV1xx411c7mD cover https://music.youtube.com/playlist?list=PL1234567890abcdef。";
        Assert.Equal(RootKind.CloudYouTube, CloudUrlClassifier.Classify(ytShare));
        var ytTarget = YouTubeClient.ParseInput(ytShare);
        Assert.True(ytTarget.IsPlaylist);
        Assert.Equal("PL1234567890abcdef", ytTarget.PlaylistId);

        string ytShortShare = "「Listen」https://youtu.be/dQw4w9WgXcQ?t=10」";
        Assert.Equal(RootKind.CloudYouTube, CloudUrlClassifier.Classify(ytShortShare));
        var ytShortTarget = YouTubeClient.ParseInput(ytShortShare);
        Assert.False(ytShortTarget.IsPlaylist);
        Assert.Equal("dQw4w9WgXcQ", ytShortTarget.VideoId);

        // Bare IDs still classify and parse
        Assert.Equal(RootKind.CloudYouTube, CloudUrlClassifier.Classify("PL1234567890abcdef"));
        Assert.Equal(RootKind.CloudYouTube, CloudUrlClassifier.Classify("OLAK5uy_k12345678"));
        Assert.Equal(RootKind.CloudBilibili, CloudUrlClassifier.Classify("BV1xx411c7mD"));
        Assert.Equal(RootKind.CloudBilibili, CloudUrlClassifier.Classify("av170001"));
        Assert.Equal(RootKind.CloudBilibili, CloudUrlClassifier.Classify("ml555666"));

        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("https://example.com/foo"));
        Assert.Throws<ArgumentException>(() => CloudUrlClassifier.Classify("just some random text"));
    }

    [Fact]
    public async Task LibraryStoreAddCloudRootFromShareTextAndReorderCloudFolderTracks()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();

        string ytJson = """
        {
          "header": { "musicDetailHeaderRenderer": { "title": { "runs": [{ "text": "YT Mix" }] } } },
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
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Song Alpha" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Artist A" }] } } }
                                ]
                              }
                            },
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "vid00000002" },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Song Beta" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Artist B" }] } } }
                                ]
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

        string biliViewJson = """
        {
          "code": 0,
          "data": {
            "bvid": "BV1xx411c7mD",
            "title": "Bili Concert",
            "duration": 400,
            "cid": 1001,
            "owner": { "name": "Uploader" },
            "pages": [
              { "cid": 1001, "page": 1, "part": "Part One", "duration": 180 },
              { "cid": 1002, "page": 2, "part": "Part Two", "duration": 220 }
            ]
          }
        }
        """;

        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/youtubei/v1/browse")) return JsonResponse(ytJson);
            if (url.Contains("b23.tv"))
            {
                var resp = new HttpResponseMessage(HttpStatusCode.Redirect);
                resp.Headers.Location = new Uri("https://www.bilibili.com/video/BV1xx411c7mD");
                return resp;
            }
            if (url.Contains("/x/frontend/finger/spi"))
                return JsonResponse("""{"code":0,"data":{"b_3":"BUVID3TEST","b_4":"BUVID4TEST"}}""");
            if (url.Contains("/x/web-interface/view"))
                return JsonResponse(biliViewJson);
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var lib = new LibraryStore(
            stateDir,
            musicDir,
            new StubMetadataReader(),
            new ArtworkCache(Path.Combine(stateDir, "Art")),
            youtubeClient: new YouTubeClient(handler),
            bilibiliClient: new BilibiliClient(handler),
            artworkHandler: handler);

        var ytRoot = await lib.AddCloudRootAsync("Check out this mix https://music.youtube.com/playlist?list=PLShareTest123。");
        Assert.Equal(RootKind.CloudYouTube, ytRoot.Kind);
        Assert.Equal("https://music.youtube.com/playlist?list=PLShareTest123", ytRoot.SourceUrl);

        var biliRoot = await lib.AddCloudRootAsync("【歌单】深夜听的歌 https://b23.tv/short123)");
        Assert.Equal(RootKind.CloudBilibili, biliRoot.Kind);
        Assert.Equal("https://www.bilibili.com/video/BV1xx411c7mD", biliRoot.SourceUrl);

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
    public async Task YouTubeResolveAudioStreamPopulatesContentLengthWhenPresentAndNullWhenMissing()
    {
        string withLengthJson = """
        {
          "streamingData": {
            "adaptiveFormats": [
              {
                "itag": 140,
                "url": "https://cdn.example/large.m4a",
                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                "bitrate": 129000,
                "contentLength": "10485760"
              }
            ]
          }
        }
        """;

        string withoutLengthJson = """
        {
          "streamingData": {
            "adaptiveFormats": [
              {
                "itag": 140,
                "url": "https://cdn.example/unknown.m4a",
                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                "bitrate": 129000
              }
            ]
          }
        }
        """;

        bool returnWithLength = true;
        var handler = new StubHttpHandler(req =>
        {
            string url = req.RequestUri!.AbsoluteUri;
            if (url == "https://www.youtube.com/sw.js_data")
            {
                return new HttpResponseMessage(HttpStatusCode.NotFound);
            }
            if (url.Contains("/youtubei/v1/player", StringComparison.Ordinal))
            {
                return JsonResponse(returnWithLength ? withLengthJson : withoutLengthJson);
            }
            if (url.StartsWith("https://cdn.example/", StringComparison.Ordinal))
            {
                return new HttpResponseMessage(HttpStatusCode.PartialContent);
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var client = new YouTubeClient(handler);

        var largeStream = await client.ResolveAudioStreamAsync("vidWithLen1");
        Assert.Equal(10_485_760L, largeStream.ContentLength);
        Assert.Equal(".m4a", largeStream.Extension);

        returnWithLength = false;
        var unknownStream = await client.ResolveAudioStreamAsync("vidNoLen002");
        Assert.Null(unknownStream.ContentLength);
        Assert.Equal(".m4a", unknownStream.Extension);
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
}
