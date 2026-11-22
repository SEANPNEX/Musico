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
    var showPlayer = false
    var searchQuery = ""
    var searchField: SearchField = .keyword
    var isSearchPresented = false

    nonisolated init() {}

    func openSearch(query: String, field: SearchField) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        searchField = field
        searchQuery = trimmed
        selectedTab = .library
        librarySection = .songs
        libraryPath = NavigationPath()
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
