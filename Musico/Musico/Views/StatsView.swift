import SwiftUI

/// Listening history, laid out like the Library tab: one list at a time, switched from the bar.
struct StatsView: View {
    enum Section: String, CaseIterable, Identifiable {
        case recent = "Recent", counts = "Plays", stale = "Not Lately", never = "Never"
        var id: String { rawValue }

        var emptyTitle: String {
            switch self {
            case .recent: "Nothing Played Yet"
            case .counts: "No Plays Yet"
            case .stale: "Nothing Neglected"
            case .never: "Everything Played"
            }
        }

        var emptyText: String {
            switch self {
            case .recent: "Songs appear here a few seconds after they start playing."
            case .counts: "A song counts as played once you have listened to half of it."
            case .stale: "Songs you have not listened to for \(PlayHistoryStore.staleAfterDays) days appear here."
            case .never: "Every song in your library has been played at least once."
            }
        }
    }

    enum CountOrder: String, CaseIterable, Identifiable {
        case most = "Most Played First", least = "Least Played First"
        var id: String { rawValue }
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
                    Picker("Order", selection: $order) {
                        ForEach(CountOrder.allCases) { Text($0.rawValue).tag($0) }
                    }
                } label: {
                    Label(order.rawValue, systemImage: "arrow.up.arrow.down").font(.subheadline)
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
        .navigationTitle("Stats")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker("Show", selection: $section) {
                    ForEach(Section.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
            }
        }
    }

    private var totals: some View {
        HStack {
            total("\(history.totalPlays)", history.totalPlays == 1 ? "play" : "plays")
            Divider()
            total(listeningTime, "listened")
            Divider()
            total("\(history.neverPlayed(library.tracks).count)", "never played")
        }
        .padding(.vertical, 6)
    }

    private var listeningTime: String {
        let minutes = Int(history.totalSeconds / 60)
        return minutes >= 60 ? "\(minutes / 60) h \(minutes % 60) min" : "\(minutes) min"
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
        let plays = count == 1 ? "1 play" : "\(count) plays"
        let when = history.lastPlayed(for: track.id)?.formatted(.relative(presentation: .named))
        switch section {
        case .recent, .stale: return [when, track.artist].compactMap { $0 }.joined(separator: " · ")
        case .counts: return "\(plays) · \(track.artist)"
        case .never: return track.artist
        }
    }
}
