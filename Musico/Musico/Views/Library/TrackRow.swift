import SwiftUI

struct ArtworkView: View {
    let artworkKey: String?
    /// Fixed edge length, or nil to fill the available width as a square.
    var size: CGFloat?

    @Environment(LibraryStore.self) private var library
    @State private var image: UIImage?

    var body: some View {
        Color(.tertiarySystemFill)
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Image(systemName: "music.note")
                        .font(.system(size: (size ?? 120) * 0.4))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: (size ?? 160) * 0.1, style: .continuous))
            .task(id: artworkKey) {
                guard let artworkKey else { image = nil; return }
                let cache = library.artwork
                image = await Task.detached { cache.image(forKey: artworkKey) }.value
            }
    }
}

struct TrackRow: View {
    let track: Track
    var subtitle: String?

    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player

    var body: some View {
        let isCurrent = player.currentTrack?.id == track.id
        HStack(spacing: 12) {
            ArtworkView(artworkKey: track.artworkKey, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .foregroundStyle(isCurrent ? Color.accentColor : .primary)
                    .lineLimit(1)
                Text(subtitle ?? track.artist)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if isCurrent {
                Image(systemName: player.isPlaying ? "waveform" : "pause.fill")
                    .foregroundStyle(Color.accentColor)
                    .symbolEffect(.variableColor.iterative, isActive: player.isPlaying)
            }
            Text(formatTime(track.duration))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
        .opacity(library.isAvailable(track) ? 1 : 0.4)
    }
}

/// Rows for a list of songs. Tapping one plays the whole list from that song.
struct TrackRows: View {
    let tracks: [Track]
    /// The list to play when `tracks` is only the start of a longer one.
    var queue: [Track]?
    var subtitle: ((Track) -> String)?

    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player

    var body: some View {
        ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
            Button {
                player.play((queue ?? tracks).map(\.id), startAt: index)
            } label: {
                TrackRow(track: track, subtitle: subtitle?(track))
            }
            .buttonStyle(.plain)
            .disabled(!library.isAvailable(track))
            .contextMenu { TrackMenuItems(ids: [track.id], favouriteID: track.id) }
        }
    }
}

/// A request to name a new playlist. A menu closes as soon as an item is chosen, so the name
/// prompt is shown by `NewPlaylistAlert` instead of by the menu; the menu item leaves here what
/// to do with the name.
@MainActor @Observable
final class NewPlaylistPrompt {
    var create: ((String) -> Void)?
}

/// Hosts the "New Playlist" name prompt for the menus inside it. Applied once per presentation
/// (the tabs, and each sheet), because an alert cannot appear from underneath a sheet.
///
/// It must not read stores from the environment: on the Mac, a view that shows an alert at the
/// root of a sheet over another sheet is first evaluated with an empty environment, and reading
/// a store there stops the app.
struct NewPlaylistAlert: ViewModifier {
    @State private var prompt = NewPlaylistPrompt()
    @State private var name = ""

    func body(content: Content) -> some View {
        content
            .environment(prompt)
            .alert("New Playlist", isPresented: Binding(get: { prompt.create != nil }, set: { if !$0 { prompt.create = nil } })) {
                TextField("Name", text: $name)
                Button("Cancel", role: .cancel) { name = "" }
                Button("Create") {
                    let trimmed = name.trimmingCharacters(in: .whitespaces)
                    if !trimmed.isEmpty { prompt.create?(trimmed) }
                    name = ""
                }
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            } message: {
                Text("Enter a name for the playlist.")
            }
    }
}

/// Shared actions for a song or a group of songs (album, artist, folder, playlist).
struct TrackMenuItems: View {
    let ids: [String]
    var favouriteID: String?

    @Environment(LibraryStore.self) private var library
    @Environment(QueueStore.self) private var queues
    @Environment(PlayerController.self) private var player
    @Environment(NewPlaylistPrompt.self) private var newPlaylist
    @Environment(CloudExportPrompt.self) private var cloudExport

    var body: some View {
        Button("Play Next", systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(ids) }
        Button("Add to Queue", systemImage: "text.append") { player.addToQueue(ids, queue: nil) }
        if queues.queues.count > 1 {
            Menu("Add to Another Queue", systemImage: "rectangle.stack.badge.plus") {
                ForEach(queues.queues.filter { $0.id != queues.activeID }) { queue in
                    Button(queue.name) { player.addToQueue(ids, queue: queue.id) }
                }
            }
        }
        Button("Play in New Queue", systemImage: "plus.rectangle.on.rectangle") { player.playInNewQueue(ids) }
        Divider()
        Menu("Add to Playlist", systemImage: "music.note.list") {
            ForEach(library.playlists) { playlist in
                Button(playlist.name) { library.add(ids, toPlaylist: playlist.id) }
            }
            Button("New Playlist…", systemImage: "plus") {
                newPlaylist.create = { [library, ids] name in
                    library.add(ids, toPlaylist: library.createPlaylist(name: name))
                }
            }
        }
        if let favouriteID {
            let isFavourite = library.isFavourite(favouriteID)
            Button(isFavourite ? "Remove from Favourites" : "Add to Favourites", systemImage: isFavourite ? "heart.slash" : "heart") {
                library.toggleFavourite(favouriteID)
            }
        }
        if ids.count == 1, let trackID = ids.first, let track = library.track(trackID), track.isCloud {
            Divider()
            Button("Save to Local Folder…", systemImage: "square.and.arrow.down") {
                let root = library.root(track.rootID)
                cloudExport.request = CloudExportPrompt.Request(track: track, root: root) { [library] savedURL in
                    if library.containsLocalURL(savedURL) {
                        await library.rescan()
                    }
                }
            }
        }
    }
}

/// "Play" and "Shuffle" buttons shown at the top of a collection.
struct PlayShuffleButtons: View {
    let ids: [String]
    @Environment(PlayerController.self) private var player
    @Environment(QueueStore.self) private var queues

    var body: some View {
        Group {
            if #available(iOS 26.0, macCatalyst 26.0, *) {
                HStack(spacing: 12) {
                    playButton
                        .buttonStyle(.glassProminent)
                    shuffleButton
                        .buttonStyle(.glass)
                }
            } else {
                HStack(spacing: 12) {
                    playButton
                    shuffleButton
                }
                .buttonStyle(.bordered)
            }
        }
        .disabled(ids.isEmpty)
        .listRowSeparator(.hidden)
    }

    private var playButton: some View {
        Button {
            if queues.active.shuffle { player.toggleShuffle() }
            player.play(ids, startAt: 0)
        } label: {
            Label("Play", systemImage: "play.fill").frame(maxWidth: .infinity)
        }
    }

    private var shuffleButton: some View {
        Button {
            if !queues.active.shuffle { player.toggleShuffle() }
            player.play(ids, startAt: Int.random(in: 0..<max(ids.count, 1)))
        } label: {
            Label("Shuffle", systemImage: "shuffle").frame(maxWidth: .infinity)
        }
    }
}
