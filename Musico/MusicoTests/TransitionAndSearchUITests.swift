import XCTest
import SwiftUI
#if !targetEnvironment(macCatalyst)
import UIKit
#endif
@testable import Musico

@MainActor
final class TransitionAndSearchUITests: XCTestCase {
    func testMusicoMotionRespectsReduceMotion() {
        XCTAssertNil(MusicoMotion.viewSwitch(reduceMotion: true))
        XCTAssertNil(MusicoMotion.panelToggle(reduceMotion: true))
        XCTAssertNil(MusicoMotion.playerPresentation(reduceMotion: true))
        XCTAssertNil(MusicoMotion.contentChange(reduceMotion: true))
        XCTAssertNil(MusicoMotion.microInteraction(reduceMotion: true))
        XCTAssertNil(MusicoMotion.symbolToggle(reduceMotion: true))

        XCTAssertNotNil(MusicoMotion.viewSwitch(reduceMotion: false))
        XCTAssertNotNil(MusicoMotion.panelToggle(reduceMotion: false))
        XCTAssertNotNil(MusicoMotion.playerPresentation(reduceMotion: false))
        XCTAssertNotNil(MusicoMotion.contentChange(reduceMotion: false))
        XCTAssertNotNil(MusicoMotion.microInteraction(reduceMotion: false))
        XCTAssertNotNil(MusicoMotion.symbolToggle(reduceMotion: false))

        XCTAssertGreaterThan(MusicoMotion.peerSlideOffset, 0)
    }

    func testLibrarySectionOrderingAndDirection() {
        XCTAssertEqual(LibraryView.Section.allCases.map(\.orderIndex), [0, 1, 2, 3])
        XCTAssertTrue(LibraryView.Section.isForward(from: .songs, to: .albums))
        XCTAssertTrue(LibraryView.Section.isForward(from: .albums, to: .playlists))
        XCTAssertTrue(LibraryView.Section.isForward(from: .artists, to: .artists))
        XCTAssertFalse(LibraryView.Section.isForward(from: .playlists, to: .artists))
        XCTAssertFalse(LibraryView.Section.isForward(from: .albums, to: .songs))
    }

    #if !targetEnvironment(macCatalyst)
    private var savedAnimationsEnabled = true

    override func setUp() async throws {
        try await super.setUp()
        savedAnimationsEnabled = UIView.areAnimationsEnabled
        UIView.setAnimationsEnabled(false)
    }

    override func tearDown() async throws {
        UIView.setAnimationsEnabled(savedAnimationsEnabled)
        try await super.tearDown()
    }

    func testIOSSongsSearchFieldVisibleImmediatelyWithoutClickingCloudSource() async throws {
        let stores = try await makeStores()
        defer { stores.player.shutdown() }

        let navigation = AppNavigation()
        navigation.selectedTab = .library
        navigation.librarySection = .songs
        navigation.searchSource = .local

        let host = LibraryView()
            .environment(stores.library)
            .environment(stores.player)
            .environment(stores.queues)
            .environment(stores.history)
            .environment(\.appNavigation, navigation)
        let window = try hostInWindow(NavigationStack { host }, size: CGSize(width: 390, height: 844))
        defer { tearDownWindow(window) }

        try await waitUntil("iOS SongsView should render its search field immediately without cloud source selection") {
            window.layoutIfNeeded()
            return !self.collectViews(UITextField.self, in: window).isEmpty
        }

        let navControllers = collectViewControllers(UINavigationController.self, from: window.rootViewController)
        for navController in navControllers {
            XCTAssertNil(navController.topViewController?.navigationItem.searchController)
        }
    }

    func testIOSSwitchingCloudSourceDoesNotForceSearchPresentation() async throws {
        let store = ExtensionStore(
            baseDirectory: makeTempDir(),
            registry: CloudProviderRegistry(),
            transport: NoopCloudHTTPTransport()
        )
        let controller = CloudSearchController { _, _, _, _, _ in
            CloudSearchPage(items: [], nextCursor: nil)
        }
        let navigation = AppNavigation()
        XCTAssertFalse(navigation.isSearchPresented)

        let descriptor = CloudSearchExtensionDescriptor(
            id: "ext.ios",
            providerID: "ext.ios.provider",
            name: "Ext iOS",
            badgeLabel: "Ext",
            searchKinds: [.track],
            supportsStream: true,
            supportsCollection: false,
            supportsCookie: false
        )

        controller.switchSource(
            to: .cloud(extensionID: "ext.ios"),
            currentQuery: "",
            activeExtensions: [descriptor],
            navigation: navigation,
            extensionStore: store
        )

        XCTAssertEqual(navigation.searchSource, .cloud(extensionID: "ext.ios"))
        XCTAssertFalse(navigation.isSearchPresented)
    }

