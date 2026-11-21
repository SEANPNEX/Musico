import Foundation

struct ScannedFile: Hashable, Sendable {
    var relativePath: String
    var fileSize: Int64
    var modified: Date
    /// An iCloud file that is not downloaded: only a hidden `.name.ext.icloud` stub is on disk.
    var isPlaceholder = false
}

enum FolderScanner {
    static let audioExtensions: Set<String> = ["mp3", "m4a", "aac", "flac", "wav", "aif", "aiff", "caf", "alac"]

    private static func isAudio(_ name: String) -> Bool {
        audioExtensions.contains((name as NSString).pathExtension.lowercased())
    }

    /// Recursively lists audio files under `root`, as NFC-normalised paths relative to it.
    /// Returns nil when the folder cannot be listed at all (offline, unmounted), which is
    /// different from a folder that is readable and empty.
    static func scan(root: URL) -> [ScannedFile]? {
        guard (try? FileManager.default.contentsOfDirectory(atPath: root.path)) != nil,
              let enumerator = FileManager.default.enumerator(atPath: root.path) else { return nil }
        var result: [ScannedFile] = []
        while let path = enumerator.nextObject() as? String {
            let name = (path as NSString).lastPathComponent
            let attributes = enumerator.fileAttributes
            let isDirectory = attributes?[.type] as? FileAttributeType == .typeDirectory
            let size = (attributes?[.size] as? NSNumber)?.int64Value ?? 0
            let modified = attributes?[.modificationDate] as? Date ?? .distantPast
            if name.hasPrefix(".") {
                if isDirectory {
                    enumerator.skipDescendants()
                } else if name.hasSuffix(".icloud") {
                    let realName = String(name.dropFirst().dropLast(".icloud".count))
                    guard isAudio(realName) else { continue }
                    let folder = (path as NSString).deletingLastPathComponent
                    let realPath = folder.isEmpty ? realName : folder + "/" + realName
                    result.append(ScannedFile(relativePath: realPath.precomposedStringWithCanonicalMapping,
                                              fileSize: size, modified: modified, isPlaceholder: true))
                }
                continue
            }
            guard !isDirectory, isAudio(name) else { continue }
            result.append(ScannedFile(relativePath: path.precomposedStringWithCanonicalMapping,
                                      fileSize: size, modified: modified))
        }
        return result.sorted { $0.relativePath.localizedStandardCompare($1.relativePath) == .orderedAscending }
    }

    static func url(root: URL, relativePath: String) -> URL {
        root.appendingPathComponent(relativePath, isDirectory: false)
    }
}
