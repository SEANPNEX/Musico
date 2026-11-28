import SwiftUI

struct BatchTagEditTarget: Identifiable {
    let id = UUID()
    let trackIDs: [String]
}

struct BatchTagEditSheet: View {
    let trackIDs: [String]
    private let explicitLibrary: LibraryStore?
    var onApplied: (() -> Void)? = nil

    @Environment(LibraryStore.self) private var envLibrary: LibraryStore?
    @Environment(\.themeManager) private var themeManager
    @Environment(\.toastManager) private var toastManager
    @Environment(\.dismiss) private var dismiss

    @State private var artistInput = ""
    @State private var albumInput = ""
    @State private var didInitialize = false

    init(
        trackIDs: [String],
        library: LibraryStore? = nil,
        onApplied: (() -> Void)? = nil
    ) {
        self.trackIDs = trackIDs
        self.explicitLibrary = library
        self.onApplied = onApplied
    }

    private var library: LibraryStore? { explicitLibrary ?? envLibrary }

    private var targetTracks: [Track] {
        guard let library else { return [] }
        var seen = Set<String>()
        var result: [Track] = []
        for id in trackIDs where !id.hasPrefix(Track.ephemeralIDPrefix) {
            guard seen.insert(id).inserted,
                  let track = library.track(id),
                  !track.isEphemeral else {
                continue
            }
            result.append(track)
        }
        return result
    }

    private var uniformArtist: String? {
        let artists = Set(targetTracks.map(\.artist))
        return artists.count == 1 ? artists.first : nil
    }

    private var uniformAlbum: String? {
        let albums = Set(targetTracks.map(\.album))
        return albums.count == 1 ? albums.first : nil
    }

    private var effectiveArtist: String? {
        let trimmed = artistInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let uniformArtist, trimmed == uniformArtist {
            return nil
        }
        return trimmed
    }

    private var effectiveAlbum: String? {
        let trimmed = albumInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let uniformAlbum, trimmed == uniformAlbum {
            return nil
        }
        return trimmed
    }

    private var canApply: Bool {
        !targetTracks.isEmpty && (effectiveArtist != nil || effectiveAlbum != nil)
    }

    var body: some View {
        NavigationStack {
            Group {
                if let library {
                    Form {
                        Section {
                            HStack(spacing: 12) {
                                Image(systemName: "tag.fill")
                                    .font(.title3)
                                    .foregroundStyle(themeManager.accentColor)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(L10n.tr("Editing %@ songs", String(targetTracks.count)))
                                        .font(.subheadline.weight(.semibold))
                                    Text(L10n.tr("Title, genre, and cover art are preserved."))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .padding(.vertical, 2)
                        }

                        Section {
                            HStack {
                                Text(L10n.tr("Artist"))
                                    .foregroundStyle(.secondary)
                                    .frame(width: 68, alignment: .leading)
                                TextField(
                                    uniformArtist == nil ? L10n.tr("Mixed") : L10n.tr("Artist"),
                                    text: $artistInput
                                )
                                .multilineTextAlignment(.trailing)
                            }

                            HStack {
                                Text(L10n.tr("Album"))
                                    .foregroundStyle(.secondary)
                                    .frame(width: 68, alignment: .leading)
                                TextField(
                                    uniformAlbum == nil ? L10n.tr("Mixed") : L10n.tr("Album"),
                                    text: $albumInput
                                )
                                .multilineTextAlignment(.trailing)
                            }
                        } header: {
                            Text(L10n.tr("Batch Tag Editing"))
                        } footer: {
                            Text(L10n.tr("Leave a field blank to keep each song’s existing value."))
                        }
                    }
                    .hiddenScrollBackgroundIfThemed(themeManager)
                    .background { ThemeBackdropView() }
                    .onAppear {
                        initializeFieldsIfNeeded()
                    }
                    .onChange(of: trackIDs) { _, _ in
                        didInitialize = false
                        initializeFieldsIfNeeded()
                    }
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(L10n.tr("Cancel")) { dismiss() }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button(L10n.tr("Apply")) {
                                let count = library.batchUpdateTrackMetadata(
                                    ids: targetTracks.map(\.id),
                                    artist: effectiveArtist,
                                    album: effectiveAlbum
                                )
                                if count > 0 {
                                    toastManager.show(
                                        L10n.tr("Updated tags for %@", L10n.songCount(count)),
                                        systemImage: "checkmark.circle.fill"
                                    )
                                }
                                onApplied?()
                                dismiss()
                            }
                            .disabled(!canApply)
                        }
                    }
                } else {
                    ContentUnavailableView(
                        L10n.tr("Song Unavailable"),
                        systemImage: "music.note"
                    )
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(L10n.tr("Close")) { dismiss() }
                        }
                    }
                }
            }
            .navigationTitle(L10n.tr("Edit Tags"))
            .navigationBarTitleDisplayMode(.inline)
        }
        #if targetEnvironment(macCatalyst)
        .frame(minWidth: 420, idealWidth: 460, minHeight: 280, idealHeight: 320)
        #endif
    }

    private func initializeFieldsIfNeeded() {
        guard !didInitialize else { return }
        artistInput = uniformArtist ?? ""
        albumInput = uniformAlbum ?? ""
        didInitialize = true
    }
}

struct BatchSelectionBottomBar: View {
    let selectedCount: Int
    let totalCount: Int
    let onToggleSelectAll: () -> Void
    let onEditTags: () -> Void
    let onDone: () -> Void

    @Environment(\.themeManager) private var themeManager

    private var allSelected: Bool {
        totalCount > 0 && selectedCount >= totalCount
    }

    var body: some View {
        VStack(spacing: 0) {
            Divider()
            HStack(spacing: 10) {
                Text(L10n.tr("%@ selected", String(selectedCount)))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                Spacer(minLength: 8)

                Button(allSelected ? L10n.tr("Deselect All") : L10n.tr("Select All")) {
                    onToggleSelectAll()
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                .disabled(totalCount == 0)

                Button(L10n.tr("Edit Tags…")) {
                    onEditTags()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .tint(themeManager.accentColor)
                .disabled(selectedCount == 0)

                Button(L10n.tr("Done")) {
                    onDone()
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.bar)
        }
    }
}
