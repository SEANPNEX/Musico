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
    @Environment(\.themeManager) private var themeManager
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
            if history.totalPlays > 0 {
                insightsCard
                    .listRowSeparator(.hidden)
            }
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
                PlayShuffleButtons(ids: tracks.map(\.id), queueName: "Stats")
                TrackRows(tracks: tracks, queueName: "Stats", subtitle: caption)
            }
        }
        .listStyle(.plain)
        .hiddenScrollBackgroundIfThemed(themeManager)
        .navigationTitle(L10n.tr("Stats"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(for: String.self) { ArtistDetailView(artist: $0) }
        .navigationDestination(for: LibraryStore.Album.self) { AlbumDetailView(album: $0) }
        .animation(.easeInOut(duration: 0.18), value: section)
        .animation(.easeInOut(duration: 0.18), value: order)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker(L10n.tr("Show"), selection: $section) {
                    ForEach(Section.allCases) { Text($0.localizedTitle).tag($0) }
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 340)
            }
        }
    }

    private var insightsCard: some View {
        let eligible = library.tracks.filter { !$0.isEphemeral && !PlayHistoryStore.isEphemeralTrackID($0.id) }
        let playedCount = eligible.filter { history.count(for: $0.id) > 0 }.count
        let fraction = history.playedLibraryFraction(from: library.tracks)
        let topArtists = history.topArtists(from: library.tracks, limit: 5)
        let topAlbums = history.topAlbums(from: library.tracks, limit: 5)
        let maxArtistPlays = max(topArtists.first?.playCount ?? 1, 1)
        let maxAlbumPlays = max(topAlbums.first?.playCount ?? 1, 1)

        return VStack(alignment: .leading, spacing: 16) {
            Text(L10n.tr("Insights"))
                .font(.headline)

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(L10n.tr("Library Explored"))
                        .font(.subheadline.weight(.medium))
                    Spacer()
                    Text("\(Int((fraction * 100).rounded()))% · \(playedCount) / \(eligible.count)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(Color.primary.opacity(0.10))
                        Capsule()
                            .fill(themeManager.accentColor)
                            .frame(width: proxy.size.width * min(max(fraction, 0), 1))
                    }
                }
                .frame(height: 7)
            }

            if !topArtists.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Text(L10n.tr("Top Artists"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)

                    ForEach(topArtists, id: \.artist) { stat in
                        let ratio = Double(stat.playCount) / Double(maxArtistPlays)
                        NavigationLink(value: stat.artist) {
                            insightBarRow(
                                title: L10n.displayArtist(stat.artist),
                                subtitle: nil,
                                playCount: stat.playCount,
                                ratio: ratio
                            ) {
                                ArtistAvatarView(artist: stat.artist, artworkKey: stat.artworkKey, size: 32)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            if !topAlbums.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Text(L10n.tr("Top Albums"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)

                    ForEach(topAlbums, id: \.album) { stat in
                        let ratio = Double(stat.playCount) / Double(maxAlbumPlays)
                        let matchedAlbum = library.albums.first {
                            $0.title == stat.album && $0.artist == stat.artist
                        }
                        let row = insightBarRow(
                            title: L10n.displayAlbum(stat.album),
                            subtitle: L10n.displayArtist(stat.artist),
                            playCount: stat.playCount,
                            ratio: ratio
                        ) {
                            ArtworkView(artworkKey: stat.artworkKey, size: 32, cornerRadius: 6)
                        }
                        if let matchedAlbum {
                            NavigationLink(value: matchedAlbum) {
                                row
                            }
                            .buttonStyle(.plain)
                        } else {
                            row
                        }
                    }
                }
            }
        }
        .padding(14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.75)
                .allowsHitTesting(false)
        }
        .padding(.vertical, 4)
    }

    private func insightBarRow<Thumb: View>(
        title: String,
        subtitle: String?,
        playCount: Int,
        ratio: Double,
        @ViewBuilder thumbnail: () -> Thumb
    ) -> some View {
        HStack(spacing: 10) {
            thumbnail()
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(title)
                        .font(.subheadline.weight(.medium))
                        .lineLimit(1)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    Text(L10n.playCount(playCount))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(Color.primary.opacity(0.08))
                        Capsule()
                            .fill(themeManager.accentColor.opacity(0.85))
                            .frame(width: max(6, proxy.size.width * min(max(ratio, 0), 1)))
                    }
                }
                .frame(height: 5)
            }
        }
    }

    private var totals: some View {
        HStack {
            total("\(history.totalPlays)", L10n.playUnit(history.totalPlays))
            Divider()
            StatsListeningTimeCell()
            Divider()
            total("\(history.neverPlayed(library.tracks).count)", L10n.tr("never played"))
        }
        .padding(.vertical, 6)
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

private struct StatsListeningTimeCell: View {
    @Environment(PlayHistoryStore.self) private var history

    var body: some View {
        VStack(spacing: 2) {
            Text(L10n.listeningDuration(history.totalSeconds))
                .font(.title3.weight(.semibold).monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(L10n.tr("listened"))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}
