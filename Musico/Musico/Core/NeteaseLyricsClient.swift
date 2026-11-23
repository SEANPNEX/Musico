import Foundation

/// Client for NetEase Cloud Music (`music.163.com`) public search and lyric endpoints.
struct NeteaseLyricsClient: Sendable {
    private static let searchEndpoint = URL(string: "https://music.163.com/api/search/get")!
    private static let cloudSearchEndpoint = URL(string: "https://music.163.com/api/cloudsearch/pc")!
    private static let lyricBaseURL = URL(string: "https://music.163.com/api/song/lyric")!

    private let transport: any CloudHTTPTransport

    init(transport: any CloudHTTPTransport = URLSessionCloudTransport()) {
        self.transport = transport
    }

    func search(query: CloudLyricsQuery) async throws -> [CloudLyricsCandidate] {
        let keyword = [query.trimmedTitle, query.trimmedArtist]
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        guard !keyword.isEmpty else {
            throw CloudLyricsError.emptyQuery
        }

        let songs = try await searchSongs(keyword: keyword)
        guard !songs.isEmpty else { return [] }

        let topSongs = selectTopSongs(songs, for: query, limit: 5)
        var results: [CloudLyricsCandidate] = []

        try await withThrowingTaskGroup(of: CloudLyricsCandidate?.self) { group in
            for song in topSongs {
                group.addTask {
                    try await fetchLyricCandidate(for: song)
                }
            }
            for try await candidate in group {
                if let candidate {
                    results.append(candidate)
                }
            }
        }

        return CloudLyricsScorer.rankAndDeduplicate(results, for: query)
    }

    private func searchSongs(keyword: String) async throws -> [NeteaseSong] {
        let primarySongs = try await postLegacySearch(keyword: keyword)
        if !primarySongs.isEmpty {
            return primarySongs
        }
        return try await getCloudSearch(keyword: keyword)
    }

    private func postLegacySearch(keyword: String) async throws -> [NeteaseSong] {
        var components = URLComponents()
        components.queryItems = [
            URLQueryItem(name: "s", value: keyword),
            URLQueryItem(name: "type", value: "1"),
            URLQueryItem(name: "offset", value: "0"),
            URLQueryItem(name: "limit", value: "10")
        ]
        let bodyString = components.percentEncodedQuery ?? ""
        var request = URLRequest(url: Self.searchEndpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        applyNeteaseHeaders(&request)
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(bodyString.utf8)

        let response = try await send(request)
        if response.statusCode == 429 || response.statusCode == 503 {
            let retryAfter = response.header("Retry-After").flatMap { Int($0.trimmingCharacters(in: .whitespaces)) }
            throw CloudLyricsError.rateLimited(retryAfterSeconds: retryAfter)
        }
        guard (200..<300).contains(response.statusCode) else {
            return []
        }
        return decodeSearchSongs(from: response.data)
    }

    private func getCloudSearch(keyword: String) async throws -> [NeteaseSong] {
        var components = URLComponents(url: Self.cloudSearchEndpoint, resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "s", value: keyword),
            URLQueryItem(name: "type", value: "1"),
            URLQueryItem(name: "offset", value: "0"),
            URLQueryItem(name: "limit", value: "10")
        ]
        guard let url = components.url else { return [] }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 15
        applyNeteaseHeaders(&request)

        let response = try await send(request)
        if response.statusCode == 429 || response.statusCode == 503 {
            let retryAfter = response.header("Retry-After").flatMap { Int($0.trimmingCharacters(in: .whitespaces)) }
            throw CloudLyricsError.rateLimited(retryAfterSeconds: retryAfter)
        }
        guard (200..<300).contains(response.statusCode) else {
            throw CloudLyricsError.network("NetEase HTTP \(response.statusCode)")
        }
        return decodeSearchSongs(from: response.data)
    }

    private func fetchLyricCandidate(for song: NeteaseSong) async throws -> CloudLyricsCandidate? {
        var components = URLComponents(url: Self.lyricBaseURL, resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "os", value: "pc"),
            URLQueryItem(name: "id", value: String(song.id)),
            URLQueryItem(name: "lv", value: "-1"),
            URLQueryItem(name: "tv", value: "-1"),
            URLQueryItem(name: "kv", value: "-1")
        ]
        guard let url = components.url else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 15
        applyNeteaseHeaders(&request)

        let response = try await send(request)
        if response.statusCode == 429 || response.statusCode == 503 {
            let retryAfter = response.header("Retry-After").flatMap { Int($0.trimmingCharacters(in: .whitespaces)) }
            throw CloudLyricsError.rateLimited(retryAfterSeconds: retryAfter)
        }
        guard (200..<300).contains(response.statusCode),
              let payload = try? JSONDecoder().decode(NeteaseLyricResponse.self, from: response.data) else {
            return nil
        }

        let primaryRaw = Self.normalizeNeteaseLyricBody(payload.lrc?.lyric)
        let transRaw = Self.normalizeNeteaseLyricBody(payload.tlyric?.lyric)

        if payload.nolyric == true {
            return CloudLyricsCandidate(
                id: "\(CloudLyricsProviderID.netease):\(song.id)",
                providerID: CloudLyricsProviderID.netease,
                sourceName: "NetEase",
                title: song.title,
                artist: song.artistName,
                album: song.albumName,
                durationSec: song.durationSec,
                isInstrumental: true,
                syncedLyrics: nil,
                plainLyrics: nil,
                translatedLyrics: nil
            )
        }

        guard let primaryRaw, !primaryRaw.isEmpty else {
            return nil
        }
        let parsed = LRCParser.parse(primaryRaw)
        guard !parsed.lines.isEmpty else { return nil }

