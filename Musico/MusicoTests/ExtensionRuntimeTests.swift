import Compression
import Foundation
import XCTest
@testable import Musico

private final class FixtureTransport: CloudHTTPTransport, @unchecked Sendable {
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

private enum TestZipBuilder {
    struct Entry {
        var name: String
        var data: Data
        var deflate: Bool = true
        var overrideUncompressedSize: UInt32? = nil
    }

    static func buildArchive(entries: [Entry]) throws -> Data {
        var archive = Data()
        var centralDirectory = Data()

        for entry in entries {
            let nameData = Data(entry.name.utf8)
            let crc = ExtensionArchiveReader.crc32IEEE(entry.data)
            let uncompressedSize = entry.overrideUncompressedSize ?? UInt32(entry.data.count)
            let method: UInt16
            let payload: Data

            if entry.deflate && !entry.data.isEmpty {
                let dstCapacity = max(entry.data.count + 128, 256)
                var dst = [UInt8](repeating: 0, count: dstCapacity)
                let encodedSize: Int = entry.data.withUnsafeBytes { rawBuf in
                    guard let srcPtr = rawBuf.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return 0 }
                    return dst.withUnsafeMutableBufferPointer { dstBuf in
                        guard let dstPtr = dstBuf.baseAddress else { return 0 }
                        return compression_encode_buffer(dstPtr, dstCapacity, srcPtr, entry.data.count, nil, COMPRESSION_ZLIB)
                    }
                }
                if encodedSize > 0 {
                    method = 8
                    payload = Data(dst.prefix(encodedSize))
                } else {
                    method = 0
                    payload = entry.data
                }
            } else {
                method = 0
                payload = entry.data
            }

            let localHeaderOffset = UInt32(archive.count)
            // Local file header (30 bytes + name)
            appendUInt32(&archive, 0x0403_4b50)
            appendUInt16(&archive, 20)
            appendUInt16(&archive, 0)
            appendUInt16(&archive, method)
            appendUInt16(&archive, 0)
            appendUInt16(&archive, 0)
            appendUInt32(&archive, crc)
            appendUInt32(&archive, UInt32(payload.count))
            appendUInt32(&archive, uncompressedSize)
            appendUInt16(&archive, UInt16(nameData.count))
            appendUInt16(&archive, 0)
            archive.append(nameData)
            archive.append(payload)

            // Central directory header (46 bytes + name)
            appendUInt32(&centralDirectory, 0x0201_4b50)
            appendUInt16(&centralDirectory, 20)
            appendUInt16(&centralDirectory, 20)
            appendUInt16(&centralDirectory, 0)
            appendUInt16(&centralDirectory, method)
            appendUInt16(&centralDirectory, 0)
            appendUInt16(&centralDirectory, 0)
            appendUInt32(&centralDirectory, crc)
            appendUInt32(&centralDirectory, UInt32(payload.count))
            appendUInt32(&centralDirectory, uncompressedSize)
            appendUInt16(&centralDirectory, UInt16(nameData.count))
            appendUInt16(&centralDirectory, 0)
            appendUInt16(&centralDirectory, 0)
            appendUInt16(&centralDirectory, 0)
            appendUInt16(&centralDirectory, 0)
            appendUInt32(&centralDirectory, 0)
            appendUInt32(&centralDirectory, localHeaderOffset)
            centralDirectory.append(nameData)
        }

        let cdOffset = UInt32(archive.count)
        let cdSize = UInt32(centralDirectory.count)
        archive.append(centralDirectory)

        // End of central directory (22 bytes)
        appendUInt32(&archive, 0x0605_4b50)
        appendUInt16(&archive, 0)
        appendUInt16(&archive, 0)
        appendUInt16(&archive, UInt16(entries.count))
        appendUInt16(&archive, UInt16(entries.count))
        appendUInt32(&archive, cdSize)
        appendUInt32(&archive, cdOffset)
        appendUInt16(&archive, 0)
        return archive
    }

    private static func appendUInt16(_ data: inout Data, _ value: UInt16) {
        var le = value.littleEndian
        withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
    }

    private static func appendUInt32(_ data: inout Data, _ value: UInt32) {
        var le = value.littleEndian
        withUnsafeBytes(of: &le) { data.append(contentsOf: $0) }
    }
}

final class ExtensionRuntimeTests: XCTestCase {

