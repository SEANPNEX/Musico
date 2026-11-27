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
    @Environment(\.themeManager) private var themeManager
    @Environment(\.toastManager) private var toastManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dismiss) private var dismiss

    @State private var lyrics: Lyrics?
    @State private var source: LyricsSource?
    @State private var showImporter = false
    @State private var showCloudSearch = false
    @State private var importError: String?
    /// Bumped after an import, cloud confirm, or removal to reload.
    @State private var revision = 0
    @State private var isManualScrolling = false
    @State private var scrollGraceTask: Task<Void, Never>?

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
                        Button {
                            showCloudSearch = true
                        } label: {
                            Text(L10n.tr("Search Online…"))
                                .lineLimit(1)
                                .fixedSize(horizontal: true, vertical: false)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(player?.currentTrack == nil)
                        Button {
                            showImporter = true
                        } label: {
                            Text(L10n.tr("Import from File…"))
                                .lineLimit(1)
                                .fixedSize(horizontal: true, vertical: false)
                        }
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
                                    toastManager.show(L10n.tr("Saved .lrc to song folder."), systemImage: "checkmark.circle.fill")
                                } catch {
                                    importError = error.localizedDescription
                                }
                            }
                        }
                        Divider()
                        Picker(L10n.tr("Lyrics Size"), selection: Binding(
                            get: { themeManager.settings.lyricsFontSize },
                            set: { themeManager.setLyricsFontSize($0) }
                        )) {
                            ForEach(LyricsFontSize.allCases) { size in
                                Text(size.displayName).tag(size)
                            }
                        }
                        Picker(L10n.tr("Lyrics Alignment"), selection: Binding(
                            get: { themeManager.settings.lyricsAlignment },
                            set: { themeManager.setLyricsAlignment($0) }
                        )) {
                            ForEach(LyricsAlignment.allCases) { alignment in
                                Text(alignment.displayName).tag(alignment)
                            }
                        }
                        if let id = player?.currentTrack?.id, store.hasImported(trackID: id) {
                            Divider()
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
                    .applyTheme(themeManager)
                }
            }
            .alert(L10n.tr("Lyrics Action Failed"), isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
                Button(L10n.tr("OK"), role: .cancel) {}
            } message: {
                Text(importError ?? "")
            }
        }
        .overlay(alignment: .bottom) {
            if !embedded && onDone == nil {
                ToastOverlayView()
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

    private var timedLyricBaseFontSize: CGFloat {
        switch themeManager.settings.lyricsFontSize {
        case .compact: return 17
        case .medium: return 20
        case .large: return 24
        }
    }

    private var plainLyricBaseFontSize: CGFloat {
        switch themeManager.settings.lyricsFontSize {
        case .compact: return 15
        case .medium: return 17
        case .large: return 20
        }
    }

    private var lyricHorizontalAlignment: HorizontalAlignment {
        themeManager.settings.lyricsAlignment == .center ? .center : .leading
    }

    private var lyricFrameAlignment: Alignment {
        themeManager.settings.lyricsAlignment == .center ? .center : .leading
    }

    private var lyricTextAlignment: TextAlignment {
        themeManager.settings.lyricsAlignment == .center ? .center : .leading
    }

    private var lyricScaleAnchor: UnitPoint {
        themeManager.settings.lyricsAlignment == .center ? .center : .leading
    }

    private func beginManualScrollGrace(proxy: ScrollViewProxy, currentProvider: @escaping () -> Int?) {
        isManualScrolling = true
        scrollGraceTask?.cancel()
        scrollGraceTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(3.5))
            guard !Task.isCancelled else { return }
            isManualScrolling = false
            if let target = currentProvider() {
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.35)) {
                    proxy.scrollTo(target, anchor: .center)
                }
            }
        }
    }

    private func timed(_ lyrics: Lyrics) -> some View {
        let current = lyrics.lineIndex(at: player?.currentTime ?? 0)
        return ScrollViewReader { proxy in
            ZStack(alignment: .bottom) {
                let scroll = ScrollView {
                    LazyVStack(alignment: lyricHorizontalAlignment, spacing: 18) {
                        ForEach(Array(lyrics.lines.enumerated()), id: \.element.id) { index, line in
                            let isCurrent = line.id == current
                            let distance = current.map { abs(index - $0) } ?? 1
                            let lineOpacity: Double = {
                                if isCurrent { return 1.0 }
                                if distance <= 1 { return 0.56 }
                                if distance <= 3 { return 0.36 }
                                return 0.22
                            }()
                            let lineScale: CGFloat = reduceMotion ? 1.0 : (isCurrent ? 1.04 : (distance <= 1 ? 0.96 : 0.92))

                            Text(line.text.isEmpty ? "♪" : line.text)
                                .font(.system(size: timedLyricBaseFontSize, weight: isCurrent ? .bold : .medium))
                                .foregroundStyle(isCurrent ? Color.primary : Color.secondary)
                                .opacity(lineOpacity)
                                .scaleEffect(lineScale, anchor: lyricScaleAnchor)
                                .multilineTextAlignment(lyricTextAlignment)
                                .frame(maxWidth: .infinity, alignment: lyricFrameAlignment)
                                .contentShape(Rectangle())
                                .animation(reduceMotion ? nil : .easeOut(duration: 0.25), value: current)
                                .onTapGesture {
                                    scrollGraceTask?.cancel()
                                    isManualScrolling = false
                                    if let time = line.time { player?.seek(to: time) }
                                }
                                .id(line.id)
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 36)
                    .padding(.bottom, 200)
                }
                .mask(
                    LinearGradient(
                        stops: [
                            .init(color: .clear, location: 0.0),
                            .init(color: .black, location: 0.08),
                            .init(color: .black, location: 0.90),
                            .init(color: .clear, location: 1.0)
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .simultaneousGesture(
                    DragGesture(minimumDistance: 6).onChanged { _ in
                        beginManualScrollGrace(proxy: proxy) {
                            lyrics.lineIndex(at: player?.currentTime ?? 0)
                        }
                    }
                )

                Group {
                    if #available(iOS 18.0, macCatalyst 18.0, *) {
                        scroll.onScrollPhaseChange { _, newPhase in
                            if newPhase == .interacting || newPhase == .decelerating {
                                beginManualScrollGrace(proxy: proxy) {
                                    lyrics.lineIndex(at: player?.currentTime ?? 0)
                                }
                            }
                        }
                    } else {
                        scroll
                    }
                }
                .onChange(of: current) { _, new in
                    guard let new, !isManualScrolling else { return }
                    withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.35)) {
                        proxy.scrollTo(new, anchor: .center)
                    }
                }
                .onAppear {
                    isManualScrolling = false
                    scrollGraceTask?.cancel()
                    if let current { proxy.scrollTo(current, anchor: .center) }
                }
                .onDisappear {
                    scrollGraceTask?.cancel()
                }

                if isManualScrolling, let current {
                    Button {
                        scrollGraceTask?.cancel()
                        isManualScrolling = false
                        withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.85)) {
                            proxy.scrollTo(current, anchor: .center)
                        }
                    } label: {
                        Label(L10n.tr("Sync to current line"), systemImage: "arrow.down.to.line")
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(.regularMaterial, in: Capsule())
                            .overlay {
                                Capsule()
                                    .strokeBorder(themeManager.accentColor.opacity(0.35), lineWidth: 0.75)
                            }
                            .shadow(color: .black.opacity(0.16), radius: 8, y: 3)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(themeManager.accentColor)
                    .padding(.bottom, 20)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
                }
            }
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: isManualScrolling)
        }
    }

    private func plain(_ lyrics: Lyrics) -> some View {
        ScrollView {
            VStack(alignment: lyricHorizontalAlignment, spacing: 10) {
                ForEach(lyrics.lines) { line in
                    Text(line.text)
                        .font(.system(size: plainLyricBaseFontSize))
                        .multilineTextAlignment(lyricTextAlignment)
                        .frame(maxWidth: .infinity, alignment: lyricFrameAlignment)
                }
            }
            .padding(24)
        }
    }
}
