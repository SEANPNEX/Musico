# ♪ Musico for Android ♪

[← Back to Root README](../README.md) | [简体中文主说明](../README.zh-CN.md)

Native Android music player built with **Kotlin**, **Jetpack Compose (Material 3 / Material You)**, **AndroidX Media3** (`ExoPlayer` + `MediaSessionService`), and embedded **QuickJS** (`quickjs-kt`) — zero WebViews allowed! `(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧`

## ✨ Highlights

- 📁 **Local Folders + Cloud Hangouts**: Reads your built-in `Musico Folder` plus any SAF directory granted through Android's folder picker, alongside **YouTube / YouTube Music**, **Bilibili**, **Google Drive**, and JS extension cloud folders with **Save to Local Folder** & **Update from Cloud** parity.
- 🖼️ **Broad Embedded Artwork & Tag Support**: Extracts embedded album art directly across ID3v2 (`v2.2` / `v2.3` / `v2.4`), FLAC (`METADATA_BLOCK_PICTURE` & Vorbis `COVERART`), MP4 / M4A / ALAC, Ogg / Opus, WAV, and AIFF — with API 28+ `ImageDecoder` fallback!
- 🎤 **Folder-Matched & Cloud Lyrics**: Resolves **Imported** → exact **Sidecar** → non-recursive **Same-Folder Fuzzy Match** → **Embedded** locally on playback, plus an on-demand **Search Online…** sheet (**Auto**, **LRCLIB**, **NetEase**, and `"lyrics"` extensions) with preview, translation toggle, and optional `.lrc` export.
- 🔍 **Extension-Only Cloud Search**: When one or more `"search"` extensions are enabled, **Songs** reveals Material 3 source chips below the search field (`Local` by default; completely hidden otherwise), ephemeral `Search (<Ext>): <query>` queue playback excluded from Library tabs & **Stats**, single-track **Save to Local Folder…**, and a collection preview sheet with **Add as Cloud Folder**.
- 🧩 **Native QuickJS Extensions**: Supports `.musicoext` bundles and `.musico.js` scripts with Permission Review, strict `permissions.hosts` allowlists, masked extension-level cookies (redacted in logs), per-extension settings, and a 200-entry debug log viewer.
- 🚀 **Multilingual & In-App Updater**: Live **English** / **简体中文** switching and GitHub Releases checks with `SHA256SUMS.txt` verification + `FileProvider` APK installer.

## 🏗️ Architecture

- **`com.musico.model`**
  - `Models.kt`: Immutable serializable domain models (`LibraryRoot`, `Track`, `Album`, `Playlist`, `RepeatMode`, `EqSettings`, `EqPreset`, `LyricLine`, `Lyrics`, `LyricsSource`, `PlayHistoryEntry`, `SleepMode`).
  - `PlayQueue.kt`: Pure immutable queue state machine with shuffle permutations, repeat modes (`OFF`, `ALL`, `ONE`), insert-next, reorder, and missing-track pruning.
- **`com.musico.core`**
  - `Persistence.kt`: Atomic temp-write + move JSON persistence via `kotlinx.serialization` with `.corrupt` backup recovery.
  - `FolderScanner.kt`: Recursive audio scanner supporting both local directories and Android Storage Access Framework (`DocumentFile` / `DocumentsContract`) trees, with NFC path normalization.
  - `EmbeddedArtworkExtractor.kt`: Pure Kotlin binary artwork extractor covering ID3v2 (`v2.2` / `v2.3` / `v2.4`), FLAC, MP4/M4A/ALAC (`covr`), Ogg/Opus, WAV (`LIST`/`ID3`), and AIFF (`ID3`).
  - `FlacTags.kt`, `MetadataReader.kt`, `AudioFileInfoReader.kt`: Combines `MediaMetadataRetriever`, Vorbis `METADATA_BLOCK_PICTURE`/`COVERART`, ID3v2.4 footer/frame parsing, and audio stream diagnostics.
  - `ArtworkCache.kt` & `CloudThumbnailCache.kt`: Persistent 600px JPEG disk artwork cache (with `ImageDecoder` fallback on API 28+ and SHA-256 keys) plus an `https://`-only in-memory LRU thumbnail cache for search/preview rows.
  - `LrcParser.kt`, `LyricsNameMatcher.kt`, `LyricsStore.kt`: `.lrc` / plain-text parser (UTF-8 / UTF-16 BOM / GB18030) and strictly local priority resolver (`IMPORTED` → `SIDECAR` → `FOLDER_MATCH` → `EMBEDDED`).
