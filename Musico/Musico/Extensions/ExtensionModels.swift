import Foundation

/// Capabilities an extension can declare in `manifest.json`.
enum ExtensionCapability: String, Codable, Hashable, CaseIterable, Sendable {
    case playlist
    case tree
    case stream
    case cookie
    case proxy
    case lyrics
    case search
    case qrLogin = "qr_login"

    @MainActor
    var localizedTitle: String {
        switch self {
        case .playlist: L10n.tr("Playlists & Collections")
        case .tree: L10n.tr("Folder Tree & Subfolders")
        case .stream: L10n.tr("Audio Stream Resolution")
        case .cookie: L10n.tr("Cookie Authentication")
        case .proxy: L10n.tr("Proxy Support")
        case .lyrics: L10n.tr("Lyrics Search")
        case .search: L10n.tr("Cloud Search")
        case .qrLogin: L10n.tr("QR Code Login")
        }
    }

    @MainActor
    var localizedDescription: String {
        switch self {
        case .playlist: L10n.tr("Fetch playlists, albums, or single tracks.")
        case .tree: L10n.tr("Browse and crawl nested cloud subfolders.")
        case .stream: L10n.tr("Resolve playable audio stream URLs and headers.")
        case .cookie: L10n.tr("Accept an optional per-folder authentication cookie.")
        case .proxy: L10n.tr("Use system proxy configuration for requests.")
        case .lyrics: L10n.tr("Search online lyrics for playing songs.")
        case .search: L10n.tr("Search songs, albums, and playlists online.")
        case .qrLogin: L10n.tr("Sign in by scanning a QR code in-app.")
        }
    }
}

enum ExtensionQRLoginStatus: String, Codable, Hashable, Sendable {
    case pending
    case scanned
    case confirmed
    case expired
    case error
}

struct ExtensionQRLoginSession: Equatable, Sendable {
    var sessionId: String
    var qrContent: String
    var expiresAtMs: Double?
    var pollIntervalMs: Int
    var message: String?
}

struct ExtensionQRLoginPollResult: Equatable, Sendable {
    var status: ExtensionQRLoginStatus
    var cookie: String?
    var accountName: String?
    var message: String?
}

/// Entity types an extension declares support for in `manifest.searchKinds`.
enum ExtensionSearchKind: String, Codable, Hashable, CaseIterable, Sendable, Identifiable {
    case track
    case playlist
    case album

    var id: String { rawValue }

    @MainActor
    var localizedTitle: String {
        switch self {
        case .track: L10n.tr("Songs")
        case .playlist: L10n.tr("Playlists")
        case .album: L10n.tr("Albums")
        }
    }
}

enum CloudSearchKindFilter: String, Codable, Hashable, CaseIterable, Sendable, Identifiable {
    case all
    case track
    case playlist
    case album

    var id: String { rawValue }

    init(searchKind: ExtensionSearchKind) {
        switch searchKind {
        case .track: self = .track
        case .playlist: self = .playlist
        case .album: self = .album
        }
    }

    @MainActor
    var localizedTitle: String {
        switch self {
        case .all: L10n.tr("All")
        case .track: L10n.tr("Songs")
        case .playlist: L10n.tr("Playlists")
        case .album: L10n.tr("Albums")
        }
    }
}

struct CloudSearchExtensionDescriptor: Identifiable, Equatable, Hashable, Sendable {
    let id: String
    let providerID: String
    let name: String
    let badgeLabel: String
    let searchKinds: [ExtensionSearchKind]
    let supportsStream: Bool
    let supportsCollection: Bool
    let supportsCookie: Bool
}

struct CloudSearchResult: Identifiable, Hashable, Sendable {
    struct DedupKey: Hashable, Sendable {
        var kind: ExtensionSearchKind
        var itemID: String
    }

    var extensionID: String
    var kind: ExtensionSearchKind
    var remoteTrackID: String?
    var remoteId: String?
    var title: String
    var artist: String?
    var album: String?
    var durationSec: Double?
    var artworkUrl: String?
    var sourceUrl: String?
    var relativePath: String?
    var trackCount: Int?

    var itemID: String {
        switch kind {
        case .track: remoteTrackID ?? ""
        case .playlist, .album: remoteId ?? ""
        }
    }

