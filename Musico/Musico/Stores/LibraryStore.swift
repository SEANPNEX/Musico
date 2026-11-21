import Foundation
import Observation

enum LibraryError: LocalizedError {
    case duplicateRoot
    case notAFolder
    var errorDescription: String? {
        switch self {
        case .duplicateRoot: "That folder is already in your library."
        case .notAFolder: "That location could not be opened as a folder."
        }
    }
}

/// The music library: folder roots, the track index, playlists and favourites.
@MainActor @Observable
final class LibraryStore {
    struct Album: Identifiable, Hashable {
        var id: String
        var title: String
        var artist: String
        var trackIDs: [String]
        var artworkKey: String?
    }

    private struct LibraryFile: Codable { var roots: [LibraryRoot]; var tracks: [Track]; var readerVersion: Int?; var appFolderRemoved: Bool? }
    /// Raised when tag reading improves, so songs indexed by an older build are read again.
    private static let readerVersion = 2
    @ObservationIgnored private var rereadAll = false
    private struct PlaylistFile: Codable { var playlists: [Playlist]; var favourites: Set<String> }

    private(set) var roots: [LibraryRoot] = []
    private(set) var tracks: [Track] = [] { didSet { rebuildIndex() } }
    private(set) var albums: [Album] = []
    private(set) var artists: [String] = []
    private(set) var playlists: [Playlist] = []
    private(set) var favourites: Set<String> = []
    private(set) var isScanning = false
    private(set) var scanProgress: (done: Int, total: Int) = (0, 0)
    private var rootURLs: [UUID: URL] = [:]
    /// True once the user has removed the built-in root (only possible where it is removable).
    private(set) var appFolderRemoved = false
    /// On the Mac the built-in root is the user's Music folder, which they may not want indexed.
    @ObservationIgnored let appFolderRemovable: Bool

    @ObservationIgnored var onTracksRemoved: ((Set<String>) -> Void)?
    @ObservationIgnored private var byID: [String: Track] = [:]
    @ObservationIgnored private let directory: URL
    @ObservationIgnored private let documentsURL: URL
    @ObservationIgnored private let reader: MetadataReading
    @ObservationIgnored let artwork: ArtworkCache
    /// How many newly read songs are published (and saved) at a time during a scan.
    @ObservationIgnored private let scanBatchSize: Int
    @ObservationIgnored private var rescanRequested = false

    /// The built-in root: the app's own folder in Files, or the user's Music folder on the Mac.
    static var appFolderName: String {
        #if targetEnvironment(macCatalyst)
        "Music Folder"
        #else
        "Musico Folder"
        #endif
    }

    /// Files hides an app's folder while it is empty, so a first launch leaves a note in it.
    private static var appFolderRoot: LibraryRoot {
        LibraryRoot(id: LibraryRoot.appFolderID, name: appFolderName, kind: .appFolder, bookmark: nil)
    }

    private static func makeAppFolderVisible(_ folder: URL) {
        let manager = FileManager.default
        try? manager.createDirectory(at: folder, withIntermediateDirectories: true)
        guard let names = try? manager.contentsOfDirectory(atPath: folder.path), names.isEmpty else { return }
        let note = "Copy music files or folders here, then open Musico and tap Rescan on the Folders tab.\n"
        try? Data(note.utf8).write(to: folder.appendingPathComponent("Put your music in this folder.txt"))
    }

    init(directory: URL, documentsURL: URL, reader: MetadataReading, artwork: ArtworkCache, scanBatchSize: Int = 100,
         appFolderRemovable: Bool = false) {
        self.appFolderRemovable = appFolderRemovable
        self.scanBatchSize = max(scanBatchSize, 1)
        self.directory = directory
        self.documentsURL = documentsURL
        self.reader = reader
        self.artwork = artwork

        let file = Persistence.load(LibraryFile.self, from: libraryURL)
        rereadAll = file != nil && file?.readerVersion != Self.readerVersion
        var loadedRoots = file?.roots.filter { $0.kind == .picked } ?? []
        appFolderRemoved = appFolderRemovable && file?.appFolderRemoved == true
        if !appFolderRemoved {
            loadedRoots.insert(Self.appFolderRoot, at: 0)
            rootURLs[LibraryRoot.appFolderID] = documentsURL
        }
        roots = loadedRoots
        #if !targetEnvironment(macCatalyst)
        Self.makeAppFolderVisible(documentsURL)
        #endif
        for index in roots.indices where roots[index].kind == .picked { resolve(rootAt: index) }
        tracks = file?.tracks ?? []
        rebuildIndex()

        let lists = Persistence.load(PlaylistFile.self, from: playlistsURL)
        playlists = lists?.playlists ?? []
        favourites = lists?.favourites ?? []
    }

