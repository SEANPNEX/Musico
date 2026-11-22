import Foundation
import CryptoKit

// MARK: - Shared Types & Protocols

enum CloudSourceError: LocalizedError, Equatable {
    case invalidURL
    case unsupportedSource
    case emptyPlaylist
    case noPlayableStream
    case apiError(String)
    case networkError(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            "Enter a valid YouTube or Bilibili playlist, collection, or video link."
        case .unsupportedSource:
            "That link isn’t a supported YouTube or Bilibili playlist or video."
        case .emptyPlaylist:
            "No playable tracks were found at that link."
        case .noPlayableStream:
            "Could not resolve a playable audio stream for this track."
        case .apiError(let message):
            message
        case .networkError(let message):
            message
        }
    }
}

struct CloudTrackInfo: Sendable, Equatable {
    var remoteTrackID: String
    var relativePath: String
    var title: String
    var artist: String
    var album: String
    var trackNumber: Int?
    var duration: TimeInterval
    var artworkURL: String?
}

struct CloudPlaylistResult: Sendable, Equatable {
    var kind: LibraryRoot.Kind
    var remoteID: String
    var title: String
    var tracks: [CloudTrackInfo]
}

struct CloudStreamResult: Sendable, Equatable {
    var url: URL
    var headers: [String: String]
    var fileExtension: String
    var expiresAt: Date
    var fallbackURLs: [URL] = []
}

struct HTTPResponseData: Sendable {
    var statusCode: Int
    var headers: [String: String]
    var data: Data
    var finalURL: URL?

    func header(_ name: String) -> String? {
        let lower = name.lowercased()
        return headers.first { $0.key.lowercased() == lower }?.value
    }
}

protocol CloudHTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> HTTPResponseData
}

struct URLSessionCloudTransport: CloudHTTPTransport {
    private let session: URLSession

    init(session: URLSession? = nil) {
        if let session {
            self.session = session
        } else {
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 20
            config.timeoutIntervalForResource = 60
            config.httpCookieAcceptPolicy = .always
            self.session = URLSession(configuration: config)
        }
    }

    func send(_ request: URLRequest) async throws -> HTTPResponseData {
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw CloudSourceError.networkError("Invalid server response.")
            }
            var normalizedHeaders: [String: String] = [:]
            for (key, value) in http.allHeaderFields {
                normalizedHeaders[String(describing: key)] = String(describing: value)
            }
            return HTTPResponseData(
                statusCode: http.statusCode,
                headers: normalizedHeaders,
                data: data,
                finalURL: http.url
            )
        } catch let error as CloudSourceError {
            throw error
        } catch {
            throw CloudSourceError.networkError(error.localizedDescription)
        }
    }
}

protocol CloudPlaylistFetching: Sendable {
    func fetchPlaylist(sourceURL: String, cookie: String?, kindHint: LibraryRoot.Kind?) async throws -> CloudPlaylistResult
}

protocol CloudStreamResolving: Sendable {
    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> CloudStreamResult
}

// MARK: - Share-Link URL Extraction & Classification

enum CloudSourceInput {
    private static let trailingPunctuation: Set<Character> = [
        ")", "]", "】", "」", "》", "。", ",", ".", "'", "\""
    ]
    private static let urlPattern = #"https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+"#

    /// Extracts an `http(s)://` URL from pasted share text while stripping surrounding copy and
    /// trailing punctuation, or falls back to a trimmed bare ID / schemeless link.
    static func extract(from raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }

        if let regex = try? NSRegularExpression(pattern: urlPattern, options: [.caseInsensitive]) {
            let fullRange = NSRange(trimmed.startIndex..<trimmed.endIndex, in: trimmed)
            let matches = regex.matches(in: trimmed, range: fullRange)
            var candidates: [String] = []
            for match in matches {
                guard let range = Range(match.range, in: trimmed) else { continue }
                let cleaned = cleanMatchedURL(String(trimmed[range]))
                if !cleaned.isEmpty {
                    candidates.append(cleaned)
                }
            }
            if let supported = candidates.first(where: {
                YouTubeClient.parseExtracted($0) != nil || BilibiliClient.parseExtracted($0) != nil
            }) {
                return supported
            }
            if let first = candidates.first {
                return first
            }
        }

