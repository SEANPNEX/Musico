import XCTest
@testable import Musico

@MainActor
final class PlayerControllerTests: XCTestCase {
    private var state: URL!
    private var docs: URL!
    private var library: LibraryStore!
    private var queues: QueueStore!
    private var player: PlayerController!
    private var history: PlayHistoryStore!

    override func setUp() async throws {
        state = makeTempDir()
        docs = makeTempDir()
        let fixture = try XCTUnwrap(Bundle(for: PlayerControllerTests.self).url(forResource: "sample", withExtension: "mp3"))
        for name in ["a.mp3", "b.mp3"] { try FileManager.default.copyItem(at: fixture, to: docs.appendingPathComponent(name)) }
        for name in ["bad1.mp3", "bad2.mp3", "bad3.mp3"] { try Data("not audio".utf8).write(to: docs.appendingPathComponent(name)) }
        library = LibraryStore(directory: state, documentsURL: docs, reader: StubReader(),
                               artwork: ArtworkCache(directory: state.appendingPathComponent("Art")))
        await library.rescan()
        makePlayer()
    }

    override func tearDown() {
        player.shutdown()
    }

    private func makePlayer() {
        queues = QueueStore(directory: state)
        history = PlayHistoryStore(directory: state)
        player = PlayerController(library: library, queues: queues, history: history, engine: AudioEngine(),
                                  settingsURL: state.appendingPathComponent("settings.json"))
    }

    private func id(_ name: String) -> String { library.tracks.first { $0.relativePath == name }!.id }

    /// Lets audio really render for a while, so system callbacks (route and format changes,
    /// the progress timer, end of track) all get a chance to run.
    func testRealPlaybackRunsThroughSeveralSeconds() async throws {
        player.play([id("a.mp3"), id("b.mp3")], startAt: 0)
        try await Task.sleep(nanoseconds: 2_500_000_000)
        XCTAssertTrue(player.isPlaying)
        XCTAssertGreaterThan(player.currentTime, 1.5)
        player.seek(to: 57)
        try await Task.sleep(nanoseconds: 3_000_000_000)
        XCTAssertEqual(player.currentTrack?.relativePath, "b.mp3")
        XCTAssertTrue(player.isPlaying)
        player.pause()
        player.resume()
        try await Task.sleep(nanoseconds: 1_000_000_000)
        XCTAssertTrue(player.isPlaying)
    }

    // MARK: Play history

    func testListeningToHalfASongCountsOnePlay() {
        player.play([id("a.mp3")], startAt: 0)
        player.noteListening(20)
        XCTAssertEqual(history.count(for: id("a.mp3")), 0)
        player.noteListening(10)
        XCTAssertEqual(history.count(for: id("a.mp3")), 1)
        player.noteListening(25)
        player.trackDidEnd()
        XCTAssertEqual(history.count(for: id("a.mp3")), 1)
        XCTAssertEqual(history.totalSeconds, 55, accuracy: 0.001)
    }

    func testAFewSecondsOfListeningMakesASongRecent() {
        player.play([id("a.mp3"), id("b.mp3")], startAt: 0)
        player.noteListening(2)
        XCTAssertNil(history.lastPlayed(for: id("a.mp3")))
        player.noteListening(3)
        XCTAssertNotNil(history.lastPlayed(for: id("a.mp3")))
        XCTAssertEqual(history.count(for: id("a.mp3")), 0)
        XCTAssertNil(history.lastPlayed(for: id("b.mp3")))
    }

    func testSkippingEarlyDoesNotCount() {
        player.play([id("a.mp3"), id("b.mp3")], startAt: 0)
        player.noteListening(5)
        player.next()
        XCTAssertEqual(history.count(for: id("a.mp3")), 0)
        player.noteListening(28)
        XCTAssertEqual(history.count(for: id("b.mp3")), 0)
    }

    func testFinishingASongCountsEvenAfterSeekingAhead() {
        player.play([id("a.mp3"), id("b.mp3")], startAt: 0)
        player.noteListening(3)
        player.trackDidEnd()
        XCTAssertEqual(history.count(for: id("a.mp3")), 1)
        XCTAssertEqual(history.count(for: id("b.mp3")), 0)
    }

    func testRepeatOneCountsEachTimeThrough() {
        player.play([id("a.mp3")], startAt: 0)
        player.cycleRepeat(); player.cycleRepeat()
        player.trackDidEnd()
        player.trackDidEnd()
        XCTAssertEqual(history.count(for: id("a.mp3")), 2)
    }

