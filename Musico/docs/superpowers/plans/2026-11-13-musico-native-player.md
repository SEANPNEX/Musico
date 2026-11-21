# Musico Native Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native SwiftUI iOS music player that plays local files in place from user-picked folders, with a tag library, multiple queues, lyrics, equalizer and sleep timer.

**Architecture:** One app target. Pure value-type logic (`PlayQueue`, `LRCParser`, `FolderScanner`, `Persistence`) is unit-tested; `@Observable` stores (`LibraryStore`, `QueueStore`, `PlayerController`) own state and are injected into SwiftUI views through the environment. Playback is `AVAudioEngine → AVAudioPlayerNode → AVAudioUnitEQ → mainMixer`.

**Tech Stack:** Swift 5 language mode, SwiftUI, Observation, AVFoundation, MediaPlayer, CryptoKit, XCTest. No packages.

**Spec:** `docs/superpowers/specs/2026-11-13-musico-native-player-design.md` — read it first; this plan does not repeat its behaviour rules.

## Global Constraints

- iOS 17.0 deployment target, iPhone only (`TARGETED_DEVICE_FAMILY = 1`), portrait.
- No third-party dependencies.
- Bundle id `com.anonymous.Musico`; test bundle `com.anonymous.MusicoTests`. No development team set.
- Info.plist keys (via `INFOPLIST_KEY_*` build settings where possible, otherwise `Musico/Info.plist`): `UIBackgroundModes = [audio]`, `UIFileSharingEnabled = YES`, `LSSupportsOpeningDocumentsInPlace = YES`.
- Absolute file paths are never persisted; only `rootID` + `relativePath`.
- Supported extensions, lowercased: `mp3 m4a aac flac wav aif aiff caf alac`.
- All stores are `@MainActor @Observable final class`.
- Build: `xcodebuild -project Musico.xcodeproj -scheme Musico -destination 'platform=iOS Simulator,name=iPhone 17' build`
- Test: same with `test` instead of `build`. Every task ends with tests green and a commit.

## Review Focus

1. File or folder names with non-ASCII characters (Chinese/Japanese titles, as in the sample file) and characters like `[ ] # %` — relative paths must round-trip to a playable URL. Test in Task 2.
2. Queue mutations while a track is current (remove the current track, remove one before it, move it) — `currentIndex` must keep pointing at the same track or the sensible successor. Test in Task 3.
3. LRC files that are not UTF-8 (GB18030, UTF-16 with BOM) or have CRLF line endings. Test in Task 4.
4. A track whose file vanished after indexing (folder removed, iCloud file evicted) — playback skips rather than hangs; rescan drops it from queues and playlists. Test in Task 5 (rescan) and manual check in Task 10.
5. Seeking to or past the end, and seeking while paused — must not crash on a zero-length segment and must stay paused. Guard in Task 6; manual check in Task 10.

## File Structure

```
Musico.xcodeproj/project.pbxproj      hand-written, objectVersion 77, file-system-synchronized groups
Musico/
  MusicoApp.swift                     @main, creates stores, injects environment
  Info.plist                          UIBackgroundModes only
  Assets.xcassets/                    AccentColor, AppIcon (placeholder)
  Models/Models.swift                 LibraryRoot, Track, Playlist, RepeatMode, EQSettings, EQPreset
  Models/PlayQueue.swift              queue value type
  Core/Persistence.swift
  Core/FolderScanner.swift
  Core/MetadataReader.swift
  Core/ArtworkCache.swift
  Core/LRCParser.swift
  Core/LyricsStore.swift
  Core/AudioEngine.swift
  Stores/LibraryStore.swift
  Stores/QueueStore.swift
  Stores/PlayerController.swift
  Views/RootView.swift                TabView + mini player
  Views/Library/{FoldersView,SongsView,AlbumsView,ArtistsView,PlaylistsView}.swift
  Views/Library/{TrackRow,TrackContextMenu,ArtworkView}.swift
  Views/Player/{MiniPlayerView,FullPlayerView,QueueSheet,LyricsView,EqualizerSheet,SleepTimerSheet}.swift
MusicoTests/                          one test file per tested unit
```

---

### Task 1: Project skeleton, models, persistence

