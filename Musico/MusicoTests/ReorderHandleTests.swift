import XCTest
import SwiftUI
#if !targetEnvironment(macCatalyst)
import UIKit
#endif
@testable import Musico

@MainActor
final class ReorderHandleTests: XCTestCase {
    func testParseSourceIndex() {
        XCTAssertEqual(ReorderableTrackRow.parseSourceIndex("queue-123:0", scopeID: "queue-123"), 0)
        XCTAssertEqual(ReorderableTrackRow.parseSourceIndex("playlist-abc:14", scopeID: "playlist-abc"), 14)
        XCTAssertNil(ReorderableTrackRow.parseSourceIndex("queue-999:2", scopeID: "queue-123"))
        XCTAssertNil(ReorderableTrackRow.parseSourceIndex("queue-123:notanumber", scopeID: "queue-123"))
    }

    #if !targetEnvironment(macCatalyst)
    private var savedAnimationsEnabled = true

    override func setUp() async throws {
        try await super.setUp()
        savedAnimationsEnabled = UIView.areAnimationsEnabled
        UIView.setAnimationsEnabled(false)
    }

    override func tearDown() async throws {
        UIView.setAnimationsEnabled(savedAnimationsEnabled)
        try await super.tearDown()
    }

    func testShouldBeginPanDirectionFiltering() {
        // Pure vertical drags should begin interactive reordering immediately.
        XCTAssertTrue(IOSListReorderHandleOverlay.Coordinator.shouldBeginPan(
            velocity: CGPoint(x: 0, y: -180),
            translation: CGPoint(x: 0, y: -12)
        ))
        XCTAssertTrue(IOSListReorderHandleOverlay.Coordinator.shouldBeginPan(
            velocity: CGPoint(x: 0, y: 140),
            translation: CGPoint(x: 0, y: 10)
        ))

        // Slightly diagonal thumb drag should still begin reordering.
        XCTAssertTrue(IOSListReorderHandleOverlay.Coordinator.shouldBeginPan(
            velocity: CGPoint(x: 40, y: -150),
            translation: CGPoint(x: 3, y: -10)
        ))

        // Clear horizontal swipes should yield to List swipeActions.
        XCTAssertFalse(IOSListReorderHandleOverlay.Coordinator.shouldBeginPan(
            velocity: CGPoint(x: -250, y: 20),
            translation: CGPoint(x: -12, y: 1)
        ))
        XCTAssertFalse(IOSListReorderHandleOverlay.Coordinator.shouldBeginPan(
            velocity: CGPoint(x: -18, y: 2),
            translation: CGPoint(x: -10, y: 1)
        ))
    }

    func testMultiSectionQueueListHandleReordersViaInteractiveMovement() async throws {
        let state = makeTempDir()
        let library = LibraryStore(
            directory: state,
            documentsURL: makeTempDir(),
            reader: StubReader(),
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork"))
        )
        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        let player = PlayerController(
            library: library,
            queues: queues,
            history: history,
            engine: AudioEngine(),
            settingsURL: state.appendingPathComponent("settings.json")
        )
        defer { player.shutdown() }

        let tracks = [
            makeTrack("0-alpha.mp3", title: "Alpha"),
            makeTrack("1-bravo.mp3", title: "Bravo"),
            makeTrack("2-charlie.mp3", title: "Charlie")
        ]
        let model = ReorderTestModel(tracks: tracks)

        let window = try makeHostedWindow(
            rootView: QueueStyleReorderHost(model: model),
            library: library,
            player: player
        )
        defer { tearDownWindow(window) }

        let handles = try await waitForHandles(in: window, expectedCount: 3)
        try performInteractiveMove(handleView: handles[0], targetHandleView: handles[2])

        try await waitUntil("multi-section queue order should update") {
            model.tracks.map(\.title) != ["Alpha", "Bravo", "Charlie"]
        }
        XCTAssertEqual(model.tracks.map(\.title), ["Bravo", "Charlie", "Alpha"])
    }

    func testPlaylistHeaderAndOffsetForEachHandleReorders() async throws {
        let state = makeTempDir()
        let library = LibraryStore(
            directory: state,
            documentsURL: makeTempDir(),
            reader: StubReader(),
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork"))
        )
        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        let player = PlayerController(
            library: library,
            queues: queues,
            history: history,
            engine: AudioEngine(),
            settingsURL: state.appendingPathComponent("settings.json")
        )
        defer { player.shutdown() }

        let tracks = [
            makeTrack("0-alpha.mp3", title: "Alpha"),
            makeTrack("1-bravo.mp3", title: "Bravo"),
            makeTrack("2-charlie.mp3", title: "Charlie")
        ]
        let model = ReorderTestModel(tracks: tracks)

        let window = try makeHostedWindow(
            rootView: PlaylistStyleReorderHost(model: model),
            library: library,
            player: player
        )
        defer { tearDownWindow(window) }

        let handles = try await waitForHandles(in: window, expectedCount: 3)
        try performInteractiveMove(handleView: handles[0], targetHandleView: handles[2])

        try await waitUntil("playlist order with header row should update") {
            model.tracks.map(\.title) != ["Alpha", "Bravo", "Charlie"]
        }
        XCTAssertEqual(model.tracks.map(\.title), ["Bravo", "Charlie", "Alpha"])
    }

