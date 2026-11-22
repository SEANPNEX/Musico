import AVFoundation
import Foundation
import UniformTypeIdentifiers

/// `AVAssetResourceLoaderDelegate` that streams remote audio over bounded 2 MB HTTP Range
/// requests with a 16 MB in-memory LRU chunk cache so large cloud tracks start immediately
/// and never trigger CDN throttling or unbounded full-file buffering.
final class ChunkedCloudResourceLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    static let chunkSize: Int64 = 2 * 1024 * 1024
    static let maxCacheBytes = 16 * 1024 * 1024
    private static let streamBatchBytes = 65_536

    private struct CachedChunk {
        let data: Data
        var accessOrder: UInt64
    }

    private let headers: [String: String]
    private let fileExtension: String
    private let session: URLSession

    private let lock = NSLock()
    private var knownTotalLength: Int64?
    private var chunkCache: [Int64: CachedChunk] = [:]
    private var cachedBytesCount = 0
    private var accessCounter: UInt64 = 0
    private var activeTasks: [ObjectIdentifier: Task<Void, Never>] = [:]
    private var isCancelled = false

    init(headers: [String: String], contentLength: Int64?, fileExtension: String) {
        self.headers = headers
        self.knownTotalLength = (contentLength ?? 0) > 0 ? contentLength : nil
        self.fileExtension = fileExtension.trimmingCharacters(in: CharacterSet(charactersIn: ". ")).lowercased()
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 300
        config.httpMaximumConnectionsPerHost = 4
        self.session = URLSession(configuration: config)
        super.init()
    }

    static func resourceURL(for url: URL) -> URL {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        if components.scheme?.lowercased() == "http" {
            components.scheme = "musico-stream-http"
        } else {
            components.scheme = "musico-stream"
        }
        return components.url ?? url
    }

    static func originalURL(for resourceURL: URL) -> URL? {
        guard var components = URLComponents(url: resourceURL, resolvingAgainstBaseURL: false) else { return nil }
        switch components.scheme?.lowercased() {
        case "musico-stream":
            components.scheme = "https"
        case "musico-stream-http":
            components.scheme = "http"
        default:
            return nil
        }
        return components.url
    }

    func cancelAll() {
        let tasksToCancel: [Task<Void, Never>] = lock.withLock {
            isCancelled = true
            let all = Array(activeTasks.values)
            activeTasks.removeAll()
            chunkCache.removeAll()
            cachedBytesCount = 0
            return all
        }
        for task in tasksToCancel {
            task.cancel()
        }
        session.invalidateAndCancel()
    }

    // MARK: - AVAssetResourceLoaderDelegate

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        guard let requestURL = loadingRequest.request.url,
              let sourceURL = Self.originalURL(for: requestURL) else {
            return false
        }

        let requestID = ObjectIdentifier(loadingRequest)
        lock.lock()
        if isCancelled {
            lock.unlock()
            return false
        }
        let task = Task { [weak self] in
            guard let self else { return }
            defer { self.removeTask(id: requestID) }
            await self.handle(loadingRequest: loadingRequest, sourceURL: sourceURL)
        }
        activeTasks[requestID] = task
        lock.unlock()
        return true
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        let requestID = ObjectIdentifier(loadingRequest)
        let task = lock.withLock { activeTasks.removeValue(forKey: requestID) }
        task?.cancel()
    }

    // MARK: - Request Handling

    private func handle(loadingRequest: AVAssetResourceLoadingRequest, sourceURL: URL) async {
        do {
            try Task.checkCancellation()

            if let infoRequest = loadingRequest.contentInformationRequest,
               loadingRequest.dataRequest == nil {
                if getKnownTotal() == nil {
                    _ = try await fetchAndCacheChunk(
                        chunkIndex: 0,
                        sourceURL: sourceURL,
                        infoRequest: infoRequest,
                        onBytes: nil
                    )
                } else {
                    populateContentInfo(infoRequest, mimeType: nil, totalLength: getKnownTotal())
                }
                try Task.checkCancellation()
                loadingRequest.finishLoading()
                return
            }

            guard let dataRequest = loadingRequest.dataRequest else {
                if let infoRequest = loadingRequest.contentInformationRequest {
                    populateContentInfo(infoRequest, mimeType: nil, totalLength: getKnownTotal())
                }
                loadingRequest.finishLoading()
                return
            }

            var cursor = dataRequest.currentOffset
            let requestsAllToEnd = dataRequest.requestsAllDataToEndOfResource
            let targetEnd = requestsAllToEnd ? Int64.max : (dataRequest.requestedOffset + Int64(dataRequest.requestedLength))

            if !requestsAllToEnd && cursor >= targetEnd {
                if let infoRequest = loadingRequest.contentInformationRequest {
                    populateContentInfo(infoRequest, mimeType: nil, totalLength: getKnownTotal())
                }
                loadingRequest.finishLoading()
                return
            }

            var populatedInfo = false
            while !Task.isCancelled {
                if !requestsAllToEnd && cursor >= targetEnd {
                    break
                }
                if requestsAllToEnd, let knownTotal = getKnownTotal(), cursor >= knownTotal {
                    break
                }

                let chunkIndex = cursor / Self.chunkSize
                let chunkStart = chunkIndex * Self.chunkSize

                if let cached = cachedChunk(for: chunkIndex) {
                    if !populatedInfo, let infoRequest = loadingRequest.contentInformationRequest {
                        populateContentInfo(infoRequest, mimeType: nil, totalLength: getKnownTotal() ?? Int64(cached.count))
                        populatedInfo = true
                    }
                    let offsetInChunk = Int(cursor - chunkStart)
                    if offsetInChunk >= cached.count {
                        break
                    }
                    let maxEndInChunk: Int
                    if requestsAllToEnd {
                        maxEndInChunk = cached.count
                    } else {
                        maxEndInChunk = min(cached.count, Int(min(targetEnd - chunkStart, Int64(cached.count))))
                    }
                    if maxEndInChunk > offsetInChunk {
                        let slice = cached.subdata(in: offsetInChunk..<maxEndInChunk)
                        dataRequest.respond(with: slice)
                        cursor += Int64(slice.count)
                    }
                    if Int64(cached.count) < Self.chunkSize && maxEndInChunk >= cached.count {
                        break
                    }
                    continue
                }

                let infoToPopulate = populatedInfo ? nil : loadingRequest.contentInformationRequest
                let chunkData = try await fetchAndCacheChunk(
                    chunkIndex: chunkIndex,
                    sourceURL: sourceURL,
                    infoRequest: infoToPopulate,
                    onBytes: { chunkBytes, startOffsetInChunk in
                        if infoToPopulate != nil {
                            populatedInfo = true
                        }
                        let currentChunkOffset = Int(cursor - chunkStart)
                        let availableEnd = startOffsetInChunk + chunkBytes.count
                        guard availableEnd > currentChunkOffset else { return }

                        let sliceEnd: Int
                        if requestsAllToEnd {
                            sliceEnd = availableEnd
                        } else {
                            let neededEnd = Int(min(targetEnd - chunkStart, Int64(availableEnd)))
                            sliceEnd = neededEnd
                        }
                        if sliceEnd > currentChunkOffset {
                            let localStart = max(0, currentChunkOffset - startOffsetInChunk)
                            let localEnd = sliceEnd - startOffsetInChunk
                            if localEnd > localStart {
                                let slice = Data(chunkBytes[localStart..<localEnd])
                                dataRequest.respond(with: slice)
                                cursor += Int64(slice.count)
                            }
                        }
                    }
                )
                if infoToPopulate != nil {
                    populatedInfo = true
                }
                if Int64(chunkData.count) < Self.chunkSize {
                    break
                }
            }

            if Task.isCancelled { return }
            loadingRequest.finishLoading()
        } catch is CancellationError {
            return
        } catch {
            if Task.isCancelled || (error as? URLError)?.code == .cancelled {
                return
            }
            loadingRequest.finishLoading(with: error)
        }
    }

    private func fetchAndCacheChunk(
        chunkIndex: Int64,
        sourceURL: URL,
        infoRequest: AVAssetResourceLoadingContentInformationRequest?,
        onBytes: (([UInt8], Int) -> Void)?
    ) async throws -> Data {
        try Task.checkCancellation()
        let chunkStart = chunkIndex * Self.chunkSize
        let knownTotal = getKnownTotal()
        let chunkEnd: Int64
        if let knownTotal {
            guard chunkStart < knownTotal else { return Data() }
            chunkEnd = min(chunkStart + Self.chunkSize - 1, knownTotal - 1)
        } else {
            chunkEnd = chunkStart + Self.chunkSize - 1
        }

        var req = URLRequest(url: sourceURL)
        for (key, value) in headers {
            req.setValue(value, forHTTPHeaderField: key)
        }
        req.setValue("bytes=\(chunkStart)-\(chunkEnd)", forHTTPHeaderField: "Range")

        let (asyncBytes, response): (URLSession.AsyncBytes, URLResponse)
        do {
            (asyncBytes, response) = try await session.bytes(for: req)
        } catch {
            if Task.isCancelled || (error as? URLError)?.code == .cancelled {
                throw CancellationError()
            }
            throw error
        }

        guard let http = response as? HTTPURLResponse else {
            throw CloudSourceError.networkError(L10n.trNonisolated("Invalid server response."))
        }
        let contentTypeHeader = http.value(forHTTPHeaderField: "Content-Type")
        if let ct = contentTypeHeader?.lowercased(), ct.contains("text/html") {
            throw CloudSourceError.noPlayableStream
        }
        guard http.statusCode == 206 || (http.statusCode == 200 && chunkStart == 0) else {
            if http.statusCode == 200 && chunkStart > 0 {
                throw CloudSourceError.networkError("Server ignored Range header.")
            }
            throw CloudSourceError.networkError(L10n.trNonisolated("Stream returned HTTP %@.", String(http.statusCode)))
        }

        if let rangeTotal = Self.parseContentRangeTotal(http.value(forHTTPHeaderField: "Content-Range")) {
            setKnownTotal(rangeTotal)
        } else if http.statusCode == 200, http.expectedContentLength > 0 {
            setKnownTotal(http.expectedContentLength)
        }

        if let infoRequest {
            populateContentInfo(infoRequest, mimeType: contentTypeHeader, totalLength: getKnownTotal())
        }

        var fullChunk = Data()
        var batch: [UInt8] = []
        batch.reserveCapacity(Self.streamBatchBytes)
        var chunkOffset = 0

        do {
            for try await byte in asyncBytes {
                if Task.isCancelled { throw CancellationError() }
                batch.append(byte)
                if batch.count >= Self.streamBatchBytes {
                    onBytes?(batch, chunkOffset)
                    fullChunk.append(contentsOf: batch)
                    chunkOffset += batch.count
                    batch.removeAll(keepingCapacity: true)
                }
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            if Task.isCancelled || (error as? URLError)?.code == .cancelled {
                throw CancellationError()
            }
            throw error
        }

        if !batch.isEmpty {
            try Task.checkCancellation()
            onBytes?(batch, chunkOffset)
            fullChunk.append(contentsOf: batch)
        }

        if http.statusCode == 200, chunkStart == 0, getKnownTotal() == nil, !fullChunk.isEmpty {
            setKnownTotal(Int64(fullChunk.count))
        }

        if !fullChunk.isEmpty && !Task.isCancelled {
            storeChunk(fullChunk, for: chunkIndex)
        }
        return fullChunk
    }

    private func populateContentInfo(
        _ infoRequest: AVAssetResourceLoadingContentInformationRequest,
        mimeType: String?,
        totalLength: Int64?
    ) {
        infoRequest.contentType = resolvedUTI(mimeType: mimeType)
        infoRequest.isByteRangeAccessSupported = true
        if let totalLength, totalLength > 0 {
            infoRequest.contentLength = totalLength
        }
    }

    private func resolvedUTI(mimeType: String?) -> String {
        if !fileExtension.isEmpty, let byExt = UTType(filenameExtension: fileExtension), byExt.conforms(to: .audiovisualContent) {
            return byExt.identifier
        }
        if let mime = mimeType?.split(separator: ";").first.map({ String($0).trimmingCharacters(in: .whitespacesAndNewlines) }),
           !mime.isEmpty,
           let byMime = UTType(mimeType: mime) {
            return byMime.identifier
        }
        return UTType.mpeg4Audio.identifier
    }

    // MARK: - Helpers & LRU Cache

    private func removeTask(id: ObjectIdentifier) {
        lock.withLock {
            _ = activeTasks.removeValue(forKey: id)
        }
    }

    private func getKnownTotal() -> Int64? {
        lock.withLock { knownTotalLength }
    }

    private func setKnownTotal(_ total: Int64) {
        guard total > 0 else { return }
        lock.withLock {
            if knownTotalLength == nil {
                knownTotalLength = total
            }
        }
    }

    private func cachedChunk(for index: Int64) -> Data? {
        lock.withLock {
            guard var entry = chunkCache[index] else { return nil }
            accessCounter += 1
            entry.accessOrder = accessCounter
            chunkCache[index] = entry
            return entry.data
        }
    }

    private func storeChunk(_ data: Data, for index: Int64) {
        guard data.count <= Self.maxCacheBytes else { return }
        lock.withLock {
            if let existing = chunkCache[index] {
                cachedBytesCount -= existing.data.count
            }
            accessCounter += 1
            chunkCache[index] = CachedChunk(data: data, accessOrder: accessCounter)
            cachedBytesCount += data.count

            while cachedBytesCount > Self.maxCacheBytes,
                  let oldest = chunkCache.min(by: { $0.value.accessOrder < $1.value.accessOrder })?.key {
                if let removed = chunkCache.removeValue(forKey: oldest) {
                    cachedBytesCount -= removed.data.count
                }
            }
        }
    }

    private static func parseContentRangeTotal(_ header: String?) -> Int64? {
        guard let header, let slash = header.lastIndex(of: "/") else { return nil }
        let totalPart = header[header.index(after: slash)...].trimmingCharacters(in: .whitespacesAndNewlines)
        guard totalPart != "*", let total = Int64(totalPart), total > 0 else { return nil }
        return total
    }
}
