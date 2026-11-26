import XCTest
@testable import Musico

final class StubReader: MetadataReading, @unchecked Sendable {
    private let lock = NSLock()
    private var _calls = 0
    var byName: [String: TrackMetadata] = [:]
    var delayNanos: UInt64 = 0
    var calls: Int { lock.withLock { _calls } }

    func read(_ url: URL) async -> TrackMetadata {
        lock.withLock { _calls += 1 }
        if delayNanos > 0 { try? await Task.sleep(nanoseconds: delayNanos) }
        return byName[url.lastPathComponent] ?? TrackMetadata(duration: 5)
    }
}

@MainActor
final class LibraryStoreTests: XCTestCase {
    private var state: URL!
    private var docs: URL!
    private var reader: StubReader!

    override func setUp() {
        state = makeTempDir()
        docs = makeTempDir()
        reader = StubReader()
    }

    private func makeStore(batch: Int = 100) -> LibraryStore {
        LibraryStore(directory: state, documentsURL: docs, reader: reader,
                     artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")), scanBatchSize: batch)
    }

    func testEmptyAppFolderGetsANoteSoFilesShowsIt() throws {
        #if targetEnvironment(macCatalyst)
        throw XCTSkip("The Mac uses the Music folder, which the app never writes to.")
        #endif
        _ = makeStore()
        let names = try FileManager.default.contentsOfDirectory(atPath: docs.path)
        XCTAssertEqual(names, ["Put your music in this folder.txt"])
    }

    func testAppFolderWithContentGetsNoNote() throws {
        touch("a.mp3")
        _ = makeStore()
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: docs.path), ["a.mp3"])
    }

    private func touch(_ path: String, in root: URL? = nil) {
        let url = (root ?? docs).appendingPathComponent(path)
        try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try! Data("x".utf8).write(to: url)
    }

    func testAppFolderRootAlwaysPresent() {
        let store = makeStore()
        XCTAssertEqual(store.roots.map(\.id), [LibraryRoot.appFolderID])
        XCTAssertTrue(store.isAvailable(store.roots[0]))
    }

    func testInitialScanIndexesFiles() async {
        touch("a.mp3"); touch("Rock/b.flac")
        let store = makeStore()
        await store.rescan()
        XCTAssertEqual(store.tracks.map(\.relativePath).sorted(), ["Rock/b.flac", "a.mp3"])
        let track = store.tracks.first { $0.relativePath == "Rock/b.flac" }!
        XCTAssertEqual(store.track(track.id), track)
        XCTAssertEqual(store.url(for: track)?.path, docs.appendingPathComponent("Rock/b.flac").path)
        XCTAssertFalse(store.isScanning)
    }

    /// A library saved by an older build is re-read once, so tags the old reader missed show up.
    func testLibraryFromOlderReaderIsRereadOnce() async throws {
        touch("a.flac")
        await makeStore().rescan()
        XCTAssertEqual(reader.calls, 1)

        let url = state.appendingPathComponent("library.json")
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        XCTAssertNotNil(json.removeValue(forKey: "readerVersion"))
        try JSONSerialization.data(withJSONObject: json).write(to: url)

        reader.byName["a.flac"] = TrackMetadata(title: "Now Read", duration: 5)
        let upgraded = makeStore()
        let id = try XCTUnwrap(upgraded.tracks.first?.id)
        await upgraded.rescan()
        XCTAssertEqual(reader.calls, 2)
        XCTAssertEqual(upgraded.tracks.map(\.title), ["Now Read"])
        XCTAssertEqual(upgraded.tracks.first?.id, id)

        await makeStore().rescan()
        XCTAssertEqual(reader.calls, 2)
    }

    func testRescanKeepsUnchangedWithoutRereading() async {
        touch("a.mp3")
        let store = makeStore()
        await store.rescan()
        let before = store.tracks
        XCTAssertEqual(reader.calls, 1)
        await store.rescan()
        XCTAssertEqual(reader.calls, 1)
        XCTAssertEqual(store.tracks, before)
    }

    func testRescanPicksUpNewAndRemovesDeleted() async throws {
        touch("a.mp3"); touch("b.mp3")
        let store = makeStore()
        await store.rescan()
        let a = store.tracks.first { $0.relativePath == "a.mp3" }!
        let playlist = store.createPlaylist(name: "P")
        store.add([a.id], toPlaylist: playlist)
        store.toggleFavourite(a.id)
        var removed: Set<String> = []
        store.onTracksRemoved = { removed.formUnion($0) }

        try FileManager.default.removeItem(at: docs.appendingPathComponent("a.mp3"))
        touch("c.mp3")
        await store.rescan()

        XCTAssertEqual(store.tracks.map(\.relativePath).sorted(), ["b.mp3", "c.mp3"])
        XCTAssertEqual(removed, [a.id])
        XCTAssertEqual(store.playlists.first?.trackIDs, [])
        XCTAssertFalse(store.favourites.contains(a.id))
    }

    func testChangedFileIsRereadAndKeepsDateAdded() async throws {
        touch("a.mp3")
        let store = makeStore()
        await store.rescan()
        let added = store.tracks[0].dateAdded
        try Data("longer content".utf8).write(to: docs.appendingPathComponent("a.mp3"))
        reader.byName["a.mp3"] = TrackMetadata(title: "New Title", duration: 9)
        await store.rescan()
        XCTAssertEqual(store.tracks.map(\.title), ["New Title"])
        XCTAssertEqual(store.tracks[0].dateAdded, added)
    }

    func testFallbacksWhenTagsMissing() async {
        touch("Live Set/01 Opening.mp3"); touch("loose.mp3")
        let store = makeStore()
        await store.rescan()
        let nested = store.tracks.first { $0.relativePath.hasPrefix("Live") }!
        XCTAssertEqual(nested.title, "01 Opening")
        XCTAssertEqual(nested.artist, "Unknown Artist")
        XCTAssertEqual(nested.album, "Live Set")
        XCTAssertEqual(store.tracks.first { $0.relativePath == "loose.mp3" }!.album, "Unknown Album")
    }

    func testFolderContentsListsSubfoldersAndTracks() async {
        touch("top.mp3"); touch("A/one.mp3"); touch("A/Deep/two.mp3"); touch("B/three.mp3")
        let store = makeStore()
        await store.rescan()
        let rootLevel = store.folderContents(root: LibraryRoot.appFolderID, path: "")
        XCTAssertEqual(rootLevel.folders, ["A", "B"])
        XCTAssertEqual(rootLevel.tracks.map(\.relativePath), ["top.mp3"])
        let a = store.folderContents(root: LibraryRoot.appFolderID, path: "A")
        XCTAssertEqual(a.folders, ["Deep"])
        XCTAssertEqual(a.tracks.map(\.relativePath), ["A/one.mp3"])
        XCTAssertEqual(store.tracks(under: LibraryRoot.appFolderID, path: "A").map(\.relativePath), ["A/Deep/two.mp3", "A/one.mp3"])
        XCTAssertEqual(store.tracks(under: LibraryRoot.appFolderID, path: "").count, 4)
    }

    func testFolderPrefixDoesNotMatchSiblingWithSamePrefix() async {
        touch("A/one.mp3"); touch("AB/two.mp3")
        let store = makeStore()
        await store.rescan()
        XCTAssertEqual(store.tracks(under: LibraryRoot.appFolderID, path: "A").map(\.relativePath), ["A/one.mp3"])
    }

    func testAlbumsSortedByDiscAndTrack() async {
        for name in ["x.mp3", "y.mp3", "z.mp3", "other.mp3"] { touch(name) }
        reader.byName["x.mp3"] = TrackMetadata(title: "Third", artist: "Ar", album: "Al", trackNumber: 1, discNumber: 2, duration: 1)
        reader.byName["y.mp3"] = TrackMetadata(title: "Second", artist: "Ar", album: "Al", trackNumber: 2, discNumber: 1, duration: 1)
        reader.byName["z.mp3"] = TrackMetadata(title: "First", artist: "Ar", album: "Al", trackNumber: 1, discNumber: 1, duration: 1)
        reader.byName["other.mp3"] = TrackMetadata(title: "Solo", artist: "Zed", album: "Else", duration: 1)
        let store = makeStore()
        await store.rescan()
        XCTAssertEqual(store.albums.map(\.title), ["Al", "Else"])
        XCTAssertEqual(store.albums[0].trackIDs.compactMap { store.track($0)?.title }, ["First", "Second", "Third"])
        XCTAssertEqual(store.artists, ["Ar", "Zed"])
    }

    func testSearchIsCaseAndDiacriticInsensitive() async {
        touch("a.mp3"); touch("b.mp3")
        reader.byName["a.mp3"] = TrackMetadata(title: "Café del Mar", artist: "X", album: "Y", duration: 1)
        reader.byName["b.mp3"] = TrackMetadata(title: "Other", artist: "Björk", album: "Y", duration: 1)
        let store = makeStore()
        await store.rescan()
        XCTAssertEqual(store.search("cafe").map(\.title), ["Café del Mar"])
        XCTAssertEqual(store.search("BJORK").map(\.title), ["Other"])
        XCTAssertEqual(store.search("  "), [])
    }

    func testStatePersistsAcrossInstances() async {
        touch("a.mp3")
        let first = makeStore()
        await first.rescan()
        let id = first.tracks[0].id
        first.toggleFavourite(id)
        let playlist = first.createPlaylist(name: "Mix")
        first.add([id, id], toPlaylist: playlist)

        let second = makeStore()
        XCTAssertEqual(second.tracks, first.tracks)
        XCTAssertEqual(second.favourites, [id])
        XCTAssertEqual(second.playlists.map(\.name), ["Mix"])
        XCTAssertEqual(second.playlists[0].trackIDs, [id, id])
        XCTAssertEqual(reader.calls, 1)
    }

    func testAddPickedRootScansItAndRejectsDuplicate() async throws {
        let picked = makeTempDir()
        touch("p.mp3", in: picked)
        let store = makeStore()
        try await store.addRoot(url: picked)
        XCTAssertEqual(store.roots.count, 2)
        XCTAssertEqual(store.roots[1].name, picked.lastPathComponent)
        XCTAssertEqual(store.tracks.map(\.relativePath), ["p.mp3"])
        do { try await store.addRoot(url: picked); XCTFail("expected duplicate error") } catch {}
        XCTAssertEqual(store.roots.count, 2)

        let reloaded = makeStore()
        XCTAssertNotNil(reloaded.url(for: reloaded.tracks[0]))
    }

    func testRemoveRootDropsItsTracks() async throws {
        let picked = makeTempDir()
        touch("p.mp3", in: picked); touch("mine.mp3")
        let store = makeStore()
        try await store.addRoot(url: picked)
        await store.rescan()
        var removed: Set<String> = []
        store.onTracksRemoved = { removed.formUnion($0) }
        let pickedTrack = store.tracks.first { $0.relativePath == "p.mp3" }!
        store.removeRoot(store.roots[1].id)
        XCTAssertEqual(store.tracks.map(\.relativePath), ["mine.mp3"])
        XCTAssertEqual(removed, [pickedTrack.id])
        store.removeRoot(LibraryRoot.appFolderID)
        XCTAssertEqual(store.roots.count, 1)
    }

    private func makeMacStore() -> LibraryStore {
        LibraryStore(directory: state, documentsURL: docs, reader: reader,
                     artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")),
                     appFolderRemovable: true)
    }

    func testRemovableAppFolderIsRemovedWithItsTracksAndStaysRemoved() async {
        touch("mine.mp3")
        let store = makeMacStore()
        await store.rescan()
        var removed: Set<String> = []
        store.onTracksRemoved = { removed.formUnion($0) }
        let id = store.tracks[0].id

        store.removeRoot(LibraryRoot.appFolderID)

        XCTAssertTrue(store.roots.isEmpty)
        XCTAssertTrue(store.tracks.isEmpty)
        XCTAssertEqual(removed, [id])
        XCTAssertTrue(store.appFolderRemoved)

        let relaunched = makeMacStore()
        await relaunched.rescan()
        XCTAssertTrue(relaunched.roots.isEmpty)
        XCTAssertTrue(relaunched.tracks.isEmpty)
        XCTAssertTrue(relaunched.appFolderRemoved)
    }

    func testRestoreAppFolderBringsItsSongsBackFirstInTheList() async throws {
        let picked = makeTempDir()
        touch("p.mp3", in: picked); touch("mine.mp3")
        let store = makeMacStore()
        try await store.addRoot(url: picked)
        store.removeRoot(LibraryRoot.appFolderID)
        XCTAssertEqual(store.tracks.map(\.relativePath), ["p.mp3"])

        await store.restoreAppFolder()

        XCTAssertEqual(store.roots.first?.id, LibraryRoot.appFolderID)
        XCTAssertFalse(store.appFolderRemoved)
        XCTAssertEqual(Set(store.tracks.map(\.relativePath)), ["p.mp3", "mine.mp3"])
        XCTAssertFalse(makeMacStore().appFolderRemoved)
    }

    func testRemovedAppFolderCanBeAddedAgainAsAPickedFolder() async throws {
        touch("mine.mp3")
        let store = makeMacStore()
        store.removeRoot(LibraryRoot.appFolderID)
        try await store.addRoot(url: docs)
        XCTAssertEqual(store.tracks.map(\.relativePath), ["mine.mp3"])
    }

    func testUnavailableRootKeepsTracksButHasNoURL() async throws {
        let picked = makeTempDir()
        touch("p.mp3", in: picked)
        let store = makeStore()
        try await store.addRoot(url: picked)
        try FileManager.default.removeItem(at: picked)

        let reloaded = makeStore()
        await reloaded.rescan()
        XCTAssertEqual(reloaded.tracks.map(\.relativePath), ["p.mp3"])
        XCTAssertFalse(reloaded.isAvailable(reloaded.roots[1]))
        XCTAssertNil(reloaded.url(for: reloaded.tracks[0]))
    }

    func testRemovingRootDuringScanLeavesNoOrphans() async throws {
        let picked = makeTempDir()
        for i in 0..<6 { touch("p\(i).mp3", in: picked) }
        let store = makeStore()
        reader.delayNanos = 40_000_000
        let adding = Task { try await store.addRoot(url: picked) }
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertTrue(store.isScanning)
        store.removeRoot(store.roots[1].id)
        try await adding.value
        XCTAssertEqual(store.tracks, [])
        XCTAssertEqual(makeStore().tracks, [])
    }

    func testAddRootWhileScanningStillScansTheNewRoot() async throws {
        for i in 0..<4 { touch("d\(i).mp3") }
        let picked = makeTempDir()
        touch("p.mp3", in: picked)
        let store = makeStore()
        reader.delayNanos = 40_000_000
        let first = Task { await store.rescan() }
        try await Task.sleep(nanoseconds: 60_000_000)
        XCTAssertTrue(store.isScanning)
        try await store.addRoot(url: picked)
        await first.value
        XCTAssertTrue(store.tracks.contains { $0.relativePath == "p.mp3" })
        XCTAssertEqual(store.tracks.count, 5)
        XCTAssertFalse(store.isScanning)
    }

    func testPickedRootThatSuddenlyLooksEmptyKeepsTracksAndPlaylists() async throws {
        let picked = makeTempDir()
        touch("p.mp3", in: picked)
        let store = makeStore()
        try await store.addRoot(url: picked)
        let id = store.tracks[0].id
        store.add([id], toPlaylist: store.createPlaylist(name: "P"))
        // A cloud or external folder that is reachable but not listing its contents yet.
        try FileManager.default.removeItem(at: picked.appendingPathComponent("p.mp3"))
        await store.rescan()
        XCTAssertEqual(store.tracks.map(\.id), [id])
        XCTAssertEqual(store.playlists[0].trackIDs, [id])
    }

    func testEvictedICloudFileStaysInLibraryAndPlaylists() async throws {
        touch("a.mp3"); touch("b.mp3")
        let store = makeStore()
        await store.rescan()
        let a = store.tracks.first { $0.relativePath == "a.mp3" }!
        store.add([a.id], toPlaylist: store.createPlaylist(name: "P"))
        try FileManager.default.removeItem(at: docs.appendingPathComponent("a.mp3"))
        touch(".a.mp3.icloud"); touch(".new.mp3.icloud")
        await store.rescan()
        XCTAssertEqual(store.tracks.map(\.relativePath).sorted(), ["a.mp3", "b.mp3"])
        XCTAssertEqual(store.track(a.id), a)
        XCTAssertEqual(store.playlists[0].trackIDs, [a.id])
        XCTAssertEqual(reader.calls, 2)
    }

    func testUnchangedRescanDoesNotRewriteLibraryFile() async throws {
        touch("a.mp3")
        let store = makeStore()
        await store.rescan()
        let file = state.appendingPathComponent("library.json")
        let past = Date(timeIntervalSince1970: 1_000_000)
        try FileManager.default.setAttributes([.modificationDate: past], ofItemAtPath: file.path)
        await store.rescan()
        let modified = try FileManager.default.attributesOfItem(atPath: file.path)[.modificationDate] as? Date
        XCTAssertEqual(modified, past)
    }

    func testScanPublishesTracksInBatchesBeforeItFinishes() async throws {
        for i in 0..<6 { touch("s\(i).mp3") }
        let store = makeStore(batch: 2)
        reader.delayNanos = 50_000_000
        let scan = Task { await store.rescan() }
        try await Task.sleep(nanoseconds: 190_000_000)
        XCTAssertTrue(store.isScanning)
        XCTAssertGreaterThanOrEqual(store.tracks.count, 2)
        XCTAssertLessThan(store.tracks.count, 6)
        await scan.value
        XCTAssertEqual(store.tracks.count, 6)
    }

    func testRealReaderReadsFixtureDuration() async throws {
        let url = try XCTUnwrap(Bundle(for: LibraryStoreTests.self).url(forResource: "sample", withExtension: "mp3"))
        let metadata = await MetadataReader().read(url)
        XCTAssertGreaterThan(metadata.duration, 1)
    }

    func testArtworkCacheStoresOncePerImageAndPrunes() {
        let cache = ArtworkCache(directory: state.appendingPathComponent("Art"))
        let png = UIGraphicsImageRenderer(size: CGSize(width: 20, height: 20)).pngData { ctx in
            UIColor.red.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 20, height: 20))
        }
        let key = cache.store(png)
        XCTAssertNotNil(key)
        XCTAssertEqual(cache.store(png), key)
        XCTAssertNotNil(cache.image(forKey: key!))
        XCTAssertNil(cache.store(Data("not an image".utf8)))
        cache.prune(keeping: [])
        XCTAssertNil(ArtworkCache(directory: state.appendingPathComponent("Art")).image(forKey: key!))
    }

    // MARK: - Cloud Roots

    func testAddCloudRootPersistsSyncsAndPreservesPlaylistsOnOfflineError() async throws {
        let png = UIGraphicsImageRenderer(size: CGSize(width: 10, height: 10)).pngData { ctx in
            UIColor.blue.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 10, height: 10))
        }
        let fetcher = StubCloudFetcher(result: .success(CloudPlaylistResult(
            kind: .cloudYouTube,
            remoteID: "PLcloud123",
            title: "Cloud Mix",
            tracks: [
                CloudTrackInfo(
                    remoteTrackID: "vid1",
                    relativePath: "vid1",
                    title: "Song One",
                    artist: "Artist One",
                    album: "Album One",
                    trackNumber: 1,
                    duration: 180,
                    artworkURL: "https://example.com/art1.jpg"
                ),
                CloudTrackInfo(
                    remoteTrackID: "vid2",
                    relativePath: "vid2",
                    title: "Song Two",
                    artist: "Artist Two",
                    album: "Album One",
                    trackNumber: 2,
                    duration: 210,
                    artworkURL: nil
                )
            ]
        )))

        let store = LibraryStore(
            directory: state,
            documentsURL: docs,
            reader: reader,
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")),
            cloudFetcher: fetcher,
            artworkDownloader: { _ in png }
        )

        try await store.addCloudRoot(sourceURL: "https://music.youtube.com/playlist?list=PLcloud123")
        XCTAssertEqual(store.roots.count, 2)
        let cloudRoot = try XCTUnwrap(store.roots.last)
        XCTAssertEqual(cloudRoot.kind, .cloudYouTube)
        XCTAssertEqual(cloudRoot.name, "Cloud Mix")
        XCTAssertTrue(store.isAvailable(cloudRoot))
        XCTAssertTrue(store.isRemovable(cloudRoot))
        XCTAssertEqual(store.tracks.count, 2)
        XCTAssertTrue(store.tracks.allSatisfy(\.isCloud))
        XCTAssertNotNil(store.tracks.first { $0.relativePath == "vid1" }?.artworkKey)

        // Duplicate cloud root is rejected.
        do {
            try await store.addCloudRoot(sourceURL: "https://www.youtube.com/playlist?list=PLcloud123")
            XCTFail("Expected duplicateRoot error")
        } catch {}

        // Add one track to a playlist and favourite it.
        let song1 = try XCTUnwrap(store.tracks.first { $0.relativePath == "vid1" })
        let addedDate = song1.dateAdded
        let playlistID = store.createPlaylist(name: "Fav Cloud")
        store.add([song1.id], toPlaylist: playlistID)
        store.toggleFavourite(song1.id)

        // Relaunch: cloud root and tracks persist, and offline sync error keeps tracks/playlists intact.
        fetcher.result = .failure(CloudSourceError.networkError("Offline"))
        let relaunched = LibraryStore(
            directory: state,
            documentsURL: docs,
            reader: reader,
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")),
            cloudFetcher: fetcher,
            artworkDownloader: { _ in png }
        )
        XCTAssertEqual(relaunched.roots.count, 2)
        XCTAssertEqual(relaunched.tracks.count, 2)
        await relaunched.rescan()
        XCTAssertEqual(relaunched.tracks.count, 2)
        XCTAssertNotNil(relaunched.cloudSyncErrors[cloudRoot.id])
        XCTAssertEqual(relaunched.playlists.first?.trackIDs, [song1.id])
        XCTAssertTrue(relaunched.isFavourite(song1.id))

        // Online again with updated track list: keeps vid1 dateAdded, removes vid2, adds vid3.
        fetcher.result = .success(CloudPlaylistResult(
            kind: .cloudYouTube,
            remoteID: "PLcloud123",
            title: "Cloud Mix",
            tracks: [
                CloudTrackInfo(
                    remoteTrackID: "vid1",
                    relativePath: "vid1",
                    title: "Song One",
                    artist: "Artist One",
                    album: "Album One",
                    trackNumber: 1,
                    duration: 180,
                    artworkURL: "https://example.com/art1.jpg"
                ),
                CloudTrackInfo(
                    remoteTrackID: "vid3",
                    relativePath: "vid3",
                    title: "Song Three",
                    artist: "Artist Three",
                    album: "Album Two",
                    trackNumber: 3,
                    duration: 195,
                    artworkURL: nil
                )
            ]
        ))
        await relaunched.rescan()
        XCTAssertNil(relaunched.cloudSyncErrors[cloudRoot.id])
        XCTAssertEqual(relaunched.tracks.map(\.relativePath).sorted(), ["vid1", "vid3"])
        XCTAssertEqual(relaunched.track(song1.id)?.dateAdded, addedDate)
        XCTAssertEqual(relaunched.playlists.first?.trackIDs, [song1.id])

        // Removing cloud root drops its tracks and cleans up playlists/favourites.
        relaunched.removeRoot(cloudRoot.id)
        XCTAssertEqual(relaunched.roots.count, 1)
        XCTAssertTrue(relaunched.tracks.isEmpty)
        XCTAssertEqual(relaunched.playlists.first?.trackIDs, [])
        XCTAssertFalse(relaunched.favourites.contains(song1.id))
    }

    // MARK: - Subfolder Removal

    func testRemoveSubfolderExcludesOnlyTargetedTreeAndPersistsAcrossRescanAndReload() async throws {
        let picked = makeTempDir()
        touch("A/one.mp3", in: picked)
        touch("A/Deep/two.mp3", in: picked)
        touch("AB/sibling.mp3", in: picked)
        touch("root.mp3", in: picked)

        let store = makeStore()
        try await store.addRoot(url: picked)
        let pickedRoot = try XCTUnwrap(store.roots.first { $0.kind == .picked })

        let aOne = try XCTUnwrap(store.tracks.first { $0.relativePath == "A/one.mp3" })
        let aDeep = try XCTUnwrap(store.tracks.first { $0.relativePath == "A/Deep/two.mp3" })
        let ab = try XCTUnwrap(store.tracks.first { $0.relativePath == "AB/sibling.mp3" })
        let playlistID = store.createPlaylist(name: "Sub Mix")
        store.add([aOne.id, aDeep.id, ab.id], toPlaylist: playlistID)
        store.toggleFavourite(aOne.id)
        store.toggleFavourite(ab.id)

        var removedCallbacks: Set<String> = []
        store.onTracksRemoved = { removedCallbacks.formUnion($0) }

        store.removeSubfolder(rootID: pickedRoot.id, path: "A")

        // Disk files remain intact.
        XCTAssertTrue(FileManager.default.fileExists(atPath: picked.appendingPathComponent("A/one.mp3").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: picked.appendingPathComponent("A/Deep/two.mp3").path))

        XCTAssertEqual(Set(store.tracks.map(\.relativePath)), ["AB/sibling.mp3", "root.mp3"])
        XCTAssertEqual(removedCallbacks, [aOne.id, aDeep.id])
        XCTAssertEqual(store.playlists[0].trackIDs, [ab.id])
        XCTAssertFalse(store.isFavourite(aOne.id))
        XCTAssertTrue(store.isFavourite(ab.id))

        // Rescan & fresh store reload keep "A" excluded.
        await store.rescan()
        XCTAssertEqual(Set(store.tracks.map(\.relativePath)), ["AB/sibling.mp3", "root.mp3"])

        let reloaded = makeStore()
        await reloaded.rescan()
        XCTAssertEqual(Set(reloaded.tracks.map(\.relativePath)), ["AB/sibling.mp3", "root.mp3"])
        XCTAssertEqual(reloaded.root(pickedRoot.id)?.normalizedExcludedPaths, ["A"])

        // Restoring excluded subfolders brings A's tracks back on rescan.
        await reloaded.restoreExcludedFolders(pickedRoot.id)
        XCTAssertEqual(
            Set(reloaded.tracks.map(\.relativePath)),
            ["A/one.mp3", "A/Deep/two.mp3", "AB/sibling.mp3", "root.mp3"]
        )
        XCTAssertEqual(reloaded.root(pickedRoot.id)?.normalizedExcludedPaths, [])
    }

    func testRemovingAllSubfoldersAndParentPruningAndLegacyDecode() async throws {
        let picked = makeTempDir()
        touch("Group/Sub/one.mp3", in: picked)
        touch("Group/two.mp3", in: picked)

        let store = makeStore()
        try await store.addRoot(url: picked)
        let pickedRoot = try XCTUnwrap(store.roots.first { $0.kind == .picked })

        store.removeSubfolder(rootID: pickedRoot.id, path: "Group/Sub")
        XCTAssertEqual(store.tracks.map(\.relativePath), ["Group/two.mp3"])
        XCTAssertEqual(store.root(pickedRoot.id)?.normalizedExcludedPaths, ["Group/Sub"])

        // Excluding parent prunes deeper child exclusion and removes remaining track cleanly.
        store.removeSubfolder(rootID: pickedRoot.id, path: "Group")
        XCTAssertEqual(store.root(pickedRoot.id)?.normalizedExcludedPaths, ["Group"])
        XCTAssertTrue(store.tracks.isEmpty)

        // Picked root with 0 non-excluded tracks must stay empty on rescan.
        await store.rescan()
        XCTAssertTrue(store.tracks.isEmpty)

        // Legacy LibraryRoot JSON without excludedPaths decodes cleanly.
        let legacyJSON = """
        {"id":"00000000-0000-0000-0000-000000000001","name":"Old","kind":"picked"}
        """
        let decoded = try JSONDecoder().decode(LibraryRoot.self, from: Data(legacyJSON.utf8))
        XCTAssertEqual(decoded.normalizedExcludedPaths, [])
        XCTAssertEqual(decoded.normalizedTrackedSubfolders, [:])
        XCTAssertFalse(decoded.isExcluded("Any/song.mp3"))
    }

    // MARK: - Subfolder Cloud Tracking, Overlap Prevention, Manifest Discovery & Address Editing

    func testOverlapPreventionRejectsChildAndMigratesContainedPickedRootsOnParentAdd() async throws {
        let parentDir = makeTempDir()
        let childA = parentDir.appendingPathComponent("ChildA")
        let childB = parentDir.appendingPathComponent("Nested/ChildB")
        touch("songA.mp3", in: childA)
        touch("Excluded/skip.mp3", in: childA)
        touch("songB.mp3", in: childB)
        touch("top.mp3", in: parentDir)

        let store = makeStore()
        try await store.addRoot(url: childA)
        try await store.addRoot(url: childB)

        let rootA = try XCTUnwrap(store.roots.first { $0.name == "ChildA" })
        let rootB = try XCTUnwrap(store.roots.first { $0.name == "ChildB" })

        let cloudA = LibraryRoot(
            id: UUID(),
            name: "Cloud A",
            kind: .cloudYouTube,
            bookmark: nil,
            sourceURL: "https://music.youtube.com/playlist?list=PLchildA",
            remoteID: "PLchildA",
            cookie: "COOKIE_A"
        )
        store.attachCloudTracking(toFolderURL: childA, from: cloudA)
        store.removeSubfolder(rootID: rootA.id, path: "Excluded")

        let songAID = try XCTUnwrap(store.tracks.first { $0.relativePath == "songA.mp3" }?.id)
        let pid = store.createPlaylist(name: "Child Mix")
        store.add([songAID], toPlaylist: pid)
        store.toggleFavourite(songAID)

        // Adding a child inside an existing root must fail with duplicateRoot.
        let subInsideA = childA.appendingPathComponent("SubInside")
        try FileManager.default.createDirectory(at: subInsideA, withIntermediateDirectories: true)
        do {
            try await store.addRoot(url: subInsideA)
            XCTFail("Expected duplicateRoot when adding folder inside existing root")
        } catch LibraryError.duplicateRoot {}

        // Also cannot add a subfolder inside appFolder.
        let subInsideApp = docs.appendingPathComponent("AppSub")
        try FileManager.default.createDirectory(at: subInsideApp, withIntermediateDirectories: true)
        do {
            try await store.addRoot(url: subInsideApp)
            XCTFail("Expected duplicateRoot when adding folder inside appFolder")
        } catch LibraryError.duplicateRoot {}

        // Adding parentDir migrates ChildA & ChildB, their cloud tracking, exclusions, and playlists/favourites.
        try await store.addRoot(url: parentDir)

        XCTAssertEqual(store.roots.filter { $0.kind == .picked }.count, 1)
        let parentRoot = try XCTUnwrap(store.roots.first { $0.kind == .picked })
        XCTAssertNil(store.root(rootA.id))
        XCTAssertNil(store.root(rootB.id))

        let migratedA = try XCTUnwrap(store.cloudTracking(rootID: parentRoot.id, path: "ChildA"))
        XCTAssertEqual(migratedA.sourceURL, "https://music.youtube.com/playlist?list=PLchildA")
        XCTAssertEqual(migratedA.cookie, "COOKIE_A")
        XCTAssertEqual(migratedA.cloudKind, .cloudYouTube)

        // Excluded subfolder stayed excluded; non-excluded tracks migrated cleanly.
        let paths = Set(store.tracks.map(\.relativePath))
        XCTAssertEqual(paths, ["top.mp3", "ChildA/songA.mp3", "Nested/ChildB/songB.mp3"])
        let migratedSongAID = try XCTUnwrap(store.tracks.first { $0.relativePath == "ChildA/songA.mp3" }?.id)
        XCTAssertEqual(store.playlists.first?.trackIDs, [migratedSongAID])
        XCTAssertTrue(store.isFavourite(migratedSongAID))
    }

    func testRegisterExportedCloudFolderManifestDiscoveryAndPruningAndAddressEdit() async throws {
        let parentDir = makeTempDir()
        touch("root.mp3", in: parentDir)

        let fetcher = MutableCloudFetcher(result: .success(CloudPlaylistResult(
            kind: .cloudYouTube,
            remoteID: "PLnew",
            title: "Updated Remote",
            tracks: [
                CloudTrackInfo(remoteTrackID: "v1", relativePath: "v1", title: "Existing", artist: "A", album: "Al", trackNumber: 1, duration: 10, artworkURL: nil)
            ]
        )))
        let store = LibraryStore(
            directory: state,
            documentsURL: docs,
            reader: reader,
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")),
            cloudFetcher: fetcher
        )
        try await store.addRoot(url: parentDir)
        let parentRoot = try XCTUnwrap(store.roots.first { $0.kind == .picked })

        // Exclude "Exports/OldMix", then registerExportedCloudFolder un-excludes it and tracks it without duplicate root.
        let exportedSub = parentDir.appendingPathComponent("Exports/OldMix")
        touch("track1.m4a", in: exportedSub)
        await store.rescan()
        store.removeSubfolder(rootID: parentRoot.id, path: "Exports/OldMix")
        XCTAssertFalse(store.tracks.contains { $0.relativePath == "Exports/OldMix/track1.m4a" })

        let cloudRoot = LibraryRoot(
            id: UUID(),
            name: "Cloud Mix",
            kind: .cloudYouTube,
            bookmark: nil,
            sourceURL: "https://music.youtube.com/playlist?list=PLold",
            remoteID: "PLold",
            cookie: "C1"
        )
        var syncedQueues: [(String, [String])] = []
        store.onFolderAdded = { name, ids in syncedQueues.append((name, ids)) }

        try await store.registerExportedCloudFolder(destinationURL: exportedSub, from: cloudRoot)

        XCTAssertEqual(store.roots.filter { $0.kind == .picked }.count, 1)
        XCTAssertTrue(store.tracks.contains { $0.relativePath == "Exports/OldMix/track1.m4a" })
        XCTAssertEqual(syncedQueues.last?.0, "OldMix")
        XCTAssertEqual(syncedQueues.last?.1.count, 1)
        let subTracking = try XCTUnwrap(store.cloudTracking(rootID: parentRoot.id, path: "Exports/OldMix"))
        XCTAssertEqual(subTracking.sourceURL, "https://music.youtube.com/playlist?list=PLold")

        // Write external manifest into another subfolder and verify scanOnce discovers it.
        let externalSub = parentDir.appendingPathComponent("ExternalBili")
        try FileManager.default.createDirectory(at: externalSub, withIntermediateDirectories: true)
        let extManifest = CloudFolderManifest(
            sourceURL: "https://www.bilibili.com/video/BV1xx411c7mD",
            remoteID: "BV1xx411c7mD",
            cloudKind: .cloudBilibili,
            lastSyncedEpochMs: Int64(Date().timeIntervalSince1970 * 1000),
            tracks: [:]
        )
        try CloudExporter.saveManifest(extManifest, in: externalSub)
        await store.rescan()
        let discovered = try XCTUnwrap(store.cloudTracking(rootID: parentRoot.id, path: "ExternalBili"))
        XCTAssertEqual(discovered.cloudKind, .cloudBilibili)

        // Update cloud tracking address (rejects invalid URL, accepts valid URL, persists store & manifest).
        XCTAssertThrowsError(try store.updateCloudTrackingAddress(
            rootID: parentRoot.id,
            path: "ExternalBili",
            sourceURL: "not-a-cloud-url",
            cookie: nil
        ))
        try store.updateCloudTrackingAddress(
            rootID: parentRoot.id,
            path: "ExternalBili",
            sourceURL: "https://music.youtube.com/playlist?list=PLedited123",
            cookie: "SID=new"
        )
        let editedTracking = try XCTUnwrap(store.cloudTracking(rootID: parentRoot.id, path: "ExternalBili"))
        XCTAssertEqual(editedTracking.sourceURL, "https://music.youtube.com/playlist?list=PLedited123")
        XCTAssertEqual(editedTracking.cloudKind, .cloudYouTube)
        XCTAssertEqual(editedTracking.cookie, "SID=new")

        let diskManifestAfterEdit = try XCTUnwrap(CloudExporter.loadManifest(in: externalSub))
        XCTAssertEqual(diskManifestAfterEdit.sourceURL, "https://music.youtube.com/playlist?list=PLedited123")
        XCTAssertEqual(diskManifestAfterEdit.cloudKind, .cloudYouTube)

        // Write a stale manifest with OLD URL & timestamp in ExternalBili and verify rescan preserves newer edited store tracking AND updateTrackedLocalRoot prefers edited tracking.
        let staleManifest = CloudFolderManifest(
            sourceURL: "https://www.bilibili.com/video/BV1stale",
            remoteID: "BV1stale",
            cloudKind: .cloudBilibili,
            lastSyncedEpochMs: 1_000,
            tracks: ["v1": "Existing.m4a"]
        )
        touch("Existing.m4a", in: externalSub)
        try CloudExporter.saveManifest(staleManifest, in: externalSub)
        await store.rescan()
        XCTAssertEqual(store.cloudTracking(rootID: parentRoot.id, path: "ExternalBili")?.sourceURL, "https://music.youtube.com/playlist?list=PLedited123")

        let update = try await store.updateTrackedLocalRoot(parentRoot.id, path: "ExternalBili")
        XCTAssertEqual(fetcher.recordedSources.last, "https://music.youtube.com/playlist?list=PLedited123")
        XCTAssertEqual(fetcher.recordedCookies.last ?? nil, "SID=new")
        XCTAssertEqual(update.skippedCount, 1)
        XCTAssertEqual(CloudExporter.loadManifest(in: externalSub)?.sourceURL, "https://music.youtube.com/playlist?list=PLedited123")

        // Delete ExternalBili folder from disk -> rescan prunes its trackedSubfolders entry.
        try FileManager.default.removeItem(at: externalSub)
        await store.rescan()
        XCTAssertNil(store.cloudTracking(rootID: parentRoot.id, path: "ExternalBili"))
    }

    func testCloudFolderSafeRebindingAndOptionAOrphanedRecovery() async throws {
        let dir = makeTempDir()
        let docs = makeTempDir()
        let registry = CloudProviderRegistry()

        // Backward-compatible LibraryRoot decoding without streamQualityID/downloadQualityID
        let legacyRootJSON = """
        {
          "id": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
          "name": "Orphaned YT",
          "kind": "cloudExtension",
          "sourceURL": "https://music.youtube.com/playlist?list=PLorphan1",
          "remoteID": "PLorphan1",
          "providerID": "ext.missing-yt"
        }
        """
        let decodedRoot = try JSONDecoder().decode(LibraryRoot.self, from: Data(legacyRootJSON.utf8))
        XCTAssertNil(decodedRoot.streamQualityID)
        XCTAssertNil(decodedRoot.downloadQualityID)

        struct SavedLib: Codable {
            var roots: [LibraryRoot]
            var tracks: [Track]
        }
        let orphanTrack = Track(
            id: Track.makeID(rootID: decodedRoot.id, relativePath: "vid-1"),
            rootID: decodedRoot.id,
            relativePath: "vid-1",
            title: "Orphan Song",
            artist: "Artist",
            album: "Album",
            albumArtist: "Artist",
            duration: 180,
            fileSize: 0,
            modified: Date(),
            hasEmbeddedLyrics: false,
            dateAdded: Date(),
            cloudSource: "ext.missing-yt",
            remoteTrackID: "vid-1"
        )
        try Persistence.save(SavedLib(roots: [decodedRoot], tracks: [orphanTrack]), to: dir.appendingPathComponent("library.json"))

        let fetcher = MutableCloudFetcher(result: .success(CloudPlaylistResult(
            kind: .cloudYouTube,
            remoteID: "PLorphan1",
            title: "Recovered YT",
            tracks: [
                CloudTrackInfo(
                    remoteTrackID: "vid-1",
                    relativePath: "vid-1",
                    title: "Recovered Song",
                    artist: "Artist",
                    album: "Album",
                    trackNumber: 1,
                    duration: 180,
                    artworkURL: nil
                )
            ],
            providerID: BuiltInProviderID.youtube
        )))

        let store = LibraryStore(
            directory: dir,
            documentsURL: docs,
            reader: StubReader(),
            artwork: ArtworkCache(directory: makeTempDir()),
            cloudFetcher: fetcher,
            providerRegistry: registry,
            extensionAvailability: { pid in .missing(providerID: pid) },
            artworkDownloader: { _ in nil }
        )

        let orphanRoot = try XCTUnwrap(store.root(decodedRoot.id))
        XCTAssertEqual(store.cloudExtensionAvailability(for: orphanRoot), .missing(providerID: "ext.missing-yt"))
        XCTAssertFalse(store.isAvailable(orphanRoot))

        // Option A: compatibleProviders finds built-in YouTube matching sourceURL
        let compatible = store.compatibleProviders(for: orphanRoot)
        XCTAssertEqual(compatible.map(\.id), [BuiltInProviderID.youtube])

        let playlistID = store.createPlaylist(name: "Saved Mix")
        store.add([orphanTrack.id], toPlaylist: playlistID)
        store.toggleFavourite(orphanTrack.id)

        // Safe re-binding to builtin.youtube keeps root.id, updates kind/provider/qualities, and triggers clean re-sync
        try await store.rebindCloudRoot(
            orphanRoot.id,
            sourceURL: "https://music.youtube.com/playlist?list=PLorphan1",
            cookie: "CONSENT=YES",
            preferredProviderID: BuiltInProviderID.youtube,
            streamQualityID: "high",
            downloadQualityID: "highest"
        )

        let rebound = try XCTUnwrap(store.root(orphanRoot.id))
        XCTAssertEqual(rebound.id, orphanRoot.id)
        XCTAssertEqual(rebound.kind, .cloudYouTube)
        XCTAssertEqual(rebound.resolvedProviderID, BuiltInProviderID.youtube)
        XCTAssertEqual(rebound.cookie, "CONSENT=YES")
        XCTAssertEqual(rebound.streamQualityID, "high")
        XCTAssertEqual(rebound.downloadQualityID, "highest")
        XCTAssertTrue(store.isAvailable(rebound))

        let syncedTrack = try XCTUnwrap(store.tracks.first { $0.rootID == orphanRoot.id })
        XCTAssertEqual(syncedTrack.cloudSource, "youtube")
        XCTAssertEqual(syncedTrack.remoteTrackID, "vid-1")
        XCTAssertEqual(syncedTrack.title, "Recovered Song")
        XCTAssertEqual(store.playlists.first { $0.id == playlistID }?.trackIDs, [syncedTrack.id])
        XCTAssertTrue(store.isFavourite(syncedTrack.id))
    }

    func testDisplayLocationRemoveTrackDeleteTrackAndFolderGuards() async throws {
        let dir = makeTempDir()
        let docs = makeTempDir()
        let pickedRootURL = makeTempDir()
        let subFolderURL = pickedRootURL.appendingPathComponent("AlbumA", isDirectory: true)
        try FileManager.default.createDirectory(at: subFolderURL, withIntermediateDirectories: true)

        let fixture = try XCTUnwrap(Bundle(for: LibraryStoreTests.self).url(forResource: "sample", withExtension: "mp3"))
        let appTrackURL = docs.appendingPathComponent("root-song.mp3")
        let pickedRootTrackURL = pickedRootURL.appendingPathComponent("keep.mp3")
        let subTrackURL = subFolderURL.appendingPathComponent("sub-song.mp3")
        try FileManager.default.copyItem(at: fixture, to: appTrackURL)
        try FileManager.default.copyItem(at: fixture, to: pickedRootTrackURL)
        try FileManager.default.copyItem(at: fixture, to: subTrackURL)

        let store = LibraryStore(
            directory: dir,
            documentsURL: docs,
            reader: StubReader(),
            artwork: ArtworkCache(directory: makeTempDir())
        )
        try await store.addRoot(url: pickedRootURL)
        await store.rescan()

        let pickedRoot = try XCTUnwrap(store.roots.first { $0.kind == .picked })
        let appTrack = try XCTUnwrap(store.tracks.first { $0.rootID == LibraryRoot.appFolderID && $0.fileName == "root-song.mp3" })
        let keepTrack = try XCTUnwrap(store.tracks.first { $0.rootID == pickedRoot.id && $0.fileName == "keep.mp3" })
        let subTrack = try XCTUnwrap(store.tracks.first { $0.rootID == pickedRoot.id && $0.fileName == "sub-song.mp3" })

        XCTAssertEqual(store.displayLocation(for: appTrack), appTrackURL.path)
        XCTAssertEqual(store.displayLocation(forRoot: pickedRoot.id, path: "AlbumA"), subFolderURL.path)

        // 1. removeTrackFromLibrary excludes path so rescan doesn't bring it back, file stays on disk
        let playlistID = store.createPlaylist(name: "Mix")
        store.add([keepTrack.id, subTrack.id], toPlaylist: playlistID)
        store.toggleFavourite(keepTrack.id)

        store.removeTrackFromLibrary(keepTrack.id)
        XCTAssertNil(store.track(keepTrack.id))
        XCTAssertFalse(store.isFavourite(keepTrack.id))
        XCTAssertEqual(store.playlists.first { $0.id == playlistID }?.trackIDs, [subTrack.id])
        XCTAssertTrue(FileManager.default.fileExists(atPath: pickedRootTrackURL.path))
        await store.rescan()
        XCTAssertNil(store.track(keepTrack.id))

        // Restore brings it back
        await store.restoreExcludedFolders(pickedRoot.id)
        XCTAssertNotNil(store.track(keepTrack.id))

        // 2. appFolder root deletion on disk MUST throw and leave docs intact
        XCTAssertThrowsError(try store.deleteFolderFiles(rootID: LibraryRoot.appFolderID, path: "")) { error in
            XCTAssertEqual(error as? LibraryError, .cannotDeleteAppFolderRoot)
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: docs.path))
        XCTAssertNotNil(store.root(LibraryRoot.appFolderID))

        // 3. deleteTrackFile invokes onBeforeDeletingTracks, deletes file on disk, and removes track
        var deletedCallbacks: [Set<String>] = []
        store.onBeforeDeletingTracks = { deletedCallbacks.append($0) }
        try store.deleteTrackFile(appTrack.id)
        XCTAssertEqual(deletedCallbacks.last, [appTrack.id])
        XCTAssertFalse(FileManager.default.fileExists(atPath: appTrackURL.path))
        XCTAssertNil(store.track(appTrack.id))

        // 4. deleteFolderFiles on subfolder deletes subfolder on disk and affected tracks
        try store.deleteFolderFiles(rootID: pickedRoot.id, path: "AlbumA")
        XCTAssertEqual(deletedCallbacks.last, [subTrack.id])
        XCTAssertFalse(FileManager.default.fileExists(atPath: subFolderURL.path))
        XCTAssertNil(store.track(subTrack.id))
        XCTAssertNotNil(store.track(keepTrack.id))

        // 5. deleteFolderFiles on picked root deletes root folder on disk and removes root
        try store.deleteFolderFiles(rootID: pickedRoot.id, path: "")
        XCTAssertEqual(deletedCallbacks.last, [keepTrack.id])
        XCTAssertFalse(FileManager.default.fileExists(atPath: pickedRootURL.path))
        XCTAssertNil(store.root(pickedRoot.id))
        XCTAssertNil(store.track(keepTrack.id))
    }
}