    func testLibrarySubtabSwitchingRendersCleanly() async throws {
        let stores = try await makeStores()
        defer { stores.player.shutdown() }

        let navigation = AppNavigation()
        navigation.selectedTab = .library
        navigation.librarySection = .songs

        let window = try hostInWindow(
            NavigationStack {
                LibraryView()
                    .environment(stores.library)
                    .environment(stores.player)
                    .environment(stores.queues)
                    .environment(stores.history)
                    .environment(\.appNavigation, navigation)
            },
            size: CGSize(width: 390, height: 844)
        )
        defer { tearDownWindow(window) }

        for section in [LibraryView.Section.albums, .artists, .playlists, .songs] {
            navigation.librarySection = section
            try await Task.sleep(for: .milliseconds(80))
            window.layoutIfNeeded()
            XCTAssertEqual(navigation.librarySection, section)
        }
    }

    func testFullPlayerTwoColumnLyricsToQueueSwitchRendersCleanly() async throws {
        let stores = try await makeStores(withSampleTrack: true)
        defer { stores.player.shutdown() }

        if let id = stores.library.tracks.first?.id {
            stores.player.play([id], startAt: 0)
            stores.player.pause()
        }

        let navigation = AppNavigation()
        let window = try hostInWindow(
            FullPlayerView(onClose: {})
                .environment(stores.library)
                .environment(stores.player)
                .environment(stores.queues)
                .environment(stores.history)
                .environment(\.appNavigation, navigation),
            size: CGSize(width: 960, height: 700)
        )
        defer { tearDownWindow(window) }

        try await Task.sleep(for: .milliseconds(250))
        window.layoutIfNeeded()
        XCTAssertNotNil(window.rootViewController?.view)
    }

    func testFullPlayerSingleColumnAndOverlayLyricsRenderCleanly() async throws {
        let stores = try await makeStores(withSampleTrack: true)
        defer { stores.player.shutdown() }

        if let id = stores.library.tracks.first?.id {
            stores.player.play([id], startAt: 0)
            stores.player.pause()
        }

        let navigation = AppNavigation()
        let window = try hostInWindow(
            ZStack {
                FullPlayerView(onClose: {})
                LyricsView(isOverlay: true, onDone: {})
            }
            .environment(stores.library)
            .environment(stores.player)
            .environment(stores.queues)
            .environment(stores.history)
            .environment(\.appNavigation, navigation),
            size: CGSize(width: 390, height: 844)
        )
        defer { tearDownWindow(window) }

        try await Task.sleep(for: .milliseconds(250))
        window.layoutIfNeeded()
        XCTAssertNotNil(window.rootViewController?.view)
    }

    // MARK: - Helpers

    private struct TestStores {
        let library: LibraryStore
        let queues: QueueStore
        let history: PlayHistoryStore
        let player: PlayerController
    }

    private func makeStores(withSampleTrack: Bool = false) async throws -> TestStores {
        let state = makeTempDir()
        let docs = makeTempDir()
        if withSampleTrack {
            let fixture = try XCTUnwrap(Bundle(for: TransitionAndSearchUITests.self).url(forResource: "sample", withExtension: "mp3"))
            try FileManager.default.copyItem(at: fixture, to: docs.appendingPathComponent("sample.mp3"))
        }
        let library = LibraryStore(
            directory: state,
            documentsURL: docs,
            reader: StubReader(),
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork"))
        )
        if withSampleTrack {
            await library.rescan()
        }
        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        let player = PlayerController(
            library: library,
            queues: queues,
            history: history,
            engine: AudioEngine(),
            settingsURL: state.appendingPathComponent("settings.json")
        )
        return TestStores(library: library, queues: queues, history: history, player: player)
    }

    private func hostInWindow<Content: View>(_ view: Content, size: CGSize) throws -> UIWindow {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        window.rootViewController = UIHostingController(rootView: view)
        window.makeKeyAndVisible()
        window.layoutIfNeeded()
        return window
    }

    private func tearDownWindow(_ window: UIWindow) {
        if window.rootViewController?.presentedViewController != nil {
            window.rootViewController?.dismiss(animated: false)
        }
        window.isHidden = true
        window.rootViewController = nil
    }

    private func collectViews<T: UIView>(_ type: T.Type, in root: UIView) -> [T] {
        var result: [T] = []
        if let match = root as? T {
            result.append(match)
        }
        for subview in root.subviews {
            result.append(contentsOf: collectViews(type, in: subview))
        }
        return result
    }

    private func collectViewControllers<T: UIViewController>(_ type: T.Type, from root: UIViewController?) -> [T] {
        guard let root else { return [] }
        var result: [T] = []
        if let match = root as? T {
            result.append(match)
        }
        for child in root.children {
            result.append(contentsOf: collectViewControllers(type, from: child))
        }
        if let presented = root.presentedViewController {
            result.append(contentsOf: collectViewControllers(type, from: presented))
        }
        return result
    }

    private func waitUntil(_ message: String, timeoutSeconds: TimeInterval = 3.0, condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeoutSeconds)
        while Date() < deadline {
            if condition() {
                return
            }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTAssertTrue(condition(), "Timed out: \(message)")
    }
    #endif
}

private final class NoopCloudHTTPTransport: CloudHTTPTransport, @unchecked Sendable {
    func send(_ request: URLRequest) async throws -> HTTPResponseData {
        HTTPResponseData(statusCode: 200, headers: [:], data: Data(), finalURL: request.url)
    }
}
