using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using MusicoWindows.Cloud;
using MusicoWindows.Models;

namespace MusicoWindows.Extensions;

public sealed record CloudCanHandleResult(
    bool Matched,
    string? NormalizedUrl = null,
    string? RemoteId = null,
    string? Kind = null);

public sealed record CloudTreeFolderBatch(
    string FolderRelativePath,
    string FolderId,
    IReadOnlyList<CloudPlaylistItem> Items,
    IReadOnlyList<string> DiscoveredSubfolderPaths,
    int FoldersVisited,
    int KnownFoldersTotal,
    bool IsComplete);

public sealed class CloudTreeTraversalHandle
{
    private readonly Action<string> _prioritize;

    public CloudTreeTraversalHandle(Action<string> prioritize)
    {
        _prioritize = prioritize ?? (_ => { });
    }

    public void PrioritizeFolder(string relativeFolderPath) => _prioritize(relativeFolderPath);
}

public sealed class CloudTreeSession : IDisposable
{
    private readonly Func<Action<CloudTreeFolderBatch>, CancellationToken, Task<CloudPlaylistResult>> _continueRemaining;
    private readonly Action? _disposeAction;
    private int _disposed;

    public CloudTreeSession(
        CloudPlaylistResult rootResult,
        IReadOnlyList<string> discoveredSubfolderPaths,
        bool hasMoreSubfolders,
        CloudTreeTraversalHandle traversalHandle,
        Func<Action<CloudTreeFolderBatch>, CancellationToken, Task<CloudPlaylistResult>> continueRemaining,
        Action? disposeAction = null)
    {
        RootResult = rootResult;
        DiscoveredSubfolderPaths = discoveredSubfolderPaths;
        HasMoreSubfolders = hasMoreSubfolders;
        TraversalHandle = traversalHandle;
        _continueRemaining = continueRemaining;
        _disposeAction = disposeAction;
    }

    public CloudPlaylistResult RootResult { get; }
    public IReadOnlyList<string> DiscoveredSubfolderPaths { get; }
    public bool HasMoreSubfolders { get; }
    public CloudTreeTraversalHandle TraversalHandle { get; }

    public async Task<CloudPlaylistResult> ContinueRemainingAsync(
        Action<CloudTreeFolderBatch>? onBatch = null,
        CancellationToken ct = default)
    {
        try
        {
            return await _continueRemaining(onBatch ?? (_ => { }), ct).ConfigureAwait(false);
        }
        finally
        {
            Dispose();
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) == 0)
        {
            _disposeAction?.Invoke();
        }
    }
}

public interface ICloudSourceProvider
{
    string ProviderId { get; }
    string DisplayName { get; }
    string BadgeLabel { get; }
    RootKind RootKind { get; }
    string CloudSourceKey { get; }
    bool SupportsCookies { get; }
    bool SupportsProxy { get; }
    bool UsesFlatCloudListing { get; }
    bool SupportsProgressiveTree { get; }
    bool SupportsCollectionImport { get; }
    bool IsAvailable { get; }
    IReadOnlyList<ExtensionQualityOption> AvailableQualities { get; }
    string? DefaultStreamQualityId { get; }
    string? DefaultDownloadQualityId { get; }

    bool MatchesUrlPattern(string? candidate);

    Task<CloudCanHandleResult> TryMatchAsync(string rawInput, string extractedUrl, CancellationToken ct = default);

    string? TryExtractRemoteId(string url);

    Task<CloudPlaylistResult> FetchPlaylistAsync(
        string sourceUrl,
        string? cookie,
        string? proxyUrl,
        CancellationToken ct = default);

    Task<CloudTreeSession?> StartProgressiveFetchAsync(
        string sourceUrl,
        string? cookie,
        string? proxyUrl,
        CancellationToken ct = default);

    Task<CloudAudioStream> ResolveAudioStreamAsync(
        string remoteTrackId,
        string? relativePath,
        string? cookie,
        string? proxyUrl,
        bool forceRefresh = false,
        CancellationToken ct = default,
        string? quality = null,
        CloudStreamPurpose purpose = CloudStreamPurpose.Stream);

    void ConfigureArtworkRequest(HttpRequestMessage request);
}

internal sealed class GoogleDriveCloudProvider : ICloudSourceProvider
{
    private readonly GoogleDriveClient _client;

    public GoogleDriveCloudProvider(GoogleDriveClient client)
    {
        _client = client;
    }