    func testSongRemovedFromLibraryLosesHistory() async throws {
        player.play([id("a.mp3")], startAt: 0)
        let a = id("a.mp3")
        player.trackDidEnd()
        XCTAssertEqual(history.count(for: a), 1)
        try FileManager.default.removeItem(at: docs.appendingPathComponent("a.mp3"))
        await library.rescan()
        XCTAssertEqual(history.count(for: a), 0)
    }

    func testPlayLoadsTrackAtIndex() {
        player.play([id("a.mp3"), id("b.mp3")], startAt: 1)
        XCTAssertEqual(player.currentTrack?.relativePath, "b.mp3")
        XCTAssertEqual(player.duration, 58.6, accuracy: 1)
        XCTAssertNil(player.errorMessage)
    }

    func testUnplayableFileIsSkipped() {
        player.play([id("bad1.mp3"), id("a.mp3")], startAt: 0)
        XCTAssertEqual(player.currentTrack?.relativePath, "a.mp3")
        XCTAssertEqual(queues.active.currentIndex, 1)
    }

    func testThreeConsecutiveFailuresStopWithError() {
        player.play([id("bad1.mp3"), id("bad2.mp3"), id("bad3.mp3"), id("a.mp3")], startAt: 0)
        XCTAssertNotNil(player.errorMessage)
        XCTAssertFalse(player.isPlaying)
        XCTAssertEqual(queues.active.currentIndex, 2)
    }

    func testOnlyUnplayableFileStopsWithError() {
        player.play([id("bad1.mp3")], startAt: 0)
        XCTAssertNotNil(player.errorMessage)
        XCTAssertFalse(player.isPlaying)
    }

    func testPreviousRestartsAfterThreeSecondsOtherwiseGoesBack() {
        player.play([id("a.mp3"), id("b.mp3")], startAt: 1)
        player.togglePlayPause()
        player.seek(to: 10)
        player.previous()
        XCTAssertEqual(player.currentTrack?.relativePath, "b.mp3")
        XCTAssertEqual(player.currentTime, 0, accuracy: 0.5)
        player.previous()
        XCTAssertEqual(player.currentTrack?.relativePath, "a.mp3")
    }

    func testSwitchingQueuesKeepsEachTrackAndPosition() {
        player.play([id("a.mp3")], startAt: 0)
        player.togglePlayPause()
        player.seek(to: 20)
        let first = queues.activeID
        player.playInNewQueue([id("b.mp3")])
        player.togglePlayPause()
        player.seek(to: 5)
        let second = queues.activeID
        XCTAssertNotEqual(first, second)

        player.switchQueue(to: first)
        XCTAssertEqual(player.currentTrack?.relativePath, "a.mp3")
        XCTAssertEqual(player.currentTime, 20, accuracy: 0.5)
        player.switchQueue(to: second)
        XCTAssertEqual(player.currentTrack?.relativePath, "b.mp3")
        XCTAssertEqual(player.currentTime, 5, accuracy: 0.5)
    }

    func testTrackEndAdvancesAndStopsAtStartOfLastTrack() {
        player.play([id("a.mp3"), id("b.mp3")], startAt: 0)
        player.trackDidEnd()
        XCTAssertEqual(player.currentTrack?.relativePath, "b.mp3")
        player.trackDidEnd()
        XCTAssertEqual(player.currentTrack?.relativePath, "b.mp3")
        XCTAssertFalse(player.isPlaying)
        XCTAssertEqual(player.currentTime, 0, accuracy: 0.1)
    }

    func testRepeatOneReplaysSameTrack() {
        player.play([id("a.mp3"), id("b.mp3")], startAt: 0)
        player.cycleRepeat(); player.cycleRepeat()
        XCTAssertEqual(queues.active.repeatMode, .one)
        player.trackDidEnd()
        XCTAssertEqual(player.currentTrack?.relativePath, "a.mp3")
    }

    func testSleepAtEndOfTrackLoadsNextButPaused() {
        player.play([id("a.mp3"), id("b.mp3")], startAt: 0)
        player.setSleep(.endOfTrack)
        player.trackDidEnd()
        XCTAssertEqual(player.currentTrack?.relativePath, "b.mp3")
        XCTAssertFalse(player.isPlaying)
        XCTAssertEqual(player.sleepMode, .off)
    }

    func testSleepTimerPausesWhenItFires() async throws {
        player.play([id("a.mp3")], startAt: 0)
        player.setSleep(.until(Date().addingTimeInterval(0.2)))
        try await Task.sleep(nanoseconds: 700_000_000)
        XCTAssertFalse(player.isPlaying)
        XCTAssertEqual(player.sleepMode, .off)
    }

