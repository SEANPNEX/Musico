import SwiftUI

struct CloudLyricsPickerSheet: View {
    let track: Track
    let canExportSidecar: Bool
    let onConfirm: (_ lyricsText: String, _ exportSidecar: Bool) throws -> Void

    @Environment(\.extensionStore) private var extensionStore
    @Environment(\.dismiss) private var dismiss

    @State private var queryTitle: String
    @State private var queryArtist: String
    @State private var selectedSource: CloudLyricsSourceSelection = .auto
    @State private var candidates: [CloudLyricsCandidate] = []
    @State private var partialFailures: [CloudLyricsSourceFailure] = []
    @State private var selectedID: String?
    @State private var includeTranslation = true
    @State private var alsoExportSidecar: Bool
    @State private var isSearching = false
    @State private var searchError: String?
    @State private var saveError: String?
    @State private var searchTrigger = 0
    @State private var previewRawText = ""
    @State private var previewLines: [LyricLine] = []

    init(
        track: Track,
        canExportSidecar: Bool,
        onConfirm: @escaping (_ lyricsText: String, _ exportSidecar: Bool) throws -> Void
    ) {
        self.track = track
        self.canExportSidecar = canExportSidecar
        self.onConfirm = onConfirm
        let cleanedArtist = (track.artist == "Unknown Artist" || track.artist == L10n.tr("Unknown Artist")) ? "" : track.artist
        _queryTitle = State(initialValue: track.title)
        _queryArtist = State(initialValue: cleanedArtist)
        _alsoExportSidecar = State(initialValue: canExportSidecar)
    }

    private var sourceOptions: [CloudLyricsSourceOption] {
        CloudLyricsService.sourceOptions(
            extensions: extensionStore.activeLyricsExtensions,
            autoLabel: L10n.tr("Auto")
        )
    }

    private var selectedCandidate: CloudLyricsCandidate? {
        guard let selectedID else { return candidates.first }
        return candidates.first { $0.id == selectedID } ?? candidates.first
    }

