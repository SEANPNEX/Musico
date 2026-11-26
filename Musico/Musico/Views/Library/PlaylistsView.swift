import SwiftUI

enum PlaylistRef: Hashable {
    case favourites
    case user(UUID)
}

@MainActor
private func formatPlaylistSummary(count: Int, duration total: TimeInterval) -> String {
    let songs = L10n.songCount(count)
    guard total > 0 else { return songs }
    let durationText = total >= 60 ? L10n.listeningDuration(total) : formatTime(total)
    return "\(songs) · \(durationText)"
}

struct PlaylistArtworkView: View {
    let tracks: [Track]
    var isFavourites: Bool = false
    var size: CGFloat? = nil
    var cornerRadius: CGFloat = 12
    @Environment(\.themeManager) private var themeManager

    private var artworkKeys: [String] {
        var seen = Set<String>()
        var result: [String] = []
        for track in tracks {
            guard let key = track.artworkKey, seen.insert(key).inserted else { continue }
            result.append(key)
            if result.count == 4 { break }
        }
        return result
    }

    var body: some View {
        let keys = artworkKeys
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)

        Group {
            if keys.count >= 4 {
                VStack(spacing: 1) {
                    HStack(spacing: 1) {
                        ArtworkView(artworkKey: keys[0], cornerRadius: 0)
                        ArtworkView(artworkKey: keys[1], cornerRadius: 0)
                    }
                    HStack(spacing: 1) {
                        ArtworkView(artworkKey: keys[2], cornerRadius: 0)
                        ArtworkView(artworkKey: keys[3], cornerRadius: 0)
                    }
                }
                .aspectRatio(1, contentMode: .fit)
                .clipShape(shape)
            } else if let first = keys.first {
                ArtworkView(artworkKey: first, size: size, cornerRadius: cornerRadius)
            } else {
                placeholderView
                    .aspectRatio(1, contentMode: .fit)
                    .clipShape(shape)
            }
        }
        .frame(width: size, height: size)
        .overlay(alignment: .bottomLeading) {
            if isFavourites && !keys.isEmpty {
                Image(systemName: "heart.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.pink)
                    .padding(6)
                    .background(.ultraThinMaterial, in: Circle())
                    .padding(8)
                    .allowsHitTesting(false)
            }
        }
        .overlay {
            shape
                .strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5)
                .allowsHitTesting(false)
        }
    }

    @ViewBuilder
    private var placeholderView: some View {
        if isFavourites {
            LinearGradient(
                colors: [Color.pink.opacity(0.85), Color.red.opacity(0.75)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .overlay {
                Image(systemName: "heart.fill")
                    .font(.system(size: (size ?? 160) * 0.34, weight: .semibold))
                    .foregroundStyle(.white)
            }
        } else {
            LinearGradient(
                colors: [themeManager.accentColor.opacity(0.22), themeManager.accentColor.opacity(0.07)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .overlay {
                Image(systemName: "music.note.list")
                    .font(.system(size: (size ?? 160) * 0.32, weight: .medium))
                    .foregroundStyle(themeManager.accentColor)
            }
        }
    }
}

struct PlaylistsView: View {
    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player
    @Environment(PlayHistoryStore.self) private var history
    @Environment(\.languageManager) private var languageManager
    @Environment(\.themeManager) private var themeManager
    @State private var showNew = false
    @State private var renaming: Playlist?

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: 168, maximum: 220), spacing: 18)]
    }

    var body: some View {
        let favTracks = library.orderedFavourites(history: history)
        ScrollView {
            LazyVGrid(columns: columns, spacing: 22) {
                PlaylistGridCard(
                    ref: .favourites,
                    title: L10n.tr("Favourites"),
                    tracks: favTracks,
                    isFavourites: true,
                    onPlay: {
                        let ids = favTracks.map(\.id)
                        guard !ids.isEmpty else { return }
                        player.playInNamedQueue(ids, name: L10n.tr("Favourites"), startAt: 0, shuffle: false)
                    },
                    onShuffle: {
                        let ids = favTracks.map(\.id)
                        guard !ids.isEmpty else { return }
                        player.playInNamedQueue(ids, name: L10n.tr("Favourites"), startAt: Int.random(in: 0..<ids.count), shuffle: true)
                    }
                )

                ForEach(library.playlists) { playlist in
                    let playlistTracks = library.tracks(playlist.trackIDs)
                    let resolvedIDs = playlistTracks.map(\.id)
                    PlaylistGridCard(
                        ref: .user(playlist.id),
                        title: playlist.name,
                        tracks: playlistTracks,
                        isFavourites: false,
                        onPlay: {
                            guard !resolvedIDs.isEmpty else { return }
                            player.playInNamedQueue(resolvedIDs, name: playlist.name, startAt: 0, shuffle: false)
                        },
                        onShuffle: {
                            guard !resolvedIDs.isEmpty else { return }
                            player.playInNamedQueue(
                                resolvedIDs,
                                name: playlist.name,
                                startAt: Int.random(in: 0..<resolvedIDs.count),
                                shuffle: true
                            )
                        },
                        onRename: {
                            renaming = playlist
                        },
                        onDelete: {
                            library.deletePlaylist(playlist.id)
                        }
                    )
                }

                if library.playlists.isEmpty {
                    NewPlaylistGridCard {
                        showNew = true
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
        }
        .navigationTitle(L10n.tr("Playlists"))
        #if targetEnvironment(macCatalyst)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .navigationDestination(for: PlaylistRef.self) { PlaylistDetailView(ref: $0) }
        .toolbar {
            Button(L10n.tr("New Playlist"), systemImage: "plus") {
                showNew = true
            }
            .help(L10n.tr("New Playlist"))
        }
        .sheet(isPresented: $showNew) {
            NamePromptSheet(
                title: L10n.tr("New Playlist"),
                subtitle: L10n.tr("Give your playlist a name."),
                placeholder: L10n.tr("Playlist name"),
                confirmLabel: L10n.tr("Create"),
                systemImage: "music.note.list"
            ) { trimmed in
                library.createPlaylist(name: trimmed)
            }
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
        }
        .sheet(item: $renaming) { playlist in
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
    }
}

private struct PlaylistGridCard: View {
    let ref: PlaylistRef
    let title: String
    let tracks: [Track]
    let isFavourites: Bool
    let onPlay: () -> Void
    let onShuffle: () -> Void
    var onRename: (() -> Void)? = nil
    var onDelete: (() -> Void)? = nil

    @Environment(\.themeManager) private var themeManager
    @State private var isHovered = false
    @State private var isPlayHovered = false

    private var trackIDs: [String] { tracks.map(\.id) }
    private var totalDuration: TimeInterval { tracks.reduce(0) { $0 + $1.duration } }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .bottomTrailing) {
                NavigationLink(value: ref) {
                    PlaylistArtworkView(tracks: tracks, isFavourites: isFavourites, cornerRadius: 12)
                        .shadow(
                            color: .black.opacity(isHovered ? 0.18 : 0.06),
                            radius: isHovered ? 12 : 5,
                            y: isHovered ? 6 : 2
                        )
                        .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)

                if !tracks.isEmpty {
                    Button(action: onPlay) {
                        Image(systemName: "play.fill")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .frame(width: 36, height: 36)
                            .background(themeManager.accentColor, in: Circle())
                            .shadow(color: .black.opacity(0.24), radius: 6, y: 2)
                            .scaleEffect(isPlayHovered ? 1.08 : 1.0)
                    }
                    .buttonStyle(.borderless)
                    .onHover { isPlayHovered = $0 }
                    .help(L10n.tr("Play"))
                    .accessibilityLabel(L10n.tr("Play"))
                    .padding(10)
                    #if targetEnvironment(macCatalyst)
                    .opacity(isHovered ? 1 : 0)
                    .allowsHitTesting(isHovered)
                    #endif
                }
            }

            HStack(alignment: .top, spacing: 4) {
                NavigationLink(value: ref) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Text(formatPlaylistSummary(count: tracks.count, duration: totalDuration))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                if onRename != nil || onDelete != nil {
                    Menu {
                        cardMenuItems
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.secondary)
                            .frame(width: 26, height: 26)
                            .background(
                                isHovered ? Color.primary.opacity(0.06) : .clear,
                                in: RoundedRectangle(cornerRadius: 6, style: .continuous)
                            )
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L10n.tr("More"))
                }
            }
        }
        .scaleEffect(isHovered ? 1.02 : 1.0)
        .animation(.easeOut(duration: 0.16), value: isHovered)
        .onHover { isHovered = $0 }
        .contextMenu { cardMenuItems }
    }

    @ViewBuilder
    private var cardMenuItems: some View {
        if !trackIDs.isEmpty {
            Button(L10n.tr("Play"), systemImage: "play.fill", action: onPlay)
            Button(L10n.tr("Shuffle"), systemImage: "shuffle", action: onShuffle)
            Divider()
        }
        if let onRename {
            Button(L10n.tr("Rename…"), systemImage: "pencil", action: onRename)
        }
        if !trackIDs.isEmpty {
            TrackMenuItems(ids: trackIDs)
        }
        if let onDelete {
            Divider()
            Button(L10n.tr("Delete"), systemImage: "trash", role: .destructive, action: onDelete)
        }
    }
}