    public string ProviderId => CloudProviderRegistry.BuiltinGoogleDriveId;
    public string DisplayName => "Google Drive";
    public string BadgeLabel => "Google Drive";
    public RootKind RootKind => RootKind.CloudGoogleDrive;
    public string CloudSourceKey => CloudSources.GoogleDrive;
    public bool SupportsCookies => true;
    public bool SupportsProxy => true;
    public bool UsesFlatCloudListing => false;
    public bool SupportsProgressiveTree => true;
    public bool SupportsCollectionImport => true;
    public bool IsAvailable => true;
    public IReadOnlyList<ExtensionQualityOption> AvailableQualities => Array.Empty<ExtensionQualityOption>();
    public string? DefaultStreamQualityId => null;
    public string? DefaultDownloadQualityId => null;

    public bool MatchesUrlPattern(string? candidate)
    {
        if (string.IsNullOrWhiteSpace(candidate)) return false;
        try
        {
            return CloudUrlClassifier.TryClassifyBuiltinUrlOrToken(candidate.Trim()) == RootKind.CloudGoogleDrive;
        }
        catch
        {
            return false;
        }
    }

    public Task<CloudCanHandleResult> TryMatchAsync(string rawInput, string extractedUrl, CancellationToken ct = default)
    {
        try
        {
            var target = GoogleDriveClient.ParseInput(extractedUrl);
            return Task.FromResult(new CloudCanHandleResult(
                Matched: true,
                NormalizedUrl: extractedUrl,
                RemoteId: target.Id,
                Kind: target.Kind == GoogleDriveTargetKind.File ? "track" : "folder"));
        }
        catch
        {
            return Task.FromResult(new CloudCanHandleResult(false));
        }
    }

    public string? TryExtractRemoteId(string url)
    {
        try { return GoogleDriveClient.ParseInput(url).Id; }
        catch { return null; }
    }

    public async Task<CloudPlaylistResult> FetchPlaylistAsync(
        string sourceUrl,
        string? cookie,
        string? proxyUrl,
        CancellationToken ct = default)
    {
        var result = await _client.FetchPlaylistAsync(sourceUrl, cookie, proxyUrl, ct).ConfigureAwait(false);
        return result with { ProviderId = ProviderId };
    }

    public async Task<CloudTreeSession?> StartProgressiveFetchAsync(
        string sourceUrl,
        string? cookie,
        string? proxyUrl,
        CancellationToken ct = default)
    {
        var session = await _client.StartProgressiveFolderFetchAsync(sourceUrl, cookie, proxyUrl, ct).ConfigureAwait(false);
        var rootResult = session.RootResult with { ProviderId = ProviderId };
        var handle = new CloudTreeTraversalHandle(path => session.TraversalHandle.PrioritizeFolder(path));

        return new CloudTreeSession(
            rootResult: rootResult,
            discoveredSubfolderPaths: session.DiscoveredSubfolderPaths,
            hasMoreSubfolders: session.HasMoreSubfolders,
            traversalHandle: handle,
            continueRemaining: async (onBatch, continueCt) =>
            {
                var finalResult = await session.ContinueRemainingAsync(batch =>
                {
                    onBatch(new CloudTreeFolderBatch(
                        FolderRelativePath: batch.FolderRelativePath,
                        FolderId: batch.FolderId,
                        Items: batch.Items,
                        DiscoveredSubfolderPaths: batch.DiscoveredSubfolderPaths,
                        FoldersVisited: batch.FoldersVisited,
                        KnownFoldersTotal: batch.KnownFoldersTotal,
                        IsComplete: batch.IsComplete));
                }, continueCt).ConfigureAwait(false);
                return finalResult with { ProviderId = ProviderId };
            },
            disposeAction: session.Dispose);
    }

    public Task<CloudAudioStream> ResolveAudioStreamAsync(
        string remoteTrackId,
        string? relativePath,
        string? cookie,
        string? proxyUrl,
        bool forceRefresh = false,
        CancellationToken ct = default,
        string? quality = null,
        CloudStreamPurpose purpose = CloudStreamPurpose.Stream) =>
        _client.ResolveAudioStreamAsync(remoteTrackId, cookie, proxyUrl, ct);

    public void ConfigureArtworkRequest(HttpRequestMessage request) { }
}

public sealed class CloudProviderRegistry
{
    public const string BuiltinYouTubeId = "builtin.youtube";
    public const string BuiltinBilibiliId = "builtin.bilibili";
    public const string BuiltinGoogleDriveId = "builtin.gdrive";
    public const string LegacyYouTubeExtensionId = "ext.youtube-search";
    public const string LegacyBilibiliExtensionId = "ext.bilibili-search";

