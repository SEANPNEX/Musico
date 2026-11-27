import Foundation
import CryptoKit

// MARK: - Shared Types & Protocols

enum CloudSourceError: LocalizedError, Equatable {
    case invalidURL
    case unsupportedSource
    case emptyPlaylist
    case noPlayableStream
    case rateLimited
    case permissionDenied(host: String)
    case extensionUnavailable(name: String)
    case extensionRuntimeError(code: String, message: String)
    case apiError(String)
    case networkError(String)

    var extensionErrorCode: String? {
        switch self {
        case .invalidURL: "INVALID_URL"
        case .emptyPlaylist: "EMPTY_COLLECTION"
        case .noPlayableStream: "NO_PLAYABLE_STREAM"
        case .rateLimited: "RATE_LIMITED"
        case .networkError: "NETWORK_ERROR"
        case .permissionDenied: "PERMISSION_DENIED"
        case .extensionUnavailable: "EXTENSION_UNAVAILABLE"
        case .extensionRuntimeError(let code, _): code
        case .unsupportedSource, .apiError: nil
        }
    }

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            L10n.trNonisolated("Enter a valid Google Drive link or a link supported by an installed extension.")
        case .unsupportedSource:
            L10n.trNonisolated("That link isn’t supported by Google Drive or any enabled extension.")
        case .emptyPlaylist:
            L10n.trNonisolated("No playable tracks were found at that link.")
        case .noPlayableStream:
            L10n.trNonisolated("Could not resolve a playable audio stream for this track.")
        case .rateLimited:
            L10n.trNonisolated("Rate limited by the cloud source. Please try again later.")
        case .permissionDenied(let host):
            if host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                L10n.trNonisolated("Extension was denied access to an unauthorized host.")
            } else {
                L10n.trNonisolated("Extension was denied access to host “%@”.", host)
            }
        case .extensionUnavailable(let name):
            L10n.trNonisolated("Extension “%@” is disabled or not installed.", name)
        case .extensionRuntimeError(let code, let message):
            if !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                message
            } else {
                L10n.trNonisolated("Extension error (%@).", code)
            }
        case .apiError(let message):
            message
        case .networkError(let message):
            message
        }
    }
}

struct CloudTrackInfo: Sendable, Equatable {
    var remoteTrackID: String
    var relativePath: String
    var title: String
    var artist: String
    var album: String
    var trackNumber: Int?
    var duration: TimeInterval
    var artworkURL: String?
}

struct CloudPlaylistResult: Sendable, Equatable {
    var kind: LibraryRoot.Kind
    var remoteID: String
    var title: String
    var tracks: [CloudTrackInfo]
    var providerID: String? = nil
}

struct CloudStreamResult: Sendable, Equatable {
    var url: URL
    var headers: [String: String]
    var fileExtension: String
    var expiresAt: Date
    var fallbackURLs: [URL] = []
    var contentLength: Int64? = nil
    var actualQuality: String? = nil
    var qualityLabel: String? = nil
    var bitrateKbps: Int? = nil
}

struct HTTPResponseData: Sendable {
    var statusCode: Int
    var headers: [String: String]
    var data: Data
    var finalURL: URL?

    func header(_ name: String) -> String? {
        let lower = name.lowercased()
        return headers.first { $0.key.lowercased() == lower }?.value
    }
}

protocol CloudHTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> HTTPResponseData
}

extension CloudHTTPTransport {
    /// Performs `request` without automatically following HTTP redirects when supported by the transport.
    func rawRequest(_ request: URLRequest) async throws -> HTTPResponseData {
        try await send(request)
    }
}

private final class NoRedirectSessionDelegate: NSObject, URLSessionTaskDelegate, Sendable {
    static let shared = NoRedirectSessionDelegate()

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

struct URLSessionCloudTransport: CloudHTTPTransport {
    private let session: URLSession

    init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 20
            config.timeoutIntervalForResource = 60
            config.httpCookieAcceptPolicy = .always
            self.session = URLSession(configuration: config)
        }
    }

    func send(_ request: URLRequest) async throws -> HTTPResponseData {
        try await performRequest(request, delegate: nil)
    }

    func rawRequest(_ request: URLRequest) async throws -> HTTPResponseData {
        try await performRequest(request, delegate: NoRedirectSessionDelegate.shared)
    }

    private func performRequest(_ request: URLRequest, delegate: (any URLSessionTaskDelegate)?) async throws -> HTTPResponseData {
        do {
            let (data, response) = try await session.data(for: request, delegate: delegate)
            guard let http = response as? HTTPURLResponse else {
                throw CloudSourceError.networkError(L10n.trNonisolated("Invalid server response."))
            }
            var normalizedHeaders: [String: String] = [:]
            for (key, value) in http.allHeaderFields {
                normalizedHeaders[String(describing: key)] = String(describing: value)
            }
            return HTTPResponseData(
                statusCode: http.statusCode,
                headers: normalizedHeaders,
                data: data,
                finalURL: http.url
            )
        } catch let error as CloudSourceError {
            throw error
        } catch {
            throw CloudSourceError.networkError(error.localizedDescription)
        }
    }
}

protocol CloudPlaylistFetching: Sendable {
    func fetchPlaylist(sourceURL: String, cookie: String?, kindHint: LibraryRoot.Kind?) async throws -> CloudPlaylistResult
}

struct DriveSubfolderRef: Equatable, Hashable, Sendable {
    var id: String
    var resourceKey: String?
    var pathSegments: [String]
    var depth: Int
    var extraJSON: String? = nil

    var relativePath: String { pathSegments.joined(separator: "/") }
    var name: String { pathSegments.last ?? id }
}

struct DriveFolderBatch: Equatable, Sendable {
    var folderID: String
    var folderPath: String
    var depth: Int
    var rootTitle: String?
    var tracks: [CloudTrackInfo]
    var discoveredSubfolders: [DriveSubfolderRef]
    var hadError: Bool
}

struct DriveFolderDiscovery: Equatable, Sendable {
    var rootID: String
    var resourceKey: String?
    var rootTitle: String
    var initialBatch: DriveFolderBatch
    var singleFileResult: CloudPlaylistResult?
    var providerID: String? = nil
    var kind: LibraryRoot.Kind = .cloudGoogleDrive
}

struct DriveCrawlSummary: Equatable, Sendable {
    var foldersVisited: Int
    var hadErrors: Bool
    var wasTruncated: Bool
}

final class DriveCrawlPriorityState: @unchecked Sendable {
    private let lock = NSLock()
    private var orderedTargets: [String] = []

    func prioritize(_ folderPath: String) {
        let norm = LibraryRoot.normalizeFolderPath(folderPath)
        guard !norm.isEmpty else { return }
        lock.withLock {
            orderedTargets.removeAll { $0 == norm }
            orderedTargets.insert(norm, at: 0)
        }
    }

    func prioritizedPaths() -> [String] {
        lock.withLock { orderedTargets }
    }
}

protocol GoogleDriveProgressiveFetching: CloudPlaylistFetching {
    func discoverGoogleDriveRoot(sourceURL: String, cookie: String?) async throws -> DriveFolderDiscovery
    func crawlGoogleDriveTree(
        discovery: DriveFolderDiscovery,
        cookie: String?,
        priorityState: DriveCrawlPriorityState?,
        onBatch: @escaping @Sendable (DriveFolderBatch) async -> Void
    ) async throws -> DriveCrawlSummary
    func fetchGoogleDriveSingleFolder(
        folderID: String,
        resourceKey: String?,
        pathSegments: [String],
        depth: Int,
        rootTitle: String?,
        cookie: String?
    ) async throws -> DriveFolderBatch
}

protocol CloudStreamResolving: Sendable {
    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult
    func resolveStream(
        track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        qualityID: String?,
        forceRefresh: Bool
    ) async throws -> CloudStreamResult
    func effectiveQualityID(
        for track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        overrideQualityID: String?
    ) -> String?
}

extension CloudStreamResolving {
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
        for track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        overrideQualityID: String?
    ) -> String? {
        let trimmed = overrideQualityID?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let trimmed, !trimmed.isEmpty {
            return trimmed
        }
        switch purpose {
        case .stream:
            let rootQ = root?.streamQualityID?.trimmingCharacters(in: .whitespacesAndNewlines)
            return (rootQ?.isEmpty == false) ? rootQ : nil
        case .download:
            let rootQ = root?.downloadQualityID?.trimmingCharacters(in: .whitespacesAndNewlines)
            return (rootQ?.isEmpty == false) ? rootQ : nil
        }
    }
}

// MARK: - Share-Link URL Extraction & Classification

enum CloudSourceInput {
    private static let trailingPunctuation: Set<Character> = [
        ")", "]", "】", "」", "》", "。", ",", ".", "'", "\""
    ]
    private static let urlPattern = #"https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+"#

