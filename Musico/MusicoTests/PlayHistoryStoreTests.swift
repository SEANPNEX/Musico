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
}
