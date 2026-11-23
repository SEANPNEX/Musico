import Foundation
import Observation
import SwiftUI

/// Thread-safe per-extension persistence backing `ctx.kv`, `ctx.settings`, and `ctx.log`.
final class ExtensionHostStorage: ExtensionRuntimeHostDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let baseDirectory: URL
    private var kvByExt: [String: [String: ExtensionKVEntry]] = [:]
    private var settingsByExt: [String: [String: ExtensionSettingValue]] = [:]
    private var logsByExt: [String: [ExtensionLogEntry]] = [:]
    var onLogsChanged: (@Sendable (String) -> Void)?

    init(baseDirectory: URL) {
        self.baseDirectory = baseDirectory
    }

    func loadExtension(id: String, settings: [String: ExtensionSettingValue]) {
        let extDir = baseDirectory.appendingPathComponent(id, isDirectory: true)
        let kv = Persistence.load([String: ExtensionKVEntry].self, from: extDir.appendingPathComponent("kv.json")) ?? [:]
        let logs = Persistence.load([ExtensionLogEntry].self, from: extDir.appendingPathComponent("logs.json")) ?? []
        lock.withLock {
            kvByExt[id] = kv
            settingsByExt[id] = settings
            logsByExt[id] = Array(logs.suffix(200))
        }
    }

    func setSettings(_ settings: [String: ExtensionSettingValue], for extensionID: String) {
        lock.withLock {
            settingsByExt[extensionID] = settings
        }
    }

    func removeExtension(id: String) {
        lock.withLock {
            kvByExt.removeValue(forKey: id)
            settingsByExt.removeValue(forKey: id)
            logsByExt.removeValue(forKey: id)
        }
    }

    func logs(for extensionID: String) -> [ExtensionLogEntry] {
        lock.withLock { logsByExt[extensionID] ?? [] }
    }

    func clearLogs(for extensionID: String) {
        lock.withLock {
            logsByExt[extensionID] = []
        }
        let logsURL = baseDirectory.appendingPathComponent(extensionID, isDirectory: true).appendingPathComponent("logs.json")
        try? Persistence.save([ExtensionLogEntry](), to: logsURL)
        onLogsChanged?(extensionID)
    }

    func kvGet(extensionID: String, key: String) -> String? {
        var didExpire = false
        let result: String? = lock.withLock {
            guard let entry = kvByExt[extensionID]?[key] else { return nil }
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            if let exp = entry.expiresAtMs, nowMs >= exp {
                kvByExt[extensionID]?.removeValue(forKey: key)
                didExpire = true
                return nil
            }
            return entry.value
        }
        if didExpire {
            saveKV(for: extensionID)
        }
        return result
    }

    func kvSet(extensionID: String, key: String, value: String, ttlSeconds: Double?) {
        let expiresAtMs: Int64? = {
            guard let ttlSeconds, ttlSeconds.isFinite, ttlSeconds > 0 else { return nil }
            return Int64(Date().timeIntervalSince1970 * 1000 + ttlSeconds * 1000)
        }()
        lock.withLock {
            var map = kvByExt[extensionID] ?? [:]
            map[key] = ExtensionKVEntry(value: value, expiresAtMs: expiresAtMs)
            kvByExt[extensionID] = map
        }
        saveKV(for: extensionID)
    }

    func kvRemove(extensionID: String, key: String) {
        lock.withLock {
            _ = kvByExt[extensionID]?.removeValue(forKey: key)
        }
        saveKV(for: extensionID)
    }

    func settingValue(extensionID: String, key: String) -> ExtensionSettingValue? {
        lock.withLock { settingsByExt[extensionID]?[key] }
    }

    func appendLog(extensionID: String, level: ExtensionLogEntry.Level, message: String) {
        let entry = ExtensionLogEntry(
            timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
            level: level,
            message: message
        )
        let snapshot: [ExtensionLogEntry] = lock.withLock {
            var list = logsByExt[extensionID] ?? []
            list.append(entry)
            if list.count > 200 {
                list.removeFirst(list.count - 200)
            }
            logsByExt[extensionID] = list
            return list
        }
        let logsURL = baseDirectory.appendingPathComponent(extensionID, isDirectory: true).appendingPathComponent("logs.json")
        try? Persistence.save(snapshot, to: logsURL)
        onLogsChanged?(extensionID)
    }

    private func saveKV(for extensionID: String) {
        let snapshot = lock.withLock { kvByExt[extensionID] ?? [:] }
        let kvURL = baseDirectory.appendingPathComponent(extensionID, isDirectory: true).appendingPathComponent("kv.json")
        try? Persistence.save(snapshot, to: kvURL)
    }
}

