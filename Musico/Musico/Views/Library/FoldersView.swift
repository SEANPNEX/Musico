import SwiftUI
import UniformTypeIdentifiers

struct FolderRef: Hashable {
    var root: UUID
    var path: String
}

struct FoldersView: View {
    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player
    @Environment(\.languageManager) private var languageManager
    @State private var showImporter = false
    @State private var showCloudSheet = false
    /// When set, the next picked folder replaces this unavailable root instead of adding a new one.
    @State private var relocating: UUID?
    @State private var importError: String?
    /// The folder the user asked to remove, awaiting confirmation.
    @State private var removing: LibraryRoot?
    /// Cloud root queued for local export.
    @State private var exportingRoot: LibraryRoot?
    /// Local folder linked to a cloud source queued for incremental sync.
    @State private var updatingTrackedRoot: LibraryRoot?

    var body: some View {
        List {
            Section {
                ForEach(library.roots) { root in
                    rootRow(root)
                }
                if library.appFolderRemoved {
                    Button(L10n.tr("Add Music Folder Back"), systemImage: "music.note.house") {
                        Task { await library.restoreAppFolder() }
                    }
                }
            } footer: {
                Text(Self.hint)
            }
            if !library.cloudSyncErrors.isEmpty {
                Section(L10n.tr("Cloud Sync Issues")) {
                    ForEach(library.roots.filter { library.cloudSyncErrors[$0.id] != nil }) { root in
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(.orange)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(L10n.displayRootName(root)).font(.subheadline.weight(.medium))
                                Text(library.cloudSyncErrors[root.id] ?? "")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button(L10n.tr("Retry")) { Task { await library.rescan() } }
                                .buttonStyle(.borderless)
                                .disabled(library.isScanning)
                        }
                    }
                }
            }
            if library.isScanning {
                Section {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text(library.scanProgress.total > 0
                             ? L10n.tr("Reading %@ of %@ songs…", String(library.scanProgress.done), String(library.scanProgress.total))
                             : L10n.tr("Looking for music…"))
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle(L10n.tr("Folders"))
        .navigationDestination(for: FolderRef.self) { FolderDetailView(ref: $0) }
        .refreshable { await library.rescan() }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(L10n.tr("Rescan"), systemImage: "arrow.clockwise") { Task { await library.rescan() } }
                    .disabled(library.isScanning)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Menu {
                    ForEach(AppLanguage.allCases) { lang in
                        Button {
                            languageManager.setLanguage(lang)
                        } label: {
                            if languageManager.selection == lang {
                                Label(lang.displayName, systemImage: "checkmark")
                            } else {
                                Text(lang.displayName)
                            }
                        }
                    }
                } label: {
                    Label(L10n.tr("Language"), systemImage: "globe")
                }
                Button(L10n.tr("Add Cloud Folder"), systemImage: "icloud.and.arrow.down") {
                    showCloudSheet = true
                }
                Button(L10n.tr("Add Folder"), systemImage: "folder.badge.plus") {
                    relocating = nil
                    showImporter = true
                }
            }
        }
        .sheet(isPresented: $showCloudSheet) {
            AddCloudFolderSheet(library: library)
                .environment(library)
                .environment(\.locale, languageManager.effectiveLocale)
        }
        .sheet(item: $exportingRoot) { root in
            SaveCloudPlaylistSheet(root: root, library: library)
                .environment(library)
                .environment(\.locale, languageManager.effectiveLocale)
        }
        .sheet(item: $updatingTrackedRoot) { root in
            UpdateTrackedFolderSheet(root: root, library: library)
                .environment(library)
                .environment(\.locale, languageManager.effectiveLocale)
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.folder]) { result in
            guard case .success(let url) = result else { return }
            let target = relocating
            Task {
                do {
                    if let target {
                        try await library.relocateRoot(target, to: url)
                    } else {
                        try await library.addRoot(url: url)
                    }
                } catch {
                    importError = error.localizedDescription
                }
            }
        }
        .confirmationDialog(L10n.tr("Remove “%@” from your library?", removing.map { L10n.displayRootName($0) } ?? ""),
                            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible, presenting: removing) { root in
            Button(L10n.tr("Remove from Library"), role: .destructive) { library.removeRoot(root.id) }
            Button(L10n.tr("Cancel"), role: .cancel) {}
        } message: { root in
            if root.kind.isCloud {
                Text(L10n.tr("Its songs leave your library, playlists and queues."))
            } else {
                Text(L10n.tr("Its songs leave your library, playlists and queues. The files themselves are not deleted."))
            }
        }
        .alert(L10n.tr("Couldn’t Add Folder"), isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
            Button(L10n.tr("OK"), role: .cancel) {}
        } message: {
            Text(importError ?? "")
        }
    }

    private static var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    @MainActor
    private static var hint: String {
        #if targetEnvironment(macCatalyst)
        L10n.tr("Your Mac’s Music folder is included to start with. Add local folders or YouTube/Bilibili cloud playlists, or remove any you don’t want.")
        #else
        L10n.tr(isPad
            ? "Add a folder from Files, link a YouTube or Bilibili playlist, or copy music into the Musico folder in Files › On My iPad."
            : "Add a folder from Files, link a YouTube or Bilibili playlist, or copy music into the Musico folder in Files › On My iPhone.")
        #endif
    }

    private static var appFolderIcon: String {
        #if targetEnvironment(macCatalyst)
        "music.note.house"
        #else
        isPad ? "ipad" : "iphone"
        #endif
    }

    private static func iconName(for root: LibraryRoot) -> String {
        switch root.kind {
        case .appFolder: appFolderIcon
        case .picked: "folder.fill"
        case .cloudYouTube: "play.rectangle.fill"
        case .cloudBilibili: "play.tv.fill"
        }
    }

    @MainActor
    private static func sourceLabel(for root: LibraryRoot) -> String? {
        switch root.kind {
        case .cloudYouTube:
            return "YouTube"
        case .cloudBilibili:
            return "Bilibili"
        case .picked, .appFolder:
            guard root.isCloudTrackedLocal else { return nil }
            switch root.linkedCloudKind {
            case .cloudYouTube: return L10n.tr("YouTube · Local")
            case .cloudBilibili: return L10n.tr("Bilibili · Local")
            default: return L10n.tr("Cloud · Local")
            }
        }
    }

    @ViewBuilder
    private func rootRow(_ root: LibraryRoot) -> some View {
        let available = library.isAvailable(root)
        let count = library.tracks.lazy.filter { $0.rootID == root.id }.count
        let removable = library.isRemovable(root)
        let syncError = library.cloudSyncErrors[root.id]
        let rootDisplayName = L10n.displayRootName(root)
        NavigationLink(value: FolderRef(root: root.id, path: "")) {
            HStack(spacing: 12) {
                Image(systemName: Self.iconName(for: root))
                    .foregroundStyle(root.kind.isCloud ? Color.red : Color.accentColor)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(rootDisplayName)
                            .lineLimit(1)
                        if let label = Self.sourceLabel(for: root) {
                            Text(label)
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.accentColor.opacity(0.14), in: Capsule())
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                    if let syncError {
                        Text(L10n.tr("%@ · Sync issue: %@", L10n.songCount(count), syncError))
                            .font(.subheadline)
                            .foregroundStyle(.orange)
                            .lineLimit(1)
                    } else {
                        Text(available ? L10n.songCount(count) : L10n.tr("Unavailable — folder moved or disconnected"))
                            .font(.subheadline)
                            .foregroundStyle(available ? Color.secondary : Color.orange)
                    }
                }
                if removable {
                    Spacer()
                    Button(L10n.tr("Remove Folder"), systemImage: "minus.circle") { removing = root }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                        .help(L10n.tr("Remove Folder"))
                        .accessibilityLabel(L10n.tr("Remove Folder"))
                }
            }
        }
        .swipeActions(edge: .trailing) {
            if removable {
                Button(L10n.tr("Remove"), systemImage: "trash", role: .destructive) { library.removeRoot(root.id) }
            }
        }
        .contextMenu {
            let folderIDs = library.orderedTracks(under: root.id, path: "").map(\.id)
            if !folderIDs.isEmpty {
                Button(L10n.tr("Play Folder"), systemImage: "play.fill") {
                    player.playInNamedQueue(folderIDs, name: root.name)
                }
            }
            if root.kind.isCloud {
                Button(L10n.tr("Save to Local Folder…"), systemImage: "square.and.arrow.down") {
                    exportingRoot = root
                }
            }
            if root.isCloudTrackedLocal {
                Button(L10n.tr("Update from Cloud"), systemImage: "icloud.and.arrow.down") {
                    updatingTrackedRoot = root
                }
            }
            if root.kind == .picked {
                Button(L10n.tr("Locate Again…"), systemImage: "folder.badge.questionmark") {
                    relocating = root.id
                    showImporter = true
                }
            }
            if !root.normalizedExcludedPaths.isEmpty {
                Button(L10n.tr("Restore Removed Subfolders (%@)", String(root.normalizedExcludedPaths.count)),
                       systemImage: "arrow.uturn.backward") {
                    Task { await library.restoreExcludedFolders(root.id) }
                }
            }
            if removable {
                Button(L10n.tr("Remove Folder"), systemImage: "trash", role: .destructive) { removing = root }
            }
            TrackMenuItems(ids: library.tracks(under: root.id, path: "").map(\.id))
        }
    }
}

