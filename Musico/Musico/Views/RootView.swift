import SwiftUI

func formatTime(_ time: TimeInterval) -> String {
    guard time.isFinite, time > 0 else { return "0:00" }
    let total = Int(time.rounded(.down))
    let (hours, minutes, seconds) = (total / 3600, (total % 3600) / 60, total % 60)
    return hours > 0 ? String(format: "%d:%02d:%02d", hours, minutes, seconds) : String(format: "%d:%02d", minutes, seconds)
}

struct RootView: View {
    @Environment(PlayerController.self) private var player
    @Environment(LibraryStore.self) private var library
    @Environment(QueueStore.self) private var queues
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var showPlayer: Bool
    @Namespace private var playerTransition
    private let fixedLayout: AppLayout?

    /// - Parameters:
    ///   - showPlayer: open with the full player already showing (used by tests).
    ///   - layout: use this layout whatever the window size (used by tests).
    init(showPlayer: Bool = false, layout: AppLayout? = nil) {
        _showPlayer = State(initialValue: showPlayer)
        fixedLayout = layout
    }

    var body: some View {
        @Bindable var player = player
        GeometryReader { proxy in
            let layout = fixedLayout ?? AppLayout.resolve(width: proxy.size.width, compactWidth: sizeClass == .compact,
                                                          phone: UIDevice.current.userInterfaceIdiom == .phone)
            Group {
                switch layout {
                case .tabs: tabs
                case .sidebar: SidebarRootView { showPlayer = true }
                }
            }
            // The full player is a sheet over the tabs and fills the window over the sidebar layout.
            .sheet(isPresented: playerShown(when: layout == .tabs)) {
                if #available(iOS 18.0, macCatalyst 18.0, *) {
                    fullPlayer
                        .navigationTransition(.zoom(sourceID: "nowPlaying", in: playerTransition))
                } else {
                    fullPlayer
                }
            }
            .fullScreenCover(isPresented: playerShown(when: layout == .sidebar)) { fullPlayer }
        }
        .modifier(NewPlaylistAlert())
        .modifier(CloudExportHostModifier())
        .alert("Playback Problem", isPresented: Binding(get: { player.errorMessage != nil }, set: { if !$0 { player.errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(player.errorMessage ?? "")
        }
    }

    @ViewBuilder
    private var tabs: some View {
        if #available(iOS 26.0, macCatalyst 26.0, *) {
            modernTabs
        } else {
            legacyTabs
        }
    }

    @available(iOS 26.0, macCatalyst 26.0, *)
    @ViewBuilder
    private var modernTabs: some View {
        let baseTabs = TabView {
            Tab("Folders", systemImage: "folder") {
                NavigationStack { FoldersView() }
            }
            Tab("Library", systemImage: "music.note.list") {
                NavigationStack { LibraryView() }
            }
            Tab("Queues", systemImage: "list.bullet") {
                NavigationStack { QueuesView() }
            }
            Tab("Stats", systemImage: "chart.bar") {
                NavigationStack { StatsView() }
            }
        }
        .tabBarMinimizeBehavior(.onScrollDown)

        if #available(iOS 26.1, macCatalyst 26.1, *) {
            baseTabs.tabViewBottomAccessory(isEnabled: player.currentTrack != nil) {
                miniPlayer(isGlassAccessory: true)
            }
        } else {
            baseTabs.tabViewBottomAccessory {
                if player.currentTrack != nil {
                    miniPlayer(isGlassAccessory: true)
                }
            }
        }
    }

    private var legacyTabs: some View {
        TabView {
            tab("Folders", "folder") { FoldersView() }
            tab("Library", "music.note.list") { LibraryView() }
            tab("Queues", "list.bullet") { QueuesView() }
            tab("Stats", "chart.bar") { StatsView() }
        }
    }

    private func playerShown(when active: Bool) -> Binding<Bool> {
        Binding(get: { showPlayer && active }, set: { showPlayer = $0 })
    }

    // The stores are handed over explicitly: on the Mac the player is first evaluated before it
    // inherits them from this view, and reading a missing store stops the app.
    private var fullPlayer: some View {
        FullPlayerView()
            .environment(player)
            .environment(library)
            .environment(queues)
    }

    @ViewBuilder
    private func miniPlayer(isGlassAccessory: Bool) -> some View {
        if #available(iOS 18.0, macCatalyst 18.0, *) {
            MiniPlayerView(isGlassAccessory: isGlassAccessory) { showPlayer = true }
                .matchedTransitionSource(id: "nowPlaying", in: playerTransition)
        } else {
            MiniPlayerView(isGlassAccessory: isGlassAccessory) { showPlayer = true }
        }
    }

    private func tab(_ title: String, _ icon: String, @ViewBuilder content: () -> some View) -> some View {
        NavigationStack { content() }
            // The inset below does not reach lists inside the navigation stack, so their last
            // rows would stay hidden behind the mini player without this margin.
            .contentMargins(.bottom, player.currentTrack == nil ? 0 : MiniPlayerView.height, for: .scrollContent)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if player.currentTrack != nil {
                    miniPlayer(isGlassAccessory: false)
                }
            }
            .tabItem { Label(title, systemImage: icon) }
    }
}
