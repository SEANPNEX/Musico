using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Text.RegularExpressions;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

public static class CloudSources
{
    public const string YouTube = "youtube";
    public const string Bilibili = "bilibili";
    public const string GoogleDrive = "gdrive";
}

public sealed record CloudPlaylistItem(
    string RemoteTrackId,
    string Title,
    string Artist,
    string Album,
    double DurationSeconds,
    string? ArtworkUrl,
    int TrackNumber,
    string? FileExtension = null);

public sealed record CloudPlaylistResult(
    RootKind Kind,
    string RemoteId,
    string Title,
    string SourceUrl,
    IReadOnlyList<CloudPlaylistItem> Items);

public sealed record CloudAudioStream(
    string Url,
    string Extension,
    IReadOnlyDictionary<string, string>? Headers = null,
    long? ContentLength = null);

public static class CloudUrlClassifier
{
    private static readonly Regex HttpUrlRegex = new(
        @"https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly Regex SchemelessCloudUrlRegex = new(
        @"(?:[A-Za-z0-9-]+\.)*(?:youtube\.com|youtu\.be|bilibili\.com|b23\.tv|drive\.google\.com|drive\.usercontent\.google\.com)/[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+",
        RegexOptions.IgnoreCase | RegexOptions.Compiled);

    private static readonly char[] TrailingUrlPunctuation =
        [')', ']', '】', '」', '》', '。', ',', '.', '\'', '"', '）', '］', '”', '’', '>', ';', '；', '！', '、', '!', '?'];

    /// <summary>
    /// Extracts an http(s)://... URL (or scheme-less YouTube/Bilibili/Google Drive URL) from pasted share text,
    /// stripping surrounding title/description text and trailing punctuation while preserving bare IDs.
    /// </summary>
    public static string ExtractCloudUrl(string? input)
    {
        if (string.IsNullOrWhiteSpace(input))
        {
            return string.Empty;
        }

        var httpMatches = HttpUrlRegex.Matches(input);
        if (httpMatches.Count > 0)
        {
            string? firstCandidate = null;
            foreach (Match match in httpMatches)
            {
                string candidate = match.Value.TrimEnd(TrailingUrlPunctuation);
                if (string.IsNullOrEmpty(candidate) ||
                    candidate.Equals("http://", StringComparison.OrdinalIgnoreCase) ||
                    candidate.Equals("https://", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                firstCandidate ??= candidate;
                if (IsKnownCloudUrl(candidate))
                {
                    return candidate;
                }
            }

            if (firstCandidate is not null)
            {
                return firstCandidate;
            }
        }

        var schemelessMatch = SchemelessCloudUrlRegex.Match(input);
        if (schemelessMatch.Success)
        {
            string candidate = schemelessMatch.Value.TrimEnd(TrailingUrlPunctuation);
            if (!string.IsNullOrEmpty(candidate))
            {
                return candidate;
            }
        }

        return input.Trim();
    }

    private static bool IsKnownCloudUrl(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            return false;
        }

        string host = uri.Host.ToLowerInvariant();
        return host.Contains("youtube.com", StringComparison.Ordinal) ||
               host.Contains("youtu.be", StringComparison.Ordinal) ||
               host.Contains("bilibili.com", StringComparison.Ordinal) ||
               host.Contains("b23.tv", StringComparison.Ordinal) ||
               host.Equals("drive.google.com", StringComparison.Ordinal) ||
               host.EndsWith(".drive.google.com", StringComparison.Ordinal) ||
               host.Equals("drive.usercontent.google.com", StringComparison.Ordinal);
    }

    public static RootKind Classify(string input)
    {
        string extracted = ExtractCloudUrl(input);
        if (string.IsNullOrWhiteSpace(extracted))
        {
            throw new ArgumentException("Enter a YouTube, Bilibili, or Google Drive URL or playlist ID.", nameof(input));
        }

        string lower = extracted.ToLowerInvariant();

        if (lower.Contains("drive.google.com") || lower.Contains("drive.usercontent.google.com"))
        {
            return RootKind.CloudGoogleDrive;
        }

        if (lower.Contains("bilibili.com") || lower.Contains("b23.tv") ||
            extracted.StartsWith("BV", StringComparison.OrdinalIgnoreCase) && extracted.Length >= 12 && !extracted.Contains('/') ||
            Regex.IsMatch(extracted, @"^(av|ml)\d+$", RegexOptions.IgnoreCase))
        {
            return RootKind.CloudBilibili;
        }

        if (lower.Contains("youtube.com") || lower.Contains("youtu.be") ||
            lower.Contains("music.youtube.com") ||
            YouTubeClient.LooksLikeRawYouTubeId(extracted))
        {
            return RootKind.CloudYouTube;
        }

        throw new ArgumentException("Unsupported URL. Paste a YouTube, Bilibili, or Google Drive link.");
    }
}

internal static class CloudHttpHelper
{
    public static HttpClient CreateClient(string? proxyUrl, HttpMessageHandler? customHandler, TimeSpan? timeout = null)
    {
        if (customHandler is not null)
        {
            return new HttpClient(customHandler, disposeHandler: false)
            {
                Timeout = timeout ?? TimeSpan.FromSeconds(30)
            };
        }

        string? normalized = YouTubeClient.NormalizeProxyUrl(proxyUrl);
        var handler = new HttpClientHandler
        {
            AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate | DecompressionMethods.Brotli,
            AllowAutoRedirect = true
        };

        if (normalized is not null)
        {
            handler.Proxy = new WebProxy(normalized);
            handler.UseProxy = true;
        }

        return new HttpClient(handler, disposeHandler: true)
        {
            Timeout = timeout ?? TimeSpan.FromSeconds(30)
        };
    }

    public static string? NormalizeImageUrl(string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) return null;
        string trimmed = url.Trim();
        if (trimmed.StartsWith("//", StringComparison.Ordinal))
        {
            return "https:" + trimmed;
        }
        if (trimmed.StartsWith("http://", StringComparison.OrdinalIgnoreCase))
        {
            return "https://" + trimmed["http://".Length..];
        }
        return trimmed;
    }
}
