import Foundation
import Network
import XCTest
@testable import Musico

private final class MockRegistryTransport: CloudHTTPTransport, @unchecked Sendable {
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

private final class LocalRedirectHTTPServer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.musico.test-redirect-server")
    private var listener: NWListener?
    private let lock = NSLock()
    private var activeConnections: [NWConnection] = []
    private var port: UInt16 = 0

    init() throws {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        self.listener = try NWListener(using: params, on: .any)
    }

    func start() async throws -> URL {
        guard let listener else { throw URLError(.cannotOpenFile) }
        return try await withCheckedThrowingContinuation { continuation in
            let flagLock = NSLock()
            nonisolated(unsafe) var resumed = false
            listener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                switch state {
                case .ready:
                    let shouldResume = flagLock.withLock {
                        let first = !resumed
                        resumed = true
                        return first
                    }
                    if shouldResume {
                        let p = listener.port?.rawValue ?? 0
                        self.lock.withLock { self.port = p }
                        continuation.resume(returning: URL(string: "http://127.0.0.1:\(p)")!)
                    }
                case .failed(let error):
                    let shouldResume = flagLock.withLock {
                        let first = !resumed
                        resumed = true
                        return first
                    }
                    if shouldResume {
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
        for conn in conns { conn.cancel() }
    }

    private func handleConnection(_ conn: NWConnection) {
        lock.withLock { activeConnections.append(conn) }
        conn.start(queue: queue)
        receive(on: conn, buffer: Data())
    }

    private func receive(on conn: NWConnection, buffer: Data) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if error != nil {
                conn.cancel()
                return
            }
            var acc = buffer
            if let data { acc.append(data) }
            if let headerEnd = acc.range(of: Data("\r\n\r\n".utf8)) {
                let raw = String(data: acc[..<headerEnd.lowerBound], encoding: .utf8) ?? ""
                self.respond(to: raw, on: conn)
                return
            }
            if isComplete {
                conn.cancel()
                return
            }
            self.receive(on: conn, buffer: acc)
        }
    }

    private func respond(to rawRequest: String, on conn: NWConnection) {
        let requestLine = rawRequest.components(separatedBy: "\r\n").first ?? ""
        let parts = requestLine.split(separator: " ")
        let path = parts.count >= 2 ? String(parts[1]) : "/"
        let currentPort = lock.withLock { port }

        let responseString: String
        if path == "/start" {
            let location = "http://127.0.0.1:\(currentPort)/dest"
            responseString = "HTTP/1.1 302 Found\r\nLocation: \(location)\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        } else {
            responseString = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 8\r\nConnection: close\r\n\r\nfollowed"
        }
        conn.send(content: Data(responseString.utf8), completion: .contentProcessed { _ in conn.cancel() })
    }
}

final class CloudProviderRegistryTests: XCTestCase {

