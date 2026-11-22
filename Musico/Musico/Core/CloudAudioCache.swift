import Foundation

/// LRU disk cache for resolved cloud audio streams, capped at 500 MB in `Caches/CloudAudio`.
actor CloudAudioCache {
    static let defaultMaxBytes: Int64 = 500 * 1024 * 1024

    static let shared: CloudAudioCache = {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return CloudAudioCache(directory: caches.appendingPathComponent("CloudAudio"))
    }()

    private static let supportedExtensions = ["m4a", "mp4", "flac", "mp3", "aac", "wav", "ogg"]

    let directory: URL
    let maxBytes: Int64
    private let resolver: any CloudStreamResolving
    private let downloader: @Sendable (URL, [String: String]) async throws -> (tempFile: URL, statusCode: Int, contentType: String?)
    private var inFlight: [String: Task<URL, Error>] = [:]

    init(
        directory: URL,
        maxBytes: Int64 = CloudAudioCache.defaultMaxBytes,
        resolver: any CloudStreamResolving = LiveCloudService.shared,
        downloader: (@Sendable (URL, [String: String]) async throws -> (tempFile: URL, statusCode: Int, contentType: String?))? = nil
    ) {
        self.directory = directory
        self.maxBytes = maxBytes
        self.resolver = resolver
        if let downloader {
            self.downloader = downloader
        } else {
            self.downloader = { url, headers in
                try await Self.downloadWithURLSession(url: url, headers: headers)
            }
        }
    }

    // MARK: Public API

    func cachedURLIfPresent(for trackID: String) -> URL? {
        for ext in Self.supportedExtensions {
            let candidate = fileURL(trackID: trackID, ext: ext)
            if FileManager.default.fileExists(atPath: candidate.path) {
                touch(candidate)
                return candidate
            }
        }
        return nil
    }

    func resolveAndCache(track: Track, root: LibraryRoot?, forceRefresh: Bool = false) async throws -> URL {
        if forceRefresh {
            remove(trackID: track.id)
        } else if let cached = cachedURLIfPresent(for: track.id) {
            return cached
        }

        if let existing = inFlight[track.id] {
            return try await existing.value
        }

        let task = Task<URL, Error> { [self] in
            try await self.performFetchAndStore(track: track, root: root, forceRefresh: forceRefresh)
        }
        inFlight[track.id] = task
        defer { inFlight[track.id] = nil }
        return try await task.value
    }

    func prefetch(track: Track, root: LibraryRoot?) async {
        guard track.isCloud, cachedURLIfPresent(for: track.id) == nil, inFlight[track.id] == nil else { return }
        _ = try? await resolveAndCache(track: track, root: root, forceRefresh: false)
    }

    func remove(trackID: String) {
        for ext in Self.supportedExtensions {
            let candidate = fileURL(trackID: trackID, ext: ext)
            try? FileManager.default.removeItem(at: candidate)
        }
    }

    /// Writes `data` atomically into the cache and enforces the LRU size limit. Useful for tests too.
    @discardableResult
    func storeData(_ data: Data, for trackID: String, fileExtension: String) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        remove(trackID: trackID)
        let ext = sanitizeExtension(fileExtension)
        let destination = fileURL(trackID: trackID, ext: ext)
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

    // MARK: Internal

    private func performFetchAndStore(track: Track, root: LibraryRoot?, forceRefresh: Bool) async throws -> URL {
        var stream = try await resolver.resolveStream(track: track, root: root, forceRefresh: forceRefresh)
        var attempts = [stream.url] + stream.fallbackURLs
        var lastError: Error = CloudSourceError.noPlayableStream

        for attemptIndex in 0..<2 {
            for candidateURL in attempts {
                do {
                    let result = try await downloader(candidateURL, stream.headers)
                    defer { try? FileManager.default.removeItem(at: result.tempFile) }

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
                        fileExtension: stream.fileExtension
                    )
                } catch {
                    lastError = error
                }
            }
            // If first pass failed (e.g. expired cached stream URL returning 403), force-refresh stream once.
            if attemptIndex == 0 && !forceRefresh {
                if let refreshed = try? await resolver.resolveStream(track: track, root: root, forceRefresh: true) {
                    stream = refreshed
                    attempts = [stream.url] + stream.fallbackURLs
                    continue
                }
            }
            break
        }
        throw lastError
    }

    private func moveTempFileIntoCache(tempFile: URL, trackID: String, fileExtension: String) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        remove(trackID: trackID)
        let ext = sanitizeExtension(fileExtension)
        let destination = fileURL(trackID: trackID, ext: ext)
        let staged = directory.appendingPathComponent(".tmp-\(UUID().uuidString).\(ext)")
        try FileManager.default.copyItem(at: tempFile, to: staged)
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

    private func fileURL(trackID: String, ext: String) -> URL {
        directory.appendingPathComponent("\(trackID).\(ext)")
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

    private static func downloadWithURLSession(
        url: URL,
        headers: [String: String]
    ) async throws -> (tempFile: URL, statusCode: Int, contentType: String?) {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 180
        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }

        var request = URLRequest(url: url)
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }
        let (downloadedTemp, response) = try await session.download(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw CloudSourceError.networkError(L10n.trNonisolated("Invalid download response."))
        }
        let contentType = http.value(forHTTPHeaderField: "Content-Type")
        // Move out of URLSession's ephemeral location before `download(for:)` deletes it on return.
        let persistentTemp = FileManager.default.temporaryDirectory
            .appendingPathComponent("musico-dl-\(UUID().uuidString)")
        try FileManager.default.moveItem(at: downloadedTemp, to: persistentTemp)
        return (persistentTemp, http.statusCode, contentType)
    }
}
