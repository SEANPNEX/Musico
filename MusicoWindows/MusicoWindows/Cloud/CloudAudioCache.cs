using System;
using System.Buffers;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;
using MusicoWindows.Core;

namespace MusicoWindows.Cloud;

public sealed record CloudDownloadProgress(
    long BytesDownloaded,
    long? TotalBytes,
    double BytesPerSecond)
{
    public double? FractionCompleted =>
        TotalBytes is long total && total > 0
            ? Math.Clamp((double)BytesDownloaded / total, 0.0, 1.0)
            : null;
}

/// <summary>
/// Thread-safe rolling ~1.5s transfer speed meter with ~120ms emission throttling
/// and byte rollback support when a chunk retries.
/// </summary>
public sealed class DownloadSpeedMeter
{
    public const double RollingWindowSeconds = 1.5;
    public const double DefaultThrottleSeconds = 0.12;

    private readonly object _lock = new();
    private readonly LinkedList<(double TimeSeconds, long Bytes)> _samples = new();
    private readonly Func<double> _clockSeconds;
    private readonly double _throttleSeconds;
    private readonly double _startTime;
    private double _lastEmitTime;
    private long _totalDownloaded;
    private long? _totalBytes;
    private long _windowBytes;

    public DownloadSpeedMeter(
        long? totalBytes = null,
        Func<double>? clockSeconds = null,
        double throttleSeconds = DefaultThrottleSeconds)
    {
        _totalBytes = totalBytes is > 0 ? totalBytes : null;
        _clockSeconds = clockSeconds ?? DefaultClockSeconds;
        _throttleSeconds = Math.Max(0, throttleSeconds);
        _startTime = _clockSeconds();
        _lastEmitTime = _startTime;
    }

    private static double DefaultClockSeconds() =>
        (double)Stopwatch.GetTimestamp() / Stopwatch.Frequency;

    public void SetTotalBytes(long? totalBytes)
    {
        if (totalBytes is not > 0) return;
        lock (_lock)
        {
            _totalBytes = totalBytes;
        }
    }

    public CloudDownloadProgress? RecordBytes(long bytes, bool forceEmit = false)
    {
        lock (_lock)
        {
            double now = _clockSeconds();
            if (bytes > 0)
            {
                _totalDownloaded += bytes;
                _windowBytes += bytes;
                _samples.AddLast((now, bytes));
            }

            double windowStart = Math.Max(_startTime, now - RollingWindowSeconds);
            while (_samples.First is { } first && first.Value.TimeSeconds < windowStart)
            {
                _windowBytes -= first.Value.Bytes;
                _samples.RemoveFirst();
            }
            if (_windowBytes < 0)
            {
                _windowBytes = 0;
            }

            if (!forceEmit && (now - _lastEmitTime) < _throttleSeconds)
            {
                return null;
            }

            _lastEmitTime = now;
            double span = Math.Max(now - windowStart, 0.05);
            double speed = _windowBytes > 0 ? _windowBytes / span : 0.0;
            return new CloudDownloadProgress(_totalDownloaded, _totalBytes, speed);
        }
    }

    public void RollbackBytes(long bytes)
    {
        if (bytes <= 0) return;
        lock (_lock)
        {
            _totalDownloaded = Math.Max(0, _totalDownloaded - bytes);
            long remaining = bytes;
            while (remaining > 0 && _samples.Last is { } last)
            {
                if (last.Value.Bytes <= remaining)
                {
                    remaining -= last.Value.Bytes;
                    _windowBytes -= last.Value.Bytes;
                    _samples.RemoveLast();
                }
                else
                {
                    last.Value = (last.Value.TimeSeconds, last.Value.Bytes - remaining);
                    _windowBytes -= remaining;
                    remaining = 0;
                }
            }
            if (_windowBytes < 0)
            {
                _windowBytes = 0;
            }
        }
    }

    public CloudDownloadProgress Complete()
    {
        lock (_lock)
        {
            if (_totalBytes is null && _totalDownloaded > 0)
            {
                _totalBytes = _totalDownloaded;
            }
        }
        return RecordBytes(0, forceEmit: true)!;
    }
}

/// <summary>
/// LRU disk cache for cloud audio streams, capped at 500 MB by default.
/// Downloads streams atomically (using 4x parallel 4 MB bounded Range chunks for large streams)
/// and coalesces concurrent fetches for the same track ID.
/// </summary>
public sealed class CloudAudioCache
{
    public const long DefaultMaxBytes = 500L * 1024 * 1024;
    internal const long ParallelChunkThresholdBytes = 4L * 1024L * 1024L;
    internal const long ChunkSizeBytes = 4L * 1024L * 1024L;
    internal const int MaxParallelChunks = 4;

