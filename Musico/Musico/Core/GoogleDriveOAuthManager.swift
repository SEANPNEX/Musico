import CryptoKit
import Foundation
import Network
import Observation
#if !targetEnvironment(macCatalyst)
import SafariServices
#endif
import Security
import UIKit

struct GoogleDriveOAuthRecord: Codable, Equatable, Sendable {
    var clientId: String = ""
    var clientSecret: String = ""
    var accessToken: String = ""
    var refreshToken: String = ""
    var expiresAtEpochMs: Double = 0
    var accountEmail: String? = nil
}

struct GoogleDrivePKCEPair: Equatable, Sendable {
    let codeVerifier: String
    let codeChallenge: String
}

struct GoogleDriveOAuthCallback: Equatable, Sendable {
    let code: String?
    let state: String?
    let error: String?
    let errorDescription: String?
}

struct GoogleDriveTokenResponse: Equatable, Sendable {
    let accessToken: String
    let refreshToken: String?
    let expiresInSeconds: Double
}

protocol GoogleDriveOAuthStorage: Sendable {
    func loadRecord() -> GoogleDriveOAuthRecord
    func saveRecord(_ record: GoogleDriveOAuthRecord)
}

final class KeychainGoogleDriveOAuthStorage: GoogleDriveOAuthStorage, @unchecked Sendable {
    private let service = "com.musico.gdrive.oauth"
    private let account = "default"
    private let lock = NSLock()

    func loadRecord() -> GoogleDriveOAuthRecord {
        lock.withLock {
            let query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: account,
                kSecReturnData as String: true,
                kSecMatchLimit as String: kSecMatchLimitOne
            ]
            var item: CFTypeRef?
            let status = SecItemCopyMatching(query as CFDictionary, &item)
            guard status == errSecSuccess,
                  let data = item as? Data,
                  let decoded = try? JSONDecoder().decode(GoogleDriveOAuthRecord.self, from: data) else {
                return GoogleDriveOAuthRecord()
            }
            return decoded
        }
    }

    func saveRecord(_ record: GoogleDriveOAuthRecord) {
        lock.withLock {
            let query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: account
            ]
            guard let data = try? JSONEncoder().encode(record) else { return }
            SecItemDelete(query as CFDictionary)
            var attrs = query
            attrs[kSecValueData as String] = data
            attrs[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            SecItemAdd(attrs as CFDictionary, nil)
        }
    }
}

actor GoogleDriveOAuthTokenCoordinator {
    private let storage: any GoogleDriveOAuthStorage
    private let transport: any CloudHTTPTransport
    private var record: GoogleDriveOAuthRecord

    init(
        storage: any GoogleDriveOAuthStorage,
        transport: any CloudHTTPTransport
    ) {
        self.storage = storage
        self.transport = transport
        self.record = storage.loadRecord()
    }

    func currentSnapshot() -> GoogleDriveOAuthRecord {
        record
    }

    func updateClientCredentials(clientId: String, clientSecret: String) -> GoogleDriveOAuthRecord {
        record.clientId = clientId.trimmingCharacters(in: .whitespacesAndNewlines)
        record.clientSecret = clientSecret.trimmingCharacters(in: .whitespacesAndNewlines)
        storage.saveRecord(record)
        return record
    }

    func storeSignedInTokens(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Double,
        accountEmail: String?
    ) -> GoogleDriveOAuthRecord {
        record.accessToken = accessToken
        if let refreshToken, !refreshToken.isEmpty {
            record.refreshToken = refreshToken
        }
        let nowMs = Date().timeIntervalSince1970 * 1000.0
        record.expiresAtEpochMs = nowMs + max(60.0, expiresInSeconds) * 1000.0
        record.accountEmail = accountEmail
        storage.saveRecord(record)
        return record
    }

    func clearTokens() -> GoogleDriveOAuthRecord {
        record.accessToken = ""
        record.refreshToken = ""
        record.expiresAtEpochMs = 0
        record.accountEmail = nil
        storage.saveRecord(record)
        return record
    }

    func validAccessToken(forceRefresh: Bool = false) async throws -> String? {
        let nowMs = Date().timeIntervalSince1970 * 1000.0
        if !forceRefresh,
           !record.accessToken.isEmpty,
           record.expiresAtEpochMs > (nowMs + 60_000.0) {
            return record.accessToken
        }

        guard !record.refreshToken.isEmpty, !record.clientId.isEmpty else {
            if !record.accessToken.isEmpty, record.expiresAtEpochMs > (nowMs + 5_000.0) {
                return record.accessToken
            }
            return nil
        }

        do {
            let refreshed = try await GoogleDriveOAuthManager.refreshAccessToken(
                clientId: record.clientId,
                clientSecret: record.clientSecret.isEmpty ? nil : record.clientSecret,
                refreshToken: record.refreshToken,
                transport: transport
            )
            record.accessToken = refreshed.accessToken
            if let nextRefresh = refreshed.refreshToken, !nextRefresh.isEmpty {
                record.refreshToken = nextRefresh
            }
            record.expiresAtEpochMs = (Date().timeIntervalSince1970 * 1000.0) + max(60.0, refreshed.expiresInSeconds) * 1000.0
            storage.saveRecord(record)
            return record.accessToken
        } catch let error as GoogleDriveOAuthError {
            if case .revokedOrInvalid = error {
                _ = clearTokens()
            }
            throw error
        }
    }
}