/// Manages installed JS extension packages, permission reviews, settings, KV caches, and logs.
@MainActor @Observable
final class ExtensionStore {
    nonisolated static let shared = ExtensionStore()

    nonisolated static var defaultBaseDirectory: URL {
        Persistence.supportDirectory.appendingPathComponent("Extensions", isDirectory: true)
    }

    private(set) var extensions: [InstalledExtension] {
        get {
            access(keyPath: \.extensions)
            return storedExtensions
        }
        set {
            withMutation(keyPath: \.extensions) {
                storedExtensions = newValue
            }
        }
    }
    @ObservationIgnored private nonisolated(unsafe) var storedExtensions: [InstalledExtension]
    @ObservationIgnored let baseDirectory: URL
    @ObservationIgnored let registry: CloudProviderRegistry
    @ObservationIgnored private let transport: any CloudHTTPTransport
    @ObservationIgnored private let hostStorage: ExtensionHostStorage
    @ObservationIgnored private let shortTimeoutMs: Int
    @ObservationIgnored private let longTimeoutMs: Int
    @ObservationIgnored private let lookupLock = NSLock()
    @ObservationIgnored private nonisolated(unsafe) var installedNamesByProviderID: [String: String] = [:]

    nonisolated init(
        baseDirectory: URL = ExtensionStore.defaultBaseDirectory,
        registry: CloudProviderRegistry = .shared,
        transport: any CloudHTTPTransport = URLSessionCloudTransport(),
        shortTimeoutMs: Int = JSExtensionRuntime.defaultShortTimeoutMs,
        longTimeoutMs: Int = JSExtensionRuntime.defaultLongTimeoutMs
    ) {
        self.baseDirectory = baseDirectory
        self.registry = registry
        self.transport = transport
        self.hostStorage = ExtensionHostStorage(baseDirectory: baseDirectory)
        self.shortTimeoutMs = shortTimeoutMs
        self.longTimeoutMs = longTimeoutMs

        let loaded = Self.loadAllFromDisk(baseDirectory: baseDirectory, hostStorage: hostStorage)
        self.storedExtensions = loaded
        self.installedNamesByProviderID = Dictionary(
            uniqueKeysWithValues: loaded.map { ($0.providerID, $0.manifest.name) }
        )
        Self.syncRegistry(extensions: loaded, registry: registry, transport: transport, hostStorage: hostStorage, shortTimeoutMs: shortTimeoutMs, longTimeoutMs: longTimeoutMs)

        self.hostStorage.onLogsChanged = { [weak self] extID in
            Task { @MainActor [weak self] in
                self?.refreshLogs(for: extID)
            }
        }
    }

    // MARK: - Lookup & Availability

    func `extension`(forID id: String) -> InstalledExtension? {
        extensions.first { $0.id == id }
    }

    func `extension`(forProviderID providerID: String?) -> InstalledExtension? {
        guard let providerID, !providerID.isEmpty else { return nil }
        return extensions.first { $0.providerID == providerID }
    }

    nonisolated func availability(forProviderID providerID: String?) -> CloudExtensionAvailability {
        guard let providerID, !providerID.isEmpty else {
            return .missing(providerID: "")
        }
        if registry.provider(forID: providerID) != nil {
            return .available
        }
        if let installedName = lookupLock.withLock({ installedNamesByProviderID[providerID] }) {
            return .disabled(name: installedName)
        }
        return .missing(providerID: providerID)
    }

