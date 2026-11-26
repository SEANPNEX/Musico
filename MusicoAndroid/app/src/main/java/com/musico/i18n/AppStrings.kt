package com.musico.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import com.musico.model.LyricsSource
import com.musico.model.SearchField
import com.musico.model.TrackSortOrder
import com.musico.ui.theme.AppThemeMode
import com.musico.ui.theme.BackgroundColorPreset
import com.musico.ui.theme.ForegroundColorPreset
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

interface AppStrings {
    val locale: Locale

    // Language switcher
    val languageTitle: String
    val languageFollowSystem: String
    val languageEnglish: String
    val languageSimplifiedChinese: String

    // Common actions & labels
    val ok: String
    val cancel: String
    val save: String
    val done: String
    val edit: String
    val close: String
    val delete: String
    val rename: String
    val renameEllipsis: String
    val create: String
    val add: String
    val remove: String
    val back: String
    val play: String
    val pause: String
    val shuffle: String
    val previous: String
    val next: String
    val repeat: String
    val playing: String
    val paused: String
    val reorder: String
    val moveUp: String
    val moveDown: String
    val unknownArtist: String
    val unknownAlbum: String
    val musicoFolderName: String
    val nameLabel: String

    // Top-level navigation & app errors
    val tabFolders: String
    val tabLibrary: String
    val tabQueues: String
    val tabStats: String
    val lyrics: String
    val queue: String
    val closePanel: String
    val playbackProblem: String
    val thisSong: String
    val playbackErrorGeneric: String

    // Library sections
    val libraryTitle: String
    val sectionSongs: String
    val sectionAlbums: String
    val sectionArtists: String
    val sectionPlaylists: String

    // Folders screen
    val foldersTitle: String
    val folderFallbackTitle: String
    val checkForUpdates: String
    val addCloudFolder: String
    val rescan: String
    val sortFolder: String
    val folderOptions: String
    val moreOptions: String
    val saveToLocalFolderEllipsis: String
    val updateFromCloud: String
    val removeFolder: String
    val locateAgainEllipsis: String
    val addFolder: String
    val couldNotAddFolderTitle: String
    val couldNotAddFolderFallback: String
    val couldNotRelocateFolderFallback: String
    val errorDuplicateRoot: String
    val errorNotAFolder: String
    val errorUnsupportedCloudUrl: String
    val removeLocalFolderConfirmMessage: String
    val removeCloudFolderConfirmMessage: String
    val cloudUrlLabel: String
    val cloudUrlPlaceholder: String
    val cloudUrlSupportingText: String
    val cookieOptionalLabel: String
    val cookiePlaceholder: String
    val cloudPlaylistLoadErrorFallback: String
    val addingEllipsis: String
    val cloudPlaylistDefaultName: String
    val noFolderSelected: String
    val savingPlaylistTitle: String
    val saveCompleteTitle: String
    val saveToLocalFolderTitle: String
    val folderNameLabel: String
    val locationLabel: String
    val chooseFolder: String
    val addSavedFolderToLibrary: String
    val addedSavedFolderWithCloudTracking: String
    val couldNotAddFolderToLibraryFallback: String
    val couldNotSavePlaylistFallback: String
    val savedFolderLocationUnavailable: String
    val couldNotUpdateFromCloudFallback: String
    val updateCompleteTitle: String
    val fetchingLatestCloudPlaylist: String
    val lookingForMusic: String
    val addYourMusicFoldersTitle: String
    val addYourMusicFoldersDesc: String
    val foldersFooterHint: String
    val badgeYouTube: String
    val badgeBilibili: String
    val badgeGoogleDrive: String
    val badgeYouTubeLocal: String
    val badgeBilibiliLocal: String
    val badgeGoogleDriveLocal: String
    val badgeCloudLocal: String
    val folderUnavailable: String
    val folderPermissionLost: String
    val relocate: String
    val noMusicHereTitle: String
    val noMusicHereDesc: String
    val runInBackground: String
    val downloadsTitle: String
    val clearFinished: String
    val dismiss: String
    val noDownloadsTitle: String
    val noDownloadsDesc: String
    val downloadStatusCancelled: String
    val downloadStatusFailed: String
    val downloadStatusCompleted: String
    val downloadKindPlaylistExport: String
    val downloadKindTrackExport: String
    val downloadKindFolderUpdate: String
    val editCloudSourceEllipsis: String
    val editCloudSourceTitle: String
    val cloudSourceUpdated: String
    val switchSourceEllipsis: String
    val switchSourceAction: String
    val audioQualityLabel: String
    val streamingQualityLabel: String
    val downloadQualityLabel: String
    val currentTrackOnlyHint: String
    val qualityLosslessBadge: String
    val providerAutoMatchUrl: String
    fun providerMissingLabel(name: String): String
    fun providerDisabledLabel(name: String): String
    fun extensionRecoveryBanner(providerId: String, bestProviderName: String): String

    // Extensions & custom providers
    val extensionsTitle: String
    val importFromFile: String
    val importFromUrl: String
    val importExtensionUrlTitle: String
    val extensionUrlLabel: String
    val extensionUrlPlaceholder: String
    val extensionUrlSupportingText: String
    val noExtensionsTitle: String
    val noExtensionsDesc: String
    val extensionImportSuccess: String
    val extensionImportFailedTitle: String
    val extensionImportFailedFallback: String
    val permissionReviewTitle: String
    val permissionReviewWarning: String
    val allowedHostsLabel: String
    val capabilitiesLabel: String
    val installAndEnable: String
    fun batchPermissionReviewTitle(count: Int): String
    val batchPermissionReviewSubtitle: String
    fun installAndEnableCount(count: Int): String
    val extensionSettingsTitle: String
    val resetDefaults: String
    val extensionLogsTitle: String
    val clearLogs: String
    val noLogsYet: String
    val uninstall: String
    val uninstallExtensionMessage: String
    val permissionsLabel: String
    val settingsLabel: String
    val logsLabel: String
    val enabledLabel: String
    val disabledLabel: String
    val cloudProviderLabel: String
    val providerAutoDetect: String
    val badgeExtension: String
    val extensionDisabledBanner: String
    val extensionUnavailableStatus: String
    val manageExtensions: String
    val errorExtensionUnavailable: String
    val capabilityPlaylist: String
    val capabilityTree: String
    val capabilityStream: String
    val capabilityCookie: String
    val capabilityProxy: String
    val capabilityLyrics: String
    val capabilitySearch: String
    val capabilityQrLogin: String
    val capabilityWebLogin: String
    val capabilityPlaylistDesc: String
    val capabilityTreeDesc: String
    val capabilityStreamDesc: String
    val capabilityCookieDesc: String
    val capabilityProxyDesc: String
    val capabilityLyricsDesc: String
    val capabilitySearchDesc: String
    val capabilityQrLoginDesc: String
    val capabilityWebLoginDesc: String
    val extensionCookieLabel: String
    val extensionCookiePlaceholder: String
    val extensionCookieSupportingText: String
    val extensionServerUrlHint: String
    fun extensionServerUrlAllowedHost(host: String): String
    val showCookie: String
    val hideCookie: String
    val clearCookie: String
    val qrLoginAction: String
    val signInWithQrCode: String
    val webLoginAction: String
    val signInInBrowser: String
    fun webLoginDialogTitle(source: String): String
    val saveCookiesAction: String
    val webLoginPrompt: String
    fun webLoginHostStatus(host: String, count: Int): String
    fun webLoginCookiesDetected(count: Int): String
    val importCookiesFile: String
    val cookieImportFailedTitle: String
    val cookieFileReadError: String
    val cookieFileEmptyError: String
    val googleDriveAccountSection: String
    val accountStatusLabel: String
    val googleDriveConnected: String
    val googleDriveNotSignedIn: String
    val googleDriveClientIdLabel: String
    val googleDriveClientSecretLabel: String
    val signInWithGoogleAction: String
    val reconnectGoogleDriveAction: String
    val signOutGoogleDriveAction: String
    val googleDriveOAuthHint: String
    val qrLoginGenerating: String
    val qrLoginFailed: String
    val qrLoginExpired: String
    val qrLoginRefreshHint: String
    val qrLoginSignedIn: String
    val qrLoginScannedConfirm: String
    val qrLoginWaiting: String
    val qrLoginRefresh: String
    val autoUpdateExtensions: String
    val autoUpdateExtensionsDesc: String
    val checkingExtensionUpdates: String
    val extensionUpToDate: String
    val reviewUpdateAction: String
    val updateAction: String
    val approveAndUpdate: String
    val extensionUpdateReviewTitle: String
    val extensionUpdateEscalationWarning: String
    val newCapabilitiesLabel: String
    val newAllowedHostsLabel: String
    fun qrLoginScanPrompt(sourceName: String): String
    fun qrLoginCookieSaved(extensionName: String): String
    fun extensionVersionTransition(fromVersion: String, toVersion: String): String
    fun extensionUpdatedTo(version: String): String
    fun extensionBatchUpdateSummary(checked: Int, applied: Int, reviewRequired: Int, failed: Int): String

    // Songs screen & cloud search
    val searchPlaceholder: String
    val clearSearch: String
    val sortSongs: String
    val resetFieldFilter: String
    val noSongsYetTitle: String
    val noSongsYetDesc: String
    val noSearchResultsDesc: String
    val sortOptionTitle: String
    val sortOptionArtist: String
    val sortOptionAlbum: String
    val sortOptionRecentlyAdded: String
    val searchFieldKeyword: String
    val searchFieldArtist: String
    val searchFieldAlbum: String
    val searchFieldGenre: String
    val searchSourceLocal: String
    val cloudSearchKindAll: String
    val cloudSearchKindTracks: String
    val cloudSearchKindAlbums: String
    val cloudSearchKindPlaylists: String
    val cloudSearchKindAlbumBadge: String
    val cloudSearchKindPlaylistBadge: String
    val cloudSearchIdleDesc: String
    val noCloudSearchResultsDesc: String
    val loadMore: String
    val loadingMore: String
    val cloudSearchErrorTitle: String
    val cloudSearchAuthRequiredHint: String
    val cloudSearchRateLimitedHint: String
    val cloudSearchPermissionDeniedHint: String
    val cloudSearchNetworkErrorHint: String
    val openPreview: String
    val addAsCloudFolder: String
    val addedToFolders: String
    val collectionPreviewEmptyTitle: String
    val collectionPreviewEmptyDesc: String
    val collectionPreviewLoadFailedTitle: String
    val collectionPreviewUnsupported: String
    val saveToMusicoFolder: String
    val chooseFolderEllipsis: String
    val saveTrackDestinationMessage: String

    // Albums & Artists
    val noAlbumsYetTitle: String
    val noAlbumsYetDesc: String
    val albumOptions: String
    val noArtistsYetTitle: String
    val noArtistsYetDesc: String
    val artistOptions: String

    // Playlists
    val newPlaylist: String
    val favourites: String
    val playlistOptions: String
    val renamePlaylistTitle: String
    val deletePlaylistConfirmMessage: String
    val playlistFallbackTitle: String
    val nothingHereYetTitle: String
    val nothingHereYetDesc: String
    val removeFromPlaylist: String
    val removeFromPlaylistCd: String

    // Queues
    val queuesTitle: String
    val clearThisQueue: String
    val clearQueue: String
    val clearQueueConfirmMessage: String
    val newQueue: String
    val emptyLabel: String
    val sortQueue: String
    val queueActions: String
    val renameQueueEllipsis: String
    val deleteQueue: String
    val queueIsEmptyTitle: String
    val queueIsEmptyDesc: String
    val removeFromQueue: String
    val removeFromQueueCd: String
    val missingSong: String
    val renameQueueTitle: String
    val deleteQueueConfirmMessage: String
    val switchQueue: String

    // Stats
    val statsTitle: String
    val statListenedLabel: String
    val statNeverPlayedLabel: String
    val statsSectionRecent: String
    val statsSectionPlays: String
    val statsSectionNotLately: String
    val statsSectionNever: String
    val statsEmptyRecentTitle: String
    val statsEmptyRecentDesc: String
    val statsEmptyPlaysTitle: String
    val statsEmptyPlaysDesc: String
    val statsEmptyNotLatelyTitle: String
    val statsEmptyEverythingPlayedTitle: String
    val statsEmptyEverythingPlayedDesc: String
    val countOrderMost: String
    val countOrderLeast: String
    val statsInsightsTitle: String
    val statsLibraryExplored: String
    val statsTopArtists: String
    val statsTopAlbums: String

    // Player & Sheets
    val closePlayer: String
    val songInfo: String
    val addToFavourites: String
    val removeFromFavourites: String
    val equalizer: String
    val sleepTimer: String
    val nothingPlayingTitle: String
    val pickSongFromLibrary: String
    val notPlaying: String
    val pickSongHint: String
    val lyricsPanel: String
    val queuePanel: String