enum GoogleDriveOAuthError: LocalizedError, Equatable {
    case missingClientID
    case cancelled
    case stateMismatch
    case authorizationFailed(String)
    case revokedOrInvalid(String)
    case network(String)

    var errorDescription: String? {
        switch self {
        case .missingClientID:
            L10n.trNonisolated("Enter a Google Cloud OAuth Client ID first.")
        case .cancelled:
            L10n.trNonisolated("Google sign-in was cancelled.")
        case .stateMismatch:
            L10n.trNonisolated("OAuth state validation failed.")
        case .authorizationFailed(let msg):
            msg
        case .revokedOrInvalid(let msg):
            msg
        case .network(let msg):
            msg
        }
    }
}

@MainActor
@Observable
final class GoogleDriveOAuthManager {
    static let shared = GoogleDriveOAuthManager()

    static let driveReadonlyScope = "https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/userinfo.email"
    static let authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
    static let tokenEndpoint = "https://oauth2.googleapis.com/token"
    static let userInfoEndpoint = "https://www.googleapis.com/oauth2/v3/userinfo"

    var clientId: String
    var clientSecret: String
    var isSignedIn: Bool
    var accountEmail: String?
    var isSigningIn: Bool = false
    var errorMessage: String?

    @ObservationIgnored private let coordinator: GoogleDriveOAuthTokenCoordinator
    @ObservationIgnored private let transport: any CloudHTTPTransport

    init(
        storage: any GoogleDriveOAuthStorage = KeychainGoogleDriveOAuthStorage(),
        transport: any CloudHTTPTransport = URLSessionCloudTransport()
    ) {
        self.transport = transport
        self.coordinator = GoogleDriveOAuthTokenCoordinator(storage: storage, transport: transport)
        let initial = storage.loadRecord()
        self.clientId = initial.clientId
        self.clientSecret = initial.clientSecret
        self.isSignedIn = !initial.accessToken.isEmpty || !initial.refreshToken.isEmpty
        self.accountEmail = initial.accountEmail
    }

    func saveClientCredentials() {
        let cid = clientId
        let secret = clientSecret
        Task {
            _ = await coordinator.updateClientCredentials(clientId: cid, clientSecret: secret)
        }
    }

    nonisolated func validAccessToken(forceRefresh: Bool = false) async throws -> String? {
        try await coordinator.validAccessToken(forceRefresh: forceRefresh)
    }

    func signOut() {
        errorMessage = nil
        isSignedIn = false
        accountEmail = nil
        Task {
            _ = await coordinator.clearTokens()
        }
    }