- **`com.musico.cloud`**
  - `YouTubeClient.kt`, `BilibiliClient.kt`, `GoogleDriveClient.kt`, `CloudStreamResolver.kt`, `CloudExporter.kt`, `CloudModels.kt`: Built-in cloud metadata/stream resolvers, isolated per-root cookie handling, and **Save to Local Folder** / incremental **Update from Cloud** exporter.
- **`com.musico.lyrics`**
  - `CloudLyricsService.kt`, `LrclibClient.kt`, `NeteaseLyricsClient.kt`, `CloudLyricsModels.kt`: On-demand cloud lyrics search, scoring, and merging across LRCLIB, NetEase, and `"lyrics"` extensions.
- **`com.musico.extensions`**
  - `QuickJsExtensionRuntime.kt`, `ExtensionStore.kt`, `ExtensionPackageLoader.kt`, `CloudProviderRegistry.kt`, `JsExtensionCloudProvider.kt`, `ExtensionModels.kt`: Embedded `quickjs-kt` runtime supporting `"playlist"`, `"tree"`, `"stream"`, `"cookie"`, `"proxy"`, `"lyrics"`, and `"search"`, strict `permissions.hosts` enforcement, `searchKinds`, and masked extension-level cookies with log redaction.
- **`com.musico.store`**
  - `LibraryStore.kt`: Local/SAF/cloud roots, incremental scanning, album/artist indexing, playlists, favourites, plus cached & auto-pruned ephemeral search tracks excluded from Library tabs.
  - `QueueStore.kt`: Multi-queue state & persistence.
  - `PlayHistoryStore.kt`: Listening time, play counts, and stats queries (`recentlyPlayed`, `mostPlayed`, `leastPlayed`, `neverPlayed`, `notPlayedLately`), automatically excluding ephemeral search tracks.
  - `CloudDownloadManager.kt` & `AppContainer.kt`: Background cloud cache/downloads and application dependency container.
- **`com.musico.audio`**
  - `AudioEngine.kt`: Media3 `ExoPlayer` wrapper with 10-band equalizer (`DynamicsProcessing` on API 28+, `Equalizer` fallback on API 26–27).
  - `MusicoPlaybackService.kt`: Media3 `MediaSessionService` foreground playback service with lock-screen controls, media notification, and `ACTION_AUDIO_BECOMING_NOISY` handling.
  - `PlayerController.kt`: Transport controller coordinating queue progression, listening history thresholds, sleep timer, and EQ persistence.
- **`com.musico.i18n` & `com.musico.update`**
  - `AppLanguage.kt`, `AppStrings.kt`: Live **Follow System**, **English**, and **简体中文** string system.
  - `UpdateManager.kt`, `UpdateModels.kt`: GitHub Releases (`releases/latest`) version checker, `SHA256SUMS.txt` verifier, and `FileProvider` APK installer.
- **`com.musico.ui`**
  - Adaptive Material 3 / Material You Compose UI (`FoldersScreen`, `SongsScreen` with conditional cloud search & `CloudSearchController`, `CloudCollectionPreviewSheet`, `AlbumsScreen`, `ArtistsScreen`, `PlaylistsScreen`, `LibraryScreen`, `QueuesScreen`, `StatsScreen`, `ExtensionsScreen`, Mini/Full Player, `LyricsSheet`, `CloudLyricsPickerSheet`, Equalizer & Sleep Timer sheets, `UpdateDialog`).

## 🛠️ Requirements & Building

- **Min SDK**: 26 (Android 8.0)
- **Target / Compile SDK**: 35 (Android 15)
- **JDK**: 17
- **Gradle**: 8.10.2 / AGP 8.7.3 / Kotlin 2.0.21

Run unit tests or build an APK from `MusicoAndroid/`:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease   # uses ANDROID_KEYSTORE_* env vars when configured for signed release builds
```