    private func fixturesDirectory() throws -> URL {
        let testFile = URL(fileURLWithPath: #filePath)
        let repoRoot = testFile
            .deletingLastPathComponent() // MusicoTests
            .deletingLastPathComponent() // Musico
            .deletingLastPathComponent() // musicplayer
        let fixtures = repoRoot.appendingPathComponent("docs/extensions/fixtures", isDirectory: true)
        XCTAssertTrue(FileManager.default.fileExists(atPath: fixtures.path), "Missing fixtures directory at \(fixtures.path)")
        return fixtures
    }

    // MARK: - Basic Playlist, Crypto, KV, Settings, Env, Log, Pagination, Stream, Cookie Isolation

    @MainActor
    func testBasicPlaylistFixtureConformanceAndCookieIsolation() async throws {
        let fixtures = try fixturesDirectory()
        let basicURL = fixtures.appendingPathComponent("basic-playlist.musico.js")

        let capturedRequests = NSLockBox<[URLRequest]>([])
        let transport = FixtureTransport { req in
            capturedRequests.withLock { $0.append(req) }
            guard let url = req.url, let comps = URLComponents(url: url, resolvingAgainstBaseURL: true) else {
                return HTTPResponseData(statusCode: 400, headers: [:], data: Data(), finalURL: req.url)
            }
            if comps.host == "api.fixture.test", comps.path.hasPrefix("/playlists/") {
                let cursor = comps.queryItems?.first(where: { $0.name == "cursor" })?.value
                if cursor == nil {
                    let body: [String: Any] = [
                        "remoteId": "conformance",
                        "title": "Conformance Playlist",
                        "injectDiagnostics": true,
                        "tracks": [
                            [
                                "remoteTrackId": "t1",
                                "relativePath": "01.mp3",
                                "title": "Track One",
                                "artist": "Tester",
                                "album": "",
                                "trackNumber": 1,
                                "durationSec": 120,
                                "artworkUrl": "https://img.cdn.fixture.test/1.jpg"
                            ]
                        ],
                        "nextCursor": "page-2"
                    ]
                    return HTTPResponseData(
                        statusCode: 200,
                        headers: ["Content-Type": "application/json"],
                        data: try JSONSerialization.data(withJSONObject: body),
                        finalURL: url
                    )
                } else {
                    XCTAssertEqual(cursor, "page-2")
                    let body: [String: Any] = [
                        "remoteId": "conformance",
                        "title": "Conformance Playlist",
                        "tracks": [
                            [
                                "remoteTrackId": "t2",
                                "relativePath": "02.flac",
                                "title": "Track Two",
                                "artist": "Tester",
                                "album": "Page 2",
                                "trackNumber": 2,
                                "durationSec": 200
                            ]
                        ],
                        "nextCursor": NSNull()
                    ]
                    return HTTPResponseData(
                        statusCode: 200,
                        headers: ["Content-Type": "application/json"],
                        data: try JSONSerialization.data(withJSONObject: body),
                        finalURL: url
                    )
                }
            }
            if comps.host == "api.fixture.test", comps.path == "/stream" {
                let body: [String: Any] = [
                    "url": "https://edge1.cdn.fixture.test/audio/t1.mp3",
                    "headers": ["Referer": "https://fixture.test/"],
                    "fileExtension": "mp3",
                    "mimeType": "audio/mpeg",
                    "expiresAtMs": 1_800_000_000_000.0,
                    "fallbackUrls": ["https://edge2.cdn.fixture.test/audio/t1.mp3"],
                    "contentLength": 1_024_000
                ]
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "application/json"],
                    data: try JSONSerialization.data(withJSONObject: body),
                    finalURL: url
                )
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: url)
        }

        let registry = CloudProviderRegistry(transport: transport)
        let extDir = makeTempDir()
        let extStore = ExtensionStore(baseDirectory: extDir, registry: registry, transport: transport)

        let staged = try await extStore.importFromFile(at: basicURL)
        XCTAssertEqual(staged.id, "com.musico.fixture.basic")
        XCTAssertFalse(staged.state.permissionApproved)
        XCTAssertFalse(staged.isActive)
        XCTAssertFalse(extStore.setEnabled(id: staged.id, enabled: true))

        extStore.approvePermissionsAndEnable(id: staged.id)
        XCTAssertTrue(extStore.extension(forID: staged.id)?.isActive == true)

        // Update settings overrides to match mock-runner conformance check
        extStore.updateSetting(extensionID: staged.id, key: "preferHighBitrate", value: .bool(false))
        extStore.updateSetting(extensionID: staged.id, key: "apiTag", value: .string("custom-tag"))
        extStore.updateSetting(extensionID: staged.id, key: "region", value: .string("jp"))

        let provider = try XCTUnwrap(registry.provider(forID: "ext.com.musico.fixture.basic") as? JSExtensionCloudProvider)
        let match = try XCTUnwrap(provider.canHandle(
            rawText: "Check this: https://www.fixture.test/playlist/conformance?foo=bar",
            extractedURL: "https://www.fixture.test/playlist/conformance?foo=bar"
        ))
        XCTAssertEqual(match.normalizedURL, "https://fixture.test/playlist/conformance")
        XCTAssertEqual(match.remoteID, "conformance")

        let playlist = try await provider.fetchCollection(sourceURL: match.normalizedURL, cookie: "sid=abc")
        XCTAssertEqual(playlist.kind, .cloudExtension)
        XCTAssertEqual(playlist.providerID, "ext.com.musico.fixture.basic")
        XCTAssertEqual(playlist.tracks.count, 2)
        XCTAssertEqual(playlist.tracks[1].title, "Track Two")

        let diag = playlist.tracks[0].album
        XCTAssertTrue(diag.contains("md5=860a75194508c8447e93dbcba86b5ffe"), diag)
        XCTAssertTrue(diag.contains("sha1=e16682e68bc1074d3b8f9e4c9869ebcdb3b20090"), diag)
        XCTAssertTrue(diag.contains("sha256=cbcd5268d62f393b8ce6cc33ed8f5309be03b110012a0bc48d302855d63edd11"), diag)
        XCTAssertTrue(diag.contains("hmac=9edcd2ade9a09eac5f91629abeb0fb0ff5fe6a4cad391511b4dbeb4a333a1ba8"), diag)
        XCTAssertTrue(diag.contains("b64=musico-hello"), diag)
        XCTAssertTrue(diag.contains("kv=1/temp/null"), diag)
        XCTAssertTrue(diag.contains("settings=false|custom-tag|jp"), diag)
        #if targetEnvironment(macCatalyst)
        XCTAssertTrue(diag.contains("env=macos|v1"), diag)
        #else
        XCTAssertTrue(diag.contains("env=ios|v1"), diag)
        #endif

