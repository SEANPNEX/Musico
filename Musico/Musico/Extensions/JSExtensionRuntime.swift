import CryptoKit
import Foundation
import JavaScriptCore

/// Decoded `canHandle(input)` response from a JS extension.
struct JSMatchOutcome: Codable, Equatable, Sendable {
    var matched: Bool
    var normalizedUrl: String?
    var remoteId: String?
    var kind: String?
}

/// Arbitrary JSON value used for forwarding opaque `ExtSubfolder.extra` objects across FFI.
enum JSAnyJSONValue: Codable, Equatable, Sendable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case object([String: JSAnyJSONValue])
    case array([JSAnyJSONValue])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let b = try? container.decode(Bool.self) {
            self = .bool(b)
        } else if let n = try? container.decode(Double.self) {
            self = .number(n)
        } else if let s = try? container.decode(String.self) {
            self = .string(s)
        } else if let arr = try? container.decode([JSAnyJSONValue].self) {
            self = .array(arr)
        } else if let obj = try? container.decode([String: JSAnyJSONValue].self) {
            self = .object(obj)
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unsupported JSON value")
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null:
            try container.encodeNil()
        case .bool(let b):
            try container.encode(b)
        case .number(let n):
            try container.encode(n)
        case .string(let s):
            try container.encode(s)
        case .array(let arr):
            try container.encode(arr)
        case .object(let obj):
            try container.encode(obj)
        }
    }

    func toJSONString() -> String? {
        guard self != .null else { return nil }
        guard let data = try? JSONEncoder().encode(self) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

struct JSTrackSpec: Codable, Equatable, Sendable {
    var remoteTrackId: String
    var relativePath: String?
    var title: String
    var artist: String?
    var album: String?
    var trackNumber: Int?
    var durationSec: Double?
    var artworkUrl: String?
}

struct JSSubfolderSpec: Codable, Equatable, Sendable {
    var id: String
    var name: String
    var pathSegments: [String]?
    var extra: JSAnyJSONValue?

    var extraJSONString: String? {
        extra?.toJSONString()
    }
}

struct JSCollectionPage: Codable, Equatable, Sendable {
    var remoteId: String
    var title: String
    var tracks: [JSTrackSpec]
    var subfolders: [JSSubfolderSpec]?
    var nextCursor: String?
}

struct JSFolderPage: Codable, Equatable, Sendable {
    var folderId: String
    var tracks: [JSTrackSpec]
    var subfolders: [JSSubfolderSpec]?
    var nextCursor: String?
}

struct JSStreamResolution: Codable, Equatable, Sendable {
    var url: String
    var headers: [String: String]?
    var fileExtension: String
    var mimeType: String?
    var expiresAtMs: Double?
    var fallbackUrls: [String]?
    var contentLength: Int64?
}

struct JSLyricsCandidateSpec: Codable, Equatable, Sendable {
    var externalId: String?
    var title: String?
    var artist: String?
    var album: String?
    var durationSec: Double?
    var syncedLyrics: String?
    var plainLyrics: String?
    var translatedLyrics: String?
    var instrumental: Bool?
}

private struct JSLyricsSearchEnvelope: Codable {
    var candidates: [JSLyricsCandidateSpec]?
    var results: [JSLyricsCandidateSpec]?
}

private struct JSLossyString: Codable, Equatable, Sendable {
    var value: String

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let str = try? container.decode(String.self) {
            value = str
        } else if let intVal = try? container.decode(Int64.self) {
            value = String(intVal)
        } else if let dblVal = try? container.decode(Double.self), dblVal.isFinite {
            if dblVal.rounded() == dblVal, let i = Int64(exactly: dblVal) {
                value = String(i)
            } else {
                value = String(dblVal)
            }
        } else {
            throw DecodingError.typeMismatch(String.self, .init(codingPath: decoder.codingPath, debugDescription: "Expected string or number"))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(value)
    }
}

private struct JSSearchItemSpec: Codable {
    var kind: String?
    var remoteTrackId: JSLossyString?
    var remoteId: JSLossyString?
    var title: String?
    var artist: String?
    var album: String?
    var durationSec: Double?
    var artworkUrl: String?
    var sourceUrl: String?
    var relativePath: String?
    var trackCount: Int?
}

private struct JSSearchEnvelope: Codable {
    var items: [JSSearchItemSpec]?
    var nextCursor: JSLossyString?
}

private struct JSQRLoginStartSpec: Codable {
    var sessionId: JSLossyString?
    var qrContent: String?
    var qrUrl: String?
    var expiresAtMs: Double?
    var pollIntervalMs: Double?
    var message: String?
}

private struct JSQRLoginPollSpec: Codable {
    var status: String?
    var cookie: String?
    var accountName: String?
    var message: String?
}

protocol ExtensionRuntimeHostDelegate: AnyObject, Sendable {
    func kvGet(extensionID: String, key: String) -> String?
    func kvSet(extensionID: String, key: String, value: String, ttlSeconds: Double?)
    func kvRemove(extensionID: String, key: String)
    func settingValue(extensionID: String, key: String) -> ExtensionSettingValue?
    func appendLog(extensionID: String, level: ExtensionLogEntry.Level, message: String)
}

final class InMemoryExtensionHostDelegate: ExtensionRuntimeHostDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var kv: [String: [String: ExtensionKVEntry]] = [:]
    private var settings: [String: [String: ExtensionSettingValue]] = [:]
    private var logs: [String: [ExtensionLogEntry]] = [:]

    init(settings: [String: [String: ExtensionSettingValue]] = [:]) {
        self.settings = settings
    }

    func kvGet(extensionID: String, key: String) -> String? {
        lock.withLock {
            guard let entry = kv[extensionID]?[key] else { return nil }
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            if let exp = entry.expiresAtMs, nowMs >= exp {
                kv[extensionID]?.removeValue(forKey: key)
                return nil
            }
            return entry.value
        }
    }

    func kvSet(extensionID: String, key: String, value: String, ttlSeconds: Double?) {
        lock.withLock {
            let expiresAtMs: Int64? = {
                guard let ttlSeconds, ttlSeconds.isFinite, ttlSeconds > 0 else { return nil }
                return Int64(Date().timeIntervalSince1970 * 1000 + ttlSeconds * 1000)
            }()
            var map = kv[extensionID] ?? [:]
            map[key] = ExtensionKVEntry(value: value, expiresAtMs: expiresAtMs)
            kv[extensionID] = map
        }
    }

    func kvRemove(extensionID: String, key: String) {
        lock.withLock {
            _ = kv[extensionID]?.removeValue(forKey: key)
        }
    }

    func settingValue(extensionID: String, key: String) -> ExtensionSettingValue? {
        lock.withLock { settings[extensionID]?[key] }
    }

    func appendLog(extensionID: String, level: ExtensionLogEntry.Level, message: String) {
        lock.withLock {
            var list = logs[extensionID] ?? []
            list.append(ExtensionLogEntry(
                timestampMs: Int64(Date().timeIntervalSince1970 * 1000),
                level: level,
                message: message
            ))
            if list.count > 200 {
                list.removeFirst(list.count - 200)
            }
            logs[extensionID] = list
        }
    }

    func allLogs(for extensionID: String) -> [ExtensionLogEntry] {
        lock.withLock { logs[extensionID] ?? [] }
    }
}

/// Executes Musico JS extensions using a fresh isolated `JSVirtualMachine` + `JSContext` per invocation.
final class JSExtensionRuntime: @unchecked Sendable {
    static let defaultShortTimeoutMs = 15_000
    static let defaultLongTimeoutMs = 60_000
    static let maxHTTPBodyBytes = 8 * 1024 * 1024
    static let maxInlineBase64Bytes = 1 * 1024 * 1024
    static let maxHTTPRedirectHops = 10

