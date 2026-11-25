import Foundation
import Observation
import SwiftUI
import UniformTypeIdentifiers

/// Per-folder manifest stored as `.musico-cloud.json` so saved local folders remember their
/// originating cloud playlist and can pull newly added songs incrementally.
struct CloudFolderManifest: Codable, Equatable, Sendable {
    static let fileName = ".musico-cloud.json"

    var sourceURL: String
    var remoteID: String?
    var cloudKind: LibraryRoot.Kind
    var lastSyncedEpochMs: Int64
    var tracks: [String: String]
    var providerID: String? = nil

    var resolvedProviderID: String? {
        if let providerID, !providerID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return providerID
        }
        return cloudKind.builtInProviderID
    }
}

/// Exports cached or fetched cloud tracks and playlists into user-selected local folders.
enum CloudExporter {
    struct ExportProgress: Sendable, Equatable {
        var completedTracks: Int
        var totalTracks: Int
        var currentTrackTitle: String
        var bytesDownloaded: Int64
        var totalBytes: Int64?
        var bytesPerSecond: Double
    }

    struct ExportSummary: Sendable, Equatable {
        let savedCount: Int
        let failedCount: Int
        let destinationURL: URL
        let errors: [String]
    }

    struct CloudUpdateSummary: Sendable, Equatable {
        let addedCount: Int
        let skippedCount: Int
        let failedCount: Int
        let totalRemoteCount: Int
        let errors: [String]
    }

    private static let illegalFileNameCharacters = CharacterSet(charactersIn: "/\\:*?\"<>|\0")
        .union(.controlCharacters)
        .union(.newlines)

    /// Produces a filesystem-safe file or folder name, falling back when the result would be empty.
    static func sanitizeFileName(_ name: String, fallback: String = "Untitled") -> String {
        let cleaned = sanitizeComponent(name)
        if !cleaned.isEmpty, cleaned != ".", cleaned != ".." {
            return cleaned
        }
        let cleanFallback = sanitizeComponent(fallback)
        if !cleanFallback.isEmpty, cleanFallback != ".", cleanFallback != ".." {
            return cleanFallback
        }
        return "Untitled"
    }

    private static func sanitizeComponent(_ raw: String) -> String {
        var scalars: [UnicodeScalar] = []
        scalars.reserveCapacity(raw.unicodeScalars.count)
        for scalar in raw.unicodeScalars {
            if illegalFileNameCharacters.contains(scalar) {
                scalars.append("-")
            } else {
                scalars.append(scalar)
            }
        }
        let replaced = String(String.UnicodeScalarView(scalars))
        let collapsed = replaced
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        let trimmed = collapsed.trimmingCharacters(in: CharacterSet(charactersIn: ". "))
        if trimmed.count > 180 {
            return String(trimmed.prefix(180)).trimmingCharacters(in: CharacterSet(charactersIn: ". "))
        }
        return trimmed
    }

    /// Returns a non-colliding file URL in `directory`, appending ` (2)`, ` (3)`, ... if needed.
    static func uniqueFileURL(in directory: URL, baseName: String, fileExtension: String) -> URL {
        let sanitizedBase = sanitizeFileName(baseName, fallback: "Track")
        let cleanExt = fileExtension.trimmingCharacters(in: CharacterSet(charactersIn: ". ")).lowercased()
        func fileName(suffix: String) -> String {
            cleanExt.isEmpty ? "\(sanitizedBase)\(suffix)" : "\(sanitizedBase)\(suffix).\(cleanExt)"
        }

        let first = directory.appendingPathComponent(fileName(suffix: ""))
        if !FileManager.default.fileExists(atPath: first.path) {
            return first
        }
        var attempt = 2
        while true {
            let candidate = directory.appendingPathComponent(fileName(suffix: " (\(attempt))"))
            if !FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
            attempt += 1
        }
    }