private final class MutableCloudFetcher: CloudPlaylistFetching, @unchecked Sendable {
    private let lock = NSLock()
    private var _result: Result<CloudPlaylistResult, Error>
    private var _recordedSources: [String] = []
    private var _recordedCookies: [String?] = []

    var recordedSources: [String] { lock.withLock { _recordedSources } }
    var recordedCookies: [String?] { lock.withLock { _recordedCookies } }

    init(result: Result<CloudPlaylistResult, Error>) {
        self._result = result
    }

    func fetchPlaylist(sourceURL: String, cookie: String?, kindHint: LibraryRoot.Kind?) async throws -> CloudPlaylistResult {
        lock.withLock {
            _recordedSources.append(sourceURL)
            _recordedCookies.append(cookie)
        }
        return try lock.withLock { _result }.get()
    }
}

private final class StubCloudFetcher: CloudPlaylistFetching, @unchecked Sendable {
    private let lock = NSLock()
    private var _result: Result<CloudPlaylistResult, Error>
    var result: Result<CloudPlaylistResult, Error> {
        get { lock.withLock { _result } }
        set { lock.withLock { _result = newValue } }
    }

    init(result: Result<CloudPlaylistResult, Error>) {
        self._result = result
    }

    func fetchPlaylist(sourceURL: String, cookie: String?, kindHint: LibraryRoot.Kind?) async throws -> CloudPlaylistResult {
        try result.get()
    }
}
