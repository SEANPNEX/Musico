import SwiftUI

struct CloudCollectionPreviewSheet: View {
    let extensionDescriptor: CloudSearchExtensionDescriptor
    let item: CloudSearchResult
    private let explicitLibrary: LibraryStore?
    private let explicitPlayer: PlayerController?
    private let explicitExtensionStore: ExtensionStore?
    private let explicitDownloadManager: DownloadManager?

    @Environment(LibraryStore.self) private var envLibrary: LibraryStore?
    @Environment(PlayerController.self) private var envPlayer: PlayerController?
    @Environment(\.extensionStore) private var envExtensionStore
    @Environment(\.downloadManager) private var envDownloadManager
    @Environment(\.languageManager) private var languageManager

    init(
        extensionDescriptor: CloudSearchExtensionDescriptor,
        item: CloudSearchResult,
        library: LibraryStore? = nil,
        player: PlayerController? = nil,
        extensionStore: ExtensionStore? = nil,
        downloadManager: DownloadManager? = nil
    ) {
        self.extensionDescriptor = extensionDescriptor
        self.item = item
        self.explicitLibrary = library
        self.explicitPlayer = player
        self.explicitExtensionStore = extensionStore
        self.explicitDownloadManager = downloadManager
    }

    private var library: LibraryStore? { explicitLibrary ?? envLibrary }
    private var player: PlayerController? { explicitPlayer ?? envPlayer }
    private var extensionStore: ExtensionStore { explicitExtensionStore ?? envExtensionStore }
    private var downloadManager: DownloadManager { explicitDownloadManager ?? envDownloadManager }

    var body: some View {
        CloudCollectionPreviewContent(
            extensionDescriptor: extensionDescriptor,
            item: item,
            library: library,
            player: player,
            extensionStore: extensionStore
        )
        .modifier(CloudExportHostModifier())
        .environment(\.downloadManager, downloadManager)
        .environment(\.extensionStore, extensionStore)
        .environment(\.languageManager, languageManager)
        .environment(\.locale, languageManager.effectiveLocale)
    }
}

private struct CloudCollectionPreviewContent: View {
    let extensionDescriptor: CloudSearchExtensionDescriptor
    let item: CloudSearchResult
    let library: LibraryStore?
    let player: PlayerController?
    let extensionStore: ExtensionStore

    @Environment(CloudExportPrompt.self) private var cloudExport
    @Environment(\.dismiss) private var dismiss

    @State private var collection: CloudPlaylistResult?
    @State private var previewTracks: [Track] = []
    @State private var isLoading = true
    @State private var loadError: CloudSearchErrorInfo?
    @State private var retryToken = 0
    @State private var isAddingFolder = false
    @State private var didAddFolder = false
    @State private var addFolderError: String?

    private var displayTitle: String {
        let fetched = collection?.title.trimmingCharacters(in: .whitespacesAndNewlines)
        return (fetched?.isEmpty == false) ? fetched! : item.title
    }

    private var effectiveRemoteID: String {
        collection?.remoteID ?? item.remoteId ?? ""
    }

    private var isAlreadyMounted: Bool {
        if didAddFolder { return true }
        guard let library, !effectiveRemoteID.isEmpty else { return false }
        return library.hasCloudRoot(providerID: extensionDescriptor.providerID, remoteID: effectiveRemoteID)
    }

    private var canAddFolder: Bool {
        guard let sourceUrl = item.sourceUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
              !sourceUrl.isEmpty else {
            return false
        }
        return !isAddingFolder && !isAlreadyMounted && library != nil
    }

