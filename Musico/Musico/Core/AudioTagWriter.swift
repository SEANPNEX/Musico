import AVFoundation
import Foundation
import UIKit

enum ArtworkImageFormat: String, Equatable, Sendable {
    case jpeg
    case png
    case webp

    var mimeType: String {
        switch self {
        case .jpeg: "image/jpeg"
        case .png: "image/png"
        case .webp: "image/webp"
        }
    }

    var fileExtension: String {
        switch self {
        case .jpeg: "jpg"
        case .png: "png"
        case .webp: "webp"
        }
    }

    static func detect(in data: Data) -> ArtworkImageFormat? {
        guard data.count >= 4 else { return nil }
        if data.starts(with: [0x89, 0x50, 0x4E, 0x47]) {
            return .png
        }
        if data.starts(with: [0xFF, 0xD8, 0xFF]) {
            return .jpeg
        }
        if data.count >= 12 {
            let b = [UInt8](data.prefix(12))
            if b[0] == 0x52, b[1] == 0x49, b[2] == 0x46, b[3] == 0x46,
               b[8] == 0x57, b[9] == 0x45, b[10] == 0x42, b[11] == 0x50 {
                return .webp
            }
        }
        return nil
    }
}

enum AudioTagWriter {
    struct Payload: Sendable {
        var title: String
        var artist: String
        var album: String
        var genre: String
        var artworkData: Data?
    }

    static let embeddableExtensions: Set<String> = ["flac", "m4a", "mp4", "mp3"]

    static func supportsEmbeddedArtwork(for url: URL) -> Bool {
        embeddableExtensions.contains(url.pathExtension.lowercased())
    }

    /// Writes a sibling `<stem>.jpg` or `<stem>.png` alongside non-embeddable audio files (e.g. `.webm`).
    @discardableResult
    static func writeArtworkSidecar(_ imageData: Data, alongside audioURL: URL) -> URL? {
        guard !imageData.isEmpty else { return nil }
        let stem = audioURL.deletingPathExtension().lastPathComponent
        guard !stem.isEmpty else { return nil }

        let targetExt: String
        let outputData: Data
        switch ArtworkImageFormat.detect(in: imageData) {
        case .png:
            targetExt = "png"
            outputData = imageData
        case .jpeg:
            targetExt = "jpg"
            outputData = imageData
        case .webp, .none:
            if let image = UIImage(data: imageData),
               let jpeg = image.jpegData(compressionQuality: 0.9) {
                targetExt = "jpg"
                outputData = jpeg
            } else if ArtworkImageFormat.detect(in: imageData) == .webp {
                targetExt = "webp"
                outputData = imageData
            } else {
                return nil
            }
        }

        let sidecarURL = audioURL.deletingLastPathComponent().appendingPathComponent("\(stem).\(targetExt)")
        do {
            try outputData.write(to: sidecarURL, options: .atomic)
            return sidecarURL
        } catch {
            return nil
        }
    }

    /// Best-effort tag writing to writable local audio files. Fails silently on read-only or unsupported formats.
    @discardableResult
    static func write(_ payload: Payload, to url: URL) async -> Bool {
        guard FileManager.default.isWritableFile(atPath: url.path) else { return false }
        let ext = url.pathExtension.lowercased()
        switch ext {
        case "flac":
            return writeFLAC(payload, to: url)
        case "m4a", "mp4":
            return await writeM4A(payload, to: url)
        case "mp3":
            return writeMP3(payload, to: url)
        default:
            return false
        }
    }

    // MARK: - FLAC