    // MARK: - Import

    @discardableResult
    func importFromFile(at fileURL: URL) async throws -> InstalledExtension {
        let scoped = fileURL.startAccessingSecurityScopedResource()
        defer {
            if scoped { fileURL.stopAccessingSecurityScopedResource() }
        }
        let data = try Data(contentsOf: fileURL)
        return try await importPackageData(data, fileName: fileURL.lastPathComponent, sourceURL: nil)
    }

    @discardableResult
    func importFromURL(_ rawURLString: String) async throws -> InstalledExtension {
        let trimmed = rawURLString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed), let scheme = url.scheme?.lowercased() else {
            throw ExtensionImportError.invalidURL
        }
        guard scheme == "https" else {
            throw ExtensionImportError.insecureImportURL
        }
        guard let host = url.host, !host.isEmpty else {
            throw ExtensionImportError.invalidURL
        }

        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        let response: HTTPResponseData
        do {
            response = try await transport.send(request)
        } catch {
            throw ExtensionImportError.downloadFailed(error.localizedDescription)
        }
        guard (200..<300).contains(response.statusCode) else {
            throw ExtensionImportError.downloadFailed("HTTP \(response.statusCode)")
        }
        guard !response.data.isEmpty else {
            throw ExtensionImportError.downloadFailed("Empty response body.")
        }
        guard response.data.count <= ExtensionArchiveReader.maxArchiveBytes else {
            throw ExtensionArchiveError.archiveTooLarge(ExtensionArchiveReader.maxArchiveBytes / (1024 * 1024))
        }

