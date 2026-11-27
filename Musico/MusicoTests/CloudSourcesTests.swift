import XCTest
import CryptoKit
@testable import Musico

private final class MockCloudTransport: CloudHTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var _requests: [URLRequest] = []
    private let handler: @Sendable (URLRequest) throws -> HTTPResponseData

    var requests: [URLRequest] { lock.withLock { _requests } }

    init(handler: @escaping @Sendable (URLRequest) throws -> HTTPResponseData) {
        self.handler = handler
    }

    func send(_ request: URLRequest) async throws -> HTTPResponseData {
        lock.withLock { _requests.append(request) }
        return try handler(request)
    }
}

private struct MockStreamResolver: CloudStreamResolving {
    let handler: @Sendable (Track, LibraryRoot?, Bool) async throws -> CloudStreamResult

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        try await handler(track, root, forceRefresh)
    }
}

private final class RecordingCloudFetcher: CloudPlaylistFetching, @unchecked Sendable {
    private let lock = NSLock()
    private var _recordedSource: String?
    var recordedSource: String? { lock.withLock { _recordedSource } }

    let result: CloudPlaylistResult

    init(result: CloudPlaylistResult) {
        self.result = result
    }

    func fetchPlaylist(sourceURL: String, cookie: String?, kindHint: LibraryRoot.Kind?) async throws -> CloudPlaylistResult {
        lock.withLock { _recordedSource = sourceURL }
        return result
    }
}

final class CloudSourcesTests: XCTestCase {

    // MARK: - Backwards-Compatible Codable

    func testLegacyLibraryRootAndTrackJSONDecodeWithNilCloudFields() throws {
        let rootJSON = """
        {
          "id": "11111111-1111-1111-1111-111111111111",
          "name": "Music",
          "kind": "picked"
        }
        """
        let root = try JSONDecoder().decode(LibraryRoot.self, from: Data(rootJSON.utf8))
        XCTAssertEqual(root.kind, .picked)
        XCTAssertTrue(root.kind.isLocal)
        XCTAssertFalse(root.kind.isCloud)
        XCTAssertNil(root.sourceURL)
        XCTAssertNil(root.remoteID)
        XCTAssertNil(root.cookie)
        XCTAssertNil(root.proxyURL)
        XCTAssertNil(root.lastSynced)

        let trackJSON = """
        {
          "id": "abc",
          "rootID": "11111111-1111-1111-1111-111111111111",
          "relativePath": "song.mp3",
          "title": "Song",
          "artist": "Artist",
          "album": "Album",
          "albumArtist": "Artist",
          "duration": 123,
          "fileSize": 456,
          "modified": 0,
          "hasEmbeddedLyrics": false,
          "dateAdded": 0
        }
        """
        let track = try JSONDecoder().decode(Track.self, from: Data(trackJSON.utf8))
        XCTAssertFalse(track.isCloud)
        XCTAssertNil(track.cloudSource)
        XCTAssertNil(track.remoteTrackID)
        XCTAssertNil(track.remoteArtworkURL)
    }

    func testCloudKindProperties() {
        XCTAssertTrue(LibraryRoot.Kind.cloudYouTube.isCloud)
        XCTAssertFalse(LibraryRoot.Kind.cloudYouTube.isLocal)
        XCTAssertTrue(LibraryRoot.Kind.cloudBilibili.isCloud)
        XCTAssertFalse(LibraryRoot.Kind.cloudBilibili.isLocal)
        XCTAssertTrue(LibraryRoot.Kind.cloudGoogleDrive.isCloud)
        XCTAssertFalse(LibraryRoot.Kind.cloudGoogleDrive.isLocal)
        XCTAssertFalse(LibraryRoot.Kind.picked.isCloud)
        XCTAssertTrue(LibraryRoot.Kind.picked.isLocal)
        XCTAssertFalse(LibraryRoot.Kind.appFolder.isCloud)
        XCTAssertTrue(LibraryRoot.Kind.appFolder.isLocal)
    }

    // MARK: - Bare App vs Extension Cloud Source Resolution

    func testBareAppRejectsYouTubeAndBilibiliWhileKeepingGoogleDriveAndResolvingViaExtensions() async throws {
        let bareRegistry = CloudProviderRegistry()
        let bareService = LiveCloudService(registry: bareRegistry)

        // Bare app rejects YouTube & Bilibili URLs/IDs, while Google Drive still classifies.
        XCTAssertNil(CloudSourceInput.classify("https://music.youtube.com/playlist?list=PL123456", registry: bareRegistry))
        XCTAssertNil(CloudSourceInput.classify("https://www.youtube.com/watch?v=dQw4w9WgXcQ", registry: bareRegistry))
        XCTAssertNil(CloudSourceInput.classify("https://www.bilibili.com/video/BV1xx411c7mD", registry: bareRegistry))
        XCTAssertNil(CloudSourceInput.classify("https://b23.tv/abcXYZ", registry: bareRegistry))
        XCTAssertNil(CloudSourceInput.classify("PL1234567890ABCDEF", registry: bareRegistry))
        XCTAssertNil(CloudSourceInput.classify("BV1xx411c7mD", registry: bareRegistry))
        XCTAssertEqual(
            CloudSourceInput.classify("https://drive.google.com/drive/folders/1AbCdEfGhIjK", registry: bareRegistry),
            .cloudGoogleDrive
        )

        XCTAssertThrowsError(try LiveCloudService.classifySource("https://music.youtube.com/playlist?list=PL123", registry: bareRegistry))
        XCTAssertThrowsError(try LiveCloudService.classifySource("https://www.bilibili.com/video/BV1xx411c7mD", registry: bareRegistry))
        let driveClassified = try LiveCloudService.classifySource("https://drive.google.com/drive/folders/1AbCdEfGhIjK", registry: bareRegistry)
        XCTAssertEqual(driveClassified.kind, .cloudGoogleDrive)
        XCTAssertEqual(driveClassified.providerID, BuiltInProviderID.googleDrive)

        do {
            _ = try await bareService.fetchPlaylist(sourceURL: "https://music.youtube.com/playlist?list=PL123", cookie: nil)
            XCTFail("Expected bare app to reject YouTube playlist fetch")
        } catch let error as CloudSourceError {
            XCTAssertEqual(error, .unsupportedSource)
        }

        // Legacy roots/tracks resolve to unavailable extension errors when no plugin is installed.
        let legacyYTRoot = LibraryRoot(
            id: UUID(),
            name: "Legacy YT",
            kind: .cloudYouTube,
            bookmark: nil,
            sourceURL: "https://music.youtube.com/playlist?list=PLlegacy",
            remoteID: "PLlegacy"
        )
        let legacyYTTrack = Track(
            id: "yt-legacy",
            rootID: legacyYTRoot.id,
            relativePath: "vid1",
            title: "Legacy YT Track",
            artist: "Art",
            album: "Alb",
            albumArtist: "Art",
            duration: 60,
            fileSize: 0,
            modified: Date(),
            hasEmbeddedLyrics: false,
            dateAdded: Date(),
            cloudSource: "youtube",
            remoteTrackID: "vid1"
        )
        do {
            _ = try await bareService.fetchPlaylist(sourceURL: legacyYTRoot.sourceURL!, cookie: nil, kindHint: .cloudYouTube, providerIDHint: BuiltInProviderID.youtube)
            XCTFail("Expected extensionUnavailable for legacy YT root")
        } catch let error as CloudSourceError {
            XCTAssertEqual(error, .extensionUnavailable(name: "ext.youtube-search"))
        }
        do {
            _ = try await bareService.resolveStream(track: legacyYTTrack, root: legacyYTRoot)
            XCTFail("Expected extensionUnavailable for legacy YT track")
        } catch let error as CloudSourceError {
            XCTAssertEqual(error, .extensionUnavailable(name: "ext.youtube-search"))
        }

        // Installing YouTube & Bilibili JS extensions enables link parsing, fetching, and legacy resolution.
        let extRegistry = CloudProviderRegistry()
        let ytExt = CloudSourceTestExtensionProvider(
            id: "ext.youtube-search",
            displayName: "YouTube Search",
            hosts: ["youtube.com", "youtu.be"],
            barePrefixes: ["PL", "yt:"]
        )
        let biliExt = CloudSourceTestExtensionProvider(
            id: "ext.bilibili-search",
            displayName: "Bilibili Search",
            hosts: ["bilibili.com", "b23.tv"],
            barePrefixes: ["BV", "bili:"]
        )
        extRegistry.registerExtension(ytExt)
        extRegistry.registerExtension(biliExt)
        let extService = LiveCloudService(registry: extRegistry)

        XCTAssertEqual(
            CloudSourceInput.classify("Check out this playlist! https://music.youtube.com/playlist?list=PL1234567890。", registry: extRegistry),
            .cloudExtension
        )
        XCTAssertEqual(
            CloudSourceInput.classify("【歌单】深夜听的歌 https://b23.tv/abc1234", registry: extRegistry),
            .cloudExtension
        )

        let ytClassified = try LiveCloudService.classifySource("https://music.youtube.com/playlist?list=PL123", registry: extRegistry)
        XCTAssertEqual(ytClassified.kind, .cloudExtension)
        XCTAssertEqual(ytClassified.providerID, "ext.youtube-search")

        let legacyFetched = try await extService.fetchPlaylist(
            sourceURL: legacyYTRoot.sourceURL!,
            cookie: nil,
            kindHint: .cloudYouTube,
            providerIDHint: BuiltInProviderID.youtube
        )
        XCTAssertEqual(legacyFetched.providerID, "ext.youtube-search")
        let legacyStream = try await extService.resolveStream(track: legacyYTTrack, root: legacyYTRoot)
        XCTAssertEqual(legacyStream.url.absoluteString, "https://cdn.example/ext.youtube-search/vid1.m4a")
    }

