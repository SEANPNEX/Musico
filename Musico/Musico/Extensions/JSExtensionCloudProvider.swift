import Foundation

/// Bridges a validated JS extension package into `CloudSourceProvider` and `CloudProgressiveTreeProvider`.
final class JSExtensionCloudProvider: CloudProgressiveTreeProvider, @unchecked Sendable {
    static let maxPaginationPages = 100
    static let maxCrawlFolders = 500
    static let maxTreeDepth = 16

    let manifest: ExtensionManifest
    let runtime: JSExtensionRuntime
    private let compiledURLRegexes: [NSRegularExpression]
    private let extensionCookieProvider: (@Sendable () -> String?)?
    private let extensionStreamQualityProvider: (@Sendable () -> String?)?
    private let extensionDownloadQualityProvider: (@Sendable () -> String?)?

    var id: String { manifest.providerID }
    var displayName: String { manifest.name }
    var badgeLabel: String { manifest.effectiveBadgeLabel }
    var rootKind: LibraryRoot.Kind { .cloudExtension }
    var trackCloudSource: String { manifest.providerID }
    var supportsTree: Bool { manifest.capabilities.contains(.tree) }
    var supportsCookie: Bool { manifest.supportsCookieAuth }
    var supportsCollection: Bool { manifest.capabilities.contains(.playlist) || manifest.capabilities.contains(.tree) }
    var availableQualities: [CloudQualityOption] { manifest.effectiveQualities }
    var defaultStreamQualityID: String? { manifest.effectiveDefaultStreamQualityID }
    var defaultDownloadQualityID: String? { manifest.effectiveDefaultDownloadQualityID }