    func testBackwardsCompatibleV1113JSONDecodingAndProviderIDMapping() throws {
        let v1113LibraryJSON = """
        {
          "roots": [
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "name": "YT Mix",
              "kind": "cloudYouTube",
              "sourceURL": "https://music.youtube.com/playlist?list=PL123",
              "remoteID": "PL123"
            },
            {
              "id": "22222222-2222-2222-2222-222222222222",
              "name": "Bili Mix",
              "kind": "cloudBilibili",
              "sourceURL": "https://www.bilibili.com/video/BV1xx411c7mD",
              "remoteID": "BV1xx411c7mD"
            },
            {
              "id": "33333333-3333-3333-3333-333333333333",
              "name": "Drive Mix",
              "kind": "cloudGoogleDrive",
              "sourceURL": "https://drive.google.com/drive/folders/fld123",
              "remoteID": "fld123"
            },
            {
              "id": "44444444-4444-4444-4444-444444444444",
              "name": "Tracked Local",
              "kind": "picked",
              "sourceURL": "https://music.youtube.com/playlist?list=PL999",
              "remoteID": "PL999",
              "linkedCloudKind": "cloudYouTube",
              "trackedSubfolders": {
                "Sub": {
                  "sourceURL": "https://www.bilibili.com/video/BV1sub",
                  "remoteID": "BV1sub",
                  "cloudKind": "cloudBilibili"
                }
              }
            }
          ],
          "tracks": []
        }
        """

        struct LegacyLib: Codable {
            var roots: [LibraryRoot]
            var tracks: [Track]
        }
        let decoded = try JSONDecoder().decode(LegacyLib.self, from: Data(v1113LibraryJSON.utf8))
        XCTAssertEqual(decoded.roots.count, 4)
        XCTAssertNil(decoded.roots[0].providerID)
        XCTAssertEqual(decoded.roots[0].resolvedProviderID, BuiltInProviderID.youtube)
        XCTAssertEqual(decoded.roots[1].resolvedProviderID, BuiltInProviderID.bilibili)
        XCTAssertEqual(decoded.roots[2].resolvedProviderID, BuiltInProviderID.googleDrive)
        XCTAssertNil(decoded.roots[3].resolvedProviderID)
        XCTAssertEqual(decoded.roots[3].effectiveProviderID, BuiltInProviderID.youtube)
        XCTAssertEqual(decoded.roots[3].normalizedTrackedSubfolders["Sub"]?.resolvedProviderID, BuiltInProviderID.bilibili)

        // CloudFolderManifest from v1.1.13 without providerID decodes cleanly
        let v1113ManifestJSON = """
        {
          "sourceURL": "https://drive.google.com/drive/folders/fld1",
          "remoteID": "fld1",
          "cloudKind": "cloudGoogleDrive",
          "lastSyncedEpochMs": 1700000000000,
          "tracks": {"f1": "01.flac"}
        }
        """
        let manifest = try JSONDecoder().decode(CloudFolderManifest.self, from: Data(v1113ManifestJSON.utf8))
        XCTAssertNil(manifest.providerID)
        XCTAssertEqual(manifest.resolvedProviderID, BuiltInProviderID.googleDrive)
    }

    @MainActor
    func testAppFolderRestorationPreservesLinkedProviderIDAndProviderID() throws {
        let state = makeTempDir()
        let docs = makeTempDir()
        let appRoot = LibraryRoot(
            id: LibraryRoot.appFolderID,
            name: LibraryStore.appFolderName,
            kind: .appFolder,
            bookmark: nil,
            sourceURL: "https://fixture.test/playlist/abc",
            remoteID: "abc",
            linkedCloudKind: .cloudExtension,
            providerID: nil,
            linkedProviderID: "ext.com.example.source"
        )
        struct SavedLibraryFile: Codable {
            var roots: [LibraryRoot]
            var tracks: [Track]
        }
        try Persistence.save(SavedLibraryFile(roots: [appRoot], tracks: []), to: state.appendingPathComponent("library.json"))

        let store = LibraryStore(
            directory: state,
            documentsURL: docs,
            reader: StubReader(),
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork"))
        )
        let restored = try XCTUnwrap(store.root(LibraryRoot.appFolderID))
        XCTAssertEqual(restored.linkedCloudKind, .cloudExtension)
        XCTAssertEqual(restored.linkedProviderID, "ext.com.example.source")
        XCTAssertEqual(restored.effectiveProviderID, "ext.com.example.source")
    }

    func testURLSessionCloudTransportRawRequestDeniesAutomaticRedirects() async throws {
        let server = try LocalRedirectHTTPServer()
        let baseURL = try await server.start()
        defer { server.stop() }

        let startURL = baseURL.appendingPathComponent("start")
        let nextURL = baseURL.appendingPathComponent("dest").absoluteString

        let session = URLSession(configuration: .ephemeral)
        let transport = URLSessionCloudTransport(session: session)

        let raw = try await transport.rawRequest(URLRequest(url: startURL))
        XCTAssertEqual(raw.statusCode, 302)
        XCTAssertEqual(raw.header("location"), nextURL)

        let auto = try await transport.send(URLRequest(url: startURL))
        XCTAssertEqual(auto.statusCode, 200)
        XCTAssertEqual(String(data: auto.data, encoding: .utf8), "followed")
    }

