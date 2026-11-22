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
    #if targetEnvironment(macCatalyst)
    @Environment(\.updateManager) private var updateManager
    @State private var showUpdateSheet = false
    #endif
    @State private var sidePanel: SidePanel?

    var body: some View {
        Group {
            if #available(iOS 26.0, macCatalyst 26.0, *) {
                splitView
                    .safeAreaInset(edge: .bottom, spacing: 0) {
                        PlayerBar(sidePanel: $sidePanel, onOpen: openPlayer)
                    }
            } else {
                VStack(spacing: 0) {
                    splitView
                    PlayerBar(sidePanel: $sidePanel, onOpen: openPlayer)
                }
            }
        }
        .onChange(of: library.playlists.map(\.id)) { _, ids in
            if case .playlist(let id) = navigation.sidebarSelection, !ids.contains(id) {
                navigation.sidebarSelection = .playlists
            }
        }
    }

    private var splitView: some View {
        @Bindable var navigation = navigation
        return NavigationSplitView {
            sidebar
        } detail: {
            // A fresh stack per item, so one section's pushed pages never show under another.
            NavigationStack { detail }
                .id(navigation.sidebarSelection)
        }
        .inspector(isPresented: Binding(get: { sidePanel != nil }, set: { if !$0 { sidePanel = nil } })) {
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
        }
    }

    private var sidebar: some View {
        @Bindable var navigation = navigation
        return List(selection: $navigation.sidebarSelection) {
            link(.folders, "Folders", "folder")
            Section("Library") {
                link(.songs, "Songs", "music.note")
                link(.albums, "Albums", "square.stack")
                link(.artists, "Artists", "music.mic")
            }
            Section("Playlists") {
                link(.playlists, "All Playlists", "square.grid.2x2")
                link(.favourites, "Favourites", "heart")
                ForEach(library.playlists) { playlist in
                    link(.playlist(playlist.id), playlist.name, "music.note.list")
                        .contextMenu {
                            TrackMenuItems(ids: playlist.trackIDs)
                            Divider()
                            Button("Delete", systemImage: "trash", role: .destructive) { library.deletePlaylist(playlist.id) }
                        }
                }
                Button("New Playlist", systemImage: "plus") {
                    newPlaylist.create = { [library] name in library.createPlaylist(name: name) }
                }
                .foregroundStyle(.secondary)
            }
            Section {
                link(.queues, "Queues", "list.bullet")
                link(.stats, "Stats", "chart.bar")
            }
        }
        .listStyle(.sidebar)
        .navigationTitle("Musico")
        #if targetEnvironment(macCatalyst)
        .safeAreaInset(edge: .bottom) {
            updateFooter
        }
        .sheet(isPresented: $showUpdateSheet) {
            UpdateSheet()
                .environment(\.updateManager, updateManager)
        }
        #endif
    }

    #if targetEnvironment(macCatalyst)
    @ViewBuilder
    private var updateFooter: some View {
        let button = Button {
            showUpdateSheet = true
            switch updateManager.state {
            case .available, .downloading, .readyToInstall, .installing:
                break
            default:
                Task { await updateManager.checkForUpdates(manual: true) }
            }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "arrow.down.circle")
                Text("Check for Updates")
                Spacer()
                if updateManager.hasAvailableUpdate {
                    Circle()
                        .fill(Color.accentColor)
                        .frame(width: 8, height: 8)
                        .accessibilityLabel("Update available")
                }
            }
            .font(.subheadline)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.secondary)

        if #available(iOS 26.0, macCatalyst 26.0, *) {
            button
                .glassEffect(.regular.interactive(), in: Capsule())
                .padding(.horizontal, 12)
                .padding(.bottom, 8)
        } else {
            button
                .background(.bar)
        }
    }
    #endif

    private func link(_ item: SidebarItem, _ title: String, _ icon: String) -> some View {
        NavigationLink(value: item) { Label(title, systemImage: icon) }
    }

    @ViewBuilder
    private var detail: some View {
        switch navigation.sidebarSelection ?? .songs {
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
        switch sidePanel {
        case .lyrics: LyricsView(onDone: { sidePanel = nil })
        case .queue, nil:
            NavigationStack {
                QueuesView().navigationBarTitleDisplayMode(.inline)
            }
        }
    }
}