    var dedupKey: DedupKey {
        DedupKey(kind: kind, itemID: itemID)
    }

    var id: String {
        "\(extensionID):\(kind.rawValue):\(itemID)"
    }

    func makeEphemeralTrack(now: Date = Date()) -> Track? {
        guard kind == .track,
              let rTrackID = remoteTrackID?.trimmingCharacters(in: .whitespacesAndNewlines),
              !rTrackID.isEmpty else {
            return nil
        }
        let cleanArtist = (artist?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false)
            ? artist!.trimmingCharacters(in: .whitespacesAndNewlines)
            : "Unknown Artist"
        let cleanAlbum = album?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let cleanRel = relativePath?.trimmingCharacters(in: .whitespacesAndNewlines)
        let duration = (durationSec ?? 0).isFinite ? max(0, durationSec ?? 0) : 0
        return Track(
            id: Track.makeEphemeralID(extensionID: extensionID, remoteTrackID: rTrackID),
            rootID: Track.ephemeralRootID,
            relativePath: (cleanRel?.isEmpty == false) ? cleanRel! : rTrackID,
            title: title,
            artist: cleanArtist,
            album: cleanAlbum,
            albumArtist: cleanArtist,
            trackNumber: nil,
            discNumber: nil,
            duration: duration,
            fileSize: 0,
            modified: now,
            hasEmbeddedLyrics: false,
            dateAdded: now,
            artworkKey: nil,
            genre: nil,
            cloudSource: "ext.\(extensionID)",
            remoteTrackID: rTrackID,
            remoteArtworkURL: artworkUrl,
            isEphemeral: true
        )
    }
}

struct CloudSearchPage: Equatable, Sendable {
    var items: [CloudSearchResult]
    var nextCursor: String?
}

/// Typed value stored for an extension setting or manifest default.
enum ExtensionSettingValue: Codable, Hashable, Sendable {
    case bool(Bool)
    case string(String)
    case number(Double)

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let boolVal = try? container.decode(Bool.self) {
            self = .bool(boolVal)
        } else if let strVal = try? container.decode(String.self) {
            self = .string(strVal)
        } else if let numVal = try? container.decode(Double.self) {
            self = .number(numVal)
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unsupported setting value type")
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .bool(let v):
            try container.encode(v)
        case .string(let v):
            try container.encode(v)
        case .number(let v):
            try container.encode(v)
        }
    }

    var boolValue: Bool {
        switch self {
        case .bool(let v): return v
        case .string(let s): return (s as NSString).boolValue
        case .number(let n): return n != 0
        }
    }

    var stringValue: String {
        switch self {
        case .bool(let v): return v ? "true" : "false"
        case .string(let s): return s
        case .number(let n):
            if n.rounded() == n, let i = Int64(exactly: n) {
                return String(i)
            }
            return String(n)
        }
    }

    /// JSON-compatible primitive for bridging across JavaScriptCore envelopes.
    var jsonPrimitive: Any {
        switch self {
        case .bool(let v): return v
        case .string(let s): return s
        case .number(let n): return n
        }
    }
}

/// Option item for `select` extension settings; accepts either `"value"` or `{"value","label"}`.
struct ExtensionSelectOption: Codable, Hashable, Identifiable, Sendable {
    var value: String
    var label: String

    var id: String { value }

    init(value: String, label: String? = nil) {
        self.value = value
        self.label = label ?? value
    }

    init(from decoder: Decoder) throws {
        if let single = try? decoder.singleValueContainer(),
           let raw = try? single.decode(String.self) {
            self.value = raw
            self.label = raw
            return
        }
        enum CodingKeys: String, CodingKey {
            case value, label
        }
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let val = try container.decode(String.self, forKey: .value)
        let lbl = try container.decodeIfPresent(String.self, forKey: .label)
        self.value = val
        self.label = (lbl?.isEmpty == false) ? lbl! : val
    }
}

/// User-configurable setting schema declared in `manifest.settings`.
struct ExtensionSettingSpec: Codable, Hashable, Identifiable, Sendable {
    enum SettingType: String, Codable, Hashable, Sendable {
        case boolean
        case string
        case select
        case serverURL = "server_url"
    }

