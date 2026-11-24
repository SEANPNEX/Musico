<div align="center">

# ♪ Musico ♪

[English](README.md) | **简体中文**

[![CI](https://github.com/SEANPNEX/Musico/actions/workflows/ci.yml/badge.svg)](https://github.com/SEANPNEX/Musico/actions/workflows/ci.yml)
[![Release](https://github.com/SEANPNEX/Musico/actions/workflows/release.yml/badge.svg)](https://github.com/SEANPNEX/Musico/actions/workflows/release.yml)
[![Latest Release](https://img.shields.io/github/v/release/SEANPNEX/Musico)](https://github.com/SEANPNEX/Musico/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

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
| [`Musico/`](Musico/) | iOS 17+、iPadOS、macOS (Catalyst) | Swift、SwiftUI (`@Observable`)、`AVAudioEngine` + `AVAudioUnitEQ`、`MPNowPlayingInfoCenter`、内置 `JavaScriptCore`（`Compression` + `CryptoKit`） | Apple HIG — iOS 上轻巧的标签页与弹窗，macOS/iPadOS 上清爽的侧边栏 + 检查器 |
| [`MusicoAndroid/`](MusicoAndroid/) | Android 8.0+ (API 26+) | Kotlin、Jetpack Compose、AndroidX Media3 (ExoPlayer + `MediaSessionService`)、SAF `DocumentFile`、`DynamicsProcessing` 均衡器、内置 `QuickJS`（`quickjs-kt`） | Material 3 / Material You — 动态取色、自适应 `NavigationBar` / `NavigationRail`、`ModalBottomSheet` |
| [`MusicoWindows/`](MusicoWindows/) | Windows 10 (2004+) & Windows 11 | C# / .NET 8 WPF、NAudio (`BiQuadFilter` 10 段均衡器)、TagLibSharp、Windows 10 SMTC、内置 `Jint` | Windows 10 Fluent Design — `Segoe UI`、亚克力 `NavigationView`、锐利 0px 几何、Reveal 悬停、浅色/深色主题同步 |

---

## ✨ 它都能做些什么

- 📁 **文件夹优先的曲库与云端小窝**：自由挂载多个本地音乐文件夹（`Documents` / `MyMusic` + 自选文件夹），重启后目录层级原封不动！移动了硬盘或文件夹？点一下 **Relocate（重新定位）** 就乖乖找回来。还能在 **Folders** 里直接挂载 **YouTube / YouTube Music**（歌单、Mix 合集与单曲）、**Bilibili**（单 P / 多 P 视频、收藏夹与合集）、**Google Drive**（公开分享文件夹递归扫描与单曲音频链接）以及你自己安装的扩展音源！
- 🧩 **跨平台 JavaScript 扩展**：写一份 CommonJS 脚本（`.musico.js`）或扩展包（`.musicoext`），就能在 Apple（`JavaScriptCore`）、Android（`QuickJS`）和 Windows（`Jint`）上零 WebView 原生运行！一套扩展体系同时支持 **自定义云端音源**、**扩展专属云端搜索**（`"search"`）与 **自定义云端歌词源**（`"lyrics"`）——自带启用前权限审查、严格域名白名单（`permissions.hosts`）、扩展专属设置、可选遮罩隐藏的扩展级 Cookie 与原生扫码登录（`"qr_login"`）、200 条调试日志视图、多层子文件夹渐进加载，并与 **Save to Local Folder（存到本地）** / **Update from Cloud（云端更新）** 完全互通。详见 [扩展开发指南](docs/extensions/README.zh-CN.md)（[English Guide](docs/extensions/README.md)）。
- 🌐 **多语言支持（`English` & `简体中文`）**：默认随操作系统语言自动切换（**跟随系统**），也支持在 Apple、Android 和 Windows 应用内随时实时切换语言。
- 🔍 **花式浏览、即时本地搜 & 扩展云端搜**：支持 **Folders（文件夹）**、**Songs（歌曲）**（支持按标题/艺人/专辑/添加时间排序，以及忽略变音符号的即时搜索）、**Albums（专辑）**（按碟片与音轨号排排坐）、**Artists（艺人）** 和 **Playlists（播放列表）**（自带 **Favourites** 收藏夹 + 随意创建/重命名/删除/排序的自定义歌单）。当你启用声明了 `"search"` 能力的扩展时，**Songs** 还会解锁可选 **搜索源切换器**（默认仍是 **Local（本地）**，未启用扩展时完全隐藏——绝不内置云端搜歌）：按回车或点搜索显式触发，临时试听队列 `Search (<Ext>): <query>` 与曲库及 **Stats（听歌统计）** 完全隔离，单曲可直接 **Save to Local Folder…（存到本地）**，歌单/专辑还可先轻量预览再点 **Add as Cloud Folder（添加为云端文件夹）**。
- 🎶 **多独立播放队列**：想开几个队列就开几个！创建、重命名、切换、排序、清空、删除随心所欲，每个队列都会贴心记住自己的当前曲目、播放进度、随机顺序和循环模式（`Off` / `All` / `One`）。
- 🎛️ **10 段均衡器**：总开关、前级增益（`-12 dB` 至 `+12 dB`）、10 个 ISO 频段（`31 Hz`–`16 kHz`），内置 8 套预设（`Flat`、`Bass Boost`、`Treble Boost`、`Rock`、`Pop`、`Jazz`、`Classical`、`Vocal`）外加 `Custom` 自定义模式。
- 🎤 **同步、同目录匹配与云端歌词**：切歌播放时严格只在本地按 **手动导入** `.lrc`/`.txt` → **同名 Sidecar** `.lrc`/`.txt` → **同文件夹模糊匹配（非递归）** → **音频内嵌标签** 自动加载，绝不后台偷跑联网！需要联网找词时点击 **Search Online…（在线搜索歌词）**，支持 **Auto（自动）**、内置 **LRCLIB** 与 **网易云音乐** 以及你的 `"lyrics"` 扩展，支持实时预览、双语翻译开关、确认后才保存以及顺手导出同级 `.lrc` 文件（目录可写时默认勾选）；同样支持多时间戳 `.lrc`、UTF-8 / UTF-16 BOM / GB18030 编码、实时自动滚动与点击跳播！
- ⏱️ **睡眠定时器 & 听歌统计**：`5`–`90` 分钟预设、自定义时长或 **当前歌曲结束后停止**，伴你安心入睡；还能在 **Stats** 里翻看 **Recent（最近播放）**、**Plays（播放次数）**、**Not Lately（久未播放）** 和 **Never（从未听过）**。
- 🎧 **系统媒体控制**：iOS/macOS 锁屏与控制中心、Android `MediaSessionService` 通知栏与媒体键、Windows 10 SMTC 统统安排妥当。
- 🚀 **应用内更新检测与升级**：自动检查 GitHub Releases（`releases/latest`），通过 `SHA256SUMS.txt` 做 SHA-256 校验，并支持 **跳过此版本**（支持 **Windows** 安装包 `.exe` 与自替换便携版 `.zip`、**Android** `FileProvider` APK 安装器以及 **macOS Catalyst** 自动下载并打开 `.dmg`；iOS 因个人签名跳过）。

---

## 📦 下载与安装

前往 [**Releases**](https://github.com/SEANPNEX/Musico/releases/latest) 页面即可抱走最新版 `(｡・ω・｡)ﾉ♡`：

- **Windows 10 / 11（x64，自带 .NET 8 — 无需额外装运行库）**：
  - **安装版**：运行 `MusicoWindows-<version>-win-x64-Setup.exe`，支持开始菜单/桌面快捷方式和干净卸载。
  - **便携版**：解压 `MusicoWindows-<version>-win-x64-portable.zip` 后直接运行 `MusicoWindows.exe`（同级目录留着 `portable.txt`，曲库和设置就会乖乖存在 `.\Data\` 里）。
- **Android (8.0+)**：
  - 推荐直接安装持续统一签名的 `MusicoAndroid-<version>-release.apk`（可丝滑支持应用内直接更新），日常本地调试也可选 `MusicoAndroid-<version>-debug.apk`。
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

### 2. 云端文件夹（YouTube、Bilibili、Google Drive 与自定义扩展）
- **挂载云端音源**：在 **Folders** 里选 **Add Cloud Folder（添加云端文件夹）**，直接粘贴链接、分享文案或 ID（会自动剥离前后附带的分享文字与标点！）。默认 **Auto-Detect（自动识别）** 会依次匹配内置音源（**YouTube**、**Bilibili**、**Google Drive**）以及已启用的 JS 扩展，也可以手动选择指定来源：
  - **YouTube / YouTube Music**：播放列表、`RD` 混音歌单（Mix）、专辑浏览链接或单曲视频链接（`youtube.com/playlist?list=…`、`music.youtube.com/playlist?list=…`、`youtube.com/watch?v=…`、`youtu.be/…`、Shorts），公开内容免登录直接听！
  - **Bilibili**：`BV`/`av` 视频链接（支持多 P）、收藏夹（`favlist`）、合集/系列以及 `b23.tv` 短链。
  - **Google Drive**：公开分享文件夹链接（支持 `resourcekey` 并自动递归发现所有嵌套子目录中的音频文件）与单曲音频链接（`drive.google.com/drive/folders/…`、`drive.google.com/file/d/…`、`drive.google.com/open?id=…`、`drive.usercontent.google.com/download?id=…`），大文件病毒扫描确认页也会自动处理！
  - **自定义扩展**：在 **扩展（Extensions）** 中启用的扩展会自动出现在此处，展示自定义 `badgeLabel` 标签、可选 Cookie 输入（当声明 `"cookie"` 能力时）以及嵌套子目录渐进/按需加载（当声明 `"tree"` 能力时）。
- **可选 Cookie 与代理**：只有访问私有播放列表、受限 Google Drive 文件、想要更高的 B 站音质或某些需要登录态的扩展时才需要填 **Cookie**。各云端目录的 Cookie 严格隔离，同时声明了 `"cookie"` 能力的扩展还可以在 **扩展管理** 中保存遮罩隐藏的 **扩展级 Cookie**（用于云端搜索、临时预览与临时流试听；已挂载云端文件夹依然优先使用目录自己的 Cookie）。Windows 端还支持为 YouTube、Google Drive 及声明代理能力的扩展设置 **HTTP Proxy**（例如 `7890`、`127.0.0.1:7890` 或 `http://127.0.0.1:7890`）。
- **离线存本地 & 增量同步**：在云端文件夹菜单（或单曲菜单）点 **Save to Local Folder…** 就能把歌下载到本地并顺手加入曲库。已关联云端源的本地文件夹会保存 `.musico-cloud.json` 清单，并出现 **Update from Cloud（从云端更新）**，一键增量拉取新歌！

### 3. 扩展管理（云端音源、搜索与歌词）
- **打开扩展管理**：从 macOS/iPadOS 侧边栏（或快捷键 `Cmd+8`）、iOS 与 Android 端 **Folders** 顶栏的扩展图标按钮，或 Windows 侧边栏的 **扩展（Extensions）** 进入。
- **导入与权限审查**：支持从本地文件或 `https://` 链接导入 `.musicoext` 压缩包或 `.musico.js` 单文件脚本。新导入的扩展默认保持停用状态，只有在你查看并确认其声明的 `permissions.hosts`（网络域名权限）与 `capabilities`（包括 `"playlist"`、`"tree"`、`"stream"`、`"cookie"`、`"proxy"`、`"lyrics"`、`"search"`）后才会启用。
- **设置、扩展 Cookie、日志与安全停用**：可直接在应用内修改扩展配置，为声明 `"cookie"` 的扩展填写遮罩隐藏的扩展级 Cookie（Android 与 Windows 端还会自动脱敏 `ctx.log` 中的敏感文本），查看或清空最近 200 条调试日志，并可随时停用或卸载扩展——曲库中已有歌曲、歌单、统计和已缓存音频都会完好保留（若正在使用的搜索扩展被停用或卸载，**歌曲** 搜索会自动切回 **Local（本地）**）。
- **动手写一个扩展**：请参阅 [扩展开发指南](docs/extensions/README.zh-CN.md)（[English Guide](docs/extensions/README.md)）以及 [`docs/extensions/`](docs/extensions/) 下的起步模板与本地调试工具。

### 4. 浏览、队列与播放控制
- **逛逛曲库**：在 **Folders**、**Songs**、**Albums**、**Artists**、**Playlists**、**Queues** 和 **Stats**（**Recent**、**Plays**、**Not Lately**、**Never**）之间自由穿梭。
- **单曲小菜单**：点击曲目即可从当前位置开播；打开上下文/更多菜单可以 **Play Next（下一首播放）**、**Add to Queue（添加到队列）**、**Add to Another Queue（添加到其他队列）**、**Play in New Queue（在新队列中播放）**、**Add to Playlist（添加到播放列表）**、**Add/Remove Favourites（收藏/取消收藏）** 和 **Song Info（歌曲信息）**。
- **多队列与拖拽排序**：在 **Queues**（或桌面端右侧队列面板）管理所有队列，在 **Folders** 和 **Queues** 中拖动曲目右侧手柄（`≡`）即可随心排顺序。
- **歌词**：从底部播放栏或全屏播放器打开 **Lyrics**。播放切歌时严格只在本地按 **手动导入** → **同名 Sidecar** → **同文件夹模糊匹配（非递归）** → **音频内嵌标签** 自动查找；也可手动导入 `.lrc`/`.txt`、点击带时间戳歌词跳转进度，或点 **Search Online…（在线搜索歌词）** 在 **Auto**、**LRCLIB**、**网易云音乐** 与已启用的 `"lyrics"` 扩展之间搜索对比——支持实时预览、双语翻译开关、仅确认后保存，并可勾选同步导出同目录 `.lrc` 文件（目录可写时默认开启）。
- **扩展云端搜索**：启用至少一个 `"search"` 扩展后，**Songs（歌曲）** 页面会出现 **搜索源切换器**（默认 **Local**，未启用扩展时不显示）。仅在按回车或点搜索时发起请求，支持按扩展声明的 **全部 / 歌曲 / 歌单 / 专辑** 筛选；点击单曲会将本次搜索已加载曲目放入临时队列 `Search (<Ext>): <query>` 播放，播放记录和临时曲目完全不混入曲库标签或 **Stats**，还支持单曲 **Save to Local Folder…（存到本地）**，点击歌单/专辑则会弹出轻量预览窗并支持一键 **Add as Cloud Folder（添加为云端文件夹）**。
- **均衡器 & 睡眠定时器**：打开 **Equalizer** 调音色，或打开 **Sleep Timer** 设定 `5`–`90` 分钟、自定义倒计时或 **当前歌曲结束后停止**。
- **歌曲信息与标签编辑**：打开 **Song Info** 可以查看位深、采样率、比特率、文件大小、声道数与播放记录，点击 Artist / Album / Genre 还能一键搜索，或者直接修改 `Title`、`Artist`、`Album`、`Genre` 和封面图片。

### 5. 快捷键
- **Windows**：
  - `Space`：播放 / 暂停（非输入框聚焦时）
  - `Ctrl+Left` / `Ctrl+Right`：上一曲 / 下一曲
  - `Ctrl+F`：跳转到 **Songs** 并聚焦搜索框
  - `Esc`：关闭全屏播放器或当前弹窗
- **macOS / iPadOS**：
  - `Cmd+F`：跳转到 **歌曲（Songs）** 并聚焦搜索框
  - `Cmd+R`：重新扫描曲库
  - `Cmd+1`–`Cmd+8`：在 **文件夹**、**歌曲**、**专辑**、**艺人**、**播放列表**、**队列**、**统计** 与 **扩展** 之间快速切换
  - `Cmd+P` / `Cmd+Left` / `Cmd+Right`：播放 / 暂停、上一曲 / 下一曲（顶部 **播放 / Playback** 菜单还提供随机播放、循环模式与显示/隐藏正在播放）
  - `Esc`：关闭全屏播放器（同时支持系统媒体键）

### 6. Windows 系统托盘与关闭行为
- 每个应用版本首次关闭窗口时，可选择 **最小化到系统托盘** 或 **退出程序**。**确定** 保存并执行选择，**取消** 不修改设置、不退出程序；同一版本后续关闭会直接沿用已确认的选择。
- 左侧边栏 **收听统计** 后的 **退出** 项打开退出设置。此处点击 **确定** 仅保存设置，不关闭窗口，也不会跳过新版本的首次关闭确认。标题栏的最小化按钮仍然执行普通任务栏最小化。
- 右键通知区域的图标，可使用 **播放 / 暂停**、**上一首**、**下一首**、**展开主页面** 和 **退出**；双击图标也可恢复窗口。托盘中的 **退出** 始终真正结束程序；应用更新退出及 Windows 注销/关机同样不会触发关闭提示。
- 设置保存在应用数据目录中的 `window_prefs.json`，便携模式下使用程序旁的 `Data` 目录。

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

### 扩展一致性测试工具（`docs/extensions/`）
需要 Node.js 18+：
```bash
node docs/extensions/tools/mock-runner.mjs --self-test
```

---

## 📂 仓库结构

```text
.
├── .github/workflows/
│   ├── ci.yml              # 持续集成（Android、Windows、Apple）
│   └── release.yml         # Tag 触发构建与 GitHub Release 发布
├── docs/extensions/        # 扩展开发指南、起步模板、一致性测试样例与本地 Node 调试器
├── Musico/                 # 原生 SwiftUI 应用（iOS / iPadOS / macOS Catalyst）
├── MusicoAndroid/          # 原生 Kotlin + Compose Material 3 + Media3 应用
├── MusicoWindows/          # 原生 C# + WPF Windows 10 Fluent Design 应用 + NSIS 安装程序
├── README.md
├── README.zh-CN.md
├── LICENSE
└── THIRD_PARTY_NOTICES.md
```

---

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源。第三方依赖的许可与致谢信息请参阅 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
