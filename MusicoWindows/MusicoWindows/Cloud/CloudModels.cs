using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using MusicoWindows.Extensions;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

public static class CloudSources
{
    public const string YouTube = "youtube";
    public const string Bilibili = "bilibili";
    public const string GoogleDrive = "gdrive";

    public static string Extension(string extensionId) =>
        $"ext.{(extensionId ?? string.Empty).Trim()}";
}

public enum CloudErrorCode
{
    InvalidUrl,
    AuthRequired,
    EmptyCollection,
    NoPlayableStream,
    RateLimited,
    NetworkError,
    PermissionDenied,
    ExtensionUnavailable,
    ExtensionRuntimeError
}

public sealed class CloudServiceException : Exception
{
    public CloudErrorCode Code { get; }
    public string WireCode { get; }

    public CloudServiceException(CloudErrorCode code, string message, Exception? innerException = null)
        : base(message, innerException)
    {
        Code = code;
        WireCode = ToWireCode(code);
    }

    public static string ToWireCode(CloudErrorCode code) => code switch
    {
        CloudErrorCode.InvalidUrl => "INVALID_URL",
        CloudErrorCode.AuthRequired => "AUTH_REQUIRED",
        CloudErrorCode.EmptyCollection => "EMPTY_COLLECTION",
        CloudErrorCode.NoPlayableStream => "NO_PLAYABLE_STREAM",
        CloudErrorCode.RateLimited => "RATE_LIMITED",
        CloudErrorCode.NetworkError => "NETWORK_ERROR",
        CloudErrorCode.PermissionDenied => "PERMISSION_DENIED",
        CloudErrorCode.ExtensionUnavailable => "EXTENSION_UNAVAILABLE",
        CloudErrorCode.ExtensionRuntimeError => "EXTENSION_RUNTIME_ERROR",
        _ => "EXTENSION_RUNTIME_ERROR"
    };

    public static CloudErrorCode? ParseWireCode(string? raw) => raw?.Trim().ToUpperInvariant() switch
    {
        "INVALID_URL" => CloudErrorCode.InvalidUrl,
        "AUTH_REQUIRED" => CloudErrorCode.AuthRequired,
        "EMPTY_COLLECTION" => CloudErrorCode.EmptyCollection,
        "NO_PLAYABLE_STREAM" => CloudErrorCode.NoPlayableStream,
        "RATE_LIMITED" => CloudErrorCode.RateLimited,
        "NETWORK_ERROR" => CloudErrorCode.NetworkError,
        "PERMISSION_DENIED" => CloudErrorCode.PermissionDenied,
        "EXTENSION_UNAVAILABLE" => CloudErrorCode.ExtensionUnavailable,
        "EXTENSION_RUNTIME_ERROR" => CloudErrorCode.ExtensionRuntimeError,
        _ => null
    };
}

public sealed record CloudPlaylistItem(
    string RemoteTrackId,
    string Title,
    string Artist,
    string Album,
    double DurationSeconds,
    string? ArtworkUrl,
    int TrackNumber,
    string? FileExtension = null,
    string? RelativePath = null);

public sealed record CloudPlaylistResult(
    RootKind Kind,
    string RemoteId,
    string Title,
    string SourceUrl,
    IReadOnlyList<CloudPlaylistItem> Items,
    string? ProviderId = null,
    IReadOnlyList<string>? DiscoveredSubfolderPaths = null)
{
    [JsonIgnore]
    public string? ResolvedProviderId =>
        !string.IsNullOrWhiteSpace(ProviderId)
            ? ProviderId
            : Kind.BuiltinProviderId();
}

public enum CloudStreamPurpose
{
    Stream,
    Download
}

public static class CloudStreamPurposeExtensions
{
    public static string ToWireString(this CloudStreamPurpose purpose) => purpose switch
    {
        CloudStreamPurpose.Download => "download",
        _ => "stream"
    };
}

