using System.Net;
using System.Net.Http;
using System.Text;
using MusicoWindows.Cloud;
using MusicoWindows.Core;
using MusicoWindows.Models;
using MusicoWindows.Stores;
using Xunit;

namespace MusicoWindows.Tests;

public sealed class LibraryStoreTests
{
    private readonly string _stateDir = TestHelpers.MakeTempDir();
    private readonly string _musicDir = TestHelpers.MakeTempDir();
    private readonly StubMetadataReader _reader = new();

    private LibraryStore MakeStore(int batch = 100, bool appFolderRemovable = true) =>
        new(_stateDir, _musicDir, _reader, new ArtworkCache(Path.Combine(_stateDir, "Artwork")), batch, appFolderRemovable);

    [Fact]
    public void AppFolderRootAlwaysPresentInitially()
    {
        var store = MakeStore();
        Assert.Equal([LibraryRoot.AppFolderId], store.Roots.Select(r => r.Id));
        Assert.True(store.IsAvailable(store.Roots[0]));
    }

    [Fact]
    public async Task InitialScanIndexesFiles()
    {
        TestHelpers.TouchFile(_musicDir, "a.mp3");
        TestHelpers.TouchFile(_musicDir, "Rock/b.flac");

        var store = MakeStore();
        await store.RescanAsync();

        Assert.Equal(["a.mp3", "Rock/b.flac"], store.Tracks.Select(t => t.RelativePath).OrderBy(p => p));
        var track = store.Tracks.First(t => t.RelativePath == "Rock/b.flac");
        Assert.Equal(track, store.GetTrack(track.Id));
        Assert.Equal(Path.Combine(_musicDir, "Rock", "b.flac"), store.GetFilePath(track));
        Assert.False(store.IsScanning);
    }

    [Fact]
    public async Task RescanKeepsUnchangedWithoutRereading()
    {
        TestHelpers.TouchFile(_musicDir, "a.mp3");
        var store = MakeStore();
        await store.RescanAsync();
        var before = store.Tracks.ToList();
        Assert.Equal(1, _reader.Calls);

        await store.RescanAsync();
        Assert.Equal(1, _reader.Calls);
        Assert.Equal(before, store.Tracks);
    }

    [Fact]
    public async Task RescanPicksUpNewAndRemovesDeleted()
    {
        TestHelpers.TouchFile(_musicDir, "a.mp3");
        TestHelpers.TouchFile(_musicDir, "b.mp3");
        var store = MakeStore();
        await store.RescanAsync();

        var a = store.Tracks.First(t => t.RelativePath == "a.mp3");
        var playlist = store.CreatePlaylist("P");
        store.AddToPlaylist([a.Id], playlist);
        store.ToggleFavourite(a.Id);

        var removed = new HashSet<string>();
        store.OnTracksRemoved = ids => removed.UnionWith(ids);

        File.Delete(Path.Combine(_musicDir, "a.mp3"));
        TestHelpers.TouchFile(_musicDir, "c.mp3");
        await store.RescanAsync();

        Assert.Equal(["b.mp3", "c.mp3"], store.Tracks.Select(t => t.RelativePath).OrderBy(p => p));
        Assert.Equal(new HashSet<string> { a.Id }, removed);
        Assert.Empty(store.Playlists[0].TrackIds);
        Assert.False(store.IsFavourite(a.Id));
    }

    [Fact]
    public async Task ChangedFileIsRereadAndKeepsDateAdded()
    {
        TestHelpers.TouchFile(_musicDir, "a.mp3", "short");
        var store = MakeStore();
        await store.RescanAsync();
        var added = store.Tracks[0].DateAddedUtc;

        TestHelpers.TouchFile(_musicDir, "a.mp3", "much longer audio content");
        _reader.ByFileName["a.mp3"] = new TrackMetadata(Title: "New Title", DurationSeconds: 9);
        await store.RescanAsync();

        Assert.Equal(["New Title"], store.Tracks.Select(t => t.Title));
        Assert.Equal(added, store.Tracks[0].DateAddedUtc);
    }

    [Fact]
    public async Task FallbacksWhenTagsMissing()
    {
        TestHelpers.TouchFile(_musicDir, "Live Set/01 Opening.mp3");
        TestHelpers.TouchFile(_musicDir, "loose.mp3");
        var store = MakeStore();
        await store.RescanAsync();

        var nested = store.Tracks.First(t => t.RelativePath.StartsWith("Live"));
        Assert.Equal("01 Opening", nested.Title);
        Assert.Equal("Unknown Artist", nested.Artist);
        Assert.Equal("Live Set", nested.Album);
        Assert.Equal("Unknown Album", store.Tracks.First(t => t.RelativePath == "loose.mp3").Album);
    }

