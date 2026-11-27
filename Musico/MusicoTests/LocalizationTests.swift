import Foundation
import XCTest
@testable import Musico

@MainActor
final class LocalizationTests: XCTestCase {
    private var previousSharedLanguage: AppLanguage = .system
    private var hadStoredSharedLanguage = false

    override func setUp() {
        super.setUp()
        hadStoredSharedLanguage = UserDefaults.standard.object(forKey: LanguageManager.storageKey) != nil
        previousSharedLanguage = LanguageManager.shared.selection
    }

    override func tearDown() {
        if hadStoredSharedLanguage {
            LanguageManager.shared.setLanguage(previousSharedLanguage)
        } else {
            LanguageManager.shared.setLanguage(.system)
            UserDefaults.standard.removeObject(forKey: LanguageManager.storageKey)
        }
        super.tearDown()
    }

    func testSystemLanguageResolution() {
        XCTAssertEqual(AppLanguage.system.resolve(preferredLanguages: ["zh-Hans-CN"]), .zhHans)
        XCTAssertEqual(AppLanguage.system.resolve(preferredLanguages: ["zh-Hans"]), .zhHans)
        XCTAssertEqual(AppLanguage.system.resolve(preferredLanguages: ["zh-CN"]), .zhHans)
        XCTAssertEqual(AppLanguage.system.resolve(preferredLanguages: ["zh-SG"]), .zhHans)
        XCTAssertEqual(AppLanguage.system.resolve(preferredLanguages: ["zh"]), .zhHans)
        XCTAssertEqual(AppLanguage.system.resolve(preferredLanguages: ["ZH-Hans-CN"]), .zhHans)

        XCTAssertEqual(AppLanguage.system.resolve(preferredLanguages: ["en-US"]), .en)
        XCTAssertEqual(AppLanguage.system.resolve(preferredLanguages: ["ja-JP"]), .en)
        XCTAssertEqual(AppLanguage.system.resolve(preferredLanguages: ["fr-FR", "zh-Hans"]), .en)
        XCTAssertEqual(AppLanguage.system.resolve(preferredLanguages: []), .en)

        XCTAssertEqual(AppLanguage.english.resolve(preferredLanguages: ["zh-Hans-CN"]), .en)
        XCTAssertEqual(AppLanguage.simplifiedChinese.resolve(preferredLanguages: ["en-US"]), .zhHans)
    }

    func testLanguageManagerPersistenceAndEffectiveLocale() {
        let suiteName = "LocalizationTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let initial = LanguageManager(defaults: defaults, preferredLanguages: ["en-US"])
        XCTAssertEqual(initial.selection, .system)
        XCTAssertEqual(initial.resolved, .en)
        XCTAssertEqual(initial.effectiveLocale.identifier, "en")

        initial.setLanguage(.simplifiedChinese)
        XCTAssertEqual(defaults.string(forKey: LanguageManager.storageKey), AppLanguage.simplifiedChinese.rawValue)
        XCTAssertEqual(initial.resolved, .zhHans)
        XCTAssertEqual(initial.effectiveLocale.identifier, "zh-Hans")

        let reloaded = LanguageManager(defaults: defaults, preferredLanguages: ["en-US"])
        XCTAssertEqual(reloaded.selection, .simplifiedChinese)
        XCTAssertEqual(reloaded.resolved, .zhHans)
        XCTAssertEqual(reloaded.effectiveLocale.identifier, "zh-Hans")

        reloaded.setLanguage(.english)
        XCTAssertEqual(defaults.string(forKey: LanguageManager.storageKey), AppLanguage.english.rawValue)
        XCTAssertEqual(reloaded.resolved, .en)
        XCTAssertEqual(reloaded.effectiveLocale.identifier, "en")

        let systemFollowsChinese = LanguageManager(defaults: UserDefaults(suiteName: "\(suiteName).sys")!, preferredLanguages: ["zh-Hans-CN"])
        defer { UserDefaults(suiteName: "\(suiteName).sys")?.removePersistentDomain(forName: "\(suiteName).sys") }
        XCTAssertEqual(systemFollowsChinese.selection, .system)
        XCTAssertEqual(systemFollowsChinese.resolved, .zhHans)
        XCTAssertEqual(systemFollowsChinese.effectiveLocale.identifier, "zh-Hans")
    }