    enum CodingKeys: String, CodingKey {
        case key
        case label
        case type
        case defaultValue = "default"
        case options
    }

    var key: String
    var label: String
    var type: SettingType
    var defaultValue: ExtensionSettingValue?
    var options: [ExtensionSelectOption]?

    var id: String { key }
}

struct ExtensionPermissions: Codable, Hashable, Sendable {
    var hosts: [String]
}

/// Strict HTTP/HTTPS host allowlist matching exact hosts or `*.sub.domain` (at least 2 labels).
enum HostAllowlistMatcher {
    static func normalizeHost(_ raw: String) -> String {
        var host = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        while host.hasSuffix(".") {
            host.removeLast()
        }
        return host
    }

    static func validatePattern(_ raw: String) throws -> String {
        let normalized = normalizeHost(raw)
        guard !normalized.isEmpty,
              normalized != "*",
              !normalized.contains("://"),
              !normalized.contains("/"),
              !normalized.contains(" "),
              !normalized.contains("\t") else {
            throw ExtensionManifestError.invalidHostPattern(raw)
        }

        if normalized.hasPrefix("*.") {
            let domain = String(normalized.dropFirst(2))
            let labels = domain.split(separator: ".", omittingEmptySubsequences: false)
            guard labels.count >= 2,
                  !domain.contains("*"),
                  labels.allSatisfy({ !$0.isEmpty }) else {
                throw ExtensionManifestError.invalidHostPattern(raw)
            }
        } else if normalized.contains("*") {
            throw ExtensionManifestError.invalidHostPattern(raw)
        }
        return normalized
    }

    static func isAllowed(urlString: String, patterns: [String]) -> Bool {
        guard let url = URL(string: urlString) else { return false }
        return isAllowed(url: url, patterns: patterns)
    }

    static func isAllowed(url: URL, patterns: [String]) -> Bool {
        guard let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            return false
        }
        guard let rawHost = URLComponents(url: url, resolvingAgainstBaseURL: true)?.host ?? url.host else {
            return false
        }
        let host = normalizeHost(rawHost)
        guard !host.isEmpty else { return false }

        for rawPattern in patterns {
            guard let pattern = try? validatePattern(rawPattern) else { continue }
            if pattern.hasPrefix("*.") {
                let domain = String(pattern.dropFirst(2))
                if host.hasSuffix("." + domain), host.count > domain.count + 1 {
                    return true
                }
            } else if host == pattern {
                return true
            }
        }
        return false
    }

    /// Returns `true` if `pattern` is already equal to or strictly narrower than a pattern in `allowedPatterns`.
    static func isPatternCovered(_ pattern: String, by allowedPatterns: [String]) -> Bool {
        guard let candidate = try? validatePattern(pattern) else { return false }
        let normalizedAllowed = allowedPatterns.compactMap { try? validatePattern($0) }
        for allowed in normalizedAllowed {
            if allowed == candidate {
                return true
            }
            if allowed.hasPrefix("*.") {
                let allowedDomain = String(allowed.dropFirst(2))
                let candidateDomain = candidate.hasPrefix("*.") ? String(candidate.dropFirst(2)) : candidate
                if candidateDomain.hasSuffix("." + allowedDomain), candidateDomain.count > allowedDomain.count + 1 {
                    return true
                }
            }
        }
        return false
    }

    static func extractedHost(from urlString: String) -> String {
        if let url = URL(string: urlString),
           let host = URLComponents(url: url, resolvingAgainstBaseURL: true)?.host ?? url.host,
           !host.isEmpty {
            return normalizeHost(host)
        }
        return urlString
    }

    static func extractApprovedHost(fromServerURL raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let rawHost = URLComponents(url: url, resolvingAgainstBaseURL: true)?.host ?? url.host else {
            return nil
        }
        let normalized = normalizeHost(rawHost)
        guard !normalized.isEmpty,
              !normalized.hasPrefix("*."),
              !normalized.contains("*"),
              let validated = try? validatePattern(normalized) else {
            return nil
        }
        return validated
    }
}

