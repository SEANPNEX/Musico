import SwiftUI

struct AlbumsView: View {
    @Environment(LibraryStore.self) private var library

    var body: some View {
        ScrollView {
            if library.albums.isEmpty {
                ContentUnavailableView(L10n.tr("No Albums Yet"), systemImage: "square.stack",
                                       description: Text(L10n.tr("Albums appear once your library has songs.")))
                    .padding(.top, 80)
            } else {
                AlbumGrid(albums: library.albums)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }
        }
        .navigationTitle(L10n.tr("Albums"))
        #if targetEnvironment(macCatalyst)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .navigationDestination(for: LibraryStore.Album.self) { AlbumDetailView(album: $0) }
    }
}

struct AlbumGrid: View {
    let albums: [LibraryStore.Album]

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 16)], spacing: 20) {
            ForEach(albums) { album in
                AlbumGridCard(album: album)
            }
        }
    }
}

private struct AlbumGridCard: View {
    let album: LibraryStore.Album
    @State private var isHovered = false

    var body: some View {
        NavigationLink(value: album) {
            VStack(alignment: .leading, spacing: 6) {
                ArtworkView(artworkKey: album.artworkKey)
                    .shadow(
                        color: .black.opacity(isHovered ? 0.18 : 0.06),
                        radius: isHovered ? 10 : 4,
                        y: isHovered ? 5 : 2
                    )
                Text(L10n.displayAlbum(album.title)).font(.subheadline.weight(.medium)).lineLimit(1)
                Text(L10n.displayArtist(album.artist)).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
            }
            .scaleEffect(isHovered ? 1.025 : 1.0)
            .animation(.easeOut(duration: 0.16), value: isHovered)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
        .contextMenu { TrackMenuItems(ids: album.trackIDs) }
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
                Text(L10n.displayAlbum(album.title)).font(.title3.weight(.semibold)).multilineTextAlignment(.center)
                Text(L10n.displayArtist(album.artist)).foregroundStyle(.secondary)
                Text("\(L10n.songCount(tracks.count)) · \(formatTime(tracks.reduce(0) { $0 + $1.duration }))")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .listRowSeparator(.hidden)
            PlayShuffleButtons(ids: album.trackIDs)
            TrackRows(tracks: tracks) { track in
                let artist = L10n.displayArtist(track.artist)
                return track.trackNumber.map { "\($0) · \(artist)" } ?? artist
            }
        }
        .listStyle(.plain)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Menu(L10n.tr("More"), systemImage: "ellipsis.circle") { TrackMenuItems(ids: album.trackIDs) }
        }
    }
}

struct ArtistsView: View {
    @Environment(LibraryStore.self) private var library

    var body: some View {
        List {
            if library.artists.isEmpty {
                ContentUnavailableView(L10n.tr("No Artists Yet"), systemImage: "music.mic",
                                       description: Text(L10n.tr("Artists appear once your library has songs.")))
            }
            ForEach(library.artists, id: \.self) { artist in
                NavigationLink(value: artist) {
                    Label(L10n.displayArtist(artist), systemImage: "person.fill")
                }
                .contextMenu { TrackMenuItems(ids: library.tracks(byArtist: artist).map(\.id)) }
            }
        }
        .listStyle(.plain)
        .navigationTitle(L10n.tr("Artists"))
        #if targetEnvironment(macCatalyst)
        .navigationBarTitleDisplayMode(.inline)
        #endif
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
                Section(L10n.tr("Albums")) {
                    AlbumGrid(albums: albums).listRowSeparator(.hidden)
                }
            }
            Section(L10n.tr("Songs")) {
                TrackRows(tracks: tracks) { L10n.displayAlbum($0.album) }
            }
        }
        .listStyle(.plain)
        .navigationTitle(L10n.displayArtist(artist))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Menu(L10n.tr("More"), systemImage: "ellipsis.circle") { TrackMenuItems(ids: tracks.map(\.id)) }
        }
    }
}
