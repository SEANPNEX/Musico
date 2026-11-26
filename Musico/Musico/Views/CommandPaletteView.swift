import SwiftUI

struct CommandPaletteView: View {
    private enum SectionKind: String, CaseIterable {
        case songs = "Songs"
        case albums = "Albums"
        case artists = "Artists"
        case playlists = "Playlists"
        case queues = "Queues"
        case actions = "Actions"

        @MainActor
        var localizedTitle: String { L10n.tr(rawValue) }
    }

    private enum Payload {
        case song(Track)
        case album(LibraryStore.Album)
        case artist(String)
        case playlist(PlaylistRef)
        case queue(PlayQueue)
        case page(SidebarItem, AppTab, LibraryView.Section?)
        case nowPlaying
        case rescan
    }

    private struct PaletteItem: Identifiable {
        let id: String
        let section: SectionKind
        let title: String
        let subtitle: String?
        let systemImage: String
        let artworkKey: String?
        let remoteArtworkURL: String?
        let isArtist: Bool
        let payload: Payload
    }

    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player
    @Environment(QueueStore.self) private var queues
    @Environment(\.appNavigation) private var navigation
    @Environment(\.themeManager) private var themeManager
    @Environment(\.toastManager) private var toastManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var query = ""
    @State private var selectedIndex = 0
    @FocusState private var isSearchFocused: Bool

