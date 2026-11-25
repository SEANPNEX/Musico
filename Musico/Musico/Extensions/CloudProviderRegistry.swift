import Foundation

enum BuiltInProviderID {
    static let youtube = "builtin.youtube"
    static let bilibili = "builtin.bilibili"
    static let googleDrive = "builtin.gdrive"
}

struct CloudMatchResult: Sendable, Equatable {
    var providerID: String
    var kind: LibraryRoot.Kind
    var extractedURL: String
    var normalizedURL: String
    var remoteID: String?
    var itemKind: String?
}

protocol CloudSourceProvider: Sendable {
    var id: String { get }
    var displayName: String { get }
    var badgeLabel: String { get }
    var rootKind: LibraryRoot.Kind { get }
    var trackCloudSource: String { get }
    var supportsTree: Bool { get }
    var supportsCookie: Bool { get }
    var supportsCollection: Bool { get }
    var availableQualities: [CloudQualityOption] { get }
    var defaultStreamQualityID: String? { get }
    var defaultDownloadQualityID: String? { get }

    func canHandle(rawText: String, extractedURL: String) -> CloudMatchResult?
    func fetchCollection(sourceURL: String, cookie: String?) async throws -> CloudPlaylistResult
    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult
    func resolveStream(
        track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        qualityID: String?,
        forceRefresh: Bool
    ) async throws -> CloudStreamResult
    func effectiveQualityID(
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        overrideQualityID: String?
    ) -> String?
}

extension CloudSourceProvider {
    var supportsCollection: Bool { true }
    var availableQualities: [CloudQualityOption] { [] }
    var defaultStreamQualityID: String? { nil }
    var defaultDownloadQualityID: String? { nil }

    func resolveStream(
        track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        qualityID: String?,
        forceRefresh: Bool
    ) async throws -> CloudStreamResult {
        try await resolveStream(track: track, root: root, forceRefresh: forceRefresh)
    }

    func effectiveQualityID(
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        overrideQualityID: String?
    ) -> String? {
        let qualities = availableQualities
        guard !qualities.isEmpty else { return nil }
        let validIDs = Set(qualities.map(\.id))
        if let override = overrideQualityID?.trimmingCharacters(in: .whitespacesAndNewlines),
           !override.isEmpty,
           validIDs.contains(override) {
            return override
        }
        switch purpose {
        case .stream:
            if let rootQ = root?.streamQualityID?.trimmingCharacters(in: .whitespacesAndNewlines),
               !rootQ.isEmpty,
               validIDs.contains(rootQ) {
                return rootQ
            }
            return defaultStreamQualityID ?? qualities.first?.id
        case .download:
            if let rootQ = root?.downloadQualityID?.trimmingCharacters(in: .whitespacesAndNewlines),
               !rootQ.isEmpty,
               validIDs.contains(rootQ) {
                return rootQ
            }
            return defaultDownloadQualityID ?? qualities.first?.id
        }
    }
}

protocol CloudProgressiveTreeProvider: CloudSourceProvider {
    func discoverRoot(sourceURL: String, cookie: String?) async throws -> DriveFolderDiscovery
    func crawlTree(
        discovery: DriveFolderDiscovery,
        cookie: String?,
        priorityState: DriveCrawlPriorityState?,
        onBatch: @escaping @Sendable (DriveFolderBatch) async -> Void
    ) async throws -> DriveCrawlSummary
    func fetchSingleFolder(
        folderID: String,
        resourceKey: String?,
        pathSegments: [String],
        depth: Int,
        rootTitle: String?,
        cookie: String?,
        extraJSON: String?
    ) async throws -> DriveFolderBatch
}

protocol CloudProviderPlaylistFetching: CloudPlaylistFetching {
    func fetchPlaylist(
        sourceURL: String,
        cookie: String?,
        kindHint: LibraryRoot.Kind?,
        providerIDHint: String?
    ) async throws -> CloudPlaylistResult
}

protocol CloudTreeProgressiveFetching: CloudPlaylistFetching {
    func supportsProgressiveTree(for providerID: String) -> Bool
    func discoverTreeRoot(sourceURL: String, cookie: String?, providerID: String?) async throws -> DriveFolderDiscovery
    func crawlTree(
        discovery: DriveFolderDiscovery,
        providerID: String?,
        cookie: String?,
        priorityState: DriveCrawlPriorityState?,
        onBatch: @escaping @Sendable (DriveFolderBatch) async -> Void
    ) async throws -> DriveCrawlSummary
    func fetchSingleTreeFolder(
        providerID: String?,
        folderID: String,
        resourceKey: String?,
        pathSegments: [String],
        depth: Int,
        rootTitle: String?,
        cookie: String?,
        extraJSON: String?
    ) async throws -> DriveFolderBatch
}

