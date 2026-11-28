import Foundation
import XCTest
@testable import Musico

private final class MockCloudLyricsTransport: CloudHTTPTransport, @unchecked Sendable {
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

final class CloudLyricsTests: XCTestCase {
    func testLrclibSearchMergesGetAndSearchWithHeadersAndHandles404Miss() async throws {
        let transport = MockCloudLyricsTransport { req in
            let url = try XCTUnwrap(req.url)
            if url.path == "/api/get" {
                return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: url)
            }
            if url.path == "/api/search" {
                let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
                if items.contains(where: { $0.name == "track_name" }) {
                    return HTTPResponseData(statusCode: 200, headers: [:], data: Data("[]".utf8), finalURL: url)
                }
                let payload = """
                [
                  {
                    "id": 42,
                    "trackName": "Nightcall",
                    "artistName": "Kavinsky",
                    "albumName": "OutRun",
                    "duration": 258,
                    "instrumental": false,
                    "plainLyrics": "There is something inside you",
                    "syncedLyrics": "[00:30.00]There is something inside you"
                  }
                ]
                """
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(payload.utf8), finalURL: url)
            }
            XCTFail("Unexpected LRCLIB path: \(url.path)")
            return HTTPResponseData(statusCode: 500, headers: [:], data: Data(), finalURL: url)
        }

