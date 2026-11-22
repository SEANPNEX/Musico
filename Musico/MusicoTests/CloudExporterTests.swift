import XCTest
@testable import Musico

private struct StubCloudStreamResolver: CloudStreamResolving {
    let handler: @Sendable (Track, LibraryRoot?, Bool) async throws -> CloudStreamResult

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        try await handler(track, root, forceRefresh)
    }
}

final class CloudExporterTests: XCTestCase {
    private func makeCloudRoot(name: String = "Cloud: Mix / 2026") -> LibraryRoot {
        LibraryRoot(
            id: UUID(),
            name: name,
            kind: .cloudYouTube,
            bookmark: nil,
            sourceURL: "https://music.youtube.com/playlist?list=PLexport",
            remoteID: "PLexport"
        )
    }

    private func makeCloudTrack(rootID: UUID, remoteID: String, title: String) -> Track {
        Track(
            id: Track.makeID(rootID: rootID, relativePath: remoteID),
            rootID: rootID,
            relativePath: remoteID,
            title: title,
            artist: "Artist",
            album: "Album",
            albumArtist: "Artist",
            trackNumber: nil,
            discNumber: nil,
            duration: 120,
            fileSize: 0,
            modified: Date(timeIntervalSince1970: 0),
            hasEmbeddedLyrics: false,
            dateAdded: Date(timeIntervalSince1970: 0),
            artworkKey: nil,
            cloudSource: "youtube",
            remoteTrackID: remoteID,
            remoteArtworkURL: nil
        )
    }

    func testSanitizeFileNameRemovesIllegalCharactersAndHandlesFallbacks() {
        XCTAssertEqual(CloudExporter.sanitizeFileName("A/B:C\\D*E?F\"G<H>I|J"), "A-B-C-D-E-F-G-H-I-J")
        XCTAssertEqual(CloudExporter.sanitizeFileName("  ..Hello   World..  "), "Hello World")
        XCTAssertEqual(CloudExporter.sanitizeFileName("   ", fallback: "My Playlist"), "My Playlist")
        XCTAssertEqual(CloudExporter.sanitizeFileName("..", fallback: "Fallback"), "Fallback")
        XCTAssertEqual(CloudExporter.sanitizeFileName(".", fallback: "..."), "Untitled")
    }

    func testUniqueFileURLAppendsCollisionNumbers() throws {
        let dir = makeTempDir()
        let first = CloudExporter.uniqueFileURL(in: dir, baseName: "Song: One", fileExtension: ".M4A")
        XCTAssertEqual(first.lastPathComponent, "Song- One.m4a")
        try Data("a".utf8).write(to: first)

        let second = CloudExporter.uniqueFileURL(in: dir, baseName: "Song: One", fileExtension: "m4a")
        XCTAssertEqual(second.lastPathComponent, "Song- One (2).m4a")
        try Data("b".utf8).write(to: second)

        let third = CloudExporter.uniqueFileURL(in: dir, baseName: "Song: One", fileExtension: "m4a")
        XCTAssertEqual(third.lastPathComponent, "Song- One (3).m4a")
    }

    func testExportPlaylistReusesCacheDownloadsUncachedAndHandlesCollisions() async throws {
        let root = makeCloudRoot()
        let t1 = makeCloudTrack(rootID: root.id, remoteID: "v1", title: "Track / A")
        let t2 = makeCloudTrack(rootID: root.id, remoteID: "v2", title: "Track / A")
        let t3 = makeCloudTrack(rootID: root.id, remoteID: "v3", title: "Track B")

        let cacheDir = makeTempDir()
        let downloadCalls = LockBox<[String]>([])
        let resolver = StubCloudStreamResolver { track, _, _ in
            CloudStreamResult(
                url: URL(string: "https://cdn.example/\(track.remoteTrackID ?? "x").m4a")!,
                headers: [:],
                fileExtension: "m4a",
                expiresAt: Date().addingTimeInterval(600)
            )
        }
        let cache = CloudAudioCache(
            directory: cacheDir,
            maxBytes: 50_000_000,
            resolver: resolver,
            downloader: { url, _ in
                downloadCalls.withLock { $0.append(url.lastPathComponent) }
                let tmp = makeTempDir().appendingPathComponent("dl.m4a")
                try Data("audio-\(url.lastPathComponent)".utf8).write(to: tmp)
                return (tmp, 200, "audio/mp4")
            }
        )
        // Pre-populate t1 in cache to verify cached files are reused without re-downloading.
        try await cache.storeData(Data("cached-v1".utf8), for: t1.id, fileExtension: "m4a")

        let parentDir = makeTempDir()
        let progressEvents = LockBox<[(Int, Int, String)]>([])

        let summary = try await CloudExporter.exportPlaylist(
            root: root,
            tracks: [t1, t2, t3],
            toParentFolder: parentDir,
            folderName: "My: Cloud / Mix",
            cache: cache
        ) { done, total, title in
            progressEvents.withLock { $0.append((done, total, title)) }
        }

        XCTAssertEqual(summary.savedCount, 3)
        XCTAssertEqual(summary.failedCount, 0)
        XCTAssertTrue(summary.errors.isEmpty)
        XCTAssertEqual(summary.destinationURL.lastPathComponent, "My- Cloud - Mix")

        let files = try FileManager.default.contentsOfDirectory(atPath: summary.destinationURL.path).sorted()
        XCTAssertEqual(files, ["Track - A (2).m4a", "Track - A.m4a", "Track B.m4a"])
        XCTAssertEqual(try Data(contentsOf: summary.destinationURL.appendingPathComponent("Track - A.m4a")), Data("cached-v1".utf8))

        // Only v2 and v3 should have been downloaded; v1 came from cache.
        XCTAssertEqual(downloadCalls.value.sorted(), ["v2.m4a", "v3.m4a"])
        XCTAssertEqual(progressEvents.value.last?.0, 3)
        XCTAssertEqual(progressEvents.value.last?.1, 3)
    }

