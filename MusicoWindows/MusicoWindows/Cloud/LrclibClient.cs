using System.Globalization;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace MusicoWindows.Cloud;

public sealed class LrclibClient
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    private readonly HttpMessageHandler? _customHandler;
    private readonly string _userAgent;

    public LrclibClient(HttpMessageHandler? customHandler = null, string appVersion = "1.2.3")
    {
        _customHandler = customHandler;
        _userAgent = $"Musico/{appVersion} (https://github.com/SEANPNEX/Musico)";
    }

    public async Task<List<CloudLyricsCandidate>> SearchAsync(
        CloudLyricsQuery query,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        string title = query.TrimmedTitle;
        string artist = query.TrimmedArtist;
        if (string.IsNullOrEmpty(title) && string.IsNullOrEmpty(artist))
        {
            throw CloudLyricsException.EmptyQuery();
        }

        using var http = CloudHttpHelper.CreateClient(proxyUrl, _customHandler, TimeSpan.FromSeconds(15));
        var collected = new List<LrclibRecord>();
        var seenIds = new HashSet<int>();

        if (!string.IsNullOrEmpty(title) && !string.IsNullOrEmpty(artist))
        {
            Task<LrclibRecord?> exactTask = FetchExactAsync(http, query, ct);
            Task<List<LrclibRecord>> structuredTask = FetchSearchAsync(
                http,
                [("track_name", title), ("artist_name", artist)],
                ct);

            await Task.WhenAll(exactTask, structuredTask).ConfigureAwait(false);
            if (exactTask.Result is { } exact && seenIds.Add(exact.Id))
            {
                collected.Add(exact);
            }
            foreach (LrclibRecord rec in structuredTask.Result)
            {
                if (seenIds.Add(rec.Id)) collected.Add(rec);
            }

            if (collected.Count == 0)
            {
                List<LrclibRecord> fallback = await FetchSearchAsync(
                    http,
                    [("q", $"{title} {artist}")],
                    ct).ConfigureAwait(false);
                foreach (LrclibRecord rec in fallback)
                {
                    if (seenIds.Add(rec.Id)) collected.Add(rec);
                }
            }
        }
        else
        {
            string keyword = !string.IsNullOrEmpty(title) ? title : artist;
            List<LrclibRecord> hits = await FetchSearchAsync(http, [("q", keyword)], ct).ConfigureAwait(false);
            foreach (LrclibRecord rec in hits)
            {
                if (seenIds.Add(rec.Id)) collected.Add(rec);
            }
        }

        var mapped = collected.Select(r => r.ToCandidate()).Where(c => c is not null).Cast<CloudLyricsCandidate>();
        return CloudLyricsScorer.RankAndDeduplicate(mapped, query);
    }

    private async Task<LrclibRecord?> FetchExactAsync(HttpClient http, CloudLyricsQuery query, CancellationToken ct)
    {
        var items = new List<(string Key, string Val)>
        {
            ("track_name", query.TrimmedTitle),
            ("artist_name", query.TrimmedArtist)
        };
        if (query.TrimmedAlbum is { } album)
        {
            items.Add(("album_name", album));
        }
        if (query.DurationSec is double dur && !double.IsNaN(dur) && !double.IsInfinity(dur) && dur > 0)
        {
            items.Add(("duration", ((int)Math.Round(dur)).ToString(CultureInfo.InvariantCulture)));
        }

        string url = BuildUrl("https://lrclib.net/api/get", items);
        using HttpResponseMessage resp = await SendGetAsync(http, url, ct).ConfigureAwait(false);
        if (resp.StatusCode == HttpStatusCode.NotFound) return null;
        await CheckRateLimitOrErrorAsync(resp, ct).ConfigureAwait(false);
        string body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        try
        {
            return JsonSerializer.Deserialize<LrclibRecord>(body, JsonOptions);
        }
        catch
        {
            return null;
        }
    }

    private async Task<List<LrclibRecord>> FetchSearchAsync(
        HttpClient http,
        IEnumerable<(string Key, string Val)> items,
        CancellationToken ct)
    {
        string url = BuildUrl("https://lrclib.net/api/search", items);
        using HttpResponseMessage resp = await SendGetAsync(http, url, ct).ConfigureAwait(false);
        if (resp.StatusCode == HttpStatusCode.NotFound) return [];
        await CheckRateLimitOrErrorAsync(resp, ct).ConfigureAwait(false);
        string body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        try
        {
            return JsonSerializer.Deserialize<List<LrclibRecord>>(body, JsonOptions) ?? [];
        }
        catch
        {
            return [];
        }
    }

    private async Task<HttpResponseMessage> SendGetAsync(HttpClient http, string url, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.TryAddWithoutValidation("User-Agent", _userAgent);
        req.Headers.TryAddWithoutValidation("Accept", "application/json");
        try
        {
            return await http.SendAsync(req, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (CloudLyricsException)
        {
            throw;
        }
        catch (Exception ex)
        {
            throw new CloudLyricsException($"LRCLIB request failed: {ex.Message}", inner: ex);
        }
    }

    private static Task CheckRateLimitOrErrorAsync(HttpResponseMessage resp, CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();
        int code = (int)resp.StatusCode;
        if (code == 429 || code == 503)
        {
            int? retryAfter = null;
            if (resp.Headers.TryGetValues("Retry-After", out IEnumerable<string>? values))
            {
                string? raw = values.FirstOrDefault()?.Trim();
                if (int.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out int parsed))
                {
                    retryAfter = parsed;
                }
            }
            throw CloudLyricsException.RateLimited(retryAfter);
        }
        if (code < 200 || code >= 300)
        {
            throw new CloudLyricsException($"LRCLIB HTTP {code}");
        }
        return Task.CompletedTask;
    }

    private static string BuildUrl(string baseUrl, IEnumerable<(string Key, string Val)> items)
    {
        string qs = string.Join("&", items.Select(kv =>
            $"{Uri.EscapeDataString(kv.Key)}={Uri.EscapeDataString(kv.Val)}"));
        return $"{baseUrl}?{qs}";
    }

    private sealed class LrclibRecord
    {
        [JsonPropertyName("id")] public int Id { get; set; }
        [JsonPropertyName("name")] public string? Name { get; set; }
        [JsonPropertyName("trackName")] public string? TrackName { get; set; }
        [JsonPropertyName("artistName")] public string? ArtistName { get; set; }
        [JsonPropertyName("albumName")] public string? AlbumName { get; set; }
        [JsonPropertyName("duration")] public double? Duration { get; set; }
        [JsonPropertyName("instrumental")] public bool? Instrumental { get; set; }
        [JsonPropertyName("plainLyrics")] public string? PlainLyrics { get; set; }
        [JsonPropertyName("syncedLyrics")] public string? SyncedLyrics { get; set; }

        public CloudLyricsCandidate? ToCandidate()
        {
            string title = (TrackName ?? Name ?? string.Empty).Trim();
            string artist = (ArtistName ?? string.Empty).Trim();
            string? synced = string.IsNullOrWhiteSpace(SyncedLyrics) ? null : SyncedLyrics.Trim();
            string? plain = string.IsNullOrWhiteSpace(PlainLyrics) ? null : PlainLyrics.Trim();
            bool isInst = Instrumental == true;
            if (string.IsNullOrEmpty(title) && !isInst) return null;

            var candidate = new CloudLyricsCandidate(
                Id: $"{CloudLyricsProviderIds.Lrclib}:{Id}",
                ProviderId: CloudLyricsProviderIds.Lrclib,
                SourceName: "LRCLIB",
                Title: title,
                Artist: artist,
                Album: string.IsNullOrWhiteSpace(AlbumName) ? null : AlbumName.Trim(),
                DurationSec: Duration,
                IsInstrumental: isInst,
                SyncedLyrics: synced,
                PlainLyrics: plain,
                TranslatedLyrics: null);
            return candidate.HasUsableContent ? candidate : null;
        }
    }
}
