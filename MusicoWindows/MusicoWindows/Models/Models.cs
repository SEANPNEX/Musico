using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Serialization;

namespace MusicoWindows.Models;

public enum RootKind
{
    Picked,
    AppFolder
}

public sealed record LibraryRoot(
    Guid Id,
    string Name,
    RootKind Kind,
    string? Path)
{
    public static readonly Guid AppFolderId = Guid.Parse("00000000-0000-0000-0000-00000000A990");
}

public sealed record Track(
    string Id,
    Guid RootId,
    string RelativePath,
    string Title,
    string Artist,
    string Album,
    string AlbumArtist,
    int? TrackNumber,
    int? DiscNumber,
    double DurationSeconds,
    long FileSize,
    DateTimeOffset ModifiedUtc,
    bool HasEmbeddedLyrics,
    DateTimeOffset DateAddedUtc,
    string? ArtworkKey = null)
{
    /// <summary>Folder part of RelativePath (empty string for files directly in the root).</summary>
    [JsonIgnore]
    public string FolderPath
    {
        get
        {
            int slash = RelativePath.LastIndexOf('/');
            return slash < 0 ? string.Empty : RelativePath[..slash];
        }
    }

    [JsonIgnore]
    public string FileName
    {
        get
        {
            int slash = RelativePath.LastIndexOf('/');
            return slash < 0 ? RelativePath : RelativePath[(slash + 1)..];
        }
    }

    public static string MakeId(Guid rootId, string relativePath)
    {
        byte[] digest = SHA256.HashData(Encoding.UTF8.GetBytes($"{rootId:D}/{relativePath}"));
        return Convert.ToHexString(digest).ToLowerInvariant();
    }
}

public sealed record AlbumInfo(
    string Id,
    string Title,
    string Artist,
    IReadOnlyList<string> TrackIds,
    string? ArtworkKey = null);

public sealed record Playlist(
    Guid Id,
    string Name,
    List<string> TrackIds);

public enum RepeatMode
{
    Off,
    All,
    One
}

public sealed class EqSettings
{
    public static readonly float[] Frequencies =
        [31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f];

    public const float MinGainDb = -12f;
    public const float MaxGainDb = 12f;

    public bool Enabled { get; set; }
    public float PreampDb { get; set; }
    public float[] Gains { get; set; } = new float[Frequencies.Length];
    public string? PresetName { get; set; } = "Flat";

    public EqSettings Clone()
    {
        var copy = new float[Frequencies.Length];
        if (Gains is { Length: > 0 })
        {
            Array.Copy(Gains, copy, Math.Min(Gains.Length, copy.Length));
        }
        return new EqSettings
        {
            Enabled = Enabled,
            PreampDb = PreampDb,
            Gains = copy,
            PresetName = PresetName
        };
    }
}

public sealed record EqPreset(string Name, float[] Gains)
{
    public static IReadOnlyList<EqPreset> All { get; } =
    [
        new("Flat",         [ 0f,  0f,  0f,  0f,  0f,  0f,  0f,  0f,  0f,  0f]),
        new("Bass Boost",   [ 6f,  5f,  4f,  2f,  0f,  0f,  0f,  0f,  0f,  0f]),
        new("Treble Boost", [ 0f,  0f,  0f,  0f,  0f,  1f,  2f,  4f,  5f,  6f]),
        new("Rock",         [ 5f,  4f,  3f,  1f, -1f, -1f,  1f,  3f,  4f,  5f]),
        new("Pop",          [-1f,  0f,  2f,  4f,  5f,  4f,  2f,  0f, -1f, -1f]),
        new("Jazz",         [ 4f,  3f,  1f,  2f, -2f, -2f,  0f,  1f,  3f,  4f]),
        new("Classical",    [ 4f,  3f,  2f,  1f, -1f, -1f,  0f,  2f,  3f,  4f]),
        new("Vocal",        [-2f, -2f, -1f,  1f,  4f,  4f,  3f,  1f,  0f, -1f]),
    ];
}

public sealed record LyricLine(int Id, double? TimeSeconds, string Text);

public sealed record Lyrics(IReadOnlyList<LyricLine> Lines, bool IsTimed)
{
    /// <summary>Index of the line being sung at <paramref name="timeSeconds"/>, or null before the first line / for untimed lyrics.</summary>
    public int? LineIndexAt(double timeSeconds)
    {
        if (!IsTimed) return null;
        int? result = null;
        for (int i = 0; i < Lines.Count; i++)
        {
            if (Lines[i].TimeSeconds is not double start || start > timeSeconds) break;
            result = i;
        }
        return result;
    }
}

public enum LyricsSource
{
    Imported,
    Sidecar,
    Embedded
}

public sealed record PlayHistoryEntry(int Count, DateTimeOffset LastPlayedUtc);

public enum SleepModeKind
{
    Off,
    Until,
    EndOfTrack
}

public sealed record SleepState(SleepModeKind Kind, DateTimeOffset? UntilUtc = null)
{
    public static SleepState Off { get; } = new(SleepModeKind.Off);
    public static SleepState EndOfTrack { get; } = new(SleepModeKind.EndOfTrack);
    public static SleepState Until(DateTimeOffset untilUtc) => new(SleepModeKind.Until, untilUtc);
}