    private readonly long _maxBytes;
    private readonly HttpMessageHandler? _handler;
    private readonly Dictionary<string, InFlightDownload> _inFlight = new(StringComparer.Ordinal);
    private readonly object _inFlightLock = new();
    private readonly object _evictLock = new();

    private sealed class InFlightDownload
    {
        private readonly CancellationTokenSource _cts = new();
        private readonly TaskCompletionSource<string> _tcs = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly object _lock = new();
        private readonly List<Action<CloudDownloadProgress>> _progressCallbacks = new();
        private CloudDownloadProgress? _latestProgress;
        private int _waiters;
        private bool _disposed;

        public CancellationToken Token => _cts.Token;
        public Task<string> Task => _tcs.Task;

        public bool TryAddWaiter(Action<CloudDownloadProgress>? onProgress = null)
        {
            CloudDownloadProgress? replay = null;
            lock (_lock)
            {
                if (_disposed || _cts.IsCancellationRequested || _tcs.Task.IsCompleted)
                {
                    return false;
                }
                _waiters++;
                if (onProgress is not null)
                {
                    _progressCallbacks.Add(onProgress);
                    replay = _latestProgress;
                }
            }

            if (replay is not null && onProgress is not null)
            {
                try { onProgress(replay); } catch { /* ignore observer errors */ }
            }
            return true;
        }

        public void RemoveProgressCallback(Action<CloudDownloadProgress>? onProgress)
        {
            if (onProgress is null) return;
            lock (_lock)
            {
                _progressCallbacks.Remove(onProgress);
            }
        }

        public void ReportProgress(CloudDownloadProgress progress)
        {
            Action<CloudDownloadProgress>[] snapshot;
            lock (_lock)
            {
                if (_disposed) return;
                _latestProgress = progress;
                if (_progressCallbacks.Count == 0) return;
                snapshot = _progressCallbacks.ToArray();
            }

            foreach (var cb in snapshot)
            {
                try { cb(progress); } catch { /* ignore observer errors */ }
            }
        }

        public bool ReleaseWaiter(bool cancelIfNoWaiters, Action<CloudDownloadProgress>? onProgress = null)
        {
            bool shouldCancel = false;
            lock (_lock)
            {
                if (onProgress is not null)
                {
                    _progressCallbacks.Remove(onProgress);
                }
                if (_waiters > 0)
                {
                    _waiters--;
                }
                if (cancelIfNoWaiters && _waiters == 0 && !_disposed && !_tcs.Task.IsCompleted)
                {
                    shouldCancel = true;
                }
            }

            if (shouldCancel)
            {
                try { _cts.Cancel(); } catch (ObjectDisposedException) { }
            }

            return shouldCancel;
        }

        public void TrySetResult(string path) => _tcs.TrySetResult(path);
        public void TrySetException(Exception ex) => _tcs.TrySetException(ex);
        public void TrySetCanceled(CancellationToken token) => _tcs.TrySetCanceled(token);

        public void Dispose()
        {
            lock (_lock)
            {
                if (_disposed) return;
                _disposed = true;
                _progressCallbacks.Clear();
            }
            _cts.Dispose();
        }
    }

    public CloudAudioCache(string directoryPath, long maxBytes = DefaultMaxBytes, HttpMessageHandler? handler = null)
    {
        DirectoryPath = directoryPath;
        _maxBytes = Math.Max(maxBytes, 1024);
        _handler = handler;
    }

    public string DirectoryPath { get; }

    public static string? SanitizeQualityKey(string? quality)
    {
        if (string.IsNullOrWhiteSpace(quality)) return null;
        string trimmed = quality.Trim();
        Span<char> buf = trimmed.Length <= 64 ? stackalloc char[trimmed.Length] : new char[trimmed.Length];
        for (int i = 0; i < trimmed.Length; i++)
        {
            char c = trimmed[i];
            buf[i] = (char.IsAsciiLetterOrDigit(c) || c == '-' || c == '_') ? c : '_';
        }
        string cleaned = new string(buf).Trim('_');
        return cleaned.Length > 0 ? cleaned : "default";
    }