    private var trimmedQuery: String {
        query.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var filteredItems: [PaletteItem] {
        let needle = trimmedQuery
        let isEmpty = needle.isEmpty
        var result: [PaletteItem] = []

        func matches(_ values: String?...) -> Bool {
            if isEmpty { return true }
            return values.compactMap { $0 }.contains { $0.localizedStandardContains(needle) }
        }

        // Songs
        let songLimit = isEmpty ? 4 : 10
        let matchingSongs = library.tracks
            .filter { library.isAvailable($0) && matches($0.title, $0.artist, $0.album) }
            .prefix(songLimit)
        for track in matchingSongs {
            result.append(PaletteItem(
                id: "song:\(track.id)",
                section: .songs,
                title: track.title,
                subtitle: "\(L10n.displayArtist(track.artist)) · \(L10n.displayAlbum(track.album))",
                systemImage: "music.note",
                artworkKey: track.artworkKey,
                remoteArtworkURL: track.remoteArtworkURL,
                isArtist: false,
                payload: .song(track)
            ))
        }

        // Albums
        let albumLimit = isEmpty ? 3 : 8
        let matchingAlbums = library.albums
            .filter { matches($0.title, L10n.displayAlbum($0.title), $0.artist, L10n.displayArtist($0.artist)) }
            .prefix(albumLimit)
        for album in matchingAlbums {
            result.append(PaletteItem(
                id: "album:\(album.id)",
                section: .albums,
                title: L10n.displayAlbum(album.title),
                subtitle: "\(L10n.displayArtist(album.artist)) · \(L10n.songCount(album.trackIDs.count))",
                systemImage: "square.stack",
                artworkKey: album.artworkKey,
                remoteArtworkURL: nil,
                isArtist: false,
                payload: .album(album)
            ))
        }

        // Artists
        let artistLimit = isEmpty ? 3 : 8
        let matchingArtists = library.artists
            .filter { matches($0, L10n.displayArtist($0)) }
            .prefix(artistLimit)
        for artist in matchingArtists {
            let artistAlbums = library.albums(byArtist: artist)
            let artistTracks = library.tracks(byArtist: artist)
            let artworkKey = artistAlbums.lazy.compactMap(\.artworkKey).first ?? artistTracks.lazy.compactMap(\.artworkKey).first
            result.append(PaletteItem(
                id: "artist:\(artist)",
                section: .artists,
                title: L10n.displayArtist(artist),
                subtitle: "\(L10n.albumCount(artistAlbums.count)) · \(L10n.songCount(artistTracks.count))",
                systemImage: "music.mic",
                artworkKey: artworkKey,
                remoteArtworkURL: nil,
                isArtist: true,
                payload: .artist(artist)
            ))
        }

        // Playlists
        if matches(L10n.tr("Favourites"), "Favourites") {
            result.append(PaletteItem(
                id: "playlist:favourites",
                section: .playlists,
                title: L10n.tr("Favourites"),
                subtitle: L10n.songCount(library.favourites.count),
                systemImage: "heart.fill",
                artworkKey: nil,
                remoteArtworkURL: nil,
                isArtist: false,
                payload: .playlist(.favourites)
            ))
        }
        let playlistLimit = isEmpty ? 4 : 8
        for playlist in library.playlists.filter({ matches($0.name) }).prefix(playlistLimit) {
            result.append(PaletteItem(
                id: "playlist:\(playlist.id.uuidString)",
                section: .playlists,
                title: playlist.name,
                subtitle: L10n.songCount(library.tracks(playlist.trackIDs).count),
                systemImage: "music.note.list",
                artworkKey: nil,
                remoteArtworkURL: nil,
                isArtist: false,
                payload: .playlist(.user(playlist.id))
            ))
        }

        // Queues
        for queue in queues.queues.filter({ matches($0.name, L10n.displayQueueName($0.name)) }).prefix(8) {
            let isActive = queue.id == queues.activeID
            let subtitle = isActive
                ? "\(L10n.tr("Playing")) · \(L10n.songCount(queue.trackIDs.count))"
                : L10n.songCount(queue.trackIDs.count)
            result.append(PaletteItem(
                id: "queue:\(queue.id.uuidString)",
                section: .queues,
                title: L10n.displayQueueName(queue.name),
                subtitle: subtitle,
                systemImage: isActive ? "speaker.wave.2.fill" : "list.bullet",
                artworkKey: nil,
                remoteArtworkURL: nil,
                isArtist: false,
                payload: .queue(queue)
            ))
        }

        // Actions / Pages
        let actions: [(id: String, title: String, alt: String, icon: String, payload: Payload)] = [
            ("action:nowPlaying", L10n.tr("Now Playing"), "Now Playing", "play.rectangle", .nowPlaying),
            ("action:rescan", L10n.tr("Rescan Library"), "Rescan Library", "arrow.clockwise", .rescan),
            ("action:folders", L10n.tr("Folders"), "Folders", "folder", .page(.folders, .folders, nil)),
            ("action:songs", L10n.tr("Songs"), "Songs", "music.note", .page(.songs, .library, .songs)),
            ("action:albums", L10n.tr("Albums"), "Albums", "square.stack", .page(.albums, .library, .albums)),
            ("action:artists", L10n.tr("Artists"), "Artists", "music.mic", .page(.artists, .library, .artists)),
            ("action:playlists", L10n.tr("All Playlists"), "All Playlists", "square.grid.2x2", .page(.playlists, .library, .playlists)),
            ("action:stats", L10n.tr("Stats"), "Stats", "chart.bar", .page(.stats, .stats, nil)),
            ("action:extensions", L10n.tr("Extensions"), "Extensions", "puzzlepiece.extension", .page(.extensions, .folders, nil)),
            ("action:settings", L10n.tr("Settings"), "Settings", "gearshape", .page(.settings, .folders, nil))
        ]
        for action in actions where matches(action.title, action.alt) {
            result.append(PaletteItem(
                id: action.id,
                section: .actions,
                title: action.title,
                subtitle: nil,
                systemImage: action.icon,
                artworkKey: nil,
                remoteArtworkURL: nil,
                isArtist: false,
                payload: action.payload
            ))
        }

        return result
    }

    private var groupedSections: [(section: SectionKind, items: [(offset: Int, item: PaletteItem)])] {
        let items = filteredItems
        return SectionKind.allCases.compactMap { section in
            let matches = items.enumerated().filter { $0.element.section == section }
                .map { (offset: $0.offset, item: $0.element) }
            return matches.isEmpty ? nil : (section: section, items: matches)
        }
    }

    var body: some View {
        let items = filteredItems
        let safeIndex = items.indices.contains(selectedIndex) ? selectedIndex : 0
        let selectedID = items.indices.contains(safeIndex) ? items[safeIndex].id : nil

        VStack(spacing: 0) {
            searchBar(items: items, selectedIndex: safeIndex)

            Divider().opacity(0.4)

            if items.isEmpty {
                ContentUnavailableView(
                    L10n.tr("No Results Found"),
                    systemImage: "magnifyingglass",
                    description: Text(L10n.tr("Try another keyword to jump to songs, albums, artists, or actions."))
                )
                .frame(maxWidth: .infinity, minHeight: 220, maxHeight: 280)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 4) {
                            ForEach(groupedSections, id: \.section) { group in
                                Text(group.section.localizedTitle)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.secondary)
                                    .textCase(.uppercase)
                                    .padding(.horizontal, 12)
                                    .padding(.top, 8)
                                    .padding(.bottom, 2)

                                ForEach(group.items, id: \.item.id) { entry in
                                    paletteRow(entry.item, isSelected: entry.offset == safeIndex)
                                        .id(entry.item.id)
                                        .onTapGesture {
                                            execute(entry.item)
                                        }
                                }
                            }
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 6)
                    }
                    .frame(maxHeight: 360)
                    .onChange(of: selectedID) { _, newID in
                        guard let newID else { return }
                        if reduceMotion {
                            proxy.scrollTo(newID, anchor: .center)
                        } else {
                            withAnimation(.easeOut(duration: 0.12)) {
                                proxy.scrollTo(newID, anchor: .center)
                            }
                        }
                    }
                }
            }

            Divider().opacity(0.4)

            footerHints
        }
        .frame(maxWidth: 540)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.14), lineWidth: 0.8)
                .allowsHitTesting(false)
        }
        .shadow(color: .black.opacity(0.26), radius: 28, y: 12)
        .padding(.horizontal, 20)
        .onKeyPress(.downArrow) {
            moveSelection(delta: 1, count: items.count)
            return .handled
        }
        .onKeyPress(.upArrow) {
            moveSelection(delta: -1, count: items.count)
            return .handled
        }
        .onKeyPress(.return) {
            if items.indices.contains(safeIndex) {
                execute(items[safeIndex])
                return .handled
            }
            return .ignored
        }
        .onKeyPress(.escape) {
            dismissPalette()
            return .handled
        }
        .onChange(of: query) { _, _ in
            selectedIndex = 0
        }
        .task {
            try? await Task.sleep(for: .milliseconds(50))
            isSearchFocused = true
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L10n.tr("Quick Jump"))
    }

    private func searchBar(items: [PaletteItem], selectedIndex: Int) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.body.weight(.medium))
                .foregroundStyle(themeManager.accentColor)

            TextField(L10n.tr("Jump to songs, albums, artists, playlists, queues, or actions…"), text: $query)
                .textFieldStyle(.plain)
                .font(.body)
                .focused($isSearchFocused)
                .submitLabel(.go)
                .onSubmit {
                    if items.indices.contains(selectedIndex) {
                        execute(items[selectedIndex])
                    }
                }

            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.tr("Clear Filter"))
            }

            Button {
                dismissPalette()
            } label: {
                Text("Esc")
                    .font(.caption2.weight(.semibold).monospaced())
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 5, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.tr("Close"))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 13)
    }

    @ViewBuilder
    private func paletteRow(_ item: PaletteItem, isSelected: Bool) -> some View {
        HStack(spacing: 12) {
            if item.isArtist {
                ArtistAvatarView(artist: item.title, artworkKey: item.artworkKey, size: 34)
            } else if item.artworkKey != nil || item.remoteArtworkURL != nil {
                ArtworkView(artworkKey: item.artworkKey, remoteArtworkURL: item.remoteArtworkURL, size: 34, cornerRadius: 6)
            } else {
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(isSelected ? themeManager.accentColor.opacity(0.22) : Color.primary.opacity(0.07))
                    .frame(width: 34, height: 34)
                    .overlay {
                        Image(systemName: item.systemImage)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(isSelected ? themeManager.accentColor : .secondary)
                    }
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                if let subtitle = item.subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 8)

            if isSelected {
                Image(systemName: "return")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(themeManager.accentColor)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .background(
            isSelected ? themeManager.accentColor.opacity(0.16) : .clear,
            in: RoundedRectangle(cornerRadius: 10, style: .continuous)
        )
        .overlay {
            if isSelected {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(themeManager.accentColor.opacity(0.32), lineWidth: 0.75)
                    .allowsHitTesting(false)
            }
        }
        .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private var footerHints: some View {
        HStack(spacing: 14) {
            Label(L10n.tr("Navigate"), systemImage: "arrow.up.arrow.down")
            Label(L10n.tr("Open"), systemImage: "return")
            Spacer()
            Text(L10n.tr("Esc to close"))
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }

    private func moveSelection(delta: Int, count: Int) {
        guard count > 0 else {
            selectedIndex = 0
            return
        }
        let next = (selectedIndex + delta + count) % count
        selectedIndex = next
    }

    private func dismissPalette() {
        navigation.showCommandPalette = false
    }

    private func execute(_ item: PaletteItem) {
        dismissPalette()
        switch item.payload {
        case .song(let track):
            if let idx = library.tracks.firstIndex(where: { $0.id == track.id }) {
                player.playInNamedQueue(library.tracks.map(\.id), name: "Songs", startAt: idx)
            } else {
                player.play([track.id], startAt: 0)
            }
        case .album(let album):
            navigation.navigateToAlbum(album)
        case .artist(let artist):
            navigation.navigateToArtist(artist)
        case .playlist(let ref):
            navigation.navigateToPlaylist(ref)
        case .queue(let queue):
            queues.selectViewedQueue(queue.id)
            player.switchQueue(to: queue.id)
            navigation.selectSidebar(.queues)
            navigation.selectedTab = .queues
            navigation.showPlayer = false
            toastManager.show(
                L10n.tr("Switched to %@", L10n.displayQueueName(queue.name)),
                systemImage: "list.bullet"
            )
        case .page(let sidebar, let tab, let section):
            navigation.showPlayer = false
            navigation.selectedTab = tab
            if let section {
                navigation.librarySection = section
                navigation.libraryPath = NavigationPath()
            }
            navigation.selectSidebar(sidebar)
        case .nowPlaying:
            navigation.showPlayer = true
        case .rescan:
            toastManager.show(L10n.tr("Rescanning library…"), systemImage: "arrow.clockwise")
            Task { await library.rescan() }
        }
    }
}
