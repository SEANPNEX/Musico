import XCTest
@testable import Musico

final class LyricsStoreTests: XCTestCase {
    private var music: URL!
    private var store: LyricsStore!
    private var song: URL { music.appendingPathComponent("song.mp3") }

    override func setUp() {
        music = makeTempDir()
        store = LyricsStore(directory: makeTempDir().appendingPathComponent("Lyrics"))
        try! Data().write(to: song)
    }

    private func write(_ text: String, _ name: String) -> URL {
        let url = music.appendingPathComponent(name)
        try! Data(text.utf8).write(to: url)
        return url
    }

    func testNothingAvailableReturnsNil() {
        XCTAssertNil(store.lyrics(trackID: "t", fileURL: song, embedded: nil))
        XCTAssertNil(store.lyrics(trackID: "t", fileURL: nil, embedded: "  \n"))
    }

    func testEmbeddedUsedWhenNothingElse() {
        let result = store.lyrics(trackID: "t", fileURL: song, embedded: "embedded words")
        XCTAssertEqual(result?.1, .embedded)
        XCTAssertEqual(result?.0.lines.map(\.text), ["embedded words"])
    }

    func testSidecarBeatsEmbeddedAndLrcBeatsTxt() {
        _ = write("txt words", "song.txt")
        XCTAssertEqual(store.lyrics(trackID: "t", fileURL: song, embedded: "e")?.0.lines.map(\.text), ["txt words"])
        _ = write("[00:01.00]lrc words", "song.lrc")
        let result = store.lyrics(trackID: "t", fileURL: song, embedded: "e")
        XCTAssertEqual(result?.1, .sidecar)
        XCTAssertEqual(result?.0.lines.map(\.text), ["lrc words"])
    }

    func testImportedBeatsSidecarAndRemoveFallsBack() throws {
        _ = write("[00:01.00]sidecar", "song.lrc")
        let picked = write("[00:02.00]imported", "elsewhere.lrc")
        XCTAssertFalse(store.hasImported(trackID: "t"))
        try store.importLyrics(from: picked, trackID: "t")
        XCTAssertTrue(store.hasImported(trackID: "t"))
        let result = store.lyrics(trackID: "t", fileURL: song, embedded: nil)
        XCTAssertEqual(result?.1, .imported)
        XCTAssertEqual(result?.0.lines.map(\.text), ["imported"])
        XCTAssertNil(store.lyrics(trackID: "other", fileURL: nil, embedded: nil))
        store.removeImported(trackID: "t")
        XCTAssertEqual(store.lyrics(trackID: "t", fileURL: song, embedded: nil)?.1, .sidecar)
    }

    func testImportRejectsUndecodableFile() {
        let url = music.appendingPathComponent("bad.lrc")
        try! Data([0xFF, 0xFE, 0xFD]).write(to: url)
        XCTAssertThrowsError(try store.importLyrics(from: url, trackID: "t"))
        XCTAssertFalse(store.hasImported(trackID: "t"))
    }
}