    let manifest: ExtensionManifest
    let scriptSource: String
    private let transport: any CloudHTTPTransport
    private weak var hostDelegate: (any ExtensionRuntimeHostDelegate)?
    private let ownedHost: InMemoryExtensionHostDelegate?
    private let shortTimeoutMs: Int
    private let longTimeoutMs: Int
    private let appVersion: String

    init(
        manifest: ExtensionManifest,
        scriptSource: String,
        transport: any CloudHTTPTransport = URLSessionCloudTransport(),
        hostDelegate: (any ExtensionRuntimeHostDelegate)? = nil,
        shortTimeoutMs: Int = JSExtensionRuntime.defaultShortTimeoutMs,
        longTimeoutMs: Int = JSExtensionRuntime.defaultLongTimeoutMs,
        appVersion: String? = nil
    ) {
        self.manifest = manifest
        self.scriptSource = scriptSource
        self.transport = transport
        if let hostDelegate {
            self.hostDelegate = hostDelegate
            self.ownedHost = nil
        } else {
            let fallback = InMemoryExtensionHostDelegate()
            self.hostDelegate = fallback
            self.ownedHost = fallback
        }
        self.shortTimeoutMs = max(20, shortTimeoutMs)
        self.longTimeoutMs = max(20, longTimeoutMs)
        self.appVersion = appVersion ?? (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.2.0")
    }

    // MARK: - Manifest Extraction (Zero-Network Throwaway Context)

    static func extractManifest(from scriptSource: String, timeoutMs: Int = 5_000) async throws -> ExtensionManifest {
        let queue = DispatchQueue(label: "com.musico.jsext.manifest.\(UUID().uuidString)", qos: .userInitiated)
        let gate = SyncExtractionGate()

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                gate.setContinuation(continuation)

                let timeoutItem = DispatchWorkItem {
                    gate.finish(with: .failure(CloudSourceError.extensionRuntimeError(
                        code: "EXTENSION_RUNTIME_ERROR",
                        message: "Timed out while reading extension manifest."
                    )))
                }
                gate.setTimeoutItem(timeoutItem)
                DispatchQueue.global(qos: .userInitiated).asyncAfter(
                    deadline: .now() + .milliseconds(max(50, timeoutMs)),
                    execute: timeoutItem
                )

                queue.async {
                    guard !gate.isFinished else { return }
                    do {
                        let manifest = try evaluateManifestOnly(scriptSource: scriptSource)
                        gate.finish(with: .success(manifest))
                    } catch {
                        gate.finish(with: .failure(error))
                    }
                }
            }
        } onCancel: {
            gate.finish(with: .failure(CancellationError()))
        }
    }

    private static func evaluateManifestOnly(scriptSource: String) throws -> ExtensionManifest {
        let vm = JSVirtualMachine()!
        let context = JSContext(virtualMachine: vm)!
        var capturedException: String?
        context.exceptionHandler = { _, exc in
            capturedException = exc?.toString()
        }

        _ = context.evaluateScript(minimalGlobalsBootstrap)
        let mod = JSValue(newObjectIn: context)!
        let exports = JSValue(newObjectIn: context)!
        mod.setObject(exports, forKeyedSubscript: "exports" as NSString)

        let wrapped = "(function(exports, module) {\n\(scriptSource)\n})"
        guard let factory = context.evaluateScript(wrapped), capturedException == nil else {
            throw ExtensionImportError.invalidPackage(capturedException ?? "Syntax error evaluating script.")
        }
        _ = factory.call(withArguments: [exports, mod])
        if let capturedException {
            throw ExtensionImportError.invalidPackage(capturedException)
        }

        context.setObject(mod, forKeyedSubscript: "__musicoTempModule" as NSString)
        let manifestJSONValue = context.evaluateScript("""
            (function() {
              var exp = __musicoTempModule && __musicoTempModule.exports;
              if (!exp || typeof exp !== 'object' || !exp.manifest || typeof exp.manifest !== 'object') {
                return null;
              }
              return JSON.stringify(exp.manifest);
            })()
        """)
        if let capturedException {
            throw ExtensionImportError.invalidPackage(capturedException)
        }
        guard let manifestJSONValue, manifestJSONValue.isString, let jsonStr = manifestJSONValue.toString() else {
            throw ExtensionImportError.invalidPackage("Single-file extension must export a valid 'manifest' object.")
        }
        let manifest = try JSONDecoder().decode(ExtensionManifest.self, from: Data(jsonStr.utf8))
        try manifest.validate()
        try validateRequiredHooks(module: mod, manifest: manifest, in: context)
        return manifest
    }

    private static func validateRequiredHooks(module: JSValue, manifest: ExtensionManifest, in context: JSContext) throws {
        context.setObject(module, forKeyedSubscript: "__musicoCheckModule" as NSString)
        let checkScript = """
            (function() {
              var exp = __musicoCheckModule.exports || {};
              return JSON.stringify({
                canHandle: typeof exp.canHandle === 'function',
                fetchCollection: typeof exp.fetchCollection === 'function',
                fetchFolder: typeof exp.fetchFolder === 'function',
                resolveStream: typeof exp.resolveStream === 'function',
                searchLyrics: typeof exp.searchLyrics === 'function',
                search: typeof exp.search === 'function',
                startQrLogin: typeof exp.startQrLogin === 'function',
                pollQrLogin: typeof exp.pollQrLogin === 'function'
              });
            })()
        """
        guard let jsonStr = context.evaluateScript(checkScript)?.toString(),
              let flags = try? JSONDecoder().decode([String: Bool].self, from: Data(jsonStr.utf8)) else {
            throw ExtensionImportError.invalidPackage("Could not inspect exported extension hooks.")
        }
        let requiresSourceHooks = manifest.capabilities.contains(where: { $0 == .playlist || $0 == .tree })
        if requiresSourceHooks && flags["canHandle"] != true {
            throw ExtensionImportError.invalidPackage("Extension must export canHandle(input).")
        }
        if (manifest.capabilities.contains(.playlist) || manifest.capabilities.contains(.tree)),
           flags["fetchCollection"] != true {
            throw ExtensionImportError.invalidPackage("Extension must export fetchCollection(ctx, req).")
        }
        if manifest.capabilities.contains(.tree), flags["fetchFolder"] != true {
            throw ExtensionImportError.invalidPackage("Tree-capable extension must export fetchFolder(ctx, req).")
        }
        if manifest.capabilities.contains(.stream), flags["resolveStream"] != true {
            throw ExtensionImportError.invalidPackage("Stream-capable extension must export resolveStream(ctx, req).")
        }
        if manifest.capabilities.contains(.lyrics), flags["searchLyrics"] != true {
            throw ExtensionImportError.invalidPackage("Lyrics-capable extension must export searchLyrics(ctx, req).")
        }
        if manifest.capabilities.contains(.search), flags["search"] != true {
            throw ExtensionImportError.invalidPackage("Search-capable extension must export search(ctx, req).")
        }
        if manifest.capabilities.contains(.qrLogin) {
            if flags["startQrLogin"] != true {
                throw ExtensionImportError.invalidPackage("QR-login extension must export startQrLogin(ctx, req).")
            }
            if flags["pollQrLogin"] != true {
                throw ExtensionImportError.invalidPackage("QR-login extension must export pollQrLogin(ctx, req).")
            }
        }
    }

    // MARK: - Extension Hooks

    func canHandle(rawText: String, extractedURL: String) -> JSMatchOutcome {
        let queue = DispatchQueue(label: "com.musico.jsext.canHandle.\(manifest.id).\(UUID().uuidString)", qos: .userInitiated)
        let semaphore = DispatchSemaphore(value: 0)
        let box = LockedResultBox<JSMatchOutcome>()

        let inputDict: [String: String] = [
            "rawText": rawText,
            "extractedUrl": extractedURL
        ]
        guard let inputData = try? JSONSerialization.data(withJSONObject: inputDict),
              let inputJSON = String(data: inputData, encoding: .utf8) else {
            return JSMatchOutcome(matched: false)
        }

        let source = scriptSource
        let id = manifest.id
        let envPlatform = Self.currentPlatform

        queue.async {
            let vm = JSVirtualMachine()!
            let context = JSContext(virtualMachine: vm)!
            var jsException: String?
            context.exceptionHandler = { _, exc in
                jsException = exc?.toString()
            }
            _ = context.evaluateScript(Self.minimalGlobalsBootstrap)
            Self.installURLBridge(in: context)
            Self.installEnv(in: context, platform: envPlatform, appVersion: self.appVersion)

            let mod = JSValue(newObjectIn: context)!
            let exports = JSValue(newObjectIn: context)!
            mod.setObject(exports, forKeyedSubscript: "exports" as NSString)

            let wrapped = "(function(exports, module) {\n\(source)\n})"
            guard let factory = context.evaluateScript(wrapped), jsException == nil else {
                box.set(JSMatchOutcome(matched: false))
                semaphore.signal()
                return
            }
            _ = factory.call(withArguments: [exports, mod])
            if jsException != nil {
                box.set(JSMatchOutcome(matched: false))
                semaphore.signal()
                return
            }

            context.setObject(mod, forKeyedSubscript: "__musicoModule" as NSString)
            _ = context.evaluateScript("var __musicoExports = __musicoModule.exports || {};")
            guard let invokeFn = context.evaluateScript("""
                (function(inputJson) {
                  try {
                    if (typeof __musicoExports.canHandle !== 'function') {
                      return JSON.stringify({ matched: false });
                    }
                    var res = __musicoExports.canHandle(JSON.parse(inputJson));
                    if (!res || typeof res !== 'object') {
                      return JSON.stringify({ matched: false });
                    }
                    return JSON.stringify({
                      matched: Boolean(res.matched),
                      normalizedUrl: res.normalizedUrl ? String(res.normalizedUrl) : null,
                      remoteId: res.remoteId ? String(res.remoteId) : null,
                      kind: res.kind ? String(res.kind) : null
                    });
                  } catch (e) {
                    return JSON.stringify({ matched: false });
                  }
                })
            """), jsException == nil else {
                box.set(JSMatchOutcome(matched: false))
                semaphore.signal()
                return
            }

            let rawResult = invokeFn.call(withArguments: [inputJSON])?.toString()
            if let rawResult,
               let decoded = try? JSONDecoder().decode(JSMatchOutcome.self, from: Data(rawResult.utf8)) {
                box.set(decoded)
            } else {
                box.set(JSMatchOutcome(matched: false))
            }
            semaphore.signal()
        }

        let waitResult = semaphore.wait(timeout: .now() + .milliseconds(shortTimeoutMs))
        if waitResult == .timedOut {
            hostDelegate?.appendLog(extensionID: id, level: .warn, message: "canHandle timed out after \(shortTimeoutMs)ms")
            return JSMatchOutcome(matched: false)
        }
        return box.get() ?? JSMatchOutcome(matched: false)
    }

    func fetchCollection(url: String, cookie: String?, cursor: String?) async throws -> JSCollectionPage {
        var req: [String: Any] = ["url": url]
        if let cookie { req["cookie"] = cookie }
        if let cursor { req["cursor"] = cursor }
        let json = try await invokeAsyncHook(name: "fetchCollection", requestObject: req, timeoutMs: longTimeoutMs)
        return try JSONDecoder().decode(JSCollectionPage.self, from: Data(json.utf8))
    }

    func fetchFolder(
        folderID: String,
        pathSegments: [String],
        depth: Int,
        cookie: String?,
        cursor: String?,
        extraJSON: String?
    ) async throws -> JSFolderPage {
        var req: [String: Any] = [
            "folderId": folderID,
            "pathSegments": pathSegments,
            "depth": depth
        ]
        if let cookie { req["cookie"] = cookie }
        if let cursor { req["cursor"] = cursor }
        if let extraJSON,
           let extraData = extraJSON.data(using: .utf8),
           let extraObj = try? JSONSerialization.jsonObject(with: extraData) {
            req["extra"] = extraObj
        }
        let json = try await invokeAsyncHook(name: "fetchFolder", requestObject: req, timeoutMs: longTimeoutMs)
        return try JSONDecoder().decode(JSFolderPage.self, from: Data(json.utf8))
    }

    func resolveStream(
        remoteTrackID: String,
        relativePath: String?,
        cookie: String?,
        forceRefresh: Bool
    ) async throws -> JSStreamResolution {
        var req: [String: Any] = [
            "remoteTrackId": remoteTrackID,
            "forceRefresh": forceRefresh
        ]
        if let relativePath { req["relativePath"] = relativePath }
        if let cookie { req["cookie"] = cookie }
        let json = try await invokeAsyncHook(name: "resolveStream", requestObject: req, timeoutMs: shortTimeoutMs)
        let resolution = try JSONDecoder().decode(JSStreamResolution.self, from: Data(json.utf8))

        // Validate primary and fallback stream URLs against permissions.hosts before returning.
        try assertAllowedURL(resolution.url)
        for fallback in resolution.fallbackUrls ?? [] {
            try assertAllowedURL(fallback)
        }
        return resolution
    }

    func searchLyrics(
        title: String,
        artist: String,
        album: String?,
        durationSec: Double?
    ) async throws -> [JSLyricsCandidateSpec] {
        var req: [String: Any] = [
            "title": title,
            "artist": artist
        ]
        if let album, !album.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            req["album"] = album
        }
        if let durationSec, durationSec.isFinite, durationSec > 0 {
            req["durationSec"] = durationSec
        }
        let json = try await invokeAsyncHook(name: "searchLyrics", requestObject: req, timeoutMs: shortTimeoutMs)
        let trimmed = json.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "null" {
            return []
        }
        let data = Data(trimmed.utf8)
        if trimmed.hasPrefix("[") {
            return try JSONDecoder().decode([JSLyricsCandidateSpec].self, from: data)
        }
        let envelope = try JSONDecoder().decode(JSLyricsSearchEnvelope.self, from: data)
        return envelope.candidates ?? envelope.results ?? []
    }

    func search(
        query: String,
        kind: String = "all",
        limit: Int = 20,
        cursor: String? = nil,
        cookie: String? = nil
    ) async throws -> CloudSearchPage {
        var req: [String: Any] = [
            "query": query,
            "kind": kind,
            "limit": max(1, limit)
        ]
        if let cursor, !cursor.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            req["cursor"] = cursor
        }
        if let cookie, !cookie.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            req["cookie"] = cookie
        }

        let json = try await invokeAsyncHook(name: "search", requestObject: req, timeoutMs: shortTimeoutMs)
        let trimmed = json.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "null" {
            return CloudSearchPage(items: [], nextCursor: nil)
        }
        let data = Data(trimmed.utf8)
        let envelope = try JSONDecoder().decode(JSSearchEnvelope.self, from: data)
        let extID = manifest.id
        let items: [CloudSearchResult] = (envelope.items ?? []).compactMap { spec in
            guard let kindStr = spec.kind?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
                  let kind = ExtensionSearchKind(rawValue: kindStr) else {
                return nil
            }
            let title = (spec.title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty else { return nil }

            let cleanTrackID = Self.trimmedNonEmpty(spec.remoteTrackId?.value)
            let cleanRemoteID = Self.trimmedNonEmpty(spec.remoteId?.value)
            switch kind {
            case .track:
                guard cleanTrackID != nil else { return nil }
            case .playlist, .album:
                guard cleanRemoteID != nil else { return nil }
            }

            return CloudSearchResult(
                extensionID: extID,
                kind: kind,
                remoteTrackID: cleanTrackID,
                remoteId: cleanRemoteID,
                title: title,
                artist: Self.trimmedNonEmpty(spec.artist),
                album: Self.trimmedNonEmpty(spec.album),
                durationSec: spec.durationSec.flatMap { ($0.isFinite && $0 > 0) ? $0 : nil },
                artworkUrl: Self.trimmedNonEmpty(spec.artworkUrl),
                sourceUrl: Self.trimmedNonEmpty(spec.sourceUrl),
                relativePath: Self.trimmedNonEmpty(spec.relativePath),
                trackCount: spec.trackCount.flatMap { $0 >= 0 ? $0 : nil }
            )
        }
        let nextCursor = Self.trimmedNonEmpty(envelope.nextCursor?.value)
        return CloudSearchPage(items: items, nextCursor: nextCursor)
    }

    func startQRLogin() async throws -> ExtensionQRLoginSession {
        let json = try await invokeAsyncHook(name: "startQrLogin", requestObject: [:], timeoutMs: shortTimeoutMs)
        let trimmed = json.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != "null" else {
            throw CloudSourceError.extensionRuntimeError(
                code: "EXTENSION_RUNTIME_ERROR",
                message: "startQrLogin must return an object."
            )
        }
        let spec = try JSONDecoder().decode(JSQRLoginStartSpec.self, from: Data(trimmed.utf8))
        guard let sessionId = Self.trimmedNonEmpty(spec.sessionId?.value) else {
            throw CloudSourceError.extensionRuntimeError(
                code: "EXTENSION_RUNTIME_ERROR",
                message: "startQrLogin requires a non-empty sessionId."
            )
        }
        guard let qrContent = Self.trimmedNonEmpty(spec.qrContent) ?? Self.trimmedNonEmpty(spec.qrUrl),
              qrContent.utf8.count <= 512 else {
            throw CloudSourceError.extensionRuntimeError(
                code: "EXTENSION_RUNTIME_ERROR",
                message: "startQrLogin requires a non-empty qrContent (<= 512 bytes)."
            )
        }
        let rawInterval = spec.pollIntervalMs.flatMap { $0.isFinite ? Int($0.rounded()) : nil } ?? 2_000
        let pollIntervalMs = min(10_000, max(1_000, rawInterval))
        let expiresAtMs = spec.expiresAtMs.flatMap { ($0.isFinite && $0 > 0) ? $0 : nil }
        return ExtensionQRLoginSession(
            sessionId: sessionId,
            qrContent: qrContent,
            expiresAtMs: expiresAtMs,
            pollIntervalMs: pollIntervalMs,
            message: Self.trimmedNonEmpty(spec.message)
        )
    }

    func pollQRLogin(sessionID: String) async throws -> ExtensionQRLoginPollResult {
        let cleanSessionID = sessionID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanSessionID.isEmpty else {
            throw CloudSourceError.extensionRuntimeError(
                code: "EXTENSION_RUNTIME_ERROR",
                message: "pollQrLogin requires a non-empty sessionId."
            )
        }
        let json = try await invokeAsyncHook(
            name: "pollQrLogin",
            requestObject: ["sessionId": cleanSessionID],
            timeoutMs: shortTimeoutMs
        )
        let trimmed = json.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != "null" else {
            throw CloudSourceError.extensionRuntimeError(
                code: "EXTENSION_RUNTIME_ERROR",
                message: "pollQrLogin must return an object."
            )
        }
        let spec = try JSONDecoder().decode(JSQRLoginPollSpec.self, from: Data(trimmed.utf8))
        let statusRaw = spec.status?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        guard let status = ExtensionQRLoginStatus(rawValue: statusRaw) else {
            throw CloudSourceError.extensionRuntimeError(
                code: "EXTENSION_RUNTIME_ERROR",
                message: "Unsupported QR login status '\(spec.status ?? "")'."
            )
        }
        let cookie = Self.trimmedNonEmpty(spec.cookie)
        if status == .confirmed, cookie == nil {
            throw CloudSourceError.extensionRuntimeError(
                code: "EXTENSION_RUNTIME_ERROR",
                message: "pollQrLogin returned confirmed without a cookie."
            )
        }
        return ExtensionQRLoginPollResult(
            status: status,
            cookie: cookie,
            accountName: Self.trimmedNonEmpty(spec.accountName),
            message: Self.trimmedNonEmpty(spec.message)
        )
    }

    private static func trimmedNonEmpty(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }

    // MARK: - Async Hook Execution Engine

    private func invokeAsyncHook(
        name: String,
        requestObject: [String: Any],
        timeoutMs: Int
    ) async throws -> String {
        let reqData = try JSONSerialization.data(withJSONObject: requestObject)
        let reqJSON = String(data: reqData, encoding: .utf8) ?? "{}"
        let session = AsyncInvocationSession(
            manifest: manifest,
            scriptSource: scriptSource,
            transport: transport,
            hostDelegate: hostDelegate,
            appVersion: appVersion,
            platform: Self.currentPlatform
        )

        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                session.start(
                    hookName: name,
                    requestJSON: reqJSON,
                    timeoutMs: timeoutMs,
                    continuation: continuation
                )
            }
        } onCancel: {
            session.cancel(with: CancellationError())
        }
    }

    private func effectiveAllowedHosts() -> [String] {
        manifest.effectiveAllowedHosts { key in
            self.hostDelegate?.settingValue(extensionID: self.manifest.id, key: key)
        }
    }

    private func assertAllowedURL(_ urlString: String) throws {
        let allowedHosts = effectiveAllowedHosts()
        guard HostAllowlistMatcher.isAllowed(urlString: urlString, patterns: allowedHosts) else {
            let host = HostAllowlistMatcher.extractedHost(from: urlString)
            throw CloudSourceError.permissionDenied(host: host)
        }
    }

    static var currentPlatform: String {
        #if targetEnvironment(macCatalyst)
        return "macos"
        #else
        return "ios"
        #endif
    }

    static func mapExtensionError(code: String, message: String) -> CloudSourceError {
        switch code {
        case "INVALID_URL":
            return .invalidURL
        case "EMPTY_COLLECTION":
            return .emptyPlaylist
        case "NO_PLAYABLE_STREAM":
            return .noPlayableStream
        case "RATE_LIMITED":
            return .rateLimited
        case "NETWORK_ERROR":
            return .networkError(message)
        case "PERMISSION_DENIED":
            let hostPrefix = "Host blocked by permissions.hosts: "
            let host = message.hasPrefix(hostPrefix) ? String(message.dropFirst(hostPrefix.count)) : message
            return .permissionDenied(host: HostAllowlistMatcher.extractedHost(from: host))
        default:
            return .extensionRuntimeError(code: code, message: message)
        }
    }

    private static func installEnv(in context: JSContext, platform: String, appVersion: String) {
        let envDict: [String: Any] = [
            "platform": platform,
            "appVersion": appVersion,
            "apiVersion": 1
        ]
        if let data = try? JSONSerialization.data(withJSONObject: envDict),
           let json = String(data: data, encoding: .utf8) {
            _ = context.evaluateScript("globalThis.__musicoEnv = \(json);")
        }
    }

    fileprivate static func installURLBridge(in context: JSContext) {
        let parseURLBlock: @convention(block) (String, String?) -> String? = { rawURL, baseURL in
            let parsed: URL?
            if let baseURL, !baseURL.isEmpty, let base = URL(string: baseURL) {
                parsed = URL(string: rawURL, relativeTo: base)?.absoluteURL
            } else {
                parsed = URL(string: rawURL)
            }
            guard let parsed, let comps = URLComponents(url: parsed, resolvingAgainstBaseURL: true) else {
                return nil
            }
            let queryPairs: [[String]] = (comps.queryItems ?? []).map { [$0.name, $0.value ?? ""] }
            let dict: [String: Any] = [
                "href": parsed.absoluteString,
                "protocol": (comps.scheme ?? "") + (comps.scheme != nil ? ":" : ""),
                "hostname": comps.host ?? "",
                "port": comps.port.map(String.init) ?? "",
                "pathname": comps.path.isEmpty ? "/" : comps.path,
                "search": comps.percentEncodedQuery.map { "?\($0)" } ?? "",
                "hash": comps.percentEncodedFragment.map { "#\($0)" } ?? "",
                "queryPairs": queryPairs
            ]
            guard let data = try? JSONSerialization.data(withJSONObject: dict) else { return nil }
            return String(data: data, encoding: .utf8)
        }
        context.setObject(parseURLBlock, forKeyedSubscript: "__nativeParseURL" as NSString)
        _ = context.evaluateScript(urlPolyfillBootstrap)
    }

    fileprivate static let minimalGlobalsBootstrap = """
        if (typeof globalThis === 'undefined') {
          var globalThis = this;
        }
    """

    fileprivate static let urlPolyfillBootstrap = """
        (function() {
          function URLSearchParamsPolyfill(init) {
            this._pairs = [];
            if (typeof init === 'string') {
              var s = init.charAt(0) === '?' ? init.slice(1) : init;
              if (s) {
                var parts = s.split('&');
                for (var i = 0; i < parts.length; i++) {
                  var eq = parts[i].indexOf('=');
                  var k = eq >= 0 ? parts[i].slice(0, eq) : parts[i];
                  var v = eq >= 0 ? parts[i].slice(eq + 1) : '';
                  this._pairs.push([
                    decodeURIComponent(k.replace(/\\+/g, ' ')),
                    decodeURIComponent(v.replace(/\\+/g, ' '))
                  ]);
                }
              }
            } else if (Array.isArray(init)) {
              for (var j = 0; j < init.length; j++) {
                this._pairs.push([String(init[j][0]), String(init[j][1])]);
              }
            } else if (init && typeof init === 'object') {
              var keys = Object.keys(init);
              for (var kIdx = 0; kIdx < keys.length; kIdx++) {
                this._pairs.push([keys[kIdx], String(init[keys[kIdx]])]);
              }
            }
          }
          URLSearchParamsPolyfill.prototype.get = function(name) {
            for (var i = 0; i < this._pairs.length; i++) {
              if (this._pairs[i][0] === name) return this._pairs[i][1];
            }
            return null;
          };
          URLSearchParamsPolyfill.prototype.getAll = function(name) {
            var out = [];
            for (var i = 0; i < this._pairs.length; i++) {
              if (this._pairs[i][0] === name) out.push(this._pairs[i][1]);
            }
            return out;
          };
          URLSearchParamsPolyfill.prototype.has = function(name) {
            return this.get(name) !== null;
          };
          URLSearchParamsPolyfill.prototype.set = function(name, value) {
            var updated = false;
            var next = [];
            for (var i = 0; i < this._pairs.length; i++) {
              if (this._pairs[i][0] === name) {
                if (!updated) {
                  next.push([name, String(value)]);
                  updated = true;
                }
              } else {
                next.push(this._pairs[i]);
              }
            }
            if (!updated) next.push([name, String(value)]);
            this._pairs = next;
          };
          URLSearchParamsPolyfill.prototype.append = function(name, value) {
            this._pairs.push([String(name), String(value)]);
          };
          URLSearchParamsPolyfill.prototype.toString = function() {
            return this._pairs.map(function(p) {
              return encodeURIComponent(p[0]) + '=' + encodeURIComponent(p[1]);
            }).join('&');
          };

          function URLPolyfill(input, base) {
            var json = __nativeParseURL(String(input), base ? String(base) : '');
            if (!json) throw new TypeError('Invalid URL: ' + String(input));
            var parsed = JSON.parse(json);
            this.href = parsed.href;
            this.protocol = parsed.protocol;
            this.hostname = parsed.hostname;
            this.port = parsed.port;
            this.host = parsed.port ? (parsed.hostname + ':' + parsed.port) : parsed.hostname;
            this.pathname = parsed.pathname;
            this.search = parsed.search;
            this.hash = parsed.hash;
            this.origin = parsed.protocol + '//' + this.host;
            this.searchParams = new URLSearchParamsPolyfill(parsed.queryPairs || []);
          }
          URLPolyfill.prototype.toString = function() {
            return this.href;
          };

          if (typeof globalThis.URLSearchParams === 'undefined') {
            globalThis.URLSearchParams = URLSearchParamsPolyfill;
          }
          if (typeof globalThis.URL === 'undefined') {
            globalThis.URL = URLPolyfill;
          }
        })();
    """
}

