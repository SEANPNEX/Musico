import SwiftUI

enum DeleteItemTarget: Identifiable {
    case track(
        track: Track,
        location: String,
        library: LibraryStore,
        lyricsStore: LyricsStore
    )
    case folder(
        rootID: UUID,
        path: String,
        name: String,
        location: String,
        songCount: Int,
        isCloud: Bool,
        isAppFolderRoot: Bool,
        library: LibraryStore,
        lyricsStore: LyricsStore,
        onCompleted: (() -> Void)?
    )

    var id: String {
        switch self {
        case .track(let track, _, _, _):
            return "track::\(track.id)"
        case .folder(let rootID, let path, _, _, _, _, _, _, _, _):
            return "folder::\(rootID.uuidString)::\(path)"
        }
    }
}

@MainActor @Observable
final class DeleteItemPrompt {
    var target: DeleteItemTarget?

    func confirmTrack(
        _ track: Track,
        library: LibraryStore,
        lyricsStore: LyricsStore
    ) {
        let location = library.displayLocation(for: track)
        target = .track(
            track: track,
            location: location,
            library: library,
            lyricsStore: lyricsStore
        )
    }

    func confirmFolder(
        rootID: UUID,
        path: String,
        name: String,
        songCount: Int,
        isCloud: Bool,
        isAppFolderRoot: Bool,
        library: LibraryStore,
        lyricsStore: LyricsStore,
        onCompleted: (() -> Void)? = nil
    ) {
        let location = library.displayLocation(forRoot: rootID, path: path)
        target = .folder(
            rootID: rootID,
            path: path,
            name: name,
            location: location,
            songCount: songCount,
            isCloud: isCloud,
            isAppFolderRoot: isAppFolderRoot,
            library: library,
            lyricsStore: lyricsStore,
            onCompleted: onCompleted
        )
    }
}

/// Hosts the confirmation dialog for removing items from the library or deleting local files/folders on disk.
struct DeleteConfirmHostModifier: ViewModifier {
    @Environment(\.toastManager) private var toastManager
    @State private var prompt = DeleteItemPrompt()
    @State private var deleteError: String?
    @State private var deleteFeedbackTrigger = 0

    func body(content: Content) -> some View {
        content
            .environment(prompt)
            .confirmationDialog(
                dialogTitle,
                isPresented: Binding(
                    get: { prompt.target != nil },
                    set: { if !$0 { prompt.target = nil } }
                ),
                titleVisibility: .visible,
                presenting: prompt.target
            ) { target in
                switch target {
                case .track(let track, _, let library, let lyricsStore):
                    if track.isCloud || track.isEphemeral {
                        Button(L10n.tr("Remove from Library"), role: .destructive) {
                            library.removeTrackFromLibrary(track.id)
                            deleteFeedbackTrigger += 1
                            toastManager.show(L10n.tr("Removed from library"), systemImage: "minus.circle")
                        }
                    } else {
                        Button(L10n.tr("Remove from Library")) {
                            library.removeTrackFromLibrary(track.id)
                            deleteFeedbackTrigger += 1
                            toastManager.show(L10n.tr("Removed from library"), systemImage: "minus.circle")
                        }
                        #if targetEnvironment(macCatalyst)
                        Button(L10n.tr("Move to Trash"), role: .destructive) {
                            performTrackDiskDelete(track: track, library: library, lyricsStore: lyricsStore)
                        }
                        #else
                        Button(L10n.tr("Delete from Device"), role: .destructive) {
                            performTrackDiskDelete(track: track, library: library, lyricsStore: lyricsStore)
                        }
                        #endif
                    }
                    Button(L10n.tr("Cancel"), role: .cancel) {}

                case .folder(let rootID, let path, _, _, _, let isCloud, let isAppFolderRoot, let library, let lyricsStore, let onCompleted):
                    if isCloud || isAppFolderRoot {
                        Button(L10n.tr("Remove from Library"), role: .destructive) {
                            if path.isEmpty {
                                library.removeRoot(rootID)
                            } else {
                                library.removeSubfolder(rootID: rootID, path: path)
                            }
                            deleteFeedbackTrigger += 1
                            toastManager.show(L10n.tr("Removed from library"), systemImage: "minus.circle")
                            onCompleted?()
                        }
                    } else {
                        Button(L10n.tr("Remove from Library")) {
                            if path.isEmpty {
                                library.removeRoot(rootID)
                            } else {
                                library.removeSubfolder(rootID: rootID, path: path)
                            }
                            deleteFeedbackTrigger += 1
                            toastManager.show(L10n.tr("Removed from library"), systemImage: "minus.circle")
                            onCompleted?()
                        }
                        #if targetEnvironment(macCatalyst)
                        Button(L10n.tr("Move to Trash"), role: .destructive) {
                            performFolderDiskDelete(rootID: rootID, path: path, library: library, lyricsStore: lyricsStore, onCompleted: onCompleted)
                        }
                        #else
                        Button(L10n.tr("Delete from Device"), role: .destructive) {
                            performFolderDiskDelete(rootID: rootID, path: path, library: library, lyricsStore: lyricsStore, onCompleted: onCompleted)
                        }
                        #endif
                    }
                    Button(L10n.tr("Cancel"), role: .cancel) {}
                }
            } message: { target in
                Text(dialogMessage(for: target))
            }
            .alert(
                L10n.tr("Couldn’t Delete"),
                isPresented: Binding(get: { deleteError != nil }, set: { if !$0 { deleteError = nil } })
            ) {
                Button(L10n.tr("OK"), role: .cancel) {}
            } message: {
                Text(deleteError ?? "")
            }
            #if !targetEnvironment(macCatalyst)
            .sensoryFeedback(.warning, trigger: deleteFeedbackTrigger)
            #endif
    }

