import CryptoKit
import Foundation
import XCTest
@testable import Musico

final class UpdateServiceTests: XCTestCase {

    // MARK: - AppVersion

    func testAppVersionParsingAndComparison() {
        XCTAssertEqual(AppVersion("v1.1.1"), AppVersion(major: 1, minor: 1, patch: 1))
        XCTAssertEqual(AppVersion("V1.1.1"), AppVersion(major: 1, minor: 1, patch: 1))
        XCTAssertEqual(AppVersion("v.1.1.1"), AppVersion(major: 1, minor: 1, patch: 1))
        XCTAssertEqual(AppVersion("V.1.1.1"), AppVersion(major: 1, minor: 1, patch: 1))
        XCTAssertEqual(AppVersion(" 1.1.1 "), AppVersion(major: 1, minor: 1, patch: 1))
        XCTAssertEqual(AppVersion("1.2"), AppVersion(major: 1, minor: 2, patch: 0))
        XCTAssertEqual(AppVersion("1.10.0")?.description, "1.10.0")

        XCTAssertGreaterThan(AppVersion("1.10.0")!, AppVersion("1.9.0")!)
        XCTAssertGreaterThan(AppVersion("1.1.1")!, AppVersion("1.1.0")!)
        XCTAssertGreaterThan(AppVersion("2.0.0")!, AppVersion("1.99.99")!)
        XCTAssertEqual(AppVersion("v1.1.1"), AppVersion("1.1.1"))

        XCTAssertNil(AppVersion(""))
        XCTAssertNil(AppVersion("v"))
        XCTAssertNil(AppVersion("1"))
        XCTAssertNil(AppVersion("1.2.3.4"))
        XCTAssertNil(AppVersion("1.2.x"))
        XCTAssertNil(AppVersion("1.-2.3"))
        XCTAssertNil(AppVersion("abc"))
    }

    // MARK: - Release JSON Decoding & Filtering

    func testReleaseJSONDecodingAndDraftPrereleaseFiltering() throws {
        let validJSON = """
        {
          "tag_name": "v1.2.0",
          "name": "Musico 1.2.0",
          "body": "- Bug fixes\\n- Updater",
          "draft": false,
          "prerelease": false,
          "html_url": "https://github.com/SEANPNEX/local_music_player/releases/tag/v1.2.0",
          "published_at": "2026-11-21T10:00:00Z",
          "assets": [
            {
              "name": "Musico-1.2.0-iOS-unsigned.ipa",
              "browser_download_url": "https://example.com/Musico-1.2.0-iOS-unsigned.ipa",
              "size": 100
            },
            {
              "name": "Musico-1.2.0-macOS.dmg",
              "browser_download_url": "https://example.com/Musico-1.2.0-macOS.dmg",
              "size": 200
            },
            {
              "name": "SHA256SUMS.txt",
              "browser_download_url": "https://example.com/SHA256SUMS.txt",
              "size": 64
            }
          ]
        }
        """

        let release = try XCTUnwrap(UpdateChecker.parseRelease(from: Data(validJSON.utf8)))
        XCTAssertEqual(release.version, AppVersion(major: 1, minor: 2, patch: 0))
        XCTAssertEqual(release.tagName, "v1.2.0")
        XCTAssertEqual(release.name, "Musico 1.2.0")
        XCTAssertEqual(release.body, "- Bug fixes\n- Updater")
        XCTAssertEqual(release.dmgAsset?.name, "Musico-1.2.0-macOS.dmg")
        XCTAssertEqual(release.sha256Asset?.name, "SHA256SUMS.txt")
        XCTAssertNotNil(release.publishedAt)

        let draftJSON = validJSON.replacingOccurrences(of: "\"draft\": false", with: "\"draft\": true")
        XCTAssertNil(try UpdateChecker.parseRelease(from: Data(draftJSON.utf8)))

        let preJSON = validJSON.replacingOccurrences(of: "\"prerelease\": false", with: "\"prerelease\": true")
        XCTAssertNil(try UpdateChecker.parseRelease(from: Data(preJSON.utf8)))
    }