**Files:** Create `Musico.xcodeproj/project.pbxproj`, `Musico/MusicoApp.swift`, `Musico/Info.plist`, `Musico/Assets.xcassets`, `Musico/Models/Models.swift`, `Musico/Core/Persistence.swift`, `MusicoTests/PersistenceTests.swift`.

**Produces:**
- Model types exactly as in the spec's "Data model" section, all `Codable, Hashable, Sendable`; `Track: Identifiable`. `enum RepeatMode: String, Codable { case off, all, one }`. `Track.makeID(rootID: UUID, relativePath: String) -> String` (SHA-256 hex).
- `EQSettings.frequencies: [Float] = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]`; `EQPreset { name: String, gains: [Float] }`; `EQPreset.all` with the eight spec presets.
- `enum Persistence { static func load<T: Decodable>(_ type: T.Type, from url: URL) -> T?; static func save<T: Encodable>(_ value: T, to url: URL) throws; static var supportDirectory: URL }`. `load` returns nil for a missing file; for undecodable data it renames the file to `<name>.corrupt` and returns nil. `save` writes atomically, creating parent directories.

- [ ] Write `PersistenceTests`: `testRoundTrip` (save a `[Track]`, load equals), `testMissingFileReturnsNil`, `testCorruptFileIsRenamed` (write `"{"`, load returns nil, `x.json.corrupt` exists, `x.json` does not), `testTrackIDIsStable` (same inputs → same 64-char id; different path → different id).
- [ ] Write the pbxproj with two targets and a shared scheme; `MusicoApp` shows `Text("Musico")`.
- [ ] Run test command; expect compile failure for missing types, then implement, then PASS.
- [ ] Commit `feat: project skeleton, models, persistence`.

### Task 2: FolderScanner

**Files:** Create `Musico/Core/FolderScanner.swift`, `MusicoTests/FolderScannerTests.swift`.

**Produces:** `struct ScannedFile: Hashable, Sendable { relativePath: String; fileSize: Int64; modified: Date }`; `enum FolderScanner { static let audioExtensions: Set<String>; static func scan(root: URL) -> [ScannedFile]; static func url(root: URL, relativePath: String) -> URL }`. `relativePath` uses `/` separators, no leading slash, NFC-normalised. Skips hidden files and packages. Result sorted by `relativePath` with `localizedStandardCompare`.

- [ ] Tests on a temp tree: `testFindsNestedAudioOnly` (`a.mp3`, `sub/b.FLAC`, `sub/deep/c.m4a`, `notes.txt`, `.hidden.mp3` → exactly the three audio paths); `testUnicodeAndSpecialNamesRoundTrip` (`日本語/曲 [live] #1 100%.mp3` → `url(root:relativePath:)` exists on disk); `testEmptyAndMissingRoot` (both return `[]`).
- [ ] Verify fail → implement with `FileManager.enumerator(at:includingPropertiesForKeys:options:[.skipsHiddenFiles, .skipsPackageDescendants])` → PASS → commit.

### Task 3: PlayQueue

**Files:** Create `Musico/Models/PlayQueue.swift`, `MusicoTests/PlayQueueTests.swift`.

**Produces** (`struct PlayQueue: Codable, Identifiable, Hashable`, fields per spec):
```swift
var currentTrackID: String? { get }
mutating func replace(with ids: [String], startAt index: Int)   // reshuffles if shuffle is on
mutating func next(auto: Bool) -> String?    // auto=true honours .one; returns nil at end with .off (index unchanged)
mutating func previous() -> String?          // wraps only with .all; stays at first otherwise
mutating func jump(to index: Int)
mutating func insertNext(_ ids: [String])
mutating func append(_ ids: [String])
mutating func remove(at index: Int)
mutating func move(from: IndexSet, to: Int)
mutating func setShuffle(_ on: Bool, using rng: inout some RandomNumberGenerator)
mutating func removeTracks(notIn valid: Set<String>)
var upcomingIndices: [Int] { get }           // play order after the current track
```
`shuffleOrder` is a permutation of `trackIDs.indices` with the current index first; `next`/`previous` walk it when `shuffle` is on. Inserted/appended tracks are added to `shuffleOrder` right after the current position / at the end.

