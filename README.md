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
- **Gemini 4 Neon & Gemini 4 Hydrogen**: Written as a heartfelt love letter to AI pair-programming — almost every line of Musico across Swift (`Musico`), Kotlin (`MusicoAndroid`), C#/WPF (`MusicoWindows`), and JavaScript extensions was imagined, designed, debugged, and polished side by side with **Gemini 4 Neon** and **Gemini 4 Hydrogen** ✨ `(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧`. Thank you for turning late-night ideas into a native multi-platform music player!

---

## 🖥️ Native Platforms

| Folder | Target Platforms | Tech Stack | Native UI Vibe |
|---|---|---|---|
| [`Musico/`](Musico/) | iOS 17+, iPadOS, macOS (Catalyst) | Swift, SwiftUI (`@Observable`), `AVAudioEngine` + `AVAudioUnitEQ`, `MPNowPlayingInfoCenter`, embedded `JavaScriptCore` (`Compression` + `CryptoKit`), ephemeral `WKWebView` (extension web login only) | Apple HIG — cozy Tabs & Sheets on iOS, crisp Sidebar + Inspector on macOS/iPadOS with `NSVisualEffectView` frosted translucency |
| [`MusicoAndroid/`](MusicoAndroid/) | Android 8.0+ (API 26+) | Kotlin, Jetpack Compose, AndroidX Media3 (ExoPlayer + `MediaSessionService`), SAF `DocumentFile`, `DynamicsProcessing` EQ, embedded `QuickJS` (`quickjs-kt`), ephemeral `WebView` (extension web login only) | Material 3 / Material You — Dynamic Color, adaptive `NavigationBar` / `NavigationRail`, `ModalBottomSheet` |
| [`MusicoWindows/`](MusicoWindows/) | Windows 10 (2004+) & Windows 11 | C# / .NET 8 WPF, NAudio (`BiQuadFilter` 10-band EQ), TagLibSharp, Windows 10 SMTC, embedded `Jint`, ephemeral `WebView2` (extension web login only) | Windows 10 Fluent Design + Windows 11 DWM rounded corners/backdrop — `Segoe UI`, Acrylic `NavigationView`, sharp 0px geometry, Reveal hover, Light/Dark sync |

---

## ✨ What Can Musico Do?