    // Lyrics
    val lyricsOptions: String
    val searchOnlineEllipsis: String
    val searchCloudLyricsTitle: String
    val importFromFileEllipsis: String
    val removeImportedLyrics: String
    val noLyricsTitle: String
    val noLyricsDesc: String
    val couldNotImportLyricsTitle: String
    val lyricsImportErrorFallback: String
    val lyricsSourceImported: String
    val lyricsSourceSidecar: String
    val lyricsSourceFolderMatch: String
    val lyricsSourceEmbedded: String
    val lyricsSourceImportedDesc: String
    val lyricsSourceSidecarDesc: String
    val lyricsSourceFolderMatchDesc: String
    val lyricsSourceEmbeddedDesc: String
    val saveLrcToFolder: String
    val savedLrcToFolderNotice: String
    val alsoSaveLrcToFolder: String
    val useLyrics: String
    val includeTranslation: String
    val lyricsTitleField: String
    val lyricsArtistField: String
    val searchAction: String
    val autoSourceLabel: String
    val lyricsFormatSynced: String
    val lyricsFormatPlain: String
    val lyricsFormatInstrumental: String
    val lyricsTranslatedBadge: String
    val noOnlineLyricsTitle: String
    val noOnlineLyricsDesc: String
    val searchingOnline: String
    val retryAction: String
    val selectResultToPreview: String
    val partialSourcesFailedHint: String
    val couldNotSaveLyricsTitle: String
    val couldNotSearchLyricsTitle: String

    // Equalizer
    val reset: String
    val presetLabel: String
    val eqCustom: String
    val eqPresetFlat: String
    val eqPresetBassBoost: String
    val eqPresetTrebleBoost: String
    val eqPresetRock: String
    val eqPresetPop: String
    val eqPresetJazz: String
    val eqPresetClassical: String
    val eqPresetVocal: String

    // Sleep timer
    val stopsAtEndOfSong: String
    val cancelTimer: String
    val stopPlayingAfter: String
    val decreaseMinutes: String
    val increaseMinutes: String
    val start: String
    val endOfCurrentSong: String

    // Song info
    val changeCover: String
    val couldNotLoadSelectedImage: String
    val titleLabel: String
    val artistLabel: String
    val albumLabel: String
    val genreLabel: String
    val addGenrePlaceholder: String
    val saveChanges: String
    val quickSearch: String
    val fileAndPlaybackInfo: String
    val infoPrecision: String
    val infoFrequency: String
    val infoBitRate: String
    val infoFileSize: String
    val infoLength: String
    val infoSoundTracks: String
    val infoTimeAdded: String
    val infoTimeEdited: String
    val infoLastPlayed: String
    val infoPlayedTimes: String
    val infoFileName: String
    val infoFileLocation: String
    val notAvailable: String
    val neverLabel: String
    val channelsMono: String
    val channelsStereo: String

    // Sort orders
    val sortCustom: String
    val sortNameAsc: String
    val sortNameDesc: String
    val sortDateModified: String
    val sortMostPlayed: String
    val sortLeastPlayed: String

    // Track actions & export
    val playNext: String
    val addToQueue: String
    val addToAnotherQueueEllipsis: String
    val playInNewQueue: String
    val addToPlaylistEllipsis: String
    val newPlaylistEllipsis: String
    val enterPlaylistNameHint: String
    val trackOptions: String
    val savingSongTitle: String
    val saveFailedTitle: String
    val songSavedTitle: String
    val couldNotSaveSongFallback: String
    val unknownError: String
    val libraryUpdated: String

    // Updater
    val checkingForUpdates: String
    val youreUpToDate: String
    val updateAvailable: String
    val downloadingUpdate: String
    val readyToInstall: String
    val updateError: String
    val checkingGithubReleases: String
    val openReleasePage: String
    val packageVerifiedReady: String
    val downloadAndInstall: String
    val install: String
    val checkAgain: String
    val retry: String
    val skipVersion: String
    val later: String
    val hide: String
    val noReleaseNotes: String

    // Formatted helpers
    fun songCount(count: Int): String
    fun albumCount(count: Int): String
    fun libraryExploredSummary(percentText: String, played: Int, total: Int): String
    fun restoreRemovedSubfolders(count: Int): String
    fun removeFolderConfirmTitle(name: String): String
    fun savingProgressWithTitle(done: Int, total: Int, title: String): String
    fun savingProgress(done: Int, total: Int): String
    fun savePlaylistSummary(saved: Int, total: Int, failed: Int): String
    fun andMoreErrors(count: Int): String
    fun updatingFolderTitle(name: String): String
    fun checkingProgressWithTitle(done: Int, total: Int, title: String): String
    fun checkingProgress(done: Int, total: Int): String
    fun updateAddedSongsSummary(added: Int, skipped: Int): String
    fun alreadyUpToDateSummary(skipped: Int): String
    fun failedCountLine(failed: Int): String
    fun readingScanProgress(done: Int, total: Int): String
    fun noSearchResultsTitle(query: String): String
    fun discPrefix(discNumber: Int): String
    fun deleteItemConfirmTitle(name: String): String
    fun clearQueueConfirmTitle(name: String): String
    fun queueSummary(songCount: Int, trackTitle: String, timeStr: String): String
    fun statPlaysLabel(count: Int): String
    fun songPlaysCount(count: Int): String
    fun statsEmptyNotLatelyDesc(staleDays: Int): String
    fun queuePosition(indexOneBased: Int, total: Int): String
    fun bandAccessibility(bandLabel: String, gainText: String): String
    fun preampLabel(gainText: String): String
    fun stopsIn(timeStr: String): String
    fun minutesCount(minutes: Int): String
    fun customMinutes(minutes: Int): String
    fun bitDepthFormat(bits: Int): String
    fun channelsCount(count: Int): String
    fun downloadingTrack(title: String): String
    fun savedAsFile(fileName: String): String
    fun currentVersionLabel(version: String): String
    fun musicoIsLatest(version: String): String
    fun downloadingAssetProgress(name: String, percent: Int): String
    fun downloadingAsset(name: String): String
    fun playbackStoppedConsecutive(count: Int): String
    fun playbackFailedTrack(title: String): String
    fun cloudSyncProgress(completedFolders: Int, totalFolders: Int, tracksFound: Int): String
    fun queueCountLabel(count: Int): String
    fun extensionVersionAuthor(version: String, author: String?): String
    fun extensionMissingBanner(providerId: String): String
    fun badgeCustomLocal(badge: String): String
    fun uninstallExtensionConfirmTitle(name: String): String
    fun detectedProviderHint(name: String): String
    fun extensionLogsCountLabel(count: Int): String
    fun cloudSearchPlaceholder(sourceName: String): String
    fun cloudSearchIdleTitle(sourceName: String): String
    fun addedFolderNotice(name: String): String
    fun formatListeningTime(totalSeconds: Double): String
    fun formatRelativeTime(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String

    fun displayArtist(artist: String): String =
        if (artist == "Unknown Artist") unknownArtist else artist

    fun displayAlbum(album: String): String =
        if (album == "Unknown Album") unknownAlbum else album

    fun displayQueueName(name: String): String = name

    fun searchFieldLabel(field: SearchField): String = when (field) {
        SearchField.KEYWORD -> searchFieldKeyword
        SearchField.ARTIST -> searchFieldArtist
        SearchField.ALBUM -> searchFieldAlbum
        SearchField.GENRE -> searchFieldGenre
    }

    fun sortOrderLabel(order: TrackSortOrder): String = when (order) {
        TrackSortOrder.CUSTOM -> sortCustom
        TrackSortOrder.NAME_ASC -> sortNameAsc
        TrackSortOrder.NAME_DESC -> sortNameDesc
        TrackSortOrder.DATE_MODIFIED -> sortDateModified
        TrackSortOrder.MOST_PLAYED -> sortMostPlayed
        TrackSortOrder.LEAST_PLAYED -> sortLeastPlayed
    }

    fun lyricsSourceBadge(source: LyricsSource): String = when (source) {
        LyricsSource.IMPORTED -> lyricsSourceImported
        LyricsSource.SIDECAR -> lyricsSourceSidecar
        LyricsSource.FOLDER_MATCH -> lyricsSourceFolderMatch
        LyricsSource.EMBEDDED -> lyricsSourceEmbedded
    }

    fun lyricsSourceDescription(source: LyricsSource): String = when (source) {
        LyricsSource.IMPORTED -> lyricsSourceImportedDesc
        LyricsSource.SIDECAR -> lyricsSourceSidecarDesc
        LyricsSource.FOLDER_MATCH -> lyricsSourceFolderMatchDesc
        LyricsSource.EMBEDDED -> lyricsSourceEmbeddedDesc
    }

    // Settings & Appearance
    val appearanceSection: String
    val generalSection: String
    val librarySection: String
    val aboutAndUpdatesSection: String
    val themeModeLabel: String
    val themeModeSystem: String
    val themeModeLight: String
    val themeModeDark: String
    val backgroundColorSetLabel: String
    val bgPresetDefault: String
    val bgPresetObsidian: String
    val bgPresetMidnight: String
    val bgPresetWarmCharcoal: String
    val bgPresetForestNight: String
    val bgPresetPaper: String
    val bgPresetMist: String
    val bgPresetBlossom: String
    val presetCustom: String
    val foregroundAndAccentLabel: String
    val fgPresetCoral: String
    val fgPresetOcean: String
    val fgPresetEmerald: String
    val fgPresetAmethyst: String
    val fgPresetAmber: String
    val fgPresetRose: String
    val fgPresetMonochrome: String
    val customBackgroundColorTitle: String
    val customAccentColorTitle: String
    val customTextColorLabel: String
    val customTextColorAuto: String
    val customTextColorTitle: String
    val useAutoTextColor: String
    val hexColorLabel: String
    val invalidHexColorError: String
    val colorRedLabel: String
    val colorGreenLabel: String
    val colorBlueLabel: String
    val colorPreviewSample: String
    val backgroundImageLabel: String
    val chooseImageAction: String
    val changeImageAction: String
    val imageVisibilityLabel: String
    val resetAppearanceToDefault: String
    val rescanLibraryAction: String
    val rescanningLibrary: String
    val appVersionLabel: String

    // Wave A: Deletion, Snackbars, Appearance & Lyrics Upgrades
    val removeOrDeleteEllipsis: String
    val removeFromLibrary: String
    val deleteFromDevice: String
    val deleteSongDialogTitle: String
    val deleteFolderDialogTitle: String
    val deleteLocalTrackMessage: String
    val deleteLocalFolderMessage: String
    val deleteFailedTitle: String
    val deleteFailedFallback: String
    val removeSongConfirmMessage: String
    val undo: String
    val addedToQueueNotice: String
    val playingNextNotice: String
    val removedFromLibraryNotice: String
    val deletedFromDeviceNotice: String
    val clearedQueueNotice: String
    val removedFromPlaylistNotice: String
    val appearancePreviewLabel: String
    val previewTrackTitle: String
    val previewTrackArtist: String
    val previewSampleButton: String
    val imageBlurLabel: String
    val lowContrastWarningText: String
    val lowContrastWarningAccent: String
    val lowContrastWarningBoth: String
    val autoFixContrastAction: String
    val syncToCurrentLine: String
    val lyricsFontSizeLabel: String
    val lyricsFontSizeSmall: String
    val lyricsFontSizeMedium: String
    val lyricsFontSizeLarge: String
    val lyricsAlignmentLabel: String
    val lyricsAlignmentLeft: String
    val lyricsAlignmentCenter: String

    fun themeModeLabel(mode: AppThemeMode): String = when (mode) {
        AppThemeMode.SYSTEM -> themeModeSystem
        AppThemeMode.LIGHT -> themeModeLight
        AppThemeMode.DARK -> themeModeDark
    }

    fun backgroundPresetLabel(preset: BackgroundColorPreset): String = when (preset) {
        BackgroundColorPreset.DEFAULT -> bgPresetDefault
        BackgroundColorPreset.OBSIDIAN -> bgPresetObsidian
        BackgroundColorPreset.MIDNIGHT -> bgPresetMidnight
        BackgroundColorPreset.WARM_CHARCOAL -> bgPresetWarmCharcoal
        BackgroundColorPreset.FOREST_NIGHT -> bgPresetForestNight
        BackgroundColorPreset.PAPER -> bgPresetPaper
        BackgroundColorPreset.MIST -> bgPresetMist
        BackgroundColorPreset.BLOSSOM -> bgPresetBlossom
        BackgroundColorPreset.CUSTOM -> presetCustom
    }

    fun foregroundPresetLabel(preset: ForegroundColorPreset): String = when (preset) {
        ForegroundColorPreset.CORAL -> fgPresetCoral
        ForegroundColorPreset.OCEAN -> fgPresetOcean
        ForegroundColorPreset.EMERALD -> fgPresetEmerald
        ForegroundColorPreset.AMETHYST -> fgPresetAmethyst
        ForegroundColorPreset.AMBER -> fgPresetAmber
        ForegroundColorPreset.ROSE -> fgPresetRose
        ForegroundColorPreset.MONOCHROME -> fgPresetMonochrome
        ForegroundColorPreset.CUSTOM -> presetCustom
    }

    fun eqPresetLabel(presetName: String?): String = when (presetName) {
        null -> eqCustom
        "Flat" -> eqPresetFlat
        "Bass Boost" -> eqPresetBassBoost
        "Treble Boost" -> eqPresetTrebleBoost
        "Rock" -> eqPresetRock
        "Pop" -> eqPresetPop
        "Jazz" -> eqPresetJazz
        "Classical" -> eqPresetClassical
        "Vocal" -> eqPresetVocal
        else -> presetName
    }
}

object EnStrings : AppStrings {
    override val locale: Locale = Locale.ENGLISH

