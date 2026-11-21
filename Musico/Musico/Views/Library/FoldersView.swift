import SwiftUI
import UniformTypeIdentifiers

struct FolderRef: Hashable {
    var root: UUID
    var path: String
}

struct FoldersView: View {
    @Environment(LibraryStore.self) private var library
    @State private var showImporter = false
    @State private var showCloudSheet = false
    /// When set, the next picked folder replaces this unavailable root instead of adding a new one.
    @State private var relocating: UUID?
    @State private var importError: String?
    /// The folder the user asked to remove, awaiting confirmation.
    @State private var removing: LibraryRoot?

    var body: some View {
        List {
            Section {
                ForEach(library.roots) { root in
                    rootRow(root)
                }
                if library.appFolderRemoved {
                    Button("Add Music Folder Back", systemImage: "music.note.house") {
                        Task { await library.restoreAppFolder() }
                    }
                }
            } footer: {
                Text(Self.hint)
            }
            if !library.cloudSyncErrors.isEmpty {
                Section("Cloud Sync Issues") {
                    ForEach(library.roots.filter { library.cloudSyncErrors[$0.id] != nil }) { root in
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundStyle(.orange)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(root.name).font(.subheadline.weight(.medium))
                                Text(library.cloudSyncErrors[root.id] ?? "")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Retry") { Task { await library.rescan() } }
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
                             ? "Reading \(library.scanProgress.done) of \(library.scanProgress.total) songs…"
                             : "Looking for music…")
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Folders")
        .navigationDestination(for: FolderRef.self) { FolderDetailView(ref: $0) }
        .refreshable { await library.rescan() }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Rescan", systemImage: "arrow.clockwise") { Task { await library.rescan() } }
                    .disabled(library.isScanning)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("Add Cloud Folder", systemImage: "icloud.and.arrow.down") {
                    showCloudSheet = true
                }
                Button("Add Folder", systemImage: "folder.badge.plus") {
                    relocating = nil
                    showImporter = true
                }
            }
        }
        .sheet(isPresented: $showCloudSheet) {
            AddCloudFolderSheet()
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
        .confirmationDialog("Remove “\(removing?.name ?? "")” from your library?",
                            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible, presenting: removing) { root in
            Button("Remove from Library", role: .destructive) { library.removeRoot(root.id) }
            Button("Cancel", role: .cancel) {}
        } message: { root in
            if root.kind.isCloud {
                Text("Its songs leave your library, playlists and queues.")
            } else {
                Text("Its songs leave your library, playlists and queues. The files themselves are not deleted.")
            }
        }
        .alert("Couldn’t Add Folder", isPresented: Binding(get: { importError != nil }, set: { if !$0 { importError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(importError ?? "")
        }
    }

    private static var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    private static var hint: String {
        #if targetEnvironment(macCatalyst)
        "Your Mac’s Music folder is included to start with. Add local folders or YouTube/Bilibili cloud playlists, or remove any you don’t want."
        #else
        "Add a folder from Files, link a YouTube or Bilibili playlist, or copy music into the Musico folder in Files › On My \(isPad ? "iPad" : "iPhone")."
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

    private static func sourceLabel(for kind: LibraryRoot.Kind) -> String? {
        switch kind {
        case .cloudYouTube: "YouTube"
        case .cloudBilibili: "Bilibili"
        case .picked, .appFolder: nil
        }
    }

    @ViewBuilder
    private func rootRow(_ root: LibraryRoot) -> some View {
        let available = library.isAvailable(root)
        let count = library.tracks.lazy.filter { $0.rootID == root.id }.count
        let removable = library.isRemovable(root)
        let syncError = library.cloudSyncErrors[root.id]
        NavigationLink(value: FolderRef(root: root.id, path: "")) {
            HStack(spacing: 12) {
                Image(systemName: Self.iconName(for: root))
                    .foregroundStyle(root.kind.isCloud ? Color.red : Color.accentColor)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(root.name)
                            .lineLimit(1)
                        if let label = Self.sourceLabel(for: root.kind) {
                            Text(label)
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.accentColor.opacity(0.14), in: Capsule())
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                    if let syncError {
                        Text("\(count) songs · Sync issue: \(syncError)")
                            .font(.subheadline)
                            .foregroundStyle(.orange)
                            .lineLimit(1)
                    } else {
                        Text(available ? "\(count) songs" : "Unavailable — folder moved or disconnected")
                            .font(.subheadline)
                            .foregroundStyle(available ? Color.secondary : Color.orange)
                    }
                }
                if removable {
                    Spacer()
                    Button("Remove Folder", systemImage: "minus.circle") { removing = root }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                        .help("Remove Folder")
                        .accessibilityLabel("Remove Folder")
                }
            }
        }
        .swipeActions(edge: .trailing) {
            if removable {
                Button("Remove", systemImage: "trash", role: .destructive) { library.removeRoot(root.id) }
            }
        }
        .contextMenu {
            if root.kind == .picked {
                Button("Locate Again…", systemImage: "folder.badge.questionmark") {
                    relocating = root.id
                    showImporter = true
                }
            }
            if !root.normalizedExcludedPaths.isEmpty {
                Button("Restore Removed Subfolders (\(root.normalizedExcludedPaths.count))",
                       systemImage: "arrow.uturn.backward") {
                    Task { await library.restoreExcludedFolders(root.id) }
                }
            }
            if removable {
                Button("Remove Folder", systemImage: "trash", role: .destructive) { removing = root }
            }
            TrackMenuItems(ids: library.tracks(under: root.id, path: "").map(\.id))
        }
    }
}

private struct AddCloudFolderSheet: View {
    @Environment(LibraryStore.self) private var library
    @Environment(\.dismiss) private var dismiss

    @State private var urlText = ""
    @State private var cookieText = ""
    @State private var isLoading = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("YouTube or Bilibili URL or ID", text: $urlText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                } header: {
                    Text("Playlist or Video Link")
                } footer: {
                    Text("Examples: youtube.com/playlist?list=…, music.youtube.com/playlist?list=…, youtu.be/…, bilibili.com/video/BV…, space.bilibili.com/…/favlist?fid=…, b23.tv/…")
                }

                Section {
                    TextField("Cookie (optional)", text: $cookieText, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .lineLimit(2...4)
                } header: {
                    Text("Authentication")
                } footer: {
                    Text("Public playlists work without signing in. Add a Cookie only for private playlists or higher Bilibili audio quality.")
                }

                if let errorMessage {
                    Section {
                        Label(errorMessage, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("Add Cloud Folder")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .disabled(isLoading)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isLoading {
                        ProgressView()
                    } else {
                        Button("Add") { add() }
                            .disabled(urlText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
        }
    }

    private func add() {
        errorMessage = nil
        isLoading = true
        let source = urlText
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
    @Environment(\.dismiss) private var dismiss
    @State private var confirmRemove = false
    @State private var removingSubfolder: FolderRef?

    var body: some View {
        let root = library.root(ref.root)
        let contents = library.folderContents(root: ref.root, path: ref.path)
        let everything = library.tracks(under: ref.root, path: ref.path)
        let isRemovableRoot = ref.path.isEmpty && (root.map { library.isRemovable($0) } ?? false)
        let canRemoveCurrentFolder = !ref.path.isEmpty || isRemovableRoot
        let excludedCount = ref.path.isEmpty ? (root?.normalizedExcludedPaths.count ?? 0) : 0
        let currentName = ref.path.isEmpty ? (root?.name ?? "Folder") : (ref.path as NSString).lastPathComponent

        List {
            if everything.isEmpty {
                ContentUnavailableView(
                    "No Music Here",
                    systemImage: root?.kind.isCloud == true ? "icloud" : "folder",
                    description: Text(root?.kind.isCloud == true
                                      ? "Pull to refresh to sync tracks from this cloud source."
                                      : "Songs you add to this folder appear after a rescan.")
                )
            } else {
                PlayShuffleButtons(ids: everything.map(\.id))
            }
            ForEach(contents.folders, id: \.self) { name in
                let child = FolderRef(root: ref.root, path: ref.path.isEmpty ? name : ref.path + "/" + name)
                NavigationLink(value: child) {
                    HStack(spacing: 12) {
                        Label(name, systemImage: "folder")
                        Spacer()
                        Button("Remove Folder", systemImage: "minus.circle") {
                            removingSubfolder = child
                        }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                        .help("Remove Folder")
                        .accessibilityLabel("Remove Folder")
                    }
                }
                .swipeActions(edge: .trailing) {
                    Button("Remove", systemImage: "trash", role: .destructive) {
                        removingSubfolder = child
                    }
                }
                .contextMenu {
                    Button("Remove Folder", systemImage: "trash", role: .destructive) {
                        removingSubfolder = child
                    }
                    TrackMenuItems(ids: library.tracks(under: child.root, path: child.path).map(\.id))
                }
            }
            TrackRows(tracks: contents.tracks) { $0.isCloud ? $0.artist : $0.fileName }
        }
        .listStyle(.plain)
        .navigationTitle(currentName)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await library.rescan() }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if excludedCount > 0 {
                    Button("Restore Removed Subfolders (\(excludedCount))", systemImage: "arrow.uturn.backward") {
                        Task { await library.restoreExcludedFolders(ref.root) }
                    }
                    .help("Restore Removed Subfolders (\(excludedCount))")
                }
                if canRemoveCurrentFolder {
                    Button("Remove Folder", systemImage: "trash", role: .destructive) {
                        confirmRemove = true
                    }
                    .help("Remove Folder")
                }
            }
        }
        .confirmationDialog("Remove “\(currentName)” from your library?",
                            isPresented: $confirmRemove,
                            titleVisibility: .visible) {
            Button("Remove from Library", role: .destructive) {
                if ref.path.isEmpty {
                    library.removeRoot(ref.root)
                } else {
                    library.removeSubfolder(rootID: ref.root, path: ref.path)
                }
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            if root?.kind.isCloud == true {
                Text("Its songs leave your library, playlists and queues.")
            } else {
                Text("Its songs leave your library, playlists and queues. The files themselves are not deleted.")
            }
        }
        .confirmationDialog("Remove “\((removingSubfolder?.path as NSString?)?.lastPathComponent ?? "")” from your library?",
                            isPresented: Binding(get: { removingSubfolder != nil }, set: { if !$0 { removingSubfolder = nil } }),
                            titleVisibility: .visible,
                            presenting: removingSubfolder) { target in
            Button("Remove from Library", role: .destructive) {
                library.removeSubfolder(rootID: target.root, path: target.path)
            }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            if root?.kind.isCloud == true {
                Text("Its songs leave your library, playlists and queues.")
            } else {
                Text("Its songs leave your library, playlists and queues. The files themselves are not deleted.")
            }
        }
    }
}