    /// Downloads/reuses cached audio for all cloud `tracks` and copies them into `<parentURL>/<sanitizedFolderName>/`.
    static func exportPlaylist(
        root: LibraryRoot,
        tracks: [Track],
        toParentFolder parentURL: URL,
        folderName: String,
        cache: CloudAudioCache = .shared,
        artworkCache: ArtworkCache = .shared,
        thumbnailCache: CloudThumbnailCache = .shared,
        onProgress: (@MainActor @Sendable (Int, Int, String) -> Void)? = nil
    ) async throws -> ExportSummary {
        try await performExportPlaylist(
            root: root,
            tracks: tracks,
            toParentFolder: parentURL,
            folderName: folderName,
            cache: cache,
            artworkCache: artworkCache,
            thumbnailCache: thumbnailCache,
            onDetailedProgress: onProgress.map { callback in
                { @MainActor progress in
                    callback(progress.completedTracks, progress.totalTracks, progress.currentTrackTitle)
                }
            }
        )
    }

    static func exportPlaylist(
        root: LibraryRoot,
        tracks: [Track],
        toParentFolder parentURL: URL,
        folderName: String,
        cache: CloudAudioCache = .shared,
        artworkCache: ArtworkCache = .shared,
        thumbnailCache: CloudThumbnailCache = .shared,
        onDetailedProgress: @escaping @MainActor @Sendable (ExportProgress) -> Void
    ) async throws -> ExportSummary {
        try await performExportPlaylist(
            root: root,
            tracks: tracks,
            toParentFolder: parentURL,
            folderName: folderName,
            cache: cache,
            artworkCache: artworkCache,
            thumbnailCache: thumbnailCache,
            onDetailedProgress: onDetailedProgress
        )
    }

    private static func performExportPlaylist(
        root: LibraryRoot,
        tracks: [Track],
        toParentFolder parentURL: URL,
        folderName: String,
        cache: CloudAudioCache,
        artworkCache: ArtworkCache,
        thumbnailCache: CloudThumbnailCache,
        onDetailedProgress: (@MainActor @Sendable (ExportProgress) -> Void)?
    ) async throws -> ExportSummary {
        try Task.checkCancellation()
        let sanitizedFolder = sanitizeFileName(folderName, fallback: sanitizeFileName(root.name, fallback: "Cloud Playlist"))
        let destinationURL = parentURL.appendingPathComponent(sanitizedFolder, isDirectory: true)
        try FileManager.default.createDirectory(at: destinationURL, withIntermediateDirectories: true)

        let cloudTracks = tracks.filter(\.isCloud)
        let total = cloudTracks.count
        var savedCount = 0
        var failedCount = 0
        var errors: [String] = []

        var trackedMap = loadManifest(in: destinationURL)?.tracks ?? [:]

        for (index, track) in cloudTracks.enumerated() {
            try Task.checkCancellation()
            await onDetailedProgress?(ExportProgress(
                completedTracks: index,
                totalTracks: total,
                currentTrackTitle: track.title,
                bytesDownloaded: 0,
                totalBytes: nil,
                bytesPerSecond: 0
            ))
            do {
                let cachedURL = try await cache.resolveAndCache(
                    track: track,
                    root: root,
                    onProgress: onDetailedProgress.map { callback in
                        { dlProgress in
                            Task { @MainActor in
                                callback(ExportProgress(
                                    completedTracks: index,
                                    totalTracks: total,
                                    currentTrackTitle: track.title,
                                    bytesDownloaded: dlProgress.bytesDownloaded,
                                    totalBytes: dlProgress.totalBytes,
                                    bytesPerSecond: dlProgress.bytesPerSecond
                                ))
                            }
                        }
                    }
                )
                try Task.checkCancellation()
                let ext = cachedURL.pathExtension.isEmpty ? "m4a" : cachedURL.pathExtension
                let targetURL = uniqueFileURL(in: destinationURL, baseName: track.title, fileExtension: ext)
                try FileManager.default.copyItem(at: cachedURL, to: targetURL)
                await writeMetadataAndArtwork(
                    for: track,
                    to: targetURL,
                    suppliedArtworkData: nil,
                    artworkCache: artworkCache,
                    thumbnailCache: thumbnailCache
                )
                let trackKey = track.remoteTrackID ?? track.relativePath
                if !trackKey.isEmpty {
                    trackedMap[trackKey] = targetURL.lastPathComponent
                }
                savedCount += 1
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                failedCount += 1
                errors.append("\(track.title): \(error.localizedDescription)")
            }
        }
        await onDetailedProgress?(ExportProgress(
            completedTracks: total,
            totalTracks: total,
            currentTrackTitle: "",
            bytesDownloaded: 0,
            totalBytes: nil,
            bytesPerSecond: 0
        ))

        if let sourceURL = root.sourceURL?.trimmingCharacters(in: .whitespacesAndNewlines),
           !sourceURL.isEmpty,
           let cloudKind = root.effectiveCloudKind {
            let manifest = CloudFolderManifest(
                sourceURL: sourceURL,
                remoteID: root.remoteID,
                cloudKind: cloudKind,
                lastSyncedEpochMs: Int64(Date().timeIntervalSince1970 * 1000),
                tracks: trackedMap,
                providerID: root.effectiveProviderID
            )
            try? saveManifest(manifest, in: destinationURL)
        }

        return ExportSummary(
            savedCount: savedCount,
            failedCount: failedCount,
            destinationURL: destinationURL,
            errors: errors
        )
    }

