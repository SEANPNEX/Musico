using System.Globalization;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using MusicoWindows.Localization;
using MusicoWindows.Models;

namespace MusicoWindows.Stores;

public enum DownloadJobKind
{
    PlaylistExport,
    SingleTrackExport,
    FolderUpdate
}

public enum DownloadJobStatus
{
    Running,
    Completed,
    Failed,
    Cancelled
}

public sealed record DownloadJob(
    Guid Id,
    DownloadJobKind Kind,
    string Title,
    string? Subtitle,
    string DestinationPath,
    DownloadJobStatus Status,
    int CompletedItems,
    int TotalItems,
    string CurrentItemTitle,
    long BytesDownloaded,
    long? TotalBytes,
    double BytesPerSecond,
    int FailedItems = 0,
    int AddedItems = 0,
    int SkippedItems = 0,
    string? SavedFilePath = null,
    string? LibraryNote = null,
    string? ErrorMessage = null,
    IReadOnlyList<string>? Errors = null,
    DateTimeOffset StartedUtc = default,
    DateTimeOffset? FinishedUtc = null,
    Guid? TargetRootId = null,
    string? TargetRelativePath = null)
{
    public bool IsActive => Status == DownloadJobStatus.Running;
    public bool IsFinished => Status != DownloadJobStatus.Running;

    public double? OverallFraction
    {
        get
        {
            if (Status == DownloadJobStatus.Completed) return 1.0;
            if (TotalItems <= 0) return null;
            if (CompletedItems >= TotalItems) return 1.0;
            if (TotalItems == 1 && TotalBytes is not > 0) return null;

            double trackFraction = TotalBytes is long tb && tb > 0
                ? Math.Clamp((double)BytesDownloaded / tb, 0.0, 1.0)
                : 0.0;
            return Math.Clamp((CompletedItems + trackFraction) / TotalItems, 0.0, 1.0);
        }
    }
}

/// <summary>
/// App-scoped background download coordinator for playlist exports, single-track saves,
/// and tracked cloud folder updates, with live progress/speed reporting and cancellation.
/// </summary>
public sealed class DownloadManager
{
    private sealed class JobEntry
    {
        public required DownloadJob Job { get; set; }
        public CancellationTokenSource Cts { get; } = new();
    }

    private sealed class InlineProgress(Action<CloudExportProgress> handler) : IProgress<CloudExportProgress>
    {
        public void Report(CloudExportProgress value) => handler(value);
    }

    private readonly CloudExporter _exporter;
    private readonly LibraryStore _library;
    private readonly object _lock = new();
    private readonly List<JobEntry> _entries = new();

    public DownloadManager(CloudExporter exporter, LibraryStore library)
    {
        _exporter = exporter ?? throw new ArgumentNullException(nameof(exporter));
        _library = library ?? throw new ArgumentNullException(nameof(library));
        _exporter.MetadataOverrideLookup ??= _library.GetMetadataOverride;
    }

    public event Action? Changed;

    public IReadOnlyList<DownloadJob> Jobs
    {
        get
        {
            lock (_lock)
            {
                return _entries.Select(e => e.Job).ToList();
            }
        }
    }

    public IReadOnlyList<DownloadJob> ActiveJobs
    {
        get
        {
            lock (_lock)
            {
                return _entries.Where(e => e.Job.IsActive).Select(e => e.Job).ToList();
            }
        }
    }

    public bool HasActiveDownloads
    {
        get
        {
            lock (_lock)
            {
                return _entries.Any(e => e.Job.IsActive);
            }
        }
    }

    public DownloadJob? GetJob(Guid jobId)
    {
        lock (_lock)
        {
            return _entries.FirstOrDefault(e => e.Job.Id == jobId)?.Job;
        }
    }

