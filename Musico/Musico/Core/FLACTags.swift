import Foundation

/// Tags and cover art read straight from a FLAC file's metadata blocks.
/// AVFoundation plays FLAC but returns none of its Vorbis comments or pictures.
struct FLACTags {
    /// Vorbis comments, keyed by upper-cased field name. The first value of a repeated field wins.
    var comments: [String: String] = [:]
    var picture: Data?

    /// Returns nil when the file is not FLAC or cannot be read.
    static func read(_ url: URL) -> FLACTags? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        func bytes(_ count: Int) -> [UInt8]? {
            guard count >= 0, let data = try? handle.read(upToCount: count), data.count == count else { return nil }
            return [UInt8](data)
        }
        guard var magic = bytes(4) else { return nil }
        if magic[0...2] == [0x49, 0x44, 0x33] {  // An ID3v2 tag placed before the FLAC stream.
            guard let rest = bytes(6) else { return nil }
            let size = rest[2...5].reduce(0) { $0 << 7 | Int($1 & 0x7F) }
            guard (try? handle.seek(toOffset: UInt64(10 + size))) != nil, let next = bytes(4) else { return nil }
            magic = next
        }
        guard magic == [0x66, 0x4C, 0x61, 0x43] else { return nil }  // "fLaC"

        var tags = FLACTags()
        var frontCover = false
        while let header = bytes(4) {
            let isLast = header[0] & 0x80 != 0
            let type = header[0] & 0x7F
            let length = Int(header[1]) << 16 | Int(header[2]) << 8 | Int(header[3])
            if type == 4 || type == 6 {
                guard let body = bytes(length) else { break }
                if type == 4 {
                    tags.parseComments(body)
                } else if let (kind, data) = parsePicture(body), !frontCover {
                    if tags.picture == nil || kind == 3 { tags.picture = data }
                    frontCover = kind == 3
                }
            } else {
                guard let offset = try? handle.offset(), (try? handle.seek(toOffset: offset + UInt64(length))) != nil else { break }
            }
            if isLast { break }
        }
        return tags
    }

    private mutating func parseComments(_ body: [UInt8]) {
        var cursor = 0
        func little() -> Int? {
            guard cursor + 4 <= body.count else { return nil }
            defer { cursor += 4 }
            return Int(body[cursor]) | Int(body[cursor + 1]) << 8 | Int(body[cursor + 2]) << 16 | Int(body[cursor + 3]) << 24
        }
        guard let vendor = little(), cursor + vendor <= body.count else { return }
        cursor += vendor
        guard let count = little() else { return }
        for _ in 0..<max(count, 0) {
            guard let length = little(), length >= 0, cursor + length <= body.count else { return }
            let text = String(decoding: body[cursor..<cursor + length], as: UTF8.self)
            cursor += length
            guard let equals = text.firstIndex(of: "=") else { continue }
            let key = text[..<equals].uppercased()
            let value = String(text[text.index(after: equals)...])
            if comments[key] == nil, !value.isEmpty { comments[key] = value }
        }
    }

    /// Returns the picture type (3 is the front cover) and the image bytes.
    private static func parsePicture(_ body: [UInt8]) -> (Int, Data)? {
        var cursor = 0
        func big() -> Int? {
            guard cursor + 4 <= body.count else { return nil }
            defer { cursor += 4 }
            return Int(body[cursor]) << 24 | Int(body[cursor + 1]) << 16 | Int(body[cursor + 2]) << 8 | Int(body[cursor + 3])
        }
        guard let kind = big(), let mime = big(), cursor + mime <= body.count else { return nil }
        cursor += mime
        guard let description = big(), cursor + description <= body.count else { return nil }
        cursor += description + 16  // width, height, colour depth, colour count
        guard let length = big(), length > 0, cursor + length <= body.count else { return nil }
        return (kind, Data(body[cursor..<cursor + length]))
    }
}
