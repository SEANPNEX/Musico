import Foundation

enum LyricsSource: Equatable, Sendable { case imported, sidecar, embedded }

enum LyricsError: LocalizedError {
    case unreadable
    var errorDescription: String? { "That file could not be read as lyrics text." }
}

/// Finds lyrics for a track: a file imported for it, then a sibling .lrc/.txt, then the embedded tag.
struct LyricsStore: Sendable {
    let directory: URL

    init(directory: URL) { self.directory = directory }

    private func importedURL(_ trackID: String) -> URL {
        directory.appendingPathComponent("\(trackID).lrc")
    }

    func lyrics(trackID: String, fileURL: URL?, embedded: String?) -> (Lyrics, LyricsSource)? {
        if let lyrics = read(importedURL(trackID)) { return (lyrics, .imported) }
        if let fileURL {
            let base = fileURL.deletingPathExtension()
            for ext in ["lrc", "txt"] {
                if let lyrics = read(base.appendingPathExtension(ext)) { return (lyrics, .sidecar) }
            }
        }
        if let embedded {
            let lyrics = LRCParser.parse(embedded)
            if !lyrics.lines.isEmpty { return (lyrics, .embedded) }
        }
        return nil
    }

    private func read(_ url: URL) -> Lyrics? {
        guard let data = try? Data(contentsOf: url), let text = LRCParser.decode(data) else { return nil }
        let lyrics = LRCParser.parse(text)
        return lyrics.lines.isEmpty ? nil : lyrics
    }

    func hasImported(trackID: String) -> Bool {
        FileManager.default.fileExists(atPath: importedURL(trackID).path)
    }

    /// Copies a user-picked lyrics file into the app, stored as UTF-8.
    func importLyrics(from url: URL, trackID: String) throws {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        guard let text = LRCParser.decode(data), !LRCParser.parse(text).lines.isEmpty else { throw LyricsError.unreadable }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data(text.utf8).write(to: importedURL(trackID), options: .atomic)
    }

    func removeImported(trackID: String) {
        try? FileManager.default.removeItem(at: importedURL(trackID))
    }
}
