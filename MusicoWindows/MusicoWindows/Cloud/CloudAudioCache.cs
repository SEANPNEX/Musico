using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace MusicoWindows.Cloud;

/// <summary>
/// LRU disk cache for cloud audio streams, capped at 500 MB by default.
/// Downloads streams atomically and coalesces concurrent fetches for the same track ID.
/// </summary>
public sealed class CloudAudioCache
{
    public const long DefaultMaxBytes = 500L * 1024 * 1024;

    private readonly long _maxBytes;
    private readonly HttpMessageHandler? _handler;
    private readonly ConcurrentDictionary<string, Task<string>> _inFlight = new(StringComparer.Ordinal);
    private readonly object _evictLock = new();

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
        string? cached = TryGetCachedPath(trackId);
        if (cached is not null)
        {
            return cached;
        }

        // Coalesce concurrent requests (e.g. prefetch + immediate play) per trackId.
        var sharedTask = _inFlight.GetOrAdd(trackId, _ => DownloadCoreAsync(trackId, resolver, proxyUrl));
        try
        {
            return await sharedTask.WaitAsync(ct).ConfigureAwait(false);
        }
        finally
        {
            if (sharedTask.IsCompleted)
            {
                _inFlight.TryRemove(new KeyValuePair<string, Task<string>>(trackId, sharedTask));
            }
        }
    }

    private async Task<string> DownloadCoreAsync(
        string trackId,
        Func<CancellationToken, Task<CloudAudioStream>> resolver,
        string? proxyUrl)
    {
        string? cached = TryGetCachedPath(trackId);
        if (cached is not null)
        {
            return cached;
        }

        var streamInfo = await resolver(CancellationToken.None).ConfigureAwait(false);
        string ext = string.IsNullOrWhiteSpace(streamInfo.Extension)
            ? ".m4a"
            : streamInfo.Extension.StartsWith('.') ? streamInfo.Extension : "." + streamInfo.Extension;

        Directory.CreateDirectory(DirectoryPath);
        string targetPath = Path.Combine(DirectoryPath, $"{trackId}{ext}");
        string tempPath = $"{targetPath}.{Guid.NewGuid():N}.tmp";

        using var http = CloudHttpHelper.CreateClient(proxyUrl, _handler, TimeSpan.FromMinutes(3));
        using var req = new HttpRequestMessage(HttpMethod.Get, streamInfo.Url);
        if (streamInfo.Headers is not null)
        {
            foreach (var kvp in streamInfo.Headers)
            {
                req.Headers.TryAddWithoutValidation(kvp.Key, kvp.Value);
            }
        }

        try
        {
            using var resp = await http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, CancellationToken.None)
                .ConfigureAwait(false);
            resp.EnsureSuccessStatusCode();

            await using (var remote = await resp.Content.ReadAsStreamAsync(CancellationToken.None).ConfigureAwait(false))
            await using (var local = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None, 81920, useAsync: true))
            {
                await remote.CopyToAsync(local, CancellationToken.None).ConfigureAwait(false);
                await local.FlushAsync(CancellationToken.None).ConfigureAwait(false);
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