    private var dialogTitle: String {
        switch prompt.target {
        case .track(let track, _, _, _):
            return L10n.tr("Remove “%@”?", track.title)
        case .folder(_, _, let name, _, _, _, _, _, _, _):
            return L10n.tr("Remove “%@”?", name)
        case nil:
            return ""
        }
    }

    private func dialogMessage(for target: DeleteItemTarget) -> String {
        switch target {
        case .track(let track, let location, _, _):
            let fileName = track.fileName
            if track.isCloud || track.isEphemeral {
                return "\(L10n.tr("File Name")): \(fileName)\n\(L10n.tr("Location")): \(location)\n\n\(L10n.tr("Its song leaves your library, playlists and queues."))"
            }
            return "\(L10n.tr("File Name")): \(fileName)\n\(L10n.tr("Location")): \(location)\n\n\(L10n.tr("Remove from Library hides it from Musico without deleting the file. Deleting removes the file from disk."))"
        case .folder(_, _, let name, let location, let songCount, let isCloud, let isAppFolderRoot, _, _, _):
            let countLine = L10n.songCount(songCount)
            if isCloud || isAppFolderRoot {
                return "\(L10n.tr("Folder")): \(name) (\(countLine))\n\(L10n.tr("Location")): \(location)\n\n\(L10n.tr("Its songs leave your library, playlists and queues. The files themselves are not deleted."))"
            }
            return "\(L10n.tr("Folder")): \(name) (\(countLine))\n\(L10n.tr("Location")): \(location)\n\n\(L10n.tr("Remove from Library keeps the files on disk. Deleting moves or removes the folder and its files from disk."))"
        }
    }

    private func performTrackDiskDelete(track: Track, library: LibraryStore, lyricsStore: LyricsStore) {
        do {
            try library.deleteTrackFile(track.id)
            lyricsStore.removeImported(trackID: track.id)
            NotificationCenter.default.post(name: .musicoLyricsDidChange, object: track.id)
            deleteFeedbackTrigger += 1
            #if targetEnvironment(macCatalyst)
            toastManager.show(L10n.tr("Moved to Trash"), systemImage: "trash")
            #else
            toastManager.show(L10n.tr("Deleted from device"), systemImage: "trash")
            #endif
        } catch {
            deleteError = error.localizedDescription
        }
    }

    private func performFolderDiskDelete(
        rootID: UUID,
        path: String,
        library: LibraryStore,
        lyricsStore: LyricsStore,
        onCompleted: (() -> Void)?
    ) {
        let affectedIDs = library.tracks(under: rootID, path: path).map(\.id)
        do {
            try library.deleteFolderFiles(rootID: rootID, path: path)
            for id in affectedIDs {
                lyricsStore.removeImported(trackID: id)
            }
            deleteFeedbackTrigger += 1
            #if targetEnvironment(macCatalyst)
            toastManager.show(L10n.tr("Moved to Trash"), systemImage: "trash")
            #else
            toastManager.show(L10n.tr("Deleted from device"), systemImage: "trash")
            #endif
            onCompleted?()
        } catch {
            deleteError = error.localizedDescription
        }
    }
}
