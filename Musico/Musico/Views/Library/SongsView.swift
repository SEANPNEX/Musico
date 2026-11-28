import SwiftUI

enum SongSort: String, CaseIterable, Identifiable {
    case title = "Title", artist = "Artist", album = "Album", dateAdded = "Recently Added"
    var id: String { rawValue }

    @MainActor
    var localizedTitle: String { L10n.tr(rawValue) }
}

struct SongsView: View {
    private struct PreviewTarget: Identifiable {
        let extensionDescriptor: CloudSearchExtensionDescriptor
        let item: CloudSearchResult
        var id: String { item.id }
    }

    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player
    @Environment(QueueStore.self) private var queues: QueueStore?
    @Environment(CloudExportPrompt.self) private var cloudExport: CloudExportPrompt?
    @Environment(\.appNavigation) private var navigation
    @Environment(\.extensionStore) private var extensionStore
    @Environment(\.downloadManager) private var downloadManager
    @Environment(\.languageManager) private var languageManager
    @Environment(\.themeManager) private var themeManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @AppStorage("songSort") private var sort: SongSort = .title

    @State private var previewTarget: PreviewTarget?
    @State private var showExtensionManager = false
    @State private var extensionManagerTargetID: String?
    @State private var addFolderError: String?
    @State private var isSelecting = false
    @State private var selectedTrackIDs: Set<String> = []
    @State private var batchEditTarget: BatchTagEditTarget?
    #if !targetEnvironment(macCatalyst)
    @FocusState private var isSearchFieldFocused: Bool
    #endif

    private var activeSearchExtensions: [CloudSearchExtensionDescriptor] {
        extensionStore.activeSearchExtensions
    }

    private var selectedCloudExtension: CloudSearchExtensionDescriptor? {
        guard case .cloud(let extID) = navigation.searchSource else { return nil }
        return activeSearchExtensions.first { $0.id == extID }
    }

    private var isCloudMode: Bool {
        selectedCloudExtension != nil
    }

