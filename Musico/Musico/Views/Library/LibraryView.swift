import SwiftUI

/// Songs, albums, artists and playlists on one tab, switched from the navigation bar.
struct LibraryView: View {
    enum Section: String, CaseIterable, Identifiable {
        case songs = "Songs", albums = "Albums", artists = "Artists", playlists = "Playlists"
        var id: String { rawValue }
    }

    @AppStorage("librarySection") private var storedSection: Section = .songs
    @Environment(\.appNavigation) private var navigation

    var body: some View {
        Group {
            switch navigation.librarySection {
            case .songs: SongsView()
            case .albums: AlbumsView()
            case .artists: ArtistsView()
            case .playlists: PlaylistsView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if !navigation.isSearchPresented {
                navigation.librarySection = storedSection
            }
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker("Show", selection: Binding(
                    get: { navigation.librarySection },
                    set: {
                        navigation.librarySection = $0
                        storedSection = $0
                    }
                )) {
                    ForEach(Section.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
            }
        }
    }
}