enum ExtensionManifestError: LocalizedError, Equatable {
    case invalidManifest(String)
    case unsupportedAPIVersion(Int)
    case invalidID(String)
    case invalidVersion(String)
    case invalidHostPattern(String)
    case invalidURLPattern(String)
    case invalidUpdateURL(String)
    case invalidEntryPath(String)
    case missingRequiredField(String)

    var errorDescription: String? {
        switch self {
        case .invalidManifest(let detail):
            L10n.trNonisolated("Invalid extension manifest: %@", detail)
        case .unsupportedAPIVersion(let version):
            L10n.trNonisolated("Unsupported extension API version: %@.", String(version))
        case .invalidID(let id):
            L10n.trNonisolated("Invalid extension ID “%@”.", id)
        case .invalidVersion(let version):
            L10n.trNonisolated("Invalid extension version “%@”.", version)
        case .invalidHostPattern(let pattern):
            L10n.trNonisolated("Invalid host permission pattern “%@”.", pattern)
        case .invalidURLPattern(let pattern):
            L10n.trNonisolated("Invalid URL pattern “%@”.", pattern)
        case .invalidUpdateURL(let url):
            L10n.trNonisolated("Invalid extension update URL “%@”.", url)
        case .invalidEntryPath(let entry):
            L10n.trNonisolated("Invalid extension entry path “%@”.", entry)
        case .missingRequiredField(let field):
            L10n.trNonisolated("Extension manifest is missing required field “%@”.", field)
        }
    }
}

/// Semantic version comparison supporting `major.minor.patch[-prerelease][+build]`.
struct ExtensionSemVer: Comparable, Equatable, Hashable, Sendable {
    let major: Int
    let minor: Int
    let patch: Int
    let prerelease: [String]

    init?(_ raw: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let withoutBuild = trimmed.split(separator: "+", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? trimmed
        let parts = withoutBuild.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
        let coreParts = parts[0].split(separator: ".", omittingEmptySubsequences: false)
        guard coreParts.count == 3,
              coreParts.allSatisfy({ !$0.isEmpty && $0.allSatisfy { $0.isASCII && $0.isNumber } }),
              let major = Int(coreParts[0]), major >= 0,
              let minor = Int(coreParts[1]), minor >= 0,
              let patch = Int(coreParts[2]), patch >= 0 else {
            return nil
        }

        let pre: [String]
        if parts.count == 2 {
            let rawPre = parts[1]
            guard !rawPre.isEmpty else { return nil }
            let preParts = rawPre.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
            guard preParts.allSatisfy({ !$0.isEmpty }) else { return nil }
            pre = preParts
        } else {
            pre = []
        }

        self.major = major
        self.minor = minor
        self.patch = patch
        self.prerelease = pre
    }

    static func < (lhs: ExtensionSemVer, rhs: ExtensionSemVer) -> Bool {
        if (lhs.major, lhs.minor, lhs.patch) != (rhs.major, rhs.minor, rhs.patch) {
            return (lhs.major, lhs.minor, lhs.patch) < (rhs.major, rhs.minor, rhs.patch)
        }
        if lhs.prerelease.isEmpty && rhs.prerelease.isEmpty { return false }
        if lhs.prerelease.isEmpty { return false }
        if rhs.prerelease.isEmpty { return true }
        for (l, r) in zip(lhs.prerelease, rhs.prerelease) {
            if l == r { continue }
            let lNum = Int(l), rNum = Int(r)
            switch (lNum, rNum) {
            case (.some(let ln), .some(let rn)):
                return ln < rn
            case (.some, .none):
                return true
            case (.none, .some):
                return false
            case (.none, .none):
                return l < r
            }
        }
        return lhs.prerelease.count < rhs.prerelease.count
    }
}

/// Supported stream resolution purpose passed to providers/extensions.
enum CloudStreamPurpose: String, Codable, Hashable, Sendable {
    case stream
    case download
}

/// Selectable audio quality tier declared by a cloud provider or extension `manifest.qualities`.
struct CloudQualityOption: Codable, Hashable, Identifiable, Sendable {
    var id: String
    var label: String
    var badge: String? = nil
    var lossless: Bool? = nil

    enum CodingKeys: String, CodingKey {
        case id, label, badge, lossless
    }

