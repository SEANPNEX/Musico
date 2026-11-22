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
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
        }
    }

    private var sidebar: some View {
        @Bindable var navigation = navigation
        return List(selection: $navigation.sidebarSelection) {
            link(.folders, L10n.tr("Folders"), "folder")
            Section(L10n.tr("Library")) {
                link(.songs, L10n.tr("Songs"), "music.note")
                link(.albums, L10n.tr("Albums"), "square.stack")
                link(.artists, L10n.tr("Artists"), "music.mic")
            }
            Section(L10n.tr("Playlists")) {
                link(.playlists, L10n.tr("All Playlists"), "square.grid.2x2")
                link(.favourites, L10n.tr("Favourites"), "heart")
                ForEach(library.playlists) { playlist in
                    link(.playlist(playlist.id), playlist.name, "music.note.list")
                        .contextMenu {
                            TrackMenuItems(ids: playlist.trackIDs)
                            Divider()
                            Button(L10n.tr("Delete"), systemImage: "trash", role: .destructive) { library.deletePlaylist(playlist.id) }
                        }
                }
                Button(L10n.tr("New Playlist"), systemImage: "plus") {
                    newPlaylist.create = { [library] name in library.createPlaylist(name: name) }
                }
                .foregroundStyle(.secondary)
            }
            Section {
                link(.queues, L10n.tr("Queues"), "list.bullet")
                link(.stats, L10n.tr("Stats"), "chart.bar")
            }
            #if targetEnvironment(macCatalyst)
            Section {
                Button {
                    updateManager.presentManualCheck()
                } label: {
                    HStack(spacing: 8) {
                        Label(L10n.tr("Check for Updates"), systemImage: "arrow.down.circle")
                        Spacer()
                        if updateManager.hasAvailableUpdate {
                            Circle()
                                .fill(Color.accentColor)
                                .frame(width: 8, height: 8)
                                .accessibilityLabel(L10n.tr("Update available"))
                        }
                    }
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
                        Spacer()
                        Text(languageManager.selection.displayName)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            #endif
        }
        .listStyle(.sidebar)
        .navigationTitle("Musico")
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
