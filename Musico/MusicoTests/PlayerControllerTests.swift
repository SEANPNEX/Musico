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
        let registry = CloudProviderRegistry()
        registry.registerExtension(TestQualityExtensionProvider(id: "ext.test-cloud"))
        let fetcher = TestCloudFetcher(result: CloudPlaylistResult(
            kind: .cloudExtension,
            remoteID: "PLplayback",
            title: "Cloud Play",
            tracks: [
                CloudTrackInfo(remoteTrackID: "c1", relativePath: "c1", title: "Cloud 1", artist: "A", album: "Al", trackNumber: 1, duration: 58, artworkURL: nil),
                CloudTrackInfo(remoteTrackID: "c2", relativePath: "c2", title: "Cloud 2", artist: "B", album: "Al", trackNumber: 2, duration: 58, artworkURL: nil)
            ],
            providerID: "ext.test-cloud"
        ))
        let cloudLib = LibraryStore(
            directory: makeTempDir(),
            documentsURL: makeTempDir(),
            reader: StubReader(),
            artwork: ArtworkCache(directory: makeTempDir()),
            cloudFetcher: fetcher,
            providerRegistry: registry,
            artworkDownloader: { _ in nil }
        )
        try await cloudLib.addCloudRoot(sourceURL: "https://ext.test/list/PLplayback", preferredProviderID: "ext.test-cloud")

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

    func testCurrentTrackOnlyQualitySwitchingPreservesPositionAndResetsOnNextTrack() async throws {
        let fixture = try XCTUnwrap(Bundle(for: PlayerControllerTests.self).url(forResource: "sample", withExtension: "mp3"))
        let sampleData = try Data(contentsOf: fixture)

        let registry = CloudProviderRegistry()
        registry.registerExtension(TestQualityExtensionProvider(id: "ext.test-quality"))
        let fetcher = TestCloudFetcher(result: CloudPlaylistResult(
            kind: .cloudExtension,
            remoteID: "BVqtest",
            title: "Quality Play",
            tracks: [
                CloudTrackInfo(remoteTrackID: "q1", relativePath: "q1", title: "Track Q1", artist: "A", album: "Al", trackNumber: 1, duration: 58, artworkURL: nil),
                CloudTrackInfo(remoteTrackID: "q2", relativePath: "q2", title: "Track Q2", artist: "B", album: "Al", trackNumber: 2, duration: 58, artworkURL: nil)
            ],
            providerID: "ext.test-quality"
        ))
        let cloudLib = LibraryStore(
            directory: makeTempDir(),
            documentsURL: makeTempDir(),
            reader: StubReader(),
            artwork: ArtworkCache(directory: makeTempDir()),
            cloudFetcher: fetcher,
            providerRegistry: registry,
            artworkDownloader: { _ in nil }
        )
        try await cloudLib.addCloudRoot(sourceURL: "https://ext.test/quality/BVqtest", preferredProviderID: "ext.test-quality")
        let rootID = try XCTUnwrap(cloudLib.roots.first?.id)

        let recordedLock = NSLock()
        var recorded: [(trackID: String, purpose: CloudStreamPurpose, quality: String?, forceRefresh: Bool)] = []
        let resolver = QualityAwareTestCloudResolver { track, _, purpose, quality, forceRefresh in
            recordedLock.withLock {
                recorded.append((track.remoteTrackID ?? "", purpose, quality, forceRefresh))
            }
            let label = (quality == "highest") ? "FLAC" : "132k"
            return CloudStreamResult(
                url: URL(string: "https://cdn.example/\(track.remoteTrackID ?? "x")-\(quality ?? "std").mp3")!,
                headers: [:],
                fileExtension: "mp3",
                expiresAt: Date().addingTimeInterval(600),
                actualQuality: quality,
                qualityLabel: label
            )
        }
        let cache = CloudAudioCache(
            directory: makeTempDir(),
            maxBytes: 50_000_000,
            resolver: resolver,
            downloader: { _, _ in
                let tmp = makeTempDir().appendingPathComponent("q.mp3")
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
        for _ in 0..<40 {
            if cloudPlayer.isPlaying { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertTrue(cloudPlayer.isPlaying)
        XCTAssertEqual(cloudPlayer.currentStreamQualityLabel, "132k")

        // Seek partway into track 1 before switching quality
        cloudPlayer.seek(to: 12)
        XCTAssertGreaterThan(cloudPlayer.currentTime, 10)

        cloudPlayer.switchCurrentTrackQuality(to: "highest")
        for _ in 0..<40 {
            if cloudPlayer.isPlaying && cloudPlayer.currentStreamQualityLabel == "FLAC" { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertEqual(cloudPlayer.currentStreamQualityLabel, "FLAC")
        XCTAssertEqual(cloudPlayer.activeQualityIDForCurrentTrack, "highest")
        XCTAssertGreaterThanOrEqual(cloudPlayer.currentTime, 11)
        // Folder default must remain untouched (current-track-only!)
        XCTAssertNil(cloudLib.root(rootID)?.streamQualityID)

        // Advancing to track 2 resets quality back to provider/folder default ("standard")
        cloudPlayer.next()
        for _ in 0..<40 {
            if cloudPlayer.currentTrack?.relativePath == "q2" && cloudPlayer.isPlaying { break }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        XCTAssertEqual(cloudPlayer.currentTrack?.relativePath, "q2")
        XCTAssertEqual(cloudPlayer.currentStreamQualityLabel, "132k")

        let calls = recordedLock.withLock { recorded }
        XCTAssertTrue(calls.contains { $0.trackID == "q1" && $0.purpose == .stream && $0.quality == "standard" && !$0.forceRefresh })
        XCTAssertTrue(calls.contains { $0.trackID == "q1" && $0.purpose == .stream && $0.quality == "highest" && $0.forceRefresh })
        XCTAssertTrue(calls.contains { $0.trackID == "q2" && $0.purpose == .stream && $0.quality == "standard" && !$0.forceRefresh })
    }

    func testVolumeMutePersistenceAndDeleteTrackStopsPlayback() throws {
        player.volume = 0.42
        player.toggleMute()
        XCTAssertTrue(player.isMuted)
        player.shutdown()

        makePlayer()
        XCTAssertEqual(player.volume, 0.42, accuracy: 0.001)
        XCTAssertTrue(player.isMuted)
        player.toggleMute()
        XCTAssertFalse(player.isMuted)
        XCTAssertEqual(player.volume, 0.42, accuracy: 0.001)

        // Playing track gets stopped/cleared before disk deletion
        let trackA = id("a.mp3")
        player.play([trackA, id("b.mp3")], startAt: 0)
        XCTAssertEqual(player.currentTrack?.id, trackA)
        try library.deleteTrackFile(trackA)
        XCTAssertFalse(FileManager.default.fileExists(atPath: docs.appendingPathComponent("a.mp3").path))
        XCTAssertNotEqual(player.currentTrack?.id, trackA)
    }

    func testRemoveFromQueueWithUndoAndRestoreToQueue() throws {
        let aID = id("a.mp3")
        let bID = id("b.mp3")

        // Out-of-bounds removal returns nil.
        XCTAssertNil(player.removeFromQueueWithUndo(at: 99))

        // Removing non-current track preserves playback and restores at exact index.
        player.play([aID, bID], startAt: 0)
        XCTAssertTrue(player.isPlaying)
        let removedNonCurrent = try XCTUnwrap(player.removeFromQueueWithUndo(at: 1))
        XCTAssertEqual(queues.active.trackIDs, [aID])
        XCTAssertEqual(player.currentTrack?.id, aID)
        XCTAssertFalse(removedNonCurrent.wasCurrent)

        player.restoreToQueue(removedNonCurrent)
        XCTAssertEqual(queues.active.trackIDs, [aID, bID])
        XCTAssertEqual(player.currentTrack?.id, aID)
        XCTAssertTrue(player.isPlaying)

        // Removing currently playing track captures position & playing state, and restore resumes at that position.
        player.seek(to: 14)
        let removedCurrent = try XCTUnwrap(player.removeFromQueueWithUndo(at: 0))
        XCTAssertTrue(removedCurrent.wasCurrent)
        XCTAssertTrue(removedCurrent.wasPlaying)
        XCTAssertEqual(removedCurrent.position, 14, accuracy: 0.6)
        XCTAssertEqual(player.currentTrack?.id, bID)

        player.restoreToQueue(removedCurrent)
        XCTAssertEqual(queues.active.trackIDs, [aID, bID])
        XCTAssertEqual(queues.active.currentIndex, 0)
        XCTAssertEqual(player.currentTrack?.id, aID)
        XCTAssertEqual(player.currentTime, 14, accuracy: 0.6)
        XCTAssertTrue(player.isPlaying)

        // Removing paused current track restores it paused.
        player.pause()
        let removedPaused = try XCTUnwrap(player.removeFromQueueWithUndo(at: 0))
        XCTAssertFalse(removedPaused.wasPlaying)
        player.restoreToQueue(removedPaused)
        XCTAssertEqual(player.currentTrack?.id, aID)
        XCTAssertFalse(player.isPlaying)

        // Removing and restoring in an inactive queue leaves active playback undisturbed.
        let otherID = queues.addQueue(name: "Other", trackIDs: [bID, aID], startAt: 0)
        let removedOther = try XCTUnwrap(player.removeFromQueueWithUndo(at: 1, inQueue: otherID))
        XCTAssertEqual(queues.queues.first { $0.id == otherID }?.trackIDs, [bID])
        XCTAssertEqual(player.currentTrack?.id, aID)
        player.restoreToQueue(removedOther)
        XCTAssertEqual(queues.queues.first { $0.id == otherID }?.trackIDs, [bID, aID])
        XCTAssertEqual(player.currentTrack?.id, aID)

        // Restoring into a deleted queue is a safe no-op.
        player.deleteQueue(otherID)
        player.restoreToQueue(removedOther)
        XCTAssertFalse(queues.queues.contains { $0.id == otherID })
    }

    func testTracksRemappedUpdatesQueuesHistoryAndCurrentTrack() {
        let aID = id("a.mp3")
        let bID = id("b.mp3")
        player.play([aID, bID], startAt: 0)
        history.recordPlay(aID)
        XCTAssertEqual(player.currentTrack?.id, aID)

        let secondID = queues.addQueue(name: "Extra", trackIDs: [aID, bID], startAt: 0)
        player.tracksRemapped([aID: bID])

        XCTAssertEqual(queues.active.trackIDs, [bID, bID])
        XCTAssertEqual(queues.queues.first { $0.id == secondID }?.trackIDs, [bID, bID])
        XCTAssertEqual(history.count(for: aID), 0)
        XCTAssertEqual(history.count(for: bID), 1)
        XCTAssertEqual(player.currentTrack?.id, bID)
    }
}

private struct TestQualityExtensionProvider: CloudSourceProvider {
    let id: String
    var displayName: String { "Test Cloud" }
    var badgeLabel: String { "Test" }
    var rootKind: LibraryRoot.Kind { .cloudExtension }
    var trackCloudSource: String { id }
    var supportsTree: Bool { false }
    var supportsCookie: Bool { true }
    var availableQualities: [CloudQualityOption] {
        [
            CloudQualityOption(id: "highest", label: "Highest (FLAC)", badge: "FLAC", lossless: true),
            CloudQualityOption(id: "standard", label: "Standard (132k)", badge: "132k", lossless: false)
        ]
    }
    var defaultStreamQualityID: String? { "standard" }
    var defaultDownloadQualityID: String? { "highest" }

    func canHandle(rawText: String, extractedURL: String) -> CloudMatchResult? {
        let candidate = extractedURL.isEmpty ? rawText : extractedURL
        return CloudMatchResult(
            providerID: id,
            kind: .cloudExtension,
            extractedURL: candidate,
            normalizedURL: candidate,
            remoteID: "test",
            itemKind: "playlist"
        )
    }

    func fetchCollection(sourceURL: String, cookie: String?) async throws -> CloudPlaylistResult {
        CloudPlaylistResult(kind: .cloudExtension, remoteID: "test", title: "Test", tracks: [], providerID: id)
    }

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        CloudStreamResult(
            url: URL(string: "https://cdn.example/\(track.remoteTrackID ?? "x").mp3")!,
            headers: [:],
            fileExtension: "mp3",
            expiresAt: Date().addingTimeInterval(600)
        )
    }
}

private struct QualityAwareTestCloudResolver: CloudStreamResolving {
    let handler: @Sendable (Track, LibraryRoot?, CloudStreamPurpose, String?, Bool) async throws -> CloudStreamResult

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        try await handler(track, root, .stream, nil, forceRefresh)
    }

    func resolveStream(
        track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        qualityID: String?,
        forceRefresh: Bool
    ) async throws -> CloudStreamResult {
        try await handler(track, root, purpose, qualityID, forceRefresh)
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
