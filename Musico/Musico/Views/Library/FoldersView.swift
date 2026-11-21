import SwiftUI
import UniformTypeIdentifiers

struct FolderRef: Hashable {
    var root: UUID
    var path: String
}

struct FoldersView: View {
    @Environment(LibraryStore.self) private var library
    @State private var showImporter = false
    /// When set, the next picked folder replaces this unavailable root instead of adding a new one.
    @State private var relocating: UUID?
    @State private var importError: String?
    /// The folder the user asked to remove, awaiting confirmation (Mac).
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
            ToolbarItem(placement: .topBarTrailing) {
                Button("Add Folder", systemImage: "folder.badge.plus") {
                    relocating = nil
                    showImporter = true
                }
            }
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
        } message: { _ in
            Text("Its songs leave your library, playlists and queues. The files themselves are not deleted.")
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
        "Your Mac’s Music folder is included to start with. Add any other folder that holds music, or remove one you don’t want."
        #else
        "Add a folder from Files, or copy music into the Musico folder in Files › On My \(isPad ? "iPad" : "iPhone")."
        #endif
    }

    private static var appFolderIcon: String {
        #if targetEnvironment(macCatalyst)
        "music.note.house"
        #else
        isPad ? "ipad" : "iphone"
        #endif
    }

    @ViewBuilder
    private func rootRow(_ root: LibraryRoot) -> some View {
        let available = library.isAvailable(root)
        let count = library.tracks.lazy.filter { $0.rootID == root.id }.count
        let removable = root.kind == .picked || library.appFolderRemovable
        NavigationLink(value: FolderRef(root: root.id, path: "")) {
            HStack(spacing: 12) {
                Image(systemName: root.kind == .appFolder ? Self.appFolderIcon : "folder.fill")
                    .foregroundStyle(Color.accentColor)
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text(root.name)
                    Text(available ? "\(count) songs" : "Unavailable — folder moved or disconnected")
                        .font(.subheadline)
                        .foregroundStyle(available ? Color.secondary : Color.orange)
                }
                #if targetEnvironment(macCatalyst)
                // The Mac has no swipe gesture to discover, so removal gets a visible button.
                if removable {
                    Spacer()
                    Button("Remove", systemImage: "minus.circle") { removing = root }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                        .help("Remove from Library")
                }
                #endif
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
            if removable {
                Button("Remove from Library", systemImage: "trash", role: .destructive) { library.removeRoot(root.id) }
            }
            TrackMenuItems(ids: library.tracks(under: root.id, path: "").map(\.id))
        }
    }
}

struct FolderDetailView: View {
    let ref: FolderRef
    @Environment(LibraryStore.self) private var library

    var body: some View {
        let contents = library.folderContents(root: ref.root, path: ref.path)
        let everything = library.tracks(under: ref.root, path: ref.path)
        List {
            if everything.isEmpty {
                ContentUnavailableView("No Music Here", systemImage: "folder",
                                       description: Text("Songs you add to this folder appear after a rescan."))
            } else {
                PlayShuffleButtons(ids: everything.map(\.id))
            }
            ForEach(contents.folders, id: \.self) { name in
                let child = FolderRef(root: ref.root, path: ref.path.isEmpty ? name : ref.path + "/" + name)
                NavigationLink(value: child) {
                    Label(name, systemImage: "folder")
                }
                .contextMenu { TrackMenuItems(ids: library.tracks(under: child.root, path: child.path).map(\.id)) }
            }
            TrackRows(tracks: contents.tracks) { $0.fileName }
        }
        .listStyle(.plain)
        .navigationTitle(ref.path.isEmpty ? (library.root(ref.root)?.name ?? "Folder") : (ref.path as NSString).lastPathComponent)
        .navigationBarTitleDisplayMode(.inline)
        .refreshable { await library.rescan() }
    }
}