struct AddCloudFolderSheet: View {
    private let explicitLibrary: LibraryStore?
    @Environment(LibraryStore.self) private var envLibrary: LibraryStore?
    @Environment(\.dismiss) private var dismiss

    @State private var urlText = ""
    @State private var cookieText = ""
    @State private var isLoading = false
    @State private var errorMessage: String?

    init(library: LibraryStore? = nil) {
        self.explicitLibrary = library
    }

    private var library: LibraryStore? { explicitLibrary ?? envLibrary }

    private var extractedSource: String {
        CloudSourceInput.extract(from: urlText)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L10n.tr("YouTube or Bilibili URL or ID"), text: $urlText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                } header: {
                    Text(L10n.tr("Playlist or Video Link"))
                } footer: {
                    Text(L10n.tr("Examples: youtube.com/playlist?list=…, music.youtube.com/playlist?list=…, youtu.be/…, bilibili.com/video/BV…, space.bilibili.com/…/favlist?fid=…, b23.tv/…"))
                }

                Section {
                    TextField(L10n.tr("Cookie (optional)"), text: $cookieText, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .lineLimit(2...4)
                } header: {
                    Text(L10n.tr("Authentication"))
                } footer: {
                    Text(L10n.tr("Public playlists work without signing in. Add a Cookie only for private playlists or higher Bilibili audio quality."))
                }

                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(L10n.tr("Add Cloud Folder"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.tr("Cancel")) { dismiss() }
                        .disabled(isLoading)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isLoading {
                        ProgressView()
                    } else {
                        Button(L10n.tr("Add")) { add() }
                            .disabled(extractedSource.isEmpty || library == nil)
                    }
                }
            }
        }
    }

    private func add() {
        guard let library else { return }
        errorMessage = nil
        isLoading = true
        let source = extractedSource
        let cookie = cookieText
        Task {
            do {
                try await library.addCloudRoot(sourceURL: source, cookie: cookie)
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
                isLoading = false
            }
        }
    }
}

