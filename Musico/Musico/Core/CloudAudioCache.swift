import Foundation
import Darwin

struct CloudDownloadProgress: Sendable, Equatable {
    var bytesDownloaded: Int64
    var totalBytes: Int64?
    var bytesPerSecond: Double

    var fractionCompleted: Double? {
        guard let totalBytes, totalBytes > 0 else { return nil }
        return min(max(Double(bytesDownloaded) / Double(totalBytes), 0), 1)
    }
}

private final class DownloadProgressMeter: @unchecked Sendable {
    private let lock = NSLock()
    private var totalBytes: Int64?
    private var bytesDownloaded: Int64 = 0
    private var samples: [(Date, Int64)]
    private var lastEmitTime: Date = .distantPast
    private let onProgress: (@Sendable (CloudDownloadProgress) -> Void)?

    init(totalBytes: Int64?, onProgress: (@Sendable (CloudDownloadProgress) -> Void)?) {
        self.totalBytes = totalBytes
        self.onProgress = onProgress
        self.samples = [(Date(), 0)]
    }

    func setTotalBytes(_ total: Int64?) {
        guard let total, total > 0 else { return }
        lock.lock()
        totalBytes = total
        lock.unlock()
    }

    func add(bytes: Int64) {
        guard bytes != 0 else { return }
        let now = Date()
        var snapshot: CloudDownloadProgress?
        lock.lock()
        bytesDownloaded = max(bytesDownloaded + bytes, 0)
        samples.append((now, bytesDownloaded))
        let cutoff = now.addingTimeInterval(-1.5)
        while samples.count >= 2 && samples[1].0 <= cutoff {
            samples.removeFirst()
        }
        if now.timeIntervalSince(lastEmitTime) >= 0.12 {
            lastEmitTime = now
            let speed = computeSpeedLocked(now: now)
            snapshot = CloudDownloadProgress(
                bytesDownloaded: bytesDownloaded,
                totalBytes: totalBytes,
                bytesPerSecond: speed
            )
        }
        lock.unlock()
        if let snapshot {
            onProgress?(snapshot)
        }
    }

    func finish() {
        let now = Date()
        lock.lock()
        let speed = computeSpeedLocked(now: now)
        let snapshot = CloudDownloadProgress(
            bytesDownloaded: bytesDownloaded,
            totalBytes: totalBytes,
            bytesPerSecond: speed
        )
        lastEmitTime = now
        lock.unlock()
        onProgress?(snapshot)
    }

    private func computeSpeedLocked(now: Date) -> Double {
        guard let first = samples.first else { return 0 }
        let dt = max(now.timeIntervalSince(first.0), 0.05)
        let db = max(bytesDownloaded - first.1, 0)
        return Double(db) / dt
    }
}

private final class ProgressBroadcaster: @unchecked Sendable {
    private let lock = NSLock()
    private var handlers: [UUID: @Sendable (CloudDownloadProgress) -> Void] = [:]
    private var latest: CloudDownloadProgress?

    func register(id: UUID, handler: (@Sendable (CloudDownloadProgress) -> Void)?) {
        guard let handler else { return }
        let snapshot: CloudDownloadProgress? = lock.withLock {
            handlers[id] = handler
            return latest
        }
        if let snapshot {
            handler(snapshot)
        }
    }

    func remove(id: UUID) {
        lock.withLock {
            _ = handlers.removeValue(forKey: id)
        }
    }

    func removeAll() {
        lock.withLock {
            handlers.removeAll()
        }
    }

    func broadcast(_ progress: CloudDownloadProgress) {
        let targets: [@Sendable (CloudDownloadProgress) -> Void] = lock.withLock {
            latest = progress
            return Array(handlers.values)
        }
        for handler in targets {
            handler(progress)
        }
    }
}

