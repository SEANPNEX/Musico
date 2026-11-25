using System.Diagnostics;
using System.Globalization;
using System.Net.Http;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Extensions;
using MusicoWindows.Models;

namespace MusicoWindows.Stores;

public sealed record CloudSyncProgress(
    Guid RootId,
    int FoldersDone,
    int FoldersTotal,
    int TracksDiscovered,
    bool IsCompleted,
    string? CurrentFolderPath = null);

/// <summary>
/// The music library: folder roots, the track index, albums, artists, playlists, and favourites.
/// </summary>
public sealed class LibraryStore : ObservableStore
{
    private sealed class LibraryFile
    {
        public List<LibraryRoot> Roots { get; set; } = new();
        public List<Track> Tracks { get; set; } = new();
        public Dictionary<string, Track>? EphemeralTracks { get; set; }
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
    private readonly GoogleDriveClient _googleDrive;
    private readonly CloudProviderRegistry _providerRegistry;
    private readonly HttpMessageHandler? _artworkHandler;
    private readonly CloudAudioCache? _cloudCache;

    private sealed class ActiveCloudSync
    {
        public CancellationTokenSource Cts { get; } = new();
        public CloudTreeTraversalHandle? Handle { get; set; }
        public Task Task { get; set; } = Task.CompletedTask;
    }

    private readonly object _cloudSyncLock = new();
    private readonly Dictionary<Guid, ActiveCloudSync> _activeCloudSyncs = new();
    private readonly Dictionary<Guid, CloudSyncProgress> _cloudSyncProgress = new();
    private readonly Dictionary<Guid, HashSet<string>> _knownCloudSubfolders = new();

