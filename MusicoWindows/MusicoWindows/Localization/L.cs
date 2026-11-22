using System;
using System.Collections.Generic;
using System.Globalization;
using MusicoWindows.Models;

namespace MusicoWindows.Localization;

/// <summary>
/// Central string table and formatting helpers for English and Simplified Chinese UI localization.
/// </summary>
public static class L
{
    public static ResolvedLanguage CurrentLanguage { get; private set; } = ResolvedLanguage.English;

    public static void SetLanguage(ResolvedLanguage language) => CurrentLanguage = language;

    public static IReadOnlyDictionary<string, string> English => En;
    public static IReadOnlyDictionary<string, string> SimplifiedChinese => ZhHans;

    public static string Get(string key)
    {
        var dict = CurrentLanguage == ResolvedLanguage.SimplifiedChinese ? ZhHans : En;
        if (dict.TryGetValue(key, out string? value))
            return value;
        if (En.TryGetValue(key, out string? fallback))
            return fallback;
        return key;
    }

    public static string Format(string key, params object[] args) =>
        string.Format(CultureInfo.CurrentCulture, Get(key), args);

    public static string SongsCount(int count) =>
        CurrentLanguage == ResolvedLanguage.SimplifiedChinese
            ? $"{count} 首歌曲"
            : (count == 1 ? "1 song" : $"{count} songs");

    public static string AlbumsCount(int count) =>
        CurrentLanguage == ResolvedLanguage.SimplifiedChinese
            ? $"{count} 张专辑"
            : (count == 1 ? "1 album" : $"{count} albums");

    public static string ArtistsCount(int count) =>
        CurrentLanguage == ResolvedLanguage.SimplifiedChinese
            ? $"{count} 位艺术家"
            : (count == 1 ? "1 artist" : $"{count} artists");

    public static string PlaylistsCount(int count) =>
        CurrentLanguage == ResolvedLanguage.SimplifiedChinese
            ? $"{count} 个播放列表"
            : (count == 1 ? "1 playlist" : $"{count} playlists");

    public static string PlaysCount(int count) =>
        CurrentLanguage == ResolvedLanguage.SimplifiedChinese
            ? $"{count} 次播放"
            : (count == 1 ? "1 play" : $"{count} plays");

    public static string SearchFieldLabel(SearchField field) => field switch
    {
        SearchField.Artist => Get("SearchField_Artist"),
        SearchField.Album => Get("SearchField_Album"),
        SearchField.Genre => Get("SearchField_Genre"),
        _ => Get("SearchField_Keyword")
    };

    public static string EqPresetName(string? canonicalName) => canonicalName switch
    {
        "Flat" => Get("EqPreset_Flat"),
        "Bass Boost" => Get("EqPreset_BassBoost"),
        "Treble Boost" => Get("EqPreset_TrebleBoost"),
        "Rock" => Get("EqPreset_Rock"),
        "Pop" => Get("EqPreset_Pop"),
        "Jazz" => Get("EqPreset_Jazz"),
        "Classical" => Get("EqPreset_Classical"),
        "Vocal" => Get("EqPreset_Vocal"),
        _ => Get("Equalizer_PresetCustom")
    };

    public static string DisplayArtist(string artist) =>
        string.Equals(artist, "Unknown Artist", StringComparison.Ordinal)
            ? Get("Common_UnknownArtist")
            : artist;

    public static string DisplayAlbum(string album) =>
        string.Equals(album, "Unknown Album", StringComparison.Ordinal)
            ? Get("Common_UnknownAlbum")
            : album;

    public static string DisplayRootName(LibraryRoot root) =>
        root.Kind == RootKind.AppFolder && string.Equals(root.Name, "Music Folder", StringComparison.Ordinal)
            ? Get("Folders_AppFolderName")
            : root.Name;

    public static string FormatListeningDuration(double totalSeconds)
    {
        int minutes = (int)(Math.Max(0, totalSeconds) / 60);
        return minutes >= 60
            ? Format("Stats_DurationHoursMinutes", minutes / 60, minutes % 60)
            : Format("Stats_DurationMinutes", minutes);
    }

