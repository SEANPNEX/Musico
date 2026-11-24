using System.Net.Http;
#if WINDOWS
using System.IO;
using System.Windows.Media.Imaging;
#endif

namespace MusicoWindows.Cloud;

/// <summary>
/// Strictly in-memory LRU cache for remote cloud search/preview thumbnails.
/// Only permits <c>https://</c> URLs and never writes browsed artwork to disk.
/// </summary>
public sealed class CloudThumbnailCache
{
    public const int DefaultMaxEntries = 200;
    public const long DefaultMaxBytes = 32L * 1024 * 1024;
    public const int MaxSingleImageBytes = 8 * 1024 * 1024;

    private sealed class ByteEntry
    {
        public required byte[] Bytes { get; init; }
        public required LinkedListNode<string> Node { get; init; }
    }

#if WINDOWS
    private sealed class BitmapEntry
    {
        public required BitmapImage Bitmap { get; init; }
        public required LinkedListNode<string> Node { get; init; }
    }
#endif

    private readonly object _lock = new();
    private readonly Dictionary<string, ByteEntry> _entries = new(StringComparer.Ordinal);
    private readonly LinkedList<string> _lru = new();
    private readonly Dictionary<string, Task<byte[]?>> _inFlight = new(StringComparer.Ordinal);
#if WINDOWS
    private readonly Dictionary<string, BitmapEntry> _bitmaps = new(StringComparer.Ordinal);
    private readonly LinkedList<string> _bitmapLru = new();
#endif
    private readonly HttpMessageHandler? _handler;
    private readonly int _maxEntries;
    private readonly long _maxBytes;
    private long _currentBytes;

    public CloudThumbnailCache(
        int maxEntries = DefaultMaxEntries,
        long maxBytes = DefaultMaxBytes,
        HttpMessageHandler? handler = null)
    {
        _maxEntries = Math.Max(1, maxEntries);
        _maxBytes = Math.Max(1024, maxBytes);
        _handler = handler;
    }

    public int Count
    {
        get
        {
            lock (_lock)
            {
                return _entries.Count;
            }
        }
    }

    public long CurrentBytes
    {
        get
        {
            lock (_lock)
            {
                return _currentBytes;
            }
        }
    }

    public static bool IsAllowedHttpsUrl(string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) return false;
        return Uri.TryCreate(url.Trim(), UriKind.Absolute, out var uri) &&
               string.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase);
    }

    public bool TryGetBytes(string? url, out byte[] bytes)
    {
        if (string.IsNullOrWhiteSpace(url))
        {
            bytes = Array.Empty<byte>();
            return false;
        }

        string key = url.Trim();
        lock (_lock)
        {
            if (_entries.TryGetValue(key, out var entry))
            {
                _lru.Remove(entry.Node);
                _lru.AddFirst(entry.Node);
                bytes = entry.Bytes;
                return true;
            }
        }

        bytes = Array.Empty<byte>();
        return false;
    }

    public async Task<byte[]?> GetBytesAsync(string? url, CancellationToken ct = default)
    {
        if (!IsAllowedHttpsUrl(url))
        {
            return null;
        }

        string key = url!.Trim();
        if (TryGetBytes(key, out byte[] cached))
        {
            return cached;
        }

        Task<byte[]?> sharedTask;
        bool startedNew = false;
        lock (_lock)
        {
            if (_entries.TryGetValue(key, out var entry))
            {
                _lru.Remove(entry.Node);
                _lru.AddFirst(entry.Node);
                return entry.Bytes;
            }

            if (!_inFlight.TryGetValue(key, out sharedTask!))
            {
                startedNew = true;
                sharedTask = DownloadAndStoreAsync(key);
                _inFlight[key] = sharedTask;
            }
        }

        try
        {
            return await sharedTask.WaitAsync(ct).ConfigureAwait(false);
        }
        finally
        {
            if (startedNew)
            {
                lock (_lock)
                {
                    if (_inFlight.TryGetValue(key, out var existing) && ReferenceEquals(existing, sharedTask))
                    {
                        _inFlight.Remove(key);
                    }
                }
            }
        }
    }

