import SwiftUI

struct ArtworkView: View {
    let artworkKey: String?
    /// Optional remote `https://` artwork URL loaded via the in-memory thumbnail cache when `artworkKey` is nil.
    var remoteArtworkURL: String? = nil
    /// Fixed edge length, or nil to fill the available width as a square.
    var size: CGFloat?
    var cornerRadius: CGFloat? = nil

    @Environment(LibraryStore.self) private var library
    @State private var image: UIImage?

    private var taskKey: String {
        "\(artworkKey ?? "")|\(remoteArtworkURL ?? "")"
    }

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
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius ?? ((size ?? 160) * 0.1), style: .continuous))
            .task(id: taskKey) {
                if let artworkKey {
                    let cache = library.artwork
                    image = await Task.detached { cache.image(forKey: artworkKey) }.value
                    return
                }
                if let remoteArtworkURL {
                    if let cached = CloudThumbnailCache.shared.cachedImage(for: remoteArtworkURL) {
                        image = cached
                        return
                    }
                    image = nil
                    let loaded = await CloudThumbnailCache.shared.loadImage(from: remoteArtworkURL)
                    if !Task.isCancelled {
                        image = loaded
                    }
                    return
                }
                image = nil
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
            ArtworkView(artworkKey: track.artworkKey, remoteArtworkURL: track.remoteArtworkURL, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .foregroundStyle(isCurrent ? Color.accentColor : .primary)
                    .lineLimit(1)
                Text(subtitle ?? L10n.displayArtist(track.artist))
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
    var onTap: ((Int, Track) -> Void)?

    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player

    var body: some View {
        ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
            Button {
                if let onTap {
                    onTap(index, track)
                } else {
                    player.play((queue ?? tracks).map(\.id), startAt: index)
                }
            } label: {
                TrackRow(track: track, subtitle: subtitle?(track))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!library.isAvailable(track))
            .contextMenu { TrackMenuItems(ids: [track.id], favouriteID: track.isEphemeral ? nil : track.id) }
        }
    }
}

struct ReorderableTrackRow: View {
    let track: Track
    let index: Int
    let scopeID: String
    var subtitle: String? = nil
    var trackNumber: Int? = nil
    var isActive: Bool = false
    var isAvailable: Bool = true
    let onTap: () -> Void
    let onMove: (Int, Int) -> Void
    var onRemove: (() -> Void)? = nil
    var removeHelp: String? = nil

