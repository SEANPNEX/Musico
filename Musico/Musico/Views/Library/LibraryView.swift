import SwiftUI

/// Songs, albums, artists and playlists on one tab, switched from the navigation bar.
struct LibraryView: View {
    enum Section: String, CaseIterable, Identifiable {
        case songs = "Songs", albums = "Albums", artists = "Artists", playlists = "Playlists"
        var id: String { rawValue }

        @MainActor
        var localizedTitle: String { L10n.tr(rawValue) }

        var orderIndex: Int {
            switch self {
            case .songs: 0
            case .albums: 1
            case .artists: 2
            case .playlists: 3
            }
        }

        static func isForward(from old: Section, to new: Section) -> Bool {
            new.orderIndex >= old.orderIndex
        }
    }

    @AppStorage("librarySection") private var storedSection: Section = .songs
    @Environment(\.appNavigation) private var navigation
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var transitionForward = true
    @State private var didRestoreStoredSection = false
    @State private var lastObservedSection: Section = .songs

    var body: some View {
        let transition = MusicoMotion.peerSwitchTransition(forward: transitionForward, reduceMotion: reduceMotion)
        ZStack {
            switch navigation.librarySection {
            case .songs:
                SongsView().transition(transition)
            case .albums:
                AlbumsView().transition(transition)
            case .artists:
                ArtistsView().transition(transition)
            case .playlists:
                PlaylistsView().transition(transition)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .animation(MusicoMotion.viewSwitch(reduceMotion: reduceMotion), value: navigation.librarySection)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if !didRestoreStoredSection {
                didRestoreStoredSection = true
                if !navigation.isSearchPresented && navigation.libraryPath.isEmpty {
                    navigation.librarySection = storedSection
                }
            }
            lastObservedSection = navigation.librarySection
        }
        .onChange(of: navigation.librarySection) { oldValue, newValue in
            guard oldValue != newValue else { return }
            if lastObservedSection != newValue {
                transitionForward = Section.isForward(from: lastObservedSection, to: newValue)
                lastObservedSection = newValue
            }
            storedSection = newValue
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker(L10n.tr("Show"), selection: Binding(
                    get: { navigation.librarySection },
                    set: { newValue in
                        guard newValue != navigation.librarySection else { return }
                        transitionForward = Section.isForward(from: navigation.librarySection, to: newValue)
                        lastObservedSection = newValue
                        storedSection = newValue
                        withAnimation(MusicoMotion.viewSwitch(reduceMotion: reduceMotion)) {
                            navigation.librarySection = newValue
                        }
                    }
                )) {
                    ForEach(Section.allCases) { Text($0.localizedTitle).tag($0) }
                }
                .pickerStyle(.segmented)
            }
        }
    }
}
