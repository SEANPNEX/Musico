import XCTest
@testable import Musico

private struct StubCloudStreamResolver: CloudStreamResolving {
    let handler: @Sendable (Track, LibraryRoot?, Bool) async throws -> CloudStreamResult

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        try await handler(track, root, forceRefresh)
    }
}

private struct QualityRecordingStreamResolver: CloudStreamResolving {
    let handler: @Sendable (String?) async throws -> CloudStreamResult

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        try await handler(nil)
    }

    func resolveStream(
        track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        qualityID: String?,
        forceRefresh: Bool
    ) async throws -> CloudStreamResult {
        _ = (track, root, purpose, forceRefresh)
        return try await handler(qualityID)
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

    @MainActor
    func testCoverArtResolutionEmbeddingAndWebMSidecarAcrossExportPaths() async throws {
        // 1. Magic-byte MIME detection & generic/provider-configured artwork headers
        let png = Self.makeImageData(color: .systemRed, png: true)
        let jpg = Self.makeImageData(color: .systemBlue, png: false)
        let webpHeader = Data([0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50])
        XCTAssertEqual(CloudThumbnailCache.detectedMIMEType(for: png), "image/png")
        XCTAssertEqual(CloudThumbnailCache.detectedMIMEType(for: jpg), "image/jpeg")
        XCTAssertEqual(CloudThumbnailCache.detectedMIMEType(for: webpHeader), "image/webp")
        var artworkReq = URLRequest(url: URL(string: "https://cdn.example.com/cover.jpg")!)
        CloudProviderRegistry().configureArtworkRequest(&artworkReq)
        XCTAssertEqual(artworkReq.value(forHTTPHeaderField: "User-Agent"), CloudProviderRegistry.defaultUserAgent)
        XCTAssertNil(artworkReq.value(forHTTPHeaderField: "Referer"))

        // 2. Disk ArtworkCache fallback + remote fetch fallback
        let artworkCache = ArtworkCache(directory: makeTempDir())
        let diskArtKey = try XCTUnwrap(artworkCache.store(jpg))
        let remoteURLString = "https://i0.hdslb.com/bfs/archive/remote-cover.png"
        let thumbCache = CloudThumbnailCache(fetcher: { _ in png })

        let root = makeCloudRoot()
        let libraryTrackWithDiskArt = Track(
            id: Track.makeID(rootID: root.id, relativePath: "disk1"),
            rootID: root.id,
            relativePath: "disk1",
            title: "Disk Art MP3",
            artist: "Art",
            album: "Alb",
            albumArtist: "Art",
            trackNumber: nil,
            discNumber: nil,
            duration: 58,
            fileSize: 0,
            modified: Date(timeIntervalSince1970: 0),
            hasEmbeddedLyrics: false,
            dateAdded: Date(timeIntervalSince1970: 0),
            artworkKey: diskArtKey,
            cloudSource: "youtube",
            remoteTrackID: "disk1",
            remoteArtworkURL: nil
        )
        let libraryTrackWithRemoteArt = Track(
            id: Track.makeID(rootID: root.id, relativePath: "remote1"),
            rootID: root.id,
            relativePath: "remote1",
            title: "Remote WebM",
            artist: "Bili",
            album: "BiliAlb",
            albumArtist: "Bili",
            trackNumber: nil,
            discNumber: nil,
            duration: 58,
            fileSize: 0,
            modified: Date(timeIntervalSince1970: 0),
            hasEmbeddedLyrics: false,
            dateAdded: Date(timeIntervalSince1970: 0),
            artworkKey: nil,
            cloudSource: "bilibili",
            remoteTrackID: "remote1",
            remoteArtworkURL: remoteURLString
        )

        let mp3Fixture = try XCTUnwrap(Bundle(for: CloudExporterTests.self).url(forResource: "sample", withExtension: "mp3"))
        let mp3Data = try Data(contentsOf: mp3Fixture)
        let resolver = StubCloudStreamResolver { track, _, _ in
            let isWebm = track.remoteTrackID == "remote1"
            let ext = isWebm ? "webm" : "mp3"
            return CloudStreamResult(
                url: URL(string: "https://cdn.example/\(track.remoteTrackID ?? "x").\(ext)")!,
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
                let tmp = makeTempDir().appendingPathComponent("dl.\(url.pathExtension)")
                if url.pathExtension == "webm" {
                    try Data("webm-bytes".utf8).write(to: tmp)
                    return (tmp, 200, "audio/webm")
                } else {
                    try mp3Data.write(to: tmp)
                    return (tmp, 200, "audio/mpeg")
                }
            }
        )

        // Single-track export for non-ephemeral library track embeds artwork into MP3 (even with artworkData: nil)
        let singleDir = makeTempDir()
        let savedMP3 = try await CloudExporter.exportTrack(
            libraryTrackWithDiskArt,
            root: root,
            toFolder: singleDir,
            cache: cache,
            artworkData: nil,
            artworkCache: artworkCache,
            thumbnailCache: thumbCache
        )
        let mp3Meta = await MetadataReader().read(savedMP3)
        XCTAssertNotNil(mp3Meta.artwork)
        XCTAssertEqual(mp3Meta.title, "Disk Art MP3")

        // Export playlist tags MP3 AND writes sibling .png sidecar for .webm
        let playlistParent = makeTempDir()
        let summary = try await CloudExporter.exportPlaylist(
            root: root,
            tracks: [libraryTrackWithDiskArt, libraryTrackWithRemoteArt],
            toParentFolder: playlistParent,
            folderName: "Cover Export",
            cache: cache,
            artworkCache: artworkCache,
            thumbnailCache: thumbCache
        )
        XCTAssertEqual(summary.savedCount, 2)
        let exportedMP3 = summary.destinationURL.appendingPathComponent("Disk Art MP3.mp3")
        let exportedWebM = summary.destinationURL.appendingPathComponent("Remote WebM.webm")
        let exportedWebMSidecar = summary.destinationURL.appendingPathComponent("Remote WebM.png")
        XCTAssertTrue(FileManager.default.fileExists(atPath: exportedWebM.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: exportedWebMSidecar.path))
        XCTAssertEqual(try Data(contentsOf: exportedWebMSidecar), png)
        let exportedMP3Meta = await MetadataReader().read(exportedMP3)
        XCTAssertNotNil(exportedMP3Meta.artwork)
        let exportedWebMMeta = await MetadataReader().read(exportedWebM)
        XCTAssertEqual(exportedWebMMeta.artwork, png)
    }

    func testExportTrackForwardsQualityIDToCacheAndResolver() async throws {
        let root = makeCloudRoot()
        let track = makeCloudTrack(rootID: root.id, remoteID: "am_1", title: "HiRes Song")
        let recordedQualities = LockBox<[String?]>([])
        let resolver = QualityRecordingStreamResolver { qualityID in
            recordedQualities.withLock { $0.append(qualityID) }
            return CloudStreamResult(
                url: URL(string: "https://cdn.example/\(qualityID ?? "default").m4a")!,
                headers: [:],
                fileExtension: "m4a",
                expiresAt: Date().addingTimeInterval(600),
                actualQuality: qualityID
            )
        }
        let cache = CloudAudioCache(
            directory: makeTempDir(),
            maxBytes: 50_000_000,
            resolver: resolver,
            downloader: { _, _ in
                let tmp = makeTempDir().appendingPathComponent("dl.m4a")
                try Data("alac-audio".utf8).write(to: tmp)
                return (tmp, 200, "audio/mp4")
            }
        )

        let outDir = makeTempDir()
        let savedURL = try await CloudExporter.exportTrack(
            track,
            root: root,
            toFolder: outDir,
            cache: cache,
            qualityID: "alac"
        )

        XCTAssertTrue(FileManager.default.fileExists(atPath: savedURL.path))
        XCTAssertEqual(recordedQualities.value, ["alac"])
    }

    @MainActor
    func testGoogleDriveExportPreservesRealEmbeddedMetadataAndArtwork() async throws {
        let bundle = Bundle(for: CloudExporterTests.self)
        let flacFixture = try XCTUnwrap(bundle.url(forResource: "art", withExtension: "flac"))
        let mp3V24Fixture = try XCTUnwrap(bundle.url(forResource: "art-png-v24", withExtension: "mp3"))
        let m4aFixture = try XCTUnwrap(bundle.url(forResource: "art", withExtension: "m4a"))

        let reader = MetadataReader()
        let origFlacMeta = await reader.read(flacFixture)
        let origMp3Meta = await reader.read(mp3V24Fixture)
        let origM4aMeta = await reader.read(m4aFixture)
        XCTAssertNotNil(origFlacMeta.artwork)
        XCTAssertNotNil(origMp3Meta.artwork)
        XCTAssertNotNil(origM4aMeta.artwork)

        let driveRoot = LibraryRoot(
            id: UUID(),
            name: "Drive Folder",
            kind: .cloudGoogleDrive,
            bookmark: nil,
            sourceURL: "https://drive.google.com/drive/folders/fld_embedded",
            remoteID: "fld_embedded"
        )
        let tFlac = Track(
            id: Track.makeID(rootID: driveRoot.id, relativePath: "01.flac"),
            rootID: driveRoot.id,
            relativePath: "01.flac",
            title: "01",
            artist: "Google Drive",
            album: "Drive Folder",
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
            remoteTrackID: "f_flac",
            remoteArtworkURL: nil
        )
        let tMp3 = Track(
            id: Track.makeID(rootID: driveRoot.id, relativePath: "02.mp3"),
            rootID: driveRoot.id,
            relativePath: "02.mp3",
            title: "02",
            artist: "Google Drive",
            album: "Drive Folder",
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
            remoteTrackID: "f_mp3",
            remoteArtworkURL: nil
        )
        let tM4a = Track(
            id: Track.makeID(rootID: driveRoot.id, relativePath: "03.m4a"),
            rootID: driveRoot.id,
            relativePath: "03.m4a",
            title: "03",
            artist: "Google Drive",
            album: "Drive Folder",
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
            remoteTrackID: "f_m4a",
            remoteArtworkURL: nil
        )

        let resolver = StubCloudStreamResolver { track, _, _ in
            let ext: String
            switch track.remoteTrackID {
            case "f_flac": ext = "flac"
            case "f_mp3": ext = "mp3"
            default: ext = "m4a"
            }
            return CloudStreamResult(
                url: URL(string: "https://drive.usercontent.google.com/download/\(track.remoteTrackID ?? "x").\(ext)")!,
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
                let src: URL
                switch url.pathExtension {
                case "flac": src = flacFixture
                case "mp3": src = mp3V24Fixture
                default: src = m4aFixture
                }
                let tmp = makeTempDir().appendingPathComponent("dl.\(url.pathExtension)")
                try FileManager.default.copyItem(at: src, to: tmp)
                return (tmp, 200, "application/octet-stream")
            }
        )

        let parentDir = makeTempDir()
        let summary = try await CloudExporter.exportPlaylist(
            root: driveRoot,
            tracks: [tFlac, tMp3, tM4a],
            toParentFolder: parentDir,
            folderName: "Drive Embedded",
            cache: cache
        )
        XCTAssertEqual(summary.savedCount, 3)
        XCTAssertEqual(summary.failedCount, 0)

        let outFlacMeta = await reader.read(summary.destinationURL.appendingPathComponent("01.flac"))
        XCTAssertEqual(outFlacMeta.title, origFlacMeta.title)
        XCTAssertEqual(outFlacMeta.artist, origFlacMeta.artist)
        XCTAssertEqual(outFlacMeta.album, origFlacMeta.album)
        XCTAssertEqual(outFlacMeta.artwork, origFlacMeta.artwork)
        XCTAssertNotEqual(outFlacMeta.artist, "Google Drive")

        let outMp3Meta = await reader.read(summary.destinationURL.appendingPathComponent("02.mp3"))
        XCTAssertEqual(outMp3Meta.title, origMp3Meta.title)
        XCTAssertEqual(outMp3Meta.artist, origMp3Meta.artist)
        XCTAssertEqual(outMp3Meta.album, origMp3Meta.album)
        XCTAssertEqual(outMp3Meta.artwork, origMp3Meta.artwork)

        let outM4aMeta = await reader.read(summary.destinationURL.appendingPathComponent("03.m4a"))
        XCTAssertEqual(outM4aMeta.title, origM4aMeta.title)
        XCTAssertEqual(outM4aMeta.artist, origM4aMeta.artist)
        XCTAssertEqual(outM4aMeta.album, origM4aMeta.album)
        XCTAssertEqual(outM4aMeta.artwork, origM4aMeta.artwork)
    }

    func testAudioTagWriterPartialUpdatePreservesCoverArtAcrossFormats() async throws {
        let bundle = Bundle(for: CloudExporterTests.self)
        let reader = MetadataReader()

        for (name, ext) in [("art", "flac"), ("art-v23", "mp3"), ("art-png-v24", "mp3"), ("art", "m4a")] {
            let src = try XCTUnwrap(bundle.url(forResource: name, withExtension: ext))
            let dst = makeTempDir().appendingPathComponent("\(name).\(ext)")
            try FileManager.default.copyItem(at: src, to: dst)

            let before = await reader.read(dst)
            let beforeArt = try XCTUnwrap(before.artwork, "Expected fixture \(name).\(ext) to contain artwork")

            let wrote = await AudioTagWriter.write(
                AudioTagWriter.Payload(artist: "Batch Updated Artist", album: "Batch Updated Album", albumArtist: "Batch Updated Artist"),
                to: dst
            )
            XCTAssertTrue(wrote, "Expected write to succeed for \(name).\(ext)")

            let after = await reader.read(dst)
            XCTAssertEqual(after.artist, "Batch Updated Artist", "Artist mismatch for \(name).\(ext)")
            XCTAssertEqual(after.album, "Batch Updated Album", "Album mismatch for \(name).\(ext)")
            XCTAssertEqual(after.title, before.title, "Title should be preserved for \(name).\(ext)")
            XCTAssertNotNil(after.artwork, "Artwork should be preserved for \(name).\(ext)")
            if ext != "m4a" {
                XCTAssertEqual(after.artwork, beforeArt, "Exact artwork bytes should be preserved for \(name).\(ext)")
            }
        }
    }

    private static func makeImageData(color: UIColor, png: Bool) -> Data {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8))
        if png {
            return renderer.pngData { ctx in
                color.setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
            }
        }
        return renderer.jpegData(withCompressionQuality: 0.9) { ctx in
            color.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }
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
