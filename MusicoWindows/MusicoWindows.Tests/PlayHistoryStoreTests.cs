using MusicoWindows.Models;
using MusicoWindows.Stores;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class PlayHistoryStoreTests
{
    private readonly string _dir = TestHelpers.MakeTempDir();
    private readonly Guid _root = Guid.NewGuid();
    private readonly DateTimeOffset _now = DateTimeOffset.FromUnixTimeSeconds(1_800_000_000);
    private static readonly TimeSpan Day = TimeSpan.FromDays(1);

    private Track[] MakeTracks(params string[] names) =>
        names.Select(n => TestHelpers.MakeTrack($"{n}.mp3", _root, title: n)).ToArray();

    [Fact]
    public void RecordPlayCountsAndRemembersLastTime()
    {
        var store = new PlayHistoryStore(_dir);
        store.recordPlay("x", _now - Day);
        store.RecordPlay("x", _now);

        Assert.Equal(2, store.GetCount("x"));
        Assert.Equal(_now, store.GetLastPlayed("x"));
        Assert.Equal(0, store.GetCount("y"));
        Assert.Null(store.GetLastPlayed("y"));
        Assert.Equal(2, store.TotalPlays);
    }

    [Fact]
    public void StartedSongIsRecentButHasNoPlays()
    {
        var all = MakeTracks("a", "b");
        var store = new PlayHistoryStore(_dir);
        store.RecordPlay(all[0].Id, _now - Day);
        store.MarkStarted(all[1].Id, _now);

        Assert.Equal(0, store.GetCount(all[1].Id));
        Assert.Equal(_now, store.GetLastPlayed(all[1].Id));
        Assert.Equal(["b", "a"], store.RecentlyPlayed(all).Select(t => t.Title));
        Assert.Equal(["a"], store.MostPlayed(all).Select(t => t.Title));
        Assert.Equal(["a"], store.LeastPlayed(all).Select(t => t.Title));
        Assert.Equal(["b"], store.NeverPlayed(all).Select(t => t.Title));
        Assert.Equal(1, store.TotalPlays);

        store.MarkStarted(all[0].Id, _now.AddMinutes(1));
        Assert.Equal(1, store.GetCount(all[0].Id));
        Assert.Equal(_now.AddMinutes(1), new PlayHistoryStore(_dir).GetLastPlayed(all[0].Id));
    }

    [Fact]
    public void HistoryAndListeningTimeSurviveReload()
    {
        var store = new PlayHistoryStore(_dir);
        store.RecordPlay("x", _now);
        store.AddListening(90);
        store.Save();

        var reloaded = new PlayHistoryStore(_dir);
        Assert.Equal(1, reloaded.GetCount("x"));
        Assert.Equal(1, reloaded.TotalPlays);
        Assert.Equal(90, reloaded.TotalSeconds, precision: 3);
    }

    [Fact]
    public void ListsReturnExpectedOrders()
    {
        var all = MakeTracks("a", "b", "c", "d");
        var (a, b, c, d) = (all[0], all[1], all[2], all[3]);
        var store = new PlayHistoryStore(_dir);

        store.RecordPlay(a.Id, _now - TimeSpan.FromDays(40));
        store.RecordPlay(b.Id, _now - TimeSpan.FromDays(2));
        store.RecordPlay(b.Id, _now - Day);
        store.RecordPlay(b.Id, _now - Day + TimeSpan.FromMinutes(1));
        store.RecordPlay(c.Id, _now - TimeSpan.FromDays(60));
        store.RecordPlay(c.Id, _now - TimeSpan.FromDays(50));

        Assert.Equal(["b", "a", "c"], store.RecentlyPlayed(all).Select(t => t.Title));
        Assert.Equal(["b", "c", "a"], store.MostPlayed(all).Select(t => t.Title));
        Assert.Equal(["a", "c", "b"], store.LeastPlayed(all).Select(t => t.Title));
        Assert.Equal([d], store.NeverPlayed(all));
        Assert.Equal(["c", "a"], store.NotPlayedLately(all, _now).Select(t => t.Title));
    }

    [Fact]
    public void EqualCountsOrderByMostRecentPlay()
    {
        var all = MakeTracks("a", "b");
        var store = new PlayHistoryStore(_dir);
        store.RecordPlay(all[0].Id, _now - Day);
        store.RecordPlay(all[1].Id, _now);

        Assert.Equal(["b", "a"], store.MostPlayed(all).Select(t => t.Title));
        Assert.Equal(["a", "b"], store.LeastPlayed(all).Select(t => t.Title));
    }

    [Fact]
    public void RemovedSongsLoseHistoryButTotalsStay()
    {
        var store = new PlayHistoryStore(_dir);
        store.RecordPlay("x", _now);
        store.RecordPlay("y", _now);
        store.RemoveTracks(new HashSet<string> { "x" });

        Assert.Equal(0, store.GetCount("x"));
        Assert.Equal(1, store.GetCount("y"));
        Assert.Equal(2, store.TotalPlays);
        Assert.Equal(0, new PlayHistoryStore(_dir).GetCount("x"));
    }

    [Fact]
    public void TopArtistsAlbumsAndExploredFractionAggregateCorrectly()
    {
        var t1 = TestHelpers.MakeTrack("1.mp3", _root, title: "Song 1", artist: "Artist A", album: "Album One") with { ArtworkKey = "art1" };
        var t2 = TestHelpers.MakeTrack("2.mp3", _root, title: "Song 2", artist: "Artist A", album: "Album Two") with { ArtworkKey = "art2" };
        var t3 = TestHelpers.MakeTrack("3.mp3", _root, title: "Song 3", artist: "Artist B", album: "Album Three") with { ArtworkKey = "art3" };
        var t4 = TestHelpers.MakeTrack("4.mp3", _root, title: "Song 4", artist: "Artist C", album: "Album Four");
        var ephemeral = TestHelpers.MakeTrack("5.mp3", _root, title: "Cloud", artist: "Artist A", album: "Album One") with
        {
            Id = Track.MakeEphemeralId("ext.demo", "remote-1"),
            IsEphemeral = true
        };

        Track[] all = [t1, t2, t3, t4, ephemeral];
        var store = new PlayHistoryStore(_dir);

        // Empty history
        Assert.Empty(store.GetTopArtists(all));
        Assert.Empty(store.GetTopAlbums(all));
        Assert.Equal(0.0, store.GetPlayedLibraryFraction(all), precision: 4);
        Assert.Equal(0.0, store.GetPlayedLibraryFraction(Array.Empty<Track>()), precision: 4);

        // Record plays: Artist A has 3 plays across Album One (2) and Album Two (1); Artist B has 4 plays on Album Three
        store.RecordPlay(t1.Id, _now - TimeSpan.FromMinutes(30));
        store.RecordPlay(t1.Id, _now - TimeSpan.FromMinutes(20));
        store.RecordPlay(t2.Id, _now - TimeSpan.FromMinutes(10));
        store.RecordPlay(t3.Id, _now - TimeSpan.FromMinutes(5));
        store.RecordPlay(t3.Id, _now - TimeSpan.FromMinutes(4));
        store.RecordPlay(t3.Id, _now - TimeSpan.FromMinutes(3));
        store.RecordPlay(t3.Id, _now - TimeSpan.FromMinutes(2));

        var topArtists = store.GetTopArtists(all, limit: 5);
        Assert.Equal(2, topArtists.Count);
        Assert.Equal("Artist B", topArtists[0].Artist);
        Assert.Equal(4, topArtists[0].PlayCount);
        Assert.Equal(1, topArtists[0].TrackCount);
        Assert.Equal("art3", topArtists[0].ArtworkKey);

        Assert.Equal("Artist A", topArtists[1].Artist);
        Assert.Equal(3, topArtists[1].PlayCount);
        Assert.Equal(2, topArtists[1].TrackCount);
        Assert.Equal("art1", topArtists[1].ArtworkKey);

        // Limit restricts results
        Assert.Single(store.GetTopArtists(all, limit: 1));

        var topAlbums = store.GetTopAlbums(all, limit: 5);
        Assert.Equal(3, topAlbums.Count);
        Assert.Equal("Album Three", topAlbums[0].Album);
        Assert.Equal(4, topAlbums[0].PlayCount);
        Assert.Equal("Album One", topAlbums[1].Album);
        Assert.Equal(2, topAlbums[1].PlayCount);
        Assert.Equal("Album Two", topAlbums[2].Album);
        Assert.Equal(1, topAlbums[2].PlayCount);

        // 3 of 4 non-ephemeral tracks played -> 0.75
        Assert.Equal(0.75, store.GetPlayedLibraryFraction(all), precision: 4);
    }
}

file static class PlayHistoryTestExtensions
{
    public static void recordPlay(this PlayHistoryStore store, string id, DateTimeOffset date) =>
        store.RecordPlay(id, date);
}