// MARK: - Built-in Cloud Providers

struct YouTubeCloudProvider: CloudSourceProvider {
    let id = BuiltInProviderID.youtube
    let displayName = "YouTube"
    let badgeLabel = "YouTube"
    let rootKind = LibraryRoot.Kind.cloudYouTube
    let trackCloudSource = "youtube"
    let supportsTree = false
    let supportsCookie = true
    var availableQualities: [CloudQualityOption] {
        [
            CloudQualityOption(id: "highest", label: L10n.trNonisolated("Highest (256k)"), badge: "256k", lossless: false),
            CloudQualityOption(id: "high", label: L10n.trNonisolated("High (128k–160k)"), badge: "128k", lossless: false),
            CloudQualityOption(id: "standard", label: L10n.trNonisolated("Standard (128k)"), badge: "128k", lossless: false),
            CloudQualityOption(id: "low", label: L10n.trNonisolated("Low (48k)"), badge: "48k", lossless: false)
        ]
    }
    let defaultStreamQualityID: String? = "standard"
    let defaultDownloadQualityID: String? = "highest"

    let client: YouTubeClient

    init(client: YouTubeClient) {
        self.client = client
    }

    func canHandle(rawText: String, extractedURL: String) -> CloudMatchResult? {
        let candidate = extractedURL.isEmpty ? rawText : extractedURL
        guard let target = YouTubeClient.parseExtracted(candidate) else { return nil }
        let remoteID: String
        let itemKind: String
        switch target {
        case .playlist(let id, _):
            remoteID = id
            itemKind = "playlist"
        case .video(let id):
            remoteID = id
            itemKind = "track"
        }
        return CloudMatchResult(
            providerID: id,
            kind: .cloudYouTube,
            extractedURL: candidate,
            normalizedURL: candidate,
            remoteID: remoteID,
            itemKind: itemKind
        )
    }

    func fetchCollection(sourceURL: String, cookie: String?) async throws -> CloudPlaylistResult {
        var result = try await client.fetchPlaylist(sourceURL: sourceURL, cookie: cookie)
        result.providerID = id
        return result
    }

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        try await resolveStream(track: track, root: root, purpose: .stream, qualityID: nil, forceRefresh: forceRefresh)
    }

    func resolveStream(
        track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        qualityID: String?,
        forceRefresh: Bool
    ) async throws -> CloudStreamResult {
        let videoID = track.remoteTrackID ?? track.relativePath
        let effectiveQuality = effectiveQualityID(root: root, purpose: purpose, overrideQualityID: qualityID)
        return try await client.resolveStream(
            videoID: videoID,
            cookie: root?.cookie,
            qualityID: effectiveQuality,
            forceRefresh: forceRefresh
        )
    }
}

struct BilibiliCloudProvider: CloudSourceProvider {
    let id = BuiltInProviderID.bilibili
    let displayName = "Bilibili"
    let badgeLabel = "Bilibili"
    let rootKind = LibraryRoot.Kind.cloudBilibili
    let trackCloudSource = "bilibili"
    let supportsTree = false
    let supportsCookie = true
    var availableQualities: [CloudQualityOption] {
        [
            CloudQualityOption(id: "highest", label: L10n.trNonisolated("Highest (FLAC / 192k)"), badge: "FLAC", lossless: true),
            CloudQualityOption(id: "high", label: L10n.trNonisolated("High (192k)"), badge: "192k", lossless: false),
            CloudQualityOption(id: "standard", label: L10n.trNonisolated("Standard (132k)"), badge: "132k", lossless: false),
            CloudQualityOption(id: "low", label: L10n.trNonisolated("Low (64k)"), badge: "64k", lossless: false)
        ]
    }
    let defaultStreamQualityID: String? = "standard"
    let defaultDownloadQualityID: String? = "highest"

    let client: BilibiliClient

    init(client: BilibiliClient) {
        self.client = client
    }

