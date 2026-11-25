import XCTest
@testable import Musico

@MainActor
final class QueueStoreTests: XCTestCase {
    private var dir: URL!
    override func setUp() { dir = makeTempDir() }

    func testStartsWithOneQueue() {
        let store = QueueStore(directory: dir)
        XCTAssertEqual(store.queues.map(\.name), ["Queue 1"])
        XCTAssertEqual(store.active.id, store.activeID)
    }

    func testAddQueueUsesNextFreeDefaultName() {
        let store = QueueStore(directory: dir)
        store.addQueue(name: nil)
        store.addQueue(name: "Gym")
        store.addQueue(name: "  ")
        XCTAssertEqual(store.queues.map(\.name), ["Queue 1", "Queue 2", "Gym", "Queue 3"])
    }

    func testCannotDeleteLastQueue() {
        let store = QueueStore(directory: dir)
        store.delete(store.activeID)
        XCTAssertEqual(store.queues.count, 1)
    }

    func testDeletingActiveActivatesNeighbour() {
        let store = QueueStore(directory: dir)
        let first = store.activeID
        let second = store.addQueue(name: nil)
        store.setActive(second)
        store.delete(second)
        XCTAssertEqual(store.activeID, first)
        XCTAssertEqual(store.queues.count, 1)
    }

    func testEachQueueKeepsOwnIndexAndPosition() {
        let store = QueueStore(directory: dir)
        let first = store.activeID
        store.updateActive { $0.replace(with: ["a", "b"], startAt: 1); $0.position = 30 }
        let second = store.addQueue(name: nil)
        store.setActive(second)
        store.updateActive { $0.replace(with: ["x"], startAt: 0); $0.position = 5 }
        store.setActive(first)
        XCTAssertEqual(store.active.currentTrackID, "b")
        XCTAssertEqual(store.active.position, 30)
        store.setActive(UUID())
        XCTAssertEqual(store.activeID, first)
    }

    func testPersistsAcrossInstances() {
        let store = QueueStore(directory: dir)
        let second = store.addQueue(name: "Two")
        store.setActive(second)
        store.updateActive { $0.replace(with: ["a", "b"], startAt: 1); $0.position = 12 }
        let reloaded = QueueStore(directory: dir)
        XCTAssertEqual(reloaded.queues, store.queues)
        XCTAssertEqual(reloaded.activeID, second)
    }

    func testRemoveTracksAppliesToAllQueues() {
        let store = QueueStore(directory: dir)
        store.updateActive { $0.replace(with: ["a", "b"], startAt: 1) }
        let second = store.addQueue(name: nil)
        store.update(second) { $0.replace(with: ["b", "c"], startAt: 0) }
        store.removeTracks(["b"])
        XCTAssertEqual(store.queues.map(\.trackIDs), [["a"], ["c"]])
        XCTAssertEqual(store.queues.map(\.currentTrackID), ["a", "c"])
    }

    func testPositionTicksStayInMemoryUntilSaved() {
        let store = QueueStore(directory: dir)
        store.updateActive { $0.replace(with: ["a", "b"], startAt: 0) }
        store.setActivePosition(42)
        XCTAssertEqual(store.active.position, 42)
        XCTAssertEqual(QueueStore(directory: dir).active.position, 0)
        store.save()
        XCTAssertEqual(QueueStore(directory: dir).active.position, 42)
    }

    func testViewedQueueInitializesToPlayingQueueAndSelectsIndependently() {
        let store = QueueStore(directory: dir)
        XCTAssertEqual(store.viewedQueueID, store.playingQueueID)
        XCTAssertEqual(store.playingQueueID, store.activeID)

        let second = store.addQueue(name: "Second")
        XCTAssertEqual(store.viewedQueueID, store.playingQueueID)

        store.selectViewedQueue(second)
        XCTAssertEqual(store.viewedQueueID, second)
        XCTAssertNotEqual(store.playingQueueID, second)

        // Deleting viewed queue falls back cleanly without changing playingQueueID
        store.delete(second)
        XCTAssertEqual(store.viewedQueueID, store.playingQueueID)
    }

    func testAppNavigationDefaultsToQueues() {
        let nav = AppNavigation()
        XCTAssertEqual(nav.selectedTab, .queues)
        XCTAssertEqual(nav.sidebarSelection, .queues)
    }
}
