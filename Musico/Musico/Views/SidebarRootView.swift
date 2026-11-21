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
    @Environment(NewPlaylistPrompt.self) private var newPlaylist
    @State private var selection: SidebarItem? = .songs
    @State private var sidePanel: SidePanel?

    var body: some View {
        VStack(spacing: 0) {
            NavigationSplitView {
                sidebar
            } detail: {
                // A fresh stack per item, so one section's pushed pages never show under another.
                NavigationStack { detail }
                    .id(selection)
            }
            .inspector(isPresented: Binding(get: { sidePanel != nil }, set: { if !$0 { sidePanel = nil } })) {
                panel
                    .inspectorColumnWidth(min: 300, ideal: 360, max: 460)
                    .environment(library)
                    .environment(player)
                    .environment(queues)
                    .environment(newPlaylist)
            }
            PlayerBar(sidePanel: $sidePanel, onOpen: openPlayer)
        }
        .onChange(of: library.playlists.map(\.id)) { _, ids in
            if case .playlist(let id) = selection, !ids.contains(id) { selection = .playlists }
        }
    }

    private var sidebar: some View {
        List(selection: $selection) {
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
    }

    private func link(_ item: SidebarItem, _ title: String, _ icon: String) -> some View {
        NavigationLink(value: item) { Label(title, systemImage: icon) }
    }

    @ViewBuilder
    private var detail: some View {
        switch selection ?? .songs {
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
