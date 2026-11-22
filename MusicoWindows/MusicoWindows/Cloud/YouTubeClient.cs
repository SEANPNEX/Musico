using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

public sealed record YouTubeTarget(string? PlaylistId, string? VideoId)
{
    public bool IsPlaylist => !string.IsNullOrEmpty(PlaylistId);
    public string RemoteId => PlaylistId ?? VideoId ?? string.Empty;
}

public sealed class YouTubeClient
{
    private const string DefaultUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private const string AndroidUserAgent =
        "com.google.android.youtube/19.29.37 (Linux; U; Android 11) gzip";

    private const string IosUserAgent =
        "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)";

    private static readonly Regex RawPlaylistRegex = new(
        @"^(PL|OLAK5uy_|UU|FL|RD)[A-Za-z0-9_-]{8,}$",
        RegexOptions.Compiled);

    private static readonly Regex RawVideoRegex = new(
        @"^[A-Za-z0-9_-]{11}$",
        RegexOptions.Compiled);

    private readonly HttpMessageHandler? _handler;

    public YouTubeClient(HttpMessageHandler? handler = null)
    {
        _handler = handler;
    }

    public static bool LooksLikeRawYouTubeId(string input)
    {
        string trimmed = input.Trim();
        return RawPlaylistRegex.IsMatch(trimmed) || RawVideoRegex.IsMatch(trimmed);
    }

    /// <summary>
    /// Normalizes Aria-style proxy inputs: "7890" -> "http://127.0.0.1:7890",
    /// "127.0.0.1:7890" -> "http://127.0.0.1:7890", or full "http://host:port".
    /// </summary>
    public static string? NormalizeProxyUrl(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        string trimmed = raw.Trim();

        if (trimmed.All(char.IsDigit))
        {
            if (!int.TryParse(trimmed, out int port) || port < 1 || port > 65535)
            {
                throw new ArgumentException("Proxy port must be between 1 and 65535.");
            }
            return $"http://127.0.0.1:{port}";
        }

        string candidate = trimmed.Contains("://", StringComparison.Ordinal) ? trimmed : $"http://{trimmed}";
        if (!Uri.TryCreate(candidate, UriKind.Absolute, out var uri) ||
            string.IsNullOrWhiteSpace(uri.Host) ||
            uri.Port <= 0 ||
            uri.Scheme is not ("http" or "https" or "socks5" or "socks4"))
        {
            throw new ArgumentException("Invalid proxy format. Use port (e.g. 7890), host:port, or http://host:port.");
        }

        return candidate;
    }

    public static YouTubeTarget ParseInput(string input)
    {
        string trimmed = CloudUrlClassifier.ExtractCloudUrl(input);
        if (string.IsNullOrWhiteSpace(trimmed))
        {
            throw new ArgumentException("Enter a YouTube playlist or video URL.");
        }

        if (RawPlaylistRegex.IsMatch(trimmed))
        {
            return new YouTubeTarget(trimmed, null);
        }

        if (RawVideoRegex.IsMatch(trimmed))
        {
            return new YouTubeTarget(null, trimmed);
        }

        string withScheme = trimmed.Contains("://", StringComparison.Ordinal) ? trimmed : $"https://{trimmed}";
        if (!Uri.TryCreate(withScheme, UriKind.Absolute, out var uri))
        {
            throw new ArgumentException("Could not parse YouTube URL or ID.");
        }

        string host = uri.Host.ToLowerInvariant();
        if (!host.Contains("youtube.com") && !host.Contains("youtu.be"))
        {
            throw new ArgumentException("Not a valid YouTube URL.");
        }

        var query = ParseQueryString(uri.Query);
        query.TryGetValue("list", out string? listId);
        query.TryGetValue("v", out string? videoId);

        if (string.IsNullOrWhiteSpace(videoId))
        {
            string path = uri.AbsolutePath.Trim('/');
            if (host.Contains("youtu.be") && !string.IsNullOrWhiteSpace(path))
            {
                videoId = path.Split('/')[0];
            }
            else if (path.StartsWith("shorts/", StringComparison.OrdinalIgnoreCase) ||
                     path.StartsWith("embed/", StringComparison.OrdinalIgnoreCase) ||
                     path.StartsWith("live/", StringComparison.OrdinalIgnoreCase))
            {
                var segs = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
                if (segs.Length >= 2) videoId = segs[1];
            }
        }

        if (!string.IsNullOrWhiteSpace(listId))
        {
            return new YouTubeTarget(listId.Trim(), string.IsNullOrWhiteSpace(videoId) ? null : videoId.Trim());
        }

        if (!string.IsNullOrWhiteSpace(videoId))
        {
            return new YouTubeTarget(null, videoId.Trim());
        }

        throw new ArgumentException("YouTube URL does not contain a playlist (?list=...) or video ID.");
    }

