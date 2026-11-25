using System;
using System.Collections.Generic;
using System.Globalization;
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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";

    private static readonly TimeSpan VisitorDataTtl = TimeSpan.FromMinutes(30);

    private static readonly Regex RawPlaylistRegex = new(
        @"^(?:RDMM[A-Za-z0-9_-]*|MPREb_[A-Za-z0-9_-]{6,}|(?:PL|OLAK5uy_|UU|FL|RD)[A-Za-z0-9_-]{8,})$",
        RegexOptions.Compiled);

    private static readonly Regex RawVideoRegex = new(
        @"^[A-Za-z0-9_-]{11}$",
        RegexOptions.Compiled);

    private static readonly Regex VisitorDataRegex = new(
        @"""(Cg[A-Za-z0-9_%-]{15,})""",
        RegexOptions.Compiled);

    private readonly HttpMessageHandler? _handler;
    private readonly SemaphoreSlim _visitorLock = new(1, 1);
    private string? _cachedVisitorData;
    private DateTimeOffset _visitorDataFetchedAtUtc;

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

        string path = uri.AbsolutePath.Trim('/');
        if (string.IsNullOrWhiteSpace(listId) &&
            path.StartsWith("browse/", StringComparison.OrdinalIgnoreCase))
        {
            var segs = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
            if (segs.Length >= 2 && !string.IsNullOrWhiteSpace(segs[1]))
            {
                return new YouTubeTarget(segs[1].Trim(), null);
            }
        }

        if (string.IsNullOrWhiteSpace(videoId))
        {
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
            string canonicalUrl = playlistId.StartsWith("MPREb_", StringComparison.Ordinal)
                ? $"https://music.youtube.com/browse/{playlistId}"
                : $"https://music.youtube.com/playlist?list={playlistId}";

            bool isRdMix = playlistId.StartsWith("RD", StringComparison.OrdinalIgnoreCase);
            string? mixVideoId = !string.IsNullOrWhiteSpace(target.VideoId)
                ? target.VideoId
                : (isRdMix ? InferVideoIdFromRdPlaylist(playlistId) : null);

            CloudPlaylistResult? fallbackEmpty = null;

            async Task<CloudPlaylistResult?> TryNextAsync()
            {
                var nextResult = await FetchNextPlaylistAsync(http, playlistId, mixVideoId, cookie, ct)
                    .ConfigureAwait(false);
                if (nextResult is not null && nextResult.Items.Count > 0)
                {
                    return nextResult with { SourceUrl = canonicalUrl };
                }
                fallbackEmpty ??= nextResult;
                return null;
            }

            async Task<CloudPlaylistResult?> TryBrowseAsync(string endpoint, string origin, InnertubeClientSpec spec)
            {
                var browseResult = await FetchBrowsePlaylistAsync(
                    http, endpoint, origin, spec, playlistId, cookie, ct).ConfigureAwait(false);
                if (browseResult is not null && browseResult.Items.Count > 0)
                {
                    return browseResult with { SourceUrl = canonicalUrl };
                }
                fallbackEmpty ??= browseResult;
                return null;
            }

            async Task<CloudPlaylistResult?> TryHtmlAsync()
            {
                var htmlResult = await FetchHtmlPlaylistAsync(http, playlistId, cookie, ct).ConfigureAwait(false);
                if (htmlResult is not null && htmlResult.Items.Count > 0)
                {
                    return htmlResult with { SourceUrl = canonicalUrl };
                }
                fallbackEmpty ??= htmlResult;
                return null;
            }

            if (isRdMix)
            {
                if (await TryNextAsync().ConfigureAwait(false) is { } nextHit) return nextHit;
                if (await TryBrowseAsync("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", "https://music.youtube.com", WebRemixClient).ConfigureAwait(false) is { } remixHit) return remixHit;
                if (await TryBrowseAsync("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", "https://www.youtube.com", WebClient).ConfigureAwait(false) is { } webHit) return webHit;
                if (await TryHtmlAsync().ConfigureAwait(false) is { } htmlHit) return htmlHit;
            }
            else
            {
                if (await TryBrowseAsync("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false", "https://music.youtube.com", WebRemixClient).ConfigureAwait(false) is { } remixHit) return remixHit;
                if (await TryBrowseAsync("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false", "https://www.youtube.com", WebClient).ConfigureAwait(false) is { } webHit) return webHit;
                if (!string.IsNullOrWhiteSpace(mixVideoId))
                {
                    if (await TryNextAsync().ConfigureAwait(false) is { } nextHit) return nextHit;
                }
                if (await TryHtmlAsync().ConfigureAwait(false) is { } htmlHit) return htmlHit;
            }

            if (!string.IsNullOrWhiteSpace(target.VideoId))
            {
                var single = await FetchSingleVideoInfoAsync(http, target.VideoId, cookie, ct).ConfigureAwait(false);
                return new CloudPlaylistResult(
                    Kind: RootKind.CloudYouTube,
                    RemoteId: target.VideoId,
                    Title: single.Title,
                    SourceUrl: $"https://www.youtube.com/watch?v={target.VideoId}",
                    Items: [single]);
            }

            if (fallbackEmpty is not null)
            {
                return fallbackEmpty with { SourceUrl = canonicalUrl };
            }

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

    private static string? InferVideoIdFromRdPlaylist(string playlistId)
    {
        if (playlistId.StartsWith("RDAMVM", StringComparison.Ordinal) && playlistId.Length == 17)
        {
            string suffix = playlistId[6..];
            if (RawVideoRegex.IsMatch(suffix)) return suffix;
        }
        if (playlistId.StartsWith("RDMM", StringComparison.Ordinal) && playlistId.Length == 15)
        {
            string suffix = playlistId[4..];
            if (RawVideoRegex.IsMatch(suffix)) return suffix;
        }
        if (playlistId.StartsWith("RD", StringComparison.Ordinal) && playlistId.Length == 13)
        {
            string suffix = playlistId[2..];
            if (RawVideoRegex.IsMatch(suffix)) return suffix;
        }
        return null;
    }

    private async Task<CloudPlaylistResult?> FetchBrowsePlaylistAsync(
        HttpClient http,
        string endpoint,
        string origin,
        InnertubeClientSpec client,
        string playlistId,
        string? cookie,
        CancellationToken ct)
    {
        string browseId = playlistId.StartsWith("VL", StringComparison.Ordinal) ||
                          playlistId.StartsWith("MPREb_", StringComparison.Ordinal) ||
                          playlistId.StartsWith("FEmusic_", StringComparison.Ordinal)
            ? playlistId
            : "VL" + playlistId;

        var items = new List<CloudPlaylistItem>();
        var seenIds = new HashSet<string>(StringComparer.Ordinal);
        string? title = null;
        string? continuation = null;

        for (int page = 0; page < 20; page++)
        {
            object body = continuation is null
                ? new { context = BuildInnertubeContext(client, null), browseId }
                : new { context = BuildInnertubeContext(client, null), continuation };

            using var doc = await PostInnertubeJsonAsync(http, endpoint, origin, client, body, cookie, null, ct)
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

        return FinalizePlaylistResult(playlistId, title, items);
    }

    private async Task<CloudPlaylistResult?> FetchNextPlaylistAsync(
        HttpClient http,
        string playlistId,
        string? videoId,
        string? cookie,
        CancellationToken ct)
    {
        const string endpoint = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false";
        const string origin = "https://www.youtube.com";
        var items = new List<CloudPlaylistItem>();
        var seenIds = new HashSet<string>(StringComparer.Ordinal);
        string? title = null;
        string? continuation = null;

        for (int page = 0; page < 3; page++)
        {
            object body;
            if (continuation is not null)
            {
                body = new { context = BuildInnertubeContext(WebClient, null), continuation };
            }
            else if (!string.IsNullOrWhiteSpace(videoId))
            {
                body = new { context = BuildInnertubeContext(WebClient, null), playlistId, videoId };
            }
            else
            {
                body = new { context = BuildInnertubeContext(WebClient, null), playlistId };
            }

            using var doc = await PostInnertubeJsonAsync(http, endpoint, origin, WebClient, body, cookie, null, ct)
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

        return FinalizePlaylistResult(playlistId, title, items);
    }

    private async Task<CloudPlaylistResult?> FetchHtmlPlaylistAsync(
        HttpClient http,
        string playlistId,
        string? cookie,
        CancellationToken ct)
    {
        string url = $"https://www.youtube.com/playlist?list={Uri.EscapeDataString(playlistId)}";
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.TryAddWithoutValidation("User-Agent", DefaultUserAgent);
        req.Headers.TryAddWithoutValidation("Accept-Language", "en-US,en;q=0.9");
        req.Headers.TryAddWithoutValidation("Cookie", string.IsNullOrWhiteSpace(cookie) ? "SOCS=CAI" : cookie);

        try
        {
            using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return null;

            string html = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            string? initialDataJson = ExtractYtInitialDataJson(html);
            if (string.IsNullOrWhiteSpace(initialDataJson)) return null;

            using var doc = JsonDocument.Parse(initialDataJson);
            var parsed = ParseBrowsePlaylistResponse(doc.RootElement, playlistId);
            return FinalizePlaylistResult(playlistId, parsed.Title, parsed.Items);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            return null;
        }
    }

    private static CloudPlaylistResult? FinalizePlaylistResult(
        string playlistId,
        string? title,
        List<CloudPlaylistItem> items)
    {
        if (items.Count == 0 && string.IsNullOrWhiteSpace(title))
        {
            return null;
        }

        string resolvedTitle = string.IsNullOrWhiteSpace(title) ? $"YouTube Playlist ({playlistId})" : title!;
        var normalizedItems = items.Select((item, idx) =>
            item with
            {
                Album = string.IsNullOrWhiteSpace(item.Album) ? resolvedTitle : item.Album,
                TrackNumber = item.TrackNumber > 0 ? item.TrackNumber : idx + 1
            }).ToList();

        return new CloudPlaylistResult(
            Kind: RootKind.CloudYouTube,
            RemoteId: playlistId,
            Title: resolvedTitle,
            SourceUrl: $"https://music.youtube.com/playlist?list={playlistId}",
            Items: normalizedItems);
    }

    private static string? ExtractYtInitialDataJson(string html)
    {
        if (string.IsNullOrWhiteSpace(html)) return null;

        string[] markers = ["var ytInitialData = ", "window[\"ytInitialData\"] = ", "ytInitialData = "];
        foreach (string marker in markers)
        {
            int idx = html.IndexOf(marker, StringComparison.Ordinal);
            if (idx < 0) continue;
            int braceStart = html.IndexOf('{', idx + marker.Length);
            if (braceStart < 0) continue;
            string? json = ExtractBalancedJsonObject(html, braceStart);
            if (json is not null) return json;
        }

        return null;
    }

    private static string? ExtractBalancedJsonObject(string text, int startIndex)
    {
        int depth = 0;
        bool inString = false;
        bool escape = false;

        for (int i = startIndex; i < text.Length; i++)
        {
            char c = text[i];
            if (escape)
            {
                escape = false;
                continue;
            }
            if (inString)
            {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }

            if (c == '"')
            {
                inString = true;
            }
            else if (c == '{')
            {
                depth++;
            }
            else if (c == '}')
            {
                depth--;
                if (depth == 0)
                {
                    return text[startIndex..(i + 1)];
                }
            }
        }

        return null;
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
            if (header.TryGetProperty("pageHeaderRenderer", out var pageHeader))
            {
                string? pageTitle = ReadText(pageHeader, "pageTitle");
                if (!string.IsNullOrWhiteSpace(pageTitle)) return pageTitle;

                if (pageHeader.TryGetProperty("content", out var phContent) &&
                    phContent.TryGetProperty("pageHeaderViewModel", out var phVm))
                {
                    string? vmTitle = ReadPageHeaderViewModelTitle(phVm);
                    if (!string.IsNullOrWhiteSpace(vmTitle)) return vmTitle;
                }
            }
            if (header.TryGetProperty("pageHeaderViewModel", out var directPhVm))
            {
                string? vmTitle = ReadPageHeaderViewModelTitle(directPhVm);
                if (!string.IsNullOrWhiteSpace(vmTitle)) return vmTitle;
            }
        }

        if (root.TryGetProperty("metadata", out var metadata) &&
            metadata.TryGetProperty("playlistMetadataRenderer", out var pmr))
        {
            string? t = ReadText(pmr, "title");
            if (!string.IsNullOrWhiteSpace(t)) return t;
        }

        if (root.TryGetProperty("contents", out var contents) &&
            contents.TryGetProperty("twoColumnWatchNextResults", out var watchNext) &&
            watchNext.TryGetProperty("playlist", out var plOuter) &&
            plOuter.TryGetProperty("playlist", out var plInner))
        {
            string? t = ReadText(plInner, "title");
            if (!string.IsNullOrWhiteSpace(t)) return t;
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

    private static string? ReadPageHeaderViewModelTitle(JsonElement phVm)
    {
        if (!phVm.TryGetProperty("title", out var titleObj)) return null;
        if (titleObj.TryGetProperty("dynamicTextViewModel", out var dtv) &&
            dtv.TryGetProperty("text", out var textObj))
        {
            return ReadViewModelText(textObj);
        }
        return ReadViewModelText(titleObj);
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

            if (node.TryGetProperty("playlistPanelVideoRenderer", out var ppvr))
            {
                var item = ParsePlaylistVideoRenderer(ppvr);
                if (item is not null) items.Add(item);
                return;
            }

            if (node.TryGetProperty("lockupViewModel", out var lvm))
            {
                var item = ParseLockupViewModel(lvm);
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

    private static CloudPlaylistItem? ParseLockupViewModel(JsonElement lvm)
    {
        string? contentId = lvm.TryGetProperty("contentId", out var cidEl) && cidEl.ValueKind == JsonValueKind.String
            ? cidEl.GetString()?.Trim()
            : null;
        string? contentType = lvm.TryGetProperty("contentType", out var ctEl) && ctEl.ValueKind == JsonValueKind.String
            ? ctEl.GetString()
            : null;

        bool isVideo = string.Equals(contentType, "LOCKUP_CONTENT_TYPE_VIDEO", StringComparison.Ordinal) ||
                       (!string.IsNullOrWhiteSpace(contentId) && RawVideoRegex.IsMatch(contentId));
        if (!isVideo) return null;

        string? videoId = !string.IsNullOrWhiteSpace(contentId) ? contentId : FindFirstWatchVideoId(lvm);
        if (string.IsNullOrWhiteSpace(videoId)) return null;

        string? title = null;
        string? artist = null;

        if (lvm.TryGetProperty("metadata", out var meta) &&
            meta.TryGetProperty("lockupMetadataViewModel", out var lmvm))
        {
            if (lmvm.TryGetProperty("title", out var titleEl))
            {
                title = ReadViewModelText(titleEl);
            }

            if (lmvm.TryGetProperty("metadata", out var innerMeta) &&
                innerMeta.TryGetProperty("contentMetadataViewModel", out var cmvm) &&
                cmvm.TryGetProperty("metadataRows", out var rows) &&
                rows.ValueKind == JsonValueKind.Array)
            {
                foreach (var row in rows.EnumerateArray())
                {
                    if (!row.TryGetProperty("metadataParts", out var parts) || parts.ValueKind != JsonValueKind.Array)
                        continue;

                    foreach (var part in parts.EnumerateArray())
                    {
                        string? partText = part.TryGetProperty("text", out var ptEl)
                            ? ReadViewModelText(ptEl)
                            : null;
                        if (!string.IsNullOrWhiteSpace(partText))
                        {
                            artist = partText.Trim();
                            break;
                        }
                    }

                    if (!string.IsNullOrWhiteSpace(artist)) break;
                }
            }
        }

        if (string.IsNullOrWhiteSpace(title)) return null;

        string? thumbUrl = null;
        double duration = 0;

        if (lvm.TryGetProperty("contentImage", out var contentImage))
        {
            if (contentImage.TryGetProperty("thumbnailViewModel", out var tvm) &&
                tvm.TryGetProperty("image", out var img) &&
                img.TryGetProperty("sources", out var sources))
            {
                thumbUrl = PickLargestThumbnailFromArray(sources);
            }

            duration = FindDurationInBadgeNode(contentImage);
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

    private static double FindDurationInBadgeNode(JsonElement node)
    {
        if (node.ValueKind == JsonValueKind.Object)
        {
            if (node.TryGetProperty("thumbnailBadgeViewModel", out var badge) &&
                badge.TryGetProperty("text", out var textEl) &&
                textEl.ValueKind == JsonValueKind.String)
            {
                double parsed = ParseDurationText(textEl.GetString());
                if (parsed > 0) return parsed;
            }

            foreach (var prop in node.EnumerateObject())
            {
                double parsed = FindDurationInBadgeNode(prop.Value);
                if (parsed > 0) return parsed;
            }
        }
        else if (node.ValueKind == JsonValueKind.Array)
        {
            foreach (var el in node.EnumerateArray())
            {
                double parsed = FindDurationInBadgeNode(el);
                if (parsed > 0) return parsed;
            }
        }

        return 0;
    }

    private static string? ReadViewModelText(JsonElement el)
    {
        if (el.ValueKind == JsonValueKind.String) return el.GetString();
        if (el.ValueKind != JsonValueKind.Object) return null;

        if (el.TryGetProperty("content", out var contentEl) && contentEl.ValueKind == JsonValueKind.String)
        {
            return contentEl.GetString();
        }
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
        string? visitorData = await GetVisitorDataAsync(http, forceRefresh: false, ct).ConfigureAwait(false);

        foreach (var client in GetSingleVideoInfoClients())
        {
            var body = BuildPlayerPayload(videoId, client, visitorData);
            using var doc = await PostInnertubeJsonAsync(
                http,
                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                "https://www.youtube.com",
                client,
                body,
                cookie,
                visitorData,
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

        var oembedItem = await FetchOembedVideoInfoAsync(http, videoId, ct).ConfigureAwait(false);
        if (oembedItem is not null)
        {
            return oembedItem;
        }

        throw new InvalidOperationException($"Could not load metadata for YouTube video \"{videoId}\".");
    }

    private static async Task<CloudPlaylistItem?> FetchOembedVideoInfoAsync(
        HttpClient http,
        string videoId,
        CancellationToken ct)
    {
        string url = $"https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v={Uri.EscapeDataString(videoId)}&format=json";
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.TryAddWithoutValidation("User-Agent", DefaultUserAgent);

        try
        {
            using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return null;

            await using var stream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            using var doc = await JsonDocument.ParseAsync(stream, cancellationToken: ct).ConfigureAwait(false);
            var root = doc.RootElement;

            string? title = root.TryGetProperty("title", out var tEl) && tEl.ValueKind == JsonValueKind.String
                ? tEl.GetString()
                : null;
            if (string.IsNullOrWhiteSpace(title)) return null;

            string? author = root.TryGetProperty("author_name", out var aEl) && aEl.ValueKind == JsonValueKind.String
                ? aEl.GetString()
                : null;
            string? thumb = root.TryGetProperty("thumbnail_url", out var thEl) && thEl.ValueKind == JsonValueKind.String
                ? thEl.GetString()
                : null;

            return new CloudPlaylistItem(
                RemoteTrackId: videoId,
                Title: title.Trim(),
                Artist: string.IsNullOrWhiteSpace(author) ? "YouTube" : author.Trim(),
                Album: title.Trim(),
                DurationSeconds: 0,
                ArtworkUrl: CloudHttpHelper.NormalizeImageUrl(thumb),
                TrackNumber: 1);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            return null;
        }
    }

    public async Task<CloudAudioStream> ResolveAudioStreamAsync(
        string videoId,
        string? cookie = null,
        string? proxyUrl = null,
        CancellationToken ct = default,
        string? quality = null)
    {
        if (string.IsNullOrWhiteSpace(videoId))
            throw new ArgumentException("Video ID is required.", nameof(videoId));

        string normalizedQuality = string.IsNullOrWhiteSpace(quality)
            ? "standard"
            : quality.Trim().ToLowerInvariant();

        using var http = CloudHttpHelper.CreateClient(proxyUrl, _handler);
        string? visitorData = await GetVisitorDataAsync(http, forceRefresh: false, ct).ConfigureAwait(false);

        Exception? lastError = null;
        bool refreshedVisitor = false;
        foreach (var client in GetPlayerClients())
        {
            try
            {
                var body = BuildPlayerPayload(videoId, client, visitorData);
                using var doc = await PostInnertubeJsonAsync(
                    http,
                    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    "https://www.youtube.com",
                    client,
                    body,
                    cookie,
                    visitorData,
                    ct).ConfigureAwait(false);
                if (doc is null)
                {
                    if (!refreshedVisitor && visitorData is not null)
                    {
                        refreshedVisitor = true;
                        visitorData = await GetVisitorDataAsync(http, forceRefresh: true, ct).ConfigureAwait(false) ?? visitorData;
                    }
                    continue;
                }

                var candidates = SelectAudioFormatCandidates(doc.RootElement, normalizedQuality);
                foreach (var candidate in candidates)
                {
                    bool readable = await ProbeStreamReadableAsync(
                        http, candidate.Url, client.UserAgent, ct, candidate.ContentLength).ConfigureAwait(false);
                    if (!readable) continue;

                    var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
                    {
                        ["User-Agent"] = client.UserAgent,
                        ["Range"] = "bytes=0-"
                    };
                    var (actualQuality, qualityLabel, bitrateKbps) = DescribeYouTubeQuality(candidate, normalizedQuality);
                    return new CloudAudioStream(
                        candidate.Url,
                        candidate.Extension,
                        headers,
                        candidate.ContentLength > 0 ? candidate.ContentLength : null,
                        ActualQuality: actualQuality,
                        QualityLabel: qualityLabel,
                        BitrateKbps: bitrateKbps);
                }
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

    public sealed record AudioFormatCandidate(int Itag, string Url, string Extension, long Bitrate, long ContentLength = 0);

    /// <summary>
    /// Selects NAudio/MediaFoundation-compatible AAC M4A (or MP4 fallback) format from Innertube player JSON.
    /// Default priority: itag 140 (128k AAC) -> itag 141 (256k AAC) -> itag 139 -> audio/mp4 -> itag 18 (360p mp4).
    /// </summary>
    public static AudioFormatCandidate? SelectBestAudioFormat(JsonElement playerRoot, string? quality = null) =>
        SelectAudioFormatCandidates(playerRoot, quality).FirstOrDefault();

    public static IReadOnlyList<AudioFormatCandidate> SelectAudioFormatCandidates(
        JsonElement playerRoot,
        string? quality = null)
    {
        if (!playerRoot.TryGetProperty("streamingData", out var sd)) return [];

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
                long contentLength = 0;
                if (fmt.TryGetProperty("contentLength", out var clEl))
                {
                    if (clEl.ValueKind == JsonValueKind.Number && clEl.TryGetInt64(out long clNum))
                    {
                        contentLength = clNum;
                    }
                    else if (clEl.ValueKind == JsonValueKind.String && long.TryParse(clEl.GetString(), out long clParsed))
                    {
                        contentLength = clParsed;
                    }
                }

                bool isM4a = itag is 140 or 141 or 139 ||
                             mime.Contains("audio/mp4", StringComparison.OrdinalIgnoreCase);
                bool isMp4Fallback = itag == 18 ||
                                     mime.Contains("video/mp4", StringComparison.OrdinalIgnoreCase);

                if (isM4a)
                {
                    candidates.Add(new AudioFormatCandidate(itag, url, ".m4a", bitrate, contentLength));
                }
                else if (isMp4Fallback)
                {
                    candidates.Add(new AudioFormatCandidate(itag, url, ".mp4", bitrate, contentLength));
                }
            }
        }

        Collect("adaptiveFormats");
        Collect("formats");

        if (candidates.Count == 0) return [];

        string normalizedQuality = string.IsNullOrWhiteSpace(quality)
            ? "standard"
            : quality.Trim().ToLowerInvariant();

        if (normalizedQuality == "low")
        {
            return candidates
                .OrderBy(c => RankYouTubeCandidate(c, normalizedQuality))
                .ThenBy(c => c.Bitrate > 0 ? c.Bitrate : long.MaxValue)
                .ToList();
        }

        return candidates
            .OrderBy(c => RankYouTubeCandidate(c, normalizedQuality))
            .ThenByDescending(c => c.Bitrate)
            .ToList();
    }

    private static int RankYouTubeCandidate(AudioFormatCandidate c, string normalizedQuality) => normalizedQuality switch
    {
        "highest" => c.Itag switch
        {
            141 => 0,
            140 => 1,
            139 => 3,
            18 => 4,
            _ => c.Extension == ".m4a" ? 2 : 5
        },
        "high" => c.Itag switch
        {
            140 => 0,
            141 => 1,
            139 => 3,
            18 => 4,
            _ => c.Extension == ".m4a" ? 2 : 5
        },
        "low" => c.Itag switch
        {
            139 => 0,
            140 => 2,
            141 => 3,
            18 => 4,
            _ => c.Extension == ".m4a" ? 1 : 5
        },
        _ => c.Itag switch // "standard"
        {
            140 => 0,
            141 => 1,
            139 => 2,
            18 => 4,
            _ => c.Extension == ".m4a" ? 3 : 5
        }
    };

    private static (string ActualQuality, string QualityLabel, int? BitrateKbps) DescribeYouTubeQuality(
        AudioFormatCandidate candidate,
        string requestedQuality)
    {
        int? kbps = candidate.Bitrate > 0
            ? (int)Math.Max(1, Math.Round(candidate.Bitrate / 1000.0))
            : candidate.Itag switch
            {
                141 => 256,
                140 => 128,
                139 => 48,
                _ => null
            };

        string actualQuality = candidate.Itag switch
        {
            141 => "highest",
            140 => string.Equals(requestedQuality, "high", StringComparison.Ordinal) ? "high" : "standard",
            139 => "low",
            18 => "low",
            _ => kbps switch
            {
                >= 200 => "highest",
                >= 110 => string.Equals(requestedQuality, "high", StringComparison.Ordinal) ? "high" : "standard",
                > 0 => "low",
                _ => requestedQuality
            }
        };

        string label = candidate.Itag switch
        {
            141 => kbps is > 0 ? $"{kbps}k" : "256k",
            140 => kbps is > 0 ? $"{kbps}k" : "128k",
            139 => kbps is > 0 ? $"{kbps}k" : "48k",
            18 => kbps is > 0 ? $"{kbps}k" : "MP4",
            _ => kbps is > 0 ? $"{kbps}k" : (candidate.Extension == ".mp4" ? "MP4" : "AAC")
        };

        return (actualQuality, label, kbps);
    }

    public static async Task<bool> ProbeStreamReadableAsync(
        HttpClient http,
        string url,
        string userAgent,
        CancellationToken ct,
        long contentLength = 0)
    {
        if (!await ProbeRangeAsync(http, url, userAgent, 0, 1023, ct).ConfigureAwait(false))
        {
            return false;
        }

        if (contentLength > 1_200_000 &&
            !await ProbeRangeAsync(http, url, userAgent, 1_048_576, 1_049_599, ct).ConfigureAwait(false))
        {
            return false;
        }

        return true;
    }

    private static async Task<bool> ProbeRangeAsync(
        HttpClient http,
        string url,
        string userAgent,
        long from,
        long to,
        CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.Range = new RangeHeaderValue(from, to);
        req.Headers.TryAddWithoutValidation("User-Agent", userAgent);

        using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        return resp.StatusCode is HttpStatusCode.OK or HttpStatusCode.PartialContent;
    }

    private sealed record InnertubeClientSpec(
        string Name,
        string Version,
        int ClientId,
        int? AndroidSdk = null,
        string? DeviceMake = null,
        string? DeviceModel = null,
        string? OsName = null,
        string? OsVersion = null,
        string? ThirdPartyEmbedUrl = null,
        string UserAgent = DefaultUserAgent);

    private static readonly InnertubeClientSpec WebRemixClient = new(
        Name: "WEB_REMIX",
        Version: "1.20260707.12.00",
        ClientId: 67);

    private static readonly InnertubeClientSpec WebClient = new(
        Name: "WEB",
        Version: "2.20260708.00.00",
        ClientId: 1);

    private static readonly InnertubeClientSpec VisionOsClient = new(
        Name: "VISIONOS",
        Version: "1.02",
        ClientId: 101,
        DeviceMake: "Apple",
        DeviceModel: "RealityDevice17,1",
        OsName: "visionOS",
        OsVersion: "26.5.23O471",
        UserAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15");

    private static readonly InnertubeClientSpec AndroidClient = new(
        Name: "ANDROID",
        Version: "20.10.38",
        ClientId: 3,
        AndroidSdk: 35,
        OsName: "Android",
        OsVersion: "15",
        UserAgent: "com.google.android.youtube/20.10.38 (Linux; U; Android 15; en_US) gzip");

    private static readonly InnertubeClientSpec IosClient = new(
        Name: "IOS",
        Version: "20.10.4",
        ClientId: 5,
        DeviceMake: "Apple",
        DeviceModel: "iPhone16,2",
        OsName: "iPhone",
        OsVersion: "18.3.1",
        UserAgent: "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X;)");

    private static readonly InnertubeClientSpec AndroidVrClient = new(
        Name: "ANDROID_VR",
        Version: "1.65.10",
        ClientId: 28,
        DeviceMake: "Oculus",
        DeviceModel: "Quest 3",
        AndroidSdk: 32,
        OsName: "Android",
        OsVersion: "12L",
        UserAgent: "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip");

    private static readonly InnertubeClientSpec WebEmbeddedClient = new(
        Name: "WEB_EMBEDDED_PLAYER",
        Version: "2.20260708.00.00",
        ClientId: 56,
        ThirdPartyEmbedUrl: "https://www.reddit.com/");

    private static IReadOnlyList<InnertubeClientSpec> GetSingleVideoInfoClients() =>
    [
        VisionOsClient,
        AndroidClient,
        IosClient,
        WebClient
    ];

    private static IReadOnlyList<InnertubeClientSpec> GetPlayerClients() =>
    [
        VisionOsClient,
        AndroidClient,
        IosClient,
        AndroidVrClient,
        WebEmbeddedClient
    ];

    private async Task<string?> GetVisitorDataAsync(HttpClient http, bool forceRefresh, CancellationToken ct)
    {
        if (!forceRefresh &&
            _cachedVisitorData is not null &&
            DateTimeOffset.UtcNow - _visitorDataFetchedAtUtc < VisitorDataTtl)
        {
            return _cachedVisitorData;
        }

        await _visitorLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (!forceRefresh &&
                _cachedVisitorData is not null &&
                DateTimeOffset.UtcNow - _visitorDataFetchedAtUtc < VisitorDataTtl)
            {
                return _cachedVisitorData;
            }

            using var req = new HttpRequestMessage(HttpMethod.Get, "https://www.youtube.com/sw.js_data");
            req.Headers.TryAddWithoutValidation("User-Agent", DefaultUserAgent);

            using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode)
            {
                return null;
            }

            string payload = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            var match = VisitorDataRegex.Match(payload);
            if (!match.Success)
            {
                return null;
            }

            _cachedVisitorData = match.Groups[1].Value;
            _visitorDataFetchedAtUtc = DateTimeOffset.UtcNow;
            return _cachedVisitorData;
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            return null;
        }
        finally
        {
            _visitorLock.Release();
        }
    }

    private static object BuildInnertubeContext(InnertubeClientSpec spec, string? visitorData)
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
        if (spec.OsName is not null) client["osName"] = spec.OsName;
        if (spec.OsVersion is not null) client["osVersion"] = spec.OsVersion;
        if (!string.IsNullOrWhiteSpace(visitorData)) client["visitorData"] = visitorData;

        if (spec.ThirdPartyEmbedUrl is not null)
        {
            return new
            {
                client,
                thirdParty = new { embedUrl = spec.ThirdPartyEmbedUrl }
            };
        }

        return new { client };
    }

    private static object BuildPlayerPayload(string videoId, InnertubeClientSpec spec, string? visitorData) =>
        new
        {
            context = BuildInnertubeContext(spec, visitorData),
            videoId,
            contentCheckOk = true,
            racyCheckOk = true
        };

    private static async Task<JsonDocument?> PostInnertubeJsonAsync(
        HttpClient http,
        string url,
        string origin,
        InnertubeClientSpec client,
        object body,
        string? cookie,
        string? visitorData,
        CancellationToken ct)
    {
        string json = JsonSerializer.Serialize(body);
        using var req = new HttpRequestMessage(HttpMethod.Post, url)
        {
            Content = new StringContent(json, Encoding.UTF8, "application/json")
        };
        req.Headers.TryAddWithoutValidation("User-Agent", client.UserAgent);
        req.Headers.TryAddWithoutValidation("Origin", origin);
        req.Headers.TryAddWithoutValidation("Referer", origin + "/");
        req.Headers.TryAddWithoutValidation("X-YouTube-Client-Name", client.ClientId.ToString(CultureInfo.InvariantCulture));
        req.Headers.TryAddWithoutValidation("X-YouTube-Client-Version", client.Version);
        if (!string.IsNullOrWhiteSpace(visitorData))
        {
            req.Headers.TryAddWithoutValidation("X-Goog-Visitor-Id", visitorData);
        }

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
        else
        {
            req.Headers.TryAddWithoutValidation("Cookie", "SOCS=CAI");
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

        return PickLargestThumbnailFromArray(arr);
    }

    private static string? PickLargestThumbnailFromArray(JsonElement arr)
    {
        if (arr.ValueKind != JsonValueKind.Array) return null;

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
