<div align="center">

# ♪ Musico ♪

**English** | [简体中文](README.zh-CN.md)

[![CI](https://github.com/SEANPNEX/Musico/actions/workflows/ci.yml/badge.svg)](https://github.com/SEANPNEX/Musico/actions/workflows/ci.yml)
[![Release](https://github.com/SEANPNEX/Musico/actions/workflows/release.yml/badge.svg)](https://github.com/SEANPNEX/Musico/actions/workflows/release.yml)
[![Latest Release](https://img.shields.io/github/v/release/SEANPNEX/Musico)](https://github.com/SEANPNEX/Musico/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**An offline, folder-loving, multi-queue local music player — built 100% natively for every platform, zero web wrappers allowed! (ﾉ◕ヮ◕)ﾉ*:･ﾟ✧**

*Inspired by Musicolet & paying warm tribute to [YRRLYB/Aria](https://github.com/YRRLYB/Aria) ♪(´▽｀)*

</div>

---

## 💖 Tribute & Inspiration

- **[Musicolet](https://krosbits.in/musicolet/)**: The OG folder-first, multi-queue offline player that proved local music players can be super clean and endlessly practical.
- **[YRRLYB/Aria](https://github.com/YRRLYB/Aria)**: A huge shoutout and tribute to Aria — a minimalist, comfy desktop music player that blends local lossless tracks with online sources like Bilibili so you can just open the app and pick up right where yesterday's song left off `(・ω・)ﾉ`. Musico shares that exact same cozy spirit across Apple, Android, and Windows!

---

## 🖥️ Native Platforms

| Folder | Target Platforms | Tech Stack | Native UI Vibe |
|---|---|---|---|
| [`Musico/`](Musico/) | iOS 17+, iPadOS, macOS (Catalyst) | Swift, SwiftUI (`@Observable`), `AVAudioEngine` + `AVAudioUnitEQ`, `MPNowPlayingInfoCenter`, embedded `JavaScriptCore` (`Compression` + `CryptoKit`) | Apple HIG — cozy Tabs & Sheets on iOS, crisp Sidebar + Inspector on macOS/iPadOS |
| [`MusicoAndroid/`](MusicoAndroid/) | Android 8.0+ (API 26+) | Kotlin, Jetpack Compose, AndroidX Media3 (ExoPlayer + `MediaSessionService`), SAF `DocumentFile`, `DynamicsProcessing` EQ, embedded `QuickJS` (`quickjs-kt`) | Material 3 / Material You — Dynamic Color, adaptive `NavigationBar` / `NavigationRail`, `ModalBottomSheet` |
| [`MusicoWindows/`](MusicoWindows/) | Windows 10 (2004+) & Windows 11 | C# / .NET 8 WPF, NAudio (`BiQuadFilter` 10-band EQ), TagLibSharp, Windows 10 SMTC, embedded `Jint` | Windows 10 Fluent Design — `Segoe UI`, Acrylic `NavigationView`, sharp 0px geometry, Reveal hover, Light/Dark sync |

---

## ✨ What Can Musico Do?

- 📁 **Folder-First Library & Cloud Hangouts**: Mount your favorite local folders (`Documents` / `MyMusic` + custom folders) while keeping your precious folder hierarchy intact! Unplugged a drive? **Relocate** fixes it without losing a thing. You can even mount **YouTube / YouTube Music** (playlists, mixes & single tracks), **Bilibili** (videos, multi-part, favlists & collections), **Google Drive** (shared folders with recursive audio discovery & single audio files), and your own custom sources right inside **Folders**!
- 🧩 **Cross-Platform JavaScript Extensions**: Write one CommonJS script (`.musico.js`) or ZIP bundle (`.musicoext`) and run it natively across Apple (`JavaScriptCore`), Android (`QuickJS`), and Windows (`Jint`) with zero WebViews! One extension system powers **custom cloud sources**, **extension-only Cloud Search** (`"search"`), and **custom Cloud Lyrics providers** (`"lyrics"`) — complete with explicit Permission Review, strict HTTP/HTTPS domain allowlists (`permissions.hosts`), per-extension settings, optional masked extension-level cookies and native QR code login (`"qr_login"`), a 200-entry log viewer, progressive folder-tree crawling, and **Save to Local Folder** / **Update from Cloud** parity. See the [Extension Developer Guide](docs/extensions/README.md) ([中文开发指南](docs/extensions/README.zh-CN.md)).
- 🌐 **Multilingual (`English` & `简体中文`)**: Defaults to your OS system language (**Follow System**) with instant live runtime switching across Apple, Android, and Windows.
- 🔍 **Browsing, Instant Search & Extension Cloud Search**: Hop around **Folders**, **Songs** (sort by title/artist/album/date added + instant diacritic-insensitive search), **Albums** (properly ordered by disc & track), **Artists**, and **Playlists** (built-in **Favourites** + your own custom playlists). When you enable a `"search"`-capable extension, **Songs** also unlocks an opt-in **Search Source Switcher** (defaults to **Local**, stays completely hidden otherwise — zero built-in cloud search): trigger searches explicitly, play ephemeral results in a dedicated `Search (<Ext>): <query>` queue excluded from Library tabs and **Stats**, save single tracks via **Save to Local Folder…**, or preview playlists/albums before clicking **Add as Cloud Folder**.
- 🎶 **Multiple Independent Queues**: Spin up as many queues as your mood needs — rename, switch, reorder, clear, or delete them anytime. Every queue remembers its own track, playback position, shuffle order, and repeat mode (`Off` / `All` / `One`).
- 🎛️ **10-Band Equalizer**: Flip the master switch, dial in preamp (`-12 dB` to `+12 dB`), tweak 10 ISO bands (`31 Hz`–`16 kHz`), or pick from 8 presets (`Flat`, `Bass Boost`, `Treble Boost`, `Rock`, `Pop`, `Jazz`, `Classical`, `Vocal`) + `Custom`.
- 🎤 **Synced, Folder-Matched & Cloud Lyrics**: On playback, Musico stays strictly local and checks **Imported** `.lrc`/`.txt` → exact **Sidecar** `.lrc`/`.txt` → non-recursive **Same-Folder Fuzzy Match** → **Embedded** tags. Need lyrics from the web? Tap **Search Online…** for on-demand lookup across **Auto**, built-in **LRCLIB** & **NetEase**, and `"lyrics"` extensions — preview candidates live, toggle bilingual translations, save on confirm, and optionally export a sibling `.lrc` file (on by default when writable)! Supports multi-timestamp `.lrc`, UTF-8 / UTF-16 BOM / GB18030 decoding, smooth auto-scroll, and tap-to-seek!
- ⏱️ **Sleep Timer & Listening Stats**: Drift off with `5`–`90` min presets, custom timers, or **End of current song**, and peek at your habits in **Stats** (**Recent**, **Plays**, **Not Lately**, **Never**).
- 🎧 **System Media Controls**: Lock-screen / Control Center & headphone controls on Apple, `MediaSessionService` notification & media keys on Android, and Windows 10 SMTC on Windows.
- 🚀 **In-App Updater**: Checks GitHub Releases (`releases/latest`) with SHA-256 verification (`SHA256SUMS.txt`) and **Skip Version** support on **Windows** (Installer `.exe` & self-replacing Portable `.zip`), **Android** (`FileProvider` APK installer), and **macOS Catalyst** (downloads & opens `.dmg`). *(Skipped on iOS for personal-team signing.)*

---

## 📦 Downloads & Installation

Grab the latest builds from the [**Releases**](https://github.com/SEANPNEX/Musico/releases/latest) page `(｡・ω・｡)ﾉ♡`:

- **Windows 10 / 11 (x64, self-contained .NET 8 — zero runtime setup!)**:
  - **Installer**: Run `MusicoWindows-<version>-win-x64-Setup.exe` for per-user install with Start Menu / Desktop shortcuts and clean uninstall.
  - **Portable**: Unzip `MusicoWindows-<version>-win-x64-portable.zip` anywhere and launch `MusicoWindows.exe` (keep `portable.txt` next to it to save all library data & settings in `.\Data\`).
- **Android (8.0+)**:
  - Install the persistently signed `MusicoAndroid-<version>-release.apk` *(recommended — enables seamless in-app updates)*, or grab `MusicoAndroid-<version>-debug.apk` for quick local testing.
- **macOS (Catalyst)**:
  - Open `Musico-<version>-macOS.dmg` and drag `Musico.app` into `Applications` (ad-hoc signed; right-click → Open on first launch if Gatekeeper asks).
- **iOS (17+)**:
  - Sideload/re-sign `Musico-<version>-iOS-unsigned.ipa` with AltStore, SideStore, Sideloadly, or Xcode.

---

## 🎀 Usage Guide

### 1. Adding Music & Managing Folders
- **Where Musico Looks First**:
  - **macOS**: Starts with your Mac's `Music` folder.
  - **iOS / iPadOS**: Drop songs into `Files › On My iPhone/iPad › Musico` or pick any folder from Files.
  - **Android**: Uses the app's `Musico Folder` plus any folder you grant via the system picker.
  - **Windows**: Starts with `MyMusic`. In Portable mode (`portable.txt` beside `MusicoWindows.exe`), everything lives neatly inside `.\Data\` instead of `%LOCALAPPDATA%\Musico`.
- **Add, Rescan & Relocate**: Head to **Folders** → **Add Folder** to mount local folders. Hit **Rescan** (or pull to refresh on mobile) whenever you add new tracks. Moved a folder or swapped drives? **Locate Again…** (**Relocate**) reconnects it without losing your playlists or stats, and **Restore Removed Subfolders** brings back anything you previously hid.
- **Supported Audio Formats**:
  - **Apple (`Musico`)**: `mp3`, `m4a`, `aac`, `flac`, `wav`, `aif`, `aiff`, `caf`, `alac`
  - **Android (`MusicoAndroid`)**: `mp3`, `m4a`, `aac`, `flac`, `wav`, `aif`, `aiff`, `caf`, `alac`, `ogg`, `opus`, `webm`
  - **Windows (`MusicoWindows`)**: `mp3`, `m4a`, `aac`, `flac`, `wav`, `aif`, `aiff`, `wma`, `ogg`, `opus`

### 2. Cloud Folders (YouTube, Bilibili, Google Drive & Extensions)
- **Mount a Cloud Source**: In **Folders**, tap **Add Cloud Folder** and paste a URL, share text, or ID (surrounding share-sheet text is stripped automatically!). By default **Auto-Detect** checks built-in sources (**YouTube**, **Bilibili**, **Google Drive**) followed by your enabled JS extensions, or you can pick a specific provider manually:
  - **YouTube / YouTube Music**: Playlists, `RD` radio mixes, album browse links, or single videos (`youtube.com/playlist?list=…`, `music.youtube.com/playlist?list=…`, `youtube.com/watch?v=…`, `youtu.be/…`, Shorts) — public playlists, mixes, and videos work with no login!
  - **Bilibili**: `BV`/`av` videos (including multi-part), `favlist`, collections/series, and `b23.tv` short links.
  - **Google Drive**: Public/shared folder links (recursively discovers all audio files across nested subfolders, including links with `resourcekey`) and single audio file links (`drive.google.com/drive/folders/…`, `drive.google.com/file/d/…`, `drive.google.com/open?id=…`, `drive.usercontent.google.com/download?id=…`), with automatic large-file confirmation handling!
  - **Custom Extensions**: Any enabled extension installed in **Extensions** appears automatically with its custom `badgeLabel`, optional cookie prompt (when `"cookie"` capability is declared), and lazy/progressive subfolder browsing (when `"tree"` capability is declared).
- **Optional Cookie & Proxy**: Add a **Cookie** only if you need private playlists, restricted Google Drive files, higher Bilibili audio quality, or an extension that requires session auth. Folder cookies stay strictly isolated per cloud folder root, while extensions declaring `"cookie"` can also store a masked **extension-level cookie** in **Extensions** (used for Cloud Search, ephemeral previews, and ephemeral streams; mounted Cloud Folders still prioritize their own folder cookie). On Windows, you can also set an **HTTP Proxy** for YouTube, Google Drive & proxy-capable extensions (e.g. `7890`, `127.0.0.1:7890`, or `http://127.0.0.1:7890`).
- **Save Offline & Sync**: Open a cloud folder's menu (or track menu) and pick **Save to Local Folder…** to download tracks locally and optionally add the folder to your library. Tracked local folders keep a `.musico-cloud.json` manifest and show an **Update from Cloud** button to pull newly added songs incrementally!

### 3. Extensions (Cloud Sources, Search & Lyrics)
- **Open Extension Manager**: Open **Extensions** from the sidebar (`Cmd+8` on macOS/iPadOS, **Extensions** toolbar button in **Folders** on iOS and Android, or the **Extensions** sidebar item on Windows).
- **Import & Permission Review**: Import a `.musicoext` bundle or `.musico.js` single-file script from local storage or an `https://` URL. Newly imported extensions stay disabled until you inspect their declared `permissions.hosts` and `capabilities` (`"playlist"`, `"tree"`, `"stream"`, `"cookie"`, `"proxy"`, `"lyrics"`, `"search"`) and approve enabling them.
- **Settings, Cookie, Logs & Safe Removal**: Configure extension-specific toggles/inputs/selects, optionally save a masked extension-level cookie for `"cookie"` extensions (kept out of logs on Android/Windows via automatic cookie redaction), inspect or clear the last 200 log entries for debugging, and disable or uninstall anytime without losing existing library tracks, playlists, stats, or cached audio (if the active search extension is disabled or removed, **Songs** cleanly resets back to **Local**).
- **Build Your Own**: Check out the [Extension Developer Guide](docs/extensions/README.md) ([中文开发指南](docs/extensions/README.zh-CN.md)) and starter templates under [`docs/extensions/`](docs/extensions/).

### 4. Browsing, Queues & Playback
- **Explore Your Library**: Jump between **Folders**, **Songs**, **Albums**, **Artists**, **Playlists**, **Queues**, and **Stats** (**Recent**, **Plays**, **Not Lately**, **Never**).
- **Track Menu Goodies**: Tap/click any track to play its list from there, or open its context/ellipsis menu for **Play Next**, **Add to Queue**, **Add to Another Queue**, **Play in New Queue**, **Add to Playlist**, **Add/Remove Favourites**, and **Song Info**.
- **Queue Juggling & Drag Reorder**: Manage multiple queues in **Queues** (or the desktop side Queue panel). Grab the drag handle (`≡`) in **Folders** and **Queues** to reorder tracks your way.
- **Lyrics**: Pop open **Lyrics** from the player bar or Full Player. Playback stays strictly local and resolves **Imported** → exact **Sidecar** → **Same-Folder Fuzzy Match** → **Embedded** automatically. You can import `.lrc`/`.txt` files manually, tap any timed line to seek, or tap **Search Online…** to search **Auto**, **LRCLIB**, **NetEase**, and enabled `"lyrics"` extensions — preview lyrics, toggle translations, save only when confirmed, and optionally export a sibling `.lrc` file (on by default when writable).
- **Extension Cloud Search**: When one or more `"search"` extensions are enabled, **Songs** displays a conditional **Search Source Switcher** (**Local** by default; completely hidden when none are enabled). Searches run only on explicit submit (`Enter` / Search button), offer kind filters (**All / Songs / Playlists / Albums**) when supported, and load track playback into an ephemeral `Search (<Ext>): <query>` queue excluded from Library tabs and **Stats** — with single-track **Save to Local Folder…** and lightweight album/playlist preview sheets featuring **Add as Cloud Folder**.
- **Equalizer & Sleep Timer**: Open **Equalizer** for 10-band tuning & presets, or **Sleep Timer** to wind down after `5`–`90` minutes, a custom duration, or **End of current song**.
- **Song Info & Tag Editor**: Open **Song Info** on any track to check audio specs (precision, frequency, bit rate, file size, channels, play history), quick-search by Artist/Album/Genre, or edit `Title`, `Artist`, `Album`, `Genre`, and cover art.

### 5. Keyboard Shortcuts
- **Windows**:
  - `Space`: Play / Pause (when not typing in a text box)
  - `Ctrl+Left` / `Ctrl+Right`: Previous / Next track
  - `Ctrl+F`: Jump to **Songs** & focus search
  - `Esc`: Close Full Player or active dialog
- **macOS / iPadOS**:
  - `Cmd+F`: Jump to **Songs** & focus search
  - `Cmd+R`: Rescan library
  - `Cmd+1`–`Cmd+8`: Switch between **Folders**, **Songs**, **Albums**, **Artists**, **Playlists**, **Queues**, **Stats**, and **Extensions**
  - `Cmd+P` / `Cmd+Left` / `Cmd+Right`: Play / Pause, Previous / Next track (`Playback` menu also includes Shuffle, Repeat, and Show/Hide Now Playing)
  - `Esc`: Close Full Player (plus standard system media keys)

---

## 🛠️ Building & Testing Locally

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

### Extension Conformance Runner (`docs/extensions/`)
Requires Node.js 18+:
```bash
node docs/extensions/tools/mock-runner.mjs --self-test
```

---

## 📂 Repository Structure

```text
.
├── .github/workflows/
│   ├── ci.yml              # Continuous integration (Android, Windows, Apple)
│   └── release.yml         # Tag-triggered builds + GitHub Release publishing
├── docs/extensions/        # Extension developer guides, starter templates, fixtures & mock runner
├── Musico/                 # Native SwiftUI app (iOS / iPadOS / macOS Catalyst)
├── MusicoAndroid/          # Native Kotlin + Compose Material 3 + Media3 app
├── MusicoWindows/          # Native C# + WPF Windows 10 Fluent Design app + NSIS installer
├── README.md
├── README.zh-CN.md
├── LICENSE
└── THIRD_PARTY_NOTICES.md
```

---

## 📄 License

This project is licensed under the [MIT License](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for attributions and licenses of third-party dependencies.
