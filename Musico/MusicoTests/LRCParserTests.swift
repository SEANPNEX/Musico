import XCTest
@testable import Musico

final class LRCParserTests: XCTestCase {
    func testParsesTimestampFormats() {
        let lyrics = LRCParser.parse("[00:12.50]Hello\n[01:02]World\n[01:02.345]Milli")
        XCTAssertTrue(lyrics.isTimed)
        XCTAssertEqual(lyrics.lines.map(\.text), ["Hello", "World", "Milli"])
        XCTAssertEqual(lyrics.lines[0].time!, 12.5, accuracy: 0.001)
        XCTAssertEqual(lyrics.lines[1].time!, 62, accuracy: 0.001)
        XCTAssertEqual(lyrics.lines[2].time!, 62.345, accuracy: 0.001)
    }

    func testMultipleTimestampsOnOneLineAndSorting() {
        let lyrics = LRCParser.parse("[00:30.00]Verse\n[00:10.00][00:40.00]Chorus")
        XCTAssertEqual(lyrics.lines.map(\.text), ["Chorus", "Verse", "Chorus"])
        XCTAssertEqual(lyrics.lines.map { $0.time! }, [10, 30, 40])
        XCTAssertEqual(lyrics.lines.map(\.id), [0, 1, 2])
    }

    func testMetadataTagsIgnored() {
        let lyrics = LRCParser.parse("[ar:Someone]\n[ti:Song]\n[offset:500]\n[00:01.00]Line")
        XCTAssertEqual(lyrics.lines.map(\.text), ["Line"])
    }

    func testCRLFLineEndings() {
        let lyrics = LRCParser.parse("[00:01.00]One\r\n[00:02.00]Two\r\n")
        XCTAssertEqual(lyrics.lines.map(\.text), ["One", "Two"])
    }

    func testTimedLineWithEmptyTextIsKeptAsGap() {
        let lyrics = LRCParser.parse("[00:01.00]One\n[00:05.00]\n[00:09.00]Two")
        XCTAssertEqual(lyrics.lines.map(\.text), ["One", "", "Two"])
    }

    func testPlainTextIsUntimed() {
        let lyrics = LRCParser.parse("First line\n\nSecond line\n")
        XCTAssertFalse(lyrics.isTimed)
        XCTAssertEqual(lyrics.lines.map(\.text), ["First line", "Second line"])
        XCTAssertNil(lyrics.lines[0].time)
        XCTAssertNil(lyrics.lineIndex(at: 10))
    }

    func testLineIndexAtTime() {
        let lyrics = LRCParser.parse("[00:10.00]A\n[00:20.00]B")
        XCTAssertNil(lyrics.lineIndex(at: 5))
        XCTAssertEqual(lyrics.lineIndex(at: 10), 0)
        XCTAssertEqual(lyrics.lineIndex(at: 19.9), 0)
        XCTAssertEqual(lyrics.lineIndex(at: 500), 1)
    }

    func testDecodesUTF8GB18030AndUTF16() {
        let text = "[00:01.00]你好，世界"
        let gb = String.Encoding(rawValue: CFStringConvertEncodingToNSStringEncoding(CFStringEncoding(CFStringEncodings.GB_18030_2000.rawValue)))
        XCTAssertEqual(LRCParser.decode(text.data(using: .utf8)!), text)
        XCTAssertEqual(LRCParser.decode(text.data(using: gb)!), text)
        XCTAssertEqual(LRCParser.decode(text.data(using: .utf16)!), text)
    }

    func testDecodeStripsUTF8BOM() {
        let data = Data([0xEF, 0xBB, 0xBF]) + Data("[00:01.00]Hi".utf8)
        XCTAssertEqual(LRCParser.parse(LRCParser.decode(data)!).lines.map(\.text), ["Hi"])
    }
}
