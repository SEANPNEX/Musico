import Foundation
import Observation
import SwiftUI

enum AppTab: String, Hashable, CaseIterable {
    case folders, library, queues, stats
}

@MainActor @Observable
final class AppNavigation {
    var selectedTab: AppTab = .folders
    var librarySection: LibraryView.Section = .songs
    var libraryPath = NavigationPath()
    var sidebarSelection: SidebarItem? = .songs
    var sidebarDetailPath = NavigationPath()
    var showPlayer = false
    var searchQuery = ""
    var searchField: SearchField = .keyword
    var isSearchPresented = false
    var searchSource: SongsSearchSource = .local
    let cloudSearch = CloudSearchController()

    nonisolated init() {}

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
