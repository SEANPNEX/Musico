# Musico

[![CI](https://github.com/SEANPNEX/local_music_player/actions/workflows/ci.yml/badge.svg)](https://github.com/SEANPNEX/local_music_player/actions/workflows/ci.yml)
[![Release](https://github.com/SEANPNEX/local_music_player/actions/workflows/release.yml/badge.svg)](https://github.com/SEANPNEX/local_music_player/actions/workflows/release.yml)
[![Latest Release](https://img.shields.io/github/v/release/SEANPNEX/local_music_player)](https://github.com/SEANPNEX/local_music_player/releases/latest)

**Musico** is an offline, folder-aware, multi-queue local music player inspired by Musicolet — built natively for each platform with zero cross-platform web wrappers.

---

## Native Platforms

| Folder | Target Platforms | Tech Stack | Native UI Language |
|---|---|---|---|
| [`Musico/`](Musico/) | iOS 17+, iPadOS, macOS (Catalyst) | Swift, SwiftUI (`@Observable`), `AVAudioEngine` + `AVAudioUnitEQ`, `MPNowPlayingInfoCenter` | Apple Human Interface Guidelines (iOS Tab/Sheet & macOS/iPadOS Sidebar + Inspector) |
| [`MusicoAndroid/`](MusicoAndroid/) | Android 8.0+ (API 26+) | Kotlin, Jetpack Compose, AndroidX Media3 (ExoPlayer + `MediaSessionService`), SAF `DocumentFile`, `DynamicsProcessing` EQ | Material 3 / Material You (Dynamic Color, adaptive `NavigationBar` / `NavigationRail`, `ModalBottomSheet`) |
| [`MusicoWindows/`](MusicoWindows/) | Windows 10 (2004+) & Windows 11 | C# / .NET 8 WPF, NAudio (`BiQuadFilter` 10-band EQ), TagLibSharp, Windows 10 SMTC | Windows 10 Fluent Design (`Segoe UI`, `Segoe MDL2 Assets`, Acrylic `NavigationView`, sharp 0px geometry, Reveal hover, Pivot tabs, Light/Dark theme sync) |

---

## Shared Feature Set

- **Folder-First Library**: Add multiple music folders (`Documents` / `MyMusic` + user-picked folders), preserve folder hierarchies across relaunches, handle temporarily unavailable/disconnected drives gracefully with **Relocate**, and incrementally diff rescans.
- **Library Browsing & Search**: Browse by **Folders**, **Songs** (title/artist/album/date-added sort + instant diacritic-insensitive search), **Albums** (ordered by disc & track number), **Artists**, and **Playlists** (built-in **Favourites** + user playlists with create/rename/delete/reorder).
- **Multiple Independent Queues**: Create, rename, switch, reorder, clear, and delete queues—each remembering its current track, playback position, shuffle permutation, and repeat mode (`Off` / `All` / `One`).
- **10-Band Equalizer**: Master toggle, preamp (`-12 dB` to `+12 dB`), 10 ISO bands (`31 Hz`–`16 kHz`), and 8 presets (`Flat`, `Bass Boost`, `Treble Boost`, `Rock`, `Pop`, `Jazz`, `Classical`, `Vocal`) plus `Custom`.
- **Lyrics Support**: Resolves **Imported** `.lrc`/`.txt` → **Sidecar** `.lrc`/`.txt` → **Embedded** tags; supports multi-timestamp `.lrc`, UTF-8 / UTF-16 BOM / GB18030 decoding, live line auto-scroll, and tap-to-seek.
- **Sleep Timer & Listening Stats**: Presets (`5`–`90` min), custom duration, or **End of current song**, plus listening statistics (**Recent**, **Plays**, **Not Lately**, **Never**).
- **System Media Controls**: Lock-screen / Control Center & headphone buttons on iOS/macOS, `MediaSessionService` notification & media keys on Android, and Windows 10 System Media Transport Controls (SMTC) on Windows.

---

## Downloads & Installation

Prebuilt binaries are published on the [**Releases**](https://github.com/SEANPNEX/local_music_player/releases/latest) page:

- **Windows 10 / 11 (x64, self-contained .NET 8 — no runtime install needed)**:
  - **Installer**: Download `MusicoWindows-<version>-win-x64-Setup.exe` and run the wizard (installs per-user with Start Menu & optional Desktop shortcuts and Add/Remove Programs uninstaller).
  - **Portable**: Download `MusicoWindows-<version>-win-x64-portable.zip`, extract anywhere, and run `MusicoWindows.exe` (keep `portable.txt` beside it to store library & settings in `.\Data\`).
- **Android (8.0+)**:
  - Download `MusicoAndroid-<version>-debug.apk` (directly sideloadable for personal testing) or `MusicoAndroid-<version>-release-unsigned.apk` (for custom signing).
- **macOS (Catalyst)**:
  - Download `Musico-<version>-macOS.dmg`, open it, and drag `Musico.app` to `Applications` (ad-hoc signed; right-click → Open on first launch if Gatekeeper prompts).
- **iOS (17+)**:
  - Download `Musico-<version>-iOS-unsigned.ipa` and sideload/re-sign with AltStore, SideStore, Sideloadly, or Xcode.

---

## Building & Testing Locally

### iOS / iPadOS / macOS (`Musico/`)
Requires Xcode (iOS 17+ SDK):
```bash
cd Musico
./scripts/test.sh
./scripts/build-release.sh   # outputs dist/Musico-*-macOS.dmg & dist/Musico-*-iOS-unsigned.ipa
# Or open Musico.xcodeproj in Xcode
```

### Android (`MusicoAndroid/`)
Requires JDK 17 and Android SDK 35:
```bash
cd MusicoAndroid
./gradlew testDebugUnitTest assembleDebug
```

### Windows 10 (`MusicoWindows/`)
Requires .NET 8 SDK (`net8.0-windows10.0.19041.0` on Windows; unit tests also run cross-platform on macOS/Linux via `net8.0`):
```powershell
cd MusicoWindows
dotnet test MusicoWindows.Tests\MusicoWindows.Tests.csproj
dotnet build MusicoWindows\MusicoWindows.csproj -c Release
```

To build the Windows Installer & Portable zip (requires `.NET 8 SDK` + NSIS `makensis`):
```bash
cd MusicoWindows
./scripts/build-release.sh
```

---

## Repository Structure

```text
.
├── .github/workflows/
│   ├── ci.yml              # Continuous integration (Android, Windows, Apple)
│   └── release.yml         # Tag-triggered builds + GitHub Release publishing
├── Musico/                 # Native SwiftUI app (iOS / iPadOS / macOS Catalyst)
├── MusicoAndroid/          # Native Kotlin + Compose Material 3 + Media3 app
├── MusicoWindows/          # Native C# + WPF Windows 10 Fluent Design app + NSIS installer
└── README.md
```
