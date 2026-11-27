import XCTest
@testable import Musico

@MainActor
final class PlayHistoryStoreTests: XCTestCase {
    private var dir: URL!
    private let root = UUID()
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private let day: TimeInterval = 86_400

    override func setUp() { dir = makeTempDir() }

    private func tracks(_ names: String...) -> [Track] { names.map { makeTrack($0 + ".mp3", root: root, title: $0) } }

    func testRecordPlayCountsAndRemembersLastTime() {
        let store = PlayHistoryStore(directory: dir)
        store.recordPlay("x", at: now - day)
        store.recordPlay("x", at: now)
        XCTAssertEqual(store.count(for: "x"), 2)
        XCTAssertEqual(store.lastPlayed(for: "x"), now)
        XCTAssertEqual(store.count(for: "y"), 0)
        XCTAssertNil(store.lastPlayed(for: "y"))
        XCTAssertEqual(store.totalPlays, 2)
    }

    /// A song that was started but not heard halfway is "recent" without being "played".
    func testStartedSongIsRecentButHasNoPlays() {
        let all = tracks("a", "b")
        let store = PlayHistoryStore(directory: dir)
        store.recordPlay(all[0].id, at: now - day)
        store.markStarted(all[1].id, at: now)
        XCTAssertEqual(store.count(for: all[1].id), 0)
        XCTAssertEqual(store.lastPlayed(for: all[1].id), now)
        XCTAssertEqual(store.recentlyPlayed(all).map(\.title), ["b", "a"])
        XCTAssertEqual(store.mostPlayed(all).map(\.title), ["a"])
        XCTAssertEqual(store.leastPlayed(all).map(\.title), ["a"])
        XCTAssertEqual(store.neverPlayed(all).map(\.title), ["b"])
        XCTAssertEqual(store.totalPlays, 1)

        store.markStarted(all[0].id, at: now + 60)
        XCTAssertEqual(store.count(for: all[0].id), 1)
        XCTAssertEqual(PlayHistoryStore(directory: dir).lastPlayed(for: all[0].id), now + 60)
    }

    func testHistoryAndListeningTimeSurviveReload() {
        let store = PlayHistoryStore(directory: dir)
        store.recordPlay("x", at: now)
        store.addListening(90)
        store.save()
        let reloaded = PlayHistoryStore(directory: dir)
        XCTAssertEqual(reloaded.count(for: "x"), 1)
        XCTAssertEqual(reloaded.totalPlays, 1)
        XCTAssertEqual(reloaded.totalSeconds, 90, accuracy: 0.001)
    }

    func testLists() {
        let all = tracks("a", "b", "c", "d")
        let (a, b, c, d) = (all[0], all[1], all[2], all[3])
        let store = PlayHistoryStore(directory: dir)
        store.recordPlay(a.id, at: now - 40 * day)
        store.recordPlay(b.id, at: now - 2 * day)
        store.recordPlay(b.id, at: now - day)
        store.recordPlay(b.id, at: now - day + 60)
        store.recordPlay(c.id, at: now - 60 * day)
        store.recordPlay(c.id, at: now - 50 * day)

        XCTAssertEqual(store.recentlyPlayed(all).map(\.title), ["b", "a", "c"])
        XCTAssertEqual(store.mostPlayed(all).map(\.title), ["b", "c", "a"])
        XCTAssertEqual(store.leastPlayed(all).map(\.title), ["a", "c", "b"])
        XCTAssertEqual(store.neverPlayed(all), [d])
        XCTAssertEqual(store.notPlayedLately(all, now: now).map(\.title), ["c", "a"])
    }

    func testEqualCountsOrderByMostRecentPlay() {
        let all = tracks("a", "b")
        let store = PlayHistoryStore(directory: dir)
        store.recordPlay(all[0].id, at: now - day)
        store.recordPlay(all[1].id, at: now)
        XCTAssertEqual(store.mostPlayed(all).map(\.title), ["b", "a"])
        XCTAssertEqual(store.leastPlayed(all).map(\.title), ["a", "b"])
    }

    func testRemovedSongsLoseHistoryButTotalsStay() {
        let store = PlayHistoryStore(directory: dir)
        store.recordPlay("x", at: now)
        store.recordPlay("y", at: now)
        store.removeTracks(["x"])
        XCTAssertEqual(store.count(for: "x"), 0)
        XCTAssertEqual(store.count(for: "y"), 1)
        XCTAssertEqual(store.totalPlays, 2)
        XCTAssertEqual(PlayHistoryStore(directory: dir).count(for: "x"), 0)
    }

