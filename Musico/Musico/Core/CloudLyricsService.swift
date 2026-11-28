import Foundation

struct CloudLyricsExtensionDescriptor: Equatable, Hashable, Sendable {
    let id: String
    let name: String
}

/// Coordinates on-demand cloud lyrics search across built-in LRCLIB
/// and active JS extensions with `capabilities: ["lyrics"]`.
/// Never invoked automatically during track playback.
struct CloudLyricsService: Sendable {
    let lrclib: LrclibClient
    let extensionSearch: (@Sendable (_ extensionID: String, _ query: CloudLyricsQuery) async throws -> [CloudLyricsCandidate])?

    init(
        transport: any CloudHTTPTransport = URLSessionCloudTransport(),
        appVersion: String = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.2.3",
        extensionSearch: (@Sendable (_ extensionID: String, _ query: CloudLyricsQuery) async throws -> [CloudLyricsCandidate])? = nil
    ) {
        self.lrclib = LrclibClient(transport: transport, appVersion: appVersion)
        self.extensionSearch = extensionSearch
    }

    static func sourceOptions(
        extensions: [CloudLyricsExtensionDescriptor],
        autoLabel: String
    ) -> [CloudLyricsSourceOption] {
        if extensions.isEmpty {
            return [CloudLyricsSourceOption(selection: .lrclib, label: "LRCLIB")]
        }
        var options: [CloudLyricsSourceOption] = [
            CloudLyricsSourceOption(selection: .auto, label: autoLabel),
            CloudLyricsSourceOption(selection: .lrclib, label: "LRCLIB")
        ]
        for ext in extensions {
            options.append(CloudLyricsSourceOption(selection: .ext(id: ext.id), label: ext.name))
        }
        return options
    }

    func search(
        query: CloudLyricsQuery,
        source: CloudLyricsSourceSelection,
        activeExtensions: [CloudLyricsExtensionDescriptor]
    ) async throws -> CloudLyricsSearchResponse {
        guard !query.isEmpty else {
            throw CloudLyricsError.emptyQuery
        }

        switch source {
        case .lrclib:
            let candidates = try await lrclib.search(query: query)
            return CloudLyricsSearchResponse(candidates: candidates, failures: [])

        case .ext(let id):
            let extDescriptor = activeExtensions.first { $0.id == id }
            guard let extensionSearch, let extDescriptor else {
                throw CloudLyricsError.providerUnavailable(id)
            }
            let raw = try await extensionSearch(extDescriptor.id, query)
            let ranked = CloudLyricsScorer.rankAndDeduplicate(raw, for: query)
            return CloudLyricsSearchResponse(candidates: ranked, failures: [])

        case .auto:
            return try await searchAllSources(query: query, activeExtensions: activeExtensions)
        }
    }

    private func searchAllSources(
        query: CloudLyricsQuery,
        activeExtensions: [CloudLyricsExtensionDescriptor]
    ) async throws -> CloudLyricsSearchResponse {
        enum TaskOutcome: Sendable {
            case success([CloudLyricsCandidate])
            case failure(CloudLyricsSourceFailure)
        }

        let lrclibClient = self.lrclib
        let extSearcher = self.extensionSearch

        var totalSources = 1
        var successCount = 0
        var allCandidates: [CloudLyricsCandidate] = []
        var failures: [CloudLyricsSourceFailure] = []
        var firstError: Error?

        await withTaskGroup(of: TaskOutcome.self) { group in
            group.addTask {
                do {
                    return .success(try await lrclibClient.search(query: query))
                } catch {
                    return .failure(CloudLyricsSourceFailure(
                        providerID: CloudLyricsProviderID.lrclib,
                        sourceName: "LRCLIB",
                        message: error.localizedDescription
                    ))
                }
            }

            if let extSearcher {
                for ext in activeExtensions {
                    totalSources += 1
                    group.addTask {
                        do {
                            return .success(try await extSearcher(ext.id, query))
                        } catch {
                            return .failure(CloudLyricsSourceFailure(
                                providerID: "ext.\(ext.id)",
                                sourceName: ext.name,
                                message: error.localizedDescription
                            ))
                        }
                    }
                }
            }

            for await outcome in group {
                switch outcome {
                case .success(let list):
                    successCount += 1
                    allCandidates.append(contentsOf: list)
                case .failure(let failure):
                    failures.append(failure)
                    if firstError == nil {
                        firstError = CloudLyricsError.network(failure.message)
                    }
                }
            }
        }

        if successCount == 0, totalSources > 0, let firstError {
            throw firstError
        }

        let ranked = CloudLyricsScorer.rankAndDeduplicate(allCandidates, for: query)
        return CloudLyricsSearchResponse(candidates: ranked, failures: failures)
    }
}