    // MARK: - CloudAudioCache LRU & Fetch

    func testCloudAudioCacheLRUEviction() async throws {
        let dir = makeTempDir()
        let cache = CloudAudioCache(directory: dir, maxBytes: 100)

        let url1 = try await cache.storeData(Data(repeating: 1, count: 60), for: "t1", fileExtension: "m4a")
        // Ensure distinct modification dates so LRU order is deterministic.
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 1000)], ofItemAtPath: url1.path)

        let url2 = try await cache.storeData(Data(repeating: 2, count: 60), for: "t2", fileExtension: "m4a")
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 2000)], ofItemAtPath: url2.path)

        // Adding t2 pushed total to 120 > 100, so t1 (oldest) must have been evicted while t2 remains.
        let cached1 = await cache.cachedURLIfPresent(for: "t1")
        let cached2 = await cache.cachedURLIfPresent(for: "t2")
        let total = await cache.totalCachedBytes()

        XCTAssertNil(cached1)
        XCTAssertNotNil(cached2)
        XCTAssertEqual(total, 60)
    }

    func testCloudAudioCacheDownloadsWithHeadersAndServesFromCache() async throws {
        let dir = makeTempDir()
        let downloadCount = NSLock()
        var downloads = 0

        let resolver = MockStreamResolver { _, _, _ in
            CloudStreamResult(
                url: URL(string: "https://cdn.example/audio.m4a")!,
                headers: ["Referer": "https://www.bilibili.com/"],
                fileExtension: "m4a",
                expiresAt: Date().addingTimeInterval(600)
            )
        }

        let cache = CloudAudioCache(
            directory: dir,
            maxBytes: 10_000,
            resolver: resolver,
            downloader: { url, headers in
                XCTAssertEqual(url.absoluteString, "https://cdn.example/audio.m4a")
                XCTAssertEqual(headers["Referer"], "https://www.bilibili.com/")
                downloadCount.withLock { downloads += 1 }
                let tmp = makeTempDir().appendingPathComponent("dl.bin")
                try Data(repeating: 0xAB, count: 32).write(to: tmp)
                return (tmp, 200, "audio/mp4")
            }
        )

        let track = Track(
            id: "cloud-track-1",
            rootID: UUID(),
            relativePath: "BV1",
            title: "Cloud Song",
            artist: "Artist",
            album: "Album",
            albumArtist: "Artist",
            duration: 10,
            fileSize: 0,
            modified: Date(),
            hasEmbeddedLyrics: false,
            dateAdded: Date(),
            cloudSource: "bilibili",
            remoteTrackID: "BV1#1"
        )

        let first = try await cache.resolveAndCache(track: track, root: nil)
        let second = try await cache.resolveAndCache(track: track, root: nil)
        XCTAssertEqual(first, second)
        XCTAssertEqual(first.pathExtension, "m4a")
        XCTAssertEqual(downloadCount.withLock { downloads }, 1)
    }

    // MARK: - Google Drive URL Parsing, Share Extraction & ResourceKey

    func testGoogleDriveURLParsingAndShareTextExtraction() {
        XCTAssertEqual(
            GoogleDriveClient.parse("https://drive.google.com/drive/folders/1AbCdEfGhIjK?usp=sharing"),
            .folder(id: "1AbCdEfGhIjK", resourceKey: nil)
        )
        XCTAssertEqual(
            GoogleDriveClient.parse("https://drive.google.com/drive/u/0/folders/1AbCdEfGhIjK?resourcekey=0-rkAlpha123"),
            .folder(id: "1AbCdEfGhIjK", resourceKey: "0-rkAlpha123")
        )
        XCTAssertEqual(
            GoogleDriveClient.parse("https://drive.google.com/drive/mobile/folders/1MobileFolderId"),
            .folder(id: "1MobileFolderId", resourceKey: nil)
        )
        XCTAssertEqual(
            GoogleDriveClient.parse("https://drive.google.com/embeddedfolderview?id=1EmbFolderId#list"),
            .folder(id: "1EmbFolderId", resourceKey: nil)
        )
        XCTAssertEqual(
            GoogleDriveClient.parse("https://drive.google.com/folderview?id=1ViewFolderId&resourcekey=0-rkView"),
            .folder(id: "1ViewFolderId", resourceKey: "0-rkView")
        )
        XCTAssertEqual(
            GoogleDriveClient.parse("https://drive.google.com/file/d/1AudioFileId/view?usp=drive_link"),
            .file(id: "1AudioFileId", resourceKey: nil)
        )
        XCTAssertEqual(
            GoogleDriveClient.parse("https://drive.google.com/u/1/file/d/1AudioFileId/preview?resourcekey=0-rkFile"),
            .file(id: "1AudioFileId", resourceKey: "0-rkFile")
        )
        XCTAssertEqual(
            GoogleDriveClient.parse("https://drive.google.com/file/d/1AudioFileId/edit"),
            .file(id: "1AudioFileId", resourceKey: nil)
        )
        XCTAssertEqual(
            GoogleDriveClient.parse("https://drive.google.com/uc?id=1UcFileId&export=download"),
            .file(id: "1UcFileId", resourceKey: nil)
        )
        XCTAssertEqual(
            GoogleDriveClient.parse("https://drive.usercontent.google.com/download?id=1UserContentId&export=download&resourcekey=0-rkUc"),
            .file(id: "1UserContentId", resourceKey: "0-rkUc")
        )
        XCTAssertEqual(
            GoogleDriveClient.parse("https://drive.google.com/open?id=1OpenTargetId&resourcekey=0-rkOpen"),
            .open(id: "1OpenTargetId", resourceKey: "0-rkOpen")
        )

        let shareText = "专辑分享【无损音乐】https://drive.google.com/drive/folders/1ShareFld?usp=sharing&resourcekey=0-rkShare。"
        XCTAssertEqual(
            CloudSourceInput.extract(from: shareText),
            "https://drive.google.com/drive/folders/1ShareFld?usp=sharing&resourcekey=0-rkShare"
        )
        XCTAssertEqual(CloudSourceInput.classify(shareText), .cloudGoogleDrive)
        XCTAssertEqual(
            GoogleDriveClient.parse(shareText),
            .folder(id: "1ShareFld", resourceKey: "0-rkShare")
        )
        XCTAssertNil(GoogleDriveClient.parse("https://example.com/drive/folders/1AbCdEfGhIjK"))
    }

    // MARK: - Google Drive Folder Listing, Recursion & Fallback

    func testGoogleDriveFolderIVDParsingRecursionAndFiltering() async throws {
        func jsEscapedIVD(_ rawJSON: String) -> String {
            rawJSON
                .replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\x22")
                .replacingOccurrences(of: "[", with: "\\x5b")
                .replacingOccurrences(of: "]", with: "\\x5d")
        }

        let rootIVD = jsEscapedIVD("""
        [[
          ["file_root_mp3",["fld_root"],"01 - Intro.mp3","audio/mpeg"],
          ["file_pdf_ignored",["fld_root"],"booklet.pdf","application/pdf"],
          ["file_noext_mime",["fld_root"],"Hidden Track","audio/flac", "0-rkHiddenFlac"],
          ["fld_sub",["fld_root"],"Disc 2","application/vnd.google-apps.folder"]
        ]]
        """)
        let subIVD = jsEscapedIVD("""
        [[
          ["file_sub_wav",["fld_sub"],"02 - Bonus.wav","audio/wav"],
          ["fld_root",["fld_sub"],"Back Cycle","application/vnd.google-apps.folder"]
        ]]
        """)

        let transport = MockCloudTransport { request in
            let url = request.url!.absoluteString
            if url.contains("/drive/folders/fld_root") {
                XCTAssertTrue(url.contains("resourcekey=0-rkRoot"))
                let html = """
                <html><head><title>OST Collection - Google Drive</title></head>
                <body><script>window['_DRIVE_ivd'] = '\(rootIVD)';</script></body></html>
                """
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "text/html"], data: Data(html.utf8), finalURL: request.url)
            }
            if url.contains("/drive/folders/fld_sub") {
                let html = """
                <html><head><title>Disc 2 - Google Drive</title></head>
                <body><script>window._DRIVE_ivd = '\(subIVD)';</script></body></html>
                """
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "text/html"], data: Data(html.utf8), finalURL: request.url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: request.url)
        }

        let service = LiveCloudService(transport: transport)
        let result = try await service.fetchPlaylist(
            sourceURL: "https://drive.google.com/drive/folders/fld_root?resourcekey=0-rkRoot",
            cookie: nil
        )

        XCTAssertEqual(result.kind, .cloudGoogleDrive)
        XCTAssertEqual(result.remoteID, "fld_root")
        XCTAssertEqual(result.title, "OST Collection")
        XCTAssertEqual(result.tracks.count, 3)

        XCTAssertEqual(result.tracks[0].remoteTrackID, "file_root_mp3")
        XCTAssertEqual(result.tracks[0].relativePath, "01 - Intro.mp3")
        XCTAssertEqual(result.tracks[0].title, "01 - Intro")
        XCTAssertEqual(result.tracks[0].album, "OST Collection")
        XCTAssertEqual(result.tracks[0].artist, "Google Drive")

        XCTAssertEqual(result.tracks[1].remoteTrackID, "file_noext_mime#0-rkHiddenFlac")
        XCTAssertEqual(result.tracks[1].relativePath, "Hidden Track.flac")
        XCTAssertEqual(result.tracks[1].title, "Hidden Track")
        XCTAssertEqual(result.tracks[1].album, "OST Collection")

        XCTAssertEqual(result.tracks[2].remoteTrackID, "file_sub_wav")
        XCTAssertEqual(result.tracks[2].relativePath, "Disc 2/02 - Bonus.wav")
        XCTAssertEqual(result.tracks[2].title, "02 - Bonus")
        XCTAssertEqual(result.tracks[2].album, "Disc 2")

        // Verify LibraryStore hierarchical wiring (albumArtist = "Google Drive", subfolder hierarchy preserved)
        let state = makeTempDir()
        let store = await MainActor.run {
            LibraryStore(
                directory: state,
                documentsURL: makeTempDir(),
                reader: StubReader(),
                artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")),
                cloudFetcher: service,
                artworkDownloader: { _ in nil }
            )
        }
        try await store.addCloudRoot(sourceURL: "https://drive.google.com/drive/folders/fld_root?resourcekey=0-rkRoot")
        let root = try await MainActor.run { try XCTUnwrap(store.roots.last) }
        XCTAssertEqual(root.kind, .cloudGoogleDrive)

        for _ in 0..<200 {
            let syncing = await MainActor.run { store.cloudSyncProgress[root.id] != nil }
            if !syncing { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }

        let rootContents = await MainActor.run { store.folderContents(root: root.id, path: "") }
        XCTAssertEqual(rootContents.folders, ["Disc 2"])
        XCTAssertEqual(rootContents.tracks.map(\.relativePath), ["01 - Intro.mp3", "Hidden Track.flac"])

        let subContents = await MainActor.run { store.folderContents(root: root.id, path: "Disc 2") }
        XCTAssertTrue(subContents.folders.isEmpty)
        XCTAssertEqual(subContents.tracks.map(\.relativePath), ["Disc 2/02 - Bonus.wav"])

        let allTracks = await MainActor.run { store.tracks }
        XCTAssertEqual(allTracks.count, 3)
        XCTAssertTrue(allTracks.allSatisfy { $0.cloudSource == "gdrive" && $0.albumArtist == "Google Drive" })
    }

    func testGoogleDriveEmbeddedFolderViewFallbackAndHTMLEntities() async throws {
        let transport = MockCloudTransport { request in
            let url = request.url!.absoluteString
            if url.contains("/drive/folders/fld_emb") {
                let shellHTML = "<html><head><title>Jazz &amp; Blues - Google 云端硬盘</title></head><body></body></html>"
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "text/html"], data: Data(shellHTML.utf8), finalURL: request.url)
            }
            if url.contains("/embeddedfolderview") && url.contains("id=fld_emb") {
                let embHTML = """
                <html><body>
                  <div class="flip-entry" id="entry-f1">
                    <a href="https://drive.google.com/file/d/file_aiff/view?usp=drive_web&amp;resourcekey=0-rkAiff">
                      <div class="flip-entry-title">Miles&#39; Tune &amp; Co.aiff</div>
                    </a>
                  </div>
                  <div class="flip-entry" id="entry-img">
                    <a href="https://drive.google.com/file/d/file_cover/view">
                      <div class="flip-entry-title">cover.jpg</div>
                    </a>
                  </div>
                </body></html>
                """
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "text/html"], data: Data(embHTML.utf8), finalURL: request.url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: request.url)
        }

        let client = GoogleDriveClient(transport: transport)
        let result = try await client.fetchPlaylist(sourceURL: "https://drive.google.com/drive/folders/fld_emb", cookie: nil)

        XCTAssertEqual(result.title, "Jazz & Blues")
        XCTAssertEqual(result.tracks.count, 1)
        XCTAssertEqual(result.tracks[0].remoteTrackID, "file_aiff#0-rkAiff")
        XCTAssertEqual(result.tracks[0].relativePath, "Miles' Tune & Co.aiff")
        XCTAssertEqual(result.tracks[0].title, "Miles' Tune & Co")
        XCTAssertEqual(result.tracks[0].album, "Jazz & Blues")
    }

    func testGoogleDriveSingleFileAndOpenAutoDetection() async throws {
        let transport = MockCloudTransport { request in
            let url = request.url!.absoluteString
            if url.contains("/file/d/singleFlac/view") {
                let html = """
                <html><head><meta property="og:title" content="03 - Sunrise.flac - Google Drive"></head></html>
                """
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "text/html"], data: Data(html.utf8), finalURL: request.url)
            }
            if url.contains("/open?id=openFile1") {
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "text/html"],
                    data: Data("<html><title>Preview</title></html>".utf8),
                    finalURL: URL(string: "https://drive.google.com/file/d/openFile1/view")!
                )
            }
            if url.contains("/file/d/openFile1/view") {
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "text/html"],
                    data: Data("<html><title>Open Track.m4a - Google Drive</title></html>".utf8),
                    finalURL: request.url
                )
            }
            if url.contains("/file/d/nonAudioPdf/view") {
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "text/html"],
                    data: Data("<html><title>Manual.pdf - Google Drive</title></html>".utf8),
                    finalURL: request.url
                )
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: request.url)
        }

        let client = GoogleDriveClient(transport: transport)
        let single = try await client.fetchPlaylist(sourceURL: "https://drive.google.com/file/d/singleFlac/view", cookie: nil)
        XCTAssertEqual(single.kind, .cloudGoogleDrive)
        XCTAssertEqual(single.remoteID, "singleFlac")
        XCTAssertEqual(single.title, "03 - Sunrise")
        XCTAssertEqual(single.tracks.count, 1)
        XCTAssertEqual(single.tracks[0].relativePath, "03 - Sunrise.flac")

        let opened = try await client.fetchPlaylist(sourceURL: "https://drive.google.com/open?id=openFile1", cookie: nil)
        XCTAssertEqual(opened.tracks.first?.relativePath, "Open Track.m4a")
        XCTAssertEqual(opened.tracks.first?.title, "Open Track")

        do {
            _ = try await client.fetchPlaylist(sourceURL: "https://drive.google.com/file/d/nonAudioPdf/view", cookie: nil)
            XCTFail("Expected non-audio error")
        } catch let error as CloudSourceError {
            guard case .apiError = error else {
                return XCTFail("Expected apiError, got \(error)")
            }
        }
    }

    func testGoogleDriveStreamResolutionConfirmFormAndAccessErrors() async throws {
        // 1. Virus-scan HTML form with uuid + confirm + resourcekey
        let confirmHTML = """
        <html><body>
          <form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
            <input type="hidden" name="id" value="bigFlacId">
            <input type="hidden" name="export" value="download">
            <input type="hidden" name="confirm" value="t">
            <input type="hidden" name="uuid" value="uuid-9988-7766">
            <input type="hidden" name="at" value="tokenXYZ">
            <input type="hidden" name="resourcekey" value="0-rkBig">
          </form>
        </body></html>
        """

        let transport = MockCloudTransport { request in
            let url = request.url!.absoluteString
            if url.contains("id=bigFlacId") && !url.contains("uuid=uuid-9988-7766") {
                XCTAssertEqual(request.value(forHTTPHeaderField: "Range"), "bytes=0-0")
                XCTAssertEqual(request.value(forHTTPHeaderField: "Cookie"), "SID=testcookie")
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "text/html; charset=utf-8"],
                    data: Data(confirmHTML.utf8),
                    finalURL: request.url
                )
            }
            if url.contains("id=bigFlacId") && url.contains("uuid=uuid-9988-7766") {
                XCTAssertTrue(url.contains("at=tokenXYZ"))
                XCTAssertTrue(url.contains("resourcekey=0-rkBig"))
                return HTTPResponseData(
                    statusCode: 206,
                    headers: [
                        "Content-Type": "audio/flac",
                        "Content-Disposition": "attachment;filename*=UTF-8''Big%20Symphony.flac"
                    ],
                    data: Data([0x66, 0x4C]),
                    finalURL: request.url
                )
            }
            if url.contains("id=privateFile") {
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "text/html"],
                    data: Data("<html><title>Sign in - Google Accounts</title></html>".utf8),
                    finalURL: URL(string: "https://accounts.google.com/ServiceLogin")!
                )
            }
            return HTTPResponseData(statusCode: 403, headers: [:], data: Data(), finalURL: request.url)
        }

        let service = LiveCloudService(transport: transport)
        let track = Track(
            id: "gdrive-1",
            rootID: UUID(),
            relativePath: "gdrive/bigFlacId.flac",
            title: "Big Symphony",
            artist: "Google Drive",
            album: "Symphonies",
            albumArtist: "Google Drive",
            duration: 0,
            fileSize: 0,
            modified: Date(),
            hasEmbeddedLyrics: false,
            dateAdded: Date(),
            cloudSource: "gdrive",
            remoteTrackID: "bigFlacId#0-rkBig"
        )
        let root = LibraryRoot(id: track.rootID, name: "Symphonies", kind: .cloudGoogleDrive, bookmark: nil, cookie: "SID=testcookie")

        let stream = try await service.resolveStream(track: track, root: root, forceRefresh: false)
        XCTAssertTrue(stream.url.absoluteString.contains("uuid=uuid-9988-7766"))
        XCTAssertEqual(stream.fileExtension, "flac")
        XCTAssertEqual(stream.headers["Cookie"], "SID=testcookie")

        // Cached on second call
        let before = transport.requests.count
        _ = try await service.resolveStream(track: track, root: root, forceRefresh: false)
        XCTAssertEqual(transport.requests.count, before)

        // Sign-in redirect produces clear share error
        let privateTrack = Track(
            id: "gdrive-priv",
            rootID: root.id,
            relativePath: "gdrive/privateFile.mp3",
            title: "Private",
            artist: "Google Drive",
            album: "Private",
            albumArtist: "Google Drive",
            duration: 0,
            fileSize: 0,
            modified: Date(),
            hasEmbeddedLyrics: false,
            dateAdded: Date(),
            cloudSource: "gdrive",
            remoteTrackID: "privateFile"
        )
        do {
            _ = try await service.resolveStream(track: privateTrack, root: root, forceRefresh: true)
            XCTFail("Expected access denied error")
        } catch let error as CloudSourceError {
            guard case .apiError(let msg) = error else {
                return XCTFail("Expected apiError, got \(error)")
            }
            XCTAssertTrue(msg.contains("Anyone with the link"))
        }
    }

    // MARK: - Google Drive Pagination, Retries, Sanitization, Hierarchy & Progressive Loading

    func testGoogleDriveLimitsExtensionsAndLegacyFlatCompatibility() async throws {
        XCTAssertEqual(GoogleDriveClient.maxDepth, 12)
        XCTAssertEqual(GoogleDriveClient.maxFolders, 2000)
        for ext in ["mp4", "ogg", "opus", "webm", "wma", "mp3", "flac", "m4a", "wav"] {
            XCTAssertTrue(GoogleDriveClient.supportedAudioExtensions.contains(ext), "Missing extension: \(ext)")
        }

        // Legacy persisted Drive tracks ("gdrive/...") still render flat at root before rescan migration.
        let state = makeTempDir()
        let rootID = UUID()
        let legacyJSON: [String: Any] = [
            "roots": [
                [
                    "id": rootID.uuidString,
                    "name": "Legacy Drive",
                    "kind": "cloudGoogleDrive",
                    "sourceURL": "https://drive.google.com/drive/folders/fld_legacy",
                    "remoteID": "fld_legacy"
                ]
            ],
            "tracks": [
                [
                    "id": Track.makeID(rootID: rootID, relativePath: "gdrive/f1.mp3"),
                    "rootID": rootID.uuidString,
                    "relativePath": "gdrive/f1.mp3",
                    "title": "Legacy One",
                    "artist": "Google Drive",
                    "album": "Legacy Drive",
                    "albumArtist": "Google Drive",
                    "duration": 0,
                    "fileSize": 0,
                    "modified": 0,
                    "hasEmbeddedLyrics": false,
                    "dateAdded": 0,
                    "cloudSource": "gdrive",
                    "remoteTrackID": "f1"
                ],
                [
                    "id": Track.makeID(rootID: rootID, relativePath: "gdrive/f2.opus"),
                    "rootID": rootID.uuidString,
                    "relativePath": "gdrive/f2.opus",
                    "title": "Legacy Opus",
                    "artist": "Google Drive",
                    "album": "Legacy Drive",
                    "albumArtist": "Google Drive",
                    "duration": 0,
                    "fileSize": 0,
                    "modified": 0,
                    "hasEmbeddedLyrics": false,
                    "dateAdded": 0,
                    "cloudSource": "gdrive",
                    "remoteTrackID": "f2"
                ]
            ]
        ]
        let libData = try JSONSerialization.data(withJSONObject: legacyJSON)
        try libData.write(to: state.appendingPathComponent("library.json"))

        let store = await MainActor.run {
            LibraryStore(
                directory: state,
                documentsURL: makeTempDir(),
                reader: StubReader(),
                artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork"))
            )
        }
        let flatContents = await MainActor.run { store.folderContents(root: rootID, path: "") }
        XCTAssertTrue(flatContents.folders.isEmpty)
        XCTAssertEqual(flatContents.tracks.count, 2)
    }

    func testGoogleDriveIVDPaginationV2BetaV3AndEmbeddedFallbackAndEmptyIVDSkip() async throws {
        let apiKey = "AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz0123456"
        XCTAssertEqual(GoogleDriveClient.extractDriveApiKey(from: #"var cfg={"key":"\#(apiKey)"};"#), apiKey)
        XCTAssertNil(GoogleDriveClient.extractDriveApiKey(from: "no key here"))

        let jsEscape: @Sendable (String) -> String = { s in
            s.replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\x22")
        }

        // 1. Empty _DRIVE_ivd ([[], null]) must NOT trigger embeddedfolderview fallback.
        let emptyIVDHTML = "<html><title>Empty - Google Drive</title><script>window['_DRIVE_ivd'] = '\(jsEscape("[[], null]"))';</script></html>"
        let emptyParsed = GoogleDriveClient.parseDriveIVDWithPagination(from: emptyIVDHTML)
        XCTAssertNotNil(emptyParsed)
        XCTAssertEqual(emptyParsed?.items, [])
        XCTAssertNil(emptyParsed?.nextPageToken)

        let emptyTransport = MockCloudTransport { req in
            let url = req.url!.absoluteString
            if url.contains("/drive/folders/fld_empty") {
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "text/html"], data: Data(emptyIVDHTML.utf8), finalURL: req.url)
            }
            XCTFail("Unexpected fallback request for valid empty IVD: \(url)")
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: req.url)
        }
        let emptyBatch = try await GoogleDriveClient(transport: emptyTransport, retryDelaysNanoseconds: [1_000_000])
            .fetchSingleFolder(folderID: "fld_empty")
        XCTAssertTrue(emptyBatch.tracks.isEmpty)
        XCTAssertTrue(emptyBatch.discoveredSubfolders.isEmpty)
        XCTAssertEqual(emptyTransport.requests.count, 1)

        // 2. Paginated folder using v2beta -> v3 fallback across pages.
        let page0JSON = """
        [[["f_p0",["fld_pag"],"01 - Page0.mp3","audio/mpeg"]],"tok_page_1"]
        """
        let page0HTML = """
        <html><head><title>Big Folder - Google Drive</title></head>
        <body><script>var k="\(apiKey)"; window['_DRIVE_ivd'] = '\(jsEscape(page0JSON))';</script></body></html>
        """
        let parsedPage0 = GoogleDriveClient.parseDriveIVDWithPagination(from: page0HTML)
        XCTAssertEqual(parsedPage0?.nextPageToken, "tok_page_1")
        XCTAssertEqual(parsedPage0?.items.count, 1)

        let transport = MockCloudTransport { req in
            let url = req.url!.absoluteString
            if url.contains("/drive/folders/fld_pag") {
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "text/html"], data: Data(page0HTML.utf8), finalURL: req.url)
            }
            if url.contains("clients6.google.com/drive/v2beta/files") {
                XCTAssertEqual(req.value(forHTTPHeaderField: "Referer"), "https://drive.google.com/")
                XCTAssertEqual(req.value(forHTTPHeaderField: "Origin"), "https://drive.google.com")
                XCTAssertEqual(req.value(forHTTPHeaderField: "X-Goog-Api-Key"), apiKey)
                XCTAssertEqual(req.value(forHTTPHeaderField: "X-Goog-Drive-Resource-Keys"), "fld_pag/0-rkPag")
                XCTAssertEqual(req.value(forHTTPHeaderField: "Cookie"), "SID=abc")
                if url.contains("pageToken=tok_page_1") {
                    let body: [String: Any] = [
                        "nextPageToken": "tok_page_2",
                        "items": [
                            ["id": "f_p1", "title": "02 - Page1.opus", "mimeType": "audio/opus", "labels": ["trashed": false]],
                            ["id": "f_trashed", "title": "Deleted.mp3", "mimeType": "audio/mpeg", "labels": ["trashed": true]]
                        ]
                    ]
                    return HTTPResponseData(statusCode: 200, headers: [:], data: try JSONSerialization.data(withJSONObject: body), finalURL: req.url)
                }
                // Simulate v2beta failure on page 2 -> triggers v3 fallback
                return HTTPResponseData(statusCode: 403, headers: [:], data: Data(), finalURL: req.url)
            }
            if url.contains("www.googleapis.com/drive/v3/files") {
                XCTAssertTrue(url.contains("pageToken=tok_page_2"))
                XCTAssertTrue(url.contains("key=\(apiKey)"))
                let body: [String: Any] = [
                    "files": [
                        ["id": "f_p2", "name": "03 - Page2.wma", "mimeType": "audio/x-ms-wma"]
                    ]
                ]
                return HTTPResponseData(statusCode: 200, headers: [:], data: try JSONSerialization.data(withJSONObject: body), finalURL: req.url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: req.url)
        }

        let client = GoogleDriveClient(transport: transport, retryDelaysNanoseconds: [1_000_000])
        let res = try await client.fetchPlaylist(
            sourceURL: "https://drive.google.com/drive/folders/fld_pag?resourcekey=0-rkPag",
            cookie: "SID=abc"
        )
        XCTAssertEqual(res.tracks.map(\.relativePath), ["01 - Page0.mp3", "02 - Page1.opus", "03 - Page2.wma"])

        // 3. When both v2beta & v3 fail, fallback to embeddedfolderview merged by id.
        let embFallbackTransport = MockCloudTransport { req in
            let url = req.url!.absoluteString
            if url.contains("/drive/folders/fld_fb") {
                let html = "<html><title>FB - Google Drive</title><script>var k='\(apiKey)'; window['_DRIVE_ivd']='\(jsEscape(page0JSON))';</script></html>"
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(html.utf8), finalURL: req.url)
            }
            if url.contains("v2beta/files") || url.contains("v3/files") {
                return HTTPResponseData(statusCode: 403, headers: [:], data: Data(), finalURL: req.url)
            }
            if url.contains("/embeddedfolderview") {
                let emb = """
                <html><body>
                  <div class="flip-entry"><a href="https://drive.google.com/file/d/f_p0/view"><div class="flip-entry-title">01 - Page0.mp3</div></a></div>
                  <div class="flip-entry"><a href="https://drive.google.com/file/d/f_emb2/view"><div class="flip-entry-title">04 - Embedded.webm</div></a></div>
                </body></html>
                """
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(emb.utf8), finalURL: req.url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: req.url)
        }
        let fbRes = try await GoogleDriveClient(transport: embFallbackTransport, retryDelaysNanoseconds: [1_000_000])
            .fetchPlaylist(sourceURL: "https://drive.google.com/drive/folders/fld_fb", cookie: nil)
        XCTAssertEqual(fbRes.tracks.map(\.relativePath), ["01 - Page0.mp3", "04 - Embedded.webm"])
    }

    func testGoogleDriveRetries429And5xxAndSanitizesDuplicatesAndDeepDepth() async throws {
        let subAttempts = SendableValueBox(0)

        let htmlFor: @Sendable (String, [[Any]]) throws -> String = { title, entries in
            let data = try JSONSerialization.data(withJSONObject: [entries, NSNull()])
            let json = String(data: data, encoding: .utf8)!
            return "<html><head><title>\(title) - Google Drive</title></head><body><script>window['_DRIVE_ivd']=\(json);</script></body></html>"
        }

        let transport = MockCloudTransport { req in
            let url = req.url!.absoluteString
            if url.contains("/drive/folders/fld_root") {
                let html = try htmlFor("Root", [
                    ["f_dup1", ["fld_root"], "Song/One.mp3", "audio/mpeg"],
                    ["f_dup2", ["fld_root"], "Song\\One.mp3", "audio/mpeg"],
                    ["fld_d1", ["fld_root"], "Artist/Band", "application/vnd.google-apps.folder"]
                ])
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(html.utf8), finalURL: req.url)
            }
            if url.contains("/drive/folders/fld_d1") {
                let attempt = subAttempts.withLock { count -> Int in count += 1; return count }
                if attempt == 1 {
                    return HTTPResponseData(statusCode: 429, headers: [:], data: Data(), finalURL: req.url)
                }
                if attempt == 2 {
                    return HTTPResponseData(statusCode: 503, headers: [:], data: Data(), finalURL: req.url)
                }
                let html = try htmlFor("Artist/Band", [
                    ["fld_d2", ["fld_d1"], "L2", "application/vnd.google-apps.folder"]
                ])
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(html.utf8), finalURL: req.url)
            }
            for depth in 2...6 {
                if url.contains("/drive/folders/fld_d\(depth)") {
                    if depth < 6 {
                        let html = try htmlFor("L\(depth)", [
                            ["fld_d\(depth + 1)", ["fld_d\(depth)"], "L\(depth + 1)", "application/vnd.google-apps.folder"]
                        ])
                        return HTTPResponseData(statusCode: 200, headers: [:], data: Data(html.utf8), finalURL: req.url)
                    } else {
                        let html = try htmlFor("L6", [
                            ["f_deep", ["fld_d6"], "Deep Song.opus", "audio/opus"]
                        ])
                        return HTTPResponseData(statusCode: 200, headers: [:], data: Data(html.utf8), finalURL: req.url)
                    }
                }
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: req.url)
        }

        let client = GoogleDriveClient(transport: transport, retryDelaysNanoseconds: [1_000_000, 2_000_000])
        let res = try await client.fetchPlaylist(sourceURL: "https://drive.google.com/drive/folders/fld_root", cookie: nil)

        XCTAssertEqual(subAttempts.value, 3)
        let paths = Set(res.tracks.map(\.relativePath))
        XCTAssertEqual(paths, [
            "Song-One.mp3",
            "Song-One (f_dup2).mp3",
            "Artist-Band/L2/L3/L4/L5/L6/Deep Song.opus"
        ])
    }

    func testGoogleDriveProgressiveAddPriorityMigrationAndConditionalPruning() async throws {
        let htmlFor: @Sendable (String, [[Any]]) throws -> String = { title, entries in
            let data = try JSONSerialization.data(withJSONObject: [entries, NSNull()])
            let json = String(data: data, encoding: .utf8)!
            return "<html><head><title>\(title) - Google Drive</title></head><body><script>window['_DRIVE_ivd']=\(json);</script></body></html>"
        }

        let phaseBox = SendableValueBox(1) // 1 = initial progressive, 2 = rescan with error (no prune), 3 = rescan clean (prune deleted)

        let transport = MockCloudTransport { req in
            let url = req.url!.absoluteString
            let currentPhase = phaseBox.value

            if url.contains("/drive/folders/fld_prog") {
                if currentPhase == 3 {
                    let html = try htmlFor("Prog Drive", [
                        ["f_keep", ["fld_prog"], "Keep.mp3", "audio/mpeg"]
                    ])
                    return HTTPResponseData(statusCode: 200, headers: [:], data: Data(html.utf8), finalURL: req.url)
                }
                let html = try htmlFor("Prog Drive", [
                    ["f_keep", ["fld_prog"], "Keep.mp3", "audio/mpeg"],
                    ["fld_alpha", ["fld_prog"], "Alpha", "application/vnd.google-apps.folder"],
                    ["fld_zeta", ["fld_prog"], "Zeta", "application/vnd.google-apps.folder"]
                ])
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(html.utf8), finalURL: req.url)
            }
            if url.contains("/drive/folders/fld_alpha") {
                if currentPhase == 2 {
                    return HTTPResponseData(statusCode: 500, headers: [:], data: Data(), finalURL: req.url)
                }
                let html = try htmlFor("Alpha", [
                    ["f_alpha", ["fld_alpha"], "AlphaSong.mp3", "audio/mpeg"]
                ])
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(html.utf8), finalURL: req.url)
            }
            if url.contains("/drive/folders/fld_zeta") {
                let html = try htmlFor("Zeta", [
                    ["f_zeta", ["fld_zeta"], "ZetaSong.mp3", "audio/mpeg"]
                ])
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(html.utf8), finalURL: req.url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: req.url)
        }

        let service = LiveCloudService(transport: transport, driveRetryDelaysNanoseconds: [1_000_000])
        let state = makeTempDir()
        let store = await MainActor.run {
            LibraryStore(
                directory: state,
                documentsURL: makeTempDir(),
                reader: StubReader(),
                artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")),
                cloudFetcher: service,
                artworkDownloader: { _ in nil }
            )
        }

        try await store.addCloudRoot(sourceURL: "https://drive.google.com/drive/folders/fld_prog")
        let root = try await MainActor.run { try XCTUnwrap(store.roots.last) }

        // Immediately after addCloudRoot returns, unloaded subfolders are already visible in folderContents!
        let initialContents = await MainActor.run { store.folderContents(root: root.id, path: "") }
        XCTAssertEqual(initialContents.folders, ["Alpha", "Zeta"])
        await MainActor.run { store.prioritizeCloudFolder(rootID: root.id, path: "Zeta") }

        for _ in 0..<200 {
            let done = await MainActor.run { store.cloudSyncProgress[root.id] == nil }
            if done { break }
            try await Task.sleep(nanoseconds: 10_000_000)
        }

        let loadedPaths = await MainActor.run { Set(store.tracks.map(\.relativePath)) }
        XCTAssertEqual(loadedPaths, ["Keep.mp3", "Alpha/AlphaSong.mp3", "Zeta/ZetaSong.mp3"])

        // Phase 2: partial subtree 500 error -> MUST NOT prune existing tracks!
        phaseBox.withLock { $0 = 2 }
        await store.rescan()
        let afterErrorPaths = await MainActor.run { Set(store.tracks.map(\.relativePath)) }
        XCTAssertEqual(afterErrorPaths, ["Keep.mp3", "Alpha/AlphaSong.mp3", "Zeta/ZetaSong.mp3"])

        // Phase 3: clean full crawl without errors -> prunes removed tracks!
        phaseBox.withLock { $0 = 3 }
        await store.rescan()
        let afterCleanPaths = await MainActor.run { Set(store.tracks.map(\.relativePath)) }
        XCTAssertEqual(afterCleanPaths, ["Keep.mp3"])

        // Legacy "gdrive/..." migration preserves playlist & favourite IDs on rescan.
        let migrateState = makeTempDir()
        let migRootID = UUID()
        let oldTrackID = Track.makeID(rootID: migRootID, relativePath: "gdrive/f_keep.mp3")
        // Write legacy library + playlist/favourite and reload
        let legacyLibJSON: [String: Any] = [
            "roots": [[
                "id": migRootID.uuidString,
                "name": "Prog Drive",
                "kind": "cloudGoogleDrive",
                "sourceURL": "https://drive.google.com/drive/folders/fld_prog",
                "remoteID": "fld_prog"
            ]],
            "tracks": [[
                "id": oldTrackID,
                "rootID": migRootID.uuidString,
                "relativePath": "gdrive/f_keep.mp3",
                "title": "Keep",
                "artist": "Google Drive",
                "album": "Prog Drive",
                "albumArtist": "Google Drive",
                "duration": 0,
                "fileSize": 0,
                "modified": 0,
                "hasEmbeddedLyrics": false,
                "dateAdded": 1000,
                "cloudSource": "gdrive",
                "remoteTrackID": "f_keep"
            ]]
        ]
        let legacyPlaylistsJSON: [String: Any] = [
            "playlists": [[
                "id": UUID().uuidString,
                "name": "Drive Favs",
                "trackIDs": [oldTrackID]
            ]],
            "favourites": [oldTrackID]
        ]
        try JSONSerialization.data(withJSONObject: legacyLibJSON).write(to: migrateState.appendingPathComponent("library.json"))
        try JSONSerialization.data(withJSONObject: legacyPlaylistsJSON).write(to: migrateState.appendingPathComponent("playlists.json"))

        let reloadedMig = await MainActor.run {
            LibraryStore(
                directory: migrateState,
                documentsURL: makeTempDir(),
                reader: StubReader(),
                artwork: ArtworkCache(directory: migrateState.appendingPathComponent("Artwork")),
                cloudFetcher: service,
                artworkDownloader: { _ in nil }
            )
        }
        await reloadedMig.rescan()
        let newTrackID = Track.makeID(rootID: migRootID, relativePath: "Keep.mp3")
        await MainActor.run {
            XCTAssertEqual(reloadedMig.tracks.map(\.relativePath), ["Keep.mp3"])
            XCTAssertEqual(reloadedMig.tracks.first?.id, newTrackID)
            XCTAssertEqual(reloadedMig.playlists.first?.trackIDs, [newTrackID])
            XCTAssertTrue(reloadedMig.isFavourite(newTrackID))
            XCTAssertFalse(reloadedMig.isFavourite(oldTrackID))
        }
    }

    func testGoogleDrivePublicServiceLoginHeaderAndXFlacMimeAccepted() async throws {
        let jsEscape: @Sendable (String) -> String = { s in
            s.replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\x22")
        }
        let ivdJSON = """
        [[["f_xflac",["fld_pub"],"Untitled Piece","application/x-flac"]]]
        """
        let publicHTML = """
        <html>
          <head><title>Shared Music - Google Drive</title></head>
          <body>
            <a href="https://accounts.google.com/ServiceLogin?continue=https://drive.google.com/drive/folders/fld_pub">Sign in</a>
            <script>window['_DRIVE_ivd'] = '\(jsEscape(ivdJSON))';</script>
          </body>
        </html>
        """

        let transport = MockCloudTransport { req in
            let url = req.url!.absoluteString
            if url.contains("/drive/folders/fld_pub") {
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "text/html"],
                    data: Data(publicHTML.utf8),
                    finalURL: req.url
                )
            }
            if url.contains("/drive/folders/fld_priv") {
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "text/html"],
                    data: Data("<html><head><title>Sign in - Google Accounts</title></head><body><a href=\"https://accounts.google.com/ServiceLogin?continue=1\">Sign in</a></body></html>".utf8),
                    finalURL: req.url
                )
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: req.url)
        }

        let client = GoogleDriveClient(transport: transport, retryDelaysNanoseconds: [1_000_000])
        let res = try await client.fetchPlaylist(sourceURL: "https://drive.google.com/drive/folders/fld_pub", cookie: nil)
        XCTAssertEqual(res.title, "Shared Music")
        XCTAssertEqual(res.tracks.count, 1)
        XCTAssertEqual(res.tracks[0].relativePath, "Untitled Piece.flac")

        do {
            _ = try await client.fetchPlaylist(sourceURL: "https://drive.google.com/drive/folders/fld_priv", cookie: nil)
            XCTFail("Expected private sign-in HTML without _DRIVE_ivd to fail")
        } catch {
            // Expected
        }
    }

    func testGoogleDriveMultiKeyFallbackCachingAndFiftyItemTruncationPagination() async throws {
        let keyBad400 = "AIzaSyBadFirstPartyKey00000000000000001"
        let keyBad403 = "AIzaSyDeniedSecondKey000000000000000002"
        let keyGood   = "AIzaSyWorkingThirdKey000000000000000003"
        let sampleHTML = """
        <script>var a="\(keyBad400)", b="\(keyBad403)", c="\(keyGood)", dup="\(keyBad400)";</script>
        """
        XCTAssertEqual(
            GoogleDriveClient.extractDriveApiKeys(from: sampleHTML),
            [keyBad400, keyBad403, keyGood]
        )

        let jsEscape: @Sendable (String) -> String = { s in
            s.replacingOccurrences(of: "\\", with: "\\\\")
                .replacingOccurrences(of: "\"", with: "\\x22")
        }
        let fiftyRows = (1...50).map { i in
            "[\"f_r_\(i)\",[\"fld_50\"],\"Song \(i).mp3\",\"audio/mpeg\"]"
        }.joined(separator: ",")
        let rootHTML = """
        <html><head><title>Large Root - Google Drive</title></head><body>
        \(sampleHTML)
        <script>window['_DRIVE_ivd'] = '\(jsEscape("[[\(fiftyRows)],null]"))';</script>
        </body></html>
        """
        let subRows = (1...50).map { i in
            "[\"f_s_\(i)\",[\"fld_sub50\"],\"Sub \(i).flac\",\"application/x-flac\"]"
        }.joined(separator: ",")
        let subHTML = """
        <html><head><title>Sub - Google Drive</title></head><body>
        <script>var a="\(keyBad400)", c="\(keyGood)"; window['_DRIVE_ivd'] = '\(jsEscape("[[\(subRows)],null]"))';</script>
        </body></html>
        """

        let requestedApiKeys = SendableValueBox<[String]>([])
        let transport = MockCloudTransport { req in
            let url = req.url!.absoluteString
            if url.contains("/drive/folders/fld_50") {
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "text/html"], data: Data(rootHTML.utf8), finalURL: req.url)
            }
            if url.contains("/drive/folders/fld_sub50") {
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "text/html"], data: Data(subHTML.utf8), finalURL: req.url)
            }
            if url.contains("clients6.google.com/drive/v2beta/files") {
                let key = req.value(forHTTPHeaderField: "X-Goog-Api-Key") ?? ""
                requestedApiKeys.withLock { $0.append(key) }
                if key == keyBad400 {
                    return HTTPResponseData(statusCode: 400, headers: [:], data: Data(), finalURL: req.url)
                }
                if key == keyBad403 {
                    return HTTPResponseData(statusCode: 403, headers: [:], data: Data(), finalURL: req.url)
                }
                if url.contains("fld_50") {
                    if !url.contains("pageToken=") {
                        let page1Items = (1...50).map { i in
                            ["id": "f_r_\(i)", "title": "Song \(i).mp3", "mimeType": "audio/mpeg"]
                        } + [["id": "f_r_51", "title": "Song 51.mp3", "mimeType": "audio/mpeg"]]
                        let body: [String: Any] = ["nextPageToken": "tok_p2", "items": page1Items]
                        return HTTPResponseData(statusCode: 200, headers: [:], data: try JSONSerialization.data(withJSONObject: body), finalURL: req.url)
                    }
                    XCTAssertTrue(url.contains("pageToken=tok_p2"))
                    let body: [String: Any] = [
                        "items": [
                            ["id": "f_r_52", "title": "Song 52.mp3", "mimeType": "audio/mpeg"]
                        ]
                    ]
                    return HTTPResponseData(statusCode: 200, headers: [:], data: try JSONSerialization.data(withJSONObject: body), finalURL: req.url)
                }
                if url.contains("fld_sub50") {
                    XCTAssertFalse(url.contains("pageToken="))
                    let subItems: [[String: Any]] = (1...51).map { i in
                        ["id": "f_s_\(i)", "title": "Sub \(i).flac", "mimeType": "application/x-flac"]
                    }
                    let body: [String: Any] = ["items": subItems]
                    return HTTPResponseData(statusCode: 200, headers: [:], data: try JSONSerialization.data(withJSONObject: body), finalURL: req.url)
                }
            }
            if url.contains("googleapis.com/drive/v3/files") {
                return HTTPResponseData(statusCode: 403, headers: [:], data: Data(), finalURL: req.url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: req.url)
        }

        let client = GoogleDriveClient(transport: transport, retryDelaysNanoseconds: [1_000_000])
        let rootRes = try await client.fetchPlaylist(sourceURL: "https://drive.google.com/drive/folders/fld_50", cookie: nil)
        XCTAssertEqual(rootRes.tracks.count, 52)
        XCTAssertTrue(rootRes.tracks.contains { $0.remoteTrackID == "f_r_52" })

        let countBeforeSub = requestedApiKeys.value.count
        let subRes = try await client.fetchPlaylist(sourceURL: "https://drive.google.com/drive/folders/fld_sub50", cookie: nil)
        XCTAssertEqual(subRes.tracks.count, 51)
        XCTAssertEqual(requestedApiKeys.value.dropFirst(countBeforeSub).first, keyGood)

        // Fallback to embeddedfolderview when 50-item IVD folder has no working API key.
        let fallbackTransport = MockCloudTransport { req in
            let url = req.url!.absoluteString
            if url.contains("/drive/folders/fld_emb50") {
                let html = "<html><title>Emb50 - Google Drive</title><script>var k='\(keyBad403)'; window['_DRIVE_ivd']='\(jsEscape("[[\(fiftyRows)],null]"))';</script></html>"
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(html.utf8), finalURL: req.url)
            }
            if url.contains("v2beta/files") || url.contains("v3/files") {
                return HTTPResponseData(statusCode: 403, headers: [:], data: Data(), finalURL: req.url)
            }
            if url.contains("/embeddedfolderview") {
                let emb = """
                <html><body>
                  <div class="flip-entry"><a href="https://drive.google.com/file/d/f_r_1/view"><div class="flip-entry-title">Song 1.mp3</div></a></div>
                  <div class="flip-entry"><a href="https://drive.google.com/file/d/f_r_53/view"><div class="flip-entry-title">Song 53.mp3</div></a></div>
                </body></html>
                """
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(emb.utf8), finalURL: req.url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: req.url)
        }
        let fbClient = GoogleDriveClient(transport: fallbackTransport, retryDelaysNanoseconds: [1_000_000])
        let fbRes = try await fbClient.fetchPlaylist(sourceURL: "https://drive.google.com/drive/folders/fld_emb50", cookie: nil)
        XCTAssertEqual(fbRes.tracks.count, 51)
        XCTAssertTrue(fbRes.tracks.contains { $0.remoteTrackID == "f_r_53" })
    }

    func testCloudAudioCacheQualityPartitioningEvictionAndDownloadPurpose() async throws {
        let dir = makeTempDir()
        let recordedCalls = SendableValueBox<[(purpose: CloudStreamPurpose, quality: String?)]>([])

        let resolver = QualityAwareMockResolver { _, _, purpose, quality, _ in
            recordedCalls.withLock { $0.append((purpose, quality)) }
            let ext = (quality == "highest") ? "flac" : "m4a"
            return CloudStreamResult(
                url: URL(string: "https://cdn.example/\(quality ?? "default").\(ext)")!,
                headers: [:],
                fileExtension: ext,
                expiresAt: Date().addingTimeInterval(600),
                actualQuality: quality,
                qualityLabel: (quality == "highest") ? "FLAC" : "132k"
            )
        }

        let cache = CloudAudioCache(
            directory: dir,
            maxBytes: 100_000,
            resolver: resolver,
            downloader: { url, _ in
                let tmp = makeTempDir().appendingPathComponent("dl.bin")
                try Data(url.absoluteString.utf8).write(to: tmp)
                return (tmp, 200, "audio/mp4")
            }
        )

        let rootID = UUID()
        let root = LibraryRoot(
            id: rootID,
            name: "Bili Mix",
            kind: .cloudBilibili,
            bookmark: nil,
            sourceURL: "https://www.bilibili.com/video/BV1xx411c7mD",
            remoteID: "BV1xx411c7mD",
            streamQualityID: "standard",
            downloadQualityID: "highest"
        )
        let track = Track(
            id: "track-partition-1",
            rootID: rootID,
            relativePath: "BV1",
            title: "Partition Song",
            artist: "Art",
            album: "Alb",
            albumArtist: "Art",
            duration: 120,
            fileSize: 0,
            modified: Date(),
            hasEmbeddedLyrics: false,
            dateAdded: Date(),
            cloudSource: "bilibili",
            remoteTrackID: "BV1#1"
        )

        // Stream uses standard -> <trackID>__standard.m4a
        let stdURL = try await cache.resolveAndCache(track: track, root: root, purpose: .stream)
        XCTAssertEqual(stdURL.lastPathComponent, "track-partition-1__standard.m4a")
        XCTAssertTrue(cache.hasCachedFileSync(for: track.id))
        let stdHit = await cache.cachedURLIfPresent(for: track.id, qualityID: "standard")
        XCTAssertEqual(stdHit, stdURL)
        let highMiss = await cache.cachedURLIfPresent(for: track.id, qualityID: "highest")
        XCTAssertNil(highMiss)

        // Switching to highest downloads <trackID>__highest.flac and evicts <trackID>__standard.m4a
        let exportFolder = makeTempDir()
        let exportedURL = try await CloudExporter.exportTrack(
            track,
            root: root,
            toFolder: exportFolder,
            cache: cache
        )
        XCTAssertEqual(exportedURL.pathExtension, "flac")
        let stdAfterSwitch = await cache.cachedURLIfPresent(for: track.id, qualityID: "standard")
        let highAfterSwitch = await cache.cachedURLIfPresent(for: track.id, qualityID: "highest")
        XCTAssertNil(stdAfterSwitch)
        XCTAssertEqual(highAfterSwitch?.lastPathComponent, "track-partition-1__highest.flac")

        XCTAssertEqual(recordedCalls.value.count, 2)
        XCTAssertEqual(recordedCalls.value[0].purpose, .stream)
        XCTAssertEqual(recordedCalls.value[0].quality, "standard")
        XCTAssertEqual(recordedCalls.value[1].purpose, .download)
        XCTAssertEqual(recordedCalls.value[1].quality, "highest")
    }
}