    [Fact]
    public async Task FolderContentsAndPrefixIsolation()
    {
        TestHelpers.TouchFile(_musicDir, "top.mp3");
        TestHelpers.TouchFile(_musicDir, "A/one.mp3");
        TestHelpers.TouchFile(_musicDir, "A/Deep/two.mp3");
        TestHelpers.TouchFile(_musicDir, "AB/sibling.mp3");
        TestHelpers.TouchFile(_musicDir, "B/three.mp3");

        var store = MakeStore();
        await store.RescanAsync();

        var rootLevel = store.GetFolderContents(LibraryRoot.AppFolderId, "");
        Assert.Equal(["A", "AB", "B"], rootLevel.Folders);
        Assert.Equal(["top.mp3"], rootLevel.Tracks.Select(t => t.RelativePath));

        var a = store.GetFolderContents(LibraryRoot.AppFolderId, "A");
        Assert.Equal(["Deep"], a.Folders);
        Assert.Equal(["A/one.mp3"], a.Tracks.Select(t => t.RelativePath));

        Assert.Equal(
            ["A/Deep/two.mp3", "A/one.mp3"],
            store.GetTracksUnder(LibraryRoot.AppFolderId, "A").Select(t => t.RelativePath));
    }

    [Fact]
    public async Task AlbumsSortedByDiscAndTrack()
    {
        foreach (string name in (string[])["x.mp3", "y.mp3", "z.mp3", "other.mp3"])
        {
            TestHelpers.TouchFile(_musicDir, name);
        }
        _reader.ByFileName["x.mp3"] = new TrackMetadata(Title: "Third", Artist: "Ar", Album: "Al", TrackNumber: 1, DiscNumber: 2, DurationSeconds: 1);
        _reader.ByFileName["y.mp3"] = new TrackMetadata(Title: "Second", Artist: "Ar", Album: "Al", TrackNumber: 2, DiscNumber: 1, DurationSeconds: 1);
        _reader.ByFileName["z.mp3"] = new TrackMetadata(Title: "First", Artist: "Ar", Album: "Al", TrackNumber: 1, DiscNumber: 1, DurationSeconds: 1);
        _reader.ByFileName["other.mp3"] = new TrackMetadata(Title: "Solo", Artist: "Zed", Album: "Else", DurationSeconds: 1);

        var store = MakeStore();
        await store.RescanAsync();

        Assert.Equal(["Al", "Else"], store.Albums.Select(a => a.Title));
        Assert.Equal(["First", "Second", "Third"], store.Albums[0].TrackIds.Select(id => store.GetTrack(id)!.Title));
        Assert.Equal(["Ar", "Zed"], store.Artists);
    }

    [Fact]
    public async Task SearchIsCaseAndDiacriticInsensitive()
    {
        TestHelpers.TouchFile(_musicDir, "a.mp3");
        TestHelpers.TouchFile(_musicDir, "b.mp3");
        _reader.ByFileName["a.mp3"] = new TrackMetadata(Title: "Café del Mar", Artist: "X", Album: "Y", DurationSeconds: 1);
        _reader.ByFileName["b.mp3"] = new TrackMetadata(Title: "Other", Artist: "Björk", Album: "Y", DurationSeconds: 1);

        var store = MakeStore();
        await store.RescanAsync();

        Assert.Equal(["Café del Mar"], store.Search("cafe").Select(t => t.Title));
        Assert.Equal(["Other"], store.Search("BJORK").Select(t => t.Title));
        Assert.Empty(store.Search("   "));
    }

    [Fact]
    public async Task StatePersistsAcrossInstances()
    {
        TestHelpers.TouchFile(_musicDir, "a.mp3");
        var first = MakeStore();
        await first.RescanAsync();
        string id = first.Tracks[0].Id;
        first.ToggleFavourite(id);
        var pl = first.CreatePlaylist("Mix");
        first.AddToPlaylist([id, id], pl);

        var second = MakeStore();
        Assert.Equal(first.Tracks, second.Tracks);
        Assert.Equal([id], second.Favourites);
        Assert.Equal(["Mix"], second.Playlists.Select(p => p.Name));
        Assert.Equal([id, id], second.Playlists[0].TrackIds);
        Assert.Equal(1, _reader.Calls);
    }

    [Fact]
    public async Task AddPickedRootScansAndRejectsDuplicate()
    {
        string picked = TestHelpers.MakeTempDir();
        TestHelpers.TouchFile(picked, "p.mp3");

        var store = MakeStore();
        await store.AddRootAsync(picked);
        Assert.Equal(2, store.Roots.Count);
        Assert.Equal(["p.mp3"], store.Tracks.Select(t => t.RelativePath));

        await Assert.ThrowsAsync<InvalidOperationException>(() => store.AddRootAsync(picked));
        Assert.Equal(2, store.Roots.Count);
    }