// MARK: - Per-Invocation Execution Session

private final class AsyncInvocationSession: @unchecked Sendable {
    private let manifest: ExtensionManifest
    private let scriptSource: String
    private let transport: any CloudHTTPTransport
    private weak var hostDelegate: (any ExtensionRuntimeHostDelegate)?
    private let appVersion: String
    private let platform: String
    private let queue: DispatchQueue

    private let lock = NSLock()
    private var finished = false
    private var continuation: CheckedContinuation<String, Error>?
    private var timeoutWorkItem: DispatchWorkItem?
    private var httpTasks: [Int: Task<Void, Never>] = [:]
    private var timerItems: [Int: DispatchWorkItem] = [:]

    // Only accessed on `queue`
    private var context: JSContext?
    private var virtualMachine: JSVirtualMachine?

    init(
        manifest: ExtensionManifest,
        scriptSource: String,
        transport: any CloudHTTPTransport,
        hostDelegate: (any ExtensionRuntimeHostDelegate)?,
        appVersion: String,
        platform: String
    ) {
        self.manifest = manifest
        self.scriptSource = scriptSource
        self.transport = transport
        self.hostDelegate = hostDelegate
        self.appVersion = appVersion
        self.platform = platform
        self.queue = DispatchQueue(label: "com.musico.jsext.exec.\(manifest.id).\(UUID().uuidString)", qos: .userInitiated)
    }