    func testDmgAssetSelectionPrefersMacOSDmgAndFallsBack() {
        let genericFirst = [
            ReleaseAsset(name: "Musico-1.2.0-Setup.exe", browserDownloadURL: URL(string: "https://example.com/a.exe")!),
            ReleaseAsset(name: "other.dmg", browserDownloadURL: URL(string: "https://example.com/other.dmg")!),
            ReleaseAsset(name: "Musico-1.2.0-macOS.dmg", browserDownloadURL: URL(string: "https://example.com/mac.dmg")!),
        ]
        XCTAssertEqual(UpdateChecker.selectMacOSAsset(from: genericFirst)?.name, "Musico-1.2.0-macOS.dmg")

        let fallbackOnly = [
            ReleaseAsset(name: "Musico-1.2.0.apk", browserDownloadURL: URL(string: "https://example.com/a.apk")!),
            ReleaseAsset(name: "Musico-Universal.dmg", browserDownloadURL: URL(string: "https://example.com/u.dmg")!),
        ]
        XCTAssertEqual(UpdateChecker.selectMacOSAsset(from: fallbackOnly)?.name, "Musico-Universal.dmg")

        let noDmg = [
            ReleaseAsset(name: "Musico-1.2.0-iOS-unsigned.ipa", browserDownloadURL: URL(string: "https://example.com/a.ipa")!),
        ]
        XCTAssertNil(UpdateChecker.selectMacOSAsset(from: noDmg))
    }

    func testUpdateCheckerFetchLatestReleaseAndHTTPError() async throws {
        let payload = """
        {
          "tag_name": "V.1.3.0",
          "name": "",
          "body": null,
          "draft": false,
          "prerelease": false,
          "html_url": "https://github.com/SEANPNEX/local_music_player/releases/tag/V.1.3.0",
          "assets": []
        }
        """
        let checker = UpdateChecker { request in
            XCTAssertEqual(request.url?.absoluteString, "https://api.github.com/repos/SEANPNEX/local_music_player/releases/latest")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "application/vnd.github+json")
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (Data(payload.utf8), response)
        }
        let fetched = try await checker.fetchLatestRelease()
        let info = try XCTUnwrap(fetched)
        XCTAssertEqual(info.version, AppVersion(major: 1, minor: 3, patch: 0))
        XCTAssertEqual(info.name, "V.1.3.0")
        XCTAssertEqual(info.body, "")