    func testTopArtistsAggregationSortingAndLimit() {
        var t1 = makeTrack("1.mp3", root: root, title: "S1", artist: "Artist A", album: "Alb1")
        t1.artworkKey = "artA1"
        var t2 = makeTrack("2.mp3", root: root, title: "S2", artist: "Artist A", album: "Alb2")
        t2.artworkKey = "artA2"
        var t3 = makeTrack("3.mp3", root: root, title: "S3", artist: "Artist B", album: "Alb3")
        t3.artworkKey = "artB"
        let t4 = makeTrack("4.mp3", root: root, title: "S4", artist: "Artist C", album: "Alb4")
        let t5 = makeTrack("5.mp3", root: root, title: "S5", artist: "Never Played", album: "Alb5")
        let ephemeral = Track.makeEphemeral(
            extensionID: "demo",
            info: CloudTrackInfo(remoteTrackID: "r1", relativePath: "r1.mp3", title: "Cloud", artist: "Cloud Artist", album: "Cloud Alb", trackNumber: nil, duration: 10, artworkURL: nil)
        )
        let all = [t1, t2, t3, t4, t5, ephemeral]

        let store = PlayHistoryStore(directory: dir)
        store.recordPlay(t1.id, at: now - 3 * day)
        store.recordPlay(t1.id, at: now - 2 * day)
        store.recordPlay(t2.id, at: now - day)
        store.recordPlay(t3.id, at: now)
        store.recordPlay(t3.id, at: now)
        store.recordPlay(t3.id, at: now)
        store.recordPlay(t3.id, at: now)
        store.recordPlay(t4.id, at: now - 4 * day)
        store.markStarted(t5.id, at: now)

        let topAll = store.topArtists(from: all, limit: 5)
        XCTAssertEqual(topAll, [
            PlayHistoryStore.ArtistStat(artist: "Artist B", playCount: 4, artworkKey: "artB"),
            PlayHistoryStore.ArtistStat(artist: "Artist A", playCount: 3, artworkKey: "artA1"),
            PlayHistoryStore.ArtistStat(artist: "Artist C", playCount: 1, artworkKey: nil)
        ])

        let topTwo: [PlayHistoryStore.ArtistStat] = store.topArtists(from: all, limit: 2)
        XCTAssertEqual(topTwo.map(\.artist), ["Artist B", "Artist A"])
    }

    func testTopAlbumsAggregationSortingAndLimit() {
        var t1 = makeTrack("1.mp3", root: root, title: "S1", artist: "Artist A", album: "Debut")
        t1.artworkKey = "cover1"
        var t2 = makeTrack("2.mp3", root: root, title: "S2", artist: "Artist A", album: "Debut")
        t2.artworkKey = "cover1"
        var t3 = makeTrack("3.mp3", root: root, title: "S3", artist: "Artist B", album: "Encore")
        t3.artworkKey = "cover2"
        let t4 = makeTrack("4.mp3", root: root, title: "S4", artist: "Artist C", album: "Unplayed")
        let all = [t1, t2, t3, t4]

        let store = PlayHistoryStore(directory: dir)
        store.recordPlay(t1.id, at: now - 2 * day)
        store.recordPlay(t2.id, at: now - day)
        store.recordPlay(t2.id, at: now)
        store.recordPlay(t3.id, at: now - day)
        store.recordPlay(t3.id, at: now)

        let top = store.topAlbums(from: all, limit: 5)
        XCTAssertEqual(top, [
            PlayHistoryStore.AlbumStat(album: "Debut", artist: "Artist A", playCount: 3, artworkKey: "cover1"),
            PlayHistoryStore.AlbumStat(album: "Encore", artist: "Artist B", playCount: 2, artworkKey: "cover2")
        ])
        XCTAssertEqual(store.topAlbums(from: all, limit: 1).count, 1)
    }

    func testPlayedLibraryFraction() {
        let store = PlayHistoryStore(directory: dir)
        XCTAssertEqual(store.playedLibraryFraction(from: []), 0, accuracy: 0.0001)

        let all = tracks("a", "b", "c", "d")
        let ephemeral = Track.makeEphemeral(
            extensionID: "demo",
            info: CloudTrackInfo(remoteTrackID: "r1", relativePath: "r1.mp3", title: "Cloud", artist: "A", album: "B", trackNumber: nil, duration: 10, artworkURL: nil)
        )
        store.recordPlay(all[0].id, at: now)
        store.markStarted(all[1].id, at: now) // 0 counted plays

        XCTAssertEqual(store.playedLibraryFraction(from: all + [ephemeral]), 0.25, accuracy: 0.0001)

        store.recordPlay(all[2].id, at: now)
        XCTAssertEqual(store.playedLibraryFraction(from: all + [ephemeral]), 0.5, accuracy: 0.0001)
    }

    func testRemapTracksSumsCountsKeepsNewerDateIgnoresEphemeralAndPreservesTotals() {
        let store = PlayHistoryStore(directory: dir)
        let older = now - 2 * day
        let newer = now - day
        let newest = now

        store.recordPlay("cloud1", at: older)
        store.recordPlay("cloud1", at: newest)
        store.recordPlay("local1", at: newer)
        store.recordPlay("cloud2", at: older)
        store.recordPlay("cloud3", at: newer)
        store.addListening(123.5)
        store.save()

        let expectedTotalPlays = store.totalPlays
        let expectedTotalSeconds = store.totalSeconds

        let ephemeralID = Track.makeEphemeralID(extensionID: "demo", remoteTrackID: "r1")
        store.remapTracks([
            "cloud1": "local1",
            "cloud2": "local2",
            "cloud3": ephemeralID,
            "missing": "localX"
        ])

        XCTAssertEqual(store.count(for: "cloud1"), 0)
        XCTAssertEqual(store.count(for: "local1"), 3)
        XCTAssertEqual(store.lastPlayed(for: "local1"), newest)
        XCTAssertEqual(store.count(for: "cloud2"), 0)
        XCTAssertEqual(store.count(for: "local2"), 1)
        XCTAssertEqual(store.lastPlayed(for: "local2"), older)
        XCTAssertEqual(store.count(for: "cloud3"), 0)
        XCTAssertEqual(store.count(for: ephemeralID), 0)
        XCTAssertEqual(store.totalPlays, expectedTotalPlays)
        XCTAssertEqual(store.totalSeconds, expectedTotalSeconds, accuracy: 0.001)

        let reloaded = PlayHistoryStore(directory: dir)
        XCTAssertEqual(reloaded.count(for: "local1"), 3)
        XCTAssertEqual(reloaded.lastPlayed(for: "local1"), newest)
        XCTAssertEqual(reloaded.count(for: "local2"), 1)
        XCTAssertEqual(reloaded.totalPlays, expectedTotalPlays)
    }
}
