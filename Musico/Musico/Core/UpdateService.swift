import CryptoKit
import Foundation
import Observation
import SwiftUI
import UIKit

/// Semantic version (`major.minor.patch`) parsed from release tags or bundle strings.
struct AppVersion: Comparable, Equatable, Hashable, Codable, Sendable, CustomStringConvertible {
    let major: Int
    let minor: Int
    let patch: Int

    var description: String { "\(major).\(minor).\(patch)" }

    init(major: Int, minor: Int, patch: Int) {
        self.major = major
        self.minor = minor
        self.patch = patch
    }

    /// Parses strings like `"v1.1.1"`, `"V.1.1.1"`, `"1.10.0"`, or `"1.2"` (treating missing patch as `0`).
    init?(_ raw: String) {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("v.") || s.hasPrefix("V.") {
            s.removeFirst(2)
        } else if s.hasPrefix("v") || s.hasPrefix("V") {
            s.removeFirst(1)
        }
        guard !s.isEmpty else { return nil }

        let parts = s.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 2 || parts.count == 3 else { return nil }

        var nums: [Int] = []
        nums.reserveCapacity(parts.count)
        for part in parts {
            guard !part.isEmpty,
                  part.allSatisfy({ $0.isASCII && $0.isNumber }),
                  let value = Int(part),
                  value >= 0 else {
                return nil
            }
            nums.append(value)
        }

        self.major = nums[0]
        self.minor = nums[1]
        self.patch = nums.count == 3 ? nums[2] : 0
    }

    static func < (lhs: AppVersion, rhs: AppVersion) -> Bool {
        (lhs.major, lhs.minor, lhs.patch) < (rhs.major, rhs.minor, rhs.patch)
    }

    static var current: AppVersion {
        let raw = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.0"
        return AppVersion(raw) ?? AppVersion(major: 0, minor: 0, patch: 0)
    }
}

struct ReleaseAsset: Codable, Equatable, Hashable, Sendable {
    let name: String
    let browserDownloadURL: URL
    let size: Int64

    enum CodingKeys: String, CodingKey {
        case name
        case browserDownloadURL = "browser_download_url"
        case size
    }

    init(name: String, browserDownloadURL: URL, size: Int64 = 0) {
        self.name = name
        self.browserDownloadURL = browserDownloadURL
        self.size = size
    }
}

struct ReleaseInfo: Equatable, Hashable, Sendable {
    let version: AppVersion
    let tagName: String
    let name: String
    let body: String
    let htmlURL: URL
    let publishedAt: Date?
    let dmgAsset: ReleaseAsset?
    let sha256Asset: ReleaseAsset?
}

enum UpdateError: LocalizedError, Equatable {
    case invalidResponse
    case httpStatus(Int)
    case invalidReleaseTag(String)
    case noDmgAsset(String)
    case checksumMismatch(expected: String, actual: String)
    case downloadFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            return "Received an invalid response from GitHub."
        case .httpStatus(let code):
            return "Update server returned HTTP \(code)."
        case .invalidReleaseTag(let tag):
            return "Could not parse release version tag \"\(tag)\"."
        case .noDmgAsset(let tag):
            return "No macOS disk image (.dmg) was attached to release \(tag)."
        case .checksumMismatch(let expected, let actual):
            return "SHA-256 verification failed (expected \(expected.prefix(12))…, got \(actual.prefix(12))…)."
        case .downloadFailed(let detail):
            return detail
        }
    }
}