    var body: some View {
        NavigationStack {
            Group {
                if isLoading && previewTracks.isEmpty {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text(L10n.tr("Loading collection…"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let loadError, previewTracks.isEmpty {
                    ContentUnavailableView {
                        Label(L10n.tr("Couldn’t Load Collection"), systemImage: "exclamationmark.triangle")
                    } description: {
                        if loadError.kind == .authRequired {
                            Text(L10n.tr("Configure a Cookie for “%@” in Extension Manager, then retry.", extensionDescriptor.name))
                        } else {
                            Text(loadError.message)
                        }
                    } actions: {
                        Button(L10n.tr("Retry")) {
                            retryToken += 1
                        }
                        .buttonStyle(.borderedProminent)
                    }
                } else {
                    List {
                        headerSection
                        if previewTracks.isEmpty {
                            ContentUnavailableView(
                                L10n.tr("No playable tracks found in this collection."),
                                systemImage: "music.note"
                            )
                            .listRowSeparator(.hidden)
                        } else {
                            ForEach(Array(previewTracks.enumerated()), id: \.element.id) { index, track in
                                previewTrackRow(track: track, index: index)
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle(L10n.tr("Collection Preview"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.tr("Done")) { dismiss() }
                }
            }
            .task(id: retryToken) {
                await loadCollection()
            }
            .alert(
                L10n.tr("Couldn’t Add Cloud Folder"),
                isPresented: Binding(get: { addFolderError != nil }, set: { if !$0 { addFolderError = nil } })
            ) {
                Button(L10n.tr("OK"), role: .cancel) {}
            } message: {
                Text(addFolderError ?? "")
            }
        }
        #if targetEnvironment(macCatalyst)
        .frame(minWidth: 540, idealWidth: 640, minHeight: 500, idealHeight: 620)
        #endif
    }

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                CloudThumbnailView(
                    urlString: item.artworkUrl ?? previewTracks.first?.remoteArtworkURL,
                    placeholderSymbol: item.kind == .album ? "square.stack" : "music.note.list",
                    size: 76,
                    cornerRadius: 12
                )
                VStack(alignment: .leading, spacing: 4) {
                    Text(displayTitle)
                        .font(.headline)
                        .lineLimit(2)
                    if let artist = item.artist, !artist.isEmpty {
                        Text(artist)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    HStack(spacing: 6) {
                        badge(item.kind == .album ? L10n.tr("Album") : L10n.tr("Playlist"), tint: .secondary)
                        badge(extensionDescriptor.badgeLabel, tint: .accentColor)
                        let count = previewTracks.isEmpty ? (item.trackCount ?? 0) : previewTracks.count
                        if count > 0 {
                            Text(L10n.songCount(count))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.top, 2)
                }
                Spacer(minLength: 0)
            }

            HStack(spacing: 10) {
                Button {
                    addAsCloudFolder()
                } label: {
                    if isAddingFolder {
                        HStack(spacing: 6) {
                            ProgressView().controlSize(.small)
                            Text(L10n.tr("Adding…"))
                        }
                        .frame(maxWidth: .infinity)
                    } else if isAlreadyMounted {
                        Label(L10n.tr("Added to Folders"), systemImage: "checkmark.circle.fill")
                            .frame(maxWidth: .infinity)
                    } else {
                        Label(L10n.tr("Add as Cloud Folder"), systemImage: "folder.badge.plus")
                            .frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(!canAddFolder)

                if !previewTracks.isEmpty {
                    Button {
                        playAll(startAt: 0, shuffle: false)
                    } label: {
                        Label(L10n.tr("Play"), systemImage: "play.fill")
                    }
                    .buttonStyle(.bordered)
                    .disabled(player == nil || library == nil)

                    Button {
                        let start = Int.random(in: 0..<max(previewTracks.count, 1))
                        playAll(startAt: start, shuffle: true)
                    } label: {
                        Label(L10n.tr("Shuffle"), systemImage: "shuffle")
                    }
                    .buttonStyle(.bordered)
                    .disabled(player == nil || library == nil)
                }
            }
        }
        .padding(.vertical, 6)
        .listRowSeparator(.hidden)
    }

    @ViewBuilder
    private func previewTrackRow(track: Track, index: Int) -> some View {
        let isCurrent = player?.currentTrack?.id == track.id
        Button {
            playAll(startAt: index, shuffle: false)
        } label: {
            HStack(spacing: 12) {
                CloudThumbnailView(
                    urlString: track.remoteArtworkURL ?? item.artworkUrl,
                    placeholderSymbol: "music.note",
                    size: 44
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title)
                        .foregroundStyle(isCurrent ? Color.accentColor : .primary)
                        .lineLimit(1)
                    Text(L10n.displayArtist(track.artist))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                if isCurrent, let player {
                    Image(systemName: player.isPlaying ? "waveform" : "pause.fill")
                        .foregroundStyle(Color.accentColor)
                        .symbolEffect(.variableColor.iterative, isActive: player.isPlaying)
                }
                if track.duration > 0 {
                    Text(formatTime(track.duration))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(L10n.tr("Play Next"), systemImage: "text.line.first.and.arrowtriangle.forward") {
                guard let library, let player else { return }
                library.registerEphemeralTracks([track])
                player.playNext([track.id])
            }
            Button(L10n.tr("Add to Queue"), systemImage: "text.append") {
                guard let library, let player else { return }
                library.registerEphemeralTracks([track])
                player.addToQueue([track.id], queue: nil)
            }
            Button(L10n.tr("Play in New Queue"), systemImage: "plus.rectangle.on.rectangle") {
                guard let library, let player else { return }
                library.registerEphemeralTracks([track])
                player.playInNewQueue([track.id])
            }
            if extensionDescriptor.supportsStream {
                Divider()
                Button(L10n.tr("Save to Local Folder…"), systemImage: "square.and.arrow.down") {
                    cloudExport.request = CloudExportPrompt.Request(track: track, root: nil) { savedURL in
                        if let library, library.containsLocalURL(savedURL) {
                            await library.rescan()
                        }
                    }
                }
            }
        }
    }

    private func badge(_ text: String, tint: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(tint.opacity(0.14), in: Capsule())
            .foregroundStyle(tint)
    }

    private func loadCollection() async {
        guard let sourceUrl = item.sourceUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
              !sourceUrl.isEmpty else {
            isLoading = false
            loadError = CloudSearchErrorInfo(
                kind: .runtime,
                code: "INVALID_URL",
                message: CloudSourceError.invalidURL.localizedDescription
            )
            return
        }
        isLoading = true
        loadError = nil
        do {
            let result = try await extensionStore.previewCollection(
                extensionID: extensionDescriptor.id,
                sourceURL: sourceUrl
            )
            guard !Task.isCancelled else { return }
            collection = result
            previewTracks = result.tracks.map { info in
                var mapped = Track.makeEphemeral(extensionID: extensionDescriptor.id, info: info)
                if mapped.remoteArtworkURL == nil {
                    mapped.remoteArtworkURL = item.artworkUrl
                }
                return mapped
            }
            isLoading = false
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled else { return }
            loadError = CloudSearchController.classifyError(error)
            isLoading = false
        }
    }

    private func playAll(startAt index: Int, shuffle: Bool) {
        guard let library, let player, !previewTracks.isEmpty else { return }
        library.registerEphemeralTracks(previewTracks)
        player.playInNamedQueue(
            previewTracks.map(\.id),
            name: displayTitle,
            startAt: index,
            shuffle: shuffle
        )
    }

    private func addAsCloudFolder() {
        guard let library,
              let sourceUrl = item.sourceUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
              !sourceUrl.isEmpty,
              !isAddingFolder else {
            return
        }
        isAddingFolder = true
        addFolderError = nil
        let cookie = extensionDescriptor.supportsCookie
            ? extensionStore.cookie(forExtensionID: extensionDescriptor.id)
            : nil
        Task {
            do {
                try await library.addCloudRoot(
                    sourceURL: sourceUrl,
                    cookie: cookie,
                    preferredProviderID: extensionDescriptor.providerID
                )
                didAddFolder = true
            } catch LibraryError.duplicateRoot {
                didAddFolder = true
            } catch {
                addFolderError = error.localizedDescription
            }
            isAddingFolder = false
        }
    }
}
