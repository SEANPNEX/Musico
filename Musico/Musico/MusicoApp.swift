import SwiftUI
#if targetEnvironment(macCatalyst)
import UIKit
#endif

/// Owns the long-lived stores for the running app.
@MainActor
final class AppModel {
    let extensions: ExtensionStore
    let library: LibraryStore
    let queues: QueueStore
    let player: PlayerController
    let lyrics: LyricsStore
    let history: PlayHistoryStore
    let navigation = AppNavigation(defaults: .standard)
    let language = LanguageManager.shared
    let theme = ThemeManager.shared
    let downloadManager = DownloadManager()
    #if targetEnvironment(macCatalyst)
    let updateManager: UpdateManager
    #endif

    init() {
        let support = Persistence.supportDirectory
        extensions = ExtensionStore.shared
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
        library.onTracksRemapped = { [weak player, weak queues, weak history] map in
            if let player {
                player.tracksRemapped(map)
            } else {
                queues?.remapTracks(map)
                history?.remapTracks(map)
            }
        }
        lyrics = LyricsStore(directory: support.appendingPathComponent("Lyrics"))
        #if targetEnvironment(macCatalyst)
        updateManager = UpdateManager()
        UIScrollView.appearance().delaysContentTouches = false
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
    #if targetEnvironment(macCatalyst)
    @Environment(\.openWindow) private var openWindow
    #endif

    var body: some Scene {
        mainWindowScene
        #if targetEnvironment(macCatalyst)
        miniPlayerScene
        #endif
    }

    private var mainWindowScene: some Scene {
        WindowGroup(id: "main") {
            RootView()
                .environment(model.library)
                .environment(model.queues)
                .environment(model.player)
                .environment(model.history)
                .environment(\.lyricsStore, model.lyrics)
                .environment(\.appNavigation, model.navigation)
                .environment(\.downloadManager, model.downloadManager)
                .environment(\.extensionStore, model.extensions)
                .environment(\.languageManager, model.language)
                .environment(\.locale, model.language.effectiveLocale)
                .environment(\.toastManager, ToastManager.shared)
                .applyTheme(model.theme)
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
                .task {
                    await model.extensions.checkForUpdatesIfNeeded()
                }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { model.player.persistPosition() }
        }
        #if targetEnvironment(macCatalyst)
        .commands {
            CommandGroup(replacing: .appSettings) {
                Button(L10n.tr("Settings…")) {
                    model.navigation.selectSidebar(.settings)
                    model.navigation.showPlayer = false
                }
                .keyboardShortcut(",", modifiers: .command)
            }

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
                Button(L10n.tr("Quick Jump…")) {
                    model.navigation.showCommandPalette.toggle()
                }
                .keyboardShortcut("k", modifiers: .command)

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
                Button(L10n.tr("Extensions")) { model.navigation.selectSidebar(.extensions) }
                    .keyboardShortcut("8", modifiers: .command)

                Divider()

                Button(L10n.tr("Toggle Lyrics Panel")) {
                    model.navigation.toggleSidePanel(.lyrics)
                }
                .keyboardShortcut("l", modifiers: [.command, .shift])

                Button(L10n.tr("Toggle Queue Panel")) {
                    model.navigation.toggleSidePanel(.queue)
                }
                .keyboardShortcut("q", modifiers: [.command, .shift])

                Button(L10n.tr("Mini Player")) {
                    openWindow(id: "mini-player")
                }
                .keyboardShortcut("n", modifiers: [.command, .shift])
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

                Button(L10n.tr("Seek Backward 10s")) {
                    model.player.seek(by: -10)
                }
                .keyboardShortcut(.leftArrow, modifiers: [.command, .shift])
                .disabled(model.player.currentTrack == nil)

                Button(L10n.tr("Seek Forward 10s")) {
                    model.player.seek(by: 10)
                }
                .keyboardShortcut(.rightArrow, modifiers: [.command, .shift])
                .disabled(model.player.currentTrack == nil)

                Divider()

                Button(L10n.tr("Volume Up")) {
                    let next = min(1.0, model.player.volume + 0.05)
                    model.player.volume = next
                    if model.player.isMuted { model.player.isMuted = false }
                    let pct = Int((model.player.volume * 100).rounded())
                    ToastManager.shared.show(
                        L10n.tr("Volume: %@", "\(pct)%"),
                        systemImage: "speaker.wave.2.fill",
                        duration: 1.5
                    )
                }
                .keyboardShortcut(.upArrow, modifiers: .command)

                Button(L10n.tr("Volume Down")) {
                    let next = max(0.0, model.player.volume - 0.05)
                    model.player.volume = next
                    let pct = Int(((model.player.isMuted ? 0 : model.player.volume) * 100).rounded())
                    ToastManager.shared.show(
                        L10n.tr("Volume: %@", "\(pct)%"),
                        systemImage: next <= 0.001 ? "speaker.slash.fill" : "speaker.wave.1.fill",
                        duration: 1.5
                    )
                }
                .keyboardShortcut(.downArrow, modifiers: .command)

                Button(model.player.isMuted ? L10n.tr("Unmute") : L10n.tr("Mute")) {
                    model.player.toggleMute()
                    ToastManager.shared.show(
                        model.player.isMuted ? L10n.tr("Muted") : L10n.tr("Unmuted"),
                        systemImage: model.player.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill",
                        duration: 1.5
                    )
                }
                .keyboardShortcut("m", modifiers: [.command, .shift])

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

    #if targetEnvironment(macCatalyst)
    private var miniPlayerScene: some Scene {
        WindowGroup(L10n.tr("Mini Player"), id: "mini-player") {
            CompactMiniWindowView()
                .environment(model.library)
                .environment(model.queues)
                .environment(model.player)
                .environment(model.history)
                .environment(\.appNavigation, model.navigation)
                .environment(\.languageManager, model.language)
                .environment(\.locale, model.language.effectiveLocale)
                .environment(\.toastManager, ToastManager.shared)
                .applyTheme(model.theme)
        }
        .defaultSize(width: 360, height: 116)
        .windowResizability(.contentSize)
    }
    #endif
}
