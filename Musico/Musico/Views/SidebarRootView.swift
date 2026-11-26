import SwiftUI

enum SidebarItem: Hashable {
    case folders, extensions, songs, albums, artists, playlists, favourites, playlist(UUID), queues, stats, settings
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
    @Environment(DeleteItemPrompt.self) private var deletePrompt: DeleteItemPrompt?
    @Environment(\.appNavigation) private var navigation
    @Environment(\.downloadManager) private var downloadManager
    @Environment(\.extensionStore) private var extensionStore
    @Environment(\.lyricsStore) private var lyricsStore
    @Environment(\.languageManager) private var languageManager
    @Environment(\.themeManager) private var themeManager
    #if targetEnvironment(macCatalyst)
    @Environment(\.updateManager) private var updateManager
    #endif
    @State private var sidePanel: SidePanel?
    @State private var columnVisibility: NavigationSplitViewVisibility = .all
    @State private var lastValidSelection: SidebarItem = .queues
    @State private var showDownloadsSheet = false
    @State private var renamingPlaylist: Playlist?

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
        .background { ThemeBackdropView() }
        .onAppear {
            lastValidSelection = navigation.sidebarSelection ?? .queues
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

    private func handleSelection(_ item: SidebarItem) {
        lastValidSelection = item
        if activeSelection == item {
            if !navigation.sidebarDetailPath.isEmpty {
                navigation.sidebarDetailPath = NavigationPath()
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
                .background { ThemeBackdropView() }
        } detail: {
            NavigationStack(path: $navigation.sidebarDetailPath) {
                detail
                    .background { ThemeBackdropView() }
            }
        }
        .navigationSplitViewStyle(.balanced)
        .inspector(isPresented: Binding(get: { sidePanel != nil }, set: {
            let show = $0
            withAnimation(.spring(response: 0.30, dampingFraction: 0.86)) {
                if !show { sidePanel = nil }
            }
        })) {
            inspectorContent
        }
    }

    @ViewBuilder
    private var inspectorContent: some View {
        let base = panel
            .inspectorColumnWidth(min: 300, ideal: 360, max: 460)
            .background { ThemeBackdropView() }
            .environment(library)
            .environment(player)
            .environment(queues)
            .environment(history)
            .environment(newPlaylist)
            .environment(cloudExport)
            .environment(songInfo)
            .environment(\.appNavigation, navigation)
            .environment(\.downloadManager, downloadManager)
            .environment(\.extensionStore, extensionStore)
            .environment(\.lyricsStore, lyricsStore)
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
        if let deletePrompt {
            base.environment(deletePrompt)
        } else {
            base
        }
    }

    private var sidebar: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    sidebarRow(.queues, L10n.tr("Queues"), "list.bullet")
                    sidebarRow(.folders, L10n.tr("Folders"), "folder")

                    sidebarSectionHeader(L10n.tr("Library"))
                    sidebarRow(.songs, L10n.tr("Songs"), "music.note")
                    sidebarRow(.albums, L10n.tr("Albums"), "square.stack")
                    sidebarRow(.artists, L10n.tr("Artists"), "music.mic")

                    HStack(spacing: 6) {
                        Text(L10n.tr("Playlists"))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Spacer()
                        Button {
                            newPlaylist.songCount = nil
                            newPlaylist.create = { [library] name in library.createPlaylist(name: name) }
                        } label: {
                            Image(systemName: "plus")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .frame(width: 24, height: 24)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(SidebarFooterButtonStyle())
                        .help(L10n.tr("New Playlist"))
                        .accessibilityLabel(L10n.tr("New Playlist"))
                    }
                    .padding(.horizontal, 10)
                    .padding(.top, 12)
                    .padding(.bottom, 2)

                    sidebarRow(.playlists, L10n.tr("All Playlists"), "square.grid.2x2")
                    let favIDs = library.orderedFavourites(history: history).map(\.id)
                    sidebarRow(.favourites, L10n.tr("Favourites"), "heart", badgeCount: library.favourites.count)
                        .contextMenu {
                            if !favIDs.isEmpty {
                                Button(L10n.tr("Play"), systemImage: "play.fill") {
                                    player.playInNamedQueue(favIDs, name: L10n.tr("Favourites"), startAt: 0, shuffle: false)
                                }
                                Divider()
                                TrackMenuItems(ids: favIDs)
                            }
                        }
                    ForEach(library.playlists) { playlist in
                        let resolvedIDs = library.tracks(playlist.trackIDs).map(\.id)
                        sidebarRow(.playlist(playlist.id), playlist.name, "music.note.list", badgeCount: resolvedIDs.count)
                            .contextMenu {
                                if !resolvedIDs.isEmpty {
                                    Button(L10n.tr("Play"), systemImage: "play.fill") {
                                        player.playInNamedQueue(resolvedIDs, name: playlist.name, startAt: 0, shuffle: false)
                                    }
                                    Divider()
                                }
                                Button(L10n.tr("Rename…"), systemImage: "pencil") {
                                    renamingPlaylist = playlist
                                }
                                if !resolvedIDs.isEmpty {
                                    TrackMenuItems(ids: resolvedIDs)
                                }
                                Divider()
                                Button(L10n.tr("Delete"), systemImage: "trash", role: .destructive) {
                                    library.deletePlaylist(playlist.id)
                                }
                            }
                    }

                    Divider()
                        .padding(.vertical, 6)
                        .padding(.horizontal, 8)

                    sidebarRow(.stats, L10n.tr("Stats"), "chart.bar")
                    sidebarRow(.extensions, L10n.tr("Extensions"), "puzzlepiece.extension")
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
            }
            Divider()
            sidebarFooter
        }
        .navigationTitle("Musico")
        .navigationSplitViewColumnWidth(min: 204, ideal: 232, max: 288)
        .sheet(item: $renamingPlaylist) { playlist in
            NamePromptSheet(
                title: L10n.tr("Rename Playlist"),
                subtitle: L10n.tr("Choose a new name for this playlist."),
                placeholder: L10n.tr("Playlist name"),
                confirmLabel: L10n.tr("Rename"),
                systemImage: "pencil",
                initialName: playlist.name
            ) { trimmed in
                library.renamePlaylist(playlist.id, to: trimmed)
            }
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
        }
        .sheet(isPresented: $showDownloadsSheet) {
            DownloadsListView(downloadManager: downloadManager)
                .environment(\.downloadManager, downloadManager)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
                .applyTheme(themeManager)
        }
        #if targetEnvironment(macCatalyst)
        .sheet(isPresented: Binding(
            get: { updateManager.isSheetPresented },
            set: { updateManager.isSheetPresented = $0 }
        )) {
            UpdateSheet()
                .environment(\.updateManager, updateManager)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
                .applyTheme(themeManager)
        }
        #endif
    }