public sealed record CloudAudioStream(
    string Url,
    string Extension,
    IReadOnlyDictionary<string, string>? Headers = null,
    long? ContentLength = null,
    IReadOnlyList<string>? FallbackUrls = null,
    string? ActualQuality = null,
    string? QualityLabel = null,
    int? BitrateKbps = null);

public sealed class ExtensionHttpRequest
{
    public string? Method { get; set; }
    public string? Url { get; set; }
    public Dictionary<string, string>? Headers { get; set; }
    public string? Body { get; set; }
    public bool FollowRedirects { get; set; } = true;
    public int TimeoutMs { get; set; } = 15000;
    public bool ReturnBytes { get; set; }
}

public sealed class ExtensionHttpResponse
{
    public int Status { get; set; }
    public string FinalUrl { get; set; } = string.Empty;
    public Dictionary<string, string> Headers { get; set; } = new(StringComparer.Ordinal);
    public List<string> SetCookies { get; set; } = [];
    public string Text { get; set; } = string.Empty;
    public string? BytesBase64 { get; set; }
}

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
    public static string ExtractCloudUrl(string? input) =>
        ExtractCloudUrl(input, registry: null);

    public static string ExtractCloudUrl(string? input, CloudProviderRegistry? registry)
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
                if (IsKnownCloudUrl(candidate) ||
                    (registry is not null && registry.MatchesAnyExtensionPattern(candidate)))
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

    public static RootKind Classify(string input) => Classify(input, registry: null);

    public static RootKind Classify(string input, CloudProviderRegistry? registry)
    {
        string extracted = ExtractCloudUrl(input, registry);
        if (string.IsNullOrWhiteSpace(extracted))
        {
            throw new ArgumentException("Enter a cloud playlist, folder, or extension URL.", nameof(input));
        }

        if (TryClassifyBuiltinUrlOrToken(extracted, includeRawYouTubeId: false) is { } builtinKind)
        {
            return builtinKind;
        }

        if (registry is not null)
        {
            var extMatch = Task.Run(() => registry.TryMatchExtensionAsync(input, extracted, CancellationToken.None))
                .GetAwaiter()
                .GetResult();
            if (extMatch is not null)
            {
                return extMatch.Value.Provider.RootKind;
            }
        }

        if (TryClassifyBuiltinUrlOrToken(extracted, includeRawYouTubeId: true) is { } rawYtKind)
        {
            return rawYtKind;
        }

        throw new ArgumentException("Unsupported URL. Paste a YouTube, Bilibili, Google Drive, or enabled extension link.");
    }

    public static async Task<(ICloudSourceProvider Provider, CloudCanHandleResult Match)> ClassifyAsync(
        string input,
        CloudProviderRegistry registry,
        string? preferredProviderId = null,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(registry);

        string extracted = ExtractCloudUrl(input, registry);
        if (string.IsNullOrWhiteSpace(extracted))
        {
            throw new ArgumentException("Enter a cloud playlist, folder, or extension URL.", nameof(input));
        }

        if (!string.IsNullOrWhiteSpace(preferredProviderId))
        {
            var provider = registry.GetById(preferredProviderId);
            if (provider is null || !provider.IsAvailable)
            {
                throw new CloudServiceException(
                    CloudErrorCode.ExtensionUnavailable,
                    $"Cloud provider '{preferredProviderId}' is disabled or not installed.");
            }

            var match = await provider.TryMatchAsync(input, extracted, ct).ConfigureAwait(false);
            if (!match.Matched)
            {
                throw new CloudServiceException(
                    CloudErrorCode.InvalidUrl,
                    $"The URL is not supported by {provider.DisplayName}.");
            }

            return (provider, match);
        }

        if (TryClassifyBuiltinUrlOrToken(extracted, includeRawYouTubeId: false) is { } builtinKind &&
            registry.GetById(builtinKind.BuiltinProviderId()) is { } builtinProvider)
        {
            var match = await builtinProvider.TryMatchAsync(input, extracted, ct).ConfigureAwait(false);
            return (builtinProvider, match.Matched ? match : new CloudCanHandleResult(true, extracted, builtinProvider.TryExtractRemoteId(extracted)));
        }

        var extMatch = await registry.TryMatchExtensionAsync(input, extracted, ct).ConfigureAwait(false);
        if (extMatch is not null)
        {
            return extMatch.Value;
        }

        if (TryClassifyBuiltinUrlOrToken(extracted, includeRawYouTubeId: true) is { } ytKind &&
            registry.GetById(ytKind.BuiltinProviderId()) is { } ytProvider)
        {
            var match = await ytProvider.TryMatchAsync(input, extracted, ct).ConfigureAwait(false);
            return (ytProvider, match.Matched ? match : new CloudCanHandleResult(true, extracted, ytProvider.TryExtractRemoteId(extracted)));
        }

        throw new ArgumentException("Unsupported URL. Paste a YouTube, Bilibili, Google Drive, or enabled extension link.");
    }

    internal static RootKind? TryClassifyBuiltinUrlOrToken(string extracted, bool includeRawYouTubeId)
    {
        string lower = extracted.ToLowerInvariant();

        if (lower.Contains("drive.google.com") || lower.Contains("drive.usercontent.google.com"))
        {
            return RootKind.CloudGoogleDrive;
        }

        if (lower.Contains("bilibili.com") || lower.Contains("b23.tv") ||
            (extracted.StartsWith("BV", StringComparison.OrdinalIgnoreCase) && extracted.Length >= 12 && !extracted.Contains('/')) ||
            Regex.IsMatch(extracted, @"^(av|ml)\d+$", RegexOptions.IgnoreCase))
        {
            return RootKind.CloudBilibili;
        }

        if (lower.Contains("youtube.com") || lower.Contains("youtu.be") ||
            lower.Contains("music.youtube.com") ||
            (includeRawYouTubeId && YouTubeClient.LooksLikeRawYouTubeId(extracted)))
        {
            return RootKind.CloudYouTube;
        }

        return null;
    }
}

