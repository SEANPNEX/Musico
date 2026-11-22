import Foundation
import Observation

enum LibraryError: LocalizedError {
    case duplicateRoot
    case notAFolder
    case invalidCloudURL
    var errorDescription: String? {
        switch self {
        case .duplicateRoot: "That location is already in your library."
        case .notAFolder: "That location could not be opened as a folder."
        case .invalidCloudURL: "Enter a valid YouTube or Bilibili playlist or video URL."
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

    private struct LibraryFile: Codable {
        var roots: [LibraryRoot]
        var tracks: [Track]
        var readerVersion: Int?
        var appFolderRemoved: Bool?
        var metadataOverrides: [String: TrackMetadataOverride]?
        var folderSortOrders: [String: FolderOrderState]?
        var knownAppFolders: [String]?
    }
    /// Raised when tag reading improves, so songs indexed by an older build are read again.
    private static let readerVersion = 3
    @ObservationIgnored private var rereadAll = false
    private struct PlaylistFile: Codable {
        var playlists: [Playlist]
        var favourites: Set<String>
        var favouritesSortOrder: TrackSortOrder?
        var favouritesOrder: [String]?
    }

    private(set) var roots: [LibraryRoot] = []
    private(set) var tracks: [Track] = [] { didSet { rebuildIndex() } }
    private(set) var albums: [Album] = []
    private(set) var artists: [String] = []
    private(set) var playlists: [Playlist] = []
    private(set) var favourites: Set<String> = []
    private(set) var favouritesSortOrder: TrackSortOrder = .custom
    private(set) var favouritesOrder: [String] = []
    private(set) var metadataOverrides: [String: TrackMetadataOverride] = [:]
    private(set) var folderOrders: [String: FolderOrderState] = [:]
    @ObservationIgnored private var knownAppFolders: Set<String> = []
    private(set) var isScanning = false
    private(set) var scanProgress: (done: Int, total: Int) = (0, 0)
    private(set) var cloudSyncErrors: [UUID: String] = [:]
    private var rootURLs: [UUID: URL] = [:]
    /// True once the user has removed the built-in root (only possible where it is removable).
    private(set) var appFolderRemoved = false
    /// On the Mac the built-in root is the user's Music folder, which they may not want indexed.
    @ObservationIgnored let appFolderRemovable: Bool

    @ObservationIgnored var onTracksRemoved: ((Set<String>) -> Void)?
    @ObservationIgnored var onTrackUpdated: ((Track) -> Void)?
    @ObservationIgnored var onFolderAdded: ((_ name: String, _ trackIDs: [String]) -> Void)?
    @ObservationIgnored private var byID: [String: Track] = [:]
    @ObservationIgnored private let directory: URL
    @ObservationIgnored private let documentsURL: URL
    @ObservationIgnored private let reader: MetadataReading
    @ObservationIgnored let artwork: ArtworkCache
    @ObservationIgnored private let cloudFetcher: any CloudPlaylistFetching
    @ObservationIgnored private let artworkDownloader: @Sendable (URL) async -> Data?
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
         appFolderRemovable: Bool = false,
         cloudFetcher: any CloudPlaylistFetching = LiveCloudService.shared,
         artworkDownloader: (@Sendable (URL) async -> Data?)? = nil) {
        self.appFolderRemovable = appFolderRemovable
        self.scanBatchSize = max(scanBatchSize, 1)
        self.directory = directory
        self.documentsURL = documentsURL
        self.reader = reader
        self.artwork = artwork
        self.cloudFetcher = cloudFetcher
        self.artworkDownloader = artworkDownloader ?? Self.defaultArtworkDownload

        let file = Persistence.load(LibraryFile.self, from: libraryURL)
        rereadAll = file != nil && file?.readerVersion != Self.readerVersion
        let savedAppRoot = file?.roots.first { $0.kind == .appFolder }
        var loadedRoots = file?.roots.filter { $0.kind != .appFolder } ?? []
        appFolderRemoved = appFolderRemovable && file?.appFolderRemoved == true
        if !appFolderRemoved {
            var appRoot = Self.appFolderRoot
            appRoot.excludedPaths = savedAppRoot?.excludedPaths
            loadedRoots.insert(appRoot, at: 0)
            rootURLs[LibraryRoot.appFolderID] = documentsURL
        }
        roots = loadedRoots
        #if !targetEnvironment(macCatalyst)
        Self.makeAppFolderVisible(documentsURL)
        #endif
        for index in roots.indices where roots[index].kind == .picked { resolve(rootAt: index) }
        metadataOverrides = file?.metadataOverrides ?? [:]
        folderOrders = file?.folderSortOrders ?? [:]
        let loadedTracks = file?.tracks ?? []
        if let savedKnown = file?.knownAppFolders {
            knownAppFolders = Set(savedKnown)
        } else if file != nil {
            knownAppFolders = Set(loadedTracks.filter { $0.rootID == LibraryRoot.appFolderID }.compactMap { Self.topFolderName($0.relativePath) })
        } else {
            knownAppFolders = Self.existingSubfolderNames(in: documentsURL)
        }
        tracks = loadedTracks.map { applyOverride(to: $0) }
        rebuildIndex()

        let lists = Persistence.load(PlaylistFile.self, from: playlistsURL)
        playlists = lists?.playlists ?? []
        favourites = lists?.favourites ?? []
        favouritesSortOrder = lists?.favouritesSortOrder ?? .custom
        favouritesOrder = lists?.favouritesOrder ?? []
    }

    private var libraryURL: URL { directory.appendingPathComponent("library.json") }
    private var playlistsURL: URL { directory.appendingPathComponent("playlists.json") }

    private func saveLibrary() {
        try? Persistence.save(
            LibraryFile(
                roots: roots,
                tracks: tracks,
                readerVersion: rereadAll ? nil : Self.readerVersion,
                appFolderRemoved: appFolderRemoved ? true : nil,
                metadataOverrides: metadataOverrides.isEmpty ? nil : metadataOverrides,
                folderSortOrders: folderOrders.isEmpty ? nil : folderOrders,
                knownAppFolders: Array(knownAppFolders).sorted()
            ),
            to: libraryURL
        )
    }

    private func savePlaylists() {
        try? Persistence.save(
            PlaylistFile(
                playlists: playlists,
                favourites: favourites,
                favouritesSortOrder: favouritesSortOrder == .custom ? nil : favouritesSortOrder,
                favouritesOrder: favouritesOrder.isEmpty ? nil : favouritesOrder
            ),
            to: playlistsURL
        )
    }

    // MARK: Roots

    private static func isDirectory(_ url: URL) -> Bool {
        var isDir: ObjCBool = false
        return FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir) && isDir.boolValue
    }