    private var targetDuration: Double? {
        track.duration > 0 ? track.duration : nil
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchHeader
                sourceBar
                Divider()
                contentBody
                if canExportSidecar || selectedCandidate != nil {
                    footerBar
                }
            }
            .navigationTitle(L10n.tr("Search Cloud Lyrics"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.tr("Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.tr("Use Lyrics")) { confirmSelection() }
                        .disabled(selectedCandidate == nil)
                }
            }
            .task(id: searchTrigger) { await performSearch() }
            .onChange(of: selectedID) { _, newID in refreshPreviewCache(forID: newID) }
            .onChange(of: includeTranslation) { _, _ in refreshPreviewCache(forID: selectedID) }
            .alert(L10n.tr("Couldn’t Save Lyrics"), isPresented: Binding(
                get: { saveError != nil },
                set: { if !$0 { saveError = nil } }
            )) {
                Button(L10n.tr("OK"), role: .cancel) {}
            } message: {
                Text(saveError ?? "")
            }
        }
        #if targetEnvironment(macCatalyst)
        .frame(minWidth: 740, idealWidth: 820, minHeight: 560, idealHeight: 620)
        #endif
    }

    private var searchHeader: some View {
        HStack(spacing: 8) {
            TextField(L10n.tr("Title"), text: $queryTitle)
                .textFieldStyle(.roundedBorder)
                .onSubmit { triggerSearch() }
            TextField(L10n.tr("Artist"), text: $queryArtist)
                .textFieldStyle(.roundedBorder)
                .onSubmit { triggerSearch() }
            Button(L10n.tr("Search")) { triggerSearch() }
                .buttonStyle(.borderedProminent)
                .disabled(isSearching || (queryTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && queryArtist.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }

    @ViewBuilder
    private var sourceBar: some View {
        let chips = HStack(spacing: 8) {
            ForEach(sourceOptions) { option in
                let isSelected = option.selection == selectedSource
                Button {
                    if selectedSource != option.selection {
                        selectedSource = option.selection
                        triggerSearch()
                    }
                } label: {
                    Text(option.label)
                        .font(.subheadline.weight(isSelected ? .semibold : .regular))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(isSelected ? Color.accentColor : Color.secondary.opacity(0.14), in: Capsule())
                        .foregroundStyle(isSelected ? Color.white : Color.primary)
                }
                .buttonStyle(.plain)
            }
            if sourceOptions.count <= 4 {
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 10)

        if sourceOptions.count > 4 {
            ScrollView(.horizontal, showsIndicators: false) {
                chips
            }
        } else {
            chips
        }
    }

    @ViewBuilder
    private var contentBody: some View {
        if isSearching && candidates.isEmpty {
            VStack(spacing: 12) {
                ProgressView()
                Text(L10n.tr("Searching…"))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let searchError, candidates.isEmpty {
            ContentUnavailableView {
                Label(L10n.tr("Couldn’t Search Lyrics"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(searchError)
            } actions: {
                Button(L10n.tr("Try Again")) { triggerSearch() }
                    .buttonStyle(.borderedProminent)
            }
        } else if candidates.isEmpty {
            ContentUnavailableView {
                Label(L10n.tr("No Lyrics Found Online"), systemImage: "magnifyingglass")
            } description: {
                Text(L10n.tr("Try editing the title or artist above, or switch lyrics source."))
            }
        } else {
            GeometryReader { proxy in
                let wide = proxy.size.width >= 600
                if wide {
                    let leftWidth = max(240, floor(proxy.size.width * 0.44))
                    let rightWidth = max(200, proxy.size.width - leftWidth - 1)
                    HStack(spacing: 0) {
                        candidateList
                            .frame(width: leftWidth, height: proxy.size.height)
                            .clipped()
                        Divider()
                        previewPane
                            .frame(width: rightWidth, height: proxy.size.height)
                            .clipped()
                    }
                    .frame(width: proxy.size.width, height: proxy.size.height)
                } else {
                    let topHeight = max(180, floor(proxy.size.height * 0.44))
                    let bottomHeight = max(160, proxy.size.height - topHeight - 1)
                    VStack(spacing: 0) {
                        candidateList
                            .frame(width: proxy.size.width, height: topHeight)
                            .clipped()
                        Divider()
                        previewPane
                            .frame(width: proxy.size.width, height: bottomHeight)
                            .clipped()
                    }
                    .frame(width: proxy.size.width, height: proxy.size.height)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var candidateList: some View {
        ScrollView(.vertical) {
            LazyVStack(alignment: .leading, spacing: 0) {
                if !partialFailures.isEmpty {
                    Label(L10n.tr("Some sources could not be reached."), systemImage: "exclamationmark.circle")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                    Divider()
                }
                ForEach(candidates) { candidate in
                    let isSelected = selectedCandidate?.id == candidate.id
                    VStack(alignment: .leading, spacing: 4) {
                        Text(candidate.title.isEmpty ? track.title : candidate.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        if !candidate.artist.isEmpty || (candidate.album?.isEmpty == false) {
                            Text([candidate.artist, candidate.album ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        HStack(spacing: 6) {
                            badge(candidate.sourceName, tint: .accentColor)
                            badge(formatBadgeText(for: candidate), tint: candidate.isTimed ? .green : .secondary)
                            if candidate.hasTranslation {
                                badge(L10n.tr("Translated"), tint: .purple)
                            }
                            if let durText = CloudLyricsFormatter.formatDurationAndDelta(
                                candidateSec: candidate.durationSec,
                                targetSec: targetDuration
                            ) {
                                Spacer(minLength: 4)
                                Text(durText)
                                    .font(.caption2.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(isSelected ? Color.accentColor.opacity(0.14) : Color(.systemBackground))
                    .contentShape(Rectangle())
                    .onTapGesture {
                        selectedID = candidate.id
                    }
                    Divider()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .scrollIndicators(.visible)
        .background(Color(.systemBackground))
        .contentShape(Rectangle())
    }

    @ViewBuilder
    private var previewPane: some View {
        if let candidate = selectedCandidate {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L10n.tr("Preview"))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        Text("\(candidate.sourceName) · \(formatBadgeText(for: candidate))")
                            .font(.subheadline.weight(.medium))
                    }
                    Spacer()
                    if candidate.hasTranslation {
                        Toggle(L10n.tr("Include Translation"), isOn: $includeTranslation)
                            .toggleStyle(.switch)
                            .labelsHidden()
                        Text(L10n.tr("Include Translation"))
                            .font(.caption)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                Divider()

                ScrollView(.vertical) {
                    VStack(alignment: .leading, spacing: 8) {
                        if previewLines.isEmpty {
                            Text(previewRawText)
                                .font(.body)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                        } else {
                            ForEach(previewLines) { line in
                                Text(line.text.isEmpty ? "♪" : line.text)
                                    .font(.body)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .contentShape(Rectangle())
                }
                .id(candidate.id)
                .scrollIndicators(.visible)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(.systemBackground))
                .contentShape(Rectangle())
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ContentUnavailableView {
                Label(L10n.tr("Select a result to preview"), systemImage: "text.quote")
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var footerBar: some View {
        VStack(spacing: 0) {
            Divider()
            HStack(spacing: 12) {
                if canExportSidecar {
                    Toggle(L10n.tr("Also save .lrc to song folder"), isOn: $alsoExportSidecar)
                        .font(.subheadline)
                }
                Spacer()
                if isSearching {
                    ProgressView()
                        .controlSize(.small)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
    }

    private func badge(_ text: String, tint: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(tint.opacity(0.14), in: Capsule())
            .foregroundStyle(tint)
    }

    private func formatBadgeText(for candidate: CloudLyricsCandidate) -> String {
        if candidate.isTimed {
            return L10n.tr("Synced")
        }
        if candidate.isInstrumental {
            return L10n.tr("Instrumental")
        }
        return L10n.tr("Plain")
    }

    private func triggerSearch() {
        searchTrigger += 1
    }

    private func performSearch() async {
        let query = CloudLyricsQuery(
            title: queryTitle,
            artist: queryArtist,
            album: track.album.isEmpty || track.album == "Unknown Album" ? nil : track.album,
            durationSec: targetDuration
        )
        guard !query.isEmpty else {
            candidates = []
            searchError = CloudLyricsError.emptyQuery.localizedDescription
            return
        }

        isSearching = true
        searchError = nil
        partialFailures = []
        let service = extensionStore.makeCloudLyricsService()
        let activeExts = extensionStore.activeLyricsExtensions
        let currentSource = selectedSource

        do {
            let response = try await service.search(
                query: query,
                source: currentSource,
                activeExtensions: activeExts
            )
            guard !Task.isCancelled else { return }
            candidates = response.candidates
            partialFailures = response.failures
            if let currentID = selectedID, candidates.contains(where: { $0.id == currentID }) {
                selectedID = currentID
                refreshPreviewCache(forID: currentID)
            } else {
                let firstID = candidates.first?.id
                selectedID = firstID
                refreshPreviewCache(forID: firstID)
            }
            isSearching = false
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled else { return }
            candidates = []
            selectedID = nil
            refreshPreviewCache(forID: nil)
            searchError = error.localizedDescription
            isSearching = false
        }
    }

    private func refreshPreviewCache(forID targetID: String?) {
        let candidate = targetID.flatMap { id in candidates.first { $0.id == id } } ?? candidates.first
        guard let candidate else {
            previewRawText = ""
            previewLines = []
            return
        }
        let raw = candidate.formattedText(includeTranslation: includeTranslation)
        previewRawText = raw
        previewLines = LRCParser.parse(raw).lines
    }

    private func confirmSelection() {
        guard let candidate = selectedCandidate else { return }
        let finalText = candidate.formattedText(includeTranslation: includeTranslation)
        do {
            try onConfirm(finalText, alsoExportSidecar && canExportSidecar)
            dismiss()
        } catch {
            saveError = error.localizedDescription
        }
    }
}
