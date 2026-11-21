using MusicoWindows.Core;
using MusicoWindows.Models;

namespace MusicoWindows.Tests;

internal static class TestHelpers
{
    public static string MakeTempDir()
    {
        string path = Path.Combine(Path.GetTempPath(), $"musico-tests-{Guid.NewGuid():N}");
        Directory.CreateDirectory(path);
        return path;
    }

    public static void TouchFile(string root, string relativePath, string content = "x")
    {
        string full = Path.Combine(root, relativePath.Replace('/', Path.DirectorySeparatorChar));
        string? dir = Path.GetDirectoryName(full);
        if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
        File.WriteAllText(full, content);
    }

    public static Track MakeTrack(
        string relativePath,
        Guid? rootId = null,
        string? title = null,
        string artist = "A",
        string album = "B",
        int? trackNumber = null,
        int? discNumber = null)
    {
        Guid rId = rootId ?? Guid.NewGuid();
        return new Track(
            Id: Track.MakeId(rId, relativePath),
            RootId: rId,
            RelativePath: relativePath,
            Title: title ?? relativePath,
            Artist: artist,
            Album: album,
            AlbumArtist: artist,
            TrackNumber: trackNumber,
            DiscNumber: discNumber,
            DurationSeconds: 10,
            FileSize: 1,
            ModifiedUtc: DateTimeOffset.UnixEpoch,
            HasEmbeddedLyrics: false,
            DateAddedUtc: DateTimeOffset.UnixEpoch);
    }
}

internal sealed class StubMetadataReader : IMetadataReader
{
    private readonly object _lock = new();
    private int _calls;

    public Dictionary<string, TrackMetadata> ByFileName { get; } = new(StringComparer.Ordinal);
    public int DelayMs { get; set; }

    public int Calls
    {
        get { lock (_lock) return _calls; }
    }

    public async Task<TrackMetadata> ReadAsync(string filePath, CancellationToken ct = default)
    {
        lock (_lock) _calls++;
        if (DelayMs > 0)
        {
            await Task.Delay(DelayMs, ct);
        }

        string name = Path.GetFileName(filePath);
        return ByFileName.TryGetValue(name, out var meta)
            ? meta
            : new TrackMetadata(DurationSeconds: 5);
    }
}
