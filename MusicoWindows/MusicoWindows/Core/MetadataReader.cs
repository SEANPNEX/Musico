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

public static class ImageFormatHelper
{
    public const int MaxSidecarImageBytes = 8 * 1024 * 1024;
    private static readonly string[] SiblingCoverExtensions = [".jpg", ".jpeg", ".png", ".webp"];

    public static string DetectMimeType(byte[]? bytes)
    {
        if (bytes is null || bytes.Length == 0)
        {
            return "image/jpeg";
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes.Length >= 8 &&
            bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47 &&
            bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A)
        {
            return "image/png";
        }

        // JPEG: FF D8 FF
        if (bytes.Length >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF)
        {
            return "image/jpeg";
        }

        // WebP: RIFF....WEBP
        if (bytes.Length >= 12 &&
            bytes[0] == (byte)'R' && bytes[1] == (byte)'I' && bytes[2] == (byte)'F' && bytes[3] == (byte)'F' &&
            bytes[8] == (byte)'W' && bytes[9] == (byte)'E' && bytes[10] == (byte)'B' && bytes[11] == (byte)'P')
        {
            return "image/webp";
        }

        // GIF: GIF87a / GIF89a
        if (bytes.Length >= 6 &&
            bytes[0] == (byte)'G' && bytes[1] == (byte)'I' && bytes[2] == (byte)'F' && bytes[3] == (byte)'8' &&
            (bytes[4] == (byte)'7' || bytes[4] == (byte)'9') && bytes[5] == (byte)'a')
        {
            return "image/gif";
        }

        // BMP: BM
        if (bytes.Length >= 2 && bytes[0] == (byte)'B' && bytes[1] == (byte)'M')
        {
            return "image/bmp";
        }

        return "image/jpeg";
    }

    public static string DetectSidecarExtension(byte[]? bytes) => DetectMimeType(bytes) switch
    {
        "image/png" => ".png",
        "image/webp" => ".webp",
        _ => ".jpg"
    };

    public static string? FindSiblingCoverPath(string? audioFilePath)
    {
        if (string.IsNullOrWhiteSpace(audioFilePath)) return null;
        string? dir = Path.GetDirectoryName(audioFilePath);
        string stem = Path.GetFileNameWithoutExtension(audioFilePath);
        if (string.IsNullOrWhiteSpace(stem)) return null;

        foreach (string ext in SiblingCoverExtensions)
        {
            string candidate = string.IsNullOrEmpty(dir)
                ? $"{stem}{ext}"
                : Path.Combine(dir, $"{stem}{ext}");
            try
            {
                var info = new FileInfo(candidate);
                if (info.Exists && info.Length > 0 && info.Length <= MaxSidecarImageBytes)
                {
                    return candidate;
                }
            }
            catch
            {
                // Ignore inaccessible candidate paths.
            }
        }

        return null;
    }

    public static byte[]? ReadSiblingCoverBytes(string? audioFilePath)
    {
        string? path = FindSiblingCoverPath(audioFilePath);
        if (path is null) return null;
        try
        {
            byte[] bytes = System.IO.File.ReadAllBytes(path);
            return bytes.Length > 0 ? bytes : null;
        }
        catch
        {
            return null;
        }
    }
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
                return new TrackMetadata(
                    DurationSeconds: duration,
                    ArtworkBytes: ImageFormatHelper.ReadSiblingCoverBytes(filePath));
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

            artwork ??= ImageFormatHelper.ReadSiblingCoverBytes(filePath);

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
            return new TrackMetadata(
                ArtworkBytes: ImageFormatHelper.ReadSiblingCoverBytes(filePath));
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
                    MimeType = ImageFormatHelper.DetectMimeType(artworkBytes),
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
