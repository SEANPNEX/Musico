using System.Globalization;
using System.Net.Http;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Models;

namespace MusicoWindows.Stores;

/// <summary>
/// The music library: folder roots, the track index, albums, artists, playlists, and favourites.
/// </summary>
public sealed class LibraryStore : ObservableStore
{
    private sealed class LibraryFile
    {
        public List<LibraryRoot> Roots { get; set; } = new();
        public List<Track> Tracks { get; set; } = new();
        public int? ReaderVersion { get; set; }
        public bool? AppFolderRemoved { get; set; }
        public Dictionary<string, TrackMetadataOverride> MetadataOverrides { get; set; } = new();
        public Dictionary<string, FolderViewPrefs> FolderPrefs { get; set; } = new();
    }

    private sealed class PlaylistFile
    {
        public List<Playlist> Playlists { get; set; } = new();
        public HashSet<string> Favourites { get; set; } = new();
        public List<string>? FavouritesOrder { get; set; }
        public TrackSortMode? FavouritesSortMode { get; set; }
    }

    private const int CurrentReaderVersion = 3;
    public const string AppFolderName = "Music Folder";

    private readonly string _stateDirectory;
    private readonly string _musicDirectory;
    private readonly IMetadataReader _reader;
    private readonly int _scanBatchSize;
    private readonly bool _appFolderRemovable;
    private readonly YouTubeClient _youtube;
    private readonly BilibiliClient _bilibili;
    private readonly HttpMessageHandler? _artworkHandler;
    private readonly CloudAudioCache? _cloudCache;

    private readonly Dictionary<Guid, string> _rootPaths = new();
    private Dictionary<string, Track> _byId = new();

    private List<LibraryRoot> _roots = new();
    private List<Track> _tracks = new();
    private List<AlbumInfo> _albums = new();
    private List<string> _artists = new();
    private List<Playlist> _playlists = new();
    private HashSet<string> _favourites = new();
    private List<string> _favouritesOrder = new();
    private TrackSortMode _favouritesSortMode = TrackSortMode.Custom;
    private Dictionary<string, TrackMetadataOverride> _metadataOverrides = new(StringComparer.Ordinal);
    private Dictionary<string, FolderViewPrefs> _folderPrefs = new(StringComparer.Ordinal);
    private bool _isScanning;
    private int _scanDone;
    private int _scanTotal;
    private bool _appFolderRemoved;
    private bool _rereadAll;
    private bool _rescanRequested;
    private Task? _activeScanTask;

    public LibraryStore(
        string stateDirectory,
        string musicDirectory,
        IMetadataReader reader,
        ArtworkCache artwork,
        int scanBatchSize = 100,
        bool appFolderRemovable = true,
        YouTubeClient? youtubeClient = null,
        BilibiliClient? bilibiliClient = null,
        HttpMessageHandler? artworkHandler = null,
        CloudAudioCache? cloudCache = null)
    {
        _stateDirectory = stateDirectory;
        _musicDirectory = musicDirectory;
        _reader = reader;
        Artwork = artwork;
        _scanBatchSize = Math.Max(scanBatchSize, 1);
        _appFolderRemovable = appFolderRemovable;
        _youtube = youtubeClient ?? new YouTubeClient();
        _bilibili = bilibiliClient ?? new BilibiliClient();
        _artworkHandler = artworkHandler;
        _cloudCache = cloudCache;

        var file = Persistence.Load<LibraryFile>(LibraryPath);
        _rereadAll = file is not null && file.ReaderVersion != CurrentReaderVersion;

        var savedAppRoot = file?.Roots.FirstOrDefault(r => r.Kind == RootKind.AppFolder);
        var loadedRoots = file?.Roots.Where(r => r.Kind != RootKind.AppFolder).ToList() ?? new List<LibraryRoot>();
        _appFolderRemoved = _appFolderRemovable && file?.AppFolderRemoved == true;

        if (!_appFolderRemoved)
        {
            loadedRoots.Insert(0, CreateAppFolderRoot() with { ExcludedPaths = savedAppRoot?.ExcludedPaths });
            _rootPaths[LibraryRoot.AppFolderId] = _musicDirectory;
        }

        foreach (var root in loadedRoots)
        {
            if (root.Kind == RootKind.Picked && !string.IsNullOrWhiteSpace(root.Path) && Directory.Exists(root.Path))
            {
                _rootPaths[root.Id] = root.Path;
            }
        }

        _roots = loadedRoots;
        _tracks = file?.Tracks ?? new List<Track>();
        _metadataOverrides = file?.MetadataOverrides is not null
            ? new Dictionary<string, TrackMetadataOverride>(file.MetadataOverrides, StringComparer.Ordinal)
            : new Dictionary<string, TrackMetadataOverride>(StringComparer.Ordinal);
        _folderPrefs = file?.FolderPrefs is not null
            ? new Dictionary<string, FolderViewPrefs>(file.FolderPrefs, StringComparer.Ordinal)
            : new Dictionary<string, FolderViewPrefs>(StringComparer.Ordinal);
        if (_metadataOverrides.Count > 0 && _tracks.Count > 0)
        {
            _tracks = _tracks.Select(ApplyMetadataOverride).ToList();
        }
        RebuildIndex();

        var lists = Persistence.Load<PlaylistFile>(PlaylistsPath);
        _playlists = lists?.Playlists ?? new List<Playlist>();
        _favourites = lists?.Favourites ?? new HashSet<string>();
        _favouritesOrder = lists?.FavouritesOrder ?? new List<string>();
        _favouritesSortMode = lists?.FavouritesSortMode ?? TrackSortMode.Custom;
    }

    public IReadOnlyList<LibraryRoot> Roots => _roots;
    public IReadOnlyList<Track> Tracks => _tracks;
    public IReadOnlyList<AlbumInfo> Albums => _albums;
    public IReadOnlyList<string> Artists => _artists;
    public IReadOnlyList<Playlist> Playlists => _playlists;
    public IReadOnlySet<string> Favourites => _favourites;
    public bool IsScanning { get => _isScanning; private set => SetProperty(ref _isScanning, value); }
    public int ScanDone { get => _scanDone; private set => SetProperty(ref _scanDone, value); }
    public int ScanTotal { get => _scanTotal; private set => SetProperty(ref _scanTotal, value); }
    public bool AppFolderRemoved { get => _appFolderRemoved; private set => SetProperty(ref _appFolderRemoved, value); }
    public ArtworkCache Artwork { get; }
    public Action<ISet<string>>? OnTracksRemoved { get; set; }
    public Action<string, IReadOnlyList<string>>? OnFolderAdded { get; set; }
    public event Action<Track>? TrackUpdated;

    private string LibraryPath => Path.Combine(_stateDirectory, "library.json");
    private string PlaylistsPath => Path.Combine(_stateDirectory, "playlists.json");

    private LibraryRoot CreateAppFolderRoot() =>
        new(LibraryRoot.AppFolderId, AppFolderName, RootKind.AppFolder, _musicDirectory);

    private void SaveLibrary()
    {
        try
        {
            Persistence.Save(new LibraryFile
            {
                Roots = _roots,
                Tracks = _tracks,
                ReaderVersion = _rereadAll ? null : CurrentReaderVersion,
                AppFolderRemoved = _appFolderRemoved ? true : null,
                MetadataOverrides = _metadataOverrides,
                FolderPrefs = _folderPrefs
            }, LibraryPath);
        }
        catch
        {
            // Best-effort persistence on background/shutdown.
        }
    }

