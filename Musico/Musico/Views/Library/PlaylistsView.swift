import SwiftUI

enum PlaylistRef: Hashable {
    case favourites
    case user(UUID)
}

struct PlaylistsView: View {
    @Environment(LibraryStore.self) private var library
    @State private var showNew = false
    @State private var renaming: Playlist?
    @State private var name = ""

    var body: some View {
        List {
            NavigationLink(value: PlaylistRef.favourites) {
                row(L10n.tr("Favourites"), icon: "heart.fill", count: library.favourites.count)
            }
            ForEach(library.playlists) { playlist in
                NavigationLink(value: PlaylistRef.user(playlist.id)) {
                    row(playlist.name, icon: "music.note.list", count: playlist.trackIDs.count)
                }
                .swipeActions(edge: .trailing) {
                    Button(L10n.tr("Delete"), systemImage: "trash", role: .destructive) { library.deletePlaylist(playlist.id) }
                }
                .contextMenu {
                    Button(L10n.tr("Rename…"), systemImage: "pencil") {
                        name = playlist.name
                        renaming = playlist
                    }
                    Button(L10n.tr("Delete"), systemImage: "trash", role: .destructive) { library.deletePlaylist(playlist.id) }
                    TrackMenuItems(ids: playlist.trackIDs)
                }
            }
        }
        .navigationTitle(L10n.tr("Playlists"))
        #if targetEnvironment(macCatalyst)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .navigationDestination(for: PlaylistRef.self) { PlaylistDetailView(ref: $0) }
        .toolbar {
            Button(L10n.tr("New Playlist"), systemImage: "plus") {
                name = ""
                showNew = true
            }
        }
        .alert(L10n.tr("New Playlist"), isPresented: $showNew) {
            TextField(L10n.tr("Name"), text: $name)
            Button(L10n.tr("Cancel"), role: .cancel) {}
            Button(L10n.tr("Create")) {
                let trimmed = name.trimmingCharacters(in: .whitespaces)
                library.createPlaylist(name: trimmed.isEmpty ? L10n.tr("New Playlist") : trimmed)
            }
        }
        .alert(L10n.tr("Rename Playlist"), isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(L10n.tr("Name"), text: $name)
            Button(L10n.tr("Cancel"), role: .cancel) {}
            Button(L10n.tr("Rename")) {
                let trimmed = name.trimmingCharacters(in: .whitespaces)
                if let renaming, !trimmed.isEmpty { library.renamePlaylist(renaming.id, to: trimmed) }
            }
        }
    }

    private func row(_ title: String, icon: String, count: Int) -> some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(Color.accentColor).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(L10n.songCount(count)).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

struct PlaylistDetailView: View {
    let ref: PlaylistRef
    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player
    @Environment(PlayHistoryStore.self) private var history

    private var title: String {
        switch ref {
        case .favourites: L10n.tr("Favourites")
        case .user(let id): library.playlists.first { $0.id == id }?.name ?? L10n.tr("Playlist")
        }
    }

    private var tracks: [Track] {
        switch ref {
        case .favourites: library.orderedFavourites(history: history)
        case .user(let id): library.tracks(library.playlists.first { $0.id == id }?.trackIDs ?? [])
        }
    }

    private var currentSortOrder: TrackSortOrder {
        switch ref {
        case .favourites: library.favouritesSortOrder
        case .user(let id): library.playlists.first { $0.id == id }?.sortOrder ?? .custom
        }
    }

    var body: some View {
        let tracks = self.tracks
        List {
            if tracks.isEmpty {
                ContentUnavailableView(L10n.tr("Nothing Here Yet"), systemImage: "music.note.list",
                                       description: Text(L10n.tr("Touch and hold a song, album or folder to add it.")))
            } else {
                PlayShuffleButtons(ids: tracks.map(\.id))
            }
            if case .user(let id) = ref {
                // The same song may be in a playlist twice, so rows are identified by position.
                ForEach(Array(tracks.enumerated()), id: \.offset) { index, track in
                    ReorderableTrackRow(
                        track: track,
                        index: index,
                        scopeID: "playlist-\(id.uuidString)",
                        isAvailable: library.isAvailable(track),
                        onTap: { player.play(tracks.map(\.id), startAt: index) },
                        onMove: { from, to in
                            library.movePlaylistTracks(id, from: IndexSet(integer: from), to: to)
                        }
                    )
                    .contextMenu { TrackMenuItems(ids: [track.id], favouriteID: track.id) }
                }
                .onDelete { library.removeFromPlaylist(id, at: $0) }
                .onMove { library.movePlaylistTracks(id, from: $0, to: $1) }
            } else {
                ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                    ReorderableTrackRow(
                        track: track,
                        index: index,
                        scopeID: "favourites",
                        isAvailable: library.isAvailable(track),
                        onTap: { player.play(tracks.map(\.id), startAt: index) },
                        onMove: { from, to in
                            library.moveFavourites(currentTrackIDs: tracks.map(\.id), from: IndexSet(integer: from), to: to)
                        }
                    )
                    .contextMenu { TrackMenuItems(ids: [track.id], favouriteID: track.id) }
                }
                .onMove { source, destination in
                    library.moveFavourites(currentTrackIDs: tracks.map(\.id), from: source, to: destination)
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if !tracks.isEmpty {
                    TrackSortMenu(selection: currentSortOrder) { order in
                        switch ref {
                        case .favourites:
                            library.sortFavourites(by: order, history: history)
                        case .user(let id):
                            library.sortPlaylist(id, by: order, history: history)
                        }
                    }
                }
                if case .user = ref, !tracks.isEmpty { EditButton() }
            }
        }
    }
}
