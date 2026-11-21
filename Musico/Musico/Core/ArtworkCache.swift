import UIKit
import CryptoKit

/// Cover art on disk, one downscaled JPEG per distinct image, with an in-memory cache in front.
final class ArtworkCache: @unchecked Sendable {
    static let maxDimension: CGFloat = 600

    private let directory: URL
    private let memory = NSCache<NSString, UIImage>()

    init(directory: URL) {
        self.directory = directory
        memory.countLimit = 200
    }

    private func fileURL(_ key: String) -> URL { directory.appendingPathComponent("\(key).jpg") }

    /// Stores the image and returns its key, or nil if the data is not an image.
    @discardableResult
    func store(_ data: Data) -> String? {
        let key = SHA256.hash(data: data).prefix(16).map { String(format: "%02x", $0) }.joined()
        let url = fileURL(key)
        if FileManager.default.fileExists(atPath: url.path) { return key }
        guard var image = UIImage(data: data) else { return nil }
        let longest = max(image.size.width, image.size.height) * image.scale
        if longest > Self.maxDimension {
            let ratio = Self.maxDimension / longest
            let target = CGSize(width: image.size.width * image.scale * ratio, height: image.size.height * image.scale * ratio)
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            image = UIGraphicsImageRenderer(size: target, format: format).image { _ in
                image.draw(in: CGRect(origin: .zero, size: target))
            }
        }
        guard let jpeg = image.jpegData(compressionQuality: 0.85) else { return nil }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        do { try jpeg.write(to: url, options: .atomic) } catch { return nil }
        return key
    }

    func image(forKey key: String) -> UIImage? {
        if let cached = memory.object(forKey: key as NSString) { return cached }
        guard let image = UIImage(contentsOfFile: fileURL(key).path) else { return nil }
        memory.setObject(image, forKey: key as NSString)
        return image
    }

    /// Deletes stored images that no track refers to any more.
    func prune(keeping keys: Set<String>) {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        for file in files where !keys.contains(file.deletingPathExtension().lastPathComponent) {
            memory.removeObject(forKey: file.deletingPathExtension().lastPathComponent as NSString)
            try? FileManager.default.removeItem(at: file)
        }
    }
}
