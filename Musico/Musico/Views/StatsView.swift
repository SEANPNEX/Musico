import SwiftUI

/// Listening history, laid out like the Library tab: one list at a time, switched from the bar.
struct StatsView: View {
    enum Section: String, CaseIterable, Identifiable {
        case recent = "Recent", counts = "Plays", stale = "Not Lately", never = "Never"
        var id: String { rawValue }

        @MainActor
        var localizedTitle: String { L10n.tr(rawValue) }

        @MainActor
        var emptyTitle: String {
            switch self {
            case .recent: L10n.tr("Nothing Played Yet")
            case .counts: L10n.tr("No Plays Yet")
            case .stale: L10n.tr("Nothing Neglected")
            case .never: L10n.tr("Everything Played")
            }
        }

        @MainActor
        var emptyText: String {
            switch self {
            case .recent: L10n.tr("Songs appear here a few seconds after they start playing.")
            case .counts: L10n.tr("A song counts as played once you have listened to half of it.")
            case .stale: L10n.tr("Songs you have not listened to for %@ days appear here.", String(PlayHistoryStore.staleAfterDays))
            case .never: L10n.tr("Every song in your library has been played at least once.")
            }
        }
    }

    enum CountOrder: String, CaseIterable, Identifiable {
        case most = "Most Played First", least = "Least Played First"
        var id: String { rawValue }

        @MainActor
        var localizedTitle: String { L10n.tr(rawValue) }
    }

    @Environment(PlayHistoryStore.self) private var history
    @Environment(LibraryStore.self) private var library
    @AppStorage("statsSection") private var section: Section = .recent
    @AppStorage("statsCountOrder") private var order: CountOrder = .most

    private var tracks: [Track] {
        switch section {
        case .recent: history.recentlyPlayed(library.tracks)
        case .counts: order == .most ? history.mostPlayed(library.tracks) : history.leastPlayed(library.tracks)
        case .stale: history.notPlayedLately(library.tracks)
        case .never: history.neverPlayed(library.tracks)
        }
    }

    var body: some View {
        let tracks = self.tracks
        List {
            totals.listRowSeparator(.hidden)
            if section == .counts {
                // In the list rather than the bar, so the switcher above keeps the full width.
                Menu {
                    Picker(L10n.tr("Order"), selection: $order) {
                        ForEach(CountOrder.allCases) { Text($0.localizedTitle).tag($0) }
                    }
                } label: {
                    Label(order.localizedTitle, systemImage: "arrow.up.arrow.down").font(.subheadline)
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
                .listRowSeparator(.hidden)
            }
            if tracks.isEmpty {
                ContentUnavailableView(section.emptyTitle, systemImage: "chart.bar", description: Text(section.emptyText))
                    .listRowSeparator(.hidden)
            } else {
                PlayShuffleButtons(ids: tracks.map(\.id))
                TrackRows(tracks: tracks, subtitle: caption)
            }
        }
        .listStyle(.plain)
        .navigationTitle(L10n.tr("Stats"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker(L10n.tr("Show"), selection: $section) {
                    ForEach(Section.allCases) { Text($0.localizedTitle).tag($0) }
                }
                .pickerStyle(.segmented)
            }
        }
    }

    private var totals: some View {
        HStack {
            total("\(history.totalPlays)", L10n.playUnit(history.totalPlays))
            Divider()
            total(listeningTime, L10n.tr("listened"))
            Divider()
            total("\(history.neverPlayed(library.tracks).count)", L10n.tr("never played"))
        }
        .padding(.vertical, 6)
    }

    private var listeningTime: String {
        L10n.listeningDuration(history.totalSeconds)
    }

    private func total(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title3.weight(.semibold).monospacedDigit()).lineLimit(1).minimumScaleFactor(0.6)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    /// The figure the current list is ordered by, then the artist.
    private func caption(_ track: Track) -> String {
        let count = history.count(for: track.id)
        let plays = L10n.playCount(count)
        let when = history.lastPlayed(for: track.id).map { L10n.formatRelativeDate($0) }
        let artist = L10n.displayArtist(track.artist)
        switch section {
        case .recent, .stale: return [when, artist].compactMap { $0 }.joined(separator: " · ")
        case .counts: return "\(plays) · \(artist)"
        case .never: return artist
        }
    }
}