        // Second call verifies persisted visit_count increments (page 1 & page 2 of first fetch ran twice -> 3 on next page 1)
        let playlistSecond = try await provider.fetchCollection(sourceURL: match.normalizedURL, cookie: "sid=abc")
        XCTAssertTrue(playlistSecond.tracks[0].album.contains("kv=3/temp/null"))

        // Verify AUTH_REQUIRED when auth-required URL has no cookie
        do {
            _ = try await provider.fetchCollection(sourceURL: "https://fixture.test/playlist/auth-required", cookie: nil)
            XCTFail("Expected AUTH_REQUIRED")
        } catch let err as CloudSourceError {
            XCTAssertEqual(err.extensionErrorCode, "AUTH_REQUIRED")
        }

        // Verify cookie isolation: root without cookie does NOT receive sid=abc from previous root!
        let countBeforeNoCookie = capturedRequests.value.count
        _ = try await provider.fetchCollection(sourceURL: match.normalizedURL, cookie: nil)
        let reqsAfterNoCookie = capturedRequests.value.dropFirst(countBeforeNoCookie)
        XCTAssertFalse(reqsAfterNoCookie.isEmpty)
        XCTAssertTrue(reqsAfterNoCookie.allSatisfy { $0.value(forHTTPHeaderField: "Cookie") == nil })

        // Verify resolveStream + LiveCloudService routing + CloudExporter.updateLocalFolder
        let service = LiveCloudService(registry: registry)
        let rootA = LibraryRoot(
            id: UUID(),
            name: "Root A",
            kind: .cloudExtension,
            bookmark: nil,
            sourceURL: match.normalizedURL,
            remoteID: "conformance",
            cookie: "sid=rootA",
            providerID: "ext.com.musico.fixture.basic"
        )
        let trackA = Track(
            id: Track.makeID(rootID: rootA.id, relativePath: "01.mp3"),
            rootID: rootA.id,
            relativePath: "01.mp3",
            title: "Track One",
            artist: "Tester",
            album: "",
            albumArtist: "Tester",
            duration: 120,
            fileSize: 0,
            modified: Date(),
            hasEmbeddedLyrics: false,
            dateAdded: Date(),
            cloudSource: "ext.com.musico.fixture.basic",
            remoteTrackID: "t1"
        )
        let stream = try await service.resolveStream(track: trackA, root: rootA, forceRefresh: true)
        XCTAssertEqual(stream.url.absoluteString, "https://edge1.cdn.fixture.test/audio/t1.mp3")
        XCTAssertEqual(stream.fallbackURLs.map(\.absoluteString), ["https://edge2.cdn.fixture.test/audio/t1.mp3"])
        XCTAssertEqual(stream.headers["Referer"], "https://fixture.test/")
        XCTAssertEqual(stream.contentLength, 1_024_000)

        // Verify logs persisted in ExtensionStore (allowing async refresh tick)
        try await Task.sleep(for: .milliseconds(50))
        let recordedLogs = extStore.extension(forID: staged.id)?.logs ?? []
        XCTAssertFalse(recordedLogs.isEmpty)
        XCTAssertTrue(recordedLogs.contains { $0.level == .info && $0.message.contains("fetchCollection start: conformance") })
        XCTAssertTrue(recordedLogs.contains { $0.level == .warn })
        XCTAssertTrue(recordedLogs.contains { $0.level == .error })
        extStore.clearLogs(for: staged.id)
        XCTAssertTrue(extStore.extension(forID: staged.id)?.logs.isEmpty == true)

