using System.Globalization;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using MusicoWindows.Core;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

public sealed class NeteaseLyricsClient
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    private readonly HttpMessageHandler? _customHandler;

    public NeteaseLyricsClient(HttpMessageHandler? customHandler = null)
    {
        _customHandler = customHandler;
    }

    public async Task<List<CloudLyricsCandidate>> SearchAsync(
        CloudLyricsQuery query,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        string keyword = string.Join(" ", new[] { query.TrimmedTitle, query.TrimmedArtist }.Where(s => !string.IsNullOrEmpty(s)));
        if (string.IsNullOrEmpty(keyword))
        {
            throw CloudLyricsException.EmptyQuery();
        }

        using var http = CloudHttpHelper.CreateClient(proxyUrl, _customHandler, TimeSpan.FromSeconds(15));
        List<NeteaseSong> songs = await SearchSongsAsync(http, keyword, ct).ConfigureAwait(false);
        if (songs.Count == 0) return [];

        List<NeteaseSong> top = SelectTopSongs(songs, query, limit: 5);
        CloudLyricsCandidate?[] fetched = await Task.WhenAll(
            top.Select(song => FetchLyricCandidateAsync(http, song, ct))).ConfigureAwait(false);

        return CloudLyricsScorer.RankAndDeduplicate(
            fetched.Where(c => c is not null).Cast<CloudLyricsCandidate>(),
            query);
    }

    private async Task<List<NeteaseSong>> SearchSongsAsync(HttpClient http, string keyword, CancellationToken ct)
    {
        List<NeteaseSong> primary = await PostLegacySearchAsync(http, keyword, ct).ConfigureAwait(false);
        if (primary.Count > 0) return primary;
        return await GetCloudSearchAsync(http, keyword, ct).ConfigureAwait(false);
    }

    private static async Task<List<NeteaseSong>> PostLegacySearchAsync(HttpClient http, string keyword, CancellationToken ct)
    {
        string form = $"s={Uri.EscapeDataString(keyword)}&type=1&offset=0&limit=10";
        using var req = new HttpRequestMessage(HttpMethod.Post, "https://music.163.com/api/search/get");
        ApplyNeteaseHeaders(req);
        req.Content = new StringContent(form, Encoding.UTF8, "application/x-www-form-urlencoded");

        HttpResponseMessage resp;
        try
        {
            resp = await http.SendAsync(req, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            throw new CloudLyricsException($"NetEase search failed: {ex.Message}", inner: ex);
        }

        using (resp)
        {
            int code = (int)resp.StatusCode;
            if (code == 429 || code == 503) throw CloudLyricsException.RateLimited(ParseRetryAfter(resp));
            if (code < 200 || code >= 300) return [];
            string body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            return DecodeSearchSongs(body);
        }
    }

    private static async Task<List<NeteaseSong>> GetCloudSearchAsync(HttpClient http, string keyword, CancellationToken ct)
    {
        string url = $"https://music.163.com/api/cloudsearch/pc?s={Uri.EscapeDataString(keyword)}&type=1&offset=0&limit=10";
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        ApplyNeteaseHeaders(req);

        HttpResponseMessage resp;
        try
        {
            resp = await http.SendAsync(req, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            throw new CloudLyricsException($"NetEase cloudsearch failed: {ex.Message}", inner: ex);
        }

        using (resp)
        {
            int code = (int)resp.StatusCode;
            if (code == 429 || code == 503) throw CloudLyricsException.RateLimited(ParseRetryAfter(resp));
            if (code < 200 || code >= 300) throw new CloudLyricsException($"NetEase HTTP {code}");
            string body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            return DecodeSearchSongs(body);
        }
    }

    private static async Task<CloudLyricsCandidate?> FetchLyricCandidateAsync(
        HttpClient http,
        NeteaseSong song,
        CancellationToken ct)
    {
        string url = $"https://music.163.com/api/song/lyric?os=pc&id={song.Id}&lv=-1&tv=-1&kv=-1";
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        ApplyNeteaseHeaders(req);

        HttpResponseMessage resp;
        try
        {
            resp = await http.SendAsync(req, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            return null;
        }

        using (resp)
        {
            int code = (int)resp.StatusCode;
            if (code == 429 || code == 503) throw CloudLyricsException.RateLimited(ParseRetryAfter(resp));
            if (code < 200 || code >= 300) return null;
            string body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);

            NeteaseLyricResponse? payload;
            try
            {
                payload = JsonSerializer.Deserialize<NeteaseLyricResponse>(body, JsonOptions);
            }
            catch
            {
                return null;
            }
            if (payload is null) return null;

            if (payload.NoLyric == true)
            {
                return new CloudLyricsCandidate(
                    Id: $"{CloudLyricsProviderIds.Netease}:{song.Id}",
                    ProviderId: CloudLyricsProviderIds.Netease,
                    SourceName: "NetEase",
                    Title: song.Title,
                    Artist: song.ArtistName,
                    Album: song.AlbumName,
                    DurationSec: song.DurationSec,
                    IsInstrumental: true);
            }

            string? primaryRaw = NormalizeNeteaseLyricBody(payload.Lrc?.Lyric);
            if (string.IsNullOrEmpty(primaryRaw)) return null;
            string? transRaw = NormalizeNeteaseLyricBody(payload.Tlyric?.Lyric);

            Lyrics parsed = LrcParser.Parse(primaryRaw);
            if (parsed.Lines.Count == 0) return null;

            var candidate = new CloudLyricsCandidate(
                Id: $"{CloudLyricsProviderIds.Netease}:{song.Id}",
                ProviderId: CloudLyricsProviderIds.Netease,
                SourceName: "NetEase",
                Title: song.Title,
                Artist: song.ArtistName,
                Album: song.AlbumName,
                DurationSec: song.DurationSec,
                IsInstrumental: false,
                SyncedLyrics: parsed.IsTimed ? primaryRaw : null,
                PlainLyrics: parsed.IsTimed ? null : primaryRaw,
                TranslatedLyrics: transRaw);
            return candidate.HasUsableContent ? candidate : null;
        }
    }

    private static void ApplyNeteaseHeaders(HttpRequestMessage req)
    {
        req.Headers.TryAddWithoutValidation("Referer", "https://music.163.com/");
        req.Headers.TryAddWithoutValidation("Origin", "https://music.163.com");
        req.Headers.TryAddWithoutValidation(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        req.Headers.TryAddWithoutValidation("Cookie", "appver=2.0.2; os=pc");
        req.Headers.TryAddWithoutValidation("Accept", "application/json");
    }

    private static int? ParseRetryAfter(HttpResponseMessage resp)
    {
        if (resp.Headers.TryGetValues("Retry-After", out IEnumerable<string>? values))
        {
            string? raw = values.FirstOrDefault()?.Trim();
            if (int.TryParse(raw, NumberStyles.Integer, CultureInfo.InvariantCulture, out int sec))
            {
                return sec;
            }
        }
        return null;
    }

    private static List<NeteaseSong> SelectTopSongs(List<NeteaseSong> songs, CloudLyricsQuery query, int limit)
    {
        return songs
            .Select((s, idx) => (Song: s, Index: idx, Score: PreScore(s, query)))
            .OrderByDescending(x => x.Score)
            .ThenBy(x => x.Index)
            .Take(limit)
            .Select(x => x.Song)
            .ToList();
    }

    private static double PreScore(NeteaseSong song, CloudLyricsQuery query)
    {
        double score = 0;
        if (!string.IsNullOrEmpty(query.TrimmedTitle))
        {
            score += LyricsNameMatcher.Similarity(song.Title, query.TrimmedTitle) * 50.0;
        }
        if (!string.IsNullOrEmpty(query.TrimmedArtist))
        {
            score += LyricsNameMatcher.Similarity(song.ArtistName, query.TrimmedArtist) * 30.0;
        }
        if (query.DurationSec is double qDur && qDur > 0 && song.DurationSec is double sDur && sDur > 0)
        {
            double diff = Math.Abs(sDur - qDur);
            if (diff <= 2) score += 20;
            else if (diff <= 6) score += 12;
            else if (diff <= 12) score += 4;
        }
        return score;
    }

    private static List<NeteaseSong> DecodeSearchSongs(string body)
    {
        try
        {
            NeteaseSearchResponse? resp = JsonSerializer.Deserialize<NeteaseSearchResponse>(body, JsonOptions);
            return resp?.Result?.Songs?
                .Select(s => s.ToNormalized())
                .Where(s => s is not null)
                .Cast<NeteaseSong>()
                .ToList() ?? [];
        }
        catch
        {
            return [];
        }
    }

    public static string? NormalizeNeteaseLyricBody(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        var lines = new List<string>();
        foreach (string rawLine in raw.Split(["\r\n", "\n", "\r"], StringSplitOptions.None))
        {
            string line = rawLine.Trim();
            if (line.Length == 0) continue;
            if (line.StartsWith('{') && line.EndsWith('}'))
            {
                try
                {
                    NeteaseJsonLine? obj = JsonSerializer.Deserialize<NeteaseJsonLine>(line, JsonOptions);
                    if (obj is not null)
                    {
                        string text = string.Concat((obj.C ?? []).Select(t => t.Tx ?? string.Empty)).Trim();
                        if (text.Length > 0)
                        {
                            double sec = (obj.T ?? 0.0) / 1000.0;
                            lines.Add($"{CloudLyricsFormatter.FormatLrcTimestamp(sec)}{text}");
                        }
                        continue;
                    }
                }
                catch
                {
                    // Fall through to preserving line as-is
                }
            }
            lines.Add(line);
        }
        string joined = string.Join("\n", lines).Trim();
        return joined.Length > 0 ? joined : null;
    }

    private sealed class NeteaseSearchResponse
    {
        [JsonPropertyName("code")] public int? Code { get; set; }
        [JsonPropertyName("result")] public NeteaseSearchResult? Result { get; set; }
    }

    private sealed class NeteaseSearchResult
    {
        [JsonPropertyName("songs")] public List<NeteaseSongDto>? Songs { get; set; }
    }

    private sealed class NeteaseArtistDto
    {
        [JsonPropertyName("name")] public string? Name { get; set; }
    }

    private sealed class NeteaseAlbumDto
    {
        [JsonPropertyName("name")] public string? Name { get; set; }
    }

    private sealed class NeteaseSongDto
    {
        [JsonPropertyName("id")] public long? Id { get; set; }
        [JsonPropertyName("name")] public string? Name { get; set; }
        [JsonPropertyName("artists")] public List<NeteaseArtistDto>? Artists { get; set; }
        [JsonPropertyName("ar")] public List<NeteaseArtistDto>? Ar { get; set; }
        [JsonPropertyName("album")] public NeteaseAlbumDto? Album { get; set; }
        [JsonPropertyName("al")] public NeteaseAlbumDto? Al { get; set; }
        [JsonPropertyName("duration")] public double? Duration { get; set; }
        [JsonPropertyName("dt")] public double? Dt { get; set; }

        public NeteaseSong? ToNormalized()
        {
            if (Id is not long id || string.IsNullOrWhiteSpace(Name)) return null;
            List<NeteaseArtistDto> artistSrc = Ar ?? Artists ?? [];
            string artistName = string.Join(" / ",
                artistSrc.Select(a => a.Name?.Trim()).Where(n => !string.IsNullOrEmpty(n)));
            string? albumName = (Al ?? Album)?.Name?.Trim();
            double? ms = Dt ?? Duration;
            double? sec = ms is > 0 ? ms.Value / 1000.0 : null;
            return new NeteaseSong(
                id,
                Name.Trim(),
                artistName,
                string.IsNullOrEmpty(albumName) ? null : albumName,
                sec);
        }
    }

    private sealed record NeteaseSong(long Id, string Title, string ArtistName, string? AlbumName, double? DurationSec);

    private sealed class NeteaseLyricResponse
    {
        [JsonPropertyName("nolyric")] public bool? NoLyric { get; set; }
        [JsonPropertyName("uncollected")] public bool? Uncollected { get; set; }
        [JsonPropertyName("lrc")] public NeteaseLyricSection? Lrc { get; set; }
        [JsonPropertyName("tlyric")] public NeteaseLyricSection? Tlyric { get; set; }
    }

    private sealed class NeteaseLyricSection
    {
        [JsonPropertyName("lyric")] public string? Lyric { get; set; }
    }

    private sealed class NeteaseJsonLine
    {
        [JsonPropertyName("t")] public double? T { get; set; }
        [JsonPropertyName("c")] public List<NeteaseJsonToken>? C { get; set; }
    }

    private sealed class NeteaseJsonToken
    {
        [JsonPropertyName("tx")] public string? Tx { get; set; }
    }
}
