import SwiftUI

enum SongSort: String, CaseIterable, Identifiable {
    case title = "Title", artist = "Artist", album = "Album", dateAdded = "Recently Added"
    var id: String { rawValue }
}

struct SongsView: View {
    @Environment(LibraryStore.self) private var library
    @AppStorage("songSort") private var sort: SongSort = .title
    @State private var query = ""

    private var songs: [Track] {
        if !query.trimmingCharacters(in: .whitespaces).isEmpty { return library.search(query) }
        func ascending(_ a: String, _ b: String) -> Bool { a.localizedStandardCompare(b) == .orderedAscending }
        switch sort {
        case .title: return library.tracks
        case .artist: return library.tracks.sorted { $0.artist == $1.artist ? ascending($0.title, $1.title) : ascending($0.artist, $1.artist) }
        case .album: return library.tracks.sorted { $0.album == $1.album ? ascending($0.title, $1.title) : ascending($0.album, $1.album) }
        case .dateAdded: return library.tracks.sorted { $0.dateAdded > $1.dateAdded }
        }
    }

    var body: some View {
        let songs = self.songs
        List {
            if library.tracks.isEmpty {
                ContentUnavailableView("No Songs Yet", systemImage: "music.note",
                                       description: Text("Add a folder in the Folders tab to build your library."))
            } else if songs.isEmpty {
                ContentUnavailableView.search(text: query)
            } else {
                if query.isEmpty { PlayShuffleButtons(ids: songs.map(\.id)) }
                TrackRows(tracks: songs) { sort == .album ? "\($0.artist) — \($0.album)" : $0.artist }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Songs")
        .searchable(text: $query, prompt: "Songs, artists, albums")
        .refreshable { await library.rescan() }
        .toolbar {
            Menu("Sort", systemImage: "arrow.up.arrow.down") {
                Picker("Sort By", selection: $sort) {
                    ForEach(SongSort.allCases) { Text($0.rawValue).tag($0) }
                }
            }
        }
    }
}
