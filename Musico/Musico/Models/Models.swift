import Foundation
import CryptoKit

struct LibraryRoot: Codable, Hashable, Identifiable, Sendable {
    enum Kind: String, Codable, Sendable {
        case picked, appFolder, cloudYouTube, cloudBilibili

        var isCloud: Bool {
            switch self {
            case .cloudYouTube, .cloudBilibili: true
            case .picked, .appFolder: false
            }
        }

        var isLocal: Bool { !isCloud }
    }

    /// Fixed id of the app's own Documents folder root.
    static let appFolderID = UUID(uuidString: "00000000-0000-0000-0000-00000000A990")!

    var id: UUID
    var name: String
    var kind: Kind
    var bookmark: Data?
    var sourceURL: String? = nil
    var remoteID: String? = nil
    var cookie: String? = nil
    var proxyURL: String? = nil
    var lastSynced: Date? = nil
    var excludedPaths: [String]? = nil

    var normalizedExcludedPaths: [String] { excludedPaths ?? [] }

    static func normalizeFolderPath(_ path: String) -> String {
        path
            .replacingOccurrences(of: "\\", with: "/")
            .precomposedStringWithCanonicalMapping
            .trimmingCharacters(in: CharacterSet(charactersIn: "/").union(.whitespacesAndNewlines))
    }

    static func folder(_ folderPath: String, contains relativePath: String) -> Bool {
        let normFolder = normalizeFolderPath(folderPath)
        guard !normFolder.isEmpty else { return false }
        let normRel = normalizeFolderPath(relativePath)
        return normRel == normFolder || normRel.hasPrefix(normFolder + "/")
    }

    func isExcluded(_ relativePath: String) -> Bool {
        let excluded = normalizedExcludedPaths
        guard !excluded.isEmpty else { return false }
        let normRel = Self.normalizeFolderPath(relativePath)
        return excluded.contains { ex in
            let normEx = Self.normalizeFolderPath(ex)
            guard !normEx.isEmpty else { return false }
            return normRel == normEx || normRel.hasPrefix(normEx + "/")
        }
    }
}

struct Track: Codable, Hashable, Identifiable, Sendable {
    var id: String
    var rootID: UUID
    var relativePath: String
    var title: String
    var artist: String
    var album: String
    var albumArtist: String
    var trackNumber: Int?
    var discNumber: Int?
    var duration: TimeInterval
    var fileSize: Int64
    var modified: Date
    var hasEmbeddedLyrics: Bool
    var dateAdded: Date
    /// Key into `ArtworkCache` (hash of the embedded image), shared by songs with the same cover.
    var artworkKey: String? = nil
    var cloudSource: String? = nil
    var remoteTrackID: String? = nil
    var remoteArtworkURL: String? = nil

    var isCloud: Bool { cloudSource != nil }

    static func makeID(rootID: UUID, relativePath: String) -> String {
        let digest = SHA256.hash(data: Data("\(rootID.uuidString)/\(relativePath)".utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    /// Folder part of `relativePath` ("" for files directly in the root).
    var folderPath: String {
        guard let slash = relativePath.lastIndex(of: "/") else { return "" }
        return String(relativePath[..<slash])
    }

    var fileName: String {
        guard let slash = relativePath.lastIndex(of: "/") else { return relativePath }
        return String(relativePath[relativePath.index(after: slash)...])
    }
}

struct Playlist: Codable, Hashable, Identifiable, Sendable {
    var id: UUID
    var name: String
    var trackIDs: [String]
}

enum RepeatMode: String, Codable, Sendable { case off, all, one }

struct EQSettings: Codable, Hashable, Sendable {
    static let frequencies: [Float] = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
    static let gainRange: ClosedRange<Float> = -12...12

    var enabled = false
    var preamp: Float = 0
    var gains: [Float] = Array(repeating: 0, count: EQSettings.frequencies.count)
    var presetName: String? = "Flat"
}

struct EQPreset: Hashable, Sendable {
    var name: String
    var gains: [Float]

    static let all: [EQPreset] = [
        EQPreset(name: "Flat", gains: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        EQPreset(name: "Bass Boost", gains: [6, 5, 4, 2, 0, 0, 0, 0, 0, 0]),
        EQPreset(name: "Treble Boost", gains: [0, 0, 0, 0, 0, 1, 2, 4, 5, 6]),
        EQPreset(name: "Rock", gains: [5, 4, 3, 1, -1, -1, 1, 3, 4, 5]),
        EQPreset(name: "Pop", gains: [-1, 0, 2, 4, 5, 4, 2, 0, -1, -1]),
        EQPreset(name: "Jazz", gains: [4, 3, 1, 2, -2, -2, 0, 1, 3, 4]),
        EQPreset(name: "Classical", gains: [4, 3, 2, 1, -1, -1, 0, 2, 3, 4]),
        EQPreset(name: "Vocal", gains: [-2, -2, -1, 1, 4, 4, 3, 1, 0, -1]),
    ]
}