    private readonly object _lock = new();
    private readonly List<ICloudSourceProvider> _builtIns;
    private readonly List<ICloudSourceProvider> _extensions = new();

    public CloudProviderRegistry(GoogleDriveClient? googleDriveClient = null)
    {
        GoogleDrive = googleDriveClient ?? new GoogleDriveClient();

        _builtIns =
        [
            new GoogleDriveCloudProvider(GoogleDrive)
        ];
    }

    public GoogleDriveClient GoogleDrive { get; }
    internal Func<string, string?>? ExtensionCookieResolver { get; set; }

    public string? GetExtensionCookie(string? extensionId) =>
        string.IsNullOrWhiteSpace(extensionId) ? null : ExtensionCookieResolver?.Invoke(extensionId.Trim());

    public event Action? Changed;

    public IReadOnlyList<ICloudSourceProvider> AllProviders
    {
        get
        {
            lock (_lock)
            {
                return [.. _builtIns, .. _extensions];
            }
        }
    }

    public IReadOnlyList<ICloudSourceProvider> AvailableProviders
    {
        get
        {
            lock (_lock)
            {
                return [.. _builtIns.Where(p => p.IsAvailable), .. _extensions.Where(p => p.IsAvailable)];
            }
        }
    }

    public IReadOnlyList<ICloudSourceProvider> ExtensionProviders
    {
        get
        {
            lock (_lock)
            {
                return _extensions.ToList();
            }
        }
    }

