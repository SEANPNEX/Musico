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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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
            .scaleEffect(!reduceMotion && isHovered ? 1.025 : 1.0)
            .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: isHovered)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
        .contextMenu { TrackMenuItems(ids: album.trackIDs) }
    }
}

struct HeroHeaderBackdrop: View {
    let artworkKey: String?
    var fallbackColor: Color

    @Environment(LibraryStore.self) private var library
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [fallbackColor.opacity(0.22), fallbackColor.opacity(0.04), .clear],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .blur(radius: 46)
                    .saturation(1.25)
                    .opacity(0.32)
                    .scaleEffect(1.25)
                    .clipped()
                    .mask(
                        LinearGradient(
                            colors: [.white, .white.opacity(0.65), .clear],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.35), value: image != nil)
        .task(id: artworkKey) {
            guard let artworkKey else {
                image = nil
                return
            }
            let cache = library.artwork
            image = await Task.detached { cache.image(forKey: artworkKey) }.value
        }
    }
}

struct ArtistAvatarView: View {
    let artist: String
    let artworkKey: String?
    var size: CGFloat = 44

    @Environment(LibraryStore.self) private var library
    @Environment(\.themeManager) private var themeManager
    @State private var image: UIImage?

    private var monogram: String {
        let display = L10n.displayArtist(artist).trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = display.first else { return "?" }
        return String(first).uppercased()
    }

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                ZStack {
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [themeManager.accentColor.opacity(0.26), themeManager.accentColor.opacity(0.11)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    Text(monogram)
                        .font(.system(size: size * 0.42, weight: .semibold, design: .rounded))
                        .foregroundStyle(themeManager.accentColor)
                }
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay {
            Circle()
                .strokeBorder(Color.primary.opacity(0.10), lineWidth: 0.5)
        }
        .task(id: artworkKey) {
            guard let artworkKey else {
                image = nil
                return
            }
            let cache = library.artwork
            image = await Task.detached { cache.image(forKey: artworkKey) }.value
        }
    }
}

