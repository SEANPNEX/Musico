import Foundation
import SwiftUI
import UIKit

/// In-memory LRU thumbnail loader (`https://` URLs only) for cloud search and preview views.
/// Never writes browsed thumbnails to disk `ArtworkCache` or `URLCache`.
final class CloudThumbnailCache: @unchecked Sendable {
    static let shared = CloudThumbnailCache()

    private let imageCache = NSCache<NSString, UIImage>()
    private let dataCache = NSCache<NSString, NSData>()
    private let lock = NSLock()
    private var inFlight: [String: Task<UIImage?, Never>] = [:]
    private let fetcher: @Sendable (URL) async throws -> Data

    init(
        countLimit: Int = 200,
        totalCostLimitBytes: Int = 32 * 1024 * 1024,
        fetcher: (@Sendable (URL) async throws -> Data)? = nil
    ) {
        imageCache.countLimit = max(1, countLimit)
        imageCache.totalCostLimit = max(1024, totalCostLimitBytes)
        dataCache.countLimit = max(1, countLimit)
        dataCache.totalCostLimit = max(1024, totalCostLimitBytes / 2)

        if let fetcher {
            self.fetcher = fetcher
        } else {
            let config = URLSessionConfiguration.ephemeral
            config.urlCache = nil
            config.httpCookieStorage = nil
            config.requestCachePolicy = .reloadIgnoringLocalCacheData
            config.timeoutIntervalForRequest = 10
            config.timeoutIntervalForResource = 15
            let session = URLSession(configuration: config)
            self.fetcher = { url in
                var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
                req.setValue(YouTubeClient.webUserAgent, forHTTPHeaderField: "User-Agent")
                if let referer = Self.referer(for: url) {
                    req.setValue(referer, forHTTPHeaderField: "Referer")
                }
                let (data, response) = try await session.data(for: req)
                guard let http = response as? HTTPURLResponse,
                      (200..<300).contains(http.statusCode),
                      !data.isEmpty else {
                    throw URLError(.badServerResponse)
                }
                return data
            }
        }
    }

    static func referer(for url: URL) -> String? {
        guard let host = url.host?.lowercased() else { return nil }
        if host.contains("hdslb.com") || host == "bilibili.com" || host.hasSuffix(".bilibili.com") || host.contains("biliimg.com") {
            return BilibiliClient.referer
        }
        return nil
    }

    static func detectedMIMEType(for data: Data) -> String? {
        ArtworkImageFormat.detect(in: data)?.mimeType
    }

    static func validatedHTTPSURL(from raw: String?) -> URL? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty,
              let url = URL(string: trimmed),
              url.scheme?.lowercased() == "https",
              let host = url.host, !host.isEmpty else {
            return nil
        }
        return url
    }

    func cachedImage(for urlString: String?) -> UIImage? {
        guard let url = Self.validatedHTTPSURL(from: urlString) else { return nil }
        return imageCache.object(forKey: url.absoluteString as NSString)
    }

    func cachedImageData(for urlString: String?) -> Data? {
        guard let url = Self.validatedHTTPSURL(from: urlString) else { return nil }
        return dataCache.object(forKey: url.absoluteString as NSString).map { Data($0) }
    }

    func clear() {
        lock.withLock {
            for (_, task) in inFlight {
                task.cancel()
            }
            inFlight.removeAll()
        }
        imageCache.removeAllObjects()
        dataCache.removeAllObjects()
    }

    func loadImage(from urlString: String?) async -> UIImage? {
        guard let url = Self.validatedHTTPSURL(from: urlString) else { return nil }
        let key = url.absoluteString
        if let cached = imageCache.object(forKey: key as NSString) {
            return cached
        }

        let task: Task<UIImage?, Never> = lock.withLock {
            if let existing = inFlight[key] {
                return existing
            }
            let created = Task<UIImage?, Never> { [weak self] in
                guard let self else { return nil }
                defer {
                    self.lock.withLock {
                        _ = self.inFlight.removeValue(forKey: key)
                    }
                }
                guard let data = try? await self.fetcher(url),
                      !Task.isCancelled,
                      let image = UIImage(data: data) else {
                    return nil
                }
                self.imageCache.setObject(image, forKey: key as NSString, cost: max(1, data.count))
                self.dataCache.setObject(data as NSData, forKey: key as NSString, cost: max(1, data.count))
                return image
            }
            inFlight[key] = created
            return created
        }
        return await task.value
    }

    func loadImageData(from urlString: String?) async -> Data? {
        guard let url = Self.validatedHTTPSURL(from: urlString) else { return nil }
        let key = url.absoluteString
        if let cached = dataCache.object(forKey: key as NSString) {
            return Data(cached)
        }
        _ = await loadImage(from: key)
        return dataCache.object(forKey: key as NSString).map { Data($0) }
    }
}

struct CloudThumbnailView: View {
    let urlString: String?
    var placeholderSymbol: String = "music.note"
    var size: CGFloat = 44
    var cornerRadius: CGFloat? = nil

    @State private var image: UIImage?

    var body: some View {
        let radius = cornerRadius ?? (size * 0.14)
        Color(.tertiarySystemFill)
            .frame(width: size, height: size)
            .overlay {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: placeholderSymbol)
                        .font(.system(size: size * 0.38))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .task(id: urlString) {
                if let cached = CloudThumbnailCache.shared.cachedImage(for: urlString) {
                    image = cached
                    return
                }
                image = nil
                guard !Task.isCancelled else { return }
                let loaded = await CloudThumbnailCache.shared.loadImage(from: urlString)
                if !Task.isCancelled {
                    image = loaded
                }
            }
    }
}