    public DownloadJob StartPlaylistExport(
        LibraryRoot root,
        IReadOnlyList<Track> tracks,
        string parentDirectory,
        string folderName,
        bool addToLibrary,
        bool isWholeRootExport = true)
    {
        ArgumentNullException.ThrowIfNull(root);
        ArgumentNullException.ThrowIfNull(tracks);

        var trackSnapshot = tracks.ToList();
        string safeFolder = CloudExporter.SanitizeFileName(
            folderName,
            CloudExporter.SanitizeFileName(root.Name, "Cloud Playlist"));
        string destinationFolder = Path.Combine(parentDirectory, safeFolder);

        var job = new DownloadJob(
            Id: Guid.NewGuid(),
            Kind: DownloadJobKind.PlaylistExport,
            Title: safeFolder,
            Subtitle: $"{root.Name} · {L.SongsCount(trackSnapshot.Count)}",
            DestinationPath: destinationFolder,
            Status: DownloadJobStatus.Running,
            CompletedItems: 0,
            TotalItems: trackSnapshot.Count,
            CurrentItemTitle: trackSnapshot.FirstOrDefault()?.Title ?? string.Empty,
            BytesDownloaded: 0,
            TotalBytes: null,
            BytesPerSecond: 0,
            StartedUtc: DateTimeOffset.UtcNow,
            TargetRootId: root.Id);

        var entry = RegisterJob(job);
        _ = RunPlaylistExportAsync(entry, root, trackSnapshot, parentDirectory, folderName, addToLibrary, isWholeRootExport);
        return job;
    }

    public DownloadJob StartSingleTrackExport(
        Track track,
        LibraryRoot? root,
        string destinationDirectory,
        string? qualityOverride = null)
    {
        ArgumentNullException.ThrowIfNull(track);

        var job = new DownloadJob(
            Id: Guid.NewGuid(),
            Kind: DownloadJobKind.SingleTrackExport,
            Title: track.Title,
            Subtitle: $"{track.Title} · {L.DisplayArtist(track.Artist)}",
            DestinationPath: destinationDirectory,
            Status: DownloadJobStatus.Running,
            CompletedItems: 0,
            TotalItems: 1,
            CurrentItemTitle: track.Title,
            BytesDownloaded: 0,
            TotalBytes: null,
            BytesPerSecond: 0,
            StartedUtc: DateTimeOffset.UtcNow,
            TargetRootId: track.RootId);

        var entry = RegisterJob(job);
        _ = RunSingleTrackExportAsync(entry, track, root, destinationDirectory, qualityOverride);
        return job;
    }

    public DownloadJob StartFolderUpdate(
        LibraryRoot root,
        string? targetFolderPath = null,
        CloudTrackingInfo? trackingOverride = null,
        string? targetRelativePath = null,
        Func<string, string?, string?, RootKind?, CancellationToken, Task<CloudPlaylistResult>>? fetchPlaylist = null)
    {
        ArgumentNullException.ThrowIfNull(root);

        string normRel = LibraryRoot.NormalizeFolderPath(targetRelativePath ?? string.Empty);
        string resolvedFolder = !string.IsNullOrWhiteSpace(targetFolderPath)
            ? targetFolderPath
            : !string.IsNullOrEmpty(normRel) && !string.IsNullOrWhiteSpace(root.Path)
                ? FolderScanner.CombinePath(root.Path, normRel)
                : root.Path ?? string.Empty;

        string folderDisplayName = normRel.Length > 0
            ? normRel.Split('/').Last()
            : root.Name;

        RootKind? effectiveKind = trackingOverride?.CloudKind.IsCloud() == true
            ? trackingOverride.CloudKind
            : root.EffectiveCloudKind;
        string? effectiveProviderId = !string.IsNullOrWhiteSpace(trackingOverride?.ResolvedProviderId)
            ? trackingOverride!.ResolvedProviderId
            : root.EffectiveProviderId;
        string sourceLabel = CloudProviderRegistry.ResolveDisplayLabel(
            _library.CloudProviders,
            effectiveProviderId,
            effectiveKind);
        string? sourceUrl = !string.IsNullOrWhiteSpace(trackingOverride?.SourceUrl)
            ? trackingOverride!.SourceUrl
            : root.SourceUrl;
        string subtitle = string.IsNullOrWhiteSpace(sourceUrl)
            ? $"{folderDisplayName} · {sourceLabel}"
            : $"{folderDisplayName} · {sourceLabel} · {sourceUrl}";

        var job = new DownloadJob(
            Id: Guid.NewGuid(),
            Kind: DownloadJobKind.FolderUpdate,
            Title: folderDisplayName,
            Subtitle: subtitle,
            DestinationPath: resolvedFolder,
            Status: DownloadJobStatus.Running,
            CompletedItems: 0,
            TotalItems: 0,
            CurrentItemTitle: string.Empty,
            BytesDownloaded: 0,
            TotalBytes: null,
            BytesPerSecond: 0,
            StartedUtc: DateTimeOffset.UtcNow,
            TargetRootId: root.Id,
            TargetRelativePath: normRel);

        var entry = RegisterJob(job);
        _ = RunFolderUpdateAsync(entry, root, resolvedFolder, trackingOverride, normRel, fetchPlaylist);
        return job;
    }

