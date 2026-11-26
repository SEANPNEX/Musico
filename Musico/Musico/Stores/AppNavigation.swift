import Foundation
import Observation
import SwiftUI

enum AppTab: String, Hashable, CaseIterable {
    case folders, library, queues, stats
}

@MainActor @Observable
final class AppNavigation {
    static let sidebarSelectionStorageKey = "appNavigation.sidebarSelection"
    static let sidePanelStorageKey = "appNavigation.sidePanel"

    var selectedTab: AppTab = .queues
    var librarySection: LibraryView.Section = .songs
    var libraryPath = NavigationPath()
    var sidebarSelection: SidebarItem? {
        get {
            access(keyPath: \.sidebarSelection)
            return storedSidebarSelection
        }
        set {
            guard storedSidebarSelection != newValue else { return }
            withMutation(keyPath: \.sidebarSelection) {
                storedSidebarSelection = newValue
            }
            persistSidebarSelection()
        }
    }
    var sidebarDetailPath = NavigationPath()
    var sidePanel: SidePanel? {
        get {
            access(keyPath: \.sidePanel)
            return storedSidePanel
        }
        set {
            guard storedSidePanel != newValue else { return }
            withMutation(keyPath: \.sidePanel) {
                storedSidePanel = newValue
            }
            persistSidePanel()
        }
    }
    var showPlayer = false
    var showCommandPalette = false
    var searchQuery = ""
    var searchField: SearchField = .keyword
    var isSearchPresented = false
    var searchSource: SongsSearchSource = .local
    let cloudSearch = CloudSearchController()

    @ObservationIgnored private nonisolated(unsafe) var storedSidebarSelection: SidebarItem?
    @ObservationIgnored private nonisolated(unsafe) var storedSidePanel: SidePanel?
    @ObservationIgnored private nonisolated(unsafe) let defaults: UserDefaults?

    nonisolated init(defaults: UserDefaults? = nil) {
        self.defaults = defaults
        var initialSelection: SidebarItem = .queues
        var initialPanel: SidePanel? = nil
        if let defaults {
            if let rawSelection = defaults.string(forKey: Self.sidebarSelectionStorageKey),
               let restored = Self.sidebarItem(fromStorageKey: rawSelection) {
                initialSelection = restored
            }
            if let rawPanel = defaults.string(forKey: Self.sidePanelStorageKey),
               let restoredPanel = SidePanel(rawValue: rawPanel),
               !(initialSelection == .queues && restoredPanel == .queue) {
                initialPanel = restoredPanel
            }
        }
        self.storedSidebarSelection = initialSelection
        self.storedSidePanel = initialPanel
    }

    func selectSidebar(_ item: SidebarItem) {
        if sidebarSelection == item {
            if !sidebarDetailPath.isEmpty {
                sidebarDetailPath = NavigationPath()
            }
            return
        }
        if sidebarSelection == .songs && searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            isSearchPresented = false
        }
        sidebarDetailPath = NavigationPath()
        sidebarSelection = item
    }

    func toggleSidePanel(_ panel: SidePanel) {
        if panel == .queue && sidebarSelection == .queues {
            sidePanel = nil
            return
        }
        sidePanel = (sidePanel == panel) ? nil : panel
    }

    func navigateToAlbum(_ album: LibraryStore.Album) {
        showPlayer = false
        selectedTab = .library
        librarySection = .albums
        var tabPath = NavigationPath()
        tabPath.append(album)
        libraryPath = tabPath
        sidebarSelection = .albums
        var detailPath = NavigationPath()
        detailPath.append(album)
        sidebarDetailPath = detailPath
    }

    func navigateToArtist(_ artist: String) {
        showPlayer = false
        selectedTab = .library
        librarySection = .artists
        var tabPath = NavigationPath()
        tabPath.append(artist)
        libraryPath = tabPath
        sidebarSelection = .artists
        var detailPath = NavigationPath()
        detailPath.append(artist)
        sidebarDetailPath = detailPath
    }

    func navigateToPlaylist(_ ref: PlaylistRef) {
        showPlayer = false
        selectedTab = .library
        librarySection = .playlists
        var tabPath = NavigationPath()
        tabPath.append(ref)
        libraryPath = tabPath
        switch ref {
        case .favourites:
            sidebarDetailPath = NavigationPath()
            sidebarSelection = .favourites
        case .user(let id):
            sidebarDetailPath = NavigationPath()
            sidebarSelection = .playlist(id)
        }
    }

    private func persistSidebarSelection() {
        guard let defaults,
              let selection = sidebarSelection,
              let key = Self.storageKey(for: selection) else {
            return
        }
        defaults.set(key, forKey: Self.sidebarSelectionStorageKey)
    }

    private func persistSidePanel() {
        guard let defaults else { return }
        if let sidePanel {
            defaults.set(sidePanel.rawValue, forKey: Self.sidePanelStorageKey)
        } else {
            defaults.removeObject(forKey: Self.sidePanelStorageKey)
        }
    }

    private nonisolated static func storageKey(for item: SidebarItem) -> String? {
        switch item {
        case .folders: "folders"
        case .extensions: "extensions"
        case .songs: "songs"
        case .albums: "albums"
        case .artists: "artists"
        case .playlists: "playlists"
        case .favourites: "favourites"
        case .queues: "queues"
        case .stats: "stats"
        case .settings: "settings"
        case .playlist: nil
        }
    }

    private nonisolated static func sidebarItem(fromStorageKey key: String) -> SidebarItem? {
        switch key {
        case "folders": .folders
        case "extensions": .extensions
        case "songs": .songs
        case "albums": .albums
        case "artists": .artists
        case "playlists": .playlists
        case "favourites": .favourites
        case "queues": .queues
        case "stats": .stats
        case "settings": .settings
        default: nil
        }
    }

    func openSearchFocus() {
        selectedTab = .library
        librarySection = .songs
        libraryPath = NavigationPath()
        sidebarDetailPath = NavigationPath()
        sidebarSelection = .songs
        isSearchPresented = true
        showPlayer = false
    }

    func openSearch(query: String, field: SearchField) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        searchSource = .local
        cloudSearch.cancelAndClear()
        searchField = field
        searchQuery = trimmed
        selectedTab = .library
        librarySection = .songs
        libraryPath = NavigationPath()
        sidebarDetailPath = NavigationPath()
        sidebarSelection = .songs
        isSearchPresented = true
        showPlayer = false
    }

    func updateSearchQueryFromUser(_ newValue: String) {
        if newValue != searchQuery {
            searchField = .keyword
            searchQuery = newValue
        }
    }

    func clearSearchFieldTag() {
        searchField = .keyword
    }
}

extension EnvironmentValues {
    @Entry var appNavigation = AppNavigation()
}