    init(id: String, label: String, badge: String? = nil, lossless: Bool? = nil) {
        self.id = id
        self.label = label
        self.badge = badge
        self.lossless = lossless
    }
}

/// Parsed and validated `manifest.json` describing an extension package.
struct ExtensionManifest: Codable, Hashable, Identifiable, Sendable {
    static let maxQualitiesCount = 16

    var apiVersion: Int
    var id: String
    var name: String
    var version: String
    var author: String? = nil
    var description: String? = nil
    var badgeLabel: String? = nil
    var entry: String? = nil
    var updateUrl: String? = nil
    var capabilities: [ExtensionCapability]
    var urlPatterns: [String] = []
    var permissions: ExtensionPermissions
    var settings: [ExtensionSettingSpec]? = nil
    var searchKinds: [ExtensionSearchKind]? = nil
    var qualities: [CloudQualityOption]? = nil
    var defaultStreamQuality: String? = nil
    var defaultDownloadQuality: String? = nil

    enum CodingKeys: String, CodingKey {
        case apiVersion, id, name, version, author, description, badgeLabel, entry, updateUrl, capabilities, urlPatterns, permissions, settings, searchKinds, qualities, defaultStreamQuality, defaultDownloadQuality
    }

    var providerID: String { "ext.\(id)" }

    var validatedUpdateURL: URL? {
        Self.validateHTTPSURL(updateUrl)
    }

    static func validateHTTPSURL(_ raw: String?) -> URL? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty,
              let url = URL(string: trimmed),
              url.scheme?.lowercased() == "https",
              let host = url.host,
              !host.isEmpty else {
            return nil
        }
        return url
    }

    var supportsCookieAuth: Bool {
        capabilities.contains(.cookie) || capabilities.contains(.qrLogin)
    }

    var supportsQRLogin: Bool {
        capabilities.contains(.qrLogin)
    }

    var effectiveSearchKinds: [ExtensionSearchKind] {
        let raw = searchKinds ?? []
        if raw.isEmpty {
            return ExtensionSearchKind.allCases
        }
        var seen = Set<ExtensionSearchKind>()
        return raw.filter { seen.insert($0).inserted }
    }

    var effectiveEntry: String {
        let trimmed = (entry ?? "index.js").trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "index.js" : trimmed
    }

    var effectiveBadgeLabel: String {
        let candidate = badgeLabel?.trimmingCharacters(in: .whitespacesAndNewlines)
        let raw = (candidate?.isEmpty == false) ? candidate! : name.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallback = raw.isEmpty ? id : raw
        if fallback.count <= 16 { return fallback }
        return String(fallback.prefix(16))
    }

    var hasServerURLSetting: Bool {
        (settings ?? []).contains { $0.type == .serverURL }
    }

    var effectiveQualities: [CloudQualityOption] {
        (qualities ?? []).compactMap { option in
            let id = option.id.trimmingCharacters(in: .whitespacesAndNewlines)
            let label = option.label.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty, !label.isEmpty else { return nil }
            let badge = option.badge?.trimmingCharacters(in: .whitespacesAndNewlines)
            return CloudQualityOption(
                id: id,
                label: label,
                badge: (badge?.isEmpty == false) ? badge : nil,
                lossless: option.lossless
            )
        }
    }

    var effectiveDefaultStreamQualityID: String? {
        let options = effectiveQualities
        guard !options.isEmpty else { return nil }
        if let raw = defaultStreamQuality?.trimmingCharacters(in: .whitespacesAndNewlines),
           !raw.isEmpty,
           options.contains(where: { $0.id == raw }) {
            return raw
        }
        if let standard = options.first(where: { $0.id == "standard" }) {
            return standard.id
        }
        return options.first?.id
    }

    var effectiveDefaultDownloadQualityID: String? {
        let options = effectiveQualities
        guard !options.isEmpty else { return nil }
        if let raw = defaultDownloadQuality?.trimmingCharacters(in: .whitespacesAndNewlines),
           !raw.isEmpty,
           options.contains(where: { $0.id == raw }) {
            return raw
        }
        if let highest = options.first(where: { $0.id == "highest" }) {
            return highest.id
        }
        return options.first?.id
    }

