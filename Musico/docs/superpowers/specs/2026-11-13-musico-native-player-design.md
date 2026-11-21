# Musico — native iOS local music player (design)

Date: 2026-11-13. Status: awaiting review.

## Goal

A local-file music player for iPhone in the spirit of Musicolet: offline, folder-aware,
multiple queues. It replaces the abandoned Expo project in `../MyMusicPlayer/`, which failed
because its picker copied single files into a cache and never saw the folder structure.

Success means: the user picks a folder in Files once, the app shows that folder tree and a
tag-based library, plays the files in place (including in the background and from the lock
screen), and still has access after a relaunch.

## Decisions made with the user

- Native SwiftUI rewrite; the Expo project is left untouched.
- First version includes: folder browsing, Songs / Albums / Artists / Folders / Playlists,
  favourites, search, background playback, lock-screen controls, shuffle / repeat, resume,
  multiple queues, sleep timer, lyrics (embedded, sidecar, and imported from a chosen file),
  equalizer.
- Out of scope: OGG / Opus, tag editing, gapless playback, CarPlay, Android, iPad-specific layout.

## Platform

- `musicplayer/Musico/`, Xcode project `Musico.xcodeproj`, app target `Musico`, test target `MusicoTests`.
- Swift, SwiftUI, Observation (`@Observable`), iOS 17.0 minimum. No third-party packages.
- Bundle id `com.anonymous.Musico` (placeholder; the user sets their signing team in Xcode).
- Info.plist: `UIBackgroundModes = [audio]`, `UIFileSharingEnabled = YES`,
  `LSSupportsOpeningDocumentsInPlace = YES`.

## File access

Two kinds of library root, both scanned recursively and played in place:

1. **Picked folder** — chosen with the system folder picker (`.fileImporter`, `UTType.folder`).
   The app stores a security-scoped bookmark for it. At launch each bookmark is resolved and
   `startAccessingSecurityScopedResource()` is held for the life of the process. A stale
   bookmark is re-created; a root that cannot be resolved is kept in the list, shown as
   "unavailable", and its tracks are greyed out rather than deleted.
2. **App folder** — the app's `Documents` directory, always present, visible in Files and Finder.

Supported extensions: `mp3 m4a aac flac wav aif aiff caf alac`. Hidden files and
`.Trash`-style directories are skipped.

## Data model (all `Codable`, persisted as JSON in Application Support)

- `LibraryRoot { id: UUID, name, kind: .picked | .appFolder, bookmark: Data? }`
- `Track { id: String, rootID: UUID, relativePath: String, title, artist, album, albumArtist,
  trackNumber: Int?, discNumber: Int?, duration: TimeInterval, fileSize: Int64,
  modified: Date, hasEmbeddedLyrics: Bool, dateAdded: Date }`
  - `id` = SHA-256 of `rootID + "/" + relativePath`, so it is stable across launches.
  - The file URL is derived at runtime from the resolved root URL + `relativePath`; absolute
    paths are never stored (the sandbox container path changes between installs).
- `Playlist { id: UUID, name, trackIDs: [String] }`; favourites are a `Set<String>` of track ids.
- `PlayQueue { id: UUID, name, trackIDs: [String], currentIndex: Int?, position: TimeInterval,
  shuffle: Bool, repeatMode: .off | .all | .one, shuffleOrder: [Int] }`
- `EQSettings { enabled: Bool, preamp: Float, gains: [Float] (10 bands), presetName: String? }`

Files: `library.json` (roots + tracks), `playlists.json` (playlists + favourites),
`queues.json` (queues + active queue id), `settings.json` (EQ). Writes are atomic and debounced.

## Components

| Unit | Responsibility | Depends on |
|---|---|---|
| `FolderScanner` | Enumerate a root URL, return audio files as relative path + size + mtime | Foundation |
| `MetadataReader` | Read tags, duration, artwork data, embedded lyrics from one file | AVFoundation |
| `ArtworkCache` | Downscale artwork to a 600 px JPEG on disk keyed by track id; in-memory `NSCache` | UIKit |
| `LibraryStore` | Roots, tracks, playlists, favourites; add/remove root; incremental rescan; derived albums/artists/folder tree; search | the three above, `Persistence` |
| `PlayQueue` (value type) | Pure queue logic: next / previous / shuffle order / insert next / append / remove / move | none |
| `QueueStore` | The set of queues and which is active; persistence | `PlayQueue`, `Persistence` |
| `AudioEngine` | `AVAudioEngine` + `AVAudioPlayerNode` + `AVAudioUnitEQ`; load, play, pause, seek, current time, end-of-track callback, EQ | AVFoundation |
| `PlayerController` | Glue: drives `AudioEngine` from the active queue, audio session, interruptions, route changes, Now Playing info, remote commands, sleep timer, position saving | `AudioEngine`, `QueueStore`, `LibraryStore` |
| `LRCParser` | Parse `.lrc` text into timed lines; plain text falls back to untimed lines | none |
| `LyricsStore` | Resolve lyrics for a track and import a chosen file | `LRCParser` |
| `Persistence` | Atomic JSON load/save helpers | Foundation |
| Views | SwiftUI screens, below | stores via environment |

### Scanning

A rescan enumerates each available root and diffs against the index by
`(relativePath, fileSize, modified)`: unchanged files keep their record, new or changed files go
through `MetadataReader`, vanished files are removed (and dropped from playlists and queues).
Scanning runs off the main actor with progress reported to the UI. It runs when a root is
added, on pull-to-refresh, and from a "Rescan" button. Missing tags fall back to the file name
for the title, and "Unknown Artist" / the parent folder name for artist / album.

