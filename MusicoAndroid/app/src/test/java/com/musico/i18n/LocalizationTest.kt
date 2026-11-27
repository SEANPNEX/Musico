package com.musico.i18n

import com.musico.model.LyricsSource
import com.musico.model.SearchField
import com.musico.model.TrackSortOrder
import com.musico.ui.theme.AppThemeMode
import com.musico.ui.theme.BackgroundColorPreset
import com.musico.ui.theme.ForegroundColorPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

class LocalizationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun languageStoreDefaultsToSystemAndPersistsRoundTrip() {
        val prefsFile = File(tempFolder.root, "language.txt")
        val initialStore = LanguageStore(context = null, prefsFile = prefsFile)
        assertEquals(AppLanguage.SYSTEM, initialStore.language.value)

        initialStore.setLanguage(AppLanguage.SIMPLIFIED_CHINESE)
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, initialStore.language.value)
        assertEquals("zh-Hans", prefsFile.readText(Charsets.UTF_8).trim())

        val reloadedZh = LanguageStore(context = null, prefsFile = prefsFile)
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, reloadedZh.language.value)

        reloadedZh.setLanguage(AppLanguage.ENGLISH)
        assertEquals("en", prefsFile.readText(Charsets.UTF_8).trim())

        val reloadedEn = LanguageStore(context = null, prefsFile = prefsFile)
        assertEquals(AppLanguage.ENGLISH, reloadedEn.language.value)

        reloadedEn.setLanguage(AppLanguage.SYSTEM)
        val reloadedSystem = LanguageStore(context = null, prefsFile = prefsFile)
        assertEquals(AppLanguage.SYSTEM, reloadedSystem.language.value)
    }

    @Test
    fun resolveLanguageHandlesSystemAndExplicitSelections() {
        assertEquals(
            ResolvedLanguage.ZH_HANS,
            resolveLanguage(AppLanguage.SYSTEM, Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            ResolvedLanguage.ZH_HANS,
            resolveLanguage(AppLanguage.SYSTEM, Locale("zh", "CN")),
        )
        assertEquals(
            ResolvedLanguage.EN,
            resolveLanguage(AppLanguage.SYSTEM, Locale.US),
        )
        assertEquals(
            ResolvedLanguage.EN,
            resolveLanguage(AppLanguage.SYSTEM, Locale.JAPAN),
        )

        // Explicit selections override system locale.
        assertEquals(
            ResolvedLanguage.EN,
            resolveLanguage(AppLanguage.ENGLISH, Locale.SIMPLIFIED_CHINESE),
        )
        assertEquals(
            ResolvedLanguage.ZH_HANS,
            resolveLanguage(AppLanguage.SIMPLIFIED_CHINESE, Locale.US),
        )
        assertEquals(
            ResolvedLanguage.ZH_HANS,
            resolveLanguage(AppLanguage.SIMPLIFIED_CHINESE, Locale.JAPAN),
        )
    }

    @Test
    fun fromCodeParsesValidCodesAndFallsBackToSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode("unknown"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("en"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromCode("zh-Hans"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromCode("ZH-HANS"))
    }

    @Test
    fun allStringPropertiesAreNonEmptyAndProperlyLocalizedInZhHans() {
        val intentionallyIdenticalGetters = setOf(
            "getLanguageEnglish",
            "getLanguageSimplifiedChinese",
            "getBadgeYouTube",
            "getBadgeBilibili",
            "getBadgeGoogleDrive",
        )

        val stringGetters = AppStrings::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == String::class.java && it.name.startsWith("get") }
            .sortedBy { it.name }

        assertTrue("Expected AppStrings to expose string properties", stringGetters.size > 100)

        for (method in stringGetters) {
            val enValue = method.invoke(EnStrings) as String
            val zhValue = method.invoke(ZhHansStrings) as String

            assertTrue("EnStrings.${method.name} must not be blank", enValue.isNotBlank())
            assertTrue("ZhHansStrings.${method.name} must not be blank", zhValue.isNotBlank())

            if (method.name !in intentionallyIdenticalGetters) {
                assertNotEquals(
                    "ZhHansStrings.${method.name} should differ from EnStrings (was '$zhValue')",
                    enValue,
                    zhValue,
                )
            }
        }
    }

    @Test
    fun formattedHelpersAndEnumLabelsAreLocalized() {
        assertEquals(EnStrings, stringsFor(ResolvedLanguage.EN))
        assertEquals(ZhHansStrings, stringsFor(ResolvedLanguage.ZH_HANS))

        assertEquals("未知艺术家", ZhHansStrings.displayArtist("Unknown Artist"))
        assertEquals("Kano", ZhHansStrings.displayArtist("Kano"))
        assertEquals("未知专辑", ZhHansStrings.displayAlbum("Unknown Album"))
        assertEquals("队列 1", ZhHansStrings.displayQueueName("Queue 1"))
        assertEquals("My Mix", ZhHansStrings.displayQueueName("My Mix"))

        assertEquals("1 小时 30 分钟", ZhHansStrings.formatListeningTime(5400.0))
        assertEquals("1 h 30 min", EnStrings.formatListeningTime(5400.0))

        val now = 1_700_000_000_000L
        assertEquals("刚刚", ZhHansStrings.formatRelativeTime(now - 10_000L, nowMs = now))
        assertEquals("5 分钟前", ZhHansStrings.formatRelativeTime(now - 5 * 60_000L, nowMs = now))
        assertEquals("Just now", EnStrings.formatRelativeTime(now - 10_000L, nowMs = now))
        assertEquals("5 min ago", EnStrings.formatRelativeTime(now - 5 * 60_000L, nowMs = now))

        TrackSortOrder.entries.forEach { order ->
            assertTrue(EnStrings.sortOrderLabel(order).isNotBlank())
            assertTrue(ZhHansStrings.sortOrderLabel(order).isNotBlank())
            assertNotEquals(EnStrings.sortOrderLabel(order), ZhHansStrings.sortOrderLabel(order))
        }
        SearchField.entries.forEach { field ->
            assertTrue(EnStrings.searchFieldLabel(field).isNotBlank())
            assertTrue(ZhHansStrings.searchFieldLabel(field).isNotBlank())
            assertNotEquals(EnStrings.searchFieldLabel(field), ZhHansStrings.searchFieldLabel(field))
        }
        LyricsSource.entries.forEach { source ->
            assertTrue(EnStrings.lyricsSourceBadge(source).isNotBlank())
            assertTrue(ZhHansStrings.lyricsSourceBadge(source).isNotBlank())
            assertNotEquals(EnStrings.lyricsSourceBadge(source), ZhHansStrings.lyricsSourceBadge(source))
        }
        AppThemeMode.entries.forEach { mode ->
            assertTrue(EnStrings.themeModeLabel(mode).isNotBlank())
            assertTrue(ZhHansStrings.themeModeLabel(mode).isNotBlank())
            assertNotEquals(EnStrings.themeModeLabel(mode), ZhHansStrings.themeModeLabel(mode))
        }
        BackgroundColorPreset.entries.forEach { preset ->
            assertTrue(EnStrings.backgroundPresetLabel(preset).isNotBlank())
            assertTrue(ZhHansStrings.backgroundPresetLabel(preset).isNotBlank())
            assertNotEquals(EnStrings.backgroundPresetLabel(preset), ZhHansStrings.backgroundPresetLabel(preset))
        }
        ForegroundColorPreset.entries.forEach { preset ->
            assertTrue(EnStrings.foregroundPresetLabel(preset).isNotBlank())
            assertTrue(ZhHansStrings.foregroundPresetLabel(preset).isNotBlank())
            assertNotEquals(EnStrings.foregroundPresetLabel(preset), ZhHansStrings.foregroundPresetLabel(preset))
        }

        assertTrue(EnStrings.cloudSyncProgress(2, 5, 14).contains("2/5"))
        assertTrue(ZhHansStrings.cloudSyncProgress(2, 5, 14).contains("2/5"))
        assertNotEquals(EnStrings.cloudSyncProgress(2, 5, 14), ZhHansStrings.cloudSyncProgress(2, 5, 14))

        assertEquals("1 queue", EnStrings.queueCountLabel(1))
        assertEquals("3 queues", EnStrings.queueCountLabel(3))
        assertEquals("3 个队列", ZhHansStrings.queueCountLabel(3))
        assertNotEquals(EnStrings.queueCountLabel(3), ZhHansStrings.queueCountLabel(3))

        assertEquals("v1.2.0 · Musico", EnStrings.extensionVersionAuthor("1.2.0", "Musico"))
        assertEquals("v1.2.0 · Musico", ZhHansStrings.extensionVersionAuthor("1.2.0", "Musico"))
        assertEquals("v1.2.0", EnStrings.extensionVersionAuthor("1.2.0", null))
        assertEquals("v1.2.0", ZhHansStrings.extensionVersionAuthor("1.2.0", null))

        val formattedPairs = listOf(
            EnStrings.extensionMissingBanner("ext.demo") to ZhHansStrings.extensionMissingBanner("ext.demo"),
            EnStrings.badgeCustomLocal("Fixture") to ZhHansStrings.badgeCustomLocal("Fixture"),
            EnStrings.uninstallExtensionConfirmTitle("Demo Ext") to ZhHansStrings.uninstallExtensionConfirmTitle("Demo Ext"),
            EnStrings.detectedProviderHint("Demo Ext") to ZhHansStrings.detectedProviderHint("Demo Ext"),
            EnStrings.extensionLogsCountLabel(12) to ZhHansStrings.extensionLogsCountLabel(12),
            EnStrings.cloudSearchPlaceholder("NetEase") to ZhHansStrings.cloudSearchPlaceholder("NetEase"),
            EnStrings.cloudSearchIdleTitle("NetEase") to ZhHansStrings.cloudSearchIdleTitle("NetEase"),
            EnStrings.addedFolderNotice("Mix") to ZhHansStrings.addedFolderNotice("Mix"),
            EnStrings.albumCount(1) to ZhHansStrings.albumCount(1),
            EnStrings.albumCount(3) to ZhHansStrings.albumCount(3),
            EnStrings.libraryExploredSummary("42%", 5, 12) to ZhHansStrings.libraryExploredSummary("42%", 5, 12),
            EnStrings.cloudUrlSupportingTextFor(emptyList()) to ZhHansStrings.cloudUrlSupportingTextFor(emptyList()),
            EnStrings.cloudUrlSupportingTextFor(listOf("YouTube Search")) to ZhHansStrings.cloudUrlSupportingTextFor(listOf("YouTube Search")),
            EnStrings.addYourMusicFoldersDescFor(listOf("YouTube Search", "Bilibili Search")) to ZhHansStrings.addYourMusicFoldersDescFor(listOf("YouTube Search", "Bilibili Search")),
            EnStrings.foldersFooterHintFor(listOf("YouTube Search")) to ZhHansStrings.foldersFooterHintFor(listOf("YouTube Search")),
            EnStrings.errorUnsupportedCloudUrlFor(emptyList()) to ZhHansStrings.errorUnsupportedCloudUrlFor(emptyList()),
            EnStrings.errorUnsupportedCloudUrlFor(listOf("YouTube Search")) to ZhHansStrings.errorUnsupportedCloudUrlFor(listOf("YouTube Search")),
        )
        assertTrue(EnStrings.cloudUrlSupportingTextFor(emptyList()).contains("Google Drive"))
        assertTrue(EnStrings.cloudUrlSupportingTextFor(emptyList()).contains("Extensions"))
        assertFalse(EnStrings.cloudUrlSupportingTextFor(emptyList()).contains("YouTube"))
        assertFalse(EnStrings.cloudUrlSupportingTextFor(emptyList()).contains("Bilibili"))
        assertTrue(EnStrings.cloudUrlSupportingTextFor(listOf("YouTube Search", "Bilibili Search")).contains("YouTube Search"))
        assertTrue(ZhHansStrings.cloudUrlSupportingTextFor(listOf("YouTube Search")).contains("Google Drive"))
        formattedPairs.forEach { (en, zh) ->
            assertTrue("EN formatted string blank: $en", en.isNotBlank())
            assertTrue("ZH formatted string blank: $zh", zh.isNotBlank())
            assertNotEquals("EN and ZH formatted strings should differ: $en", en, zh)
        }
    }
}