    public ICloudSourceProvider? GetById(string? providerId)
    {
        if (string.IsNullOrWhiteSpace(providerId)) return null;
        string target = providerId.Trim();
        lock (_lock)
        {
            foreach (var p in _builtIns)
            {
                if (string.Equals(p.ProviderId, target, StringComparison.OrdinalIgnoreCase))
                {
                    return p;
                }
            }
            foreach (var p in _extensions)
            {
                if (string.Equals(p.ProviderId, target, StringComparison.OrdinalIgnoreCase))
                {
                    return p;
                }
            }

            string? legacyExtId = target.ToLowerInvariant() switch
            {
                BuiltinYouTubeId => LegacyYouTubeExtensionId,
                BuiltinBilibiliId => LegacyBilibiliExtensionId,
                _ => null
            };
            if (legacyExtId is not null)
            {
                foreach (var p in _extensions)
                {
                    if (p.IsAvailable && string.Equals(p.ProviderId, legacyExtId, StringComparison.OrdinalIgnoreCase))
                    {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    public ICloudSourceProvider? GetBySourceOrProviderId(string? sourceOrProviderId)
    {
        if (string.IsNullOrWhiteSpace(sourceOrProviderId)) return null;
        string trimmed = sourceOrProviderId.Trim();
        string mapped = trimmed.ToLowerInvariant() switch
        {
            CloudSources.YouTube => BuiltinYouTubeId,
            CloudSources.Bilibili => BuiltinBilibiliId,
            CloudSources.GoogleDrive => BuiltinGoogleDriveId,
            _ => trimmed
        };
        return GetById(mapped);
    }

    public IReadOnlyList<ICloudSourceProvider> GetCompatibleProviders(string? url)
    {
        if (string.IsNullOrWhiteSpace(url))
        {
            return Array.Empty<ICloudSourceProvider>();
        }

        string extracted = CloudUrlClassifier.ExtractCloudUrl(url, this);
        var candidates = new List<ICloudSourceProvider>();
        foreach (var provider in AvailableProviders)
        {
            if (!provider.SupportsCollectionImport) continue;
            if (provider.MatchesUrlPattern(extracted) || provider.MatchesUrlPattern(url))
            {
                candidates.Add(provider);
            }
        }
        return candidates;
    }

    public ICloudSourceProvider? GetBestCompatibleProvider(string? url) =>
        GetCompatibleProviders(url).FirstOrDefault();

    public ICloudSourceProvider? GetProvider(LibraryRoot root)
    {
        ArgumentNullException.ThrowIfNull(root);
        var provider = GetById(root.EffectiveProviderId);
        if (provider is null &&
            (root.EffectiveCloudKind is RootKind.CloudYouTube or RootKind.CloudBilibili ||
             string.Equals(root.EffectiveProviderId, BuiltinYouTubeId, StringComparison.OrdinalIgnoreCase) ||
             string.Equals(root.EffectiveProviderId, BuiltinBilibiliId, StringComparison.OrdinalIgnoreCase)) &&
            !string.IsNullOrWhiteSpace(root.SourceUrl))
        {
            provider = GetBestCompatibleProvider(root.SourceUrl);
        }
        return provider;
    }

    public ICloudSourceProvider? GetProvider(CloudTrackingInfo tracking)
    {
        ArgumentNullException.ThrowIfNull(tracking);
        var provider = GetById(tracking.ResolvedProviderId);
        if (provider is null &&
            tracking.CloudKind is RootKind.CloudYouTube or RootKind.CloudBilibili &&
            !string.IsNullOrWhiteSpace(tracking.SourceUrl))
        {
            provider = GetBestCompatibleProvider(tracking.SourceUrl);
        }
        return provider;
    }

    public bool MatchesAnyExtensionPattern(string? candidate)
    {
        if (string.IsNullOrWhiteSpace(candidate)) return false;
        List<ICloudSourceProvider> extSnapshot;
        lock (_lock)
        {
            extSnapshot = _extensions.Where(e => e.IsAvailable && e.SupportsCollectionImport).ToList();
        }
        return extSnapshot.Any(p => p.MatchesUrlPattern(candidate));
    }

    public async Task<(ICloudSourceProvider Provider, CloudCanHandleResult Match)?> TryMatchExtensionAsync(
        string rawInput,
        string extractedUrl,
        CancellationToken ct = default)
    {
        List<ICloudSourceProvider> extSnapshot;
        lock (_lock)
        {
            extSnapshot = _extensions.Where(e => e.IsAvailable && e.SupportsCollectionImport).ToList();
        }

        foreach (var provider in extSnapshot)
        {
            if (!provider.MatchesUrlPattern(extractedUrl) && !provider.MatchesUrlPattern(rawInput))
            {
                continue;
            }

            try
            {
                var match = await provider.TryMatchAsync(rawInput, extractedUrl, ct).ConfigureAwait(false);
                if (match.Matched)
                {
                    return (provider, match);
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch
            {
                // Auto-detect skips extensions whose canHandle fails on candidate input.
            }
        }

        return null;
    }

    public void RegisterExtensionProvider(ICloudSourceProvider provider)
    {
        ArgumentNullException.ThrowIfNull(provider);
        lock (_lock)
        {
            _extensions.RemoveAll(p => string.Equals(p.ProviderId, provider.ProviderId, StringComparison.OrdinalIgnoreCase));
            _extensions.Add(provider);
        }
        Changed?.Invoke();
    }

    public bool UnregisterExtensionProvider(string providerId)
    {
        if (string.IsNullOrWhiteSpace(providerId)) return false;
        bool removed;
        lock (_lock)
        {
            removed = _extensions.RemoveAll(p => string.Equals(p.ProviderId, providerId, StringComparison.OrdinalIgnoreCase)) > 0;
        }
        if (removed)
        {
            Changed?.Invoke();
        }
        return removed;
    }

    public void SyncExtensionProviders(IEnumerable<ICloudSourceProvider> providers)
    {
        ArgumentNullException.ThrowIfNull(providers);
        var next = providers.Where(p => p is not null).ToList();
        lock (_lock)
        {
            _extensions.Clear();
            _extensions.AddRange(next);
        }
        Changed?.Invoke();
    }

    public static string ResolveDisplayLabel(
        CloudProviderRegistry? registry,
        string? providerId,
        RootKind? fallbackKind = null)
    {
        if (!string.IsNullOrWhiteSpace(providerId))
        {
            if (registry?.GetById(providerId) is { } provider)
            {
                return provider.BadgeLabel;
            }

            string? builtinName = providerId.Trim().ToLowerInvariant() switch
            {
                BuiltinYouTubeId => "YouTube",
                BuiltinBilibiliId => "Bilibili",
                BuiltinGoogleDriveId => "Google Drive",
                _ => null
            };
            if (builtinName is not null) return builtinName;

            if (providerId.StartsWith("ext.", StringComparison.OrdinalIgnoreCase) && providerId.Length > 4)
            {
                return providerId[4..];
            }
            return providerId;
        }

        return fallbackKind switch
        {
            RootKind.CloudBilibili => "Bilibili",
            RootKind.CloudGoogleDrive => "Google Drive",
            RootKind.CloudYouTube => "YouTube",
            _ => "Extension"
        };
    }
}
