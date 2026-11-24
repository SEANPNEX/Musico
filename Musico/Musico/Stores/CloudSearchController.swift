import Foundation
import Observation

enum SongsSearchSource: Hashable, Sendable {
    case local
    case cloud(extensionID: String)

    var extensionID: String? {
        switch self {
        case .local: nil
        case .cloud(let id): id
        }
    }

    var isCloud: Bool {
        if case .cloud = self { return true }
        return false
    }
}

struct CloudSearchErrorInfo: Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case authRequired
        case rateLimited
        case permissionDenied
        case extensionUnavailable
        case network
        case runtime
    }

    var kind: Kind
    var code: String?
    var message: String
}

/// Manages explicit-submit cloud search state, pagination, deduplication, and cancellation for `SongsView`.
@MainActor @Observable
final class CloudSearchController {
    typealias SearchHandler = @Sendable (
        _ extensionID: String,
        _ query: String,
        _ kind: CloudSearchKindFilter,
        _ limit: Int,
        _ cursor: String?
    ) async throws -> CloudSearchPage

    private(set) var results: [CloudSearchResult] = []
    private(set) var nextCursor: String? = nil
    private(set) var isLoading = false
    private(set) var isLoadingMore = false
    private(set) var error: CloudSearchErrorInfo? = nil
    private(set) var loadMoreError: CloudSearchErrorInfo? = nil
    private(set) var selectedKind: CloudSearchKindFilter = .all
    private(set) var activeQuery: String = ""
    private(set) var activeExtensionID: String? = nil
    private(set) var hasSubmitted = false

    @ObservationIgnored private var seenKeys: Set<CloudSearchResult.DedupKey> = []
    @ObservationIgnored private var seenCursors: Set<String> = []
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var currentTask: Task<Void, Never>? = nil
    @ObservationIgnored private let searchHandler: SearchHandler?

    nonisolated init(searchHandler: SearchHandler? = nil) {
        self.searchHandler = searchHandler
    }

    var trackResults: [CloudSearchResult] {
        results.filter { $0.kind == .track }
    }

    func kindOptions(for descriptor: CloudSearchExtensionDescriptor) -> [CloudSearchKindFilter] {
        guard descriptor.searchKinds.count > 1 else { return [] }
        return [.all] + descriptor.searchKinds.map(CloudSearchKindFilter.init(searchKind:))
    }

    func effectiveKind(for descriptor: CloudSearchExtensionDescriptor) -> CloudSearchKindFilter {
        if descriptor.searchKinds.count == 1, let only = descriptor.searchKinds.first {
            return CloudSearchKindFilter(searchKind: only)
        }
        if selectedKind == .all {
            return .all
        }
        if descriptor.searchKinds.contains(where: { CloudSearchKindFilter(searchKind: $0) == selectedKind }) {
            return selectedKind
        }
        return .all
    }

    func switchSource(
        to newSource: SongsSearchSource,
        currentQuery: String,
        activeExtensions: [CloudSearchExtensionDescriptor],
        navigation: AppNavigation,
        extensionStore: ExtensionStore
    ) {
        let oldSource = navigation.searchSource
        guard oldSource != newSource else { return }
        navigation.searchSource = newSource

        switch newSource {
        case .local:
            cancelAndClear()
        case .cloud(let extID):
            let wasCloud = oldSource.isCloud
            cancelAndClear()
            activeExtensionID = extID
            navigation.isSearchPresented = true
            let descriptor = activeExtensions.first { $0.id == extID }
            if let descriptor, descriptor.searchKinds.count == 1, let only = descriptor.searchKinds.first {
                selectedKind = CloudSearchKindFilter(searchKind: only)
            } else {
                selectedKind = .all
            }
            let trimmed = currentQuery.trimmingCharacters(in: .whitespacesAndNewlines)
            if wasCloud, !trimmed.isEmpty, let descriptor {
                submitSearch(query: trimmed, descriptor: descriptor, extensionStore: extensionStore)
            }
        }
    }

    func selectKind(
        _ kind: CloudSearchKindFilter,
        currentQuery: String,
        descriptor: CloudSearchExtensionDescriptor,
        extensionStore: ExtensionStore
    ) {
        guard selectedKind != kind else { return }
        selectedKind = kind
        let trimmed = currentQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty && (hasSubmitted || !results.isEmpty || isLoading || error != nil) {
            submitSearch(query: trimmed, descriptor: descriptor, extensionStore: extensionStore)
        }
    }

