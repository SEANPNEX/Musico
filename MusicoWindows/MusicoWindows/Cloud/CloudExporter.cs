using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using MusicoWindows.Core;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

public sealed record CloudExportProgress(
    int Completed,
    int Total,
    string CurrentTrackTitle,
    int FailedCount = 0);

public sealed record CloudExportResult(
    int SavedCount,
    int FailedCount,
    string DestinationFolder,
    IReadOnlyList<string> Errors);

/// <summary>
/// Exports cloud tracks and playlists to local folders, reusing CloudAudioCache
/// and writing best-effort ID3/MP4 tags + cover art via TagLibSharp.
/// </summary>
public sealed class CloudExporter
{
    public const int MaxFileNameLength = 120;

    private static readonly HashSet<string> ReservedDeviceNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };

    private readonly CloudAudioCache _cloudCache;
    private readonly YouTubeClient _youtube;
    private readonly BilibiliClient _bilibili;
    private readonly ArtworkCache? _artworkCache;

    public CloudExporter(
        CloudAudioCache cloudCache,
        YouTubeClient youtubeClient,
        BilibiliClient bilibiliClient,
        ArtworkCache? artworkCache = null)
    {
        _cloudCache = cloudCache ?? throw new ArgumentNullException(nameof(cloudCache));
        _youtube = youtubeClient ?? throw new ArgumentNullException(nameof(youtubeClient));
        _bilibili = bilibiliClient ?? throw new ArgumentNullException(nameof(bilibiliClient));
        _artworkCache = artworkCache;
    }

    public static string SanitizeFileName(string? raw, string fallback = "Untitled")
    {
        string safeFallback = SanitizeFileNameCore(fallback) ?? "Untitled";
        return SanitizeFileNameCore(raw) ?? safeFallback;
    }

    private static string? SanitizeFileNameCore(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
        {
            return null;
        }

        var sb = new StringBuilder(raw.Length);
        foreach (char c in raw)
        {
            if (!IsInvalidWindowsFileNameChar(c))
            {
                sb.Append(c);
            }
        }

        string cleaned = sb.ToString().Trim().TrimEnd('.', ' ');
        if (cleaned.Length == 0)
        {
            return null;
        }

        if (cleaned.Length > MaxFileNameLength)
        {
            cleaned = cleaned[..MaxFileNameLength].TrimEnd('.', ' ');
            if (cleaned.Length == 0)
            {
                return null;
            }
        }

        int dotIdx = cleaned.IndexOf('.');
        string stem = dotIdx >= 0 ? cleaned[..dotIdx] : cleaned;
        if (ReservedDeviceNames.Contains(stem))
        {
            cleaned = "_" + cleaned;
            if (cleaned.Length > MaxFileNameLength)
            {
                cleaned = cleaned[..MaxFileNameLength].TrimEnd('.', ' ');
            }
        }

        return cleaned.Length == 0 ? null : cleaned;
    }

    public static string GetUniqueFilePath(string directory, string baseName, string extension)
    {
        if (string.IsNullOrWhiteSpace(directory))
        {
            throw new ArgumentException("Destination directory is required.", nameof(directory));
        }

        string safeBase = SanitizeFileName(baseName, "Track");
        string ext = string.IsNullOrWhiteSpace(extension)
            ? string.Empty
            : extension.StartsWith('.') ? extension : "." + extension;

        string candidate = Path.Combine(directory, $"{safeBase}{ext}");
        if (!File.Exists(candidate) && !Directory.Exists(candidate))
        {
            return candidate;
        }

        for (int counter = 2; ; counter++)
        {
            string numbered = Path.Combine(directory, $"{safeBase} ({counter}){ext}");
            if (!File.Exists(numbered) && !Directory.Exists(numbered))
            {
                return numbered;
            }
        }
    }

    public async Task<CloudExportResult> ExportPlaylistAsync(
        LibraryRoot root,
        IReadOnlyList<Track> tracks,
        string parentDirectory,
        string folderName,
        IProgress<CloudExportProgress>? progress = null,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(root);
        ArgumentNullException.ThrowIfNull(tracks);
        if (string.IsNullOrWhiteSpace(parentDirectory))
        {
            throw new ArgumentException("Choose a save location folder.", nameof(parentDirectory));
        }

        string rootFallback = SanitizeFileName(root.Name, "Cloud Playlist");
        string safeFolderName = SanitizeFileName(folderName, rootFallback);
        string destinationFolder = Path.Combine(parentDirectory, safeFolderName);
        Directory.CreateDirectory(destinationFolder);

        var cloudTracks = tracks.Where(t => t.IsCloud).ToList();
        int savedCount = 0;
        int failedCount = 0;
        var errors = new List<string>();

        for (int i = 0; i < cloudTracks.Count; i++)
        {
            ct.ThrowIfCancellationRequested();
            var track = cloudTracks[i];
            progress?.Report(new CloudExportProgress(i, cloudTracks.Count, track.Title, failedCount));

            try
            {
                await ExportTrackAsync(track, root, destinationFolder, ct).ConfigureAwait(false);
                savedCount++;
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                failedCount++;
                errors.Add($"{track.Title}: {ex.Message}");
            }
        }

        progress?.Report(new CloudExportProgress(cloudTracks.Count, cloudTracks.Count, string.Empty, failedCount));
        return new CloudExportResult(savedCount, failedCount, destinationFolder, errors);
    }

    public async Task<string> ExportTrackAsync(
        Track track,
        LibraryRoot? root,
        string destinationDirectory,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(track);
        if (!track.IsCloud)
        {
            throw new InvalidOperationException("Only cloud songs can be exported to a local folder.");
        }
        if (string.IsNullOrWhiteSpace(destinationDirectory))
        {
            throw new ArgumentException("Choose a destination folder.", nameof(destinationDirectory));
        }

        ct.ThrowIfCancellationRequested();

        string cachedPath = await _cloudCache.GetOrDownloadAsync(
            track.Id,
            token => ResolveCloudStreamAsync(track, root, token),
            root?.ProxyUrl,
            ct).ConfigureAwait(false);

        ct.ThrowIfCancellationRequested();
        Directory.CreateDirectory(destinationDirectory);

        string ext = Path.GetExtension(cachedPath);
        if (string.IsNullOrWhiteSpace(ext))
        {
            ext = ".m4a";
        }

        string fallbackName = string.IsNullOrWhiteSpace(track.RemoteTrackId) ? "Track" : track.RemoteTrackId;
        string baseName = SanitizeFileName(track.Title, fallbackName);
        string destPath = GetUniqueFilePath(destinationDirectory, baseName, ext);
        string tempPath = $"{destPath}.{Guid.NewGuid():N}.tmp";

        try
        {
            await using (var src = new FileStream(cachedPath, FileMode.Open, FileAccess.Read, FileShare.Read, 81920, useAsync: true))
            await using (var dst = new FileStream(tempPath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920, useAsync: true))
            {
                await src.CopyToAsync(dst, ct).ConfigureAwait(false);
                await dst.FlushAsync(ct).ConfigureAwait(false);
            }

            File.Move(tempPath, destPath, overwrite: false);
        }
        catch
        {
            try { if (File.Exists(tempPath)) File.Delete(tempPath); } catch { /* best effort */ }
            throw;
        }

        TryWriteTags(destPath, track);
        return destPath;
    }

    private Task<CloudAudioStream> ResolveCloudStreamAsync(Track track, LibraryRoot? root, CancellationToken ct)
    {
        string remoteId = track.RemoteTrackId ?? throw new InvalidOperationException("Missing remote track ID.");
        return string.Equals(track.CloudSource, CloudSources.Bilibili, StringComparison.OrdinalIgnoreCase)
            ? _bilibili.ResolveAudioStreamAsync(remoteId, root?.Cookie, root?.ProxyUrl, ct)
            : _youtube.ResolveAudioStreamAsync(remoteId, root?.Cookie, root?.ProxyUrl, ct);
    }

    private void TryWriteTags(string destPath, Track track)
    {
        try
        {
            using var file = TagLib.File.Create(destPath);
            if (!string.IsNullOrWhiteSpace(track.Title))
            {
                file.Tag.Title = track.Title;
            }
            if (!string.IsNullOrWhiteSpace(track.Artist))
            {
                file.Tag.Performers = [track.Artist];
            }
            if (!string.IsNullOrWhiteSpace(track.Album))
            {
                file.Tag.Album = track.Album;
            }
            if (!string.IsNullOrWhiteSpace(track.AlbumArtist))
            {
                file.Tag.AlbumArtists = [track.AlbumArtist];
            }
            if (track.TrackNumber is int tn && tn > 0)
            {
                file.Tag.Track = (uint)tn;
            }
            if (track.DiscNumber is int dn && dn > 0)
            {
                file.Tag.Disc = (uint)dn;
            }

            string? artPath = _artworkCache?.GetImagePath(track.ArtworkKey);
            if (!string.IsNullOrEmpty(artPath) && File.Exists(artPath))
            {
                byte[] artBytes = File.ReadAllBytes(artPath);
                if (artBytes.Length > 0)
                {
                    var picture = new TagLib.Picture(new TagLib.ByteVector(artBytes))
                    {
                        Type = TagLib.PictureType.FrontCover,
                        MimeType = "image/jpeg",
                        Description = "Cover"
                    };
                    file.Tag.Pictures = [picture];
                }
            }

            file.Save();
        }
        catch
        {
            // Best-effort tagging only; keep the exported audio file intact.
        }
    }

    private static bool IsInvalidWindowsFileNameChar(char c) =>
        c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' ||
        c == '"' || c == '<' || c == '>' || c == '|' || char.IsControl(c);
}
