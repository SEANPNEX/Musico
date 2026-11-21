import SwiftUI

/// Songs, albums, artists and playlists on one tab, switched from the navigation bar.
struct LibraryView: View {
    enum Section: String, CaseIterable, Identifiable {
        case songs = "Songs", albums = "Albums", artists = "Artists", playlists = "Playlists"
        var id: String { rawValue }
    }

    @AppStorage("librarySection") private var section: Section = .songs

    var body: some View {
        Group {
            switch section {
            case .songs: SongsView()
            case .albums: AlbumsView()
            case .artists: ArtistsView()
            case .playlists: PlaylistsView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker("Show", selection: $section) {
                    ForEach(Section.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
            }
        }
    }
}