    func effectiveAllowedHosts(settingLookup: (String) -> ExtensionSettingValue?) -> [String] {
        var combined = permissions.hosts
        var seen = Set(combined.map { HostAllowlistMatcher.normalizeHost($0) })
        for spec in settings ?? [] where spec.type == .serverURL {
            guard let raw = (settingLookup(spec.key) ?? spec.defaultValue)?.stringValue,
                  let approvedHost = HostAllowlistMatcher.extractApprovedHost(fromServerURL: raw) else {
                continue
            }
            if seen.insert(approvedHost).inserted {
                combined.append(approvedHost)
            }
        }
        return combined
    }

    func defaultSettingValue(for key: String) -> ExtensionSettingValue? {
        settings?.first { $0.key == key }?.defaultValue
    }

    func validate() throws {
        guard apiVersion == 1 else {
            throw ExtensionManifestError.unsupportedAPIVersion(apiVersion)
        }
        guard Self.matchesRegex(id, pattern: #"^[a-z0-9._-]{3,64}$"#) else {
            throw ExtensionManifestError.invalidID(id)
        }
        guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ExtensionManifestError.missingRequiredField("name")
        }
        guard Self.matchesRegex(version, pattern: #"^\d+\.\d+\.\d+([+-][0-9A-Za-z.-]+)?$"#) else {
            throw ExtensionManifestError.invalidVersion(version)
        }
        guard !capabilities.isEmpty else {
            throw ExtensionManifestError.missingRequiredField("capabilities")
        }
        let requiresSourcePatterns = capabilities.contains(where: { $0 == .playlist || $0 == .tree })
        if requiresSourcePatterns && urlPatterns.isEmpty {
            throw ExtensionManifestError.missingRequiredField("urlPatterns")
        }
        for pattern in urlPatterns {
            guard !pattern.isEmpty, (try? NSRegularExpression(pattern: pattern)) != nil else {
                throw ExtensionManifestError.invalidURLPattern(pattern)
            }
        }
        guard !permissions.hosts.isEmpty || hasServerURLSetting else {
            throw ExtensionManifestError.missingRequiredField("permissions.hosts")
        }
        for host in permissions.hosts {
            _ = try HostAllowlistMatcher.validatePattern(host)
        }

        let entryPath = effectiveEntry.replacingOccurrences(of: "\\", with: "/")
        let segments = entryPath.split(separator: "/", omittingEmptySubsequences: false)
        guard !entryPath.hasPrefix("/"),
              !entryPath.contains("\0"),
              !segments.contains("..") else {
            throw ExtensionManifestError.invalidEntryPath(effectiveEntry)
        }

        if let rawUpdateUrl = updateUrl {
            guard Self.validateHTTPSURL(rawUpdateUrl) != nil else {
                throw ExtensionManifestError.invalidUpdateURL(rawUpdateUrl)
            }
        }

        if let settings {
            var seenKeys = Set<String>()
            for spec in settings {
                let cleanKey = spec.key.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !cleanKey.isEmpty, seenKeys.insert(cleanKey).inserted else {
                    throw ExtensionManifestError.invalidManifest("Duplicate or empty setting key '\(spec.key)'.")
                }
            }
        }

