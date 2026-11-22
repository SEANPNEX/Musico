import SwiftUI

enum SongSort: String, CaseIterable, Identifiable {
    case title = "Title", artist = "Artist", album = "Album", dateAdded = "Recently Added"
    var id: String { rawValue }

    @MainActor
    var localizedTitle: String { L10n.tr(rawValue) }
}

struct SongsView: View {
    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player
    @Environment(\.appNavigation) private var navigation
    @AppStorage("songSort") private var sort: SongSort = .title

    private var trimmedQuery: String {
        navigation.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var isSearching: Bool { !trimmedQuery.isEmpty }

    private var songs: [Track] {
        if isSearching {
            return library.search(trimmedQuery, field: navigation.searchField)
        }
        func ascending(_ a: String, _ b: String) -> Bool { a.localizedStandardCompare(b) == .orderedAscending }
        switch sort {
        case .title: return library.tracks
        case .artist: return library.tracks.sorted { $0.artist == $1.artist ? ascending($0.title, $1.title) : ascending($0.artist, $1.artist) }
        case .album: return library.tracks.sorted { $0.album == $1.album ? ascending($0.title, $1.title) : ascending($0.album, $1.album) }
        case .dateAdded: return library.tracks.sorted { $0.dateAdded > $1.dateAdded }
        }
    }

    var body: some View {
        @Bindable var navigation = navigation
        let songs = self.songs
        let searchTextBinding = Binding<String>(
            get: { navigation.searchQuery },
            set: { navigation.updateSearchQueryFromUser($0) }
        )
        List {
            if isSearching && navigation.searchField != .keyword {
                HStack(spacing: 8) {
                    Image(systemName: "line.3.horizontal.decrease.circle.fill")
                        .foregroundStyle(Color.accentColor)
                    Text("\(navigation.searchField.localizedTitle): \(trimmedQuery)")
                        .font(.subheadline.weight(.medium))
                    Spacer()
                    Button(L10n.tr("Clear Filter")) {
                        navigation.clearSearchFieldTag()
                    }
                    .font(.caption)
                    .buttonStyle(.borderless)
                }
                .listRowSeparator(.hidden)
            }
            if library.tracks.isEmpty {
                ContentUnavailableView(L10n.tr("No Songs Yet"), systemImage: "music.note",
                                       description: Text(L10n.tr("Add a folder in the Folders tab to build your library.")))
            } else if songs.isEmpty {
                ContentUnavailableView.search(text: trimmedQuery)
            } else {
                if !isSearching {
                    PlayShuffleButtons(ids: songs.map(\.id))
                }
                TrackRows(
                    tracks: songs,
                    subtitle: { sort == .album || isSearching ? "\(L10n.displayArtist($0.artist)) — \(L10n.displayAlbum($0.album))" : L10n.displayArtist($0.artist) },
                    onTap: isSearching ? { index, _ in
                        let queueName = SearchField.queueName(field: navigation.searchField, query: trimmedQuery)
                        player.playInNewQueue(songs.map(\.id), startAt: index, name: queueName)
                    } : nil
                )
            }
        }
        .listStyle(.plain)
        .navigationTitle(L10n.tr("Songs"))
        .searchable(text: searchTextBinding, isPresented: $navigation.isSearchPresented, prompt: L10n.tr("Songs, artists, albums, genres"))
        .refreshable { await library.rescan() }
        .toolbar {
            Menu(L10n.tr("Sort"), systemImage: "arrow.up.arrow.down") {
                Picker(L10n.tr("Sort By"), selection: $sort) {
                    ForEach(SongSort.allCases) { Text($0.localizedTitle).tag($0) }
                }
            }
        }
    }
}
