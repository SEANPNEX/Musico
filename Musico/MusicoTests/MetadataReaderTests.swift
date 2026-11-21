import XCTest
@testable import Musico

final class MetadataReaderTests: XCTestCase {
    private func fixture(_ name: String) throws -> URL {
        let parts = name.split(separator: ".").map(String.init)
        return try XCTUnwrap(Bundle(for: MetadataReaderTests.self).url(forResource: parts[0], withExtension: parts[1]))
    }

    private func assertTagsAndArtwork(_ name: String, file: StaticString = #filePath, line: UInt = #line) async throws {
        let meta = await MetadataReader().read(try fixture(name))
        XCTAssertEqual(meta.title, "T", name, file: file, line: line)
        XCTAssertEqual(meta.artist, "A", name, file: file, line: line)
        XCTAssertEqual(meta.album, "Al", name, file: file, line: line)
        XCTAssertEqual(meta.trackNumber, 3, name, file: file, line: line)
        XCTAssertEqual(meta.duration, 1, accuracy: 0.2, name, file: file, line: line)
        let art = try XCTUnwrap(meta.artwork, name, file: file, line: line)
        XCTAssertNotNil(UIImage(data: art), name, file: file, line: line)
    }

    func testReadsTagsAndArtworkFromID3v23MP3() async throws { try await assertTagsAndArtwork("art-v23.mp3") }
    func testReadsTagsAndPNGArtworkFromID3v24MP3() async throws { try await assertTagsAndArtwork("art-png-v24.mp3") }
    func testReadsTagsAndArtworkFromM4A() async throws { try await assertTagsAndArtwork("art.m4a") }
    func testReadsTagsAndArtworkFromFLAC() async throws { try await assertTagsAndArtwork("art.flac") }
}