private struct NewPlaylistGridCard: View {
    let action: () -> Void
    @Environment(\.themeManager) private var themeManager
    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(isHovered ? themeManager.accentColor.opacity(0.10) : Color.primary.opacity(0.04))
                    .aspectRatio(1, contentMode: .fit)
                    .overlay {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(
                                isHovered ? themeManager.accentColor.opacity(0.55) : Color.primary.opacity(0.16),
                                style: StrokeStyle(lineWidth: 1.5, dash: [6, 5])
                            )
                    }
                    .overlay {
                        Image(systemName: "plus")
                            .font(.system(size: 32, weight: .medium))
                            .foregroundStyle(isHovered ? themeManager.accentColor : .secondary)
                    }

                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.tr("New Playlist"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(L10n.tr("Enter a name for the playlist."))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
        .animation(.easeOut(duration: 0.16), value: isHovered)
    }
}

struct PlaylistDetailView: View {
    let ref: PlaylistRef
    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player
    @Environment(PlayHistoryStore.self) private var history
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.dismiss) private var dismiss
    @Environment(\.languageManager) private var languageManager
    @Environment(\.themeManager) private var themeManager

    @State private var showAddSongs = false
    @State private var showRenameSheet = false
    @State private var confirmDelete = false

    private var isFavourites: Bool {
        if case .favourites = ref { return true }
        return false
    }

    private var playlistID: UUID? {
        if case .user(let id) = ref { return id }
        return nil
    }

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

    private var usesWideHero: Bool {
        #if targetEnvironment(macCatalyst)
        return true
        #else
        return sizeClass == .regular
        #endif
    }

    var body: some View {
        let tracks = self.tracks
        let trackIDs = tracks.map(\.id)
        let totalDuration = tracks.reduce(0) { $0 + $1.duration }

        List {
            heroHeader(tracks: tracks, trackIDs: trackIDs, totalDuration: totalDuration)
                .listRowSeparator(.hidden)

            if tracks.isEmpty {
                emptyStateView
                    .listRowSeparator(.hidden)
            }

            if case .user(let id) = ref {
                // The same song may be in a playlist twice, so rows are identified by position.
                ForEach(Array(tracks.enumerated()), id: \.offset) { index, track in
                    ReorderableTrackRow(
                        track: track,
                        index: index,
                        scopeID: "playlist-\(id.uuidString)",
                        subtitle: "\(L10n.displayArtist(track.artist)) — \(L10n.displayAlbum(track.album))",
                        trackNumber: index + 1,
                        isAvailable: library.isAvailable(track),
                        onTap: { player.playInNamedQueue(trackIDs, name: title, startAt: index) },
                        onMove: { from, to in
                            library.movePlaylistTracks(id, from: IndexSet(integer: from), to: to)
                        },
                        onRemove: {
                            library.removeFromPlaylist(id, at: IndexSet(integer: index))
                        },
                        removeHelp: L10n.tr("Remove from Playlist")
                    )
                    .contextMenu {
                        TrackMenuItems(ids: [track.id], favouriteID: track.id)
                        Divider()
                        Button(L10n.tr("Remove from Playlist"), systemImage: "minus.circle", role: .destructive) {
                            library.removeFromPlaylist(id, at: IndexSet(integer: index))
                        }
                    }
                }
                .onDelete { library.removeFromPlaylist(id, at: $0) }
                .onMove { library.movePlaylistTracks(id, from: $0, to: $1) }
            } else {
                ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                    ReorderableTrackRow(
                        track: track,
                        index: index,
                        scopeID: "favourites",
                        subtitle: "\(L10n.displayArtist(track.artist)) — \(L10n.displayAlbum(track.album))",
                        trackNumber: index + 1,
                        isAvailable: library.isAvailable(track),
                        onTap: { player.playInNamedQueue(trackIDs, name: title, startAt: index) },
                        onMove: { from, to in
                            library.moveFavourites(currentTrackIDs: trackIDs, from: IndexSet(integer: from), to: to)
                        }
                    )
                    .contextMenu { TrackMenuItems(ids: [track.id], favouriteID: track.id) }
                }
                .onMove { source, destination in
                    library.moveFavourites(currentTrackIDs: trackIDs, from: source, to: destination)
                }
            }
        }
        .listStyle(.plain)
        .hiddenScrollBackgroundIfThemed(themeManager)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: ref) {
            let ids = tracks.map(\.id)
            if !ids.isEmpty {
                player.syncNamedQueue(ids, name: title)
            }
        }
        .onChange(of: tracks.map(\.id)) { _, ids in
            if !ids.isEmpty {
                player.syncNamedQueue(ids, name: title)
            }
        }
        .onChange(of: ref) { _, _ in
            showAddSongs = false
            showRenameSheet = false
            confirmDelete = false
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if playlistID != nil {
                    Button(L10n.tr("Add Songs"), systemImage: "plus") {
                        showAddSongs = true
                    }
                    .help(L10n.tr("Add Songs"))
                }
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
                if playlistID != nil, !tracks.isEmpty {
                    EditButton()
                }
                if playlistID != nil || !trackIDs.isEmpty {
                    Menu(L10n.tr("More"), systemImage: "ellipsis.circle") {
                        detailMoreMenu(trackIDs: trackIDs)
                    }
                }
            }
        }
        .sheet(isPresented: $showRenameSheet) {
            NamePromptSheet(
                title: L10n.tr("Rename Playlist"),
                subtitle: L10n.tr("Choose a new name for this playlist."),
                placeholder: L10n.tr("Playlist name"),
                confirmLabel: L10n.tr("Rename"),
                systemImage: "pencil",
                initialName: title
            ) { trimmed in
                if let playlistID {
                    library.renamePlaylist(playlistID, to: trimmed)
                }
            }
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
        }
        .confirmationDialog(
            L10n.tr("Delete “%@”?", title),
            isPresented: $confirmDelete,
            titleVisibility: .visible
        ) {
            Button(L10n.tr("Delete Playlist"), role: .destructive) {
                if let playlistID {
                    library.deletePlaylist(playlistID)
                    dismiss()
                }
            }
            Button(L10n.tr("Cancel"), role: .cancel) {}
        } message: {
            Text(L10n.tr("This playlist will be removed. Your songs stay in your library."))
        }
        .sheet(isPresented: $showAddSongs) {
            if let playlistID {
                AddSongsToPlaylistSheet(playlistID: playlistID, library: library)
                    .environment(library)
                    .environment(\.languageManager, languageManager)
                    .environment(\.locale, languageManager.effectiveLocale)
                    .applyTheme(themeManager)
            }
        }
    }

    @ViewBuilder
    private func heroHeader(tracks: [Track], trackIDs: [String], totalDuration: TimeInterval) -> some View {
        if usesWideHero {
            HStack(alignment: .bottom, spacing: 22) {
                PlaylistArtworkView(tracks: tracks, isFavourites: isFavourites, size: 172, cornerRadius: 14)
                    .shadow(color: .black.opacity(0.14), radius: 14, y: 6)

                VStack(alignment: .leading, spacing: 10) {
                    Text(isFavourites ? L10n.tr("Favourites") : L10n.tr("Playlist"))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(isFavourites ? Color.pink : themeManager.accentColor)
                        .textCase(.uppercase)

                    Text(title)
                        .font(.title.bold())
                        .lineLimit(2)

                    Text(formatPlaylistSummary(count: tracks.count, duration: totalDuration))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    HStack(spacing: 10) {
                        Button {
                            player.playInNamedQueue(trackIDs, name: title, startAt: 0, shuffle: false)
                        } label: {
                            Label(L10n.tr("Play"), systemImage: "play.fill")
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(trackIDs.isEmpty)

                        Button {
                            let start = Int.random(in: 0..<max(trackIDs.count, 1))
                            player.playInNamedQueue(trackIDs, name: title, startAt: start, shuffle: true)
                        } label: {
                            Label(L10n.tr("Shuffle"), systemImage: "shuffle")
                        }
                        .buttonStyle(.bordered)
                        .disabled(trackIDs.isEmpty)

                        if playlistID != nil {
                            Button {
                                showAddSongs = true
                            } label: {
                                Label(L10n.tr("Add Songs"), systemImage: "plus")
                            }
                            .buttonStyle(.bordered)
                        }

                        if playlistID != nil || !trackIDs.isEmpty {
                            Menu {
                                detailMoreMenu(trackIDs: trackIDs)
                            } label: {
                                Image(systemName: "ellipsis")
                                    .frame(width: 24, height: 20)
                            }
                            .buttonStyle(.bordered)
                            .accessibilityLabel(L10n.tr("More"))
                        }
                    }
                    .controlSize(.regular)
                    .padding(.top, 2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 8)
        } else {
            VStack(spacing: 12) {
                PlaylistArtworkView(tracks: tracks, isFavourites: isFavourites, size: 180, cornerRadius: 14)
                    .shadow(color: .black.opacity(0.14), radius: 12, y: 5)
                Text(title)
                    .font(.title2.weight(.bold))
                    .multilineTextAlignment(.center)
                Text(formatPlaylistSummary(count: tracks.count, duration: totalDuration))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                PlayShuffleButtons(ids: trackIDs, queueName: title)
                if playlistID != nil {
                    Button {
                        showAddSongs = true
                    } label: {
                        Label(L10n.tr("Add Songs"), systemImage: "plus")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
        }
    }

    private var emptyStateView: some View {
        let descriptionText: String
        #if targetEnvironment(macCatalyst)
        descriptionText = L10n.tr("Add songs below, or right-click any song, album, or folder to add it to this playlist.")
        #else
        descriptionText = L10n.tr("Add songs below, or touch and hold any song, album, or folder to add it.")
        #endif
        return VStack(spacing: 12) {
            ContentUnavailableView(
                L10n.tr("Nothing Here Yet"),
                systemImage: isFavourites ? "heart" : "music.note.list",
                description: Text(descriptionText)
            )
            if playlistID != nil {
                Button {
                    showAddSongs = true
                } label: {
                    Label(L10n.tr("Add Songs…"), systemImage: "plus")
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
    }

    @ViewBuilder
    private func detailMoreMenu(trackIDs: [String]) -> some View {
        if !trackIDs.isEmpty {
            TrackMenuItems(ids: trackIDs)
        }
        if playlistID != nil {
            if !trackIDs.isEmpty {
                Divider()
            }
            Button(L10n.tr("Rename Playlist…"), systemImage: "pencil") {
                showRenameSheet = true
            }
            Button(L10n.tr("Delete Playlist"), systemImage: "trash", role: .destructive) {
                confirmDelete = true
            }
        }
    }
}

struct AddSongsToPlaylistSheet: View {
    let playlistID: UUID
    private let explicitLibrary: LibraryStore?
    @Environment(LibraryStore.self) private var envLibrary: LibraryStore?
    @Environment(\.themeManager) private var themeManager
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var selectedIDs: Set<String> = []

    init(playlistID: UUID, library: LibraryStore? = nil) {
        self.playlistID = playlistID
        self.explicitLibrary = library
    }

    private var resolvedLibrary: LibraryStore? {
        explicitLibrary ?? envLibrary
    }

    private var existingTrackIDs: Set<String> {
        guard let library = resolvedLibrary,
              let playlist = library.playlists.first(where: { $0.id == playlistID }) else {
            return []
        }
        return Set(playlist.trackIDs)
    }

    private var filteredTracks: [Track] {
        let all = resolvedLibrary?.tracks ?? []
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return all }
        return all.filter {
            $0.title.localizedStandardContains(trimmed)
                || $0.artist.localizedStandardContains(trimmed)
                || $0.album.localizedStandardContains(trimmed)
        }
    }

    var body: some View {
        let tracks = filteredTracks
        let existing = existingTrackIDs
        NavigationStack {
            Group {
                if let library = resolvedLibrary {
                    if library.tracks.isEmpty {
                        ContentUnavailableView(
                            L10n.tr("No Songs Yet"),
                            systemImage: "music.note",
                            description: Text(L10n.tr("Add a folder in the Folders tab to build your library."))
                        )
                    } else if tracks.isEmpty {
                        ContentUnavailableView(
                            L10n.tr("No matching songs"),
                            systemImage: "magnifyingglass"
                        )
                    } else {
                        List(tracks) { track in
                            let isSelected = selectedIDs.contains(track.id)
                            let alreadyInPlaylist = existing.contains(track.id)
                            Button {
                                if isSelected {
                                    selectedIDs.remove(track.id)
                                } else {
                                    selectedIDs.insert(track.id)
                                }
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                                        .font(.title3)
                                        .foregroundStyle(isSelected ? themeManager.accentColor : Color.secondary)

                                    ArtworkView(artworkKey: track.artworkKey, size: 40)

                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(track.title)
                                            .foregroundStyle(.primary)
                                            .lineLimit(1)
                                        Text("\(L10n.displayArtist(track.artist)) — \(L10n.displayAlbum(track.album))")
                                            .font(.footnote)
                                            .foregroundStyle(.secondary)
                                            .lineLimit(1)
                                    }

                                    Spacer(minLength: 8)

                                    if alreadyInPlaylist {
                                        Text(L10n.tr("Added"))
                                            .font(.caption2.weight(.medium))
                                            .foregroundStyle(.secondary)
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 3)
                                            .background(Color.primary.opacity(0.06), in: Capsule())
                                    }

                                    Text(formatTime(track.duration))
                                        .font(.caption.monospacedDigit())
                                        .foregroundStyle(.secondary)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.borderless)
                        }
                        .listStyle(.plain)
                        .hiddenScrollBackgroundIfThemed(themeManager)
                    }
                } else {
                    ProgressView()
                }
            }
            .background { ThemeBackdropView() }
            .navigationTitle(L10n.tr("Add Songs"))
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: L10n.tr("Search songs to add"))
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(L10n.tr("Cancel"), role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.addSongsCount(selectedIDs.count)) {
                        if let library = resolvedLibrary {
                            let ordered = library.tracks.map(\.id).filter { selectedIDs.contains($0) }
                            if !ordered.isEmpty {
                                library.add(ordered, toPlaylist: playlistID)
                            }
                        }
                        dismiss()
                    }
                    .fontWeight(.semibold)
                    .disabled(selectedIDs.isEmpty)
                }
            }
        }
    }
}
