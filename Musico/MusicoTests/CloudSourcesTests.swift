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
        XCTAssertFalse(LibraryRoot.Kind.picked.isCloud)
        XCTAssertTrue(LibraryRoot.Kind.picked.isLocal)
        XCTAssertFalse(LibraryRoot.Kind.appFolder.isCloud)
        XCTAssertTrue(LibraryRoot.Kind.appFolder.isLocal)
    }

    // MARK: - YouTube URL Parsing

    func testYouTubeURLParsing() {
        XCTAssertEqual(
            YouTubeClient.parse("https://www.youtube.com/playlist?list=PL1234567890ABCDEF"),
            .playlist(id: "PL1234567890ABCDEF")
        )
        XCTAssertEqual(
            YouTubeClient.parse("https://music.youtube.com/playlist?list=OLAK5uy_abc123def456"),
            .playlist(id: "OLAK5uy_abc123def456")
        )
        XCTAssertEqual(
            YouTubeClient.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLxyz987654321"),
            .playlist(id: "PLxyz987654321")
        )
        XCTAssertEqual(
            YouTubeClient.parse("https://youtu.be/dQw4w9WgXcQ"),
            .video(id: "dQw4w9WgXcQ")
        )
        XCTAssertEqual(
            YouTubeClient.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
            .video(id: "dQw4w9WgXcQ")
        )
        XCTAssertEqual(
            YouTubeClient.parse("PL1234567890ABCDEF"),
            .playlist(id: "PL1234567890ABCDEF")
        )
        XCTAssertEqual(
            YouTubeClient.parse("OLAK5uy_testplaylistid"),
            .playlist(id: "OLAK5uy_testplaylistid")
        )
        XCTAssertEqual(
            YouTubeClient.parse("dQw4w9WgXcQ"),
            .video(id: "dQw4w9WgXcQ")
        )
        XCTAssertNil(YouTubeClient.parse("https://example.com/not-youtube"))
    }

    // MARK: - Bilibili URL Parsing

    func testBilibiliURLParsing() {
        XCTAssertEqual(
            BilibiliClient.parse("https://b23.tv/abcXYZ"),
            .shortLink(url: URL(string: "https://b23.tv/abcXYZ")!)
        )
        XCTAssertEqual(
            BilibiliClient.parse("https://space.bilibili.com/12345/favlist?fid=67890"),
            .favlist(mediaID: "67890")
        )
        XCTAssertEqual(
            BilibiliClient.parse("https://www.bilibili.com/medialist/detail/ml555666"),
            .favlist(mediaID: "555666")
        )
        XCTAssertEqual(
            BilibiliClient.parse("ml555666"),
            .favlist(mediaID: "555666")
        )
        XCTAssertEqual(
            BilibiliClient.parse("https://space.bilibili.com/1001/channel/collectiondetail?sid=2002"),
            .season(seasonID: "2002", mid: "1001")
        )
        XCTAssertEqual(
            BilibiliClient.parse("https://space.bilibili.com/1001/lists/3003?type=season"),
            .season(seasonID: "3003", mid: "1001")
        )
        XCTAssertEqual(
            BilibiliClient.parse("https://space.bilibili.com/1001/lists/4004?type=series"),
            .series(seriesID: "4004", mid: "1001")
        )
        XCTAssertEqual(
            BilibiliClient.parse("https://space.bilibili.com/1001/channel/seriesdetail?sid=5005"),
            .series(seriesID: "5005", mid: "1001")
        )
        XCTAssertEqual(
            BilibiliClient.parse("https://www.bilibili.com/video/BV1xx411c7mD?p=2"),
            .video(bvid: "BV1xx411c7mD", aid: nil)
        )
        XCTAssertEqual(
            BilibiliClient.parse("https://www.bilibili.com/video/av170001"),
            .video(bvid: nil, aid: "170001")
        )
        XCTAssertEqual(
            BilibiliClient.parse("BV1xx411c7mD"),
            .video(bvid: "BV1xx411c7mD", aid: nil)
        )
    }

    // MARK: - WBI & SAPISIDHASH

    func testWBIMixinKeyAndSigningVector() {
        let imgKey = "7cd084941338484aae1ad9425b84077c"
        let subKey = "4932caff0ff746eab6f01bf08b70ac45"
        XCTAssertEqual(
            BilibiliClient.extractWBIKey(from: "https://i0.hdslb.com/bfs/wbi/\(imgKey).png"),
            imgKey
        )
        let mixin = BilibiliClient.mixinKey(imgKey: imgKey, subKey: subKey)
        XCTAssertEqual(mixin, "ea1db124af3c7062474693fa704f4ff8")

        let signed = BilibiliClient.signWBI(
            params: ["foo": "114", "bar": "514", "baz": "1919!()*810"],
            imgKey: imgKey,
            subKey: subKey,
            timestamp: 1702204169
        )
        XCTAssertEqual(signed["wts"], "1702204169")
        // Expected query before MD5: bar=514&baz=1919810&foo=114&wts=1702204169
        let expectedString = "bar=514&baz=1919810&foo=114&wts=1702204169" + mixin
        let expectedMD5 = Insecure.MD5.hash(data: Data(expectedString.utf8)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(signed["w_rid"], expectedMD5)
    }

    func testSAPISIDHashHeader() {
        let header = YouTubeClient.sapisidHashHeader(
            cookie: "FOO=1; SAPISID=my_secret_sid; BAR=2",
            origin: "https://music.youtube.com",
            timestamp: 1700000000
        )
        let digest = Insecure.SHA1.hash(data: Data("1700000000 my_secret_sid https://music.youtube.com".utf8))
            .map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(header, "SAPISIDHASH 1700000000_\(digest)")
    }

    // MARK: - YouTube Innertube Parsing & Resolution

    func testInnertubeBrowseParsesBothRenderersAndPagination() async throws {
        let page1: [String: Any] = [
            "header": [
                "musicDetailHeaderRenderer": [
                    "title": ["runs": [["text": "Chill Mix"]]]
                ]
            ],
            "contents": [
                "twoColumnBrowseResultsRenderer": [
                    "tabs": [[
                        "tabRenderer": [
                            "content": [
                                "sectionListRenderer": [
                                    "contents": [[
                                        "musicPlaylistShelfRenderer": [
                                            "contents": [
                                                [
                                                    "musicResponsiveListItemRenderer": [
                                                        "playlistItemData": ["videoId": "vid00000001"],
                                                        "flexColumns": [
                                                            ["musicResponsiveListItemFlexColumnRenderer": ["text": ["runs": [["text": "Track One"]]]]],
                                                            ["musicResponsiveListItemFlexColumnRenderer": ["text": ["runs": [
                                                                ["text": "Artist A"], ["text": " • "], ["text": "Album X"]
                                                            ]]]]
                                                        ],
                                                        "fixedColumns": [
                                                            ["musicResponsiveListItemFixedColumnRenderer": ["text": ["runs": [["text": "3:45"]]]]]
                                                        ],
                                                        "thumbnail": [
                                                            "musicThumbnailRenderer": [
                                                                "thumbnail": ["thumbnails": [["url": "https://i.ytimg.com/vi/1/hq.jpg"]]]
                                                            ]
                                                        ]
                                                    ]
                                                ],
                                                [
                                                    "continuationItemRenderer": [
                                                        "continuationEndpoint": [
                                                            "continuationCommand": ["token": "CONT_TOKEN_2"]
                                                        ]
                                                    ]
                                                ]
                                            ]
                                        ]
                                    ]]
                                ]
                            ]
                        ]
                    ]]
                ]
            ]
        ]

        let page2: [String: Any] = [
            "onResponseReceivedActions": [[
                "appendContinuationItemsAction": [
                    "continuationItems": [[
                        "playlistVideoRenderer": [
                            "videoId": "vid00000002",
                            "title": ["runs": [["text": "Track Two"]]],
                            "shortBylineText": ["runs": [["text": "Artist B"]]],
                            "lengthSeconds": "205",
                            "thumbnail": ["thumbnails": [["url": "//i.ytimg.com/vi/2/hq.jpg"]]]
                        ]
                    ]]
                ]
            ]]
        ]

        let transport = MockCloudTransport { request in
            let body = try JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: Any]
            let payload: [String: Any] = (body?["continuation"] as? String == "CONT_TOKEN_2") ? page2 : page1
            let data = try JSONSerialization.data(withJSONObject: payload)
            return HTTPResponseData(statusCode: 200, headers: [:], data: data, finalURL: request.url)
        }

        let client = YouTubeClient(transport: transport)
        let result = try await client.fetchPlaylist(sourceURL: "https://music.youtube.com/playlist?list=PLtest123456", cookie: nil)

        XCTAssertEqual(result.kind, .cloudYouTube)
        XCTAssertEqual(result.remoteID, "PLtest123456")
        XCTAssertEqual(result.title, "Chill Mix")
        XCTAssertEqual(result.tracks.count, 2)

        XCTAssertEqual(result.tracks[0].remoteTrackID, "vid00000001")
        XCTAssertEqual(result.tracks[0].title, "Track One")
        XCTAssertEqual(result.tracks[0].artist, "Artist A")
        XCTAssertEqual(result.tracks[0].album, "Album X")
        XCTAssertEqual(result.tracks[0].duration, 225, accuracy: 0.1)
        XCTAssertEqual(result.tracks[0].artworkURL, "https://i.ytimg.com/vi/1/hq.jpg")

        XCTAssertEqual(result.tracks[1].remoteTrackID, "vid00000002")
        XCTAssertEqual(result.tracks[1].title, "Track Two")
        XCTAssertEqual(result.tracks[1].artist, "Artist B")
        XCTAssertEqual(result.tracks[1].duration, 205, accuracy: 0.1)
        XCTAssertEqual(result.tracks[1].artworkURL, "https://i.ytimg.com/vi/2/hq.jpg")
    }

    func testYouTubeStreamResolutionSkips403ProbeAndFallsBackClient() async throws {
        let androidResponse: [String: Any] = [
            "streamingData": [
                "adaptiveFormats": [
                    ["itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "url": "https://cdn.example/android-140-blocked"],
                    ["itag": 251, "mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000, "url": "https://cdn.example/opus-ignored"]
                ]
            ]
        ]
        let iosResponse: [String: Any] = [
            "streamingData": [
                "adaptiveFormats": [
                    ["itag": 141, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 256000, "url": "https://cdn.example/ios-141-ok?expire=1900000000"],
                    ["itag": 140, "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "url": "https://cdn.example/ios-140-ok"]
                ]
            ]
        ]

        let transport = MockCloudTransport { request in
            let url = request.url!.absoluteString
            if url.contains("/youtubei/v1/player") {
                let body = try JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: Any]
                let clientName = ((body?["context"] as? [String: Any])?["client"] as? [String: Any])?["clientName"] as? String
                let payload = (clientName == "ANDROID") ? androidResponse : iosResponse
                return HTTPResponseData(statusCode: 200, headers: [:], data: try JSONSerialization.data(withJSONObject: payload), finalURL: request.url)
            }
            if url.contains("android-140-blocked") {
                return HTTPResponseData(statusCode: 403, headers: [:], data: Data(), finalURL: request.url)
            }
            if url.contains("ios-141-ok") {
                XCTAssertEqual(request.value(forHTTPHeaderField: "Range"), "bytes=0-1023")
                return HTTPResponseData(statusCode: 206, headers: ["Content-Type": "audio/mp4"], data: Data([0x00, 0x01]), finalURL: request.url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: request.url)
        }

        let client = YouTubeClient(transport: transport)
        let stream = try await client.resolveStream(videoID: "vid00000001", cookie: nil)
        XCTAssertEqual(stream.url.absoluteString, "https://cdn.example/ios-141-ok?expire=1900000000")
        XCTAssertEqual(stream.fileExtension, "m4a")

        // Second call uses TTL cache without hitting network again.
        let beforeCount = transport.requests.count
        let cached = try await client.resolveStream(videoID: "vid00000001", cookie: nil)
        XCTAssertEqual(cached.url, stream.url)
        XCTAssertEqual(transport.requests.count, beforeCount)
    }

    // MARK: - Bilibili JSON Parsing & Resolution

    func testBilibiliMultiPartVideoAndShortLinkRedirect() async throws {
        let viewPayload: [String: Any] = [
            "code": 0,
            "data": [
                "bvid": "BV1xx411c7mD",
                "title": "Live Concert",
                "pic": "http://i0.hdslb.com/bfs/archive/cover.jpg",
                "duration": 600,
                "owner": ["name": "Uploader"],
                "pages": [
                    ["cid": 1001, "page": 1, "part": "Opening", "duration": 200],
                    ["cid": 1002, "page": 2, "part": "Encore", "duration": 400]
                ]
            ]
        ]

        let transport = MockCloudTransport { request in
            let url = request.url!.absoluteString
            if url.contains("b23.tv") {
                return HTTPResponseData(
                    statusCode: 200,
                    headers: [:],
                    data: Data(),
                    finalURL: URL(string: "https://www.bilibili.com/video/BV1xx411c7mD")!
                )
            }
            if url.contains("/x/frontend/finger/spi") {
                let d = try JSONSerialization.data(withJSONObject: ["code": 0, "data": ["b_3": "buvid3_test"]])
                return HTTPResponseData(statusCode: 200, headers: [:], data: d, finalURL: request.url)
            }
            if url.contains("/x/web-interface/view") {
                let d = try JSONSerialization.data(withJSONObject: viewPayload)
                return HTTPResponseData(statusCode: 200, headers: [:], data: d, finalURL: request.url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: request.url)
        }

        let client = BilibiliClient(transport: transport)
        let result = try await client.fetchPlaylist(sourceURL: "https://b23.tv/short123", cookie: nil)

        XCTAssertEqual(result.kind, .cloudBilibili)
        XCTAssertEqual(result.title, "Live Concert")
        XCTAssertEqual(result.tracks.count, 2)
        XCTAssertEqual(result.tracks[0].remoteTrackID, "BV1xx411c7mD#1001")
        XCTAssertEqual(result.tracks[0].title, "Opening")
        XCTAssertEqual(result.tracks[0].album, "Live Concert")
        XCTAssertEqual(result.tracks[0].artist, "Uploader")
        XCTAssertEqual(result.tracks[0].artworkURL, "https://i0.hdslb.com/bfs/archive/cover.jpg")
        XCTAssertEqual(result.tracks[1].remoteTrackID, "BV1xx411c7mD#1002")
        XCTAssertEqual(result.tracks[1].title, "Encore")
        XCTAssertEqual(result.tracks[1].duration, 400, accuracy: 0.1)
    }

    func testBilibiliFavlistAndWBIPlayURLResolution() async throws {
        let navPayload: [String: Any] = [
            "code": -101,
            "data": [
                "wbi_img": [
                    "img_url": "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
                    "sub_url": "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"
                ]
            ]
        ]
        let playPayload: [String: Any] = [
            "code": 0,
            "data": [
                "dash": [
                    "audio": [
                        ["id": 30216, "baseUrl": "https://upos.bilivideo.com/low.m4s", "bandwidth": 64000, "mimeType": "audio/mp4"],
                        ["id": 30280, "baseUrl": "https://upos.bilivideo.com/high.m4s", "backupUrl": ["https://upos2.bilivideo.com/high.m4s"], "bandwidth": 192000, "mimeType": "audio/mp4"]
                    ]
                ]
            ]
        ]

        let transport = MockCloudTransport { request in
            let url = request.url!.absoluteString
            if url.contains("/x/frontend/finger/spi") {
                return HTTPResponseData(statusCode: 200, headers: [:], data: try JSONSerialization.data(withJSONObject: ["code": 0, "data": ["b_3": "b3_xyz"]]), finalURL: request.url)
            }
            if url.contains("/x/web-interface/nav") {
                return HTTPResponseData(statusCode: 200, headers: [:], data: try JSONSerialization.data(withJSONObject: navPayload), finalURL: request.url)
            }
            if url.contains("/x/player/wbi/playurl") {
                XCTAssertTrue(url.contains("w_rid="))
                XCTAssertTrue(url.contains("wts="))
                XCTAssertEqual(request.value(forHTTPHeaderField: "Referer"), "https://www.bilibili.com/")
                XCTAssertTrue(request.value(forHTTPHeaderField: "Cookie")?.contains("buvid3=b3_xyz") == true)
                return HTTPResponseData(statusCode: 200, headers: [:], data: try JSONSerialization.data(withJSONObject: playPayload), finalURL: request.url)
            }
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: request.url)
        }

        let client = BilibiliClient(transport: transport)
        let stream = try await client.resolveStream(trackID: "BV1xx411c7mD#1001", cookie: nil)
        XCTAssertEqual(stream.url.absoluteString, "https://upos.bilivideo.com/high.m4s")
        XCTAssertEqual(stream.fallbackURLs.map(\.absoluteString), ["https://upos2.bilivideo.com/high.m4s"])
        XCTAssertEqual(stream.fileExtension, "m4a")
        XCTAssertEqual(stream.headers["Referer"], "https://www.bilibili.com/")
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
}
