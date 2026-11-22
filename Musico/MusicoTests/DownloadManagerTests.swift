import Foundation
import Network
import XCTest
@testable import Musico

private struct TestStreamResolver: CloudStreamResolving {
    let handler: @Sendable (Track, LibraryRoot?, Bool) async throws -> CloudStreamResult

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        try await handler(track, root, forceRefresh)
    }
}

private final class TestLockBox<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: Value

    init(_ initial: Value) {
        self.storage = initial
    }

    var value: Value { lock.withLock { storage } }

    func withLock<T>(_ body: (inout Value) -> T) -> T {
        lock.withLock { body(&storage) }
    }
}

private final class LocalRangeHTTPServer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.musico.test-http-server")
    private var listener: NWListener?
    private let payload: Data
    private let contentType: String
    private let stallPaths: Set<String>
    private let lock = NSLock()
    private var recordedRanges: [String] = []
    private var activeConnections: [NWConnection] = []
    private(set) var port: UInt16 = 0

    init(payload: Data, contentType: String = "audio/mp4", stallPaths: Set<String> = []) throws {
        self.payload = payload
        self.contentType = contentType
        self.stallPaths = stallPaths
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        self.listener = try NWListener(using: params, on: .any)
    }

    func start() async throws -> URL {
        guard let listener else { throw URLError(.cannotOpenFile) }
        return try await withCheckedThrowingContinuation { continuation in
            let resumed = TestLockBox(false)
            listener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    if resumed.withLock({ let was = $0; $0 = true; return !was }) {
                        let p = listener.port?.rawValue ?? 0
                        self.port = p
                        continuation.resume(returning: URL(string: "http://127.0.0.1:\(p)")!)
                    }
                case .failed(let error):
                    if resumed.withLock({ let was = $0; $0 = true; return !was }) {
                        continuation.resume(throwing: error)
                    }
                default:
                    break
                }
            }
            listener.newConnectionHandler = { [weak self] conn in
                self?.handleConnection(conn)
            }
            listener.start(queue: queue)
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
        let conns = lock.withLock {
            let copy = activeConnections
            activeConnections.removeAll()
            return copy
        }
        for c in conns { c.cancel() }
    }

    var ranges: [String] {
        lock.withLock { recordedRanges }
    }

    private func handleConnection(_ conn: NWConnection) {
        lock.withLock { activeConnections.append(conn) }
        conn.start(queue: queue)
        receiveRequest(on: conn, accumulated: Data())
    }

    private func receiveRequest(on conn: NWConnection, accumulated: Data) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if error != nil {
                conn.cancel()
                return
            }
            var buffer = accumulated
            if let data {
                buffer.append(data)
            }
            if let headerEnd = buffer.range(of: Data("\r\n\r\n".utf8)) {
                let headerText = String(data: buffer[..<headerEnd.lowerBound], encoding: .utf8) ?? ""
                self.respond(to: headerText, on: conn)
                return
            }
            if isComplete {
                conn.cancel()
                return
            }
            self.receiveRequest(on: conn, accumulated: buffer)
        }
    }

    private func respond(to rawRequest: String, on conn: NWConnection) {
        let lines = rawRequest.components(separatedBy: "\r\n")
        let requestLine = lines.first ?? ""
        let parts = requestLine.split(separator: " ")
        let path = parts.count >= 2 ? String(parts[1]) : "/"

        if stallPaths.contains(path) {
            // Intentionally keep the socket open with no response to test instant cancellation.
            return
        }

        var rangeHeader: String?
        for line in lines.dropFirst() {
            if let colon = line.firstIndex(of: ":") {
                let name = line[..<colon].trimmingCharacters(in: .whitespaces).lowercased()
                if name == "range" {
                    rangeHeader = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                }
            }
        }

        if let rangeHeader {
            lock.withLock { recordedRanges.append(rangeHeader) }
        }

        if let rangeHeader, rangeHeader.hasPrefix("bytes=") {
            let spec = String(rangeHeader.dropFirst(6))
            if let dash = spec.firstIndex(of: "-") {
                let startStr = String(spec[..<dash])
                let endStr = String(spec[spec.index(after: dash)...])
                let start = Int(startStr) ?? 0
                let requestedEnd = endStr.isEmpty ? (payload.count - 1) : (Int(endStr) ?? (payload.count - 1))
                if start >= payload.count {
                    let header = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */\(payload.count)\r\nConnection: close\r\n\r\n"
                    conn.send(content: Data(header.utf8), completion: .contentProcessed { _ in conn.cancel() })
                    return
                }
                let end = min(requestedEnd, payload.count - 1)
                let slice = payload[start...end]
                let header = "HTTP/1.1 206 Partial Content\r\nContent-Type: \(contentType)\r\nContent-Range: bytes \(start)-\(end)/\(payload.count)\r\nContent-Length: \(slice.count)\r\nConnection: close\r\n\r\n"
                var out = Data(header.utf8)
                out.append(slice)
                conn.send(content: out, completion: .contentProcessed { _ in conn.cancel() })
                return
            }
        }

        let header = "HTTP/1.1 200 OK\r\nContent-Type: \(contentType)\r\nContent-Length: \(payload.count)\r\nConnection: close\r\n\r\n"
        var out = Data(header.utf8)
        out.append(payload)
        conn.send(content: out, completion: .contentProcessed { _ in conn.cancel() })
    }
}