    private static func writeFLAC(_ payload: Payload, to url: URL) -> Bool {
        guard let data = try? Data(contentsOf: url), data.count >= 8 else { return false }
        var offset = 0
        if data.prefix(3) == Data([0x49, 0x44, 0x33]), data.count >= 10 {
            let size = data[6...9].reduce(0) { $0 << 7 | Int($1 & 0x7F) }
            offset = 10 + size
        }
        guard offset + 4 <= data.count,
              data[offset..<(offset + 4)] == Data([0x66, 0x4C, 0x61, 0x43]) else {
            return false
        }
        let flacStart = offset
        offset += 4

        struct Block {
            var type: UInt8
            var body: Data
        }

        var blocks: [Block] = []
        var existingComments: [(String, String)] = []
        var vendor = "Musico"
        var hasLast = false

        while offset + 4 <= data.count {
            let h0 = data[offset]
            let isLast = (h0 & 0x80) != 0
            let type = h0 & 0x7F
            let length = Int(data[offset + 1]) << 16 | Int(data[offset + 2]) << 8 | Int(data[offset + 3])
            offset += 4
            guard offset + length <= data.count else { return false }
            let body = data[offset..<(offset + length)]
            offset += length
            if type == 4 {
                let parsed = parseVorbisComments(Data(body))
                if let v = parsed.vendor { vendor = v }
                existingComments = parsed.comments
            } else if type == 6, payload.artworkData != nil {
                // Replaced below with updated front cover.
            } else if type != 1 { // Drop padding blocks; we keep metadata compact.
                blocks.append(Block(type: type, body: Data(body)))
            }
            if isLast {
                hasLast = true
                break
            }
        }
        guard hasLast else { return false }
        let audioFrames = data[offset...]

        // Rebuild Vorbis comments preserving unrelated keys.
        let managedKeys: Set<String> = ["TITLE", "ARTIST", "ALBUM", "GENRE"]
        var mergedComments = existingComments.filter { !managedKeys.contains($0.0.uppercased()) }
        mergedComments.append(("TITLE", payload.title))
        mergedComments.append(("ARTIST", payload.artist))
        mergedComments.append(("ALBUM", payload.album))
        if !payload.genre.isEmpty {
            mergedComments.append(("GENRE", payload.genre))
        }
        let commentBody = encodeVorbisComments(vendor: vendor, comments: mergedComments)
        blocks.append(Block(type: 4, body: commentBody))

        if let artworkData = payload.artworkData, !artworkData.isEmpty {
            blocks.append(Block(type: 6, body: encodeFLACPicture(artworkData)))
        }

        var out = Data()
        if flacStart > 0 {
            out.append(data[0..<flacStart])
        }
        out.append(contentsOf: [0x66, 0x4C, 0x61, 0x43]) // "fLaC"
        for (idx, block) in blocks.enumerated() {
            let isLast = idx == blocks.count - 1
            let len = block.body.count
            guard len <= 0xFFFFFF else { return false }
            out.append((isLast ? 0x80 : 0x00) | (block.type & 0x7F))
            out.append(UInt8((len >> 16) & 0xFF))
            out.append(UInt8((len >> 8) & 0xFF))
            out.append(UInt8(len & 0xFF))
            out.append(block.body)
        }
        out.append(audioFrames)

        return atomicReplace(url: url, with: out) { tempURL in
            FLACTags.read(tempURL) != nil
        }
    }

    private static func parseVorbisComments(_ body: Data) -> (vendor: String?, comments: [(String, String)]) {
        let bytes = [UInt8](body)
        var cursor = 0
        func little() -> Int? {
            guard cursor + 4 <= bytes.count else { return nil }
            defer { cursor += 4 }
            return Int(bytes[cursor]) | Int(bytes[cursor + 1]) << 8 | Int(bytes[cursor + 2]) << 16 | Int(bytes[cursor + 3]) << 24
        }
        guard let vendorLen = little(), vendorLen >= 0, cursor + vendorLen <= bytes.count else { return (nil, []) }
        let vendor = String(decoding: bytes[cursor..<(cursor + vendorLen)], as: UTF8.self)
        cursor += vendorLen
        guard let count = little(), count >= 0 else { return (vendor, []) }
        var result: [(String, String)] = []
        for _ in 0..<count {
            guard let len = little(), len >= 0, cursor + len <= bytes.count else { break }
            let str = String(decoding: bytes[cursor..<(cursor + len)], as: UTF8.self)
            cursor += len
            if let eq = str.firstIndex(of: "=") {
                result.append((String(str[..<eq]), String(str[str.index(after: eq)...])))
            }
        }
        return (vendor, result)
    }

    private static func encodeVorbisComments(vendor: String, comments: [(String, String)]) -> Data {
        func appendLE32(_ value: Int, to data: inout Data) {
            var le = UInt32(value).littleEndian
            withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
        }
        var data = Data()
        let vendorData = Data(vendor.utf8)
        appendLE32(vendorData.count, to: &data)
        data.append(vendorData)
        appendLE32(comments.count, to: &data)
        for (key, value) in comments {
            let entry = Data("\(key)=\(value)".utf8)
            appendLE32(entry.count, to: &data)
            data.append(entry)
        }
        return data
    }