struct AlbumDetailView: View {
    let album: LibraryStore.Album
    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.themeManager) private var themeManager

    private var usesWideHero: Bool {
        #if targetEnvironment(macCatalyst)
        return true
        #else
        return sizeClass == .regular
        #endif
    }

    var body: some View {
        // Use the live album if it still exists so a rescan updates this screen.
        let album = library.albums.first { $0.id == self.album.id } ?? self.album
        let tracks = library.tracks(album.trackIDs)
        let totalDuration = tracks.reduce(0) { $0 + $1.duration }
        let queueName = "Album: \(album.title)"
        List {
            heroHeader(album: album, tracks: tracks, totalDuration: totalDuration, queueName: queueName)
                .listRowSeparator(.hidden)
            if !usesWideHero {
                PlayShuffleButtons(ids: album.trackIDs, queueName: queueName)
            }
            TrackRows(tracks: tracks, queueName: queueName) { track in
                let artist = L10n.displayArtist(track.artist)
                return track.trackNumber.map { "\($0) · \(artist)" } ?? artist
            }
        }
        .listStyle(.plain)
        .hiddenScrollBackgroundIfThemed(themeManager)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Menu(L10n.tr("More"), systemImage: "ellipsis.circle") { TrackMenuItems(ids: album.trackIDs) }
        }
    }

    @ViewBuilder
    private func heroHeader(
        album: LibraryStore.Album,
        tracks: [Track],
        totalDuration: TimeInterval,
        queueName: String
    ) -> some View {
        let metadata = "\(L10n.songCount(tracks.count)) · \(formatTime(totalDuration))"
        Group {
            if usesWideHero {
                HStack(alignment: .bottom, spacing: 22) {
                    ArtworkView(artworkKey: album.artworkKey, size: 180, cornerRadius: 14)
                        .shadow(color: .black.opacity(0.22), radius: 18, y: 8)

                    VStack(alignment: .leading, spacing: 8) {
                        Text(L10n.tr("Album"))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(themeManager.accentColor)
                            .textCase(.uppercase)

                        Text(L10n.displayAlbum(album.title))
                            .font(.title.bold())
                            .lineLimit(2)

                        Text(L10n.displayArtist(album.artist))
                            .font(.title3.weight(.medium))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)

                        Text(metadata)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                        HStack(spacing: 10) {
                            Button {
                                player.playInNamedQueue(album.trackIDs, name: queueName, startAt: 0, shuffle: false)
                            } label: {
                                Label(L10n.tr("Play"), systemImage: "play.fill")
                                    .lineLimit(1)
                                    .fixedSize(horizontal: true, vertical: false)
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(album.trackIDs.isEmpty)

                            Button {
                                let start = Int.random(in: 0..<max(album.trackIDs.count, 1))
                                player.playInNamedQueue(album.trackIDs, name: queueName, startAt: start, shuffle: true)
                            } label: {
                                Label(L10n.tr("Shuffle"), systemImage: "shuffle")
                                    .lineLimit(1)
                                    .fixedSize(horizontal: true, vertical: false)
                            }
                            .buttonStyle(.bordered)
                            .disabled(album.trackIDs.isEmpty)
                        }
                        .padding(.top, 4)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } else {
                VStack(spacing: 10) {
                    ArtworkView(artworkKey: album.artworkKey, size: 210, cornerRadius: 16)
                        .shadow(color: .black.opacity(0.22), radius: 18, y: 8)
                    Text(L10n.displayAlbum(album.title))
                        .font(.title2.weight(.bold))
                        .multilineTextAlignment(.center)
                    Text(L10n.displayArtist(album.artist))
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.secondary)
                    Text(metadata)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(16)
        .background {
            HeroHeaderBackdrop(artworkKey: album.artworkKey, fallbackColor: themeManager.accentColor)
        }
        .padding(.vertical, 4)
    }
}

struct ArtistsView: View {
    @Environment(LibraryStore.self) private var library
    @Environment(\.themeManager) private var themeManager

    var body: some View {
        List {
            if library.artists.isEmpty {
                ContentUnavailableView(L10n.tr("No Artists Yet"), systemImage: "music.mic",
                                       description: Text(L10n.tr("Artists appear once your library has songs.")))
            }
            ForEach(library.artists, id: \.self) { artist in
                let artistAlbums = library.albums(byArtist: artist)
                let artistTracks = library.tracks(byArtist: artist)
                let artworkKey = artistAlbums.lazy.compactMap(\.artworkKey).first
                    ?? artistTracks.lazy.compactMap(\.artworkKey).first
                NavigationLink(value: artist) {
                    HStack(spacing: 12) {
                        ArtistAvatarView(artist: artist, artworkKey: artworkKey, size: 44)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L10n.displayArtist(artist))
                                .font(.body.weight(.medium))
                                .lineLimit(1)
                            Text("\(L10n.albumCount(artistAlbums.count)) · \(L10n.songCount(artistTracks.count))")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    .padding(.vertical, 2)
                }
                .contextMenu { TrackMenuItems(ids: artistTracks.map(\.id)) }
            }
        }
        .listStyle(.plain)
        .hiddenScrollBackgroundIfThemed(themeManager)
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
    @Environment(PlayerController.self) private var player
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.themeManager) private var themeManager

    private var usesWideHero: Bool {
        #if targetEnvironment(macCatalyst)
        return true
        #else
        return sizeClass == .regular
        #endif
    }

    var body: some View {
        let tracks = library.tracks(byArtist: artist)
        let albums = library.albums(byArtist: artist)
        let trackIDs = tracks.map(\.id)
        let totalDuration = tracks.reduce(0) { $0 + $1.duration }
        let artworkKey = albums.lazy.compactMap(\.artworkKey).first
            ?? tracks.lazy.compactMap(\.artworkKey).first
        let queueName = "Artist: \(artist)"
        List {
            heroHeader(
                artworkKey: artworkKey,
                albumsCount: albums.count,
                tracksCount: tracks.count,
                totalDuration: totalDuration,
                trackIDs: trackIDs,
                queueName: queueName
            )
            .listRowSeparator(.hidden)
            if !usesWideHero {
                PlayShuffleButtons(ids: trackIDs, queueName: queueName)
            }
            if !albums.isEmpty {
                Section(L10n.tr("Albums")) {
                    AlbumGrid(albums: albums).listRowSeparator(.hidden)
                }
            }
            Section(L10n.tr("Songs")) {
                TrackRows(tracks: tracks, queueName: queueName) { L10n.displayAlbum($0.album) }
            }
        }
        .listStyle(.plain)
        .hiddenScrollBackgroundIfThemed(themeManager)
        .navigationTitle(L10n.displayArtist(artist))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            Menu(L10n.tr("More"), systemImage: "ellipsis.circle") { TrackMenuItems(ids: trackIDs) }
        }
    }

    @ViewBuilder
    private func heroHeader(
        artworkKey: String?,
        albumsCount: Int,
        tracksCount: Int,
        totalDuration: TimeInterval,
        trackIDs: [String],
        queueName: String
    ) -> some View {
        let metadata = "\(L10n.albumCount(albumsCount)) · \(L10n.songCount(tracksCount)) · \(formatTime(totalDuration))"
        Group {
            if usesWideHero {
                HStack(alignment: .bottom, spacing: 22) {
                    ArtistAvatarView(artist: artist, artworkKey: artworkKey, size: 136)
                        .shadow(color: .black.opacity(0.20), radius: 16, y: 8)

                    VStack(alignment: .leading, spacing: 8) {
                        Text(L10n.tr("Artist"))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(themeManager.accentColor)
                            .textCase(.uppercase)

                        Text(L10n.displayArtist(artist))
                            .font(.title.bold())
                            .lineLimit(2)

                        Text(metadata)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)

                        HStack(spacing: 10) {
                            Button {
                                player.playInNamedQueue(trackIDs, name: queueName, startAt: 0, shuffle: false)
                            } label: {
                                Label(L10n.tr("Play"), systemImage: "play.fill")
                                    .lineLimit(1)
                                    .fixedSize(horizontal: true, vertical: false)
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(trackIDs.isEmpty)

                            Button {
                                let start = Int.random(in: 0..<max(trackIDs.count, 1))
                                player.playInNamedQueue(trackIDs, name: queueName, startAt: start, shuffle: true)
                            } label: {
                                Label(L10n.tr("Shuffle"), systemImage: "shuffle")
                                    .lineLimit(1)
                                    .fixedSize(horizontal: true, vertical: false)
                            }
                            .buttonStyle(.bordered)
                            .disabled(trackIDs.isEmpty)
                        }
                        .padding(.top, 4)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            } else {
                VStack(spacing: 10) {
                    ArtistAvatarView(artist: artist, artworkKey: artworkKey, size: 120)
                        .shadow(color: .black.opacity(0.20), radius: 14, y: 6)
                    Text(L10n.displayArtist(artist))
                        .font(.title2.weight(.bold))
                        .multilineTextAlignment(.center)
                    Text(metadata)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(16)
        .background {
            HeroHeaderBackdrop(artworkKey: artworkKey, fallbackColor: themeManager.accentColor)
        }
        .padding(.vertical, 4)
    }
}
