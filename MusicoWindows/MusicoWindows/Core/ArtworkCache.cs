using System.Security.Cryptography;
#if WINDOWS
using System.Windows.Media;
using System.Windows.Media.Imaging;
#endif

namespace MusicoWindows.Core;

/// <summary>
/// Cover art disk cache: one downscaled (max 600px) JPEG per distinct image, keyed by SHA-256 of raw image bytes.
/// </summary>
public sealed class ArtworkCache
{
    public const int MaxDimension = 600;

    public string DirectoryPath { get; }

    public ArtworkCache(string directoryPath)
    {
        DirectoryPath = directoryPath;
    }

    private string FilePath(string key) => Path.Combine(DirectoryPath, $"{key}.jpg");

    /// <summary>Stores the image on disk and returns its SHA-256 key, or null if the bytes are not a valid image.</summary>
    public string? Store(byte[] data)
    {
        if (data is null || data.Length == 0) return null;

        string key = Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();
        string targetPath = FilePath(key);
        if (File.Exists(targetPath)) return key;

        try
        {
            byte[] jpeg;
#if WINDOWS
            using var input = new MemoryStream(data, writable: false);
            var decoder = BitmapDecoder.Create(input, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
            if (decoder.Frames.Count == 0) return null;

            BitmapSource source = decoder.Frames[0];
            int longest = Math.Max(source.PixelWidth, source.PixelHeight);
            if (longest <= 0) return null;

            if (longest > MaxDimension)
            {
                double scale = (double)MaxDimension / longest;
                var scaled = new TransformedBitmap(source, new ScaleTransform(scale, scale));
                scaled.Freeze();
                source = scaled;
            }

            var encoder = new JpegBitmapEncoder { QualityLevel = 85 };
            encoder.Frames.Add(BitmapFrame.Create(source));

            using var output = new MemoryStream();
            encoder.Save(output);
            jpeg = output.ToArray();
#else
            jpeg = data;
#endif

            Directory.CreateDirectory(DirectoryPath);
            string tempPath = $"{targetPath}.{Guid.NewGuid():N}.tmp";
            try
            {
                File.WriteAllBytes(tempPath, jpeg);
                File.Move(tempPath, targetPath, overwrite: true);
            }
            catch
            {
                try { if (File.Exists(tempPath)) File.Delete(tempPath); } catch { /* best effort */ }
                return null;
            }
            return key;
        }
        catch
        {
            return null;
        }
    }

    public string? GetImagePath(string? key)
    {
        if (string.IsNullOrWhiteSpace(key)) return null;
        string path = FilePath(key);
        return File.Exists(path) ? path : null;
    }

    /// <summary>Deletes stored images that no track refers to anymore.</summary>
    public void Prune(ISet<string> keepKeys)
    {
        if (!Directory.Exists(DirectoryPath)) return;
        foreach (string file in Directory.EnumerateFiles(DirectoryPath))
        {
            string key = Path.GetFileNameWithoutExtension(file);
            if (!keepKeys.Contains(key))
            {
                try { File.Delete(file); } catch { /* ignore locked files */ }
            }
        }
    }
}
