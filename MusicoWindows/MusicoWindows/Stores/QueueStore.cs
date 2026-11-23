using MusicoWindows.Core;
using MusicoWindows.Models;

namespace MusicoWindows.Stores;

/// <summary>
/// The set of play queues and which one is active. There is always at least one queue.
/// </summary>
public sealed class QueueStore : ObservableStore
{
    private sealed class QueueFile
    {
        public List<PlayQueue> Queues { get; set; } = new();
        public Guid ActiveId { get; set; }
    }

    private readonly string _filePath;
    private List<PlayQueue> _queues;
    private Guid _activeId;

    public QueueStore(string stateDirectory)
    {
        _filePath = Path.Combine(stateDirectory, "queues.json");
        var file = Persistence.Load<QueueFile>(_filePath);
        if (file is { Queues.Count: > 0 })
        {
            _queues = file.Queues;
            _activeId = _queues.Any(q => q.Id == file.ActiveId) ? file.ActiveId : _queues[0].Id;
        }
        else
        {
            var first = new PlayQueue("Queue 1");
            _queues = [first];
            _activeId = first.Id;
        }
    }

    public IReadOnlyList<PlayQueue> Queues => _queues;
    public Guid ActiveId => _activeId;
    public PlayQueue Active => _queues.FirstOrDefault(q => q.Id == _activeId) ?? _queues[0];

    public void Save()
    {
        try
        {
            Persistence.Save(new QueueFile
            {
                Queues = _queues,
                ActiveId = _activeId
            }, _filePath);
        }
        catch
        {
            // Best-effort persistence.
        }
    }

    public void Update(Guid id, Action<PlayQueue> action)
    {
        var queue = _queues.FirstOrDefault(q => q.Id == id);
        if (queue is null) return;
        action(queue);
        Save();
        OnPropertyChanged(nameof(Queues));
        if (id == _activeId)
        {
            OnPropertyChanged(nameof(Active));
        }
    }

    public void UpdateActive(Action<PlayQueue> action) => Update(_activeId, action);

    /// <summary>Records the playback position in memory without writing to disk; flush via Save().</summary>
    public void SetActivePosition(double seconds)
    {
        Active.PositionSeconds = seconds;
    }

    public static string FormatSearchQueueName(SearchField field, string query)
    {
        string trimmed = query.Trim();
        string prefix = field switch
        {
            SearchField.Artist => "Artist",
            SearchField.Album => "Album",
            SearchField.Genre => "Genre",
            _ => "Keyword"
        };
        return $"{prefix}: {trimmed}";
    }

    public (Guid QueueId, bool WasActive) CreateOrReplaceQueue(
        string name,
        IReadOnlyList<string> trackIds,
        int startAt = 0,
        bool activate = false,
        bool? shuffle = null)
    {
        string title = name.Trim();
        if (title.Length == 0)
        {
            title = "Queue";
        }

        var existing = _queues.FirstOrDefault(q => string.Equals(q.Name, title, StringComparison.Ordinal));
        if (existing is not null)
        {
            bool wasActive = existing.Id == _activeId;
            existing.Replace(trackIds, startAt);
            if (shuffle is bool s)
            {
                existing.SetShuffle(s);
            }

            if (activate && _activeId != existing.Id)
            {
                _activeId = existing.Id;
                OnPropertyChanged(nameof(ActiveId));
            }

            Save();
            OnPropertyChanged(nameof(Queues));
            if (_activeId == existing.Id)
            {
                OnPropertyChanged(nameof(Active));
            }
            return (existing.Id, wasActive);
        }

        var queue = new PlayQueue(title);
        queue.Replace(trackIds, startAt);
        if (shuffle is bool shuf)
        {
            queue.SetShuffle(shuf);
        }
        _queues = [.. _queues, queue];

        if (activate)
        {
            _activeId = queue.Id;
            OnPropertyChanged(nameof(ActiveId));
            OnPropertyChanged(nameof(Active));
        }

        Save();
        OnPropertyChanged(nameof(Queues));
        return (queue.Id, false);
    }

    public void SortQueue(Guid id, TrackSortMode mode, LibraryStore library, PlayHistoryStore history)
    {
        Update(id, q => q.Sort(mode, library.GetTrack, history.GetCount));
    }

    public void SortActive(TrackSortMode mode, LibraryStore library, PlayHistoryStore history) =>
        SortQueue(_activeId, mode, library, history);

    public Guid AddQueue(string? name = null)
    {
        string title = name?.Trim() ?? string.Empty;
        if (title.Length == 0)
        {
            var used = new HashSet<string>(_queues.Select(q => q.Name), StringComparer.Ordinal);
            for (int n = 1; ; n++)
            {
                string candidate = $"Queue {n}";
                if (!used.Contains(candidate))
                {
                    title = candidate;
                    break;
                }
            }
        }

        var queue = new PlayQueue(title);
        _queues = [.. _queues, queue];
        Save();
        OnPropertyChanged(nameof(Queues));
        return queue.Id;
    }

    public void Rename(Guid id, string name)
    {
        string title = name.Trim();
        if (title.Length == 0) return;
        Update(id, q => q.Name = title);
    }

    /// <summary>Deletes a queue. The last remaining queue cannot be deleted.</summary>
    public void Delete(Guid id)
    {
        if (_queues.Count <= 1) return;
        int index = _queues.FindIndex(q => q.Id == id);
        if (index < 0) return;

        var updated = new List<PlayQueue>(_queues);
        updated.RemoveAt(index);
        _queues = updated;

        if (_activeId == id)
        {
            _activeId = _queues[Math.Min(index, _queues.Count - 1)].Id;
            OnPropertyChanged(nameof(ActiveId));
            OnPropertyChanged(nameof(Active));
        }

        Save();
        OnPropertyChanged(nameof(Queues));
    }

    public void SetActive(Guid id)
    {
        if (_queues.All(q => q.Id != id)) return;
        if (_activeId == id) return;
        _activeId = id;
        Save();
        OnPropertyChanged(nameof(ActiveId));
        OnPropertyChanged(nameof(Active));
    }

    /// <summary>Drops tracks that left the library from every queue.</summary>
    public void RemoveTracks(ISet<string> ids)
    {
        if (ids.Count == 0) return;
        foreach (var q in _queues)
        {
            var valid = new HashSet<string>(q.TrackIds);
            valid.ExceptWith(ids);
            q.RemoveTracksNotIn(valid);
        }
        Save();
        OnPropertyChanged(nameof(Queues));
        OnPropertyChanged(nameof(Active));
    }

    /// <summary>Updates track IDs across all queues when legacy track paths/IDs migrate.</summary>
    public void MigrateTracks(IReadOnlyDictionary<string, string> idMigrations)
    {
        if (idMigrations.Count == 0) return;
        bool changed = false;
        foreach (var q in _queues)
        {
            if (q.MigrateTrackIds(idMigrations))
            {
                changed = true;
            }
        }
        if (!changed) return;
        Save();
        OnPropertyChanged(nameof(Queues));
        OnPropertyChanged(nameof(Active));
    }
}