    public static string BuildCacheFileStem(string trackId, string? quality = null)
    {
        string safeTrack = Persistence.SafeTrackFileKey(trackId);
        string? safeQuality = SanitizeQualityKey(quality);
        return safeQuality is null ? safeTrack : $"{safeTrack}__{safeQuality}";
    }

    private static string BuildInFlightKey(string trackId, string? quality)
    {
        string? safeQuality = SanitizeQualityKey(quality);
        return safeQuality is null ? trackId : $"{trackId}\u001F{safeQuality}";
    }

    public string? TryGetCachedPath(string trackId, string? quality = null, bool fallbackToLegacy = true)
    {
        if (string.IsNullOrWhiteSpace(trackId) || !Directory.Exists(DirectoryPath))
        {
            return null;
        }

        string? hit = TryFindNonEmptyCacheFile(BuildCacheFileStem(trackId, quality));
        if (hit is not null)
        {
            return hit;
        }

        string safeTrack = Persistence.SafeTrackFileKey(trackId);
        if (string.IsNullOrWhiteSpace(quality))
        {
            foreach (string file in Directory.EnumerateFiles(DirectoryPath, $"{safeTrack}__*.*"))
            {
                if (TryAcceptCachedFile(file)) return file;
            }
            return null;
        }

        if (fallbackToLegacy)
        {
            foreach (string file in Directory.EnumerateFiles(DirectoryPath, $"{safeTrack}.*"))
            {
                if (!Path.GetFileNameWithoutExtension(file).Equals(safeTrack, StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }
                if (TryAcceptCachedFile(file)) return file;
            }
        }

        return null;
    }

    private string? TryFindNonEmptyCacheFile(string stem)
    {
        foreach (string file in Directory.EnumerateFiles(DirectoryPath, $"{stem}.*"))
        {
            if (TryAcceptCachedFile(file)) return file;
        }
        return null;
    }

    private static bool TryAcceptCachedFile(string file)
    {
        if (file.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase)) return false;
        var info = new FileInfo(file);
        if (!info.Exists || info.Length <= 0) return false;
        try
        {
            File.SetLastAccessTimeUtc(file, DateTime.UtcNow);
        }
        catch
        {
            // Best-effort LRU touch.
        }
        return true;
    }

