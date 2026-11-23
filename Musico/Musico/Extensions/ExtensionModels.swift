import Foundation

/// Capabilities an extension can declare in `manifest.json`.
enum ExtensionCapability: String, Codable, Hashable, CaseIterable, Sendable {
    case playlist
    case tree
    case stream
    case cookie
    case proxy
    case lyrics

    @MainActor
    var localizedTitle: String {
        switch self {
        case .playlist: L10n.tr("Playlists & Collections")
        case .tree: L10n.tr("Folder Tree & Subfolders")
        case .stream: L10n.tr("Audio Stream Resolution")
        case .cookie: L10n.tr("Cookie Authentication")
        case .proxy: L10n.tr("Proxy Support")
        case .lyrics: L10n.tr("Lyrics Search")
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
        }
    }
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

    static func extractedHost(from urlString: String) -> String {
        if let url = URL(string: urlString),
           let host = URLComponents(url: url, resolvingAgainstBaseURL: true)?.host ?? url.host,
           !host.isEmpty {
            return normalizeHost(host)
        }
        return urlString
    }
}

enum ExtensionManifestError: LocalizedError, Equatable {
    case invalidManifest(String)
    case unsupportedAPIVersion(Int)
    case invalidID(String)
    case invalidVersion(String)
    case invalidHostPattern(String)
    case invalidURLPattern(String)
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
        case .invalidEntryPath(let entry):
            L10n.trNonisolated("Invalid extension entry path “%@”.", entry)
        case .missingRequiredField(let field):
            L10n.trNonisolated("Extension manifest is missing required field “%@”.", field)
        }
    }
}

/// Parsed and validated `manifest.json` describing an extension package.
struct ExtensionManifest: Codable, Hashable, Identifiable, Sendable {
    var apiVersion: Int
    var id: String
    var name: String
    var version: String
    var author: String? = nil
    var description: String? = nil
    var badgeLabel: String? = nil
    var entry: String? = nil
    var capabilities: [ExtensionCapability]
    var urlPatterns: [String] = []
    var permissions: ExtensionPermissions
    var settings: [ExtensionSettingSpec]? = nil

    enum CodingKeys: String, CodingKey {
        case apiVersion, id, name, version, author, description, badgeLabel, entry, capabilities, urlPatterns, permissions, settings
    }

    var providerID: String { "ext.\(id)" }

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
        let requiresSourcePatterns = capabilities.contains(where: { $0 == .playlist || $0 == .tree || $0 == .stream })
        if requiresSourcePatterns && urlPatterns.isEmpty {
            throw ExtensionManifestError.missingRequiredField("urlPatterns")
        }
        for pattern in urlPatterns {
            guard !pattern.isEmpty, (try? NSRegularExpression(pattern: pattern)) != nil else {
                throw ExtensionManifestError.invalidURLPattern(pattern)
            }
        }
        guard !permissions.hosts.isEmpty else {
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

        if let settings {
            var seenKeys = Set<String>()
            for spec in settings {
                let cleanKey = spec.key.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !cleanKey.isEmpty, seenKeys.insert(cleanKey).inserted else {
                    throw ExtensionManifestError.invalidManifest("Duplicate or empty setting key '\(spec.key)'.")
                }
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
        self.capabilities = try c.decode([ExtensionCapability].self, forKey: .capabilities)
        self.urlPatterns = try c.decodeIfPresent([String].self, forKey: .urlPatterns) ?? []
        self.permissions = try c.decode(ExtensionPermissions.self, forKey: .permissions)
        self.settings = try c.decodeIfPresent([ExtensionSettingSpec].self, forKey: .settings)
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
