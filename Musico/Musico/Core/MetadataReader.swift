import AVFoundation

struct TrackMetadata: Sendable {
    var title: String?
    var artist: String?
    var album: String?
    var albumArtist: String?
    var genre: String?
    var trackNumber: Int?
    var discNumber: Int?
    var duration: TimeInterval
    var artwork: Data?
    var lyrics: String?
}

protocol MetadataReading: Sendable {
    func read(_ url: URL) async -> TrackMetadata
}

/// Reads tags with AVFoundation (ID3, iTunes/MP4 atoms, Vorbis comments where the system exposes them).
struct MetadataReader: MetadataReading {
    func read(_ url: URL) async -> TrackMetadata {
        let asset = AVURLAsset(url: url)
        var result = TrackMetadata(duration: 0)
        if let duration = try? await asset.load(.duration) {
            let seconds = CMTimeGetSeconds(duration)
            if seconds.isFinite, seconds > 0 { result.duration = seconds }
        }

        let common = (try? await asset.load(.commonMetadata)) ?? []
        func commonString(_ id: AVMetadataIdentifier) async -> String? {
            guard let item = AVMetadataItem.metadataItems(from: common, filteredByIdentifier: id).first else { return nil }
            return Self.clean(try? await item.load(.stringValue))
        }
        result.title = await commonString(.commonIdentifierTitle)
        result.artist = await commonString(.commonIdentifierArtist)
        result.album = await commonString(.commonIdentifierAlbumName)
        if let item = AVMetadataItem.metadataItems(from: common, filteredByIdentifier: .commonIdentifierArtwork).first {
            result.artwork = try? await item.load(.dataValue)
        }

        for item in (try? await asset.load(.metadata)) ?? [] {
            let name = ((item.key as? String) ?? item.identifier?.rawValue.components(separatedBy: "/").last ?? "").uppercased()
            switch (item.identifier, name) {
            case (.id3MetadataTrackNumber?, _), (.iTunesMetadataTrackNumber?, _), (_, "TRACKNUMBER"):
                if result.trackNumber == nil { result.trackNumber = await Self.number(item) }
            case (.id3MetadataPartOfASet?, _), (.iTunesMetadataDiscNumber?, _), (_, "DISCNUMBER"):
                if result.discNumber == nil { result.discNumber = await Self.number(item) }
            case (.id3MetadataBand?, _), (.iTunesMetadataAlbumArtist?, _), (_, "ALBUMARTIST"), (_, "ALBUM ARTIST"):
                if result.albumArtist == nil { result.albumArtist = Self.clean(try? await item.load(.stringValue)) }
            case (.id3MetadataUnsynchronizedLyric?, _), (.iTunesMetadataLyrics?, _), (_, "LYRICS"), (_, "UNSYNCEDLYRICS"):
                if result.lyrics == nil { result.lyrics = Self.clean(try? await item.load(.stringValue)) }
            case (.id3MetadataContentType?, _), (.iTunesMetadataUserGenre?, _), (.quickTimeMetadataGenre?, _), (_, "GENRE"), (_, "TCON"), (_, "©GEN"):
                if result.genre == nil {
                    result.genre = Self.cleanGenre(try? await item.load(.stringValue))
                }
            default:
                break
            }
        }
        if result.genre == nil {
            result.genre = await commonString(.commonIdentifierType).flatMap { Self.cleanGenre($0) }
        }
        if result.lyrics == nil { result.lyrics = Self.clean(try? await asset.load(.lyrics)) }
        if url.pathExtension.lowercased() == "flac", let flac = FLACTags.read(url) {
            func text(_ keys: String...) -> String? { keys.lazy.compactMap { Self.clean(flac.comments[$0]) }.first }
            func number(_ key: String) -> Int? {
                guard let value = Int((flac.comments[key] ?? "").prefix { $0.isNumber }), value > 0 else { return nil }
                return value
            }
            result.title = result.title ?? text("TITLE")
            result.artist = result.artist ?? text("ARTIST")
            result.album = result.album ?? text("ALBUM")
            result.albumArtist = result.albumArtist ?? text("ALBUMARTIST", "ALBUM ARTIST")
            result.genre = result.genre ?? text("GENRE")
            result.trackNumber = result.trackNumber ?? number("TRACKNUMBER")
            result.discNumber = result.discNumber ?? number("DISCNUMBER")
            result.lyrics = result.lyrics ?? text("LYRICS", "UNSYNCEDLYRICS")
            result.artwork = result.artwork ?? flac.picture
        }
        if result.artwork == nil {
            result.artwork = Self.loadSiblingArtwork(for: url)
        }
        return result
    }

    private static let siblingArtworkExtensions = ["jpg", "jpeg", "png", "webp"]

    /// Loads `<stem>.jpg` / `<stem>.jpeg` / `<stem>.png` / `<stem>.webp` next to an audio file when embedded art is absent.
    static func loadSiblingArtwork(for audioURL: URL) -> Data? {
        let dir = audioURL.deletingLastPathComponent()
        let stem = audioURL.deletingPathExtension().lastPathComponent
        guard !stem.isEmpty else { return nil }
        let fm = FileManager.default
        for ext in siblingArtworkExtensions {
            let candidate = dir.appendingPathComponent("\(stem).\(ext)")
            if fm.fileExists(atPath: candidate.path),
               let data = try? Data(contentsOf: candidate),
               !data.isEmpty {
                return data
            }
        }
        guard let entries = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]) else {
            return nil
        }
        for entry in entries {
            let ext = entry.pathExtension.lowercased()
            guard siblingArtworkExtensions.contains(ext) else { continue }
            if entry.deletingPathExtension().lastPathComponent == stem,
               let data = try? Data(contentsOf: entry),
               !data.isEmpty {
                return data
            }
        }
        return nil
    }

    private static func cleanGenre(_ value: String??) -> String? {
        guard var text = clean(value) else { return nil }
        if text.hasPrefix("("), let close = text.firstIndex(of: ")") {
            let inside = text[text.index(after: text.startIndex)..<close]
            if !inside.isEmpty, inside.allSatisfy(\.isNumber) {
                let after = String(text[text.index(after: close)...]).trimmingCharacters(in: .whitespacesAndNewlines)
                if !after.isEmpty {
                    text = after
                }
            }
        }
        return text
    }

    private static func clean(_ value: String??) -> String? {
        guard let text = (value ?? nil)?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return nil }
        return text
    }

    /// Track/disc numbers come as "3", "3/12", a number, or the MP4 binary form (bytes 2-3 big-endian).
    private static func number(_ item: AVMetadataItem) async -> Int? {
        if let text = try? await item.load(.stringValue) {
            let digits = text.trimmingCharacters(in: .whitespaces).prefix { $0.isNumber }
            if let value = Int(digits), value > 0 { return value }
        }
        if let value = try? await item.load(.numberValue), value.intValue > 0 { return value.intValue }
        if let data = try? await item.load(.dataValue), data.count >= 4 {
            let bytes = [UInt8](data)
            let value = Int(bytes[2]) << 8 | Int(bytes[3])
            if value > 0 { return value }
        }
        return nil
    }
}
