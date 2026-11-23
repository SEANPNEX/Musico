using MusicoWindows.Core;
using MusicoWindows.Models;

namespace MusicoWindows.Stores;

/// <summary>
/// How often and how recently each song was played, for the Stats view.
/// </summary>
public sealed class PlayHistoryStore : ObservableStore
{
    private sealed class HistoryFile
    {
        public Dictionary<string, PlayHistoryEntry> Entries { get; set; } = new();
        public int TotalPlays { get; set; }
        public double TotalSeconds { get; set; }
    }

    /// <summary>Songs last played longer ago than this are listed as "not played lately".</summary>
    public const int StaleAfterDays = 30;

    private readonly string _filePath;
    private readonly Dictionary<string, PlayHistoryEntry> _entries;
    private int _totalPlays;
    private double _totalSeconds;

    public PlayHistoryStore(string stateDirectory)
    {
        _filePath = Path.Combine(stateDirectory, "history.json");
        var file = Persistence.Load<HistoryFile>(_filePath);
        _entries = file?.Entries ?? new Dictionary<string, PlayHistoryEntry>();
        _totalPlays = file?.TotalPlays ?? 0;
        _totalSeconds = file?.TotalSeconds ?? 0;
    }

    public IReadOnlyDictionary<string, PlayHistoryEntry> Entries => _entries;
    public int TotalPlays { get => _totalPlays; private set => SetProperty(ref _totalPlays, value); }
    public double TotalSeconds { get => _totalSeconds; private set => SetProperty(ref _totalSeconds, value); }

    public void Save()
    {
        try
        {
            Persistence.Save(new HistoryFile
            {
                Entries = _entries,
                TotalPlays = _totalPlays,
                TotalSeconds = _totalSeconds
            }, _filePath);
        }
        catch
        {
            // Best-effort persistence.
        }
    }

    public void RecordPlay(string id, DateTimeOffset? now = null)
    {
        var timestamp = now ?? DateTimeOffset.UtcNow;
        int prevCount = _entries.TryGetValue(id, out var existing) ? existing.Count : 0;
        _entries[id] = new PlayHistoryEntry(prevCount + 1, timestamp);
        TotalPlays = _totalPlays + 1;
        OnPropertyChanged(nameof(Entries));
        Save();
    }

    /// <summary>Notes that a song started playing: it becomes "recently played" without gaining a play.</summary>
    public void MarkStarted(string id, DateTimeOffset? now = null)
    {
        var timestamp = now ?? DateTimeOffset.UtcNow;
        int prevCount = _entries.TryGetValue(id, out var existing) ? existing.Count : 0;
        _entries[id] = new PlayHistoryEntry(prevCount, timestamp);
        OnPropertyChanged(nameof(Entries));
        Save();
    }

    /// <summary>Adds listening time in memory; callers flush with Save().</summary>
    public void AddListening(double seconds)
    {
        if (seconds <= 0 || !double.IsFinite(seconds)) return;
        TotalSeconds = _totalSeconds + seconds;
    }

    public void RemoveTracks(ISet<string> ids)
    {
        bool changed = false;
        foreach (string id in ids)
        {
            if (_entries.Remove(id)) changed = true;
        }
        if (!changed) return;
        OnPropertyChanged(nameof(Entries));
        Save();
    }

    public void MigrateTracks(IReadOnlyDictionary<string, string> idMigrations)
    {
        if (idMigrations.Count == 0) return;
        bool changed = false;
        foreach (var kvp in idMigrations)
        {
            if (_entries.Remove(kvp.Key, out var entry))
            {
                if (_entries.TryGetValue(kvp.Value, out var existing))
                {
                    _entries[kvp.Value] = new PlayHistoryEntry(
                        existing.Count + entry.Count,
                        existing.LastPlayedUtc >= entry.LastPlayedUtc ? existing.LastPlayedUtc : entry.LastPlayedUtc);
                }
                else
                {
                    _entries[kvp.Value] = entry;
                }
                changed = true;
            }
        }
        if (!changed) return;
        OnPropertyChanged(nameof(Entries));
        Save();
    }

    public int GetCount(string id) => _entries.TryGetValue(id, out var entry) ? entry.Count : 0;

    public DateTimeOffset? GetLastPlayed(string id) => _entries.TryGetValue(id, out var entry) ? entry.LastPlayedUtc : null;

    private IEnumerable<(Track Track, PlayHistoryEntry Entry)> Started(IEnumerable<Track> tracks)
    {
        foreach (var track in tracks)
        {
            if (_entries.TryGetValue(track.Id, out var entry))
            {
                yield return (track, entry);
            }
        }
    }

    private IEnumerable<(Track Track, PlayHistoryEntry Entry)> Played(IEnumerable<Track> tracks) =>
        Started(tracks).Where(x => x.Entry.Count > 0);

    public IReadOnlyList<Track> RecentlyPlayed(IEnumerable<Track> tracks) =>
        Started(tracks)
            .OrderByDescending(x => x.Entry.LastPlayedUtc)
            .Select(x => x.Track)
            .ToList();

    public IReadOnlyList<Track> MostPlayed(IEnumerable<Track> tracks) =>
        Played(tracks)
            .OrderByDescending(x => x.Entry.Count)
            .ThenByDescending(x => x.Entry.LastPlayedUtc)
            .Select(x => x.Track)
            .ToList();

    public IReadOnlyList<Track> LeastPlayed(IEnumerable<Track> tracks) =>
        Played(tracks)
            .OrderBy(x => x.Entry.Count)
            .ThenBy(x => x.Entry.LastPlayedUtc)
            .Select(x => x.Track)
            .ToList();

    public IReadOnlyList<Track> NeverPlayed(IEnumerable<Track> tracks) =>
        tracks.Where(t => GetCount(t.Id) == 0).ToList();

    public IReadOnlyList<Track> NotPlayedLately(IEnumerable<Track> tracks, DateTimeOffset? now = null)
    {
        var cutoff = (now ?? DateTimeOffset.UtcNow).AddDays(-StaleAfterDays);
        return Started(tracks)
            .Where(x => x.Entry.LastPlayedUtc < cutoff)
            .OrderBy(x => x.Entry.LastPlayedUtc)
            .Select(x => x.Track)
            .ToList();
    }
}