    [Fact]
    public async Task RemovableAppFolderStaysRemovedUntilRestored()
    {
        TestHelpers.TouchFile(_musicDir, "mine.mp3");
        var store = MakeStore(appFolderRemovable: true);
        await store.RescanAsync();

        store.RemoveRoot(LibraryRoot.AppFolderId);
        Assert.Empty(store.Roots);
        Assert.Empty(store.Tracks);
        Assert.True(store.AppFolderRemoved);

        var relaunched = MakeStore(appFolderRemovable: true);
        await relaunched.RescanAsync();
        Assert.True(relaunched.AppFolderRemoved);
        Assert.Empty(relaunched.Tracks);

        await relaunched.RestoreAppFolderAsync();
        Assert.False(relaunched.AppFolderRemoved);
        Assert.Single(relaunched.Tracks);
        Assert.Equal(LibraryRoot.AppFolderId, relaunched.Roots[0].Id);
    }

    [Fact]
    public async Task PickedRootThatSuddenlyLooksEmptyKeepsTracksAndPlaylists()
    {
        string picked = TestHelpers.MakeTempDir();
        TestHelpers.TouchFile(picked, "p.mp3");

        var store = MakeStore();
        await store.AddRootAsync(picked);
        string id = store.Tracks[0].Id;
        var pl = store.CreatePlaylist("P");
        store.AddToPlaylist([id], pl);

        File.Delete(Path.Combine(picked, "p.mp3"));
        await store.RescanAsync();

        Assert.Equal([id], store.Tracks.Select(t => t.Id));
        Assert.Equal([id], store.Playlists[0].TrackIds);
    }

    private sealed class DelegatingStubHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _fn;
        public DelegatingStubHandler(Func<HttpRequestMessage, HttpResponseMessage> fn) => _fn = fn;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(_fn(request));
    }

    [Fact]
    public async Task CloudRootAddSyncResilienceAndRemoveWorks()
    {
        bool failNetwork = false;
        string playlistJson = """
        {
          "header": { "musicDetailHeaderRenderer": { "title": { "runs": [{ "text": "Cloud Mix" }] } } },
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [{
                "tabRenderer": {
                  "content": {
                    "sectionListRenderer": {
                      "contents": [{
                        "musicPlaylistShelfRenderer": {
                          "contents": [
                            {
                              "musicResponsiveListItemRenderer": {
                                "playlistItemData": { "videoId": "cloudvid001" },
                                "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [{ "url": "https://img.test/cover.jpg", "width": 400 }] } } },
                                "flexColumns": [
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Cloud Song 1" }] } } },
                                  { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Cloud Artist" }, { "text": " • " }, { "text": "2:30" }] } } }
                                ]
                              }
                            }
                          ]
                        }
                      }]
                    }
                  }
                }
              }]
            }
          }
        }
        """;

        byte[] fakeArtBytes = [0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4];
        var handler = new DelegatingStubHandler(req =>
        {
            if (req.RequestUri!.AbsoluteUri == "https://img.test/cover.jpg")
            {
                return new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(fakeArtBytes) };
            }
            if (failNetwork)
            {
                throw new HttpRequestException("Offline");
            }
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(playlistJson, Encoding.UTF8, "application/json")
            };
        });

        var yt = new YouTubeClient(handler);
        var store = new LibraryStore(
            _stateDir,
            _musicDir,
            _reader,
            new ArtworkCache(Path.Combine(_stateDir, "Artwork")),
            youtubeClient: yt,
            artworkHandler: handler);

        var root = await store.AddCloudRootAsync("https://music.youtube.com/playlist?list=PLCloudTest1234", proxyUrl: "7890");
        Assert.Equal(RootKind.CloudYouTube, root.Kind);
        Assert.Equal("http://127.0.0.1:7890", root.ProxyUrl);
        Assert.True(store.IsAvailable(root));

        var track = Assert.Single(store.Tracks);
        Assert.True(track.IsCloud);
        Assert.True(store.IsAvailable(track));
        Assert.NotNull(track.ArtworkKey);
        var addedUtc = track.DateAddedUtc;

        var plId = store.CreatePlaylist("My Cloud List");
        store.AddToPlaylist([track.Id], plId);
        store.ToggleFavourite(track.Id);

        // Transient network failure on rescan must keep existing cloud tracks & playlists intact.
        failNetwork = true;
        await store.RescanAsync();
        Assert.Single(store.Tracks);
        Assert.Equal(addedUtc, store.Tracks[0].DateAddedUtc);
        Assert.Single(store.Playlists[0].TrackIds);
        Assert.True(store.IsFavourite(track.Id));

        // Re-instantiate store to verify cloud root persists across restarts.
        var reloaded = new LibraryStore(
            _stateDir,
            _musicDir,
            _reader,
            new ArtworkCache(Path.Combine(_stateDir, "Artwork")),
            youtubeClient: yt,
            artworkHandler: handler);
        Assert.Equal(2, reloaded.Roots.Count);
        Assert.True(reloaded.IsAvailable(reloaded.Roots[1]));

        // RemoveRoot cleans up cloud root & tracks.
        reloaded.RemoveRoot(root.Id);
        Assert.Single(reloaded.Roots);
        Assert.Empty(reloaded.Tracks);
    }
}