- [ ] Tests: `testNextAdvancesAndStopsAtEnd`, `testRepeatAllWraps`, `testRepeatOneAutoRepeatsButManualNextAdvances`, `testPreviousAtStartStays`, `testShuffleOrderIsPermutationStartingAtCurrent`, `testShuffleVisitsEveryTrackOnce`, `testInsertNextPlaysNext` (also with shuffle on), `testRemoveBeforeCurrentKeepsCurrentTrack`, `testRemoveCurrentSelectsSuccessor`, `testRemoveLastRemainingClearsCurrent`, `testMoveKeepsCurrentTrack`, `testRemoveTracksNotInValidSet`, `testEmptyQueueOperationsAreSafe`, `testCodableRoundTrip`.
- [ ] Fail → implement → PASS → commit.

### Task 4: LRCParser and LyricsStore

**Files:** Create `Musico/Core/LRCParser.swift`, `Musico/Core/LyricsStore.swift`, `MusicoTests/LRCParserTests.swift`, `MusicoTests/LyricsStoreTests.swift`.

**Produces:**
- `struct LyricLine: Hashable, Identifiable { id: Int; time: TimeInterval?; text: String }`; `struct Lyrics: Hashable { lines: [LyricLine]; isTimed: Bool; func lineIndex(at time: TimeInterval) -> Int? }`.
- `enum LRCParser { static func parse(_ text: String) -> Lyrics; static func decode(_ data: Data) -> String? }` — decode tries UTF-8, UTF-16 (BOM), GB18030.
- `enum LyricsSource { case imported, sidecar, embedded }`; `struct LyricsStore { init(directory: URL); func lyrics(trackID: String, fileURL: URL?, embedded: String?) -> (Lyrics, LyricsSource)?; func importLyrics(from url: URL, trackID: String) throws; func removeImported(trackID: String); func hasImported(trackID: String) -> Bool }`. `importLyrics` uses security-scoped access on `url` and rejects undecodable data with an error.

- [ ] Parser tests: `[00:12.50]Hello` → 12.5; `[01:02]`, `[01:02.345]`; `[00:10.00][00:20.00]Chorus` → two lines; `[ar:Someone]`/`[offset:…]` ignored; output sorted by time; CRLF input; plain text → `isTimed == false`, one line per non-empty line; `lineIndex(at:)` before first line → nil, between lines → earlier one; GB18030 and UTF-16 data decode to the expected Chinese string.
- [ ] Store tests (temp dirs): imported beats sidecar beats embedded; `.lrc` preferred over `.txt` sidecar; remove falls back; nothing available → nil.
- [ ] Fail → implement → PASS → commit.

### Task 5: MetadataReader, ArtworkCache, LibraryStore

**Files:** Create `Musico/Core/MetadataReader.swift`, `Musico/Core/ArtworkCache.swift`, `Musico/Stores/LibraryStore.swift`, `MusicoTests/LibraryStoreTests.swift`, `MusicoTests/Fixtures/sample.mp3` (copy of `../MyMusicPlayer/assets/audio/*.mp3`, renamed).

**Produces:**
- `struct TrackMetadata { title, artist, album, albumArtist: String?; trackNumber, discNumber: Int?; duration: TimeInterval; artwork: Data?; lyrics: String? }`; `protocol MetadataReading: Sendable { func read(_ url: URL) async -> TrackMetadata }`; `struct MetadataReader: MetadataReading` using `AVURLAsset` async `load(.commonMetadata, .metadata, .duration)`, checking common keys, then ID3 (`TRCK`, `TPOS`, `USLT`, `TPE2`) and iTunes equivalents.
- `final class ArtworkCache { init(directory: URL); func store(_ data: Data, for trackID: String); func image(for trackID: String) -> UIImage?; func remove(_ trackID: String) }` — 600 px max JPEG, `NSCache` in front.
- `LibraryStore`:
```swift
init(directory: URL, documentsURL: URL, reader: MetadataReading, artwork: ArtworkCache)
private(set) var roots: [LibraryRoot]; private(set) var tracks: [Track]
private(set) var isScanning: Bool; private(set) var scanProgress: (done: Int, total: Int)
var playlists: [Playlist]; var favourites: Set<String>
var onTracksRemoved: ((Set<String>) -> Void)?
func track(_ id: String) -> Track?            // dictionary lookup
func url(for track: Track) -> URL?            // nil if root unavailable
func isAvailable(_ root: LibraryRoot) -> Bool
func addRoot(url: URL) async throws           // bookmark + scan; rejects duplicates
func relocateRoot(_ id: UUID, to url: URL) async throws
func removeRoot(_ id: UUID)
func rescan() async
struct Album: Identifiable, Hashable { id: String; title; artist; trackIDs: [String] }   // sorted disc, track, title
var albums: [Album] { get }; var artists: [String] { get }
func folderContents(root: UUID, path: String) -> (folders: [String], tracks: [Track])
func tracks(under root: UUID, path: String) -> [Track]   // recursive
func search(_ query: String) -> [Track]       // case/diacritic-insensitive on title, artist, album
func toggleFavourite(_ id: String); playlist CRUD: createPlaylist(name:), renamePlaylist, deletePlaylist, add(_ ids:toPlaylist:), remove/move within playlist
```
The app-folder root has a fixed UUID and is created on first launch. Rescan diffing and fallbacks per spec "Scanning". Derived collections are cached and invalidated when `tracks` changes.