        if let qualities {
            guard qualities.count <= Self.maxQualitiesCount else {
                throw ExtensionManifestError.invalidManifest("Manifest qualities cannot exceed \(Self.maxQualitiesCount) entries.")
            }
            var seenQualityIDs = Set<String>()
            for option in qualities {
                let cleanID = option.id.trimmingCharacters(in: .whitespacesAndNewlines)
                let cleanLabel = option.label.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !cleanID.isEmpty else {
                    throw ExtensionManifestError.invalidManifest("Manifest quality entry is missing a non-empty id.")
                }
                guard !cleanLabel.isEmpty else {
                    throw ExtensionManifestError.invalidManifest("Manifest quality '\(cleanID)' is missing a non-empty label.")
                }
                guard seenQualityIDs.insert(cleanID).inserted else {
                    throw ExtensionManifestError.invalidManifest("Duplicate quality id '\(cleanID)'.")
                }
            }
            if let defaultStreamQuality {
                let clean = defaultStreamQuality.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !clean.isEmpty, seenQualityIDs.contains(clean) else {
                    throw ExtensionManifestError.invalidManifest("defaultStreamQuality '\(defaultStreamQuality)' must match an id in manifest.qualities.")
                }
            }
            if let defaultDownloadQuality {
                let clean = defaultDownloadQuality.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !clean.isEmpty, seenQualityIDs.contains(clean) else {
                    throw ExtensionManifestError.invalidManifest("defaultDownloadQuality '\(defaultDownloadQuality)' must match an id in manifest.qualities.")
                }
            }
        } else {
            if let defaultStreamQuality, !defaultStreamQuality.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                throw ExtensionManifestError.invalidManifest("defaultStreamQuality requires manifest.qualities to be declared.")
            }
            if let defaultDownloadQuality, !defaultDownloadQuality.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                throw ExtensionManifestError.invalidManifest("defaultDownloadQuality requires manifest.qualities to be declared.")
            }
        }
    }

    private static func matchesRegex(_ value: String, pattern: String) -> Bool {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return false }
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return regex.firstMatch(in: value, range: range) != nil
    }
}

extension ExtensionManifest {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.apiVersion = try c.decode(Int.self, forKey: .apiVersion)
        self.id = try c.decode(String.self, forKey: .id)
        self.name = try c.decode(String.self, forKey: .name)
        self.version = try c.decode(String.self, forKey: .version)
        self.author = try c.decodeIfPresent(String.self, forKey: .author)
        self.description = try c.decodeIfPresent(String.self, forKey: .description)
        self.badgeLabel = try c.decodeIfPresent(String.self, forKey: .badgeLabel)
        self.entry = try c.decodeIfPresent(String.self, forKey: .entry)
        self.updateUrl = try c.decodeIfPresent(String.self, forKey: .updateUrl)
        self.capabilities = try c.decode([ExtensionCapability].self, forKey: .capabilities)
        self.urlPatterns = try c.decodeIfPresent([String].self, forKey: .urlPatterns) ?? []
        self.permissions = try c.decode(ExtensionPermissions.self, forKey: .permissions)
        self.settings = try c.decodeIfPresent([ExtensionSettingSpec].self, forKey: .settings)
        self.searchKinds = try c.decodeIfPresent([ExtensionSearchKind].self, forKey: .searchKinds)
        self.qualities = try c.decodeIfPresent([CloudQualityOption].self, forKey: .qualities)
        self.defaultStreamQuality = try c.decodeIfPresent(String.self, forKey: .defaultStreamQuality)
        self.defaultDownloadQuality = try c.decodeIfPresent(String.self, forKey: .defaultDownloadQuality)
    }
}

/// Persisted enable/approval state and user settings (`state.json`).
struct ExtensionState: Codable, Equatable, Sendable {
    var isEnabled: Bool = false
    var permissionApproved: Bool = false
    var settings: [String: ExtensionSettingValue] = [:]
    var installedAt: Date = Date()
    var updatedAt: Date = Date()
    var sourceURL: String? = nil
    var cookie: String? = nil
    var streamQualityID: String? = nil
    var downloadQualityID: String? = nil

    enum CodingKeys: String, CodingKey {
        case isEnabled, permissionApproved, settings, installedAt, updatedAt, sourceURL, cookie, streamQualityID, downloadQualityID
    }

