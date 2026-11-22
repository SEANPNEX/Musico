import Foundation
import Observation
import SwiftUI

enum AppLanguage: String, CaseIterable, Identifiable, Codable, Sendable {
    case system
    case english
    case simplifiedChinese

    var id: String { rawValue }

    @MainActor
    var displayName: String {
        switch self {
        case .system: L10n.tr("Follow System")
        case .english: "English"
        case .simplifiedChinese: "简体中文"
        }
    }

    func resolve(preferredLanguages: [String] = Locale.preferredLanguages) -> ResolvedLanguage {
        Self.resolve(self, preferredLanguages: preferredLanguages)
    }

    static func resolve(_ selection: AppLanguage, preferredLanguages: [String] = Locale.preferredLanguages) -> ResolvedLanguage {
        switch selection {
        case .english:
            return .en
        case .simplifiedChinese:
            return .zhHans
        case .system:
            guard let first = preferredLanguages.first?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
                  first.hasPrefix("zh") else {
                return .en
            }
            return .zhHans
        }
    }
}

enum ResolvedLanguage: String, Equatable, Sendable {
    case en
    case zhHans
}

private enum SharedLanguageMirror {
    private static let lock = NSLock()
    private nonisolated(unsafe) static var current: ResolvedLanguage = AppLanguage.resolve(
        AppLanguage(rawValue: UserDefaults.standard.string(forKey: LanguageManager.storageKey) ?? "") ?? .system
    )

    static func get() -> ResolvedLanguage {
        lock.withLock { current }
    }

    static func set(_ value: ResolvedLanguage) {
        lock.withLock { current = value }
    }
}

@MainActor @Observable
final class LanguageManager {
    nonisolated static let shared = LanguageManager(defaults: .standard, syncsGlobalMirror: true)
    nonisolated static let storageKey = "appLanguage"

    var selection: AppLanguage {
        get {
            access(keyPath: \.selection)
            return storedSelection
        }
        set {
            guard storedSelection != newValue else { return }
            withMutation(keyPath: \.selection) {
                storedSelection = newValue
            }
            defaults.set(newValue.rawValue, forKey: Self.storageKey)
            syncMirrorIfNeeded()
        }
    }

    private var systemLocaleRevision = 0

    @ObservationIgnored private nonisolated(unsafe) var storedSelection: AppLanguage
    @ObservationIgnored private nonisolated(unsafe) let defaults: UserDefaults
    @ObservationIgnored private nonisolated(unsafe) let preferredLanguagesOverride: [String]?
    @ObservationIgnored private nonisolated(unsafe) let syncsGlobalMirror: Bool
    @ObservationIgnored private nonisolated(unsafe) var localeObserver: NSObjectProtocol?

    nonisolated convenience init(defaults: UserDefaults = .standard, preferredLanguages: [String]? = nil) {
        self.init(defaults: defaults, preferredLanguages: preferredLanguages, syncsGlobalMirror: false)
    }

    private nonisolated init(defaults: UserDefaults = .standard, preferredLanguages: [String]? = nil, syncsGlobalMirror: Bool) {
        self.defaults = defaults
        self.preferredLanguagesOverride = preferredLanguages
        self.syncsGlobalMirror = syncsGlobalMirror

        let storedRaw = defaults.string(forKey: Self.storageKey) ?? ""
        let initial = AppLanguage(rawValue: storedRaw) ?? .system
        self.storedSelection = initial

        if syncsGlobalMirror {
            SharedLanguageMirror.set(initial.resolve(preferredLanguages: preferredLanguages ?? Locale.preferredLanguages))
        }

        if preferredLanguages == nil {
            localeObserver = NotificationCenter.default.addObserver(
                forName: NSLocale.currentLocaleDidChangeNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.systemLocaleRevision &+= 1
                    self.syncMirrorIfNeeded()
                }
            }
        }
    }

    deinit {
        if let localeObserver {
            NotificationCenter.default.removeObserver(localeObserver)
        }
    }

    var resolved: ResolvedLanguage {
        _ = systemLocaleRevision
        return selection.resolve(preferredLanguages: preferredLanguagesOverride ?? Locale.preferredLanguages)
    }

    var effectiveLocale: Locale {
        Locale(identifier: resolved == .zhHans ? "zh-Hans" : "en")
    }

    func setLanguage(_ language: AppLanguage) {
        if selection != language {
            selection = language
        } else {
            defaults.set(language.rawValue, forKey: Self.storageKey)
            syncMirrorIfNeeded()
        }
    }

    private nonisolated func syncMirrorIfNeeded() {
        guard syncsGlobalMirror else { return }
        let storedRaw = defaults.string(forKey: Self.storageKey) ?? ""
        let currentSelection = AppLanguage(rawValue: storedRaw) ?? .system
        let resolvedValue = currentSelection.resolve(preferredLanguages: preferredLanguagesOverride ?? Locale.preferredLanguages)
        SharedLanguageMirror.set(resolvedValue)
    }
}

