import SwiftUI
import UniformTypeIdentifiers

struct LyricsView: View {
    /// Shown inside another page rather than as a sheet: no Done button.
    var embedded = false
    /// What Done does; closes the sheet when nil.
    var onDone: (() -> Void)?

    @Environment(PlayerController.self) private var player: PlayerController?
    @Environment(LibraryStore.self) private var library: LibraryStore?
    @Environment(\.lyricsStore) private var store
    @Environment(\.extensionStore) private var extensionStore
    @Environment(\.languageManager) private var languageManager
    @Environment(\.dismiss) private var dismiss

    @State private var lyrics: Lyrics?
    @State private var source: LyricsSource?
    @State private var showImporter = false
    @State private var showCloudSearch = false
    @State private var importError: String?
    @State private var sidecarSavedToast = false
    /// Bumped after an import, cloud confirm, or removal to reload.
    @State private var revision = 0

    private var loadKey: String { "\(player?.currentTrack?.id ?? "")#\(revision)" }

    var body: some View {
        NavigationStack {
            Group {
                if let lyrics {
                    if lyrics.isTimed { timed(lyrics) } else { plain(lyrics) }
                } else {
                    ContentUnavailableView {
                        Label(L10n.tr("No Lyrics"), systemImage: "quote.bubble")
                    } description: {
                        Text(L10n.tr("Import a .lrc or .txt file for this song, or put one with the same name next to it."))
                    } actions: {
                        Button(L10n.tr("Search Online…")) { showCloudSearch = true }
                            .buttonStyle(.borderedProminent)
                            .disabled(player?.currentTrack == nil)
                        Button(L10n.tr("Import from File…")) { showImporter = true }
                            .buttonStyle(.bordered)
                            .disabled(player?.currentTrack == nil)
                    }
                }
            }
            .navigationTitle(player?.currentTrack?.title ?? L10n.tr("Lyrics"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu(L10n.tr("Lyrics Options"), systemImage: "ellipsis.circle") {
                        if let source { Text(sourceText(source)) }
                        Button(L10n.tr("Search Online…"), systemImage: "magnifyingglass") { showCloudSearch = true }
                        Button(L10n.tr("Import from File…"), systemImage: "square.and.arrow.down") { showImporter = true }
                        if let track = player?.currentTrack,
                           let fileURL = library?.url(for: track),
                           store.canExportSidecar(for: fileURL),
                           let rawText = currentExportableLRCText(for: track.id) {
                            Button(L10n.tr("Save .lrc to Song Folder"), systemImage: "folder.badge.plus") {
                                do {
                                    try store.exportSidecarLyrics(text: rawText, for: fileURL)
                                    sidecarSavedToast = true
                                } catch {
                                    importError = error.localizedDescription
                                }
                            }
                        }
                        if let id = player?.currentTrack?.id, store.hasImported(trackID: id) {
                            Button(L10n.tr("Remove Imported Lyrics"), systemImage: "trash", role: .destructive) {
                                store.removeImported(trackID: id)
                                NotificationCenter.default.post(name: .musicoLyricsDidChange, object: id)
                                revision += 1
                            }
                        }
                    }
                    .disabled(player?.currentTrack == nil)
                }
                if !embedded {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(L10n.tr("Done")) { if let onDone { onDone() } else { dismiss() } }
                    }
                }
            }
            .task(id: loadKey) { await load() }
            .onReceive(NotificationCenter.default.publisher(for: .musicoLyricsDidChange)) { note in
                let changedID = note.object as? String
                if changedID == nil || changedID == player?.currentTrack?.id {
                    revision += 1
                }
            }
            .fileImporter(isPresented: $showImporter,
                          allowedContentTypes: [.plainText, .text, UTType(filenameExtension: "lrc") ?? .data]) { result in
                guard case .success(let url) = result, let id = player?.currentTrack?.id else { return }
                do {
                    try store.importLyrics(from: url, trackID: id)
                    NotificationCenter.default.post(name: .musicoLyricsDidChange, object: id)
                    revision += 1
                } catch {
                    importError = error.localizedDescription
                }
            }
            .sheet(isPresented: $showCloudSearch) {
                if let track = player?.currentTrack {
                    let audioURL = library?.url(for: track)
                    let canExport = store.canExportSidecar(for: audioURL)
                    CloudLyricsPickerSheet(track: track, canExportSidecar: canExport) { text, exportSidecar in
                        try store.saveImportedLyrics(text: text, trackID: track.id)
                        if exportSidecar, let audioURL {
                            try store.exportSidecarLyrics(text: text, for: audioURL)
                        }
                        NotificationCenter.default.post(name: .musicoLyricsDidChange, object: track.id)
                        revision += 1
                    }
                    .environment(\.extensionStore, extensionStore)
                    .environment(\.languageManager, languageManager)
                    .environment(\.locale, languageManager.effectiveLocale)
                }
            }
            .alert(L10n.tr("Lyrics Action Failed"), isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
                Button(L10n.tr("OK"), role: .cancel) {}
            } message: {
                Text(importError ?? "")
            }
            .alert(L10n.tr("Saved .lrc to song folder."), isPresented: $sidecarSavedToast) {
                Button(L10n.tr("OK"), role: .cancel) {}
            }
        }
    }

    private func sourceText(_ source: LyricsSource) -> String {
        switch source {
        case .imported: L10n.tr("From the file you imported")
        case .sidecar: L10n.tr("From a file next to the song")
        case .folderMatch: L10n.tr("Matched from song folder")
        case .embedded: L10n.tr("From the song’s tags")
        }
    }

    private func currentExportableLRCText(for trackID: String) -> String? {
        if let raw = store.rawImportedText(trackID: trackID) {
            return raw
        }
        guard let lyrics, !lyrics.lines.isEmpty else { return nil }
        return lyrics.lines.map { line in
            if let time = line.time {
                return "\(CloudLyricsFormatter.formatLRCTimestamp(time))\(line.text)"
            }
            return line.text
        }.joined(separator: "\n")
    }

    private func load() async {
        guard let player, let library, let track = player.currentTrack else {
            lyrics = nil
            source = nil
            return
        }
        let embedded = await library.embeddedLyrics(for: track)
        guard track.id == player.currentTrack?.id else { return }
        let found = store.lyrics(
            trackID: track.id,
            fileURL: library.url(for: track),
            embedded: embedded,
            trackTitle: track.title,
            trackArtist: track.artist
        )
        lyrics = found?.0
        source = found?.1
    }

    private func timed(_ lyrics: Lyrics) -> some View {
        let current = lyrics.lineIndex(at: player?.currentTime ?? 0)
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    ForEach(lyrics.lines) { line in
                        Text(line.text.isEmpty ? "♪" : line.text)
                            .font(.title3.weight(line.id == current ? .bold : .regular))
                            .foregroundStyle(line.id == current ? Color.primary : Color.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                if let time = line.time { player?.seek(to: time) }
                            }
                            .id(line.id)
                    }
                }
                .padding(24)
                .padding(.bottom, 200)
            }
            .onChange(of: current) { _, new in
                guard let new else { return }
                withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(new, anchor: .center) }
            }
            .onAppear {
                if let current { proxy.scrollTo(current, anchor: .center) }
            }
        }
    }

    private func plain(_ lyrics: Lyrics) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(lyrics.lines) { line in
                    Text(line.text).font(.body).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(24)
        }
    }
}
