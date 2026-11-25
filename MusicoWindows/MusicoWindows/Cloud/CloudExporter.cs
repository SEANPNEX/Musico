using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

public sealed record CloudFolderManifest(
    string SourceUrl,
    string? RemoteId,
    string CloudKind,
    DateTimeOffset LastSyncedUtc,
    Dictionary<string, string> Tracks,
    string? ProviderId = null);

public sealed record CloudUpdateResult(
    int AddedCount,
    int SkippedCount,
    int FailedCount,
    int TotalRemoteCount,
    IReadOnlyList<string> Errors);

public sealed record CloudExportProgress(
    int Completed,
    int Total,
    string CurrentTrackTitle,
    int FailedCount = 0,
    long BytesDownloaded = 0,
    long? TotalBytes = null,
    double BytesPerSecond = 0)
{
    public double? OverallFraction
    {
        get
        {
            if (Total <= 0) return null;
            if (Completed >= Total) return 1.0;
            if (Total == 1 && TotalBytes is not > 0) return null;
            double trackFraction = TotalBytes is long totalB && totalB > 0
                ? Math.Clamp((double)BytesDownloaded / totalB, 0.0, 1.0)
                : 0.0;
            return Math.Clamp((Completed + trackFraction) / Total, 0.0, 1.0);
        }
    }
}

public sealed record CloudExportResult(
    int SavedCount,
    int FailedCount,
    string DestinationFolder,
    IReadOnlyList<string> Errors);

/// <summary>
/// Exports cloud tracks and playlists to local folders, reusing CloudAudioCache
/// and writing best-effort ID3/MP4 tags + cover art via TagLibSharp.
/// </summary>
public sealed class CloudExporter
{
    public const int MaxFileNameLength = 120;
    public const string ManifestFileName = ".musico-cloud.json";