    /// On the Mac a bookmark only keeps its access across launches when it is made, and resolved,
    /// as a security-scoped one; iOS bookmarks of picked folders carry that implicitly.
    private static func makeBookmark(_ url: URL) -> Data? {
        #if targetEnvironment(macCatalyst)
        if let scoped = try? url.bookmarkData(options: [.withSecurityScope]) {
            return scoped
        }
        return try? url.bookmarkData()
        #else
        try? url.bookmarkData()
        #endif
    }

    private static func resolveBookmark(_ data: Data, isStale: inout Bool) -> URL? {
        #if targetEnvironment(macCatalyst)
        if let scoped = try? URL(resolvingBookmarkData: data, options: .withSecurityScope, bookmarkDataIsStale: &isStale) {
            return scoped
        }
        return try? URL(resolvingBookmarkData: data, bookmarkDataIsStale: &isStale)
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

    func isAvailable(_ root: LibraryRoot) -> Bool { root.kind.isCloud || rootURLs[root.id] != nil }
    func isRemovable(_ root: LibraryRoot) -> Bool { root.kind != .appFolder || appFolderRemovable }
    func rootURL(_ id: UUID) -> URL? { rootURLs[id] }
    func root(_ id: UUID) -> LibraryRoot? { roots.first { $0.id == id } }

    private func sameLocation(_ a: URL, _ b: URL) -> Bool {
        a.resolvingSymlinksInPath().standardizedFileURL.path == b.resolvingSymlinksInPath().standardizedFileURL.path
    }

    /// True when `url` is an existing local root or resides inside one.
    func containsLocalURL(_ url: URL) -> Bool {
        let targetPath = url.resolvingSymlinksInPath().standardizedFileURL.path
        return rootURLs.values.contains { rootURL in
            let rootPath = rootURL.resolvingSymlinksInPath().standardizedFileURL.path
            let prefix = rootPath.hasSuffix("/") ? rootPath : rootPath + "/"
            return targetPath == rootPath || targetPath.hasPrefix(prefix)
        }
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
        let manifest = CloudExporter.loadManifest(in: url)
        let root = LibraryRoot(
            id: UUID(),
            name: url.lastPathComponent,
            kind: .picked,
            bookmark: bookmark,
            sourceURL: manifest?.sourceURL,
            remoteID: manifest?.remoteID,
            lastSynced: manifest.map { Date(timeIntervalSince1970: Double($0.lastSyncedEpochMs) / 1000.0) },
            linkedCloudKind: manifest?.cloudKind
        )
        roots.append(root)
        rootURLs[root.id] = url
        saveLibrary()
        onFolderAdded?(root.name, [])
        await rescan()
        onFolderAdded?(root.name, tracks(under: root.id, path: "").map(\.id))
    }

    func notifyFolderQueueSync(folderURL: URL) {
        let name = folderURL.lastPathComponent
        guard !name.isEmpty else { return }
        if let matchingRoot = roots.first(where: { root in
            guard let u = rootURLs[root.id] else { return false }
            return sameLocation(u, folderURL)
        }) {
            onFolderAdded?(matchingRoot.name, tracks(under: matchingRoot.id, path: "").map(\.id))
        } else {
            onFolderAdded?(name, [])
        }
    }

    func attachCloudTracking(toFolderURL folderURL: URL, from cloudRoot: LibraryRoot) {
        guard let index = roots.firstIndex(where: { root in
            guard let url = rootURLs[root.id] else { return false }
            return sameLocation(url, folderURL)
        }) else { return }
        roots[index].sourceURL = cloudRoot.sourceURL
        roots[index].remoteID = cloudRoot.remoteID
        roots[index].linkedCloudKind = cloudRoot.effectiveCloudKind
        roots[index].cookie = cloudRoot.cookie
        roots[index].lastSynced = Date()
        saveLibrary()
    }

    func updateTrackedLocalRoot(
        _ rootID: UUID,
        cache: CloudAudioCache = .shared,
        onProgress: (@MainActor @Sendable (Int, Int, String) -> Void)? = nil
    ) async throws -> CloudExporter.CloudUpdateSummary {
        guard let index = roots.firstIndex(where: { $0.id == rootID }),
              let folderURL = rootURLs[rootID] else {
            throw LibraryError.notAFolder
        }
        let targetRoot = roots[index]
        guard targetRoot.isCloudTrackedLocal else {
            throw LibraryError.invalidCloudURL
        }
        let summary = try await CloudExporter.updateLocalFolder(
            root: targetRoot,
            folderURL: folderURL,
            fetcher: cloudFetcher,
            cache: cache,
            onProgress: onProgress
        )
        if let updatedIdx = roots.firstIndex(where: { $0.id == rootID }) {
            roots[updatedIdx].lastSynced = Date()
            saveLibrary()
        }
        if summary.addedCount > 0 {
            await rescan()
        }
        return summary
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

    func addCloudRoot(sourceURL: String, cookie: String? = nil) async throws {
        let trimmedURL = sourceURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedURL.isEmpty else { throw LibraryError.invalidCloudURL }
        let trimmedCookie = cookie?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanCookie = (trimmedCookie?.isEmpty == false) ? trimmedCookie : nil

        let fetched = try await cloudFetcher.fetchPlaylist(sourceURL: trimmedURL, cookie: cleanCookie, kindHint: nil)
        guard !roots.contains(where: { $0.kind == fetched.kind && $0.remoteID == fetched.remoteID }) else {
            throw LibraryError.duplicateRoot
        }

        let now = Date()
        let root = LibraryRoot(
            id: UUID(),
            name: fetched.title,
            kind: fetched.kind,
            bookmark: nil,
            sourceURL: trimmedURL,
            remoteID: fetched.remoteID,
            cookie: cleanCookie,
            proxyURL: nil,
            lastSynced: now
        )
        let (newTracks, _) = Self.mergeCloudTracks(root: root, items: fetched.tracks, existing: [:], now: now)
        roots.append(root)
        cloudSyncErrors[root.id] = nil
        publish(tracks + newTracks)
        onFolderAdded?(root.name, tracks(under: root.id, path: "").map(\.id))
        await populateMissingCloudArtwork(forRoot: root.id)
    }

    func removeRoot(_ id: UUID) {
        guard let index = roots.firstIndex(where: { $0.id == id }) else { return }
        if roots[index].kind == .appFolder {
            guard appFolderRemovable else { return }
            appFolderRemoved = true
        } else if roots[index].kind == .picked {
            rootURLs[id]?.stopAccessingSecurityScopedResource()
        }
        rootURLs[id] = nil
        cloudSyncErrors[id] = nil
        roots.remove(at: index)
        let removed = Set(tracks.filter { $0.rootID == id }.map(\.id))
        tracks.removeAll { $0.rootID == id }
        forget(removed)
        saveLibrary()
    }

    /// Excludes a subfolder from the library without deleting files on disk.
    func removeSubfolder(rootID: UUID, path: String) {
        let norm = LibraryRoot.normalizeFolderPath(path)
        guard !norm.isEmpty, let index = roots.firstIndex(where: { $0.id == rootID }) else { return }

        let current = roots[index].normalizedExcludedPaths.map(LibraryRoot.normalizeFolderPath).filter { !$0.isEmpty }
        let alreadyCovered = current.contains { ex in norm == ex || norm.hasPrefix(ex + "/") }
        if !alreadyCovered {
            var next = current.filter { ex in !(ex.hasPrefix(norm + "/")) }
            next.append(norm)
            next.sort()
            roots[index].excludedPaths = next
        }

        let removed = Set(tracks.filter {
            $0.rootID == rootID && LibraryRoot.folder(norm, contains: $0.relativePath)
        }.map(\.id))
        if !removed.isEmpty {
            tracks.removeAll { removed.contains($0.id) }
            forget(removed)
        }
        saveLibrary()
        if !removed.isEmpty {
            let keys = Set(tracks.compactMap(\.artworkKey))
            let artwork = self.artwork
            Task.detached(priority: .utility) { artwork.prune(keeping: keys) }
        }
    }

    /// Clears excluded subfolders on a root and rescans so their songs return.
    func restoreExcludedFolders(_ rootID: UUID) async {
        guard let index = roots.firstIndex(where: { $0.id == rootID }),
              !roots[index].normalizedExcludedPaths.isEmpty else { return }
        roots[index].excludedPaths = nil
        saveLibrary()
        await rescan()
    }

    /// Puts the built-in root back after the user removed it.
    func restoreAppFolder() async {
        guard appFolderRemoved else { return }
        appFolderRemoved = false
        roots.insert(Self.appFolderRoot, at: 0)
        rootURLs[LibraryRoot.appFolderID] = documentsURL
        saveLibrary()
        onFolderAdded?(Self.appFolderName, [])
        await rescan()
        onFolderAdded?(Self.appFolderName, tracks(under: LibraryRoot.appFolderID, path: "").map(\.id))
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

        var kept = tracks.filter { track in
            rootURLs[track.rootID] == nil && self.root(track.rootID)?.kind.isCloud != true
        }
        var pending: [(root: LibraryRoot, url: URL, file: ScannedFile, old: Track?)] = []
        var removed = Set<String>()
        var cloudChanged = false
        let reread = rereadAll
        let syncNow = Date()

        for root in roots {
            let existingTracks = tracks.filter { $0.rootID == root.id }
            if root.kind.isCloud {
                do {
                    let source = root.sourceURL ?? root.remoteID ?? ""
                    let fetched = try await cloudFetcher.fetchPlaylist(sourceURL: source, cookie: root.cookie, kindHint: root.kind)
                    // A root removed while fetching must not resurrect.
                    guard let rootIndex = roots.firstIndex(where: { $0.id == root.id }) else { continue }
                    cloudSyncErrors[root.id] = nil
                    roots[rootIndex].lastSynced = syncNow
                    if roots[rootIndex].name.isEmpty, !fetched.title.isEmpty {
                        roots[rootIndex].name = fetched.title
                    }
                    let existing = Dictionary(existingTracks.map { ($0.relativePath, $0) }, uniquingKeysWith: { first, _ in first })
                    let (merged, didChange) = Self.mergeCloudTracks(root: roots[rootIndex], items: fetched.tracks, existing: existing, now: syncNow)
                    let seen = Set(merged.map(\.relativePath))
                    let dropped = existing.filter { !seen.contains($0.key) }.map(\.value.id)
                    removed.formUnion(dropped)
                    if didChange || !dropped.isEmpty { cloudChanged = true }
                    kept.append(contentsOf: merged)
                } catch {
                    cloudSyncErrors[root.id] = error.localizedDescription
                    kept.append(contentsOf: existingTracks)
                }
                continue
            }

            guard let rootURL = rootURLs[root.id] else { continue }
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
                if root.isExcluded(file.relativePath) { continue }
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

        // A root or subfolder removed while we were reading must not come back as orphans.
        let rootsByID = Dictionary(uniqueKeysWithValues: roots.map { ($0.id, $0) })
        let final = kept.filter { track in
            guard let activeRoot = rootsByID[track.rootID] else { return false }
            return !activeRoot.isExcluded(track.relativePath)
        }
        removed = removed.filter { id in !final.contains { $0.id == id } }
        if reread { rereadAll = false }
        if !pending.isEmpty || !removed.isEmpty || final.count != tracks.count || cloudChanged {
            publish(final)
            forget(removed)
            let keys = Set(tracks.compactMap(\.artworkKey))
            await Task.detached(priority: .utility) { artwork.prune(keeping: keys) }.value
        } else if reread {
            saveLibrary()
        }

        if let appRoot = rootsByID[LibraryRoot.appFolderID], rootURLs[LibraryRoot.appFolderID] != nil {
            let onDiskFolders = Self.existingSubfolderNames(in: documentsURL)
            let newlyDiscovered = onDiskFolders.subtracting(knownAppFolders)
                .filter { !appRoot.isExcluded($0) }
                .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
            if !newlyDiscovered.isEmpty {
                knownAppFolders.formUnion(newlyDiscovered)
                saveLibrary()
                for folderName in newlyDiscovered {
                    let ids = tracks(under: LibraryRoot.appFolderID, path: folderName).map(\.id)
                    onFolderAdded?(folderName, ids)
                }
            }
        }

        await populateMissingCloudArtwork(forRoot: nil)
    }

    private func publish(_ list: [Track]) {
        let rootsByID = Dictionary(uniqueKeysWithValues: roots.map { ($0.id, $0) })
        let filtered = list.filter { track in
            guard let activeRoot = rootsByID[track.rootID] else { return false }
            return !activeRoot.isExcluded(track.relativePath)
        }.map { applyOverride(to: $0) }
        tracks = sortedByTitle(filtered)
        saveLibrary()
    }

    private func applyOverride(to track: Track) -> Track {
        guard let ov = metadataOverrides[track.id] else { return track }
        var updated = track
        if let title = ov.title { updated.title = title }
        if let artist = ov.artist {
            if updated.albumArtist == updated.artist {
                updated.albumArtist = artist
            }
            updated.artist = artist
        }
        if let album = ov.album { updated.album = album }
        if let genre = ov.genre { updated.genre = genre }
        if let artworkKey = ov.artworkKey { updated.artworkKey = artworkKey }
        return updated
    }

    private static func topFolderName(_ relativePath: String) -> String? {
        let norm = LibraryRoot.normalizeFolderPath(relativePath)
        guard let slash = norm.firstIndex(of: "/") else { return nil }
        return String(norm[..<slash])
    }

    private static func existingSubfolderNames(in rootURL: URL) -> Set<String> {
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: rootURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else { return [] }
        var names = Set<String>()
        for url in urls {
            if (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
                names.insert(url.lastPathComponent)
            }
        }
        return names
    }

    private func makeTrack(root: LibraryRoot, file: ScannedFile, metadata: TrackMetadata, artworkKey: String?, dateAdded: Date) -> Track {
        let name = (file.relativePath as NSString).lastPathComponent
        let folder = ((file.relativePath as NSString).deletingLastPathComponent as NSString).lastPathComponent
        let artist = metadata.artist ?? "Unknown Artist"
        let base = Track(
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
            artworkKey: artworkKey,
            genre: metadata.genre)
        return applyOverride(to: base)
    }

    private static func mergeCloudTracks(
        root: LibraryRoot,
        items: [CloudTrackInfo],
        existing: [String: Track],
        now: Date
    ) -> (tracks: [Track], changed: Bool) {
        let sourceName = root.kind == .cloudYouTube ? "youtube" : "bilibili"
        var seen = Set<String>()
        var result: [Track] = []
        var changed = false

        for item in items {
            if root.isExcluded(item.relativePath) { continue }
            guard seen.insert(item.relativePath).inserted else { continue }
            if let old = existing[item.relativePath] {
                let sameMetadata = old.title == item.title
                    && old.artist == item.artist
                    && old.album == item.album
                    && old.trackNumber == item.trackNumber
                    && abs(old.duration - item.duration) < 0.5
                    && old.remoteTrackID == item.remoteTrackID
                    && old.remoteArtworkURL == item.artworkURL
                    && old.cloudSource == sourceName
                if sameMetadata {
                    result.append(old)
                    continue
                }
            }
            changed = true
            let old = existing[item.relativePath]
            let keepArtworkKey = (old?.remoteArtworkURL == item.artworkURL) ? old?.artworkKey : nil
            result.append(Track(
                id: Track.makeID(rootID: root.id, relativePath: item.relativePath),
                rootID: root.id,
                relativePath: item.relativePath,
                title: item.title,
                artist: item.artist,
                album: item.album,
                albumArtist: item.artist,
                trackNumber: item.trackNumber,
                discNumber: nil,
                duration: item.duration,
                fileSize: 0,
                modified: now,
                hasEmbeddedLyrics: false,
                dateAdded: old?.dateAdded ?? now,
                artworkKey: keepArtworkKey,
                cloudSource: sourceName,
                remoteTrackID: item.remoteTrackID,
                remoteArtworkURL: item.artworkURL
            ))
        }
        return (result, changed)
    }

    private func populateMissingCloudArtwork(forRoot rootID: UUID?) async {
        let targets = tracks.filter { track in
            track.isCloud
                && track.artworkKey == nil
                && track.remoteArtworkURL != nil
                && (rootID == nil || track.rootID == rootID)
        }
        guard !targets.isEmpty else { return }

        // Deduplicate thumbnail URLs so shared covers download once.
        var byURL: [String: [String]] = [:]
        for track in targets {
            if let urlStr = track.remoteArtworkURL {
                byURL[urlStr, default: []].append(track.id)
            }
        }

        let downloader = self.artworkDownloader
        let artwork = self.artwork
        var urlToKey: [String: String] = [:]

        let urlStrings = Array(byURL.keys)
        let batchLimit = 6
        for start in stride(from: 0, to: urlStrings.count, by: batchLimit) {
            let batch = Array(urlStrings[start..<min(start + batchLimit, urlStrings.count)])
            await withTaskGroup(of: (String, String?).self) { group in
                for urlStr in batch {
                    group.addTask {
                        guard let url = URL(string: urlStr),
                              let data = await downloader(url),
                              let key = artwork.store(data) else {
                            return (urlStr, nil)
                        }
                        return (urlStr, key)
                    }
                }
                for await (urlStr, key) in group {
                    if let key { urlToKey[urlStr] = key }
                }
            }
        }

        guard !urlToKey.isEmpty else { return }
        var updated = tracks
        var changed = false
        for index in updated.indices {
            if updated[index].artworkKey == nil,
               let urlStr = updated[index].remoteArtworkURL,
               let key = urlToKey[urlStr] {
                updated[index].artworkKey = key
                changed = true
            }
        }
        if changed {
            publish(updated)
        }
    }

    private static let defaultArtworkDownload: @Sendable (URL) async -> Data? = { url in
        var req = URLRequest(url: url, timeoutInterval: 10)
        req.setValue(YouTubeClient.webUserAgent, forHTTPHeaderField: "User-Agent")
        if url.host?.lowercased().contains("hdslb.com") == true {
            req.setValue(BilibiliClient.referer, forHTTPHeaderField: "Referer")
        }
        guard let (data, response) = try? await URLSession.shared.data(for: req),
              let http = response as? HTTPURLResponse,
              (200..<300).contains(http.statusCode),
              !data.isEmpty else {
            return nil
        }
        return data
    }

    /// Removes vanished tracks from playlists and favourites and tells the player.
    private func forget(_ ids: Set<String>) {
        guard !ids.isEmpty else { return }
        for index in playlists.indices { playlists[index].trackIDs.removeAll { ids.contains($0) } }
        favourites.subtract(ids)
        favouritesOrder.removeAll { ids.contains($0) }
        for id in ids { metadataOverrides.removeValue(forKey: id) }
        for key in folderOrders.keys {
            folderOrders[key]?.customTrackIDs.removeAll { ids.contains($0) }
        }
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

    func isAvailable(_ track: Track) -> Bool { track.isCloud || rootURLs[track.rootID] != nil }

    func embeddedLyrics(for track: Track) async -> String? {
        guard track.hasEmbeddedLyrics, let url = url(for: track) else { return nil }
        return await reader.read(url).lyrics
    }

    func albums(byArtist artist: String) -> [Album] {
        albums.filter { album in album.artist == artist || album.trackIDs.contains { byID[$0]?.artist == artist } }
    }

    func tracks(byArtist artist: String) -> [Track] { tracks.filter { $0.artist == artist } }

    // MARK: Metadata editing & search

    @discardableResult
    func updateTrackMetadata(
        id: String,
        title: String,
        artist: String,
        album: String,
        genre: String,
        artworkData: Data? = nil
    ) -> Track? {
        guard let index = tracks.firstIndex(where: { $0.id == id }) else { return nil }
        let existing = tracks[index]
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanArtist = artist.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanAlbum = album.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanGenre = genre.trimmingCharacters(in: .whitespacesAndNewlines)

        let finalTitle = cleanTitle.isEmpty ? existing.title : cleanTitle
        let finalArtist = cleanArtist.isEmpty ? "Unknown Artist" : cleanArtist
        let finalAlbum = cleanAlbum.isEmpty ? "Unknown Album" : cleanAlbum
        let newArtworkKey = artworkData.flatMap { artwork.store($0) } ?? metadataOverrides[id]?.artworkKey

        let override = TrackMetadataOverride(
            title: finalTitle,
            artist: finalArtist,
            album: finalAlbum,
            genre: cleanGenre,
            artworkKey: newArtworkKey
        )
        metadataOverrides[id] = override

        var updated = existing
        updated.title = finalTitle
        if updated.albumArtist == updated.artist {
            updated.albumArtist = finalArtist
        }
        updated.artist = finalArtist
        updated.album = finalAlbum
        updated.genre = cleanGenre
        if let newArtworkKey {
            updated.artworkKey = newArtworkKey
        }
        tracks[index] = updated
        saveLibrary()
        onTrackUpdated?(updated)

        if !updated.isCloud, let fileURL = url(for: updated) {
            let payload = AudioTagWriter.Payload(
                title: finalTitle,
                artist: finalArtist,
                album: finalAlbum,
                genre: cleanGenre,
                artworkData: artworkData
            )
            Task.detached(priority: .utility) { [weak self] in
                let wrote = await AudioTagWriter.write(payload, to: fileURL)
                if wrote,
                   let attrs = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
                   let mtime = attrs[.modificationDate] as? Date,
                   let size = (attrs[.size] as? NSNumber)?.int64Value {
                    await MainActor.run {
                        self?.syncFileStatsAfterTagWrite(id: id, fileSize: size, modified: mtime)
                    }
                }
            }
        }
        return updated
    }

    private func syncFileStatsAfterTagWrite(id: String, fileSize: Int64, modified: Date) {
        guard let index = tracks.firstIndex(where: { $0.id == id }) else { return }
        tracks[index].fileSize = fileSize
        tracks[index].modified = modified
        saveLibrary()
    }

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

    // MARK: Folder sorting & custom reordering

    static func folderKey(rootID: UUID, path: String) -> String {
        "\(rootID.uuidString)::\(LibraryRoot.normalizeFolderPath(path))"
    }

    func folderSortOrder(root: UUID, path: String) -> TrackSortOrder {
        folderOrders[Self.folderKey(rootID: root, path: path)]?.sortOrder ?? .custom
    }

    func setFolderSortOrder(_ order: TrackSortOrder, root: UUID, path: String) {
        let key = Self.folderKey(rootID: root, path: path)
        var state = folderOrders[key] ?? FolderOrderState()
        state.sortOrder = order
        folderOrders[key] = state
        saveLibrary()
    }

    func orderedFolderTracks(root: UUID, path: String, history: PlayHistoryStore? = nil) -> [Track] {
        let direct = folderContents(root: root, path: path).tracks
        let key = Self.folderKey(rootID: root, path: path)
        let state = folderOrders[key] ?? FolderOrderState()
        switch state.sortOrder {
        case .custom:
            guard !state.customTrackIDs.isEmpty else { return direct }
            let byTrackID = Dictionary(direct.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            var ordered: [Track] = []
            var seen = Set<String>()
            for id in state.customTrackIDs {
                if let track = byTrackID[id], seen.insert(id).inserted {
                    ordered.append(track)
                }
            }
            for track in direct where !seen.contains(track.id) {
                ordered.append(track)
            }
            return ordered
        default:
            return TrackSorter.sort(
                direct,
                by: state.sortOrder,
                playCount: { history?.count(for: $0) ?? 0 },
                lastPlayed: { history?.lastPlayed(for: $0) }
            )
        }
    }

    func orderedTracks(under root: UUID, path: String, history: PlayHistoryStore? = nil) -> [Track] {
        let direct = orderedFolderTracks(root: root, path: path, history: history)
        let subfolders = folderContents(root: root, path: path).folders
        var result = direct
        for child in subfolders {
            let childPath = path.isEmpty ? child : "\(path)/\(child)"
            result.append(contentsOf: orderedTracks(under: root, path: childPath, history: history))
        }
        return result
    }

    func moveFolderTracks(root: UUID, path: String, currentTrackIDs: [String], from source: IndexSet, to destination: Int) {
        var reordered = currentTrackIDs
        reordered.move(fromOffsets: source, toOffset: destination)
        let key = Self.folderKey(rootID: root, path: path)
        folderOrders[key] = FolderOrderState(sortOrder: .custom, customTrackIDs: reordered)
        saveLibrary()
    }

    func search(_ query: String, field: SearchField = .keyword) -> [Track] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty else { return [] }
        switch field {
        case .keyword:
            return tracks.filter {
                $0.title.localizedStandardContains(needle)
                    || $0.artist.localizedStandardContains(needle)
                    || $0.album.localizedStandardContains(needle)
                    || $0.displayGenre.localizedStandardContains(needle)
            }
        case .artist:
            return tracks.filter {
                $0.artist.compare(needle, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
            }
        case .album:
            return tracks.filter {
                $0.album.compare(needle, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
            }
        case .genre:
            return tracks.filter {
                $0.displayGenre.compare(needle, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame
            }
        }
    }

    // MARK: Favourites and playlists

    func isFavourite(_ id: String) -> Bool { favourites.contains(id) }

    func toggleFavourite(_ id: String) {
        if favourites.contains(id) {
            favourites.remove(id)
            favouritesOrder.removeAll { $0 == id }
        } else {
            favourites.insert(id)
            if !favouritesOrder.contains(id) {
                favouritesOrder.append(id)
            }
        }
        savePlaylists()
    }

    func orderedFavourites(history: PlayHistoryStore? = nil) -> [Track] {
        let favTracks = tracks.filter { favourites.contains($0.id) }
        switch favouritesSortOrder {
        case .custom:
            guard !favouritesOrder.isEmpty else { return favTracks }
            let map = Dictionary(favTracks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            var ordered: [Track] = []
            var seen = Set<String>()
            for id in favouritesOrder {
                if let t = map[id], seen.insert(id).inserted { ordered.append(t) }
            }
            for t in favTracks where !seen.contains(t.id) { ordered.append(t) }
            return ordered
        default:
            return TrackSorter.sort(
                favTracks,
                by: favouritesSortOrder,
                playCount: { history?.count(for: $0) ?? 0 },
                lastPlayed: { history?.lastPlayed(for: $0) }
            )
        }
    }

    func sortFavourites(by order: TrackSortOrder, history: PlayHistoryStore? = nil) {
        if order != .custom {
            favouritesOrder = orderedFavourites(history: nil).map(\.id)
        }
        favouritesSortOrder = order
        if order != .custom {
            favouritesOrder = TrackSorter.sort(
                tracks.filter { favourites.contains($0.id) },
                by: order,
                playCount: { history?.count(for: $0) ?? 0 },
                lastPlayed: { history?.lastPlayed(for: $0) }
            ).map(\.id)
        }
        savePlaylists()
    }

    func moveFavourites(currentTrackIDs: [String], from source: IndexSet, to destination: Int) {
        var reordered = currentTrackIDs
        reordered.move(fromOffsets: source, toOffset: destination)
        favouritesOrder = reordered
        favouritesSortOrder = .custom
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

    func sortPlaylist(_ id: UUID, by order: TrackSortOrder, history: PlayHistoryStore? = nil) {
        updatePlaylist(id) { playlist in
            if order != .custom {
                let orderIndices = TrackSorter.sortedIndexOrder(
                    for: playlist.trackIDs,
                    tracksByID: byID,
                    by: order,
                    playCount: { history?.count(for: $0) ?? 0 },
                    lastPlayed: { history?.lastPlayed(for: $0) }
                )
                playlist.trackIDs = orderIndices.map { playlist.trackIDs[$0] }
            }
            playlist.sortOrder = order
        }
    }

    func movePlaylistTracks(_ id: UUID, from source: IndexSet, to destination: Int) {
        updatePlaylist(id) { playlist in
            let moved = source.filter { playlist.trackIDs.indices.contains($0) }.map { playlist.trackIDs[$0] }
            var remaining = playlist.trackIDs.enumerated().filter { !source.contains($0.offset) }.map(\.element)
            let insertAt = destination - source.filter { $0 < destination }.count
            remaining.insert(contentsOf: moved, at: min(max(insertAt, 0), remaining.count))
            playlist.trackIDs = remaining
            playlist.sortOrder = .custom
        }
    }
}
