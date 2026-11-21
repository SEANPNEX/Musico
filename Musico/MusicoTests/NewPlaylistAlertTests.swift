import XCTest
import SwiftUI
@testable import Musico

@MainActor
final class NewPlaylistAlertTests: XCTestCase {
    /// The queue page opens as a sheet over the player sheet with the prompt host at its root.
    /// On the Mac that arrangement evaluated the host with an empty environment, and reading a store there crashed.
    func testPromptHostAtTheRootOfASheetOverASheetDoesNotStopTheApp() async throws {
        let state = makeTempDir()
        let library = LibraryStore(directory: state, documentsURL: makeTempDir(), reader: StubReader(),
                                   artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        let shown = expectation(description: "inner sheet content appeared")
        window.rootViewController = UIHostingController(rootView: OuterSheetHost { shown.fulfill() }.environment(library))
        window.makeKeyAndVisible()
        await fulfillment(of: [shown], timeout: 5)
        window.isHidden = true
    }
}

private struct OuterSheetHost: View {
    let onInnerAppear: () -> Void
    @State private var show = false

    var body: some View {
        Color.clear
            .sheet(isPresented: $show) { InnerSheetHost(onInnerAppear: onInnerAppear) }
            .task { show = true }
    }
}

private struct InnerSheetHost: View {
    let onInnerAppear: () -> Void
    @State private var show = false

    var body: some View {
        Text("Player")
            .sheet(isPresented: $show) {
                Text("Queue").onAppear(perform: onInnerAppear).modifier(NewPlaylistAlert())
            }
            .task {
                try? await Task.sleep(for: .milliseconds(600))
                show = true
            }
    }
}
