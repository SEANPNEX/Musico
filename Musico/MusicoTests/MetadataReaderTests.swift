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

    func testReadsSiblingArtworkForWebMAndMissingEmbeddedArt() async throws {
        let png = Self.makeTinyPNGData()
        for ext in ["jpg", "png", "webp"] {
            let dir = makeTempDir()
            let webmURL = dir.appendingPathComponent("Track One.webm")
            try Data("webm-placeholder".utf8).write(to: webmURL)
            let sidecarURL = dir.appendingPathComponent("Track One.\(ext)")
            try png.write(to: sidecarURL)

            let meta = await MetadataReader().read(webmURL)
            XCTAssertEqual(meta.artwork, png, "Expected sibling .\(ext) artwork to load for .webm")
        }
    }

    func testEmbeddedArtworkTakesPrecedenceOverSiblingSidecar() async throws {
        let dir = makeTempDir()
        let mp3Fixture = try fixture("art-v23.mp3")
        let mp3URL = dir.appendingPathComponent("embedded.mp3")
        try FileManager.default.copyItem(at: mp3Fixture, to: mp3URL)
        let fakeSibling = Data("not-used-sibling".utf8)
        try fakeSibling.write(to: dir.appendingPathComponent("embedded.png"))

        let meta = await MetadataReader().read(mp3URL)
        XCTAssertNotNil(meta.artwork)
        XCTAssertNotEqual(meta.artwork, fakeSibling)
    }

    private static func makeTinyPNGData() -> Data {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8))
        return renderer.pngData { ctx in
            UIColor.systemTeal.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }
    }
}