    public static string? BuildSapisidHashHeader(string? cookie, string origin, long? timestampOverride = null)
    {
        if (string.IsNullOrWhiteSpace(cookie)) return null;

        string? sapisid = ExtractCookieValue(cookie, "SAPISID")
                          ?? ExtractCookieValue(cookie, "__Secure-3PAPISID")
                          ?? ExtractCookieValue(cookie, "__Secure-1PAPISID");
        if (string.IsNullOrWhiteSpace(sapisid)) return null;

        long ts = timestampOverride ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        string payload = $"{ts} {sapisid} {origin}";
        byte[] hash = SHA1.HashData(Encoding.UTF8.GetBytes(payload));
        string hex = Convert.ToHexString(hash).ToLowerInvariant();
        return $"SAPISIDHASH {ts}_{hex}";
    }

    private static string? ExtractCookieValue(string cookie, string key)
    {
        foreach (string part in cookie.Split(';'))
        {
            int eq = part.IndexOf('=');
            if (eq <= 0) continue;
            string name = part[..eq].Trim();
            if (string.Equals(name, key, StringComparison.Ordinal))
            {
                return part[(eq + 1)..].Trim();
            }
        }
        return null;
    }

    public async Task<CloudPlaylistResult> FetchPlaylistAsync(
        string sourceUrl,
        string? cookie = null,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        var target = ParseInput(sourceUrl);
        using var http = CloudHttpHelper.CreateClient(proxyUrl, _handler);

        if (target.IsPlaylist)
        {
            string playlistId = target.PlaylistId!;
            string canonicalUrl = $"https://music.youtube.com/playlist?list={playlistId}";

            // Try anonymous/authenticated WEB_REMIX first, then WEB fallback.
            var remixResult = await FetchBrowsePlaylistAsync(
                http,
                endpoint: "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",
                origin: "https://music.youtube.com",
                clientName: "WEB_REMIX",
                clientVersion: "1.20241106.01.00",
                playlistId: playlistId,
                cookie: cookie,
                ct: ct).ConfigureAwait(false);

            if (remixResult is not null && remixResult.Items.Count > 0)
            {
                return remixResult with { SourceUrl = canonicalUrl };
            }

            var webResult = await FetchBrowsePlaylistAsync(
                http,
                endpoint: "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                origin: "https://www.youtube.com",
                clientName: "WEB",
                clientVersion: "2.20241107.01.00",
                playlistId: playlistId,
                cookie: cookie,
                ct: ct).ConfigureAwait(false);

            if (webResult is not null && webResult.Items.Count > 0)
            {
                return webResult with { SourceUrl = canonicalUrl };
            }

            if (remixResult is not null) return remixResult with { SourceUrl = canonicalUrl };
            if (webResult is not null) return webResult with { SourceUrl = canonicalUrl };

            throw new InvalidOperationException("Could not load tracks from this YouTube playlist. Check that it is public or provide a valid cookie.");
        }
        else
        {
            string videoId = target.VideoId!;
            string canonicalUrl = $"https://www.youtube.com/watch?v={videoId}";
            var item = await FetchSingleVideoInfoAsync(http, videoId, cookie, ct).ConfigureAwait(false);
            return new CloudPlaylistResult(
                Kind: RootKind.CloudYouTube,
                RemoteId: videoId,
                Title: item.Title,
                SourceUrl: canonicalUrl,
                Items: [item]);
        }
    }