internal static class CloudHttpHelper
{
    public const int MaxExtensionRedirectHops = 10;
    public const int MaxExtensionBodyBytes = 8 * 1024 * 1024;
    public const int InlineBase64ThresholdBytes = 1024 * 1024;

    public static void ConfigureProxy(HttpClientHandler handler, string? proxyUrl)
    {
        string? normalized = YouTubeClient.NormalizeProxyUrl(proxyUrl);
        if (normalized is not null)
        {
            handler.Proxy = new WebProxy(normalized);
            handler.UseProxy = true;
        }
    }

    public static HttpClient CreateClient(string? proxyUrl, HttpMessageHandler? customHandler, TimeSpan? timeout = null)
    {
        if (customHandler is not null)
        {
            return new HttpClient(customHandler, disposeHandler: false)
            {
                Timeout = timeout ?? TimeSpan.FromSeconds(30)
            };
        }

        var handler = new HttpClientHandler
        {
            AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate | DecompressionMethods.Brotli,
            AllowAutoRedirect = true
        };
        ConfigureProxy(handler, proxyUrl);

        return new HttpClient(handler, disposeHandler: true)
        {
            Timeout = timeout ?? TimeSpan.FromSeconds(30)
        };
    }

    public static HttpClient CreateManualRedirectClient(string? proxyUrl, HttpMessageHandler? customHandler, TimeSpan? timeout = null)
    {
        if (customHandler is not null)
        {
            return new HttpClient(customHandler, disposeHandler: false)
            {
                Timeout = timeout ?? TimeSpan.FromSeconds(30)
            };
        }

        var handler = new HttpClientHandler
        {
            AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate | DecompressionMethods.Brotli,
            AllowAutoRedirect = false
        };
        ConfigureProxy(handler, proxyUrl);

        return new HttpClient(handler, disposeHandler: true)
        {
            Timeout = timeout ?? TimeSpan.FromSeconds(30)
        };
    }

