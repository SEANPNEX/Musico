# ♪ Musico for Windows ♪

[← Back to Root README](../README.md) | [简体中文主说明](../README.zh-CN.md)

A native **Windows 10 / 11 WPF (.NET 8)** local & cloud music player featuring Windows 10 Fluent / Acrylic aesthetics, NAudio playback with a 10-band equalizer, Windows 10 System Media Transport Controls (SMTC), synced/folder-matched/cloud LRC lyrics, multi-queue playback, system tray integration, and embedded **Jint** JS extensions — zero WebViews allowed! `(・ω・)ﾉ`

## 📋 Requirements

- **OS:** Windows 10 (version 19041 / 2004 or later) or Windows 11
- **SDK:** [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0) (`net8.0-windows10.0.19041.0`; `MusicoWindows.Tests` also targets `net8.0` for cross-platform unit tests)

## 🛠️ Build & Run (Windows)

```powershell
cd MusicoWindows
dotnet restore
dotnet build MusicoWindows.sln -c Release
dotnet run --project MusicoWindows\MusicoWindows.csproj
```

## 🧪 Run Unit Tests

```powershell
cd MusicoWindows
dotnet test MusicoWindows.Tests\MusicoWindows.Tests.csproj
```

## 📦 Portable Edition & Windows Installer

Both distributions are self-contained (`win-x64`, zero separate .NET Runtime install needed on Windows 10/11!):

- **Portable Edition (`dist/MusicoWindows-<version>-win-x64-portable.zip`)**:
  - Contains a single-file `MusicoWindows.exe` and `portable.txt`.
  - When `portable.txt` sits next to `MusicoWindows.exe`, all library state, playlists, queues, history, lyrics, artwork cache, extensions, window preferences, and EQ settings are stored neatly inside `.\Data\` instead of `%LOCALAPPDATA%\Musico`.
- **Windows Installer (`dist/MusicoWindows-<version>-win-x64-Setup.exe`)**:
  - Built with NSIS (`installer/MusicoWindows.nsi`), installs per-user under `%LOCALAPPDATA%\Programs\Musico`, registers Start Menu / optional Desktop shortcuts and Windows Add/Remove Programs (`Uninstall.exe`).

To rebuild both release packages from macOS/Linux (requires .NET 8 SDK + `makensis`):

```bash
./scripts/build-release.sh
```

## 🏗️ Architecture

- **`MusicoWindows/Models/`**: Immutable data models (`Models.cs`, `LibraryRoot`, `Track`, `AlbumInfo`, `Playlist`, `EqSettings`, `EqPreset`, `Lyrics`, `PlayHistoryEntry`, `SleepState`) and `PlayQueue` state machine.
- **`MusicoWindows/Core/`**: Atomic JSON `Persistence` (with `.corrupt` backup), `FolderScanner` (NFC-normalized `/` relative paths, skips hidden/system directories), `MetadataReader` (`TagLibMetadataReader`), 600px JPEG `ArtworkCache`, `LrcParser` (UTF-8 / UTF-16 BOM / GB18030), `LyricsNameMatcher`, `LyricsStore` (`imported` → `sidecar` → `folderMatch` → `embedded`), `TrackSorter`, and `WindowPreferences` (per-version first-close prompt, **Exit** sidebar settings dialog, and single-instance / tray state).
- **`MusicoWindows/Cloud/`**: Built-in `YouTubeClient`, `BilibiliClient`, `GoogleDriveClient`, `CloudStreamResolver`, `CloudExporter` (**Save to Local Folder** / `.musico-cloud.json` **Update from Cloud**), `CloudAudioCache`, `CloudThumbnailCache` (`https://`-only in-memory LRU cache), and on-demand cloud lyrics (`LrclibClient`, `NeteaseLyricsClient`, `CloudLyricsService`, `CloudLyricsModels`) with HTTP proxy support.
- **`MusicoWindows/Extensions/`**: Embedded `JintExtensionRuntime`, `ExtensionStore`, `ExtensionPackageLoader`, `CloudProviderRegistry`, `JsExtensionCloudProvider`, `CloudSearchController`, and `ExtensionModels` — supporting `"playlist"`, `"tree"`, `"stream"`, `"cookie"`, `"proxy"`, `"lyrics"`, and `"search"` capabilities, `searchKinds`, and masked extension-level cookies with log redaction.
- **`MusicoWindows/Audio/`**: `AudioEngine` (`AudioFileReader` → `TenBandEqualizerSampleProvider` → `WaveOutEvent` with generation-token stop handling), `TenBandEqualizerSampleProvider` (`BiQuadFilter.PeakingEQ` across 31 Hz–16 kHz + preamp), and `SmtcBridge` (`Windows.Media.Playback.MediaPlayer` SMTC).
- **`MusicoWindows/Stores/`**: `LibraryStore` (including cached & auto-pruned `extsearch:` ephemeral tracks excluded from Library tabs), `QueueStore`, `PlayHistoryStore` (excluding ephemeral search tracks from stats), `PlayerController`, `DownloadManager`, `ObservableStore`, and `AppServices` rooted in `%LOCALAPPDATA%\Musico` (or `.\Data\` in portable mode) and `Environment.SpecialFolder.MyMusic`.
- **`MusicoWindows/Localization/` & `Update/`**: Live **Follow System** / `Strings.en.xaml` / `Strings.zh-Hans.xaml` switching (`LocalizationManager`), `UpdateService` (GitHub Releases + `SHA256SUMS.txt` verification + Installer/self-replacing Portable upgrades), and `TrayIconService.cs`.
- **`MusicoWindows/Theme/`, `Controls/`, `Converters/`, `Views/`**: Windows 10 Fluent WPF UI (Acrylic `NavigationView`, `WindowChrome` title bar, Light/Dark theme sync, `ExtensionsView`, `SongsView` conditional source switcher preserving `Ctrl+F`, `CloudCollectionPreviewDialog`, `CloudLyricsDialog`, `WindowBehaviorDialog`, `FullPlayerView`, EQ, Sleep Timer, and Stats views).