    private async Task<CloudPlaylistResult?> FetchBrowsePlaylistAsync(
        HttpClient http,
        string endpoint,
        string origin,
        string clientName,
        string clientVersion,
        string playlistId,
        string? cookie,
        CancellationToken ct)
    {
        string browseId = playlistId.StartsWith("VL", StringComparison.Ordinal) ? playlistId : "VL" + playlistId;
        var items = new List<CloudPlaylistItem>();
        var seenIds = new HashSet<string>(StringComparer.Ordinal);
        string? title = null;
        string? continuation = null;

        for (int page = 0; page < 20; page++)
        {
            object body = continuation is null
                ? new
                {
                    context = new
                    {
                        client = new
                        {
                            clientName,
                            clientVersion,
                            hl = "en",
                            gl = "US"
                        }
                    },
                    browseId
                }
                : new
                {
                    context = new
                    {
                        client = new
                        {
                            clientName,
                            clientVersion,
                            hl = "en",
                            gl = "US"
                        }
                    },
                    continuation
                };

            using var doc = await PostInnertubeJsonAsync(http, endpoint, origin, body, cookie, DefaultUserAgent, ct)
                .ConfigureAwait(false);
            if (doc is null) break;

            var parsed = ParseBrowsePlaylistResponse(doc.RootElement, playlistId);
            if (string.IsNullOrWhiteSpace(title) && !string.IsNullOrWhiteSpace(parsed.Title))
            {
                title = parsed.Title;
            }

            foreach (var it in parsed.Items)
            {
                if (seenIds.Add(it.RemoteTrackId))
                {
                    items.Add(it with { TrackNumber = items.Count + 1 });
                }
            }

            continuation = parsed.ContinuationToken;
            if (string.IsNullOrWhiteSpace(continuation)) break;
        }

        if (items.Count == 0 && string.IsNullOrWhiteSpace(title))
        {
            return null;
        }

        string resolvedTitle = string.IsNullOrWhiteSpace(title) ? $"YouTube Playlist ({playlistId})" : title!;
        var normalizedItems = items.Select(i =>
            string.IsNullOrWhiteSpace(i.Album) ? i with { Album = resolvedTitle } : i).ToList();

        return new CloudPlaylistResult(
            Kind: RootKind.CloudYouTube,
            RemoteId: playlistId,
            Title: resolvedTitle,
            SourceUrl: $"https://music.youtube.com/playlist?list={playlistId}",
            Items: normalizedItems);
    }

    public static (string? Title, List<CloudPlaylistItem> Items, string? ContinuationToken) ParseBrowsePlaylistResponse(
        JsonElement root,
        string fallbackAlbumTitle = "")
    {
        string? title = ExtractPlaylistTitle(root);
        var rawItems = new List<CloudPlaylistItem>();
        string? continuation = null;

        WalkBrowseNode(root, rawItems, ref continuation);

        string albumName = string.IsNullOrWhiteSpace(title) ? fallbackAlbumTitle : title!;
        var finalized = new List<CloudPlaylistItem>(rawItems.Count);
        for (int i = 0; i < rawItems.Count; i++)
        {
            var item = rawItems[i];
            finalized.Add(item with
            {
                Album = string.IsNullOrWhiteSpace(item.Album) ? albumName : item.Album,
                TrackNumber = i + 1
            });
        }

        return (title, finalized, continuation);
    }