    override val languageTitle = "Language"
    override val languageFollowSystem = "Follow System"
    override val languageEnglish = "English"
    override val languageSimplifiedChinese = "简体中文"

    override val ok = "OK"
    override val cancel = "Cancel"
    override val save = "Save"
    override val done = "Done"
    override val edit = "Edit"
    override val close = "Close"
    override val delete = "Delete"
    override val rename = "Rename"
    override val renameEllipsis = "Rename…"
    override val create = "Create"
    override val add = "Add"
    override val remove = "Remove"
    override val back = "Back"
    override val play = "Play"
    override val pause = "Pause"
    override val shuffle = "Shuffle"
    override val previous = "Previous"
    override val next = "Next"
    override val repeat = "Repeat"
    override val playing = "Playing"
    override val paused = "Paused"
    override val reorder = "Reorder"
    override val moveUp = "Move up"
    override val moveDown = "Move down"
    override val unknownArtist = "Unknown Artist"
    override val unknownAlbum = "Unknown Album"
    override val musicoFolderName = "Musico Folder"
    override val nameLabel = "Name"

    override val tabFolders = "Folders"
    override val tabLibrary = "Library"
    override val tabQueues = "Queues"
    override val tabStats = "Stats"
    override val lyrics = "Lyrics"
    override val queue = "Queue"
    override val closePanel = "Close panel"
    override val playbackProblem = "Playback Problem"
    override val thisSong = "This song"
    override val playbackErrorGeneric = "Playback error"

    override val libraryTitle = "Library"
    override val sectionSongs = "Songs"
    override val sectionAlbums = "Albums"
    override val sectionArtists = "Artists"
    override val sectionPlaylists = "Playlists"

    override val foldersTitle = "Folders"
    override val folderFallbackTitle = "Folder"
    override val checkForUpdates = "Check for Updates"
    override val addCloudFolder = "Add Cloud Folder"
    override val rescan = "Rescan"
    override val sortFolder = "Sort folder"
    override val folderOptions = "Folder options"
    override val moreOptions = "More options"
    override val saveToLocalFolderEllipsis = "Save to Local Folder…"
    override val updateFromCloud = "Update from Cloud"
    override val removeFolder = "Remove Folder"
    override val locateAgainEllipsis = "Locate Again…"
    override val addFolder = "Add Folder"
    override val couldNotAddFolderTitle = "Couldn’t Add Folder"
    override val couldNotAddFolderFallback = "Could not add that folder."
    override val couldNotRelocateFolderFallback = "Could not relocate that folder."
    override val errorDuplicateRoot = "That folder is already in your library."
    override val errorNotAFolder = "That location could not be opened as a folder."
    override val errorUnsupportedCloudUrl = "Enter a valid YouTube, Bilibili, or Google Drive URL."
    override val removeLocalFolderConfirmMessage = "Its songs leave your library, playlists and queues. The files themselves are not deleted."
    override val removeCloudFolderConfirmMessage = "Its songs leave your library, playlists and queues."
    override val cloudUrlLabel = "YouTube, Bilibili, or Google Drive URL"
    override val cloudUrlPlaceholder = "Playlist, video, or Google Drive link"
    override val cloudUrlSupportingText = "Supports YouTube / YT Music playlists & videos, Bilibili favlists, collections, series & BV / b23.tv links, and Google Drive shared folders or audio files."
    override val cookieOptionalLabel = "Cookie (optional)"
    override val cookiePlaceholder = "Only needed for private playlists"
    override val cloudPlaylistLoadErrorFallback = "Could not load that cloud playlist."
    override val addingEllipsis = "Adding…"
    override val cloudPlaylistDefaultName = "Cloud Playlist"
    override val noFolderSelected = "No folder selected"
    override val savingPlaylistTitle = "Saving Playlist"
    override val saveCompleteTitle = "Save Complete"
    override val saveToLocalFolderTitle = "Save to Local Folder"
    override val folderNameLabel = "Folder Name"
    override val locationLabel = "Location"
    override val chooseFolder = "Choose Folder"
    override val addSavedFolderToLibrary = "Add saved folder to Library"
    override val addedSavedFolderWithCloudTracking = "Added saved folder to Library with cloud tracking."
    override val couldNotAddFolderToLibraryFallback = "Could not add folder to Library."
    override val couldNotSavePlaylistFallback = "Could not save playlist."
    override val savedFolderLocationUnavailable = "Saved folder location is unavailable."
    override val couldNotUpdateFromCloudFallback = "Could not update from cloud."
    override val updateCompleteTitle = "Update Complete"
    override val fetchingLatestCloudPlaylist = "Fetching latest cloud playlist…"
    override val lookingForMusic = "Looking for music…"
    override val addYourMusicFoldersTitle = "Add Your Music Folders"
    override val addYourMusicFoldersDesc = "Pick folders on your device, add a YouTube, Bilibili, or Google Drive link, or copy songs into the Musico Folder and pull down to rescan."
    override val foldersFooterHint = "Add local folders, link YouTube, Bilibili, or Google Drive folders, or copy music into the Musico Folder and pull down to rescan."
    override val badgeYouTube = "YouTube"
    override val badgeBilibili = "Bilibili"
    override val badgeGoogleDrive = "Google Drive"
    override val badgeYouTubeLocal = "YouTube · Local"
    override val badgeBilibiliLocal = "Bilibili · Local"
    override val badgeGoogleDriveLocal = "Google Drive · Local"
    override val badgeCloudLocal = "Cloud · Local"
    override val folderUnavailable = "Unavailable — folder moved or disconnected"
    override val folderPermissionLost = "Folder permission lost or moved."
    override val relocate = "Relocate"
    override val noMusicHereTitle = "No Music Here"
    override val noMusicHereDesc = "Songs you add to this folder appear after a rescan."
    override val runInBackground = "Run in Background"
    override val downloadsTitle = "Downloads"
    override val clearFinished = "Clear Finished"
    override val dismiss = "Dismiss"
    override val noDownloadsTitle = "No Downloads"
    override val noDownloadsDesc = "Cloud playlist saves, song downloads, and folder updates appear here."
    override val downloadStatusCancelled = "Cancelled"
    override val downloadStatusFailed = "Failed"
    override val downloadStatusCompleted = "Completed"
    override val downloadKindPlaylistExport = "Saving Playlist"
    override val downloadKindTrackExport = "Saving Song"
    override val downloadKindFolderUpdate = "Updating Folder"
    override val editCloudSourceEllipsis = "Edit Cloud Source…"
    override val editCloudSourceTitle = "Edit Cloud Source"
    override val cloudSourceUpdated = "Cloud source updated."
    override val switchSourceEllipsis = "Switch Source…"
    override val switchSourceAction = "Switch Source"
    override val audioQualityLabel = "Audio Quality"
    override val streamingQualityLabel = "Streaming Quality"
    override val downloadQualityLabel = "Download Quality"
    override val currentTrackOnlyHint = "Applies to current song only"
    override val qualityLosslessBadge = "Lossless"
    override val providerAutoMatchUrl = "Auto (Match URL)"
    override fun providerMissingLabel(name: String) = "$name (Missing)"
    override fun providerDisabledLabel(name: String) = "$name (Disabled)"
    override fun extensionRecoveryBanner(providerId: String, bestProviderName: String) =
        "Extension “$providerId” is unavailable. Switch to $bestProviderName to restore playback."

    override val extensionsTitle = "Extensions"
    override val importFromFile = "Import File"
    override val importFromUrl = "Import URL"
    override val importExtensionUrlTitle = "Import Extension from URL"
    override val extensionUrlLabel = "Extension HTTPS URL"
    override val extensionUrlPlaceholder = "https://example.com/source.musicoext"
    override val extensionUrlSupportingText = "Supports .musicoext bundles, .musico.js scripts, or .zip packs over HTTPS."
    override val noExtensionsTitle = "No Extensions Installed"
    override val noExtensionsDesc = "Import .musicoext bundles, .musico.js scripts, or a multi-plugin .zip pack to add custom cloud music sources."
    override val extensionImportSuccess = "Extension installed and enabled."
    override val extensionImportFailedTitle = "Couldn’t Import Extension"
    override val extensionImportFailedFallback = "The selected extension could not be loaded."
    override val permissionReviewTitle = "Review Extension Permissions"
    override val permissionReviewWarning = "Only install extensions from sources you trust. Network requests are restricted to the hosts listed below."
    override val allowedHostsLabel = "Allowed Hosts"
    override val capabilitiesLabel = "Capabilities"
    override val installAndEnable = "Install & Enable"
    override fun batchPermissionReviewTitle(count: Int) = "Review Imported Extensions ($count)"
    override val batchPermissionReviewSubtitle = "Select which extensions to install and enable. Network requests are restricted to each extension's listed hosts."
    override fun installAndEnableCount(count: Int) = "Install & Enable ($count)"
    override val extensionSettingsTitle = "Extension Settings"
    override val resetDefaults = "Reset to Defaults"
    override val extensionLogsTitle = "Extension Logs"
    override val clearLogs = "Clear"
    override val noLogsYet = "No logs recorded yet."
    override val uninstall = "Uninstall"
    override val uninstallExtensionMessage = "This extension will be removed. Cloud folders using it will stay in your library as unavailable until reinstalled."
    override val permissionsLabel = "Permissions"
    override val settingsLabel = "Settings"
    override val logsLabel = "Logs"
    override val enabledLabel = "Enabled"
    override val disabledLabel = "Disabled"
    override val cloudProviderLabel = "Source Provider"
    override val providerAutoDetect = "Auto-Detect"
    override val badgeExtension = "Extension"
    override val extensionDisabledBanner = "This extension is disabled. Enable it in Extensions to sync and stream its songs."
    override val extensionUnavailableStatus = "Extension disabled or missing"
    override val manageExtensions = "Extensions"
    override val errorExtensionUnavailable = "This cloud extension is disabled or not installed."
    override val capabilityPlaylist = "Playlist"
    override val capabilityTree = "Folder Tree"
    override val capabilityStream = "Audio Stream"
    override val capabilityCookie = "Cookie Auth"
    override val capabilityProxy = "Proxy"
    override val capabilityLyrics = "Lyrics Search"
    override val capabilitySearch = "Cloud Search"
    override val capabilityQrLogin = "QR Code Login"
    override val capabilityWebLogin = "Browser Login"
    override val capabilityPlaylistDesc = "Load playlists or albums from links"
    override val capabilityTreeDesc = "Browse nested cloud folders"
    override val capabilityStreamDesc = "Resolve playable audio streams"
    override val capabilityCookieDesc = "Use an optional cookie for account access"
    override val capabilityProxyDesc = "Route extension network requests through proxy"
    override val capabilityLyricsDesc = "Search online lyrics"
    override val capabilitySearchDesc = "Search tracks, albums, and playlists from this source"
    override val capabilityQrLoginDesc = "Sign in by scanning a QR code in-app"
    override val capabilityWebLoginDesc = "Sign in via an in-app browser to capture session cookies"
    override val extensionCookieLabel = "Extension Cookie"
    override val extensionCookiePlaceholder = "Paste cookie header (optional)"
    override val extensionCookieSupportingText = "Stored locally and sent only to this extension for search, preview, and streaming."
    override val extensionServerUrlHint = "Enter an http:// or https:// server URL. Its host will be allowed for this extension."
    override fun extensionServerUrlAllowedHost(host: String) = "Allows network access to: $host"
    override val showCookie = "Show cookie"
    override val hideCookie = "Hide cookie"
    override val clearCookie = "Clear cookie"
    override val qrLoginAction = "QR Login"
    override val signInWithQrCode = "Sign In with QR Code…"
    override val webLoginAction = "Browser Login"
    override val signInInBrowser = "Sign In in Browser…"
    override fun webLoginDialogTitle(source: String) = "Sign In to $source"
    override val saveCookiesAction = "Save Cookies"
    override val webLoginPrompt = "Sign in below, then tap Save Cookies."
    override fun webLoginHostStatus(host: String, count: Int) = "$host · $count cookies captured"
    override fun webLoginCookiesDetected(count: Int) = "Sign-in cookies detected ($count). Tap Save Cookies to finish."
    override val importCookiesFile = "Import cookies.txt…"
    override val cookieImportFailedTitle = "Couldn't Import Cookies"
    override val cookieFileReadError = "Could not read cookie file as text."
    override val cookieFileEmptyError = "No valid cookies for this extension were found in the file."
    override val googleDriveAccountSection = "Google Drive Account"
    override val accountStatusLabel = "Account Status"
    override val googleDriveConnected = "Connected"
    override val googleDriveNotSignedIn = "Not Signed In"
    override val googleDriveClientIdLabel = "OAuth Client ID"
    override val googleDriveClientSecretLabel = "OAuth Client Secret (Optional)"
    override val signInWithGoogleAction = "Sign In with Google"
    override val reconnectGoogleDriveAction = "Reconnect Google Drive"
    override val signOutGoogleDriveAction = "Sign Out"
    override val googleDriveOAuthHint = "Sign in with OAuth 2.0 to access your private Google Drive folders and bypass public download confirmation pages. Public links still work without signing in."
    override val qrLoginGenerating = "Generating QR code…"
    override val qrLoginFailed = "QR Code Login Failed"
    override val qrLoginExpired = "QR Code Expired"
    override val qrLoginRefreshHint = "Refresh to generate a new QR code."
    override val qrLoginSignedIn = "Signed In"
    override val qrLoginScannedConfirm = "Scanned — Confirm on Your Phone"
    override val qrLoginWaiting = "Waiting for QR code scan…"
    override val qrLoginRefresh = "Refresh QR Code"
    override val autoUpdateExtensions = "Auto-update extensions"
    override val autoUpdateExtensionsDesc = "Check for extension updates on launch (every 6 hours)"
    override val checkingExtensionUpdates = "Checking for extension updates…"
    override val extensionUpToDate = "Up to date"
    override val reviewUpdateAction = "Review Update"
    override val updateAction = "Update"
    override val approveAndUpdate = "Approve & Update"
    override val extensionUpdateReviewTitle = "Review Extension Update"
    override val extensionUpdateEscalationWarning =
        "This update requests additional permissions. Review the new capabilities and hosts before applying."
    override val newCapabilitiesLabel = "New Capabilities"
    override val newAllowedHostsLabel = "New Allowed Hosts"
    override fun qrLoginScanPrompt(sourceName: String) = "Scan with $sourceName App"
    override fun qrLoginCookieSaved(extensionName: String) = "Session cookie saved to $extensionName."
    override fun extensionVersionTransition(fromVersion: String, toVersion: String) =
        "v$fromVersion → v$toVersion"
    override fun extensionUpdatedTo(version: String) = "Updated to v$version"
    override fun extensionBatchUpdateSummary(
        checked: Int,
        applied: Int,
        reviewRequired: Int,
        failed: Int,
    ): String = buildString {
        if (checked == 0) {
            append("No extensions with an update URL.")
        } else {
            val parts = buildList {
                if (applied > 0) add("$applied updated")
                if (reviewRequired > 0) add("$reviewRequired awaiting review")
                if (failed > 0) add("$failed failed")
            }
            if (parts.isEmpty()) {
                append("All extensions up to date.")
            } else {
                append(parts.joinToString(" · "))
            }
        }
    }