    private func sidebarSectionHeader(_ title: String) -> some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 10)
            .padding(.top, 12)
            .padding(.bottom, 2)
    }

    private func sidebarRow(_ item: SidebarItem, _ title: String, _ icon: String, badgeCount: Int? = nil) -> some View {
        SidebarRowButton(
            title: title,
            icon: icon,
            badgeCount: badgeCount,
            isSelected: activeSelection == item
        ) {
            handleSelection(item)
        }
    }

    private var sidebarFooter: some View {
        VStack(spacing: 4) {
            SidebarDownloadsWidget(downloadManager: downloadManager) {
                showDownloadsSheet = true
            }
            SidebarRowButton(
                title: L10n.tr("Settings"),
                icon: "gearshape",
                showsAccentDot: hasAvailableUpdate,
                isSelected: activeSelection == .settings
            ) {
                handleSelection(.settings)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 8)
    }

    private var hasAvailableUpdate: Bool {
        #if targetEnvironment(macCatalyst)
        updateManager.hasAvailableUpdate
        #else
        false
        #endif
    }

    @ViewBuilder
    private var detail: some View {
        switch activeSelection {
        case .folders: FoldersView()
        case .extensions: ExtensionsView()
        case .songs: SongsView()
        case .albums: AlbumsView()
        case .artists: ArtistsView()
        case .playlists: PlaylistsView()
        case .favourites: PlaylistDetailView(ref: .favourites)
        case .playlist(let id): PlaylistDetailView(ref: .user(id))
        case .queues: QueuesView()
        case .stats: StatsView()
        case .settings: SettingsView(isSheet: false)
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

private struct SidebarRowButton: View {
    let title: String
    let icon: String
    var badgeCount: Int? = nil
    var showsAccentDot: Bool = false
    let isSelected: Bool
    let action: () -> Void

    @Environment(\.themeManager) private var themeManager
    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.body)
                    .foregroundStyle(isSelected ? themeManager.accentColor : .secondary)
                    .frame(width: 20, alignment: .center)
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Spacer(minLength: 4)
                if showsAccentDot {
                    Circle()
                        .fill(themeManager.accentColor)
                        .frame(width: 8, height: 8)
                        .accessibilityLabel(L10n.tr("Update available"))
                } else if let badgeCount, badgeCount > 0 {
                    Text("\(badgeCount)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .buttonStyle(SidebarPillButtonStyle(isSelected: isSelected, isHovered: isHovered, accentColor: themeManager.accentColor))
        .onHover { isHovered = $0 }
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

private struct SidebarPillButtonStyle: ButtonStyle {
    let isSelected: Bool
    let isHovered: Bool
    let accentColor: Color

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                backgroundColor(isPressed: configuration.isPressed),
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )
            .scaleEffect(configuration.isPressed ? 0.99 : 1.0)
            .animation(.easeOut(duration: 0.1), value: configuration.isPressed)
    }

    private func backgroundColor(isPressed: Bool) -> Color {
        if isPressed {
            return isSelected ? accentColor.opacity(0.22) : Color.primary.opacity(0.10)
        }
        if isSelected {
            return accentColor.opacity(0.15)
        }
        if isHovered {
            return Color.primary.opacity(0.06)
        }
        return .clear
    }
}

private struct SidebarFooterButtonStyle: ButtonStyle {
    @State private var isHovered = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(
                configuration.isPressed
                    ? Color.primary.opacity(0.10)
                    : (isHovered ? Color.primary.opacity(0.06) : .clear),
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )
            .onHover { isHovered = $0 }
    }
}
