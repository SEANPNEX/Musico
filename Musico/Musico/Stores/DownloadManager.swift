import Foundation
import Observation
import SwiftUI

@MainActor @Observable
final class DownloadJob: Identifiable {
    enum Kind: Sendable, Equatable {
        case playlistExport
        case singleTrackExport
        case folderUpdate
    }

    enum State: Sendable, Equatable {
        case running
        case completed
        case cancelled
        case failed(String)
    }

    let id = UUID()
    let kind: Kind
    let title: String
    var state: State = .running
    var completedTracks: Int = 0
    var totalTracks: Int = 0
    var currentTrackTitle: String = ""
    var bytesDownloaded: Int64 = 0
    var totalBytes: Int64?
    var bytesPerSecond: Double = 0
    var summaryMessage: String?
    var errors: [String] = []
    var destinationURL: URL?
    var exportSummary: CloudExporter.ExportSummary?
    var updateSummary: CloudExporter.CloudUpdateSummary?

    init(kind: Kind, title: String, totalTracks: Int = 0, currentTrackTitle: String = "") {
        self.kind = kind
        self.title = title
        self.totalTracks = totalTracks
        self.currentTrackTitle = currentTrackTitle
    }

    var isFinished: Bool {
        state != .running
    }

    var overallFraction: Double? {
        if totalTracks > 1 {
            let currentTrackFraction: Double
            if let totalBytes, totalBytes > 0 {
                currentTrackFraction = min(max(Double(bytesDownloaded) / Double(totalBytes), 0), 1)
            } else {
                currentTrackFraction = 0
            }
            return min(max((Double(completedTracks) + currentTrackFraction) / Double(totalTracks), 0), 1)
        }
        if let totalBytes, totalBytes > 0 {
            return min(max(Double(bytesDownloaded) / Double(totalBytes), 0), 1)
        }
        if totalTracks == 1, completedTracks >= 1 {
            return 1
        }
        return nil
    }
}

/// Owns background cloud export and sync jobs so sheet dismissal does not interrupt downloads.
@MainActor @Observable
final class DownloadManager {
    private(set) var jobs: [DownloadJob] = []
    @ObservationIgnored private var tasks: [UUID: Task<Void, Never>] = [:]
    @ObservationIgnored private let cache: CloudAudioCache

    nonisolated init(cache: CloudAudioCache = .shared) {
        self.cache = cache
    }

    var activeJobs: [DownloadJob] {
        jobs.filter { $0.state == .running }
    }

    var finishedJobs: [DownloadJob] {
        jobs.filter { $0.state != .running }
    }

    var hasActiveDownloads: Bool {
        !activeJobs.isEmpty
    }

