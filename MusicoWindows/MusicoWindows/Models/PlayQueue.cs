using System.Text.Json.Serialization;

namespace MusicoWindows.Models;

/// <summary>
/// One play queue. Pure queue logic; playback lives in PlayerController.
/// Invariant: when Shuffle is on, ShuffleOrder is a permutation of TrackIds indices; otherwise it is empty.
/// </summary>
public sealed class PlayQueue
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = "Queue 1";

    [JsonInclude]
    public List<string> TrackIds { get; private set; } = new();

    [JsonInclude]
    public int? CurrentIndex { get; private set; }

    public double PositionSeconds { get; set; }

    [JsonInclude]
    public bool Shuffle { get; private set; }

    public RepeatMode RepeatMode { get; set; } = RepeatMode.Off;

    [JsonInclude]
    public List<int> ShuffleOrder { get; private set; } = new();

    public PlayQueue() { }

    public PlayQueue(string name)
    {
        Name = name;
    }

    [JsonIgnore]
    public string? CurrentTrackId =>
        CurrentIndex is int idx && idx >= 0 && idx < TrackIds.Count ? TrackIds[idx] : null;

    [JsonIgnore]
    private IReadOnlyList<int> Order =>
        Shuffle ? ShuffleOrder : Enumerable.Range(0, TrackIds.Count).ToList();

    [JsonIgnore]
    private int? OrderPosition
    {
        get
        {
            if (CurrentIndex is not int idx) return null;
            if (!Shuffle) return idx >= 0 && idx < TrackIds.Count ? idx : null;
            int found = ShuffleOrder.IndexOf(idx);
            return found >= 0 ? found : null;
        }
    }

    [JsonIgnore]
    public IReadOnlyList<int> UpcomingIndices
    {
        get
        {
            if (OrderPosition is not int pos) return Array.Empty<int>();
            var order = Order;
            if (pos + 1 >= order.Count) return Array.Empty<int>();
            var upcoming = new int[order.Count - (pos + 1)];
            for (int i = 0; i < upcoming.Length; i++)
            {
                upcoming[i] = order[pos + 1 + i];
            }
            return upcoming;
        }
    }

    public void Replace(IEnumerable<string> ids, int startAt = 0)
    {
        TrackIds = ids.ToList();
        CurrentIndex = TrackIds.Count == 0 ? null : Math.Clamp(startAt, 0, TrackIds.Count - 1);
        PositionSeconds = 0;
        Reshuffle(Random.Shared);
    }

    /// <summary>
    /// Advances and returns the new current track. <paramref name="auto"/> (track finished naturally) honours RepeatMode.One.
    /// Returns null, leaving the index unchanged, at the end of the queue unless RepeatMode is All.
    /// </summary>
    public string? Next(bool auto)
    {
        if (OrderPosition is not int pos) return null;
        if (auto && RepeatMode == RepeatMode.One)
        {
            PositionSeconds = 0;
            return CurrentTrackId;
        }
        var order = Order;
        if (pos + 1 < order.Count)
        {
            CurrentIndex = order[pos + 1];
        }
        else if (RepeatMode == RepeatMode.All && order.Count > 0)
        {
            CurrentIndex = order[0];
        }
        else
        {
            return null;
        }
        PositionSeconds = 0;
        return CurrentTrackId;
    }

    /// <summary>Steps back; wraps to the end only with RepeatMode.All, otherwise stays on the first track.</summary>
    public string? Previous()
    {
        if (OrderPosition is not int pos) return null;
        var order = Order;
        if (pos > 0)
        {
            CurrentIndex = order[pos - 1];
        }
        else if (RepeatMode == RepeatMode.All && order.Count > 0)
        {
            CurrentIndex = order[^1];
        }
        PositionSeconds = 0;
        return CurrentTrackId;
    }

    public void Jump(int index)
    {
        if (index < 0 || index >= TrackIds.Count) return;
        CurrentIndex = index;
        PositionSeconds = 0;
    }

    public void InsertNext(IEnumerable<string> ids)
    {
        var list = ids as IList<string> ?? ids.ToList();
        if (list.Count == 0) return;

        int insertAt = CurrentIndex.HasValue ? CurrentIndex.Value + 1 : 0;
        int? orderPos = OrderPosition;
        TrackIds.InsertRange(insertAt, list);

        if (Shuffle)
        {
            for (int i = 0; i < ShuffleOrder.Count; i++)
            {
                if (ShuffleOrder[i] >= insertAt) ShuffleOrder[i] += list.Count;
            }
            ShuffleOrder.InsertRange(orderPos.HasValue ? orderPos.Value + 1 : 0, Enumerable.Range(insertAt, list.Count));
        }

        if (!CurrentIndex.HasValue)
        {
            CurrentIndex = 0;
            PositionSeconds = 0;
        }
    }

    public void Append(IEnumerable<string> ids)
    {
        var list = ids as IList<string> ?? ids.ToList();
        if (list.Count == 0) return;

        int start = TrackIds.Count;
        TrackIds.AddRange(list);
        if (Shuffle)
        {
            ShuffleOrder.AddRange(Enumerable.Range(start, list.Count));
        }
        if (!CurrentIndex.HasValue)
        {
            CurrentIndex = start;
            PositionSeconds = 0;
        }
    }

    public void RemoveAt(int index)
    {
        if (index < 0 || index >= TrackIds.Count) return;

        int? successor = null;
        bool removingCurrent = index == CurrentIndex;
        if (removingCurrent && OrderPosition is int pos)
        {
            var order = Order;
            if (pos + 1 < order.Count) successor = order[pos + 1];
            else if (pos > 0) successor = order[pos - 1];
        }

        TrackIds.RemoveAt(index);

        if (Shuffle)
        {
            var updated = new List<int>(Math.Max(0, ShuffleOrder.Count - 1));
            foreach (int i in ShuffleOrder)
            {
                if (i == index) continue;
                updated.Add(i > index ? i - 1 : i);
            }
            ShuffleOrder = updated;
        }

        if (removingCurrent)
        {
            CurrentIndex = successor is int s ? (s > index ? s - 1 : s) : null;
            PositionSeconds = 0;
        }
        else if (CurrentIndex is int current && current > index)
        {
            CurrentIndex = current - 1;
        }
    }

    public void Move(int fromIndex, int toIndex)
    {
        if (fromIndex < 0 || fromIndex >= TrackIds.Count || toIndex < 0 || toIndex >= TrackIds.Count || fromIndex == toIndex)
        {
            return;
        }

        var oldIndices = Enumerable.Range(0, TrackIds.Count).ToList();
        int movedOld = oldIndices[fromIndex];
        oldIndices.RemoveAt(fromIndex);
        oldIndices.Insert(toIndex, movedOld);

        string movedId = TrackIds[fromIndex];
        TrackIds.RemoveAt(fromIndex);
        TrackIds.Insert(toIndex, movedId);

        var newPosition = new int[oldIndices.Count];
        for (int newPos = 0; newPos < oldIndices.Count; newPos++)
        {
            newPosition[oldIndices[newPos]] = newPos;
        }

        if (CurrentIndex is int ci) CurrentIndex = newPosition[ci];
        for (int i = 0; i < ShuffleOrder.Count; i++)
        {
            ShuffleOrder[i] = newPosition[ShuffleOrder[i]];
        }
    }

    public void SetShuffle(bool on, Random? rng = null)
    {
        Shuffle = on;
        Reshuffle(rng ?? Random.Shared);
    }

    /// <summary>Drops every track whose id is not in <paramref name="validIds"/>.</summary>
    public void RemoveTracksNotIn(ISet<string> validIds)
    {
        for (int i = TrackIds.Count - 1; i >= 0; i--)
        {
            if (!validIds.Contains(TrackIds[i]))
            {
                RemoveAt(i);
            }
        }
    }

    public PlayQueue Clone()
    {
        return new PlayQueue(Name)
        {
            Id = Id,
            TrackIds = new List<string>(TrackIds),
            CurrentIndex = CurrentIndex,
            PositionSeconds = PositionSeconds,
            Shuffle = Shuffle,
            RepeatMode = RepeatMode,
            ShuffleOrder = new List<int>(ShuffleOrder)
        };
    }

    private void Reshuffle(Random rng)
    {
        if (!Shuffle)
        {
            ShuffleOrder = new List<int>();
            return;
        }

        var rest = new List<int>(TrackIds.Count);
        for (int i = 0; i < TrackIds.Count; i++)
        {
            if (i != CurrentIndex) rest.Add(i);
        }

        // Fisher-Yates shuffle
        for (int i = rest.Count - 1; i > 0; i--)
        {
            int j = rng.Next(i + 1);
            (rest[i], rest[j]) = (rest[j], rest[i]);
        }

        var result = new List<int>(TrackIds.Count);
        if (CurrentIndex is int ci && ci >= 0 && ci < TrackIds.Count)
        {
            result.Add(ci);
        }
        result.AddRange(rest);
        ShuffleOrder = result;
    }
}
