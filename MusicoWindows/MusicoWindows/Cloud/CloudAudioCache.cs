using System;
using System.Buffers;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;

namespace MusicoWindows.Cloud;

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
        private int _waiters;
        private bool _disposed;

        public CancellationToken Token => _cts.Token;
        public Task<string> Task => _tcs.Task;

        public bool TryAddWaiter()
        {
            lock (_lock)
            {
                if (_disposed || _cts.IsCancellationRequested || _tcs.Task.IsCompleted)
                {
                    return false;
                }
                _waiters++;
                return true;
            }
        }

        public bool ReleaseWaiter(bool cancelIfNoWaiters)
        {
            bool shouldCancel = false;
            lock (_lock)
            {
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

    public string? TryGetCachedPath(string trackId)
    {
        if (string.IsNullOrWhiteSpace(trackId) || !Directory.Exists(DirectoryPath))
        {
            return null;
        }

        foreach (string file in Directory.EnumerateFiles(DirectoryPath, $"{trackId}.*"))
        {
            if (file.EndsWith(".tmp", StringComparison.OrdinalIgnoreCase)) continue;
            var info = new FileInfo(file);
            if (info.Exists && info.Length > 0)
            {
                try
                {
                    File.SetLastAccessTimeUtc(file, DateTime.UtcNow);
                }
                catch
                {
                    // Best-effort LRU touch.
                }
                return file;
            }
        }
        return null;
    }

    public async Task<string> GetOrDownloadAsync(
        string trackId,
        Func<CancellationToken, Task<CloudAudioStream>> resolver,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        ct.ThrowIfCancellationRequested();

        string? cached = TryGetCachedPath(trackId);
        if (cached is not null)
        {
            return cached;
        }

        // Coalesce concurrent requests (e.g. prefetch + immediate play) per trackId,
        // cancelling the underlying download only once all waiters cancel.
        InFlightDownload entry;
        bool isOwner = false;
        lock (_inFlightLock)
        {
            if (_inFlight.TryGetValue(trackId, out var existing) && existing.TryAddWaiter())
            {
                entry = existing;
            }
            else
            {
                entry = new InFlightDownload();
                entry.TryAddWaiter();
                _inFlight[trackId] = entry;
                isOwner = true;
            }
        }

        if (isOwner)
        {
            _ = RunAndCleanupAsync(trackId, entry, resolver, proxyUrl);
        }

        bool released = false;
        try
        {
            return await entry.Task.WaitAsync(ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            released = true;
            if (entry.ReleaseWaiter(cancelIfNoWaiters: true))
            {
                lock (_inFlightLock)
                {
                    if (_inFlight.TryGetValue(trackId, out var current) && ReferenceEquals(current, entry))
                    {
                        _inFlight.Remove(trackId);
                    }
                }
            }
            throw;
        }
        finally
        {
            if (!released)
            {
                entry.ReleaseWaiter(cancelIfNoWaiters: false);
            }
        }
    }

    private async Task RunAndCleanupAsync(
        string trackId,
        InFlightDownload entry,
        Func<CancellationToken, Task<CloudAudioStream>> resolver,
        string? proxyUrl)
    {
        try
        {
            string path = await DownloadCoreAsync(trackId, resolver, proxyUrl, entry.Token).ConfigureAwait(false);
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
                if (_inFlight.TryGetValue(trackId, out var current) && ReferenceEquals(current, entry))
                {
                    _inFlight.Remove(trackId);
                }
            }
            entry.Dispose();
        }
    }

    private async Task<string> DownloadCoreAsync(
        string trackId,
        Func<CancellationToken, Task<CloudAudioStream>> resolver,
        string? proxyUrl,
        CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();

        string? cached = TryGetCachedPath(trackId);
        if (cached is not null)
        {
            return cached;
        }

        var streamInfo = await resolver(ct).ConfigureAwait(false);
        string ext = string.IsNullOrWhiteSpace(streamInfo.Extension)
            ? ".m4a"
            : streamInfo.Extension.StartsWith('.') ? streamInfo.Extension : "." + streamInfo.Extension;

        Directory.CreateDirectory(DirectoryPath);
        string targetPath = Path.Combine(DirectoryPath, $"{trackId}{ext}");
        string tempPath = $"{targetPath}.{Guid.NewGuid():N}.tmp";

        using var http = CloudHttpHelper.CreateClient(proxyUrl, _handler, TimeSpan.FromMinutes(3));

        try
        {
            if (streamInfo.ContentLength is long totalBytes && totalBytes > ParallelChunkThresholdBytes)
            {
                await DownloadChunkedAsync(http, streamInfo, totalBytes, tempPath, ct).ConfigureAwait(false);
            }
            else
            {
                await DownloadSingleAsync(http, streamInfo, tempPath, ct).ConfigureAwait(false);
            }

            var fi = new FileInfo(tempPath);
            if (!fi.Exists || fi.Length == 0)
            {
                throw new InvalidOperationException("Downloaded cloud audio stream was empty.");
            }

            File.Move(tempPath, targetPath, overwrite: true);
            File.SetLastAccessTimeUtc(targetPath, DateTime.UtcNow);
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

        await using var remote = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        await using var local = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None, 81920, useAsync: true);
        await remote.CopyToAsync(local, ct).ConfigureAwait(false);
        await local.FlushAsync(ct).ConfigureAwait(false);
    }

    private static async Task DownloadChunkedAsync(
        HttpClient http,
        CloudAudioStream streamInfo,
        long totalBytes,
        string tempPath,
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

        var parallelOptions = new ParallelOptions
        {
            MaxDegreeOfParallelism = MaxParallelChunks,
            CancellationToken = ct
        };

        await Parallel.ForEachAsync(ranges, parallelOptions, async (range, chunkCt) =>
        {
            await DownloadChunkWithRetryAsync(http, handle, streamInfo, range.Start, range.End, chunkCt)
                .ConfigureAwait(false);
        }).ConfigureAwait(false);
    }

    private static async Task DownloadChunkWithRetryAsync(
        HttpClient http,
        SafeFileHandle handle,
        CloudAudioStream streamInfo,
        long start,
        long end,
        CancellationToken ct)
    {
        for (int attempt = 0; ; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                await DownloadChunkOnceAsync(http, handle, streamInfo, start, end, ct).ConfigureAwait(false);
                return;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex) when (attempt == 0 && IsTransientChunkError(ex, ct))
            {
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

    public void RemoveForTracks(IEnumerable<string> trackIds)
    {
        if (!Directory.Exists(DirectoryPath)) return;
        foreach (string id in trackIds)
        {
            if (string.IsNullOrWhiteSpace(id)) continue;
            foreach (string file in Directory.EnumerateFiles(DirectoryPath, $"{id}.*"))
            {
                try { File.Delete(file); } catch { /* ignore locked */ }
            }
        }
    }
}