    func testSimplifiedChineseTranslationsAndFormatters() {
        for (key, value) in L10n.zhHansTable {
            XCTAssertFalse(key.isEmpty)
            XCTAssertFalse(value.isEmpty, "Empty translation for key: \(key)")
        }

        LanguageManager.shared.setLanguage(.simplifiedChinese)
        XCTAssertEqual(L10n.resolved, .zhHans)
        XCTAssertEqual(AppLanguage.system.displayName, "跟随系统")
        XCTAssertEqual(AppLanguage.english.displayName, "English")
        XCTAssertEqual(AppLanguage.simplifiedChinese.displayName, "简体中文")

        XCTAssertEqual(L10n.tr("Folders"), "文件夹")
        XCTAssertEqual(L10n.tr("Library"), "资料库")
        XCTAssertEqual(L10n.tr("Queues"), "队列")
        XCTAssertEqual(L10n.tr("Stats"), "统计")
        XCTAssertEqual(L10n.tr("Song Info"), "歌曲信息")
        XCTAssertEqual(L10n.tr("Equalizer"), "均衡器")
        XCTAssertEqual(L10n.tr("Sleep Timer"), "睡眠定时")
        XCTAssertEqual(L10n.tr("Check for Updates"), "检查更新")
        XCTAssertEqual(L10n.tr("Language"), "语言")
        XCTAssertEqual(L10n.tr("Find Songs"), "搜索歌曲")
        XCTAssertEqual(L10n.tr("Rescan Library"), "重新扫描资料库")
        XCTAssertEqual(L10n.tr("View"), "视图")
        XCTAssertEqual(L10n.tr("Playback"), "播放")
        XCTAssertEqual(L10n.tr("Downloads"), "下载")
        XCTAssertEqual(L10n.tr("Run in Background"), "后台运行")
        XCTAssertEqual(L10n.tr("Clear Finished"), "清除已完成")
        XCTAssertEqual(L10n.tr("Delete Queue"), "删除队列")
        XCTAssertEqual(L10n.tr("Switch to Queue"), "切换到此队列")
        XCTAssertEqual(L10n.tr("Remove from Playlist"), "从歌单移除")
        XCTAssertEqual(L10n.tr("Add Songs"), "添加歌曲")
        XCTAssertEqual(L10n.tr("Delete Playlist"), "删除歌单")
        XCTAssertEqual(L10n.tr("Playlist name"), "歌单名称")
        XCTAssertEqual(L10n.tr("Queue name"), "队列名称")
        XCTAssertEqual(L10n.tr("Give your playlist a name."), "为你的歌单起一个名字。")
        XCTAssertEqual(L10n.tr("At least one queue is required"), "至少需要保留一个队列")
        XCTAssertEqual(L10n.addSongsCount(3), "添加 3 首歌曲")

        XCTAssertEqual(TrackSortOrder.custom.localizedTitle, "自定义顺序")
        XCTAssertEqual(SearchField.artist.localizedTitle, "艺术家")
        XCTAssertEqual(L10n.songCount(3), "3 首歌曲")
        XCTAssertEqual(L10n.playCount(5), "5 次播放")
        XCTAssertEqual(L10n.listeningDuration(3660), "1 小时 1 分钟")
        XCTAssertEqual(L10n.displayArtist("Unknown Artist"), "未知艺术家")
        XCTAssertEqual(L10n.displayArtist("Kano"), "Kano")
        XCTAssertEqual(L10n.displayAlbum("Unknown Album"), "未知专辑")
        XCTAssertEqual(L10n.displayQueueName("Queue 1"), "队列 1")
        XCTAssertEqual(L10n.displayQueueName("Artist: Kano"), "艺术家：Kano")
        XCTAssertEqual(L10n.eqPresetName("Flat"), "平坦")
        XCTAssertEqual(L10n.eqPresetName(nil), "自定义")

        XCTAssertEqual(LibraryError.duplicateRoot.errorDescription, "该位置已在你的资料库中。")
        XCTAssertEqual(LyricsError.unreadable.errorDescription, "无法将该文件读取为歌词文本。")
        XCTAssertEqual(CloudSourceError.invalidURL.errorDescription, "请输入有效的 Google Drive 链接，或已安装扩展支持的链接。")
        XCTAssertEqual(CloudSourceError.unsupportedSource.errorDescription, "Google Drive 或当前已启用的扩展均不支持该链接。")
        XCTAssertEqual(LibraryError.invalidCloudURL.errorDescription, "请输入有效的 Google Drive 链接，或已安装扩展支持的链接。")
        XCTAssertEqual(CloudSourceError.rateLimited.errorDescription, "云端来源请求过于频繁，请稍后再试。")
        XCTAssertEqual(CloudSourceError.permissionDenied(host: "evil.test").errorDescription, "已拒绝扩展访问未授权的主机“evil.test”。")
        XCTAssertEqual(CloudSourceError.extensionUnavailable(name: "Demo").errorDescription, "扩展“Demo”已停用或未安装。")
        XCTAssertEqual(L10n.tr("Extensions"), "扩展")
        XCTAssertEqual(L10n.tr("Permission Review"), "权限确认")
        XCTAssertEqual(L10n.tr("Auto-Detect"), "自动识别")
        XCTAssertEqual(L10n.tr("Google Drive · Local"), "Google Drive · 本地")
        XCTAssertEqual(L10n.tr("Edit Cloud Source…"), "编辑云端来源…")
        XCTAssertEqual(L10n.tr("Cloud Tracking Address"), "云端追踪地址")
        XCTAssertEqual(L10n.tr("Loading %@ / %@ folders · %@ songs…", "2", "5", "42"), "正在加载第 2 / 5 个文件夹 · 42 首歌曲…")
        XCTAssertEqual(L10n.tr("That folder is already inside an existing library folder."), "该文件夹已位于现有资料库文件夹内。")
        XCTAssertTrue(UpdateError.checksumMismatch(expected: "abcdef123456", actual: "123456abcdef").errorDescription?.contains("SHA-256") == true)
        XCTAssertEqual(ExtensionCapability.search.localizedTitle, "云端搜索")
        XCTAssertEqual(ExtensionCapability.search.localizedDescription, "在线搜索歌曲、专辑和歌单。")
        XCTAssertEqual(L10n.tr("Local"), "本地")
        XCTAssertEqual(L10n.tr("Load More"), "加载更多")
        XCTAssertEqual(L10n.tr("Add as Cloud Folder"), "添加为云端文件夹")
        XCTAssertEqual(L10n.tr("Authentication Required"), "需要身份验证")
        XCTAssertEqual(L10n.displayQueueName("Search (Demo): hello"), "搜索 (Demo)：hello")
        XCTAssertEqual(L10n.tr("Appearance"), "外观")
        XCTAssertEqual(L10n.tr("Theme Mode"), "主题模式")
        XCTAssertEqual(L10n.tr("Background Color"), "背景颜色")
        XCTAssertEqual(L10n.tr("Foreground & Accent"), "前景与强调色")
        XCTAssertEqual(L10n.tr("Background Image"), "背景图片")
        XCTAssertEqual(L10n.tr("Image Visibility"), "图片可见度")
        XCTAssertEqual(L10n.tr("Window Background Opacity"), "窗口背景不透明度")
        XCTAssertEqual(L10n.tr("Reset to Default"), "恢复默认")
        XCTAssertEqual(L10n.tr("General"), "通用")
        XCTAssertEqual(L10n.tr("About & Updates"), "关于与更新")
        XCTAssertEqual(AppThemeMode.light.displayName, "浅色")
        XCTAssertEqual(AppThemeMode.dark.displayName, "深色")
        XCTAssertEqual(BackgroundColorPreset.obsidian.displayName, "曜石黑")
        XCTAssertEqual(ForegroundColorPreset.coral.displayName, "珊瑚橙")
        XCTAssertEqual(L10n.tr("Auto-fix Contrast"), "自动修复对比度")
        XCTAssertEqual(L10n.tr("Background Blur"), "背景模糊")
        XCTAssertEqual(L10n.tr("Low Contrast Warning"), "低对比度警告")
        XCTAssertEqual(L10n.tr("Move to Trash"), "移到废纸篓")
        XCTAssertEqual(L10n.tr("Delete from Device"), "从设备删除")
        XCTAssertEqual(L10n.tr("File Name"), "文件名")
        XCTAssertEqual(L10n.tr("Location"), "目标位置")
        XCTAssertEqual(L10n.tr("Sync to current line"), "回到当前歌词")
        XCTAssertEqual(L10n.tr("Lyrics Size"), "歌词字号")
        XCTAssertEqual(L10n.tr("Lyrics Alignment"), "歌词对齐")
        XCTAssertEqual(L10n.tr("Undo"), "撤销")
        XCTAssertEqual(L10n.tr("Cleared queue"), "已清空队列")
        XCTAssertEqual(L10n.tr("Removed from playlist"), "已从歌单移除")
        XCTAssertEqual(LyricsFontSize.compact.displayName, "紧凑")
        XCTAssertEqual(LyricsAlignment.center.displayName, "居中")

        // Wave B translations
        XCTAssertEqual(L10n.tr("Quick Jump…"), "快速跳转…")
        XCTAssertEqual(L10n.tr("Mini Player"), "迷你播放器")
        XCTAssertEqual(L10n.tr("Open Main Window"), "打开主窗口")
        XCTAssertEqual(L10n.tr("Toggle Lyrics Panel"), "切换歌词面板")
        XCTAssertEqual(L10n.tr("Toggle Queue Panel"), "切换队列面板")
        XCTAssertEqual(L10n.tr("Drop folders or audio files to add to Musico"), "拖放文件夹或音频文件以添加到 Musico")
        XCTAssertEqual(L10n.tr("Insights"), "洞察")
        XCTAssertEqual(L10n.tr("Library Explored"), "资料库探索度")
        XCTAssertEqual(L10n.tr("Top Artists"), "常听艺术家")
        XCTAssertEqual(L10n.tr("Top Albums"), "常听专辑")
        XCTAssertEqual(L10n.tr("Actions"), "操作")
        XCTAssertEqual(L10n.tr("Now Playing"), "正在播放")
        XCTAssertEqual(L10n.tr("Volume: %@", "75%"), "音量：75%")
        XCTAssertEqual(L10n.albumCount(3), "3 张专辑")

        LanguageManager.shared.setLanguage(.english)
        XCTAssertEqual(L10n.albumCount(1), "1 album")
        XCTAssertEqual(L10n.albumCount(2), "2 albums")
        XCTAssertEqual(L10n.resolved, .en)
        XCTAssertEqual(AppLanguage.system.displayName, "Follow System")
        XCTAssertEqual(L10n.tr("Appearance"), "Appearance")
        XCTAssertEqual(AppThemeMode.light.displayName, "Light")
        XCTAssertEqual(BackgroundColorPreset.obsidian.displayName, "Obsidian")
        XCTAssertEqual(ForegroundColorPreset.coral.displayName, "Coral")
        XCTAssertEqual(L10n.tr("Folders"), "Folders")
        XCTAssertEqual(L10n.tr("Extensions"), "Extensions")
        XCTAssertEqual(ExtensionCapability.search.localizedTitle, "Cloud Search")
        XCTAssertEqual(L10n.tr("Local"), "Local")
        XCTAssertEqual(L10n.displayQueueName("Search (Demo): hello"), "Search (Demo): hello")
        XCTAssertEqual(CloudSourceError.rateLimited.errorDescription, "Rate limited by the cloud source. Please try again later.")
        XCTAssertEqual(CloudSourceError.permissionDenied(host: "evil.test").errorDescription, "Extension was denied access to host “evil.test”.")
        XCTAssertEqual(CloudSourceError.extensionUnavailable(name: "Demo").errorDescription, "Extension “Demo” is disabled or not installed.")
        XCTAssertEqual(L10n.songCount(1), "1 song")
        XCTAssertEqual(L10n.songCount(2), "2 songs")
        XCTAssertEqual(L10n.addSongsCount(1), "Add 1 Song")
        XCTAssertEqual(L10n.addSongsCount(3), "Add 3 Songs")
        XCTAssertEqual(L10n.displayArtist("Unknown Artist"), "Unknown Artist")
        XCTAssertEqual(L10n.displayQueueName("Queue 1"), "Queue 1")
    }
}