    private static string? ExtractPlaylistTitle(JsonElement root)
    {
        if (root.TryGetProperty("header", out var header))
        {
            if (header.TryGetProperty("musicDetailHeaderRenderer", out var mdh))
            {
                string? t = ReadText(mdh, "title");
                if (!string.IsNullOrWhiteSpace(t)) return t;
            }
            if (header.TryGetProperty("musicEditablePlaylistDetailHeaderRenderer", out var editable) &&
                editable.TryGetProperty("header", out var inner) &&
                inner.TryGetProperty("musicDetailHeaderRenderer", out var innerMdh))
            {
                string? t = ReadText(innerMdh, "title");
                if (!string.IsNullOrWhiteSpace(t)) return t;
            }
            if (header.TryGetProperty("musicResponsiveHeaderRenderer", out var mrh))
            {
                string? t = ReadText(mrh, "title");
                if (!string.IsNullOrWhiteSpace(t)) return t;
            }
            if (header.TryGetProperty("playlistHeaderRenderer", out var phr))
            {
                string? t = ReadText(phr, "title");
                if (!string.IsNullOrWhiteSpace(t)) return t;
            }
        }

        if (root.TryGetProperty("microformat", out var mf) &&
            mf.TryGetProperty("microformatDataRenderer", out var mfd) &&
            mfd.TryGetProperty("title", out var mft) &&
            mft.ValueKind == JsonValueKind.String)
        {
            return mft.GetString();
        }

        return null;
    }

    private static void WalkBrowseNode(
        JsonElement node,
        List<CloudPlaylistItem> items,
        ref string? continuation)
    {
        if (node.ValueKind == JsonValueKind.Object)
        {
            if (node.TryGetProperty("musicResponsiveListItemRenderer", out var mrl))
            {
                var item = ParseMusicResponsiveListItem(mrl);
                if (item is not null) items.Add(item);
                return;
            }

            if (node.TryGetProperty("playlistVideoRenderer", out var pvr))
            {
                var item = ParsePlaylistVideoRenderer(pvr);
                if (item is not null) items.Add(item);
                return;
            }

            if (continuation is null &&
                node.TryGetProperty("continuationItemRenderer", out var cir) &&
                cir.TryGetProperty("continuationEndpoint", out var cep) &&
                cep.TryGetProperty("continuationCommand", out var cmd) &&
                cmd.TryGetProperty("token", out var tok) &&
                tok.ValueKind == JsonValueKind.String)
            {
                continuation = tok.GetString();
            }
            else if (continuation is null &&
                     node.TryGetProperty("nextContinuationData", out var ncd) &&
                     ncd.TryGetProperty("continuation", out var ctok) &&
                     ctok.ValueKind == JsonValueKind.String)
            {
                continuation = ctok.GetString();
            }

            foreach (var prop in node.EnumerateObject())
            {
                WalkBrowseNode(prop.Value, items, ref continuation);
            }
        }
        else if (node.ValueKind == JsonValueKind.Array)
        {
            foreach (var el in node.EnumerateArray())
            {
                WalkBrowseNode(el, items, ref continuation);
            }
        }
    }

