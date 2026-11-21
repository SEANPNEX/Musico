using System.Text.Json;
using MusicoWindows.Core;
using MusicoWindows.Models;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class PlayQueueTests
{
    private static PlayQueue MakeQueue(IEnumerable<string> ids, int index = 0, RepeatMode repeatMode = RepeatMode.Off)
    {
        var q = new PlayQueue("Q") { RepeatMode = repeatMode };
        q.Replace(ids, index);
        return q;
    }

    [Fact]
    public void NextAdvancesAndStopsAtEnd()
    {
        var q = MakeQueue(["a", "b"]);
        Assert.Equal("a", q.CurrentTrackId);
        Assert.Equal("b", q.Next(auto: true));
        Assert.Null(q.Next(auto: true));
        Assert.Equal("b", q.CurrentTrackId);
    }

    [Fact]
    public void RepeatAllWraps()
    {
        var q = MakeQueue(["a", "b"], index: 1, repeatMode: RepeatMode.All);
        Assert.Equal("a", q.Next(auto: true));
        Assert.Equal("b", q.Previous());
    }

    [Fact]
    public void RepeatOneAutoRepeatsButManualNextAdvances()
    {
        var q = MakeQueue(["a", "b"], repeatMode: RepeatMode.One);
        Assert.Equal("a", q.Next(auto: true));
        Assert.Equal("b", q.Next(auto: false));
    }

    [Fact]
    public void PreviousAtStartStays()
    {
        var q = MakeQueue(["a", "b"]);
        Assert.Equal("a", q.Previous());
        Assert.Equal(0, q.CurrentIndex);
    }

    [Fact]
    public void ReplaceClampsStartIndexAndResetsPosition()
    {
        var q = MakeQueue(["a"]);
        q.PositionSeconds = 42;
        q.Replace(["x", "y"], startAt: 9);
        Assert.Equal("y", q.CurrentTrackId);
        Assert.Equal(0, q.PositionSeconds);
    }

    [Fact]
    public void ShuffleOrderIsPermutationStartingAtCurrent()
    {
        var q = MakeQueue(["a", "b", "c", "d", "e"], index: 2);
        q.SetShuffle(true, new Random(7));
        Assert.Equal(2, q.ShuffleOrder[0]);
        Assert.Equal([0, 1, 2, 3, 4], q.ShuffleOrder.OrderBy(i => i).ToArray());
        Assert.Equal("c", q.CurrentTrackId);
    }

    [Fact]
    public void ShuffleVisitsEveryTrackOnce()
    {
        var q = MakeQueue(["a", "b", "c", "d", "e"], index: 1);
        q.SetShuffle(true, new Random(3));
        var seen = new List<string> { q.CurrentTrackId! };
        while (q.Next(auto: true) is string id)
        {
            seen.Add(id);
        }
        Assert.Equal(5, seen.Count);
        Assert.Equal(new HashSet<string> { "a", "b", "c", "d", "e" }, seen.ToHashSet());
    }

    [Fact]
    public void TurningShuffleOffKeepsCurrentTrackAndRestoresListOrder()
    {
        var q = MakeQueue(["a", "b", "c"], index: 1);
        q.SetShuffle(true, new Random(1));
        q.SetShuffle(false, new Random(1));
        Assert.Equal("b", q.CurrentTrackId);
        Assert.Equal("c", q.Next(auto: true));
    }

    [Fact]
    public void InsertNextPlaysNext()
    {
        var q = MakeQueue(["a", "b"]);
        q.InsertNext(["x", "y"]);
        Assert.Equal(["a", "x", "y", "b"], q.TrackIds);
        Assert.Equal("x", q.Next(auto: true));
        Assert.Equal("y", q.Next(auto: true));
    }

    [Fact]
    public void InsertNextPlaysNextWhenShuffled()
    {
        var q = MakeQueue(["a", "b", "c", "d"], index: 3);
        q.SetShuffle(true, new Random(5));
        q.InsertNext(["x"]);
        Assert.Equal("d", q.CurrentTrackId);
        Assert.Equal([0, 1, 2, 3, 4], q.ShuffleOrder.OrderBy(i => i).ToArray());
        Assert.Equal("x", q.Next(auto: true));
    }

    [Fact]
    public void InsertIntoEmptyQueueMakesFirstCurrent()
    {
        var q = new PlayQueue("Q");
        q.InsertNext(["x", "y"]);
        Assert.Equal("x", q.CurrentTrackId);

        var r = new PlayQueue("R");
        r.Append(["z"]);
        Assert.Equal("z", r.CurrentTrackId);
    }

    [Fact]
    public void AppendWhenShuffledPlaysLast()
    {
        var q = MakeQueue(["a", "b", "c"]);
        q.SetShuffle(true, new Random(9));
        q.Append(["z"]);
        string? last = null;
        while (q.Next(auto: true) is string id)
        {
            last = id;
        }
        Assert.Equal("z", last);
    }

    [Fact]
    public void RemoveBeforeCurrentKeepsCurrentTrack()
    {
        var q = MakeQueue(["a", "b", "c"], index: 2);
        q.PositionSeconds = 30;
        q.RemoveAt(0);
        Assert.Equal("c", q.CurrentTrackId);
        Assert.Equal(30, q.PositionSeconds);
    }

    [Fact]
    public void RemoveCurrentSelectsSuccessor()
    {
        var q = MakeQueue(["a", "b", "c"], index: 1);
        q.PositionSeconds = 30;
        q.RemoveAt(1);
        Assert.Equal("c", q.CurrentTrackId);
        Assert.Equal(0, q.PositionSeconds);
    }

    [Fact]
    public void RemoveCurrentAtEndSelectsNewLast()
    {
        var q = MakeQueue(["a", "b", "c"], index: 2);
        q.RemoveAt(2);
        Assert.Equal("b", q.CurrentTrackId);
    }

    [Fact]
    public void RemoveLastRemainingClearsCurrent()
    {
        var q = MakeQueue(["a"]);
        q.RemoveAt(0);
        Assert.Null(q.CurrentIndex);
        Assert.Null(q.CurrentTrackId);
        Assert.Null(q.Next(auto: true));
    }

    [Fact]
    public void RemoveWhenShuffledKeepsOrderValid()
    {
        var q = MakeQueue(["a", "b", "c", "d"], index: 1);
        q.SetShuffle(true, new Random(11));
        q.RemoveAt(0);
        Assert.Equal("b", q.CurrentTrackId);
        Assert.Equal([0, 1, 2], q.ShuffleOrder.OrderBy(i => i).ToArray());
    }

    [Fact]
    public void MoveKeepsCurrentTrack()
    {
        var q = MakeQueue(["a", "b", "c", "d"], index: 1);
        q.Move(1, 3);
        Assert.Equal(["a", "c", "d", "b"], q.TrackIds);
        Assert.Equal("b", q.CurrentTrackId);
        q.Move(0, 3);
        Assert.Equal("b", q.CurrentTrackId);
        Assert.Equal(2, q.CurrentIndex);
    }

    [Fact]
    public void MoveWithDuplicateIdsKeepsCurrentPosition()
    {
        var q = MakeQueue(["a", "a", "b"], index: 1);
        q.Move(2, 0);
        Assert.Equal(["b", "a", "a"], q.TrackIds);
        Assert.Equal(2, q.CurrentIndex);
    }

    [Fact]
    public void RemoveTracksNotInValidSet()
    {
        var q = MakeQueue(["a", "b", "c", "b"], index: 2);
        q.RemoveTracksNotIn(new HashSet<string> { "a", "c" });
        Assert.Equal(["a", "c"], q.TrackIds);
        Assert.Equal("c", q.CurrentTrackId);
    }

    [Fact]
    public void UpcomingIndicesReflectsCurrentPosition()
    {
        var q = MakeQueue(["a", "b", "c"], index: 1);
        Assert.Equal([2], q.UpcomingIndices);
        q.Jump(0);
        Assert.Equal([1, 2], q.UpcomingIndices);
    }

    [Fact]
    public void EmptyQueueOperationsAreSafe()
    {
        var q = new PlayQueue("Q");
        Assert.Null(q.Next(auto: false));
        Assert.Null(q.Previous());
        q.SetShuffle(true, new Random(1));
        q.Replace([], 0);
        q.RemoveTracksNotIn(new HashSet<string>());
        q.Jump(5);
        Assert.Null(q.CurrentTrackId);
        Assert.Empty(q.UpcomingIndices);
    }

    [Fact]
    public void JsonRoundTripPreservesState()
    {
        var q = MakeQueue(["a", "b", "c"], index: 1, repeatMode: RepeatMode.All);
        q.SetShuffle(true, new Random(2));
        q.PositionSeconds = 12.5;

        string json = JsonSerializer.Serialize(q, Persistence.JsonOptions);
        var decoded = JsonSerializer.Deserialize<PlayQueue>(json, Persistence.JsonOptions);

        Assert.NotNull(decoded);
        Assert.Equal(q.Id, decoded!.Id);
        Assert.Equal(q.Name, decoded.Name);
        Assert.Equal(q.TrackIds, decoded.TrackIds);
        Assert.Equal(q.CurrentIndex, decoded.CurrentIndex);
        Assert.Equal(q.PositionSeconds, decoded.PositionSeconds);
        Assert.Equal(q.Shuffle, decoded.Shuffle);
        Assert.Equal(q.RepeatMode, decoded.RepeatMode);
        Assert.Equal(q.ShuffleOrder, decoded.ShuffleOrder);
    }
}
