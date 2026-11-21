import XCTest
@testable import Musico

struct SeededRNG: RandomNumberGenerator {
    var state: UInt64
    mutating func next() -> UInt64 {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return state
    }
}

final class PlayQueueTests: XCTestCase {
    private func queue(_ ids: [String], at index: Int = 0, repeatMode: RepeatMode = .off) -> PlayQueue {
        var q = PlayQueue(name: "Q")
        q.repeatMode = repeatMode
        q.replace(with: ids, startAt: index)
        return q
    }

    func testNextAdvancesAndStopsAtEnd() {
        var q = queue(["a", "b"])
        XCTAssertEqual(q.currentTrackID, "a")
        XCTAssertEqual(q.next(auto: true), "b")
        XCTAssertNil(q.next(auto: true))
        XCTAssertEqual(q.currentTrackID, "b")
    }

    func testRepeatAllWraps() {
        var q = queue(["a", "b"], at: 1, repeatMode: .all)
        XCTAssertEqual(q.next(auto: true), "a")
        XCTAssertEqual(q.previous(), "b")
    }

    func testRepeatOneAutoRepeatsButManualNextAdvances() {
        var q = queue(["a", "b"], repeatMode: .one)
        XCTAssertEqual(q.next(auto: true), "a")
        XCTAssertEqual(q.next(auto: false), "b")
    }

    func testPreviousAtStartStays() {
        var q = queue(["a", "b"])
        XCTAssertEqual(q.previous(), "a")
        XCTAssertEqual(q.currentIndex, 0)
    }

    func testReplaceClampsStartIndexAndResetsPosition() {
        var q = queue(["a"])
        q.position = 42
        q.replace(with: ["x", "y"], startAt: 9)
        XCTAssertEqual(q.currentTrackID, "y")
        XCTAssertEqual(q.position, 0)
    }

    func testShuffleOrderIsPermutationStartingAtCurrent() {
        var q = queue(["a", "b", "c", "d", "e"], at: 2)
        var rng = SeededRNG(state: 7)
        q.setShuffle(true, using: &rng)
        XCTAssertEqual(q.shuffleOrder.first, 2)
        XCTAssertEqual(q.shuffleOrder.sorted(), [0, 1, 2, 3, 4])
        XCTAssertEqual(q.currentTrackID, "c")
    }

    func testShuffleVisitsEveryTrackOnce() {
        var q = queue(["a", "b", "c", "d", "e"], at: 1)
        var rng = SeededRNG(state: 3)
        q.setShuffle(true, using: &rng)
        var seen = [q.currentTrackID!]
        while let id = q.next(auto: true) { seen.append(id) }
        XCTAssertEqual(seen.count, 5)
        XCTAssertEqual(Set(seen), ["a", "b", "c", "d", "e"])
    }

    func testTurningShuffleOffKeepsCurrentTrackAndRestoresListOrder() {
        var q = queue(["a", "b", "c"], at: 1)
        var rng = SeededRNG(state: 1)
        q.setShuffle(true, using: &rng)
        q.setShuffle(false, using: &rng)
        XCTAssertEqual(q.currentTrackID, "b")
        XCTAssertEqual(q.next(auto: true), "c")
    }

    func testInsertNextPlaysNext() {
        var q = queue(["a", "b"])
        q.insertNext(["x", "y"])
        XCTAssertEqual(q.trackIDs, ["a", "x", "y", "b"])
        XCTAssertEqual(q.next(auto: true), "x")
        XCTAssertEqual(q.next(auto: true), "y")
    }

    func testInsertNextPlaysNextWhenShuffled() {
        var q = queue(["a", "b", "c", "d"], at: 3)
        var rng = SeededRNG(state: 5)
        q.setShuffle(true, using: &rng)
        q.insertNext(["x"])
        XCTAssertEqual(q.currentTrackID, "d")
        XCTAssertEqual(q.shuffleOrder.sorted(), [0, 1, 2, 3, 4])
        XCTAssertEqual(q.next(auto: true), "x")
    }

    func testInsertIntoEmptyQueueMakesFirstCurrent() {
        var q = PlayQueue(name: "Q")
        q.insertNext(["x", "y"])
        XCTAssertEqual(q.currentTrackID, "x")
        var r = PlayQueue(name: "R")
        r.append(["z"])
        XCTAssertEqual(r.currentTrackID, "z")
    }