    @MainActor
    func testExtensionDedupeAllowsSameRemoteIDAcrossDistinctProvidersAndPreservesStateWhenDisabledOrMissing() async throws {
        let scriptA = """
        var manifest = {
          apiVersion: 1,
          id: 'com.test.alpha',
          name: 'Alpha Source',
          version: '1.0.0',
          badgeLabel: 'Alpha',
          capabilities: ['playlist', 'stream'],
          urlPatterns: ['^https?://alpha\\\\.test/list/[a-zA-Z0-9_-]+$'],
          permissions: { hosts: ['alpha.test'] }
        };
        function canHandle(input) {
          var m = String(input.extractedUrl || input.rawText || '').match(/\\/list\\/([a-zA-Z0-9_-]+)$/);
          return m ? { matched: true, normalizedUrl: 'https://alpha.test/list/' + m[1], remoteId: m[1], kind: 'playlist' } : { matched: false };
        }
        async function fetchCollection(ctx, req) {
          var h = canHandle({ extractedUrl: req.url });
          return {
            remoteId: h.remoteId,
            title: 'Alpha ' + h.remoteId,
            tracks: [{ remoteTrackId: 'a1', relativePath: 'a1.mp3', title: 'Alpha Track', artist: 'A Artist', album: 'A Album', trackNumber: 1, durationSec: 120 }]
          };
        }
        async function resolveStream(ctx, req) {
          return { url: 'https://alpha.test/audio/' + req.remoteTrackId + '.mp3', fileExtension: 'mp3' };
        }
        module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection, resolveStream: resolveStream };
        """

        let scriptB = """
        var manifest = {
          apiVersion: 1,
          id: 'com.test.beta',
          name: 'Beta Source',
          version: '1.0.0',
          badgeLabel: 'Beta',
          capabilities: ['playlist', 'stream'],
          urlPatterns: ['^https?://beta\\\\.test/list/[a-zA-Z0-9_-]+$'],
          permissions: { hosts: ['beta.test'] }
        };
        function canHandle(input) {
          var m = String(input.extractedUrl || input.rawText || '').match(/\\/list\\/([a-zA-Z0-9_-]+)$/);
          return m ? { matched: true, normalizedUrl: 'https://beta.test/list/' + m[1], remoteId: m[1], kind: 'playlist' } : { matched: false };
        }
        async function fetchCollection(ctx, req) {
          var h = canHandle({ extractedUrl: req.url });
          return {
            remoteId: h.remoteId,
            title: 'Beta ' + h.remoteId,
            tracks: [{ remoteTrackId: 'b1', relativePath: 'b1.mp3', title: 'Beta Track', artist: 'B Artist', album: 'B Album', trackNumber: 1, durationSec: 140 }]
          };
        }
        async function resolveStream(ctx, req) {
          return { url: 'https://beta.test/audio/' + req.remoteTrackId + '.mp3', fileExtension: 'mp3' };
        }
        module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection, resolveStream: resolveStream };
        """

        let transport = MockRegistryTransport { _ in
            HTTPResponseData(statusCode: 200, headers: [:], data: Data(), finalURL: nil)
        }
        let registry = CloudProviderRegistry(transport: transport)
        let extDir = makeTempDir()
        let extStore = ExtensionStore(baseDirectory: extDir, registry: registry, transport: transport)

        let extA = try await extStore.importPackageData(Data(scriptA.utf8), fileName: "alpha.musico.js", sourceURL: nil)
        let extB = try await extStore.importPackageData(Data(scriptB.utf8), fileName: "beta.musico.js", sourceURL: nil)
        XCTAssertFalse(extA.isActive)
        XCTAssertNil(registry.provider(forID: extA.providerID))

        extStore.approvePermissionsAndEnable(id: extA.id)
        extStore.approvePermissionsAndEnable(id: extB.id)
        XCTAssertNotNil(registry.provider(forID: extA.providerID))
        XCTAssertNotNil(registry.provider(forID: extB.providerID))

        let service = LiveCloudService(registry: registry)
        let state = makeTempDir()
        let docs = makeTempDir()
        let store = LibraryStore(
            directory: state,
            documentsURL: docs,
            reader: StubReader(),
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")),
            cloudFetcher: service,
            providerRegistry: registry,
            extensionAvailability: { extStore.availability(forProviderID: $0) },
            artworkDownloader: { _ in nil }
        )

        // Two distinct extensions sharing the exact same remoteID ("shared-123") must both succeed!
        try await store.addCloudRoot(sourceURL: "https://alpha.test/list/shared-123")
        try await store.addCloudRoot(sourceURL: "https://beta.test/list/shared-123")

        let alphaRoot = try XCTUnwrap(store.roots.first { $0.resolvedProviderID == "ext.com.test.alpha" })
        let betaRoot = try XCTUnwrap(store.roots.first { $0.resolvedProviderID == "ext.com.test.beta" })
        XCTAssertEqual(alphaRoot.kind, .cloudExtension)
        XCTAssertEqual(alphaRoot.remoteID, "shared-123")
        XCTAssertEqual(betaRoot.kind, .cloudExtension)
        XCTAssertEqual(betaRoot.remoteID, "shared-123")
        XCTAssertEqual(store.tracks.count, 2)

        // Duplicate same extension + same remoteID rejects with duplicateRoot
        do {
            try await store.addCloudRoot(sourceURL: "https://alpha.test/list/shared-123")
            XCTFail("Expected duplicateRoot")
        } catch LibraryError.duplicateRoot {}

        // Add Alpha's track to a playlist, favourite, play history, and cached audio
        let alphaTrack = try XCTUnwrap(store.tracks.first { $0.rootID == alphaRoot.id })
        XCTAssertEqual(alphaTrack.cloudSource, "ext.com.test.alpha")
        let playlistID = store.createPlaylist(name: "Ext Favs")
        store.add([alphaTrack.id], toPlaylist: playlistID)
        store.toggleFavourite(alphaTrack.id)

        let history = PlayHistoryStore(directory: state)
        history.recordPlay(alphaTrack.id)

        let cache = CloudAudioCache(
            directory: makeTempDir(),
            maxBytes: 10_000_000,
            resolver: service,
            downloader: { _, _ in
                let tmp = makeTempDir().appendingPathComponent("audio.bin")
                try Data("cached-audio-bytes".utf8).write(to: tmp)
                return (tmp, 200, "audio/mpeg")
            }
        )
        let cachedURL = try await cache.resolveAndCache(track: alphaTrack, root: alphaRoot)
        XCTAssertTrue(FileManager.default.fileExists(atPath: cachedURL.path))

        // Export Alpha root to local folder and verify .musico-cloud.json saves providerID
        let exportParent = makeTempDir()
        let exportSummary = try await CloudExporter.exportPlaylist(
            root: alphaRoot,
            tracks: [alphaTrack],
            toParentFolder: exportParent,
            folderName: "Alpha Export",
            cache: cache
        )
        let exportedManifest = try XCTUnwrap(CloudExporter.loadManifest(in: exportSummary.destinationURL))
        XCTAssertEqual(exportedManifest.cloudKind, .cloudExtension)
        XCTAssertEqual(exportedManifest.providerID, "ext.com.test.alpha")

        // Disable Alpha -> non-destructive availability == .disabled, tracks/playlists/stats/cache stay intact across rescan!
        extStore.setEnabled(id: extA.id, enabled: false)
        XCTAssertEqual(store.cloudExtensionAvailability(for: alphaRoot), .disabled(name: "Alpha Source"))
        XCTAssertFalse(store.isAvailable(alphaRoot))
        XCTAssertEqual(store.cloudExtensionAvailability(for: betaRoot), .available)
        XCTAssertTrue(store.isAvailable(betaRoot))

        await store.rescan()
        XCTAssertNotNil(store.track(alphaTrack.id))
        XCTAssertEqual(store.playlists.first?.trackIDs, [alphaTrack.id])
        XCTAssertTrue(store.isFavourite(alphaTrack.id))
        XCTAssertEqual(history.count(for: alphaTrack.id), 1)
        let cachedWhileDisabled = await cache.cachedURLIfPresent(for: alphaTrack.id)
        XCTAssertNotNil(cachedWhileDisabled)

        // Uninstall Alpha -> availability == .missing, still non-destructive across rescan!
        extStore.uninstall(id: extA.id)
        XCTAssertEqual(store.cloudExtensionAvailability(for: alphaRoot), .missing(providerID: "ext.com.test.alpha"))
        await store.rescan()
        XCTAssertNotNil(store.track(alphaTrack.id))
        XCTAssertEqual(store.playlists.first?.trackIDs, [alphaTrack.id])
        XCTAssertTrue(store.isFavourite(alphaTrack.id))
        XCTAssertEqual(history.count(for: alphaTrack.id), 1)
        let cachedWhileMissing = await cache.cachedURLIfPresent(for: alphaTrack.id)
        XCTAssertNotNil(cachedWhileMissing)

        // Re-install & enable Alpha -> becomes available again!
        _ = try await extStore.importPackageData(Data(scriptA.utf8), fileName: "alpha.musico.js", sourceURL: nil)
        extStore.approvePermissionsAndEnable(id: extA.id)
        XCTAssertEqual(store.cloudExtensionAvailability(for: alphaRoot), .available)
        XCTAssertTrue(store.isAvailable(alphaRoot))
    }

