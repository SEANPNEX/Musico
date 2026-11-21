import XCTest
@testable import Musico

@MainActor
final class AudioEngineTests: XCTestCase {
    private func fixture() throws -> URL {
        try XCTUnwrap(Bundle(for: AudioEngineTests.self).url(forResource: "sample", withExtension: "mp3"))
    }

    private func fixture(_ name: String, _ ext: String) throws -> URL {
        try XCTUnwrap(Bundle(for: AudioEngineTests.self).url(forResource: name, withExtension: ext))
    }

    /// Songs in one library differ in sample rate and channel count; switching must not crash.
    func testSwitchingBetweenFilesOfDifferentFormatsWhilePlaying() throws {
        let engine = AudioEngine()
        let files = [try fixture(), try fixture("tone-48k-mono", "wav"), try fixture("hires-96k", "flac"),
                     try fixture("art", "m4a"), try fixture("surround", "flac"), try fixture()]
        for url in files {
            try engine.load(url: url)
            engine.play()
            XCTAssertTrue(engine.isPlaying, url.lastPathComponent)
            engine.seek(to: 0.3)
            XCTAssertTrue(engine.isPlaying, url.lastPathComponent)
        }
    }

    func testLoadReportsDurationAndStartsPausedAtZero() throws {
        let engine = AudioEngine()
        try engine.load(url: fixture())
        XCTAssertEqual(engine.duration, 58.6, accuracy: 1.0)
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(engine.currentTime, 0)
    }

    func testSeekWhilePausedUpdatesCurrentTimeAndStaysPaused() throws {
        let engine = AudioEngine()
        try engine.load(url: fixture())
        engine.seek(to: 20)
        XCTAssertEqual(engine.currentTime, 20, accuracy: 0.05)
        XCTAssertFalse(engine.isPlaying)
    }

    func testSeekPastEndClampsWithoutCrashOrEndCallbackWhilePaused() throws {
        let engine = AudioEngine()
        var ended = 0
        engine.onTrackEnded = { ended += 1 }
        try engine.load(url: fixture())
        engine.seek(to: 9999)
        XCTAssertEqual(engine.currentTime, engine.duration, accuracy: 0.05)
        engine.seek(to: -5)
        XCTAssertEqual(engine.currentTime, 0)
        XCTAssertEqual(ended, 0)
    }

    func testLoadMissingFileThrowsAndClearsState() throws {
        let engine = AudioEngine()
        try engine.load(url: fixture())
        XCTAssertThrowsError(try engine.load(url: URL(fileURLWithPath: "/nonexistent/x.mp3")))
        XCTAssertEqual(engine.duration, 0)
        engine.seek(to: 10)
        engine.play()
        XCTAssertFalse(engine.isPlaying)
    }

    func testOperationsBeforeLoadAreSafe() {
        let engine = AudioEngine()
        engine.play(); engine.pause(); engine.seek(to: 3); engine.unload()
        XCTAssertFalse(engine.isPlaying)
        XCTAssertEqual(engine.currentTime, 0)
    }

    func testApplyEQSetsBandsAndClampsGain() {
        let engine = AudioEngine()
        for preset in EQPreset.all {
            engine.apply(EQSettings(enabled: true, preamp: 0, gains: preset.gains, presetName: preset.name))
            XCTAssertEqual(engine.bandGains, preset.gains)
        }
        engine.apply(EQSettings(enabled: true, preamp: 40, gains: Array(repeating: 99, count: 10), presetName: nil))
        XCTAssertEqual(engine.bandGains, Array(repeating: 12, count: 10))
        engine.apply(EQSettings(enabled: false, preamp: 0, gains: [1, 2], presetName: nil))
        XCTAssertTrue(engine.isEQBypassed)
    }

    func testPauseKeepsPositionWhenEngineNoLongerReportsTime() throws {
        let engine = AudioEngine()
        try engine.load(url: fixture())
        engine.seek(to: 10)
        engine.play()
        try XCTSkipUnless(engine.isPlaying, "audio output unavailable")
        engine.renderedFrames = { 5 * 44100 }
        XCTAssertEqual(engine.currentTime, 15, accuracy: 0.05)
        // A phone call or route change stops the engine before we are told; it then reports no time.
        engine.renderedFrames = { nil }
        engine.pause()
        XCTAssertEqual(engine.currentTime, 15, accuracy: 0.05)
    }

    func testEngineStopsRenderingWhenTrackEndsOrUnloads() throws {
        let engine = AudioEngine()
        try engine.load(url: fixture())
        engine.play()
        try XCTSkipUnless(engine.isPlaying, "audio output unavailable")
        XCTAssertTrue(engine.isEngineRunning)
        engine.seek(to: 9999)
        XCTAssertFalse(engine.isPlaying)
        XCTAssertFalse(engine.isEngineRunning)

        try engine.load(url: fixture())
        engine.play()
        XCTAssertTrue(engine.isEngineRunning)
        engine.unload()
        XCTAssertFalse(engine.isEngineRunning)
    }
}
