import Foundation
import Observation

enum LibraryError: LocalizedError {
    case duplicateRoot
    case notAFolder
    case invalidCloudURL
    case cannotDeleteAppFolderRoot
    case fileNotFoundOnDisk
    case cannotDeleteCloudItem
    var errorDescription: String? {
        switch self {
        case .duplicateRoot: L10n.trNonisolated("That location is already in your library.")
        case .notAFolder: L10n.trNonisolated("That location could not be opened as a folder.")
        case .invalidCloudURL: L10n.trNonisolated("Enter a valid Google Drive link or a link supported by an installed extension.")
        case .cannotDeleteAppFolderRoot: L10n.trNonisolated("The built-in music folder cannot be deleted from disk.")
        case .fileNotFoundOnDisk: L10n.trNonisolated("The file or folder could not be found on disk.")
        case .cannotDeleteCloudItem: L10n.trNonisolated("Cloud items can only be removed from the library.")
        }
    }
}

/// The music library: folder roots, the track index, playlists and favourites.
@MainActor @Observable
final class LibraryStore {
    struct CloudSyncProgress: Equatable, Sendable {
        var foldersDone: Int
        var foldersTotal: Int
        var tracksLoaded: Int
    }

    struct KnownDriveSubfolder: Equatable, Sendable {
        var id: String
        var resourceKey: String?
        var pathSegments: [String]
        var depth: Int
        var isLoaded: Bool = false
        var extraJSON: String? = nil

        var relativePath: String { pathSegments.joined(separator: "/") }
    }

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
        var ephemeralTracks: [String: Track]?
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
    private(set) var ephemeralTracks: [String: Track] = [:]
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
    private(set) var cloudSyncProgress: [UUID: CloudSyncProgress] = [:]
    private(set) var mergedFolderRedirects: [UUID: FolderRef] = [:]
    private var knownDriveSubfolders: [UUID: [String: KnownDriveSubfolder]] = [:]
    private var rootURLs: [UUID: URL] = [:]
    /// True once the user has removed the built-in root (only possible where it is removable).
    private(set) var appFolderRemoved = false
    /// On the Mac the built-in root is the user's Music folder, which they may not want indexed.
    @ObservationIgnored let appFolderRemovable: Bool