    private static CloudPlaylistItem? ParseMusicResponsiveListItem(JsonElement mrl)
    {
        string? videoId = null;
        if (mrl.TryGetProperty("playlistItemData", out var pid) &&
            pid.TryGetProperty("videoId", out var vidEl) &&
            vidEl.ValueKind == JsonValueKind.String)
        {
            videoId = vidEl.GetString();
        }

        if (string.IsNullOrWhiteSpace(videoId) &&
            mrl.TryGetProperty("overlay", out var ov) &&
            ov.TryGetProperty("musicItemThumbnailOverlayRenderer", out var ovr) &&
            ovr.TryGetProperty("content", out var ovc) &&
            ovc.TryGetProperty("musicPlayButtonRenderer", out var pbr) &&
            pbr.TryGetProperty("playNavigationEndpoint", out var pne) &&
            pne.TryGetProperty("watchEndpoint", out var we) &&
            we.TryGetProperty("videoId", out var vidEl2))
        {
            videoId = vidEl2.GetString();
        }

        string? title = null;
        string? artist = null;
        double duration = 0;

        if (mrl.TryGetProperty("flexColumns", out var cols) && cols.ValueKind == JsonValueKind.Array)
        {
            int colIdx = 0;
            foreach (var col in cols.EnumerateArray())
            {
                if (!col.TryGetProperty("musicResponsiveListItemFlexColumnRenderer", out var fcr))
                {
                    colIdx++;
                    continue;
                }

                string? colText = ReadText(fcr, "text");
                if (colIdx == 0)
                {
                    title = colText;
                    if (string.IsNullOrWhiteSpace(videoId))
                    {
                        videoId = FindFirstWatchVideoId(fcr);
                    }
                }
                else if (colIdx == 1 && !string.IsNullOrWhiteSpace(colText))
                {
                    // On YouTube Music, col 1 runs are often "Artist • Album • 3:45" or just "Artist"
                    string[] parts = colText.Split('•', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
                    if (parts.Length > 0) artist = parts[0];
                    foreach (string p in parts)
                    {
                        double parsedDur = ParseDurationText(p);
                        if (parsedDur > 0) duration = parsedDur;
                    }
                }
                else if (colIdx >= 2 && !string.IsNullOrWhiteSpace(colText))
                {
                    double parsedDur = ParseDurationText(colText);
                    if (parsedDur > 0) duration = parsedDur;
                }

                colIdx++;
            }
        }

        if (duration <= 0 && mrl.TryGetProperty("fixedColumns", out var fixedCols) && fixedCols.ValueKind == JsonValueKind.Array)
        {
            foreach (var col in fixedCols.EnumerateArray())
            {
                if (col.TryGetProperty("musicResponsiveListItemFixedColumnRenderer", out var fcr))
                {
                    double parsedDur = ParseDurationText(ReadText(fcr, "text"));
                    if (parsedDur > 0)
                    {
                        duration = parsedDur;
                        break;
                    }
                }
            }
        }

        if (string.IsNullOrWhiteSpace(videoId) || string.IsNullOrWhiteSpace(title))
        {
            return null;
        }

        string? thumbUrl = null;
        if (mrl.TryGetProperty("thumbnail", out var thumb) &&
            thumb.TryGetProperty("musicThumbnailRenderer", out var mtr) &&
            mtr.TryGetProperty("thumbnail", out var innerThumb))
        {
            thumbUrl = PickLargestThumbnailUrl(innerThumb);
        }

        return new CloudPlaylistItem(
            RemoteTrackId: videoId.Trim(),
            Title: title.Trim(),
            Artist: string.IsNullOrWhiteSpace(artist) ? "YouTube" : artist.Trim(),
            Album: string.Empty,
            DurationSeconds: duration,
            ArtworkUrl: CloudHttpHelper.NormalizeImageUrl(thumbUrl),
            TrackNumber: 0);
    }

    private static CloudPlaylistItem? ParsePlaylistVideoRenderer(JsonElement pvr)
    {
        if (!pvr.TryGetProperty("videoId", out var vidEl) || vidEl.ValueKind != JsonValueKind.String)
            return null;
        string? videoId = vidEl.GetString();
        if (string.IsNullOrWhiteSpace(videoId)) return null;

        string? title = ReadText(pvr, "title");
        if (string.IsNullOrWhiteSpace(title)) return null;

        string? artist = ReadText(pvr, "shortBylineText") ?? ReadText(pvr, "longBylineText");

        double duration = 0;
        if (pvr.TryGetProperty("lengthSeconds", out var lsEl))
        {
            if (lsEl.ValueKind == JsonValueKind.String && double.TryParse(lsEl.GetString(), out double sec))
                duration = sec;
            else if (lsEl.ValueKind == JsonValueKind.Number)
                duration = lsEl.GetDouble();
        }
        if (duration <= 0)
        {
            duration = ParseDurationText(ReadText(pvr, "lengthText"));
        }

        string? thumbUrl = pvr.TryGetProperty("thumbnail", out var thumb) ? PickLargestThumbnailUrl(thumb) : null;

        return new CloudPlaylistItem(
            RemoteTrackId: videoId.Trim(),
            Title: title.Trim(),
            Artist: string.IsNullOrWhiteSpace(artist) ? "YouTube" : artist.Trim(),
            Album: string.Empty,
            DurationSeconds: duration,
            ArtworkUrl: CloudHttpHelper.NormalizeImageUrl(thumbUrl),
            TrackNumber: 0);
    }

    private async Task<CloudPlaylistItem> FetchSingleVideoInfoAsync(
        HttpClient http,
        string videoId,
        string? cookie,
        CancellationToken ct)
    {
        foreach (var client in GetPlayerClients())
        {
            var body = BuildPlayerPayload(videoId, client);
            using var doc = await PostInnertubeJsonAsync(
                http,
                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                "https://www.youtube.com",
                body,
                cookie,
                client.UserAgent,
                ct).ConfigureAwait(false);
            if (doc is null) continue;

            if (doc.RootElement.TryGetProperty("videoDetails", out var vd))
            {
                string title = vd.TryGetProperty("title", out var t) ? t.GetString() ?? videoId : videoId;
                string author = vd.TryGetProperty("author", out var a) ? a.GetString() ?? "YouTube" : "YouTube";
                double duration = 0;
                if (vd.TryGetProperty("lengthSeconds", out var ls))
                {
                    if (ls.ValueKind == JsonValueKind.String) double.TryParse(ls.GetString(), out duration);
                    else if (ls.ValueKind == JsonValueKind.Number) duration = ls.GetDouble();
                }
                string? thumb = vd.TryGetProperty("thumbnail", out var th) ? PickLargestThumbnailUrl(th) : null;

                return new CloudPlaylistItem(
                    RemoteTrackId: videoId,
                    Title: title,
                    Artist: author,
                    Album: title,
                    DurationSeconds: duration,
                    ArtworkUrl: CloudHttpHelper.NormalizeImageUrl(thumb),
                    TrackNumber: 1);
            }
        }

        throw new InvalidOperationException($"Could not load metadata for YouTube video \"{videoId}\".");
    }

    public async Task<CloudAudioStream> ResolveAudioStreamAsync(
        string videoId,
        string? cookie = null,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(videoId))
            throw new ArgumentException("Video ID is required.", nameof(videoId));

        using var http = CloudHttpHelper.CreateClient(proxyUrl, _handler);

        Exception? lastError = null;
        foreach (var client in GetPlayerClients())
        {
            try
            {
                var body = BuildPlayerPayload(videoId, client);
                using var doc = await PostInnertubeJsonAsync(
                    http,
                    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    "https://www.youtube.com",
                    body,
                    cookie,
                    client.UserAgent,
                    ct).ConfigureAwait(false);
                if (doc is null) continue;

                var candidate = SelectBestAudioFormat(doc.RootElement);
                if (candidate is null) continue;

                bool readable = await ProbeStreamReadableAsync(http, candidate.Url, client.UserAgent, ct).ConfigureAwait(false);
                if (!readable) continue;

                var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
                {
                    ["User-Agent"] = client.UserAgent
                };
                return new CloudAudioStream(candidate.Url, candidate.Extension, headers);
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                lastError = ex;
            }
        }

        throw new InvalidOperationException(
            $"Could not resolve a playable audio stream for YouTube track \"{videoId}\".",
            lastError);
    }

