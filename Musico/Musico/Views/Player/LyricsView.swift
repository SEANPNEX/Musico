import SwiftUI
import UniformTypeIdentifiers

struct LyricsView: View {
    /// Shown inside another page rather than as a sheet: no Done button.
    var embedded = false
    /// What Done does; closes the sheet when nil.
    var onDone: (() -> Void)?

    @Environment(PlayerController.self) private var player
    @Environment(LibraryStore.self) private var library
    @Environment(\.lyricsStore) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var lyrics: Lyrics?
    @State private var source: LyricsSource?
    @State private var showImporter = false
    @State private var importError: String?
    /// Bumped after an import or removal to reload.
    @State private var revision = 0

    private var loadKey: String { "\(player.currentTrack?.id ?? "")#\(revision)" }

    var body: some View {
        NavigationStack {
            Group {
                if let lyrics {
                    if lyrics.isTimed { timed(lyrics) } else { plain(lyrics) }
                } else {
                    ContentUnavailableView {
                        Label("No Lyrics", systemImage: "quote.bubble")
                    } description: {
                        Text("Import a .lrc or .txt file for this song, or put one with the same name next to it.")
                    } actions: {
                        Button("Import from File…") { showImporter = true }
                            .buttonStyle(.borderedProminent)
                            .disabled(player.currentTrack == nil)
                    }
                }
            }
            .navigationTitle(player.currentTrack?.title ?? "Lyrics")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu("Lyrics Options", systemImage: "ellipsis.circle") {
                        if let source { Text(sourceText(source)) }
                        Button("Import from File…", systemImage: "square.and.arrow.down") { showImporter = true }
                        if let id = player.currentTrack?.id, store.hasImported(trackID: id) {
                            Button("Remove Imported Lyrics", systemImage: "trash", role: .destructive) {
                                store.removeImported(trackID: id)
                                revision += 1
                            }
                        }
                    }
                    .disabled(player.currentTrack == nil)
                }
                if !embedded {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") { if let onDone { onDone() } else { dismiss() } }
                    }
                }
            }
            .task(id: loadKey) { await load() }
            .fileImporter(isPresented: $showImporter,
                          allowedContentTypes: [.plainText, .text, UTType(filenameExtension: "lrc") ?? .data]) { result in
                guard case .success(let url) = result, let id = player.currentTrack?.id else { return }
                do {
                    try store.importLyrics(from: url, trackID: id)
                    revision += 1
                } catch {
                    importError = error.localizedDescription
                }
            }
            .alert("Couldn’t Import Lyrics", isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(importError ?? "")
            }
        }
    }

    private func sourceText(_ source: LyricsSource) -> String {
        switch source {
        case .imported: "From the file you imported"
        case .sidecar: "From a file next to the song"
        case .embedded: "From the song’s tags"
        }
    }

    private func load() async {
        guard let track = player.currentTrack else {
            lyrics = nil
            source = nil
            return
        }
        let embedded = await library.embeddedLyrics(for: track)
        guard track.id == player.currentTrack?.id else { return }
        let found = store.lyrics(trackID: track.id, fileURL: library.url(for: track), embedded: embedded)
        lyrics = found?.0
        source = found?.1
    }

    private func timed(_ lyrics: Lyrics) -> some View {
        let current = lyrics.lineIndex(at: player.currentTime)
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
                                if let time = line.time { player.seek(to: time) }
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
