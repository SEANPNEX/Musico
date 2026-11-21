import XCTest
@testable import Musico

final class FolderScannerTests: XCTestCase {
    private func touch(_ root: URL, _ path: String) {
        let url = root.appendingPathComponent(path)
        try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try! Data("x".utf8).write(to: url)
    }

    func testFindsNestedAudioOnly() {
        let root = makeTempDir()
        for p in ["a.mp3", "sub/b.FLAC", "sub/deep/c.m4a", "notes.txt", ".hidden.mp3", "sub/cover.jpg"] { touch(root, p) }
        let found = FolderScanner.scan(root: root)!
        XCTAssertEqual(found.map(\.relativePath), ["a.mp3", "sub/b.FLAC", "sub/deep/c.m4a"])
        XCTAssertEqual(found.first?.fileSize, 1)
    }

    func testUnicodeAndSpecialNamesRoundTrip() {
        let root = makeTempDir()
        let path = "日本語/曲 [live] #1 100%.mp3"
        touch(root, path)
        let found = FolderScanner.scan(root: root)!
        XCTAssertEqual(found.map(\.relativePath), [path])
        let url = FolderScanner.url(root: root, relativePath: found[0].relativePath)
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
    }

    func testDecomposedUnicodeNameIsNormalisedAndStillResolves() {
        let root = makeTempDir()
        let decomposed = "Cafe\u{301}.mp3"
        touch(root, decomposed)
        let found = FolderScanner.scan(root: root)!
        XCTAssertEqual(found.map(\.relativePath), ["Café.mp3".precomposedStringWithCanonicalMapping])
        XCTAssertTrue(FileManager.default.fileExists(atPath: FolderScanner.url(root: root, relativePath: found[0].relativePath).path))
    }

    func testEmptyAndMissingRoot() {
        let root = makeTempDir()
        XCTAssertEqual(FolderScanner.scan(root: root), [])
    }

    func testUnreadableRootReturnsNilNotEmpty() {
        XCTAssertNil(FolderScanner.scan(root: makeTempDir().appendingPathComponent("missing")))
    }

    func testEvictedICloudStubIsReportedAsPlaceholder() {
        let root = makeTempDir()
        touch(root, "Album/.song.mp3.icloud"); touch(root, ".notes.txt.icloud"); touch(root, "real.mp3")
        let found = FolderScanner.scan(root: root)!
        XCTAssertEqual(found.map(\.relativePath), ["Album/song.mp3", "real.mp3"])
        XCTAssertEqual(found.map(\.isPlaceholder), [true, false])
    }
}