    init(
        isEnabled: Bool = false,
        permissionApproved: Bool = false,
        settings: [String: ExtensionSettingValue] = [:],
        installedAt: Date = Date(),
        updatedAt: Date = Date(),
        sourceURL: String? = nil,
        cookie: String? = nil,
        streamQualityID: String? = nil,
        downloadQualityID: String? = nil
    ) {
        self.isEnabled = isEnabled
        self.permissionApproved = permissionApproved
        self.settings = settings
        self.installedAt = installedAt
        self.updatedAt = updatedAt
        self.sourceURL = sourceURL
        self.cookie = cookie
        self.streamQualityID = streamQualityID
        self.downloadQualityID = downloadQualityID
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.isEnabled = try c.decodeIfPresent(Bool.self, forKey: .isEnabled) ?? false
        self.permissionApproved = try c.decodeIfPresent(Bool.self, forKey: .permissionApproved) ?? false
        self.settings = try c.decodeIfPresent([String: ExtensionSettingValue].self, forKey: .settings) ?? [:]
        self.installedAt = try c.decodeIfPresent(Date.self, forKey: .installedAt) ?? Date()
        self.updatedAt = try c.decodeIfPresent(Date.self, forKey: .updatedAt) ?? Date()
        self.sourceURL = try c.decodeIfPresent(String.self, forKey: .sourceURL)
        self.cookie = try c.decodeIfPresent(String.self, forKey: .cookie)
        self.streamQualityID = try c.decodeIfPresent(String.self, forKey: .streamQualityID)
        self.downloadQualityID = try c.decodeIfPresent(String.self, forKey: .downloadQualityID)
    }

    var isActive: Bool {
        isEnabled && permissionApproved
    }
}

/// Ring-buffer diagnostic log entry (`logs.json`).
struct ExtensionLogEntry: Codable, Hashable, Identifiable, Sendable {
    enum Level: String, Codable, Sendable {
        case info
        case warn
        case error
    }

    var id: UUID = UUID()
    var timestampMs: Int64
    var level: Level
    var message: String
}

/// Persisted TTL-capable KV record (`kv.json`).
struct ExtensionKVEntry: Codable, Equatable, Sendable {
    var value: String
    var expiresAtMs: Int64?
}

/// Availability status for a `.cloudExtension` library root.
enum CloudExtensionAvailability: Equatable, Sendable {
    case available
    case disabled(name: String)
    case missing(providerID: String)
}

/// Snapshot of an installed extension managed by `ExtensionStore`.
struct InstalledExtension: Identifiable, Equatable, Sendable {
    var manifest: ExtensionManifest
    var state: ExtensionState
    var directoryURL: URL
    var iconData: Data?
    var logs: [ExtensionLogEntry]

    var id: String { manifest.id }
    var providerID: String { manifest.providerID }
    var isActive: Bool { state.isActive }
}

enum ExtensionImportError: LocalizedError, Equatable {
    case invalidPackage(String)
    case invalidURL
    case insecureImportURL
    case downloadFailed(String)
    case missingEntryScript(String)

    var errorDescription: String? {
        switch self {
        case .invalidPackage(let reason):
            L10n.trNonisolated("Could not read extension package: %@", reason)
        case .invalidURL:
            L10n.trNonisolated("Enter a valid HTTPS extension URL.")
        case .insecureImportURL:
            L10n.trNonisolated("Extension URLs must use HTTPS.")
        case .downloadFailed(let message):
            L10n.trNonisolated("Could not download extension: %@", message)
        case .missingEntryScript(let path):
            L10n.trNonisolated("Extension package is missing entry script “%@”.", path)
        }
    }
}

/// Staged extension update waiting for explicit user review because permissions expanded.
struct PendingExtensionUpdate: Identifiable, Equatable, Sendable {
    var id: String { manifest.id }
    var installedVersion: String
    var manifest: ExtensionManifest
    var scriptSource: String
    var iconData: Data?
    var sourceURL: String
    var addedHosts: [String]
    var addedCapabilities: [ExtensionCapability]
    var checkedAt: Date
}

enum ExtensionUpdateStatus: Equatable, Sendable {
    case checking
    case upToDate
    case updated(toVersion: String)
    case pendingReview(version: String)
    case error(String)
}

enum ExtensionUpdateError: LocalizedError, Equatable {
    case noUpdateURL
    case insecureUpdateURL
    case mismatchedID(expected: String, actual: String)
    case invalidVersion(String)

    var errorDescription: String? {
        switch self {
        case .noUpdateURL:
            L10n.trNonisolated("This extension has no update URL configured.")
        case .insecureUpdateURL:
            L10n.trNonisolated("Extension update URLs must use HTTPS.")
        case .mismatchedID(let expected, let actual):
            L10n.trNonisolated("Update rejected: expected extension ID “%@”, got “%@”.", expected, actual)
        case .invalidVersion(let version):
            L10n.trNonisolated("Invalid extension version “%@”.", version)
        }
    }
}
