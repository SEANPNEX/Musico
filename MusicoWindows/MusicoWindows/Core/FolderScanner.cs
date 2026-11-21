using System.Text;

namespace MusicoWindows.Core;

public sealed record ScannedFile(string RelativePath, long FileSize, DateTimeOffset ModifiedUtc);

public static class FolderScanner
{
    public static readonly HashSet<string> AudioExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        "mp3", "m4a", "aac", "flac", "wav", "aif", "aiff", "wma", "ogg", "opus"
    };

    private static bool IsAudio(string fileName)
    {
        string ext = Path.GetExtension(fileName);
        return ext.Length > 1 && AudioExtensions.Contains(ext[1..]);
    }

    private static bool IsHiddenOrSystem(FileSystemInfo info)
    {
        if (info.Name.StartsWith('.')) return true;
        return (info.Attributes & (FileAttributes.Hidden | FileAttributes.System)) != 0;
    }

    /// <summary>
    /// Recursively lists audio files under <paramref name="rootPath"/> as NFC-normalised '/' relative paths.
    /// Returns null when the folder cannot be listed at all (offline, unmounted, missing).
    /// </summary>
    public static IReadOnlyList<ScannedFile>? Scan(string rootPath)
    {
        if (string.IsNullOrWhiteSpace(rootPath) || !Directory.Exists(rootPath))
        {
            return null;
        }

        var rootDir = new DirectoryInfo(rootPath);
        try
        {
            // Verify the root itself is readable.
            _ = rootDir.GetFileSystemInfos();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or System.Security.SecurityException)
        {
            return null;
        }

        var results = new List<ScannedFile>();
        var stack = new Stack<DirectoryInfo>();
        stack.Push(rootDir);

        while (stack.Count > 0)
        {
            var current = stack.Pop();
            FileSystemInfo[] entries;
            try
            {
                entries = current.GetFileSystemInfos();
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or System.Security.SecurityException)
            {
                continue;
            }

            foreach (var entry in entries)
            {
                if (IsHiddenOrSystem(entry)) continue;

                if (entry is DirectoryInfo subDir)
                {
                    stack.Push(subDir);
                }
                else if (entry is FileInfo file && IsAudio(file.Name))
                {
                    string rel = Path.GetRelativePath(rootPath, file.FullName)
                        .Replace('\\', '/')
                        .Normalize(NormalizationForm.FormC);
                    var modified = new DateTimeOffset(DateTime.SpecifyKind(file.LastWriteTimeUtc, DateTimeKind.Utc));
                    results.Add(new ScannedFile(rel, file.Length, modified));
                }
            }
        }

        results.Sort((a, b) => string.Compare(a.RelativePath, b.RelativePath, StringComparison.CurrentCultureIgnoreCase));
        return results;
    }

    public static string CombinePath(string rootPath, string relativePath)
    {
        string nativeRel = relativePath.Replace('/', Path.DirectorySeparatorChar);
        return Path.Combine(rootPath, nativeRel);
    }
}