    init(
        manifest: ExtensionManifest,
        runtime: JSExtensionRuntime,
        extensionCookieProvider: (@Sendable () -> String?)? = nil,
        extensionStreamQualityProvider: (@Sendable () -> String?)? = nil,
        extensionDownloadQualityProvider: (@Sendable () -> String?)? = nil
    ) {
        self.manifest = manifest
        self.runtime = runtime
        self.extensionCookieProvider = extensionCookieProvider
        self.extensionStreamQualityProvider = extensionStreamQualityProvider
        self.extensionDownloadQualityProvider = extensionDownloadQualityProvider
        self.compiledURLRegexes = manifest.urlPatterns.compactMap {
            try? NSRegularExpression(pattern: $0, options: [.caseInsensitive])
        }
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
            if let extQ = extensionStreamQualityProvider?()?.trimmingCharacters(in: .whitespacesAndNewlines),
               !extQ.isEmpty,
               validIDs.contains(extQ) {
                return extQ
            }
            return defaultStreamQualityID ?? qualities.first?.id
        case .download:
            if let rootQ = root?.downloadQualityID?.trimmingCharacters(in: .whitespacesAndNewlines),
               !rootQ.isEmpty,
               validIDs.contains(rootQ) {
                return rootQ
            }
            if let extQ = extensionDownloadQualityProvider?()?.trimmingCharacters(in: .whitespacesAndNewlines),
               !extQ.isEmpty,
               validIDs.contains(extQ) {
                return extQ
            }
            return defaultDownloadQualityID ?? qualities.first?.id
        }
    }

    func canHandle(rawText: String, extractedURL: String) -> CloudMatchResult? {
        guard supportsCollection else { return nil }
        let candidates = [extractedURL, rawText]
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        guard !candidates.isEmpty else { return nil }

        let patternMatched = candidates.contains { candidate in
            let range = NSRange(candidate.startIndex..<candidate.endIndex, in: candidate)
            return compiledURLRegexes.contains { regex in
                regex.firstMatch(in: candidate, range: range) != nil
            }
        }
        guard patternMatched else { return nil }

        let outcome = runtime.canHandle(rawText: rawText, extractedURL: extractedURL)
        guard outcome.matched else { return nil }

        let fallbackURL = extractedURL.isEmpty ? rawText : extractedURL
        let normalized = (outcome.normalizedUrl?.isEmpty == false) ? outcome.normalizedUrl! : fallbackURL
        return CloudMatchResult(
            providerID: id,
            kind: .cloudExtension,
            extractedURL: fallbackURL,
            normalizedURL: normalized,
            remoteID: outcome.remoteId,
            itemKind: outcome.kind
        )
    }

    func fetchCollection(sourceURL: String, cookie: String?) async throws -> CloudPlaylistResult {
        let discovery = try await discoverRoot(sourceURL: sourceURL, cookie: cookie)
        var collected = discovery.initialBatch.tracks
        var seenPaths = Set(collected.map(\.relativePath))

        if supportsTree && !discovery.initialBatch.discoveredSubfolders.isEmpty {
            let lock = NSLock()
            var extraTracks: [CloudTrackInfo] = []
            _ = try await crawlTree(
                discovery: discovery,
                cookie: cookie,
                priorityState: nil
            ) { batch in
                lock.withLock {
                    extraTracks.append(contentsOf: batch.tracks)
                }
            }
            for item in extraTracks where seenPaths.insert(item.relativePath).inserted {
                collected.append(item)
            }
        }

        guard !collected.isEmpty else {
            throw CloudSourceError.emptyPlaylist
        }
        return CloudPlaylistResult(
            kind: .cloudExtension,
            remoteID: discovery.rootID,
            title: discovery.rootTitle,
            tracks: collected,
            providerID: id
        )
    }

    func discoverRoot(sourceURL: String, cookie: String?) async throws -> DriveFolderDiscovery {
        let effectiveCookie = resolveEffectiveCookie(cookie)
        var cursor: String? = nil
        var seenCursors = Set<String>()
        var pageCount = 0

        var rootID = ""
        var rootTitle = manifest.name
        var allTracks: [CloudTrackInfo] = []
        var allSubfolders: [DriveSubfolderRef] = []
        var seenSubfolderIDs = Set<String>()

        repeat {
            try Task.checkCancellation()
            pageCount += 1
            let page = try await runtime.fetchCollection(url: sourceURL, cookie: effectiveCookie, cursor: cursor)
            if rootID.isEmpty { rootID = page.remoteId }
            if !page.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                rootTitle = page.title
            }
            allTracks.append(contentsOf: mapTracks(page.tracks, pathSegments: [], defaultAlbum: rootTitle))
            for sub in mapSubfolders(page.subfolders ?? [], parentSegments: [], parentDepth: 0) {
                if seenSubfolderIDs.insert(sub.id).inserted {
                    allSubfolders.append(sub)
                }
            }

            let next = page.nextCursor?.trimmingCharacters(in: .whitespacesAndNewlines)
            if let next, !next.isEmpty, pageCount < Self.maxPaginationPages, seenCursors.insert(next).inserted {
                cursor = next
            } else {
                cursor = nil
            }
        } while cursor != nil

        if allTracks.isEmpty && allSubfolders.isEmpty {
            throw CloudSourceError.emptyPlaylist
        }

        let initialBatch = DriveFolderBatch(
            folderID: rootID.isEmpty ? sourceURL : rootID,
            folderPath: "",
            depth: 0,
            rootTitle: rootTitle,
            tracks: allTracks,
            discoveredSubfolders: allSubfolders,
            hadError: false
        )
        return DriveFolderDiscovery(
            rootID: rootID.isEmpty ? sourceURL : rootID,
            resourceKey: nil,
            rootTitle: rootTitle,
            initialBatch: initialBatch,
            singleFileResult: nil,
            providerID: id,
            kind: .cloudExtension
        )
    }

    func crawlTree(
        discovery: DriveFolderDiscovery,
        cookie: String?,
        priorityState: DriveCrawlPriorityState?,
        onBatch: @escaping @Sendable (DriveFolderBatch) async -> Void
    ) async throws -> DriveCrawlSummary {
        let effectiveCookie = resolveEffectiveCookie(cookie)
        let rootTitle = discovery.rootTitle
        return try await CloudTreeCrawler.crawl(
            rootID: discovery.rootID,
            initialSubfolders: discovery.initialBatch.discoveredSubfolders,
            maxFolders: Self.maxCrawlFolders,
            maxWorkers: 4,
            priorityState: priorityState,
            fetchNode: { node in
                try await self.fetchSingleFolder(
                    folderID: node.id,
                    resourceKey: node.resourceKey,
                    pathSegments: node.pathSegments,
                    depth: node.depth,
                    rootTitle: rootTitle,
                    cookie: effectiveCookie,
                    extraJSON: node.extraJSON
                )
            },
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
        guard supportsTree else {
            throw CloudSourceError.unsupportedSource
        }
        let effectiveCookie = resolveEffectiveCookie(cookie)
        var cursor: String? = nil
        var seenCursors = Set<String>()
        var pageCount = 0

        var tracks: [CloudTrackInfo] = []
        var subfolders: [DriveSubfolderRef] = []
        var seenSubfolderIDs = Set<String>()

        repeat {
            try Task.checkCancellation()
            pageCount += 1
            let page = try await runtime.fetchFolder(
                folderID: folderID,
                pathSegments: pathSegments,
                depth: depth,
                cookie: effectiveCookie,
                cursor: cursor,
                extraJSON: extraJSON
            )
            let defaultAlbum = pathSegments.last ?? rootTitle ?? manifest.name
            tracks.append(contentsOf: mapTracks(page.tracks, pathSegments: pathSegments, defaultAlbum: defaultAlbum))
            if depth < Self.maxTreeDepth {
                for sub in mapSubfolders(page.subfolders ?? [], parentSegments: pathSegments, parentDepth: depth) {
                    if sub.id != folderID && seenSubfolderIDs.insert(sub.id).inserted {
                        subfolders.append(sub)
                    }
                }
            }

            let next = page.nextCursor?.trimmingCharacters(in: .whitespacesAndNewlines)
            if let next, !next.isEmpty, pageCount < Self.maxPaginationPages, seenCursors.insert(next).inserted {
                cursor = next
            } else {
                cursor = nil
            }
        } while cursor != nil

        return DriveFolderBatch(
            folderID: folderID,
            folderPath: pathSegments.joined(separator: "/"),
            depth: depth,
            rootTitle: depth == 0 ? rootTitle : nil,
            tracks: tracks,
            discoveredSubfolders: subfolders,
            hadError: false
        )
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
        guard manifest.capabilities.contains(.stream) else {
            throw CloudSourceError.noPlayableStream
        }
        let effectiveCookie = resolveEffectiveCookie(root?.cookie)
        let effectiveQuality = effectiveQualityID(root: root, purpose: purpose, overrideQualityID: qualityID)
        let remoteTrackID = track.remoteTrackID ?? track.relativePath
        let resolution = try await runtime.resolveStream(
            remoteTrackID: remoteTrackID,
            relativePath: track.relativePath,
            cookie: effectiveCookie,
            quality: effectiveQuality,
            purpose: purpose,
            forceRefresh: forceRefresh
        )
        guard let streamURL = URL(string: resolution.url) else {
            throw CloudSourceError.noPlayableStream
        }
        let fallbackURLs = (resolution.fallbackUrls ?? []).compactMap { URL(string: $0) }
        let ext = sanitizeExtension(resolution.fileExtension, mimeType: resolution.mimeType)
        let expiresAt: Date = {
            if let ms = resolution.expiresAtMs, ms.isFinite, ms > 0 {
                return Date(timeIntervalSince1970: ms / 1000.0)
            }
            return Date().addingTimeInterval(1800)
        }()
        let rawActual = resolution.actualQuality?.trimmingCharacters(in: .whitespacesAndNewlines)
        let actualQuality = (rawActual?.isEmpty == false) ? rawActual : effectiveQuality
        let rawLabel = resolution.qualityLabel?.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallbackLabel: String? = {
            guard let targetID = actualQuality ?? effectiveQuality,
                  let option = availableQualities.first(where: { $0.id == targetID }) else {
                return nil
            }
            return option.badge ?? option.label
        }()
        let qualityLabel = (rawLabel?.isEmpty == false) ? rawLabel : fallbackLabel
        let bitrateKbps = resolution.bitrateKbps.flatMap { ($0.isFinite && $0 > 0) ? Int($0.rounded()) : nil }
        return CloudStreamResult(
            url: streamURL,
            headers: resolution.headers ?? [:],
            fileExtension: ext,
            expiresAt: expiresAt,
            fallbackURLs: fallbackURLs,
            contentLength: resolution.contentLength,
            actualQuality: actualQuality,
            qualityLabel: qualityLabel,
            bitrateKbps: bitrateKbps
        )
    }

    // MARK: - Mapping Helpers

    private func resolveEffectiveCookie(_ explicitCookie: String?) -> String? {
        guard supportsCookie else { return nil }
        if let explicit = explicitCookie?.trimmingCharacters(in: .whitespacesAndNewlines), !explicit.isEmpty {
            return explicit
        }
        if let extCookie = extensionCookieProvider?()?.trimmingCharacters(in: .whitespacesAndNewlines), !extCookie.isEmpty {
            return extCookie
        }
        return nil
    }

    private func mapTracks(_ specs: [JSTrackSpec], pathSegments: [String], defaultAlbum: String) -> [CloudTrackInfo] {
        let folderPrefix = pathSegments.isEmpty ? "" : pathSegments.joined(separator: "/") + "/"
        return specs.map { spec in
            let fallbackName = CloudExporter.sanitizeFileName(spec.title, fallback: spec.remoteTrackId)
            let rawRel = spec.relativePath?.trimmingCharacters(in: .whitespacesAndNewlines)
            var safeRel = Self.sanitizeRelativePath((rawRel?.isEmpty == false) ? rawRel! : fallbackName)
            if !folderPrefix.isEmpty, !safeRel.contains("/") {
                safeRel = folderPrefix + safeRel
            }
            let artist = (spec.artist?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false)
                ? spec.artist!
                : "Unknown Artist"
            let album = spec.album ?? defaultAlbum
            let duration = (spec.durationSec ?? 0).isFinite ? max(0, spec.durationSec ?? 0) : 0
            return CloudTrackInfo(
                remoteTrackID: spec.remoteTrackId,
                relativePath: safeRel,
                title: spec.title,
                artist: artist,
                album: album,
                trackNumber: spec.trackNumber,
                duration: duration,
                artworkURL: spec.artworkUrl
            )
        }
    }

    private func mapSubfolders(
        _ specs: [JSSubfolderSpec],
        parentSegments: [String],
        parentDepth: Int
    ) -> [DriveSubfolderRef] {
        specs.compactMap { spec in
            let safeName = CloudExporter.sanitizeFileName(spec.name, fallback: spec.id)
            let segments: [String]
            if let provided = spec.pathSegments?.map({ CloudExporter.sanitizeFileName($0, fallback: safeName) }).filter({ !$0.isEmpty }),
               !provided.isEmpty {
                if !parentSegments.isEmpty && !provided.starts(with: parentSegments) {
                    segments = parentSegments + provided
                } else {
                    segments = provided
                }
            } else {
                segments = parentSegments + [safeName]
            }
            return DriveSubfolderRef(
                id: spec.id,
                resourceKey: nil,
                pathSegments: segments,
                depth: parentDepth + 1,
                extraJSON: spec.extraJSONString
            )
        }
    }

    private static func sanitizeRelativePath(_ raw: String) -> String {
        let normalized = raw.replacingOccurrences(of: "\\", with: "/")
        let cleanParts = normalized
            .split(separator: "/")
            .map(String.init)
            .filter { !$0.isEmpty && $0 != "." && $0 != ".." }
        if cleanParts.isEmpty {
            return "track"
        }
        return cleanParts.joined(separator: "/")
    }

    private func sanitizeExtension(_ rawExt: String, mimeType: String?) -> String {
        let cleaned = rawExt
            .trimmingCharacters(in: CharacterSet(charactersIn: ". ").union(.whitespacesAndNewlines))
            .lowercased()
        if !cleaned.isEmpty, cleaned.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) {
            return cleaned
        }
        if let mime = mimeType?.lowercased() {
            if mime.contains("flac") { return "flac" }
            if mime.contains("mp4") || mime.contains("aac") { return "m4a" }
            if mime.contains("ogg") { return "ogg" }
            if mime.contains("opus") { return "opus" }
            if mime.contains("wav") { return "wav" }
        }
        return "mp3"
    }
}