    func testFolderStyleElementIDForEachHandleReorders() async throws {
        let state = makeTempDir()
        let library = LibraryStore(
            directory: state,
            documentsURL: makeTempDir(),
            reader: StubReader(),
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork"))
        )
        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        let player = PlayerController(
            library: library,
            queues: queues,
            history: history,
            engine: AudioEngine(),
            settingsURL: state.appendingPathComponent("settings.json")
        )
        defer { player.shutdown() }

        let tracks = [
            makeTrack("0-alpha.mp3", title: "Alpha"),
            makeTrack("1-bravo.mp3", title: "Bravo"),
            makeTrack("2-charlie.mp3", title: "Charlie")
        ]
        let model = ReorderTestModel(tracks: tracks)

        let window = try makeHostedWindow(
            rootView: FolderStyleReorderHost(model: model),
            library: library,
            player: player
        )
        defer { tearDownWindow(window) }

        let handles = try await waitForHandles(in: window, expectedCount: 3)
        try performInteractiveMove(handleView: handles[0], targetHandleView: handles[2])

        try await waitUntil("folder order with leading rows should update") {
            model.tracks.map(\.title) != ["Alpha", "Bravo", "Charlie"]
        }
        XCTAssertEqual(model.tracks.map(\.title), ["Bravo", "Charlie", "Alpha"])
    }

    func testEditModeHidesCustomReorderHandle() async throws {
        let state = makeTempDir()
        let library = LibraryStore(
            directory: state,
            documentsURL: makeTempDir(),
            reader: StubReader(),
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork"))
        )
        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        let player = PlayerController(
            library: library,
            queues: queues,
            history: history,
            engine: AudioEngine(),
            settingsURL: state.appendingPathComponent("settings.json")
        )
        defer { player.shutdown() }

        let model = ReorderTestModel(tracks: [makeTrack("0-alpha.mp3", title: "Alpha")])
        let window = try makeHostedWindow(
            rootView: EditModeReorderHost(model: model),
            library: library,
            player: player
        )
        defer { tearDownWindow(window) }

        try await Task.sleep(for: .milliseconds(250))
        window.layoutIfNeeded()
        XCTAssertTrue(collectHandles(in: window).isEmpty)
    }

    // MARK: - Helpers

    private func makeHostedWindow<Content: View>(
        rootView: Content,
        library: LibraryStore,
        player: PlayerController
    ) throws -> UIWindow {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        let hosted = rootView
            .environment(library)
            .environment(player)
        window.rootViewController = UIHostingController(rootView: hosted)
        window.makeKeyAndVisible()
        window.layoutIfNeeded()
        return window
    }

    private func tearDownWindow(_ window: UIWindow) {
        if window.rootViewController?.presentedViewController != nil {
            window.rootViewController?.dismiss(animated: false)
        }
        window.isHidden = true
        window.rootViewController = nil
    }

    private func collectHandles(in view: UIView) -> [HandleContainerView] {
        var found: [HandleContainerView] = []
        if let handle = view as? HandleContainerView {
            found.append(handle)
        }
        for subview in view.subviews {
            found.append(contentsOf: collectHandles(in: subview))
        }
        return found.sorted {
            let ay = $0.convert($0.bounds, to: view).minY
            let by = $1.convert($1.bounds, to: view).minY
            return ay < by
        }
    }

    private func waitForHandles(in window: UIWindow, expectedCount: Int) async throws -> [HandleContainerView] {
        try await waitUntil("expected \(expectedCount) reorder handles") {
            window.layoutIfNeeded()
            return self.collectHandles(in: window).count == expectedCount
        }
        window.layoutIfNeeded()
        return collectHandles(in: window)
    }

