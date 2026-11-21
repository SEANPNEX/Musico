# Musico for Android

Native Android music player built with Kotlin, Jetpack Compose (Material 3 / Material You), and AndroidX Media3 (ExoPlayer + `MediaSessionService`).

## Architecture

- **`com.musico.model`**
  - `Models.kt`: Immutable serializable domain models (`LibraryRoot`, `Track`, `Album`, `Playlist`, `RepeatMode`, `EqSettings`, `EqPreset`, `LyricLine`, `Lyrics`, `LyricsSource`, `PlayHistoryEntry`, `SleepMode`).
  - `PlayQueue.kt`: Pure immutable queue state machine with shuffle permutations, repeat modes (`OFF`, `ALL`, `ONE`), insert-next, reorder, and missing-track pruning.
- **`com.musico.core`**
  - `Persistence.kt`: Atomic temp-write + move JSON persistence via `kotlinx.serialization` with `.corrupt` backup recovery.
  - `FolderScanner.kt`: Recursive audio scanner supporting both the local app Music directory and Android Storage Access Framework (`DocumentFile` / `DocumentsContract`) trees, with NFC path normalization.
  - `FlacTags.kt`: Native FLAC Vorbis Comment & `METADATA_BLOCK_PICTURE` parser.
  - `MetadataReader.kt`: `AndroidMetadataReader` combining `MediaMetadataRetriever`, `FlacTags`, and ID3v2 `USLT` lyrics extraction.
  - `ArtworkCache.kt`: 600px JPEG disk cache keyed by SHA-256 of image bytes with an in-memory `LruCache`.
  - `LrcParser.kt`: `.lrc` / plain-text parser and strict UTF-8 / UTF-16 BOM / GB18030 decoder.
  - `LyricsStore.kt`: Priority lyrics resolver (`IMPORTED` → `SIDECAR` → `EMBEDDED`).
- **`com.musico.store`**
  - `LibraryStore.kt`: Folder roots (built-in `Musico Folder` + SAF `PICKED` folders), incremental scanning, album/artist indexing, search, favourites, and playlists.
  - `QueueStore.kt`: Multi-queue state & persistence.
  - `PlayHistoryStore.kt`: Listening time, play counts, and stats queries (`recentlyPlayed`, `mostPlayed`, `leastPlayed`, `neverPlayed`, `notPlayedLately`).
  - `AppContainer.kt`: Application dependency holder and `AppContainerProvider`.
- **`com.musico.audio`**
  - `AudioEngine.kt`: Media3 `ExoPlayer` wrapper with 10-band equalizer (`DynamicsProcessing` on API 28+, `Equalizer` fallback on API 26–27).
  - `MusicoPlaybackService.kt`: Media3 `MediaSessionService` foreground playback service with lock-screen controls, media notification, and `ACTION_AUDIO_BECOMING_NOISY` handling.
  - `PlayerController.kt`: Transport controller coordinating queue progression, listening history thresholds, sleep timer, and EQ persistence.
- **`com.musico.ui`**
  - Adaptive Material 3 / Material You Compose UI (`Folders`, `Library`, `Queues`, `Stats`, Mini Player, Full Player, Lyrics/Queue/EQ/Sleep Timer sheets).

## Requirements & Building

- **Min SDK**: 26 (Android 8.0)
- **Target / Compile SDK**: 35 (Android 15)
- **JDK**: 17
- **Gradle**: 8.9 / AGP 8.7.3 / Kotlin 2.0.21

Run unit tests or build an APK from `MusicoAndroid/`:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