    private static func encodeFLACPicture(_ imageData: Data) -> Data {
        func appendBE32(_ value: Int, to data: inout Data) {
            var be = UInt32(value).bigEndian
            withUnsafeBytes(of: &be) { data.append(contentsOf: $0) }
        }
        let mime = (ArtworkImageFormat.detect(in: imageData) ?? .jpeg).mimeType
        let mimeData = Data(mime.utf8)
        var data = Data()
        appendBE32(3, to: &data) // Front cover
        appendBE32(mimeData.count, to: &data)
        data.append(mimeData)
        appendBE32(0, to: &data) // Description length
        appendBE32(0, to: &data) // Width
        appendBE32(0, to: &data) // Height
        appendBE32(0, to: &data) // Color depth
        appendBE32(0, to: &data) // Indexed colors
        appendBE32(imageData.count, to: &data)
        data.append(imageData)
        return data
    }

    // MARK: - M4A

    private static func writeM4A(_ payload: Payload, to url: URL) async -> Bool {
        let asset = AVURLAsset(url: url)
        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else {
            return false
        }
        let tempURL = url.deletingLastPathComponent()
            .appendingPathComponent(".\(UUID().uuidString).tmp.m4a")
        defer { try? FileManager.default.removeItem(at: tempURL) }

        var items: [AVMutableMetadataItem] = []
        func makeTextItem(keySpace: AVMetadataKeySpace, key: AVMetadataKey, value: String) -> AVMutableMetadataItem {
            let item = AVMutableMetadataItem()
            item.keySpace = keySpace
            item.key = key as NSString
            item.value = value as NSString
            return item
        }
        items.append(makeTextItem(keySpace: .common, key: .commonKeyTitle, value: payload.title))
        items.append(makeTextItem(keySpace: .common, key: .commonKeyArtist, value: payload.artist))
        items.append(makeTextItem(keySpace: .common, key: .commonKeyAlbumName, value: payload.album))
        if !payload.genre.isEmpty {
            let genreItem = AVMutableMetadataItem()
            genreItem.identifier = .iTunesMetadataUserGenre
            genreItem.value = payload.genre as NSString
            items.append(genreItem)
        }
        if let artworkData = payload.artworkData, !artworkData.isEmpty {
            let embeddableData: Data
            if ArtworkImageFormat.detect(in: artworkData) == .webp,
               let image = UIImage(data: artworkData),
               let jpeg = image.jpegData(compressionQuality: 0.9) {
                embeddableData = jpeg
            } else {
                embeddableData = artworkData
            }
            let artItem = AVMutableMetadataItem()
            artItem.keySpace = .common
            artItem.key = AVMetadataKey.commonKeyArtwork as NSString
            artItem.value = embeddableData as NSData
            items.append(artItem)
        }

        let existing = (try? await asset.load(.metadata)) ?? []
        let replacedIDs: Set<AVMetadataIdentifier> = [
            .commonIdentifierTitle, .commonIdentifierArtist, .commonIdentifierAlbumName,
            .iTunesMetadataSongName, .iTunesMetadataArtist, .iTunesMetadataAlbum,
            .iTunesMetadataUserGenre, .iTunesMetadataGenreID, .commonIdentifierType
        ]
        var merged = existing.compactMap { item -> AVMetadataItem? in
            if let id = item.identifier, replacedIDs.contains(id) { return nil }
            if payload.artworkData != nil, item.commonKey == .commonKeyArtwork || item.identifier == .iTunesMetadataCoverArt {
                return nil
            }
            return item
        }
        merged.append(contentsOf: items)

        session.outputURL = tempURL
        session.outputFileType = .m4a
        session.metadata = merged
        await session.export()
        guard session.status == .completed,
              (try? AVAudioFile(forReading: tempURL)) != nil else {
            return false
        }
        do {
            _ = try FileManager.default.replaceItemAt(url, withItemAt: tempURL)
            return true
        } catch {
            return false
        }
    }

    // MARK: - MP3 (ID3v2.3)

