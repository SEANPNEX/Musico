import SwiftUI
import UniformTypeIdentifiers

struct FolderRef: Hashable {
    var root: UUID
    var path: String
}

struct FoldersView: View {
    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player
    @Environment(\.downloadManager) private var downloadManager
    @Environment(\.extensionStore) private var extensionStore
    @Environment(\.lyricsStore) private var lyricsStore
    @Environment(\.languageManager) private var languageManager
    @Environment(\.themeManager) private var themeManager
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(DeleteItemPrompt.self) private var deletePrompt: DeleteItemPrompt?
    @State private var showImporter = false
    @State private var showCloudSheet = false
    @State private var showDownloadsSheet = false
    @State private var showExtensionsSheet = false
    @State private var showSettingsSheet = false
    /// When set, the next picked folder replaces this unavailable root instead of adding a new one.
    @State private var relocating: UUID?
    @State private var importError: String?
    /// Cloud root queued for local export.
    @State private var exportingRoot: LibraryRoot?
    /// Local folder linked to a cloud source queued for incremental sync.
    @State private var updatingTrackedRoot: LibraryRoot?
    /// Cloud folder or tracked local folder queued for source/provider editing.
    @State private var editingSourceTarget: CloudSourceEditTarget?

    private struct CloudSourceEditTarget: Identifiable {
        let root: LibraryRoot
        let path: String
        let preselectedProviderID: String?

        var id: String { "\(root.id.uuidString)::\(path)::\(preselectedProviderID ?? "")" }
    }