    @discardableResult
    func startPlaylistExport(
        root: LibraryRoot,
        tracks: [Track],
        parentURL: URL,
        folderName: String,
        addToLibrary: Bool,
        library: LibraryStore?,
        isWholeRootExport: Bool = true
    ) -> DownloadJob {
        let cloudTracks = tracks.filter(\.isCloud)
        let displayTitle = folderName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? L10n.displayRootName(root)
            : folderName.trimmingCharacters(in: .whitespacesAndNewlines)
        let job = DownloadJob(
            kind: .playlistExport,
            title: displayTitle,
            totalTracks: cloudTracks.count,
            currentTrackTitle: cloudTracks.first?.title ?? ""
        )
        jobs.insert(job, at: 0)

        let cache = self.cache
        let task = Task { @MainActor [weak self] in
            let scoped = parentURL.startAccessingSecurityScopedResource()
            defer {
                if scoped { parentURL.stopAccessingSecurityScopedResource() }
                self?.tasks[job.id] = nil
            }
            do {
                let result = try await CloudExporter.exportPlaylist(
                    root: root,
                    tracks: cloudTracks,
                    toParentFolder: parentURL,
                    folderName: folderName,
                    cache: cache,
                    artworkCache: library?.artwork ?? .shared,
                    onDetailedProgress: { progress in
                        guard job.state == .running else { return }
                        job.completedTracks = progress.completedTracks
                        job.totalTracks = progress.totalTracks
                        job.currentTrackTitle = progress.currentTrackTitle
                        job.bytesDownloaded = progress.bytesDownloaded
                        job.totalBytes = progress.totalBytes
                        job.bytesPerSecond = progress.bytesPerSecond
                    }
                )
                guard !Task.isCancelled else {
                    job.state = .cancelled
                    job.bytesPerSecond = 0
                    job.summaryMessage = L10n.tr("Export cancelled.")
                    return
                }
                job.exportSummary = result
                job.destinationURL = result.destinationURL
                job.errors = result.errors
                job.completedTracks = cloudTracks.count
                job.totalTracks = cloudTracks.count
                job.bytesPerSecond = 0

                let shouldRegister = result.savedCount > 0 && (library.map { lib in
                    addToLibrary || lib.containsLocalURL(result.destinationURL)
                } ?? false)
                if shouldRegister, let library, FileManager.default.fileExists(atPath: result.destinationURL.path) {
                    let shouldMergeCloudRoot = root.kind.isCloud && isWholeRootExport
                    do {
                        try await library.registerExportedCloudFolder(
                            destinationURL: result.destinationURL,
                            from: root,
                            mergeCloudRoot: shouldMergeCloudRoot
                        )
                    } catch {
                        job.errors.append(L10n.tr("Saved songs, but couldn’t add folder to library: %@", error.localizedDescription))
                    }
                }

                job.summaryMessage = L10n.tr(
                    "Saved %@ to “%@”",
                    L10n.songCount(result.savedCount),
                    result.destinationURL.lastPathComponent
                )
                job.state = .completed
            } catch is CancellationError {
                job.bytesPerSecond = 0
                job.state = .cancelled
                job.summaryMessage = L10n.tr("Export cancelled.")
            } catch {
                job.bytesPerSecond = 0
                job.state = .failed(error.localizedDescription)
                job.summaryMessage = error.localizedDescription
            }
        }
        tasks[job.id] = task
        return job
    }

    @discardableResult
    func startSingleTrackExport(
        track: Track,
        root: LibraryRoot?,
        folderURL: URL,
        qualityID: String? = nil,
        onSaved: (@MainActor (URL) async -> Void)? = nil
    ) -> DownloadJob {
        let job = DownloadJob(
            kind: .singleTrackExport,
            title: track.title,
            totalTracks: 1,
            currentTrackTitle: track.title
        )
        jobs.insert(job, at: 0)

        let cache = self.cache
        let task = Task { @MainActor [weak self] in
            let scoped = folderURL.startAccessingSecurityScopedResource()
            defer {
                if scoped { folderURL.stopAccessingSecurityScopedResource() }
                self?.tasks[job.id] = nil
            }
            do {
                let savedURL = try await CloudExporter.exportTrack(
                    track,
                    root: root,
                    toFolder: folderURL,
                    cache: cache,
                    qualityID: qualityID,
                    onProgress: { progress in
                        guard job.state == .running else { return }
                        job.bytesDownloaded = progress.bytesDownloaded
                        job.totalBytes = progress.totalBytes
                        job.bytesPerSecond = progress.bytesPerSecond
                    }
                )
                guard !Task.isCancelled else {
                    job.bytesPerSecond = 0
                    job.state = .cancelled
                    job.summaryMessage = L10n.tr("Export cancelled.")
                    return
                }
                await onSaved?(savedURL)
                job.destinationURL = savedURL
                job.completedTracks = 1
                job.bytesPerSecond = 0
                job.summaryMessage = L10n.tr("Saved “%@” to “%@”.", savedURL.lastPathComponent, folderURL.lastPathComponent)
                job.state = .completed
            } catch is CancellationError {
                job.bytesPerSecond = 0
                job.state = .cancelled
                job.summaryMessage = L10n.tr("Export cancelled.")
            } catch {
                job.bytesPerSecond = 0
                job.state = .failed(error.localizedDescription)
                job.summaryMessage = error.localizedDescription
            }
        }
        tasks[job.id] = task
        return job
    }