    func testPlayNextIntoEmptyQueueStartsIt() {
        player.playNext([id("a.mp3")])
        XCTAssertEqual(player.currentTrack?.relativePath, "a.mp3")
        player.addToQueue([id("b.mp3")], queue: nil)
        XCTAssertEqual(queues.active.trackIDs.count, 2)
        XCTAssertEqual(player.currentTrack?.relativePath, "a.mp3")
    }

    func testRemovingCurrentFromQueueMovesToSuccessorAndEmptyingClears() {
        player.play([id("a.mp3"), id("b.mp3")], startAt: 0)
        player.removeFromQueue(at: 0)
        XCTAssertEqual(player.currentTrack?.relativePath, "b.mp3")
        player.removeFromQueue(at: 0)
        XCTAssertNil(player.currentTrack)
        XCTAssertFalse(player.isPlaying)
    }

    func testFileDeletedFromDiskLeavesQueueOnRescan() async throws {
        player.play([id("a.mp3"), id("b.mp3")], startAt: 0)
        try FileManager.default.removeItem(at: docs.appendingPathComponent("a.mp3"))
        await library.rescan()
        XCTAssertEqual(player.currentTrack?.relativePath, "b.mp3")
        XCTAssertEqual(queues.active.trackIDs.count, 1)
    }

    func testRestoreLoadsSavedTrackAndPositionPaused() {
        player.play([id("a.mp3"), id("b.mp3")], startAt: 1)
        player.togglePlayPause()
        player.seek(to: 12)
        player.persistPosition()
        player.shutdown()

        makePlayer()
        player.restore()
        XCTAssertEqual(player.currentTrack?.relativePath, "b.mp3")
        XCTAssertEqual(player.currentTime, 12, accuracy: 0.5)
        XCTAssertFalse(player.isPlaying)
    }

    func testEQSettingsPersist() {
        player.eq = EQSettings(enabled: true, preamp: 2, gains: EQPreset.all[3].gains, presetName: "Rock")
        player.shutdown()
        makePlayer()
        XCTAssertEqual(player.eq.presetName, "Rock")
        XCTAssertTrue(player.eq.enabled)
    }

