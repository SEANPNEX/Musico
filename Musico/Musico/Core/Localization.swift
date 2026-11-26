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
    static func albumCount(_ count: Int) -> String {
        switch resolved {
        case .en:
            return count == 1 ? "1 album" : "\(count) albums"
        case .zhHans:
            return "\(count) 张专辑"
        }
    }

    @MainActor
    static func addSongsCount(_ count: Int) -> String {
        switch resolved {
        case .en:
            return count == 1 ? "Add 1 Song" : "Add \(count) Songs"
        case .zhHans:
            return "添加 \(count) 首歌曲"
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
        if name == "Songs" {
            return tr("Songs")
        }
        if name == "Stats" {
            return tr("Stats")
        }
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
        if name.hasPrefix("Search ("), let sepRange = name.range(of: "): ") {
            let extName = name[name.index(name.startIndex, offsetBy: 8)..<sepRange.lowerBound]
            let query = name[sepRange.upperBound...]
            return "搜索 (\(extName))：\(query)"
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
        "Find Songs": "搜索歌曲",
        "Rescan Library": "重新扫描资料库",
        "View": "视图",
        "Playback": "播放",

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
        "Your Mac’s Music folder is included to start with. Add local folders or YouTube, Bilibili, or Google Drive cloud sources, or remove any you don’t want.": "默认已包含 Mac 的“音乐”文件夹。你可以添加本地文件夹或 YouTube、Bilibili、Google Drive 云端来源，也可以移除不需要的来源。",
        "Add a folder from Files, link a YouTube, Bilibili, or Google Drive source, or copy music into the Musico folder in Files › On My iPad.": "从“文件”中添加文件夹、关联 YouTube、Bilibili 或 Google Drive 云端来源，或将音乐拷贝到“文件”›“我的 iPad”›“Musico”文件夹中。",
        "Add a folder from Files, link a YouTube, Bilibili, or Google Drive source, or copy music into the Musico folder in Files › On My iPhone.": "从“文件”中添加文件夹、关联 YouTube、Bilibili 或 Google Drive 云端来源，或将音乐拷贝到“文件”›“我的 iPhone”›“Musico”文件夹中。",
        "YouTube · Local": "YouTube · 本地",
        "Bilibili · Local": "Bilibili · 本地",
        "Google Drive": "Google Drive",
        "Google Drive · Local": "Google Drive · 本地",
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
        "YouTube, Bilibili, or Google Drive URL or ID": "YouTube、Bilibili 或 Google Drive 链接或 ID",
        "Playlist, Folder, or Video Link": "歌单、文件夹或视频链接",
        "Examples: youtube.com/playlist?list=…, bilibili.com/video/BV…, b23.tv/…, drive.google.com/drive/folders/…, drive.google.com/file/d/…": "示例：youtube.com/playlist?list=…、bilibili.com/video/BV…、b23.tv/…、drive.google.com/drive/folders/…、drive.google.com/file/d/…",
        "Cookie (optional)": "Cookie（可选）",
        "Authentication": "身份验证",
        "Public links work without signing in. Add a Cookie only for private playlists, restricted Google Drive links, or higher Bilibili audio quality.": "公开链接无需登录即可添加。仅在访问私密歌单、受限 Google Drive 链接或获取更高 Bilibili 音质时填写 Cookie。",
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
        "Edit": "编辑",
        "Edit Cloud Source": "编辑云端来源",
        "Edit Cloud Source…": "编辑云端来源…",
        "Cloud Tracking Address": "云端追踪地址",
        "Updating the address links this local folder to the new cloud playlist or folder on future updates.": "更改地址后，下次从云端更新时将从此新歌单或文件夹同步歌曲。",
        "Loading %@ / %@ folders · %@ songs…": "正在加载第 %@ / %@ 个文件夹 · %@ 首歌曲…",
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
        "Delete Queue": "删除队列",
        "Switch to Queue": "切换到此队列",
        "Remove from Playlist": "从歌单移除",
        "Rename Playlist…": "重命名歌单…",
        "Delete Playlist": "删除歌单",
        "Delete “%@”?": "确定要删除“%@”吗？",
        "This playlist will be removed. Your songs stay in your library.": "此歌单将被删除，歌曲仍会保留在你的资料库中。",
        "Add Songs": "添加歌曲",
        "Add Songs…": "添加歌曲…",
        "Search songs to add": "搜索要添加的歌曲",
        "Added": "已添加",
        "Add songs below, or right-click any song, album, or folder to add it to this playlist.": "点击下方按钮添加歌曲，或右键点击任意歌曲、专辑或文件夹将其添加到此歌单。",
        "Add songs below, or touch and hold any song, album, or folder to add it.": "点击下方按钮添加歌曲，或长按任意歌曲、专辑或文件夹将其添加到此歌单。",
        "No matching songs": "没有匹配的歌曲",
        "Playlist name": "歌单名称",
        "Queue name": "队列名称",
        "Give your playlist a name.": "为你的歌单起一个名字。",
        "Choose a new name for this playlist.": "为此歌单输入新名称。",
        "Choose a new name for this queue.": "为此队列输入新名称。",
        "Includes %@": "将包含 %@",
        "At least one queue is required": "至少需要保留一个队列",
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

        // CloudExporter Host Alerts & Downloads
        "Couldn’t Save Song": "无法保存歌曲",
        "Saving Song": "正在保存歌曲",
        "Saving “%@”…": "正在保存“%@”…",
        "Saved Song": "歌曲已保存",
        "Saved “%@” to “%@”.": "已将“%@”保存到“%@”。",
        "Downloads": "下载",
        "No Downloads": "暂无下载",
        "Background cloud downloads and folder updates appear here.": "后台云端下载与文件夹更新将显示在这里。",
        "Active": "进行中",
        "Completed": "已完成",
        "Clear Finished": "清除已完成",
        "Dismiss": "关闭",
        "Preparing…": "准备中…",
        "Save Song": "保存歌曲",
        "%@ Downloads Active": "%@ 个下载任务进行中",
        "Run in Background": "后台运行",

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
        "That folder is already inside an existing library folder.": "该文件夹已位于现有资料库文件夹内。",
        "That location could not be opened as a folder.": "无法将该位置作为文件夹打开。",
        "Enter a valid YouTube, Bilibili, or Google Drive link.": "请输入有效的 YouTube、Bilibili 或 Google Drive 链接。",
        "That link isn’t a supported YouTube, Bilibili, or Google Drive link.": "该链接不是受支持的 YouTube、Bilibili 或 Google Drive 链接。",
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
        "Google Drive returned HTTP %@.": "Google Drive 返回了 HTTP %@。",
        "Cannot access this Google Drive link. Make sure the link is shared with 'Anyone with the link'.": "无法访问此 Google Drive 链接。请确保该链接已设置为“知道链接的所有人”可访问。",
        "No supported audio files were found in this Google Drive folder.": "此 Google Drive 文件夹中未找到受支持的音频文件。",
        "This Google Drive file is not a supported audio format.": "此 Google Drive 文件不是受支持的音频格式。",
        "Stream returned HTTP %@.": "音频流返回了 HTTP %@。",

        // Extensions & Cross-Platform JS Interface
        "Extensions": "扩展",
        "Extension": "扩展",
        "No Extensions Installed": "尚未安装扩展",
        "Import a .musicoext bundle or .musico.js script to add custom cloud music sources.": "导入 .musicoext 扩展包或 .musico.js 脚本以添加自定义云端音乐来源。",
        "Import a .musicoext bundle, .musico.js script, or multi-extension .zip pack to add custom cloud music sources.": "导入 .musicoext 扩展包、.musico.js 脚本或包含多个扩展的 .zip 压缩包以添加自定义云端音乐来源。",
        "Import File…": "导入文件…",
        "Import URL…": "从链接导入…",
        "Import from HTTPS URL…": "从 HTTPS 链接导入…",
        "Import Extension": "导入扩展",
        "Import from URL": "从链接导入",
        "Import": "导入",
        "Couldn’t Import Extension": "无法导入扩展",
        "Extension Package URL": "扩展包链接",
        "Enter an HTTPS link to a .musicoext bundle or .musico.js file.": "请输入指向 .musicoext 扩展包或 .musico.js 文件的 HTTPS 链接。",
        "Enter an HTTPS link to a .musicoext bundle, .musico.js script, or multi-extension .zip pack.": "请输入指向 .musicoext 扩展包、.musico.js 脚本或包含多个扩展的 .zip 压缩包的 HTTPS 链接。",
        "Review %@ Extensions": "确认 %@ 个扩展的权限",
        "Select the extensions you want to approve and enable. Unselected extensions remain disabled until reviewed.": "选择要授权并启用的扩展。未选中的扩展将保持停用，直至单独确认。",
        "Allow & Enable (%@)": "允许并启用 (%@)",
        "Extensions run natively in an isolated JavaScript sandbox with strict host allowlists.": "扩展在隔离的原生 JavaScript 沙盒中运行，并受严格的主机白名单限制。",
        "Permission Review Required": "需要权限确认",
        "Enabled": "已启用",
        "Disabled": "已停用",
        "By %@": "作者：%@",
        "Allowed Network Hosts": "允许访问的网络主机",
        "HTTP requests, redirects, and stream URLs outside these hosts are blocked automatically.": "超出以下主机范围的 HTTP 请求、重定向和音频流链接将被自动拦截。",
        "Declared Capabilities": "声明的能力",
        "Remove Extension": "移除扩展",
        "Permission Review": "权限确认",
        "Keep Disabled": "保持停用",
        "Allow & Enable": "允许并启用",
        "Extension Removed": "扩展已移除",
        "Settings": "设置",
        "Reset Settings": "重置设置",
        "Permissions": "权限",
        "Capabilities": "能力",
        "Review Permissions…": "查看权限…",
        "No diagnostic logs recorded yet.": "暂无诊断日志。",
        "Logs (%@)": "日志 (%@)",
        "Clear": "清除",
        "Uninstall Extension": "卸载扩展",
        "Uninstall “%@”?": "要卸载“%@”吗？",
        "Uninstall": "卸载",
        "Existing cloud folders from this extension stay in your library with their tracks and cached audio intact.": "来自此扩展的现有云端文件夹将保留在资料库中，其中的歌曲和已缓存音频不会丢失。",
        "Playlists & Collections": "歌单与合集",
        "Folder Tree & Subfolders": "目录树与子文件夹",
        "Audio Stream Resolution": "音频流解析",
        "Cookie Authentication": "Cookie 身份验证",
        "Proxy Support": "代理支持",
        "Fetch playlists, albums, or single tracks.": "获取歌单、专辑或单曲列表。",
        "Browse and crawl nested cloud subfolders.": "浏览并抓取嵌套的云端子文件夹。",
        "Resolve playable audio stream URLs and headers.": "解析可播放的音频流地址与请求头。",
        "Accept an optional per-folder authentication cookie.": "支持按文件夹传入可选的身份验证 Cookie。",
        "Use system proxy configuration for requests.": "在网络请求中使用系统代理配置。",
        "Unavailable Extensions": "不可用的扩展",
        "Some extension folders are currently unavailable.": "部分扩展云端文件夹当前不可用。",
        "Your tracks, playlists, stats, and cached audio remain intact. Re-enable or reinstall the extension to sync updates.": "你的歌曲、歌单、统计记录和已缓存音频均已完好保留。重新启用或安装对应扩展后即可同步更新。",
        "Manage Extensions": "管理扩展",
        "%@ · Local": "%@ · 本地",
        "Extension · Local": "扩展 · 本地",
        "%@ · Extension “%@” disabled": "%@ · 扩展“%@”已停用",
        "%@ · Missing extension (%@)": "%@ · 缺少扩展 (%@)",
        "Extension “%@” is disabled.": "扩展“%@”已停用。",
        "Extension “%@” is not installed.": "扩展“%@”未安装。",
        "Source": "来源",
        "Provider": "提供方",
        "Auto-Detect": "自动识别",
        "Detected": "已识别",
        "Cloud URL or ID": "云端链接或 ID",
        "Rate limited by the cloud source. Please try again later.": "云端来源请求过于频繁，请稍后再试。",
        "Extension was denied access to an unauthorized host.": "已拒绝扩展访问未授权的主机。",
        "Extension was denied access to host “%@”.": "已拒绝扩展访问未授权的主机“%@”。",
        "Extension “%@” is disabled or not installed.": "扩展“%@”已停用或未安装。",
        "Extension error (%@).": "扩展运行错误 (%@)。",
        "Invalid extension manifest: %@": "无效的扩展清单：%@",
        "Unsupported extension API version: %@.": "不支持的扩展 API 版本：%@。",
        "Invalid extension ID “%@”.": "无效的扩展 ID“%@”。",
        "Invalid extension version “%@”.": "无效的扩展版本号“%@”。",
        "Invalid host permission pattern “%@”.": "无效的主机权限规则“%@”。",
        "Invalid URL pattern “%@”.": "无效的链接匹配规则“%@”。",
        "Invalid extension entry path “%@”.": "无效的扩展入口路径“%@”。",
        "Extension manifest is missing required field “%@”.": "扩展清单缺少必填字段“%@”。",
        "Could not read extension package: %@": "无法读取扩展包：%@",
        "Enter a valid HTTPS extension URL.": "请输入有效的 HTTPS 扩展链接。",
        "Extension URLs must use HTTPS.": "扩展下载链接必须使用 HTTPS。",
        "Could not download extension: %@": "无法下载扩展：%@",
        "Extension package is missing entry script “%@”.": "扩展包缺少入口脚本“%@”。",
        "Invalid extension archive: %@": "无效的扩展压缩包：%@",
        "Extension archive contains unsafe path “%@”.": "扩展压缩包包含不安全的路径“%@”。",
        "Extension archive exceeds the %@ entry limit.": "扩展压缩包文件数量超过 %@ 个上限。",
        "Extension archive exceeds the %@ MB uncompressed size limit.": "扩展压缩包解压后大小超过 %@ MB 上限。",
        "Extension archive uses unsupported compression method (%@).": "扩展压缩包使用了不支持的压缩方式 (%@)。",
        "Failed to decompress “%@” from extension archive.": "无法从扩展压缩包解压“%@”。",

        // Cloud Lyrics & Local Folder Matching (v1.2.3)
        "Lyrics Search": "歌词搜索",
        "Search online lyrics for playing songs.": "搜索正在播放歌曲的在线歌词。",
        "Search Online…": "在线搜索…",
        "Search Cloud Lyrics": "在线搜索歌词",
        "Matched from song folder": "匹配自歌曲所在文件夹",
        "Save .lrc to Song Folder": "保存 .lrc 到歌曲文件夹",
        "Saved .lrc to song folder.": "已将 .lrc 保存到歌曲文件夹。",
        "Also save .lrc to song folder": "同时保存 .lrc 到歌曲文件夹",
        "The song’s folder cannot be modified.": "无法写入歌曲所在的文件夹。",
        "Lyrics Action Failed": "歌词操作失败",
        "Couldn’t Save Lyrics": "无法保存歌词",
        "Couldn’t Search Lyrics": "无法搜索歌词",
        "Search": "搜索",
        "Use Lyrics": "使用歌词",
        "Include Translation": "包含翻译",
        "Auto": "自动",
        "Synced": "逐行同步",
        "Plain": "纯文本",
        "Instrumental": "纯音乐",
        "Translated": "含翻译",
        "No Lyrics Found Online": "未找到在线歌词",
        "Try editing the title or artist above, or switch lyrics source.": "可尝试修改上方歌名或歌手，或切换歌词来源。",
        "Searching…": "正在搜索…",
        "Select a result to preview": "选择一条结果以预览",
        "Preview": "预览",
        "Try Again": "重试",
        "Enter a song title or artist to search.": "请输入歌名或歌手后再搜索。",
        "Lyrics service is busy. Try again in %@s.": "歌词服务繁忙，请在 %@ 秒后重试。",
        "Lyrics service rate limited. Please try again shortly.": "歌词服务请求过于频繁，请稍后再试。",
        "Lyrics provider “%@” is unavailable.": "歌词提供方“%@”当前不可用。",
        "Some sources could not be reached.": "部分来源暂时无法访问。",

        // Extension-Only Cloud Search & Auth (v1.2.4)
        "Cloud Search": "云端搜索",
        "Search songs, albums, and playlists online.": "在线搜索歌曲、专辑和歌单。",
        "Clear Cookie": "清除 Cookie",
        "Saved locally for search and playback with this extension. Folder-specific cookies still take precedence.": "仅保存在本地，用于此扩展的搜索与播放。已添加文件夹的独立 Cookie 仍会优先使用。",
        "Search Kinds": "搜索类型",
        "Local": "本地",
        "All": "全部",
        "Search %@": "搜索 %@",
        "Search %@…": "搜索 %@…",
        "Enter a query and press Search to search %@.": "输入关键词并点击“搜索”，即可在 %@ 中搜索。",
        "No Results Found": "未找到结果",
        "Try another keyword or switch search filter.": "请尝试其他关键词或切换筛选类型。",
        "Loading more…": "正在加载更多…",
        "Load More": "加载更多",
        "Authentication Required": "需要身份验证",
        "Search Failed": "搜索失败",
        "Configure a Cookie for “%@” in Extension Manager, then retry.": "请在扩展管理中为“%@”配置 Cookie 后重试。",
        "Open Extension Manager": "打开扩展管理",
        "Configure Cookie…": "配置 Cookie…",
        "Open Preview": "打开预览",
        "Add as Cloud Folder": "添加为云端文件夹",
        "Added to Folders": "已添加到文件夹",
        "Couldn’t Add Cloud Folder": "无法添加云端文件夹",
        "Collection Preview": "合集预览",
        "Loading collection…": "正在加载合集…",
        "Couldn’t Load Collection": "无法加载合集",
        "No playable tracks found in this collection.": "此合集中未找到可播放的歌曲。",
        "Adding…": "正在添加…",

        // Extension QR Code Login
        "QR Code Login": "扫码登录",
        "Sign in by scanning a QR code in-app.": "在应用内扫码登录账号。",
        "Sign In with QR Code…": "扫码登录…",
        "In-App Browser Login": "应用内网页登录",
        "Sign in via an in-app browser to capture session cookies.": "通过应用内浏览器登录并自动保存会话 Cookie。",
        "Sign In in Browser…": "网页登录…",
        "Sign In to %@": "登录 %@",
        "Save Cookies": "保存 Cookie",
        "Sign in below, then tap Save Cookies.": "请在下方完成登录，然后点击保存 Cookie。",
        "%@ · %d cookies captured": "%@ · 已捕获 %d 项 Cookie",
        "Sign-in cookies detected (%d). Tap Save Cookies to finish.": "已检测到登录 Cookie（%d 项），点击保存 Cookie 即可完成。",
        "Import cookies.txt…": "导入 cookies.txt…",
        "Couldn’t Import Cookies": "无法导入 Cookie",
        "Could not read cookie file as text.": "无法将该 Cookie 文件读取为文本。",
        "No valid cookies for this extension were found in the file.": "文件中未找到适用于此扩展的有效 Cookie。",
        "Enable this extension first to sign in with a QR code. Folder-specific cookies still take precedence.": "请先启用此扩展再使用扫码登录。已添加文件夹的独立 Cookie 仍会优先使用。",
        "Generating QR code…": "正在生成二维码…",
        "QR Code Login Failed": "扫码登录失败",
        "QR Code Expired": "二维码已过期",
        "Refresh to generate a new QR code.": "请刷新以重新生成二维码。",
        "Could not complete QR code login.": "未能完成扫码登录。",
        "Signed In": "登录成功",
        "Session cookie saved to %@.": "会话 Cookie 已保存到 %@。",
        "Scanned — Confirm on Your Phone": "已扫码 — 请在手机上确认",
        "Scan with %@ App": "请使用 %@ 客户端扫码",
        "Waiting for QR code scan…": "等待扫描二维码…",
        "Refresh QR Code": "刷新二维码",
        "Couldn’t generate QR code.": "无法生成二维码。",

        // Extension Auto-Updates & Queue Decoupling (v1.2.6)
        "Automatic Updates": "自动更新",
        "Updates": "更新",
        "Last checked %@": "上次检查：%@",
        "Extensions installed from HTTPS links or declaring an update URL check automatically every 6 hours.": "通过 HTTPS 链接安装或声明了更新地址的扩展每 6 小时自动检查一次更新。",
        "Review Update": "审核更新",
        "Review Update…": "审核更新…",
        "Update Source": "更新源",
        "Check for Update": "检查更新",
        "Extension is up to date.": "扩展已是最新版本。",
        "Updated to v%@": "已更新至 v%@",
        "Update v%@ requires permission review": "更新 v%@ 需要确认权限",
        "Version %@ is ready for permission review.": "版本 %@ 已就绪，等待确认权限。",
        "This update requests additional permissions. Review the new hosts or capabilities before applying it.": "此更新请求了额外权限。应用前请先确认新增的主机或能力。",
        "New Network Hosts": "新增网络主机",
        "New Capabilities": "新增能力",
        "All Allowed Hosts": "全部允许的网络主机",
        "All Capabilities": "全部能力",
        "Allow & Update": "允许并更新",
        "Invalid extension update URL “%@”.": "无效的扩展更新链接“%@”。",
        "This extension has no update URL configured.": "此扩展未配置更新地址。",
        "Extension update URLs must use HTTPS.": "扩展更新链接必须使用 HTTPS。",
        "Update rejected: expected extension ID “%@”, got “%@”.": "已拒绝更新：预期扩展 ID 为“%@”，实际为“%@”。",
        "Playing": "正在播放",
        "View Queue": "查看队列",
        "Allows network access to: %@": "允许网络访问：%@",
        "Enter an http:// or https:// server URL. Its host will be allowed for this extension.": "请输入 http:// 或 https:// 服务器地址，其主机将被允许供此扩展访问。",
        "From server setting": "来自服务器设置",

        // Cloud Audio Quality & Cloud Folder Source Recovery (v1.2.7)
        "Audio Quality": "音质",
        "Audio Quality (This Track)": "音质（仅当前歌曲）",
        "Streaming Quality": "在线播放音质",
        "Download Quality": "下载 / 缓存音质",
        "Default quality used for playback and saving songs when a folder does not specify its own.": "未在文件夹单独设置时，默认用于播放和保存歌曲的音质。",
        "Default": "默认",
        "Default (%@)": "默认（%@）",
        "Highest (FLAC / 192k)": "最高 (FLAC / 192k)",
        "High (192k)": "高 (192k)",
        "Standard (132k)": "标准 (132k)",
        "Low (64k)": "低 (64k)",
        "Highest (256k)": "最高 (256k)",
        "High (128k–160k)": "高 (128k–160k)",
        "Standard (128k)": "标准 (128k)",
        "Low (48k)": "低 (48k)",
        "Switch Source…": "切换来源…",
        "Source Provider": "来源提供方",
        "Auto (Match URL)": "自动（匹配链接）",
        "%@ (Disabled)": "%@（已停用）",
        "%@ (Missing)": "%@（未安装）",
        "Cloud Source Address": "云端来源地址",
        "Changing the URL or provider re-syncs this cloud folder while keeping its playlists and queues where possible.": "修改链接或提供方将重新同步此云端文件夹，并尽可能保留歌单和队列。",

        // Settings & Appearance Overhaul
        "Settings…": "设置…",
        "Light": "浅色",
        "Dark": "深色",
        "Obsidian": "曜石黑",
        "Midnight": "午夜蓝",
        "Warm Charcoal": "暖炭灰",
        "Forest Night": "森夜绿",
        "Paper": "米白纸",
        "Mist": "薄雾蓝",
        "Blossom": "樱花粉",
        "Coral": "珊瑚橙",
        "Ocean": "海洋蓝",
        "Emerald": "翡翠绿",
        "Amethyst": "紫水晶",
        "Amber": "琥珀黄",
        "Rose": "玫瑰红",
        "Monochrome": "黑白灰",
        "Appearance": "外观",
        "Theme Mode": "主题模式",
        "Background Color": "背景颜色",
        "Custom Background Color": "自定义背景颜色",
        "Foreground & Accent": "前景与强调色",
        "Custom Accent Color": "自定义强调色",
        "Custom Text Color": "自定义文字颜色",
        "Text Color": "文字颜色",
        "Background Image": "背景图片",
        "Custom image active": "已启用自定义图片",
        "None": "无",
        "Image Visibility": "图片可见度",
        "Window Background Opacity": "窗口背景不透明度",
        "Reset to Default": "恢复默认",
        "General": "通用",
        "About & Updates": "关于与更新",
        "Version": "版本",
        "Couldn’t Import Background Image": "无法导入背景图片",
        "The selected image file is empty.": "所选图片文件为空。",
        "The selected image could not be loaded.": "无法加载所选图片。",

        // Google Drive OAuth 2.0
        "Google Drive Account": "Google Drive 账号",
        "Account Status": "登录状态",
        "Connected": "已连接",
        "Not Signed In": "未登录",
        "OAuth Client ID": "OAuth 客户端 ID",
        "Optional Google Cloud Client ID": "填入 Google Cloud 客户端 ID",
        "OAuth Client Secret": "OAuth 客户端密钥",
        "Optional": "可选",
        "Sign In with Google": "使用 Google 账号登录",
        "Reconnect Google Drive": "重新连接 Google Drive",
        "Sign Out of Google Drive": "退出 Google Drive 登录",
        "Sign in with OAuth 2.0 to access your private Google Drive folders and bypass public download confirmation pages. Public links still work without signing in.": "通过 OAuth 2.0 登录后可访问私人 Google Drive 文件夹并跳过下载确认页。公开分享链接无需登录仍可直接使用。",
        "Enter a Google Cloud OAuth Client ID first.": "请先填写 Google Cloud OAuth 客户端 ID。",
        "Google sign-in was cancelled.": "已取消 Google 登录。",
        "OAuth state validation failed.": "OAuth state 校验失败。",

        // Wave A: Deletion, Paths, Toasts, Appearance Polish, Audio Output, Lyrics Upgrades
        "File Name": "文件名",
        "Remove “%@”?": "要移除“%@”吗？",
        "Delete…": "删除…",
        "Remove from Library…": "从资料库移除…",
        "Move to Trash": "移到废纸篓",
        "Delete from Device": "从设备删除",
        "Moved to Trash": "已移到废纸篓",
        "Deleted from device": "已从设备删除",
        "Removed from library": "已从资料库移除",
        "Couldn’t Delete": "无法删除",
        "Its song leaves your library, playlists and queues.": "该歌曲将从资料库、歌单和队列中移除。",
        "Remove from Library hides it from Musico without deleting the file. Deleting removes the file from disk.": "“从资料库移除”仅在 Musico 中隐藏，不会删除磁盘文件；“删除”会从磁盘中移除该文件。",
        "Remove from Library keeps the files on disk. Deleting moves or removes the folder and its files from disk.": "“从资料库移除”会保留磁盘文件；“删除”会从磁盘中移动或删除该文件夹及其文件。",
        "The built-in music folder cannot be deleted from disk.": "内置音乐文件夹无法从磁盘删除。",
        "The file or folder could not be found on disk.": "在磁盘上找不到该文件或文件夹。",
        "Cloud items can only be removed from the library.": "云端项目只能从资料库移除。",
        "Undo": "撤销",
        "Playing next": "已设为下一首播放",
        "Added to queue": "已添加到队列",
        "Added to playlist": "已添加到歌单",
        "Added to favourites": "已添加到我喜欢",
        "Removed from favourites": "已从我喜欢移除",
        "Cleared queue": "已清空队列",
        "Removed from playlist": "已从歌单移除",
        "Background Blur": "背景模糊",
        "Low Contrast Warning": "低对比度警告",
        "Current text or accent color may be hard to read against the background.": "当前文字或强调色在背景上可能较难辨认。",
        "Auto-fix Contrast": "自动修复对比度",
        "Now Playing Preview": "正在播放预览",
        "Sample Artist — Sample Album": "示例艺术家 — 示例专辑",
        "Compact": "紧凑",
        "Medium": "适中",
        "Large": "大号",
        "Leading": "左对齐",
        "Center": "居中",
        "Lyrics Size": "歌词字号",
        "Lyrics Alignment": "歌词对齐",
        "Sync to current line": "回到当前歌词",
        "Audio Output": "音频输出",
        "Mute": "静音",
        "Unmute": "取消静音",
        "Volume": "音量",

        // Wave B: Command Palette, Desktop Shortcuts, Drag & Drop, Mini Window, Visual Polish
        "Actions": "操作",
        "Now Playing": "正在播放",
        "Quick Jump": "快速跳转",
        "Quick Jump…": "快速跳转…",
        "Jump to songs, albums, artists, playlists, queues, or actions…": "跳转到歌曲、专辑、艺术家、歌单、队列或操作…",
        "Try another keyword to jump to songs, albums, artists, or actions.": "请尝试其他关键词以跳转到歌曲、专辑、艺术家或操作。",
        "Navigate": "选择",
        "Open": "打开",
        "Esc to close": "按 Esc 关闭",
        "Switched to %@": "已切换到 %@",
        "Rescanning library…": "正在重新扫描资料库…",
        "Toggle Lyrics Panel": "切换歌词面板",
        "Toggle Queue Panel": "切换队列面板",
        "Mini Player": "迷你播放器",
        "Open Main Window": "打开主窗口",
        "Seek Backward 10s": "快退 10 秒",
        "Seek Forward 10s": "快进 10 秒",
        "Volume Up": "增大音量",
        "Volume Down": "减小音量",
        "Volume: %@": "音量：%@",
        "Muted": "已静音",
        "Unmuted": "已取消静音",
        "Drop folders or audio files to add to Musico": "拖放文件夹或音频文件以添加到 Musico",
        "Added folder “%@”": "已添加文件夹“%@”",
        "Added and playing %@": "已添加并播放 %@",
        "Playing %@": "正在播放 %@",
        "Insights": "洞察",
        "Library Explored": "资料库探索度",
        "Top Artists": "常听艺术家",
        "Top Albums": "常听专辑",
    ]
}