    func start(
        hookName: String,
        requestJSON: String,
        timeoutMs: Int,
        continuation: CheckedContinuation<String, Error>
    ) {
        lock.withLock {
            self.continuation = continuation
        }

        let timeoutItem = DispatchWorkItem { [weak self] in
            self?.finish(with: .failure(CloudSourceError.extensionRuntimeError(
                code: "EXTENSION_RUNTIME_ERROR",
                message: "Execution timed out after \(timeoutMs)ms"
            )))
        }
        lock.withLock {
            self.timeoutWorkItem = timeoutItem
        }
        DispatchQueue.global(qos: .userInitiated).asyncAfter(
            deadline: .now() + .milliseconds(timeoutMs),
            execute: timeoutItem
        )

        queue.async { [weak self] in
            self?.bootAndInvoke(hookName: hookName, requestJSON: requestJSON)
        }
    }

    func cancel(with error: Error) {
        finish(with: .failure(error))
    }

    private func isSessionFinished() -> Bool {
        lock.withLock { finished }
    }

    private func finish(with result: Result<String, Error>) {
        let cont: CheckedContinuation<String, Error>? = lock.withLock {
            guard !finished else { return nil }
            finished = true
            timeoutWorkItem?.cancel()
            timeoutWorkItem = nil
            for (_, task) in httpTasks {
                task.cancel()
            }
            httpTasks.removeAll()
            for (_, item) in timerItems {
                item.cancel()
            }
            timerItems.removeAll()
            let c = continuation
            continuation = nil
            return c
        }

        guard let cont else { return }
        cont.resume(with: result)

        queue.async { [weak self] in
            self?.cleanupContextOnQueue()
        }
    }