    private void SavePlaylists()
    {
        try
        {
            Persistence.Save(new PlaylistFile
            {
                Playlists = _playlists,
                Favourites = _favourites,
                FavouritesOrder = _favouritesOrder,
                FavouritesSortMode = _favouritesSortMode
            }, PlaylistsPath);
        }
        catch
        {
            // Best-effort persistence.
        }
    }

    // MARK: Roots

    public bool IsAvailable(LibraryRoot root) => root.IsCloud || _rootPaths.ContainsKey(root.Id);

    public bool IsAvailable(Track track) => track.IsCloud || _rootPaths.ContainsKey(track.RootId);

    public string? GetRootPath(Guid rootId) => _rootPaths.TryGetValue(rootId, out string? p) ? p : null;

    public LibraryRoot? GetRoot(Guid rootId) => _roots.FirstOrDefault(r => r.Id == rootId);

    private static string NormalizeDirPath(string path)
    {
        string full = Path.GetFullPath(path);
        return full.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
    }

    public bool IsFolderIndexed(string folderPath)
    {
        if (string.IsNullOrWhiteSpace(folderPath)) return false;

        string target = NormalizeDirPath(folderPath);
        foreach (var root in _roots)
        {
            if (!root.IsLocal || !_rootPaths.TryGetValue(root.Id, out string? rootPath)) continue;

            string normRoot = NormalizeDirPath(rootPath);
            if (string.Equals(target, normRoot, StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }

            string prefix = Path.EndsInDirectorySeparator(normRoot)
                ? normRoot
                : normRoot + Path.DirectorySeparatorChar;
            if (target.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                string rel = Path.GetRelativePath(normRoot, target).Replace('\\', '/');
                if (!root.IsExcluded(rel))
                {
                    return true;
                }
            }
        }
        return false;
    }

    public async Task AddRootAsync(string path, LibraryRoot? cloudSourceRoot = null)
    {
        if (string.IsNullOrWhiteSpace(path) || !Directory.Exists(path))
        {
            throw new DirectoryNotFoundException("That location could not be opened as a folder.");
        }

        string normalized = NormalizeDirPath(path);
        if (_rootPaths.Values.Any(existing => string.Equals(NormalizeDirPath(existing), normalized, StringComparison.OrdinalIgnoreCase)))
        {
            throw new InvalidOperationException("That folder is already in your library.");
        }

        string name = new DirectoryInfo(normalized).Name;
        string? sourceUrl = null;
        string? remoteId = null;
        string? cookie = null;
        string? proxyUrl = null;
        DateTimeOffset? lastSyncedUtc = null;
        RootKind? linkedCloudKind = null;

        if (cloudSourceRoot is not null &&
            cloudSourceRoot.EffectiveCloudKind is { } cloudKind &&
            !string.IsNullOrWhiteSpace(cloudSourceRoot.SourceUrl))
        {
            sourceUrl = cloudSourceRoot.SourceUrl.Trim();
            remoteId = cloudSourceRoot.RemoteId;
            cookie = cloudSourceRoot.Cookie;
            proxyUrl = cloudSourceRoot.ProxyUrl;
            lastSyncedUtc = DateTimeOffset.UtcNow;
            linkedCloudKind = cloudKind;
        }
        else if (CloudExporter.LoadManifest(normalized) is { } manifest)
        {
            sourceUrl = manifest.SourceUrl.Trim();
            remoteId = manifest.RemoteId;
            lastSyncedUtc = manifest.LastSyncedUtc;
            if (Enum.TryParse<RootKind>(manifest.CloudKind, ignoreCase: true, out var parsedKind) && parsedKind.IsCloud())
            {
                linkedCloudKind = parsedKind;
            }
            else
            {
                try { linkedCloudKind = CloudUrlClassifier.Classify(sourceUrl); } catch { /* ignore */ }
            }
        }

        var root = new LibraryRoot(
            Id: Guid.NewGuid(),
            Name: name,
            Kind: RootKind.Picked,
            Path: normalized,
            SourceUrl: sourceUrl,
            RemoteId: remoteId,
            Cookie: cookie,
            ProxyUrl: proxyUrl,
            LastSyncedUtc: lastSyncedUtc,
            LinkedCloudKind: linkedCloudKind);
        _roots = [.. _roots, root];
        _rootPaths[root.Id] = normalized;
        OnPropertyChanged(nameof(Roots));
        SaveLibrary();
        await RescanAsync();
        var addedIds = GetTracksUnder(root.Id, string.Empty).Select(t => t.Id).ToList();
        OnFolderAdded?.Invoke(root.Name, addedIds);
    }

    public void AttachCloudTracking(string folderPath, LibraryRoot cloudSourceRoot)
    {
        if (string.IsNullOrWhiteSpace(folderPath)) return;
        if (cloudSourceRoot.EffectiveCloudKind is not { } cloudKind || string.IsNullOrWhiteSpace(cloudSourceRoot.SourceUrl)) return;

        string normalized = NormalizeDirPath(folderPath);
        int idx = _roots.FindIndex(r =>
            r.IsLocal &&
            _rootPaths.TryGetValue(r.Id, out string? existingPath) &&
            string.Equals(NormalizeDirPath(existingPath), normalized, StringComparison.OrdinalIgnoreCase));
        if (idx < 0) return;

        var updated = new List<LibraryRoot>(_roots);
        updated[idx] = updated[idx] with
        {
            SourceUrl = cloudSourceRoot.SourceUrl.Trim(),
            RemoteId = cloudSourceRoot.RemoteId ?? updated[idx].RemoteId,
            Cookie = cloudSourceRoot.Cookie ?? updated[idx].Cookie,
            ProxyUrl = cloudSourceRoot.ProxyUrl ?? updated[idx].ProxyUrl,
            LastSyncedUtc = DateTimeOffset.UtcNow,
            LinkedCloudKind = cloudKind
        };
        _roots = updated;
        OnPropertyChanged(nameof(Roots));
        SaveLibrary();
    }

    public void MarkRootCloudSynced(Guid rootId)
    {
        int idx = _roots.FindIndex(r => r.Id == rootId);
        if (idx < 0) return;

        var updated = new List<LibraryRoot>(_roots);
        updated[idx] = updated[idx] with { LastSyncedUtc = DateTimeOffset.UtcNow };
        _roots = updated;
        OnPropertyChanged(nameof(Roots));
        SaveLibrary();
    }

    public async Task<LibraryRoot> AddCloudRootAsync(
        string sourceUrl,
        string? cookie = null,
        string? proxyUrl = null,
        CancellationToken ct = default)
    {
        string extractedUrl = CloudUrlClassifier.ExtractCloudUrl(sourceUrl);
        var kind = CloudUrlClassifier.Classify(extractedUrl);
        string? normalizedProxy = YouTubeClient.NormalizeProxyUrl(proxyUrl);
        string? trimmedCookie = string.IsNullOrWhiteSpace(cookie) ? null : cookie.Trim();

        CloudPlaylistResult result = kind == RootKind.CloudYouTube
            ? await _youtube.FetchPlaylistAsync(extractedUrl, trimmedCookie, normalizedProxy, ct).ConfigureAwait(false)
            : await _bilibili.FetchPlaylistAsync(extractedUrl, trimmedCookie, normalizedProxy, ct).ConfigureAwait(false);

        if (_roots.Any(r => r.Kind == result.Kind &&
                            string.Equals(r.RemoteId, result.RemoteId, StringComparison.OrdinalIgnoreCase)))
        {
            throw new InvalidOperationException("That cloud playlist is already in your library.");
        }

        var root = new LibraryRoot(
            Id: Guid.NewGuid(),
            Name: result.Title,
            Kind: result.Kind,
            Path: result.SourceUrl,
            SourceUrl: result.SourceUrl,
            RemoteId: result.RemoteId,
            Cookie: trimmedCookie,
            ProxyUrl: normalizedProxy,
            LastSyncedUtc: DateTimeOffset.UtcNow);

        var newTracks = await BuildCloudTracksAsync(root, result, Array.Empty<Track>(), ct).ConfigureAwait(false);

        _roots = [.. _roots, root];
        OnPropertyChanged(nameof(Roots));
        Publish([.. _tracks, .. newTracks]);
        OnFolderAdded?.Invoke(root.Name, newTracks.Select(t => t.Id).ToList());
        return root;
    }

    public async Task<string> CreateSubfolderAsync(Guid rootId, string parentPath, string folderName)
    {
        var root = GetRoot(rootId);
        if (root is null || !root.IsLocal || !_rootPaths.TryGetValue(rootId, out string? rootPath))
        {
            throw new InvalidOperationException("Cannot create a subfolder here.");
        }

        string trimmed = folderName.Trim();
        if (trimmed.Length == 0 ||
            trimmed is "." or ".." ||
            trimmed.IndexOfAny(['/', '\\', ':', '*', '?', '"', '<', '>', '|']) >= 0)
        {
            throw new ArgumentException("Enter a valid folder name.", nameof(folderName));
        }

        string normParent = LibraryRoot.NormalizeFolderPath(parentPath);
        string relPath = string.IsNullOrEmpty(normParent) ? trimmed : $"{normParent}/{trimmed}";
        string fullPath = FolderScanner.CombinePath(rootPath, relPath);
        Directory.CreateDirectory(fullPath);

        await RescanAsync();
        OnFolderAdded?.Invoke(trimmed, Array.Empty<string>());
        return relPath;
    }

    public void NotifyFolderAddedByPath(string folderPath, IReadOnlyList<string>? fallbackTrackIds = null)
    {
        if (string.IsNullOrWhiteSpace(folderPath)) return;
        string target = NormalizeDirPath(folderPath);
        string exactName = new DirectoryInfo(target).Name;

        foreach (var root in _roots)
        {
            if (!root.IsLocal || !_rootPaths.TryGetValue(root.Id, out string? rootPath)) continue;
            string normRoot = NormalizeDirPath(rootPath);
            if (string.Equals(target, normRoot, StringComparison.OrdinalIgnoreCase))
            {
                OnFolderAdded?.Invoke(exactName, GetTracksUnder(root.Id, string.Empty).Select(t => t.Id).ToList());
                return;
            }

            string prefix = Path.EndsInDirectorySeparator(normRoot)
                ? normRoot
                : normRoot + Path.DirectorySeparatorChar;
            if (target.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                string rel = LibraryRoot.NormalizeFolderPath(Path.GetRelativePath(normRoot, target));
                OnFolderAdded?.Invoke(exactName, GetTracksUnder(root.Id, rel).Select(t => t.Id).ToList());
                return;
            }
        }

        OnFolderAdded?.Invoke(exactName, fallbackTrackIds ?? Array.Empty<string>());
    }

    public async Task RelocateRootAsync(Guid id, string path)
    {
        int index = _roots.FindIndex(r => r.Id == id && r.Kind == RootKind.Picked);
        if (index < 0) return;

        if (string.IsNullOrWhiteSpace(path) || !Directory.Exists(path))
        {
            throw new DirectoryNotFoundException("That location could not be opened as a folder.");
        }

        string normalized = NormalizeDirPath(path);
        string name = new DirectoryInfo(normalized).Name;
        var updated = new List<LibraryRoot>(_roots);
        updated[index] = updated[index] with { Name = name, Path = normalized };
        _roots = updated;
        _rootPaths[id] = normalized;
        OnPropertyChanged(nameof(Roots));
        SaveLibrary();
        await RescanAsync();
    }

    public void RemoveRoot(Guid id)
    {
        int index = _roots.FindIndex(r => r.Id == id);
        if (index < 0) return;

        if (_roots[index].Kind == RootKind.AppFolder)
        {
            if (!_appFolderRemovable) return;
            AppFolderRemoved = true;
        }

        _rootPaths.Remove(id);
        var updatedRoots = new List<LibraryRoot>(_roots);
        updatedRoots.RemoveAt(index);
        _roots = updatedRoots;
        OnPropertyChanged(nameof(Roots));

        var removedIds = new HashSet<string>(_tracks.Where(t => t.RootId == id).Select(t => t.Id));
        RemoveFolderPrefsForRoot(id);
        if (removedIds.Count > 0)
        {
            _tracks = _tracks.Where(t => t.RootId != id).ToList();
            RebuildIndex();
            OnPropertyChanged(nameof(Tracks));
            Forget(removedIds);
            _cloudCache?.RemoveForTracks(removedIds);
        }

        SaveLibrary();
    }

    public void RemoveSubfolder(Guid rootId, string path)
    {
        string norm = LibraryRoot.NormalizeFolderPath(path);
        if (norm.Length == 0) return;

        int index = _roots.FindIndex(r => r.Id == rootId);
        if (index < 0) return;

        var currentExcluded = (_roots[index].ExcludedPaths ?? Array.Empty<string>())
            .Select(LibraryRoot.NormalizeFolderPath)
            .Where(p => p.Length > 0)
            .ToList();

        bool alreadyCovered = currentExcluded.Any(ex =>
            string.Equals(norm, ex, StringComparison.Ordinal) ||
            norm.StartsWith(ex + "/", StringComparison.Ordinal));

        var nextExcluded = currentExcluded;
        if (!alreadyCovered)
        {
            nextExcluded = currentExcluded
                .Where(ex => !ex.StartsWith(norm + "/", StringComparison.Ordinal))
                .Append(norm)
                .OrderBy(p => p, StringComparer.Ordinal)
                .ToList();
        }

        var updatedRoots = new List<LibraryRoot>(_roots);
        updatedRoots[index] = updatedRoots[index] with { ExcludedPaths = nextExcluded };
        _roots = updatedRoots;
        OnPropertyChanged(nameof(Roots));

        var removedIds = new HashSet<string>(_tracks
            .Where(t => t.RootId == rootId && LibraryRoot.FolderContains(norm, t.RelativePath))
            .Select(t => t.Id));

        if (removedIds.Count > 0)
        {
            _tracks = _tracks.Where(t => !removedIds.Contains(t.Id)).ToList();
            RebuildIndex();
            OnPropertyChanged(nameof(Tracks));
            Forget(removedIds);
            _cloudCache?.RemoveForTracks(removedIds);
            var keepKeys = new HashSet<string>(_tracks.Select(t => t.ArtworkKey).Where(k => k is not null)!);
            _ = Task.Run(() => Artwork.Prune(keepKeys));
        }

        SaveLibrary();
    }

    public async Task RestoreExcludedFoldersAsync(Guid rootId)
    {
        int index = _roots.FindIndex(r => r.Id == rootId);
        if (index < 0 || (_roots[index].ExcludedPaths?.Count ?? 0) == 0) return;

        var updatedRoots = new List<LibraryRoot>(_roots);
        updatedRoots[index] = updatedRoots[index] with { ExcludedPaths = null };
        _roots = updatedRoots;
        OnPropertyChanged(nameof(Roots));
        SaveLibrary();
        await RescanAsync();
    }

    public async Task RestoreAppFolderAsync()
    {
        if (!AppFolderRemoved) return;
        AppFolderRemoved = false;
        _roots = [CreateAppFolderRoot(), .. _roots];
        _rootPaths[LibraryRoot.AppFolderId] = _musicDirectory;
        OnPropertyChanged(nameof(Roots));
        SaveLibrary();
        await RescanAsync();
    }

    // MARK: Scanning

    public async Task RescanAsync()
    {
        if (IsScanning && _activeScanTask is not null)
        {
            _rescanRequested = true;
            await _activeScanTask;
            return;
        }

        IsScanning = true;
        _activeScanTask = RunScanLoopAsync();
        try
        {
            await _activeScanTask;
        }
        finally
        {
            _activeScanTask = null;
            IsScanning = false;
        }
    }

    private async Task RunScanLoopAsync()
    {
        do
        {
            _rescanRequested = false;
            await ScanOnceAsync();
        }
        while (_rescanRequested);
    }

    private async Task ScanOnceAsync()
    {
        ScanDone = 0;
        ScanTotal = 0;

        // Refresh availability of picked roots (reconnected drives / unavailable folders).
        bool rootsAvailabilityChanged = false;
        foreach (var root in _roots.Where(r => r.Kind == RootKind.Picked))
        {
            if (_rootPaths.TryGetValue(root.Id, out string? existingPath))
            {
                if (!Directory.Exists(existingPath))
                {
                    _rootPaths.Remove(root.Id);
                    rootsAvailabilityChanged = true;
                }
            }
            else if (!string.IsNullOrWhiteSpace(root.Path) && Directory.Exists(root.Path))
            {
                _rootPaths[root.Id] = root.Path;
                rootsAvailabilityChanged = true;
            }
        }
        if (rootsAvailabilityChanged)
        {
            OnPropertyChanged(nameof(Roots));
        }

        var snapshotRoots = _roots.ToList();
        var snapshotTracks = _tracks.ToList();
        var kept = snapshotTracks.Where(t => !_rootPaths.ContainsKey(t.RootId) && !t.IsCloud).ToList();
        var pending = new List<(LibraryRoot Root, string RootPath, ScannedFile File, Track? Old)>();
        var removed = new HashSet<string>();
        bool reread = _rereadAll;
        bool cloudSynced = false;

        foreach (var root in snapshotRoots)
        {
            if (!_rootPaths.TryGetValue(root.Id, out string? rootPath)) continue;

            var existingTracks = snapshotTracks.Where(t => t.RootId == root.Id).ToList();
            var scanned = await Task.Run(() => FolderScanner.Scan(rootPath));

            // Treat unreadable folders or picked folders that suddenly list zero files (when they had tracks)
            // as temporarily unreachable so we do not purge playlists.
            if (scanned is null || (scanned.Count == 0 && existingTracks.Count > 0 && root.Kind == RootKind.Picked))
            {
                kept.AddRange(existingTracks);
                continue;
            }

            var existingByRel = new Dictionary<string, Track>(StringComparer.Ordinal);
            foreach (var t in existingTracks)
            {
                existingByRel.TryAdd(t.RelativePath, t);
            }

            var seen = new HashSet<string>(StringComparer.Ordinal);
            foreach (var file in scanned)
            {
                if (root.IsExcluded(file.RelativePath)) continue;
                if (!seen.Add(file.RelativePath)) continue;

                if (!reread
                    && existingByRel.TryGetValue(file.RelativePath, out var old)
                    && old.FileSize == file.FileSize
                    && (Math.Abs((old.ModifiedUtc - file.ModifiedUtc).TotalSeconds) < 0.001 ||
                        (_metadataOverrides.TryGetValue(old.Id, out var ov) &&
                         ov.EditedUtc is DateTimeOffset edited &&
                         file.ModifiedUtc <= edited)))
                {
                    kept.Add(ApplyMetadataOverride(old));
                }
                else
                {
                    existingByRel.TryGetValue(file.RelativePath, out var oldTrack);
                    pending.Add((root, rootPath, file, oldTrack));
                }
            }

            foreach (var kvp in existingByRel)
            {
                if (!seen.Contains(kvp.Key))
                {
                    removed.Add(kvp.Value.Id);
                }
            }
        }

        ScanTotal = pending.Count;
        var now = DateTimeOffset.UtcNow;

        for (int i = 0; i < pending.Count; i++)
        {
            var item = pending[i];
            string fullPath = FolderScanner.CombinePath(item.RootPath, item.File.RelativePath);

            var (metadata, artworkKey) = await Task.Run(async () =>
            {
                var meta = await _reader.ReadAsync(fullPath);
                string? artKey = meta.ArtworkBytes is { Length: > 0 } ? Artwork.Store(meta.ArtworkBytes) : null;
                return (meta, artKey);
            });

            kept.Add(MakeTrack(item.Root, item.File, metadata, artworkKey, item.Old?.DateAddedUtc ?? now));
            ScanDone = i + 1;

            if ((i + 1) % _scanBatchSize == 0 && (i + 1) < pending.Count)
            {
                var notReached = pending.Skip(i + 1).Select(p => p.Old).Where(o => o is not null).Cast<Track>();
                Publish(kept.Concat(notReached));
            }
        }

        // Sync cloud roots; preserve existing cloud tracks on transient network failure.
        foreach (var root in snapshotRoots.Where(r => r.IsCloud))
        {
            var existingTracks = snapshotTracks.Where(t => t.RootId == root.Id).ToList();
            try
            {
                string source = root.SourceUrl ?? root.Path ?? string.Empty;
                CloudPlaylistResult result = root.Kind == RootKind.CloudYouTube
                    ? await _youtube.FetchPlaylistAsync(source, root.Cookie, root.ProxyUrl).ConfigureAwait(false)
                    : await _bilibili.FetchPlaylistAsync(source, root.Cookie, root.ProxyUrl).ConfigureAwait(false);

                var synced = await BuildCloudTracksAsync(root, result, existingTracks, CancellationToken.None).ConfigureAwait(false);
                kept.AddRange(synced);

                var syncedIds = new HashSet<string>(synced.Select(t => t.Id), StringComparer.Ordinal);
                foreach (var old in existingTracks)
                {
                    if (!syncedIds.Contains(old.Id))
                    {
                        removed.Add(old.Id);
                    }
                }

                UpdateCloudRootSynced(root.Id, result.Title);
                cloudSynced = true;
            }
            catch
            {
                kept.AddRange(existingTracks);
            }
        }

        // A root or subfolder removed while scanning must not leave orphan tracks behind.
        var activeRoots = _roots.ToDictionary(r => r.Id);
        var finalTracks = kept
            .Where(t => activeRoots.TryGetValue(t.RootId, out var r) && !r.IsExcluded(t.RelativePath))
            .ToList();
        var finalTrackIds = new HashSet<string>(finalTracks.Select(t => t.Id));
        removed.RemoveWhere(id => finalTrackIds.Contains(id));

        if (reread) _rereadAll = false;

        if (pending.Count == 0 && removed.Count == 0 && !cloudSynced && finalTracks.Count == _tracks.Count)
        {
            if (reread) SaveLibrary();
            return;
        }

        Publish(finalTracks);
        Forget(removed);
        _cloudCache?.RemoveForTracks(removed);

        var keepKeys = new HashSet<string>(
            _tracks.Select(t => t.ArtworkKey)
                .Concat(_metadataOverrides.Values.Select(o => o.ArtworkKey))
                .Where(k => k is not null)!);
        await Task.Run(() => Artwork.Prune(keepKeys));
    }

    private void UpdateCloudRootSynced(Guid rootId, string title)
    {
        int idx = _roots.FindIndex(r => r.Id == rootId);
        if (idx < 0) return;
        var updated = new List<LibraryRoot>(_roots);
        var current = updated[idx];
        updated[idx] = current with
        {
            Name = string.IsNullOrWhiteSpace(title) ? current.Name : title,
            LastSyncedUtc = DateTimeOffset.UtcNow
        };
        _roots = updated;
        OnPropertyChanged(nameof(Roots));
    }

    private async Task<List<Track>> BuildCloudTracksAsync(
        LibraryRoot root,
        CloudPlaylistResult result,
        IReadOnlyList<Track> existingTracks,
        CancellationToken ct)
    {
        var existingByRemoteId = new Dictionary<string, Track>(StringComparer.Ordinal);
        foreach (var t in existingTracks)
        {
            if (!string.IsNullOrWhiteSpace(t.RemoteTrackId))
            {
                existingByRemoteId.TryAdd(t.RemoteTrackId, t);
            }
        }

        var urlArtCache = new Dictionary<string, string?>(StringComparer.Ordinal);
        using var artHttp = CreateArtworkHttpClient(root);
        var now = DateTimeOffset.UtcNow;
        var built = new List<Track>(result.Items.Count);
        var seenTrackIds = new HashSet<string>(StringComparer.Ordinal);
        string cloudSource = root.Kind == RootKind.CloudBilibili ? CloudSources.Bilibili : CloudSources.YouTube;
        string albumArtist = root.Kind == RootKind.CloudBilibili ? "Bilibili" : "YouTube";

        foreach (var item in result.Items)
        {
            string relPath = MakeCloudRelativePath(item.RemoteTrackId);
            if (root.IsExcluded(relPath)) continue;
            string trackId = Track.MakeId(root.Id, relPath);
            if (!seenTrackIds.Add(trackId)) continue;

            existingByRemoteId.TryGetValue(item.RemoteTrackId, out var old);

            string? artKey = null;
            if (!string.IsNullOrWhiteSpace(item.ArtworkUrl))
            {
                if (old is not null &&
                    string.Equals(old.RemoteArtworkUrl, item.ArtworkUrl, StringComparison.Ordinal) &&
                    Artwork.GetImagePath(old.ArtworkKey) is not null)
                {
                    artKey = old.ArtworkKey;
                }
                else if (urlArtCache.TryGetValue(item.ArtworkUrl, out string? cachedKey))
                {
                    artKey = cachedKey;
                }
                else
                {
                    artKey = await DownloadAndStoreArtworkAsync(artHttp, root.Kind, item.ArtworkUrl, ct).ConfigureAwait(false);
                    urlArtCache[item.ArtworkUrl] = artKey;
                }
            }

            built.Add(ApplyMetadataOverride(new Track(
                Id: trackId,
                RootId: root.Id,
                RelativePath: relPath,
                Title: item.Title,
                Artist: item.Artist,
                Album: string.IsNullOrWhiteSpace(item.Album) ? result.Title : item.Album,
                AlbumArtist: albumArtist,
                TrackNumber: item.TrackNumber,
                DiscNumber: 1,
                DurationSeconds: item.DurationSeconds,
                FileSize: 0,
                ModifiedUtc: now,
                HasEmbeddedLyrics: false,
                DateAddedUtc: old?.DateAddedUtc ?? now,
                ArtworkKey: artKey,
                CloudSource: cloudSource,
                RemoteTrackId: item.RemoteTrackId,
                RemoteArtworkUrl: item.ArtworkUrl)));
        }

        return built;
    }

    private HttpClient CreateArtworkHttpClient(LibraryRoot root)
    {
        string? proxy = root.Kind == RootKind.CloudYouTube ? root.ProxyUrl : null;
        return CloudHttpHelper.CreateClient(proxy, _artworkHandler, TimeSpan.FromSeconds(15));
    }

    private async Task<string?> DownloadAndStoreArtworkAsync(HttpClient http, RootKind kind, string url, CancellationToken ct)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            if (kind == RootKind.CloudBilibili)
            {
                req.Headers.TryAddWithoutValidation("Referer", BilibiliClient.DefaultReferer);
                req.Headers.TryAddWithoutValidation("User-Agent", BilibiliClient.DefaultUserAgent);
            }
            using var resp = await http.SendAsync(req, ct).ConfigureAwait(false);
            if (!resp.IsSuccessStatusCode) return null;
            byte[] bytes = await resp.Content.ReadAsByteArrayAsync(ct).ConfigureAwait(false);
            return Artwork.Store(bytes);
        }
        catch
        {
            return null;
        }
    }

    private static string MakeCloudRelativePath(string remoteTrackId)
    {
        string safe = remoteTrackId.Replace('/', '_').Replace('\\', '_').Replace(':', '_');
        return $"{safe}.m4a";
    }

    private void Publish(IEnumerable<Track> list)
    {
        var activeRoots = _roots.ToDictionary(r => r.Id);
        _tracks = list
            .Where(t => activeRoots.TryGetValue(t.RootId, out var r) && !r.IsExcluded(t.RelativePath))
            .OrderBy(t => t.Title, StringComparer.CurrentCultureIgnoreCase)
            .ToList();
        RebuildIndex();
        OnPropertyChanged(nameof(Tracks));
        SaveLibrary();
    }

    private Track MakeTrack(LibraryRoot root, ScannedFile file, TrackMetadata metadata, string? artworkKey, DateTimeOffset dateAdded)
    {
        string fileName = Path.GetFileName(file.RelativePath);
        string titleFallback = Path.GetFileNameWithoutExtension(fileName);
        int lastSlash = file.RelativePath.LastIndexOf('/');
        string folderName = lastSlash > 0
            ? Path.GetFileName(file.RelativePath[..lastSlash])
            : string.Empty;

        string artist = metadata.Artist ?? "Unknown Artist";
        var track = new Track(
            Id: Track.MakeId(root.Id, file.RelativePath),
            RootId: root.Id,
            RelativePath: file.RelativePath,
            Title: metadata.Title ?? titleFallback,
            Artist: artist,
            Album: metadata.Album ?? (string.IsNullOrEmpty(folderName) ? "Unknown Album" : folderName),
            AlbumArtist: metadata.AlbumArtist ?? artist,
            TrackNumber: metadata.TrackNumber,
            DiscNumber: metadata.DiscNumber,
            DurationSeconds: metadata.DurationSeconds,
            FileSize: file.FileSize,
            ModifiedUtc: file.ModifiedUtc,
            HasEmbeddedLyrics: metadata.Lyrics is not null,
            DateAddedUtc: dateAdded,
            ArtworkKey: artworkKey,
            Genre: metadata.Genre);
        return ApplyMetadataOverride(track);
    }

    private Track ApplyMetadataOverride(Track track)
    {
        if (!_metadataOverrides.TryGetValue(track.Id, out var ov)) return track;

        string title = !string.IsNullOrWhiteSpace(ov.Title) ? ov.Title! : track.Title;
        string artist = !string.IsNullOrWhiteSpace(ov.Artist) ? ov.Artist! : track.Artist;
        string album = !string.IsNullOrWhiteSpace(ov.Album) ? ov.Album! : track.Album;
        string albumArtist = string.Equals(track.AlbumArtist, track.Artist, StringComparison.Ordinal) ||
                             string.IsNullOrWhiteSpace(track.AlbumArtist)
            ? artist
            : track.AlbumArtist;
        string? genre = ov.HasGenreOverride ? ov.Genre : track.Genre;
        string? artKey = !string.IsNullOrWhiteSpace(ov.ArtworkKey) ? ov.ArtworkKey : track.ArtworkKey;
        var modifiedUtc = ov.EditedUtc is DateTimeOffset edited && edited > track.ModifiedUtc
            ? edited
            : track.ModifiedUtc;

        return track with
        {
            Title = title,
            Artist = artist,
            Album = album,
            AlbumArtist = albumArtist,
            Genre = genre,
            ArtworkKey = artKey,
            ModifiedUtc = modifiedUtc
        };
    }

    public async Task<Track?> UpdateTrackMetadataAsync(
        string trackId,
        string title,
        string artist,
        string album,
        string? genre,
        byte[]? newArtworkBytes = null,
        CancellationToken ct = default)
    {
        var track = GetTrack(trackId);
        if (track is null) return null;

        string finalTitle = string.IsNullOrWhiteSpace(title) ? track.Title : title.Trim();
        string finalArtist = string.IsNullOrWhiteSpace(artist) ? "Unknown Artist" : artist.Trim();
        string finalAlbum = string.IsNullOrWhiteSpace(album) ? "Unknown Album" : album.Trim();
        string? finalGenre = string.IsNullOrWhiteSpace(genre) ? null : genre.Trim();

        string? finalArtKey = track.ArtworkKey;
        byte[]? artBytesForFile = null;
        if (newArtworkBytes is { Length: > 0 })
        {
            string? storedKey = Artwork.Store(newArtworkBytes);
            if (!string.IsNullOrEmpty(storedKey))
            {
                finalArtKey = storedKey;
                string? artPath = Artwork.GetImagePath(storedKey);
                if (!string.IsNullOrEmpty(artPath) && File.Exists(artPath))
                {
                    try { artBytesForFile = File.ReadAllBytes(artPath); } catch { /* ignore */ }
                }
                artBytesForFile ??= newArtworkBytes;
            }
        }

        long fileSize = track.FileSize;
        var modifiedUtc = DateTimeOffset.UtcNow;
        string? filePath = !track.IsCloud ? GetFilePath(track) : null;

        if (!string.IsNullOrWhiteSpace(filePath) && File.Exists(filePath))
        {
            await Task.Run(() =>
            {
                if (TagLibMetadataReader.TryWriteTags(filePath, finalTitle, finalArtist, finalAlbum, finalGenre, artBytesForFile))
                {
                    try
                    {
                        var fi = new FileInfo(filePath);
                        fileSize = fi.Length;
                        modifiedUtc = new DateTimeOffset(DateTime.SpecifyKind(fi.LastWriteTimeUtc, DateTimeKind.Utc));
                    }
                    catch
                    {
                        // Keep fallback timestamp/size if FileInfo fails.
                    }
                }
            }, ct).ConfigureAwait(false);
        }

        _metadataOverrides[trackId] = new TrackMetadataOverride(
            Title: finalTitle,
            Artist: finalArtist,
            Album: finalAlbum,
            Genre: finalGenre,
            HasGenreOverride: true,
            ArtworkKey: finalArtKey,
            EditedUtc: modifiedUtc);

        string albumArtist = string.Equals(track.AlbumArtist, track.Artist, StringComparison.Ordinal) ||
                             string.IsNullOrWhiteSpace(track.AlbumArtist)
            ? finalArtist
            : track.AlbumArtist;

        var updated = track with
        {
            Title = finalTitle,
            Artist = finalArtist,
            Album = finalAlbum,
            AlbumArtist = albumArtist,
            Genre = finalGenre,
            ArtworkKey = finalArtKey,
            FileSize = fileSize,
            ModifiedUtc = modifiedUtc
        };

        var nextTracks = _tracks.Select(t => t.Id == trackId ? updated : t).ToList();
        Publish(nextTracks);
        TrackUpdated?.Invoke(updated);
        return updated;
    }

    private void Forget(ISet<string> ids)
    {
        if (ids.Count == 0) return;

        var updatedPlaylists = new List<Playlist>(_playlists.Count);
        foreach (var pl in _playlists)
        {
            var filtered = pl.TrackIds.Where(id => !ids.Contains(id)).ToList();
            updatedPlaylists.Add(pl with { TrackIds = filtered });
        }
        _playlists = updatedPlaylists;
        _favourites.ExceptWith(ids);
        _favouritesOrder = _favouritesOrder.Where(id => !ids.Contains(id)).ToList();

        bool folderPrefsChanged = false;
        foreach (var key in _folderPrefs.Keys.ToList())
        {
            var prefs = _folderPrefs[key];
            if (prefs.CustomTrackOrder is { Count: > 0 } order && order.Any(ids.Contains))
            {
                _folderPrefs[key] = prefs with { CustomTrackOrder = order.Where(id => !ids.Contains(id)).ToList() };
                folderPrefsChanged = true;
            }
        }
        foreach (string id in ids)
        {
            _metadataOverrides.Remove(id);
        }

        OnPropertyChanged(nameof(Playlists));
        OnPropertyChanged(nameof(Favourites));
        SavePlaylists();
        if (folderPrefsChanged) SaveLibrary();
        OnTracksRemoved?.Invoke(ids);
    }

    private void RemoveFolderPrefsForRoot(Guid rootId)
    {
        string prefix = $"{rootId:D}:";
        foreach (string key in _folderPrefs.Keys.Where(k => k.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)).ToList())
        {
            _folderPrefs.Remove(key);
        }
    }

    // MARK: Index & Lookup

    private void RebuildIndex()
    {
        var byId = new Dictionary<string, Track>(_tracks.Count);
        var groups = new Dictionary<string, List<Track>>();

        foreach (var track in _tracks)
        {
            byId.TryAdd(track.Id, track);
            string key = $"{track.AlbumArtist}\u001F{track.Album}";
            if (!groups.TryGetValue(key, out var list))
            {
                list = new List<Track>();
                groups[key] = list;
            }
            list.Add(track);
        }

        _byId = byId;

        _albums = groups.Select(kvp =>
        {
            var sorted = kvp.Value
                .OrderBy(t => t.DiscNumber ?? 1)
                .ThenBy(t => t.TrackNumber ?? int.MaxValue)
                .ThenBy(t => t.Title, StringComparer.CurrentCultureIgnoreCase)
                .ToList();

            string? art = sorted.Select(t => t.ArtworkKey).FirstOrDefault(k => k is not null);
            return new AlbumInfo(
                Id: kvp.Key,
                Title: sorted[0].Album,
                Artist: sorted[0].AlbumArtist,
                TrackIds: sorted.Select(t => t.Id).ToList(),
                ArtworkKey: art);
        })
        .OrderBy(a => a.Title, StringComparer.CurrentCultureIgnoreCase)
        .ThenBy(a => a.Artist, StringComparer.Ordinal)
        .ToList();

        _artists = _tracks
            .Select(t => t.Artist)
            .Distinct(StringComparer.Ordinal)
            .OrderBy(a => a, StringComparer.CurrentCultureIgnoreCase)
            .ToList();

        OnPropertyChanged(nameof(Albums));
        OnPropertyChanged(nameof(Artists));
    }

    public Track? GetTrack(string id) => _byId.TryGetValue(id, out var track) ? track : null;

    public IReadOnlyList<Track> GetTracks(IEnumerable<string> ids)
    {
        var list = new List<Track>();
        foreach (string id in ids)
        {
            if (_byId.TryGetValue(id, out var track)) list.Add(track);
        }
        return list;
    }

    public string? GetFilePath(Track track) =>
        _rootPaths.TryGetValue(track.RootId, out string? rootPath)
            ? FolderScanner.CombinePath(rootPath, track.RelativePath)
            : null;

    public Task<string?> GetEmbeddedLyricsAsync(Track track)
    {
        if (!track.HasEmbeddedLyrics) return Task.FromResult<string?>(null);
        string? path = GetFilePath(track);
        if (path is null) return Task.FromResult<string?>(null);
        return ReadLyricsAsync(path);
    }

    private async Task<string?> ReadLyricsAsync(string path) => (await _reader.ReadAsync(path)).Lyrics;

    public IReadOnlyList<AlbumInfo> GetAlbumsByArtist(string artist)
    {
        return _albums
            .Where(a => a.Artist == artist || a.TrackIds.Any(id => _byId.TryGetValue(id, out var t) && t.Artist == artist))
            .ToList();
    }

    public IReadOnlyList<Track> GetTracksByArtist(string artist) =>
        _tracks.Where(t => t.Artist == artist).ToList();

    public static string MakeFolderOrderKey(Guid rootId, string path) =>
        $"{rootId:D}:{LibraryRoot.NormalizeFolderPath(path)}";

    public TrackSortMode GetFolderSortMode(Guid rootId, string path) =>
        _folderPrefs.TryGetValue(MakeFolderOrderKey(rootId, path), out var prefs)
            ? prefs.SortMode
            : TrackSortMode.NameAscending;

    public void SetFolderSortMode(Guid rootId, string path, TrackSortMode mode)
    {
        string key = MakeFolderOrderKey(rootId, path);
        _folderPrefs.TryGetValue(key, out var existing);
        _folderPrefs[key] = existing is not null
            ? existing with { SortMode = mode }
            : new FolderViewPrefs(SortMode: mode);
        SaveLibrary();
        OnPropertyChanged(nameof(Tracks));
    }

    public void MoveFolderTrack(Guid rootId, string path, int from, int to, PlayHistoryStore? history = null)
    {
        var current = GetFolderContents(rootId, path, history).Tracks.Select(t => t.Id).ToList();
        if (from < 0 || from >= current.Count || to < 0 || to >= current.Count || from == to)
        {
            return;
        }

        string moved = current[from];
        current.RemoveAt(from);
        current.Insert(to, moved);

        string key = MakeFolderOrderKey(rootId, path);
        _folderPrefs[key] = new FolderViewPrefs(TrackSortMode.Custom, current);
        SaveLibrary();
        OnPropertyChanged(nameof(Tracks));
    }

    /// <summary>Direct children of a folder: sorted subfolder names, then direct tracks ordered by folder prefs.</summary>
    public (IReadOnlyList<string> Folders, IReadOnlyList<Track> Tracks) GetFolderContents(Guid rootId, string path) =>
        GetFolderContents(rootId, path, null);

    public (IReadOnlyList<string> Folders, IReadOnlyList<Track> Tracks) GetFolderContents(
        Guid rootId,
        string path,
        PlayHistoryStore? history)
    {
        string normPath = LibraryRoot.NormalizeFolderPath(path);
        string prefix = string.IsNullOrEmpty(normPath) ? string.Empty : normPath + "/";
        var folders = new HashSet<string>(StringComparer.Ordinal);
        var direct = new List<Track>();

        foreach (var track in _tracks)
        {
            if (track.RootId != rootId || !track.RelativePath.StartsWith(prefix, StringComparison.Ordinal)) continue;
            string rest = track.RelativePath[prefix.Length..];
            int slash = rest.IndexOf('/');
            if (slash >= 0)
            {
                folders.Add(rest[..slash]);
            }
            else
            {
                direct.Add(track);
            }
        }

        IReadOnlyList<Track> orderedDirect;
        if (_folderPrefs.TryGetValue(MakeFolderOrderKey(rootId, normPath), out var prefs))
        {
            orderedDirect = prefs.SortMode == TrackSortMode.Custom
                ? TrackSorter.ApplyCustomOrder(direct, prefs.CustomTrackOrder)
                : TrackSorter.Sort(direct, prefs.SortMode, history);
        }
        else
        {
            orderedDirect = direct.OrderBy(t => t.FileName, StringComparer.CurrentCultureIgnoreCase).ToList();
        }

        return (
            folders.OrderBy(f => f, StringComparer.CurrentCultureIgnoreCase).ToList(),
            orderedDirect
        );
    }

    /// <summary>Every song in a folder and its subfolders, respecting persisted folder sort/custom order when present.</summary>
    public IReadOnlyList<Track> GetTracksUnder(Guid rootId, string path) =>
        GetTracksUnder(rootId, path, null);

    public IReadOnlyList<Track> GetTracksUnder(Guid rootId, string path, PlayHistoryStore? history)
    {
        string normPath = LibraryRoot.NormalizeFolderPath(path);
        string prefix = string.IsNullOrEmpty(normPath) ? string.Empty : normPath + "/";
        string rootPrefix = $"{rootId:D}:";
        string selfKey = MakeFolderOrderKey(rootId, normPath);
        string subKeyPrefix = string.IsNullOrEmpty(normPath)
            ? rootPrefix
            : $"{selfKey}/";

        bool hasPrefsInTree = _folderPrefs.ContainsKey(selfKey) ||
            _folderPrefs.Keys.Any(k => k.StartsWith(subKeyPrefix, StringComparison.Ordinal));

        if (!hasPrefsInTree)
        {
            return _tracks
                .Where(t => t.RootId == rootId && t.RelativePath.StartsWith(prefix, StringComparison.Ordinal))
                .OrderBy(t => t.RelativePath, StringComparer.CurrentCultureIgnoreCase)
                .ToList();
        }

        var result = new List<Track>();
        CollectFolderTracksOrdered(rootId, normPath, history, result);
        return result;
    }

    private void CollectFolderTracksOrdered(Guid rootId, string normPath, PlayHistoryStore? history, List<Track> output)
    {
        var (subfolders, directTracks) = GetFolderContents(rootId, normPath, history);
        output.AddRange(directTracks);
        foreach (string sub in subfolders)
        {
            string childPath = string.IsNullOrEmpty(normPath) ? sub : $"{normPath}/{sub}";
            CollectFolderTracksOrdered(rootId, childPath, history, output);
        }
    }

    public IReadOnlyList<Track> Search(string query) => Search(query, SearchField.Keyword);

    public IReadOnlyList<Track> Search(string query, SearchField field)
    {
        string needle = query.Trim();
        if (needle.Length == 0) return Array.Empty<Track>();

        var compare = CultureInfo.CurrentCulture.CompareInfo;
        const CompareOptions options = CompareOptions.IgnoreCase | CompareOptions.IgnoreNonSpace;

        return field switch
        {
            SearchField.Artist => _tracks.Where(t => compare.IndexOf(t.Artist, needle, options) >= 0).ToList(),
            SearchField.Album => _tracks.Where(t => compare.IndexOf(t.Album, needle, options) >= 0).ToList(),
            SearchField.Genre => _tracks.Where(t => t.Genre is not null && compare.IndexOf(t.Genre, needle, options) >= 0).ToList(),
            _ => _tracks.Where(t =>
                compare.IndexOf(t.Title, needle, options) >= 0 ||
                compare.IndexOf(t.Artist, needle, options) >= 0 ||
                compare.IndexOf(t.Album, needle, options) >= 0 ||
                (t.Genre is not null && compare.IndexOf(t.Genre, needle, options) >= 0)).ToList()
        };
    }

    // MARK: Favourites and Playlists

    public bool IsFavourite(string id) => _favourites.Contains(id);

    public TrackSortMode GetFavouritesSortMode() => _favouritesSortMode;

    public IReadOnlyList<Track> GetFavouriteTracks(PlayHistoryStore? history = null)
    {
        var favTracks = _tracks.Where(t => _favourites.Contains(t.Id)).ToList();
        if (_favouritesSortMode == TrackSortMode.Custom)
        {
            return _favouritesOrder.Count > 0
                ? TrackSorter.ApplyCustomOrder(favTracks, _favouritesOrder)
                : favTracks;
        }
        return TrackSorter.Sort(favTracks, _favouritesSortMode, history);
    }

    public void SetFavouritesSortMode(TrackSortMode mode, PlayHistoryStore? history = null)
    {
        _favouritesSortMode = mode;
        if (mode != TrackSortMode.Custom)
        {
            _favouritesOrder = TrackSorter.Sort(_tracks.Where(t => _favourites.Contains(t.Id)), mode, history)
                .Select(t => t.Id)
                .ToList();
        }
        OnPropertyChanged(nameof(Favourites));
        SavePlaylists();
    }

    public void MoveFavouriteTrack(int from, int to, PlayHistoryStore? history = null)
    {
        var current = GetFavouriteTracks(history).Select(t => t.Id).ToList();
        if (from < 0 || from >= current.Count || to < 0 || to >= current.Count || from == to)
        {
            return;
        }
        string moved = current[from];
        current.RemoveAt(from);
        current.Insert(to, moved);

        _favouritesOrder = current;
        _favouritesSortMode = TrackSortMode.Custom;
        OnPropertyChanged(nameof(Favourites));
        SavePlaylists();
    }

    public void ToggleFavourite(string id)
    {
        if (!_favourites.Remove(id))
        {
            _favourites.Add(id);
            if (!_favouritesOrder.Contains(id, StringComparer.Ordinal))
            {
                _favouritesOrder.Add(id);
            }
        }
        else
        {
            _favouritesOrder.RemoveAll(x => x == id);
        }
        OnPropertyChanged(nameof(Favourites));
        SavePlaylists();
    }

    public IReadOnlyList<Track> GetPlaylistTracks(Guid playlistId, PlayHistoryStore? history = null)
    {
        var pl = _playlists.FirstOrDefault(p => p.Id == playlistId);
        if (pl is null) return Array.Empty<Track>();
        var tracks = GetTracks(pl.TrackIds);
        return pl.SortMode == TrackSortMode.Custom
            ? tracks
            : TrackSorter.Sort(tracks, pl.SortMode, history);
    }

    public void SetPlaylistSortMode(Guid playlistId, TrackSortMode mode, PlayHistoryStore? history = null)
    {
        UpdatePlaylist(playlistId, pl =>
        {
            if (mode == TrackSortMode.Custom)
            {
                return pl with { SortMode = TrackSortMode.Custom };
            }

            var resolved = TrackSorter.Sort(GetTracks(pl.TrackIds), mode, history).Select(t => t.Id).ToList();
            var unresolved = pl.TrackIds.Where(id => !_byId.ContainsKey(id));
            resolved.AddRange(unresolved);
            return pl with { TrackIds = resolved, SortMode = mode };
        });
    }

    public Guid CreatePlaylist(string name)
    {
        var playlist = new Playlist(Guid.NewGuid(), name.Trim(), new List<string>());
        _playlists = [.. _playlists, playlist];
        OnPropertyChanged(nameof(Playlists));
        SavePlaylists();
        return playlist.Id;
    }

    public void RenamePlaylist(Guid id, string name)
    {
        string trimmed = name.Trim();
        if (trimmed.Length == 0) return;
        UpdatePlaylist(id, pl => pl with { Name = trimmed });
    }

    public void DeletePlaylist(Guid id)
    {
        _playlists = _playlists.Where(p => p.Id != id).ToList();
        OnPropertyChanged(nameof(Playlists));
        SavePlaylists();
    }

    public void AddToPlaylist(IEnumerable<string> ids, Guid playlistId)
    {
        var toAdd = ids.ToList();
        if (toAdd.Count == 0) return;
        UpdatePlaylist(playlistId, pl =>
        {
            var next = new List<string>(pl.TrackIds);
            next.AddRange(toAdd);
            return pl with { TrackIds = next };
        });
    }

    public void RemoveFromPlaylist(Guid playlistId, int index) =>
        RemoveFromPlaylist(playlistId, index, null);

    public void RemoveFromPlaylist(Guid playlistId, int index, PlayHistoryStore? history)
    {
        UpdatePlaylist(playlistId, pl =>
        {
            List<string> next = pl.SortMode == TrackSortMode.Custom
                ? new List<string>(pl.TrackIds)
                : TrackSorter.Sort(GetTracks(pl.TrackIds), pl.SortMode, history)
                    .Select(t => t.Id)
                    .Concat(pl.TrackIds.Where(id => !_byId.ContainsKey(id)))
                    .ToList();
            if (index < 0 || index >= next.Count) return pl;
            next.RemoveAt(index);
            return pl with { TrackIds = next };
        });
    }

    public void MovePlaylistTrack(Guid playlistId, int from, int to) =>
        MovePlaylistTrack(playlistId, from, to, null);

    public void MovePlaylistTrack(Guid playlistId, int from, int to, PlayHistoryStore? history)
    {
        UpdatePlaylist(playlistId, pl =>
        {
            List<string> next = pl.SortMode == TrackSortMode.Custom
                ? new List<string>(pl.TrackIds)
                : TrackSorter.Sort(GetTracks(pl.TrackIds), pl.SortMode, history).Select(t => t.Id).ToList();

            if (from < 0 || from >= next.Count || to < 0 || to >= next.Count || from == to)
                return pl;

            string item = next[from];
            next.RemoveAt(from);
            next.Insert(to, item);
            return pl with { TrackIds = next, SortMode = TrackSortMode.Custom };
        });
    }

    private void UpdatePlaylist(Guid id, Func<Playlist, Playlist> updater)
    {
        int index = _playlists.FindIndex(p => p.Id == id);
        if (index < 0) return;
        var updated = new List<Playlist>(_playlists);
        updated[index] = updater(updated[index]);
        _playlists = updated;
        OnPropertyChanged(nameof(Playlists));
        SavePlaylists();
    }
}