extension EnvironmentValues {
    @Entry var languageManager = LanguageManager.shared
}

enum L10n {
    @MainActor
    static var resolved: ResolvedLanguage {
        LanguageManager.shared.resolved
    }

    @MainActor
    static var locale: Locale {
        LanguageManager.shared.effectiveLocale
    }

    @MainActor
    static func tr(_ key: String) -> String {
        translate(key, for: LanguageManager.shared.resolved)
    }

    @MainActor
    static func tr(_ key: String, _ args: CVarArg...) -> String {
        let format = translate(key, for: LanguageManager.shared.resolved)
        return String(format: format, locale: LanguageManager.shared.effectiveLocale, arguments: args)
    }

    static func trNonisolated(_ key: String) -> String {
        translate(key, for: SharedLanguageMirror.get())
    }

    static func trNonisolated(_ key: String, _ args: CVarArg...) -> String {
        let lang = SharedLanguageMirror.get()
        let format = translate(key, for: lang)
        let loc = Locale(identifier: lang == .zhHans ? "zh-Hans" : "en")
        return String(format: format, locale: loc, arguments: args)
    }

    private static func translate(_ key: String, for language: ResolvedLanguage) -> String {
        switch language {
        case .en:
            return key
        case .zhHans:
            return zhHansTable[key] ?? key
        }
    }

    // MARK: - Formatting & Domain Helpers

    @MainActor
    static func songCount(_ count: Int) -> String {
        switch resolved {
        case .en:
            return count == 1 ? "1 song" : "\(count) songs"
        case .zhHans:
            return "\(count) 首歌曲"
        }
    }

    @MainActor
    static func playCount(_ count: Int) -> String {
        switch resolved {
        case .en:
            return count == 1 ? "1 play" : "\(count) plays"
        case .zhHans:
            return "\(count) 次播放"
        }
    }

    @MainActor
    static func playUnit(_ count: Int) -> String {
        switch resolved {
        case .en:
            return count == 1 ? "play" : "plays"
        case .zhHans:
            return "次播放"
        }
    }

    @MainActor
    static func listeningDuration(_ seconds: TimeInterval) -> String {
        let minutes = Int(seconds / 60)
        switch resolved {
        case .en:
            return minutes >= 60 ? "\(minutes / 60) h \(minutes % 60) min" : "\(minutes) min"
        case .zhHans:
            return minutes >= 60 ? "\(minutes / 60) 小时 \(minutes % 60) 分钟" : "\(minutes) 分钟"
        }
    }