    private readonly Dictionary<Guid, string> _rootPaths = new();
    private Dictionary<string, Track> _byId = new();
    private readonly object _ephemeralLock = new();
    private Dictionary<string, Track> _ephemeralTracks = new(StringComparer.Ordinal);

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
        CloudAudioCache? cloudCache = null,
        GoogleDriveClient? googleDriveClient = null,
        CloudProviderRegistry? providerRegistry = null)
    {
        _stateDirectory = stateDirectory;
        _musicDirectory = musicDirectory;
        _reader = reader;
        Artwork = artwork;
        _scanBatchSize = Math.Max(scanBatchSize, 1);
        _appFolderRemovable = appFolderRemovable;
        _youtube = youtubeClient ?? new YouTubeClient();
        _bilibili = bilibiliClient ?? new BilibiliClient();
        _googleDrive = googleDriveClient ?? new GoogleDriveClient();
        _providerRegistry = providerRegistry ?? new CloudProviderRegistry(_youtube, _bilibili, _googleDrive);
        _providerRegistry.Changed += OnCloudProvidersChanged;
        _artworkHandler = artworkHandler;
        _cloudCache = cloudCache;

        var file = Persistence.Load<LibraryFile>(LibraryPath);
        _rereadAll = file is not null && file.ReaderVersion != CurrentReaderVersion;

        var savedAppRoot = file?.Roots.FirstOrDefault(r => r.Kind == RootKind.AppFolder);
        var loadedRoots = file?.Roots.Where(r => r.Kind != RootKind.AppFolder).ToList() ?? new List<LibraryRoot>();
        _appFolderRemoved = _appFolderRemovable && file?.AppFolderRemoved == true;

        if (!_appFolderRemoved)
        {
            loadedRoots.Insert(0, CreateAppFolderRoot() with
            {
                SourceUrl = savedAppRoot?.SourceUrl,
                RemoteId = savedAppRoot?.RemoteId,
                Cookie = savedAppRoot?.Cookie,
                ProxyUrl = savedAppRoot?.ProxyUrl,
                LastSyncedUtc = savedAppRoot?.LastSyncedUtc,
                ExcludedPaths = savedAppRoot?.ExcludedPaths,
                LinkedCloudKind = savedAppRoot?.LinkedCloudKind,
                TrackedSubfolders = savedAppRoot?.TrackedSubfolders,
                LinkedProviderId = savedAppRoot?.LinkedProviderId,
                StreamQualityId = savedAppRoot?.StreamQualityId,
                DownloadQualityId = savedAppRoot?.DownloadQualityId
            });
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
        if (file?.EphemeralTracks is { Count: > 0 })
        {
            lock (_ephemeralLock)
            {
                foreach (var kvp in file.EphemeralTracks)
                {
                    if (string.IsNullOrWhiteSpace(kvp.Key) || kvp.Value is null) continue;
                    _ephemeralTracks[kvp.Key] = kvp.Value with { IsEphemeral = true };
                }
            }
        }
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
    public CloudProviderRegistry CloudProviders => _providerRegistry;
    public Action<ISet<string>>? OnTracksRemoved { get; set; }
    public Action<IReadOnlyDictionary<string, string>>? OnTracksMigrated { get; set; }
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
            Dictionary<string, Track>? ephemeralSnapshot = null;
            lock (_ephemeralLock)
            {
                if (_ephemeralTracks.Count > 0)
                {
                    ephemeralSnapshot = new Dictionary<string, Track>(_ephemeralTracks, StringComparer.Ordinal);
                }
            }

            Persistence.Save(new LibraryFile
            {
                Roots = _roots,
                Tracks = _tracks,
                EphemeralTracks = ephemeralSnapshot,
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

    private void OnCloudProvidersChanged()
    {
        OnPropertyChanged(nameof(Roots));
        OnPropertyChanged(nameof(Tracks));
    }

    // MARK: Roots

    public bool IsAvailable(LibraryRoot root)
    {
        if (root.Kind == RootKind.CloudExtension)
        {
            return _providerRegistry.GetProvider(root) is { IsAvailable: true };
        }
        return root.IsCloud || _rootPaths.ContainsKey(root.Id);
    }

    public IReadOnlyList<ICloudSourceProvider> GetCompatibleRecoveryProviders(LibraryRoot root)
    {
        ArgumentNullException.ThrowIfNull(root);
        if (root.Kind != RootKind.CloudExtension || IsAvailable(root))
        {
            return Array.Empty<ICloudSourceProvider>();
        }
        return _providerRegistry.GetCompatibleProviders(root.SourceUrl);
    }

    public bool IsAvailable(Track track)
    {
        if (track.IsCloud)
        {
            var root = GetRoot(track.RootId);
            if (root is not null && root.IsCloud)
            {
                return IsAvailable(root);
            }
            return _providerRegistry.GetBySourceOrProviderId(track.CloudSource) is { IsAvailable: true };
        }
        return _rootPaths.ContainsKey(track.RootId);
    }

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

    private static bool IsStrictSubdirectory(string parentNormalized, string childNormalized)
    {
        string prefix = Path.EndsInDirectorySeparator(parentNormalized)
            ? parentNormalized
            : parentNormalized + Path.DirectorySeparatorChar;
        return childNormalized.StartsWith(prefix, StringComparison.OrdinalIgnoreCase);
    }

    private static (string Key, CloudTrackingInfo Value)? FindTrackedSubfolderEntry(
        Dictionary<string, CloudTrackingInfo>? map,
        string normalizedRelativePath)
    {
        if (map is null || map.Count == 0 || string.IsNullOrEmpty(normalizedRelativePath)) return null;
        if (map.TryGetValue(normalizedRelativePath, out var exact))
        {
            return (normalizedRelativePath, exact);
        }
        foreach (var kvp in map)
        {
            if (string.Equals(LibraryRoot.NormalizeFolderPath(kvp.Key), normalizedRelativePath, StringComparison.OrdinalIgnoreCase))
            {
                return (kvp.Key, kvp.Value);
            }
        }
        return null;
    }

    public async Task AddRootAsync(string path, LibraryRoot? cloudSourceRoot = null)
    {
        if (string.IsNullOrWhiteSpace(path) || !Directory.Exists(path))
        {
            throw new DirectoryNotFoundException("That location could not be opened as a folder.");
        }

        string normalized = NormalizeDirPath(path);
        foreach (var existingRoot in _roots)
        {
            if (!existingRoot.IsLocal || !_rootPaths.TryGetValue(existingRoot.Id, out string? existingPath)) continue;
            string normExisting = NormalizeDirPath(existingPath);
            if (string.Equals(normExisting, normalized, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException("That folder is already in your library.");
            }
            if (IsStrictSubdirectory(normExisting, normalized))
            {
                throw new InvalidOperationException("That folder is already inside an indexed library folder.");
            }
        }

        Guid newRootId = Guid.NewGuid();
        string name = new DirectoryInfo(normalized).Name;
        string? sourceUrl = null;
        string? remoteId = null;
        string? cookie = null;
        string? proxyUrl = null;
        DateTimeOffset? lastSyncedUtc = null;
        RootKind? linkedCloudKind = null;
        string? linkedProviderId = null;
        string? streamQualityId = null;
        string? downloadQualityId = null;

        if (cloudSourceRoot is not null &&
            (cloudSourceRoot.EffectiveCloudKind is not null || !string.IsNullOrWhiteSpace(cloudSourceRoot.EffectiveProviderId)) &&
            !string.IsNullOrWhiteSpace(cloudSourceRoot.SourceUrl))
        {
            sourceUrl = cloudSourceRoot.SourceUrl.Trim();
            remoteId = cloudSourceRoot.RemoteId;
            cookie = cloudSourceRoot.Cookie;
            proxyUrl = cloudSourceRoot.ProxyUrl;
            lastSyncedUtc = DateTimeOffset.UtcNow;
            linkedCloudKind = cloudSourceRoot.EffectiveCloudKind ?? RootKind.CloudExtension;
            linkedProviderId = cloudSourceRoot.EffectiveProviderId;
            streamQualityId = cloudSourceRoot.StreamQualityId;
            downloadQualityId = cloudSourceRoot.DownloadQualityId;
        }
        else if (CloudExporter.LoadManifest(normalized) is { } manifest)
        {
            sourceUrl = manifest.SourceUrl.Trim();
            remoteId = manifest.RemoteId;
            lastSyncedUtc = manifest.LastSyncedUtc;
            (linkedCloudKind, linkedProviderId) = ResolveCloudFromManifest(manifest, sourceUrl);
            streamQualityId = manifest.StreamQualityId;
            downloadQualityId = manifest.DownloadQualityId;
        }

        var migratedTrackedSubfolders = new Dictionary<string, CloudTrackingInfo>(StringComparer.Ordinal);
        var migratedExcluded = new List<string>();
        var idMigrations = new Dictionary<string, string>(StringComparer.Ordinal);
        var childIdsToRemove = new HashSet<Guid>();

        foreach (var existingRoot in _roots)
        {
            if (!existingRoot.IsLocal || !_rootPaths.TryGetValue(existingRoot.Id, out string? existingPath)) continue;
            if (existingRoot.Kind == RootKind.AppFolder && !_appFolderRemovable) continue;

            string normChild = NormalizeDirPath(existingPath);
            if (!IsStrictSubdirectory(normalized, normChild)) continue;

            childIdsToRemove.Add(existingRoot.Id);
            string relPrefix = LibraryRoot.NormalizeFolderPath(Path.GetRelativePath(normalized, normChild));

            if (existingRoot.IsCloudTrackedLocal && existingRoot.EffectiveCloudKind is { } childCloudKind)
            {
                migratedTrackedSubfolders[relPrefix] = new CloudTrackingInfo(
                    existingRoot.SourceUrl!.Trim(),
                    existingRoot.RemoteId,
                    childCloudKind,
                    existingRoot.Cookie,
                    existingRoot.ProxyUrl,
                    existingRoot.LastSyncedUtc,
                    existingRoot.EffectiveProviderId,
                    existingRoot.StreamQualityId,
                    existingRoot.DownloadQualityId);
            }

            if (existingRoot.TrackedSubfolders is { Count: > 0 })
            {
                foreach (var kvp in existingRoot.TrackedSubfolders)
                {
                    string normSub = LibraryRoot.NormalizeFolderPath(kvp.Key);
                    string combined = string.IsNullOrEmpty(normSub) ? relPrefix : $"{relPrefix}/{normSub}";
                    migratedTrackedSubfolders[combined] = kvp.Value;
                }
            }

            if (existingRoot.ExcludedPaths is { Count: > 0 })
            {
                foreach (string ex in existingRoot.ExcludedPaths)
                {
                    string normEx = LibraryRoot.NormalizeFolderPath(ex);
                    if (normEx.Length > 0)
                    {
                        migratedExcluded.Add($"{relPrefix}/{normEx}");
                    }
                }
            }

            if (existingRoot.Kind == RootKind.AppFolder)
            {
                AppFolderRemoved = true;
            }
        }

        if (childIdsToRemove.Count > 0)
        {
            var remappedTracks = new List<Track>(_tracks.Count);
            foreach (var t in _tracks)
            {
                if (childIdsToRemove.Contains(t.RootId) &&
                    _rootPaths.TryGetValue(t.RootId, out string? childPath))
                {
                    string relPrefix = LibraryRoot.NormalizeFolderPath(Path.GetRelativePath(normalized, NormalizeDirPath(childPath)));
                    string newRel = string.IsNullOrEmpty(relPrefix) ? t.RelativePath : $"{relPrefix}/{t.RelativePath}";
                    string newId = Track.MakeId(newRootId, newRel);
                    if (!string.Equals(t.Id, newId, StringComparison.Ordinal))
                    {
                        idMigrations[t.Id] = newId;
                    }
                    remappedTracks.Add(t with { Id = newId, RootId = newRootId, RelativePath = newRel });
                }
                else
                {
                    remappedTracks.Add(t);
                }
            }
            _tracks = remappedTracks;

            foreach (var childId in childIdsToRemove)
            {
                _rootPaths.Remove(childId);
                RemoveFolderPrefsForRoot(childId);
            }

            _roots = _roots.Where(r => !childIdsToRemove.Contains(r.Id)).ToList();
            MigrateTrackReferences(idMigrations);
            RebuildIndex();
        }

        var root = new LibraryRoot(
            Id: newRootId,
            Name: name,
            Kind: RootKind.Picked,
            Path: normalized,
            SourceUrl: sourceUrl,
            RemoteId: remoteId,
            Cookie: cookie,
            ProxyUrl: proxyUrl,
            LastSyncedUtc: lastSyncedUtc,
            ExcludedPaths: migratedExcluded.Count > 0 ? migratedExcluded : null,
            LinkedCloudKind: linkedCloudKind,
            TrackedSubfolders: migratedTrackedSubfolders.Count > 0 ? migratedTrackedSubfolders : null,
            LinkedProviderId: linkedProviderId,
            StreamQualityId: streamQualityId,
            DownloadQualityId: downloadQualityId);
        _roots = [.. _roots, root];
        _rootPaths[root.Id] = normalized;
        OnPropertyChanged(nameof(Roots));
        SaveLibrary();
        await RescanAsync();
        var addedIds = GetTracksUnder(root.Id, string.Empty).Select(t => t.Id).ToList();
        OnFolderAdded?.Invoke(root.Name, addedIds);
    }

    public async Task RegisterExportedCloudFolderAsync(string destinationFolder, LibraryRoot cloudRoot)
    {
        if (string.IsNullOrWhiteSpace(destinationFolder)) return;
        string target = NormalizeDirPath(destinationFolder);

        CloudTrackingInfo? tracking = null;
        if ((cloudRoot.EffectiveCloudKind is { } || !string.IsNullOrWhiteSpace(cloudRoot.EffectiveProviderId)) &&
            !string.IsNullOrWhiteSpace(cloudRoot.SourceUrl))
        {
            tracking = new CloudTrackingInfo(
                cloudRoot.SourceUrl.Trim(),
                cloudRoot.RemoteId,
                cloudRoot.EffectiveCloudKind ?? RootKind.CloudExtension,
                cloudRoot.Cookie,
                cloudRoot.ProxyUrl,
                DateTimeOffset.UtcNow,
                cloudRoot.EffectiveProviderId,
                cloudRoot.StreamQualityId,
                cloudRoot.DownloadQualityId);
        }

        for (int i = 0; i < _roots.Count; i++)
        {
            var root = _roots[i];
            if (!root.IsLocal || !_rootPaths.TryGetValue(root.Id, out string? rootPath)) continue;
            string normRoot = NormalizeDirPath(rootPath);

            if (string.Equals(target, normRoot, StringComparison.OrdinalIgnoreCase))
            {
                if (tracking is not null)
                {
                    var updated = new List<LibraryRoot>(_roots);
                    updated[i] = root with
                    {
                        SourceUrl = tracking.SourceUrl,
                        RemoteId = tracking.RemoteId ?? root.RemoteId,
                        Cookie = tracking.Cookie ?? root.Cookie,
                        ProxyUrl = tracking.ProxyUrl ?? root.ProxyUrl,
                        LastSyncedUtc = tracking.LastSynced,
                        LinkedCloudKind = tracking.CloudKind,
                        LinkedProviderId = tracking.ResolvedProviderId,
                        StreamQualityId = tracking.StreamQualityId ?? root.StreamQualityId,
                        DownloadQualityId = tracking.DownloadQualityId ?? root.DownloadQualityId
                    };
                    _roots = updated;
                    OnPropertyChanged(nameof(Roots));
                    SaveLibrary();
                }

                await RescanAsync();
                NotifyFolderAddedByPath(target);
                return;
            }

            if (IsStrictSubdirectory(normRoot, target))
            {
                string relPath = LibraryRoot.NormalizeFolderPath(Path.GetRelativePath(normRoot, target));
                var updatedRoot = root;

                if (root.ExcludedPaths is { Count: > 0 } && root.IsExcluded(relPath))
                {
                    var nextExcluded = root.ExcludedPaths
                        .Select(LibraryRoot.NormalizeFolderPath)
                        .Where(ex => ex.Length > 0 &&
                                     !string.Equals(ex, relPath, StringComparison.Ordinal) &&
                                     !relPath.StartsWith(ex + "/", StringComparison.Ordinal) &&
                                     !ex.StartsWith(relPath + "/", StringComparison.Ordinal))
                        .ToList();
                    updatedRoot = updatedRoot with
                    {
                        ExcludedPaths = nextExcluded.Count > 0 ? nextExcluded : null
                    };
                }

                if (tracking is not null && relPath.Length > 0)
                {
                    var nextTracked = updatedRoot.TrackedSubfolders is not null
                        ? new Dictionary<string, CloudTrackingInfo>(updatedRoot.TrackedSubfolders, StringComparer.Ordinal)
                        : new Dictionary<string, CloudTrackingInfo>(StringComparer.Ordinal);
                    if (FindTrackedSubfolderEntry(nextTracked, relPath) is { } existing &&
                        !string.Equals(existing.Key, relPath, StringComparison.Ordinal))
                    {
                        nextTracked.Remove(existing.Key);
                    }
                    nextTracked[relPath] = tracking;
                    updatedRoot = updatedRoot with { TrackedSubfolders = nextTracked };
                }

                if (!ReferenceEquals(updatedRoot, root))
                {
                    var updated = new List<LibraryRoot>(_roots);
                    updated[i] = updatedRoot;
                    _roots = updated;
                    OnPropertyChanged(nameof(Roots));
                    SaveLibrary();
                }

                await RescanAsync();
                NotifyFolderAddedByPath(target);
                return;
            }
        }

        await AddRootAsync(target, cloudRoot);
    }

    public void AttachCloudTracking(string folderPath, LibraryRoot cloudSourceRoot)
    {
        if (string.IsNullOrWhiteSpace(folderPath)) return;
        if ((cloudSourceRoot.EffectiveCloudKind is not { } cloudKind && string.IsNullOrWhiteSpace(cloudSourceRoot.EffectiveProviderId)) ||
            string.IsNullOrWhiteSpace(cloudSourceRoot.SourceUrl)) return;

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
            LinkedCloudKind = cloudSourceRoot.EffectiveCloudKind ?? RootKind.CloudExtension,
            LinkedProviderId = cloudSourceRoot.EffectiveProviderId,
            StreamQualityId = cloudSourceRoot.StreamQualityId ?? updated[idx].StreamQualityId,
            DownloadQualityId = cloudSourceRoot.DownloadQualityId ?? updated[idx].DownloadQualityId
        };
        _roots = updated;
        OnPropertyChanged(nameof(Roots));
        SaveLibrary();
    }

    public void MarkRootCloudSynced(Guid rootId) => MarkCloudSynced(rootId, string.Empty);

    public void MarkCloudSynced(Guid rootId, string relativePath)
    {
        int idx = _roots.FindIndex(r => r.Id == rootId);
        if (idx < 0) return;

        string norm = LibraryRoot.NormalizeFolderPath(relativePath);
        var updated = new List<LibraryRoot>(_roots);
        var current = updated[idx];

        if (norm.Length == 0)
        {
            updated[idx] = current with { LastSyncedUtc = DateTimeOffset.UtcNow };
        }
        else if (FindTrackedSubfolderEntry(current.TrackedSubfolders, norm) is { } entry)
        {
            var nextTracked = new Dictionary<string, CloudTrackingInfo>(current.TrackedSubfolders!, StringComparer.Ordinal);
            nextTracked[entry.Key] = new CloudTrackingInfo(
                entry.Value.SourceUrl,
                entry.Value.RemoteId,
                entry.Value.CloudKind,
                entry.Value.Cookie,
                entry.Value.ProxyUrl,
                DateTimeOffset.UtcNow,
                entry.Value.ProviderId,
                entry.Value.StreamQualityId,
                entry.Value.DownloadQualityId);
            updated[idx] = current with { TrackedSubfolders = nextTracked };
        }
        else
        {
            return;
        }

        _roots = updated;
        OnPropertyChanged(nameof(Roots));
        SaveLibrary();
    }

    public CloudTrackingInfo? GetCloudTracking(Guid rootId, string relativePath)
    {
        var root = GetRoot(rootId);
        if (root is null) return null;

        string norm = LibraryRoot.NormalizeFolderPath(relativePath);
        if (norm.Length == 0)
        {
            if ((root.EffectiveCloudKind is { } || !string.IsNullOrWhiteSpace(root.EffectiveProviderId)) &&
                !string.IsNullOrWhiteSpace(root.SourceUrl))
            {
                return new CloudTrackingInfo(
                    root.SourceUrl!,
                    root.RemoteId,
                    root.EffectiveCloudKind ?? RootKind.CloudExtension,
                    root.Cookie,
                    root.ProxyUrl,
                    root.LastSyncedUtc,
                    root.EffectiveProviderId,
                    root.StreamQualityId,
                    root.DownloadQualityId);
            }
            return null;
        }

        return FindTrackedSubfolderEntry(root.TrackedSubfolders, norm)?.Value;
    }

    private static string? NormalizeQualityForProvider(ICloudSourceProvider provider, string? qualityId)
    {
        if (provider.AvailableQualities.Count == 0 || string.IsNullOrWhiteSpace(qualityId))
        {
            return null;
        }
        string trimmed = qualityId.Trim();
        var match = provider.AvailableQualities.FirstOrDefault(q =>
            string.Equals(q.Id.Trim(), trimmed, StringComparison.OrdinalIgnoreCase));
        return match?.Id.Trim();
    }

    public void UpdateCloudTrackingAddress(
        Guid rootId,
        string relativePath,
        string newSourceUrl,
        string? cookie,
        string? proxyUrl,
        string? preferredProviderId = null,
        string? streamQualityId = null,
        string? downloadQualityId = null,
        bool updateQualityPrefs = false)
    {
        var (provider, match) = Task.Run(() =>
            CloudUrlClassifier.ClassifyAsync(newSourceUrl, _providerRegistry, preferredProviderId, CancellationToken.None))
            .GetAwaiter()
            .GetResult();

        string extractedUrl = !string.IsNullOrWhiteSpace(match.NormalizedUrl)
            ? match.NormalizedUrl!
            : CloudUrlClassifier.ExtractCloudUrl(newSourceUrl, _providerRegistry);
        RootKind newKind = provider.RootKind;
        string? normalizedProxy = provider.SupportsProxy ? YouTubeClient.NormalizeProxyUrl(proxyUrl) : null;
        string? trimmedCookie = provider.SupportsCookies && !string.IsNullOrWhiteSpace(cookie) ? cookie.Trim() : null;
        string? resolvedRemoteId = match.RemoteId ?? provider.TryExtractRemoteId(extractedUrl);

        int idx = _roots.FindIndex(r => r.Id == rootId);
        if (idx < 0) return;

        var current = _roots[idx];
        string norm = LibraryRoot.NormalizeFolderPath(relativePath);
        var updated = new List<LibraryRoot>(_roots);

        if (norm.Length == 0)
        {
            string? resolvedStreamQuality = updateQualityPrefs
                ? NormalizeQualityForProvider(provider, streamQualityId)
                : NormalizeQualityForProvider(provider, streamQualityId ?? current.StreamQualityId);
            string? resolvedDownloadQuality = updateQualityPrefs
                ? NormalizeQualityForProvider(provider, downloadQualityId)
                : NormalizeQualityForProvider(provider, downloadQualityId ?? current.DownloadQualityId);

            updated[idx] = current.IsCloud
                ? current with
                {
                    Kind = newKind,
                    Path = extractedUrl,
                    SourceUrl = extractedUrl,
                    RemoteId = resolvedRemoteId ?? current.RemoteId,
                    Cookie = trimmedCookie,
                    ProxyUrl = normalizedProxy,
                    ProviderId = provider.ProviderId,
                    StreamQualityId = resolvedStreamQuality,
                    DownloadQualityId = resolvedDownloadQuality
                }
                : current with
                {
                    SourceUrl = extractedUrl,
                    RemoteId = resolvedRemoteId ?? current.RemoteId,
                    LinkedCloudKind = newKind,
                    Cookie = trimmedCookie,
                    ProxyUrl = normalizedProxy,
                    LinkedProviderId = provider.ProviderId,
                    StreamQualityId = resolvedStreamQuality,
                    DownloadQualityId = resolvedDownloadQuality
                };

            if (current.IsLocal && _rootPaths.TryGetValue(rootId, out string? rootDiskPath) && Directory.Exists(rootDiskPath))
            {
                UpdateManifestOnDisk(
                    rootDiskPath,
                    extractedUrl,
                    resolvedRemoteId ?? current.RemoteId,
                    newKind,
                    provider.ProviderId,
                    resolvedStreamQuality,
                    resolvedDownloadQuality);
            }
        }
        else
        {
            var nextTracked = current.TrackedSubfolders is not null
                ? new Dictionary<string, CloudTrackingInfo>(current.TrackedSubfolders, StringComparer.Ordinal)
                : new Dictionary<string, CloudTrackingInfo>(StringComparer.Ordinal);
            var existing = FindTrackedSubfolderEntry(nextTracked, norm);
            string key = existing?.Key ?? norm;

            string? resolvedStreamQuality = updateQualityPrefs
                ? NormalizeQualityForProvider(provider, streamQualityId)
                : NormalizeQualityForProvider(provider, streamQualityId ?? existing?.Value.StreamQualityId);
            string? resolvedDownloadQuality = updateQualityPrefs
                ? NormalizeQualityForProvider(provider, downloadQualityId)
                : NormalizeQualityForProvider(provider, downloadQualityId ?? existing?.Value.DownloadQualityId);

            nextTracked[key] = new CloudTrackingInfo(
                sourceUrl: extractedUrl,
                remoteId: resolvedRemoteId ?? existing?.Value.RemoteId,
                cloudKind: newKind,
                cookie: trimmedCookie,
                proxyUrl: normalizedProxy,
                lastSynced: existing?.Value.LastSynced,
                providerId: provider.ProviderId,
                streamQualityId: resolvedStreamQuality,
                downloadQualityId: resolvedDownloadQuality);

            updated[idx] = current with { TrackedSubfolders = nextTracked };

            if (current.IsLocal && _rootPaths.TryGetValue(rootId, out string? rootDiskPath))
            {
                string subDiskPath = FolderScanner.CombinePath(rootDiskPath, norm);
                if (Directory.Exists(subDiskPath))
                {
                    UpdateManifestOnDisk(
                        subDiskPath,
                        extractedUrl,
                        resolvedRemoteId ?? existing?.Value.RemoteId,
                        newKind,
                        provider.ProviderId,
                        resolvedStreamQuality,
                        resolvedDownloadQuality);
                }
            }
        }

        _roots = updated;
        OnPropertyChanged(nameof(Roots));
        SaveLibrary();
    }

    private static void UpdateManifestOnDisk(
        string folderPath,
        string sourceUrl,
        string? remoteId,
        RootKind cloudKind,
        string? providerId,
        string? streamQualityId = null,
        string? downloadQualityId = null)
    {
        var existing = CloudExporter.LoadManifest(folderPath);
        var manifest = new CloudFolderManifest(
            SourceUrl: sourceUrl,
            RemoteId: remoteId ?? existing?.RemoteId,
            CloudKind: cloudKind.ToString(),
            LastSyncedUtc: existing?.LastSyncedUtc ?? DateTimeOffset.UtcNow,
            Tracks: existing?.Tracks is not null
                ? new Dictionary<string, string>(existing.Tracks, StringComparer.Ordinal)
                : new Dictionary<string, string>(StringComparer.Ordinal),
            ProviderId: providerId ?? existing?.ProviderId,
            StreamQualityId: streamQualityId,
            DownloadQualityId: downloadQualityId);
        CloudExporter.SaveManifest(folderPath, manifest);
    }

    private (RootKind? Kind, string? ProviderId) ResolveCloudFromManifest(CloudFolderManifest manifest, string sourceUrl)
    {
        if (!string.IsNullOrWhiteSpace(manifest.ProviderId))
        {
            var provider = _providerRegistry.GetById(manifest.ProviderId);
            RootKind kind = provider?.RootKind
                ?? (Enum.TryParse<RootKind>(manifest.CloudKind, ignoreCase: true, out var parsed) && parsed.IsCloud()
                    ? parsed
                    : RootKind.CloudExtension);
            return (kind, manifest.ProviderId);
        }

        if (Enum.TryParse<RootKind>(manifest.CloudKind, ignoreCase: true, out var parsedKind) && parsedKind.IsCloud())
        {
            return (parsedKind, parsedKind.BuiltinProviderId());
        }

        try
        {
            var classified = CloudUrlClassifier.Classify(sourceUrl, _providerRegistry);
            return (classified, classified.BuiltinProviderId());
        }
        catch
        {
            return (null, null);
        }
    }

    public CloudSyncProgress? GetCloudSyncProgress(Guid rootId)
    {
        lock (_cloudSyncLock)
        {
            return _cloudSyncProgress.TryGetValue(rootId, out var progress) ? progress : null;
        }
    }

    public void PrioritizeCloudFolder(Guid rootId, string path)
    {
        CloudTreeTraversalHandle? handle;
        lock (_cloudSyncLock)
        {
            _activeCloudSyncs.TryGetValue(rootId, out var active);
            handle = active?.Handle;
        }
        handle?.PrioritizeFolder(path);
    }

    public Task WaitForCloudSyncAsync(Guid rootId)
    {
        lock (_cloudSyncLock)
        {
            return _activeCloudSyncs.TryGetValue(rootId, out var active)
                ? active.Task
                : Task.CompletedTask;
        }
    }

    private void CancelCloudSync(Guid rootId)
    {
        ActiveCloudSync? active;
        lock (_cloudSyncLock)
        {
            if (_activeCloudSyncs.Remove(rootId, out active))
            {
                active.Cts.Cancel();
            }
            _cloudSyncProgress.Remove(rootId);
            _knownCloudSubfolders.Remove(rootId);
        }
    }

    private bool IsDuplicateCloudRoot(RootKind kind, string? providerId, string? remoteId, Guid? excludeRootId = null) =>
        !string.IsNullOrWhiteSpace(remoteId) &&
        _roots.Any(r =>
            r.Id != excludeRootId &&
            r.Kind == kind &&
            string.Equals(r.ResolvedProviderId, providerId, StringComparison.OrdinalIgnoreCase) &&
            string.Equals(r.RemoteId, remoteId, StringComparison.OrdinalIgnoreCase));

    public async Task<LibraryRoot> AddCloudRootAsync(
        string sourceUrl,
        string? cookie = null,
        string? proxyUrl = null,
        CancellationToken ct = default,
        string? preferredProviderId = null,
        string? streamQualityId = null,
        string? downloadQualityId = null)
    {
        var (provider, match) = await CloudUrlClassifier
            .ClassifyAsync(sourceUrl, _providerRegistry, preferredProviderId, ct)
            .ConfigureAwait(false);
        string effectiveUrl = !string.IsNullOrWhiteSpace(match.NormalizedUrl)
            ? match.NormalizedUrl!
            : CloudUrlClassifier.ExtractCloudUrl(sourceUrl, _providerRegistry);
        string? normalizedProxy = provider.SupportsProxy ? YouTubeClient.NormalizeProxyUrl(proxyUrl) : null;
        string? trimmedCookie = provider.SupportsCookies && !string.IsNullOrWhiteSpace(cookie) ? cookie.Trim() : null;
        string? normalizedStreamQuality = NormalizeQualityForProvider(provider, streamQualityId);
        string? normalizedDownloadQuality = NormalizeQualityForProvider(provider, downloadQualityId);

        if (!provider.UsesFlatCloudListing)
        {
            var session = await provider
                .StartProgressiveFetchAsync(effectiveUrl, trimmedCookie, normalizedProxy, ct)
                .ConfigureAwait(false);

            if (session is not null)
            {
                var rootResult = session.RootResult;
                string resolvedProviderId = rootResult.ResolvedProviderId ?? provider.ProviderId;
                if (IsDuplicateCloudRoot(rootResult.Kind, resolvedProviderId, rootResult.RemoteId))
                {
                    session.Dispose();
                    throw new InvalidOperationException("That cloud playlist is already in your library.");
                }

                var root = new LibraryRoot(
                    Id: Guid.NewGuid(),
                    Name: rootResult.Title,
                    Kind: rootResult.Kind,
                    Path: rootResult.SourceUrl,
                    SourceUrl: rootResult.SourceUrl,
                    RemoteId: rootResult.RemoteId,
                    Cookie: trimmedCookie,
                    ProxyUrl: normalizedProxy,
                    LastSyncedUtc: DateTimeOffset.UtcNow,
                    ProviderId: resolvedProviderId,
                    StreamQualityId: normalizedStreamQuality,
                    DownloadQualityId: normalizedDownloadQuality);

                var initialTracks = await BuildCloudTracksAsync(root, rootResult, Array.Empty<Track>(), ct, provider: provider)
                    .ConfigureAwait(false);

                lock (_cloudSyncLock)
                {
                    if (session.DiscoveredSubfolderPaths.Count > 0)
                    {
                        var known = new HashSet<string>(StringComparer.Ordinal);
                        foreach (string sub in session.DiscoveredSubfolderPaths)
                        {
                            string normSub = LibraryRoot.NormalizeFolderPath(sub);
                            if (normSub.Length > 0) known.Add(normSub);
                        }
                        _knownCloudSubfolders[root.Id] = known;
                    }

                    _cloudSyncProgress[root.Id] = new CloudSyncProgress(
                        RootId: root.Id,
                        FoldersDone: 1,
                        FoldersTotal: 1 + session.DiscoveredSubfolderPaths.Count,
                        TracksDiscovered: initialTracks.Count,
                        IsCompleted: !session.HasMoreSubfolders);
                }

                _roots = [.. _roots, root];
                OnPropertyChanged(nameof(Roots));
                Publish([.. _tracks, .. initialTracks]);
                OnFolderAdded?.Invoke(root.Name, initialTracks.Select(t => t.Id).ToList());

                if (session.HasMoreSubfolders)
                {
                    var active = new ActiveCloudSync { Handle = session.TraversalHandle };
                    lock (_cloudSyncLock)
                    {
                        _activeCloudSyncs[root.Id] = active;
                    }
                    active.Task = Task.Run(() => RunTreeBackgroundSyncAsync(root, provider, session, active, initialTracks), CancellationToken.None);
                }
                else
                {
                    session.Dispose();
                }

                return root;
            }
        }

        CloudPlaylistResult result = await provider
            .FetchPlaylistAsync(effectiveUrl, trimmedCookie, normalizedProxy, ct)
            .ConfigureAwait(false);

        string flatProviderId = result.ResolvedProviderId ?? provider.ProviderId;
        if (IsDuplicateCloudRoot(result.Kind, flatProviderId, result.RemoteId))
        {
            throw new InvalidOperationException("That cloud playlist is already in your library.");
        }

        var flatRoot = new LibraryRoot(
            Id: Guid.NewGuid(),
            Name: result.Title,
            Kind: result.Kind,
            Path: result.SourceUrl,
            SourceUrl: result.SourceUrl,
            RemoteId: result.RemoteId,
            Cookie: trimmedCookie,
            ProxyUrl: normalizedProxy,
            LastSyncedUtc: DateTimeOffset.UtcNow,
            ProviderId: flatProviderId,
            StreamQualityId: normalizedStreamQuality,
            DownloadQualityId: normalizedDownloadQuality);

        var newTracks = await BuildCloudTracksAsync(flatRoot, result, Array.Empty<Track>(), ct, provider: provider)
            .ConfigureAwait(false);

        _roots = [.. _roots, flatRoot];
        OnPropertyChanged(nameof(Roots));
        Publish([.. _tracks, .. newTracks]);
        OnFolderAdded?.Invoke(flatRoot.Name, newTracks.Select(t => t.Id).ToList());
        return flatRoot;
    }

    public async Task<LibraryRoot> UpdateCloudRootSourceAsync(
        Guid rootId,
        string newSourceUrl,
        string? cookie,
        string? proxyUrl,
        string? preferredProviderId = null,
        string? streamQualityId = null,
        string? downloadQualityId = null,
        bool updateQualityPrefs = true,
        CancellationToken ct = default)
    {
        var current = GetRoot(rootId);
        if (current is null || !current.IsCloud)
        {
            throw new InvalidOperationException("Cloud folder was not found.");
        }

        var (provider, match) = await CloudUrlClassifier
            .ClassifyAsync(newSourceUrl, _providerRegistry, preferredProviderId, ct)
            .ConfigureAwait(false);

        string effectiveUrl = !string.IsNullOrWhiteSpace(match.NormalizedUrl)
            ? match.NormalizedUrl!
            : CloudUrlClassifier.ExtractCloudUrl(newSourceUrl, _providerRegistry);
        string? normalizedProxy = provider.SupportsProxy ? YouTubeClient.NormalizeProxyUrl(proxyUrl) : null;
        string? trimmedCookie = provider.SupportsCookies && !string.IsNullOrWhiteSpace(cookie) ? cookie.Trim() : null;
        string? normalizedStreamQuality = updateQualityPrefs
            ? NormalizeQualityForProvider(provider, streamQualityId)
            : NormalizeQualityForProvider(provider, streamQualityId ?? current.StreamQualityId);
        string? normalizedDownloadQuality = updateQualityPrefs
            ? NormalizeQualityForProvider(provider, downloadQualityId)
            : NormalizeQualityForProvider(provider, downloadQualityId ?? current.DownloadQualityId);

        bool providerChanged = !string.Equals(current.ResolvedProviderId, provider.ProviderId, StringComparison.OrdinalIgnoreCase) ||
                               current.Kind != provider.RootKind;
        bool urlChanged = !string.Equals(current.SourceUrl, effectiveUrl, StringComparison.Ordinal);
        bool authOrProxyChanged = !string.Equals(current.Cookie, trimmedCookie, StringComparison.Ordinal) ||
                                  !string.Equals(current.ProxyUrl, normalizedProxy, StringComparison.Ordinal);
        bool needsResync = providerChanged || urlChanged || authOrProxyChanged || !IsAvailable(current);

        if (!needsResync)
        {
            int idx = _roots.FindIndex(r => r.Id == rootId);
            if (idx < 0) throw new InvalidOperationException("Cloud folder was not found.");
            var updatedNoSync = _roots[idx] with
            {
                StreamQualityId = normalizedStreamQuality,
                DownloadQualityId = normalizedDownloadQuality
            };
            var nextRoots = new List<LibraryRoot>(_roots);
            nextRoots[idx] = updatedNoSync;
            _roots = nextRoots;
            OnPropertyChanged(nameof(Roots));
            SaveLibrary();
            return updatedNoSync;
        }

        CancelCloudSync(rootId);
        var existingTracks = _tracks.Where(t => t.RootId == rootId).ToList();

        static string ResolveRootName(LibraryRoot oldRoot, string nextUrl, string? fetchedTitle)
        {
            bool sameUrl = string.Equals(oldRoot.SourceUrl, nextUrl, StringComparison.OrdinalIgnoreCase);
            if (sameUrl && !string.IsNullOrWhiteSpace(oldRoot.Name))
            {
                return oldRoot.Name;
            }
            return string.IsNullOrWhiteSpace(fetchedTitle) ? oldRoot.Name : fetchedTitle;
        }

        if (!provider.UsesFlatCloudListing)
        {
            var session = await provider
                .StartProgressiveFetchAsync(effectiveUrl, trimmedCookie, normalizedProxy, ct)
                .ConfigureAwait(false);

            if (session is not null)
            {
                var rootResult = session.RootResult;
                string resolvedProviderId = rootResult.ResolvedProviderId ?? provider.ProviderId;
                if (IsDuplicateCloudRoot(rootResult.Kind, resolvedProviderId, rootResult.RemoteId, excludeRootId: rootId))
                {
                    session.Dispose();
                    throw new InvalidOperationException("That cloud playlist is already in your library.");
                }

                var updatedRoot = current with
                {
                    Name = ResolveRootName(current, rootResult.SourceUrl, rootResult.Title),
                    Kind = rootResult.Kind,
                    Path = rootResult.SourceUrl,
                    SourceUrl = rootResult.SourceUrl,
                    RemoteId = rootResult.RemoteId,
                    Cookie = trimmedCookie,
                    ProxyUrl = normalizedProxy,
                    LastSyncedUtc = DateTimeOffset.UtcNow,
                    ProviderId = resolvedProviderId,
                    StreamQualityId = normalizedStreamQuality,
                    DownloadQualityId = normalizedDownloadQuality
                };

                var idMigrations = new Dictionary<string, string>(StringComparer.Ordinal);
                var initialTracks = await BuildCloudTracksAsync(updatedRoot, rootResult, existingTracks, ct, idMigrations, provider)
                    .ConfigureAwait(false);

                int rootIdx = _roots.FindIndex(r => r.Id == rootId);
                if (rootIdx < 0)
                {
                    session.Dispose();
                    throw new InvalidOperationException("Cloud folder was removed.");
                }

                var nextRoots = new List<LibraryRoot>(_roots);
                nextRoots[rootIdx] = updatedRoot;
                _roots = nextRoots;
                OnPropertyChanged(nameof(Roots));

                if (!session.HasMoreSubfolders)
                {
                    ApplyReboundCloudTracks(rootId, existingTracks, initialTracks, idMigrations, clearExistingCache: providerChanged || urlChanged);
                    session.Dispose();
                    return updatedRoot;
                }

                lock (_cloudSyncLock)
                {
                    if (session.DiscoveredSubfolderPaths.Count > 0)
                    {
                        var known = new HashSet<string>(StringComparer.Ordinal);
                        foreach (string sub in session.DiscoveredSubfolderPaths)
                        {
                            string normSub = LibraryRoot.NormalizeFolderPath(sub);
                            if (normSub.Length > 0) known.Add(normSub);
                        }
                        _knownCloudSubfolders[rootId] = known;
                    }

                    _cloudSyncProgress[rootId] = new CloudSyncProgress(
                        RootId: rootId,
                        FoldersDone: 1,
                        FoldersTotal: 1 + session.DiscoveredSubfolderPaths.Count,
                        TracksDiscovered: initialTracks.Count,
                        IsCompleted: false);
                }

                ApplyReboundCloudTracks(rootId, existingTracks, initialTracks, idMigrations, clearExistingCache: providerChanged || urlChanged);

                var active = new ActiveCloudSync { Handle = session.TraversalHandle };
                lock (_cloudSyncLock)
                {
                    _activeCloudSyncs[rootId] = active;
                }
                active.Task = Task.Run(() => RunTreeBackgroundSyncAsync(updatedRoot, provider, session, active, initialTracks), CancellationToken.None);
                return updatedRoot;
            }
        }

        CloudPlaylistResult result = await provider
            .FetchPlaylistAsync(effectiveUrl, trimmedCookie, normalizedProxy, ct)
            .ConfigureAwait(false);

        string flatProviderId = result.ResolvedProviderId ?? provider.ProviderId;
        if (IsDuplicateCloudRoot(result.Kind, flatProviderId, result.RemoteId, excludeRootId: rootId))
        {
            throw new InvalidOperationException("That cloud playlist is already in your library.");
        }

        var updatedFlatRoot = current with
        {
            Name = ResolveRootName(current, result.SourceUrl, result.Title),
            Kind = result.Kind,
            Path = result.SourceUrl,
            SourceUrl = result.SourceUrl,
            RemoteId = result.RemoteId,
            Cookie = trimmedCookie,
            ProxyUrl = normalizedProxy,
            LastSyncedUtc = DateTimeOffset.UtcNow,
            ProviderId = flatProviderId,
            StreamQualityId = normalizedStreamQuality,
            DownloadQualityId = normalizedDownloadQuality
        };

        var flatMigrations = new Dictionary<string, string>(StringComparer.Ordinal);
        var syncedTracks = await BuildCloudTracksAsync(updatedFlatRoot, result, existingTracks, ct, flatMigrations, provider)
            .ConfigureAwait(false);

        int idx2 = _roots.FindIndex(r => r.Id == rootId);
        if (idx2 < 0)
        {
            throw new InvalidOperationException("Cloud folder was removed.");
        }

        var updatedList = new List<LibraryRoot>(_roots);
        updatedList[idx2] = updatedFlatRoot;
        _roots = updatedList;
        OnPropertyChanged(nameof(Roots));

        ApplyReboundCloudTracks(rootId, existingTracks, syncedTracks, flatMigrations, clearExistingCache: providerChanged || urlChanged);
        return updatedFlatRoot;
    }

    private void ApplyReboundCloudTracks(
        Guid rootId,
        List<Track> existingTracks,
        List<Track> syncedTracks,
        Dictionary<string, string> idMigrations,
        bool clearExistingCache)
    {
        if (idMigrations.Count > 0)
        {
            MigrateTrackReferences(idMigrations);
        }

        var syncedIds = new HashSet<string>(syncedTracks.Select(t => t.Id), StringComparer.Ordinal);
        var removedIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var old in existingTracks)
        {
            if (!syncedIds.Contains(old.Id) && !idMigrations.ContainsKey(old.Id))
            {
                removedIds.Add(old.Id);
            }
        }

        var otherTracks = _tracks.Where(t => t.RootId != rootId).ToList();
        Publish([.. otherTracks, .. syncedTracks]);

        if (removedIds.Count > 0)
        {
            Forget(removedIds);
        }

        if (clearExistingCache)
        {
            _cloudCache?.RemoveForTracks(existingTracks.Select(t => t.Id).Concat(syncedTracks.Select(t => t.Id)));
        }
        else if (removedIds.Count > 0)
        {
            _cloudCache?.RemoveForTracks(removedIds);
        }
    }

    private async Task RunTreeBackgroundSyncAsync(
        LibraryRoot root,
        ICloudSourceProvider provider,
        CloudTreeSession session,
        ActiveCloudSync active,
        List<Track> initialTracks)
    {
        var ct = active.Cts.Token;
        var pendingNewTracks = new List<Track>();
        var syncTimer = Stopwatch.StartNew();
        long lastUiNotifyMs = 0;
        int discoveredCount = initialTracks.Count;

        void FlushPendingTracksLocked(bool force)
        {
            if (!_roots.Any(r => r.Id == root.Id))
            {
                pendingNewTracks.Clear();
                return;
            }

            if (pendingNewTracks.Count > 0)
            {
                var existingById = new HashSet<string>(_tracks.Select(t => t.Id), StringComparer.Ordinal);
                var toAdd = pendingNewTracks.Where(t => existingById.Add(t.Id)).ToList();
                pendingNewTracks.Clear();
                if (toAdd.Count > 0)
                {
                    Publish([.. _tracks, .. toAdd]);
                }
                else if (force)
                {
                    OnPropertyChanged(nameof(Tracks));
                }
            }
            else if (force)
            {
                OnPropertyChanged(nameof(Tracks));
            }
        }

        try
        {
            var finalResult = await session.ContinueRemainingAsync(batch =>
            {
                if (ct.IsCancellationRequested) return;
                if (!_roots.Any(r => r.Id == root.Id)) return;

                var batchTracks = BuildCloudTracksSync(root, provider, batch.Items, root.Name);
                bool shouldNotify = false;
                lock (_cloudSyncLock)
                {
                    if (!_roots.Any(r => r.Id == root.Id)) return;

                    if (batch.DiscoveredSubfolderPaths.Count > 0)
                    {
                        if (!_knownCloudSubfolders.TryGetValue(root.Id, out var known))
                        {
                            known = new HashSet<string>(StringComparer.Ordinal);
                            _knownCloudSubfolders[root.Id] = known;
                        }
                        foreach (string sub in batch.DiscoveredSubfolderPaths)
                        {
                            string normSub = LibraryRoot.NormalizeFolderPath(sub);
                            if (normSub.Length > 0) known.Add(normSub);
                        }
                    }

                    pendingNewTracks.AddRange(batchTracks);
                    discoveredCount += batchTracks.Count;

                    _cloudSyncProgress[root.Id] = new CloudSyncProgress(
                        RootId: root.Id,
                        FoldersDone: batch.FoldersVisited,
                        FoldersTotal: Math.Max(batch.KnownFoldersTotal, batch.FoldersVisited),
                        TracksDiscovered: discoveredCount,
                        IsCompleted: batch.IsComplete,
                        CurrentFolderPath: batch.FolderRelativePath);

                    long nowMs = syncTimer.ElapsedMilliseconds;
                    if (batch.IsComplete || pendingNewTracks.Count >= 100 || (nowMs - lastUiNotifyMs) >= 300)
                    {
                        lastUiNotifyMs = nowMs;
                        shouldNotify = true;
                        FlushPendingTracksLocked(force: batch.IsComplete);
                    }
                }

                if (shouldNotify)
                {
                    OnPropertyChanged(nameof(CloudSyncProgress));
                }
            }, ct).ConfigureAwait(false);

            lock (_cloudSyncLock)
            {
                if (_roots.Any(r => r.Id == root.Id))
                {
                    FlushPendingTracksLocked(force: true);
                    _cloudSyncProgress[root.Id] = new CloudSyncProgress(
                        RootId: root.Id,
                        FoldersDone: finalResult.Items.Count > 0 ? Math.Max(1, _cloudSyncProgress.GetValueOrDefault(root.Id)?.FoldersDone ?? 1) : 1,
                        FoldersTotal: Math.Max(1, _cloudSyncProgress.GetValueOrDefault(root.Id)?.FoldersTotal ?? 1),
                        TracksDiscovered: _tracks.Count(t => t.RootId == root.Id),
                        IsCompleted: true);
                    UpdateCloudRootSynced(root.Id, finalResult.Title);
                    SaveLibrary();
                }
            }
            OnPropertyChanged(nameof(CloudSyncProgress));
        }
        catch (OperationCanceledException)
        {
            // Cancelled via RemoveRoot or shutdown.
        }
        catch
        {
            lock (_cloudSyncLock)
            {
                FlushPendingTracksLocked(force: true);
                if (_cloudSyncProgress.TryGetValue(root.Id, out var prev))
                {
                    _cloudSyncProgress[root.Id] = prev with { IsCompleted = true };
                }
            }
            OnPropertyChanged(nameof(CloudSyncProgress));
        }
        finally
        {
            lock (_cloudSyncLock)
            {
                if (_activeCloudSyncs.TryGetValue(root.Id, out var current) && ReferenceEquals(current, active))
                {
                    _activeCloudSyncs.Remove(root.Id);
                }
            }
            active.Cts.Dispose();
            session.Dispose();
        }
    }

    private List<Track> BuildCloudTracksSync(
        LibraryRoot root,
        ICloudSourceProvider provider,
        IReadOnlyList<CloudPlaylistItem> items,
        string fallbackAlbum)
    {
        var now = DateTimeOffset.UtcNow;
        var list = new List<Track>(items.Count);
        foreach (var item in items)
        {
            string relPath = MakeCloudRelativePath(item);
            if (root.IsExcluded(relPath)) continue;
            string trackId = Track.MakeId(root.Id, relPath);
            list.Add(MakeCloudTrack(
                root: root,
                trackId: trackId,
                relPath: relPath,
                item: item,
                fallbackAlbum: fallbackAlbum,
                albumArtist: provider.DisplayName,
                cloudSource: provider.CloudSourceKey,
                artworkKey: null,
                dateAddedUtc: now,
                now: now));
        }
        return list;
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

        CancelCloudSync(id);

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
        var allIdMigrations = new Dictionary<string, string>(StringComparer.Ordinal);
        bool reread = _rereadAll;
        bool cloudSynced = false;
        bool trackedSubfoldersChanged = false;

        foreach (var root in snapshotRoots)
        {
            if (!_rootPaths.TryGetValue(root.Id, out string? rootPath)) continue;

            var existingTracks = snapshotTracks.Where(t => t.RootId == root.Id).ToList();
            var (scanned, manifestFolders) = await Task.Run(() => FolderScanner.ScanWithManifests(rootPath));

            // Treat unreadable folders or picked folders that suddenly list zero files (when they had tracks)
            // as temporarily unreachable so we do not purge playlists.
            if (scanned is null || (scanned.Count == 0 && existingTracks.Count > 0 && root.Kind == RootKind.Picked))
            {
                kept.AddRange(existingTracks);
                continue;
            }

            if (SyncTrackedSubfoldersFromManifests(root.Id, rootPath, manifestFolders))
            {
                trackedSubfoldersChanged = true;
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
                         file.ModifiedUtc <= edited))
                    && !(old.ArtworkKey is null &&
                         ImageFormatHelper.FindSiblingCoverPath(FolderScanner.CombinePath(rootPath, file.RelativePath)) is not null))
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

        if (trackedSubfoldersChanged)
        {
            OnPropertyChanged(nameof(Roots));
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

        // Sync cloud roots; preserve existing cloud tracks on transient network failure or disabled extensions.
        foreach (var root in snapshotRoots.Where(r => r.IsCloud))
        {
            bool activeSyncRunning;
            lock (_cloudSyncLock)
            {
                activeSyncRunning = _activeCloudSyncs.ContainsKey(root.Id);
            }
            if (activeSyncRunning)
            {
                kept.AddRange(_tracks.Where(t => t.RootId == root.Id));
                continue;
            }

            var existingTracks = snapshotTracks.Where(t => t.RootId == root.Id).ToList();
            var provider = _providerRegistry.GetProvider(root);
            if (provider is null || !provider.IsAvailable)
            {
                kept.AddRange(existingTracks);
                continue;
            }

            try
            {
                string source = root.SourceUrl ?? root.Path ?? string.Empty;
                CloudPlaylistResult result = await provider
                    .FetchPlaylistAsync(source, root.Cookie, root.ProxyUrl)
                    .ConfigureAwait(false);

                if (result.DiscoveredSubfolderPaths is { Count: > 0 })
                {
                    lock (_cloudSyncLock)
                    {
                        var known = new HashSet<string>(StringComparer.Ordinal);
                        foreach (string sub in result.DiscoveredSubfolderPaths)
                        {
                            string normSub = LibraryRoot.NormalizeFolderPath(sub);
                            if (normSub.Length > 0) known.Add(normSub);
                        }
                        _knownCloudSubfolders[root.Id] = known;
                    }
                }

                var idMigrations = new Dictionary<string, string>(StringComparer.Ordinal);
                var synced = await BuildCloudTracksAsync(root, result, existingTracks, CancellationToken.None, idMigrations, provider)
                    .ConfigureAwait(false);
                kept.AddRange(synced);

                foreach (var kvp in idMigrations)
                {
                    allIdMigrations[kvp.Key] = kvp.Value;
                }

                var syncedIds = new HashSet<string>(synced.Select(t => t.Id), StringComparer.Ordinal);
                foreach (var old in existingTracks)
                {
                    if (!syncedIds.Contains(old.Id) && !idMigrations.ContainsKey(old.Id))
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

        if (allIdMigrations.Count > 0)
        {
            MigrateTrackReferences(allIdMigrations);
        }

        // A root or subfolder removed while scanning must not leave orphan tracks behind.
        var activeRoots = _roots.ToDictionary(r => r.Id);
        var finalTracks = kept
            .Where(t => activeRoots.TryGetValue(t.RootId, out var r) && !r.IsExcluded(t.RelativePath))
            .ToList();
        var finalTrackIds = new HashSet<string>(finalTracks.Select(t => t.Id));
        removed.RemoveWhere(id => finalTrackIds.Contains(id));

        if (reread) _rereadAll = false;

        if (pending.Count == 0 &&
            removed.Count == 0 &&
            !cloudSynced &&
            !trackedSubfoldersChanged &&
            allIdMigrations.Count == 0 &&
            finalTracks.Count == _tracks.Count)
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

    private bool SyncTrackedSubfoldersFromManifests(
        Guid rootId,
        string rootPath,
        IReadOnlyList<string> manifestFolders)
    {
        int idx = _roots.FindIndex(r => r.Id == rootId);
        if (idx < 0) return false;
        var root = _roots[idx];

        var nextTracked = new Dictionary<string, CloudTrackingInfo>(StringComparer.Ordinal);
        foreach (string relDir in manifestFolders)
        {
            string normRel = LibraryRoot.NormalizeFolderPath(relDir);
            if (normRel.Length == 0 || root.IsExcluded(normRel)) continue;

            string fullDir = FolderScanner.CombinePath(rootPath, normRel);
            var manifest = CloudExporter.LoadManifest(fullDir);
            if (manifest is null) continue;

            string sourceUrl = manifest.SourceUrl.Trim();
            var (resolvedKind, resolvedProviderId) = ResolveCloudFromManifest(manifest, sourceUrl);
            if (resolvedKind is not { } cloudKind) continue;

            var existingEntry = FindTrackedSubfolderEntry(root.TrackedSubfolders, normRel)?.Value;
            nextTracked[normRel] = new CloudTrackingInfo(
                sourceUrl: sourceUrl,
                remoteId: manifest.RemoteId ?? existingEntry?.RemoteId,
                cloudKind: cloudKind,
                cookie: existingEntry?.Cookie,
                proxyUrl: existingEntry?.ProxyUrl,
                lastSynced: manifest.LastSyncedUtc,
                providerId: resolvedProviderId ?? existingEntry?.ProviderId,
                streamQualityId: manifest.StreamQualityId ?? existingEntry?.StreamQualityId,
                downloadQualityId: manifest.DownloadQualityId ?? existingEntry?.DownloadQualityId);
        }

        var normalizedNext = nextTracked.Count > 0 ? nextTracked : null;
        if (AreTrackedSubfoldersEqual(root.TrackedSubfolders, normalizedNext))
        {
            return false;
        }

        var updated = new List<LibraryRoot>(_roots);
        updated[idx] = root with { TrackedSubfolders = normalizedNext };
        _roots = updated;
        return true;
    }

    private static bool AreTrackedSubfoldersEqual(
        Dictionary<string, CloudTrackingInfo>? a,
        Dictionary<string, CloudTrackingInfo>? b)
    {
        int countA = a?.Count ?? 0;
        int countB = b?.Count ?? 0;
        if (countA != countB) return false;
        if (countA == 0) return true;

        foreach (var kvp in a!)
        {
            if (!b!.TryGetValue(kvp.Key, out var other)) return false;
            if (!string.Equals(kvp.Value.SourceUrl, other.SourceUrl, StringComparison.Ordinal) ||
                !string.Equals(kvp.Value.RemoteId, other.RemoteId, StringComparison.Ordinal) ||
                kvp.Value.CloudKind != other.CloudKind ||
                !string.Equals(kvp.Value.ResolvedProviderId, other.ResolvedProviderId, StringComparison.OrdinalIgnoreCase) ||
                kvp.Value.LastSynced != other.LastSynced ||
                !string.Equals(kvp.Value.Cookie, other.Cookie, StringComparison.Ordinal) ||
                !string.Equals(kvp.Value.ProxyUrl, other.ProxyUrl, StringComparison.Ordinal) ||
                !string.Equals(kvp.Value.StreamQualityId, other.StreamQualityId, StringComparison.OrdinalIgnoreCase) ||
                !string.Equals(kvp.Value.DownloadQualityId, other.DownloadQualityId, StringComparison.OrdinalIgnoreCase))
            {
                return false;
            }
        }
        return true;
    }

    private void MigrateTrackReferences(IReadOnlyDictionary<string, string> idMigrations)
    {
        if (idMigrations.Count == 0) return;

        bool playlistsChanged = false;
        var updatedPlaylists = new List<Playlist>(_playlists.Count);
        foreach (var pl in _playlists)
        {
            bool changed = false;
            var nextIds = new List<string>(pl.TrackIds.Count);
            foreach (string id in pl.TrackIds)
            {
                if (idMigrations.TryGetValue(id, out string? replacement))
                {
                    nextIds.Add(replacement);
                    changed = true;
                }
                else
                {
                    nextIds.Add(id);
                }
            }
            if (changed) playlistsChanged = true;
            updatedPlaylists.Add(changed ? pl with { TrackIds = nextIds } : pl);
        }
        if (playlistsChanged)
        {
            _playlists = updatedPlaylists;
            OnPropertyChanged(nameof(Playlists));
        }

        bool favsChanged = false;
        foreach (var kvp in idMigrations)
        {
            if (_favourites.Remove(kvp.Key))
            {
                _favourites.Add(kvp.Value);
                favsChanged = true;
            }
        }
        for (int i = 0; i < _favouritesOrder.Count; i++)
        {
            if (idMigrations.TryGetValue(_favouritesOrder[i], out string? replacement))
            {
                _favouritesOrder[i] = replacement;
                favsChanged = true;
            }
        }
        if (favsChanged)
        {
            OnPropertyChanged(nameof(Favourites));
        }

        if (playlistsChanged || favsChanged)
        {
            SavePlaylists();
        }

        foreach (var kvp in idMigrations)
        {
            if (_metadataOverrides.Remove(kvp.Key, out var ov))
            {
                _metadataOverrides[kvp.Value] = ov;
            }
        }

        foreach (string key in _folderPrefs.Keys.ToList())
        {
            var prefs = _folderPrefs[key];
            if (prefs.CustomTrackOrder is not { Count: > 0 } order) continue;
            bool orderChanged = false;
            var nextOrder = new List<string>(order.Count);
            foreach (string id in order)
            {
                if (idMigrations.TryGetValue(id, out string? replacement))
                {
                    nextOrder.Add(replacement);
                    orderChanged = true;
                }
                else
                {
                    nextOrder.Add(id);
                }
            }
            if (orderChanged)
            {
                _folderPrefs[key] = prefs with { CustomTrackOrder = nextOrder };
            }
        }

        OnTracksMigrated?.Invoke(idMigrations);
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
        CancellationToken ct,
        IDictionary<string, string>? idMigrations = null,
        ICloudSourceProvider? provider = null)
    {
        provider ??= _providerRegistry.GetProvider(root);
        var existingByRemoteId = new Dictionary<string, Track>(StringComparer.Ordinal);
        foreach (var t in existingTracks)
        {
            if (!string.IsNullOrWhiteSpace(t.RemoteTrackId))
            {
                existingByRemoteId.TryAdd(t.RemoteTrackId, t);
            }
        }

        var urlArtCache = new Dictionary<string, string?>(StringComparer.Ordinal);
        using var artHttp = CreateArtworkHttpClient(root, provider);
        var now = DateTimeOffset.UtcNow;
        var built = new List<Track>(result.Items.Count);
        var seenTrackIds = new HashSet<string>(StringComparer.Ordinal);
        string cloudSource = provider?.CloudSourceKey ?? root.Kind switch
        {
            RootKind.CloudBilibili => CloudSources.Bilibili,
            RootKind.CloudGoogleDrive => CloudSources.GoogleDrive,
            RootKind.CloudExtension => root.EffectiveProviderId ?? "extension",
            _ => CloudSources.YouTube
        };
        string albumArtist = provider?.DisplayName ?? CloudProviderRegistry.ResolveDisplayLabel(
            _providerRegistry,
            root.EffectiveProviderId,
            root.Kind);

        foreach (var item in result.Items)
        {
            string relPath = MakeCloudRelativePath(item);
            if (root.IsExcluded(relPath)) continue;
            string trackId = Track.MakeId(root.Id, relPath);
            if (!seenTrackIds.Add(trackId)) continue;

            existingByRemoteId.TryGetValue(item.RemoteTrackId, out var old);
            if (old is not null && !string.Equals(old.Id, trackId, StringComparison.Ordinal))
            {
                if (idMigrations is not null)
                {
                    idMigrations[old.Id] = trackId;
                }
            }

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
                    artKey = await DownloadAndStoreArtworkAsync(artHttp, provider, item.ArtworkUrl, ct).ConfigureAwait(false);
                    urlArtCache[item.ArtworkUrl] = artKey;
                }
            }

            built.Add(MakeCloudTrack(
                root: root,
                trackId: trackId,
                relPath: relPath,
                item: item,
                fallbackAlbum: result.Title,
                albumArtist: albumArtist,
                cloudSource: cloudSource,
                artworkKey: artKey,
                dateAddedUtc: old?.DateAddedUtc ?? now,
                now: now));
        }

        return built;
    }

    private Track MakeCloudTrack(
        LibraryRoot root,
        string trackId,
        string relPath,
        CloudPlaylistItem item,
        string fallbackAlbum,
        string albumArtist,
        string cloudSource,
        string? artworkKey,
        DateTimeOffset dateAddedUtc,
        DateTimeOffset now) =>
        ApplyMetadataOverride(new Track(
            Id: trackId,
            RootId: root.Id,
            RelativePath: relPath,
            Title: item.Title,
            Artist: item.Artist,
            Album: string.IsNullOrWhiteSpace(item.Album) ? fallbackAlbum : item.Album,
            AlbumArtist: albumArtist,
            TrackNumber: item.TrackNumber,
            DiscNumber: 1,
            DurationSeconds: item.DurationSeconds,
            FileSize: 0,
            ModifiedUtc: now,
            HasEmbeddedLyrics: false,
            DateAddedUtc: dateAddedUtc,
            ArtworkKey: artworkKey,
            CloudSource: cloudSource,
            RemoteTrackId: item.RemoteTrackId,
            RemoteArtworkUrl: item.ArtworkUrl));

    private HttpClient CreateArtworkHttpClient(LibraryRoot root, ICloudSourceProvider? provider)
    {
        string? proxy = provider is { SupportsProxy: true } ? root.ProxyUrl : null;
        return CloudHttpHelper.CreateClient(proxy, _artworkHandler, TimeSpan.FromSeconds(15));
    }

    private async Task<string?> DownloadAndStoreArtworkAsync(
        HttpClient http,
        ICloudSourceProvider? provider,
        string url,
        CancellationToken ct)
    {
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            provider?.ConfigureArtworkRequest(req);
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

    private static string MakeCloudRelativePath(CloudPlaylistItem item)
    {
        string ext = string.IsNullOrWhiteSpace(item.FileExtension)
            ? ".m4a"
            : item.FileExtension.StartsWith('.') ? item.FileExtension : "." + item.FileExtension;

        if (!string.IsNullOrWhiteSpace(item.RelativePath))
        {
            string[] rawSegments = item.RelativePath.Replace('\\', '/')
                .Split('/', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            var cleanSegments = new List<string>(rawSegments.Length);
            foreach (string seg in rawSegments)
            {
                string sanitized = GoogleDriveClient.SanitizePathSegment(seg, "Item");
                if (sanitized.Length > 0)
                {
                    cleanSegments.Add(sanitized);
                }
            }

            if (cleanSegments.Count > 0)
            {
                string last = cleanSegments[^1];
                if (string.IsNullOrEmpty(Path.GetExtension(last)))
                {
                    cleanSegments[^1] = $"{last}{ext}";
                }
                return string.Join('/', cleanSegments);
            }
        }

        string safe = item.RemoteTrackId
            .Replace('/', '_')
            .Replace('\\', '_')
            .Replace(':', '_')
            .Replace('?', '_')
            .Replace('&', '_')
            .Replace('=', '_')
            .Replace('|', '_');
        return $"{safe}{ext}";
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
        if (track is null || track.IsEphemeral || Track.HasEphemeralIdPrefix(track.Id)) return null;

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

    public Track? GetTrack(string id)
    {
        if (string.IsNullOrEmpty(id)) return null;
        if (_byId.TryGetValue(id, out var track)) return track;
        lock (_ephemeralLock)
        {
            return _ephemeralTracks.TryGetValue(id, out var eph) ? eph : null;
        }
    }

    public IReadOnlyList<Track> GetTracks(IEnumerable<string> ids)
    {
        var list = new List<Track>();
        foreach (string id in ids)
        {
            if (GetTrack(id) is { } track) list.Add(track);
        }
        return list;
    }

    public bool IsEphemeralTrack(string id)
    {
        if (string.IsNullOrEmpty(id)) return false;
        lock (_ephemeralLock)
        {
            return _ephemeralTracks.ContainsKey(id);
        }
    }

    public int EphemeralTrackCount
    {
        get
        {
            lock (_ephemeralLock)
            {
                return _ephemeralTracks.Count;
            }
        }
    }

    public void RegisterEphemeralTrack(Track track)
    {
        ArgumentNullException.ThrowIfNull(track);
        RegisterEphemeralTracks([track]);
    }

    public void RegisterEphemeralTracks(IEnumerable<Track> tracks)
    {
        ArgumentNullException.ThrowIfNull(tracks);
        bool changed = false;
        lock (_ephemeralLock)
        {
            foreach (var track in tracks)
            {
                if (track is null || string.IsNullOrWhiteSpace(track.Id)) continue;
                var normalized = track.IsEphemeral ? track : track with { IsEphemeral = true };
                if (!_ephemeralTracks.TryGetValue(normalized.Id, out var existing) || !Equals(existing, normalized))
                {
                    _ephemeralTracks[normalized.Id] = normalized;
                    changed = true;
                }
            }
        }
        if (changed)
        {
            SaveLibrary();
        }
    }

    public int PruneEphemeralTracks(ISet<string> referencedIds)
    {
        ArgumentNullException.ThrowIfNull(referencedIds);
        int removed = 0;
        lock (_ephemeralLock)
        {
            if (_ephemeralTracks.Count == 0) return 0;
            var toRemove = new List<string>();
            foreach (string id in _ephemeralTracks.Keys)
            {
                if (!referencedIds.Contains(id))
                {
                    toRemove.Add(id);
                }
            }
            foreach (string id in toRemove)
            {
                if (_ephemeralTracks.Remove(id))
                {
                    removed++;
                }
            }
        }
        if (removed > 0)
        {
            SaveLibrary();
        }
        return removed;
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

        List<string>? knownSnapshot = null;
        lock (_cloudSyncLock)
        {
            if (_knownCloudSubfolders.TryGetValue(rootId, out var known) && known.Count > 0)
            {
                knownSnapshot = known.ToList();
            }
        }

        if (knownSnapshot is not null)
        {
            var root = GetRoot(rootId);
            foreach (string subRel in knownSnapshot)
            {
                if (root is not null && root.IsExcluded(subRel)) continue;
                if (!subRel.StartsWith(prefix, StringComparison.Ordinal)) continue;
                string rest = subRel[prefix.Length..];
                if (rest.Length == 0) continue;
                int slash = rest.IndexOf('/');
                string directFolder = slash >= 0 ? rest[..slash] : rest;
                if (directFolder.Length > 0)
                {
                    folders.Add(directFolder);
                }
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