    private func performInteractiveMove(handleView: HandleContainerView, targetHandleView: HandleContainerView) throws {
        let coordinator = try XCTUnwrap(handleView.coordinator)
        let resolved = try XCTUnwrap(IOSListReorderHandleOverlay.Coordinator.enclosingCollectionView(from: handleView))
        XCTAssertNotNil(resolved.cell, "Handle should resolve its enclosing UICollectionViewCell")

        let collectionView = resolved.collectionView
        guard coordinator.beginInteractiveReorder() else {
            let dataSourceDesc = collectionView.dataSource.map { String(describing: type(of: $0)) } ?? "nil"
            let cellIndexPath = resolved.cell.flatMap { collectionView.indexPath(for: $0) }
            let canMove = cellIndexPath.map { collectionView.dataSource?.collectionView?(collectionView, canMoveItemAt: $0) } ?? nil
            let respondsMove = collectionView.dataSource?.responds(to: #selector(UICollectionViewDataSource.collectionView(_:moveItemAt:to:))) ?? false
            XCTFail("beginInteractiveMovementForItem returned false (cv=\(type(of: collectionView)), dataSource=\(dataSourceDesc), indexPath=\(String(describing: cellIndexPath)), canMove=\(String(describing: canMove)), respondsMove=\(respondsMove), dragDelegate=\(String(describing: collectionView.dragDelegate)))")
            return
        }

        let targetCenter = targetHandleView.convert(
            CGPoint(x: targetHandleView.bounds.midX, y: targetHandleView.bounds.midY),
            to: collectionView
        )
        coordinator.updateInteractiveReorder(to: CGPoint(x: collectionView.bounds.midX, y: targetCenter.y + 8))
        coordinator.endInteractiveReorder()
    }

    private func waitUntil(_ message: String, timeoutSeconds: TimeInterval = 3.0, condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if condition() {
                return
            }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertTrue(condition(), "Timed out: \(message)")
    }
    #endif
}

@MainActor @Observable
private final class ReorderTestModel {
    var tracks: [Track]

    init(tracks: [Track]) {
        self.tracks = tracks
    }

    func move(from source: IndexSet, to destination: Int) {
        tracks.move(fromOffsets: source, toOffset: destination)
    }
}

private struct QueueStyleReorderHost: View {
    let model: ReorderTestModel

    var body: some View {
        List {
            Section("Queues") {
                Text("Main Queue")
                Text("Secondary Queue")
            }
            Section("Tracks") {
                ForEach(Array(model.tracks.enumerated()), id: \.offset) { index, track in
                    ReorderableTrackRow(
                        track: track,
                        index: index,
                        scopeID: "test-queue",
                        onTap: {},
                        onMove: { from, to in model.move(from: IndexSet(integer: from), to: to) },
                        onRemove: {}
                    )
                    .contextMenu {
                        Button("Dummy Action") {}
                    }
                    #if !targetEnvironment(macCatalyst)
                    .swipeActions(edge: .leading, allowsFullSwipe: true) {
                        Button("Play Next") {}
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button("Remove", role: .destructive) {}
                    }
                    #endif
                }
                .onMove { source, destination in
                    model.move(from: source, to: destination)
                }
            }
        }
        .listStyle(.plain)
    }
}

private struct PlaylistStyleReorderHost: View {
    let model: ReorderTestModel

    var body: some View {
        List {
            Text("Hero Header")
                .listRowSeparator(.hidden)
            ForEach(Array(model.tracks.enumerated()), id: \.offset) { index, track in
                ReorderableTrackRow(
                    track: track,
                    index: index,
                    scopeID: "test-playlist",
                    trackNumber: index + 1,
                    onTap: {},
                    onMove: { from, to in model.move(from: IndexSet(integer: from), to: to) },
                    onRemove: {}
                )
                .contextMenu {
                    Button("Dummy Action") {}
                }
                #if !targetEnvironment(macCatalyst)
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button("Remove", role: .destructive) {}
                }
                #endif
            }
            .onMove { source, destination in
                model.move(from: source, to: destination)
            }
        }
        .listStyle(.plain)
    }
}

private struct FolderStyleReorderHost: View {
    let model: ReorderTestModel

    var body: some View {
        List {
            Text("Subfolder One")
            Text("Subfolder Two")
            ForEach(Array(model.tracks.enumerated()), id: \.element.id) { index, track in
                ReorderableTrackRow(
                    track: track,
                    index: index,
                    scopeID: "test-folder",
                    onTap: {},
                    onMove: { from, to in model.move(from: IndexSet(integer: from), to: to) }
                )
                .contextMenu {
                    Button("Dummy Action") {}
                }
            }
            .onMove { source, destination in
                model.move(from: source, to: destination)
            }
        }
        .listStyle(.plain)
    }
}

private struct EditModeReorderHost: View {
    let model: ReorderTestModel

    var body: some View {
        List {
            ForEach(Array(model.tracks.enumerated()), id: \.offset) { index, track in
                ReorderableTrackRow(
                    track: track,
                    index: index,
                    scopeID: "test-edit",
                    onTap: {},
                    onMove: { _, _ in },
                    onRemove: {}
                )
            }
            .onMove { _, _ in }
        }
        .listStyle(.plain)
        #if !targetEnvironment(macCatalyst)
        .environment(\.editMode, .constant(.active))
        #endif
    }
}