struct FolderDetailView: View {
    let ref: FolderRef
    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player
    @Environment(PlayHistoryStore.self) private var history
    @Environment(\.languageManager) private var languageManager
    @Environment(\.dismiss) private var dismiss
    @State private var confirmRemove = false
    @State private var removingSubfolder: FolderRef?
    @State private var showExportSheet = false
    @State private var showUpdateTrackedSheet = false

    var body: some View {
        let root = library.root(ref.root)
        let contents = library.folderContents(root: ref.root, path: ref.path)
        let directTracks = library.orderedFolderTracks(root: ref.root, path: ref.path, history: history)
        let everything = library.orderedTracks(under: ref.root, path: ref.path, history: history)
        let isRemovableRoot = ref.path.isEmpty && (root.map { library.isRemovable($0) } ?? false)
        let canRemoveCurrentFolder = !ref.path.isEmpty || isRemovableRoot
        let excludedCount = ref.path.isEmpty ? (root?.normalizedExcludedPaths.count ?? 0) : 0
        let queueName = ref.path.isEmpty ? (root?.name ?? "Folder") : (ref.path as NSString).lastPathComponent
        let currentName = ref.path.isEmpty
            ? (root.map { L10n.displayRootName($0) } ?? L10n.tr("Folder"))
            : (ref.path as NSString).lastPathComponent
        let currentSortOrder = library.folderSortOrder(root: ref.root, path: ref.path)

        List {
            if everything.isEmpty {
                ContentUnavailableView(
                    L10n.tr("No Music Here"),
                    systemImage: root?.kind.isCloud == true ? "icloud" : "folder",
                    description: Text(L10n.tr(root?.kind.isCloud == true
                                      ? "Pull to refresh to sync tracks from this cloud source."
                                      : "Songs you add to this folder appear after a rescan."))
                )
            } else {
                PlayShuffleButtons(ids: everything.map(\.id), queueName: queueName)
            }
            ForEach(contents.folders, id: \.self) { name in
                let child = FolderRef(root: ref.root, path: ref.path.isEmpty ? name : ref.path + "/" + name)
                NavigationLink(value: child) {
                    HStack(spacing: 12) {
                        Label(name, systemImage: "folder")
                        Spacer()
                        Button(L10n.tr("Remove Folder"), systemImage: "minus.circle") {
                            removingSubfolder = child
                        }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                        .help(L10n.tr("Remove Folder"))
                        .accessibilityLabel(L10n.tr("Remove Folder"))
                    }
                }
                .swipeActions(edge: .trailing) {
                    Button(L10n.tr("Remove"), systemImage: "trash", role: .destructive) {
                        removingSubfolder = child
                    }
                }
                .contextMenu {
                    let childIDs = library.orderedTracks(under: child.root, path: child.path, history: history).map(\.id)
                    if !childIDs.isEmpty {
                        Button(L10n.tr("Play Folder"), systemImage: "play.fill") {
                            player.playInNamedQueue(childIDs, name: name)
                        }
                    }
                    Button(L10n.tr("Remove Folder"), systemImage: "trash", role: .destructive) {
                        removingSubfolder = child
                    }
                    TrackMenuItems(ids: childIDs)
                }
            }
            ForEach(Array(directTracks.enumerated()), id: \.element.id) { index, track in
                ReorderableTrackRow(
                    track: track,
                    index: index,
                    scopeID: "folder-\(LibraryStore.folderKey(rootID: ref.root, path: ref.path))",
                    subtitle: track.isCloud ? L10n.displayArtist(track.artist) : track.fileName,
                    isAvailable: library.isAvailable(track),
                    onTap: {
                        let allIDs = everything.map(\.id)
                        let startAt = allIDs.firstIndex(of: track.id) ?? index
                        player.playInNamedQueue(allIDs, name: queueName, startAt: startAt)
                    },
                    onMove: { from, to in
                        library.moveFolderTracks(
                            root: ref.root,
                            path: ref.path,
                            currentTrackIDs: directTracks.map(\.id),
                            from: IndexSet(integer: from),
                            to: to
                        )
                    }
                )
                .contextMenu { TrackMenuItems(ids: [track.id], favouriteID: track.id) }
            }
            .onMove { source, destination in
                library.moveFolderTracks(
                    root: ref.root,
                    path: ref.path,
                    currentTrackIDs: directTracks.map(\.id),
                    from: source,
                    to: destination
                )
            }
        }
        .listStyle(.plain)
        .navigationTitle(currentName)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await library.rescan() }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if !directTracks.isEmpty {
                    TrackSortMenu(selection: currentSortOrder) { order in
                        library.setFolderSortOrder(order, root: ref.root, path: ref.path)
                    }
                }
                if ref.path.isEmpty, let root, root.kind.isCloud {
                    Button(L10n.tr("Save to Local Folder…"), systemImage: "square.and.arrow.down") {
                        showExportSheet = true
                    }
                    .help(L10n.tr("Save to Local Folder…"))
                }
                if ref.path.isEmpty, let root, root.isCloudTrackedLocal {
                    Button(L10n.tr("Update from Cloud"), systemImage: "icloud.and.arrow.down") {
                        showUpdateTrackedSheet = true
                    }
                    .help(L10n.tr("Update from Cloud"))
                }
                if excludedCount > 0 {
                    Button(L10n.tr("Restore Removed Subfolders (%@)", String(excludedCount)), systemImage: "arrow.uturn.backward") {
                        Task { await library.restoreExcludedFolders(ref.root) }
                    }
                    .help(L10n.tr("Restore Removed Subfolders (%@)", String(excludedCount)))
                }
                if canRemoveCurrentFolder {
                    Button(L10n.tr("Remove Folder"), systemImage: "trash", role: .destructive) {
                        confirmRemove = true
                    }
                    .help(L10n.tr("Remove Folder"))
                }
            }
        }
        .sheet(isPresented: $showExportSheet) {
            if let root {
                SaveCloudPlaylistSheet(root: root, library: library)
                    .environment(library)
                    .environment(\.locale, languageManager.effectiveLocale)
            }
        }
        .sheet(isPresented: $showUpdateTrackedSheet) {
            if let root {
                UpdateTrackedFolderSheet(root: root, library: library)
                    .environment(library)
                    .environment(\.locale, languageManager.effectiveLocale)
            }
        }
        .confirmationDialog(L10n.tr("Remove “%@” from your library?", currentName),
                            isPresented: $confirmRemove,
                            titleVisibility: .visible) {
            Button(L10n.tr("Remove from Library"), role: .destructive) {
                if ref.path.isEmpty {
                    library.removeRoot(ref.root)
                } else {
                    library.removeSubfolder(rootID: ref.root, path: ref.path)
                }
                dismiss()
            }
            Button(L10n.tr("Cancel"), role: .cancel) {}
        } message: {
            if root?.kind.isCloud == true {
                Text(L10n.tr("Its songs leave your library, playlists and queues."))
            } else {
                Text(L10n.tr("Its songs leave your library, playlists and queues. The files themselves are not deleted."))
            }
        }
        .confirmationDialog(L10n.tr("Remove “%@” from your library?", (removingSubfolder?.path as NSString?)?.lastPathComponent ?? ""),
                            isPresented: Binding(get: { removingSubfolder != nil }, set: { if !$0 { removingSubfolder = nil } }),
                            titleVisibility: .visible,
                            presenting: removingSubfolder) { target in
            Button(L10n.tr("Remove from Library"), role: .destructive) {
                library.removeSubfolder(rootID: target.root, path: target.path)
            }
            Button(L10n.tr("Cancel"), role: .cancel) {}
        } message: { _ in
            if root?.kind.isCloud == true {
                Text(L10n.tr("Its songs leave your library, playlists and queues."))
            } else {
                Text(L10n.tr("Its songs leave your library, playlists and queues. The files themselves are not deleted."))
            }
        }
    }
}