    func testCloudTrackPlaysAsynchronouslyAndRapidNextCancelsStaleLoad() async throws {
        let fixture = try XCTUnwrap(Bundle(for: PlayerControllerTests.self).url(forResource: "sample", withExtension: "mp3"))
        let sampleData = try Data(contentsOf: fixture)
        let fetcher = TestCloudFetcher(result: CloudPlaylistResult(
            kind: .cloudYouTube,
            remoteID: "PLplayback",
            title: "Cloud Play",
            tracks: [
                CloudTrackInfo(remoteTrackID: "c1", relativePath: "c1", title: "Cloud 1", artist: "A", album: "Al", trackNumber: 1, duration: 58, artworkURL: nil),
                CloudTrackInfo(remoteTrackID: "c2", relativePath: "c2", title: "Cloud 2", artist: "B", album: "Al", trackNumber: 2, duration: 58, artworkURL: nil)
            ]
        ))
        let cloudLib = LibraryStore(
            directory: makeTempDir(),
            documentsURL: makeTempDir(),
            reader: StubReader(),
            artwork: ArtworkCache(directory: makeTempDir()),
            cloudFetcher: fetcher,
            artworkDownloader: { _ in nil }
        )
        try await cloudLib.addCloudRoot(sourceURL: "https://music.youtube.com/playlist?list=PLplayback")

        let cacheDir = makeTempDir()
        let resolver = TestCloudResolver { track, _, _ in
            if track.remoteTrackID == "c1" {
                try await Task.sleep(nanoseconds: 250_000_000)
            }
            return CloudStreamResult(url: URL(string: "https://cdn.example/\(track.remoteTrackID ?? "x").mp3")!, headers: [:], fileExtension: "mp3", expiresAt: Date().addingTimeInterval(600))
        }
        let cache = CloudAudioCache(
            directory: cacheDir,
            maxBytes: 50_000_000,
            resolver: resolver,
            downloader: { _, _ in
                let tmp = makeTempDir().appendingPathComponent("s.mp3")
                try sampleData.write(to: tmp)
                return (tmp, 200, "audio/mpeg")
            }
        )

        let cloudState = makeTempDir()
        let cloudQueues = QueueStore(directory: cloudState)
        let cloudHistory = PlayHistoryStore(directory: cloudState)
        let cloudPlayer = PlayerController(
            library: cloudLib,
            queues: cloudQueues,
            history: cloudHistory,
            engine: AudioEngine(),
            settingsURL: cloudState.appendingPathComponent("settings.json"),
            cloudAudio: cache
        )
        defer { cloudPlayer.shutdown() }

        let ids = cloudLib.tracks.sorted { $0.relativePath < $1.relativePath }.map(\.id)
        cloudPlayer.play(ids, startAt: 0)
        XCTAssertEqual(cloudPlayer.currentTrack?.relativePath, "c1")
        XCTAssertTrue(cloudPlayer.isBuffering)

        // Rapidly skip to c2 while c1 is still resolving.
        cloudPlayer.next()
        XCTAssertEqual(cloudPlayer.currentTrack?.relativePath, "c2")

        // Wait for c2 to load and start playing.
        for _ in 0..<40 {
            if cloudPlayer.isPlaying { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertEqual(cloudPlayer.currentTrack?.relativePath, "c2")
        XCTAssertFalse(cloudPlayer.isBuffering)
        XCTAssertTrue(cloudPlayer.isPlaying)
        XCTAssertNil(cloudPlayer.errorMessage)
    }

    func testClearAndDeleteSpecificAndActiveQueues() throws {
        let initialID = queues.activeID
        player.play([id("a.mp3"), id("b.mp3")], startAt: 0)
        XCTAssertTrue(player.isPlaying)

        let secondID = queues.addQueue(name: "Secondary")
        player.addToQueue([id("b.mp3")], queue: secondID)
        XCTAssertEqual(queues.queues.count, 2)

        // Clearing an inactive queue should empty it without disturbing active playback.
        player.clearQueue(secondID)
        XCTAssertEqual(queues.queues.first { $0.id == secondID }?.trackIDs, [])
        XCTAssertEqual(queues.activeID, initialID)
        XCTAssertTrue(player.isPlaying)

        // Deleting an inactive queue removes it while keeping active queue intact.
        player.deleteQueue(secondID)
        XCTAssertEqual(queues.queues.map(\.id), [initialID])
        XCTAssertTrue(player.isPlaying)

        // Cannot delete the last remaining queue.
        player.deleteQueue(initialID)
        XCTAssertEqual(queues.queues.count, 1)

        // Add another queue, switch playback to it, then delete active queue -> switches cleanly.
        let thirdID = queues.addQueue(name: "Third")
        player.playInNamedQueue([id("b.mp3")], name: "Third", startAt: 0, shuffle: false)
        XCTAssertEqual(queues.activeID, thirdID)
        XCTAssertTrue(player.isPlaying)

        player.deleteQueue(thirdID)
        XCTAssertEqual(queues.activeID, initialID)
        XCTAssertEqual(queues.queues.map(\.id), [initialID])

        // Default clearQueue() clears active queue and stops playback.
        player.clearQueue()
        XCTAssertEqual(queues.active.trackIDs, [])
        XCTAssertFalse(player.isPlaying)
        XCTAssertNil(player.currentTrack)
    }

    func testSyncNamedQueueCreatesAndReplacesWithoutInterruptingOtherActiveQueue() throws {
        let aID = id("a.mp3")
        let bID = id("b.mp3")
        player.play([aID], startAt: 0)
        let originalActiveID = queues.activeID

        // Empty track list is ignored.
        player.syncNamedQueue([], name: "Empty Mix")
        XCTAssertFalse(queues.queues.contains { $0.name == "Empty Mix" })

        // Syncing a named queue creates it without switching away from active queue.
        player.syncNamedQueue([aID, bID], name: "Roadtrip")
        XCTAssertEqual(queues.activeID, originalActiveID)
        XCTAssertEqual(player.currentTrack?.id, aID)
        let roadtripID = try XCTUnwrap(queues.queues.first { $0.name == "Roadtrip" }?.id)
        XCTAssertEqual(queues.queues.first { $0.id == roadtripID }?.trackIDs, [aID, bID])

        // Replacing inactive same-name queue updates tracks without changing active queue.
        player.syncNamedQueue([bID], name: "Roadtrip")
        XCTAssertEqual(queues.activeID, originalActiveID)
        XCTAssertEqual(queues.queues.first { $0.id == roadtripID }?.trackIDs, [bID])

        // When the named queue IS active, syncNamedQueue preserves current track playback if still present.
        player.playInNamedQueue([aID, bID], name: "Roadtrip", startAt: 1, shuffle: false)
        XCTAssertEqual(queues.activeID, roadtripID)
        XCTAssertEqual(player.currentTrack?.id, bID)

        player.syncNamedQueue([bID, aID], name: "Roadtrip")
        XCTAssertEqual(queues.activeID, roadtripID)
        XCTAssertEqual(queues.active.trackIDs, [bID, aID])
        XCTAssertEqual(queues.active.currentTrackID, bID)
        XCTAssertEqual(player.currentTrack?.id, bID)
    }

    func testViewedQueueDecoupledFromPlayingQueueEditsAndPlayTrack() throws {
        let aID = id("a.mp3")
        let bID = id("b.mp3")
        player.play([aID], startAt: 0)
        let playingID = queues.playingQueueID
        XCTAssertTrue(player.isPlaying)
        XCTAssertEqual(queues.viewedQueueID, playingID)

        // Add another queue and select it for viewing -> playback in playingID must not be interrupted.
        let secondID = queues.addQueue(name: "Inspecting")
        queues.selectViewedQueue(secondID)
        XCTAssertEqual(queues.viewedQueueID, secondID)
        XCTAssertEqual(queues.playingQueueID, playingID)
        XCTAssertEqual(player.currentTrack?.id, aID)
        XCTAssertTrue(player.isPlaying)

        // Edits target viewedQueueID (addToQueue with nil, move, sort, remove, clear) without disturbing playingID.
        player.addToQueue([bID, aID], queue: nil)
        XCTAssertEqual(queues.viewed.trackIDs, [bID, aID])
        XCTAssertEqual(queues.active.trackIDs, [aID])
        XCTAssertEqual(player.currentTrack?.id, aID)
        XCTAssertTrue(player.isPlaying)

        player.moveInQueue(from: IndexSet(integer: 0), to: 2, inQueue: queues.viewedQueueID)
        XCTAssertEqual(queues.viewed.trackIDs, [aID, bID])

        player.sortQueue(queues.viewedQueueID, by: .nameDescending)
        XCTAssertEqual(queues.viewed.trackIDs.count, 2)

        player.removeFromQueue(at: 0, inQueue: queues.viewedQueueID)
        XCTAssertEqual(queues.viewed.trackIDs.count, 1)
        XCTAssertEqual(queues.playingQueueID, playingID)
        XCTAssertTrue(player.isPlaying)

        // Clicking a song row calls playTrack(inQueue:at:) which switches playingQueueID and starts playing.
        let expectedTargetTrack = queues.viewed.trackIDs[0]
        player.playTrack(inQueue: queues.viewedQueueID, at: 0)
        XCTAssertEqual(queues.playingQueueID, secondID)
        XCTAssertEqual(player.currentTrack?.id, expectedTargetTrack)
        XCTAssertTrue(player.isPlaying)

        // Deleting the playing queue pauses cleanly without auto-starting the fallback queue.
        player.deleteQueue(secondID)
        XCTAssertEqual(queues.playingQueueID, playingID)
        XCTAssertFalse(player.isPlaying)
    }

    func testPlayInNamedQueuePreservesExistingQueuesForLibraryContexts() {
        let aID = id("a.mp3")
        let bID = id("b.mp3")
        player.play([aID], startAt: 0)
        let customID = queues.playingQueueID
        queues.rename(customID, to: "My Custom Queue")

        player.playInNamedQueue([aID, bID], name: "Songs", startAt: 1, shuffle: false)
        XCTAssertEqual(queues.active.name, "Songs")
        XCTAssertEqual(queues.active.currentTrackID, bID)
        XCTAssertEqual(queues.queues.first { $0.id == customID }?.trackIDs, [aID])

        player.playInNamedQueue([bID, aID], name: "Album: Greatest Hits", startAt: 0, shuffle: false)
        XCTAssertEqual(queues.active.name, "Album: Greatest Hits")

        player.playInNamedQueue([aID], name: "Artist: Kano", startAt: 0, shuffle: true)
        XCTAssertEqual(queues.active.name, "Artist: Kano")

        player.playInNamedQueue([bID], name: "Stats", startAt: 0, shuffle: false)
        XCTAssertEqual(queues.active.name, "Stats")
        XCTAssertEqual(queues.queues.first { $0.id == customID }?.trackIDs, [aID])
    }
}

private struct TestCloudFetcher: CloudPlaylistFetching {
    let result: CloudPlaylistResult
    func fetchPlaylist(sourceURL: String, cookie: String?, kindHint: LibraryRoot.Kind?) async throws -> CloudPlaylistResult {
        result
    }
}

private struct TestCloudResolver: CloudStreamResolving {
    let handler: @Sendable (Track, LibraryRoot?, Bool) async throws -> CloudStreamResult
    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        try await handler(track, root, forceRefresh)
    }
}
