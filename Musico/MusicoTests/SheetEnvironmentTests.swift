import XCTest
import SwiftUI
@testable import Musico

/// On the Mac a sheet's top-level view can be evaluated before the app's stores reach it.
/// These show each sheet the way the app does, with the stores set on the presenting view.
@MainActor
final class SheetEnvironmentTests: XCTestCase {
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

    private func tearDownWindow(_ window: UIWindow) {
        if window.rootViewController?.presentedViewController != nil {
            window.rootViewController?.dismiss(animated: false)
        }
        window.isHidden = true
        window.rootViewController = nil
    }

    /// `sheet` nil shows the app's own root view in `layout`, with the full player open if `player`.
    private func present(
        _ sheet: (some View)?,
        over outer: Bool,
        layout: AppLayout = .tabs,
        showPlayer: Bool = true,
        withSampleTrack: Bool = false,
        attachEmptyHostEnvironment: Bool = false
    ) async throws {
        var player: PlayerController?
        defer { player?.shutdown() }

        let label = sheet.map { String(describing: type(of: $0)) } ?? "RootView"
        let shown = expectation(description: "sheet appeared: \(label)")
        var didFulfill = false
        let markShown = {
            guard !didFulfill else { return }
            didFulfill = true
            shown.fulfill()
        }

        let root: AnyView
        if let sheet {
            let content = AnyView(sheet.onAppear(perform: markShown))
            if attachEmptyHostEnvironment {
                root = AnyView(SheetHost(content: content, delay: 20))
            } else {
                root = AnyView(SheetHost(content: outer ? AnyView(SheetHost(content: content, delay: 100)) : content, delay: 20))
            }
        } else {
            root = AnyView(RootView(showPlayer: showPlayer, layout: layout).onAppear(perform: markShown))
        }

        let hosted: AnyView
        if attachEmptyHostEnvironment {
            hosted = root
        } else {
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
            let createdPlayer = PlayerController(library: library, queues: queues, history: history, engine: AudioEngine(),
                                                 settingsURL: state.appendingPathComponent("settings.json"))
            player = createdPlayer
            if withSampleTrack, let id = library.tracks.first?.id {
                createdPlayer.play([id], startAt: 0)
                createdPlayer.pause()
            }
            hosted = AnyView(root.environment(library).environment(queues).environment(createdPlayer).environment(history))
        }

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: hosted)
        window.makeKeyAndVisible()
        defer { tearDownWindow(window) }
        await fulfillment(of: [shown], timeout: 15)
        try await Task.sleep(for: .milliseconds(attachEmptyHostEnvironment ? 80 : 200))
        tearDownWindow(window)
        try await Task.sleep(for: .milliseconds(20))
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
        var didFulfill = false
        let root = SongInfoTriggerHarness(
            trackID: trackID,
            layout: layout,
            showPlayer: showPlayer,
            onTriggerReady: { prompt in
                prompt.show(trackID, library: library, history: history, navigation: navigation)
                guard !didFulfill else { return }
                didFulfill = true
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
        defer { tearDownWindow(window) }
        await fulfillment(of: [shown], timeout: 15)
        try await Task.sleep(for: .milliseconds(150))
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
    func testSettingsSheetAndEmbeddedPageRenderCleanly() async throws {
        try await present(SettingsView(isSheet: true), over: false, attachEmptyHostEnvironment: true)
        try await present(SettingsView(isSheet: false), over: false, attachEmptyHostEnvironment: true)
        try await present(SettingsView(isSheet: true), over: false)
        try await present(NavigationStack { SettingsView(isSheet: false) }, over: false)
    }

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
            kind: .cloudGoogleDrive,
            bookmark: nil,
            sourceURL: "https://drive.google.com/drive/folders/fld123",
            remoteID: "fld123"
        )
        try await present(AddCloudFolderSheet(), over: false, attachEmptyHostEnvironment: true)
        try await present(SaveCloudPlaylistSheet(root: cloudRoot), over: false, attachEmptyHostEnvironment: true)
        try await present(UpdateTrackedFolderSheet(root: cloudRoot), over: false, attachEmptyHostEnvironment: true)
        try await present(UpdateTrackedFolderSheet(root: cloudRoot, subfolderPath: "Sub", subfolderName: "Sub"), over: false, attachEmptyHostEnvironment: true)
        try await present(EditCloudTrackingSheet(root: cloudRoot), over: false, attachEmptyHostEnvironment: true)
        try await present(EditCloudTrackingSheet(root: cloudRoot, path: "Sub", folderName: "Sub"), over: false, attachEmptyHostEnvironment: true)
        try await present(DownloadsListView(), over: false, attachEmptyHostEnvironment: true)
        try await present(ExtensionsView(), over: false, attachEmptyHostEnvironment: true)
        let sampleExt = InstalledExtension(
            manifest: ExtensionManifest(
                apiVersion: 1,
                id: "com.test.sheet",
                name: "Sheet Sample",
                version: "1.0.0",
                capabilities: [.playlist, .stream],
                urlPatterns: ["^https://example\\\\.com/.+"],
                permissions: ExtensionPermissions(hosts: ["example.com"])
            ),
            state: ExtensionState(),
            directoryURL: makeTempDir(),
            iconData: nil,
            logs: []
        )
        try await present(ExtensionPermissionReviewSheet(extensionItem: sampleExt), over: false, attachEmptyHostEnvironment: true)
        try await present(ExtensionDetailView(extensionID: sampleExt.id), over: false, attachEmptyHostEnvironment: true)
        let qrExt = InstalledExtension(
            manifest: ExtensionManifest(
                apiVersion: 1,
                id: "com.test.sheet.qr",
                name: "QR Sample",
                version: "1.0.0",
                capabilities: [.playlist, .stream, .qrLogin],
                urlPatterns: ["^https://example\\.com/.+"],
                permissions: ExtensionPermissions(hosts: ["example.com"])
            ),
            state: ExtensionState(),
            directoryURL: makeTempDir(),
            iconData: nil,
            logs: []
        )
        try await present(ExtensionQRLoginSheet(extensionItem: qrExt), over: false, attachEmptyHostEnvironment: true)
        try await present(
            CloudCollectionPreviewSheet(
                extensionDescriptor: CloudSearchExtensionDescriptor(
                    id: "com.test.sheet",
                    providerID: "ext.com.test.sheet",
                    name: "Sheet Sample",
                    badgeLabel: "Sheet",
                    searchKinds: [.album, .playlist],
                    supportsStream: true,
                    supportsCollection: true,
                    supportsCookie: true
                ),
                item: CloudSearchResult(
                    extensionID: "com.test.sheet",
                    kind: .album,
                    remoteId: "alb-1",
                    title: "Preview Sheet Album",
                    sourceUrl: "https://example.com/album/alb-1"
                )
            ),
            over: false,
            attachEmptyHostEnvironment: true
        )
        try await present(AddSongsToPlaylistSheet(playlistID: UUID()), over: false, attachEmptyHostEnvironment: true)
        try await present(BatchTagEditSheet(trackIDs: []), over: false, attachEmptyHostEnvironment: true)
        try await present(
            NamePromptSheet(
                title: "New Playlist",
                subtitle: "Give your playlist a name.",
                placeholder: "Playlist name",
                confirmLabel: "Create",
                systemImage: "music.note.list",
                badgeText: "Includes 3 songs",
                onConfirm: { _ in }
            ),
            over: false,
            attachEmptyHostEnvironment: true
        )
    }