    /// Extracts an `http(s)://` URL from pasted share text while stripping surrounding copy and
    /// trailing punctuation, or falls back to a trimmed bare ID / schemeless link.
    static func extract(from raw: String, registry: CloudProviderRegistry = .shared) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }

        if let regex = try? NSRegularExpression(pattern: urlPattern, options: [.caseInsensitive]) {
            let fullRange = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
            let matches = regex.matches(in: trimmed, range: fullRange)
            var candidates: [String] = []
            for match in matches {
                guard let range = Range(match.range, in: trimmed) else { continue }
                let cleaned = cleanMatchedURL(String(trimmed[range]))
                if !cleaned.isEmpty {
                    candidates.append(cleaned)
                }
            }
            if let supported = candidates.first(where: { candidate in
                registry.match(rawText: trimmed, extractedURL: candidate) != nil
            }) {
                return supported
            }
            if let first = candidates.first {
                return first
            }
        }

        return stripTrailingPunctuation(from: trimmed)
    }

    static func classify(_ raw: String, registry: CloudProviderRegistry = .shared) -> LibraryRoot.Kind? {
        let extracted = extract(from: raw, registry: registry)
        guard !extracted.isEmpty else { return nil }
        return registry.match(rawText: raw, extractedURL: extracted)?.kind
    }

    private static func cleanMatchedURL(_ rawMatch: String) -> String {
        var result = stripTrailingPunctuation(from: rawMatch)
        if result.lowercased().hasPrefix("https://") {
            result = "https://" + result.dropFirst(8)
        } else if result.lowercased().hasPrefix("http://") {
            result = "http://" + result.dropFirst(7)
        }
        return result
    }

    private static func stripTrailingPunctuation(from value: String) -> String {
        var result = value.trimmingCharacters(in: .whitespacesAndNewlines)
        while let last = result.last, trailingPunctuation.contains(last) {
            result.removeLast()
        }
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: - Live Service Facade

final class LiveCloudService: CloudPlaylistFetching, CloudProviderPlaylistFetching, GoogleDriveProgressiveFetching, CloudTreeProgressiveFetching, CloudStreamResolving, @unchecked Sendable {
    static let shared = LiveCloudService(registry: .shared)

    let registry: CloudProviderRegistry
    let googleDrive: GoogleDriveClient

    init(
        transport: any CloudHTTPTransport = URLSessionCloudTransport(),
        driveRetryDelaysNanoseconds: [UInt64] = [500_000_000, 1_500_000_000],
        registry: CloudProviderRegistry? = nil
    ) {
        let resolvedRegistry = registry ?? CloudProviderRegistry(
            transport: transport,
            driveRetryDelaysNanoseconds: driveRetryDelaysNanoseconds
        )
        self.registry = resolvedRegistry
        self.googleDrive = resolvedRegistry.googleDriveClient ?? GoogleDriveClient(
            transport: transport,
            retryDelaysNanoseconds: driveRetryDelaysNanoseconds
        )
    }

    nonisolated static func classifySource(
        _ sourceURL: String,
        fallbackKind: LibraryRoot.Kind? = nil,
        preferredProviderID: String? = nil,
        registry: CloudProviderRegistry = .shared
    ) throws -> (extracted: String, kind: LibraryRoot.Kind, remoteID: String?, providerID: String?) {
        let extracted = CloudSourceInput.extract(from: sourceURL, registry: registry)
        guard !extracted.isEmpty else { throw LibraryError.invalidCloudURL }

        if let preferredProviderID, !preferredProviderID.isEmpty {
            guard let provider = registry.providerForKind(fallbackKind, providerID: preferredProviderID, sourceURL: sourceURL) else {
                if preferredProviderID.hasPrefix("ext.") {
                    throw CloudSourceError.extensionUnavailable(name: preferredProviderID)
                }
                if let fallbackExt = CloudProviderRegistry.legacyFallbackExtensionID(forKind: fallbackKind, providerID: preferredProviderID) {
                    throw CloudSourceError.extensionUnavailable(name: fallbackExt)
                }
                throw LibraryError.invalidCloudURL
            }
            if let match = provider.canHandle(rawText: sourceURL, extractedURL: extracted) {
                return (extracted, match.kind, match.remoteID, provider.id)
            }
            if provider.id == BuiltInProviderID.googleDrive, GoogleDriveClient.isValidDriveID(extracted) {
                return (extracted, .cloudGoogleDrive, extracted, BuiltInProviderID.googleDrive)
            }
            throw LibraryError.invalidCloudURL
        }

        if let match = registry.match(rawText: sourceURL, extractedURL: extracted) {
            return (extracted, match.kind, match.remoteID, match.providerID)
        }
        if fallbackKind == .cloudGoogleDrive, GoogleDriveClient.isValidDriveID(extracted) {
            return (extracted, .cloudGoogleDrive, extracted, BuiltInProviderID.googleDrive)
        }
        throw LibraryError.invalidCloudURL
    }

    func fetchPlaylist(sourceURL: String, cookie: String?, kindHint: LibraryRoot.Kind? = nil) async throws -> CloudPlaylistResult {
        try await fetchPlaylist(sourceURL: sourceURL, cookie: cookie, kindHint: kindHint, providerIDHint: nil)
    }

    func fetchPlaylist(
        sourceURL: String,
        cookie: String?,
        kindHint: LibraryRoot.Kind?,
        providerIDHint: String?
    ) async throws -> CloudPlaylistResult {
        let extracted = CloudSourceInput.extract(from: sourceURL, registry: registry)
        guard !extracted.isEmpty else { throw CloudSourceError.invalidURL }

        let targetProvider: any CloudSourceProvider
        if let providerIDHint, !providerIDHint.isEmpty {
            guard let resolved = registry.providerForKind(kindHint, providerID: providerIDHint, sourceURL: sourceURL) else {
                if providerIDHint.hasPrefix("ext.") {
                    throw CloudSourceError.extensionUnavailable(name: providerIDHint)
                }
                if let fallbackExt = CloudProviderRegistry.legacyFallbackExtensionID(forKind: kindHint, providerID: providerIDHint) {
                    throw CloudSourceError.extensionUnavailable(name: fallbackExt)
                }
                throw CloudSourceError.unsupportedSource
            }
            if resolved.id == BuiltInProviderID.googleDrive {
                return try await googleDrive.fetchPlaylist(sourceURL: extracted, cookie: cookie, allowBareID: true)
            }
            targetProvider = resolved
        } else if let kindHint, kindHint == .cloudGoogleDrive {
            return try await googleDrive.fetchPlaylist(sourceURL: extracted, cookie: cookie, allowBareID: true)
        } else if let kindHint, kindHint.isLegacyExtensionBacked {
            guard let resolved = registry.providerForKind(kindHint, providerID: nil, sourceURL: sourceURL) else {
                let fallbackExt = CloudProviderRegistry.legacyFallbackExtensionID(forKind: kindHint, providerID: nil) ?? "extension"
                throw CloudSourceError.extensionUnavailable(name: fallbackExt)
            }
            targetProvider = resolved
        } else if let match = registry.match(rawText: sourceURL, extractedURL: extracted),
                  let resolved = registry.provider(forID: match.providerID) {
            let fetchURL = match.kind == .cloudExtension ? match.normalizedURL : extracted
            return try await resolved.fetchCollection(sourceURL: fetchURL, cookie: cookie)
        } else {
            throw CloudSourceError.unsupportedSource
        }

        if targetProvider.rootKind == .cloudExtension {
            guard targetProvider.supportsCollection else {
                throw CloudSourceError.unsupportedSource
            }
            if let matched = targetProvider.canHandle(rawText: sourceURL, extractedURL: extracted) {
                return try await targetProvider.fetchCollection(sourceURL: matched.normalizedURL, cookie: cookie)
            }
        }
        return try await targetProvider.fetchCollection(sourceURL: extracted, cookie: cookie)
    }

    func supportsProgressiveTree(for providerID: String) -> Bool {
        registry.provider(forID: providerID) is any CloudProgressiveTreeProvider
    }

    func discoverTreeRoot(sourceURL: String, cookie: String?, providerID: String?) async throws -> DriveFolderDiscovery {
        let extracted = CloudSourceInput.extract(from: sourceURL, registry: registry)
        guard !extracted.isEmpty else { throw CloudSourceError.invalidURL }

        let resolvedProvider: (any CloudProgressiveTreeProvider)?
        if let providerID, !providerID.isEmpty {
            resolvedProvider = registry.provider(forID: providerID) as? any CloudProgressiveTreeProvider
            if resolvedProvider == nil, providerID.hasPrefix("ext.") {
                throw CloudSourceError.extensionUnavailable(name: providerID)
            }
        } else if let match = registry.match(rawText: sourceURL, extractedURL: extracted) {
            resolvedProvider = registry.provider(forID: match.providerID) as? any CloudProgressiveTreeProvider
        } else {
            resolvedProvider = nil
        }

        guard let treeProvider = resolvedProvider else {
            throw CloudSourceError.unsupportedSource
        }
        return try await treeProvider.discoverRoot(sourceURL: extracted, cookie: cookie)
    }

    func crawlTree(
        discovery: DriveFolderDiscovery,
        providerID: String?,
        cookie: String?,
        priorityState: DriveCrawlPriorityState?,
        onBatch: @escaping @Sendable (DriveFolderBatch) async -> Void
    ) async throws -> DriveCrawlSummary {
        let targetID = providerID ?? discovery.providerID ?? BuiltInProviderID.googleDrive
        guard let treeProvider = registry.provider(forID: targetID) as? any CloudProgressiveTreeProvider else {
            throw CloudSourceError.extensionUnavailable(name: targetID)
        }
        return try await treeProvider.crawlTree(
            discovery: discovery,
            cookie: cookie,
            priorityState: priorityState,
            onBatch: onBatch
        )
    }

    func fetchSingleTreeFolder(
        providerID: String?,
        folderID: String,
        resourceKey: String?,
        pathSegments: [String],
        depth: Int,
        rootTitle: String?,
        cookie: String?,
        extraJSON: String?
    ) async throws -> DriveFolderBatch {
        let targetID = providerID ?? BuiltInProviderID.googleDrive
        guard let treeProvider = registry.provider(forID: targetID) as? any CloudProgressiveTreeProvider else {
            throw CloudSourceError.extensionUnavailable(name: targetID)
        }
        return try await treeProvider.fetchSingleFolder(
            folderID: folderID,
            resourceKey: resourceKey,
            pathSegments: pathSegments,
            depth: depth,
            rootTitle: rootTitle,
            cookie: cookie,
            extraJSON: extraJSON
        )
    }

    func discoverGoogleDriveRoot(sourceURL: String, cookie: String?) async throws -> DriveFolderDiscovery {
        try await discoverTreeRoot(sourceURL: sourceURL, cookie: cookie, providerID: BuiltInProviderID.googleDrive)
    }

    func crawlGoogleDriveTree(
        discovery: DriveFolderDiscovery,
        cookie: String?,
        priorityState: DriveCrawlPriorityState?,
        onBatch: @escaping @Sendable (DriveFolderBatch) async -> Void
    ) async throws -> DriveCrawlSummary {
        try await crawlTree(
            discovery: discovery,
            providerID: BuiltInProviderID.googleDrive,
            cookie: cookie,
            priorityState: priorityState,
            onBatch: onBatch
        )
    }

    func fetchGoogleDriveSingleFolder(
        folderID: String,
        resourceKey: String?,
        pathSegments: [String],
        depth: Int,
        rootTitle: String?,
        cookie: String?
    ) async throws -> DriveFolderBatch {
        try await fetchSingleTreeFolder(
            providerID: BuiltInProviderID.googleDrive,
            folderID: folderID,
            resourceKey: resourceKey,
            pathSegments: pathSegments,
            depth: depth,
            rootTitle: rootTitle,
            cookie: cookie,
            extraJSON: nil
        )
    }

    func effectiveQualityID(
        for track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        overrideQualityID: String?
    ) -> String? {
        guard let provider = registry.provider(forTrack: track, root: root) else {
            return nil
        }
        return provider.effectiveQualityID(root: root, purpose: purpose, overrideQualityID: overrideQualityID)
    }

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool = false) async throws -> CloudStreamResult {
        try await resolveStream(track: track, root: root, purpose: .stream, qualityID: nil, forceRefresh: forceRefresh)
    }

    func resolveStream(
        track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        qualityID: String?,
        forceRefresh: Bool = false
    ) async throws -> CloudStreamResult {
        if let provider = registry.provider(forTrack: track, root: root) {
            return try await provider.resolveStream(
                track: track,
                root: root,
                purpose: purpose,
                qualityID: qualityID,
                forceRefresh: forceRefresh
            )
        }
        let pid = CloudProviderRegistry.providerID(for: track, root: root)
        if let pid {
            if pid.hasPrefix("ext.") {
                let displayName = root?.name ?? ExtensionStore.shared.installedName(forProviderID: pid) ?? pid
                throw CloudSourceError.extensionUnavailable(name: displayName)
            }
            if let fallbackExt = CloudProviderRegistry.legacyFallbackExtensionID(forKind: root?.effectiveCloudKind, providerID: pid) {
                let displayName = ExtensionStore.shared.installedName(forProviderID: fallbackExt) ?? fallbackExt
                throw CloudSourceError.extensionUnavailable(name: displayName)
            }
        }
        throw CloudSourceError.unsupportedSource
    }
}

// MARK: - Google Drive Client