    @ObservationIgnored var onBeforeDeletingTracks: ((Set<String>) -> Void)?
    @ObservationIgnored var onTracksRemoved: ((Set<String>) -> Void)?
    @ObservationIgnored var onTracksRemapped: (([String: String]) -> Void)?
    @ObservationIgnored var onTrackUpdated: ((Track) -> Void)?
    @ObservationIgnored var onFolderAdded: ((_ name: String, _ trackIDs: [String]) -> Void)?
    @ObservationIgnored private var byID: [String: Track] = [:]
    @ObservationIgnored private let directory: URL
    @ObservationIgnored private let documentsURL: URL
    @ObservationIgnored private let reader: MetadataReading
    @ObservationIgnored let artwork: ArtworkCache
    @ObservationIgnored private let cloudFetcher: any CloudPlaylistFetching
    @ObservationIgnored private let providerRegistry: CloudProviderRegistry
    @ObservationIgnored private let extensionAvailabilityProvider: @Sendable (String) -> CloudExtensionAvailability
    @ObservationIgnored private let artworkDownloader: @Sendable (URL) async -> Data?
    /// How many newly read songs are published (and saved) at a time during a scan.
    @ObservationIgnored private let scanBatchSize: Int
    @ObservationIgnored private var rescanRequested = false
    @ObservationIgnored private var driveCrawlTasks: [UUID: Task<Void, Never>] = [:]
    @ObservationIgnored private var driveCrawlPriorities: [UUID: DriveCrawlPriorityState] = [:]
    @ObservationIgnored private var onDemandDriveFolderTasks: [String: Task<Void, Never>] = [:]

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
         providerRegistry: CloudProviderRegistry? = nil,
         extensionAvailability: (@Sendable (String) -> CloudExtensionAvailability)? = nil,
         artworkDownloader: (@Sendable (URL) async -> Data?)? = nil) {
        self.appFolderRemovable = appFolderRemovable
        self.scanBatchSize = max(scanBatchSize, 1)
        self.directory = directory
        self.documentsURL = documentsURL
        self.reader = reader
        self.artwork = artwork
        self.cloudFetcher = cloudFetcher
        let resolvedRegistry = providerRegistry ?? (cloudFetcher as? LiveCloudService)?.registry ?? .shared
        self.providerRegistry = resolvedRegistry
        self.extensionAvailabilityProvider = extensionAvailability ?? { [resolvedRegistry] pid in
            if resolvedRegistry.provider(forID: pid) != nil {
                return .available
            }
            if resolvedRegistry === CloudProviderRegistry.shared {
                return ExtensionStore.shared.availability(forProviderID: pid)
            }
            return .missing(providerID: pid)
        }
        self.artworkDownloader = artworkDownloader ?? Self.defaultArtworkDownload

        let file = Persistence.load(LibraryFile.self, from: libraryURL)
        rereadAll = file != nil && file?.readerVersion != Self.readerVersion
        let savedAppRoot = file?.roots.first { $0.kind == .appFolder }
        var loadedRoots = file?.roots.filter { $0.kind != .appFolder } ?? []
        appFolderRemoved = appFolderRemovable && file?.appFolderRemoved == true
        if !appFolderRemoved {
            var appRoot = Self.appFolderRoot
            appRoot.excludedPaths = savedAppRoot?.excludedPaths
            appRoot.sourceURL = savedAppRoot?.sourceURL
            appRoot.remoteID = savedAppRoot?.remoteID
            appRoot.cookie = savedAppRoot?.cookie
            appRoot.lastSynced = savedAppRoot?.lastSynced
            appRoot.providerID = savedAppRoot?.providerID
            appRoot.linkedCloudKind = savedAppRoot?.linkedCloudKind
            appRoot.linkedProviderID = savedAppRoot?.linkedProviderID
            appRoot.trackedSubfolders = savedAppRoot?.trackedSubfolders
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
        ephemeralTracks = (file?.ephemeralTracks ?? [:]).mapValues { item in
            var copy = item
            copy.isEphemeral = true
            return copy
        }
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
                knownAppFolders: Array(knownAppFolders).sorted(),
                ephemeralTracks: ephemeralTracks.isEmpty ? nil : ephemeralTracks
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

    func provider(for root: LibraryRoot) -> (any CloudSourceProvider)? {
        providerRegistry.provider(forRoot: root)
    }

    func provider(for track: Track, root: LibraryRoot? = nil) -> (any CloudSourceProvider)? {
        let resolvedRoot = root ?? self.root(track.rootID)
        return providerRegistry.provider(forTrack: track, root: resolvedRoot)
    }

    func compatibleProviders(
        forSourceURL sourceURL: String?,
        excludingProviderID: String? = nil
    ) -> [any CloudSourceProvider] {
        let raw = (sourceURL ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return [] }
        let extracted = CloudSourceInput.extract(from: raw, registry: providerRegistry)
        guard !extracted.isEmpty else { return [] }
        return providerRegistry.allProviders().filter { provider in
            guard provider.supportsCollection else { return false }
            if let excludingProviderID, provider.id == excludingProviderID { return false }
            if provider.canHandle(rawText: raw, extractedURL: extracted) != nil {
                return true
            }
            if provider.id == BuiltInProviderID.googleDrive, GoogleDriveClient.isValidDriveID(extracted) {
                return true
            }
            return false
        }
    }

    func compatibleProviders(for root: LibraryRoot) -> [any CloudSourceProvider] {
        compatibleProviders(forSourceURL: root.sourceURL, excludingProviderID: root.effectiveProviderID)
    }

    func cloudExtensionAvailability(for root: LibraryRoot) -> CloudExtensionAvailability {
        if root.kind.isLegacyExtensionBacked {
            if providerRegistry.provider(forRoot: root) != nil {
                return .available
            }
            let fallbackID = CloudProviderRegistry.legacyFallbackExtensionID(
                forKind: root.kind,
                providerID: root.resolvedProviderID
            ) ?? ""
            return extensionAvailabilityProvider(fallbackID)
        }
        guard root.kind == .cloudExtension else { return .available }
        guard let pid = root.resolvedProviderID, !pid.isEmpty else {
            return .missing(providerID: "")
        }
        if providerRegistry.provider(forID: pid) != nil {
            return .available
        }
        return extensionAvailabilityProvider(pid)
    }

    func isAvailable(_ root: LibraryRoot) -> Bool {
        if root.kind == .cloudExtension || root.kind.isLegacyExtensionBacked {
            return cloudExtensionAvailability(for: root) == .available
        }
        return root.kind.isCloud || rootURLs[root.id] != nil
    }
    func isRemovable(_ root: LibraryRoot) -> Bool { root.kind != .appFolder || appFolderRemovable }
    func rootURL(_ id: UUID) -> URL? { rootURLs[id] }
    func root(_ id: UUID) -> LibraryRoot? { roots.first { $0.id == id } }

    private static func canonicalPath(_ url: URL) -> String {
        let raw = url.resolvingSymlinksInPath().standardizedFileURL.path
        if raw.count > 1 && raw.hasSuffix("/") {
            return String(raw.dropLast())
        }
        return raw
    }

    private func sameLocation(_ a: URL, _ b: URL) -> Bool {
        Self.canonicalPath(a) == Self.canonicalPath(b)
    }

    /// Returns the local root containing `url` (exact root match gives `relativePath == ""`).
    func containingLocalRoot(for url: URL) -> (root: LibraryRoot, relativePath: String)? {
        let targetPath = Self.canonicalPath(url)
        var bestMatch: (root: LibraryRoot, relativePath: String, rootLength: Int)?
        for root in roots where root.kind.isLocal {
            guard let rootURL = rootURLs[root.id] else { continue }
            let rootPath = Self.canonicalPath(rootURL)
            if targetPath == rootPath {
                return (root, "")
            }
            let prefix = rootPath == "/" ? "/" : rootPath + "/"
            if targetPath.hasPrefix(prefix) {
                let rel = LibraryRoot.normalizeFolderPath(String(targetPath.dropFirst(prefix.count)))
                if bestMatch == nil || rootPath.count > bestMatch!.rootLength {
                    bestMatch = (root, rel, rootPath.count)
                }
            }
        }
        return bestMatch.map { ($0.root, $0.relativePath) }
    }

    /// True when `url` is an existing local root or resides inside one.
    func containsLocalURL(_ url: URL) -> Bool {
        containingLocalRoot(for: url) != nil
    }

    func addRoot(url: URL) async throws {
        try await addRoot(url: url, notifyFolderAdded: true, skippingCloudRootID: nil)
    }

    private func addRoot(url: URL, notifyFolderAdded: Bool, skippingCloudRootID: UUID?) async throws {
        let scoped = url.startAccessingSecurityScopedResource()
        func fail(_ error: LibraryError) -> LibraryError {
            if scoped { url.stopAccessingSecurityScopedResource() }
            return error
        }
        guard Self.isDirectory(url) else { throw fail(.notAFolder) }
        guard containingLocalRoot(for: url) == nil else { throw fail(.duplicateRoot) }
        guard let bookmark = Self.makeBookmark(url) else { throw fail(.notAFolder) }

        let newRootID = UUID()
        let parentPath = Self.canonicalPath(url)
        let parentPrefix = parentPath == "/" ? "/" : parentPath + "/"

        var migratedTrackedSubfolders: [String: CloudTrackingInfo] = [:]
        var migratedExcludedPaths: [String] = []
        var containedPickedIDs: [(id: UUID, relPath: String)] = []

        for existingRoot in roots where existingRoot.kind == .picked {
            guard let childURL = rootURLs[existingRoot.id] else { continue }
            let childPath = Self.canonicalPath(childURL)
            guard childPath.hasPrefix(parentPrefix) else { continue }
            let relPath = LibraryRoot.normalizeFolderPath(String(childPath.dropFirst(parentPrefix.count)))
            guard !relPath.isEmpty else { continue }
            containedPickedIDs.append((existingRoot.id, relPath))

            if existingRoot.isCloudTrackedLocal, let src = existingRoot.sourceURL {
                migratedTrackedSubfolders[relPath] = CloudTrackingInfo(
                    sourceURL: src,
                    remoteID: existingRoot.remoteID,
                    cloudKind: existingRoot.linkedCloudKind ?? .cloudExtension,
                    cookie: existingRoot.cookie,
                    lastSynced: existingRoot.lastSynced,
                    providerID: existingRoot.effectiveProviderID
                )
            }
            for (subRel, subInfo) in existingRoot.normalizedTrackedSubfolders {
                migratedTrackedSubfolders["\(relPath)/\(subRel)"] = subInfo
            }
            for excluded in existingRoot.normalizedExcludedPaths {
                let normEx = LibraryRoot.normalizeFolderPath(excluded)
                if !normEx.isEmpty {
                    migratedExcludedPaths.append("\(relPath)/\(normEx)")
                }
            }
        }

        if !containedPickedIDs.isEmpty {
            let relByChildID = Dictionary(uniqueKeysWithValues: containedPickedIDs.map { ($0.id, $0.relPath) })
            var trackIDMap: [String: String] = [:]
            var updatedTracks = tracks
            for idx in updatedTracks.indices {
                guard let relPrefix = relByChildID[updatedTracks[idx].rootID] else { continue }
                let oldID = updatedTracks[idx].id
                let newRel = LibraryRoot.normalizeFolderPath("\(relPrefix)/\(updatedTracks[idx].relativePath)")
                let newID = Track.makeID(rootID: newRootID, relativePath: newRel)
                updatedTracks[idx].rootID = newRootID
                updatedTracks[idx].relativePath = newRel
                updatedTracks[idx].id = newID
                trackIDMap[oldID] = newID
            }

            for (childID, relPrefix) in containedPickedIDs {
                let oldPrefix = "\(childID.uuidString)::"
                let matchingKeys = folderOrders.keys.filter { $0.hasPrefix(oldPrefix) }
                for oldKey in matchingKeys {
                    if let state = folderOrders.removeValue(forKey: oldKey) {
                        let sub = String(oldKey.dropFirst(oldPrefix.count))
                        let combined = sub.isEmpty ? relPrefix : "\(relPrefix)/\(sub)"
                        folderOrders[Self.folderKey(rootID: newRootID, path: combined)] = state
                    }
                }
                rootURLs[childID]?.stopAccessingSecurityScopedResource()
                rootURLs[childID] = nil
                cloudSyncErrors[childID] = nil
            }
            let removedIDs = Set(containedPickedIDs.map(\.id))
            roots.removeAll { removedIDs.contains($0.id) }
            tracks = updatedTracks
            remapTrackIDs(trackIDMap)
        }

        let manifest = CloudExporter.loadManifest(in: url)
        let root = LibraryRoot(
            id: newRootID,
            name: url.lastPathComponent,
            kind: .picked,
            bookmark: bookmark,
            sourceURL: manifest?.sourceURL,
            remoteID: manifest?.remoteID,
            lastSynced: manifest.map { Date(timeIntervalSince1970: Double($0.lastSyncedEpochMs) / 1000.0) },
            excludedPaths: migratedExcludedPaths.isEmpty ? nil : migratedExcludedPaths.sorted(),
            linkedCloudKind: manifest?.cloudKind,
            trackedSubfolders: migratedTrackedSubfolders.isEmpty ? nil : migratedTrackedSubfolders,
            linkedProviderID: manifest?.resolvedProviderID
        )
        roots.append(root)
        rootURLs[root.id] = url
        saveLibrary()
        if notifyFolderAdded {
            onFolderAdded?(root.name, [])
        }
        await rescan(skippingCloudRootID: skippingCloudRootID)
        if notifyFolderAdded {
            onFolderAdded?(root.name, tracks(under: root.id, path: "").map(\.id))
        }
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
        guard let match = containingLocalRoot(for: folderURL),
              let index = roots.firstIndex(where: { $0.id == match.root.id }) else { return }
        let normPath = LibraryRoot.normalizeFolderPath(match.relativePath)
        if normPath.isEmpty {
            roots[index].sourceURL = cloudRoot.sourceURL
            roots[index].remoteID = cloudRoot.remoteID
            roots[index].linkedCloudKind = cloudRoot.effectiveCloudKind
            roots[index].linkedProviderID = cloudRoot.effectiveProviderID
            roots[index].cookie = cloudRoot.cookie
            roots[index].streamQualityID = cloudRoot.streamQualityID
            roots[index].downloadQualityID = cloudRoot.downloadQualityID
            roots[index].lastSynced = Date()
        } else if let sourceURL = cloudRoot.sourceURL?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !sourceURL.isEmpty,
                  let cloudKind = cloudRoot.effectiveCloudKind {
            var map = roots[index].normalizedTrackedSubfolders
            map[normPath] = CloudTrackingInfo(
                sourceURL: sourceURL,
                remoteID: cloudRoot.remoteID,
                cloudKind: cloudKind,
                cookie: cloudRoot.cookie,
                lastSynced: Date(),
                providerID: cloudRoot.effectiveProviderID,
                streamQualityID: cloudRoot.streamQualityID,
                downloadQualityID: cloudRoot.downloadQualityID
            )
            roots[index].trackedSubfolders = map
        }
        saveLibrary()
    }

    func registerExportedCloudFolder(
        destinationURL: URL,
        from cloudRoot: LibraryRoot,
        mergeCloudRoot: Bool = false
    ) async throws {
        let now = Date()
        let trimmedSource = cloudRoot.sourceURL?.trimmingCharacters(in: .whitespacesAndNewlines)
        let trackingInfo: CloudTrackingInfo? = {
            guard let trimmedSource, !trimmedSource.isEmpty,
                  let cloudKind = cloudRoot.effectiveCloudKind else {
                return nil
            }
            return CloudTrackingInfo(
                sourceURL: trimmedSource,
                remoteID: cloudRoot.remoteID,
                cloudKind: cloudKind,
                cookie: cloudRoot.cookie,
                lastSynced: now,
                providerID: cloudRoot.effectiveProviderID,
                streamQualityID: cloudRoot.streamQualityID,
                downloadQualityID: cloudRoot.downloadQualityID
            )
        }()

        let shouldMerge = mergeCloudRoot
            && cloudRoot.kind.isCloud
            && roots.contains(where: { $0.id == cloudRoot.id })
        let cloudTracksSnapshot = shouldMerge ? tracks.filter { $0.rootID == cloudRoot.id } : []

        let targetRootID: UUID
        let targetRelPath: String
        let folderName: String
        var createdStandaloneRootID: UUID? = nil

        if let match = containingLocalRoot(for: destinationURL),
           let index = roots.firstIndex(where: { $0.id == match.root.id }) {
            let rootID = match.root.id
            let relPath = LibraryRoot.normalizeFolderPath(match.relativePath)
            if relPath.isEmpty {
                if let info = trackingInfo {
                    roots[index].sourceURL = info.sourceURL
                    roots[index].remoteID = info.remoteID
                    roots[index].linkedCloudKind = info.cloudKind
                    roots[index].linkedProviderID = info.resolvedProviderID
                    roots[index].cookie = info.cookie
                    roots[index].streamQualityID = info.streamQualityID
                    roots[index].downloadQualityID = info.downloadQualityID
                    roots[index].lastSynced = info.lastSynced
                }
            } else {
                let currentExcluded = roots[index].normalizedExcludedPaths.map(LibraryRoot.normalizeFolderPath)
                let filteredExcluded = currentExcluded.filter { ex in
                    guard !ex.isEmpty else { return false }
                    return !(relPath == ex || relPath.hasPrefix(ex + "/") || ex.hasPrefix(relPath + "/"))
                }
                roots[index].excludedPaths = filteredExcluded.isEmpty ? nil : filteredExcluded
                if let info = trackingInfo {
                    var map = roots[index].normalizedTrackedSubfolders
                    map[relPath] = info
                    roots[index].trackedSubfolders = map
                }
            }
            saveLibrary()
            await rescan(skippingCloudRootID: shouldMerge ? cloudRoot.id : nil)
            targetRootID = rootID
            targetRelPath = relPath
            folderName = relPath.isEmpty ? (root(rootID)?.name ?? destinationURL.lastPathComponent) : destinationURL.lastPathComponent
        } else {
            try await addRoot(url: destinationURL, notifyFolderAdded: !shouldMerge, skippingCloudRootID: shouldMerge ? cloudRoot.id : nil)
            attachCloudTracking(toFolderURL: destinationURL, from: cloudRoot)
            guard let addedMatch = containingLocalRoot(for: destinationURL) else {
                return
            }
            targetRootID = addedMatch.root.id
            targetRelPath = LibraryRoot.normalizeFolderPath(addedMatch.relativePath)
            folderName = addedMatch.root.name
            if targetRelPath.isEmpty {
                createdStandaloneRootID = addedMatch.root.id
            }
        }

        if shouldMerge {
            let localTracks = tracks(under: targetRootID, path: targetRelPath)
            let manifest = CloudExporter.loadManifest(in: destinationURL)
            let trackIDMap = Self.buildMergedTrackIDMap(
                cloudTracks: cloudTracksSnapshot,
                localTracks: localTracks,
                targetRelPath: targetRelPath,
                manifest: manifest
            )

            let oldFolderKey = Self.folderKey(rootID: cloudRoot.id, path: "")
            let newFolderKey = Self.folderKey(rootID: targetRootID, path: targetRelPath)
            if oldFolderKey != newFolderKey, var oldOrder = folderOrders.removeValue(forKey: oldFolderKey) {
                oldOrder.customTrackIDs = oldOrder.customTrackIDs.map { trackIDMap[$0] ?? $0 }
                if folderOrders[newFolderKey] == nil {
                    folderOrders[newFolderKey] = oldOrder
                }
            }

            remapTrackIDs(trackIDMap)

            driveCrawlTasks[cloudRoot.id]?.cancel()
            driveCrawlTasks[cloudRoot.id] = nil
            driveCrawlPriorities[cloudRoot.id] = nil
            cloudSyncProgress[cloudRoot.id] = nil
            knownDriveSubfolders[cloudRoot.id] = nil
            let keyPrefix = "\(cloudRoot.id.uuidString)::"
            for key in onDemandDriveFolderTasks.keys where key.hasPrefix(keyPrefix) {
                onDemandDriveFolderTasks[key]?.cancel()
                onDemandDriveFolderTasks[key] = nil
            }
            cloudSyncErrors[cloudRoot.id] = nil

            let cloudOriginalIdx = roots.firstIndex(where: { $0.id == cloudRoot.id })
            if let cloudOriginalIdx {
                roots.remove(at: cloudOriginalIdx)
            }
            if let standaloneID = createdStandaloneRootID,
               let cloudOriginalIdx,
               let currentStandaloneIdx = roots.firstIndex(where: { $0.id == standaloneID }) {
                let standaloneRoot = roots.remove(at: currentStandaloneIdx)
                let insertIdx = min(cloudOriginalIdx, roots.count)
                roots.insert(standaloneRoot, at: insertIdx)
            }

            let unmappedIDs = Set(cloudTracksSnapshot.map(\.id)).subtracting(trackIDMap.keys)
            let remainingTracks = tracks.filter { $0.rootID != cloudRoot.id }
            publish(remainingTracks)
            if !unmappedIDs.isEmpty {
                forget(unmappedIDs)
            }
            mergedFolderRedirects[cloudRoot.id] = FolderRef(root: targetRootID, path: targetRelPath)
            onFolderAdded?(folderName, tracks(under: targetRootID, path: targetRelPath).map(\.id))
        } else if createdStandaloneRootID == nil {
            onFolderAdded?(folderName, tracks(under: targetRootID, path: targetRelPath).map(\.id))
        }
    }

    private static func normalizedTrackMatchKey(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .precomposedStringWithCanonicalMapping
            .lowercased()
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private static func buildMergedTrackIDMap(
        cloudTracks: [Track],
        localTracks: [Track],
        targetRelPath: String,
        manifest: CloudFolderManifest?
    ) -> [String: String] {
        guard !cloudTracks.isEmpty, !localTracks.isEmpty else { return [:] }

        func relativeInDestination(for localTrack: Track) -> String {
            guard !targetRelPath.isEmpty else { return localTrack.relativePath }
            let prefix = targetRelPath + "/"
            if localTrack.relativePath.hasPrefix(prefix) {
                return String(localTrack.relativePath.dropFirst(prefix.count))
            }
            return localTrack.fileName
        }

        var byRelExact: [String: Track] = [:]
        var byRelLower: [String: Track] = [:]
        var byFileLower: [String: Track] = [:]
        var byStem: [String: [Track]] = [:]
        var byTitleArtist: [String: [Track]] = [:]

        for lt in localTracks {
            let rel = relativeInDestination(for: lt)
            if byRelExact[rel] == nil { byRelExact[rel] = lt }
            let relLower = rel.lowercased()
            if byRelLower[relLower] == nil { byRelLower[relLower] = lt }
            let fileLower = lt.fileName.lowercased()
            if byFileLower[fileLower] == nil { byFileLower[fileLower] = lt }
            let stem = normalizedTrackMatchKey((lt.fileName as NSString).deletingPathExtension)
            if !stem.isEmpty { byStem[stem, default: []].append(lt) }
            let taKey = "\(normalizedTrackMatchKey(lt.title))\u{1F}\(normalizedTrackMatchKey(lt.artist))"
            byTitleArtist[taKey, default: []].append(lt)
        }

        var map: [String: String] = [:]
        var claimedLocalIDs: Set<String> = []

        func claim(_ candidate: Track?) -> Track? {
            guard let candidate, !claimedLocalIDs.contains(candidate.id) else { return nil }
            claimedLocalIDs.insert(candidate.id)
            return candidate
        }

        for cloudTrack in cloudTracks {
            var matched: Track? = nil

            if let manifest {
                let keys = [cloudTrack.remoteTrackID, cloudTrack.relativePath].compactMap { $0 }
                for key in keys where !key.isEmpty {
                    if let fileName = manifest.tracks[key] {
                        let cleanName = LibraryRoot.normalizeFolderPath(fileName)
                        matched = claim(byRelExact[cleanName])
                            ?? claim(byRelLower[cleanName.lowercased()])
                            ?? claim(byFileLower[(cleanName as NSString).lastPathComponent.lowercased()])
                        if matched != nil { break }
                    }
                }
            }

            if matched == nil {
                let sanitizedTitleStem = normalizedTrackMatchKey(
                    CloudExporter.sanitizeFileName(cloudTrack.title, fallback: cloudTrack.remoteTrackID ?? "")
                )
                let rawFileStem = normalizedTrackMatchKey((cloudTrack.fileName as NSString).deletingPathExtension)
                for stem in [sanitizedTitleStem, rawFileStem] where !stem.isEmpty {
                    if let hit = byStem[stem]?.first(where: { !claimedLocalIDs.contains($0.id) }) {
                        matched = claim(hit)
                        break
                    }
                }
            }

            if matched == nil {
                let taKey = "\(normalizedTrackMatchKey(cloudTrack.title))\u{1F}\(normalizedTrackMatchKey(cloudTrack.artist))"
                if let hit = byTitleArtist[taKey]?.first(where: { !claimedLocalIDs.contains($0.id) }) {
                    matched = claim(hit)
                }
            }

            if let matched, cloudTrack.id != matched.id {
                map[cloudTrack.id] = matched.id
            }
        }

        return map
    }

    func cloudTracking(rootID: UUID, path: String) -> CloudTrackingInfo? {
        guard let root = self.root(rootID) else { return nil }
        let normPath = LibraryRoot.normalizeFolderPath(path)
        if normPath.isEmpty {
            if root.kind.isCloud {
                guard let src = root.sourceURL?.trimmingCharacters(in: .whitespacesAndNewlines), !src.isEmpty else {
                    return nil
                }
                return CloudTrackingInfo(
                    sourceURL: src,
                    remoteID: root.remoteID,
                    cloudKind: root.kind,
                    cookie: root.cookie,
                    lastSynced: root.lastSynced,
                    providerID: root.resolvedProviderID,
                    streamQualityID: root.streamQualityID,
                    downloadQualityID: root.downloadQualityID
                )
            }
            guard root.isCloudTrackedLocal, let src = root.sourceURL else { return nil }
            return CloudTrackingInfo(
                sourceURL: src,
                remoteID: root.remoteID,
                cloudKind: root.linkedCloudKind ?? .cloudExtension,
                cookie: root.cookie,
                lastSynced: root.lastSynced,
                providerID: root.effectiveProviderID,
                streamQualityID: root.streamQualityID,
                downloadQualityID: root.downloadQualityID
            )
        }
        return root.normalizedTrackedSubfolders[normPath]
    }

    func updateCloudTrackingAddress(
        rootID: UUID,
        path: String,
        sourceURL: String,
        cookie: String?,
        preferredProviderID: String? = nil,
        streamQualityID: String? = nil,
        downloadQualityID: String? = nil
    ) throws {
        guard let index = roots.firstIndex(where: { $0.id == rootID }) else {
            throw LibraryError.notAFolder
        }
        let normPath = LibraryRoot.normalizeFolderPath(path)
        let existingTracking = cloudTracking(rootID: rootID, path: normPath)
        let fallbackKind = existingTracking?.cloudKind ?? roots[index].effectiveCloudKind
        let classified = try LiveCloudService.classifySource(
            sourceURL,
            fallbackKind: fallbackKind,
            preferredProviderID: preferredProviderID,
            registry: providerRegistry
        )
        if normPath.isEmpty,
           roots[index].kind.isCloud,
           let remoteID = classified.remoteID,
           !remoteID.isEmpty,
           roots.contains(where: {
               $0.id != rootID
                   && $0.kind == classified.kind
                   && $0.resolvedProviderID == classified.providerID
                   && $0.remoteID == remoteID
           }) {
            throw LibraryError.duplicateRoot
        }

        let targetProvider = classified.providerID.flatMap { providerRegistry.provider(forID: $0) }
        let validQualities = Set((targetProvider?.availableQualities ?? []).map(\.id))
        let trimmedStreamQ = streamQualityID?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanStreamQ = (trimmedStreamQ?.isEmpty == false && validQualities.contains(trimmedStreamQ!)) ? trimmedStreamQ : nil
        let trimmedDownloadQ = downloadQualityID?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDownloadQ = (trimmedDownloadQ?.isEmpty == false && validQualities.contains(trimmedDownloadQ!)) ? trimmedDownloadQ : nil

        let trimmedCookie = cookie?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanCookie = (targetProvider?.supportsCookie == false)
            ? nil
            : ((trimmedCookie?.isEmpty == false) ? trimmedCookie : nil)
        let now = Date()

        if normPath.isEmpty {
            roots[index].sourceURL = classified.extracted
            roots[index].remoteID = classified.remoteID
            roots[index].cookie = cleanCookie
            roots[index].streamQualityID = cleanStreamQ
            roots[index].downloadQualityID = cleanDownloadQ
            roots[index].lastSynced = now
            if roots[index].kind.isLocal {
                roots[index].linkedCloudKind = classified.kind
                roots[index].linkedProviderID = classified.providerID
            } else if roots[index].kind.isCloud {
                roots[index].kind = classified.kind
                roots[index].providerID = classified.providerID
                cloudSyncErrors[rootID] = nil
            }
        } else {
            var map = roots[index].normalizedTrackedSubfolders
            map[normPath] = CloudTrackingInfo(
                sourceURL: classified.extracted,
                remoteID: classified.remoteID,
                cloudKind: classified.kind,
                cookie: cleanCookie,
                lastSynced: now,
                providerID: classified.providerID,
                streamQualityID: cleanStreamQ,
                downloadQualityID: cleanDownloadQ
            )
            roots[index].trackedSubfolders = map
        }
        saveLibrary()

        if roots[index].kind.isLocal, let rootURL = rootURLs[rootID] {
            let folderURL = normPath.isEmpty ? rootURL : rootURL.appendingPathComponent(normPath, isDirectory: true)
            if Self.isDirectory(folderURL) {
                let existingManifest = CloudExporter.loadManifest(in: folderURL)
                let updatedManifest = CloudFolderManifest(
                    sourceURL: classified.extracted,
                    remoteID: classified.remoteID,
                    cloudKind: classified.kind,
                    lastSyncedEpochMs: Int64(now.timeIntervalSince1970 * 1000),
                    tracks: existingManifest?.tracks ?? [:],
                    providerID: classified.providerID,
                    streamQualityID: cleanStreamQ,
                    downloadQualityID: cleanDownloadQ
                )
                try? CloudExporter.saveManifest(updatedManifest, in: folderURL)
            }
        }
    }

    /// Updates a cloud folder's source URL, provider binding, cookie, and quality preferences while keeping `root.id`,
    /// triggering a clean re-sync when the source binding or cookie changed.
    func rebindCloudRoot(
        _ rootID: UUID,
        sourceURL: String,
        cookie: String?,
        preferredProviderID: String?,
        streamQualityID: String? = nil,
        downloadQualityID: String? = nil
    ) async throws {
        guard let oldRoot = root(rootID), oldRoot.kind.isCloud else {
            throw LibraryError.notAFolder
        }
        try updateCloudTrackingAddress(
            rootID: rootID,
            path: "",
            sourceURL: sourceURL,
            cookie: cookie,
            preferredProviderID: preferredProviderID,
            streamQualityID: streamQualityID,
            downloadQualityID: downloadQualityID
        )
        guard let newRoot = root(rootID) else { return }
        let bindingChanged = oldRoot.kind != newRoot.kind
            || oldRoot.resolvedProviderID != newRoot.resolvedProviderID
            || oldRoot.sourceURL != newRoot.sourceURL
            || oldRoot.remoteID != newRoot.remoteID
        let cookieChanged = oldRoot.cookie != newRoot.cookie

        if bindingChanged {
            driveCrawlTasks[rootID]?.cancel()
            driveCrawlTasks[rootID] = nil
            driveCrawlPriorities[rootID] = nil
            cloudSyncProgress[rootID] = nil
            knownDriveSubfolders[rootID] = nil
        }

        if bindingChanged || cookieChanged {
            await rescan()
        }
    }

    func updateTrackedLocalRoot(
        _ rootID: UUID,
        path: String = "",
        cache: CloudAudioCache = .shared,
        onProgress: (@MainActor @Sendable (Int, Int, String) -> Void)? = nil
    ) async throws -> CloudExporter.CloudUpdateSummary {
        try await performUpdateTrackedLocalRoot(
            rootID,
            path: path,
            cache: cache,
            onDetailedProgress: onProgress.map { callback in
                { @MainActor @Sendable progress in
                    callback(progress.completedTracks, progress.totalTracks, progress.currentTrackTitle)
                }
            }
        )
    }

    func updateTrackedLocalRoot(
        _ rootID: UUID,
        path: String = "",
        cache: CloudAudioCache = .shared,
        onDetailedProgress: @escaping @MainActor @Sendable (CloudExporter.ExportProgress) -> Void
    ) async throws -> CloudExporter.CloudUpdateSummary {
        try await performUpdateTrackedLocalRoot(
            rootID,
            path: path,
            cache: cache,
            onDetailedProgress: onDetailedProgress
        )
    }

    private func performUpdateTrackedLocalRoot(
        _ rootID: UUID,
        path: String,
        cache: CloudAudioCache,
        onDetailedProgress: (@MainActor @Sendable (CloudExporter.ExportProgress) -> Void)?
    ) async throws -> CloudExporter.CloudUpdateSummary {
        let normPath = LibraryRoot.normalizeFolderPath(path)
        guard let index = roots.firstIndex(where: { $0.id == rootID }),
              let rootURL = rootURLs[rootID] else {
            throw LibraryError.notAFolder
        }
        guard let tracking = cloudTracking(rootID: rootID, path: normPath) else {
            throw LibraryError.invalidCloudURL
        }
        let folderURL = normPath.isEmpty ? rootURL : rootURL.appendingPathComponent(normPath, isDirectory: true)
        var lookupRoot = roots[index]
        lookupRoot.sourceURL = tracking.sourceURL
        lookupRoot.remoteID = tracking.remoteID
        lookupRoot.linkedCloudKind = tracking.cloudKind
        lookupRoot.linkedProviderID = tracking.resolvedProviderID
        lookupRoot.cookie = tracking.cookie
        lookupRoot.streamQualityID = tracking.streamQualityID
        lookupRoot.downloadQualityID = tracking.downloadQualityID

        let summary: CloudExporter.CloudUpdateSummary
        if let onDetailedProgress {
            summary = try await CloudExporter.updateLocalFolder(
                root: lookupRoot,
                folderURL: folderURL,
                fetcher: cloudFetcher,
                cache: cache,
                artworkCache: artwork,
                sourceURLOverride: tracking.sourceURL,
                cookieOverride: tracking.cookie,
                kindHintOverride: tracking.cloudKind,
                providerIDOverride: tracking.resolvedProviderID,
                onDetailedProgress: onDetailedProgress
            )
        } else {
            summary = try await CloudExporter.updateLocalFolder(
                root: lookupRoot,
                folderURL: folderURL,
                fetcher: cloudFetcher,
                cache: cache,
                artworkCache: artwork,
                sourceURLOverride: tracking.sourceURL,
                cookieOverride: tracking.cookie,
                kindHintOverride: tracking.cloudKind,
                providerIDOverride: tracking.resolvedProviderID,
                onProgress: nil
            )
        }
        if let updatedIdx = roots.firstIndex(where: { $0.id == rootID }) {
            let now = Date()
            if normPath.isEmpty {
                roots[updatedIdx].lastSynced = now
            } else {
                var map = roots[updatedIdx].normalizedTrackedSubfolders
                if var info = map[normPath] {
                    info.lastSynced = now
                    map[normPath] = info
                    roots[updatedIdx].trackedSubfolders = map
                }
            }
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

    private func isDuplicateCloudRoot(kind: LibraryRoot.Kind, providerID: String?, remoteID: String?) -> Bool {
        guard let remoteID, !remoteID.isEmpty else { return false }
        return roots.contains { existing in
            existing.kind == kind
                && existing.resolvedProviderID == providerID
                && existing.remoteID == remoteID
        }
    }

    private func isProgressiveTreeRoot(_ root: LibraryRoot) -> Bool {
        if let treeFetcher = cloudFetcher as? any CloudTreeProgressiveFetching,
           let pid = root.resolvedProviderID,
           treeFetcher.supportsProgressiveTree(for: pid) {
            return true
        }
        return root.kind == .cloudGoogleDrive && (cloudFetcher as? any GoogleDriveProgressiveFetching) != nil
    }

    func prioritizeCloudFolder(rootID: UUID, path: String) {
        let normPath = LibraryRoot.normalizeFolderPath(path)
        guard !normPath.isEmpty else { return }
        if let priority = driveCrawlPriorities[rootID] {
            priority.prioritize(normPath)
            return
        }
        guard driveCrawlTasks[rootID] == nil,
              let sub = knownDriveSubfolders[rootID]?[normPath],
              !sub.isLoaded,
              let root = self.root(rootID),
              isProgressiveTreeRoot(root) else {
            return
        }
        let taskKey = Self.folderKey(rootID: rootID, path: normPath)
        guard onDemandDriveFolderTasks[taskKey] == nil else { return }
        let treeFetcher = cloudFetcher as? any CloudTreeProgressiveFetching
        let legacyDriveFetcher = cloudFetcher as? any GoogleDriveProgressiveFetching
        onDemandDriveFolderTasks[taskKey] = Task { @MainActor [weak self] in
            defer { self?.onDemandDriveFolderTasks[taskKey] = nil }
            guard let self else { return }
            do {
                let batch: DriveFolderBatch
                if let treeFetcher {
                    batch = try await treeFetcher.fetchSingleTreeFolder(
                        providerID: root.resolvedProviderID,
                        folderID: sub.id,
                        resourceKey: sub.resourceKey,
                        pathSegments: sub.pathSegments,
                        depth: sub.depth,
                        rootTitle: root.name,
                        cookie: root.cookie,
                        extraJSON: sub.extraJSON
                    )
                } else if let legacyDriveFetcher, root.kind == .cloudGoogleDrive {
                    batch = try await legacyDriveFetcher.fetchGoogleDriveSingleFolder(
                        folderID: sub.id,
                        resourceKey: sub.resourceKey,
                        pathSegments: sub.pathSegments,
                        depth: sub.depth,
                        rootTitle: root.name,
                        cookie: root.cookie
                    )
                } else {
                    return
                }
                guard !Task.isCancelled, let activeRoot = self.root(rootID) else { return }
                self.recordDriveBatchMetadata(root: activeRoot, batch: batch)
                if self.mergeIncrementalCloudBatch(rootID: rootID, items: batch.tracks, now: Date()) {
                    await self.populateMissingCloudArtwork(forRoot: rootID)
                }
            } catch {
                // On-demand prioritization is best-effort; full rescan will report persistent errors.
            }
        }
    }

    func addCloudRoot(sourceURL: String, cookie: String? = nil, preferredProviderID: String? = nil) async throws {
        let rawInput = sourceURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedURL = CloudSourceInput.extract(from: rawInput, registry: providerRegistry)
        guard !trimmedURL.isEmpty else { throw LibraryError.invalidCloudURL }
        let trimmedCookie = cookie?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanCookie = (trimmedCookie?.isEmpty == false) ? trimmedCookie : nil

        let targetProvider: (any CloudSourceProvider)?
        let fetchSourceURL: String
        if let preferredProviderID, !preferredProviderID.isEmpty {
            guard let explicit = providerRegistry.provider(forID: preferredProviderID) else {
                if preferredProviderID.hasPrefix("ext.") {
                    throw CloudSourceError.extensionUnavailable(name: preferredProviderID)
                }
                throw LibraryError.invalidCloudURL
            }
            targetProvider = explicit
            let matched = explicit.canHandle(rawText: rawInput, extractedURL: trimmedURL)
            fetchSourceURL = (explicit.rootKind == .cloudExtension ? matched?.normalizedURL : nil) ?? trimmedURL
        } else if let match = providerRegistry.match(rawText: rawInput, extractedURL: trimmedURL) {
            targetProvider = providerRegistry.provider(forID: match.providerID)
            fetchSourceURL = match.kind == .cloudExtension ? match.normalizedURL : trimmedURL
        } else {
            targetProvider = nil
            fetchSourceURL = trimmedURL
        }

        let treeFetcher = cloudFetcher as? any CloudTreeProgressiveFetching
        let legacyDriveFetcher = cloudFetcher as? any GoogleDriveProgressiveFetching
        let targetPID = targetProvider?.id ?? preferredProviderID
        let shouldUseProgressiveTree: Bool = {
            if let treeFetcher, let targetPID, treeFetcher.supportsProgressiveTree(for: targetPID) {
                return true
            }
            return targetProvider?.rootKind == .cloudGoogleDrive && legacyDriveFetcher != nil
        }()

        if shouldUseProgressiveTree {
            let discovery: DriveFolderDiscovery
            if let treeFetcher, let targetPID, treeFetcher.supportsProgressiveTree(for: targetPID) {
                discovery = try await treeFetcher.discoverTreeRoot(sourceURL: fetchSourceURL, cookie: cleanCookie, providerID: targetPID)
            } else if let legacyDriveFetcher {
                discovery = try await legacyDriveFetcher.discoverGoogleDriveRoot(sourceURL: fetchSourceURL, cookie: cleanCookie)
            } else {
                throw CloudSourceError.unsupportedSource
            }

            let resolvedPID = discovery.providerID ?? targetPID ?? discovery.kind.builtInProviderID
            guard !isDuplicateCloudRoot(kind: discovery.kind, providerID: resolvedPID, remoteID: discovery.rootID) else {
                throw LibraryError.duplicateRoot
            }

            if let single = discovery.singleFileResult {
                let singlePID = single.providerID ?? resolvedPID
                let now = Date()
                let root = LibraryRoot(
                    id: UUID(),
                    name: single.title,
                    kind: single.kind,
                    bookmark: nil,
                    sourceURL: trimmedURL,
                    remoteID: single.remoteID,
                    cookie: cleanCookie,
                    proxyURL: nil,
                    lastSynced: now,
                    providerID: singlePID
                )
                let (newTracks, _, _) = Self.mergeCloudTracks(root: root, items: single.tracks, existing: [:], now: now)
                roots.append(root)
                cloudSyncErrors[root.id] = nil
                publish(tracks + newTracks)
                onFolderAdded?(root.name, tracks(under: root.id, path: "").map(\.id))
                await populateMissingCloudArtwork(forRoot: root.id)
                return
            }

            guard !(discovery.initialBatch.tracks.isEmpty && discovery.initialBatch.discoveredSubfolders.isEmpty) else {
                if discovery.kind == .cloudGoogleDrive {
                    throw CloudSourceError.apiError(L10n.trNonisolated("No supported audio files were found in this Google Drive folder."))
                }
                throw CloudSourceError.emptyPlaylist
            }

            let now = Date()
            let root = LibraryRoot(
                id: UUID(),
                name: discovery.rootTitle,
                kind: discovery.kind,
                bookmark: nil,
                sourceURL: trimmedURL,
                remoteID: discovery.rootID,
                cookie: cleanCookie,
                proxyURL: nil,
                lastSynced: now,
                providerID: resolvedPID
            )
            roots.append(root)
            cloudSyncErrors[root.id] = nil
            knownDriveSubfolders[root.id] = [:]
            recordDriveBatchMetadata(root: root, batch: discovery.initialBatch)

            let (initialTracks, _, _) = Self.mergeCloudTracks(root: root, items: discovery.initialBatch.tracks, existing: [:], now: now)
            publish(tracks + initialTracks)
            onFolderAdded?(root.name, tracks(under: root.id, path: "").map(\.id))

            if !discovery.initialBatch.discoveredSubfolders.isEmpty {
                let priorityState = DriveCrawlPriorityState()
                driveCrawlPriorities[root.id] = priorityState
                cloudSyncProgress[root.id] = CloudSyncProgress(
                    foldersDone: 1,
                    foldersTotal: 1 + (knownDriveSubfolders[root.id]?.count ?? 0),
                    tracksLoaded: initialTracks.count
                )
                let rootID = root.id
                driveCrawlTasks[rootID] = Task { @MainActor [weak self] in
                    guard let self else { return }
                    await self.runDriveCrawl(
                        rootID: rootID,
                        providerID: resolvedPID,
                        discovery: discovery,
                        cookie: cleanCookie,
                        treeFetcher: treeFetcher,
                        legacyDriveFetcher: legacyDriveFetcher,
                        priorityState: priorityState,
                        pruneDeletedOnSuccess: false,
                        notifyFolderQueueOnComplete: true
                    )
                }
            }
            return
        }

        let fetched: CloudPlaylistResult
        if let providerAware = cloudFetcher as? any CloudProviderPlaylistFetching {
            fetched = try await providerAware.fetchPlaylist(
                sourceURL: fetchSourceURL,
                cookie: cleanCookie,
                kindHint: targetProvider?.rootKind,
                providerIDHint: targetPID
            )
        } else {
            fetched = try await cloudFetcher.fetchPlaylist(
                sourceURL: trimmedURL,
                cookie: cleanCookie,
                kindHint: targetProvider?.rootKind
            )
        }

        let resolvedPID = fetched.providerID ?? targetPID ?? fetched.kind.builtInProviderID
        guard !isDuplicateCloudRoot(kind: fetched.kind, providerID: resolvedPID, remoteID: fetched.remoteID) else {
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
            lastSynced: now,
            providerID: resolvedPID
        )
        let (newTracks, _, _) = Self.mergeCloudTracks(root: root, items: fetched.tracks, existing: [:], now: now)
        roots.append(root)
        cloudSyncErrors[root.id] = nil
        publish(tracks + newTracks)
        onFolderAdded?(root.name, tracks(under: root.id, path: "").map(\.id))
        await populateMissingCloudArtwork(forRoot: root.id)
    }

    func removeRoot(_ id: UUID) {
        guard let index = roots.firstIndex(where: { $0.id == id }) else { return }
        driveCrawlTasks[id]?.cancel()
        driveCrawlTasks[id] = nil
        driveCrawlPriorities[id] = nil
        cloudSyncProgress[id] = nil
        knownDriveSubfolders[id] = nil
        let keyPrefix = "\(id.uuidString)::"
        for key in onDemandDriveFolderTasks.keys where key.hasPrefix(keyPrefix) {
            onDemandDriveFolderTasks[key]?.cancel()
            onDemandDriveFolderTasks[key] = nil
        }

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

    func displayLocation(for track: Track) -> String {
        if track.isEphemeral {
            return L10n.tr("Cloud Search")
        }
        if let fileURL = url(for: track) {
            return fileURL.path
        }
        if let root = self.root(track.rootID) {
            let rootName = L10n.displayRootName(root)
            if track.relativePath.isEmpty {
                return rootName
            }
            return track.isCloud ? "\(rootName) / \(track.relativePath)" : "\(rootName)/\(track.relativePath)"
        }
        return track.relativePath
    }

    func displayLocation(forRoot rootID: UUID, path: String) -> String {
        let normPath = LibraryRoot.normalizeFolderPath(path)
        if let rootURL = rootURLs[rootID] {
            let targetURL = normPath.isEmpty ? rootURL : rootURL.appendingPathComponent(normPath, isDirectory: true)
            return targetURL.path
        }
        if let root = self.root(rootID) {
            let rootName = L10n.displayRootName(root)
            if normPath.isEmpty {
                return rootName
            }
            return root.kind.isCloud ? "\(rootName) / \(normPath)" : "\(rootName)/\(normPath)"
        }
        return normPath
    }

    /// Removes a single song from the library by excluding its relative path so rescans will not re-add it.
    func removeTrackFromLibrary(_ trackID: String) {
        guard let track = byID[trackID] else { return }
        let normRel = LibraryRoot.normalizeFolderPath(track.relativePath)
        if !normRel.isEmpty, let rootIndex = roots.firstIndex(where: { $0.id == track.rootID }) {
            let current = roots[rootIndex].normalizedExcludedPaths.map(LibraryRoot.normalizeFolderPath).filter { !$0.isEmpty }
            let alreadyCovered = current.contains { ex in normRel == ex || normRel.hasPrefix(ex + "/") }
            if !alreadyCovered {
                var next = current
                next.append(normRel)
                next.sort()
                roots[rootIndex].excludedPaths = next
            }
        }
        tracks.removeAll { $0.id == trackID }
        forget([trackID])
        saveLibrary()
        let keys = Set(tracks.compactMap(\.artworkKey))
        let artwork = self.artwork
        Task.detached(priority: .utility) { artwork.prune(keeping: keys) }
    }

    private static func trashOrRemoveItem(at url: URL) throws {
        let fm = FileManager.default
        #if targetEnvironment(macCatalyst)
        try fm.trashItem(at: url, resultingItemURL: nil)
        #else
        do {
            try fm.trashItem(at: url, resultingItemURL: nil)
        } catch {
            try fm.removeItem(at: url)
        }
        #endif
    }

    /// Deletes a local non-ephemeral track's file from disk (Trash with iOS fallback) and removes it from the library.
    func deleteTrackFile(_ trackID: String) throws {
        guard let track = byID[trackID], !track.isCloud, !track.isEphemeral else {
            throw LibraryError.cannotDeleteCloudItem
        }
        guard let fileURL = url(for: track), FileManager.default.fileExists(atPath: fileURL.path) else {
            throw LibraryError.fileNotFoundOnDisk
        }
        onBeforeDeletingTracks?([trackID])
        try Self.trashOrRemoveItem(at: fileURL)

        tracks.removeAll { $0.id == trackID }
        forget([trackID])
        saveLibrary()
        let keys = Set(tracks.compactMap(\.artworkKey))
        let artwork = self.artwork
        Task.detached(priority: .utility) { artwork.prune(keeping: keys) }
    }

    /// Deletes a local folder (picked root or subfolder) from disk (Trash with iOS fallback) and removes affected library state.
    func deleteFolderFiles(rootID: UUID, path: String) throws {
        let normPath = LibraryRoot.normalizeFolderPath(path)
        guard let rootIndex = roots.firstIndex(where: { $0.id == rootID }) else {
            throw LibraryError.notAFolder
        }
        let targetRoot = roots[rootIndex]
        guard targetRoot.kind.isLocal else {
            throw LibraryError.cannotDeleteCloudItem
        }
        if targetRoot.kind == .appFolder && normPath.isEmpty {
            throw LibraryError.cannotDeleteAppFolderRoot
        }
        guard let rootURL = rootURLs[rootID] else {
            throw LibraryError.fileNotFoundOnDisk
        }
        let targetURL = normPath.isEmpty ? rootURL : rootURL.appendingPathComponent(normPath, isDirectory: true)
        guard FileManager.default.fileExists(atPath: targetURL.path) else {
            throw LibraryError.fileNotFoundOnDisk
        }

        let removedIDs: Set<String>
        if normPath.isEmpty {
            removedIDs = Set(tracks.filter { $0.rootID == rootID }.map(\.id))
        } else {
            removedIDs = Set(tracks.filter {
                $0.rootID == rootID && LibraryRoot.folder(normPath, contains: $0.relativePath)
            }.map(\.id))
        }

        onBeforeDeletingTracks?(removedIDs)
        try Self.trashOrRemoveItem(at: targetURL)

        if normPath.isEmpty {
            driveCrawlTasks[rootID]?.cancel()
            driveCrawlTasks[rootID] = nil
            driveCrawlPriorities[rootID] = nil
            cloudSyncProgress[rootID] = nil
            knownDriveSubfolders[rootID] = nil
            let keyPrefix = "\(rootID.uuidString)::"
            for key in onDemandDriveFolderTasks.keys where key.hasPrefix(keyPrefix) {
                onDemandDriveFolderTasks[key]?.cancel()
                onDemandDriveFolderTasks[key] = nil
            }
            if targetRoot.kind == .picked {
                rootURLs[rootID]?.stopAccessingSecurityScopedResource()
            }
            rootURLs[rootID] = nil
            cloudSyncErrors[rootID] = nil
            roots.remove(at: rootIndex)
            tracks.removeAll { $0.rootID == rootID }
            for key in folderOrders.keys where key.hasPrefix(keyPrefix) {
                folderOrders.removeValue(forKey: key)
            }
        } else {
            if !removedIDs.isEmpty {
                tracks.removeAll { removedIDs.contains($0.id) }
            }
            if rootID == LibraryRoot.appFolderID && !normPath.contains("/") {
                knownAppFolders.remove(normPath)
            }
            if var tracked = roots[rootIndex].trackedSubfolders, !tracked.isEmpty {
                for key in tracked.keys where LibraryRoot.folder(normPath, contains: key) {
                    tracked.removeValue(forKey: key)
                }
                roots[rootIndex].trackedSubfolders = tracked.isEmpty ? nil : tracked
            }
            if var excluded = roots[rootIndex].excludedPaths, !excluded.isEmpty {
                excluded.removeAll { LibraryRoot.folder(normPath, contains: $0) }
                roots[rootIndex].excludedPaths = excluded.isEmpty ? nil : excluded
            }
            let prefixKey = Self.folderKey(rootID: rootID, path: normPath)
            for key in folderOrders.keys where key == prefixKey || key.hasPrefix(prefixKey + "/") {
                folderOrders.removeValue(forKey: key)
            }
        }

        forget(removedIDs)
        saveLibrary()
        let keys = Set(tracks.compactMap(\.artworkKey))
        let artwork = self.artwork
        Task.detached(priority: .utility) { artwork.prune(keeping: keys) }
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
        await rescan(skippingCloudRootID: nil)
    }

    private func rescan(skippingCloudRootID: UUID?) async {
        if isScanning {
            rescanRequested = true
            while isScanning { try? await Task.sleep(nanoseconds: 50_000_000) }
            return
        }
        isScanning = true
        repeat {
            rescanRequested = false
            await scanOnce(skippingCloudRootID: skippingCloudRootID)
        } while rescanRequested
        isScanning = false
    }

    private func sortedByTitle(_ list: [Track]) -> [Track] {
        list.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }

    private func scanOnce(skippingCloudRootID: UUID? = nil) async {
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
        var manifestChanged = false
        var progressiveDriveRoots: [LibraryRoot] = []
        let treeFetcher = cloudFetcher as? any CloudTreeProgressiveFetching
        let progressiveDriveFetcher = cloudFetcher as? any GoogleDriveProgressiveFetching
        let providerAwareFetcher = cloudFetcher as? any CloudProviderPlaylistFetching
        let reread = rereadAll
        let syncNow = Date()

        for root in roots {
            let existingTracks = tracks.filter { $0.rootID == root.id }
            if let skippingCloudRootID, root.id == skippingCloudRootID {
                kept.append(contentsOf: existingTracks)
                continue
            }
            if (root.kind == .cloudExtension || root.kind.isLegacyExtensionBacked),
               cloudExtensionAvailability(for: root) != .available {
                // Non-destructive: keep tracks, playlists, stats, and cached audio intact while disabled or missing.
                cloudSyncErrors[root.id] = nil
                kept.append(contentsOf: existingTracks)
                continue
            }
            if isProgressiveTreeRoot(root) {
                progressiveDriveRoots.append(root)
                kept.append(contentsOf: existingTracks)
                continue
            }
            if root.kind.isCloud {
                do {
                    let source = root.sourceURL ?? root.remoteID ?? ""
                    let fetched: CloudPlaylistResult
                    if let providerAwareFetcher {
                        fetched = try await providerAwareFetcher.fetchPlaylist(
                            sourceURL: source,
                            cookie: root.cookie,
                            kindHint: root.kind,
                            providerIDHint: root.resolvedProviderID
                        )
                    } else {
                        fetched = try await cloudFetcher.fetchPlaylist(sourceURL: source, cookie: root.cookie, kindHint: root.kind)
                    }
                    // A root removed while fetching must not resurrect.
                    guard let rootIndex = roots.firstIndex(where: { $0.id == root.id }) else { continue }
                    cloudSyncErrors[root.id] = nil
                    roots[rootIndex].lastSynced = syncNow
                    if roots[rootIndex].name.isEmpty, !fetched.title.isEmpty {
                        roots[rootIndex].name = fetched.title
                    }
                    let existing = Dictionary(existingTracks.map { ($0.relativePath, $0) }, uniquingKeysWith: { first, _ in first })
                    let (merged, didChange, migratedIDs) = Self.mergeCloudTracks(root: roots[rootIndex], items: fetched.tracks, existing: existing, now: syncNow)
                    remapTrackIDs(migratedIDs)
                    let seen = Set(merged.map(\.relativePath))
                    let dropped = existing.filter { !seen.contains($0.key) && migratedIDs[$0.value.id] == nil }.map(\.value.id)
                    removed.formUnion(dropped)
                    if didChange || !dropped.isEmpty || !migratedIDs.isEmpty { cloudChanged = true }
                    kept.append(contentsOf: merged)
                } catch {
                    cloudSyncErrors[root.id] = error.localizedDescription
                    kept.append(contentsOf: existingTracks)
                }
                continue
            }

            guard let rootURL = rootURLs[root.id] else { continue }
            let (scanned, manifests) = await Task.detached(priority: .userInitiated) {
                (FolderScanner.scan(root: rootURL), FolderScanner.scanCloudManifests(root: rootURL))
            }.value
            // A folder that cannot be listed, or a cloud/external folder that suddenly lists nothing,
            // is treated as temporarily unreadable: keep what we know rather than purge playlists.
            guard let files = scanned, !(files.isEmpty && !existingTracks.isEmpty && root.kind == .picked) else {
                kept.append(contentsOf: existingTracks)
                continue
            }
            if let rootIndex = roots.firstIndex(where: { $0.id == root.id }),
               reconcileLocalManifests(rootAt: rootIndex, rootURL: rootURL, manifests: manifests) {
                manifestChanged = true
            }
            let activeRoot = roots.first { $0.id == root.id } ?? root
            let existing = Dictionary(existingTracks.map { ($0.relativePath, $0) }, uniquingKeysWith: { first, _ in first })
            var seen = Set<String>()
            for file in files {
                if activeRoot.isExcluded(file.relativePath) { continue }
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
                    pending.append((activeRoot, rootURL, file, existing[file.relativePath]))
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
        } else if reread || manifestChanged {
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

        if treeFetcher != nil || progressiveDriveFetcher != nil {
            for driveRoot in progressiveDriveRoots {
                guard roots.contains(where: { $0.id == driveRoot.id }) else { continue }
                await rescanProgressiveDriveRoot(
                    driveRoot,
                    treeFetcher: treeFetcher,
                    legacyDriveFetcher: progressiveDriveFetcher,
                    syncNow: syncNow
                )
            }
        }

        await populateMissingCloudArtwork(forRoot: nil)
    }

    private func reconcileLocalManifests(
        rootAt rootIndex: Int,
        rootURL: URL,
        manifests: [String: CloudFolderManifest]
    ) -> Bool {
        var changed = false

        if let rootManifest = manifests[""] {
            let manifestDate = Date(timeIntervalSince1970: Double(rootManifest.lastSyncedEpochMs) / 1000.0)
            let shouldAdoptRoot: Bool
            if !roots[rootIndex].isCloudTrackedLocal {
                shouldAdoptRoot = true
            } else if let currentLast = roots[rootIndex].lastSynced {
                shouldAdoptRoot = manifestDate > currentLast.addingTimeInterval(0.001)
            } else {
                shouldAdoptRoot = true
            }
            if shouldAdoptRoot {
                if roots[rootIndex].sourceURL != rootManifest.sourceURL
                    || roots[rootIndex].remoteID != rootManifest.remoteID
                    || roots[rootIndex].linkedCloudKind != rootManifest.cloudKind
                    || roots[rootIndex].linkedProviderID != rootManifest.resolvedProviderID
                    || roots[rootIndex].streamQualityID != rootManifest.streamQualityID
                    || roots[rootIndex].downloadQualityID != rootManifest.downloadQualityID
                    || roots[rootIndex].lastSynced != manifestDate {
                    roots[rootIndex].sourceURL = rootManifest.sourceURL
                    roots[rootIndex].remoteID = rootManifest.remoteID
                    roots[rootIndex].linkedCloudKind = rootManifest.cloudKind
                    roots[rootIndex].linkedProviderID = rootManifest.resolvedProviderID
                    roots[rootIndex].streamQualityID = rootManifest.streamQualityID
                    roots[rootIndex].downloadQualityID = rootManifest.downloadQualityID
                    roots[rootIndex].lastSynced = manifestDate
                    changed = true
                }
            }
        }

        let currentSubfolders = roots[rootIndex].normalizedTrackedSubfolders
        var updatedSubfolders: [String: CloudTrackingInfo] = [:]

        for (path, existingInfo) in currentSubfolders {
            guard !roots[rootIndex].isExcluded(path) else { continue }
            let subfolderURL = rootURL.appendingPathComponent(path, isDirectory: true)
            guard Self.isDirectory(subfolderURL) else {
                // Prune tracking entries whose folder no longer exists on disk.
                continue
            }
            if let manifest = manifests[path] {
                let manifestDate = Date(timeIntervalSince1970: Double(manifest.lastSyncedEpochMs) / 1000.0)
                if existingInfo.lastSynced == nil || manifestDate > existingInfo.lastSynced!.addingTimeInterval(0.001) {
                    updatedSubfolders[path] = CloudTrackingInfo(
                        sourceURL: manifest.sourceURL,
                        remoteID: manifest.remoteID,
                        cloudKind: manifest.cloudKind,
                        cookie: existingInfo.cookie,
                        lastSynced: manifestDate,
                        providerID: manifest.resolvedProviderID,
                        streamQualityID: manifest.streamQualityID ?? existingInfo.streamQualityID,
                        downloadQualityID: manifest.downloadQualityID ?? existingInfo.downloadQualityID
                    )
                } else {
                    updatedSubfolders[path] = existingInfo
                }
            } else {
                updatedSubfolders[path] = existingInfo
            }
        }

        for (path, manifest) in manifests where !path.isEmpty {
            guard !roots[rootIndex].isExcluded(path),
                  updatedSubfolders[path] == nil else { continue }
            let subfolderURL = rootURL.appendingPathComponent(path, isDirectory: true)
            guard Self.isDirectory(subfolderURL) else { continue }
            updatedSubfolders[path] = CloudTrackingInfo(
                sourceURL: manifest.sourceURL,
                remoteID: manifest.remoteID,
                cloudKind: manifest.cloudKind,
                cookie: nil,
                lastSynced: Date(timeIntervalSince1970: Double(manifest.lastSyncedEpochMs) / 1000.0),
                providerID: manifest.resolvedProviderID,
                streamQualityID: manifest.streamQualityID,
                downloadQualityID: manifest.downloadQualityID
            )
        }

        if updatedSubfolders != currentSubfolders {
            roots[rootIndex].trackedSubfolders = updatedSubfolders.isEmpty ? nil : updatedSubfolders
            changed = true
        }
        return changed
    }

    private func rescanProgressiveDriveRoot(
        _ rootSnapshot: LibraryRoot,
        treeFetcher: (any CloudTreeProgressiveFetching)?,
        legacyDriveFetcher: (any GoogleDriveProgressiveFetching)?,
        syncNow: Date
    ) async {
        driveCrawlTasks[rootSnapshot.id]?.cancel()
        driveCrawlTasks[rootSnapshot.id] = nil
        driveCrawlPriorities[rootSnapshot.id] = nil
        cloudSyncProgress[rootSnapshot.id] = nil

        let source = rootSnapshot.sourceURL ?? rootSnapshot.remoteID ?? ""
        do {
            let discovery: DriveFolderDiscovery
            if let treeFetcher, let pid = rootSnapshot.resolvedProviderID, treeFetcher.supportsProgressiveTree(for: pid) {
                discovery = try await treeFetcher.discoverTreeRoot(sourceURL: source, cookie: rootSnapshot.cookie, providerID: pid)
            } else if let legacyDriveFetcher, rootSnapshot.kind == .cloudGoogleDrive {
                discovery = try await legacyDriveFetcher.discoverGoogleDriveRoot(sourceURL: source, cookie: rootSnapshot.cookie)
            } else {
                return
            }
            guard let rootIndex = roots.firstIndex(where: { $0.id == rootSnapshot.id }) else { return }
            cloudSyncErrors[rootSnapshot.id] = nil
            if roots[rootIndex].name.isEmpty, !discovery.rootTitle.isEmpty {
                roots[rootIndex].name = discovery.rootTitle
            }

            if let single = discovery.singleFileResult {
                roots[rootIndex].lastSynced = syncNow
                knownDriveSubfolders[rootSnapshot.id] = nil
                let existingTracks = tracks.filter { $0.rootID == rootSnapshot.id }
                let existing = Dictionary(existingTracks.map { ($0.relativePath, $0) }, uniquingKeysWith: { first, _ in first })
                let (merged, didChange, migratedIDs) = Self.mergeCloudTracks(root: roots[rootIndex], items: single.tracks, existing: existing, now: syncNow)
                remapTrackIDs(migratedIDs)
                let seen = Set(merged.map(\.relativePath))
                let dropped = Set(existing.filter { !seen.contains($0.key) && migratedIDs[$0.value.id] == nil }.map(\.value.id))
                if didChange || !dropped.isEmpty || !migratedIDs.isEmpty {
                    let otherTracks = tracks.filter { $0.rootID != rootSnapshot.id }
                    publish(otherTracks + merged)
                    forget(dropped)
                } else {
                    saveLibrary()
                }
                return
            }

            knownDriveSubfolders[rootSnapshot.id] = [:]
            recordDriveBatchMetadata(root: roots[rootIndex], batch: discovery.initialBatch)
            _ = mergeIncrementalCloudBatch(rootID: rootSnapshot.id, items: discovery.initialBatch.tracks, now: syncNow)

            if discovery.initialBatch.discoveredSubfolders.isEmpty {
                if !discovery.initialBatch.hadError,
                   let idx = roots.firstIndex(where: { $0.id == rootSnapshot.id }) {
                    roots[idx].lastSynced = Date()
                    let keeping = Set(discovery.initialBatch.tracks.map(\.relativePath))
                    pruneTracks(forRoot: rootSnapshot.id, keepingPaths: keeping)
                }
                return
            }

            let priorityState = DriveCrawlPriorityState()
            driveCrawlPriorities[rootSnapshot.id] = priorityState
            cloudSyncProgress[rootSnapshot.id] = CloudSyncProgress(
                foldersDone: 1,
                foldersTotal: 1 + (knownDriveSubfolders[rootSnapshot.id]?.count ?? 0),
                tracksLoaded: tracks.lazy.filter { $0.rootID == rootSnapshot.id }.count
            )
            let rootID = rootSnapshot.id
            let providerID = discovery.providerID ?? rootSnapshot.resolvedProviderID
            let task = Task { @MainActor [weak self] in
                guard let self else { return }
                await self.runDriveCrawl(
                    rootID: rootID,
                    providerID: providerID,
                    discovery: discovery,
                    cookie: rootSnapshot.cookie,
                    treeFetcher: treeFetcher,
                    legacyDriveFetcher: legacyDriveFetcher,
                    priorityState: priorityState,
                    pruneDeletedOnSuccess: true,
                    notifyFolderQueueOnComplete: false
                )
            }
            driveCrawlTasks[rootID] = task
            await task.value
        } catch is CancellationError {
            cloudSyncProgress[rootSnapshot.id] = nil
        } catch {
            cloudSyncErrors[rootSnapshot.id] = error.localizedDescription
            cloudSyncProgress[rootSnapshot.id] = nil
        }
    }

    private func runDriveCrawl(
        rootID: UUID,
        providerID: String?,
        discovery: DriveFolderDiscovery,
        cookie: String?,
        treeFetcher: (any CloudTreeProgressiveFetching)?,
        legacyDriveFetcher: (any GoogleDriveProgressiveFetching)?,
        priorityState: DriveCrawlPriorityState,
        pruneDeletedOnSuccess: Bool,
        notifyFolderQueueOnComplete: Bool
    ) async {
        defer {
            driveCrawlTasks[rootID] = nil
            driveCrawlPriorities[rootID] = nil
            cloudSyncProgress[rootID] = nil
        }

        @MainActor
        final class DriveCrawlTracker {
            var crawledPaths: Set<String>
            var foldersDone = 1

            init(initialPaths: [String]) {
                self.crawledPaths = Set(initialPaths)
            }
        }

        let initialCount = tracks.lazy.filter { $0.rootID == rootID }.count
        let tracker = DriveCrawlTracker(initialPaths: discovery.initialBatch.tracks.map(\.relativePath))

        do {
            let batchHandler: @Sendable (DriveFolderBatch) async -> Void = { @Sendable [weak self] batch in
                await MainActor.run {
                    guard let store = self, !Task.isCancelled, let activeRoot = store.root(rootID) else { return }
                    tracker.foldersDone += 1
                    store.recordDriveBatchMetadata(root: activeRoot, batch: batch)
                    for item in batch.tracks {
                        tracker.crawledPaths.insert(item.relativePath)
                    }
                    _ = store.mergeIncrementalCloudBatch(rootID: rootID, items: batch.tracks, now: Date())
                    let totalFolders = max(tracker.foldersDone, 1 + (store.knownDriveSubfolders[rootID]?.count ?? 0))
                    let loadedCount = store.tracks.lazy.filter { $0.rootID == rootID }.count
                    store.cloudSyncProgress[rootID] = CloudSyncProgress(
                        foldersDone: tracker.foldersDone,
                        foldersTotal: totalFolders,
                        tracksLoaded: loadedCount
                    )
                }
            }

            let summary: DriveCrawlSummary
            if let treeFetcher {
                summary = try await treeFetcher.crawlTree(
                    discovery: discovery,
                    providerID: providerID,
                    cookie: cookie,
                    priorityState: priorityState,
                    onBatch: batchHandler
                )
            } else if let legacyDriveFetcher {
                summary = try await legacyDriveFetcher.crawlGoogleDriveTree(
                    discovery: discovery,
                    cookie: cookie,
                    priorityState: priorityState,
                    onBatch: batchHandler
                )
            } else {
                return
            }

            guard !Task.isCancelled, let rootIndex = roots.firstIndex(where: { $0.id == rootID }) else { return }
            let fullCrawlSucceeded = !summary.hadErrors && !summary.wasTruncated && !Task.isCancelled
            if fullCrawlSucceeded {
                roots[rootIndex].lastSynced = Date()
                if pruneDeletedOnSuccess {
                    pruneTracks(forRoot: rootID, keepingPaths: tracker.crawledPaths)
                } else {
                    saveLibrary()
                }
            } else {
                saveLibrary()
            }

            let finalCount = tracks.lazy.filter { $0.rootID == rootID }.count
            if notifyFolderQueueOnComplete, finalCount > initialCount {
                onFolderAdded?(roots[rootIndex].name, tracks(under: rootID, path: "").map(\.id))
            }
            await populateMissingCloudArtwork(forRoot: rootID)
        } catch {
            // Cancelled or failed mid-crawl: keep tracks discovered so far without pruning.
        }
    }

    private func recordDriveBatchMetadata(root: LibraryRoot, batch: DriveFolderBatch) {
        var map = knownDriveSubfolders[root.id] ?? [:]
        let normFolderPath = LibraryRoot.normalizeFolderPath(batch.folderPath)
        if !normFolderPath.isEmpty, var current = map[normFolderPath] {
            current.isLoaded = true
            map[normFolderPath] = current
        }
        var knownIDs = Set(map.values.map(\.id))
        if let rootRemoteID = root.remoteID, !rootRemoteID.isEmpty {
            knownIDs.insert(rootRemoteID)
        }
        for sub in batch.discoveredSubfolders {
            let normSub = LibraryRoot.normalizeFolderPath(sub.relativePath)
            guard !normSub.isEmpty, !root.isExcluded(normSub) else { continue }
            guard knownIDs.insert(sub.id).inserted else { continue }
            if map[normSub] == nil {
                map[normSub] = KnownDriveSubfolder(
                    id: sub.id,
                    resourceKey: sub.resourceKey,
                    pathSegments: sub.pathSegments,
                    depth: sub.depth,
                    isLoaded: false,
                    extraJSON: sub.extraJSON
                )
            }
        }
        knownDriveSubfolders[root.id] = map
    }

    @discardableResult
    private func mergeIncrementalCloudBatch(rootID: UUID, items: [CloudTrackInfo], now: Date) -> Bool {
        guard !items.isEmpty, let activeRoot = self.root(rootID) else { return false }
        let existingRootTracks = tracks.filter { $0.rootID == rootID }
        let existingByPath = Dictionary(existingRootTracks.map { ($0.relativePath, $0) }, uniquingKeysWith: { first, _ in first })
        let (mergedBatch, changed, migratedIDs, consumedOldIDs) = Self.mergeCloudTracksDetailed(
            root: activeRoot,
            items: items,
            existing: existingByPath,
            now: now
        )
        guard changed || !migratedIDs.isEmpty else { return false }
        remapTrackIDs(migratedIDs)
        let otherTracks = tracks.filter { $0.rootID != rootID }
        let remainingRootTracks = existingRootTracks.filter { !consumedOldIDs.contains($0.id) }
        publish(otherTracks + remainingRootTracks + mergedBatch)
        return true
    }

    private func pruneTracks(forRoot rootID: UUID, keepingPaths: Set<String>) {
        let rootTracks = tracks.filter { $0.rootID == rootID }
        let droppedIDs = Set(rootTracks.filter { !keepingPaths.contains($0.relativePath) }.map(\.id))
        guard !droppedIDs.isEmpty else {
            saveLibrary()
            return
        }
        let remaining = tracks.filter { !droppedIDs.contains($0.id) }
        publish(remaining)
        forget(droppedIDs)
        let keys = Set(tracks.compactMap(\.artworkKey))
        let artwork = self.artwork
        Task.detached(priority: .utility) { artwork.prune(keeping: keys) }
    }

    private func remapTrackIDs(_ map: [String: String]) {
        guard !map.isEmpty else { return }
        var playlistsChanged = false
        var libraryChanged = false
        for idx in playlists.indices {
            let updated = playlists[idx].trackIDs.map { map[$0] ?? $0 }
            if updated != playlists[idx].trackIDs {
                playlists[idx].trackIDs = updated
                playlistsChanged = true
            }
        }
        var newFavs = Set<String>()
        for id in favourites {
            newFavs.insert(map[id] ?? id)
        }
        if newFavs != favourites {
            favourites = newFavs
            playlistsChanged = true
        }
        let newFavOrder = favouritesOrder.map { map[$0] ?? $0 }
        if newFavOrder != favouritesOrder {
            favouritesOrder = newFavOrder
            playlistsChanged = true
        }
        for (oldID, newID) in map where oldID != newID {
            if let ov = metadataOverrides.removeValue(forKey: oldID), metadataOverrides[newID] == nil {
                metadataOverrides[newID] = ov
                libraryChanged = true
            }
        }
        for key in folderOrders.keys {
            if let existing = folderOrders[key]?.customTrackIDs {
                let updated = existing.map { map[$0] ?? $0 }
                if updated != existing {
                    folderOrders[key]?.customTrackIDs = updated
                    libraryChanged = true
                }
            }
        }
        if playlistsChanged { savePlaylists() }
        if libraryChanged { saveLibrary() }
        onTracksRemapped?(map)
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
    ) -> (tracks: [Track], changed: Bool, migratedIDs: [String: String]) {
        let detailed = mergeCloudTracksDetailed(root: root, items: items, existing: existing, now: now)
        return (detailed.tracks, detailed.changed, detailed.migratedIDs)
    }

    private static func mergeCloudTracksDetailed(
        root: LibraryRoot,
        items: [CloudTrackInfo],
        existing: [String: Track],
        now: Date
    ) -> (tracks: [Track], changed: Bool, migratedIDs: [String: String], consumedOldIDs: Set<String>) {
        let sourceName = root.kind.cloudSourceSlug ?? root.resolvedProviderID ?? "cloud"
        let albumArtistName: String? = root.kind == .cloudGoogleDrive ? "Google Drive" : nil
        var legacyByFileID: [String: Track] = [:]
        if root.kind == .cloudGoogleDrive {
            for track in existing.values where track.relativePath.hasPrefix("gdrive/") {
                let key = GoogleDriveClient.decodeTrackID(track.remoteTrackID ?? track.relativePath).fileID
                if legacyByFileID[key] == nil {
                    legacyByFileID[key] = track
                }
            }
        }

        var seen = Set<String>()
        var result: [Track] = []
        var changed = false
        var migratedIDs: [String: String] = [:]
        var consumedOldIDs = Set<String>()

        var remainingByRemoteID: [String: Track] = [:]
        for track in existing.values {
            if let rid = track.remoteTrackID, !rid.isEmpty, remainingByRemoteID[rid] == nil {
                remainingByRemoteID[rid] = track
            }
        }

        for item in items {
            if root.isExcluded(item.relativePath) { continue }
            guard seen.insert(item.relativePath).inserted else { continue }
            let directMatch = existing[item.relativePath]
            let legacyMatch: Track? = {
                guard directMatch == nil, root.kind == .cloudGoogleDrive else { return nil }
                let fileID = GoogleDriveClient.decodeTrackID(item.remoteTrackID).fileID
                return legacyByFileID[fileID]
            }()
            let remoteMatch: Track? = {
                guard directMatch == nil, legacyMatch == nil, !item.remoteTrackID.isEmpty else { return nil }
                guard let candidate = remainingByRemoteID[item.remoteTrackID],
                      !consumedOldIDs.contains(candidate.id) else {
                    return nil
                }
                return candidate
            }()
            let old = directMatch ?? legacyMatch ?? remoteMatch
            if let old {
                consumedOldIDs.insert(old.id)
            }

            if let directMatch {
                let sameMetadata = directMatch.title == item.title
                    && directMatch.artist == item.artist
                    && directMatch.album == item.album
                    && directMatch.trackNumber == item.trackNumber
                    && abs(directMatch.duration - item.duration) < 0.5
                    && directMatch.remoteTrackID == item.remoteTrackID
                    && directMatch.remoteArtworkURL == item.artworkURL
                    && directMatch.cloudSource == sourceName
                    && directMatch.albumArtist == (albumArtistName ?? item.artist)
                if sameMetadata {
                    result.append(directMatch)
                    continue
                }
            }

            changed = true
            let newID = Track.makeID(rootID: root.id, relativePath: item.relativePath)
            if let old, old.id != newID {
                migratedIDs[old.id] = newID
            }
            let keepArtworkKey = (old?.remoteArtworkURL == item.artworkURL) ? old?.artworkKey : nil
            result.append(Track(
                id: newID,
                rootID: root.id,
                relativePath: item.relativePath,
                title: item.title,
                artist: item.artist,
                album: item.album,
                albumArtist: albumArtistName ?? item.artist,
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
        return (result, changed, migratedIDs, consumedOldIDs)
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
        CloudProviderRegistry.shared.configureArtworkRequest(&req)
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

    func track(_ id: String) -> Track? { byID[id] ?? ephemeralTracks[id] }
    func tracks(_ ids: [String]) -> [Track] { ids.compactMap { byID[$0] } }

    func registerEphemeralTracks(_ items: [Track]) {
        guard !items.isEmpty else { return }
        var changed = false
        for item in items {
            guard item.isEphemeral || item.id.hasPrefix(Track.ephemeralIDPrefix) else { continue }
            var normalized = item
            normalized.isEphemeral = true
            if ephemeralTracks[normalized.id] != normalized {
                ephemeralTracks[normalized.id] = normalized
                changed = true
            }
        }
        if changed {
            saveLibrary()
        }
    }

    func pruneEphemeralTracks(keeping referencedIDs: Set<String>) {
        guard !ephemeralTracks.isEmpty else { return }
        let staleKeys = ephemeralTracks.keys.filter { !referencedIDs.contains($0) }
        guard !staleKeys.isEmpty else { return }
        for key in staleKeys {
            ephemeralTracks.removeValue(forKey: key)
        }
        saveLibrary()
    }

    var defaultLocalSaveDirectory: URL { documentsURL }

    @discardableResult
    func saveCloudTrack(
        _ track: Track,
        toFolder folderURL: URL? = nil,
        cache: CloudAudioCache = .shared,
        artworkData: Data? = nil,
        thumbnailCache: CloudThumbnailCache = .shared
    ) async throws -> URL {
        let targetFolder = folderURL ?? defaultLocalSaveDirectory
        let savedURL = try await CloudExporter.exportTrack(
            track,
            root: root(track.rootID),
            toFolder: targetFolder,
            cache: cache,
            artworkData: artworkData,
            artworkCache: artwork,
            thumbnailCache: thumbnailCache
        )
        if containsLocalURL(savedURL) {
            await rescan()
        }
        return savedURL
    }

    func hasCloudRoot(providerID: String, remoteID: String) -> Bool {
        let cleanRemote = remoteID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanRemote.isEmpty else { return false }
        return roots.contains {
            $0.kind == .cloudExtension
                && $0.resolvedProviderID == providerID
                && $0.remoteID == cleanRemote
        }
    }

    /// The file's current location, or nil while its folder is unavailable.
    func url(for track: Track) -> URL? {
        guard !track.isEphemeral else { return nil }
        return rootURLs[track.rootID].map { FolderScanner.url(root: $0, relativePath: track.relativePath) }
    }

    func isAvailable(_ track: Track) -> Bool {
        if track.isEphemeral || track.id.hasPrefix(Track.ephemeralIDPrefix) {
            if let pid = CloudProviderRegistry.providerID(for: track, root: nil) {
                return extensionAvailabilityProvider(pid) == .available
                    || CloudAudioCache.shared.hasCachedFileSync(for: track.id)
            }
            return false
        }
        if track.isCloud {
            if let root = self.root(track.rootID), root.kind == .cloudExtension {
                return isAvailable(root) || CloudAudioCache.shared.hasCachedFileSync(for: track.id)
            }
            return true
        }
        return rootURLs[track.rootID] != nil
    }

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

    private func usesFlatCloudListing(root: LibraryRoot, rootTracks: [Track]) -> Bool {
        guard root.kind.isCloud else { return false }
        switch root.kind {
        case .cloudYouTube, .cloudBilibili:
            return true
        case .cloudExtension:
            if let provider = provider(for: root), provider.supportsTree {
                return false
            }
            if let known = knownDriveSubfolders[root.id], !known.isEmpty {
                return false
            }
            if rootTracks.contains(where: { $0.relativePath.contains("/") }) {
                return false
            }
            return true
        case .cloudGoogleDrive:
            if let known = knownDriveSubfolders[root.id], !known.isEmpty { return false }
            if rootTracks.isEmpty { return false }
            let hasHierarchicalTrack = rootTracks.contains {
                $0.relativePath.contains("/") && !$0.relativePath.hasPrefix("gdrive/")
            }
            if hasHierarchicalTrack { return false }
            return rootTracks.allSatisfy {
                !$0.relativePath.contains("/") || $0.relativePath.hasPrefix("gdrive/")
            }
        case .picked, .appFolder:
            return false
        }
    }

    /// Direct children of a folder: subfolder names, then the songs in it.
    func folderContents(root: UUID, path: String) -> (folders: [String], tracks: [Track]) {
        let normPath = LibraryRoot.normalizeFolderPath(path)
        let rootObj = self.root(root)
        let rootTracks = tracks.filter { $0.rootID == root }
        if normPath.isEmpty, let rootObj, usesFlatCloudListing(root: rootObj, rootTracks: rootTracks) {
            let direct = rootTracks
                .sorted { $0.fileName.localizedStandardCompare($1.fileName) == .orderedAscending }
            return ([], direct)
        }
        let prefix = normPath.isEmpty ? "" : normPath + "/"
        var folders = Set<String>()
        var direct: [Track] = []
        for track in rootTracks where track.relativePath.hasPrefix(prefix) {
            let rest = track.relativePath.dropFirst(prefix.count)
            if let slash = rest.firstIndex(of: "/") {
                folders.insert(String(rest[..<slash]))
            } else {
                direct.append(track)
            }
        }
        if let known = knownDriveSubfolders[root] {
            for sub in known.values where sub.relativePath.hasPrefix(prefix) {
                if let rootObj, rootObj.isExcluded(sub.relativePath) { continue }
                let rest = sub.relativePath.dropFirst(prefix.count)
                guard !rest.isEmpty else { continue }
                if let slash = rest.firstIndex(of: "/") {
                    folders.insert(String(rest[..<slash]))
                } else {
                    folders.insert(String(rest))
                }
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

    func restorePlaylistTracks(_ id: UUID, trackIDs: [String]) {
        updatePlaylist(id) { playlist in
            playlist.trackIDs = trackIDs
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
