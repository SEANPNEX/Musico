using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

public enum BilibiliTargetKind
{
    FavList,
    Season,
    Series,
    Video
}

public sealed record BilibiliTarget(BilibiliTargetKind Kind, string Id, string? Mid = null);

public sealed class BilibiliClient
{
    public const string DefaultReferer = "https://www.bilibili.com/";
    public const string DefaultUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static readonly int[] WbiMixinKeyEncTab =
    [
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    ];

    private static readonly Regex BvidRegex = new(@"BV[0-9A-Za-z]{10}", RegexOptions.Compiled);
    private static readonly Regex AidRegex = new(@"\bav(\d+)\b", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex MlRegex = new(@"\bml(\d+)\b", RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private readonly HttpMessageHandler? _handler;
    private string? _cachedBuvid3;
    private (string ImgKey, string SubKey, DateTimeOffset FetchedUtc)? _cachedWbiKeys;

    public BilibiliClient(HttpMessageHandler? handler = null)
    {
        _handler = handler;
    }

    public static string GetMixinKey(string orig)
    {
        var sb = new StringBuilder(32);
        for (int i = 0; i < 32 && i < WbiMixinKeyEncTab.Length; i++)
        {
            int idx = WbiMixinKeyEncTab[i];
            if (idx < orig.Length)
            {
                sb.Append(orig[idx]);
            }
        }
        return sb.ToString();
    }

    public static string SignWbiQuery(
        IDictionary<string, string> parameters,
        string imgKey,
        string subKey,
        long? wtsOverride = null)
    {
        string mixinKey = GetMixinKey(imgKey + subKey);
        long wts = wtsOverride ?? DateTimeOffset.UtcNow.ToUnixTimeSeconds();

        var sorted = new SortedDictionary<string, string>(parameters, StringComparer.Ordinal)
        {
            ["wts"] = wts.ToString()
        };

        var pairs = new List<string>(sorted.Count);
        foreach (var kvp in sorted)
        {
            string filtered = FilterWbiValue(kvp.Value);
            string encodedKey = EncodeUriComponentUpper(kvp.Key);
            string encodedVal = EncodeUriComponentUpper(filtered);
            pairs.Add($"{encodedKey}={encodedVal}");
        }

        string query = string.Join("&", pairs);
        byte[] hash = MD5.HashData(Encoding.UTF8.GetBytes(query + mixinKey));
        string wRid = Convert.ToHexString(hash).ToLowerInvariant();
        return $"{query}&w_rid={wRid}";
    }

    private static string FilterWbiValue(string value)
    {
        var sb = new StringBuilder(value.Length);
        foreach (char c in value)
        {
            if (c is not ('!' or '\'' or '(' or ')' or '*'))
            {
                sb.Append(c);
            }
        }
        return sb.ToString();
    }

    private static string EncodeUriComponentUpper(string s)
    {
        // Uri.EscapeDataString encodes spaces as %20 and hex as uppercase %XX, matching JS encodeURIComponent.
        return Uri.EscapeDataString(s);
    }

    public static BilibiliTarget ParseTarget(string input)
    {
        string trimmed = CloudUrlClassifier.ExtractCloudUrl(input);
        if (string.IsNullOrWhiteSpace(trimmed))
        {
            throw new ArgumentException("Enter a Bilibili URL, BV ID, or playlist ID.");
        }

        var bvDirect = BvidRegex.Match(trimmed);
        if (bvDirect.Success && !trimmed.Contains("favlist", StringComparison.OrdinalIgnoreCase) &&
            !trimmed.Contains("collectiondetail", StringComparison.OrdinalIgnoreCase) &&
            !trimmed.Contains("seriesdetail", StringComparison.OrdinalIgnoreCase) &&
            !trimmed.Contains("lists", StringComparison.OrdinalIgnoreCase))
        {
            return new BilibiliTarget(BilibiliTargetKind.Video, bvDirect.Value);
        }

        var avDirect = AidRegex.Match(trimmed);
        if (avDirect.Success && !trimmed.Contains('/'))
        {
            return new BilibiliTarget(BilibiliTargetKind.Video, "av" + avDirect.Groups[1].Value);
        }

        var mlDirect = MlRegex.Match(trimmed);
        if (mlDirect.Success && !trimmed.Contains('/'))
        {
            return new BilibiliTarget(BilibiliTargetKind.FavList, mlDirect.Groups[1].Value);
        }

        string withScheme = trimmed.Contains("://", StringComparison.Ordinal) ? trimmed : $"https://{trimmed}";
        if (!Uri.TryCreate(withScheme, UriKind.Absolute, out var uri))
        {
            throw new ArgumentException("Could not parse Bilibili URL.");
        }

        var query = ParseQueryString(uri.Query);
        string path = uri.AbsolutePath;
        string lowerPath = path.ToLowerInvariant();

        // /medialist/detail/ml123456 or favlist?fid=123456
        var mlMatch = MlRegex.Match(path);
        if (mlMatch.Success)
        {
            return new BilibiliTarget(BilibiliTargetKind.FavList, mlMatch.Groups[1].Value);
        }
        if (query.TryGetValue("fid", out string? fid) && !string.IsNullOrWhiteSpace(fid))
        {
            return new BilibiliTarget(BilibiliTargetKind.FavList, fid.Trim());
        }
        if (query.TryGetValue("media_id", out string? mediaId) && !string.IsNullOrWhiteSpace(mediaId))
        {
            return new BilibiliTarget(BilibiliTargetKind.FavList, mediaId.Trim());
        }

        // Extract mid from space.bilibili.com/<mid>/...
        string? mid = query.TryGetValue("mid", out string? qMid) ? qMid : null;
        if (string.IsNullOrWhiteSpace(mid) && uri.Host.Contains("space.bilibili.com", StringComparison.OrdinalIgnoreCase))
        {
            var segs = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
            if (segs.Length > 0 && segs[0].All(char.IsDigit))
            {
                mid = segs[0];
            }
        }

        // collectiondetail?sid=... or season_id=... or lists?sid=...&type=season
        if (lowerPath.Contains("collectiondetail") ||
            query.ContainsKey("season_id") ||
            (lowerPath.Contains("/lists") && query.TryGetValue("type", out var tSeason) && tSeason.Equals("season", StringComparison.OrdinalIgnoreCase)))
        {
            string? sid = query.TryGetValue("season_id", out var s1) ? s1 : query.GetValueOrDefault("sid");
            if (!string.IsNullOrWhiteSpace(sid))
            {
                return new BilibiliTarget(BilibiliTargetKind.Season, sid.Trim(), mid);
            }
        }

        // seriesdetail?sid=... or series_id=... or lists?sid=...&type=series
        if (lowerPath.Contains("seriesdetail") ||
            query.ContainsKey("series_id") ||
            (lowerPath.Contains("/lists") && query.TryGetValue("type", out var tSeries) && tSeries.Equals("series", StringComparison.OrdinalIgnoreCase)))
        {
            string? sid = query.TryGetValue("series_id", out var s1) ? s1 : query.GetValueOrDefault("sid");
            if (!string.IsNullOrWhiteSpace(sid))
            {
                return new BilibiliTarget(BilibiliTargetKind.Series, sid.Trim(), mid);
            }
        }

        // Video BV or av
        var bvMatch = BvidRegex.Match(path);
        if (bvMatch.Success)
        {
            return new BilibiliTarget(BilibiliTargetKind.Video, bvMatch.Value);
        }
        var avMatch = AidRegex.Match(path);
        if (avMatch.Success)
        {
            return new BilibiliTarget(BilibiliTargetKind.Video, "av" + avMatch.Groups[1].Value);
        }

        throw new ArgumentException("Could not identify a Bilibili video, favlist, collection, or series from this URL.");
    }

    public async Task<CloudPlaylistResult> FetchPlaylistAsync(
        string sourceUrl,
        string? cookie = null,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        string extractedUrl = CloudUrlClassifier.ExtractCloudUrl(sourceUrl);
        using var http = CloudHttpHelper.CreateClient(proxyUrl, _handler);
        string resolvedUrl = await ResolveShortLinkIfNeededAsync(http, extractedUrl, ct).ConfigureAwait(false);
        var target = ParseTarget(resolvedUrl);
        string effectiveCookie = await EnsureBuvid3CookieAsync(http, cookie, ct).ConfigureAwait(false);

        return target.Kind switch
        {
            BilibiliTargetKind.FavList => await FetchFavListAsync(http, target.Id, effectiveCookie, resolvedUrl, ct).ConfigureAwait(false),
            BilibiliTargetKind.Season => await FetchSeasonAsync(http, target.Id, target.Mid, effectiveCookie, resolvedUrl, ct).ConfigureAwait(false),
            BilibiliTargetKind.Series => await FetchSeriesAsync(http, target.Id, target.Mid, effectiveCookie, resolvedUrl, ct).ConfigureAwait(false),
            BilibiliTargetKind.Video => await FetchVideoAsync(http, target.Id, effectiveCookie, resolvedUrl, ct).ConfigureAwait(false),
            _ => throw new ArgumentOutOfRangeException()
        };
    }

    private async Task<string> ResolveShortLinkIfNeededAsync(HttpClient http, string url, CancellationToken ct)
    {
        if (!url.Contains("b23.tv", StringComparison.OrdinalIgnoreCase)) return url;

        string full = url.Contains("://", StringComparison.Ordinal) ? url : $"https://{url}";
        using var req = new HttpRequestMessage(HttpMethod.Get, full);
        req.Headers.TryAddWithoutValidation("User-Agent", DefaultUserAgent);

        using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        string? finalUri = resp.RequestMessage?.RequestUri?.ToString();
        if (!string.IsNullOrWhiteSpace(finalUri) && !finalUri.Contains("b23.tv", StringComparison.OrdinalIgnoreCase))
        {
            return finalUri;
        }
        if (resp.Headers.Location is { } loc)
        {
            return loc.IsAbsoluteUri ? loc.ToString() : new Uri(new Uri(full), loc).ToString();
        }
        return full;
    }

    private async Task<string> EnsureBuvid3CookieAsync(HttpClient http, string? cookie, CancellationToken ct)
    {
        string userCookie = cookie?.Trim() ?? string.Empty;
        if (userCookie.Contains("buvid3=", StringComparison.OrdinalIgnoreCase))
        {
            return userCookie;
        }

        if (string.IsNullOrEmpty(_cachedBuvid3))
        {
            try
            {
                using var doc = await GetJsonAsync(http, "https://api.bilibili.com/x/frontend/finger/spi", userCookie, ct)
                    .ConfigureAwait(false);
                if (doc is not null &&
                    doc.RootElement.TryGetProperty("data", out var data) &&
                    data.TryGetProperty("b_3", out var b3) &&
                    b3.ValueKind == JsonValueKind.String)
                {
                    string? v3 = b3.GetString();
                    string? v4 = data.TryGetProperty("b_4", out var b4) && b4.ValueKind == JsonValueKind.String ? b4.GetString() : null;
                    if (!string.IsNullOrWhiteSpace(v3))
                    {
                        _cachedBuvid3 = string.IsNullOrWhiteSpace(v4)
                            ? $"buvid3={v3}"
                            : $"buvid3={v3}; buvid4={v4}";
                    }
                }
            }
            catch
            {
                // Fallback below
            }

            _cachedBuvid3 ??= $"buvid3={Guid.NewGuid():N}infoc";
        }

        return string.IsNullOrEmpty(userCookie) ? _cachedBuvid3 : $"{userCookie}; {_cachedBuvid3}";
    }

    private async Task<(string ImgKey, string SubKey)> GetWbiKeysAsync(HttpClient http, string cookie, CancellationToken ct)
    {
        if (_cachedWbiKeys is { } cached && (DateTimeOffset.UtcNow - cached.FetchedUtc) < TimeSpan.FromMinutes(30))
        {
            return (cached.ImgKey, cached.SubKey);
        }

        using var doc = await GetJsonAsync(http, "https://api.bilibili.com/x/web-interface/nav", cookie, ct)
            .ConfigureAwait(false);
        if (doc is not null &&
            doc.RootElement.TryGetProperty("data", out var data) &&
            data.TryGetProperty("wbi_img", out var wbi))
        {
            string? imgUrl = wbi.TryGetProperty("img_url", out var iu) ? iu.GetString() : null;
            string? subUrl = wbi.TryGetProperty("sub_url", out var su) ? su.GetString() : null;
            string imgKey = ExtractWbiKeyFromUrl(imgUrl);
            string subKey = ExtractWbiKeyFromUrl(subUrl);
            if (!string.IsNullOrEmpty(imgKey) && !string.IsNullOrEmpty(subKey))
            {
                _cachedWbiKeys = (imgKey, subKey, DateTimeOffset.UtcNow);
                return (imgKey, subKey);
            }
        }

        throw new InvalidOperationException("Failed to fetch Bilibili WBI signing keys.");
    }

    public static string ExtractWbiKeyFromUrl(string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) return string.Empty;
        int slash = url.LastIndexOf('/');
        string file = slash >= 0 ? url[(slash + 1)..] : url;
        int dot = file.IndexOf('.');
        return dot >= 0 ? file[..dot] : file;
    }

    private async Task<CloudPlaylistResult> FetchFavListAsync(
        HttpClient http,
        string mediaId,
        string cookie,
        string sourceUrl,
        CancellationToken ct)
    {
        string? title = null;
        var items = new List<CloudPlaylistItem>();

        for (int page = 1; page <= 50; page++)
        {
            string url = $"https://api.bilibili.com/x/v3/fav/resource/list?media_id={Uri.EscapeDataString(mediaId)}&pn={page}&ps=20&platform=web";
            using var doc = await GetJsonAsync(http, url, cookie, ct).ConfigureAwait(false);
            if (doc is null) break;

            var (pageTitle, pageItems, hasMore) = ParseFavListResponse(doc.RootElement, title ?? $"Bilibili Fav {mediaId}");
            if (string.IsNullOrWhiteSpace(title) && !string.IsNullOrWhiteSpace(pageTitle))
            {
                title = pageTitle;
            }
            foreach (var it in pageItems)
            {
                items.Add(it with { TrackNumber = items.Count + 1 });
            }
            if (!hasMore || pageItems.Count == 0) break;
        }

        if (items.Count == 0)
        {
            throw new InvalidOperationException("No playable items found in this Bilibili favlist.");
        }

        string resolvedTitle = string.IsNullOrWhiteSpace(title) ? $"Bilibili Fav {mediaId}" : title!;
        return new CloudPlaylistResult(
            Kind: RootKind.CloudBilibili,
            RemoteId: $"fav:{mediaId}",
            Title: resolvedTitle,
            SourceUrl: sourceUrl,
            Items: items.Select(i => i with { Album = resolvedTitle }).ToList());
    }

    public static (string? Title, List<CloudPlaylistItem> Items, bool HasMore) ParseFavListResponse(
        JsonElement root,
        string albumTitle)
    {
        int code = root.TryGetProperty("code", out var codeEl) ? codeEl.GetInt32() : -1;
        if (code != 0 || !root.TryGetProperty("data", out var data) || data.ValueKind != JsonValueKind.Object)
        {
            return (null, new List<CloudPlaylistItem>(), false);
        }

        string? title = null;
        if (data.TryGetProperty("info", out var info) && info.TryGetProperty("title", out var tEl))
        {
            title = tEl.GetString();
        }
        string resolvedAlbum = string.IsNullOrWhiteSpace(title) ? albumTitle : title!;

        var items = new List<CloudPlaylistItem>();
        if (data.TryGetProperty("medias", out var medias) && medias.ValueKind == JsonValueKind.Array)
        {
            foreach (var m in medias.EnumerateArray())
            {
                int attr = m.TryGetProperty("attr", out var attrEl) && attrEl.ValueKind == JsonValueKind.Number
                    ? attrEl.GetInt32()
                    : 0;
                // attr == 9 indicates invalid/deleted video on Bilibili
                if (attr == 9) continue;

                string? bvid = m.TryGetProperty("bvid", out var bvEl) ? bvEl.GetString() : null;
                if (string.IsNullOrWhiteSpace(bvid)) continue;

                string itemTitle = m.TryGetProperty("title", out var itEl) ? itEl.GetString() ?? bvid : bvid;
                if (itemTitle == "已失效视频") continue;

                string artist = "Bilibili";
                if (m.TryGetProperty("upper", out var up) && up.TryGetProperty("name", out var upName))
                {
                    artist = upName.GetString() ?? artist;
                }

                double duration = m.TryGetProperty("duration", out var durEl) && durEl.ValueKind == JsonValueKind.Number
                    ? durEl.GetDouble()
                    : 0;
                string? cover = m.TryGetProperty("cover", out var covEl) ? covEl.GetString() : null;

                string remoteTrackId = bvid;
                if (m.TryGetProperty("ugc", out var ugc) && ugc.TryGetProperty("first_cid", out var cidEl) &&
                    cidEl.ValueKind == JsonValueKind.Number && cidEl.GetInt64() > 0)
                {
                    remoteTrackId = $"{bvid}#{cidEl.GetInt64()}";
                }

                items.Add(new CloudPlaylistItem(
                    RemoteTrackId: remoteTrackId,
                    Title: itemTitle,
                    Artist: artist,
                    Album: resolvedAlbum,
                    DurationSeconds: duration,
                    ArtworkUrl: CloudHttpHelper.NormalizeImageUrl(cover),
                    TrackNumber: items.Count + 1));
            }
        }

        bool hasMore = data.TryGetProperty("has_more", out var hm) && hm.ValueKind == JsonValueKind.True;
        return (title, items, hasMore);
    }

    private async Task<CloudPlaylistResult> FetchSeasonAsync(
        HttpClient http,
        string seasonId,
        string? mid,
        string cookie,
        string sourceUrl,
        CancellationToken ct)
    {
        string? title = null;
        var items = new List<CloudPlaylistItem>();

        for (int page = 1; page <= 50; page++)
        {
            string url = $"https://api.bilibili.com/x/polymer/web-space/seasons_archives_list?mid={Uri.EscapeDataString(mid ?? "0")}&season_id={Uri.EscapeDataString(seasonId)}&sort_reverse=false&page_num={page}&page_size=30";
            using var doc = await GetJsonAsync(http, url, cookie, ct).ConfigureAwait(false);
            if (doc is null) break;

            var (pageTitle, pageItems, total) = ParseArchivesListResponse(doc.RootElement, title ?? $"Bilibili Season {seasonId}");
            if (string.IsNullOrWhiteSpace(title) && !string.IsNullOrWhiteSpace(pageTitle))
            {
                title = pageTitle;
            }
            foreach (var it in pageItems)
            {
                items.Add(it with { TrackNumber = items.Count + 1 });
            }
            if (pageItems.Count == 0 || (total > 0 && items.Count >= total)) break;
        }

        if (items.Count == 0)
        {
            throw new InvalidOperationException("No playable items found in this Bilibili collection.");
        }

        string resolvedTitle = string.IsNullOrWhiteSpace(title) ? $"Bilibili Collection {seasonId}" : title!;
        return new CloudPlaylistResult(
            Kind: RootKind.CloudBilibili,
            RemoteId: $"season:{seasonId}",
            Title: resolvedTitle,
            SourceUrl: sourceUrl,
            Items: items.Select(i => i with { Album = resolvedTitle }).ToList());
    }

    private async Task<CloudPlaylistResult> FetchSeriesAsync(
        HttpClient http,
        string seriesId,
        string? mid,
        string cookie,
        string sourceUrl,
        CancellationToken ct)
    {
        string? title = null;
        var items = new List<CloudPlaylistItem>();

        for (int page = 1; page <= 50; page++)
        {
            string url = $"https://api.bilibili.com/x/series/archives?mid={Uri.EscapeDataString(mid ?? "0")}&series_id={Uri.EscapeDataString(seriesId)}&only_normal=true&sort=desc&pn={page}&ps=30";
            using var doc = await GetJsonAsync(http, url, cookie, ct).ConfigureAwait(false);
            if (doc is null) break;

            var (pageTitle, pageItems, total) = ParseArchivesListResponse(doc.RootElement, title ?? $"Bilibili Series {seriesId}");
            if (string.IsNullOrWhiteSpace(title) && !string.IsNullOrWhiteSpace(pageTitle))
            {
                title = pageTitle;
            }
            foreach (var it in pageItems)
            {
                items.Add(it with { TrackNumber = items.Count + 1 });
            }
            if (pageItems.Count == 0 || (total > 0 && items.Count >= total)) break;
        }

        if (items.Count == 0)
        {
            throw new InvalidOperationException("No playable items found in this Bilibili series.");
        }

        string resolvedTitle = string.IsNullOrWhiteSpace(title) ? $"Bilibili Series {seriesId}" : title!;
        return new CloudPlaylistResult(
            Kind: RootKind.CloudBilibili,
            RemoteId: $"series:{seriesId}",
            Title: resolvedTitle,
            SourceUrl: sourceUrl,
            Items: items.Select(i => i with { Album = resolvedTitle }).ToList());
    }

    public static (string? Title, List<CloudPlaylistItem> Items, int Total) ParseArchivesListResponse(
        JsonElement root,
        string albumTitle)
    {
        int code = root.TryGetProperty("code", out var codeEl) ? codeEl.GetInt32() : -1;
        if (code != 0 || !root.TryGetProperty("data", out var data) || data.ValueKind != JsonValueKind.Object)
        {
            return (null, new List<CloudPlaylistItem>(), 0);
        }

        string? title = null;
        if (data.TryGetProperty("meta", out var meta) && meta.TryGetProperty("name", out var nameEl))
        {
            title = nameEl.GetString();
        }
        string resolvedAlbum = string.IsNullOrWhiteSpace(title) ? albumTitle : title!;

        int total = 0;
        if (data.TryGetProperty("page", out var pg) && pg.TryGetProperty("total", out var totEl) &&
            totEl.ValueKind == JsonValueKind.Number)
        {
            total = totEl.GetInt32();
        }

        var items = new List<CloudPlaylistItem>();
        if (data.TryGetProperty("archives", out var archives) && archives.ValueKind == JsonValueKind.Array)
        {
            foreach (var a in archives.EnumerateArray())
            {
                string? bvid = a.TryGetProperty("bvid", out var bvEl) ? bvEl.GetString() : null;
                if (string.IsNullOrWhiteSpace(bvid)) continue;

                string itemTitle = a.TryGetProperty("title", out var itEl) ? itEl.GetString() ?? bvid : bvid;
                double duration = a.TryGetProperty("duration", out var durEl) && durEl.ValueKind == JsonValueKind.Number
                    ? durEl.GetDouble()
                    : 0;
                string? pic = a.TryGetProperty("pic", out var picEl) ? picEl.GetString() : null;

                items.Add(new CloudPlaylistItem(
                    RemoteTrackId: bvid,
                    Title: itemTitle,
                    Artist: "Bilibili",
                    Album: resolvedAlbum,
                    DurationSeconds: duration,
                    ArtworkUrl: CloudHttpHelper.NormalizeImageUrl(pic),
                    TrackNumber: items.Count + 1));
            }
        }

        return (title, items, total);
    }

    private async Task<CloudPlaylistResult> FetchVideoAsync(
        HttpClient http,
        string videoId,
        string cookie,
        string sourceUrl,
        CancellationToken ct)
    {
        string param = videoId.StartsWith("av", StringComparison.OrdinalIgnoreCase)
            ? $"aid={Uri.EscapeDataString(videoId[2..])}"
            : $"bvid={Uri.EscapeDataString(videoId)}";

        using var doc = await GetJsonAsync(http, $"https://api.bilibili.com/x/web-interface/view?{param}", cookie, ct)
            .ConfigureAwait(false);
        if (doc is null)
        {
            throw new InvalidOperationException($"Failed to load Bilibili video \"{videoId}\".");
        }

        return ParseVideoViewResponse(doc.RootElement, sourceUrl);
    }

    /// <summary>
    /// Parses /x/web-interface/view JSON and expands multi-part videos (pages.Count > 1)
    /// into separate bvid#cid tracks.
    /// </summary>
    public static CloudPlaylistResult ParseVideoViewResponse(JsonElement root, string sourceUrl)
    {
        int code = root.TryGetProperty("code", out var codeEl) ? codeEl.GetInt32() : -1;
        if (code != 0 || !root.TryGetProperty("data", out var data) || data.ValueKind != JsonValueKind.Object)
        {
            string msg = root.TryGetProperty("message", out var msgEl) ? msgEl.GetString() ?? "Unknown error" : "Unknown error";
            throw new InvalidOperationException($"Bilibili API error ({code}): {msg}");
        }

        string bvid = data.TryGetProperty("bvid", out var bvEl) ? bvEl.GetString() ?? string.Empty : string.Empty;
        string title = data.TryGetProperty("title", out var titleEl) ? titleEl.GetString() ?? bvid : bvid;
        string artist = "Bilibili";
        if (data.TryGetProperty("owner", out var owner) && owner.TryGetProperty("name", out var ownerName))
        {
            artist = ownerName.GetString() ?? artist;
        }
        string? pic = CloudHttpHelper.NormalizeImageUrl(
            data.TryGetProperty("pic", out var picEl) ? picEl.GetString() : null);
        double videoDuration = data.TryGetProperty("duration", out var durEl) && durEl.ValueKind == JsonValueKind.Number
            ? durEl.GetDouble()
            : 0;
        long defaultCid = data.TryGetProperty("cid", out var cidEl) && cidEl.ValueKind == JsonValueKind.Number
            ? cidEl.GetInt64()
            : 0;

        var items = new List<CloudPlaylistItem>();
        if (data.TryGetProperty("pages", out var pages) && pages.ValueKind == JsonValueKind.Array)
        {
            var pageList = pages.EnumerateArray().ToList();
            if (pageList.Count > 1)
            {
                for (int i = 0; i < pageList.Count; i++)
                {
                    var p = pageList[i];
                    long cid = p.TryGetProperty("cid", out var pcid) && pcid.ValueKind == JsonValueKind.Number ? pcid.GetInt64() : 0;
                    int pNum = p.TryGetProperty("page", out var pp) && pp.ValueKind == JsonValueKind.Number ? pp.GetInt32() : i + 1;
                    string part = p.TryGetProperty("part", out var partEl) ? partEl.GetString() ?? $"P{pNum}" : $"P{pNum}";
                    double pDur = p.TryGetProperty("duration", out var pdEl) && pdEl.ValueKind == JsonValueKind.Number ? pdEl.GetDouble() : 0;

                    string remoteId = cid > 0 ? $"{bvid}#{cid}" : $"{bvid}#p{pNum}";
                    string partTitle = string.IsNullOrWhiteSpace(part) ? $"{title} (P{pNum})" : $"P{pNum} {part}";

                    items.Add(new CloudPlaylistItem(
                        RemoteTrackId: remoteId,
                        Title: partTitle,
                        Artist: artist,
                        Album: title,
                        DurationSeconds: pDur > 0 ? pDur : videoDuration,
                        ArtworkUrl: pic,
                        TrackNumber: i + 1));
                }
            }
            else if (pageList.Count == 1)
            {
                var p = pageList[0];
                long cid = p.TryGetProperty("cid", out var pcid) && pcid.ValueKind == JsonValueKind.Number
                    ? pcid.GetInt64()
                    : defaultCid;
                double pDur = p.TryGetProperty("duration", out var pdEl) && pdEl.ValueKind == JsonValueKind.Number
                    ? pdEl.GetDouble()
                    : videoDuration;

                items.Add(new CloudPlaylistItem(
                    RemoteTrackId: cid > 0 ? $"{bvid}#{cid}" : bvid,
                    Title: title,
                    Artist: artist,
                    Album: title,
                    DurationSeconds: pDur > 0 ? pDur : videoDuration,
                    ArtworkUrl: pic,
                    TrackNumber: 1));
            }
        }

        if (items.Count == 0)
        {
            items.Add(new CloudPlaylistItem(
                RemoteTrackId: defaultCid > 0 ? $"{bvid}#{defaultCid}" : bvid,
                Title: title,
                Artist: artist,
                Album: title,
                DurationSeconds: videoDuration,
                ArtworkUrl: pic,
                TrackNumber: 1));
        }

        return new CloudPlaylistResult(
            Kind: RootKind.CloudBilibili,
            RemoteId: $"video:{bvid}",
            Title: title,
            SourceUrl: string.IsNullOrWhiteSpace(sourceUrl) ? $"https://www.bilibili.com/video/{bvid}" : sourceUrl,
            Items: items);
    }

    public async Task<CloudAudioStream> ResolveAudioStreamAsync(
        string remoteTrackId,
        string? cookie = null,
        string? proxyUrl = null,
        CancellationToken ct = default,
        string? quality = null)
    {
        if (string.IsNullOrWhiteSpace(remoteTrackId))
            throw new ArgumentException("Remote track ID is required.", nameof(remoteTrackId));

        string bvid = remoteTrackId;
        long cid = 0;
        int hashIdx = remoteTrackId.IndexOf('#');
        if (hashIdx > 0)
        {
            bvid = remoteTrackId[..hashIdx];
            long.TryParse(remoteTrackId[(hashIdx + 1)..], out cid);
        }

        using var http = CloudHttpHelper.CreateClient(proxyUrl, _handler);
        string effectiveCookie = await EnsureBuvid3CookieAsync(http, cookie, ct).ConfigureAwait(false);

        if (cid <= 0)
        {
            using var viewDoc = await GetJsonAsync(http, $"https://api.bilibili.com/x/web-interface/view?bvid={Uri.EscapeDataString(bvid)}", effectiveCookie, ct)
                .ConfigureAwait(false);
            if (viewDoc is not null &&
                viewDoc.RootElement.TryGetProperty("data", out var vdata) &&
                vdata.TryGetProperty("cid", out var cidEl) &&
                cidEl.ValueKind == JsonValueKind.Number)
            {
                cid = cidEl.GetInt64();
            }
        }

        if (cid <= 0)
        {
            throw new InvalidOperationException($"Could not resolve cid for Bilibili track \"{remoteTrackId}\".");
        }

        // Primary: WBI-signed /x/player/wbi/playurl
        CloudAudioStream? stream = null;
        try
        {
            var (imgKey, subKey) = await GetWbiKeysAsync(http, effectiveCookie, ct).ConfigureAwait(false);
            var queryParams = new Dictionary<string, string>
            {
                ["bvid"] = bvid,
                ["cid"] = cid.ToString(),
                ["fnval"] = "16",
                ["fnver"] = "0",
                ["fourk"] = "1"
            };
            string signedQuery = SignWbiQuery(queryParams, imgKey, subKey);
            string wbiUrl = $"https://api.bilibili.com/x/player/wbi/playurl?{signedQuery}";
            using var wbiDoc = await GetJsonAsync(http, wbiUrl, effectiveCookie, ct).ConfigureAwait(false);
            if (wbiDoc is not null)
            {
                stream = SelectBestAudioFromPlayUrl(wbiDoc.RootElement, effectiveCookie, quality);
            }
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch
        {
            // Fall through to legacy endpoint
        }

        // Fallback: /x/player/playurl
        if (stream is null)
        {
            string fallbackUrl = $"https://api.bilibili.com/x/player/playurl?bvid={Uri.EscapeDataString(bvid)}&cid={cid}&fnval=16&fnver=0&fourk=1";
            using var fbDoc = await GetJsonAsync(http, fallbackUrl, effectiveCookie, ct).ConfigureAwait(false);
            if (fbDoc is not null)
            {
                stream = SelectBestAudioFromPlayUrl(fbDoc.RootElement, effectiveCookie, quality);
            }
        }

        return stream ?? throw new InvalidOperationException($"Could not resolve audio stream for Bilibili track \"{remoteTrackId}\".");
    }

    public static CloudAudioStream? SelectBestAudioFromPlayUrl(
        JsonElement root,
        string? cookie,
        string? quality = null)
    {
        int code = root.TryGetProperty("code", out var codeEl) ? codeEl.GetInt32() : -1;
        if (code != 0 || !root.TryGetProperty("data", out var data) || data.ValueKind != JsonValueKind.Object)
        {
            return null;
        }

        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["Referer"] = DefaultReferer,
            ["User-Agent"] = DefaultUserAgent
        };
        if (!string.IsNullOrWhiteSpace(cookie))
        {
            headers["Cookie"] = cookie;
        }

        string normalizedQuality = string.IsNullOrWhiteSpace(quality)
            ? "highest"
            : quality.Trim().ToLowerInvariant();

        if (data.TryGetProperty("dash", out var dash) && dash.ValueKind == JsonValueKind.Object)
        {
            var candidates = new List<(int Id, string Url, string Ext, long Bandwidth)>();

            if (dash.TryGetProperty("audio", out var audios) && audios.ValueKind == JsonValueKind.Array)
            {
                foreach (var a in audios.EnumerateArray())
                {
                    int id = a.TryGetProperty("id", out var idEl) && idEl.ValueKind == JsonValueKind.Number ? idEl.GetInt32() : 0;
                    long bw = a.TryGetProperty("bandwidth", out var bwEl) && bwEl.ValueKind == JsonValueKind.Number ? bwEl.GetInt64() : 0;
                    string? url = ExtractDashStreamUrl(a);
                    if (!string.IsNullOrWhiteSpace(url))
                    {
                        candidates.Add((id, url, ".m4a", bw));
                    }
                }
            }

            if (dash.TryGetProperty("flac", out var flac) &&
                flac.ValueKind == JsonValueKind.Object &&
                flac.TryGetProperty("audio", out var flacAudio) &&
                flacAudio.ValueKind == JsonValueKind.Object)
            {
                int id = flacAudio.TryGetProperty("id", out var idEl) && idEl.ValueKind == JsonValueKind.Number ? idEl.GetInt32() : 30251;
                long bw = flacAudio.TryGetProperty("bandwidth", out var bwEl) && bwEl.ValueKind == JsonValueKind.Number ? bwEl.GetInt64() : 0;
                string? url = ExtractDashStreamUrl(flacAudio);
                if (!string.IsNullOrWhiteSpace(url))
                {
                    candidates.Add((id, url, ".flac", bw));
                }
            }

            if (candidates.Count > 0)
            {
                var best = candidates
                    .OrderBy(c => RankBilibiliAudioId(c.Id, normalizedQuality))
                    .ThenByDescending(c => c.Bandwidth)
                    .First();
                var (actualQuality, qualityLabel, defaultKbps) = DescribeBilibiliQuality(best.Id, normalizedQuality);
                int? bitrateKbps = best.Bandwidth > 0
                    ? (int)Math.Max(1, Math.Round(best.Bandwidth / 1000.0))
                    : defaultKbps;
                string effectiveLabel = qualityLabel
                    ?? (bitrateKbps is > 0 ? $"{bitrateKbps}k" : "Audio");
                return new CloudAudioStream(
                    best.Url,
                    best.Ext,
                    headers,
                    ActualQuality: actualQuality,
                    QualityLabel: effectiveLabel,
                    BitrateKbps: bitrateKbps);
            }
        }

        if (data.TryGetProperty("durl", out var durl) && durl.ValueKind == JsonValueKind.Array)
        {
            foreach (var d in durl.EnumerateArray())
            {
                if (d.TryGetProperty("url", out var urlEl) && urlEl.ValueKind == JsonValueKind.String)
                {
                    string? url = urlEl.GetString();
                    if (!string.IsNullOrWhiteSpace(url))
                    {
                        return new CloudAudioStream(
                            url,
                            ".mp4",
                            headers,
                            ActualQuality: normalizedQuality,
                            QualityLabel: "MP4");
                    }
                }
            }
        }

        return null;
    }

    private static int RankBilibiliAudioId(int id, string normalizedQuality) => normalizedQuality switch
    {
        "high" => id switch
        {
            30280 => 0,
            30232 => 1,
            30216 => 2,
            30251 => 3,
            _ => 4
        },
        "standard" => id switch
        {
            30232 => 0,
            30280 => 1,
            30216 => 2,
            30251 => 3,
            _ => 4
        },
        "low" => id switch
        {
            30216 => 0,
            30232 => 1,
            30280 => 2,
            30251 => 3,
            _ => 4
        },
        _ => id switch // "highest"
        {
            30251 => 0,
            30280 => 1,
            30232 => 2,
            30216 => 3,
            _ => 4
        }
    };

    private static (string ActualQuality, string? QualityLabel, int? DefaultKbps) DescribeBilibiliQuality(
        int id,
        string requestedQuality) => id switch
    {
        30251 => ("highest", "FLAC", null),
        30280 => ("high", "192k", 192),
        30232 => ("standard", "132k", 132),
        30216 => ("low", "64k", 64),
        _ => (requestedQuality, null, null)
    };

    private static string? ExtractDashStreamUrl(JsonElement node)
    {
        if (node.TryGetProperty("baseUrl", out var bu) && bu.ValueKind == JsonValueKind.String)
        {
            string? s = bu.GetString();
            if (!string.IsNullOrWhiteSpace(s)) return s;
        }
        if (node.TryGetProperty("base_url", out var bu2) && bu2.ValueKind == JsonValueKind.String)
        {
            string? s = bu2.GetString();
            if (!string.IsNullOrWhiteSpace(s)) return s;
        }
        if (node.TryGetProperty("backupUrl", out var backup) && backup.ValueKind == JsonValueKind.Array)
        {
            foreach (var b in backup.EnumerateArray())
            {
                if (b.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(b.GetString()))
                    return b.GetString();
            }
        }
        return null;
    }

    private static async Task<JsonDocument?> GetJsonAsync(
        HttpClient http,
        string url,
        string? cookie,
        CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.TryAddWithoutValidation("Referer", DefaultReferer);
        req.Headers.TryAddWithoutValidation("User-Agent", DefaultUserAgent);
        if (!string.IsNullOrWhiteSpace(cookie))
        {
            req.Headers.TryAddWithoutValidation("Cookie", cookie);
        }

        using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode) return null;

        await using var stream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        return await JsonDocument.ParseAsync(stream, cancellationToken: ct).ConfigureAwait(false);
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
