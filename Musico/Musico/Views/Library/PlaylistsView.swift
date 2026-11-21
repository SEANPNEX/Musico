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
                row("Favourites", icon: "heart.fill", count: library.favourites.count)
            }
            ForEach(library.playlists) { playlist in
                NavigationLink(value: PlaylistRef.user(playlist.id)) {
                    row(playlist.name, icon: "music.note.list", count: playlist.trackIDs.count)
                }
                .swipeActions(edge: .trailing) {
                    Button("Delete", systemImage: "trash", role: .destructive) { library.deletePlaylist(playlist.id) }
                }
                .contextMenu {
                    Button("Rename…", systemImage: "pencil") {
                        name = playlist.name
                        renaming = playlist
                    }
                    Button("Delete", systemImage: "trash", role: .destructive) { library.deletePlaylist(playlist.id) }
                    TrackMenuItems(ids: playlist.trackIDs)
                }
            }
        }
        .navigationTitle("Playlists")
        .navigationDestination(for: PlaylistRef.self) { PlaylistDetailView(ref: $0) }
        .toolbar {
            Button("New Playlist", systemImage: "plus") {
                name = ""
                showNew = true
            }
        }
        .alert("New Playlist", isPresented: $showNew) {
            TextField("Name", text: $name)
            Button("Cancel", role: .cancel) {}
            Button("Create") {
                let trimmed = name.trimmingCharacters(in: .whitespaces)
                library.createPlaylist(name: trimmed.isEmpty ? "New Playlist" : trimmed)
            }
        }
        .alert("Rename Playlist", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $name)
            Button("Cancel", role: .cancel) {}
            Button("Rename") {
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
                Text("\(count) songs").font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }
}

struct PlaylistDetailView: View {
    let ref: PlaylistRef
    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player

    private var title: String {
        switch ref {
        case .favourites: "Favourites"
        case .user(let id): library.playlists.first { $0.id == id }?.name ?? "Playlist"
        }
    }

    private var tracks: [Track] {
        switch ref {
        case .favourites: library.tracks.filter { library.favourites.contains($0.id) }
        case .user(let id): library.tracks(library.playlists.first { $0.id == id }?.trackIDs ?? [])
        }
    }

    var body: some View {
        let tracks = self.tracks
        List {
            if tracks.isEmpty {
                ContentUnavailableView("Nothing Here Yet", systemImage: "music.note.list",
                                       description: Text("Touch and hold a song, album or folder to add it."))
            } else {
                PlayShuffleButtons(ids: tracks.map(\.id))
            }
            if case .user(let id) = ref {
                // The same song may be in a playlist twice, so rows are identified by position.
                ForEach(Array(tracks.enumerated()), id: \.offset) { index, track in
                    Button {
                        player.play(tracks.map(\.id), startAt: index)
                    } label: {
                        TrackRow(track: track)
                    }
                    .buttonStyle(.plain)
                    .disabled(!library.isAvailable(track))
                    .contextMenu { TrackMenuItems(ids: [track.id], favouriteID: track.id) }
                }
                .onDelete { library.removeFromPlaylist(id, at: $0) }
                .onMove { library.movePlaylistTracks(id, from: $0, to: $1) }
            } else {
                TrackRows(tracks: tracks)
            }
        }
        .listStyle(.plain)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if case .user = ref, !tracks.isEmpty { EditButton() }
        }
    }
}