    public sealed record AudioFormatCandidate(int Itag, string Url, string Extension, long Bitrate);

    /// <summary>
    /// Selects NAudio/MediaFoundation-compatible AAC M4A (or MP4 fallback) format from Innertube player JSON.
    /// Priority: itag 140 (128k AAC) -> itag 141 (256k AAC) -> itag 139 -> audio/mp4 -> itag 18 (360p mp4).
    /// </summary>
    public static AudioFormatCandidate? SelectBestAudioFormat(JsonElement playerRoot)
    {
        if (!playerRoot.TryGetProperty("streamingData", out var sd)) return null;

        var candidates = new List<AudioFormatCandidate>();

        void Collect(string propName)
        {
            if (!sd.TryGetProperty(propName, out var arr) || arr.ValueKind != JsonValueKind.Array) return;
            foreach (var fmt in arr.EnumerateArray())
            {
                if (!fmt.TryGetProperty("url", out var urlEl) || urlEl.ValueKind != JsonValueKind.String) continue;
                string? url = urlEl.GetString();
                if (string.IsNullOrWhiteSpace(url)) continue;

                int itag = fmt.TryGetProperty("itag", out var itagEl) && itagEl.ValueKind == JsonValueKind.Number
                    ? itagEl.GetInt32()
                    : 0;
                string mime = fmt.TryGetProperty("mimeType", out var mimeEl) ? mimeEl.GetString() ?? string.Empty : string.Empty;
                long bitrate = fmt.TryGetProperty("bitrate", out var brEl) && brEl.ValueKind == JsonValueKind.Number
                    ? brEl.GetInt64()
                    : 0;

                bool isM4a = itag is 140 or 141 or 139 ||
                             mime.Contains("audio/mp4", StringComparison.OrdinalIgnoreCase);
                bool isMp4Fallback = itag == 18 ||
                                     mime.Contains("video/mp4", StringComparison.OrdinalIgnoreCase);

                if (isM4a)
                {
                    candidates.Add(new AudioFormatCandidate(itag, url, ".m4a", bitrate));
                }
                else if (isMp4Fallback)
                {
                    candidates.Add(new AudioFormatCandidate(itag, url, ".mp4", bitrate));
                }
            }
        }

        Collect("adaptiveFormats");
        Collect("formats");

        if (candidates.Count == 0) return null;

        return candidates
            .OrderBy(c => c.Itag switch
            {
                140 => 0,
                141 => 1,
                139 => 2,
                18 => 4,
                _ => c.Extension == ".m4a" ? 3 : 5
            })
            .ThenByDescending(c => c.Bitrate)
            .First();
    }

