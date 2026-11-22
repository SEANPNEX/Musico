import XCTest
import SwiftUI
@testable import Musico

/// On the Mac a sheet's top-level view can be evaluated before the app's stores reach it.
/// These show each sheet the way the app does, with the stores set on the presenting view.
@MainActor
final class SheetEnvironmentTests: XCTestCase {
    /// `sheet` nil shows the app's own root view in `layout`, with the full player open if `player`.
    private func present(
        _ sheet: (some View)?,
        over outer: Bool,
        layout: AppLayout = .tabs,
        showPlayer: Bool = true,
        withSampleTrack: Bool = false,
        attachEmptyHostEnvironment: Bool = false
    ) async throws {
        let state = makeTempDir()
        let docs = makeTempDir()
        if withSampleTrack {
            let fixture = try XCTUnwrap(Bundle(for: SheetEnvironmentTests.self).url(forResource: "sample", withExtension: "mp3"))
            try FileManager.default.copyItem(at: fixture, to: docs.appendingPathComponent("sample.mp3"))
        }
        let library = LibraryStore(directory: state, documentsURL: docs, reader: StubReader(),
                                   artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")))
        if withSampleTrack {
            await library.rescan()
        }
        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        let player = PlayerController(library: library, queues: queues, history: history, engine: AudioEngine(),
                                      settingsURL: state.appendingPathComponent("settings.json"))
        defer { player.shutdown() }
        if withSampleTrack, let id = library.tracks.first?.id {
            player.play([id], startAt: 0)
            player.pause()
        }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        let shown = expectation(description: "sheet appeared")
        let root: AnyView
        if let sheet {
            let content = AnyView(sheet.onAppear { shown.fulfill() })
            if attachEmptyHostEnvironment {
                root = AnyView(SheetHost(content: content, delay: 0))
            } else {
                root = AnyView(SheetHost(content: outer ? AnyView(SheetHost(content: content, delay: 600)) : content, delay: 0))
            }
        } else {
            root = AnyView(RootView(showPlayer: showPlayer, layout: layout).onAppear { shown.fulfill() })
        }
        let hosted = attachEmptyHostEnvironment
            ? root
            : AnyView(root.environment(library).environment(queues).environment(player).environment(history))
        window.rootViewController = UIHostingController(rootView: hosted)
        window.makeKeyAndVisible()
        await fulfillment(of: [shown], timeout: 5)
        try await Task.sleep(for: .milliseconds(1000))
        window.isHidden = true
    }

    private func presentSongInfoViaHost(layout: AppLayout, showPlayer: Bool) async throws {
        let state = makeTempDir()
        let docs = makeTempDir()
        let fixture = try XCTUnwrap(Bundle(for: SheetEnvironmentTests.self).url(forResource: "sample", withExtension: "mp3"))
        try FileManager.default.copyItem(at: fixture, to: docs.appendingPathComponent("sample.mp3"))
        let library = LibraryStore(directory: state, documentsURL: docs, reader: StubReader(),
                                   artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")))
        await library.rescan()
        let trackID = try XCTUnwrap(library.tracks.first?.id)
        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        let player = PlayerController(library: library, queues: queues, history: history, engine: AudioEngine(),
                                      settingsURL: state.appendingPathComponent("settings.json"))
        defer { player.shutdown() }
        player.play([trackID], startAt: 0)
        player.pause()

        let navigation = AppNavigation()
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        let shown = expectation(description: "song info sheet appeared")
        let root = SongInfoTriggerHarness(
            trackID: trackID,
            layout: layout,
            showPlayer: showPlayer,
            onTriggerReady: { prompt in
                prompt.show(trackID, library: library, history: history, navigation: navigation)
                shown.fulfill()
            }
        )
        .environment(library)
        .environment(queues)
        .environment(player)
        .environment(history)
        .environment(\.appNavigation, navigation)

        window.rootViewController = UIHostingController(rootView: root)
        window.makeKeyAndVisible()
        await fulfillment(of: [shown], timeout: 5)
        try await Task.sleep(for: .milliseconds(800))
        window.isHidden = true
    }

    func testPlayerSheetOpensFromTheRootView() async throws { try await present(nil as EmptyView?, over: false) }
    func testSidebarLayoutOpens() async throws {
        try await present(nil as EmptyView?, over: false, layout: .sidebar, showPlayer: false)
    }
    func testPlayerOpensFromTheSidebarLayout() async throws {
        try await present(nil as EmptyView?, over: false, layout: .sidebar)
    }
    func testQueueSheetOpensOverThePlayer() async throws { try await present(QueueSheet(), over: true) }
    func testLyricsSheetOpensOverThePlayer() async throws { try await present(LyricsView(), over: true) }
    func testEqualizerSheetOpensOverThePlayer() async throws { try await present(EqualizerSheet(), over: true) }
    func testSleepTimerSheetOpensOverThePlayer() async throws { try await present(SleepTimerSheet(), over: true) }

    func testSongInfoSheetOpensWithoutParentEnvironmentOverRootAndPlayer() async throws {
        try await present(SongInfoSheet(trackID: "missing"), over: false, attachEmptyHostEnvironment: true)
        try await present(SongInfoSheet(trackID: "missing"), over: true, withSampleTrack: true)
    }

    func testSongInfoHostPresentsOverRootAndPlayerInTabsAndSidebarLayouts() async throws {
        try await presentSongInfoViaHost(layout: .tabs, showPlayer: false)
        try await presentSongInfoViaHost(layout: .tabs, showPlayer: true)
        try await presentSongInfoViaHost(layout: .sidebar, showPlayer: false)
        try await presentSongInfoViaHost(layout: .sidebar, showPlayer: true)
    }

    func testCloudSheetsOpenWithoutMissingEnvironmentCrash() async throws {
        let cloudRoot = LibraryRoot(
            id: UUID(),
            name: "Cloud Mix",
            kind: .cloudYouTube,
            bookmark: nil,
            sourceURL: "https://music.youtube.com/playlist?list=PL123",
            remoteID: "PL123"
        )
        try await present(AddCloudFolderSheet(), over: false, attachEmptyHostEnvironment: true)
        try await present(SaveCloudPlaylistSheet(root: cloudRoot), over: false, attachEmptyHostEnvironment: true)
        try await present(UpdateTrackedFolderSheet(root: cloudRoot), over: false, attachEmptyHostEnvironment: true)
    }

    func testPlayerBarAndMiniPlayerRenderAndControlPlayback() async throws {
        let state = makeTempDir()
        let docs = makeTempDir()
        let fixture = try XCTUnwrap(Bundle(for: SheetEnvironmentTests.self).url(forResource: "sample", withExtension: "mp3"))
        try FileManager.default.copyItem(at: fixture, to: docs.appendingPathComponent("a.mp3"))
        try FileManager.default.copyItem(at: fixture, to: docs.appendingPathComponent("b.mp3"))

        let library = LibraryStore(directory: state, documentsURL: docs, reader: StubReader(),
                                   artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")))
        await library.rescan()
        let ids = library.tracks.map(\.id)
        XCTAssertEqual(ids.count, 2)

        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        let player = PlayerController(library: library, queues: queues, history: history, engine: AudioEngine(),
                                      settingsURL: state.appendingPathComponent("settings.json"))
        defer { player.shutdown() }

        player.play(ids, startAt: 0)
        XCTAssertTrue(player.isPlaying)
        player.togglePlayPause()
        XCTAssertFalse(player.isPlaying)
        player.togglePlayPause()
        XCTAssertTrue(player.isPlaying)
        player.next()
        XCTAssertEqual(player.currentTrack?.id, ids[1])
        player.previous()
        XCTAssertEqual(player.currentTrack?.id, ids[0])

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        let rendered = expectation(description: "player controls rendered")
        let controls = VStack(spacing: 12) {
            MiniPlayerView(isGlassAccessory: false, onOpen: {})
            MiniPlayerView(isGlassAccessory: true, onOpen: {})
            PlayerBar(sidePanel: .constant(nil), onOpen: {})
        }
        .onAppear { rendered.fulfill() }
        .environment(library)
        .environment(queues)
        .environment(player)
        .environment(history)

        window.rootViewController = UIHostingController(rootView: controls)
        window.makeKeyAndVisible()
        await fulfillment(of: [rendered], timeout: 5)
        try await Task.sleep(for: .milliseconds(300))
        window.isHidden = true
    }
}

private struct SongInfoTriggerHarness: View {
    let trackID: String
    let layout: AppLayout
    let showPlayer: Bool
    let onTriggerReady: (SongInfoPrompt) -> Void

    var body: some View {
        RootView(showPlayer: showPlayer, layout: layout)
            .overlay {
                SongInfoProbe { prompt in
                    onTriggerReady(prompt)
                }
                .modifier(SongInfoHostModifier())
            }
    }
}

private struct SongInfoProbe: View {
    let onReady: (SongInfoPrompt) -> Void
    @Environment(SongInfoPrompt.self) private var prompt
    @State private var triggered = false

    var body: some View {
        Color.clear
            .frame(width: 1, height: 1)
            .task {
                guard !triggered else { return }
                triggered = true
                try? await Task.sleep(for: .milliseconds(250))
                onReady(prompt)
            }
    }
}

private struct SheetHost: View {
    let content: AnyView
    let delay: Int
    @State private var show = false

    var body: some View {
        Color.clear
            .sheet(isPresented: $show) { content }
            .task {
                try? await Task.sleep(for: .milliseconds(delay))
                show = true
            }
    }
}
