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
    string? Lyrics = null,
    string? Genre = null);

public sealed record AudioFileDetails(
    int? BitDepth = null,
    int? SampleRateHz = null,
    int? BitRateKbps = null,
    int? Channels = null,
    double DurationSeconds = 0,
    long? FileSize = null);

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
                Lyrics: Clean(tag.Lyrics),
                Genre: Clean(tag.FirstGenre));
        }
        catch
        {
            return new TrackMetadata();
        }
    }

    public static AudioFileDetails ReadAudioDetails(string? filePath)
    {
        if (string.IsNullOrWhiteSpace(filePath) || !System.IO.File.Exists(filePath))
        {
            return new AudioFileDetails();
        }

        long? fileSize = null;
        try { fileSize = new FileInfo(filePath).Length; } catch { /* ignore */ }

        int? bitDepth = null;
        int? sampleRate = null;
        int? bitRate = null;
        int? channels = null;
        double duration = 0;

        try
        {
            using var file = TagLib.File.Create(filePath);
            if (file.Properties is { } props)
            {
                if (props.BitsPerSample > 0) bitDepth = props.BitsPerSample;
                if (props.AudioSampleRate > 0) sampleRate = props.AudioSampleRate;
                if (props.AudioBitrate > 0) bitRate = props.AudioBitrate;
                if (props.AudioChannels > 0) channels = props.AudioChannels;
                double d = props.Duration.TotalSeconds;
                if (double.IsFinite(d) && d > 0) duration = d;
            }
        }
        catch
        {
            // Ignore unreadable headers; return whatever FileInfo gave us.
        }

#if WINDOWS
        if (bitDepth is null && string.Equals(Path.GetExtension(filePath), ".wav", StringComparison.OrdinalIgnoreCase))
        {
            try
            {
                using var wav = new NAudio.Wave.WaveFileReader(filePath);
                if (wav.WaveFormat.BitsPerSample > 0) bitDepth = wav.WaveFormat.BitsPerSample;
                sampleRate ??= wav.WaveFormat.SampleRate > 0 ? wav.WaveFormat.SampleRate : null;
                channels ??= wav.WaveFormat.Channels > 0 ? wav.WaveFormat.Channels : null;
                if (duration <= 0 && wav.TotalTime.TotalSeconds > 0) duration = wav.TotalTime.TotalSeconds;
            }
            catch
            {
                // Best effort only.
            }
        }
#endif

        return new AudioFileDetails(bitDepth, sampleRate, bitRate, channels, duration, fileSize);
    }

    public static bool TryWriteTags(
        string filePath,
        string title,
        string artist,
        string album,
        string? genre,
        byte[]? artworkBytes = null)
    {
        if (string.IsNullOrWhiteSpace(filePath) || !System.IO.File.Exists(filePath))
        {
            return false;
        }

        try
        {
            var attrs = System.IO.File.GetAttributes(filePath);
            if ((attrs & FileAttributes.ReadOnly) != 0)
            {
                return false;
            }

            using var file = TagLib.File.Create(filePath);
            if (file.Tag is null) return false;

            file.Tag.Title = title;
            file.Tag.Performers = string.IsNullOrWhiteSpace(artist) ? Array.Empty<string>() : [artist];
            file.Tag.Album = album;
            file.Tag.Genres = string.IsNullOrWhiteSpace(genre) ? Array.Empty<string>() : [genre];

            if (artworkBytes is { Length: > 0 })
            {
                var picture = new TagLib.Picture(new TagLib.ByteVector(artworkBytes))
                {
                    Type = TagLib.PictureType.FrontCover,
                    MimeType = "image/jpeg",
                    Description = "Cover"
                };
                file.Tag.Pictures = [picture];
            }

            file.Save();
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static string? Clean(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        string trimmed = value.Trim();
        return trimmed.Length == 0 ? null : trimmed;
    }
}
