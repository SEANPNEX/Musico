import Foundation
import UIKit
import XCTest
@testable import Musico

private final class SearchFixtureTransport: CloudHTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var _requests: [URLRequest] = []
    private let handler: @Sendable (URLRequest) async throws -> HTTPResponseData

    var requests: [URLRequest] { lock.withLock { _requests } }

    init(handler: @escaping @Sendable (URLRequest) async throws -> HTTPResponseData) {
        self.handler = handler
    }

    func send(_ request: URLRequest) async throws -> HTTPResponseData {
        lock.withLock { _requests.append(request) }
        return try await handler(request)
    }
}

private final class TestBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: T

    init(_ initial: T) {
        self.value = initial
    }

    func get() -> T { lock.withLock { value } }

    @discardableResult
    func withLock<R>(_ body: (inout T) -> R) -> R {
        lock.withLock { body(&value) }
    }
}

final class CloudSearchV124Tests: XCTestCase {

    private static func makePNGData(color: UIColor = .systemIndigo) -> Data {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8))
        let image = renderer.image { ctx in
            color.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }
        return image.pngData()!
    }

    // MARK: - Manifest, Validation, SearchKinds, Cookie Persistence & Security

    @MainActor
    func testManifestRelaxedURLPatternsSearchKindsAndCookiePersistence() async throws {
        // 1. search + stream + cookie without urlPatterns or canHandle validates and imports cleanly
        let searchStreamScript = """
        exports.manifest = {
          apiVersion: 1,
          id: "com.test.searchstream",
          name: "Cloud Search Ext",
          version: "1.0.0",
          badgeLabel: "CloudExt",
          capabilities: ["search", "stream", "cookie"],
          permissions: { hosts: ["api.search.test", "cdn.search.test"] }
        };
        exports.search = async function(ctx, req) {
          var r = await ctx.http.request({
            url: "https://api.search.test/search?q=" + encodeURIComponent(req.query) + "&kind=" + encodeURIComponent(req.kind || "all"),
            headers: req.cookie ? { Cookie: req.cookie } : {}
          });
          return JSON.parse(r.text);
        };
        exports.resolveStream = async function(ctx, req) {
          var r = await ctx.http.request({
            url: "https://api.search.test/stream/" + encodeURIComponent(req.remoteTrackId),
            headers: req.cookie ? { Cookie: req.cookie } : {}
          });
          return JSON.parse(r.text);
        };
        """

        let capturedRequests = TestBox<[URLRequest]>([])
        let transport = SearchFixtureTransport { req in
            capturedRequests.withLock { $0.append(req) }
            let url = req.url!
            let comps = URLComponents(url: url, resolvingAgainstBaseURL: true)!
            if comps.host == "api.search.test", comps.path == "/search" {
                let cookieHeader = req.value(forHTTPHeaderField: "Cookie")
                if cookieHeader != "sid=secret-cookie-123" {
                    let errPayload = #"{"items":[]}"#
                    return HTTPResponseData(statusCode: 200, headers: [:], data: Data(errPayload.utf8), finalURL: url)
                }
                let payload = """
                {
                  "items": [
                    {
                      "kind": "track",
                      "remoteTrackId": "trk/1",
                      "title": "Cloud Song",
                      "artist": "Cloud Artist",
                      "album": "Cloud Album",
                      "durationSec": 185,
                      "artworkUrl": "https://cdn.search.test/cover.png"
                    },
                    {
                      "kind": "album",
                      "remoteId": "alb-9",
                      "title": "Cloud Album",
                      "artist": "Cloud Artist",
                      "trackCount": 12,
                      "sourceUrl": "https://api.search.test/album/alb-9"
                    }
                  ],
                  "nextCursor": "p2"
                }
                """
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(payload.utf8), finalURL: url)
            }
            if comps.host == "api.search.test", comps.path == "/stream/trk/1" {
                let cookie = req.value(forHTTPHeaderField: "Cookie") ?? ""
                let payload = """
                {
                  "url": "https://cdn.search.test/audio.mp3",
                  "headers": { "X-Cookie-Seen": "\(cookie)" },
                  "fileExtension": "mp3"
                }
                """
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(payload.utf8), finalURL: url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: url)
        }

        let extDir = makeTempDir()
        let registry = CloudProviderRegistry(transport: transport)
        let store = ExtensionStore(baseDirectory: extDir, registry: registry, transport: transport)

        // Initially zero search extensions
        XCTAssertTrue(store.activeSearchExtensions.isEmpty)

        let installed = try await store.importPackageData(Data(searchStreamScript.utf8), fileName: "search.musico.js", sourceURL: nil)
        XCTAssertEqual(installed.manifest.effectiveSearchKinds, [.track, .playlist, .album])
        XCTAssertTrue(store.activeSearchExtensions.isEmpty) // not yet approved/enabled

        store.approvePermissionsAndEnable(id: installed.id)
        XCTAssertEqual(store.activeSearchExtensions.map(\.id), ["com.test.searchstream"])
        XCTAssertEqual(store.activeSearchExtensions.first?.badgeLabel, "CloudExt")
        XCTAssertTrue(store.activeSearchExtensions.first?.supportsStream == true)
        XCTAssertTrue(store.activeSearchExtensions.first?.supportsCookie == true)
        // Must NEVER expose built-in YouTube, Bilibili, or Google Drive
        XCTAssertFalse(store.activeSearchExtensions.contains {
            $0.id.contains("youtube") || $0.id.contains("bilibili") || $0.id.contains("gdrive")
        })

        // Save extension-level cookie, reload from disk, verify persisted and never leaked to logs
        store.updateCookie(extensionID: installed.id, cookie: "  sid=secret-cookie-123  ")
        XCTAssertEqual(store.cookie(forExtensionID: installed.id), "sid=secret-cookie-123")

        let reloaded = ExtensionStore(baseDirectory: extDir, registry: CloudProviderRegistry(transport: transport), transport: transport)
        XCTAssertEqual(reloaded.cookie(forExtensionID: installed.id), "sid=secret-cookie-123")
        XCTAssertEqual(reloaded.extension(forID: installed.id)?.state.cookie, "sid=secret-cookie-123")

        // Backwards-compatible state.json without cookie decodes cleanly
        let legacyStateData = Data(#"{"isEnabled":true,"permissionApproved":true,"settings":{},"installedAt":0,"updatedAt":0}"#.utf8)
        let decodedLegacyState = try JSONDecoder().decode(ExtensionState.self, from: legacyStateData)
        XCTAssertNil(decodedLegacyState.cookie)

        // Execute search -> passes extension cookie, returns track + album + nextCursor
        let page = try await reloaded.search(extensionID: installed.id, query: "hello", kind: .all, limit: 20, cursor: nil)
        XCTAssertEqual(page.items.count, 2)
        XCTAssertEqual(page.items[0].kind, .track)
        XCTAssertEqual(page.items[0].remoteTrackID, "trk/1")
        XCTAssertEqual(page.items[1].kind, .album)
        XCTAssertEqual(page.items[1].remoteId, "alb-9")
        XCTAssertEqual(page.items[1].trackCount, 12)
        XCTAssertEqual(page.nextCursor, "p2")

        // Verify logs never contain the saved cookie
        let logs = reloaded.extension(forID: installed.id)?.logs ?? []
        XCTAssertFalse(logs.contains { $0.message.contains("secret-cookie-123") })

        // Verify root-less resolveStream for ephemeral track passes extension cookie
        let ephemeral = try XCTUnwrap(page.items[0].makeEphemeralTrack())
        XCTAssertTrue(ephemeral.isEphemeral)
        XCTAssertEqual(ephemeral.id, "extsearch:com.test.searchstream:trk/1")
        let service = LiveCloudService(registry: reloaded.registry)
        let stream = try await service.resolveStream(track: ephemeral, root: nil, forceRefresh: false)
        XCTAssertEqual(stream.url.absoluteString, "https://cdn.search.test/audio.mp3")
        XCTAssertEqual(stream.headers["X-Cookie-Seen"], "sid=secret-cookie-123")

        // Per-LibraryRoot cookie still takes precedence over extension-level cookie when root != nil
        let mountedRoot = LibraryRoot(
            id: UUID(),
            name: "Mounted",
            kind: .cloudExtension,
            bookmark: nil,
            sourceURL: "https://api.search.test/album/alb-9",
            remoteID: "alb-9",
            cookie: "sid=root-override",
            providerID: "ext.com.test.searchstream"
        )
        let mountedTrack = Track(
            id: Track.makeID(rootID: mountedRoot.id, relativePath: "trk/1"),
            rootID: mountedRoot.id,
            relativePath: "trk/1",
            title: "Mounted",
            artist: "A",
            album: "Al",
            albumArtist: "A",
            duration: 100,
            fileSize: 0,
            modified: Date(),
            hasEmbeddedLyrics: false,
            dateAdded: Date(),
            cloudSource: "ext.com.test.searchstream",
            remoteTrackID: "trk/1"
        )
        let mountedStream = try await service.resolveStream(track: mountedTrack, root: mountedRoot, forceRefresh: false)
        XCTAssertEqual(mountedStream.headers["X-Cookie-Seen"], "sid=root-override")

        // Playlist/tree extensions STILL require urlPatterns; search-only without search() hook fails validation
        let missingUrlPatternsPlaylist = ExtensionManifest(
            apiVersion: 1,
            id: "com.test.needspattern",
            name: "Playlist Ext",
            version: "1.0.0",
            capabilities: [.playlist],
            urlPatterns: [],
            permissions: ExtensionPermissions(hosts: ["example.com"])
        )
        XCTAssertThrowsError(try missingUrlPatternsPlaylist.validate())

        let missingSearchHook = """
        exports.manifest = {
          apiVersion: 1,
          id: "com.test.nohook",
          name: "No Hook",
          version: "1.0.0",
          capabilities: ["search"],
          permissions: { hosts: ["example.com"] }
        };
        """
        do {
            _ = try await JSExtensionRuntime.extractManifest(from: missingSearchHook)
            XCTFail("Expected missing search hook error")
        } catch ExtensionImportError.invalidPackage {}
    }

    @MainActor
    func testManifestSearchKindsDefaultsAndExplicitSubset() throws {
        let omittedJSON = """
        {
          "apiVersion": 1,
          "id": "com.test.kinds1",
          "name": "Kinds 1",
          "version": "1.0.0",
          "capabilities": ["search"],
          "permissions": { "hosts": ["example.com"] }
        }
        """
        let m1 = try JSONDecoder().decode(ExtensionManifest.self, from: Data(omittedJSON.utf8))
        XCTAssertEqual(m1.effectiveSearchKinds, [.track, .playlist, .album])

        let emptyJSON = """
        {
          "apiVersion": 1,
          "id": "com.test.kinds2",
          "name": "Kinds 2",
          "version": "1.0.0",
          "capabilities": ["search"],
          "searchKinds": [],
          "permissions": { "hosts": ["example.com"] }
        }
        """
        let m2 = try JSONDecoder().decode(ExtensionManifest.self, from: Data(emptyJSON.utf8))
        XCTAssertEqual(m2.effectiveSearchKinds, [.track, .playlist, .album])

        let subsetJSON = """
        {
          "apiVersion": 1,
          "id": "com.test.kinds3",
          "name": "Kinds 3",
          "version": "1.0.0",
          "capabilities": ["search"],
          "searchKinds": ["album", "track", "album"],
          "permissions": { "hosts": ["example.com"] }
        }
        """
        let m3 = try JSONDecoder().decode(ExtensionManifest.self, from: Data(subsetJSON.utf8))
        XCTAssertEqual(m3.effectiveSearchKinds, [.album, .track])
    }

    // MARK: - Search Runtime Security, Timeout, Cancellation & Error Codes

    func testSearchRuntimeTimeoutCancellationHostAllowlistAndErrors() async throws {
        let script = """
        exports.manifest = {
          apiVersion: 1,
          id: "com.test.searchsec",
          name: "Search Security",
          version: "1.0.0",
          capabilities: ["search"],
          permissions: { hosts: ["allowed.test"] }
        };
        exports.search = async function(ctx, req) {
          if (req.query === "disallowed-host") {
            await ctx.http.request({ url: "https://evil.test/steal" });
          }
          if (req.query === "auth") {
            var e = new Error("Sign in required");
            e.code = "AUTH_REQUIRED";
            throw e;
          }
          if (req.query === "rate") {
            var e = new Error("Slow down");
            e.code = "RATE_LIMITED";
            throw e;
          }
          if (req.query === "hang") {
            await new Promise(function() {});
          }
          return { items: [] };
        };
        """
        let manifest = try await JSExtensionRuntime.extractManifest(from: script)
        let transport = SearchFixtureTransport { req in
            HTTPResponseData(statusCode: 200, headers: [:], data: Data(), finalURL: req.url)
        }

        // Strict host allowlist
        let shortRuntime = JSExtensionRuntime(manifest: manifest, scriptSource: script, transport: transport, shortTimeoutMs: 120, longTimeoutMs: 120)
        do {
            _ = try await shortRuntime.search(query: "disallowed-host")
            XCTFail("Expected PERMISSION_DENIED")
        } catch let err as CloudSourceError {
            XCTAssertEqual(err.extensionErrorCode, "PERMISSION_DENIED")
        }

        // AUTH_REQUIRED and RATE_LIMITED
        do {
            _ = try await shortRuntime.search(query: "auth")
            XCTFail("Expected AUTH_REQUIRED")
        } catch let err as CloudSourceError {
            XCTAssertEqual(err.extensionErrorCode, "AUTH_REQUIRED")
        }
        do {
            _ = try await shortRuntime.search(query: "rate")
            XCTFail("Expected RATE_LIMITED")
        } catch let err as CloudSourceError {
            XCTAssertEqual(err.extensionErrorCode, "RATE_LIMITED")
        }

        // Timeout
        do {
            _ = try await shortRuntime.search(query: "hang")
            XCTFail("Expected timeout")
        } catch let err as CloudSourceError {
            XCTAssertEqual(err.extensionErrorCode, "EXTENSION_RUNTIME_ERROR")
        }

        // Task cancellation aborts search promptly
        let longRuntime = JSExtensionRuntime(manifest: manifest, scriptSource: script, transport: transport, shortTimeoutMs: 10_000, longTimeoutMs: 10_000)
        let task = Task {
            try await longRuntime.search(query: "hang")
        }
        try await Task.sleep(for: .milliseconds(30))
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("Expected CancellationError")
        } catch is CancellationError {}
    }

    // MARK: - Search Controller: Explicit Submit, Source Switching, Pagination, Reset

    @MainActor
    func testSearchControllerExplicitSubmitSwitchingPaginationAndReset() async throws {
        let calls = TestBox<[(extID: String, query: String, kind: CloudSearchKindFilter, cursor: String?)]>([])
        let hangGate = TestBox<Bool>(false)

        let controller = CloudSearchController { extID, query, kind, _, cursor in
            calls.withLock { $0.append((extID, query, kind, cursor)) }
            if query == "slow" {
                hangGate.withLock { $0 = true }
                try await Task.sleep(for: .seconds(5))
                return CloudSearchPage(items: [], nextCursor: nil)
            }
            if query == "auth-err" {
                throw CloudSourceError.extensionRuntimeError(code: "AUTH_REQUIRED", message: "Need cookie")
            }
            if extID == "ext.a" {
                if cursor == nil {
                    return CloudSearchPage(
                        items: [
                            CloudSearchResult(extensionID: extID, kind: .track, remoteTrackID: "1", title: "Track 1"),
                            CloudSearchResult(extensionID: extID, kind: .album, remoteId: "1", title: "Album 1")
                        ],
                        nextCursor: "c1"
                    )
                } else if cursor == "c1" {
                    // Duplicate (track, "1") must be deduplicated, and nextCursor == prevCursor must terminate pagination
                    return CloudSearchPage(
                        items: [
                            CloudSearchResult(extensionID: extID, kind: .track, remoteTrackID: "1", title: "Track 1 Dup"),
                            CloudSearchResult(extensionID: extID, kind: .track, remoteTrackID: "2", title: "Track 2")
                        ],
                        nextCursor: "c1"
                    )
                }
            }
            return CloudSearchPage(
                items: [
                    CloudSearchResult(extensionID: extID, kind: .track, remoteTrackID: "b1", title: "Beta \(query)")
                ],
                nextCursor: nil
            )
        }

        let extA = CloudSearchExtensionDescriptor(
            id: "ext.a",
            providerID: "ext.ext.a",
            name: "Ext A",
            badgeLabel: "ExtA",
            searchKinds: [.track, .album],
            supportsStream: true,
            supportsCollection: true,
            supportsCookie: true
        )
        let extB = CloudSearchExtensionDescriptor(
            id: "ext.b",
            providerID: "ext.ext.b",
            name: "Ext B",
            badgeLabel: "ExtB",
            searchKinds: [.track],
            supportsStream: true,
            supportsCollection: false,
            supportsCookie: false
        )
        let activeExts = [extA, extB]
        let store = ExtensionStore(baseDirectory: makeTempDir(), registry: CloudProviderRegistry(), transport: SearchFixtureTransport { req in
            HTTPResponseData(statusCode: 200, headers: [:], data: Data(), finalURL: req.url)
        })
        let nav = AppNavigation()

        // Default source is Local
        XCTAssertEqual(nav.searchSource, .local)
        // Typing while in Local or switching Local -> Cloud does NOT auto-submit
        nav.updateSearchQueryFromUser("hello")
        XCTAssertTrue(calls.get().isEmpty)

        controller.switchSource(to: .cloud(extensionID: "ext.a"), currentQuery: nav.searchQuery, activeExtensions: activeExts, navigation: nav, extensionStore: store)
        XCTAssertEqual(nav.searchSource, .cloud(extensionID: "ext.a"))
        XCTAssertEqual(controller.kindOptions(for: extA), [.all, .track, .album])
        XCTAssertEqual(controller.kindOptions(for: extB), []) // single kind hides pills
        XCTAssertTrue(calls.get().isEmpty) // still waiting for explicit submit

        // Explicit submit runs search on Ext A
        controller.submitSearch(query: "hello", descriptor: extA, extensionStore: store)
        await controller.waitForCurrentTask()
        XCTAssertEqual(calls.get().count, 1)
        XCTAssertEqual(controller.results.count, 2) // (track, 1) and (album, 1) both kept!
        XCTAssertEqual(controller.nextCursor, "c1")

        // Load More deduplicates (track, 1) and stops when nextCursor == prevCursor
        controller.loadMore(descriptor: extA, extensionStore: store)
        await controller.waitForCurrentTask()
        XCTAssertEqual(controller.results.map(\.title), ["Track 1", "Album 1", "Track 2"])
        XCTAssertNil(controller.nextCursor)

        // Switching between cloud sources while query is non-empty triggers ONCE on target extension
        controller.switchSource(to: .cloud(extensionID: "ext.b"), currentQuery: "hello", activeExtensions: activeExts, navigation: nav, extensionStore: store)
        await controller.waitForCurrentTask()
        XCTAssertEqual(calls.get().last?.extID, "ext.b")
        XCTAssertEqual(calls.get().last?.kind, .track)
        XCTAssertEqual(controller.results.map(\.title), ["Beta hello"])

        // Error classification for AUTH_REQUIRED
        controller.submitSearch(query: "auth-err", descriptor: extA, extensionStore: store)
        await controller.waitForCurrentTask()
        XCTAssertEqual(controller.error?.kind, .authRequired)

        // Switching back to Local cancels in-flight search and clears results
        controller.submitSearch(query: "slow", descriptor: extA, extensionStore: store)
        try await Task.sleep(for: .milliseconds(30))
        XCTAssertTrue(hangGate.get())
        XCTAssertTrue(controller.isLoading)
        controller.switchSource(to: .local, currentQuery: "slow", activeExtensions: activeExts, navigation: nav, extensionStore: store)
        XCTAssertEqual(nav.searchSource, .local)
        XCTAssertFalse(controller.isLoading)
        XCTAssertTrue(controller.results.isEmpty)

        // Local Artist/Album/Genre shortcut resets source to Local
        nav.searchSource = .cloud(extensionID: "ext.a")
        nav.openSearch(query: "Kano", field: .artist)
        XCTAssertEqual(nav.searchSource, .local)
        XCTAssertEqual(nav.searchField, .artist)
        XCTAssertEqual(nav.searchQuery, "Kano")

        // Disabling/removing active extension resets source to Local and clears results
        controller.switchSource(to: .cloud(extensionID: "ext.a"), currentQuery: "hello", activeExtensions: activeExts, navigation: nav, extensionStore: store)
        controller.submitSearch(query: "hello", descriptor: extA, extensionStore: store)
        await controller.waitForCurrentTask()
        XCTAssertFalse(controller.results.isEmpty)
        controller.reconcileActiveExtensions([extB], navigation: nav)
        XCTAssertEqual(nav.searchSource, .local)
        XCTAssertTrue(controller.results.isEmpty)
    }

    // MARK: - Ephemeral Track Registry, Isolation, Auto-Prune, Stats Exclusion, Unavailable Error

    @MainActor
    func testEphemeralTrackRegistryIsolationAutoPruneAndStatsExclusion() async throws {
        let stateDir = makeTempDir()
        let docsDir = makeTempDir()
        let sampleURL = try XCTUnwrap(Bundle(for: CloudSearchV124Tests.self).url(forResource: "sample", withExtension: "mp3"))
        let sampleData = try Data(contentsOf: sampleURL)
        try sampleData.write(to: docsDir.appendingPathComponent("local.mp3"))

        // Backwards-compatible Track decode without isEphemeral defaults to false
        let legacyTrackJSON = """
        {
          "id": "legacy1",
          "rootID": "00000000-0000-0000-0000-00000000A990",
          "relativePath": "local.mp3",
          "title": "Legacy",
          "artist": "A",
          "album": "B",
          "albumArtist": "A",
          "duration": 10,
          "fileSize": 100,
          "modified": 0,
          "hasEmbeddedLyrics": false,
          "dateAdded": 0
        }
        """
        let decodedLegacyTrack = try JSONDecoder().decode(Track.self, from: Data(legacyTrackJSON.utf8))
        XCTAssertFalse(decodedLegacyTrack.isEphemeral)

        // Setup extension with search + stream
        let extScript = """
        exports.manifest = {
          apiVersion: 1,
          id: "com.test.ephemeral",
          name: "Ephemeral Ext",
          version: "1.0.0",
          capabilities: ["search", "stream"],
          permissions: { hosts: ["cdn.ephemeral.test"] }
        };
        exports.search = async function() { return { items: [] }; };
        exports.resolveStream = async function(ctx, req) {
          return { url: "https://cdn.ephemeral.test/" + req.remoteTrackId + ".mp3", fileExtension: "mp3" };
        };
        """
        let registry = CloudProviderRegistry()
        let extStore = ExtensionStore(
            baseDirectory: makeTempDir(),
            registry: registry,
            transport: SearchFixtureTransport { req in
                HTTPResponseData(statusCode: 200, headers: [:], data: Data(), finalURL: req.url)
            }
        )
        let installed = try await extStore.importPackageData(Data(extScript.utf8), fileName: "eph.musico.js", sourceURL: nil)
        extStore.approvePermissionsAndEnable(id: installed.id)

        let service = LiveCloudService(registry: registry)
        let cache = CloudAudioCache(
            directory: makeTempDir(),
            maxBytes: 20_000_000,
            resolver: service,
            downloader: { _, _ in
                let tmp = makeTempDir().appendingPathComponent("eph.mp3")
                try sampleData.write(to: tmp)
                return (tmp, 200, "audio/mpeg")
            }
        )

        let library = LibraryStore(
            directory: stateDir,
            documentsURL: docsDir,
            reader: StubReader(),
            artwork: ArtworkCache(directory: stateDir.appendingPathComponent("Artwork")),
            cloudFetcher: service,
            providerRegistry: registry,
            extensionAvailability: { extStore.availability(forProviderID: $0) }
        )
        await library.rescan()
        let localID = try XCTUnwrap(library.tracks.first?.id)

        let queues = QueueStore(directory: stateDir)
        let history = PlayHistoryStore(directory: stateDir)
        let player = PlayerController(
            library: library,
            queues: queues,
            history: history,
            engine: AudioEngine(),
            settingsURL: stateDir.appendingPathComponent("settings.json"),
            cloudAudio: cache
        )
        defer { player.shutdown() }

        let eph1 = CloudSearchResult(
            extensionID: installed.id,
            kind: .track,
            remoteTrackID: "e1",
            title: "Ephemeral One",
            artist: "Cloud Artist",
            album: "Cloud Album",
            durationSec: 58,
            artworkUrl: "https://cdn.ephemeral.test/e1.png"
        ).makeEphemeralTrack()!
        let eph2 = CloudSearchResult(
            extensionID: installed.id,
            kind: .track,
            remoteTrackID: "e2",
            title: "Ephemeral Two",
            artist: "Cloud Artist",
            album: "Cloud Album",
            durationSec: 58
        ).makeEphemeralTrack()!

        // Register & queue eph1, eph2
        library.registerEphemeralTracks([eph1, eph2])
        player.playInNamedQueue([eph1.id, eph2.id], name: "Search (Ephemeral Ext): test", startAt: 0, shuffle: false)

        // Wait for eph1 to load & start playing
        for _ in 0..<40 {
            if player.isPlaying { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertEqual(player.currentTrack?.id, eph1.id)

        // Ephemeral tracks resolve via library.track(_:) but NEVER pollute library.tracks, albums, artists, or local search
        XCTAssertEqual(library.track(eph1.id)?.id, eph1.id)
        XCTAssertEqual(library.tracks.map(\.id), [localID])
        XCTAssertFalse(library.albums.contains { $0.title == "Cloud Album" })
        XCTAssertFalse(library.artists.contains("Cloud Artist"))
        XCTAssertTrue(library.search("Ephemeral").isEmpty)

        // Play history & listening stats MUST completely exclude ephemeral tracks
        player.noteListening(30)
        player.trackDidEnd()
        XCTAssertEqual(history.count(for: eph1.id), 0)
        XCTAssertNil(history.lastPlayed(for: eph1.id))
        XCTAssertEqual(history.totalPlays, 0)
        XCTAssertEqual(history.totalSeconds, 0, accuracy: 0.001)
        history.recordPlay(eph1.id)
        history.markStarted(eph2.id)
        XCTAssertEqual(history.totalPlays, 0)
        XCTAssertTrue(history.recentlyPlayed([eph1, eph2]).isEmpty)
        XCTAssertTrue(history.neverPlayed([eph1, eph2]).isEmpty)

        // Ephemeral track in active queue survives LibraryStore + QueueStore reload
        player.shutdown()
        let reloadedLib = LibraryStore(
            directory: stateDir,
            documentsURL: docsDir,
            reader: StubReader(),
            artwork: ArtworkCache(directory: stateDir.appendingPathComponent("Artwork")),
            cloudFetcher: service,
            providerRegistry: registry,
            extensionAvailability: { extStore.availability(forProviderID: $0) }
        )
        let reloadedQueues = QueueStore(directory: stateDir)
        let reloadedHistory = PlayHistoryStore(directory: stateDir)
        let reloadedPlayer = PlayerController(
            library: reloadedLib,
            queues: reloadedQueues,
            history: reloadedHistory,
            engine: AudioEngine(),
            settingsURL: stateDir.appendingPathComponent("settings.json"),
            cloudAudio: cache
        )
        defer { reloadedPlayer.shutdown() }
        XCTAssertNotNil(reloadedLib.track(eph1.id))
        XCTAssertNotNil(reloadedLib.track(eph2.id))

        // Auto-pruning removes unreferenced ephemeral tracks when queue drops them
        reloadedPlayer.clearQueue()
        XCTAssertNil(reloadedLib.track(eph1.id))
        XCTAssertNil(reloadedLib.track(eph2.id))

        // Playing an uncached ephemeral track from a disabled extension surfaces EXTENSION_UNAVAILABLE cleanly
        let uncachedEph = CloudSearchResult(
            extensionID: installed.id,
            kind: .track,
            remoteTrackID: "uncached-99",
            title: "Disabled Ext Song",
            artist: "Cloud Artist"
        ).makeEphemeralTrack()!
        reloadedLib.registerEphemeralTracks([uncachedEph])
        extStore.setEnabled(id: installed.id, enabled: false)
        reloadedPlayer.play([uncachedEph.id], startAt: 0)
        for _ in 0..<40 {
            if reloadedPlayer.errorMessage != nil { break }
            try await Task.sleep(for: .milliseconds(25))
        }
        XCTAssertNotNil(reloadedPlayer.errorMessage)
        XCTAssertFalse(reloadedPlayer.isPlaying)
    }

    // MARK: - Single-Track Save to Local Folder (No .musico-cloud.json, Rescan) & Safe Cache Stems

    @MainActor
    func testSingleTrackSaveToLocalFolderWithoutManifestAndRescan() async throws {
        let stateDir = makeTempDir()
        let docsDir = makeTempDir()
        let sampleURL = try XCTUnwrap(Bundle(for: CloudSearchV124Tests.self).url(forResource: "sample", withExtension: "mp3"))
        let sampleData = try Data(contentsOf: sampleURL)
        let pngData = Self.makePNGData()

        let thumbCache = CloudThumbnailCache(fetcher: { _ in pngData })
        let ephTrack = CloudSearchResult(
            extensionID: "com.test.save",
            kind: .track,
            remoteTrackID: "folder/sub/song-7",
            title: "Saved Ephemeral Track",
            artist: "Saved Artist",
            album: "Saved Album",
            durationSec: 58,
            artworkUrl: "https://cdn.save.test/cover.png"
        ).makeEphemeralTrack()!

        let resolver = InlineStreamResolver { _, _, _ in
            CloudStreamResult(
                url: URL(string: "https://cdn.save.test/song.mp3")!,
                headers: [:],
                fileExtension: "mp3",
                expiresAt: Date().addingTimeInterval(600)
            )
        }
        let cache = CloudAudioCache(
            directory: makeTempDir(),
            maxBytes: 20_000_000,
            resolver: resolver,
            downloader: { _, _ in
                let tmp = makeTempDir().appendingPathComponent("dl.mp3")
                try sampleData.write(to: tmp)
                return (tmp, 200, "audio/mpeg")
            }
        )

        let library = LibraryStore(
            directory: stateDir,
            documentsURL: docsDir,
            reader: MetadataReader(),
            artwork: ArtworkCache(directory: stateDir.appendingPathComponent("Artwork"))
        )
        await library.rescan()
        XCTAssertTrue(library.tracks.isEmpty)

        // Saving to default Documents folder writes audio + metadata/artwork, NEVER .musico-cloud.json, and rescans!
        let savedURL = try await library.saveCloudTrack(ephTrack, toFolder: nil, cache: cache, thumbnailCache: thumbCache)
        XCTAssertEqual(savedURL.deletingLastPathComponent().path, docsDir.path)
        XCTAssertTrue(FileManager.default.fileExists(atPath: savedURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: docsDir.appendingPathComponent(CloudFolderManifest.fileName).path))
        XCTAssertEqual(library.tracks.count, 1)
        XCTAssertEqual(library.tracks.first?.title, "Saved Ephemeral Track")
        XCTAssertEqual(library.tracks.first?.artist, "Saved Artist")
        XCTAssertEqual(library.tracks.first?.album, "Saved Album")
        XCTAssertFalse(library.tracks.first?.isEphemeral ?? true)

        // LyricsStore also handles extsearch: IDs with slashes safely without path traversal
        let lyricsStore = LyricsStore(directory: makeTempDir())
        try lyricsStore.saveImportedLyrics(text: "[00:01.00]Hello", trackID: ephTrack.id)
        XCTAssertTrue(lyricsStore.hasImported(trackID: ephTrack.id))
    }

    // MARK: - In-Memory Thumbnail Cache & Ephemeral Collection Preview

    @MainActor
    func testInMemoryThumbnailCacheHTTPSOnlyAndNoDiskArtworkPollution() async throws {
        let fetchCalls = TestBox<Int>(0)
        let pngData = Self.makePNGData()
        let thumbCache = CloudThumbnailCache(fetcher: { _ in
            fetchCalls.withLock { $0 += 1 }
            return pngData
        })

        // Non-HTTPS URLs rejected
        let httpImg = await thumbCache.loadImage(from: "http://insecure.test/a.png")
        let fileImg = await thumbCache.loadImage(from: "file:///etc/passwd")
        let jsImg = await thumbCache.loadImage(from: "javascript:alert(1)")
        XCTAssertNil(httpImg)
        XCTAssertNil(fileImg)
        XCTAssertNil(jsImg)
        XCTAssertEqual(fetchCalls.get(), 0)

        // HTTPS URL fetched once and cached in memory
        let img1 = await thumbCache.loadImage(from: "https://cdn.thumb.test/1.png")
        let img2 = await thumbCache.loadImage(from: "https://cdn.thumb.test/1.png")
        XCTAssertNotNil(img1)
        XCTAssertNotNil(img2)
        XCTAssertEqual(fetchCalls.get(), 1)
        XCTAssertNotNil(thumbCache.cachedImage(for: "https://cdn.thumb.test/1.png"))

        // Disk ArtworkCache directory remains empty
        let artworkDir = makeTempDir()
        _ = ArtworkCache(directory: artworkDir)
        let diskFiles = try FileManager.default.contentsOfDirectory(atPath: artworkDir.path)
        XCTAssertTrue(diskFiles.isEmpty)
    }

    @MainActor
    func testEphemeralCollectionPreviewDoesNotModifyFoldersUntilAdded() async throws {
        let script = """
        exports.manifest = {
          apiVersion: 1,
          id: "com.test.preview",
          name: "Preview Ext",
          version: "1.0.0",
          capabilities: ["search", "playlist", "stream", "cookie"],
          urlPatterns: ["^https://preview\\\\.test/album/.+$"],
          permissions: { hosts: ["preview.test"] }
        };
        exports.canHandle = function(input) {
          return { matched: true, normalizedUrl: input.extractedUrl, remoteId: "alb-1", kind: "album" };
        };
        exports.search = async function() {
          return {
            items: [{
              kind: "album",
              remoteId: "alb-1",
              title: "Preview Album",
              sourceUrl: "https://preview.test/album/alb-1"
            }]
          };
        };
        exports.fetchCollection = async function(ctx, req) {
          return {
            remoteId: "alb-1",
            title: "Preview Album (" + (req.cookie || "none") + ")",
            tracks: [{
              remoteTrackId: "p1",
              relativePath: "01.mp3",
              title: "Preview Track 1",
              artist: "Preview Artist",
              album: "Preview Album",
              durationSec: 120
            }]
          };
        };
        exports.resolveStream = async function(ctx, req) {
          return { url: "https://preview.test/audio/" + req.remoteTrackId + ".mp3", fileExtension: "mp3" };
        };
        """
        let registry = CloudProviderRegistry()
        let extStore = ExtensionStore(
            baseDirectory: makeTempDir(),
            registry: registry,
            transport: SearchFixtureTransport { req in
                HTTPResponseData(statusCode: 200, headers: [:], data: Data(), finalURL: req.url)
            }
        )
        let ext = try await extStore.importPackageData(Data(script.utf8), fileName: "prev.musico.js", sourceURL: nil)
        extStore.approvePermissionsAndEnable(id: ext.id)
        extStore.updateCookie(extensionID: ext.id, cookie: "sid=preview")

        let service = LiveCloudService(registry: registry)
        let stateDir = makeTempDir()
        let library = LibraryStore(
            directory: stateDir,
            documentsURL: makeTempDir(),
            reader: StubReader(),
            artwork: ArtworkCache(directory: stateDir.appendingPathComponent("Artwork")),
            cloudFetcher: service,
            providerRegistry: registry,
            extensionAvailability: { extStore.availability(forProviderID: $0) },
            artworkDownloader: { _ in nil }
        )
        let rootCountBefore = library.roots.count

        // Preview calls fetchCollection in memory with extension cookie without modifying library.roots
        let preview = try await extStore.previewCollection(extensionID: ext.id, sourceURL: "https://preview.test/album/alb-1")
        XCTAssertEqual(preview.title, "Preview Album (sid=preview)")
        XCTAssertEqual(preview.tracks.count, 1)
        XCTAssertEqual(library.roots.count, rootCountBefore)

        // Adding as Cloud Folder mounts the extension LibraryRoot permanently
        try await library.addCloudRoot(
            sourceURL: "https://preview.test/album/alb-1",
            cookie: extStore.cookie(forExtensionID: ext.id),
            preferredProviderID: ext.providerID
        )
        XCTAssertEqual(library.roots.count, rootCountBefore + 1)
        XCTAssertTrue(library.hasCloudRoot(providerID: ext.providerID, remoteID: "alb-1"))
    }
}

private struct InlineStreamResolver: CloudStreamResolving {
    let handler: @Sendable (Track, LibraryRoot?, Bool) async throws -> CloudStreamResult

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult {
        try await handler(track, root, forceRefresh)
    }
}