    private func cleanupContextOnQueue() {
        context?.exceptionHandler = nil
        context = nil
        virtualMachine = nil
    }

    private func bootAndInvoke(hookName: String, requestJSON: String) {
        guard !isSessionFinished() else { return }

        let vm = JSVirtualMachine()!
        let ctx = JSContext(virtualMachine: vm)!
        self.virtualMachine = vm
        self.context = ctx

        var uncaughtError: JSValue?
        ctx.exceptionHandler = { _, exc in
            uncaughtError = exc
        }

        _ = ctx.evaluateScript(JSExtensionRuntime.minimalGlobalsBootstrap)
        JSExtensionRuntime.installURLBridge(in: ctx)
        installHostCallbacks(in: ctx)
        _ = ctx.evaluateScript(hostBootstrapScript)

        if let exc = uncaughtError {
            failFromJSException(exc)
            return
        }

        let mod = JSValue(newObjectIn: ctx)!
        let exports = JSValue(newObjectIn: ctx)!
        mod.setObject(exports, forKeyedSubscript: "exports" as NSString)

        let wrapped = "(function(exports, module) {\n\(scriptSource)\n})"
        guard let factory = ctx.evaluateScript(wrapped) else {
            if let exc = uncaughtError {
                failFromJSException(exc)
            } else {
                finish(with: .failure(CloudSourceError.extensionRuntimeError(
                    code: "EXTENSION_RUNTIME_ERROR",
                    message: "Failed to parse extension script."
                )))
            }
            return
        }
        if let exc = uncaughtError {
            failFromJSException(exc)
            return
        }

        _ = factory.call(withArguments: [exports, mod])
        if let exc = uncaughtError {
            failFromJSException(exc)
            return
        }

        ctx.setObject(mod, forKeyedSubscript: "__musicoModule" as NSString)
        _ = ctx.evaluateScript("var __musicoExports = __musicoModule.exports || {};")
        if let exc = uncaughtError {
            failFromJSException(exc)
            return
        }

        guard let invoker = ctx.objectForKeyedSubscript("__musicoInvokeHook") else {
            finish(with: .failure(CloudSourceError.extensionRuntimeError(
                code: "EXTENSION_RUNTIME_ERROR",
                message: "Missing JS hook dispatcher."
            )))
            return
        }
        _ = invoker.call(withArguments: [hookName, requestJSON])
        if let exc = uncaughtError {
            failFromJSException(exc)
        }
    }