    override val searchPlaceholder = "Songs, artists, albums, genres"
    override val clearSearch = "Clear search"
    override val sortSongs = "Sort songs"
    override val resetFieldFilter = "Reset field filter"
    override val noSongsYetTitle = "No Songs Yet"
    override val noSongsYetDesc = "Add a folder in the Folders tab to build your library."
    override val noSearchResultsDesc = "Try searching for another song, artist, or album."
    override val sortOptionTitle = "Title"
    override val sortOptionArtist = "Artist"
    override val sortOptionAlbum = "Album"
    override val sortOptionRecentlyAdded = "Recently Added"
    override val searchFieldKeyword = "Keyword"
    override val searchFieldArtist = "Artist"
    override val searchFieldAlbum = "Album"
    override val searchFieldGenre = "Genre"
    override val searchSourceLocal = "Local"
    override val cloudSearchKindAll = "All"
    override val cloudSearchKindTracks = "Songs"
    override val cloudSearchKindAlbums = "Albums"
    override val cloudSearchKindPlaylists = "Playlists"
    override val cloudSearchKindAlbumBadge = "Album"
    override val cloudSearchKindPlaylistBadge = "Playlist"
    override val cloudSearchIdleDesc = "Enter keywords and tap Search to query this extension."
    override val noCloudSearchResultsDesc = "Try another query or switch category."
    override val loadMore = "Load More"
    override val loadingMore = "Loading more…"
    override val cloudSearchErrorTitle = "Search Failed"
    override val cloudSearchAuthRequiredHint = "Authentication required. Configure this extension’s cookie in Folders → Extensions → Settings."
    override val cloudSearchRateLimitedHint = "Rate limited by this source. Please wait a moment and try again."
    override val cloudSearchPermissionDeniedHint = "Blocked by the extension’s host allowlist."
    override val cloudSearchNetworkErrorHint = "Network error while contacting this source."
    override val openPreview = "Open Preview"
    override val addAsCloudFolder = "Add as Cloud Folder"
    override val addedToFolders = "Added to Folders"
    override val collectionPreviewEmptyTitle = "No Songs Found"
    override val collectionPreviewEmptyDesc = "This collection does not contain any playable items."
    override val collectionPreviewLoadFailedTitle = "Couldn’t Load Collection"
    override val collectionPreviewUnsupported = "This extension does not support loading collection previews."
    override val saveToMusicoFolder = "Save to Musico Folder"
    override val chooseFolderEllipsis = "Choose Folder…"
    override val saveTrackDestinationMessage = "Choose where to save this song locally. Saved songs join your local library without cloud sync tracking."

    override val noAlbumsYetTitle = "No Albums Yet"
    override val noAlbumsYetDesc = "Albums appear once your library has songs."
    override val albumOptions = "Album options"
    override val noArtistsYetTitle = "No Artists Yet"
    override val noArtistsYetDesc = "Artists appear once your library has songs."
    override val artistOptions = "Artist options"

    override val newPlaylist = "New Playlist"
    override val favourites = "Favourites"
    override val playlistOptions = "Playlist options"
    override val renamePlaylistTitle = "Rename Playlist"
    override val deletePlaylistConfirmMessage = "This playlist will be removed. Your songs will stay in your library."
    override val playlistFallbackTitle = "Playlist"
    override val nothingHereYetTitle = "Nothing Here Yet"
    override val nothingHereYetDesc = "Open the menu on a song, album or folder to add it."
    override val removeFromPlaylist = "Remove from Playlist"
    override val removeFromPlaylistCd = "Remove from playlist"

    override val queuesTitle = "Queues"
    override val clearThisQueue = "Clear This Queue"
    override val clearQueue = "Clear Queue"
    override val clearQueueConfirmMessage = "Your songs remain in your library."
    override val newQueue = "New Queue"
    override val emptyLabel = "Empty"
    override val sortQueue = "Sort queue"
    override val queueActions = "Queue actions"
    override val renameQueueEllipsis = "Rename Queue…"
    override val deleteQueue = "Delete Queue"
    override val queueIsEmptyTitle = "Queue is Empty"
    override val queueIsEmptyDesc = "This queue is empty. Tap a song in your library to fill it."
    override val removeFromQueue = "Remove from Queue"
    override val removeFromQueueCd = "Remove from queue"
    override val missingSong = "Missing song"
    override val renameQueueTitle = "Rename Queue"
    override val deleteQueueConfirmMessage = "This queue will be deleted. Your songs remain in your library."
    override val switchQueue = "Switch Queue"

    override val statsTitle = "Stats"
    override val statListenedLabel = "listened"
    override val statNeverPlayedLabel = "never played"
    override val statsSectionRecent = "Recent"
    override val statsSectionPlays = "Plays"
    override val statsSectionNotLately = "Not Lately"
    override val statsSectionNever = "Never"
    override val statsEmptyRecentTitle = "Nothing Played Yet"
    override val statsEmptyRecentDesc = "Songs appear here a few seconds after they start playing."
    override val statsEmptyPlaysTitle = "No Plays Yet"
    override val statsEmptyPlaysDesc = "A song counts as played once you have listened to half of it."
    override val statsEmptyNotLatelyTitle = "Nothing Neglected"
    override val statsEmptyEverythingPlayedTitle = "Everything Played"
    override val statsEmptyEverythingPlayedDesc = "Every song in your library has been played at least once."
    override val countOrderMost = "Most Played First"
    override val countOrderLeast = "Least Played First"
    override val statsInsightsTitle = "Insights"
    override val statsLibraryExplored = "Library Explored"
    override val statsTopArtists = "Top Artists"
    override val statsTopAlbums = "Top Albums"

    override val closePlayer = "Close player"
    override val songInfo = "Song Info"
    override val addToFavourites = "Add to Favourites"
    override val removeFromFavourites = "Remove from Favourites"
    override val equalizer = "Equalizer"
    override val sleepTimer = "Sleep Timer"
    override val nothingPlayingTitle = "Nothing Playing"
    override val pickSongFromLibrary = "Pick a song from your library."
    override val notPlaying = "Not Playing"
    override val pickSongHint = "Pick a song from your library"
    override val lyricsPanel = "Lyrics panel"
    override val queuePanel = "Queue panel"

    override val lyricsOptions = "Lyrics options"
    override val searchOnlineEllipsis = "Search Online…"
    override val searchCloudLyricsTitle = "Search Cloud Lyrics"
    override val importFromFileEllipsis = "Import from File…"
    override val removeImportedLyrics = "Remove Imported Lyrics"
    override val noLyricsTitle = "No Lyrics"
    override val noLyricsDesc = "Import a .lrc or .txt file for this song, or put one with the same name next to it."
    override val couldNotImportLyricsTitle = "Couldn’t Import Lyrics"
    override val lyricsImportErrorFallback = "That file could not be read as lyrics text."
    override val lyricsSourceImported = "Imported"
    override val lyricsSourceSidecar = "Sidecar"
    override val lyricsSourceFolderMatch = "Folder Match"
    override val lyricsSourceEmbedded = "Embedded"
    override val lyricsSourceImportedDesc = "From the file you imported"
    override val lyricsSourceSidecarDesc = "From a file next to the song"
    override val lyricsSourceFolderMatchDesc = "Matched from song folder"
    override val lyricsSourceEmbeddedDesc = "From the song’s tags"
    override val saveLrcToFolder = "Save .lrc to Song Folder"
    override val savedLrcToFolderNotice = "Saved .lrc to song folder."
    override val alsoSaveLrcToFolder = "Also save .lrc to song folder"
    override val useLyrics = "Use Lyrics"
    override val includeTranslation = "Include Translation"
    override val lyricsTitleField = "Title"
    override val lyricsArtistField = "Artist"
    override val searchAction = "Search"
    override val autoSourceLabel = "Auto"
    override val lyricsFormatSynced = "Synced"
    override val lyricsFormatPlain = "Plain"
    override val lyricsFormatInstrumental = "Instrumental"
    override val lyricsTranslatedBadge = "Translated"
    override val noOnlineLyricsTitle = "No Lyrics Found Online"
    override val noOnlineLyricsDesc = "Try editing the title or artist above, or switch lyrics source."
    override val searchingOnline = "Searching…"
    override val retryAction = "Try Again"
    override val selectResultToPreview = "Select a result to preview"
    override val partialSourcesFailedHint = "Some sources could not be reached."
    override val couldNotSaveLyricsTitle = "Couldn’t Save Lyrics"
    override val couldNotSearchLyricsTitle = "Couldn’t Search Lyrics"

    override val reset = "Reset"
    override val presetLabel = "Preset"
    override val eqCustom = "Custom"
    override val eqPresetFlat = "Flat"
    override val eqPresetBassBoost = "Bass Boost"
    override val eqPresetTrebleBoost = "Treble Boost"
    override val eqPresetRock = "Rock"
    override val eqPresetPop = "Pop"
    override val eqPresetJazz = "Jazz"
    override val eqPresetClassical = "Classical"
    override val eqPresetVocal = "Vocal"

    override val stopsAtEndOfSong = "Stops at the end of this song"
    override val cancelTimer = "Cancel Timer"
    override val stopPlayingAfter = "Stop playing after"
    override val decreaseMinutes = "Decrease minutes"
    override val increaseMinutes = "Increase minutes"
    override val start = "Start"
    override val endOfCurrentSong = "End of current song"

    override val changeCover = "Change Cover"
    override val couldNotLoadSelectedImage = "Could not load selected image."
    override val titleLabel = "Title"
    override val artistLabel = "Artist"
    override val albumLabel = "Album"
    override val genreLabel = "Genre"
    override val addGenrePlaceholder = "Add genre"
    override val saveChanges = "Save Changes"
    override val quickSearch = "Quick Search"
    override val fileAndPlaybackInfo = "File & Playback Info"
    override val infoPrecision = "Precision"
    override val infoFrequency = "Frequency"
    override val infoBitRate = "Bit Rate"
    override val infoFileSize = "File Size"
    override val infoLength = "Length"
    override val infoSoundTracks = "Sound Tracks"
    override val infoTimeAdded = "Time Added"
    override val infoTimeEdited = "Time Edited"
    override val infoLastPlayed = "Last Played"
    override val infoPlayedTimes = "Played Times"
    override val infoFileName = "File Name"
    override val infoFileLocation = "File Location"
    override val notAvailable = "N/A"
    override val neverLabel = "Never"
    override val channelsMono = "1 (Mono)"
    override val channelsStereo = "2 (Stereo)"