        // Verify CloudExporter.updateLocalFolder works cleanly with extension root
        let exportFolder = makeTempDir()
        let dlCache = CloudAudioCache(
            directory: makeTempDir(),
            maxBytes: 10_000_000,
            resolver: service,
            downloader: { url, _ in
                let tmp = makeTempDir().appendingPathComponent("dl.mp3")
                try Data("audio-\(url.lastPathComponent)".utf8).write(to: tmp)
                return (tmp, 200, "audio/mpeg")
            }
        )
        let updateSummary = try await CloudExporter.updateLocalFolder(
            root: rootA,
            folderURL: exportFolder,
            fetcher: service,
            cache: dlCache
        )
        XCTAssertEqual(updateSummary.addedCount, 2)
        XCTAssertEqual(updateSummary.failedCount, 0)
        let savedManifest = try XCTUnwrap(CloudExporter.loadManifest(in: exportFolder))
        XCTAssertEqual(savedManifest.cloudKind, .cloudExtension)
        XCTAssertEqual(savedManifest.providerID, "ext.com.musico.fixture.basic")
    }

    // MARK: - Tree Source Bundle (.musicoext ZIP) & Progressive Crawl / Prioritize

    @MainActor
    func testTreeSourceBundleZipImportProgressiveCrawlAndPrioritize() async throws {
        let fixtures = try fixturesDirectory()
        let treeDir = fixtures.appendingPathComponent("tree-source", isDirectory: true)
        let manifestData = try Data(contentsOf: treeDir.appendingPathComponent("manifest.json"))
        let indexData = try Data(contentsOf: treeDir.appendingPathComponent("index.js"))

        let bundleBytes = try TestZipBuilder.buildArchive(entries: [
            .init(name: "manifest.json", data: manifestData, deflate: true),
            .init(name: "index.js", data: indexData, deflate: true)
        ])

        let bundleFile = makeTempDir().appendingPathComponent("tree-source.musicoext")
        try bundleBytes.write(to: bundleFile)

        let capturedExtra = NSLockBox<[String]>([])
        let transport = FixtureTransport { req in
            let url = req.url!
            let comps = URLComponents(url: url, resolvingAgainstBaseURL: true)!
            if comps.host == "cdn.pkg.test", comps.path == "/tree-source.musicoext" {
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "application/zip"], data: bundleBytes, finalURL: url)
            }
            if comps.host == "tree.fixture.test", comps.path == "/api/root/root-1" {
                let body: [String: Any] = [
                    "remoteId": "root-1",
                    "title": "Tree Root",
                    "tracks": [
                        ["remoteTrackId": "rt-1", "relativePath": "intro.mp3", "title": "Intro", "artist": "Root", "album": "Tree", "trackNumber": 1, "durationSec": 30]
                    ],
                    "subfolders": [
                        ["id": "sub-a", "name": "Disc 1", "pathSegments": ["Disc 1"], "extra": ["pageSize": 1]]
                    ],
                    "nextCursor": NSNull()
                ]
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "application/json"], data: try JSONSerialization.data(withJSONObject: body), finalURL: url)
            }
            if comps.host == "tree.fixture.test", comps.path == "/api/folder/sub-a" {
                let cursor = comps.queryItems?.first(where: { $0.name == "cursor" })?.value
                capturedExtra.withLock { $0.append(url.absoluteString) }
                let body: [String: Any]
                if cursor == nil {
                    body = [
                        "folderId": "sub-a",
                        "tracks": [
                            ["remoteTrackId": "sa-1", "relativePath": "Disc 1/01.mp3", "title": "Sub 1", "artist": "Tree", "album": "Disc 1", "trackNumber": 1, "durationSec": 90]
                        ],
                        "subfolders": [],
                        "nextCursor": "sub-p2"
                    ]
                } else {
                    XCTAssertEqual(cursor, "sub-p2")
                    body = [
                        "folderId": "sub-a",
                        "tracks": [
                            ["remoteTrackId": "sa-2", "relativePath": "Disc 1/02.mp3", "title": "Sub 2", "artist": "Tree", "album": "Disc 1", "trackNumber": 2, "durationSec": 95]
                        ],
                        "subfolders": [],
                        "nextCursor": NSNull()
                    ]
                }
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "application/json"], data: try JSONSerialization.data(withJSONObject: body), finalURL: url)
            }
            if comps.host == "tree.fixture.test", comps.path == "/api/stream/sa-2" {
                let body: [String: Any] = [
                    "url": "https://cdn1.cdn.fixture.test/Disc1/02.mp3",
                    "fileExtension": "mp3",
                    "mimeType": "audio/mpeg"
                ]
                return HTTPResponseData(statusCode: 200, headers: ["Content-Type": "application/json"], data: try JSONSerialization.data(withJSONObject: body), finalURL: url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: url)
        }

        let registry = CloudProviderRegistry(transport: transport)
        let extStore = ExtensionStore(baseDirectory: makeTempDir(), registry: registry, transport: transport)

        // HTTP URL import must be rejected (HTTPS only)
        do {
            _ = try await extStore.importFromURL("http://cdn.pkg.test/tree-source.musicoext")
            XCTFail("Expected insecureImportURL")
        } catch ExtensionImportError.insecureImportURL {}

        // File import works and stages disabled until approved
        let installed = try await extStore.importFromFile(at: bundleFile)
        XCTAssertEqual(installed.id, "com.musico.fixture.tree")
        XCTAssertFalse(installed.isActive)

        // HTTPS URL import also succeeds
        let reimported = try await extStore.importFromURL("https://cdn.pkg.test/tree-source.musicoext")
        XCTAssertEqual(reimported.id, "com.musico.fixture.tree")
        extStore.approvePermissionsAndEnable(id: reimported.id)

        // Verify extra JSON forwarded to fetchFolder directly via runtime
        let provider = try XCTUnwrap(registry.provider(forID: "ext.com.musico.fixture.tree") as? JSExtensionCloudProvider)
        let extraPage = try await provider.runtime.fetchFolder(
            folderID: "sub-a",
            pathSegments: ["Disc 1"],
            depth: 1,
            cookie: nil,
            cursor: nil,
            extraJSON: #"{"pageSize":1}"#
        )
        XCTAssertEqual(extraPage.tracks.count, 1)

        // Verify LibraryStore progressive tree crawl + folderContents (non-flat) + prioritizeCloudFolder
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

        try await library.addCloudRoot(sourceURL: "https://tree.fixture.test/root/root-1")
        let root = try XCTUnwrap(library.roots.first { $0.kind == .cloudExtension })
        XCTAssertEqual(root.resolvedProviderID, "ext.com.musico.fixture.tree")

        library.prioritizeCloudFolder(rootID: root.id, path: "Disc 1")
        for _ in 0..<200 {
            if library.cloudSyncProgress[root.id] == nil { break }
            try await Task.sleep(for: .milliseconds(10))
        }

        let rootFolder = library.folderContents(root: root.id, path: "")
        XCTAssertEqual(rootFolder.folders, ["Disc 1"])
        XCTAssertEqual(rootFolder.tracks.map(\.relativePath), ["intro.mp3"])

        let subFolder = library.folderContents(root: root.id, path: "Disc 1")
        XCTAssertEqual(subFolder.tracks.map(\.title), ["Sub 1", "Sub 2"])
        XCTAssertEqual(subFolder.tracks.map(\.relativePath), ["Disc 1/01.mp3", "Disc 1/02.mp3"])

        let subTrack2 = try XCTUnwrap(subFolder.tracks.first { $0.title == "Sub 2" })
        let resolvedStream = try await service.resolveStream(track: subTrack2, root: root, forceRefresh: false)
        XCTAssertEqual(resolvedStream.url.absoluteString, "https://cdn1.cdn.fixture.test/Disc1/02.mp3")
    }

    // MARK: - Security, Redirects, Error Codes, Timeouts & Task Cancellation

    func testSecurityAndErrorsFixtureConformance() async throws {
        let fixtures = try fixturesDirectory()
        let secSource = try String(contentsOf: fixtures.appendingPathComponent("security-and-errors.musico.js"), encoding: .utf8)
        let manifest = try await JSExtensionRuntime.extractManifest(from: secSource)

        let capturedHeaders = NSLockBox<[[String: String]]>([])
        let transport = FixtureTransport { req in
            capturedHeaders.withLock { $0.append(req.allHTTPHeaderFields ?? [:]) }
            let url = req.url!
            if url.host == "fixture.test" && url.path == "/redirect-evil" {
                return HTTPResponseData(
                    statusCode: 302,
                    headers: ["Location": "https://evil.example.com/steal"],
                    data: Data(),
                    finalURL: url
                )
            }
            if url.host == "fixture.test" && url.path == "/redirect-allowed" {
                return HTTPResponseData(
                    statusCode: 302,
                    headers: ["Location": "https://edge1.cdn.fixture.test/ok"],
                    data: Data(),
                    finalURL: url
                )
            }
            if url.host == "edge1.cdn.fixture.test" && url.path == "/ok" {
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "text/plain"],
                    data: Data("edge-ok".utf8),
                    finalURL: url
                )
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: url)
        }

        let runtime = JSExtensionRuntime(
            manifest: manifest,
            scriptSource: secSource,
            transport: transport,
            shortTimeoutMs: 120,
            longTimeoutMs: 120
        )
        let provider = JSExtensionCloudProvider(manifest: manifest, runtime: runtime)

        // Allowlist & redirect blocks
        for mode in ["disallowed-host", "disallowed-scheme", "redirect-escape", "subdomain-exact-miss"] {
            do {
                _ = try await provider.fetchCollection(sourceURL: "https://fixture.test/security/\(mode)", cookie: nil)
                XCTFail("Expected PERMISSION_DENIED for \(mode)")
            } catch let err as CloudSourceError {
                XCTAssertEqual(err.extensionErrorCode, "PERMISSION_DENIED", "Mode \(mode) got \(err)")
            }
        }

        // followRedirects: false returns 302 + lowercase headers.location
        let noFollow = try await provider.fetchCollection(sourceURL: "https://fixture.test/security/redirect-no-follow", cookie: nil)
        XCTAssertEqual(noFollow.title, "status=302")
        XCTAssertEqual(noFollow.tracks.first?.album, "https://evil.example.com/steal")

        // Subdomain hit succeeds
        let subHit = try await provider.fetchCollection(sourceURL: "https://fixture.test/security/subdomain-hit", cookie: nil)
        XCTAssertEqual(subHit.tracks.first?.album, "edge-ok")

        // Structured error codes
        let expectedCodes: [(String, String)] = [
            ("throw-invalid-url", "INVALID_URL"),
            ("throw-auth-required", "AUTH_REQUIRED"),
            ("throw-empty-collection", "EMPTY_COLLECTION"),
            ("throw-no-stream", "NO_PLAYABLE_STREAM"),
            ("throw-rate-limited", "RATE_LIMITED"),
            ("throw-network-error", "NETWORK_ERROR")
        ]
        for (mode, expected) in expectedCodes {
            do {
                _ = try await provider.fetchCollection(sourceURL: "https://fixture.test/security/\(mode)", cookie: nil)
                XCTFail("Expected \(expected) for \(mode)")
            } catch let err as CloudSourceError {
                XCTAssertEqual(err.extensionErrorCode, expected, "Mode \(mode) got \(err)")
            }
        }

        // Stream URL & fallbackUrls host allowlist checks
        for badTrackID in ["bad-stream-host", "bad-fallback-host"] {
            do {
                _ = try await runtime.resolveStream(remoteTrackID: badTrackID, relativePath: nil, cookie: nil, forceRefresh: false)
                XCTFail("Expected PERMISSION_DENIED for \(badTrackID)")
            } catch let err as CloudSourceError {
                XCTAssertEqual(err.extensionErrorCode, "PERMISSION_DENIED")
            }
        }
        let validStream = try await runtime.resolveStream(remoteTrackID: "good", relativePath: nil, cookie: nil, forceRefresh: false)
        XCTAssertEqual(validStream.url, "https://media1.cdn.fixture.test/audio.mp3")

        // Async hang timeout
        do {
            _ = try await runtime.fetchCollection(url: "https://fixture.test/security/async-hang", cookie: nil, cursor: nil)
            XCTFail("Expected timeout error for async-hang")
        } catch let err as CloudSourceError {
            XCTAssertEqual(err.extensionErrorCode, "EXTENSION_RUNTIME_ERROR")
        }

        // Task cancellation aborts suspended JS hook promptly
        let longRuntime = JSExtensionRuntime(
            manifest: manifest,
            scriptSource: secSource,
            transport: transport,
            shortTimeoutMs: 10_000,
            longTimeoutMs: 10_000
        )
        let hangTask = Task {
            try await longRuntime.fetchCollection(url: "https://fixture.test/security/async-hang", cookie: nil, cursor: nil)
        }
        try await Task.sleep(for: .milliseconds(30))
        hangTask.cancel()
        do {
            _ = try await hangTask.value
            XCTFail("Expected CancellationError")
        } catch is CancellationError {}
    }

    // MARK: - HTTP Redirect Header Stripping, Body Cap, Base64, KV TTL, Log Ring Buffer, Select Options

    func testHTTPRedirectHeaderStrippingCapsKVTTLAndLogRingBuffer() async throws {
        let script = """
        var manifest = {
          apiVersion: 1,
          id: 'com.test.bridge',
          name: 'Bridge Test',
          version: '1.0.0',
          capabilities: ['playlist', 'stream'],
          urlPatterns: ['^https?://fixture\\\\.test/bridge/.+$'],
          permissions: { hosts: ['fixture.test', '*.cdn.fixture.test'] },
          settings: [
            {
              key: 'tier',
              label: 'Tier',
              type: 'select',
              default: 'free',
              options: ['free', 'pro']
            }
          ]
        };
        function canHandle(input) {
          return { matched: true, normalizedUrl: input.extractedUrl, remoteId: 'bridge', kind: 'playlist' };
        }
        async function fetchCollection(ctx, req) {
          var r1 = await ctx.http.request({
            method: 'GET',
            url: 'https://fixture.test/redirect-allowed',
            headers: { Cookie: 'secret=1', Authorization: 'Bearer xyz', 'X-Custom': 'keep' },
            followRedirects: true
          });
          var rLarge = await ctx.http.request({
            method: 'GET',
            url: 'https://edge1.cdn.fixture.test/large'
          });
          var rLargeForced = await ctx.http.request({
            method: 'GET',
            url: 'https://edge1.cdn.fixture.test/large',
            returnBytes: true
          });
          var rCapped = await ctx.http.request({
            method: 'GET',
            url: 'https://edge1.cdn.fixture.test/huge'
          });
          ctx.kv.set('ttlKey', 'alive', 0.5);
          var immediate = ctx.kv.get('ttlKey');
          await new Promise(function(resolve) { setTimeout(resolve, 700); });
          var afterExpire = ctx.kv.get('ttlKey');
          for (var i = 0; i < 210; i++) {
            ctx.log.info('entry-' + String(i));
          }
          return {
            remoteId: 'bridge',
            title: String(ctx.settings.get('tier')),
            tracks: [{
              remoteTrackId: '1',
              relativePath: '1.mp3',
              title: [
                'status=' + r1.status,
                'b64Small=' + (typeof r1.bytesBase64 === 'string'),
                'b64LargeDefault=' + (rLarge.bytesBase64 === undefined),
                'b64LargeForced=' + (typeof rLargeForced.bytesBase64 === 'string'),
                'hugeLen=' + String(rCapped.text.length),
                'ttl=' + String(immediate) + '/' + String(afterExpire)
              ].join('|'),
              artist: 'Test',
              album: '',
              trackNumber: 1,
              durationSec: 10
            }]
          };
        }
        async function resolveStream() {
          return { url: 'https://edge1.cdn.fixture.test/1.mp3', fileExtension: 'mp3' };
        }
        module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection, resolveStream: resolveStream };
        """

        let hostRequests = NSLockBox<[URLRequest]>([])
        let transport = FixtureTransport { req in
            hostRequests.withLock { $0.append(req) }
            let url = req.url!
            if url.host == "fixture.test" && url.path == "/redirect-allowed" {
                return HTTPResponseData(
                    statusCode: 302,
                    headers: ["Location": "https://edge1.cdn.fixture.test/target"],
                    data: Data(),
                    finalURL: url
                )
            }
            if url.host == "edge1.cdn.fixture.test" && url.path == "/target" {
                return HTTPResponseData(statusCode: 200, headers: ["X-MixedCase": "Val"], data: Data("ok".utf8), finalURL: url)
            }
            if url.host == "edge1.cdn.fixture.test" && url.path == "/large" {
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(repeating: 0x41, count: 1_500_000), finalURL: url)
            }
            if url.host == "edge1.cdn.fixture.test" && url.path == "/huge" {
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(repeating: 0x42, count: 9_000_000), finalURL: url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: url)
        }

        let manifest = try await JSExtensionRuntime.extractManifest(from: script)
        XCTAssertEqual(manifest.settings?.first?.options?.map(\.value), ["free", "pro"])

        let host = ExtensionHostStorage(baseDirectory: makeTempDir())
        let runtime = JSExtensionRuntime(manifest: manifest, scriptSource: script, transport: transport, hostDelegate: host)
        let page = try await runtime.fetchCollection(url: "https://fixture.test/bridge/1", cookie: nil, cursor: nil)

        XCTAssertEqual(page.title, "free")
        let summary = page.tracks[0].title
        XCTAssertTrue(summary.contains("status=200"), summary)
        XCTAssertTrue(summary.contains("b64Small=true"), summary)
        XCTAssertTrue(summary.contains("b64LargeDefault=true"), summary)
        XCTAssertTrue(summary.contains("b64LargeForced=true"), summary)
        XCTAssertTrue(summary.contains("hugeLen=\(8 * 1024 * 1024)"), summary)
        XCTAssertTrue(summary.contains("ttl=alive/null"), summary)

        // Verify Cookie and Authorization stripped across host redirect while X-Custom kept
        let redirectTargetReq = try XCTUnwrap(hostRequests.value.first { $0.url?.path == "/target" })
        XCTAssertNil(redirectTargetReq.value(forHTTPHeaderField: "Cookie"))
        XCTAssertNil(redirectTargetReq.value(forHTTPHeaderField: "Authorization"))
        XCTAssertEqual(redirectTargetReq.value(forHTTPHeaderField: "X-Custom"), "keep")

        // Ring buffer capped at 200 entries keeping latest
        let logs = host.logs(for: manifest.id)
        XCTAssertEqual(logs.count, 200)
        XCTAssertEqual(logs.first?.message, "entry-10")
        XCTAssertEqual(logs.last?.message, "entry-209")
    }

    // MARK: - ZIP Reader Security & Host Allowlist Validation

    func testArchiveReaderRejectsPathTraversalTooManyEntriesAndOversize() throws {
        // 1. ../ path traversal
        let traversalZip = try TestZipBuilder.buildArchive(entries: [
            .init(name: "../evil.js", data: Data("x".utf8), deflate: false)
        ])
        XCTAssertThrowsError(try ExtensionArchiveReader.extractEntries(from: traversalZip)) { error in
            XCTAssertTrue(error is ExtensionArchiveError)
        }

        // 2. Absolute path
        let absZip = try TestZipBuilder.buildArchive(entries: [
            .init(name: "/etc/passwd", data: Data("root".utf8), deflate: false)
        ])
        XCTAssertThrowsError(try ExtensionArchiveReader.extractEntries(from: absZip))

        // 3. >256 entries
        let tooMany = (0..<257).map { i in
            TestZipBuilder.Entry(name: "f\(i).txt", data: Data("a".utf8), deflate: false)
        }
        let manyZip = try TestZipBuilder.buildArchive(entries: tooMany)
        XCTAssertThrowsError(try ExtensionArchiveReader.extractEntries(from: manyZip)) { error in
            XCTAssertEqual(error as? ExtensionArchiveError, .tooManyEntries(256))
        }

        // 4. >16 MB uncompressed size
        let hugeData = Data(repeating: 0x41, count: 16 * 1024 * 1024 + 1)
        let oversizeZip = try TestZipBuilder.buildArchive(entries: [
            .init(name: "huge.txt", data: hugeData, deflate: true)
        ])
        XCTAssertThrowsError(try ExtensionArchiveReader.extractEntries(from: oversizeZip))

        // 5. Stored (method 0) valid archive works
        let storedZip = try TestZipBuilder.buildArchive(entries: [
            .init(name: "hello.txt", data: Data("world".utf8), deflate: false)
        ])
        let extracted = try ExtensionArchiveReader.extractEntries(from: storedZip)
        XCTAssertEqual(extracted["hello.txt"], Data("world".utf8))
    }

    func testHostAllowlistMatcherEdgeCases() {
        XCTAssertThrowsError(try HostAllowlistMatcher.validatePattern("*"))
        XCTAssertThrowsError(try HostAllowlistMatcher.validatePattern("*.com"))
        XCTAssertThrowsError(try HostAllowlistMatcher.validatePattern("https://example.com"))
        XCTAssertEqual(try HostAllowlistMatcher.validatePattern("*.CDN.Example.com."), "*.cdn.example.com")

        let patterns = ["example.com", "*.cdn.example.com"]
        XCTAssertTrue(HostAllowlistMatcher.isAllowed(urlString: "https://example.com/path", patterns: patterns))
        XCTAssertTrue(HostAllowlistMatcher.isAllowed(urlString: "http://edge1.cdn.example.com:8080/a", patterns: patterns))
        XCTAssertFalse(HostAllowlistMatcher.isAllowed(urlString: "https://cdn.example.com/a", patterns: patterns))
        XCTAssertFalse(HostAllowlistMatcher.isAllowed(urlString: "https://sub.example.com/a", patterns: patterns))
        XCTAssertFalse(HostAllowlistMatcher.isAllowed(urlString: "file:///etc/passwd", patterns: patterns))
        XCTAssertFalse(HostAllowlistMatcher.isAllowed(urlString: "javascript:alert(1)", patterns: patterns))
    }

    @MainActor
    func testQRLoginManifestRequiresBothHooks() async {
        let missingPollScript = """
        var manifest = {
          apiVersion: 1,
          id: 'com.example.qr.missingpoll',
          name: 'Missing Poll',
          version: '1.0.0',
          capabilities: ['qr_login'],
          permissions: { hosts: ['auth.fixture.test'] }
        };
        async function startQrLogin() {
          return { sessionId: 's1', qrContent: 'https://auth.fixture.test/qr/s1' };
        }
        module.exports = { manifest: manifest, startQrLogin: startQrLogin };
        """
        do {
            _ = try await JSExtensionRuntime.extractManifest(from: missingPollScript)
            XCTFail("Expected missing pollQrLogin validation error")
        } catch {
            XCTAssertTrue(error is ExtensionImportError, "Unexpected error: \(error)")
        }
    }

    @MainActor
    func testQRLoginLifecycleSetCookiesAndAutoPersist() async throws {
        let script = """
        'use strict';
        var manifest = {
          apiVersion: 1,
          id: 'com.example.qr.liveflow',
          name: 'QR Flow',
          version: '1.0.0',
          capabilities: ['search', 'qr_login'],
          searchKinds: ['track'],
          permissions: { hosts: ['auth.fixture.test'] }
        };
        async function startQrLogin(ctx) {
          var res = await ctx.http.request({ method: 'GET', url: 'https://auth.fixture.test/qr/start' });
          var data = JSON.parse(res.text || '{}');
          return { sessionId: data.key, qrContent: data.url, pollIntervalMs: 300 };
        }
        async function pollQrLogin(ctx, req) {
          var res = await ctx.http.request({
            method: 'GET',
            url: 'https://auth.fixture.test/qr/poll?key=' + encodeURIComponent(req.sessionId)
          });
          var data = JSON.parse(res.text || '{}');
          if (data.status === 'confirmed') {
            var setCookies = Array.isArray(res.setCookies) ? res.setCookies : [];
            var musicU = '';
            for (var i = 0; i < setCookies.length; i++) {
              if (String(setCookies[i]).indexOf('MUSIC_U=') === 0) {
                musicU = String(setCookies[i]).split(';')[0];
                break;
              }
            }
            return { status: 'confirmed', cookie: musicU, accountName: data.nickname };
          }
          return { status: data.status, accountName: data.nickname || null, message: data.message || null };
        }
        async function search(ctx, req) {
          return {
            items: [{
              kind: 'track',
              remoteTrackId: 't-qr-1',
              title: (req.cookie === 'MUSIC_U=qr_secret_token_99') ? 'authed' : 'anon',
              artist: 'Tester'
            }],
            nextCursor: null
          };
        }
        module.exports = {
          manifest: manifest,
          startQrLogin: startQrLogin,
          pollQrLogin: pollQrLogin,
          search: search
        };
        """

        let pollIndex = NSLockBox(0)
        let transport = FixtureTransport { req in
            let url = req.url!
            if url.path == "/qr/start" {
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "application/json"],
                    data: Data("{\"key\":\"sess-abc\",\"url\":\"https://music.163.com/login?codekey=test-uuid-1234567890\"}".utf8),
                    finalURL: url
                )
            }
            if url.path == "/qr/poll" {
                let idx = pollIndex.withLock { val -> Int in
                    let cur = val
                    val += 1
                    return cur
                }
                if idx == 0 {
                    return HTTPResponseData(
                        statusCode: 200,
                        headers: ["Content-Type": "application/json"],
                        data: Data("{\"status\":\"pending\"}".utf8),
                        finalURL: url
                    )
                }
                if idx == 1 {
                    return HTTPResponseData(
                        statusCode: 200,
                        headers: ["Content-Type": "application/json"],
                        data: Data("{\"status\":\"scanned\",\"nickname\":\"QR Tester\"}".utf8),
                        finalURL: url
                    )
                }
                return HTTPResponseData(
                    statusCode: 200,
                    headers: [
                        "Content-Type": "application/json",
                        "Set-Cookie": "NMTID=n1; Path=/; Expires=Thu, 01 Jan 2099 00:00:00 GMT, MUSIC_U=qr_secret_token_99; Path=/; HttpOnly"
                    ],
                    data: Data("{\"status\":\"confirmed\",\"nickname\":\"QR Tester\"}".utf8),
                    finalURL: url
                )
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: url)
        }

        let dir = makeTempDir()
        let store = ExtensionStore(baseDirectory: dir, registry: CloudProviderRegistry(builtIns: []), transport: transport)
        let installed = try await store.importPackageData(Data(script.utf8), fileName: "qr.musico.js", sourceURL: nil)
        store.approvePermissionsAndEnable(id: installed.id)

        let session = try await store.startQRLogin(extensionID: installed.id)
        XCTAssertEqual(session.sessionId, "sess-abc")
        XCTAssertEqual(session.pollIntervalMs, 1000)

        let qrImage = try XCTUnwrap(ExtensionQRLoginSheet.makeQRCodeImage(from: session.qrContent))
        let ciImage = try XCTUnwrap(CIImage(image: qrImage))
        let detector = try XCTUnwrap(CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh]))
        let decodedQR = detector.features(in: ciImage).compactMap { ($0 as? CIQRCodeFeature)?.messageString }.first
        XCTAssertEqual(decodedQR, "https://music.163.com/login?codekey=test-uuid-1234567890")

        let pending = try await store.pollQRLogin(extensionID: installed.id, sessionID: session.sessionId)
        XCTAssertEqual(pending.status, .pending)
        XCTAssertNil(store.cookie(forExtensionID: installed.id))

        let scanned = try await store.pollQRLogin(extensionID: installed.id, sessionID: session.sessionId)
        XCTAssertEqual(scanned.status, .scanned)
        XCTAssertEqual(scanned.accountName, "QR Tester")

        let confirmed = try await store.pollQRLogin(extensionID: installed.id, sessionID: session.sessionId)
        XCTAssertEqual(confirmed.status, .confirmed)
        XCTAssertEqual(confirmed.cookie, "MUSIC_U=qr_secret_token_99")
        XCTAssertEqual(store.cookie(forExtensionID: installed.id), "MUSIC_U=qr_secret_token_99")
        XCTAssertEqual(store.extension(forID: installed.id)?.state.cookie, "MUSIC_U=qr_secret_token_99")

        let searchPage = try await store.search(extensionID: installed.id, query: "test", kind: .track, limit: 10, cursor: nil)
        XCTAssertEqual(searchPage.items.first?.title, "authed")

        let logsText = (store.extension(forID: installed.id)?.logs ?? []).map(\.message).joined(separator: "\n")
        XCTAssertFalse(logsText.contains("qr_secret_token_99"))
    }
}

private final class NSLockBox<Value>: @unchecked Sendable {
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
