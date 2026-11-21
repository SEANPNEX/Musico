using TagLib;

namespace MusicoWindows.Core;

public sealed record TrackMetadata(
    string? Title = null,
    string? Artist = null,
    string? Album = null,
    string? AlbumArtist = null,
    int? TrackNumber = null,
    int? DiscNumber = null,
    double DurationSeconds = 0,
    byte[]? ArtworkBytes = null,
    string? Lyrics = null);

public interface IMetadataReader
{
    Task<TrackMetadata> ReadAsync(string filePath, CancellationToken ct = default);
}

public sealed class TagLibMetadataReader : IMetadataReader
{
    public Task<TrackMetadata> ReadAsync(string filePath, CancellationToken ct = default)
    {
        return Task.Run(() => ReadSync(filePath), ct);
    }

    private static TrackMetadata ReadSync(string filePath)
    {
        try
        {
            using var file = TagLib.File.Create(filePath);
            double duration = file.Properties?.Duration.TotalSeconds ?? 0;
            if (!double.IsFinite(duration) || duration <= 0) duration = 0;

            var tag = file.Tag;
            if (tag is null)
            {
                return new TrackMetadata(DurationSeconds: duration);
            }

            byte[]? artwork = null;
            if (tag.Pictures is { Length: > 0 } pictures)
            {
                var front = pictures.FirstOrDefault(p => p.Type == PictureType.FrontCover && p.Data?.Data is { Length: > 0 });
                var chosen = front ?? pictures.FirstOrDefault(p => p.Data?.Data is { Length: > 0 });
                if (chosen?.Data?.Data is { Length: > 0 } bytes)
                {
                    artwork = bytes;
                }
            }

            return new TrackMetadata(
                Title: Clean(tag.Title),
                Artist: Clean(tag.FirstPerformer),
                Album: Clean(tag.Album),
                AlbumArtist: Clean(tag.FirstAlbumArtist),
                TrackNumber: tag.Track > 0 ? (int)tag.Track : null,
                DiscNumber: tag.Disc > 0 ? (int)tag.Disc : null,
                DurationSeconds: duration,
                ArtworkBytes: artwork,
                Lyrics: Clean(tag.Lyrics));
        }
        catch
        {
            return new TrackMetadata();
        }
    }

    private static string? Clean(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        string trimmed = value.Trim();
        return trimmed.Length == 0 ? null : trimmed;
    }
}