- [ ] Tests with a stub `MetadataReading` and temp directories as the documents root: `testInitialScanIndexesFiles`; `testRescanKeepsUnchangedAndCountsReaderCalls` (second scan calls reader 0 times); `testRescanPicksUpNewAndRemovesDeleted` (and `onTracksRemoved` receives the id; id also gone from a playlist and favourites); `testFallbackTitleIsFileName`; `testFolderContentsListsSubfoldersAndTracks`; `testAlbumsSortedByDiscAndTrack`; `testSearchIsDiacriticInsensitive`; `testStatePersistsAcrossInstances`. One test with the real `MetadataReader` on the fixture: `duration > 0`.
- [ ] Fail → implement → PASS → commit.

### Task 6: AudioEngine

**Files:** Create `Musico/Core/AudioEngine.swift`, `MusicoTests/AudioEngineTests.swift`.

**Produces:**
```swift
@MainActor final class AudioEngine {
  var onTrackEnded: (() -> Void)?
  private(set) var duration: TimeInterval; private(set) var isPlaying: Bool
  var currentTime: TimeInterval { get }
  func load(url: URL) throws            // stops current, opens AVAudioFile, reconnects with its format; stays paused at 0
  func play(); func pause()
  func seek(to time: TimeInterval)      // clamps to [0, duration]; keeps play/pause state; at end → fires onTrackEnded if playing
  func unload()
  func apply(_ eq: EQSettings)          // bypass when !enabled; band i: .parametric, bandwidth 1.0 octave, gain clamped ±12
}
```
Implementation notes that the signature does not determine: keep `seekFrame` and a `generation` counter; `scheduleSegment(... completionCallbackType: .dataPlayedBack)` hops to the main actor and fires `onTrackEnded` only if its captured generation is current; never schedule a segment with `frameCount == 0`; `currentTime` while paused returns the stored position (player node time is nil when stopped); restart the engine on `AVAudioEngineConfigurationChange`.

- [ ] Tests on the fixture: `testLoadReportsDuration`; `testSeekWhilePausedUpdatesCurrentTimeAndStaysPaused`; `testSeekPastEndClampsWithoutCrash`; `testLoadMissingFileThrows`; `testApplyEQDoesNotThrowForAllPresets`. (Audible output is checked manually in Task 10.)
- [ ] Fail → implement → PASS → commit.

### Task 7: QueueStore and PlayerController

**Files:** Create `Musico/Stores/QueueStore.swift`, `Musico/Stores/PlayerController.swift`, `MusicoTests/QueueStoreTests.swift`.