/// LRU disk cache for resolved cloud audio streams, capped at 500 MB in `Caches/CloudAudio`.
actor CloudAudioCache {
    static let defaultMaxBytes: Int64 = 500 * 1024 * 1024
    static let streamingThresholdBytes: Int64 = 6 * 1024 * 1024
    static let chunkSizeBytes: Int64 = 4 * 1024 * 1024

    static let shared: CloudAudioCache = {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return CloudAudioCache(directory: caches.appendingPathComponent("CloudAudio"))
    }()

    private static let supportedExtensions = ["m4a", "mp4", "flac", "mp3", "aac", "wav", "ogg", "webm", "aif", "aiff", "alac", "caf"]
    private static let streamBufferBytes = 65_536
    private static let maxConcurrentChunks = 4

    private final class InFlightEntry {
        let id = UUID()
        var task: Task<URL, Error>?
        var waiters: [UUID: CheckedContinuation<URL, Error>] = [:]
        let broadcaster = ProgressBroadcaster()
    }

    let directory: URL
    let maxBytes: Int64
    let usesCustomDownloader: Bool
    private let resolver: any CloudStreamResolving
    private let downloader: (@Sendable (URL, [String: String]) async throws -> (tempFile: URL, statusCode: Int, contentType: String?))?
    private var inFlight: [String: InFlightEntry] = [:]

    init(
        directory: URL,
        maxBytes: Int64 = CloudAudioCache.defaultMaxBytes,
        resolver: any CloudStreamResolving = LiveCloudService.shared,
        downloader: (@Sendable (URL, [String: String]) async throws -> (tempFile: URL, statusCode: Int, contentType: String?))? = nil
    ) {
        self.directory = directory
        self.maxBytes = maxBytes
        self.resolver = resolver
        self.downloader = downloader
        self.usesCustomDownloader = downloader != nil
    }

    // MARK: Public API

    nonisolated static func sanitizeQuality(_ qualityID: String?) -> String? {
        guard let raw = qualityID?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
            return nil
        }
        let cleaned = raw
            .replacingOccurrences(of: #"[^A-Za-z0-9_-]+"#, with: "_", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        return cleaned.isEmpty ? nil : cleaned
    }

    private nonisolated static func inFlightKey(trackID: String, qualityID: String?) -> String {
        "\(trackID)|\(sanitizeQuality(qualityID) ?? "")"
    }

    func effectiveQualityID(
        for track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose = .stream,
        overrideQualityID: String? = nil
    ) -> String? {
        resolver.effectiveQualityID(for: track, root: root, purpose: purpose, overrideQualityID: overrideQualityID)
    }

    func cachedURLIfPresent(for trackID: String, qualityID: String? = nil) -> URL? {
        for ext in Self.supportedExtensions {
            let candidate = fileURL(trackID: trackID, qualityID: qualityID, ext: ext)
            if FileManager.default.fileExists(atPath: candidate.path) {
                touch(candidate)
                return candidate
            }
        }
        return nil
    }

    nonisolated func hasCachedFileSync(for trackID: String) -> Bool {
        if Self.supportedExtensions.contains(where: { ext in
            FileManager.default.fileExists(atPath: fileURL(trackID: trackID, qualityID: nil, ext: ext).path)
        }) {
            return true
        }
        let prefix = Track.safeCacheStem(for: trackID) + "__"
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) else {
            return false
        }
        return urls.contains { url in
            let name = url.lastPathComponent
            let ext = url.pathExtension.lowercased()
            return name.hasPrefix(prefix) && Self.supportedExtensions.contains(ext)
        }
    }

    func resolveStream(
        track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose = .stream,
        qualityID: String? = nil,
        forceRefresh: Bool = false
    ) async throws -> CloudStreamResult {
        try Task.checkCancellation()
        let effectiveQuality = effectiveQualityID(for: track, root: root, purpose: purpose, overrideQualityID: qualityID)
        return try await resolver.resolveStream(
            track: track,
            root: root,
            purpose: purpose,
            qualityID: effectiveQuality,
            forceRefresh: forceRefresh
        )
    }

    func resolveAndCache(
        track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose = .stream,
        qualityID: String? = nil,
        forceRefresh: Bool = false,
        onProgress: (@Sendable (CloudDownloadProgress) -> Void)? = nil
    ) async throws -> URL {
        try Task.checkCancellation()
        let effectiveQuality = effectiveQualityID(for: track, root: root, purpose: purpose, overrideQualityID: qualityID)
        if forceRefresh {
            remove(trackID: track.id)
        } else if let cached = cachedURLIfPresent(for: track.id, qualityID: effectiveQuality) {
            return cached
        }

        let cacheKey = Self.inFlightKey(trackID: track.id, qualityID: effectiveQuality)
        let entry: InFlightEntry
        if let existing = inFlight[cacheKey] {
            entry = existing
        } else {
            let created = InFlightEntry()
            inFlight[cacheKey] = created
            let entryID = created.id
            let broadcaster = created.broadcaster
            created.task = Task<URL, Error> { [self] in
                do {
                    let url = try await self.performFetchAndStore(
                        track: track,
                        root: root,
                        purpose: purpose,
                        qualityID: effectiveQuality,
                        forceRefresh: forceRefresh,
                        onProgress: { progress in
                            broadcaster.broadcast(progress)
                        }
                    )
                    await self.finishInFlight(cacheKey: cacheKey, entryID: entryID, result: .success(url))
                    return url
                } catch {
                    await self.finishInFlight(cacheKey: cacheKey, entryID: entryID, result: .failure(error))
                    throw error
                }
            }
            entry = created
        }

        let entryID = entry.id
        let waiterID = UUID()
        let entryBroadcaster = entry.broadcaster

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                if Task.isCancelled {
                    if entry.waiters.isEmpty {
                        entry.task?.cancel()
                        entryBroadcaster.removeAll()
                        if inFlight[cacheKey]?.id == entryID {
                            inFlight[cacheKey] = nil
                        }
                    }
                    continuation.resume(throwing: CancellationError())
                    return
                }
                entry.waiters[waiterID] = continuation
                entryBroadcaster.register(id: waiterID, handler: onProgress)
            }
        } onCancel: {
            entryBroadcaster.remove(id: waiterID)
            Task {
                await self.cancelWaiter(cacheKey: cacheKey, entryID: entryID, waiterID: waiterID)
            }
        }
    }

    func prefetch(track: Track, root: LibraryRoot?, purpose: CloudStreamPurpose = .stream) async {
        let effectiveQuality = effectiveQualityID(for: track, root: root, purpose: purpose, overrideQualityID: nil)
        let cacheKey = Self.inFlightKey(trackID: track.id, qualityID: effectiveQuality)
        guard track.isCloud, cachedURLIfPresent(for: track.id, qualityID: effectiveQuality) == nil, inFlight[cacheKey] == nil else { return }
        _ = try? await resolveAndCache(track: track, root: root, purpose: purpose, qualityID: effectiveQuality, forceRefresh: false)
    }

    func remove(trackID: String) {
        for ext in Self.supportedExtensions {
            let candidate = fileURL(trackID: trackID, qualityID: nil, ext: ext)
            try? FileManager.default.removeItem(at: candidate)
        }
        let prefix = Track.safeCacheStem(for: trackID) + "__"
        if let urls = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        ) {
            for url in urls where url.lastPathComponent.hasPrefix(prefix) {
                try? FileManager.default.removeItem(at: url)
            }
        }
    }

    /// Writes `data` atomically into the cache and enforces the LRU size limit. Useful for tests too.
    @discardableResult
    func storeData(_ data: Data, for trackID: String, qualityID: String? = nil, fileExtension: String) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        remove(trackID: trackID)
        let ext = sanitizeExtension(fileExtension)
        let destination = fileURL(trackID: trackID, qualityID: qualityID, ext: ext)
        let temp = directory.appendingPathComponent(".tmp-\(UUID().uuidString).\(ext)")
        try data.write(to: temp, options: .atomic)
        try FileManager.default.moveItem(at: temp, to: destination)
        touch(destination)
        evictIfNeeded(protecting: destination)
        return destination
    }

    func totalCachedBytes() -> Int64 {
        cachedEntries().reduce(0) { $0 + $1.size }
    }

    // MARK: Waiter Management

    private func cancelWaiter(cacheKey: String, entryID: UUID, waiterID: UUID) {
        guard let entry = inFlight[cacheKey], entry.id == entryID else { return }
        entry.broadcaster.remove(id: waiterID)
        guard let continuation = entry.waiters.removeValue(forKey: waiterID) else {
            if entry.waiters.isEmpty {
                entry.task?.cancel()
                entry.broadcaster.removeAll()
                inFlight[cacheKey] = nil
            }
            return
        }
        continuation.resume(throwing: CancellationError())
        if entry.waiters.isEmpty {
            entry.task?.cancel()
            entry.broadcaster.removeAll()
            inFlight[cacheKey] = nil
        }
    }

    private func finishInFlight(cacheKey: String, entryID: UUID, result: Result<URL, Error>) {
        guard let entry = inFlight[cacheKey], entry.id == entryID else { return }
        inFlight[cacheKey] = nil
        entry.broadcaster.removeAll()
        let continuations = Array(entry.waiters.values)
        entry.waiters.removeAll()
        for continuation in continuations {
            continuation.resume(with: result)
        }
    }

    // MARK: Internal

    private func performFetchAndStore(
        track: Track,
        root: LibraryRoot?,
        purpose: CloudStreamPurpose,
        qualityID: String?,
        forceRefresh: Bool,
        onProgress: (@Sendable (CloudDownloadProgress) -> Void)?
    ) async throws -> URL {
        try Task.checkCancellation()
        var stream = try await resolver.resolveStream(
            track: track,
            root: root,
            purpose: purpose,
            qualityID: qualityID,
            forceRefresh: forceRefresh
        )
        var attempts = [stream.url] + stream.fallbackURLs
        var lastError: Error = CloudSourceError.noPlayableStream

        for attemptIndex in 0..<2 {
            try Task.checkCancellation()
            for candidateURL in attempts {
                try Task.checkCancellation()
                do {
                    let result: (tempFile: URL, statusCode: Int, contentType: String?)
                    if let customDownloader = downloader {
                        result = try await customDownloader(candidateURL, stream.headers)
                    } else {
                        result = try await Self.downloadSmart(
                            url: candidateURL,
                            headers: stream.headers,
                            contentLength: stream.contentLength,
                            onProgress: onProgress
                        )
                    }
                    defer { try? FileManager.default.removeItem(at: result.tempFile) }
                    try Task.checkCancellation()

                    if let ct = result.contentType?.lowercased(), ct.contains("text/html") {
                        throw CloudSourceError.noPlayableStream
                    }
                    guard (200..<300).contains(result.statusCode) else {
                        throw CloudSourceError.networkError(L10n.trNonisolated("Stream returned HTTP %@.", String(result.statusCode)))
                    }
                    let attrs = try FileManager.default.attributesOfItem(atPath: result.tempFile.path)
                    let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
                    guard size > 0 else {
                        throw CloudSourceError.noPlayableStream
                    }

                    return try moveTempFileIntoCache(
                        tempFile: result.tempFile,
                        trackID: track.id,
                        qualityID: qualityID,
                        fileExtension: stream.fileExtension
                    )
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    if Task.isCancelled { throw CancellationError() }
                    lastError = error
                }
            }
            // If first pass failed (e.g. expired cached stream URL returning 403), force-refresh stream once.
            if attemptIndex == 0 && !forceRefresh && !Task.isCancelled {
                if let refreshed = try? await resolver.resolveStream(
                    track: track,
                    root: root,
                    purpose: purpose,
                    qualityID: qualityID,
                    forceRefresh: true
                ) {
                    stream = refreshed
                    attempts = [stream.url] + stream.fallbackURLs
                    continue
                }
            }
            break
        }
        throw lastError
    }

    private func moveTempFileIntoCache(tempFile: URL, trackID: String, qualityID: String?, fileExtension: String) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        remove(trackID: trackID)
        let ext = sanitizeExtension(fileExtension)
        let destination = fileURL(trackID: trackID, qualityID: qualityID, ext: ext)
        let staged = directory.appendingPathComponent(".tmp-\(UUID().uuidString).\(ext)")
        if (try? FileManager.default.moveItem(at: tempFile, to: staged)) == nil {
            try FileManager.default.copyItem(at: tempFile, to: staged)
        }
        do {
            try FileManager.default.moveItem(at: staged, to: destination)
        } catch {
            try? FileManager.default.removeItem(at: staged)
            throw error
        }
        touch(destination)
        evictIfNeeded(protecting: destination)
        return destination
    }

    private nonisolated func fileURL(trackID: String, qualityID: String? = nil, ext: String) -> URL {
        let stem = Track.safeCacheStem(for: trackID)
        if let cleanQ = Self.sanitizeQuality(qualityID) {
            return directory.appendingPathComponent("\(stem)__\(cleanQ).\(ext)")
        }
        return directory.appendingPathComponent("\(stem).\(ext)")
    }

    private func sanitizeExtension(_ ext: String) -> String {
        let lower = ext.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return Self.supportedExtensions.contains(lower) ? lower : "m4a"
    }

    private func touch(_ url: URL) {
        try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: url.path)
    }

    private struct CacheEntry {
        let url: URL
        let size: Int64
        let modified: Date
    }

    private func cachedEntries() -> [CacheEntry] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey]
        guard let urls = try? FileManager.default.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles]
        ) else { return [] }

        return urls.compactMap { url -> CacheEntry? in
            guard let values = try? url.resourceValues(forKeys: Set(keys)),
                  values.isRegularFile == true else { return nil }
            let size = Int64(values.fileSize ?? 0)
            let modified = values.contentModificationDate ?? .distantPast
            return CacheEntry(url: url, size: size, modified: modified)
        }
    }

    func evictIfNeeded(protecting protectedURL: URL? = nil) {
        var entries = cachedEntries()
        var total = entries.reduce(Int64(0)) { $0 + $1.size }
        guard total > maxBytes else { return }

        // Oldest modification date first (LRU).
        entries.sort { $0.modified < $1.modified }
        let protectedPath = protectedURL?.standardizedFileURL.path
        for entry in entries {
            guard total > maxBytes else { break }
            if let protectedPath, entry.url.standardizedFileURL.path == protectedPath { continue }
            if (try? FileManager.default.removeItem(at: entry.url)) != nil {
                total -= entry.size
            }
        }
    }

    // MARK: Smart Parallel / Streaming Downloader

    private nonisolated static func downloadSmart(
        url: URL,
        headers: [String: String],
        contentLength: Int64?,
        onProgress: (@Sendable (CloudDownloadProgress) -> Void)?
    ) async throws -> (tempFile: URL, statusCode: Int, contentType: String?) {
        try Task.checkCancellation()
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 300
        config.httpMaximumConnectionsPerHost = maxConcurrentChunks + 2
        let session = URLSession(configuration: config)

        let meter = DownloadProgressMeter(totalBytes: contentLength, onProgress: onProgress)

        return try await withTaskCancellationHandler {
            defer { session.invalidateAndCancel() }
            try Task.checkCancellation()

            if let contentLength, contentLength > chunkSizeBytes {
                let result = try await downloadParallelChunks(
                    session: session,
                    url: url,
                    headers: headers,
                    totalLength: contentLength,
                    initialChunk: nil,
                    meter: meter
                )
                meter.finish()
                return result
            }

            if contentLength == nil,
               let host = url.host?.lowercased(),
               host.contains("googlevideo.com") {
                let firstEnd = chunkSizeBytes - 1
                let firstChunk = try await fetchRangeBytes(
                    session: session,
                    url: url,
                    headers: headers,
                    range: "bytes=0-\(firstEnd)",
                    meter: meter
                )
                if let total = parseContentRangeTotal(firstChunk.contentRange), total > chunkSizeBytes {
                    meter.setTotalBytes(total)
                    let result = try await downloadParallelChunks(
                        session: session,
                        url: url,
                        headers: headers,
                        totalLength: total,
                        initialChunk: (data: firstChunk.data, contentType: firstChunk.contentType),
                        meter: meter
                    )
                    meter.finish()
                    return result
                }
                let persistentTemp = FileManager.default.temporaryDirectory
                    .appendingPathComponent("musico-dl-\(UUID().uuidString)")
                do {
                    try firstChunk.data.write(to: persistentTemp, options: .atomic)
                } catch {
                    try? FileManager.default.removeItem(at: persistentTemp)
                    throw error
                }
                if let total = parseContentRangeTotal(firstChunk.contentRange) {
                    meter.setTotalBytes(total)
                } else {
                    meter.setTotalBytes(Int64(firstChunk.data.count))
                }
                meter.finish()
                return (persistentTemp, firstChunk.statusCode, firstChunk.contentType)
            }

            let result = try await downloadStreaming(
                session: session,
                url: url,
                headers: headers,
                knownTotal: contentLength,
                meter: meter
            )
            meter.finish()
            return result
        } onCancel: {
            session.invalidateAndCancel()
        }
    }

    private nonisolated static func downloadParallelChunks(
        session: URLSession,
        url: URL,
        headers: [String: String],
        totalLength: Int64,
        initialChunk: (data: Data, contentType: String?)?,
        meter: DownloadProgressMeter
    ) async throws -> (tempFile: URL, statusCode: Int, contentType: String?) {
        let persistentTemp = FileManager.default.temporaryDirectory
            .appendingPathComponent("musico-dl-\(UUID().uuidString)")
        let fd = Darwin.open(persistentTemp.path, O_RDWR | O_CREAT | O_TRUNC, 0o600)
        guard fd >= 0 else {
            throw CloudSourceError.networkError(String(cString: strerror(errno)))
        }

        var didClose = false
        func closeFD() {
            if !didClose {
                Darwin.close(fd)
                didClose = true
            }
        }
        defer { closeFD() }

        do {
            if Darwin.ftruncate(fd, off_t(totalLength)) != 0 {
                throw CloudSourceError.networkError(String(cString: strerror(errno)))
            }

            var startOffset: Int64 = 0
            var resolvedContentType: String? = initialChunk?.contentType
            if let initialChunk {
                let bytes = [UInt8](initialChunk.data)
                if !bytes.isEmpty {
                    try pwriteAll(fd: fd, bytes: bytes, offset: 0)
                }
                startOffset = Int64(bytes.count)
            }

            var ranges: [(start: Int64, end: Int64)] = []
            var cursor = startOffset
            while cursor < totalLength {
                let end = min(cursor + chunkSizeBytes - 1, totalLength - 1)
                ranges.append((cursor, end))
                cursor = end + 1
            }

            try await withThrowingTaskGroup(of: String?.self) { group in
                var nextIndex = 0
                let initialBatch = min(maxConcurrentChunks, ranges.count)
                for _ in 0..<initialBatch {
                    let range = ranges[nextIndex]
                    nextIndex += 1
                    group.addTask {
                        try await downloadChunkWithRetry(
                            session: session,
                            url: url,
                            headers: headers,
                            start: range.start,
                            end: range.end,
                            fd: fd,
                            meter: meter
                        )
                    }
                }

                while let ct = try await group.next() {
                    if resolvedContentType == nil, let ct {
                        resolvedContentType = ct
                    }
                    if nextIndex < ranges.count {
                        try Task.checkCancellation()
                        let range = ranges[nextIndex]
                        nextIndex += 1
                        group.addTask {
                            try await downloadChunkWithRetry(
                                session: session,
                                url: url,
                                headers: headers,
                                start: range.start,
                                end: range.end,
                                fd: fd,
                                meter: meter
                            )
                        }
                    }
                }
            }

            try Task.checkCancellation()
            closeFD()
            return (persistentTemp, 206, resolvedContentType)
        } catch {
            closeFD()
            try? FileManager.default.removeItem(at: persistentTemp)
            if Task.isCancelled {
                throw CancellationError()
            }
            throw error
        }
    }

    private nonisolated static func downloadChunkWithRetry(
        session: URLSession,
        url: URL,
        headers: [String: String],
        start: Int64,
        end: Int64,
        fd: Int32,
        meter: DownloadProgressMeter
    ) async throws -> String? {
        for attempt in 0..<2 {
            try Task.checkCancellation()
            var localWritten: Int64 = 0
            do {
                var req = URLRequest(url: url)
                for (key, value) in headers {
                    req.setValue(value, forHTTPHeaderField: key)
                }
                req.setValue("bytes=\(start)-\(end)", forHTTPHeaderField: "Range")

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
                    throw CloudSourceError.networkError(L10n.trNonisolated("Invalid download response."))
                }
                let contentType = http.value(forHTTPHeaderField: "Content-Type")
                if let ct = contentType?.lowercased(), ct.contains("text/html") {
                    throw CloudSourceError.noPlayableStream
                }
                guard http.statusCode == 206 || (http.statusCode == 200 && start == 0) else {
                    if http.statusCode == 200 && start > 0 {
                        throw CloudSourceError.networkError("Server ignored Range header.")
                    }
                    throw CloudSourceError.networkError(L10n.trNonisolated("Stream returned HTTP %@.", String(http.statusCode)))
                }

                let expectedCount = end - start + 1
                var buffer: [UInt8] = []
                buffer.reserveCapacity(streamBufferBytes)

                do {
                    for try await byte in asyncBytes {
                        if Task.isCancelled { throw CancellationError() }
                        if http.statusCode == 200 && localWritten + Int64(buffer.count) >= expectedCount {
                            break
                        }
                        buffer.append(byte)
                        if buffer.count >= streamBufferBytes {
                            try pwriteAll(fd: fd, bytes: buffer, offset: start + localWritten)
                            let delta = Int64(buffer.count)
                            localWritten += delta
                            meter.add(bytes: delta)
                            buffer.removeAll(keepingCapacity: true)
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

                if !buffer.isEmpty {
                    try Task.checkCancellation()
                    try pwriteAll(fd: fd, bytes: buffer, offset: start + localWritten)
                    let delta = Int64(buffer.count)
                    localWritten += delta
                    meter.add(bytes: delta)
                }
                return contentType
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if Task.isCancelled { throw CancellationError() }
                if localWritten > 0 {
                    meter.add(bytes: -localWritten)
                }
                if attempt == 0 {
                    continue
                }
                throw error
            }
        }
        throw CloudSourceError.noPlayableStream
    }

    private nonisolated static func fetchRangeBytes(
        session: URLSession,
        url: URL,
        headers: [String: String],
        range: String,
        meter: DownloadProgressMeter
    ) async throws -> (data: Data, statusCode: Int, contentType: String?, contentRange: String?) {
        var req = URLRequest(url: url)
        for (key, value) in headers {
            req.setValue(value, forHTTPHeaderField: key)
        }
        req.setValue(range, forHTTPHeaderField: "Range")

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
            throw CloudSourceError.networkError(L10n.trNonisolated("Invalid download response."))
        }
        let contentType = http.value(forHTTPHeaderField: "Content-Type")
        if let ct = contentType?.lowercased(), ct.contains("text/html") {
            throw CloudSourceError.noPlayableStream
        }
        guard (200..<300).contains(http.statusCode) else {
            throw CloudSourceError.networkError(L10n.trNonisolated("Stream returned HTTP %@.", String(http.statusCode)))
        }

        let contentRange = http.value(forHTTPHeaderField: "Content-Range")
        if let total = parseContentRangeTotal(contentRange) {
            meter.setTotalBytes(total)
        } else if http.expectedContentLength > 0 {
            meter.setTotalBytes(http.expectedContentLength)
        }

        var data = Data()
        var buffer: [UInt8] = []
        buffer.reserveCapacity(streamBufferBytes)
        do {
            for try await byte in asyncBytes {
                if Task.isCancelled { throw CancellationError() }
                buffer.append(byte)
                if buffer.count >= streamBufferBytes {
                    data.append(contentsOf: buffer)
                    meter.add(bytes: Int64(buffer.count))
                    buffer.removeAll(keepingCapacity: true)
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
        if !buffer.isEmpty {
            try Task.checkCancellation()
            data.append(contentsOf: buffer)
            meter.add(bytes: Int64(buffer.count))
        }
        return (data, http.statusCode, contentType, contentRange)
    }

    private nonisolated static func downloadStreaming(
        session: URLSession,
        url: URL,
        headers: [String: String],
        knownTotal: Int64?,
        meter: DownloadProgressMeter
    ) async throws -> (tempFile: URL, statusCode: Int, contentType: String?) {
        var req = URLRequest(url: url)
        for (key, value) in headers {
            req.setValue(value, forHTTPHeaderField: key)
        }

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
            throw CloudSourceError.networkError(L10n.trNonisolated("Invalid download response."))
        }
        let contentType = http.value(forHTTPHeaderField: "Content-Type")
        if let ct = contentType?.lowercased(), ct.contains("text/html") {
            throw CloudSourceError.noPlayableStream
        }
        guard (200..<300).contains(http.statusCode) else {
            throw CloudSourceError.networkError(L10n.trNonisolated("Stream returned HTTP %@.", String(http.statusCode)))
        }

        if let total = parseContentRangeTotal(http.value(forHTTPHeaderField: "Content-Range")) {
            meter.setTotalBytes(total)
        } else if knownTotal == nil, http.expectedContentLength > 0 {
            meter.setTotalBytes(http.expectedContentLength)
        }

        let persistentTemp = FileManager.default.temporaryDirectory
            .appendingPathComponent("musico-dl-\(UUID().uuidString)")
        guard FileManager.default.createFile(atPath: persistentTemp.path, contents: nil) else {
            throw CloudSourceError.networkError("Could not create temporary download file.")
        }

        let handle: FileHandle
        do {
            handle = try FileHandle(forWritingTo: persistentTemp)
        } catch {
            try? FileManager.default.removeItem(at: persistentTemp)
            throw error
        }

        var didClose = false
        func closeHandle() {
            if !didClose {
                try? handle.close()
                didClose = true
            }
        }

        do {
            var buffer: [UInt8] = []
            buffer.reserveCapacity(streamBufferBytes)
            for try await byte in asyncBytes {
                if Task.isCancelled { throw CancellationError() }
                buffer.append(byte)
                if buffer.count >= streamBufferBytes {
                    try handle.write(contentsOf: buffer)
                    meter.add(bytes: Int64(buffer.count))
                    buffer.removeAll(keepingCapacity: true)
                }
            }
            if !buffer.isEmpty {
                try Task.checkCancellation()
                try handle.write(contentsOf: buffer)
                meter.add(bytes: Int64(buffer.count))
            }
            closeHandle()
            return (persistentTemp, http.statusCode, contentType)
        } catch {
            closeHandle()
            try? FileManager.default.removeItem(at: persistentTemp)
            if Task.isCancelled || (error as? URLError)?.code == .cancelled {
                throw CancellationError()
            }
            throw error
        }
    }

    private nonisolated static func pwriteAll(fd: Int32, bytes: [UInt8], offset: Int64) throws {
        var written = 0
        while written < bytes.count {
            let result = bytes.withUnsafeBytes { raw in
                Darwin.pwrite(
                    fd,
                    raw.baseAddress!.advanced(by: written),
                    bytes.count - written,
                    off_t(offset) + off_t(written)
                )
            }
            if result < 0 {
                if errno == EINTR { continue }
                throw CloudSourceError.networkError(String(cString: strerror(errno)))
            }
            if result == 0 {
                throw CloudSourceError.networkError("Short write to temporary file.")
            }
            written += result
        }
    }

    private nonisolated static func parseContentRangeTotal(_ header: String?) -> Int64? {
        guard let header, let slash = header.lastIndex(of: "/") else { return nil }
        let totalPart = header[header.index(after: slash)...].trimmingCharacters(in: .whitespacesAndNewlines)
        guard totalPart != "*", let total = Int64(totalPart), total > 0 else { return nil }
        return total
    }
}