        let failingChecker = UpdateChecker { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 403, httpVersion: nil, headerFields: nil)!
            return (Data(), response)
        }
        do {
            _ = try await failingChecker.fetchLatestRelease()
            XCTFail("Expected HTTP error")
        } catch let error as UpdateError {
            XCTAssertEqual(error, .httpStatus(403))
        }
    }

    // MARK: - SHA256SumsParser & Digest Verification

    func testSHA256SumsParserAndFileHashing() throws {
        let sampleData = Data("musico-dmg-payload".utf8)
        let expectedHash = SHA256.hash(data: sampleData).map { String(format: "%02x", $0) }.joined()
        let upperHash = expectedHash.uppercased()
        let otherHash = String(repeating: "a", count: 64)

        let sumsText = """
        # Release checksums
        \(otherHash)  Musico-1.2.0-Setup.exe
        \(upperHash) *./Musico-1.2.0-macOS.dmg
        not-a-hash  ignored.txt
        """

        let parsed = SHA256SumsParser.parse(sumsText)
        XCTAssertEqual(parsed["Musico-1.2.0-Setup.exe"], otherHash)
        XCTAssertEqual(parsed["Musico-1.2.0-macOS.dmg"], expectedHash)
        XCTAssertEqual(SHA256SumsParser.checksum(for: "Musico-1.2.0-macOS.dmg", in: sumsText), expectedHash)

        let dir = makeTempDir()
        let fileURL = dir.appendingPathComponent("Musico-1.2.0-macOS.dmg")
        try sampleData.write(to: fileURL)
        XCTAssertEqual(try SHA256SumsParser.sha256Hex(ofFile: fileURL), expectedHash)
    }

    // MARK: - UpdateManager Behavior

    @MainActor
    func testUpdateManagerSkippedVersionAndManualCheck() async throws {
        let suiteName = "UpdateServiceTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let releaseJSON = """
        {
          "tag_name": "v1.2.0",
          "name": "Musico 1.2.0",
          "body": "Notes",
          "draft": false,
          "prerelease": false,
          "html_url": "https://github.com/SEANPNEX/local_music_player/releases/tag/v1.2.0",
          "assets": [
            {
              "name": "Musico-1.2.0-macOS.dmg",
              "browser_download_url": "https://example.com/Musico-1.2.0-macOS.dmg",
              "size": 1024
            }
          ]
        }
        """

        let checker = UpdateChecker { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (Data(releaseJSON.utf8), response)
        }

        let manager = UpdateManager(
            checker: checker,
            defaults: defaults,
            currentVersion: AppVersion(major: 1, minor: 1, patch: 1),
            cacheDirectory: makeTempDir()
        )

        // First auto-check finds 1.2.0
        await manager.checkForUpdates(manual: false)
        guard case .available(let found) = manager.state else {
            return XCTFail("Expected .available, got \(manager.state)")
        }
        XCTAssertEqual(found.version, AppVersion(major: 1, minor: 2, patch: 0))

        // Skipping persists version and auto-check ignores it
        manager.skipVersion(found.version)
        XCTAssertEqual(manager.skippedUpdateVersion, "1.2.0")
        XCTAssertEqual(manager.state, .idle)

        await manager.checkForUpdates(manual: false)
        XCTAssertEqual(manager.state, .idle)

        // Manual check always shows the newer version even if skipped
        await manager.checkForUpdates(manual: true)
        guard case .available(let manualFound) = manager.state else {
            return XCTFail("Expected .available on manual check, got \(manager.state)")
        }
        XCTAssertEqual(manualFound.version, AppVersion(major: 1, minor: 2, patch: 0))

        // When current version is already >= latest, manual check reports upToDate
        let currentManager = UpdateManager(
            checker: checker,
            defaults: defaults,
            currentVersion: AppVersion(major: 1, minor: 2, patch: 0),
            cacheDirectory: makeTempDir()
        )
        await currentManager.checkForUpdates(manual: true)
        XCTAssertEqual(currentManager.state, .upToDate)
    }

    @MainActor
    func testUpdateManagerDownloadVerifiesSHA256AndOpensDmg() async throws {
        let suiteName = "UpdateServiceTests.dl.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let dmgPayload = Data("fake-dmg-bytes".utf8)
        let dmgHash = SHA256SumsParser.sha256Hex(of: dmgPayload)
        let sumsText = "\(dmgHash)  Musico-1.2.0-macOS.dmg\n"

        let releaseJSON = """
        {
          "tag_name": "v1.2.0",
          "name": "Musico 1.2.0",
          "body": "Release notes",
          "draft": false,
          "prerelease": false,
          "html_url": "https://github.com/SEANPNEX/local_music_player/releases/tag/v1.2.0",
          "assets": [
            {
              "name": "Musico-1.2.0-macOS.dmg",
              "browser_download_url": "https://example.com/Musico-1.2.0-macOS.dmg",
              "size": \(dmgPayload.count)
            },
            {
              "name": "SHA256SUMS.txt",
              "browser_download_url": "https://example.com/SHA256SUMS.txt",
              "size": \(sumsText.utf8.count)
            }
          ]
        }
        """

        let checker = UpdateChecker { request in
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
            return (Data(releaseJSON.utf8), response)
        }

        let cacheDir = makeTempDir()
        var openedURLs: [URL] = []

        let manager = UpdateManager(
            checker: checker,
            defaults: defaults,
            currentVersion: AppVersion(major: 1, minor: 1, patch: 1),
            cacheDirectory: cacheDir,
            downloadFile: { _, dest, progress in
                await progress(0.5)
                try dmgPayload.write(to: dest)
                await progress(1.0)
            },
            fetchData: { _ in Data(sumsText.utf8) },
            openURL: { url in
                openedURLs.append(url)
                return true
            }
        )

        await manager.checkForUpdates(manual: true)
        await manager.downloadAndOpenUpdate()

        let expectedPath = cacheDir.appendingPathComponent("Musico-1.2.0-macOS.dmg")
        XCTAssertEqual(openedURLs, [expectedPath])
        XCTAssertTrue(FileManager.default.fileExists(atPath: expectedPath.path))
        if case .readyToInstall(let info, let fileURL) = manager.state {
            XCTAssertEqual(info.version, AppVersion(major: 1, minor: 2, patch: 0))
            XCTAssertEqual(fileURL, expectedPath)
        } else {
            XCTFail("Expected .readyToInstall, got \(manager.state)")
        }

        // Mismatch test: tampered payload must fail cleanly and delete the downloaded file
        let badCacheDir = makeTempDir()
        var badOpened: [URL] = []
        let badManager = UpdateManager(
            checker: checker,
            defaults: defaults,
            currentVersion: AppVersion(major: 1, minor: 1, patch: 1),
            cacheDirectory: badCacheDir,
            downloadFile: { _, dest, _ in
                try Data("tampered-dmg".utf8).write(to: dest)
            },
            fetchData: { _ in Data(sumsText.utf8) },
            openURL: { url in
                badOpened.append(url)
                return true
            }
        )

        await badManager.checkForUpdates(manual: true)
        await badManager.downloadAndOpenUpdate()

        XCTAssertTrue(badOpened.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: badCacheDir.appendingPathComponent("Musico-1.2.0-macOS.dmg").path))
        guard case .error(let message) = badManager.state else {
            return XCTFail("Expected .error on checksum mismatch, got \(badManager.state)")
        }
        XCTAssertTrue(message.contains("SHA-256"))
    }
}