    @MainActor
    static func formatDateTime(_ date: Date) -> String {
        date.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened).locale(locale))
    }

    @MainActor
    static func formatRelativeDate(_ date: Date) -> String {
        date.formatted(Date.RelativeFormatStyle(presentation: .named).locale(locale))
    }

    @MainActor
    static func displayArtist(_ artist: String) -> String {
        artist == "Unknown Artist" ? tr("Unknown Artist") : artist
    }

    @MainActor
    static func displayAlbum(_ album: String) -> String {
        album == "Unknown Album" ? tr("Unknown Album") : album
    }

    @MainActor
    static func displayRootName(_ root: LibraryRoot) -> String {
        guard root.kind == .appFolder else { return root.name }
        #if targetEnvironment(macCatalyst)
        return tr("Music Folder")
        #else
        return tr("Musico Folder")
        #endif
    }

    @MainActor
    static func displayQueueName(_ name: String) -> String {
        guard resolved == .zhHans else { return name }
        if name.hasPrefix("Queue "), let number = Int(name.dropFirst(6)), number > 0 {
            return "队列 \(number)"
        }
        if name.hasPrefix("Artist: ") {
            return "艺术家：\(name.dropFirst(8))"
        }
        if name.hasPrefix("Album: ") {
            return "专辑：\(name.dropFirst(7))"
        }
        if name.hasPrefix("Genre: ") {
            return "流派：\(name.dropFirst(7))"
        }
        if name.hasPrefix("Keyword: ") {
            return "关键词：\(name.dropFirst(9))"
        }
        return name
    }

    @MainActor
    static func eqPresetName(_ name: String?) -> String {
        guard let name else { return tr("Custom") }
        return tr(name)
    }

    @MainActor
    static func precisionDisplay(_ techInfo: AudioTechnicalInfo?) -> String {
        guard let techInfo else { return tr("Loading…") }
        guard let bitDepth = techInfo.bitDepth, bitDepth > 0 else { return tr("N/A") }
        return resolved == .zhHans ? "\(bitDepth) 位" : "\(bitDepth)-bit"
    }

    @MainActor
    static func frequencyDisplay(_ techInfo: AudioTechnicalInfo?) -> String {
        guard let techInfo else { return tr("Loading…") }
        return techInfo.frequencyDisplay == "N/A" ? tr("N/A") : techInfo.frequencyDisplay
    }

    @MainActor
    static func bitRateDisplay(_ techInfo: AudioTechnicalInfo?) -> String {
        guard let techInfo else { return tr("Loading…") }
        return techInfo.bitRateDisplay == "N/A" ? tr("N/A") : techInfo.bitRateDisplay
    }

    @MainActor
    static func channelsDisplay(_ techInfo: AudioTechnicalInfo?) -> String {
        guard let techInfo else { return tr("Loading…") }
        guard let channels = techInfo.channels, channels > 0 else { return tr("N/A") }
        if resolved == .zhHans {
            switch channels {
            case 1: return "1 声道（单声道）"
            case 2: return "2 声道（立体声）"
            default: return "\(channels) 声道"
            }
        }
        return techInfo.channelsDisplay
    }

    @MainActor
    static func fileSizeDisplay(_ techInfo: AudioTechnicalInfo?, fallbackBytes: Int64) -> String {
        let bytes = techInfo?.fileSize ?? fallbackBytes
        guard bytes > 0 else { return tr("N/A") }
        return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    // MARK: - Simplified Chinese Dictionary

    static let zhHansTable: [String: String] = [
        // Language Switcher
        "Follow System": "跟随系统",
        "Language": "语言",

        // Common Actions & Alerts
        "OK": "好",
        "Cancel": "取消",
        "Done": "完成",
        "Close": "关闭",
        "Save": "保存",
        "Add": "添加",
        "Create": "创建",
        "Rename": "重命名",
        "Rename…": "重命名…",
        "Delete": "删除",
        "Remove": "移除",
        "Retry": "重试",
        "Reset": "重置",
        "Start": "开始",
        "More": "更多",
        "Show": "显示",
        "Name": "名称",
        "N/A": "暂无",
        "Loading…": "正在读取…",

        // Navigation, Tabs & Sidebar
        "Folders": "文件夹",
        "Folder": "文件夹",
        "Library": "资料库",
        "Queues": "队列",
        "Stats": "统计",
        "Songs": "歌曲",
        "Albums": "专辑",
        "Artists": "艺术家",
        "Playlists": "歌单",
        "Playlist": "歌单",
        "All Playlists": "全部歌单",
        "Favourites": "我的最爱",
        "New Playlist": "新建歌单",
        "Check for Updates": "检查更新",
        "Check for Updates…": "检查更新…",
        "Update available": "有可用更新",

        // Built-in & Metadata Fallbacks
        "Music Folder": "音乐文件夹",
        "Musico Folder": "Musico 文件夹",
        "Unknown Artist": "未知艺术家",
        "Unknown Album": "未知专辑",

        // Playback Error Alert
        "Playback Problem": "播放出错",
        "Playback stopped: %@ songs in a row could not be played.": "播放已停止：连续 %@ 首歌曲无法播放。",
        "“%@” could not be played.": "无法播放“%@”。",
        "This song": "此歌曲",

        // FoldersView & Cloud Sheets
        "Add Music Folder Back": "重新添加音乐文件夹",
        "Cloud Sync Issues": "云端同步异常",
        "Reading %@ of %@ songs…": "正在读取第 %@ / %@ 首歌曲…",
        "Looking for music…": "正在查找音乐…",
        "Rescan": "重新扫描",
        "Add Cloud Folder": "添加云端歌单",
        "Add Folder": "添加文件夹",
        "Your Mac’s Music folder is included to start with. Add local folders or YouTube/Bilibili cloud playlists, or remove any you don’t want.": "默认已包含 Mac 的“音乐”文件夹。你可以添加本地文件夹或 YouTube / Bilibili 云端歌单，也可以移除不需要的来源。",
        "Add a folder from Files, link a YouTube or Bilibili playlist, or copy music into the Musico folder in Files › On My iPad.": "从“文件”中添加文件夹、关联 YouTube 或 Bilibili 歌单，或将音乐拷贝到“文件”›“我的 iPad”›“Musico”文件夹中。",
        "Add a folder from Files, link a YouTube or Bilibili playlist, or copy music into the Musico folder in Files › On My iPhone.": "从“文件”中添加文件夹、关联 YouTube 或 Bilibili 歌单，或将音乐拷贝到“文件”›“我的 iPhone”›“Musico”文件夹中。",
        "YouTube · Local": "YouTube · 本地",
        "Bilibili · Local": "Bilibili · 本地",
        "Cloud · Local": "云端 · 本地",
        "%@ · Sync issue: %@": "%@ · 同步失败：%@",
        "Unavailable — folder moved or disconnected": "不可用 — 文件夹已移动或断开连接",
        "Remove Folder": "移除文件夹",
        "Play Folder": "播放文件夹",
        "Save to Local Folder…": "保存到本地文件夹…",
        "Update from Cloud": "从云端更新",
        "Locate Again…": "重新定位…",
        "Restore Removed Subfolders (%@)": "恢复已移除的子文件夹 (%@)",
        "Remove “%@” from your library?": "要从资料库中移除“%@”吗？",
        "Remove from Library": "从资料库移除",
        "Its songs leave your library, playlists and queues.": "其中的歌曲将从资料库、歌单和队列中移除。",
        "Its songs leave your library, playlists and queues. The files themselves are not deleted.": "其中的歌曲将从资料库、歌单和队列中移除，但不会删除磁盘上的原始文件。",
        "Couldn’t Add Folder": "无法添加文件夹",
        "YouTube or Bilibili URL or ID": "YouTube 或 Bilibili 链接或 ID",
        "Playlist or Video Link": "歌单或视频链接",
        "Examples: youtube.com/playlist?list=…, music.youtube.com/playlist?list=…, youtu.be/…, bilibili.com/video/BV…, space.bilibili.com/…/favlist?fid=…, b23.tv/…": "示例：youtube.com/playlist?list=…、music.youtube.com/playlist?list=…、youtu.be/…、bilibili.com/video/BV…、space.bilibili.com/…/favlist?fid=…、b23.tv/…",
        "Cookie (optional)": "Cookie（可选）",
        "Authentication": "身份验证",
        "Public playlists work without signing in. Add a Cookie only for private playlists or higher Bilibili audio quality.": "公开歌单无需登录即可添加。仅在访问私密歌单或获取更高 Bilibili 音质时填写 Cookie。",
        "No Music Here": "暂无音乐",
        "Pull to refresh to sync tracks from this cloud source.": "下拉刷新以同步此云端来源的歌曲。",
        "Songs you add to this folder appear after a rescan.": "添加到此文件夹的歌曲将在重新扫描后显示。",
        "Destination": "保存位置",
        "Folder Name": "文件夹名称",
        "Location": "目标位置",
        "Choose Folder…": "选择文件夹…",
        "Add saved folder to Library": "将保存的文件夹添加到资料库",
        "Progress": "保存进度",
        "Saving %@ of %@ songs…": "正在保存第 %@ / %@ 首歌曲…",
        "Summary": "完成摘要",
        "Saved %@ to “%@”": "已保存 %@到“%@”",
        "%@ failed to download.": "有 %@下载失败。",
        "Save Cloud Playlist": "保存云端歌单",
        "Saved songs, but couldn’t add folder to library: %@": "歌曲已保存，但无法将文件夹添加到资料库：%@",
        "Export cancelled.": "已取消导出。",
        "Cloud Source": "云端来源",
        "Syncing": "正在同步",
        "Checking %@ of %@ songs…": "正在检查第 %@ / %@ 首歌曲…",
        "Fetching latest cloud playlist…": "正在获取最新云端歌单…",
        "Result": "同步结果",
        "Added %@ new (%@ already saved).": "新增 %@（已有 %@）。",
        "Already up to date (%@).": "已是最新（共 %@）。",
        "%@ failed.": "有 %@失败。",
        "Update “%@”": "更新“%@”",
        "Update cancelled.": "已取消更新。",

        // Songs, Albums, Artists, Playlists, TrackRow
        "Title": "标题",
        "Artist": "艺术家",
        "Album": "专辑",
        "Recently Added": "最近添加",
        "Genre": "流派",
        "Keyword": "关键词",
        "Clear Filter": "清除筛选",
        "No Songs Yet": "暂无歌曲",
        "Add a folder in the Folders tab to build your library.": "前往“文件夹”标签页添加文件夹以建立你的音乐库。",
        "Songs, artists, albums, genres": "搜索歌曲、艺术家、专辑、流派",
        "Sort": "排序",
        "Sort By": "排序方式",
        "No Albums Yet": "暂无专辑",
        "Albums appear once your library has songs.": "当资料库中有歌曲时，专辑会显示在这里。",
        "No Artists Yet": "暂无艺术家",
        "Artists appear once your library has songs.": "当资料库中有歌曲时，艺术家会显示在这里。",
        "Rename Playlist": "重命名歌单",
        "Nothing Here Yet": "暂无内容",
        "Touch and hold a song, album or folder to add it.": "长按或右键点击歌曲、专辑或文件夹即可添加到此处。",
        "Enter a name for the playlist.": "请输入歌单名称。",
        "Reorder %@": "调整 %@ 的顺序",
        "Custom Order": "自定义顺序",
        "Name (A–Z)": "名称 (A–Z)",
        "Name (Z–A)": "名称 (Z–A)",
        "Date Modified": "修改日期",
        "Most Played": "播放最多",
        "Least Played": "播放最少",
        "Play Next": "插播下一首",
        "Add to Queue": "添加到当前队列",
        "Add to Another Queue": "添加到其他队列",
        "Play in New Queue": "在新队列中播放",
        "Song Info": "歌曲信息",
        "Add to Playlist": "添加到歌单",
        "New Playlist…": "新建歌单…",
        "Remove from Favourites": "取消喜欢",
        "Add to Favourites": "添加到我喜欢",
        "Play": "播放",
        "Shuffle": "随机播放",

        // Song Info Sheet
        "Song Unavailable": "歌曲不可用",
        "This song is no longer in your library.": "此歌曲已不在你的资料库中。",
        "Cover & Metadata": "封面与元数据",
        "Choose from Photos…": "从相册选择…",
        "Choose Image File…": "选择图片文件…",
        "Metadata saved": "元数据已保存",
        "Quick Search": "快速搜索",
        "File & Playback Info": "文件与播放信息",
        "Precision": "位深",
        "Frequency": "采样率",
        "Bit Rate": "比特率",
        "File Size": "文件大小",
        "Length": "时长",
        "Sound Tracks": "声道",
        "Time Added": "添加时间",
        "Time Edited": "修改时间",
        "Last Played": "上次播放",
        "Never": "从未播放",
        "Played Times": "播放次数",

        // StatsView
        "Recent": "最近播放",
        "Plays": "播放次数",
        "Not Lately": "久未播放",
        "Nothing Played Yet": "暂无播放记录",
        "No Plays Yet": "暂无播放统计",
        "Nothing Neglected": "没有久未播放的歌曲",
        "Everything Played": "所有歌曲均已播放过",
        "Songs appear here a few seconds after they start playing.": "歌曲开始播放几秒后会显示在这里。",
        "A song counts as played once you have listened to half of it.": "收听超过歌曲时长的一半后将计为一次播放。",
        "Songs you have not listened to for %@ days appear here.": "超过 %@ 天未听的歌曲会显示在这里。",
        "Every song in your library has been played at least once.": "资料库中的每首歌曲都至少播放过一次。",
        "Most Played First": "按播放最多排序",
        "Least Played First": "按播放最少排序",
        "Order": "排序方式",
        "listened": "累计收听",
        "never played": "从未播放",

        // Player, Queue, Lyrics, EQ, Sleep Timer
        "Lyrics": "歌词",
        "Queue": "队列",
        "Equalizer": "均衡器",
        "Sleep Timer": "睡眠定时",
        "Show lyrics": "显示歌词",
        "Favourite": "喜欢",
        "Song options": "歌曲选项",
        "Nothing Playing": "暂无播放内容",
        "Pick a song from your library.": "从资料库中选择一首歌曲。",
        "%@ of %@": "第 %@ / %@ 首",
        "Previous": "上一首",
        "Play or Pause": "播放或暂停",
        "Next": "下一首",
        "Repeat": "循环播放",
        "Open Now Playing": "打开正在播放",
        "Not Playing": "未在播放",
        "Pick a song from your library": "从资料库中选择歌曲",
        "New Queue": "新建队列",
        "This queue is empty. Tap a song in your library to fill it.": "此队列为空，在资料库中轻点歌曲即可加入播放。",
        "Missing song": "找不到歌曲",
        "Remove from Queue": "从队列移除",
        "Clear This Queue": "清空此队列",
        "Remove every song from “%@”?": "确定要移除“%@”中的所有歌曲吗？",
        "Clear Queue": "清空队列",
        "Rename Queue": "重命名队列",
        "Empty": "空",
        "%@ · %@ at %@": "%@ · %@ (%@)",
        "No Lyrics": "暂无歌词",
        "Import a .lrc or .txt file for this song, or put one with the same name next to it.": "为此歌曲导入 .lrc 或 .txt 文件，或将同名歌词文件放在音频文件旁。",
        "Import from File…": "从文件导入…",
        "Lyrics Options": "歌词选项",
        "Remove Imported Lyrics": "移除已导入的歌词",
        "Couldn’t Import Lyrics": "无法导入歌词",
        "From the file you imported": "来自已导入的文件",
        "From a file next to the song": "来自同目录下的歌词文件",
        "From the song’s tags": "来自音频内嵌标签",
        "Preset": "预设",
        "Custom": "自定义",
        "Flat": "平坦",
        "Bass Boost": "低音增强",
        "Treble Boost": "高音增强",
        "Rock": "摇滚",
        "Pop": "流行",
        "Jazz": "爵士",
        "Classical": "古典",
        "Vocal": "人声",
        "%@ hertz": "%@ 赫兹",
        "Preamp %@ dB": "前置增益 %@ dB",
        "Cancel Timer": "取消定时",
        "Stop playing after": "在以下时间后停止播放",
        "%@ minutes": "%@ 分钟",
        "End of current song": "当前歌曲播放结束后",
        "Stops in %@": "将在 %@ 后停止",
        "Stops at the end of this song": "将在当前歌曲结束后停止",

        // CloudExporter Host Alerts
        "Couldn’t Save Song": "无法保存歌曲",
        "Saving Song": "正在保存歌曲",
        "Saving “%@”…": "正在保存“%@”…",
        "Saved Song": "歌曲已保存",
        "Saved “%@” to “%@”.": "已将“%@”保存到“%@”。",

        // Update Sheet & Service
        "Software Update": "软件更新",
        "Checking for updates…": "正在检查更新…",
        "Current version: %@": "当前版本：%@",
        "Musico is Up to Date": "Musico 已是最新版本",
        "Version %@ is the latest available release.": "当前版本 %@ 已是最新发布版本。",
        "Check Again": "重新检查",
        "Open Release Page": "打开发布页面",
        "Update Available": "发现新版本",
        "Download & Install Update": "下载并安装更新",
        "Skip Version": "跳过此版本",
        "Downloading Update…": "正在下载更新…",
        "Downloading %@…": "正在下载 %@…",
        "installer": "安装包",
        "Installing Update…": "正在安装更新…",
        "Replacing application bundle and relaunching Musico %@…": "正在替换应用并重新启动 Musico %@…",
        "Ready to Install": "准备安装",
        "Update verified and ready.": "更新校验通过，已准备就绪。",
        "Install & Relaunch": "安装并重启",
        "Update Error": "更新出错",
        "Release Notes": "更新说明",
        "No release notes provided.": "暂无更新说明。",
        "Received an invalid response from GitHub.": "从 GitHub 收到了无效的响应。",
        "Update server returned HTTP %@.": "更新服务器返回 HTTP %@。",
        "Could not parse release version tag “%@”.": "无法解析发布版本标签“%@”。",
        "No macOS disk image (.dmg) was attached to release %@.": "发布版本 %@ 未附带 macOS 磁盘映像 (.dmg)。",
        "SHA-256 verification failed (expected %@…, got %@…).": "SHA-256 校验失败（预期 %@…，实际 %@…）。",
        "This build is sandboxed and cannot replace itself automatically. Use \"Open Release Page\" or install the DMG build from GitHub Releases.": "当前构建处于沙盒环境中，无法自动替换应用。请使用“打开发布页面”或从 GitHub Releases 下载 DMG 安装。",
        "Invalid installer file or application bundle path.": "无效的安装包文件或应用路径。",
        "Move Musico to your Applications folder first, then try updating again.": "请先将 Musico 移至“应用程序”文件夹，然后再尝试更新。",
        "Failed to launch updater helper (errno %@).": "无法启动更新辅助程序 (errno %@)。",
        "Could not decode %@.": "无法解码 %@。",
        "Downloaded %@, but could not open it automatically. Use \"Open Release Page\" to install manually.": "已下载 %@，但无法自动打开。请使用“打开发布页面”手动安装。",

        // Errors (Library, CloudSources, Lyrics)
        "That location is already in your library.": "该位置已在你的资料库中。",
        "That location could not be opened as a folder.": "无法将该位置作为文件夹打开。",
        "Enter a valid YouTube or Bilibili playlist or video URL.": "请输入有效的 YouTube 或 Bilibili 歌单或视频链接。",
        "Enter a valid YouTube or Bilibili playlist, collection, or video link.": "请输入有效的 YouTube 或 Bilibili 歌单、合集或视频链接。",
        "That link isn’t a supported YouTube or Bilibili playlist or video.": "该链接不是受支持的 YouTube 或 Bilibili 歌单或视频。",
        "No playable tracks were found at that link.": "在该链接中未找到可播放的歌曲。",
        "Could not resolve a playable audio stream for this track.": "无法解析该歌曲的可播放音频流。",
        "That file could not be read as lyrics text.": "无法将该文件读取为歌词文本。",
        "Invalid server response.": "服务器返回了无效响应。",
        "Invalid download response.": "下载响应无效。",
        "YouTube returned HTTP %@.": "YouTube 返回了 HTTP %@。",
        "Unexpected YouTube response format.": "YouTube 返回的数据格式异常。",
        "Could not obtain Bilibili WBI signing keys.": "无法获取 Bilibili WBI 签名密钥。",
        "Bilibili returned HTTP %@.": "Bilibili 返回了 HTTP %@。",
        "Unexpected Bilibili response format.": "Bilibili 返回的数据格式异常。",
        "Stream returned HTTP %@.": "音频流返回了 HTTP %@。",
    ]
}