    private var libraryURL: URL { directory.appendingPathComponent("library.json") }
    private var playlistsURL: URL { directory.appendingPathComponent("playlists.json") }

    private func saveLibrary() { try? Persistence.save(LibraryFile(roots: roots, tracks: tracks, readerVersion: rereadAll ? nil : Self.readerVersion,
                                                                       appFolderRemoved: appFolderRemoved ? true : nil), to: libraryURL) }
    private func savePlaylists() { try? Persistence.save(PlaylistFile(playlists: playlists, favourites: favourites), to: playlistsURL) }

    // MARK: Roots

    private static func isDirectory(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) && isDir.boolValue
    }

    /// On the Mac a bookmark only keeps its access across launches when it is made, and resolved,
    /// as a security-scoped one; iOS bookmarks of picked folders carry that implicitly.
    private static func makeBookmark(_ url: URL) -> Data? {
        #if targetEnvironment(macCatalyst)
        try? url.bookmarkData(options: [.withSecurityScope, .securityScopeAllowOnlyReadAccess])
        #else
        try? url.bookmarkData()
        #endif
    }

    private static func resolveBookmark(_ data: Data, isStale: inout Bool) -> URL? {
        #if targetEnvironment(macCatalyst)
        try? URL(resolvingBookmarkData: data, options: .withSecurityScope, bookmarkDataIsStale: &isStale)
        #else
        try? URL(resolvingBookmarkData: data, bookmarkDataIsStale: &isStale)
        #endif
    }

    /// Resolves a picked root's bookmark and starts security-scoped access, held for the life of the process.
    private func resolve(rootAt index: Int) {
        guard let bookmark = roots[index].bookmark else { return }
        var stale = false
        guard let url = Self.resolveBookmark(bookmark, isStale: &stale) else { return }
        _ = url.startAccessingSecurityScopedResource()
        guard Self.isDirectory(url) else {
            url.stopAccessingSecurityScopedResource()
            return
        }
        if stale, let fresh = Self.makeBookmark(url) { roots[index].bookmark = fresh }
        rootURLs[roots[index].id] = url
    }

    func isAvailable(_ root: LibraryRoot) -> Bool { rootURLs[root.id] != nil }
    func rootURL(_ id: UUID) -> URL? { rootURLs[id] }
    func root(_ id: UUID) -> LibraryRoot? { roots.first { $0.id == id } }

    private func sameLocation(_ a: URL, _ b: URL) -> Bool {
        a.resolvingSymlinksInPath().standardizedFileURL.path == b.resolvingSymlinksInPath().standardizedFileURL.path
    }

    func addRoot(url: URL) async throws {
        let scoped = url.startAccessingSecurityScopedResource()
        func fail(_ error: LibraryError) -> LibraryError {
            if scoped { url.stopAccessingSecurityScopedResource() }
            return error
        }
        guard Self.isDirectory(url) else { throw fail(.notAFolder) }
        guard !rootURLs.values.contains(where: { sameLocation($0, url) }) else { throw fail(.duplicateRoot) }
        guard let bookmark = Self.makeBookmark(url) else { throw fail(.notAFolder) }
        let root = LibraryRoot(id: UUID(), name: url.lastPathComponent, kind: .picked, bookmark: bookmark)
        roots.append(root)
        rootURLs[root.id] = url
        saveLibrary()
        await rescan()
    }

    /// Points an existing (usually unavailable) root at a newly picked folder, keeping its tracks' identities.
    func relocateRoot(_ id: UUID, to url: URL) async throws {
        guard let index = roots.firstIndex(where: { $0.id == id && $0.kind == .picked }) else { return }
        let scoped = url.startAccessingSecurityScopedResource()
        guard Self.isDirectory(url), let bookmark = Self.makeBookmark(url) else {
            if scoped { url.stopAccessingSecurityScopedResource() }
            throw LibraryError.notAFolder
        }
        rootURLs[id]?.stopAccessingSecurityScopedResource()
        roots[index].bookmark = bookmark
        roots[index].name = url.lastPathComponent
        rootURLs[id] = url
        saveLibrary()
        await rescan()
    }

    func removeRoot(_ id: UUID) {
        guard let index = roots.firstIndex(where: { $0.id == id }) else { return }
        if roots[index].kind == .appFolder {
            guard appFolderRemovable else { return }
            appFolderRemoved = true
        } else {
            rootURLs[id]?.stopAccessingSecurityScopedResource()
        }
        rootURLs[id] = nil
        roots.remove(at: index)
        let removed = Set(tracks.filter { $0.rootID == id }.map(\.id))
        tracks.removeAll { $0.rootID == id }
        forget(removed)
        saveLibrary()
    }

    /// Puts the built-in root back after the user removed it.
    func restoreAppFolder() async {
        guard appFolderRemoved else { return }
        appFolderRemoved = false
        roots.insert(Self.appFolderRoot, at: 0)
        rootURLs[LibraryRoot.appFolderID] = documentsURL
        saveLibrary()
        await rescan()
    }

    // MARK: Scanning

    /// Brings the index up to date with the folders. If a scan is already running, this asks it
    /// to run once more (so a folder added meanwhile is not missed) and waits for it.
    func rescan() async {
        if isScanning {
            rescanRequested = true
            while isScanning { try? await Task.sleep(nanoseconds: 50_000_000) }
            return
        }
        isScanning = true
        repeat {
            rescanRequested = false
            await scanOnce()
        } while rescanRequested
        isScanning = false
    }

    private func sortedByTitle(_ list: [Track]) -> [Track] {
        list.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    private func scanOnce() async {
        scanProgress = (0, 0)

        // Roots that were unreachable (unplugged drive, moved folder) may be back.
        for index in roots.indices where roots[index].kind == .picked {
            if let url = rootURLs[roots[index].id] {
                if !Self.isDirectory(url) {
                    url.stopAccessingSecurityScopedResource()
                    rootURLs[roots[index].id] = nil
                }
            } else {
                resolve(rootAt: index)
            }
        }

        var kept = tracks.filter { rootURLs[$0.rootID] == nil }
        var pending: [(root: LibraryRoot, url: URL, file: ScannedFile, old: Track?)] = []
        var removed = Set<String>()
        let reread = rereadAll

        for root in roots {
            guard let rootURL = rootURLs[root.id] else { continue }
            let existingTracks = tracks.filter { $0.rootID == root.id }
            let scanned = await Task.detached(priority: .userInitiated) { FolderScanner.scan(root: rootURL) }.value
            // A folder that cannot be listed, or a cloud/external folder that suddenly lists nothing,
            // is treated as temporarily unreadable: keep what we know rather than purge playlists.
            guard let files = scanned, !(files.isEmpty && !existingTracks.isEmpty && root.kind == .picked) else {
                kept.append(contentsOf: existingTracks)
                continue
            }
            let existing = Dictionary(existingTracks.map { ($0.relativePath, $0) }, uniquingKeysWith: { first, _ in first })
            var seen = Set<String>()
            for file in files {
                if file.isPlaceholder {
                    // Not downloaded from iCloud: keep a song we already know, ignore one we cannot read yet.
                    if let old = existing[file.relativePath], seen.insert(file.relativePath).inserted { kept.append(old) }
                    continue
                }
                guard seen.insert(file.relativePath).inserted else { continue }
                if !reread, let old = existing[file.relativePath], old.fileSize == file.fileSize,
                   abs(old.modified.timeIntervalSince(file.modified)) < 0.001 {
                    kept.append(old)
                } else {
                    pending.append((root, rootURL, file, existing[file.relativePath]))
                }
            }
            removed.formUnion(existing.filter { !seen.contains($0.key) }.map(\.value.id))
        }

        scanProgress = (0, pending.count)
        let now = Date()
        let reader = self.reader
        let artwork = self.artwork
        for (index, item) in pending.enumerated() {
            let url = FolderScanner.url(root: item.url, relativePath: item.file.relativePath)
            // Tag reading and cover resizing both happen off the main actor.
            let (metadata, artworkKey) = await Task.detached(priority: .userInitiated) { () -> (TrackMetadata, String?) in
                let metadata = await reader.read(url)
                return (metadata, metadata.artwork.flatMap { artwork.store($0) })
            }.value
            kept.append(makeTrack(root: item.root, file: item.file, metadata: metadata, artworkKey: artworkKey,
                                  dateAdded: item.old?.dateAdded ?? now))
            scanProgress = (index + 1, pending.count)
            if (index + 1) % scanBatchSize == 0, index + 1 < pending.count {
                // Publish progress: songs read so far plus the old records of changed files not reached yet.
                let notReached = pending[(index + 1)...].compactMap(\.old)
                publish(kept + notReached)
            }
        }

        // A root removed while we were reading must not come back as orphans.
        let rootIDs = Set(roots.map(\.id))
        let final = kept.filter { rootIDs.contains($0.rootID) }
        removed = removed.filter { id in !final.contains { $0.id == id } }
        if reread { rereadAll = false }
        guard !pending.isEmpty || !removed.isEmpty || final.count != tracks.count else {
            if reread { saveLibrary() }
            return
        }

        publish(final)
        forget(removed)
        let keys = Set(tracks.compactMap(\.artworkKey))
        await Task.detached(priority: .utility) { artwork.prune(keeping: keys) }.value
    }

    private func publish(_ list: [Track]) {
        let rootIDs = Set(roots.map(\.id))
        tracks = sortedByTitle(list.filter { rootIDs.contains($0.rootID) })
        saveLibrary()
    }

    private func makeTrack(root: LibraryRoot, file: ScannedFile, metadata: TrackMetadata, artworkKey: String?, dateAdded: Date) -> Track {
        let name = (file.relativePath as NSString).lastPathComponent
        let folder = ((file.relativePath as NSString).deletingLastPathComponent as NSString).lastPathComponent
        let artist = metadata.artist ?? "Unknown Artist"
        return Track(
            id: Track.makeID(rootID: root.id, relativePath: file.relativePath),
            rootID: root.id,
            relativePath: file.relativePath,
            title: metadata.title ?? (name as NSString).deletingPathExtension,
            artist: artist,
            album: metadata.album ?? (folder.isEmpty ? "Unknown Album" : folder),
            albumArtist: metadata.albumArtist ?? artist,
            trackNumber: metadata.trackNumber,
            discNumber: metadata.discNumber,
            duration: metadata.duration,
            fileSize: file.fileSize,
            modified: file.modified,
            hasEmbeddedLyrics: metadata.lyrics != nil,
            dateAdded: dateAdded,
            artworkKey: artworkKey)
    }

    /// Removes vanished tracks from playlists and favourites and tells the player.
    private func forget(_ ids: Set<String>) {
        guard !ids.isEmpty else { return }
        for index in playlists.indices { playlists[index].trackIDs.removeAll { ids.contains($0) } }
        favourites.subtract(ids)
        savePlaylists()
        onTracksRemoved?(ids)
    }

    // MARK: Lookup

    private func rebuildIndex() {
        byID = Dictionary(tracks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var groups: [String: [Track]] = [:]
        for track in tracks { groups["\(track.albumArtist)\u{1F}\(track.album)", default: []].append(track) }
        albums = groups.map { key, members in
            let sorted = members.sorted {
                ($0.discNumber ?? 1, $0.trackNumber ?? Int.max, $0.title) < ($1.discNumber ?? 1, $1.trackNumber ?? Int.max, $1.title)
            }
            return Album(id: key, title: sorted[0].album, artist: sorted[0].albumArtist, trackIDs: sorted.map(\.id),
                         artworkKey: sorted.lazy.compactMap(\.artworkKey).first)
        }.sorted {
            let order = $0.title.localizedStandardCompare($1.title)
            return order == .orderedSame ? $0.artist < $1.artist : order == .orderedAscending
        }
        artists = Set(tracks.map(\.artist)).sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    }

    func track(_ id: String) -> Track? { byID[id] }
    func tracks(_ ids: [String]) -> [Track] { ids.compactMap { byID[$0] } }

    /// The file's current location, or nil while its folder is unavailable.
    func url(for track: Track) -> URL? {
        rootURLs[track.rootID].map { FolderScanner.url(root: $0, relativePath: track.relativePath) }
    }

    func isAvailable(_ track: Track) -> Bool { rootURLs[track.rootID] != nil }

    func embeddedLyrics(for track: Track) async -> String? {
        guard track.hasEmbeddedLyrics, let url = url(for: track) else { return nil }
        return await reader.read(url).lyrics
    }

    func albums(byArtist artist: String) -> [Album] {
        albums.filter { album in album.artist == artist || album.trackIDs.contains { byID[$0]?.artist == artist } }
    }

    func tracks(byArtist artist: String) -> [Track] { tracks.filter { $0.artist == artist } }

    /// Direct children of a folder: subfolder names, then the songs in it.
    func folderContents(root: UUID, path: String) -> (folders: [String], tracks: [Track]) {
        let prefix = path.isEmpty ? "" : path + "/"
        var folders = Set<String>()
        var direct: [Track] = []
        for track in tracks where track.rootID == root && track.relativePath.hasPrefix(prefix) {
            let rest = track.relativePath.dropFirst(prefix.count)
            if let slash = rest.firstIndex(of: "/") {
                folders.insert(String(rest[..<slash]))
            } else {
                direct.append(track)
            }
        }
        return (folders.sorted { $0.localizedStandardCompare($1) == .orderedAscending },
                direct.sorted { $0.fileName.localizedStandardCompare($1.fileName) == .orderedAscending })
    }

    /// Every song in a folder and its subfolders, in path order.
    func tracks(under root: UUID, path: String) -> [Track] {
        let prefix = path.isEmpty ? "" : path + "/"
        return tracks.filter { $0.rootID == root && $0.relativePath.hasPrefix(prefix) }
            .sorted { $0.relativePath.localizedStandardCompare($1.relativePath) == .orderedAscending }
    }

    func search(_ query: String) -> [Track] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return [] }
        return tracks.filter {
            $0.title.localizedStandardContains(needle) || $0.artist.localizedStandardContains(needle)
                || $0.album.localizedStandardContains(needle)
        }
    }

    // MARK: Favourites and playlists

    func isFavourite(_ id: String) -> Bool { favourites.contains(id) }

    func toggleFavourite(_ id: String) {
        if favourites.contains(id) { favourites.remove(id) } else { favourites.insert(id) }
        savePlaylists()
    }

    @discardableResult
    func createPlaylist(name: String) -> UUID {
        let playlist = Playlist(id: UUID(), name: name, trackIDs: [])
        playlists.append(playlist)
        savePlaylists()
        return playlist.id
    }

    private func updatePlaylist(_ id: UUID, _ body: (inout Playlist) -> Void) {
        guard let index = playlists.firstIndex(where: { $0.id == id }) else { return }
        body(&playlists[index])
        savePlaylists()
    }

    func renamePlaylist(_ id: UUID, to name: String) { updatePlaylist(id) { $0.name = name } }

    func deletePlaylist(_ id: UUID) {
        playlists.removeAll { $0.id == id }
        savePlaylists()
    }

    func add(_ ids: [String], toPlaylist id: UUID) { updatePlaylist(id) { $0.trackIDs.append(contentsOf: ids) } }

    func removeFromPlaylist(_ id: UUID, at offsets: IndexSet) {
        updatePlaylist(id) { playlist in
            for index in offsets.reversed() where playlist.trackIDs.indices.contains(index) { playlist.trackIDs.remove(at: index) }
        }
    }

    func movePlaylistTracks(_ id: UUID, from source: IndexSet, to destination: Int) {
        updatePlaylist(id) { playlist in
            let moved = source.filter { playlist.trackIDs.indices.contains($0) }.map { playlist.trackIDs[$0] }
            var remaining = playlist.trackIDs.enumerated().filter { !source.contains($0.offset) }.map(\.element)
            let insertAt = destination - source.filter { $0 < destination }.count
            remaining.insert(contentsOf: moved, at: min(max(insertAt, 0), remaining.count))
            playlist.trackIDs = remaining
        }
    }
}