    static func manifestURL(in directory: URL) -> URL {
        directory.appendingPathComponent(CloudFolderManifest.fileName)
    }

    static func loadManifest(in directory: URL) -> CloudFolderManifest? {
        let url = manifestURL(in: directory)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(CloudFolderManifest.self, from: data)
    }

    static func saveManifest(_ manifest: CloudFolderManifest, in directory: URL) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(manifest)
        let url = manifestURL(in: directory)
        try data.write(to: url, options: .atomic)
    }

    /// Fetches the latest remote playlist for a tracked local folder and downloads only missing/new tracks.
    static func updateLocalFolder(
        root: LibraryRoot,
        folderURL: URL,
        fetcher: any CloudPlaylistFetching = LiveCloudService.shared,
        cache: CloudAudioCache = .shared,
        artworkCache: ArtworkCache = .shared,
        thumbnailCache: CloudThumbnailCache = .shared,
        sourceURLOverride: String? = nil,
        cookieOverride: String? = nil,
        kindHintOverride: LibraryRoot.Kind? = nil,
        providerIDOverride: String? = nil,
        onProgress: (@MainActor @Sendable (Int, Int, String) -> Void)? = nil
    ) async throws -> CloudUpdateSummary {
        try await performUpdateLocalFolder(
            root: root,
            folderURL: folderURL,
            fetcher: fetcher,
            cache: cache,
            artworkCache: artworkCache,
            thumbnailCache: thumbnailCache,
            sourceURLOverride: sourceURLOverride,
            cookieOverride: cookieOverride,
            kindHintOverride: kindHintOverride,
            providerIDOverride: providerIDOverride,
            onDetailedProgress: onProgress.map { callback in
                { @MainActor progress in
                    callback(progress.completedTracks, progress.totalTracks, progress.currentTrackTitle)
                }
            }
        )
    }

    static func updateLocalFolder(
        root: LibraryRoot,
        folderURL: URL,
        fetcher: any CloudPlaylistFetching = LiveCloudService.shared,
        cache: CloudAudioCache = .shared,
        artworkCache: ArtworkCache = .shared,
        thumbnailCache: CloudThumbnailCache = .shared,
        sourceURLOverride: String? = nil,
        cookieOverride: String? = nil,
        kindHintOverride: LibraryRoot.Kind? = nil,
        providerIDOverride: String? = nil,
        onDetailedProgress: @escaping @MainActor @Sendable (ExportProgress) -> Void
    ) async throws -> CloudUpdateSummary {
        try await performUpdateLocalFolder(
            root: root,
            folderURL: folderURL,
            fetcher: fetcher,
            cache: cache,
            artworkCache: artworkCache,
            thumbnailCache: thumbnailCache,
            sourceURLOverride: sourceURLOverride,
            cookieOverride: cookieOverride,
            kindHintOverride: kindHintOverride,
            providerIDOverride: providerIDOverride,
            onDetailedProgress: onDetailedProgress
        )
    }

    private static func performUpdateLocalFolder(
        root: LibraryRoot,
        folderURL: URL,
        fetcher: any CloudPlaylistFetching,
        cache: CloudAudioCache,
        artworkCache: ArtworkCache,
        thumbnailCache: CloudThumbnailCache,
        sourceURLOverride: String?,
        cookieOverride: String?,
        kindHintOverride: LibraryRoot.Kind?,
        providerIDOverride: String?,
        onDetailedProgress: (@MainActor @Sendable (ExportProgress) -> Void)?
    ) async throws -> CloudUpdateSummary {
        try Task.checkCancellation()
        let existingManifest = loadManifest(in: folderURL)
        let explicitSource = (sourceURLOverride ?? root.sourceURL)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let rawSource: String
        if let explicitSource, !explicitSource.isEmpty {
            rawSource = explicitSource
        } else {
            rawSource = (existingManifest?.sourceURL ?? root.remoteID ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard !rawSource.isEmpty else {
            throw CloudSourceError.invalidURL
        }
        let effectiveCookie = cookieOverride ?? root.cookie
        let kindHint = kindHintOverride ?? root.effectiveCloudKind ?? existingManifest?.cloudKind
        let providerHint = providerIDOverride ?? root.effectiveProviderID ?? existingManifest?.resolvedProviderID
        let fetched: CloudPlaylistResult
        if let providerFetcher = fetcher as? any CloudProviderPlaylistFetching {
            fetched = try await providerFetcher.fetchPlaylist(
                sourceURL: rawSource,
                cookie: effectiveCookie,
                kindHint: kindHint,
                providerIDHint: providerHint
            )
        } else {
            fetched = try await fetcher.fetchPlaylist(sourceURL: rawSource, cookie: effectiveCookie, kindHint: kindHint)
        }
        try Task.checkCancellation()

        let registry = (fetcher as? LiveCloudService)?.registry ?? CloudProviderRegistry.shared
        let resolvedProviderID = fetched.providerID ?? providerHint ?? fetched.kind.builtInProviderID
        let resolvedProvider = registry.provider(forID: resolvedProviderID)

        try FileManager.default.createDirectory(at: folderURL, withIntermediateDirectories: true)
        var trackedMap = existingManifest?.tracks ?? [:]
        var cloudLookupRoot = root
        cloudLookupRoot.kind = fetched.kind
        cloudLookupRoot.providerID = resolvedProviderID
        cloudLookupRoot.linkedProviderID = resolvedProviderID
        cloudLookupRoot.sourceURL = rawSource
        cloudLookupRoot.remoteID = fetched.remoteID
        cloudLookupRoot.cookie = effectiveCookie

        let now = Date()
        let sourceSlug = resolvedProvider?.trackCloudSource
            ?? fetched.kind.cloudSourceSlug
            ?? resolvedProviderID
            ?? "cloud"
        let total = fetched.tracks.count
        var addedCount = 0
        var skippedCount = 0
        var failedCount = 0
        var errors: [String] = []

        for (index, item) in fetched.tracks.enumerated() {
            try Task.checkCancellation()
            await onDetailedProgress?(ExportProgress(
                completedTracks: index,
                totalTracks: total,
                currentTrackTitle: item.title,
                bytesDownloaded: 0,
                totalBytes: nil,
                bytesPerSecond: 0
            ))

            if let existingName = trackedMap[item.remoteTrackID],
               FileManager.default.fileExists(atPath: folderURL.appendingPathComponent(existingName).path) {
                skippedCount += 1
                continue
            }

            let tempTrack = Track(
                id: Track.makeID(rootID: root.id, relativePath: item.relativePath),
                rootID: root.id,
                relativePath: item.relativePath,
                title: item.title,
                artist: item.artist,
                album: item.album,
                albumArtist: fetched.kind == .cloudGoogleDrive ? "Google Drive" : item.artist,
                trackNumber: item.trackNumber,
                discNumber: nil,
                duration: item.duration,
                fileSize: 0,
                modified: now,
                hasEmbeddedLyrics: false,
                dateAdded: now,
                artworkKey: nil,
                cloudSource: sourceSlug,
                remoteTrackID: item.remoteTrackID,
                remoteArtworkURL: item.artworkURL
            )

            do {
                let targetURL = try await exportTrack(
                    tempTrack,
                    root: cloudLookupRoot,
                    toFolder: folderURL,
                    cache: cache,
                    artworkCache: artworkCache,
                    thumbnailCache: thumbnailCache,
                    onProgress: onDetailedProgress.map { callback in
                        { @MainActor dlProgress in
                            callback(ExportProgress(
                                completedTracks: index,
                                totalTracks: total,
                                currentTrackTitle: item.title,
                                bytesDownloaded: dlProgress.bytesDownloaded,
                                totalBytes: dlProgress.totalBytes,
                                bytesPerSecond: dlProgress.bytesPerSecond
                            ))
                        }
                    }
                )
                trackedMap[item.remoteTrackID] = targetURL.lastPathComponent
                addedCount += 1
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                failedCount += 1
                errors.append("\(item.title): \(error.localizedDescription)")
            }
        }
        await onDetailedProgress?(ExportProgress(
            completedTracks: total,
            totalTracks: total,
            currentTrackTitle: "",
            bytesDownloaded: 0,
            totalBytes: nil,
            bytesPerSecond: 0
        ))

        let updatedManifest = CloudFolderManifest(
            sourceURL: rawSource,
            remoteID: fetched.remoteID,
            cloudKind: fetched.kind,
            lastSyncedEpochMs: Int64(Date().timeIntervalSince1970 * 1000),
            tracks: trackedMap,
            providerID: resolvedProviderID
        )
        try saveManifest(updatedManifest, in: folderURL)

        return CloudUpdateSummary(
            addedCount: addedCount,
            skippedCount: skippedCount,
            failedCount: failedCount,
            totalRemoteCount: total,
            errors: errors
        )
    }

    /// Resolves cover art in priority order: supplied bytes -> disk ArtworkCache -> remote HTTPS fetch.
    static func resolveArtworkData(
        for track: Track,
        suppliedData: Data? = nil,
        artworkCache: ArtworkCache = .shared,
        thumbnailCache: CloudThumbnailCache = .shared
    ) async -> Data? {
        if let suppliedData, !suppliedData.isEmpty {
            return suppliedData
        }
        if let key = track.artworkKey,
           let cachedDisk = artworkCache.data(forKey: key),
           !cachedDisk.isEmpty {
            return cachedDisk
        }
        if let remoteURL = track.remoteArtworkURL,
           let fetched = await thumbnailCache.loadImageData(from: remoteURL),
           !fetched.isEmpty {
            return fetched
        }
        return nil
    }

    private static func writeMetadataAndArtwork(
        for track: Track,
        to targetURL: URL,
        suppliedArtworkData: Data?,
        artworkCache: ArtworkCache,
        thumbnailCache: CloudThumbnailCache
    ) async {
        let resolvedArtwork = await resolveArtworkData(
            for: track,
            suppliedData: suppliedArtworkData,
            artworkCache: artworkCache,
            thumbnailCache: thumbnailCache
        )
        let payload = AudioTagWriter.Payload(
            title: track.title,
            artist: track.artist,
            album: track.album,
            genre: track.displayGenre,
            artworkData: resolvedArtwork
        )
        if AudioTagWriter.supportsEmbeddedArtwork(for: targetURL) {
            let wrote = await AudioTagWriter.write(payload, to: targetURL)
            if !wrote, let resolvedArtwork {
                _ = AudioTagWriter.writeArtworkSidecar(resolvedArtwork, alongside: targetURL)
            }
        } else if let resolvedArtwork {
            _ = AudioTagWriter.writeArtworkSidecar(resolvedArtwork, alongside: targetURL)
        }
    }

    /// Downloads/reuses cached audio for a single cloud `track` and copies it into `folderURL`.
    @discardableResult
    static func exportTrack(
        _ track: Track,
        root: LibraryRoot?,
        toFolder folderURL: URL,
        cache: CloudAudioCache = .shared,
        artworkData: Data? = nil,
        artworkCache: ArtworkCache = .shared,
        thumbnailCache: CloudThumbnailCache = .shared,
        onProgress: (@MainActor @Sendable (CloudDownloadProgress) -> Void)? = nil
    ) async throws -> URL {
        try Task.checkCancellation()
        try FileManager.default.createDirectory(at: folderURL, withIntermediateDirectories: true)
        let cachedURL = try await cache.resolveAndCache(
            track: track,
            root: root,
            onProgress: onProgress.map { callback in
                { progress in
                    Task { @MainActor in
                        callback(progress)
                    }
                }
            }
        )
        try Task.checkCancellation()
        let ext = cachedURL.pathExtension.isEmpty ? "m4a" : cachedURL.pathExtension
        let targetURL = uniqueFileURL(in: folderURL, baseName: track.title, fileExtension: ext)
        try FileManager.default.copyItem(at: cachedURL, to: targetURL)

        await writeMetadataAndArtwork(
            for: track,
            to: targetURL,
            suppliedArtworkData: artworkData,
            artworkCache: artworkCache,
            thumbnailCache: thumbnailCache
        )
        return targetURL
    }
}

/// Request carrier so context menu items can trigger single-track cloud export after closing.
@MainActor @Observable
final class CloudExportPrompt {
    struct Request {
        let track: Track
        let root: LibraryRoot?
        let onSaved: @MainActor (URL) async -> Void
    }

    var request: Request?
}

/// Hosts single-song cloud folder picker, background download submission, and completion alerts.
/// Kept free of store `@Environment` dependencies so evaluating it at a Mac sheet root is always safe.
struct CloudExportHostModifier: ViewModifier {
    @Environment(\.downloadManager) private var downloadManager
    @State private var prompt = CloudExportPrompt()
    @State private var showFolderPicker = false
    @State private var pendingRequest: CloudExportPrompt.Request?
    @State private var alertTitle = ""
    @State private var alertMessage: String?

    func body(content: Content) -> some View {
        content
            .environment(prompt)
            .onChange(of: prompt.request != nil) { _, hasRequest in
                guard hasRequest, let req = prompt.request else { return }
                pendingRequest = req
                prompt.request = nil
                showFolderPicker = true
            }
            .fileImporter(isPresented: $showFolderPicker, allowedContentTypes: [.folder]) { result in
                guard let req = pendingRequest else { return }
                pendingRequest = nil
                switch result {
                case .failure(let error):
                    alertTitle = L10n.tr("Couldn’t Save Song")
                    alertMessage = error.localizedDescription
                case .success(let folderURL):
                    startSingleExport(req, to: folderURL)
                }
            }
            .alert(
                alertTitle,
                isPresented: Binding(
                    get: { alertMessage != nil },
                    set: { if !$0 { alertMessage = nil } }
                )
            ) {
                Button(L10n.tr("OK"), role: .cancel) {}
            } message: {
                Text(alertMessage ?? "")
            }
    }

    private func startSingleExport(_ req: CloudExportPrompt.Request, to folderURL: URL) {
        let job = downloadManager.startSingleTrackExport(
            track: req.track,
            root: req.root,
            folderURL: folderURL,
            onSaved: req.onSaved
        )
        Task { @MainActor in
            while job.state == .running {
                try? await Task.sleep(for: .milliseconds(150))
            }
            switch job.state {
            case .completed:
                alertTitle = L10n.tr("Saved Song")
                alertMessage = job.summaryMessage
            case .failed(let message):
                alertTitle = L10n.tr("Couldn’t Save Song")
                alertMessage = message
            case .cancelled, .running:
                break
            }
        }
    }
}