    private var unavailableExtensionRoots: [(root: LibraryRoot, availability: CloudExtensionAvailability)] {
        library.roots.compactMap { root in
            guard root.kind == .cloudExtension || root.kind.isLegacyExtensionBacked else { return nil }
            let status = library.cloudExtensionAvailability(for: root)
            return status == .available ? nil : (root, status)
        }
    }

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
                Text(Self.hint(extensionStore: extensionStore))
            }
            if !unavailableExtensionRoots.isEmpty {
                Section(L10n.tr("Unavailable Extensions")) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "puzzlepiece.extension")
                                .foregroundStyle(.orange)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(L10n.tr("Some extension folders are currently unavailable."))
                                    .font(.subheadline.weight(.medium))
                                Text(L10n.tr("Your tracks, playlists, stats, and cached audio remain intact. Re-enable or reinstall the extension to sync updates."))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Button(L10n.tr("Manage Extensions")) {
                            showExtensionsSheet = true
                        }
                        .font(.subheadline.weight(.medium))
                    }
                    .padding(.vertical, 2)
                }
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
                    if library.tracks.isEmpty && library.roots.contains(where: { $0.kind.isCloud }) {
                        SkeletonTrackListPlaceholder(count: 4)
                    }
                }
            }
        }
        .hiddenScrollBackgroundIfThemed(themeManager)
        .navigationTitle(L10n.tr("Folders"))
        #if targetEnvironment(macCatalyst)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .navigationDestination(for: FolderRef.self) { FolderDetailView(ref: $0) }
        .refreshable { await library.rescan() }
        .animation(MusicoMotion.contentChange(reduceMotion: reduceMotion), value: library.isScanning)
        .animation(MusicoMotion.contentChange(reduceMotion: reduceMotion), value: library.roots.map(\.id))
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(L10n.tr("Rescan"), systemImage: "arrow.clockwise") { Task { await library.rescan() } }
                    .disabled(library.isScanning)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                #if !targetEnvironment(macCatalyst)
                Button(L10n.tr("Settings"), systemImage: "gearshape") {
                    showSettingsSheet = true
                }
                .help(L10n.tr("Settings"))
                #endif
                Button(L10n.tr("Add Cloud Folder"), systemImage: "icloud.and.arrow.down") {
                    showCloudSheet = true
                }
                Button(L10n.tr("Add Folder"), systemImage: "folder.badge.plus") {
                    relocating = nil
                    showImporter = true
                }
                Button(L10n.tr("Extensions"), systemImage: "puzzlepiece.extension") {
                    showExtensionsSheet = true
                }
                .help(L10n.tr("Extensions"))
                if !downloadManager.jobs.isEmpty {
                    Button(
                        L10n.tr("Downloads"),
                        systemImage: downloadManager.hasActiveDownloads ? "arrow.down.circle.fill" : "arrow.down.circle"
                    ) {
                        showDownloadsSheet = true
                    }
                    .help(L10n.tr("Downloads"))
                }
            }
        }
        #if !targetEnvironment(macCatalyst)
        .sheet(isPresented: $showSettingsSheet) {
            SettingsView(isSheet: true)
                .environment(library)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
                .applyTheme(themeManager)
        }
        #endif
        .sheet(isPresented: $showExtensionsSheet) {
            NavigationStack {
                ExtensionsView(extensionStore: extensionStore, showDoneButton: true)
            }
            .environment(\.extensionStore, extensionStore)
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
        }
        .sheet(isPresented: $showDownloadsSheet) {
            DownloadsListView(downloadManager: downloadManager)
                .environment(\.downloadManager, downloadManager)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
                .applyTheme(themeManager)
        }
        .sheet(isPresented: $showCloudSheet) {
            AddCloudFolderSheet(library: library, extensionStore: extensionStore)
                .environment(library)
                .environment(\.extensionStore, extensionStore)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
                .applyTheme(themeManager)
        }
        .sheet(item: $exportingRoot) { root in
            SaveCloudPlaylistSheet(root: root, library: library, downloadManager: downloadManager)
                .environment(library)
                .environment(\.downloadManager, downloadManager)
                .environment(\.locale, languageManager.effectiveLocale)
                .applyTheme(themeManager)
        }
        .sheet(item: $updatingTrackedRoot) { root in
            UpdateTrackedFolderSheet(root: root, library: library, downloadManager: downloadManager)
                .environment(library)
                .environment(\.downloadManager, downloadManager)
                .environment(\.locale, languageManager.effectiveLocale)
                .applyTheme(themeManager)
        }
        .sheet(item: $editingSourceTarget) { target in
            EditCloudTrackingSheet(
                root: target.root,
                path: target.path,
                library: library,
                preselectedProviderID: target.preselectedProviderID
            )
            .environment(library)
            .environment(\.extensionStore, extensionStore)
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
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
        .alert(L10n.tr("Couldn’t Add Folder"), isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
            Button(L10n.tr("OK"), role: .cancel) {}
        } message: {
            Text(importError ?? "")
        }
    }

    private func confirmRemoveRoot(_ root: LibraryRoot) {
        let songCount = library.tracks.lazy.filter { $0.rootID == root.id }.count
        deletePrompt?.confirmFolder(
            rootID: root.id,
            path: "",
            name: L10n.displayRootName(root),
            songCount: songCount,
            isCloud: root.kind.isCloud,
            isAppFolderRoot: root.kind == .appFolder,
            library: library,
            lyricsStore: lyricsStore
        )
    }

    private static var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    @MainActor
    private static func hint(extensionStore: ExtensionStore) -> String {
        let extNames = extensionStore.registry.extensionProviders().map(\.displayName)
        if extNames.isEmpty {
            #if targetEnvironment(macCatalyst)
            return L10n.tr("Your Mac’s Music folder is included to start with. Add local folders, Google Drive links, or install extensions for other cloud sources.")
            #else
            return L10n.tr(isPad
                ? "Add a folder from Files, link a Google Drive source, install extensions for more cloud sources, or copy music into the Musico folder in Files › On My iPad."
                : "Add a folder from Files, link a Google Drive source, install extensions for more cloud sources, or copy music into the Musico folder in Files › On My iPhone.")
            #endif
        }
        let sources = ([L10n.tr("Google Drive")] + extNames).joined(separator: ", ")
        #if targetEnvironment(macCatalyst)
        return L10n.tr("Your Mac’s Music folder is included to start with. Add local folders or cloud sources (%@), or remove any you don’t want.", sources)
        #else
        return L10n.tr(isPad
            ? "Add a folder from Files, link a cloud source (%@), or copy music into the Musico folder in Files › On My iPad."
            : "Add a folder from Files, link a cloud source (%@), or copy music into the Musico folder in Files › On My iPhone.", sources)
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
        case .cloudGoogleDrive: "externaldrive.fill.badge.icloud"
        case .cloudExtension: "puzzlepiece.extension.fill"
        }
    }

    private static func iconColor(
        for root: LibraryRoot,
        availability: CloudExtensionAvailability = .available,
        accentColor: Color
    ) -> Color {
        switch root.kind {
        case .cloudYouTube, .cloudBilibili: .red
        case .cloudGoogleDrive: .blue
        case .cloudExtension: availability == .available ? .indigo : .orange
        case .picked, .appFolder: accentColor
        }
    }

    @MainActor
    static func localTrackingBadgeLabel(
        for kind: LibraryRoot.Kind?,
        providerID: String? = nil,
        extensionStore: ExtensionStore? = nil
    ) -> String {
        switch kind {
        case .cloudYouTube: return L10n.tr("YouTube · Local")
        case .cloudBilibili: return L10n.tr("Bilibili · Local")
        case .cloudGoogleDrive: return L10n.tr("Google Drive · Local")
        case .cloudExtension:
            if let badge = extensionBadgeLabel(providerID: providerID, extensionStore: extensionStore) {
                return L10n.tr("%@ · Local", badge)
            }
            return L10n.tr("Extension · Local")
        default: return L10n.tr("Cloud · Local")
        }
    }

    static func localTrackingColor(for kind: LibraryRoot.Kind?, accentColor: Color) -> Color {
        switch kind {
        case .cloudYouTube, .cloudBilibili: .red
        case .cloudGoogleDrive: .blue
        case .cloudExtension: .indigo
        default: accentColor
        }
    }

    @MainActor
    private static func extensionBadgeLabel(providerID: String?, extensionStore: ExtensionStore?) -> String? {
        guard let providerID, !providerID.isEmpty else { return nil }
        if let provider = (extensionStore?.registry ?? CloudProviderRegistry.shared).provider(forID: providerID) {
            return provider.badgeLabel
        }
        if let ext = extensionStore?.extension(forProviderID: providerID) {
            return ext.manifest.effectiveBadgeLabel
        }
        return nil
    }

    @MainActor
    private static func sourceLabel(for root: LibraryRoot, library: LibraryStore, extensionStore: ExtensionStore) -> String? {
        switch root.kind {
        case .cloudYouTube:
            return library.provider(for: root)?.badgeLabel ?? "YouTube"
        case .cloudBilibili:
            return library.provider(for: root)?.badgeLabel ?? "Bilibili"
        case .cloudGoogleDrive:
            return L10n.tr("Google Drive")
        case .cloudExtension:
            return library.provider(for: root)?.badgeLabel
                ?? extensionBadgeLabel(providerID: root.resolvedProviderID, extensionStore: extensionStore)
                ?? L10n.tr("Extension")
        case .picked, .appFolder:
            guard root.isCloudTrackedLocal else { return nil }
            return localTrackingBadgeLabel(
                for: root.linkedCloudKind,
                providerID: root.effectiveProviderID,
                extensionStore: extensionStore
            )
        }
    }

    @MainActor
    private func unavailableSubtitle(for count: Int, root: LibraryRoot, availability: CloudExtensionAvailability) -> String {
        switch availability {
        case .available:
            return L10n.songCount(count)
        case .disabled(let name):
            return L10n.tr("%@ · Extension “%@” disabled", L10n.songCount(count), name)
        case .missing(let providerID):
            let short = providerID.hasPrefix("ext.") ? String(providerID.dropFirst(4)) : providerID
            return L10n.tr("%@ · Missing extension (%@)", L10n.songCount(count), short)
        }
    }

    @ViewBuilder
    private func rootRow(_ root: LibraryRoot) -> some View {
        let available = library.isAvailable(root)
        let extAvailability = library.cloudExtensionAvailability(for: root)
        let count = library.tracks.lazy.filter { $0.rootID == root.id }.count
        let removable = library.isRemovable(root)
        let syncError = library.cloudSyncErrors[root.id]
        let syncProgress = library.cloudSyncProgress[root.id]
        let rootDisplayName = L10n.displayRootName(root)
        NavigationLink(value: FolderRef(root: root.id, path: "")) {
            HStack(spacing: 12) {
                Image(systemName: Self.iconName(for: root))
                    .foregroundStyle(Self.iconColor(for: root, availability: extAvailability, accentColor: themeManager.accentColor))
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(rootDisplayName)
                            .lineLimit(1)
                        if let label = Self.sourceLabel(for: root, library: library, extensionStore: extensionStore) {
                            Text(label)
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(themeManager.accentColor.opacity(0.14), in: Capsule())
                                .foregroundStyle(themeManager.accentColor)
                        }
                    }
                    if let syncProgress {
                        HStack(spacing: 6) {
                            ProgressView()
                                .controlSize(.mini)
                            Text(L10n.tr(
                                "Loading %@ / %@ folders · %@ songs…",
                                String(syncProgress.foldersDone),
                                String(syncProgress.foldersTotal),
                                String(syncProgress.tracksLoaded)
                            ))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        }
                    } else if extAvailability != .available {
                        Text(unavailableSubtitle(for: count, root: root, availability: extAvailability))
                            .font(.subheadline)
                            .foregroundStyle(.orange)
                            .lineLimit(1)
                    } else if let syncError {
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
                    Button(L10n.tr("Remove Folder"), systemImage: "minus.circle") { confirmRemoveRoot(root) }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                        .help(L10n.tr("Remove Folder"))
                        .accessibilityLabel(L10n.tr("Remove Folder"))
                }
            }
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if removable {
                Button(L10n.tr("Remove"), systemImage: "trash", role: .destructive) { confirmRemoveRoot(root) }
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
                if (root.kind == .cloudExtension || root.kind.isLegacyExtensionBacked),
                   extAvailability != .available,
                   let suggested = library.compatibleProviders(for: root).first {
                    Button(L10n.tr("Switch Source…"), systemImage: "arrow.triangle.2.circlepath") {
                        editingSourceTarget = CloudSourceEditTarget(
                            root: root,
                            path: "",
                            preselectedProviderID: suggested.id
                        )
                    }
                }
                Button(L10n.tr("Edit Cloud Source…"), systemImage: "link") {
                    editingSourceTarget = CloudSourceEditTarget(
                        root: root,
                        path: "",
                        preselectedProviderID: nil
                    )
                }
                Button(L10n.tr("Save to Local Folder…"), systemImage: "square.and.arrow.down") {
                    exportingRoot = root
                }
            }
            if root.isCloudTrackedLocal {
                Button(L10n.tr("Update from Cloud"), systemImage: "icloud.and.arrow.down") {
                    updatingTrackedRoot = root
                }
                Button(L10n.tr("Edit Cloud Source…"), systemImage: "link") {
                    editingSourceTarget = CloudSourceEditTarget(
                        root: root,
                        path: "",
                        preselectedProviderID: nil
                    )
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
                Button(L10n.tr("Remove Folder"), systemImage: "trash", role: .destructive) { confirmRemoveRoot(root) }
            }
            TrackMenuItems(ids: library.tracks(under: root.id, path: "").map(\.id))
        }
    }
}

