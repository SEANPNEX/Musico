import XCTest
@testable import Musico

func makeTempDir() -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("musico-\(UUID().uuidString)")
    try! FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url.resolvingSymlinksInPath()
}

func makeTrack(_ path: String, root: UUID = UUID(), title: String? = nil, artist: String = "A", album: String = "B",
               track: Int? = nil, disc: Int? = nil) -> Track {
    Track(id: Track.makeID(rootID: root, relativePath: path), rootID: root, relativePath: path,
          title: title ?? path, artist: artist, album: album, albumArtist: artist,
          trackNumber: track, discNumber: disc, duration: 10, fileSize: 1, modified: Date(timeIntervalSince1970: 0),
          hasEmbeddedLyrics: false, dateAdded: Date(timeIntervalSince1970: 0))
}

final class PersistenceTests: XCTestCase {
    func testRoundTrip() throws {
        let url = makeTempDir().appendingPathComponent("nested/x.json")
        let tracks = [makeTrack("a.mp3"), makeTrack("日本語/b.flac")]
        try Persistence.save(tracks, to: url)
        XCTAssertEqual(Persistence.load([Track].self, from: url), tracks)
    }

    func testMissingFileReturnsNil() {
        let url = makeTempDir().appendingPathComponent("nope.json")
        XCTAssertNil(Persistence.load([Track].self, from: url))
    }

    func testCorruptFileIsRenamed() throws {
        let dir = makeTempDir()
        let url = dir.appendingPathComponent("x.json")
        try Data("{".utf8).write(to: url)
        XCTAssertNil(Persistence.load([Track].self, from: url))
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: dir.appendingPathComponent("x.json.corrupt").path))
    }

    func testTrackIDIsStable() {
        let root = UUID()
        let a = Track.makeID(rootID: root, relativePath: "a/b.mp3")
        XCTAssertEqual(a, Track.makeID(rootID: root, relativePath: "a/b.mp3"))
        XCTAssertEqual(a.count, 64)
        XCTAssertNotEqual(a, Track.makeID(rootID: root, relativePath: "a/c.mp3"))
    }

    func testEQPresetsMatchSpec() {
        XCTAssertEqual(EQPreset.all.map(\.name),
                       ["Flat", "Bass Boost", "Treble Boost", "Rock", "Pop", "Jazz", "Classical", "Vocal"])
        XCTAssertTrue(EQPreset.all.allSatisfy { $0.gains.count == EQSettings.frequencies.count })
        XCTAssertEqual(EQSettings.frequencies.count, 10)
    }
}