    func canHandle(rawText: String, extractedURL: String) -> CloudMatchResult? {
        let candidate = extractedURL.isEmpty ? rawText : extractedURL
        guard let target = BilibiliClient.parseExtracted(candidate) else { return nil }
        let remoteID: String?
        let itemKind: String
        switch target {
        case .video(let bvid, let aid):
            remoteID = bvid ?? aid
            itemKind = "track"
        case .favlist(let id):
            remoteID = "fid_\(id)"
            itemKind = "playlist"
        case .season(let id, _):
            remoteID = "season_\(id)"
            itemKind = "playlist"
        case .series(let id, _):
            remoteID = "series_\(id)"
            itemKind = "playlist"
        case .shortLink:
            remoteID = nil
            itemKind = "playlist"
        }
        return CloudMatchResult(
            providerID: id,
            kind: .cloudBilibili,
            extractedURL: candidate,
            normalizedURL: candidate,
            remoteID: remoteID,
            itemKind: itemKind
        )
    }

    func fetchCollection(sourceURL: String, cookie: String?) async throws -> CloudPlaylistResult {
        var result = try await client.fetchPlaylist(sourceURL: sourceURL, cookie: cookie)
        result.providerID = id
        return result
    }

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        try await resolveStream(track: track, root: root, purpose: .stream, qualityID: nil, forceRefresh: forceRefresh)
    }

    func resolveStream(
        track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        qualityID: String?,
        forceRefresh: Bool
    ) async throws -> CloudStreamResult {
        let trackID = track.remoteTrackID ?? track.relativePath
        let effectiveQuality = effectiveQualityID(root: root, purpose: purpose, overrideQualityID: qualityID)
        return try await client.resolveStream(
            trackID: trackID,
            cookie: root?.cookie,
            qualityID: effectiveQuality,
            forceRefresh: forceRefresh
        )
    }
}

struct GoogleDriveCloudProvider: CloudProgressiveTreeProvider {
    let id = BuiltInProviderID.googleDrive
    let displayName = "Google Drive"
    var badgeLabel: String { L10n.trNonisolated("Google Drive") }
    let rootKind = LibraryRoot.Kind.cloudGoogleDrive
    let trackCloudSource = "gdrive"
    let supportsTree = true
    let supportsCookie = true

    let client: GoogleDriveClient

    init(client: GoogleDriveClient) {
        self.client = client
    }

    func canHandle(rawText: String, extractedURL: String) -> CloudMatchResult? {
        let candidate = extractedURL.isEmpty ? rawText : extractedURL
        guard let target = GoogleDriveClient.parseExtracted(candidate) else { return nil }
        let remoteID: String
        let itemKind: String
        switch target {
        case .folder(let id, _):
            remoteID = id
            itemKind = "folder"
        case .file(let id, _):
            remoteID = id
            itemKind = "track"
        case .open(let id, _):
            remoteID = id
            itemKind = "folder"
        }
        return CloudMatchResult(
            providerID: id,
            kind: .cloudGoogleDrive,
            extractedURL: candidate,
            normalizedURL: candidate,
            remoteID: remoteID,
            itemKind: itemKind
        )
    }

    func fetchCollection(sourceURL: String, cookie: String?) async throws -> CloudPlaylistResult {
        var result = try await client.fetchPlaylist(sourceURL: sourceURL, cookie: cookie, allowBareID: false)
        result.providerID = id
        return result
    }

    func discoverRoot(sourceURL: String, cookie: String?) async throws -> DriveFolderDiscovery {
        var discovery = try await client.discoverRoot(sourceURL: sourceURL, cookie: cookie, allowBareID: false)
        discovery.providerID = id
        discovery.kind = .cloudGoogleDrive
        if var single = discovery.singleFileResult {
            single.providerID = id
            discovery.singleFileResult = single
        }
        return discovery
    }

    func crawlTree(
        discovery: DriveFolderDiscovery,
        cookie: String?,
        priorityState: DriveCrawlPriorityState?,
        onBatch: @escaping @Sendable (DriveFolderBatch) async -> Void
    ) async throws -> DriveCrawlSummary {
        try await client.crawlFolderTree(
            discovery: discovery,
            cookie: cookie,
            priorityState: priorityState,
            maxWorkers: 4,
            onBatch: onBatch
        )
    }

