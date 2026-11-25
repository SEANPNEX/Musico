using System.Collections.Concurrent;
using System.Runtime.CompilerServices;
using System.Security.Cryptography;
using System.Text;
using MusicoWindows.Extensions;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

/// <summary>
/// Resolves playable cloud streams for both root-backed tracks and root-less ephemeral search tracks.
/// Mounted root cookies retain precedence over extension-level saved cookies.
/// Caches resolved stream URLs keyed by track, provider, purpose, cookie context, proxy, and requested quality.
/// </summary>
public static class CloudStreamResolver
{
    private static readonly TimeSpan DefaultUrlCacheTtl = TimeSpan.FromMinutes(10);

    private readonly record struct StreamCacheKey(
        string ProviderId,
        string RemoteTrackId,
        string RelativePath,
        string RequestedQuality,
        CloudStreamPurpose Purpose,
        string CookieDigest,
        string ProxyUrl);

    private sealed record CachedStreamEntry(CloudAudioStream Stream, DateTimeOffset ExpiresAtUtc);

    private static readonly ConditionalWeakTable<CloudProviderRegistry, ConcurrentDictionary<StreamCacheKey, CachedStreamEntry>> CacheByRegistry = new();

    public static ICloudSourceProvider? GetProvider(
        CloudProviderRegistry registry,
        Track track,
        LibraryRoot? root = null)
    {
        ArgumentNullException.ThrowIfNull(registry);
        ArgumentNullException.ThrowIfNull(track);

        ICloudSourceProvider? provider = !string.IsNullOrWhiteSpace(track.CloudSource)
            ? registry.GetBySourceOrProviderId(track.CloudSource)
            : null;

        if (provider is null && root is not null)
        {
            provider = registry.GetProvider(root);
        }

        return provider;
    }

    public static string? ResolveRequestedQuality(
        ICloudSourceProvider? provider,
        Track track,
        LibraryRoot? root = null,
        ExtensionStore? extensions = null,
        CloudStreamPurpose purpose = CloudStreamPurpose.Stream,
        string? qualityOverride = null)
    {
        if (provider is null || provider.AvailableQualities.Count == 0)
        {
            return null;
        }

        string? MatchAvailable(string? candidate)
        {
            if (string.IsNullOrWhiteSpace(candidate)) return null;
            string trimmed = candidate.Trim();
            foreach (var q in provider.AvailableQualities)
            {
                if (string.Equals(q.Id.Trim(), trimmed, StringComparison.OrdinalIgnoreCase))
                {
                    return q.Id.Trim();
                }
            }
            return null;
        }

        if (!string.IsNullOrWhiteSpace(qualityOverride))
        {
            return MatchAvailable(qualityOverride) ?? qualityOverride.Trim();
        }

        string? extId = TryExtractExtensionId(provider.ProviderId)
                        ?? TryExtractExtensionId(track.CloudSource)
                        ?? Track.TryParseEphemeralExtensionId(track.Id);

        if (purpose == CloudStreamPurpose.Download)
        {
            return MatchAvailable(root?.DownloadQualityId)
                   ?? (extId is not null ? MatchAvailable(extensions?.GetDownloadQualityId(extId)) : null)
                   ?? MatchAvailable(provider.DefaultDownloadQualityId)
                   ?? provider.DefaultDownloadQualityId;
        }

        return MatchAvailable(root?.StreamQualityId)
               ?? (extId is not null ? MatchAvailable(extensions?.GetStreamQualityId(extId)) : null)
               ?? MatchAvailable(provider.DefaultStreamQualityId)
               ?? provider.DefaultStreamQualityId;
    }

    public static string? ResolveQualityLabel(ICloudSourceProvider? provider, string? qualityId)
    {
        if (provider is null || string.IsNullOrWhiteSpace(qualityId)) return null;
        string target = qualityId.Trim();
        var match = provider.AvailableQualities.FirstOrDefault(q =>
            string.Equals(q.Id.Trim(), target, StringComparison.OrdinalIgnoreCase));
        return match?.EffectiveBadge;
    }

    public static void ClearCache(CloudProviderRegistry? registry = null)
    {
        if (registry is not null)
        {
            if (CacheByRegistry.TryGetValue(registry, out var dict))
            {
                dict.Clear();
            }
            return;
        }

        foreach (var kvp in CacheByRegistry)
        {
            kvp.Value.Clear();
        }
    }

