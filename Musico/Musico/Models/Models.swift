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
    var linkedCloudKind: Kind? = nil

    var isCloudTrackedLocal: Bool {
        kind.isLocal && !(sourceURL ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var effectiveCloudKind: Kind? {
        kind.isCloud ? kind : linkedCloudKind
    }

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
    var genre: String? = nil
    var cloudSource: String? = nil
    var remoteTrackID: String? = nil
    var remoteArtworkURL: String? = nil

    var isCloud: Bool { cloudSource != nil }

    var displayGenre: String {
        (genre ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

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
    var sortOrder: TrackSortOrder? = nil
}

struct TrackMetadataOverride: Codable, Hashable, Sendable {
    var title: String?
    var artist: String?
    var album: String?
    var genre: String?
    var artworkKey: String?
}

enum SearchField: String, Codable, Hashable, Sendable {
    case keyword = "Keyword"
    case artist = "Artist"
    case album = "Album"
    case genre = "Genre"

    static func queueName(field: SearchField, query: String) -> String {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        return "\(field.rawValue): \(trimmed)"
    }
}

enum TrackSortOrder: String, Codable, CaseIterable, Identifiable, Sendable {
    case custom = "Custom Order"
    case nameAscending = "Name (A–Z)"
    case nameDescending = "Name (Z–A)"
    case dateModified = "Date Modified"
    case mostPlayed = "Most Played"
    case leastPlayed = "Least Played"

    var id: String { rawValue }
}

struct FolderOrderState: Codable, Hashable, Sendable {
    var sortOrder: TrackSortOrder = .custom
    var customTrackIDs: [String] = []
}

enum TrackSorter {
    static func sort(
        _ tracks: [Track],
        by order: TrackSortOrder,
        playCount: (String) -> Int,
        lastPlayed: ((String) -> Date?)? = nil
    ) -> [Track] {
        guard order != .custom else { return tracks }
        let orderIndices = sortedIndexOrder(
            for: tracks.map(\.id),
            tracksByID: Dictionary(tracks.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first }),
            by: order,
            playCount: playCount,
            lastPlayed: lastPlayed
        )
        return orderIndices.map { tracks[$0] }
    }

    static func sortedIndexOrder(
        for trackIDs: [String],
        tracksByID: [String: Track],
        by order: TrackSortOrder,
        playCount: (String) -> Int,
        lastPlayed: ((String) -> Date?)? = nil
    ) -> [Int] {
        let indices = Array(trackIDs.indices)
        guard order != .custom else { return indices }
        return indices.sorted { lhsIdx, rhsIdx in
            let lhsID = trackIDs[lhsIdx]
            let rhsID = trackIDs[rhsIdx]
            guard let lhs = tracksByID[lhsID], let rhs = tracksByID[rhsID] else {
                if tracksByID[lhsID] != nil { return true }
                if tracksByID[rhsID] != nil { return false }
                return lhsIdx < rhsIdx
            }
            switch order {
            case .custom:
                return lhsIdx < rhsIdx
            case .nameAscending:
                let cmp = lhs.title.localizedStandardCompare(rhs.title)
                if cmp != .orderedSame { return cmp == .orderedAscending }
                let pathCmp = lhs.relativePath.localizedStandardCompare(rhs.relativePath)
                return pathCmp == .orderedSame ? lhsIdx < rhsIdx : pathCmp == .orderedAscending
            case .nameDescending:
                let cmp = lhs.title.localizedStandardCompare(rhs.title)
                if cmp != .orderedSame { return cmp == .orderedDescending }
                let pathCmp = lhs.relativePath.localizedStandardCompare(rhs.relativePath)
                return pathCmp == .orderedSame ? lhsIdx < rhsIdx : pathCmp == .orderedAscending
            case .dateModified:
                if lhs.modified != rhs.modified { return lhs.modified > rhs.modified }
                let cmp = lhs.title.localizedStandardCompare(rhs.title)
                return cmp == .orderedSame ? lhsIdx < rhsIdx : cmp == .orderedAscending
            case .mostPlayed:
                let c0 = playCount(lhs.id)
                let c1 = playCount(rhs.id)
                if c0 != c1 { return c0 > c1 }
                if let lp0 = lastPlayed?(lhs.id), let lp1 = lastPlayed?(rhs.id), lp0 != lp1 {
                    return lp0 > lp1
                } else if (lastPlayed?(lhs.id) != nil) != (lastPlayed?(rhs.id) != nil) {
                    return lastPlayed?(lhs.id) != nil
                }
                let cmp = lhs.title.localizedStandardCompare(rhs.title)
                return cmp == .orderedSame ? lhsIdx < rhsIdx : cmp == .orderedAscending
            case .leastPlayed:
                let c0 = playCount(lhs.id)
                let c1 = playCount(rhs.id)
                if c0 != c1 { return c0 < c1 }
                let cmp = lhs.title.localizedStandardCompare(rhs.title)
                return cmp == .orderedSame ? lhsIdx < rhsIdx : cmp == .orderedAscending
            }
        }
    }
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