    private func failFromJSException(_ exception: JSValue) {
        let code = exception.objectForKeyedSubscript("code")?.toString()
        let msg = exception.objectForKeyedSubscript("message")?.toString() ?? exception.toString() ?? "JS execution failed"
        let mapped = JSExtensionRuntime.mapExtensionError(
            code: (code?.isEmpty == false && code != "undefined") ? code! : "EXTENSION_RUNTIME_ERROR",
            message: msg
        )
        finish(with: .failure(mapped))
    }

    private func installHostCallbacks(in ctx: JSContext) {
        let extID = manifest.id
        let weakSelf = self

        let completeBlock: @convention(block) (Bool, String) -> Void = { [weak weakSelf] ok, json in
            guard let self = weakSelf, !self.isSessionFinished() else { return }
            if ok {
                self.finish(with: .success(json))
            } else {
                let decoded = (try? JSONDecoder().decode(ErrorEnvelope.self, from: Data(json.utf8)))
                    ?? ErrorEnvelope(code: "EXTENSION_RUNTIME_ERROR", message: json)
                let err = JSExtensionRuntime.mapExtensionError(code: decoded.code, message: decoded.message)
                self.finish(with: .failure(err))
            }
        }
        ctx.setObject(completeBlock, forKeyedSubscript: "__nativeComplete" as NSString)

        let httpBlock: @convention(block) (Int, String) -> Void = { [weak weakSelf] reqID, reqJSON in
            guard let self = weakSelf, !self.isSessionFinished() else { return }
            let task = Task { [weak self] in
                guard let self else { return }
                await self.performHTTPBridge(requestID: reqID, requestJSON: reqJSON)
            }
            self.lock.withLock {
                if self.finished {
                    task.cancel()
                } else {
                    self.httpTasks[reqID] = task
                }
            }
        }
        ctx.setObject(httpBlock, forKeyedSubscript: "__nativeHttpRequest" as NSString)

        let timerBlock: @convention(block) (Int, Double) -> Void = { [weak weakSelf] timerID, delayMs in
            guard let self = weakSelf, !self.isSessionFinished() else { return }
            let clamped = max(0, min(Int(delayMs.isFinite ? delayMs : 0), 120_000))
            let item = DispatchWorkItem { [weak self] in
                guard let self, !self.isSessionFinished() else { return }
                self.lock.withLock {
                    _ = self.timerItems.removeValue(forKey: timerID)
                }
                _ = self.context?.objectForKeyedSubscript("__musicoFireTimer")?.call(withArguments: [timerID])
            }
            self.lock.withLock {
                if !self.finished {
                    self.timerItems[timerID] = item
                }
            }
            self.queue.asyncAfter(deadline: .now() + .milliseconds(clamped), execute: item)
        }
        ctx.setObject(timerBlock, forKeyedSubscript: "__nativeScheduleTimer" as NSString)

        let cancelTimerBlock: @convention(block) (Int) -> Void = { [weak weakSelf] timerID in
            guard let self = weakSelf else { return }
            let item = self.lock.withLock { self.timerItems.removeValue(forKey: timerID) }
            item?.cancel()
        }
        ctx.setObject(cancelTimerBlock, forKeyedSubscript: "__nativeCancelTimer" as NSString)

        let cryptoBlock: @convention(block) (String, String, String?) -> String = { op, arg1, arg2 in
            switch op {
            case "md5":
                return Insecure.MD5.hash(data: Data(arg1.utf8)).map { String(format: "%02x", $0) }.joined()
            case "sha1":
                return Insecure.SHA1.hash(data: Data(arg1.utf8)).map { String(format: "%02x", $0) }.joined()
            case "sha256":
                return SHA256.hash(data: Data(arg1.utf8)).map { String(format: "%02x", $0) }.joined()
            case "hmacSha256":
                let key = SymmetricKey(data: Data(arg1.utf8))
                let mac = HMAC<SHA256>.authenticationCode(for: Data((arg2 ?? "").utf8), using: key)
                return mac.map { String(format: "%02x", $0) }.joined()
            case "base64Encode":
                return Data(arg1.utf8).base64EncodedString()
            case "base64Decode":
                if let decoded = Self.decodeBase64Data(arg1) {
                    return String(data: decoded, encoding: .utf8) ?? String(decoding: decoded, as: UTF8.self)
                }
                return ""
            default:
                return ""
            }
        }
        ctx.setObject(cryptoBlock, forKeyedSubscript: "__nativeCrypto" as NSString)

        let kvGetBlock: @convention(block) (String) -> String? = { [weak weakSelf] key in
            weakSelf?.hostDelegate?.kvGet(extensionID: extID, key: key)
        }
        ctx.setObject(kvGetBlock, forKeyedSubscript: "__nativeKVGet" as NSString)

        let kvSetBlock: @convention(block) (String, String, JSValue?) -> Void = { [weak weakSelf] key, val, ttlVal in
            let ttl: Double? = (ttlVal != nil && ttlVal!.isNumber) ? ttlVal!.toDouble() : nil
            weakSelf?.hostDelegate?.kvSet(extensionID: extID, key: key, value: val, ttlSeconds: ttl)
        }
        ctx.setObject(kvSetBlock, forKeyedSubscript: "__nativeKVSet" as NSString)

        let kvRemoveBlock: @convention(block) (String) -> Void = { [weak weakSelf] key in
            weakSelf?.hostDelegate?.kvRemove(extensionID: extID, key: key)
        }
        ctx.setObject(kvRemoveBlock, forKeyedSubscript: "__nativeKVRemove" as NSString)

        let settingsGetBlock: @convention(block) (String) -> String = { [weak weakSelf] key in
            guard let self = weakSelf else { return "null" }
            let val = self.hostDelegate?.settingValue(extensionID: extID, key: key)
                ?? self.manifest.defaultSettingValue(for: key)
            guard let val else { return "null" }
            if let data = try? JSONSerialization.data(withJSONObject: [val.jsonPrimitive]),
               let json = String(data: data, encoding: .utf8) {
                return json
            }
            return "null"
        }
        ctx.setObject(settingsGetBlock, forKeyedSubscript: "__nativeSettingsGetJSON" as NSString)

        let logBlock: @convention(block) (String, String) -> Void = { [weak weakSelf] levelRaw, message in
            let level = ExtensionLogEntry.Level(rawValue: levelRaw) ?? .info
            weakSelf?.hostDelegate?.appendLog(extensionID: extID, level: level, message: message)
        }
        ctx.setObject(logBlock, forKeyedSubscript: "__nativeLog" as NSString)

        let envDict: [String: Any] = [
            "platform": platform,
            "appVersion": appVersion,
            "apiVersion": 1
        ]
        if let envData = try? JSONSerialization.data(withJSONObject: envDict),
           let envJSON = String(data: envData, encoding: .utf8) {
            _ = ctx.evaluateScript("globalThis.__musicoEnv = \(envJSON);")
        }
    }