    func testPlaylistAndQueueViewsRenderInSidebarAndDetail() async throws {
        let state = makeTempDir()
        let docs = makeTempDir()
        let fixture = try XCTUnwrap(Bundle(for: SheetEnvironmentTests.self).url(forResource: "sample", withExtension: "mp3"))
        try FileManager.default.copyItem(at: fixture, to: docs.appendingPathComponent("a.mp3"))
        try FileManager.default.copyItem(at: fixture, to: docs.appendingPathComponent("b.mp3"))

        let library = LibraryStore(directory: state, documentsURL: docs, reader: StubReader(),
                                   artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")))
        await library.rescan()
        let ids = library.tracks.map(\.id)
        let playlistID = library.createPlaylist(name: "Weekend Mix")
        library.add(ids, toPlaylist: playlistID)
        if let first = ids.first {
            library.toggleFavourite(first)
        }

        let queues = QueueStore(directory: state)
        let extraQueue = queues.addQueue(name: "Queue 2")
        let history = PlayHistoryStore(directory: state)
        if let first = ids.first {
            history.recordPlay(first)
        }
        let player = PlayerController(library: library, queues: queues, history: history, engine: AudioEngine(),
                                      settingsURL: state.appendingPathComponent("settings.json"))
        defer { player.shutdown() }
        player.addToQueue(ids, queue: extraQueue)

        let firstAlbum = try XCTUnwrap(library.albums.first)
        let firstArtist = try XCTUnwrap(library.artists.first)

        let viewsToMount: [(String, AnyView)] = [
            ("PlaylistsView", AnyView(NavigationStack { PlaylistsView() })),
            ("PlaylistDetailView.favourites", AnyView(NavigationStack { PlaylistDetailView(ref: .favourites) })),
            ("PlaylistDetailView.user", AnyView(NavigationStack { PlaylistDetailView(ref: .user(playlistID)) })),
            ("QueuesView", AnyView(NavigationStack { QueuesView() })),
            ("SidebarRootView", AnyView(SidebarRootView(openPlayer: {}))),
            ("CommandPaletteView", AnyView(CommandPaletteView())),
            ("CompactMiniWindowView", AnyView(CompactMiniWindowView())),
            ("StatsView", AnyView(NavigationStack { StatsView() })),
            ("ArtistsView", AnyView(NavigationStack { ArtistsView() })),
            ("ArtistDetailView", AnyView(NavigationStack { ArtistDetailView(artist: firstArtist) })),
            ("AlbumDetailView", AnyView(NavigationStack { AlbumDetailView(album: firstAlbum) })),
            ("SkeletonViews", AnyView(List { SkeletonTrackListPlaceholder(count: 3) }))
        ]

        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        for (name, view) in viewsToMount {
            let window = UIWindow(windowScene: scene)
            let rendered = expectation(description: "rendered \(name)")
            var didFulfill = false
            let harness = view
                .onAppear {
                    guard !didFulfill else { return }
                    didFulfill = true
                    rendered.fulfill()
                }
                .modifier(NewPlaylistAlert())
                .modifier(CloudExportHostModifier())
                .modifier(SongInfoHostModifier())
                .modifier(DeleteConfirmHostModifier())
                .environment(library)
                .environment(queues)
                .environment(player)
                .environment(history)

            window.rootViewController = UIHostingController(rootView: harness)
            window.makeKeyAndVisible()
            defer { tearDownWindow(window) }
            await fulfillment(of: [rendered], timeout: 15)
            try await Task.sleep(for: .milliseconds(80))
            tearDownWindow(window)
            try await Task.sleep(for: .milliseconds(20))
        }
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
        var didFulfill = false
        let controls = VStack(spacing: 12) {
            MiniPlayerView(isGlassAccessory: false, onOpen: {})
            MiniPlayerView(isGlassAccessory: true, onOpen: {})
            PlayerBar(sidePanel: .constant(nil), onOpen: {})
            PlayerBar(sidePanel: .constant(.queue), isQueueMainActive: true, onOpen: {})
            QueuesView(embeddedInInspector: true, onDone: {})
            FullPlayerView(onClose: {})
            ToastOverlayView()
            MarqueeText(text: "A Very Long Queue Name That Should Scroll Cleanly Across Narrow Screens", font: .footnote)
                .frame(width: 140)
        }
        .onAppear {
            guard !didFulfill else { return }
            didFulfill = true
            rendered.fulfill()
        }
        .modifier(NewPlaylistAlert())
        .modifier(CloudExportHostModifier())
        .modifier(SongInfoHostModifier())
        .modifier(DeleteConfirmHostModifier())
        .environment(library)
        .environment(queues)
        .environment(player)
        .environment(history)

        window.rootViewController = UIHostingController(rootView: controls)
        window.makeKeyAndVisible()
        defer { tearDownWindow(window) }
        await fulfillment(of: [rendered], timeout: 15)
        try await Task.sleep(for: .milliseconds(100))
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
                try? await Task.sleep(for: .milliseconds(60))
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
                if delay > 0 {
                    try? await Task.sleep(for: .milliseconds(delay))
                }
                show = true
            }
    }
}
