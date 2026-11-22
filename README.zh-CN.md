<div align="center">

# ♪ Musico ♪

[English](README.md) | **简体中文**

[![CI](https://github.com/SEANPNEX/local_music_player/actions/workflows/ci.yml/badge.svg)](https://github.com/SEANPNEX/local_music_player/actions/workflows/ci.yml)
[![Release](https://github.com/SEANPNEX/local_music_player/actions/workflows/release.yml/badge.svg)](https://github.com/SEANPNEX/local_music_player/actions/workflows/release.yml)
[![Latest Release](https://img.shields.io/github/v/release/SEANPNEX/local_music_player)](https://github.com/SEANPNEX/local_music_player/releases/latest)

**一款离线、超爱文件夹、支持多播放队列的本地音乐播放器——全平台 100% 原生开发，拒绝 Web 套壳！(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧**

*灵感源自 Musicolet，并向温暖可爱的 [YRRLYB/Aria](https://github.com/YRRLYB/Aria) 致敬 ♪(´▽｀)*

</div>

---

## 💖 致敬与灵感

- **[Musicolet](https://krosbits.in/musicolet/)**：文件夹优先、多播放队列离线播放器的初心之作，把本地听歌这件事做得干净又顺手。
- **[YRRLYB/Aria](https://github.com/YRRLYB/Aria)**：特别向 Aria 致敬！一个简约又干净的音乐播放器，把本地无损曲库和 B 站等在线音源揉到同一张桌面上，打开就能接着昨天那首继续听 `(・ω・)ﾉ`。Musico 也把这份轻松自在的听歌体验带到了 Apple、Android 和 Windows 端～

---

## 🖥️ 原生平台阵容

| 目录 | 目标平台 | 技术栈 | 原生 UI 气质 |
|---|---|---|---|
| [`Musico/`](Musico/) | iOS 17+、iPadOS、macOS (Catalyst) | Swift、SwiftUI (`@Observable`)、`AVAudioEngine` + `AVAudioUnitEQ`、`MPNowPlayingInfoCenter` | Apple HIG — iOS 上轻巧的标签页与弹窗，macOS/iPadOS 上清爽的侧边栏 + 检查器 |
| [`MusicoAndroid/`](MusicoAndroid/) | Android 8.0+ (API 26+) | Kotlin、Jetpack Compose、AndroidX Media3 (ExoPlayer + `MediaSessionService`)、SAF `DocumentFile`、`DynamicsProcessing` 均衡器 | Material 3 / Material You — 动态取色、自适应 `NavigationBar` / `NavigationRail`、`ModalBottomSheet` |
| [`MusicoWindows/`](MusicoWindows/) | Windows 10 (2004+) & Windows 11 | C# / .NET 8 WPF、NAudio (`BiQuadFilter` 10 段均衡器)、TagLibSharp、Windows 10 SMTC | Windows 10 Fluent Design — `Segoe UI`、亚克力 `NavigationView`、锐利 0px 几何、Reveal 悬停、浅色/深色主题同步 |

---

## ✨ 它都能做些什么

- 📁 **文件夹优先的曲库与云端小窝**：自由挂载多个本地音乐文件夹（`Documents` / `MyMusic` + 自选文件夹），重启后目录层级原封不动！移动了硬盘或文件夹？点一下 **Relocate（重新定位）** 就乖乖找回来。还能在 **Folders** 里直接挂载 **YouTube / YouTube Music**（歌单、Mix 合集与单曲）、**Bilibili**（单 P / 多 P 视频、收藏夹与合集）以及 **Google Drive**（公开分享文件夹递归扫描与单曲音频链接）！
- 🌐 **多语言支持（`English` & `简体中文`）**：默认随操作系统语言自动切换（**跟随系统**），也支持在 Apple、Android 和 Windows 应用内随时实时切换语言。
- 🔍 **花式浏览与秒搜**：支持 **Folders（文件夹）**、**Songs（歌曲）**（支持按标题/艺人/专辑/添加时间排序，以及忽略变音符号的即时搜索）、**Albums（专辑）**（按碟片与音轨号排排坐）、**Artists（艺人）** 和 **Playlists（播放列表）**（自带 **Favourites** 收藏夹 + 随意创建/重命名/删除/排序的自定义歌单）。
- 🎶 **多独立播放队列**：想开几个队列就开几个！创建、重命名、切换、排序、清空、删除随心所欲，每个队列都会贴心记住自己的当前曲目、播放进度、随机顺序和循环模式（`Off` / `All` / `One`）。
- 🎛️ **10 段均衡器**：总开关、前级增益（`-12 dB` 至 `+12 dB`）、10 个 ISO 频段（`31 Hz`–`16 kHz`），内置 8 套预设（`Flat`、`Bass Boost`、`Treble Boost`、`Rock`、`Pop`、`Jazz`、`Classical`、`Vocal`）外加 `Custom` 自定义模式。
- 🎤 **同步 & 纯文本歌词**：按 **手动导入** `.lrc`/`.txt` → **同目录同名文件（Sidecar）** `.lrc`/`.txt` → **音频内嵌标签** 自动匹配；支持多时间戳 `.lrc`、UTF-8 / UTF-16 BOM / GB18030 编码、实时自动滚动，点哪句唱哪句！
- ⏱️ **睡眠定时器 & 听歌统计**：`5`–`90` 分钟预设、自定义时长或 **当前歌曲结束后停止**，伴你安心入睡；还能在 **Stats** 里翻看 **Recent（最近播放）**、**Plays（播放次数）**、**Not Lately（久未播放）** 和 **Never（从未听过）**。
- 🎧 **系统媒体控制**：iOS/macOS 锁屏与控制中心、Android `MediaSessionService` 通知栏与媒体键、Windows 10 SMTC 统统安排妥当。
- 🚀 **应用内更新检测与升级**：自动检查 GitHub Releases（`releases/latest`），通过 `SHA256SUMS.txt` 做 SHA-256 校验，并支持 **跳过此版本**（支持 **Windows** 安装包 `.exe` 与自替换便携版 `.zip`、**Android** `FileProvider` APK 安装器以及 **macOS Catalyst** 自动下载并打开 `.dmg`；iOS 因个人签名跳过）。

---

## 📦 下载与安装

前往 [**Releases**](https://github.com/SEANPNEX/local_music_player/releases/latest) 页面即可抱走最新版 `(｡・ω・｡)ﾉ♡`：

- **Windows 10 / 11（x64，自带 .NET 8 — 无需额外装运行库）**：
  - **安装版**：运行 `MusicoWindows-<version>-win-x64-Setup.exe`，支持开始菜单/桌面快捷方式和干净卸载。
  - **便携版**：解压 `MusicoWindows-<version>-win-x64-portable.zip` 后直接运行 `MusicoWindows.exe`（同级目录留着 `portable.txt`，曲库和设置就会乖乖存在 `.\Data\` 里）。
- **Android (8.0+)**：
  - 个人测试可直接安装 `MusicoAndroid-<version>-debug.apk`，或下载 `MusicoAndroid-<version>-release-unsigned.apk` 自行签名。
- **macOS (Catalyst)**：
  - 打开 `Musico-<version>-macOS.dmg`，把 `Musico.app` 拖进 `Applications`（采用 ad-hoc 签名；首次打开如遇 Gatekeeper 提示，右键 → 打开即可）。
- **iOS (17+)**：
  - 下载 `Musico-<version>-iOS-unsigned.ipa`，使用 AltStore、SideStore、Sideloadly 或 Xcode 签名安装。

---

## 🎀 使用指南

### 1. 添加音乐与管理文件夹
- **默认去哪找歌**：
  - **macOS**：默认包含 Mac 上的 `Music`（音乐）文件夹。
  - **iOS / iPadOS**：把歌放进 `文件 › 我的 iPhone/iPad › Musico`，或在“文件”里添加任意文件夹。
  - **Android**：默认使用应用的 `Musico Folder`，也支持通过系统文件夹选择器授权任意目录。
  - **Windows**：默认包含 `MyMusic`（我的音乐）。便携版模式下（`MusicoWindows.exe` 同级目录有 `portable.txt`），所有数据都收在 `.\Data\` 里，不打扰 `%LOCALAPPDATA%\Musico`。
- **添加、扫描与找回目录**：在 **Folders** 点 **Add Folder** 添加本地目录；新拷了歌就点 **Rescan**（手机端下拉刷新）。磁盘或文件夹挪了窝？用 **Locate Again…**（**Relocate**）重新指路即可，歌单和统计都会好好留着；也可以用 **Restore Removed Subfolders** 找回之前隐藏的子文件夹。
- **支持的音频格式**：
  - **Apple (`Musico`)**：`mp3`、`m4a`、`aac`、`flac`、`wav`、`aif`、`aiff`、`caf`、`alac`
  - **Android (`MusicoAndroid`)**：`mp3`、`m4a`、`aac`、`flac`、`wav`、`aif`、`aiff`、`caf`、`alac`、`ogg`、`opus`、`webm`
  - **Windows (`MusicoWindows`)**：`mp3`、`m4a`、`aac`、`flac`、`wav`、`aif`、`aiff`、`wma`、`ogg`、`opus`

### 2. 云端文件夹（YouTube、Bilibili 与 Google Drive）
- **挂载云端音源**：在 **Folders** 里选 **Add Cloud Folder（添加云端文件夹）**，直接粘贴链接、分享文案或 ID（会自动剥离前后附带的分享文字与标点！）：
  - **YouTube / YouTube Music**：播放列表、`RD` 混音歌单（Mix）、专辑浏览链接或单曲视频链接（`youtube.com/playlist?list=…`、`music.youtube.com/playlist?list=…`、`youtube.com/watch?v=…`、`youtu.be/…`、Shorts），公开内容免登录直接听！
  - **Bilibili**：`BV`/`av` 视频链接（支持多 P）、收藏夹（`favlist`）、合集/系列以及 `b23.tv` 短链。
  - **Google Drive**：公开分享文件夹链接（支持 `resourcekey` 并自动递归发现所有嵌套子目录中的音频文件）与单曲音频链接（`drive.google.com/drive/folders/…`、`drive.google.com/file/d/…`、`drive.google.com/open?id=…`、`drive.usercontent.google.com/download?id=…`），大文件病毒扫描确认页也会自动处理！
- **可选 Cookie 与代理**：只有访问私有播放列表、受限 Google Drive 文件或想要更高的 B 站音质时才需要填 **Cookie**。Windows 端还贴心地为 YouTube 与 Google Drive 准备了 **HTTP Proxy** 选项（例如 `7890`、`127.0.0.1:7890` 或 `http://127.0.0.1:7890`）。
- **离线存本地 & 增量同步**：在云端文件夹菜单（或单曲菜单）点 **Save to Local Folder…** 就能把歌下载到本地并顺手加入曲库。已关联云端源的本地文件夹会保存 `.musico-cloud.json` 清单，并出现 **Update from Cloud（从云端更新）**，一键增量拉取新歌！

### 3. 浏览、队列与播放控制
- **逛逛曲库**：在 **Folders**、**Songs**、**Albums**、**Artists**、**Playlists**、**Queues** 和 **Stats**（**Recent**、**Plays**、**Not Lately**、**Never**）之间自由穿梭。
- **单曲小菜单**：点击曲目即可从当前位置开播；打开上下文/更多菜单可以 **Play Next（下一首播放）**、**Add to Queue（添加到队列）**、**Add to Another Queue（添加到其他队列）**、**Play in New Queue（在新队列中播放）**、**Add to Playlist（添加到播放列表）**、**Add/Remove Favourites（收藏/取消收藏）** 和 **Song Info（歌曲信息）**。
- **多队列与拖拽排序**：在 **Queues**（或桌面端右侧队列面板）管理所有队列，在 **Folders** 和 **Queues** 中拖动曲目右侧手柄（`≡`）即可随心排顺序。
- **歌词**：从底部播放栏或全屏播放器打开 **Lyrics**，支持手动导入 `.lrc`/`.txt` 文件，点击带时间戳的歌词行还能直接跳转进度。
- **均衡器 & 睡眠定时器**：打开 **Equalizer** 调音色，或打开 **Sleep Timer** 设定 `5`–`90` 分钟、自定义倒计时或 **当前歌曲结束后停止**。
- **歌曲信息与标签编辑**：打开 **Song Info** 可以查看位深、采样率、比特率、文件大小、声道数与播放记录，点击 Artist / Album / Genre 还能一键搜索，或者直接修改 `Title`、`Artist`、`Album`、`Genre` 和封面图片。

### 4. 快捷键
- **Windows**：
  - `Space`：播放 / 暂停（非输入框聚焦时）
  - `Ctrl+Left` / `Ctrl+Right`：上一曲 / 下一曲
  - `Ctrl+F`：跳转到 **Songs** 并聚焦搜索框
  - `Esc`：关闭全屏播放器或当前弹窗
- **macOS / iPadOS**：
  - `Cmd+F`：跳转到 **歌曲（Songs）** 并聚焦搜索框
  - `Cmd+R`：重新扫描曲库
  - `Cmd+1`–`Cmd+7`：在 **文件夹**、**歌曲**、**专辑**、**艺人**、**播放列表**、**队列** 与 **统计** 之间快速切换
  - `Cmd+P` / `Cmd+Left` / `Cmd+Right`：播放 / 暂停、上一曲 / 下一曲（顶部 **播放 / Playback** 菜单还提供随机播放、循环模式与显示/隐藏正在播放）
  - `Esc`：关闭全屏播放器（同时支持系统媒体键）

---

## 🛠️ 本地构建与测试

### iOS / iPadOS / macOS (`Musico/`)
需要安装 Xcode（iOS 17+ SDK）：
```bash
cd Musico
./scripts/test.sh
./scripts/build-release.sh   # 输出 dist/Musico-*-macOS.dmg 与 dist/Musico-*-iOS-unsigned.ipa
# 或直接在 Xcode 中打开 Musico.xcodeproj
```

### Android (`MusicoAndroid/`)
需要 JDK 17 与 Android SDK 35：
```bash
cd MusicoAndroid
./gradlew testDebugUnitTest assembleDebug
```

### Windows 10 (`MusicoWindows/`)
需要 .NET 8 SDK（Windows 平台目标为 `net8.0-windows10.0.19041.0`；单元测试也可在 macOS/Linux 上通过 `net8.0` 跨平台运行）：
```powershell
cd MusicoWindows
dotnet test MusicoWindows.Tests\MusicoWindows.Tests.csproj
dotnet build MusicoWindows\MusicoWindows.csproj -c Release
```

如需构建 Windows 安装包与便携版压缩包（需要 `.NET 8 SDK` + NSIS `makensis`）：
```bash
cd MusicoWindows
./scripts/build-release.sh
```

---

## 📂 仓库结构

```text
.
├── .github/workflows/
│   ├── ci.yml              # 持续集成（Android、Windows、Apple）
│   └── release.yml         # Tag 触发构建与 GitHub Release 发布
├── Musico/                 # 原生 SwiftUI 应用（iOS / iPadOS / macOS Catalyst）
├── MusicoAndroid/          # 原生 Kotlin + Compose Material 3 + Media3 应用
├── MusicoWindows/          # 原生 C# + WPF Windows 10 Fluent Design 应用 + NSIS 安装程序
├── README.md
└── README.zh-CN.md
```
