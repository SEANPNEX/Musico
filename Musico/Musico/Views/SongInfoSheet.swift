import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct SongInfoTarget: Identifiable {
    let id: String
    let library: LibraryStore?
    let history: PlayHistoryStore?
    let navigation: AppNavigation?
}

@MainActor @Observable
final class SongInfoPrompt {
    var target: SongInfoTarget?

    func show(
        _ trackID: String,
        library: LibraryStore? = nil,
        history: PlayHistoryStore? = nil,
        navigation: AppNavigation? = nil
    ) {
        target = SongInfoTarget(id: trackID, library: library, history: history, navigation: navigation)
    }
}

/// Hosts the Song Info sheet for context/ellipsis menus inside it without reading stores at sheet root.
struct SongInfoHostModifier: ViewModifier {
    @State private var prompt = SongInfoPrompt()

    func body(content: Content) -> some View {
        content
            .environment(prompt)
            .sheet(item: $prompt.target) { target in
                SongInfoSheet(
                    trackID: target.id,
                    library: target.library,
                    history: target.history,
                    navigation: target.navigation
                )
            }
    }
}

struct SongInfoSheet: View {
    let trackID: String
    private let explicitLibrary: LibraryStore?
    private let explicitHistory: PlayHistoryStore?
    private let explicitNavigation: AppNavigation?

    @Environment(LibraryStore.self) private var envLibrary: LibraryStore?
    @Environment(PlayHistoryStore.self) private var envHistory: PlayHistoryStore?
    @Environment(\.appNavigation) private var envNavigation
    @Environment(\.dismiss) private var dismiss

    init(
        trackID: String,
        library: LibraryStore? = nil,
        history: PlayHistoryStore? = nil,
        navigation: AppNavigation? = nil
    ) {
        self.trackID = trackID
        self.explicitLibrary = library
        self.explicitHistory = history
        self.explicitNavigation = navigation
    }

    private var library: LibraryStore? { explicitLibrary ?? envLibrary }
    private var history: PlayHistoryStore? { explicitHistory ?? envHistory }
    private var navigation: AppNavigation { explicitNavigation ?? envNavigation }

    @State private var title = ""
    @State private var artist = ""
    @State private var album = ""
    @State private var genre = ""
    @State private var pendingArtworkData: Data?
    @State private var pendingPreviewImage: UIImage?
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var showImageFileImporter = false
    @State private var loadedInitial = false
    @State private var savedFeedback = false
    @State private var techInfo: AudioTechnicalInfo?

    private var track: Track? { library?.track(trackID) }

    private var hasChanges: Bool {
        guard let track else { return false }
        return title.trimmingCharacters(in: .whitespacesAndNewlines) != track.title
            || artist.trimmingCharacters(in: .whitespacesAndNewlines) != track.artist
            || album.trimmingCharacters(in: .whitespacesAndNewlines) != track.album
            || genre.trimmingCharacters(in: .whitespacesAndNewlines) != track.displayGenre
            || pendingArtworkData != nil
    }