    private func performHTTPBridge(requestID: Int, requestJSON: String) async {
        defer {
            _ = lock.withLock { httpTasks.removeValue(forKey: requestID) }
        }
        guard !isSessionFinished() else { return }

        do {
            let responseEnvelope = try await executeHTTPRequest(requestJSON: requestJSON)
            resolveHTTPOnQueue(requestID: requestID, ok: true, envelopeJSON: responseEnvelope)
        } catch is CancellationError {
            return
        } catch let cloudErr as CloudSourceError {
            let code = cloudErr.extensionErrorCode ?? "NETWORK_ERROR"
            let message = cloudErr.localizedDescription
            let errEnvelope = encodeErrorEnvelope(code: code, message: message)
            resolveHTTPOnQueue(requestID: requestID, ok: false, envelopeJSON: errEnvelope)
        } catch {
            let errEnvelope = encodeErrorEnvelope(code: "NETWORK_ERROR", message: error.localizedDescription)
            resolveHTTPOnQueue(requestID: requestID, ok: false, envelopeJSON: errEnvelope)
        }
    }

    private func resolveHTTPOnQueue(requestID: Int, ok: Bool, envelopeJSON: String) {
        queue.async { [weak self] in
            guard let self, !self.isSessionFinished(), let ctx = self.context else { return }
            _ = ctx.objectForKeyedSubscript("__musicoResolveHttp")?.call(withArguments: [requestID, ok, envelopeJSON])
        }
    }

    private func effectiveAllowedHosts() -> [String] {
        manifest.effectiveAllowedHosts { key in
            self.hostDelegate?.settingValue(extensionID: self.manifest.id, key: key)
        }
    }