- 📁 **Folder-First Library & Cloud Hangouts**: Mount your favorite local folders (`Documents` / `MyMusic` + custom folders) while keeping your precious folder hierarchy intact! Unplugged a drive? **Relocate** fixes it without losing a thing, and direct file/folder deletion lets you choose **Remove from Library** or delete on disk (**Move to Trash** / **Move to Recycle Bin** / **Delete from Device**, including Android's `Musico` folder). Mount **Google Drive** out of the box (public links + optional personal OAuth 2.0 for private folders & bypassing confirmation pages), plus plugin-powered sources like **YouTube / YouTube Music**, **Bilibili**, **NetEase Cloud Music**, and **Apple Music** right inside **Folders**, with automatic cloud-to-local merging when saved offline!
- 🧩 **Cross-Platform JavaScript Extensions**: Write one CommonJS script (`.musico.js`) or ZIP bundle (`.musicoext`) and run it natively across Apple (`JavaScriptCore`), Android (`QuickJS`), and Windows (`Jint`) with zero web-wrapped player UI! One extension system powers **YouTube / Bilibili / NetEase / Apple Music & custom cloud sources**, **extension-only Cloud Search** (`"search"`), and **Cloud Lyrics providers** (`"lyrics"`) — complete with explicit Permission Review, strict HTTP/HTTPS domain allowlists (`permissions.hosts`), per-extension settings, optional masked cookies (`cookies.txt` import), native QR code login (`"qr_login"`), isolated ephemeral in-app WebView sign-in (`"web_login"`), a 200-entry log viewer, progressive folder-tree crawling, and **Save to Local Folder** / **Update from Cloud** parity. Grab official plugins from [`SEANPNEX/musico_plugins`](https://github.com/SEANPNEX/musico_plugins/releases/latest) or see the [Extension Developer Guide](docs/extensions/README.md) ([中文开发指南](docs/extensions/README.zh-CN.md)).
- 🎨 **Unified Settings & Custom Appearance**: Personalize Musico with `System` / `Light` / `Dark` theme modes, curated background & accent presets, custom hex/RGB/color pickers, a live **Now Playing Preview**, automatic **WCAG 2.1** contrast detection with inline warnings and one-click **Auto-fix Contrast**, custom background images (with **Image Visibility** opacity & **Background Blur**), desktop frosted window opacity/blur (`NSVisualEffectView` on macOS, Acrylic & Windows 11 DWM rounded corners/backdrop on Windows), independent sidebar background color (macOS & Windows), and sidebar opacity (Windows).
- ⚡ **Desktop Power Features**: Jump anywhere in milliseconds with the **Global Command Palette** (`Cmd+K` / `Ctrl+K`), drop folders or audio files onto the window for instant **Drag-and-Drop** import/playback, pop out the compact **Floating Mini-Player Window** (`Cmd+Shift+N` / `Ctrl+Shift+N`), and drive playback, panels, volume, and navigation with expanded desktop shortcuts.
- 🌐 **Multilingual (`English` & `简体中文`)**: Defaults to your OS system language (**Follow System**) with instant live runtime switching across Apple, Android, and Windows.
- 🔍 **Browsing, Instant Search & Extension Cloud Search**: Hop around **Folders**, **Songs** (sort by title/artist/album/date added + instant diacritic-insensitive search), **Albums** (properly ordered by disc & track), **Artists**, and **Playlists** (built-in **Favourites** + your own custom playlists). When you enable a `"search"`-capable extension, **Songs** also unlocks an opt-in **Search Source Switcher** (defaults to **Local**, stays completely hidden otherwise — zero built-in cloud search): trigger searches explicitly, play ephemeral results in a dedicated `Search (<Ext>): <query>` queue excluded from Library tabs and **Stats**, save single tracks via **Save to Local Folder…**, or preview playlists/albums before clicking **Add as Cloud Folder**.
- 🎶 **Multiple Independent Queues**: Spin up as many queues as your mood needs — rename, switch, reorder, clear, or delete them anytime. Every queue remembers its own track, playback position, shuffle order, and repeat mode (`Off` / `All` / `One`).
- 🎛️ **10-Band Equalizer**: Flip the master switch, dial in preamp (`-12 dB` to `+12 dB`), tweak 10 ISO bands (`31 Hz`–`16 kHz`), or pick from 8 presets (`Flat`, `Bass Boost`, `Treble Boost`, `Rock`, `Pop`, `Jazz`, `Classical`, `Vocal`) + `Custom`.
- 🎤 **Synced, Folder-Matched & Cloud Lyrics**: Enjoy an immersive translucent blurred lyrics view where your album art and global background shine right through across iOS, Android, macOS, and Windows! On playback, Musico stays strictly local and checks **Imported** `.lrc`/`.txt` → exact **Sidecar** `.lrc`/`.txt` → non-recursive **Same-Folder Fuzzy Match** → **Embedded** tags. Need lyrics from the web? Tap **Search Online…** for on-demand lookup via built-in **LRCLIB** — plus **Auto** and additional providers such as **NetEase Cloud Music** or **Apple Music** once their `"lyrics"` extensions are enabled — preview candidates live, toggle bilingual translations, save on confirm, and optionally export a sibling `.lrc` file (on by default when writable)! Supports multi-timestamp `.lrc`, UTF-8 / UTF-16 BOM / GB18030 decoding, customizable font size & alignment, **Sync to current line**, smooth auto-scroll, and tap-to-seek!
- ⏱️ **Sleep Timer & Listening Stats**: Drift off with `5`–`90` min presets, custom timers, or **End of current song**, and peek at your habits in **Stats** (**Recent**, **Plays**, **Not Lately**, **Never**, plus Top Artists & Top Albums).
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
- **Drag-and-Drop Import (Desktop)**: Drag local folders straight onto the macOS or Windows app window to mount them in **Folders**, or drop supported audio files to play them immediately (tracks outside your library are copied into your default music folder automatically).
- **Remove from Library vs. Delete Local Files & Folders**: Deleting a local track or folder gives you an explicit choice — **Remove from Library** (hides the song or folder from Musico without touching disk) or permanently/safely delete the underlying local files (**Move to Trash** on macOS, **Move to Recycle Bin** on Windows, or **Delete from Device** on iOS/iPadOS & Android, including Android's `Musico` folder).
- **Supported Audio Formats**:
  - **Apple (`Musico`)**: `mp3`, `m4a`, `aac`, `flac`, `wav`, `aif`, `aiff`, `caf`, `alac`
  - **Android (`MusicoAndroid`)**: `mp3`, `m4a`, `aac`, `flac`, `wav`, `aif`, `aiff`, `caf`, `alac`, `ogg`, `opus`, `webm`
  - **Windows (`MusicoWindows`)**: `mp3`, `m4a`, `aac`, `flac`, `wav`, `aif`, `aiff`, `wma`, `ogg`, `opus`

### 2. Cloud Folders (Google Drive & Extensions)
- **Mount a Cloud Source**: In **Folders**, tap **Add Cloud Folder** and paste a URL, share text, or ID (surrounding share-sheet text is stripped automatically!). By default **Auto-Detect** checks built-in **Google Drive** followed by your enabled JS extensions (such as **YouTube Search**, **Bilibili Search**, **NetEase Cloud Music**, or **Apple Music** from [`musico_plugins`](https://github.com/SEANPNEX/musico_plugins/releases/latest)), or you can pick a specific provider manually:
  - **Google Drive (Built-In)**: Public/shared folder links (recursively discovers all audio files across nested subfolders, including links with `resourcekey`) and single audio file links (`drive.google.com/drive/folders/…`, `drive.google.com/file/d/…`, `drive.google.com/open?id=…`, `drive.usercontent.google.com/download?id=…`), with automatic large-file confirmation handling! Public links work with **no sign-in or Client ID required**, and cached or saved offline tracks preserve their real embedded tags and album cover art automatically! Optionally connect your own OAuth 2.0 Client ID in **Settings → Google Drive Account** to access private Drive folders and bypass public download confirmation pages.
  - **YouTube, Bilibili, NetEase, Apple Music & Custom Extensions**: Any enabled extension installed in **Extensions** appears automatically with its custom `badgeLabel`, optional cookie prompt (when `"cookie"` capability is declared), QR/Web login, and lazy/progressive subfolder browsing (when `"tree"` capability is declared). Install official plugins from [`SEANPNEX/musico_plugins`](https://github.com/SEANPNEX/musico_plugins/releases/latest) in **Extensions**.
- **Optional Cookie & Proxy**: Add a **Cookie** only if you need restricted Google Drive links (when not using Google Drive OAuth) or an extension that requires session auth. Folder cookies stay strictly isolated per cloud folder root, while extensions declaring `"cookie"` can also store a masked **extension-level cookie** in **Extensions** (used for Cloud Search, Cloud Lyrics, ephemeral previews, and ephemeral streams; mounted Cloud Folders still prioritize their own folder cookie). On Windows, you can also set an **HTTP Proxy** for Google Drive, LRCLIB & proxy-capable extensions (e.g. `7890`, `127.0.0.1:7890`, or `http://127.0.0.1:7890`).
- **Save Offline & Merge**: Open a cloud folder's menu (or track menu) and pick **Save to Local Folder…** to download tracks locally (preserving embedded tags & cover art!). Whole-folder exports automatically convert or merge into a single cloud-tracked local entry in **Folders** while keeping your playlists, favourites, queues, custom order, and play stats intact, with `.musico-cloud.json` and **Update from Cloud** ready to pull newly added songs incrementally!

#### Google Drive OAuth 2.0 Client ID Setup Guide (Optional)
> **Public/shared Google Drive links work out of the box with no login or Client ID required.** You only need OAuth 2.0 if you want to mount **private** Google Drive folders or bypass Google Drive's public download confirmation pages.

**Why do you supply your own Client ID?**  
Google classifies `https://www.googleapis.com/auth/drive.readonly` as a restricted scope, which cannot be shipped inside an open-source app without annual CASA Tier 2 security verification and a 100-user cap. Creating your own free personal **Desktop app** OAuth client takes ~3 minutes, requires **zero Google app review**, and works across **macOS, Windows, and Android** (as well as iOS/iPadOS).

1. Open [Google Cloud Console](https://console.cloud.google.com/) and create a new project (or select an existing one).
2. Open **APIs & Services → Library**, search for **Google Drive API**, and click **Enable**.
3. Open **Google Auth Platform** from the navigation menu *(or **APIs & Services → OAuth consent screen** in the classic layout)*:
   - Set **Audience / User Type** to **External** (fill in any app name such as `Musico` and your email).
   - Leave the publishing status in **Testing** *(zero Google app verification required!)*.
   - Under **Audience → Test users**, click **+ Add users** and add **your own Gmail address**.
   - Under **Data Access** *(or **Scopes**)*, click **Add or remove scopes** and add both:
     - `https://www.googleapis.com/auth/drive.readonly`
     - `https://www.googleapis.com/auth/userinfo.email`
4. Open **Clients** *(under **Google Auth Platform**, or **APIs & Services → Credentials → + Create Credentials → OAuth client ID**)*:
   - Choose Application type: **Desktop app** *(even if you also use it on Android — Musico on macOS, Windows, and Android uses an automatic `http://127.0.0.1:<port>/oauth2callback` loopback listener, which Google enables automatically for **Desktop app** clients with no manual redirect URI setup!)*.
   - Click **Create**, then copy the generated **Client ID** and **Client Secret**.
5. In Musico, open **Settings → Google Drive Account**, paste your **OAuth Client ID** and **OAuth Client Secret**, and click **Sign In with Google**.
6. **7-day token expiry note**: Because personal **Testing** mode refresh tokens expire after **7 days**, simply click **Reconnect Google Drive** in **Settings → Google Drive Account** if prompted again after a week.

### 3. Extensions (Cloud Sources, Search & Lyrics)
- **Open Extension Manager**: Open **Extensions** from the sidebar (`Cmd+8` on macOS/iPadOS, **Extensions** toolbar button in **Folders** on iOS and Android, or the **Extensions** sidebar item on Windows).
- **Import & Permission Review**: Import a `.musicoext` bundle or `.musico.js` single-file script from local storage or an `https://` URL. Newly imported extensions stay disabled until you inspect their declared `permissions.hosts` and `capabilities` (`"playlist"`, `"tree"`, `"stream"`, `"cookie"`, `"proxy"`, `"lyrics"`, `"search"`, `"qr_login"`, `"web_login"`) and approve enabling them.
- **Settings, Login (`"qr_login"` / `"web_login"` / Cookie), Logs & Safe Removal**: Configure extension-specific toggles/inputs/selects, sign in via native QR code (`"qr_login"`), isolated ephemeral in-app WebView (`"web_login"` — `WKWebView` on Apple, `WebView` on Android, `WebView2` on Windows), or masked `cookies.txt` / cookie input (`"cookie"`, with automatic cookie redaction in logs on Android/Windows), inspect or clear the last 200 log entries for debugging, and disable or uninstall anytime without losing existing library tracks, playlists, stats, or cached audio (if the active search extension is disabled or removed, **Songs** cleanly resets back to **Local**).
- **Build Your Own**: Check out the [Extension Developer Guide](docs/extensions/README.md) ([中文开发指南](docs/extensions/README.zh-CN.md)) and starter templates under [`docs/extensions/`](docs/extensions/).

### 4. Browsing, Queues & Playback
- **Explore Your Library**: Jump between **Folders**, **Songs**, **Albums**, **Artists**, **Playlists**, **Queues**, and **Stats** (**Recent**, **Plays**, **Not Lately**, **Never**, plus Top Artists & Top Albums).
- **Track Menu Goodies**: Tap/click any track to play its list from there, or open its context/ellipsis menu for **Play Next**, **Add to Queue**, **Add to Another Queue**, **Play in New Queue**, **Add to Playlist**, **Add/Remove Favourites**, **Song Info**, and **Remove / Delete**.
- **Queue Juggling & Drag Reorder**: Manage multiple queues in **Queues** (or the desktop side Queue panel). Grab the drag handle (`≡`) in **Folders** and **Queues** to reorder tracks your way.
- **Lyrics**: Pop open **Lyrics** from the player bar or Full Player for an immersive translucent blurred canvas over your album art & global background. Playback stays strictly local and resolves **Imported** → exact **Sidecar** → **Same-Folder Fuzzy Match** → **Embedded** automatically. You can import `.lrc`/`.txt` files manually, customize lyrics font size & alignment, tap **Sync to current line** or any timed line to seek, or tap **Search Online…** to search built-in **LRCLIB** — plus **Auto** and any enabled `"lyrics"` extensions (such as **NetEase Cloud Music** or **Apple Music**) — preview lyrics, toggle translations, save only when confirmed, and optionally export a sibling `.lrc` file (on by default when writable).
- **Extension Cloud Search**: When one or more `"search"` extensions are enabled, **Songs** displays a conditional **Search Source Switcher** (**Local** by default; completely hidden when none are enabled). Searches run only on explicit submit (`Enter` / Search button), offer kind filters (**All / Songs / Playlists / Albums**) when supported, and load track playback into an ephemeral `Search (<Ext>): <query>` queue excluded from Library tabs and **Stats** — with single-track **Save to Local Folder…** and lightweight album/playlist preview sheets featuring **Add as Cloud Folder**.
- **Equalizer & Sleep Timer**: Open **Equalizer** for 10-band tuning & presets, or **Sleep Timer** to wind down after `5`–`90` minutes, a custom duration, or **End of current song**.
- **Song Info & Batch Tag Editor**: Open **Song Info** on any track to check audio specs (precision, frequency, bit rate, file size, channels, play history), quick-search by Artist/Album/Genre, or edit `Title`, `Artist`, `Album`, `Genre`, and cover art. Need to fix multi-part tracks or Bilibili uploads at once? Tap **Select** in **Folders**, **Songs**, **Playlists**, or **Albums** (or `Ctrl+Click` / `Shift+Click` / `Ctrl+A` on Windows) to batch-edit **Artist** and **Album** tags across multiple local or cloud songs in one go!

### 5. Appearance & Desktop Power Features
- **Unified Settings & Custom Appearance** (`Cmd+,` on macOS, `Ctrl+,` on Windows, or **Settings** in the app):
  - Switch between **System**, **Light**, and **Dark** modes, or pick from curated background and foreground/accent presets.
  - Dial in custom **Background**, **Accent**, and **Text** colors with built-in hex/RGB/system pickers and a live **Now Playing Preview** card.
  - Built-in **WCAG 2.1 contrast checking** alerts you if text or accent colors become hard to read and offers one-click **Auto-fix Contrast**.
  - Set a custom **Background Image** with adjustable **Image Visibility** (opacity) and **Background Blur**.
  - On desktop, fine-tune frosted window translucency (`NSVisualEffectView` on macOS, Acrylic & Windows 11 DWM rounded corners/backdrop on Windows) plus independent sidebar background color (and sidebar opacity on Windows).
- **Global Command Palette** (`Cmd+K` / `Ctrl+K`): Search and jump directly to songs, albums, artists, playlists, queues, library views, or app actions without touching the mouse.
- **Floating Mini-Player Window** (`Cmd+Shift+N` / `Ctrl+Shift+N`): Toggle a compact, standalone desktop Mini-Player window with album artwork, track progress, and playback controls.

### 6. Keyboard Shortcuts
- **Windows**:
  - `Ctrl+K`: Open / close **Command Palette**
  - `Ctrl+F`: Jump to **Songs** & focus search
  - `Ctrl+,`: Open **Settings**
  - `Ctrl+Shift+N`: Toggle Floating **Mini-Player** window
  - `Ctrl+Shift+L` (or `L` when not typing): Toggle **Lyrics** side panel
  - `Ctrl+Shift+Q` (or `Q` when not typing): Toggle **Queue** side panel
  - `Space`: Play / Pause (when not typing in a text box)
  - `Ctrl+Left` / `Ctrl+Right`: Previous / Next track
  - `Shift+Left` / `Shift+Right`: Seek backward / forward 10s (when not typing)
  - `Ctrl+Up` / `Ctrl+Down`: Volume up / down (`±5%`)
  - `Ctrl+Shift+M` (or `M` when not typing): Mute / Unmute
  - `Esc`: Close Command Palette, Full Player, or active modal/dialog
- **macOS / iPadOS**:
  - `Cmd+K`: Open / toggle **Quick Jump** (Global Command Palette)
  - `Cmd+F`: Jump to **Songs** & focus search
  - `Cmd+R`: Rescan library
  - `Cmd+,`: Open **Settings**
  - `Cmd+1`–`Cmd+8`: Switch between **Folders**, **Songs**, **Albums**, **Artists**, **All Playlists**, **Queues**, **Stats**, and **Extensions**
  - `Cmd+Shift+N`: Open Floating **Mini-Player** window
  - `Cmd+Shift+L` / `Cmd+Shift+Q`: Toggle **Lyrics** / **Queue** side panel
  - `Cmd+P` / `Cmd+Left` / `Cmd+Right`: Play / Pause, Previous / Next track
  - `Cmd+Shift+Left` / `Cmd+Shift+Right`: Seek backward / forward 10s
  - `Cmd+Up` / `Cmd+Down`: Volume up / down (`±5%`)
  - `Cmd+Shift+M`: Mute / Unmute
  - `Cmd+Shift+S` / `Cmd+Shift+R`: Toggle Shuffle / Cycle Repeat
  - `Cmd+Shift+P`: Open / toggle **Now Playing**
  - `Esc`: Close Command Palette or Full Player (plus standard system media keys)

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