struct AddCloudFolderSheet: View {
    private let explicitLibrary: LibraryStore?
    private let explicitExtensionStore: ExtensionStore?
    @Environment(LibraryStore.self) private var envLibrary: LibraryStore?
    @Environment(\.extensionStore) private var envExtensionStore
    @Environment(\.themeManager) private var themeManager
    @Environment(\.dismiss) private var dismiss

    @State private var urlText = ""
    @State private var cookieText = ""
    @State private var selectedProviderID: String? = nil
    @State private var isLoading = false
    @State private var errorMessage: String?

    init(library: LibraryStore? = nil, extensionStore: ExtensionStore? = nil) {
        self.explicitLibrary = library
        self.explicitExtensionStore = extensionStore
    }

    private var library: LibraryStore? { explicitLibrary ?? envLibrary }
    private var extensionStore: ExtensionStore { explicitExtensionStore ?? envExtensionStore }

    private var availableProviders: [any CloudSourceProvider] {
        extensionStore.registry.allProviders().filter(\.supportsCollection)
    }

    private var selectedProvider: (any CloudSourceProvider)? {
        guard let selectedProviderID else { return nil }
        return extensionStore.registry.provider(forID: selectedProviderID)
    }

    private var extractedSource: String {
        CloudSourceInput.extract(from: urlText, registry: extensionStore.registry)
    }

    private var detectedProviderName: String? {
        guard selectedProviderID == nil, !extractedSource.isEmpty else { return nil }
        guard let match = extensionStore.registry.match(rawText: urlText, extractedURL: extractedSource) else {
            return nil
        }
        return extensionStore.registry.provider(forID: match.providerID)?.displayName
    }

    private var shouldShowCookieField: Bool {
        selectedProvider?.supportsCookie ?? true
    }

    private var urlExamplesFooter: String {
        let extNames = extensionStore.registry.extensionProviders().map(\.displayName)
        if extNames.isEmpty {
            return L10n.tr("Examples: drive.google.com/drive/folders/…, drive.google.com/file/d/…. Install extensions in Extensions to add more cloud sources.")
        }
        return L10n.tr("Supported sources: Google Drive, %@. Example: drive.google.com/drive/folders/…", extNames.joined(separator: ", "))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(L10n.tr("Source")) {
                    Picker(L10n.tr("Provider"), selection: $selectedProviderID) {
                        Text(L10n.tr("Auto-Detect")).tag(nil as String?)
                        ForEach(availableProviders, id: \.id) { provider in
                            Text(provider.displayName).tag(Optional(provider.id))
                        }
                    }
                    if let detectedProviderName {
                        LabeledContent(L10n.tr("Detected")) {
                            Text(detectedProviderName)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Section {
                    TextField(L10n.tr("Cloud URL or ID"), text: $urlText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                } header: {
                    Text(L10n.tr("Playlist, Folder, or Video Link"))
                } footer: {
                    Text(urlExamplesFooter)
                }

                if shouldShowCookieField {
                    Section {
                        TextField(L10n.tr("Cookie (optional)"), text: $cookieText, axis: .vertical)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .lineLimit(2...4)
                    } header: {
                        Text(L10n.tr("Authentication"))
                    } footer: {
                        Text(L10n.tr("Public links work without signing in. Add a Cookie only for private playlists, restricted Google Drive links, or extension-authenticated sources."))
                    }
                }

                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                    }
                }
            }
            .hiddenScrollBackgroundIfThemed(themeManager)
            .background { ThemeBackdropView() }
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
        let rawSource = urlText
        let cookie = shouldShowCookieField ? cookieText : nil
        let preferredProvider = selectedProviderID
        Task {
            do {
                try await library.addCloudRoot(
                    sourceURL: rawSource,
                    cookie: cookie,
                    preferredProviderID: preferredProvider
                )
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
                isLoading = false
            }
        }
    }
}

struct FolderDetailView: View {
    private struct FolderTrackingTarget: Identifiable {
        let root: LibraryRoot
        let path: String
        let displayName: String
        var preselectedProviderID: String? = nil

        var id: String { "\(root.id.uuidString)::\(path)::\(preselectedProviderID ?? "")" }
    }

    let ref: FolderRef
    @Environment(LibraryStore.self) private var library
    @Environment(PlayerController.self) private var player
    @Environment(PlayHistoryStore.self) private var history
    @Environment(\.downloadManager) private var downloadManager
    @Environment(\.extensionStore) private var extensionStore
    @Environment(\.lyricsStore) private var lyricsStore
    @Environment(\.languageManager) private var languageManager
    @Environment(\.themeManager) private var themeManager
    @Environment(\.appNavigation) private var navigation
    @Environment(DeleteItemPrompt.self) private var deletePrompt: DeleteItemPrompt?
    @Environment(\.dismiss) private var dismiss
    @State private var exportingRoot: LibraryRoot?
    @State private var showExtensionsSheet = false
    @State private var updatingTarget: FolderTrackingTarget?
    @State private var editingTarget: FolderTrackingTarget?
    @State private var reorderFeedbackTrigger = 0
    @State private var isSelecting = false
    @State private var selectedTrackIDs: Set<String> = []
    @State private var batchEditTarget: BatchTagEditTarget?

