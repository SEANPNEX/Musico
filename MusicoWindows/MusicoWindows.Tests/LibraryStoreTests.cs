using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
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
        // Valid 1x1 RGB PNG so Windows WIC BitmapDecoder accepts it in CI as well as macOS.
        byte[] fakeArtBytes =
        [
            0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
            0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0x1F, 0xEA, 0x02,
            0x00, 0x03, 0xEF, 0x01, 0x99, 0xC3, 0x49, 0xF5, 0x60, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
            0x44, 0xAE, 0x42, 0x60, 0x82
        ];
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
            if (req.RequestUri!.AbsoluteUri.Contains("/drive/folders/gdCloudTest1", StringComparison.Ordinal))
            {
                string html = """
                <html><head><title>Cloud Test - Google Drive</title></head><body>
                <script>window['_DRIVE_ivd'] = '[["gd_file_1",["gdCloudTest1"],"Cloud Song.mp3","audio/mpeg"]]';</script>
                </body></html>
                """;
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(html, Encoding.UTF8, "text/html")
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var gd = new GoogleDriveClient(handler);
        var registry = new MusicoWindows.Extensions.CloudProviderRegistry(gd);
        var store = new LibraryStore(
            _stateDir,
            _musicDir,
            _reader,
            new ArtworkCache(Path.Combine(_stateDir, "Artwork")),
            artworkHandler: handler,
            googleDriveClient: gd,
            providerRegistry: registry);

        var root = await store.AddCloudRootAsync("https://drive.google.com/drive/folders/gdCloudTest1", proxyUrl: "7890");
        Assert.Equal(RootKind.CloudGoogleDrive, root.Kind);
        Assert.Equal("http://127.0.0.1:7890", root.ProxyUrl);
        Assert.True(store.IsAvailable(root));

        var track = Assert.Single(store.Tracks);
        Assert.True(track.IsCloud);
        Assert.True(store.IsAvailable(track));
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
            artworkHandler: handler,
            googleDriveClient: gd,
            providerRegistry: registry);
        Assert.Equal(2, reloaded.Roots.Count);
        Assert.True(reloaded.IsAvailable(reloaded.Roots[1]));

        // RemoveRoot cleans up cloud root & tracks.
        reloaded.RemoveRoot(root.Id);
        Assert.Single(reloaded.Roots);
        Assert.Empty(reloaded.Tracks);
    }

    [Fact]
    public async Task RemoveSubfolderExcludesOnlyTargetedTreeAndPersistsAcrossRescanAndReload()
    {
        string picked = TestHelpers.MakeTempDir();
        TestHelpers.TouchFile(picked, "A/one.mp3");
        TestHelpers.TouchFile(picked, "A/Deep/two.mp3");
        TestHelpers.TouchFile(picked, "AB/sibling.mp3");
        TestHelpers.TouchFile(picked, "root.mp3");

        var store = MakeStore();
        await store.AddRootAsync(picked);
        var pickedRoot = store.Roots.First(r => r.Kind == RootKind.Picked);

        var aOne = store.Tracks.First(t => t.RelativePath == "A/one.mp3");
        var aDeep = store.Tracks.First(t => t.RelativePath == "A/Deep/two.mp3");
        var ab = store.Tracks.First(t => t.RelativePath == "AB/sibling.mp3");
        var plId = store.CreatePlaylist("Sub Mix");
        store.AddToPlaylist([aOne.Id, aDeep.Id, ab.Id], plId);
        store.ToggleFavourite(aOne.Id);
        store.ToggleFavourite(ab.Id);

        var removedCallbacks = new HashSet<string>();
        store.OnTracksRemoved = ids => removedCallbacks.UnionWith(ids);

        store.RemoveSubfolder(pickedRoot.Id, "A");

        // Disk files remain intact.
        Assert.True(File.Exists(Path.Combine(picked, "A", "one.mp3")));
        Assert.True(File.Exists(Path.Combine(picked, "A", "Deep", "two.mp3")));

        Assert.Equal(["AB/sibling.mp3", "root.mp3"], store.Tracks.Select(t => t.RelativePath).OrderBy(x => x));
        Assert.Equal(new HashSet<string>([aOne.Id, aDeep.Id]), removedCallbacks);
        Assert.Equal([ab.Id], store.Playlists[0].TrackIds);
        Assert.False(store.IsFavourite(aOne.Id));
        Assert.True(store.IsFavourite(ab.Id));

        // Rescan & fresh store reload keep "A" excluded.
        await store.RescanAsync();
        Assert.Equal(["AB/sibling.mp3", "root.mp3"], store.Tracks.Select(t => t.RelativePath).OrderBy(x => x));

        var reloaded = MakeStore();
        await reloaded.RescanAsync();
        Assert.Equal(["AB/sibling.mp3", "root.mp3"], reloaded.Tracks.Select(t => t.RelativePath).OrderBy(x => x));
        Assert.Equal(["A"], reloaded.GetRoot(pickedRoot.Id)?.ExcludedPaths);

        // Restoring excluded subfolders brings A's tracks back on rescan.
        await reloaded.RestoreExcludedFoldersAsync(pickedRoot.Id);
        Assert.Equal(
            ["A/Deep/two.mp3", "A/one.mp3", "AB/sibling.mp3", "root.mp3"],
            reloaded.Tracks.Select(t => t.RelativePath).OrderBy(x => x));
        Assert.Null(reloaded.GetRoot(pickedRoot.Id)?.ExcludedPaths);
    }

    [Fact]
    public async Task RemovingAllSubfoldersAndParentPruningAndLegacyDecode()
    {
        string picked = TestHelpers.MakeTempDir();
        TestHelpers.TouchFile(picked, "Group/Sub/one.mp3");
        TestHelpers.TouchFile(picked, "Group/two.mp3");

        var store = MakeStore();
        await store.AddRootAsync(picked);
        var pickedRoot = store.Roots.First(r => r.Kind == RootKind.Picked);

        store.RemoveSubfolder(pickedRoot.Id, "Group/Sub");
        Assert.Equal(["Group/two.mp3"], store.Tracks.Select(t => t.RelativePath));
        Assert.Equal(["Group/Sub"], store.GetRoot(pickedRoot.Id)?.ExcludedPaths);

        // Excluding parent prunes deeper child exclusion and removes remaining track cleanly.
        store.RemoveSubfolder(pickedRoot.Id, "Group");
        Assert.Equal(["Group"], store.GetRoot(pickedRoot.Id)?.ExcludedPaths);
        Assert.Empty(store.Tracks);

        await store.RescanAsync();
        Assert.Empty(store.Tracks);

        const string legacyJson = """{"Id":"00000000-0000-0000-0000-000000000001","Name":"Old","Kind":0,"Path":"C:\\Music"}""";
        var decoded = JsonSerializer.Deserialize<LibraryRoot>(legacyJson);
        Assert.NotNull(decoded);
        Assert.Null(decoded.ExcludedPaths);
        Assert.False(decoded.IsExcluded("Any/song.mp3"));
    }

    [Fact]
    public async Task AddCloudRootAndResyncSupportGoogleDriveWithProxyAndExtensions()
    {
        bool failNetwork = false;
        bool includeSecondTrack = false;

        var handler = new DelegatingStubHandler(req =>
        {
            if (failNetwork)
            {
                throw new HttpRequestException("Offline");
            }

            string url = req.RequestUri!.AbsoluteUri;
            if (url.Contains("/drive/folders/gdRoot123", StringComparison.Ordinal))
            {
                Assert.True(req.Headers.TryGetValues("Cookie", out var cookies));
                Assert.Contains("NID=test", cookies);

                string rows = includeSecondTrack
                    ? """["gd_f1",["gdRoot123"],"Alpha.flac","audio/flac"],["gd_f2",["gdRoot123"],"Beta.mp3","audio/mpeg"]"""
                    : """["gd_f1",["gdRoot123"],"Alpha.flac","audio/flac"]""";
                string html = $"<html><head><title>Drive OST - Google Drive</title></head><body><script>window['_DRIVE_ivd'] = '[[{rows}]]';</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(html, Encoding.UTF8, "text/html")
                };
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var gdrive = new GoogleDriveClient(handler);
        var store = new LibraryStore(
            _stateDir,
            _musicDir,
            _reader,
            new ArtworkCache(Path.Combine(_stateDir, "Artwork")),
            googleDriveClient: gdrive,
            artworkHandler: handler);

        var root = await store.AddCloudRootAsync(
            "Shared OST https://drive.google.com/drive/folders/gdRoot123?usp=sharing】",
            cookie: "NID=test",
            proxyUrl: "7890");

        Assert.Equal(RootKind.CloudGoogleDrive, root.Kind);
        Assert.Equal("Drive OST", root.Name);
        Assert.Equal("http://127.0.0.1:7890", root.ProxyUrl);
        Assert.Equal("NID=test", root.Cookie);
        Assert.True(store.IsAvailable(root));

        var track = Assert.Single(store.Tracks);
        Assert.True(track.IsCloud);
        Assert.Equal(CloudSources.GoogleDrive, track.CloudSource);
        Assert.Equal("Google Drive", track.AlbumArtist);
        Assert.Equal("Drive OST", track.Album);
        Assert.Equal("Alpha", track.Title);
        Assert.EndsWith(".flac", track.RelativePath);

        // Transient failure keeps existing Google Drive tracks intact.
        failNetwork = true;
        await store.RescanAsync();
        Assert.Single(store.Tracks);

        // Recovery + new remote track picked up on rescan.
        failNetwork = false;
        includeSecondTrack = true;
        await store.RescanAsync();
        Assert.Equal(2, store.Tracks.Count);
        Assert.Contains(store.Tracks, t => t.Title == "Beta" && t.RelativePath.EndsWith(".mp3", StringComparison.Ordinal));
    }

    [Fact]
    public async Task RemoveTrackFromLibraryExcludesTrackWithoutDeletingDiskFile()
    {
        TestHelpers.TouchFile(_musicDir, "keep.mp3");
        TestHelpers.TouchFile(_musicDir, "hide.mp3");

        var store = MakeStore();
        await store.RescanAsync();
        var hide = store.Tracks.First(t => t.RelativePath == "hide.mp3");
        var plId = store.CreatePlaylist("Mix");
        store.AddToPlaylist([hide.Id], plId);
        store.ToggleFavourite(hide.Id);

        var removedCallbacks = new HashSet<string>();
        store.OnTracksRemoved = ids => removedCallbacks.UnionWith(ids);

        store.RemoveTrackFromLibrary(hide.Id);

        Assert.True(File.Exists(Path.Combine(_musicDir, "hide.mp3")));
        Assert.Equal(["keep.mp3"], store.Tracks.Select(t => t.RelativePath));
        Assert.Equal(new HashSet<string>([hide.Id]), removedCallbacks);
        Assert.Empty(store.Playlists[0].TrackIds);
        Assert.False(store.IsFavourite(hide.Id));
        Assert.Contains("hide.mp3", store.GetRoot(LibraryRoot.AppFolderId)!.ExcludedPaths!);

        // Rescan keeps track excluded; restoring excluded paths brings it back.
        await store.RescanAsync();
        Assert.Equal(["keep.mp3"], store.Tracks.Select(t => t.RelativePath));

        await store.RestoreExcludedFoldersAsync(LibraryRoot.AppFolderId);
        Assert.Equal(["hide.mp3", "keep.mp3"], store.Tracks.Select(t => t.RelativePath).OrderBy(x => x));
    }

    [Fact]
    public async Task DeleteTrackFileUnloadsBeforeDeletingAndLeavesLibraryIntactOnFailure()
    {
        TestHelpers.TouchFile(_musicDir, "a.mp3");
        TestHelpers.TouchFile(_musicDir, "b.mp3");

        var store = MakeStore();
        await store.RescanAsync();
        var trackA = store.Tracks.First(t => t.RelativePath == "a.mp3");
        var trackB = store.Tracks.First(t => t.RelativePath == "b.mp3");

        var beforeDeleteCalls = new List<string>();
        var removedCalls = new List<string>();
        store.OnBeforeDeletingTracks = ids =>
        {
            // File must still exist when OnBeforeDeletingTracks runs (unloading NAudio lock first).
            Assert.True(File.Exists(Path.Combine(_musicDir, "a.mp3")));
            beforeDeleteCalls.AddRange(ids);
        };
        store.OnTracksRemoved = ids => removedCalls.AddRange(ids);

        store.DeleteTrackFile(trackA.Id);

        Assert.Equal([trackA.Id], beforeDeleteCalls);
        Assert.Equal([trackA.Id], removedCalls);
        Assert.False(File.Exists(Path.Combine(_musicDir, "a.mp3")));
        Assert.Equal(["b.mp3"], store.Tracks.Select(t => t.RelativePath));

        // If disk deletion fails (file already missing), DeleteTrackFile throws and leaves library intact.
        store.OnBeforeDeletingTracks = null;
        File.Delete(Path.Combine(_musicDir, "b.mp3"));
        Assert.Throws<FileNotFoundException>(() => store.DeleteTrackFile(trackB.Id));
        Assert.Single(store.Tracks);
        Assert.Equal(trackB.Id, store.Tracks[0].Id);
    }

    [Fact]
    public async Task DeleteFolderFilesDeletesSubfolderAndPickedRootWhileGuardingAppFolderRoot()
    {
        TestHelpers.TouchFile(_musicDir, "Sub/song1.mp3");
        TestHelpers.TouchFile(_musicDir, "keep.mp3");

        string picked = TestHelpers.MakeTempDir();
        TestHelpers.TouchFile(picked, "picked1.mp3");

        var store = MakeStore();
        await store.RescanAsync();
        await store.AddRootAsync(picked);
        var pickedRoot = store.Roots.First(r => r.Kind == RootKind.Picked);

        var beforeDelete = new HashSet<string>();
        store.OnBeforeDeletingTracks = ids => beforeDelete.UnionWith(ids);

        // AppFolder root disk deletion must always be blocked.
        Assert.Throws<InvalidOperationException>(() => store.DeleteFolderFiles(LibraryRoot.AppFolderId, string.Empty));
        Assert.True(Directory.Exists(_musicDir));
        Assert.NotNull(store.GetRoot(LibraryRoot.AppFolderId));

        // AppFolder subfolder deletion is allowed.
        var subTrack = store.Tracks.First(t => t.RelativePath == "Sub/song1.mp3");
        store.DeleteFolderFiles(LibraryRoot.AppFolderId, "Sub");
        Assert.False(Directory.Exists(Path.Combine(_musicDir, "Sub")));
        Assert.Contains(subTrack.Id, beforeDelete);
        Assert.DoesNotContain(store.Tracks, t => t.Id == subTrack.Id);
        Assert.True(File.Exists(Path.Combine(_musicDir, "keep.mp3")));

        // Picked root deletion removes folder on disk and root from library.
        var pickedTrack = store.Tracks.First(t => t.RootId == pickedRoot.Id);
        store.DeleteFolderFiles(pickedRoot.Id, string.Empty);
        Assert.False(Directory.Exists(picked));
        Assert.Contains(pickedTrack.Id, beforeDelete);
        Assert.Null(store.GetRoot(pickedRoot.Id));
        Assert.Equal(["keep.mp3"], store.Tracks.Select(t => t.RelativePath));
    }

    [Fact]
    public async Task EnrichCachedGoogleDriveTrackAndMergeDoesNotCarryPlaceholderOverride()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        string cacheDir = TestHelpers.MakeTempDir();
        string exportDir = TestHelpers.MakeTempDir();

        var handler = new DelegatingStubHandler(req =>
        {
            string uri = req.RequestUri!.AbsoluteUri;
            if (uri.Contains("/drive/folders/gdEnrich1", StringComparison.Ordinal))
            {
                const string rows = """["gd_e1",["gdEnrich1"],"Sunset.mp3","audio/mpeg"]""";
                string html = $"<html><head><title>Drive Folder - Google Drive</title></head><body><script>window['_DRIVE_ivd'] = '[[{rows}]]';</script></body></html>";
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(html, Encoding.UTF8, "text/html")
                };
            }
            return new HttpResponseMessage(HttpStatusCode.NotFound);
        });

        var artwork = new ArtworkCache(Path.Combine(stateDir, "Artwork"));
        var cloudCache = new CloudAudioCache(cacheDir, handler: handler);
        var gdrive = new GoogleDriveClient(handler);
        var store = new LibraryStore(
            stateDir,
            musicDir,
            new TagLibMetadataReader(),
            artwork,
            cloudCache: cloudCache,
            googleDriveClient: gdrive,
            artworkHandler: handler);

        var cloudRoot = await store.AddCloudRootAsync("https://drive.google.com/drive/folders/gdEnrich1");
        var initial = Assert.Single(store.Tracks);
        Assert.Equal("Google Drive", initial.Artist);
        Assert.Null(initial.ArtworkKey);

        // Seed cached audio file with real embedded ID3 tags and album art.
        Directory.CreateDirectory(cacheDir);
        string cachedMp3 = Path.Combine(cacheDir, CloudAudioCache.BuildCacheFileStem(initial.Id) + ".mp3");
        WriteTaggedMp3(cachedMp3, "Sunset Boulevard", "Real Drive Artist", "Real Drive Album", "Dream Pop", ValidPngBytes);

        // Rescan enriches cached Google Drive tracks from the downloaded cache file.
        await store.RescanAsync();
        var enriched = Assert.Single(store.Tracks);
        Assert.Equal("Sunset Boulevard", enriched.Title);
        Assert.Equal("Real Drive Artist", enriched.Artist);
        Assert.Equal("Real Drive Album", enriched.Album);
        Assert.Equal("Dream Pop", enriched.Genre);
        Assert.False(string.IsNullOrEmpty(enriched.ArtworkKey));

        // Even if a stale placeholder override existed before cloud-to-local merge, local track never inherits "Google Drive".
        string exportedMp3 = Path.Combine(exportDir, "Sunset.mp3");
        File.Copy(cachedMp3, exportedMp3);
        CloudExporter.SaveManifest(exportDir, new CloudFolderManifest(
            SourceUrl: cloudRoot.SourceUrl!,
            RemoteId: cloudRoot.RemoteId,
            CloudKind: RootKind.CloudGoogleDrive.ToString(),
            LastSyncedUtc: DateTimeOffset.UtcNow,
            Tracks: new Dictionary<string, string>(StringComparer.Ordinal) { ["gd_e1"] = "Sunset.mp3" }));

        await store.UpdateTrackMetadataAsync(
            enriched.Id,
            title: "Sunset Boulevard",
            artist: "Google Drive",
            album: "Unknown Album",
            genre: null);
        await store.RegisterExportedCloudFolderAsync(exportDir, cloudRoot, mergeCloudRoot: true);

        var localTrack = Assert.Single(store.Tracks);
        Assert.False(localTrack.IsCloud);
        Assert.Equal("Real Drive Artist", localTrack.Artist);
        Assert.Equal("Real Drive Album", localTrack.Album);
    }

    [Fact]
    public async Task BatchUpdateTrackMetadataAsyncUpdatesArtistAndAlbumPreservingTitleGenreAndCoverArt()
    {
        string stateDir = TestHelpers.MakeTempDir();
        string musicDir = TestHelpers.MakeTempDir();
        var artwork = new ArtworkCache(Path.Combine(stateDir, "Artwork"));
        var reader = new TagLibMetadataReader();

        string p1 = Path.Combine(musicDir, "song1.mp3");
        string p2 = Path.Combine(musicDir, "song2.mp3");
        string p3 = Path.Combine(musicDir, "song3.mp3");
        WriteTaggedMp3(p1, "Song One", "Artist A", "Album One", "Jazz", ValidPngBytes);
        WriteTaggedMp3(p2, "Song Two", "Artist B", "Album Two", "Lo-Fi", ValidPngBytes);
        WriteTaggedMp3(p3, "Song Three", "Keep Artist", "Keep Album", "Classical", ValidPngBytes);

        var store = new LibraryStore(stateDir, musicDir, reader, artwork);
        await store.RescanAsync();

        var t1 = store.Tracks.First(t => t.Title == "Song One");
        var t2 = store.Tracks.First(t => t.Title == "Song Two");
        var t3 = store.Tracks.First(t => t.Title == "Song Three");
        string? t1OrigArt = t1.ArtworkKey;

        var updatedIds = new List<string>();
        store.TrackUpdated += tr => updatedIds.Add(tr.Id);

        // Batch edit Artist only (album == null leaves each track's existing album intact; ephemeral skipped).
        int countArtist = await store.BatchUpdateTrackMetadataAsync(
            [t1.Id, t2.Id, Track.MakeEphemeralId("ext.test", "ep-1")],
            artist: "Unified Band",
            album: null);
        Assert.Equal(2, countArtist);
        Assert.Equal([t1.Id, t2.Id], updatedIds);

        var t1After = store.GetTrack(t1.Id)!;
        var t2After = store.GetTrack(t2.Id)!;
        var t3After = store.GetTrack(t3.Id)!;

        Assert.Equal("Unified Band", t1After.Artist);
        Assert.Equal("Unified Band", t1After.AlbumArtist);
        Assert.Equal("Album One", t1After.Album);
        Assert.Equal("Song One", t1After.Title);
        Assert.Equal("Jazz", t1After.Genre);
        Assert.Equal(t1OrigArt, t1After.ArtworkKey);

        Assert.Equal("Unified Band", t2After.Artist);
        Assert.Equal("Album Two", t2After.Album);
        Assert.Equal("Lo-Fi", t2After.Genre);

        Assert.Equal("Keep Artist", t3After.Artist);
        Assert.Equal("Keep Album", t3After.Album);

        // Verify disk ID3 tags preserve title, genre, album, and picture while updating artist.
        using (var file1 = TagLib.File.Create(p1))
        {
            Assert.Equal("Song One", file1.Tag.Title);
            Assert.Equal(["Unified Band"], file1.Tag.Performers);
            Assert.Equal(["Unified Band"], file1.Tag.AlbumArtists);
            Assert.Equal("Album One", file1.Tag.Album);
            Assert.Equal(["Jazz"], file1.Tag.Genres);
            Assert.Single(file1.Tag.Pictures);
            Assert.Equal(ValidPngBytes, file1.Tag.Pictures[0].Data.Data);
        }

        // Batch edit Album only keeps previously updated Artist.
        int countAlbum = await store.BatchUpdateTrackMetadataAsync([t1.Id, t2.Id], artist: null, album: "Unified Album");
        Assert.Equal(2, countAlbum);

        var reloaded = new LibraryStore(stateDir, musicDir, reader, artwork);
        var r1 = reloaded.GetTrack(t1.Id)!;
        var r2 = reloaded.GetTrack(t2.Id)!;
        Assert.Equal("Unified Band", r1.Artist);
        Assert.Equal("Unified Album", r1.Album);
        Assert.Equal("Unified Band", r2.Artist);
        Assert.Equal("Unified Album", r2.Album);
    }

    private static readonly byte[] ValidPngBytes =
    [
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
        0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0x1F, 0xEA, 0x02,
        0x00, 0x03, 0xEF, 0x01, 0x99, 0xC3, 0x49, 0xF5, 0x60, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
        0x44, 0xAE, 0x42, 0x60, 0x82
    ];

    private static void WriteTaggedMp3(
        string path,
        string title,
        string artist,
        string album,
        string genre,
        byte[] pictureBytes)
    {
        const int frameSize = 417;
        byte[] data = new byte[frameSize * 2];
        for (int f = 0; f < 2; f++)
        {
            int offset = f * frameSize;
            data[offset] = 0xFF;
            data[offset + 1] = 0xFB;
            data[offset + 2] = 0x90;
            data[offset + 3] = 0x00;
        }
        File.WriteAllBytes(path, data);
        using var tagFile = TagLib.File.Create(path);
        tagFile.Tag.Title = title;
        tagFile.Tag.Performers = [artist];
        tagFile.Tag.AlbumArtists = [artist];
        tagFile.Tag.Album = album;
        tagFile.Tag.Genres = [genre];
        tagFile.Tag.Pictures =
        [
            new TagLib.Picture(new TagLib.ByteVector(pictureBytes))
            {
                Type = TagLib.PictureType.FrontCover,
                MimeType = "image/png"
            }
        ];
        tagFile.Save();
    }
}
