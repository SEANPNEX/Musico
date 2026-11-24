using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using MusicoWindows.Cloud;
using MusicoWindows.Models;

namespace MusicoWindows.Extensions;

public sealed class JsExtensionCloudProvider : ICloudSourceProvider
{
    public const int MaxPagesPerNode = 200;
    public const int MaxTreeDepth = 16;
    public const int MaxTreeFolders = 2000;
    public const int FolderWorkerConcurrency = 4;

    private sealed record TreeFolderWorkItem(
        string FolderId,
        string Name,
        List<string> PathSegments,
        string RelativeFolderPath,
        int Depth,
        long Sequence,
        JsonElement? Extra);

    private sealed record CollectedTreeTrack(
        int Depth,
        long FolderSequence,
        int ItemOrder,
        CloudPlaylistItem Item);

    private readonly InstalledExtension _extension;
    private readonly ExtensionStore _store;
    private readonly JintExtensionRuntime _runtime;
    private readonly List<Regex> _urlRegexes;

    public JsExtensionCloudProvider(
        InstalledExtension extension,
        ExtensionStore store,
        JintExtensionRuntime? runtime = null)
    {
        _extension = extension ?? throw new ArgumentNullException(nameof(extension));
        _store = store ?? throw new ArgumentNullException(nameof(store));
        _runtime = runtime ?? store.Runtime;

        _urlRegexes = (_extension.Manifest.UrlPatterns ?? [])
            .Select(pat => new Regex(pat, RegexOptions.IgnoreCase | RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1)))
            .ToList();
    }

    public ExtensionManifest Manifest => _extension.Manifest;
    public string ProviderId => _extension.ProviderId;
    public string DisplayName => _extension.Manifest.Name;
    public string BadgeLabel => _extension.Manifest.EffectiveBadgeLabel;
    public RootKind RootKind => RootKind.CloudExtension;
    public string CloudSourceKey => _extension.ProviderId;
    public bool SupportsCookies => _extension.Manifest.SupportsCookieAuth;
    public bool SupportsProxy => _extension.Manifest.HasCapability(ExtensionCapability.Proxy);
    public bool SupportsCollectionImport =>
        _extension.Manifest.HasCapability(ExtensionCapability.Playlist) ||
        _extension.Manifest.HasCapability(ExtensionCapability.Tree);
    public bool UsesFlatCloudListing => !_extension.Manifest.HasCapability(ExtensionCapability.Tree);
    public bool SupportsProgressiveTree => _extension.Manifest.HasCapability(ExtensionCapability.Tree);
    public bool IsAvailable => _store.GetById(_extension.Id)?.IsEnabled == true;

    public bool MatchesUrlPattern(string? candidate)
    {
        if (string.IsNullOrWhiteSpace(candidate)) return false;
        string trimmed = candidate.Trim();
        foreach (var regex in _urlRegexes)
        {
            try
            {
                if (regex.IsMatch(trimmed)) return true;
            }
            catch (RegexMatchTimeoutException)
            {
                // Ignore slow regex match on pathological input.
            }
        }
        return false;
    }

    public async Task<CloudCanHandleResult> TryMatchAsync(
        string rawInput,
        string extractedUrl,
        CancellationToken ct = default)
    {
        EnsureAvailable();
        if (!MatchesUrlPattern(extractedUrl) && !MatchesUrlPattern(rawInput))
        {
            return new CloudCanHandleResult(false);
        }

        string source = _store.ReadScriptSource(_extension);
        var res = await _runtime.CanHandleAsync(
            Manifest,
            source,
            new ExtCanHandleInput
            {
                RawText = rawInput ?? string.Empty,
                ExtractedUrl = extractedUrl ?? string.Empty
            },
            _store,
            ct).ConfigureAwait(false);

        return new CloudCanHandleResult(res.Matched, res.NormalizedUrl, res.RemoteId, res.Kind);
    }

    public string? TryExtractRemoteId(string url)
    {
        if (string.IsNullOrWhiteSpace(url)) return null;
        try
        {
            var match = Task.Run(() => TryMatchAsync(url, url, CancellationToken.None)).GetAwaiter().GetResult();
            return match.Matched ? match.RemoteId : null;
        }
        catch
        {
            return null;
        }
    }

    public async Task<CloudPlaylistResult> FetchPlaylistAsync(
        string sourceUrl,
        string? cookie,
        string? proxyUrl,
        CancellationToken ct = default)
    {
        EnsureAvailable();
        string? effectiveProxy = SupportsProxy ? proxyUrl : null;

        if (SupportsProgressiveTree)
        {
            using var session = await StartProgressiveFetchAsync(sourceUrl, cookie, effectiveProxy, ct)
                .ConfigureAwait(false);
            if (session is null)
            {
                throw new CloudServiceException(CloudErrorCode.EmptyCollection, "No tracks were found.");
            }
            if (!session.HasMoreSubfolders)
            {
                return session.RootResult;
            }
            return await session.ContinueRemainingAsync(onBatch: null, ct).ConfigureAwait(false);
        }

        string scriptSource = _store.ReadScriptSource(_extension);
        string? effectiveCookie = ResolveEffectiveCookie(cookie);
        var (remoteId, title, items, _) = await FetchRootCollectionPagesAsync(
            scriptSource,
            sourceUrl,
            effectiveCookie,
            effectiveProxy,
            ct).ConfigureAwait(false);

        return new CloudPlaylistResult(
            Kind: RootKind.CloudExtension,
            RemoteId: remoteId,
            Title: title,
            SourceUrl: sourceUrl,
            Items: items,
            ProviderId: ProviderId);
    }

    private string? ResolveEffectiveCookie(string? rootOrCallerCookie)
    {
        if (!SupportsCookies) return null;
        if (!string.IsNullOrWhiteSpace(rootOrCallerCookie)) return rootOrCallerCookie;
        return _store.GetCookie(_extension.Id);
    }

    public async Task<CloudTreeSession?> StartProgressiveFetchAsync(
        string sourceUrl,
        string? cookie,
        string? proxyUrl,
        CancellationToken ct = default)
    {
        EnsureAvailable();
        if (!SupportsProgressiveTree)
        {
            return null;
        }

        string scriptSource = _store.ReadScriptSource(_extension);
        string? effectiveCookie = ResolveEffectiveCookie(cookie);
        string? effectiveProxy = SupportsProxy ? proxyUrl : null;

        var (remoteId, rootTitle, rootItems, rootSubfolders) = await FetchRootCollectionPagesAsync(
            scriptSource,
            sourceUrl,
            effectiveCookie,
            effectiveProxy,
            ct).ConfigureAwait(false);

        var gate = new object();
        var pendingQueue = new LinkedList<TreeFolderWorkItem>();
        var visitedKeys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var priorityPaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var collected = new List<CollectedTreeTrack>();
        long nextSeq = 1;
        int foldersVisited = 1;
        int knownFoldersTotal = 1;

        for (int i = 0; i < rootItems.Count; i++)
        {
            collected.Add(new CollectedTreeTrack(0, 0, i, rootItems[i]));
        }

        var initialDiscoveredPaths = new List<string>();
        EnqueueSubfoldersLocked(
            subfolders: rootSubfolders,
            parentPathSegments: [],
            depth: 1,
            pendingQueue: pendingQueue,
            visitedKeys: visitedKeys,
            discoveredPaths: initialDiscoveredPaths,
            ref nextSeq);

        knownFoldersTotal += initialDiscoveredPaths.Count;

        var rootResult = new CloudPlaylistResult(
            Kind: RootKind.CloudExtension,
            RemoteId: remoteId,
            Title: rootTitle,
            SourceUrl: sourceUrl,
            Items: rootItems,
            ProviderId: ProviderId,
            DiscoveredSubfolderPaths: initialDiscoveredPaths);

        var handle = new CloudTreeTraversalHandle(path =>
        {
            string norm = LibraryRoot.NormalizeFolderPath(path);
            if (norm.Length == 0) return;
            lock (gate)
            {
                priorityPaths.Add(norm);
            }
        });

        bool hasMore = pendingQueue.Count > 0 && foldersVisited < MaxTreeFolders;
        if (!hasMore)
        {
            return new CloudTreeSession(
                rootResult: rootResult,
                discoveredSubfolderPaths: initialDiscoveredPaths,
                hasMoreSubfolders: false,
                traversalHandle: handle,
                continueRemaining: (_, _) => Task.FromResult(rootResult));
        }

        async Task<CloudPlaylistResult> ContinueRemainingAsync(
            Action<CloudTreeFolderBatch> onBatch,
            CancellationToken continueCt)
        {
            var batchEmitLock = new SemaphoreSlim(1, 1);

            async Task WorkerLoopAsync()
            {
                while (true)
                {
                    continueCt.ThrowIfCancellationRequested();
                    TreeFolderWorkItem? work = null;
                    lock (gate)
                    {
                        if (foldersVisited >= MaxTreeFolders || pendingQueue.Count == 0)
                        {
                            return;
                        }
                        work = DequeueNextFolderLocked(pendingQueue, priorityPaths);
                        if (work is null)
                        {
                            return;
                        }
                        foldersVisited++;
                    }

                    (List<CloudPlaylistItem> FolderItems, List<ExtSubfolderDto> ChildSubfolders) fetched;
                    try
                    {
                        fetched = await FetchFolderPagesAsync(
                            scriptSource,
                            work,
                            rootTitle,
                            effectiveCookie,
                            effectiveProxy,
                            continueCt).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException)
                    {
                        throw;
                    }
                    catch
                    {
                        continue;
                    }

                    var discoveredChildren = new List<string>();
                    int visitedSnap;
                    int totalSnap;

                    lock (gate)
                    {
                        for (int i = 0; i < fetched.FolderItems.Count; i++)
                        {
                            collected.Add(new CollectedTreeTrack(work.Depth, work.Sequence, i, fetched.FolderItems[i]));
                        }

                        if (work.Depth < MaxTreeDepth)
                        {
                            EnqueueSubfoldersLocked(
                                subfolders: fetched.ChildSubfolders,
                                parentPathSegments: work.PathSegments,
                                depth: work.Depth + 1,
                                pendingQueue: pendingQueue,
                                visitedKeys: visitedKeys,
                                discoveredPaths: discoveredChildren,
                                ref nextSeq);
                        }

                        knownFoldersTotal += discoveredChildren.Count;
                        visitedSnap = foldersVisited;
                        totalSnap = knownFoldersTotal;
                    }

                    if (fetched.FolderItems.Count > 0 || discoveredChildren.Count > 0)
                    {
                        await batchEmitLock.WaitAsync(continueCt).ConfigureAwait(false);
                        try
                        {
                            onBatch(new CloudTreeFolderBatch(
                                FolderRelativePath: work.RelativeFolderPath,
                                FolderId: work.FolderId,
                                Items: fetched.FolderItems,
                                DiscoveredSubfolderPaths: discoveredChildren,
                                FoldersVisited: visitedSnap,
                                KnownFoldersTotal: totalSnap,
                                IsComplete: false));
                        }
                        finally
                        {
                            batchEmitLock.Release();
                        }
                    }
                }
            }

            while (true)
            {
                continueCt.ThrowIfCancellationRequested();
                int remainingBudget;
                int queuedCount;
                lock (gate)
                {
                    remainingBudget = MaxTreeFolders - foldersVisited;
                    queuedCount = pendingQueue.Count;
                }
                if (remainingBudget <= 0 || queuedCount <= 0)
                {
                    break;
                }

                int launchCount = Math.Min(FolderWorkerConcurrency, Math.Min(remainingBudget, queuedCount));
                var workers = new Task[launchCount];
                for (int i = 0; i < launchCount; i++)
                {
                    workers[i] = Task.Run(WorkerLoopAsync, continueCt);
                }
                await Task.WhenAll(workers).ConfigureAwait(false);
            }

            List<CloudPlaylistItem> orderedItems;
            int finalVisited;
            int finalTotal;
            List<string> allDiscovered;
            lock (gate)
            {
                orderedItems = collected
                    .OrderBy(c => c.Depth)
                    .ThenBy(c => c.FolderSequence)
                    .ThenBy(c => c.ItemOrder)
                    .Select((c, idx) => c.Item with { TrackNumber = c.Item.TrackNumber > 0 ? c.Item.TrackNumber : idx + 1 })
                    .ToList();
                finalVisited = foldersVisited;
                finalTotal = knownFoldersTotal;
                allDiscovered = visitedKeys.ToList();
            }

            await batchEmitLock.WaitAsync(continueCt).ConfigureAwait(false);
            try
            {
                onBatch(new CloudTreeFolderBatch(
                    FolderRelativePath: string.Empty,
                    FolderId: remoteId,
                    Items: Array.Empty<CloudPlaylistItem>(),
                    DiscoveredSubfolderPaths: Array.Empty<string>(),
                    FoldersVisited: finalVisited,
                    KnownFoldersTotal: finalTotal,
                    IsComplete: true));
            }
            finally
            {
                batchEmitLock.Release();
            }

            return new CloudPlaylistResult(
                Kind: RootKind.CloudExtension,
                RemoteId: remoteId,
                Title: rootTitle,
                SourceUrl: sourceUrl,
                Items: orderedItems,
                ProviderId: ProviderId,
                DiscoveredSubfolderPaths: allDiscovered);
        }

        return new CloudTreeSession(
            rootResult: rootResult,
            discoveredSubfolderPaths: initialDiscoveredPaths,
            hasMoreSubfolders: true,
            traversalHandle: handle,
            continueRemaining: ContinueRemainingAsync);
    }

    public async Task<CloudAudioStream> ResolveAudioStreamAsync(
        string remoteTrackId,
        string? relativePath,
        string? cookie,
        string? proxyUrl,
        bool forceRefresh = false,
        CancellationToken ct = default)
    {
        EnsureAvailable();
        if (!Manifest.HasCapability(ExtensionCapability.Stream))
        {
            throw new CloudServiceException(
                CloudErrorCode.NoPlayableStream,
                $"Extension '{Manifest.Name}' does not declare the 'stream' capability.");
        }

        string? effectiveCookie = ResolveEffectiveCookie(cookie);
        string scriptSource = _store.ReadScriptSource(_extension);
        var res = await _runtime.ResolveStreamAsync(
            Manifest,
            scriptSource,
            new ExtResolveStreamRequest
            {
                RemoteTrackId = remoteTrackId,
                RelativePath = relativePath,
                Cookie = effectiveCookie,
                ForceRefresh = forceRefresh
            },
            SupportsProxy ? proxyUrl : null,
            _store,
            ct).ConfigureAwait(false);

        string ext = string.IsNullOrWhiteSpace(res.FileExtension)
            ? InferExtensionFromUrl(res.Url) ?? "mp3"
            : res.FileExtension.Trim().TrimStart('.');

        return new CloudAudioStream(
            Url: res.Url!,
            Extension: ext,
            Headers: res.Headers is { Count: > 0 } ? res.Headers : null,
            ContentLength: res.ContentLength is > 0 ? res.ContentLength : null,
            FallbackUrls: res.FallbackUrls is { Count: > 0 } ? res.FallbackUrls : null);
    }

    public void ConfigureArtworkRequest(HttpRequestMessage request) { }

    private void EnsureAvailable()
    {
        if (!IsAvailable)
        {
            throw new CloudServiceException(
                CloudErrorCode.ExtensionUnavailable,
                $"Extension '{Manifest.Name}' ({Manifest.Id}) is disabled or uninstalled.");
        }
    }

    private async Task<(string RemoteId, string Title, List<CloudPlaylistItem> Items, List<ExtSubfolderDto> Subfolders)>
        FetchRootCollectionPagesAsync(
            string scriptSource,
            string sourceUrl,
            string? cookie,
            string? proxyUrl,
            CancellationToken ct)
    {
        var items = new List<CloudPlaylistItem>();
        var subfolders = new List<ExtSubfolderDto>();
        var seenCursors = new HashSet<string>(StringComparer.Ordinal);
        string? cursor = null;
        string? remoteId = null;
        string? title = null;

        for (int page = 0; page < MaxPagesPerNode; page++)
        {
            ct.ThrowIfCancellationRequested();
            var pageRes = await _runtime.FetchCollectionAsync(
                Manifest,
                scriptSource,
                new ExtFetchCollectionRequest
                {
                    Url = sourceUrl,
                    Cookie = cookie,
                    Cursor = cursor
                },
                proxyUrl,
                _store,
                ct).ConfigureAwait(false);

            if (string.IsNullOrWhiteSpace(remoteId) && !string.IsNullOrWhiteSpace(pageRes.RemoteId))
            {
                remoteId = pageRes.RemoteId.Trim();
            }
            if (string.IsNullOrWhiteSpace(title) && !string.IsNullOrWhiteSpace(pageRes.Title))
            {
                title = pageRes.Title.Trim();
            }

            if (pageRes.Tracks is not null)
            {
                foreach (var track in pageRes.Tracks)
                {
                    if (MapTrack(track, folderRelPath: string.Empty, title ?? Manifest.Name, items.Count + 1) is { } mapped)
                    {
                        items.Add(mapped);
                    }
                }
            }

            if (pageRes.Subfolders is not null)
            {
                subfolders.AddRange(pageRes.Subfolders);
            }

            if (string.IsNullOrWhiteSpace(pageRes.NextCursor) || !seenCursors.Add(pageRes.NextCursor))
            {
                break;
            }
            cursor = pageRes.NextCursor;
        }

        return (
            remoteId ?? sourceUrl,
            string.IsNullOrWhiteSpace(title) ? Manifest.Name : title!,
            items,
            subfolders);
    }

    private async Task<(List<CloudPlaylistItem> Items, List<ExtSubfolderDto> Subfolders)> FetchFolderPagesAsync(
        string scriptSource,
        TreeFolderWorkItem work,
        string fallbackAlbum,
        string? cookie,
        string? proxyUrl,
        CancellationToken ct)
    {
        var items = new List<CloudPlaylistItem>();
        var subfolders = new List<ExtSubfolderDto>();
        var seenCursors = new HashSet<string>(StringComparer.Ordinal);
        string? cursor = null;

        for (int page = 0; page < MaxPagesPerNode; page++)
        {
            ct.ThrowIfCancellationRequested();
            var pageRes = await _runtime.FetchFolderAsync(
                Manifest,
                scriptSource,
                new ExtFetchFolderRequest
                {
                    FolderId = work.FolderId,
                    PathSegments = work.PathSegments,
                    Depth = work.Depth,
                    Cookie = cookie,
                    Cursor = cursor,
                    Extra = work.Extra
                },
                proxyUrl,
                _store,
                ct).ConfigureAwait(false);

            if (pageRes.Tracks is not null)
            {
                foreach (var track in pageRes.Tracks)
                {
                    if (MapTrack(track, work.RelativeFolderPath, fallbackAlbum, items.Count + 1) is { } mapped)
                    {
                        items.Add(mapped);
                    }
                }
            }

            if (pageRes.Subfolders is not null)
            {
                subfolders.AddRange(pageRes.Subfolders);
            }

            if (string.IsNullOrWhiteSpace(pageRes.NextCursor) || !seenCursors.Add(pageRes.NextCursor))
            {
                break;
            }
            cursor = pageRes.NextCursor;
        }

        return (items, subfolders);
    }

    private CloudPlaylistItem? MapTrack(
        ExtTrackDto dto,
        string folderRelPath,
        string fallbackAlbum,
        int fallbackTrackNumber)
    {
        if (string.IsNullOrWhiteSpace(dto.RemoteTrackId)) return null;
        string remoteTrackId = dto.RemoteTrackId.Trim();
        string title = string.IsNullOrWhiteSpace(dto.Title) ? remoteTrackId : dto.Title.Trim();
        string artist = string.IsNullOrWhiteSpace(dto.Artist) ? Manifest.Name : dto.Artist.Trim();
        string album = string.IsNullOrWhiteSpace(dto.Album) ? fallbackAlbum : dto.Album.Trim();
        int trackNo = dto.TrackNumber is double tn && tn > 0 ? (int)Math.Round(tn) : fallbackTrackNumber;
        double duration = dto.DurationSec is double d && d >= 0 && !double.IsNaN(d) ? d : 0;

        string? rawRel = dto.RelativePath?.Replace('\\', '/').Trim().TrimStart('/');
        string? resolvedRelPath;
        if (string.IsNullOrWhiteSpace(rawRel))
        {
            string safeTitle = GoogleDriveClient.SanitizePathSegment(title, remoteTrackId);
            resolvedRelPath = string.IsNullOrEmpty(folderRelPath) ? $"{safeTitle}.m4a" : $"{folderRelPath}/{safeTitle}.m4a";
        }
        else if (!string.IsNullOrEmpty(folderRelPath) &&
                 !rawRel.StartsWith(folderRelPath + "/", StringComparison.OrdinalIgnoreCase))
        {
            resolvedRelPath = $"{folderRelPath}/{rawRel}";
        }
        else
        {
            resolvedRelPath = rawRel;
        }

        string? ext = Path.GetExtension(resolvedRelPath);
        if (string.IsNullOrWhiteSpace(ext)) ext = null;

        return new CloudPlaylistItem(
            RemoteTrackId: remoteTrackId,
            Title: title,
            Artist: artist,
            Album: album,
            DurationSeconds: duration,
            ArtworkUrl: CloudHttpHelper.NormalizeImageUrl(dto.ArtworkUrl),
            TrackNumber: trackNo,
            FileExtension: ext,
            RelativePath: resolvedRelPath);
    }

    private static void EnqueueSubfoldersLocked(
        IEnumerable<ExtSubfolderDto> subfolders,
        IReadOnlyList<string> parentPathSegments,
        int depth,
        LinkedList<TreeFolderWorkItem> pendingQueue,
        HashSet<string> visitedKeys,
        List<string> discoveredPaths,
        ref long nextSeq)
    {
        foreach (var sub in subfolders)
        {
            if (string.IsNullOrWhiteSpace(sub.Id)) continue;
            string folderId = sub.Id.Trim();

            List<string> segments;
            if (sub.PathSegments is { Count: > 0 })
            {
                segments = sub.PathSegments
                    .Select(s => GoogleDriveClient.SanitizePathSegment(s, "Folder"))
                    .Where(s => s.Length > 0)
                    .ToList();
            }
            else
            {
                string safeName = GoogleDriveClient.SanitizePathSegment(sub.Name, folderId);
                segments = [.. parentPathSegments, safeName];
            }

            if (segments.Count == 0) continue;

            string relPath = LibraryRoot.NormalizeFolderPath(string.Join('/', segments));
            string dedupeKey = $"{folderId}\u001F{relPath}";
            if (!visitedKeys.Add(dedupeKey)) continue;

            discoveredPaths.Add(relPath);
            pendingQueue.AddLast(new TreeFolderWorkItem(
                FolderId: folderId,
                Name: sub.Name?.Trim() ?? segments[^1],
                PathSegments: segments,
                RelativeFolderPath: relPath,
                Depth: depth,
                Sequence: nextSeq++,
                Extra: sub.Extra));
        }
    }

    private static TreeFolderWorkItem? DequeueNextFolderLocked(
        LinkedList<TreeFolderWorkItem> pendingQueue,
        HashSet<string> priorityPaths)
    {
        if (pendingQueue.Count == 0) return null;

        if (priorityPaths.Count > 0)
        {
            LinkedListNode<TreeFolderWorkItem>? exactMatch = null;
            LinkedListNode<TreeFolderWorkItem>? childMatch = null;
            LinkedListNode<TreeFolderWorkItem>? ancestorMatch = null;

            for (var node = pendingQueue.First; node is not null; node = node.Next)
            {
                string folderPath = node.Value.RelativeFolderPath;
                if (string.IsNullOrEmpty(folderPath)) continue;

                foreach (string target in priorityPaths)
                {
                    if (string.Equals(folderPath, target, StringComparison.OrdinalIgnoreCase))
                    {
                        exactMatch ??= node;
                        break;
                    }
                    if (folderPath.StartsWith(target + "/", StringComparison.OrdinalIgnoreCase))
                    {
                        childMatch ??= node;
                    }
                    else if (target.StartsWith(folderPath + "/", StringComparison.OrdinalIgnoreCase))
                    {
                        ancestorMatch ??= node;
                    }
                }

                if (exactMatch is not null) break;
            }

            var chosen = exactMatch ?? childMatch ?? ancestorMatch;
            if (chosen is not null)
            {
                pendingQueue.Remove(chosen);
                return chosen.Value;
            }
        }

        var first = pendingQueue.First!;
        pendingQueue.RemoveFirst();
        return first.Value;
    }

    private static string? InferExtensionFromUrl(string? url)
    {
        if (string.IsNullOrWhiteSpace(url) || !Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            return null;
        }
        string ext = Path.GetExtension(uri.AbsolutePath).TrimStart('.');
        return string.IsNullOrWhiteSpace(ext) || ext.Length > 8 ? null : ext;
    }
}