    @State private var isDropTargeted = false
    @State private var isRowHovered = false
    @State private var isRemoveHovered = false

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onTap) {
                HStack(spacing: 10) {
                    if let trackNumber {
                        Text("\(trackNumber)")
                            .font(.subheadline.monospacedDigit())
                            .foregroundStyle(isActive ? Color.accentColor : .secondary)
                            .frame(minWidth: 26, alignment: .trailing)
                    }
                    TrackRow(track: track, subtitle: subtitle)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .disabled(!isAvailable)

            if let onRemove {
                let helpLabel = removeHelp ?? L10n.tr("Remove")
                Button {
                    onRemove()
                } label: {
                    Image(systemName: "minus.circle")
                        .font(.subheadline)
                        .foregroundStyle(isRemoveHovered ? Color.red : Color.secondary)
                        .frame(width: 26, height: 26)
                        .background(
                            isRemoveHovered ? Color.red.opacity(0.12) : .clear,
                            in: RoundedRectangle(cornerRadius: 6, style: .continuous)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .onHover { isRemoveHovered = $0 }
                .help(helpLabel)
                .accessibilityLabel(helpLabel)
                #if targetEnvironment(macCatalyst)
                .opacity(isRowHovered ? 1 : 0)
                .allowsHitTesting(isRowHovered)
                #endif
            }

            Image(systemName: "line.3.horizontal")
                .font(.body)
                .foregroundStyle(.tertiary)
                .frame(width: 28, height: 36)
                .contentShape(Rectangle())
                .accessibilityLabel(L10n.tr("Reorder %@", track.title))
                .draggable("\(scopeID):\(index)") {
                    Text(track.title)
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
                }
        }
        .onHover { isRowHovered = $0 }
        .listRowBackground(
            isDropTargeted
                ? Color.accentColor.opacity(0.20)
                : (isActive
                    ? Color.accentColor.opacity(0.12)
                    : (isRowHovered ? Color.primary.opacity(0.04) : nil))
        )
        .dropDestination(for: String.self) { items, _ in
            guard let payload = items.first,
                  let sourceIndex = Self.parseSourceIndex(payload, scopeID: scopeID),
                  sourceIndex != index else {
                return false
            }
            let destination = sourceIndex < index ? index + 1 : index
            onMove(sourceIndex, destination)
            return true
        } isTargeted: { targeted in
            isDropTargeted = targeted
        }
    }

    static func parseSourceIndex(_ payload: String, scopeID: String) -> Int? {
        let prefix = "\(scopeID):"
        guard payload.hasPrefix(prefix) else { return nil }
        return Int(payload.dropFirst(prefix.count))
    }
}

struct TrackSortMenu: View {
    let selection: TrackSortOrder
    let onSelect: (TrackSortOrder) -> Void

    var body: some View {
        Menu(L10n.tr("Sort"), systemImage: "arrow.up.arrow.down") {
            ForEach(TrackSortOrder.allCases) { order in
                Button {
                    onSelect(order)
                } label: {
                    if selection == order {
                        Label(order.localizedTitle, systemImage: "checkmark")
                    } else {
                        Text(order.localizedTitle)
                    }
                }
            }
        }
    }
}

/// A request to name a new playlist. A menu closes as soon as an item is chosen, so the name
/// prompt is shown by `NewPlaylistAlert` instead of by the menu; the menu item leaves here what
/// to do with the name.
@MainActor @Observable
final class NewPlaylistPrompt {
    var create: ((String) -> Void)?
    var songCount: Int?
}

/// Hosts the "New Playlist" name prompt for the menus inside it. Applied once per presentation
/// (the tabs, and each sheet), because a prompt cannot appear from underneath a sheet.
///
/// It must not read stores from the environment: on the Mac, a view that presents from the
/// root of a sheet over another sheet is first evaluated with an empty environment, and reading
/// a store there stops the app.
struct NewPlaylistAlert: ViewModifier {
    @State private var prompt = NewPlaylistPrompt()

    func body(content: Content) -> some View {
        content
            .environment(prompt)
            .sheet(
                isPresented: Binding(
                    get: { prompt.create != nil },
                    set: {
                        if !$0 {
                            prompt.create = nil
                            prompt.songCount = nil
                        }
                    }
                )
            ) {
                NamePromptSheet(
                    title: L10n.tr("New Playlist"),
                    subtitle: L10n.tr("Give your playlist a name."),
                    placeholder: L10n.tr("Playlist name"),
                    confirmLabel: L10n.tr("Create"),
                    systemImage: "music.note.list",
                    badgeText: prompt.songCount.flatMap { $0 > 0 ? L10n.tr("Includes %@", L10n.songCount($0)) : nil }
                ) { trimmed in
                    let create = prompt.create
                    prompt.create = nil
                    prompt.songCount = nil
                    create?(trimmed)
                }
                .environment(\.locale, LanguageManager.shared.effectiveLocale)
            }
    }
}

/// Compact modal sheet for creating or renaming a playlist or queue, styled to match Musico's
/// native card & material language without depending on environment stores.
struct NamePromptSheet: View {
    let title: String
    let subtitle: String
    let placeholder: String
    let confirmLabel: String
    let systemImage: String
    var badgeText: String? = nil
    let onConfirm: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.languageManager) private var languageManager
    @State private var text: String
    @FocusState private var isFocused: Bool

    init(
        title: String,
        subtitle: String,
        placeholder: String,
        confirmLabel: String,
        systemImage: String,
        badgeText: String? = nil,
        initialName: String = "",
        onConfirm: @escaping (String) -> Void
    ) {
        self.title = title
        self.subtitle = subtitle
        self.placeholder = placeholder
        self.confirmLabel = confirmLabel
        self.systemImage = systemImage
        self.badgeText = badgeText
        self.onConfirm = onConfirm
        _text = State(initialValue: initialName)
    }

    private var trimmed: String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var body: some View {
        let content = VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .center, spacing: 14) {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [Color.accentColor.opacity(0.22), Color.accentColor.opacity(0.08)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 48, height: 48)
                    .overlay {
                        RoundedRectangle(cornerRadius: 12, style: .continuous)
                            .strokeBorder(Color.accentColor.opacity(0.20), lineWidth: 0.75)
                    }
                    .overlay {
                        Image(systemName: systemImage)
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(Color.accentColor)
                    }

                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.title3.weight(.semibold))
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if let badgeText, !badgeText.isEmpty {
                        Text(badgeText)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(Color.accentColor)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color.accentColor.opacity(0.12), in: Capsule())
                            .padding(.top, 2)
                    }
                }
                Spacer(minLength: 0)
            }

            HStack(spacing: 8) {
                TextField(placeholder, text: $text)
                    .textFieldStyle(.plain)
                    .font(.body)
                    .focused($isFocused)
                    .submitLabel(.done)
                    .onSubmit { submit() }

                if !text.isEmpty {
                    Button {
                        text = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L10n.tr("Clear Filter"))
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(Color(.secondarySystemFill).opacity(0.65), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(
                        isFocused ? Color.accentColor : Color.primary.opacity(0.12),
                        lineWidth: isFocused ? 1.5 : 0.75
                    )
            }
            .task {
                try? await Task.sleep(for: .milliseconds(120))
                isFocused = true
            }

            HStack(spacing: 10) {
                Spacer()
                Button(L10n.tr("Cancel"), role: .cancel) {
                    dismiss()
                }
                .buttonStyle(.bordered)
                .keyboardShortcut(.cancelAction)

                Button(confirmLabel) {
                    submit()
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(trimmed.isEmpty)
            }
        }
        .padding(20)
        #if targetEnvironment(macCatalyst)
        .frame(minWidth: 380, idealWidth: 420, maxWidth: 460)
        .presentationDragIndicator(.hidden)
        #endif
        .presentationCornerRadius(18)
        .environment(\.locale, languageManager.effectiveLocale)

        if #available(iOS 18.0, macCatalyst 18.0, *) {
            content.presentationSizing(.fitted)
        } else {
            content.presentationDetents([.height(230)])
        }
    }

    private func submit() {
        let value = trimmed
        guard !value.isEmpty else { return }
        onConfirm(value)
        dismiss()
    }
}

