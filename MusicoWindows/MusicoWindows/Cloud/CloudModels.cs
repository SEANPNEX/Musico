using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

public static class CloudSources
{
    public const string YouTube = "youtube";
    public const string Bilibili = "bilibili";
}

public sealed record CloudPlaylistItem(
    string RemoteTrackId,
    string Title,
    string Artist,
    string Album,
    double DurationSeconds,
    string? ArtworkUrl,
    int TrackNumber);

public sealed record CloudPlaylistResult(
    RootKind Kind,
    string RemoteId,
    string Title,
    string SourceUrl,
    IReadOnlyList<CloudPlaylistItem> Items);

public sealed record CloudAudioStream(
    string Url,
    string Extension,
    IReadOnlyDictionary<string, string>? Headers = null);

public static class CloudUrlClassifier
{
    public static RootKind Classify(string input)
    {
        if (string.IsNullOrWhiteSpace(input))
        {
            throw new ArgumentException("Enter a YouTube or Bilibili URL or playlist ID.", nameof(input));
        }

        string trimmed = input.Trim();
        string lower = trimmed.ToLowerInvariant();

        if (lower.Contains("bilibili.com") || lower.Contains("b23.tv") ||
            trimmed.StartsWith("BV", StringComparison.OrdinalIgnoreCase) && trimmed.Length >= 12 && !trimmed.Contains('/') ||
            System.Text.RegularExpressions.Regex.IsMatch(trimmed, @"^(av|ml)\d+$", System.Text.RegularExpressions.RegexOptions.IgnoreCase))
        {
            return RootKind.CloudBilibili;
        }

        if (lower.Contains("youtube.com") || lower.Contains("youtu.be") ||
            lower.Contains("music.youtube.com") ||
            YouTubeClient.LooksLikeRawYouTubeId(trimmed))
        {
            return RootKind.CloudYouTube;
        }

        throw new ArgumentException("Unsupported URL. Paste a YouTube or Bilibili playlist or video link.");
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
