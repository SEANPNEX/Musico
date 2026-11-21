using System.Text;
using MusicoWindows.Models;

namespace MusicoWindows.Core;

/// <summary>
/// Resolves lyrics for a track in priority order: imported file -> sibling .lrc/.txt -> embedded tag.
/// </summary>
public sealed class LyricsStore
{
    public string DirectoryPath { get; }

    public LyricsStore(string directoryPath)
    {
        DirectoryPath = directoryPath;
    }

    private string ImportedPath(string trackId) => Path.Combine(DirectoryPath, $"{trackId}.lrc");

    public (Lyrics Lyrics, LyricsSource Source)? Resolve(string trackId, string? audioPath, string? embedded)
    {
        if (ReadFile(ImportedPath(trackId)) is { } imported)
        {
            return (imported, LyricsSource.Imported);
        }

        if (!string.IsNullOrWhiteSpace(audioPath))
        {
            foreach (string ext in (string[])[".lrc", ".txt"])
            {
                string sidecarPath = Path.ChangeExtension(audioPath, ext);
                if (ReadFile(sidecarPath) is { } sidecar)
                {
                    return (sidecar, LyricsSource.Sidecar);
                }
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

    public bool HasImported(string trackId) => File.Exists(ImportedPath(trackId));

    /// <summary>Copies a user-picked lyrics file into the app directory, stored as UTF-8.</summary>
    public void ImportLyrics(string sourcePath, string trackId)
    {
        byte[] data = File.ReadAllBytes(sourcePath);
        string? text = LrcParser.Decode(data);
        if (text is null || LrcParser.Parse(text).Lines.Count == 0)
        {
            throw new InvalidDataException("That file could not be read as lyrics text.");
        }

        Directory.CreateDirectory(DirectoryPath);
        string dest = ImportedPath(trackId);
        string temp = $"{dest}.{Guid.NewGuid():N}.tmp";
        try
        {
            File.WriteAllText(temp, text, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
            File.Move(temp, dest, overwrite: true);
        }
        catch
        {
            try { if (File.Exists(temp)) File.Delete(temp); } catch { /* best effort */ }
            throw;
        }
    }

    public void RemoveImported(string trackId)
    {
        string path = ImportedPath(trackId);
        if (File.Exists(path))
        {
            try { File.Delete(path); } catch { /* ignore */ }
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