final class DownloadManagerTests: XCTestCase {
    private func makeCloudTrack(id: String = "track-1", title: String = "Test Track") -> Track {
        Track(
            id: id,
            rootID: UUID(),
            relativePath: id,
            title: title,
            artist: "Artist",
            album: "Album",
            albumArtist: "Artist",
            duration: 180,
            fileSize: 0,
            modified: Date(timeIntervalSince1970: 0),
            hasEmbeddedLyrics: false,
            dateAdded: Date(timeIntervalSince1970: 0),
            cloudSource: "youtube",
            remoteTrackID: id
        )
    }

    func testCloudAudioCacheParallelChunkedDownloadAndProgressCallbacks() async throws {
        // 9 MB payload -> triggers 4 MB parallel chunks (0..4MB, 4..8MB, 8..9MB).
        let totalSize = 9 * 1024 * 1024 + 12345
        var payload = Data(count: totalSize)
        payload.withUnsafeMutableBytes { raw in
            guard let ptr = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            for i in 0..<totalSize {
                ptr[i] = UInt8((i * 31 + (i >> 8)) & 0xFF)
            }
        }

        let server = try LocalRangeHTTPServer(payload: payload, contentType: "audio/mp4")
        let baseURL = try await server.start()
        defer { server.stop() }

        let cacheDir = makeTempDir()
        let resolver = TestStreamResolver { _, _, _ in
            CloudStreamResult(
                url: baseURL.appendingPathComponent("chunked.m4a"),
                headers: ["Range": "bytes=0-"],
                fileExtension: "m4a",
                expiresAt: Date().addingTimeInterval(600),
                contentLength: Int64(totalSize)
            )
        }
        let cache = CloudAudioCache(directory: cacheDir, maxBytes: 50_000_000, resolver: resolver)
        let track = makeCloudTrack(id: "parallel-9mb", title: "Parallel 9MB")

        let updates = TestLockBox<[CloudDownloadProgress]>([])
        let cachedURL = try await cache.resolveAndCache(track: track, root: nil) { progress in
            updates.withLock { $0.append(progress) }
        }

        let downloadedData = try Data(contentsOf: cachedURL)
        XCTAssertEqual(downloadedData.count, totalSize)
        XCTAssertEqual(downloadedData, payload)

        let ranges = Set(server.ranges)
        XCTAssertTrue(ranges.contains("bytes=0-4194303"))
        XCTAssertTrue(ranges.contains("bytes=4194304-8388607"))
        XCTAssertTrue(ranges.contains("bytes=8388608-\(totalSize - 1)"))

        // Give progress callback dispatch a brief moment to deliver final tick.
        try await Task.sleep(for: .milliseconds(50))
        let recorded = updates.value
        XCTAssertFalse(recorded.isEmpty)
        let last = try XCTUnwrap(recorded.last)
        XCTAssertEqual(last.bytesDownloaded, Int64(totalSize))
        XCTAssertEqual(last.totalBytes, Int64(totalSize))
        XCTAssertEqual(last.fractionCompleted ?? 0, 1.0, accuracy: 0.001)
        XCTAssertGreaterThan(last.bytesPerSecond, 0)
    }