    public void Cancel(Guid jobId)
    {
        JobEntry? entry;
        lock (_lock)
        {
            entry = _entries.FirstOrDefault(e => e.Job.Id == jobId);
        }
        if (entry is null) return;

        try { entry.Cts.Cancel(); } catch (ObjectDisposedException) { }
    }

    public void CancelAll()
    {
        List<JobEntry> snapshot;
        lock (_lock)
        {
            snapshot = _entries.ToList();
        }
        foreach (var entry in snapshot)
        {
            try { entry.Cts.Cancel(); } catch (ObjectDisposedException) { }
        }
    }

    public void Dismiss(Guid jobId)
    {
        JobEntry? removed = null;
        lock (_lock)
        {
            int idx = _entries.FindIndex(e => e.Job.Id == jobId);
            if (idx >= 0)
            {
                removed = _entries[idx];
                _entries.RemoveAt(idx);
            }
        }
        if (removed is null) return;

        if (removed.Job.IsActive)
        {
            try { removed.Cts.Cancel(); } catch (ObjectDisposedException) { }
        }
        NotifyChanged();
    }

    public void ClearFinished()
    {
        bool changed;
        lock (_lock)
        {
            changed = _entries.RemoveAll(e => e.Job.IsFinished) > 0;
        }
        if (changed)
        {
            NotifyChanged();
        }
    }

    public static string FormatBytes(long bytes)
    {
        long safe = Math.Max(0, bytes);
        if (safe < 1024)
        {
            return $"{safe} B";
        }
        if (safe < 1024L * 1024)
        {
            return $"{(safe / 1024.0).ToString("0.0", CultureInfo.InvariantCulture)} KB";
        }
        if (safe < 1024L * 1024 * 1024)
        {
            return $"{(safe / (1024.0 * 1024.0)).ToString("0.0", CultureInfo.InvariantCulture)} MB";
        }
        return $"{(safe / (1024.0 * 1024.0 * 1024.0)).ToString("0.00", CultureInfo.InvariantCulture)} GB";
    }

    public static string FormatSpeed(double bytesPerSecond)
    {
        if (double.IsNaN(bytesPerSecond) || double.IsInfinity(bytesPerSecond) || bytesPerSecond <= 0)
        {
            return "0 B/s";
        }
        return $"{FormatBytes((long)Math.Round(bytesPerSecond))}/s";
    }

    public static string FormatPercentage(double? fraction)
    {
        if (fraction is not double f || double.IsNaN(f) || double.IsInfinity(f))
        {
            return string.Empty;
        }
        int pct = (int)Math.Clamp(Math.Round(f * 100.0), 0, 100);
        return $"{pct}%";
    }

    private JobEntry RegisterJob(DownloadJob job)
    {
        var entry = new JobEntry { Job = job };
        lock (_lock)
        {
            _entries.Insert(0, entry);
        }
        NotifyChanged();
        return entry;
    }

    private void UpdateJob(JobEntry entry, Func<DownloadJob, DownloadJob> updater)
    {
        lock (_lock)
        {
            entry.Job = updater(entry.Job);
        }
        NotifyChanged();
    }

    private void NotifyChanged() => Changed?.Invoke();