    private static readonly JsonSerializerOptions ManifestJsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true
    };

    private static readonly HashSet<string> ReservedDeviceNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };

    private readonly CloudAudioCache _cloudCache;
    private readonly YouTubeClient _youtube;
    private readonly BilibiliClient _bilibili;
    private readonly GoogleDriveClient _googleDrive;
    private readonly CloudProviderRegistry _providerRegistry;
    private readonly ArtworkCache? _artworkCache;
    private readonly ExtensionStore? _extensions;
    private readonly CloudThumbnailCache? _thumbnailCache;

    public CloudExporter(
        CloudAudioCache cloudCache,
        YouTubeClient youtubeClient,
        BilibiliClient bilibiliClient,
        ArtworkCache? artworkCache = null,
        GoogleDriveClient? googleDriveClient = null,
        CloudProviderRegistry? providerRegistry = null,
        ExtensionStore? extensions = null,
        CloudThumbnailCache? thumbnailCache = null)
    {
        _cloudCache = cloudCache ?? throw new ArgumentNullException(nameof(cloudCache));
        _youtube = youtubeClient ?? throw new ArgumentNullException(nameof(youtubeClient));
        _bilibili = bilibiliClient ?? throw new ArgumentNullException(nameof(bilibiliClient));
        _artworkCache = artworkCache;
        _googleDrive = googleDriveClient ?? new GoogleDriveClient();
        _providerRegistry = providerRegistry ?? new CloudProviderRegistry(_youtube, _bilibili, _googleDrive);
        _extensions = extensions;
        _thumbnailCache = thumbnailCache ?? new CloudThumbnailCache(maxEntries: 16);
    }

    public CloudExporter(
        CloudAudioCache cloudCache,
        CloudProviderRegistry providerRegistry,
        ArtworkCache? artworkCache = null,
        ExtensionStore? extensions = null,
        CloudThumbnailCache? thumbnailCache = null)
    {
        _cloudCache = cloudCache ?? throw new ArgumentNullException(nameof(cloudCache));
        _providerRegistry = providerRegistry ?? throw new ArgumentNullException(nameof(providerRegistry));
        _youtube = _providerRegistry.YouTube;
        _bilibili = _providerRegistry.Bilibili;
        _googleDrive = _providerRegistry.GoogleDrive;
        _artworkCache = artworkCache;
        _extensions = extensions;
        _thumbnailCache = thumbnailCache ?? new CloudThumbnailCache(maxEntries: 16);
    }

    public static string SanitizeFileName(string? raw, string fallback = "Untitled")
    {
        string safeFallback = SanitizeFileNameCore(fallback) ?? "Untitled";
        return SanitizeFileNameCore(raw) ?? safeFallback;
    }

    private static string? SanitizeFileNameCore(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return null;
        }

        var sb = new StringBuilder(raw.Length);
        foreach (char c in raw)
        {
            if (!IsInvalidWindowsFileNameChar(c))
            {
                sb.Append(c);
            }
        }

        string cleaned = sb.ToString().Trim().TrimEnd('.', ' ');
        if (cleaned.Length == 0)
        {
            return null;
        }

        if (cleaned.Length > MaxFileNameLength)
        {
            cleaned = cleaned[..MaxFileNameLength].TrimEnd('.', ' ');
            if (cleaned.Length == 0)
            {
                return null;
            }
        }

        int dotIdx = cleaned.IndexOf('.');
        string stem = dotIdx >= 0 ? cleaned[..dotIdx] : cleaned;
        if (ReservedDeviceNames.Contains(stem))
        {
            cleaned = "_" + cleaned;
            if (cleaned.Length > MaxFileNameLength)
            {
                cleaned = cleaned[..MaxFileNameLength].TrimEnd('.', ' ');
            }
        }

        return cleaned.Length == 0 ? null : cleaned;
    }

    public static string GetUniqueFilePath(string directory, string baseName, string extension)
    {
        if (string.IsNullOrWhiteSpace(directory))
        {
            throw new ArgumentException("Destination directory is required.", nameof(directory));
        }

        string safeBase = SanitizeFileName(baseName, "Track");
        string ext = string.IsNullOrWhiteSpace(extension)
            ? string.Empty
            : extension.StartsWith('.') ? extension : "." + extension;

        string candidate = Path.Combine(directory, $"{safeBase}{ext}");
        if (!File.Exists(candidate) && !Directory.Exists(candidate))
        {
            return candidate;
        }

        for (int counter = 2; ; counter++)
        {
            string numbered = Path.Combine(directory, $"{safeBase} ({counter}){ext}");
            if (!File.Exists(numbered) && !Directory.Exists(numbered))
            {
                return numbered;
            }
        }
    }

    public async Task<CloudExportResult> ExportPlaylistAsync(
        LibraryRoot root,
        IReadOnlyList<Track> tracks,
        string parentDirectory,
        string folderName,
        IProgress<CloudExportProgress>? progress = null,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(root);
        ArgumentNullException.ThrowIfNull(tracks);
        if (string.IsNullOrWhiteSpace(parentDirectory))
        {
            throw new ArgumentException("Choose a save location folder.", nameof(parentDirectory));
        }

        string rootFallback = SanitizeFileName(root.Name, "Cloud Playlist");
        string safeFolderName = SanitizeFileName(folderName, rootFallback);
        string destinationFolder = Path.Combine(parentDirectory, safeFolderName);
        Directory.CreateDirectory(destinationFolder);

        var existingManifest = LoadManifest(destinationFolder);
        var trackedMap = existingManifest?.Tracks is not null
            ? new Dictionary<string, string>(existingManifest.Tracks, StringComparer.Ordinal)
            : new Dictionary<string, string>(StringComparer.Ordinal);

        var cloudTracks = tracks.Where(t => t.IsCloud).ToList();
        int savedCount = 0;
        int failedCount = 0;
        var errors = new List<string>();

        for (int i = 0; i < cloudTracks.Count; i++)
        {
            ct.ThrowIfCancellationRequested();
            var track = cloudTracks[i];
            progress?.Report(new CloudExportProgress(i, cloudTracks.Count, track.Title, failedCount));

            var trackProgress = CreateChildProgressAdapter(
                progress,
                i,
                cloudTracks.Count,
                track.Title,
                failedCount);

            try
            {
                string destPath = await ExportTrackAsync(track, root, destinationFolder, trackProgress, ct).ConfigureAwait(false);
                string trackKey = !string.IsNullOrWhiteSpace(track.RemoteTrackId)
                    ? track.RemoteTrackId!
                    : track.RelativePath;
                if (!string.IsNullOrWhiteSpace(trackKey))
                {
                    trackedMap[trackKey] = Path.GetFileName(destPath);
                }
                savedCount++;
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                failedCount++;
                errors.Add($"{track.Title}: {ex.Message}");
            }
        }

        string? sourceUrl = string.IsNullOrWhiteSpace(root.SourceUrl) ? null : root.SourceUrl.Trim();
        RootKind? cloudKind = root.EffectiveCloudKind;
        string? providerId = root.EffectiveProviderId;
        if (sourceUrl is not null && (cloudKind is { } || !string.IsNullOrWhiteSpace(providerId)))
        {
            try
            {
                SaveManifest(
                    destinationFolder,
                    new CloudFolderManifest(
                        SourceUrl: sourceUrl,
                        RemoteId: root.RemoteId,
                        CloudKind: (cloudKind ?? RootKind.CloudExtension).ToString(),
                        LastSyncedUtc: DateTimeOffset.UtcNow,
                        Tracks: trackedMap,
                        ProviderId: providerId));
            }
            catch
            {
                // Best effort; exported audio files remain valid even if manifest write fails.
            }
        }

        progress?.Report(new CloudExportProgress(cloudTracks.Count, cloudTracks.Count, string.Empty, failedCount));
        return new CloudExportResult(savedCount, failedCount, destinationFolder, errors);
    }

    public async Task<CloudUpdateResult> UpdateLocalFolderAsync(
        LibraryRoot root,
        Func<string, string?, string?, RootKind?, CancellationToken, Task<CloudPlaylistResult>>? fetchPlaylist = null,
        IProgress<CloudExportProgress>? progress = null,
        CancellationToken ct = default,
        string? targetFolderPath = null,
        CloudTrackingInfo? trackingOverride = null)
    {
        ArgumentNullException.ThrowIfNull(root);
        string folderPath = !string.IsNullOrWhiteSpace(targetFolderPath)
            ? targetFolderPath
            : !string.IsNullOrWhiteSpace(root.Path)
                ? root.Path
                : throw new DirectoryNotFoundException("Saved folder location is unavailable.");

        Directory.CreateDirectory(folderPath);

        CloudFolderManifest? existingManifest = LoadManifest(folderPath);
        string rawSource = (!string.IsNullOrWhiteSpace(trackingOverride?.SourceUrl)
            ? trackingOverride!.SourceUrl
            : existingManifest?.SourceUrl ?? root.SourceUrl ?? root.RemoteId ?? string.Empty).Trim();
        if (rawSource.Length == 0)
        {
            throw new InvalidOperationException("No cloud tracking source URL is associated with this folder.");
        }

        RootKind? kindHint = trackingOverride is not null && trackingOverride.CloudKind.IsCloud()
            ? trackingOverride.CloudKind
            : root.EffectiveCloudKind;
        if (kindHint is null &&
            existingManifest is not null &&
            Enum.TryParse<RootKind>(existingManifest.CloudKind, ignoreCase: true, out var parsedKind) &&
            parsedKind.IsCloud())
        {
            kindHint = parsedKind;
        }

        string? providerIdHint = !string.IsNullOrWhiteSpace(trackingOverride?.ResolvedProviderId)
            ? trackingOverride!.ResolvedProviderId
            : !string.IsNullOrWhiteSpace(existingManifest?.ProviderId)
                ? existingManifest!.ProviderId
                : root.EffectiveProviderId ?? kindHint?.BuiltinProviderId();

        string? effectiveCookie = trackingOverride is not null
            ? trackingOverride.Cookie ?? root.Cookie
            : root.Cookie;
        string? effectiveProxy = trackingOverride is not null
            ? trackingOverride.ProxyUrl ?? root.ProxyUrl
            : root.ProxyUrl;

        CloudPlaylistResult playlist;
        ICloudSourceProvider? resolvedProvider = _providerRegistry.GetById(providerIdHint);
        if (fetchPlaylist is not null)
        {
            playlist = await fetchPlaylist(rawSource, effectiveCookie, effectiveProxy, kindHint, ct).ConfigureAwait(false);
            resolvedProvider ??= _providerRegistry.GetById(playlist.ResolvedProviderId);
        }
        else
        {
            if (resolvedProvider is null)
            {
                if (!string.IsNullOrWhiteSpace(providerIdHint))
                {
                    throw new CloudServiceException(
                        CloudErrorCode.ExtensionUnavailable,
                        $"Cloud provider '{providerIdHint}' is disabled or not installed.");
                }
                var classified = await CloudUrlClassifier.ClassifyAsync(rawSource, _providerRegistry, ct: ct)
                    .ConfigureAwait(false);
                resolvedProvider = classified.Provider;
                rawSource = classified.Match.NormalizedUrl ?? rawSource;
            }
            else if (!resolvedProvider.IsAvailable)
            {
                throw new CloudServiceException(
                    CloudErrorCode.ExtensionUnavailable,
                    $"Cloud provider '{resolvedProvider.DisplayName}' is disabled or not installed.");
            }

            playlist = await resolvedProvider.FetchPlaylistAsync(rawSource, effectiveCookie, effectiveProxy, ct)
                .ConfigureAwait(false);
        }

        var trackedMap = existingManifest?.Tracks is not null
            ? new Dictionary<string, string>(existingManifest.Tracks, StringComparer.Ordinal)
            : new Dictionary<string, string>(StringComparer.Ordinal);

        string cloudSource = resolvedProvider?.CloudSourceKey ?? playlist.Kind switch
        {
            RootKind.CloudBilibili => CloudSources.Bilibili,
            RootKind.CloudGoogleDrive => CloudSources.GoogleDrive,
            RootKind.CloudExtension => playlist.ResolvedProviderId ?? "extension",
            _ => CloudSources.YouTube
        };
        string albumArtist = resolvedProvider?.DisplayName ?? playlist.Kind switch
        {
            RootKind.CloudBilibili => "Bilibili",
            RootKind.CloudGoogleDrive => "Google Drive",
            RootKind.CloudExtension => CloudProviderRegistry.ResolveDisplayLabel(_providerRegistry, playlist.ResolvedProviderId, playlist.Kind),
            _ => "YouTube"
        };
        string resolvedSourceUrl = !string.IsNullOrWhiteSpace(trackingOverride?.SourceUrl)
            ? trackingOverride!.SourceUrl.Trim()
            : (string.IsNullOrWhiteSpace(playlist.SourceUrl) ? rawSource : playlist.SourceUrl);
        string? effectiveProviderId = resolvedProvider?.ProviderId ?? playlist.ResolvedProviderId ?? providerIdHint;

        var lookupRoot = root with
        {
            Kind = playlist.Kind,
            SourceUrl = resolvedSourceUrl,
            RemoteId = playlist.RemoteId,
            Cookie = effectiveCookie,
            ProxyUrl = effectiveProxy,
            ProviderId = effectiveProviderId
        };

        int addedCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        var errors = new List<string>();
        var now = DateTimeOffset.UtcNow;

        for (int i = 0; i < playlist.Items.Count; i++)
        {
            ct.ThrowIfCancellationRequested();
            var item = playlist.Items[i];
            progress?.Report(new CloudExportProgress(i, playlist.Items.Count, item.Title, failedCount));

            if (trackedMap.TryGetValue(item.RemoteTrackId, out string? recordedFile) &&
                !string.IsNullOrWhiteSpace(recordedFile) &&
                File.Exists(Path.Combine(folderPath, recordedFile)))
            {
                skippedCount++;
                continue;
            }

            string relPath = $"cloud/{item.RemoteTrackId}";
            var tempTrack = new Track(
                Id: Track.MakeId(root.Id, relPath),
                RootId: root.Id,
                RelativePath: relPath,
                Title: string.IsNullOrWhiteSpace(item.Title) ? item.RemoteTrackId : item.Title,
                Artist: string.IsNullOrWhiteSpace(item.Artist) ? root.Name : item.Artist,
                Album: string.IsNullOrWhiteSpace(item.Album) ? (string.IsNullOrWhiteSpace(playlist.Title) ? root.Name : playlist.Title) : item.Album,
                AlbumArtist: albumArtist,
                TrackNumber: item.TrackNumber > 0 ? item.TrackNumber : i + 1,
                DiscNumber: 1,
                DurationSeconds: item.DurationSeconds,
                FileSize: 0,
                ModifiedUtc: now,
                HasEmbeddedLyrics: false,
                DateAddedUtc: now,
                ArtworkKey: null,
                CloudSource: cloudSource,
                RemoteTrackId: item.RemoteTrackId,
                RemoteArtworkUrl: item.ArtworkUrl);

            var trackProgress = CreateChildProgressAdapter(
                progress,
                i,
                playlist.Items.Count,
                tempTrack.Title,
                failedCount);

            try
            {
                string destPath = await ExportTrackAsync(tempTrack, lookupRoot, folderPath, trackProgress, ct).ConfigureAwait(false);
                trackedMap[item.RemoteTrackId] = Path.GetFileName(destPath);
                addedCount++;
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                failedCount++;
                errors.Add($"{item.Title}: {ex.Message}");
            }
        }

        SaveManifest(
            folderPath,
            new CloudFolderManifest(
                SourceUrl: resolvedSourceUrl,
                RemoteId: playlist.RemoteId,
                CloudKind: playlist.Kind.ToString(),
                LastSyncedUtc: DateTimeOffset.UtcNow,
                Tracks: trackedMap,
                ProviderId: effectiveProviderId));

        progress?.Report(new CloudExportProgress(playlist.Items.Count, playlist.Items.Count, string.Empty, failedCount));
        return new CloudUpdateResult(addedCount, skippedCount, failedCount, playlist.Items.Count, errors);
    }

    public static CloudFolderManifest? LoadManifest(string folderPath)
    {
        if (string.IsNullOrWhiteSpace(folderPath)) return null;
        string path = Path.Combine(folderPath, ManifestFileName);
        if (!File.Exists(path)) return null;
        try
        {
            string json = File.ReadAllText(path, Encoding.UTF8);
            var manifest = JsonSerializer.Deserialize<CloudFolderManifest>(json, ManifestJsonOptions);
            if (manifest is null || string.IsNullOrWhiteSpace(manifest.SourceUrl))
            {
                return null;
            }
            return manifest;
        }
        catch
        {
            return null;
        }
    }

    public static void SaveManifest(string folderPath, CloudFolderManifest manifest)
    {
        ArgumentNullException.ThrowIfNull(manifest);
        Directory.CreateDirectory(folderPath);
        string path = Path.Combine(folderPath, ManifestFileName);
        string tempPath = $"{path}.{Guid.NewGuid():N}.tmp";
        string json = JsonSerializer.Serialize(manifest, ManifestJsonOptions);
        try
        {
            File.WriteAllText(tempPath, json, Encoding.UTF8);
            File.Move(tempPath, path, overwrite: true);
        }
        catch
        {
            try { if (File.Exists(tempPath)) File.Delete(tempPath); } catch { /* ignore */ }
            throw;
        }
    }

    public Task<string> ExportTrackAsync(
        Track track,
        LibraryRoot? root,
        string destinationDirectory,
        CancellationToken ct = default) =>
        ExportTrackAsync(track, root, destinationDirectory, progress: null, suppliedArtworkBytes: null, ct: ct);

    public Task<string> ExportTrackAsync(
        Track track,
        LibraryRoot? root,
        string destinationDirectory,
        IProgress<CloudExportProgress>? progress,
        CancellationToken ct = default) =>
        ExportTrackAsync(track, root, destinationDirectory, progress, suppliedArtworkBytes: null, ct: ct);

    public async Task<string> ExportTrackAsync(
        Track track,
        LibraryRoot? root,
        string destinationDirectory,
        IProgress<CloudExportProgress>? progress,
        byte[]? suppliedArtworkBytes,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(track);
        if (!track.IsCloud)
        {
            throw new InvalidOperationException("Only cloud songs can be exported to a local folder.");
        }
        if (string.IsNullOrWhiteSpace(destinationDirectory))
        {
            throw new ArgumentException("Choose a destination folder.", nameof(destinationDirectory));
        }

        ct.ThrowIfCancellationRequested();

        long lastBytes = 0;
        long? lastTotal = null;
        Action<CloudDownloadProgress>? onDownloadProgress = progress is null
            ? null
            : dl =>
            {
                lastBytes = dl.BytesDownloaded;
                if (dl.TotalBytes is > 0)
                {
                    lastTotal = dl.TotalBytes;
                }
                progress.Report(new CloudExportProgress(
                    Completed: 0,
                    Total: 1,
                    CurrentTrackTitle: track.Title,
                    FailedCount: 0,
                    BytesDownloaded: dl.BytesDownloaded,
                    TotalBytes: dl.TotalBytes ?? lastTotal,
                    BytesPerSecond: dl.BytesPerSecond));
            };

        string cachedPath = await _cloudCache.GetOrDownloadAsync(
            track.Id,
            token => ResolveCloudStreamAsync(track, root, token),
            root?.ProxyUrl,
            ct,
            onDownloadProgress).ConfigureAwait(false);

        ct.ThrowIfCancellationRequested();
        Directory.CreateDirectory(destinationDirectory);

        string ext = Path.GetExtension(cachedPath);
        if (string.IsNullOrWhiteSpace(ext))
        {
            ext = ".m4a";
        }

        string fallbackName = string.IsNullOrWhiteSpace(track.RemoteTrackId) ? "Track" : track.RemoteTrackId;
        string baseName = SanitizeFileName(track.Title, fallbackName);
        string destPath = GetUniqueFilePath(destinationDirectory, baseName, ext);
        string tempPath = $"{destPath}.{Guid.NewGuid():N}.tmp";

        try
        {
            await using (var src = new FileStream(cachedPath, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, useAsync: true))
            await using (var dst = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920, useAsync: true))
            {
                await src.CopyToAsync(dst, ct).ConfigureAwait(false);
                await dst.FlushAsync(ct).ConfigureAwait(false);
            }

            File.Move(tempPath, destPath, overwrite: false);
        }
        catch
        {
            try { if (File.Exists(tempPath)) File.Delete(tempPath); } catch { /* best effort */ }
            throw;
        }

        byte[]? resolvedArtBytes = await ResolveCoverBytesAsync(track, root, suppliedArtworkBytes, ct).ConfigureAwait(false);
        TryWriteTagsAndSidecar(destPath, track, resolvedArtBytes);

        if (progress is not null)
        {
            long finalBytes = lastTotal ?? lastBytes;
            if (finalBytes <= 0)
            {
                try { finalBytes = new FileInfo(destPath).Length; } catch { /* ignore */ }
            }
            progress.Report(new CloudExportProgress(
                Completed: 1,
                Total: 1,
                CurrentTrackTitle: track.Title,
                FailedCount: 0,
                BytesDownloaded: finalBytes,
                TotalBytes: finalBytes > 0 ? finalBytes : null,
                BytesPerSecond: 0));
        }

        return destPath;
    }

    private async Task<byte[]?> ResolveCoverBytesAsync(
        Track track,
        LibraryRoot? root,
        byte[]? suppliedArtworkBytes,
        CancellationToken ct)
    {
        if (suppliedArtworkBytes is { Length: > 0 })
        {
            return suppliedArtworkBytes;
        }

        string? artPath = _artworkCache?.GetImagePath(track.ArtworkKey);
        if (!string.IsNullOrEmpty(artPath) && File.Exists(artPath))
        {
            try
            {
                byte[] diskBytes = await File.ReadAllBytesAsync(artPath, ct).ConfigureAwait(false);
                if (diskBytes.Length > 0)
                {
                    return diskBytes;
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch
            {
                // Fall through to remote fetch.
            }
        }

        string? normalizedUrl = CloudHttpHelper.NormalizeImageUrl(track.RemoteArtworkUrl);
        if (!CloudThumbnailCache.IsAllowedHttpsUrl(normalizedUrl))
        {
            return null;
        }

        string? referer = root?.Kind == RootKind.CloudBilibili ||
                          string.Equals(track.CloudSource, CloudSources.Bilibili, StringComparison.OrdinalIgnoreCase)
            ? BilibiliClient.DefaultReferer
            : null;

        try
        {
            return await _thumbnailCache!.GetBytesAsync(normalizedUrl!, referer, ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            return null;
        }
    }

    private static IProgress<CloudExportProgress>? CreateChildProgressAdapter(
        IProgress<CloudExportProgress>? parent,
        int completedIndex,
        int totalItems,
        string itemTitle,
        int failedCount)
    {
        if (parent is null) return null;
        return new InlineProgress<CloudExportProgress>(tp =>
        {
            if (tp.Completed != 0) return;
            parent.Report(new CloudExportProgress(
                Completed: completedIndex,
                Total: totalItems,
                CurrentTrackTitle: itemTitle,
                FailedCount: failedCount,
                BytesDownloaded: tp.BytesDownloaded,
                TotalBytes: tp.TotalBytes,
                BytesPerSecond: tp.BytesPerSecond));
        });
    }

    private sealed class InlineProgress<T>(Action<T> handler) : IProgress<T>
    {
        public void Report(T value) => handler(value);
    }

    private Task<CloudAudioStream> ResolveCloudStreamAsync(Track track, LibraryRoot? root, CancellationToken ct) =>
        CloudStreamResolver.ResolveAsync(_providerRegistry, track, root, _extensions, forceRefresh: false, ct: ct);

    private static bool IsNonEmbeddableContainer(string filePath)
    {
        string ext = Path.GetExtension(filePath);
        return string.Equals(ext, ".webm", StringComparison.OrdinalIgnoreCase) ||
               string.Equals(ext, ".weba", StringComparison.OrdinalIgnoreCase);
    }

    private static void TryWriteTagsAndSidecar(string destPath, Track track, byte[]? artBytes)
    {
        if (IsNonEmbeddableContainer(destPath))
        {
            if (artBytes is { Length: > 0 })
            {
                WriteSiblingCoverSidecar(destPath, artBytes);
            }
            return;
        }

        bool embeddedCoverOk = false;
        try
        {
            using var file = TagLib.File.Create(destPath);
            if (!string.IsNullOrWhiteSpace(track.Title))
            {
                file.Tag.Title = track.Title;
            }
            if (!string.IsNullOrWhiteSpace(track.Artist))
            {
                file.Tag.Performers = [track.Artist];
            }
            if (!string.IsNullOrWhiteSpace(track.Album))
            {
                file.Tag.Album = track.Album;
            }
            if (!string.IsNullOrWhiteSpace(track.AlbumArtist))
            {
                file.Tag.AlbumArtists = [track.AlbumArtist];
            }
            if (track.TrackNumber is int tn && tn > 0)
            {
                file.Tag.Track = (uint)tn;
            }
            if (track.DiscNumber is int dn && dn > 0)
            {
                file.Tag.Disc = (uint)dn;
            }

            if (artBytes is { Length: > 0 })
            {
                string mimeType = ImageFormatHelper.DetectMimeType(artBytes);
                var picture = new TagLib.Picture(new TagLib.ByteVector(artBytes))
                {
                    Type = TagLib.PictureType.FrontCover,
                    MimeType = mimeType,
                    Description = "Cover"
                };
                file.Tag.Pictures = [picture];
            }

            file.Save();
            embeddedCoverOk = artBytes is { Length: > 0 };
        }
        catch
        {
            // Fall through to sidecar fallback if cover art could not be embedded.
        }

        if (artBytes is { Length: > 0 } && !embeddedCoverOk)
        {
            WriteSiblingCoverSidecar(destPath, artBytes);
        }
    }

    private static void WriteSiblingCoverSidecar(string audioFilePath, byte[] artBytes)
    {
        if (artBytes.Length == 0) return;

        string? dir = Path.GetDirectoryName(audioFilePath);
        string stem = Path.GetFileNameWithoutExtension(audioFilePath);
        if (string.IsNullOrWhiteSpace(stem)) return;

        string imgExt = ImageFormatHelper.DetectSidecarExtension(artBytes);
        string sidecarPath = string.IsNullOrEmpty(dir)
            ? $"{stem}{imgExt}"
            : Path.Combine(dir, $"{stem}{imgExt}");
        string tempPath = $"{sidecarPath}.{Guid.NewGuid():N}.tmp";

        try
        {
            File.WriteAllBytes(tempPath, artBytes);
            File.Move(tempPath, sidecarPath, overwrite: true);
        }
        catch
        {
            try { if (File.Exists(tempPath)) File.Delete(tempPath); } catch { /* best effort */ }
        }
    }

    private static bool IsInvalidWindowsFileNameChar(char c) =>
        c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' ||
        c == '"' || c == '<' || c == '>' || c == '|' || char.IsControl(c);
}