    func fetchSingleFolder(
        folderID: String,
        resourceKey: String?,
        pathSegments: [String],
        depth: Int,
        rootTitle: String?,
        cookie: String?,
        extraJSON: String?
    ) async throws -> DriveFolderBatch {
        try await client.fetchSingleFolder(
            folderID: folderID,
            resourceKey: resourceKey,
            pathSegments: pathSegments,
            depth: depth,
            rootTitle: rootTitle,
            cookie: cookie
        )
    }

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        let trackID = track.remoteTrackID ?? track.relativePath
        let ext = (track.relativePath as NSString).pathExtension.lowercased()
        return try await client.resolveStream(
            trackID: trackID,
            cookie: root?.cookie,
            fallbackExtension: ext.isEmpty ? nil : ext,
            forceRefresh: forceRefresh
        )
    }
}

// MARK: - Provider Registry

/// Central thread-safe registry mapping built-in cloud providers and enabled JS extensions.
final class CloudProviderRegistry: @unchecked Sendable {
    static let shared = CloudProviderRegistry()

    private let lock = NSLock()
    private let builtIns: [any CloudSourceProvider]
    private var extensionsByID: [String: any CloudSourceProvider] = [:]

    let youtubeClient: YouTubeClient?
    let bilibiliClient: BilibiliClient?
    let googleDriveClient: GoogleDriveClient?

    init(
        transport: any CloudHTTPTransport = URLSessionCloudTransport(),
        driveRetryDelaysNanoseconds: [UInt64] = [500_000_000, 1_500_000_000]
    ) {
        let yt = YouTubeClient(transport: transport)
        let bili = BilibiliClient(transport: transport)
        let drive = GoogleDriveClient(transport: transport, retryDelaysNanoseconds: driveRetryDelaysNanoseconds)
        self.youtubeClient = yt
        self.bilibiliClient = bili
        self.googleDriveClient = drive
        self.builtIns = [
            YouTubeCloudProvider(client: yt),
            BilibiliCloudProvider(client: bili),
            GoogleDriveCloudProvider(client: drive)
        ]
    }

    init(builtIns: [any CloudSourceProvider]) {
        self.youtubeClient = builtIns.compactMap { ($0 as? YouTubeCloudProvider)?.client }.first
        self.bilibiliClient = builtIns.compactMap { ($0 as? BilibiliCloudProvider)?.client }.first
        self.googleDriveClient = builtIns.compactMap { ($0 as? GoogleDriveCloudProvider)?.client }.first
        self.builtIns = builtIns
    }

    func allProviders() -> [any CloudSourceProvider] {
        let extSorted = lock.withLock {
            extensionsByID.values.sorted { $0.id < $1.id }
        }
        return builtIns + extSorted
    }

    func extensionProviders() -> [any CloudSourceProvider] {
        lock.withLock {
            extensionsByID.values.sorted { $0.id < $1.id }
        }
    }

    func provider(forID providerID: String?) -> (any CloudSourceProvider)? {
        guard let providerID, !providerID.isEmpty else { return nil }
        if let builtIn = builtIns.first(where: { $0.id == providerID }) {
            return builtIn
        }
        return lock.withLock { extensionsByID[providerID] }
    }

    func registerExtension(_ provider: any CloudSourceProvider) {
        lock.withLock {
            extensionsByID[provider.id] = provider
        }
    }

    func unregisterExtension(providerID: String) {
        lock.withLock {
            extensionsByID.removeValue(forKey: providerID)
        }
    }

    func replaceExtensions(_ providers: [any CloudSourceProvider]) {
        lock.withLock {
            extensionsByID = Dictionary(uniqueKeysWithValues: providers.map { ($0.id, $0) })
        }
    }

    func match(rawText: String, extractedURL: String) -> CloudMatchResult? {
        let providers = allProviders()
        for provider in providers {
            if let hit = provider.canHandle(rawText: rawText, extractedURL: extractedURL) {
                return hit
            }
        }
        return nil
    }

    static func providerID(for track: Track, root: LibraryRoot?) -> String? {
        if let cs = track.cloudSource?.trimmingCharacters(in: .whitespacesAndNewlines), !cs.isEmpty {
            switch cs {
            case "youtube": return BuiltInProviderID.youtube
            case "bilibili": return BuiltInProviderID.bilibili
            case "gdrive", "googledrive": return BuiltInProviderID.googleDrive
            default:
                if cs.hasPrefix("ext.") {
                    return cs
                }
            }
        }
        if let extID = track.ephemeralExtensionID {
            return "ext.\(extID)"
        }
        return root?.resolvedProviderID ?? root?.effectiveProviderID
    }
}