    override val sortCustom = "Custom"
    override val sortNameAsc = "Name (A–Z)"
    override val sortNameDesc = "Name (Z–A)"
    override val sortDateModified = "Date Modified"
    override val sortMostPlayed = "Most Played"
    override val sortLeastPlayed = "Least Played"

    override val playNext = "Play Next"
    override val addToQueue = "Add to Queue"
    override val addToAnotherQueueEllipsis = "Add to Another Queue…"
    override val playInNewQueue = "Play in New Queue"
    override val addToPlaylistEllipsis = "Add to Playlist…"
    override val newPlaylistEllipsis = "New Playlist…"
    override val enterPlaylistNameHint = "Enter a name for the playlist."
    override val trackOptions = "Track options"
    override val savingSongTitle = "Saving Song"
    override val saveFailedTitle = "Save Failed"
    override val songSavedTitle = "Song Saved"
    override val couldNotSaveSongFallback = "Could not save song."
    override val unknownError = "Unknown error"
    override val libraryUpdated = "Library updated."

    override val checkingForUpdates = "Checking for Updates"
    override val youreUpToDate = "You’re Up to Date"
    override val updateAvailable = "Update Available"
    override val downloadingUpdate = "Downloading Update"
    override val readyToInstall = "Ready to Install"
    override val updateError = "Update Error"
    override val checkingGithubReleases = "Checking GitHub Releases…"
    override val openReleasePage = "Open Release Page"
    override val packageVerifiedReady = "Package verified and ready to install."
    override val downloadAndInstall = "Download & Install"
    override val install = "Install"
    override val checkAgain = "Check Again"
    override val retry = "Retry"
    override val skipVersion = "Skip Version"
    override val later = "Later"
    override val hide = "Hide"
    override val noReleaseNotes = "No release notes provided."

    override fun songCount(count: Int) = if (count == 1) "1 song" else "$count songs"
    override fun albumCount(count: Int) = if (count == 1) "1 album" else "$count albums"
    override fun libraryExploredSummary(percentText: String, played: Int, total: Int) =
        "$percentText · $played / $total songs"
    override fun restoreRemovedSubfolders(count: Int) = "Restore Removed Subfolders ($count)"
    override fun removeFolderConfirmTitle(name: String) = "Remove “$name” from your library?"
    override fun savingProgressWithTitle(done: Int, total: Int, title: String) =
        "Saving $done of $total: $title"
    override fun savingProgress(done: Int, total: Int) = "Saving $done of $total…"
    override fun savePlaylistSummary(saved: Int, total: Int, failed: Int) = buildString {
        append("Saved $saved of $total songs.")
        if (failed > 0) append(" $failed failed.")
    }
    override fun andMoreErrors(count: Int) = "…and $count more."
    override fun updatingFolderTitle(name: String) = "Updating “$name”"
    override fun checkingProgressWithTitle(done: Int, total: Int, title: String) =
        "Checking $done of $total: $title"
    override fun checkingProgress(done: Int, total: Int) = "Checking $done of $total…"
    override fun updateAddedSongsSummary(added: Int, skipped: Int): String {
        val word = if (added == 1) "song" else "songs"
        return "Added $added new $word ($skipped already saved)."
    }
    override fun alreadyUpToDateSummary(skipped: Int): String {
        val word = if (skipped == 1) "song" else "songs"
        return "Already up to date ($skipped $word)."
    }
    override fun failedCountLine(failed: Int) = "$failed failed."
    override fun readingScanProgress(done: Int, total: Int) = "Reading $done of $total songs…"
    override fun noSearchResultsTitle(query: String) = "No Results for “$query”"
    override fun discPrefix(discNumber: Int) = "Disc $discNumber · "
    override fun deleteItemConfirmTitle(name: String) = "Delete “$name”?"
    override fun clearQueueConfirmTitle(name: String) = "Remove every song from “$name”?"
    override fun queueSummary(songCount: Int, trackTitle: String, timeStr: String) =
        "${songCount(songCount)} · $trackTitle at $timeStr"
    override fun statPlaysLabel(count: Int) = if (count == 1) "play" else "plays"
    override fun songPlaysCount(count: Int) = if (count == 1) "1 play" else "$count plays"
    override fun statsEmptyNotLatelyDesc(staleDays: Int) =
        "Songs you have not listened to for $staleDays days appear here."
    override fun queuePosition(indexOneBased: Int, total: Int) = "$indexOneBased of $total"
    override fun bandAccessibility(bandLabel: String, gainText: String) =
        "$bandLabel hertz, $gainText decibels"
    override fun preampLabel(gainText: String) = "Preamp $gainText dB"
    override fun stopsIn(timeStr: String) = "Stops in $timeStr"
    override fun minutesCount(minutes: Int) = "$minutes minutes"
    override fun customMinutes(minutes: Int) = "Custom: $minutes minutes"
    override fun bitDepthFormat(bits: Int) = "$bits-bit"
    override fun channelsCount(count: Int) = "$count channels"
    override fun downloadingTrack(title: String) = "Downloading $title…"
    override fun savedAsFile(fileName: String) = "Saved as “$fileName”."
    override fun currentVersionLabel(version: String) = "Current version: $version"
    override fun musicoIsLatest(version: String) = "Musico $version is the latest version."
    override fun downloadingAssetProgress(name: String, percent: Int) =
        "Downloading $name ($percent%)…"
    override fun downloadingAsset(name: String) = "Downloading $name…"
    override fun playbackStoppedConsecutive(count: Int) =
        "Playback stopped: $count songs in a row could not be played."
    override fun playbackFailedTrack(title: String) = "“$title” could not be played."
    override fun cloudSyncProgress(completedFolders: Int, totalFolders: Int, tracksFound: Int) =
        "Syncing folders ($completedFolders/$totalFolders · ${songCount(tracksFound)})…"
    override fun queueCountLabel(count: Int) = if (count == 1) "1 queue" else "$count queues"
    override fun extensionVersionAuthor(version: String, author: String?): String =
        if (author.isNullOrBlank()) "v$version" else "v$version · $author"
    override fun extensionMissingBanner(providerId: String): String =
        "Extension “$providerId” is missing. Reinstall it in Extensions to restore playback."
    override fun badgeCustomLocal(badge: String): String = "$badge · Local"
    override fun uninstallExtensionConfirmTitle(name: String): String = "Uninstall “$name”?"
    override fun detectedProviderHint(name: String): String = "Detected: $name"
    override fun extensionLogsCountLabel(count: Int): String = "Logs ($count)"
    override fun cloudSearchPlaceholder(sourceName: String): String = "Search $sourceName"
    override fun cloudSearchIdleTitle(sourceName: String): String = "Search $sourceName"
    override fun addedFolderNotice(name: String): String = "Added “$name” to Folders."

    override val appearanceSection = "Appearance"
    override val generalSection = "General"
    override val librarySection = "Library"
    override val aboutAndUpdatesSection = "About & Updates"
    override val themeModeLabel = "Theme Mode"
    override val themeModeSystem = "System"
    override val themeModeLight = "Light"
    override val themeModeDark = "Dark"
    override val backgroundColorSetLabel = "Background Color"
    override val bgPresetDefault = "Default"
    override val bgPresetObsidian = "Obsidian"
    override val bgPresetMidnight = "Midnight"
    override val bgPresetWarmCharcoal = "Warm Charcoal"
    override val bgPresetForestNight = "Forest Night"
    override val bgPresetPaper = "Paper"
    override val bgPresetMist = "Mist"
    override val bgPresetBlossom = "Blossom"
    override val presetCustom = "Custom"
    override val foregroundAndAccentLabel = "Foreground & Accent"
    override val fgPresetCoral = "Coral"
    override val fgPresetOcean = "Ocean"
    override val fgPresetEmerald = "Emerald"
    override val fgPresetAmethyst = "Amethyst"
    override val fgPresetAmber = "Amber"
    override val fgPresetRose = "Rose"
    override val fgPresetMonochrome = "Monochrome"
    override val customBackgroundColorTitle = "Custom Background Color"
    override val customAccentColorTitle = "Custom Accent Color"
    override val customTextColorLabel = "Text Color"
    override val customTextColorAuto = "Auto (Adaptive Contrast)"
    override val customTextColorTitle = "Custom Text Color"
    override val useAutoTextColor = "Use Auto"
    override val hexColorLabel = "Hex Color (#RRGGBB)"
    override val invalidHexColorError = "Enter a valid 6-digit hex color like #E85440"
    override val colorRedLabel = "Red"
    override val colorGreenLabel = "Green"
    override val colorBlueLabel = "Blue"
    override val colorPreviewSample = "Sample Text"
    override val backgroundImageLabel = "Background Image"
    override val chooseImageAction = "Choose Image"
    override val changeImageAction = "Change Image"
    override val imageVisibilityLabel = "Image Visibility"
    override val resetAppearanceToDefault = "Reset to Default"
    override val rescanLibraryAction = "Rescan Library"
    override val rescanningLibrary = "Scanning library…"
    override val appVersionLabel = "Version"

    override val removeOrDeleteEllipsis = "Remove or Delete…"
    override val removeFromLibrary = "Remove from Library"
    override val deleteFromDevice = "Delete from Device"
    override val deleteSongDialogTitle = "Remove or Delete Song?"
    override val deleteFolderDialogTitle = "Remove or Delete Folder?"
    override val deleteLocalTrackMessage = "“Remove from Library” hides this song from Musico without deleting the file. “Delete from Device” permanently deletes the audio file."
    override val deleteLocalFolderMessage = "“Remove from Library” hides this folder and its songs from Musico without deleting files. “Delete from Device” permanently deletes the folder and its contents."
    override val deleteFailedTitle = "Could Not Delete"
    override val deleteFailedFallback = "Could not delete from device."
    override val removeSongConfirmMessage = "This song leaves your library, playlists, and queues."
    override val undo = "Undo"
    override val addedToQueueNotice = "Added to Queue"
    override val playingNextNotice = "Playing Next"
    override val removedFromLibraryNotice = "Removed from Library"
    override val deletedFromDeviceNotice = "Deleted from Device"
    override val clearedQueueNotice = "Cleared queue"
    override val removedFromPlaylistNotice = "Removed from playlist"
    override val appearancePreviewLabel = "Preview"
    override val previewTrackTitle = "Midnight Reverie"
    override val previewTrackArtist = "Musico Studio"
    override val previewSampleButton = "Play"
    override val imageBlurLabel = "Background Blur"
    override val lowContrastWarningText = "Text contrast is low and may be hard to read."
    override val lowContrastWarningAccent = "Accent contrast is low against this background."
    override val lowContrastWarningBoth = "Text and accent contrast are low against this background."
    override val autoFixContrastAction = "Auto-fix Contrast"
    override val syncToCurrentLine = "Sync to current line"
    override val lyricsFontSizeLabel = "Font Size"
    override val lyricsFontSizeSmall = "Small"
    override val lyricsFontSizeMedium = "Medium"
    override val lyricsFontSizeLarge = "Large"
    override val lyricsAlignmentLabel = "Alignment"
    override val lyricsAlignmentLeft = "Left"
    override val lyricsAlignmentCenter = "Center"

    override fun formatListeningTime(totalSeconds: Double): String {
        if (!totalSeconds.isFinite() || totalSeconds <= 0.0) return "0 min"
        val minutes = (totalSeconds / 60.0).toLong()
        return if (minutes >= 60) {
            "${minutes / 60} h ${minutes % 60} min"
        } else {
            "$minutes min"
        }
    }

    override fun formatRelativeTime(epochMs: Long, nowMs: Long): String {
        val diffMs = max(0L, nowMs - epochMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        if (minutes < 1L) return "Just now"
        if (minutes < 60L) return "$minutes min ago"
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
        if (hours < 24L) return "$hours hr ago"
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        if (days < 7L) return "$days d ago"
        return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(epochMs))
    }
}

object ZhHansStrings : AppStrings {
    override val locale: Locale = Locale.SIMPLIFIED_CHINESE

    override val languageTitle = "语言"
    override val languageFollowSystem = "跟随系统"
    override val languageEnglish = "English"
    override val languageSimplifiedChinese = "简体中文"

    override val ok = "确定"
    override val cancel = "取消"
    override val save = "保存"
    override val done = "完成"
    override val edit = "编辑"
    override val close = "关闭"
    override val delete = "删除"
    override val rename = "重命名"
    override val renameEllipsis = "重命名…"
    override val create = "创建"
    override val add = "添加"
    override val remove = "移除"
    override val back = "返回"
    override val play = "播放"
    override val pause = "暂停"
    override val shuffle = "随机播放"
    override val previous = "上一首"
    override val next = "下一首"
    override val repeat = "循环模式"
    override val playing = "正在播放"
    override val paused = "已暂停"
    override val reorder = "调整顺序"
    override val moveUp = "上移"
    override val moveDown = "下移"
    override val unknownArtist = "未知艺术家"
    override val unknownAlbum = "未知专辑"
    override val musicoFolderName = "Musico 文件夹"
    override val nameLabel = "名称"