    func testCloudAudioCacheInstantCancellationCleansUpAndAllowsSubsequentDownload() async throws {
        let smallPayload = Data("hello-audio-stream".utf8)
        let server = try LocalRangeHTTPServer(payload: smallPayload, stallPaths: ["/hang.m4a"])
        let baseURL = try await server.start()
        defer { server.stop() }

        let attemptCounter = TestLockBox(0)
        let resolver = TestStreamResolver { _, _, _ in
            let attempt = attemptCounter.withLock { $0 += 1; return $0 }
            let path = attempt == 1 ? "hang.m4a" : "ok.m4a"
            return CloudStreamResult(
                url: baseURL.appendingPathComponent(path),
                headers: [:],
                fileExtension: "m4a",
                expiresAt: Date().addingTimeInterval(600),
                contentLength: Int64(smallPayload.count)
            )
        }

        let cache = CloudAudioCache(directory: makeTempDir(), maxBytes: 10_000_000, resolver: resolver)
        let track = makeCloudTrack(id: "cancel-track", title: "Cancel Track")

        let tempFilesBefore = Set(
            (try? FileManager.default.contentsOfDirectory(atPath: FileManager.default.temporaryDirectory.path))?
                .filter { $0.hasPrefix("musico-dl-") } ?? []
        )

        let downloadTask = Task {
            try await cache.resolveAndCache(track: track, root: nil)
        }

        try await Task.sleep(for: .milliseconds(40))
        let cancelStart = Date()
        downloadTask.cancel()

        do {
            _ = try await downloadTask.value
            XCTFail("Expected CancellationError")
        } catch is CancellationError {
            let elapsed = Date().timeIntervalSince(cancelStart)
            XCTAssertLessThan(elapsed, 0.25, "Cancellation took too long: \(elapsed)s")
        }

        try await Task.sleep(for: .milliseconds(50))
        let tempFilesAfter = Set(
            (try? FileManager.default.contentsOfDirectory(atPath: FileManager.default.temporaryDirectory.path))?
                .filter { $0.hasPrefix("musico-dl-") } ?? []
        )
        XCTAssertEqual(tempFilesAfter.subtracting(tempFilesBefore), [])

        // Subsequent download of the same track must not remain stuck on the cancelled inFlight entry.
        let recoveredURL = try await cache.resolveAndCache(track: track, root: nil)
        XCTAssertEqual(try Data(contentsOf: recoveredURL), smallPayload)
    }

    @MainActor
    func testDownloadManagerPlaylistExportAndInstantCancellation() async throws {
        let root = LibraryRoot(
            id: UUID(),
            name: "Cloud Mix",
            kind: .cloudYouTube,
            bookmark: nil,
            sourceURL: "https://music.youtube.com/playlist?list=PLdm",
            remoteID: "PLdm"
        )
        let t1 = makeCloudTrack(id: "dm-1", title: "Song One")
        let t2 = makeCloudTrack(id: "dm-2", title: "Song Two")

        let resolver = TestStreamResolver { track, _, _ in
            CloudStreamResult(
                url: URL(string: "https://cdn.example/\(track.id).m4a")!,
                headers: [:],
                fileExtension: "m4a",
                expiresAt: Date().addingTimeInterval(600)
            )
        }
        let cache = CloudAudioCache(
            directory: makeTempDir(),
            maxBytes: 10_000_000,
            resolver: resolver,
            downloader: { url, _ in
                if url.lastPathComponent == "dm-1.m4a" {
                    let tmp = makeTempDir().appendingPathComponent("a.m4a")
                    try Data("track-one".utf8).write(to: tmp)
                    return (tmp, 200, "audio/mp4")
                }
                // Second track waits until cancelled in cancellation test, or completes quickly when not cancelled.
                try await Task.sleep(for: .milliseconds(50))
                let tmp = makeTempDir().appendingPathComponent("b.m4a")
                try Data("track-two".utf8).write(to: tmp)
                return (tmp, 200, "audio/mp4")
            }
        )

        let manager = DownloadManager(cache: cache)
        let parentDir = makeTempDir()
        let job = manager.startPlaylistExport(
            root: root,
            tracks: [t1, t2],
            parentURL: parentDir,
            folderName: "BG Export",
            addToLibrary: false,
            library: nil
        )
        XCTAssertTrue(manager.hasActiveDownloads)

        for _ in 0..<40 {
            if job.state != .running { break }
            try await Task.sleep(for: .milliseconds(30))
        }
        XCTAssertEqual(job.state, .completed)
        XCTAssertEqual(job.completedTracks, 2)
        XCTAssertEqual(job.totalTracks, 2)
        XCTAssertEqual(job.overallFraction, 1.0)
        XCTAssertFalse(manager.hasActiveDownloads)
        XCTAssertTrue(FileManager.default.fileExists(atPath: parentDir.appendingPathComponent("BG Export/Song One.m4a").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: parentDir.appendingPathComponent("BG Export/Song Two.m4a").path))

        // Cancellation test with a slow downloader
        let slowCache = CloudAudioCache(
            directory: makeTempDir(),
            maxBytes: 10_000_000,
            resolver: resolver,
            downloader: { _, _ in
                try await Task.sleep(for: .seconds(10))
                let tmp = makeTempDir().appendingPathComponent("slow.m4a")
                try Data("slow".utf8).write(to: tmp)
                return (tmp, 200, "audio/mp4")
            }
        )
        let cancelManager = DownloadManager(cache: slowCache)
        let slowJob = cancelManager.startPlaylistExport(
            root: root,
            tracks: [t1],
            parentURL: makeTempDir(),
            folderName: "Slow Export",
            addToLibrary: false,
            library: nil
        )
        try await Task.sleep(for: .milliseconds(30))
        cancelManager.cancel(jobID: slowJob.id)
        XCTAssertEqual(slowJob.state, .cancelled)
        XCTAssertFalse(cancelManager.hasActiveDownloads)
    }
}