actor GoogleDriveClient {
    enum Target: Equatable, Sendable {
        case folder(id: String, resourceKey: String?)
        case file(id: String, resourceKey: String?)
        case open(id: String, resourceKey: String?)
    }

    struct DriveItem: Equatable, Sendable {
        var id: String
        var name: String
        var mimeType: String
        var resourceKey: String?
        var isFolder: Bool {
            mimeType.caseInsensitiveCompare("application/vnd.google-apps.folder") == .orderedSame
        }
    }

    private struct CachedStream: Sendable {
        let result: CloudStreamResult
    }

    private let transport: any CloudHTTPTransport
    private let retryDelaysNanoseconds: [UInt64]
    private let oauthTokenProvider: (@Sendable (Bool) async throws -> String?)?
    private var streamCache: [String: CachedStream] = [:]
    private var cachedWorkingApiKey: String?

    static let userAgent = CloudProviderRegistry.defaultUserAgent
    static let maxDepth = 12
    static let maxFolders = 2000
    static let ivdTruncationThreshold = 50
    static let supportedAudioExtensions: Set<String> = FolderScanner.audioExtensions.union(["mp4", "ogg", "opus", "webm", "wma"])

    struct IVDParseResult: Equatable, Sendable {
        var items: [DriveItem]
        var nextPageToken: String?
    }

    private static let mimeToExtension: [String: String] = [
        "audio/mpeg": "mp3",
        "audio/mp3": "mp3",
        "audio/mp4": "m4a",
        "audio/x-m4a": "m4a",
        "audio/m4a": "m4a",
        "audio/aac": "aac",
        "audio/x-aac": "aac",
        "audio/flac": "flac",
        "audio/x-flac": "flac",
        "application/x-flac": "flac",
        "application/flac": "flac",
        "audio/wav": "wav",
        "audio/x-wav": "wav",
        "audio/wave": "wav",
        "audio/vnd.wave": "wav",
        "audio/aiff": "aiff",
        "audio/x-aiff": "aiff",
        "audio/x-caf": "caf",
        "audio/alac": "alac",
        "audio/ogg": "ogg",
        "application/ogg": "ogg",
        "audio/opus": "opus",
        "audio/webm": "webm",
        "audio/x-ms-wma": "wma"
    ]

    init(
        transport: any CloudHTTPTransport = URLSessionCloudTransport(),
        retryDelaysNanoseconds: [UInt64] = [500_000_000, 1_500_000_000],
        oauthTokenProvider: (@Sendable (Bool) async throws -> String?)? = nil
    ) {
        self.transport = transport
        self.retryDelaysNanoseconds = retryDelaysNanoseconds
        self.oauthTokenProvider = oauthTokenProvider
    }

    // MARK: URL & ID Parsing

    nonisolated static func parse(_ input: String) -> Target? {
        parseExtracted(CloudSourceInput.extract(from: input))
    }

    nonisolated static func parseExtracted(_ input: String) -> Target? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard trimmed.contains("/") || trimmed.contains(".") || trimmed.contains("?") else { return nil }

        let normalized = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        guard let components = URLComponents(string: normalized),
              let host = components.host?.lowercased() else { return nil }

        let query = lowercasedQueryDictionary(components.queryItems)
        let resourceKey = query["resourcekey"]

        if host == "drive.usercontent.google.com" {
            if let id = query["id"], isValidDriveID(id) {
                return .file(id: id, resourceKey: resourceKey)
            }
            return nil
        }

        guard host == "drive.google.com" else { return nil }

        let segments = components.path.split(separator: "/").map(String.init)
        let lowerPath = components.path.lowercased()

        // /embeddedfolderview?id=<ID> or /folderview?id=<ID>
        if lowerPath == "/embeddedfolderview" || lowerPath == "/folderview"
            || segments.last?.lowercased() == "embeddedfolderview"
            || segments.last?.lowercased() == "folderview" {
            if let id = query["id"], isValidDriveID(id) {
                return .folder(id: id, resourceKey: resourceKey)
            }
            return nil
        }

        // /uc?id=<ID>
        if lowerPath == "/uc" || segments == ["uc"] {
            if let id = query["id"], isValidDriveID(id) {
                return .file(id: id, resourceKey: resourceKey)
            }
            return nil
        }

        // /open?id=<ID>
        if lowerPath == "/open" || segments == ["open"] {
            if let id = query["id"], isValidDriveID(id) {
                return .open(id: id, resourceKey: resourceKey)
            }
            return nil
        }

        // Folders: /drive/folders/<ID>, /drive/u/<N>/folders/<ID>, /drive/mobile/folders/<ID>
        if segments.first?.lowercased() == "drive",
           let foldersIdx = segments.firstIndex(where: { $0.caseInsensitiveCompare("folders") == .orderedSame }),
           foldersIdx + 1 < segments.count {
            let folderID = segments[foldersIdx + 1]
            if isValidDriveID(folderID) {
                return .folder(id: folderID, resourceKey: resourceKey)
            }
        }

        // Files: /file/d/<ID>/view (or /edit, /preview, /u/<N>/file/d/<ID>/...)
        if let fileIdx = segments.firstIndex(where: { $0.caseInsensitiveCompare("file") == .orderedSame }),
           fileIdx + 2 < segments.count,
           segments[fileIdx + 1].caseInsensitiveCompare("d") == .orderedSame {
            let fileID = segments[fileIdx + 2]
            if isValidDriveID(fileID) {
                return .file(id: fileID, resourceKey: resourceKey)
            }
        }

        return nil
    }

    nonisolated static func isValidDriveID(_ value: String) -> Bool {
        guard value.count >= 3 else { return false }
        return value.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
    }

    nonisolated static func encodeTrackID(fileID: String, resourceKey: String?) -> String {
        if let rk = resourceKey?.trimmingCharacters(in: .whitespacesAndNewlines), !rk.isEmpty {
            return "\(fileID)#\(rk)"
        }
        return fileID
    }

    nonisolated static func decodeTrackID(_ trackID: String) -> (fileID: String, resourceKey: String?) {
        var raw = trackID
        if raw.hasPrefix("gdrive/") {
            raw = String(raw.dropFirst("gdrive/".count))
            if let dot = raw.lastIndex(of: ".") {
                raw = String(raw[..<dot])
            }
        }
        let parts = raw.split(separator: "#", maxSplits: 1).map(String.init)
        let fileID = parts.first ?? raw
        let rk = (parts.count > 1 && !parts[1].isEmpty) ? parts[1] : nil
        return (fileID, rk)
    }

    // MARK: Playlist / Folder Fetching

    func fetchPlaylist(sourceURL: String, cookie: String?, allowBareID: Bool = false) async throws -> CloudPlaylistResult {
        let discovery = try await discoverRoot(sourceURL: sourceURL, cookie: cookie, allowBareID: allowBareID)
        if let single = discovery.singleFileResult {
            return single
        }

        let collector = DriveTrackCollector(initialTracks: discovery.initialBatch.tracks)
        if !discovery.initialBatch.discoveredSubfolders.isEmpty {
            _ = try await crawlFolderTree(
                discovery: discovery,
                cookie: cookie,
                priorityState: nil,
                maxWorkers: 4
            ) { batch in
                await collector.append(batch.tracks)
            }
        }

        let tracks = await collector.allTracks()
        guard !tracks.isEmpty else {
            throw CloudSourceError.apiError(L10n.trNonisolated("No supported audio files were found in this Google Drive folder."))
        }

        return CloudPlaylistResult(
            kind: .cloudGoogleDrive,
            remoteID: discovery.rootID,
            title: discovery.rootTitle,
            tracks: tracks
        )
    }

    func discoverRoot(sourceURL: String, cookie: String?, allowBareID: Bool = false) async throws -> DriveFolderDiscovery {
        var target = Self.parse(sourceURL)
        if target == nil && allowBareID {
            let trimmed = sourceURL.trimmingCharacters(in: .whitespacesAndNewlines)
            if Self.isValidDriveID(trimmed) {
                target = .folder(id: trimmed, resourceKey: nil)
            }
        }
        guard let target else {
            throw CloudSourceError.invalidURL
        }

        switch target {
        case .folder(let id, let rk):
            return try await discoverFolder(rootID: id, resourceKey: rk, cookie: cookie, prefetchedRootHTML: nil)

        case .file(let id, let rk):
            let single = try await fetchSingleFile(fileID: id, resourceKey: rk, cookie: cookie)
            return DriveFolderDiscovery(
                rootID: id,
                resourceKey: rk,
                rootTitle: single.title,
                initialBatch: DriveFolderBatch(
                    folderID: id,
                    folderPath: "",
                    depth: 0,
                    rootTitle: single.title,
                    tracks: single.tracks,
                    discoveredSubfolders: [],
                    hadError: false
                ),
                singleFileResult: single
            )

        case .open(let id, let rk):
            return try await resolveOpenTarget(id: id, resourceKey: rk, cookie: cookie)
        }
    }

    private func resolveOpenTarget(id: String, resourceKey: String?, cookie: String?) async throws -> DriveFolderDiscovery {
        if let meta = try await fetchAuthorizedFileMetadata(fileID: id, resourceKey: resourceKey) {
            if meta.isFolder {
                return try await discoverFolder(
                    rootID: meta.id,
                    resourceKey: meta.resourceKey ?? resourceKey,
                    cookie: cookie,
                    prefetchedRootHTML: nil
                )
            } else {
                let single = try buildSingleFilePlaylist(
                    fileID: meta.id,
                    resourceKey: meta.resourceKey ?? resourceKey,
                    resolvedName: meta.name,
                    mimeType: meta.mimeType
                )
                return DriveFolderDiscovery(
                    rootID: meta.id,
                    resourceKey: meta.resourceKey ?? resourceKey,
                    rootTitle: single.title,
                    initialBatch: DriveFolderBatch(
                        folderID: meta.id,
                        folderPath: "",
                        depth: 0,
                        rootTitle: single.title,
                        tracks: single.tracks,
                        discoveredSubfolders: [],
                        hadError: false
                    ),
                    singleFileResult: single
                )
            }
        }

        let openURL = Self.driveURL(path: "/open", queryItems: [
            ("id", id),
            ("resourcekey", resourceKey)
        ])
        let response = try await sendGET(url: openURL, cookie: cookie)
        try Self.validateAccess(response: response)

        let html = String(data: response.data, encoding: .utf8) ?? ""
        let finalPath = response.finalURL?.path.lowercased() ?? ""
        let isFolderResponse = finalPath.contains("/folders/")
            || finalPath.contains("folderview")
            || html.contains("_DRIVE_ivd")
            || html.contains("flip-entry")

        if isFolderResponse {
            let resolvedFolderID: String
            if let finalURL = response.finalURL,
               case .folder(let parsedID, _) = Self.parseExtracted(finalURL.absoluteString) {
                resolvedFolderID = parsedID
            } else {
                resolvedFolderID = id
            }
            let prefetched = (html.contains("_DRIVE_ivd") || html.contains("flip-entry")) ? html : nil
            return try await discoverFolder(
                rootID: resolvedFolderID,
                resourceKey: resourceKey,
                cookie: cookie,
                prefetchedRootHTML: prefetched
            )
        }
        let single = try await fetchSingleFile(fileID: id, resourceKey: resourceKey, cookie: cookie)
        return DriveFolderDiscovery(
            rootID: id,
            resourceKey: resourceKey,
            rootTitle: single.title,
            initialBatch: DriveFolderBatch(
                folderID: id,
                folderPath: "",
                depth: 0,
                rootTitle: single.title,
                tracks: single.tracks,
                discoveredSubfolders: [],
                hadError: false
            ),
            singleFileResult: single
        )
    }

    private func discoverFolder(
        rootID: String,
        resourceKey: String?,
        cookie: String?,
        prefetchedRootHTML: String?
    ) async throws -> DriveFolderDiscovery {
        let batch = try await fetchSingleFolder(
            folderID: rootID,
            resourceKey: resourceKey,
            pathSegments: [],
            depth: 0,
            rootTitle: nil,
            cookie: cookie,
            prefetchedHTML: prefetchedRootHTML
        )
        let title = (batch.rootTitle?.isEmpty == false) ? batch.rootTitle! : "Google Drive"
        return DriveFolderDiscovery(
            rootID: rootID,
            resourceKey: resourceKey,
            rootTitle: title,
            initialBatch: batch,
            singleFileResult: nil
        )
    }

    func fetchSingleFolder(
        folderID: String,
        resourceKey: String? = nil,
        pathSegments: [String] = [],
        depth: Int = 0,
        rootTitle: String? = nil,
        cookie: String? = nil,
        prefetchedHTML: String? = nil
    ) async throws -> DriveFolderBatch {
        let folderResult = try await fetchSingleFolderEntries(
            folderID: folderID,
            resourceKey: resourceKey,
            cookie: cookie,
            prefetchedHTML: prefetchedHTML
        )
        let resolvedRootTitle = (depth == 0) ? (folderResult.title ?? rootTitle) : rootTitle
        let effectiveAlbum = pathSegments.last ?? resolvedRootTitle ?? folderResult.title ?? "Google Drive"
        let folderPrefix = pathSegments.isEmpty ? "" : pathSegments.joined(separator: "/") + "/"

        var tracks: [CloudTrackInfo] = []
        var subfolders: [DriveSubfolderRef] = []
        var usedTrackNames: Set<String> = []
        var usedFolderSegments: Set<String> = []

        for item in folderResult.items {
            if item.isFolder {
                if item.id != folderID, depth < Self.maxDepth {
                    var seg = Self.sanitizeRelativeComponent(item.name, fallback: item.id)
                    if !usedFolderSegments.insert(seg.lowercased()).inserted {
                        seg = Self.disambiguateComponent(seg, id: item.id, used: &usedFolderSegments)
                    }
                    subfolders.append(DriveSubfolderRef(
                        id: item.id,
                        resourceKey: item.resourceKey ?? resourceKey,
                        pathSegments: pathSegments + [seg],
                        depth: depth + 1
                    ))
                }
            } else if Self.isSupportedAudio(name: item.name, mimeType: item.mimeType) {
                var fileName = Self.sanitizedAudioFileName(name: item.name, mimeType: item.mimeType, fallbackID: item.id)
                if !usedTrackNames.insert(fileName.lowercased()).inserted {
                    fileName = Self.disambiguateFileName(fileName, id: item.id, used: &usedTrackNames)
                }
                let title = Self.stripAudioExtensionIfPresent(Self.sanitizeRelativeComponent(item.name, fallback: item.id))
                let remoteTrackID = Self.encodeTrackID(fileID: item.id, resourceKey: item.resourceKey)
                tracks.append(CloudTrackInfo(
                    remoteTrackID: remoteTrackID,
                    relativePath: folderPrefix + fileName,
                    title: title,
                    artist: "Google Drive",
                    album: effectiveAlbum,
                    trackNumber: nil,
                    duration: 0,
                    artworkURL: nil
                ))
            }
        }

        return DriveFolderBatch(
            folderID: folderID,
            folderPath: pathSegments.joined(separator: "/"),
            depth: depth,
            rootTitle: depth == 0 ? folderResult.title : nil,
            tracks: tracks,
            discoveredSubfolders: subfolders,
            hadError: false
        )
    }

    func crawlFolderTree(
        discovery: DriveFolderDiscovery,
        cookie: String?,
        priorityState: DriveCrawlPriorityState? = nil,
        maxWorkers: Int = 4,
        onBatch: @escaping @Sendable (DriveFolderBatch) async -> Void
    ) async throws -> DriveCrawlSummary {
        let rootTitle = discovery.rootTitle
        return try await CloudTreeCrawler.crawl(
            rootID: discovery.rootID,
            initialSubfolders: discovery.initialBatch.discoveredSubfolders,
            maxFolders: Self.maxFolders,
            maxWorkers: maxWorkers,
            priorityState: priorityState,
            fetchNode: { node in
                try await self.fetchSingleFolder(
                    folderID: node.id,
                    resourceKey: node.resourceKey,
                    pathSegments: node.pathSegments,
                    depth: node.depth,
                    rootTitle: rootTitle,
                    cookie: cookie,
                    prefetchedHTML: nil
                )
            },
            onBatch: onBatch
        )
    }

    private func fetchSingleFolderEntries(
        folderID: String,
        resourceKey: String?,
        cookie: String?,
        prefetchedHTML: String?
    ) async throws -> (title: String?, items: [DriveItem]) {
        if prefetchedHTML == nil,
           let oauthResult = try await fetchAuthorizedFolderEntries(folderID: folderID, resourceKey: resourceKey) {
            return oauthResult
        }

        let html: String
        if let prefetchedHTML {
            html = prefetchedHTML
        } else {
            let primaryURL = Self.driveURL(path: "/drive/folders/\(folderID)", queryItems: [
                ("resourcekey", resourceKey)
            ])
            let response = try await sendGET(url: primaryURL, cookie: cookie)
            try Self.validateAccess(response: response)
            html = String(data: response.data, encoding: .utf8) ?? ""
        }

        var title = Self.extractTitle(from: html)
        guard let ivdResult = Self.parseDriveIVDWithPagination(from: html) else {
            // Fallback: embeddedfolderview#list when _DRIVE_ivd is absent or unparseable
            return try await fetchEmbeddedFolderView(
                folderID: folderID,
                resourceKey: resourceKey,
                cookie: cookie,
                existingTitle: title
            )
        }

        var items = ivdResult.items
        var seenIDs = Set(items.map(\.id))

        let initialToken = ivdResult.nextPageToken.flatMap { $0.isEmpty ? nil : $0 }
        let shouldPaginate = (initialToken != nil) || (items.count >= Self.ivdTruncationThreshold)
        guard shouldPaginate else {
            return (title, items)
        }

        var candidateKeys: [String] = []
        if let cached = cachedWorkingApiKey, !cached.isEmpty {
            candidateKeys.append(cached)
        }
        for key in Self.extractDriveApiKeys(from: html) where !candidateKeys.contains(key) {
            candidateKeys.append(key)
        }

        var needsEmbeddedFallback = candidateKeys.isEmpty
        if !candidateKeys.isEmpty {
            var activeKey: String?
            var preferV3 = false
            var pageToken: String? = initialToken

            for key in candidateKeys {
                try Task.checkCancellation()
                if let first = try await fetchDriveApiPageWithFallback(
                    folderID: folderID,
                    resourceKey: resourceKey,
                    pageToken: pageToken,
                    apiKey: key,
                    cookie: cookie,
                    preferV3: false
                ) {
                    activeKey = key
                    cachedWorkingApiKey = key
                    preferV3 = first.usedV3
                    for item in first.items where seenIDs.insert(item.id).inserted {
                        items.append(item)
                    }
                    pageToken = first.nextPageToken
                    break
                }
            }

            if let activeKey {
                var seenTokens = Set<String>()
                if let initialToken { seenTokens.insert(initialToken) }
                var pageCount = 1
                let maxPages = 100

                while let tok = pageToken, !tok.isEmpty,
                      pageCount < maxPages,
                      seenTokens.insert(tok).inserted {
                    try Task.checkCancellation()
                    pageCount += 1

                    guard let pageResult = try await fetchDriveApiPageWithFallback(
                        folderID: folderID,
                        resourceKey: resourceKey,
                        pageToken: tok,
                        apiKey: activeKey,
                        cookie: cookie,
                        preferV3: preferV3
                    ) else {
                        needsEmbeddedFallback = true
                        break
                    }

                    preferV3 = preferV3 || pageResult.usedV3
                    for item in pageResult.items where seenIDs.insert(item.id).inserted {
                        items.append(item)
                    }
                    pageToken = pageResult.nextPageToken
                }
            } else {
                needsEmbeddedFallback = true
            }
        }

        if needsEmbeddedFallback {
            do {
                let embedded = try await fetchEmbeddedFolderView(
                    folderID: folderID,
                    resourceKey: resourceKey,
                    cookie: cookie,
                    existingTitle: title
                )
                if title == nil { title = embedded.title }
                for item in embedded.items where seenIDs.insert(item.id).inserted {
                    items.append(item)
                }
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if items.isEmpty { throw error }
            }
        }

        return (title, items)
    }

    private func fetchEmbeddedFolderView(
        folderID: String,
        resourceKey: String?,
        cookie: String?,
        existingTitle: String?
    ) async throws -> (title: String?, items: [DriveItem]) {
        let fallbackURL = Self.driveURL(path: "/embeddedfolderview", queryItems: [
            ("id", folderID),
            ("resourcekey", resourceKey)
        ], fragment: "list")
        let fallbackResponse = try await sendGET(url: fallbackURL, cookie: cookie)
        try Self.validateAccess(response: fallbackResponse)
        let fallbackHTML = String(data: fallbackResponse.data, encoding: .utf8) ?? ""
        let resolvedTitle = existingTitle ?? Self.extractTitle(from: fallbackHTML)
        let embeddedItems = Self.parseEmbeddedFolderView(from: fallbackHTML)
        return (resolvedTitle, embeddedItems)
    }

    private func fetchDriveApiPageWithFallback(
        folderID: String,
        resourceKey: String?,
        pageToken: String?,
        apiKey: String,
        cookie: String?,
        preferV3: Bool
    ) async throws -> (items: [DriveItem], nextPageToken: String?, usedV3: Bool)? {
        if !preferV3 {
            do {
                let res = try await fetchDriveV2BetaPage(
                    folderID: folderID,
                    resourceKey: resourceKey,
                    pageToken: pageToken,
                    apiKey: apiKey,
                    cookie: cookie
                )
                return (res.items, res.nextPageToken, false)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                // Fall back to Drive v3 below.
            }
        }
        do {
            let res = try await fetchDriveV3Page(
                folderID: folderID,
                resourceKey: resourceKey,
                pageToken: pageToken,
                apiKey: apiKey,
                cookie: cookie
            )
            return (res.items, res.nextPageToken, true)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return nil
        }
    }

    private func fetchDriveV2BetaPage(
        folderID: String,
        resourceKey: String?,
        pageToken: String?,
        apiKey: String,
        cookie: String?
    ) async throws -> (items: [DriveItem], nextPageToken: String?) {
        var components = URLComponents(string: "https://clients6.google.com/drive/v2beta/files")!
        var queryItems = [
            URLQueryItem(name: "openDrive", value: "true"),
            URLQueryItem(name: "reason", value: "102"),
            URLQueryItem(name: "q", value: "'\(folderID)' in parents and trashed = false"),
            URLQueryItem(name: "fields", value: "nextPageToken,items(id,title,mimeType,resourceKey,labels(trashed))"),
            URLQueryItem(name: "maxResults", value: "1000"),
            URLQueryItem(name: "supportsAllDrives", value: "true"),
            URLQueryItem(name: "includeItemsFromAllDrives", value: "true"),
            URLQueryItem(name: "key", value: apiKey)
        ]
        if let pageToken, !pageToken.isEmpty {
            queryItems.append(URLQueryItem(name: "pageToken", value: pageToken))
        }
        components.queryItems = queryItems
        let req = Self.driveAPIRequest(
            url: components.url!,
            folderID: folderID,
            resourceKey: resourceKey,
            apiKey: apiKey,
            cookie: cookie
        )
        let response = try await sendRequestWithRetry(req)
        try Self.validateAccess(response: response)
        guard let parsed = Self.parseDriveAPIListResponse(data: response.data, itemsKey: "items", preferredNameKey: "title") else {
            throw CloudSourceError.networkError(L10n.trNonisolated("Invalid server response."))
        }
        return parsed
    }

    private func fetchDriveV3Page(
        folderID: String,
        resourceKey: String?,
        pageToken: String?,
        apiKey: String,
        cookie: String?
    ) async throws -> (items: [DriveItem], nextPageToken: String?) {
        var components = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        var queryItems = [
            URLQueryItem(name: "q", value: "'\(folderID)' in parents and trashed = false"),
            URLQueryItem(name: "pageSize", value: "1000"),
            URLQueryItem(name: "fields", value: "nextPageToken,files(id,name,mimeType,resourceKey,size)"),
            URLQueryItem(name: "supportsAllDrives", value: "true"),
            URLQueryItem(name: "includeItemsFromAllDrives", value: "true"),
            URLQueryItem(name: "key", value: apiKey)
        ]
        if let pageToken, !pageToken.isEmpty {
            queryItems.append(URLQueryItem(name: "pageToken", value: pageToken))
        }
        components.queryItems = queryItems
        let req = Self.driveAPIRequest(
            url: components.url!,
            folderID: folderID,
            resourceKey: resourceKey,
            apiKey: apiKey,
            cookie: cookie
        )
        let response = try await sendRequestWithRetry(req)
        try Self.validateAccess(response: response)
        guard let parsed = Self.parseDriveAPIListResponse(data: response.data, itemsKey: "files", preferredNameKey: "name") else {
            throw CloudSourceError.networkError(L10n.trNonisolated("Invalid server response."))
        }
        return parsed
    }

    private func fetchSingleFile(fileID: String, resourceKey: String?, cookie: String?) async throws -> CloudPlaylistResult {
        if let meta = try await fetchAuthorizedFileMetadata(fileID: fileID, resourceKey: resourceKey) {
            return try buildSingleFilePlaylist(
                fileID: meta.id,
                resourceKey: meta.resourceKey ?? resourceKey,
                resolvedName: meta.name,
                mimeType: meta.mimeType
            )
        }

        var fileName: String?
        var mimeType: String?

        let viewURL = Self.driveURL(path: "/file/d/\(fileID)/view", queryItems: [
            ("resourcekey", resourceKey)
        ])
        let viewResponse = try await sendGET(url: viewURL, cookie: cookie)
        try Self.validateAccess(response: viewResponse)
        if let viewHTML = String(data: viewResponse.data, encoding: .utf8) {
            fileName = Self.extractTitle(from: viewHTML)
            mimeType = Self.extractHTMLMimeType(from: viewHTML)
        }

        let hasAudioExt = fileName.map { Self.supportedAudioExtension(fromFilename: $0) != nil } ?? false
        let hasAudioMime = mimeType.map { $0.lowercased().hasPrefix("audio/") } ?? false
        if !hasAudioExt && !hasAudioMime {
            let probeURL = Self.downloadURL(fileID: fileID, resourceKey: resourceKey, useUserContent: true)
            let probeResponse = try await sendGET(url: probeURL, cookie: cookie, range: "bytes=0-0")
            try Self.validateAccess(response: probeResponse)
            if let cd = probeResponse.header("Content-Disposition"),
               let dispName = Self.parseContentDispositionFilename(cd), !dispName.isEmpty {
                fileName = dispName
            }
            if let ct = probeResponse.header("Content-Type"),
               !ct.lowercased().contains("text/html") {
                mimeType = ct.split(separator: ";").first.map { String($0).trimmingCharacters(in: .whitespacesAndNewlines) }
            }
        }

        let resolvedName = fileName?.trimmingCharacters(in: .whitespacesAndNewlines)
        return try buildSingleFilePlaylist(
            fileID: fileID,
            resourceKey: resourceKey,
            resolvedName: resolvedName,
            mimeType: mimeType
        )
    }

    private func buildSingleFilePlaylist(
        fileID: String,
        resourceKey: String?,
        resolvedName: String?,
        mimeType: String?
    ) throws -> CloudPlaylistResult {
        if let mimeType, !Self.isSupportedAudio(name: resolvedName ?? "", mimeType: mimeType) {
            if !mimeType.lowercased().contains("octet-stream") || (resolvedName != nil && !(resolvedName!).isEmpty && Self.supportedAudioExtension(fromFilename: resolvedName!) == nil) {
                throw CloudSourceError.apiError(L10n.trNonisolated("This Google Drive file is not a supported audio format."))
            }
        } else if let resolvedName, !resolvedName.isEmpty, !Self.isSupportedAudio(name: resolvedName, mimeType: mimeType ?? "") {
            throw CloudSourceError.apiError(L10n.trNonisolated("This Google Drive file is not a supported audio format."))
        }

        let relativeFileName = Self.sanitizedAudioFileName(
            name: resolvedName ?? "",
            mimeType: mimeType ?? "",
            fallbackID: fileID
        )
        let title = Self.stripAudioExtensionIfPresent(
            Self.sanitizeRelativeComponent(resolvedName?.isEmpty == false ? resolvedName! : fileID, fallback: fileID)
        )
        let track = CloudTrackInfo(
            remoteTrackID: Self.encodeTrackID(fileID: fileID, resourceKey: resourceKey),
            relativePath: relativeFileName,
            title: title,
            artist: "Google Drive",
            album: title,
            trackNumber: 1,
            duration: 0,
            artworkURL: nil
        )
        return CloudPlaylistResult(
            kind: .cloudGoogleDrive,
            remoteID: fileID,
            title: title,
            tracks: [track]
        )
    }

    // MARK: Stream Resolution

    func resolveStream(
        trackID: String,
        cookie: String?,
        fallbackExtension: String? = nil,
        forceRefresh: Bool = false
    ) async throws -> CloudStreamResult {
        if !forceRefresh, let cached = streamCache[trackID], cached.result.expiresAt > Date().addingTimeInterval(30) {
            return cached.result
        }

        let (fileID, resourceKey) = Self.decodeTrackID(trackID)
        if let oauthResult = try await resolveAuthorizedStream(
            fileID: fileID,
            resourceKey: resourceKey,
            trackID: trackID,
            fallbackExtension: fallbackExtension
        ) {
            return oauthResult
        }

        var headers: [String: String] = [
            "User-Agent": Self.userAgent,
            "Accept": "*/*"
        ]
        if let cleanCookie = Self.normalizedCookie(cookie) {
            headers["Cookie"] = cleanCookie
        }

        let candidates = [
            Self.downloadURL(fileID: fileID, resourceKey: resourceKey, useUserContent: true),
            Self.downloadURL(fileID: fileID, resourceKey: resourceKey, useUserContent: false)
        ]

        var lastError: Error = CloudSourceError.noPlayableStream
        for candidate in candidates {
            do {
                let response = try await sendGET(url: candidate, cookie: cookie, range: "bytes=0-0")
                try Self.validateAccess(response: response)

                if let ct = response.header("Content-Type")?.lowercased(), ct.contains("text/html") {
                    let html = String(data: response.data, encoding: .utf8) ?? ""
                    if Self.isSignInHTML(html) {
                        throw CloudSourceError.apiError(Self.accessDeniedMessage)
                    }
                    if let confirmedURL = Self.parseDownloadConfirmForm(
                        html: html,
                        baseURL: response.finalURL ?? candidate
                    ) {
                        let confirmedResponse = try await sendGET(url: confirmedURL, cookie: cookie, range: "bytes=0-0")
                        try Self.validateAccess(response: confirmedResponse)
                        if let cct = confirmedResponse.header("Content-Type")?.lowercased(), cct.contains("text/html") {
                            let chtml = String(data: confirmedResponse.data, encoding: .utf8) ?? ""
                            if Self.isSignInHTML(chtml) {
                                throw CloudSourceError.apiError(Self.accessDeniedMessage)
                            }
                            continue
                        }
                        if confirmedResponse.statusCode == 200 || confirmedResponse.statusCode == 206 {
                            let resolvedURL = confirmedResponse.finalURL ?? confirmedURL
                            let ext = Self.detectStreamExtension(
                                response: confirmedResponse,
                                fallbackExtension: fallbackExtension
                            )
                            let result = CloudStreamResult(
                                url: resolvedURL,
                                headers: headers,
                                fileExtension: ext,
                                expiresAt: Date().addingTimeInterval(1800)
                            )
                            streamCache[trackID] = CachedStream(result: result)
                            return result
                        }
                    }
                    continue
                }

                if response.statusCode == 200 || response.statusCode == 206 {
                    let resolvedURL = response.finalURL ?? candidate
                    let ext = Self.detectStreamExtension(
                        response: response,
                        fallbackExtension: fallbackExtension
                    )
                    let result = CloudStreamResult(
                        url: resolvedURL,
                        headers: headers,
                        fileExtension: ext,
                        expiresAt: Date().addingTimeInterval(1800)
                    )
                    streamCache[trackID] = CachedStream(result: result)
                    return result
                }
            } catch let error as CloudSourceError {
                lastError = error
                if case .apiError = error {
                    // Preserve access denied errors if fallback also fails.
                }
            } catch {
                lastError = error
            }
        }

        throw lastError
    }

    // MARK: HTTP Helpers

    private func sendGET(url: URL, cookie: String?, range: String? = nil) async throws -> HTTPResponseData {
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        if let range {
            req.setValue(range, forHTTPHeaderField: "Range")
            req.setValue("*/*", forHTTPHeaderField: "Accept")
        } else {
            req.setValue("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", forHTTPHeaderField: "Accept")
        }
        if let cleanCookie = Self.normalizedCookie(cookie) {
            req.setValue(cleanCookie, forHTTPHeaderField: "Cookie")
        }
        return try await sendRequestWithRetry(req)
    }

    // MARK: OAuth 2.0 Bearer Helpers

    private func sendAuthorizedDriveGET(
        url: URL,
        resourceKey: String?,
        folderIDForRK: String? = nil,
        range: String? = nil
    ) async throws -> (response: HTTPResponseData, token: String)? {
        guard let oauthTokenProvider,
              let firstToken = try await oauthTokenProvider(false),
              !firstToken.isEmpty else {
            return nil
        }

        func makeReq(token: String) -> URLRequest {
            var req = URLRequest(url: url)
            req.httpMethod = "GET"
            req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            if let range {
                req.setValue(range, forHTTPHeaderField: "Range")
                req.setValue("*/*", forHTTPHeaderField: "Accept")
            } else {
                req.setValue("application/json", forHTTPHeaderField: "Accept")
            }
            if let rk = Self.normalizedCookie(resourceKey) {
                let targetID = folderIDForRK ?? ""
                if !targetID.isEmpty {
                    req.setValue("\(targetID)/\(rk)", forHTTPHeaderField: "X-Goog-Drive-Resource-Keys")
                }
            }
            return req
        }

        var activeToken = firstToken
        var response = try await sendRequestWithRetry(makeReq(token: activeToken))
        if response.statusCode == 401,
           let refreshedToken = try await oauthTokenProvider(true),
           !refreshedToken.isEmpty {
            activeToken = refreshedToken
            response = try await sendRequestWithRetry(makeReq(token: activeToken))
        }
        return (response, activeToken)
    }

    private func fetchAuthorizedFileMetadata(fileID: String, resourceKey: String?) async throws -> DriveItem? {
        var components = URLComponents(string: "https://www.googleapis.com/drive/v3/files/\(fileID)")!
        components.queryItems = [
            URLQueryItem(name: "fields", value: "id,name,mimeType,resourceKey,size"),
            URLQueryItem(name: "supportsAllDrives", value: "true")
        ]
        guard let authorized = try await sendAuthorizedDriveGET(
            url: components.url!,
            resourceKey: resourceKey,
            folderIDForRK: fileID
        ) else {
            return nil
        }
        if authorized.response.statusCode == 403 || authorized.response.statusCode == 404 {
            return nil
        }
        guard (200..<300).contains(authorized.response.statusCode),
              let dict = try? JSONSerialization.jsonObject(with: authorized.response.data) as? [String: Any],
              let id = dict["id"] as? String,
              let name = dict["name"] as? String,
              let mime = dict["mimeType"] as? String else {
            throw CloudSourceError.networkError(L10n.trNonisolated("Invalid server response."))
        }
        return DriveItem(
            id: id,
            name: name,
            mimeType: mime,
            resourceKey: (dict["resourceKey"] as? String) ?? resourceKey
        )
    }

    private func fetchAuthorizedFolderEntries(
        folderID: String,
        resourceKey: String?
    ) async throws -> (title: String?, items: [DriveItem])? {
        guard oauthTokenProvider != nil else { return nil }
        guard let folderMeta = try await fetchAuthorizedFileMetadata(fileID: folderID, resourceKey: resourceKey) else {
            return nil
        }

        var items: [DriveItem] = []
        var seenIDs = Set<String>()
        var pageToken: String? = nil
        var seenTokens = Set<String>()

        repeat {
            try Task.checkCancellation()
            var components = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
            var queryItems = [
                URLQueryItem(name: "q", value: "'\(folderID)' in parents and trashed = false"),
                URLQueryItem(name: "pageSize", value: "1000"),
                URLQueryItem(name: "fields", value: "nextPageToken,files(id,name,mimeType,resourceKey,size)"),
                URLQueryItem(name: "supportsAllDrives", value: "true"),
                URLQueryItem(name: "includeItemsFromAllDrives", value: "true")
            ]
            if let pageToken, !pageToken.isEmpty {
                queryItems.append(URLQueryItem(name: "pageToken", value: pageToken))
            }
            components.queryItems = queryItems
            guard let authorized = try await sendAuthorizedDriveGET(
                url: components.url!,
                resourceKey: resourceKey,
                folderIDForRK: folderID
            ) else {
                return nil
            }
            try Self.validateAccess(response: authorized.response)
            guard let parsed = Self.parseDriveAPIListResponse(
                data: authorized.response.data,
                itemsKey: "files",
                preferredNameKey: "name"
            ) else {
                throw CloudSourceError.networkError(L10n.trNonisolated("Invalid server response."))
            }
            for item in parsed.items where seenIDs.insert(item.id).inserted {
                items.append(item)
            }
            if let next = parsed.nextPageToken, !next.isEmpty, seenTokens.insert(next).inserted {
                pageToken = next
            } else {
                pageToken = nil
            }
        } while pageToken != nil

        return (folderMeta.name, items)
    }

    private func resolveAuthorizedStream(
        fileID: String,
        resourceKey: String?,
        trackID: String,
        fallbackExtension: String?
    ) async throws -> CloudStreamResult? {
        var components = URLComponents(string: "https://www.googleapis.com/drive/v3/files/\(fileID)")!
        components.queryItems = [
            URLQueryItem(name: "alt", value: "media"),
            URLQueryItem(name: "supportsAllDrives", value: "true")
        ]
        guard let mediaURL = components.url,
              let authorized = try await sendAuthorizedDriveGET(
                  url: mediaURL,
                  resourceKey: resourceKey,
                  folderIDForRK: fileID,
                  range: "bytes=0-0"
              ) else {
            return nil
        }

        if authorized.response.statusCode == 403 || authorized.response.statusCode == 404 {
            return nil
        }
        guard authorized.response.statusCode == 200 || authorized.response.statusCode == 206 else {
            throw CloudSourceError.networkError(L10n.trNonisolated("Google Drive returned HTTP %@.", String(authorized.response.statusCode)))
        }
        let resolvedURL = authorized.response.finalURL ?? mediaURL
        let ext = Self.detectStreamExtension(
            response: authorized.response,
            fallbackExtension: fallbackExtension
        )
        let headers: [String: String] = [
            "User-Agent": Self.userAgent,
            "Accept": "*/*",
            "Authorization": "Bearer \(authorized.token)"
        ]
        let result = CloudStreamResult(
            url: resolvedURL,
            headers: headers,
            fileExtension: ext,
            expiresAt: Date().addingTimeInterval(1800)
        )
        streamCache[trackID] = CachedStream(result: result)
        return result
    }

    private func sendRequestWithRetry(_ request: URLRequest) async throws -> HTTPResponseData {
        var attempt = 0
        while true {
            try Task.checkCancellation()
            let response = try await transport.send(request)
            let isRetryable = response.statusCode == 429 || (500..<600).contains(response.statusCode)
            if isRetryable, attempt < retryDelaysNanoseconds.count {
                let delay = retryDelaysNanoseconds[attempt]
                attempt += 1
                if delay > 0 {
                    try await Task.sleep(nanoseconds: delay)
                }
                continue
            }
            return response
        }
    }

    nonisolated private static func driveAPIRequest(
        url: URL,
        folderID: String,
        resourceKey: String?,
        apiKey: String,
        cookie: String?
    ) -> URLRequest {
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        req.setValue("https://drive.google.com/", forHTTPHeaderField: "Referer")
        req.setValue("https://drive.google.com", forHTTPHeaderField: "Origin")
        req.setValue(apiKey, forHTTPHeaderField: "X-Goog-Api-Key")
        if let rk = normalizedCookie(resourceKey) {
            req.setValue("\(folderID)/\(rk)", forHTTPHeaderField: "X-Goog-Drive-Resource-Keys")
        }
        if let cleanCookie = normalizedCookie(cookie) {
            req.setValue(cleanCookie, forHTTPHeaderField: "Cookie")
        }
        return req
    }

    nonisolated static func parseDriveAPIListResponse(
        data: Data,
        itemsKey: String,
        preferredNameKey: String
    ) -> (items: [DriveItem], nextPageToken: String?)? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawItems = root[itemsKey] as? [[String: Any]] else {
            return nil
        }
        var items: [DriveItem] = []
        var seen = Set<String>()
        for dict in rawItems {
            if let trashed = dict["trashed"] as? Bool, trashed { continue }
            if let labels = dict["labels"] as? [String: Any], let trashed = labels["trashed"] as? Bool, trashed { continue }
            guard let id = dict["id"] as? String, isValidDriveID(id) else { continue }
            let rawName = (dict[preferredNameKey] as? String)
                ?? (dict["title"] as? String)
                ?? (dict["name"] as? String)
            guard let name = rawName, !name.isEmpty,
                  let mime = dict["mimeType"] as? String, mime.contains("/") else {
                continue
            }
            if seen.insert(id).inserted {
                let rk = (dict["resourceKey"] as? String) ?? (dict["resourcekey"] as? String)
                items.append(DriveItem(id: id, name: name, mimeType: mime, resourceKey: rk))
            }
        }
        let rawToken = (root["nextPageToken"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let nextToken = (rawToken?.isEmpty == false) ? rawToken : nil
        return (items, nextToken)
    }

    nonisolated static var accessDeniedMessage: String {
        L10n.trNonisolated("Cannot access this Google Drive link. Make sure the link is shared with 'Anyone with the link'.")
    }

    nonisolated private static func validateAccess(response: HTTPResponseData) throws {
        if let finalHost = response.finalURL?.host?.lowercased(),
           finalHost == "accounts.google.com" || finalHost.hasSuffix(".accounts.google.com") {
            throw CloudSourceError.apiError(accessDeniedMessage)
        }
        if let location = response.header("Location")?.lowercased(),
           location.contains("accounts.google.com") {
            throw CloudSourceError.apiError(accessDeniedMessage)
        }
        if response.statusCode == 401 || response.statusCode == 403 || response.statusCode == 404 {
            throw CloudSourceError.apiError(accessDeniedMessage)
        }
        guard (200..<400).contains(response.statusCode) else {
            throw CloudSourceError.networkError(L10n.trNonisolated("Google Drive returned HTTP %@.", String(response.statusCode)))
        }
        if let ct = response.header("Content-Type")?.lowercased(), ct.contains("text/html"),
           let html = String(data: response.data, encoding: .utf8),
           isSignInHTML(html) {
            throw CloudSourceError.apiError(accessDeniedMessage)
        }
    }

    nonisolated private static func isSignInHTML(_ html: String) -> Bool {
        if extractDriveIVDJSONString(from: html) != nil { return false }
        if html.range(of: "flip-entry", options: .caseInsensitive) != nil { return false }
        if html.range(of: "uc-download-link", options: .caseInsensitive) != nil { return false }
        if let base = URL(string: "https://drive.google.com"),
           parseDownloadConfirmForm(html: html, baseURL: base) != nil {
            return false
        }
        let lower = html.lowercased()
        return lower.contains("accounts.google.com/servicelogin")
            || lower.contains("sign in - google accounts")
            || lower.contains("servicelogin?")
            || lower.contains("google drive: sign-in")
            || lower.contains("you need access")
    }

    nonisolated private static func normalizedCookie(_ cookie: String?) -> String? {
        guard let trimmed = cookie?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }

    nonisolated private static func driveURL(path: String, queryItems: [(String, String?)], fragment: String? = nil) -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "drive.google.com"
        components.path = path
        let items = queryItems.compactMap { name, value -> URLQueryItem? in
            guard let value, !value.isEmpty else { return nil }
            return URLQueryItem(name: name, value: value)
        }
        if !items.isEmpty { components.queryItems = items }
        if let fragment, !fragment.isEmpty { components.fragment = fragment }
        return components.url!
    }

    nonisolated private static func downloadURL(fileID: String, resourceKey: String?, useUserContent: Bool) -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = useUserContent ? "drive.usercontent.google.com" : "drive.google.com"
        components.path = useUserContent ? "/download" : "/uc"
        var items = [
            URLQueryItem(name: "id", value: fileID),
            URLQueryItem(name: "export", value: "download"),
            URLQueryItem(name: "confirm", value: "t")
        ]
        if let resourceKey, !resourceKey.isEmpty {
            items.append(URLQueryItem(name: "resourcekey", value: resourceKey))
        }
        components.queryItems = items
        return components.url!
    }

    nonisolated private static func lowercasedQueryDictionary(_ items: [URLQueryItem]?) -> [String: String] {
        var result: [String: String] = [:]
        for item in items ?? [] {
            guard let value = item.value, !value.isEmpty else { continue }
            let key = item.name.lowercased()
            if result[key] == nil {
                result[key] = value
            }
        }
        return result
    }

    // MARK: HTML & JS Parsing (Static & Testable)

    nonisolated static func extractTitle(from html: String) -> String? {
        // 1. <meta property="og:title" content="...">
        if let ogRegex = try? NSRegularExpression(
            pattern: #"<meta\b[^>]*property=["']og:title["'][^>]*content=["']([^"']*)["']|<meta\b[^>]*content=["']([^"']*)["'][^>]*property=["']og:title["']"#,
            options: [.caseInsensitive]
        ) {
            let range = NSRange(html.startIndex..<html.endIndex, in: html)
            if let match = ogRegex.firstMatch(in: html, range: range) {
                for g in 1...2 {
                    if let r = Range(match.range(at: g), in: html) {
                        let cleaned = cleanDriveTitle(decodeHTMLEntities(String(html[r])))
                        if !cleaned.isEmpty { return cleaned }
                    }
                }
            }
        }

        // 2. <title>...</title>
        if let titleRegex = try? NSRegularExpression(pattern: #"<title\b[^>]*>([\s\S]*?)</title>"#, options: [.caseInsensitive]) {
            let range = NSRange(html.startIndex..<html.endIndex, in: html)
            if let match = titleRegex.firstMatch(in: html, range: range),
               let r = Range(match.range(at: 1), in: html) {
                let cleaned = cleanDriveTitle(decodeHTMLEntities(String(html[r])))
                if !cleaned.isEmpty { return cleaned }
            }
        }

        return nil
    }

    nonisolated private static func cleanDriveTitle(_ raw: String) -> String {
        var title = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        for suffix in [" - Google Drive", " - Google 云端硬盘", " – Google Drive"] {
            if title.hasSuffix(suffix) {
                title = String(title.dropLast(suffix.count)).trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        let lower = title.lowercased()
        if lower == "google drive" || lower == "meet google drive" || lower == "error" || lower.hasPrefix("sign in") {
            return ""
        }
        return title
    }

    nonisolated private static func extractHTMLMimeType(from html: String) -> String? {
        guard let regex = try? NSRegularExpression(
            pattern: #""mimeType"\s*:\s*"([^"]+)""#,
            options: [.caseInsensitive]
        ) else { return nil }
        let range = NSRange(html.startIndex..<html.endIndex, in: html)
        guard let match = regex.firstMatch(in: html, range: range),
              let r = Range(match.range(at: 1), in: html) else { return nil }
        return String(html[r])
    }

    nonisolated static func extractDriveApiKey(from html: String) -> String? {
        extractDriveApiKeys(from: html).first
    }

    nonisolated static func extractDriveApiKeys(from html: String) -> [String] {
        guard let regex = try? NSRegularExpression(pattern: #"AIzaSy[0-9A-Za-z_-]{33}"#, options: []) else {
            return []
        }
        let range = NSRange(html.startIndex..<html.endIndex, in: html)
        var result: [String] = []
        var seen = Set<String>()
        for match in regex.matches(in: html, range: range) {
            guard let r = Range(match.range, in: html) else { continue }
            let key = String(html[r])
            if seen.insert(key).inserted {
                result.append(key)
            }
        }
        return result
    }

    nonisolated static func parseDriveIVD(from html: String) -> [DriveItem] {
        parseDriveIVDWithPagination(from: html)?.items ?? []
    }

    nonisolated static func parseDriveIVDWithPagination(from html: String) -> IVDParseResult? {
        guard let jsonString = extractDriveIVDJSONString(from: html),
              let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) else {
            return nil
        }

        var nextPageToken: String?
        if let rootArray = json as? [Any], rootArray.count > 1 {
            let isSingleItemTuple = rootArray.count >= 4
                && (rootArray[0] as? String).map(isValidDriveID) == true
                && ((rootArray[3] as? String)?.contains("/") == true)
            if !isSingleItemTuple,
               let rawToken = (rootArray[1] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
               !rawToken.isEmpty {
                nextPageToken = rawToken
            }
        }

        var items: [DriveItem] = []
        var seen = Set<String>()

        func collect(_ node: Any, depth: Int) {
            guard depth <= 4 else { return }
            if let dict = node as? [String: Any],
               let id = dict["id"] as? String, isValidDriveID(id),
               let name = (dict["name"] as? String) ?? (dict["title"] as? String), !name.isEmpty,
               let mime = dict["mimeType"] as? String, mime.contains("/") {
                if let trashed = dict["trashed"] as? Bool, trashed { return }
                if let labels = dict["labels"] as? [String: Any], let trashed = labels["trashed"] as? Bool, trashed { return }
                if seen.insert(id).inserted {
                    let rk = (dict["resourceKey"] as? String) ?? (dict["resourcekey"] as? String)
                    items.append(DriveItem(id: id, name: name, mimeType: mime, resourceKey: rk))
                }
                return
            }
            guard let array = node as? [Any] else { return }
            if array.count >= 4,
               let id = array[0] as? String, isValidDriveID(id),
               let name = array[2] as? String, !name.isEmpty,
               let mime = array[3] as? String, mime.contains("/") {
                if seen.insert(id).inserted {
                    let rk = extractResourceKey(fromItemArray: array)
                    items.append(DriveItem(id: id, name: name, mimeType: mime, resourceKey: rk))
                }
                return
            }
            for element in array {
                collect(element, depth: depth + 1)
            }
        }

        collect(json, depth: 0)
        return IVDParseResult(items: items, nextPageToken: nextPageToken)
    }

    nonisolated private static func extractResourceKey(fromItemArray array: [Any]) -> String? {
        guard array.count > 4 else { return nil }
        for (offset, elem) in array.dropFirst(4).enumerated() {
            if let dict = elem as? [String: Any],
               let rk = (dict["resourceKey"] as? String) ?? (dict["resourcekey"] as? String),
               !rk.isEmpty {
                return rk
            }
            if let str = elem as? String, !str.isEmpty {
                if str.hasPrefix("0-"), str.count >= 6, isValidDriveID(str) {
                    return str
                }
                if array.count == 5, offset == 0, !str.contains("/"), !str.contains(" "), !str.hasPrefix("http") {
                    return str
                }
            }
        }
        return nil
    }

    nonisolated static func extractDriveIVDJSONString(from html: String) -> String? {
        let markers = ["window['_DRIVE_ivd']", "window[\"_DRIVE_ivd\"]", "window._DRIVE_ivd"]
        var markerEnd: String.Index?
        for marker in markers {
            if let range = html.range(of: marker) {
                markerEnd = range.upperBound
                break
            }
        }
        guard var idx = markerEnd else { return nil }

        // Skip whitespace and '='
        while idx < html.endIndex, html[idx].isWhitespace { idx = html.index(after: idx) }
        guard idx < html.endIndex, html[idx] == "=" else { return nil }
        idx = html.index(after: idx)
        while idx < html.endIndex, html[idx].isWhitespace { idx = html.index(after: idx) }
        guard idx < html.endIndex else { return nil }

        let first = html[idx]
        if first == "'" || first == "\"" {
            let quote = first
            var cursor = html.index(after: idx)
            let start = cursor
            var escaped = false
            while cursor < html.endIndex {
                let ch = html[cursor]
                if escaped {
                    escaped = false
                } else if ch == "\\" {
                    escaped = true
                } else if ch == quote {
                    let rawLiteral = String(html[start..<cursor])
                    return unescapeJSString(rawLiteral)
                }
                cursor = html.index(after: cursor)
            }
            return nil
        } else if first == "[" {
            // Direct JSON array literal fallback
            let rest = html[idx...]
            if let semi = rest.firstIndex(of: ";") {
                return String(rest[..<semi]).trimmingCharacters(in: .whitespacesAndNewlines)
            }
            return String(rest).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return nil
    }

    nonisolated static func unescapeJSString(_ raw: String) -> String {
        var result = ""
        result.reserveCapacity(raw.count)
        var utf16Buffer: [UInt16] = []

        func flushUTF16() {
            if !utf16Buffer.isEmpty {
                result.append(String(decoding: utf16Buffer, as: UTF16.self))
                utf16Buffer.removeAll(keepingCapacity: true)
            }
        }

        var idx = raw.startIndex
        while idx < raw.endIndex {
            let ch = raw[idx]
            guard ch == "\\" else {
                flushUTF16()
                result.append(ch)
                idx = raw.index(after: idx)
                continue
            }

            let nextIdx = raw.index(after: idx)
            guard nextIdx < raw.endIndex else {
                flushUTF16()
                result.append("\\")
                break
            }

            let esc = raw[nextIdx]
            switch esc {
            case "x":
                let hexStart = raw.index(after: nextIdx)
                if let hexEnd = raw.index(hexStart, offsetBy: 2, limitedBy: raw.endIndex),
                   let code = UInt16(raw[hexStart..<hexEnd], radix: 16) {
                    flushUTF16()
                    if let scalar = UnicodeScalar(code) {
                        result.append(Character(scalar))
                    }
                    idx = hexEnd
                } else {
                    flushUTF16()
                    result.append(esc)
                    idx = raw.index(after: nextIdx)
                }
            case "u":
                let hexStart = raw.index(after: nextIdx)
                if let hexEnd = raw.index(hexStart, offsetBy: 4, limitedBy: raw.endIndex),
                   let code = UInt16(raw[hexStart..<hexEnd], radix: 16) {
                    utf16Buffer.append(code)
                    idx = hexEnd
                } else {
                    flushUTF16()
                    result.append(esc)
                    idx = raw.index(after: nextIdx)
                }
            case "n":
                flushUTF16(); result.append("\n"); idx = raw.index(after: nextIdx)
            case "r":
                flushUTF16(); result.append("\r"); idx = raw.index(after: nextIdx)
            case "t":
                flushUTF16(); result.append("\t"); idx = raw.index(after: nextIdx)
            case "b":
                flushUTF16(); result.append("\u{08}"); idx = raw.index(after: nextIdx)
            case "f":
                flushUTF16(); result.append("\u{0C}"); idx = raw.index(after: nextIdx)
            case "\\", "'", "\"", "/":
                flushUTF16(); result.append(esc); idx = raw.index(after: nextIdx)
            default:
                flushUTF16(); result.append(esc); idx = raw.index(after: nextIdx)
            }
        }
        flushUTF16()
        return result
    }

    nonisolated static func parseEmbeddedFolderView(from html: String) -> [DriveItem] {
        var items: [DriveItem] = []
        var seen = Set<String>()

        // Match flip-entry blocks when present (either single or double quoted class).
        if let entryRegex = try? NSRegularExpression(
            pattern: #"class\s*=\s*["'](?:[^"']*\s)?flip-entry(?:\s[^"']*)?["']"#,
            options: [.caseInsensitive]
        ) {
            let nsRange = NSRange(html.startIndex..<html.endIndex, in: html)
            let matches = entryRegex.matches(in: html, range: nsRange)
            for (index, match) in matches.enumerated() {
                guard let startRange = Range(match.range, in: html) else { continue }
                let nextStart: String.Index
                if index + 1 < matches.count, let nextRange = Range(matches[index + 1].range, in: html) {
                    nextStart = nextRange.lowerBound
                } else {
                    nextStart = html.endIndex
                }
                let block = String(html[startRange.lowerBound..<nextStart])
                guard let entry = parseEmbeddedEntryBlock(block) else { continue }
                if seen.insert(entry.id).inserted {
                    items.append(entry)
                }
            }
        }

        if !items.isEmpty { return items }

        // Fallback: scan anchor tags pointing to /drive/folders/<ID> or /file/d/<ID>
        guard let anchorRegex = try? NSRegularExpression(
            pattern: #"<a\b[^>]*href=["']([^"']*(?:/drive/(?:u/\d+/)?folders/|/file/d/)[^"']*)["'][^>]*>([\s\S]*?)</a>"#,
            options: [.caseInsensitive]
        ) else { return items }

        let range = NSRange(html.startIndex..<html.endIndex, in: html)
        for match in anchorRegex.matches(in: html, range: range) {
            guard let hrefRange = Range(match.range(at: 1), in: html),
                  let textRange = Range(match.range(at: 2), in: html) else { continue }
            let href = decodeHTMLEntities(String(html[hrefRange]))
            let title = stripHTMLTags(decodeHTMLEntities(String(html[textRange]))).trimmingCharacters(in: .whitespacesAndNewlines)
            guard let item = driveItemFromHref(href, title: title) else { continue }
            if seen.insert(item.id).inserted {
                items.append(item)
            }
        }
        return items
    }

    nonisolated private static func parseEmbeddedEntryBlock(_ block: String) -> DriveItem? {
        guard let hrefRegex = try? NSRegularExpression(
            pattern: #"href=["']([^"']*(?:/drive/(?:u/\d+/)?folders/|/file/d/)[^"']*)["']"#,
            options: [.caseInsensitive]
        ) else { return nil }
        let range = NSRange(block.startIndex..<block.endIndex, in: block)
        guard let hrefMatch = hrefRegex.firstMatch(in: block, range: range),
              let hrefRange = Range(hrefMatch.range(at: 1), in: block) else { return nil }
        let href = decodeHTMLEntities(String(block[hrefRange]))

        var title = ""
        if let titleRegex = try? NSRegularExpression(
            pattern: #"class=["'][^"']*flip-entry-title[^"']*["'][^>]*>([\s\S]*?)</(?:div|span|a)>"#,
            options: [.caseInsensitive]
        ),
           let titleMatch = titleRegex.firstMatch(in: block, range: range),
           let titleRange = Range(titleMatch.range(at: 1), in: block) {
            title = stripHTMLTags(decodeHTMLEntities(String(block[titleRange]))).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if title.isEmpty,
           let anchorRegex = try? NSRegularExpression(pattern: #"<a\b[^>]*>([\s\S]*?)</a>"#, options: [.caseInsensitive]),
           let anchorMatch = anchorRegex.firstMatch(in: block, range: range),
           let textRange = Range(anchorMatch.range(at: 1), in: block) {
            title = stripHTMLTags(decodeHTMLEntities(String(block[textRange]))).trimmingCharacters(in: .whitespacesAndNewlines)
        }

        return driveItemFromHref(href, title: title)
    }

    nonisolated private static func driveItemFromHref(_ href: String, title: String) -> DriveItem? {
        let normalized = href.hasPrefix("/") ? "https://drive.google.com\(href)" : href
        guard let target = parseExtracted(normalized) else { return nil }
        switch target {
        case .folder(let id, let rk):
            let resolvedTitle = title.isEmpty ? id : title
            return DriveItem(id: id, name: resolvedTitle, mimeType: "application/vnd.google-apps.folder", resourceKey: rk)
        case .file(let id, let rk):
            let resolvedTitle = title.isEmpty ? id : title
            let ext = supportedAudioExtension(fromFilename: resolvedTitle) ?? ""
            let mime = ext.isEmpty ? "application/octet-stream" : (mimeTypeForExtension(ext) ?? "audio/mpeg")
            return DriveItem(id: id, name: resolvedTitle, mimeType: mime, resourceKey: rk)
        case .open:
            return nil
        }
    }

    nonisolated static func parseDownloadConfirmForm(html: String, baseURL: URL) -> URL? {
        guard let formRegex = try? NSRegularExpression(
            pattern: #"<form\b([^>]*)>([\s\S]*?)</form>"#,
            options: [.caseInsensitive]
        ) else { return nil }

        let range = NSRange(html.startIndex..<html.endIndex, in: html)
        for match in formRegex.matches(in: html, range: range) {
            guard let attrsRange = Range(match.range(at: 1), in: html),
                  let bodyRange = Range(match.range(at: 2), in: html) else { continue }
            let formAttrs = parseHTMLAttributes(String(html[attrsRange]))
            guard let rawAction = formAttrs["action"] else { continue }
            let decodedAction = decodeHTMLEntities(rawAction)
            let actionLower = decodedAction.lowercased()
            guard actionLower.contains("download") || actionLower.contains("/uc") else { continue }

            guard let actionURL = URL(string: decodedAction, relativeTo: baseURL)?.absoluteURL,
                  var components = URLComponents(url: actionURL, resolvingAgainstBaseURL: true) else {
                continue
            }

            let body = String(html[bodyRange])
            guard let inputRegex = try? NSRegularExpression(pattern: #"<input\b([^>]*)>"#, options: [.caseInsensitive]) else {
                continue
            }
            let bodyNSRange = NSRange(body.startIndex..<body.endIndex, in: body)
            var queryItems = components.queryItems ?? []
            var existingKeys = Set(queryItems.map { $0.name.lowercased() })

            for inputMatch in inputRegex.matches(in: body, range: bodyNSRange) {
                guard let inputAttrRange = Range(inputMatch.range(at: 1), in: body) else { continue }
                let attrs = parseHTMLAttributes(String(body[inputAttrRange]))
                let inputType = attrs["type"]?.lowercased() ?? "text"
                if inputType == "submit" || inputType == "button" || inputType == "reset" { continue }
                guard let name = attrs["name"], !name.isEmpty,
                      let rawValue = attrs["value"] else { continue }
                let value = decodeHTMLEntities(rawValue)
                if existingKeys.contains(name.lowercased()) {
                    queryItems.removeAll { $0.name.caseInsensitiveCompare(name) == .orderedSame }
                }
                existingKeys.insert(name.lowercased())
                queryItems.append(URLQueryItem(name: name, value: value))
            }

            components.queryItems = queryItems.isEmpty ? nil : queryItems
            if let url = components.url {
                return url
            }
        }
        return nil
    }

    nonisolated private static func parseHTMLAttributes(_ raw: String) -> [String: String] {
        var result: [String: String] = [:]
        guard let regex = try? NSRegularExpression(
            pattern: #"([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))"#,
            options: []
        ) else { return result }
        let range = NSRange(raw.startIndex..<raw.endIndex, in: raw)
        for match in regex.matches(in: raw, range: range) {
            guard let nameRange = Range(match.range(at: 1), in: raw) else { continue }
            let name = String(raw[nameRange]).lowercased()
            var value = ""
            for g in 2...4 {
                if let valRange = Range(match.range(at: g), in: raw) {
                    value = String(raw[valRange])
                    break
                }
            }
            result[name] = value
        }
        return result
    }

    nonisolated static func decodeHTMLEntities(_ text: String) -> String {
        var result = text
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&#x27;", with: "'", options: .caseInsensitive)
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&nbsp;", with: " ")

        if let numRegex = try? NSRegularExpression(pattern: #"&#(x[0-9A-Fa-f]+|\d+);"#, options: []) {
            let matches = numRegex.matches(in: result, range: NSRange(result.startIndex..<result.endIndex, in: result))
            for match in matches.reversed() {
                guard let fullRange = Range(match.range(at: 0), in: result),
                      let codeRange = Range(match.range(at: 1), in: result) else { continue }
                let token = String(result[codeRange])
                let codePoint: UInt32? = token.hasPrefix("x") || token.hasPrefix("X")
                    ? UInt32(token.dropFirst(), radix: 16)
                    : UInt32(token, radix: 10)
                if let codePoint, let scalar = UnicodeScalar(codePoint) {
                    result.replaceSubrange(fullRange, with: String(Character(scalar)))
                }
            }
        }
        return result
    }

    nonisolated private static func stripHTMLTags(_ html: String) -> String {
        html.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
    }

    // MARK: Audio Type & Extension Helpers

    nonisolated static func isSupportedAudio(name: String, mimeType: String) -> Bool {
        if supportedAudioExtension(fromFilename: name) != nil {
            return true
        }
        let lowerMime = mimeType.lowercased().split(separator: ";").first.map {
            String($0).trimmingCharacters(in: .whitespacesAndNewlines)
        } ?? ""
        if lowerMime.hasPrefix("audio/") ||
            lowerMime == "application/ogg" ||
            lowerMime == "application/x-flac" ||
            lowerMime == "application/flac" {
            return true
        }
        return false
    }

    nonisolated static func supportedAudioExtension(fromFilename name: String) -> String? {
        let ext = (name as NSString).pathExtension.lowercased()
        return supportedAudioExtensions.contains(ext) ? ext : nil
    }

    nonisolated static func resolvedAudioExtension(name: String, mimeType: String) -> String {
        if let ext = supportedAudioExtension(fromFilename: name) {
            return ext
        }
        let lowerMime = mimeType.lowercased().split(separator: ";").first.map {
            String($0).trimmingCharacters(in: .whitespacesAndNewlines)
        } ?? ""
        if let mapped = mimeToExtension[lowerMime] {
            return mapped
        }
        if lowerMime.hasPrefix("audio/") {
            let sub = String(lowerMime.dropFirst("audio/".count))
            let cleaned = sub.replacingOccurrences(of: "x-", with: "")
            if supportedAudioExtensions.contains(cleaned) {
                return cleaned
            }
        }
        return "m4a"
    }

    nonisolated static func stripAudioExtensionIfPresent(_ name: String) -> String {
        if supportedAudioExtension(fromFilename: name) != nil {
            let stripped = (name as NSString).deletingPathExtension
            if !stripped.isEmpty { return stripped }
        }
        return name
    }

    nonisolated static func sanitizeRelativeComponent(_ raw: String, fallback: String) -> String {
        let cleaned = raw
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: "\\", with: "-")
            .replacingOccurrences(of: "\0", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleaned.isEmpty, cleaned != ".", cleaned != ".." {
            return cleaned
        }
        let cleanFallback = fallback
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: "\\", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return cleanFallback.isEmpty ? "Untitled" : cleanFallback
    }

    nonisolated private static func sanitizedAudioFileName(name: String, mimeType: String, fallbackID: String) -> String {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let baseComponent = sanitizeRelativeComponent(trimmedName.isEmpty ? fallbackID : trimmedName, fallback: fallbackID)
        if supportedAudioExtension(fromFilename: baseComponent) != nil {
            return baseComponent
        }
        let ext = resolvedAudioExtension(name: trimmedName, mimeType: mimeType)
        return "\(baseComponent).\(ext)"
    }

    nonisolated private static func disambiguateComponent(_ segment: String, id: String, used: inout Set<String>) -> String {
        let shortId = String(id.prefix(6))
        let candidate = "\(segment) (\(shortId))"
        if used.insert(candidate.lowercased()).inserted {
            return candidate
        }
        let fullCandidate = "\(segment) (\(id))"
        if used.insert(fullCandidate.lowercased()).inserted {
            return fullCandidate
        }
        var n = 2
        while true {
            let indexed = "\(segment) (\(shortId)-\(n))"
            if used.insert(indexed.lowercased()).inserted { return indexed }
            n += 1
        }
    }

    nonisolated private static func disambiguateFileName(_ fileName: String, id: String, used: inout Set<String>) -> String {
        let shortId = String(id.prefix(6))
        let ext = (fileName as NSString).pathExtension
        let base = ext.isEmpty ? fileName : (fileName as NSString).deletingPathExtension
        func format(_ tag: String) -> String {
            ext.isEmpty ? "\(base) (\(tag))" : "\(base) (\(tag)).\(ext)"
        }
        let candidate = format(shortId)
        if used.insert(candidate.lowercased()).inserted {
            return candidate
        }
        let fullCandidate = format(id)
        if used.insert(fullCandidate.lowercased()).inserted {
            return fullCandidate
        }
        var n = 2
        while true {
            let indexed = format("\(shortId)-\(n)")
            if used.insert(indexed.lowercased()).inserted { return indexed }
            n += 1
        }
    }

    nonisolated private static func mimeTypeForExtension(_ ext: String) -> String? {
        switch ext.lowercased() {
        case "mp3": "audio/mpeg"
        case "m4a", "mp4": "audio/mp4"
        case "aac": "audio/aac"
        case "flac": "audio/flac"
        case "wav": "audio/wav"
        case "aif", "aiff": "audio/aiff"
        case "alac": "audio/alac"
        case "caf": "audio/x-caf"
        case "ogg": "audio/ogg"
        case "opus": "audio/opus"
        case "webm": "audio/webm"
        case "wma": "audio/x-ms-wma"
        default: nil
        }
    }

    nonisolated static func parseContentDispositionFilename(_ header: String) -> String? {
        // Prefer RFC 5987 filename*=UTF-8''...
        if let starRegex = try? NSRegularExpression(
            pattern: #"filename\*\s*=\s*(?:UTF-8''|utf-8'')([^;\r\n]+)"#,
            options: []
        ) {
            let range = NSRange(header.startIndex..<header.endIndex, in: header)
            if let match = starRegex.firstMatch(in: header, range: range),
               let r = Range(match.range(at: 1), in: header) {
                let raw = String(header[r]).trimmingCharacters(in: CharacterSet(charactersIn: "\"' "))
                if let decoded = raw.removingPercentEncoding, !decoded.isEmpty {
                    return decoded
                }
                if !raw.isEmpty { return raw }
            }
        }
        // Fallback: filename="..." or filename=...
        if let plainRegex = try? NSRegularExpression(
            pattern: #"filename\s*=\s*(?:"([^"]+)"|([^;\r\n]+))"#,
            options: [.caseInsensitive]
        ) {
            let range = NSRange(header.startIndex..<header.endIndex, in: header)
            if let match = plainRegex.firstMatch(in: header, range: range) {
                for g in 1...2 {
                    if let r = Range(match.range(at: g), in: header) {
                        let name = String(header[r]).trimmingCharacters(in: CharacterSet(charactersIn: "\"' "))
                        if !name.isEmpty { return name }
                    }
                }
            }
        }
        return nil
    }

    nonisolated private static func detectStreamExtension(response: HTTPResponseData, fallbackExtension: String?) -> String {
        if let cd = response.header("Content-Disposition"),
           let filename = parseContentDispositionFilename(cd),
           let ext = supportedAudioExtension(fromFilename: filename) {
            return ext
        }
        if let ct = response.header("Content-Type") {
            let cleanCT = ct.lowercased().split(separator: ";").first.map {
                String($0).trimmingCharacters(in: .whitespacesAndNewlines)
            } ?? ""
            if let mapped = mimeToExtension[cleanCT] {
                return mapped
            }
        }
        if let fallbackExtension, supportedAudioExtensions.contains(fallbackExtension.lowercased()) {
            return fallbackExtension.lowercased()
        }
        return "m4a"
    }
}

private actor DriveTrackCollector {
    private var tracks: [CloudTrackInfo] = []
    private var seenFileIDs: Set<String> = []

    init(initialTracks: [CloudTrackInfo]) {
        for track in initialTracks {
            let fileID = GoogleDriveClient.decodeTrackID(track.remoteTrackID).fileID
            if seenFileIDs.insert(fileID).inserted {
                tracks.append(track)
            }
        }
    }

    func append(_ newTracks: [CloudTrackInfo]) {
        for track in newTracks {
            let fileID = GoogleDriveClient.decodeTrackID(track.remoteTrackID).fileID
            if seenFileIDs.insert(fileID).inserted {
                tracks.append(track)
            }
        }
    }

    func allTracks() -> [CloudTrackInfo] {
        tracks
    }
}

enum CloudTreeCrawler {
    static func crawl(
        rootID: String,
        initialSubfolders: [DriveSubfolderRef],
        maxFolders: Int = 500,
        maxWorkers: Int = 4,
        priorityState: DriveCrawlPriorityState?,
        fetchNode: @escaping @Sendable (DriveSubfolderRef) async throws -> DriveFolderBatch,
        onBatch: @escaping @Sendable (DriveFolderBatch) async -> Void
    ) async throws -> DriveCrawlSummary {
        let queue = DriveCrawlQueue(
            rootID: rootID,
            initialSubfolders: initialSubfolders,
            maxFolders: maxFolders,
            priorityState: priorityState
        )
        let workerCount = max(1, min(maxWorkers, 4))

        try await withTaskCancellationHandler {
            try await withThrowingTaskGroup(of: Void.self) { group in
                for _ in 0..<workerCount {
                    group.addTask {
                        while let node = await queue.takeNext() {
                            try Task.checkCancellation()
                            do {
                                var batch = try await fetchNode(node)
                                let accepted = await queue.complete(discovered: batch.discoveredSubfolders, hadError: false)
                                batch.discoveredSubfolders = accepted
                                await onBatch(batch)
                            } catch is CancellationError {
                                throw CancellationError()
                            } catch {
                                let failedBatch = DriveFolderBatch(
                                    folderID: node.id,
                                    folderPath: node.relativePath,
                                    depth: node.depth,
                                    rootTitle: nil,
                                    tracks: [],
                                    discoveredSubfolders: [],
                                    hadError: true
                                )
                                await onBatch(failedBatch)
                                await queue.complete(discovered: [], hadError: true)
                            }
                        }
                    }
                }
                try await group.waitForAll()
            }
        } onCancel: {
            Task { await queue.cancelAll() }
        }

        return await queue.summary()
    }
}

actor DriveCrawlQueue {
    private let maxFolders: Int
    private let priorityState: DriveCrawlPriorityState?
    private var visitedIDs: Set<String>
    private var foldersVisited: Int
    private var pending: [DriveSubfolderRef]
    private var inFlightCount = 0
    private var hadErrors = false
    private var wasTruncated = false
    private var waiters: [CheckedContinuation<DriveSubfolderRef?, Never>] = []
    private var cancelled = false

    init(
        rootID: String,
        initialSubfolders: [DriveSubfolderRef],
        maxFolders: Int,
        priorityState: DriveCrawlPriorityState?
    ) {
        self.maxFolders = maxFolders
        self.priorityState = priorityState
        self.visitedIDs = [rootID]
        self.foldersVisited = 1
        self.pending = []
        for sub in initialSubfolders where visitedIDs.insert(sub.id).inserted {
            pending.append(sub)
        }
    }

    func takeNext() async -> DriveSubfolderRef? {
        if cancelled || Task.isCancelled { return nil }
        let targets = priorityState?.prioritizedPaths() ?? []
        if let next = popNextIfAvailable(priorityTargets: targets) {
            return next
        }
        if inFlightCount == 0 || foldersVisited >= maxFolders {
            if !pending.isEmpty && foldersVisited >= maxFolders {
                wasTruncated = true
            }
            finishWaiters()
            return nil
        }
        return await withCheckedContinuation { continuation in
            if cancelled {
                continuation.resume(returning: nil)
                return
            }
            waiters.append(continuation)
        }
    }

    @discardableResult
    func complete(discovered: [DriveSubfolderRef], hadError: Bool) -> [DriveSubfolderRef] {
        inFlightCount = max(inFlightCount - 1, 0)
        if hadError { hadErrors = true }
        var accepted: [DriveSubfolderRef] = []
        for sub in discovered where visitedIDs.insert(sub.id).inserted {
            pending.append(sub)
            accepted.append(sub)
        }
        if cancelled {
            finishWaiters()
            return accepted
        }
        let targets = priorityState?.prioritizedPaths() ?? []
        while !waiters.isEmpty, foldersVisited < maxFolders, let node = popNextIfAvailable(priorityTargets: targets) {
            let waiter = waiters.removeFirst()
            waiter.resume(returning: node)
        }
        if (pending.isEmpty && inFlightCount == 0) || foldersVisited >= maxFolders {
            if !pending.isEmpty && foldersVisited >= maxFolders {
                wasTruncated = true
            }
            finishWaiters()
        }
        return accepted
    }

    func cancelAll() {
        cancelled = true
        finishWaiters()
    }

    func summary() -> DriveCrawlSummary {
        DriveCrawlSummary(foldersVisited: foldersVisited, hadErrors: hadErrors, wasTruncated: wasTruncated)
    }

    private func popNextIfAvailable(priorityTargets: [String]) -> DriveSubfolderRef? {
        guard !pending.isEmpty else { return nil }
        guard foldersVisited < maxFolders else {
            wasTruncated = true
            return nil
        }
        let index = bestCandidateIndex(priorityTargets: priorityTargets)
        let node = pending.remove(at: index)
        foldersVisited += 1
        inFlightCount += 1
        return node
    }

    private func bestCandidateIndex(priorityTargets: [String]) -> Int {
        guard !priorityTargets.isEmpty else {
            // Breadth-first order (shallowest depth first, preserving discovery order).
            var bestIdx = 0
            var bestDepth = pending[0].depth
            for i in pending.indices where pending[i].depth < bestDepth {
                bestDepth = pending[i].depth
                bestIdx = i
            }
            return bestIdx
        }

        func score(for node: DriveSubfolderRef) -> (tier: Int, targetOrder: Int, depth: Int) {
            let path = node.relativePath
            for (tIdx, target) in priorityTargets.enumerated() {
                if path == target {
                    return (0, tIdx, node.depth)
                }
                if target.hasPrefix(path + "/") {
                    return (1, tIdx, node.depth)
                }
                if path.hasPrefix(target + "/") {
                    return (2, tIdx, node.depth)
                }
            }
            return (3, Int.max, node.depth)
        }

        var bestIdx = 0
        var bestScore = score(for: pending[0])
        for i in 1..<pending.count {
            let candidateScore = score(for: pending[i])
            if candidateScore < bestScore {
                bestScore = candidateScore
                bestIdx = i
            }
        }
        return bestIdx
    }

    private func finishWaiters() {
        let current = waiters
        waiters.removeAll()
        for waiter in current {
            waiter.resume(returning: nil)
        }
    }
}