    public async Task<string> GetOrDownloadAsync(
        string trackId,
        Func<CancellationToken, Task<CloudAudioStream>> resolver,
        string? proxyUrl = null,
        CancellationToken ct = default,
        Action<CloudDownloadProgress>? onProgress = null,
        string? quality = null,
        bool forceRefresh = false)
    {
        ct.ThrowIfCancellationRequested();

        if (!forceRefresh)
        {
            string? cached = TryGetCachedPath(trackId, quality);
            if (cached is not null)
            {
                if (onProgress is not null)
                {
                    try
                    {
                        long len = new FileInfo(cached).Length;
                        onProgress(new CloudDownloadProgress(len, len, 0));
                    }
                    catch
                    {
                        // Ignore stat failure on cache hit.
                    }
                }
                return cached;
            }
        }

        string inFlightKey = BuildInFlightKey(trackId, quality);

        // Coalesce concurrent requests (e.g. prefetch + immediate play) per trackId + quality,
        // cancelling the underlying download only once all waiters cancel.
        InFlightDownload entry;
        bool isOwner = false;
        lock (_inFlightLock)
        {
            if (!forceRefresh &&
                _inFlight.TryGetValue(inFlightKey, out var existing) &&
                existing.TryAddWaiter(onProgress))
            {
                entry = existing;
            }
            else
            {
                entry = new InFlightDownload();
                entry.TryAddWaiter(onProgress);
                _inFlight[inFlightKey] = entry;
                isOwner = true;
            }
        }

        if (isOwner)
        {
            _ = RunAndCleanupAsync(trackId, quality, inFlightKey, forceRefresh, entry, resolver, proxyUrl);
        }

        bool released = false;
        try
        {
            return await entry.Task.WaitAsync(ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            released = true;
            if (entry.ReleaseWaiter(cancelIfNoWaiters: true, onProgress))
            {
                lock (_inFlightLock)
                {
                    if (_inFlight.TryGetValue(inFlightKey, out var current) && ReferenceEquals(current, entry))
                    {
                        _inFlight.Remove(inFlightKey);
                    }
                }
            }
            throw;
        }
        finally
        {
            if (!released)
            {
                entry.ReleaseWaiter(cancelIfNoWaiters: false, onProgress);
            }
        }
    }

    private async Task RunAndCleanupAsync(
        string trackId,
        string? quality,
        string inFlightKey,
        bool forceRefresh,
        InFlightDownload entry,
        Func<CancellationToken, Task<CloudAudioStream>> resolver,
        string? proxyUrl)
    {
        try
        {
            string path = await DownloadCoreAsync(
                trackId,
                quality,
                forceRefresh,
                resolver,
                proxyUrl,
                entry.ReportProgress,
                entry.Token).ConfigureAwait(false);
            entry.TrySetResult(path);
        }
        catch (OperationCanceledException oce) when (entry.Token.IsCancellationRequested)
        {
            entry.TrySetCanceled(oce.CancellationToken.IsCancellationRequested ? oce.CancellationToken : entry.Token);
        }
        catch (Exception ex)
        {
            entry.TrySetException(ex);
        }
        finally
        {
            lock (_inFlightLock)
            {
                if (_inFlight.TryGetValue(inFlightKey, out var current) && ReferenceEquals(current, entry))
                {
                    _inFlight.Remove(inFlightKey);
                }
            }
            entry.Dispose();
        }
    }

    private async Task<string> DownloadCoreAsync(
        string trackId,
        string? quality,
        bool forceRefresh,
        Func<CancellationToken, Task<CloudAudioStream>> resolver,
        string? proxyUrl,
        Action<CloudDownloadProgress> onProgress,
        CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();

        if (!forceRefresh)
        {
            string? cached = TryGetCachedPath(trackId, quality);
            if (cached is not null)
            {
                try
                {
                    long len = new FileInfo(cached).Length;
                    onProgress(new CloudDownloadProgress(len, len, 0));
                }
                catch
                {
                    // Ignore stat failure on cache hit.
                }
                return cached;
            }
        }

        var streamInfo = await resolver(ct).ConfigureAwait(false);
        string ext = string.IsNullOrWhiteSpace(streamInfo.Extension)
            ? ".m4a"
            : streamInfo.Extension.StartsWith('.') ? streamInfo.Extension : "." + streamInfo.Extension;

        Directory.CreateDirectory(DirectoryPath);
        string targetPath = Path.Combine(DirectoryPath, $"{BuildCacheFileStem(trackId, quality)}{ext}");
        string tempPath = $"{targetPath}.{Guid.NewGuid():N}.tmp";

        using var http = CloudHttpHelper.CreateClient(proxyUrl, _handler, TimeSpan.FromMinutes(3));

        try
        {
            if (streamInfo.ContentLength is long totalBytes && totalBytes > ParallelChunkThresholdBytes)
            {
                await DownloadChunkedAsync(http, streamInfo, totalBytes, tempPath, onProgress, ct).ConfigureAwait(false);
            }
            else
            {
                await DownloadSingleAsync(http, streamInfo, tempPath, onProgress, ct).ConfigureAwait(false);
            }

            var fi = new FileInfo(tempPath);
            if (!fi.Exists || fi.Length == 0)
            {
                throw new InvalidOperationException("Downloaded cloud audio stream was empty.");
            }

            File.Move(tempPath, targetPath, overwrite: true);
            File.SetLastAccessTimeUtc(targetPath, DateTime.UtcNow);
            EvictStaleQualityVariants(trackId, keepPath: targetPath);
            EvictIfNeeded(protectedPath: targetPath);
            return targetPath;
        }
        catch
        {
            try { if (File.Exists(tempPath)) File.Delete(tempPath); } catch { /* best effort */ }
            throw;
        }
    }

    private static async Task DownloadSingleAsync(
        HttpClient http,
        CloudAudioStream streamInfo,
        string tempPath,
        Action<CloudDownloadProgress> onProgress,
        CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, streamInfo.Url);
        if (streamInfo.Headers is not null)
        {
            foreach (var kvp in streamInfo.Headers)
            {
                req.Headers.TryAddWithoutValidation(kvp.Key, kvp.Value);
            }
        }

        using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);
        if (resp.StatusCode is not (HttpStatusCode.OK or HttpStatusCode.PartialContent))
        {
            resp.EnsureSuccessStatusCode();
        }

        ValidateNonHtmlMediaType(resp);

        long? totalBytes = streamInfo.ContentLength ?? resp.Content.Headers.ContentLength;
        var meter = new DownloadSpeedMeter(totalBytes);