    public static async Task<bool> ProbeStreamReadableAsync(
        HttpClient http,
        string url,
        string userAgent,
        CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.Range = new RangeHeaderValue(0, 1023);
        req.Headers.TryAddWithoutValidation("User-Agent", userAgent);

        using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        return resp.StatusCode is HttpStatusCode.OK or HttpStatusCode.PartialContent;
    }

    private sealed record PlayerClientSpec(string Name, string Version, int? AndroidSdk, string? DeviceMake, string? DeviceModel, string UserAgent);

    private static IReadOnlyList<PlayerClientSpec> GetPlayerClients() =>
    [
        new("ANDROID", "19.29.37", 30, null, null, AndroidUserAgent),
        new("IOS", "19.29.1", null, "Apple", "iPhone16,2", IosUserAgent),
        new("WEB_EMBEDDED_PLAYER", "1.20241107.01.00", null, null, null, DefaultUserAgent)
    ];

    private static object BuildPlayerPayload(string videoId, PlayerClientSpec spec)
    {
        var client = new Dictionary<string, object>
        {
            ["clientName"] = spec.Name,
            ["clientVersion"] = spec.Version,
            ["hl"] = "en",
            ["gl"] = "US"
        };
        if (spec.AndroidSdk is int sdk) client["androidSdkVersion"] = sdk;
        if (spec.DeviceMake is not null) client["deviceMake"] = spec.DeviceMake;
        if (spec.DeviceModel is not null) client["deviceModel"] = spec.DeviceModel;

        if (spec.Name == "WEB_EMBEDDED_PLAYER")
        {
            return new
            {
                context = new
                {
                    client,
                    thirdParty = new { embedUrl = "https://www.youtube.com/" }
                },
                videoId,
                contentCheckOk = true,
                racyCheckOk = true
            };
        }

        return new
        {
            context = new { client },
            videoId,
            contentCheckOk = true,
            racyCheckOk = true
        };
    }

