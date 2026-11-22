import SwiftUI

/// Owns the long-lived stores for the running app.
@MainActor
final class AppModel {
    let library: LibraryStore
    let queues: QueueStore
    let player: PlayerController
    let lyrics: LyricsStore
    let history: PlayHistoryStore
    let navigation = AppNavigation()
    let language = LanguageManager.shared
    let downloadManager = DownloadManager()
    #if targetEnvironment(macCatalyst)
    let updateManager: UpdateManager
    #endif

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
        #if targetEnvironment(macCatalyst)
        updateManager = UpdateManager()
        #endif
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
                .environment(\.appNavigation, model.navigation)
                .environment(\.downloadManager, model.downloadManager)
                .environment(\.languageManager, model.language)
                .environment(\.locale, model.language.effectiveLocale)
                #if targetEnvironment(macCatalyst)
                .environment(\.updateManager, model.updateManager)
                .task {
                    await model.updateManager.checkForUpdates(manual: false)
                }
                #endif
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
        #if targetEnvironment(macCatalyst)
        .commands {
            CommandGroup(after: .appInfo) {
                Button(L10n.tr("Check for Updates…")) {
                    model.updateManager.presentManualCheck()
                }
                Menu(L10n.tr("Language")) {
                    Picker(L10n.tr("Language"), selection: Binding(
                        get: { model.language.selection },
                        set: { model.language.setLanguage($0) }
                    )) {
                        ForEach(AppLanguage.allCases) { lang in
                            Text(lang.displayName).tag(lang)
                        }
                    }
                }
            }

            CommandMenu(L10n.tr("Library")) {
                Button(L10n.tr("Find Songs")) {
                    model.navigation.openSearchFocus()
                }
                .keyboardShortcut("f", modifiers: .command)

                Button(L10n.tr("Rescan Library")) {
                    Task { await model.library.rescan() }
                }
                .keyboardShortcut("r", modifiers: .command)
                .disabled(model.library.isScanning)
            }

            CommandMenu(L10n.tr("View")) {
                Button(L10n.tr("Folders")) { model.navigation.selectSidebar(.folders) }
                    .keyboardShortcut("1", modifiers: .command)
                Button(L10n.tr("Songs")) { model.navigation.selectSidebar(.songs) }
                    .keyboardShortcut("2", modifiers: .command)
                Button(L10n.tr("Albums")) { model.navigation.selectSidebar(.albums) }
                    .keyboardShortcut("3", modifiers: .command)
                Button(L10n.tr("Artists")) { model.navigation.selectSidebar(.artists) }
                    .keyboardShortcut("4", modifiers: .command)
                Button(L10n.tr("All Playlists")) { model.navigation.selectSidebar(.playlists) }
                    .keyboardShortcut("5", modifiers: .command)
                Button(L10n.tr("Queues")) { model.navigation.selectSidebar(.queues) }
                    .keyboardShortcut("6", modifiers: .command)
                Button(L10n.tr("Stats")) { model.navigation.selectSidebar(.stats) }
                    .keyboardShortcut("7", modifiers: .command)
            }

            CommandMenu(L10n.tr("Playback")) {
                Button(L10n.tr("Play or Pause")) {
                    model.player.togglePlayPause()
                }
                .keyboardShortcut("p", modifiers: .command)
                .disabled(model.player.currentTrack == nil)

                Button(L10n.tr("Previous")) {
                    model.player.previous()
                }
                .keyboardShortcut(.leftArrow, modifiers: .command)
                .disabled(model.player.currentTrack == nil)

                Button(L10n.tr("Next")) {
                    model.player.next()
                }
                .keyboardShortcut(.rightArrow, modifiers: .command)
                .disabled(model.player.currentTrack == nil)

                Divider()

                Button(L10n.tr("Shuffle")) {
                    model.player.toggleShuffle()
                }
                .keyboardShortcut("s", modifiers: [.command, .shift])

                Button(L10n.tr("Repeat")) {
                    model.player.cycleRepeat()
                }
                .keyboardShortcut("r", modifiers: [.command, .shift])

                Divider()

                Button(L10n.tr("Open Now Playing")) {
                    model.navigation.showPlayer.toggle()
                }
                .keyboardShortcut("p", modifiers: [.command, .shift])
            }
        }
        #endif
    }
}
