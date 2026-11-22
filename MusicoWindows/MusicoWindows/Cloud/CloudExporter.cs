using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using MusicoWindows.Core;
using MusicoWindows.Models;

namespace MusicoWindows.Cloud;

public sealed record CloudFolderManifest(
    string SourceUrl,
    string? RemoteId,
    string CloudKind,
    DateTimeOffset LastSyncedUtc,
    Dictionary<string, string> Tracks);

public sealed record CloudUpdateResult(
    int AddedCount,
    int SkippedCount,
    int FailedCount,
    int TotalRemoteCount,
    IReadOnlyList<string> Errors);

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
    public const string ManifestFileName = ".musico-cloud.json";

    private static readonly JsonSerializerOptions ManifestJsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true
    };

    private static readonly HashSet<string> ReservedDeviceNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };

    private readonly CloudAudioCache _cloudCache;
    private readonly YouTubeClient _youtube;
    private readonly BilibiliClient _bilibili;
    private readonly GoogleDriveClient _googleDrive;
    private readonly ArtworkCache? _artworkCache;

    public CloudExporter(
        CloudAudioCache cloudCache,
        YouTubeClient youtubeClient,
        BilibiliClient bilibiliClient,
        ArtworkCache? artworkCache = null,
        GoogleDriveClient? googleDriveClient = null)
    {
        _cloudCache = cloudCache ?? throw new ArgumentNullException(nameof(cloudCache));
        _youtube = youtubeClient ?? throw new ArgumentNullException(nameof(youtubeClient));
        _bilibili = bilibiliClient ?? throw new ArgumentNullException(nameof(bilibiliClient));
        _artworkCache = artworkCache;
        _googleDrive = googleDriveClient ?? new GoogleDriveClient();
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

        var existingManifest = LoadManifest(destinationFolder);
        var trackedMap = existingManifest?.Tracks is not null
            ? new Dictionary<string, string>(existingManifest.Tracks, StringComparer.Ordinal)
            : new Dictionary<string, string>(StringComparer.Ordinal);

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
                string destPath = await ExportTrackAsync(track, root, destinationFolder, ct).ConfigureAwait(false);
                string trackKey = !string.IsNullOrWhiteSpace(track.RemoteTrackId)
                    ? track.RemoteTrackId!
                    : track.RelativePath;
                if (!string.IsNullOrWhiteSpace(trackKey))
                {
                    trackedMap[trackKey] = Path.GetFileName(destPath);
                }
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

        string? sourceUrl = string.IsNullOrWhiteSpace(root.SourceUrl) ? null : root.SourceUrl.Trim();
        RootKind? cloudKind = root.EffectiveCloudKind;
        if (sourceUrl is not null && cloudKind is { } kind)
        {
            try
            {
                SaveManifest(
                    destinationFolder,
                    new CloudFolderManifest(
                        SourceUrl: sourceUrl,
                        RemoteId: root.RemoteId,
                        CloudKind: kind.ToString(),
                        LastSyncedUtc: DateTimeOffset.UtcNow,
                        Tracks: trackedMap));
            }
            catch
            {
                // Best effort; exported audio files remain valid even if manifest write fails.
            }
        }

        progress?.Report(new CloudExportProgress(cloudTracks.Count, cloudTracks.Count, string.Empty, failedCount));
        return new CloudExportResult(savedCount, failedCount, destinationFolder, errors);
    }

    public async Task<CloudUpdateResult> UpdateLocalFolderAsync(
        LibraryRoot root,
        Func<string, string?, string?, RootKind?, CancellationToken, Task<CloudPlaylistResult>>? fetchPlaylist = null,
        IProgress<CloudExportProgress>? progress = null,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(root);
        if (string.IsNullOrWhiteSpace(root.Path))
        {
            throw new DirectoryNotFoundException("Saved folder location is unavailable.");
        }

        string folderPath = root.Path;
        Directory.CreateDirectory(folderPath);

        CloudFolderManifest? existingManifest = LoadManifest(folderPath);
        string rawSource = (existingManifest?.SourceUrl ?? root.SourceUrl ?? root.RemoteId ?? string.Empty).Trim();
        if (rawSource.Length == 0)
        {
            throw new InvalidOperationException("No cloud tracking source URL is associated with this folder.");
        }

        RootKind? kindHint = root.EffectiveCloudKind;
        if (kindHint is null &&
            existingManifest is not null &&
            Enum.TryParse<RootKind>(existingManifest.CloudKind, ignoreCase: true, out var parsedKind) &&
            parsedKind.IsCloud())
        {
            kindHint = parsedKind;
        }

        CloudPlaylistResult playlist;
        if (fetchPlaylist is not null)
        {
            playlist = await fetchPlaylist(rawSource, root.Cookie, root.ProxyUrl, kindHint, ct).ConfigureAwait(false);
        }
        else
        {
            RootKind resolvedKind = kindHint ?? CloudUrlClassifier.Classify(rawSource);
            playlist = resolvedKind switch
            {
                RootKind.CloudBilibili => await _bilibili.FetchPlaylistAsync(rawSource, root.Cookie, root.ProxyUrl, ct).ConfigureAwait(false),
                RootKind.CloudGoogleDrive => await _googleDrive.FetchPlaylistAsync(rawSource, root.Cookie, root.ProxyUrl, ct).ConfigureAwait(false),
                _ => await _youtube.FetchPlaylistAsync(rawSource, root.Cookie, root.ProxyUrl, ct).ConfigureAwait(false)
            };
        }

        var trackedMap = existingManifest?.Tracks is not null
            ? new Dictionary<string, string>(existingManifest.Tracks, StringComparer.Ordinal)
            : new Dictionary<string, string>(StringComparer.Ordinal);

        string cloudSource = playlist.Kind switch
        {
            RootKind.CloudBilibili => CloudSources.Bilibili,
            RootKind.CloudGoogleDrive => CloudSources.GoogleDrive,
            _ => CloudSources.YouTube
        };
        string albumArtist = playlist.Kind switch
        {
            RootKind.CloudBilibili => "Bilibili",
            RootKind.CloudGoogleDrive => "Google Drive",
            _ => "YouTube"
        };
        var lookupRoot = root with
        {
            Kind = playlist.Kind,
            SourceUrl = string.IsNullOrWhiteSpace(playlist.SourceUrl) ? rawSource : playlist.SourceUrl,
            RemoteId = playlist.RemoteId
        };

        int addedCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        var errors = new List<string>();
        var now = DateTimeOffset.UtcNow;

        for (int i = 0; i < playlist.Items.Count; i++)
        {
            ct.ThrowIfCancellationRequested();
            var item = playlist.Items[i];
            progress?.Report(new CloudExportProgress(i, playlist.Items.Count, item.Title, failedCount));

            if (trackedMap.TryGetValue(item.RemoteTrackId, out string? recordedFile) &&
                !string.IsNullOrWhiteSpace(recordedFile) &&
                File.Exists(Path.Combine(folderPath, recordedFile)))
            {
                skippedCount++;
                continue;
            }

            string relPath = $"cloud/{item.RemoteTrackId}";
            var tempTrack = new Track(
                Id: Track.MakeId(root.Id, relPath),
                RootId: root.Id,
                RelativePath: relPath,
                Title: string.IsNullOrWhiteSpace(item.Title) ? item.RemoteTrackId : item.Title,
                Artist: string.IsNullOrWhiteSpace(item.Artist) ? root.Name : item.Artist,
                Album: string.IsNullOrWhiteSpace(item.Album) ? (string.IsNullOrWhiteSpace(playlist.Title) ? root.Name : playlist.Title) : item.Album,
                AlbumArtist: albumArtist,
                TrackNumber: item.TrackNumber > 0 ? item.TrackNumber : i + 1,
                DiscNumber: 1,
                DurationSeconds: item.DurationSeconds,
                FileSize: 0,
                ModifiedUtc: now,
                HasEmbeddedLyrics: false,
                DateAddedUtc: now,
                ArtworkKey: null,
                CloudSource: cloudSource,
                RemoteTrackId: item.RemoteTrackId,
                RemoteArtworkUrl: item.ArtworkUrl);

            try
            {
                string destPath = await ExportTrackAsync(tempTrack, lookupRoot, folderPath, ct).ConfigureAwait(false);
                trackedMap[item.RemoteTrackId] = Path.GetFileName(destPath);
                addedCount++;
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                failedCount++;
                errors.Add($"{item.Title}: {ex.Message}");
            }
        }

        SaveManifest(
            folderPath,
            new CloudFolderManifest(
                SourceUrl: string.IsNullOrWhiteSpace(playlist.SourceUrl) ? rawSource : playlist.SourceUrl,
                RemoteId: playlist.RemoteId,
                CloudKind: playlist.Kind.ToString(),
                LastSyncedUtc: DateTimeOffset.UtcNow,
                Tracks: trackedMap));

        progress?.Report(new CloudExportProgress(playlist.Items.Count, playlist.Items.Count, string.Empty, failedCount));
        return new CloudUpdateResult(addedCount, skippedCount, failedCount, playlist.Items.Count, errors);
    }

    public static CloudFolderManifest? LoadManifest(string folderPath)
    {
        if (string.IsNullOrWhiteSpace(folderPath)) return null;
        string path = Path.Combine(folderPath, ManifestFileName);
        if (!File.Exists(path)) return null;
        try
        {
            string json = File.ReadAllText(path, Encoding.UTF8);
            var manifest = JsonSerializer.Deserialize<CloudFolderManifest>(json, ManifestJsonOptions);
            if (manifest is null || string.IsNullOrWhiteSpace(manifest.SourceUrl))
            {
                return null;
            }
            return manifest;
        }
        catch
        {
            return null;
        }
    }

    public static void SaveManifest(string folderPath, CloudFolderManifest manifest)
    {
        ArgumentNullException.ThrowIfNull(manifest);
        Directory.CreateDirectory(folderPath);
        string path = Path.Combine(folderPath, ManifestFileName);
        string tempPath = $"{path}.{Guid.NewGuid():N}.tmp";
        string json = JsonSerializer.Serialize(manifest, ManifestJsonOptions);
        try
        {
            File.WriteAllText(tempPath, json, Encoding.UTF8);
            File.Move(tempPath, path, overwrite: true);
        }
        catch
        {
            try { if (File.Exists(tempPath)) File.Delete(tempPath); } catch { /* ignore */ }
            throw;
        }
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
        if (string.Equals(track.CloudSource, CloudSources.Bilibili, StringComparison.OrdinalIgnoreCase))
        {
            return _bilibili.ResolveAudioStreamAsync(remoteId, root?.Cookie, root?.ProxyUrl, ct);
        }
        if (string.Equals(track.CloudSource, CloudSources.GoogleDrive, StringComparison.OrdinalIgnoreCase))
        {
            return _googleDrive.ResolveAudioStreamAsync(remoteId, root?.Cookie, root?.ProxyUrl, ct);
        }
        return _youtube.ResolveAudioStreamAsync(remoteId, root?.Cookie, root?.ProxyUrl, ct);
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
