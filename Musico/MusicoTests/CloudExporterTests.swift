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

        let files = try FileManager.default.contentsOfDirectory(atPath: summary.destinationURL.path)
            .filter { $0 != CloudFolderManifest.fileName }
            .sorted()
        XCTAssertEqual(files, ["Track - A (2).m4a", "Track - A.m4a", "Track B.m4a"])
        XCTAssertEqual(try Data(contentsOf: summary.destinationURL.appendingPathComponent("Track - A.m4a")), Data("cached-v1".utf8))

        let manifest = try XCTUnwrap(CloudExporter.loadManifest(in: summary.destinationURL))
        XCTAssertEqual(manifest.sourceURL, root.sourceURL)
        XCTAssertEqual(manifest.remoteID, root.remoteID)
        XCTAssertEqual(manifest.cloudKind, .cloudYouTube)
        XCTAssertEqual(manifest.tracks["v1"], "Track - A.m4a")
        XCTAssertEqual(manifest.tracks["v2"], "Track - A (2).m4a")
        XCTAssertEqual(manifest.tracks["v3"], "Track B.m4a")

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
        let files = try FileManager.default.contentsOfDirectory(atPath: summary.destinationURL.path)
            .filter { $0 != CloudFolderManifest.fileName }
            .sorted()
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

    @MainActor
    func testUpdateLocalFolderDownloadsOnlyNewAndMissingAndLibraryStoreAutoTracksManifest() async throws {
        let root = makeCloudRoot()
        let t1 = makeCloudTrack(rootID: root.id, remoteID: "v1", title: "Song One")
        let t2 = makeCloudTrack(rootID: root.id, remoteID: "v2", title: "Song Two")

        let cacheDir = makeTempDir()
        let downloads = LockBox<[String]>([])
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
                downloads.withLock { $0.append(url.lastPathComponent) }
                let tmp = makeTempDir().appendingPathComponent("dl.m4a")
                try Data("audio-\(url.lastPathComponent)".utf8).write(to: tmp)
                return (tmp, 200, "audio/mp4")
            }
        )

        let parentDir = makeTempDir()
        let initial = try await CloudExporter.exportPlaylist(
            root: root,
            tracks: [t1, t2],
            toParentFolder: parentDir,
            folderName: "Tracked Mix",
            cache: cache
        )
        let savedFolder = initial.destinationURL

        // Delete Song Two on disk and wipe cache for v1+v2 to verify v1 is skipped while deleted v2 & new v3 are fetched.
        try FileManager.default.removeItem(at: savedFolder.appendingPathComponent("Song Two.m4a"))
        await cache.remove(trackID: t1.id)
        await cache.remove(trackID: t2.id)
        downloads.withLock { $0.removeAll() }

        struct StubFetcher: CloudPlaylistFetching {
            let result: CloudPlaylistResult
            func fetchPlaylist(sourceURL: String, cookie: String?, kindHint: LibraryRoot.Kind?) async throws -> CloudPlaylistResult {
                result
            }
        }
        let fetcher = StubFetcher(result: CloudPlaylistResult(
            kind: .cloudYouTube,
            remoteID: "PLexport",
            title: "Tracked Mix",
            tracks: [
                CloudTrackInfo(remoteTrackID: "v1", relativePath: "v1", title: "Song One", artist: "Artist", album: "Album", trackNumber: 1, duration: 100, artworkURL: nil),
                CloudTrackInfo(remoteTrackID: "v2", relativePath: "v2", title: "Song Two", artist: "Artist", album: "Album", trackNumber: 2, duration: 100, artworkURL: nil),
                CloudTrackInfo(remoteTrackID: "v3", relativePath: "v3", title: "Song Three", artist: "Artist", album: "Album", trackNumber: 3, duration: 100, artworkURL: nil),
            ]
        ))

        let stateDir = makeTempDir()
        let docsDir = makeTempDir()
        let store = LibraryStore(
            directory: stateDir,
            documentsURL: docsDir,
            reader: StubReader(),
            artwork: ArtworkCache(directory: stateDir.appendingPathComponent("Artwork")),
            cloudFetcher: fetcher
        )
        try await store.addRoot(url: savedFolder)
        let pickedRoot = try XCTUnwrap(store.roots.first { $0.kind == .picked })
        XCTAssertTrue(pickedRoot.isCloudTrackedLocal)
        XCTAssertEqual(pickedRoot.linkedCloudKind, .cloudYouTube)
        XCTAssertEqual(pickedRoot.sourceURL, root.sourceURL)

        let update = try await store.updateTrackedLocalRoot(pickedRoot.id, cache: cache)
        XCTAssertEqual(update.addedCount, 2)
        XCTAssertEqual(update.skippedCount, 1)
        XCTAssertEqual(update.failedCount, 0)
        XCTAssertEqual(update.totalRemoteCount, 3)
        XCTAssertEqual(downloads.value.sorted(), ["v2.m4a", "v3.m4a"])

        let updatedManifest = try XCTUnwrap(CloudExporter.loadManifest(in: savedFolder))
        XCTAssertEqual(Set(updatedManifest.tracks.keys), ["v1", "v2", "v3"])
        XCTAssertTrue(FileManager.default.fileExists(atPath: savedFolder.appendingPathComponent("Song Three.m4a").path))
    }

    @MainActor
    func testExportAndIncrementalUpdateForGoogleDrive() async throws {
        let driveRoot = LibraryRoot(
            id: UUID(),
            name: "Drive Audio",
            kind: .cloudGoogleDrive,
            bookmark: nil,
            sourceURL: "https://drive.google.com/drive/folders/fld_drive_1?resourcekey=0-rk",
            remoteID: "fld_drive_1"
        )
        let t1 = Track(
            id: Track.makeID(rootID: driveRoot.id, relativePath: "gdrive/f1.flac"),
            rootID: driveRoot.id,
            relativePath: "gdrive/f1.flac",
            title: "Lossless One",
            artist: "Google Drive",
            album: "Drive Audio",
            albumArtist: "Google Drive",
            trackNumber: nil,
            discNumber: nil,
            duration: 0,
            fileSize: 0,
            modified: Date(timeIntervalSince1970: 0),
            hasEmbeddedLyrics: false,
            dateAdded: Date(timeIntervalSince1970: 0),
            artworkKey: nil,
            cloudSource: "gdrive",
            remoteTrackID: "f1#0-rk",
            remoteArtworkURL: nil
        )

        let downloads = LockBox<[String]>([])
        let resolver = StubCloudStreamResolver { track, _, _ in
            let id = (track.remoteTrackID ?? "x").split(separator: "#").first.map(String.init) ?? "x"
            let ext = id == "f1" ? "flac" : "mp3"
            return CloudStreamResult(
                url: URL(string: "https://drive.usercontent.google.com/download/\(id).\(ext)")!,
                headers: [:],
                fileExtension: ext,
                expiresAt: Date().addingTimeInterval(600)
            )
        }
        let cache = CloudAudioCache(
            directory: makeTempDir(),
            maxBytes: 50_000_000,
            resolver: resolver,
            downloader: { url, _ in
                downloads.withLock { $0.append(url.lastPathComponent) }
                let tmp = makeTempDir().appendingPathComponent("dl.bin")
                try Data("audio-\(url.lastPathComponent)".utf8).write(to: tmp)
                return (tmp, 200, "audio/flac")
            }
        )

        let parentDir = makeTempDir()
        let summary = try await CloudExporter.exportPlaylist(
            root: driveRoot,
            tracks: [t1],
            toParentFolder: parentDir,
            folderName: "Drive Export",
            cache: cache
        )
        XCTAssertEqual(summary.savedCount, 1)
        XCTAssertEqual(summary.failedCount, 0)
        let exportedFile = summary.destinationURL.appendingPathComponent("Lossless One.flac")
        XCTAssertTrue(FileManager.default.fileExists(atPath: exportedFile.path))

        let manifest = try XCTUnwrap(CloudExporter.loadManifest(in: summary.destinationURL))
        XCTAssertEqual(manifest.cloudKind, .cloudGoogleDrive)
        XCTAssertEqual(manifest.tracks["f1#0-rk"], "Lossless One.flac")

        // Incremental update adds a new mp3 track while skipping existing f1#0-rk
        struct DriveStubFetcher: CloudPlaylistFetching {
            let result: CloudPlaylistResult
            func fetchPlaylist(sourceURL: String, cookie: String?, kindHint: LibraryRoot.Kind?) async throws -> CloudPlaylistResult {
                result
            }
        }
        let fetcher = DriveStubFetcher(result: CloudPlaylistResult(
            kind: .cloudGoogleDrive,
            remoteID: "fld_drive_1",
            title: "Drive Audio",
            tracks: [
                CloudTrackInfo(remoteTrackID: "f1#0-rk", relativePath: "gdrive/f1.flac", title: "Lossless One", artist: "Google Drive", album: "Drive Audio", trackNumber: nil, duration: 0, artworkURL: nil),
                CloudTrackInfo(remoteTrackID: "f2", relativePath: "gdrive/f2.mp3", title: "New Track", artist: "Google Drive", album: "Drive Audio", trackNumber: nil, duration: 0, artworkURL: nil)
            ]
        ))

        let stateDir = makeTempDir()
        let store = LibraryStore(
            directory: stateDir,
            documentsURL: makeTempDir(),
            reader: StubReader(),
            artwork: ArtworkCache(directory: stateDir.appendingPathComponent("Artwork")),
            cloudFetcher: fetcher
        )
        try await store.addRoot(url: summary.destinationURL)
        let pickedRoot = try XCTUnwrap(store.roots.first { $0.kind == .picked })
        XCTAssertEqual(pickedRoot.linkedCloudKind, .cloudGoogleDrive)

        downloads.withLock { $0.removeAll() }
        let update = try await store.updateTrackedLocalRoot(pickedRoot.id, cache: cache)
        XCTAssertEqual(update.addedCount, 1)
        XCTAssertEqual(update.skippedCount, 1)
        XCTAssertEqual(update.failedCount, 0)
        XCTAssertEqual(downloads.value, ["f2.mp3"])
        XCTAssertTrue(FileManager.default.fileExists(atPath: summary.destinationURL.appendingPathComponent("New Track.mp3").path))
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