    override val tabFolders = "文件夹"
    override val tabLibrary = "音乐库"
    override val tabQueues = "队列"
    override val tabStats = "统计"
    override val lyrics = "歌词"
    override val queue = "队列"
    override val closePanel = "关闭面板"
    override val playbackProblem = "播放出错"
    override val thisSong = "此歌曲"
    override val playbackErrorGeneric = "播放错误"

    override val libraryTitle = "音乐库"
    override val sectionSongs = "歌曲"
    override val sectionAlbums = "专辑"
    override val sectionArtists = "艺术家"
    override val sectionPlaylists = "歌单"

    override val foldersTitle = "文件夹"
    override val folderFallbackTitle = "文件夹"
    override val checkForUpdates = "检查更新"
    override val addCloudFolder = "添加云端文件夹"
    override val rescan = "重新扫描"
    override val sortFolder = "文件夹排序"
    override val folderOptions = "文件夹选项"
    override val moreOptions = "更多选项"
    override val saveToLocalFolderEllipsis = "保存到本地文件夹…"
    override val updateFromCloud = "从云端更新"
    override val removeFolder = "移除文件夹"
    override val locateAgainEllipsis = "重新定位…"
    override val addFolder = "添加文件夹"
    override val couldNotAddFolderTitle = "无法添加文件夹"
    override val couldNotAddFolderFallback = "无法添加该文件夹。"
    override val couldNotRelocateFolderFallback = "无法重新定位该文件夹。"
    override val errorDuplicateRoot = "该文件夹已在你的音乐库中。"
    override val errorNotAFolder = "无法将该位置作为文件夹打开。"
    override val errorUnsupportedCloudUrl = "请输入有效的 YouTube、Bilibili 或 Google Drive 链接。"
    override val removeLocalFolderConfirmMessage = "其中的歌曲将从音乐库、歌单和队列中移除，但本地文件本身不会被删除。"
    override val removeCloudFolderConfirmMessage = "其中的歌曲将从音乐库、歌单和队列中移除。"
    override val cloudUrlLabel = "YouTube、Bilibili 或 Google Drive 链接"
    override val cloudUrlPlaceholder = "歌单、视频或 Google Drive 分享链接"
    override val cloudUrlSupportingText = "支持 YouTube / YT Music 歌单与视频、Bilibili 收藏夹/合集/系列与 BV / b23.tv 链接，以及 Google Drive 共享文件夹或音频文件。"
    override val cookieOptionalLabel = "Cookie（可选）"
    override val cookiePlaceholder = "仅私密歌单需要填写"
    override val cloudPlaylistLoadErrorFallback = "无法加载该云端歌单。"
    override val addingEllipsis = "正在添加…"
    override val cloudPlaylistDefaultName = "云端歌单"
    override val noFolderSelected = "未选择文件夹"
    override val savingPlaylistTitle = "正在保存歌单"
    override val saveCompleteTitle = "保存完成"
    override val saveToLocalFolderTitle = "保存到本地文件夹"
    override val folderNameLabel = "文件夹名称"
    override val locationLabel = "保存位置"
    override val chooseFolder = "选择文件夹"
    override val addSavedFolderToLibrary = "将保存的文件夹添加到音乐库"
    override val addedSavedFolderWithCloudTracking = "已将保存的文件夹添加到音乐库并启用云端同步追踪。"
    override val couldNotAddFolderToLibraryFallback = "无法将该文件夹添加到音乐库。"
    override val couldNotSavePlaylistFallback = "无法保存歌单。"
    override val savedFolderLocationUnavailable = "已保存的文件夹位置不可用。"
    override val couldNotUpdateFromCloudFallback = "无法从云端更新。"
    override val updateCompleteTitle = "更新完成"
    override val fetchingLatestCloudPlaylist = "正在获取最新云端歌单…"
    override val lookingForMusic = "正在查找音乐…"
    override val addYourMusicFoldersTitle = "添加你的音乐文件夹"
    override val addYourMusicFoldersDesc = "选择设备上的本地文件夹、添加 YouTube、Bilibili 或 Google Drive 链接，或将歌曲复制到 Musico 文件夹后下拉重新扫描。"
    override val foldersFooterHint = "添加本地文件夹、关联 YouTube、Bilibili 或 Google Drive 文件夹，或将音乐复制到 Musico 文件夹后下拉重新扫描。"
    override val badgeYouTube = "YouTube"
    override val badgeBilibili = "Bilibili"
    override val badgeGoogleDrive = "Google Drive"
    override val badgeYouTubeLocal = "YouTube · 本地"
    override val badgeBilibiliLocal = "Bilibili · 本地"
    override val badgeGoogleDriveLocal = "Google Drive · 本地"
    override val badgeCloudLocal = "云端 · 本地"
    override val folderUnavailable = "不可用 — 文件夹已移动或断开连接"
    override val folderPermissionLost = "文件夹权限已失效或位置已移动。"
    override val relocate = "重新定位"
    override val noMusicHereTitle = "暂无音乐"
    override val noMusicHereDesc = "添加到此文件夹的歌曲将在重新扫描后显示。"
    override val runInBackground = "后台运行"
    override val downloadsTitle = "下载管理"
    override val clearFinished = "清除已完成"
    override val dismiss = "移除"
    override val noDownloadsTitle = "暂无下载任务"
    override val noDownloadsDesc = "保存云端歌单、下载单曲或更新追踪文件夹时，任务会显示在这里。"
    override val downloadStatusCancelled = "已取消"
    override val downloadStatusFailed = "失败"
    override val downloadStatusCompleted = "已完成"
    override val downloadKindPlaylistExport = "保存歌单"
    override val downloadKindTrackExport = "保存歌曲"
    override val downloadKindFolderUpdate = "更新文件夹"
    override val editCloudSourceEllipsis = "编辑云端来源…"
    override val editCloudSourceTitle = "编辑云端来源"
    override val cloudSourceUpdated = "云端来源已更新。"
    override val switchSourceEllipsis = "切换来源…"
    override val switchSourceAction = "切换来源"
    override val audioQualityLabel = "音质"
    override val streamingQualityLabel = "在线播放音质"
    override val downloadQualityLabel = "下载音质"
    override val currentTrackOnlyHint = "仅对当前播放歌曲生效"
    override val qualityLosslessBadge = "无损"
    override val providerAutoMatchUrl = "自动（匹配链接）"
    override fun providerMissingLabel(name: String) = "$name（未安装）"
    override fun providerDisabledLabel(name: String) = "$name（已停用）"
    override fun extensionRecoveryBanner(providerId: String, bestProviderName: String) =
        "扩展“$providerId”不可用，可切换至 $bestProviderName 以恢复播放。"

    override val extensionsTitle = "扩展管理"
    override val importFromFile = "导入文件"
    override val importFromUrl = "从链接导入"
    override val importExtensionUrlTitle = "从链接导入扩展"
    override val extensionUrlLabel = "扩展 HTTPS 链接"
    override val extensionUrlPlaceholder = "输入 .musicoext、.musico.js 或 .zip 下载链接"
    override val extensionUrlSupportingText = "支持通过 HTTPS 导入 .musicoext 扩展包、.musico.js 单文件脚本或多扩展 .zip 合集。"
    override val noExtensionsTitle = "尚未安装扩展"
    override val noExtensionsDesc = "导入 .musicoext 扩展包、.musico.js 脚本或多扩展 .zip 合集即可添加自定义云端音乐来源。"
    override val extensionImportSuccess = "扩展已安装并启用。"
    override val extensionImportFailedTitle = "无法导入扩展"
    override val extensionImportFailedFallback = "无法加载所选扩展。"
    override val permissionReviewTitle = "审查扩展权限"
    override val permissionReviewWarning = "请仅安装来自可信来源的扩展。扩展的网络请求将被严格限制在下方列出的主机范围内。"
    override val allowedHostsLabel = "允许访问的主机"
    override val capabilitiesLabel = "声明的能力"
    override val installAndEnable = "安装并启用"
    override fun batchPermissionReviewTitle(count: Int) = "审查导入的扩展（$count）"
    override val batchPermissionReviewSubtitle = "勾选要安装并启用的扩展。各扩展的网络请求将被严格限制在各自列出的主机范围内。"
    override fun installAndEnableCount(count: Int) = "安装并启用（$count）"
    override val extensionSettingsTitle = "扩展设置"
    override val resetDefaults = "恢复默认设置"
    override val extensionLogsTitle = "扩展日志"
    override val clearLogs = "清空"
    override val noLogsYet = "暂无日志记录。"
    override val uninstall = "卸载"
    override val uninstallExtensionMessage = "该扩展将被移除。使用此扩展的云端文件夹及曲目将保留在音乐库中，但在重新安装前不可播放。"
    override val permissionsLabel = "权限"
    override val settingsLabel = "设置"
    override val logsLabel = "日志"
    override val enabledLabel = "已启用"
    override val disabledLabel = "已停用"
    override val cloudProviderLabel = "云端来源"
    override val providerAutoDetect = "自动识别"
    override val badgeExtension = "扩展"
    override val extensionDisabledBanner = "此扩展已停用。请在扩展管理中启用后再同步和播放其中的歌曲。"
    override val extensionUnavailableStatus = "扩展已停用或未安装"
    override val manageExtensions = "管理扩展"
    override val errorExtensionUnavailable = "该云端扩展已停用或未安装。"
    override val capabilityPlaylist = "歌单"
    override val capabilityTree = "目录树"
    override val capabilityStream = "音频流"
    override val capabilityCookie = "Cookie 登录态"
    override val capabilityProxy = "代理"
    override val capabilityLyrics = "歌词搜索"
    override val capabilitySearch = "云端搜索"
    override val capabilityQrLogin = "扫码登录"
    override val capabilityWebLogin = "网页登录"
    override val capabilityPlaylistDesc = "从链接加载歌单或专辑"
    override val capabilityTreeDesc = "浏览多层云端文件夹"
    override val capabilityStreamDesc = "解析可播放的音频流"
    override val capabilityCookieDesc = "使用可选 Cookie 访问受限内容"
    override val capabilityProxyDesc = "通过代理发送扩展网络请求"
    override val capabilityLyricsDesc = "在线搜索歌词"
    override val capabilitySearchDesc = "在此来源中搜索歌曲、专辑和歌单"
    override val capabilityQrLoginDesc = "在应用内扫码登录账号"
    override val capabilityWebLoginDesc = "通过应用内浏览器登录并自动保存会话 Cookie"
    override val extensionCookieLabel = "扩展 Cookie"
    override val extensionCookiePlaceholder = "粘贴 Cookie 请求头（可选）"
    override val extensionCookieSupportingText = "仅保存在本地，并仅发送给此扩展用于搜索、预览和播放。"
    override val extensionServerUrlHint = "请输入 http:// 或 https:// 服务器地址，其主机将被允许供此扩展访问。"
    override fun extensionServerUrlAllowedHost(host: String) = "允许网络访问：$host"
    override val showCookie = "显示 Cookie"
    override val hideCookie = "隐藏 Cookie"
    override val clearCookie = "清除 Cookie"
    override val qrLoginAction = "扫码登录"
    override val signInWithQrCode = "扫码登录…"
    override val webLoginAction = "网页登录"
    override val signInInBrowser = "网页登录…"
    override fun webLoginDialogTitle(source: String) = "登录 $source"
    override val saveCookiesAction = "保存 Cookie"
    override val webLoginPrompt = "请在下方完成登录，然后点击保存 Cookie。"
    override fun webLoginHostStatus(host: String, count: Int) = "$host · 已捕获 $count 项 Cookie"
    override fun webLoginCookiesDetected(count: Int) = "已检测到登录 Cookie（$count 项），点击保存 Cookie 即可完成。"
    override val importCookiesFile = "导入 cookies.txt…"
    override val cookieImportFailedTitle = "无法导入 Cookie"
    override val cookieFileReadError = "无法将该 Cookie 文件读取为文本。"
    override val cookieFileEmptyError = "文件中未找到适用于此扩展的有效 Cookie。"
    override val googleDriveAccountSection = "Google Drive 账号"
    override val accountStatusLabel = "登录状态"
    override val googleDriveConnected = "已连接"
    override val googleDriveNotSignedIn = "未登录"
    override val googleDriveClientIdLabel = "OAuth 客户端 ID"
    override val googleDriveClientSecretLabel = "OAuth 客户端密钥（可选）"
    override val signInWithGoogleAction = "使用 Google 账号登录"
    override val reconnectGoogleDriveAction = "重新连接 Google Drive"
    override val signOutGoogleDriveAction = "退出登录"
    override val googleDriveOAuthHint = "通过 OAuth 2.0 登录后可访问私人 Google Drive 文件夹并跳过下载确认页。公开分享链接无需登录仍可直接使用。"
    override val qrLoginGenerating = "正在生成二维码…"
    override val qrLoginFailed = "扫码登录失败"
    override val qrLoginExpired = "二维码已过期"
    override val qrLoginRefreshHint = "请刷新以重新生成二维码。"
    override val qrLoginSignedIn = "登录成功"
    override val qrLoginScannedConfirm = "已扫码 — 请在手机上确认"
    override val qrLoginWaiting = "等待扫描二维码…"
    override val qrLoginRefresh = "刷新二维码"
    override val autoUpdateExtensions = "自动更新扩展"
    override val autoUpdateExtensionsDesc = "启动时自动检查扩展更新（每 6 小时一次）"
    override val checkingExtensionUpdates = "正在检查扩展更新…"
    override val extensionUpToDate = "已是最新"
    override val reviewUpdateAction = "审核更新"
    override val updateAction = "更新"
    override val approveAndUpdate = "批准并更新"
    override val extensionUpdateReviewTitle = "审核扩展更新"
    override val extensionUpdateEscalationWarning =
        "此更新请求了额外权限。应用前请确认新增的功能与主机访问权限。"
    override val newCapabilitiesLabel = "新增功能权限"
    override val newAllowedHostsLabel = "新增允许的主机"
    override fun qrLoginScanPrompt(sourceName: String) = "请使用 $sourceName 客户端扫码"
    override fun qrLoginCookieSaved(extensionName: String) = "会话 Cookie 已保存到 $extensionName。"
    override fun extensionVersionTransition(fromVersion: String, toVersion: String) =
        "v$fromVersion → v$toVersion"
    override fun extensionUpdatedTo(version: String) = "已更新至 v$version"
    override fun extensionBatchUpdateSummary(
        checked: Int,
        applied: Int,
        reviewRequired: Int,
        failed: Int,
    ): String = buildString {
        if (checked == 0) {
            append("没有可检查更新的扩展。")
        } else {
            val parts = buildList {
                if (applied > 0) add("已更新 $applied 个")
                if (reviewRequired > 0) add("$reviewRequired 个待审核")
                if (failed > 0) add("$failed 个失败")
            }
            if (parts.isEmpty()) {
                append("所有扩展均为最新版本。")
            } else {
                append(parts.joinToString(" · "))
            }
        }
    }