    @discardableResult
    func startTrackedFolderUpdate(
        root: LibraryRoot,
        subfolderPath: String = "",
        tracking: CloudTrackingInfo? = nil,
        library: LibraryStore
    ) -> DownloadJob {
        let normPath = LibraryRoot.normalizeFolderPath(subfolderPath)
        let displayTitle = normPath.isEmpty
            ? L10n.displayRootName(root)
            : (normPath as NSString).lastPathComponent
        let job = DownloadJob(
            kind: .folderUpdate,
            title: displayTitle,
            totalTracks: 0,
            currentTrackTitle: ""
        )
        jobs.insert(job, at: 0)

        let cache = self.cache
        let task = Task { @MainActor [weak self] in
            defer { self?.tasks[job.id] = nil }
            do {
                _ = tracking
                let result = try await library.updateTrackedLocalRoot(
                    root.id,
                    path: normPath,
                    cache: cache,
                    onDetailedProgress: { progress in
                        guard job.state == .running else { return }
                        job.completedTracks = progress.completedTracks
                        job.totalTracks = progress.totalTracks
                        job.currentTrackTitle = progress.currentTrackTitle
                        job.bytesDownloaded = progress.bytesDownloaded
                        job.totalBytes = progress.totalBytes
                        job.bytesPerSecond = progress.bytesPerSecond
                    }
                )
                guard !Task.isCancelled else {
                    job.bytesPerSecond = 0
                    job.state = .cancelled
                    job.summaryMessage = L10n.tr("Update cancelled.")
                    return
                }
                job.updateSummary = result
                job.errors = result.errors
                job.completedTracks = result.totalRemoteCount
                job.totalTracks = result.totalRemoteCount
                job.bytesPerSecond = 0
                job.summaryMessage = result.addedCount > 0
                    ? L10n.tr("Added %@ new (%@ already saved).", L10n.songCount(result.addedCount), String(result.skippedCount))
                    : L10n.tr("Already up to date (%@).", L10n.songCount(result.skippedCount))
                job.state = .completed
            } catch is CancellationError {
                job.bytesPerSecond = 0
                job.state = .cancelled
                job.summaryMessage = L10n.tr("Update cancelled.")
            } catch {
                job.bytesPerSecond = 0
                job.state = .failed(error.localizedDescription)
                job.summaryMessage = error.localizedDescription
            }
        }
        tasks[job.id] = task
        return job
    }

    func cancel(jobID: UUID) {
        tasks[jobID]?.cancel()
        tasks[jobID] = nil
        if let job = jobs.first(where: { $0.id == jobID }), job.state == .running {
            job.bytesPerSecond = 0
            job.state = .cancelled
            job.summaryMessage = job.kind == .folderUpdate ? L10n.tr("Update cancelled.") : L10n.tr("Export cancelled.")
        }
    }

    func dismiss(jobID: UUID) {
        tasks[jobID]?.cancel()
        tasks[jobID] = nil
        jobs.removeAll { $0.id == jobID }
    }

    func clearFinished() {
        jobs.removeAll { $0.state != .running }
    }

    nonisolated static func formattedBytes(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: max(bytes, 0), countStyle: .file)
    }

    nonisolated static func formattedSpeed(_ bytesPerSecond: Double) -> String {
        "\(formattedBytes(Int64(max(bytesPerSecond, 0))))/s"
    }

    nonisolated static func formattedPercentage(_ fraction: Double) -> String {
        let clamped = min(max(fraction, 0), 1)
        return "\(Int((clamped * 100).rounded()))%"
    }
}

extension EnvironmentValues {
    @Entry var downloadManager = DownloadManager()
}
