import Foundation

/// Client for the public LRCLIB lyrics API (`lrclib.net`).
struct LrclibClient: Sendable {
    private static let baseURL = URL(string: "https://lrclib.net")!

    private let transport: any CloudHTTPTransport
    private let userAgent: String

    init(
        transport: any CloudHTTPTransport = URLSessionCloudTransport(),
        appVersion: String = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.2.3"
    ) {
        self.transport = transport
        self.userAgent = "Musico/\(appVersion) (https://github.com/SEANPNEX/Musico)"
    }

    func search(query: CloudLyricsQuery) async throws -> [CloudLyricsCandidate] {
        let title = query.trimmedTitle
        let artist = query.trimmedArtist
        guard !title.isEmpty || !artist.isEmpty else {
            throw CloudLyricsError.emptyQuery
        }

        var collected: [LrclibRecord] = []
        var seenIDs = Set<Int>()

        if !title.isEmpty && !artist.isEmpty {
            async let exactHit = fetchExact(query: query)
            async let structuredHits = fetchSearch(params: [
                URLQueryItem(name: "track_name", value: title),
                URLQueryItem(name: "artist_name", value: artist)
            ])

            if let exact = try await exactHit, seenIDs.insert(exact.id).inserted {
                collected.append(exact)
            }
            for item in try await structuredHits where seenIDs.insert(item.id).inserted {
                collected.append(item)
            }

            if collected.isEmpty {
                let fallback = try await fetchSearch(params: [
                    URLQueryItem(name: "q", value: "\(title) \(artist)")
                ])
                for item in fallback where seenIDs.insert(item.id).inserted {
                    collected.append(item)
                }
            }
        } else {
            let keyword = !title.isEmpty ? title : artist
            let hits = try await fetchSearch(params: [
                URLQueryItem(name: "q", value: keyword)
            ])
            for item in hits where seenIDs.insert(item.id).inserted {
                collected.append(item)
            }
        }

        let mapped = collected.compactMap { $0.toCandidate() }
        return CloudLyricsScorer.rankAndDeduplicate(mapped, for: query)
    }

    private func fetchExact(query: CloudLyricsQuery) async throws -> LrclibRecord? {
        var components = URLComponents(url: Self.baseURL.appendingPathComponent("/api/get"), resolvingAgainstBaseURL: false)!
        var items: [URLQueryItem] = [
            URLQueryItem(name: "track_name", value: query.trimmedTitle),
            URLQueryItem(name: "artist_name", value: query.trimmedArtist)
        ]
        if let album = query.trimmedAlbum {
            items.append(URLQueryItem(name: "album_name", value: album))
        }
        if let duration = query.durationSec, duration.isFinite, duration > 0 {
            items.append(URLQueryItem(name: "duration", value: String(Int(duration.rounded()))))
        }
        components.queryItems = items
        guard let url = components.url else { return nil }

        let response = try await sendGET(url: url)
        if response.statusCode == 404 {
            return nil
        }
        try checkRateLimitOrError(response: response)
        return try? JSONDecoder().decode(LrclibRecord.self, from: response.data)
    }

    private func fetchSearch(params: [URLQueryItem]) async throws -> [LrclibRecord] {
        var components = URLComponents(url: Self.baseURL.appendingPathComponent("/api/search"), resolvingAgainstBaseURL: false)!
        components.queryItems = params
        guard let url = components.url else { return [] }

        let response = try await sendGET(url: url)
        if response.statusCode == 404 {
            return []
        }
        try checkRateLimitOrError(response: response)
        return (try? JSONDecoder().decode([LrclibRecord].self, from: response.data)) ?? []
    }

    private func sendGET(url: URL) async throws -> HTTPResponseData {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.timeoutInterval = 15
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        do {
            return try await transport.send(request)
        } catch let err as CloudLyricsError {
            throw err
        } catch {
            throw CloudLyricsError.network(error.localizedDescription)
        }
    }

    private func checkRateLimitOrError(response: HTTPResponseData) throws {
        if response.statusCode == 429 || response.statusCode == 503 {
            let retryAfter = response.header("Retry-After")
                .flatMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
            throw CloudLyricsError.rateLimited(retryAfterSeconds: retryAfter)
        }
        guard (200..<300).contains(response.statusCode) else {
            throw CloudLyricsError.network("LRCLIB HTTP \(response.statusCode)")
        }
    }
}

private struct LrclibRecord: Decodable {
    let id: Int
    let name: String?
    let trackName: String?
    let artistName: String?
    let albumName: String?
    let duration: Double?
    let instrumental: Bool?
    let plainLyrics: String?
    let syncedLyrics: String?

    func toCandidate() -> CloudLyricsCandidate? {
        let title = (trackName ?? name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let artist = (artistName ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let synced = syncedLyrics?.trimmingCharacters(in: .whitespacesAndNewlines)
        let plain = plainLyrics?.trimmingCharacters(in: .whitespacesAndNewlines)
        let isInst = instrumental ?? false
        guard !title.isEmpty || isInst else { return nil }

        let candidate = CloudLyricsCandidate(
            id: "\(CloudLyricsProviderID.lrclib):\(id)",
            providerID: CloudLyricsProviderID.lrclib,
            sourceName: "LRCLIB",
            title: title,
            artist: artist,
            album: albumName?.trimmingCharacters(in: .whitespacesAndNewlines),
            durationSec: duration,
            isInstrumental: isInst,
            syncedLyrics: (synced?.isEmpty == false) ? synced : nil,
            plainLyrics: (plain?.isEmpty == false) ? plain : nil,
            translatedLyrics: nil
        )
        return candidate.hasUsableContent ? candidate : nil
    }
}