    var body: some View {
        NavigationStack {
            Group {
                if let library, let track = library.track(trackID) {
                    formContent(for: track, library: library)
                        .environment(library)
                } else {
                    ContentUnavailableView(L10n.tr("Song Unavailable"), systemImage: "music.note",
                                           description: Text(L10n.tr("This song is no longer in your library.")))
                }
            }
            .navigationTitle(L10n.tr("Song Info"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.tr("Close")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.tr("Save")) { saveChanges() }
                        .disabled(!hasChanges)
                }
            }
        }
        .task(id: trackID) {
            if let track, !loadedInitial {
                title = track.title
                artist = track.artist
                album = track.album
                genre = track.displayGenre
                loadedInitial = true
            }
            if let library, let track {
                let url = library.url(for: track)
                techInfo = await AudioFileInspector.inspect(url: url, track: track)
            }
        }
        .onChange(of: selectedPhotoItem) { _, newItem in
            guard let newItem else { return }
            Task {
                if let data = try? await newItem.loadTransferable(type: Data.self),
                   let image = UIImage(data: data) {
                    pendingArtworkData = data
                    pendingPreviewImage = image
                    savedFeedback = false
                }
            }
        }
        .fileImporter(isPresented: $showImageFileImporter, allowedContentTypes: [.image]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped { url.stopAccessingSecurityScopedResource() }
            }
            if let data = try? Data(contentsOf: url), let image = UIImage(data: data) {
                pendingArtworkData = data
                pendingPreviewImage = image
                savedFeedback = false
            }
        }
    }

    @ViewBuilder
    private func formContent(for track: Track, library: LibraryStore) -> some View {
        Form {
            Section(L10n.tr("Cover & Metadata")) {
                HStack(spacing: 16) {
                    Group {
                        if let pendingPreviewImage {
                            Image(uiImage: pendingPreviewImage)
                                .resizable()
                                .scaledToFill()
                                .frame(width: 84, height: 84)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        } else {
                            ArtworkView(artworkKey: track.artworkKey, size: 84)
                        }
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        let chooseFromPhotosTitle = L10n.tr("Choose from Photos…")
                        PhotosPicker(selection: $selectedPhotoItem, matching: .images) {
                            Label(chooseFromPhotosTitle, systemImage: "photo")
                        }
                        .buttonStyle(.borderless)

                        Button {
                            showImageFileImporter = true
                        } label: {
                            Label(L10n.tr("Choose Image File…"), systemImage: "folder")
                        }
                        .buttonStyle(.borderless)
                    }
                }
                .padding(.vertical, 4)

                labeledField(L10n.tr("Title"), text: $title)
                labeledField(L10n.tr("Artist"), text: $artist)
                labeledField(L10n.tr("Album"), text: $album)
                labeledField(L10n.tr("Genre"), text: $genre)

                if savedFeedback && !hasChanges {
                    Label(L10n.tr("Metadata saved"), systemImage: "checkmark.circle.fill")
                        .font(.footnote)
                        .foregroundStyle(.green)
                }
            }

            Section(L10n.tr("Quick Search")) {
                quickSearchRow(label: L10n.tr("Artist"), value: artist.trimmingCharacters(in: .whitespacesAndNewlines), field: .artist, icon: "person.fill")
                quickSearchRow(label: L10n.tr("Album"), value: album.trimmingCharacters(in: .whitespacesAndNewlines), field: .album, icon: "square.stack")
                quickSearchRow(label: L10n.tr("Genre"), value: genre.trimmingCharacters(in: .whitespacesAndNewlines), field: .genre, icon: "guitars")
            }

            Section(L10n.tr("File & Playback Info")) {
                infoRow(L10n.tr("Precision"), value: L10n.precisionDisplay(techInfo))
                infoRow(L10n.tr("Frequency"), value: L10n.frequencyDisplay(techInfo))
                infoRow(L10n.tr("Bit Rate"), value: L10n.bitRateDisplay(techInfo))
                infoRow(L10n.tr("File Size"), value: L10n.fileSizeDisplay(techInfo, fallbackBytes: track.fileSize))
                infoRow(L10n.tr("Length"), value: formatTime(techInfo?.duration ?? track.duration))
                infoRow(L10n.tr("Sound Tracks"), value: L10n.channelsDisplay(techInfo))
                infoRow(L10n.tr("Time Added"), value: L10n.formatDateTime(track.dateAdded))
                infoRow(L10n.tr("Time Edited"), value: L10n.formatDateTime(track.modified))
                infoRow(L10n.tr("Last Played"), value: history?.lastPlayed(for: track.id).map { L10n.formatDateTime($0) } ?? L10n.tr("Never"))
                infoRow(L10n.tr("Played Times"), value: "\(history?.count(for: track.id) ?? 0)")
            }
        }
    }

    private func labeledField(_ label: String, text: Binding<String>) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(.secondary)
                .frame(width: 64, alignment: .leading)
            TextField(label, text: text)
                .multilineTextAlignment(.trailing)
                .onChange(of: text.wrappedValue) { _, _ in
                    savedFeedback = false
                }
        }
    }

    private func quickSearchRow(label: String, value: String, field: SearchField, icon: String) -> some View {
        Button {
            dismiss()
            navigation.openSearch(query: value, field: field)
        } label: {
            HStack {
                Label(label, systemImage: icon)
                    .foregroundStyle(.primary)
                Spacer()
                Text(value.isEmpty ? "—" : value)
                    .foregroundStyle(value.isEmpty ? Color.secondary : Color.accentColor)
                    .lineLimit(1)
                if !value.isEmpty {
                    Image(systemName: "magnifyingglass")
                        .font(.footnote)
                        .foregroundStyle(Color.accentColor)
                }
            }
        }
        .disabled(value.isEmpty)
    }

    private func infoRow(_ label: String, value: String) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .monospacedDigit()
                .multilineTextAlignment(.trailing)
        }
    }

    private func saveChanges() {
        guard let library,
              let updated = library.updateTrackMetadata(
            id: trackID,
            title: title,
            artist: artist,
            album: album,
            genre: genre,
            artworkData: pendingArtworkData
        ) else { return }
        title = updated.title
        artist = updated.artist
        album = updated.album
        genre = updated.displayGenre
        pendingArtworkData = nil
        pendingPreviewImage = nil
        savedFeedback = true
    }
}
