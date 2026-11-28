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

        // 3. >maxEntries
        let tooMany = (0..<(ExtensionArchiveReader.maxEntries + 1)).map { i in
            TestZipBuilder.Entry(name: "f\(i).txt", data: Data("a".utf8), deflate: false)
        }
        let manyZip = try TestZipBuilder.buildArchive(entries: tooMany)
        XCTAssertThrowsError(try ExtensionArchiveReader.extractEntries(from: manyZip)) { error in
            XCTAssertEqual(error as? ExtensionArchiveError, .tooManyEntries(ExtensionArchiveReader.maxEntries))
        }

        // 4. >maxUncompressedBytes uncompressed size
        let hugeData = Data(repeating: 0x41, count: ExtensionArchiveReader.maxUncompressedBytes + 1)
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

    @MainActor
    func testQRLoginLowercaseSetCookieAndExtensionCookieFallbackForFolderAndStream() async throws {
        let script = """
        'use strict';
        var manifest = {
          apiVersion: 1,
          id: 'com.example.qr.fallback',
          name: 'QR Cookie Fallback',
          version: '1.0.0',
          capabilities: ['playlist', 'stream', 'qr_login', 'cookie'],
          urlPatterns: ['^https?://auth\\\\.fixture\\\\.test/playlist/'],
          permissions: { hosts: ['auth.fixture.test'] }
        };
        function canHandle(input) {
          var url = (input && (input.extractedUrl || input.rawText)) || '';
          if (url.indexOf('https://auth.fixture.test/playlist/') === 0) {
            return { matched: true, normalizedUrl: url, remoteId: 'pl-1', kind: 'playlist' };
          }
          return { matched: false };
        }
        async function startQrLogin() {
          return { sessionId: 'sess-lower', qrContent: 'https://music.163.com/login?codekey=lower-1' };
        }
        async function pollQrLogin(ctx, req) {
          var res = await ctx.http.request({
            method: 'GET',
            url: 'https://auth.fixture.test/qr/poll?key=' + encodeURIComponent(req.sessionId)
          });
          var list = Array.isArray(res.setCookies) ? res.setCookies : [];
          var pairs = [];
          for (var i = 0; i < list.length; i++) {
            var p = String(list[i]).split(';')[0].trim();
            if (p.indexOf('MUSIC_U=') === 0 || p.indexOf('__csrf=') === 0) pairs.push(p);
          }
          return { status: 'confirmed', cookie: pairs.join('; '), accountName: 'LowerUser' };
        }
        async function fetchCollection(ctx, req) {
          return {
            remoteId: 'pl-1',
            title: 'Fallback Playlist',
            tracks: [{
              remoteTrackId: 'tr-1',
              relativePath: '01.mp3',
              title: req.cookie ? ('col:' + req.cookie) : 'col:anon',
              artist: 'Tester',
              durationSec: 120
            }]
          };
        }
        async function resolveStream(ctx, req) {
          return {
            url: 'https://auth.fixture.test/audio/' + encodeURIComponent(req.cookie || 'anon') + '.mp3',
            fileExtension: 'mp3'
          };
        }
        module.exports = {
          manifest: manifest,
          canHandle: canHandle,
          startQrLogin: startQrLogin,
          pollQrLogin: pollQrLogin,
          fetchCollection: fetchCollection,
          resolveStream: resolveStream
        };
        """

        let transport = FixtureTransport { req in
            let url = req.url!
            if url.path == "/qr/poll" {
                return HTTPResponseData(
                    statusCode: 200,
                    headers: [
                        "content-type": "application/json",
                        "set-cookie": "NMTID=n1; Expires=Tue, 25 Nov 2036 12:41:38 GMT; Path=/, MUSIC_U=lower_token_abc; Max-Age=1296000; Expires=Sat, 12 Dec 2026 12:41:38 GMT; Path=/; Domain=.music.163.com; HttpOnly, __csrf=csrf_xyz; Path=/"
                    ],
                    data: Data("{\"code\":803}".utf8),
                    finalURL: url
                )
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: url)
        }

        let dir = makeTempDir()
        let registry = CloudProviderRegistry(builtIns: [])
        let store = ExtensionStore(baseDirectory: dir, registry: registry, transport: transport)
        let installed = try await store.importPackageData(Data(script.utf8), fileName: "fallback.musico.js", sourceURL: nil)
        store.approvePermissionsAndEnable(id: installed.id)

        let provider = try XCTUnwrap(registry.provider(forID: installed.providerID) as? JSExtensionCloudProvider)

        // When no cookie is stored on either root or extension, cookie remains isolated (nil)
        let colNoCookie = try await provider.fetchCollection(sourceURL: "https://auth.fixture.test/playlist/1", cookie: nil)
        XCTAssertEqual(colNoCookie.tracks.first?.title, "col:anon")

        // QR login with lowercase HTTP/2 'set-cookie' extracts both tokens and saves to ExtensionStore
        let poll = try await store.pollQRLogin(extensionID: installed.id, sessionID: "sess-lower")
        XCTAssertEqual(poll.status, .confirmed)
        XCTAssertEqual(poll.cookie, "MUSIC_U=lower_token_abc; __csrf=csrf_xyz")
        XCTAssertEqual(store.cookie(forExtensionID: installed.id), "MUSIC_U=lower_token_abc; __csrf=csrf_xyz")

        // Stored extension cookie acts as fallback when folder/root cookie is nil
        let colFallback = try await provider.fetchCollection(sourceURL: "https://auth.fixture.test/playlist/1", cookie: nil)
        XCTAssertEqual(colFallback.tracks.first?.title, "col:MUSIC_U=lower_token_abc; __csrf=csrf_xyz")

        let rootWithoutCookie = LibraryRoot(
            id: UUID(),
            name: "Imported Folder",
            kind: .cloudExtension,
            bookmark: nil,
            sourceURL: "https://auth.fixture.test/playlist/1",
            remoteID: "pl-1",
            cookie: nil,
            providerID: installed.providerID
        )
        let track = Track(
            id: Track.makeID(rootID: rootWithoutCookie.id, relativePath: "01.mp3"),
            rootID: rootWithoutCookie.id,
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
            cloudSource: installed.providerID,
            remoteTrackID: "tr-1"
        )
        let streamFallback = try await provider.resolveStream(track: track, root: rootWithoutCookie, forceRefresh: true)
        XCTAssertTrue(streamFallback.url.absoluteString.contains("MUSIC_U%3Dlower_token_abc"), streamFallback.url.absoluteString)

        // Per-folder cookie still overrides the extension-level cookie
        var rootWithCookie = rootWithoutCookie
        rootWithCookie.cookie = "MUSIC_U=folder_override"
        let streamOverride = try await provider.resolveStream(track: track, root: rootWithCookie, forceRefresh: true)
        XCTAssertTrue(streamOverride.url.absoluteString.contains("MUSIC_U%3Dfolder_override"), streamOverride.url.absoluteString)
    }

    // MARK: - Extension Auto-Updates, SemVer, UpdateUrl & Permission Escalation Guard

    func testExtensionManifestUpdateUrlAndSemVerValidation() throws {
        // HTTPS updateUrl succeeds
        let validJSON = """
        {
          "apiVersion": 1,
          "id": "com.test.upd",
          "name": "Updater",
          "version": "1.2.3",
          "updateUrl": "https://cdn.example.test/updater.musicoext",
          "capabilities": ["search"],
          "permissions": { "hosts": ["api.example.test"] }
        }
        """
        let valid = try JSONDecoder().decode(ExtensionManifest.self, from: Data(validJSON.utf8))
        try valid.validate()
        XCTAssertEqual(valid.validatedUpdateURL?.absoluteString, "https://cdn.example.test/updater.musicoext")

        // HTTP updateUrl rejected
        let httpJSON = validJSON.replacingOccurrences(of: "https://", with: "http://")
        let insecure = try JSONDecoder().decode(ExtensionManifest.self, from: Data(httpJSON.utf8))
        XCTAssertThrowsError(try insecure.validate()) { error in
            XCTAssertEqual(error as? ExtensionManifestError, .invalidUpdateURL("http://cdn.example.test/updater.musicoext"))
        }

        // SemVer ordering
        XCTAssertLessThan(ExtensionSemVer("1.0.0-alpha")!, ExtensionSemVer("1.0.0-beta")!)
        XCTAssertLessThan(ExtensionSemVer("1.0.0-beta")!, ExtensionSemVer("1.0.0")!)
        XCTAssertLessThan(ExtensionSemVer("1.0.0")!, ExtensionSemVer("1.0.1")!)
        XCTAssertLessThan(ExtensionSemVer("1.2.9")!, ExtensionSemVer("1.10.0")!)
        XCTAssertEqual(ExtensionSemVer("1.2.0+build1"), ExtensionSemVer("1.2.0+build2"))
    }

    @MainActor
    func testExtensionAutoUpdateThrottlePreferenceSilentApplyAndEscalationGuard() async throws {
        func makeScript(id: String, version: String, updateUrl: String?, hosts: [String], caps: [String]) -> String {
            let hostsJSON = hosts.map { "\"\($0)\"" }.joined(separator: ", ")
            let capsJSON = caps.map { "\"\($0)\"" }.joined(separator: ", ")
            let updateLine = updateUrl.map { "  updateUrl: \"\($0)\",\n" } ?? ""
            return """
            exports.manifest = {
              apiVersion: 1,
              id: "\(id)",
              name: "AutoExt",
              version: "\(version)",
            \(updateLine)  capabilities: [\(capsJSON)],
              permissions: { hosts: [\(hostsJSON)] }
            };
            exports.search = async function() { return { items: [] }; };
            exports.resolveStream = async function() { return { url: "https://api.example.test/s.mp3", fileExtension: "mp3" }; };
            """
        }

        let suiteName = "ExtensionAutoUpdateTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let remoteBody = NSLockBox<String>(makeScript(
            id: "com.test.auto",
            version: "1.0.0",
            updateUrl: "https://upd.example.test/latest.musico.js",
            hosts: ["api.example.test"],
            caps: ["search"]
        ))
        let requestedURLs = NSLockBox<[String]>([])

        let transport = FixtureTransport { req in
            let url = req.url!
            requestedURLs.withLock { $0.append(url.absoluteString) }
            if url.host == "install.example.test" || url.host == "upd.example.test" {
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "application/javascript"],
                    data: Data(remoteBody.value.utf8),
                    finalURL: url
                )
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: url)
        }

        let baseDir = makeTempDir()
        let store = ExtensionStore(
            baseDirectory: baseDir,
            registry: CloudProviderRegistry(builtIns: []),
            transport: transport,
            defaults: defaults
        )
        XCTAssertTrue(store.autoUpdateEnabled)

        let installed = try await store.importFromURL("https://install.example.test/v1.musico.js")
        store.approvePermissionsAndEnable(id: installed.id)
        XCTAssertEqual(store.updateSourceURL(for: installed)?.absoluteString, "https://upd.example.test/latest.musico.js")

        // Mismatched ID is rejected cleanly
        remoteBody.withLock {
            $0 = makeScript(
                id: "com.test.other",
                version: "2.0.0",
                updateUrl: "https://upd.example.test/latest.musico.js",
                hosts: ["api.example.test"],
                caps: ["search"]
            )
        }
        await store.checkForUpdate(extensionID: installed.id)
        XCTAssertEqual(store.extension(forID: installed.id)?.manifest.version, "1.0.0")
        if case .error = store.updateStatusByID[installed.id] {} else {
            XCTFail("Expected error status for mismatched ID")
        }

        // Unchanged/reduced permissions + newer SemVer -> silent in-place update preserving enabled state
        remoteBody.withLock {
            $0 = makeScript(
                id: "com.test.auto",
                version: "1.1.0",
                updateUrl: "https://upd.example.test/latest.musico.js",
                hosts: ["api.example.test"],
                caps: ["search"]
            )
        }
        let t0 = Date(timeIntervalSince1970: 1_700_000_000)
        requestedURLs.withLock { $0.removeAll() }
        await store.checkForUpdatesIfNeeded(now: t0)
        XCTAssertEqual(requestedURLs.value, ["https://upd.example.test/latest.musico.js"])
        XCTAssertEqual(store.extension(forID: installed.id)?.manifest.version, "1.1.0")
        XCTAssertTrue(store.extension(forID: installed.id)?.isActive ?? false)
        XCTAssertNil(store.pendingUpdates[installed.id])

        // Throttled within 6 hours -> does not fetch again
        requestedURLs.withLock { $0.removeAll() }
        await store.checkForUpdatesIfNeeded(now: t0.addingTimeInterval(3 * 60 * 60))
        XCTAssertTrue(requestedURLs.value.isEmpty)

        // Expanded permissions after 6 hours -> staged for Review Update instead of auto-applying
        remoteBody.withLock {
            $0 = makeScript(
                id: "com.test.auto",
                version: "1.2.0",
                updateUrl: "https://upd.example.test/latest.musico.js",
                hosts: ["api.example.test", "new.example.test"],
                caps: ["search", "stream"]
            )
        }
        await store.checkForUpdatesIfNeeded(now: t0.addingTimeInterval(6 * 60 * 60 + 1))
        XCTAssertEqual(store.extension(forID: installed.id)?.manifest.version, "1.1.0")
        let pending = try XCTUnwrap(store.pendingUpdates[installed.id])
        XCTAssertEqual(pending.manifest.version, "1.2.0")
        XCTAssertEqual(pending.addedHosts, ["new.example.test"])
        XCTAssertEqual(pending.addedCapabilities, [.stream])

        // Approving staged update applies v1.2.0 and enables it
        let approved = store.approvePendingUpdate(id: installed.id)
        XCTAssertEqual(approved?.manifest.version, "1.2.0")
        XCTAssertTrue(approved?.isActive ?? false)
        XCTAssertNil(store.pendingUpdates[installed.id])

        // Disabling autoUpdateEnabled skips background checks even past 6 hours
        store.autoUpdateEnabled = false
        requestedURLs.withLock { $0.removeAll() }
        await store.checkForUpdatesIfNeeded(now: t0.addingTimeInterval(20 * 60 * 60))
        XCTAssertTrue(requestedURLs.value.isEmpty)
    }

    func testServerURLSettingDynamicallyAllowsConfiguredHostWhileBlockingOthers() async throws {
        let script = """
        'use strict';
        var manifest = {
          apiVersion: 1,
          id: 'com.test.serverurl',
          name: 'Server URL Ext',
          version: '1.0.0',
          capabilities: ['search', 'stream'],
          permissions: { hosts: [] },
          settings: [
            { key: 'serverUrl', label: 'Server URL', type: 'server_url', default: '' }
          ]
        };
        async function search(ctx, req) {
          var base = String(ctx.settings.get('serverUrl') || '').replace(/\\/+$/, '');
          var res = await ctx.http.request({ method: 'GET', url: base + '/v1/search?q=' + encodeURIComponent(req.query) });
          var data = JSON.parse(res.text);
          return {
            items: [{ kind: 'track', remoteTrackId: data.id, title: data.title }],
            nextCursor: null
          };
        }
        async function resolveStream(ctx, req) {
          var base = String(ctx.settings.get('serverUrl') || '').replace(/\\/+$/, '');
          if (req.remoteTrackId === 'evil') {
            return { url: 'https://evil.example.test/stream.m4a', fileExtension: 'm4a' };
          }
          return { url: base + '/v1/tracks/' + req.remoteTrackId + '/stream', fileExtension: 'm4a' };
        }
        module.exports = { manifest: manifest, search: search, resolveStream: resolveStream };
        """

        let manifest = try await JSExtensionRuntime.extractManifest(from: script)
        XCTAssertTrue(manifest.hasServerURLSetting)
        XCTAssertEqual(manifest.settings?.first?.type, .serverURL)
        XCTAssertNil(HostAllowlistMatcher.extractApprovedHost(fromServerURL: "file:///etc/passwd"))
        XCTAssertNil(HostAllowlistMatcher.extractApprovedHost(fromServerURL: "https://*.example.com"))
        XCTAssertNil(HostAllowlistMatcher.extractApprovedHost(fromServerURL: "not a url"))
        XCTAssertEqual(HostAllowlistMatcher.extractApprovedHost(fromServerURL: "https://am.local.test:8787/api"), "am.local.test")
        XCTAssertEqual(HostAllowlistMatcher.extractApprovedHost(fromServerURL: "http://127.0.0.1:8787"), "127.0.0.1")

        let transport = FixtureTransport { req in
            let payload = #"{"id":"tr-9","title":"Server OK"}"#
            return HTTPResponseData(
                statusCode: 200,
                headers: ["Content-Type": "application/json"],
                data: Data(payload.utf8),
                finalURL: req.url
            )
        }

        // Valid HTTPS server URL with port
        let delegate = InMemoryExtensionHostDelegate(settings: [
            manifest.id: ["serverUrl": .string("https://am.local.test:8787/")]
        ])
        let runtime = JSExtensionRuntime(manifest: manifest, scriptSource: script, transport: transport, hostDelegate: delegate)
        let page = try await runtime.search(query: "test")
        XCTAssertEqual(page.items.first?.title, "Server OK")

        let stream = try await runtime.resolveStream(remoteTrackID: "tr-9", relativePath: nil, cookie: nil, forceRefresh: false)
        XCTAssertEqual(stream.url, "https://am.local.test:8787/v1/tracks/tr-9/stream")

        do {
            _ = try await runtime.resolveStream(remoteTrackID: "evil", relativePath: nil, cookie: nil, forceRefresh: false)
            XCTFail("Expected unrelated stream host to be rejected")
        } catch let err as CloudSourceError {
            guard case .permissionDenied = err else {
                return XCTFail("Expected permissionDenied, got \(err)")
            }
        }

        // Invalid serverUrl grants no host permission
        let badDelegate = InMemoryExtensionHostDelegate(settings: [
            manifest.id: ["serverUrl": .string("https://*.wildcard.test")]
        ])
        let badRuntime = JSExtensionRuntime(manifest: manifest, scriptSource: script, transport: transport, hostDelegate: badDelegate)
        do {
            _ = try await badRuntime.resolveStream(remoteTrackID: "tr-9", relativePath: nil, cookie: nil, forceRefresh: false)
            XCTFail("Expected invalid wildcard serverUrl to grant no host permission")
        } catch let err as CloudSourceError {
            guard case .permissionDenied = err else {
                return XCTFail("Expected permissionDenied, got \(err)")
            }
        }
    }

    @MainActor
    func testManifestQualitiesValidationAndResolveStreamQualityPurpose() async throws {
        // 1. Valid qualities + defaults
        let validScript = """
        'use strict';
        var manifest = {
          apiVersion: 1,
          id: 'com.test.qualities',
          name: 'Quality Ext',
          version: '1.0.0',
          capabilities: ['playlist', 'stream'],
          urlPatterns: ['^https://q\\\\.test/list/[a-z0-9]+$'],
          permissions: { hosts: ['q.test'] },
          qualities: [
            { id: 'highest', label: 'Highest (FLAC)', badge: 'FLAC', lossless: true },
            { id: 'standard', label: 'Standard (128k)', badge: '128k', lossless: false }
          ],
          defaultStreamQuality: 'standard',
          defaultDownloadQuality: 'highest'
        };
        function canHandle(input) {
          var m = String(input.extractedUrl || '').match(/\\/list\\/([a-z0-9]+)$/);
          return m ? { matched: true, normalizedUrl: 'https://q.test/list/' + m[1], remoteId: m[1], kind: 'playlist' } : { matched: false };
        }
        async function fetchCollection(ctx, req) {
          return { remoteId: '1', title: 'Q', tracks: [] };
        }
        async function resolveStream(ctx, req) {
          return {
            url: 'https://q.test/audio/' + req.remoteTrackId + '?q=' + (req.quality || '') + '&p=' + (req.purpose || ''),
            fileExtension: req.quality === 'highest' ? 'flac' : 'm4a',
            actualQuality: req.quality,
            qualityLabel: req.quality === 'highest' ? 'FLAC' : '128k',
            bitrateKbps: req.quality === 'highest' ? 980 : 128
          };
        }
        module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection, resolveStream: resolveStream };
        """
        let manifest = try await JSExtensionRuntime.extractManifest(from: validScript)
        XCTAssertEqual(manifest.effectiveQualities.count, 2)
        XCTAssertEqual(manifest.effectiveDefaultStreamQualityID, "standard")
        XCTAssertEqual(manifest.effectiveDefaultDownloadQualityID, "highest")

        // 2. Rejects invalid quality manifests (>16 entries, duplicate id, empty label, invalid default)
        let tooMany = (0..<17).map { ["id": "q\($0)", "label": "Q\($0)"] }
        let tooManyManifest = ExtensionManifest(
            apiVersion: 1,
            id: "com.test.toomany",
            name: "Too Many",
            version: "1.0.0",
            capabilities: [.stream],
            permissions: ExtensionPermissions(hosts: ["q.test"]),
            qualities: tooMany.map { CloudQualityOption(id: $0["id"]!, label: $0["label"]!) }
        )
        XCTAssertThrowsError(try tooManyManifest.validate())

        var dupManifest = manifest
        dupManifest.qualities = [
            CloudQualityOption(id: "std", label: "Standard"),
            CloudQualityOption(id: "std", label: "Duplicate")
        ]
        dupManifest.defaultStreamQuality = "std"
        dupManifest.defaultDownloadQuality = "std"
        XCTAssertThrowsError(try dupManifest.validate())

        var emptyLabel = manifest
        emptyLabel.qualities = [CloudQualityOption(id: "std", label: "   ")]
        XCTAssertThrowsError(try emptyLabel.validate())

        var badDefault = manifest
        badDefault.defaultStreamQuality = "missing"
        XCTAssertThrowsError(try badDefault.validate())

        var defaultWithoutQualities = manifest
        defaultWithoutQualities.qualities = nil
        defaultWithoutQualities.defaultStreamQuality = "standard"
        XCTAssertThrowsError(try defaultWithoutQualities.validate())

        // 3. Backward-compatible ExtensionState decoding without streamQualityID/downloadQualityID
        let legacyStateJSON = #"{"isEnabled":true,"permissionApproved":true,"settings":{}}"#
        let decodedLegacy = try JSONDecoder().decode(ExtensionState.self, from: Data(legacyStateJSON.utf8))
        XCTAssertTrue(decodedLegacy.isEnabled)
        XCTAssertNil(decodedLegacy.streamQualityID)
        XCTAssertNil(decodedLegacy.downloadQualityID)

        // 4. ExtensionStore quality preferences flow into JSExtensionCloudProvider.resolveStream
        let registry = CloudProviderRegistry(transport: FixtureTransport { req in
            HTTPResponseData(statusCode: 200, headers: [:], data: Data(), finalURL: req.url)
        })
        let extStore = ExtensionStore(baseDirectory: makeTempDir(), registry: registry)
        let staged = try await extStore.importPackageData(Data(validScript.utf8), fileName: "q.musico.js", sourceURL: nil)
        extStore.approvePermissionsAndEnable(id: staged.id)

        let provider = try XCTUnwrap(registry.provider(forID: "ext.com.test.qualities") as? JSExtensionCloudProvider)
        XCTAssertEqual(provider.availableQualities.count, 2)
        XCTAssertEqual(provider.defaultStreamQualityID, "standard")
        XCTAssertEqual(provider.defaultDownloadQualityID, "highest")

        let track = Track(
            id: "t-q1",
            rootID: UUID(),
            relativePath: "tr1",
            title: "Song",
            artist: "Art",
            album: "Alb",
            albumArtist: "Art",
            duration: 100,
            fileSize: 0,
            modified: Date(),
            hasEmbeddedLyrics: false,
            dateAdded: Date(),
            cloudSource: "ext.com.test.qualities",
            remoteTrackID: "tr1"
        )

        // Default stream & download purposes
        let streamDefault = try await provider.resolveStream(track: track, root: nil, purpose: .stream, qualityID: nil, forceRefresh: false)
        XCTAssertEqual(streamDefault.url.absoluteString, "https://q.test/audio/tr1?q=standard&p=stream")
        XCTAssertEqual(streamDefault.actualQuality, "standard")
        XCTAssertEqual(streamDefault.qualityLabel, "128k")
        XCTAssertEqual(streamDefault.bitrateKbps, 128)

        let downloadDefault = try await provider.resolveStream(track: track, root: nil, purpose: .download, qualityID: nil, forceRefresh: false)
        XCTAssertEqual(downloadDefault.url.absoluteString, "https://q.test/audio/tr1?q=highest&p=download")
        XCTAssertEqual(downloadDefault.fileExtension, "flac")
        XCTAssertEqual(downloadDefault.actualQuality, "highest")
        XCTAssertEqual(downloadDefault.qualityLabel, "FLAC")
        XCTAssertEqual(downloadDefault.bitrateKbps, 980)

        // Extension-level preference overrides manifest default when root doesn't override
        extStore.updateQualityPreferences(extensionID: staged.id, streamQualityID: "highest", downloadQualityID: "standard")
        let streamAfterExtPref = try await provider.resolveStream(track: track, root: nil, purpose: .stream, qualityID: nil, forceRefresh: false)
        XCTAssertEqual(streamAfterExtPref.url.absoluteString, "https://q.test/audio/tr1?q=highest&p=stream")

        // Folder-level override wins over extension preference, and explicit qualityID wins over folder
        let rootOverride = LibraryRoot(
            id: track.rootID,
            name: "Q Folder",
            kind: .cloudExtension,
            bookmark: nil,
            sourceURL: "https://q.test/list/1",
            remoteID: "1",
            providerID: "ext.com.test.qualities",
            streamQualityID: "standard",
            downloadQualityID: "highest"
        )
        let streamRootOverride = try await provider.resolveStream(track: track, root: rootOverride, purpose: .stream, qualityID: nil, forceRefresh: false)
        XCTAssertEqual(streamRootOverride.url.absoluteString, "https://q.test/audio/tr1?q=standard&p=stream")

        let streamTrackOverride = try await provider.resolveStream(track: track, root: rootOverride, purpose: .stream, qualityID: "highest", forceRefresh: true)
        XCTAssertEqual(streamTrackOverride.url.absoluteString, "https://q.test/audio/tr1?q=highest&p=stream")
    }

    private func makeMultiFixtureScript(id: String, name: String) -> String {
        """
        'use strict';
        var manifest = {
          apiVersion: 1,
          id: '\(id)',
          name: '\(name)',
          version: '1.0.0',
          author: 'Fixture',
          updateUrl: 'https://updates.fixture.test/\(id).musicoext',
          capabilities: ['playlist'],
          urlPatterns: ['^https://fixture\\\\.test/'],
          permissions: { hosts: ['fixture.test'] }
        };
        function canHandle(url) { return String(url).indexOf('https://fixture.test/') === 0; }
        async function fetchCollection() {
          return { remoteId: 'c1', title: '\(name)', tracks: [] };
        }
        module.exports = { manifest: manifest, canHandle: canHandle, fetchCollection: fetchCollection };
        """
    }

    private func makeMultiFixtureManifestJSON(id: String, name: String) -> String {
        """
        {
          "apiVersion": 1,
          "id": "\(id)",
          "name": "\(name)",
          "version": "1.0.0",
          "author": "Fixture",
          "entry": "index.js",
          "updateUrl": "https://updates.fixture.test/\(id).musicoext",
          "capabilities": ["playlist"],
          "urlPatterns": ["^https://fixture\\\\.test/"],
          "permissions": { "hosts": ["fixture.test"] }
        }
        """
    }

    private func makeMultiFixtureMusicoext(id: String, name: String) throws -> Data {
        try TestZipBuilder.buildArchive(entries: [
            .init(name: "manifest.json", data: Data(makeMultiFixtureManifestJSON(id: id, name: name).utf8)),
            .init(name: "index.js", data: Data(makeMultiFixtureScript(id: id, name: name).utf8)),
            .init(name: "icon.png", data: Data([0x89, 0x50, 0x4E, 0x47]))
        ])
    }

    @MainActor
    func testMultiPluginImportNestedMusicoextAndBatchEnable() async throws {
        let packData = try TestZipBuilder.buildArchive(entries: [
            .init(name: "alpha.musicoext", data: try makeMultiFixtureMusicoext(id: "com.example.alpha", name: "Alpha Plugin")),
            .init(name: "nested/beta.musicoext", data: try makeMultiFixtureMusicoext(id: "com.example.beta", name: "Beta Plugin")),
            .init(name: "__MACOSX/._alpha.musicoext", data: Data("appledouble".utf8)),
            .init(name: ".DS_Store", data: Data("junk".utf8))
        ])

        let tempDir = makeTempDir()
        let packURL = tempDir.appendingPathComponent("musico-plugins-all.zip")
        try packData.write(to: packURL)

        let registry = CloudProviderRegistry()
        let store = ExtensionStore(baseDirectory: makeTempDir(), registry: registry)

        // Single-package API must reject multi-extension archive.
        do {
            _ = try await store.importFromFile(at: packURL)
            XCTFail("Expected single-package importFromFile to reject multi-plugin ZIP")
        } catch {}

        let installed = try await store.importPackagesFromFile(at: packURL)
        XCTAssertEqual(installed.map(\.id), ["com.example.alpha", "com.example.beta"])
        XCTAssertTrue(installed.allSatisfy { !$0.isActive && !$0.state.permissionApproved && $0.iconData != nil })
        XCTAssertNil(registry.provider(forID: "ext.com.example.alpha"))

        store.approvePermissionsAndEnable(ids: installed.map(\.id))
        XCTAssertNotNil(registry.provider(forID: "ext.com.example.alpha"))
        XCTAssertNotNil(registry.provider(forID: "ext.com.example.beta"))
    }

    @MainActor
    func testMultiPluginSubdirsNestedScriptsAndValidationErrors() async throws {
        let registry = CloudProviderRegistry()
        let store = ExtensionStore(baseDirectory: makeTempDir(), registry: registry)

        let subdirPack = try TestZipBuilder.buildArchive(entries: [
            .init(name: "__MACOSX/pkg-one/._manifest.json", data: Data("appledouble".utf8)),
            .init(name: "pkg-one/.DS_Store", data: Data("junk".utf8)),
            .init(name: "pkg-one/manifest.json", data: Data(makeMultiFixtureManifestJSON(id: "com.example.one", name: "One").utf8)),
            .init(name: "pkg-one/index.js", data: Data(makeMultiFixtureScript(id: "com.example.one", name: "One").utf8)),
            .init(name: "pkg-two/manifest.json", data: Data(makeMultiFixtureManifestJSON(id: "com.example.two", name: "Two").utf8)),
            .init(name: "pkg-two/index.js", data: Data(makeMultiFixtureScript(id: "com.example.two", name: "Two").utf8))
        ])
        let subdirInstalled = try await store.importPackagesData(subdirPack, fileName: "subdirs.zip", sourceURL: nil)
        XCTAssertEqual(subdirInstalled.map(\.id).sorted(), ["com.example.one", "com.example.two"])

        let scriptPack = try TestZipBuilder.buildArchive(entries: [
            .init(name: "scripts/jsone.musico.js", data: Data(makeMultiFixtureScript(id: "com.example.jsone", name: "JS One").utf8)),
            .init(name: "scripts/jstwo.musico.js", data: Data(makeMultiFixtureScript(id: "com.example.jstwo", name: "JS Two").utf8)),
            .init(name: "__MACOSX/scripts/._jsone.musico.js", data: Data("appledouble".utf8))
        ])
        let scriptInstalled = try await store.importPackagesData(scriptPack, fileName: "scripts.zip", sourceURL: nil)
        XCTAssertEqual(scriptInstalled.map(\.id).sorted(), ["com.example.jsone", "com.example.jstwo"])

        let duplicatePack = try TestZipBuilder.buildArchive(entries: [
            .init(name: "a.musicoext", data: try makeMultiFixtureMusicoext(id: "com.example.dup", name: "First")),
            .init(name: "b.musicoext", data: try makeMultiFixtureMusicoext(id: "com.example.dup", name: "Second"))
        ])
        do {
            _ = try await store.importPackagesData(duplicatePack, fileName: "dup.zip", sourceURL: nil)
            XCTFail("Expected duplicate extension ID rejection")
        } catch {}

        let emptyPack = try TestZipBuilder.buildArchive(entries: [
            .init(name: "README.md", data: Data("not an extension".utf8)),
            .init(name: "__MACOSX/._README.md", data: Data("appledouble".utf8))
        ])
        do {
            _ = try await store.importPackagesData(emptyPack, fileName: "empty.zip", sourceURL: nil)
            XCTFail("Expected empty pack rejection")
        } catch {}
    }

    @MainActor
    func testMultiPluginURLImportClearsSourceURLOnPackButKeepsSingle() async throws {
        let singleData = try makeMultiFixtureMusicoext(id: "com.example.single", name: "Single")
        let multiData = try TestZipBuilder.buildArchive(entries: [
            .init(name: "alpha.musicoext", data: try makeMultiFixtureMusicoext(id: "com.example.ualpha", name: "UAlpha")),
            .init(name: "beta.musicoext", data: try makeMultiFixtureMusicoext(id: "com.example.ubeta", name: "UBeta"))
        ])

        let transport = FixtureTransport { req in
            let url = req.url!
            let payload = url.path.hasSuffix("single.musicoext") ? singleData : multiData
            return HTTPResponseData(statusCode: 200, headers: [:], data: payload, finalURL: url)
        }
        let store = ExtensionStore(baseDirectory: makeTempDir(), registry: CloudProviderRegistry(), transport: transport)

        let single = try await store.importPackagesFromURL("https://updates.fixture.test/single.musicoext")
        XCTAssertEqual(single.count, 1)
        XCTAssertEqual(single.first?.state.sourceURL, "https://updates.fixture.test/single.musicoext")

        let multi = try await store.importPackagesFromURL("https://updates.fixture.test/musico-plugins-all.zip")
        XCTAssertEqual(multi.count, 2)
        XCTAssertTrue(multi.allSatisfy { $0.state.sourceURL == nil })
    }

    func testWebLoginManifestValidationAndNetscapeCookieParser() throws {
        let validJSON = """
        {
          "apiVersion": 1,
          "id": "com.test.weblogin",
          "name": "Web Login Ext",
          "version": "1.0.0",
          "capabilities": ["search", "web_login"],
          "permissions": { "hosts": ["music.youtube.com"] },
          "webLogin": {
            "loginUrl": "https://accounts.google.com/ServiceLogin",
            "cookieDomains": [".youtube.com", ".google.com"],
            "requiredCookies": ["SAPISID"]
          }
        }
        """
        let valid = try JSONDecoder().decode(ExtensionManifest.self, from: Data(validJSON.utf8))
        try valid.validate()
        XCTAssertTrue(valid.supportsWebLogin)
        XCTAssertTrue(valid.supportsCookieAuth)

        let missingWebLogin = try JSONDecoder().decode(ExtensionManifest.self, from: Data("""
        {
          "apiVersion": 1,
          "id": "com.test.missingwl",
          "name": "Missing",
          "version": "1.0.0",
          "capabilities": ["search", "web_login"],
          "permissions": { "hosts": ["music.youtube.com"] }
        }
        """.utf8))
        XCTAssertThrowsError(try missingWebLogin.validate())

        let httpWebLogin = try JSONDecoder().decode(ExtensionManifest.self, from: Data("""
        {
          "apiVersion": 1,
          "id": "com.test.httpwl",
          "name": "Insecure",
          "version": "1.0.0",
          "capabilities": ["search", "web_login"],
          "permissions": { "hosts": ["music.youtube.com"] },
          "webLogin": {
            "loginUrl": "http://accounts.google.com/ServiceLogin",
            "cookieDomains": [".youtube.com"]
          }
        }
        """.utf8))
        XCTAssertThrowsError(try httpWebLogin.validate())

        let netscape = """
        # Netscape HTTP Cookie File
        #HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t1900000000\tSAPISID\tsapisid_val
        .google.com\tTRUE\t/\tTRUE\t0\tSID\tsid_val
        .youtube.com\tTRUE\t/\tTRUE\t1000\tEXPIRED\told_val
        .github.com\tTRUE\t/\tTRUE\t0\tGH_SESS\tdo_not_leak
        """
        let parsed = NetscapeCookieFileParser.parseCookieHeader(
            from: netscape,
            allowedDomains: [".youtube.com", ".google.com"],
            nowEpochSec: 1700000000
        )
        XCTAssertEqual(parsed, "SAPISID=sapisid_val; SID=sid_val")

        let plainParsed = NetscapeCookieFileParser.parseCookieHeader(
            from: "Cookie: SESSDATA=abc123; bili_jct=def456; SESSDATA=abc_new",
            allowedDomains: ["www.bilibili.com"]
        )
        XCTAssertEqual(plainParsed, "SESSDATA=abc_new; bili_jct=def456")
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