private struct QualityAwareMockResolver: CloudStreamResolving {
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

private final class SendableValueBox<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: Value

    init(_ initial: Value) {
        self.storage = initial
    }

    var value: Value { lock.withLock { storage } }

    @discardableResult
    func withLock<T>(_ body: (inout Value) -> T) -> T {
        lock.withLock { body(&storage) }
    }
}

private struct CloudSourceTestExtensionProvider: CloudSourceProvider {
    let id: String
    let displayName: String
    let hosts: [String]
    let barePrefixes: [String]
    var badgeLabel: String { displayName }
    var rootKind: LibraryRoot.Kind { .cloudExtension }
    var trackCloudSource: String { id }
    var supportsTree: Bool { false }
    var supportsCookie: Bool { true }

    func canHandle(rawText: String, extractedURL: String) -> CloudMatchResult? {
        let candidate = extractedURL.isEmpty ? rawText : extractedURL
        let lower = candidate.lowercased()
        let matchesHost = hosts.contains { lower.contains($0) }
        let matchesPrefix = barePrefixes.contains { candidate.hasPrefix($0) }
        guard matchesHost || matchesPrefix else { return nil }
        return CloudMatchResult(
            providerID: id,
            kind: .cloudExtension,
            extractedURL: candidate,
            normalizedURL: candidate,
            remoteID: "remote-\(id)",
            itemKind: "playlist"
        )
    }

    func fetchCollection(sourceURL: String, cookie: String?) async throws -> CloudPlaylistResult {
        CloudPlaylistResult(
            kind: .cloudExtension,
            remoteID: "remote-\(id)",
            title: displayName,
            tracks: [
                CloudTrackInfo(
                    remoteTrackID: "vid1",
                    relativePath: "vid1",
                    title: "Track 1",
                    artist: "Artist",
                    album: displayName,
                    trackNumber: 1,
                    duration: 120,
                    artworkURL: nil
                )
            ],
            providerID: id
        )
    }

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        CloudStreamResult(
            url: URL(string: "https://cdn.example/\(id)/\(track.remoteTrackID ?? "x").m4a")!,
            headers: [:],
            fileExtension: "m4a",
            expiresAt: Date().addingTimeInterval(600)
        )
    }
}
