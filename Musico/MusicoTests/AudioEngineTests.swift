import Network
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

    func testLoadStreamWithChunkedResourceLoaderReachesReadyToPlaySeekAndUnload() async throws {
        let m4aURL = try fixture("art", "m4a")
        let payload = try Data(contentsOf: m4aURL)

        let server = try StreamFixtureHTTPServer(payload: payload, contentType: "audio/mp4")
        let baseURL = try await server.start()
        defer { server.stop() }

        let engine = AudioEngine()
        let readyExp = expectation(description: "stream readyToPlay")
        var readyDuration: TimeInterval = 0
        var failureMessage: String?

        engine.onStreamReady = { dur in
            readyDuration = dur
            readyExp.fulfill()
        }
        engine.onStreamFailed = { msg in
            failureMessage = msg
            readyExp.fulfill()
        }

        engine.loadStream(
            url: baseURL.appendingPathComponent("art.m4a"),
            headers: ["Range": "bytes=0-"],
            contentLength: Int64(payload.count),
            fileExtension: "m4a",
            estimatedDuration: 0
        )

        await fulfillment(of: [readyExp], timeout: 10)
        XCTAssertNil(failureMessage)
        XCTAssertGreaterThan(readyDuration, 0)
        XCTAssertGreaterThan(engine.duration, 0)

        engine.seek(to: min(1.0, engine.duration / 2))
        engine.play()
        XCTAssertTrue(engine.isPlaying)
        engine.pause()
        XCTAssertFalse(engine.isPlaying)

        engine.unload()
        XCTAssertEqual(engine.duration, 0)
        XCTAssertEqual(engine.currentTime, 0)
        XCTAssertFalse(engine.isPlaying)
    }
}

private final class StreamFixtureHTTPServer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.musico.audio-engine-stream-test")
    private var listener: NWListener?
    private let payload: Data
    private let contentType: String

    init(payload: Data, contentType: String) throws {
        self.payload = payload
        self.contentType = contentType
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        self.listener = try NWListener(using: params, on: .any)
    }

    func start() async throws -> URL {
        guard let listener else { throw URLError(.cannotOpenFile) }
        return try await withCheckedThrowingContinuation { continuation in
            let lock = NSLock()
            var didResume = false
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    lock.lock()
                    let shouldResume = !didResume
                    didResume = true
                    lock.unlock()
                    if shouldResume {
                        let port = listener.port?.rawValue ?? 0
                        continuation.resume(returning: URL(string: "http://127.0.0.1:\(port)")!)
                    }
                case .failed(let error):
                    lock.lock()
                    let shouldResume = !didResume
                    didResume = true
                    lock.unlock()
                    if shouldResume {
                        continuation.resume(throwing: error)
                    }
                default:
                    break
                }
            }
            listener.newConnectionHandler = { [weak self] conn in
                self?.serve(conn)
            }
            listener.start(queue: queue)
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func serve(_ conn: NWConnection) {
        conn.start(queue: queue)
        readHeaders(conn: conn, buffer: Data())
    }

    private func readHeaders(conn: NWConnection, buffer: Data) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if error != nil { conn.cancel(); return }
            var next = buffer
            if let data { next.append(data) }
            if let end = next.range(of: Data("\r\n\r\n".utf8)) {
                let text = String(data: next[..<end.lowerBound], encoding: .utf8) ?? ""
                self.writeResponse(for: text, on: conn)
                return
            }
            if isComplete { conn.cancel(); return }
            self.readHeaders(conn: conn, buffer: next)
        }
    }

    private func writeResponse(for rawRequest: String, on conn: NWConnection) {
        var rangeHeader: String?
        for line in rawRequest.components(separatedBy: "\r\n").dropFirst() {
            if let colon = line.firstIndex(of: ":") {
                let key = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
                if key == "range" {
                    rangeHeader = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                }
            }
        }
        if let rangeHeader, rangeHeader.hasPrefix("bytes=") {
            let spec = String(rangeHeader.dropFirst(6))
            if let dash = spec.firstIndex(of: "-") {
                let start = Int(String(spec[..<dash])) ?? 0
                let endPart = String(spec[spec.index(after: dash)...])
                let requestedEnd = endPart.isEmpty ? (payload.count - 1) : (Int(endPart) ?? (payload.count - 1))
                let end = min(requestedEnd, payload.count - 1)
                if start <= end, start < payload.count {
                    let slice = payload[start...end]
                    let header = "HTTP/1.1 206 Partial Content\r\nContent-Type: \(contentType)\r\nContent-Range: bytes \(start)-\(end)/\(payload.count)\r\nContent-Length: \(slice.count)\r\nConnection: close\r\n\r\n"
                    var out = Data(header.utf8)
                    out.append(slice)
                    conn.send(content: out, completion: .contentProcessed { _ in conn.cancel() })
                    return
                }
            }
        }
        let header = "HTTP/1.1 200 OK\r\nContent-Type: \(contentType)\r\nContent-Length: \(payload.count)\r\nConnection: close\r\n\r\n"
        var out = Data(header.utf8)
        out.append(payload)
        conn.send(content: out, completion: .contentProcessed { _ in conn.cancel() })
    }
}