    func testBareRegistryExcludesYouTubeAndBilibiliAndResolvesLegacyRootsViaExtensions() async throws {
        let registry = CloudProviderRegistry()
        XCTAssertEqual(registry.allProviders().map(\.id), [BuiltInProviderID.googleDrive])
        XCTAssertNil(registry.provider(forID: BuiltInProviderID.youtube))
        XCTAssertNil(registry.provider(forID: BuiltInProviderID.bilibili))
        XCTAssertNotNil(registry.provider(forID: BuiltInProviderID.googleDrive))

        let ytRoot = LibraryRoot(
            id: UUID(),
            name: "Legacy YT",
            kind: .cloudYouTube,
            bookmark: nil,
            sourceURL: "https://music.youtube.com/playlist?list=PLlegacy",
            remoteID: "PLlegacy"
        )
        let biliRoot = LibraryRoot(
            id: UUID(),
            name: "Legacy Bili",
            kind: .cloudBilibili,
            bookmark: nil,
            sourceURL: "https://www.bilibili.com/video/BV1xx411c7mD",
            remoteID: "BV1xx411c7mD"
        )
        let driveRoot = LibraryRoot(
            id: UUID(),
            name: "Drive",
            kind: .cloudGoogleDrive,
            bookmark: nil,
            sourceURL: "https://drive.google.com/drive/folders/fld1",
            remoteID: "fld1"
        )
        let ytTrack = Track(
            id: "yt-1",
            rootID: ytRoot.id,
            relativePath: "vid1",
            title: "YT",
            artist: "Art",
            album: "Alb",
            albumArtist: "Art",
            duration: 10,
            fileSize: 0,
            modified: Date(),
            hasEmbeddedLyrics: false,
            dateAdded: Date(),
            cloudSource: "youtube",
            remoteTrackID: "vid1"
        )
        let biliTrack = Track(
            id: "bili-1",
            rootID: biliRoot.id,
            relativePath: "BV1xx411c7mD",
            title: "Bili",
            artist: "Art",
            album: "Alb",
            albumArtist: "Art",
            duration: 10,
            fileSize: 0,
            modified: Date(),
            hasEmbeddedLyrics: false,
            dateAdded: Date(),
            cloudSource: "bilibili",
            remoteTrackID: "BV1xx411c7mD#1"
        )

        // Without installed plugins, legacy YouTube/Bilibili resolve to nil while Google Drive still resolves.
        XCTAssertNil(registry.provider(forRoot: ytRoot))
        XCTAssertNil(registry.provider(forRoot: biliRoot))
        XCTAssertNil(registry.provider(forTrack: ytTrack, root: ytRoot))
        XCTAssertNil(registry.provider(forTrack: biliTrack, root: biliRoot))
        XCTAssertNil(registry.providerForKind(.cloudYouTube, providerID: BuiltInProviderID.youtube, sourceURL: ytRoot.sourceURL))
        XCTAssertNil(registry.providerForKind(.cloudBilibili, providerID: BuiltInProviderID.bilibili, sourceURL: biliRoot.sourceURL))
        XCTAssertEqual(registry.provider(forRoot: driveRoot)?.id, BuiltInProviderID.googleDrive)

        // Installing YouTube and Bilibili JS extensions seamlessly backs legacy roots and tracks.
        let ytExt = StubExtensionProvider(
            id: "ext.youtube-search",
            displayName: "YouTube Search",
            urlPrefix: "youtube.com"
        )
        let biliExt = StubExtensionProvider(
            id: "ext.bilibili-search",
            displayName: "Bilibili Search",
            urlPrefix: "bilibili.com"
        )
        registry.registerExtension(ytExt)
        registry.registerExtension(biliExt)

        XCTAssertEqual(registry.provider(forRoot: ytRoot)?.id, "ext.youtube-search")
        XCTAssertEqual(registry.provider(forRoot: biliRoot)?.id, "ext.bilibili-search")
        XCTAssertEqual(registry.provider(forTrack: ytTrack, root: ytRoot)?.id, "ext.youtube-search")
        XCTAssertEqual(registry.provider(forTrack: biliTrack, root: biliRoot)?.id, "ext.bilibili-search")
        XCTAssertEqual(registry.providerForKind(.cloudYouTube, providerID: nil, sourceURL: ytRoot.sourceURL)?.id, "ext.youtube-search")
        XCTAssertEqual(registry.providerForKind(.cloudBilibili, providerID: BuiltInProviderID.bilibili, sourceURL: nil)?.id, "ext.bilibili-search")
    }
}

private struct StubExtensionProvider: CloudSourceProvider {
    let id: String
    let displayName: String
    let urlPrefix: String
    var badgeLabel: String { displayName }
    var rootKind: LibraryRoot.Kind { .cloudExtension }
    var trackCloudSource: String { id }
    var supportsTree: Bool { false }
    var supportsCookie: Bool { true }

    func canHandle(rawText: String, extractedURL: String) -> CloudMatchResult? {
        let candidate = extractedURL.isEmpty ? rawText : extractedURL
        guard candidate.lowercased().contains(urlPrefix) else { return nil }
        return CloudMatchResult(
            providerID: id,
            kind: .cloudExtension,
            extractedURL: candidate,
            normalizedURL: candidate,
            remoteID: "stub",
            itemKind: "playlist"
        )
    }

    func fetchCollection(sourceURL: String, cookie: String?) async throws -> CloudPlaylistResult {
        CloudPlaylistResult(kind: .cloudExtension, remoteID: "stub", title: displayName, tracks: [], providerID: id)
    }

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        CloudStreamResult(
            url: URL(string: "https://cdn.example/\(track.remoteTrackID ?? "x").m4a")!,
            headers: [:],
            fileExtension: "m4a",
            expiresAt: Date().addingTimeInterval(600)
        )
    }
}
