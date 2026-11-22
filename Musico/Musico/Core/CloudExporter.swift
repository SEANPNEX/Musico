import Foundation
import Observation
import SwiftUI
import UniformTypeIdentifiers

/// Exports cached or fetched cloud tracks and playlists into user-selected local folders.
enum CloudExporter {
    struct ExportSummary: Sendable, Equatable {
        let savedCount: Int
        let failedCount: Int
        let destinationURL: URL
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
        onProgress: (@MainActor @Sendable (Int, Int, String) -> Void)? = nil
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

        for (index, track) in cloudTracks.enumerated() {
            try Task.checkCancellation()
            await onProgress?(index, total, track.title)
            do {
                let cachedURL = try await cache.resolveAndCache(track: track, root: root)
                try Task.checkCancellation()
                let ext = cachedURL.pathExtension.isEmpty ? "m4a" : cachedURL.pathExtension
                let targetURL = uniqueFileURL(in: destinationURL, baseName: track.title, fileExtension: ext)
                try FileManager.default.copyItem(at: cachedURL, to: targetURL)
                savedCount += 1
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                failedCount += 1
                errors.append("\(track.title): \(error.localizedDescription)")
            }
        }
        await onProgress?(total, total, "")

        return ExportSummary(
            savedCount: savedCount,
            failedCount: failedCount,
            destinationURL: destinationURL,
            errors: errors
        )
    }

    /// Downloads/reuses cached audio for a single cloud `track` and copies it into `folderURL`.
    @discardableResult
    static func exportTrack(
        _ track: Track,
        root: LibraryRoot?,
        toFolder folderURL: URL,
        cache: CloudAudioCache = .shared
    ) async throws -> URL {
        try Task.checkCancellation()
        try FileManager.default.createDirectory(at: folderURL, withIntermediateDirectories: true)
        let cachedURL = try await cache.resolveAndCache(track: track, root: root)
        try Task.checkCancellation()
        let ext = cachedURL.pathExtension.isEmpty ? "m4a" : cachedURL.pathExtension
        let targetURL = uniqueFileURL(in: folderURL, baseName: track.title, fileExtension: ext)
        try FileManager.default.copyItem(at: cachedURL, to: targetURL)
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

/// Hosts single-song cloud folder picker, progress, and completion alerts.
/// Kept free of store `@Environment` dependencies so evaluating it at a Mac sheet root is always safe.
struct CloudExportHostModifier: ViewModifier {
    @State private var prompt = CloudExportPrompt()
    @State private var showFolderPicker = false
    @State private var pendingRequest: CloudExportPrompt.Request?
    @State private var exportingTitle: String?
    @State private var exportTask: Task<Void, Never>?
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
                    alertTitle = "Couldn’t Save Song"
                    alertMessage = error.localizedDescription
                case .success(let folderURL):
                    startSingleExport(req, to: folderURL)
                }
            }
            .alert(
                "Saving Song",
                isPresented: Binding(
                    get: { exportingTitle != nil },
                    set: { if !$0 { exportTask?.cancel(); exportingTitle = nil } }
                )
            ) {
                Button("Cancel", role: .cancel) { exportTask?.cancel() }
            } message: {
                Text("Saving “\(exportingTitle ?? "")”…")
            }
            .alert(
                alertTitle,
                isPresented: Binding(
                    get: { alertMessage != nil },
                    set: { if !$0 { alertMessage = nil } }
                )
            ) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(alertMessage ?? "")
            }
    }

    private func startSingleExport(_ req: CloudExportPrompt.Request, to folderURL: URL) {
        exportTask?.cancel()
        exportingTitle = req.track.title
        exportTask = Task { @MainActor in
            let scoped = folderURL.startAccessingSecurityScopedResource()
            defer {
                if scoped { folderURL.stopAccessingSecurityScopedResource() }
            }
            do {
                let savedURL = try await CloudExporter.exportTrack(req.track, root: req.root, toFolder: folderURL)
                await req.onSaved(savedURL)
                exportingTitle = nil
                alertTitle = "Saved Song"
                alertMessage = "Saved “\(savedURL.lastPathComponent)” to “\(folderURL.lastPathComponent)”."
            } catch is CancellationError {
                exportingTitle = nil
            } catch {
                exportingTitle = nil
                alertTitle = "Couldn’t Save Song"
                alertMessage = error.localizedDescription
            }
        }
    }
}
