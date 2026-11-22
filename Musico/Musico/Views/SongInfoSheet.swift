import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

struct SongInfoTarget: Identifiable, Hashable {
    let id: String
}

@MainActor @Observable
final class SongInfoPrompt {
    var target: SongInfoTarget?

    func show(_ trackID: String) {
        target = SongInfoTarget(id: trackID)
    }
}

/// Hosts the Song Info sheet for context/ellipsis menus inside it without reading stores at sheet root.
struct SongInfoHostModifier: ViewModifier {
    @State private var prompt = SongInfoPrompt()

    func body(content: Content) -> some View {
        content
            .environment(prompt)
            .sheet(item: $prompt.target) { target in
                SongInfoSheet(trackID: target.id)
            }
    }
}

struct SongInfoSheet: View {
    let trackID: String

    @Environment(LibraryStore.self) private var library
    @Environment(PlayHistoryStore.self) private var history
    @Environment(\.appNavigation) private var navigation
    @Environment(\.dismiss) private var dismiss

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

    private var track: Track? { library.track(trackID) }

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
                if let track {
                    formContent(for: track)
                } else {
                    ContentUnavailableView("Song Unavailable", systemImage: "music.note",
                                           description: Text("This song is no longer in your library."))
                }
            }
            .navigationTitle("Song Info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { saveChanges() }
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
            if let track {
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
    private func formContent(for track: Track) -> some View {
        Form {
            Section("Cover & Metadata") {
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
                        PhotosPicker(selection: $selectedPhotoItem, matching: .images) {
                            Label("Choose from Photos…", systemImage: "photo")
                        }
                        .buttonStyle(.borderless)

                        Button {
                            showImageFileImporter = true
                        } label: {
                            Label("Choose Image File…", systemImage: "folder")
                        }
                        .buttonStyle(.borderless)
                    }
                }
                .padding(.vertical, 4)

                labeledField("Title", text: $title)
                labeledField("Artist", text: $artist)
                labeledField("Album", text: $album)
                labeledField("Genre", text: $genre)

                if savedFeedback && !hasChanges {
                    Label("Metadata saved", systemImage: "checkmark.circle.fill")
                        .font(.footnote)
                        .foregroundStyle(.green)
                }
            }

            Section("Quick Search") {
                quickSearchRow(label: "Artist", value: artist.trimmingCharacters(in: .whitespacesAndNewlines), field: .artist, icon: "person.fill")
                quickSearchRow(label: "Album", value: album.trimmingCharacters(in: .whitespacesAndNewlines), field: .album, icon: "square.stack")
                quickSearchRow(label: "Genre", value: genre.trimmingCharacters(in: .whitespacesAndNewlines), field: .genre, icon: "guitars")
            }

            Section("File & Playback Info") {
                infoRow("Precision", value: techInfo?.precisionDisplay ?? "Loading…")
                infoRow("Frequency", value: techInfo?.frequencyDisplay ?? "Loading…")
                infoRow("Bit Rate", value: techInfo?.bitRateDisplay ?? "Loading…")
                infoRow("File Size", value: techInfo?.fileSizeDisplay ?? ByteCountFormatter.string(fromByteCount: track.fileSize, countStyle: .file))
                infoRow("Length", value: formatTime(techInfo?.duration ?? track.duration))
                infoRow("Sound Tracks", value: techInfo?.channelsDisplay ?? "Loading…")
                infoRow("Time Added", value: track.dateAdded.formatted(date: .abbreviated, time: .shortened))
                infoRow("Time Edited", value: track.modified.formatted(date: .abbreviated, time: .shortened))
                infoRow("Last Played", value: history.lastPlayed(for: track.id)?.formatted(date: .abbreviated, time: .shortened) ?? "Never")
                infoRow("Played Times", value: "\(history.count(for: track.id))")
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
        guard let updated = library.updateTrackMetadata(
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