    public static async Task<CloudAudioStream> ResolveAsync(
        CloudProviderRegistry registry,
        Track track,
        LibraryRoot? root = null,
        ExtensionStore? extensions = null,
        bool forceRefresh = false,
        CancellationToken ct = default,
        CloudStreamPurpose purpose = CloudStreamPurpose.Stream,
        string? qualityOverride = null)
    {
        ArgumentNullException.ThrowIfNull(registry);
        ArgumentNullException.ThrowIfNull(track);

        if (string.IsNullOrWhiteSpace(track.RemoteTrackId))
        {
            throw new CloudServiceException(
                CloudErrorCode.NoPlayableStream,
                $"Cloud track '{track.Title}' is missing its remote track identifier.");
        }

        ICloudSourceProvider? provider = GetProvider(registry, track, root);

        if (provider is null || !provider.IsAvailable)
        {
            string label = track.CloudSource ?? root?.EffectiveProviderId ?? "cloud";
            throw new CloudServiceException(
                CloudErrorCode.ExtensionUnavailable,
                $"Required extension for '{label}' is disabled or not installed.");
        }

        string? cookie = ResolveEffectiveCookie(registry, track, root, provider, extensions);
        string? proxy = provider.SupportsProxy && !string.IsNullOrWhiteSpace(root?.ProxyUrl)
            ? root.ProxyUrl
            : null;
        string? requestedQuality = ResolveRequestedQuality(provider, track, root, extensions, purpose, qualityOverride);

        var cache = CacheByRegistry.GetOrCreateValue(registry);
        var cacheKey = new StreamCacheKey(
            ProviderId: provider.ProviderId,
            RemoteTrackId: track.RemoteTrackId,
            RelativePath: track.RelativePath ?? string.Empty,
            RequestedQuality: requestedQuality ?? string.Empty,
            Purpose: purpose,
            CookieDigest: ComputeShortDigest(cookie),
            ProxyUrl: proxy ?? string.Empty);

        if (!forceRefresh &&
            cache.TryGetValue(cacheKey, out var cached) &&
            cached.ExpiresAtUtc > DateTimeOffset.UtcNow)
        {
            return cached.Stream;
        }

        var resolved = await provider.ResolveAudioStreamAsync(
            track.RemoteTrackId,
            track.RelativePath,
            cookie,
            proxy,
            forceRefresh: forceRefresh,
            ct: ct,
            quality: requestedQuality,
            purpose: purpose).ConfigureAwait(false);

        cache[cacheKey] = new CachedStreamEntry(resolved, DateTimeOffset.UtcNow + DefaultUrlCacheTtl);
        return resolved;
    }

    private static string ComputeShortDigest(string? value)
    {
        if (string.IsNullOrEmpty(value)) return string.Empty;
        byte[] hash = SHA256.HashData(Encoding.UTF8.GetBytes(value));
        return Convert.ToHexString(hash.AsSpan(0, 8));
    }

    public static string? ResolveEffectiveCookie(
        CloudProviderRegistry registry,
        Track track,
        LibraryRoot? root,
        ICloudSourceProvider provider,
        ExtensionStore? extensions = null)
    {
        if (!provider.SupportsCookies)
        {
            return null;
        }

        if (!string.IsNullOrWhiteSpace(root?.Cookie))
        {
            return root.Cookie;
        }

        string? extId = TryExtractExtensionId(provider.ProviderId)
                        ?? TryExtractExtensionId(track.CloudSource)
                        ?? Track.TryParseEphemeralExtensionId(track.Id);
        if (string.IsNullOrWhiteSpace(extId))
        {
            return null;
        }

        string? extCookie = extensions?.GetCookie(extId) ?? registry.GetExtensionCookie(extId);
        return string.IsNullOrWhiteSpace(extCookie) ? null : extCookie;
    }

    public static string? TryExtractExtensionId(string? providerOrSourceId)
    {
        if (string.IsNullOrWhiteSpace(providerOrSourceId)) return null;
        string trimmed = providerOrSourceId.Trim();
        return trimmed.StartsWith("ext.", StringComparison.OrdinalIgnoreCase) && trimmed.Length > 4
            ? trimmed[4..]
            : null;
    }
}