### Playback

`AudioEngine` opens the file with `AVAudioFile` and schedules it on the player node with
`scheduleSegment`; seeking stops the node and reschedules from the target frame. Current time is
`seekOffset + playerTime.sampleTime / sampleRate`. Each schedule carries a generation token so a
completion callback from a superseded schedule is ignored. The node connects to the EQ with the
file's processing format, so changing sample rate between tracks just reconnects.

Graph: `playerNode → AVAudioUnitEQ (10 bands: 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz,
±12 dB, plus globalGain as preamp) → mainMixer`. Presets: Flat, Bass Boost, Treble Boost, Rock,
Pop, Jazz, Classical, Vocal; any manual change becomes "Custom".

`PlayerController`:
- Audio session category `.playback`. On interruption began → pause; on ended with
  `shouldResume` → resume. Headphones unplugged → pause.
- Remote commands: play, pause, toggle, next, previous, change playback position.
  Now Playing info: title, artist, album, artwork, duration, elapsed, rate.
- End of track: repeat-one replays; otherwise advance; at queue end with repeat off, stop at the
  start of the last track.
- "Previous" restarts the track if more than 3 s in, otherwise goes back.
- A file that fails to open is skipped; after 3 consecutive failures playback stops and an error
  banner is shown.
- Position is saved every 5 s while playing, on pause, and when the app goes to the background.
  On launch the active queue is restored paused at its saved track and position.

### Multiple queues

There is always at least one queue. Tapping a song in a list replaces the **active** queue's
contents with that list (the album, folder, playlist, or search results) starting at the tapped
song. The context menu on a song, album, artist, folder or playlist offers Play Next, Add to
Queue, Add to Another Queue…, and Play in New Queue. Switching queues saves the outgoing queue's
position and loads the incoming queue's track at its saved position, keeping the play/pause
state. Queues can be renamed, cleared, and deleted (not the last one). Shuffle and repeat are
per-queue. Shuffle keeps the current track first and randomises the rest.

### Sleep timer

Options: 5, 10, 15, 30, 45, 60, 90 minutes, a custom number of minutes, or "end of current
track". When it fires, playback pauses. The remaining time is shown on the player; the timer can
be cancelled. Not persisted across launches.

### Lyrics

Lookup order for a track: (1) file imported for that track, stored as
`Application Support/Lyrics/<trackID>.lrc`; (2) a sibling file with the same base name and
extension `.lrc` or `.txt`; (3) embedded lyrics tag. Imported lyrics come from the system file
picker restricted to `.lrc` / plain text, and can be removed again. Text is decoded as UTF-8,
falling back to UTF-16 and then GB18030. `LRCParser` handles `[mm:ss]`, `[mm:ss.xx]`,
`[mm:ss.xxx]`, several timestamps on one line, and ignores metadata tags like `[ar:…]`. Timed
lyrics highlight and auto-scroll to the current line, and tapping a line seeks to it; untimed
lyrics are a plain scrollable text.

## Screens

- **Tab bar**: Folders · Songs · Albums · Artists · Playlists. A mini player sits above the tab
  bar whenever the active queue has a current track.
- **Folders**: list of roots with an "Add Folder" button and an explanation when empty; drill
  down through subfolders; each level lists subfolders then songs; "Play all" / "Shuffle".
  Swipe a root to remove it (files are not deleted).
- **Songs**: all tracks, sortable by title / artist / album / date added, with search.
- **Albums**: artwork grid → album detail ordered by disc and track number.
- **Artists**: list → that artist's albums and songs.
- **Playlists**: Favourites (built in) plus user playlists; create, rename, delete, reorder,
  remove songs.
- **Full player** (sheet from the mini player): artwork, title / artist, seek bar with times,
  previous / play-pause / next, shuffle, repeat, favourite, and buttons for Queue, Lyrics,
  Equalizer, Sleep Timer.
- **Queue sheet**: queue switcher at the top (add / rename / delete), then the active queue's
  tracks with reorder, swipe-to-remove, tap-to-jump.
- **Lyrics view**, **Equalizer sheet** (toggle, preset menu, preamp + 10 sliders, reset),
  **Sleep timer sheet**.

Follows the system light / dark appearance with a single accent colour.

## Error handling

- Unavailable root: shown with a warning and a "Locate again" action that re-opens the picker.
- Unreadable or unsupported file during scan: indexed by file name with zero duration; if it
  also fails to play it is skipped as described above.
- Corrupt JSON store: the bad file is renamed to `*.corrupt` and the app starts with an empty
  store rather than crashing.

## Testing

- Unit tests (`MusicoTests`): `PlayQueue` (next / previous / repeat modes / shuffle invariants /
  insert-next / remove / move keeping the current track), `LRCParser`, `FolderScanner` against a
  temporary directory tree, `LibraryStore` diffing on rescan, `Persistence` round-trip and
  corrupt-file recovery.
- Simulator check: build, copy the sample MP3 from the old project into the app folder, confirm
  it is scanned, plays, seeks, shows in the Now Playing state, and survives a relaunch.
- Not verifiable in the Simulator: real lock-screen behaviour, headphone buttons, picking
  folders from iCloud Drive / USB, and device signing. These need a run on the user's iPhone.
