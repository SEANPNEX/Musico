import Foundation

enum BuiltInProviderID {
    // Legacy IDs kept ONLY for resolving persisted roots/tracks/manifests via compatible JS extensions.
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
    func configureArtworkRequest(_ request: inout URLRequest)
}

extension CloudSourceProvider {
    var supportsCollection: Bool { true }
    var availableQualities: [CloudQualityOption] { [] }
    var defaultStreamQualityID: String? { nil }
    var defaultDownloadQualityID: String? { nil }

    func configureArtworkRequest(_ request: inout URLRequest) {}

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
    static let defaultUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private let lock = NSLock()
    private let builtIns: [any CloudSourceProvider]
    private var extensionsByID: [String: any CloudSourceProvider] = [:]

    let googleDriveClient: GoogleDriveClient?

    init(
        transport: any CloudHTTPTransport = URLSessionCloudTransport(),
        driveRetryDelaysNanoseconds: [UInt64] = [500_000_000, 1_500_000_000]
    ) {
        let drive = GoogleDriveClient(
            transport: transport,
            retryDelaysNanoseconds: driveRetryDelaysNanoseconds,
            oauthTokenProvider: { forceRefresh in
                try await GoogleDriveOAuthManager.shared.validAccessToken(forceRefresh: forceRefresh)
            }
        )
        self.googleDriveClient = drive
        self.builtIns = [
            GoogleDriveCloudProvider(client: drive)
        ]
    }

    init(builtIns: [any CloudSourceProvider]) {
        self.googleDriveClient = builtIns.compactMap { ($0 as? GoogleDriveCloudProvider)?.client }.first
        self.builtIns = builtIns
    }

    static func isLegacyYouTubeID(_ id: String?) -> Bool {
        guard let trimmed = id?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return false
        }
        return trimmed == BuiltInProviderID.youtube || trimmed == "youtube"
    }

    static func isLegacyBilibiliID(_ id: String?) -> Bool {
        guard let trimmed = id?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return false
        }
        return trimmed == BuiltInProviderID.bilibili || trimmed == "bilibili"
    }

    static func isLegacyProviderID(_ id: String?) -> Bool {
        isLegacyYouTubeID(id) || isLegacyBilibiliID(id)
    }

    static func legacyFallbackExtensionID(forKind kind: LibraryRoot.Kind?, providerID: String?) -> String? {
        if kind == .cloudYouTube || isLegacyYouTubeID(providerID) {
            return "ext.youtube-search"
        }
        if kind == .cloudBilibili || isLegacyBilibiliID(providerID) {
            return "ext.bilibili-search"
        }
        return nil
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

    func provider(forRoot root: LibraryRoot) -> (any CloudSourceProvider)? {
        let pid = root.effectiveProviderID
        if root.kind.isLegacyExtensionBacked || Self.isLegacyProviderID(pid) {
            return resolveLegacyExtensionProvider(
                kind: root.effectiveCloudKind,
                providerID: pid,
                sourceURL: root.sourceURL,
                trackID: nil
            )
        }
        return provider(forID: pid)
    }

    func provider(forTrack track: Track, root: LibraryRoot? = nil) -> (any CloudSourceProvider)? {
        if let cs = track.cloudSource?.trimmingCharacters(in: .whitespacesAndNewlines), !cs.isEmpty {
            switch cs {
            case "youtube":
                return resolveLegacyExtensionProvider(
                    kind: .cloudYouTube,
                    providerID: BuiltInProviderID.youtube,
                    sourceURL: root?.sourceURL,
                    trackID: track.remoteTrackID ?? track.relativePath
                )
            case "bilibili":
                return resolveLegacyExtensionProvider(
                    kind: .cloudBilibili,
                    providerID: BuiltInProviderID.bilibili,
                    sourceURL: root?.sourceURL,
                    trackID: track.remoteTrackID ?? track.relativePath
                )
            case "gdrive", "googledrive":
                return provider(forID: BuiltInProviderID.googleDrive)
            default:
                if cs.hasPrefix("ext.") {
                    return provider(forID: cs)
                }
            }
        }
        if let extID = track.ephemeralExtensionID {
            return provider(forID: "ext.\(extID)")
        }
        if let root {
            return provider(forRoot: root)
        }
        return nil
    }

    func providerForKind(
        _ kind: LibraryRoot.Kind?,
        providerID: String?,
        sourceURL: String? = nil
    ) -> (any CloudSourceProvider)? {
        if let providerID, !providerID.isEmpty {
            if Self.isLegacyProviderID(providerID) {
                return resolveLegacyExtensionProvider(
                    kind: kind,
                    providerID: providerID,
                    sourceURL: sourceURL,
                    trackID: nil
                )
            }
            return provider(forID: providerID)
        }
        if let kind, kind.isLegacyExtensionBacked {
            return resolveLegacyExtensionProvider(
                kind: kind,
                providerID: nil,
                sourceURL: sourceURL,
                trackID: nil
            )
        }
        if let builtInID = kind?.builtInProviderID {
            return provider(forID: builtInID)
        }
        return nil
    }

    private func resolveLegacyExtensionProvider(
        kind: LibraryRoot.Kind?,
        providerID: String?,
        sourceURL: String?,
        trackID: String?
    ) -> (any CloudSourceProvider)? {
        let exts = extensionProviders()
        guard !exts.isEmpty else { return nil }

        if let raw = sourceURL?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty {
            let extracted = CloudSourceInput.extract(from: raw, registry: self)
            if let matched = exts.first(where: { $0.canHandle(rawText: raw, extractedURL: extracted) != nil }) {
                return matched
            }
        }

        let isYouTube = kind == .cloudYouTube || Self.isLegacyYouTubeID(providerID)
        let isBilibili = kind == .cloudBilibili || Self.isLegacyBilibiliID(providerID)

        if isYouTube {
            if let preferred = lock.withLock({ extensionsByID["ext.youtube-search"] }) {
                return preferred
            }
            if let trackID = trackID?.trimmingCharacters(in: .whitespacesAndNewlines), !trackID.isEmpty,
               let matched = exts.first(where: { $0.canHandle(rawText: trackID, extractedURL: trackID) != nil }) {
                return matched
            }
            let probe = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
            return exts.first(where: { $0.canHandle(rawText: probe, extractedURL: probe) != nil })
        }

        if isBilibili {
            if let preferred = lock.withLock({ extensionsByID["ext.bilibili-search"] }) {
                return preferred
            }
            if let trackID = trackID?.trimmingCharacters(in: .whitespacesAndNewlines), !trackID.isEmpty,
               let matched = exts.first(where: { $0.canHandle(rawText: trackID, extractedURL: trackID) != nil }) {
                return matched
            }
            let probe = "https://www.bilibili.com/video/BV1xx411c7mD"
            return exts.first(where: { $0.canHandle(rawText: probe, extractedURL: probe) != nil })
        }

        return nil
    }

    func configureArtworkRequest(_ request: inout URLRequest, providerID: String? = nil) {
        if request.value(forHTTPHeaderField: "User-Agent") == nil {
            request.setValue(Self.defaultUserAgent, forHTTPHeaderField: "User-Agent")
        }
        if let providerID, let resolved = provider(forID: providerID) {
            resolved.configureArtworkRequest(&request)
            return
        }
        for provider in allProviders() {
            provider.configureArtworkRequest(&request)
        }
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