        let candidate = CloudLyricsCandidate(
            id: "\(CloudLyricsProviderID.netease):\(song.id)",
            providerID: CloudLyricsProviderID.netease,
            sourceName: "NetEase",
            title: song.title,
            artist: song.artistName,
            album: song.albumName,
            durationSec: song.durationSec,
            isInstrumental: false,
            syncedLyrics: parsed.isTimed ? primaryRaw : nil,
            plainLyrics: parsed.isTimed ? nil : primaryRaw,
            translatedLyrics: transRaw
        )
        return candidate.hasUsableContent ? candidate : nil
    }

    private func send(_ request: URLRequest) async throws -> HTTPResponseData {
        do {
            return try await transport.send(request)
        } catch let err as CloudLyricsError {
            throw err
        } catch {
            throw CloudLyricsError.network(error.localizedDescription)
        }
    }

    private func applyNeteaseHeaders(_ request: inout URLRequest) {
        request.setValue("https://music.163.com/", forHTTPHeaderField: "Referer")
        request.setValue("https://music.163.com", forHTTPHeaderField: "Origin")
        request.setValue(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            forHTTPHeaderField: "User-Agent"
        )
        request.setValue("appver=2.0.2; os=pc", forHTTPHeaderField: "Cookie")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
    }

    private func selectTopSongs(_ songs: [NeteaseSong], for query: CloudLyricsQuery, limit: Int) -> [NeteaseSong] {
        let sorted = songs.enumerated().sorted { lhs, rhs in
            let sl = songPreScore(lhs.element, query: query)
            let sr = songPreScore(rhs.element, query: query)
            if abs(sl - sr) > 0.001 { return sl > sr }
            return lhs.offset < rhs.offset
        }
        return Array(sorted.prefix(limit).map(\.element))
    }

    private func songPreScore(_ song: NeteaseSong, query: CloudLyricsQuery) -> Double {
        var score: Double = 0
        if !query.trimmedTitle.isEmpty {
            score += LyricsNameMatcher.similarity(song.title, query.trimmedTitle) * 50
        }
        if !query.trimmedArtist.isEmpty {
            score += LyricsNameMatcher.similarity(song.artistName, query.trimmedArtist) * 30
        }
        if let qDur = query.durationSec, qDur > 0, let sDur = song.durationSec, sDur > 0 {
            let diff = abs(sDur - qDur)
            if diff <= 2 { score += 20 }
            else if diff <= 6 { score += 12 }
            else if diff <= 12 { score += 4 }
        }
        return score
    }

    private func decodeSearchSongs(from data: Data) -> [NeteaseSong] {
        guard let envelope = try? JSONDecoder().decode(NeteaseSearchResponse.self, from: data),
              let rawSongs = envelope.result?.songs else {
            return []
        }
        return rawSongs.compactMap { $0.normalized }
    }

    /// Strips/converts modern NetEase inline JSON credit lines (`{"t":0,"c":[{"tx":"..."}]}`) if present.
    static func normalizeNeteaseLyricBody(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        var lines: [String] = []
        for rawLine in trimmed.components(separatedBy: .newlines) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty else { continue }
            if line.hasPrefix("{"), line.hasSuffix("}"),
               let jsonData = line.data(using: .utf8),
               let jsonObj = try? JSONDecoder().decode(NeteaseJSONLyricLine.self, from: jsonData) {
                let text = (jsonObj.c ?? []).compactMap(\.tx).joined().trimmingCharacters(in: .whitespaces)
                if !text.isEmpty {
                    let sec = Double(jsonObj.t ?? 0) / 1000.0
                    lines.append("\(CloudLyricsFormatter.formatLRCTimestamp(sec))\(text)")
                }
                continue
            }
            lines.append(line)
        }
        let result = lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        return result.isEmpty ? nil : result
    }
}

private struct NeteaseSearchResponse: Decodable {
    let code: Int?
    let result: NeteaseSearchResult?
}

private struct NeteaseSearchResult: Decodable {
    let songs: [NeteaseSongDTO]?
}

private struct NeteaseArtistDTO: Decodable {
    let name: String?
}

private struct NeteaseAlbumDTO: Decodable {
    let name: String?
}

private struct NeteaseSongDTO: Decodable {
    let id: Int?
    let name: String?
    let artists: [NeteaseArtistDTO]?
    let ar: [NeteaseArtistDTO]?
    let album: NeteaseAlbumDTO?
    let al: NeteaseAlbumDTO?
    let duration: Double?
    let dt: Double?

    var normalized: NeteaseSong? {
        guard let id, let name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        let artistList = (ar ?? artists ?? [])
            .compactMap { $0.name?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let albumName = (al ?? album)?.name?.trimmingCharacters(in: .whitespacesAndNewlines)
        let ms = dt ?? duration
        let sec = (ms != nil && ms! > 0) ? ms! / 1000.0 : nil
        return NeteaseSong(
            id: id,
            title: name.trimmingCharacters(in: .whitespacesAndNewlines),
            artistName: artistList.joined(separator: " / "),
            albumName: (albumName?.isEmpty == false) ? albumName : nil,
            durationSec: sec
        )
    }
}

private struct NeteaseSong: Sendable {
    let id: Int
    let title: String
    let artistName: String
    let albumName: String?
    let durationSec: Double?
}

private struct NeteaseLyricResponse: Decodable {
    let nolyric: Bool?
    let uncollected: Bool?
    let lrc: NeteaseLyricSection?
    let tlyric: NeteaseLyricSection?
}

private struct NeteaseLyricSection: Decodable {
    let lyric: String?
}

private struct NeteaseJSONLyricLine: Decodable {
    let t: Double?
    let c: [NeteaseJSONCreditToken]?
}

private struct NeteaseJSONCreditToken: Decodable {
    let tx: String?
}
