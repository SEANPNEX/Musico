import XCTest
import SwiftUI
@testable import Musico

/// On the Mac a sheet's top-level view can be evaluated before the app's stores reach it.
/// These show each sheet the way the app does, with the stores set on the presenting view.
@MainActor
final class SheetEnvironmentTests: XCTestCase {
    /// `sheet` nil shows the app's own root view in `layout`, with the full player open if `player`.
    private func present(_ sheet: (some View)?, over outer: Bool, layout: AppLayout = .tabs, showPlayer: Bool = true) async throws {
        let state = makeTempDir()
        let library = LibraryStore(directory: state, documentsURL: makeTempDir(), reader: StubReader(),
                                   artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")))
        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        let player = PlayerController(library: library, queues: queues, history: history, engine: AudioEngine(),
                                      settingsURL: state.appendingPathComponent("settings.json"))
        defer { player.shutdown() }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        let shown = expectation(description: "sheet appeared")
        let root: AnyView
        if let sheet {
            let content = AnyView(sheet.onAppear { shown.fulfill() })
            root = AnyView(SheetHost(content: outer ? AnyView(SheetHost(content: content, delay: 600)) : content, delay: 0))
        } else {
            root = AnyView(RootView(showPlayer: showPlayer, layout: layout).onAppear { shown.fulfill() })
        }
        let hosted = root.environment(library).environment(queues).environment(player).environment(history)
        window.rootViewController = UIHostingController(rootView: hosted)
        window.makeKeyAndVisible()
        await fulfillment(of: [shown], timeout: 5)
        try await Task.sleep(for: .milliseconds(1000))
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
