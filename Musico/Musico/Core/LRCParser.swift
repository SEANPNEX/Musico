import Foundation

struct LyricLine: Hashable, Identifiable, Sendable {
    var id: Int
    var time: TimeInterval?
    var text: String
}

struct Lyrics: Hashable, Sendable {
    var lines: [LyricLine]
    var isTimed: Bool

    /// Index of the line being sung at `time`, or nil before the first line / for untimed lyrics.
    func lineIndex(at time: TimeInterval) -> Int? {
        guard isTimed else { return nil }
        var result: Int?
        for (index, line) in lines.enumerated() {
            guard let start = line.time, start <= time else { break }
            result = index
        }
        return result
    }
}

enum LRCParser {
    private static let timestamp = try! NSRegularExpression(pattern: #"^\s*\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]"#)
    private static let metadataTag = try! NSRegularExpression(pattern: #"^\s*\[[A-Za-z#][^\]]*\]\s*$"#)

    static func parse(_ text: String) -> Lyrics {
        var timed: [(TimeInterval, String)] = []
        var plain: [String] = []
        for rawLine in text.components(separatedBy: .newlines) {
            var rest = rawLine
            var times: [TimeInterval] = []
            while let match = timestamp.firstMatch(in: rest, range: NSRange(rest.startIndex..., in: rest)) {
                func group(_ i: Int) -> String? { Range(match.range(at: i), in: rest).map { String(rest[$0]) } }
                let minutes = Double(group(1) ?? "") ?? 0
                let seconds = Double(group(2) ?? "") ?? 0
                let fraction = group(3).flatMap { Double("0." + $0) } ?? 0
                times.append(minutes * 60 + seconds + fraction)
                rest = String(rest[Range(match.range, in: rest)!.upperBound...])
            }
            let content = rest.trimmingCharacters(in: .whitespaces)
            if !times.isEmpty {
                timed.append(contentsOf: times.map { ($0, content) })
            } else if metadataTag.firstMatch(in: rest, range: NSRange(rest.startIndex..., in: rest)) == nil, !content.isEmpty {
                plain.append(content)
            }
        }
        if timed.isEmpty {
            return Lyrics(lines: plain.enumerated().map { LyricLine(id: $0.offset, time: nil, text: $0.element) }, isTimed: false)
        }
        let sorted = timed.enumerated().sorted { ($0.element.0, $0.offset) < ($1.element.0, $1.offset) }
        return Lyrics(lines: sorted.enumerated().map { LyricLine(id: $0.offset, time: $0.element.element.0, text: $0.element.element.1) },
                      isTimed: true)
    }

    /// Decodes lyric file bytes: UTF-8, then UTF-16 (with BOM), then GB18030.
    static func decode(_ data: Data) -> String? {
        let gb18030 = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(
            CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
        let hasUTF16BOM = data.starts(with: [0xFF, 0xFE]) || data.starts(with: [0xFE, 0xFF])
        let decoded: String?
        if let utf8 = String(data: data, encoding: .utf8) {
            decoded = utf8
        } else if hasUTF16BOM {
            decoded = data.count % 2 == 0 ? String(data: data, encoding: .utf16) : nil
        } else {
            decoded = String(data: data, encoding: gb18030)
        }
        guard let decoded else { return nil }
        return decoded.hasPrefix("\u{FEFF}") ? String(decoded.dropFirst()) : decoded
    }
}