struct SaveCloudPlaylistSheet: View {
    let root: LibraryRoot
    private let explicitLibrary: LibraryStore?
    @Environment(LibraryStore.self) private var envLibrary: LibraryStore?
    @Environment(\.dismiss) private var dismiss

    @State private var folderName: String
    @State private var parentFolderURL: URL?
    @State private var addToLibrary = true
    @State private var showLocationPicker = false
    @State private var isExporting = false
    @State private var progress: (done: Int, total: Int, title: String) = (0, 0, "")
    @State private var exportTask: Task<Void, Never>?
    @State private var summary: CloudExporter.ExportSummary?
    @State private var errorMessage: String?

    init(root: LibraryRoot, library: LibraryStore? = nil) {
        self.root = root
        self.explicitLibrary = library
        _folderName = State(initialValue: CloudExporter.sanitizeFileName(root.name, fallback: "Cloud Playlist"))
    }

    private var library: LibraryStore? { explicitLibrary ?? envLibrary }

    private var cloudTracks: [Track] {
        library?.tracks(under: root.id, path: "").filter(\.isCloud) ?? []
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(L10n.tr("Destination")) {
                    TextField(L10n.tr("Folder Name"), text: $folderName)
                        .disabled(isExporting || summary != nil)
                    HStack {
                        Text(L10n.tr("Location"))
                        Spacer()
                        Button(parentFolderURL?.lastPathComponent ?? L10n.tr("Choose Folder…")) {
                            showLocationPicker = true
                        }
                        .disabled(isExporting || summary != nil)
                    }
                    Toggle(L10n.tr("Add saved folder to Library"), isOn: $addToLibrary)
                        .disabled(isExporting || summary != nil)
                }

                if isExporting {
                    Section(L10n.tr("Progress")) {
                        VStack(alignment: .leading, spacing: 8) {
                            let currentStep = min(progress.done + (progress.done < progress.total ? 1 : 0), max(progress.total, 1))
                            ProgressView(value: Double(progress.done), total: Double(max(progress.total, 1)))
                            Text(L10n.tr("Saving %@ of %@ songs…", String(currentStep), String(progress.total)))
                                .font(.subheadline)
                            if !progress.title.isEmpty {
                                Text(progress.title)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }

                if let summary {
                    Section(L10n.tr("Summary")) {
                        Label(
                            L10n.tr("Saved %@ to “%@”", L10n.songCount(summary.savedCount), summary.destinationURL.lastPathComponent),
                            systemImage: summary.failedCount == 0 ? "checkmark.circle.fill" : "exclamationmark.circle.fill"
                        )
                        .foregroundStyle(summary.failedCount == 0 ? Color.green : Color.orange)

                        if summary.failedCount > 0 {
                            Text(L10n.tr("%@ failed to download.", L10n.songCount(summary.failedCount)))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            ForEach(Array(summary.errors.prefix(5).enumerated()), id: \.offset) { _, errorText in
                                Text(errorText)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(L10n.tr("Save Cloud Playlist"))
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(isExporting)
            .fileImporter(isPresented: $showLocationPicker, allowedContentTypes: [.folder]) { result in
                switch result {
                case .success(let url):
                    parentFolderURL = url
                    errorMessage = nil
                case .failure(let error):
                    errorMessage = error.localizedDescription
                }
            }
            .onDisappear {
                exportTask?.cancel()
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if isExporting {
                        Button(L10n.tr("Cancel"), role: .cancel) {
                            exportTask?.cancel()
                        }
                    } else {
                        Button(summary == nil ? L10n.tr("Cancel") : L10n.tr("Done")) {
                            dismiss()
                        }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if summary == nil && !isExporting {
                        Button(L10n.tr("Save")) {
                            startExport()
                        }
                        .disabled(
                            parentFolderURL == nil
                            || cloudTracks.isEmpty
                            || folderName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        )
                    }
                }
            }
        }
    }

    private func startExport() {
        guard let parentURL = parentFolderURL else { return }
        let tracksToSave = cloudTracks
        let requestedFolderName = folderName
        errorMessage = nil
        summary = nil
        isExporting = true
        progress = (0, tracksToSave.count, tracksToSave.first?.title ?? "")

        exportTask = Task { @MainActor in
            let scoped = parentURL.startAccessingSecurityScopedResource()
            defer {
                if scoped { parentURL.stopAccessingSecurityScopedResource() }
            }
            do {
                let result = try await CloudExporter.exportPlaylist(
                    root: root,
                    tracks: tracksToSave,
                    toParentFolder: parentURL,
                    folderName: requestedFolderName
                ) { done, total, title in
                    progress = (done, total, title)
                }
                summary = result
                isExporting = false
                if addToLibrary, let library, FileManager.default.fileExists(atPath: result.destinationURL.path) {
                    do {
                        try await library.addRoot(url: result.destinationURL)
                    } catch LibraryError.duplicateRoot {
                        await library.rescan()
                        library.notifyFolderQueueSync(folderURL: result.destinationURL)
                    } catch {
                        errorMessage = L10n.tr("Saved songs, but couldn’t add folder to library: %@", error.localizedDescription)
                    }
                    library.attachCloudTracking(toFolderURL: result.destinationURL, from: root)
                }
            } catch is CancellationError {
                isExporting = false
                errorMessage = L10n.tr("Export cancelled.")
            } catch {
                isExporting = false
                errorMessage = error.localizedDescription
            }
        }
    }
}

struct UpdateTrackedFolderSheet: View {
    let root: LibraryRoot
    private let explicitLibrary: LibraryStore?
    @Environment(LibraryStore.self) private var envLibrary: LibraryStore?
    @Environment(\.dismiss) private var dismiss

    @State private var isUpdating = true
    @State private var progress: (done: Int, total: Int, title: String) = (0, 0, "")
    @State private var summary: CloudExporter.CloudUpdateSummary?
    @State private var errorMessage: String?
    @State private var syncTask: Task<Void, Never>?

    init(root: LibraryRoot, library: LibraryStore? = nil) {
        self.root = root
        self.explicitLibrary = library
    }

    private var library: LibraryStore? { explicitLibrary ?? envLibrary }

    var body: some View {
        NavigationStack {
            Form {
                if let sourceURL = root.sourceURL, !sourceURL.isEmpty {
                    Section(L10n.tr("Cloud Source")) {
                        Text(sourceURL)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                }

                if isUpdating {
                    Section(L10n.tr("Syncing")) {
                        VStack(alignment: .leading, spacing: 8) {
                            if progress.total > 0 {
                                let step = min(progress.done + (progress.done < progress.total ? 1 : 0), max(progress.total, 1))
                                ProgressView(value: Double(progress.done), total: Double(max(progress.total, 1)))
                                Text(L10n.tr("Checking %@ of %@ songs…", String(step), String(progress.total)))
                                    .font(.subheadline)
                            } else {
                                ProgressView()
                                Text(L10n.tr("Fetching latest cloud playlist…"))
                                    .font(.subheadline)
                            }
                            if !progress.title.isEmpty {
                                Text(progress.title)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }

                if let summary {
                    Section(L10n.tr("Result")) {
                        Label(
                            summary.addedCount > 0
                                ? L10n.tr("Added %@ new (%@ already saved).", L10n.songCount(summary.addedCount), String(summary.skippedCount))
                                : L10n.tr("Already up to date (%@).", L10n.songCount(summary.skippedCount)),
                            systemImage: summary.failedCount == 0 ? "checkmark.circle.fill" : "exclamationmark.circle.fill"
                        )
                        .foregroundStyle(summary.failedCount == 0 ? Color.green : Color.orange)

                        if summary.failedCount > 0 {
                            Text(L10n.tr("%@ failed.", L10n.songCount(summary.failedCount)))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                            ForEach(Array(summary.errors.prefix(5).enumerated()), id: \.offset) { _, err in
                                Text(err)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(L10n.tr("Update “%@”", L10n.displayRootName(root)))
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(isUpdating)
            .onDisappear { syncTask?.cancel() }
            .task {
                await startUpdate()
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if isUpdating {
                        Button(L10n.tr("Cancel"), role: .cancel) {
                            syncTask?.cancel()
                        }
                    } else {
                        Button(L10n.tr("Done")) { dismiss() }
                    }
                }
            }
        }
    }

    private func startUpdate() async {
        guard let library else {
            isUpdating = false
            return
        }
        isUpdating = true
        summary = nil
        errorMessage = nil
        let task = Task { @MainActor in
            do {
                let result = try await library.updateTrackedLocalRoot(root.id) { done, total, title in
                    progress = (done, total, title)
                }
                summary = result
            } catch is CancellationError {
                errorMessage = L10n.tr("Update cancelled.")
            } catch {
                errorMessage = error.localizedDescription
            }
            isUpdating = false
        }
        syncTask = task
        await task.value
    }
}