        let client = LrclibClient(transport: transport, appVersion: "1.2.3")
        let query = CloudLyricsQuery(title: "Nightcall", artist: "Kavinsky", album: "OutRun", durationSec: 258)
        let results = try await client.search(query: query)

        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results[0].providerID, CloudLyricsProviderID.lrclib)
        XCTAssertTrue(results[0].isTimed)
        XCTAssertTrue(transport.requests.allSatisfy {
            ($0.value(forHTTPHeaderField: "User-Agent") ?? "").contains("Musico/1.2.3")
                && $0.value(forHTTPHeaderField: "Lrclib-App-Id") == nil
        })
    }

    func testLrclibPropagatesRetryAfterOn429() async {
        let transport = MockCloudLyricsTransport { req in
            HTTPResponseData(
                statusCode: 429,
                headers: ["Retry-After": "12"],
                data: Data(),
                finalURL: req.url
            )
        }
        let client = LrclibClient(transport: transport, appVersion: "1.2.3")
        do {
            _ = try await client.search(query: CloudLyricsQuery(title: "Hello", artist: "Adele", album: nil, durationSec: nil))
            XCTFail("Expected rateLimited error")
        } catch let error as CloudLyricsError {
            XCTAssertEqual(error, .rateLimited(retryAfterSeconds: 12))
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testSourceOptionsExposeLrclibOnlyWithoutExtensionsAndAutoWithExtensions() async throws {
        let bareOptions = CloudLyricsService.sourceOptions(extensions: [], autoLabel: "Auto")
        XCTAssertEqual(bareOptions.map(\.selection), [.lrclib])
        XCTAssertEqual(bareOptions.map(\.label), ["LRCLIB"])

        let ext = CloudLyricsExtensionDescriptor(id: "netease.cloud", name: "NetEase")
        let withExtOptions = CloudLyricsService.sourceOptions(extensions: [ext], autoLabel: "Auto")
        XCTAssertEqual(withExtOptions.map(\.selection), [.auto, .lrclib, .ext(id: "netease.cloud")])
        XCTAssertEqual(withExtOptions.map(\.label), ["Auto", "LRCLIB", "NetEase"])

        // `.auto` still works when no extensions are enabled and queries LRCLIB only.
        let transport = MockCloudLyricsTransport { req in
            let url = try XCTUnwrap(req.url)
            XCTAssertEqual(url.host, "lrclib.net")
            if url.path == "/api/get" {
                return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: url)
            }
            if url.path == "/api/search" {
                let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
                if items.contains(where: { $0.name == "track_name" }) {
                    return HTTPResponseData(statusCode: 200, headers: [:], data: Data("[]".utf8), finalURL: url)
                }
                let payload = """
                [
                  {
                    "id": 99,
                    "trackName": "Nightcall",
                    "artistName": "Kavinsky",
                    "duration": 258,
                    "syncedLyrics": "[00:30.00]Nightcall"
                  }
                ]
                """
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(payload.utf8), finalURL: url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: url)
        }
        let service = CloudLyricsService(transport: transport, appVersion: "1.2.3")
        let response = try await service.search(
            query: CloudLyricsQuery(title: "Nightcall", artist: "Kavinsky", album: nil, durationSec: 258),
            source: .auto,
            activeExtensions: []
        )
        XCTAssertEqual(response.candidates.count, 1)
        XCTAssertEqual(response.candidates[0].providerID, CloudLyricsProviderID.lrclib)
        XCTAssertTrue(response.failures.isEmpty)
    }

    @MainActor
    func testLyricsOnlyExtensionManifestRuntimeAndSourceSwitching() async throws {
        let extScript = """
        exports.manifest = {
          apiVersion: 1,
          id: "sample.lyrics",
          name: "Sample Lyrics",
          version: "1.0.0",
          badgeLabel: "SampleLrc",
          capabilities: ["lyrics"],
          permissions: { hosts: ["lyrics.example.com"] }
        };

        exports.searchLyrics = async function(ctx, req) {
          var res = await ctx.http.request({
            url: "https://lyrics.example.com/search?title=" + encodeURIComponent(req.title)
          });
          var json = JSON.parse(res.text);
          return json.items;
        };
        """

        let transport = MockCloudLyricsTransport { req in
            let host = req.url?.host ?? ""
            if host == "lyrics.example.com" {
                let payload = """
                {
                  "items": [
                    {
                      "externalId": "ext-1",
                      "title": "Sample Song",
                      "artist": "Sample Artist",
                      "durationSec": 200,
                      "syncedLyrics": "[00:05.00]From Extension"
                    }
                  ]
                }
                """
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(payload.utf8), finalURL: req.url)
            }
            if host == "lrclib.net" && req.url?.path == "/api/search" {
                let payload = """
                [
                  {
                    "id": 7,
                    "trackName": "Sample Song",
                    "artistName": "Sample Artist",
                    "duration": 180,
                    "plainLyrics": "Plain fallback"
                  }
                ]
                """
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(payload.utf8), finalURL: req.url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: req.url)
        }

        let dir = makeTempDir()
        let registry = CloudProviderRegistry()
        let store = ExtensionStore(baseDirectory: dir, registry: registry, transport: transport)
        let installed = try await store.importPackageData(Data(extScript.utf8), fileName: "sample.musico.js", sourceURL: nil)
        store.approvePermissionsAndEnable(id: installed.id)

        // Lyrics-only extension must NOT appear in CloudProviderRegistry cloud-folder providers.
        XCTAssertTrue(registry.extensionProviders().isEmpty)
        XCTAssertEqual(store.activeLyricsExtensions.map(\.id), ["sample.lyrics"])

        let lyricsStore = LyricsStore(directory: makeTempDir().appendingPathComponent("Lyrics"))
        let service = store.makeCloudLyricsService()
        let query = CloudLyricsQuery(title: "Sample Song", artist: "Sample Artist", album: nil, durationSec: 200)

        // Auto merges LRCLIB + extension, ranking synced + exact duration first.
        let autoResponse = try await service.search(query: query, source: .auto, activeExtensions: store.activeLyricsExtensions)
        XCTAssertEqual(autoResponse.candidates.first?.sourceName, "SampleLrc")
        XCTAssertEqual(autoResponse.candidates.count, 2)
        XCTAssertTrue(autoResponse.failures.isEmpty)
        // Save-only-on-confirm: searching alone must never persist to LyricsStore.
        XCTAssertFalse(lyricsStore.hasImported(trackID: "track-1"))

        // Switching to explicit source returns only that source's results.
        let lrclibOnly = try await service.search(query: query, source: .lrclib, activeExtensions: store.activeLyricsExtensions)
        XCTAssertEqual(lrclibOnly.candidates.map(\.providerID), [CloudLyricsProviderID.lrclib])

        let extOnly = try await service.search(query: query, source: .ext(id: "sample.lyrics"), activeExtensions: store.activeLyricsExtensions)
        XCTAssertEqual(extOnly.candidates.map(\.sourceName), ["SampleLrc"])
    }

    @MainActor
    func testLyricsExtensionReceivesSavedCookieInSearchLyrics() async throws {
        let extScript = """
        exports.manifest = {
          apiVersion: 1,
          id: "sample.lyrics.auth",
          name: "Sample Auth Lyrics",
          version: "1.0.0",
          badgeLabel: "AuthLrc",
          capabilities: ["lyrics", "cookie"],
          permissions: { hosts: ["lyrics.example.com"] }
        };

        exports.searchLyrics = async function(ctx, req) {
          var headers = {};
          if (req.cookie) {
            headers.Authorization = "Bearer " + req.cookie;
          }
          var res = await ctx.http.request({
            url: "https://lyrics.example.com/search?title=" + encodeURIComponent(req.title),
            headers: headers
          });
          return JSON.parse(res.text).items;
        };
        """

        let transport = MockCloudLyricsTransport { req in
            let auth = req.value(forHTTPHeaderField: "Authorization")
            if auth == "Bearer secret-lyrics-token" {
                let payload = """
                {
                  "items": [
                    {
                      "externalId": "auth-1",
                      "title": "21→22",
                      "artist": "あよ",
                      "syncedLyrics": "[00:01.00]Line"
                    }
                  ]
                }
                """
                return HTTPResponseData(statusCode: 200, headers: [:], data: Data(payload.utf8), finalURL: req.url)
            }
            return HTTPResponseData(statusCode: 401, headers: [:], data: Data(), finalURL: req.url)
        }

        let dir = makeTempDir()
        let store = ExtensionStore(baseDirectory: dir, registry: CloudProviderRegistry(), transport: transport)
        let installed = try await store.importPackageData(Data(extScript.utf8), fileName: "auth.musico.js", sourceURL: nil)
        store.approvePermissionsAndEnable(id: installed.id)
        store.updateCookie(extensionID: installed.id, cookie: "secret-lyrics-token")

        let service = store.makeCloudLyricsService()
        let resp = try await service.search(
            query: CloudLyricsQuery(title: "21→22", artist: "あよ", album: nil, durationSec: nil),
            source: .ext(id: "sample.lyrics.auth"),
            activeExtensions: store.activeLyricsExtensions
        )
        XCTAssertEqual(resp.candidates.count, 1)
        XCTAssertEqual(transport.requests.first?.value(forHTTPHeaderField: "Authorization"), "Bearer secret-lyrics-token")
    }
}