    private async Task RunPlaylistExportAsync(
        JobEntry entry,
        LibraryRoot root,
        IReadOnlyList<Track> tracks,
        string parentDirectory,
        string folderName,
        bool addToLibrary,
        bool isWholeRootExport)
    {
        var ct = entry.Cts.Token;
        var progress = new InlineProgress(p =>
        {
            UpdateJob(entry, j => j with
            {
                CompletedItems = p.Completed,
                TotalItems = p.Total,
                CurrentItemTitle = p.CurrentTrackTitle,
                FailedItems = p.FailedCount,
                BytesDownloaded = p.BytesDownloaded,
                TotalBytes = p.TotalBytes,
                BytesPerSecond = p.BytesPerSecond
            });
        });

        try
        {
            CloudExportResult result = await Task.Run(
                () => _exporter.ExportPlaylistAsync(root, tracks, parentDirectory, folderName, progress, ct),
                ct).ConfigureAwait(false);

            string? libraryNote = null;
            Guid? resolvedTargetRootId = null;
            string? resolvedTargetRelPath = null;
            bool shouldMergeCloudRoot = root.Kind.IsCloud() && isWholeRootExport;

            if (addToLibrary && result.SavedCount > 0)
            {
                bool wasAlreadyIndexed = _library.IsFolderIndexed(result.DestinationFolder);
                try
                {
                    await _library.RegisterExportedCloudFolderAsync(
                        result.DestinationFolder,
                        root,
                        mergeCloudRoot: shouldMergeCloudRoot).ConfigureAwait(false);
                    if (_library.ResolveIndexedFolderLocation(result.DestinationFolder) is { } loc)
                    {
                        resolvedTargetRootId = loc.RootId;
                        resolvedTargetRelPath = loc.RelativePath;
                    }
                    libraryNote = wasAlreadyIndexed
                        ? L.Get("SaveCloud_LibraryRescanned")
                        : L.Get("SaveCloud_AddedToLibrary");
                }
                catch (Exception libEx)
                {
                    libraryNote = L.Format("SaveCloud_SavedLibError", libEx.Message);
                }
            }
            else if (result.SavedCount > 0 && _library.IsFolderIndexed(result.DestinationFolder))
            {
                try
                {
                    await _library.RegisterExportedCloudFolderAsync(
                        result.DestinationFolder,
                        root,
                        mergeCloudRoot: shouldMergeCloudRoot).ConfigureAwait(false);
                    if (_library.ResolveIndexedFolderLocation(result.DestinationFolder) is { } loc)
                    {
                        resolvedTargetRootId = loc.RootId;
                        resolvedTargetRelPath = loc.RelativePath;
                    }
                    libraryNote = L.Get("SaveCloud_LibraryRescanned");
                }
                catch
                {
                    // Best-effort refresh.
                }
            }
            else if (result.SavedCount > 0)
            {
                var trackIds = tracks
                    .Where(t => _library.GetTrack(t.Id) is not null)
                    .Select(t => t.Id)
                    .ToList();
                _library.NotifyFolderAddedByPath(result.DestinationFolder, trackIds);
            }

            UpdateJob(entry, j => j with
            {
                Status = DownloadJobStatus.Completed,
                DestinationPath = result.DestinationFolder,
                CompletedItems = result.SavedCount,
                FailedItems = result.FailedCount,
                Errors = result.Errors,
                LibraryNote = libraryNote,
                TargetRootId = resolvedTargetRootId ?? j.TargetRootId,
                TargetRelativePath = resolvedTargetRelPath ?? j.TargetRelativePath,
                BytesPerSecond = 0,
                FinishedUtc = DateTimeOffset.UtcNow
            });
        }
        catch (OperationCanceledException)
        {
            UpdateJob(entry, j => j with
            {
                Status = DownloadJobStatus.Cancelled,
                ErrorMessage = L.Get("SaveCloud_ExportCancelled"),
                BytesPerSecond = 0,
                FinishedUtc = DateTimeOffset.UtcNow
            });
        }
        catch (Exception ex)
        {
            UpdateJob(entry, j => j with
            {
                Status = DownloadJobStatus.Failed,
                ErrorMessage = ex.Message,
                BytesPerSecond = 0,
                FinishedUtc = DateTimeOffset.UtcNow
            });
        }
        finally
        {
            entry.Cts.Dispose();
        }
    }

