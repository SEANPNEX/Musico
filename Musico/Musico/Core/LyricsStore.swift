import Foundation

enum LyricsSource: Equatable, Sendable { case imported, sidecar, folderMatch, embedded }

extension Notification.Name {
    static let musicoLyricsDidChange = Notification.Name("MusicoLyricsDidChange")
}

enum LyricsError: LocalizedError {
    case unreadable
    case folderNotWritable
    var errorDescription: String? {
        switch self {
        case .unreadable:
            L10n.trNonisolated("That file could not be read as lyrics text.")
        case .folderNotWritable:
            L10n.trNonisolated("The song’s folder cannot be modified.")
        }
    }
}

/// Finds lyrics for a track using local sources only:
/// imported copy -> exact sibling .lrc/.txt -> non-recursive folder fuzzy match -> embedded tag.
struct LyricsStore: Sendable {
    let directory: URL

    init(directory: URL) { self.directory = directory }

    private func importedURL(_ trackID: String) -> URL {
        directory.appendingPathComponent("\(trackID).lrc")
    }

    func lyrics(
        trackID: String,
        fileURL: URL?,
        embedded: String?,
        trackTitle: String? = nil,
        trackArtist: String? = nil
    ) -> (Lyrics, LyricsSource)? {
        if let lyrics = read(importedURL(trackID)) { return (lyrics, .imported) }
        if let fileURL {
            let base = fileURL.deletingPathExtension()
            var exactURLs: Set<URL> = []
            for ext in ["lrc", "txt"] {
                let candidate = base.appendingPathExtension(ext)
                exactURLs.insert(candidate.standardizedFileURL)
                if let lyrics = read(candidate) { return (lyrics, .sidecar) }
            }
            if let folderLyrics = findFolderMatch(
                for: fileURL,
                excluding: exactURLs,
                trackTitle: trackTitle,
                trackArtist: trackArtist
            ) {
                return (folderLyrics, .folderMatch)
            }
        }
        if let embedded {
            let lyrics = LRCParser.parse(embedded)
            if !lyrics.lines.isEmpty { return (lyrics, .embedded) }
        }
        return nil
    }

    private func findFolderMatch(
        for audioURL: URL,
        excluding exactURLs: Set<URL>,
        trackTitle: String?,
        trackArtist: String?
    ) -> Lyrics? {
        guard audioURL.isFileURL else { return nil }
        let directoryURL = audioURL.deletingLastPathComponent()
        guard let children = try? FileManager.default.contentsOfDirectory(
            at: directoryURL,
            includingPropertiesForKeys: [.isRegularFileKey, .isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else {
            return nil
        }

        let audioStem = audioURL.deletingPathExtension().lastPathComponent
        struct ScoredCandidate {
            let fileName: String
            let isLRC: Bool
            let score: Double
            let lyrics: Lyrics
        }

        var candidates: [ScoredCandidate] = []
        for child in children {
            let standardized = child.standardizedFileURL
            if exactURLs.contains(standardized) { continue }
            let ext = child.pathExtension.lowercased()
            guard ext == "lrc" || ext == "txt" else { continue }
            if let values = try? child.resourceValues(forKeys: [.isDirectoryKey]),
               values.isDirectory == true {
                continue
            }
            let stem = child.deletingPathExtension().lastPathComponent
            let score = LyricsNameMatcher.matchScore(
                candidateStem: stem,
                audioStem: audioStem,
                trackTitle: trackTitle,
                trackArtist: trackArtist
            )
            guard score >= LyricsNameMatcher.minimumMatchScore,
                  let lyrics = read(child) else {
                continue
            }
            candidates.append(ScoredCandidate(
                fileName: child.lastPathComponent,
                isLRC: ext == "lrc",
                score: score,
                lyrics: lyrics
            ))
        }

        guard let best = candidates.max(by: { lhs, rhs in
            if abs(lhs.score - rhs.score) > 0.001 {
                return lhs.score < rhs.score
            }
            if lhs.lyrics.isTimed != rhs.lyrics.isTimed {
                return !lhs.lyrics.isTimed && rhs.lyrics.isTimed
            }
            if lhs.isLRC != rhs.isLRC {
                return !lhs.isLRC && rhs.isLRC
            }
            return lhs.fileName.localizedCaseInsensitiveCompare(rhs.fileName) == .orderedDescending
        }) else {
            return nil
        }
        return best.lyrics
    }

    private func read(_ url: URL) -> Lyrics? {
        guard let data = try? Data(contentsOf: url), let text = LRCParser.decode(data) else { return nil }
        let lyrics = LRCParser.parse(text)
        return lyrics.lines.isEmpty ? nil : lyrics
    }

    func hasImported(trackID: String) -> Bool {
        FileManager.default.fileExists(atPath: importedURL(trackID).path)
    }

    func rawImportedText(trackID: String) -> String? {
        guard let data = try? Data(contentsOf: importedURL(trackID)) else { return nil }
        return LRCParser.decode(data)
    }

    /// Copies a user-picked lyrics file into the app, stored as UTF-8.
    func importLyrics(from url: URL, trackID: String) throws {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        guard let text = LRCParser.decode(data) else { throw LyricsError.unreadable }
        try saveImportedLyrics(text: text, trackID: trackID)
    }

    /// Saves resolved/edited lyrics text into the app's imported store as UTF-8 `.lrc`.
    func saveImportedLyrics(text: String, trackID: String) throws {
        guard !LRCParser.parse(text).lines.isEmpty else { throw LyricsError.unreadable }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data(text.utf8).write(to: importedURL(trackID), options: .atomic)
    }

    func canExportSidecar(for audioURL: URL?) -> Bool {
        guard let audioURL, audioURL.isFileURL else { return false }
        let dir = audioURL.deletingLastPathComponent()
        return FileManager.default.isWritableFile(atPath: dir.path)
    }

    /// Exports UTF-8 `.lrc` sidecar next to a writable local audio file.
    func exportSidecarLyrics(text: String, for audioURL: URL) throws {
        guard !LRCParser.parse(text).lines.isEmpty else { throw LyricsError.unreadable }
        guard canExportSidecar(for: audioURL) else { throw LyricsError.folderNotWritable }
        let sidecarURL = audioURL.deletingPathExtension().appendingPathExtension("lrc")
        try Data(text.utf8).write(to: sidecarURL, options: .atomic)
    }

    func removeImported(trackID: String) {
        try? FileManager.default.removeItem(at: importedURL(trackID))
    }
}
