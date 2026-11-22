import SwiftUI

enum SidebarItem: Hashable {
    case folders, songs, albums, artists, playlists, favourites, playlist(UUID), queues, stats
}

/// The main window on wide screens: sidebar, content, an optional queue or lyrics panel on the
/// right, and the player bar along the bottom.
struct SidebarRootView: View {
    let openPlayer: () -> Void
    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player
    @Environment(QueueStore.self) private var queues
    @Environment(PlayHistoryStore.self) private var history
    @Environment(NewPlaylistPrompt.self) private var newPlaylist
    @Environment(CloudExportPrompt.self) private var cloudExport
    @Environment(SongInfoPrompt.self) private var songInfo
    @Environment(\.appNavigation) private var navigation
    @Environment(\.languageManager) private var languageManager
    #if targetEnvironment(macCatalyst)
    @Environment(\.updateManager) private var updateManager
    #endif
    @State private var sidePanel: SidePanel?
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @State private var lastValidSelection: SidebarItem = .songs

    private var activeSelection: SidebarItem {
        navigation.sidebarSelection ?? lastValidSelection
    }

    var body: some View {
        VStack(spacing: 0) {
            splitView
            PlayerBar(
                sidePanel: $sidePanel,
                isQueueMainActive: activeSelection == .queues,
                onOpen: openPlayer
            )
        }
        .onAppear {
            lastValidSelection = navigation.sidebarSelection ?? .songs
        }
        .onChange(of: navigation.sidebarSelection) { oldValue, newValue in
            guard let newValue else {
                navigation.sidebarSelection = lastValidSelection
                return
            }
            lastValidSelection = newValue
            if oldValue != newValue {
                if oldValue == .songs && navigation.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    navigation.isSearchPresented = false
                }
                if !navigation.sidebarDetailPath.isEmpty {
                    navigation.sidebarDetailPath = NavigationPath()
                }
                if newValue == .queues && sidePanel == .queue {
                    withAnimation(.spring(response: 0.30, dampingFraction: 0.86)) {
                        sidePanel = nil
                    }
                }
            }
        }
        .onChange(of: library.playlists.map(\.id)) { _, ids in
            if case .playlist(let id) = activeSelection, !ids.contains(id) {
                navigation.selectSidebar(.playlists)
            }
        }
    }

    private var selectionBinding: Binding<SidebarItem?> {
        Binding(
            get: { activeSelection },
            set: { newValue in
                guard let newValue else { return }
                handleSelection(newValue)
            }
        )
    }

    private func handleSelection(_ item: SidebarItem) {
        lastValidSelection = item
        if activeSelection == item {
            if !navigation.sidebarDetailPath.isEmpty {
                withAnimation(.easeInOut(duration: 0.18)) {
                    navigation.sidebarDetailPath = NavigationPath()
                }
            }
            return
        }
        if activeSelection == .songs && navigation.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            navigation.isSearchPresented = false
        }
        if item == .queues && sidePanel == .queue {
            withAnimation(.spring(response: 0.30, dampingFraction: 0.86)) {
                sidePanel = nil
            }
        }
        navigation.selectSidebar(item)
    }

    private var splitView: some View {
        @Bindable var navigation = navigation
        return NavigationSplitView(columnVisibility: $columnVisibility) {
            sidebar
        } detail: {
            NavigationStack(path: $navigation.sidebarDetailPath) {
                detail
                    .id(activeSelection)
                    .transition(.opacity)
                    .animation(.easeInOut(duration: 0.18), value: activeSelection)
            }
        }
        .navigationSplitViewStyle(.balanced)
        .inspector(isPresented: Binding(get: { sidePanel != nil }, set: {
            let show = $0
            withAnimation(.spring(response: 0.30, dampingFraction: 0.86)) {
                if !show { sidePanel = nil }
            }
        })) {
            panel
                .inspectorColumnWidth(min: 300, ideal: 360, max: 460)
                .environment(library)
                .environment(player)
                .environment(queues)
                .environment(history)
                .environment(newPlaylist)
                .environment(cloudExport)
                .environment(songInfo)
                .environment(\.appNavigation, navigation)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
        }
    }

    private var sidebar: some View {
        VStack(spacing: 0) {
            List(selection: selectionBinding) {
                link(.folders, L10n.tr("Folders"), "folder")
                Section(L10n.tr("Library")) {
                    link(.songs, L10n.tr("Songs"), "music.note")
                    link(.albums, L10n.tr("Albums"), "square.stack")
                    link(.artists, L10n.tr("Artists"), "music.mic")
                }
                Section {
                    link(.playlists, L10n.tr("All Playlists"), "square.grid.2x2")
                    link(.favourites, L10n.tr("Favourites"), "heart", badgeCount: library.favourites.count)
                    ForEach(library.playlists) { playlist in
                        link(.playlist(playlist.id), playlist.name, "music.note.list", badgeCount: playlist.trackIDs.count)
                            .contextMenu {
                                TrackMenuItems(ids: playlist.trackIDs)
                                Divider()
                                Button(L10n.tr("Delete"), systemImage: "trash", role: .destructive) { library.deletePlaylist(playlist.id) }
                            }
                    }
                } header: {
                    HStack {
                        Text(L10n.tr("Playlists"))
                        Spacer()
                        Button {
                            newPlaylist.create = { [library] name in library.createPlaylist(name: name) }
                        } label: {
                            Image(systemName: "plus")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .frame(width: 22, height: 22)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .help(L10n.tr("New Playlist"))
                        .accessibilityLabel(L10n.tr("New Playlist"))
                    }
                }
                Section {
                    link(.queues, L10n.tr("Queues"), "list.bullet")
                    link(.stats, L10n.tr("Stats"), "chart.bar")
                }
            }
            .listStyle(.sidebar)
            #if targetEnvironment(macCatalyst)
            Divider()
            sidebarFooter
            #endif
        }
        .navigationTitle("Musico")
        .navigationSplitViewColumnWidth(min: 204, ideal: 232, max: 288)
        #if targetEnvironment(macCatalyst)
        .sheet(isPresented: Binding(
            get: { updateManager.isSheetPresented },
            set: { updateManager.isSheetPresented = $0 }
        )) {
            UpdateSheet()
                .environment(\.updateManager, updateManager)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
        }
        #endif
    }

    #if targetEnvironment(macCatalyst)
    private var sidebarFooter: some View {
        VStack(spacing: 4) {
            Button {
                updateManager.presentManualCheck()
            } label: {
                HStack(spacing: 8) {
                    Label(L10n.tr("Check for Updates"), systemImage: "arrow.down.circle")
                        .lineLimit(1)
                    Spacer()
                    if updateManager.hasAvailableUpdate {
                        Circle()
                            .fill(Color.accentColor)
                            .frame(width: 8, height: 8)
                            .accessibilityLabel(L10n.tr("Update available"))
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Menu {
                ForEach(AppLanguage.allCases) { lang in
                    Button {
                        languageManager.setLanguage(lang)
                    } label: {
                        if languageManager.selection == lang {
                            Label(lang.displayName, systemImage: "checkmark")
                        } else {
                            Text(lang.displayName)
                        }
                    }
                }
            } label: {
                HStack(spacing: 8) {
                    Label(L10n.tr("Language"), systemImage: "globe")
                        .lineLimit(1)
                    Spacer()
                    Text(languageManager.selection.displayName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .font(.subheadline)
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
    }
    #endif

    private func link(_ item: SidebarItem, _ title: String, _ icon: String, badgeCount: Int? = nil) -> some View {
        NavigationLink(value: item) {
            HStack(spacing: 8) {
                Label(title, systemImage: icon)
                    .lineLimit(1)
                if let badgeCount, badgeCount > 0 {
                    Spacer(minLength: 4)
                    Text("\(badgeCount)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        }
        .simultaneousGesture(TapGesture().onEnded {
            if activeSelection == item && !navigation.sidebarDetailPath.isEmpty {
                withAnimation(.easeInOut(duration: 0.18)) {
                    navigation.sidebarDetailPath = NavigationPath()
                }
            }
        })
    }

    @ViewBuilder
    private var detail: some View {
        switch activeSelection {
        case .folders: FoldersView()
        case .songs: SongsView()
        case .albums: AlbumsView()
        case .artists: ArtistsView()
        case .playlists: PlaylistsView()
        case .favourites: PlaylistDetailView(ref: .favourites)
        case .playlist(let id): PlaylistDetailView(ref: .user(id))
        case .queues: QueuesView()
        case .stats: StatsView()
        }
    }

    @ViewBuilder
    private var panel: some View {
        let closePanel = {
            withAnimation(.spring(response: 0.30, dampingFraction: 0.86)) {
                sidePanel = nil
            }
        }
        switch sidePanel {
        case .lyrics: LyricsView(onDone: closePanel)
        case .queue, nil:
            NavigationStack {
                QueuesView(embeddedInInspector: true, onDone: closePanel)
                    .navigationBarTitleDisplayMode(.inline)
            }
        }
    }
}