    override val searchPlaceholder = "搜索歌曲、艺术家、专辑、流派"
    override val clearSearch = "清除搜索"
    override val sortSongs = "排序方式"
    override val resetFieldFilter = "重置字段筛选"
    override val noSongsYetTitle = "暂无歌曲"
    override val noSongsYetDesc = "在“文件夹”标签页中添加文件夹以建立你的音乐库。"
    override val noSearchResultsDesc = "请尝试搜索其他歌曲、艺术家或专辑。"
    override val sortOptionTitle = "歌曲标题"
    override val sortOptionArtist = "艺术家"
    override val sortOptionAlbum = "专辑"
    override val sortOptionRecentlyAdded = "最近添加"
    override val searchFieldKeyword = "关键词"
    override val searchFieldArtist = "艺术家"
    override val searchFieldAlbum = "专辑"
    override val searchFieldGenre = "流派"
    override val searchSourceLocal = "本地"
    override val cloudSearchKindAll = "全部"
    override val cloudSearchKindTracks = "歌曲"
    override val cloudSearchKindAlbums = "专辑"
    override val cloudSearchKindPlaylists = "歌单"
    override val cloudSearchKindAlbumBadge = "专辑"
    override val cloudSearchKindPlaylistBadge = "歌单"
    override val cloudSearchIdleDesc = "输入关键词并点击搜索以查询此扩展来源。"
    override val noCloudSearchResultsDesc = "请尝试其他关键词或切换分类。"
    override val loadMore = "加载更多"
    override val loadingMore = "正在加载更多…"
    override val cloudSearchErrorTitle = "搜索失败"
    override val cloudSearchAuthRequiredHint = "需要登录验证。请在“文件夹 → 扩展管理 → 设置”中配置此扩展的 Cookie。"
    override val cloudSearchRateLimitedHint = "请求过于频繁，请稍后再试。"
    override val cloudSearchPermissionDeniedHint = "请求被扩展的主机白名单拦截。"
    override val cloudSearchNetworkErrorHint = "连接此来源时发生网络错误。"
    override val openPreview = "打开预览"
    override val addAsCloudFolder = "添加为云端文件夹"
    override val addedToFolders = "已添加到文件夹"
    override val collectionPreviewEmptyTitle = "未找到歌曲"
    override val collectionPreviewEmptyDesc = "该合集中没有可播放的曲目。"
    override val collectionPreviewLoadFailedTitle = "无法加载合集"
    override val collectionPreviewUnsupported = "该扩展不支持加载合集预览。"
    override val saveToMusicoFolder = "保存到 Musico 文件夹"
    override val chooseFolderEllipsis = "选择文件夹…"
    override val saveTrackDestinationMessage = "选择将此歌曲保存到本地的位置。保存的歌曲将直接加入本地音乐库，不启用云端同步追踪。"

    override val noAlbumsYetTitle = "暂无专辑"
    override val noAlbumsYetDesc = "音乐库中有歌曲后，专辑将显示在此处。"
    override val albumOptions = "专辑选项"
    override val noArtistsYetTitle = "暂无艺术家"
    override val noArtistsYetDesc = "音乐库中有歌曲后，艺术家将显示在此处。"
    override val artistOptions = "艺术家选项"

    override val newPlaylist = "新建歌单"
    override val favourites = "我的收藏"
    override val playlistOptions = "歌单选项"
    override val renamePlaylistTitle = "重命名歌单"
    override val deletePlaylistConfirmMessage = "该歌单将被移除，你的歌曲仍会保留在音乐库中。"
    override val playlistFallbackTitle = "歌单"
    override val nothingHereYetTitle = "暂无内容"
    override val nothingHereYetDesc = "点击歌曲、专辑或文件夹上的菜单即可添加到此处。"
    override val removeFromPlaylist = "从歌单移除"
    override val removeFromPlaylistCd = "从歌单移除"

    override val queuesTitle = "队列"
    override val clearThisQueue = "清空此队列"
    override val clearQueue = "清空队列"
    override val clearQueueConfirmMessage = "你的歌曲仍会保留在音乐库中。"
    override val newQueue = "新建队列"
    override val emptyLabel = "空"
    override val sortQueue = "队列排序"
    override val queueActions = "队列操作"
    override val renameQueueEllipsis = "重命名队列…"
    override val deleteQueue = "删除队列"
    override val queueIsEmptyTitle = "队列为空"
    override val queueIsEmptyDesc = "当前队列为空，点击音乐库中的歌曲即可开始播放。"
    override val removeFromQueue = "从队列移除"
    override val removeFromQueueCd = "从队列移除"
    override val missingSong = "歌曲缺失"
    override val renameQueueTitle = "重命名队列"
    override val deleteQueueConfirmMessage = "该队列将被删除，你的歌曲仍会保留在音乐库中。"
    override val switchQueue = "切换队列"

    override val statsTitle = "统计"
    override val statListenedLabel = "累计收听"
    override val statNeverPlayedLabel = "从未播放"
    override val statsSectionRecent = "最近"
    override val statsSectionPlays = "播放次数"
    override val statsSectionNotLately = "久未播放"
    override val statsSectionNever = "从未播放"
    override val statsEmptyRecentTitle = "暂无播放记录"
    override val statsEmptyRecentDesc = "歌曲开始播放几秒后会显示在这里。"
    override val statsEmptyPlaysTitle = "暂无播放统计"
    override val statsEmptyPlaysDesc = "收听超过歌曲时长的一半即计为一次播放。"
    override val statsEmptyNotLatelyTitle = "没有久未播放的歌曲"
    override val statsEmptyEverythingPlayedTitle = "所有歌曲都已听过"
    override val statsEmptyEverythingPlayedDesc = "音乐库中的每首歌曲都至少播放过一次。"
    override val countOrderMost = "播放次数从高到低"
    override val countOrderLeast = "播放次数从低到高"
    override val statsInsightsTitle = "听歌洞察"
    override val statsLibraryExplored = "音乐库探索"
    override val statsTopArtists = "常听艺术家"
    override val statsTopAlbums = "常听专辑"

    override val closePlayer = "关闭播放器"
    override val songInfo = "歌曲信息"
    override val addToFavourites = "添加到收藏"
    override val removeFromFavourites = "取消收藏"
    override val equalizer = "均衡器"
    override val sleepTimer = "睡眠定时"
    override val nothingPlayingTitle = "当前无播放"
    override val pickSongFromLibrary = "从音乐库中挑选一首歌曲吧。"
    override val notPlaying = "未在播放"
    override val pickSongHint = "从音乐库中选择歌曲"
    override val lyricsPanel = "歌词面板"
    override val queuePanel = "队列面板"

    override val lyricsOptions = "歌词选项"
    override val searchOnlineEllipsis = "在线搜索…"
    override val searchCloudLyricsTitle = "在线搜索歌词"
    override val importFromFileEllipsis = "从文件导入…"
    override val removeImportedLyrics = "移除已导入的歌词"
    override val noLyricsTitle = "暂无歌词"
    override val noLyricsDesc = "为这首歌导入 .lrc 或 .txt 文件，或在歌曲旁放置同名的歌词文件。"
    override val couldNotImportLyricsTitle = "无法导入歌词"
    override val lyricsImportErrorFallback = "无法将该文件读取为歌词文本。"
    override val lyricsSourceImported = "已导入"
    override val lyricsSourceSidecar = "同目录文件"
    override val lyricsSourceFolderMatch = "目录匹配"
    override val lyricsSourceEmbedded = "内嵌标签"
    override val lyricsSourceImportedDesc = "来自你导入的文件"
    override val lyricsSourceSidecarDesc = "来自歌曲旁的同目录文件"
    override val lyricsSourceFolderMatchDesc = "匹配自歌曲所在文件夹"
    override val lyricsSourceEmbeddedDesc = "来自歌曲内嵌标签"
    override val saveLrcToFolder = "保存 .lrc 到歌曲文件夹"
    override val savedLrcToFolderNotice = "已将 .lrc 保存到歌曲文件夹。"
    override val alsoSaveLrcToFolder = "同时保存 .lrc 到歌曲文件夹"
    override val useLyrics = "使用歌词"
    override val includeTranslation = "包含翻译"
    override val lyricsTitleField = "标题"
    override val lyricsArtistField = "艺术家"
    override val searchAction = "搜索"
    override val autoSourceLabel = "自动"
    override val lyricsFormatSynced = "逐行同步"
    override val lyricsFormatPlain = "纯文本"
    override val lyricsFormatInstrumental = "纯音乐"
    override val lyricsTranslatedBadge = "含翻译"
    override val noOnlineLyricsTitle = "未找到在线歌词"
    override val noOnlineLyricsDesc = "可尝试修改上方标题或艺术家，或切换歌词来源。"
    override val searchingOnline = "正在搜索…"
    override val retryAction = "重试"
    override val selectResultToPreview = "选择一条结果以预览"
    override val partialSourcesFailedHint = "部分来源暂时无法访问。"
    override val couldNotSaveLyricsTitle = "无法保存歌词"
    override val couldNotSearchLyricsTitle = "无法搜索歌词"

    override val reset = "重置"
    override val presetLabel = "预设"
    override val eqCustom = "自定义"
    override val eqPresetFlat = "原声平坦"
    override val eqPresetBassBoost = "低音增强"
    override val eqPresetTrebleBoost = "高音增强"
    override val eqPresetRock = "摇滚"
    override val eqPresetPop = "流行"
    override val eqPresetJazz = "爵士"
    override val eqPresetClassical = "古典"
    override val eqPresetVocal = "人声"

    override val stopsAtEndOfSong = "将在当前歌曲结束后停止"
    override val cancelTimer = "取消定时"
    override val stopPlayingAfter = "定时停止播放"
    override val decreaseMinutes = "减少分钟数"
    override val increaseMinutes = "增加分钟数"
    override val start = "开始"
    override val endOfCurrentSong = "当前歌曲播放完毕后"

    override val changeCover = "更换封面"
    override val couldNotLoadSelectedImage = "无法加载所选图片。"
    override val titleLabel = "标题"
    override val artistLabel = "艺术家"
    override val albumLabel = "专辑"
    override val genreLabel = "流派"
    override val addGenrePlaceholder = "添加流派"
    override val saveChanges = "保存更改"
    override val quickSearch = "快速搜索"
    override val fileAndPlaybackInfo = "文件与播放信息"
    override val infoPrecision = "采样精度"
    override val infoFrequency = "采样率"
    override val infoBitRate = "比特率"
    override val infoFileSize = "文件大小"
    override val infoLength = "时长"
    override val infoSoundTracks = "声道数"
    override val infoTimeAdded = "添加时间"
    override val infoTimeEdited = "修改时间"
    override val infoLastPlayed = "最近播放"
    override val infoPlayedTimes = "播放次数"
    override val infoFileName = "文件名"
    override val infoFileLocation = "文件位置"
    override val notAvailable = "暂无"
    override val neverLabel = "从未"
    override val channelsMono = "1（单声道）"
    override val channelsStereo = "2（立体声）"