    public static string FormatRelativeTime(DateTimeOffset utcTime)
    {
        var elapsed = DateTimeOffset.UtcNow - utcTime;
        if (elapsed.TotalSeconds < 60) return Get("Relative_JustNow");
        if (elapsed.TotalMinutes < 60)
        {
            int m = (int)elapsed.TotalMinutes;
            return m == 1 ? Get("Relative_OneMinuteAgo") : Format("Relative_MinutesAgo", m);
        }
        if (elapsed.TotalHours < 24)
        {
            int h = (int)elapsed.TotalHours;
            return h == 1 ? Get("Relative_OneHourAgo") : Format("Relative_HoursAgo", h);
        }
        if (elapsed.TotalDays < 30)
        {
            int d = (int)elapsed.TotalDays;
            return d == 1 ? Get("Relative_OneDayAgo") : Format("Relative_DaysAgo", d);
        }
        return CurrentLanguage == ResolvedLanguage.SimplifiedChinese
            ? utcTime.ToLocalTime().ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)
            : utcTime.ToLocalTime().ToString("MMM d, yyyy", CultureInfo.InvariantCulture);
    }

    private static readonly Dictionary<string, string> En = new(StringComparer.Ordinal)
    {
        // Language switcher
        ["Lang_FollowSystem"] = "Follow System",
        ["Lang_English"] = "English",
        ["Lang_SimplifiedChinese"] = "简体中文",

        // Window & Navigation
        ["Window_Minimize"] = "Minimize",
        ["Window_Maximize"] = "Maximize",
        ["Window_Restore"] = "Restore",
        ["Window_Close"] = "Close",
        ["Nav_TogglePane"] = "Toggle navigation pane",
        ["Nav_Folders"] = "Folders",
        ["Nav_HeaderLibrary"] = "LIBRARY",
        ["Nav_Songs"] = "Songs",
        ["Nav_Albums"] = "Albums",
        ["Nav_Artists"] = "Artists",
        ["Nav_HeaderPlaylists"] = "PLAYLISTS",
        ["Nav_AllPlaylists"] = "All Playlists",
        ["Nav_Favourites"] = "Favourites",
        ["Nav_NewPlaylist"] = "New Playlist",
        ["Nav_Queues"] = "Queues",
        ["Nav_Stats"] = "Stats",
        ["Nav_LightTheme"] = "Light Theme",
        ["Nav_DarkTheme"] = "Dark Theme",
        ["Nav_ThemeTooltip"] = "Switch Light / Dark theme",
        ["Nav_Language"] = "Language",
        ["Nav_LanguageTooltip"] = "Switch language",
        ["Nav_Rescan"] = "Rescan Library",
        ["Nav_RescanTooltip"] = "Rescan library folders",
        ["Nav_CheckUpdates"] = "Check for Updates",

        // Common actions & labels
        ["Common_OK"] = "OK",
        ["Common_Cancel"] = "Cancel",
        ["Common_Cancelling"] = "Cancelling...",
        ["Common_Close"] = "Close",
        ["Common_Confirm"] = "Confirm",
        ["Common_Save"] = "Save",
        ["Common_Done"] = "Done",
        ["Common_Back"] = "Back",
        ["Common_Create"] = "Create",
        ["Common_Rename"] = "Rename",
        ["Common_RenameEllipsis"] = "Rename...",
        ["Common_Delete"] = "Delete",
        ["Common_Play"] = "Play",
        ["Common_PlayAll"] = "Play All",
        ["Common_Shuffle"] = "Shuffle",
        ["Common_MoreOptions"] = "More options",
        ["Common_MoveUp"] = "Move Up",
        ["Common_MoveDown"] = "Move Down",
        ["Common_Browse"] = "Browse...",
        ["Common_UnknownArtist"] = "Unknown Artist",
        ["Common_UnknownAlbum"] = "Unknown Album",

        // Sort modes
        ["Sort_Custom"] = "Custom",
        ["Sort_NameAsc"] = "Name (A-Z)",
        ["Sort_NameDesc"] = "Name (Z-A)",
        ["Sort_DateModified"] = "Date Modified",
        ["Sort_MostPlayed"] = "Most Played",
        ["Sort_LeastPlayed"] = "Least Played",

        // Search fields
        ["SearchField_Keyword"] = "Keyword",
        ["SearchField_Artist"] = "Artist",
        ["SearchField_Album"] = "Album",
        ["SearchField_Genre"] = "Genre",

        // Folders
        ["Folders_Title"] = "Folders",
        ["Folders_AppFolderName"] = "Music Folder",
        ["Folders_Subtitle"] = "Your Music folder and added music directories",
        ["Folders_RootsSubtitle"] = "Your Music folder is included by default. Add any folder with music, or remove one you don't want.",
        ["Folders_Rescan"] = "Rescan",
        ["Folders_AddCloudFolder"] = "Add Cloud Folder",
        ["Folders_AddFolder"] = "Add Folder",
        ["Folders_NewFolder"] = "New Folder",
        ["Folders_NewFolderPrompt"] = "Enter a name for the new folder.",
        ["Folders_SaveToLocal"] = "Save to Local Folder...",
        ["Folders_SaveCloudTooltip"] = "Save Cloud Playlist to Local Folder",
        ["Folders_UpdateFromCloud"] = "Update from Cloud",
        ["Folders_RemoveFolder"] = "Remove Folder",
        ["Folders_RemoveFromLibraryTooltip"] = "Remove Folder from Library",
        ["Folders_ScanningDefault"] = "Looking for music...",
        ["Folders_ScanningProgress"] = "Reading {0} of {1} songs...",
        ["Folders_CloudLocalBadge"] = "{0} · Local",
        ["Folders_Unavailable"] = "Unavailable — folder moved or disconnected",
        ["Folders_LocateAgain"] = "Locate Again...",
        ["Folders_RestoreRemovedSubfolders"] = "Restore Removed Subfolders ({0})",
        ["Folders_RestoreMusicFolder"] = "Restore Music Folder",
        ["Folders_EmptyTitle"] = "No Music Here",
        ["Folders_EmptyDesc"] = "Songs you add to this folder appear after a rescan.",
        ["Folders_SelectMusicFolderTitle"] = "Select Music Folder",
        ["Folders_LocateFolderTitle"] = "Locate Music Folder",
        ["Folders_CouldNotAddTitle"] = "Couldn't Add Folder",
        ["Folders_CouldNotLocateTitle"] = "Couldn't Locate Folder",
        ["Folders_CouldNotCreateTitle"] = "Couldn't Create Folder",
        ["Folders_RemoveConfirmTitle"] = "Remove \"{0}\" from your library?",
        ["Folders_RemoveConfirmCloudMessage"] = "Its songs leave your library, playlists and queues.",
        ["Folders_RemoveConfirmLocalMessage"] = "Its songs leave your library, playlists and queues. The files themselves are not deleted.",
        ["Folders_RemoveConfirmAction"] = "Remove from Library",

        // Songs
        ["Songs_Title"] = "Songs",
        ["Songs_SearchPlaceholder"] = "Search songs, artists, albums (Ctrl+F)",
        ["Songs_ClearFieldFilter"] = "Clear field filter",
        ["Songs_SortTitle"] = "Title",
        ["Songs_SortArtist"] = "Artist",
        ["Songs_SortAlbum"] = "Album",
        ["Songs_SortRecentlyAdded"] = "Recently Added",
        ["Songs_EmptyTitle"] = "No Songs Yet",
        ["Songs_EmptyDesc"] = "Add a folder in Folders to build your music library.",
        ["Songs_NoSearchResultsTitle"] = "No Results for \"{0}\"",
        ["Songs_NoSearchResultsDesc"] = "Try searching for another song, artist, or album.",

        // Albums & Artists
        ["Albums_Title"] = "Albums",
        ["Albums_EmptyTitle"] = "No Albums Yet",
        ["Albums_EmptyDesc"] = "Albums appear once your library has songs.",
        ["Artists_Title"] = "Artists",
        ["Artists_EmptyTitle"] = "No Artists Yet",
        ["Artists_EmptyDesc"] = "Artists appear once your library has songs.",

        // Playlists & Playlist Detail
        ["Playlists_Title"] = "Playlists",
        ["Playlists_RenameTooltip"] = "Rename Playlist",
        ["Playlists_DeleteTooltip"] = "Delete Playlist",
        ["Playlists_RenameTitle"] = "Rename Playlist",
        ["Playlists_RenameMessage"] = "Enter a new name for this playlist.",
        ["Playlists_DeleteConfirmTitle"] = "Delete \"{0}\"?",
        ["Playlists_DeleteConfirmMessage"] = "This removes the playlist. Your music files are not affected.",
        ["Playlists_NewMessage"] = "Enter a name for the playlist.",
        ["PlaylistDetail_FallbackTitle"] = "Playlist",
        ["PlaylistDetail_EmptyTitle"] = "Nothing Here Yet",
        ["PlaylistDetail_EmptyDesc"] = "Right-click any song, album, or folder to add it to this playlist.",
        ["PlaylistDetail_RemoveFromPlaylist"] = "Remove from Playlist",

        // Queues
        ["Queues_Title"] = "Queues",
        ["Queues_NewQueue"] = "New Queue",
        ["Queues_Clear"] = "Clear",
        ["Queues_ClearTooltip"] = "Clear Active Queue",
        ["Queues_PlayQueuesHeader"] = "PLAY QUEUES",
        ["Queues_SortLabel"] = "Sort:",
        ["Queues_EmptyTitle"] = "Queue is Empty",
        ["Queues_EmptyDesc"] = "Click any song in your library to start playing.",
        ["Queues_EmptyChip"] = "Empty",
        ["Queues_MissingSong"] = "Missing song",
        ["Queues_RemoveFromQueue"] = "Remove from Queue",
        ["Queues_RenameTitle"] = "Rename Queue",
        ["Queues_RenameMessage"] = "Enter a new name for this queue.",
        ["Queues_ClearConfirmTitle"] = "Clear \"{0}\"?",
        ["Queues_ClearConfirmMessage"] = "Remove every song from \"{0}\"? The queue itself will remain.",
        ["Queues_ClearConfirmAction"] = "Clear Queue",

        // Stats
        ["Stats_Title"] = "Stats",
        ["Stats_PlaysUnitSingular"] = "play",
        ["Stats_PlaysUnitPlural"] = "plays",
        ["Stats_ListenedLabel"] = "listened",
        ["Stats_NeverPlayedLabel"] = "never played",
        ["Stats_TabRecent"] = "Recent",
        ["Stats_TabPlays"] = "Plays",
        ["Stats_TabNotLately"] = "Not Lately",
        ["Stats_TabNever"] = "Never",
        ["Stats_MostPlayedFirst"] = "Most Played First",
        ["Stats_LeastPlayedFirst"] = "Least Played First",
        ["Stats_EmptyRecentTitle"] = "Nothing Played Yet",
        ["Stats_EmptyRecentDesc"] = "Songs appear here a few seconds after they start playing.",
        ["Stats_EmptyPlaysTitle"] = "No Plays Yet",
        ["Stats_EmptyPlaysDesc"] = "A song counts as played once you have listened to half of it.",
        ["Stats_EmptyStaleTitle"] = "Nothing Neglected",
        ["Stats_EmptyStaleDesc"] = "Songs you have not listened to for {0} days appear here.",
        ["Stats_EmptyNeverTitle"] = "Everything Played",
        ["Stats_EmptyNeverDesc"] = "Every song in your library has been played at least once.",
        ["Stats_DurationHoursMinutes"] = "{0} h {1} min",
        ["Stats_DurationMinutes"] = "{0} min",
        ["Relative_JustNow"] = "just now",
        ["Relative_OneMinuteAgo"] = "1 min ago",
        ["Relative_MinutesAgo"] = "{0} min ago",
        ["Relative_OneHourAgo"] = "1 hr ago",
        ["Relative_HoursAgo"] = "{0} hr ago",
        ["Relative_OneDayAgo"] = "1 day ago",
        ["Relative_DaysAgo"] = "{0} days ago",

        // PlayerBar & FullPlayerView & Errors
        ["PlayerBar_NotPlaying"] = "Not Playing",
        ["PlayerBar_PickSong"] = "Pick a song from your library",
        ["PlayerBar_NowPlayingTooltip"] = "Open Now Playing (Right-click for Song Info & options)",
        ["PlayerBar_PrevTooltip"] = "Previous (Ctrl+Left)",
        ["PlayerBar_PlayPauseTooltip"] = "Play / Pause (Space)",
        ["PlayerBar_NextTooltip"] = "Next (Ctrl+Right)",
        ["PlayerBar_RepeatTooltip"] = "Repeat",
        ["PlayerBar_MuteTooltip"] = "Mute",
        ["PlayerBar_VolumeTooltip"] = "Volume",
        ["FullPlayer_NowPlaying"] = "Now Playing",
        ["FullPlayer_NothingPlaying"] = "Nothing Playing",
        ["FullPlayer_CloseTooltip"] = "Close Now Playing (Esc)",
        ["FullPlayer_ShowLyricsTooltip"] = "Show Lyrics",
        ["FullPlayer_ToggleFavTooltip"] = "Toggle Favourite",
        ["FullPlayer_QueueTab"] = "Queue",
        ["FullPlayer_QueuePosition"] = "{0} · {1} of {2}",
        ["Player_PlaybackProblemTitle"] = "Playback Problem",
        ["Player_StoppedMultipleError"] = "Playback stopped: {0} songs in a row could not be played.",
        ["Player_CouldNotPlaySong"] = "\"{0}\" could not be played.",
        ["Player_ThisSong"] = "This song",

        // LyricsPanel
        ["Lyrics_Title"] = "Lyrics",
        ["Lyrics_ImportTooltip"] = "Import .lrc or .txt lyrics",
        ["Lyrics_RemoveTooltip"] = "Remove imported lyrics",
        ["Lyrics_ClosePanelTooltip"] = "Close panel",
        ["Lyrics_EmptyTitle"] = "No Lyrics",
        ["Lyrics_EmptyDesc"] = "Import a .lrc or .txt file for this song, or place one with the same name next to it.",
        ["Lyrics_ImportFromFile"] = "Import from File...",
        ["Lyrics_SourceImported"] = "From imported file",
        ["Lyrics_SourceSidecar"] = "From sidecar file next to song",
        ["Lyrics_SourceEmbedded"] = "From song tags",
        ["Lyrics_ImportDialogTitle"] = "Import Lyrics",
        ["Lyrics_ImportDialogFilter"] = "Lyrics Files (*.lrc;*.txt)|*.lrc;*.txt|All Files (*.*)|*.*",
        ["Lyrics_CouldNotImportTitle"] = "Couldn't Import Lyrics",

        // EqualizerDialog
        ["Equalizer_Title"] = "Equalizer",
        ["Equalizer_Preset"] = "Preset",
        ["Equalizer_PresetCustom"] = "Custom",
        ["Equalizer_ResetToFlat"] = "Reset to Flat",
        ["Equalizer_PreampFormat"] = "Preamp {0} dB",
        ["EqPreset_Flat"] = "Flat",
        ["EqPreset_BassBoost"] = "Bass Boost",
        ["EqPreset_TrebleBoost"] = "Treble Boost",
        ["EqPreset_Rock"] = "Rock",
        ["EqPreset_Pop"] = "Pop",
        ["EqPreset_Jazz"] = "Jazz",
        ["EqPreset_Classical"] = "Classical",
        ["EqPreset_Vocal"] = "Vocal",

        // SleepTimerDialog
        ["SleepTimer_Title"] = "Sleep Timer",
        ["SleepTimer_CancelTimer"] = "Cancel Timer",
        ["SleepTimer_StopPlayingAfter"] = "Stop playing after",
        ["SleepTimer_PresetMin"] = "{0} min",
        ["SleepTimer_CustomMinutes"] = "{0} minutes",
        ["SleepTimer_Start"] = "Start",
        ["SleepTimer_EndOfCurrentSong"] = "End of current song",
        ["SleepTimer_StopsAtEndOfSong"] = "Stops at the end of this song",
        ["SleepTimer_StopsIn"] = "Stops in {0}",

        // SongInfoDialog
        ["SongInfo_Title"] = "Song Info",
        ["SongInfo_MetadataHeader"] = "METADATA",
        ["SongInfo_CoverImage"] = "Cover Image",
        ["SongInfo_NoCover"] = "No cover artwork",
        ["SongInfo_EmbeddedOrCachedCover"] = "Embedded or cached artwork",
        ["SongInfo_ChangeCover"] = "Change Cover...",
        ["SongInfo_FieldTitle"] = "Title",
        ["SongInfo_FieldArtist"] = "Artist",
        ["SongInfo_FieldAlbum"] = "Album",
        ["SongInfo_FieldGenre"] = "Genre",
        ["SongInfo_SearchArtistTooltip"] = "Search songs by this Artist",
        ["SongInfo_SearchAlbumTooltip"] = "Search songs in this Album",
        ["SongInfo_SearchGenreTooltip"] = "Search songs with this Genre",
        ["SongInfo_SearchFieldTooltip"] = "Search {0}: {1}",
        ["SongInfo_FileInfoHeader"] = "FILE & PLAYBACK INFO",
        ["SongInfo_Precision"] = "Precision",
        ["SongInfo_Frequency"] = "Frequency",
        ["SongInfo_BitRate"] = "Bit Rate",
        ["SongInfo_FileSize"] = "File Size",
        ["SongInfo_Length"] = "Length",
        ["SongInfo_SoundTracks"] = "Sound Tracks",
        ["SongInfo_TimeAdded"] = "Time Added",
        ["SongInfo_TimeEdited"] = "Time Edited",
        ["SongInfo_LastPlayed"] = "Last Played",
        ["SongInfo_PlayedTimes"] = "Played Times",
        ["SongInfo_Source"] = "Source",
        ["SongInfo_Never"] = "Never",
        ["SongInfo_BitsFormat"] = "{0}-bit",
        ["SongInfo_ChannelsMono"] = "1 (Mono)",
        ["SongInfo_ChannelsStereo"] = "2 (Stereo)",
        ["SongInfo_Channels51"] = "6 (5.1)",
        ["SongInfo_Channels71"] = "8 (7.1)",
        ["SongInfo_BytesUnit"] = "bytes",
        ["SongInfo_SelectCoverTitle"] = "Select Cover Image",
        ["SongInfo_SelectCoverFilter"] = "Image Files|*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.webp|All Files|*.*",
        ["SongInfo_InvalidImageTitle"] = "Invalid Image",
        ["SongInfo_InvalidImageMsg"] = "Could not load that image: {0}",
        ["SongInfo_CouldNotSaveTitle"] = "Couldn't Save Song Info",

        // UpdateDialog & UpdateService
        ["Update_Title"] = "Software Update",
        ["Update_ReleaseNotes"] = "Release Notes",
        ["Update_UpdateNow"] = "Update Now",
        ["Update_SkipVersion"] = "Skip Version",
        ["Update_OpenReleasePage"] = "Open Release Page",
        ["Update_CheckAgain"] = "Check Again",
        ["Update_Checking"] = "Checking for updates...",
        ["Update_CheckingTitle"] = "Checking for Updates",
        ["Update_CurrentVersion"] = "Current version: {0}",
        ["Update_ModePortable"] = "Portable mode (.zip)",
        ["Update_ModeInstaller"] = "Installer mode (.exe)",
        ["Update_PackagePortable"] = "Portable (.zip)",
        ["Update_PackageInstaller"] = "Installer (.exe)",
        ["Update_AvailableTitle"] = "Update Available",
        ["Update_PackageLabel"] = "Package: {0}",
        ["Update_NoReleaseNotes"] = "No release notes provided for this release.",
        ["Update_NoCompatibleAsset"] = "No compatible {0} package was found in release {1}. You can open the release page to download it manually.",
        ["Update_FailedTitle"] = "Update Check Failed",
        ["Update_UnableToReach"] = "Unable to reach GitHub Releases.",
        ["Update_UpToDateTitle"] = "You're Up to Date",
        ["Update_UpToDateVersion"] = "Musico {0} is the latest version",
        ["Update_Preparing"] = "Preparing update...",
        ["Update_OpenBrowserError"] = "Unable to open browser: {0}",
        ["Update_StageFetchingChecksums"] = "Fetching SHA-256 checksums...",
        ["Update_StageDownloading"] = "Downloading update...",
        ["Update_StageVerifying"] = "Verifying SHA-256 checksum...",
        ["Update_StageStartingInstaller"] = "Starting installer...",
        ["Update_StageExtracting"] = "Extracting portable update...",
        ["Update_StageApplying"] = "Applying update and restarting...",

        // AddCloudFolderDialog
        ["AddCloud_Title"] = "Add Cloud Folder",
        ["AddCloud_UrlLabel"] = "Playlist or Video URL",
        ["AddCloud_UrlHint"] = "Supports YouTube / YouTube Music playlists & videos (no login required), Bilibili favlists, collections, series, b23.tv & BV links.",
        ["AddCloud_CookieLabel"] = "Cookie (Optional)",
        ["AddCloud_CookieHint"] = "Only needed for private/authenticated playlists or higher Bilibili audio quality.",
        ["AddCloud_ProxyLabel"] = "HTTP Proxy (Optional — YouTube)",
        ["AddCloud_ProxyHint"] = "e.g. 7890, 127.0.0.1:7890, or http://127.0.0.1:7890",
        ["AddCloud_Fetching"] = "Fetching playlist tracks...",

        // SaveCloudFolderDialog
        ["SaveCloud_Title"] = "Save Cloud Playlist to Local Folder",
        ["SaveCloud_SingleTrackTitle"] = "Save Song to Local Folder",
        ["SaveCloud_DefaultFolderName"] = "Cloud Playlist",
        ["SaveCloud_FolderName"] = "Folder Name",
        ["SaveCloud_SaveLocation"] = "Save Location",
        ["SaveCloud_AddToLibrary"] = "Add saved folder to Library",
        ["SaveCloud_SaveFolder"] = "Save Folder",
        ["SaveCloud_OpenFolder"] = "Open Folder",
        ["SaveCloud_SavingItem"] = "Saving \"{0}\"...",
        ["SaveCloud_CheckingPlaylist"] = "Checking cloud playlist...",
        ["SaveCloud_SyncingItem"] = "Syncing \"{0}\"...",
        ["SaveCloud_Finishing"] = "Finishing...",
        ["SaveCloud_Preparing"] = "Preparing export...",
        ["SaveCloud_SavedSingleTrack"] = "Saved song to local folder",
        ["SaveCloud_AlreadyUpToDate"] = "Already up to date ({0})",
        ["SaveCloud_UpdateDownloaded"] = "Downloaded {0} new, skipped {1} existing",
        ["SaveCloud_UpdateWithFailures"] = "Downloaded {0}, skipped {1}, failed {2}",
        ["SaveCloud_ExportSuccess"] = "Saved {0}",
        ["SaveCloud_ExportWithFailures"] = "Saved {0}, {1} failed",
        ["SaveCloud_LibraryRescanned"] = "Library rescanned.",
        ["SaveCloud_AddedToLibrary"] = "Added saved folder to Library.",
        ["SaveCloud_SavedLibError"] = "Saved, but could not update library: {0}",
        ["SaveCloud_ExportCancelled"] = "Export cancelled.",
        ["SaveCloud_UpdateCancelled"] = "Update cancelled.",
        ["SaveCloud_EnterFolderName"] = "Enter a folder name.",
        ["SaveCloud_ChooseSaveLocation"] = "Choose a save location.",
        ["SaveCloud_SelectSaveLocationTitle"] = "Select Save Location",
        ["SaveCloud_DestinationPreview"] = "Destination: {0}",
        ["SaveCloud_ChooseDestinationHint"] = "Choose a destination folder.",
        ["SaveCloud_OpenFolderError"] = "Unable to open folder: {0}",

        // TrackMenuHelper & TrackRowControl
        ["Menu_PlayNext"] = "Play Next",
        ["Menu_AddToQueue"] = "Add to Queue",
        ["Menu_AddToAnotherQueue"] = "Add to Another Queue",
        ["Menu_PlayInNewQueue"] = "Play in New Queue",
        ["Menu_AddToPlaylist"] = "Add to Playlist",
        ["Menu_NewPlaylistEllipsis"] = "New Playlist...",
        ["Menu_RemoveFromFavourites"] = "Remove from Favourites",
        ["Menu_AddToFavourites"] = "Add to Favourites",
        ["Menu_SaveTrackToLocalTitle"] = "Save \"{0}\" to Local Folder",
        ["TrackRow_DragToReorder"] = "Drag to reorder",
    };

    private static readonly Dictionary<string, string> ZhHans = new(StringComparer.Ordinal)
    {
        // Language switcher
        ["Lang_FollowSystem"] = "跟随系统",
        ["Lang_English"] = "English",
        ["Lang_SimplifiedChinese"] = "简体中文",

        // Window & Navigation
        ["Window_Minimize"] = "最小化",
        ["Window_Maximize"] = "最大化",
        ["Window_Restore"] = "还原",
        ["Window_Close"] = "关闭",
        ["Nav_TogglePane"] = "切换导航栏",
        ["Nav_Folders"] = "文件夹",
        ["Nav_HeaderLibrary"] = "音乐库",
        ["Nav_Songs"] = "歌曲",
        ["Nav_Albums"] = "专辑",
        ["Nav_Artists"] = "艺术家",
        ["Nav_HeaderPlaylists"] = "播放列表",
        ["Nav_AllPlaylists"] = "全部播放列表",
        ["Nav_Favourites"] = "我喜欢的音乐",
        ["Nav_NewPlaylist"] = "新建播放列表",
        ["Nav_Queues"] = "播放队列",
        ["Nav_Stats"] = "收听统计",
        ["Nav_LightTheme"] = "浅色主题",
        ["Nav_DarkTheme"] = "深色主题",
        ["Nav_ThemeTooltip"] = "切换浅色/深色主题",
        ["Nav_Language"] = "语言",
        ["Nav_LanguageTooltip"] = "切换语言",
        ["Nav_Rescan"] = "重新扫描音乐库",
        ["Nav_RescanTooltip"] = "重新扫描音乐库文件夹",
        ["Nav_CheckUpdates"] = "检查更新",

        // Common actions & labels
        ["Common_OK"] = "确定",
        ["Common_Cancel"] = "取消",
        ["Common_Cancelling"] = "正在取消...",
        ["Common_Close"] = "关闭",
        ["Common_Confirm"] = "确认",
        ["Common_Save"] = "保存",
        ["Common_Done"] = "完成",
        ["Common_Back"] = "返回",
        ["Common_Create"] = "创建",
        ["Common_Rename"] = "重命名",
        ["Common_RenameEllipsis"] = "重命名...",
        ["Common_Delete"] = "删除",
        ["Common_Play"] = "播放",
        ["Common_PlayAll"] = "全部播放",
        ["Common_Shuffle"] = "随机播放",
        ["Common_MoreOptions"] = "更多选项",
        ["Common_MoveUp"] = "上移",
        ["Common_MoveDown"] = "下移",
        ["Common_Browse"] = "浏览...",
        ["Common_UnknownArtist"] = "未知艺术家",
        ["Common_UnknownAlbum"] = "未知专辑",

        // Sort modes
        ["Sort_Custom"] = "自定义",
        ["Sort_NameAsc"] = "名称 (A-Z)",
        ["Sort_NameDesc"] = "名称 (Z-A)",
        ["Sort_DateModified"] = "修改日期",
        ["Sort_MostPlayed"] = "最多播放",
        ["Sort_LeastPlayed"] = "最少播放",

        // Search fields
        ["SearchField_Keyword"] = "关键词",
        ["SearchField_Artist"] = "艺术家",
        ["SearchField_Album"] = "专辑",
        ["SearchField_Genre"] = "流派",

        // Folders
        ["Folders_Title"] = "文件夹",
        ["Folders_AppFolderName"] = "音乐文件夹",
        ["Folders_Subtitle"] = "系统“音乐”文件夹与已添加的音乐目录",
        ["Folders_RootsSubtitle"] = "默认包含系统“音乐”文件夹。你可以添加任意包含音乐的文件夹，或移除不需要的文件夹。",
        ["Folders_Rescan"] = "重新扫描",
        ["Folders_AddCloudFolder"] = "添加云端文件夹",
        ["Folders_AddFolder"] = "添加文件夹",
        ["Folders_NewFolder"] = "新建文件夹",
        ["Folders_NewFolderPrompt"] = "请输入新文件夹的名称。",
        ["Folders_SaveToLocal"] = "保存到本地文件夹...",
        ["Folders_SaveCloudTooltip"] = "将云端播放列表保存到本地文件夹",
        ["Folders_UpdateFromCloud"] = "从云端更新",
        ["Folders_RemoveFolder"] = "移除文件夹",
        ["Folders_RemoveFromLibraryTooltip"] = "从音乐库中移除文件夹",
        ["Folders_ScanningDefault"] = "正在查找音乐...",
        ["Folders_ScanningProgress"] = "正在读取第 {0} / {1} 首歌曲...",
        ["Folders_CloudLocalBadge"] = "{0} · 本地",
        ["Folders_Unavailable"] = "不可用 — 文件夹已移动或断开连接",
        ["Folders_LocateAgain"] = "重新定位...",
        ["Folders_RestoreRemovedSubfolders"] = "恢复已移除的子文件夹 ({0})",
        ["Folders_RestoreMusicFolder"] = "恢复音乐文件夹",
        ["Folders_EmptyTitle"] = "这里没有音乐",
        ["Folders_EmptyDesc"] = "添加到此文件夹的歌曲将在重新扫描后显示。",
        ["Folders_SelectMusicFolderTitle"] = "选择音乐文件夹",
        ["Folders_LocateFolderTitle"] = "定位音乐文件夹",
        ["Folders_CouldNotAddTitle"] = "无法添加文件夹",
        ["Folders_CouldNotLocateTitle"] = "无法定位文件夹",
        ["Folders_CouldNotCreateTitle"] = "无法创建文件夹",
        ["Folders_RemoveConfirmTitle"] = "确定从音乐库中移除“{0}”吗？",
        ["Folders_RemoveConfirmCloudMessage"] = "其中的歌曲将从音乐库、播放列表和队列中移除。",
        ["Folders_RemoveConfirmLocalMessage"] = "其中的歌曲将从音乐库、播放列表和队列中移除，本地文件本身不会被删除。",
        ["Folders_RemoveConfirmAction"] = "从音乐库移除",

        // Songs
        ["Songs_Title"] = "歌曲",
        ["Songs_SearchPlaceholder"] = "搜索歌曲、艺术家、专辑 (Ctrl+F)",
        ["Songs_ClearFieldFilter"] = "清除字段筛选",
        ["Songs_SortTitle"] = "标题",
        ["Songs_SortArtist"] = "艺术家",
        ["Songs_SortAlbum"] = "专辑",
        ["Songs_SortRecentlyAdded"] = "最近添加",
        ["Songs_EmptyTitle"] = "暂无歌曲",
        ["Songs_EmptyDesc"] = "前往“文件夹”添加文件夹以构建你的音乐库。",
        ["Songs_NoSearchResultsTitle"] = "未找到“{0}”的结果",
        ["Songs_NoSearchResultsDesc"] = "请尝试搜索其他歌曲、艺术家或专辑。",

        // Albums & Artists
        ["Albums_Title"] = "专辑",
        ["Albums_EmptyTitle"] = "暂无专辑",
        ["Albums_EmptyDesc"] = "音乐库中有歌曲后将在此显示专辑。",
        ["Artists_Title"] = "艺术家",
        ["Artists_EmptyTitle"] = "暂无艺术家",
        ["Artists_EmptyDesc"] = "音乐库中有歌曲后将在此显示艺术家。",

        // Playlists & Playlist Detail
        ["Playlists_Title"] = "播放列表",
        ["Playlists_RenameTooltip"] = "重命名播放列表",
        ["Playlists_DeleteTooltip"] = "删除播放列表",
        ["Playlists_RenameTitle"] = "重命名播放列表",
        ["Playlists_RenameMessage"] = "请输入此播放列表的新名称。",
        ["Playlists_DeleteConfirmTitle"] = "确定删除“{0}”吗？",
        ["Playlists_DeleteConfirmMessage"] = "此操作仅移除播放列表，不会影响你的音乐文件。",
        ["Playlists_NewMessage"] = "请输入播放列表名称。",
        ["PlaylistDetail_FallbackTitle"] = "播放列表",
        ["PlaylistDetail_EmptyTitle"] = "这里暂无内容",
        ["PlaylistDetail_EmptyDesc"] = "右键点击任意歌曲、专辑或文件夹，即可将其添加到此播放列表。",
        ["PlaylistDetail_RemoveFromPlaylist"] = "从播放列表移除",

        // Queues
        ["Queues_Title"] = "播放队列",
        ["Queues_NewQueue"] = "新建队列",
        ["Queues_Clear"] = "清空",
        ["Queues_ClearTooltip"] = "清空当前队列",
        ["Queues_PlayQueuesHeader"] = "播放队列",
        ["Queues_SortLabel"] = "排序：",
        ["Queues_EmptyTitle"] = "队列为空",
        ["Queues_EmptyDesc"] = "点击音乐库中的任意歌曲即可开始播放。",
        ["Queues_EmptyChip"] = "空",
        ["Queues_MissingSong"] = "歌曲缺失",
        ["Queues_RemoveFromQueue"] = "从队列移除",
        ["Queues_RenameTitle"] = "重命名队列",
        ["Queues_RenameMessage"] = "请输入此队列的新名称。",
        ["Queues_ClearConfirmTitle"] = "确定清空“{0}”吗？",
        ["Queues_ClearConfirmMessage"] = "确定要移除“{0}”中的所有歌曲吗？队列本身将保留。",
        ["Queues_ClearConfirmAction"] = "清空队列",

        // Stats
        ["Stats_Title"] = "收听统计",
        ["Stats_PlaysUnitSingular"] = "次播放",
        ["Stats_PlaysUnitPlural"] = "次播放",
        ["Stats_ListenedLabel"] = "累计收听",
        ["Stats_NeverPlayedLabel"] = "从未播放",
        ["Stats_TabRecent"] = "最近播放",
        ["Stats_TabPlays"] = "播放次数",
        ["Stats_TabNotLately"] = "许久未听",
        ["Stats_TabNever"] = "从未播放",
        ["Stats_MostPlayedFirst"] = "播放最多优先",
        ["Stats_LeastPlayedFirst"] = "播放最少优先",
        ["Stats_EmptyRecentTitle"] = "暂无播放记录",
        ["Stats_EmptyRecentDesc"] = "歌曲开始播放几秒后将显示在这里。",
        ["Stats_EmptyPlaysTitle"] = "暂无播放次数",
        ["Stats_EmptyPlaysDesc"] = "收听一首歌曲超过一半时长即计为一次播放。",
        ["Stats_EmptyStaleTitle"] = "没有久未收听的歌曲",
        ["Stats_EmptyStaleDesc"] = "超过 {0} 天未收听的歌曲将显示在这里。",
        ["Stats_EmptyNeverTitle"] = "全部歌曲均已播放",
        ["Stats_EmptyNeverDesc"] = "音乐库中的每首歌曲都至少播放过一次。",
        ["Stats_DurationHoursMinutes"] = "{0} 小时 {1} 分钟",
        ["Stats_DurationMinutes"] = "{0} 分钟",
        ["Relative_JustNow"] = "刚刚",
        ["Relative_OneMinuteAgo"] = "1 分钟前",
        ["Relative_MinutesAgo"] = "{0} 分钟前",
        ["Relative_OneHourAgo"] = "1 小时前",
        ["Relative_HoursAgo"] = "{0} 小时前",
        ["Relative_OneDayAgo"] = "1 天前",
        ["Relative_DaysAgo"] = "{0} 天前",

        // PlayerBar & FullPlayerView & Errors
        ["PlayerBar_NotPlaying"] = "未在播放",
        ["PlayerBar_PickSong"] = "从音乐库选择一首歌曲",
        ["PlayerBar_NowPlayingTooltip"] = "打开正在播放（右键点击查看歌曲信息与更多选项）",
        ["PlayerBar_PrevTooltip"] = "上一首 (Ctrl+Left)",
        ["PlayerBar_PlayPauseTooltip"] = "播放 / 暂停 (Space)",
        ["PlayerBar_NextTooltip"] = "下一首 (Ctrl+Right)",
        ["PlayerBar_RepeatTooltip"] = "循环播放",
        ["PlayerBar_MuteTooltip"] = "静音",
        ["PlayerBar_VolumeTooltip"] = "音量",
        ["FullPlayer_NowPlaying"] = "正在播放",
        ["FullPlayer_NothingPlaying"] = "暂无播放内容",
        ["FullPlayer_CloseTooltip"] = "关闭正在播放 (Esc)",
        ["FullPlayer_ShowLyricsTooltip"] = "显示歌词",
        ["FullPlayer_ToggleFavTooltip"] = "收藏 / 取消收藏",
        ["FullPlayer_QueueTab"] = "队列",
        ["FullPlayer_QueuePosition"] = "{0} · 第 {1} / {2} 首",
        ["Player_PlaybackProblemTitle"] = "播放出错",
        ["Player_StoppedMultipleError"] = "播放已停止：连续 {0} 首歌曲无法播放。",
        ["Player_CouldNotPlaySong"] = "无法播放“{0}”。",
        ["Player_ThisSong"] = "此歌曲",

        // LyricsPanel
        ["Lyrics_Title"] = "歌词",
        ["Lyrics_ImportTooltip"] = "导入 .lrc 或 .txt 歌词",
        ["Lyrics_RemoveTooltip"] = "移除已导入的歌词",
        ["Lyrics_ClosePanelTooltip"] = "关闭面板",
        ["Lyrics_EmptyTitle"] = "暂无歌词",
        ["Lyrics_EmptyDesc"] = "为此歌曲导入 .lrc 或 .txt 歌词文件，或将同名歌词文件放在歌曲同级目录下。",
        ["Lyrics_ImportFromFile"] = "从文件导入...",
        ["Lyrics_SourceImported"] = "来自导入文件",
        ["Lyrics_SourceSidecar"] = "来自歌曲同目录歌词文件",
        ["Lyrics_SourceEmbedded"] = "来自音频内嵌标签",
        ["Lyrics_ImportDialogTitle"] = "导入歌词",
        ["Lyrics_ImportDialogFilter"] = "歌词文件 (*.lrc;*.txt)|*.lrc;*.txt|所有文件 (*.*)|*.*",
        ["Lyrics_CouldNotImportTitle"] = "无法导入歌词",

        // EqualizerDialog
        ["Equalizer_Title"] = "均衡器",
        ["Equalizer_Preset"] = "预设",
        ["Equalizer_PresetCustom"] = "自定义",
        ["Equalizer_ResetToFlat"] = "重置为平衡",
        ["Equalizer_PreampFormat"] = "前置增益 {0} dB",
        ["EqPreset_Flat"] = "平衡",
        ["EqPreset_BassBoost"] = "低音增强",
        ["EqPreset_TrebleBoost"] = "高音增强",
        ["EqPreset_Rock"] = "摇滚",
        ["EqPreset_Pop"] = "流行",
        ["EqPreset_Jazz"] = "爵士",
        ["EqPreset_Classical"] = "古典",
        ["EqPreset_Vocal"] = "人声",

        // SleepTimerDialog
        ["SleepTimer_Title"] = "睡眠定时",
        ["SleepTimer_CancelTimer"] = "取消定时",
        ["SleepTimer_StopPlayingAfter"] = "在以下时间后停止播放",
        ["SleepTimer_PresetMin"] = "{0} 分钟",
        ["SleepTimer_CustomMinutes"] = "{0} 分钟",
        ["SleepTimer_Start"] = "开始",
        ["SleepTimer_EndOfCurrentSong"] = "当前歌曲播放完毕后",
        ["SleepTimer_StopsAtEndOfSong"] = "将在本首歌曲结束时停止",
        ["SleepTimer_StopsIn"] = "{0} 后停止播放",

        // SongInfoDialog
        ["SongInfo_Title"] = "歌曲信息",
        ["SongInfo_MetadataHeader"] = "元数据",
        ["SongInfo_CoverImage"] = "封面图片",
        ["SongInfo_NoCover"] = "无封面图片",
        ["SongInfo_EmbeddedOrCachedCover"] = "内嵌或已缓存封面",
        ["SongInfo_ChangeCover"] = "更换封面...",
        ["SongInfo_FieldTitle"] = "标题",
        ["SongInfo_FieldArtist"] = "艺术家",
        ["SongInfo_FieldAlbum"] = "专辑",
        ["SongInfo_FieldGenre"] = "流派",
        ["SongInfo_SearchArtistTooltip"] = "搜索此艺术家的歌曲",
        ["SongInfo_SearchAlbumTooltip"] = "搜索此专辑中的歌曲",
        ["SongInfo_SearchGenreTooltip"] = "搜索此流派的歌曲",
        ["SongInfo_SearchFieldTooltip"] = "搜索{0}：{1}",
        ["SongInfo_FileInfoHeader"] = "文件与播放信息",
        ["SongInfo_Precision"] = "位深",
        ["SongInfo_Frequency"] = "采样率",
        ["SongInfo_BitRate"] = "比特率",
        ["SongInfo_FileSize"] = "文件大小",
        ["SongInfo_Length"] = "时长",
        ["SongInfo_SoundTracks"] = "声道",
        ["SongInfo_TimeAdded"] = "添加时间",
        ["SongInfo_TimeEdited"] = "修改时间",
        ["SongInfo_LastPlayed"] = "上次播放",
        ["SongInfo_PlayedTimes"] = "播放次数",
        ["SongInfo_Source"] = "来源",
        ["SongInfo_Never"] = "从未",
        ["SongInfo_BitsFormat"] = "{0} 位",
        ["SongInfo_ChannelsMono"] = "1 (单声道)",
        ["SongInfo_ChannelsStereo"] = "2 (立体声)",
        ["SongInfo_Channels51"] = "6 (5.1)",
        ["SongInfo_Channels71"] = "8 (7.1)",
        ["SongInfo_BytesUnit"] = "字节",
        ["SongInfo_SelectCoverTitle"] = "选择封面图片",
        ["SongInfo_SelectCoverFilter"] = "图片文件|*.png;*.jpg;*.jpeg;*.bmp;*.gif;*.webp|所有文件|*.*",
        ["SongInfo_InvalidImageTitle"] = "无效的图片",
        ["SongInfo_InvalidImageMsg"] = "无法加载该图片：{0}",
        ["SongInfo_CouldNotSaveTitle"] = "无法保存歌曲信息",

        // UpdateDialog & UpdateService
        ["Update_Title"] = "软件更新",
        ["Update_ReleaseNotes"] = "更新日志",
        ["Update_UpdateNow"] = "立即更新",
        ["Update_SkipVersion"] = "跳过此版本",
        ["Update_OpenReleasePage"] = "打开发布页",
        ["Update_CheckAgain"] = "重新检查",
        ["Update_Checking"] = "正在检查更新...",
        ["Update_CheckingTitle"] = "正在检查更新",
        ["Update_CurrentVersion"] = "当前版本：{0}",
        ["Update_ModePortable"] = "便携版模式 (.zip)",
        ["Update_ModeInstaller"] = "安装版模式 (.exe)",
        ["Update_PackagePortable"] = "便携版 (.zip)",
        ["Update_PackageInstaller"] = "安装版 (.exe)",
        ["Update_AvailableTitle"] = "发现新版本",
        ["Update_PackageLabel"] = "安装包：{0}",
        ["Update_NoReleaseNotes"] = "此版本未提供更新日志。",
        ["Update_NoCompatibleAsset"] = "在版本 {1} 中未找到兼容的{0}安装包，你可以打开发布页手动下载。",
        ["Update_FailedTitle"] = "检查更新失败",
        ["Update_UnableToReach"] = "无法连接到 GitHub Releases。",
        ["Update_UpToDateTitle"] = "已是最新版本",
        ["Update_UpToDateVersion"] = "Musico {0} 已是最新版本",
        ["Update_Preparing"] = "正在准备更新...",
        ["Update_OpenBrowserError"] = "无法打开浏览器：{0}",
        ["Update_StageFetchingChecksums"] = "正在获取 SHA-256 校验和...",
        ["Update_StageDownloading"] = "正在下载更新...",
        ["Update_StageVerifying"] = "正在校验 SHA-256...",
        ["Update_StageStartingInstaller"] = "正在启动安装程序...",
        ["Update_StageExtracting"] = "正在解压便携版更新...",
        ["Update_StageApplying"] = "正在应用更新并重启...",

        // AddCloudFolderDialog
        ["AddCloud_Title"] = "添加云端文件夹",
        ["AddCloud_UrlLabel"] = "播放列表或视频链接",
        ["AddCloud_UrlHint"] = "支持 YouTube / YouTube Music 播放列表与视频（无需登录），以及 Bilibili 收藏夹、合集、系列、b23.tv 与 BV 链接。",
        ["AddCloud_CookieLabel"] = "Cookie（可选）",
        ["AddCloud_CookieHint"] = "仅在访问私有/需登录的播放列表或获取更高音质的 Bilibili 音频时需要。",
        ["AddCloud_ProxyLabel"] = "HTTP 代理（可选 — YouTube）",
        ["AddCloud_ProxyHint"] = "例如：7890、127.0.0.1:7890 或 http://127.0.0.1:7890",
        ["AddCloud_Fetching"] = "正在获取播放列表歌曲...",

        // SaveCloudFolderDialog
        ["SaveCloud_Title"] = "将云端播放列表保存到本地文件夹",
        ["SaveCloud_SingleTrackTitle"] = "将歌曲保存到本地文件夹",
        ["SaveCloud_DefaultFolderName"] = "云端播放列表",
        ["SaveCloud_FolderName"] = "文件夹名称",
        ["SaveCloud_SaveLocation"] = "保存位置",
        ["SaveCloud_AddToLibrary"] = "将保存的文件夹添加到音乐库",
        ["SaveCloud_SaveFolder"] = "保存文件夹",
        ["SaveCloud_OpenFolder"] = "打开文件夹",
        ["SaveCloud_SavingItem"] = "正在保存“{0}”...",
        ["SaveCloud_CheckingPlaylist"] = "正在检查云端播放列表...",
        ["SaveCloud_SyncingItem"] = "正在同步“{0}”...",
        ["SaveCloud_Finishing"] = "即将完成...",
        ["SaveCloud_Preparing"] = "正在准备导出...",
        ["SaveCloud_SavedSingleTrack"] = "已将歌曲保存到本地文件夹",
        ["SaveCloud_AlreadyUpToDate"] = "已是最新（共 {0}）",
        ["SaveCloud_UpdateDownloaded"] = "已下载 {0}，跳过 {1} 首已有歌曲",
        ["SaveCloud_UpdateWithFailures"] = "已下载 {0} 首，跳过 {1} 首，失败 {2} 首",
        ["SaveCloud_ExportSuccess"] = "已保存 {0}",
        ["SaveCloud_ExportWithFailures"] = "已保存 {0}，{1} 首失败",
        ["SaveCloud_LibraryRescanned"] = "已重新扫描音乐库。",
        ["SaveCloud_AddedToLibrary"] = "已将保存的文件夹添加到音乐库。",
        ["SaveCloud_SavedLibError"] = "已保存，但无法更新音乐库：{0}",
        ["SaveCloud_ExportCancelled"] = "已取消导出。",
        ["SaveCloud_UpdateCancelled"] = "已取消更新。",
        ["SaveCloud_EnterFolderName"] = "请输入文件夹名称。",
        ["SaveCloud_ChooseSaveLocation"] = "请选择保存位置。",
        ["SaveCloud_SelectSaveLocationTitle"] = "选择保存位置",
        ["SaveCloud_DestinationPreview"] = "目标路径：{0}",
        ["SaveCloud_ChooseDestinationHint"] = "请选择目标文件夹。",
        ["SaveCloud_OpenFolderError"] = "无法打开文件夹：{0}",

        // TrackMenuHelper & TrackRowControl
        ["Menu_PlayNext"] = "下一首播放",
        ["Menu_AddToQueue"] = "添加到队列",
        ["Menu_AddToAnotherQueue"] = "添加到其他队列",
        ["Menu_PlayInNewQueue"] = "在新队列中播放",
        ["Menu_AddToPlaylist"] = "添加到播放列表",
        ["Menu_NewPlaylistEllipsis"] = "新建播放列表...",
        ["Menu_RemoveFromFavourites"] = "从我喜欢的音乐移除",
        ["Menu_AddToFavourites"] = "添加到我喜欢的音乐",
        ["Menu_SaveTrackToLocalTitle"] = "将“{0}”保存到本地文件夹",
        ["TrackRow_DragToReorder"] = "拖动以重新排序",
    };
}