    func startSignIn() async {
        let cleanClientID = clientId.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanSecret = clientSecret.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanClientID.isEmpty else {
            errorMessage = GoogleDriveOAuthError.missingClientID.localizedDescription
            return
        }

        _ = await coordinator.updateClientCredentials(clientId: cleanClientID, clientSecret: cleanSecret)
        isSigningIn = true
        errorMessage = nil
        defer { isSigningIn = false }

        do {
            let pkce = Self.generatePKCE()
            let state = Self.randomBase64URLString(byteCount: 24)
            let server = try LoopbackOAuthListener()
            defer { server.stop() }

            let port = try await server.startAndAwaitPort()
            let redirectURI = "http://127.0.0.1:\(port)/oauth2callback"
            let authURL = try Self.buildAuthorizationURL(
                clientId: cleanClientID,
                redirectURI: redirectURI,
                state: state,
                codeChallenge: pkce.codeChallenge
            )

            await presentBrowser(url: authURL)
            let callback = try await server.waitForCallback(timeoutSeconds: 180)
            dismissSafariIfNeeded()

            if let err = callback.error, !err.isEmpty {
                throw GoogleDriveOAuthError.authorizationFailed(callback.errorDescription ?? err)
            }
            guard callback.state == state else {
                throw GoogleDriveOAuthError.stateMismatch
            }
            guard let code = callback.code, !code.isEmpty else {
                throw GoogleDriveOAuthError.cancelled
            }

            let tokens = try await Self.exchangeAuthorizationCode(
                clientId: cleanClientID,
                clientSecret: cleanSecret.isEmpty ? nil : cleanSecret,
                code: code,
                codeVerifier: pkce.codeVerifier,
                redirectURI: redirectURI,
                transport: transport
            )
            let email = await Self.fetchUserEmail(accessToken: tokens.accessToken, transport: transport)
            let saved = await coordinator.storeSignedInTokens(
                accessToken: tokens.accessToken,
                refreshToken: tokens.refreshToken,
                expiresInSeconds: tokens.expiresInSeconds,
                accountEmail: email
            )
            isSignedIn = !saved.accessToken.isEmpty || !saved.refreshToken.isEmpty
            accountEmail = saved.accountEmail
        } catch is CancellationError {
            errorMessage = GoogleDriveOAuthError.cancelled.localizedDescription
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Pure Testable Helpers

    nonisolated static func base64URLEncode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    nonisolated static func randomBase64URLString(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: max(16, byteCount))
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return base64URLEncode(Data(bytes))
    }

    nonisolated static func generatePKCE() -> GoogleDrivePKCEPair {
        let verifier = randomBase64URLString(byteCount: 32)
        let digest = SHA256.hash(data: Data(verifier.utf8))
        let challenge = base64URLEncode(Data(digest))
        return GoogleDrivePKCEPair(codeVerifier: verifier, codeChallenge: challenge)
    }

    nonisolated static func buildAuthorizationURL(
        clientId: String,
        redirectURI: String,
        state: String,
        codeChallenge: String
    ) throws -> URL {
        guard var components = URLComponents(string: authEndpoint) else {
            throw GoogleDriveOAuthError.network("Invalid Google OAuth authorization endpoint.")
        }
        components.queryItems = [
            URLQueryItem(name: "client_id", value: clientId),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: driveReadonlyScope),
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "prompt", value: "consent"),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "code_challenge", value: codeChallenge),
            URLQueryItem(name: "state", value: state)
        ]
        guard let url = components.url else {
            throw GoogleDriveOAuthError.network("Failed to build Google OAuth authorization URL.")
        }
        return url
    }

    nonisolated static func parseCallbackRequest(_ rawHTTPRequest: String) -> GoogleDriveOAuthCallback? {
        guard let firstLine = rawHTTPRequest.components(separatedBy: .newlines).first else {
            return nil
        }
        let parts = firstLine.split(separator: " ")
        guard parts.count >= 2 else { return nil }
        let pathAndQuery = String(parts[1])
        guard let components = URLComponents(string: "http://127.0.0.1\(pathAndQuery)") else {
            return nil
        }
        let query = Dictionary(
            uniqueKeysWithValues: (components.queryItems ?? []).compactMap { item in
                item.value.map { (item.name, $0) }
            }
        )
        return GoogleDriveOAuthCallback(
            code: query["code"],
            state: query["state"],
            error: query["error"],
            errorDescription: query["error_description"]
        )
    }

    nonisolated static func formURLEncodedBody(_ params: [(String, String)]) -> Data {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "+&=")
        let encoded = params.compactMap { key, val -> String? in
            guard let k = key.addingPercentEncoding(withAllowedCharacters: allowed),
                  let v = val.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
            return "\(k)=\(v)"
        }.joined(separator: "&")
        return Data(encoded.utf8)
    }

    nonisolated static func exchangeAuthorizationCode(
        clientId: String,
        clientSecret: String?,
        code: String,
        codeVerifier: String,
        redirectURI: String,
        transport: any CloudHTTPTransport = URLSessionCloudTransport()
    ) async throws -> GoogleDriveTokenResponse {
        var params: [(String, String)] = [
            ("grant_type", "authorization_code"),
            ("client_id", clientId),
            ("code", code),
            ("code_verifier", codeVerifier),
            ("redirect_uri", redirectURI)
        ]
        if let clientSecret, !clientSecret.isEmpty {
            params.append(("client_secret", clientSecret))
        }
        return try await postTokenRequest(params: params, transport: transport)
    }

    nonisolated static func refreshAccessToken(
        clientId: String,
        clientSecret: String?,
        refreshToken: String,
        transport: any CloudHTTPTransport = URLSessionCloudTransport()
    ) async throws -> GoogleDriveTokenResponse {
        var params: [(String, String)] = [
            ("grant_type", "refresh_token"),
            ("client_id", clientId),
            ("refresh_token", refreshToken)
        ]
        if let clientSecret, !clientSecret.isEmpty {
            params.append(("client_secret", clientSecret))
        }
        return try await postTokenRequest(params: params, transport: transport)
    }

    nonisolated private static func postTokenRequest(
        params: [(String, String)],
        transport: any CloudHTTPTransport
    ) async throws -> GoogleDriveTokenResponse {
        guard let url = URL(string: tokenEndpoint) else {
            throw GoogleDriveOAuthError.network("Invalid Google OAuth token endpoint.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = formURLEncodedBody(params)

        let response = try await transport.send(request)
        let json = (try? JSONSerialization.jsonObject(with: response.data) as? [String: Any]) ?? [:]
        if let errCode = json["error"] as? String {
            let desc = (json["error_description"] as? String) ?? errCode
            if errCode == "invalid_grant" || errCode == "unauthorized_client" || response.statusCode == 401 {
                throw GoogleDriveOAuthError.revokedOrInvalid(desc)
            }
            throw GoogleDriveOAuthError.authorizationFailed(desc)
        }
        guard (200..<300).contains(response.statusCode),
              let accessToken = json["access_token"] as? String,
              !accessToken.isEmpty else {
            throw GoogleDriveOAuthError.network("Google OAuth token request failed (HTTP \(response.statusCode)).")
        }
        let refreshToken = json["refresh_token"] as? String
        let expiresIn = (json["expires_in"] as? NSNumber)?.doubleValue ?? 3600.0
        return GoogleDriveTokenResponse(
            accessToken: accessToken,
            refreshToken: refreshToken,
            expiresInSeconds: expiresIn
        )
    }

    nonisolated static func fetchUserEmail(
        accessToken: String,
        transport: any CloudHTTPTransport = URLSessionCloudTransport()
    ) async -> String? {
        guard let url = URL(string: userInfoEndpoint) else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "GET"
        req.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        guard let response = try? await transport.send(req),
              (200..<300).contains(response.statusCode),
              let json = try? JSONSerialization.jsonObject(with: response.data) as? [String: Any],
              let email = json["email"] as? String,
              !email.isEmpty else {
            return nil
        }
        return email
    }

    // MARK: - Browser Presentation

    #if !targetEnvironment(macCatalyst)
    @ObservationIgnored private var presentedSafari: SFSafariViewController?
    #endif

    private func presentBrowser(url: URL) async {
        #if targetEnvironment(macCatalyst)
        await UIApplication.shared.open(url)
        #else
        if let topVC = Self.topViewController() {
            let safari = SFSafariViewController(url: url)
            safari.modalPresentationStyle = .formSheet
            presentedSafari = safari
            topVC.present(safari, animated: true)
        } else {
            await UIApplication.shared.open(url)
        }
        #endif
    }

    private func dismissSafariIfNeeded() {
        #if !targetEnvironment(macCatalyst)
        presentedSafari?.dismiss(animated: true)
        presentedSafari = nil
        #endif
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let root = scenes
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController
        var current = root
        while let next = current?.presentedViewController {
            current = next
        }
        return current
    }
}

private final class LoopbackOAuthListener: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.musico.gdrive.oauth.loopback")
    private let listener: NWListener
    private var portContinuation: CheckedContinuation<UInt16, Error>?
    private var callbackContinuation: CheckedContinuation<GoogleDriveOAuthCallback, Error>?
    private var resolvedCallback: GoogleDriveOAuthCallback?

    init() throws {
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: .ipv4(.loopback), port: .any)
        self.listener = try NWListener(using: params)
    }

    func startAndAwaitPort() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { cont in
            queue.async {
                self.portContinuation = cont
                self.listener.stateUpdateHandler = { [weak self] state in
                    guard let self else { return }
                    switch state {
                    case .ready:
                        if let port = self.listener.port?.rawValue, let c = self.portContinuation {
                            self.portContinuation = nil
                            c.resume(returning: port)
                        }
                    case .failed(let error):
                        if let c = self.portContinuation {
                            self.portContinuation = nil
                            c.resume(throwing: error)
                        }
                        if let cc = self.callbackContinuation {
                            self.callbackContinuation = nil
                            cc.resume(throwing: error)
                        }
                    default:
                        break
                    }
                }
                self.listener.newConnectionHandler = { [weak self] conn in
                    self?.handleConnection(conn)
                }
                self.listener.start(queue: self.queue)
            }
        }
    }

    func waitForCallback(timeoutSeconds: UInt64) async throws -> GoogleDriveOAuthCallback {
        try await withThrowingTaskGroup(of: GoogleDriveOAuthCallback.self) { group in
            group.addTask {
                try await withCheckedThrowingContinuation { cont in
                    self.queue.async {
                        if let already = self.resolvedCallback {
                            self.resolvedCallback = nil
                            cont.resume(returning: already)
                        } else {
                            self.callbackContinuation = cont
                        }
                    }
                }
            }
            group.addTask {
                try await Task.sleep(nanoseconds: timeoutSeconds * 1_000_000_000)
                throw GoogleDriveOAuthError.cancelled
            }
            guard let first = try await group.next() else {
                throw GoogleDriveOAuthError.cancelled
            }
            group.cancelAll()
            return first
        }
    }

    func stop() {
        queue.async {
            self.listener.cancel()
        }
    }

    private func handleConnection(_ connection: NWConnection) {
        connection.start(queue: queue)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, _, _ in
            guard let self else {
                connection.cancel()
                return
            }
            let raw = data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            let html = """
            HTTP/1.1 200 OK\r
            Content-Type: text/html; charset=utf-8\r
            Connection: close\r
            \r
            <!doctype html><html><body style="font-family:-apple-system,sans-serif;text-align:center;padding:48px;">
            <h2>Musico</h2><p>Google Drive sign-in complete. You can close this window and return to Musico.</p>
            </body></html>
            """
            connection.send(content: Data(html.utf8), completion: .contentProcessed { _ in
                connection.cancel()
            })

            if let parsed = GoogleDriveOAuthManager.parseCallbackRequest(raw),
               (parsed.code != nil || parsed.error != nil) {
                if let cont = self.callbackContinuation {
                    self.callbackContinuation = nil
                    cont.resume(returning: parsed)
                } else {
                    self.resolvedCallback = parsed
                }
            }
        }
    }
}