    private var trimmedQuery: String {
        navigation.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var isSearchingLocal: Bool {
        !isCloudMode && !trimmedQuery.isEmpty
    }

    private var songs: [Track] {
        if isSearchingLocal {
            return library.search(trimmedQuery, field: navigation.searchField)
        }
        func ascending(_ a: String, _ b: String) -> Bool { a.localizedStandardCompare(b) == .orderedAscending }
        switch sort {
        case .title: return library.tracks
        case .artist: return library.tracks.sorted { $0.artist == $1.artist ? ascending($0.title, $1.title) : ascending($0.artist, $1.artist) }
        case .album: return library.tracks.sorted { $0.album == $1.album ? ascending($0.title, $1.title) : ascending($0.album, $1.album) }
        case .dateAdded: return library.tracks.sorted { $0.dateAdded > $1.dateAdded }
        }
    }

    private var searchPrompt: String {
        if let ext = selectedCloudExtension {
            return L10n.tr("Search %@…", ext.name)
        }
        return L10n.tr("Songs, artists, albums, genres")
    }

    var body: some View {
        @Bindable var navigation = navigation
        let cloudSearch = navigation.cloudSearch
        let searchExtensions = activeSearchExtensions
        let selectedExt = selectedCloudExtension
        let searchTextBinding = Binding<String>(
            get: { navigation.searchQuery },
            set: { navigation.updateSearchQueryFromUser($0) }
        )

        VStack(spacing: 0) {
            #if !targetEnvironment(macCatalyst)
            iosSearchHeader(
                extensions: searchExtensions,
                selectedExtension: selectedExt,
                cloudSearch: cloudSearch,
                searchText: searchTextBinding
            )
            Divider()
            #else
            if !searchExtensions.isEmpty {
                searchControlsHeader(extensions: searchExtensions, selectedExtension: selectedExt, cloudSearch: cloudSearch)
                Divider()
            }
            #endif

            List {
                if let selectedExt {
                    cloudSearchListBody(ext: selectedExt, cloudSearch: cloudSearch)
                } else {
                    localListBody
                }
            }
            .listStyle(.plain)
            .hiddenScrollBackgroundIfThemed(themeManager)
            #if !targetEnvironment(macCatalyst)
            .scrollDismissesKeyboard(.interactively)
            #endif
            .refreshable {
                if let selectedExt {
                    if !trimmedQuery.isEmpty {
                        cloudSearch.submitSearch(query: trimmedQuery, descriptor: selectedExt, extensionStore: extensionStore)
                        await cloudSearch.waitForCurrentTask()
                    }
                } else {
                    await library.rescan()
                }
            }
        }
        .navigationTitle(L10n.tr("Songs"))
        .navigationBarTitleDisplayMode(.inline)
        #if targetEnvironment(macCatalyst)
        .searchable(text: searchTextBinding, isPresented: $navigation.isSearchPresented, prompt: searchPrompt)
        .onSubmit(of: .search) {
            if let selectedExt {
                cloudSearch.submitSearch(query: trimmedQuery, descriptor: selectedExt, extensionStore: extensionStore)
            }
        }
        #else
        .onChange(of: navigation.isSearchPresented) { _, presented in
            if isSearchFieldFocused != presented {
                isSearchFieldFocused = presented
            }
        }
        .onChange(of: isSearchFieldFocused) { _, focused in
            if navigation.isSearchPresented != focused {
                navigation.isSearchPresented = focused
            }
        }
        #endif
        .animation(MusicoMotion.viewSwitch(reduceMotion: reduceMotion), value: sort)
        .animation(MusicoMotion.viewSwitch(reduceMotion: reduceMotion), value: trimmedQuery)
        .animation(MusicoMotion.viewSwitch(reduceMotion: reduceMotion), value: navigation.searchSource)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if !isCloudMode && !songs.isEmpty {
                    let selectableIDs = songs.filter { !$0.isEphemeral }.map(\.id)
                    let allSelected = !selectableIDs.isEmpty && selectableIDs.allSatisfy { selectedTrackIDs.contains($0) }
                    if isSelecting {
                        Button(allSelected ? L10n.tr("Deselect All") : L10n.tr("Select All")) {
                            if allSelected {
                                selectedTrackIDs.removeAll()
                            } else {
                                selectedTrackIDs = Set(selectableIDs)
                            }
                        }
                        Button(L10n.tr("Done")) {
                            isSelecting = false
                            selectedTrackIDs.removeAll()
                        }
                        .fontWeight(.semibold)
                    } else {
                        Button(L10n.tr("Select")) {
                            selectedTrackIDs.removeAll()
                            isSelecting = true
                        }
                        Menu(L10n.tr("Sort"), systemImage: "arrow.up.arrow.down") {
                            Picker(L10n.tr("Sort By"), selection: $sort) {
                                ForEach(SongSort.allCases) { Text($0.localizedTitle).tag($0) }
                            }
                        }
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if isSelecting && !isCloudMode {
                let selectableIDs = songs.filter { !$0.isEphemeral }.map(\.id)
                BatchSelectionBottomBar(
                    selectedCount: selectedTrackIDs.count,
                    totalCount: selectableIDs.count,
                    onToggleSelectAll: {
                        let allSelected = !selectableIDs.isEmpty && selectableIDs.allSatisfy { selectedTrackIDs.contains($0) }
                        if allSelected {
                            selectedTrackIDs.removeAll()
                        } else {
                            selectedTrackIDs = Set(selectableIDs)
                        }
                    },
                    onEditTags: {
                        let ordered = selectableIDs.filter { selectedTrackIDs.contains($0) }
                        if !ordered.isEmpty {
                            batchEditTarget = BatchTagEditTarget(trackIDs: ordered)
                        }
                    },
                    onDone: {
                        isSelecting = false
                        selectedTrackIDs.removeAll()
                    }
                )
            }
        }
        .onChange(of: isCloudMode) { _, cloud in
            if cloud {
                isSelecting = false
                selectedTrackIDs.removeAll()
            }
        }
        .sheet(item: $batchEditTarget) { target in
            BatchTagEditSheet(trackIDs: target.trackIDs, library: library) {
                isSelecting = false
                selectedTrackIDs.removeAll()
            }
            .environment(library)
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
        }
        .onAppear {
            cloudSearch.reconcileActiveExtensions(searchExtensions, navigation: navigation)
            #if !targetEnvironment(macCatalyst)
            if navigation.isSearchPresented {
                isSearchFieldFocused = true
            }
            #endif
        }
        .onChange(of: searchExtensions) { _, updated in
            cloudSearch.reconcileActiveExtensions(updated, navigation: navigation)
        }
        .sheet(item: $previewTarget) { target in
            CloudCollectionPreviewSheet(
                extensionDescriptor: target.extensionDescriptor,
                item: target.item,
                library: library,
                player: player,
                extensionStore: extensionStore,
                downloadManager: downloadManager
            )
            .environment(library)
            .environment(player)
            .environment(\.extensionStore, extensionStore)
            .environment(\.downloadManager, downloadManager)
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
        }
        .sheet(isPresented: $showExtensionManager) {
            NavigationStack {
                if let extID = extensionManagerTargetID, extensionStore.extension(forID: extID) != nil {
                    ExtensionDetailView(extensionID: extID, extensionStore: extensionStore)
                        .toolbar {
                            ToolbarItem(placement: .cancellationAction) {
                                Button(L10n.tr("Done")) { showExtensionManager = false }
                            }
                        }
                } else {
                    ExtensionsView(extensionStore: extensionStore, showDoneButton: true)
                }
            }
            .environment(\.extensionStore, extensionStore)
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
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

    // MARK: - Search Source Switcher & Kind Pills

    #if !targetEnvironment(macCatalyst)
    @ViewBuilder
    private func iosSearchHeader(
        extensions: [CloudSearchExtensionDescriptor],
        selectedExtension: CloudSearchExtensionDescriptor?,
        cloudSearch: CloudSearchController,
        searchText: Binding<String>
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextField(searchPrompt, text: searchText)
                        .focused($isSearchFieldFocused)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled(true)
                        .submitLabel(.search)
                        .accessibilityIdentifier("ios-songs-search-field")
                        .onSubmit {
                            if let selectedExtension {
                                cloudSearch.submitSearch(
                                    query: trimmedQuery,
                                    descriptor: selectedExtension,
                                    extensionStore: extensionStore
                                )
                            }
                        }

                    if !navigation.searchQuery.isEmpty {
                        Button {
                            navigation.updateSearchQueryFromUser("")
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(L10n.tr("Clear Filter"))
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color(.secondarySystemFill), in: Capsule())

                if isSearchFieldFocused {
                    Button(L10n.tr("Cancel")) {
                        navigation.updateSearchQueryFromUser("")
                        isSearchFieldFocused = false
                        navigation.isSearchPresented = false
                    }
                    .foregroundStyle(themeManager.accentColor)
                    .transition(reduceMotion ? .opacity : .move(edge: .trailing).combined(with: .opacity))
                }
            }

            if !extensions.isEmpty {
                sourceChipsRow(
                    extensions: extensions,
                    selectedExtension: selectedExtension,
                    cloudSearch: cloudSearch
                )
            }

            if let selectedExtension {
                kindFilterRow(
                    selectedExtension: selectedExtension,
                    cloudSearch: cloudSearch
                )
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.bar)
        .animation(MusicoMotion.viewSwitch(reduceMotion: reduceMotion), value: isSearchFieldFocused)
        .animation(MusicoMotion.viewSwitch(reduceMotion: reduceMotion), value: navigation.searchSource)
    }
    #endif

    #if targetEnvironment(macCatalyst)
    @ViewBuilder
    private func searchControlsHeader(
        extensions: [CloudSearchExtensionDescriptor],
        selectedExtension: CloudSearchExtensionDescriptor?,
        cloudSearch: CloudSearchController
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            sourceChipsRow(
                extensions: extensions,
                selectedExtension: selectedExtension,
                cloudSearch: cloudSearch
            )

            if let selectedExtension {
                kindFilterRow(
                    selectedExtension: selectedExtension,
                    cloudSearch: cloudSearch
                )
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.bar)
    }
    #endif

    @ViewBuilder
    private func sourceChipsRow(
        extensions: [CloudSearchExtensionDescriptor],
        selectedExtension: CloudSearchExtensionDescriptor?,
        cloudSearch: CloudSearchController
    ) -> some View {
        HStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    sourceChip(
                        title: L10n.tr("Local"),
                        isSelected: navigation.searchSource == .local
                    ) {
                        cloudSearch.switchSource(
                            to: .local,
                            currentQuery: trimmedQuery,
                            activeExtensions: extensions,
                            navigation: navigation,
                            extensionStore: extensionStore
                        )
                    }

                    ForEach(extensions) { ext in
                        let source = SongsSearchSource.cloud(extensionID: ext.id)
                        sourceChip(
                            title: ext.badgeLabel,
                            isSelected: navigation.searchSource == source
                        ) {
                            cloudSearch.switchSource(
                                to: source,
                                currentQuery: trimmedQuery,
                                activeExtensions: extensions,
                                navigation: navigation,
                                extensionStore: extensionStore
                            )
                        }
                    }
                }
                .padding(.vertical, 2)
            }

            if let selectedExtension {
                Button {
                    cloudSearch.submitSearch(
                        query: trimmedQuery,
                        descriptor: selectedExtension,
                        extensionStore: extensionStore
                    )
                } label: {
                    if cloudSearch.isLoading {
                        ProgressView()
                            .controlSize(.small)
                            .padding(.horizontal, 8)
                    } else {
                        Text(L10n.tr("Search"))
                            .font(.subheadline.weight(.semibold))
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                .fixedSize(horizontal: true, vertical: false)
                .layoutPriority(1)
                .disabled(trimmedQuery.isEmpty || cloudSearch.isLoading)
            }
        }
    }

    @ViewBuilder
    private func kindFilterRow(
        selectedExtension: CloudSearchExtensionDescriptor,
        cloudSearch: CloudSearchController
    ) -> some View {
        let kindOptions = cloudSearch.kindOptions(for: selectedExtension)
        if !kindOptions.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(kindOptions) { kind in
                        let isSelected = cloudSearch.selectedKind == kind
                        Button {
                            cloudSearch.selectKind(
                                kind,
                                currentQuery: trimmedQuery,
                                descriptor: selectedExtension,
                                extensionStore: extensionStore
                            )
                        } label: {
                            Text(kind.localizedTitle)
                                .font(.caption.weight(isSelected ? .semibold : .medium))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(
                                    isSelected ? themeManager.accentColor.opacity(0.18) : Color(.secondarySystemFill),
                                    in: Capsule()
                                )
                                .foregroundStyle(isSelected ? themeManager.accentColor : Color.primary)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private func sourceChip(title: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(isSelected ? .semibold : .regular))
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(
                    isSelected ? themeManager.accentColor : Color(.secondarySystemFill),
                    in: Capsule()
                )
                .foregroundStyle(isSelected ? Color.white : Color.primary)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Local Library Body

    @ViewBuilder
    private var localListBody: some View {
        let songs = self.songs
        if isSearchingLocal && navigation.searchField != .keyword {
            HStack(spacing: 8) {
                Image(systemName: "line.3.horizontal.decrease.circle.fill")
                    .foregroundStyle(themeManager.accentColor)
                Text("\(navigation.searchField.localizedTitle): \(trimmedQuery)")
                    .font(.subheadline.weight(.medium))
                Spacer()
                Button(L10n.tr("Clear Filter")) {
                    navigation.clearSearchFieldTag()
                }
                .font(.caption)
                .buttonStyle(.borderless)
            }
            .listRowSeparator(.hidden)
        }
        if library.tracks.isEmpty {
            ContentUnavailableView(
                L10n.tr("No Songs Yet"),
                systemImage: "music.note",
                description: Text(L10n.tr("Add a folder in the Folders tab to build your library."))
            )
        } else if songs.isEmpty {
            ContentUnavailableView.search(text: trimmedQuery)
        } else {
            if !isSearchingLocal {
                PlayShuffleButtons(ids: songs.map(\.id), queueName: "Songs")
            }
            TrackRows(
                tracks: songs,
                queueName: isSearchingLocal ? nil : "Songs",
                subtitle: { sort == .album || isSearchingLocal ? "\(L10n.displayArtist($0.artist)) — \(L10n.displayAlbum($0.album))" : L10n.displayArtist($0.artist) },
                onTap: isSearchingLocal ? { index, _ in
                    let queueName = SearchField.queueName(field: navigation.searchField, query: trimmedQuery)
                    player.playInNewQueue(songs.map(\.id), startAt: index, name: queueName)
                } : nil,
                isSelecting: isSelecting,
                selectedIDs: $selectedTrackIDs
            )
        }
    }

    // MARK: - Cloud Search Body

    @ViewBuilder
    private func cloudSearchListBody(
        ext: CloudSearchExtensionDescriptor,
        cloudSearch: CloudSearchController
    ) -> some View {
        if cloudSearch.isLoading && cloudSearch.results.isEmpty {
            SkeletonTrackListPlaceholder(count: 6)
        } else if let error = cloudSearch.error, cloudSearch.results.isEmpty {
            cloudErrorView(error: error, ext: ext, cloudSearch: cloudSearch)
                .listRowSeparator(.hidden)
        } else if !cloudSearch.hasSubmitted && cloudSearch.results.isEmpty {
            ContentUnavailableView(
                L10n.tr("Search %@", ext.name),
                systemImage: "magnifyingglass",
                description: Text(L10n.tr("Enter a query and press Search to search %@.", ext.name))
            )
            .listRowSeparator(.hidden)
        } else if cloudSearch.results.isEmpty {
            ContentUnavailableView(
                L10n.tr("No Results Found"),
                systemImage: "magnifyingglass",
                description: Text(L10n.tr("Try another keyword or switch search filter."))
            )
            .listRowSeparator(.hidden)
        } else {
            let loadedTrackResults = cloudSearch.trackResults
            ForEach(cloudSearch.results) { item in
                switch item.kind {
                case .track:
                    cloudTrackRow(item: item, ext: ext, allTrackResults: loadedTrackResults, activeQuery: cloudSearch.activeQuery)
                case .playlist, .album:
                    cloudCollectionRow(item: item, ext: ext)
                }
            }

            if cloudSearch.isLoadingMore {
                HStack(spacing: 10) {
                    Spacer()
                    ProgressView()
                        .controlSize(.small)
                    Text(L10n.tr("Loading more…"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                .padding(.vertical, 12)
                .listRowSeparator(.hidden)
            } else if let loadMoreErr = cloudSearch.loadMoreError {
                HStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text(loadMoreErr.message)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                    Spacer()
                    Button {
                        cloudSearch.retry(currentQuery: trimmedQuery, descriptor: ext, extensionStore: extensionStore)
                    } label: {
                        Text(L10n.tr("Retry"))
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .layoutPriority(1)
                }
                .padding(.vertical, 8)
                .listRowSeparator(.hidden)
            } else if cloudSearch.nextCursor != nil {
                HStack {
                    Spacer()
                    Button(L10n.tr("Load More")) {
                        cloudSearch.loadMore(descriptor: ext, extensionStore: extensionStore)
                    }
                    .buttonStyle(.bordered)
                    Spacer()
                }
                .padding(.vertical, 10)
                .listRowSeparator(.hidden)
            }
        }
    }

    @ViewBuilder
    private func cloudErrorView(
        error: CloudSearchErrorInfo,
        ext: CloudSearchExtensionDescriptor,
        cloudSearch: CloudSearchController
    ) -> some View {
        ContentUnavailableView {
            Label(
                error.kind == .authRequired ? L10n.tr("Authentication Required") : L10n.tr("Search Failed"),
                systemImage: error.kind == .authRequired ? "key.fill" : "exclamationmark.triangle"
            )
        } description: {
            if error.kind == .authRequired {
                Text(L10n.tr("Configure a Cookie for “%@” in Extension Manager, then retry.", ext.name))
            } else {
                Text(error.message)
            }
        } actions: {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) {
                    if error.kind == .authRequired {
                        cloudErrorAuthButton(ext: ext)
                    }
                    cloudErrorRetryButton(ext: ext, cloudSearch: cloudSearch)
                }
                VStack(spacing: 10) {
                    if error.kind == .authRequired {
                        cloudErrorAuthButton(ext: ext)
                    }
                    cloudErrorRetryButton(ext: ext, cloudSearch: cloudSearch)
                }
            }
        }
        .padding(.vertical, 20)
    }

    private func cloudErrorAuthButton(ext: CloudSearchExtensionDescriptor) -> some View {
        Button {
            extensionManagerTargetID = ext.id
            showExtensionManager = true
        } label: {
            Text(ext.supportsCookie ? L10n.tr("Configure Cookie…") : L10n.tr("Open Extension Manager"))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(.bordered)
    }

    private func cloudErrorRetryButton(
        ext: CloudSearchExtensionDescriptor,
        cloudSearch: CloudSearchController
    ) -> some View {
        Button {
            cloudSearch.retry(currentQuery: trimmedQuery, descriptor: ext, extensionStore: extensionStore)
        } label: {
            Text(L10n.tr("Retry"))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(.borderedProminent)
    }

    @ViewBuilder
    private func cloudTrackRow(
        item: CloudSearchResult,
        ext: CloudSearchExtensionDescriptor,
        allTrackResults: [CloudSearchResult],
        activeQuery: String
    ) -> some View {
        let ephemeralID = item.remoteTrackID.map { Track.makeEphemeralID(extensionID: ext.id, remoteTrackID: $0) }
        let isCurrent = ephemeralID != nil && player.currentTrack?.id == ephemeralID
        let subtitleParts = [item.artist, item.album]
            .compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let subtitle = subtitleParts.isEmpty ? ext.badgeLabel : subtitleParts.joined(separator: " — ")

        Button {
            playSearchTracks(from: item, in: allTrackResults, ext: ext, query: activeQuery)
        } label: {
            HStack(spacing: 12) {
                CloudThumbnailView(urlString: item.artworkUrl, placeholderSymbol: "music.note", size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.title)
                        .foregroundStyle(isCurrent ? themeManager.accentColor : .primary)
                        .lineLimit(1)
                    Text(subtitle)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 8)
                if isCurrent {
                    Image(systemName: player.isPlaying ? "waveform" : "pause.fill")
                        .foregroundStyle(themeManager.accentColor)
                        .symbolEffect(.variableColor.iterative, isActive: player.isPlaying)
                }
                if let duration = item.durationSec, duration > 0 {
                    Text(formatTime(duration))
                        .font(.subheadline.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            if let track = item.makeEphemeralTrack() {
                Button(L10n.tr("Play Next"), systemImage: "text.line.first.and.arrowtriangle.forward") {
                    library.registerEphemeralTracks([track])
                    player.playNext([track.id])
                }
                Button(L10n.tr("Add to Queue"), systemImage: "text.append") {
                    library.registerEphemeralTracks([track])
                    player.addToQueue([track.id], queue: nil)
                }
                if let queues, queues.queues.count > 1 {
                    Menu(L10n.tr("Add to Another Queue"), systemImage: "rectangle.stack.badge.plus") {
                        ForEach(queues.queues.filter { $0.id != queues.viewedQueueID }) { queue in
                            Button(L10n.displayQueueName(queue.name)) {
                                library.registerEphemeralTracks([track])
                                player.addToQueue([track.id], queue: queue.id)
                            }
                        }
                    }
                }
                Button(L10n.tr("Play in New Queue"), systemImage: "plus.rectangle.on.rectangle") {
                    library.registerEphemeralTracks([track])
                    player.playInNewQueue([track.id])
                }
                if ext.supportsStream, let cloudExport {
                    Divider()
                    Button(L10n.tr("Save to Local Folder…"), systemImage: "square.and.arrow.down") {
                        cloudExport.request = CloudExportPrompt.Request(track: track, root: nil) { [library] savedURL in
                            if library.containsLocalURL(savedURL) {
                                await library.rescan()
                            }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func cloudCollectionRow(item: CloudSearchResult, ext: CloudSearchExtensionDescriptor) -> some View {
        let kindLabel = item.kind == .album ? L10n.tr("Album") : L10n.tr("Playlist")
        let placeholder = item.kind == .album ? "square.stack" : "music.note.list"
        let hasSourceURL = (item.sourceUrl?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false)

        Button {
            previewTarget = PreviewTarget(extensionDescriptor: ext, item: item)
        } label: {
            HStack(spacing: 12) {
                CloudThumbnailView(urlString: item.artworkUrl, placeholderSymbol: placeholder, size: 44)
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.title)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        Text(kindLabel)
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .background(themeManager.accentColor.opacity(0.14), in: Capsule())
                            .foregroundStyle(themeManager.accentColor)
                        if let artist = item.artist, !artist.isEmpty {
                            Text(artist)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        if let count = item.trackCount, count > 0 {
                            Text("· \(L10n.songCount(count))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(L10n.tr("Open Preview"), systemImage: "eye") {
                previewTarget = PreviewTarget(extensionDescriptor: ext, item: item)
            }
            if hasSourceURL {
                Button(L10n.tr("Add as Cloud Folder"), systemImage: "folder.badge.plus") {
                    addCollectionAsCloudFolder(item: item, ext: ext)
                }
            }
        }
    }

    private func playSearchTracks(
        from tappedItem: CloudSearchResult,
        in trackResults: [CloudSearchResult],
        ext: CloudSearchExtensionDescriptor,
        query: String
    ) {
        let pairs: [(result: CloudSearchResult, track: Track)] = trackResults.compactMap { res in
            guard let track = res.makeEphemeralTrack() else { return nil }
            return (res, track)
        }
        guard !pairs.isEmpty else { return }
        let startIndex = pairs.firstIndex(where: { $0.result.id == tappedItem.id }) ?? 0
        let tracks = pairs.map(\.track)
        library.registerEphemeralTracks(tracks)
        let queueQuery = query.isEmpty ? trimmedQuery : query
        let queueName = "Search (\(ext.badgeLabel)): \(queueQuery)"
        player.playInNamedQueue(tracks.map(\.id), name: queueName, startAt: startIndex, shuffle: false)
    }

    private func addCollectionAsCloudFolder(item: CloudSearchResult, ext: CloudSearchExtensionDescriptor) {
        guard let sourceUrl = item.sourceUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
              !sourceUrl.isEmpty else {
            return
        }
        let cookie = ext.supportsCookie ? extensionStore.cookie(forExtensionID: ext.id) : nil
        Task {
            do {
                try await library.addCloudRoot(
                    sourceURL: sourceUrl,
                    cookie: cookie,
                    preferredProviderID: ext.providerID
                )
            } catch LibraryError.duplicateRoot {
                // Already mounted in Folders.
            } catch {
                addFolderError = error.localizedDescription
            }
        }
    }
}
