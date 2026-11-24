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

    func testFolderMatchFuzzyNormalizesTrackPrefixNoiseDiacriticsAndArtistOrder() {
        let caféSong = music.appendingPathComponent("01 - Café Del Mar (Official Audio).mp3")
        try! Data().write(to: caféSong)
        _ = write("[00:05.00]Sunrise on the beach", "Artist - Cafe del Mar [FLAC].lrc")

        let found = store.lyrics(
            trackID: "cafe",
            fileURL: caféSong,
            embedded: "embedded fallback",
            trackTitle: "Café Del Mar",
            trackArtist: "Artist"
        )
        XCTAssertEqual(found?.1, .folderMatch)
        XCTAssertEqual(found?.0.lines.map(\.text), ["Sunrise on the beach"])
    }

    func testFolderMatchPreservesJapaneseDakutenAnd1989Title() {
        let jpSong = music.appendingPathComponent("かがみ.mp3")
        try! Data().write(to: jpSong)
        _ = write("[00:01.00]wrong kana", "かかみ.lrc")
        XCTAssertNil(store.lyrics(trackID: "jp", fileURL: jpSong, embedded: nil, trackTitle: "かがみ", trackArtist: ""))

        _ = write("[00:02.00]exact kana", "01. かがみ.lrc")
        let jpFound = store.lyrics(trackID: "jp", fileURL: jpSong, embedded: nil, trackTitle: "かがみ", trackArtist: "")
        XCTAssertEqual(jpFound?.1, .folderMatch)
        XCTAssertEqual(jpFound?.0.lines.map(\.text), ["exact kana"])

        let numericSong = music.appendingPathComponent("1989.mp3")
        try! Data().write(to: numericSong)
        _ = write("[00:03.00]welcome to 1989", "01 - 1989.lrc")
        let numFound = store.lyrics(trackID: "yr", fileURL: numericSong, embedded: nil, trackTitle: "1989", trackArtist: "Taylor")
        XCTAssertEqual(numFound?.1, .folderMatch)
        XCTAssertEqual(numFound?.0.lines.map(\.text), ["welcome to 1989"])
    }

    func testFolderMatchIgnoresGenericNamesAndSubfoldersAndPrefersTimedLrcOverTxt() throws {
        let trackURL = music.appendingPathComponent("Nightcall.mp3")
        try! Data().write(to: trackURL)
        let subDir = music.appendingPathComponent("Subfolder")
        try FileManager.default.createDirectory(at: subDir, withIntermediateDirectories: true)
        try Data("[00:01.00]sub".utf8).write(to: subDir.appendingPathComponent("Nightcall.lrc"))
        _ = write("[00:01.00]generic", "lyrics.lrc")
        _ = write("[00:01.00]readme", "readme.txt")

        // Only generic / subfolder files present -> falls through to embedded
        let embeddedOnly = store.lyrics(trackID: "nc", fileURL: trackURL, embedded: "tag text", trackTitle: "Nightcall", trackArtist: "Kavinsky")
        XCTAssertEqual(embeddedOnly?.1, .embedded)

        // Exact sidecar named "lyrics.lrc" still matches "lyrics.mp3"
        let genericNamedSong = music.appendingPathComponent("lyrics.mp3")
        try! Data().write(to: genericNamedSong)
        XCTAssertEqual(store.lyrics(trackID: "gen", fileURL: genericNamedSong, embedded: nil)?.1, .sidecar)

        // Tie-breaker: timed .lrc beats untimed .txt at equal score, and folderMatch beats embedded
        _ = write("plain line", "Kavinsky - Nightcall.txt")
        _ = write("[00:04.00]timed line", "Kavinsky - Nightcall.lrc")
        let folderFound = store.lyrics(trackID: "nc", fileURL: trackURL, embedded: "tag text", trackTitle: "Nightcall", trackArtist: "Kavinsky")
        XCTAssertEqual(folderFound?.1, .folderMatch)
        XCTAssertEqual(folderFound?.0.isTimed, true)
        XCTAssertEqual(folderFound?.0.lines.map(\.text), ["timed line"])
    }

    func testSaveImportedAndExportSidecar() throws {
        XCTAssertTrue(store.canExportSidecar(for: song))
        let lrc = "[00:10.00]Cloud line"
        try store.saveImportedLyrics(text: lrc, trackID: "t")
        XCTAssertTrue(store.hasImported(trackID: "t"))
        XCTAssertEqual(store.rawImportedText(trackID: "t"), lrc)

        try store.exportSidecarLyrics(text: lrc, for: song)
        let sidecarURL = music.appendingPathComponent("song.lrc")
        XCTAssertEqual(try String(contentsOf: sidecarURL, encoding: .utf8), lrc)
    }
}