    func testAppendWhenShuffledPlaysLast() {
        var q = queue(["a", "b", "c"])
        var rng = SeededRNG(state: 9)
        q.setShuffle(true, using: &rng)
        q.append(["z"])
        var last: String?
        while let id = q.next(auto: true) { last = id }
        XCTAssertEqual(last, "z")
    }

    func testRemoveBeforeCurrentKeepsCurrentTrack() {
        var q = queue(["a", "b", "c"], at: 2)
        q.position = 30
        q.remove(at: 0)
        XCTAssertEqual(q.currentTrackID, "c")
        XCTAssertEqual(q.position, 30)
    }

    func testRemoveCurrentSelectsSuccessor() {
        var q = queue(["a", "b", "c"], at: 1)
        q.position = 30
        q.remove(at: 1)
        XCTAssertEqual(q.currentTrackID, "c")
        XCTAssertEqual(q.position, 0)
    }

    func testRemoveCurrentAtEndSelectsNewLast() {
        var q = queue(["a", "b", "c"], at: 2)
        q.remove(at: 2)
        XCTAssertEqual(q.currentTrackID, "b")
    }

    func testRemoveLastRemainingClearsCurrent() {
        var q = queue(["a"])
        q.remove(at: 0)
        XCTAssertNil(q.currentIndex)
        XCTAssertNil(q.currentTrackID)
        XCTAssertNil(q.next(auto: true))
    }

    func testRemoveWhenShuffledKeepsOrderValid() {
        var q = queue(["a", "b", "c", "d"], at: 1)
        var rng = SeededRNG(state: 11)
        q.setShuffle(true, using: &rng)
        q.remove(at: 0)
        XCTAssertEqual(q.currentTrackID, "b")
        XCTAssertEqual(q.shuffleOrder.sorted(), [0, 1, 2])
    }

    func testMoveKeepsCurrentTrack() {
        var q = queue(["a", "b", "c", "d"], at: 1)
        q.move(from: IndexSet(integer: 1), to: 4)
        XCTAssertEqual(q.trackIDs, ["a", "c", "d", "b"])
        XCTAssertEqual(q.currentTrackID, "b")
        q.move(from: IndexSet(integer: 0), to: 4)
        XCTAssertEqual(q.currentTrackID, "b")
        XCTAssertEqual(q.currentIndex, 2)
    }

    func testMoveWithDuplicateIDsKeepsCurrentPosition() {
        var q = queue(["a", "a", "b"], at: 1)
        q.move(from: IndexSet(integer: 2), to: 0)
        XCTAssertEqual(q.trackIDs, ["b", "a", "a"])
        XCTAssertEqual(q.currentIndex, 2)
    }

    func testRemoveTracksNotInValidSet() {
        var q = queue(["a", "b", "c", "b"], at: 2)
        q.removeTracks(notIn: ["a", "c"])
        XCTAssertEqual(q.trackIDs, ["a", "c"])
        XCTAssertEqual(q.currentTrackID, "c")
    }

    func testUpcomingIndices() {
        var q = queue(["a", "b", "c"], at: 1)
        XCTAssertEqual(q.upcomingIndices, [2])
        q.jump(to: 0)
        XCTAssertEqual(q.upcomingIndices, [1, 2])
    }

    func testEmptyQueueOperationsAreSafe() {
        var q = PlayQueue(name: "Q")
        var rng = SeededRNG(state: 1)
        XCTAssertNil(q.next(auto: false))
        XCTAssertNil(q.previous())
        q.setShuffle(true, using: &rng)
        q.replace(with: [], startAt: 0)
        q.removeTracks(notIn: [])
        q.jump(to: 5)
        XCTAssertNil(q.currentTrackID)
        XCTAssertEqual(q.upcomingIndices, [])
    }

    func testCodableRoundTrip() throws {
        var q = queue(["a", "b", "c"], at: 1, repeatMode: .all)
        var rng = SeededRNG(state: 2)
        q.setShuffle(true, using: &rng)
        q.position = 12.5
        let decoded = try JSONDecoder().decode(PlayQueue.self, from: JSONEncoder().encode(q))
        XCTAssertEqual(decoded, q)
    }
}
