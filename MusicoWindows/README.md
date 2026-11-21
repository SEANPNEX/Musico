# Musico for Windows

A native **Windows 10 WPF (.NET 8)** local music player inspired by Musico, featuring Windows 10 Fluent / Acrylic aesthetics, NAudio playback with a 10-band equalizer, Windows 10 System Media Transport Controls (SMTC), synced/unsynced LRC lyrics, multi-queue playback, and play statistics.

## Requirements

- **OS:** Windows 10 (version 19041 / 2004 or later) or Windows 11
- **SDK:** [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0) (`net8.0-windows10.0.19041.0`)

## Build & Run (Windows)

```powershell
cd MusicoWindows
dotnet restore
dotnet build MusicoWindows.sln -c Release
dotnet run --project MusicoWindows\MusicoWindows.csproj
```

## Run Unit Tests

```powershell
cd MusicoWindows
dotnet test MusicoWindows.Tests\MusicoWindows.Tests.csproj
```

## Portable Edition & Windows Installer

Both distributions are self-contained (`win-x64`, no separate .NET Runtime install required on Windows 10):

- **Portable Edition (`dist/MusicoWindows-1.0.0-win-x64-portable.zip`)**:
  - Contains a single-file `MusicoWindows.exe` and `portable.txt`.
  - When `portable.txt` sits next to `MusicoWindows.exe`, all library state, playlists, queues, history, lyrics, artwork cache, and EQ settings are stored locally in `.\Data\` instead of `%LOCALAPPDATA%\Musico`.
- **Windows Installer (`dist/MusicoWindows-1.0.0-win-x64-Setup.exe`)**:
  - Built with NSIS (`installer/MusicoWindows.nsi`), installs per-user under `%LOCALAPPDATA%\Programs\Musico`, registers Start Menu / optional Desktop shortcuts and Windows Add/Remove Programs (`Uninstall.exe`).

To rebuild both release packages from macOS/Linux (requires Microsoft .NET 8 SDK + `makensis`):

```bash
./scripts/build-release.sh
```

## Architecture

- **`MusicoWindows/Models/`**: Immutable data models (`LibraryRoot`, `Track`, `AlbumInfo`, `Playlist`, `EqSettings`, `EqPreset`, `Lyrics`, `PlayHistoryEntry`, `SleepState`) and `PlayQueue` state machine.
- **`MusicoWindows/Core/`**: Atomic JSON `Persistence` (with `.corrupt` backup), `FolderScanner` (NFC-normalized `/` relative paths, skips hidden/system directories), `TagLibMetadataReader`, 600px JPEG `ArtworkCache`, `LrcParser` (UTF-8 / UTF-16 BOM / GB18030), and `LyricsStore` (imported → sidecar → embedded).
- **`MusicoWindows/Audio/`**: `AudioEngine` (`AudioFileReader` → `TenBandEqualizerSampleProvider` → `WaveOutEvent` with generation-token stop handling), `TenBandEqualizerSampleProvider` (`BiQuadFilter.PeakingEQ` across 31 Hz–16 kHz + preamp), and `SmtcBridge` (`Windows.Media.Playback.MediaPlayer` SMTC).
- **`MusicoWindows/Stores/`**: `LibraryStore`, `QueueStore`, `PlayHistoryStore`, `PlayerController`, and `AppServices` rooted in `%LOCALAPPDATA%\Musico` and `Environment.SpecialFolder.MyMusic`.
- **`MusicoWindows/Theme/`, `Controls/`, `Converters/`, `Views/`**: Windows 10 Fluent WPF UI (Acrylic NavigationView, WindowChrome title bar, Light/Dark theme sync, full player, lyrics, equalizer, sleep timer, and stats views).
