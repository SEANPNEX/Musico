using System.Text.Json;
using System.Text.Json.Serialization;

namespace MusicoWindows.Core;

public static class Persistence
{
    public static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        Converters = { new JsonStringEnumConverter() }
    };

    public static bool IsPortableMode => File.Exists(Path.Combine(AppContext.BaseDirectory, "portable.txt"));

    public static string AppDataDirectory
    {
        get
        {
            string dir = ResolveAppDataDirectory(
                AppContext.BaseDirectory,
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData));
            Directory.CreateDirectory(dir);
            return dir;
        }
    }

    /// <summary>
    /// Resolves the state directory: uses &lt;baseDirectory&gt;\Data when portable.txt is present
    /// next to the executable, otherwise &lt;localAppDataRoot&gt;\Musico.
    /// </summary>
    public static string ResolveAppDataDirectory(string baseDirectory, string localAppDataRoot)
    {
        if (!string.IsNullOrWhiteSpace(baseDirectory) &&
            File.Exists(Path.Combine(baseDirectory, "portable.txt")))
        {
            return Path.Combine(baseDirectory, "Data");
        }
        return Path.Combine(localAppDataRoot, "Musico");
    }

    /// <summary>
    /// Loads JSON from <paramref name="path"/>. Returns default when missing.
    /// If the file cannot be decoded, renames it to &lt;name&gt;.corrupt and returns default.
    /// </summary>
    public static T? Load<T>(string path)
    {
        if (!File.Exists(path)) return default;

        byte[] data;
        try
        {
            data = File.ReadAllBytes(path);
        }
        catch (IOException)
        {
            return default;
        }

        try
        {
            return JsonSerializer.Deserialize<T>(data, JsonOptions);
        }
        catch (JsonException)
        {
            BackupCorruptFile(path);
            return default;
        }
        catch (NotSupportedException)
        {
            BackupCorruptFile(path);
            return default;
        }
    }

    /// <summary>Atomically writes <paramref name="value"/> as JSON to <paramref name="path"/>.</summary>
    public static void Save<T>(T value, string path)
    {
        string? dir = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(dir))
        {
            Directory.CreateDirectory(dir);
        }

        byte[] bytes = JsonSerializer.SerializeToUtf8Bytes(value, JsonOptions);
        string tempPath = $"{path}.{Guid.NewGuid():N}.tmp";
        try
        {
            using (var fs = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None, 4096, FileOptions.WriteThrough))
            {
                fs.Write(bytes, 0, bytes.Length);
                fs.Flush(flushToDisk: true);
            }
            File.Move(tempPath, path, overwrite: true);
        }
        catch
        {
            try { if (File.Exists(tempPath)) File.Delete(tempPath); } catch { /* best-effort cleanup */ }
            throw;
        }
    }

    private static void BackupCorruptFile(string path)
    {
        try
        {
            string backup = path + ".corrupt";
            File.Move(path, backup, overwrite: true);
        }
        catch
        {
            // Ignore backup errors if the file is locked or already removed.
        }
    }

    /// <summary>
    /// Converts a track ID into a safe single filename component across Windows/POSIX filesystems.
    /// Existing local/cloud SHA-256 hex IDs pass through unchanged, while ephemeral IDs such as
    /// <c>extsearch:&lt;extId&gt;:&lt;remoteId&gt;</c> (which contain <c>:</c>) are hashed cleanly.
    /// </summary>
    public static string SafeTrackFileKey(string trackId)
    {
        if (string.IsNullOrWhiteSpace(trackId)) return "empty";

        bool isSafe = true;
        foreach (char c in trackId)
        {
            if (!(char.IsAsciiLetterOrDigit(c) || c == '-' || c == '_' || c == '.'))
            {
                isSafe = false;
                break;
            }
        }

        if (isSafe && !trackId.Contains("..", StringComparison.Ordinal))
        {
            return trackId;
        }

        byte[] hash = System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(trackId));
        return "ext_" + Convert.ToHexString(hash).ToLowerInvariant();
    }
}
