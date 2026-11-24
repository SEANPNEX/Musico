using MusicoWindows.Extensions;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

/// <summary>
/// Resolves playable cloud streams for both root-backed tracks and root-less ephemeral search tracks.
/// Mounted root cookies retain precedence over extension-level saved cookies.
/// </summary>
public static class CloudStreamResolver
{
    public static Task<CloudAudioStream> ResolveAsync(
        CloudProviderRegistry registry,
        Track track,
        LibraryRoot? root = null,
        ExtensionStore? extensions = null,
        bool forceRefresh = false,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(registry);
        ArgumentNullException.ThrowIfNull(track);

        if (string.IsNullOrWhiteSpace(track.RemoteTrackId))
        {
            throw new CloudServiceException(
                CloudErrorCode.NoPlayableStream,
                $"Cloud track '{track.Title}' is missing its remote track identifier.");
        }

        ICloudSourceProvider? provider = !string.IsNullOrWhiteSpace(track.CloudSource)
            ? registry.GetBySourceOrProviderId(track.CloudSource)
            : null;

        if (provider is null && root is not null)
        {
            provider = registry.GetProvider(root);
        }

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

        return provider.ResolveAudioStreamAsync(
            track.RemoteTrackId,
            track.RelativePath,
            cookie,
            proxy,
            forceRefresh: forceRefresh,
            ct: ct);
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