        return try await importPackageData(response.data, fileName: url.lastPathComponent, sourceURL: url.absoluteString)
    }

    @discardableResult
    func importPackageData(_ data: Data, fileName: String, sourceURL: String?) async throws -> InstalledExtension {
        let lowerName = fileName.lowercased()
        let hasZipMagic = data.count >= 4
            && data[data.startIndex] == 0x50
            && data[data.startIndex + 1] == 0x4B
            && data[data.startIndex + 2] == 0x03
            && data[data.startIndex + 3] == 0x04

        let manifest: ExtensionManifest
        let scriptSource: String
        let iconData: Data?

        if hasZipMagic || lowerName.hasSuffix(".musicoext") || lowerName.hasSuffix(".zip") {
            let entries = try ExtensionArchiveReader.extractEntries(from: data)
            guard let manifestData = entries["manifest.json"] else {
                throw ExtensionImportError.invalidPackage("Missing manifest.json in archive.")
            }
            manifest = try JSONDecoder().decode(ExtensionManifest.self, from: manifestData)
            try manifest.validate()

            let entryPath = try ExtensionArchiveReader.validateAndNormalizeEntryPath(manifest.effectiveEntry, isDirectory: false)
            guard let scriptData = entries[entryPath],
                  let decodedScript = String(data: scriptData, encoding: .utf8),
                  !decodedScript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw ExtensionImportError.missingEntryScript(manifest.effectiveEntry)
            }
            scriptSource = decodedScript
            iconData = entries["icon.png"]
        } else {
            guard data.count <= ExtensionArchiveReader.maxUncompressedBytes else {
                throw ExtensionArchiveError.archiveTooLarge(ExtensionArchiveReader.maxUncompressedBytes / (1024 * 1024))
            }
            guard let decodedScript = String(data: data, encoding: .utf8),
                  !decodedScript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw ExtensionImportError.invalidPackage("Extension script is not valid UTF-8.")
            }
            var extractedManifest = try await JSExtensionRuntime.extractManifest(from: decodedScript)
            // Single-file scripts always execute from saved index.js.
            extractedManifest.entry = "index.js"
            try extractedManifest.validate()
            manifest = extractedManifest
            scriptSource = decodedScript
            iconData = nil
        }

        let extDir = baseDirectory.appendingPathComponent(manifest.id, isDirectory: true)
        try FileManager.default.createDirectory(at: extDir, withIntermediateDirectories: true)

        let existingState = extensions.first { $0.id == manifest.id }?.state
        let mergedSettings = filterCompatibleSettings(existingState?.settings ?? [:], manifest: manifest)
        // Newly imported or updated packages always require Permission Review before enabling.
        let newState = ExtensionState(
            isEnabled: false,
            permissionApproved: false,
            settings: mergedSettings,
            installedAt: existingState?.installedAt ?? Date(),
            updatedAt: Date(),
            sourceURL: sourceURL ?? existingState?.sourceURL
        )

        var storedManifest = manifest
        storedManifest.entry = "index.js"
        try Persistence.save(storedManifest, to: extDir.appendingPathComponent("manifest.json"))
        try Data(scriptSource.utf8).write(to: extDir.appendingPathComponent("index.js"), options: .atomic)
        if let iconData {
            try iconData.write(to: extDir.appendingPathComponent("icon.png"), options: .atomic)
        } else {
            try? FileManager.default.removeItem(at: extDir.appendingPathComponent("icon.png"))
        }
        try Persistence.save(newState, to: extDir.appendingPathComponent("state.json"))

        hostStorage.loadExtension(id: storedManifest.id, settings: mergedSettings)
        let installed = InstalledExtension(
            manifest: storedManifest,
            state: newState,
            directoryURL: extDir,
            iconData: iconData,
            logs: hostStorage.logs(for: storedManifest.id)
        )

        if let idx = extensions.firstIndex(where: { $0.id == installed.id }) {
            extensions[idx] = installed
        } else {
            extensions.append(installed)
            extensions.sort { $0.manifest.name.localizedStandardCompare($1.manifest.name) == .orderedAscending }
        }
        syncAll()
        return installed
    }

    // MARK: - Lifecycle, Settings & Logs

    func approvePermissionsAndEnable(id: String) {
        guard let idx = extensions.firstIndex(where: { $0.id == id }) else { return }
        extensions[idx].state.permissionApproved = true
        extensions[idx].state.isEnabled = true
        extensions[idx].state.updatedAt = Date()
        saveState(for: extensions[idx])
        syncAll()
    }

    /// Returns `false` if enabling was blocked because the user has not yet approved permissions.
    @discardableResult
    func setEnabled(id: String, enabled: Bool) -> Bool {
        guard let idx = extensions.firstIndex(where: { $0.id == id }) else { return false }
        if enabled && !extensions[idx].state.permissionApproved {
            return false
        }
        extensions[idx].state.isEnabled = enabled
        extensions[idx].state.updatedAt = Date()
        saveState(for: extensions[idx])
        syncAll()
        return true
    }

    func updateSetting(extensionID: String, key: String, value: ExtensionSettingValue) {
        guard let idx = extensions.firstIndex(where: { $0.id == extensionID }) else { return }
        extensions[idx].state.settings[key] = value
        extensions[idx].state.updatedAt = Date()
        hostStorage.setSettings(extensions[idx].state.settings, for: extensionID)
        saveState(for: extensions[idx])
    }

    func resetSettings(extensionID: String) {
        guard let idx = extensions.firstIndex(where: { $0.id == extensionID }) else { return }
        extensions[idx].state.settings = [:]
        extensions[idx].state.updatedAt = Date()
        hostStorage.setSettings([:], for: extensionID)
        saveState(for: extensions[idx])
    }

    func clearLogs(for extensionID: String) {
        hostStorage.clearLogs(for: extensionID)
        if let idx = extensions.firstIndex(where: { $0.id == extensionID }) {
            extensions[idx].logs = []
        }
    }

    func uninstall(id: String) {
        guard let idx = extensions.firstIndex(where: { $0.id == id }) else { return }
        let extDir = extensions[idx].directoryURL
        extensions.remove(at: idx)
        hostStorage.removeExtension(id: id)
        try? FileManager.default.removeItem(at: extDir)
        syncAll()
    }

    private func refreshLogs(for extensionID: String) {
        guard let idx = extensions.firstIndex(where: { $0.id == extensionID }) else { return }
        extensions[idx].logs = hostStorage.logs(for: extensionID)
    }

    private func saveState(for ext: InstalledExtension) {
        let stateURL = ext.directoryURL.appendingPathComponent("state.json")
        try? Persistence.save(ext.state, to: stateURL)
    }

    private func syncAll() {
        let map = Dictionary(uniqueKeysWithValues: extensions.map { ($0.providerID, $0.manifest.name) })
        lookupLock.withLock {
            installedNamesByProviderID = map
        }
        Self.syncRegistry(
            extensions: extensions,
            registry: registry,
            transport: transport,
            hostStorage: hostStorage,
            shortTimeoutMs: shortTimeoutMs,
            longTimeoutMs: longTimeoutMs
        )
    }

    private func filterCompatibleSettings(
        _ existing: [String: ExtensionSettingValue],
        manifest: ExtensionManifest
    ) -> [String: ExtensionSettingValue] {
        guard let specs = manifest.settings, !specs.isEmpty else { return [:] }
        let validKeys = Set(specs.map(\.key))
        return existing.filter { validKeys.contains($0.key) }
    }

    private nonisolated static func syncRegistry(
        extensions: [InstalledExtension],
        registry: CloudProviderRegistry,
        transport: any CloudHTTPTransport,
        hostStorage: ExtensionHostStorage,
        shortTimeoutMs: Int,
        longTimeoutMs: Int
    ) {
        var providers: [any CloudSourceProvider] = []
        for ext in extensions where ext.isActive {
            let scriptURL = ext.directoryURL.appendingPathComponent(ext.manifest.effectiveEntry)
            let fallbackURL = ext.directoryURL.appendingPathComponent("index.js")
            guard let source = (try? String(contentsOf: scriptURL, encoding: .utf8))
                    ?? (try? String(contentsOf: fallbackURL, encoding: .utf8)) else {
                continue
            }
            let runtime = JSExtensionRuntime(
                manifest: ext.manifest,
                scriptSource: source,
                transport: transport,
                hostDelegate: hostStorage,
                shortTimeoutMs: shortTimeoutMs,
                longTimeoutMs: longTimeoutMs
            )
            providers.append(JSExtensionCloudProvider(manifest: ext.manifest, runtime: runtime))
        }
        registry.replaceExtensions(providers)
    }

    private nonisolated static func loadAllFromDisk(
        baseDirectory: URL,
        hostStorage: ExtensionHostStorage
    ) -> [InstalledExtension] {
        let fm = FileManager.default
        guard let children = try? fm.contentsOfDirectory(
            at: baseDirectory,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }

        var loaded: [InstalledExtension] = []
        for dir in children {
            let isDir = (try? dir.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
            guard isDir else { continue }
            guard let manifest = Persistence.load(ExtensionManifest.self, from: dir.appendingPathComponent("manifest.json")),
                  (try? manifest.validate()) != nil else {
                continue
            }
            let entryURL = dir.appendingPathComponent(manifest.effectiveEntry)
            let fallbackURL = dir.appendingPathComponent("index.js")
            guard fm.fileExists(atPath: entryURL.path) || fm.fileExists(atPath: fallbackURL.path) else {
                continue
            }
            let state = Persistence.load(ExtensionState.self, from: dir.appendingPathComponent("state.json")) ?? ExtensionState()
            let iconData = try? Data(contentsOf: dir.appendingPathComponent("icon.png"))
            hostStorage.loadExtension(id: manifest.id, settings: state.settings)
            loaded.append(InstalledExtension(
                manifest: manifest,
                state: state,
                directoryURL: dir,
                iconData: iconData,
                logs: hostStorage.logs(for: manifest.id)
            ))
        }
        loaded.sort { $0.manifest.name.localizedStandardCompare($1.manifest.name) == .orderedAscending }
        return loaded
    }
}

extension EnvironmentValues {
    @Entry var extensionStore = ExtensionStore.shared
}