    override val sortCustom = "自定义"
    override val sortNameAsc = "名称 (A–Z)"
    override val sortNameDesc = "名称 (Z–A)"
    override val sortDateModified = "修改日期"
    override val sortMostPlayed = "播放最多"
    override val sortLeastPlayed = "播放最少"

    override val playNext = "下一首播放"
    override val addToQueue = "添加到队列"
    override val addToAnotherQueueEllipsis = "添加到其他队列…"
    override val playInNewQueue = "在新队列中播放"
    override val addToPlaylistEllipsis = "添加到歌单…"
    override val newPlaylistEllipsis = "新建歌单…"
    override val enterPlaylistNameHint = "请输入歌单名称。"
    override val trackOptions = "歌曲选项"
    override val savingSongTitle = "正在保存歌曲"
    override val saveFailedTitle = "保存失败"
    override val songSavedTitle = "歌曲已保存"
    override val couldNotSaveSongFallback = "无法保存歌曲。"
    override val unknownError = "未知错误"
    override val libraryUpdated = "音乐库已更新。"

    override val checkingForUpdates = "正在检查更新"
    override val youreUpToDate = "已是最新版本"
    override val updateAvailable = "发现新版本"
    override val downloadingUpdate = "正在下载更新"
    override val readyToInstall = "准备安装"
    override val updateError = "更新出错"
    override val checkingGithubReleases = "正在检查 GitHub Releases…"
    override val openReleasePage = "打开发布页面"
    override val packageVerifiedReady = "安装包已校验完成，可以安装。"
    override val downloadAndInstall = "下载并安装"
    override val install = "安装"
    override val checkAgain = "重新检查"
    override val retry = "重试"
    override val skipVersion = "跳过此版本"
    override val later = "稍后"
    override val hide = "隐藏"
    override val noReleaseNotes = "未提供更新说明。"

    override fun songCount(count: Int) = "$count 首歌曲"
    override fun albumCount(count: Int) = "$count 张专辑"
    override fun libraryExploredSummary(percentText: String, played: Int, total: Int) =
        "$percentText · $played / $total 首歌曲"
    override fun restoreRemovedSubfolders(count: Int) = "恢复已移除的子文件夹 ($count)"
    override fun removeFolderConfirmTitle(name: String) = "确定从音乐库中移除“$name”吗？"
    override fun savingProgressWithTitle(done: Int, total: Int, title: String) =
        "正在保存第 $done/$total 首：$title"
    override fun savingProgress(done: Int, total: Int) = "正在保存第 $done/$total 首…"
    override fun savePlaylistSummary(saved: Int, total: Int, failed: Int) = buildString {
        append("已保存 $saved/$total 首歌曲。")
        if (failed > 0) append(" $failed 首失败。")
    }
    override fun andMoreErrors(count: Int) = "…还有 $count 个错误。"
    override fun updatingFolderTitle(name: String) = "正在更新“$name”"
    override fun checkingProgressWithTitle(done: Int, total: Int, title: String) =
        "正在检查第 $done/$total 首：$title"
    override fun checkingProgress(done: Int, total: Int) = "正在检查第 $done/$total 首…"
    override fun updateAddedSongsSummary(added: Int, skipped: Int) =
        "已新增 $added 首歌曲（$skipped 首已存在）。"
    override fun alreadyUpToDateSummary(skipped: Int) =
        "已是最新（共 $skipped 首歌曲）。"
    override fun failedCountLine(failed: Int) = "$failed 首失败。"
    override fun readingScanProgress(done: Int, total: Int) = "正在读取第 $done/$total 首歌曲…"
    override fun noSearchResultsTitle(query: String) = "未找到“$query”的结果"
    override fun discPrefix(discNumber: Int) = "第 $discNumber 碟 · "
    override fun deleteItemConfirmTitle(name: String) = "确定删除“$name”吗？"
    override fun clearQueueConfirmTitle(name: String) = "确定清空“$name”中的所有歌曲吗？"
    override fun queueSummary(songCount: Int, trackTitle: String, timeStr: String) =
        "${songCount(songCount)} · $trackTitle（$timeStr）"
    override fun statPlaysLabel(count: Int) = "次播放"
    override fun songPlaysCount(count: Int) = "$count 次播放"
    override fun statsEmptyNotLatelyDesc(staleDays: Int) =
        "超过 $staleDays 天未收听的歌曲会显示在这里。"
    override fun queuePosition(indexOneBased: Int, total: Int) = "第 $indexOneBased/$total 首"
    override fun bandAccessibility(bandLabel: String, gainText: String) =
        "$bandLabel 赫兹，$gainText 分贝"
    override fun preampLabel(gainText: String) = "前级增益 $gainText dB"
    override fun stopsIn(timeStr: String) = "将在 $timeStr 后停止"
    override fun minutesCount(minutes: Int) = "$minutes 分钟"
    override fun customMinutes(minutes: Int) = "自定义：$minutes 分钟"
    override fun bitDepthFormat(bits: Int) = "$bits 位"
    override fun channelsCount(count: Int) = "$count 声道"
    override fun downloadingTrack(title: String) = "正在下载 $title…"
    override fun savedAsFile(fileName: String) = "已保存为“$fileName”。"
    override fun currentVersionLabel(version: String) = "当前版本：$version"
    override fun musicoIsLatest(version: String) = "Musico $version 已是最新版本。"
    override fun downloadingAssetProgress(name: String, percent: Int) =
        "正在下载 $name ($percent%)…"
    override fun downloadingAsset(name: String) = "正在下载 $name…"
    override fun playbackStoppedConsecutive(count: Int) =
        "播放已停止：连续 $count 首歌曲无法播放。"
    override fun playbackFailedTrack(title: String) = "无法播放“$title”。"
    override fun cloudSyncProgress(completedFolders: Int, totalFolders: Int, tracksFound: Int) =
        "正在同步文件夹 ($completedFolders/$totalFolders · ${songCount(tracksFound)})…"
    override fun queueCountLabel(count: Int) = "$count 个队列"
    override fun extensionVersionAuthor(version: String, author: String?): String =
        if (author.isNullOrBlank()) "v$version" else "v$version · $author"
    override fun extensionMissingBanner(providerId: String): String =
        "未安装扩展“$providerId”。请在扩展管理中重新安装以恢复播放。"
    override fun badgeCustomLocal(badge: String): String = "$badge · 本地"
    override fun uninstallExtensionConfirmTitle(name: String): String = "确定卸载“$name”吗？"
    override fun detectedProviderHint(name: String): String = "已识别来源：$name"
    override fun extensionLogsCountLabel(count: Int): String = "日志 ($count)"
    override fun cloudSearchPlaceholder(sourceName: String): String = "搜索 $sourceName"
    override fun cloudSearchIdleTitle(sourceName: String): String = "搜索 $sourceName"
    override fun addedFolderNotice(name: String): String = "已将“$name”添加到文件夹。"

    override val appearanceSection = "外观"
    override val generalSection = "通用"
    override val librarySection = "音乐库"
    override val aboutAndUpdatesSection = "关于与更新"
    override val themeModeLabel = "主题模式"
    override val themeModeSystem = "跟随系统"
    override val themeModeLight = "浅色"
    override val themeModeDark = "深色"
    override val backgroundColorSetLabel = "背景颜色"
    override val bgPresetDefault = "默认"
    override val bgPresetObsidian = "曜石黑"
    override val bgPresetMidnight = "午夜蓝"
    override val bgPresetWarmCharcoal = "暖炭灰"
    override val bgPresetForestNight = "森夜绿"
    override val bgPresetPaper = "纸白"
    override val bgPresetMist = "薄雾蓝"
    override val bgPresetBlossom = "樱粉"
    override val presetCustom = "自定义"
    override val foregroundAndAccentLabel = "前景与强调色"
    override val fgPresetCoral = "珊瑚红"
    override val fgPresetOcean = "海洋蓝"
    override val fgPresetEmerald = "翡翠绿"
    override val fgPresetAmethyst = "紫水晶"
    override val fgPresetAmber = "琥珀黄"
    override val fgPresetRose = "玫瑰粉"
    override val fgPresetMonochrome = "单色灰"
    override val customBackgroundColorTitle = "自定义背景颜色"
    override val customAccentColorTitle = "自定义强调色"
    override val customTextColorLabel = "文字颜色"
    override val customTextColorAuto = "自动（自适应对比度）"
    override val customTextColorTitle = "自定义文字颜色"
    override val useAutoTextColor = "恢复自动"
    override val hexColorLabel = "十六进制颜色 (#RRGGBB)"
    override val invalidHexColorError = "请输入有效的 6 位十六进制颜色，例如 #E85440"
    override val colorRedLabel = "红色"
    override val colorGreenLabel = "绿色"
    override val colorBlueLabel = "蓝色"
    override val colorPreviewSample = "示例文本"
    override val backgroundImageLabel = "背景图片"
    override val chooseImageAction = "选择图片"
    override val changeImageAction = "更换图片"
    override val imageVisibilityLabel = "图片可见度"
    override val resetAppearanceToDefault = "恢复默认外观"
    override val rescanLibraryAction = "重新扫描音乐库"
    override val rescanningLibrary = "正在扫描音乐库…"
    override val appVersionLabel = "版本"

    override val removeOrDeleteEllipsis = "移除或删除…"
    override val removeFromLibrary = "从音乐库移除"
    override val deleteFromDevice = "从设备删除"
    override val deleteSongDialogTitle = "移除或删除歌曲？"
    override val deleteFolderDialogTitle = "移除或删除文件夹？"
    override val deleteLocalTrackMessage = "“从音乐库移除”仅将其从 Musico 中隐藏，不会删除文件；“从设备删除”将永久删除该音频文件。"
    override val deleteLocalFolderMessage = "“从音乐库移除”仅将此文件夹及其歌曲从 Musico 中隐藏，不会删除文件；“从设备删除”将永久删除该文件夹及其全部内容。"
    override val deleteFailedTitle = "删除失败"
    override val deleteFailedFallback = "无法从设备删除。"
    override val removeSongConfirmMessage = "该歌曲将从你的音乐库、歌单和队列中移除。"
    override val undo = "撤销"
    override val addedToQueueNotice = "已添加到队列"
    override val playingNextNotice = "已设为下一首播放"
    override val removedFromLibraryNotice = "已从音乐库移除"
    override val deletedFromDeviceNotice = "已从设备删除"
    override val clearedQueueNotice = "已清空队列"
    override val removedFromPlaylistNotice = "已从歌单移除"
    override val appearancePreviewLabel = "效果预览"
    override val previewTrackTitle = "午夜遐想"
    override val previewTrackArtist = "Musico 工作室"
    override val previewSampleButton = "播放"
    override val imageBlurLabel = "背景模糊"
    override val lowContrastWarningText = "文字对比度偏低，可能影响阅读清晰度。"
    override val lowContrastWarningAccent = "强调色与当前背景对比度偏低。"
    override val lowContrastWarningBoth = "文字与强调色在当前背景下的对比度偏低。"
    override val autoFixContrastAction = "自动修复对比度"
    override val syncToCurrentLine = "同步到当前行"
    override val lyricsFontSizeLabel = "字体大小"
    override val lyricsFontSizeSmall = "小"
    override val lyricsFontSizeMedium = "中"
    override val lyricsFontSizeLarge = "大"
    override val lyricsAlignmentLabel = "对齐方式"
    override val lyricsAlignmentLeft = "左对齐"
    override val lyricsAlignmentCenter = "居中"

    private val defaultQueueRegex = Regex("^Queue (\\d+)$")

    override fun displayQueueName(name: String): String {
        val match = defaultQueueRegex.matchEntire(name) ?: return name
        return "队列 ${match.groupValues[1]}"
    }

    override fun formatListeningTime(totalSeconds: Double): String {
        if (!totalSeconds.isFinite() || totalSeconds <= 0.0) return "0 分钟"
        val minutes = (totalSeconds / 60.0).toLong()
        return if (minutes >= 60) {
            "${minutes / 60} 小时 ${minutes % 60} 分钟"
        } else {
            "$minutes 分钟"
        }
    }

    override fun formatRelativeTime(epochMs: Long, nowMs: Long): String {
        val diffMs = max(0L, nowMs - epochMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        if (minutes < 1L) return "刚刚"
        if (minutes < 60L) return "$minutes 分钟前"
        val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
        if (hours < 24L) return "$hours 小时前"
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        if (days < 7L) return "$days 天前"
        return DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(epochMs))
    }
}

fun stringsFor(resolved: ResolvedLanguage): AppStrings = when (resolved) {
    ResolvedLanguage.EN -> EnStrings
    ResolvedLanguage.ZH_HANS -> ZhHansStrings
}

val LocalStrings = staticCompositionLocalOf<AppStrings> { EnStrings }