**Produces:**
- `QueueStore`: `init(directory: URL)`; `private(set) var queues: [PlayQueue]`; `private(set) var activeID: UUID`; `var active: PlayQueue { get }`; `func updateActive(_ body: (inout PlayQueue) -> Void)`; `func update(_ id: UUID, _ body: (inout PlayQueue) -> Void)`; `@discardableResult func addQueue(name: String?) -> UUID` (default name "Queue N"); `func rename(_:to:)`; `func delete(_ id: UUID)` (no-op for the last queue; deleting the active one activates its neighbour); `func setActive(_ id: UUID)`; `func removeTracks(_ ids: Set<String>)`; `func save()`. Always ≥ 1 queue, first named "Queue 1".
- `PlayerController`:
```swift
init(library: LibraryStore, queues: QueueStore, engine: AudioEngine, settingsURL: URL)
private(set) var currentTrack: Track?; private(set) var isPlaying: Bool
private(set) var duration: TimeInterval; var currentTime: TimeInterval   // refreshed by a 0.25 s timer while playing
private(set) var errorMessage: String?
var eq: EQSettings                           // didSet → engine.apply + save
enum SleepMode: Equatable { case off, until(Date), endOfTrack }
private(set) var sleepMode: SleepMode
func play(_ ids: [String], startAt: Int)     // replaces active queue
func playInNewQueue(_ ids: [String]); func playNext(_ ids: [String]); func addToQueue(_ ids: [String], queue: UUID?)
func togglePlayPause(); func next(); func previous(); func seek(to: TimeInterval)
func jump(to index: Int); func switchQueue(to id: UUID)
func toggleShuffle(); func cycleRepeat()
func setSleep(_ mode: SleepMode)
func restore()                               // load active queue's track paused at saved position
func persistPosition()
```
Behaviour per spec "Playback", "Multiple queues", "Sleep timer". Audio session, interruption/route observers, `MPRemoteCommandCenter` and `MPNowPlayingInfoCenter` live here. `library.onTracksRemoved` is wired to `queues.removeTracks` and, if the current track was removed, to stop.

- [ ] `QueueStoreTests`: `testStartsWithOneQueue`; `testCannotDeleteLastQueue`; `testDeletingActiveActivatesNeighbour`; `testEachQueueKeepsOwnIndexAndPosition`; `testPersistsAcrossInstances`; `testRemoveTracksAppliesToAllQueues`.
- [ ] Fail → implement QueueStore → PASS. Implement PlayerController; build succeeds.
- [ ] Commit.

### Task 8: Library screens

**Files:** Create `Views/RootView.swift`, `Views/Library/*` (see File Structure); modify `MusicoApp.swift` to build the stores, call `player.restore()`, and inject them with `.environment(_:)`; save on `scenePhase == .background`.

**Consumes:** `LibraryStore`, `PlayerController`, `ArtworkCache` APIs above.

Decisions: `TrackContextMenu` is one `ViewModifier` taking `[String]` track ids, used for songs, albums, artists, folders and playlists, offering Play Next / Add to Queue / Add to Another Queue… (submenu of queues) / Play in New Queue / Add to Playlist… / Favourite (single track only). Folders tab uses `.fileImporter(allowedContentTypes: [.folder])`. Empty state text: "Add a folder from Files, or copy music into the Musico folder in Files › On My iPhone." Songs sort order stored in `@AppStorage("songSort")`. Unavailable tracks render at 40 % opacity and are not tappable. Mini player is attached with `.safeAreaInset(edge: .bottom)` on each tab's `NavigationStack` so lists scroll under it correctly.

- [ ] Implement; build succeeds; commit.

### Task 9: Player screens

**Files:** Create `Views/Player/*`.

Decisions: full player is a `.sheet` with `.presentationDragIndicator(.visible)`; seek slider updates a local value while dragging and calls `seek` on release; Queue, Lyrics, Equalizer and Sleep Timer are sheets from the full player. Lyrics view has a toolbar menu: Import from File… (`.fileImporter`, types `.plainText` + `UTType(filenameExtension: "lrc")`), Remove Imported Lyrics, and shows the source. Equalizer sliders are vertical, −12…+12 dB, labelled `31 62 125 250 500 1K 2K 4K 8K 16K`; preamp −12…+12. Sleep timer presets `5, 10, 15, 30, 45, 60, 90` minutes + custom stepper + End of Track + Cancel.

- [ ] Implement; build succeeds; commit.

### Task 10: Simulator verification

- [ ] Run the full test suite; all green.
- [ ] Install on the iPhone 17 simulator; copy the fixture MP3 plus a hand-written `.lrc` into the app's Documents (`xcrun simctl get_app_container booted com.anonymous.Musico data`), in a subfolder.
- [ ] Verify by driving the simulator: Folders shows the subfolder and song; tapping plays; time advances; seek works; seek while paused stays paused; next at end of a one-song queue stops; lyrics show and highlight; EQ toggles without a glitch; create a second queue and switch back — position is kept; set a 1-minute custom sleep timer and confirm it pauses; delete the file on disk, rescan, and confirm the song leaves the queue; relaunch and confirm the queue and position are restored.
- [ ] Fix anything found, re-run tests, commit. Report what could not be verified in the Simulator (spec "Testing", last bullet).