#if WINDOWS
    public bool TryGetBitmap(string? url, int decodeSize, out BitmapImage? bitmap)
    {
        bitmap = null;
        if (!IsAllowedHttpsUrl(url)) return false;

        string bmpKey = MakeBitmapKey(url!.Trim(), decodeSize);
        lock (_lock)
        {
            if (_bitmaps.TryGetValue(bmpKey, out var entry))
            {
                _bitmapLru.Remove(entry.Node);
                _bitmapLru.AddFirst(entry.Node);
                bitmap = entry.Bitmap;
                return true;
            }
        }
        return false;
    }

    public async Task<BitmapImage?> GetBitmapAsync(string? url, int decodeSize = 96, CancellationToken ct = default)
    {
        if (!IsAllowedHttpsUrl(url)) return null;
        string trimmed = url!.Trim();
        int clampedSize = Math.Clamp(decodeSize, 24, 512);

        if (TryGetBitmap(trimmed, clampedSize, out var cached) && cached is not null)
        {
            return cached;
        }

        byte[]? bytes = await GetBytesAsync(trimmed, ct).ConfigureAwait(false);
        if (bytes is null || bytes.Length == 0)
        {
            return null;
        }

        BitmapImage? decoded = await Task.Run(() => DecodeFrozenBitmap(bytes, clampedSize), ct).ConfigureAwait(false);
        if (decoded is null)
        {
            return null;
        }

        string bmpKey = MakeBitmapKey(trimmed, clampedSize);
        lock (_lock)
        {
            if (_bitmaps.TryGetValue(bmpKey, out var existing))
            {
                _bitmapLru.Remove(existing.Node);
                _bitmapLru.AddFirst(existing.Node);
                return existing.Bitmap;
            }

            var node = _bitmapLru.AddFirst(bmpKey);
            _bitmaps[bmpKey] = new BitmapEntry { Bitmap = decoded, Node = node };
            while (_bitmaps.Count > _maxEntries && _bitmapLru.Last is { } oldest)
            {
                _bitmapLru.RemoveLast();
                _bitmaps.Remove(oldest.Value);
            }
        }

        return decoded;
    }

    private static string MakeBitmapKey(string url, int decodeSize) => $"{decodeSize}:{url}";

    private static BitmapImage? DecodeFrozenBitmap(byte[] bytes, int decodeSize)
    {
        try
        {
            using var ms = new MemoryStream(bytes, writable: false);
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            bmp.DecodePixelWidth = decodeSize;
            bmp.StreamSource = ms;
            bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch
        {
            return null;
        }
    }
#endif

    public void Clear()
    {
        lock (_lock)
        {
            _entries.Clear();
            _lru.Clear();
            _currentBytes = 0;
#if WINDOWS
            _bitmaps.Clear();
            _bitmapLru.Clear();
#endif
        }
    }

    private async Task<byte[]?> DownloadAndStoreAsync(string url)
    {
        try
        {
            using var client = CloudHttpHelper.CreateClient(proxyUrl: null, _handler, TimeSpan.FromSeconds(15));
            using var resp = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, CancellationToken.None)
                .ConfigureAwait(false);

            if (!resp.IsSuccessStatusCode)
            {
                return null;
            }

            if (resp.RequestMessage?.RequestUri is Uri finalUri &&
                !string.Equals(finalUri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase))
            {
                return null;
            }

            if (resp.Content.Headers.ContentLength is long declaredLen && declaredLen > MaxSingleImageBytes)
            {
                return null;
            }

            await using var stream = await resp.Content.ReadAsStreamAsync(CancellationToken.None).ConfigureAwait(false);
            using var ms = new MemoryStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = await stream.ReadAsync(buffer.AsMemory(0, buffer.Length), CancellationToken.None).ConfigureAwait(false)) > 0)
            {
                if (ms.Length + read > MaxSingleImageBytes)
                {
                    return null;
                }
                ms.Write(buffer, 0, read);
            }

            byte[] bytes = ms.ToArray();
            if (bytes.Length == 0 || bytes.Length > _maxBytes)
            {
                return null;
            }

            StoreBytes(url, bytes);
            return bytes;
        }
        catch
        {
            return null;
        }
    }

    private void StoreBytes(string url, byte[] bytes)
    {
        lock (_lock)
        {
            if (_entries.TryGetValue(url, out var existing))
            {
                _currentBytes -= existing.Bytes.Length;
                _lru.Remove(existing.Node);
                _entries.Remove(url);
            }

            var node = _lru.AddFirst(url);
            _entries[url] = new ByteEntry { Bytes = bytes, Node = node };
            _currentBytes += bytes.Length;

            while ((_entries.Count > _maxEntries || _currentBytes > _maxBytes) && _lru.Last is { } oldest)
            {
                _lru.RemoveLast();
                if (_entries.Remove(oldest.Value, out var evicted))
                {
                    _currentBytes -= evicted.Bytes.Length;
                }
            }
        }
    }
}
