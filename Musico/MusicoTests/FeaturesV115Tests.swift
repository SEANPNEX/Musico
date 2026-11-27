import UIKit
import XCTest
@testable import Musico

@MainActor
final class FeaturesV115Tests: XCTestCase {
    private var state: URL!
    private var docs: URL!
    private var reader: StubReader!

    override func setUp() {
        state = makeTempDir()
        docs = makeTempDir()
        reader = StubReader()
    }

    private func makeLibrary() -> LibraryStore {
        LibraryStore(
            directory: state,
            documentsURL: docs,
            reader: reader,
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork"))
        )
    }

    private func touch(_ path: String, in root: URL? = nil, contents: String = "audio") {
        let url = (root ?? docs).appendingPathComponent(path)
        try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try! Data(contents.utf8).write(to: url)
    }

    // MARK: 1. Song Info Metadata Overrides & Audio Inspection

    func testMetadataOverridesPersistAcrossRescansAndRestarts() async throws {
        touch("song.mp3")
        reader.byName["song.mp3"] = TrackMetadata(
            title: "Original Title",
            artist: "Original Artist",
            album: "Original Album",
            genre: "Pop",
            duration: 120
        )
        let store = makeLibrary()
        await store.rescan()
        let track = try XCTUnwrap(store.tracks.first)
        XCTAssertEqual(track.displayGenre, "Pop")

        let customPNG = UIGraphicsImageRenderer(size: CGSize(width: 24, height: 24)).pngData { ctx in
            UIColor.systemTeal.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 24, height: 24))
        }

        let updated = try XCTUnwrap(store.updateTrackMetadata(
            id: track.id,
            title: "Edited Title",
            artist: "Kano",
            album: "Edited Album",
            genre: "J-Pop",
            artworkData: customPNG
        ))
        XCTAssertEqual(updated.title, "Edited Title")
        XCTAssertEqual(updated.artist, "Kano")
        XCTAssertEqual(updated.album, "Edited Album")
        XCTAssertEqual(updated.displayGenre, "J-Pop")
        XCTAssertNotNil(updated.artworkKey)
        XCTAssertEqual(store.artists, ["Kano"])
        XCTAssertEqual(store.albums.first?.title, "Edited Album")

        // Modify file on disk so rescan re-reads tags from StubReader, then verify overrides still win.
        try Data("modified audio content".utf8).write(to: docs.appendingPathComponent("song.mp3"))
        await store.rescan()
        let afterRescan = try XCTUnwrap(store.track(track.id))
        XCTAssertEqual(afterRescan.title, "Edited Title")
        XCTAssertEqual(afterRescan.artist, "Kano")
        XCTAssertEqual(afterRescan.album, "Edited Album")
        XCTAssertEqual(afterRescan.displayGenre, "J-Pop")
        XCTAssertEqual(afterRescan.artworkKey, updated.artworkKey)

        // Recreate LibraryStore from disk (simulated app restart).
        let reloaded = makeLibrary()
        let afterRestart = try XCTUnwrap(reloaded.track(track.id))
        XCTAssertEqual(afterRestart.title, "Edited Title")
        XCTAssertEqual(afterRestart.artist, "Kano")
        XCTAssertEqual(afterRestart.album, "Edited Album")
        XCTAssertEqual(afterRestart.displayGenre, "J-Pop")
        XCTAssertEqual(afterRestart.artworkKey, updated.artworkKey)
        XCTAssertNotNil(reloaded.artwork.image(forKey: updated.artworkKey!))
    }

    func testAudioFileInspectorReadsRealFixtures() async throws {
        let bundle = Bundle(for: FeaturesV115Tests.self)

        let wavURL = try XCTUnwrap(bundle.url(forResource: "tone-48k-mono", withExtension: "wav"))
        let wavTrack = Track(
            id: "wav",
            rootID: LibraryRoot.appFolderID,
            relativePath: "tone-48k-mono.wav",
            title: "Tone",
            artist: "A",
            album: "Al",
            albumArtist: "A",
            duration: 1,
            fileSize: 0,
            modified: Date(),
            hasEmbeddedLyrics: false,
            dateAdded: Date()
        )
        let wavInfo = await AudioFileInspector.inspect(url: wavURL, track: wavTrack)
        XCTAssertEqual(wavInfo.frequencyDisplay, "48 kHz")
        XCTAssertEqual(wavInfo.channelsDisplay, "1 ch (Mono)")
        XCTAssertEqual(wavInfo.precisionDisplay, "16-bit")
        XCTAssertGreaterThan(wavInfo.fileSize, 0)

        let flacURL = try XCTUnwrap(bundle.url(forResource: "hires-96k", withExtension: "flac"))
        let flacInfo = await AudioFileInspector.inspect(url: flacURL, track: wavTrack)
        XCTAssertEqual(flacInfo.frequencyDisplay, "96 kHz")
        XCTAssertNotEqual(flacInfo.precisionDisplay, "N/A")
        XCTAssertGreaterThan(flacInfo.bitRateBps ?? 0, 0)
    }

    func testTagWriterUpdatesFLACAndMP3TagsSafely() async throws {
        let bundle = Bundle(for: FeaturesV115Tests.self)
        let flacSrc = try XCTUnwrap(bundle.url(forResource: "art", withExtension: "flac"))
        let flacDst = state.appendingPathComponent("copy.flac")
        try FileManager.default.copyItem(at: flacSrc, to: flacDst)

        let wroteFLAC = await AudioTagWriter.write(
            AudioTagWriter.Payload(title: "New FLAC", artist: "FLAC Artist", album: "FLAC Album", genre: "Electronic", artworkData: nil),
            to: flacDst
        )
        XCTAssertTrue(wroteFLAC)
        let readBackFLAC = await MetadataReader().read(flacDst)
        XCTAssertEqual(readBackFLAC.title, "New FLAC")
        XCTAssertEqual(readBackFLAC.artist, "FLAC Artist")
        XCTAssertEqual(readBackFLAC.album, "FLAC Album")
        XCTAssertEqual(readBackFLAC.genre, "Electronic")

        let mp3Src = try XCTUnwrap(bundle.url(forResource: "sample", withExtension: "mp3"))
        let mp3Dst = state.appendingPathComponent("copy.mp3")
        try FileManager.default.copyItem(at: mp3Src, to: mp3Dst)

        let wroteMP3 = await AudioTagWriter.write(
            AudioTagWriter.Payload(title: "New MP3", artist: "MP3 Artist", album: "MP3 Album", genre: "Rock", artworkData: nil),
            to: mp3Dst
        )
        XCTAssertTrue(wroteMP3)
        let readBackMP3 = await MetadataReader().read(mp3Dst)
        XCTAssertEqual(readBackMP3.title, "New MP3")
        XCTAssertEqual(readBackMP3.artist, "MP3 Artist")
        XCTAssertEqual(readBackMP3.album, "MP3 Album")
        XCTAssertEqual(readBackMP3.genre, "Rock")
    }

    // MARK: 2. Search & Queue from Search Result

    func testSearchFieldFilteringAndQueueNaming() async throws {
        touch("a.mp3"); touch("b.mp3"); touch("c.mp3")
        reader.byName["a.mp3"] = TrackMetadata(title: "Song One", artist: "Kano", album: "Blue", genre: "J-Pop", duration: 10)
        reader.byName["b.mp3"] = TrackMetadata(title: "Kano Tribute", artist: "Other", album: "Red", genre: "Rock", duration: 10)
        reader.byName["c.mp3"] = TrackMetadata(title: "Song Three", artist: "Kano", album: "Blue", genre: "Rock", duration: 10)

        let store = makeLibrary()
        await store.rescan()

        XCTAssertEqual(store.search("Kano", field: .artist).map(\.title), ["Song One", "Song Three"])
        XCTAssertEqual(store.search("Blue", field: .album).map(\.title), ["Song One", "Song Three"])
        XCTAssertEqual(store.search("Rock", field: .genre).map(\.title), ["Kano Tribute", "Song Three"])
        XCTAssertEqual(store.search("Kano", field: .keyword).map(\.title), ["Kano Tribute", "Song One", "Song Three"])

        XCTAssertEqual(SearchField.queueName(field: .artist, query: "Kano"), "Artist: Kano")
        XCTAssertEqual(SearchField.queueName(field: .album, query: "Blue"), "Album: Blue")
        XCTAssertEqual(SearchField.queueName(field: .genre, query: "Rock"), "Genre: Rock")
        XCTAssertEqual(SearchField.queueName(field: .keyword, query: "Kano"), "Keyword: Kano")

        let nav = AppNavigation()
        nav.libraryPath.append("pushedAlbum")
        nav.openSearch(query: "Kano", field: .artist)
        XCTAssertEqual(nav.searchField, .artist)
        XCTAssertEqual(nav.searchQuery, "Kano")
        XCTAssertEqual(nav.selectedTab, .library)
        XCTAssertEqual(nav.librarySection, .songs)
        XCTAssertTrue(nav.libraryPath.isEmpty)
        XCTAssertEqual(nav.sidebarSelection, .songs)

        // Manual edit clears field tag back to keyword search.
        nav.updateSearchQueryFromUser("Kanoo")
        XCTAssertEqual(nav.searchField, .keyword)
        XCTAssertEqual(nav.searchQuery, "Kanoo")

        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        let player = PlayerController(
            library: store,
            queues: queues,
            history: history,
            engine: AudioEngine(),
            settingsURL: state.appendingPathComponent("settings.json")
        )
        defer { player.shutdown() }

        let initialQueueID = queues.activeID
        let artistResults = store.search("Kano", field: .artist).map(\.id)
        player.playInNewQueue(artistResults, startAt: 1, name: SearchField.queueName(field: .artist, query: "Kano"))

        XCTAssertNotEqual(queues.activeID, initialQueueID)
        XCTAssertEqual(queues.active.name, "Artist: Kano")
        XCTAssertEqual(queues.active.trackIDs, artistResults)
        XCTAssertEqual(queues.active.currentIndex, 1)
    }

    // MARK: 3 & 4. Sorting & Drag Reorder Persistence in Queue, Folder, Playlist

    func testSortingComparatorsAndQueueCurrentIndexPreservation() async throws {
        let fixture = try XCTUnwrap(Bundle(for: FeaturesV115Tests.self).url(forResource: "sample", withExtension: "mp3"))
        try FileManager.default.copyItem(at: fixture, to: docs.appendingPathComponent("a.mp3"))
        try FileManager.default.copyItem(at: fixture, to: docs.appendingPathComponent("b.mp3"))
        try FileManager.default.copyItem(at: fixture, to: docs.appendingPathComponent("c.mp3"))

        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 100)], ofItemAtPath: docs.appendingPathComponent("a.mp3").path)
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 300)], ofItemAtPath: docs.appendingPathComponent("b.mp3").path)
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSince1970: 200)], ofItemAtPath: docs.appendingPathComponent("c.mp3").path)

        reader.byName["a.mp3"] = TrackMetadata(title: "Alpha", duration: 50)
        reader.byName["b.mp3"] = TrackMetadata(title: "Charlie", duration: 50)
        reader.byName["c.mp3"] = TrackMetadata(title: "Bravo", duration: 50)

        let store = makeLibrary()
        await store.rescan()
        let a = store.tracks.first { $0.title == "Alpha" }!
        let b = store.tracks.first { $0.title == "Bravo" }!
        let c = store.tracks.first { $0.title == "Charlie" }!

        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        history.recordPlay(b.id)
        history.recordPlay(b.id)
        history.recordPlay(c.id)

        let player = PlayerController(
            library: store,
            queues: queues,
            history: history,
            engine: AudioEngine(),
            settingsURL: state.appendingPathComponent("settings.json")
        )
        defer { player.shutdown() }

        // Start playback on "Alpha" at index 0.
        player.play([a.id, c.id, b.id], startAt: 0)
        XCTAssertEqual(player.currentTrack?.id, a.id)
        XCTAssertEqual(queues.active.currentIndex, 0)

        player.sortActiveQueue(by: .nameDescending)
        XCTAssertEqual(queues.active.trackIDs, [c.id, b.id, a.id])
        XCTAssertEqual(queues.active.currentIndex, 2)
        XCTAssertEqual(queues.active.currentTrackID, a.id)

        player.sortActiveQueue(by: .nameAscending)
        XCTAssertEqual(queues.active.trackIDs, [a.id, b.id, c.id])
        XCTAssertEqual(queues.active.currentIndex, 0)

        player.sortActiveQueue(by: .dateModified)
        XCTAssertEqual(queues.active.trackIDs, [c.id, b.id, a.id]) // 300 (Charlie), 200 (Bravo), 100 (Alpha)
        XCTAssertEqual(queues.active.currentIndex, 2)

        player.sortActiveQueue(by: .mostPlayed)
        XCTAssertEqual(queues.active.trackIDs, [b.id, c.id, a.id]) // 2 plays, 1 play, 0 plays
        XCTAssertEqual(queues.active.currentIndex, 2)

        player.sortActiveQueue(by: .leastPlayed)
        XCTAssertEqual(queues.active.trackIDs, [a.id, c.id, b.id]) // 0, 1, 2
        XCTAssertEqual(queues.active.currentIndex, 0)

        // Drag reorder in queue persists and switches sortOrder to .custom while tracking currentIndex.
        player.moveInQueue(from: IndexSet(integer: 0), to: 3)
        XCTAssertEqual(queues.active.trackIDs, [c.id, b.id, a.id])
        XCTAssertEqual(queues.active.currentIndex, 2)
        XCTAssertEqual(queues.active.sortOrder, .custom)

        let reloadedQueues = QueueStore(directory: state)
        XCTAssertEqual(reloadedQueues.active.trackIDs, [c.id, b.id, a.id])
        XCTAssertEqual(reloadedQueues.active.currentIndex, 2)
    }

    func testFolderAndPlaylistSortingAndReorderPersistence() async throws {
        touch("Mix/01.mp3")
        touch("Mix/02.mp3")
        touch("Mix/03.mp3")
        reader.byName["01.mp3"] = TrackMetadata(title: "Zeta", duration: 10)
        reader.byName["02.mp3"] = TrackMetadata(title: "Alpha", duration: 10)
        reader.byName["03.mp3"] = TrackMetadata(title: "Beta", duration: 10)

        let store = makeLibrary()
        await store.rescan()
        let history = PlayHistoryStore(directory: state)
        let zeta = store.tracks.first { $0.title == "Zeta" }!
        let alpha = store.tracks.first { $0.title == "Alpha" }!
        let beta = store.tracks.first { $0.title == "Beta" }!
        history.recordPlay(beta.id)

        // Folder sorting & persistence
        store.setFolderSortOrder(.nameAscending, root: LibraryRoot.appFolderID, path: "Mix")
        XCTAssertEqual(store.orderedFolderTracks(root: LibraryRoot.appFolderID, path: "Mix", history: history).map(\.id), [alpha.id, beta.id, zeta.id])

        store.setFolderSortOrder(.mostPlayed, root: LibraryRoot.appFolderID, path: "Mix")
        XCTAssertEqual(store.orderedFolderTracks(root: LibraryRoot.appFolderID, path: "Mix", history: history).map(\.id), [beta.id, alpha.id, zeta.id])

        // Reorder folder tracks -> automatically switches to .custom and persists across reload.
        let current = store.orderedFolderTracks(root: LibraryRoot.appFolderID, path: "Mix", history: history).map(\.id)
        store.moveFolderTracks(root: LibraryRoot.appFolderID, path: "Mix", currentTrackIDs: current, from: IndexSet(integer: 2), to: 0)
        XCTAssertEqual(store.folderSortOrder(root: LibraryRoot.appFolderID, path: "Mix"), .custom)
        XCTAssertEqual(store.orderedFolderTracks(root: LibraryRoot.appFolderID, path: "Mix", history: history).map(\.id), [zeta.id, beta.id, alpha.id])

        let reloaded = makeLibrary()
        XCTAssertEqual(reloaded.folderSortOrder(root: LibraryRoot.appFolderID, path: "Mix"), .custom)
        XCTAssertEqual(reloaded.orderedFolderTracks(root: LibraryRoot.appFolderID, path: "Mix", history: history).map(\.id), [zeta.id, beta.id, alpha.id])

        // Playlist sorting & reorder persistence
        let pid = store.createPlaylist(name: "My List")
        store.add([zeta.id, alpha.id, beta.id], toPlaylist: pid)
        store.sortPlaylist(pid, by: .nameAscending, history: history)
        XCTAssertEqual(store.playlists.first { $0.id == pid }?.trackIDs, [alpha.id, beta.id, zeta.id])
        XCTAssertEqual(store.playlists.first { $0.id == pid }?.sortOrder, .nameAscending)

        store.movePlaylistTracks(pid, from: IndexSet(integer: 0), to: 3)
        XCTAssertEqual(store.playlists.first { $0.id == pid }?.trackIDs, [beta.id, zeta.id, alpha.id])
        XCTAssertEqual(store.playlists.first { $0.id == pid }?.sortOrder, .custom)

        let reloadedPlaylists = makeLibrary()
        XCTAssertEqual(reloadedPlaylists.playlists.first { $0.id == pid }?.trackIDs, [beta.id, zeta.id, alpha.id])
        XCTAssertEqual(reloadedPlaylists.playlists.first { $0.id == pid }?.sortOrder, .custom)
    }

    // MARK: 5. Auto-Create/Replace Queue on New Folder

    func testAutoCreateAndReplaceExactFolderQueueWithoutDisruptingOtherActiveQueue() async throws {
        let fixture = try XCTUnwrap(Bundle(for: FeaturesV115Tests.self).url(forResource: "sample", withExtension: "mp3"))
        try FileManager.default.copyItem(at: fixture, to: docs.appendingPathComponent("existing.mp3"))

        let store = makeLibrary()
        await store.rescan()

        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        let player = PlayerController(
            library: store,
            queues: queues,
            history: history,
            engine: AudioEngine(),
            settingsURL: state.appendingPathComponent("settings.json")
        )
        defer { player.shutdown() }

        let existingID = try XCTUnwrap(store.tracks.first?.id)
        player.play([existingID], startAt: 0)
        let originalQueueID = queues.activeID
        XCTAssertEqual(player.currentTrack?.id, existingID)

        // Adding a local picked folder named "rock_songs" with a child folder "disc1" creates queue named "rock_songs" (never parent or child).
        let parentDir = makeTempDir()
        let rockDir = parentDir.appendingPathComponent("rock_songs")
        touch("lead.mp3", in: rockDir)
        touch("disc1/deep.mp3", in: rockDir)

        try await store.addRoot(url: rockDir)

        let rockQueue = try XCTUnwrap(queues.queues.first { $0.name == "rock_songs" })
        XCTAssertEqual(rockQueue.trackIDs.count, 2)
        XCTAssertFalse(queues.queues.contains { $0.name == parentDir.lastPathComponent })
        XCTAssertFalse(queues.queues.contains { $0.name == "disc1" })
        // Active queue & playback were NOT interrupted.
        XCTAssertEqual(queues.activeID, originalQueueID)
        XCTAssertEqual(player.currentTrack?.id, existingID)

        // Adding a new folder inside appFolder creates a queue with its exact folder name.
        touch("jazz_hits/smooth.mp3")
        await store.rescan()
        let jazzQueue = try XCTUnwrap(queues.queues.first { $0.name == "jazz_hits" })
        XCTAssertEqual(jazzQueue.trackIDs.count, 1)
        XCTAssertEqual(queues.activeID, originalQueueID)

        // Replacing an existing queue with the same exact name replaces tracks in place (no duplicates).
        let existingQueueCount = queues.queues.count
        let secondRockParent = makeTempDir()
        let secondRockDir = secondRockParent.appendingPathComponent("rock_songs")
        touch("new_anthem.mp3", in: secondRockDir)
        try await store.addRoot(url: secondRockDir)

        XCTAssertEqual(queues.queues.filter { $0.name == "rock_songs" }.count, 1)
        XCTAssertEqual(queues.queues.count, existingQueueCount)
        let replacedRock = try XCTUnwrap(queues.queues.first { $0.name == "rock_songs" })
        XCTAssertEqual(replacedRock.trackIDs.count, 1)

        // Playing a folder/subfolder uses its exact folder name and replaces any same-name queue.
        let disc1IDs = store.orderedTracks(under: store.roots.first(where: { $0.name == "rock_songs" && $0.id != store.roots.last?.id })!.id, path: "disc1").map(\.id)
        player.playInNamedQueue(disc1IDs, name: "disc1")
        XCTAssertEqual(queues.active.name, "disc1")
        XCTAssertEqual(queues.active.trackIDs, disc1IDs)

        player.playInNamedQueue([existingID], name: "disc1")
        XCTAssertEqual(queues.queues.filter { $0.name == "disc1" }.count, 1)
        XCTAssertEqual(queues.active.name, "disc1")
        XCTAssertEqual(queues.active.trackIDs, [existingID])
    }

    func testAddCloudRootCreatesQueueWithExactCloudFolderName() async throws {
        let fetcher = CloudRootTestFetcher(result: CloudPlaylistResult(
            kind: .cloudGoogleDrive,
            remoteID: "fld99",
            title: "City Pop Mix",
            tracks: [
                CloudTrackInfo(remoteTrackID: "v1", relativePath: "v1.flac", title: "Plastic Love", artist: "Mariya", album: "Variety", trackNumber: 1, duration: 240, artworkURL: nil)
            ]
        ))
        let store = LibraryStore(
            directory: state,
            documentsURL: docs,
            reader: reader,
            artwork: ArtworkCache(directory: state.appendingPathComponent("Artwork")),
            cloudFetcher: fetcher,
            artworkDownloader: { _ in nil }
        )
        let queues = QueueStore(directory: state)
        let history = PlayHistoryStore(directory: state)
        let player = PlayerController(
            library: store,
            queues: queues,
            history: history,
            engine: AudioEngine(),
            settingsURL: state.appendingPathComponent("settings.json")
        )
        defer { player.shutdown() }

        let initialActive = queues.activeID
        try await store.addCloudRoot(sourceURL: "https://drive.google.com/drive/folders/fld99")

        let cloudQueue = try XCTUnwrap(queues.queues.first { $0.name == "City Pop Mix" })
        XCTAssertEqual(cloudQueue.trackIDs.count, 1)
        XCTAssertEqual(queues.activeID, initialActive)
    }
}

private struct CloudRootTestFetcher: CloudPlaylistFetching {
    let result: CloudPlaylistResult
    func fetchPlaylist(sourceURL: String, cookie: String?, kindHint: LibraryRoot.Kind?) async throws -> CloudPlaylistResult {
        result
    }
}
