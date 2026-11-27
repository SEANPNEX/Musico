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
    @Environment(PlayHistoryStore.self) private var history
    @Environment(\.appNavigation) private var navigation
    @Environment(\.downloadManager) private var downloadManager
    @Environment(\.extensionStore) private var extensionStore
    @Environment(\.lyricsStore) private var lyricsStore
    @Environment(\.languageManager) private var languageManager
    @Environment(\.themeManager) private var themeManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var localShowPlayer: Bool
    @State private var didApplyInitialPlayerState = false
    private let fixedLayout: AppLayout?
    private let initialShowPlayer: Bool

    /// - Parameters:
    ///   - showPlayer: open with the full player already showing (used by tests).
    ///   - layout: use this layout whatever the window size (used by tests).
    init(showPlayer: Bool = false, layout: AppLayout? = nil) {
        _localShowPlayer = State(initialValue: showPlayer)
        initialShowPlayer = showPlayer
        fixedLayout = layout
    }

    var body: some View {
        @Bindable var player = player
        GeometryReader { proxy in
            let layout = fixedLayout ?? AppLayout.resolve(width: proxy.size.width, compactWidth: sizeClass == .compact,
                                                          phone: UIDevice.current.userInterfaceIdiom == .phone)
            ZStack {
                if layout == .tabs {
                    ThemeBackdropView()
                }
                Group {
                    switch layout {
                    case .tabs:
                        tabs
                    case .sidebar:
                        SidebarRootView {
                            withAnimation(reduceMotion ? nil : .spring(response: 0.36, dampingFraction: 0.88)) {
                                localShowPlayer = true
                                navigation.showPlayer = true
                            }
                        }
                    }
                }
                .opacity(layout == .sidebar && localShowPlayer ? 0 : 1)
                .allowsHitTesting(!(layout == .sidebar && localShowPlayer))
                .accessibilityHidden(layout == .sidebar && localShowPlayer)
                if layout == .sidebar && localShowPlayer {
                    fullPlayerView {
                        withAnimation(reduceMotion ? nil : .spring(response: 0.36, dampingFraction: 0.88)) {
                            localShowPlayer = false
                            navigation.showPlayer = false
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background {
                        if !themeManager.hasCustomBackdrop {
                            Color(.systemBackground)
                        }
                    }
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                    .zIndex(1)
                }
                if !(layout == .sidebar && localShowPlayer) {
                    let toastPadding: CGFloat = layout == .sidebar
                        ? 96
                        : (player.currentTrack == nil ? 64 : 124)
                    ToastOverlayView(bottomPadding: toastPadding)
                        .zIndex(2)
                }
                if navigation.showCommandPalette {
                    Color.black.opacity(0.28)
                        .ignoresSafeArea()
                        .contentShape(Rectangle())
                        .onTapGesture {
                            navigation.showCommandPalette = false
                        }
                        .zIndex(4)

                    CommandPaletteView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .padding(.top, 72)
                        .transition(reduceMotion ? .opacity : .scale(scale: 0.96, anchor: .top).combined(with: .opacity))
                        .zIndex(5)
                }
            }
            .animation(reduceMotion ? nil : .spring(response: 0.36, dampingFraction: 0.88), value: localShowPlayer && layout == .sidebar)
            .animation(reduceMotion ? nil : .spring(response: 0.24, dampingFraction: 0.88), value: navigation.showCommandPalette)
            // The full player is a sheet over the tabs and an in-window overlay over the sidebar layout.
            .sheet(isPresented: playerShown(when: layout == .tabs)) {
                fullPlayerView()
                    .presentationDragIndicator(.visible)
            }
        }
        .background {
            #if targetEnvironment(macCatalyst)
            MacWindowTransparencyApplier(
                isTranslucent: themeManager.isWindowTranslucent,
                hasCustomSidebarColor: themeManager.activeSidebarBackgroundColor != nil
            )
                .allowsHitTesting(false)
            #endif
        }
        .applyTheme(themeManager)
        .onAppear {
            if !didApplyInitialPlayerState {
                didApplyInitialPlayerState = true
                if initialShowPlayer {
                    navigation.showPlayer = true
                }
            }
        }
        .onChange(of: navigation.showPlayer) { _, newValue in
            localShowPlayer = newValue
        }
        .modifier(NewPlaylistAlert())
        .modifier(CloudExportHostModifier())
        .modifier(SongInfoHostModifier())
        .modifier(DeleteConfirmHostModifier())
        .alert(L10n.tr("Playback Problem"), isPresented: Binding(get: { player.errorMessage != nil }, set: { if !$0 { player.errorMessage = nil } })) {
            Button(L10n.tr("OK"), role: .cancel) {}
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
        @Bindable var navigation = navigation
        let baseTabs = TabView(selection: $navigation.selectedTab) {
            Tab(L10n.tr("Folders"), systemImage: "folder", value: AppTab.folders) {
                NavigationStack(path: $navigation.foldersPath) { FoldersView().background { ThemeBackdropView() } }
            }
            Tab(L10n.tr("Library"), systemImage: "music.note.list", value: AppTab.library) {
                NavigationStack(path: $navigation.libraryPath) { LibraryView().background { ThemeBackdropView() } }
            }
            Tab(L10n.tr("Queues"), systemImage: "list.bullet", value: AppTab.queues) {
                NavigationStack { QueuesView().background { ThemeBackdropView() } }
            }
            Tab(L10n.tr("Stats"), systemImage: "chart.bar", value: AppTab.stats) {
                NavigationStack { StatsView().background { ThemeBackdropView() } }
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
        @Bindable var navigation = navigation
        return TabView(selection: $navigation.selectedTab) {
            tab(L10n.tr("Folders"), "folder", tag: .folders, path: $navigation.foldersPath) { FoldersView() }
            tab(L10n.tr("Library"), "music.note.list", tag: .library, path: $navigation.libraryPath) { LibraryView() }
            tab(L10n.tr("Queues"), "list.bullet", tag: .queues) { QueuesView() }
            tab(L10n.tr("Stats"), "chart.bar", tag: .stats) { StatsView() }
        }
    }

    private func playerShown(when active: Bool) -> Binding<Bool> {
        Binding(
            get: { localShowPlayer && active },
            set: { newValue in
                localShowPlayer = newValue
                navigation.showPlayer = newValue
            }
        )
    }

    // The stores are handed over explicitly: on the Mac the player is first evaluated before it
    // inherits them from this view, and reading a missing store stops the app.
    private func fullPlayerView(onClose: (() -> Void)? = nil) -> some View {
        FullPlayerView(onClose: onClose)
            .environment(player)
            .environment(library)
            .environment(queues)
            .environment(history)
            .environment(\.appNavigation, navigation)
            .environment(\.downloadManager, downloadManager)
            .environment(\.extensionStore, extensionStore)
            .environment(\.lyricsStore, lyricsStore)
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
    }

    private func miniPlayer(isGlassAccessory: Bool) -> some View {
        let open = {
            localShowPlayer = true
            navigation.showPlayer = true
        }
        return MiniPlayerView(isGlassAccessory: isGlassAccessory, onOpen: open)
    }

    private func tab(_ title: String, _ icon: String, tag: AppTab, path: Binding<NavigationPath>? = nil, @ViewBuilder content: () -> some View) -> some View {
        Group {
            if let path {
                NavigationStack(path: path) { content().background { ThemeBackdropView() } }
            } else {
                NavigationStack { content().background { ThemeBackdropView() } }
            }
        }
            // The inset below does not reach lists inside the navigation stack, so their last
            // rows would stay hidden behind the mini player without this margin.
            .contentMargins(.bottom, player.currentTrack == nil ? 0 : MiniPlayerView.height, for: .scrollContent)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if player.currentTrack != nil {
                    miniPlayer(isGlassAccessory: false)
                }
            }
            .tabItem { Label(title, systemImage: icon) }
            .tag(tag)
    }
}
