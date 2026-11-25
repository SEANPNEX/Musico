import Foundation
import Observation
import SwiftUI

/// Thread-safe per-extension persistence backing `ctx.kv`, `ctx.settings`, and `ctx.log`.
final class ExtensionHostStorage: ExtensionRuntimeHostDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let baseDirectory: URL
    private var kvByExt: [String: [String: ExtensionKVEntry]] = [:]
    private var settingsByExt: [String: [String: ExtensionSettingValue]] = [:]
    private var cookiesByExt: [String: String] = [:]
    private var streamQualityByExt: [String: String] = [:]
    private var downloadQualityByExt: [String: String] = [:]
    private var logsByExt: [String: [ExtensionLogEntry]] = [:]
    var onLogsChanged: (@Sendable (String) -> Void)?

    init(baseDirectory: URL) {
        self.baseDirectory = baseDirectory
    }

    func loadExtension(
        id: String,
        settings: [String: ExtensionSettingValue],
        cookie: String? = nil,
        streamQualityID: String? = nil,
        downloadQualityID: String? = nil
    ) {
        let extDir = baseDirectory.appendingPathComponent(id, isDirectory: true)
        let kv = Persistence.load([String: ExtensionKVEntry].self, from: extDir.appendingPathComponent("kv.json")) ?? [:]
        let logs = Persistence.load([ExtensionLogEntry].self, from: extDir.appendingPathComponent("logs.json")) ?? []
        let cleanCookie = cookie?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanStreamQ = streamQualityID?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDownloadQ = downloadQualityID?.trimmingCharacters(in: .whitespacesAndNewlines)
        lock.withLock {
            kvByExt[id] = kv
            settingsByExt[id] = settings
            if let cleanCookie, !cleanCookie.isEmpty {
                cookiesByExt[id] = cleanCookie
            } else {
                cookiesByExt.removeValue(forKey: id)
            }
            if let cleanStreamQ, !cleanStreamQ.isEmpty {
                streamQualityByExt[id] = cleanStreamQ
            } else {
                streamQualityByExt.removeValue(forKey: id)
            }
            if let cleanDownloadQ, !cleanDownloadQ.isEmpty {
                downloadQualityByExt[id] = cleanDownloadQ
            } else {
                downloadQualityByExt.removeValue(forKey: id)
            }
            logsByExt[id] = Array(logs.suffix(200))
        }
    }

    func setSettings(_ settings: [String: ExtensionSettingValue], for extensionID: String) {
        lock.withLock {
            settingsByExt[extensionID] = settings
        }
    }

    func setQualityPreferences(streamQualityID: String?, downloadQualityID: String?, for extensionID: String) {
        let cleanStreamQ = streamQualityID?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDownloadQ = downloadQualityID?.trimmingCharacters(in: .whitespacesAndNewlines)
        lock.withLock {
            if let cleanStreamQ, !cleanStreamQ.isEmpty {
                streamQualityByExt[extensionID] = cleanStreamQ
            } else {
                streamQualityByExt.removeValue(forKey: extensionID)
            }
            if let cleanDownloadQ, !cleanDownloadQ.isEmpty {
                downloadQualityByExt[extensionID] = cleanDownloadQ
            } else {
                downloadQualityByExt.removeValue(forKey: extensionID)
            }
        }
    }

    func streamQualityID(for extensionID: String) -> String? {
        lock.withLock { streamQualityByExt[extensionID] }
    }

    func downloadQualityID(for extensionID: String) -> String? {
        lock.withLock { downloadQualityByExt[extensionID] }
    }

    func setCookie(_ cookie: String?, for extensionID: String) {
        let cleanCookie = cookie?.trimmingCharacters(in: .whitespacesAndNewlines)
        lock.withLock {
            if let cleanCookie, !cleanCookie.isEmpty {
                cookiesByExt[extensionID] = cleanCookie
            } else {
                cookiesByExt.removeValue(forKey: extensionID)
            }
        }
    }

    func cookie(for extensionID: String) -> String? {
        lock.withLock { cookiesByExt[extensionID] }
    }

    func removeExtension(id: String) {
        lock.withLock {
            kvByExt.removeValue(forKey: id)
            settingsByExt.removeValue(forKey: id)
            cookiesByExt.removeValue(forKey: id)
            streamQualityByExt.removeValue(forKey: id)
            downloadQualityByExt.removeValue(forKey: id)
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
    nonisolated static let autoUpdateInterval: TimeInterval = 6 * 60 * 60
    nonisolated static let autoUpdateEnabledDefaultsKey = "extensions.autoUpdateEnabled"
    nonisolated static let lastUpdateCheckDefaultsKey = "extensions.lastUpdateCheckAt"

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
    var autoUpdateEnabled: Bool {
        get {
            access(keyPath: \.autoUpdateEnabled)
            return storedAutoUpdateEnabled
        }
        set {
            withMutation(keyPath: \.autoUpdateEnabled) {
                storedAutoUpdateEnabled = newValue
            }
            defaults.set(newValue, forKey: Self.autoUpdateEnabledDefaultsKey)
        }
    }
    private(set) var lastUpdateCheckAt: Date? {
        get {
            access(keyPath: \.lastUpdateCheckAt)
            return storedLastUpdateCheckAt
        }
        set {
            withMutation(keyPath: \.lastUpdateCheckAt) {
                storedLastUpdateCheckAt = newValue
            }
        }
    }
    private(set) var isCheckingForUpdates: Bool = false
    private(set) var pendingUpdates: [String: PendingExtensionUpdate] = [:]
    private(set) var updateStatusByID: [String: ExtensionUpdateStatus] = [:]

    @ObservationIgnored private nonisolated(unsafe) var storedExtensions: [InstalledExtension]
    @ObservationIgnored private nonisolated(unsafe) var storedAutoUpdateEnabled: Bool
    @ObservationIgnored private nonisolated(unsafe) var storedLastUpdateCheckAt: Date?
    @ObservationIgnored let baseDirectory: URL
    @ObservationIgnored let registry: CloudProviderRegistry
    @ObservationIgnored private let transport: any CloudHTTPTransport
    @ObservationIgnored private let hostStorage: ExtensionHostStorage
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let shortTimeoutMs: Int
    @ObservationIgnored private let longTimeoutMs: Int
    @ObservationIgnored private let lookupLock = NSLock()
    @ObservationIgnored private nonisolated(unsafe) var installedNamesByProviderID: [String: String] = [:]
    @ObservationIgnored private nonisolated(unsafe) var lyricsRuntimesByID: [String: (badgeLabel: String, runtime: JSExtensionRuntime)] = [:]
    @ObservationIgnored private nonisolated(unsafe) var searchRuntimesByID: [String: (manifest: ExtensionManifest, runtime: JSExtensionRuntime)] = [:]
    @ObservationIgnored private nonisolated(unsafe) var qrLoginRuntimesByID: [String: (manifest: ExtensionManifest, runtime: JSExtensionRuntime)] = [:]

    nonisolated init(
        baseDirectory: URL = ExtensionStore.defaultBaseDirectory,
        registry: CloudProviderRegistry = .shared,
        transport: any CloudHTTPTransport = URLSessionCloudTransport(),
        defaults: UserDefaults = .standard,
        shortTimeoutMs: Int = JSExtensionRuntime.defaultShortTimeoutMs,
        longTimeoutMs: Int = JSExtensionRuntime.defaultLongTimeoutMs
    ) {
        self.baseDirectory = baseDirectory
        self.registry = registry
        self.transport = transport
        self.hostStorage = ExtensionHostStorage(baseDirectory: baseDirectory)
        self.defaults = defaults
        self.shortTimeoutMs = shortTimeoutMs
        self.longTimeoutMs = longTimeoutMs
        if defaults.object(forKey: Self.autoUpdateEnabledDefaultsKey) != nil {
            self.storedAutoUpdateEnabled = defaults.bool(forKey: Self.autoUpdateEnabledDefaultsKey)
        } else {
            self.storedAutoUpdateEnabled = true
        }
        if let ts = defaults.object(forKey: Self.lastUpdateCheckDefaultsKey) as? Double {
            self.storedLastUpdateCheckAt = Date(timeIntervalSince1970: ts)
        } else {
            self.storedLastUpdateCheckAt = nil
        }

        let loaded = Self.loadAllFromDisk(baseDirectory: baseDirectory, hostStorage: hostStorage)
        self.storedExtensions = loaded
        self.installedNamesByProviderID = Dictionary(
            uniqueKeysWithValues: loaded.map { ($0.providerID, $0.manifest.name) }
        )
        let synced = Self.syncRegistry(
            extensions: loaded,
            registry: registry,
            transport: transport,
            hostStorage: hostStorage,
            shortTimeoutMs: shortTimeoutMs,
            longTimeoutMs: longTimeoutMs
        )
        self.lyricsRuntimesByID = synced.lyrics
        self.searchRuntimesByID = synced.search
        self.qrLoginRuntimesByID = synced.qrLogin

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

    nonisolated func installedName(forProviderID providerID: String?) -> String? {
        guard let providerID, !providerID.isEmpty else { return nil }
        return lookupLock.withLock { installedNamesByProviderID[providerID] }
    }

    nonisolated func cookie(forExtensionID id: String) -> String? {
        hostStorage.cookie(for: id)
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

        let data = try await downloadPackageData(from: url)
        return try await importPackageData(data, fileName: url.lastPathComponent, sourceURL: url.absoluteString)
    }

    private func downloadPackageData(from url: URL) async throws -> Data {
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
        return response.data
    }

    private func parsePackageData(
        _ data: Data,
        fileName: String
    ) async throws -> (manifest: ExtensionManifest, scriptSource: String, iconData: Data?) {
        let lowerName = fileName.lowercased()
        let hasZipMagic = data.count >= 4
            && data[data.startIndex] == 0x50
            && data[data.startIndex + 1] == 0x4B
            && data[data.startIndex + 2] == 0x03
            && data[data.startIndex + 3] == 0x04

        if hasZipMagic || lowerName.hasSuffix(".musicoext") || lowerName.hasSuffix(".zip") {
            let entries = try ExtensionArchiveReader.extractEntries(from: data)
            guard let manifestData = entries["manifest.json"] else {
                throw ExtensionImportError.invalidPackage("Missing manifest.json in archive.")
            }
            let manifest = try JSONDecoder().decode(ExtensionManifest.self, from: manifestData)
            try manifest.validate()

            let entryPath = try ExtensionArchiveReader.validateAndNormalizeEntryPath(manifest.effectiveEntry, isDirectory: false)
            guard let scriptData = entries[entryPath],
                  let decodedScript = String(data: scriptData, encoding: .utf8),
                  !decodedScript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                throw ExtensionImportError.missingEntryScript(manifest.effectiveEntry)
            }
            return (manifest, decodedScript, entries["icon.png"])
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
            return (extractedManifest, decodedScript, nil)
        }
    }

    @discardableResult
    func importPackageData(_ data: Data, fileName: String, sourceURL: String?) async throws -> InstalledExtension {
        let parsed = try await parsePackageData(data, fileName: fileName)
        let installed = try writeExtensionPackage(
            manifest: parsed.manifest,
            scriptSource: parsed.scriptSource,
            iconData: parsed.iconData,
            sourceURL: sourceURL,
            isEnabled: false,
            permissionApproved: false,
            now: Date()
        )
        pendingUpdates.removeValue(forKey: installed.id)
        updateStatusByID.removeValue(forKey: installed.id)
        return installed
    }

    @discardableResult
    private func writeExtensionPackage(
        manifest: ExtensionManifest,
        scriptSource: String,
        iconData: Data?,
        sourceURL: String?,
        isEnabled: Bool,
        permissionApproved: Bool,
        now: Date
    ) throws -> InstalledExtension {
        let extDir = baseDirectory.appendingPathComponent(manifest.id, isDirectory: true)
        try FileManager.default.createDirectory(at: extDir, withIntermediateDirectories: true)

        let existingState = extensions.first { $0.id == manifest.id }?.state
        let mergedSettings = filterCompatibleSettings(existingState?.settings ?? [:], manifest: manifest)
        let preservedCookie = manifest.supportsCookieAuth ? existingState?.cookie : nil
        let validQualityIDs = Set(manifest.effectiveQualities.map(\.id))
        let preservedStreamQuality = existingState?.streamQualityID.flatMap { validQualityIDs.contains($0) ? $0 : nil }
        let preservedDownloadQuality = existingState?.downloadQualityID.flatMap { validQualityIDs.contains($0) ? $0 : nil }
        let newState = ExtensionState(
            isEnabled: isEnabled,
            permissionApproved: permissionApproved,
            settings: mergedSettings,
            installedAt: existingState?.installedAt ?? now,
            updatedAt: now,
            sourceURL: sourceURL ?? existingState?.sourceURL,
            cookie: preservedCookie,
            streamQualityID: preservedStreamQuality,
            downloadQualityID: preservedDownloadQuality
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

        hostStorage.loadExtension(
            id: storedManifest.id,
            settings: mergedSettings,
            cookie: preservedCookie,
            streamQualityID: preservedStreamQuality,
            downloadQualityID: preservedDownloadQuality
        )
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

    // MARK: - Updates

    /// Prefers valid HTTPS `manifest.updateUrl`, falling back to valid HTTPS `state.sourceURL`.
    func updateSourceURL(for ext: InstalledExtension) -> URL? {
        if let manifestURL = ext.manifest.validatedUpdateURL {
            return manifestURL
        }
        return ExtensionManifest.validateHTTPSURL(ext.state.sourceURL)
    }

    static func permissionEscalation(
        from current: ExtensionManifest,
        to candidate: ExtensionManifest
    ) -> (addedHosts: [String], addedCapabilities: [ExtensionCapability]) {
        var addedHosts: [String] = []
        var seenHosts = Set<String>()
        for host in candidate.permissions.hosts {
            let norm = HostAllowlistMatcher.normalizeHost(host)
            if !HostAllowlistMatcher.isPatternCovered(host, by: current.permissions.hosts),
               seenHosts.insert(norm).inserted {
                addedHosts.append(host)
            }
        }
        let currentCaps = Set(current.capabilities)
        var addedCapabilities: [ExtensionCapability] = []
        var seenCaps = Set<ExtensionCapability>()
        for cap in candidate.capabilities where !currentCaps.contains(cap) {
            if seenCaps.insert(cap).inserted {
                addedCapabilities.append(cap)
            }
        }
        return (addedHosts, addedCapabilities)
    }

    func checkForUpdatesIfNeeded(now: Date = Date()) async {
        guard autoUpdateEnabled else { return }
        if let lastUpdateCheckAt, now.timeIntervalSince(lastUpdateCheckAt) < Self.autoUpdateInterval {
            return
        }
        await checkForUpdates(manual: false, now: now)
    }

    func checkForUpdates(manual: Bool = true, now: Date = Date()) async {
        guard !isCheckingForUpdates else { return }
        _ = manual
        isCheckingForUpdates = true
        defer { isCheckingForUpdates = false }

        lastUpdateCheckAt = now
        defaults.set(now.timeIntervalSince1970, forKey: Self.lastUpdateCheckDefaultsKey)

        let snapshot = extensions
        for ext in snapshot {
            guard updateSourceURL(for: ext) != nil else { continue }
            await checkSingleExtensionUpdate(ext, now: now)
        }
    }

    func checkForUpdate(extensionID: String, now: Date = Date()) async {
        guard let ext = self.extension(forID: extensionID) else { return }
        await checkSingleExtensionUpdate(ext, now: now)
    }

    private func checkSingleExtensionUpdate(_ ext: InstalledExtension, now: Date) async {
        guard let updateURL = updateSourceURL(for: ext) else {
            let hasRawCandidate = !(ext.manifest.updateUrl?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
                || !(ext.state.sourceURL?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
            let err: ExtensionUpdateError = hasRawCandidate ? .insecureUpdateURL : .noUpdateURL
            updateStatusByID[ext.id] = .error(err.localizedDescription)
            return
        }

        updateStatusByID[ext.id] = .checking
        do {
            let data = try await downloadPackageData(from: updateURL)
            let parsed = try await parsePackageData(data, fileName: updateURL.lastPathComponent)

            guard parsed.manifest.id == ext.id else {
                throw ExtensionUpdateError.mismatchedID(expected: ext.id, actual: parsed.manifest.id)
            }
            guard let installedSemVer = ExtensionSemVer(ext.manifest.version) else {
                throw ExtensionUpdateError.invalidVersion(ext.manifest.version)
            }
            guard let remoteSemVer = ExtensionSemVer(parsed.manifest.version) else {
                throw ExtensionUpdateError.invalidVersion(parsed.manifest.version)
            }

            guard remoteSemVer > installedSemVer else {
                pendingUpdates.removeValue(forKey: ext.id)
                updateStatusByID[ext.id] = .upToDate
                return
            }

            let escalation = Self.permissionEscalation(from: ext.manifest, to: parsed.manifest)
            if escalation.addedHosts.isEmpty && escalation.addedCapabilities.isEmpty && ext.state.permissionApproved {
                try writeExtensionPackage(
                    manifest: parsed.manifest,
                    scriptSource: parsed.scriptSource,
                    iconData: parsed.iconData,
                    sourceURL: ext.state.sourceURL,
                    isEnabled: ext.state.isEnabled,
                    permissionApproved: true,
                    now: now
                )
                pendingUpdates.removeValue(forKey: ext.id)
                updateStatusByID[ext.id] = .updated(toVersion: parsed.manifest.version)
            } else {
                pendingUpdates[ext.id] = PendingExtensionUpdate(
                    installedVersion: ext.manifest.version,
                    manifest: parsed.manifest,
                    scriptSource: parsed.scriptSource,
                    iconData: parsed.iconData,
                    sourceURL: updateURL.absoluteString,
                    addedHosts: escalation.addedHosts,
                    addedCapabilities: escalation.addedCapabilities,
                    checkedAt: now
                )
                updateStatusByID[ext.id] = .pendingReview(version: parsed.manifest.version)
            }
        } catch {
            updateStatusByID[ext.id] = .error(error.localizedDescription)
        }
    }

    @discardableResult
    func approvePendingUpdate(id: String, now: Date = Date()) -> InstalledExtension? {
        guard let pending = pendingUpdates[id],
              let ext = self.extension(forID: id) else {
            return nil
        }
        guard let updated = try? writeExtensionPackage(
            manifest: pending.manifest,
            scriptSource: pending.scriptSource,
            iconData: pending.iconData,
            sourceURL: ext.state.sourceURL,
            isEnabled: true,
            permissionApproved: true,
            now: now
        ) else {
            return nil
        }
        pendingUpdates.removeValue(forKey: id)
        updateStatusByID[id] = .updated(toVersion: pending.manifest.version)
        return updated
    }

    func dismissPendingUpdate(id: String) {
        pendingUpdates.removeValue(forKey: id)
        if case .pendingReview = updateStatusByID[id] {
            updateStatusByID.removeValue(forKey: id)
        }
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

    func updateQualityPreferences(extensionID: String, streamQualityID: String?, downloadQualityID: String?) {
        guard let idx = extensions.firstIndex(where: { $0.id == extensionID }) else { return }
        let validIDs = Set(extensions[idx].manifest.effectiveQualities.map(\.id))
        let cleanStream = streamQualityID?.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDownload = downloadQualityID?.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedStream = cleanStream.flatMap { (!$0.isEmpty && validIDs.contains($0)) ? $0 : nil }
        let normalizedDownload = cleanDownload.flatMap { (!$0.isEmpty && validIDs.contains($0)) ? $0 : nil }
        extensions[idx].state.streamQualityID = normalizedStream
        extensions[idx].state.downloadQualityID = normalizedDownload
        extensions[idx].state.updatedAt = Date()
        hostStorage.setQualityPreferences(
            streamQualityID: normalizedStream,
            downloadQualityID: normalizedDownload,
            for: extensionID
        )
        saveState(for: extensions[idx])
    }

    func updateCookie(extensionID: String, cookie: String?) {
        guard let idx = extensions.firstIndex(where: { $0.id == extensionID }) else { return }
        let trimmed = cookie?.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = (trimmed?.isEmpty == false) ? trimmed : nil
        extensions[idx].state.cookie = normalized
        extensions[idx].state.updatedAt = Date()
        hostStorage.setCookie(normalized, for: extensionID)
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
        pendingUpdates.removeValue(forKey: id)
        updateStatusByID.removeValue(forKey: id)
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

    var activeLyricsExtensions: [CloudLyricsExtensionDescriptor] {
        extensions
            .filter { $0.isActive && $0.manifest.capabilities.contains(.lyrics) }
            .map { CloudLyricsExtensionDescriptor(id: $0.id, name: $0.manifest.effectiveBadgeLabel) }
    }

    var activeSearchExtensions: [CloudSearchExtensionDescriptor] {
        extensions
            .filter { $0.isActive && $0.manifest.capabilities.contains(.search) }
            .map {
                CloudSearchExtensionDescriptor(
                    id: $0.id,
                    providerID: $0.providerID,
                    name: $0.manifest.name,
                    badgeLabel: $0.manifest.effectiveBadgeLabel,
                    searchKinds: $0.manifest.effectiveSearchKinds,
                    supportsStream: $0.manifest.capabilities.contains(.stream),
                    supportsCollection: $0.manifest.capabilities.contains(.playlist) || $0.manifest.capabilities.contains(.tree),
                    supportsCookie: $0.manifest.supportsCookieAuth
                )
            }
    }

    nonisolated func search(
        extensionID: String,
        query: String,
        kind: CloudSearchKindFilter = .all,
        limit: Int = 20,
        cursor: String? = nil
    ) async throws -> CloudSearchPage {
        let entry: (manifest: ExtensionManifest, runtime: JSExtensionRuntime)? = lookupLock.withLock {
            searchRuntimesByID[extensionID]
        }
        guard let entry else {
            let name = installedName(forProviderID: "ext.\(extensionID)") ?? extensionID
            throw CloudSourceError.extensionUnavailable(name: name)
        }
        let cookie = entry.manifest.supportsCookieAuth ? hostStorage.cookie(for: extensionID) : nil
        return try await entry.runtime.search(
            query: query,
            kind: kind.rawValue,
            limit: limit,
            cursor: cursor,
            cookie: cookie
        )
    }

    nonisolated func previewCollection(extensionID: String, sourceURL: String) async throws -> CloudPlaylistResult {
        let cleanURL = sourceURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanURL.isEmpty else {
            throw CloudSourceError.invalidURL
        }
        let providerID = "ext.\(extensionID)"
        if let provider = registry.provider(forID: providerID) {
            let cookie = provider.supportsCookie ? hostStorage.cookie(for: extensionID) : nil
            return try await provider.fetchCollection(sourceURL: cleanURL, cookie: cookie)
        }
        let searchEntry: (manifest: ExtensionManifest, runtime: JSExtensionRuntime)? = lookupLock.withLock {
            searchRuntimesByID[extensionID]
        }
        guard let searchEntry else {
            let name = installedName(forProviderID: providerID) ?? extensionID
            throw CloudSourceError.extensionUnavailable(name: name)
        }
        let provider = JSExtensionCloudProvider(
            manifest: searchEntry.manifest,
            runtime: searchEntry.runtime,
            extensionCookieProvider: { [weak hostStorage] in hostStorage?.cookie(for: extensionID) }
        )
        let cookie = searchEntry.manifest.supportsCookieAuth ? hostStorage.cookie(for: extensionID) : nil
        return try await provider.fetchCollection(sourceURL: cleanURL, cookie: cookie)
    }

    nonisolated func startQRLogin(extensionID: String) async throws -> ExtensionQRLoginSession {
        let entry: (manifest: ExtensionManifest, runtime: JSExtensionRuntime)? = lookupLock.withLock {
            qrLoginRuntimesByID[extensionID]
        }
        guard let entry else {
            let name = installedName(forProviderID: "ext.\(extensionID)") ?? extensionID
            throw CloudSourceError.extensionUnavailable(name: name)
        }
        return try await entry.runtime.startQRLogin()
    }

    nonisolated func pollQRLogin(extensionID: String, sessionID: String) async throws -> ExtensionQRLoginPollResult {
        let entry: (manifest: ExtensionManifest, runtime: JSExtensionRuntime)? = lookupLock.withLock {
            qrLoginRuntimesByID[extensionID]
        }
        guard let entry else {
            let name = installedName(forProviderID: "ext.\(extensionID)") ?? extensionID
            throw CloudSourceError.extensionUnavailable(name: name)
        }
        let result = try await entry.runtime.pollQRLogin(sessionID: sessionID)
        if result.status == .confirmed, let cookie = result.cookie {
            await MainActor.run {
                self.updateCookie(extensionID: extensionID, cookie: cookie)
            }
        }
        return result
    }

    nonisolated func makeCloudLyricsService() -> CloudLyricsService {
        CloudLyricsService(transport: transport) { [weak self] extID, query in
            guard let self else { throw CloudLyricsError.providerUnavailable(extID) }
            return try await self.searchLyrics(extensionID: extID, query: query)
        }
    }

    nonisolated func searchLyrics(extensionID: String, query: CloudLyricsQuery) async throws -> [CloudLyricsCandidate] {
        let entry: (badgeLabel: String, runtime: JSExtensionRuntime)? = lookupLock.withLock {
            lyricsRuntimesByID[extensionID]
        }
        guard let entry else {
            throw CloudLyricsError.providerUnavailable(extensionID)
        }
        let specs = try await entry.runtime.searchLyrics(
            title: query.trimmedTitle,
            artist: query.trimmedArtist,
            album: query.trimmedAlbum,
            durationSec: query.durationSec
        )
        return specs.enumerated().compactMap { index, spec -> CloudLyricsCandidate? in
            let title = (spec.title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let artist = (spec.artist ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let synced = spec.syncedLyrics?.trimmingCharacters(in: .whitespacesAndNewlines)
            let plain = spec.plainLyrics?.trimmingCharacters(in: .whitespacesAndNewlines)
            let trans = spec.translatedLyrics?.trimmingCharacters(in: .whitespacesAndNewlines)
            let isInst = spec.instrumental ?? false
            let extItemID = (spec.externalId?.trimmingCharacters(in: .whitespacesAndNewlines)).flatMap { $0.isEmpty ? nil : $0 } ?? "row-\(index)"
            let candidate = CloudLyricsCandidate(
                id: "ext.\(extensionID):\(extItemID)",
                providerID: "ext.\(extensionID)",
                sourceName: entry.badgeLabel,
                title: title.isEmpty ? query.trimmedTitle : title,
                artist: artist.isEmpty ? query.trimmedArtist : artist,
                album: spec.album?.trimmingCharacters(in: .whitespacesAndNewlines),
                durationSec: spec.durationSec,
                isInstrumental: isInst,
                syncedLyrics: (synced?.isEmpty == false) ? synced : nil,
                plainLyrics: (plain?.isEmpty == false) ? plain : nil,
                translatedLyrics: (trans?.isEmpty == false) ? trans : nil
            )
            return candidate.hasUsableContent ? candidate : nil
        }
    }

    private func syncAll() {
        let map = Dictionary(uniqueKeysWithValues: extensions.map { ($0.providerID, $0.manifest.name) })
        let synced = Self.syncRegistry(
            extensions: extensions,
            registry: registry,
            transport: transport,
            hostStorage: hostStorage,
            shortTimeoutMs: shortTimeoutMs,
            longTimeoutMs: longTimeoutMs
        )
        lookupLock.withLock {
            installedNamesByProviderID = map
            lyricsRuntimesByID = synced.lyrics
            searchRuntimesByID = synced.search
            qrLoginRuntimesByID = synced.qrLogin
        }
    }

    private func filterCompatibleSettings(
        _ existing: [String: ExtensionSettingValue],
        manifest: ExtensionManifest
    ) -> [String: ExtensionSettingValue] {
        guard let specs = manifest.settings, !specs.isEmpty else { return [:] }
        let validKeys = Set(specs.map(\.key))
        return existing.filter { validKeys.contains($0.key) }
    }

    @discardableResult
    private nonisolated static func syncRegistry(
        extensions: [InstalledExtension],
        registry: CloudProviderRegistry,
        transport: any CloudHTTPTransport,
        hostStorage: ExtensionHostStorage,
        shortTimeoutMs: Int,
        longTimeoutMs: Int
    ) -> (
        lyrics: [String: (badgeLabel: String, runtime: JSExtensionRuntime)],
        search: [String: (manifest: ExtensionManifest, runtime: JSExtensionRuntime)],
        qrLogin: [String: (manifest: ExtensionManifest, runtime: JSExtensionRuntime)]
    ) {
        var providers: [any CloudSourceProvider] = []
        var lyricsRuntimes: [String: (badgeLabel: String, runtime: JSExtensionRuntime)] = [:]
        var searchRuntimes: [String: (manifest: ExtensionManifest, runtime: JSExtensionRuntime)] = [:]
        var qrLoginRuntimes: [String: (manifest: ExtensionManifest, runtime: JSExtensionRuntime)] = [:]
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
            let hasCloudSourceCap = ext.manifest.capabilities.contains {
                $0 == .playlist || $0 == .tree || $0 == .stream
            }
            if hasCloudSourceCap {
                let extID = ext.manifest.id
                providers.append(JSExtensionCloudProvider(
                    manifest: ext.manifest,
                    runtime: runtime,
                    extensionCookieProvider: { [weak hostStorage] in hostStorage?.cookie(for: extID) },
                    extensionStreamQualityProvider: { [weak hostStorage] in hostStorage?.streamQualityID(for: extID) },
                    extensionDownloadQualityProvider: { [weak hostStorage] in hostStorage?.downloadQualityID(for: extID) }
                ))
            }
            if ext.manifest.capabilities.contains(.lyrics) {
                lyricsRuntimes[ext.manifest.id] = (badgeLabel: ext.manifest.effectiveBadgeLabel, runtime: runtime)
            }
            if ext.manifest.capabilities.contains(.search) {
                searchRuntimes[ext.manifest.id] = (manifest: ext.manifest, runtime: runtime)
            }
            if ext.manifest.capabilities.contains(.qrLogin) {
                qrLoginRuntimes[ext.manifest.id] = (manifest: ext.manifest, runtime: runtime)
            }
        }
        registry.replaceExtensions(providers)
        return (lyricsRuntimes, searchRuntimes, qrLoginRuntimes)
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
            hostStorage.loadExtension(
                id: manifest.id,
                settings: state.settings,
                cookie: manifest.supportsCookieAuth ? state.cookie : nil,
                streamQualityID: state.streamQualityID,
                downloadQualityID: state.downloadQualityID
            )
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