        return stripTrailingPunctuation(from: trimmed)
    }

    static func classify(_ raw: String) -> LibraryRoot.Kind? {
        let extracted = extract(from: raw)
        guard !extracted.isEmpty else { return nil }
        if YouTubeClient.parseExtracted(extracted) != nil { return .cloudYouTube }
        if BilibiliClient.parseExtracted(extracted) != nil { return .cloudBilibili }
        return nil
    }

    private static func cleanMatchedURL(_ rawMatch: String) -> String {
        var result = stripTrailingPunctuation(from: rawMatch)
        if result.lowercased().hasPrefix("https://") {
            result = "https://" + result.dropFirst(8)
        } else if result.lowercased().hasPrefix("http://") {
            result = "http://" + result.dropFirst(7)
        }
        return result
    }

    private static func stripTrailingPunctuation(from value: String) -> String {
        var result = value.trimmingCharacters(in: .whitespacesAndNewlines)
        while let last = result.last, trailingPunctuation.contains(last) {
            result.removeLast()
        }
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

// MARK: - Live Service Facade

final class LiveCloudService: CloudPlaylistFetching, CloudStreamResolving, @unchecked Sendable {
    static let shared = LiveCloudService()

    let youtube: YouTubeClient
    let bilibili: BilibiliClient

    init(transport: any CloudHTTPTransport = URLSessionCloudTransport()) {
        self.youtube = YouTubeClient(transport: transport)
        self.bilibili = BilibiliClient(transport: transport)
    }

    func fetchPlaylist(sourceURL: String, cookie: String?, kindHint: LibraryRoot.Kind? = nil) async throws -> CloudPlaylistResult {
        let extracted = CloudSourceInput.extract(from: sourceURL)
        guard !extracted.isEmpty else { throw CloudSourceError.invalidURL }
        if kindHint == .cloudYouTube || (kindHint == nil && YouTubeClient.parseExtracted(extracted) != nil) {
            return try await youtube.fetchPlaylist(sourceURL: extracted, cookie: cookie)
        }
        if kindHint == .cloudBilibili || BilibiliClient.parseExtracted(extracted) != nil {
            return try await bilibili.fetchPlaylist(sourceURL: extracted, cookie: cookie)
        }
        throw CloudSourceError.unsupportedSource
    }

    func resolveStream(track: Track, root: LibraryRoot?, forceRefresh: Bool = false) async throws -> CloudStreamResult {
        let cookie = root?.cookie
        switch track.cloudSource {
        case "youtube":
            let videoID = track.remoteTrackID ?? track.relativePath
            return try await youtube.resolveStream(videoID: videoID, cookie: cookie, forceRefresh: forceRefresh)
        case "bilibili":
            let trackID = track.remoteTrackID ?? track.relativePath
            return try await bilibili.resolveStream(trackID: trackID, cookie: cookie, forceRefresh: forceRefresh)
        default:
            throw CloudSourceError.unsupportedSource
        }
    }
}

// MARK: - YouTube Client

actor YouTubeClient {
    enum Target: Equatable, Sendable {
        case playlist(id: String)
        case video(id: String)
    }

    struct ClientProfile: Sendable {
        let name: String
        let version: String
        let userAgent: String
        let origin: String
        let baseURL: String
        let clientBody: [String: Any]
    }

    private struct CachedStream: Sendable {
        let result: CloudStreamResult
    }

    private let transport: any CloudHTTPTransport
    private var streamCache: [String: CachedStream] = [:]

    static let webUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private static let webRemixClient = ClientProfile(
        name: "WEB_REMIX",
        version: "1.20250310.01.00",
        userAgent: webUserAgent,
        origin: "https://music.youtube.com",
        baseURL: "https://music.youtube.com",
        clientBody: [
            "clientName": "WEB_REMIX",
            "clientVersion": "1.20250310.01.00",
            "hl": "en",
            "gl": "US"
        ]
    )

    private static let webClient = ClientProfile(
        name: "WEB",
        version: "2.20250310.01.00",
        userAgent: webUserAgent,
        origin: "https://www.youtube.com",
        baseURL: "https://www.youtube.com",
        clientBody: [
            "clientName": "WEB",
            "clientVersion": "2.20250310.01.00",
            "hl": "en",
            "gl": "US"
        ]
    )

    private static let androidPlayerClient = ClientProfile(
        name: "ANDROID",
        version: "20.10.38",
        userAgent: "com.google.android.youtube/20.10.38 (Linux; U; Android 15; en_US) gzip",
        origin: "https://www.youtube.com",
        baseURL: "https://www.youtube.com",
        clientBody: [
            "clientName": "ANDROID",
            "clientVersion": "20.10.38",
            "androidSdkVersion": 35,
            "osName": "Android",
            "osVersion": "15",
            "hl": "en",
            "gl": "US"
        ]
    )

    private static let iosPlayerClient = ClientProfile(
        name: "IOS",
        version: "20.10.4",
        userAgent: "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X;)",
        origin: "https://www.youtube.com",
        baseURL: "https://www.youtube.com",
        clientBody: [
            "clientName": "IOS",
            "clientVersion": "20.10.4",
            "deviceMake": "Apple",
            "deviceModel": "iPhone16,2",
            "osName": "iPhone",
            "osVersion": "18.3.1",
            "hl": "en",
            "gl": "US"
        ]
    )

    private static let webEmbeddedClient = ClientProfile(
        name: "WEB_EMBEDDED_PLAYER",
        version: "1.20250310.01.00",
        userAgent: webUserAgent,
        origin: "https://www.youtube.com",
        baseURL: "https://www.youtube.com",
        clientBody: [
            "clientName": "WEB_EMBEDDED_PLAYER",
            "clientVersion": "1.20250310.01.00",
            "hl": "en",
            "gl": "US"
        ]
    )

    init(transport: any CloudHTTPTransport = URLSessionCloudTransport()) {
        self.transport = transport
    }

    // MARK: URL & ID Parsing

    nonisolated static func parse(_ input: String) -> Target? {
        parseExtracted(CloudSourceInput.extract(from: input))
    }

    nonisolated static func parseExtracted(_ input: String) -> Target? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        // Raw playlist or video IDs (no slashes, spaces, or query separators).
        if !trimmed.contains("/"), !trimmed.contains("."), !trimmed.contains("?"), !trimmed.contains(" ") {
            if isPlaylistID(trimmed) {
                return .playlist(id: trimmed)
            }
            if isVideoID(trimmed) {
                return .video(id: trimmed)
            }
            return nil
        }

        let normalized = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        guard let components = URLComponents(string: normalized),
              let host = components.host?.lowercased() else { return nil }

        let queryItems = Dictionary(
            (components.queryItems ?? []).compactMap { item -> (String, String)? in
                guard let value = item.value, !value.isEmpty else { return nil }
                return (item.name, value)
            },
            uniquingKeysWith: { first, _ in first }
        )

        if host == "youtu.be" || host.hasSuffix(".youtu.be") {
            if let list = queryItems["list"], !list.isEmpty {
                return .playlist(id: list)
            }
            let segments = components.path.split(separator: "/").map(String.init)
            if let videoID = segments.first, isVideoID(videoID) {
                return .video(id: videoID)
            }
            return nil
        }

        let isYouTubeHost = host == "youtube.com" || host.hasSuffix(".youtube.com") || host == "youtube-nocookie.com" || host.hasSuffix(".youtube-nocookie.com")
        guard isYouTubeHost else { return nil }

        if let list = queryItems["list"], !list.isEmpty {
            return .playlist(id: list)
        }
        if let v = queryItems["v"], isVideoID(v) {
            return .video(id: v)
        }
        let segments = components.path.split(separator: "/").map(String.init)
        if segments.count >= 2, ["shorts", "live", "embed", "v"].contains(segments[0]), isVideoID(segments[1]) {
            return .video(id: segments[1])
        }
        return nil
    }

    nonisolated private static func isPlaylistID(_ value: String) -> Bool {
        let prefixes = ["PL", "OLAK5uy_", "UU", "FL", "RD", "LL", "VL"]
        return value.count >= 10 && prefixes.contains(where: { value.hasPrefix($0) })
    }

    nonisolated private static func isVideoID(_ value: String) -> Bool {
        guard value.count == 11 else { return false }
        return value.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
    }

    // MARK: Auth Helpers

    nonisolated static func sapisidHashHeader(cookie: String, origin: String, timestamp: Int = Int(Date().timeIntervalSince1970)) -> String? {
        let pairs = parseCookiePairs(cookie)
        guard let sapisid = pairs["SAPISID"] ?? pairs["__Secure-3PAPISID"] ?? pairs["__Secure-1PAPISID"],
              !sapisid.isEmpty else { return nil }
        let input = "\(timestamp) \(sapisid) \(origin)"
        let digest = Insecure.SHA1.hash(data: Data(input.utf8))
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return "SAPISIDHASH \(timestamp)_\(hex)"
    }

    nonisolated static func parseCookiePairs(_ cookie: String) -> [String: String] {
        var result: [String: String] = [:]
        for part in cookie.split(separator: ";") {
            let trimmed = part.trimmingCharacters(in: .whitespaces)
            guard let eq = trimmed.firstIndex(of: "=") else { continue }
            let key = String(trimmed[..<eq]).trimmingCharacters(in: .whitespaces)
            let value = String(trimmed[trimmed.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
            if !key.isEmpty { result[key] = value }
        }
        return result
    }

    // MARK: Playlist Fetching

    func fetchPlaylist(sourceURL: String, cookie: String?) async throws -> CloudPlaylistResult {
        guard let target = Self.parse(sourceURL) else {
            throw CloudSourceError.invalidURL
        }
        switch target {
        case .playlist(let rawID):
            let playlistID = rawID.hasPrefix("VL") ? String(rawID.dropFirst(2)) : rawID
            // Try YouTube Music WEB_REMIX first (cleanest music metadata), then www.youtube.com WEB fallback.
            do {
                let result = try await fetchBrowsePlaylist(playlistID: playlistID, client: Self.webRemixClient, cookie: cookie)
                if !result.tracks.isEmpty { return result }
            } catch {
                // Fall back to standard WEB browse below.
            }
            let fallback = try await fetchBrowsePlaylist(playlistID: playlistID, client: Self.webClient, cookie: cookie)
            guard !fallback.tracks.isEmpty else { throw CloudSourceError.emptyPlaylist }
            return fallback

        case .video(let videoID):
            return try await fetchSingleVideo(videoID: videoID, cookie: cookie)
        }
    }

    private func fetchBrowsePlaylist(playlistID: String, client: ClientProfile, cookie: String?) async throws -> CloudPlaylistResult {
        let browseID = playlistID.hasPrefix("VL") ? playlistID : "VL\(playlistID)"
        var allTracks: [CloudTrackInfo] = []
        var seenVideoIDs = Set<String>()
        var playlistTitle: String?

        let firstJSON = try await postInnertube(
            url: URL(string: "\(client.baseURL)/youtubei/v1/browse?prettyPrint=false")!,
            body: [
                "context": ["client": client.clientBody],
                "browseId": browseID
            ],
            client: client,
            cookie: cookie
        )
        let firstPage = Self.parseBrowsePage(firstJSON, seenVideoIDs: &seenVideoIDs)
        if let title = firstPage.title, !title.isEmpty { playlistTitle = title }
        allTracks.append(contentsOf: firstPage.tracks)

        var nextToken = firstPage.continuation
        var visitedTokens = Set<String>()
        var pagesFetched = 1
        while let token = nextToken, !token.isEmpty, visitedTokens.insert(token).inserted, pagesFetched < 30 {
            pagesFetched += 1
            let pageJSON = try await postInnertube(
                url: URL(string: "\(client.baseURL)/youtubei/v1/browse?prettyPrint=false")!,
                body: [
                    "context": ["client": client.clientBody],
                    "continuation": token
                ],
                client: client,
                cookie: cookie
            )
            let parsed = Self.parseBrowsePage(pageJSON, seenVideoIDs: &seenVideoIDs)
            allTracks.append(contentsOf: parsed.tracks)
            nextToken = parsed.continuation
        }

        return CloudPlaylistResult(
            kind: .cloudYouTube,
            remoteID: playlistID,
            title: playlistTitle ?? "YouTube Playlist",
            tracks: allTracks
        )
    }

    private func fetchSingleVideo(videoID: String, cookie: String?) async throws -> CloudPlaylistResult {
        var lastError: Error?
        for client in [Self.webClient, Self.androidPlayerClient] {
            do {
                let json = try await postInnertube(
                    url: URL(string: "\(client.baseURL)/youtubei/v1/player?prettyPrint=false")!,
                    body: [
                        "context": ["client": client.clientBody],
                        "videoId": videoID
                    ],
                    client: client,
                    cookie: cookie
                )
                if let details = json["videoDetails"] as? [String: Any],
                   let title = details["title"] as? String, !title.isEmpty {
                    let author = (details["author"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
                    let duration = Double((details["lengthSeconds"] as? String) ?? "") ?? 0
                    let thumbs = (details["thumbnail"] as? [String: Any])?["thumbnails"] as? [[String: Any]]
                    let art = (thumbs?.last?["url"] as? String).flatMap(Self.normalizeHTTPS)
                    let track = CloudTrackInfo(
                        remoteTrackID: videoID,
                        relativePath: videoID,
                        title: title,
                        artist: (author?.isEmpty == false) ? author! : "YouTube",
                        album: "YouTube",
                        trackNumber: 1,
                        duration: duration,
                        artworkURL: art
                    )
                    return CloudPlaylistResult(kind: .cloudYouTube, remoteID: videoID, title: title, tracks: [track])
                }
            } catch {
                lastError = error
            }
        }
        throw lastError ?? CloudSourceError.emptyPlaylist
    }

    // MARK: Stream Resolution

    func resolveStream(videoID: String, cookie: String?, forceRefresh: Bool = false) async throws -> CloudStreamResult {
        if !forceRefresh, let cached = streamCache[videoID], cached.result.expiresAt > Date().addingTimeInterval(30) {
            return cached.result
        }

        let clients = [Self.androidPlayerClient, Self.iosPlayerClient, Self.webEmbeddedClient]
        var lastError: Error = CloudSourceError.noPlayableStream

        for client in clients {
            do {
                var clientBody = client.clientBody
                var thirdParty: [String: Any]?
                if client.name == "WEB_EMBEDDED_PLAYER" {
                    thirdParty = ["embedUrl": "https://www.youtube.com/"]
                }
                var body: [String: Any] = [
                    "context": thirdParty != nil
                        ? ["client": clientBody, "thirdParty": thirdParty!]
                        : ["client": clientBody],
                    "videoId": videoID,
                    "contentCheckOk": true,
                    "racyCheckOk": true
                ]
                if client.name == "ANDROID" || client.name == "IOS" {
                    clientBody["hl"] = "en"
                    body["params"] = "CgIIAQ=="
                }

                let json = try await postInnertube(
                    url: URL(string: "\(client.baseURL)/youtubei/v1/player?prettyPrint=false")!,
                    body: body,
                    client: client,
                    cookie: nil // Android/iOS Innertube clients work best anonymously for audio streams.
                )
                let candidates = Self.extractStreamCandidates(from: json)
                for candidate in candidates.prefix(3) {
                    let headers = [
                        "User-Agent": client.userAgent,
                        "Accept": "*/*"
                    ]
                    if await probeStreamReadable(url: candidate.url, headers: headers) {
                        let expiresAt = Self.streamExpiration(for: candidate.url)
                        let result = CloudStreamResult(
                            url: candidate.url,
                            headers: headers,
                            fileExtension: candidate.fileExtension,
                            expiresAt: expiresAt
                        )
                        streamCache[videoID] = CachedStream(result: result)
                        return result
                    }
                }
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    private func probeStreamReadable(url: URL, headers: [String: String]) async -> Bool {
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        req.setValue("bytes=0-1023", forHTTPHeaderField: "Range")
        do {
            let response = try await transport.send(req)
            guard response.statusCode == 200 || response.statusCode == 206 else { return false }
            if let ct = response.header("Content-Type")?.lowercased(), ct.contains("text/html") {
                return false
            }
            return !response.data.isEmpty || response.statusCode == 206
        } catch {
            return false
        }
    }

    private func postInnertube(url: URL, body: [String: Any], client: ClientProfile, cookie: String?) async throws -> [String: Any] {
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(client.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue(client.origin, forHTTPHeaderField: "Origin")
        req.setValue("\(client.origin)/", forHTTPHeaderField: "Referer")
        if let cookie, !cookie.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            req.setValue(cookie, forHTTPHeaderField: "Cookie")
            if let auth = Self.sapisidHashHeader(cookie: cookie, origin: client.origin) {
                req.setValue(auth, forHTTPHeaderField: "Authorization")
                req.setValue(client.origin, forHTTPHeaderField: "X-Origin")
            }
        }
        let response = try await transport.send(req)
        guard (200..<300).contains(response.statusCode) else {
            throw CloudSourceError.networkError("YouTube returned HTTP \(response.statusCode).")
        }
        guard let json = try JSONSerialization.jsonObject(with: response.data) as? [String: Any] else {
            throw CloudSourceError.apiError("Unexpected YouTube response format.")
        }
        return json
    }

    // MARK: Innertube JSON Parsing (Testable Static Helpers)

    struct BrowseParseResult: Equatable {
        var title: String?
        var tracks: [CloudTrackInfo]
        var continuation: String?
    }

    struct StreamCandidate: Equatable {
        var itag: Int
        var url: URL
        var fileExtension: String
        var priority: Int
        var bitrate: Int
    }

    nonisolated static func parseBrowsePage(_ json: [String: Any], seenVideoIDs: inout Set<String>) -> BrowseParseResult {
        var title: String?
        var tracks: [CloudTrackInfo] = []
        var continuation: String?

        if let header = json["header"] as? [String: Any] {
            for key in ["musicDetailHeaderRenderer", "musicResponsiveHeaderRenderer", "playlistHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer"] {
                if let sub = header[key] as? [String: Any] {
                    if let t = extractText(sub["title"]), !t.isEmpty {
                        title = t
                        break
                    }
                    if let inner = (sub["header"] as? [String: Any])?["musicDetailHeaderRenderer"] as? [String: Any],
                       let t = extractText(inner["title"]), !t.isEmpty {
                        title = t
                        break
                    }
                }
            }
        }
        if title == nil, let meta = json["metadata"] as? [String: Any],
           let plMeta = meta["playlistMetadataRenderer"] as? [String: Any],
           let t = plMeta["title"] as? String, !t.isEmpty {
            title = t
        }
        if title == nil, let micro = json["microformat"] as? [String: Any],
           let renderer = micro["microformatDataRenderer"] as? [String: Any],
           let t = renderer["title"] as? String, !t.isEmpty {
            title = t
        }

        func walk(_ node: Any) {
            if let dict = node as? [String: Any] {
                if let item = dict["musicResponsiveListItemRenderer"] as? [String: Any] {
                    if let track = parseMusicResponsiveItem(item), seenVideoIDs.insert(track.remoteTrackID).inserted {
                        tracks.append(track)
                    }
                } else if let item = dict["playlistVideoRenderer"] as? [String: Any] {
                    if let track = parsePlaylistVideoItem(item), seenVideoIDs.insert(track.remoteTrackID).inserted {
                        tracks.append(track)
                    }
                }
                if continuation == nil {
                    if let contItem = dict["continuationItemRenderer"] as? [String: Any],
                       let endpoint = contItem["continuationEndpoint"] as? [String: Any],
                       let cmd = endpoint["continuationCommand"] as? [String: Any],
                       let token = cmd["token"] as? String, !token.isEmpty {
                        continuation = token
                    } else if let continuations = dict["continuations"] as? [[String: Any]] {
                        for c in continuations {
                            if let nextData = c["nextContinuationData"] as? [String: Any],
                               let token = nextData["continuation"] as? String, !token.isEmpty {
                                continuation = token
                                break
                            }
                        }
                    }
                }
                for (_, value) in dict {
                    walk(value)
                }
            } else if let array = node as? [Any] {
                for element in array { walk(element) }
            }
        }

        walk(json)
        return BrowseParseResult(title: title, tracks: tracks, continuation: continuation)
    }

    nonisolated private static func parseMusicResponsiveItem(_ item: [String: Any]) -> CloudTrackInfo? {
        var videoID: String?
        if let plData = item["playlistItemData"] as? [String: Any], let id = plData["videoId"] as? String, !id.isEmpty {
            videoID = id
        }
        if videoID == nil,
           let overlay = item["overlay"] as? [String: Any],
           let thumbOverlay = overlay["musicItemThumbnailOverlayRenderer"] as? [String: Any],
           let content = thumbOverlay["content"] as? [String: Any],
           let playBtn = content["musicPlayButtonRenderer"] as? [String: Any],
           let nav = playBtn["playNavigationEndpoint"] as? [String: Any],
           let watch = nav["watchEndpoint"] as? [String: Any],
           let id = watch["videoId"] as? String, !id.isEmpty {
            videoID = id
        }
        if videoID == nil,
           let nav = item["navigationEndpoint"] as? [String: Any],
           let watch = nav["watchEndpoint"] as? [String: Any],
           let id = watch["videoId"] as? String, !id.isEmpty {
            videoID = id
        }

        let flexColumns = (item["flexColumns"] as? [[String: Any]]) ?? []
        var title: String?
        if let col0 = flexColumns.first?["musicResponsiveListItemFlexColumnRenderer"] as? [String: Any] {
            title = extractText(col0["text"])
            if videoID == nil,
               let text = col0["text"] as? [String: Any],
               let runs = text["runs"] as? [[String: Any]] {
                for run in runs {
                    if let nav = run["navigationEndpoint"] as? [String: Any],
                       let watch = nav["watchEndpoint"] as? [String: Any],
                       let id = watch["videoId"] as? String, !id.isEmpty {
                        videoID = id
                        break
                    }
                }
            }
        }
        guard let id = videoID, !id.isEmpty else { return nil }
        let trackTitle = (title?.isEmpty == false) ? title! : id

        var duration: TimeInterval = 0
        if let fixed = (item["fixedColumns"] as? [[String: Any]])?.first?["musicResponsiveListItemFixedColumnRenderer"] as? [String: Any],
           let fixedText = extractText(fixed["text"]),
           let parsed = parseDurationString(fixedText) {
            duration = parsed
        }

        var metadataTokens: [String] = []
        if flexColumns.count > 1,
           let col1 = flexColumns[1]["musicResponsiveListItemFlexColumnRenderer"] as? [String: Any],
           let runs = (col1["text"] as? [String: Any])?["runs"] as? [[String: Any]] {
            for run in runs {
                guard let text = (run["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                      !text.isEmpty, text != "•", text != "·" else { continue }
                if let parsed = parseDurationString(text) {
                    if duration == 0 { duration = parsed }
                    continue
                }
                if isMetadataNoise(text) { continue }
                metadataTokens.append(text)
            }
        }

        var album = "YouTube Music"
        if flexColumns.count > 2,
           let col2 = flexColumns[2]["musicResponsiveListItemFlexColumnRenderer"] as? [String: Any],
           let col2Text = extractText(col2["text"]),
           !col2Text.isEmpty, !isMetadataNoise(col2Text), parseDurationString(col2Text) == nil {
            album = col2Text
        } else if metadataTokens.count >= 2 {
            album = metadataTokens[1]
        }

        let artist = metadataTokens.first ?? "Unknown Artist"
        let thumbURL = extractBestThumbnail(item["thumbnail"])

        return CloudTrackInfo(
            remoteTrackID: id,
            relativePath: id,
            title: trackTitle,
            artist: artist,
            album: album,
            trackNumber: nil,
            duration: duration,
            artworkURL: thumbURL
        )
    }

    nonisolated private static func parsePlaylistVideoItem(_ item: [String: Any]) -> CloudTrackInfo? {
        guard let videoID = item["videoId"] as? String, !videoID.isEmpty else { return nil }
        // Skip unavailable/private deleted video placeholders if unplayable and title is generic.
        if item["isPlayable"] as? Bool == false { return nil }
        let title = extractText(item["title"]) ?? videoID
        let artist = extractText(item["shortBylineText"]) ?? extractText(item["longBylineText"]) ?? "YouTube"
        var duration: TimeInterval = 0
        if let secsStr = item["lengthSeconds"] as? String, let secs = Double(secsStr) {
            duration = secs
        } else if let lenText = extractText(item["lengthText"]), let parsed = parseDurationString(lenText) {
            duration = parsed
        }
        let thumbURL = extractBestThumbnail(item["thumbnail"])
        return CloudTrackInfo(
            remoteTrackID: videoID,
            relativePath: videoID,
            title: title,
            artist: artist,
            album: "YouTube",
            trackNumber: nil,
            duration: duration,
            artworkURL: thumbURL
        )
    }

    nonisolated static func extractStreamCandidates(from playerJSON: [String: Any]) -> [StreamCandidate] {
        guard let streamingData = playerJSON["streamingData"] as? [String: Any] else { return [] }
        let adaptive = (streamingData["adaptiveFormats"] as? [[String: Any]]) ?? []
        let progressive = (streamingData["formats"] as? [[String: Any]]) ?? []

        var candidates: [StreamCandidate] = []
        for format in adaptive + progressive {
            guard let urlString = format["url"] as? String, let url = URL(string: urlString),
                  let itag = format["itag"] as? Int else { continue }
            let mime = (format["mimeType"] as? String ?? "").lowercased()
            let bitrate = (format["bitrate"] as? Int) ?? (format["averageBitrate"] as? Int) ?? 0

            let priority: Int
            let ext: String
            if itag == 141 {
                priority = 0; ext = "m4a"
            } else if itag == 140 {
                priority = 1; ext = "m4a"
            } else if itag == 139 {
                priority = 2; ext = "m4a"
            } else if mime.hasPrefix("audio/mp4") || mime.contains("mp4a") {
                priority = 3; ext = "m4a"
            } else if itag == 18 {
                priority = 4; ext = "mp4"
            } else if mime.hasPrefix("video/mp4") {
                priority = 5; ext = "mp4"
            } else {
                continue
            }

            candidates.append(StreamCandidate(itag: itag, url: url, fileExtension: ext, priority: priority, bitrate: bitrate))
        }

        return candidates.sorted {
            if $0.priority != $1.priority { return $0.priority < $1.priority }
            return $0.bitrate > $1.bitrate
        }
    }

    nonisolated private static func streamExpiration(for url: URL) -> Date {
        if let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
           let expStr = components.queryItems?.first(where: { $0.name == "expire" })?.value,
           let exp = TimeInterval(expStr) {
            let candidate = Date(timeIntervalSince1970: exp - 120)
            return min(candidate, Date().addingTimeInterval(3600))
        }
        return Date().addingTimeInterval(1800)
    }

    nonisolated private static func extractText(_ node: Any?) -> String? {
        guard let dict = node as? [String: Any] else { return nil }
        if let simple = dict["simpleText"] as? String {
            return simple.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let runs = dict["runs"] as? [[String: Any]] {
            let joined = runs.compactMap { $0["text"] as? String }.joined().trimmingCharacters(in: .whitespacesAndNewlines)
            return joined.isEmpty ? nil : joined
        }
        return nil
    }

    nonisolated private static func extractBestThumbnail(_ node: Any?) -> String? {
        guard let dict = node as? [String: Any] else { return nil }
        if let musicThumb = dict["musicThumbnailRenderer"] as? [String: Any] {
            return extractBestThumbnail(musicThumb["thumbnail"])
        }
        if let thumbs = dict["thumbnails"] as? [[String: Any]],
           let url = thumbs.last?["url"] as? String {
            return normalizeHTTPS(url)
        }
        return nil
    }

    nonisolated static func normalizeHTTPS(_ url: String) -> String {
        if url.hasPrefix("//") { return "https:\(url)" }
        if url.hasPrefix("http://") { return "https://" + url.dropFirst(7) }
        return url
    }

    nonisolated static func parseDurationString(_ text: String) -> TimeInterval? {
        let parts = text.trimmingCharacters(in: .whitespacesAndNewlines).split(separator: ":")
        guard parts.count == 2 || parts.count == 3 else { return nil }
        var total = 0
        for part in parts {
            guard let num = Int(part), num >= 0 else { return nil }
            total = total * 60 + num
        }
        return TimeInterval(total)
    }

    nonisolated private static func isMetadataNoise(_ text: String) -> Bool {
        let lower = text.lowercased()
        return lower.hasSuffix(" views") || lower.hasSuffix(" plays") || lower.hasSuffix(" subscribers")
    }
}

// MARK: - Bilibili Client

actor BilibiliClient {
    enum Target: Equatable, Sendable {
        case shortLink(url: URL)
        case favlist(mediaID: String)
        case season(seasonID: String, mid: String?)
        case series(seriesID: String, mid: String?)
        case video(bvid: String?, aid: String?)
    }

    private struct WBIKeys: Sendable {
        let imgKey: String
        let subKey: String
        let fetchedAt: Date
    }

    private struct CachedStream: Sendable {
        let result: CloudStreamResult
    }

    private let transport: any CloudHTTPTransport
    private var buvid3: String?
    private var wbiKeys: WBIKeys?
    private var streamCache: [String: CachedStream] = [:]

    static let userAgent = YouTubeClient.webUserAgent
    static let referer = "https://www.bilibili.com/"

    static let wbiMixinTab: [Int] = [
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
        22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    ]

    init(transport: any CloudHTTPTransport = URLSessionCloudTransport()) {
        self.transport = transport
    }

    // MARK: URL & ID Parsing

    nonisolated static func parse(_ input: String) -> Target? {
        parseExtracted(CloudSourceInput.extract(from: input))
    }

    nonisolated static func parseExtracted(_ input: String) -> Target? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        // Raw BV / av / ml identifiers.
        if !trimmed.contains("/"), !trimmed.contains("."), !trimmed.contains("?") {
            if let bv = extractBVID(from: trimmed), trimmed.count <= 16 {
                return .video(bvid: bv, aid: nil)
            }
            if trimmed.lowercased().hasPrefix("av"),
               let digits = digitsOnly(String(trimmed.dropFirst(2))) {
                return .video(bvid: nil, aid: digits)
            }
            if trimmed.lowercased().hasPrefix("ml"),
               let digits = digitsOnly(String(trimmed.dropFirst(2))) {
                return .favlist(mediaID: digits)
            }
            return nil
        }

        let normalized = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        guard let url = URL(string: normalized),
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let host = components.host?.lowercased() else { return nil }

        if host == "b23.tv" || host.hasSuffix(".b23.tv") {
            return .shortLink(url: url)
        }
        guard host == "bilibili.com" || host.hasSuffix(".bilibili.com") else { return nil }

        let query = Dictionary(
            (components.queryItems ?? []).compactMap { item -> (String, String)? in
                guard let v = item.value, !v.isEmpty else { return nil }
                return (item.name, v)
            },
            uniquingKeysWith: { first, _ in first }
        )
        let path = components.path
        let segments = path.split(separator: "/").map(String.init)
        let pathMid: String? = (host == "space.bilibili.com" && segments.first.flatMap(digitsOnly) != nil)
            ? segments.first
            : nil

        // Favlist: ?fid=..., ?media_id=..., /medialist/detail/ml...
        if let fid = query["fid"].flatMap(digitsOnly) ?? query["media_id"].flatMap(digitsOnly) {
            return .favlist(mediaID: fid)
        }
        if let mlSeg = segments.first(where: { $0.lowercased().hasPrefix("ml") }),
           let mlID = digitsOnly(String(mlSeg.dropFirst(2))) {
            return .favlist(mediaID: mlID)
        }

        // Creator lists: /<mid>/lists/<sid>?type=season|series
        if let listsIdx = segments.firstIndex(of: "lists"), listsIdx + 1 < segments.count,
           let sid = digitsOnly(segments[listsIdx + 1]) {
            if query["type"] == "series" {
                return .series(seriesID: sid, mid: pathMid ?? query["mid"])
            }
            return .season(seasonID: sid, mid: pathMid ?? query["mid"])
        }

        // Collection / Series detail pages.
        if path.contains("collectiondetail"), let sid = query["sid"].flatMap(digitsOnly) {
            return .season(seasonID: sid, mid: pathMid ?? query["mid"])
        }
        if path.contains("seriesdetail"), let sid = query["sid"].flatMap(digitsOnly) {
            return .series(seriesID: sid, mid: pathMid ?? query["mid"])
        }
        if let seasonID = query["season_id"].flatMap(digitsOnly) {
            return .season(seasonID: seasonID, mid: pathMid ?? query["mid"])
        }
        if let seriesID = query["series_id"].flatMap(digitsOnly) {
            return .series(seriesID: seriesID, mid: pathMid ?? query["mid"])
        }

        // Video: /video/BV... or /video/av...
        if let bv = extractBVID(from: path) {
            return .video(bvid: bv, aid: nil)
        }
        if let avSeg = segments.first(where: { $0.lowercased().hasPrefix("av") }),
           let aid = digitsOnly(String(avSeg.dropFirst(2))) {
            return .video(bvid: nil, aid: aid)
        }

        return nil
    }

    nonisolated private static func extractBVID(from text: String) -> String? {
        guard let range = text.range(of: #"BV[0-9A-Za-z]{10,}"#, options: [.regularExpression, .caseInsensitive]) else {
            return nil
        }
        let match = String(text[range])
        return "BV" + match.dropFirst(2)
    }

    nonisolated private static func digitsOnly(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.allSatisfy(\.isNumber) else { return nil }
        return trimmed
    }

    // MARK: WBI Signing (Static & Testable)

    nonisolated static func extractWBIKey(from urlString: String) -> String {
        let last = (urlString as NSString).lastPathComponent
        return (last as NSString).deletingPathExtension
    }

    nonisolated static func mixinKey(imgKey: String, subKey: String) -> String {
        let combined = Array(imgKey + subKey)
        let mixed = wbiMixinTab.compactMap { idx -> Character? in
            idx < combined.count ? combined[idx] : nil
        }
        return String(mixed.prefix(32))
    }

    nonisolated static func signWBI(
        params: [String: String],
        imgKey: String,
        subKey: String,
        timestamp: Int = Int(Date().timeIntervalSince1970)
    ) -> [String: String] {
        var signed = params
        signed["wts"] = String(timestamp)

        let forbidden = CharacterSet(charactersIn: "!'()*")
        let sorted = signed.sorted { $0.key < $1.key }
        let query = sorted.map { key, value in
            let cleaned = String(value.unicodeScalars.filter { !forbidden.contains($0) })
            return "\(wbiPercentEncode(key))=\(wbiPercentEncode(cleaned))"
        }.joined(separator: "&")

        let key = mixinKey(imgKey: imgKey, subKey: subKey)
        let digest = Insecure.MD5.hash(data: Data((query + key).utf8))
        signed["w_rid"] = digest.map { String(format: "%02x", $0) }.joined()
        return signed
    }

    nonisolated private static func wbiPercentEncode(_ value: String) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        var result = ""
        for byte in value.utf8 {
            let scalar = UnicodeScalar(byte)
            if allowed.contains(scalar) {
                result.append(Character(scalar))
            } else {
                result.append(String(format: "%%%02X", byte))
            }
        }
        return result
    }

    // MARK: Playlist / Video Fetching

    func fetchPlaylist(sourceURL: String, cookie: String?) async throws -> CloudPlaylistResult {
        guard var target = Self.parse(sourceURL) else {
            throw CloudSourceError.invalidURL
        }
        if case .shortLink(let shortURL) = target {
            let resolvedURL = try await resolveRedirect(url: shortURL, cookie: cookie)
            guard let parsed = Self.parse(resolvedURL.absoluteString) else {
                throw CloudSourceError.unsupportedSource
            }
            if case .shortLink = parsed { throw CloudSourceError.unsupportedSource }
            target = parsed
        }

        switch target {
        case .shortLink:
            throw CloudSourceError.unsupportedSource
        case .favlist(let mediaID):
            return try await fetchFavlist(mediaID: mediaID, cookie: cookie)
        case .season(let seasonID, let mid):
            return try await fetchSeason(seasonID: seasonID, mid: mid, cookie: cookie)
        case .series(let seriesID, let mid):
            return try await fetchSeries(seriesID: seriesID, mid: mid, cookie: cookie)
        case .video(let bvid, let aid):
            return try await fetchVideoParts(bvid: bvid, aid: aid, cookie: cookie)
        }
    }

    private func resolveRedirect(url: URL, cookie: String?) async throws -> URL {
        let req = try await makeRequest(url: url, cookie: cookie)
        let response = try await transport.send(req)
        if let finalURL = response.finalURL, finalURL != url {
            return finalURL
        }
        if let location = response.header("Location"), let locURL = URL(string: location) {
            return locURL
        }
        return response.finalURL ?? url
    }

    private func fetchFavlist(mediaID: String, cookie: String?) async throws -> CloudPlaylistResult {
        var tracks: [CloudTrackInfo] = []
        var seen = Set<String>()
        var title: String?
        var page = 1

        while page <= 50 {
            let url = URL(string: "https://api.bilibili.com/x/v3/fav/resource/list?media_id=\(mediaID)&pn=\(page)&ps=20&platform=web")!
            let json = try await getBilibiliJSON(url: url, cookie: cookie)
            let data = (json["data"] as? [String: Any]) ?? [:]
            if title == nil, let info = data["info"] as? [String: Any], let t = info["title"] as? String, !t.isEmpty {
                title = t
            }
            let parsed = Self.parseFavlistData(data, seenIDs: &seen)
            tracks.append(contentsOf: parsed)
            let hasMore = (data["has_more"] as? Bool) ?? false
            if !hasMore || parsed.isEmpty { break }
            page += 1
        }

        guard !tracks.isEmpty else { throw CloudSourceError.emptyPlaylist }
        return CloudPlaylistResult(
            kind: .cloudBilibili,
            remoteID: "fav:\(mediaID)",
            title: title ?? "Bilibili Favourites",
            tracks: tracks
        )
    }

    private func fetchSeason(seasonID: String, mid: String?, cookie: String?) async throws -> CloudPlaylistResult {
        var tracks: [CloudTrackInfo] = []
        var seen = Set<String>()
        var title: String?
        var page = 1
        let midParam = mid ?? "0"

        while page <= 50 {
            let url = URL(string: "https://api.bilibili.com/x/polymer/web-space/seasons_archives_list?mid=\(midParam)&season_id=\(seasonID)&sort_reverse=false&page_num=\(page)&page_size=30")!
            let json = try await getBilibiliJSON(url: url, cookie: cookie)
            let data = (json["data"] as? [String: Any]) ?? [:]
            if title == nil, let meta = data["meta"] as? [String: Any], let t = meta["name"] as? String, !t.isEmpty {
                title = t
            }
            let parsed = Self.parseSeasonData(data, seenIDs: &seen)
            tracks.append(contentsOf: parsed)
            let total = (data["page"] as? [String: Any])?["total"] as? Int ?? 0
            if parsed.isEmpty || tracks.count >= total || (data["archives"] as? [[String: Any]])?.count ?? 0 < 30 {
                break
            }
            page += 1
        }

        guard !tracks.isEmpty else { throw CloudSourceError.emptyPlaylist }
        return CloudPlaylistResult(
            kind: .cloudBilibili,
            remoteID: "season:\(seasonID)",
            title: title ?? "Bilibili Collection",
            tracks: tracks
        )
    }

    private func fetchSeries(seriesID: String, mid: String?, cookie: String?) async throws -> CloudPlaylistResult {
        var tracks: [CloudTrackInfo] = []
        var seen = Set<String>()
        var title: String?
        let midParam = mid ?? "0"

        if let metaURL = URL(string: "https://api.bilibili.com/x/series/series?series_id=\(seriesID)"),
           let metaJSON = try? await getBilibiliJSON(url: metaURL, cookie: cookie),
           let metaData = metaJSON["data"] as? [String: Any],
           let meta = metaData["meta"] as? [String: Any],
           let name = meta["name"] as? String, !name.isEmpty {
            title = name
        }

        var page = 1
        while page <= 50 {
            let url = URL(string: "https://api.bilibili.com/x/series/archives?mid=\(midParam)&series_id=\(seriesID)&only_normal=true&sort=desc&pn=\(page)&ps=30")!
            let json = try await getBilibiliJSON(url: url, cookie: cookie)
            let data = (json["data"] as? [String: Any]) ?? [:]
            let parsed = Self.parseSeasonData(data, seenIDs: &seen)
            tracks.append(contentsOf: parsed)
            let total = (data["page"] as? [String: Any])?["total"] as? Int ?? 0
            if parsed.isEmpty || tracks.count >= total || (data["archives"] as? [[String: Any]])?.count ?? 0 < 30 {
                break
            }
            page += 1
        }

        guard !tracks.isEmpty else { throw CloudSourceError.emptyPlaylist }
        return CloudPlaylistResult(
            kind: .cloudBilibili,
            remoteID: "series:\(seriesID)",
            title: title ?? "Bilibili Series",
            tracks: tracks
        )
    }

    private func fetchVideoParts(bvid: String?, aid: String?, cookie: String?) async throws -> CloudPlaylistResult {
        var components = URLComponents(string: "https://api.bilibili.com/x/web-interface/view")!
        if let bvid {
            components.queryItems = [URLQueryItem(name: "bvid", value: bvid)]
        } else if let aid {
            components.queryItems = [URLQueryItem(name: "aid", value: aid)]
        } else {
            throw CloudSourceError.invalidURL
        }
        let json = try await getBilibiliJSON(url: components.url!, cookie: cookie)
        guard let data = json["data"] as? [String: Any] else {
            throw CloudSourceError.emptyPlaylist
        }
        let result = Self.parseVideoViewData(data)
        guard !result.tracks.isEmpty else { throw CloudSourceError.emptyPlaylist }
        return result
    }

    // MARK: Stream Resolution

    func resolveStream(trackID: String, cookie: String?, forceRefresh: Bool = false) async throws -> CloudStreamResult {
        if !forceRefresh, let cached = streamCache[trackID], cached.result.expiresAt > Date().addingTimeInterval(30) {
            return cached.result
        }

        let parts = trackID.split(separator: "#", maxSplits: 1).map(String.init)
        let bvid = parts[0]
        var cid: String? = parts.count > 1 ? parts[1] : nil

        if cid == nil {
            let viewURL = URL(string: "https://api.bilibili.com/x/web-interface/view?bvid=\(bvid)")!
            let viewJSON = try await getBilibiliJSON(url: viewURL, cookie: cookie)
            if let data = viewJSON["data"] as? [String: Any] {
                if let c = data["cid"] as? Int {
                    cid = String(c)
                } else if let pages = data["pages"] as? [[String: Any]], let c = pages.first?["cid"] as? Int {
                    cid = String(c)
                }
            }
        }
        guard let resolvedCID = cid else {
            throw CloudSourceError.noPlayableStream
        }

        let baseParams = [
            "bvid": bvid,
            "cid": resolvedCID,
            "qn": "80",
            "fnval": "4048",
            "fnver": "0",
            "fourk": "1"
        ]

        var playJSON: [String: Any]?
        if let keys = try? await ensureWBIKeys(cookie: cookie) {
            let signed = Self.signWBI(params: baseParams, imgKey: keys.imgKey, subKey: keys.subKey)
            var components = URLComponents(string: "https://api.bilibili.com/x/player/wbi/playurl")!
            components.queryItems = signed.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
            if let url = components.url {
                playJSON = try? await getBilibiliJSON(url: url, cookie: cookie)
            }
        }
        if playJSON == nil || Self.extractPlayURLCandidate(from: playJSON ?? [:]) == nil {
            var components = URLComponents(string: "https://api.bilibili.com/x/player/playurl")!
            components.queryItems = [
                URLQueryItem(name: "bvid", value: bvid),
                URLQueryItem(name: "cid", value: resolvedCID),
                URLQueryItem(name: "qn", value: "80"),
                URLQueryItem(name: "fnval", value: "16"),
                URLQueryItem(name: "fnver", value: "0")
            ]
            if let url = components.url {
                playJSON = try await getBilibiliJSON(url: url, cookie: cookie)
            }
        }

        guard let json = playJSON, let candidate = Self.extractPlayURLCandidate(from: json) else {
            throw CloudSourceError.noPlayableStream
        }

        let cookieHeader = await effectiveCookieHeader(userCookie: cookie)
        var headers = [
            "Referer": Self.referer,
            "User-Agent": Self.userAgent,
            "Accept": "*/*"
        ]
        if let cookieHeader { headers["Cookie"] = cookieHeader }

        let result = CloudStreamResult(
            url: candidate.url,
            headers: headers,
            fileExtension: candidate.fileExtension,
            expiresAt: Date().addingTimeInterval(1800),
            fallbackURLs: candidate.backups
        )
        streamCache[trackID] = CachedStream(result: result)
        return result
    }

    // MARK: Bilibili Session / WBI / HTTP Helpers

    private func ensureBuvid3() async -> String? {
        if let buvid3 { return buvid3 }
        guard let url = URL(string: "https://api.bilibili.com/x/frontend/finger/spi") else { return nil }
        var req = URLRequest(url: url)
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue(Self.referer, forHTTPHeaderField: "Referer")
        guard let response = try? await transport.send(req),
              let json = try? JSONSerialization.jsonObject(with: response.data) as? [String: Any],
              let data = json["data"] as? [String: Any],
              let b3 = data["b_3"] as? String, !b3.isEmpty else {
            return nil
        }
        buvid3 = b3
        return b3
    }

    private func ensureWBIKeys(cookie: String?) async throws -> WBIKeys {
        if let existing = wbiKeys, Date().timeIntervalSince(existing.fetchedAt) < 3600 {
            return existing
        }
        let url = URL(string: "https://api.bilibili.com/x/web-interface/nav")!
        let req = try await makeRequest(url: url, cookie: cookie)
        let response = try await transport.send(req)
        guard let json = try JSONSerialization.jsonObject(with: response.data) as? [String: Any],
              let data = json["data"] as? [String: Any],
              let wbiImg = data["wbi_img"] as? [String: Any],
              let imgURL = wbiImg["img_url"] as? String,
              let subURL = wbiImg["sub_url"] as? String else {
            throw CloudSourceError.apiError("Could not obtain Bilibili WBI signing keys.")
        }
        let keys = WBIKeys(
            imgKey: Self.extractWBIKey(from: imgURL),
            subKey: Self.extractWBIKey(from: subURL),
            fetchedAt: Date()
        )
        wbiKeys = keys
        return keys
    }

    private func effectiveCookieHeader(userCookie: String?) async -> String? {
        let trimmed = userCookie?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if trimmed.contains("buvid3=") { return trimmed }
        guard let b3 = await ensureBuvid3() else {
            return trimmed.isEmpty ? nil : trimmed
        }
        return trimmed.isEmpty ? "buvid3=\(b3)" : "\(trimmed); buvid3=\(b3)"
    }

    private func makeRequest(url: URL, cookie: String?) async throws -> URLRequest {
        var req = URLRequest(url: url)
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        req.setValue(Self.referer, forHTTPHeaderField: "Referer")
        if let cookieHeader = await effectiveCookieHeader(userCookie: cookie) {
            req.setValue(cookieHeader, forHTTPHeaderField: "Cookie")
        }
        return req
    }

    private func getBilibiliJSON(url: URL, cookie: String?) async throws -> [String: Any] {
        let req = try await makeRequest(url: url, cookie: cookie)
        let response = try await transport.send(req)
        guard (200..<300).contains(response.statusCode) else {
            throw CloudSourceError.networkError("Bilibili returned HTTP \(response.statusCode).")
        }
        guard let json = try JSONSerialization.jsonObject(with: response.data) as? [String: Any] else {
            throw CloudSourceError.apiError("Unexpected Bilibili response format.")
        }
        if let code = json["code"] as? Int, code != 0 {
            let msg = (json["message"] as? String) ?? "Bilibili API error (\(code))."
            throw CloudSourceError.apiError(msg)
        }
        return json
    }

    // MARK: Bilibili JSON Parsing (Static & Testable)

    nonisolated static func parseFavlistData(_ data: [String: Any], seenIDs: inout Set<String>) -> [CloudTrackInfo] {
        let medias = (data["medias"] as? [[String: Any]]) ?? []
        let albumTitle = ((data["info"] as? [String: Any])?["title"] as? String) ?? "Bilibili Favourites"
        var tracks: [CloudTrackInfo] = []
        for item in medias {
            let attr = item["attr"] as? Int ?? 0
            guard attr != 9 else { continue }
            guard let bvid = item["bvid"] as? String, !bvid.isEmpty else { continue }
            guard seenIDs.insert(bvid).inserted else { continue }
            let cid = (item["ugc"] as? [String: Any])?["first_cid"] as? Int
            let remoteTrackID = cid.map { "\(bvid)#\($0)" } ?? bvid
            let title = (item["title"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? bvid
            let artist = ((item["upper"] as? [String: Any])?["name"] as? String) ?? "Bilibili"
            let duration = TimeInterval((item["duration"] as? Int) ?? 0)
            let cover = (item["cover"] as? String).flatMap(YouTubeClient.normalizeHTTPS)
            tracks.append(CloudTrackInfo(
                remoteTrackID: remoteTrackID,
                relativePath: bvid,
                title: title,
                artist: artist,
                album: albumTitle,
                trackNumber: nil,
                duration: duration,
                artworkURL: cover
            ))
        }
        return tracks
    }

    nonisolated static func parseSeasonData(_ data: [String: Any], seenIDs: inout Set<String>) -> [CloudTrackInfo] {
        let archives = (data["archives"] as? [[String: Any]]) ?? []
        let meta = data["meta"] as? [String: Any]
        let albumTitle = (meta?["name"] as? String) ?? "Bilibili Collection"
        var tracks: [CloudTrackInfo] = []
        for item in archives {
            guard let bvid = item["bvid"] as? String, !bvid.isEmpty else { continue }
            guard seenIDs.insert(bvid).inserted else { continue }
            let title = (item["title"] as? String) ?? bvid
            let artist = (item["author"] as? String)
                ?? ((item["owner"] as? [String: Any])?["name"] as? String)
                ?? "Bilibili"
            let duration = TimeInterval((item["duration"] as? Int) ?? 0)
            let cover = (item["pic"] as? String).flatMap(YouTubeClient.normalizeHTTPS)
            tracks.append(CloudTrackInfo(
                remoteTrackID: bvid,
                relativePath: bvid,
                title: title,
                artist: artist,
                album: albumTitle,
                trackNumber: nil,
                duration: duration,
                artworkURL: cover
            ))
        }
        return tracks
    }

    nonisolated static func parseVideoViewData(_ data: [String: Any]) -> CloudPlaylistResult {
        let bvid = (data["bvid"] as? String) ?? "av\(data["aid"] ?? "")"
        let videoTitle = (data["title"] as? String) ?? bvid
        let artist = ((data["owner"] as? [String: Any])?["name"] as? String) ?? "Bilibili"
        let cover = (data["pic"] as? String).flatMap(YouTubeClient.normalizeHTTPS)
        let totalDuration = TimeInterval((data["duration"] as? Int) ?? 0)
        let pages = (data["pages"] as? [[String: Any]]) ?? []

        if pages.count > 1 {
            let tracks: [CloudTrackInfo] = pages.enumerated().compactMap { idx, page in
                guard let cid = page["cid"] as? Int else { return nil }
                let pageNum = (page["page"] as? Int) ?? (idx + 1)
                let partName = (page["part"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                let title = partName.isEmpty ? "\(videoTitle) (P\(pageNum))" : partName
                let dur = TimeInterval((page["duration"] as? Int) ?? 0)
                let trackID = "\(bvid)#\(cid)"
                return CloudTrackInfo(
                    remoteTrackID: trackID,
                    relativePath: trackID,
                    title: title,
                    artist: artist,
                    album: videoTitle,
                    trackNumber: pageNum,
                    duration: dur > 0 ? dur : totalDuration,
                    artworkURL: cover
                )
            }
            return CloudPlaylistResult(kind: .cloudBilibili, remoteID: bvid, title: videoTitle, tracks: tracks)
        } else {
            let cid = (pages.first?["cid"] as? Int) ?? (data["cid"] as? Int)
            let remoteTrackID = cid.map { "\(bvid)#\($0)" } ?? bvid
            let track = CloudTrackInfo(
                remoteTrackID: remoteTrackID,
                relativePath: bvid,
                title: videoTitle,
                artist: artist,
                album: videoTitle,
                trackNumber: 1,
                duration: totalDuration,
                artworkURL: cover
            )
            return CloudPlaylistResult(kind: .cloudBilibili, remoteID: bvid, title: videoTitle, tracks: [track])
        }
    }

    struct PlayURLCandidate: Equatable {
        var url: URL
        var backups: [URL]
        var fileExtension: String
    }

    nonisolated static func extractPlayURLCandidate(from json: [String: Any]) -> PlayURLCandidate? {
        guard let data = json["data"] as? [String: Any] else { return nil }
        if let dash = data["dash"] as? [String: Any] {
            var audioStreams: [[String: Any]] = (dash["audio"] as? [[String: Any]]) ?? []
            if let flac = (dash["flac"] as? [String: Any])?["audio"] as? [String: Any] {
                audioStreams.append(flac)
            }
            let preferredIDs = [30280, 30232, 30216, 30251]
            let sorted = audioStreams.sorted { a, b in
                let idA = a["id"] as? Int ?? 0
                let idB = b["id"] as? Int ?? 0
                let rankA = preferredIDs.firstIndex(of: idA) ?? preferredIDs.count
                let rankB = preferredIDs.firstIndex(of: idB) ?? preferredIDs.count
                if rankA != rankB { return rankA < rankB }
                return (a["bandwidth"] as? Int ?? 0) > (b["bandwidth"] as? Int ?? 0)
            }
            for stream in sorted {
                let urlStr = (stream["baseUrl"] as? String) ?? (stream["base_url"] as? String)
                guard let urlStr, let url = URL(string: urlStr) else { continue }
                let backupStrs = (stream["backupUrl"] as? [String]) ?? (stream["backup_url"] as? [String]) ?? []
                let backups = backupStrs.compactMap(URL.init(string:))
                let id = stream["id"] as? Int ?? 0
                let mime = (stream["mimeType"] as? String ?? stream["mime_type"] as? String ?? "").lowercased()
                let ext = (id == 30251 || mime.contains("flac")) ? "flac" : "m4a"
                return PlayURLCandidate(url: url, backups: backups, fileExtension: ext)
            }
        }
        if let durl = data["durl"] as? [[String: Any]], let first = durl.first,
           let urlStr = first["url"] as? String, let url = URL(string: urlStr) {
            let backups = ((first["backup_url"] as? [String]) ?? []).compactMap(URL.init(string:))
            return PlayURLCandidate(url: url, backups: backups, fileExtension: "mp4")
        }
        return nil
    }
}