    private async Task RunSingleTrackExportAsync(
        JobEntry entry,
        Track track,
        LibraryRoot? root,
        string destinationDirectory,
        string? qualityOverride = null)
    {
        var ct = entry.Cts.Token;
        var progress = new InlineProgress(p =>
        {
            UpdateJob(entry, j => j with
            {
                CompletedItems = p.Completed,
                TotalItems = p.Total,
                CurrentItemTitle = p.CurrentTrackTitle,
                BytesDownloaded = p.BytesDownloaded,
                TotalBytes = p.TotalBytes,
                BytesPerSecond = p.BytesPerSecond
            });
        });

        try
        {
            string savedPath = await Task.Run(
                () => _exporter.ExportTrackAsync(track, root, destinationDirectory, progress, suppliedArtworkBytes: null, ct: ct, qualityOverride: qualityOverride),
                ct).ConfigureAwait(false);

            string savedFolder = Path.GetDirectoryName(savedPath) ?? destinationDirectory;
            string? libraryNote = null;
            if (_library.IsFolderIndexed(savedFolder))
            {
                await _library.RescanAsync().ConfigureAwait(false);
                libraryNote = L.Get("SaveCloud_LibraryRescanned");
            }

            UpdateJob(entry, j => j with
            {
                Status = DownloadJobStatus.Completed,
                CompletedItems = 1,
                TotalItems = 1,
                DestinationPath = savedFolder,
                SavedFilePath = savedPath,
                LibraryNote = libraryNote,
                BytesPerSecond = 0,
                FinishedUtc = DateTimeOffset.UtcNow
            });
        }
        catch (OperationCanceledException)
        {
            UpdateJob(entry, j => j with
            {
                Status = DownloadJobStatus.Cancelled,
                ErrorMessage = L.Get("SaveCloud_ExportCancelled"),
                BytesPerSecond = 0,
                FinishedUtc = DateTimeOffset.UtcNow
            });
        }
        catch (Exception ex)
        {
            UpdateJob(entry, j => j with
            {
                Status = DownloadJobStatus.Failed,
                ErrorMessage = ex.Message,
                BytesPerSecond = 0,
                FinishedUtc = DateTimeOffset.UtcNow
            });
        }
        finally
        {
            entry.Cts.Dispose();
        }
    }

    private async Task RunFolderUpdateAsync(
        JobEntry entry,
        LibraryRoot root,
        string targetFolderPath,
        CloudTrackingInfo? trackingOverride,
        string targetRelativePath,
        Func<string, string?, string?, RootKind?, CancellationToken, Task<CloudPlaylistResult>>? fetchPlaylist)
    {
        var ct = entry.Cts.Token;
        var progress = new InlineProgress(p =>
        {
            UpdateJob(entry, j => j with
            {
                CompletedItems = p.Completed,
                TotalItems = p.Total,
                CurrentItemTitle = p.CurrentTrackTitle,
                FailedItems = p.FailedCount,
                BytesDownloaded = p.BytesDownloaded,
                TotalBytes = p.TotalBytes,
                BytesPerSecond = p.BytesPerSecond
            });
        });

        try
        {
            CloudUpdateResult result = await Task.Run(
                () => _exporter.UpdateLocalFolderAsync(
                    root,
                    fetchPlaylist,
                    progress,
                    ct,
                    targetFolderPath,
                    trackingOverride),
                ct).ConfigureAwait(false);

            _library.MarkCloudSynced(root.Id, targetRelativePath);
            string? libraryNote = null;
            if (result.AddedCount > 0)
            {
                await _library.RescanAsync().ConfigureAwait(false);
                libraryNote = L.Get("SaveCloud_LibraryRescanned");
            }

            UpdateJob(entry, j => j with
            {
                Status = DownloadJobStatus.Completed,
                DestinationPath = targetFolderPath,
                CompletedItems = result.TotalRemoteCount,
                TotalItems = result.TotalRemoteCount,
                AddedItems = result.AddedCount,
                SkippedItems = result.SkippedCount,
                FailedItems = result.FailedCount,
                Errors = result.Errors,
                LibraryNote = libraryNote,
                BytesPerSecond = 0,
                FinishedUtc = DateTimeOffset.UtcNow
            });
        }
        catch (OperationCanceledException)
        {
            UpdateJob(entry, j => j with
            {
                Status = DownloadJobStatus.Cancelled,
                ErrorMessage = L.Get("SaveCloud_UpdateCancelled"),
                BytesPerSecond = 0,
                FinishedUtc = DateTimeOffset.UtcNow
            });
        }
        catch (Exception ex)
        {
            UpdateJob(entry, j => j with
            {
                Status = DownloadJobStatus.Failed,
                ErrorMessage = ex.Message,
                BytesPerSecond = 0,
                FinishedUtc = DateTimeOffset.UtcNow
            });
        }
        finally
        {
            entry.Cts.Dispose();
        }
    }
}