        await using var remote = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        await using var local = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None, 81920, useAsync: true);

        byte[] buffer = ArrayPool<byte>.Shared.Rent(81920);
        try
        {
            while (true)
            {
                int read = await remote.ReadAsync(buffer.AsMemory(0, buffer.Length), ct).ConfigureAwait(false);
                if (read <= 0) break;

                await local.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
                if (meter.RecordBytes(read) is { } progress)
                {
                    onProgress(progress);
                }
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }

        await local.FlushAsync(ct).ConfigureAwait(false);
        onProgress(meter.Complete());
    }

    private static async Task DownloadChunkedAsync(
        HttpClient http,
        CloudAudioStream streamInfo,
        long totalBytes,
        string tempPath,
        Action<CloudDownloadProgress> onProgress,
        CancellationToken ct)
    {
        var ranges = new List<(long Start, long End)>();
        for (long start = 0; start < totalBytes; start += ChunkSizeBytes)
        {
            long end = Math.Min(start + ChunkSizeBytes - 1, totalBytes - 1);
            ranges.Add((start, end));
        }

        using SafeFileHandle handle = File.OpenHandle(
            tempPath,
            FileMode.Create,
            FileAccess.ReadWrite,
            FileShare.ReadWrite,
            FileOptions.Asynchronous);
        RandomAccess.SetLength(handle, totalBytes);

        var meter = new DownloadSpeedMeter(totalBytes);
        var parallelOptions = new ParallelOptions
        {
            MaxDegreeOfParallelism = MaxParallelChunks,
            CancellationToken = ct
        };

        await Parallel.ForEachAsync(ranges, parallelOptions, async (range, chunkCt) =>
        {
            await DownloadChunkWithRetryAsync(http, handle, streamInfo, range.Start, range.End, meter, onProgress, chunkCt)
                .ConfigureAwait(false);
        }).ConfigureAwait(false);

        onProgress(meter.Complete());
    }

    private static async Task DownloadChunkWithRetryAsync(
        HttpClient http,
        SafeFileHandle handle,
        CloudAudioStream streamInfo,
        long start,
        long end,
        DownloadSpeedMeter meter,
        Action<CloudDownloadProgress> onProgress,
        CancellationToken ct)
    {
        for (int attempt = 0; ; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            long attemptBytes = 0;
            try
            {
                await DownloadChunkOnceAsync(
                    http,
                    handle,
                    streamInfo,
                    start,
                    end,
                    read =>
                    {
                        attemptBytes += read;
                        if (meter.RecordBytes(read) is { } progress)
                        {
                            onProgress(progress);
                        }
                    },
                    ct).ConfigureAwait(false);
                return;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                if (attemptBytes > 0)
                {
                    meter.RollbackBytes(attemptBytes);
                }
                throw;
            }
            catch (Exception ex) when (attempt == 0 && IsTransientChunkError(ex, ct))
            {
                if (attemptBytes > 0)
                {
                    meter.RollbackBytes(attemptBytes);
                }
                await Task.Delay(50, ct).ConfigureAwait(false);
            }
        }
    }

    private static bool IsTransientChunkError(Exception ex, CancellationToken ct) =>
        ex is HttpRequestException or IOException ||
        (ex is OperationCanceledException && !ct.IsCancellationRequested);

    private static async Task DownloadChunkOnceAsync(
        HttpClient http,
        SafeFileHandle handle,
        CloudAudioStream streamInfo,
        long start,
        long end,
        Action<long>? onBytesWritten,
        CancellationToken ct)
    {
        using var req = new HttpRequestMessage(HttpMethod.Get, streamInfo.Url);
        if (streamInfo.Headers is not null)
        {
            foreach (var kvp in streamInfo.Headers)
            {
                if (string.Equals(kvp.Key, "Range", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }
                req.Headers.TryAddWithoutValidation(kvp.Key, kvp.Value);
            }
        }
        req.Headers.Range = new RangeHeaderValue(start, end);

        using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);

        if (resp.StatusCode is not (HttpStatusCode.OK or HttpStatusCode.PartialContent))
        {
            throw new HttpRequestException(
                $"Unexpected HTTP status {(int)resp.StatusCode} ({resp.StatusCode}) for chunk [{start}-{end}].",
                null,
                resp.StatusCode);
        }

        ValidateNonHtmlMediaType(resp);

        long expectedLength = end - start + 1;
        if (resp.StatusCode == HttpStatusCode.OK &&
            resp.Content.Headers.ContentRange is null &&
            resp.Content.Headers.ContentLength is long fullLen &&
            fullLen != expectedLength)
        {
            throw new InvalidOperationException(
                $"Server ignored Range header for chunk [{start}-{end}] (returned HTTP 200 with Content-Length {fullLen}).");
        }

        await using var remote = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        long writeOffset = start;
        long bytesWritten = 0;
        byte[] buffer = ArrayPool<byte>.Shared.Rent(81920);
        try
        {
            while (true)
            {
                int read = await remote.ReadAsync(buffer.AsMemory(0, buffer.Length), ct).ConfigureAwait(false);
                if (read <= 0) break;

                if (bytesWritten + read > expectedLength)
                {
                    throw new InvalidOperationException(
                        $"Chunk [{start}-{end}] returned more bytes than requested ({bytesWritten + read} > {expectedLength}).");
                }

                await RandomAccess.WriteAsync(handle, buffer.AsMemory(0, read), writeOffset, ct)
                    .ConfigureAwait(false);
                writeOffset += read;
                bytesWritten += read;
                onBytesWritten?.Invoke(read);
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }

        if (bytesWritten != expectedLength)
        {
            throw new IOException(
                $"Incomplete chunk [{start}-{end}]: received {bytesWritten} of {expectedLength} bytes.");
        }
    }

    private static void ValidateNonHtmlMediaType(HttpResponseMessage resp)
    {
        string? mediaType = resp.Content.Headers.ContentType?.MediaType;
        if (!string.IsNullOrWhiteSpace(mediaType) &&
            (mediaType.Equals("text/html", StringComparison.OrdinalIgnoreCase) ||
             mediaType.Equals("application/xhtml+xml", StringComparison.OrdinalIgnoreCase)))
        {
            throw new InvalidOperationException("Cloud audio stream returned an HTML page instead of audio.");
        }
    }

    public void EvictIfNeeded(string? protectedPath = null)
    {
        lock (_evictLock)
        {
            if (!Directory.Exists(DirectoryPath)) return;

            var entries = new DirectoryInfo(DirectoryPath)
                .EnumerateFiles()
                .Where(f => !f.Name.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase))
                .Select(f => new
                {
                    File = f,
                    LastUsed = f.LastAccessTimeUtc >= f.LastWriteTimeUtc ? f.LastAccessTimeUtc : f.LastWriteTimeUtc
                })
                .OrderBy(e => e.LastUsed)
                .ThenBy(e => e.File.Name, StringComparer.Ordinal)
                .ToList();

            long total = entries.Sum(e => e.File.Length);
            if (total <= _maxBytes) return;

            foreach (var entry in entries)
            {
                if (total <= _maxBytes) break;
                if (protectedPath is not null &&
                    string.Equals(entry.File.FullName, protectedPath, StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                long len = entry.File.Length;
                try
                {
                    entry.File.Delete();
                    total -= len;
                }
                catch
                {
                    // Skip files currently locked by NAudio playback.
                }
            }
        }
    }

    public void EvictStaleQualityVariants(string trackId, string? keepPath = null)
    {
        if (string.IsNullOrWhiteSpace(trackId) || !Directory.Exists(DirectoryPath)) return;
        string fileKey = Persistence.SafeTrackFileKey(trackId);
        string? normalizedKeep = string.IsNullOrWhiteSpace(keepPath) ? null : Path.GetFullPath(keepPath);

        lock (_evictLock)
        {
            foreach (string pattern in (string[])[$"{fileKey}.*", $"{fileKey}__*"])
            {
                foreach (string file in Directory.EnumerateFiles(DirectoryPath, pattern))
                {
                    if (file.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase)) continue;
                    if (normalizedKeep is not null &&
                        string.Equals(Path.GetFullPath(file), normalizedKeep, StringComparison.OrdinalIgnoreCase))
                    {
                        continue;
                    }
                    try { File.Delete(file); } catch { /* ignore locked by playback */ }
                }
            }
        }
    }

    public void RemoveForTracks(IEnumerable<string> trackIds)
    {
        if (!Directory.Exists(DirectoryPath)) return;
        foreach (string id in trackIds)
        {
            if (string.IsNullOrWhiteSpace(id)) continue;
            string fileKey = Persistence.SafeTrackFileKey(id);
            foreach (string pattern in (string[])[$"{fileKey}.*", $"{fileKey}__*"])
            {
                foreach (string file in Directory.EnumerateFiles(DirectoryPath, pattern))
                {
                    try { File.Delete(file); } catch { /* ignore locked */ }
                }
            }
        }
    }
}