    public static async Task<ExtensionHttpResponse> SendExtensionRequestAsync(
        ExtensionHttpRequest request,
        IReadOnlyList<string> allowedHosts,
        string? proxyUrl = null,
        HttpMessageHandler? customHandler = null,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(request);

        int effectiveTimeoutMs = request.TimeoutMs > 0 ? Math.Min(request.TimeoutMs, 120_000) : 15_000;
        var timeoutSpan = TimeSpan.FromMilliseconds(effectiveTimeoutMs);
        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        linkedCts.CancelAfter(timeoutSpan);

        using var http = CreateManualRedirectClient(proxyUrl, customHandler, Timeout.InfiniteTimeSpan);

        string currentMethod = string.IsNullOrWhiteSpace(request.Method)
            ? "GET"
            : request.Method.Trim().ToUpperInvariant();
        string currentUrl = request.Url?.Trim() ?? string.Empty;
        string? currentBody = request.Body;
        var workingHeaders = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (request.Headers is not null)
        {
            foreach (var kvp in request.Headers)
            {
                if (!string.IsNullOrWhiteSpace(kvp.Key) && kvp.Value is not null)
                {
                    workingHeaders[kvp.Key.Trim()] = kvp.Value;
                }
            }
        }

        for (int hop = 0; hop <= MaxExtensionRedirectHops; hop++)
        {
            if (!HostAllowlistMatcher.IsAllowed(currentUrl, allowedHosts, out var currentUri) || currentUri is null)
            {
                throw new CloudServiceException(
                    CloudErrorCode.PermissionDenied,
                    $"Host blocked by permissions.hosts: {currentUrl}");
            }

            using var msg = new HttpRequestMessage(new HttpMethod(currentMethod), currentUri);
            if (currentBody is not null && !string.Equals(currentMethod, "GET", StringComparison.OrdinalIgnoreCase) &&
                !string.Equals(currentMethod, "HEAD", StringComparison.OrdinalIgnoreCase))
            {
                msg.Content = new ByteArrayContent(Encoding.UTF8.GetBytes(currentBody));
            }

            foreach (var kvp in workingHeaders)
            {
                if (kvp.Key.StartsWith("Content-", StringComparison.OrdinalIgnoreCase) && msg.Content is not null)
                {
                    msg.Content.Headers.Remove(kvp.Key);
                    msg.Content.Headers.TryAddWithoutValidation(kvp.Key, kvp.Value);
                }
                else
                {
                    msg.Headers.TryAddWithoutValidation(kvp.Key, kvp.Value);
                }
            }

            HttpResponseMessage resp;
            try
            {
                resp = await http.SendAsync(msg, HttpCompletionOption.ResponseHeadersRead, linkedCts.Token)
                    .ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (OperationCanceledException ex)
            {
                throw new CloudServiceException(CloudErrorCode.NetworkError, "Extension HTTP request timed out.", ex);
            }
            catch (HttpRequestException ex)
            {
                throw new CloudServiceException(CloudErrorCode.NetworkError, $"HTTP request failed: {ex.Message}", ex);
            }

            using (resp)
            {
                var lowerHeaders = ExtractLowercaseHeaders(resp);
                int statusCode = (int)resp.StatusCode;

                if (request.FollowRedirects &&
                    statusCode is 301 or 302 or 303 or 307 or 308 &&
                    lowerHeaders.TryGetValue("location", out string? locationRaw) &&
                    !string.IsNullOrWhiteSpace(locationRaw))
                {
                    if (hop == MaxExtensionRedirectHops)
                    {
                        throw new CloudServiceException(CloudErrorCode.NetworkError, "Too many HTTP redirects.");
                    }

                    if (!Uri.TryCreate(currentUri, locationRaw.Trim(), out var nextUri))
                    {
                        throw new CloudServiceException(CloudErrorCode.NetworkError, $"Invalid redirect location '{locationRaw}'.");
                    }

                    if (!HostAllowlistMatcher.IsAllowed(nextUri.AbsoluteUri, allowedHosts, out _))
                    {
                        throw new CloudServiceException(
                            CloudErrorCode.PermissionDenied,
                            $"Redirect target blocked by permissions.hosts: {nextUri.AbsoluteUri}");
                    }

                    if (!string.Equals(currentUri.Host, nextUri.Host, StringComparison.OrdinalIgnoreCase))
                    {
                        workingHeaders.Remove("Cookie");
                        workingHeaders.Remove("Authorization");
                    }

                    if (statusCode is 301 or 302 or 303)
                    {
                        currentMethod = "GET";
                        currentBody = null;
                        foreach (string key in workingHeaders.Keys
                                     .Where(k => k.StartsWith("Content-", StringComparison.OrdinalIgnoreCase))
                                     .ToList())
                        {
                            workingHeaders.Remove(key);
                        }
                    }

                    currentUrl = nextUri.AbsoluteUri;
                    continue;
                }

                byte[] cappedBytes;
                try
                {
                    cappedBytes = await ReadBoundedBytesAsync(resp.Content, MaxExtensionBodyBytes, linkedCts.Token)
                        .ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    throw;
                }
                catch (OperationCanceledException ex)
                {
                    throw new CloudServiceException(CloudErrorCode.NetworkError, "Extension HTTP response read timed out.", ex);
                }
                catch (Exception ex) when (ex is HttpRequestException or IOException)
                {
                    throw new CloudServiceException(CloudErrorCode.NetworkError, $"Failed to read HTTP response: {ex.Message}", ex);
                }

                return new ExtensionHttpResponse
                {
                    Status = statusCode,
                    FinalUrl = currentUri.AbsoluteUri,
                    Headers = lowerHeaders,
                    SetCookies = ExtractSetCookies(resp),
                    Text = Encoding.UTF8.GetString(cappedBytes),
                    BytesBase64 = request.ReturnBytes || cappedBytes.Length <= InlineBase64ThresholdBytes
                        ? Convert.ToBase64String(cappedBytes)
                        : null
                };
            }
        }

        throw new CloudServiceException(CloudErrorCode.NetworkError, "Too many HTTP redirects.");
    }

    private static List<string> ExtractSetCookies(HttpResponseMessage resp)
    {
        var list = new List<string>();
        if (resp.Headers.TryGetValues("Set-Cookie", out var headerValues))
        {
            foreach (string value in headerValues)
            {
                if (!string.IsNullOrWhiteSpace(value))
                {
                    list.Add(value);
                }
            }
        }
        if (resp.Content?.Headers.TryGetValues("Set-Cookie", out var contentValues) == true)
        {
            foreach (string value in contentValues)
            {
                if (!string.IsNullOrWhiteSpace(value))
                {
                    list.Add(value);
                }
            }
        }
        return list;
    }

    private static Dictionary<string, string> ExtractLowercaseHeaders(HttpResponseMessage resp)
    {
        var headers = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var kvp in resp.Headers)
        {
            headers[kvp.Key.ToLowerInvariant()] = string.Join(", ", kvp.Value);
        }
        foreach (var kvp in resp.Content.Headers)
        {
            headers[kvp.Key.ToLowerInvariant()] = string.Join(", ", kvp.Value);
        }
        if (resp.Headers.Location is Uri loc && !headers.ContainsKey("location"))
        {
            headers["location"] = loc.OriginalString;
        }
        return headers;
    }

    private static async Task<byte[]> ReadBoundedBytesAsync(HttpContent content, int maxBytes, CancellationToken ct)
    {
        await using var stream = await content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var ms = new MemoryStream();
        byte[] buffer = new byte[16384];
        int totalRead = 0;
        while (totalRead < maxBytes)
        {
            int toRead = Math.Min(buffer.Length, maxBytes - totalRead);
            int read = await stream.ReadAsync(buffer.AsMemory(0, toRead), ct).ConfigureAwait(false);
            if (read <= 0) break;
            ms.Write(buffer, 0, read);
            totalRead += read;
        }
        return ms.ToArray();
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