    func testExportPlaylistHandlesPartialFailuresWithoutAborting() async throws {
        let root = makeCloudRoot()
        let t1 = makeCloudTrack(rootID: root.id, remoteID: "ok1", title: "Good Song 1")
        let t2 = makeCloudTrack(rootID: root.id, remoteID: "bad", title: "Broken Song")
        let t3 = makeCloudTrack(rootID: root.id, remoteID: "ok2", title: "Good Song 2")

        let resolver = StubCloudStreamResolver { track, _, _ in
            if track.remoteTrackID == "bad" {
                throw CloudSourceError.noPlayableStream
            }
            return CloudStreamResult(
                url: URL(string: "https://cdn.example/\(track.remoteTrackID ?? "x").m4a")!,
                headers: [:],
                fileExtension: "m4a",
                expiresAt: Date().addingTimeInterval(600)
            )
        }
        let cache = CloudAudioCache(
            directory: makeTempDir(),
            maxBytes: 50_000_000,
            resolver: resolver,
            downloader: { url, _ in
                let tmp = makeTempDir().appendingPathComponent("dl.m4a")
                try Data("audio-\(url.lastPathComponent)".utf8).write(to: tmp)
                return (tmp, 200, "audio/mp4")
            }
        )

        let parentDir = makeTempDir()
        let summary = try await CloudExporter.exportPlaylist(
            root: root,
            tracks: [t1, t2, t3],
            toParentFolder: parentDir,
            folderName: "Partial",
            cache: cache
        )

        XCTAssertEqual(summary.savedCount, 2)
        XCTAssertEqual(summary.failedCount, 1)
        XCTAssertEqual(summary.errors.count, 1)
        XCTAssertTrue(summary.errors[0].contains("Broken Song"))
        let files = try FileManager.default.contentsOfDirectory(atPath: summary.destinationURL.path).sorted()
        XCTAssertEqual(files, ["Good Song 1.m4a", "Good Song 2.m4a"])
    }

    func testExportSingleTrackCreatesCollisionSafeFile() async throws {
        let root = makeCloudRoot()
        let track = makeCloudTrack(rootID: root.id, remoteID: "single1", title: "Solo: Song?")

        let resolver = StubCloudStreamResolver { _, _, _ in
            CloudStreamResult(
                url: URL(string: "https://cdn.example/single.m4a")!,
                headers: [:],
                fileExtension: "m4a",
                expiresAt: Date().addingTimeInterval(600)
            )
        }
        let cache = CloudAudioCache(
            directory: makeTempDir(),
            maxBytes: 50_000_000,
            resolver: resolver,
            downloader: { _, _ in
                let tmp = makeTempDir().appendingPathComponent("single.m4a")
                try Data("solo-audio".utf8).write(to: tmp)
                return (tmp, 200, "audio/mp4")
            }
        )

        let folder = makeTempDir()
        let first = try await CloudExporter.exportTrack(track, root: root, toFolder: folder, cache: cache)
        let second = try await CloudExporter.exportTrack(track, root: root, toFolder: folder, cache: cache)

        XCTAssertEqual(first.lastPathComponent, "Solo- Song-.m4a")
        XCTAssertEqual(second.lastPathComponent, "Solo- Song- (2).m4a")
        XCTAssertEqual(try Data(contentsOf: first), Data("solo-audio".utf8))
        XCTAssertEqual(try Data(contentsOf: second), Data("solo-audio".utf8))
    }

    @MainActor
    func testLibraryStoreContainsLocalURL() throws {
        let state = makeTempDir()
        let docs = makeTempDir()
        let outside = makeTempDir()
        let store = LibraryStore(
            directory: state,
            documentsURL: docs,
            reader: StubReader(),
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork"))
        )

        let insideFolder = docs.appendingPathComponent("SavedPlaylist")
        try FileManager.default.createDirectory(at: insideFolder, withIntermediateDirectories: true)
        XCTAssertTrue(store.containsLocalURL(docs))
        XCTAssertTrue(store.containsLocalURL(insideFolder))
        XCTAssertTrue(store.containsLocalURL(insideFolder.appendingPathComponent("song.m4a")))
        XCTAssertFalse(store.containsLocalURL(outside))
        XCTAssertFalse(store.containsLocalURL(outside.appendingPathComponent("song.m4a")))
    }
}

private final class LockBox<Value>: @unchecked Sendable {
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