    func submitSearch(
        query: String,
        descriptor: CloudSearchExtensionDescriptor,
        extensionStore: ExtensionStore
    ) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }

        cancelCurrentTask()
        generation += 1
        let gen = generation

        activeExtensionID = descriptor.id
        activeQuery = trimmed
        hasSubmitted = true
        results = []
        nextCursor = nil
        error = nil
        loadMoreError = nil
        seenKeys = []
        seenCursors = []
        isLoading = true
        isLoadingMore = false

        let requestKind = effectiveKind(for: descriptor)
        let extID = descriptor.id
        let handler = self.searchHandler

        currentTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let page: CloudSearchPage
                if let handler {
                    page = try await handler(extID, trimmed, requestKind, 20, nil)
                } else {
                    page = try await extensionStore.search(
                        extensionID: extID,
                        query: trimmed,
                        kind: requestKind,
                        limit: 20,
                        cursor: nil
                    )
                }
                guard !Task.isCancelled, self.generation == gen else { return }
                self.currentTask = nil
                self.appendPage(page, previousCursor: nil)
                self.isLoading = false
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, self.generation == gen else { return }
                self.currentTask = nil
                self.error = Self.classifyError(error)
                self.isLoading = false
            }
        }
    }

    func loadMore(
        descriptor: CloudSearchExtensionDescriptor,
        extensionStore: ExtensionStore
    ) {
        guard !isLoading,
              !isLoadingMore,
              activeExtensionID == descriptor.id,
              let cursor = nextCursor,
              !cursor.isEmpty,
              !activeQuery.isEmpty else {
            return
        }

        generation += 1
        let gen = generation
        let prevCursor = cursor
        seenCursors.insert(prevCursor)
        isLoadingMore = true
        loadMoreError = nil

        let requestKind = effectiveKind(for: descriptor)
        let extID = descriptor.id
        let query = activeQuery
        let handler = self.searchHandler

        currentTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let page: CloudSearchPage
                if let handler {
                    page = try await handler(extID, query, requestKind, 20, prevCursor)
                } else {
                    page = try await extensionStore.search(
                        extensionID: extID,
                        query: query,
                        kind: requestKind,
                        limit: 20,
                        cursor: prevCursor
                    )
                }
                guard !Task.isCancelled, self.generation == gen else { return }
                self.currentTask = nil
                self.appendPage(page, previousCursor: prevCursor)
                self.isLoadingMore = false
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, self.generation == gen else { return }
                self.currentTask = nil
                self.loadMoreError = Self.classifyError(error)
                self.isLoadingMore = false
            }
        }
    }

    func retry(
        currentQuery: String,
        descriptor: CloudSearchExtensionDescriptor,
        extensionStore: ExtensionStore
    ) {
        if loadMoreError != nil && !results.isEmpty {
            loadMore(descriptor: descriptor, extensionStore: extensionStore)
        } else {
            let targetQuery = activeQuery.isEmpty ? currentQuery : activeQuery
            submitSearch(query: targetQuery, descriptor: descriptor, extensionStore: extensionStore)
        }
    }

    func reconcileActiveExtensions(
        _ activeExtensions: [CloudSearchExtensionDescriptor],
        navigation: AppNavigation
    ) {
        if case .cloud(let extID) = navigation.searchSource {
            if !activeExtensions.contains(where: { $0.id == extID }) {
                navigation.searchSource = .local
                cancelAndClear()
            }
        } else if activeExtensionID != nil || !results.isEmpty || isLoading || isLoadingMore || error != nil {
            cancelAndClear()
        }
    }

    func cancelAndClear() {
        cancelCurrentTask()
        generation += 1
        results = []
        nextCursor = nil
        isLoading = false
        isLoadingMore = false
        error = nil
        loadMoreError = nil
        selectedKind = .all
        activeQuery = ""
        activeExtensionID = nil
        hasSubmitted = false
        seenKeys = []
        seenCursors = []
    }

    /// Wait for the currently running search task (used in deterministic unit tests).
    func waitForCurrentTask() async {
        await currentTask?.value
    }

    private func cancelCurrentTask() {
        currentTask?.cancel()
        currentTask = nil
        isLoading = false
        isLoadingMore = false
    }

    private func appendPage(_ page: CloudSearchPage, previousCursor: String?) {
        var added: [CloudSearchResult] = []
        added.reserveCapacity(page.items.count)
        for item in page.items {
            if seenKeys.insert(item.dedupKey).inserted {
                added.append(item)
            }
        }
        if !added.isEmpty {
            results.append(contentsOf: added)
        }

        let candidateCursor = page.nextCursor?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let candidateCursor,
           !candidateCursor.isEmpty,
           candidateCursor != previousCursor,
           seenCursors.insert(candidateCursor).inserted {
            nextCursor = candidateCursor
        } else {
            nextCursor = nil
        }
    }

    static func classifyError(_ error: Error) -> CloudSearchErrorInfo {
        if let cloudErr = error as? CloudSourceError {
            switch cloudErr {
            case .rateLimited:
                return CloudSearchErrorInfo(kind: .rateLimited, code: "RATE_LIMITED", message: cloudErr.localizedDescription)
            case .permissionDenied:
                return CloudSearchErrorInfo(kind: .permissionDenied, code: "PERMISSION_DENIED", message: cloudErr.localizedDescription)
            case .extensionUnavailable:
                return CloudSearchErrorInfo(kind: .extensionUnavailable, code: "EXTENSION_UNAVAILABLE", message: cloudErr.localizedDescription)
            case .networkError(let msg):
                return CloudSearchErrorInfo(
                    kind: .network,
                    code: "NETWORK_ERROR",
                    message: msg.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? cloudErr.localizedDescription : msg
                )
            case .extensionRuntimeError(let code, let msg):
                let normalized = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
                let kind: CloudSearchErrorInfo.Kind
                switch normalized {
                case "AUTH_REQUIRED": kind = .authRequired
                case "RATE_LIMITED": kind = .rateLimited
                case "PERMISSION_DENIED": kind = .permissionDenied
                case "EXTENSION_UNAVAILABLE": kind = .extensionUnavailable
                case "NETWORK_ERROR": kind = .network
                default: kind = .runtime
                }
                let text = msg.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? cloudErr.localizedDescription : msg
                return CloudSearchErrorInfo(kind: kind, code: normalized.isEmpty ? nil : normalized, message: text)
            default:
                return CloudSearchErrorInfo(kind: .runtime, code: cloudErr.extensionErrorCode, message: cloudErr.localizedDescription)
            }
        }
        return CloudSearchErrorInfo(kind: .network, code: nil, message: error.localizedDescription)
    }
}