/// Shared actions for a song or a group of songs (album, artist, folder, playlist).
struct TrackMenuItems: View {
    let ids: [String]
    var favouriteID: String?

    @Environment(LibraryStore.self) private var library
    @Environment(QueueStore.self) private var queues
    @Environment(PlayerController.self) private var player
    @Environment(PlayHistoryStore.self) private var history: PlayHistoryStore?
    @Environment(NewPlaylistPrompt.self) private var newPlaylist
    @Environment(CloudExportPrompt.self) private var cloudExport
    @Environment(SongInfoPrompt.self) private var songInfo
    @Environment(\.appNavigation) private var navigation
    @Environment(\.extensionStore) private var extensionStore

    private var hasEphemeralTrack: Bool {
        ids.contains { library.track($0)?.isEphemeral == true || $0.hasPrefix(Track.ephemeralIDPrefix) }
    }

    private func canSaveCloudTrack(_ track: Track) -> Bool {
        guard track.isCloud else { return false }
        if track.isEphemeral, let extID = track.ephemeralExtensionID {
            return extensionStore.extension(forID: extID)?.manifest.capabilities.contains(.stream) ?? false
        }
        return true
    }

    var body: some View {
        Button(L10n.tr("Play Next"), systemImage: "text.line.first.and.arrowtriangle.forward") { player.playNext(ids) }
        Button(L10n.tr("Add to Queue"), systemImage: "text.append") { player.addToQueue(ids, queue: nil) }
        if queues.queues.count > 1 {
            Menu(L10n.tr("Add to Another Queue"), systemImage: "rectangle.stack.badge.plus") {
                ForEach(queues.queues.filter { $0.id != queues.activeID }) { queue in
                    Button(L10n.displayQueueName(queue.name)) { player.addToQueue(ids, queue: queue.id) }
                }
            }
        }
        Button(L10n.tr("Play in New Queue"), systemImage: "plus.rectangle.on.rectangle") { player.playInNewQueue(ids) }
        if !hasEphemeralTrack {
            if ids.count == 1, let trackID = ids.first, library.track(trackID) != nil {
                Button(L10n.tr("Song Info"), systemImage: "info.circle") {
                    songInfo.show(trackID, library: library, history: history, navigation: navigation)
                }
            }
            Divider()
            Menu(L10n.tr("Add to Playlist"), systemImage: "music.note.list") {
                ForEach(library.playlists) { playlist in
                    Button(playlist.name) { library.add(ids, toPlaylist: playlist.id) }
                }
                Button(L10n.tr("New Playlist…"), systemImage: "plus") {
                    newPlaylist.songCount = ids.count
                    newPlaylist.create = { [library, ids] name in
                        library.add(ids, toPlaylist: library.createPlaylist(name: name))
                    }
                }
            }
            if let favouriteID {
                let isFavourite = library.isFavourite(favouriteID)
                Button(isFavourite ? L10n.tr("Remove from Favourites") : L10n.tr("Add to Favourites"), systemImage: isFavourite ? "heart.slash" : "heart") {
                    library.toggleFavourite(favouriteID)
                }
            }
        }
        if ids.count == 1, let trackID = ids.first, let track = library.track(trackID), canSaveCloudTrack(track) {
            Divider()
            Button(L10n.tr("Save to Local Folder…"), systemImage: "square.and.arrow.down") {
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
    var queueName: String? = nil
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
            if let queueName {
                player.playInNamedQueue(ids, name: queueName, startAt: 0, shuffle: false)
            } else {
                if queues.active.shuffle { player.toggleShuffle() }
                player.play(ids, startAt: 0)
            }
        } label: {
            Label(L10n.tr("Play"), systemImage: "play.fill").frame(maxWidth: .infinity)
        }
    }

    private var shuffleButton: some View {
        Button {
            let startIndex = Int.random(in: 0..<max(ids.count, 1))
            if let queueName {
                player.playInNamedQueue(ids, name: queueName, startAt: startIndex, shuffle: true)
            } else {
                if !queues.active.shuffle { player.toggleShuffle() }
                player.play(ids, startAt: startIndex)
            }
        } label: {
            Label(L10n.tr("Shuffle"), systemImage: "shuffle").frame(maxWidth: .infinity)
        }
    }
}
