import SwiftUI

/// Owns the long-lived stores for the running app.
@MainActor
final class AppModel {
    let library: LibraryStore
    let queues: QueueStore
    let player: PlayerController
    let lyrics: LyricsStore
    let history: PlayHistoryStore

    init() {
        let support = Persistence.supportDirectory
        #if targetEnvironment(macCatalyst)
        // The Mac has no per-app folder in Files; the built-in root is the user's Music folder.
        let documents = FileManager.default.urls(for: .musicDirectory, in: .userDomainMask)[0].resolvingSymlinksInPath()
        let removable = true
        #else
        let removable = false
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        #endif
        library = LibraryStore(directory: support, documentsURL: documents, reader: MetadataReader(),
                               artwork: ArtworkCache(directory: support.appendingPathComponent("Artwork")),
                               appFolderRemovable: removable)
        queues = QueueStore(directory: support)
        history = PlayHistoryStore(directory: support)
        player = PlayerController(library: library, queues: queues, history: history, engine: AudioEngine(),
                                  settingsURL: support.appendingPathComponent("settings.json"))
        lyrics = LyricsStore(directory: support.appendingPathComponent("Lyrics"))
    }
}

extension EnvironmentValues {
    @Entry var lyricsStore = LyricsStore(directory: Persistence.supportDirectory.appendingPathComponent("Lyrics"))
}

@main
struct MusicoApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model.library)
                .environment(model.queues)
                .environment(model.player)
                .environment(model.history)
                .environment(\.lyricsStore, model.lyrics)
                .task {
                    model.player.restore()
                    await model.library.rescan()
                    // Tracks whose folder was unreachable at launch may be playable now.
                    if model.player.currentTrack == nil { model.player.restore() }
                }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { model.player.persistPosition() }
        }
    }
}