    private static func writeMP3(_ payload: Payload, to url: URL) -> Bool {
        guard let data = try? Data(contentsOf: url), data.count > 10 else { return false }
        var audioOffset = 0
        var preservedFrames = Data()
        if data.prefix(3) == Data([0x49, 0x44, 0x33]) {
            let major = data[3]
            let tagSize = data[6...9].reduce(0) { $0 << 7 | Int($1 & 0x7F) }
            audioOffset = min(10 + tagSize, data.count)
            if major == 3 {
                preservedFrames = extractPreservedID3v23Frames(
                    data[10..<audioOffset],
                    droppingArtwork: payload.artworkData != nil
                )
            }
        }

        var frames = Data()
        frames.append(preservedFrames)
        frames.append(makeID3TextFrame(id: "TIT2", text: payload.title))
        frames.append(makeID3TextFrame(id: "TPE1", text: payload.artist))
        frames.append(makeID3TextFrame(id: "TALB", text: payload.album))
        if !payload.genre.isEmpty {
            frames.append(makeID3TextFrame(id: "TCON", text: payload.genre))
        }
        if let artworkData = payload.artworkData, !artworkData.isEmpty {
            frames.append(makeID3PictureFrame(artworkData))
        }

        var out = Data()
        out.append(contentsOf: [0x49, 0x44, 0x33, 0x03, 0x00, 0x00]) // ID3v2.3.0
        out.append(contentsOf: encodeSyncSafe(frames.count))
        out.append(frames)
        out.append(data[audioOffset...])

        return atomicReplace(url: url, with: out) { tempURL in
            (try? AVAudioFile(forReading: tempURL)) != nil
        }
    }

    private static func extractPreservedID3v23Frames(_ tagBody: Data, droppingArtwork: Bool) -> Data {
        let bytes = [UInt8](tagBody)
        var cursor = 0
        var result = Data()
        var excluded: Set<String> = ["TIT2", "TPE1", "TALB", "TCON"]
        if droppingArtwork { excluded.insert("APIC") }

        while cursor + 10 <= bytes.count {
            let frameIDBytes = bytes[cursor..<(cursor + 4)]
            if frameIDBytes.allSatisfy({ $0 == 0 }) { break }
            let frameID = String(bytes: frameIDBytes, encoding: .ascii) ?? ""
            let size = Int(bytes[cursor + 4]) << 24 | Int(bytes[cursor + 5]) << 16
                | Int(bytes[cursor + 6]) << 8 | Int(bytes[cursor + 7])
            guard size > 0, cursor + 10 + size <= bytes.count else { break }
            if !excluded.contains(frameID) {
                result.append(contentsOf: bytes[cursor..<(cursor + 10 + size)])
            }
            cursor += 10 + size
        }
        return result
    }

    private static func makeID3TextFrame(id: String, text: String) -> Data {
        var payload = Data([0x01]) // UTF-16 with BOM
        if let utf16 = text.data(using: .utf16) {
            payload.append(utf16)
        }
        return makeID3Frame(id: id, payload: payload)
    }

    private static func makeID3PictureFrame(_ imageData: Data) -> Data {
        let mime = (ArtworkImageFormat.detect(in: imageData) ?? .jpeg).mimeType
        var payload = Data([0x00]) // ISO-8859-1 description
        payload.append(Data(mime.utf8))
        payload.append(0x00)
        payload.append(0x03) // Front cover
        payload.append(0x00) // Empty description
        payload.append(imageData)
        return makeID3Frame(id: "APIC", payload: payload)
    }

    private static func makeID3Frame(id: String, payload: Data) -> Data {
        var frame = Data(id.utf8.prefix(4))
        var beSize = UInt32(payload.count).bigEndian
        withUnsafeBytes(of: &beSize) { frame.append(contentsOf: $0) }
        frame.append(contentsOf: [0x00, 0x00]) // flags
        frame.append(payload)
        return frame
    }

    private static func encodeSyncSafe(_ value: Int) -> [UInt8] {
        [
            UInt8((value >> 21) & 0x7F),
            UInt8((value >> 14) & 0x7F),
            UInt8((value >> 7) & 0x7F),
            UInt8(value & 0x7F),
        ]
    }

    private static func atomicReplace(url: URL, with data: Data, validate: (URL) -> Bool) -> Bool {
        let tempURL = url.deletingLastPathComponent()
            .appendingPathComponent(".\(UUID().uuidString).tmp.\(url.pathExtension)")
        defer { try? FileManager.default.removeItem(at: tempURL) }
        do {
            try data.write(to: tempURL, options: .atomic)
            guard validate(tempURL) else { return false }
            _ = try FileManager.default.replaceItemAt(url, withItemAt: tempURL)
            return true
        } catch {
            return false
        }
    }
}