    private static async Task<JsonDocument?> PostInnertubeJsonAsync(
        HttpClient http,
        string url,
        string origin,
        object body,
        string? cookie,
        string userAgent,
        CancellationToken ct)
    {
        string json = JsonSerializer.Serialize(body);
        using var req = new HttpRequestMessage(HttpMethod.Post, url)
        {
            Content = new StringContent(json, Encoding.UTF8, "application/json")
        };
        req.Headers.TryAddWithoutValidation("User-Agent", userAgent);
        req.Headers.TryAddWithoutValidation("Origin", origin);
        req.Headers.TryAddWithoutValidation("Referer", origin + "/");

        if (!string.IsNullOrWhiteSpace(cookie))
        {
            req.Headers.TryAddWithoutValidation("Cookie", cookie);
            string? auth = BuildSapisidHashHeader(cookie, origin);
            if (auth is not null)
            {
                req.Headers.TryAddWithoutValidation("Authorization", auth);
                req.Headers.TryAddWithoutValidation("X-Origin", origin);
            }
        }

        using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode) return null;

        await using var stream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        return await JsonDocument.ParseAsync(stream, cancellationToken: ct).ConfigureAwait(false);
    }

    private static string? ReadText(JsonElement obj, string propName)
    {
        if (!obj.TryGetProperty(propName, out var el)) return null;
        if (el.ValueKind == JsonValueKind.String) return el.GetString();
        if (el.ValueKind != JsonValueKind.Object) return null;

        if (el.TryGetProperty("simpleText", out var st) && st.ValueKind == JsonValueKind.String)
        {
            return st.GetString();
        }
        if (el.TryGetProperty("runs", out var runs) && runs.ValueKind == JsonValueKind.Array)
        {
            var sb = new StringBuilder();
            foreach (var r in runs.EnumerateArray())
            {
                if (r.TryGetProperty("text", out var t) && t.ValueKind == JsonValueKind.String)
                {
                    sb.Append(t.GetString());
                }
            }
            return sb.Length > 0 ? sb.ToString() : null;
        }
        return null;
    }

    private static string? FindFirstWatchVideoId(JsonElement el)
    {
        if (el.ValueKind == JsonValueKind.Object)
        {
            if (el.TryGetProperty("watchEndpoint", out var we) &&
                we.TryGetProperty("videoId", out var vid) &&
                vid.ValueKind == JsonValueKind.String)
            {
                return vid.GetString();
            }
            foreach (var p in el.EnumerateObject())
            {
                string? found = FindFirstWatchVideoId(p.Value);
                if (found is not null) return found;
            }
        }
        else if (el.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in el.EnumerateArray())
            {
                string? found = FindFirstWatchVideoId(item);
                if (found is not null) return found;
            }
        }
        return null;
    }

    private static string? PickLargestThumbnailUrl(JsonElement thumbContainer)
    {
        if (!thumbContainer.TryGetProperty("thumbnails", out var arr) || arr.ValueKind != JsonValueKind.Array)
            return null;

        string? best = null;
        int bestWidth = -1;
        foreach (var t in arr.EnumerateArray())
        {
            if (!t.TryGetProperty("url", out var urlEl) || urlEl.ValueKind != JsonValueKind.String) continue;
            int w = t.TryGetProperty("width", out var wEl) && wEl.ValueKind == JsonValueKind.Number ? wEl.GetInt32() : 0;
            if (w >= bestWidth)
            {
                bestWidth = w;
                best = urlEl.GetString();
            }
        }
        return best;
    }

    public static double ParseDurationText(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return 0;
        string[] parts = text.Trim().Split(':');
        if (parts.Length is < 2 or > 3) return 0;

        double total = 0;
        foreach (string p in parts)
        {
            if (!int.TryParse(p, out int val) || val < 0) return 0;
            total = total * 60 + val;
        }
        return total;
    }

    private static Dictionary<string, string> ParseQueryString(string query)
    {
        var dict = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (string.IsNullOrEmpty(query)) return dict;
        foreach (string pair in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            int eq = pair.IndexOf('=');
            if (eq < 0) continue;
            string k = Uri.UnescapeDataString(pair[..eq]);
            string v = Uri.UnescapeDataString(pair[(eq + 1)..]);
            dict[k] = v;
        }
        return dict;
    }
}