    private func executeHTTPRequest(requestJSON: String) async throws -> String {
        guard let reqData = requestJSON.data(using: .utf8),
              let reqObj = try JSONSerialization.jsonObject(with: reqData) as? [String: Any] else {
            throw CloudSourceError.extensionRuntimeError(code: "NETWORK_ERROR", message: "Invalid HTTP request object.")
        }

        let rawMethod = (reqObj["method"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let method = (rawMethod?.isEmpty == false ? rawMethod! : "GET").uppercased()
        let urlString = (reqObj["url"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !urlString.isEmpty, let initialURL = URL(string: urlString) else {
            throw CloudSourceError.permissionDenied(host: HostAllowlistMatcher.extractedHost(from: urlString))
        }

        let followRedirects = (reqObj["followRedirects"] as? Bool) ?? true
        let returnBytes = (reqObj["returnBytes"] as? Bool) ?? false
        let rawTimeoutMs = (reqObj["timeoutMs"] as? NSNumber)?.doubleValue ?? 15_000
        let clampedTimeoutMs = max(100, min(rawTimeoutMs.isFinite ? rawTimeoutMs : 15_000, 60_000))
        let bodyString = reqObj["body"] as? String

        var currentHeaders: [String: String] = [:]
        if let rawHeaders = reqObj["headers"] as? [String: Any] {
            for (k, v) in rawHeaders {
                if v is NSNull { continue }
                currentHeaders[k] = String(describing: v)
            }
        }

        var currentURL = initialURL
        var currentMethod = method
        var currentBody: Data? = bodyString.map { Data($0.utf8) }

        let allowedHosts = effectiveAllowedHosts()
        for hop in 0...JSExtensionRuntime.maxHTTPRedirectHops {
            try Task.checkCancellation()
            guard HostAllowlistMatcher.isAllowed(url: currentURL, patterns: allowedHosts) else {
                throw CloudSourceError.permissionDenied(host: HostAllowlistMatcher.extractedHost(from: currentURL.absoluteString))
            }

            var urlRequest = URLRequest(url: currentURL)
            urlRequest.httpMethod = hop == 0 ? method : currentMethod
            urlRequest.httpShouldHandleCookies = false
            urlRequest.timeoutInterval = clampedTimeoutMs / 1000.0
            for (k, v) in currentHeaders {
                urlRequest.setValue(v, forHTTPHeaderField: k)
            }
            urlRequest.httpBody = currentBody

            let response = try await transport.rawRequest(urlRequest)
            try Task.checkCancellation()

            var lowerHeaders: [String: String] = [:]
            for (k, v) in response.headers {
                lowerHeaders[k.lowercased()] = v
            }

            if followRedirects,
               [301, 302, 303, 307, 308].contains(response.statusCode),
               let location = lowerHeaders["location"]?.trimmingCharacters(in: .whitespacesAndNewlines),
               !location.isEmpty {
                guard hop < JSExtensionRuntime.maxHTTPRedirectHops else {
                    throw CloudSourceError.networkError("Too many HTTP redirects")
                }
                guard let nextURL = URL(string: location, relativeTo: currentURL)?.absoluteURL else {
                    throw CloudSourceError.networkError("Invalid redirect Location URL")
                }
                guard HostAllowlistMatcher.isAllowed(url: nextURL, patterns: allowedHosts) else {
                    throw CloudSourceError.permissionDenied(host: HostAllowlistMatcher.extractedHost(from: nextURL.absoluteString))
                }

                let prevHost = HostAllowlistMatcher.normalizeHost(currentURL.host ?? "")
                let nextHost = HostAllowlistMatcher.normalizeHost(nextURL.host ?? "")
                if prevHost != nextHost {
                    currentHeaders = currentHeaders.filter { key, _ in
                        let lower = key.lowercased()
                        return lower != "cookie" && lower != "authorization"
                    }
                }
                if [301, 302, 303].contains(response.statusCode) {
                    currentMethod = "GET"
                    currentBody = nil
                }
                currentURL = nextURL
                continue
            }

            let capped = response.data.prefix(JSExtensionRuntime.maxHTTPBodyBytes)
            let text = String(data: capped, encoding: .utf8) ?? String(decoding: capped, as: UTF8.self)
            let effectiveFinalURL = response.finalURL ?? currentURL
            let parsedSetCookies = HTTPCookie
                .cookies(withResponseHeaderFields: response.headers, for: effectiveFinalURL)
                .map { "\($0.name)=\($0.value)" }
            var out: [String: Any] = [
                "status": response.statusCode,
                "finalUrl": effectiveFinalURL.absoluteString,
                "headers": lowerHeaders,
                "text": text
            ]
            if !parsedSetCookies.isEmpty {
                out["setCookies"] = parsedSetCookies
            }
            if returnBytes || capped.count <= JSExtensionRuntime.maxInlineBase64Bytes {
                out["bytesBase64"] = capped.base64EncodedString()
            }
            let outData = try JSONSerialization.data(withJSONObject: out)
            return String(data: outData, encoding: .utf8) ?? "{}"
        }

        throw CloudSourceError.networkError("Too many HTTP redirects")
    }

    private func encodeErrorEnvelope(code: String, message: String) -> String {
        let env = ErrorEnvelope(code: code, message: message)
        if let data = try? JSONEncoder().encode(env), let str = String(data: data, encoding: .utf8) {
            return str
        }
        return "{\"code\":\"NETWORK_ERROR\",\"message\":\"Request failed\"}"
    }

    private static func decodeBase64Data(_ raw: String) -> Data? {
        var clean = raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let rem = clean.count % 4
        if rem != 0 {
            clean += String(repeating: "=", count: 4 - rem)
        }
        return Data(base64Encoded: clean)
    }

    private var hostBootstrapScript: String {
        """
        var __musicoPendingHttp = {};
        var __musicoNextHttpId = 1;
        globalThis.__musicoResolveHttp = function(id, ok, envelopeJson) {
          var entry = __musicoPendingHttp[id];
          if (!entry) return;
          delete __musicoPendingHttp[id];
          var payload = JSON.parse(envelopeJson);
          if (ok) {
            entry.resolve(payload);
          } else {
            var err = new Error(payload.message || 'HTTP request failed');
            if (payload.code) err.code = payload.code;
            entry.reject(err);
          }
        };

        var __musicoTimers = {};
        var __musicoNextTimerId = 1;
        globalThis.setTimeout = function(fn, delay) {
          var id = __musicoNextTimerId++;
          __musicoTimers[id] = fn;
          __nativeScheduleTimer(id, Number(delay) || 0);
          return id;
        };
        globalThis.clearTimeout = function(id) {
          delete __musicoTimers[id];
          __nativeCancelTimer(Number(id) || 0);
        };
        globalThis.__musicoFireTimer = function(id) {
          var fn = __musicoTimers[id];
          delete __musicoTimers[id];
          if (typeof fn === 'function') fn();
        };

        var __musicoCtx = {
          http: {
            request: function(req) {
              return new Promise(function(resolve, reject) {
                var id = __musicoNextHttpId++;
                __musicoPendingHttp[id] = { resolve: resolve, reject: reject };
                __nativeHttpRequest(id, JSON.stringify(req || {}));
              });
            }
          },
          crypto: {
            md5: function(s) { return __nativeCrypto('md5', String(s === undefined ? '' : s), null); },
            sha1: function(s) { return __nativeCrypto('sha1', String(s === undefined ? '' : s), null); },
            sha256: function(s) { return __nativeCrypto('sha256', String(s === undefined ? '' : s), null); },
            hmacSha256: function(k, m) { return __nativeCrypto('hmacSha256', String(k === undefined ? '' : k), String(m === undefined ? '' : m)); },
            base64Encode: function(s) { return __nativeCrypto('base64Encode', String(s === undefined ? '' : s), null); },
            base64Decode: function(s) { return __nativeCrypto('base64Decode', String(s === undefined ? '' : s), null); }
          },
          kv: {
            get: function(key) {
              var val = __nativeKVGet(String(key));
              return (val === null || val === undefined) ? null : String(val);
            },
            set: function(key, val, ttlSeconds) {
              if (typeof ttlSeconds === 'number') {
                __nativeKVSet(String(key), String(val), ttlSeconds);
              } else {
                __nativeKVSet(String(key), String(val), null);
              }
            },
            remove: function(key) {
              __nativeKVRemove(String(key));
            }
          },
          settings: {
            get: function(key) {
              var json = __nativeSettingsGetJSON(String(key));
              if (!json || json === 'null') return null;
              var arr = JSON.parse(json);
              return Array.isArray(arr) && arr.length > 0 ? arr[0] : null;
            }
          },
          env: globalThis.__musicoEnv,
          log: {
            info: function(msg) { __nativeLog('info', String(msg)); },
            warn: function(msg) { __nativeLog('warn', String(msg)); },
            error: function(msg) { __nativeLog('error', String(msg)); }
          }
        };

        globalThis.console = {
          log: function() { __musicoCtx.log.info(Array.prototype.slice.call(arguments).map(String).join(' ')); },
          info: function() { __musicoCtx.log.info(Array.prototype.slice.call(arguments).map(String).join(' ')); },
          warn: function() { __musicoCtx.log.warn(Array.prototype.slice.call(arguments).map(String).join(' ')); },
          error: function() { __musicoCtx.log.error(Array.prototype.slice.call(arguments).map(String).join(' ')); }
        };

        globalThis.__musicoInvokeHook = function(hookName, reqJson) {
          try {
            var fn = __musicoExports[hookName];
            if (typeof fn !== 'function') {
              var missingErr = new Error('Missing hook: ' + hookName);
              missingErr.code = 'EXTENSION_RUNTIME_ERROR';
              __nativeComplete(false, JSON.stringify({ code: missingErr.code, message: missingErr.message }));
              return;
            }
            var req = JSON.parse(reqJson);
            Promise.resolve(fn(__musicoCtx, req)).then(
              function(result) {
                __nativeComplete(true, JSON.stringify(result === undefined ? null : result));
              },
              function(err) {
                var code = (err && typeof err.code === 'string' && err.code) ? err.code : 'EXTENSION_RUNTIME_ERROR';
                var msg = (err && err.message) ? String(err.message) : String(err || 'Unknown extension error');
                __nativeComplete(false, JSON.stringify({ code: code, message: msg }));
              }
            );
          } catch (err) {
            var code = (err && typeof err.code === 'string' && err.code) ? err.code : 'EXTENSION_RUNTIME_ERROR';
            var msg = (err && err.message) ? String(err.message) : String(err || 'Unknown extension error');
            __nativeComplete(false, JSON.stringify({ code: code, message: msg }));
          }
        };
        """
    }
}

private struct ErrorEnvelope: Codable {
    var code: String
    var message: String
}

private final class SyncExtractionGate: @unchecked Sendable {
    private let lock = NSLock()
    private var finished = false
    private var continuation: CheckedContinuation<ExtensionManifest, Error>?
    private var timeoutItem: DispatchWorkItem?

    var isFinished: Bool { lock.withLock { finished } }

    func setContinuation(_ continuation: CheckedContinuation<ExtensionManifest, Error>) {
        lock.withLock {
            if finished {
                continuation.resume(throwing: CancellationError())
            } else {
                self.continuation = continuation
            }
        }
    }

    func setTimeoutItem(_ item: DispatchWorkItem) {
        lock.withLock {
            if finished {
                item.cancel()
            } else {
                self.timeoutItem = item
            }
        }
    }

    func finish(with result: Result<ExtensionManifest, Error>) {
        let cont: CheckedContinuation<ExtensionManifest, Error>? = lock.withLock {
            guard !finished else { return nil }
            finished = true
            timeoutItem?.cancel()
            timeoutItem = nil
            let c = continuation
            continuation = nil
            return c
        }
        cont?.resume(with: result)
    }
}

private final class LockedResultBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: T?

    func set(_ value: T) {
        lock.withLock { self.value = value }
    }

    func get() -> T? {
        lock.withLock { value }
    }
}
