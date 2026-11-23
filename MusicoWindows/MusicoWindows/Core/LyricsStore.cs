using System.Text;
using MusicoWindows.Models;

namespace MusicoWindows.Core;

/// <summary>
/// Resolves lyrics for a track using local sources only, in priority order:
/// imported copy -> exact sibling .lrc/.txt -> non-recursive folder fuzzy match -> embedded tag.
/// </summary>
public sealed class LyricsStore
{
    public event Action<string>? LyricsChanged;

    public string DirectoryPath { get; }

    public LyricsStore(string directoryPath)
    {
        DirectoryPath = directoryPath;
    }

    private string ImportedPath(string trackId) => Path.Combine(DirectoryPath, $"{trackId}.lrc");

    public (Lyrics Lyrics, LyricsSource Source)? Resolve(
        string trackId,
        string? audioPath,
        string? embedded,
        string? trackTitle = null,
        string? trackArtist = null)
    {
        if (ReadFile(ImportedPath(trackId)) is { } imported)
        {
            return (imported, LyricsSource.Imported);
        }

        if (!string.IsNullOrWhiteSpace(audioPath))
        {
            var exactPaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (string ext in (string[])[".lrc", ".txt"])
            {
                string sidecarPath = Path.ChangeExtension(audioPath, ext);
                exactPaths.Add(Path.GetFullPath(sidecarPath));
                if (ReadFile(sidecarPath) is { } sidecar)
                {
                    return (sidecar, LyricsSource.Sidecar);
                }
            }

            if (FindBestFolderMatch(audioPath, exactPaths, trackTitle, trackArtist) is { } folderLyrics)
            {
                return (folderLyrics, LyricsSource.FolderMatch);
            }
        }

        if (!string.IsNullOrWhiteSpace(embedded))
        {
            var parsed = LrcParser.Parse(embedded);
            if (parsed.Lines.Count > 0)
            {
                return (parsed, LyricsSource.Embedded);
            }
        }

        return null;
    }

    private static Lyrics? FindBestFolderMatch(
        string audioPath,
        HashSet<string> exactPaths,
        string? trackTitle,
        string? trackArtist)
    {
        string? dir;
        try
        {
            dir = Path.GetDirectoryName(audioPath);
        }
        catch
        {
            return null;
        }
        if (string.IsNullOrEmpty(dir) || !Directory.Exists(dir)) return null;

        string audioStem = Path.GetFileNameWithoutExtension(audioPath);
        var candidates = new List<(string FileName, bool IsLrc, double Score, Lyrics Lyrics)>();

        IEnumerable<string> files;
        try
        {
            files = Directory.EnumerateFiles(dir, "*", SearchOption.TopDirectoryOnly);
        }
        catch
        {
            return null;
        }

        foreach (string child in files)
        {
            string full = Path.GetFullPath(child);
            if (exactPaths.Contains(full)) continue;

            string ext = Path.GetExtension(child).ToLowerInvariant();
            if (ext != ".lrc" && ext != ".txt") continue;

            FileAttributes attrs;
            try
            {
                attrs = File.GetAttributes(child);
                if (attrs.HasFlag(FileAttributes.Hidden) || attrs.HasFlag(FileAttributes.Directory)) continue;
            }
            catch
            {
                continue;
            }

            string stem = Path.GetFileNameWithoutExtension(child);
            double score = LyricsNameMatcher.MatchScore(stem, audioStem, trackTitle, trackArtist);
            if (score < LyricsNameMatcher.MinimumMatchScore) continue;

            Lyrics? parsed = ReadFile(child);
            if (parsed is null) continue;

            candidates.Add((Path.GetFileName(child), ext == ".lrc", score, parsed));
        }

        if (candidates.Count == 0) return null;

        candidates.Sort((a, b) =>
        {
            if (Math.Abs(a.Score - b.Score) > 0.001) return b.Score.CompareTo(a.Score);
            if (a.Lyrics.IsTimed != b.Lyrics.IsTimed) return a.Lyrics.IsTimed ? -1 : 1;
            if (a.IsLrc != b.IsLrc) return a.IsLrc ? -1 : 1;
            return StringComparer.OrdinalIgnoreCase.Compare(a.FileName, b.FileName);
        });

        return candidates[0].Lyrics;
    }

    public bool HasImported(string trackId) => File.Exists(ImportedPath(trackId));

    public string? ReadRawImportedText(string trackId)
    {
        string path = ImportedPath(trackId);
        if (!File.Exists(path)) return null;
        try
        {
            return LrcParser.Decode(File.ReadAllBytes(path));
        }
        catch
        {
            return null;
        }
    }

    /// <summary>Copies a user-picked lyrics file into the app directory, stored as UTF-8.</summary>
    public void ImportLyrics(string sourcePath, string trackId)
    {
        byte[] data = File.ReadAllBytes(sourcePath);
        string? text = LrcParser.Decode(data);
        if (text is null)
        {
            throw new InvalidDataException("That file could not be read as lyrics text.");
        }
        SaveImportedLyrics(text, trackId);
    }

    /// <summary>Validates and persists resolved/edited lyrics text as UTF-8 `.lrc` in the app store.</summary>
    public void SaveImportedLyrics(string text, string trackId)
    {
        if (string.IsNullOrWhiteSpace(text) || LrcParser.Parse(text).Lines.Count == 0)
        {
            throw new InvalidDataException("That file could not be read as lyrics text.");
        }

        Directory.CreateDirectory(DirectoryPath);
        string dest = ImportedPath(trackId);
        WriteUtf8Atomic(dest, text);
        LyricsChanged?.Invoke(trackId);
    }

    public bool CanExportSidecar(string? audioPath)
    {
        if (string.IsNullOrWhiteSpace(audioPath)) return false;
        try
        {
            if (!Path.IsPathRooted(audioPath)) return false;
            string? dir = Path.GetDirectoryName(audioPath);
            return !string.IsNullOrEmpty(dir) && Directory.Exists(dir);
        }
        catch
        {
            return false;
        }
    }

    public void ExportSidecarLyrics(string text, string audioPath)
    {
        if (string.IsNullOrWhiteSpace(text) || LrcParser.Parse(text).Lines.Count == 0)
        {
            throw new InvalidDataException("That file could not be read as lyrics text.");
        }
        if (!CanExportSidecar(audioPath))
        {
            throw new IOException("Song folder is not available.");
        }
        string sidecarPath = Path.ChangeExtension(audioPath, ".lrc");
        WriteUtf8Atomic(sidecarPath, text);
    }

    public void RemoveImported(string trackId)
    {
        string path = ImportedPath(trackId);
        if (File.Exists(path))
        {
            try { File.Delete(path); } catch { /* ignore */ }
        }
        LyricsChanged?.Invoke(trackId);
    }

    private static void WriteUtf8Atomic(string destPath, string text)
    {
        string temp = $"{destPath}.{Guid.NewGuid():N}.tmp";
        try
        {
            File.WriteAllText(temp, text, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.Move(temp, destPath, overwrite: true);
        }
        catch
        {
            try { if (File.Exists(temp)) File.Delete(temp); } catch { /* best effort */ }
            throw;
        }
    }

    private static Lyrics? ReadFile(string path)
    {
        if (!File.Exists(path)) return null;
        try
        {
            byte[] data = File.ReadAllBytes(path);
            string? text = LrcParser.Decode(data);
            if (text is null) return null;
            var lyrics = LrcParser.Parse(text);
            return lyrics.Lines.Count > 0 ? lyrics : null;
        }
        catch
        {
            return null;
        }
    }
}
