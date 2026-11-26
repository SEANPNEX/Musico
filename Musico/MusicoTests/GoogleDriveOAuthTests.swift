import Foundation
import XCTest
@testable import Musico

private final class InMemoryDriveOAuthStorage: GoogleDriveOAuthStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var record: GoogleDriveOAuthRecord

    init(_ initial: GoogleDriveOAuthRecord = GoogleDriveOAuthRecord()) {
        self.record = initial
    }

    func loadRecord() -> GoogleDriveOAuthRecord {
        lock.withLock { record }
    }

    func saveRecord(_ record: GoogleDriveOAuthRecord) {
        lock.withLock { self.record = record }
    }
}

private final class OAuthMockTransport: CloudHTTPTransport, @unchecked Sendable {
    let handler: @Sendable (URLRequest) throws -> HTTPResponseData

    init(_ handler: @escaping @Sendable (URLRequest) throws -> HTTPResponseData) {
        self.handler = handler
    }

    func send(_ request: URLRequest) async throws -> HTTPResponseData {
        try handler(request)
    }
}

final class GoogleDriveOAuthTests: XCTestCase {
    func testPKCEAuthURLAndCallbackParsing() throws {
        let pkce = GoogleDriveOAuthManager.generatePKCE()
        XCTAssertFalse(pkce.codeVerifier.contains("="))
        XCTAssertFalse(pkce.codeVerifier.contains("+"))
        XCTAssertFalse(pkce.codeVerifier.contains("/"))
        XCTAssertFalse(pkce.codeChallenge.contains("="))
        XCTAssertNotEqual(pkce.codeVerifier, pkce.codeChallenge)

        let url = try GoogleDriveOAuthManager.buildAuthorizationURL(
            clientId: "client-123.apps.googleusercontent.com",
            redirectURI: "http://127.0.0.1:45678/oauth2callback",
            state: "state_xyz",
            codeChallenge: pkce.codeChallenge
        )
        let components = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let query = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).compactMap { item in
            item.value.map { (item.name, $0) }
        })
        XCTAssertEqual(components.host, "accounts.google.com")
        XCTAssertEqual(query["client_id"], "client-123.apps.googleusercontent.com")
        XCTAssertEqual(query["redirect_uri"], "http://127.0.0.1:45678/oauth2callback")
        XCTAssertEqual(query["code_challenge"], pkce.codeChallenge)
        XCTAssertEqual(query["code_challenge_method"], "S256")
        XCTAssertEqual(query["state"], "state_xyz")
        XCTAssertEqual(query["access_type"], "offline")

        let parsed = try XCTUnwrap(GoogleDriveOAuthManager.parseCallbackRequest(
            "GET /oauth2callback?code=auth_code_99&state=state_xyz HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
        ))
        XCTAssertEqual(parsed.code, "auth_code_99")
        XCTAssertEqual(parsed.state, "state_xyz")
        XCTAssertNil(parsed.error)
    }

    func testTokenCoordinatorRefreshesExpiredAccessToken() async throws {
        let storage = InMemoryDriveOAuthStorage(GoogleDriveOAuthRecord(
            clientId: "cid-1",
            clientSecret: "csec-1",
            accessToken: "stale_tok",
            refreshToken: "ref_tok_1",
            expiresAtEpochMs: 1_000,
            accountEmail: "user@example.com"
        ))
        let transport = OAuthMockTransport { req in
            XCTAssertEqual(req.url?.absoluteString, GoogleDriveOAuthManager.tokenEndpoint)
            let body = String(data: req.httpBody ?? Data(), encoding: .utf8) ?? ""
            XCTAssertTrue(body.contains("grant_type=refresh_token"))
            XCTAssertTrue(body.contains("refresh_token=ref_tok_1"))
            let json = """
            {
              "access_token": "fresh_tok_2",
              "expires_in": 3600
            }
            """
            return HTTPResponseData(
                statusCode: 200,
                headers: ["Content-Type": "application/json"],
                data: Data(json.utf8),
                finalURL: req.url!
            )
        }
        let coordinator = GoogleDriveOAuthTokenCoordinator(storage: storage, transport: transport)
        let token = try await coordinator.validAccessToken()
        XCTAssertEqual(token, "fresh_tok_2")
        XCTAssertEqual(storage.loadRecord().refreshToken, "ref_tok_1")
    }

    func testGoogleDriveClientUsesBearerOAuthAndRetriesOn401() async throws {
        let box = NSLockBox(0)
        let transport = OAuthMockTransport { req in
            let url = req.url!.absoluteString
            let auth = req.value(forHTTPHeaderField: "Authorization")

            if url.contains("/drive/v3/files/folder123?") {
                XCTAssertEqual(auth, "Bearer tok_1")
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "application/json"],
                    data: Data(#"{"id":"folder123","name":"Private Album","mimeType":"application/vnd.google-apps.folder"}"#.utf8),
                    finalURL: req.url!
                )
            }
            if url.contains("/drive/v3/files?") {
                XCTAssertEqual(auth, "Bearer tok_1")
                return HTTPResponseData(
                    statusCode: 200,
                    headers: ["Content-Type": "application/json"],
                    data: Data(#"{"files":[{"id":"trackABC12345","name":"01 Song.flac","mimeType":"audio/flac"}]}"#.utf8),
                    finalURL: req.url!
                )
            }
            if url.contains("/drive/v3/files/trackABC12345?alt=media") {
                let n = box.withLock {
                    $0 += 1
                    return $0
                }
                if n == 1 {
                    XCTAssertEqual(auth, "Bearer tok_1")
                    return HTTPResponseData(statusCode: 401, headers: [:], data: Data(), finalURL: req.url!)
                }
                XCTAssertEqual(auth, "Bearer tok_2")
                return HTTPResponseData(
                    statusCode: 206,
                    headers: ["Content-Type": "audio/flac"],
                    data: Data([0x66, 0x4C, 0x61, 0x43]),
                    finalURL: req.url!
                )
            }
            XCTFail("Unexpected URL: \(url)")
            return HTTPResponseData(statusCode: 404, headers: [:], data: Data(), finalURL: req.url!)
        }

        let client = GoogleDriveClient(
            transport: transport,
            retryDelaysNanoseconds: [],
            oauthTokenProvider: { forceRefresh in
                forceRefresh ? "tok_2" : "tok_1"
            }
        )

        let folder = try await client.fetchPlaylist(sourceURL: "https://drive.google.com/drive/folders/folder123", cookie: nil)
        XCTAssertEqual(folder.title, "Private Album")
        XCTAssertEqual(folder.tracks.first?.title, "01 Song")
        XCTAssertEqual(folder.tracks.first?.remoteTrackID, "trackABC12345")

        let stream = try await client.resolveStream(trackID: "trackABC12345", cookie: nil, fallbackExtension: nil, forceRefresh: false)
        XCTAssertEqual(stream.fileExtension, "flac")
        XCTAssertEqual(stream.headers["Authorization"], "Bearer tok_2")
        XCTAssertTrue(stream.url.absoluteString.contains("alt=media"))
    }
}

private final class NSLockBox<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: Value

    init(_ initial: Value) {
        self.storage = initial
    }

    @discardableResult
    func withLock<T>(_ body: (inout Value) -> T) -> T {
        lock.withLock { body(&storage) }
    }
}
