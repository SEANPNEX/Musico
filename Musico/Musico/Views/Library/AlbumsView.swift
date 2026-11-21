import SwiftUI

struct AlbumsView: View {
    @Environment(LibraryStore.self) private var library

    var body: some View {
        ScrollView {
            if library.albums.isEmpty {
                ContentUnavailableView("No Albums Yet", systemImage: "square.stack",
                                       description: Text("Albums appear once your library has songs."))
                    .padding(.top, 80)
            } else {
                AlbumGrid(albums: library.albums)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }
        }
        .navigationTitle("Albums")
        .navigationDestination(for: LibraryStore.Album.self) { AlbumDetailView(album: $0) }
    }
}

struct AlbumGrid: View {
    let albums: [LibraryStore.Album]

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 16)], spacing: 20) {
            ForEach(albums) { album in
                NavigationLink(value: album) {
                    VStack(alignment: .leading, spacing: 6) {
                        ArtworkView(artworkKey: album.artworkKey)
                        Text(album.title).font(.subheadline.weight(.medium)).lineLimit(1)
                        Text(album.artist).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                .buttonStyle(.plain)
                .contextMenu { TrackMenuItems(ids: album.trackIDs) }
            }
        }
    }
}

struct AlbumDetailView: View {
    let album: LibraryStore.Album
    @Environment(LibraryStore.self) private var library

    var body: some View {
        // Use the live album if it still exists so a rescan updates this screen.
        let album = library.albums.first { $0.id == self.album.id } ?? self.album
        let tracks = library.tracks(album.trackIDs)
        List {
            VStack(spacing: 8) {
                ArtworkView(artworkKey: album.artworkKey, size: 220)
                Text(album.title).font(.title3.weight(.semibold)).multilineTextAlignment(.center)
                Text(album.artist).foregroundStyle(.secondary)
                Text("\(tracks.count) songs · \(formatTime(tracks.reduce(0) { $0 + $1.duration }))")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .listRowSeparator(.hidden)
            PlayShuffleButtons(ids: album.trackIDs)
            TrackRows(tracks: tracks) { track in
                track.trackNumber.map { "\($0) · \(track.artist)" } ?? track.artist
            }
        }
        .listStyle(.plain)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Menu("More", systemImage: "ellipsis.circle") { TrackMenuItems(ids: album.trackIDs) }
        }
    }
}

struct ArtistsView: View {
    @Environment(LibraryStore.self) private var library

    var body: some View {
        List {
            if library.artists.isEmpty {
                ContentUnavailableView("No Artists Yet", systemImage: "music.mic",
                                       description: Text("Artists appear once your library has songs."))
            }
            ForEach(library.artists, id: \.self) { artist in
                NavigationLink(value: artist) {
                    Label(artist, systemImage: "person.fill")
                }
                .contextMenu { TrackMenuItems(ids: library.tracks(byArtist: artist).map(\.id)) }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Artists")
        .navigationDestination(for: String.self) { ArtistDetailView(artist: $0) }
        .navigationDestination(for: LibraryStore.Album.self) { AlbumDetailView(album: $0) }
    }
}

struct ArtistDetailView: View {
    let artist: String
    @Environment(LibraryStore.self) private var library

    var body: some View {
        let tracks = library.tracks(byArtist: artist)
        let albums = library.albums(byArtist: artist)
        List {
            PlayShuffleButtons(ids: tracks.map(\.id))
            if !albums.isEmpty {
                Section("Albums") {
                    AlbumGrid(albums: albums).listRowSeparator(.hidden)
                }
            }
            Section("Songs") {
                TrackRows(tracks: tracks) { $0.album }
            }
        }
        .listStyle(.plain)
        .navigationTitle(artist)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Menu("More", systemImage: "ellipsis.circle") { TrackMenuItems(ids: tracks.map(\.id)) }
        }
    }
}