enum SHA256SumsParser {
    /// Parses `<sha256>  <filename>` lines from a `SHA256SUMS.txt` file into `[filename: lowercaseHex]`.
    static func parse(_ text: String) -> [String: String] {
        var result: [String: String] = [:]
        for rawLine in text.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty, !line.hasPrefix("#") else { continue }
            guard let splitIndex = line.firstIndex(where: { $0 == " " || $0 == "\t" }) else { continue }

            let hashPart = String(line[..<splitIndex]).lowercased()
            guard hashPart.count == 64,
                  hashPart.allSatisfy({ ("0"..."9").contains($0) || ("a"..."f").contains($0) }) else {
                continue
            }

            var namePart = line[splitIndex...].trimmingCharacters(in: .whitespaces)
            if namePart.hasPrefix("*") {
                namePart.removeFirst()
            }
            if namePart.hasPrefix("./") {
                namePart.removeFirst(2)
            }
            guard !namePart.isEmpty else { continue }
            result[namePart] = hashPart
        }
        return result
    }

    static func checksum(for filename: String, in text: String) -> String? {
        let map = parse(text)
        if let exact = map[filename] { return exact }
        let targetBase = (filename as NSString).lastPathComponent
        return map.first { ($0.key as NSString).lastPathComponent == targetBase }?.value
    }

    static func sha256Hex(of data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func sha256Hex(ofFile url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while true {
            let chunk = try handle.read(upToCount: 1_048_576) ?? Data()
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

/// Fetches and parses the latest release metadata from GitHub Releases.
struct UpdateChecker: Sendable {
    static let defaultEndpoint = URL(string: "https://api.github.com/repos/SEANPNEX/local_music_player/releases/latest")!
    typealias DataLoader = @Sendable (URLRequest) async throws -> (Data, URLResponse)

    private struct GitHubReleaseDTO: Decodable {
        let tagName: String
        let name: String?
        let body: String?
        let draft: Bool
        let prerelease: Bool
        let htmlURL: URL
        let publishedAt: String?
        let assets: [ReleaseAsset]

        enum CodingKeys: String, CodingKey {
            case tagName = "tag_name"
            case name
            case body
            case draft
            case prerelease
            case htmlURL = "html_url"
            case publishedAt = "published_at"
            case assets
        }
    }

    private let endpoint: URL
    private let loadData: DataLoader

    init(endpoint: URL = UpdateChecker.defaultEndpoint, session: URLSession = .shared) {
        self.endpoint = endpoint
        self.loadData = { request in
            try await session.data(for: request)
        }
    }

    init(endpoint: URL = UpdateChecker.defaultEndpoint, loadData: @escaping DataLoader) {
        self.endpoint = endpoint
        self.loadData = loadData
    }

    func fetchLatestRelease() async throws -> ReleaseInfo? {
        var request = URLRequest(url: endpoint)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("Musico", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 20

        let (data, response) = try await loadData(request)
        guard let http = response as? HTTPURLResponse else {
            throw UpdateError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            throw UpdateError.httpStatus(http.statusCode)
        }
        return try Self.parseRelease(from: data)
    }

    static func parseRelease(from data: Data) throws -> ReleaseInfo? {
        let dto = try JSONDecoder().decode(GitHubReleaseDTO.self, from: data)
        if dto.draft || dto.prerelease {
            return nil
        }
        guard let version = AppVersion(dto.tagName) else {
            throw UpdateError.invalidReleaseTag(dto.tagName)
        }

        let dmgAsset = selectMacOSAsset(from: dto.assets)
        let sha256Asset = dto.assets.first {
            $0.name.caseInsensitiveCompare("SHA256SUMS.txt") == .orderedSame
        }

        let publishedDate: Date?
        if let publishedAt = dto.publishedAt {
            publishedDate = ISO8601DateFormatter().date(from: publishedAt)
        } else {
            publishedDate = nil
        }

        let title = (dto.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return ReleaseInfo(
            version: version,
            tagName: dto.tagName,
            name: title.isEmpty ? dto.tagName : title,
            body: dto.body ?? "",
            htmlURL: dto.htmlURL,
            publishedAt: publishedDate,
            dmgAsset: dmgAsset,
            sha256Asset: sha256Asset
        )
    }

    static func selectMacOSAsset(from assets: [ReleaseAsset]) -> ReleaseAsset? {
        let dmgAssets = assets.filter { $0.name.lowercased().hasSuffix(".dmg") }
        let preferred = dmgAssets.first { asset in
            let lower = asset.name.lowercased()
            return lower.hasPrefix("musico-") && lower.hasSuffix("-macos.dmg")
        }
        return preferred ?? dmgAssets.first
    }
}

@MainActor
@Observable
final class UpdateManager {
    enum State: Equatable {
        case idle
        case checking
        case upToDate
        case available(ReleaseInfo)
        case downloading(ReleaseInfo, progress: Double)
        case readyToInstall(ReleaseInfo, fileURL: URL)
        case error(String)
    }

    typealias FileDownloader = @Sendable (URL, URL, @escaping @Sendable (Double) async -> Void) async throws -> Void
    typealias DataFetcher = @Sendable (URL) async throws -> Data
    typealias URLOpener = @MainActor @Sendable (URL) async -> Bool

    static let skippedVersionKey = "skippedUpdateVersion"

    private(set) var state: State = .idle
    private(set) var lastCheckedRelease: ReleaseInfo?

    let currentVersion: AppVersion
    private let checker: UpdateChecker
    private let defaults: UserDefaults
    private let cacheDirectory: URL
    private let downloadFile: FileDownloader
    private let fetchData: DataFetcher
    private let openURL: URLOpener

    nonisolated init(
        checker: UpdateChecker = UpdateChecker(),
        defaults: UserDefaults = .standard,
        currentVersion: AppVersion = .current,
        cacheDirectory: URL? = nil,
        downloadFile: FileDownloader? = nil,
        fetchData: DataFetcher? = nil,
        openURL: URLOpener? = nil
    ) {
        self.checker = checker
        self.defaults = defaults
        self.currentVersion = currentVersion
        self.cacheDirectory = cacheDirectory ?? FileManager.default
            .urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Updates", isDirectory: true)
        self.downloadFile = downloadFile ?? Self.defaultDownloadFile
        self.fetchData = fetchData ?? Self.defaultFetchData
        self.openURL = openURL ?? { url in
            await UIApplication.shared.open(url)
        }
    }

    var skippedUpdateVersion: String? {
        defaults.string(forKey: Self.skippedVersionKey)
    }

    var hasAvailableUpdate: Bool {
        switch state {
        case .available, .downloading, .readyToInstall:
            return true
        case .idle, .checking, .upToDate, .error:
            return false
        }
    }

    var activeRelease: ReleaseInfo? {
        switch state {
        case .available(let info), .downloading(let info, _), .readyToInstall(let info, _):
            return info
        case .idle, .checking, .upToDate, .error:
            return lastCheckedRelease
        }
    }

    func checkForUpdates(manual: Bool) async {
        switch state {
        case .checking, .downloading:
            return
        default:
            break
        }

        state = .checking
        do {
            guard let release = try await checker.fetchLatestRelease() else {
                state = manual ? .upToDate : .idle
                return
            }
            lastCheckedRelease = release

            guard release.version > currentVersion else {
                state = manual ? .upToDate : .idle
                return
            }

            if !manual, isVersionSkipped(release.version) {
                state = .idle
                return
            }

            state = .available(release)
        } catch {
            state = manual ? .error(error.localizedDescription) : .idle
        }
    }

    func skipVersion(_ version: AppVersion) {
        defaults.set(version.description, forKey: Self.skippedVersionKey)
        state = .idle
    }

    func clearSkippedVersion() {
        defaults.removeObject(forKey: Self.skippedVersionKey)
    }

    func downloadAndOpenUpdate() async {
        if case .readyToInstall(let info, let existingURL) = state,
           FileManager.default.fileExists(atPath: existingURL.path) {
            let opened = await openURL(existingURL)
            if !opened {
                state = .error("Downloaded \(existingURL.lastPathComponent), but could not open it automatically. Use \"Open Release Page\" to install manually.")
            }
            return
        }

        let info: ReleaseInfo
        switch state {
        case .available(let release):
            info = release
        case .error:
            guard let release = lastCheckedRelease, release.version > currentVersion else { return }
            info = release
        default:
            return
        }

        guard let dmgAsset = info.dmgAsset else {
            state = .error(UpdateError.noDmgAsset(info.tagName).localizedDescription)
            return
        }

        let destinationURL = cacheDirectory.appendingPathComponent(dmgAsset.name)
        let tempURL = cacheDirectory.appendingPathComponent("\(dmgAsset.name).download")

        state = .downloading(info, progress: 0)
        do {
            try FileManager.default.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
            try? FileManager.default.removeItem(at: tempURL)

            try await downloadFile(dmgAsset.browserDownloadURL, tempURL) { [weak self] progress in
                await MainActor.run {
                    guard let self else { return }
                    if case .downloading(let current, _) = self.state, current == info {
                        self.state = .downloading(info, progress: min(max(progress, 0), 1))
                    }
                }
            }

            try? FileManager.default.removeItem(at: destinationURL)
            try FileManager.default.moveItem(at: tempURL, to: destinationURL)

            if let shaAsset = info.sha256Asset {
                let sumData = try await fetchData(shaAsset.browserDownloadURL)
                guard let sumText = String(data: sumData, encoding: .utf8) else {
                    try? FileManager.default.removeItem(at: destinationURL)
                    throw UpdateError.downloadFailed("Could not decode \(shaAsset.name).")
                }
                if let expected = SHA256SumsParser.checksum(for: dmgAsset.name, in: sumText) {
                    let actual = try SHA256SumsParser.sha256Hex(ofFile: destinationURL)
                    guard actual.caseInsensitiveCompare(expected) == .orderedSame else {
                        try? FileManager.default.removeItem(at: destinationURL)
                        throw UpdateError.checksumMismatch(expected: expected, actual: actual)
                    }
                }
            }

            state = .readyToInstall(info, fileURL: destinationURL)
            let opened = await openURL(destinationURL)
            if !opened {
                state = .error("Downloaded \(dmgAsset.name), but could not open it automatically. Use \"Open Release Page\" to install manually.")
            }
        } catch {
            try? FileManager.default.removeItem(at: tempURL)
            state = .error(error.localizedDescription)
        }
    }

    func openReleasePage() async {
        let fallback = URL(string: "https://github.com/SEANPNEX/local_music_player/releases")!
        _ = await openURL(activeRelease?.htmlURL ?? fallback)
    }

    private func isVersionSkipped(_ version: AppVersion) -> Bool {
        guard let stored = defaults.string(forKey: Self.skippedVersionKey) else { return false }
        if let parsed = AppVersion(stored) {
            return parsed == version
        }
        return stored == version.description
    }

    private static let defaultFetchData: DataFetcher = { url in
        var request = URLRequest(url: url)
        request.setValue("Musico", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw UpdateError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            throw UpdateError.httpStatus(http.statusCode)
        }
        return data
    }

    private static let defaultDownloadFile: FileDownloader = { url, destination, onProgress in
        var request = URLRequest(url: url)
        request.setValue("Musico", forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 120

        let (bytes, response) = try await URLSession.shared.bytes(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw UpdateError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            throw UpdateError.httpStatus(http.statusCode)
        }

        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let handle = try FileHandle(forWritingTo: destination)
        defer { try? handle.close() }

        let expectedLength = response.expectedContentLength
        var received: Int64 = 0
        var buffer = Data()
        buffer.reserveCapacity(65_536)
        var lastReportedBytes: Int64 = 0

        for try await byte in bytes {
            buffer.append(byte)
            if buffer.count >= 65_536 {
                try handle.write(contentsOf: buffer)
                received += Int64(buffer.count)
                buffer.removeAll(keepingCapacity: true)
                if expectedLength > 0, received - lastReportedBytes >= 262_144 {
                    lastReportedBytes = received
                    await onProgress(Double(received) / Double(expectedLength))
                }
            }
        }
        if !buffer.isEmpty {
            try handle.write(contentsOf: buffer)
            received += Int64(buffer.count)
        }
        await onProgress(1.0)
    }
}

extension EnvironmentValues {
    @Entry var updateManager = UpdateManager()
}