    var body: some View {
        let root = library.root(ref.root)
        let extAvailability = root.map { library.cloudExtensionAvailability(for: $0) } ?? .available
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
        let syncProgress = library.cloudSyncProgress[ref.root]
        let currentTracking = library.cloudTracking(rootID: ref.root, path: ref.path)
        let editableDirectIDs = directTracks.filter { !$0.isEphemeral }.map(\.id)
        let editableAllIDs = everything.filter { !$0.isEphemeral }.map(\.id)
        let allDirectSelected = !editableDirectIDs.isEmpty && editableDirectIDs.allSatisfy { selectedTrackIDs.contains($0) }

        List {
            if let root, (root.kind == .cloudExtension || root.kind.isLegacyExtensionBacked), extAvailability != .available {
                let compatible = library.compatibleProviders(for: root)
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "puzzlepiece.extension")
                            .foregroundStyle(.orange)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(extensionBannerTitle(for: extAvailability))
                                .font(.subheadline.weight(.medium))
                            Text(L10n.tr("Your tracks, playlists, stats, and cached audio remain intact. Re-enable or reinstall the extension to sync updates."))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 12) {
                            if let suggested = compatible.first {
                                switchSourceBannerButton(root: root, currentName: currentName, suggestedID: suggested.id)
                            }
                            manageExtensionsBannerButton
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            if let suggested = compatible.first {
                                switchSourceBannerButton(root: root, currentName: currentName, suggestedID: suggested.id)
                            }
                            manageExtensionsBannerButton
                        }
                    }
                }
                .padding(.vertical, 4)
            }
            if let syncProgress {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(L10n.tr(
                        "Loading %@ / %@ folders · %@ songs…",
                        String(syncProgress.foldersDone),
                        String(syncProgress.foldersTotal),
                        String(syncProgress.tracksLoaded)
                    ))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                }
                .padding(.vertical, 2)
                if everything.isEmpty && contents.folders.isEmpty {
                    SkeletonTrackListPlaceholder(count: 6)
                }
            }
            if everything.isEmpty && contents.folders.isEmpty && syncProgress == nil {
                ContentUnavailableView(
                    L10n.tr("No Music Here"),
                    systemImage: root?.kind.isCloud == true ? "icloud" : "folder",
                    description: Text(L10n.tr(root?.kind.isCloud == true
                                      ? "Pull to refresh to sync tracks from this cloud source."
                                      : "Songs you add to this folder appear after a rescan."))
                )
            } else if !everything.isEmpty {
                PlayShuffleButtons(ids: everything.map(\.id), queueName: queueName)
            }
            ForEach(contents.folders, id: \.self) { name in
                let child = FolderRef(root: ref.root, path: ref.path.isEmpty ? name : ref.path + "/" + name)
                let childTracking = library.cloudTracking(rootID: child.root, path: child.path)
                NavigationLink(value: child) {
                    HStack(spacing: 10) {
                        if let childTracking {
                            Image(systemName: "folder.fill")
                                .foregroundStyle(FoldersView.localTrackingColor(for: childTracking.cloudKind, accentColor: themeManager.accentColor))
                        } else {
                            Image(systemName: "folder")
                                .foregroundStyle(themeManager.accentColor)
                        }
                        Text(name)
                            .lineLimit(1)
                        if let childTracking {
                            Text(FoldersView.localTrackingBadgeLabel(
                                for: childTracking.cloudKind,
                                providerID: childTracking.resolvedProviderID,
                                extensionStore: extensionStore
                            ))
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(themeManager.accentColor.opacity(0.14), in: Capsule())
                                .foregroundStyle(themeManager.accentColor)
                                .lineLimit(1)
                        }
                        Spacer()
                        Button(L10n.tr("Remove Folder"), systemImage: "minus.circle") {
                            confirmRemoveFolder(rootID: child.root, path: child.path, name: name, root: root)
                        }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                        .help(L10n.tr("Remove Folder"))
                        .accessibilityLabel(L10n.tr("Remove Folder"))
                    }
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button(L10n.tr("Remove"), systemImage: "trash", role: .destructive) {
                        confirmRemoveFolder(rootID: child.root, path: child.path, name: name, root: root)
                    }
                }
                .contextMenu {
                    let childIDs = library.orderedTracks(under: child.root, path: child.path, history: history).map(\.id)
                    if !childIDs.isEmpty {
                        Button(L10n.tr("Play Folder"), systemImage: "play.fill") {
                            player.playInNamedQueue(childIDs, name: name)
                        }
                        Button(L10n.tr("Edit All Songs' Tags…"), systemImage: "tag") {
                            batchEditTarget = BatchTagEditTarget(trackIDs: childIDs)
                        }
                    }
                    if let root, root.kind.isLocal, childTracking != nil {
                        Button(L10n.tr("Update from Cloud"), systemImage: "icloud.and.arrow.down") {
                            updatingTarget = FolderTrackingTarget(root: root, path: child.path, displayName: name)
                        }
                        Button(L10n.tr("Edit Cloud Source…"), systemImage: "link") {
                            editingTarget = FolderTrackingTarget(root: root, path: child.path, displayName: name)
                        }
                    }
                    Button(L10n.tr("Remove Folder"), systemImage: "trash", role: .destructive) {
                        confirmRemoveFolder(rootID: child.root, path: child.path, name: name, root: root)
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
                        reorderFeedbackTrigger += 1
                        library.moveFolderTracks(
                            root: ref.root,
                            path: ref.path,
                            currentTrackIDs: directTracks.map(\.id),
                            from: IndexSet(integer: from),
                            to: to
                        )
                    },
                    isSelecting: isSelecting,
                    isSelected: selectedTrackIDs.contains(track.id),
                    onToggleSelect: {
                        if selectedTrackIDs.contains(track.id) {
                            selectedTrackIDs.remove(track.id)
                        } else {
                            selectedTrackIDs.insert(track.id)
                        }
                    }
                )
                .contextMenu {
                    if !isSelecting {
                        TrackMenuItems(ids: [track.id], favouriteID: track.id)
                    }
                }
            }
            .onMove { source, destination in
                reorderFeedbackTrigger += 1
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
        .hiddenScrollBackgroundIfThemed(themeManager)
        .navigationTitle(currentName)
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if isSelecting {
                BatchSelectionBottomBar(
                    selectedCount: selectedTrackIDs.count,
                    totalCount: editableDirectIDs.count,
                    onToggleSelectAll: {
                        if allDirectSelected {
                            selectedTrackIDs.removeAll()
                        } else {
                            selectedTrackIDs = Set(editableDirectIDs)
                        }
                    },
                    onEditTags: {
                        let ordered = editableDirectIDs.filter { selectedTrackIDs.contains($0) }
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
        .task(id: ref) {
            isSelecting = false
            selectedTrackIDs.removeAll()
            library.prioritizeCloudFolder(rootID: ref.root, path: ref.path)
        }
        .refreshable { await library.rescan() }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if isSelecting {
                    Button(allDirectSelected ? L10n.tr("Deselect All") : L10n.tr("Select All")) {
                        if allDirectSelected {
                            selectedTrackIDs.removeAll()
                        } else {
                            selectedTrackIDs = Set(editableDirectIDs)
                        }
                    }
                    Button(L10n.tr("Done")) {
                        isSelecting = false
                        selectedTrackIDs.removeAll()
                    }
                    .fontWeight(.semibold)
                } else {
                    if !editableDirectIDs.isEmpty {
                        Button(L10n.tr("Select")) {
                            selectedTrackIDs.removeAll()
                            isSelecting = true
                        }
                    }
                    if !directTracks.isEmpty {
                        TrackSortMenu(selection: currentSortOrder) { order in
                            library.setFolderSortOrder(order, root: ref.root, path: ref.path)
                        }
                    }
                    if !editableAllIDs.isEmpty {
                        Menu(L10n.tr("More"), systemImage: "ellipsis.circle") {
                            Button(L10n.tr("Edit All Songs' Tags…"), systemImage: "tag") {
                                batchEditTarget = BatchTagEditTarget(trackIDs: editableAllIDs)
                            }
                        }
                    }
                    if ref.path.isEmpty, let root, root.kind.isCloud {
                        Button(L10n.tr("Edit Cloud Source…"), systemImage: "link") {
                            editingTarget = FolderTrackingTarget(root: root, path: "", displayName: currentName)
                        }
                        .help(L10n.tr("Edit Cloud Source…"))
                        Button(L10n.tr("Save to Local Folder…"), systemImage: "square.and.arrow.down") {
                            exportingRoot = root
                        }
                        .help(L10n.tr("Save to Local Folder…"))
                    }
                    if let root, root.kind.isLocal, currentTracking != nil {
                        Button(L10n.tr("Update from Cloud"), systemImage: "icloud.and.arrow.down") {
                            updatingTarget = FolderTrackingTarget(root: root, path: ref.path, displayName: currentName)
                        }
                        .help(L10n.tr("Update from Cloud"))
                        Button(L10n.tr("Edit Cloud Source…"), systemImage: "link") {
                            editingTarget = FolderTrackingTarget(root: root, path: ref.path, displayName: currentName)
                        }
                        .help(L10n.tr("Edit Cloud Source…"))
                    }
                    if excludedCount > 0 {
                        Button(L10n.tr("Restore Removed Subfolders (%@)", String(excludedCount)), systemImage: "arrow.uturn.backward") {
                            Task { await library.restoreExcludedFolders(ref.root) }
                        }
                        .help(L10n.tr("Restore Removed Subfolders (%@)", String(excludedCount)))
                    }
                    if canRemoveCurrentFolder {
                        Button(L10n.tr("Remove Folder"), systemImage: "trash", role: .destructive) {
                            confirmRemoveFolder(
                                rootID: ref.root,
                                path: ref.path,
                                name: currentName,
                                root: root,
                                onCompleted: { dismiss() }
                            )
                        }
                        .help(L10n.tr("Remove Folder"))
                    }
                }
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
        .sheet(isPresented: $showExtensionsSheet) {
            NavigationStack {
                ExtensionsView(extensionStore: extensionStore, showDoneButton: true)
            }
            .environment(\.extensionStore, extensionStore)
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
        }
        .sheet(item: $exportingRoot) { root in
            SaveCloudPlaylistSheet(root: root, library: library, downloadManager: downloadManager)
                .environment(library)
                .environment(\.downloadManager, downloadManager)
                .environment(\.locale, languageManager.effectiveLocale)
                .applyTheme(themeManager)
        }
        .onChange(of: library.mergedFolderRedirects[ref.root]) { _, _ in
            redirectIfMerged()
        }
        .onChange(of: exportingRoot == nil) { _, isClosed in
            if isClosed {
                redirectIfMerged()
            }
        }
        .sheet(item: $updatingTarget) { target in
            UpdateTrackedFolderSheet(
                root: target.root,
                subfolderPath: target.path,
                subfolderName: target.displayName,
                library: library,
                downloadManager: downloadManager
            )
            .environment(library)
            .environment(\.downloadManager, downloadManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
        }
        .sheet(item: $editingTarget) { target in
            EditCloudTrackingSheet(
                root: target.root,
                path: target.path,
                folderName: target.displayName,
                library: library,
                preselectedProviderID: target.preselectedProviderID
            )
            .environment(library)
            .environment(\.extensionStore, extensionStore)
            .environment(\.languageManager, languageManager)
            .environment(\.locale, languageManager.effectiveLocale)
            .applyTheme(themeManager)
        }
        #if !targetEnvironment(macCatalyst)
        .sensoryFeedback(.impact(weight: .light), trigger: reorderFeedbackTrigger)
        #endif
    }

    private func redirectIfMerged() {
        guard exportingRoot == nil,
              let redirect = library.mergedFolderRedirects[ref.root] else {
            return
        }
        let destinationPath: String
        if ref.path.isEmpty {
            destinationPath = redirect.path
        } else if redirect.path.isEmpty {
            destinationPath = ref.path
        } else {
            destinationPath = "\(redirect.path)/\(ref.path)"
        }
        let target = FolderRef(root: redirect.root, path: destinationPath)
        var next = NavigationPath()
        next.append(target)
        if !navigation.foldersPath.isEmpty {
            navigation.foldersPath = next
        } else if navigation.sidebarSelection == .folders, !navigation.sidebarDetailPath.isEmpty {
            navigation.sidebarDetailPath = next
        } else {
            dismiss()
        }
    }

    private func switchSourceBannerButton(root: LibraryRoot, currentName: String, suggestedID: String) -> some View {
        Button {
            editingTarget = FolderTrackingTarget(
                root: root,
                path: "",
                displayName: currentName,
                preselectedProviderID: suggestedID
            )
        } label: {
            Text(L10n.tr("Switch Source…"))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.small)
    }

    private var manageExtensionsBannerButton: some View {
        Button {
            showExtensionsSheet = true
        } label: {
            Text(L10n.tr("Manage Extensions"))
                .font(.subheadline.weight(.medium))
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
    }

    private func confirmRemoveFolder(
        rootID: UUID,
        path: String,
        name: String,
        root: LibraryRoot?,
        onCompleted: (() -> Void)? = nil
    ) {
        let songCount = library.tracks(under: rootID, path: path).count
        let normPath = LibraryRoot.normalizeFolderPath(path)
        deletePrompt?.confirmFolder(
            rootID: rootID,
            path: normPath,
            name: name,
            songCount: songCount,
            isCloud: root?.kind.isCloud == true,
            isAppFolderRoot: root?.kind == .appFolder && normPath.isEmpty,
            library: library,
            lyricsStore: lyricsStore,
            onCompleted: onCompleted
        )
    }

    @MainActor
    private func extensionBannerTitle(for availability: CloudExtensionAvailability) -> String {
        switch availability {
        case .available:
            return ""
        case .disabled(let name):
            return L10n.tr("Extension “%@” is disabled.", name)
        case .missing(let providerID):
            return L10n.tr("Extension “%@” is not installed.", providerID)
        }
    }
}

struct SaveCloudPlaylistSheet: View {
    let root: LibraryRoot
    var isWholeRootExport: Bool = true
    private let explicitLibrary: LibraryStore?
    private let explicitDownloadManager: DownloadManager?
    @Environment(LibraryStore.self) private var envLibrary: LibraryStore?
    @Environment(\.downloadManager) private var envDownloadManager
    @Environment(\.themeManager) private var themeManager
    @Environment(\.dismiss) private var dismiss

    @State private var folderName: String
    @State private var parentFolderURL: URL?
    @State private var addToLibrary = true
    @State private var showLocationPicker = false
    @State private var activeJobID: UUID?
    @State private var pickerErrorMessage: String?

    init(
        root: LibraryRoot,
        isWholeRootExport: Bool = true,
        library: LibraryStore? = nil,
        downloadManager: DownloadManager? = nil
    ) {
        self.root = root
        self.isWholeRootExport = isWholeRootExport
        self.explicitLibrary = library
        self.explicitDownloadManager = downloadManager
        _folderName = State(initialValue: CloudExporter.sanitizeFileName(root.name, fallback: "Cloud Playlist"))
    }

    private var library: LibraryStore? { explicitLibrary ?? envLibrary }
    private var downloadManager: DownloadManager { explicitDownloadManager ?? envDownloadManager }

    private var activeJob: DownloadJob? {
        activeJobID.flatMap { id in downloadManager.jobs.first(where: { $0.id == id }) }
    }

    private var isExporting: Bool {
        activeJob?.state == .running
    }

    private var summary: CloudExporter.ExportSummary? {
        activeJob?.exportSummary
    }

    private var errorMessage: String? {
        if let pickerErrorMessage { return pickerErrorMessage }
        switch activeJob?.state {
        case .cancelled:
            return activeJob?.summaryMessage ?? L10n.tr("Export cancelled.")
        case .failed(let message):
            return message
        default:
            return nil
        }
    }

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

                if isExporting, let job = activeJob {
                    Section(L10n.tr("Progress")) {
                        VStack(alignment: .leading, spacing: 8) {
                            let total = max(job.totalTracks, 1)
                            let currentStep = min(job.completedTracks + (job.completedTracks < job.totalTracks ? 1 : 0), total)
                            if let fraction = job.overallFraction {
                                ProgressView(value: fraction)
                            } else {
                                ProgressView(value: Double(job.completedTracks), total: Double(total))
                            }
                            DownloadProgressMetricsView(
                                job: job,
                                stepText: L10n.tr("Saving %@ of %@ songs…", String(currentStep), String(job.totalTracks))
                            )
                        }
                        .padding(.vertical, 4)

                        Button(L10n.tr("Run in Background")) {
                            dismiss()
                        }
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
            .hiddenScrollBackgroundIfThemed(themeManager)
            .background { ThemeBackdropView() }
            .navigationTitle(L10n.tr("Save Cloud Playlist"))
            .navigationBarTitleDisplayMode(.inline)
            .fileImporter(isPresented: $showLocationPicker, allowedContentTypes: [.folder]) { result in
                switch result {
                case .success(let url):
                    parentFolderURL = url
                    pickerErrorMessage = nil
                case .failure(let error):
                    pickerErrorMessage = error.localizedDescription
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if isExporting, let jobID = activeJobID {
                        Button(L10n.tr("Cancel"), role: .cancel) {
                            downloadManager.cancel(jobID: jobID)
                        }
                    } else {
                        Button(summary == nil ? L10n.tr("Cancel") : L10n.tr("Done")) {
                            dismiss()
                        }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isExporting {
                        Button(L10n.tr("Run in Background")) {
                            dismiss()
                        }
                    } else if summary == nil {
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
        pickerErrorMessage = nil
        let job = downloadManager.startPlaylistExport(
            root: root,
            tracks: cloudTracks,
            parentURL: parentURL,
            folderName: folderName,
            addToLibrary: addToLibrary,
            library: library,
            isWholeRootExport: isWholeRootExport
        )
        activeJobID = job.id
    }
}

struct UpdateTrackedFolderSheet: View {
    let root: LibraryRoot
    let subfolderPath: String
    let subfolderName: String?
    private let explicitLibrary: LibraryStore?
    private let explicitDownloadManager: DownloadManager?
    @Environment(LibraryStore.self) private var envLibrary: LibraryStore?
    @Environment(\.downloadManager) private var envDownloadManager
    @Environment(\.languageManager) private var languageManager
    @Environment(\.themeManager) private var themeManager
    @Environment(\.dismiss) private var dismiss

    @State private var activeJobID: UUID?
    @State private var didAttemptStart = false
    @State private var showEditSheet = false

    init(
        root: LibraryRoot,
        subfolderPath: String = "",
        subfolderName: String? = nil,
        library: LibraryStore? = nil,
        downloadManager: DownloadManager? = nil
    ) {
        self.root = root
        self.subfolderPath = LibraryRoot.normalizeFolderPath(subfolderPath)
        self.subfolderName = subfolderName
        self.explicitLibrary = library
        self.explicitDownloadManager = downloadManager
    }

    private var library: LibraryStore? { explicitLibrary ?? envLibrary }
    private var downloadManager: DownloadManager { explicitDownloadManager ?? envDownloadManager }

    private var displayFolderName: String {
        if let subfolderName, !subfolderName.isEmpty {
            return subfolderName
        }
        if !subfolderPath.isEmpty {
            return (subfolderPath as NSString).lastPathComponent
        }
        return L10n.displayRootName(root)
    }

    private var currentTracking: CloudTrackingInfo? {
        if let live = library?.cloudTracking(rootID: root.id, path: subfolderPath) {
            return live
        }
        if subfolderPath.isEmpty, let src = root.sourceURL, !src.isEmpty {
            return CloudTrackingInfo(
                sourceURL: src,
                remoteID: root.remoteID,
                cloudKind: root.effectiveCloudKind ?? .cloudExtension,
                cookie: root.cookie,
                lastSynced: root.lastSynced,
                providerID: root.effectiveProviderID
            )
        }
        return root.normalizedTrackedSubfolders[subfolderPath]
    }

    private var activeJob: DownloadJob? {
        activeJobID.flatMap { id in downloadManager.jobs.first(where: { $0.id == id }) }
    }

    private var isUpdating: Bool {
        activeJob?.state == .running
    }

    private var summary: CloudExporter.CloudUpdateSummary? {
        activeJob?.updateSummary
    }

    private var errorMessage: String? {
        switch activeJob?.state {
        case .cancelled:
            return activeJob?.summaryMessage ?? L10n.tr("Update cancelled.")
        case .failed(let message):
            return message
        default:
            return nil
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                if let sourceURL = currentTracking?.sourceURL, !sourceURL.isEmpty {
                    Section(L10n.tr("Cloud Source")) {
                        HStack(alignment: .center, spacing: 8) {
                            Text(sourceURL)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                            Spacer(minLength: 8)
                            Button {
                                showEditSheet = true
                            } label: {
                                Text(L10n.tr("Edit"))
                                    .lineLimit(1)
                                    .fixedSize(horizontal: true, vertical: false)
                            }
                            .buttonStyle(.borderless)
                            .layoutPriority(1)
                            .disabled(library == nil)
                        }
                    }
                }

                if isUpdating, let job = activeJob {
                    Section(L10n.tr("Syncing")) {
                        VStack(alignment: .leading, spacing: 8) {
                            if job.totalTracks > 0 {
                                let step = min(job.completedTracks + (job.completedTracks < job.totalTracks ? 1 : 0), job.totalTracks)
                                if let fraction = job.overallFraction {
                                    ProgressView(value: fraction)
                                } else {
                                    ProgressView(value: Double(job.completedTracks), total: Double(max(job.totalTracks, 1)))
                                }
                                DownloadProgressMetricsView(
                                    job: job,
                                    stepText: L10n.tr("Checking %@ of %@ songs…", String(step), String(job.totalTracks))
                                )
                            } else {
                                ProgressView()
                                Text(L10n.tr("Fetching latest cloud playlist…"))
                                    .font(.subheadline)
                            }
                        }
                        .padding(.vertical, 4)

                        Button(L10n.tr("Run in Background")) {
                            dismiss()
                        }
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
            .hiddenScrollBackgroundIfThemed(themeManager)
            .background { ThemeBackdropView() }
            .navigationTitle(L10n.tr("Update “%@”", displayFolderName))
            .navigationBarTitleDisplayMode(.inline)
            .task {
                startUpdateIfNeeded()
            }
            .sheet(isPresented: $showEditSheet) {
                let sheet = EditCloudTrackingSheet(
                    root: root,
                    path: subfolderPath,
                    folderName: displayFolderName,
                    library: library,
                    onSaved: {
                        restartUpdateAfterAddressEdit()
                    }
                )
                .environment(\.extensionStore, ExtensionStore.shared)
                .environment(\.languageManager, languageManager)
                .environment(\.locale, languageManager.effectiveLocale)
                .applyTheme(themeManager)
                if let library {
                    sheet.environment(library)
                } else {
                    sheet
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if isUpdating, let jobID = activeJobID {
                        Button(L10n.tr("Cancel"), role: .cancel) {
                            downloadManager.cancel(jobID: jobID)
                        }
                    } else {
                        Button(L10n.tr("Done")) { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isUpdating {
                        Button(L10n.tr("Run in Background")) {
                            dismiss()
                        }
                    }
                }
            }
        }
    }

    private func startUpdateIfNeeded() {
        guard !didAttemptStart else { return }
        didAttemptStart = true
        guard let library else { return }
        activeJobID = downloadManager.startTrackedFolderUpdate(
            root: root,
            subfolderPath: subfolderPath,
            tracking: currentTracking,
            library: library
        ).id
    }

    private func restartUpdateAfterAddressEdit() {
        if let jobID = activeJobID, activeJob?.state == .running {
            downloadManager.cancel(jobID: jobID)
        }
        guard let library else { return }
        activeJobID = downloadManager.startTrackedFolderUpdate(
            root: root,
            subfolderPath: subfolderPath,
            tracking: currentTracking,
            library: library
        ).id
    }
}

struct EditCloudTrackingSheet: View {
    let root: LibraryRoot
    let path: String
    let folderName: String?
    let preselectedProviderID: String?
    var onSaved: (() -> Void)? = nil
    private let explicitLibrary: LibraryStore?
    @Environment(LibraryStore.self) private var envLibrary: LibraryStore?
    @Environment(\.extensionStore) private var extensionStore
    @Environment(\.themeManager) private var themeManager
    @Environment(\.dismiss) private var dismiss

    @State private var urlText: String
    @State private var cookieText: String
    @State private var selectedProviderID: String?
    @State private var selectedStreamQualityID: String?
    @State private var selectedDownloadQualityID: String?
    @State private var isSaving = false
    @State private var errorMessage: String?

    init(
        root: LibraryRoot,
        path: String = "",
        folderName: String? = nil,
        library: LibraryStore? = nil,
        preselectedProviderID: String? = nil,
        onSaved: (() -> Void)? = nil
    ) {
        self.root = root
        let normPath = LibraryRoot.normalizeFolderPath(path)
        self.path = normPath
        self.folderName = folderName
        self.explicitLibrary = library
        self.preselectedProviderID = preselectedProviderID
        self.onSaved = onSaved

        let existingTracking = library?.cloudTracking(rootID: root.id, path: normPath)
            ?? (normPath.isEmpty
                ? root.sourceURL.map {
                    CloudTrackingInfo(
                        sourceURL: $0,
                        remoteID: root.remoteID,
                        cloudKind: root.effectiveCloudKind ?? .cloudExtension,
                        cookie: root.cookie,
                        lastSynced: root.lastSynced,
                        providerID: root.effectiveProviderID,
                        streamQualityID: root.streamQualityID,
                        downloadQualityID: root.downloadQualityID
                    )
                }
                : root.normalizedTrackedSubfolders[normPath])
        _urlText = State(initialValue: existingTracking?.sourceURL ?? "")
        _cookieText = State(initialValue: existingTracking?.cookie ?? "")
        _selectedProviderID = State(initialValue: preselectedProviderID ?? existingTracking?.resolvedProviderID)
        _selectedStreamQualityID = State(initialValue: existingTracking?.streamQualityID)
        _selectedDownloadQualityID = State(initialValue: existingTracking?.downloadQualityID)
    }

    private var library: LibraryStore? { explicitLibrary ?? envLibrary }

    private var displayFolderName: String {
        if let folderName, !folderName.isEmpty {
            return folderName
        }
        if !path.isEmpty {
            return (path as NSString).lastPathComponent
        }
        return L10n.displayRootName(root)
    }

    private var extractedURL: String {
        CloudSourceInput.extract(from: urlText, registry: extensionStore.registry)
    }

    private var compatibleProviders: [any CloudSourceProvider] {
        let all = extensionStore.registry.allProviders().filter(\.supportsCollection)
        let extracted = extractedURL
        guard !extracted.isEmpty else { return all }
        return all.filter { provider in
            if provider.canHandle(rawText: urlText, extractedURL: extracted) != nil {
                return true
            }
            if provider.id == BuiltInProviderID.googleDrive, GoogleDriveClient.isValidDriveID(extracted) {
                return true
            }
            return false
        }
    }

    private var missingSelectedProviderLabel: String? {
        guard let pid = selectedProviderID,
              !pid.isEmpty,
              extensionStore.registry.provider(forID: pid) == nil else {
            return nil
        }
        switch extensionStore.availability(forProviderID: pid) {
        case .available:
            return nil
        case .disabled(let name):
            return L10n.tr("%@ (Disabled)", name)
        case .missing(let providerID):
            return L10n.tr("%@ (Missing)", providerID)
        }
    }

    private var detectedProvider: (any CloudSourceProvider)? {
        guard selectedProviderID == nil, !extractedURL.isEmpty else { return nil }
        guard let match = extensionStore.registry.match(rawText: urlText, extractedURL: extractedURL) else {
            return nil
        }
        return extensionStore.registry.provider(forID: match.providerID)
    }

    private var effectiveProviderForUI: (any CloudSourceProvider)? {
        if let selectedProviderID, !selectedProviderID.isEmpty {
            return extensionStore.registry.provider(forID: selectedProviderID)
        }
        return detectedProvider
    }

    private var availableQualities: [CloudQualityOption] {
        effectiveProviderForUI?.availableQualities ?? []
    }

    private var shouldShowCookieField: Bool {
        effectiveProviderForUI?.supportsCookie ?? false
    }

    private func defaultStreamLabel(for provider: (any CloudSourceProvider)?, qualities: [CloudQualityOption]) -> String {
        let defID = provider?.defaultStreamQualityID
        if let defID, let opt = qualities.first(where: { $0.id == defID }) {
            return L10n.tr("Default (%@)", opt.label)
        }
        return L10n.tr("Default")
    }

    private func defaultDownloadLabel(for provider: (any CloudSourceProvider)?, qualities: [CloudQualityOption]) -> String {
        let defID = provider?.defaultDownloadQualityID
        if let defID, let opt = qualities.first(where: { $0.id == defID }) {
            return L10n.tr("Default (%@)", opt.label)
        }
        return L10n.tr("Default")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(L10n.tr("Folder")) {
                    Text(displayFolderName)
                        .foregroundStyle(.secondary)
                }

                Section(L10n.tr("Source Provider")) {
                    Picker(L10n.tr("Provider"), selection: $selectedProviderID) {
                        Text(L10n.tr("Auto (Match URL)")).tag(nil as String?)
                        if let missingLabel = missingSelectedProviderLabel, let missingID = selectedProviderID {
                            Text(missingLabel).tag(Optional(missingID))
                        }
                        ForEach(compatibleProviders, id: \.id) { provider in
                            Text(provider.displayName).tag(Optional(provider.id))
                        }
                    }
                    if let detectedProvider {
                        LabeledContent(L10n.tr("Detected")) {
                            Text(detectedProvider.displayName)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                Section {
                    TextField(L10n.tr("Cloud URL or ID"), text: $urlText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                } header: {
                    Text(root.kind.isCloud ? L10n.tr("Cloud Source Address") : L10n.tr("Cloud Tracking Address"))
                } footer: {
                    Text(root.kind.isCloud
                         ? L10n.tr("Changing the URL or provider re-syncs this cloud folder while keeping its playlists and queues where possible.")
                         : L10n.tr("Updating the address links this local folder to the new cloud playlist or folder on future updates."))
                }

                if shouldShowCookieField {
                    Section {
                        TextField(L10n.tr("Cookie (optional)"), text: $cookieText, axis: .vertical)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .lineLimit(2...4)
                    } header: {
                        Text(L10n.tr("Authentication"))
                    }
                }

                if !availableQualities.isEmpty {
                    Section(L10n.tr("Audio Quality")) {
                        Picker(L10n.tr("Streaming Quality"), selection: $selectedStreamQualityID) {
                            Text(defaultStreamLabel(for: effectiveProviderForUI, qualities: availableQualities))
                                .tag(nil as String?)
                            ForEach(availableQualities) { quality in
                                Text(quality.label).tag(Optional(quality.id))
                            }
                        }

                        Picker(L10n.tr("Download Quality"), selection: $selectedDownloadQualityID) {
                            Text(defaultDownloadLabel(for: effectiveProviderForUI, qualities: availableQualities))
                                .tag(nil as String?)
                            ForEach(availableQualities) { quality in
                                Text(quality.label).tag(Optional(quality.id))
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
            .hiddenScrollBackgroundIfThemed(themeManager)
            .background { ThemeBackdropView() }
            .navigationTitle(L10n.tr("Edit Cloud Source"))
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                if urlText.isEmpty, let live = library?.cloudTracking(rootID: root.id, path: path) {
                    urlText = live.sourceURL
                    cookieText = live.cookie ?? ""
                    if selectedProviderID == nil {
                        selectedProviderID = preselectedProviderID ?? live.resolvedProviderID
                    }
                    if selectedStreamQualityID == nil {
                        selectedStreamQualityID = live.streamQualityID
                    }
                    if selectedDownloadQualityID == nil {
                        selectedDownloadQualityID = live.downloadQualityID
                    }
                }
            }
            .onChange(of: effectiveProviderForUI?.id) { _, _ in
                let validIDs = Set(availableQualities.map(\.id))
                if let sq = selectedStreamQualityID, !validIDs.contains(sq) {
                    selectedStreamQualityID = nil
                }
                if let dq = selectedDownloadQualityID, !validIDs.contains(dq) {
                    selectedDownloadQualityID = nil
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.tr("Cancel")) { dismiss() }
                        .disabled(isSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isSaving {
                        ProgressView()
                    } else {
                        Button(L10n.tr("Save")) { save() }
                            .disabled(extractedURL.isEmpty || library == nil || missingSelectedProviderLabel != nil)
                    }
                }
            }
        }
    }

    private func save() {
        guard let library else { return }
        errorMessage = nil
        let effectiveCookie = shouldShowCookieField ? cookieText : nil
        if root.kind.isCloud {
            isSaving = true
            let sourceURL = urlText
            let providerID = selectedProviderID
            let streamQ = selectedStreamQualityID
            let downloadQ = selectedDownloadQualityID
            Task {
                do {
                    try await library.rebindCloudRoot(
                        root.id,
                        sourceURL: sourceURL,
                        cookie: effectiveCookie,
                        preferredProviderID: providerID,
                        streamQualityID: streamQ,
                        downloadQualityID: downloadQ
                    )
                    onSaved?()
                    dismiss()
                } catch {
                    errorMessage = error.localizedDescription
                    isSaving = false
                }
            }
        } else {
            do {
                try library.updateCloudTrackingAddress(
                    rootID: root.id,
                    path: path,
                    sourceURL: urlText,
                    cookie: effectiveCookie,
                    preferredProviderID: selectedProviderID,
                    streamQualityID: selectedStreamQualityID,
                    downloadQualityID: selectedDownloadQualityID
                )
                onSaved?()
                dismiss()
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}
