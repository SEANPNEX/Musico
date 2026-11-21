using System.Globalization;
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
    }

    private sealed class PlaylistFile
    {
        public List<Playlist> Playlists { get; set; } = new();
        public HashSet<string> Favourites { get; set; } = new();
    }

    private const int CurrentReaderVersion = 2;
    public const string AppFolderName = "Music Folder";

    private readonly string _stateDirectory;
    private readonly string _musicDirectory;
    private readonly IMetadataReader _reader;
    private readonly int _scanBatchSize;
    private readonly bool _appFolderRemovable;

    private readonly Dictionary<Guid, string> _rootPaths = new();
    private Dictionary<string, Track> _byId = new();

    private List<LibraryRoot> _roots = new();
    private List<Track> _tracks = new();
    private List<AlbumInfo> _albums = new();
    private List<string> _artists = new();
    private List<Playlist> _playlists = new();
    private HashSet<string> _favourites = new();
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
        bool appFolderRemovable = true)
    {
        _stateDirectory = stateDirectory;
        _musicDirectory = musicDirectory;
        _reader = reader;
        Artwork = artwork;
        _scanBatchSize = Math.Max(scanBatchSize, 1);
        _appFolderRemovable = appFolderRemovable;

        var file = Persistence.Load<LibraryFile>(LibraryPath);
        _rereadAll = file is not null && file.ReaderVersion != CurrentReaderVersion;

        var loadedRoots = file?.Roots.Where(r => r.Kind == RootKind.Picked).ToList() ?? new List<LibraryRoot>();
        _appFolderRemoved = _appFolderRemovable && file?.AppFolderRemoved == true;

        if (!_appFolderRemoved)
        {
            loadedRoots.Insert(0, CreateAppFolderRoot());
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
        RebuildIndex();

        var lists = Persistence.Load<PlaylistFile>(PlaylistsPath);
        _playlists = lists?.Playlists ?? new List<Playlist>();
        _favourites = lists?.Favourites ?? new HashSet<string>();
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
                AppFolderRemoved = _appFolderRemoved ? true : null
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
                Favourites = _favourites
            }, PlaylistsPath);
        }
        catch
        {
            // Best-effort persistence.
        }
    }

    // MARK: Roots

    public bool IsAvailable(LibraryRoot root) => _rootPaths.ContainsKey(root.Id);

    public bool IsAvailable(Track track) => _rootPaths.ContainsKey(track.RootId);

    public string? GetRootPath(Guid rootId) => _rootPaths.TryGetValue(rootId, out string? p) ? p : null;

    public LibraryRoot? GetRoot(Guid rootId) => _roots.FirstOrDefault(r => r.Id == rootId);

    private static string NormalizeDirPath(string path)
    {
        string full = Path.GetFullPath(path);
        return full.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
    }

    public async Task AddRootAsync(string path)
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
        var root = new LibraryRoot(Guid.NewGuid(), name, RootKind.Picked, normalized);
        _roots = [.. _roots, root];
        _rootPaths[root.Id] = normalized;
        OnPropertyChanged(nameof(Roots));
        SaveLibrary();
        await RescanAsync();
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
        if (removedIds.Count > 0)
        {
            _tracks = _tracks.Where(t => t.RootId != id).ToList();
            RebuildIndex();
            OnPropertyChanged(nameof(Tracks));
            Forget(removedIds);
        }

        SaveLibrary();
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
        var kept = snapshotTracks.Where(t => !_rootPaths.ContainsKey(t.RootId)).ToList();
        var pending = new List<(LibraryRoot Root, string RootPath, ScannedFile File, Track? Old)>();
        var removed = new HashSet<string>();
        bool reread = _rereadAll;

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
                if (!seen.Add(file.RelativePath)) continue;

                if (!reread
                    && existingByRel.TryGetValue(file.RelativePath, out var old)
                    && old.FileSize == file.FileSize
                    && Math.Abs((old.ModifiedUtc - file.ModifiedUtc).TotalSeconds) < 0.001)
                {
                    kept.Add(old);
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

        // A root removed while scanning must not leave orphan tracks behind.
        var activeRootIds = new HashSet<Guid>(_roots.Select(r => r.Id));
        var finalTracks = kept.Where(t => activeRootIds.Contains(t.RootId)).ToList();
        var finalTrackIds = new HashSet<string>(finalTracks.Select(t => t.Id));
        removed.RemoveWhere(id => finalTrackIds.Contains(id));

        if (reread) _rereadAll = false;

        if (pending.Count == 0 && removed.Count == 0 && finalTracks.Count == _tracks.Count)
        {
            if (reread) SaveLibrary();
            return;
        }

        Publish(finalTracks);
        Forget(removed);

        var keepKeys = new HashSet<string>(_tracks.Select(t => t.ArtworkKey).Where(k => k is not null)!);
        await Task.Run(() => Artwork.Prune(keepKeys));
    }

    private void Publish(IEnumerable<Track> list)
    {
        var activeRootIds = new HashSet<Guid>(_roots.Select(r => r.Id));
        _tracks = list
            .Where(t => activeRootIds.Contains(t.RootId))
            .OrderBy(t => t.Title, StringComparer.CurrentCultureIgnoreCase)
            .ToList();
        RebuildIndex();
        OnPropertyChanged(nameof(Tracks));
        SaveLibrary();
    }

    private static Track MakeTrack(LibraryRoot root, ScannedFile file, TrackMetadata metadata, string? artworkKey, DateTimeOffset dateAdded)
    {
        string fileName = Path.GetFileName(file.RelativePath);
        string titleFallback = Path.GetFileNameWithoutExtension(fileName);
        int lastSlash = file.RelativePath.LastIndexOf('/');
        string folderName = lastSlash > 0
            ? Path.GetFileName(file.RelativePath[..lastSlash])
            : string.Empty;

        string artist = metadata.Artist ?? "Unknown Artist";
        return new Track(
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
            ArtworkKey: artworkKey);
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
        OnPropertyChanged(nameof(Playlists));
        OnPropertyChanged(nameof(Favourites));
        SavePlaylists();
        OnTracksRemoved?.Invoke(ids);
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

    /// <summary>Direct children of a folder: sorted subfolder names, then direct tracks sorted by file name.</summary>
    public (IReadOnlyList<string> Folders, IReadOnlyList<Track> Tracks) GetFolderContents(Guid rootId, string path)
    {
        string prefix = string.IsNullOrEmpty(path) ? string.Empty : path + "/";
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

        return (
            folders.OrderBy(f => f, StringComparer.CurrentCultureIgnoreCase).ToList(),
            direct.OrderBy(t => t.FileName, StringComparer.CurrentCultureIgnoreCase).ToList()
        );
    }

    /// <summary>Every song in a folder and its subfolders, in path order.</summary>
    public IReadOnlyList<Track> GetTracksUnder(Guid rootId, string path)
    {
        string prefix = string.IsNullOrEmpty(path) ? string.Empty : path + "/";
        return _tracks
            .Where(t => t.RootId == rootId && t.RelativePath.StartsWith(prefix, StringComparison.Ordinal))
            .OrderBy(t => t.RelativePath, StringComparer.CurrentCultureIgnoreCase)
            .ToList();
    }

    public IReadOnlyList<Track> Search(string query)
    {
        string needle = query.Trim();
        if (needle.Length == 0) return Array.Empty<Track>();

        var compare = CultureInfo.CurrentCulture.CompareInfo;
        const CompareOptions options = CompareOptions.IgnoreCase | CompareOptions.IgnoreNonSpace;

        return _tracks.Where(t =>
            compare.IndexOf(t.Title, needle, options) >= 0 ||
            compare.IndexOf(t.Artist, needle, options) >= 0 ||
            compare.IndexOf(t.Album, needle, options) >= 0).ToList();
    }

    // MARK: Favourites and Playlists

    public bool IsFavourite(string id) => _favourites.Contains(id);

    public void ToggleFavourite(string id)
    {
        if (!_favourites.Remove(id))
        {
            _favourites.Add(id);
        }
        OnPropertyChanged(nameof(Favourites));
        SavePlaylists();
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

    public void RemoveFromPlaylist(Guid playlistId, int index)
    {
        UpdatePlaylist(playlistId, pl =>
        {
            if (index < 0 || index >= pl.TrackIds.Count) return pl;
            var next = new List<string>(pl.TrackIds);
            next.RemoveAt(index);
            return pl with { TrackIds = next };
        });
    }

    public void MovePlaylistTrack(Guid playlistId, int from, int to)
    {
        UpdatePlaylist(playlistId, pl =>
        {
            if (from < 0 || from >= pl.TrackIds.Count || to < 0 || to >= pl.TrackIds.Count || from == to)
                return pl;
            var next = new List<string>(pl.TrackIds);
            string item = next[from];
            next.RemoveAt(from);
            next.Insert(to, item);
            return pl with { TrackIds = next };
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
